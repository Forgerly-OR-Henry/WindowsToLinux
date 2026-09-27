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
        return read(rows, column, 1_048_576);
    }

    /** Reads under the fixed limit declared by the owning format. / 在所属格式声明的固定上限下读取。
     * @param rows original current row / 原当前行
     * @param column fixed payload column / 固定载荷列
     * @param limit declared byte bound / 声明的字节上限
     * @return original bytes or SQL NULL / 原字节或SQL NULL
     * @throws SQLException if JDBC fails / JDBC失败时
     * @throws IOException if reading fails or exceeds the declared bound / 读取失败或超限时 */
    static byte[] read(ResultSet rows, String column, int limit) throws SQLException, IOException {
        try (var stream = rows.getBinaryStream(column)) {
            if (stream == null)
                return null;
            byte[] bytes = stream.readNBytes(Math.addExact(limit, 1));
            if (bytes.length > limit)
                throw new IOException("Stored deployment payload exceeds the format bound");
            return bytes;
        }
    }
}
