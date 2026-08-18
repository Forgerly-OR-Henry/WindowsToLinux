package gold.debug.windowstolinux.shared.linux.sshd.distro.setup.apt.ubuntu;

import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.apt.AptSetupRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.setup.catalog.DistributionPackageSets;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.EcosystemCapabilityChecks;
import gold.debug.windowstolinux.shared.linux.sshd.distro.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.distro.shell.SetupShellSupport;

import gold.debug.windowstolinux.shared.model.server.CpuMicroarchitectureLevel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Ubuntu-specific identity, version, package, and runtime preparation adapter. / Ubuntu 专用身份、版本、软件包与运行时准备适配器。 */
public final class UbuntuSetup {
    /** Maximum allowed preparation duration. / 允许的最长准备时长。 */
    public static final Duration TIMEOUT = SetupShellSupport.TIMEOUT;
    /** APT lock wait bound. / APT 锁等待上限。 */
    public static final int APT_LOCK_TIMEOUT_SECONDS = AptSetupRenderer.LOCK_TIMEOUT_SECONDS;
    /** Restricted sudo policy path. / 受限 sudo 策略路径。 */
    public static final String SUDOERS_PATH = SetupShellSupport.SUDOERS_PATH;
    /** Fixed Ubuntu baseline packages. / 固定 Ubuntu 基线软件包。 */
    public static final List<String> PACKAGES = DistributionPackageSets.APT_BASE;
    /** Ubuntu 24.04-only experimental toolchains. / 仅限 Ubuntu 24.04 的试验工具链。 */
    public static final List<String> EXPERIMENTAL_LANGUAGE_PACKAGES_2404 =
            DistributionPackageSets.UBUNTU_2404_EXPERIMENTAL;

    private UbuntuSetup() {
    }

    /** Renders the exact restricted sudo rule. / 渲染精确的受限 sudo 规则。 */
    public static String renderSudoers(String username) {
        return SetupShellSupport.renderSudoers(username);
    }

    /** Renders the current Ubuntu LTS preparation profile. / 渲染当前 Ubuntu LTS 准备配置。 */
    public static String renderScript(String username) {
        return renderScript(username, "24.04");
    }

    /** Renders one supported Ubuntu LTS profile. / 渲染一个受支持的 Ubuntu LTS 配置。 */
    public static String renderScript(String username, String version) {
        if (!("22.04".equals(version) || "24.04".equals(version))) {
            throw new IllegalArgumentException("Ubuntu preparation supports only 22.04 or 24.04");
        }
        List<String> packages = new ArrayList<>(PACKAGES);
        if ("24.04".equals(version)) {
            packages.addAll(EXPERIMENTAL_LANGUAGE_PACKAGES_2404);
        }
        EcosystemCapabilityChecks runtime = "24.04".equals(version)
                ? EcosystemCapabilityChecks.UBUNTU_2404 : EcosystemCapabilityChecks.UBUNTU_2204;
        return AptSetupRenderer.render(new DistributionSetupProfile(
                "ubuntu", "", version, "amd64", CpuMicroarchitectureLevel.X86_64_V1, packages, runtime), username);
    }
}
