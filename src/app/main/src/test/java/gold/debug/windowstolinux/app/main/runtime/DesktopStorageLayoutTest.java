package gold.debug.windowstolinux.app.main.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the fixed data children derived only from the resolved runtime root. / 测试仅从解析运行根派生的固定数据子项。 */
class DesktopStorageLayoutTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void derivesAndInitializesEverySharedDirectoryBelowTheResolvedDataRoot() throws Exception {
        Path root = temporaryDirectory.resolve("installation").resolve("data");
        DesktopStorageLayout layout = DesktopStorageLayout.from(root);

        layout.initializeDirectories();

        assertEquals(root.toAbsolutePath(), layout.root());
        assertEquals(layout.root().resolve("windowstolinux.db"), layout.databaseFile());
        assertTrue(Files.isDirectory(layout.managedApplicationsDirectory()));
        assertTrue(Files.isDirectory(layout.workDirectory()));
        assertTrue(Files.isDirectory(layout.backupsDirectory()));
        assertTrue(Files.isDirectory(layout.diagnosticsDirectory()));
        assertEquals(layout.root().resolve("managed-applications/demo/files/file-uploads"),
                layout.fileBindingDirectory("demo", "file-uploads"));
        assertEquals(layout.root().resolve("managed-applications/demo/databases/orders/application.db"),
                layout.sqliteDatabaseFile("demo", "orders", "application.db"));
    }

    @Test
    void rejectsUnsafeIdentifiersAndDatabaseFilePathSyntax() {
        DesktopStorageLayout layout = DesktopStorageLayout.from(temporaryDirectory.resolve("data"));

        assertThrows(IllegalArgumentException.class,
                () -> layout.fileBindingDirectory("../outside", "file-one"));
        assertThrows(IllegalArgumentException.class,
                () -> layout.sqliteDatabaseFile("demo", "orders", "../outside.db"));
    }

    @Test
    void stopsWhenAFixedDirectoryNameIsOccupiedByAFile() throws Exception {
        DesktopStorageLayout layout = DesktopStorageLayout.from(temporaryDirectory.resolve("data"));
        Files.createDirectories(layout.root());
        Files.writeString(layout.backupsDirectory(), "not a directory");

        assertThrows(IOException.class, layout::initializeDirectories);
    }
}
