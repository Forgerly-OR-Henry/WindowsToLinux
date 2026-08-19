package gold.debug.windowstolinux.shared.deploy.support;

import gold.debug.windowstolinux.shared.deploy.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.deploy.result.compatibility.HostSupportDecision;
import gold.debug.windowstolinux.shared.deploy.support.distro.DistributionSupportEvaluator;
import gold.debug.windowstolinux.shared.deploy.support.runtime.RuntimeCapabilityEvaluator;
import gold.debug.windowstolinux.shared.deploy.support.runtime.RuntimeCapabilityDecision;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Coordinates common, distribution, and runtime support checks over collected host facts.
 *
 * <p>协调对已采集主机事实的公共、发行版与运行时支持检查。
 */
public final class HostSupportEvaluator {
    private HostSupportEvaluator() {
    }

    /**
     * Evaluates the selected runtime against live host facts.
     *
     * <p>根据实时主机事实评估所选运行时。
     *
     * @param capabilities collected host facts / 已采集的主机事实
     * @param runtime selected typed runtime / 所选类型化运行时
     * @return conservative compatibility outcome / 保守兼容性结果
     */
    public static HostSupportDecision evaluate(LinuxCapabilityFacts capabilities, DeploymentProjectFacts facts,
                                               DeploymentRuntimeSpecification runtime) {
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
        facts = Objects.requireNonNull(facts, "facts");
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (facts.projectType() != runtime.projectType()) {
            throw new IllegalArgumentException("project facts and runtime must use the same type");
        }
        List<String> evidence = new ArrayList<>();
        if (!capabilities.x86_64()) {
            return result(HostSupportStatus.UNSUPPORTED, "architecture must be x86_64", evidence);
        }
        if (!capabilities.systemdAvailable()) {
            return result(HostSupportStatus.UNSUPPORTED, "systemd is required for managed lifecycle", evidence);
        }
        HostSupportStatus base = DistributionSupportEvaluator.evaluate(capabilities, evidence);
        if (base != HostSupportStatus.READY_FOR_RUNTIME_VALIDATION) {
            return new HostSupportDecision(base, evidence);
        }
        RuntimeCapabilityDecision runtimeDecision = RuntimeCapabilityEvaluator.evaluate(capabilities, facts, runtime);
        if (!runtimeDecision.supported()) {
            return result(HostSupportStatus.UNSUPPORTED, runtimeDecision.detail().orElseThrow(), evidence);
        }
        evidence.add("runtime matrix matches collected host facts only");
        return new HostSupportDecision(HostSupportStatus.READY_FOR_RUNTIME_VALIDATION, evidence);
    }

    private static HostSupportDecision result(HostSupportStatus support, String detail, List<String> evidence) {
        evidence.add(detail);
        return new HostSupportDecision(support, evidence);
    }
}
