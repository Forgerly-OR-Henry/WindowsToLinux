package gold.debug.windowstolinux.shared.model.capability;

import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;
import gold.debug.windowstolinux.shared.model.server.LinuxDistro;
import gold.debug.windowstolinux.shared.model.server.LinuxSecurityPosture;

import java.util.Map;
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
 * @param packageArchitecture observed package architecture / 观测到的软件包架构
 * @param systemdAvailable whether systemd is present / 是否存在 systemd
 * @param dockerAvailable whether the Docker client is present / 是否存在 Docker 客户端
 * @param podmanAvailable whether the Podman client is present / 是否存在 Podman 客户端
 * @param podmanQuadletAvailable whether Quadlet support is present / 是否存在 Quadlet 支持
 * @param javaMajorVersions observed Java major versions / 观察到的 Java 主版本
 * @param nodeMajorVersions observed Node.js major versions / 观察到的 Node.js 主版本
 * @param npmAvailable whether npm is present / 是否存在 npm
 * @param mavenAvailable whether Maven is present / 是否存在 Maven
 * @param pythonVersions observed Python interpreters with venv support / 观察到且支持 venv 的 Python 解释器
 * @param python3Available whether the generic Python 3 executable is available / 通用 Python 3 可执行文件是否可用
 * @param serviceRuntimeVersions exact observed versions for ecosystem service toolchains / 生态服务工具链的精确观测版本
 * @param dockerOperational whether Docker is usable by the authenticated account / 已认证账户能否使用 Docker
 * @param podmanOperational whether Podman is usable by the authenticated account / 已认证账户能否使用 Podman
 * @param cpuMicroarchitecture highest cumulative CPU level confirmed by the runtime linker / 运行时链接器确认的最高累积 CPU 级别
 * @param cpuFlags observed CPU instruction flags / 观测到的 CPU 指令标志
 * @param securityPosture observed mandatory-access-control and firewall state / 观测到的强制访问控制与防火墙状态
 * @param evidence bounded collection evidence / 有界采集证据
 */
public record LinuxCapabilities(
        LinuxDistro distro,
        String version,
        String architecture,
        String packageManager,
        String packageArchitecture,
        boolean systemdAvailable,
        boolean dockerAvailable,
        boolean podmanAvailable,
        boolean podmanQuadletAvailable,
        Set<Integer> javaMajorVersions,
        Set<Integer> nodeMajorVersions,
        boolean npmAvailable,
        boolean mavenAvailable,
        Set<String> pythonVersions,
        boolean python3Available,
        Map<DeploymentProjectType, Set<String>> serviceRuntimeVersions,
        boolean dockerOperational,
        boolean podmanOperational,
        CpuMicroarchitectureLevel cpuMicroarchitecture,
        Set<String> cpuFlags,
        LinuxSecurityPosture securityPosture,
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
        packageArchitecture = fact(packageArchitecture, "packageArchitecture");
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
        java.util.EnumMap<DeploymentProjectType, Set<String>> normalizedService =
                new java.util.EnumMap<>(DeploymentProjectType.class);
        Objects.requireNonNull(serviceRuntimeVersions, "serviceRuntimeVersions").forEach((projectType, versions) -> {
            Objects.requireNonNull(projectType, "service runtime project type");
            Set<String> copied = Set.copyOf(Objects.requireNonNull(versions, "service runtime versions"));
            if (!serviceProjectType(projectType) || copied.stream().anyMatch(candidate -> !validServiceVersion(projectType, candidate))) {
                throw new IllegalArgumentException("service runtime versions must match their bounded project type");
            }
            normalizedService.put(projectType, copied);
        });
        serviceRuntimeVersions = Map.copyOf(normalizedService);
        cpuMicroarchitecture = Objects.requireNonNull(cpuMicroarchitecture, "cpuMicroarchitecture");
        cpuFlags = Set.copyOf(Objects.requireNonNull(cpuFlags, "cpuFlags"));
        if (cpuFlags.stream().anyMatch(flag -> flag == null || !flag.matches("[a-z0-9_.-]{1,64}"))) {
            throw new IllegalArgumentException("cpuFlags must be normalized bounded instruction names");
        }
        securityPosture = Objects.requireNonNull(securityPosture, "securityPosture");
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

    private static boolean serviceProjectType(DeploymentProjectType projectType) {
        return switch (projectType) {
            case GO_SERVICE, RUST_SERVICE, DOTNET_SERVICE, KOTLIN_SERVICE, PHP_SERVICE, RUBY_SERVICE -> true;
            default -> false;
        };
    }

    private static boolean validServiceVersion(DeploymentProjectType projectType, String value) {
        if (value == null) {
            return false;
        }
        return switch (projectType) {
            case GO_SERVICE -> value.matches("1\\.(?:22|23|24)");
            case RUST_SERVICE -> value.matches("1\\.(?:7[5-9]|8[0-9]|9[0-9])(?:\\.[0-9]+)?");
            case DOTNET_SERVICE -> value.matches("(?:8|9)\\.0(?:\\.[0-9]+)?");
            case KOTLIN_SERVICE -> value.equals("21");
            case PHP_SERVICE -> value.matches("8\\.(?:2|3|4)");
            case RUBY_SERVICE -> value.matches("3\\.(?:2|3|4)(?:\\.[0-9]+)?");
            default -> false;
        };
    }
}
