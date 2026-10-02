package gold.debug.windowstolinux.app.mod.load;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ModCodeInspectionTest {
    @TempDir
    Path root;

    int sequence;

    static final String MARK = "wtl.code.admission.initialized";

    static final String CONTRACT = "implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint";

    static final String METHODS = """
        public void start(gold.debug.windowstolinux.appapi.mod.ModContext c) {}
        public void stop(gold.debug.windowstolinux.appapi.mod.StopContext c) {}
        """;

    @AfterEach
    void neverExecutedAuthors() {
        try {
            assertNull(System.getProperty(MARK));
        } finally {
            System.clearProperty(MARK);
        }
    }

    ModManifest manifest(String owner, String entry, String exports, String dependencies) throws Exception {
        return ManifestReader.read(("""
            {"formatVersion":1,"id":"%s","name":"Code fixture","version":"1.0.0",
             "platformApiRange":"1.0.0-dev.1","minJavaVersion":21,"mainJar":"main/code.jar",
             "entryClass":"%s","exports":[%s],"dependencies":[%s]}
            """).formatted(owner, entry, exports, dependencies).getBytes(StandardCharsets.UTF_8));
    }

    ModManifest own(String exports) throws Exception {
        return manifest("sample.owner", "sample.owner.Mod", exports, "");
    }

    String entry(String pkg) {
        return "package " + pkg + "; public class Mod " + CONTRACT + " { static {System.setProperty(\"" + MARK
            + "\",\"ran\");} " + METHODS + "}";
    }

    Path compile(Map<String, String> sources, Path... dependencies) throws Exception {
        Path module = root.resolve("compile-" + sequence++), output = module.resolve("classes");
        Files.createDirectories(output);
        var cp = new ArrayList<String>();
        cp.add(Path.of(ModEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Arrays.stream(dependencies).map(Path::toString).forEach(cp::add);
        var args = new ArrayList<>(List.of("-proc:none", "--release", "21", "-encoding", "UTF-8", "-classpath",
            String.join(File.pathSeparator, cp), "-d", output.toString()));
        for (var source : sources.entrySet()) {
            Path file = module.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return output.toRealPath();
    }

    ModCodeInspection inspect(ModManifest manifest, Path... sources) throws Exception {
        return ModCodeInspection.read(manifest, List.of(sources));
    }

    @Test
    void provesInheritedEntryWithoutInitializingPrivateCompanionBase() throws Exception {
        Path base = compile(Map.of("privatebase.Base",
            "package privatebase; public class Base " + CONTRACT + " {" + METHODS + "}"));
        Path code = compile(
            Map.of("sample.owner.Mod", "package sample.owner; public class Mod extends privatebase.Base {"
                + "static {System.setProperty(\"" + MARK + "\",\"ran\");}}"),
            base);
        var inspection = inspect(own(""), code, base);
        inspection.validate(false, Map.of());
        inspection.validate(true, Map.of());
    }

    @Test
    void rejectsEntryThatOnlyHasMatchingMethodsAndDoesNotImplementContract() throws Exception {
        Path code = compile(Map.of("sample.owner.Mod", entry("sample.owner").replace(CONTRACT, "")));
        assertTrue(assertThrows(IOException.class, () -> inspect(own(""), code).validate(false, Map.of())).getMessage()
            .contains("implement"));
        try (var loader = new ModClassLoader(own(""), List.of(code.toUri().toURL()), Map.of())) {
            assertThrows(ReflectiveOperationException.class, () -> ModEntryFactory.create(loader));
        }
    }

    @Test
    void rejectsLeaksThroughFieldsArraysGenericsBoundsThrowsRecordsAndAnnotations() throws Exception {
        for (String api : List.of("public class Surface { public sample.owner.Hidden field; }",
            "public class Surface { protected sample.owner.Hidden[][] value(){return null;} }",
            "public class Surface { public java.util.Map<String,java.util.List<? extends sample.owner.Hidden>> value(){return null;} }",
            "public class Surface<T extends sample.owner.Hidden> {}",
            "public class Surface { public void value() throws sample.owner.Hidden {} }",
            "public record Surface(sample.owner.Hidden value) {}",
            "@sample.owner.api.Tag(sample.owner.Hidden.class) public class Surface {}",
            "public class Surface { public java.util.List<@sample.owner.HiddenTag String> value(){return null;} }")) {
            var sources = new HashMap<String, String>();
            sources.put("sample.owner.Mod", entry("sample.owner"));
            sources.put("sample.owner.Hidden", "package sample.owner; public class Hidden extends RuntimeException {}");
            sources.put("sample.owner.HiddenTag",
                "package sample.owner; @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) public @interface HiddenTag {}");
            sources.put("sample.owner.api.Tag", "package sample.owner.api; public @interface Tag {Class<?> value();}");
            sources.put("sample.owner.api.Surface", "package sample.owner.api; " + api);
            Path code = compile(sources);
            var failure = assertThrows(IOException.class,
                () -> inspect(own("\"sample.owner.api\""), code).validate(false, Map.of()), api);
            assertTrue(failure.getMessage().contains("signature"), failure.toString());
        }
    }

    @Test
    void rejectsPackagePrivateAndHiddenEnclosingTypesEvenInAnExportedPackage() throws Exception {
        for (String declaration : List.of("class Hidden {} public class Surface { public Hidden value; }",
            "class Hidden {public static class Nested {}} public class Surface { public Hidden.Nested value; }")) {
            Path code = compile(Map.of("sample.owner.Mod", entry("sample.owner"), "sample.owner.api.Surface",
                "package sample.owner.api; " + declaration));
            assertThrows(IOException.class, () -> inspect(own("\"sample.owner.api\""), code).validate(false, Map.of()));
        }
    }

    @Test
    void acceptsPublicGenericNestedRecordsAndDoesNotTreatPrivateMembersAsExports() throws Exception {
        Path code = compile(Map.of("sample.owner.Mod", entry("sample.owner"), "sample.owner.Hidden",
            "package sample.owner; public class Hidden {}", "sample.owner.api.Surface",
            """
                package sample.owner.api;
                public class Surface<T extends Number> {
                    private sample.owner.Hidden internal;
                    public record Value(String text, gold.debug.windowstolinux.appapi.mod.ModContext context) {}
                    public class Nested<U> {}
                    public Surface<Integer>.Nested<String> value(){return null;}
                    protected java.util.List<? super Value[]> exchange(java.util.Map<String,T> input) throws java.io.IOException {return null;}
                }
                """));
        var inspection = inspect(own("\"sample.owner.api\""), code);
        inspection.validate(false, Map.of());
        inspection.validate(true, Map.of());
    }

    @Test
    void resolvesDependenciesByExactManifestIdentityAndActualExportedTypes() throws Exception {
        var providerManifest = manifest("historical.identity", "provider.code.Mod", "\"provider.code.api\"", "");
        Path provider = compile(Map.of("provider.code.Mod", entry("provider.code"), "provider.code.api.Value",
            "package provider.code.api; public record Value(String value) {}", "provider.code.Private",
            "package provider.code; public class Private {}"));
        String dep = "{\"id\":\"historical.identity\",\"versionRange\":\"1.0.0\"}";
        var consumerManifest = manifest("sample.owner", "sample.owner.Mod", "\"sample.owner.api\"", dep);
        for (String type : List.of("provider.code.api.Value", "provider.code.Private")) {
            Path consumer = compile(Map.of("sample.owner.Mod", entry("sample.owner"), "sample.owner.api.Surface",
                "package sample.owner.api; public class Surface {public " + type + " value;}"), provider);
            var inspection = inspect(consumerManifest, consumer);
            inspection.validate(false, Map.of());
            assertThrows(IOException.class, () -> inspection.validate(true, Map.of()));
            assertThrows(IOException.class,
                () -> inspect(own("\"sample.owner.api\""), consumer).validate(false, Map.of()));
            var inspectedProvider = inspect(providerManifest, provider);
            inspectedProvider.validate(true, Map.of());
            if (type.endsWith("api.Value"))
                inspection.validate(true, Map.of("provider.code.api", inspectedProvider));
            else
                assertThrows(IOException.class,
                    () -> inspection.validate(true, Map.of("provider.code.api", inspectedProvider)));
        }
    }

    @Test
    void entryMayInheritTheContractThroughAnExplicitProvider() throws Exception {
        var providerManifest = manifest("historical.identity", "provider.code.Mod", "\"provider.code.api\"", "");
        Path provider = compile(Map.of("provider.code.Mod", entry("provider.code"), "provider.code.api.Base",
            "package provider.code.api; public class Base " + CONTRACT + " {" + METHODS + "}"));
        var manifest = manifest("sample.owner", "sample.owner.Mod", "",
            "{\"id\":\"historical.identity\",\"versionRange\":\"1.0.0\"}");
        Path consumer = compile(
            Map.of("sample.owner.Mod", "package sample.owner; public class Mod extends provider.code.api.Base {}"),
            provider);
        var inspection = inspect(manifest, consumer);
        inspection.validate(false, Map.of());
        assertThrows(IOException.class, () -> inspection.validate(true, Map.of()));
        var inspectedProvider = inspect(providerManifest, provider);
        inspectedProvider.validate(true, Map.of());
        inspection.validate(true, Map.of("provider.code.api", inspectedProvider));
    }

    Path jar(Map<String, byte[]> values, boolean multiRelease) throws Exception {
        Path path = root.resolve("jar-" + sequence++ + ".jar");
        var metadata = new Manifest();
        metadata.getMainAttributes().putValue("Manifest-Version", "1.0");
        if (multiRelease)
            metadata.getMainAttributes().putValue("Multi-Release", "true");
        try (var output = new JarOutputStream(Files.newOutputStream(path), metadata)) {
            for (var value : values.entrySet()) {
                output.putNextEntry(new JarEntry(value.getKey()));
                output.write(value.getValue());
                output.closeEntry();
            }
        }
        return path;
    }

    @Test
    void checksAllEnabledMultiReleaseVariantsButUsesCurrentOverlayForActivation() throws Exception {
        Path good = compile(Map.of("sample.owner.Mod", entry("sample.owner")));
        Path bad = compile(Map.of("sample.owner.Mod", entry("sample.owner").replace(CONTRACT, "")));
        var values = Map.of("sample/owner/Mod.class", Files.readAllBytes(good.resolve("sample/owner/Mod.class")),
            "META-INF/versions/25/sample/owner/Mod.class",
            Files.readAllBytes(bad.resolve("sample/owner/Mod.class")));
        var enabled = inspect(own(""), jar(values, true));
        assertThrows(IOException.class, () -> enabled.validate(false, Map.of()));
        enabled.validate(true, Map.of());
        inspect(own(""), jar(values, false)).validate(false, Map.of());
    }

    @Test
    void installsInspectableNewerClassVersionsButRefusesCurrentRuntimeActivation() throws Exception {
        Path code = compile(Map.of("sample.owner.Mod", entry("sample.owner")));
        Path file = code.resolve("sample/owner/Mod.class");
        byte[] bytes = Files.readAllBytes(file);
        bytes[7] = 71;
        Files.write(file, bytes);
        var inspection = inspect(own(""), code);
        inspection.validate(false, Map.of());
        assertThrows(IOException.class, () -> inspection.validate(true, Map.of()));
        bytes[7] = 72;
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> inspect(own(""), code));
    }

    @Test
    void rejectsMalformedIdentityDuplicateOwnedTypesAndExcessiveOrTruncatedBytes() throws Exception {
        Path code = compile(Map.of("sample.owner.Mod", entry("sample.owner")));
        Path entry = code.resolve("sample/owner/Mod.class");
        Files.copy(entry, entry.resolveSibling("Other.class"));
        assertThrows(IOException.class, () -> inspect(own(""), code));
        Files.delete(entry.resolveSibling("Other.class"));
        Path copy = root.resolve("copy");
        Files.createDirectories(copy.resolve("sample/owner"));
        Files.copy(entry, copy.resolve("sample/owner/Mod.class"));
        assertThrows(IOException.class, () -> inspect(own(""), code, copy));
        var small = new ModCodeInspection(own(""));
        assertThrows(IOException.class, () -> small.add("main", true, "sample/owner/Mod.class", 0,
            InputStream.nullInputStream(), ModCodeInspection.MAX_CLASS_BYTES + 1L));
        byte[] original = Files.readAllBytes(entry);
        Files.write(entry, Arrays.copyOf(original, original.length + 1));
        assertThrows(IOException.class, () -> inspect(own(""), code));
        Files.write(entry, Arrays.copyOf(original, 20));
        assertThrows(IOException.class, () -> inspect(own(""), code));
    }

    @Test
    void dependencyDeclarationsNeverPermitHostPrivateSignatureTypes() throws Exception {
        Path host = compile(Map.of("gold.debug.windowstolinux.app.internal.Hidden",
            "package gold.debug.windowstolinux.app.internal; public class Hidden {}"));
        Path consumer = compile(Map.of("sample.owner.Mod", entry("sample.owner"), "sample.owner.api.Surface",
                "package sample.owner.api; public class Surface {public gold.debug.windowstolinux.app.internal.Hidden field;}"),
            host);
        var manifest = manifest("sample.owner", "sample.owner.Mod", "\"sample.owner.api\"",
            "{\"id\":\"declared.provider\",\"versionRange\":\"1.0.0\"}");
        assertThrows(IOException.class, () -> inspect(manifest, consumer).validate(false, Map.of()));
        assertThrows(IOException.class, () -> inspect(manifest, consumer, host));
    }

    @Test
    void factoryRechecksExportLeaksBeforeConstructingTheAuthor() throws Exception {
        Path code = compile(Map.of("sample.owner.Mod", entry("sample.owner"), "sample.owner.Hidden",
            "package sample.owner; public class Hidden {}", "sample.owner.api.Surface",
            "package sample.owner.api; public class Surface {public sample.owner.Hidden field;}"));
        try (var loader = new ModClassLoader(own("\"sample.owner.api\""), List.of(code.toUri().toURL()), Map.of())) {
            assertThrows(ReflectiveOperationException.class, () -> ModEntryFactory.create(loader));
        }
    }
}
