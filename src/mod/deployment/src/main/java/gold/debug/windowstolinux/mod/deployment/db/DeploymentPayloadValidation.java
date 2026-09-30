package gold.debug.windowstolinux.mod.deployment.db;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

/** Reads original bounded business payloads through the same versioned codecs used for persistence. / 通过持久化所用版本化编解码器读取原有界业务载荷。 */
final class DeploymentPayloadValidation {
    /** Prevents construction of the fixed read-only validator. / 防止构造固定只读验证器。 */
    private DeploymentPayloadValidation() {
    }

    /** Validates stored non-null runtime, health and reviewed component payloads. / 核验已存非空运行时、健康及组件审阅载荷。
     * @param connection original SELECT-only schema connection / 原仅SELECT结构连接
     * @throws SQLException if stored rows cannot be read / 存储行不可读时
     * @throws IOException if a payload is malformed or exceeds its format bound / 载荷格式错误或超过格式上限时 */
    static void validate(Connection connection) throws SQLException, IOException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT runtime_payload FROM deployment_unit WHERE runtime_payload IS NOT NULL")) {
            while (rows.next())
                new DeploymentUnitRuntimeCodec().read(DeploymentPayloadReader.read(rows, "runtime_payload"));
        }
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT health_check_payload FROM managed_application WHERE health_check_payload IS NOT NULL")) {
            while (rows.next())
                new DeploymentHealthCodec().read(DeploymentPayloadReader.read(rows, "health_check_payload"));
        }
        components(connection);
    }

    /** Resolves component health from its original unit rather than inventing another health copy. / 从组件原部署单元解析健康契约，不另造健康副本。
     * @param connection original read-only connection / 原只读连接
     * @throws SQLException if original rows are unavailable / 原记录不可用时
     * @throws IOException if a reviewed payload lacks its required unit or is malformed / 审阅载荷缺少必需单元或格式错误时 */
    private static void components(Connection connection) throws SQLException, IOException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        """
                                SELECT u.runtime_payload,c.reviewed_runtime_payload,c.reviewed_data_paths_payload,c.reviewed_resource_bindings_payload
                                FROM managed_application_component c JOIN deployment_unit u ON u.deployment_unit_id=c.deployment_unit_id
                                """)) {
            while (rows.next()) {
                byte[] runtime = DeploymentPayloadReader.read(rows, "reviewed_runtime_payload");
                if (runtime != null) {
                    byte[] unit = DeploymentPayloadReader.read(rows, "runtime_payload");
                    if (unit == null)
                        throw new IOException("Reviewed runtime lacks its original unit health contract");
                    var health = new DeploymentUnitRuntimeCodec().read(unit).healthCheck();
                    new DeploymentRuntimeCodec().read(runtime, health);
                }
                byte[] paths = DeploymentPayloadReader.read(rows, "reviewed_data_paths_payload");
                if (paths != null)
                    new DeploymentStoragePathCodec().read(paths);
                byte[] resources = DeploymentPayloadReader.read(rows, "reviewed_resource_bindings_payload");
                if (resources != null)
                    new DeploymentResourceCodec().read(resources);
            }
        }
    }

}
