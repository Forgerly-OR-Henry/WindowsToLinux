package gold.debug.windowstolinux.shared.git.snapshot;

import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.git.GitSnapshot;
import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSnapshotFailureType;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchivePreparer;
import gold.debug.windowstolinux.shared.source.archive.SourceArchive;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Coordinates clone, pinned checkout, repository policy validation, and safe source archiving. / 协调克隆、固定检出、仓库策略校验和安全源码归档。 */
public final class GitSnapshotPreparer {
    private static final int MAX_TRANSIENT_RETRIES = 2;
    private static final long RETRY_INTERVAL_MILLIS = 500;
    private final SafeSourceArchivePreparer archiver;
    private final GitCommandExecutor commands;
    private final ControlledGitWorkspaceValidator workspaces;
    private final GitRepositoryFeaturePolicy features;

    /** Creates the production Git snapshot preparer. / 创建生产 Git 快照准备器。 */
    public GitSnapshotPreparer() {
        this(new SafeSourceArchivePreparer(), new GitCommandExecutor(), new ControlledGitWorkspaceValidator(),
                new GitRepositoryFeaturePolicy());
    }

    GitSnapshotPreparer(SafeSourceArchivePreparer archiver, GitCommandExecutor commands,
                        ControlledGitWorkspaceValidator workspaces, GitRepositoryFeaturePolicy features) {
        this.archiver = Objects.requireNonNull(archiver, "archiver");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.features = Objects.requireNonNull(features, "features");
    }

    /** Creates a controlled, commit-pinned source snapshot. / 创建受控且固定到 Commit 的源码快照。 */
    public GitSnapshot prepare(GitSourceRequest request, Path workspaceRoot) throws GitSnapshotException {
        Objects.requireNonNull(request, "request");
        Path root = workspaces.require(workspaceRoot);
        for (int retry = 0; ; retry++) {
            try {
                return prepareAttempt(request, root);
            } catch (GitSnapshotException failure) {
                if (failure.failure().definition() != GitSnapshotFailureType.TRANSIENT_NETWORK_FAILURE
                        || retry >= MAX_TRANSIENT_RETRIES) {
                    throw failure;
                }
                waitForRetry();
            }
        }
    }

    private GitSnapshot prepareAttempt(GitSourceRequest request, Path root) throws GitSnapshotException {
        Path operation = null;
        try {
            operation = Files.createTempDirectory(root, "git-snapshot-");
            Path checkout = Files.createDirectories(operation.resolve("checkout"));
            Path disabledHooks = Files.createDirectories(operation.resolve("disabled-hooks"));
            commands.run(checkout, List.of("git", "init", "--initial-branch=windowstolinux-snapshot"));
            commands.run(checkout, List.of("git", "config", "core.autocrlf", "false"));
            commands.run(checkout, List.of("git", "remote", "add", "origin",
                    request.remote().location().toString()));
            commands.run(checkout, List.of("git", "-c", "core.hooksPath=" + disabledHooks, "fetch", "--no-tags",
                    "--depth", "1", "origin", referenceName(request)));
            commands.run(checkout, List.of("git", "-c", "core.hooksPath=" + disabledHooks,
                    "checkout", "--detach", "FETCH_HEAD"));
            features.verify(checkout, commands.readIndex(checkout));
            String commit = commands.run(checkout, List.of("git", "rev-parse", "HEAD")).trim().toLowerCase(Locale.ROOT);
            if (!commit.matches("[0-9a-f]{40}")) {
                throw GitSnapshotException.create(GitSnapshotFailureType.PREPARATION_FAILED,
                        "Git did not resolve a full commit identifier");
            }
            SourceArchive archive = archiver.archive(checkout, operation.resolve("source.tar.gz"));
            if (archive.byteCount() > request.maximumArchiveBytes()) {
                throw GitSnapshotException.create(GitSnapshotFailureType.PREPARATION_FAILED,
                        "The checked-out source archive exceeds the configured size limit");
            }
            return new GitSnapshot(request.remote(), commit, checkout, archive);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw cleanupThen(operation, GitSnapshotException.create(GitSnapshotFailureType.INTERRUPTED,
                    "Git source snapshot preparation was interrupted", exception));
        } catch (GitSnapshotException exception) {
            throw cleanupThen(operation, exception);
        } catch (IOException exception) {
            throw cleanupThen(operation, GitSnapshotException.create(GitSnapshotFailureType.PREPARATION_FAILED,
                    "Git source snapshot preparation failed without executing project code", exception));
        }
    }

    private static String referenceName(GitSourceRequest request) {
        return switch (request.reference()) {
            case GitReference.Branch branch -> "refs/heads/" + branch.value();
            case GitReference.Tag tag -> "refs/tags/" + tag.value();
            case GitReference.Commit commit -> commit.value();
        };
    }

    private static void waitForRetry() throws GitSnapshotException {
        try {
            Thread.sleep(RETRY_INTERVAL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw GitSnapshotException.create(GitSnapshotFailureType.INTERRUPTED,
                    "Git transient-network retry was interrupted", exception);
        }
    }

    private static GitSnapshotException cleanupThen(Path operation, GitSnapshotException failure) {
        if (operation == null) {
            return failure;
        }
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                deleteWorkspace(operation);
                if (!Files.exists(operation)) {
                    return failure;
                }
            } catch (IOException cleanup) {
                lastFailure = cleanup;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                lastFailure = new IOException("Git workspace cleanup was interrupted", interrupted);
                break;
            }
        }
        IOException cleanup = lastFailure == null
                ? new IOException("Git workspace still exists after bounded cleanup") : lastFailure;
        cleanup.addSuppressed(failure);
        return GitSnapshotException.create(GitSnapshotFailureType.CLEANUP_FAILED,
                "Git temporary workspace cleanup could not be verified", cleanup);
    }

    private static void deleteWorkspace(Path operation) throws IOException {
        if (!Files.exists(operation)) return;
        Files.walkFileTree(operation, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                clearReadOnly(file);
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) throw exception;
                clearReadOnly(directory);
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void clearReadOnly(Path path) {
        try {
            Files.setAttribute(path, "dos:readonly", false, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-DOS filesystems or disappearing entries need no attribute adjustment. / 非 DOS 文件系统或已消失条目无需调整属性。
        }
    }
}
