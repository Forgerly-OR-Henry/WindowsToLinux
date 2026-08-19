package gold.debug.windowstolinux.shared.model.project;

import gold.debug.windowstolinux.shared.model.server.LinuxDistroType;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One exact target combination backed by real product-entrypoint acceptance evidence.
 *
 * <p>由真实产品入口验收证据支撑的一个精确目标组合。
 *
 * @param distro independently classified distribution family / 独立分类的发行版系列
 * @param version exact distribution version / 精确发行版版本
 * @param architecture exact architecture / 精确架构
 * @param validatedOn date of the real acceptance / 真实验收日期
 */
public record ValidatedDeploymentTarget(
        LinuxDistroType distro,
        String version,
        String architecture,
        LocalDate validatedOn
) {
    /** Validates a non-secret exact target identity. / 验证非秘密的精确目标身份。 */
    public ValidatedDeploymentTarget {
        distro = Objects.requireNonNull(distro, "distro");
        version = fact(version, "version");
        architecture = fact(architecture, "architecture");
        validatedOn = Objects.requireNonNull(validatedOn, "validatedOn");
    }

    private static String fact(String value, String name) {
        value = Objects.requireNonNull(value, name).trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[a-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException(name + " must be normalized bounded target evidence");
        }
        return value;
    }
}
