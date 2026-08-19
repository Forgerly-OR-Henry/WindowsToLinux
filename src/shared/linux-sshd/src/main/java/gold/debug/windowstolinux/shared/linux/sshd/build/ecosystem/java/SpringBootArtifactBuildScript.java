package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.java;

/** Shares only the invariant executable Spring Boot JAR verification. / 仅共享不变的 Spring Boot 可执行 JAR 验证。 */
final class SpringBootArtifactBuildScript {
    private SpringBootArtifactBuildScript() { }

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
