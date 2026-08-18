package gold.debug.windowstolinux.shared.linux.sshd.distro;

/** Fixed runtime checks selected only by implementation-owned distribution adapters. / 仅由实现持有的发行版适配器选择的固定运行时检查。 */
public enum EcosystemCapabilityChecks {
    /** Represents the {@code UBUNTU_2204} value. / 表示 {@code UBUNTU_2204} 值。 */
    UBUNTU_2204,
    /** Represents the {@code UBUNTU_2404} value. / 表示 {@code UBUNTU_2404} 值。 */
    UBUNTU_2404,
    /** Represents the {@code DEBIAN_13} value. / 表示 {@code DEBIAN_13} 值。 */
    DEBIAN_13,
    /** Represents the {@code ENTERPRISE_9} value. / 表示 {@code ENTERPRISE_9} 值。 */
    ENTERPRISE_9,
    /** Represents the {@code ENTERPRISE_10} value. / 表示 {@code ENTERPRISE_10} 值。 */
    ENTERPRISE_10;

    /** Performs the {@code pythonCommand} operation. / 执行 {@code pythonCommand} 操作。 */
    public String pythonCommand() {
        return switch (this) {
            case UBUNTU_2204 -> "python3.10";
            case UBUNTU_2404 -> "python3.12";
            case DEBIAN_13 -> "python3.13";
            case ENTERPRISE_9 -> "python3.11";
            case ENTERPRISE_10 -> "python3.12";
        };
    }

    /** Performs the {@code renderChecks} operation. / 执行 {@code renderChecks} 操作。 */
    public String renderChecks() {
        String common = """
                prepare_check=node-command
                command -v node >/dev/null 2>&1
                prepare_check=npm-command
                command -v npm >/dev/null 2>&1
                prepare_check=%s-command
                command -v %s >/dev/null 2>&1
                prepare_check=%s-venv
                %s -m venv --help >/dev/null 2>&1
                """.formatted(pythonCommand(), pythonCommand(), pythonCommand(), pythonCommand());
        if (this != UBUNTU_2404) {
            return common;
        }
        return common + """
                prepare_check=node-version
                node --version | grep -Eq '^v18[.]'
                prepare_check=go-version
                go version | grep -Eq '^go version go1[.]2[2-4]([.][0-9]+)? '
                prepare_check=rustc-version
                rustc --version | grep -Eq '^rustc 1[.](7[5-9]|8[0-9]|9[0-9])([.][0-9]+)? '
                prepare_check=cargo-version
                cargo --version >/dev/null
                prepare_check=dotnet-version
                dotnet --version | grep -Eq '^(8|9)[.]0([.][0-9]+)?$'
                prepare_check=php-version
                php -r 'exit(PHP_MAJOR_VERSION === 8 && PHP_MINOR_VERSION >= 2 && PHP_MINOR_VERSION <= 4 ? 0 : 1);'
                prepare_check=composer-version
                composer --version >/dev/null
                prepare_check=ruby-version
                ruby -e 'exit(RUBY_VERSION.match?(/^3[.][234]([.][0-9]+)?$/) ? 0 : 1)'
                prepare_check=bundle-version
                bundle --version >/dev/null
                """;
    }
}
