package gold.debug.windowstolinux.mod.deployment.service.mirror;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;

class DeploymentMirrorRequirementsTest {
    private Path root;
    @BeforeEach void directory() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (project != null && !Files.isDirectory(project.resolve(".ai-workspace"))) project = project.getParent();
        if (project == null) throw new IOException("Project workspace unavailable");
        root = Files.createDirectories(project.resolve(".ai-workspace/tmp/deployment-mirrors-20261104/requirements/junit-" + UUID.randomUUID()));
    }
    @AfterEach void cleanup() throws Exception {
        if (root != null) try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    private static Set<DownloadPoint> systems() {
        return Set.of(DownloadPoint.APT_DEBIAN, DownloadPoint.APT_UBUNTU, DownloadPoint.DNF_FEDORA,
            DownloadPoint.DNF_ROCKY, DownloadPoint.DNF_ALMA, DownloadPoint.DNF_CENTOS_STREAM, DownloadPoint.DNF_ORACLE);
    }
    private Path marker(String directory, String name, String text) throws Exception {
        Path component = Files.createDirectories(root.resolve(directory));
        Files.writeString(component.resolve(name), text);
        return component;
    }

    @Test void fixedNodePurposesDoNotActivateUnrelatedAutomaticPreferences() {
        var catalog = MirrorCatalog.load();
        var required = DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.NODE_SERVICE));
        assertTrue(required.containsAll(Set.of(DownloadPoint.NODE_DISTRIBUTION, DownloadPoint.NODE_METADATA, DownloadPoint.NODE_PACKAGE)));
        assertFalse(required.contains(DownloadPoint.JAVA_JDK));
        assertFalse(required.contains(DownloadPoint.PHP_DISTRIBUTION));
        assertFalse(required.contains(DownloadPoint.GNU_MAKE_SOURCE));
        var points = new EnumMap<DownloadPoint, List<MirrorSettings.Row>>(DownloadPoint.class);
        for (var point : DownloadPoint.values()) points.put(point, List.of(MirrorSettings.Row.auto(true)));
        for (var point : required) {
            var official = catalog.providersFor(point).stream().filter(MirrorCatalog.Provider::official).findFirst().orElseThrow();
            points.put(point, List.of(new MirrorSettings.Row(official.id(), "", false, false, "")));
        }
        var settings = new MirrorSettings(1, points, Map.of());
        assertFalse(required.stream().anyMatch(point -> settings.rows(point).stream().anyMatch(MirrorSettings.Row::automatic)));
        assertTrue(settings.rows(DownloadPoint.PHP_DISTRIBUTION).getFirst().automatic());
        var resolved = MirrorSelector.resolve(catalog, settings, "server", "CN", required);
        assertEquals(DownloadPoint.values().length, resolved.points().size());
        assertTrue(resolved.points().get(DownloadPoint.JAVA_JDK).isEmpty());
        assertTrue(resolved.points().get(DownloadPoint.PHP_DISTRIBUTION).isEmpty());
        assertEquals(1, resolved.points().get(DownloadPoint.NODE_PACKAGE).size());
    }

    @Test void onlyPythonAndRubyUsingKindsRequireTheFixedGnuMakeSource() {
        var point = DownloadPoint.GNU_MAKE_SOURCE;
        for (var kind : List.of(DeploymentProjectKind.PYTHON_SERVICE, DeploymentProjectKind.STATIC_SITE,
                DeploymentProjectKind.RUBY_SERVICE))
            assertTrue(DeploymentMirrorRequirements.forKinds(List.of(kind)).contains(point));
        for (var kind : List.of(DeploymentProjectKind.CMAKE_SERVICE,
                DeploymentProjectKind.PHP_SERVICE, DeploymentProjectKind.NODE_SERVICE))
            assertFalse(DeploymentMirrorRequirements.forKinds(List.of(kind)).contains(point));
    }

    @Test void rubyDescendantEnablesOnlyTheFixedSourceInItsNewPolicy() throws Exception {
        var catalog = MirrorCatalog.load();
        marker("components/scorer", "Gemfile", "source 'https://rubygems.org'\n");
        var active = DeploymentMirrorRequirements.forSource(root);
        var point = DownloadPoint.GNU_MAKE_SOURCE;
        assertTrue(active.containsAll(Set.of(DownloadPoint.RUBY_DISTRIBUTION, point)));
        var ruby = new MirrorSettings.Row("official-ruby_distribution", "", false, false, "");
        var python = new MirrorSettings.Row("official-python_distribution", "", false, false, "");
        var enabled = new MirrorSettings(1, Map.of(DownloadPoint.RUBY_DISTRIBUTION, List.of(ruby)), Map.of());
        var endpoint = MirrorSelector.resolve(catalog, enabled, "server", "CN", active).points().get(point).getFirst();
        assertTrue(endpoint.official());
        assertEquals(DownloadPoint.GNU_MAKE_ORIGIN, endpoint.baseUrl());
        assertTrue(endpoint.redirectRules().isEmpty());
        var unrelated = new MirrorSettings(1, Map.of(DownloadPoint.PYTHON_DISTRIBUTION, List.of(python)), Map.of());
        assertTrue(MirrorSelector.resolve(catalog, unrelated, "server", "CN", active).points().get(point).isEmpty());
        var disabled = new MirrorSettings(1, Map.of(DownloadPoint.RUBY_DISTRIBUTION, List.of(ruby),
            point, List.of(MirrorSettings.Row.auto(false))), Map.of());
        assertTrue(MirrorSelector.resolve(catalog, disabled, "server", "CN", active).points().get(point).isEmpty());
    }

    @Test void outOfScopeAutomaticStopsAtFixedPrefixAndLegacyOverloadStillExpands() {
        var rows = List.of(new MirrorSettings.Row("huawei", "", false, false, ""), MirrorSettings.Row.auto(true),
            new MirrorSettings.Row("official-maven_repository", "", false, false, ""));
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.MAVEN_REPOSITORY, rows), Map.of());
        var catalog = MirrorCatalog.load();
        var resolved = MirrorSelector.resolve(catalog, settings, "server", "CN", Set.of());
        assertEquals(List.of("huawei"), resolved.points().get(DownloadPoint.MAVEN_REPOSITORY).stream()
            .map(ResolvedMirrorPolicy.Endpoint::providerId).toList());
        assertEquals(rows, settings.rows(DownloadPoint.MAVEN_REPOSITORY));
        assertTrue(MirrorSelector.resolve(catalog, settings, "server", "CN")
            .points().get(DownloadPoint.MAVEN_REPOSITORY).size() > 1);
    }

    @Test void dockerAndCmakeHaveNoUnrelatedLanguagePurposesAndComponentsUseUnion() {
        var expectedDocker = EnumSet.copyOf(systems());
        expectedDocker.add(DownloadPoint.OCI_REGISTRY);
        assertEquals(expectedDocker, DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.DOCKERFILE_CONTAINER)));
        assertEquals(systems(), DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.CMAKE_SERVICE)));
        var node = DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.NODE_SERVICE));
        var go = DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.GO_SERVICE));
        var combined = EnumSet.copyOf(node);
        combined.addAll(go);
        assertEquals(combined, DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.NODE_SERVICE, DeploymentProjectKind.GO_SERVICE)));
    }

    @Test void discoveryNarrowsMavenGradleAndStandaloneKotlinFromRealPartRoots() throws Exception {
        var maven = marker("maven", "pom.xml", "<project><artifactId>spring-boot</artifactId></project>");
        var gradle = marker("gradle", "build.gradle", "plugins { id 'org.springframework.boot' }");
        var kotlin = marker("kotlin", "build.gradle.kts", "plugins { kotlin(\"jvm\") version \"2.0.21\" }");
        var compiler = marker("compiler", "windowstolinux-kotlin.properties", "version=1\n");
        var mavenPoints = DeploymentMirrorRequirements.forSource(maven);
        assertTrue(mavenPoints.contains(DownloadPoint.MAVEN_DISTRIBUTION));
        assertFalse(mavenPoints.contains(DownloadPoint.GRADLE_DISTRIBUTION));
        var gradlePoints = DeploymentMirrorRequirements.forSource(gradle);
        assertTrue(gradlePoints.contains(DownloadPoint.GRADLE_DISTRIBUTION));
        assertFalse(gradlePoints.contains(DownloadPoint.MAVEN_DISTRIBUTION));
        var kotlinPoints = DeploymentMirrorRequirements.forSource(kotlin);
        assertTrue(kotlinPoints.contains(DownloadPoint.GRADLE_PLUGIN));
        assertTrue(kotlinPoints.contains(DownloadPoint.KOTLIN_DISTRIBUTION));
        assertTrue(kotlinPoints.contains(DownloadPoint.KOTLIN_METADATA));
        var compilerPoints = DeploymentMirrorRequirements.forSource(compiler);
        assertTrue(compilerPoints.contains(DownloadPoint.KOTLIN_DISTRIBUTION));
        assertTrue(compilerPoints.contains(DownloadPoint.KOTLIN_METADATA));
        assertFalse(compilerPoints.contains(DownloadPoint.GRADLE_DISTRIBUTION));
    }

    @Test void discoveryPreservesMultiComponentUnionPureStaticAndUnknownEvidence() throws Exception {
        var web = marker("application/web", "package.json", "{\"scripts\":{\"start\":\"node app.js\"}}");
        var api = marker("application/api", "go.mod", "module fixture\n");
        var expected = EnumSet.copyOf(DeploymentMirrorRequirements.forSource(web));
        expected.addAll(DeploymentMirrorRequirements.forSource(api));
        assertEquals(expected, DeploymentMirrorRequirements.forSource(root.resolve("application")));
        var html = marker("static", "index.html", "<!doctype html><title>fixture</title>");
        var staticRuntime = EnumSet.copyOf(systems());
        staticRuntime.addAll(Set.of(DownloadPoint.PYTHON_DISTRIBUTION, DownloadPoint.PYTHON_METADATA));
        assertEquals(staticRuntime, DeploymentMirrorRequirements.forSource(html));
        var unknown = marker("unknown", "README.txt", "No recognized project marker\n");
        var all = EnumSet.allOf(DownloadPoint.class);
        assertEquals(all, DeploymentMirrorRequirements.forSource(unknown));
        assertEquals(all, DeploymentMirrorRequirements.forKinds(List.of()));
        assertEquals(all, DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.MANAGED_PROCESS)));
        assertThrows(IOException.class, () -> DeploymentMirrorRequirements.forSource(unknown.resolve("README.txt")));
    }

    @Test void declaredBundleUsesItsExistingValidatedBuildDirectory() throws Exception {
        marker("bundle", "windowstolinux-application.properties", "projectType=SPRING_BOOT\nbuildDirectory=backend\n");
        marker("bundle/backend", "pom.xml", "<project><artifactId>spring-boot</artifactId></project>");
        marker("bundle/unowned-web", "package.json", "{\"scripts\":{\"start\":\"node app.js\"}}");
        var points = DeploymentMirrorRequirements.forSource(root.resolve("bundle"));
        assertTrue(points.contains(DownloadPoint.MAVEN_DISTRIBUTION));
        assertFalse(points.contains(DownloadPoint.GRADLE_DISTRIBUTION));
        assertFalse(points.contains(DownloadPoint.NODE_DISTRIBUTION));
    }

    @Test void eachSupportedLanguageIncludesItsToolchainMetadataBootstrapAndRepository() {
        Map<DeploymentProjectKind, Set<DownloadPoint>> expected = Map.of(
            DeploymentProjectKind.JAVA_SOURCE, Set.of(DownloadPoint.JAVA_JDK, DownloadPoint.JAVA_METADATA),
            DeploymentProjectKind.PYTHON_SERVICE, Set.of(DownloadPoint.PYTHON_DISTRIBUTION, DownloadPoint.PYTHON_METADATA,
                DownloadPoint.PYTHON_PIP_BOOTSTRAP, DownloadPoint.PYTHON_PACKAGE, DownloadPoint.GNU_MAKE_SOURCE),
            DeploymentProjectKind.GO_SERVICE, Set.of(DownloadPoint.GO_DISTRIBUTION, DownloadPoint.GO_METADATA, DownloadPoint.GO_MODULE, DownloadPoint.GO_CHECKSUM),
            DeploymentProjectKind.RUST_SERVICE, Set.of(DownloadPoint.RUST_TOOLCHAIN, DownloadPoint.RUST_METADATA, DownloadPoint.RUST_CRATE, DownloadPoint.RUST_INDEX),
            DeploymentProjectKind.DOTNET_SERVICE, Set.of(DownloadPoint.DOTNET_SDK, DownloadPoint.DOTNET_METADATA, DownloadPoint.NUGET),
            DeploymentProjectKind.PHP_SERVICE, Set.of(DownloadPoint.PHP_DISTRIBUTION, DownloadPoint.PHP_METADATA,
                DownloadPoint.COMPOSER_INSTALLER, DownloadPoint.COMPOSER_REPOSITORY),
            DeploymentProjectKind.RUBY_SERVICE, Set.of(DownloadPoint.RUBY_DISTRIBUTION, DownloadPoint.RUBY_METADATA,
                DownloadPoint.RUBYGEMS, DownloadPoint.GNU_MAKE_SOURCE));
        expected.forEach((kind, downloads) -> {
            var points = EnumSet.copyOf(systems());
            points.addAll(downloads);
            assertEquals(points, DeploymentMirrorRequirements.forKinds(List.of(kind)), kind.toString());
        });
        var java = DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.JAVA_JAR));
        assertTrue(java.containsAll(Set.of(DownloadPoint.MAVEN_DISTRIBUTION, DownloadPoint.GRADLE_DISTRIBUTION)));
    }
    @Test void staticAndKotlinPurposesRetainTheActualManagedRuntimePreparationContract() throws Exception {
        var staticKinds = DeploymentMirrorRequirements.forKinds(List.of(DeploymentProjectKind.STATIC_SITE));
        assertTrue(staticKinds.containsAll(Set.of(DownloadPoint.PYTHON_DISTRIBUTION, DownloadPoint.PYTHON_METADATA)));
        assertFalse(staticKinds.contains(DownloadPoint.PYTHON_PACKAGE));
        var pure = marker("pure", "index.html", "<title>pure</title>");
        assertFalse(DeploymentMirrorRequirements.forSource(pure).contains(DownloadPoint.NODE_DISTRIBUTION));
        var kotlin = marker("gradle-kotlin", "build.gradle.kts", "plugins { kotlin(\"jvm\") version \"2.0.21\" }");
        assertTrue(DeploymentMirrorRequirements.forSource(kotlin).containsAll(Set.of(DownloadPoint.JAVA_JDK,
            DownloadPoint.JAVA_METADATA, DownloadPoint.KOTLIN_DISTRIBUTION, DownloadPoint.KOTLIN_METADATA)));
    }

    @Test void explicitStaticNodeRuntimeAddsOnlyItsThreeDownloadPurposes() throws Exception {
        var pure = marker("reviewed-static", "index.html", "<title>static</title>");
        var source = DeploymentMirrorRequirements.forSource(pure);
        assertFalse(source.contains(DownloadPoint.NODE_DISTRIBUTION));
        var health = new DeploymentHealthCheck.Http(java.net.URI.create("http://127.0.0.1:8080/"), 200, 10);
        var withoutNode = new DeploymentRuntimeDefinition.StaticSite("public", OptionalInt.empty(), health);
        assertEquals(Set.of(), DeploymentMirrorRequirements.additionalForRuntime(withoutNode));
        var withNode = new DeploymentRuntimeDefinition.StaticSite("public", OptionalInt.of(22), health);
        var additional = DeploymentMirrorRequirements.additionalForRuntime(withNode);
        assertEquals(Set.of(DownloadPoint.NODE_DISTRIBUTION, DownloadPoint.NODE_METADATA, DownloadPoint.NODE_PACKAGE), additional);
        assertThrows(UnsupportedOperationException.class, () -> additional.add(DownloadPoint.JAVA_JDK));
        var combined = EnumSet.copyOf(source);
        combined.addAll(additional);
        assertTrue(combined.containsAll(Set.of(DownloadPoint.PYTHON_DISTRIBUTION, DownloadPoint.PYTHON_METADATA)));
        assertFalse(combined.contains(DownloadPoint.PHP_DISTRIBUTION));
        assertFalse(combined.contains(DownloadPoint.JAVA_JDK));
        assertEquals(Set.of(), DeploymentMirrorRequirements.additionalForRuntime(new DeploymentRuntimeDefinition.NodeService(22, health)));
    }
}
