package gold.debug.windowstolinux.app.db.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import gold.debug.windowstolinux.app.db.error.DesktopStorageException;
import gold.debug.windowstolinux.app.db.error.DesktopStorageFailureType;
import org.junit.jupiter.api.Test;

class StoreTransactionRunnerTest {
    @Test
    void stopsWithStructuredFailureWhenRollbackCannotBeVerified() {
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("getAutoCommit"))
                    return true;
                if (method.getName().equals("rollback")) {
                    throw new SQLException("fixture rollback failure");
                }
                if (method.getName().equals("isWrapperFor")) {
                    return false;
                }
                return null;
            });

        DesktopStorageException failure = assertThrows(DesktopStorageException.class,
            () -> StoreTransactionRunner.execute(connection, () -> {
                throw new SQLException("fixture transaction failure");
            }));

        assertEquals(DesktopStorageFailureType.ROLLBACK_FAILED.code(), failure.problem().eventCode());
        org.junit.jupiter.api.Assertions.assertTrue(failure.problem().diagnosticId().isPresent());
        assertEquals(1, failure.getCause().getSuppressed().length);
    }
}
