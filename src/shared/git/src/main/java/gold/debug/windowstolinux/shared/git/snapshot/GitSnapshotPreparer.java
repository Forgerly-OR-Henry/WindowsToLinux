package gold.debug.windowstolinux.shared.git.snapshot;

import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.git.GitRemote;
import gold.debug.windowstolinux.shared.git.GitSnapshot;
import gold.debug.windowstolinux.shared.git.GitSnapshotException;
import gold.debug.windowstolinux.shared.git.GitSourceRequest;
import gold.debug.windowstolinux.shared.git.GitReference;
import gold.debug.windowstolinux.shared.source.archive.SafeSourceArchiver;
import gold.debug.windowstolinux.shared.source.archive.SourceArchive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Coordinates clone, pinned checkout, repository policy validation, and safe source archiving. / 协调克隆、固定检出、仓库策略校验和安全源码归档。 */
public final class GitSnapshotPreparer {
    private final SafeSourceArchiver archiver;
    private final GitCommandRunner commands;
    private final ControlledGitWorkspaceValidator workspaces;
    private final GitRepositoryFeaturePolicy features;

    /** Creates the production Git snapshot preparer. / 创建生产 Git 快照准备器。 */
    public GitSnapshotPreparer() {
        this(new SafeSourceArchiver(), new GitCommandRunner(), new ControlledGitWorkspaceValidator(),
                new GitRepositoryFeaturePolicy());
    }

    GitSnapshotPreparer(SafeSourceArchiver archiver, GitCommandRunner commands,
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
                throw new IOException("Git did not resolve a full commit identifier");
            }
            SourceArchive archive = archiver.archive(checkout, operation.resolve("source.tar.gz"));
            if (archive.byteCount() > request.maximumArchiveBytes()) {
                throw new IOException("the checked-out source archive exceeds the configured size limit");
            }
            return new GitSnapshot(request.remote(), commit, checkout, archive);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cleanupFailure(operation, exception);
            throw failure(exception);
        } catch (IOException exception) {
            cleanupFailure(operation, exception);
            throw failure(exception);
        }
    }

    private static String referenceName(GitSourceRequest request) {
        return switch (request.reference()) {
            case GitReference.Branch branch -> "refs/heads/" + branch.value();
            case GitReference.Tag tag -> "refs/tags/" + tag.value();
            case GitReference.Commit commit -> commit.value();
        };
    }

    private static GitSnapshotException failure(Exception cause) {
        return new GitSnapshotException("Git source snapshot preparation failed without executing project code", cause);
    }

    private static void cleanupFailure(Path operation, Exception failure) {
        if (operation == null) {
            return;
        }
        try {
            Files.walkFileTree(operation, new SimpleFileVisitor<>() {
                /** Performs the {@code visitFile} operation. / 执行 {@code visitFile} 操作。 */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                /** Performs the {@code postVisitDirectory} operation. / 执行 {@code postVisitDirectory} 操作。 */
                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    if (exception != null) {
                        throw exception;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }
}
