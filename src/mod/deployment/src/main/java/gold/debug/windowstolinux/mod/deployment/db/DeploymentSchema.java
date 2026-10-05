package gold.debug.windowstolinux.mod.deployment.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.db.schema.CheckResult;
import gold.debug.windowstolinux.appapi.db.schema.ModSchemaLifecycle;
import gold.debug.windowstolinux.appapi.db.schema.SchemaAccess;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;

/** Initializes and reads the fixed official deployment schema through public managed JDBC. / 通过公开受管 JDBC 初始化并读取固定官方部署结构。 */
public final class DeploymentSchema implements ModSchemaLifecycle {
    /** Exact published owner, never a selectable schema name. / 精确发布所有者，不是可选物理结构名。 */
    public static final String OWNER = "gold.debug.windowstolinux.mod.deployment";

    /** Current independently versioned business data format. / 当前独立版本化业务数据格式。 */
    public static final String VERSION = "1";

    /** Creates the platform-discovered schema lifecycle independently of business startup. / 独立于业务启动创建平台发现的数据生命周期。 */
    public DeploymentSchema() {
    }

    /** Rejects foreign ownership before opening any author connection. / 打开任一作者连接前拒绝其他所有者。
     * @param identity original bound data identity / 原绑定数据身份 */
    public static void requireIdentity(SchemaIdentity identity) {
        if (!OWNER.equals(identity.ownerId()) || !VERSION.equals(identity.schemaVersion()))
            throw new IllegalArgumentException(
                "Deployment schema identity differs from the supported owner and version");
    }

    /** Creates the documented tables only inside the fresh platform target. / 仅在全新平台目标内创建文档约定表。
     * @param target original fresh generation / 原全新代次
     * @throws SQLException if target is nonempty or DDL fails / 目标非空或 DDL 失败时
     * @throws IOException if the fixed schema resource is absent / 固定结构资源缺失时 */
    @Override
    public void initialize(SchemaAccess target) throws SQLException, IOException {
        requireIdentity(target.identity());
        String script = resource("schema-v1.sql");
        try (var connection = target.openConnection()) {
            if (!tables(connection).isEmpty())
                throw new SQLException("Deployment initialization requires an empty target");
            try (var statement = connection.createStatement()) {
                for (var command : script.split(";"))
                    if (!command.isBlank())
                        statement.executeUpdate(command);
            }
        }
    }

    /** Reads exact column inventory and cross-record business constraints without applying DDL. / 只读核验精确列清单及跨记录业务约束，不执行 DDL。
     * @param target original read-only generation / 原只读代次
     * @return deterministic structure evidence and any safe violations / 确定性结构依据及安全违规说明
     * @throws Exception if reading cannot establish a result / 读取无法确立结果时 */
    @Override
    public CheckResult validate(SchemaAccess target) throws Exception {
        requireIdentity(target.identity());
        var violations = new ArrayList<String>();
        String inventory = resource("validation-v1.sql");
        try (var connection = target.openConnection()) {
            var expected = new TreeSet<String>();
            for (var query : inventory.split(";")) {
                if (query.isBlank())
                    continue;
                String declaration = query.replaceAll("\\s+", " ");
                String table = declaration.substring(declaration.indexOf(" FROM ") + 6, declaration.indexOf(" WHERE "));
                expected.add(table);
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
                    int declared = rows.getMetaData().getColumnCount();
                    try (var all = connection.createStatement();
                         var columns = all.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
                        if (declared != columns.getMetaData().getColumnCount())
                            violations.add("Unexpected deployment columns: " + table);
                    }
                } catch (SQLException invalid) {
                    violations.add("Deployment table or columns are unavailable: " + table);
                }
            }
            if (!tables(connection).equals(expected))
                violations.add("Deployment table inventory differs from schema version 1");
            if (violations.isEmpty()) {
                validateBindings(connection, violations);
                try {
                    DeploymentProjectStore.validate(connection);
                } catch (SQLException invalid) {
                    violations.add("Deployment project facts are invalid");
                }
                try {
                    DeploymentConfigStore.validate(connection);
                } catch (SQLException invalid) {
                    violations.add("Stored deployment configuration snapshots are invalid");
                }
                try {
                    DeploymentSecretStore.validate(connection);
                } catch (SQLException invalid) {
                    violations.add("Stored deployment secret metadata or bindings are invalid");
                }
                try {
                    DeploymentReleaseChecker.validate(connection);
                } catch (SQLException invalid) {
                    violations.add("Stored deployment release history is invalid");
                }
                try {
                    DeploymentExecutionStore.validate(connection);
                } catch (SQLException invalid) {
                    violations.add("Stored deployment execution evidence is invalid");
                }
                try {
                    DeploymentPayloadChecker.validate(connection);
                } catch (SQLException | IOException invalid) {
                    violations.add("Stored deployment runtime payloads are invalid");
                }
                if (violations.isEmpty()) {
                    try {
                        DeploymentGraphStore.validate(connection);
                    } catch (SQLException invalid) {
                        violations.add("Stored complete application graphs are invalid");
                    }
                }
                if (violations.isEmpty()) {
                    try {
                        DeploymentPublishStore.validate(connection);
                    } catch (SQLException invalid) {
                        violations.add("Stored current publication facts are invalid");
                    }
                }
            }
        }
        var digest = MessageDigest.getInstance("SHA-256")
            .digest((resource("schema-v1.sql") + inventory).getBytes(StandardCharsets.UTF_8));
        return new CheckResult(violations.isEmpty(), violations,
            violations.isEmpty() ? Optional.of(HexFormat.of().formatHex(digest)) : Optional.empty());
    }

    /** Checks stored relationships that ordinary foreign keys do not bind by digest. / 核对普通外键未按摘要绑定的存储关系。
     * @param connection original read-only connection / 原只读连接
     * @param violations accumulated safe failures / 累积安全失败
     * @throws SQLException if relationships cannot be read / 无法读取关系时 */
    private static void validateBindings(Connection connection, List<String> violations) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(
                 """
                     SELECT COUNT(*) FROM deployment_unit_release_binding b
                     JOIN deployment_unit_configuration_snapshot s ON s.deployment_unit_id=b.deployment_unit_id AND s.configuration_revision=b.configuration_revision
                     WHERE b.configuration_sha256<>s.configuration_sha256
                     """)) {
            rows.next();
            if (rows.getLong(1) != 0)
                violations.add("Release configuration digests differ from their immutable snapshots");
        }
    }

    /** Reads only ordinary tables in the connection's original schema. / 仅读取连接原结构内的普通表。
     * @param connection original author connection / 原作者连接
     * @return exact table names / 精确表名
     * @throws SQLException if metadata is unavailable / 元数据不可用时 */
    private static Set<String> tables(Connection connection) throws SQLException {
        var result = new TreeSet<String>();
        try (var statement = connection.prepareStatement(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE'")) {
            statement.setString(1, connection.getSchema());
            try (var rows = statement.executeQuery()) {
                while (rows.next())
                    result.add(rows.getString(1));
            }
        }
        return result;
    }

    /** Reads a fixed bundled UTF-8 resource, never a caller-provided SQL path. / 读取固定内置 UTF-8 资源，绝不读取调用方提供的 SQL 路径。
     * @param name fixed source resource name / 固定源码资源名
     * @return original script / 原脚本
     * @throws IOException if the original resource is absent / 原资源缺失时 */
    static String resource(String name) throws IOException {
        try (var stream = DeploymentSchema.class.getResourceAsStream(name)) {
            if (stream == null)
                throw new IOException("Deployment schema resource is unavailable");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
