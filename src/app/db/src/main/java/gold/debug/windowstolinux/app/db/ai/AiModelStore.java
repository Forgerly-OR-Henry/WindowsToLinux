package gold.debug.windowstolinux.app.db.ai;

import gold.debug.windowstolinux.app.db.setting.DesktopPreferenceStore;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.ai.StoredAiModelInfo;
import gold.debug.windowstolinux.app.db.ai.StoredAiModelProfile;
import gold.debug.windowstolinux.app.db.connect.StoreConnections;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityCheck;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityStatus;
import gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel;
import gold.debug.windowstolinux.app.ai.model.ModelProtocol;

/** Stores model connection, capability and routing state in one row. / 在一行中保存模型连接、能力及路由状态。 */
public final class AiModelStore {
    /** Scoped database connection factory. / 限定数据库连接工厂。
     */
    private final StoreConnections connections;

    /** Binds owned platform connections and repository transactions. / 绑定所属平台连接和仓库事务。
     * @param connections platform transaction scope / 平台事务作用域
     */
    public AiModelStore(StoreConnections connections) {
        this.connections = java.util.Objects.requireNonNull(connections);
    }

    /** Saves settings without inventing capability evidence. / 保存设置，不生成能力证据。
     * @param profile non-secret model settings / 非秘密模型设置
     * @throws SQLException if storage fails / 保存失败时 */
    public void saveNamed(StoredAiModelProfile profile) throws SQLException {
        saveConfiguration(profile, profile.id());
    }

    /** Saves one model while invalidating only affected capabilities. / 保存模型，仅撤销受影响能力。
     * @param profile exact protocol settings / 精确协议设置
     * @param name display label / 显示名称
     * @throws SQLException if validation or commit fails / 校验或提交失败时 */
    public void saveConfiguration(StoredAiModelProfile profile, String name) throws SQLException {
        new StoredAiModelInfo(profile, name, 0, 1, Optional.empty(), Optional.empty());
        connections.transaction(() -> {
            try (var c = connections.open()) {
                StoreConnections.lockPlatformState(c);
                var previous = listConfigured().stream().filter(v -> v.profile().id().equals(profile.id())).findFirst();
                long revision = previous.map(v -> v.revision() + (v.profile().equals(profile) ? 0 : 1)).orElse(1L);
                var checks = new EnumMap<AiCapabilityType, AiCapabilityCheck>(AiCapabilityType.class);
                for (var capability : AiCapabilityType.values())
                    checks.put(capability, previous.filter(v -> unaffected(v.profile(), profile, capability))
                        .map(v -> v.checks().get(capability)).orElseGet(AiCapabilityCheck::unverified));
                if (previous.isEmpty()) {
                    try (var insert = c.prepareStatement("INSERT INTO ai_model(model_id,credential_id,endpoint_url,"
                        + "model_name,display_name,display_order) VALUES(?,?,?,?,?,"
                        + "(SELECT COALESCE(MAX(display_order),-1)+1 FROM ai_model))")) {
                        insert.setString(1, profile.id());
                        insert.setString(2, profile.credentialKey());
                        insert.setString(3, profile.endpoint());
                        insert.setString(4, profile.model());
                        insert.setString(5, name.strip());
                        insert.executeUpdate();
                    }
                } else {
                    try (var update = c.prepareStatement("UPDATE ai_model SET configuration_revision=?,credential_id=?,"
                        + "endpoint_url=?,model_name=?,display_name=? WHERE model_id=?")) {
                        update.setLong(1, revision);
                        update.setString(2, profile.credentialKey());
                        update.setString(3, profile.endpoint());
                        update.setString(4, profile.model());
                        update.setString(5, name.strip());
                        update.setString(6, profile.id());
                        update.executeUpdate();
                    }
                }
                new DesktopPreferenceStore(connections).save(reasoningEffortKey(profile.id()), profile.reasoningEffort().name());
                new DesktopPreferenceStore(connections).save(protocolKey(profile.id()), profile.protocol().name());
                for (var capability : AiCapabilityType.values())
                    writeVerification(c, profile.id(), capability, checks.get(capability));
            }
        });
    }

    /** Determines whether a protocol's connection identity is unchanged. / 判断协议连接身份是否未变。
     * @param old previous settings / 旧设置
     * @param current proposed settings / 新设置
     * @param capability actual protocol / 实际协议
     * @return whether prior evidence remains applicable / 原证据是否仍适用 */
    private static boolean unaffected(StoredAiModelProfile old, StoredAiModelProfile current,
                                      AiCapabilityType capability) {
        return old.equals(current);
    }

    /** Saves independently verified text metadata. / 保存独立验证的文字元数据。
     * @param profile settings / 设置
     * @param name label / 名称
     * @param verifiedAt actual verification / 实际验证
     * @throws SQLException if commit fails / 提交失败时 */
    public void saveVerified(StoredAiModelProfile profile, String name, Instant verifiedAt) throws SQLException {
        saveVerified(profile, name, verifiedAt, AiCapabilityType.TEXT);
    }

    /** Saves externally verified evidence for one capability. / 保存外部已核实的单项能力证据。
     * @param profile settings / 设置
     * @param name label / 名称
     * @param verifiedAt actual check time / 实际检查时间
     * @param capability checked capability / 已检查能力
     * @throws SQLException if commit fails / 提交失败时 */
    public void saveVerified(StoredAiModelProfile profile, String name, Instant verifiedAt,
                             AiCapabilityType capability) throws SQLException {
        connections.transaction(() -> {
            saveConfiguration(profile, name);
            try (var c = connections.open()) {
                writeVerification(c, profile.id(), capability,
                    new AiCapabilityCheck(AiCapabilityStatus.AVAILABLE, "", Optional.of(verifiedAt)));
            }
        });
    }

    /** Records successful evidence only against its original revision. / 仅为原修订记录成功证据。
     * @param expected captured settings / 已捕获设置
     * @param expectedRevision original revision / 原修订
     * @param capability actual capability / 实际能力
     * @param verifiedAt actual probe time / 实际验证时间
     * @throws SQLException if configuration changed or storage failed / 配置变化或存储失败时 */
    public void recordVerification(StoredAiModelProfile expected, long expectedRevision,
                                   AiCapabilityType capability, Instant verifiedAt) throws SQLException {
        recordVerification(expected, expectedRevision, capability,
            new AiCapabilityCheck(AiCapabilityStatus.AVAILABLE, "", Optional.of(verifiedAt)));
    }

    /** Records safe independent outcomes for an unchanged configuration. / 为未变化配置记录安全独立结果。
     * @param expected original protocol settings / 原协议设置
     * @param expectedRevision original revision / 原修订
     * @param capability actual capability / 实际能力
     * @param check safe classified result / 安全分类结果
     * @throws SQLException if the probe became stale or commit failed / 验证过期或提交失败时 */
    public void recordVerification(StoredAiModelProfile expected, long expectedRevision,
                                   AiCapabilityType capability, AiCapabilityCheck check) throws SQLException {
        connections.transaction(() -> {
            try (var c = connections.open()) {
                StoreConnections.lockPlatformState(c);
                try (var statement = c.prepareStatement("SELECT * FROM ai_model WHERE model_id=?")) {
                    statement.setString(1, expected.id());
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next() || rows.getLong("configuration_revision") != expectedRevision
                            || !provider(rows).equals(expected))
                            throw new SQLException("Model changed during capability probe", "40001");
                    }
                }
                writeVerification(c, expected.id(), capability, check);
            }
        });
    }

    /** Maps a capability to a fixed database column family. / 将能力映射到固定数据库列组。
     * @param capability input capability / 输入能力
     * @return fixed safe SQL prefix / 固定安全SQL前缀 */
    static String capabilityPrefix(AiCapabilityType capability) {
        return switch (Objects.requireNonNull(capability)) {
            case TEXT -> "text";
            case TOOL_CALLING ->
                throw new IllegalArgumentException("Native tool evidence uses revision-bound preferences");
            case VISION -> "vision";
            case VOICE_FILE -> "voice_file";
            case VOICE_REALTIME -> "voice_realtime";
        };
    }

    /** Writes current revision evidence, including absence and failures. / 写入当前修订证据，包括未验证及失败。
     * @param c transaction connection / 事务连接
     * @param id model identity / 模型身份
     * @param capability selected capability / 所选能力
     * @param check safe evidence / 安全证据
     * @throws SQLException if storage fails / 保存失败时 */
    private static void writeVerification(Connection c, String id, AiCapabilityType capability, AiCapabilityCheck check)
        throws SQLException {
        if (capability == AiCapabilityType.TOOL_CALLING) {
            long revision;
            try (var q = c.prepareStatement("SELECT configuration_revision FROM ai_model WHERE model_id=?")) {
                q.setString(1, id);
                try (var r = q.executeQuery()) {
                    if (!r.next()) throw new SQLException("Model no longer exists");
                    revision = r.getLong(1);
                }
            }
            try (var q = c.prepareStatement("MERGE INTO desktop_preference(preference_key,preference_value) KEY(preference_key) VALUES(?,?)")) {
                q.setString(1, toolCheckKey(id));
                q.setString(2, revision + "," + check.state() + "," + check.checkedAt().map(Instant::toEpochMilli).orElse(-1L) + "," + check.reason());
                q.executeUpdate();
            }
            return;
        }
        String prefix = capabilityPrefix(capability);
        String revision = check.checkedAt().isPresent() ? "configuration_revision" : "NULL";
        try (var statement = c.prepareStatement("UPDATE ai_model SET " + prefix + "_verified_revision=" + revision
            + "," + prefix + "_verified_at=?," + prefix + "_state=?," + prefix + "_reason=? WHERE model_id=?")) {
            if (check.checkedAt().isPresent()) statement.setLong(1, check.checkedAt().orElseThrow().toEpochMilli());
            else statement.setNull(1, Types.BIGINT);
            statement.setString(2, check.state().name());
            statement.setString(3, check.reason());
            statement.setString(4, id);
            if (statement.executeUpdate() != 1) throw new SQLException("Model no longer exists");
        }
    }

    /** Lists model inventory in display order with revision-bound evidence. / 按展示顺序列出模型库存及绑定修订的证据。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public List<StoredAiModelInfo> listConfigured() throws SQLException {
        try (var c = connections.open();
             var s = c.prepareStatement("SELECT * FROM ai_model ORDER BY display_order");
             var rows = s.executeQuery()) {
            var values = new ArrayList<StoredAiModelInfo>();
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
    public List<StoredAiModelInfo> configuredFor(AiPurposeType purpose) throws SQLException {
        return purposeSnapshot().get(Objects.requireNonNull(purpose));
    }

    /** Captures independent ordered model chains for all purposes. / 取得所有用途的独立有序模型调用链。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public Map<AiPurposeType, List<StoredAiModelInfo>> purposeSnapshot() throws SQLException {
        var grouped = new EnumMap<AiPurposeType, TreeMap<Integer, StoredAiModelInfo>>(AiPurposeType.class);
        for (var purpose : AiPurposeType.values())
            grouped.put(purpose, new TreeMap<>());
        try (var c = connections.open();
             var s = c.prepareStatement("SELECT * FROM ai_model");
             var rows = s.executeQuery()) {
            while (rows.next()) {
                var value = configuration(rows);
                for (var purpose : AiPurposeType.values()) {
                    String prefix = AiPurposeStore.prefix(purpose);
                    if (rows.getObject(prefix + "_call_order") != null
                        && value.verified(purpose))
                        grouped.get(purpose).put(rows.getInt(prefix + "_call_order"), value);
                }
            }
        }
        var result = new EnumMap<AiPurposeType, List<StoredAiModelInfo>>(AiPurposeType.class);
        grouped.forEach((purpose, rows) -> result.put(purpose, List.copyOf(rows.values())));
        return Map.copyOf(result);
    }

    /** Captures all routes under one platform configuration lock. / 在同一平台配置锁下捕获全部路由。
     * @return one immutable routing state / 一份不可变路由状态
     * @throws SQLException if the state cannot be read / 状态无法读取时 */
    public RoutingState routingState() throws SQLException {
        RoutingState[] result = new RoutingState[1];
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                StoreConnections.lockPlatformState(connection);
                var routes = purposeSnapshot();
                var inventory = new HashMap<String, StoredAiModelInfo>();
                for (var configuration : listConfigured())
                    inventory.put(configuration.profile().id(), configuration);
                var members = new EnumMap<AiPurposeType, List<RoutingMember>>(AiPurposeType.class);
                for (var purpose : AiPurposeType.values()) {
                    var selected = new ArrayList<RoutingMember>();
                    for (var assignment : purposes().list(purpose)) {
                        var configuration = inventory.get(assignment.profileId());
                        if (configuration == null)
                            throw new SQLException("Purpose route names a missing model");
                        selected.add(new RoutingMember(configuration));
                    }
                    members.put(purpose, List.copyOf(selected));
                }
                result[0] = new RoutingState(routes, Map.copyOf(members));
            }
        });
        return result[0];
    }

    /** One ordered purpose member; eligibility remains independently verified. / 一个有序用途成员，调用资格仍独立核验。
     * @param configuration exact current model configuration / 精确当前模型配置 */
    public record RoutingMember(StoredAiModelInfo configuration) {
    }

    /** One transaction-consistent AI routing view. / 一份事务一致的 AI 路由视图。
     * @param routes purpose-ordered model chains / 按用途排序的模型链
     * @param members all ordered assignments including invalid members / 全部有序分配，包括失效成员
     */
    public record RoutingState(Map<AiPurposeType, List<StoredAiModelInfo>> routes,
                               Map<AiPurposeType, List<RoutingMember>> members) {
    }

    /** Returns the repository for independent purpose routing settings. / 返回独立用途路由设置仓库。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    public AiPurposeStore purposes() {
        return new AiPurposeStore(connections);
    }

    /**
     * Retains the legacy editor value without changing invocation or verification. / 保留旧编辑器值，不改变调用或能力验证。
     * @param id existing model identity / 已有模型身份
     * @param tokens positive legacy editor value / 正的旧编辑器值
     * @throws SQLException if the model or transaction is unavailable / 模型或事务不可用时
     */
    public void saveOutputLimit(String id, int tokens) throws SQLException {
        if (tokens < 1)
            throw new IllegalArgumentException("Model output limit must be positive");
        String key = outputLimitKey(id);
        connections.transaction(() -> {
            try (var connection = connections.open()) {
                StoreConnections.lockPlatformState(connection);
                try (var statement = connection
                    .prepareStatement("SELECT model_id FROM ai_model WHERE model_id=? FOR UPDATE")) {
                    statement.setString(1, id);
                    try (var rows = statement.executeQuery()) {
                        if (!rows.next())
                            throw new SQLException("Model is absent", "02000");
                    }
                }
                new DesktopPreferenceStore(connections).save(key, Integer.toString(tokens));
            }
        });
    }

    /** Reads the retained legacy editor value, independent of invocation policy. / 读取保留的旧编辑器值，与调用策略独立。
     * @param id existing model identity / 已有模型身份
     * @return retained value or absent / 保留值或缺失
     * @throws SQLException if configuration is unreadable or malformed / 配置不可读或格式无效时 */
    public OptionalInt outputLimit(String id) throws SQLException {
        var saved = new DesktopPreferenceStore(connections).find(outputLimitKey(id));
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
    public void requireInvocation(StoredAiModelInfo expected, AiPurposeType purpose) throws SQLException {
        try (var connection = connections.open()) {
            if (connection.getAutoCommit())
                throw new SQLException("Model capture requires a transaction");
            try (var statement = connection.prepareStatement("SELECT * FROM ai_model WHERE model_id=? FOR UPDATE")) {
                statement.setString(1, expected.profile().id());
                try (var rows = statement.executeQuery()) {
                    String prefix = AiPurposeStore.prefix(purpose);
                    if (!rows.next() || !configuration(rows).profile().equals(expected.profile())
                        || rows.getLong("configuration_revision") != expected.revision()
                        || !configuration(rows).verified(purpose)
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

    /** Maps reasoning configuration to the existing non-secret preference store. / 将推理配置映射到已有非秘密偏好仓库。
     * @param id exact model identity / 精确模型身份
     * @return preference key / 偏好键 */
    private static String reasoningEffortKey(String id) {
        outputLimitKey(id);
        return "ai_model_reasoning_effort_" + id.replace('-', '_');
    }

    private static String protocolKey(String id) {
        outputLimitKey(id);
        return "ai_model_protocol_" + id.replace('-', '_');
    }

    private static String toolCheckKey(String id) {
        outputLimitKey(id);
        return "ai_model_tool_check_" + id.replace('-', '_');
    }

    /** Reorders inventory through nonconflicting temporary positions in one transaction. / 在同一事务借助无冲突临时位置调整库存顺序。
     * @param ids ids supplied to this boundary / 传入当前边界的ids值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void reorder(List<String> ids) throws SQLException {
        reorder(listNamed().stream().map(StoredAiModelProfile::id).toList(), ids);
    }

    /** Reorders only if the confirmed inventory still matches. / 仅在已确认清单仍匹配时排序。 */
    public void reorder(List<String> expected, List<String> ids) throws SQLException {
        var baseline = List.copyOf(expected);
        var order = List.copyOf(ids);
        connections.transaction(() -> {
            try (var c = connections.open(); var max = c.createStatement()) {
                StoreConnections.lockPlatformState(c);
                var current = listNamed().stream().map(StoredAiModelProfile::id).toList();
                if (!current.equals(baseline)) throw new SQLException("Inventory order changed", "40001");
                var saved = new HashSet<>(current);
                if (order.size() != saved.size() || !saved.equals(new HashSet<>(order)))
                    throw new SQLException("inventory order changed");
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
    public List<StoredAiModelProfile> listNamed() throws SQLException {
        return listConfigured().stream().map(StoredAiModelInfo::profile).toList();
    }

    /** Finds one model connection profile by identifier. / 按标识查找模型连接配置。
     * @param id stable persisted identifier / 稳定持久化标识
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public Optional<StoredAiModelProfile> findNamed(String id) throws SQLException {
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
    private StoredAiModelProfile provider(ResultSet rows) throws SQLException {
        ModelReasoningLevel effort;
        try {
            effort = ModelReasoningLevel.valueOf(new DesktopPreferenceStore(connections)
                .find(reasoningEffortKey(rows.getString("model_id"))).orElse("DEFAULT"));
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Invalid stored model reasoning effort", "55000");
        }
        return new StoredAiModelProfile(rows.getString("model_id"), rows.getString("endpoint_url"), rows.getString("model_name"), rows.getString("credential_id"), effort, storedProtocol(rows.getString("model_id")));
    }

    private ModelProtocol storedProtocol(String id) throws SQLException {
        try {
            return ModelProtocol.valueOf(new DesktopPreferenceStore(connections).find(protocolKey(id))
                .orElseThrow(() -> new SQLException("Explicit model protocol is absent", "55000")));
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Invalid explicit model protocol", "55000");
        }
    }

    private AiCapabilityCheck toolCheck(ResultSet rows) throws SQLException {
        var saved = new DesktopPreferenceStore(connections).find(toolCheckKey(rows.getString("model_id")));
        if (saved.isEmpty()) return AiCapabilityCheck.unverified();
        try {
            var fields = saved.orElseThrow().split(",", -1);
            if (fields.length != 4) throw new IllegalArgumentException();
            if (Long.parseLong(fields[0]) != rows.getLong("configuration_revision"))
                return AiCapabilityCheck.unverified();
            var state = AiCapabilityStatus.valueOf(fields[1]);
            long time = Long.parseLong(fields[2]);
            return new AiCapabilityCheck(state, fields[3], time < 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(time)));
        } catch (IllegalArgumentException invalid) {
            throw new SQLException("Invalid native tool verification", "55000");
        }
    }

    /** Decodes model settings and only revision-valid capability evidence. / 解码模型设置及修订有效的能力证据。
     * @param rows rows supplied to this boundary / 传入当前边界的rows值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    private StoredAiModelInfo configuration(ResultSet rows) throws SQLException {
        var checks = new EnumMap<AiCapabilityType, AiCapabilityCheck>(AiCapabilityType.class);
        for (var capability : AiCapabilityType.values()) {
            if (capability == AiCapabilityType.TOOL_CALLING) {
                checks.put(capability, toolCheck(rows));
                continue;
            }
            String prefix = capabilityPrefix(capability);
            var checkedAt = verifiedAt(rows, prefix);
            checks.put(capability, checkedAt.isEmpty() ? AiCapabilityCheck.unverified()
                : new AiCapabilityCheck(AiCapabilityStatus.valueOf(rows.getString(prefix + "_state")),
                rows.getString(prefix + "_reason"), checkedAt));
        }
        return new StoredAiModelInfo(provider(rows), rows.getString("display_name"),
            rows.getInt("display_order"), rows.getLong("configuration_revision"), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), checks);
    }

    /** Returns attempted verification time only for the current revision. / 仅返回当前修订的验证尝试时间。
     * @param rows saved row / 已存行
     * @param capability fixed capability prefix / 固定能力前缀
     * @return current attempt time or absent / 当前尝试时间或空
     * @throws SQLException if storage is malformed / 存储格式错误时 */
    private static Optional<Instant> verifiedAt(ResultSet rows, String capability) throws SQLException {
        return rows.getObject(capability + "_verified_revision") != null
            && rows.getLong(capability + "_verified_revision") == rows.getLong("configuration_revision")
            ? Optional.of(Instant.ofEpochMilli(rows.getLong(capability + "_verified_at"))) : Optional.empty();
    }
}
