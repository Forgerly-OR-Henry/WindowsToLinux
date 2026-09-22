package gold.debug.windowstolinux.shared.standard.deploy.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportDecision;
import gold.debug.windowstolinux.shared.standard.deploy.contract.result.compatibility.HostSupportStatus;
import gold.debug.windowstolinux.shared.standard.deploy.support.distro.DistributionSupportEvaluator;
import gold.debug.windowstolinux.shared.standard.deploy.support.runtime.RuntimeCapabilityDecision;
import gold.debug.windowstolinux.shared.standard.deploy.support.runtime.RuntimeCapabilityEvaluator;

/**
 * Coordinates common, distribution, and runtime support checks over collected host facts.
 *
 *  <p>协调对已采集主机事实的公共、发行版与运行时支持检查。
 */
public final class HostSupportEvaluator {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private HostSupportEvaluator() {
    }

    /**
     * Checks the unchanged platform boundary before project-specific installation is allowed. / 允许安装项目专属工具前，检查保持不变的平台边界。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @return constructed or resolved host support decision / 构造或解析得到的主机支持决定
     */
    public static HostSupportDecision evaluatePlatform(LinuxCapabilityFacts capabilities) {
        List<String> evidence = new ArrayList<>();
        if (!capabilities.x86_64())
            return result(HostSupportStatus.UNSUPPORTED, "architecture must be x86_64", evidence);
        if (!capabilities.systemdAvailable())
            return result(HostSupportStatus.UNSUPPORTED, "systemd is required", evidence);
        return new HostSupportDecision(DistributionSupportEvaluator.evaluate(capabilities, evidence), evidence);
    }

    /**
     * Combines distribution support, runtime capabilities and resolved toolchain evidence into a host admission decision.
     * <p>将发行版支持、运行能力及已解析工具链证据组合为主机准入决定。
     *
     * @param capabilities collected host facts / 已采集的主机事实
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime selected typed runtime / 所选类型化运行时
     * @return conservative compatibility outcome / 保守兼容性结果
     */
    public static HostSupportDecision evaluate(LinuxCapabilityFacts capabilities, DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime) {
        return evaluate(capabilities, facts, runtime,
                new gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet("legacy", List.of()));
    }

    /**
     * Combines distribution support, runtime capabilities and resolved toolchain evidence into a host admission decision.
     * <p>将发行版支持、运行能力及已解析工具链证据组合为主机准入决定。
     *
     * @param capabilities observed target tools and runtime capabilities / 目标工具及运行能力观测
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param tools tools / 工具集合
     * @return constructed or resolved host support decision / 构造或解析得到的主机支持决定
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static HostSupportDecision evaluate(LinuxCapabilityFacts capabilities, DeploymentProjectFacts facts,
            DeploymentRuntimeSpecification runtime,
            gold.debug.windowstolinux.shared.model.toolchain.ResolvedToolchainSet tools) {
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
        RuntimeCapabilityDecision runtimeDecision = RuntimeCapabilityEvaluator.evaluate(capabilities, facts, runtime,
                tools);
        if (!runtimeDecision.supported()) {
            return result(HostSupportStatus.UNSUPPORTED, runtimeDecision.detail().orElseThrow(), evidence);
        }
        evidence.add("runtime matrix matches collected host facts only");
        return new HostSupportDecision(HostSupportStatus.READY_FOR_RUNTIME_VALIDATION, evidence);
    }

    /**
     * Builds host support decision from the supplied result inputs.
     * <p>根据所提供结果输入构建主机支持决定。
     *
     * @param support exact support level and validation scope / 精确支持等级与验证范围
     * @param detail detail / 详情
     * @param evidence observations supporting the reported result / 支持所报告结果的观测证据
     * @return host support decision from the supplied result inputs / 根据所提供结果输入构建主机支持决定
     */
    private static HostSupportDecision result(HostSupportStatus support, String detail, List<String> evidence) {
        evidence.add(detail);
        return new HostSupportDecision(support, evidence);
    }
}
