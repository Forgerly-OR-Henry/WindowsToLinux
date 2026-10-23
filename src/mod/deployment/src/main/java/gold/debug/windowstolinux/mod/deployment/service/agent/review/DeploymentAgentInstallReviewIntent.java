package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;

/** Adds Agent bootstrap targets without changing shared helper intents or granting authority. / 增补 Agent 引导目标，不改变共用 helper 意图或授予权限。 */
public final class DeploymentAgentInstallReviewIntent {
    private DeploymentAgentInstallReviewIntent() {
    }

    /** Describes only the current untracked installer after its owning callback has checked the authentic task. / 仅在所属回调核验真实任务后描述当前非回执式安装器。
     * @param original original single-helper intent / 原单 helper 意图
     * @param request complete original immutable request / 完整原不可变请求
     * @param server original admitted target / 原已准入目标
     * @param revision frozen original model route / 冻结原模型路由
     * @return an exact command with Agent-specific effect targets, never an approval or grant / 带 Agent 专用副作用目标的精确命令，不是批准或授权
     * @throws IllegalArgumentException if the original operation, target, request or installer bytes differ / 原动作、目标、请求或安装字节不一致时 */
    public static ActionIntent upgrade(ActionIntent original, CommandRequest request, ServerId server, String revision) {
        return upgrade(original, request, server, revision, DeploymentRemoteLayout.original());
    }

    public static ActionIntent upgradeFiles(ActionIntent original, CommandRequest request, ServerId server, String revision) {
        return upgradeFiles(original, request, server, revision, DeploymentRemoteLayout.original());
    }

    public static ActionIntent upgrade(ActionIntent original, CommandRequest request, ServerId server, String revision, DeploymentRemoteLayout layout) {
        return upgrade(original, request, server, revision, false, layout);
    }

    public static ActionIntent upgradeFiles(ActionIntent original, CommandRequest request, ServerId server, String revision, DeploymentRemoteLayout layout) {
        return upgrade(original, request, server, revision, true, layout);
    }

    private static ActionIntent upgrade(ActionIntent original, CommandRequest request, ServerId server, String revision, boolean filesOnly, DeploymentRemoteLayout layout) {
        if (original == null || request == null || server == null || revision == null)
            throw new IllegalArgumentException("Original Agent installer binding required");
        String operation = filesOnly ? "deployment.helper.install.files.v1" : "deployment.helper.install.v1";
        var expected = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics(operation,
            gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelper.installationTargets(server, layout)));
        if (!original.equals(expected) || !request.script().equals("exec /bin/bash --noprofile --norc -s"))
            throw new IllegalArgumentException("Agent installer intent differs from its original complete helper installation request");
        byte[] body = request.stdin();
        try {
            String script = filesOnly ? DeploymentHelperInstallerScript.renderFiles(layout) : DeploymentHelperInstallerScript.render(layout);
            if (!Arrays.equals(body, script.getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Agent installer bytes differ from the current complete installer");
        } finally {
            Arrays.fill(body, (byte) 0);
        }
        var targets = new java.util.LinkedHashSet<>(Set.of(
            new ActionTarget(server.id(), "deployment-helper", layout.helperPath()),
            new ActionTarget(server.id(), "directory", layout.paths().helperDirectory()),
            new ActionTarget(server.id(), "file", layout.buildEntry()),
            new ActionTarget(server.id(), "package-manager", "apt-get"),
            new ActionTarget(server.id(), "package-manager", "dnf"),
            new ActionTarget(server.id(), "package-set", "apt-get:" + DeploymentHelperInstallerScript.APT_PACKAGES),
            new ActionTarget(server.id(), "package-set", "dnf:" + DeploymentHelperInstallerScript.DNF_PACKAGES)));
        for (String root : java.util.List.of(layout.paths().applicationsRoot(), layout.paths().stateRoot(), layout.paths().configurationRoot(), layout.paths().dataRoot()))
            targets.add(new ActionTarget(server.id(), "directory", root));
        targets.addAll(Set.of(
            new ActionTarget(server.id(), "file", "/etc/systemd/system/windowstolinux-workspace-recovery.service"),
            new ActionTarget(server.id(), "service-manager", "systemd"),
            new ActionTarget(server.id(), "systemd-service", "windowstolinux-workspace-recovery.service")));
        if (!filesOnly) targets.addAll(Set.of(
            new ActionTarget(server.id(), "file", "/etc/systemd/system/windowstolinux-workspace-recovery.service"),
            new ActionTarget(server.id(), "file", "/etc/systemd/system/multi-user.target.wants/windowstolinux-workspace-recovery.service"),
            new ActionTarget(server.id(), "service-manager", "systemd"),
            new ActionTarget(server.id(), "systemd-service", "windowstolinux-workspace-recovery.service")));
        return original.withSemantics(new ActionSemantics(operation, Set.copyOf(targets)));
    }
}
