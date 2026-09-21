package gold.debug.windowstolinux.shared.linux.ecosystem.db;

import gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseEngineType;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import java.util.*;

/**
 * Typed native database operations; passwords and SQL travel only through sensitive input streams. / 类型化原生数据库操作，密码和 SQL 仅通过敏感输入流传输。
 */
public interface NativeDatabasePort {
    /**
     * Inspects reviewed database identity or database operation boundary.
     * <p>检查已审阅数据库身份或数据库操作边界。
     *
     * @param engine engine / 引擎
     * @return constructed or resolved inventory / 构造或解析得到的清单
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    Inventory inspectDatabase(DatabaseEngineType engine) throws LinuxOperationException;
    /**
     * Installs the reviewed database package, requiring explicit replacement evidence when replacing an existing package.
     * <p>安装已审阅数据库软件包，替换既有软件包时要求显式替换证据。
     *
     * @param engine engine / 引擎
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param replacement replacement / 替换
     * @return constructed or resolved inventory / 构造或解析得到的清单
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    Inventory installDatabase(DatabaseEngineType engine, PackageCandidate target, Optional<Replacement> replacement) throws LinuxOperationException;
    /**
     * Starts reviewed database identity or database operation boundary.
     * <p>启动已审阅数据库身份或数据库操作边界。
     *
     * @param instance instance / 实例
     * @return constructed or resolved instance / 构造或解析得到的实例
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    Instance startDatabase(Instance instance) throws LinuxOperationException;
    /**
     * Confirms database restored.
     * <p>确认数据库已恢复。
     *
     * @param instance instance / 实例
     * @param administratorPassword administrator password / 管理员密码
     * @return constructed or resolved instance / 构造或解析得到的实例
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    Instance confirmDatabaseRestored(Instance instance, char[] administratorPassword) throws LinuxOperationException;
    /**
     * Inspects database target.
     * <p>检查数据库目标。
     *
     * @param instance instance / 实例
     * @param applicationId managed application identifier / 受管应用标识
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param administratorPassword administrator password / 管理员密码
     * @return constructed or resolved target / 构造或解析得到的目标
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    Target inspectDatabaseTarget(Instance instance, String applicationId, String database, String username, char[] administratorPassword) throws LinuxOperationException;
    /**
     * Prepares database target.
     * <p>准备数据库目标。
     *
     * @param instance instance / 实例
     * @param applicationId managed application identifier / 受管应用标识
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param secretIdentifier secret identifier / 秘密标识
     * @param secretRevision secret revision / 秘密修订
     * @param applicationPassword application password / 应用密码
     * @param administratorPassword administrator password / 管理员密码
     * @return constructed or resolved target / 构造或解析得到的目标
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    Target prepareDatabaseTarget(Instance instance, String applicationId, String database, String username,
                                 String secretIdentifier, long secretRevision, char[] applicationPassword, char[] administratorPassword) throws LinuxOperationException;
    /**
     * Initializes reviewed database identity or database operation boundary.
     * <p>初始化已审阅数据库身份或数据库操作边界。
     *
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param sourceSha256 SHA-256 identity of the frozen source snapshot / 已冻结源码快照的 SHA-256 身份
     * @param sql reviewed SQL initialization content / 已审阅 SQL 初始化内容
     * @param applicationPassword application password / 应用密码
     * @param existingSchemaChangeApproved existing schema change approved / 既有结构变更已批准
     * @return constructed or resolved target / 构造或解析得到的目标
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    Target initializeDatabase(Target target, String sourceSha256, byte[] sql, char[] applicationPassword,
                              boolean existingSchemaChangeApproved) throws LinuxOperationException;

    /**
     * Describes an available native database package before installation approval.
     * <p>描述安装批准前可用的原生数据库软件包。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @param packageVersion package version / 软件包版本
     * @param engineVersion engine version / 引擎版本
     */
    record PackageCandidate(String name, String packageVersion, String engineVersion) {
        /**
         * Binds the supplied dependencies and state for package candidate.
         * <p>为软件包候选绑定传入的依赖及状态。
         *
         * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
         * @param packageVersion package version / 软件包版本
         * @param engineVersion engine version / 引擎版本
         */
        public PackageCandidate { name = token(name); packageVersion = token(packageVersion); engineVersion = token(engineVersion); }
    }
    /**
     * Describes one discovered native database instance and its current state.
     * <p>描述一个已发现的原生数据库实例及其当前状态。
     *
     * @param engine engine / 引擎
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param dataDirectory data directory / 数据目录
     * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
     * @param running running / 运行中
     */
    record Instance(DatabaseEngineType engine, String id, String version, int port, String service, String dataDirectory,
                    String fingerprint, boolean running) {
        /**
         * Validates and binds the inputs required by instance.
         * <p>校验并绑定实例所需输入。
         *
         * @param engine engine / 引擎
         * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
         * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
         * @param port network port number in the reviewed endpoint / 已审阅端点中的网络端口号
         * @param service application service used by the caller / 调用方使用的应用服务
         * @param dataDirectory data directory / 数据目录
         * @param fingerprint pinned or freshly observed host-key fingerprint / 固定或新近观测的主机密钥指纹
         * @param running running / 运行中
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Instance {
            Objects.requireNonNull(engine); id = text(id); version = text(version); service = token(service);
            dataDirectory = text(dataDirectory); fingerprint = digest(fingerprint);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("invalid DB port");
        }
    }
    /**
     * Groups native database packages and observed instances for target selection.
     * <p>组合原生数据库软件包及实例观测以供目标选择。
     *
     * @param engine engine / 引擎
     * @param instances instances / 实例集合
     * @param candidate candidate / 候选
     * @param conflicts the observed conflicting facts / 观察到的冲突事实
     */
    record Inventory(DatabaseEngineType engine, List<Instance> instances, Optional<PackageCandidate> candidate, List<String> conflicts) {
        /**
         * Validates and binds the inputs required by inventory.
         * <p>校验并绑定清单所需输入。
         *
         * @param engine engine / 引擎
         * @param instances instances / 实例集合
         * @param candidate candidate / 候选
         * @param conflicts the observed conflicting facts / 观察到的冲突事实
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Inventory {
            Objects.requireNonNull(engine); instances = List.copyOf(instances); candidate = Objects.requireNonNull(candidate); conflicts = List.copyOf(conflicts);
            if (instances.size() > 64 || conflicts.size() > 64 || instances.stream().anyMatch(instance -> instance.engine() != engine)) throw new IllegalArgumentException("invalid DB inventory");
        }
    }
    /**
     * One operation's exact instance/version approval; data recovery remains a separate user action. / 本次操作对精确实例和版本的批准，数据恢复仍需用户单独操作。
     *
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param previous previous / 此前
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param operation operation / 操作
     * @param backupConfirmed backup confirmed / 备份已确认
     * @param downtimeConfirmed downtime confirmed / 停机已确认
     */
    record Replacement(String serverId, Instance previous, PackageCandidate target, UUID operation, boolean backupConfirmed, boolean downtimeConfirmed) {
        /**
         * Validates and binds the inputs required by replacement.
         * <p>校验并绑定替换所需输入。
         *
         * @param serverId persisted server identifier / 持久化服务器标识
         * @param previous previous / 此前
         * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
         * @param operation operation / 操作
         * @param backupConfirmed backup confirmed / 备份已确认
         * @param downtimeConfirmed downtime confirmed / 停机已确认
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Replacement {
            serverId = token(serverId); Objects.requireNonNull(previous); Objects.requireNonNull(target); Objects.requireNonNull(operation);
            if (!backupConfirmed || !downtimeConfirmed) throw new IllegalArgumentException("DB replacement requires backup and downtime confirmation");
        }
    }
    /**
     * Distinguishes database initialization evidence before accepting new writes.
     * <p>区分接受新写入前的数据库初始化证据。
     */
    enum InitializationState {
    /**
     * UNOWNED classification within initialization state.
     * <p>初始化状态中的无归属分类。
     */
     UNOWNED,
    /**
     * EMPTY classification within initialization state.
     * <p>初始化状态中的空分类。
     */
     EMPTY,
    /**
     * STARTED classification within initialization state.
     * <p>初始化状态中的已启动分类。
     */
     STARTED,
    /**
     * COMPLETE classification within initialization state.
     * <p>初始化状态中的完整分类。
     */
     COMPLETE,
    /**
     * FAILED classification within initialization state.
     * <p>初始化状态中的失败分类。
     */
     FAILED,
    /**
     * WAITING FOR RESTORE classification within initialization state.
     * <p>初始化状态中的等待对应恢复分类。
     */
     WAITING_FOR_RESTORE }
    /**
     * Identifies an exact native database target admitted for a fixed operation.
     * <p>标识已准入固定操作的精确原生数据库目标。
     *
     * @param instance instance / 实例
     * @param applicationId managed application identifier / 受管应用标识
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param exists exists / 存在
     * @param empty empty / 空
     * @param ownershipToken ownership token / 归属令牌
     * @param initialization initialization / 初始化
     * @param initializedSourceSha256 initialized source sha 256 / 已初始化源码SHA256
     */
    record Target(Instance instance, String applicationId, String database, String username, boolean exists, boolean empty,
                  String ownershipToken, InitializationState initialization, String initializedSourceSha256) {
        /**
         * Validates and binds the inputs required by target.
         * <p>校验并绑定目标所需输入。
         *
         * @param instance instance / 实例
         * @param applicationId managed application identifier / 受管应用标识
         * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
         * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
         * @param exists exists / 存在
         * @param empty empty / 空
         * @param ownershipToken ownership token / 归属令牌
         * @param initialization initialization / 初始化
         * @param initializedSourceSha256 initialized source sha 256 / 已初始化源码SHA256
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        public Target {
            Objects.requireNonNull(instance); applicationId = token(applicationId); database = token(database); username = token(username);
            Objects.requireNonNull(ownershipToken); Objects.requireNonNull(initialization); Objects.requireNonNull(initializedSourceSha256);
            if (!ownershipToken.isEmpty()) ownershipToken = digest(ownershipToken);
            if (!initializedSourceSha256.isEmpty()) initializedSourceSha256 = digest(initializedSourceSha256);
            if (!exists && !empty) throw new IllegalArgumentException("absent DB cannot contain data");
        }
        /**
         * Tests the owned predicate against the supplied evidence.
         * <p>根据所提供证据检查已持有条件。
         *
         * @return true when owned predicate against the supplied evidence, false otherwise / 根据所提供证据检查已持有条件时为 true，否则为 false
         */
        public boolean owned() { return !ownershipToken.isEmpty(); }
    }
    /**
     * Checks token syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查令牌语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return token text / 令牌文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:+~@%=-]{0,255}")) throw new IllegalArgumentException("invalid DB token");
        return value;
    }
    /**
     * Validates textual content against the declared length and character constraints.
     * <p>按声明的长度及字符约束校验文本内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return text text / 文本文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String text(String value) {
        if (value == null || value.isEmpty() || value.length() > 512 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("invalid DB text");
        return value;
    }
    /**
     * Computes or retrieves content identity for independent evidence checks.
     * <p>计算或取得用于独立证据检查的内容身份。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return or retrieves content identity for independent evidence checks / 或取得用于独立证据检查的内容身份
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static String digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("invalid DB fingerprint");
        return value;
    }
}
