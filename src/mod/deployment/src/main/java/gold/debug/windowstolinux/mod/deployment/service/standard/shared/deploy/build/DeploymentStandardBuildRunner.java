package gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.CompanionBuildScript;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.DeploymentBuildEnvironmentWriter;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentProjectToolchainRequirements;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.ToolchainBuildEnvironment;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainSetup;

/** Executes one reviewed source candidate through exact helper tool binding and a restricted build. / 经精确 helper 工具绑定与受限构建执行一个已审阅来源候选。 */
final class DeploymentStandardBuildRunner {
    private final gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver preparationObserver;

    DeploymentStandardBuildRunner(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver preparationObserver) {
        this(DeploymentToolchainCatalog.defaults(), new DeploymentStandardPlanner(), preparationObserver);
    }

    /** One original pre-upload selection; no remote candidate is implied. / 一次原上传前选择，不意味着远端候选已存在。 */
    static final class Prepared {
        /** Original reviewed plan. / 原已审阅计划。 */
        final ReviewedDeploymentPlan plan;

        /** Rendered source-bound plan. / 与原来源绑定的渲染计划。 */
        final DeploymentStandardPlanner.RenderedPlan rendered;

        /** Candidate naming value, not upload evidence. / 候选命名值，不是上传证据。 */
        final DeploymentRemoteWorkspace workspace;

        /** Reviewed build environment. / 已审阅构建环境。 */
        final DeploymentBuildEnvironment environment;

        /** Shared remote execution budget. / 共用远端执行预算。 */
        final DeploymentBuildBudget budget;

        /** Original approved command channel. / 原已批准命令通道。 */
        final DeploymentBuildCommandChannel commands;

        /** Project tool requirements. / 项目工具要求。 */
        final List<ToolchainRequirement> requirements;

        /** Installed exact tool identities. / 已安装的精确工具身份。 */
        final DeploymentResolvedToolchains tools;

        /** Exact tools of the most recent actual build send. / 最近实际构建发送的精确工具。 */
        DeploymentResolvedToolchains executedTools;

        /** Selected build engine. / 已选构建引擎。 */
        final String engine;

        /** Current candidate attempt. / 当前候选次数。 */
        final int attempt;

        /** Borrowed original session. / 借用的原会话。 */
        final RemoteSession session;

        /** Original admitted task. / 原准入任务。 */
        final TaskContext task;

        /** Single-use guard. / 单次消费门禁。 */
        private boolean consumed;

        /** Stores the one pre-upload selection. / 保存一次上传前选择。
         * @param plan reviewed plan / 已审阅计划
         * @param rendered rendered source plan / 已渲染来源计划
         * @param workspace candidate naming value / 候选命名值
         * @param environment reviewed build values / 已审阅构建值
         * @param budget shared execution budget / 共用执行预算
         * @param commands original command channel / 原命令通道
         * @param requirements tool requirements / 工具要求
         * @param tools exact installed tools / 精确已安装工具
         * @param engine selected engine / 已选引擎
         * @param attempt current attempt / 当前尝试次数
         * @param session original session / 原会话
         * @param task original task / 原任务 */
        Prepared(ReviewedDeploymentPlan plan, DeploymentStandardPlanner.RenderedPlan rendered,
                 DeploymentRemoteWorkspace workspace, DeploymentBuildEnvironment environment,
                 DeploymentBuildBudget budget, DeploymentBuildCommandChannel commands,
                 List<ToolchainRequirement> requirements, DeploymentResolvedToolchains tools, String engine, int attempt,
                 RemoteSession session, TaskContext task) {
            this.plan = plan;
            this.rendered = rendered;
            this.workspace = workspace;
            this.environment = environment;
            this.budget = budget;
            this.commands = commands;
            this.requirements = requirements;
            this.tools = tools;
            this.executedTools = tools;
            this.engine = engine;
            this.attempt = attempt;
            this.session = session;
            this.task = task;
        }

        /** Consumes only for the original task and session. / 仅允许原任务及会话单次消费。
         * @param actualSession supplied session / 提供的会话
         * @param actualTask supplied task / 提供的任务 */
        synchronized void consume(RemoteSession actualSession, TaskContext actualTask) {
            if (consumed || session != actualSession || task != actualTask)
                throw new IllegalStateException("Prepared build belongs to another operation or was consumed");
            consumed = true;
        }
    }

    /** The same reviewed catalog used by planning and remote identity checks. / 规划与远端身份核对共用的已审阅目录。 */
    private final DeploymentToolchainCatalog catalog;

    /** Frozen source and renderer contract. / 冻结来源与渲染器契约。 */
    private final DeploymentStandardPlanner planner;

    /** Creates the official fixed build executor. / 创建官方固定构建执行器。 */
    DeploymentStandardBuildRunner() {
        this(DeploymentToolchainCatalog.defaults(), new DeploymentStandardPlanner());
    }

    /** Binds an exact catalog and renderer for controlled tests. / 为受控测试绑定精确目录和渲染器。
     * @param catalog reviewed tool releases / 已审阅工具发布
     * @param planner original source-bound planner / 原来源绑定规划器 */
    DeploymentStandardBuildRunner(DeploymentToolchainCatalog catalog, DeploymentStandardPlanner planner) {
        this(catalog, planner, gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver.NONE);
    }

    private DeploymentStandardBuildRunner(DeploymentToolchainCatalog catalog, DeploymentStandardPlanner planner,
        gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentPreparationObserver preparationObserver) {
        this.catalog = Objects.requireNonNull(catalog);
        this.planner = Objects.requireNonNull(planner);
        this.preparationObserver = Objects.requireNonNull(preparationObserver);
    }

    /** Internal build-only compatibility path; a workspace value does not prove upload. / 内部仅构建兼容路径；工作区值不能证明已上传。
     * @param plan original reviewed single-component plan / 原已审阅单组件计划
     * @param uploaded original candidate already uploaded and confirmed by its owning flow / 已由所属流程上传并确认的原候选
     * @param session original authenticated connection, closed by its caller / 原已认证连接，由调用方关闭
     * @param interaction real host review capability / 真实宿主审核能力
     * @param task original admitted task / 原准入任务
     * @return exact archive and installed tool identities only after a confirmed build / 仅确认构建后返回精确归档及已安装工具身份
     * @throws ApiException if the host rejects admission before dispatch / 宿主发送前拒绝准入时 */
    OperationResult<DeploymentVerifiedBuildResult> build(ReviewedDeploymentPlan plan,
                                                         DeploymentRemoteWorkspace uploaded, RemoteSession session, InteractionService interaction, TaskContext task)
        throws ApiException {
        if (!Objects.requireNonNull(uploaded).equals(new DeploymentRemoteWorkspace(
            plan.request().facts().applicationId(), plan.request().archive().sha256(), plan.request().layout())))
            throw new IllegalArgumentException("Build candidate differs from reviewed source");
        var prepared = prepareForUpload(plan, session, interaction, task);
        if (prepared.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(prepared);
        return buildPrepared(prepared.value().orElseThrow(), uploaded, session, task);
    }

    /** Confirms isolation and exact tool binding before source upload. / 在源码上传前确认隔离与精确工具绑定。
     * @param plan original reviewed plan / 原已审阅计划
     * @param session borrowed original session / 借用的原会话
     * @param interaction actual host review / 真实宿主审核
     * @param task original task / 原任务
     * @return one prepared selection without a candidate / 未创建候选的一次已准备选择
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    OperationResult<Prepared> prepareForUpload(ReviewedDeploymentPlan plan, RemoteSession session,
                                               InteractionService interaction, TaskContext task) throws ApiException {
        return prepareForUpload(plan, session, interaction, task,
            new DeploymentBuildBudget(plan.request().limits().timeoutSeconds()));
    }

    /** Shares one cumulative execution budget with the preceding candidate preflight. / 与先前候选预检共用一份累计执行预算。
     * @param plan original reviewed plan / 原已审阅计划
     * @param session original session / 原会话
     * @param interaction actual host review / 真实宿主审核
     * @param task original task / 原任务
     * @param budget original cumulative execution budget / 原累计执行预算
     * @return exact installed selection / 精确已安装选择
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    OperationResult<Prepared> prepareForUpload(ReviewedDeploymentPlan plan, RemoteSession session,
                                               InteractionService interaction, TaskContext task, DeploymentBuildBudget budget) throws ApiException {
        return prepareForUpload(plan, session, interaction, task, budget, 0, true);
    }

    /** Uses only an explicitly reviewed catalog branch while retaining the original cumulative time. / 保留原累计时间，仅使用明确审阅目录分支。
     * @param plan actual newly reviewed plan / 实际新审阅计划
     * @param session original session / 原会话
     * @param interaction original exact approval / 原精确审批
     * @param task original task / 原任务
     * @param budget unchanged original time budget / 不变原时间预算
     * @param branch explicitly admitted fixed branch / 明确准入固定分支
     * @param fallback whether original static minimum fallback remains allowed / 是否仍允许原静态最低版本回退
     * @return confirmed installed selection / 已确认安装选择
     * @throws ApiException if original approval or transport rejects / 原审批或传输拒绝时 */
    OperationResult<Prepared> prepareForUpload(ReviewedDeploymentPlan plan, RemoteSession session,
                                               InteractionService interaction, TaskContext task, DeploymentBuildBudget budget, int branch, boolean fallback) throws ApiException {
        return prepareForUpload(plan, session, interaction, task, budget, branch, fallback,
            new DeploymentRemoteWorkspace(plan.request().facts().applicationId(), plan.request().archive().sha256(), plan.request().layout()));
    }

    /** Prepares an explicitly named later candidate under the unchanged task and cumulative budget. / 在不变任务及累计预算下准备明确命名的后续候选。
     * @param plan actual full reviewed plan / 实际完整审阅计划
     * @param session authentic current borrowed connection / 真实当前借用连接
     * @param interaction exact frozen business approval / 精确冻结业务审批
     * @param task authentic original task / 真实原任务
     * @param budget original cumulative execution time / 原累计执行时间
     * @param branch explicitly reviewed fixed catalog branch / 明确审阅固定目录分支
     * @param fallback whether the remaining shared retry permits fallback / 剩余共用重试是否允许回退
     * @param workspace source and operation bound candidate / 来源及操作绑定候选
     * @return confirmed actual installed selection / 已确认实际安装选择
     * @throws ApiException if original authority rejects / 原权限拒绝时 */
    OperationResult<Prepared> prepareForUpload(ReviewedDeploymentPlan plan, RemoteSession session,
                                               InteractionService interaction, TaskContext task, DeploymentBuildBudget budget, int branch, boolean fallback,
                                               DeploymentRemoteWorkspace workspace) throws ApiException {
        return prepareForUpload(plan, session, interaction, task, budget, branch, fallback, workspace, null);
    }

    OperationResult<Prepared> prepareForUpload(ReviewedDeploymentPlan plan, RemoteSession session,
                                               InteractionService interaction, TaskContext task, DeploymentBuildBudget budget, int branch, boolean fallback,
                                               DeploymentRemoteWorkspace workspace,
                                               gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.Session trackedSession) throws ApiException {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(session);
        Objects.requireNonNull(interaction);
        Objects.requireNonNull(task);
        var request = plan.request();
        var facts = request.facts();
        var runtime = request.runtime();
        var limits = request.limits();
        if (!Objects.requireNonNull(workspace).applicationId().equals(facts.applicationId())
            || !workspace.archiveSha256().equals(request.archive().sha256()) || !workspace.layout().equals(request.layout()))
            throw new IllegalArgumentException("Prepared operation changed original source or unit");
        var buildValues = new LinkedHashMap<String, String>();
        for (var entry : request.configuration().entries()) {
            if (entry.scope() == DeploymentConfigScopeType.BUILD)
                buildValues.put(entry.key(), entry.value().canonicalValue());
        }
        var environment = new DeploymentBuildEnvironment(request.configuration().applicationId(), buildValues);
        var rendered = planner.render(plan, task, request.layout());
        if (rendered.outcome() != TaskResult.SUCCEEDED)
            return withoutValuePrepared(rendered);
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            return cancelledPrepared();
        String revision = rendered.value().orElseThrow().reviewedPlanSha256();
        String engine = runtime instanceof DeploymentRuntimeDefinition.Container container
            ? container.engine().name().toLowerCase(java.util.Locale.ROOT)
            : "ordinary";
        Objects.requireNonNull(budget);
        var commands = new DeploymentBuildCommandChannel(session, interaction, request.server(), task, revision,
            budget, gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE,
            request.layout(), preparationObserver, trackedSession);
        var preflight = commands.preflight(engine);
        if (preflight.outcome() == TaskResult.UNKNOWN || preflight.outcome() == TaskResult.CANCELLED)
            return withoutValuePrepared(preflight);
        if (preflight.outcome() == TaskResult.FAILED && preflight.value().isEmpty())
            return withoutValuePrepared(preflight);
        if (!knownSuccess(preflight))
            return preflight.outcome() == TaskResult.FAILED && knownExit(preflight)
                ? failedPrepared(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_BUILD_ISOLATION_UNAVAILABLE")
                : unknownPrepared("DEPLOYMENT_BUILD_PREFLIGHT_UNCONFIRMED");
        List<ToolchainRequirement> requirements = DeploymentProjectToolchainRequirements.from(facts, runtime);
        if (trackedSession != null)
            commands.saveTrackedRecipe(recipe(rendered.value().orElseThrow().buildScript(), request, environment));
        var preparer = new DeploymentToolchainSetup(commands, catalog);
        int attempt = branch;
        OperationResult<DeploymentResolvedToolchains> prepared = preparer.prepare(requirements, attempt,
            (int) budget.remainingSeconds());
        if (fallback && prepared.outcome() == TaskResult.FAILED
            && prepared.problem().orElseThrow().eventCode().equals("DEPLOYMENT_TOOLCHAIN_MINIMUM")
            && hasAlternative(requirements)) {
            attempt = 1;
            prepared = preparer.prepare(requirements, attempt, (int) budget.remainingSeconds());
        }
        if (prepared.outcome() != TaskResult.SUCCEEDED)
            return withoutValuePrepared(prepared);
        var tools = prepared.value().orElseThrow();
        return new OperationResult<>(
            TaskResult.SUCCEEDED, Optional.of(new Prepared(plan, rendered.value().orElseThrow(), workspace,
            environment, budget, commands, requirements, tools, engine, attempt, session, task)),
            Optional.empty());
    }

    /** Builds the exact uploaded candidate using the pre-upload selection and budget. / 使用上传前的选择与预算构建精确候选。
     * @param prepared original confirmed pre-upload selection / 原已确认上传前选择
     * @param uploaded independently confirmed uploaded workspace / 独立确认已上传的工作区
     * @param session same original borrowed session / 同一原借用会话
     * @param task same original admitted task / 同一原准入任务
     * @return confirmed build or retained uncertainty / 已确认构建或保留的不确定性
     * @throws ApiException if host admission rejects before dispatch / 宿主发送前拒绝准入时 */
    OperationResult<DeploymentVerifiedBuildResult> buildPrepared(Prepared prepared, DeploymentRemoteWorkspace uploaded,
                                                                 RemoteSession session, TaskContext task) throws ApiException {
        return buildPrepared(prepared, uploaded, session, task, true);
    }

    /** Keeps assisted retries outside the original static compiler fallback. / 将辅助重试置于原静态编译器回退之外。
     * @param prepared actual installed selection / 实际安装选择
     * @param uploaded verified original candidate / 已核原候选
     * @param session original session / 原会话
     * @param task original task / 原任务
     * @param fallback whether ordinary static fallback is allowed / 是否允许普通静态回退
     * @return confirmed original build outcome / 已确认原构建结果
     * @throws ApiException if original authority refuses / 原权限拒绝时 */
    OperationResult<DeploymentVerifiedBuildResult> buildPrepared(Prepared prepared, DeploymentRemoteWorkspace uploaded,
                                                                 RemoteSession session, TaskContext task, boolean fallback) throws ApiException {
        Objects.requireNonNull(prepared);
        if (!prepared.workspace.equals(uploaded))
            throw new IllegalArgumentException("Uploaded candidate differs from prepared source");
        prepared.consume(session, task);
        if (task.cancelToken().requested() || Thread.currentThread().isInterrupted())
            return cancelled();
        var rerendered = planner.render(prepared.plan, task, prepared.plan.request().layout());
        if (rerendered.outcome() != TaskResult.SUCCEEDED)
            return withoutValue(rerendered);
        if (!rerendered.value().orElseThrow().reviewedPlanSha256().equals(prepared.rendered.reviewedPlanSha256()))
            return unknown("DEPLOYMENT_BUILD_PLAN_CHANGED");
        var request = prepared.plan.request();
        var budget = prepared.budget;
        var commands = prepared.commands;
        var engine = prepared.engine;
        var requirements = prepared.requirements;
        int attempt = prepared.attempt;
        var preparer = new DeploymentToolchainSetup(commands, catalog);
        var built = execute(prepared.rendered.buildScript(), request, uploaded, prepared.environment, commands, budget,
            engine, prepared.tools);
        if (fallback && built.outcome() == TaskResult.FAILED
            && built.problem().orElseThrow().eventCode().equals("DEPLOYMENT_BUILD_COMPATIBLE_FAILURE")
            && attempt == 0 && hasAlternative(requirements) && budget.remainingSeconds() > 60) {
            var second = preparer.prepare(requirements, 1, (int) budget.remainingSeconds());
            if (second.outcome() != TaskResult.SUCCEEDED)
                return withoutValue(second);
            prepared.executedTools = second.value().orElseThrow();
            built = execute(prepared.rendered.buildScript(), request, uploaded, prepared.environment, commands, budget,
                engine, second.value().orElseThrow());
        }
        if (fallback && built.outcome() == TaskResult.FAILED
            && built.problem().orElseThrow().eventCode().equals("DEPLOYMENT_BUILD_COMPATIBLE_FAILURE")) {
            var original = built.problem().orElseThrow();
            return new OperationResult<>(TaskResult.FAILED, Optional.empty(),
                Optional.of(new Problem(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_BUILD_CANDIDATES_EXHAUSTED",
                    original.message(), original.context(), original.diagnosticId())));
        }
        return built;
    }

    /** Executes the fixed script with an installed binding under the unchanged cumulative budget. / 在不变的累计预算内以已安装绑定执行固定脚本。
     * @param base original architecture script / 原架构脚本
     * @param request reviewed request / 已审阅请求
     * @param workspace previously uploaded candidate / 此前已上传候选
     * @param environment reviewed non-secret values / 已审阅非秘密值
     * @param commands exact original host channel / 精确原宿主通道
     * @param budget cumulative execution time / 累计执行时间
     * @param engine fixed engine / 固定引擎
     * @param tools exact installed tools / 精确已安装工具
     * @return confirmed result, known failure or retained uncertainty / 已确认结果、已知失败或保留未知
     * @throws ApiException if the host rejects admission before dispatch / 宿主发送前拒绝准入时 */
    private static OperationResult<DeploymentVerifiedBuildResult> execute(String base,
                                                                          gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest request,
                                                                          DeploymentRemoteWorkspace workspace, DeploymentBuildEnvironment environment,
                                                                          DeploymentBuildCommandChannel commands, DeploymentBuildBudget budget, String engine,
                                                                          DeploymentResolvedToolchains tools) throws ApiException {
        long remaining = budget.remainingSeconds();
        if (remaining <= 15)
            return failed(ProblemCode.TIMEOUT, "DEPLOYMENT_BUILD_BUDGET_EXHAUSTED");
        String script = script(base, request, environment, tools);
        String toolchainBinding = tools.selections().isEmpty() ? "-" : java.util.Base64.getEncoder().encodeToString(
            DeploymentToolchainBindingCodec.encode(tools).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var observed = commands.build(workspace, request.limits(), remaining - 15, engine, script,
            toolchainBinding, BuildMatchPolicy.groups());
        if (observed.outcome() == TaskResult.UNKNOWN || observed.outcome() == TaskResult.CANCELLED)
            return withoutValue(observed);
        if (observed.outcome() == TaskResult.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (!knownSuccess(observed))
            return observed.outcome() == TaskResult.FAILED && knownExit(observed)
                ? unknown("DEPLOYMENT_BUILD_WRAPPER_FAILED")
                : unknown("DEPLOYMENT_BUILD_RESULT_UNCONFIRMED");
        DeploymentBuildOutputProtocol.Receipt receipt;
        try {
            receipt = DeploymentBuildOutputProtocol.parse(observed.value().orElseThrow().stdout());
        } catch (IllegalArgumentException invalid) {
            return unknown("DEPLOYMENT_BUILD_REPORT_UNCONFIRMED");
        }
        if (receipt.conflicting() || receipt.exit() < 0 || receipt.bytes() > request.limits().maxOutputBytes() + 65536L)
            return unknown("DEPLOYMENT_BUILD_EVIDENCE_CONFLICT");
        if (!receipt.uid().matches("[1-9][0-9]{0,9}") || !engine.equals(receipt.engine())
            || !request.facts().buildTool().name().equals(receipt.tool()))
            return unknown("DEPLOYMENT_BUILD_IDENTITY_UNCONFIRMED");
        if (receipt.exit() != 0)
            return failedBuild(receipt,
                receipt.compatible() && receipt.limit().equals("-")
                    ? "DEPLOYMENT_BUILD_COMPATIBLE_FAILURE"
                    : receipt.limit().equals("-") ? "DEPLOYMENT_BUILD_FAILED" : "DEPLOYMENT_BUILD_LIMIT");
        if (!receipt.limit().equals("-"))
            return unknown("DEPLOYMENT_BUILD_IDENTITY_UNCONFIRMED");
        var result = new DeploymentVerifiedBuildResult(workspace.archiveSha256(), workspace.candidateId(), tools,
            DeploymentToolchainBindingCodec.identity(tools), workspace.operation());
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(result), Optional.empty());
    }

    /** Reconstructs only the exact fixed script sent with a confirmed tool selection. / 仅重建随已确认工具选择发送的精确固定脚本。
     * @param base actual architecture renderer text / 实际架构渲染文本
     * @param request complete reviewed request / 完整审阅请求
     * @param environment fixed configuration values / 固定配置值
     * @param tools actual installed tools / 实际安装工具
     * @return complete fixed build input / 完整固定构建输入 */
    static String script(String base, gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest request,
                         DeploymentBuildEnvironment environment, DeploymentResolvedToolchains tools) {
        return recipe(base, request, environment).render(tools);
    }

    static gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.ScriptRecipe recipe(String base,
            gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest request,
            DeploymentBuildEnvironment environment) {
        return new gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentTrackedToolchains.ScriptRecipe(1, 1,
            failureDiagnostics() + DeploymentBuildEnvironmentWriter.render(environment), base
            + CompanionBuildScript.render(request.runtime().workload(), request.facts()) + (request.runtime().workload().companions().isEmpty()
            ? "" : "\ntest \"$(du -sb \"$mutable\" | cut -f1)\" -le " + request.limits().maxWorkspaceBytes() + "\n"));
    }

    /** Identifies silent fixed-script failures without printing commands or environment values. / 标识固定脚本的静默失败，不打印命令或环境值。
     * @return fixed numeric failure diagnostic / 固定数字失败诊断 */
    static String failureDiagnostics() {
        return """
            set -EeTuo pipefail
            wtl_failure_line=1
            trap 'if [ "$LINENO" -gt 1 ]; then wtl_failure_line=$LINENO; fi' DEBUG
            trap 'wtl_failure_line=$LINENO' ERR
            trap 'wtl_failure_status=$?; trap - DEBUG; if [ "$wtl_failure_status" -ne 0 ]; then printf "BUILD_FAILURE_LINE=%s\\n" "$wtl_failure_line"; fi' EXIT
            """;
    }

    /** Reports a complete successful host command without interpreting author text. / 不解释作者文本，仅报告完整成功的宿主命令。
     * @param result original host observation / 原宿主观测
     * @return true only for known exit zero and no truncation / 仅已知退出零且未截断时为真 */
    private static boolean knownSuccess(OperationResult<CommandResult> result) {
        return result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()
            && result.value().orElseThrow().exitCode().equals(Optional.of(0))
            && !result.value().orElseThrow().truncated();
    }

    /** Finds an actual second catalog candidate before retry. / 重试前确认确有第二目录候选。
     * @param requirements original requirements / 原需求
     * @return whether another supported branch exists / 是否存在另一受支持分支 */
    private boolean hasAlternative(List<ToolchainRequirement> requirements) {
        return requirements.stream().anyMatch(requirement -> catalog.candidates(requirement).size() > 1);
    }

    /** Distinguishes observed nonzero exit from missing completion. / 区分已观察非零退出与完成证据缺失。
     * @param result original host observation / 原宿主观测
     * @return true only for a known nonzero exit / 仅已知非零退出时为真 */
    private static boolean knownExit(OperationResult<CommandResult> result) {
        return result.value().isPresent() && !result.value().orElseThrow().truncated()
            && result.value().orElseThrow().exitCode().isPresent()
            && result.value().orElseThrow().exitCode().orElseThrow() != 0;
    }

    /** Propagates a public non-success without inventing a build value. / 不虚构构建值地传播公开非成功结果。
     * @param outcome original outcome / 原结果
     * @return same problem without a build value / 无构建值的相同问题 */
    private static OperationResult<DeploymentVerifiedBuildResult> withoutValue(OperationResult<?> outcome) {
        return new OperationResult<>(outcome.outcome(), Optional.empty(), outcome.problem());
    }

    /** Propagates a pre-upload outcome without manufacturing a selection. / 不虚构选择地传播上传前结果。
     * @param outcome original outcome / 原结果
     * @return same outcome without a selection / 无选择的相同结果 */
    private static OperationResult<Prepared> withoutValuePrepared(OperationResult<?> outcome) {
        return new OperationResult<>(outcome.outcome(), Optional.empty(), outcome.problem());
    }

    /** Returns a known pre-upload failure. / 返回已知上传前失败。
     * @param code public category / 公开类别
     * @param event stable event / 稳定事件
     * @return failed preparation / 已失败的准备 */
    private static OperationResult<Prepared> failedPrepared(ProblemCode code, String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem(code, event)));
    }

    /** Retains uncertain pre-upload effects. / 保留未知上传前副作用。
     * @param event stable event / 稳定事件
     * @return uncertain preparation / 未知准备 */
    private static OperationResult<Prepared> unknownPrepared(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Stops before further remote work on cancellation. / 取消时停止后续远端操作。
     * @return cancelled preparation / 已取消准备 */
    private static OperationResult<Prepared> cancelledPrepared() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
            Optional.of(problem(ProblemCode.CANCELLED, "DEPLOYMENT_BUILD_CANCELLED")));
    }

    /** Returns a known failure. / 返回已知失败。
     * @param code public category / 公开分类
     * @param event stable event / 稳定事件
     * @return failed operation / 失败操作 */
    private static OperationResult<DeploymentVerifiedBuildResult> failed(ProblemCode code, String event) {
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(problem(code, event)));
    }

    /** Exposes only bounded, already-redacted compiler output from a complete fixed receipt. / 仅从完整固定回执公开有界且已遮蔽的编译输出。
     * @param receipt complete fixed report / 完整固定报告
     * @param event stable build failure / 稳定构建失败事件
     * @return known failure with safe compiler diagnostics / 含安全编译诊断的已知失败 */
    private static OperationResult<DeploymentVerifiedBuildResult> failedBuild(
        DeploymentBuildOutputProtocol.Receipt receipt, String event) {
        String head = receipt.head();
        String tail = receipt.tail();
        String diagnostic = head.equals(tail) ? head : head + "\n[... output omitted ...]\n" + tail;
        var context = Map.of("exit", Integer.toString(receipt.exit()), "outputBytes", Long.toString(receipt.bytes()),
            "outputSha256", receipt.digest(), "diagnostic", diagnostic, "uid", receipt.uid(), "engine", receipt.engine(), "tool", receipt.tool());
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
            event, "Reviewed build failed: " + diagnostic, context, Optional.empty())));
    }

    /** Retains uncertain remote effects. / 保留未知远端副作用。
     * @param event stable event / 稳定事件
     * @return unknown operation / 未知操作 */
    private static OperationResult<DeploymentVerifiedBuildResult> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Returns a confirmed cancellation before remote dispatch. / 返回远端发送前已确认取消。
     * @return cancelled build / 已取消构建 */
    private static OperationResult<DeploymentVerifiedBuildResult> cancelled() {
        return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(),
            Optional.of(problem(ProblemCode.CANCELLED, "DEPLOYMENT_BUILD_CANCELLED")));
    }

    /** Builds only a stable safe diagnostic. / 仅构造稳定安全诊断。
     * @param code category / 分类
     * @param event stable event / 稳定事件
     * @return bounded public problem / 有界公开问题 */
    private static Problem problem(ProblemCode code, String event) {
        return new Problem(code, event, "Reviewed build could not be confirmed", Map.of(), Optional.empty());
    }
}
