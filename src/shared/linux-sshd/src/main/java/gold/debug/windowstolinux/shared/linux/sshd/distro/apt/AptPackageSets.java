package gold.debug.windowstolinux.shared.linux.sshd.distro.apt;

import java.util.List;

/** Fixed package names owned by APT distribution profiles. / 由 APT 发行版配置持有的固定软件包名称。 */
final class AptPackageSets {
    static final List<String> BASE = List.of(
            "openjdk-21-jdk-headless", "maven", "curl", "sudo", "tar", "gzip", "iproute2", "coreutils",
            "e2fsprogs", "skopeo", "acl", "uidmap", "rootlesskit", "slirp4netns", "fuse-overlayfs", "util-linux", "findutils", "gawk", "nodejs", "npm", "python3", "python3-venv", "python3-pip",
            "cmake", "ninja-build", "gcc", "g++", "docker.io", "podman"
    );

    private AptPackageSets() {
    }
}
