package gold.debug.windowstolinux.shared.linux.sshd.distro;

/** Fixed runtime checks selected only by implementation-owned distribution adapters. / 仅由实现持有的发行版适配器选择的固定运行时检查。 */
enum PreparationRuntimeProfile {
    UBUNTU_2204,
    UBUNTU_2404,
    DEBIAN_13,
    ENTERPRISE_9,
    ENTERPRISE_10;

    String pythonCommand() {
        return switch (this) {
            case UBUNTU_2204 -> "python3.10";
            case UBUNTU_2404 -> "python3.12";
            case DEBIAN_13 -> "python3.13";
            case ENTERPRISE_9 -> "python3.11";
            case ENTERPRISE_10 -> "python3.12";
        };
    }

    String renderChecks() {
        String common = """
                command -v node >/dev/null 2>&1
                command -v npm >/dev/null 2>&1
                command -v %s >/dev/null 2>&1
                %s -m venv --help >/dev/null 2>&1
                """.formatted(pythonCommand(), pythonCommand());
        if (this != UBUNTU_2404) {
            return common;
        }
        return common + """
                node --version | grep -Eq '^v18[.]'
                go version | grep -Eq '^go version go1[.]2[2-4]([.][0-9]+)? '
                rustc --version | grep -Eq '^rustc 1[.](7[5-9]|8[0-9]|9[0-9])([.][0-9]+)? '
                cargo --version >/dev/null
                dotnet --version | grep -Eq '^(8|9)[.]0([.][0-9]+)?$'
                php -r 'exit(PHP_MAJOR_VERSION === 8 && PHP_MINOR_VERSION >= 2 && PHP_MINOR_VERSION <= 4 ? 0 : 1);'
                composer --version >/dev/null
                ruby -e 'exit(RUBY_VERSION.match?(/^3[.][234]([.][0-9]+)?$/) ? 0 : 1)'
                bundle --version >/dev/null
                """;
    }
}
