package gold.debug.windowstolinux.mod.deployment.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.task.*;

/** Commits an owned business transaction without treating uncertain acknowledgements as failure. / 提交所属业务事务，不将不确定的确认误记为已失败。 */
final class DeploymentDataTransaction {
    /** Prevents construction of the fixed transaction primitive. / 防止构造固定事务原语。 */
    private DeploymentDataTransaction() {
    }

    /** Owns and closes a fresh connection, distinguishing committed data from uncertain release. / 持有并关闭全新连接，区分已提交数据和不确定释放。
     * @param connection original owned connection / 原所属连接
     * @param work bounded business SQL work / 有界业务SQL工作
     * @throws SQLException if work fails with confirmed rollback / 工作失败且已确认回滚时
     * @throws ApiException if rollback, commit or final release cannot be confirmed / 无法确认回滚、提交或最终释放时 */
    static void execute(Connection connection, Work work) throws SQLException, ApiException {
        boolean committed = false;
        try (connection) {
            commit(connection, work);
            committed = true;
        } catch (SQLException failure) {
            if (committed) {
                var unknown = uncertain("DEPLOYMENT_CONNECTION_RELEASE_UNKNOWN");
                unknown.addSuppressed(failure);
                throw unknown;
            }
            throw failure;
        }
    }

    /** Applies the original SQL transaction without retrying any business statements. / 应用原SQL事务，不重试任一业务语句。
     * @param connection original owned connection / 原所属连接
     * @param work bounded business SQL work / 有界业务SQL工作
     * @throws SQLException if work fails with confirmed rollback / 工作失败且已确认回滚时
     * @throws ApiException if rollback or commit cannot be confirmed / 无法确认回滚或提交时 */
    private static void commit(Connection connection, Work work) throws SQLException, ApiException {
        if (!connection.getAutoCommit())
            throw new SQLException("A fresh deployment transaction is required");
        connection.setAutoCommit(false);
        try {
            work.execute();
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                var unknown = uncertain("DEPLOYMENT_ROLLBACK_UNKNOWN");
                unknown.addSuppressed(failure);
                unknown.addSuppressed(rollback);
                throw unknown;
            }
            throw failure;
        }
        try {
            connection.commit();
        } catch (SQLException commit) {
            var unknown = uncertain("DEPLOYMENT_COMMIT_UNKNOWN");
            unknown.addSuppressed(commit);
            throw unknown;
        }
    }

    /** Describes uncertainty without embedding JDBC text or business values. / 不嵌入JDBC文本或业务值地说明不确定性。
     * @param code exact failing durability boundary / 精确失败持久边界
     * @return original-result uncertainty / 原结果不确定性 */
    private static ApiException uncertain(String code) {
        return new ApiException(new Problem(ProblemCode.UNKNOWN, code,
                "Deployment data or connection release requires reconciliation before retry", Map.of(),
                Optional.empty()));
    }

    /** SQL work whose surrounding transaction is owned by the caller. / 外层事务由调用方持有的SQL工作。 */
    @FunctionalInterface
    interface Work {
        /** Performs the original accepted writes. / 执行原已接受写入。
         * @throws SQLException if a statement fails / 语句失败时 */
        void execute() throws SQLException;
    }
}
