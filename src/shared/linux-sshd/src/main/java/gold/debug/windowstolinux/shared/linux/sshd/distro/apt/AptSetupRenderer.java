package gold.debug.windowstolinux.shared.linux.sshd.distro.apt;

import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.capability.ecosystem.EcosystemCapabilityScriptRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.generation.script.SetupScriptRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;

/**
 * Fixed APT preparation mechanics used by independent Ubuntu and Debian adapters. / 独立 Ubuntu 与 Debian 适配器使用的固定 APT 准备机械流程。
 */
public final class AptSetupRenderer {
    /**
     * LOCK TIMEOUT SECONDS.
     * <p>锁超时秒。
     */
    public static final int LOCK_TIMEOUT_SECONDS = 300;

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private AptSetupRenderer() {
    }

    /**
     * Adds only implementation-owned preparation after package installation. / 仅在安装软件包后加入实现自有的准备步骤。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @param username account name used by the reviewed connection / 已审阅连接使用的账户名
     * @param additionalPreparation additional preparation / 额外准备
     * @return render text / 渲染文本
     */
    static String render(DistributionSetupProfile profile, String username, String additionalPreparation) {
        username = SetupScriptRenderer.requireUsername(username);
        String packages = String.join(" ", profile.packages());
        String variantCheck = profile.variant().isEmpty() ? ":"
                : "test \"${VARIANT_ID:-}\" = " + SetupScriptRenderer.quote(profile.variant());
        String preflight = """
                set -euo pipefail
                export LC_ALL=C
                %s
                test -r /etc/os-release
                . /etc/os-release
                test "${ID:-}" = %s
                %s
                test "${VERSION_ID:-}" = %s
                test "$(uname -m)" = x86_64
                test "$(dpkg --print-architecture)" = %s
                command -v systemctl >/dev/null 2>&1
                test -x /usr/bin/apt-get
                %s
                account="$(id -un)"
                test "$account" = %s
                [ "$(id -u)" -eq 0 ] || { printf 'PREPARE_REJECT=root-management-required\\n'; exit 64; }
                elevation=root
                """.formatted(SetupScriptRenderer.renderStageDiagnostics(),
                SetupScriptRenderer.quote(profile.id()), variantCheck,
                SetupScriptRenderer.quote(profile.version()),
                SetupScriptRenderer.quote(profile.packageArchitecture()),
                SetupScriptRenderer.renderCpuCheck(profile.requiredCpu()),
                SetupScriptRenderer.quote(username));
        String install = """
                export DEBIAN_FRONTEND=noninteractive
                export NEEDRESTART_MODE=l
                  /usr/bin/apt-get -o DPkg::Lock::Timeout=%d update
                  /usr/bin/apt-get -o DPkg::Lock::Timeout=%d install -y --no-install-recommends %s
                """.formatted(LOCK_TIMEOUT_SECONDS, LOCK_TIMEOUT_SECONDS, packages);
        return preflight
                + "prepare_stage=security-observation\n"
                + SetupScriptRenderer.renderSecurityObservationFunctions()
                + "prepare_stage=package-install\n"
                + install
                + "prepare_stage=post-install-checks\n"
                + SetupScriptRenderer.renderJava21RuntimeInstallation()
                + additionalPreparation
                + SetupScriptRenderer.renderCommonChecks()
                + EcosystemCapabilityScriptRenderer.render(profile.capabilityChecks())
                + """
                prepare_check=docker-command
                command -v docker >/dev/null 2>&1
                prepare_check=docker-info
                docker info >/dev/null 2>&1
                prepare_check=podman-command
                command -v podman >/dev/null 2>&1
                prepare_check=podman-info
                podman info >/dev/null 2>&1
                """
                + "prepare_stage=helper-installation\n"
                + SetupScriptRenderer.renderHelperInstallation(username)
                + "prepare_stage=security-invariant\n"
                + SetupScriptRenderer.renderSecurityInvariant()
                + """
                printf 'PREPARED_AS=%%s\\n' "$elevation"
                printf 'PACKAGES=%s\\n'
                printf 'HELPER=%s\\n'
                """.formatted(packages,
                gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.PATH);
    }


}
