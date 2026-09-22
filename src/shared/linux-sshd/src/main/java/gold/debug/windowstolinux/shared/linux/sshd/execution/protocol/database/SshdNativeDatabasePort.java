package gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.database;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.shared.linux.ecosystem.db.NativeDatabasePort;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseException;
import gold.debug.windowstolinux.shared.linux.error.NativeDatabaseFailureType;
import gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.model.ecosystem.db.DatabaseEngineType;

/**
 * Native DB adapter using one fixed root-owned helper, with sensitive fields carried over stdin. / 使用固定 root 持有 helper 的原生数据库适配器，敏感字段通过标准输入传送。
 */
public final class SshdNativeDatabasePort implements NativeDatabasePort {
    /**
     * Bound ssh command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的SSH命令执行器协作对象。
     */
    private final SshCommandExecutor commands;
    /**
     * Validates and binds the inputs required by sshd native database port.
     * <p>校验并绑定Sshd原生数据库端口所需输入。
     *
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public SshdNativeDatabasePort(SshCommandExecutor commands) {
        this.commands = Objects.requireNonNull(commands);
    }

    /**
     * Inspects reviewed database identity or database operation boundary.
     * <p>检查已审阅数据库身份或数据库操作边界。
     *
     * @param engine engine / 引擎
     * @return constructed or resolved inventory / 构造或解析得到的清单
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public Inventory inspectDatabase(DatabaseEngineType engine) throws LinuxOperationException {
        return inventory(invoke("inspect", Map.of("engine", engine.name())));
    }

    /**
     * Invokes database package installation with the reviewed target and any explicit replacement approval.
     * <p>使用已审阅目标及任何显式替换批准调用数据库软件包安装。
     *
     * @param engine engine / 引擎
     * @param target exact destination or managed target of the operation / 操作的精确目的地或受管目标
     * @param replacement replacement / 替换
     * @return constructed or resolved inventory / 构造或解析得到的清单
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public Inventory installDatabase(DatabaseEngineType engine, PackageCandidate target,
            Optional<Replacement> replacement) throws LinuxOperationException {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("engine", engine.name());
        packageFields(input, target);
        replacement.ifPresent(approval -> {
            if (!target.equals(approval.target()))
                throw new IllegalArgumentException("replacement target changed");
            instanceFields(input, approval.previous());
            input.put("approvedServer", approval.serverId());
            input.put("operation", approval.operation().toString());
            input.put("replacementApproved", "true");
        });
        return inventory(invoke("install", input));
    }

    /**
     * Starts reviewed database identity or database operation boundary.
     * <p>启动已审阅数据库身份或数据库操作边界。
     *
     * @param instance instance / 实例
     * @return constructed or resolved instance / 构造或解析得到的实例
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public Instance startDatabase(Instance instance) throws LinuxOperationException {
        Map<String, String> input = new LinkedHashMap<>();
        instanceFields(input, instance);
        return instance(invoke("start", input), "instance.");
    }

    /**
     * Confirms database restored.
     * <p>确认数据库已恢复。
     *
     * @param instance instance / 实例
     * @param administratorPassword administrator password / 管理员密码
     * @return constructed or resolved instance / 构造或解析得到的实例
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    @Override
    public Instance confirmDatabaseRestored(Instance instance, char[] administratorPassword)
            throws LinuxOperationException {
        Map<String, String> input = new LinkedHashMap<>();
        instanceFields(input, instance);
        input.put("adminPassword", new String(administratorPassword));
        return instance(invoke("resume", input), "instance.");
    }

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
    @Override
    public Target inspectDatabaseTarget(Instance instance, String applicationId, String database, String username,
            char[] administratorPassword) throws LinuxOperationException {
        Map<String, String> input = targetInput(instance, applicationId, database, username);
        input.put("adminPassword", new String(administratorPassword));
        return target(invoke("target", input));
    }

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
    @Override
    public Target prepareDatabaseTarget(Instance instance, String applicationId, String database, String username,
            String secretIdentifier, long secretRevision, char[] applicationPassword, char[] administratorPassword)
            throws LinuxOperationException {
        Map<String, String> input = targetInput(instance, applicationId, database, username);
        input.put("secretIdentifier", secretIdentifier);
        input.put("secretRevision", Long.toString(secretRevision));
        input.put("applicationPassword", new String(applicationPassword));
        input.put("adminPassword", new String(administratorPassword));
        return target(invoke("prepare", input));
    }

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
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public Target initializeDatabase(Target target, String sourceSha256, byte[] sql, char[] applicationPassword,
            boolean existingSchemaChangeApproved) throws LinuxOperationException {
        if (sql.length > 2 * 1024 * 1024 || !sourceSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("invalid DB initialization payload");
        Map<String, String> input = targetInput(target.instance(), target.applicationId(), target.database(),
                target.username());
        input.put("ownershipToken", target.ownershipToken());
        input.put("sourceSha256", sourceSha256);
        input.put("sql", new String(sql, StandardCharsets.UTF_8));
        input.put("applicationPassword", new String(applicationPassword));
        input.put("existingApproved", Boolean.toString(existingSchemaChangeApproved));
        return target(invoke("initialize", input));
    }

    /**
     * Invokes the fixed native-database helper operation with separated secret input and validates its bounded protocol response.
     * <p>使用分离秘密输入调用固定原生数据库 helper 操作，并校验有界协议响应。
     *
     * @param operation operation / 操作
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return constructed or resolved map / 构造或解析得到的映射
     * @throws LinuxOperationException if the authenticated remote operation fails or its evidence is rejected / 已认证远端操作失败或其证据被拒绝时
     */
    private Map<String, String> invoke(String operation, Map<String, String> input) throws LinuxOperationException {
        StringBuilder encoded = new StringBuilder();
        input.forEach((key, value) -> encoded.append(key).append('=')
                .append(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))).append('\n'));
        byte[] payload = encoded.toString().getBytes(StandardCharsets.US_ASCII);
        try {
            var response = commands.execProtocolWithInput(
                    gold.debug.windowstolinux.shared.linux.command.CommandText.quote(ManagedHelperBundle.PATH)
                            + " native-db " + operation,
                    payload, Duration.ofMinutes(operation.equals("install") ? 30 : 10));
            if (!response.succeeded())
                throw new NativeDatabaseException(NativeDatabaseFailureType.ACTION_FAILED);
            Map<String, String> values = decode(response.output());
            String status = required(values, "status");
            if (!status.equals("OK")) {
                NativeDatabaseFailureType failure;
                try {
                    failure = NativeDatabaseFailureType.valueOf(status);
                } catch (IllegalArgumentException unknown) {
                    failure = NativeDatabaseFailureType.ACTION_FAILED;
                }
                throw new NativeDatabaseException(failure);
            }
            return values;
        } finally {
            Arrays.fill(payload, (byte) 0);
        }
    }

    /**
     * Decodes map.
     * <p>解码映射。
     *
     * @param output destination receiving the produced content / 接收所生成内容的目标
     * @return map / 映射
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    static Map<String, String> decode(String output) {
        if (output.length() > 128 * 1024)
            throw new NativeDatabaseException(NativeDatabaseFailureType.ACTION_FAILED);
        Map<String, String> result = new LinkedHashMap<>();
        try {
            for (String line : output.lines().toList()) {
                int delimiter = line.indexOf('=');
                if (delimiter < 1)
                    throw new IllegalArgumentException();
                String key = line.substring(0, delimiter);
                String value = new String(Base64.getDecoder().decode(line.substring(delimiter + 1)),
                        StandardCharsets.UTF_8);
                if (!key.matches("[a-zA-Z0-9.]{1,64}") || result.putIfAbsent(key, value) != null)
                    throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException invalid) {
            throw new NativeDatabaseException(NativeDatabaseFailureType.ACTION_FAILED);
        }
        return result;
    }

    /**
     * Combines instance identity with the application, database and database-user input fields.
     * <p>将实例身份与应用、数据库及数据库用户输入字段组合。
     *
     * @param instance instance / 实例
     * @param app app / 应用
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @return constructed or resolved map / 构造或解析得到的映射
     */
    private static Map<String, String> targetInput(Instance instance, String app, String database, String username) {
        Map<String, String> input = new LinkedHashMap<>();
        instanceFields(input, instance);
        input.put("applicationId", app);
        input.put("database", database);
        input.put("username", username);
        return input;
    }

    /**
     * Adds engine, instance identifier and fingerprint fields to the helper request.
     * <p>向 helper 请求添加引擎、实例标识及指纹字段。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param instance instance / 实例
     */
    private static void instanceFields(Map<String, String> values, Instance instance) {
        values.put("engine", instance.engine().name());
        values.put("instanceId", instance.id());
        values.put("fingerprint", instance.fingerprint());
    }

    /**
     * Adds the reviewed package name and package/engine version fields to the helper request.
     * <p>向 helper 请求添加已审阅软件包名称及软件包和引擎版本字段。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param candidate candidate / 候选
     */
    private static void packageFields(Map<String, String> values, PackageCandidate candidate) {
        values.put("package", candidate.name());
        values.put("packageVersion", candidate.packageVersion());
        values.put("engineVersion", candidate.engineVersion());
    }

    /**
     * Parses database engine, instances and conflict evidence from the helper's bounded inventory response.
     * <p>从 helper 有界清单响应解析数据库引擎、实例及冲突证据。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return database engine, instances and conflict evidence from the helper's bounded inventory response / 从 helper 有界清单响应解析数据库引擎、实例及冲突证据
     */
    private static Inventory inventory(Map<String, String> values) {
        DatabaseEngineType engine = DatabaseEngineType.valueOf(required(values, "engine"));
        List<Instance> instances = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        int count = count(values, "instanceCount"), issues = count(values, "conflictCount");
        for (int index = 0; index < count; index++)
            instances.add(instance(values, "instance." + index + "."));
        for (int index = 0; index < issues; index++)
            conflicts.add(required(values, "conflict." + index));
        Optional<PackageCandidate> candidate = values.containsKey("package")
                ? Optional.of(new PackageCandidate(required(values, "package"), required(values, "packageVersion"),
                        required(values, "engineVersion")))
                : Optional.empty();
        return new Inventory(engine, instances, candidate, conflicts);
    }

    /**
     * Checks the item or byte count against the explicit bound before accepting more content.
     * <p>在接受更多内容前按显式边界检查条目数或字节数。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return count as a numeric result / 数量的数值结果
     */
    private static int count(Map<String, String> values, String key) {
        int value = Integer.parseInt(required(values, key));
        if (value < 0 || value > 64)
            throw new NativeDatabaseException(NativeDatabaseFailureType.ACTION_FAILED);
        return value;
    }

    /**
     * Builds instance from the supplied instance inputs.
     * <p>根据所提供实例输入构建实例。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param prefix prefix / 前缀
     * @return instance from the supplied instance inputs / 根据所提供实例输入构建实例
     */
    private static Instance instance(Map<String, String> values, String prefix) {
        return new Instance(DatabaseEngineType.valueOf(required(values, prefix + "engine")),
                required(values, prefix + "id"), required(values, prefix + "version"),
                Integer.parseInt(required(values, prefix + "port")), required(values, prefix + "service"),
                required(values, prefix + "dataDirectory"), required(values, prefix + "fingerprint"),
                Boolean.parseBoolean(required(values, prefix + "running")));
    }

    /**
     * Builds target from the supplied target inputs.
     * <p>根据所提供目标输入构建目标。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @return target from the supplied target inputs / 根据所提供目标输入构建目标
     */
    private static Target target(Map<String, String> values) {
        return new Target(instance(values, "instance."), required(values, "applicationId"),
                required(values, "database"), required(values, "username"),
                Boolean.parseBoolean(required(values, "exists")), Boolean.parseBoolean(required(values, "empty")),
                required(values, "ownershipToken"), InitializationState.valueOf(required(values, "initialization")),
                required(values, "initializedSourceSha256"));
    }

    /**
     * Requires the named input to be present and valid before continuing.
     * <p>继续前要求具名输入存在且有效。
     *
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return required text / 必需文本
     */
    private static String required(Map<String, String> values, String key) {
        if (!values.containsKey(key))
            throw new NativeDatabaseException(NativeDatabaseFailureType.ACTION_FAILED);
        return values.get(key);
    }
}
