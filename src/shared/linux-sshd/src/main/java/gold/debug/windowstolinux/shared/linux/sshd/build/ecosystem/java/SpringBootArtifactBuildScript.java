package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java;

/**
 * Shares only the invariant executable Spring Boot JAR verification. / 仅共享不变的 Spring Boot 可执行 JAR 验证。
 */
final class SpringBootArtifactBuildScript {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SpringBootArtifactBuildScript() { }

    /**
     * Verifies spring boot artifact build script as text without executing the rendered command.
     * <p>验证Spring启动制品构建脚本为文本，不执行所渲染命令。
     *
     * @param outputDirectory output directory / 输出目录
     * @return verify text / 验证文本
     */
    static String verify(String outputDirectory) {
        return """
                mapfile -t artifacts < <(find %s -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -printf '%%p\n' | LC_ALL=C sort)
                test "${#artifacts[@]}" -eq 1
                artifact="$(readlink -f -- "${artifacts[0]}")"
                manifest="$mutable/artifact-manifest"
                rm -rf -- "$manifest"
                mkdir -p -- "$manifest"
                (cd "$manifest" && jar xf "$artifact" META-INF/MANIFEST.MF)
                tr -d '\\r' < "$manifest/META-INF/MANIFEST.MF" | grep -Eq '^Main-Class: org\\.springframework\\.boot\\.loader\\.(launch\\.)?JarLauncher$'
                printf 'ARTIFACT=%%s\n' "$artifact"
                """.formatted(outputDirectory);
    }
}
