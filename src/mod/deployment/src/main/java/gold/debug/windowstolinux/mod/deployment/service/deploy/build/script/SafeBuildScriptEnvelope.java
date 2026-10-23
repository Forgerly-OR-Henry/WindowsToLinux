package gold.debug.windowstolinux.mod.deployment.service.deploy.build.script;

import java.util.Objects;
import java.util.Set;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;

/**
 * Owns archive validation, extraction, limits, logging, and build-tool attestation shared by all renderers. / 持有全部渲染器共享的归档校验、解压、限制、日志与构建工具证明。
 */
public final class SafeBuildScriptEnvelope {
    /** Fixed identifiers used by current build recipes, never arbitrary executable paths. / 当前构建配方的固定标识，不含任意程序路径。 */
    private static final Set<String> DIAGNOSTIC_TOOLS = Set.of("java", "javac", "jar", "mvn", "mvnw", "gradlew",
        "kotlinc", "node", "npm", "pnpm", "yarn", "python3", "pip", "pipenv", "poetry", "uv", "go",
        "rustc", "cargo", "dotnet", "php", "composer", "ruby", "gem", "bundle", "cmake", "ninja", "cc",
        "c++", "readelf", "ldd", "curl", "sha256sum", "sha512sum", "flock", "tar", "docker", "podman");

    /** Returns the shared immutable diagnostic vocabulary. / 返回共享不可变诊断词表。
     * @return fixed diagnostic tool names / 固定诊断工具名称 */
    public static Set<String> diagnosticToolNames() {
        return DIAGNOSTIC_TOOLS;
    }

    /** Checks CMake's indirect tools and binds the configure step to their verified paths. / 检查CMake间接工具并将配置步骤绑定到已校验路径。
     * @param languages reviewed C/C++ language set / 已审阅C/C++语言集合
     * @return guarded configure arguments / 受保护配置参数 */
    public static String cmakeToolArguments(Set<SourceLanguageType> languages) {
        if (languages.isEmpty() || !Set.of(SourceLanguageType.C, SourceLanguageType.CPP).containsAll(languages))
            throw new IllegalArgumentException("CMake tools require reviewed C/C++ languages");
        return "require_build_tool cmake\nrequire_build_tool ninja\n"
            + (languages.contains(SourceLanguageType.C) ? "require_build_tool cc\n" : "")
            + (languages.contains(SourceLanguageType.CPP) ? "require_build_tool c++\n" : "")
            + "cmake_ninja=$(/usr/bin/readlink -f -- \"$(builtin command -v ninja)\")\n"
            + "cmake_arguments=(\"-DCMAKE_MAKE_PROGRAM=$cmake_ninja\")\n"
            + (languages.contains(SourceLanguageType.C)
            ? "cmake_c=$(/usr/bin/readlink -f -- \"$(builtin command -v cc)\")\ncmake_arguments+=(\"-DCMAKE_C_COMPILER=$cmake_c\")\n" : "")
            + (languages.contains(SourceLanguageType.CPP)
            ? "cmake_cpp=$(/usr/bin/readlink -f -- \"$(builtin command -v c++)\")\ncmake_arguments+=(\"-DCMAKE_CXX_COMPILER=$cmake_cpp\")\n" : "");
    }

    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SafeBuildScriptEnvelope() {
    }

    /**
     * Wraps safe build script envelope as text without executing the rendered command.
     * <p>封装安全构建脚本信封为文本，不执行所渲染命令。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @param command fixed or explicitly reviewed command text / 固定或显式审阅的命令文本
     * @return wrap text / 包装文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String wrap(DeploymentProjectFacts facts, DeploymentRemoteWorkspace workspace,
                              DeploymentBuildLimits limits, String command) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(command, "command");
        String source = workspace.candidateRoot() + "/mutable/source";
        String mutable = workspace.candidateRoot() + "/mutable";
        boolean container = facts
            .buildTool() == gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.CONTAINER_BUILD;
        return jvmEnvironment(facts, limits)
            + """
            set -euo pipefail
            candidate=%s
            mutable=%s
            source=%s
            printf 'BUILD_TOOL=%s\n'
            if [ -n "${WTL_AGENT_SOURCE:-}" ]; then
              test "${WTL_SOURCE:-}" = "$mutable/input-source"
              test "${WTL_OUTPUT:-}" = "$source"
              case "$WTL_AGENT_SOURCE" in *'/../'*|*'/./'*|*/..|*/.|*'//'*) exit 64 ;; esac
              case "$WTL_AGENT_SOURCE" in "$WTL_SOURCE"|"$WTL_SOURCE"/*) ;; *) exit 64 ;; esac
              test -d "$WTL_AGENT_SOURCE" && test ! -L "$WTL_AGENT_SOURCE" && test ! -w "$WTL_AGENT_SOURCE"
              test -z "$(find "$WTL_AGENT_SOURCE" -xdev -type l -print -quit)"
              rm -rf -- "$source"
              mkdir -p -- "$source" "$mutable/home"
              cp -R -- "$WTL_AGENT_SOURCE/." "$source/"
              find "$source" -type f -exec chmod u+rw -- {} +
              find "$source" -type d -exec chmod u+rwx -- {} +
            else
            archive="$mutable/source.tar.gz"
            test -f "$archive"
            test ! -L "$archive"
            test "$(sha256sum "$archive" | awk '{print $1}')" = %s
            rm -rf -- "$source"
            mkdir -p -- "$source" "$mutable/home"
            tar -tzf "$archive" > "$mutable/archive-entries.txt"
            test -s "$mutable/archive-entries.txt"
            if LC_ALL=C sort "$mutable/archive-entries.txt" | uniq -d | grep -q .; then
              exit 64
            fi
            while IFS= read -r entry; do
              case "$entry" in ''|/*|./*|../*|*/../*|..|*//*) exit 64 ;; esac
            done < "$mutable/archive-entries.txt"
            tar -tvzf "$archive" | awk 'substr($0, 1, 1) != "-" { exit 1 }'
            %s
            fi
            test -z "$(find "$source" -xdev -type l -print -quit)"
            test -z "$(find "$source" -xdev ! -type f ! -type d -print -quit)"
            cd "$source"
            %s
            run() {
              if ! verify_build_command "$1"; then report_build_tool_failure "$1"; return 65; fi
              "$@"
            }
            retry_run() {
              local max_attempts="$1" attempt=1 status
              shift
              if ! verify_build_command "$1"; then report_build_tool_failure "$1"; return 65; fi
              while true; do
                if "$@"; then return 0; else status=$?; fi
                [ "$attempt" -lt "$max_attempts" ] || return "$status"
                printf 'BUILD_RETRY=%%d/%%d\n' "$attempt" "$max_attempts"
                attempt=$((attempt + 1))
                sleep 2
              done
            }
            java_gradle_arguments=()
            if [ -n "${WTL_JAVA_HOME:-}" ]; then
              export GRADLE_USER_HOME="$mutable/home/.gradle"
              mkdir -p "$GRADLE_USER_HOME"
              printf 'org.gradle.java.installations.auto-download=false\\norg.gradle.java.installations.auto-detect=false\\norg.gradle.java.installations.paths=%%s\\n' "$WTL_JAVA_HOME" > "$GRADLE_USER_HOME/gradle.properties"
              java_init="$mutable/home/wtl-java-toolchain.gradle"
              cat > "$java_init" <<'WTL_JAVA_INIT'
            allprojects { project ->
                afterEvaluate {
                    def java = project.extensions.findByName('java')
                    if (java != null && java.hasProperty('toolchain')) {
                        def type = Class.forName('org.gradle.jvm.toolchain.JavaLanguageVersion')
                        def selected = type.getMethod('of', Integer.TYPE).invoke(null, Integer.parseInt(System.getenv('WTL_JAVA_BRANCH')))
                        if (java.toolchain.languageVersion.orNull != selected) {
                            def sourceTarget = java.sourceCompatibility
                            def bytecodeTarget = java.targetCompatibility
                            def kotlinTargets = [:]
                            project.tasks.each { task ->
                                if (task.hasProperty('kotlinOptions') && task.kotlinOptions.hasProperty('jvmTarget')) {
                                    kotlinTargets[task] = task.kotlinOptions.jvmTarget
                                }
                            }
                            java.toolchain.languageVersion.set(selected)
                            java.sourceCompatibility = sourceTarget
                            java.targetCompatibility = bytecodeTarget
                            kotlinTargets.each { task, target -> task.kotlinOptions.jvmTarget = target }
                        }
                    }
                }
            }
            WTL_JAVA_INIT
              java_gradle_arguments=(--init-script "$java_init")
            fi
            %s
            %s
            """
            .formatted(shellQuote(workspace.candidateRoot()), shellQuote(mutable), shellQuote(source), facts.buildTool().name(),
                shellQuote(workspace.archiveSha256()), extractSource(container),
                (facts.buildDirectory().isEmpty() ? "" : "cd -- " + shellQuote(facts.buildDirectory()) + "\n")
                    + commandGuards(),
                command, workspaceAccounting(container, limits));
    }

    /** Verifies host executables while allowing project tools only inside the bounded mutable workspace. / 校验宿主程序，项目工具仅允许位于有界可变工作区。
     * @return fixed command guards / 固定命令校验 */
    static String commandGuards() {
        return """
            verify_build_command() {
              local executable resolved parent mode
              executable="$(builtin command -v "$1")" || return 1
              resolved="$(/usr/bin/readlink -f -- "$executable")" || return 1
              [ -f "$resolved" ] && [ -x "$resolved" ] || return 1
              case "$resolved" in "$mutable"/*) return 0 ;; esac
              parent="$resolved"
              while true; do
                [ "$(/usr/bin/stat -c '%%u' -- "$parent")" = 0 ] || return 1
                mode="$(/usr/bin/stat -c '%%a' -- "$parent")" || return 1
                [[ "$mode" =~ ^[0-7]{3,4}$ ]] && [ $((8#$mode & 022)) -eq 0 ] || return 1
                [ "$parent" != / ] || break
                parent="${parent%%/*}"
                [ -n "$parent" ] || parent=/
                [ -d "$parent" ] || return 1
              done
            }
            build_tool_identifier() {
              local name="${1##*/}"
              if [[ "$name" =~ ^python3[.][0-9]+$ ]]; then name=python3; fi
              case "$name" in %s) printf '%%s' "$name" ;; *) return 64 ;; esac
            }
            report_build_tool_failure() {
              local tool
              tool="$(build_tool_identifier "$1")" || return 0
              if builtin command -v "$1" >/dev/null 2>&1; then
                printf 'BUILD_TOOL_REJECTED=%%s\n' "$tool"
              else
                printf 'BUILD_MISSING_TOOL=%%s\n' "$tool"
              fi
            }
            require_build_tool() {
              local tool
              tool="$(build_tool_identifier "$1")" || return 64
              if ! builtin command -v "$1" >/dev/null 2>&1; then
                printf 'BUILD_MISSING_TOOL=%%s\n' "$tool"
                return 69
              fi
              if ! verify_build_command "$1"; then
                printf 'BUILD_TOOL_REJECTED=%%s\n' "$tool"
                return 65
              fi
            }
            """.formatted(String.join(" | ", DIAGNOSTIC_TOOLS.stream().sorted().toList()));
    }

    /**
     * Renders bounded source extraction with ownership preservation disabled and the container-specific umask when needed.
     * <p>渲染不保留归档所有者的有界源码提取命令，并在需要时设置容器专用 umask。
     *
     * @param container container / 容器
     * @return extract source text / 提取源码文本
     */
    private static String extractSource(boolean container) {
        String extract = "tar --extract --gzip --file \"$archive\" --directory \"$source\" --no-same-owner --no-same-permissions --numeric-owner";
        return container ? "(umask 022; " + extract + ")" : extract;
    }

    /**
     * Renders fixed workspace accounting protocol text from the reviewed inputs.
     * <p>根据已审阅输入渲染固定工作区用量统计协议文本。
     *
     * @param container container / 容器
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return workspace accounting text / 工作区用量统计文本
     */
    private static String workspaceAccounting(boolean container, DeploymentBuildLimits limits) {
        // Mapped engine files cannot be traversed by the project UID; the root-owned fixed-capacity volume bounds them. / 项目 UID 无法遍历映射的引擎文件；这些文件受 root 持有的固定容量卷约束。
        if (container)
            return "printf 'BUILD_WORKSPACE_LIMIT=fixed-volume\\n'";
        return """
            used=$(du -sb "$mutable" | awk '{print $1}')
            if [ "$used" -gt %d ]; then
              printf 'BUILD_LIMIT=workspace\n'
              exit 42
            fi
            """.formatted(limits.maxWorkspaceBytes());
    }

    /**
     * Renders fixed jvm environment protocol text from the reviewed inputs.
     * <p>根据已审阅输入渲染固定jvm环境协议文本。
     *
     * @param facts typed facts used for deterministic planning / 确定性计划使用的类型化事实
     * @param limits resource and time bounds enforced during execution / 执行期间实施的资源及时间边界
     * @return jvm environment text / jvm环境文本
     */
    private static String jvmEnvironment(DeploymentProjectFacts facts, DeploymentBuildLimits limits) {
        boolean jvm = switch (facts.buildTool()) {
            case JDK, JAVA, MAVEN, MAVEN_WRAPPER, GRADLE_WRAPPER, KOTLINC, GRADLE_KOTLIN_WRAPPER -> true;
            default -> false;
        };
        if (!jvm)
            return "";
        int memory = limits.maxMemoryMiB();
        return "export JAVA_TOOL_OPTIONS='-Xms16m -Xmx" + Math.min(768, memory / 4) + "m -XX:MaxMetaspaceSize="
            + Math.min(384, memory / 8) + "m -XX:CompressedClassSpaceSize=" + Math.min(64, memory / 16)
            + "m -XX:ReservedCodeCacheSize=" + Math.min(128, memory / 16) + "m'\n";
    }

    /**
     * Quotes a literal shell argument without evaluating its content.
     * <p>引用 Shell 字面参数，不求值其内容。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return shell quote text / shell引用文本
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public static String shellQuote(String value) {
        return "'" + Objects.requireNonNull(value, "value").replace("'", "'\"'\"'") + "'";
    }
}
