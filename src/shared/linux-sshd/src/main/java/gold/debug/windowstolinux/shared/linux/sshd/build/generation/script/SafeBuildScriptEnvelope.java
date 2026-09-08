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
        return jvmEnvironment(facts, limits) + """
                set -euo pipefail
                candidate=%s
                mutable=%s
                source=%s
                archive="$mutable/source.tar.gz"
                log="$mutable/build.log"
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
                tar --extract --gzip --file "$archive" --directory "$source" --no-same-owner --no-same-permissions --numeric-owner
                test -z "$(find "$source" -xdev -type l -print -quit)"
                test -z "$(find "$source" -xdev ! -type f ! -type d -print -quit)"
                cd "$source"
                ulimit -u %d
                ulimit -v %d
                run() {
                  set +e
                  setsid /usr/bin/timeout --signal=TERM %d "$@" 2>&1 | head -c %d > "$log"
                  statuses=("${PIPESTATUS[@]}")
                  set -e
                  if [ "$(stat -c %%s "$log")" -ge %d ]; then
                    printf 'BUILD_LIMIT=output\n'
                    exit 43
                  fi
                  if [ "${statuses[0]}" -ne 0 ]; then
                    head -c %d -- "$log"
                    exit "${statuses[0]}"
                  fi
                }
                retry_run() {
                  local max_attempts="$1"
                  local attempt=1
                  shift
                  while [ "$attempt" -le "$max_attempts" ]; do
                    set +e
                    setsid /usr/bin/timeout --signal=TERM %d "$@" 2>&1 | head -c %d > "$log"
                    statuses=("${PIPESTATUS[@]}")
                    set -e
                    if [ "$(stat -c %%s "$log")" -ge %d ]; then
                      printf 'BUILD_LIMIT=output\n'
                      exit 43
                    fi
                    if [ "${statuses[0]}" -eq 0 ]; then
                      return 0
                    fi
                    if [ "$attempt" -eq "$max_attempts" ]; then
                      head -c %d -- "$log"
                      exit "${statuses[0]}"
                    fi
                    printf 'BUILD_RETRY=%%d/%%d\n' "$attempt" "$max_attempts"
                    attempt=$((attempt + 1))
                    sleep 2
                  done
                }
                %s
                used=$(du -sb "$mutable" | awk '{print $1}')
                if [ "$used" -gt %d ]; then
                  printf 'BUILD_LIMIT=workspace\n'
                  exit 42
                fi
                printf 'BUILD_TOOL=%s\n'
                """.formatted(shellQuote(workspace.candidateRoot()), shellQuote(mutable), shellQuote(source),
                shellQuote(workspace.sourceSha256()), limits.maxProcesses(), limits.maxMemoryMiB() * 1024L,
                limits.timeoutSeconds(), limits.maxOutputBytes(), limits.maxOutputBytes(), limits.maxOutputBytes(),
                limits.timeoutSeconds(), limits.maxOutputBytes(), limits.maxOutputBytes(), limits.maxOutputBytes(), command,
                limits.maxWorkspaceBytes(), facts.buildTool().name());
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
