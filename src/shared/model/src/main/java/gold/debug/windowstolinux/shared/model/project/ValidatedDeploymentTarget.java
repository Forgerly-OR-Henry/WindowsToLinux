package gold.debug.windowstolinux.shared.model.project;

import java.time.LocalDate;
import java.util.Objects;

import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

/**
 * One exact target combination backed by real product-entrypoint acceptance evidence.
 *
 *  <p>由真实产品入口验收证据支撑的一个精确目标组合。
 *
 * @param distro independently classified distribution family / 独立分类的发行版系列
 * @param version exact distribution version / 精确发行版版本
 * @param architecture exact architecture / 精确架构
 * @param validatedOn date of the real acceptance / 真实验收日期
 */
public record ValidatedDeploymentTarget(LinuxDistroType distro, String version, String architecture,
        LocalDate validatedOn) {
    /**
     * Validates a non-secret exact target identity. / 验证非秘密的精确目标身份。
     *
     * @param distro independently classified distribution family / 独立分类的发行版系列
     * @param version version of the relevant protocol, configuration or runtime / 相应协议、配置或运行时的版本
     * @param architecture exact architecture / 精确架构
     * @param validatedOn date of the real acceptance / 真实验收日期
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public ValidatedDeploymentTarget {
        distro = Objects.requireNonNull(distro, "distro");
        version = fact(version, "version");
        architecture = fact(architecture, "architecture");
        validatedOn = Objects.requireNonNull(validatedOn, "validatedOn");
    }

    /**
     * Checks fact syntax and bounds before returning the admitted content.
     * <p>在返回已准入内容前检查事实语法及边界。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return fact text / 事实文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static String fact(String value, String name) {
        value = Objects.requireNonNull(value, name).trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[a-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException(name + " must be normalized bounded target evidence");
        }
        return value;
    }
}
