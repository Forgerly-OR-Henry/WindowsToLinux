package gold.debug.windowstolinux.shared.linux.sshd.capability.ecosystem;

/** Selects and prepares a verified Kotlin compiler without replacing the system installation. / 选择并准备经过校验的 Kotlin 编译器，不替换系统安装。 */
public final class KotlinCompilerToolchain {
    public static final String VERSION = "2.0.21";
    public static final String SHA256 = "0352c0a45bd22f80f6b26e485cd04da8047baa5de54865281fb9f89a4a7bcf2a";
    public static final String DIRECTORY = "/usr/local/lib/windowstolinux/kotlin-" + VERSION;

    private KotlinCompilerToolchain() { }

    public static String selectionScript() {
        return """
                kotlin_compiler="$(command -v kotlinc || true)"
                if [ -z "$kotlin_compiler" ] || ! "$kotlin_compiler" -version 2>&1 | grep -Eq 'kotlinc-jvm (1[.]9|2[.][0-9]+)[.][0-9]+'; then
                  kotlin_compiler='%s/bin/kotlinc'
                fi
                """.formatted(DIRECTORY);
    }

    public static String installationScript() {
        return selectionScript() + """
                if [ ! -x "$kotlin_compiler" ]; then
                  prepare_check=kotlin-compiler-installation
                  install_managed_kotlin() (
                    set -euo pipefail
                    as_root() { if [ "$elevation" = root ]; then "$@"; else /usr/bin/sudo -n "$@"; fi; }
                    compiler_target='%s'
                    as_root /usr/bin/install -d -o root -g root -m 755 /usr/local/lib/windowstolinux
                    test ! -e "$compiler_target" && test ! -L "$compiler_target"
                    compiler_tmp="$(as_root /usr/bin/mktemp -d /usr/local/lib/windowstolinux/.kotlin-install.XXXXXX)"
                    trap 'as_root /usr/bin/rm -rf -- "$compiler_tmp"' EXIT
                    as_root /usr/bin/curl --fail --location --proto '=https' --proto-redir '=https' \
                      --connect-timeout 20 --max-time 300 --retry 2 --retry-max-time 600 \
                      --output "$compiler_tmp/compiler.zip" \
                      'https://github.com/JetBrains/kotlin/releases/download/v%s/kotlin-compiler-%s.zip'
                    printf '%%s  %%s\\n' '%s' "$compiler_tmp/compiler.zip" | as_root /usr/bin/sha256sum --check --status
                    as_root /usr/bin/python3 -m zipfile -e "$compiler_tmp/compiler.zip" "$compiler_tmp/unpacked"
                    as_root /usr/bin/chmod 755 "$compiler_tmp/unpacked/kotlinc/bin/kotlinc" \
                      "$compiler_tmp/unpacked/kotlinc/bin/kotlinc-jvm" "$compiler_tmp/unpacked/kotlinc/bin/kotlin"
                    as_root /usr/bin/chown -R root:root "$compiler_tmp/unpacked/kotlinc"
                    as_root /usr/bin/mv -T --no-clobber "$compiler_tmp/unpacked/kotlinc" "$compiler_target"
                  )
                  install_managed_kotlin
                fi
                """.formatted(DIRECTORY, VERSION, VERSION, SHA256) + selectionScript();
    }
}
