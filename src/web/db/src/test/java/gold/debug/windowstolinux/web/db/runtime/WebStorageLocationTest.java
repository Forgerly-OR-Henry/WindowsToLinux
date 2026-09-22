package gold.debug.windowstolinux.web.db.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebStorageLocationTest {
    @TempDir
    Path root;
    @Test
    void classLocationComesFromTheDbModuleAndNeverWorkingDirectory() throws Exception {
        var actual = WebStorageLocation.resolve("db.data");
        assertEquals(WebStorageLocation.Mode.CLASS, actual.mode());
        assertTrue(Files.readString(actual.root().getParent().resolve("pom.xml"))
                .contains("<artifactId>windowstolinux-web-db</artifactId>"));
        assertEquals("windowstolinuxweb.db", actual.database().getFileName().toString());
        assertEquals(actual.root().resolve("abc"), WebStorageLocation.resolve("db.data/abc").root());
        Path classes = Files.createDirectories(root.resolve("db-module/target/classes"));
        Files.writeString(root.resolve("db-module/pom.xml"), "<artifactId>windowstolinux-web-db</artifactId>");
        assertEquals(root.resolve("db-module/data"), WebStorageLocation.resolve("db.data", classes.toUri()).root());
    }

    @Test
    void externalJarUsesItsPhysicalParentRegardlessOfFilename() throws Exception {
        Path libraries = Files.createDirectory(root.resolve("lib"));
        Path jar = Files.createFile(libraries.resolve("db-custom-version.jar"));
        var location = WebStorageLocation.resolve("db.data/abc", jar.toUri());
        assertEquals(WebStorageLocation.Mode.JAR, location.mode());
        assertEquals(libraries.resolve("data/abc"), location.root());
        Path renamed = Files.move(jar, libraries.resolve("next-version.jar"));
        assertEquals(location.root(), WebStorageLocation.resolve("db.data/abc", renamed.toUri()).root());
    }

    @Test
    void invalidLegacyNestedAndUnknownLocationsFailWithoutFallback() throws Exception {
        for (String value : new String[]{"db/data", "data", "db.data/../../escape", "db.data\\abc"})
            assertThrows(IOException.class, () -> WebStorageLocation.resolve(value));
        assertThrows(IOException.class, () -> WebStorageLocation.resolve("db.data", root.toUri()));
        assertThrows(IOException.class,
                () -> WebStorageLocation.resolve("db.data", java.net.URI.create("jar:file:/outer.jar!/lib/db.jar")));
        assertEquals(root.resolve("absolute"), WebStorageLocation.resolve(root.resolve("absolute").toString()).root());
    }
}
