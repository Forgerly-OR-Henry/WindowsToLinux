package gold.debug.windowstolinux.shared.source.snapshot;

import gold.debug.windowstolinux.shared.source.contract.validation.SourceBoundaryValidator;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A private, source-only directory frozen before analysis and used by every component. / 分析前冻结的私有纯源码目录，供所有组件共同使用。
 */
public final class SourceDirectorySnapshot implements AutoCloseable {
    /**
     * Directory within the caller's controlled storage boundary.
     * <p>调用方受控存储边界内的目录。
     */
    private final Path directory;
    /**
     * Identity.
     * <p>身份。
     */
    private final Object identity;
    /**
     * Root of the reviewed source tree.
     * <p>已审阅源码树的根目录。
     */
    private Path sourceRoot;

    /**
     * Binds the supplied dependencies and state for source directory snapshot.
     * <p>为源码目录快照绑定传入的依赖及状态。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private SourceDirectorySnapshot(Path directory) throws IOException {
        this.directory = directory;
        this.identity = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    /**
     * Copies the validated manifest into a new private directory outside the user's source tree. / 将已验证清单复制到用户源码树之外的新私有目录。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @return constructed or resolved source directory snapshot / 构造或解析得到的源码目录快照
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static SourceDirectorySnapshot create(Path source, Path workspace) throws IOException {
        return create(source, workspace, source.toAbsolutePath().normalize().getFileName().toString());
    }

    /**
     * Keeps the source name stable when the original directory is a generic Git checkout. / 原始目录为通用 Git 检出目录时，保持源码名称稳定。
     *
     * @param source source identity or content read by the operation / 操作读取的源身份或内容
     * @param workspace platform-owned work area with enforced path boundaries / 具有路径边界约束的平台工作区
     * @param sourceName source name / 源码名称
     * @return constructed or resolved source directory snapshot / 构造或解析得到的源码目录快照
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public static SourceDirectorySnapshot create(Path source, Path workspace, String sourceName) throws IOException {
        if (sourceName == null || sourceName.isBlank() || sourceName.equals(".") || sourceName.equals("..")
                || sourceName.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")) throw new IOException("invalid source name");
        SourceBoundaryValidator validator = new SourceBoundaryValidator();
        Path root = validator.validateSourceDirectory(source);
        Path parent = workspace.toAbsolutePath().normalize();
        if (parent.startsWith(root)) throw new IOException("snapshot workspace cannot be inside source");
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(parent)) throw new IOException("snapshot parent cannot be symbolic");
        var manifest = validator.collect(root);
        SourceDirectorySnapshot snapshot = new SourceDirectorySnapshot(Files.createTempDirectory(parent, "source-snapshot-"));
        try {
            snapshot.sourceRoot = Files.createDirectory(snapshot.directory.resolve(sourceName));
            for (var entry : manifest.entries()) {
                validator.verifyUnchangedRegularFile(root, entry);
                Path target = snapshot.sourceRoot.resolve(entry.relativePath()).normalize();
                if (!target.startsWith(snapshot.directory)) throw new IOException("snapshot entry escapes root");
                Files.createDirectories(target.getParent());
                Files.copy(entry.path(), target);
                validator.verifyUnchangedRegularFile(root, entry);
                if (Files.mismatch(entry.path(), target) != -1) throw new IOException("source changed during snapshot");
            }
            return snapshot;
        } catch (IOException | RuntimeException failure) {
            try { snapshot.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Returns the frozen source root. / 返回冻结的源码根目录。
     *
     * @return the frozen source root / 冻结的源码根目录
     */
    public Path directory() { return sourceRoot; }

    /**
     * Removes only this exact private directory; replacement or symbolic roots are rejected. / 仅删除本次精确私有目录，拒绝被替换或符号链接形式的根目录。
     *
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    @Override public void close() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        var attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || !java.util.Objects.equals(identity, attributes.fileKey()))
            throw new IOException("snapshot identity changed before cleanup");
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            /**
             * Visits file.
             * <p>遍历文件。
             *
             * @param file file / 文件
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes value) throws IOException {
                Files.delete(file); return FileVisitResult.CONTINUE;
            }
            /**
             * Completes the directory traversal step and propagates any traversal failure.
             * <p>完成目录遍历步骤并传播遍历失败。
             *
             * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
             * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
             * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override public FileVisitResult postVisitDirectory(Path path, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
        });
    }
}
