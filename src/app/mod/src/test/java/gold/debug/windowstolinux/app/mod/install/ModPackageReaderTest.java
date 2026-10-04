package gold.debug.windowstolinux.app.mod.install;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;
import org.apache.commons.compress.archivers.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ModPackageReaderTest {
    @TempDir
    Path root;

    byte[] entryClass;

    static final String ENTRY = "sample.bundle.Mod";

    static final String MARK = "wtl.package.reader.executed";

    static final String MANIFEST = """
        {"formatVersion":1,"id":"sample.bundle","name":"本机包","version":"1.0.0",
         "platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/primary.jar",
         "entryClass":"sample.bundle.Mod"}
        """;

    @BeforeEach
    void compile() throws Exception {
        System.clearProperty(MARK);
        var source = root.resolve("Mod.java");
        Files.writeString(source, """
            package sample.bundle;
            import gold.debug.windowstolinux.appapi.mod.ModContext; import gold.debug.windowstolinux.appapi.mod.ModEntrypoint; import gold.debug.windowstolinux.appapi.mod.lifecycle.StopContext;
            public class Mod implements ModEntrypoint {
                static {System.setProperty("wtl.package.reader.executed","initialized");}
                public Mod() {}
                public void start(ModContext context) {}
                public void stop(StopContext context) {}
            }
            """);
        var cp = Path.of(ModEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21",
            "-classpath", cp.toString(), "-d", root.toString(), source.toString()));
        entryClass = Files.readAllBytes(root.resolve("sample/bundle/Mod.class"));
    }

    @AfterEach
    void clear() {
        System.clearProperty(MARK);
    }

    byte[] jar(Map<String, byte[]> values) throws Exception {
        var result = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(result)) {
            for (var e : values.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return result.toByteArray();
    }

    LinkedHashMap<String, byte[]> files() throws Exception {
        var values = new LinkedHashMap<String, byte[]>();
        values.put("mod.json", MANIFEST.getBytes(StandardCharsets.UTF_8));
        values.put("README.md", "说明".getBytes(StandardCharsets.UTF_8));
        values.put("LICENSE", "MIT".getBytes(StandardCharsets.UTF_8));
        values.put("main/primary.jar",
            jar(Map.of("sample/bundle/Mod.class", entryClass, "assets/中文.txt", new byte[]{1, 2, 3})));
        return values;
    }

    Path archive(String name, Map<String, byte[]> values) throws Exception {
        return archive(name, values, false, false);
    }

    @Test
    void installedFileLimitDoesNotCountImplicitContainersAsArchiveEntries() throws Exception {
        var values = files();
        byte[] library = jar(Map.of());
        for (int i = values.size(); i < ModPackageReader.MAX_ENTRIES; i++)
            values.put("lib/library-" + i + ".jar", library);
        var inspected = ModPackageReader.read(archive("limit.wtlmod", values));
        Path program = root.resolve("limit-program");
        Files.createDirectories(program.resolve("main"));
        Files.createDirectory(program.resolve("lib"));
        for (var entry : values.entrySet())
            Files.write(program.resolve(entry.getKey()), entry.getValue());
        assertEquals(ModPackageReader.MAX_ENTRIES - 3,
            ModPackageReader.installedSources(program, inspected.manifest(), inspected.contentSha256()).size());
        Files.write(program.resolve("lib/over-limit.jar"), library);
        assertThrows(IOException.class,
            () -> ModPackageReader.installedSources(program, inspected.manifest(), inspected.contentSha256()));
        assertNull(System.getProperty(MARK));
    }

    @Test
    void hostClaimedEmptyDirectoryAcceptsExtractionButExistingContentIsNeverOverwritten() throws Exception {
        var source = archive("owned.wtlmod", files());
        var inspection = ModPackageReader.read(source);
        var stage = Files.createDirectory(root.resolve("owned-stage"));
        Object key = Files.readAttributes(stage, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        ModPackageReader.extractIntoEmpty(inspection, stage);
        assertEquals(key, Files.readAttributes(stage, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
        ModPackageReader.verifyDirectory(inspection, stage);
        Files.writeString(stage.resolve("README.md"), "preserved existing bytes");
        assertThrows(IOException.class, () -> ModPackageReader.extractIntoEmpty(inspection, stage));
        assertEquals("preserved existing bytes", Files.readString(stage.resolve("README.md")));
        assertThrows(IOException.class,
            () -> ModPackageReader.extractIntoEmpty(inspection, root.resolve("absent-stage")));
        assertFalse(Files.exists(root.resolve("absent-stage")));
        assertNull(System.getProperty(MARK));
    }

    Path archive(String name, Map<String, byte[]> values, boolean compressed, boolean zip64) throws Exception {
        Path path = root.resolve(name);
        try (var output = new ZipArchiveOutputStream(path)) {
            output.setUseZip64(zip64 ? Zip64Mode.Always : Zip64Mode.AsNeeded);
            for (var value : values.entrySet()) {
                var entry = new ZipArchiveEntry(value.getKey());
                entry.setMethod(compressed ? ZipEntry.DEFLATED : ZipEntry.STORED);
                var crc = new CRC32();
                crc.update(value.getValue());
                entry.setSize(value.getValue().length);
                entry.setCrc(crc.getValue());
                output.putArchiveEntry(entry);
                output.write(value.getValue());
                output.closeArchiveEntry();
            }
        }
        return path;
    }

    @Test
    void readsRealNestedJarsWithoutInitializationAndStagesOnlyOriginalFiles() throws Exception {
        var values = files();
        values.put("main/companion.jar", jar(Map.of("sample/companion.txt", new byte[]{5})));
        values.put("lib/private.jar", jar(Map.of("private.txt", new byte[]{6})));
        values.put("CHANGELOG.md", new byte[0]);
        var path = archive("用户重命名.wtlmod", values);
        var inspection = ModPackageReader.read(path);
        assertEquals("sample.bundle", inspection.manifest().id());
        assertEquals(List.of("main/primary.jar", "main/companion.jar", "lib/private.jar"), inspection.jars());
        assertEquals("main/primary.jar", inspection.classes().get(ENTRY));
        assertNull(System.getProperty(MARK));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))),
            inspection.archiveSha256());
        var stage = root.resolve("staged");
        var staged = ModPackageReader.extract(inspection, stage);
        assertEquals(inspection.files(), staged.files());
        assertEquals(inspection.archiveSha256(), staged.archiveSha256());
        assertEquals(inspection.contentSha256(), staged.contentSha256());
        assertEquals(inspection.manifest().json(), staged.manifest().json());
        for (var e : values.entrySet())
            assertArrayEquals(e.getValue(), Files.readAllBytes(stage.resolve(e.getKey())));
        assertFalse(Files.exists(stage.resolve("main/sample")));
        assertNull(System.getProperty(MARK));
        try (var loader = new ModClassLoader(inspection.manifest(), inspection.jars().stream().map(p -> {
            try {
                return stage.resolve(p).toUri().toURL();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }).toList(), Map.of())) {
            var type = loader.loadClass(ENTRY);
            assertNull(System.getProperty(MARK));
            assertTrue(ModEntrypoint.class.isAssignableFrom(type));
            type.getConstructor().newInstance();
            assertEquals("initialized", System.getProperty(MARK));
        }
        Files.move(stage.resolve("main/primary.jar"), stage.resolve("main/released.jar"));
        Files.move(path, root.resolve("released.wtlmod"));
    }

    @Test
    void archiveAdmissionRejectsDuckTypedEntryWithoutInitializingIt() throws Exception {
        String original = Files.readString(root.resolve("Mod.java"));
        String changed = original.replace("implements ModEntrypoint", "");
        assertNotEquals(original, changed);
        Files.writeString(root.resolve("Mod.java"), changed);
        var cp = Path.of(ModEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21",
            "-classpath", cp.toString(), "-d", root.toString(), root.resolve("Mod.java").toString()));
        entryClass = Files.readAllBytes(root.resolve("sample/bundle/Mod.class"));
        var originalPackage = archive("invalid-contract.wtlmod", files());
        assertTrue(assertThrows(IOException.class, () -> ModPackageReader.read(originalPackage)).getMessage()
            .contains("implement"));
        assertNull(System.getProperty(MARK));
    }

    @Test
    void contentDigestIgnoresArchiveOrderDirectoriesAndContainerMetadata() throws Exception {
        var values = files();
        var first = ModPackageReader.read(archive("one.wtlmod", values));
        var reversed = new LinkedHashMap<String, byte[]>();
        reversed.put("main/", new byte[0]);
        var names = new ArrayList<>(values.keySet());
        Collections.reverse(names);
        for (var key : names)
            reversed.put(key, values.get(key));
        var second = ModPackageReader.read(archive("two.wtlmod", reversed));
        assertEquals(first.contentSha256(), second.contentSha256());
        assertNotEquals(first.archiveSha256(), second.archiveSha256());
        var expected = MessageDigest.getInstance("SHA-256");
        expected.update("WTLMOD-CONTENT-1\n".getBytes(StandardCharsets.US_ASCII));
        for (var e : values.entrySet().stream()
            .sorted((a, b) -> Arrays.compareUnsigned(a.getKey().getBytes(StandardCharsets.UTF_8),
                b.getKey().getBytes(StandardCharsets.UTF_8)))
            .toList()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            expected.update(ByteBuffer.allocate(4).putInt(name.length).array());
            expected.update(name);
            expected.update(ByteBuffer.allocate(8).putLong(e.getValue().length).array());
            expected.update(MessageDigest.getInstance("SHA-256").digest(e.getValue()));
        }
        assertEquals(HexFormat.of().formatHex(expected.digest()), first.contentSha256());
        values.put("README.md", new byte[]{42});
        assertNotEquals(first.contentSha256(), ModPackageReader.read(archive("three.wtlmod", values)).contentSha256());
    }

    @Test
    void storedZip64IsAcceptedWithinTheSameLimits() throws Exception {
        assertEquals(ENTRY,
            ModPackageReader.read(archive("zip64.wtlmod", files(), false, true)).manifest().entryClass());
        var path = archive("zip64-local-mismatch.wtlmod", files(), false, true);
        byte[] raw = Files.readAllBytes(path);
        var bytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int extra = 30 + Short.toUnsignedInt(bytes.getShort(26));
        assertEquals(1, Short.toUnsignedInt(bytes.getShort(extra)));
        bytes.putLong(extra + 4, bytes.getLong(extra + 4) + 1);
        Files.write(path, raw);
        assertThrows(IOException.class, () -> ModPackageReader.read(path));
    }

    @Test
    void extensionOuterCompressionTruncationAndTrailingJunkAreRejected() throws Exception {
        var valid = archive("valid.wtlmod", files());
        assertThrows(IOException.class, () -> ModPackageReader.read(archive("invalid.zip", files())));
        assertThrows(IOException.class,
            () -> ModPackageReader.read(archive("compressed.wtlmod", files(), true, false)));
        byte[] bytes = Files.readAllBytes(valid);
        for (int removed : List.of(1, 22, bytes.length - 8)) {
            var truncated = root.resolve("truncated.wtlmod");
            Files.write(truncated, Arrays.copyOf(bytes, bytes.length - removed));
            assertThrows(IOException.class, () -> ModPackageReader.read(truncated));
        }
        Files.write(valid, new byte[]{1}, StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> ModPackageReader.read(valid));
    }

    @Test
    void requiredFilesRootLayoutAndManifestRulesAreEnforced() throws Exception {
        for (String missing : List.of("mod.json", "README.md", "LICENSE", "main/primary.jar")) {
            var values = files();
            values.remove(missing);
            assertThrows(IOException.class, () -> ModPackageReader.read(archive("missing.wtlmod", values)), missing);
        }
        for (String extra : List.of("other.txt", "wrapped/mod.json", "resources/icon.png", "main/nested/file.jar",
            "lib/nested/file.jar")) {
            var values = files();
            values.put(extra, new byte[]{1});
            assertThrows(IOException.class, () -> ModPackageReader.read(archive("layout.wtlmod", values)), extra);
        }
        var values = files();
        values.put("README.md", new byte[0]);
        var empty = archive("empty.wtlmod", values);
        assertThrows(IOException.class, () -> ModPackageReader.read(empty));
        values = files();
        values.put("mod.json", ("\ufeff" + MANIFEST).getBytes(StandardCharsets.UTF_8));
        var invalid = archive("bom.wtlmod", values);
        assertThrows(IOException.class, () -> ModPackageReader.read(invalid));
    }

    @Test
    void unsafeOuterPathsAndCaseOrUnicodeAliasesAreRejected() throws Exception {
        for (String unsafe : List.of("../escape.jar", "/absolute.jar", "C:/drive.jar", "//server/share.jar",
            "main\\escape.jar", "main//x.jar", "main/./x.jar", "main/../x.jar", "main/NUL.jar", "main/COM¹.jar",
            "main/x.jar.", "main/x.jar ", "main/a:b.jar")) {
            var values = files();
            values.put(unsafe, new byte[]{1});
            assertThrows(IOException.class, () -> ModPackageReader.read(archive("unsafe.wtlmod", values)), unsafe);
        }
        for (var pair : List.of(List.of("main/A.jar", "main/a.jar"), List.of("lib/é.jar", "lib/e\u0301.jar"))) {
            var values = files();
            for (var key : pair)
                values.put(key, jar(Map.of()));
            assertThrows(IOException.class, () -> ModPackageReader.read(archive("collision.wtlmod", values)));
        }
    }

    @Test
    void duplicateEntriesAndDeclaredLinksAreRejected() throws Exception {
        for (int mode : List.of(0, 0120777, 0020600)) {
            var path = archive("link.wtlmod", files());
            try (var out = new ZipArchiveOutputStream(path)) {
                for (var value : files().entrySet()) {
                    var original = new ZipArchiveEntry(value.getKey());
                    original.setMethod(ZipEntry.STORED);
                    original.setSize(value.getValue().length);
                    var checksum = new CRC32();
                    checksum.update(value.getValue());
                    original.setCrc(checksum.getValue());
                    out.putArchiveEntry(original);
                    out.write(value.getValue());
                    out.closeArchiveEntry();
                }
                {
                    var entry = new ZipArchiveEntry(mode == 0 ? "LICENSE" : "main/link.jar");
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(1);
                    var crc = new CRC32();
                    crc.update(new byte[]{1});
                    entry.setCrc(crc.getValue());
                    if (mode != 0)
                        entry.setUnixMode(mode);
                    out.putArchiveEntry(entry);
                    out.write(new byte[]{1});
                    out.closeArchiveEntry();
                }
            }
            String reason = assertThrows(IOException.class, () -> ModPackageReader.read(path)).getMessage();
            assertTrue(mode == 0 ? reason.contains("Duplicate") : reason.contains("link") || reason.contains("regular"),
                reason);
        }
    }

    @Test
    void actualPayloadCrcAndLocalHeaderNamesAreRechecked() throws Exception {
        var path = archive("tamper.wtlmod", files());
        byte[] original = Files.readAllBytes(path);
        var changed = original.clone();
        int offset;
        try (var zip = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(path).get()) {
            offset = (int) zip.getEntry("README.md").getDataOffset();
        }
        changed[offset] ^= 1;
        Files.write(path, changed);
        assertThrows(IOException.class, () -> ModPackageReader.read(path));
        changed = original.clone();
        changed[30] ^= 1;
        Files.write(path, changed);
        assertThrows(IOException.class, () -> ModPackageReader.read(path));
    }

    @Test
    void declaredCountAndArchiveSizeLimitsFailBeforeLargeAllocation() throws Exception {
        var path = archive("limit.wtlmod", files());
        byte[] bytes = Files.readAllBytes(path);
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int end = bytes.length - 22;
        buffer.putShort(end + 8, (short) 10001);
        buffer.putShort(end + 10, (short) 10001);
        Files.write(path, bytes);
        assertThrows(IOException.class, () -> ModPackageReader.read(path));
        var oversized = root.resolve("oversized.wtlmod");
        try (var file = java.nio.channels.FileChannel.open(oversized, StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            file.position(ModPackageReader.MAX_BYTES);
            file.write(ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> ModPackageReader.read(oversized));
    }

    @Test
    void nestedReservedClassesDuplicateOwnersAndInvalidEntryShapesAreRejected() throws Exception {
        for (String reserved : List.of("gold/debug/windowstolinux/appapi/Extra.class",
            "gold/debug/windowstolinux/app/Private.class", "org/h2/Driver.class", "java/lang/New.class",
            "javax/xml/parsers/DocumentBuilder.class", "META-INF/versions/21/org/h2/Driver.class")) {
            var values = files();
            values.put("lib/reserved.jar", jar(Map.of(reserved, new byte[]{1})));
            assertThrows(IOException.class, () -> ModPackageReader.read(archive("reserved.wtlmod", values)), reserved);
        }
        var values = files();
        values.put("main/other.jar", jar(Map.of("sample/bundle/Mod.class", entryClass)));
        var duplicate = archive("duplicate.wtlmod", values);
        assertThrows(IOException.class, () -> ModPackageReader.read(duplicate));
        values = files();
        values.put("main/primary.jar", jar(Map.of("sample/bundle/Mod.class", new byte[30])));
        var malformed = archive("malformed.wtlmod", values);
        assertThrows(IOException.class, () -> ModPackageReader.read(malformed));
        values = files();
        values.put("main/primary.jar", jar(Map.of("sample/Other.class", entryClass)));
        var absent = archive("absent.wtlmod", values);
        assertThrows(IOException.class, () -> ModPackageReader.read(absent));
        assertNull(System.getProperty(MARK));
    }

    @Test
    void jarCaseSensitiveResourcesAndSameJarMultiReleaseClassesRemainValid() throws Exception {
        var values = files();
        values.put("main/primary.jar",
            jar(Map.of("sample/bundle/Mod.class", entryClass, "META-INF/versions/21/sample/bundle/Mod.class",
                entryClass, "assets/a.txt", new byte[]{1}, "assets/A.txt", new byte[]{2}, "assets/CON.txt",
                new byte[]{3})));
        assertEquals(Map.of(ENTRY, "main/primary.jar"),
            ModPackageReader.read(archive("resources.wtlmod", values)).classes());
    }

    @Test
    void reviewDriftAndExistingStagingNeverOverwriteUserFiles() throws Exception {
        var values = files();
        var path = archive("review.wtlmod", values);
        var inspection = ModPackageReader.read(path);
        var stage = root.resolve("stage");
        Files.createDirectory(stage);
        Files.writeString(stage.resolve("keep.txt"), "keep");
        assertThrows(IOException.class, () -> ModPackageReader.extract(inspection, stage));
        assertEquals("keep", Files.readString(stage.resolve("keep.txt")));
        values.put("README.md", new byte[]{99});
        archive("review.wtlmod", values);
        var absent = root.resolve("never-created");
        assertThrows(IOException.class, () -> ModPackageReader.extract(inspection, absent));
        assertFalse(Files.exists(absent));
    }

    @Test
    void incompatibleDeclaredJavaOrApiDoesNotRunOrRejectWellFormedBytes() throws Exception {
        var values = files();
        values.put("mod.json", MANIFEST.replace("\"minJavaVersion\":21", "\"minJavaVersion\":99")
            .replace("1.0.0-dev.1", "99.0.0").getBytes(StandardCharsets.UTF_8));
        assertEquals(99, ModPackageReader.read(archive("future.wtlmod", values)).manifest().minJavaVersion());
        assertNull(System.getProperty(MARK));
    }

    @Test
    void realEntryVisibilityAbstractnessAndConstructorsAreCheckedWithoutLinking() throws Exception {
        String original = Files.readString(root.resolve("Mod.java"));
        for (String changed : List.of(original.replace("public class Mod", "class Mod"),
            original.replace("public class Mod", "public abstract class Mod"),
            original.replace("public Mod()", "private Mod()"))) {
            Files.writeString(root.resolve("Mod.java"), changed);
            var cp = Path.of(ModEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-proc:none", "--release", "21",
                "-classpath", cp.toString(), "-d", root.toString(), root.resolve("Mod.java").toString()));
            entryClass = Files.readAllBytes(root.resolve("sample/bundle/Mod.class"));
            var path = archive("shape.wtlmod", files());
            assertThrows(IOException.class, () -> ModPackageReader.read(path));
            assertNull(System.getProperty(MARK));
        }
    }

    @Test
    void invalidNestedZipAndEntryCrcCannotPassOuterIntegrityAlone() throws Exception {
        var values = files();
        values.put("lib/broken.jar", new byte[]{1, 2, 3});
        var broken = archive("broken.wtlmod", values);
        assertThrows(IOException.class, () -> ModPackageReader.read(broken));
        var nested = jar(Map.of("sample/bundle/Mod.class", entryClass));
        var bytes = ByteBuffer.wrap(nested).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < nested.length - 46; i++)
            if (bytes.getInt(i) == 0x02014b50) {
                bytes.putInt(i + 16, bytes.getInt(i + 16) ^ 1);
                break;
            }
        values = files();
        values.put("main/primary.jar", nested);
        var corrupted = archive("inner-crc.wtlmod", values);
        assertTrue(
            assertThrows(IOException.class, () -> ModPackageReader.read(corrupted)).getMessage().contains("CRC"));
    }

    @Test
    void redirectedOriginalAndStagingParentsAreRejectedWithoutTouchingTheirTargets() throws Exception {
        var path = archive("original.wtlmod", files());
        var inspection = ModPackageReader.read(path);
        var target = root.resolve("external");
        Files.createDirectory(target);
        Files.copy(path, target.resolve("original.wtlmod"));
        Files.writeString(target.resolve("keep.txt"), "keep");
        var link = root.resolve("redirected");
        if (System.getProperty("os.name").startsWith("Windows")) {
            var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), output);
        } else
            Files.createSymbolicLink(link, target);
        try {
            assertThrows(IOException.class, () -> ModPackageReader.read(link.resolve("original.wtlmod")));
            assertThrows(IOException.class, () -> ModPackageReader.extract(inspection, link.resolve("new-stage")));
            assertFalse(Files.exists(target.resolve("new-stage")));
            assertEquals("keep", Files.readString(target.resolve("keep.txt")));
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void jarManifestCannotAddUndeclaredSourcesToEitherPackageOrClassLoading() throws Exception {
        Files.write(root.resolve("foreign.jar"), jar(Map.of("outside.txt", new byte[]{7})));
        var original = files();
        var clean = ModPackageReader.read(archive("clean.wtlmod", original));
        byte[] bytes = jar(Map.of("sample/bundle/Mod.class", entryClass, "META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\r\nClass-Path: foreign.jar\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
        var source = root.resolve("implicit.jar");
        Files.write(source, bytes);
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{source.toUri().toURL()}, null)) {
            assertNotNull(loader.getResource("outside.txt"));
        }
        assertThrows(IllegalArgumentException.class,
            () -> new ModClassLoader(clean.manifest(), List.of(source.toUri().toURL()), Map.of()));
        original.put("main/primary.jar", bytes);
        var implicit = archive("implicit.wtlmod", original);
        assertTrue(assertThrows(IOException.class, () -> ModPackageReader.read(implicit)).getMessage()
            .contains("Class-Path"));
        assertNull(System.getProperty(MARK));
        Files.move(root.resolve("foreign.jar"), root.resolve("foreign-released.jar"));
        Files.move(source, root.resolve("implicit-released.jar"));
    }

    @Test
    void extractedTreeCanBeVerifiedWithoutTheArchiveAndRejectsMissingChangedOrAddedContent() throws Exception {
        var original = archive("original.wtlmod", files());
        var inspection = ModPackageReader.read(original);
        var directory = root.resolve("verified");
        ModPackageReader.extract(inspection, directory);
        Files.delete(original);
        ModPackageReader.verifyDirectory(inspection, directory);
        var durable = new ModPackageReader.Inspection(inspection.archive(), inspection.manifest(),
            inspection.archiveSha256(), inspection.contentSha256(), inspection.files(), List.of(), Map.of());
        ModPackageReader.verifyDirectory(durable, directory);
        var readme = directory.resolve("README.md");
        byte[] expected = Files.readAllBytes(readme);
        Files.writeString(readme, "tampered");
        assertThrows(IOException.class, () -> ModPackageReader.verifyDirectory(inspection, directory));
        Files.delete(readme);
        assertThrows(IOException.class, () -> ModPackageReader.verifyDirectory(inspection, directory));
        Files.write(readme, expected);
        var extra = directory.resolve("unknown.txt");
        Files.writeString(extra, "preserve");
        assertThrows(IOException.class, () -> ModPackageReader.verifyDirectory(inspection, directory));
        assertEquals("preserve", Files.readString(extra));
        Files.delete(extra);
        var subdirectory = directory.resolve("unknown");
        Files.createDirectory(subdirectory);
        assertThrows(IOException.class, () -> ModPackageReader.verifyDirectory(inspection, directory));
        Files.delete(subdirectory);
        ModPackageReader.verifyDirectory(inspection, directory);
        var changedEvidence = new ModPackageReader.Inspection(inspection.archive(), inspection.manifest(),
            inspection.archiveSha256(), "0".repeat(64), inspection.files(), inspection.jars(),
            inspection.classes());
        assertThrows(IOException.class, () -> ModPackageReader.verifyDirectory(changedEvidence, directory));
    }
}
