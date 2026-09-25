package gold.debug.windowstolinux.app.db.persistence.repository;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.shared.model.ai.*;

/** Maintains independent purpose membership and order in the model row. / 在模型行维护独立用途成员及顺序。 */
public final class AiPurposeRepository {
    /** Scoped database connection factory. / 限定数据库连接工厂。
     */
    private final DesktopConnectionFactory connections;

    /** Binds the validated inputs and owned state for this scope. / 绑定当前作用域的已验证输入及持有状态。
     * @param connections connections supplied to this boundary / 传入当前边界的connections值
     */
    public AiPurposeRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    /** Maps a typed purpose to its fixed SQL column prefix. / 将类型化用途映射为固定 SQL 列前缀。
     * @param purpose purpose supplied to this boundary / 传入当前边界的purpose值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     */
    static String prefix(AiPurposeType purpose) {
        return switch (Objects.requireNonNull(purpose)) {
            case DEPLOYMENT -> "language";
            case APPROVAL -> "approval";
            case VISION -> "vision";
        };
    }

    /** Reads the selected purpose assignments in call order. / 按调用顺序读取所选用途分配。
     * @param purpose purpose supplied to this boundary / 传入当前边界的purpose值
     * @return the validated result or retained scoped handle / 已验证结果或保留的限定句柄
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public List<AiPurposeAssignment> list(AiPurposeType purpose) throws SQLException {
        String prefix = prefix(purpose);
        try (var c = connections.open();
                var s = c.prepareStatement("SELECT model_id,is_" + prefix + "_enabled FROM ai_model WHERE " + prefix
                        + "_call_order IS NOT NULL ORDER BY " + prefix + "_call_order");
                var rows = s.executeQuery()) {
            var values = new ArrayList<AiPurposeAssignment>();
            while (rows.next())
                values.add(new AiPurposeAssignment(rows.getString(1), rows.getBoolean(2)));
            return List.copyOf(values);
        }
    }

    /** Validates and persists the supplied state in its transaction boundary. / 在对应事务边界内验证并保存传入状态。
     * @param purpose purpose supplied to this boundary / 传入当前边界的purpose值
     * @param assignments assignments supplied to this boundary / 传入当前边界的assignments值
     * @throws SQLException if validation or the scoped operation fails / 校验或限定操作失败时
     */
    public void save(AiPurposeType purpose, List<AiPurposeAssignment> assignments) throws SQLException {
        String prefix = prefix(purpose);
        var members = List.copyOf(assignments);
        if (members.stream().map(AiPurposeAssignment::profileId).distinct().count() != members.size())
            throw new IllegalArgumentException("duplicate model purpose membership");
        connections.transaction(() -> {
            try (var c = connections.open()) {
                String capability = purpose == AiPurposeType.VISION ? "vision" : "text";
                try (var check = c.prepareStatement("SELECT configuration_revision," + capability
                        + "_verified_revision FROM ai_model WHERE model_id=?")) {
                    for (var member : members) {
                        check.setString(1, member.profileId());
                        try (var rows = check.executeQuery()) {
                            if (!rows.next() || member.enabled()
                                    && (rows.getObject(2) == null || rows.getLong(1) != rows.getLong(2)))
                                throw new SQLException("model capability is not verified");
                        }
                    }
                }
                try (var clear = c.createStatement()) {
                    clear.executeUpdate(
                            "UPDATE ai_model SET is_" + prefix + "_enabled=0," + prefix + "_call_order=NULL");
                }
                try (var update = c.prepareStatement("UPDATE ai_model SET is_" + prefix + "_enabled=?," + prefix
                        + "_call_order=? WHERE model_id=?")) {
                    for (int i = 0; i < members.size(); i++) {
                        update.setBoolean(1, members.get(i).enabled());
                        update.setInt(2, i);
                        update.setString(3, members.get(i).profileId());
                        update.addBatch();
                    }
                    update.executeBatch();
                }
            }
        });
    }
}
