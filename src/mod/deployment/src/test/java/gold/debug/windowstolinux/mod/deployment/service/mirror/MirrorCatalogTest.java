package gold.debug.windowstolinux.mod.deployment.service.mirror;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog;

class MirrorCatalogTest {
    @Test void officialGoDistributionRedirectIsFrozenWithoutBroadeningInitialOrCustomHosts() throws Exception {
        var catalog = MirrorCatalog.load();
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.GO_MODULE, List.of(
            new MirrorSettings.Row("official-go_module", "", false, false, ""))), Map.of());
        var policy = MirrorSelector.resolve(catalog, settings, "server", "US").withTask("task");
        var endpoint = policy.points().get(DownloadPoint.GO_MODULE).getFirst();
        var expected = List.of(new ResolvedMirrorPolicy.RedirectRule(
            "proxy.golang.org", "storage.googleapis.com", "/proxy-golang-org-prod/"));
        assertEquals(expected, endpoint.redirectRules());
        assertEquals(List.of("proxy.golang.org"), endpoint.allowedHosts());
        assertEquals(expected, endpoint.selection("synthetic-reference").redirectRules());
        assertEquals(policy, ResolvedMirrorPolicy.fromJson(policy.toJson(), "task", "server"));
        var distributionRules = new TreeMap<String, List<ResolvedMirrorPolicy.RedirectRule>>();
        catalog.providers().forEach(provider -> provider.sources().forEach((point, source) -> {
            if (!source.redirectRules().isEmpty()) distributionRules.put(provider.id() + ":" + point, source.redirectRules());
        }));
        assertEquals(Map.of("official-go_module:GO_MODULE", expected,
            "official-gradle_distribution:GRADLE_DISTRIBUTION", List.of(new ResolvedMirrorPolicy.RedirectRule(
                "github.com", "release-assets.githubusercontent.com", "/github-production-release-asset/696192900/"))),
            distributionRules);

        var custom = new MirrorSettings(1, Map.of(DownloadPoint.GO_MODULE, List.of(
            new MirrorSettings.Row("", "https://company.invalid/go/", false, false, ""))), Map.of());
        var customEndpoint = MirrorSelector.resolve(catalog, custom, "server", "US")
            .points().get(DownloadPoint.GO_MODULE).getFirst();
        assertTrue(customEndpoint.redirectRules().isEmpty());
        assertEquals(List.of("company.invalid"), customEndpoint.allowedHosts());

        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var legacy = json.readTree(policy.toJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacy.path("points").path("GO_MODULE").get(0)).remove("redirectRules");
        assertTrue(ResolvedMirrorPolicy.fromJson(json.writeValueAsString(legacy), "task", "server")
            .points().get(DownloadPoint.GO_MODULE).getFirst().redirectRules().isEmpty());
    }

    @Test void officialGradleDistributionRedirectIsFrozenWithoutBroadeningInitialOrCustomHosts() throws Exception {
        var catalog = MirrorCatalog.load();
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.GRADLE_DISTRIBUTION, List.of(
            new MirrorSettings.Row("official-gradle_distribution", "", false, false, ""))), Map.of());
        var policy = MirrorSelector.resolve(catalog, settings, "server", "US").withTask("task");
        var endpoint = policy.points().get(DownloadPoint.GRADLE_DISTRIBUTION).getFirst();
        var expected = List.of(new ResolvedMirrorPolicy.RedirectRule(
            "github.com", "release-assets.githubusercontent.com", "/github-production-release-asset/696192900/"));
        assertEquals(expected, endpoint.redirectRules());
        assertEquals(List.of("downloads.gradle.org", "github.com", "services.gradle.org"), endpoint.allowedHosts());
        var origins = List.of("https://services.gradle.org/distributions/", "https://downloads.gradle.org/distributions/",
            "https://github.com/gradle/gradle-distributions/releases/download/");
        assertEquals(origins, endpoint.sourceOrigins());
        assertEquals(origins.stream().map(origin -> new ResolvedMirrorPolicy.Route(origin, origin)).toList(), endpoint.routes());
        assertEquals(expected, endpoint.selection("synthetic-reference").redirectRules());
        assertEquals(policy, ResolvedMirrorPolicy.fromJson(policy.toJson(), "task", "server"));
        assertEquals(1, policy.points().get(DownloadPoint.GRADLE_DISTRIBUTION).size());
        assertTrue(policy.points().entrySet().stream().filter(entry -> entry.getKey() != DownloadPoint.GRADLE_DISTRIBUTION)
            .allMatch(entry -> entry.getValue().isEmpty()));

        var custom = new MirrorSettings(1, Map.of(DownloadPoint.GRADLE_DISTRIBUTION, List.of(
            new MirrorSettings.Row("", "https://company.invalid/gradle/", false, false, ""))), Map.of());
        var customEndpoint = MirrorSelector.resolve(catalog, custom, "server", "US")
            .points().get(DownloadPoint.GRADLE_DISTRIBUTION).getFirst();
        assertTrue(customEndpoint.redirectRules().isEmpty());
        assertEquals(List.of("company.invalid"), customEndpoint.allowedHosts());
        assertThrows(IllegalArgumentException.class, () -> new ResolvedMirrorPolicy.Endpoint(
            endpoint.providerId(), endpoint.protocol(), endpoint.baseUrl(), endpoint.allowedHosts(), false, true,
            endpoint.sourceOrigins(), endpoint.routes(), endpoint.repoTemplates(), "", false, expected));

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var frozen = mapper.readTree(policy.toJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) frozen.path("points").path("GRADLE_DISTRIBUTION").get(0))
            .put("custom", true).put("official", false);
        var text = mapper.writeValueAsString(frozen);
        assertThrows(IllegalArgumentException.class, () -> ResolvedMirrorPolicy.fromJson(text, "task", "server"));
    }

    @Test void redirectRuleHostsPathsCountsAndSourceEdgesAreValidated() {
        for (String host : List.of("*.googleapis.com", "storage.googleapis.com:443", "user@storage.googleapis.com",
                "https://storage.googleapis.com", "storage.googleapis.com/", "bad..host", "-bad.host", "host."))
            assertThrows(IllegalArgumentException.class, () -> new ResolvedMirrorPolicy.RedirectRule(
                "proxy.golang.org", host, "/proxy-golang-org-prod/"), host);
        for (String prefix : List.of("/", "bucket/", "/bucket", "/bucket/../", "/bucket/./", "/bucket//",
                "/bucket/%2e/", "/bucket/\\escape/", "/bucket/?token=x/", "/bucket/#fragment/", "/bucket/\n/"))
            assertThrows(IllegalArgumentException.class, () -> new ResolvedMirrorPolicy.RedirectRule(
                "proxy.golang.org", "storage.googleapis.com", prefix), prefix);
        var rule = new ResolvedMirrorPolicy.RedirectRule("proxy.golang.org", "storage.googleapis.com", "/bucket/");
        var route = new ResolvedMirrorPolicy.Route("https://proxy.golang.org/", "https://proxy.golang.org/");
        for (var rules : List.of(Collections.nCopies(17, rule), List.of(new ResolvedMirrorPolicy.RedirectRule(
                "other.invalid", "storage.googleapis.com", "/bucket/")))) {
            assertThrows(IllegalArgumentException.class, () -> new MirrorCatalog.Source("https://proxy.golang.org/",
                List.of("proxy.golang.org"), List.of(route), true, "synthetic", Map.of(), rules));
            assertThrows(IllegalArgumentException.class, () -> new ResolvedMirrorPolicy.Endpoint("synthetic", "GO_MODULE",
                "https://proxy.golang.org/", List.of("proxy.golang.org"), true, false, List.of(route.origin()),
                List.of(route), Map.of(), "", false, rules));
        }
    }

    @Test void catalogAndFrozenJsonRejectMalformedRedirectRules() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode catalogJson;
        try (var input = getClass().getResourceAsStream("/gold/debug/windowstolinux/mod/deployment/config/mirrors.yaml")) {
            catalogJson = mapper.readTree(input);
        }
        for (var provider : catalogJson.path("providers")) {
            if (!provider.path("id").asText().equals("official-go_module")) continue;
            ((com.fasterxml.jackson.databind.node.ObjectNode) provider.path("sources").path("GO_MODULE")
                .path("redirectRules").get(0)).put("fromHost", "outside.invalid");
        }
        var catalogBytes = mapper.writeValueAsBytes(catalogJson);
        assertThrows(IOException.class, () -> MirrorCatalog.load(new ByteArrayInputStream(catalogBytes)));
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.GO_MODULE, List.of(
            new MirrorSettings.Row("official-go_module", "", false, false, ""))), Map.of());
        var policy = MirrorSelector.resolve(MirrorCatalog.load(), settings, "server", "US").withTask("task");
        var frozen = mapper.readTree(policy.toJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) frozen.path("points").path("GO_MODULE").get(0)
            .path("redirectRules").get(0)).put("pathPrefix", "/bucket/../");
        var frozenText = mapper.writeValueAsString(frozen);
        assertThrows(IllegalArgumentException.class, () -> ResolvedMirrorPolicy.fromJson(frozenText, "task", "server"));
    }

    @Test void customFrozenEndpointsCannotCarryDistributionRedirectRules() throws Exception {
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.GO_MODULE, List.of(
            new MirrorSettings.Row("official-go_module", "", false, false, ""))), Map.of());
        var policy = MirrorSelector.resolve(MirrorCatalog.load(), settings, "server", "US").withTask("task");
        var endpoint = policy.points().get(DownloadPoint.GO_MODULE).getFirst();
        assertThrows(IllegalArgumentException.class, () -> new ResolvedMirrorPolicy.Endpoint(
            endpoint.providerId(), endpoint.protocol(), endpoint.baseUrl(), endpoint.allowedHosts(), false, true,
            endpoint.sourceOrigins(), endpoint.routes(), endpoint.repoTemplates(), "", false, endpoint.redirectRules()));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var frozen = mapper.readTree(policy.toJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) frozen.path("points").path("GO_MODULE").get(0))
            .put("custom", true).put("official", false);
        var text = mapper.writeValueAsString(frozen);
        assertThrows(IllegalArgumentException.class, () -> ResolvedMirrorPolicy.fromJson(text, "task", "server"));
    }

    @Test void bundledCatalogDefinesEveryPointAndSeventeenRegions() {
        var catalog = MirrorCatalog.load();
        assertSame(catalog, MirrorCatalog.load());
        assertEquals(17, catalog.regions().size());
        for (var point : DownloadPoint.values()) {
            assertFalse(catalog.point(point).sourceOrigins().isEmpty());
            assertTrue(catalog.providersFor(point).stream().anyMatch(MirrorCatalog.Provider::official));
            assertFalse(catalog.point(point).coverageNote().isBlank());
        }
        assertEquals("CN", catalog.countries().get("CN"));
        assertEquals("IR", catalog.countries().get("IR"));
    }
    @Test void consentAndSingleFixedSelectionDoNotAppendUnrequestedSources() {
        var catalog = MirrorCatalog.load();
        assertTrue(MirrorSelector.resolve(catalog, MirrorSettings.defaults(), "server", "CN")
            .points().values().stream().allMatch(List::isEmpty));
        var fixed = new MirrorSettings(1, Map.of(DownloadPoint.MAVEN_REPOSITORY,
            List.of(new MirrorSettings.Row("huawei", "", false, false, ""))), Map.of());
        var selected = MirrorSelector.resolve(catalog, fixed, "server", "US");
        assertEquals(List.of("huawei"), selected.points().get(DownloadPoint.MAVEN_REPOSITORY).stream()
            .map(ResolvedMirrorPolicy.Endpoint::providerId).toList());
    }
    @Test void automaticStopsLaterRowsAndPreservesTheirValues() {
        var catalog = MirrorCatalog.load();
        var rows = List.of(new MirrorSettings.Row("huawei", "", true, true, ""),
            new MirrorSettings.Row("official-maven_repository", "", false, false, ""));
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.MAVEN_REPOSITORY, rows), Map.of("server", "CN"));
        var selected = MirrorSelector.resolve(catalog, settings, "server", "US");
        assertEquals("CN", selected.country());
        assertEquals(3, selected.points().get(DownloadPoint.MAVEN_REPOSITORY).size());
        assertEquals(rows, settings.rows(DownloadPoint.MAVEN_REPOSITORY));
        assertEquals("huawei", settings.rows(DownloadPoint.MAVEN_REPOSITORY).getFirst().providerId());
    }
    @Test void snapshotRoundTripRequiresOriginalTaskServerAndRouteBoundary() {
        var policy = MirrorSelector.resolve(MirrorCatalog.load(), MirrorSettings.defaults(), "server", "CN").withTask("task");
        assertEquals(policy, ResolvedMirrorPolicy.fromJson(policy.toJson(), "task", "server"));
        assertThrows(IllegalArgumentException.class, () -> ResolvedMirrorPolicy.fromJson(policy.toJson(), "other", "server"));
        assertThrows(IllegalArgumentException.class, () -> new MirrorSettings.Row("", "https://user:secret@mirror.invalid/", false, false, ""));
        assertThrows(IllegalArgumentException.class, () -> new MirrorSettings(2, Map.of(), Map.of()));
    }
    @Test void localDatabaseReadsBothFamiliesWithoutHostnameLookup() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(("1.0.0.0,1.0.0.255,AU\n2001:db8::,2001:db8::ffff,JP\n").getBytes(StandardCharsets.UTF_8));
        }
        var database = LocalCountryDatabase.load(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(Optional.of("AU"), database.country("1.0.0.255"));
        assertEquals(Optional.of("JP"), database.country("2001:db8::123"));
        assertTrue(database.country("1.0.1.0").isEmpty());
        assertTrue(database.country("localhost").isEmpty());
        assertTrue(database.country("example.com").isEmpty());
        assertTrue(database.country("001.0.0.1").isEmpty());
    }
    @Test void bundledCountryDataIsReadable() throws Exception {
        var database = LocalCountryDatabase.load(MirrorCatalog.load());
        assertSame(database, LocalCountryDatabase.load(MirrorCatalog.load()));
        assertTrue(database.country("8.8.8.8").isPresent());
        assertTrue(database.country("2001:4860:4860::8888").isPresent());
    }
    @Test void automaticExpandsAllCandidatesKeepsOfficialLastAndDeduplicatesFixedPrefix() {
        var catalog = MirrorCatalog.load();
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.APT_DEBIAN, List.of(
            new MirrorSettings.Row("huawei", "", false, false, ""), MirrorSettings.Row.auto(true))), Map.of());
        var policy = MirrorSelector.resolve(catalog, settings, "server", "CN").withTask("task");
        var endpoints = policy.points().get(DownloadPoint.APT_DEBIAN);
        assertTrue(endpoints.size() > 3);
        assertEquals("huawei", endpoints.getFirst().providerId());
        assertTrue(endpoints.getLast().official());
        assertEquals(1, endpoints.stream().filter(p -> p.providerId().equals("huawei")).count());
        assertEquals(policy, ResolvedMirrorPolicy.fromJson(policy.toJson(), "task", "server"));
        assertEquals("", MirrorSelector.resolve(catalog, settings, "server", "ZZ").country());
    }
    @Test void customDnfUsesOnlyConfiguredCompatibleRootTemplates() {
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.DNF_FEDORA, List.of(
            new MirrorSettings.Row("", "https://mirror.invalid/fedora/", false, false, ""))), Map.of());
        var endpoint = MirrorSelector.resolve(MirrorCatalog.load(), settings, "server", "CN")
            .points().get(DownloadPoint.DNF_FEDORA).getFirst();
        assertEquals(Set.of("fedora", "updates"), endpoint.repoTemplates().keySet());
        assertEquals("https://mirror.invalid/fedora/releases/$releasever/Everything/$basearch/os/",
            endpoint.repoTemplates().get("fedora"));
    }
    @Test void oracleLinuxUsesVersionSpecificRepositoriesAndCoverageIncludesEveryPoint() throws Exception {
        var catalog = MirrorCatalog.load();
        var oracle = catalog.providersFor(DownloadPoint.DNF_ORACLE).stream().filter(MirrorCatalog.Provider::official)
            .findFirst().orElseThrow().sources().get(DownloadPoint.DNF_ORACLE);
        assertTrue(oracle.repoTemplates().get("ol9_baseos_latest").contains("/OL9/baseos/latest/"));
        assertTrue(oracle.repoTemplates().get("ol10_baseos_latest").contains("/OL10/baseos/latest/"));
        assertTrue(oracle.repoTemplates().containsKey("ol9_UEKR7"));
        assertFalse(oracle.repoTemplates().containsKey("ol10_UEKR7"));
        try (var input = getClass().getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/config/mirrors-coverage.yaml")) {
            var matrix = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            assertEquals(catalog.regions().size(), matrix.path("regions").size());
            var fixed = matrix.path("fixedOfficialPoints");
            assertEquals(1, fixed.size());
            var make = fixed.path(DownloadPoint.GNU_MAKE_SOURCE.name());
            assertEquals(catalog.catalogVersion(), make.path("catalogVersion").asText());
            assertEquals(1, make.path("compatibleProviderCount").asInt());
            assertEquals(DownloadPoint.GNU_MAKE_PROVIDER, make.path("automaticProviderIds").get(0).asText());
            for (var region : matrix.path("regions")) {
                assertEquals(DownloadPoint.values().length - fixed.size(), region.path("points").size());
                for (var point : DownloadPoint.values()) {
                    if (fixed.has(point.name())) continue;
                    long count = catalog.providersFor(point).stream().map(p -> p.sources().get(point).baseUrl()).distinct().count();
                    assertEquals(count, region.path("points").path(point.name()).path("compatibleProviderCount").asLong());
                }
            }
        }
    }

    @Test void gnuMakeSourceIsOfficialOnlyInNewPythonPoliciesAndDoesNotChangeOldSnapshots() throws Exception {
        var catalog = MirrorCatalog.load();
        var point = DownloadPoint.GNU_MAKE_SOURCE;
        assertEquals(List.of(DownloadPoint.GNU_MAKE_PROVIDER),
            catalog.allProvidersFor(point).stream().map(MirrorCatalog.Provider::id).toList());
        var python = new MirrorSettings.Row("official-python_distribution", "", false, false, "");
        var prefs = new MirrorSettings(1, Map.of(DownloadPoint.PYTHON_DISTRIBUTION, List.of(python)), Map.of());
        var active = Set.of(DownloadPoint.PYTHON_DISTRIBUTION, point);
        var current = MirrorSelector.resolve(catalog, prefs, "server", "CN", active).withTask("task");
        var endpoint = current.points().get(point).getFirst();
        assertTrue(endpoint.official());
        assertEquals(DownloadPoint.GNU_MAKE_ORIGIN, endpoint.baseUrl());
        assertEquals(List.of("ftp.gnu.org"), endpoint.allowedHosts());
        assertTrue(endpoint.redirectRules().isEmpty());
        assertTrue(MirrorSelector.resolve(catalog, prefs, "server", "CN", Set.of()).points().get(point).isEmpty());
        assertTrue(MirrorSelector.resolve(catalog, MirrorSettings.defaults(), "server", "CN", active).points().get(point).isEmpty());
        var defaultRows = new EnumMap<DownloadPoint, List<MirrorSettings.Row>>(MirrorSettings.defaults().points());
        assertFalse(defaultRows.containsKey(point));
        defaultRows.put(DownloadPoint.PYTHON_DISTRIBUTION, List.of(MirrorSettings.Row.auto(true)));
        var consentedPython = new MirrorSettings(1, defaultRows, Map.of());
        assertEquals(DownloadPoint.GNU_MAKE_ORIGIN,
            MirrorSelector.resolve(catalog, consentedPython, "server", "CN", active).points().get(point).getFirst().baseUrl());

        var disabled = new MirrorSettings(1, Map.of(DownloadPoint.PYTHON_DISTRIBUTION, List.of(python),
            point, List.of(MirrorSettings.Row.auto(false))), Map.of());
        assertTrue(MirrorSelector.resolve(catalog, disabled, "server", "CN", active).points().get(point).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new MirrorSettings(1,
            Map.of(point, List.of(new MirrorSettings.Row("", "https://alternate.invalid/", false, false, ""))), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new MirrorSettings(1,
            Map.of(point, List.of(new MirrorSettings.Row(DownloadPoint.GNU_MAKE_PROVIDER, "", false, false, "test-ref"))), Map.of()));

        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var frozen = json.readTree(current.toJson());
        var points = (com.fasterxml.jackson.databind.node.ObjectNode) frozen.path("points");
        ((com.fasterxml.jackson.databind.node.ObjectNode) points.path(point.name()).get(0)).put("custom", true);
        assertThrows(IllegalArgumentException.class, () -> ResolvedMirrorPolicy.fromJson(frozen.toString(), "task", "server"));
        points.remove(point.name());
        var legacy = ResolvedMirrorPolicy.fromJson(frozen.toString(), "task", "server");
        assertFalse(legacy.points().containsKey(point));
        assertFalse(json.readTree(legacy.withTask("successor").toJson()).path("points").has(point.name()));
        points.remove(DownloadPoint.PYTHON_DISTRIBUTION.name());
        assertThrows(IllegalArgumentException.class, () -> ResolvedMirrorPolicy.fromJson(frozen.toString(), "task", "server"));
    }
    @Test void jdkCountryExclusionsAndWrapperIdentityTemplatesAreExplicitCatalogData() throws Exception {
        var catalog = MirrorCatalog.load();
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.JAVA_JDK, List.of(MirrorSettings.Row.auto(true))), Map.of());
        var china = MirrorSelector.resolve(catalog, settings, "server", "CN").points().get(DownloadPoint.JAVA_JDK);
        var france = MirrorSelector.resolve(catalog, settings, "server", "FR").points().get(DownloadPoint.JAVA_JDK);
        assertEquals(3, china.size());
        assertTrue(china.stream().anyMatch(p -> p.providerId().equals("tuna")));
        assertEquals(2, france.size());
        assertTrue(france.stream().noneMatch(p -> p.providerId().equals("tuna")));
        assertTrue(france.getLast().official());
        assertEquals(27, catalog.providersFor(DownloadPoint.JAVA_JDK).stream()
            .filter(p -> p.id().equals("tuna")).findFirst().orElseThrow().excludedCountries().size());
        try (var input = getClass().getResourceAsStream(
            "/gold/debug/windowstolinux/mod/deployment/config/mirrors-coverage.yaml")) {
            var matrix = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            assertEquals(27, matrix.path("countryOverrides").size());
            assertEquals(2, matrix.path("countryOverrides").path("FR").path("points").path("JAVA_JDK")
                .path("compatibleProviderCount").asInt());
        }
        for (var provider : catalog.providersFor(DownloadPoint.JAVA_JDK)) {
            if (provider.official()) continue;
            var source = provider.sources().get(DownloadPoint.JAVA_JDK);
            var route = source.routes().getFirst();
            var original = route.origin() + "temurin21-binaries/releases/download/jdk-21.0.13%2B9/"
                + "OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz";
            var match = java.util.regex.Pattern.compile(route.pattern()).matcher(original);
            assertTrue(match.matches());
            assertEquals("21", match.group(1));
            assertEquals("x64", match.group(3));
            assertEquals("OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz", match.group(2));
        }
        for (var provider : catalog.providersFor(DownloadPoint.MAVEN_DISTRIBUTION)) {
            var source = provider.sources().get(DownloadPoint.MAVEN_DISTRIBUTION);
            var route = source.routes().stream().filter(r -> !r.pattern().isEmpty()).findFirst().orElseThrow();
            var original = route.origin() + "3.9.12/apache-maven-3.9.12-bin.zip.sha512";
            var match = java.util.regex.Pattern.compile(route.pattern()).matcher(original);
            assertTrue(match.matches());
            assertEquals("3.9.12", match.group(1));
            assertEquals("3", match.group(2));
            assertEquals("zip", match.group(3));
            assertEquals(".sha512", match.group(4));
        }
        var custom = new MirrorSettings(1, Map.of(DownloadPoint.MAVEN_DISTRIBUTION, List.of(
            new MirrorSettings.Row("", "https://corporate.invalid/apache/maven/", false, false, ""))), Map.of());
        var customEndpoint = MirrorSelector.resolve(catalog, custom, "server", "CN")
            .points().get(DownloadPoint.MAVEN_DISTRIBUTION).getFirst();
        assertEquals(2, customEndpoint.routes().stream().filter(route -> !route.pattern().isEmpty()).count());
        assertTrue(customEndpoint.routes().stream().filter(route -> !route.pattern().isEmpty())
            .allMatch(route -> route.replacement().startsWith(customEndpoint.baseUrl())));
    }
    @Test void customRowsHaveDistinctStableIdsAndNormalizedUrlDeduplication() {
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.APT_DEBIAN, List.of(
            new MirrorSettings.Row("", "https://FIRST.invalid:443/debian/", false, false, ""),
            new MirrorSettings.Row("", "https://second.invalid/debian/", false, false, ""),
            new MirrorSettings.Row("", "https://first.invalid/debian/", false, false, ""))), Map.of());
        var catalog = MirrorCatalog.load();
        var first = MirrorSelector.resolve(catalog, settings, "server", "CN").points().get(DownloadPoint.APT_DEBIAN);
        var again = MirrorSelector.resolve(catalog, settings, "server", "CN").points().get(DownloadPoint.APT_DEBIAN);
        assertEquals(2, first.size());
        assertEquals("https://first.invalid/debian/", first.getFirst().baseUrl());
        assertEquals("https://second.invalid/debian/", first.getLast().baseUrl());
        assertNotEquals(first.getFirst().providerId(), first.getLast().providerId());
        assertTrue(first.stream().allMatch(p -> p.providerId().matches("custom-[a-f0-9]{16}")));
        assertEquals(first, again);
    }
}
