package gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;

/** Non-secret native database inventory and target facts observed from the fixed helper. / 从固定 helper 观察到的不含秘密的原生数据库库存与目标事实。 */
public final class DeploymentNativeDatabaseFacts {
    /** Prevents construction of the pure fact container. / 禁止构造纯事实容器。 */
    private DeploymentNativeDatabaseFacts() {
    }

    /** Exact available package before an installation decision. / 安装决策前精确可用的软件包。
     * @param name package name / 软件包名
     * @param packageVersion exact package version / 精确软件包版本
     * @param engineVersion observed engine version / 已观察引擎版本 */
    public record Candidate(String name, String packageVersion, String engineVersion) {
        /** Requires bounded non-empty package facts. / 要求有界且非空的软件包事实。
         * @param name package name / 软件包名
         * @param packageVersion exact package version / 精确软件包版本
         * @param engineVersion observed engine version / 已观察引擎版本 */
        public Candidate {
            name = token(name);
            packageVersion = token(packageVersion);
            engineVersion = token(engineVersion);
        }
    }

    /** One observed service and data-directory identity. / 一个已观察服务与数据目录身份。
     * @param engine database family / 数据库族
     * @param id exact helper instance ID / 精确 helper 实例 ID
     * @param version observed engine version / 已观察引擎版本
     * @param port observed local port / 已观察本机端口
     * @param service exact systemd service / 精确 systemd 服务
     * @param dataDirectory observed data directory / 已观察数据目录
     * @param fingerprint helper-bound instance fingerprint / helper 绑定实例指纹
     * @param running observed active state / 已观察运行状态 */
    public record Instance(DeploymentDatabaseEngineType engine, String id, String version, int port, String service,
                           String dataDirectory, String fingerprint, boolean running) {
        /** Requires the complete bounded helper identity. / 要求完整有界的 helper 身份。
         * @param engine database family / 数据库族
         * @param id exact instance ID / 精确实例 ID
         * @param version observed engine version / 已观察引擎版本
         * @param port observed port / 已观察端口
         * @param service exact service / 精确服务
         * @param dataDirectory observed data directory / 已观察数据目录
         * @param fingerprint instance fingerprint / 实例指纹
         * @param running active state / 活动状态 */
        public Instance {
            engine = Objects.requireNonNull(engine);
            if (engine == DeploymentDatabaseEngineType.SQLITE)
                throw new IllegalArgumentException("Native instance cannot be SQLite");
            id = bounded(id);
            version = bounded(version);
            service = token(service);
            dataDirectory = bounded(dataDirectory);
            fingerprint = digest(fingerprint);
            if (!dataDirectory.startsWith("/") || port < 1 || port > 65535)
                throw new IllegalArgumentException("Invalid native instance identity");
        }
    }

    /** Bounded helper inventory for one selected family. / 选定数据库族的有界 helper 库存。
     * @param engine selected family / 选定数据库族
     * @param instances observed instances / 已观察实例
     * @param candidate available package / 可用软件包
     * @param conflicts actual conflicts / 实际冲突 */
    public record Inventory(DeploymentDatabaseEngineType engine, List<Instance> instances,
                            Optional<Candidate> candidate, List<String> conflicts) {
        /** Copies bounded inventory without inferring a selection. / 复制有界库存而不推断选择。
         * @param engine selected family / 选定数据库族
         * @param instances observed instances / 已观察实例
         * @param candidate available package / 可用软件包
         * @param conflicts actual conflicts / 实际冲突 */
        public Inventory {
            engine = Objects.requireNonNull(engine);
            instances = List.copyOf(instances);
            candidate = Objects.requireNonNull(candidate);
            conflicts = List.copyOf(conflicts);
            var observed = new java.util.HashSet<String>();
            for (Instance instance : instances)
                if (instance.engine() != engine || !observed.add(instance.engine().name() + "\0" + instance.id()))
                    throw new IllegalArgumentException("Duplicate native database instance");
            if (engine == DeploymentDatabaseEngineType.SQLITE || instances.size() > 64 || conflicts.size() > 64)
                throw new IllegalArgumentException("Invalid native database inventory");
            for (String conflict : conflicts)
                if (conflict == null || conflict.isBlank() || conflict.length() > 256)
                    throw new IllegalArgumentException("Invalid native database conflict");
        }
    }

    /** One actual application database target. / 一个实际应用数据库目标。
     * @param instance exact observed instance / 精确已观察实例
     * @param applicationId managed application / 受管应用
     * @param database database name / 数据库名
     * @param username application account / 应用账户
     * @param exists whether the target exists / 目标是否存在
     * @param empty whether the target has no schema / 目标是否没有结构
     * @param ownershipToken helper-held ownership marker / helper 持有的归属标记
     * @param initialization helper initialization phase / helper 初始化阶段
     * @param initializedSourceSha256 completed source digest, if any / 已完成源码摘要，如有 */
    public record Target(Instance instance, String applicationId, String database, String username, boolean exists,
                         boolean empty, String ownershipToken, String initialization, String initializedSourceSha256) {
        /** Requires a complete bounded target without inventing ownership. / 要求完整有界目标，不虚构归属。
         * @param instance exact observed instance / 精确已观察实例
         * @param applicationId managed application / 受管应用
         * @param database database name / 数据库名
         * @param username application account / 应用账户
         * @param exists whether target exists / 目标是否存在
         * @param empty whether target has no schema / 目标是否没有结构
         * @param ownershipToken helper-held marker / helper 持有标记
         * @param initialization helper phase / helper 阶段
         * @param initializedSourceSha256 completed source digest / 已完成源码摘要 */
        public Target {
            instance = Objects.requireNonNull(instance);
            applicationId = token(applicationId);
            database = token(database);
            username = token(username);
            ownershipToken = Objects.requireNonNull(ownershipToken);
            initializedSourceSha256 = Objects.requireNonNull(initializedSourceSha256);
            if (!exists && !empty
                || !java.util.Set.of("UNOWNED", "EMPTY", "STARTED", "FAILED", "COMPLETE", "WAITING_FOR_RESTORE")
                .contains(initialization))
                throw new IllegalArgumentException("Invalid native database target");
            if (!ownershipToken.isEmpty())
                ownershipToken = digest(ownershipToken);
            if (!initializedSourceSha256.isEmpty())
                initializedSourceSha256 = digest(initializedSourceSha256);
        }
    }

    /** Rejects absent or control-character helper text. / 拒绝空缺或包含控制字符的 helper 文本。
     * @param value exact observed value / 精确已观察值
     * @return validated text / 已校验文本 */
    private static String bounded(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid native database fact");
        return value;
    }

    /** Validates the old helper's business token syntax without narrowing it. / 不缩减旧 helper 业务令牌语法地校验。
     * @param value candidate token / 候选令牌
     * @return exact token / 精确令牌 */
    private static String token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:+~@%=-]{0,255}"))
            throw new IllegalArgumentException("Invalid native database token");
        return value;
    }

    /** Validates an exact lowercase SHA-256 identity. / 校验精确小写 SHA-256 身份。
     * @param value digest / 摘要
     * @return exact digest / 精确摘要 */
    private static String digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid native database digest");
        return value;
    }
}
