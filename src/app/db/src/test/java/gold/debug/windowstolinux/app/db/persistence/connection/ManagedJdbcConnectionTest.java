package gold.debug.windowstolinux.app.db.persistence.connection;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ManagedJdbcConnectionTest {
    @Test
    void standardJdbcStaysUsableWithoutLeakingPhysicalHandles() throws Exception {
        var held = new AtomicInteger(1);
        try (var connection = ManagedJdbcConnection.wrap(DriverManager.getConnection("jdbc:h2:mem:wrapper"),
                held::decrementAndGet)) {
            assertSame(connection, connection.unwrap(Connection.class));
            assertFalse(connection.isWrapperFor(org.h2.jdbc.JdbcConnection.class));
            assertThrows(SQLException.class, () -> connection.unwrap(org.h2.jdbc.JdbcConnection.class));
            try (var statement = connection.createStatement()) {
                assertSame(connection, statement.getConnection());
                assertSame(statement, statement.unwrap(Statement.class));
                statement.execute("CREATE TABLE test(id INT PRIMARY KEY, content CLOB)");
                try (var insert = connection.prepareStatement("INSERT INTO test VALUES(?,?)")) {
                    insert.setInt(1, 1);
                    insert.setString(2, "中文");
                    assertEquals(1, insert.executeUpdate());
                    assertSame(connection, insert.getConnection());
                    var parameters = insert.getParameterMetaData();
                    assertEquals(2, parameters.getParameterCount());
                    assertSame(parameters, parameters.unwrap(ParameterMetaData.class));
                    assertThrows(SQLException.class, () -> parameters.unwrap(org.h2.jdbc.JdbcParameterMetaData.class));
                }
                try (var result = statement.executeQuery("SELECT content FROM test")) {
                    assertSame(statement, result.getStatement());
                    assertTrue(result.next());
                    var clob = result.getClob(1);
                    assertEquals("中文", clob.getSubString(1, 2));
                    clob.free();
                    var columns = result.getMetaData();
                    assertEquals(1, columns.getColumnCount());
                    assertSame(columns, columns.unwrap(ResultSetMetaData.class));
                    assertThrows(SQLException.class, () -> columns.unwrap(org.h2.jdbc.JdbcResultSetMetaData.class));
                }
            }
            var metadata = connection.getMetaData();
            assertSame(connection, metadata.getConnection());
            assertThrows(SQLException.class, metadata::getURL);
            assertEquals(1, held.get());
            connection.close();
            assertEquals(0, held.get());
            connection.close();
            assertEquals(0, held.get());
        }
    }

    @Test
    void closeFailureKeepsOccupancyAndCanBeRetried() throws Exception {
        var attempts = new AtomicInteger();
        var held = new AtomicInteger(1);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var faulty = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> {
                    if (m.getName().equals("close")) {
                        if (attempts.incrementAndGet() == 1)
                            throw new SQLException("injected close failure");
                        closed.set(true);
                        return null;
                    }
                    if (m.getName().equals("isClosed"))
                        return closed.get();
                    throw new UnsupportedOperationException(m.getName());
                });
        var managed = ManagedJdbcConnection.wrap(faulty, held::decrementAndGet);
        assertThrows(SQLException.class, managed::close);
        assertEquals(1, held.get());
        managed.close();
        assertEquals(0, held.get());
    }

    @Test
    void crossThreadUseAndForcedAbortAreRejectedWithoutClosingOwnerConnection() throws Exception {
        try (var connection = ManagedJdbcConnection.wrap(DriverManager.getConnection("jdbc:h2:mem:thread"), () -> {
        })) {
            assertTrue(CompletableFuture.supplyAsync(() -> {
                try {
                    connection.createStatement();
                    return false;
                } catch (SQLException expected) {
                    return true;
                }
            }).get());
            assertThrows(SQLException.class, () -> connection.abort(Runnable::run));
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void uncommittedWorkRollsBackWhenAuthorCloses() throws Exception {
        try (var observer = DriverManager.getConnection("jdbc:h2:mem:rollback");
                var connection = ManagedJdbcConnection.wrap(DriverManager.getConnection("jdbc:h2:mem:rollback"), () -> {
                })) {
            try (var s = observer.createStatement()) {
                s.execute("CREATE TABLE test(id INT)");
            }
            connection.setAutoCommit(false);
            try (var s = connection.createStatement()) {
                s.execute("INSERT INTO test VALUES(1)");
            }
            connection.close();
            try (var s = observer.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM test")) {
                assertTrue(r.next());
                assertEquals(0, r.getInt(1));
            }
        }
    }

    @Test
    void commitExceptionDoesNotClaimRollbackOrReleaseTheConnection() throws Exception {
        var held = new AtomicInteger(1);
        try (var physical = DriverManager.getConnection("jdbc:h2:mem:commit_unknown");
                var observer = DriverManager.getConnection("jdbc:h2:mem:commit_unknown")) {
            try (var s = physical.createStatement()) {
                s.execute("CREATE TABLE test(id INT PRIMARY KEY)");
            }
            var injected = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (p, m, a) -> {
                        try {
                            var value = m.invoke(physical, a);
                            if (m.getName().equals("commit"))
                                throw new SQLException("injected lost acknowledgement", "08007");
                            return value;
                        } catch (java.lang.reflect.InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            try (var connection = ManagedJdbcConnection.wrap(injected, held::decrementAndGet)) {
                connection.setAutoCommit(false);
                try (var s = connection.createStatement()) {
                    s.execute("INSERT INTO test VALUES(1)");
                }
                assertThrows(SQLException.class, connection::commit);
                assertEquals(1, held.get());
                try (var s = observer.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM test")) {
                    assertTrue(r.next());
                    assertEquals(1, r.getInt(1));
                }
            }
            assertEquals(0, held.get());
        }
    }
}
