package gold.debug.windowstolinux.mod.deployment.linux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Candidate;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Instance;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Inventory;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Target;

/** Strictly decodes non-secret facts returned by the fixed native DB helper. / 严格解码固定原生数据库 helper 返回的非秘密事实。 */
public final class DeploymentNativeDatabaseResults {
    /** Prevents construction of the decoder. / 禁止构造解码器。 */
    private DeploymentNativeDatabaseResults() {
    }

    /** Decodes one bounded inventory without inferring an instance choice. / 解码有界库存，不推断实例选择。
     * @param fields exact helper fields / 精确 helper 字段
     * @param prefix empty for direct replies or result. for receipts / 直接回包为空，回执为 result.
     * @return observed inventory / 已观察库存 */
    public static Inventory inventory(Map<String, String> fields, String prefix) {
        DeploymentDatabaseEngineType engine = engine(required(fields, prefix + "engine"));
        int count = count(fields, prefix + "instanceCount");
        int conflicts = count(fields, prefix + "conflictCount");
        var instances = new ArrayList<Instance>(count);
        var warnings = new ArrayList<String>(conflicts);
        for (int index = 0; index < count; index++)
            instances.add(instance(fields, prefix + "instance." + index + "."));
        for (int index = 0; index < conflicts; index++)
            warnings.add(required(fields, prefix + "conflict." + index));
        Optional<Candidate> candidate = Optional.empty();
        if (fields.containsKey(prefix + "package") || fields.containsKey(prefix + "packageVersion")
                || fields.containsKey(prefix + "engineVersion"))
            candidate = Optional.of(new Candidate(required(fields, prefix + "package"),
                    required(fields, prefix + "packageVersion"), required(fields, prefix + "engineVersion")));
        return new Inventory(engine, instances, candidate, warnings);
    }

    /** Decodes the exact selected instance. / 解码精确选中实例。
     * @param fields exact helper fields / 精确 helper 字段
     * @param prefix field prefix / 字段前缀
     * @return observed instance / 已观察实例 */
    public static Instance instance(Map<String, String> fields, String prefix) {
        int port;
        try {
            port = Integer.parseInt(required(fields, prefix + "port"));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid native database port", invalid);
        }
        return new Instance(engine(required(fields, prefix + "engine")), required(fields, prefix + "id"),
                required(fields, prefix + "version"), port, required(fields, prefix + "service"),
                required(fields, prefix + "dataDirectory"), required(fields, prefix + "fingerprint"),
                bool(fields, prefix + "running"));
    }

    /** Decodes exact target ownership and initialization facts. / 解码精确目标归属与初始化事实。
     * @param fields exact helper fields / 精确 helper 字段
     * @param prefix empty for direct replies or result. for receipts / 直接回包为空，回执为 result.
     * @return observed target / 已观察目标 */
    public static Target target(Map<String, String> fields, String prefix) {
        return new Target(instance(fields, prefix + "instance."), required(fields, prefix + "applicationId"),
                required(fields, prefix + "database"), required(fields, prefix + "username"),
                bool(fields, prefix + "exists"), bool(fields, prefix + "empty"),
                required(fields, prefix + "ownershipToken"), required(fields, prefix + "initialization"),
                required(fields, prefix + "initializedSourceSha256"));
    }

    /** Reads a required wire field, including permitted empty values. / 读取必有线字段，允许约定为空的值。
     * @param fields helper fields / helper 字段
     * @param key exact key / 精确键
     * @return observed value / 已观察值 */
    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null)
            throw new IllegalArgumentException("Missing native database result field: " + key);
        return value;
    }

    /** Parses a bounded decimal count. / 解析有界十进制数量。
     * @param fields helper fields / helper 字段
     * @param key exact key / 精确键
     * @return count from zero through 64 / 零至64的数量 */
    private static int count(Map<String, String> fields, String key) {
        String value = required(fields, key);
        if (!value.matches("0|[1-9][0-9]?") || Integer.parseInt(value) > 64)
            throw new IllegalArgumentException("Invalid native database result count");
        return Integer.parseInt(value);
    }

    /** Parses only the helper's exact boolean spelling. / 仅解析 helper 精确布尔拼写。
     * @param fields helper fields / helper 字段
     * @param key exact key / 精确键
     * @return observed boolean / 已观察布尔值 */
    private static boolean bool(Map<String, String> fields, String key) {
        return switch (required(fields, key)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("Invalid native database result boolean");
        };
    }

    /** Parses only supported native families. / 仅解析已支持的原生数据库族。
     * @param value helper engine name / helper 引擎名
     * @return native family / 原生数据库族 */
    private static DeploymentDatabaseEngineType engine(String value) {
        DeploymentDatabaseEngineType engine = DeploymentDatabaseEngineType.valueOf(value);
        if (engine == DeploymentDatabaseEngineType.SQLITE)
            throw new IllegalArgumentException("SQLite is not a native database instance");
        return engine;
    }
}
