package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.TaskService;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentNativeDatabaseResults;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentNativeDatabaseWire;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentDatabaseEngineType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.InstanceList;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DeploymentNativeDatabaseFacts.Target;

/** Sends one original native DB operation and later reads only its exact helper receipt. / 发送一次原生数据库原操作，随后仅读取其精确 helper 回执。 */
public final class DeploymentNativeDatabaseChannel {
    /** Exact reviewed helper transport. / 精确审核的 helper 传输。 */
    private final DeploymentNativeDatabaseWire wire;

    /** Borrowed original session used for an exact helper identity probe. / 用于精确 helper 身份探测的借用原会话。 */
    private final RemoteSession session;

    /** Host review used by the probe. / 探测使用的宿主审核。 */
    private final InteractionService interaction;

    /** Original admitted server. / 原已准入服务器。 */
    private final ServerId server;

    /** Original task. / 原任务。 */
    private final TaskContext task;

    /** Binds one admitted task, server and borrowed session. / 绑定一项已准入任务、服务器及借用会话。
     * @param session borrowed session / 借用会话
     * @param interaction host review / 宿主审核
     * @param server exact server / 精确服务器
     * @param task original task / 原任务 */
    public DeploymentNativeDatabaseChannel(RemoteSession session, InteractionService interaction, ServerId server,
                                           TaskContext task) {
        this.wire = new DeploymentNativeDatabaseWire(session, interaction, server, task);
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
    }

    /** Inspects actual instances and exact package candidates without making a DB change. / 不改变数据库而检查实际实例和精确包候选。
     * @param engine supported native family / 已支持原生数据库族
     * @return observed bounded inventory / 已观察有界库存
     * @throws ApiException if helper identity or reviewed transport fails / helper 身份或已审核传输失败时 */
    public OperationResult<InstanceList> inspect(DeploymentDatabaseEngineType engine) throws ApiException {
        Objects.requireNonNull(engine);
        if (engine == DeploymentDatabaseEngineType.SQLITE)
            throw new IllegalArgumentException("SQLite has no native inventory");
        var identity = new DeploymentManagedHelper(session, interaction, server, task, "native-db-protocol-12").probe();
        if (identity.outcome() != TaskResult.SUCCEEDED)
            return empty(identity);
        byte[] frame = DeploymentNativeDatabaseWire.frame(Map.of("engine", engine.name()));
        try {
            return readOnly("inspect", frame, engine.name(), fields -> {
                var inventory = DeploymentNativeDatabaseResults.inventory(fields, "");
                if (inventory.engine() != engine)
                    throw new IllegalArgumentException("Native inventory returned another engine");
                return inventory;
            });
        } finally {
            Arrays.fill(frame, (byte) 0);
        }
    }

    /** Inspects one actual application target without making a DB change. / 不改变数据库而检查一个实际应用目标。
     * @param fields original bounded helper request, excluding secrets / 不含秘密的原有界 helper 请求
     * @return observed target / 已观察目标
     * @throws ApiException if helper identity or reviewed transport fails / helper 身份或已审核传输失败时 */
    public OperationResult<Target> target(Map<String, String> fields) throws ApiException {
        return target(fields, new byte[0]);
    }

    /** Inspects one target using a borrowed, clearable administrator input after local socket authentication fails. / 本机 socket 认证失败后以借用可清零管理员输入检查目标。
     * @param fields bounded non-secret target fields / 有界非秘密目标字段
     * @param administratorPassword borrowed UTF-8 bytes, possibly empty / 借用 UTF-8 字节，可为空
     * @return observed target / 已观察目标
     * @throws ApiException if helper identity or reviewed transport fails / helper 身份或已审核传输失败时 */
    public OperationResult<Target> target(Map<String, String> fields, byte[] administratorPassword)
        throws ApiException {
        Objects.requireNonNull(administratorPassword);
        if (fields.keySet().stream().anyMatch(Set.of("adminPassword", "applicationPassword", "sql")::contains))
            throw new IllegalArgumentException("Administrator password must remain mutable bytes");
        var identity = new DeploymentManagedHelper(session, interaction, server, task, "native-db-protocol-12").probe();
        if (identity.outcome() != TaskResult.SUCCEEDED)
            return empty(identity);
        byte[] frame = DeploymentNativeDatabaseWire.sensitiveFrame(fields,
            administratorPassword.length == 0 ? Map.of() : Map.of("adminPassword", administratorPassword));
        try {
            return readOnly("target", frame, fields.getOrDefault("applicationId", "native-database"),
                result -> DeploymentNativeDatabaseResults.target(result, ""));
        } finally {
            Arrays.fill(frame, (byte) 0);
        }
    }

    /** Parses a reviewed read-only result without inferring missing facts. / 解析已审核只读结果，不猜测缺失事实。
     * @param action fixed helper action / 固定 helper 动作
     * @param frame caller-owned exact frame / 调用方持有的精确帧
     * @param target reviewed target / 已审核目标
     * @param decode strict non-secret result decoder / 严格非秘密结果解码器
     * @return observed result / 已观察结果
     * @param <T> result type / 结果类型
     * @throws ApiException if reviewed transport fails / 已审核传输失败时 */
    private <T> OperationResult<T> readOnly(String action, byte[] frame, String target,
                                            java.util.function.Function<Map<String, String>, T> decode) throws ApiException {
        var observed = wire.send(action, frame, DeploymentNativeDatabaseWire.digest(frame), target, () -> {
        });
        if (observed.outcome() != TaskResult.SUCCEEDED)
            return empty(observed);
        if (observed.value().isEmpty())
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_NATIVE_DB_INSPECT_MISSING",
                    "Native database inspection returned no command result", Map.of(), Optional.empty())));
        var command = observed.value().orElseThrow();
        if (!command.exitCode().equals(Optional.of(0)) || command.truncated() || !command.stderr().isEmpty())
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_NATIVE_DB_INSPECT_UNCONFIRMED",
                    "Native database read-only result was not confirmed", Map.of(), Optional.empty())));
        try {
            var fields = DeploymentNativeDatabaseWire.fields(command.stdout());
            String status = fields.get("status");
            if (status == null || !status.matches("[A-Z][A-Z0-9_]{0,63}"))
                throw new IllegalArgumentException("Invalid native database status");
            if (!"OK".equals(status))
                return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_NATIVE_DB_INSPECT_FAILED",
                        "Native database inspection failed", Map.of("reason", status), Optional.empty())));
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(decode.apply(fields)), Optional.empty());
        } catch (IllegalArgumentException invalid) {
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_NATIVE_DB_INSPECT_INVALID",
                    "Native database inspection returned invalid facts", Map.of(), Optional.empty())));
        }
    }

    /** Keeps a non-successful original observation without a payload. / 保留不成功的原观察结果但不携带载荷。
     * @param observed original observation / 原观察结果
     * @return empty result / 空结果
     * @param <T> result type / 结果类型 */
    private static <T> OperationResult<T> empty(OperationResult<?> observed) {
        return new OperationResult<>(observed.outcome(), Optional.empty(), observed.problem());
    }

    /** Starts only the selected existing instance under an original durable operation identity. / 仅以原持久操作身份启动选定既有实例。
     * @param tasks original checkpoint service / 原检查点服务
     * @param engine selected engine / 选定引擎
     * @param instanceId selected instance / 选定实例
     * @param fingerprint observed instance fingerprint / 已观察实例指纹
     * @return exact known result or retained uncertainty / 精确已知结果或保留未知
     * @throws ApiException if original persistence or admission fails / 原持久化或准入失败时 */
    public OperationResult<Void> start(TaskService tasks, String engine, String instanceId, String fingerprint)
        throws ApiException {
        if (!Set.of("POSTGRESQL", "MYSQL", "MARIADB", "REDIS").contains(engine) || instanceId == null
            || instanceId.isBlank() || instanceId.length() > 256 || instanceId.indexOf('\0') >= 0
            || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid selected native database instance");
        final OperationResult<DeploymentManagedHelper.Identity> identity;
        try {
            identity = new DeploymentManagedHelper(session, interaction, server, task, "native-db-protocol-12").probe();
        } catch (ApiException unsent) {
            return new OperationResult<>(
                unsent.problem().code() == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                Optional.empty(), Optional.of(unsent.problem()));
        }
        if (identity.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(identity);
        var values = new LinkedHashMap<String, String>();
        values.put("operationId", UUID.randomUUID().toString());
        values.put("taskId", task.id().toString());
        values.put("serverId", server.id());
        values.put("engine", engine);
        values.put("instanceId", instanceId);
        values.put("fingerprint", fingerprint);
        String targetDigest = DeploymentNativeDatabaseWire
            .digest((engine + '\0' + instanceId + '\0' + fingerprint).getBytes(StandardCharsets.UTF_8));
        byte[] frame = DeploymentNativeDatabaseWire.frame(values);
        try {
            var journal = DeploymentNativeDatabaseActionLog.begin(tasks, task, server,
                UUID.fromString(values.get("operationId")), "start", targetDigest,
                DeploymentNativeDatabaseWire.digest(frame), engine + ":" + instanceId);
            var dispatched = new java.util.concurrent.atomic.AtomicBoolean();
            var approvalReturned = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                var observed = wire.send("start", frame, journal.facts().requestDigest(), journal.facts().target(),
                    () -> {
                        approvalReturned.set(true);
                        journal.dispatched();
                        dispatched.set(true);
                    });
                if (!dispatched.get()) {
                    journal.close();
                    return withoutValue(observed);
                }
                return confirmed(observed, journal);
            } catch (ApiException uncertain) {
                if (!approvalReturned.get()) {
                    journal.close();
                    return new OperationResult<>(
                        uncertain.problem().code() == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                        Optional.empty(), Optional.of(uncertain.problem()));
                }
                return unknown("DEPLOYMENT_NATIVE_DB_DISPATCH_UNCONFIRMED");
            }
        } finally {
            Arrays.fill(frame, (byte) 0);
        }
    }

    /** Sends one indexed database mutation under the same original publication task. / 在同一原发布任务下发送一项已索引数据库变更。
     * @param tasks original checkpoint service / 原检查点服务
     * @param plan pre-effect durable whole-database index / 副作用前持久化的全数据库索引
     * @param entry exact component/database pair / 精确组件数据库对
     * @param action fixed helper mutation / 固定 helper 变更
     * @param fields non-secret actual request fields / 非秘密实际请求字段
     * @param sensitive borrowed sensitive bytes, never Strings / 借用敏感字节，绝非字符串
     * @param target bounded reviewed business target / 有界已审阅业务目标
     * @return exact known non-secret result or retained uncertainty / 精确已知非秘密结果或保留未知
     * @throws ApiException if original persistence or admission fails / 原持久化或准入失败时 */
    public OperationResult<Map<String, String>> perform(TaskService tasks, DeploymentDatabasePlanActionLog plan,
                                                        DeploymentDatabasePlanActionLog.Entry entry, String action, Map<String, String> fields,
                                                        Map<String, byte[]> sensitive, String target) throws ApiException {
        if (!Set.of("install", "start", "resume", "prepare", "initialize").contains(action)
            || !plan.facts().serverId().equals(server.id()) || !plan.facts().keys().contains(entry.key())
            || fields.keySet().stream()
            .anyMatch(Set.of("operationId", "taskId", "serverId", "adminPassword", "applicationPassword",
                "sql")::contains)
            || sensitive.keySet().stream()
            .anyMatch(key -> !Set.of("adminPassword", "applicationPassword", "sql").contains(key)))
            throw new IllegalArgumentException("Unplanned native database operation");
        var identity = new DeploymentManagedHelper(session, interaction, server, task, "native-db-protocol-12").probe();
        if (identity.outcome() != TaskResult.SUCCEEDED)
            return empty(identity);
        var actual = new LinkedHashMap<>(fields);
        UUID operationId = UUID.randomUUID();
        actual.put("operationId", operationId.toString());
        actual.put("taskId", task.id().toString());
        actual.put("serverId", server.id());
        String targetDigest = DeploymentNativeDatabaseWire.targetDigest(action, actual);
        byte[] frame = DeploymentNativeDatabaseWire.sensitiveFrame(actual, sensitive);
        try {
            String key = DeploymentNativeDatabaseActionLog.KEY + "." + entry.key();
            String requestDigest = DeploymentNativeDatabaseWire.digest(frame);
            var previous = DeploymentNativeDatabaseActionLog.find(tasks, task, key);
            var journal = previous.orElse(null);
            if (journal == null)
                journal = DeploymentNativeDatabaseActionLog.begin(tasks, task, server, key, operationId, action,
                    targetDigest, requestDigest, target);
            else
                journal.next(operationId, action, targetDigest, requestDigest, target);
            var current = journal;
            var dispatched = new java.util.concurrent.atomic.AtomicBoolean();
            var approvalReturned = new java.util.concurrent.atomic.AtomicBoolean();
            try {
                var observed = wire.send(action, frame, requestDigest, target, () -> {
                    approvalReturned.set(true);
                    current.dispatched();
                    dispatched.set(true);
                });
                if (!dispatched.get()) {
                    current.close();
                    return empty(observed);
                }
                var settled = confirmed(observed, current, true, actual);
                if (settled.outcome() != TaskResult.SUCCEEDED)
                    return empty(settled);
                var receipt = DeploymentNativeDatabaseWire.fields(observed.value().orElseThrow().stdout());
                var output = new LinkedHashMap<String, String>();
                receipt.forEach((name, value) -> {
                    if (name.startsWith("result."))
                        output.put(name.substring(7), value);
                });
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(Map.copyOf(output)), Optional.empty());
            } catch (ApiException uncertain) {
                if (!approvalReturned.get()) {
                    current.close();
                    return new OperationResult<>(
                        uncertain.problem().code() == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                        Optional.empty(), Optional.of(uncertain.problem()));
                }
                return empty(unknown("DEPLOYMENT_NATIVE_DB_DISPATCH_UNCONFIRMED"));
            }
        } finally {
            Arrays.fill(frame, (byte) 0);
        }
    }

    /** Queries only the original durable receipt, without replaying a mutation. / 仅查询原持久回执，不重放变更。
     * @param journal exact original journal / 精确原日志
     * @return reconciled terminal result or retained unknown / 已核对终态或保留未知
     * @throws ApiException if original access or persistence fails / 原访问或持久化失败时 */
    public OperationResult<Void> status(DeploymentNativeDatabaseActionLog journal) throws ApiException {
        var facts = journal.facts();
        if (!server.id().equals(facts.serverId()))
            return unknown("DEPLOYMENT_NATIVE_DB_SERVER_CHANGED");
        var identity = new DeploymentManagedHelper(session, interaction, server, task, "native-db-protocol-12").probe();
        if (identity.outcome() != TaskResult.SUCCEEDED)
            return unknown("DEPLOYMENT_NATIVE_DB_HELPER_IDENTITY_UNCONFIRMED");
        var values = new LinkedHashMap<String, String>();
        values.put("operationId", facts.operationId().toString());
        values.put("taskId", task.id().toString());
        values.put("serverId", facts.serverId());
        values.put("action", facts.action());
        values.put("targetDigest", facts.targetDigest());
        values.put("requestDigest", facts.requestDigest());
        byte[] frame = DeploymentNativeDatabaseWire.frame(values);
        try {
            try {
                return confirmed(wire.send("status", frame, facts.requestDigest(), facts.target(), () -> {
                }), journal);
            } catch (ApiException uncertain) {
                return unknown("DEPLOYMENT_NATIVE_DB_STATUS_UNAVAILABLE");
            }
        } finally {
            Arrays.fill(frame, (byte) 0);
        }
    }

    /** Confirms an exact original operation receipt. / 核对精确的原操作回执。
     * @param observed observed command result / 已观测的命令结果
     * @param journal original journal / 原检查点
     * @return confirmed result or unknown / 已确认或未知结果
     * @throws ApiException when the journal cannot be read or advanced / 检查点无法读取或推进时
     */
    private OperationResult<Void> confirmed(OperationResult<CommandResult> observed,
                                            DeploymentNativeDatabaseActionLog journal) throws ApiException {
        return confirmed(observed, journal, false, Map.of());
    }

    /** Validates optional typed result before persisting the terminal receipt. / 持久终态回执前可选校验类型化结果。
     * @param observed observed command / 已观察命令
     * @param journal original journal / 原日志
     * @param typed whether the caller requires a complete result / 调用方是否要求完整结果
     * @param expected exact non-secret request target / 精确非秘密请求目标
     * @return exact known result or unknown / 精确已知结果或未知
     * @throws ApiException if persistence fails / 持久化失败时 */
    private OperationResult<Void> confirmed(OperationResult<CommandResult> observed,
                                            DeploymentNativeDatabaseActionLog journal, boolean typed, Map<String, String> expected) throws ApiException {
        if (observed.outcome() != TaskResult.SUCCEEDED || observed.value().isEmpty())
            return unknown("DEPLOYMENT_NATIVE_DB_RECEIPT_UNCONFIRMED");
        var command = observed.value().orElseThrow();
        if (!command.exitCode().equals(Optional.of(0)) || command.truncated() || !command.stderr().isEmpty())
            return unknown("DEPLOYMENT_NATIVE_DB_RECEIPT_UNCONFIRMED");
        Map<String, String> fields;
        try {
            fields = DeploymentNativeDatabaseWire.fields(command.stdout());
        } catch (IllegalArgumentException invalid) {
            return unknown("DEPLOYMENT_NATIVE_DB_RECEIPT_INVALID");
        }
        var facts = journal.facts();
        if (!"1".equals(fields.get("receipt.formatVersion"))
            || !facts.operationId().toString().equals(fields.get("receipt.operationId"))
            || !task.id().toString().equals(fields.get("receipt.taskId"))
            || !facts.serverId().equals(fields.get("receipt.serverId"))
            || !facts.action().equals(fields.get("receipt.action"))
            || !facts.targetDigest().equals(fields.get("receipt.targetDigest"))
            || !facts.requestDigest().equals(fields.get("receipt.requestDigest")))
            return unknown("DEPLOYMENT_NATIVE_DB_RECEIPT_CONFLICT");
        String phase = fields.get("receipt.phase");
        String boundary = fields.get("receipt.effectBoundary");
        if ("SUCCEEDED".equals(phase) && "CONFIRMED".equals(boundary) && "OK".equals(fields.get("status"))
            && !fields.containsKey("receipt.failureCode")) {
            if (typed) {
                try {
                    switch (facts.action()) {
                        case "install" -> {
                            if (!DeploymentNativeDatabaseResults.inventory(fields, "result.").engine().name()
                                .equals(expected.get("engine")))
                                throw new IllegalArgumentException("Native DB installation returned another engine");
                        }
                        case "start", "resume" -> {
                            var instance = DeploymentNativeDatabaseResults.instance(fields, "result.instance.");
                            if (!instance.engine().name().equals(expected.get("engine"))
                                || !instance.id().equals(expected.get("instanceId")))
                                throw new IllegalArgumentException("Native DB operation returned another instance");
                        }
                        case "prepare", "initialize" -> {
                            var targetResult = DeploymentNativeDatabaseResults.target(fields, "result.");
                            if (!targetResult.instance().engine().name().equals(expected.get("engine"))
                                || !targetResult.instance().id().equals(expected.get("instanceId"))
                                || !targetResult.applicationId().equals(expected.get("applicationId"))
                                || !targetResult.database().equals(expected.get("database"))
                                || !targetResult.username().equals(expected.get("username")))
                                throw new IllegalArgumentException("Native DB operation returned another target");
                        }
                        default -> throw new IllegalArgumentException("Unexpected native database result action");
                    }
                } catch (IllegalArgumentException invalid) {
                    return unknown("DEPLOYMENT_NATIVE_DB_RESULT_INVALID");
                }
            }
            journal.terminal(phase, boundary);
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
        }
        if ("FAILED".equals(phase) && ("NONE".equals(boundary) || "MAY_HAVE_RUN".equals(boundary))
            && fields.get("receipt.failureCode") != null
            && fields.get("receipt.failureCode").matches("[A-Z][A-Z0-9_]{0,63}")
            && ("OK".equals(fields.get("status"))
            || fields.get("receipt.failureCode").equals(fields.get("status")))) {
            journal.terminal(phase, boundary);
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_NATIVE_DB_HELPER_FAILED",
                    "Native database operation failed; inspect original server and target before retry",
                    Map.of("serverId", facts.serverId(), "target", facts.target(), "failureCode",
                        fields.get("receipt.failureCode")),
                    Optional.empty())));
        }
        return unknown("DEPLOYMENT_NATIVE_DB_RECEIPT_UNCONFIRMED");
    }

    /** Retains an outcome without a command payload. / 保留结果分类而不携带命令载荷。
     * @param value observed result / 已观测结果
     * @return result without a value / 无载荷结果
     */
    private static OperationResult<Void> withoutValue(OperationResult<?> value) {
        return new OperationResult<>(value.outcome(), Optional.empty(), value.problem());
    }

    /** Reports an unresolved original operation. / 报告尚未核实的原操作。
     * @param event stable event code / 稳定事件码
     * @return unknown result / 未知结果
     */
    private static OperationResult<Void> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
            event, "Original native database operation needs exact receipt review", Map.of(), Optional.empty())));
    }
}
