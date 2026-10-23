package gold.debug.windowstolinux.mod.deployment.secret;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.*;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.recover.TaskRecovery;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.db.resource.DeploymentSecretStore;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRevision;

/** Creates one credential and immutable revision under an original task, then only reconciles durable facts. / 在原任务下创建一次凭据及不可变修订，此后仅核对持久依据。 */
public final class DeploymentSecretProvisionFlow implements TaskRecovery {
    /** Original recoverable task type. / 原可恢复任务类型。 */
    public static final String TASK_TYPE = "deployment-secret-revision";

    /** Bound platform capabilities. / 绑定的平台能力。 */
    private final ModContext context;

    /** Exact immutable business rows. / 精确不可变业务行。 */
    private final DeploymentSecretStore revisions;

    /** Binds one actual Mod activation. / 绑定一次实际 Mod 启用。
     * @param context original Mod context / 原 Mod 上下文 */
    public DeploymentSecretProvisionFlow(ModContext context) {
        this.context = Objects.requireNonNull(context);
        this.revisions = new DeploymentSecretStore(context.db());
    }

    /** Submits one non-replayed creation; plaintext is obtained only after task admission. / 提交一次不重放的创建；明文仅在任务准入后取得。
     * @param server original server scope / 原服务器作用域
     * @param revision reviewed immutable business identity / 已审阅不可变业务身份
     * @param purpose exact platform secret purpose / 精确平台秘密用途
     * @param createdAt reviewed millisecond creation time / 已审阅毫秒创建时间
     * @param value one-shot callback owning its returned array / 拥有所返回数组的一次性回调
     * @return original task handle / 原任务句柄
     * @throws ApiException if admission or pure input validation fails / 准入或纯输入校验失败时 */
    public TaskHandle<DeploymentSecretRevision> submit(ServerId server, DeploymentSecretRef revision,
                                                       String purpose, Instant createdAt, SecretValueSource value) throws ApiException {
        Objects.requireNonNull(value);
        try {
            DeploymentSecretProvisionActionLog.validateInput(server, revision, purpose, createdAt);
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
    public TaskHandle<DeploymentSecretRevision> submitOwned(ServerId server, DeploymentSecretRef revision,
                                                            String purpose, Instant createdAt, SecretValueSource value) throws ApiException {
        Objects.requireNonNull(value);
        try {
            DeploymentSecretProvisionActionLog.validateInput(server, revision, purpose, createdAt);
        } catch (RuntimeException invalid) {
            throw problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SECRET_INPUT");
        }
        return context.tasks().submit(new TaskRequest(TASK_TYPE, "Secret revision", List.of(server.id()), true),
            task -> execute(task, server, revision, purpose, createdAt, value, true));
    }

    /** Creates one indexed database credential inside the already admitted publication task. / 在已准入发布原任务内创建一项已索引数据库凭据。
     * @param task already admitted original publication task / 已准入原发布任务
     * @param server original admitted server / 原已准入服务器
     * @param key child key from the durable whole-database plan / 持久全数据库计划中的子项键
     * @param revision reviewed immutable business revision / 已审阅不可变业务修订
     * @param purpose exact credential purpose / 精确凭据用途
     * @param createdAt reviewed millisecond time / 已审阅毫秒时间
     * @param value one-shot source transferring its returned bytes / 转移返回字节所有权的一次性来源
     * @return known revision or retained uncertainty / 已知修订或保留未知 */
    public OperationResult<DeploymentSecretRevision> createWithinTask(TaskContext task, ServerId server, String key,
                                                                      DeploymentSecretRef revision, String purpose, Instant createdAt, SecretValueSource value) {
        Objects.requireNonNull(value);
        try {
            DeploymentSecretProvisionActionLog.validateInput(server, revision, purpose, createdAt);
        } catch (RuntimeException invalid) {
            return failed("DEPLOYMENT_SECRET_INPUT");
        }
        return execute(task, server, revision, purpose, createdAt, value, true, key, false);
    }

    /** Stores one borrowed database password while its original preparation retains and later clears that buffer. / 原数据库准备保留并随后清零借用缓冲时保存一次密码。
     * @param task already admitted original task / 已准入原任务
     * @param server admitted server / 已准入服务器
     * @param key planned child checkpoint key / 已计划子项检查点键
     * @param revision exact immutable revision / 精确不可变修订
     * @param purpose exact purpose / 精确用途
     * @param createdAt reviewed time / 已审阅时间
     * @param borrowed caller-owned bytes that remain needed for original DB preparation / 原数据库准备仍需使用的调用方字节
     * @return known revision or retained uncertainty / 已知修订或保留未知 */
    public OperationResult<DeploymentSecretRevision> saveBorrowedWithinTask(TaskContext task, ServerId server,
                                                                            String key, DeploymentSecretRef revision, String purpose, Instant createdAt, byte[] borrowed) {
        Objects.requireNonNull(borrowed);
        try {
            DeploymentSecretProvisionActionLog.validateInput(server, revision, purpose, createdAt);
        } catch (RuntimeException invalid) {
            return failed("DEPLOYMENT_SECRET_INPUT");
        }
        return execute(task, server, revision, purpose, createdAt, ignored -> borrowed, false, key, false);
    }

    /** Reconciles only one indexed child, leaving whole-task resource release to the publication owner. / 仅核对一项已索引子项，整任务资源释放留给发布所有者。
     * @param task authentic original recovery task / 真实原恢复任务
     * @param key previously indexed child key / 先前已索引子项键
     * @param server exact server in the original plan / 原计划中的精确服务器
     * @return known child outcome or retained unknown / 已知子项结果或保留未知 */
    public OperationResult<Void> reconcileChild(TaskContext task, String key, ServerId server) {
        return reconcileChild(task, key, server, "deployment-database");
    }

    /** Reconciles an indexed child using its exact original purpose. / 按精确原用途核对索引子项。
     * @param task original task / 原任务
     * @param key original child key / 原子项键
     * @param server original server / 原服务器
     * @param purpose indexed purpose / 索引用途
     * @return known result or uncertainty / 已知结果或未知 */
    public OperationResult<Void> reconcileChild(TaskContext task, String key, ServerId server, String purpose) {
        try {
            var original = DeploymentSecretProvisionActionLog.find(context.tasks(), task, key);
            if (original.isEmpty() || !server.equals(original.orElseThrow().facts().server())
                || !purpose.equals(original.orElseThrow().facts().purpose()))
                return unknown();
            return settle(task, original.orElseThrow(), false);
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            return unknown();
        }
    }

    /** Reads only a confirmed immutable database revision for an original indexed child. / 仅读取原索引子项已确认不可变数据库修订。
     * @param task original task / 原任务
     * @param key original indexed child key / 原索引子项键
     * @param server exact planned server / 精确计划服务器
     * @return saved business revision or genuine absence / 已保存业务修订或真实不存在
     * @throws ApiException if original evidence cannot be read / 原依据无法读取时 */
    public Optional<DeploymentSecretRef> savedChild(TaskContext task, String key, ServerId server)
        throws ApiException {
        var original = DeploymentSecretProvisionActionLog.find(context.tasks(), task, key);
        if (original.isEmpty())
            return Optional.empty();
        var facts = original.orElseThrow().facts();
        if (!server.equals(facts.server()) || !"deployment-database".equals(facts.purpose()))
            throw new IllegalArgumentException("Database secret child differs from original plan");
        return facts.phase() == DeploymentSecretProvisionActionLog.PhaseType.SAVED
            ? Optional.of(facts.revision())
            : Optional.empty();
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
            var original = DeploymentSecretProvisionActionLog.find(context.tasks(), task);
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
    private OperationResult<DeploymentSecretRevision> execute(TaskContext task, ServerId server,
                                                              DeploymentSecretRef revision, String purpose, Instant createdAt, SecretValueSource value,
                                                              boolean ownsOriginal) {
        return execute(task, server, revision, purpose, createdAt, value, ownsOriginal,
            DeploymentSecretProvisionActionLog.KEY, true);
    }

    /** Runs one original indexed or standalone creation with the same save and recovery algorithm. / 用同一保存与恢复算法执行已索引或独立原创建。
     * @param task original task / 原任务
     * @param server admitted server / 已准入服务器
     * @param revision immutable revision / 不可变修订
     * @param purpose exact purpose / 精确用途
     * @param createdAt reviewed time / 已审阅时间
     * @param value one-shot secret source / 一次性秘密来源
     * @param ownsOriginal whether to clear the returned bytes / 是否清零返回字节
     * @param key original checkpoint key / 原检查点键
     * @param requireAll whether this is the standalone whole-task result / 是否独立整任务结果
     * @return observed result / 已观察结果 */
    private OperationResult<DeploymentSecretRevision> execute(TaskContext task, ServerId server,
                                                              DeploymentSecretRef revision, String purpose, Instant createdAt, SecretValueSource value,
                                                              boolean ownsOriginal, String key, boolean requireAll) {
        try {
            if (revisions.findRevision(task, revision).isPresent())
                return failed("DEPLOYMENT_SECRET_REVISION_EXISTS");
        } catch (ApiException rejected) {
            return new OperationResult<>(
                rejected.problem().code() == ProblemCode.CANCELLED ? TaskResult.CANCELLED : TaskResult.FAILED,
                Optional.empty(), Optional.of(rejected.problem()));
        } catch (SQLException unreadable) {
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SECRET_PREFLIGHT_FAILED",
                    "Existing secret revision could not be checked", Map.of(), Optional.empty())));
        }
        DeploymentSecretProvisionActionLog journal = null;
        try {
            journal = DeploymentSecretProvisionActionLog.begin(context.tasks(), task, server, revision, purpose,
                createdAt, key);
            byte[] original;
            try {
                original = Objects.requireNonNull(value.obtain(task));
            } catch (Exception | LinkageError | AssertionError invalid) {
                journal.knownFailure(
                    task.cancelToken().requested() || invalid instanceof java.util.concurrent.CancellationException
                        ? new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_SECRET_CANCELLED",
                        "Secret creation was cancelled before it began", Map.of(), Optional.empty())
                        : new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SECRET_INPUT_FAILED",
                        "Secret input could not be obtained", Map.of(), Optional.empty()));
                return result(settle(task, journal, requireAll), journal);
            }
            try {
                if (task.cancelToken().requested()) {
                    journal.knownFailure(new Problem(ProblemCode.CANCELLED, "DEPLOYMENT_SECRET_CANCELLED",
                        "Secret creation was cancelled before it began", Map.of(), Optional.empty()));
                    return result(settle(task, journal, requireAll), journal);
                }
                if (original.length > 1024 * 1024) {
                    journal.knownFailure(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SECRET_INPUT_SIZE",
                        "Secret input exceeds the platform bound", Map.of(), Optional.empty()));
                    return result(settle(task, journal, requireAll), journal);
                }
                byte[] copy = original.clone();
                SecretId created;
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
                    return result(settle(task, journal, requireAll), journal);
                }
                journal.saved();
                return result(settle(task, journal, requireAll), journal);
            } finally {
                if (ownsOriginal)
                    Arrays.fill(original, (byte) 0);
            }
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            if (journal == null)
                return unknownValue();
            try {
                journal.refresh();
                return result(settle(task, journal, requireAll), journal);
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
                                                                    DeploymentSecretProvisionActionLog journal) {
        if (settled.outcome() == TaskResult.SUCCEEDED) {
            var facts = journal.facts();
            return new OperationResult<>(
                TaskResult.SUCCEEDED, Optional.of(new DeploymentSecretRevision(facts.revision(),
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
    private OperationResult<Void> settle(TaskContext task, DeploymentSecretProvisionActionLog journal)
        throws SQLException, ApiException {
        return settle(task, journal, true);
    }

    /** Settles one exact secret child without requiring unrelated resources to be released. / 核对精确秘密子项，不要求无关资源已释放。
     * @param task original task / 原任务
     * @param journal original child journal / 原子项日志
     * @param requireAll whether standalone whole-task cleanup is required / 是否要求独立整任务清理
     * @return exact known result or unknown / 精确已知结果或未知
     * @throws SQLException if immutable business facts fail / 不可变业务依据失败时
     * @throws ApiException if original platform facts fail / 原平台依据失败时 */
    private OperationResult<Void> settle(TaskContext task, DeploymentSecretProvisionActionLog journal, boolean requireAll)
        throws SQLException, ApiException {
        journal.refresh();
        var facts = journal.facts();
        if (facts.phase() == DeploymentSecretProvisionActionLog.PhaseType.CREATE_STARTED)
            return unknown();
        if (facts.phase() == DeploymentSecretProvisionActionLog.PhaseType.RESERVE_STARTED) {
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
        if (facts.phase() == DeploymentSecretProvisionActionLog.PhaseType.WRITE_STARTED
            && current.filter(revision(facts)::equals).isPresent()) {
            journal.saved();
            facts = journal.facts();
        }
        if (facts.phase() == DeploymentSecretProvisionActionLog.PhaseType.WRITE_STARTED
            || facts.phase() == DeploymentSecretProvisionActionLog.PhaseType.SAVED && current.isEmpty())
            return unknown();
        if (facts.phase() != DeploymentSecretProvisionActionLog.PhaseType.SAVED && current.isPresent())
            return unknown();
        TaskResult outcome = facts.phase() == DeploymentSecretProvisionActionLog.PhaseType.SAVED
            ? TaskResult.SUCCEEDED
            : facts.failure().filter(failure -> failure.code() == ProblemCode.CANCELLED).isPresent()
            ? TaskResult.CANCELLED
            : TaskResult.FAILED;
        if (facts.phase() == DeploymentSecretProvisionActionLog.PhaseType.KNOWN_FAILURE && current.isPresent())
            return unknown();
        if (facts.referenceId().isPresent()) {
            var secret = secret(facts);
            var found = context.secrets().findReference(secret, journal.intent().businessKey(), task);
            var resolution = current.isPresent() ? ResourceRefResult.REFERENCED : ResourceRefResult.NOT_REFERENCED;
            if (found.isPresent()) {
                if (!found.orElseThrow().id().equals(facts.referenceId().orElseThrow()))
                    return unknown();
                context.secrets().resolve(facts.referenceId().orElseThrow(), resolution, task);
            } else if (resolution == ResourceRefResult.REFERENCED) {
                return unknown();
            }
        } else if (facts.phase() == DeploymentSecretProvisionActionLog.PhaseType.RESERVE_STARTED && current.isPresent()) {
            return unknown();
        }
        journal.close();
        if (requireAll && !context.tasks().resourcesReleased(task))
            return unknown();
        if (outcome == TaskResult.SUCCEEDED)
            return success();
        return new OperationResult<>(outcome, Optional.empty(),
            Optional.of(facts.failure()
                .orElseGet(() -> new Problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_SECRET_NOT_SAVED",
                    "Original secret revision was not saved", Map.of(), Optional.empty()))));
    }

    /** Constructs the original opaque secret only from durable facts. / 仅从持久依据构造原不透明秘密。
     * @param facts original durable facts / 原持久依据
     * @return exact secret reference / 精确秘密引用 */
    private static SecretId secret(DeploymentSecretProvisionActionLog.Facts facts) {
        return new SecretId(facts.credentialId().orElseThrow(), facts.purpose());
    }

    /** Constructs the exact immutable metadata row. / 构造精确不可变元数据行。
     * @param facts original durable facts / 原持久依据
     * @return exact row / 精确行 */
    private static DeploymentSecretRevision revision(DeploymentSecretProvisionActionLog.Facts facts) {
        return new DeploymentSecretRevision(facts.revision(), facts.credentialId().orElseThrow(), facts.createdAt(),
            Optional.of(facts.purpose()));
    }

    /** Returns a successful result without a retained Java value. / 返回不留存 Java 值的成功结果。
     * @return successful recovery outcome / 成功恢复结果 */
    private static OperationResult<Void> success() {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
    }

    /** Returns a definite failure before any creation. / 返回创建前的确定失败。
     * @param event stable event code / 稳定事件码
     * @return known failed result / 已知失败结果 */
    private static OperationResult<DeploymentSecretRevision> failed(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.INVALID_INPUT, event, "Secret revision already exists", Map.of(),
                Optional.empty())));
    }

    /** Returns uncertainty without claiming cleanup. / 不声称收尾地返回未知。
     * @return retained uncertainty / 保留未知 */
    private static OperationResult<Void> unknown() {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
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
