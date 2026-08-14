package gold.debug.windowstolinux.shared.linux.sshd.distro;

/** Fixed DNF preparation mechanics shared without sharing distribution identity rules. / 在不共享发行版身份规则的情况下复用的固定 DNF 准备机械流程。 */
final class DnfEnvironmentPreparationRenderer {
    private DnfEnvironmentPreparationRenderer() {
    }

    static String render(DistributionPreparationProfile profile, String username) {
        username = EnvironmentPreparationShellSupport.requireUsername(username);
        String packages = String.join(" ", profile.packages());
        String variantCheck = profile.variant().isEmpty() ? ":"
                : "test \"${VARIANT_ID:-}\" = " + EnvironmentPreparationShellSupport.quote(profile.variant());
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
                """.formatted(EnvironmentPreparationShellSupport.renderStageDiagnostics(),
                EnvironmentPreparationShellSupport.quote(profile.id()), variantCheck,
                EnvironmentPreparationShellSupport.quote(profile.version()),
                EnvironmentPreparationShellSupport.quote(profile.packageArchitecture()),
                EnvironmentPreparationShellSupport.renderCpuCheck(profile.requiredCpu()),
                EnvironmentPreparationShellSupport.quote(username));
        String install = """
                if [ "$elevation" = root ]; then
                  /usr/bin/dnf -y install %s
                else
                  /usr/bin/sudo -n /usr/bin/dnf -y install %s
                fi
                """.formatted(packages, packages);
        return preflight
                + "prepare_stage=security-observation\n"
                + EnvironmentPreparationShellSupport.renderSecurityObservationFunctions()
                + "prepare_stage=package-install\n"
                + install
                + "prepare_stage=post-install-checks\n"
                + EnvironmentPreparationShellSupport.renderJava21RuntimeInstallation()
                + renderCommonChecks()
                + profile.runtimeProfile().renderChecks()
                + """
                prepare_check=podman-command
                command -v podman >/dev/null 2>&1
                prepare_check=podman-info
                podman info >/dev/null 2>&1
                """
                + "prepare_stage=helper-installation\n"
                + EnvironmentPreparationShellSupport.renderHelperInstallation(username)
                + "prepare_stage=security-invariant\n"
                + EnvironmentPreparationShellSupport.renderSecurityInvariant()
                + """
                printf 'PREPARED_AS=%%s\\n' "$elevation"
                printf 'PACKAGES=%s\\n'
                printf 'SUDOERS=%s\\n'
                printf 'HELPER=%s\\n'
                """.formatted(packages, EnvironmentPreparationShellSupport.SUDOERS_PATH,
                gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedHelperBundle.PATH);
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
                """.formatted(EnvironmentPreparationShellSupport.quote(
                gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedHelperBundle.JAVA_RUNTIME_PATH),
                EnvironmentPreparationShellSupport.quote(
                gold.debug.windowstolinux.shared.linux.sshd.protocol.ManagedHelperBundle.JAVA_RUNTIME_PATH));
    }
}
