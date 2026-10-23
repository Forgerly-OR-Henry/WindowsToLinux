package gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract;

import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentNativeDatabaseReview;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.DeploymentPreparedDatabase;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.deployment.db.project.DeploymentProjectRecord;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentFileBinding;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.part.DiscoveredProjectPart;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.ecosystem.db.DatabaseProjectChecker;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.input.DeploymentReviewField;

/** Immutable inputs and owner review for both standard modes. / 两种标准模式的不可变输入及所属审阅。 */
public final class DeploymentStandardInputs {
    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentStandardInputs() {
    }

    /** Captured standard source limits and original page inputs. / 已捕获标准来源限额及原页面输入。
     * @param logicalRootName reviewed logical source root name / 已审阅逻辑来源根名称
     * @param sourceBytes maximum frozen source bytes / 冻结来源字节上限
     * @param sourceEntries maximum discoverable source entries / 可发现来源条目上限
     * @param archiveBytes maximum compressed source archive bytes / 压缩来源归档字节上限
     * @param sourceOptions captured source metadata and non-secret runtime inputs / 已捕获来源元数据及非秘密运行输入 */
    public record Draft(String logicalRootName, long sourceBytes, int sourceEntries, long archiveBytes,
                        SourceOptions sourceOptions) {
        /** Retains the original host-picker workflow for direct callers. / 为直接调用方保留原宿主选择器流程。
         * @param logicalRootName original name / 原名称
         * @param sourceBytes source budget / 来源预算
         * @param sourceEntries entry budget / 条目预算
         * @param archiveBytes archive budget / 归档预算 */
        public Draft(String logicalRootName, long sourceBytes, int sourceEntries, long archiveBytes) {
            this(logicalRootName, sourceBytes, sourceEntries, archiveBytes, SourceOptions.empty());
        }

        /** Rejects invalid source limits before admission. / 准入前拒绝无效来源限额。
         * @param logicalRootName original logical name / 原逻辑名称
         * @param sourceBytes source bytes / 来源字节
         * @param sourceEntries source entries / 来源条目
         * @param archiveBytes archive bytes / 归档字节
         * @param sourceOptions captured source and runtime inputs / 抓取的来源及运行输入 */
        public Draft {
            logicalRootName = Objects.requireNonNull(logicalRootName).trim();
            Objects.requireNonNull(sourceOptions);
            if (sourceBytes < 1 || sourceBytes > 2L * 1024 * 1024 * 1024 || sourceEntries < 1 || sourceEntries > 100_000
                || archiveBytes < 1 || archiveBytes > 2L * 1024 * 1024 * 1024)
                throw new IllegalArgumentException("Source budgets differ from the supported bounds");
        }
    }

    /** Captured metadata and non-secret prefill, without a retained source grant. / 已抓取元数据及非秘密预填，不含保留来源授权。
     * @param localLocation explicit local URI metadata / 明确本地 URI 元数据
     * @param project complete old-row edit / 完整旧行编辑
     * @param runtimeDraft explicit pre-start runtime values / 明确启动前运行值 */
    public record SourceOptions(Optional<java.net.URI> localLocation,
                                Optional<DeploymentStandardInputs.ProjectEdit> project,
                                Map<String, String> runtimeDraft) {
        /** Explicit pre-start runtime keys; resource rememberAuthorization remains per component. / 明确启动前运行键；资源授权仍限定各组件。 */
        public static final List<String> RUNTIME_FIELDS = List.of("applicationDeclaration", "executionMode", "exposure", "port", "primary", "secondary",
            "version", "ports", "healthMode", "healthEndpoint", "expectedStatus", "timeout", "stability", "accessUrl",
            "configuration", "containerEngine", "volumes", "jvmArguments", "arguments", "jvmTarget");

        /** Captures immutable inputs before task admission. / 任务准入前抓取不可变输入。
         * @param localLocation local metadata / 本地元数据
         * @param project complete project edit / 完整项目编辑
         * @param runtimeDraft non-secret values / 非秘密值 */
        public SourceOptions {
            Objects.requireNonNull(localLocation);
            Objects.requireNonNull(project);
            runtimeDraft = Map.copyOf(runtimeDraft);
            localLocation.ifPresent(uri -> new gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation(uri, ""));
            if (!RUNTIME_FIELDS.containsAll(runtimeDraft.keySet()) || runtimeDraft.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank()
                || entry.getKey().length() > 128 || entry.getValue().length() > 12_000))
                throw new IllegalArgumentException("Runtime draft exceeds reviewed bounds");
        }

        /** Returns an unselected original source route. / 返回未预选原来源路径。
         * @return empty metadata / 空元数据 */
        public static SourceOptions empty() {
            return new SourceOptions(Optional.empty(), Optional.empty(), Map.of());
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
     * @param dependencies predecessor component IDs / 前驱组件标识
     * @param inputs explicit manual boundary or original automatic choice / 明确手工边界或原自动选择 */
    public record Choice(DeploymentProjectKind type, Set<String> dependencies, Optional<ComponentInputs> inputs) {
        /** Retains strict automatic-discovery choices for existing callers. / 为既有调用方保留严格自动发现选择。
         * @param type discovered type / 发现类型
         * @param dependencies explicit edges / 明确边 */
        public Choice(DeploymentProjectKind type, Set<String> dependencies) {
            this(type, dependencies, Optional.empty());
        }

        /** Freezes a component choice. / 冻结组件选择。
         * @param type selected type / 选定类型
         * @param dependencies predecessors / 前驱
         * @param inputs explicit manual boundary / 明确手工边界 */
        public Choice {
            Objects.requireNonNull(type);
            dependencies = Set.copyOf(dependencies);
            Objects.requireNonNull(inputs);
        }
    }

    /** Explicit manual component boundary, with automatic suggestions represented by absence. / 明确手工组件边界，以空值表示自动建议。
     * @param relativeRoot source-root-relative path / 相对来源根路径
     * @param required whole-application requirement / 整体应用必需性
     * @param artifactPaths explicit artifacts or runtime suggestions / 明确产物或运行建议
     * @param ports explicit host ports or runtime suggestions / 明确宿主端口或运行建议 */
    public record ComponentInputs(String relativeRoot, boolean required,
                                  Optional<List<String>> artifactPaths, Optional<Set<Integer>> ports) {
        /** Validates path syntax and bounds without reading live source. / 不读取活动来源地校验路径语法及边界。
         * @param relativeRoot relative path / 相对路径
         * @param required required component / 必需组件
         * @param artifactPaths explicit artifacts / 明确产物
         * @param ports explicit ports / 明确端口 */
        public ComponentInputs {
            relativeRoot = Objects.requireNonNull(relativeRoot).trim().replace('\\', '/');
            if (!(relativeRoot.equals(".") || relativeRoot.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*"))
                || relativeRoot.contains(".."))
                throw new IllegalArgumentException("Component root escapes frozen source");
            artifactPaths = Objects.requireNonNull(artifactPaths).map(List::copyOf);
            ports = Objects.requireNonNull(ports).map(Set::copyOf);
            if (artifactPaths.filter(values -> values.size() > 64).isPresent()
                || ports.filter(values -> values.size() > 64 || values.stream().anyMatch(port -> port < 1 || port > 65535)).isPresent())
                throw new IllegalArgumentException("Component declarations exceed bounds");
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
                          boolean containerRisk, boolean adapterRisk,
                          gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request workspaceRequest,
                          Optional<gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Selection> workspaceSelection) {
        public Answers(Map<String, String> values, List<DeploymentFileBinding> files, DeploymentBuildLimits limits,
                       boolean containerRisk, boolean adapterRisk) {
            this(values, files, limits, containerRisk, adapterRisk,
                gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Request.explicit(limits.maxWorkspaceBytes()), Optional.empty());
        }

        public Answers withWorkspace(gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.Selection selected) {
            var fixed = new DeploymentBuildLimits(limits.timeoutSeconds(), limits.maxProcesses(), limits.maxMemoryMiB(),
                limits.maxOutputBytes(), selected.bytes(), limits.runAsRoot(), selected.policy().reserveBytes());
            return new Answers(values, files, fixed, containerRisk, adapterRisk, workspaceRequest, Optional.of(selected));
        }

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
            Objects.requireNonNull(workspaceRequest);
            Objects.requireNonNull(workspaceSelection);
            if (workspaceSelection.isPresent() && workspaceSelection.orElseThrow().bytes() != limits.maxWorkspaceBytes())
                throw new IllegalArgumentException("Reviewed workspace selection differs from build limits");
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
        default OperationResult<Map<String, String>> databaseInputs(ServerId server, String componentId,
                                                                    String sourceSha256, Path root, String applicationId, DatabaseProjectChecker.Report assessment,
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
        default OperationResult<Boolean> confirmSqliteInitialization(ServerId server, String componentId,
                                                                     String sourceSha256, DatabaseProjectChecker.Report assessment, TaskContext task)
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
        OperationResult<Selection> select(ServerId server, String sourceSha256,
                                          List<DiscoveredProjectPart> discovered, TaskContext task) throws Exception;

        /** Collects editable typed runtime inputs before archive creation. / 归档前收集可编辑类型化运行输入。
         * @param server exact server / 精确服务器
         * @param sourceSha256 manifest digest / 清单摘要
         * @param prompts typed dynamic questions / 类型化动态问题
         * @param task original task / 原任务
         * @return complete answers or classified refusal / 完整答案或分类拒绝
         * @throws Exception if the owned view cannot observe its decision / 所属视图无法观察决定时 */
        OperationResult<Map<String, Answers>> review(ServerId server, String sourceSha256, List<Prompt> prompts,
                                                     TaskContext task) throws Exception;

        /** Reviews only four typed build fields while retaining the original immutable form scope. / 仅审阅四项类型化构建字段，保留原不可变表单范围。
         * @param server original exact target / 原精确目标
         * @param sourceSha256 original frozen manifest / 原冻结清单
         * @param prompt exact four technical fields / 精确四项技术字段
         * @param original original complete human answers / 原完整人工答案
         * @param task authentic original task / 真实原任务
         * @return explicitly reviewed answers or refusal / 明确审阅答案或拒绝
         * @throws Exception if original view scope or identity conflicts / 原视图范围或身份冲突时 */
        default OperationResult<Answers> reviewBuildCorrection(ServerId server, String sourceSha256, Prompt prompt,
                                                               Answers original, TaskContext task) throws Exception {
            var result = review(server, sourceSha256, List.of(prompt), task);
            if (result.outcome() != TaskResult.SUCCEEDED)
                return new OperationResult<>(result.outcome(), Optional.empty(), result.problem());
            var values = result.value().orElseThrow();
            if (!values.keySet().equals(Set.of(prompt.componentId())))
                throw new SecurityException("Build correction changed component scope");
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(values.get(prompt.componentId())),
                Optional.empty());
        }

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
        OperationResult<Boolean> confirm(ServerId server, String sourceSha256, Map<String, String> archiveSha256,
                                         Map<String, Answers> answers, Map<String, DeploymentPreparedDatabase> databases,
                                         Optional<GitReview> git, TaskContext task) throws Exception;
    }

    /** Independent standard mode admission and deterministic review validation. / 独立标准模式准入及确定性审阅校验。 */
    public interface Entry {
        /** Admits one source, review and publication task using captured page inputs. / 使用已捕获页面输入准入一项来源、审阅与发布任务。
         * @param server exact original target server / 精确原目标服务器
         * @param draft captured source and page values / 已捕获来源及页面值
         * @param git optional captured Git source / 可选已捕获 Git 来源
         * @param reviewer original human review adapter / 原人工审阅适配
         * @param approvalMode captured approval policy / 已捕获审批策略
         * @param rememberAuthorization captured exact-action reuse preference / 已捕获精确动作复用偏好
         * @return one original admitted publication handle / 一项原已准入发布句柄
         * @throws gold.debug.windowstolinux.appapi.task.result.ApiException if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
        gold.debug.windowstolinux.appapi.task.run.TaskHandle<Void> submit(
            gold.debug.windowstolinux.appapi.server.model.ServerId server, Draft draft,
            java.util.Optional<GitSource> git, Reviewer reviewer, String approvalMode,
            boolean rememberAuthorization) throws gold.debug.windowstolinux.appapi.task.result.ApiException;

        /** Validates human or suggested values against the original deterministic form. / 按原确定性表单校验人工或建议值。
         * @param prompt original deterministic review prompt / 原确定性审阅问题
         * @param answer reviewed component answer / 已审阅组件答案 */
        void validateReview(Prompt prompt, Answers answer);
    }

    /** Returns the original structured incomplete-review failure. / 返回原结构化审阅不完整失败。
     * @param event stable structured failure event / 稳定结构化失败事件
     * @param <T> task result value type / 任务结果值类型
     * @return original structured incomplete-review failure / 原结构化审阅不完整失败 */
    private static <T> OperationResult<T> rejected(String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
            Optional.of(new Problem(ProblemCode.INVALID_INPUT, event, "Static review is incomplete", Map.of(), Optional.empty())));
    }

    /** Original project row identity with a later exact observation. / 带更晚精确观测的原项目行身份。
     * @param id exact original stored or logical identity / 精确原存储或逻辑身份
     * @param displayName bounded user-selected project display name / 有界用户选择项目显示名称
     * @param expected complete original project row used for compare-and-save / 用于比较保存的完整原项目行
     * @param observedAt exact observation time / 精确观测时间 */
    public record ProjectEdit(UUID id, String displayName, Optional<DeploymentProjectRecord> expected,
                              Instant observedAt) {
        /** Validates the old identity and actual observation before source work. / 来源工作前校验旧身份及实际观测。
         * @param id stable identity / 稳定身份
         * @param displayName user name / 用户名称
         * @param expected complete old row / 完整旧行
         * @param observedAt observation / 观测 */
        public ProjectEdit {
            Objects.requireNonNull(id);
            Objects.requireNonNull(observedAt);
            Objects.requireNonNull(expected);
            if (displayName == null || displayName.isBlank() || displayName.length() > 256
                || displayName.chars().anyMatch(c -> c < 32 || c == 127) || observedAt.getNano() % 1_000_000 != 0
                || expected.isPresent() && (!expected.orElseThrow().id().equals(id)
                || !observedAt.isAfter(expected.orElseThrow().updatedAt())))
                throw new IllegalArgumentException("Project edit requires complete original identity and later observation");
        }
    }
}
