package gold.debug.windowstolinux.app.mod.development;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.mod.loader.*;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DevelopmentModDiscoveryTest {
    @TempDir
    Path temporary;

    final ObjectMapper json = new ObjectMapper();

    Path workspace() throws IOException {
        Path root = temporary.toRealPath().resolve("工作区 with spaces");
        for (String pom : List.of("pom.xml", "src/app/pom.xml", "src/mod/pom.xml")) {
            Files.createDirectories(root.resolve(pom).getParent());
            Files.writeString(root.resolve(pom), "<project/>\n");
        }
        return root;
    }

    Path module(Path root, String name, String id) throws Exception {
        Path module = root.resolve("src/mod/" + name);
        Files.createDirectories(module);
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("mod.json"),
                """
                        {"formatVersion":1,"id":"%s","name":"Local fixture","version":"1.0.0","platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/not-built.jar","entryClass":"%s.Mod"}
                        """
                        .formatted(id, id));
        compile(module, Map.of(id + ".Mod", """
                package %s;
                public final class Mod implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint {
                    static {System.setProperty("wtl.discovery.initialized","yes");}
                    public void start(gold.debug.windowstolinux.appapi.mod.ModContext c) {}
                    public void stop(gold.debug.windowstolinux.appapi.mod.StopContext c) {}
                    public String value() {return "current classes";}
                }
                """.formatted(id)));
        return module;
    }

    Path compile(Path module, Map<String, String> sources) throws Exception {
        Path output = module.resolve("target/classes");
        Files.createDirectories(output);
        var arguments = new ArrayList<>(List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-d",
                output.toString(), "-classpath",
                Path.of(ModEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
        for (var entry : sources.entrySet()) {
            Path file = module.resolve("src/main/java/" + entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            arguments.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return output;
    }

    Path generate(Path root, Path module) throws IOException {
        return DevelopmentDescriptor.generate(root, module.resolve("mod.json"), module.resolve("target/classes"),
                List.of(), List.of(root.resolve("pom.xml"), root.resolve("src/mod/pom.xml"), module.resolve("pom.xml")),
                List.of());
    }

    ObjectNode read(Path file) throws IOException {
        return (ObjectNode) json.readTree(Files.readAllBytes(file));
    }

    void write(Path file, ObjectNode value) throws IOException {
        Files.write(file, json.writeValueAsBytes(value));
    }

    @Test
    void discoversCompiledMainCompanionAndPrivateLibraryWithoutBuildingAMainJarOrExecutingCode() throws Exception {
        Path root = workspace();
        Path main = module(root, "main", "example.discovery");
        Path companion = root.resolve("src/mod/companion");
        var companionClasses = compile(companion, Map.of("example.companion.Value",
                "package example.companion; public final class Value { public static String get(){return \"companion\";} }"));
        Files.writeString(companion.resolve("pom.xml"), "<project/>\n");
        Files.writeString(companionClasses.resolve("companion.txt"), "processed 中文 resource");
        Path external = temporary.resolve("maven cache/library.jar");
        Files.createDirectories(external.getParent());
        try (var jar = new JarOutputStream(Files.newOutputStream(external))) {
            jar.putNextEntry(new JarEntry("library.txt"));
            jar.write("private library".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        DevelopmentDescriptor.generate(root, main.resolve("mod.json"), main.resolve("target/classes"),
                List.of(companionClasses), List.of(root.resolve("pom.xml"), root.resolve("src/mod/pom.xml"),
                        main.resolve("pom.xml"), companion.resolve("pom.xml")),
                List.of(external));
        System.clearProperty("wtl.discovery.initialized");
        var scan = DevelopmentModDiscovery.scan(root);
        assertTrue(scan.rejections().isEmpty(), scan.rejections().toString());
        assertEquals(1, scan.candidates().size());
        var candidate = scan.candidates().getFirst();
        assertNull(System.getProperty("wtl.discovery.initialized"));
        assertFalse(Files.exists(main.resolve("main/not-built.jar")));
        var sources = new ArrayList<java.net.URL>();
        for (var source : candidate.descriptor().sources())
            sources.add(source.toUri().toURL());
        try (var loader = new ModClassLoader(candidate.manifest(), sources, Map.of())) {
            assertEquals("companion", loader.loadClass("example.companion.Value").getMethod("get").invoke(null));
            for (String name : List.of("companion.txt", "library.txt"))
                try (var resource = Objects.requireNonNull(loader.getResource(name)).openStream()) {
                    assertEquals(name.equals("companion.txt") ? "processed 中文 resource" : "private library",
                            new String(resource.readAllBytes(), StandardCharsets.UTF_8));
                }
            var entry = ModEntryFactory.create(loader);
            assertEquals("current classes", entry.getClass().getMethod("value").invoke(entry));
            entry.start(null);
            entry.stop(null);
        } finally {
            System.clearProperty("wtl.discovery.initialized");
        }
        Files.delete(external);
    }

    @Test
    void explicitInputSetIgnoresDuplicateOrderButMissingAndCorruptDependenciesFail() throws Exception {
        Path root = workspace();
        Path main = module(root, "main", "example.inputs");
        Path descriptor = generate(root, main);
        String expected = DevelopmentDescriptor.read(root, descriptor).inputDigest();
        DevelopmentDescriptor.generate(root, main.resolve("mod.json"), main.resolve("target/classes"), List.of(),
                List.of(main.resolve("mod.json"), main.resolve("pom.xml"), root.resolve("src/mod/pom.xml"),
                        root.resolve("pom.xml"), main.resolve("pom.xml")),
                List.of());
        assertEquals(expected, DevelopmentDescriptor.read(root, descriptor).inputDigest());
        var valid = read(descriptor);
        Path broken = temporary.resolve("broken.jar");
        Files.writeString(broken, "not a JAR");
        for (Path bad : List.of(temporary.resolve("missing.jar"), broken)) {
            var invalid = valid.deepCopy();
            invalid.withArray("libraries").add(bad.toString());
            write(descriptor, invalid);
            assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
        }
        Path marker = main.resolve("target/classes/META-INF/wtlmod-development-input.sha256");
        Files.delete(marker);
        write(descriptor, valid);
        assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
    }

    @Test
    void changedManifestBuildInputLibraryAndCompiledMarkerRequireRecompilation() throws Exception {
        Path root = workspace();
        Path main = module(root, "main", "example.stale");
        Path library = temporary.resolve("dependency.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(library))) {
            jar.putNextEntry(new JarEntry("fixture.txt"));
            jar.write(new byte[]{1, 2, 3});
            jar.closeEntry();
        }
        Path descriptor = DevelopmentDescriptor.generate(root, main.resolve("mod.json"), main.resolve("target/classes"),
                List.of(), List.of(root.resolve("pom.xml"), root.resolve("src/mod/pom.xml"), main.resolve("pom.xml")),
                List.of(library));
        assertNotNull(DevelopmentDescriptor.read(root, descriptor));
        for (Path changed : List.of(main.resolve("mod.json"), main.resolve("pom.xml"), library,
                main.resolve("target/classes/META-INF/wtlmod-development-input.sha256"))) {
            byte[] original = Files.readAllBytes(changed);
            Files.write(changed, new byte[]{32}, StandardOpenOption.APPEND);
            assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor), changed.toString());
            Files.write(changed, original);
            assertNotNull(DevelopmentDescriptor.read(root, descriptor));
        }
        Files.delete(main.resolve("target/classes/example/stale/Mod.class"));
        assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
    }

    @Test
    void strictDescriptionRejectsMissingUnknownDuplicateNullTrailingAndMalformedFields() throws Exception {
        Path root = workspace();
        Path main = module(root, "main", "example.strict");
        Path descriptor = generate(root, main);
        var valid = read(descriptor);
        for (String field : List.of("formatVersion", "manifestPath", "mainOutput", "authorOutputs", "buildInputs",
                "libraries", "inputDigest")) {
            var missing = valid.deepCopy();
            missing.remove(field);
            write(descriptor, missing);
            assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
            write(descriptor, valid.deepCopy().putNull(field));
            assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
        }
        for (var invalid : List.of(valid.deepCopy().put("unknown", true), valid.deepCopy().put("formatVersion", 1.0),
                valid.deepCopy().put("formatVersion", 4294967297L), valid.deepCopy().put("mainOutput", "relative"),
                valid.deepCopy().put("authorOutputs", "wrong"), valid.deepCopy().put("inputDigest", "0"))) {
            write(descriptor, invalid);
            assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
        }
        for (String invalid : List.of(valid + " {}", valid.toString().replace("{", "{\"formatVersion\":1,"),
                "\ufeff" + valid)) {
            Files.writeString(descriptor, invalid);
            assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
        }
        Files.write(descriptor, new byte[]{(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
        Files.write(descriptor, new byte[1024 * 1024 + 1]);
        assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
    }

    @Test
    void rejectsForeignOutputsUndeclaredCompanionsAndIncompleteBuildProvenance() throws Exception {
        Path root = workspace();
        Path main = module(root, "main", "example.boundary");
        Path descriptor = generate(root, main);
        var valid = read(descriptor);
        Path sibling = module(root, "sibling", "example.sibling");
        var wrongMain = valid.deepCopy().put("mainOutput", sibling.resolve("target/classes").toString());
        var companion = valid.deepCopy();
        companion.withArray("authorOutputs").add(sibling.resolve("target/classes").toString());
        var duplicate = valid.deepCopy();
        duplicate.withArray("authorOutputs").add(main.resolve("target/classes").toString());
        var noRoot = valid.deepCopy();
        noRoot.withArray("buildInputs").removeAll().add(main.resolve("pom.xml").toString());
        var archivedInput = valid.deepCopy();
        Path archive = root.resolve("history-src/saved/pom.xml");
        Files.createDirectories(archive.getParent());
        Files.writeString(archive, "immutable history");
        archivedInput.withArray("buildInputs").add(archive.toString());
        Path oldLibrary = archive.getParent().resolve("old.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(oldLibrary))) {
            jar.putNextEntry(new JarEntry("retained.txt"));
            jar.closeEntry();
        }
        var archivedLibrary = valid.deepCopy();
        archivedLibrary.withArray("libraries").add(oldLibrary.toString());
        for (var invalid : List.of(wrongMain, companion, duplicate, noRoot, archivedInput, archivedLibrary)) {
            write(descriptor, invalid);
            assertThrows(IOException.class, () -> DevelopmentDescriptor.read(root, descriptor));
        }
        assertEquals("immutable history", Files.readString(archive));
        assertThrows(IOException.class, () -> DevelopmentModDiscovery.scan(Path.of(".")));
        assertThrows(IOException.class, () -> DevelopmentModDiscovery.scan(temporary));
    }

    @Test
    void missingDescriptionsAreReportedAndAllDuplicateIdentitiesAreRejectedIndependently() throws Exception {
        Path root = workspace();
        Path one = module(root, "one", "example.duplicate");
        Path two = module(root, "two", "example.duplicate");
        Path valid = module(root, "valid", "example.valid");
        Path missing = module(root, "uncompiled", "example.missing");
        generate(root, one);
        generate(root, two);
        generate(root, valid);
        var scan = DevelopmentModDiscovery.scan(root);
        assertEquals(List.of("example.valid"), scan.candidates().stream().map(c -> c.manifest().id()).toList());
        assertEquals(3, scan.rejections().size());
        assertEquals(2, scan.rejections().stream()
                .filter(r -> r.problem().eventCode().equals("MOD_DEVELOPMENT_AMBIGUOUS")).count());
        assertTrue(scan.rejections().stream().anyMatch(r -> r.source().equals(missing)));
        for (var failure : scan.rejections())
            assertTrue(failure.problem().diagnosticId().isPresent());
        Files.delete(two.resolve("target/wtlmod-development.json"));
        var partiallyCompiled = DevelopmentModDiscovery.scan(root);
        assertEquals(List.of("example.valid"),
                partiallyCompiled.candidates().stream().map(c -> c.manifest().id()).toList());
        assertTrue(partiallyCompiled.rejections().stream()
                .anyMatch(r -> r.source().equals(one) && r.problem().eventCode().equals("MOD_DEVELOPMENT_AMBIGUOUS")));
    }

    @Test
    void ignoresWorkingDirectoryArchivesAndBuildOutputCopies() throws Exception {
        Path root = workspace();
        Path main = module(root, "valid", "example.visible");
        generate(root, main);
        for (Path ignored : List.of(root.resolve("history-src/src/mod/copied"), main.resolve("target/copied"),
                root.resolve("src/mod/history-src/copied"), temporary.resolve("mod"))) {
            Files.createDirectories(ignored);
            Files.writeString(ignored.resolve("mod.json"), "invalid and must never be inspected");
        }
        var scan = DevelopmentModDiscovery.scan(root);
        assertEquals(1, scan.candidates().size());
        assertTrue(scan.rejections().isEmpty());
    }

    @Test
    void workspaceAnchorsAndGeneratedInputsCannotRedirectThroughWindowsJunctions() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path root = workspace();
        Path main = module(root, "main", "example.redirect");
        generate(root, main);
        Path foreign = temporary.resolve("foreign");
        Files.createDirectories(foreign);
        Path link = root.resolve("src/mod/redirected");
        var process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), foreign.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
        try {
            Files.writeString(foreign.resolve("mod.json"), "must not load");
            var scan = DevelopmentModDiscovery.scan(root);
            assertEquals(1, scan.candidates().size());
            assertTrue(scan.rejections().stream().anyMatch(r -> r.source().equals(link)));
            assertEquals("must not load", Files.readString(foreign.resolve("mod.json")));
        } finally {
            Files.delete(link);
        }
    }
}
