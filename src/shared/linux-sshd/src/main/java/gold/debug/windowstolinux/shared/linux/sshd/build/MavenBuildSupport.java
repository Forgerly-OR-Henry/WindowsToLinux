package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;

import java.util.Objects;

/**
 * Provides the {@code MavenBuildSupport} implementation.
 *
 * <p>提供 {@code MavenBuildSupport} 实现。
 */
public final class MavenBuildSupport {
    private static final String SPRING_BOOT_LAUNCHER_CLASS_PATTERN =
            "org\\.springframework\\.boot\\.loader(\\.launch)?\\.(JarLauncher|PropertiesLauncher)";
    /**
     * Exposes the {@code SPRING_BOOT_LAUNCHER_MANIFEST_PATTERN} constant.
     *
     * <p>公开 {@code SPRING_BOOT_LAUNCHER_MANIFEST_PATTERN} 常量。
     */
    public static final String SPRING_BOOT_LAUNCHER_MANIFEST_PATTERN =
            "^Main-Class: " + SPRING_BOOT_LAUNCHER_CLASS_PATTERN + "$";

    private MavenBuildSupport() {
    }

    /**
     * Checks the condition represented by {@code isSupportedSpringBootLauncher}.
     *
     * <p>检查 {@code isSupportedSpringBootLauncher} 表示的条件。
     *
     * @param mainClass the {@code mainClass} value / {@code mainClass} 值
     * @return whether the operation condition is satisfied / 操作条件是否满足
     */
    public static boolean isSupportedSpringBootLauncher(String mainClass) {
        return mainClass != null && mainClass.matches(SPRING_BOOT_LAUNCHER_CLASS_PATTERN);
    }

    /**
     * Performs the {@code renderSourceBuildScript} operation.
     *
     * <p>执行 {@code renderSourceBuildScript} 操作。
     *
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @param limits the {@code limits} value / {@code limits} 值
     * @return the operation result / 操作结果
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public static String renderSourceBuildScript(RemoteWorkspace workspace, BuildLimits limits) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(limits, "limits");
        String candidate = workspace.candidateRoot();
        String mutable = candidate + "/mutable";
        String source = mutable + "/source";
        return """
                set -euo pipefail
                candidate=%s
                mutable=%s
                source=%s
                archive="$mutable/source.tar.gz"
                home="$mutable/home"
                test -f "$archive"
                test ! -L "$archive"
                test "$(sha256sum "$archive" | awk '{print $1}')" = %s
                rm -rf -- "$source"
                mkdir -p -- "$source"
                mkdir -p -- "$home"
                tar -tzf "$archive" > "$mutable/archive-entries.txt"
                test -s "$mutable/archive-entries.txt"
                if LC_ALL=C sort "$mutable/archive-entries.txt" | uniq -d | grep -q .; then
                  exit 64
                fi
                while IFS= read -r entry; do
                  case "$entry" in
                    ""|/*|./*|../*|*/../*|..|*//*) exit 64 ;;
                  esac
                done < "$mutable/archive-entries.txt"
                tar -tvzf "$archive" | awk 'substr($0, 1, 1) != "-" { exit 1 }'
                tar --extract --gzip --file "$archive" --directory "$source" --no-same-owner --no-same-permissions --numeric-owner
                test -f "$source/pom.xml"
                test -z "$(find "$source" -xdev -type l -print -quit)"
                test -z "$(find "$source" -xdev ! -type f ! -type d -print -quit)"
                find "$source" -xdev -type f -printf '%%P\\n' | LC_ALL=C sort > "$mutable/source-files.txt"
                test -s "$mutable/source-files.txt"
                cd "$source"
                ulimit -u %d
                ulimit -v %d
                if [ -f ./mvnw ]; then
                  chmod 700 -- ./mvnw
                  builder=./mvnw
                  build_tool=maven-wrapper
                else
                  builder=mvn
                  build_tool=maven
                fi
                command -v setsid >/dev/null
                set +e
                setsid /bin/bash -c 'set -o pipefail; timeout --signal=TERM "$1" "$2" -B -DskipTests package 2>&1 | head -c "$3" > "$4"; exit "${PIPESTATUS[0]}"' \\
                  _ %d "$builder" %d "$mutable/build.log" &
                build_pid=$!
                workspace_limited=0
                while kill -0 "$build_pid" 2>/dev/null; do
                  used=$(du -sb "$mutable" | awk '{print $1}')
                  if [ "$used" -gt %d ]; then
                    workspace_limited=1
                    kill -TERM -- "-$build_pid" 2>/dev/null || true
                    break
                  fi
                  sleep 1
                done
                wait "$build_pid"
                build_status=$?
                if [ "$workspace_limited" -ne 0 ]; then
                  printf 'BUILD_LIMIT=workspace\\n'
                  exit 42
                fi
                output_bytes=$(stat -c %%s "$mutable/build.log" 2>/dev/null || printf 0)
                if [ "$output_bytes" -ge %d ]; then
                  printf 'BUILD_LIMIT=output\\n'
                  exit 43
                fi
                if [ "$build_status" -eq 141 ]; then
                  printf 'BUILD_LIMIT=output\\n'
                  exit 43
                fi
                set -e
                test "$build_status" -eq 0
                printf 'BUILD_TOOL=%%s\\n' "$build_tool"
                """.formatted(candidate, mutable, source, workspace.sourceSha256(), limits.maxProcesses(),
                limits.maxMemoryMiB() * 1024L, limits.timeoutSeconds(), limits.maxOutputBytes(),
                limits.maxWorkspaceBytes(), limits.maxOutputBytes());
    }
}
