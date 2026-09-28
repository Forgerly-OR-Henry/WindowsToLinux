package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.CommandResult;
import gold.debug.windowstolinux.appapi.linux.RemoteSession;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentBuildOutputProtocol;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.generation.script.CompanionBuildScript;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.build.generation.script.DeploymentBuildEnvironmentRenderer;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.toolchain.DeploymentProjectToolchainRequirements;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.toolchain.ToolchainBuildEnvironment;

/** Executes one reviewed source candidate through exact helper tool binding and a restricted build. / 经精确 helper 工具绑定与受限构建执行一个已审阅来源候选。 */
public final class DeploymentStandardBuildExecutor {
    /** The same reviewed catalog used by planning and remote identity checks. / 规划与远端身份核对共用的已审阅目录。 */
    private final DeploymentToolchainCatalog catalog;

    /** Frozen source and renderer contract. / 冻结来源与渲染器契约。 */
    private final DeploymentStandardPlanner planner;

    /** Creates the official fixed build executor. / 创建官方固定构建执行器。 */
    public DeploymentStandardBuildExecutor() {
        this(DeploymentToolchainCatalog.defaults(), new DeploymentStandardPlanner());
    }

    /** Binds an exact catalog and renderer for controlled tests. / 为受控测试绑定精确目录和渲染器。
     * @param catalog reviewed tool releases / 已审阅工具发布
     * @param planner original source-bound planner / 原来源绑定规划器 */
    DeploymentStandardBuildExecutor(DeploymentToolchainCatalog catalog, DeploymentStandardPlanner planner) {
        this.catalog = Objects.requireNonNull(catalog);
        this.planner = Objects.requireNonNull(planner);
    }

    /** Prepares and builds only a previously uploaded candidate, borrowing the caller's original session and source handles. / 仅准备并构建此前已上传候选，借用调用方的原会话和来源句柄。
     * @param plan original reviewed single-component plan / 原已审阅单组件计划
     * @param uploaded original candidate already uploaded and confirmed by its owning flow / 已由所属流程上传并确认的原候选
     * @param session original authenticated connection, closed by its caller / 原已认证连接，由调用方关闭
     * @param interaction real host review capability / 真实宿主审核能力
     * @param task original admitted task / 原准入任务
     * @return exact archive and installed tool identities only after a confirmed build / 仅确认构建后返回精确归档及已安装工具身份
     * @throws ApiException if the host rejects admission before dispatch / 宿主发送前拒绝准入时 */
    public OperationResult<DeploymentVerifiedBuildResult> build(ReviewedDeploymentPlan plan,
            DeploymentRemoteWorkspace uploaded, RemoteSession session, InteractionService interaction, TaskContext task)
            throws ApiException {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(uploaded);
        Objects.requireNonNull(session);
        Objects.requireNonNull(interaction);
        Objects.requireNonNull(task);
        var request = plan.request();
        var facts = request.facts();
        var runtime = request.runtime();
        var limits = request.limits();
        if (!uploaded.equals(new DeploymentRemoteWorkspace(facts.applicationId(), request.archive().sha256())))
            throw new IllegalArgumentException("Build candidate or configuration differs from the reviewed source");
        var buildValues = new LinkedHashMap<String, String>();
        for (var entry : request.configuration().entries()) {
            if (entry.scope() == DeploymentConfigurationScopeType.BUILD)
                buildValues.put(entry.key(), entry.value().canonicalValue());
        }
        var environment = new DeploymentBuildEnvironment(request.configuration().applicationId(), buildValues);
        var rendered = planner.render(plan, task);
        if (rendered.outcome() != Outcome.SUCCEEDED)
            return withoutValue(rendered);
        if (task.cancellation().requested() || Thread.currentThread().isInterrupted())
            return cancelled();
        String revision = rendered.value().orElseThrow().reviewedPlanSha256();
        String engine = runtime instanceof DeploymentRuntimeDefinition.Container container
                ? container.engine().name().toLowerCase(java.util.Locale.ROOT)
                : "ordinary";
        var budget = new DeploymentBuildBudget(limits.timeoutSeconds());
        var commands = new DeploymentBuildCommandChannel(session, interaction, request.server(), task, revision,
                budget);
        var preflight = commands.preflight(engine);
        if (preflight.outcome() == Outcome.UNKNOWN || preflight.outcome() == Outcome.CANCELLED)
            return withoutValue(preflight);
        if (preflight.outcome() == Outcome.FAILED && preflight.value().isEmpty())
            return withoutValue(preflight);
        if (!knownSuccess(preflight))
            return preflight.outcome() == Outcome.FAILED && knownExit(preflight)
                    ? failed(ProblemCode.INCOMPATIBLE, "DEPLOYMENT_BUILD_ISOLATION_UNAVAILABLE")
                    : unknown("DEPLOYMENT_BUILD_PREFLIGHT_UNCONFIRMED");
        List<ToolchainRequirement> requirements = DeploymentProjectToolchainRequirements.from(facts, runtime);
        var preparer = new DeploymentToolchainPreparer(commands, catalog);
        int attempt = 0;
        OperationResult<DeploymentResolvedToolchains> prepared = preparer.prepare(requirements, attempt,
                (int) budget.remainingSeconds());
        if (prepared.outcome() == Outcome.FAILED
                && prepared.problem().orElseThrow().eventCode().equals("DEPLOYMENT_TOOLCHAIN_MINIMUM")
                && hasAlternative(requirements)) {
            attempt = 1;
            prepared = preparer.prepare(requirements, attempt, (int) budget.remainingSeconds());
        }
        if (prepared.outcome() != Outcome.SUCCEEDED)
            return withoutValue(prepared);
        var tools = prepared.value().orElseThrow();
        var built = execute(rendered.value().orElseThrow().buildScript(), request, uploaded, environment, commands,
                budget, engine, tools);
        if (built.outcome() == Outcome.FAILED
                && built.problem().orElseThrow().eventCode().equals("DEPLOYMENT_BUILD_COMPATIBLE_FAILURE")
                && attempt == 0 && hasAlternative(requirements) && budget.remainingSeconds() > 60) {
            prepared = preparer.prepare(requirements, 1, (int) budget.remainingSeconds());
            if (prepared.outcome() != Outcome.SUCCEEDED)
                return withoutValue(prepared);
            built = execute(rendered.value().orElseThrow().buildScript(), request, uploaded, environment, commands,
                    budget, engine, prepared.value().orElseThrow());
        }
        if (built.outcome() == Outcome.FAILED
                && built.problem().orElseThrow().eventCode().equals("DEPLOYMENT_BUILD_COMPATIBLE_FAILURE")) {
            var original = built.problem().orElseThrow();
            return new OperationResult<>(Outcome.FAILED, Optional.empty(),
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
            gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentRequest request,
            DeploymentRemoteWorkspace workspace, DeploymentBuildEnvironment environment,
            DeploymentBuildCommandChannel commands, DeploymentBuildBudget budget, String engine,
            DeploymentResolvedToolchains tools) throws ApiException {
        long remaining = budget.remainingSeconds();
        if (remaining <= 15)
            return failed(ProblemCode.TIMEOUT, "DEPLOYMENT_BUILD_BUDGET_EXHAUSTED");
        String script = DeploymentBuildEnvironmentRenderer.render(environment) + ToolchainBuildEnvironment.render(tools)
                + base + CompanionBuildScript.render(request.runtime().workload())
                + (request.runtime().workload().companions().isEmpty()
                        ? ""
                        : "\ntest \"$(du -sb \"$mutable\" | cut -f1)\" -le " + request.limits().maxWorkspaceBytes()
                                + "\n");
        var observed = commands.build(workspace, request.limits(), remaining - 15, engine, script,
                BuildCompatibilityPolicy.groups());
        if (observed.outcome() == Outcome.UNKNOWN || observed.outcome() == Outcome.CANCELLED)
            return withoutValue(observed);
        if (observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
            return withoutValue(observed);
        if (!knownSuccess(observed))
            return observed.outcome() == Outcome.FAILED && knownExit(observed)
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
        if (receipt.exit() != 0)
            return failedBuild(receipt,
                    receipt.compatible() && receipt.limit().equals("-")
                            ? "DEPLOYMENT_BUILD_COMPATIBLE_FAILURE"
                            : receipt.limit().equals("-") ? "DEPLOYMENT_BUILD_FAILED" : "DEPLOYMENT_BUILD_LIMIT");
        if (!receipt.limit().equals("-") || !receipt.uid().matches("[1-9][0-9]{0,9}")
                || !engine.equals(receipt.engine()) || !request.facts().buildTool().name().equals(receipt.tool()))
            return unknown("DEPLOYMENT_BUILD_IDENTITY_UNCONFIRMED");
        var result = new DeploymentVerifiedBuildResult(workspace.archiveSha256(), workspace.candidateId(), tools,
                DeploymentToolchainBindingCodec.identity(tools));
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(result), Optional.empty());
    }

    /** Reports a complete successful host command without interpreting author text. / 不解释作者文本，仅报告完整成功的宿主命令。
     * @param result original host observation / 原宿主观测
     * @return true only for known exit zero and no truncation / 仅已知退出零且未截断时为真 */
    private static boolean knownSuccess(OperationResult<CommandResult> result) {
        return result.outcome() == Outcome.SUCCEEDED && result.value().isPresent()
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

    /** Returns a known failure. / 返回已知失败。
     * @param code public category / 公开分类
     * @param event stable event / 稳定事件
     * @return failed operation / 失败操作 */
    private static OperationResult<DeploymentVerifiedBuildResult> failed(ProblemCode code, String event) {
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(problem(code, event)));
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
                "outputSha256", receipt.digest(), "diagnostic", diagnostic);
        return new OperationResult<>(Outcome.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
                event, "Reviewed build failed: " + diagnostic, context, Optional.empty())));
    }

    /** Retains uncertain remote effects. / 保留未知远端副作用。
     * @param event stable event / 稳定事件
     * @return unknown operation / 未知操作 */
    private static OperationResult<DeploymentVerifiedBuildResult> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
                Optional.of(problem(ProblemCode.UNKNOWN, event)));
    }

    /** Returns a confirmed cancellation before remote dispatch. / 返回远端发送前已确认取消。
     * @return cancelled build / 已取消构建 */
    private static OperationResult<DeploymentVerifiedBuildResult> cancelled() {
        return new OperationResult<>(Outcome.CANCELLED, Optional.empty(),
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
