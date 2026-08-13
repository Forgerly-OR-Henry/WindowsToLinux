package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

/** Renders one of the three fixed Spring Boot build entries and verifies one supported executable JAR. / 渲染三个固定 Spring Boot 构建入口之一，并验证唯一受支持的可执行 JAR。 */
public final class SpringBootBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.SPRING_BOOT; }

    @Override public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                                   RemoteWorkspace workspace, BuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.SpringBoot)) {
            throw new IllegalArgumentException("Spring Boot renderer requires the reviewed Spring Boot runtime");
        }
        BuildEntry entry = switch (facts.buildTool()) {
            case GRADLE_WRAPPER -> new BuildEntry("""
                    test -f ./gradlew
                    test -f ./gradle/wrapper/gradle-wrapper.properties
                    test -f ./gradle/wrapper/gradle-wrapper.jar
                    chmod 700 -- ./gradlew
                    run ./gradlew --no-daemon -x test bootJar
                    """, "./build/libs");
            case MAVEN_WRAPPER -> new BuildEntry("""
                    test -f ./mvnw
                    test -f ./.mvn/wrapper/maven-wrapper.properties
                    chmod 700 -- ./mvnw
                    run ./mvnw -B -DskipTests package
                    """, "./target");
            case MAVEN -> new BuildEntry("""
                    command -v mvn >/dev/null 2>&1
                    run mvn -B -DskipTests package
                    """, "./target");
            default -> throw new IllegalArgumentException("unsupported Spring Boot build tool");
        };
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, entry.command() + """
                mapfile -t artifacts < <(find %s -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -printf '%%p\\n' | LC_ALL=C sort)
                test "${#artifacts[@]}" -eq 1
                artifact="$(readlink -f -- "${artifacts[0]}")"
                manifest="$mutable/artifact-manifest"
                rm -rf -- "$manifest"
                mkdir -p -- "$manifest"
                (cd "$manifest" && jar xf "$artifact" META-INF/MANIFEST.MF)
                tr -d '\\r' < "$manifest/META-INF/MANIFEST.MF" | grep -Eq '^Main-Class: org\\.springframework\\.boot\\.loader\\.(launch\\.)?JarLauncher$'
                printf 'ARTIFACT=%%s\\n' "$artifact"
                """.formatted(entry.outputDirectory()));
    }

    private record BuildEntry(String command, String outputDirectory) { }
}
