package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.linux.CommandResult;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelper;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentNativeDatabaseWire;

/** Sends one original native DB operation and later reads only its exact helper receipt. / 发送一次原生数据库原操作，随后仅读取其精确 helper 回执。 */
public final class DeploymentNativeDatabaseChannel {
    /** Exact reviewed helper transport. / 精确审核的 helper 传输。 */
    private final DeploymentNativeDatabaseWire wire;

    /** Borrowed original session used for an exact helper identity probe. / 用于精确 helper 身份探测的借用原会话。 */
    private final RemoteSession session;

    /** Host review used by the probe. / 探测使用的宿主审核。 */
    private final InteractionService interaction;

    /** Original admitted server. / 原已准入服务器。 */
    private final ServerRef server;

    /** Original task. / 原任务。 */
    private final TaskContext task;

    /** Binds one admitted task, server and borrowed session. / 绑定一项已准入任务、服务器及借用会话。
     * @param session borrowed session / 借用会话
     * @param interaction host review / 宿主审核
     * @param server exact server / 精确服务器
     * @param task original task / 原任务 */
    public DeploymentNativeDatabaseChannel(RemoteSession session, InteractionService interaction, ServerRef server,
            TaskContext task) {
        this.wire = new DeploymentNativeDatabaseWire(session, interaction, server, task);
        this.session = Objects.requireNonNull(session);
        this.interaction = Objects.requireNonNull(interaction);
        this.server = Objects.requireNonNull(server);
        this.task = Objects.requireNonNull(task);
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
                    unsent.problem().code() == ProblemCode.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED,
                    Optional.empty(), Optional.of(unsent.problem()));
        }
        if (identity.outcome() != Outcome.SUCCEEDED)
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
            var journal = DeploymentNativeDatabaseJournal.begin(tasks, task, server,
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
                            uncertain.problem().code() == ProblemCode.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED,
                            Optional.empty(), Optional.of(uncertain.problem()));
                }
                return unknown("DEPLOYMENT_NATIVE_DB_DISPATCH_UNCONFIRMED");
            }
        } finally {
            Arrays.fill(frame, (byte) 0);
        }
    }

    /** Queries only the original durable receipt, without replaying a mutation. / 仅查询原持久回执，不重放变更。
     * @param journal exact original journal / 精确原日志
     * @return reconciled terminal result or retained unknown / 已核对终态或保留未知
     * @throws ApiException if original access or persistence fails / 原访问或持久化失败时 */
    public OperationResult<Void> status(DeploymentNativeDatabaseJournal journal) throws ApiException {
        var facts = journal.facts();
        if (!server.id().equals(facts.serverId()))
            return unknown("DEPLOYMENT_NATIVE_DB_SERVER_CHANGED");
        var identity = new DeploymentManagedHelper(session, interaction, server, task, "native-db-protocol-12").probe();
        if (identity.outcome() != Outcome.SUCCEEDED)
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
            DeploymentNativeDatabaseJournal journal) throws ApiException {
        if (observed.outcome() != Outcome.SUCCEEDED || observed.value().isEmpty())
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
            journal.terminal(phase, boundary);
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
        }
        if ("FAILED".equals(phase) && ("NONE".equals(boundary) || "MAY_HAVE_RUN".equals(boundary))
                && fields.get("receipt.failureCode") != null
                && fields.get("receipt.failureCode").matches("[A-Z][A-Z0-9_]{0,63}")
                && ("OK".equals(fields.get("status"))
                        || fields.get("receipt.failureCode").equals(fields.get("status")))) {
            journal.terminal(phase, boundary);
            return new OperationResult<>(Outcome.FAILED, Optional.empty(),
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
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                event, "Original native database operation needs exact receipt review", Map.of(), Optional.empty())));
    }
}
