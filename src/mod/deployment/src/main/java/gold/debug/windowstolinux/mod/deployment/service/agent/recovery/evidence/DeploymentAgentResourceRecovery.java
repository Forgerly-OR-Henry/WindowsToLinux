package gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence;

import java.util.*;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.secret.DeploymentSecretProvisionFlow;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.deploy.database.*;

/** Reconciles only indexed original DB/secret receipts, without running model decisions or mutations. / 只核对已索引原 DB/秘密回执，不执行模型决策或变更。 */
public final class DeploymentAgentResourceRecovery {
    /** Prevents construction / 禁止构造。
     */
    private DeploymentAgentResourceRecovery() {
    }

    /** Settles known original indexed resources, retaining any uncertainty. / 结清确知原索引资源，保留所有未知。
     * @param context original capabilities / 原能力
     * @param task original task / 原任务
     * @param server original server / 原服务器
     * @param events original event stream / 原事件流
     * @param channel original borrowed receipt channel / 原借用回执通道
     * @return all indexed children known / 所有索引子项确知
     * @throws Exception invalid evidence / 无效依据 */
    public static boolean settle(ModContext context, TaskContext task, ServerId server,
                                 List<DeploymentAgentActionLog.Event> events, DeploymentNativeDatabaseChannel channel) throws Exception {
        var settled = new HashSet<String>();
        var plans = events.stream().filter(v -> v.type().equals("AGENT_DATABASE_INDEX")).toList();
        for (var event : plans) {
            if (!event.detail().matches("[0-9a-f]{64}:[0-9a-f]{64}")) return false;
            String binding = event.detail().substring(65);
            var original = DeploymentDatabasePlanActionLog.find(context.tasks(), task, event.action());
            if (original.isEmpty()) {
                settled.add(binding);
                continue;
            }
            var plan = original.orElseThrow();
            if (!plan.facts().serverId().equals(server.id()) || !plan.facts().manifestSha256().equals(event.detail().substring(0, 64)))
                return false;
            for (String address : plan.facts().keys()) {
                var child = DeploymentNativeDatabaseActionLog.find(context.tasks(), task, DeploymentNativeDatabaseActionLog.KEY + "." + address);
                if (child.isPresent()) {
                    var log = child.orElseThrow();
                    if (!log.facts().serverId().equals(server.id())) return false;
                    if (log.facts().phase().equals("DISPATCHED") && (channel == null || channel.status(log).outcome() == TaskResult.UNKNOWN))
                        return false;
                    log.close();
                }
                if (!secret(context, task, server, "deployment-secret-revision." + address, "deployment-database"))
                    return false;
            }
            plan.close();
            settled.add(binding);
        }
        for (var event : events.stream().filter(v -> v.type().equals("AGENT_SECRET_INDEX")).toList()) {
            String[] detail = event.detail().split(":", -1);
            if (detail.length != 2 || !detail[0].matches("[0-9a-f]{64}")
                || !Set.of("deployment-runtime", "deployment-database").contains(detail[1])
                || !secret(context, task, server, event.action(), detail[1])) return false;
            settled.add(detail[0]);
        }
        var pending = new HashMap<String, Integer>();
        for (var event : events) {
            if (event.type().equals("CAPABILITY_EXECUTING")) pending.merge(event.action(), 1, Integer::sum);
            if (Set.of("CAPABILITY_RESULT", "CAPABILITY_NOT_SENT").contains(event.type()))
                pending.computeIfPresent(event.action(), (key, count) -> count == 1 ? null : count - 1);
        }
        var log = new DeploymentAgentActionLog(context.db());
        for (String binding : settled)
            if (pending.containsKey(binding))
                log.event(task, "CAPABILITY_RESULT", binding, "ORIGINAL_RECEIPTS_SETTLED");
        return true;
    }

    /** Reconciles one original purpose-bound credential / 核对一项原用途绑定凭据。
     * @param context Original platform capabilities / 原平台能力
     * @param task Original admitted task / 原已准入任务
     * @param server Exact original server / 精确原服务器
     * @param key Original indexed key / 原索引键
     * @param purpose Accepts only current deployment credential purposes / 只接纳当前部署凭据用途
     * @return checked current result / 已校验当前结果
     * @throws Exception if original evidence or admission is unconfirmed / 原依据或准入未确认时
     */
    private static boolean secret(ModContext context, TaskContext task, ServerId server, String key, String purpose) throws Exception {
        if (!key.matches("deployment-secret-revision\\.[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid original secret index");
        return context.tasks().findCheckpoint(task, key).isEmpty()
            || new DeploymentSecretProvisionFlow(context).reconcileChild(task, key, server, purpose).outcome() != TaskResult.UNKNOWN;
    }
}
