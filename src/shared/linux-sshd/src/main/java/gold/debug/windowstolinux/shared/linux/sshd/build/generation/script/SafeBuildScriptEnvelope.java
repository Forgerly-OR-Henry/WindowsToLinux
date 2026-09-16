package gold.debug.windowstolinux.shared.linux.sshd.build.generation.script;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimitConfiguration;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.util.Objects;

/** Owns archive validation, extraction, limits, logging, and build-tool attestation shared by all renderers. / 持有全部渲染器共享的归档校验、解压、限制、日志与构建工具证明。 */
public final class SafeBuildScriptEnvelope {
    private SafeBuildScriptEnvelope() { }

    /** Performs the {@code wrap} operation. / 执行 {@code wrap} 操作。 */
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
                command,
                workspaceAccounting(container, limits), facts.buildTool().name());
    }

    private static String extractSource(boolean container) {
        String extract = "tar --extract --gzip --file \"$archive\" --directory \"$source\" --no-same-owner --no-same-permissions --numeric-owner";
        return container ? "(umask 022; " + extract + ")" : extract;
    }

    private static String workspaceAccounting(boolean container, BuildLimitConfiguration limits) {
        // Mapped engine files cannot be traversed by the project UID; the root-owned fixed-capacity volume bounds them.
        if (container) return "printf 'BUILD_WORKSPACE_LIMIT=fixed-volume\\n'";
        return """
                used=$(du -sb "$mutable" | awk '{print $1}')
                if [ "$used" -gt %d ]; then
                  printf 'BUILD_LIMIT=workspace\n'
                  exit 42
                fi
                """.formatted(limits.maxWorkspaceBytes());
    }

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

    /** Performs the {@code shellQuote} operation. / 执行 {@code shellQuote} 操作。 */
    public static String shellQuote(String value) {
        return "'" + Objects.requireNonNull(value, "value").replace("'", "'\"'\"'") + "'";
    }
}
