package gold.debug.windowstolinux.mod.git.service;

import java.nio.file.*;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.material.MaterialRef;
import gold.debug.windowstolinux.appapi.file.material.MaterialState;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.git.api.*;
import gold.debug.windowstolinux.mod.git.file.GitMessages;
import gold.debug.windowstolinux.mod.git.file.GitPreparation;

/** Publishes only original controlled Git work and reconciles its retained evidence. / 只发布原受控 Git 工作并核对其留存依据。 */
public final class GitSourceProvider implements GitSourceService, AutoCloseable {
    /** Original provider activation. / 原提供方启用。 */
    private final ModContext context;

    /** Actual preparations retained until physical cleanup. / 保留至物理清理的实际准备。 */
    private final Map<UUID, GitPreparation> pending = new HashMap<>();

    /** Admission state for this activation. / 此启用准入状态。 */
    private volatile boolean closed;

    /** Binds only original public platform capabilities. / 仅绑定原公开平台能力。
     * @param context original host-bound activation / 原宿主绑定启用 */
    public GitSourceProvider(ModContext context) {
        this.context = Objects.requireNonNull(context);
    }

    /** Executes exactly one new checkpointed source request. / 精确执行单个新检查点来源请求。
     * @param request original reviewed intent / 原已审阅意图
     * @param task authentic original task / 真实原任务
     * @return original fixed source reference / 原固定来源引用
     * @throws ApiException if work, evidence or cleanup is unconfirmed / 工作、依据或清理未确认时 */
    @Override
    public GitSourceResult prepare(GitSourceRequest request, TaskContext task) throws ApiException {
        if (closed) throw problem(ProblemCode.UNAVAILABLE, "GIT_SOURCE_CLOSED");
        GitSourceRequest original;
        Path executable;
        try {
            var transport = GitMessages.request(Objects.requireNonNull(request));
            executable = Path.of(request.gitExecutable());
            if (!executable.isAbsolute() || !Files.isRegularFile(executable)) throw new IllegalArgumentException();
            original = GitMessages.source(request.operationId(), request.materialId(), request.gitExecutable(), transport);
        } catch (RuntimeException invalid) {
            throw problem(ProblemCode.INVALID_INPUT, "GIT_SOURCE_INPUT");
        }
        var existing = GitSourceJournal.find(context.tasks(), task, original.operationId());
        if (existing.isPresent()) {
            if (!existing.orElseThrow().facts().request().equals(original))
                throw problem(ProblemCode.CONTRACT_VIOLATION, "GIT_SOURCE_ORIGINAL_INTENT_CHANGED");
            return requireSuccess(reconcile(original.operationId(), original.materialId(), task));
        }
        var journal = GitSourceJournal.begin(context.tasks(), task, original);
        var preparation = new GitPreparation(context, executable);
        synchronized (this) {
            pending.put(original.operationId(), preparation);
        }
        try {
            var material = preparation.prepare(GitMessages.request(original), original.materialId(), task, journal);
            journal.published(material);
            preparation.closeTaskResources(task);
            context.processes().closeTaskResources(task);
            context.file().closeTaskResources(task);
            journal.close();
            synchronized (this) {
                pending.remove(original.operationId());
            }
            return new GitSourceResult(original.operationId(), material.revision(), material);
        } catch (ApiException | RuntimeException failure) {
            var settled = reconcile(original.operationId(), original.materialId(), task);
            if (settled.outcome() == TaskResult.SUCCEEDED) return settled.value().orElseThrow();
            if (settled.outcome() == TaskResult.UNKNOWN)
                throw problem(ProblemCode.UNKNOWN, "GIT_SOURCE_RESULT_UNCONFIRMED");
            throw new ApiException(settled.problem().orElseThrow());
        }
    }

    /** Reads and releases only original facts; never creates replacement work. / 仅读取及释放原依据，不创建替代工作。
     * @param operationId original operation / 原操作
     * @param materialId original material / 原素材
     * @param task authentic original task or recovery / 真实原任务或恢复
     * @return original outcome or explicit uncertainty / 原结果或明确未知
     * @throws ApiException if original caller authority is unavailable / 原调用方授权不可用时 */
    @Override
    public OperationResult<GitSourceResult> reconcile(UUID operationId, UUID materialId, TaskContext task)
        throws ApiException {
        var original = GitSourceJournal.find(context.tasks(), task, Objects.requireNonNull(operationId));
        if (original.isEmpty()) return unknown("GIT_SOURCE_ORIGINAL_CHECKPOINT_MISSING");
        var journal = original.orElseThrow();
        if (!journal.facts().request().materialId().equals(materialId))
            throw problem(ProblemCode.ACCESS_DENIED, "GIT_SOURCE_MATERIAL_IDENTITY");
        try {
            GitPreparation preparation;
            synchronized (this) {
                preparation = pending.get(operationId);
            }
            if (preparation != null) {
                try {
                    preparation.closeTaskResources(task);
                } catch (ApiException uncertainCleanup) {
                    // Original custody can remain until the root explicitly releases an unpublished capture. / 原持有可保留至根明确释放未发布接管。
                }
            }
            context.processes().closeTaskResources(task);
            journal.refresh();
            var facts = journal.facts();
            if (facts.phase() == GitSourceJournal.PhaseState.PLANNED) {
                finish(task, journal, operationId);
                return failure(TaskResult.FAILED, ProblemCode.IO_FAILURE, "GIT_SOURCE_NOT_REQUESTED");
            }
            if (facts.phase() == GitSourceJournal.PhaseState.REQUESTED) {
                var bytes = context.file().workspaces()
                    .readWorkspaceEvidence(task, facts.plan().control(), "git-result.json", 1_048_576);
                if (bytes.isEmpty()) return unknown("GIT_SOURCE_NATIVE_EXIT_UNCONFIRMED");
                journal.receipt(GitPreparation.readReceipt(bytes.orElseThrow(), task.id(), facts.plan().process(), operationId));
                facts = journal.facts();
            }
            var receipt = facts.receipt().orElseThrow();
            if (receipt.outcome() != TaskResult.SUCCEEDED) {
                finish(task, journal, operationId);
                return failure(receipt.outcome(), receipt.problemCode().orElseThrow(), receipt.eventCode().orElseThrow());
            }
            var publication = context.file().materials().inspect(materialId, operationId, task);
            if (publication.state() == MaterialState.ABSENT && facts.phase() == GitSourceJournal.PhaseState.RECEIPT) {
                finish(task, journal, operationId);
                return failure(TaskResult.FAILED, ProblemCode.IO_FAILURE, "GIT_SOURCE_NOT_PUBLISHED");
            }
            if (publication.state() == MaterialState.RELEASED && publication.reference().isEmpty()) {
                finish(task, journal, operationId);
                return task.cancelToken().requested()
                    ? failure(TaskResult.CANCELLED, ProblemCode.CANCELLED, "GIT_SOURCE_UNPUBLISHED_CANCELLED")
                    : failure(TaskResult.FAILED, ProblemCode.IO_FAILURE, "GIT_SOURCE_UNPUBLISHED_RELEASED");
            }
            if (publication.reference().isEmpty()) return unknown("GIT_SOURCE_PUBLICATION_UNCONFIRMED");
            MaterialRef reference = publication.reference().orElseThrow();
            verifyReference(reference, facts);
            journal.published(reference);
            finish(task, journal, operationId);
            return new OperationResult<>(TaskResult.SUCCEEDED,
                Optional.of(new GitSourceResult(operationId, reference.revision(), reference)), Optional.empty());
        } catch (Exception | LinkageError | AssertionError unconfirmed) {
            String cause = unconfirmed instanceof ApiException api
                ? api.problem().eventCode() : unconfirmed.getClass().getSimpleName();
            return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(
                ProblemCode.UNKNOWN, "GIT_SOURCE_RECOVERY_UNCONFIRMED", "Original Git source evidence remains unconfirmed",
                Map.of("cause", cause), Optional.empty())));
        }
    }

    /** Verifies the exact original producer, commit and publication proof. / 核验精确原生产方、提交及发布证明。
     * @param reference retained platform reference / 留存平台引用
     * @param facts retained original facts / 留存原依据
     * @throws ApiException if any original identity differs / 任一原身份不同 */
    private void verifyReference(MaterialRef reference, GitSourceJournal.Facts facts) throws ApiException {
        if (!reference.taskId().equals(facts.task()) || !reference.id().equals(facts.request().materialId())
            || !reference.operationId().equals(facts.request().operationId())
            || !reference.producerId().equals(context.identity().id())
            || !reference.producerVersion().equals(context.identity().version()) || reference.producerSchema().isPresent()
            || !reference.revision().equals(facts.receipt().orElseThrow().commit().orElse("")))
            throw problem(ProblemCode.CONTRACT_VIOLATION, "GIT_SOURCE_PUBLICATION_IDENTITY");
        if (facts.manifest().isPresent()) {
            var original = facts.manifest().orElseThrow();
            if (!original.digest().equals(reference.manifestSha256()) || original.bytes() != reference.byteCount()
                || original.files() != reference.fileCount())
                throw problem(ProblemCode.CONTRACT_VIOLATION, "GIT_SOURCE_PUBLICATION_CHANGED");
        }
    }

    /** Cleans provider work and checkpoint while preserving root-owned material. / 清理提供方工作及检查点，同时保留根持有素材。
     * @param task original task / 原任务
     * @param journal original checkpoint / 原检查点
     * @param operation original operation / 原操作
     * @throws ApiException if actual cleanup remains uncertain / 实际清理仍不确定时 */
    private void finish(TaskContext task, GitSourceJournal journal, UUID operation) throws ApiException {
        GitPreparation preparation;
        synchronized (this) {
            preparation = pending.get(operation);
        }
        if (preparation != null) preparation.closeTaskResources(task);
        context.processes().closeTaskResources(task);
        context.file().closeTaskResources(task);
        journal.close();
        synchronized (this) {
            pending.remove(operation);
        }
    }

    /** Requires a proved result without replaying a retained operation. / 要求已证明结果，不重放留存操作。
     * @param result original observation / 原观察
     * @return original success value / 原成功值
     * @throws ApiException if the original result is failed or unknown / 原结果失败或未知时 */
    private static GitSourceResult requireSuccess(OperationResult<GitSourceResult> result) throws ApiException {
        if (result.outcome() == TaskResult.SUCCEEDED) return result.value().orElseThrow();
        throw new ApiException(result.problem().orElseThrow());
    }

    /** Retains unknown effects and original resources. / 保留未知效果及原资源。
     * @param event stable diagnostic / 稳定诊断
     * @return explicit uncertainty / 明确未知 */
    private static OperationResult<GitSourceResult> unknown(String event) {
        return failure(TaskResult.UNKNOWN, ProblemCode.UNKNOWN, event);
    }

    /** Produces the original known failure or explicit uncertainty. / 生成原已知失败或明确未知。
     * @param outcome original outcome / 原结果
     * @param code original category / 原类别
     * @param event original diagnostic / 原诊断
     * @return bounded result / 有界结果 */
    private static OperationResult<GitSourceResult> failure(TaskResult outcome, ProblemCode code, String event) {
        return new OperationResult<>(outcome, Optional.empty(), Optional.of(problem(code, event).problem()));
    }

    /** Builds a safe diagnostic without credentials or native output. / 构造不含凭据或原生输出的安全诊断。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return bounded public error / 有界公开错误 */
    private static ApiException problem(ProblemCode code, String event) {
        return new ApiException(new Problem(code, event, "Original Git source operation could not be completed",
            Map.of(), Optional.empty()));
    }

    /** Seals new work only after original preparations have returned and released. / 仅在原准备返回及释放后封闭新工作。
     * @throws ApiException if an actual preparation remains / 仍有实际准备时 */
    @Override
    public synchronized void close() throws ApiException {
        closed = true;
        if (!pending.isEmpty()) throw problem(ProblemCode.BUSY, "GIT_SOURCE_PREPARATIONS_OPEN");
    }
}
