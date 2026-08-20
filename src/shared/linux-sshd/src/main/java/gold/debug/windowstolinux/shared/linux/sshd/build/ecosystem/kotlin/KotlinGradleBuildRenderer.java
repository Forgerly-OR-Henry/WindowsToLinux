package gold.debug.windowstolinux.shared.linux.sshd.build.ecosystem.kotlin;

import gold.debug.windowstolinux.shared.linux.sshd.build.generation.script.SafeBuildScriptEnvelope;
import gold.debug.windowstolinux.shared.linux.sshd.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;

import java.util.Set;

/** Renders the fixed Kotlin Gradle extension architecture. / 渲染固定 Kotlin Gradle 扩展架构。 */
public final class KotlinGradleBuildRenderer implements DeploymentBuildRenderer {
    @Override public DeploymentProjectType projectType() { return DeploymentProjectType.KOTLIN_SERVICE; }
    @Override public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER);
    }

    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
                         RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.KotlinService)
                || facts.buildTool() != DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER) {
            throw new IllegalArgumentException("Kotlin Gradle renderer requires reviewed Gradle inputs");
        }
        String command = """
                export GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m'
                command -v java >/dev/null
                command -v curl >/dev/null
                command -v flock >/dev/null
                java -version 2>&1 | grep -Eq 'version "21([.]|")'
                test -f ./build.gradle.kts
                test -f ./gradle.lockfile
                test -f ./gradle/wrapper/gradle-wrapper.jar
                test -f ./gradle/wrapper/gradle-wrapper.properties
                test -f ./gradlew
                chmod u+x ./gradlew
                wrapper_properties=./gradle/wrapper/gradle-wrapper.properties
                mapfile -t distribution_urls < <(sed -n 's/^distributionUrl=//p' "$wrapper_properties")
                [ "${#distribution_urls[@]}" -eq 1 ]
                distribution_url="${distribution_urls[0]//\\:/:}"
                case "$distribution_url" in
                  https://services.gradle.org/distributions/*|https://downloads.gradle.org/distributions/*)
                    mapfile -t distribution_sums < <(sed -n 's/^distributionSha256Sum=//p' "$wrapper_properties")
                    [ "${#distribution_sums[@]}" -eq 1 ]
                    printf '%s\n' "${distribution_sums[0]}" | grep -Eq '^[0-9a-f]{64}$'
                    gradle_cache_root=/var/lib/windowstolinux/cache/gradle-distributions
                    install -d -m 0700 -- "$gradle_cache_root"
                    test -d "$gradle_cache_root"
                    test ! -L "$gradle_cache_root"
                    gradle_distribution="$gradle_cache_root/${distribution_sums[0]}.zip"
                    exec 9> "$gradle_distribution.lock"
                    flock 9
                    if [ -f "$gradle_distribution" ] \
                        && ! printf '%s  %s\n' "${distribution_sums[0]}" "$gradle_distribution" \
                          | sha256sum --check --status; then
                      rm -f -- "$gradle_distribution"
                    fi
                    if [ ! -f "$gradle_distribution" ]; then
                      run curl --fail --location --silent --show-error --continue-at - \
                        --connect-timeout 30 --max-time 540 --retry 4 --retry-all-errors \
                        --retry-delay 2 --retry-max-time 540 \
                        --output "$gradle_distribution.partial" "$distribution_url"
                      printf '%s  %s\n' "${distribution_sums[0]}" "$gradle_distribution.partial" \
                        | sha256sum --check --status
                      chmod 0600 -- "$gradle_distribution.partial"
                      mv -- "$gradle_distribution.partial" "$gradle_distribution"
                    fi
                    test -f "$gradle_distribution"
                    test ! -L "$gradle_distribution"
                    printf '%s  %s\n' "${distribution_sums[0]}" "$gradle_distribution" | sha256sum --check --status
                    flock --unlock 9
                    awk '$0 !~ /^distributionUrl=/' "$wrapper_properties" > "$wrapper_properties.local"
                    printf 'distributionUrl=file\\:%s\n' "$gradle_distribution" >> "$wrapper_properties.local"
                    mv -- "$wrapper_properties.local" "$wrapper_properties"
                    ;;
                  *) exit 64 ;;
                esac
                retry_run 3 ./gradlew --no-daemon --version
                run ./gradlew --no-daemon installDist
                mapfile -t distributions < <(find ./build/install -mindepth 1 -maxdepth 1 -type d -print)
                [ "${#distributions[@]}" -eq 1 ]
                test -d "${distributions[0]}/lib"
                test -z "$(find "${distributions[0]}" -xdev -type l -print -quit)"
                mkdir -p ./.w2l/kotlin
                cp -a -- "${distributions[0]}/lib" ./.w2l/kotlin/lib
                test -n "$(find ./.w2l/kotlin/lib -maxdepth 1 -type f -name '*.jar' -print -quit)"
                printf 'ARTIFACT=%s\n' ./.w2l/kotlin/lib
                """;
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits, command);
    }
}
