package gold.debug.windowstolinux.app.db.connect;

import java.sql.Connection;
import java.sql.SQLException;

import gold.debug.windowstolinux.app.db.error.DesktopStorageException;
import gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType;

/**
 * Shared transaction primitives used by focused repositories. / 由聚焦仓库使用的共享事务原语。
 */
public final class StoreTransactionRunner {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private StoreTransactionRunner() {
    }

    /**
     * Executes repository transaction.
     * <p>执行仓库事务。
     *
     * @param connection connection scoped to the current database or remote operation / 限定于当前数据库或远端操作的连接
     * @param work work / 工作
     * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
     */
    public static void execute(Connection connection, SqlWork work) throws SQLException {
        if (!connection.getAutoCommit()) {
            work.execute();
            return;
        }
        connection.setAutoCommit(false);
        try {
            work.execute();
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                rollbackFailure.addSuppressed(exception);
                throw DesktopStorageException.create(DesktopStorageFailureType.ROLLBACK_FAILED,
                    "A database transaction failed and its rollback could not be verified", rollbackFailure);
            }
            throw exception;
        }
    }

    /**
     * SQL work executed inside the repository transaction boundary.
     * <p>在仓库事务边界内执行的 SQL 工作。
     */
    @FunctionalInterface
    public interface SqlWork {
        /**
         * Executes sql work.
         * <p>执行SQL工作。
         *
         * @throws SQLException if the database cannot complete the requested read or transaction / 数据库无法完成请求的读取或事务时
         */
        void execute() throws SQLException;
    }
}
