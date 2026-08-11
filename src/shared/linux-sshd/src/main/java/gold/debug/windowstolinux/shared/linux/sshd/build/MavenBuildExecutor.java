package gold.debug.windowstolinux.shared.linux.sshd.build;

import gold.debug.windowstolinux.shared.linux.build.RemoteBuildResult;
import gold.debug.windowstolinux.shared.linux.connection.LinuxOperationException;
import gold.debug.windowstolinux.shared.linux.sshd.connection.SshCommandExecutor;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.model.deployment.BuildLimits;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Provides the {@code MavenBuildExecutor} implementation.
 *
 * <p>提供 {@code MavenBuildExecutor} 实现。
 */
public final class MavenBuildExecutor {
    private final SshCommandExecutor commands;
    private final String username;

    /**
     * Creates a {@code MavenBuildExecutor} instance.
     *
     * <p>创建 {@code MavenBuildExecutor} 实例。
     *
     * @param commands the {@code commands} value / {@code commands} 值
     * @param username the {@code username} value / {@code username} 值
     * @throws NullPointerException if a required argument is {@code null} / 必要参数为 {@code null} 时
     */
    public MavenBuildExecutor(SshCommandExecutor commands, String username) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.username = Objects.requireNonNull(username, "username");
    }

    /**
     * Performs the {@code build} operation.
     *
     * <p>执行 {@code build} 操作。
     *
     * @param workspace the {@code workspace} value / {@code workspace} 值
     * @param limits the {@code limits} value / {@code limits} 值
     * @return the operation result / 操作结果
     * @throws LinuxOperationException if the operation cannot be completed / 无法完成操作时
     */
    public RemoteBuildResult build(RemoteWorkspace workspace, BuildLimits limits) throws LinuxOperationException {
        if (limits.runAsRoot() && !"root".equals(username)) {
            throw LinuxOperationException.localized("linux.error.rootBuildRequiresRootSession",
                    "Phase-one root builds require a root SSH session");
        }
        String candidate = workspace.candidateRoot();
        String mutable = candidate + "/mutable";
        String source = mutable + "/source";
        String buildScript = MavenBuildSupport.renderSourceBuildScript(workspace, limits);
        String command = "env -i PATH=/usr/bin:/bin HOME=" + SshCommandExecutor.quote(mutable + "/home")
                + " /bin/bash -lc " + SshCommandExecutor.quote(buildScript);
        var build = commands.exec(command, Duration.ofSeconds(limits.timeoutSeconds() + 30L), true);
        if (!build.succeeded()) {
            String limit = SshCommandExecutor.lines(build.output()).get("BUILD_LIMIT");
            if ("workspace".equals(limit)) {
                return RemoteBuildResult.failed("Target build candidate exceeded the confirmed workspace limit");
            }
            if ("output".equals(limit)) {
                return RemoteBuildResult.failed("Target Maven build output exceeded the confirmed output limit");
            }
            String detail = readBuildDiagnostic(candidate);
            if (detail.isBlank()) {
                detail = build.failureEvidence();
            }
            String suffix = detail.isBlank() ? "" : "; controlled build diagnostic: " + detail;
            return RemoteBuildResult.failed(build.timedOut() ? "Target Maven build timed out" + suffix
                    : "Target Maven build failed" + suffix);
        }
        String buildTool = SshCommandExecutor.lines(build.output()).get("BUILD_TOOL");
        if (!"maven".equals(buildTool) && !"maven-wrapper".equals(buildTool)) {
            return RemoteBuildResult.failed("Target build entry point could not be verified");
        }
        String verifyScript = """
                set -euo pipefail
                source=%s
                manifest_dir=%s
                mapfile -t jars < <(find "$source/target" -maxdepth 1 -type f -name '*.jar' ! -name 'original-*.jar' -printf '%%p\n' | sort)
                printf 'JAR_COUNT=%%s\n' "${#jars[@]}"
                printf 'JARS=%%s\n' "${jars[*]:-}"
                test "${#jars[@]}" -eq 1
                rm -rf -- "$manifest_dir"
                mkdir -p -- "$manifest_dir"
                (
                  cd "$manifest_dir"
                  /usr/bin/jar xf "${jars[0]}" META-INF/MANIFEST.MF
                )
                test -f "$manifest_dir/META-INF/MANIFEST.MF"
                tr -d '\r' < "$manifest_dir/META-INF/MANIFEST.MF" | grep -Eq %s
                rm -rf -- "$manifest_dir"
                printf 'ARTIFACT=%%s\n' "${jars[0]}"
                printf 'DIGEST='; sha256sum "${jars[0]}" | awk '{print $1}'
                printf 'JAVA='; /usr/bin/java -version 2>&1 | head -n 1
                """.formatted(SshCommandExecutor.quote(source),
                SshCommandExecutor.quote(mutable + "/manifest"),
                SshCommandExecutor.quote(MavenBuildSupport.SPRING_BOOT_LAUNCHER_MANIFEST_PATTERN));
        var artifact = commands.exec("/bin/bash -lc " + SshCommandExecutor.quote(verifyScript),
                Duration.ofSeconds(30), true);
        if (!artifact.succeeded()) {
            return RemoteBuildResult.failed("Build output is not exactly one executable Spring Boot JAR; "
                    + "controlled verification diagnostic: "
                    + artifact.failureEvidence());
        }
        Map<String, String> values = SshCommandExecutor.lines(artifact.output());
        String path = values.get("ARTIFACT");
        String digest = values.get("DIGEST");
        String javaVersion = values.get("JAVA");
        if (path == null || digest == null || javaVersion == null || javaVersion.isBlank()
                || !digest.matches("[0-9a-f]{64}")) {
            return RemoteBuildResult.failed("Candidate JAR path, digest, or Java version could not be verified");
        }
        String builderLabel = "maven-wrapper".equals(buildTool) ? "Maven Wrapper" : "Maven";
        return RemoteBuildResult.succeeded(path, digest,
                "Target " + builderLabel + " build completed with exactly one executable JAR; " + javaVersion);
    }

    private String readBuildDiagnostic(String candidate) throws LinuxOperationException {
        String script = """
                if [ -f %s ]; then
                  head -c %d -- %s
                fi
                """.formatted(SshCommandExecutor.quote(candidate + "/mutable/build.log"),
                SshCommandExecutor.MAX_EVIDENCE_CHARS,
                SshCommandExecutor.quote(candidate + "/mutable/build.log"));
        var result = commands.exec(script, Duration.ofSeconds(10), true);
        return result.succeeded() ? result.output().trim() : "";
    }
}
