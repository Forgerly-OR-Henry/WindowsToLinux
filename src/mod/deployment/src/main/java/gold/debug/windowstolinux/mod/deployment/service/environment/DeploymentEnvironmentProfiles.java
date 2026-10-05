package gold.debug.windowstolinux.mod.deployment.service.environment;

import java.util.List;

/** Original implementation-owned package profiles; callers cannot choose arbitrary packages. / 原实现自有软件包配置，调用者不能选择任意软件包。 */
public final class DeploymentEnvironmentProfiles {
    /** Exact historical APT base packages. / 精确历史 APT 基础包。 */
    private static final List<String> APT = List.of("openjdk-21-jdk-headless", "maven", "curl", "sudo", "tar", "gzip",
        "iproute2", "coreutils", "e2fsprogs", "skopeo", "acl", "sqlite3", "uidmap", "rootlesskit", "slirp4netns",
        "fuse-overlayfs", "util-linux", "findutils", "gawk", "nodejs", "npm", "python3", "python3-venv",
        "python3-pip", "cmake", "ninja-build", "gcc", "g++", "docker.io", "podman");

    /** Exact historical DNF base packages. / 精确历史 DNF 基础包。 */
    private static final List<String> DNF = List.of("java-21-openjdk-devel", "maven", "curl", "sudo", "tar", "gzip",
        "iproute", "coreutils", "e2fsprogs", "skopeo", "acl", "sqlite", "shadow-utils", "util-linux", "findutils",
        "gawk", "nodejs", "npm", "cmake", "ninja-build", "gcc", "gcc-c++", "podman");

    /** Prevents construction. / 禁止实例化。 */
    private DeploymentEnvironmentProfiles() {
    }

    /** Returns the exact fixed package list for the original supported distribution. / 返回原受支持发行版的精确固定软件包集合。
     * @param profile observed distribution and exact version / 已观测发行版及精确版本
     * @return immutable original package list / 不可变原软件包集合 */
    public static List<String> packages(String profile) {
        var values = new java.util.ArrayList<String>();
        switch (profile) {
            case "ubuntu-22.04", "debian-13" -> values.addAll(APT);
            case "ubuntu-24.04" -> {
                values.addAll(APT);
                values.addAll(List.of("golang-go", "rustc", "cargo", "dotnet-sdk-8.0", "php-cli", "composer", "ruby", "ruby-bundler"));
            }
            case "centos-9", "almalinux-9.8", "rocky-9.8", "ol-9.7" -> {
                values.addAll(DNF);
                values.addAll(List.of("openssh", "openssh-server", "openssh-clients", "python3.11", "python3.11-pip"));
            }
            case "centos-10", "almalinux-10.2", "rocky-10.2", "ol-10.2" -> {
                values.addAll(DNF);
                values.addAll(List.of("python3.12", "python3.12-pip"));
            }
            default -> throw new IllegalArgumentException("Unsupported original environment profile");
        }
        return List.copyOf(values);
    }
}
