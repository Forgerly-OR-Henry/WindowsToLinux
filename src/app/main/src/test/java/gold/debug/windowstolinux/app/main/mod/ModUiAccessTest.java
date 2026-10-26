package gold.debug.windowstolinux.app.main.mod;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.swing.*;
import javax.tools.ToolProvider;

import gold.debug.windowstolinux.app.mod.config.ManifestReader;
import gold.debug.windowstolinux.app.mod.load.ModClassLoader;
import gold.debug.windowstolinux.app.ui.mod.UiExtensionRegistry;
import gold.debug.windowstolinux.appapi.ui.extension.*;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModUiAccessTest {
    @TempDir
    Path root;

    private Path compile(String owner, Map<String, String> sources, Path dependency) throws Exception {
        var output = Files.createDirectories(root.resolve(owner).resolve("classes"));
        var source = Files.createDirectories(root.resolve(owner).resolve("source"));
        var args = new ArrayList<String>(List.of("--release", "21", "-d", output.toString(), "-classpath",
            System.getProperty("java.class.path") + (dependency == null ? "" : java.io.File.pathSeparator + dependency)));
        for (var entry : sources.entrySet()) {
            var file = source.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return output;
    }

    private ModClassLoader loader(String id, Path classes, boolean dependency, String range,
                                  Map<String, ModClassLoader> exports) throws Exception {
        var json = """
            {"formatVersion":1,"id":"%s","name":"UI fixture","version":"1.0.0","platformApiRange":"1.0.0",
             "minJavaVersion":21,"mainJar":"main/fixture.jar","entryClass":"%s.Mod"%s%s}
            """.formatted(id, id,
            id.equals("example.provider") ? ",\"exports\":[\"example.provider.api\"]" : "",
            dependency ? ",\"dependencies\":[{\"id\":\"example.provider\",\"versionRange\":\"" + range + "\"}]" : "");
        return new ModClassLoader(ManifestReader.read(json.getBytes(StandardCharsets.UTF_8)),
            List.of(classes.toUri().toURL()), exports);
    }

    private static final class Host implements UiExtensionHost {
        final JPanel panel = new JPanel();

        List<JComponent> children = List.of();

        public JComponent anchor() {
            return panel;
        }

        public void setContents(List<JComponent> children) {
            panel.removeAll();
            this.children = List.copyOf(children);
            children.forEach(panel::add);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void actualIsolatedModsRequireDeclaredCompatibleExportsAndRetainComponentsUntilCleanupSucceeds() throws Exception {
        var providerClasses = compile("provider", Map.of(
            "example.provider.Mod", "package example.provider; public final class Mod {}",
            "example.provider.api.Selection", "package example.provider.api; public record Selection(String id) {}",
            "example.provider.internal.Hidden", "package example.provider.internal; public record Hidden(String id) {}"), null);
        var consumerClasses = compile("consumer", Map.of(
            "example.consumer.Mod", """
                package example.consumer;
                import javax.swing.*;
                import java.util.*;
                import gold.debug.windowstolinux.appapi.ui.extension.*;
                import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
                import example.provider.api.Selection;
                public final class Mod {
                    public static boolean failClose;
                    public static int closes;
                    public static int clicks;
                    public static UiComponentFactory<Selection> factory() {
                        return () -> new UiComponent<>() {
                            final JTextField field = new JTextField("retained");
                            final java.awt.event.ActionListener listener = event -> clicks++;
                            public JComponent create(Optional<Selection> context, UiEnvironment environment) {
                                field.addActionListener(listener); field.setName(context.orElseThrow().id()); return field;
                            }
                            public void contextChanged(Optional<Selection> context) { field.setName(context.orElseThrow().id()); }
                            public void environmentChanged(UiEnvironment environment) {}
                            public void close() { closes++; if(failClose) throw new IllegalStateException("private"); field.removeActionListener(listener); }
                        };
                    }
                }
                """), providerClasses);
        try (var providerLoader = loader("example.provider", providerClasses, false, "", Map.of());
             var consumerLoader = loader("example.consumer", consumerClasses, true, "[1.0.0,2.0.0)",
                 Map.of("example.provider.api", providerLoader));
             var undeclaredLoader = loader("example.undeclared", consumerClasses, false, "", Map.of());
             var duplicateLoader = loader("example.provider", providerClasses, false, "", Map.of());
             var wrongVersionLoader = loader("example.consumer", consumerClasses, true, "2.0.0",
                 Map.of("example.provider.api", providerLoader))) {
            var type = (Class<Object>) providerLoader.loadClass("example.provider.api.Selection");
            assertSame(type, consumerLoader.loadClass(type.getName()));
            var consumerClass = consumerLoader.loadClass("example.consumer.Mod");
            var factory = (UiComponentFactory<Object>) consumerClass.getMethod("factory").invoke(null);
            var providerPolicy = new ModUiAccess(providerLoader);
            assertThrows(IllegalArgumentException.class, () -> providerPolicy.checkContextType(providerLoader.loadClass("example.provider.internal.Hidden"), true));
            var consumerPolicy = new ModUiAccess(consumerLoader);
            consumerPolicy.checkContextType(type, false);
            assertThrows(IllegalArgumentException.class, () -> consumerPolicy.checkContextType(type, true));
            var registry = new UiExtensionRegistry(new UiEnvironment(Locale.ENGLISH, "light", false));
            var provider = registry.begin("example.provider", false, providerPolicy);
            var consumer = registry.begin("example.consumer", false, consumerPolicy);
            var point = provider.provide("details.extra", type, UiExtensionKind.COMPONENT);
            var contribution = consumer.registerComponent(point.point(), new UiExtensionSpec("details", 0), factory);
            var undeclared = registry.begin("example.undeclared", false, new ModUiAccess(undeclaredLoader));
            assertThrows(ApiException.class, () -> undeclared.registerComponent(point.point(), new UiExtensionSpec("invalid", 0), factory));
            var wrong = registry.begin("example.wrong", false, new ModUiAccess(wrongVersionLoader));
            assertThrows(ApiException.class, () -> wrong.registerComponent(point.point(), new UiExtensionSpec("invalid", 0), factory));
            var duplicate = (Class<Object>) duplicateLoader.loadClass(type.getName());
            assertNotSame(type, duplicate);
            assertThrows(ApiException.class, () -> consumer.registerComponent(
                new UiExtensionPoint<>("example.provider", "details.extra", duplicate, UiExtensionKind.COMPONENT),
                new UiExtensionSpec("duplicate", 0), factory));
            provider.publish();
            consumer.publish();
            var value = type.getConstructor(String.class).newInstance("server-a");
            assertNotSame(value, new ModUiAccess(consumerLoader).copy(value));
            var host = new Host();
            point.mount(host, Optional.of(value));
            assertEquals(UiExtensionState.MOUNTED, contribution.state());
            var body = ((JLayer<?>) host.children.getFirst()).getView();
            assertEquals("server-a", body.getName());
            assertEquals(1, ((JTextField) body).getActionListeners().length);
            consumerClass.getField("failClose").setBoolean(null, true);
            assertThrows(ApiException.class, provider::close);
            assertEquals(UiExtensionState.FAILED, contribution.state());
            assertTrue(host.children.getFirst().getParent() != null);
            consumerClass.getField("failClose").setBoolean(null, false);
            provider.close();
            assertTrue(host.children.isEmpty());
            assertNull(body.getParent());
            assertEquals(0, ((JTextField) body).getActionListeners().length);
            consumer.close();
            undeclared.close();
            wrong.close();
            assertEquals(UiExtensionState.CLOSED, contribution.state());
            assertEquals(2, consumerClass.getField("closes").getInt(null));
            for (String fieldName : List.of("components", "authors", "bindings")) {
                var retained = UiExtensionRegistry.class.getDeclaredField(fieldName);
                retained.setAccessible(true);
                var values = (Map<?, ?>) retained.get(registry);
                if (fieldName.equals("bindings")) assertEquals(java.util.Set.of("platform"), values.keySet());
                else assertTrue(values.isEmpty(), fieldName);
            }
            assertThrows(ApiException.class, () -> consumer.registerComponent(point.point(), new UiExtensionSpec("after-close", 0), factory));
        }
    }

    @Test
    void liveSwingResourcesCannotCrossTheContextValueBoundary() throws Exception {
        var classes = compile("values", Map.of(
            "example.provider.Mod", "package example.provider; public final class Mod {}",
            "example.provider.api.BadContext", "package example.provider.api; public record BadContext(javax.swing.JButton button) {}"), null);
        try (var loader = loader("example.provider", classes, false, "", Map.of())) {
            var type = loader.loadClass("example.provider.api.BadContext");
            var policy = new ModUiAccess(loader);
            var value = type.getConstructor(JButton.class).newInstance(new JButton("private"));
            assertThrows(IllegalArgumentException.class, () -> policy.copy(value));
        }
    }
}
