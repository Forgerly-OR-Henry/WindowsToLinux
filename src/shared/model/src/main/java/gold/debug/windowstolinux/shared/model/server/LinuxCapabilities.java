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
 * @param javaMajorVersions observed Java major versions / 观察到的 Java 主版本
 * @param nodeMajorVersions observed Node.js major versions / 观察到的 Node.js 主版本
 * @param npmAvailable whether npm is present / 是否存在 npm
 * @param pythonVersions observed Python interpreters with venv support / 观察到且支持 venv 的 Python 解释器
 * @param python3Available whether the generic Python 3 executable is available / 通用 Python 3 可执行文件是否可用
 * @param dockerOperational whether Docker is usable by the authenticated account / 已认证账户能否使用 Docker
 * @param podmanOperational whether Podman is usable by the authenticated account / 已认证账户能否使用 Podman
 * @param x86_64V3Available whether the runtime linker reports the cumulative x86-64-v3 level / 运行时链接器是否报告累积 x86-64-v3 级别
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
        Set<Integer> javaMajorVersions,
        Set<Integer> nodeMajorVersions,
        boolean npmAvailable,
        Set<String> pythonVersions,
        boolean python3Available,
        boolean dockerOperational,
        boolean podmanOperational,
        boolean x86_64V3Available,
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
        javaMajorVersions = Set.copyOf(Objects.requireNonNull(javaMajorVersions, "javaMajorVersions"));
        if (javaMajorVersions.stream().anyMatch(major -> major == null || major < 1 || major > 99)) {
            throw new IllegalArgumentException("javaMajorVersions must contain bounded positive majors");
        }
        nodeMajorVersions = Set.copyOf(Objects.requireNonNull(nodeMajorVersions, "nodeMajorVersions"));
        if (nodeMajorVersions.stream().anyMatch(major -> major == null || major < 1 || major > 99)) {
            throw new IllegalArgumentException("nodeMajorVersions must contain bounded positive majors");
        }
        pythonVersions = Set.copyOf(Objects.requireNonNull(pythonVersions, "pythonVersions"));
        if (pythonVersions.stream().anyMatch(minor -> minor == null || !minor.matches("3\\.(?:10|11|12|13)"))) {
            throw new IllegalArgumentException("pythonVersions must contain supported normalized versions");
        }
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
