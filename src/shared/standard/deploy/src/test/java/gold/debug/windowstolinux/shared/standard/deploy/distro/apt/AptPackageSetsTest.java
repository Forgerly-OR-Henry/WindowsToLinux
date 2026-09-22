package gold.debug.windowstolinux.shared.standard.deploy.distro.apt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class AptPackageSetsTest {
    @Test
    void keepsSharedAptPackageSetExact() {
        assertEquals(
                List.of("openjdk-21-jdk-headless", "maven", "curl", "sudo", "tar", "gzip", "iproute2", "coreutils",
                        "e2fsprogs", "skopeo", "acl", "sqlite3", "uidmap", "rootlesskit", "slirp4netns",
                        "fuse-overlayfs", "util-linux", "findutils", "gawk", "nodejs", "npm", "python3", "python3-venv",
                        "python3-pip", "cmake", "ninja-build", "gcc", "g++", "docker.io", "podman"),
                AptPackageSets.BASE);
    }
}
