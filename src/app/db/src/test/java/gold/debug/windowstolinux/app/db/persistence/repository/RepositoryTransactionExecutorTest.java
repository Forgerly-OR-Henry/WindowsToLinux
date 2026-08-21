package gold.debug.windowstolinux.app.db.persistence.repository;

import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceException;
import gold.debug.windowstolinux.app.db.failure.DesktopPersistenceFailureType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryTransactionExecutorTest {
    @Test
    void stopsWithStructuredFailureWhenRollbackCannotBeVerified() {
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("rollback")) {
                        throw new SQLException("fixture rollback failure");
                    }
                    if (method.getName().equals("isWrapperFor")) {
                        return false;
                    }
                    return null;
                });

        DesktopPersistenceException failure = assertThrows(DesktopPersistenceException.class,
                () -> RepositoryTransactionExecutor.execute(connection,
                        () -> { throw new SQLException("fixture transaction failure"); }));

        assertEquals(DesktopPersistenceFailureType.ROLLBACK_FAILED, failure.failure().definition());
        assertEquals(1, failure.getCause().getSuppressed().length);
    }
}
