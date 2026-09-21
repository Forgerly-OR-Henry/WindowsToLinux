package gold.debug.windowstolinux.app.main.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Fixed child layout beneath the single run-mode-resolved desktop data root. / 唯一运行模式解析桌面数据根下的固定子布局。
 *
 * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
 * @param databaseFile database file / 数据库文件
 * @param workDirectory work directory / 工作目录
 * @param backupsDirectory backups directory / 备份集合目录
 * @param diagnosticsDirectory diagnostics directory / 诊断目录
 */
public record DesktopStorageLayout(
        Path root,
        Path databaseFile,
        Path workDirectory,
        Path backupsDirectory,
        Path diagnosticsDirectory
) {
    /**
     * Validates that every shared path is an exact child of the resolved root. / 校验所有共享路径均为解析数据根的精确子项。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param databaseFile database file / 数据库文件
     * @param workDirectory work directory / 工作目录
     * @param backupsDirectory backups directory / 备份集合目录
     * @param diagnosticsDirectory diagnostics directory / 诊断目录
     */
    public DesktopStorageLayout {
        root = normalize(root, "root");
        databaseFile = directChild(root, databaseFile, "windowstolinux.db", "databaseFile");
        workDirectory = directChild(root, workDirectory, "work", "workDirectory");
        backupsDirectory = directChild(root, backupsDirectory, "backups", "backupsDirectory");
        diagnosticsDirectory = directChild(root, diagnosticsDirectory, "error-logs", "diagnosticsDirectory");
    }

    /**
     * Creates the fixed layout from the sole root resolved by {@link RunModeResolver}. / 从 {@link RunModeResolver} 解析的唯一根创建固定布局。
     *
     * @param dataRoot data root / 数据根目录
     * @return the fixed layout from the sole root resolved by {@link RunModeResolver} / 从 {@link RunModeResolver} 解析的唯一根创建固定布局
     */
    public static DesktopStorageLayout from(Path dataRoot) {
        Path root = normalize(dataRoot, "dataRoot");
        return new DesktopStorageLayout(root, root.resolve("windowstolinux.db"),
                root.resolve("work"), root.resolve("backups"), root.resolve("error-logs"));
    }

    /**
     * Creates and validates the shared directories without using a fallback root. / 创建并验证共享目录且不使用回退根。
     *
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    public void initializeDirectories() throws IOException {
        Files.createDirectories(root);
        requireWritableDirectory(root);
        for (Path directory : List.of(workDirectory, backupsDirectory, diagnosticsDirectory)) {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
            requireWritableDirectory(directory);
        }
        if (Files.exists(databaseFile, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(databaseFile) || !Files.isRegularFile(databaseFile, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("desktop database path is not a regular file");
        }
    }

    /**
     * Requires writable directory and rejects inputs outside the declared constraints.
     * <p>要求可写目录并拒绝超出已声明约束的输入。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     */
    private static void requireWritableDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(directory)) {
            throw new IOException("desktop data layout contains an unavailable or unsafe directory");
        }
    }

    /**
     * Requires the normalized path to be the named direct child of the data root.
     * <p>要求规范化路径是数据根目录下指定名称的直接子路径。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param expectedName expected name / 预期名称
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    private static Path directChild(Path root, Path value, String expectedName, String field) {
        value = normalize(value, field);
        if (!value.equals(root.resolve(expectedName))) {
            throw new IllegalArgumentException(field + " must be the fixed direct child of the data root");
        }
        return value;
    }

    /**
     * Normalizes path.
     * <p>规范化路径。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return constructed or resolved path / 构造或解析得到的路径
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    private static Path normalize(Path value, String field) {
        return Objects.requireNonNull(value, field).toAbsolutePath().normalize();
    }
}
