package gold.debug.windowstolinux.shared.linux.sshd.build.generation.script;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.util.Objects;

/**
 * Owns archive validation, extraction, limits, logging, and build-tool attestation shared by all renderers. / 持有全部渲染器共享的归档校验、解压、限制、日志与构建工具证明。
 */
public final class SafeBuildScriptEnvelope {
    /**
     * Prevents instantiation of this static contract helper.
     * <p>防止实例化当前静态契约辅助类。
     */
    private SafeBuildScriptEnvelope() { }

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
    public static String wrap(DeploymentProjectFacts facts, RemoteWorkspace workspace, BuildLimitConfiguration limits, String command) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(command, "command");
        String source = workspace.candidateRoot() + "/mutable/source";
        String mutable = workspace.candidateRoot() + "/mutable";
        boolean container = facts.buildTool() == gold.debug.windowstolinux.shared.model.project.DeploymentBuildToolType.CONTAINER_BUILD;
        return jvmEnvironment(facts, limits) + """
                set -euo pipefail
                candidate=%s
                mutable=%s
                source=%s
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
                test -z "$(find "$source" -xdev -type l -print -quit)"
                test -z "$(find "$source" -xdev ! -type f ! -type d -print -quit)"
                cd "$source"
                %s
                run() {
                  "$@"
                }
                retry_run() {
                  local max_attempts="$1" attempt=1 status
                  shift
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
                printf 'BUILD_TOOL=%s\n'
                """.formatted(shellQuote(workspace.candidateRoot()), shellQuote(mutable), shellQuote(source),
                shellQuote(workspace.sourceSha256()),
                extractSource(container),
                facts.buildDirectory().isEmpty() ? "" : "cd -- " + shellQuote(facts.buildDirectory()),
                command,
                workspaceAccounting(container, limits), facts.buildTool().name());
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
    private static String workspaceAccounting(boolean container, BuildLimitConfiguration limits) {
        // Mapped engine files cannot be traversed by the project UID; the root-owned fixed-capacity volume bounds them. / 项目 UID 无法遍历映射的引擎文件；这些文件受 root 持有的固定容量卷约束。
        if (container) return "printf 'BUILD_WORKSPACE_LIMIT=fixed-volume\\n'";
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
    private static String jvmEnvironment(DeploymentProjectFacts facts, BuildLimitConfiguration limits) {
        boolean jvm = switch (facts.buildTool()) {
            case JDK, JAVA, MAVEN, MAVEN_WRAPPER, GRADLE_WRAPPER, KOTLINC, GRADLE_KOTLIN_WRAPPER -> true;
            default -> false;
        };
        if (!jvm) return "";
        int memory = limits.maxMemoryMiB();
        return "export JAVA_TOOL_OPTIONS='-Xms16m -Xmx" + Math.min(768, memory / 4)
                + "m -XX:MaxMetaspaceSize=" + Math.min(384, memory / 8)
                + "m -XX:CompressedClassSpaceSize=" + Math.min(64, memory / 16)
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
