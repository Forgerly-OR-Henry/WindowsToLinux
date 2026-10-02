package gold.debug.windowstolinux.mod.deployment.service.standard.deploy.publication;

import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.OperationResult;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.Problem;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.SecretRef;

import gold.debug.windowstolinux.mod.deployment.linux.DeploymentPublicationArguments;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentPublicationChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.standard.deploy.contract.ReviewedDeploymentPlan;

/** Seals one component's reviewed non-secret configuration and transient secret values. / 封存单组件已审阅非秘密配置及瞬时秘密值。 */
final class DeploymentPublicationStaging {
    /** Utility-only fixed staging. / 仅固定暂存工具。 */
    private DeploymentPublicationStaging() {
    }

    /** Seals both configuration formats and each exact saved credential before snapshot or publish. / 快照或发布前封存两种配置格式及各精确已存凭据。
     * @param context original Mod capability / 原Mod能力
     * @param plan open reviewed component / 开放的已审阅组件
     * @param unit exact owner / 精确归属
     * @param credentials exact persisted mapping / 精确持久映射
     * @param channel original borrowed-session command channel / 原借用会话命令通道
     * @param task original task / 原任务
     * @return sealed secret digests or explicit non-success / 已封存秘密摘要或显式非成功
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    static OperationResult<Map<DeploymentSecretReference, String>> stage(ModContext context,
                                                                         ReviewedDeploymentPlan plan, DeploymentUnitIdentity unit,
                                                                         Map<DeploymentSecretReference, SecretRef> credentials, DeploymentPublicationChannel channel,
                                                                         TaskContext task) throws ApiException {
        var request = plan.request();
        var systemd = DeploymentPublicationArguments.configuration(request.configuration(), "systemd");
        var container = DeploymentPublicationArguments.configuration(request.configuration(), "container");
        boolean staged = false;
        try {
            for (String format : java.util.List.of("systemd", "container")) {
                var observed = channel.stageConfiguration(unit, request.configuration().sha256(), format,
                    format.equals("systemd") ? systemd : container);
                if (observed.outcome() != Outcome.SUCCEEDED) {
                    if (!staged && observed.outcome() == Outcome.FAILED && observed.value().isEmpty())
                        return new OperationResult<>(Outcome.FAILED, Optional.empty(), observed.problem());
                    return unknown("DEPLOYMENT_CONFIGURATION_STAGE_UNCONFIRMED");
                }
                staged = true;
            }
        } finally {
            Arrays.fill(systemd, (byte) 0);
            Arrays.fill(container, (byte) 0);
        }
        var digests = new HashMap<DeploymentSecretReference, String>();
        for (var revision : request.secretReferences().stream()
            .sorted(Comparator.comparing(DeploymentSecretReference::identifier)
                .thenComparingLong(DeploymentSecretReference::revision))
            .toList()) {
            var ref = credentials.get(revision);
            var observed = context.secrets().use(ref, ref.purpose(), task,
                plaintext -> channel.stageSecret(unit, revision.identifier(), revision.revision(), plaintext));
            if (observed.outcome() != Outcome.SUCCEEDED)
                return unknown("DEPLOYMENT_SECRET_STAGE_UNCONFIRMED");
            digests.put(revision, observed.value().orElseThrow());
        }
        return new OperationResult<>(Outcome.SUCCEEDED, Optional.of(Map.copyOf(digests)), Optional.empty());
    }

    /** Refuses to erase an unconfirmed remote input effect. / 拒绝抹去未确认远端输入副作用。
     * @param event stable event / 稳定事件
     * @return retained uncertainty / 保留未知 */
    private static OperationResult<Map<DeploymentSecretReference, String>> unknown(String event) {
        return new OperationResult<>(Outcome.UNKNOWN, Optional.empty(),
            Optional.of(new Problem(ProblemCode.UNKNOWN, event,
                "Original configuration or secret staging requires reconciliation", Map.of(),
                Optional.empty())));
    }
}
