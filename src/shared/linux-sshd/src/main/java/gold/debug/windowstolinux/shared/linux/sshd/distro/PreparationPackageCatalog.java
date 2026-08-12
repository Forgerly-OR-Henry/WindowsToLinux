package gold.debug.windowstolinux.shared.linux.sshd.distro;

import java.util.ArrayList;
import java.util.List;

/** Fixed package names shared only by implementation-owned adapters. / 仅由实现持有适配器共享的固定软件包名称。 */
final class PreparationPackageCatalog {
    static final List<String> APT_BASE = List.of(
            "openjdk-21-jdk-headless", "maven", "curl", "sudo", "tar", "gzip", "iproute2", "coreutils",
            "util-linux", "findutils", "gawk", "nodejs", "npm", "python3", "python3-venv", "python3-pip", "docker.io"
    );
    static final List<String> UBUNTU_2404_EXPERIMENTAL = List.of(
            "golang-go", "rustc", "cargo", "dotnet-sdk-8.0", "php-cli", "composer", "ruby", "ruby-bundler"
    );
    private static final List<String> DNF_BASE = List.of(
            "java-21-openjdk-headless", "maven", "curl", "sudo", "tar", "gzip", "iproute", "coreutils",
            "util-linux", "findutils", "gawk", "nodejs", "npm", "podman"
    );

    private PreparationPackageCatalog() {
    }

    static List<String> enterprise(String major) {
        if (!("9".equals(major) || "10".equals(major))) {
            throw new IllegalArgumentException("enterprise package profile supports only major 9 or 10");
        }
        List<String> packages = new ArrayList<>(DNF_BASE);
        String python = "9".equals(major) ? "python3.11" : "python3.12";
        packages.add(python);
        packages.add(python + "-pip");
        return List.copyOf(packages);
    }
}
