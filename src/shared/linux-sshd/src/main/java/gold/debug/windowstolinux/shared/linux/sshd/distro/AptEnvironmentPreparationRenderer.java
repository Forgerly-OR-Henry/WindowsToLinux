package gold.debug.windowstolinux.shared.linux.sshd.distro;

/** Fixed APT preparation mechanics used by independent Ubuntu and Debian adapters. / 独立 Ubuntu 与 Debian 适配器使用的固定 APT 准备机械流程。 */
final class AptEnvironmentPreparationRenderer {
    static final int LOCK_TIMEOUT_SECONDS = 300;

    private AptEnvironmentPreparationRenderer() {
    }

    static String render(DistributionPreparationProfile profile, String username) {
        username = EnvironmentPreparationShellSupport.requireUsername(username);
        String packages = String.join(" ", profile.packages());
        String variantCheck = profile.variant().isEmpty() ? ":"
                : "test \"${VARIANT_ID:-}\" = " + EnvironmentPreparationShellSupport.quote(profile.variant());
        String preflight = """
                set -euo pipefail
                export LC_ALL=C
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
                if [ "$(id -u)" -eq 0 ]; then
                  elevation=root
                elif [ -x /usr/bin/sudo ] && /usr/bin/sudo -n true >/dev/null 2>&1; then
                  /usr/bin/sudo -n /usr/bin/apt-get --version >/dev/null
                  /usr/bin/sudo -n /usr/sbin/visudo -V >/dev/null
                  /usr/bin/sudo -n /usr/bin/install --version >/dev/null
                  elevation=sudo
                else
                  printf 'PREPARE_REJECT=non-root-requires-existing-noninteractive-sudo\\n'
                  exit 64
                fi
                """.formatted(EnvironmentPreparationShellSupport.quote(profile.id()), variantCheck,
                EnvironmentPreparationShellSupport.quote(profile.version()),
                EnvironmentPreparationShellSupport.quote(profile.packageArchitecture()),
                EnvironmentPreparationShellSupport.renderCpuCheck(profile.requiredCpu()),
                EnvironmentPreparationShellSupport.quote(username));
        String install = """
                if [ "$elevation" = root ]; then
                  /usr/bin/apt-get -o DPkg::Lock::Timeout=%d update
                  /usr/bin/apt-get -o DPkg::Lock::Timeout=%d install -y --no-install-recommends %s
                else
                  /usr/bin/sudo -n /usr/bin/apt-get -o DPkg::Lock::Timeout=%d update
                  /usr/bin/sudo -n /usr/bin/apt-get -o DPkg::Lock::Timeout=%d install -y --no-install-recommends %s
                fi
                """.formatted(LOCK_TIMEOUT_SECONDS, LOCK_TIMEOUT_SECONDS, packages,
                LOCK_TIMEOUT_SECONDS, LOCK_TIMEOUT_SECONDS, packages);
        return preflight
                + EnvironmentPreparationShellSupport.renderSecurityObservationFunctions()
                + install
                + renderCommonChecks()
                + profile.runtimeProfile().renderChecks()
                + """
                command -v docker >/dev/null 2>&1
                docker info >/dev/null 2>&1
                """
                + EnvironmentPreparationShellSupport.renderHelperInstallation(username)
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
                /usr/bin/java -version 2>&1 | /usr/bin/grep -q '"21\\.'
                command -v mvn >/dev/null 2>&1
                command -v curl >/dev/null 2>&1
                command -v tar >/dev/null 2>&1
                command -v gzip >/dev/null 2>&1
                command -v ss >/dev/null 2>&1
                command -v setsid >/dev/null 2>&1
                command -v timeout >/dev/null 2>&1
                command -v du >/dev/null 2>&1
                """;
    }
}
