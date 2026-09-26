package gold.debug.windowstolinux.app.db.persistence.repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.entity.StoredAiProviderConfiguration;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.persistence.connection.RepositoryConnections;
import gold.debug.windowstolinux.shared.model.ai.*;

/** Stores model connection, capability and routing state in one row. / 在一行中保存模型连接、能力及路由状态。 */
public final class AiProfileRepository {
    /** Scoped database connection factory. / 限定数据库连接工厂。
     */
    private final RepositoryConnections connections;

    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param connections connections supplied to this boundary / 传入当前边界的connections值
     */
    public AiProfileRepository(DesktopConnectionFactory connections) {
        this(connections.scoped());
    }

    /** Binds owned platform connections and repository transactions. / 绑定所属平台连接和仓库事务。
     * @param connections platform transaction scope / 平台事务作用域
     */
    public AiProfileRepository(RepositoryConnections connections) {
        this.connections = java.util.Objects.requireNonNull(connections);
    }

    /** Saves model connection settings without inventing capability evidence. / 保存模型连接设置，不生成能力验证证据。
     * @param profile profile supplied to this boundary / 传入当前边界的profile值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void saveNamed(StoredAiProviderProfile profile) throws SQLException {
        saveConfiguration(profile, profile.id(), null, AiCapabilityType.TEXT);
    }

    /** Saves model settings and the corresponding successful capability probe. / 保存模型设置及对应的成功能力验证。
     * @param profile profile supplied to this boundary / 传入当前边界的profile值
     * @param name name supplied to this boundary / 传入当前边界的name值
     * @param verifiedAt verified at supplied to this boundary / 传入当前边界的verifiedAt值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void saveVerified(StoredAiProviderProfile profile, String name, Instant verifiedAt) throws SQLException {
        saveVerified(profile, name, verifiedAt, AiCapabilityType.TEXT);
    }

    /** Saves model settings and the corresponding successful capability probe. / 保存模型设置及对应的成功能力验证。
     * @param profile profile supplied to this boundary / 传入当前边界的profile值
     * @param name name supplied to this boundary / 传入当前边界的name值
     * @param verifiedAt verified at supplied to this boundary / 传入当前边界的verifiedAt值
     * @param capability capability supplied to this boundary / 传入当前边界的capability值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void saveVerified(StoredAiProviderProfile profile, String name, Instant verifiedAt,
            AiCapabilityType capability) throws SQLException {
        saveConfiguration(profile, name, Objects.requireNonNull(verifiedAt), capability);
    }

    /** Updates model connection identity and revision atomically. / 原子更新模型连接身份及修订。
     * @param profile profile supplied to this boundary / 传入当前边界的profile值
     * @param name name supplied to this boundary / 传入当前边界的name值
     * @param verifiedAt verified at supplied to this boundary / 传入当前边界的verifiedAt值
     * @param capability capability supplied to this boundary / 传入当前边界的capability值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private void saveConfiguration(StoredAiProviderProfile profile, String name, Instant verifiedAt,
            AiCapabilityType capability) throws SQLException {
        new StoredAiProviderConfiguration(profile, name, 0, 1, Optional.empty(), Optional.empty());
        connections.transaction(() -> {
            try (var c = connections.open()) {
                int changed;
                try (var update = c.prepareStatement(
                        "UPDATE ai_model SET configuration_revision=configuration_revision+CASE WHEN credential_id<>? OR endpoint_url<>? OR model_name<>? THEN 1 ELSE 0 END,credential_id=?,endpoint_url=?,model_name=?,display_name=? WHERE model_id=?")) {
                    update.setString(1, profile.credentialKey());
                    update.setString(2, profile.endpoint());
                    update.setString(3, profile.model());
                    update.setString(4, profile.credentialKey());
                    update.setString(5, profile.endpoint());
                    update.setString(6, profile.model());
                    update.setString(7, name.trim());
                    update.setString(8, profile.id());
                    changed = update.executeUpdate();
                }
                if (changed == 0)
                    try (var insert = c.prepareStatement(
                            "INSERT INTO ai_model(model_id,credential_id,endpoint_url,model_name,display_name,display_order) VALUES(?,?,?,?,?,(SELECT COALESCE(MAX(display_order),-1)+1 FROM ai_model))")) {
                        insert.setString(1, profile.id());
                        insert.setString(2, profile.credentialKey());
                        insert.setString(3, profile.endpoint());
                        insert.setString(4, profile.model());
                        insert.setString(5, name.trim());
                        insert.executeUpdate();
                    }
                if (verifiedAt != null)
                    writeVerification(c, profile.id(), capability, verifiedAt);
            }
        });
    }

    /** Accepts probe evidence only for the unchanged expected configuration revision. / 仅对未改变的预期配置修订接受验证证据。
     * @param expected expected supplied to this boundary / 传入当前边界的expected值
     * @param expectedRevision configuration revision captured before the probe / 验证前取得的配置修订
     * @param capability capability supplied to this boundary / 传入当前边界的capability值
     * @param verifiedAt verified at supplied to this boundary / 传入当前边界的verifiedAt值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void recordVerification(StoredAiProviderProfile expected, long expectedRevision, AiCapabilityType capability,
            Instant verifiedAt) throws SQLException {
        connections.transaction(() -> {
            try (var c = connections.open(); var s = c.prepareStatement("SELECT * FROM ai_model WHERE model_id=?")) {
                s.setString(1, expected.id());
                try (var rows = s.executeQuery()) {
                    if (!rows.next() || rows.getLong("configuration_revision") != expectedRevision
                            || !provider(rows).equals(expected))
                        throw new SQLException("model changed during capability probe");
                }
                writeVerification(c, expected.id(), capability, Objects.requireNonNull(verifiedAt));
            }
        });
    }

    /** Records successful capability evidence for the current model revision. / 为当前模型修订记录成功能力验证。
     * @param c c supplied to this boundary / 传入当前边界的c值
     * @param id stable persisted identifier / 稳定持久化标识
     * @param capability capability supplied to this boundary / 传入当前边界的capability值
     * @param time time supplied to this boundary / 传入当前边界的time值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private static void writeVerification(Connection c, String id, AiCapabilityType capability, Instant time)
            throws SQLException {
        String prefix = capability == AiCapabilityType.VISION ? "vision" : "text";
        try (var s = c.prepareStatement("UPDATE ai_model SET " + prefix + "_verified_revision=configuration_revision,"
                + prefix + "_verified_at=? WHERE model_id=?")) {
            s.setLong(1, time.toEpochMilli());
            s.setString(2, id);
            if (s.executeUpdate() != 1)
                throw new SQLException("model no longer exists");
        }
    }

    /** Lists model inventory in display order with revision-bound evidence. / 按展示顺序列出模型库存及绑定修订的证据。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public List<StoredAiProviderConfiguration> listConfigured() throws SQLException {
        try (var c = connections.open();
                var s = c.prepareStatement("SELECT * FROM ai_model ORDER BY display_order");
                var rows = s.executeQuery()) {
            var values = new ArrayList<StoredAiProviderConfiguration>();
            while (rows.next())
                values.add(configuration(rows));
            return List.copyOf(values);
        }
    }

    /** Returns enabled and currently verified models in the selected purpose order. / 按用途顺序返回已启用且当前验证有效的模型。
     * @param purpose purpose supplied to this boundary / 传入当前边界的purpose值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public List<StoredAiProviderConfiguration> configuredFor(AiPurposeType purpose) throws SQLException {
        return purposeSnapshot().get(Objects.requireNonNull(purpose));
    }

    /** Captures independent ordered model chains for all purposes. / 取得所有用途的独立有序模型调用链。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public Map<AiPurposeType, List<StoredAiProviderConfiguration>> purposeSnapshot() throws SQLException {
        var grouped = new EnumMap<AiPurposeType, TreeMap<Integer, StoredAiProviderConfiguration>>(AiPurposeType.class);
        for (var purpose : AiPurposeType.values())
            grouped.put(purpose, new TreeMap<>());
        try (var c = connections.open();
                var s = c.prepareStatement("SELECT * FROM ai_model");
                var rows = s.executeQuery()) {
            while (rows.next()) {
                var value = configuration(rows);
                for (var purpose : AiPurposeType.values()) {
                    String prefix = AiPurposeRepository.prefix(purpose);
                    if (rows.getBoolean("is_" + prefix + "_enabled") && rows.getObject(prefix + "_call_order") != null
                            && value.verified(purpose))
                        grouped.get(purpose).put(rows.getInt(prefix + "_call_order"), value);
                }
            }
        }
        var result = new EnumMap<AiPurposeType, List<StoredAiProviderConfiguration>>(AiPurposeType.class);
        grouped.forEach((purpose, rows) -> result.put(purpose, List.copyOf(rows.values())));
        return Map.copyOf(result);
    }

    /** Returns the repository for independent purpose routing settings. / 返回独立用途路由设置仓库。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public AiPurposeRepository purposes() {
        return new AiPurposeRepository(connections);
    }

    /**
     * Saves an explicit model output budget using existing platform settings. / 使用已有平台设置保存明确模型输出预算。
     * @param id existing model identity / 已有模型身份
     * @param tokens positive output token ceiling / 正的输出 Token 上限
     * @throws SQLException if the model or transaction is unavailable / 模型或事务不可用时
     */
    public void saveOutputLimit(String id, int tokens) throws SQLException {
        if (tokens < 1)
            throw new IllegalArgumentException("Model output limit must be positive");
        String key = outputLimitKey(id);
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                RepositoryConnections.lockPlatformState(connection);
                try (var statement = connection
                        .prepareStatement("SELECT model_id FROM ai_model WHERE model_id=? FOR UPDATE")) {
                    statement.setString(1, id);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next())
                            throw new SQLException("Model is absent", "02000");
                    }
                }
                new DesktopPreferenceRepository(connections).save(key, Integer.toString(tokens));
            }
        });
    }

    /** Reads a configured ceiling without inventing a provider capability. / 读取已配置上限，不虚构提供者能力。
     * @param id existing model identity / 已有模型身份
     * @return explicit limit or absent / 明确上限或缺失
     * @throws SQLException if configuration is unreadable or malformed / 配置不可读或格式无效时 */
    public OptionalInt outputLimit(String id) throws SQLException {
        var saved = new DesktopPreferenceRepository(connections).find(outputLimitKey(id));
        if (saved.isEmpty())
            return OptionalInt.empty();
        try {
            int value = Integer.parseInt(saved.orElseThrow());
            if (value < 1 || !Integer.toString(value).equals(saved.orElseThrow()))
                throw new NumberFormatException();
            return OptionalInt.of(value);
        } catch (NumberFormatException invalid) {
            throw new SQLException("Invalid stored model output limit", "55000");
        }
    }

    /**
     * Locks and revalidates a selected revision before the caller captures its credential. / 调用方取得凭据前锁定并重新核验所选修订。
     * @param expected captured model revision / 已捕获模型修订
     * @param purpose selected purpose / 所选用途
     * @throws SQLException if the model changed or the caller has no owning transaction / 模型已变化或调用方无所属事务时
     */
    public void requireInvocation(StoredAiProviderConfiguration expected, AiPurposeType purpose) throws SQLException {
        try (var connection = connections.open()) {
            if (connection.getAutoCommit())
                throw new SQLException("Model capture requires a transaction");
            try (var statement = connection.prepareStatement("SELECT * FROM ai_model WHERE model_id=? FOR UPDATE")) {
                statement.setString(1, expected.profile().id());
                try (var rows = statement.executeQuery()) {
                    String prefix = AiPurposeRepository.prefix(purpose);
                    if (!rows.next() || !configuration(rows).profile().equals(expected.profile())
                            || rows.getLong("configuration_revision") != expected.revision()
                            || !configuration(rows).verified(purpose) || !rows.getBoolean("is_" + prefix + "_enabled")
                            || rows.getObject(prefix + "_call_order") == null)
                        throw new SQLException("Captured model configuration is no longer available", "55000");
                }
            }
        }
    }

    /** Maps valid model IDs injectively into the platform preference namespace. / 将合法模型标识单射映射到平台偏好命名空间。
     * @param id exact model identity / 精确模型身份
     * @return settings key / 设置键 */
    private static String outputLimitKey(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid model identity");
        return "ai_model_output_limit_" + id.replace('-', '_');
    }

    /** Reorders inventory through nonconflicting temporary positions in one transaction. / 在同一事务借助无冲突临时位置调整库存顺序。
     * @param ids ids supplied to this boundary / 传入当前边界的ids值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void reorder(List<String> ids) throws SQLException {
        var order = List.copyOf(ids);
        connections.transaction(() -> {
            var saved = listNamed().stream().map(StoredAiProviderProfile::id)
                    .collect(java.util.stream.Collectors.toSet());
            if (order.size() != saved.size() || !saved.equals(new HashSet<>(order)))
                throw new SQLException("inventory order changed");
            try (var c = connections.open(); var max = c.createStatement()) {
                long offset;
                try (var rows = max.executeQuery("SELECT COALESCE(MAX(display_order),-1)+1 FROM ai_model")) {
                    rows.next();
                    offset = rows.getLong(1);
                }
                try (var update = c.prepareStatement("UPDATE ai_model SET display_order=? WHERE model_id=?")) {
                    for (int i = 0; i < order.size(); i++) {
                        update.setLong(1, offset + i);
                        update.setString(2, order.get(i));
                        update.executeUpdate();
                    }
                    for (int i = 0; i < order.size(); i++) {
                        update.setInt(1, i);
                        update.setString(2, order.get(i));
                        update.executeUpdate();
                    }
                }
            }
        });
    }

    /** Lists connection profiles in display order. / 按展示顺序列出连接配置。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public List<StoredAiProviderProfile> listNamed() throws SQLException {
        return listConfigured().stream().map(StoredAiProviderConfiguration::profile).toList();
    }

    /** Finds one model connection profile by identifier. / 按标识查找模型连接配置。
     * @param id stable persisted identifier / 稳定持久化标识
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public Optional<StoredAiProviderProfile> findNamed(String id) throws SQLException {
        try (var c = connections.open(); var s = c.prepareStatement("SELECT * FROM ai_model WHERE model_id=?")) {
            s.setString(1, id);
            try (var rows = s.executeQuery()) {
                return rows.next() ? Optional.of(provider(rows)) : Optional.empty();
            }
        }
    }

    /** Decodes connection identity from the current model row. / 从当前模型行解码连接身份。
     * @param rows rows supplied to this boundary / 传入当前边界的rows值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private static StoredAiProviderProfile provider(ResultSet rows) throws SQLException {
        return new StoredAiProviderProfile(rows.getString("model_id"), rows.getString("endpoint_url"),
                rows.getString("model_name"), rows.getString("credential_id"));
    }

    /** Decodes model settings and only revision-valid capability evidence. / 解码模型设置及修订有效的能力证据。
     * @param rows rows supplied to this boundary / 传入当前边界的rows值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private static StoredAiProviderConfiguration configuration(ResultSet rows) throws SQLException {
        return new StoredAiProviderConfiguration(provider(rows), rows.getString("display_name"),
                rows.getInt("display_order"), rows.getLong("configuration_revision"), verifiedAt(rows, "text"),
                verifiedAt(rows, "vision"));
    }

    /** Returns capability time only when its revision matches the current configuration. / 仅当能力修订与当前配置一致时返回验证时间。
     * @param rows rows supplied to this boundary / 传入当前边界的rows值
     * @param capability capability supplied to this boundary / 传入当前边界的capability值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private static Optional<Instant> verifiedAt(ResultSet rows, String capability) throws SQLException {
        return rows.getObject(capability + "_verified_revision") != null
                && rows.getLong(capability + "_verified_revision") == rows.getLong("configuration_revision")
                        ? Optional.of(Instant.ofEpochMilli(rows.getLong(capability + "_verified_at")))
                        : Optional.empty();
    }
}
