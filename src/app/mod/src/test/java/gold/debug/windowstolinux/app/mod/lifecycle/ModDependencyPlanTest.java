package gold.debug.windowstolinux.app.mod.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

import javax.tools.ToolProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.mod.loader.*;
import gold.debug.windowstolinux.app.mod.manifest.*;
import gold.debug.windowstolinux.app.mod.version.SemanticVersion;
import gold.debug.windowstolinux.appapi.mod.ModEntrypoint;
import gold.debug.windowstolinux.appapi.task.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModDependencyPlanTest {
    @TempDir
    Path root;

    static final SemanticVersion API = SemanticVersion.parse("1.0.0-dev.1");

    static ModManifest manifest(String id, String entryPackage, String version, Map<String, String> dependencies,
            String... exports) throws Exception {
        var data = new LinkedHashMap<String, Object>();
        data.put("formatVersion", 1);
        data.put("id", id);
        data.put("name", "Fixture");
        data.put("version", version);
        data.put("platformApiRange", API.toString());
        data.put("minJavaVersion", 21);
        data.put("mainJar", "main/fixture.jar");
        data.put("entryClass", entryPackage + ".Mod");
        data.put("dependencies", new TreeMap<>(dependencies).entrySet().stream()
                .map(e -> Map.of("id", e.getKey(), "versionRange", e.getValue())).toList());
        data.put("exports", List.of(exports));
        return ManifestReader.read(new ObjectMapper().writeValueAsBytes(data));
    }

    static ModManifest mod(String id, String... dependencies) throws Exception {
        var declared = new TreeMap<String, String>();
        for (var dependency : dependencies)
            declared.put(dependency, "1.0.0");
        return manifest(id, id, "1.0.0", declared);
    }

    static List<String> order(ModDependencyPlan plan) {
        return plan.startupOrder().stream().map(ModManifest::id).toList();
    }

    static String event(ModDependencyPlan plan, String owner) {
        return plan.failures().get(owner).eventCode();
    }

    @Test
    void diamondHasStableDependencyFirstOrderWithoutChangingSelections() throws Exception {
        var a = mod("example.a", "example.b", "example.c");
        var b = mod("example.b", "example.z");
        var c = mod("example.c", "example.z");
        var z = mod("example.z");
        var independent = mod("example.free");
        var input = new ArrayList<>(List.of(a, b, c, z, independent));
        var plan = ModDependencyPlan.resolve(input, API, 21);
        assertTrue(plan.failures().isEmpty());
        assertEquals(List.of("example.free", "example.z", "example.b", "example.c", "example.a"), order(plan));
        Collections.reverse(input);
        assertEquals(order(plan), order(ModDependencyPlan.resolve(input, API, 21)));
        assertThrows(UnsupportedOperationException.class, () -> plan.startupOrder().clear());
        assertEquals(5, input.size());
    }

    @Test
    void missingAndIncompatibleSelectionsBlockOnlyTheirTransitiveConsumers() throws Exception {
        var plan = ModDependencyPlan.resolve(
                List.of(mod("example.missing", "example.disabled"), mod("example.child", "example.missing"),
                        mod("example.version", "example.provider"),
                        manifest("example.provider", "example.provider", "1.0.0-rc.1", Map.of()), mod("example.free")),
                API, 21);
        assertEquals("MOD_DEPENDENCY_MISSING", event(plan, "example.missing"));
        assertEquals("MOD_DEPENDENCY_UNAVAILABLE", event(plan, "example.child"));
        assertEquals("example.missing", plan.failures().get("example.child").context().get("dependencyId"));
        assertEquals("MOD_DEPENDENCY_INCOMPATIBLE", event(plan, "example.version"));
        assertEquals(List.of("example.free", "example.provider"), order(plan));
        assertFalse(plan.failures().containsKey("example.disabled"));
    }

    @Test
    void cyclesIncludingSelfAndTheirConsumersDoNotBlockIndependentMods() throws Exception {
        var plan = ModDependencyPlan.resolve(List.of(mod("example.a", "example.b"), mod("example.b", "example.a"),
                mod("example.child", "example.a"), mod("example.self", "example.self"), mod("example.free")), API, 21);
        assertEquals(Set.of("example.a", "example.b", "example.child", "example.self"), plan.failures().keySet());
        for (var owner : plan.failures().keySet())
            assertEquals("MOD_DEPENDENCY_CYCLE", event(plan, owner));
        assertEquals(List.of("example.free"), order(plan));
        assertThrows(ApiException.class, () -> plan.createLoader("example.a", List.of(), Map.of()));
    }

    @Test
    void duplicateIdentityAndHostIncompatibilityRemainFailuresRatherThanSelectingAFallback() throws Exception {
        var first = mod("example.same");
        var second = manifest("example.same", "renamed.entry", "2.0.0", Map.of());
        var plan = ModDependencyPlan.resolve(List.of(first, second, mod("example.child", "example.same")), API, 21);
        assertEquals("MOD_SELECTION_AMBIGUOUS", event(plan, "example.same"));
        assertEquals("MOD_DEPENDENCY_UNAVAILABLE", event(plan, "example.child"));
        assertTrue(plan.startupOrder().isEmpty());
        assertEquals("MOD_HOST_INCOMPATIBLE", event(ModDependencyPlan.resolve(List.of(first), API, 20), first.id()));
        assertEquals("MOD_HOST_INCOMPATIBLE",
                event(ModDependencyPlan.resolve(List.of(first), SemanticVersion.parse("2.0.0"), 21), first.id()));
        assertThrows(UnsupportedOperationException.class, () -> plan.failures().clear());
    }

    @Test
    void publicPackageOverlapIsRejectedButSimilarTextualPrefixesAreIndependent() throws Exception {
        var first = manifest("example.one", "changed.root", "1.0.0", Map.of(), "changed.root.api");
        var second = manifest("example.two", "changed.root", "1.0.0", Map.of(), "changed.root.api.child");
        var conflict = mod("example.consumer", first.id(), second.id());
        var ownConflict = manifest("example.own", "changed.root.api.consumer", "1.0.0", Map.of(first.id(), "1.0.0"));
        var plan = ModDependencyPlan.resolve(List.of(first, second, conflict, ownConflict), API, 21);
        assertEquals("MOD_EXPORT_CONFLICT", event(plan, conflict.id()));
        assertEquals("MOD_EXPORT_CONFLICT", event(plan, ownConflict.id()));
        var unrelated = manifest("example.two", "changed.root", "1.0.0", Map.of(), "changed.root.api2");
        assertTrue(ModDependencyPlan.resolve(List.of(first, unrelated, conflict), API, 21).failures().isEmpty());
    }

    Path compile(String name, Map<String, String> sources, Path... dependencies) throws Exception {
        var classes = Files.createDirectories(root.resolve(name).resolve("classes"));
        var classpath = new ArrayList<String>();
        classpath.add(
                Path.of(ModEntrypoint.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        for (var dependency : dependencies)
            classpath.add(dependency.toString());
        var args = new ArrayList<>(List.of("--release", "21", "-proc:none", "-encoding", "UTF-8", "-classpath",
                String.join(java.io.File.pathSeparator, classpath), "-d", classes.toString()));
        for (var source : sources.entrySet()) {
            var path = root.resolve(name).resolve("sources").resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue());
            args.add(path.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        return classes;
    }

    @Test
    void realClassConsumersUseOriginalDeclaredProviderAndRejectMissingClosedOrChangedLoaders() throws Exception {
        var provider = manifest("stable.provider", "example.provider", "1.0.0", Map.of(), "example.provider.api");
        var consumer = manifest("stable.consumer", "example.consumer", "1.0.0", Map.of(provider.id(), "1.0.0"));
        var providerClasses = compile("provider", Map.of("example.provider.api.Echo",
                "package example.provider.api; public interface Echo { String value(); }", "example.provider.Private",
                "package example.provider; public class Private {}", "example.provider.Mod",
                "package example.provider; public final class Mod implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint,example.provider.api.Echo {public String value(){return \"actual\";}public void start(gold.debug.windowstolinux.appapi.mod.ModContext c){}public void stop(gold.debug.windowstolinux.appapi.mod.StopContext c){}}"));
        var consumerClasses = compile("consumer", Map.of("example.consumer.Mod",
                "package example.consumer; public final class Mod implements gold.debug.windowstolinux.appapi.mod.ModEntrypoint {public String call(example.provider.api.Echo e){return e.value();}public void start(gold.debug.windowstolinux.appapi.mod.ModContext c){}public void stop(gold.debug.windowstolinux.appapi.mod.StopContext c){}}"),
                providerClasses);
        var plan = ModDependencyPlan.resolve(List.of(consumer, provider), API, 21);
        var urls = List.of(consumerClasses.toUri().toURL());
        assertEquals(List.of(provider.id(), consumer.id()), order(plan));
        assertEquals("MOD_PROVIDER_CHANGED",
                assertThrows(ApiException.class, () -> plan.createLoader(consumer.id(), urls, Map.of())).problem()
                        .eventCode());
        try (var p = plan.createLoader(provider.id(), List.of(providerClasses.toUri().toURL()), Map.of());
                var c = plan.createLoader(consumer.id(), urls, Map.of(provider.id(), p))) {
            var shared = p.loadClass("example.provider.api.Echo");
            assertSame(shared, c.loadClass(shared.getName()));
            var entry = ModEntryFactory.create(c);
            assertEquals("actual", entry.getClass().getMethod("call", shared).invoke(entry, ModEntryFactory.create(p)));
            assertThrows(ClassNotFoundException.class, () -> c.loadClass("example.provider.Private"));
            var undeclared = manifest(consumer.id(), "example.consumer", "1.0.0", Map.of());
            var isolated = ModDependencyPlan.resolve(List.of(provider, undeclared), API, 21);
            try (var withoutDependency = isolated.createLoader(consumer.id(), urls, Map.of(provider.id(), p))) {
                assertThrows(ClassNotFoundException.class,
                        () -> withoutDependency.loadClass("example.provider.api.Echo"));
            }
            var changed = manifest(provider.id(), "example.other", "1.0.0", Map.of(), "example.other.api");
            try (var wrong = new ModClassLoader(changed, List.of(), Map.of())) {
                assertThrows(ApiException.class,
                        () -> plan.createLoader(consumer.id(), urls, Map.of(provider.id(), wrong)));
            }
            p.close();
            assertThrows(ApiException.class, () -> plan.createLoader(consumer.id(), urls, Map.of(provider.id(), p)));
        }
    }
}
