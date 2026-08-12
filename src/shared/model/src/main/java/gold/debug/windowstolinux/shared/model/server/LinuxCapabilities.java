package gold.debug.windowstolinux.shared.model.server;

import java.util.Objects;
import java.util.Set;

/**
 * Live, non-secret host facts used only to decide whether a typed deployment plan may be offered.
 *
 * <p>仅用于决定能否提供部署计划的实时、非秘密主机事实。
 *
 * @param distro independently classified distribution family / 独立分类的发行版系列
 * @param version observed distribution version / 观测到的发行版版本
 * @param architecture observed machine architecture / 观测到的机器架构
 * @param packageManager observed package manager / 观测到的包管理器
 * @param systemdAvailable whether systemd is present / 是否存在 systemd
 * @param dockerAvailable whether the Docker client is present / 是否存在 Docker 客户端
 * @param podmanAvailable whether the Podman client is present / 是否存在 Podman 客户端
 * @param podmanQuadletAvailable whether Quadlet support is present / 是否存在 Quadlet 支持
 * @param cpuFlags observed CPU instruction flags / 观测到的 CPU 指令标志
 * @param evidence bounded collection evidence / 有界采集证据
 */
public record LinuxCapabilities(
        LinuxDistro distro,
        String version,
        String architecture,
        String packageManager,
        boolean systemdAvailable,
        boolean dockerAvailable,
        boolean podmanAvailable,
        boolean podmanQuadletAvailable,
        Set<String> cpuFlags,
        String evidence
) {
    /**
     * Creates a {@code LinuxCapabilities} instance.
     *
     * <p>创建 {@code LinuxCapabilities} 实例。
     */
    public LinuxCapabilities {
        distro = Objects.requireNonNull(distro, "distro");
        version = fact(version, "version");
        architecture = fact(architecture, "architecture");
        packageManager = fact(packageManager, "packageManager");
        cpuFlags = Set.copyOf(Objects.requireNonNull(cpuFlags, "cpuFlags"));
        if (cpuFlags.stream().anyMatch(flag -> flag == null || !flag.matches("[a-z0-9_.-]{1,64}"))) {
            throw new IllegalArgumentException("cpuFlags must be normalized bounded instruction names");
        }
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (evidence.length() > 4096 || evidence.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("evidence must be bounded non-secret text");
        }
    }

    /**
     * Checks whether this capability record is an x86-64 host.
     *
     * <p>检查此能力记录是否为 x86-64 主机。
     */
    public boolean x86_64() {
        return architecture.equals("x86_64") || architecture.equals("amd64");
    }

    private static String fact(String value, String name) {
        value = Objects.requireNonNull(value, name).trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isBlank() || value.length() > 64 || !value.matches("[a-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " must be normalized bounded host evidence");
        }
        return value;
    }
}
