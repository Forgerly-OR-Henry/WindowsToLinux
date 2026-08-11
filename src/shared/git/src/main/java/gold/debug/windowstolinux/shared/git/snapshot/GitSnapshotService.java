package gold.debug.windowstolinux.shared.git.snapshot;

import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchiver;
import gold.debug.windowstolinux.shared.source.archive.SourceArchive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Prepares a hook-free, detached, read-only Git checkout and source archive without executing repository code.
 *
 * <p>在不执行仓库代码的前提下准备无 Hook、分离头、只读的 Git 检出和源码归档。
 */
public final class GitSnapshotService {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_OUTPUT_BYTES = 16 * 1024;
    private static final int MAX_GIT_ATTRIBUTES_BYTES = 256 * 1024;
    private final SafeSourceArchiver archiver;

    /**
     * Creates a {@code GitSnapshotService} instance.
     *
     * <p>创建 {@code GitSnapshotService} 实例。
     */
    public GitSnapshotService() {
        this(new SafeSourceArchiver());
    }

    GitSnapshotService(SafeSourceArchiver archiver) {
        this.archiver = Objects.requireNonNull(archiver, "archiver");
    }

    /**
     * Creates a controlled snapshot below the platform-owned workspace root.
     *
     * <p>在平台拥有的工作区根目录下创建受控快照。
     *
     * @param request the bounded Git request / 有界 Git 请求
     * @param workspaceRoot the platform-owned workspace root / 平台拥有的工作区根目录
     * @return the pinned source snapshot / 固定的源码快照
     * @throws GitSnapshotException if snapshot preparation cannot be safely completed / 无法安全完成快照准备时
     */
    public GitSnapshot prepare(GitSourceRequest request, Path workspaceRoot) throws GitSnapshotException {
        Objects.requireNonNull(request, "request");
        Path root = requireControlledWorkspace(workspaceRoot);
        try {
            Path operation = Files.createTempDirectory(root, "git-snapshot-");
            Path checkout = operation.resolve("checkout");
            Path disabledHooks = Files.createDirectories(operation.resolve("disabled-hooks"));
            run(operation, List.of("git", "clone", "--no-checkout", "--no-recurse-submodules", "-c",
                    "core.hooksPath=" + disabledHooks, "--", request.remote().location().toString(), checkout.toString()));
            String remoteReference = referenceName(request);
            run(checkout, List.of("git", "-c", "core.hooksPath=" + disabledHooks, "fetch", "--no-tags", "--depth", "1",
                    "origin", remoteReference));
            run(checkout, List.of("git", "-c", "core.hooksPath=" + disabledHooks, "checkout", "--detach", "FETCH_HEAD"));
            rejectUnsupportedRepositoryFeatures(checkout);
            String commit = run(checkout, List.of("git", "rev-parse", "HEAD")).trim().toLowerCase(java.util.Locale.ROOT);
            if (!commit.matches("[0-9a-f]{40}")) {
                throw new IOException("Git did not resolve a full commit identifier");
            }
            SourceArchive archive = archiver.archive(checkout, operation.resolve("source.tar.gz"));
            if (archive.byteCount() > request.maximumArchiveBytes()) {
                throw new IOException("the checked-out source archive exceeds the configured size limit");
            }
            return new GitSnapshot(request.remote(), commit, checkout, archive);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GitSnapshotException("Git source snapshot preparation failed without executing project code", exception);
        } catch (IOException exception) {
            throw new GitSnapshotException("Git source snapshot preparation failed without executing project code", exception);
        }
    }

    private static Path requireControlledWorkspace(Path workspaceRoot) throws GitSnapshotException {
        if (workspaceRoot == null) {
            throw new GitSnapshotException("Git source snapshot workspace is required", null);
        }
        try {
            Path root = workspaceRoot.toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("workspace root is not a non-symbolic-link directory");
            }
            return root;
        } catch (IOException exception) {
            throw new GitSnapshotException("Git source snapshot workspace is unavailable", exception);
        }
    }

    private static String referenceName(GitSourceRequest request) {
        return switch (request.reference()) {
            case gold.debug.windowstolinux.shared.git.reference.GitReference.Branch branch -> "refs/heads/" + branch.value();
            case gold.debug.windowstolinux.shared.git.reference.GitReference.Tag tag -> "refs/tags/" + tag.value();
            case gold.debug.windowstolinux.shared.git.reference.GitReference.Commit commit -> commit.value();
        };
    }

    private static void rejectUnsupportedRepositoryFeatures(Path checkout) throws IOException {
        if (Files.exists(checkout.resolve(".gitmodules"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Git submodules require an explicit controlled policy and are not accepted by this snapshot");
        }
        Path attributes = checkout.resolve(".gitattributes");
        if (Files.isRegularFile(attributes, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.size(attributes) > MAX_GIT_ATTRIBUTES_BYTES) {
                throw new IOException(".gitattributes exceeds the Git snapshot inspection bound");
            }
            String content = Files.readString(attributes, StandardCharsets.UTF_8);
            if (content.matches("(?s).*\\bfilter=lfs\\b.*")) {
                throw new IOException("Git LFS requires an explicit bounded materialization policy and is not accepted by this snapshot");
            }
        }
    }

    private static String run(Path directory, List<String> command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(new ArrayList<>(command));
        processBuilder.directory(directory.toFile());
        processBuilder.redirectErrorStream(true);
        Map<String, String> environment = processBuilder.environment();
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_LFS_SKIP_SMUDGE", "1");
        Process process = processBuilder.start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Git command timed out");
        }
        byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
        if (output.length > MAX_OUTPUT_BYTES) {
            throw new IOException("Git command output exceeds the safe diagnostic bound");
        }
        if (process.exitValue() != 0) {
            throw new IOException("Git command returned a non-zero exit status");
        }
        return new String(output, StandardCharsets.UTF_8);
    }
}
