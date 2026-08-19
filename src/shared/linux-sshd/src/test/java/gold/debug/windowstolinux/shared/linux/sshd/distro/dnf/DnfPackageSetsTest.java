package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DnfPackageSetsTest {
    @Test
    void keepsEnterpriseLinuxPackageSetsExact() {
        List<String> base = List.of(
                "java-21-openjdk-devel", "maven", "curl", "sudo", "tar", "gzip", "iproute", "coreutils",
                "util-linux", "findutils", "gawk", "nodejs", "npm", "cmake", "ninja-build", "gcc", "gcc-c++", "podman"
        );
        assertEquals(append(base, "python3.11", "python3.11-pip"), DnfPackageSets.enterprise("9"));
        assertEquals(append(base, "python3.12", "python3.12-pip"), DnfPackageSets.enterprise("10"));
        assertThrows(IllegalArgumentException.class, () -> DnfPackageSets.enterprise("11"));
    }

    private static List<String> append(List<String> base, String... additions) {
        return java.util.stream.Stream.concat(base.stream(), List.of(additions).stream()).toList();
    }
}
