package gold.debug.windowstolinux.shared.source.contract.validation;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import gold.debug.windowstolinux.shared.source.manifest.SourceEntry;
import gold.debug.windowstolinux.shared.source.manifest.SourceManifest;

/**
 * Validates local source boundaries and creates a deterministic safe-file manifest.
 *
 *  <p>验证本地源码边界并创建确定性安全文件清单。
 */
public final class SourceBoundaryValidator {
    /**
     * EXCLUDED DIRECTORIES.
     * <p>排除目录集合。
     */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", ".idea", "target", "node_modules", ".m2",
            ".gradle", "logs");

    /**
     * EXCLUDED FILE NAMES.
     * <p>排除文件名称集合。
     */
    private static final Set<String> EXCLUDED_FILE_NAMES = Set.of(".env", ".npmrc", ".pypirc", "id_rsa", "id_dsa",
            "id_ecdsa", "id_ed25519", "known_hosts");

    /**
     * Validates the input through {@code validateSourceDirectory}.
     *
     *  <p>通过 {@code validateSourceDirectory} 验证输入。
     *
     * @param sourceDirectory the user-selected source directory / 用户选择的源码目录
     * @return the operation result / 操作结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     */
    public Path validateSourceDirectory(Path sourceDirectory) throws IOException {
        if (sourceDirectory == null) {
            throw new IllegalArgumentException("sourceDirectory is required");
        }
        Path root = sourceDirectory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("sourceDirectory must be a non-symbolic-link directory");
        }
        rejectSymbolicLinksInPath(root, "sourceDirectory must not traverse symbolic links");
        return root;
    }

    /**
     * Validates the input through {@code validateDestination}.
     *
     *  <p>通过 {@code validateDestination} 验证输入。
     *
     * @param sourceRoot root of the reviewed source tree / 已审阅源码树的根目录
     * @param requestedArchivePath requested archive path / 已请求归档路径
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     */
    public Path validateDestination(Path sourceRoot, Path requestedArchivePath) {
        if (requestedArchivePath == null) {
            throw new IllegalArgumentException("requestedArchivePath is required");
        }
        Path archivePath = requestedArchivePath.toAbsolutePath().normalize();
        Path fileName = archivePath.getFileName();
        if (fileName == null || !fileName.toString().toLowerCase(Locale.ROOT).endsWith(".tar.gz")) {
            throw new IllegalArgumentException("requestedArchivePath must end in .tar.gz");
        }
        if (archivePath.startsWith(sourceRoot)) {
            throw new IllegalArgumentException("archive destination must stay outside the source boundary");
        }
        if (archivePath.getParent() == null) {
            throw new IllegalArgumentException("requestedArchivePath must have a parent directory");
        }
        if (Files.isSymbolicLink(archivePath)) {
            throw new IllegalArgumentException("archive destination must not be a symbolic link");
        }
        rejectSymbolicLinksInPath(archivePath.getParent(), "archive destination must not traverse symbolic links");
        return archivePath;
    }

    /**
     * Traverses the admitted source tree into a deterministic manifest while enforcing path, type, count and byte boundaries.
     * <p>将已准入源码树遍历为确定性清单，并执行路径、类型、数量及字节边界检查。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return the operation result / 操作结果
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public SourceManifest collect(Path root) throws IOException {
        Set<String> explicitFiles = explicitFiles(root);
        List<SourceEntry> included = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        long[] byteCount = {0L};
        Files.walkFileTree(root, new FileVisitor<>() {
            /**
             * Checks the directory boundary before visiting its contents.
             * <p>在访问目录内容前检查目录边界。
             *
             * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
             * @param attributes attributes / 属性
             * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(root) && Files.isSymbolicLink(directory)) {
                    throw new IOException("symbolic-link directory is not allowed: " + root.relativize(directory));
                }
                if (!directory.equals(root) && EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())) {
                    excluded.add(normalizedRelative(root, directory) + "/");
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

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
                if (Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                    throw new IOException("only regular files can be archived: " + root.relativize(file));
                }
                if (isExcludedFile(file) && !explicitFiles.contains(normalizedRelative(root, file))) {
                    excluded.add(normalizedRelative(root, file));
                    return FileVisitResult.CONTINUE;
                }
                String relative = normalizedRelative(root, file);
                included.add(new SourceEntry(file, relative, attributes.size()));
                byteCount[0] = Math.addExact(byteCount[0], attributes.size());
                return FileVisitResult.CONTINUE;
            }

            /**
             * Visits file failed.
             * <p>遍历文件失败。
             *
             * @param file file / 文件
             * @param exception original exception being classified or translated / 正在分类或转换的原始异常
             * @return constructed or resolved file visit result / 构造或解析得到的文件Visit结果
             * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
             */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                throw new IOException("cannot read source entry: " + file, exception);
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
                if (exception != null) {
                    throw exception;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        included.sort(Comparator.comparing(SourceEntry::relativePath));
        return new SourceManifest(included, excluded, byteCount[0]);
    }

    /**
     * Validates the input through {@code verifyUnchangedRegularFile}.
     *
     *  <p>通过 {@code verifyUnchangedRegularFile} 验证输入。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param entry entry / 条目
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public void verifyUnchangedRegularFile(Path root, SourceEntry entry) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(entry.path(), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(entry.path()) || !attributes.isRegularFile() || attributes.size() != entry.byteCount()
                || !entry.relativePath().equals(normalizedRelative(root, entry.path()))) {
            throw new IOException("source entry changed while creating archive: " + entry.relativePath());
        }
    }

    /**
     * Reports whether the excluded file condition holds for this contract.
     * <p>判断当前契约是否满足排除文件条件。
     *
     * @param file file / 文件
     * @return true when excluded file condition holds for this contract, false otherwise / 当前契约是否满足排除文件条件时为 true，否则为 false
     */
    private static boolean isExcludedFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return isSensitiveFile(name) || name.endsWith(".log");
    }

    /**
     * Reports whether the sensitive file condition holds for this contract.
     * <p>判断当前契约是否满足敏感文件条件。
     *
     * @param name human-readable name or diagnostic field label / 可读名称或诊断字段标签
     * @return true when sensitive file condition holds for this contract, false otherwise / 当前契约是否满足敏感文件条件时为 true，否则为 false
     */
    private static boolean isSensitiveFile(String name) {
        return EXCLUDED_FILE_NAMES.contains(name) || name.startsWith(".env.") || name.endsWith(".pem")
                || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".pfx");
    }

    /**
     * Explicit resources can include sample logs but cannot override secret or source boundaries. / 显式资源可纳入样例日志，但不能绕过秘密或源码边界。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @return constructed or resolved set / 构造或解析得到的集合
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static Set<String> explicitFiles(Path root) throws IOException {
        Path declaration = root.resolve("windowstolinux-application.properties");
        if (!Files.exists(declaration, LinkOption.NOFOLLOW_LINKS))
            return Set.of();
        if (!Files.isRegularFile(declaration, LinkOption.NOFOLLOW_LINKS) || Files.size(declaration) > 65536)
            throw new IOException("application source declaration must be a bounded regular file");
        var properties = new java.util.Properties();
        try (var reader = Files.newBufferedReader(declaration)) {
            properties.load(reader);
        }
        String value = properties.getProperty("source.include", "");
        if (value.isBlank())
            return Set.of();
        var result = new java.util.HashSet<String>();
        for (String item : value.split(",", -1)) {
            String relative = gold.debug.windowstolinux.shared.model.project.application.ApplicationCommand
                    .relative(item.trim(), false);
            Path file = root.resolve(relative).normalize();
            if (result.size() >= 32 || !result.add(relative) || !file.startsWith(root)
                    || !relative.equals(normalizedRelative(root, file))
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || isSensitiveFile(file.getFileName().toString().toLowerCase(Locale.ROOT)))
                throw new IOException("explicit source resource violates archive boundaries");
            rejectSymbolicLinksInPath(file, "explicit source resource must not traverse symbolic links");
            for (Path part : Path.of(relative))
                if (EXCLUDED_DIRECTORIES.contains(part.toString()))
                    throw new IOException("explicit source resource crosses an excluded directory");
        }
        return Set.copyOf(result);
    }

    /**
     * Rejects a symbolic link at the selected path or any ancestor.
     * <p>拒绝所选路径或任意祖先路径上的符号链接。
     *
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @param message localized explanation / 本地化说明
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static void rejectSymbolicLinksInPath(Path path, String message) {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(message);
            }
        }
    }

    /**
     * Validates and produces normalized relative for the next contract boundary.
     * <p>校验并生成供下一契约边界使用的规范化相对。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param path filesystem or archive member path used by this operation / 当前操作使用的文件系统或归档成员路径
     * @return the operation result / 操作结果
     * @throws IllegalArgumentException if an argument violates the required constraints / 参数违反必要约束时
     */
    public static String normalizedRelative(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        if (relative.isBlank() || relative.startsWith("/") || relative.startsWith("../") || relative.equals("..")
                || relative.contains("/../") || relative.contains("//") || relative.indexOf('\0') >= 0
                || relative.contains("\r") || relative.contains("\n")) {
            throw new IllegalArgumentException("entry escapes source boundary");
        }
        for (String segment : relative.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("entry escapes source boundary");
            }
        }
        return relative;
    }
}
