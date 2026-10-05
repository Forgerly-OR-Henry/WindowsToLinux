package gold.debug.windowstolinux.mod.deployment.file.git;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.appapi.file.material.FileMaterialHold;
import gold.debug.windowstolinux.appapi.file.material.MaterialRef;
import gold.debug.windowstolinux.appapi.file.material.MaterialState;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.service.AvailabilityState;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.file.*;

/** Consumes optional Git material through platform grants and keeps deployment filtering locally. / 经平台授权消费可选 Git 素材，并在部署侧保留筛选。 */
public final class DeploymentGitSetup {
    /** Exact provider identity without resolving optional public types. / 不解析可选公开类型的精确提供方身份。 */
    private static final String PROVIDER = "gold.debug.windowstolinux.mod.git";

    /** Original consumer platform capabilities. / 原消费方平台能力。 */
    private final ModContext context;

    /** Explicit original native tool choice. / 明确原原生工具选择。 */
    private final Path executable;

    /** Original material holds whose release needs retry. / 需要重试释放的原素材持有。 */
    private final Set<Consumption> pending = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Source identities recorded before the first provider call. / 首次提供方调用前记录的来源身份。
     * @param job original source operation / 原来源操作
     * @param material original platform material / 原平台素材 */
    public record Planned(UUID job, UUID material) {
        /** Requires two distinct original identities. / 要求两个不同原身份。
         * @param job original operation / 原操作
         * @param material original material / 原素材 */
        public Planned {
            if (job == null || material == null || job.equals(material))
                throw new IllegalArgumentException("Source operation identities differ");
        }
    }

    /** Original provider outcome; deployment project writes remain separate. / 原提供方结果；部署项目写入保持独立。
     * @param outcome exact source outcome / 精确来源结果
     * @param commit original immutable commit / 原不可变提交
     * @param problemCode original known failure category / 原已知失败类别
     * @param eventCode original known diagnostic / 原已知诊断 */
    public record Receipt(TaskResult outcome, Optional<String> commit, Optional<ProblemCode> problemCode,
                          Optional<String> eventCode) {
        /** Rejects incomplete original outcome evidence. / 拒绝不完整原结果依据。
         * @param outcome original outcome / 原结果
         * @param commit original commit / 原提交
         * @param problemCode known category / 已知类别
         * @param eventCode known diagnostic / 已知诊断 */
        public Receipt {
            Objects.requireNonNull(outcome);
            Objects.requireNonNull(commit);
            Objects.requireNonNull(problemCode);
            Objects.requireNonNull(eventCode);
            if (outcome == TaskResult.UNKNOWN || outcome == TaskResult.SUCCEEDED
                ? outcome == TaskResult.UNKNOWN || commit.isEmpty() || problemCode.isPresent() || eventCode.isPresent()
                : commit.isPresent() || problemCode.isEmpty() || eventCode.isEmpty()
                || problemCode.orElseThrow() == ProblemCode.UNKNOWN
                || !eventCode.orElseThrow().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}"))
                throw new IllegalArgumentException("Source result is incomplete");
            if (commit.isPresent() && !commit.orElseThrow().matches("[0-9a-f]{40}"))
                throw new IllegalArgumentException("Source commit is invalid");
        }
    }

    /** Original deployment checkpoint effect boundaries. / 原部署检查点效果边界。 */
    public interface Observer {
        /** Returns already recorded source identities. / 返回已记录来源身份。
         * @return original plan / 原计划 */
        Planned planned();

        /** Records possible service invocation before calling. / 调用前记录可能的服务调用。
         * @throws ApiException if original durability fails / 原持久化失败时 */
        void beforeRequest() throws ApiException;

        /** Records only a proved original provider result. / 仅记录已证明原提供方结果。
         * @param receipt exact original result / 精确原结果
         * @throws ApiException if original durability fails / 原持久化失败时 */
        void receipt(Receipt receipt) throws ApiException;
    }

    /** Binds source consumption without linking optional Git types. / 绑定来源消费，不链接可选 Git 类型。
     * @param context original deployment activation / 原部署启用
     * @param executable explicit native tool / 明确原生工具 */
    public DeploymentGitSetup(ModContext context, Path executable) {
        this.context = Objects.requireNonNull(context);
        this.executable = Objects.requireNonNull(executable);
        if (!executable.isAbsolute()) throw new IllegalArgumentException("Native Git path must be absolute");
    }

    /** Uses the real provider, then preserves original deployment selection and archive budgets. / 使用真实提供方，随后保留原部署筛选及归档预算。
     * @param request original reviewed source choice / 原已审阅来源选择
     * @param maximumSourceBytes filtered byte budget / 筛选后字节预算
     * @param maximumEntries original discovery budget / 原发现预算
     * @param task authentic original task / 真实原任务
     * @param observer original deployment checkpoint / 原部署检查点
     * @return owned deployment source and archive / 所属部署来源及归档
     * @throws ApiException if original work, result or release is unconfirmed / 原工作、结果或释放未确认时 */
    public DeploymentGitMaterial prepare(DeploymentGitRequest request, long maximumSourceBytes, int maximumEntries,
                                         TaskContext task, Observer observer) throws ApiException {
        if (!java.nio.file.Files.isRegularFile(executable)
            || maximumSourceBytes < 0 || maximumSourceBytes > 2L * 1024 * 1024 * 1024
            || maximumEntries < 1 || maximumEntries > 100000)
            throw problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_GIT_INPUT");
        var availability = context.services().availability(PROVIDER);
        if (availability.state() != AvailabilityState.AVAILABLE)
            throw new ApiException(new Problem(ProblemCode.UNAVAILABLE, "DEPLOYMENT_GIT_" + availability.state().name(),
                "Git source provider is unavailable: " + availability.state().name(), Map.of(), Optional.empty()));
        observer.beforeRequest();
        var access = new DeploymentGitAccess(context);
        MaterialRef reference;
        try {
            reference = access.prepare(request, executable, observer.planned(), task);
        } catch (ApiException failure) {
            var original = access.reconcile(observer.planned(), task);
            if (original.outcome() == TaskResult.UNKNOWN)
                throw problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GIT_RESULT_UNCONFIRMED");
            if (original.outcome() != TaskResult.SUCCEEDED) {
                var reason = original.problem().orElseThrow();
                observer.receipt(new Receipt(original.outcome(), Optional.empty(), Optional.of(reason.code()), Optional.of(reason.eventCode())));
                throw new ApiException(reason);
            }
            reference = original.value().orElseThrow();
        }
        observer.receipt(new Receipt(TaskResult.SUCCEEDED, Optional.of(reference.revision()), Optional.empty(), Optional.empty()));
        var consumption = new Consumption(task, reference);
        synchronized (this) {
            pending.add(consumption);
        }
        DeploymentSourceMaterial source = null;
        DeploymentSourceArchive archive = null;
        boolean returned = false;
        try {
            consumption.hold = context.file().materials().acquire(reference, task);
            source = new DeploymentSourceSetup(context.file()).prepare(consumption.hold.selection(), maximumSourceBytes,
                maximumEntries, task);
            archive = new DeploymentSourceArchiver(context.file()).prepare(source, request.maximumArchiveBytes(), task);
            consumption.close();
            var result = new DeploymentGitMaterial(request.remote(), reference.revision(), source, archive);
            returned = true;
            return result;
        } finally {
            try {
                consumption.close();
            } finally {
                if (!returned) {
                    try {
                        if (archive != null) archive.close();
                    } finally {
                        if (source != null) source.close();
                    }
                }
            }
        }
    }

    /** Retries only known original in-memory material cleanup. / 仅重试已知原内存素材清理。
     * @param task authentic original task / 真实原任务
     * @throws ApiException if an original hold or release remains unconfirmed / 原持有或释放仍未确认时 */
    public void closeTaskResources(TaskContext task) throws ApiException {
        List<Consumption> values;
        synchronized (this) {
            values = List.copyOf(pending);
        }
        for (var value : values) if (value.task == task) value.close();
    }

    /** Reconciles the exact provider without constructing new requests or workspaces. / 不构造新请求或工作目录，核对精确提供方。
     * @param context original consumer activation / 原消费方启用
     * @param plan original recorded source identities / 原记录来源身份
     * @param task authentic original execution or recovery / 真实原执行或恢复
     * @return proved original provider outcome / 已证明原提供方结果
     * @throws ApiException if original provider or authority is unavailable / 原提供方或授权不可用时 */
    public static OperationResult<MaterialRef> reconcileOriginal(ModContext context, Planned plan, TaskContext task)
        throws ApiException {
        return new DeploymentGitAccess(context).reconcile(plan, task);
    }

    /** Cleans retained original holds only after the caller has settled its business facts. / 仅在调用方确定业务依据后清理留存原持有。
     * @param context original consumer activation / 原消费方启用
     * @param plan original recorded identities / 原记录身份
     * @param task authentic original recovery task / 真实原恢复任务
     * @param recovery whether this is the original explicit recovery callback / 是否原显式恢复回调
     * @throws ApiException if retained original material or release is unconfirmed / 留存原素材或释放未确认时 */
    public static void releaseOriginal(ModContext context, Planned plan, TaskContext task, boolean recovery) throws ApiException {
        var original = context.file().materials().find(plan.material(), plan.job(), task);
        var retained = context.file().materials().inspect(plan.material(), plan.job(), task);
        if (!retained.reference().equals(Optional.of(original)))
            throw problem(ProblemCode.UNKNOWN, "DEPLOYMENT_GIT_MATERIAL_IDENTITY");
        if (retained.state() == MaterialState.RELEASED) return;
        if (recovery) for (var hold : context.file().materials().recoverHolds(original, task)) hold.close();
        context.file().materials().release(original, task);
    }

    /** Constructs a safe bounded failure. / 构造安全有界失败。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return public failure / 公开失败 */
    private static ApiException problem(ProblemCode code, String event) {
        return new ApiException(new Problem(code, event, "Original Git source operation could not be completed", Map.of(), Optional.empty()));
    }

    /** Root-owned material lifetime during original filtering. / 原筛选期间根持有素材寿命。 */
    private final class Consumption {
        /** Authentic original root task. / 真实原根任务。 */
        private final TaskContext task;

        /** Exact original source reference. / 精确原来源引用。 */
        private final MaterialRef reference;

        /** Original platform read hold. / 原平台读取持有。 */
        private FileMaterialHold hold;

        /** Whether physical and durable custody release completed. / 物理及持久持有释放是否完成。 */
        private boolean released;

        /** Records original identities before acquiring read access. / 取得读访问前记录原身份。
         * @param task original task / 原任务
         * @param reference original reference / 原引用 */
        private Consumption(TaskContext task, MaterialRef reference) {
            this.task = task;
            this.reference = reference;
        }

        /** Releases only original read and root custody, preserving uncertainty. / 仅释放原读取和根持有，保留不确定性。
         * @throws ApiException if either original release fails / 任一原释放失败时 */
        private synchronized void close() throws ApiException {
            if (released) return;
            if (hold != null) hold.close();
            context.file().materials().release(reference, task);
            released = true;
            synchronized (DeploymentGitSetup.this) {
                pending.remove(this);
            }
        }
    }
}
