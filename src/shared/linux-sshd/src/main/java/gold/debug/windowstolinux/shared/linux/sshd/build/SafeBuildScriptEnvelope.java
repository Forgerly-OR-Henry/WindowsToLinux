package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;
import gold.debug.windowstolinux.shared.model.project.DeploymentProjectFacts;

import java.util.Objects;

/** Owns archive validation, extraction, limits, logging, and build-tool attestation shared by all renderers. / 持有全部渲染器共享的归档校验、解压、限制、日志与构建工具证明。 */
final class SafeBuildScriptEnvelope {
    private SafeBuildScriptEnvelope() { }

    static String wrap(DeploymentProjectFacts facts, RemoteWorkspace workspace, BuildLimits limits, String command) {
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(command, "command");
        String source = workspace.candidateRoot() + "/mutable/source";
        String mutable = workspace.candidateRoot() + "/mutable";
        return """
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
                %s
                used=$(du -sb "$mutable" | awk '{print $1}')
                if [ "$used" -gt %d ]; then
                  printf 'BUILD_LIMIT=workspace\n'
                  exit 42
                fi
                printf 'BUILD_TOOL=%s\n'
                """.formatted(shellQuote(workspace.candidateRoot()), shellQuote(mutable), shellQuote(source),
                shellQuote(workspace.sourceSha256()), limits.maxProcesses(), limits.maxMemoryMiB() * 1024L,
                limits.timeoutSeconds(), limits.maxOutputBytes(), limits.maxOutputBytes(), limits.maxOutputBytes(), command,
                limits.maxWorkspaceBytes(), facts.buildTool().name());
    }

    static String shellQuote(String value) {
        return "'" + Objects.requireNonNull(value, "value").replace("'", "'\"'\"'") + "'";
    }
}
