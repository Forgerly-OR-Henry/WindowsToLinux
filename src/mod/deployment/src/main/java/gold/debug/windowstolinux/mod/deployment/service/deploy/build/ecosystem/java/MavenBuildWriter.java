package gold.debug.windowstolinux.mod.deployment.service.deploy.build.ecosystem.java;

import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.contract.DeploymentBuildWriter;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;

/**
 * Renders reviewed Spring Boot Maven and Maven Wrapper entries. / 渲染经审阅的 Spring Boot Maven 与 Maven Wrapper 入口。
 */
public final class MavenBuildWriter implements DeploymentBuildWriter {
    /**
     * Returns the supported project category handled by this strategy.
     * <p>返回当前策略处理的受支持项目类别。
     *
     * @return the supported project category handled by this strategy / 当前策略处理的受支持项目类别
     */
    @Override
    public DeploymentProjectKind projectType() {
        return DeploymentProjectKind.SPRING_BOOT;
    }

    /**
     * Returns the supported build-tool identifiers recognized by this strategy.
     * <p>返回当前策略识别的受支持构建工具标识。
     *
     * @return the supported build-tool identifiers recognized by this strategy / 当前策略识别的受支持构建工具标识
     */
    @Override
    public Set<DeploymentBuildToolType> buildTools() {
        return Set.of(DeploymentBuildToolType.MAVEN, DeploymentBuildToolType.MAVEN_WRAPPER);
    }

    /**
     * Renders maven build as text without executing the rendered command.
     * <p>渲染Maven构建为文本，不执行所渲染命令。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return render text / 渲染文本
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    @Override
    public String render(DeploymentProjectFacts facts, DeploymentRuntimeDefinition runtime,
                         DeploymentRemoteWorkspace workspace, DeploymentBuildLimits limits) {
        if (!(runtime instanceof DeploymentRuntimeDefinition.SpringBoot) || !buildTools().contains(facts.buildTool())) {
            throw new IllegalArgumentException("Maven renderer requires reviewed Spring Boot Maven inputs");
        }
        String build = switch (facts.buildTool()) {
            case MAVEN_WRAPPER -> """
                test -f ./mvnw
                test -f ./.mvn/wrapper/maven-wrapper.properties
                chmod 700 -- ./mvnw
                run ./mvnw -v
                maven_package ./mvnw -B "${maven_toolchain_arguments[@]}" -DskipTests package
                """;
            case MAVEN -> """
                """ + prepareMaven() + """
                run mvn -v
                maven_package mvn -B "${maven_toolchain_arguments[@]}" -DskipTests package
                """;
            default -> throw new IllegalArgumentException("Maven renderer requires a Maven build identity");
        };
        return SafeBuildScriptEnvelope.wrap(facts, workspace, limits,
            """
                maven_toolchain_arguments=()
                if [ -n "${WTL_JAVA_HOME:-}" ]; then
                  toolchains="$mutable/home/wtl-toolchains.xml"
                  printf '<toolchains><toolchain><type>jdk</type><provides><version>%s</version></provides><configuration><jdkHome>%s</jdkHome></configuration></toolchain></toolchains>\\n' "$WTL_JAVA_BRANCH" "$WTL_JAVA_HOME" > "$toolchains"
                  maven_toolchain_arguments=(--global-toolchains "$toolchains" --toolchains "$toolchains" "-Dmaven.compiler.executable=$WTL_JAVA_HOME/bin/javac" -Dmaven.compiler.fork=true)
                fi
                """
                + downloadRetry() + build + SpringBootArtifactBuildScript.verify("./target"));
    }

    /**
     * Downloads retry.
     * <p>下载重试。
     *
     * @return download retry text / 下载重试文本
     */
    static String downloadRetry() {
        return """
            maven_package() {
              local attempt status log="$mutable/maven-download-attempt.log"
              local -a pipeline_status
              for attempt in 1 2; do
                if run "$@" 2>&1 | tee "$log"; then rm -f -- "$log"; return 0
                else pipeline_status=("${PIPESTATUS[@]}"); fi
                status="${pipeline_status[0]}"
                if [ "${pipeline_status[1]}" -ne 0 ]; then rm -f -- "$log"; return "${pipeline_status[1]}"; fi
                if [ "$attempt" -eq 2 ] || ! grep -Eq '^\\[ERROR\\].*Could not transfer artifact.*(Premature end of Content-Length|Connection reset|Read timed out)' "$log"; then
                  rm -f -- "$log"; return "$status"
                fi
                printf 'BUILD_DOWNLOAD_RETRY=maven:%s/2\\n' "$attempt"
                sleep 2
              done
            }
            """;
    }

    /** Describes only an exact observed product bootstrap fragment; the enclosing script still requires full review. */
    public static java.util.Optional<java.util.Map<String, Object>> bootstrapEvidence(String input) {
        String fragment = prepareMaven();
        if (!input.contains(fragment)) return java.util.Optional.empty();
        var source = java.util.regex.Pattern.compile("https://repo[.]maven[.]apache[.]org/maven2/org/apache/maven/apache-maven/([0-9.]+)/apache-maven-\\1-bin[.]tar[.]gz").matcher(fragment);
        var digest = java.util.regex.Pattern.compile("[a-f0-9]{128}").matcher(fragment);
        if (!source.find() || !digest.find())
            throw new IllegalStateException("Maven bootstrap evidence contract changed");
        return java.util.Optional.of(java.util.Map.of("version", source.group(1), "url", source.group(),
            "algorithm", "SHA512", "expectedArchiveDigest", digest.group(), "candidateLocalHome", "$mutable/home/.wtl-tools/apache-maven-" + source.group(1),
            "meaning", "Exact complete MavenBuildWriter.prepareMaven fragment occurs in supplied executable stdin. Only when mvn is absent from the isolated build PATH: bounded HTTPS curl (32MiB,120s,retry2) downloads the product-pinned Maven archive into candidate mutable/home; SHA512 is verified before tar inspection. It limits 1024 members, permits only the fixed version prefix and safe components, rejects links/special types/duplicate files, then extracts without preserving owner/permissions into candidate-local home and checks bin/mvn. It makes no host-wide installation. This identifies that exact fragment only; separately review the full body's mutable binding, overrides and all other commands. It does not prove a live download, upstream metadata freshness, isolated execution or permission."));
    }

    /** Prepares a pinned Maven only when no executable is accessible to the isolated build identity. / 隔离构建身份无可执行Maven时才准备固定版本。
     * @return bounded candidate-local preparation / 有界候选内准备 */
    static String prepareMaven() {
        return """
            if ! command -v mvn >/dev/null 2>&1; then
              maven_archive="$mutable/home/wtl-maven.tar.gz"
              maven_entries="$mutable/home/wtl-maven-entries.txt"
              maven_home="$mutable/home/.wtl-tools/apache-maven-3.9.16"
              run curl --fail --silent --show-error --proto '=https' --max-time 120 --retry 2 --retry-max-time 180 --max-filesize 33554432 \\
                --output "$maven_archive" 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.tar.gz'
              test "$(stat -c '%s' -- "$maven_archive")" -le 33554432
              printf '%s  %s\\n' '831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6' "$maven_archive" | run sha512sum --check --status
              tar -tzf "$maven_archive" > "$maven_entries"
              test -s "$maven_entries"
              test "$(wc -l < "$maven_entries")" -le 1024
              while IFS= read -r entry; do
                case "$entry" in apache-maven-3.9.16|apache-maven-3.9.16/|apache-maven-3.9.16/*) ;; *) exit 64 ;; esac
                case "$entry" in *//*|*/../*|*/..|*/./*|*/.) exit 64 ;; esac
                [[ "$entry" =~ ^apache-maven-3[.]9[.]16(/[A-Za-z0-9_.+-]+)*/?$ ]] || exit 64
              done < "$maven_entries"
              tar -tvzf "$maven_archive" | awk '
                {
                  kind=substr($0, 1, 1)
                  if (kind != "-" && kind != "d") exit 1
                  path=$NF
                  sub(/\\/$/, "", path)
                  if (path in seen && (kind != "d" || seen[path] != "d")) exit 1
                  seen[path]=kind
                }'
              rm -rf -- "$maven_home"
              mkdir -p -- "$maven_home"
              tar --extract --gzip --file "$maven_archive" --directory "$maven_home" --strip-components=1 --no-same-owner --no-same-permissions
              test -f "$maven_home/bin/mvn" && test ! -L "$maven_home/bin/mvn" && test -x "$maven_home/bin/mvn"
              export PATH="$maven_home/bin:$PATH"
              rm -f -- "$maven_archive" "$maven_entries"
              printf 'BUILD_MANAGER=MAVEN:3.9.16:candidate\\n'
            fi
            """;
    }
}
