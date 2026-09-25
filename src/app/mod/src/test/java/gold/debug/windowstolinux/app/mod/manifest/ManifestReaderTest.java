package gold.debug.windowstolinux.app.mod.manifest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ManifestReaderTest {
    static final ObjectMapper JSON = new ObjectMapper();

    static final String MINIMAL = """
            {"formatVersion":1,"id":"example.mod.demo","name":"示例","version":"1.0.0",
             "platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/demo.jar",
             "entryClass":"example.mod.demo.Mod"}
            """;
    ModManifest read(String text) throws IOException {
        return ManifestReader.read(text.getBytes(StandardCharsets.UTF_8));
    }

    ObjectNode node() throws IOException {
        return (ObjectNode) JSON.readTree(MINIMAL);
    }

    ModManifest read(ObjectNode node) throws IOException {
        return read(node.toString());
    }

    @Test
    void minimalAndFullManifestsRetainImmutableAuthorDeclarations() throws Exception {
        var minimal = read(MINIMAL);
        assertEquals("example.mod.demo", minimal.id());
        assertTrue(minimal.dependencies().isEmpty());
        assertTrue(minimal.schemaVersion().isEmpty());
        assertEquals(MINIMAL, minimal.json());
        var node = node();
        node.put("name", "😀".repeat(128));
        node.put("description", "Description");
        node.put("homepage", "https://example.com/mod");
        node.putArray("authors").add("作者");
        node.putArray("exports").add("example.mod.demo.api");
        node.put("schemaVersion", "3");
        node.putArray("dependencies").addObject().put("id", "example.mod.provider").put("versionRange",
                "[1.0.0,2.0.0)");
        var migrations = node.putArray("migrations");
        migrations.addObject().put("from", "1").put("to", "2").put("class", "example.mod.demo.db.First");
        migrations.addObject().put("from", "2").put("to", "3").put("class", "example.mod.demo.db.Second");
        var full = read(node);
        assertEquals(2, full.migrations().size());
        assertEquals(1, full.dependencies().size());
        assertEquals("https", full.homepage().orElseThrow().getScheme());
        assertThrows(UnsupportedOperationException.class, () -> full.authors().add("changed"));
        node.put("name", "changed");
        assertEquals("😀".repeat(128), full.name());
    }

    @Test
    void jsonEncodingDuplicateUnknownNullAndTrailingContentAreRejected() throws Exception {
        for (var invalid : new String[]{"\ufeff" + MINIMAL, MINIMAL + "{}",
                MINIMAL.replace("\"name\":\"示例\"", "\"name\":\"one\",\"name\":\"two\""), "// comment\n" + MINIMAL,
                "[]"})
            assertThrows(IOException.class, () -> read(invalid));
        assertThrows(IOException.class, () -> ManifestReader.read(new byte[]{(byte) 0xc0, (byte) 0xaf}));
        assertThrows(IOException.class, () -> ManifestReader.read(new byte[ManifestReader.MAX_BYTES + 1]));
        var unknown = node().put("enabled", true);
        assertThrows(IOException.class, () -> read(unknown));
        var explicitNull = node().putNull("homepage");
        assertThrows(IOException.class, () -> read(explicitNull));
        var wrongType = node().put("minJavaVersion", "21");
        assertThrows(IOException.class, () -> read(wrongType));
        var decimal = node().put("formatVersion", 1.0);
        assertThrows(IOException.class, () -> read(decimal));
        var missing = node();
        missing.remove("entryClass");
        assertThrows(IOException.class, () -> read(missing));
    }

    @Test
    void javaIdentitiesPathsHomepageAndTextBoundsAreCheckedBeforeLoading() throws Exception {
        for (var identity : new String[]{"example..mod", "example.class.mod", " example.mod", "example/mod",
                "example.mod."})
            assertThrows(IOException.class, () -> read(node().put("id", identity)));
        for (var path : new String[]{"../demo.jar", "main/sub/demo.jar", "main/demo\\test.jar", "main/CON.jar",
                "main/demo:stream.jar", "main/demo?.jar"})
            assertThrows(IOException.class, () -> read(node().put("mainJar", path)));
        assertThrows(IOException.class, () -> read(node().put("entryClass", "example.mod.Entry")));
        assertThrows(IOException.class, () -> read(node().put("name", "😀".repeat(129))));
        for (var uri : new String[]{"http://example.com", "https://user:password@example.com", "/relative",
                "https:example.com"})
            assertThrows(IOException.class, () -> read(node().put("homepage", uri)));
        var outside = node();
        outside.putArray("exports").add("another.mod.api");
        assertThrows(IOException.class, () -> read(outside));
    }

    @Test
    void stableIdentityCanSurviveAnExplicitEntryPackageChange() throws Exception {
        var moved = node().put("entryClass", "example.renamed.demo.Mod");
        moved.putArray("exports").add("example.renamed.demo.api");
        var manifest = read(moved);
        assertEquals("example.mod.demo", manifest.id());
        assertEquals("example.renamed.demo", manifest.rootPackage());
    }

    @Test
    void duplicateAndOversizedDeclarationsAreRejected() throws Exception {
        var duplicate = node();
        var dependencies = duplicate.putArray("dependencies");
        for (int i = 0; i < 2; i++)
            dependencies.addObject().put("id", "example.mod.provider").put("versionRange", "1.0.0");
        assertThrows(IOException.class, () -> read(duplicate));
        var exports = node();
        exports.putArray("exports").add("example.mod.demo.api").add("example.mod.demo.api");
        assertThrows(IOException.class, () -> read(exports));
        var tooMany = node();
        var authors = tooMany.putArray("authors");
        for (int i = 0; i < 33; i++)
            authors.add("Author " + i);
        assertThrows(IOException.class, () -> read(tooMany));
        var extraField = node();
        extraField.putArray("dependencies").addObject().put("id", "example.mod.provider").put("versionRange", "1.0.0")
                .put("optional", true);
        assertThrows(IOException.class, () -> read(extraField));
    }

    @Test
    void migrationVersionsUseArbitraryPrecisionAndStrictIncreasingEdges() throws Exception {
        var large = "9".repeat(64);
        var valid = node().put("schemaVersion", large);
        valid.putArray("migrations").addObject().put("from", "9223372036854775808").put("to", large).put("class",
                "example.mod.demo.db.Upgrade");
        assertEquals(large, read(valid).schemaVersion().orElseThrow());
        for (var bad : new String[]{"0", "01", "-1", "9".repeat(65)})
            assertThrows(IOException.class, () -> read(node().put("schemaVersion", bad)));
        var backwards = node().put("schemaVersion", "3");
        backwards.putArray("migrations").addObject().put("from", "2").put("to", "1").put("class",
                "example.mod.demo.db.Upgrade");
        assertThrows(IOException.class, () -> read(backwards));
        var missing = node();
        missing.putArray("migrations");
        assertThrows(IOException.class, () -> read(missing));
        var repeated = node().put("schemaVersion", "2");
        var migrations = repeated.putArray("migrations");
        for (int i = 0; i < 2; i++)
            migrations.addObject().put("from", "1").put("to", "2").put("class", "example.mod.demo.db.Upgrade");
        assertThrows(IOException.class, () -> read(repeated));
    }

    @Test
    void runtimeCompatibilityIsDeferredForStructurallyValidPackages() throws Exception {
        var future = read(node().put("minJavaVersion", 999).put("platformApiRange", "[99.0.0,100.0.0)"));
        assertEquals(999, future.minJavaVersion());
    }
}
