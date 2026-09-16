package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.StoredAiProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiRoleAssignment;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderConfiguration;
import java.time.Instant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Stores credential-free AI provider profiles. / 保存不含凭据的 AI 提供者资料。 */
public final class AiProfileRepository {
    private final DesktopConnectionFactory connections;

    /** Creates the repository. / 创建仓库。 */
    public AiProfileRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /** Saves the default profile. / 保存默认资料。 */
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

    /** Finds the default profile. / 查找默认资料。 */
    public Optional<StoredAiProfile> findDefault() throws SQLException {
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT endpoint, model, credential_key, credential_mode FROM ai_profile WHERE id='default'");
             ResultSet result = statement.executeQuery()) {
            return result.next() ? Optional.of(new StoredAiProfile(result.getString("endpoint"), result.getString("model"),
                    result.getString("credential_key"), result.getString("credential_mode"))) : Optional.empty();
        }
    }

    /** Saves a named provider profile. / 保存命名提供者资料。 */
    public void saveNamed(StoredAiProviderProfile profile) throws SQLException {
        saveConfiguration(profile, profile.id(), null);
    }

    /** Stores a profile only after its selected-model probe succeeded. / 仅在所选模型探测成功后保存配置。 */
    public void saveVerified(StoredAiProviderProfile profile, String name, Instant verifiedAt) throws SQLException {
        saveConfiguration(profile, name, Objects.requireNonNull(verifiedAt));
    }

    private void saveConfiguration(StoredAiProviderProfile profile, String name, Instant verifiedAt) throws SQLException {
        new StoredAiProviderConfiguration(profile, name, true, 0, Optional.ofNullable(verifiedAt));
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ai_provider_profile (profile_id, endpoint, model, credential_key, credential_mode)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT(profile_id) DO UPDATE SET endpoint=excluded.endpoint,
                        model=excluded.model, credential_key=excluded.credential_key, credential_mode=excluded.credential_mode
                        """)) {
                    statement.setString(1, profile.id()); statement.setString(2, profile.endpoint()); statement.setString(3, profile.model());
                    statement.setString(4, profile.credentialKey()); statement.setString(5, profile.credentialMode()); statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ai_provider_control(profile_id,display_name,enabled,priority,verified_at)
                        VALUES (?,?,1,(SELECT COALESCE(MAX(priority),-1)+1 FROM ai_provider_control),?)
                        ON CONFLICT(profile_id) DO UPDATE SET display_name=excluded.display_name, verified_at=excluded.verified_at
                        """)) {
                    statement.setString(1, profile.id()); statement.setString(2, name.trim());
                    statement.setString(3, verifiedAt == null ? null : verifiedAt.toString()); statement.executeUpdate();
                }
            });
        }
    }

    /** Reads one consistent ordered snapshot of profiles and their controls. / 读取配置及其控制状态的一致有序快照。 */
    public List<StoredAiProviderConfiguration> listConfigured() throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT p.*, c.display_name, c.enabled, c.priority, c.verified_at FROM ai_provider_profile p
                JOIN ai_provider_control c ON c.profile_id=p.profile_id ORDER BY c.priority,p.profile_id
                """); ResultSet result = statement.executeQuery()) {
            List<StoredAiProviderConfiguration> values = new ArrayList<>();
            while (result.next()) values.add(new StoredAiProviderConfiguration(provider(result), result.getString("display_name"),
                    result.getBoolean("enabled"), result.getInt("priority"), Optional.ofNullable(result.getString("verified_at")).map(Instant::parse)));
            return List.copyOf(values);
        }
    }

    /** Changes enablement without changing the saved position. / 改变启用状态，不改变已保存位置。 */
    public void setEnabled(String id, boolean enabled) throws SQLException {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("UPDATE ai_provider_control SET enabled=? WHERE profile_id=?")) {
            statement.setBoolean(1, enabled); statement.setString(2, id);
            if (statement.executeUpdate() != 1) throw new SQLException("AI provider no longer exists");
        }
    }

    /** Saves a complete permutation atomically; stale or duplicate lists leave ordering unchanged. / 原子保存完整排列，过期或重复列表不改变顺序。 */
    public void reorder(List<String> ids) throws SQLException {
        List<String> order = List.copyOf(ids);
        try (Connection connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                java.util.Set<String> saved = new java.util.HashSet<>();
                try (var statement = connection.prepareStatement("SELECT profile_id FROM ai_provider_control"); var rows = statement.executeQuery()) {
                    while (rows.next()) saved.add(rows.getString(1));
                }
                if (order.size() != saved.size() || !saved.equals(new java.util.HashSet<>(order))) throw new SQLException("AI order must contain every saved provider exactly once");
                try (var statement = connection.prepareStatement("UPDATE ai_provider_control SET priority=? WHERE profile_id=?")) {
                    for (int index = 0; index < order.size(); index++) {
                        statement.setInt(1, index); statement.setString(2, order.get(index)); statement.addBatch();
                    }
                    statement.executeBatch();
                }
            });
        }
    }

    /** Lists named provider profiles. / 列出命名提供者资料。 */
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

    /** Finds one exact named provider without provider fallback. / 查找一个精确命名提供者且不执行提供者回退。 */
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

    /** Assigns one fixed role to one existing named provider. / 将一个固定角色分配给一个已有命名提供者。 */
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

    /** Lists all explicit role assignments in stable role order. / 以稳定角色顺序列出全部显式角色分配。 */
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

    /** Finds the explicit provider assignment for one exact fixed role. / 查找一个精确固定角色的显式提供者分配。 */
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

    private static StoredAiProviderProfile provider(ResultSet result) throws SQLException {
        return new StoredAiProviderProfile(result.getString("profile_id"), result.getString("endpoint"),
                result.getString("model"), result.getString("credential_key"), result.getString("credential_mode"));
    }
}
