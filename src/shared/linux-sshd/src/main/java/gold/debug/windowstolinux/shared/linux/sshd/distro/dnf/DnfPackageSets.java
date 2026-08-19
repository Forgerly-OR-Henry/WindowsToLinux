package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import java.util.ArrayList;
import java.util.List;

/** Fixed package names owned by DNF distribution profiles. / 由 DNF 发行版配置持有的固定软件包名称。 */
final class DnfPackageSets {
    private static final List<String> BASE = List.of(
            "java-21-openjdk-headless", "maven", "curl", "sudo", "tar", "gzip", "iproute", "coreutils",
            "util-linux", "findutils", "gawk", "nodejs", "npm", "podman"
    );

    private DnfPackageSets() {
    }

    static List<String> enterprise(String major) {
        if (!("9".equals(major) || "10".equals(major))) {
            throw new IllegalArgumentException("enterprise package profile supports only major 9 or 10");
        }
        List<String> packages = new ArrayList<>(BASE);
        String python = "9".equals(major) ? "python3.11" : "python3.12";
        packages.add(python);
        packages.add(python + "-pip");
        return List.copyOf(packages);
    }
}
