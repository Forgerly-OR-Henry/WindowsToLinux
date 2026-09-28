package gold.debug.windowstolinux.mod.deployment.secret;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.*;
import gold.debug.windowstolinux.appapi.server.*;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRevision;

/** Creates one credential and immutable revision under an original task, then only reconciles durable facts. / 在原任务下创建一次凭据及不可变修订，此后仅核对持久依据。 */
public final class DeploymentSecretProvisionFlow implements TaskRecovery {
    /** Original recoverable task type. / 原可恢复任务类型。 */
    public static final String TASK_TYPE = "deployment-secret-revision";

    /** Bound platform capabilities. / 绑定的平台能力。 */
    private final ModContext context;

    /** Exact immutable business rows. / 精确不可变业务行。 */
    private final DeploymentSecretRepository revisions;

    /** Binds one actual Mod activation. / 绑定一次实际 Mod 启用。
     * @param context original Mod context / 原 Mod 上下文 */
    public DeploymentSecretProvisionFlow(ModContext context) {
        this.context = Objects.requireNonNull(context);
        this.revisions = new DeploymentSecretRepository(context.db());
    }

    /** Submits one non-replayed creation; plaintext is obtained only after task admission. / 提交一次不重放的创建；明文仅在任务准入后取得。
     * @param server original server scope / 原服务器作用域
     * @param revision reviewed immutable business identity / 已审阅不可变业务身份
     * @param purpose exact platform secret purpose / 精确平台秘密用途
     * @param createdAt reviewed millisecond creation time / 已审阅毫秒创建时间
     * @param value one-shot callback owning its returned array / 拥有所返回数组的一次性回调
     * @return original task handle / 原任务句柄
     * @throws ApiException if admission or pure input validation fails / 准入或纯输入校验失败时 */
    public TaskHandle<DeploymentSecretRevision> submit(ServerRef server, DeploymentSecretReference revision,
            String purpose, Instant createdAt, SecretValueSource value) throws ApiException {
        Objects.requireNonNull(value);
        try {
            DeploymentSecretProvisionJournal.validateInput(server, revision, purpose, createdAt);
        } catch (RuntimeException invalid) {
            throw problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SECRET_INPUT");
        }
        return context.tasks().submit(new TaskRequest(TASK_TYPE, "Secret revision", List.of(server.id()), true),
                task -> execute(task, server, revision, purpose, createdAt, value, false));
    }

    /** Transfers a page-owned plaintext buffer to one admitted task and clears it on every outcome. / 将页面所属明文缓冲区转交一项已准入任务，并在所有结果下清零。
     * @param server original server scope / 原服务器作用域
     * @param revision reviewed immutable identity / 经审阅不可变身份
     * @param purpose exact business purpose / 精确业务用途
     * @param createdAt reviewed creation time / 经审阅创建时间
     * @param value one-shot callback creating a newly owned byte array only after admission / 仅准入后新建所属字节数组的一次性回调
     * @return original task handle / 原任务句柄
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<DeploymentSecretRevision> submitOwned(ServerRef server, DeploymentSecretReference revision,
            String purpose, Instant createdAt, SecretValueSource value) throws ApiException {
        Objects.requireNonNull(value);
        try {
            DeploymentSecretProvisionJournal.validateInput(server, revision, purpose, createdAt);
        } catch (RuntimeException invalid) {
            throw problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SECRET_INPUT");
        }
        return context.tasks().submit(new TaskRequest(TASK_TYPE, "Secret revision", List.of(server.id()), true),
                task -> execute(task, server, revision, purpose, createdAt, value, true));
    }

    /** Reconciles the original task without creating a credential or reservation. / 不创建凭据或预留地核对原任务。
     * @param previous retained original task / 留存原任务
     * @param task authentic original recovery context / 真实原恢复上下文
     * @return known result or retained uncertainty / 已知结果或保留未知 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) {
        if (!TASK_TYPE.equals(previous.type()) || !previous.id().equals(task.id()))
            return unknown();
        try {
            var original = DeploymentSecretProvisionJournal.find(context.tasks(), task);
            return original.isPresent() ? settle(task, original.orElseThrow()) : unknown();
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            return unknown();
        }
    }

    /** Runs the single create, reserve and immutable write after each required checkpoint. / 每道必要检查点后运行唯一创建、预留和不可变写入。
     * @param task original accepted task / 原已接纳任务
     * @param server reviewed server / 已审阅服务器
     * @param revision reviewed revision / 已审阅修订
     * @param purpose reviewed purpose / 已审阅用途
     * @param createdAt reviewed time / 已审阅时间
     * @param value one-shot plaintext source / 一次性明文来源
     * @param ownsOriginal whether the flow clears the returned source array / 流程是否清零返回的来源数组
     * @return observed result / 已观察结果 */
    private OperationResult<DeploymentSecretRevision> execute(TaskContext task, ServerRef server,
            DeploymentSecretReference revision, String purpose, Instant createdAt, SecretValueSource value,
            boolean ownsOriginal) {
        try {
            if (revisions.findRevision(task, revision).isPresent())
                return failed("DEPLOYMENT_SECRET_REVISION_EXISTS");
        } catch (ApiException rejected) {
            return new OperationResult<>(
                    rejected.problem().code() == ProblemCode.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED,
                    Optional.empty(), Optional.of(rejected.problem()));
        } catch (SQLException unreadable) {
            return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SECRET_PREFLIGHT_FAILED",
                            "Existing secret revision could not be checked", Map.of(), Optional.empty())));
        }
        DeploymentSecretProvisionJournal journal = null;
        try {
            journal = DeploymentSecretProvisionJournal.begin(context.tasks(), task, server, revision, purpose,
                    createdAt);
            byte[] original;
            try {
                original = Objects.requireNonNull(value.obtain(task));
            } catch (Exception | LinkageError | AssertionError invalid) {
                journal.knownFailure(
                        task.cancellation().requested() || invalid instanceof java.util.concurrent.CancellationException
                                ? new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_SECRET_CANCELLED",
                                        "Secret creation was cancelled before it began", Map.of(), Optional.empty())
                                : new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SECRET_INPUT_FAILED",
                                        "Secret input could not be obtained", Map.of(), Optional.empty()));
                return result(settle(task, journal), journal);
            }
            try {
                if (task.cancellation().requested()) {
                    journal.knownFailure(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_SECRET_CANCELLED",
                            "Secret creation was cancelled before it began", Map.of(), Optional.empty()));
                    return result(settle(task, journal), journal);
                }
                if (original.length > 1024 * 1024) {
                    journal.knownFailure(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SECRET_INPUT_SIZE",
                            "Secret input exceeds the platform bound", Map.of(), Optional.empty()));
                    return result(settle(task, journal), journal);
                }
                byte[] copy = original.clone();
                SecretRef created;
                try {
                    journal.createStarted();
                    created = context.secrets().create(purpose, copy, task);
                } finally {
                    Arrays.fill(copy, (byte) 0);
                }
                journal.created(created);
                journal.reserveStarted();
                var intent = journal.intent();
                var existing = context.secrets().findReference(created, intent.businessKey(), task);
                var reference = existing.isPresent()
                        ? existing.orElseThrow()
                        : context.secrets().reserve(created, intent.businessKey(), task);
                journal.reserved(reference.id());
                journal.writeStarted();
                try {
                    revisions.saveRevision(task,
                            new DeploymentSecretRevision(revision, created.id(), createdAt, Optional.of(purpose)));
                } catch (SQLException knownRollback) {
                    journal.knownFailure(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SECRET_SAVE_FAILED",
                            "Secret revision was not saved", Map.of(), Optional.empty()));
                    return result(settle(task, journal), journal);
                }
                journal.saved();
                return result(settle(task, journal), journal);
            } finally {
                if (ownsOriginal)
                    Arrays.fill(original, (byte) 0);
            }
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            if (journal == null)
                return unknownValue();
            try {
                journal.refresh();
                return result(settle(task, journal), journal);
            } catch (Exception | LinkageError | AssertionError stillUnconfirmed) {
                return unknownValue();
            }
        }
    }

    /** Converts a verified final result to the directly available business value. / 将已核实终态转换为本次可用业务值。
     * @param settled original settled result / 原已收尾结果
     * @param journal original facts / 原依据
     * @return exact current result / 精确当前结果 */
    private static OperationResult<DeploymentSecretRevision> result(OperationResult<Void> settled,
            DeploymentSecretProvisionJournal journal) {
        if (settled.outcome() == Outcome.SUCCEEDED) {
            var facts = journal.facts();
            return new OperationResult<>(
                    Outcome.SUCCEEDED, Optional.of(new DeploymentSecretRevision(facts.revision(),
                            facts.credentialId().orElseThrow(), facts.createdAt(), Optional.of(facts.purpose()))),
                    Optional.empty());
        }
        return new OperationResult<>(settled.outcome(), Optional.empty(), settled.problem());
    }

    /** Completes only on exact business and original platform reference facts. / 仅按精确业务与原平台引用依据完成。
     * @param task original task / 原任务
     * @param journal original checkpoint / 原检查点
     * @return verified result / 已核实结果
     * @throws SQLException if business facts cannot be read / 业务依据无法读取时
     * @throws ApiException if original capability cannot be confirmed / 原能力无法确认时 */
    private OperationResult<Void> settle(TaskContext task, DeploymentSecretProvisionJournal journal)
            throws SQLException, ApiException {
        journal.refresh();
        var facts = journal.facts();
        if (facts.phase() == DeploymentSecretProvisionJournal.PhaseType.CREATE_STARTED)
            return unknown();
        if (facts.phase() == DeploymentSecretProvisionJournal.PhaseType.RESERVE_STARTED) {
            var secret = secret(facts);
            var found = context.secrets().findReference(secret, journal.intent().businessKey(), task);
            if (found.isPresent()) {
                journal.reserved(found.orElseThrow().id());
                facts = journal.facts();
            }
        }
        var current = revisions.findRevision(task, facts.revision());
        if (current.isPresent() && facts.credentialId().isPresent() && !current.orElseThrow().equals(revision(facts)))
            return unknown();
        if (facts.phase() == DeploymentSecretProvisionJournal.PhaseType.WRITE_STARTED
                && current.filter(revision(facts)::equals).isPresent()) {
            journal.saved();
            facts = journal.facts();
        }
        if (facts.phase() == DeploymentSecretProvisionJournal.PhaseType.WRITE_STARTED
                || facts.phase() == DeploymentSecretProvisionJournal.PhaseType.SAVED && current.isEmpty())
            return unknown();
        if (facts.phase() != DeploymentSecretProvisionJournal.PhaseType.SAVED && current.isPresent())
            return unknown();
        Outcome outcome = facts.phase() == DeploymentSecretProvisionJournal.PhaseType.SAVED
                ? Outcome.SUCCEEDED
                : facts.failure().filter(failure -> failure.code() == ProblemCode.CANCELLED).isPresent()
                        ? Outcome.CANCELLED
                        : Outcome.FAILED;
        if (facts.phase() == DeploymentSecretProvisionJournal.PhaseType.KNOWN_FAILURE && current.isPresent())
            return unknown();
        if (facts.referenceId().isPresent()) {
            var secret = secret(facts);
            var found = context.secrets().findReference(secret, journal.intent().businessKey(), task);
            var resolution = current.isPresent() ? ReferenceResolution.REFERENCED : ReferenceResolution.NOT_REFERENCED;
            if (found.isPresent()) {
                if (!found.orElseThrow().id().equals(facts.referenceId().orElseThrow()))
                    return unknown();
                context.secrets().resolve(facts.referenceId().orElseThrow(), resolution, task);
            } else if (resolution == ReferenceResolution.REFERENCED) {
                return unknown();
            }
        } else if (facts.phase() == DeploymentSecretProvisionJournal.PhaseType.RESERVE_STARTED && current.isPresent()) {
            return unknown();
        }
        journal.close();
        if (!context.tasks().resourcesReleased(task))
            return unknown();
        if (outcome == Outcome.SUCCEEDED)
            return success();
        return new OperationResult<>(outcome, Optional.empty(),
                Optional.of(facts.failure()
                        .orElseGet(() -> new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SECRET_NOT_SAVED",
                                "Original secret revision was not saved", Map.of(), Optional.empty()))));
    }

    /** Constructs the original opaque secret only from durable facts. / 仅从持久依据构造原不透明秘密。
     * @param facts original durable facts / 原持久依据
     * @return exact secret reference / 精确秘密引用 */
    private static SecretRef secret(DeploymentSecretProvisionJournal.Facts facts) {
        return new SecretRef(facts.credentialId().orElseThrow(), facts.purpose());
    }

    /** Constructs the exact immutable metadata row. / 构造精确不可变元数据行。
     * @param facts original durable facts / 原持久依据
     * @return exact row / 精确行 */
    private static DeploymentSecretRevision revision(DeploymentSecretProvisionJournal.Facts facts) {
        return new DeploymentSecretRevision(facts.revision(), facts.credentialId().orElseThrow(), facts.createdAt(),
                Optional.of(facts.purpose()));
    }

    /** Returns a successful result without a retained Java value. / 返回不留存 Java 值的成功结果。
     * @return successful recovery outcome / 成功恢复结果 */
    private static OperationResult<Void> success() {
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Returns a definite failure before any creation. / 返回创建前的确定失败。
     * @param event stable event code / 稳定事件码
     * @return known failed result / 已知失败结果 */
    private static OperationResult<DeploymentSecretRevision> failed(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.INVALID_INPUT, event, "Secret revision already exists", Map.of(),
                        Optional.empty())));
    }

    /** Returns uncertainty without claiming cleanup. / 不声称收尾地返回未知。
     * @return retained uncertainty / 保留未知 */
    private static OperationResult<Void> unknown() {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_SECRET_UNKNOWN",
                        "Original secret creation requires reconciliation", Map.of(), Optional.empty())));
    }

    /** Returns unknown direct task result. / 返回未知直接任务结果。
     * @return retained uncertainty / 保留未知 */
    private static OperationResult<DeploymentSecretRevision> unknownValue() {
        var unknown = unknown();
        return new OperationResult<>(unknown.outcome(), Optional.empty(), unknown.problem());
    }

    /** Builds a bounded public input problem. / 构造有界公开输入问题。
     * @param code category / 类别
     * @param event stable event / 稳定事件
     * @return API exception / API 异常 */
    private static ApiException problem(ProblemCode code, String event) {
        return new ApiException(
                new Problem(code, event, "Secret provision input is invalid", Map.of(), Optional.empty()));
    }

}
