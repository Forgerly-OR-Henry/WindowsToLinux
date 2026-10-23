package gold.debug.windowstolinux.mod.deployment.service.deploy.publish;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.secret.model.SecretId;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishArguments;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;

/** Seals one component's reviewed non-secret configuration and transient secret values. / 封存单组件已审阅非秘密配置及瞬时秘密值。 */
public final class DeploymentPublishStaging {
    /** Utility-only fixed staging. / 仅固定暂存工具。 */
    private DeploymentPublishStaging() {
    }

    /** Seals both configuration formats and each exact saved credential before snapshot or publish. / 快照或发布前封存两种配置格式及各精确已存凭据。
     * @param context original Mod capability / 原Mod能力
     * @param configuration original immutable configuration / 原不可变配置
     * @param secretReferences original exact secret revisions / 原精确秘密修订
     * @param unit exact owner / 精确归属
     * @param credentials exact persisted mapping / 精确持久映射
     * @param channel original borrowed-session command channel / 原借用会话命令通道
     * @param task original task / 原任务
     * @return sealed secret digests or explicit non-success / 已封存秘密摘要或显式非成功
     * @throws ApiException if host admission rejects / 宿主准入拒绝时 */
    public static OperationResult<Map<DeploymentSecretRef, String>> stage(ModContext context,
                                                                          DeploymentConfigSnapshot configuration, List<DeploymentSecretRef> secretReferences,
                                                                          DeploymentUnitIdentity unit,
                                                                          Map<DeploymentSecretRef, SecretId> credentials, DeploymentPublishChannel channel,
                                                                          TaskContext task) throws ApiException {
        secretReferences = List.copyOf(secretReferences);
        var systemd = DeploymentPublishArguments.configuration(configuration, "systemd");
        var container = DeploymentPublishArguments.configuration(configuration, "container");
        boolean staged = false;
        try {
            for (String format : java.util.List.of("systemd", "container")) {
                var observed = channel.stageConfiguration(unit, configuration.sha256(), format,
                    format.equals("systemd") ? systemd : container);
                if (observed.outcome() != TaskResult.SUCCEEDED) {
                    if (!staged && observed.outcome() == TaskResult.FAILED && observed.value().isEmpty())
                        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), observed.problem());
                    return unknown("DEPLOYMENT_CONFIGURATION_STAGE_UNCONFIRMED");
                }
                staged = true;
            }
        } finally {
            Arrays.fill(systemd, (byte) 0);
            Arrays.fill(container, (byte) 0);
        }
        var digests = new HashMap<DeploymentSecretRef, String>();
        for (var revision : secretReferences.stream()
            .sorted(Comparator.comparing(DeploymentSecretRef::identifier)
                .thenComparingLong(DeploymentSecretRef::revision))
            .toList()) {
            var ref = credentials.get(revision);
            var observed = context.secrets().use(ref, ref.purpose(), task,
                plaintext -> channel.stageSecret(unit, revision.identifier(), revision.revision(), plaintext));
            if (observed.outcome() != TaskResult.SUCCEEDED)
                return unknown("DEPLOYMENT_SECRET_STAGE_UNCONFIRMED");
            digests.put(revision, observed.value().orElseThrow());
        }
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(Map.copyOf(digests)), Optional.empty());
    }

    /** Refuses to erase an unconfirmed remote input effect. / 拒绝抹去未确认远端输入副作用。
     * @param event stable event / 稳定事件
     * @return retained uncertainty / 保留未知 */
    private static OperationResult<Map<DeploymentSecretRef, String>> unknown(String event) {
        return new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(),
            Optional.of(new Problem(ProblemCode.UNKNOWN, event,
                "Original configuration or secret staging requires reconciliation", Map.of(),
                Optional.empty())));
    }
}
