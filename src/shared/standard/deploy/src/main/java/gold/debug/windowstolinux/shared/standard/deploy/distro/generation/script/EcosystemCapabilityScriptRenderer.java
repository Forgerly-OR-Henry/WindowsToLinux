package gold.debug.windowstolinux.shared.standard.deploy.distro.generation.script;

import java.util.Objects;

import gold.debug.windowstolinux.shared.standard.deploy.distro.contract.profile.EcosystemCapabilityProfile;

/**
 * Renders the fixed runtime checks selected by a distribution profile. / 渲染发行版配置选择的固定运行时检查。
 */
public final class EcosystemCapabilityScriptRenderer {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private EcosystemCapabilityScriptRenderer() {
    }

    /**
     * Renders capability checks without changing their evidence order. / 渲染能力检查且不改变证据顺序。
     *
     * @param profile connection or provider settings supplied to the operation / 提供给操作的连接或提供者设置
     * @return render text / 渲染文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String render(EcosystemCapabilityProfile profile) {
        profile = Objects.requireNonNull(profile, "profile");
        String python = profile.pythonCommand();
        String common = """
                prepare_check=javac-command
                command -v javac >/dev/null 2>&1
                prepare_check=jar-command
                command -v jar >/dev/null 2>&1
                prepare_check=node-command
                command -v node >/dev/null 2>&1
                prepare_check=npm-command
                command -v npm >/dev/null 2>&1
                prepare_check=%s-command
                command -v %s >/dev/null 2>&1
                prepare_check=%s-venv
                %s -m venv --help >/dev/null 2>&1
                prepare_check=cmake-command
                command -v cmake >/dev/null 2>&1
                prepare_check=ninja-command
                command -v ninja >/dev/null 2>&1
                prepare_check=c-compiler-command
                command -v cc >/dev/null 2>&1
                prepare_check=cpp-compiler-command
                command -v c++ >/dev/null 2>&1
                """.formatted(python, python, python, python);
        if (profile != EcosystemCapabilityProfile.UBUNTU_2404) {
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
