package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.StoredAiProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiRoleAssignment;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderConfiguration;
import java.time.Instant;
import gold.debug.windowstolinux.shared.model.ai.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Stores credential-free AI provider profiles. / 保存不含凭据的 AI 提供者资料。
 */
public final class AiProfileRepository {
    /**
     * Factory for scoped database connections.
     * <p>限定作用域数据库连接的工厂。
     */
    private final DesktopConnectionFactory connections;

    /**
     * Creates the repository. / 创建仓库。
     *
     * @param connections factory for scoped database connections / 限定作用域数据库连接的工厂
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public AiProfileRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Saves the default profile. / 保存默认资料。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveDefault(StoredAiProfile profile) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_profile (id, endpoint, model, credential_key, credential_mode)
                     VALUES ('default', ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET endpoint=excluded.endpoint, model=excluded.model,
                         credential_key=excluded.credential_key, credential_mode=excluded.credential_mode
                     """)) {
            statement.setString(1, profile.endpoint());
            statement.setString(2, profile.model());
            statement.setString(3, profile.credentialKey());
            statement.setString(4, profile.credentialMode());
            statement.executeUpdate();
        }
    }

    /**
     * Finds the default profile. / 查找默认资料。
     *
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<StoredAiProfile> findDefault() throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT endpoint, model, credential_key, credential_mode FROM ai_profile WHERE id='default'");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? Optional.of(new StoredAiProfile(result.getString("endpoint"), result.getString("model"),
                    result.getString("credential_key"), result.getString("credential_mode"))) : Optional.empty();
        }
    }

    /**
     * Saves a named provider profile. / 保存命名提供者资料。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveNamed(StoredAiProviderProfile profile) throws SQLException {
        saveConfiguration(profile, profile.id(), null, AiCapabilityType.TEXT);
    }

    /** Saves a profile with verified text capability. / 保存已验证文本能力的模型。
     * @param profile connection revision / 连接修订
     * @param name display name / 显示名称
     * @param verifiedAt probe completion time / 测试完成时间
     * @throws SQLException if persistence fails / 保存失败时
     */
    public void saveVerified(StoredAiProviderProfile profile, String name, Instant verifiedAt) throws SQLException {
        saveVerified(profile, name, verifiedAt, AiCapabilityType.TEXT);
    }
    /** Saves a tested capability and invalidates evidence when the connection changes. / 保存已测能力，连接改变时作废旧证据。
     * @param profile connection revision / 连接修订
     * @param name display name / 显示名称
     * @param verifiedAt probe completion time / 测试完成时间
     * @param capability tested capability / 已测试能力
     * @throws SQLException if persistence fails / 保存失败时
     */
    public void saveVerified(StoredAiProviderProfile profile, String name, Instant verifiedAt, AiCapabilityType capability) throws SQLException {
        saveConfiguration(profile, name, Objects.requireNonNull(verifiedAt), capability);
    }
    /** Applies profile and capability changes in one transaction. / 在同一事务中保存模型和能力变化。
     * @param profile connection revision / 连接修订
     * @param name display name / 显示名称
     * @param verifiedAt optional probe time / 可选测试时间
     * @param capability tested capability / 已测试能力
     * @throws SQLException if persistence fails / 保存失败时
     */
    private void saveConfiguration(StoredAiProviderProfile profile, String name, Instant verifiedAt, AiCapabilityType capability) throws SQLException {
        new StoredAiProviderConfiguration(profile, name, 0, 1, Optional.empty(), Optional.empty());
        Objects.requireNonNull(capability);
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                boolean changed = false;
                try (var existing = connection.prepareStatement("SELECT * FROM ai_provider_profile WHERE profile_id=?")) {
                    existing.setString(1, profile.id());
                    try (var row = existing.executeQuery()) { changed = row.next() && !provider(row).equals(profile); }
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO ai_provider_profile(profile_id,endpoint,model,credential_key,credential_mode)
                        VALUES(?,?,?,?,?) ON CONFLICT(profile_id) DO UPDATE SET endpoint=excluded.endpoint,
                        model=excluded.model,credential_key=excluded.credential_key,credential_mode=excluded.credential_mode
                        """)) {
                    statement.setString(1,profile.id()); statement.setString(2,profile.endpoint()); statement.setString(3,profile.model());
                    statement.setString(4,profile.credentialKey()); statement.setString(5,profile.credentialMode()); statement.executeUpdate();
                }
                try (var statement = connection.prepareStatement("""
                        INSERT INTO ai_model_inventory(profile_id,display_name,display_order)
                        VALUES(?,?,(SELECT COALESCE(MAX(display_order),-1)+1 FROM ai_model_inventory))
                        ON CONFLICT(profile_id) DO UPDATE SET display_name=excluded.display_name,revision=revision+?
                        """)) {
                    statement.setString(1,profile.id()); statement.setString(2,name.trim()); statement.setInt(3,changed ? 1 : 0); statement.executeUpdate();
                }
                if (changed) {
                    try (var statement = connection.prepareStatement("DELETE FROM ai_model_verification WHERE profile_id=?")) {
                        statement.setString(1,profile.id()); statement.executeUpdate();
                    }
                }
                if (verifiedAt != null) writeVerification(connection, profile.id(), capability, verifiedAt);
            });
        }
    }
    /** Records a completed probe only if its exact profile still exists. / 仅当测试对应的精确模型配置仍存在时保存验证。
     * @param expected probed profile / 已测试配置
     * @param capability probed capability / 已测试能力
     * @param verifiedAt completion time / 完成时间
     * @throws SQLException if configuration changed or storage fails / 配置已改变或保存失败时
     */
    public void recordVerification(StoredAiProviderProfile expected, AiCapabilityType capability, Instant verifiedAt) throws SQLException {
        try (var connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                try (var statement = connection.prepareStatement("SELECT * FROM ai_provider_profile WHERE profile_id=?")) {
                    statement.setString(1,expected.id());
                    try (var row = statement.executeQuery()) {
                        if (!row.next() || !provider(row).equals(expected)) throw new SQLException("model changed during capability probe");
                    }
                }
                writeVerification(connection,expected.id(),capability,Objects.requireNonNull(verifiedAt));
            });
        }
    }
    /** Writes evidence bound to the current revision. / 保存绑定当前修订的证据。
     * @param connection transaction / 事务
     * @param id profile identity / 模型标识
     * @param capability tested capability / 测试能力
     * @param time completion time / 完成时间
     * @throws SQLException if storage fails / 保存失败时
     */
    private static void writeVerification(Connection connection, String id, AiCapabilityType capability, Instant time) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO ai_model_verification(profile_id,capability,revision,verified_at)
                SELECT profile_id,?,revision,? FROM ai_model_inventory WHERE profile_id=?
                ON CONFLICT(profile_id,capability) DO UPDATE SET revision=excluded.revision,verified_at=excluded.verified_at
                """)) {
            statement.setString(1,capability.name()); statement.setString(2,time.toString()); statement.setString(3,id);
            if (statement.executeUpdate()!=1) throw new SQLException("model no longer exists");
        }
    }
    /** Capability evidence is joined only at the same revision. / 能力证据只连接相同修订。 */
    private static final String CONFIGURED_SELECT = """
            SELECT p.*,i.display_name,i.display_order,i.revision,t.verified_at AS text_verified,v.verified_at AS vision_verified
            FROM ai_provider_profile p JOIN ai_model_inventory i ON i.profile_id=p.profile_id
            LEFT JOIN ai_model_verification t ON t.profile_id=i.profile_id AND t.revision=i.revision AND t.capability='TEXT'
            LEFT JOIN ai_model_verification v ON v.profile_id=i.profile_id AND v.revision=i.revision AND v.capability='VISION'
            """;
    /** Reads the full inventory in display order. / 按展示顺序读取完整清单。
     * @return immutable model inventory / 不可变模型清单
     * @throws SQLException if reading fails / 读取失败时
     */
    public List<StoredAiProviderConfiguration> listConfigured() throws SQLException { return configured(null); }
    /** Captures enabled and verified purpose members in invocation order. / 按调用顺序捕获已启用且验证通过的用途成员。
     * @param purpose invocation purpose / 调用用途
     * @return immutable provider snapshot / 不可变模型快照
     * @throws SQLException if reading fails / 读取失败时
     */
    public List<StoredAiProviderConfiguration> configuredFor(AiPurposeType purpose) throws SQLException {
        return configured(Objects.requireNonNull(purpose));
    }
    /** Performs a single-statement inventory or purpose snapshot. / 通过单条语句取得清单或用途快照。
     * @param purpose optional selected purpose / 可选用途
     * @return immutable selected configurations / 不可变所选配置
     * @throws SQLException if reading fails / 读取失败时
     */
    private List<StoredAiProviderConfiguration> configured(AiPurposeType purpose) throws SQLException {
        String query = CONFIGURED_SELECT + (purpose == null ? " ORDER BY i.display_order,p.profile_id" :
                " JOIN ai_model_purpose r ON r.profile_id=p.profile_id WHERE r.purpose=? AND r.enabled=1 AND "
                + (purpose == AiPurposeType.VISION ? "v" : "t") + ".verified_at IS NOT NULL ORDER BY r.priority");
        try (var connection = connections.open(); var statement = connection.prepareStatement(query)) {
            if (purpose != null) statement.setString(1,purpose.name());
            try (var result = statement.executeQuery()) {
                List<StoredAiProviderConfiguration> values = new ArrayList<>();
                while (result.next()) values.add(new StoredAiProviderConfiguration(provider(result),result.getString("display_name"),
                        result.getInt("display_order"),result.getLong("revision"),
                        Optional.ofNullable(result.getString("text_verified")).map(Instant::parse),
                        Optional.ofNullable(result.getString("vision_verified")).map(Instant::parse)));
                return List.copyOf(values);
            }
        }
    }
    /** Captures all purpose routes in one SQLite statement and read snapshot. / 用一条 SQLite 语句及读快照捕获全部用途路由。
     * @return immutable routes including empty purposes / 包含空用途的不可变路由
     * @throws SQLException when configuration cannot be read / 无法读取配置时
     */
    public Map<AiPurposeType,List<StoredAiProviderConfiguration>> purposeSnapshot()throws SQLException{
        var grouped=new EnumMap<AiPurposeType,List<StoredAiProviderConfiguration>>(AiPurposeType.class);
        for(var purpose:AiPurposeType.values())grouped.put(purpose,new ArrayList<>());
        String query=CONFIGURED_SELECT.replace("SELECT p.*", "SELECT r.purpose AS routing_purpose,p.*")
            +" JOIN ai_model_purpose r ON r.profile_id=p.profile_id WHERE r.enabled=1 AND ((r.purpose='VISION' AND v.verified_at IS NOT NULL) OR (r.purpose!='VISION' AND t.verified_at IS NOT NULL)) ORDER BY r.purpose,r.priority";
        try(var connection=connections.open();var statement=connection.prepareStatement(query);var result=statement.executeQuery()){
            while(result.next())grouped.get(AiPurposeType.valueOf(result.getString("routing_purpose"))).add(new StoredAiProviderConfiguration(provider(result),result.getString("display_name"),
                result.getInt("display_order"),result.getLong("revision"),Optional.ofNullable(result.getString("text_verified")).map(Instant::parse),Optional.ofNullable(result.getString("vision_verified")).map(Instant::parse)));
        }
        grouped.replaceAll((purpose,values)->List.copyOf(values));return Map.copyOf(grouped);
    }
    /** Provides purpose membership operations over the same database. / 提供同一数据库的用途成员操作。
     * @return purpose repository / 用途仓库
     */
    public AiPurposeRepository purposes() { return new AiPurposeRepository(connections); }
    /** Atomically changes display order without affecting any invocation list. / 原子调整展示顺序而不影响调用列表。
     * @param ids every inventory identity once / 每个清单标识恰好一次
     * @throws SQLException if the inventory changed or storage fails / 清单已改变或保存失败时
     */
    public void reorder(List<String> ids) throws SQLException {
        var order = List.copyOf(ids);
        try (var connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                var saved = new java.util.HashSet<String>();
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT profile_id FROM ai_model_inventory")) {
                    while (rows.next()) saved.add(rows.getString(1));
                }
                if (order.size()!=saved.size() || !saved.equals(new java.util.HashSet<>(order))) throw new SQLException("inventory order changed");
                try (var statement = connection.prepareStatement("UPDATE ai_model_inventory SET display_order=? WHERE profile_id=?")) {
                    for (int i=0;i<order.size();i++) { statement.setInt(1,i); statement.setString(2,order.get(i)); statement.addBatch(); }
                    statement.executeBatch();
                }
            });
        }
    }

    /**
     * Lists named provider profiles. / 列出命名提供者资料。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<StoredAiProviderProfile> listNamed() throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT profile_id, endpoint, model, credential_key, credential_mode
                     FROM ai_provider_profile ORDER BY profile_id
                     """); ResultSet result = statement.executeQuery()) {
            List<StoredAiProviderProfile> profiles = new ArrayList<>();
            while (result.next()) {
                profiles.add(new StoredAiProviderProfile(result.getString("profile_id"), result.getString("endpoint"),
                        result.getString("model"), result.getString("credential_key"), result.getString("credential_mode")));
            }
            return List.copyOf(profiles);
        }
    }

    /**
     * Finds one exact named provider without provider fallback. / 查找一个精确命名提供者且不执行提供者回退。
     *
     * @param profileId profile id / 配置资料标识
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<StoredAiProviderProfile> findNamed(String profileId) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT profile_id, endpoint, model, credential_key, credential_mode
                     FROM ai_provider_profile WHERE profile_id = ?
                     """)) {
            statement.setString(1, profileId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(provider(result)) : Optional.empty();
            }
        }
    }

    /**
     * Assigns one fixed role to one existing named provider. / 将一个固定角色分配给一个已有命名提供者。
     *
     * @param assignment assignment / 赋值
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void saveRoleAssignment(StoredAiRoleAssignment assignment) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_role_assignment (role, profile_id) VALUES (?, ?)
                     ON CONFLICT(role) DO UPDATE SET profile_id=excluded.profile_id
                     """)) {
            statement.setString(1, assignment.role());
            statement.setString(2, assignment.profileId());
            statement.executeUpdate();
        }
    }

    /**
     * Lists all explicit role assignments in stable role order. / 以稳定角色顺序列出全部显式角色分配。
     *
     * @return constructed or resolved list / 构造或解析得到的列表
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public List<StoredAiRoleAssignment> listRoleAssignments() throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT role, profile_id FROM ai_role_assignment ORDER BY role");
             ResultSet result = statement.executeQuery()) {
            List<StoredAiRoleAssignment> assignments = new ArrayList<>();
            while (result.next()) {
                assignments.add(new StoredAiRoleAssignment(result.getString("role"), result.getString("profile_id")));
            }
            return List.copyOf(assignments);
        }
    }

    /**
     * Finds the explicit provider assignment for one exact fixed role. / 查找一个精确固定角色的显式提供者分配。
     *
     * @param role role / 角色
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<StoredAiRoleAssignment> findRoleAssignment(String role) throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT role, profile_id FROM ai_role_assignment WHERE role = ?")) {
            statement.setString(1, role);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new StoredAiRoleAssignment(
                        result.getString("role"), result.getString("profile_id"))) : Optional.empty();
            }
        }
    }

    /**
     * Builds stored ai provider profile from the supplied provider inputs.
     * <p>根据所提供提供者输入构建已存储AI提供者配置资料。
     *
     * @param result typed outcome produced by the delegated operation / 被委派操作产生的类型化结果
     * @return stored ai provider profile from the supplied provider inputs / 根据所提供提供者输入构建已存储AI提供者配置资料
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    private static StoredAiProviderProfile provider(ResultSet result) throws SQLException {
        return new StoredAiProviderProfile(result.getString("profile_id"), result.getString("endpoint"),
                result.getString("model"), result.getString("credential_key"), result.getString("credential_mode"));
    }
}
