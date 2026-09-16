package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.capability.ecosystem.EcosystemCapabilityScriptRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.generation.script.SetupScriptRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.error.LinuxOperationFailureType;
import gold.debug.windowstolinux.shared.model.capability.LinuxCapabilityFacts;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityModuleType;
import gold.debug.windowstolinux.shared.model.server.security.LinuxSecurityState;

/** Fixed DNF preparation mechanics shared without sharing distribution identity rules. / 在不共享发行版身份规则的情况下复用的固定 DNF 准备机械流程。 */
public final class DnfSetupRenderer {
    private DnfSetupRenderer() {
    }

    /** Requires the shared enterprise security prerequisite. / 核验企业发行版共用的安全前置条件。 */
    static void requireEnterpriseSecurity(LinuxCapabilityFacts capabilities) throws LinuxOperationException {
        if (capabilities.securityPosture().module() != LinuxSecurityModuleType.SELINUX
                || capabilities.securityPosture().state() != LinuxSecurityState.ENFORCING) {
            throw LinuxOperationException.create(LinuxOperationFailureType.ENVIRONMENT_UNSUPPORTED_DISTRO,
                    "Enterprise Linux automatic preparation requires collected SELinux enforcing evidence");
        }
    }

    /** Renders the controlled output. / 渲染受控输出。 */
    public static String render(DistributionSetupProfile profile, String username) {
        return render(profile, username, false);
    }

    /** Uses the configured CRB repository for this transaction only. / 仅在当前事务使用已配置的 CRB 仓库。 */
    static String renderWithCrb(DistributionSetupProfile profile, String username) {
        return render(profile, username, true);
    }

    private static String render(DistributionSetupProfile profile, String username, boolean withCrb) {
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
                test "$(rpm --eval '%%{_arch}')" = %s
                command -v systemctl >/dev/null 2>&1
                test -x /usr/bin/dnf
                command -v getenforce >/dev/null 2>&1
                test "$(getenforce)" = Enforcing
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
                  /usr/bin/dnf %s-y install %s
                """.formatted(withCrb ? "--enablerepo=crb " : "", packages);
        return preflight
                + "prepare_stage=security-observation\n"
                + SetupScriptRenderer.renderSecurityObservationFunctions()
                + "prepare_stage=package-install\n"
                + install
                + "prepare_stage=post-install-checks\n"
                + "prepare_check=sshd-configuration-and-libraries\n/usr/sbin/sshd -t\n"
                + SetupScriptRenderer.renderJava21RuntimeInstallation()
                + SetupScriptRenderer.renderCommonChecks()
                + EcosystemCapabilityScriptRenderer.render(profile.capabilityChecks())
                + """
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
