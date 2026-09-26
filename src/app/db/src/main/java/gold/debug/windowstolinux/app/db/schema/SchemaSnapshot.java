package gold.debug.windowstolinux.app.db.schema;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.app.db.PlatformDatabase;

/** Fingerprints H2-native structure and rows without creating export files or logs. / 对 H2 原生结构及行计算指纹，不创建导出文件或日志。 */
public final class SchemaSnapshot {
    /** Prevents construction of this stateless snapshot helper. / 禁止构造无状态快照辅助类。 */
    private SchemaSnapshot() {
    }

    /** Hashes the pinned engine's lossless script before and after one exclusive migration. / 在一次独占迁移前后对固定引擎的无损脚本求摘要。
     * @param database original privileged host engine, never exposed to authors / 原宿主特权引擎，绝不交付作者
     * @param source exact registered source / 精确已登记来源
     * @return full lowercase SHA-256 / 完整小写 SHA-256
     * @throws SQLException if source ownership or snapshot generation fails / 来源归属或快照生成失败时 */
    public static String digest(PlatformDatabase database, SchemaCoordinates source) throws SQLException {
        source.requireRegistered(database, Set.of("PREPARING", "READY"));
        try (var c = database.openConnection(); var s = c.createStatement()) {
            source.requirePhysicalOwnership(c);
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update("WTL-H2-2.5.250-SOURCE-1\n".getBytes(StandardCharsets.UTF_8));
            try (var rows = s.executeQuery("SCRIPT SIMPLE NOPASSWORDS NOSETTINGS NOVERSION BLOCKSIZE 4096 SCHEMA "
                    + SchemaAccessScope.quote(source.physicalName()))) {
                while (rows.next()) {
                    String sql = rows.getString(1);
                    if (sql.startsWith("CREATE USER ") || sql.startsWith("CREATE ROLE ") || sql.startsWith("GRANT ")
                            || sql.startsWith("COMMENT ON ") || sql.startsWith("-- "))
                        continue;
                    byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
                    hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                    hash.update(bytes);
                }
            }
            source.requireRegistered(c, Set.of("PREPARING", "READY"));
            source.requirePhysicalOwnership(c);
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("JDK SHA-256 unavailable", unavailable);
        }
    }
}
