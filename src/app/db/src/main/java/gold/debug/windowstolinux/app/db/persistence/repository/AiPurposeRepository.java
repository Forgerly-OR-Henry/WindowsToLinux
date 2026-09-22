package gold.debug.windowstolinux.app.db.persistence.repository;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.persistence.connection.DesktopConnectionFactory;
import gold.debug.windowstolinux.shared.model.ai.*;

/** Persists purpose membership independently from model inventory order. / 独立于模型清单顺序保存用途成员。 */
public final class AiPurposeRepository {
    /** Scoped database factory. / 限定数据库工厂。 */
    private final DesktopConnectionFactory connections;
    /** Creates a purpose repository. / 创建用途仓库。
     * @param connections database factory / 数据库工厂
     */
    public AiPurposeRepository(DesktopConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    /** Reads members in invocation order. / 按调用顺序读取成员。
     * @param purpose selected purpose / 所选用途
     * @return immutable ordered members / 不可变有序成员
     * @throws SQLException if reading fails / 读取失败时
     */
    public List<AiPurposeAssignment> list(AiPurposeType purpose) throws SQLException {
        try (var connection = connections.open();
                var statement = connection.prepareStatement(
                        "SELECT profile_id,enabled FROM ai_model_purpose WHERE purpose=? ORDER BY priority")) {
            statement.setString(1, Objects.requireNonNull(purpose).name());
            try (var rows = statement.executeQuery()) {
                List<AiPurposeAssignment> result = new ArrayList<>();
                while (rows.next())
                    result.add(new AiPurposeAssignment(rows.getString(1), rows.getBoolean(2)));
                return List.copyOf(result);
            }
        }
    }

    /** Atomically replaces one purpose after checking capability revisions. / 校验能力修订后原子替换一个用途。
     * @param purpose selected purpose / 所选用途
     * @param assignments complete ordered membership / 完整有序成员
     * @throws SQLException if validation or persistence fails / 校验或保存失败时
     */
    public void save(AiPurposeType purpose, List<AiPurposeAssignment> assignments) throws SQLException {
        Objects.requireNonNull(purpose);
        var members = List.copyOf(assignments);
        if (members.stream().map(AiPurposeAssignment::profileId).distinct().count() != members.size())
            throw new IllegalArgumentException("duplicate model purpose membership");
        try (var connection = connections.open()) {
            RepositoryTransactionExecutor.execute(connection, () -> {
                try (var check = connection.prepareStatement("""
                        SELECT 1 FROM ai_model_inventory i JOIN ai_model_verification v
                        ON v.profile_id=i.profile_id AND v.revision=i.revision
                        WHERE i.profile_id=? AND v.capability=?
                        """)) {
                    for (var member : members) {
                        check.setString(1, member.profileId());
                        check.setString(2, purpose == AiPurposeType.VISION ? "VISION" : "TEXT");
                        try (var rows = check.executeQuery()) {
                            if (member.enabled() && !rows.next())
                                throw new SQLException("model capability is not verified");
                        }
                    }
                }
                try (var delete = connection.prepareStatement("DELETE FROM ai_model_purpose WHERE purpose=?")) {
                    delete.setString(1, purpose.name());
                    delete.executeUpdate();
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO ai_model_purpose(purpose,profile_id,priority,enabled) VALUES(?,?,?,?)")) {
                    for (int index = 0; index < members.size(); index++) {
                        insert.setString(1, purpose.name());
                        insert.setString(2, members.get(index).profileId());
                        insert.setInt(3, index);
                        insert.setBoolean(4, members.get(index).enabled());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
            });
        }
    }
}
