package gold.debug.windowstolinux.app.main.runtime;

import gold.debug.windowstolinux.shared.config.resource.ManagedDatabaseConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Fixed child layout beneath the single run-mode-resolved desktop data root. / 唯一运行模式解析桌面数据根下的固定子布局。 */
public record DesktopStorageLayout(
        Path root,
        Path databaseFile,
        Path managedApplicationsDirectory,
        Path workDirectory,
        Path backupsDirectory,
        Path diagnosticsDirectory
) {
    /** Validates that every shared path is an exact child of the resolved root. / 校验所有共享路径均为解析数据根的精确子项。 */
    public DesktopStorageLayout {
        root = normalize(root, "root");
        databaseFile = directChild(root, databaseFile, "windowstolinux.db", "databaseFile");
        managedApplicationsDirectory = directChild(root, managedApplicationsDirectory,
                "managed-applications", "managedApplicationsDirectory");
        workDirectory = directChild(root, workDirectory, "work", "workDirectory");
        backupsDirectory = directChild(root, backupsDirectory, "backups", "backupsDirectory");
        diagnosticsDirectory = directChild(root, diagnosticsDirectory, "diagnostics", "diagnosticsDirectory");
    }

    /** Creates the fixed layout from the sole root resolved by {@link RunModeResolver}. / 从 {@link RunModeResolver} 解析的唯一根创建固定布局。 */
    public static DesktopStorageLayout from(Path dataRoot) {
        Path root = normalize(dataRoot, "dataRoot");
        return new DesktopStorageLayout(root, root.resolve("windowstolinux.db"),
                root.resolve("managed-applications"), root.resolve("work"), root.resolve("backups"),
                root.resolve("diagnostics"));
    }

    /** Creates and validates the shared directories without using a fallback root. / 创建并验证共享目录且不使用回退根。 */
    public void initializeDirectories() throws IOException {
        Files.createDirectories(root);
        requireWritableDirectory(root);
        for (Path directory : List.of(managedApplicationsDirectory, workDirectory, backupsDirectory,
                diagnosticsDirectory)) {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
            requireWritableDirectory(directory);
        }
        if (Files.exists(databaseFile, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(databaseFile) || !Files.isRegularFile(databaseFile, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("desktop database path is not a regular file");
        }
    }

    /** Returns one managed application's local data root without creating it. / 返回一个受管应用的本地数据根但不创建。 */
    public Path managedApplicationDirectory(String applicationId) {
        return managedApplicationsDirectory.resolve(identifier(applicationId, "applicationId"));
    }

    /** Returns one local managed file binding directory without treating its logical path as physical. / 返回一个本地受管文件绑定目录且不把逻辑路径当作物理路径。 */
    public Path fileBindingDirectory(String applicationId, String bindingId) {
        return managedApplicationDirectory(applicationId).resolve("files")
                .resolve(identifier(bindingId, "bindingId"));
    }

    /** Returns one local managed database directory. / 返回一个本地受管数据库目录。 */
    public Path databaseDirectory(String applicationId, String databaseId) {
        return managedApplicationDirectory(applicationId).resolve("databases")
                .resolve(identifier(databaseId, "databaseId"));
    }

    /** Returns one validated SQLite file below its managed database directory. / 返回受管数据库目录下一个已校验的 SQLite 文件。 */
    public Path sqliteDatabaseFile(String applicationId, String databaseId, String fileName) {
        ManagedDatabaseConnection.Sqlite sqlite = new ManagedDatabaseConnection.Sqlite(fileName);
        return databaseDirectory(applicationId, databaseId).resolve(sqlite.fileName());
    }

    private static void requireWritableDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isWritable(directory)) {
            throw new IOException("desktop data layout contains an unavailable or unsafe directory");
        }
    }

    private static Path directChild(Path root, Path value, String expectedName, String field) {
        value = normalize(value, field);
        if (!value.equals(root.resolve(expectedName))) {
            throw new IllegalArgumentException(field + " must be the fixed direct child of the data root");
        }
        return value;
    }

    private static Path normalize(Path value, String field) {
        return Objects.requireNonNull(value, field).toAbsolutePath().normalize();
    }

    private static String identifier(String value, String field) {
        value = Objects.requireNonNull(value, field).trim();
        if (!value.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException(field + " must be a bounded managed identifier");
        }
        return value;
    }
}
