package gold.debug.windowstolinux.shared.linux.sshd.distro.dnf;

import gold.debug.windowstolinux.shared.linux.sshd.distro.contract.profile.DistributionSetupProfile;
import gold.debug.windowstolinux.shared.linux.sshd.capability.ecosystem.EcosystemCapabilityScriptRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.distro.generation.script.SetupScriptRenderer;
import gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle;

/** Fixed DNF preparation mechanics shared without sharing distribution identity rules. / 在不共享发行版身份规则的情况下复用的固定 DNF 准备机械流程。 */
public final class DnfSetupRenderer {
    private DnfSetupRenderer() {
    }

    /** Renders the controlled output. / 渲染受控输出。 */
    public static String render(DistributionSetupProfile profile, String username) {
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
                if [ "$(id -u)" -eq 0 ]; then
                  elevation=root
                elif [ -x /usr/bin/sudo ] && /usr/bin/sudo -n true >/dev/null 2>&1; then
                  /usr/bin/sudo -n /usr/bin/dnf --version >/dev/null
                  /usr/bin/sudo -n /usr/sbin/visudo -V >/dev/null
                  /usr/bin/sudo -n /usr/bin/install --version >/dev/null
                  elevation=sudo
                else
                  printf 'PREPARE_REJECT=non-root-requires-existing-noninteractive-sudo\\n'
                  exit 64
                fi
                """.formatted(SetupScriptRenderer.renderStageDiagnostics(),
                SetupScriptRenderer.quote(profile.id()), variantCheck,
                SetupScriptRenderer.quote(profile.version()),
                SetupScriptRenderer.quote(profile.packageArchitecture()),
                SetupScriptRenderer.renderCpuCheck(profile.requiredCpu()),
                SetupScriptRenderer.quote(username));
        String install = """
                if [ "$elevation" = root ]; then
                  /usr/bin/dnf -y install %s
                else
                  /usr/bin/sudo -n /usr/bin/dnf -y install %s
                fi
                """.formatted(packages, packages);
        return preflight
                + "prepare_stage=security-observation\n"
                + SetupScriptRenderer.renderSecurityObservationFunctions()
                + "prepare_stage=package-install\n"
                + install
                + "prepare_stage=post-install-checks\n"
                + SetupScriptRenderer.renderJava21RuntimeInstallation()
                + renderCommonChecks()
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
                printf 'SUDOERS=%s\\n'
                printf 'HELPER=%s\\n'
                """.formatted(packages, SetupScriptRenderer.SUDOERS_PATH,
                gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.PATH);
    }

    private static String renderCommonChecks() {
        return """
                prepare_check=java-command
                test -x %s
                prepare_check=java-version
                java_version="$(%s -version 2>&1)"
                prepare_check=java-21
                printf 'PREPARE_JAVA_VERSION=%%s\\n' "$java_version"
                printf '%%s\\n' "$java_version" | /usr/bin/grep -Eq '(^|[^0-9])21[.]'
                prepare_check=maven-command
                command -v mvn >/dev/null 2>&1
                prepare_check=curl-command
                command -v curl >/dev/null 2>&1
                prepare_check=tar-command
                command -v tar >/dev/null 2>&1
                prepare_check=gzip-command
                command -v gzip >/dev/null 2>&1
                prepare_check=ss-command
                command -v ss >/dev/null 2>&1
                prepare_check=setsid-command
                command -v setsid >/dev/null 2>&1
                prepare_check=timeout-command
                command -v timeout >/dev/null 2>&1
                prepare_check=du-command
                command -v du >/dev/null 2>&1
                """.formatted(SetupScriptRenderer.quote(
                gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.JAVA_RUNTIME_PATH),
                SetupScriptRenderer.quote(
                gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.helper.ManagedHelperBundle.JAVA_RUNTIME_PATH));
    }
}
