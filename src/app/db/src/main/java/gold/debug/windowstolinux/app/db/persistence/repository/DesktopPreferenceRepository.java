package gold.debug.windowstolinux.app.db.persistence.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;

/**
 * Stores small non-secret desktop preferences. / 保存小型非秘密桌面偏好。
 */
public final class DesktopPreferenceRepository {
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
    public DesktopPreferenceRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    /**
     * Saves one preference. / 保存一项偏好。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public void save(String name, String value) throws SQLException {
        requireText(name, "preference_key");
        if (!name.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("preference key must use lowercase underscores");
        requireText(value, "preference_value");
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO desktop_preference (preference_key, preference_value) VALUES (?, ?)
                ON CONFLICT(preference_key) DO UPDATE SET preference_value=excluded.preference_value
                """)) {
            statement.setString(1, name);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    /**
     * Finds one preference. / 查找一项偏好。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public Optional<String> find(String name) throws SQLException {
        requireText(name, "preference_key");
        if (!name.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("preference key must use lowercase underscores");
        try (Connection connection = connections.open();
                PreparedStatement statement = connection
                        .prepareStatement("SELECT preference_value FROM desktop_preference WHERE preference_key=?")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("preference_value")) : Optional.empty();
            }
        }
    }

    /**
     * Rejects missing or blank required text.
     * <p>拒绝缺失或空白的必填文本。
     *
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @param values values supplied to this boundary / 传入当前边界的values值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void saveAll(java.util.Map<String, String> values) throws SQLException {
        connections.transaction(() -> {
            for (var entry : values.entrySet())
                save(entry.getKey(), entry.getValue());
        });
    }

    /** Validates stored deployment preferences and initializes only missing defaults. / 验证已存部署偏好，仅初始化缺失的默认值。
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public java.util.Map<String, String> deploymentSelection() throws SQLException {
        var result = new java.util.HashMap<String, String>();
        connections.transaction(() -> {
            var mode = find("deploy_mode");
            var approval = find("deploy_approval");
            String selectedMode = mode.orElse("ASSISTED"), selectedApproval = approval.orElse("AUTOMATIC");
            try {
                gold.debug.windowstolinux.shared.model.deployment.DeploymentAutomationMode.valueOf(selectedMode);
                gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode.valueOf(selectedApproval);
            } catch (IllegalArgumentException invalid) {
                throw new SQLException("invalid stored deployment preferences", invalid);
            }
            if (mode.isEmpty())
                save("deploy_mode", selectedMode);
            if (approval.isEmpty())
                save("deploy_approval", selectedApproval);
            result.put("deploy_mode", selectedMode);
            result.put("deploy_approval", selectedApproval);
        });
        return java.util.Map.copyOf(result);
    }

    /** Requires nonempty persisted preference text. / 要求持久化偏好文本非空。
     * @param value value supplied to this boundary / 传入当前边界的value值
     * @param name name supplied to this boundary / 传入当前边界的name值
     */
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
