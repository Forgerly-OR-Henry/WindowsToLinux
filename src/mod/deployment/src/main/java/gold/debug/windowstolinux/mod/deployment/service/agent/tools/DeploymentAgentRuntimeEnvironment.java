package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.ArrayList;
import java.util.List;

import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigValue;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.ToolchainBuildEnvironment;

/** Projects executed Agent tool bindings into a new runtime configuration. / 将已执行自主工具绑定投影到新运行配置。 */
final class DeploymentAgentRuntimeEnvironment {
    private DeploymentAgentRuntimeEnvironment() {
    }

    /** Adds the canonical bound PATH only when a generic process has no explicit declaration. / 仅为未明确声明 PATH 的通用进程追加绑定路径。
     * @param runtime declared runtime / 已声明运行方式
     * @param entries original configuration / 原配置
     * @param tools current executed binding supplied by the caller / 调用方提供的当前已执行绑定
     * @return original entries or an immutable augmented copy / 原条目或追加后的不可变副本 */
    static List<DeploymentConfigEntry> entries(DeploymentRuntimeDefinition runtime, List<DeploymentConfigEntry> entries,
                                               DeploymentResolvedToolchains tools) {
        if (!(runtime instanceof DeploymentRuntimeDefinition.ManagedProcess) || tools.selections().isEmpty())
            return entries;
        boolean declared = false;
        for (var entry : entries) {
            if (!entry.key().equals("PATH"))
                continue;
            if (entry.scope() != DeploymentConfigScopeType.RUNTIME || !(entry.value() instanceof DeploymentConfigValue.Text))
                throw new IllegalArgumentException("Agent PATH must be declared as RUNTIME Text; correct the declaration");
            declared = true;
        }
        if (declared)
            return entries;
        if (entries.size() >= 64)
            throw new IllegalArgumentException("Agent runtime configuration cannot exceed 64 entries; declare RUNTIME PATH within the limit");
        var result = new ArrayList<>(entries);
        result.add(new DeploymentConfigEntry("PATH", DeploymentConfigScopeType.RUNTIME,
            new DeploymentConfigValue.Text(ToolchainBuildEnvironment.path(tools))));
        return List.copyOf(result);
    }
}
