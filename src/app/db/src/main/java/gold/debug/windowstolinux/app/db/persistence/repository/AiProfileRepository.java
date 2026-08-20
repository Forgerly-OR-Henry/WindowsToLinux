package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.app.db.entity.StoredAiProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiProviderProfile;
import gold.debug.windowstolinux.app.db.entity.StoredAiRoleAssignment;

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
        try (Connection connection = connections.open();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO ai_provider_profile (profile_id, endpoint, model, credential_key, credential_mode)
                     VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT(profile_id) DO UPDATE SET endpoint=excluded.endpoint, model=excluded.model,
                         credential_key=excluded.credential_key, credential_mode=excluded.credential_mode
                     """)) {
            statement.setString(1, profile.id());
            statement.setString(2, profile.endpoint());
            statement.setString(3, profile.model());
            statement.setString(4, profile.credentialKey());
            statement.setString(5, profile.credentialMode());
            statement.executeUpdate();
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
