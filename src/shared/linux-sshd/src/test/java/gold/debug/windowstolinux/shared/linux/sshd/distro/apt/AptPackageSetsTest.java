package gold.debug.windowstolinux.shared.linux.sshd.distro.apt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AptPackageSetsTest {
    @Test
    void keepsDebianFamilyPackageSetsExact() {
        assertEquals(List.of(
                "openjdk-21-jdk-headless", "maven", "curl", "sudo", "tar", "gzip", "iproute2", "coreutils",
                "util-linux", "findutils", "gawk", "nodejs", "npm", "python3", "python3-venv", "python3-pip",
                "cmake", "ninja-build", "gcc", "g++",
                "docker.io", "podman"
        ), AptPackageSets.BASE);
        assertEquals(List.of(
                "golang-go", "rustc", "cargo", "dotnet-sdk-8.0", "kotlin", "php-cli", "composer", "ruby", "ruby-bundler"
        ), AptPackageSets.UBUNTU_2404_EXPERIMENTAL);
    }
}
