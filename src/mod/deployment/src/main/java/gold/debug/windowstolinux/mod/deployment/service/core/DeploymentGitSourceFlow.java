package gold.debug.windowstolinux.mod.deployment.service.core;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentProjectSource;
import gold.debug.windowstolinux.mod.deployment.source.git.*;

/** Executes one reviewed Git project task and reconciles only its original durable evidence. / 执行一次已审阅 Git 项目任务，并仅核对其原持久依据。 */
public final class DeploymentGitSourceFlow implements TaskRecovery {
    /** Fixed production task type registered at Mod startup. / Mod 启动时登记的固定生产任务类型。 */
    public static final String TASK_TYPE = "deployment-git-source";

    /** Original bound capabilities and business generation. / 原绑定能力及业务代次。 */
    private final ModContext context;

    /** Exact project row persistence and observation. / 精确项目行的持久化与观察。 */
    private final DeploymentProjectRepository projects;

    /** Binds one active Mod context without creating Git work. / 绑定一个活动 Mod 上下文而不创建 Git 工作。
     * @param context original active context / 原活动上下文 */
    public DeploymentGitSourceFlow(ModContext context) {
        this.context = Objects.requireNonNull(context);
        this.projects = new DeploymentProjectRepository(context.db());
    }

    /** Submits one real original task; no user-source grant or Git executable is needed for recovery. / 提交一个真实原任务；恢复不需要用户源码授权或 Git 可执行文件。
     * @param request reviewed credential-free Git request / 已审阅不含凭据 Git 请求
     * @param executable explicit local native Git executable / 明确本机原生 Git 可执行文件
     * @param project target project UUID / 目标项目 UUID
     * @param displayName reviewed target name / 已审阅目标名称
     * @param expected complete reviewed old row or absence / 完整已审阅旧行或不存在
     * @param observedAt reviewed millisecond observation / 已审阅毫秒观测
     * @param maximumSourceBytes bounded selected source bytes / 有界所选源码字节
     * @param maximumEntries bounded source entries / 有界来源条目数
     * @return original platform task / 原平台任务
     * @throws ApiException if original admission fails / 原准入失败时 */
    public TaskHandle<DeploymentProjectRecord> submit(DeploymentGitRequest request, Path executable, UUID project,
            String displayName, Optional<DeploymentProjectRecord> expected, Instant observedAt, long maximumSourceBytes,
            int maximumEntries) throws ApiException {
        Objects.requireNonNull(request);
        Objects.requireNonNull(executable);
        Objects.requireNonNull(project);
        Objects.requireNonNull(expected);
        Objects.requireNonNull(observedAt);
        try {
            if (!executable.isAbsolute() || !java.nio.file.Files.isRegularFile(executable))
                throw new IllegalArgumentException("Native Git executable is unavailable");
            DeploymentGitSourceJournal.validateInput(project, displayName, expected, observedAt, request,
                    maximumSourceBytes, maximumEntries);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_GIT_INPUT"));
        }
        return context.tasks().submit(new TaskRequest(TASK_TYPE, "Git project source", List.of(), true),
                task -> execute(request, executable, project, displayName, expected, observedAt, maximumSourceBytes,
                        maximumEntries, task));
    }

    /** Reconciles the original task without sending another Git request or opening a writable source. / 不重发 Git 请求或打开可写来源，仅核对原任务。
     * @param previous retained original task / 留存原任务
     * @param task authentic original recovery context / 真实原恢复上下文
     * @return known result or explicit uncertainty / 已知结果或明确未知 */
    @Override
    public OperationResult<Void> reconcile(TaskSnapshot previous, TaskContext task) {
        if (!TASK_TYPE.equals(previous.type()) || !previous.id().equals(task.id()))
            return unknown();
        try {
            var journal = DeploymentGitSourceJournal.find(context.tasks(), task);
            if (journal.isEmpty())
                return unknown();
            var settled = settle(task, journal.orElseThrow(), null);
            return new OperationResult<>(settled.outcome(), Optional.empty(), settled.problem());
        } catch (Exception | LinkageError | AssertionError uncertain) {
            return unknown();
        }
    }

    /** Executes the one admitted Git request, then records an exact complete project row. / 执行唯一已准入 Git 请求，再记录精确完整项目行。
     * @param request original request / 原请求
     * @param executable original executable / 原可执行文件
     * @param project target project / 目标项目
     * @param name reviewed name / 已审阅名称
     * @param expected reviewed previous row / 已审阅旧行
     * @param observedAt reviewed observation / 已审阅观测
     * @param maximumBytes source byte budget / 来源字节预算
     * @param maximumEntries source entry budget / 来源条目预算
     * @param task original context / 原上下文
     * @return complete observed result / 完整观测结果 */
    private OperationResult<DeploymentProjectRecord> execute(DeploymentGitRequest request, Path executable,
            UUID project, String name, Optional<DeploymentProjectRecord> expected, Instant observedAt,
            long maximumBytes, int maximumEntries, TaskContext task) {
        DeploymentGitSourceJournal journal = null;
        DeploymentGitPreparer preparer = null;
        try {
            journal = DeploymentGitSourceJournal.begin(context.tasks(), task, project, name, expected, observedAt,
                    request, maximumBytes, maximumEntries);
            preparer = new DeploymentGitPreparer(context, executable);
            try (var material = preparer.prepare(request, maximumBytes, maximumEntries, task, journal)) {
                var source = new DeploymentProjectSource(DeploymentProjectSource.KindType.GIT,
                        material.remote().location(), Optional.of(reference(request.reference())),
                        Optional.of(material.commit()), material.source().snapshot().sha256());
                var next = new DeploymentProjectRecord(project, name, source,
                        expected.map(DeploymentProjectRecord::createdAt).orElse(observedAt), observedAt);
                journal.propose(next);
                journal.writeStarted();
                try {
                    if (expected.isPresent())
                        projects.update(task, expected.orElseThrow(), next);
                    else
                        projects.create(task, next);
                } catch (SQLException knownRollback) {
                    journal.knownFailure(problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_PROJECT_SAVE_FAILED"));
                    throw knownRollback;
                }
                journal.saved();
            }
            var settled = settle(task, journal, preparer);
            if (settled.outcome() == Outcome.SUCCEEDED)
                return new OperationResult<>(Outcome.SUCCEEDED, journal.facts().next(), Optional.empty());
            return new OperationResult<>(settled.outcome(), Optional.empty(), settled.problem());
        } catch (Exception | LinkageError | AssertionError failure) {
            if (journal == null)
                return unknown();
            try {
                journal.refresh();
                if (journal.facts().phase() == DeploymentGitSourceJournal.Phase.RECEIPT
                        && journal.facts().receipt().orElseThrow().outcome() != Outcome.SUCCEEDED) {
                    var receipt = journal.facts().receipt().orElseThrow();
                    journal.knownFailure(
                            problem(receipt.problemCode().orElseThrow(), receipt.eventCode().orElseThrow()));
                }
                var settled = settle(task, journal, preparer);
                return new OperationResult<>(settled.outcome(), Optional.empty(), settled.problem());
            } catch (Exception | LinkageError | AssertionError stillUncertain) {
                return unknown();
            }
        }
    }

    /** Reconciles physical evidence, then exact project state, then original resource release. / 先核对物理依据，再核对精确项目状态，最后释放原资源。
     * @param task original context / 原上下文
     * @param journal original checkpoint / 原检查点
     * @param preparer current-boot original attempts, if any / 当前启动会话原尝试，可空
     * @return known or unknown result / 已知或未知结果
     * @throws ApiException if platform evidence or cleanup fails / 平台依据或清理失败时
     * @throws SQLException if project observation fails / 项目观察失败时 */
    private OperationResult<Void> settle(TaskContext task, DeploymentGitSourceJournal journal,
            DeploymentGitPreparer preparer) throws ApiException, SQLException {
        if (preparer != null)
            preparer.closeTaskResources(task);
        var facts = journal.refresh();
        context.processes().closeTaskResources(task);
        if (facts.phase() == DeploymentGitSourceJournal.Phase.REQUESTED) {
            var bytes = context.source()
                    .readWorkspaceEvidence(task, facts.plan().control(), "git-result.json", 1_048_576).orElse(null);
            if (bytes == null)
                return unknown();
            try {
                journal.receipt(DeploymentGitPreparer.readReceipt(bytes, task.id(), facts.plan().process(),
                        facts.plan().job()));
            } catch (java.io.IOException invalid) {
                return unknown();
            }
            facts = journal.facts();
        }
        if (facts.phase() == DeploymentGitSourceJournal.Phase.REQUESTED)
            return unknown();
        var result = business(task, journal);
        if (result.outcome() == Outcome.UNKNOWN)
            return result;
        context.source().closeTaskResources(task);
        journal.close();
        if (!context.tasks().resourcesReleased(task))
            return unknown();
        return result;
    }

    /** Classifies only known business facts; never infers a save from Git fetch success. / 仅按已知业务依据分类，绝不由 Git fetch 成功推断保存成功。
     * @param task original task / 原任务
     * @param journal original facts / 原依据
     * @return exact business result / 精确业务结果
     * @throws SQLException if project row cannot be read / 项目行无法读取时
     * @throws ApiException if original database cannot be read / 原数据库无法读取时 */
    private OperationResult<Void> business(TaskContext task, DeploymentGitSourceJournal journal)
            throws SQLException, ApiException {
        var facts = journal.facts();
        if (facts.phase() == DeploymentGitSourceJournal.Phase.KNOWN_FAILURE)
            return failed(facts.failure().orElseThrow());
        if (facts.phase() == DeploymentGitSourceJournal.Phase.PLANNED
                || facts.phase() == DeploymentGitSourceJournal.Phase.PROPOSED)
            return failed(problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_GIT_NOT_SAVED"));
        if (facts.phase() == DeploymentGitSourceJournal.Phase.RECEIPT) {
            var receipt = facts.receipt().orElseThrow();
            if (receipt.outcome() == Outcome.SUCCEEDED)
                return failed(problem(ProblemCode.IO_FAILURE, "DEPLOYMENT_GIT_NOT_SAVED"));
            return failed(problem(receipt.problemCode().orElseThrow(), receipt.eventCode().orElseThrow()));
        }
        if (facts.phase() == DeploymentGitSourceJournal.Phase.SAVED)
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
        var current = projects.find(task, facts.project());
        if (facts.phase() == DeploymentGitSourceJournal.Phase.WRITE_STARTED && current.equals(facts.next())) {
            journal.saved();
            return new OperationResult<>(Outcome.SUCCEEDED, Optional.empty(), Optional.empty());
        }
        return unknown();
    }

    /** Encodes the exact reviewed reference kind. / 编码精确已审阅引用类别。
     * @param ref original reference / 原引用
     * @return distinct persisted kind and value / 互不冲突的持久类别和值 */
    private static String reference(DeploymentGitReference ref) {
        return switch (ref) {
            case DeploymentGitReference.DefaultBranch ignored -> "DEFAULT:HEAD";
            case DeploymentGitReference.Branch branch -> "BRANCH:" + branch.value();
            case DeploymentGitReference.Tag tag -> "TAG:" + tag.value();
            case DeploymentGitReference.Commit commit -> "COMMIT:" + commit.value();
        };
    }

    /** Creates a safe bounded problem. / 创建安全有界问题。
     * @param code stable category / 稳定类别
     * @param event stable event / 稳定事件
     * @return safe problem / 安全问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Original Git project operation was not completed", Map.of(), Optional.empty());
    }

    /** Preserves cancellation as a distinct known outcome. / 保留取消作为不同的已知结果。
     * @param problem original bounded problem / 原有界问题
     * @return known failure or cancellation / 已知失败或取消 */
    private static OperationResult<Void> failed(Problem problem) {
        return new OperationResult<>(problem.code() == ProblemCode.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED,
                Optional.empty(), Optional.of(problem));
    }

    /** Retains uncertain original effects and resources. / 保留不确定的原效果及资源。
     * @param <T> result type / 结果类型
     * @return explicit uncertainty / 明确未知 */
    private static <T> OperationResult<T> unknown() {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GIT_RECOVERY_UNKNOWN")));
    }
}
