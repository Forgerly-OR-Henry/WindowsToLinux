package gold.debug.windowstolinux.shared.standard.deploy.build.ecosystem.kotlin;

import java.util.Set;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectType;
import gold.debug.windowstolinux.shared.model.project.DeploymentRuntimeSpecification;
import gold.debug.windowstolinux.shared.standard.deploy.build.contract.spi.DeploymentBuildRenderer;
import gold.debug.windowstolinux.shared.standard.deploy.build.generation.script.SafeBuildScriptEnvelope;

/**
 * Renders the fixed Kotlin Gradle extension architecture. / 渲染固定 Kotlin Gradle 扩展架构。
 */
public final class KotlinGradleBuildRenderer implements DeploymentBuildRenderer {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectType projectType() {
        return DeploymentProjectType.KOTLIN_SERVICE;
    }

    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override
    public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER);
    }

    /**
     * Renders kotlin gradle build as text without executing the rendered command.
     * <p>渲染KotlinGradle构建为文本，不执行所渲染命令。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeSpecification runtime,
            RemoteWorkspace workspace, BuildLimitConfiguration limits) {
        if (!(runtime instanceof DeploymentRuntimeSpecification.KotlinService)
                || facts.buildTool() != DeploymentBuildToolType.GRADLE_KOTLIN_WRAPPER) {
            throw new IllegalArgumentException("Kotlin Gradle renderer requires reviewed Gradle inputs");
        }
        String command = """
                kotlin_gradle_arguments=()
                if [ -n "${WTL_KOTLIN_VERSION:-}" ]; then
                  kotlin_init="$mutable/home/wtl-kotlin-toolchain.gradle"
                  cat > "$kotlin_init" <<'WTL_KOTLIN_INIT'
                gradle.settingsEvaluated { settings ->
                    settings.pluginManagement.resolutionStrategy.eachPlugin { details ->
                        if (details.requested.id.id == 'org.jetbrains.kotlin.jvm') {
                            details.useVersion(System.getenv('WTL_KOTLIN_VERSION'))
                        }
                    }
                }
                WTL_KOTLIN_INIT
                  kotlin_gradle_arguments=(--init-script "$kotlin_init")
                fi
                export GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=384m'
                command -v java >/dev/null
                command -v curl >/dev/null
                command -v flock >/dev/null
                java -version 2>&1
                test -f ./build.gradle.kts
                test -f ./gradle.lockfile
                test -f ./gradle/wrapper/gradle-wrapper.jar
                test -f ./gradle/wrapper/gradle-wrapper.properties
                test -f ./gradlew
                wrapper_root="$mutable/home/wtl-gradle-wrapper"
                mkdir -p "$wrapper_root/gradle/wrapper"
                sed 's/\\r$//' ./gradlew > "$wrapper_root/gradlew"
                cp -- ./gradle/wrapper/gradle-wrapper.jar "$wrapper_root/gradle/wrapper/gradle-wrapper.jar"
                sed 's/\\r$//' ./gradle/wrapper/gradle-wrapper.properties > "$wrapper_root/gradle/wrapper/gradle-wrapper.properties"
                chmod u+x "$wrapper_root/gradlew"
                wrapper_properties="$wrapper_root/gradle/wrapper/gradle-wrapper.properties"
                mapfile -t distribution_urls < <(sed -n 's/^distributionUrl=//p' "$wrapper_properties")
                [ "${#distribution_urls[@]}" -eq 1 ]
                distribution_url="${distribution_urls[0]//\\\\:/:}"
                case "$distribution_url" in
                  https://services.gradle.org/distributions/*|https://downloads.gradle.org/distributions/*)
                    mapfile -t distribution_sums < <(sed -n 's/^distributionSha256Sum=//p' "$wrapper_properties")
                    [ "${#distribution_sums[@]}" -eq 1 ]
                    printf '%s\n' "${distribution_sums[0]}" | grep -Eq '^[0-9a-f]{64}$'
                    gradle_cache_root="$mutable/home/gradle-distributions"
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
                retry_run 3 "$wrapper_root/gradlew" --project-dir "$source" --no-daemon --version
                run "$wrapper_root/gradlew" --project-dir "$source" --no-daemon installDist "${java_gradle_arguments[@]}" "${kotlin_gradle_arguments[@]}"
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
