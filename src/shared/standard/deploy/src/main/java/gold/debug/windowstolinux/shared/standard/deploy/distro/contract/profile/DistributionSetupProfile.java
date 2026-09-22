package gold.debug.windowstolinux.shared.standard.deploy.distro.contract.profile;

import java.util.List;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

/**
 * Immutable, injection-resistant facts owned by one distribution adapter. / 单个发行版适配器持有的不可变、防注入事实。
 *
 * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
 * @param variant variant / 变体
 * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
 * @param packageArchitecture observed package architecture / 观测到的软件包架构
 * @param requiredCpu required cpu / 必需Cpu
 * @param packages packages / 软件包集合
 * @param capabilityChecks capability checks / 能力检查集合
 */
public record DistributionSetupProfile(String id, String variant, String version, String packageArchitecture,
        CpuMicroarchitectureLevel requiredCpu, List<String> packages, EcosystemCapabilityProfile capabilityChecks) {
    /**
     * Creates an instance of this type. / 创建此类型的实例。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param variant variant / 变体
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param packageArchitecture observed package architecture / 观测到的软件包架构
     * @param requiredCpu required cpu / 必需Cpu
     * @param packages packages / 软件包集合
     * @param capabilityChecks capability checks / 能力检查集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public DistributionSetupProfile {
        id = token(id, "id");
        variant = Objects.requireNonNull(variant, "variant");
        if (!variant.isEmpty()) {
            variant = token(variant, "variant");
        }
        version = token(version, "version");
        packageArchitecture = token(packageArchitecture, "packageArchitecture");
        requiredCpu = Objects.requireNonNull(requiredCpu, "requiredCpu");
        packages = List.copyOf(Objects.requireNonNull(packages, "packages"));
        if (packages.isEmpty()
                || packages.stream().anyMatch(item -> item == null || !item.matches("[a-zA-Z0-9_.+:-]{1,80}"))) {
            throw new IllegalArgumentException("packages must contain only fixed package names");
        }
        capabilityChecks = Objects.requireNonNull(capabilityChecks, "capabilityChecks");
    }

    /**
     * Checks token syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查令牌语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return token text / 令牌文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String token(String value, String name) {
        value = Objects.requireNonNull(value, name).trim();
        if (!value.matches("[a-zA-Z0-9._-]{1,64}")) {
            throw new IllegalArgumentException(name + " must be a bounded distribution fact");
        }
        return value;
    }
}
