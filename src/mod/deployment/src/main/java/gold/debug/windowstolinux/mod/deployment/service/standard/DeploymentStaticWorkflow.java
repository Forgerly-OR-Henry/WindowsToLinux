package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentConfigurationRepository;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentSecretRepository;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentGitSourceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentReferenceFlow;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationInput;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.analysis.DeploymentAdmissionStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.model.assessment.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.ecosystem.db.DatabaseSchemaReview;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.component.ComponentIsolationSpecification;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.DeploymentStandardAnalyzer;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.ComponentAnalysisRequest;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.DiscoveredProjectComponent;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.MixedProjectInspector;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.component.ProjectComponentDiscovery;
import gold.debug.windowstolinux.mod.deployment.service.standard.analyze.ecosystem.db.DatabaseProjectInspector;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentDatabaseSourceInputs;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentNativeDatabaseReview;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentPreparedDatabase;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.database.DeploymentSqlitePreparation;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.input.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication.*;
import gold.debug.windowstolinux.mod.deployment.source.*;
import gold.debug.windowstolinux.mod.deployment.source.git.DeploymentGitRequest;

/** Coordinates one original local or Git static review and publication task without minting a second source authorization. / 协调一项本地或 Git 静态审阅与发布原任务，不另造来源授权。 */
public final class DeploymentStaticWorkflow {
    /** Original task-bound capabilities. / 原任务绑定能力。 */
    private final ModContext context;

    /** Existing formal publication transaction. / 既有正式发布事务。 */
    private final DeploymentReferenceFlow references;

    /** Deterministic source assessment. / 确定性来源评估。 */
    private final DeploymentStandardAnalyzer analyzer = new DeploymentStandardAnalyzer();

    /** Existing reviewed request converter. / 既有已审阅请求转换器。 */
    private final DeploymentStaticReviewAssembler assembler;

    /** Existing typed runtime questions. / 既有类型化运行问题。 */
    private final DeploymentStaticRuntimeResolver runtime = new DeploymentStaticRuntimeResolver();

    /** Exact managed ownership resolver. / 精确受管归属解析器。 */
    private final DeploymentStaticUnitResolver units;

    /** Existing original single publication. / 既有原单组件发布。 */
    private final DeploymentStandardPublicationExecutor single;

    /** Existing original whole-graph publication. / 既有原整图发布。 */
    private final DeploymentMultiComponentPublicationExecutor multi;

    /** Initial limits; component types are selected only after frozen-source discovery. / 初始限额；组件类型仅在冻结来源发现后选择。
     * @param logicalRootName reviewed source name / 审阅的来源名称
     * @param sourceBytes frozen source budget / 冻结来源字节预算
     * @param sourceEntries discovery budget / 发现条目预算
     * @param archiveBytes compressed archive budget / 压缩归档预算 */
    public record Draft(String logicalRootName, long sourceBytes, int sourceEntries, long archiveBytes) {
        /** Rejects invalid source limits before admission. / 准入前拒绝无效来源限额。
         * @param logicalRootName original logical name / 原逻辑名称
         * @param sourceBytes source bytes / 来源字节
         * @param sourceEntries source entries / 来源条目
         * @param archiveBytes archive bytes / 归档字节 */
        public Draft {
            logicalRootName = Objects.requireNonNull(logicalRootName).trim();
            if (sourceBytes < 1 || sourceBytes > 2L * 1024 * 1024 * 1024 || sourceEntries < 1 || sourceEntries > 100_000
                    || archiveBytes < 1 || archiveBytes > 2L * 1024 * 1024 * 1024)
                throw new IllegalArgumentException("Source budgets differ from the supported bounds");
        }
    }

    /** One reviewed source route; Git stays in the original publication task. / 一项经审阅来源路径；Git 保留在原发布任务。
     * @param request credential-free Git selection / 不含凭据 Git 选择
     * @param executable explicit native Git path / 明确原生 Git 路径 */
    public record GitSource(DeploymentGitRequest request, Path executable) {
        /** Requires exact input before task admission. / 任务准入前要求精确输入。
         * @param request original request / 原请求
         * @param executable original executable / 原可执行文件 */
        public GitSource {
            Objects.requireNonNull(request);
            Objects.requireNonNull(executable);
        }
    }

    /** Actual pinned identity shown at final consent. / 最终同意时展示的真实固定身份。
     * @param remote original URL / 原 URL
     * @param reference selected ref kind and value / 选定引用类型和值
     * @param commit resolved immutable commit / 已解析不可变提交 */
    public record GitReview(String remote, String reference, String commit) {
    }

    /** Reviewed choice for one discovered root. / 一个已发现根的审阅选择。
     * @param type selected deployable type / 选定可部署类型
     * @param dependencies predecessor component IDs / 前驱组件标识 */
    public record Choice(DeploymentProjectKind type, Set<String> dependencies) {
        /** Freezes a component choice. / 冻结组件选择。
         * @param type selected type / 选定类型
         * @param dependencies predecessors / 前驱 */
        public Choice {
            Objects.requireNonNull(type);
            dependencies = Set.copyOf(dependencies);
        }
    }

    /** Reviewed graph identity and discovery choices. / 经审阅整图身份及发现选择。
     * @param applicationId managed application ID / 受管应用 ID
     * @param choices one per discovered component / 每个发现组件一项
     * @param healthComponent graph health owner, absent for a single component / 整图健康归属，单项为空 */
    public record Selection(String applicationId, Map<String, Choice> choices, Optional<String> healthComponent) {
        /** Freezes the current selection. / 冻结当前选择。
         * @param applicationId application / 应用
         * @param choices component choices / 组件选择
         * @param healthComponent graph health owner / 整图健康归属 */
        public Selection {
            Objects.requireNonNull(applicationId);
            choices = Map.copyOf(choices);
            Objects.requireNonNull(healthComponent);
        }
    }

    /** One dynamic field set associated with the actual frozen source and selected target. / 关联真实冻结来源及目标的一组动态字段。
     * @param componentId graph component or single unit ID / 整图组件或单单元标识
     * @param applicationId managed unit ID / 受管单元标识
     * @param projectType analyzed project kind / 已分析项目种类
     * @param defaults source-backed values / 来源依据值
     * @param fields missing and editable bounded questions / 缺项及可编辑有界问题 */
    public record Prompt(String componentId, String applicationId, DeploymentProjectKind projectType,
            Map<String, String> defaults, List<DeploymentReviewField> fields) {
        /** Freezes displayed review facts. / 冻结展示的审阅事实。
         * @param componentId component / 组件
         * @param applicationId unit / 单元
         * @param projectType kind / 类型
         * @param defaults suggestions / 建议
         * @param fields questions / 问题 */
        public Prompt {
            defaults = Map.copyOf(defaults);
            fields = List.copyOf(fields);
        }
    }

    /** Non-secret answers returned only after an explicit current-view confirmation. / 仅在当前视图明确确认后返回的不含秘密答案。
     * @param values typed runtime, config and DB notation / 类型化运行、配置和数据库记法
     * @param files actual managed file bindings / 实际受管文件绑定
     * @param limits reviewed build limits / 经审阅构建限额
     * @param containerRisk explicit container decision / 容器风险明确决定
     * @param adapterRisk explicit adapter decision / 适配器风险明确决定 */
    public record Answers(Map<String, String> values, List<DeploymentFileBinding> files, DeploymentBuildLimits limits,
            boolean containerRisk, boolean adapterRisk) {
        /** Copies answers without creating consent. / 复制答案但不生成确认。
         * @param values non-secret text / 非秘密文本
         * @param files file bindings / 文件绑定
         * @param limits limits / 限额
         * @param containerRisk risk / 风险
         * @param adapterRisk risk / 风险 */
        public Answers {
            values = Map.copyOf(values);
            files = List.copyOf(files);
            Objects.requireNonNull(limits);
        }
    }

    /** Owner-view review boundary; rejection must leave all source cleanup to this task. / 所属视图审阅边界；拒绝时来源清理由本任务完成。 */
    public interface Reviewer extends DeploymentNativeDatabaseReview {
        /** Completes source-derived non-secret database fields without authorizing an effect. / 补齐源码派生的非秘密数据库字段，不授权副作用。
         * @param server exact target / 精确目标
         * @param componentId reviewed component / 经审阅组件
         * @param sourceSha256 frozen manifest digest / 冻结清单摘要
         * @param root actual frozen component root / 实际冻结组件根
         * @param applicationId managed identity / 受管身份
         * @param assessment source assessment / 源码评估
         * @param task original task / 原任务
         * @return explicit answers or refusal / 明确答案或拒绝
         * @throws Exception if the owner view fails / 所属视图失败时 */
        default OperationResult<Map<String, String>> databaseInputs(ServerRef server, String componentId,
                String sourceSha256, Path root, String applicationId, DatabaseProjectInspector.Assessment assessment,
                TaskContext task) throws Exception {
            return rejected("DEPLOYMENT_DATABASE_REVIEW_UNAVAILABLE");
        }

        /** Separately confirms planned first SQLite initialization against exact source files. / 对精确源码文件单独确认 SQLite 首次初始化计划。
         * @param server exact target / 精确目标
         * @param componentId component / 组件
         * @param sourceSha256 frozen manifest digest / 冻结清单摘要
         * @param assessment completed declaration / 已补齐声明
         * @param task original task / 原任务
         * @return affirmative current-view decision / 当前视图明确决定
         * @throws Exception if the owner view fails / 所属视图失败时 */
        default OperationResult<Boolean> confirmSqliteInitialization(ServerRef server, String componentId,
                String sourceSha256, DatabaseProjectInspector.Assessment assessment, TaskContext task)
                throws Exception {
            return rejected("DEPLOYMENT_DATABASE_REVIEW_UNAVAILABLE");
        }

        /** Chooses actual discovered component kinds and graph identity. / 选择实际发现组件的类型和整图身份。
         * @param server exact server / 精确服务器
         * @param sourceSha256 manifest digest / 清单摘要
         * @param discovered original frozen-source roots / 原冻结来源根
         * @param task original task / 原任务
         * @return reviewed selection or refusal / 审阅选择或拒绝
         * @throws Exception if the owned view cannot observe its decision / 所属视图无法观察决定时 */
        OperationResult<Selection> select(ServerRef server, String sourceSha256,
                List<DiscoveredProjectComponent> discovered, TaskContext task) throws Exception;

        /** Collects editable typed runtime inputs before archive creation. / 归档前收集可编辑类型化运行输入。
         * @param server exact server / 精确服务器
         * @param sourceSha256 manifest digest / 清单摘要
         * @param prompts typed dynamic questions / 类型化动态问题
         * @param task original task / 原任务
         * @return complete answers or classified refusal / 完整答案或分类拒绝
         * @throws Exception if the owned view cannot observe its decision / 所属视图无法观察决定时 */
        OperationResult<Map<String, Answers>> review(ServerRef server, String sourceSha256, List<Prompt> prompts,
                TaskContext task) throws Exception;

        /** Confirms the final server, source, every actual archive and edited value. / 确认最终服务器、来源、各实际归档及编辑值。
         * @param server exact server / 精确服务器
         * @param sourceSha256 manifest digest / 清单摘要
         * @param archiveSha256 component-to-tar digest / 组件至 tar 摘要
         * @param answers reviewed values / 经审阅值
         * @param databases source-derived exact database preparation / 源码派生的精确数据库准备
         * @param git original selected and resolved Git identity / 原选定及已解析 Git 身份
         * @param task original task / 原任务
         * @return explicit current-view confirmation or refusal / 当前视图明确确认或拒绝
         * @throws Exception if view observation fails / 视图观察失败时 */
        OperationResult<Boolean> confirm(ServerRef server, String sourceSha256, Map<String, String> archiveSha256,
                Map<String, Answers> answers, Map<String, DeploymentPreparedDatabase> databases,
                Optional<GitReview> git, TaskContext task) throws Exception;
    }

    /** Binds the existing task, analyzer, planner and publisher without owning their resources. / 绑定既有任务、分析、规划与发布能力但不取得资源所有权。
     * @param context actual Mod activation / 实际Mod启用
     * @param references registered formal recovery / 已登记正式恢复 */
    public DeploymentStaticWorkflow(ModContext context, DeploymentReferenceFlow references) {
        this.context = Objects.requireNonNull(context);
        this.references = Objects.requireNonNull(references);
        this.assembler = new DeploymentStaticReviewAssembler(context.servers(), new DeploymentStandardPlanner());
        this.units = new DeploymentStaticUnitResolver(context.db());
        this.single = new DeploymentStandardPublicationExecutor(context, references);
        this.multi = new DeploymentMultiComponentPublicationExecutor(context, references);
    }

    /** Admits one publication task and performs all local review before formal effects. / 接纳一项发布任务，并在正式副作用前完成全部本机审阅。
     * @param server exact target / 精确目标
     * @param draft frozen choices / 冻结选择
     * @param reviewer current owner view / 当前所属视图
     * @return one original task / 一项原任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<Void> submit(ServerRef server, Draft draft, Reviewer reviewer) throws ApiException {
        return submit(server, draft, Optional.empty(), reviewer);
    }

    /** Admits either local selection or one explicit Git source under the same publication task. / 在同一发布任务中准入本地选择或一项明确 Git 来源。
     * @param server exact target / 精确目标
     * @param draft frozen limits and name / 冻结限额与名称
     * @param git optional original Git input / 可选原 Git 输入
     * @param reviewer current owner view / 当前所属视图
     * @return original publication task / 原发布任务
     * @throws ApiException if admission fails / 准入失败时 */
    public TaskHandle<Void> submit(ServerRef server, Draft draft, Optional<GitSource> git, Reviewer reviewer)
            throws ApiException {
        Objects.requireNonNull(server);
        Objects.requireNonNull(draft);
        Objects.requireNonNull(git);
        Objects.requireNonNull(reviewer);
        if (git.isPresent() && draft.logicalRootName().isBlank())
            throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_GIT_NAME_REQUIRED",
                    "Git source requires an explicit project name", Map.of(), Optional.empty()));
        return references.submitPrepared(server, task -> prepare(server, draft, git, reviewer, task));
    }

    /** Retains local evidence before original selection, then hands off only a complete reviewed publication. / 原选材前保留本机依据，且只交接完整审阅发布。
     * @param server original target / 原目标
     * @param draft immutable choices / 不可变选择
     * @param git optional reviewed Git source / 可选的已审阅 Git 来源
     * @param reviewer original review / 原审阅
     * @param task original task / 原任务
     * @return actual prepared publication / 实际已准备发布
     * @throws Exception if original cleanup or checkpoint cannot be confirmed / 原清理或检查点无法确认时 */
    private OperationResult<DeploymentReferenceFlow.PreparedPublication> prepare(ServerRef server, Draft draft,
            Optional<GitSource> git, Reviewer reviewer, TaskContext task) throws Exception {
        var marker = DeploymentStaticPreparationJournal.begin(context.tasks(), task, server);
        DeploymentSourceMaterial material = null;
        DeploymentGitSourceFlow.PublicationSource pinned = null;
        var archives = new ArrayList<DeploymentSourceArchive>();
        boolean transferred = false;
        boolean retainMarker = false;
        try {
            if (task.cancellation().requested()) {
                marker.cancelled();
                return cancelled();
            }
            String logicalName;
            if (git.isPresent()) {
                var source = git.orElseThrow();
                pinned = new DeploymentGitSourceFlow(context).prepareForPublication(source.request(),
                        source.executable(), draft.logicalRootName(), draft.sourceBytes(), draft.sourceEntries(), task);
                material = pinned.material().source();
                logicalName = draft.logicalRootName();
            } else {
                var selection = context.source().selectDirectory("Deployment source", task);
                if (task.cancellation().requested()) {
                    marker.cancelled();
                    return cancelled();
                }
                material = new DeploymentSourcePreparer(context.source()).prepare(selection, draft.sourceBytes(),
                        draft.sourceEntries(), task);
                logicalName = draft.logicalRootName().isBlank() ? selection.displayName() : draft.logicalRootName();
            }
            if (logicalName.isBlank())
                return rejected("DEPLOYMENT_STATIC_SOURCE_NAME_UNAVAILABLE");
            var discovered = new ProjectComponentDiscovery().discover(material.snapshot().directory());
            if (discovered.size() > 16)
                return rejected("DEPLOYMENT_STATIC_COMPONENT_LIMIT");
            var chosen = reviewer.select(server, material.snapshot().sha256(), discovered, task);
            if (chosen.outcome() == Outcome.CANCELLED)
                marker.cancelled();
            if (chosen.outcome() != Outcome.SUCCEEDED)
                return withoutValue(chosen);
            var choices = chosen.value().orElseThrow();
            if (!validSelection(discovered, choices))
                return rejected("DEPLOYMENT_STATIC_SELECTION_INVALID");
            OperationResult<DeploymentReferenceFlow.PreparedPublication> ready = discovered.size() == 1
                    ? prepareSingle(server, draft, logicalName, reviewer, task, material, discovered.getFirst(),
                            choices, archives, pinned)
                    : prepareGraph(server, draft, logicalName, reviewer, task, material, discovered, choices, archives,
                            pinned);
            if (ready.outcome() == Outcome.CANCELLED)
                marker.cancelled();
            if (ready.outcome() != Outcome.SUCCEEDED)
                return priorEffects(ready, task);
            if (pinned != null)
                pinned.close();
            marker.close();
            transferred = true;
            return ready;
        } catch (ApiException failure) {
            var code = failure.problem().code();
            retainMarker = code == ProblemCode.UNKNOWN && git.isPresent();
            var outcome = code == ProblemCode.CANCELLED
                    ? Outcome.CANCELLED
                    : code == ProblemCode.UNKNOWN ? Outcome.UNKNOWN : Outcome.FAILED;
            if (outcome == Outcome.CANCELLED)
                marker.cancelled();
            return priorEffects(new OperationResult<>(outcome, Optional.empty(), Optional.of(failure.problem())), task);
        } catch (CancellationException cancelled) {
            marker.cancelled();
            return priorEffects(cancelled(), task);
        } catch (SQLException readFailure) {
            return priorEffects(ioFailure("DEPLOYMENT_STATIC_METADATA_UNAVAILABLE"), task);
        } catch (IOException sourceFailure) {
            return priorEffects(ioFailure("DEPLOYMENT_STATIC_DISCOVERY_UNAVAILABLE"), task);
        } catch (IllegalArgumentException invalid) {
            return priorEffects(rejected("DEPLOYMENT_STATIC_REVIEW_INVALID"), task);
        } finally {
            if (!transferred) {
                retainMarker |= unresolvedRemotePreparation(task);
                closePreparation(archives, material, pinned, marker, retainMarker);
            }
        }
    }

    /** Releases owned preparation resources only after their actual prior cleanup is known. / 仅在先前清理确知后释放所属准备资源。
     * @param archives child archives still owned here / 此处仍持有的组件归档
     * @param material original frozen source still owned here / 此处仍持有的原冻结来源
     * @param pinned optional original Git material and checkpoint / 可选的原 Git 素材及检查点
     * @param marker original static preparation marker / 原静态准备标记
     * @param retainMarker unresolved Git or remote work still needs all original preparation resources / 未决 Git 或远端操作仍需全部原准备资源
     * @throws Exception if any original resource release is unconfirmed / 任一原资源释放未确认时 */
    private static void closePreparation(List<DeploymentSourceArchive> archives, DeploymentSourceMaterial material,
            DeploymentGitSourceFlow.PublicationSource pinned, DeploymentStaticPreparationJournal marker,
            boolean retainMarker) throws Exception {
        if (retainMarker)
            return;
        Exception cleanup = null;
        for (var archive : archives)
            try {
                archive.close();
            } catch (Exception failure) {
                if (cleanup == null)
                    cleanup = failure;
                else
                    cleanup.addSuppressed(failure);
            }
        try {
            if (material != null)
                material.close();
            if (pinned != null)
                pinned.close();
        } catch (Exception failure) {
            if (cleanup == null)
                cleanup = failure;
            else
                cleanup.addSuppressed(failure);
        }
        if (cleanup == null)
            try {
                marker.close();
            } catch (Exception failure) {
                cleanup = failure;
            }
        if (cleanup != null)
            throw cleanup;
    }

    /** Completes one discovered project on its original frozen subtree. / 在原冻结子树完成一个已发现项目。
     * @param server exact target / 精确目标
     * @param draft source limits / 来源限额
     * @param logicalName source-bound project name / 来源绑定项目名
     * @param reviewer owner view / 所属视图
     * @param task original task / 原任务
     * @param material live material / 开放素材
     * @param discovered discovered root / 已发现根
     * @param selection reviewed type / 经审阅类型
     * @param archives owned archives to release on failure / 失败时释放的所属归档
     * @param pinned optional original Git material / 可选原 Git 素材
     * @return prepared original publication / 已准备原发布
     * @throws Exception if original review or storage fails / 原审阅或存储失败时 */
    private OperationResult<DeploymentReferenceFlow.PreparedPublication> prepareSingle(ServerRef server, Draft draft,
            String logicalName, Reviewer reviewer, TaskContext task, DeploymentSourceMaterial material,
            DiscoveredProjectComponent discovered, Selection selection, List<DeploymentSourceArchive> archives,
            DeploymentGitSourceFlow.PublicationSource pinned) throws Exception {
        String id = discovered.id();
        var type = selection.choices().get(id).type();
        var initial = analyzer.analyzeComponent(material, discovered.relativeRoot(), logicalName, type,
                Optional.empty(), true, task);
        if (initial.outcome() != Outcome.SUCCEEDED)
            return withoutValue(initial);
        var assessed = initial.value().orElseThrow().assessment();
        if (assessed.admission() == DeploymentAdmissionStatus.REJECTED
                || assessed.admission() == DeploymentAdmissionStatus.RECOGNITION_PREVIEW)
            return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
        var facts = assessed.facts().orElseThrow();
        var database = prepareDatabase(server, id, facts.applicationId(), material, facts, reviewer, task);
        if (database.outcome() != Outcome.SUCCEEDED)
            return withoutValue(database);
        var databaseLocal = database.value().orElseThrow();
        var prompt = prompt(id, facts, assessed.runtimeSuggestion().orElse(null), databaseLocal.hasBindings());
        var reviewed = reviewer.review(server, material.snapshot().sha256(), List.of(prompt), task);
        if (reviewed.outcome() != Outcome.SUCCEEDED)
            return withoutValue(reviewed);
        var supplied = reviewed.value().orElseThrow();
        if (!supplied.keySet().equals(Set.of(id)))
            return rejected("DEPLOYMENT_STATIC_REVIEW_INCOMPLETE");
        var values = completed(prompt, supplied.get(id));
        validateReview(prompt, supplied.get(id));
        var stages = new DeploymentDatabaseStages(context);
        stages.validateRuntime(databaseLocal, supplied.get(id));
        var plan = stages.begin(server, material.snapshot().sha256(), List.of(databaseLocal), task);
        if (plan.isPresent()) {
            var helper = ensureHelper(server, task);
            if (helper.outcome() != Outcome.SUCCEEDED)
                return withoutValue(helper);
        }
        var prepared = stages.prepare(server, plan, databaseLocal, reviewer, task);
        if (prepared.outcome() != Outcome.SUCCEEDED)
            return withoutValue(prepared);
        var preparedDatabase = prepared.value().orElseThrow();
        if (plan.isPresent() && !stages.releaseKnown(task, plan.orElseThrow()))
            return unknownPreparation();
        var reanalyzed = analyzer.analyzeComponent(material, discovered.relativeRoot(), logicalName, type,
                preparedDatabase.schemaReview(), false, task);
        if (reanalyzed.outcome() != Outcome.SUCCEEDED)
            return withoutValue(reanalyzed);
        if (schemaReviewMissing(reanalyzed.value().orElseThrow().assessment()))
            return schemaReviewRequired();
        if (reanalyzed.value().orElseThrow().assessment().admission() != DeploymentAdmissionStatus.READY_FOR_PLANNING)
            return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
        var archive = new DeploymentSourceArchiver(context.source()).prepare(material, relative(discovered),
                draft.archiveBytes(), task);
        archives.add(archive);
        var confirmed = reviewer.confirm(server, material.snapshot().sha256(), Map.of(id, archive.sha256()), supplied,
                Map.of(id, preparedDatabase), gitReview(pinned), task);
        if (confirmed.outcome() != Outcome.SUCCEEDED)
            return withoutValue(confirmed);
        if (!confirmed.value().orElse(false))
            return cancelled();
        var answer = answers(task, server, facts.applicationId(), material, archive, supplied.get(id));
        var planned = assembler.plan(server, material, archive, reanalyzed.value().orElseThrow(), answer,
                preparedDatabase, task);
        if (planned.outcome() != Outcome.SUCCEEDED)
            return withoutValue(planned);
        var publication = planned.value().orElseThrow();
        var unit = units.resolve(task, facts.applicationId(), server);
        var credentials = credentials(task, publication.request().secretReferences());
        var helper = ensureHelper(server, task);
        if (helper.outcome() != Outcome.SUCCEEDED)
            return withoutValue(helper);
        Optional<DeploymentProjectRecord> project = pinned == null ? Optional.empty() : Optional.of(pinned.save());
        return single.prepareWithinTask(task, unit, project, credentials,
                original -> new OperationResult<>(Outcome.SUCCEEDED, Optional.of(publication), Optional.empty()));
    }

    /** Completes a discovered graph only after each reviewed runtime has been reanalyzed. / 每份经审阅运行时重分析后才完成发现整图。
     * @param server exact target / 精确目标
     * @param draft source limits / 来源限额
     * @param logicalName source-bound project name / 来源绑定项目名
     * @param reviewer owner view / 所属视图
     * @param task original task / 原任务
     * @param material live material / 开放素材
     * @param discovered original roots / 原发现根
     * @param selection reviewed graph / 经审阅整图
     * @param archives owned archives / 所属归档
     * @param pinned optional original Git material / 可选原 Git 素材
     * @return prepared original publication / 已准备原发布
     * @throws Exception if review or storage fails / 审阅或存储失败时 */
    private OperationResult<DeploymentReferenceFlow.PreparedPublication> prepareGraph(ServerRef server, Draft draft,
            String logicalName, Reviewer reviewer, TaskContext task, DeploymentSourceMaterial material,
            List<DiscoveredProjectComponent> discovered, Selection selection, List<DeploymentSourceArchive> archives,
            DeploymentGitSourceFlow.PublicationSource pinned) throws Exception {
        var prompts = new ArrayList<Prompt>();
        var facts = new HashMap<String, DeploymentProjectFacts>();
        var databaseLocal = new HashMap<String, DeploymentDatabaseStages.Local>();
        var collected = graphPrompts(server, material, discovered, selection, reviewer, task, facts, databaseLocal);
        if (collected.outcome() != Outcome.SUCCEEDED)
            return withoutValue(collected);
        prompts.addAll(collected.value().orElseThrow());
        var reviewed = reviewer.review(server, material.snapshot().sha256(), List.copyOf(prompts), task);
        if (reviewed.outcome() != Outcome.SUCCEEDED)
            return withoutValue(reviewed);
        var supplied = reviewed.value().orElseThrow();
        if (!supplied.keySet().equals(selection.choices().keySet()))
            return rejected("DEPLOYMENT_STATIC_COMPONENT_REVIEW_INCOMPLETE");
        for (var prompt : prompts)
            validateReview(prompt, supplied.get(prompt.componentId()));
        var stages = new DeploymentDatabaseStages(context);
        for (var prompt : prompts)
            stages.validateRuntime(databaseLocal.get(prompt.componentId()), supplied.get(prompt.componentId()));
        var requests = new ArrayList<ComponentAnalysisRequest>();
        for (int i = 0; i < discovered.size(); i++)
            requests.add(componentRequest(discovered.get(i), selection.choices().get(discovered.get(i).id()),
                    facts.get(discovered.get(i).id()), prompts.get(i), supplied.get(discovered.get(i).id())));
        var preparedDatabases = graphDatabases(server, material, discovered, databaseLocal, reviewer, task);
        if (preparedDatabases.outcome() != Outcome.SUCCEEDED)
            return withoutValue(preparedDatabases);
        var databases = preparedDatabases.value().orElseThrow();
        var reviews = new HashMap<String, DatabaseSchemaReview>();
        databases.forEach((id, prepared) -> prepared.schemaReview().ifPresent(review -> reviews.put(id, review)));
        var analyzed = analyzer.analyzeComponents(material, logicalName, selection.applicationId(), requests, reviews,
                false, task);
        if (analyzed.outcome() != Outcome.SUCCEEDED)
            return withoutValue(analyzed);
        var assessment = analyzed.value().orElseThrow();
        if (assessment.assessment().issues().stream().anyMatch(issue -> schemaReviewCode(issue.code())))
            return schemaReviewRequired();
        if (assessment.assessment().admission() != DeploymentAdmissionStatus.READY_FOR_PLANNING)
            return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
        var byComponent = new HashMap<String, DeploymentSourceArchive>();
        var digests = new HashMap<String, String>();
        for (var component : discovered) {
            var archive = new DeploymentSourceArchiver(context.source()).prepare(material, relative(component),
                    draft.archiveBytes(), task);
            archives.add(archive);
            byComponent.put(component.id(), archive);
            digests.put(component.id(), archive.sha256());
        }
        var confirmed = reviewer.confirm(server, material.snapshot().sha256(), Map.copyOf(digests), supplied, databases,
                gitReview(pinned), task);
        if (confirmed.outcome() != Outcome.SUCCEEDED)
            return withoutValue(confirmed);
        if (!confirmed.value().orElse(false))
            return cancelled();
        var answers = new HashMap<String, DeploymentStaticReviewAnswers>();
        for (var component : assessment.assessment().components())
            answers.put(component.componentId(), answers(task, server, component.facts().applicationId(), material,
                    byComponent.get(component.componentId()), supplied.get(component.componentId())));
        var planned = assembler.planComponents(server, material, byComponent, assessment, answers, databases, task);
        if (planned.outcome() != Outcome.SUCCEEDED)
            return withoutValue(planned);
        var plans = planned.value().orElseThrow();
        var owners = new HashMap<String, DeploymentUnitIdentity>();
        var secrets = new HashSet<DeploymentSecretReference>();
        for (var entry : plans.components().entrySet()) {
            owners.put(entry.getKey(), units.resolve(task, entry.getValue().request().facts().applicationId(), server));
            secrets.addAll(entry.getValue().request().secretReferences());
        }
        var credentials = credentials(task, List.copyOf(secrets));
        var healthId = selection.healthComponent().orElseThrow();
        var healthPrompt = prompts.stream().filter(value -> value.componentId().equals(healthId)).findFirst()
                .orElseThrow();
        var helper = ensureHelper(server, task);
        if (helper.outcome() != Outcome.SUCCEEDED)
            return withoutValue(helper);
        Optional<DeploymentProjectRecord> project = pinned == null ? Optional.empty() : Optional.of(pinned.save());
        return multi.prepareWithinTask(task, server, project, owners, credentials, healthId,
                runtime.health(completed(healthPrompt, supplied.get(healthId))),
                original -> new OperationResult<>(Outcome.SUCCEEDED, Optional.of(plans), Optional.empty()));
    }

    /** Prepares all locally verified databases under one durable original-task index. / 在一个持久原任务索引下准备全部本机已核数据库。
     * @param server exact target / 精确目标
     * @param material original frozen material / 原冻结素材
     * @param discovered original ordered components / 原有序组件
     * @param databaseLocal validated local plans / 已校验本机计划
     * @param reviewer owner view / 所属视图
     * @param task original task / 原任务
     * @return actual prepared facts or original refusal / 实际准备事实或原拒绝
     * @throws Exception if original preparation or journal fails / 原准备或日志失败时 */
    private OperationResult<Map<String, DeploymentPreparedDatabase>> graphDatabases(ServerRef server,
            DeploymentSourceMaterial material, List<DiscoveredProjectComponent> discovered,
            Map<String, DeploymentDatabaseStages.Local> databaseLocal, Reviewer reviewer, TaskContext task)
            throws Exception {
        var stages = new DeploymentDatabaseStages(context);
        var databasePlan = stages.begin(server, material.snapshot().sha256(), List.copyOf(databaseLocal.values()),
                task);
        if (databasePlan.isPresent()) {
            var helper = ensureHelper(server, task);
            if (helper.outcome() != Outcome.SUCCEEDED)
                return withoutValue(helper);
        }
        var databases = new HashMap<String, DeploymentPreparedDatabase>();
        for (var component : discovered) {
            var database = stages.prepare(server, databasePlan, databaseLocal.get(component.id()), reviewer, task);
            if (database.outcome() != Outcome.SUCCEEDED)
                return withoutValue(database);
            databases.put(component.id(), database.value().orElseThrow());
        }
        if (databasePlan.isPresent() && !stages.releaseKnown(task, databasePlan.orElseThrow()))
            return unknownPreparation();
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(Map.copyOf(databases)), Optional.empty());
    }

    /** Collects source facts and locally checked database prompts for the entire graph. / 为整图收集源码事实及本机已核数据库问题。
     * @param server exact target / 精确目标
     * @param material live frozen material / 开放冻结素材
     * @param discovered original component roots / 原组件根
     * @param selection reviewed graph kinds / 经审阅整图类型
     * @param reviewer owner view / 所属视图
     * @param task original publication task / 原发布任务
     * @param facts collected actual component facts / 收集的实际组件事实
     * @param databaseLocal collected source-backed database plans / 收集的源码绑定数据库计划
     * @return complete local prompts or original refusal / 完整本机问题或原拒绝
     * @throws Exception if source inspection or review fails / 来源检查或审阅失败时 */
    private OperationResult<List<Prompt>> graphPrompts(ServerRef server, DeploymentSourceMaterial material,
            List<DiscoveredProjectComponent> discovered, Selection selection, Reviewer reviewer, TaskContext task,
            Map<String, DeploymentProjectFacts> facts, Map<String, DeploymentDatabaseStages.Local> databaseLocal)
            throws Exception {
        var prompts = new ArrayList<Prompt>();
        for (var component : discovered) {
            var initial = analyzer.analyzeComponent(material, component.relativeRoot(), component.id(),
                    selection.choices().get(component.id()).type(), Optional.empty(), true, task);
            if (initial.outcome() != Outcome.SUCCEEDED)
                return withoutValue(initial);
            var assessed = initial.value().orElseThrow().assessment();
            if (assessed.admission() == DeploymentAdmissionStatus.REJECTED
                    || assessed.admission() == DeploymentAdmissionStatus.RECOGNITION_PREVIEW)
                return rejected("DEPLOYMENT_STATIC_ANALYSIS_INCOMPLETE");
            var observed = assessed.facts().orElseThrow();
            facts.put(component.id(), observed);
            var database = prepareDatabase(server, component.id(),
                    MixedProjectInspector.managedComponentId(selection.applicationId(), component.id()), material,
                    observed, reviewer, task);
            if (database.outcome() != Outcome.SUCCEEDED)
                return withoutValue(database);
            databaseLocal.put(component.id(), database.value().orElseThrow());
            prompts.add(prompt(component.id(), observed, assessed.runtimeSuggestion().orElse(null),
                    database.value().orElseThrow().hasBindings()));
        }
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(List.copyOf(prompts)), Optional.empty());
    }

    /** Completes database declarations before runtime analysis and requires separate SQLite initialization consent. / 运行重分析前补齐数据库声明，并要求单独的 SQLite 初始化同意。
     * @param server exact target / 精确目标
     * @param componentId actual discovered component / 实际发现组件
     * @param applicationId final managed component identity / 最终受管组件身份
     * @param material original frozen source / 原冻结来源
     * @param facts actual component facts / 实际组件事实
     * @param reviewer original owner view / 原所属视图
     * @param task original publication task / 原发布任务
     * @return bound SQLite preparation or classified refusal / 绑定 SQLite 准备或分类拒绝
     * @throws Exception if source inspection or review fails / 来源检查或审阅失败时 */
    private OperationResult<DeploymentDatabaseStages.Local> prepareDatabase(ServerRef server, String componentId,
            String applicationId, DeploymentSourceMaterial material, DeploymentProjectFacts facts, Reviewer reviewer,
            TaskContext task) throws Exception {
        return new DeploymentDatabaseStages(context).local(server, componentId, applicationId,
                material.snapshot().sha256(), facts.sourceRoot(), reviewer, task);
    }

    /** Ensures the fixed helper after final review but before ordinary publication effects. / 最终审阅后、普通发布副作用前确保固定 helper。
     * @param server exact reviewed target / 精确审阅目标
     * @param task original publication task / 原发布任务
     * @return known helper readiness or retained uncertainty / 已知 helper 就绪或留存未知
     * @throws ApiException if original access or persistence fails / 原访问或持久化失败时 */
    private OperationResult<Void> ensureHelper(ServerRef server, TaskContext task) throws ApiException {
        try (var session = context.linux().connect(server, task)) {
            var checked = new DeploymentHelperBootstrap(context.tasks(), task, server, session, context.interaction())
                    .ensure();
            return new OperationResult<>(checked.outcome(), Optional.empty(), checked.problem());
        }
    }

    /** Detects an unresolved original remote preparation before releasing its marker or source. / 释放标记或来源前识别未决原远端准备。
     * @param task original publication task / 原发布任务
     * @return whether an effect still needs original recovery / 是否仍需原恢复核对效果 */
    private boolean unresolvedRemotePreparation(TaskContext task) {
        try {
            var helper = DeploymentHelperBootstrapJournal.find(context.tasks(), task);
            var database = DeploymentNativeDatabaseJournal.find(context.tasks(), task);
            var indexed = DeploymentDatabasePlanJournal.find(context.tasks(), task);
            if (indexed.isPresent() && !new DeploymentDatabaseStages(context).releaseKnown(task, indexed.orElseThrow()))
                return true;
            return helper.filter(value -> {
                try {
                    return "DISPATCHED".equals(value.facts().phase());
                } catch (ApiException unreadable) {
                    return true;
                }
            }).isPresent() || database.filter(value -> {
                try {
                    return "DISPATCHED".equals(value.facts().phase());
                } catch (ApiException unreadable) {
                    return true;
                }
            }).isPresent();
        } catch (ApiException | IllegalArgumentException unreadable) {
            return true;
        }
    }

    /** Retains the original task when an indexed preparation still lacks a confirmed terminal fact. / 索引准备仍缺确认终态事实时保留原任务。
     * @param <T> discarded prepared value / 丢弃的准备值
     * @return classified unknown with retained original recovery / 保留原恢复的已分类未知 */
    private static <T> OperationResult<T> unknownPreparation() {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_DATABASE_PREPARATION_UNKNOWN",
                        "Original database preparation needs exact receipt review", Map.of(), Optional.empty())));
    }

    /** Keeps prior helper effects visible when later local review ends without publication. / 后续本机审阅未发布即结束时保留先前 helper 效果提示。
     * @param result actual preparation result / 实际准备结果
     * @param task original publication task / 原发布任务
     * @return result with non-secret prior-effect context / 带非秘密先前效果上下文的结果
     * @throws ApiException if original evidence cannot be read / 原依据无法读取时 */
    private OperationResult<DeploymentReferenceFlow.PreparedPublication> priorEffects(
            OperationResult<DeploymentReferenceFlow.PreparedPublication> result, TaskContext task) throws ApiException {
        var retained = DeploymentHelperBootstrapJournal.find(context.tasks(), task);
        var plan = DeploymentDatabasePlanJournal.find(context.tasks(), task);
        if (retained.isPresent() && "DISPATCHED".equals(retained.orElseThrow().facts().phase())) {
            var facts = retained.orElseThrow().facts();
            return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_HELPER_BOOTSTRAP_UNRESOLVED",
                            "Original helper installation needs exact receipt review",
                            Map.of("serverId", facts.serverId(), "operationId", facts.operationId().toString()),
                            Optional.empty())));
        }
        if (plan.isPresent() && !new DeploymentDatabaseStages(context).releaseKnown(task, plan.orElseThrow()))
            return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                    Optional.of(new Problem(ProblemCode.UNKNOWN, "DEPLOYMENT_DATABASE_PREPARATION_UNKNOWN",
                            "Original database effects need exact receipt review",
                            Map.of("serverId", plan.orElseThrow().facts().serverId()), Optional.empty())));
        if (retained.isEmpty() && plan.isEmpty())
            return result;
        var original = result.problem()
                .orElseGet(() -> new Problem(
                        result.outcome() == Outcome.CANCELLED ? ProblemCode.CANCELLED : ProblemCode.IO_FAILURE,
                        "DEPLOYMENT_STATIC_REVIEW_ENDED", "Static review ended after preparation", Map.of(),
                        Optional.empty()));
        var details = new HashMap<>(original.context());
        if (retained.isPresent() && !"INTENT".equals(retained.orElseThrow().facts().phase())) {
            var facts = retained.orElseThrow().facts();
            details.put("helperServerId", facts.serverId());
            details.put("helperOperationId", facts.operationId().toString());
            details.put("helperPhase", facts.phase());
        }
        if (plan.isPresent())
            details.putAll(new DeploymentDatabaseStages(context).effectDetails(task, plan.orElseThrow()));
        if (details.equals(original.context()))
            return result;
        return new OperationResult<>(result.outcome(), Optional.empty(),
                Optional.of(new Problem(original.code(), original.eventCode(),
                        original.message() + "; review earlier helper, database or secret effects", details,
                        original.diagnosticId())));
    }

    /** Projects exact selected and resolved Git identity into final consent. / 将精确选定并解析的 Git 身份投影到最终同意。
     * @param pinned original pinned source or null / 原固定来源或空值
     * @return final consent identity / 最终同意身份 */
    private static Optional<GitReview> gitReview(DeploymentGitSourceFlow.PublicationSource pinned) {
        if (pinned == null)
            return Optional.empty();
        var material = pinned.material();
        return Optional
                .of(new GitReview(material.remote().location().toString(), pinned.reference(), material.commit()));
    }

    /** Checks exact frozen discovery choices before reading any component. / 读取组件前核对精确冻结发现选择。
     * @param discovered original source discovery / 原来源发现
     * @param selection reviewed selection / 经审阅选择
     * @return true only for complete bounded choices / 仅完整有界选择为真 */
    public static boolean validSelection(List<DiscoveredProjectComponent> discovered, Selection selection) {
        if (!selection.applicationId().matches("[a-z0-9][a-z0-9-]{0,62}")
                || !selection.choices().keySet()
                        .equals(discovered.stream().map(DiscoveredProjectComponent::id)
                                .collect(java.util.stream.Collectors.toSet()))
                || discovered.size() == 1 != selection.healthComponent().isEmpty()
                || selection.healthComponent().isPresent()
                        && !selection.choices().containsKey(selection.healthComponent().orElseThrow()))
            return false;
        for (var component : discovered) {
            var choice = selection.choices().get(component.id());
            if (choice == null || !component.types().contains(choice.type()) || !choice.type().deployable()
                    || choice.dependencies().contains(component.id())
                    || !selection.choices().keySet().containsAll(choice.dependencies()))
                return false;
        }
        return true;
    }

    /** Returns the canonical relative root used by both analysis and archive. / 返回分析与归档共用的规范相对根。
     * @param discovered original discovered root / 原发现根
     * @return slash-separated relative root / 斜线相对根 */
    private static String relative(DiscoveredProjectComponent discovered) {
        return discovered.relativeRoot().toString().replace('\\', '/');
    }

    /** Applies edited answers to source defaults and rejects remaining missing inputs. / 将编辑答案应用于源码默认值并拒绝残留缺项。
     * @param prompt original source-backed prompt / 原源码依据问题
     * @param answer actual reviewer answer / 真实审阅答案
     * @return complete immutable runtime values / 完整不可变运行值 */
    private Map<String, String> completed(Prompt prompt, Answers answer) {
        var values = new LinkedHashMap<>(prompt.defaults());
        values.putAll(answer.values());
        if (!runtime.missing(prompt.componentId(), prompt.projectType(), values).isEmpty())
            throw new IllegalArgumentException("Required runtime fields remain unreviewed");
        return Map.copyOf(values);
    }

    /** Checks only local typed values so an owner can correct the same form before publication. / 仅校验本地类型化值，使所属者能在发布前修改同一表单。
     * @param prompt source-backed questions / 来源依据问题
     * @param answer edited non-secret values / 已编辑非秘密值
     * @throws IllegalArgumentException if the form is incomplete or malformed / 表单缺项或格式错误时 */
    public void validateReview(Prompt prompt, Answers answer) {
        var values = completed(prompt, answer);
        runtime.runtime(prompt.projectType(), values);
        DeploymentConfigurationInput.parse(values.getOrDefault("configuration", ""));
        DeploymentConfigurationInput.secrets(values.getOrDefault("secrets", ""));
        var mode = DeploymentDatabaseReviewModeType.valueOf(values.getOrDefault("databaseMode", "UNREVIEWED"));
        DeploymentStaticInputParser.databaseBindings(mode, values.getOrDefault("databaseDetails", ""));
    }

    /** Derives a graph request from the exact reviewed runtime values. / 从精确审阅运行值派生整图请求。
     * @param component discovered component / 已发现组件
     * @param choice reviewed kind and edges / 经审阅类型与边
     * @param facts original source facts / 原来源事实
     * @param prompt source-backed defaults / 源码依据默认值
     * @param answer edited answer / 编辑答案
     * @return complete same-value analysis request / 完整同值分析请求 */
    private ComponentAnalysisRequest componentRequest(DiscoveredProjectComponent component, Choice choice,
            DeploymentProjectFacts facts, Prompt prompt, Answers answer) {
        var values = completed(prompt, answer);
        var definition = runtime.runtime(choice.type(), values);
        var configuration = DeploymentConfigurationInput.parse(values.getOrDefault("configuration", ""));
        var secrets = DeploymentConfigurationInput.secrets(values.getOrDefault("secrets", ""));
        return new ComponentAnalysisRequest(component.id(), relative(component).isEmpty() ? "." : relative(component),
                choice.type(), Optional.of(definition), runtime.artifacts(component.relativeRoot(), facts, values),
                definition.workload().endpoints().stream().map(endpoint -> endpoint.hostPort()).collect(
                        java.util.stream.Collectors.toSet()),
                configuration.stream().map(value -> value.key()).toList(),
                secrets.stream().map(DeploymentSecretReference::identifier).toList(),
                answer.files().stream().map(DeploymentFileBinding::dataPath).toList(), choice.dependencies(), true,
                ComponentIsolationSpecification.managed());
    }

    /** Forms source-backed questions without treating defaults as user consent. / 形成来源依据问题而不把默认值当用户确认。
     * @param component component scope / 组件范围
     * @param facts actual analysis / 真实分析
     * @param suggestion optional analyzer recommendation / 可选分析建议
     * @param sourceDatabase whether a source-derived database replaces manual notation / 源码派生数据库是否取代手填记法
     * @return dynamic review prompt / 动态审阅问题 */
    private Prompt prompt(String component, DeploymentProjectFacts facts,
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentRuntimeAssessment suggestion,
            boolean sourceDatabase) {
        var defaults = runtime.defaults(facts.projectType(), suggestion, facts.sourceRoot());
        defaults = new LinkedHashMap<>(defaults);
        defaults.putIfAbsent("configuration", "");
        defaults.putIfAbsent("secrets", "");
        defaults.putIfAbsent("databaseMode", "NONE");
        defaults.putIfAbsent("databaseDetails", "");
        var fields = new ArrayList<DeploymentReviewField>(runtime.corrections(component, defaults).stream()
                .filter(field -> !sourceDatabase
                        || !Set.of(component + "/databaseMode", component + "/databaseDetails").contains(field.id()))
                .toList());
        fields.addAll(runtime.missing(component, facts.projectType(), defaults));
        return new Prompt(component, facts.applicationId(), facts.projectType(), defaults, fields);
    }

    /** Binds an explicit decision to the actual manifest, tar bytes and server. / 将明确决定绑定真实清单、tar字节及服务器。
     * @param task original task / 原任务
     * @param server original server / 原服务器
     * @param applicationId exact unit / 精确单元
     * @param material original material / 原素材
     * @param archive derived archive / 派生归档
     * @param answer confirmed non-secret input / 已确认非秘密输入
     * @return existing reviewed converter input / 既有审阅转换输入
     * @throws SQLException if configuration revision cannot be read / 配置修订无法读取时
     * @throws ApiException if the original task loses admission / 原任务失去准入时 */
    private DeploymentStaticReviewAnswers answers(TaskContext task, ServerRef server, String applicationId,
            DeploymentSourceMaterial material, DeploymentSourceArchive archive, Answers answer)
            throws SQLException, ApiException {
        var consent = new DeploymentReviewConsent(applicationId, server.id(), material.snapshot().sha256(),
                archive.sha256(), Instant.now());
        long revision = new DeploymentConfigurationRepository(context.db()).nextRevision(task, applicationId);
        return new DeploymentStaticReviewAnswers(answer.values(), answer.files(), revision, answer.limits(), consent,
                answer.containerRisk(), answer.adapterRisk());
    }

    /** Maps existing immutable revisions to their actual opaque platform credentials. / 将已存不可变修订映射到实际平台不透明凭据。
     * @param task original task / 原任务
     * @param references exact reviewed revisions / 精确已审阅修订
     * @return precise saved credential map / 精确已存凭据映射
     * @throws SQLException if metadata fails / 元数据失败时
     * @throws ApiException if original access fails / 原访问失败时 */
    private Map<DeploymentSecretReference, SecretRef> credentials(TaskContext task,
            List<DeploymentSecretReference> references) throws SQLException, ApiException {
        var repository = new DeploymentSecretRepository(context.db());
        var result = new HashMap<DeploymentSecretReference, SecretRef>();
        for (var reference : references) {
            var saved = repository.findRevision(task, reference);
            if (saved.isEmpty())
                throw new IllegalArgumentException("Reviewed secret revision does not exist");
            var purpose = saved.orElseThrow().purpose();
            if (purpose.isEmpty())
                throw new ApiException(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SECRET_PURPOSE_UNAVAILABLE",
                        "Original secret purpose is unavailable", Map.of(), Optional.empty()));
            result.put(reference, new SecretRef(saved.orElseThrow().credentialId(), purpose.orElseThrow()));
        }
        return Map.copyOf(result);
    }

    /** Keeps classified refusal while dropping an unavailable payload. / 保留分类拒绝且丢弃不可用载荷。
     * @param <T> expected payload / 预期载荷
     * @param result original result / 原结果
     * @return same classification without a payload / 相同分类且无载荷 */
    private static <T> OperationResult<T> withoutValue(OperationResult<?> result) {
        return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
    }

    /** Rejects an incomplete local review without external effects. / 无外部副作用地拒绝不完整本机审阅。
     * @param <T> expected result type / 预期结果类型
     * @param event stable event / 稳定事件
     * @return known failure / 已知失败 */
    private static <T> OperationResult<T> rejected(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.INVALID_INPUT, event, "Static review is incomplete", Map.of(),
                        Optional.empty())));
    }

    /** Identifies source mutation guards that require independent verified database work. / 识别需要独立已验证数据库操作的源码变更门槛。
     * @param assessment actual reanalysis / 实际重分析
     * @return whether the missing evidence blocks publication / 缺依据是否阻止发布 */
    private static boolean schemaReviewMissing(DeploymentProjectAssessment assessment) {
        return assessment.rejections().stream().anyMatch(reason -> schemaReviewCode(reason.code()));
    }

    /** Matches only established source mutation diagnostic codes. / 仅匹配既有源码变更诊断码。
     * @param code established analyzer code / 既有分析码
     * @return whether schema evidence is needed / 是否需要模式依据 */
    private static boolean schemaReviewCode(String code) {
        return code.equals("AUTOMATIC_SCHEMA_MUTATION_DETECTED") || code.equals("DATABASE_MIGRATION_DETECTED");
    }

    /** Refuses publication until a real source-bound schema review exists. / 无真实来源绑定模式审阅前拒绝发布。
     * @return classified local failure / 已分类本机失败 */
    private static OperationResult<DeploymentReferenceFlow.PreparedPublication> schemaReviewRequired() {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.INVALID_INPUT, "DEPLOYMENT_SCHEMA_REVIEW_REQUIRED",
                        "Source schema changes require verified database review before deployment", Map.of(),
                        Optional.empty())));
    }

    /** Reports a confirmed local read failure without claiming a remote effect. / 报告确定的本机读取失败而不声称远端效果。
     * @param event stable safe event / 稳定安全事件
     * @return known local failure / 已知本机失败 */
    private static OperationResult<DeploymentReferenceFlow.PreparedPublication> ioFailure(String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
                event, "Static source review could not be read", Map.of(), Optional.empty())));
    }

    /** Reports a known pre-effect cancellation. / 报告已知的效果前取消。
     * @return cancelled original preparation / 已取消原准备 */
    private static OperationResult<DeploymentReferenceFlow.PreparedPublication> cancelled() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(), Optional.of(new Problem(ProblemCode.CANCELLED,
                "DEPLOYMENT_STATIC_REVIEW_CANCELLED", "Static review was cancelled", Map.of(), Optional.empty())));
    }
}
