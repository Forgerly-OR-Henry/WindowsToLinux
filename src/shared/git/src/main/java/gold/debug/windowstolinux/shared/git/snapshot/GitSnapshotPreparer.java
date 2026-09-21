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

/**
 * Coordinates clone, pinned checkout, repository policy validation, and safe source archiving. / 协调克隆、固定检出、仓库策略校验和安全源码归档。
 */
public final class GitSnapshotPreparer {
    /**
     * Maximum additional attempts after a transient Git transfer failure.
     * <p>Git 传输暂时失败后允许的额外尝试次数上限。
     */
    private static final int MAX_TRANSIENT_RETRIES = 2;
    /**
     * RETRY INTERVAL MILLIS.
     * <p>重试间隔毫秒。
     */
    private static final long RETRY_INTERVAL_MILLIS = 500;
    /**
     * Bound safe source archive preparer collaborator for archiver.
     * <p>处理归档生成器的安全源码归档准备器协作对象。
     */
    private final SafeSourceArchivePreparer archiver;
    /**
     * Bound git command executor collaborator for typed remote command boundary.
     * <p>处理类型化远端命令边界的Git命令执行器协作对象。
     */
    private final GitCommandExecutor commands;
    /**
     * Workspaces.
     * <p>工作区集合。
     */
    private final ControlledGitWorkspaceValidator workspaces;
    /**
     * Bound git repository feature policy collaborator for features.
     * <p>处理特性的Git仓库Feature策略协作对象。
     */
    private final GitRepositoryFeaturePolicy features;

    /**
     * Creates the production Git snapshot preparer. / 创建生产 Git 快照准备器。
     */
    public GitSnapshotPreparer() {
        this(new SafeSourceArchivePreparer(), new GitCommandExecutor(), new ControlledGitWorkspaceValidator(),
                new GitRepositoryFeaturePolicy());
    }

    /**
     * Validates and binds the inputs required by git snapshot preparer.
     * <p>校验并绑定Git快照准备器所需输入。
     *
     * @param archiver archiver / 归档生成器
     * @param commands typed remote command boundary / 类型化远端命令边界
     * @param workspaces workspaces / 工作区集合
     * @param features features / 特性
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    GitSnapshotPreparer(SafeSourceArchivePreparer archiver, GitCommandExecutor commands,
                        ControlledGitWorkspaceValidator workspaces, GitRepositoryFeaturePolicy features) {
        this.archiver = Objects.requireNonNull(archiver, "archiver");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.features = Objects.requireNonNull(features, "features");
    }

    /**
     * Creates a controlled, commit-pinned source snapshot. / 创建受控且固定到 Commit 的源码快照。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param workspaceRoot workspace root / 工作区根目录
     * @return a controlled, commit-pinned source snapshot / 受控且固定到 Commit 的源码快照
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
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

    /**
     * Creates one isolated Git preparation attempt, pins the requested revision and archives the admitted checkout.
     * <p>创建一次隔离 Git 准备尝试、固定请求修订，并归档已准入检出内容。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return one isolated Git preparation attempt, pins the requested revision and archives the admitted checkout / 一次隔离 Git 准备尝试、固定请求修订，并归档已准入检出内容
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     */
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

    /**
     * Renders the requested Git reference as HEAD, a full branch or tag ref, or a commit identifier.
     * <p>将请求的 Git 引用渲染为 HEAD、完整分支或标签引用，或提交标识。
     *
     * @param request reviewed inputs for the requested operation / 所请求操作的已审阅输入
     * @return reference name text / 引用名称文本
     */
    private static String referenceName(GitSourceRequest request) {
        return switch (request.reference()) {
            case GitReference.DefaultBranch ignored -> "HEAD";
            case GitReference.Branch branch -> "refs/heads/" + branch.value();
            case GitReference.Tag tag -> "refs/tags/" + tag.value();
            case GitReference.Commit commit -> commit.value();
        };
    }

    /**
     * Waits for for retry.
     * <p>等待对应重试。
     *
     * @throws GitSnapshotException if the git snapshot boundary rejects the operation / Git快照边界拒绝当前操作时
     */
    private static void waitForRetry() throws GitSnapshotException {
        try {
            Thread.sleep(RETRY_INTERVAL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw GitSnapshotException.create(GitSnapshotFailureType.INTERRUPTED,
                    "Git transient-network retry was interrupted", exception);
        }
    }

    /**
     * Cleans the failed Git attempt and attaches any cleanup failure to the original classified failure.
     * <p>清理失败的 Git 尝试，并将清理失败附加到原始分类失败。
     *
     * @param operation operation / 操作
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return constructed or resolved git snapshot exception / 构造或解析得到的Git快照异常
     */
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

    /**
     * Deletes platform-owned work area with enforced path boundaries.
     * <p>删除具有路径边界约束的平台工作区。
     *
     * @param operation operation / 操作
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void deleteWorkspace(Path operation) throws IOException {
        if (!Files.exists(operation)) return;
        Files.walkFileTree(operation, new SimpleFileVisitor<>() {
            /**
             * Visits file.
             * <p>遍历文件。
             *
             * @param file file / 文件
             * @param attributes attributes / 属性
             * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                clearReadOnly(file);
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            /**
             * Completes the directory traversal step and propagates any traversal failure.
             * <p>完成目录遍历步骤并传播遍历失败。
             *
             * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
             * @param exception original exception being classified or translated / 正在分类或转换的原始异常
             * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) throw exception;
                clearReadOnly(directory);
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Clears read only.
     * <p>清空读取仅。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     */
    private static void clearReadOnly(Path path) {
        try {
            Files.setAttribute(path, "dos:readonly", false, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Non-DOS filesystems or disappearing entries need no attribute adjustment. / 非 DOS 文件系统或已消失条目无需调整属性。
        }
    }
}
