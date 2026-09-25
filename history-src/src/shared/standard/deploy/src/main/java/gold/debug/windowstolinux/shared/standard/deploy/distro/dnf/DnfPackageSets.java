package gold.debug.windowstolinux.shared.standard.deploy.distro.dnf;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed package names owned by DNF distribution profiles. / 由 DNF 发行版配置持有的固定软件包名称。
 */
final class DnfPackageSets {
    /**
     * BASE.
     * <p>基础。
     */
    private static final List<String> BASE = List.of("java-21-openjdk-devel", "maven", "curl", "sudo", "tar", "gzip",
            "iproute", "coreutils", "e2fsprogs", "skopeo", "acl", "sqlite", "shadow-utils", "util-linux", "findutils",
            "gawk", "nodejs", "npm", "cmake", "ninja-build", "gcc", "gcc-c++", "podman");

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private DnfPackageSets() {
    }

    /**
     * Returns the package set for supported enterprise Linux major versions nine or ten.
     * <p>返回受支持企业 Linux 主版本九或十的软件包集合。
     *
     * @param major major / 主版本
     * @return the package set for supported enterprise Linux major versions nine or ten / 受支持企业 Linux 主版本九或十的软件包集合
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    static List<String> enterprise(String major) {
        if (!("9".equals(major) || "10".equals(major))) {
            throw new IllegalArgumentException("enterprise package profile supports only major 9 or 10");
        }
        List<String> packages = new ArrayList<>(BASE);
        if ("9".equals(major)) {
            packages.addAll(List.of("openssh", "openssh-server", "openssh-clients"));
        }
        String python = "9".equals(major) ? "python3.11" : "python3.12";
        packages.add(python);
        packages.add(python + "-pip");
        return List.copyOf(packages);
    }
}
