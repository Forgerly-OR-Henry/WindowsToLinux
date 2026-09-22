package gold.debug.windowstolinux.shared.standard.deploy.distro.dnf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class DnfPackageSetsTest {
    @Test
    void keepsEnterpriseLinuxPackageSetsExact() {
        List<String> base = List.of("java-21-openjdk-devel", "maven", "curl", "sudo", "tar", "gzip", "iproute",
                "coreutils", "e2fsprogs", "skopeo", "acl", "sqlite", "shadow-utils", "util-linux", "findutils", "gawk",
                "nodejs", "npm", "cmake", "ninja-build", "gcc", "gcc-c++", "podman");
        assertEquals(append(base, "openssh", "openssh-server", "openssh-clients", "python3.11", "python3.11-pip"),
                DnfPackageSets.enterprise("9"));
        assertEquals(append(base, "python3.12", "python3.12-pip"), DnfPackageSets.enterprise("10"));
        assertThrows(IllegalArgumentException.class, () -> DnfPackageSets.enterprise("11"));
    }

    private static List<String> append(List<String> base, String... additions) {
        return java.util.stream.Stream.concat(base.stream(), List.of(additions).stream()).toList();
    }
}
