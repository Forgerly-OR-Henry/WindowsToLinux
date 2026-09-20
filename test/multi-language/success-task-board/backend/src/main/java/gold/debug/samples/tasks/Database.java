package gold.debug.samples.tasks;
import static gold.debug.samples.tasks.Models.*;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

final class Database {
    private final String url;
    Database() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("DATA_DIR", "data")).toAbsolutePath();
        Files.createDirectories(dir);
        url = "jdbc:sqlite:" + dir.resolve("tasks.db");
    }
    Connection open() throws SQLException {
        var c = DriverManager.getConnection(url);
        try {
            exec(c, "PRAGMA foreign_keys=ON");
            exec(c, "PRAGMA busy_timeout=10000");
            return c;
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }
    static PreparedStatement command(Connection c, String sql, Object... values) throws SQLException {
        var s = c.prepareStatement(sql);
        for (int i = 0; i < values.length; i++)
            s.setObject(i + 1, values[i]);
        return s;
    }
    static int exec(Connection c, String sql, Object... values) throws SQLException {
        try (var s = command(c, sql, values)) {
            s.execute();
            return s.getUpdateCount();
        }
    }
    static List<Map<String, Object>> rows(Connection c, String sql, Object... values) throws SQLException {
        try (var s = command(c, sql, values); var r = s.executeQuery()) {
            var out = new ArrayList<Map<String, Object>>();
            var m = r.getMetaData();
            while (r.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= m.getColumnCount(); i++)
                    row.put(m.getColumnLabel(i), r.getObject(i));
                out.add(row);
            }
            return out;
        }
    }
    static Map<String, Object> one(Connection c, String sql, Object... values) throws SQLException {
        var list = rows(c, sql, values);
        if (list.isEmpty())
            throw new BusinessError(404, "记录不存在");
        return list.getFirst();
    }
    static long scalar(Connection c, String sql, Object... values) throws SQLException {
        return ((Number)one(c, sql, values).values().iterator().next()).longValue();
    }
    interface Work<T> {
        T run(Connection c) throws Exception;
    }
    <T> T write(Work<T> work) throws Exception {
        try (var c = open()) {
            exec(c, "BEGIN IMMEDIATE");
            try {
                T result = work.run(c);
                exec(c, "COMMIT");
                return result;
            } catch (Exception e) {
                exec(c, "ROLLBACK");
                throw e;
            }
        }
    }
    static void fault(String point) throws Exception {
        if (!point.equals(System.getenv("SAMPLE_FAULT_POINT")))
            return;
        Path dir =
            Path.of(Objects.requireNonNull(System.getenv("SAMPLE_FAULT_DIR"), "SAMPLE_FAULT_DIR required"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(point + ".ready"), Long.toString(ProcessHandle.current().pid()));
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(dir.resolve(point + ".release")))
                return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("Fault gate expired: " + point);
    }
}
