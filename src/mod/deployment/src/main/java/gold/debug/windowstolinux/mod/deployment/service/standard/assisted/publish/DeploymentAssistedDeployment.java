package gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish;

import gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources;

import java.util.*;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.ai.model.AiPurpose;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentModelToolLoop;
import gold.debug.windowstolinux.mod.deployment.ai.json.DeploymentJsonFields;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;

/** Assisted tool loop over original shared publication capabilities. / 原共用发布能力上的辅助工具循环。 */
public final class DeploymentAssistedDeployment {
    /** Bundled fixed flow instruction. / 随包固定流程说明。 */
    private static final String POLICY = DeploymentSkillResources.text(DeploymentAssistedDeployment.class, "assisted-deployment.md") + gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelExecutionContract.EXECUTION_CONTRACT;

    /** Binds only the original inputs and capabilities owned by this adapter. / 仅绑定当前适配所属的原输入及能力。 */
    private DeploymentAssistedDeployment() {
    }

    /** Advances the original bounded tool session using verified feedback. / 使用已核实反馈推进原有界工具会话。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param session original session bound to this operation / 当前操作绑定的原session值
     * @param server exact original target server / 精确原目标服务器
     * @param task original admitted task / 原已准入任务
     * @param inspectionPlan original inspection Plan bound to this operation / 当前操作绑定的原inspectionPlan值
     * @param available original available bound to this operation / 当前操作绑定的原available值
     * @param state original state bound to this operation / 当前操作绑定的原state值
     * @param <T> task result value type / 任务结果值类型
     * @return verified terminal tool-loop result / 已核实终态工具循环结果
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    public static <T> OperationResult<T> run(ModContext context, Supplier<RemoteSession> session, ServerId server,
                                             TaskContext task, Supplier<ReviewedDeploymentPlan> inspectionPlan,
                                             Supplier<Map<String, DeploymentModelToolLoop.Tool<T>>> available,
                                             Supplier<Map<String, Object>> state) throws Exception {
        String revision = context.ai().configurationRevision(AiPurpose.LANGUAGE, task);
        String policy = POLICY;
        var loop = new DeploymentModelToolLoop<T>(context, task, revision, policy,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().localToolTurns(),
            () -> {}, (name, output) -> output.replaceAll(
                "(?im)^.*(?:password|passwd|secret|token|api[_-]?key|private[_-]?key).*[:=].*$", "[credential content excluded]"));
        var inspectionBudget = new DeploymentBuildBudget(600);
        var currentTools = new java.util.concurrent.atomic.AtomicReference<List<String>>(List.of());
        try (var budget = gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolBudget.openIndependent(
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().standaloneToolAttempts())) {
            return loop.run(() -> {
                var tools = new LinkedHashMap<>(available.get());
                if (tools.containsKey("finish")) {
                    currentTools.set(tools.keySet().stream().sorted().toList());
                    return tools;
                }
                tools.put("inspect-host", new DeploymentModelToolLoop.Tool<>(
                    DeploymentSkillResources.description(DeploymentAssistedDeployment.class, "assisted-deployment-tools.properties", "inspect-host"), args -> {
                    DeploymentJsonFields.exact(args, Set.of());
                    return inspectHost(context, session.get(), server, task, inspectionPlan.get(), inspectionBudget);
                }));
                tools.put("supplement", new DeploymentModelToolLoop.Tool<>(
                    DeploymentSkillResources.description(DeploymentAssistedDeployment.class, "assisted-deployment-tools.properties", "supplement"),
                    gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolSchemas.object("script", "seconds:integer", "missingCapability", "reason").toString(), args -> {
                    DeploymentJsonFields.exact(args, Set.of("script", "seconds", "missingCapability", "reason"));
                    String gap = DeploymentJsonFields.text(args, "missingCapability", 500);
                    if (tools.containsKey(gap)) throw new IllegalArgumentException("Use the existing standard tool");
                    String reason = DeploymentJsonFields.text(args, "reason", 1400);
                    return DeploymentModelToolLoop.Feedback.observed(DeploymentModelCommandRunner.execute(context, session.get(),
                        server, task, revision, DeploymentJsonFields.text(args, "script", 24000),
                        DeploymentJsonFields.number(args, "seconds", 1, 7200), "Missing capability: " + gap + "; " + reason));
                }));
                currentTools.set(tools.keySet().stream().sorted().toList());
                return tools;
            }, () -> {
                var current = new LinkedHashMap<String, Object>(state.get());
                current.put("availableTools", currentTools.get());
                return current;
            });
        } finally {
            DeploymentModelCommandRunner.releaseKnown(context, task);
        }
    }

    /** Reads fixed host prerequisites through the original reviewed command channel. / 经原已审阅命令通道读取固定主机前置条件。
     * @param context original Mod capabilities / 原 Mod 能力
     * @param session original session bound to this operation / 当前操作绑定的原session值
     * @param server exact original target server / 精确原目标服务器
     * @param task original admitted task / 原已准入任务
     * @param plan original deterministically reviewed build or deployment plan / 原确定性审阅构建或部署计划
     * @param budget maximum model decisions in this original loop / 本原循环模型决策上限
     * @param <T> task result value type / 任务结果值类型
     * @return verified host prerequisite observation / 已核实主机前置观测
     * @throws Exception if original input, admission or evidence validation fails / 原输入、准入或依据校验失败时 */
    private static <T> DeploymentModelToolLoop.Feedback<T> inspectHost(ModContext context, RemoteSession session,
                                                                       ServerId server, TaskContext task,
                                                                       ReviewedDeploymentPlan plan, DeploymentBuildBudget budget)
        throws Exception {
        var rendered = new DeploymentStandardPlanner().render(plan, task, plan.request().layout());
        if (rendered.outcome() != TaskResult.SUCCEEDED)
            return DeploymentModelToolLoop.Feedback.ended(new OperationResult<>(rendered.outcome(), Optional.empty(), rendered.problem()));
        var observed = new DeploymentCandidateChannel(session, context.interaction(), server, task,
            rendered.value().orElseThrow().reviewedPlanSha256(), budget, plan.request().layout()).hostFacts();
        if (observed.outcome() != TaskResult.SUCCEEDED)
            return DeploymentModelToolLoop.Feedback.ended(new OperationResult<>(observed.outcome(), Optional.empty(), observed.problem()));
        var facts = observed.value().orElseThrow();
        return DeploymentModelToolLoop.Feedback.observed("HOST_FACTS " + new ObjectMapper().writeValueAsString(Map.of(
            "distribution", facts.distro().name(), "distributionVersion", facts.version(),
            "architecture", facts.architecture(), "packageManager", facts.packageManager(),
            "systemdAvailable", facts.systemdAvailable(), "systemToolVersions", facts.ecosystemToolVersions())));
    }
}
