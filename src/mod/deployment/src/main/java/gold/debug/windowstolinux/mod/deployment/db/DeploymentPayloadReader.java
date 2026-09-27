package gold.debug.windowstolinux.mod.deployment.db;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads bounded persisted documents without materializing an unbounded BLOB. / 读取有界持久文档，不将无界BLOB加载入内存。 */
final class DeploymentPayloadReader {
    /** Prevents utility construction. / 防止构造工具实例。 */
    private DeploymentPayloadReader() {
    }

    /** Preserves SQL NULL and rejects documents above the existing codec limit. / 保留SQL NULL并拒绝超过既有格式上限的文档。
     * @param rows original current row / 原当前行
     * @param column fixed payload column / 固定载荷列
     * @return original bytes or null for SQL NULL / 原字节，SQL NULL时为空
     * @throws SQLException if JDBC cannot read the value / JDBC无法读取值时
     * @throws IOException if reading fails or the document exceeds one MiB / 读取失败或文档超过一MiB时 */
    static byte[] read(ResultSet rows, String column) throws SQLException, IOException {
        try (var stream = rows.getBinaryStream(column)) {
            if (stream == null)
                return null;
            byte[] bytes = stream.readNBytes(1_048_577);
            if (bytes.length > 1_048_576)
                throw new IOException("Stored deployment payload exceeds the format bound");
            return bytes;
        }
    }
}
