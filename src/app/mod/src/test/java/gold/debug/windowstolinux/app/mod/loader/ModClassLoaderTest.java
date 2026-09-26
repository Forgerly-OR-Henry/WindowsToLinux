package gold.debug.windowstolinux.app.mod.loader;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;

import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.mod.manifest.ManifestReader;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pf4j.*;

class ModClassLoaderTest {
    @TempDir
    Path root;
    ModClassLoader loader(String id, List<URL> sources, Map<String, ModClassLoader> exports) throws Exception {
        String root = id.contains(".") ? id : "example." + id;
        String manifest = "{\"formatVersion\":1,\"id\":\"" + root
                + "\",\"name\":\"fixture\",\"version\":\"1.0.0\",\"platformApiRange\":\"1.0.0-dev.1\",\"minJavaVersion\":21,\"mainJar\":\"main/fixture.jar\",\"entryClass\":\""
                + root + ".Mod\"}";
        return new ModClassLoader(ManifestReader.read(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                sources, exports);
    }

    Path compile(String name, Map<String, String> sources, Path... dependencies) throws Exception {
        var out = root.resolve(name).resolve("classes");
        Files.createDirectories(out);
        var classpath = new ArrayList<String>();
        classpath.add(
                Path.of(ModEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        for (var dependency : dependencies) {
            assertTrue(Files.isRegularFile(dependency.resolve("example/provider/api/Echo.class")),
                    dependency.toString());
            classpath.add(dependency.toAbsolutePath().toString());
        }
        var arguments = new ArrayList<String>(List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-d",
                out.toString(), "-classpath", String.join(File.pathSeparator, classpath)));
        for (var entry : sources.entrySet()) {
            var file = root.resolve(name).resolve("src").resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            arguments.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(String[]::new)));
        return out;
    }

    Path jar(Path classes, String name) throws Exception {
        var path = root.resolve(name + ".jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(path)); var files = Files.walk(classes)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        return path;
    }

    @Test
    void classAndJarShareOnlyExportedTypesAndReleaseAllJarHandles() throws Exception {
        var provider = compile("provider", Map.of("example.provider.api.Echo",
                "package example.provider.api; public interface Echo { String value(); }",
                "example.provider.PrivateService", "package example.provider; public final class PrivateService {}",
                "privatevendor.Value",
                "package privatevendor; public final class Value { public static String get(){return \"provider\";} }",
                "example.provider.Mod",
                "package example.provider; public final class Mod implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint, example.provider.api.Echo { public String value(){ return privatevendor.Value.get(); } public void start(gold.debug.windowstolinux.appapi.mod.ModContext c){} public void stop(gold.debug.windowstolinux.appapi.mod.StopContext c){} }"));
        var consumer = compile("consumer", Map.of("privatevendor.Value",
                "package privatevendor; public final class Value { public static String get(){return \"consumer\";} }",
                "example.consumer.Mod",
                "package example.consumer; public final class Mod implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint { public String call(example.provider.api.Echo e) {return e.value()+\":\"+privatevendor.Value.get();} public void start(gold.debug.windowstolinux.appapi.mod.ModContext c){} public void stop(gold.debug.windowstolinux.appapi.mod.StopContext c){} }"),
                provider);
        var companion = compile("companion", Map.of("example.consumer.extra.Companion",
                "package example.consumer.extra; public final class Companion {}"));
        Files.writeString(companion.resolve("companion.txt"), "resource bytes 中文");
        Files.writeString(consumer.resolve("shared.txt"), "main");
        Files.writeString(companion.resolve("shared.txt"), "companion");
        Files.writeString(provider.resolve("example/provider/api/public.txt"), "provider-api");
        var providerJar = jar(provider, "provider");
        var consumerJar = jar(consumer, "consumer");
        var companionJar = jar(companion, "companion");
        for (boolean packaged : List.of(false, true)) {
            try (var p = loader("provider", List.of((packaged ? providerJar : provider).toUri().toURL()), Map.of());
                    var c = loader("consumer",
                            List.of((packaged ? consumerJar : consumer).toUri().toURL(),
                                    (packaged ? companionJar : companion).toUri().toURL()),
                            Map.of("example.provider.api", p))) {
                var api = p.loadClass("example.provider.api.Echo");
                assertSame(api, c.loadClass(api.getName()));
                var mod = ModEntryFactory.create(c);
                assertInstanceOf(ModEntrypoint.class, mod);
                assertEquals("provider:consumer",
                        mod.getClass().getMethod("call", api).invoke(mod, ModEntryFactory.create(p)));
                assertNotSame(p.loadClass("privatevendor.Value"), c.loadClass("privatevendor.Value"));
                assertThrows(ClassNotFoundException.class, () -> c.loadClass("example.provider.PrivateService"));
                assertThrows(ClassNotFoundException.class,
                        () -> c.loadClass("gold.debug.windowstolinux.app.mod.loader.ModClassLoaderTest"));
                assertNotNull(Class.forName("org.h2.Driver"));
                assertThrows(ClassNotFoundException.class, () -> c.loadClass("org.h2.Driver"));
                assertThrows(ClassNotFoundException.class, () -> c.loadClass("org.pf4j.Plugin"));
                assertEquals("example.consumer.extra.Companion",
                        c.loadClass("example.consumer.extra.Companion").getName());
                try (var stream = c.getResource("companion.txt").openStream()) {
                    assertEquals("resource bytes 中文",
                            new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                assertNull(c.getResource("gold/debug/windowstolinux/app/mod/loader/ModClassLoaderTest.class"));
                assertNotNull(c.getResource("gold/debug/windowstolinux/appapi/mod/ModEntrypoint.class"));
                var resources = new ArrayList<String>();
                var found = c.getResources("shared.txt");
                while (found.hasMoreElements())
                    try (var stream = found.nextElement().openStream()) {
                        resources.add(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                assertEquals(List.of("main", "companion"), resources);
                try (var stream = c.getResource("example/provider/api/public.txt").openStream()) {
                    assertEquals("provider-api",
                            new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
                ((ModEntrypoint) mod).start(null);
                ((ModEntrypoint) mod).stop(null);
            }
        }
        Files.delete(providerJar);
        Files.delete(consumerJar);
        Files.delete(companionJar);
        assertFalse(Files.exists(providerJar));
    }

    @Test
    void entryInspectionDoesNotExecuteStaticInitializersAndRejectsWrongShape() throws Exception {
        var path = compile("invalid", Map.of("example.bad.Mod",
                "package example.bad; public class Mod {static {System.setProperty(\"phase11.invalid.entry\",\"executed\");} }"));
        try (var loader = loader("bad", List.of(path.toUri().toURL()), Map.of())) {
            var type = Class.forName("example.bad.Mod", false, loader);
            assertFalse(ModEntrypoint.class.isAssignableFrom(type));
            assertThrows(ReflectiveOperationException.class, () -> ModEntryFactory.create(loader));
            assertNull(System.getProperty("phase11.invalid.entry"));
        }
    }

    PluginDescriptor descriptor(String id, String... dependencies) {
        var value = new DefaultPluginDescriptor(id, "", "", "1.0.0", "*", "", "");
        for (var dep : dependencies)
            value.addDependency(new PluginDependency(dep));
        return value;
    }

    @Test
    void frameworkResolvesTopologicalOrderAndRejectsMissingAndCyclicDependencies() {
        var graph = new DependencyResolver(new DefaultVersionManager());
        var valid = graph.resolve(List.of(descriptor("consumer", "provider"), descriptor("provider")));
        assertTrue(valid.isOK());
        assertTrue(valid.getSortedPlugins().indexOf("provider") < valid.getSortedPlugins().indexOf("consumer"));
        assertTrue(new DependencyResolver(new DefaultVersionManager())
                .resolve(List.of(descriptor("consumer", "missing"))).hasNotFoundDependencies());
        assertTrue(new DependencyResolver(new DefaultVersionManager())
                .resolve(List.of(descriptor("a", "b"), descriptor("b", "a"))).hasCyclicDependency());
    }

    @Test
    void closedLoaderDoesNotRemainUsable() throws Exception {
        var loader = loader("empty", List.of(), Map.of());
        loader.close();
        loader.close();
        assertTrue(loader.isClosed());
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("java.lang.String"));
    }

    @Test
    void overlappingExportsAreRejectedInsteadOfUsingDeclarationOrder() throws Exception {
        try (var provider = loader("example.provider", List.of(), Map.of())) {
            assertThrows(IllegalArgumentException.class,
                    () -> loader("example.consumer", List.of(), Map.of("example.consumer.api", provider)));
            assertThrows(IllegalArgumentException.class, () -> loader("example.consumer", List.of(),
                    Map.of("example.provider.api", provider, "example.provider.api.nested", provider)));
        }
    }

    @Test
    void resourceTraversalAndOpeningAfterCloseAreRejected() throws Exception {
        Files.writeString(root.resolve("note.txt"), "fixture");
        URL resource;
        try (var loader = loader("resource", List.of(root.toUri().toURL()), Map.of())) {
            resource = loader.getResource("note.txt");
            assertNotNull(resource);
            for (var name : List.of("../note.txt", "/note.txt",
                    "gold/debug/windowstolinux/appapi/../app/main/AppMain.class",
                    "gold\\debug\\windowstolinux\\api\\mod\\ModEntrypoint.class")) {
                assertNull(loader.getResource(name));
                assertFalse(loader.getResources(name).hasMoreElements());
            }
        }
        assertThrows(java.io.IOException.class, resource::openStream);
    }

    @Test
    void remoteCodeSourcesCannotBypassTheValidatedLocalGraph() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> loader("remote", List.of(java.net.URI.create("https://example.com/mod.jar").toURL()), Map.of()));
    }

    @Test
    void entryMustBePublicConcreteAndDefinedByTheExplicitMainSource() throws Exception {
        String methods = "public void start(gold.debug.windowstolinux.appapi.mod.ModContext c){} public void stop(gold.debug.windowstolinux.appapi.mod.StopContext c){}";
        for (var shape : List.of("class Mod", "public abstract class Mod", "public class Mod")) {
            var name = "shape" + Math.abs(shape.hashCode());
            var body = shape + " implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint {"
                    + (shape.equals("public class Mod") ? "private Mod(){}" : "") + methods + "}";
            var output = compile(name, Map.of("example.shape.Mod", "package example.shape; " + body));
            try (var loader = loader("shape", List.of(output.toUri().toURL()), Map.of())) {
                assertThrows(ReflectiveOperationException.class, () -> ModEntryFactory.create(loader));
            }
        }
        var main = root.resolve("empty-main");
        Files.createDirectories(main);
        var companion = compile("misplaced", Map.of("example.misplaced.Mod",
                "package example.misplaced; public class Mod implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint {"
                        + methods + "}"));
        try (var loader = loader("misplaced", List.of(main.toUri().toURL(), companion.toUri().toURL()), Map.of())) {
            assertThrows(ReflectiveOperationException.class, () -> ModEntryFactory.create(loader));
        }
    }
}
