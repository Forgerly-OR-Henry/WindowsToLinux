package gold.debug.windowstolinux.mod.deployment.service.mirror;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentNativeHistory;
import gold.debug.windowstolinux.mod.deployment.service.agent.skill.DeploymentAgentSkills;
import org.junit.jupiter.api.Test;

class DeploymentMirrorGuidanceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void publicGoSourcesDescribeRedirectOnlyBucketScope() throws Exception {
        var task = task();
        var settings = new MirrorSettings(1, Map.of(DownloadPoint.GO_MODULE, List.of(
            new MirrorSettings.Row("official-go_module", "", false, false, ""))), Map.of());
        var policy = MirrorSelector.resolve(gold.debug.windowstolinux.mod.deployment.config.mirror.MirrorCatalog.load(),
            settings, "synthetic-server", "US").withTask(task.id().toString());
        bind(task, policy.toJson());
        try {
            var page = JSON.valueToTree(DeploymentMirrorGuidance.sources(task, "GO_MODULE"));
            var source = page.path("entries").get(0);
            assertEquals(JSON.readTree("[\"proxy.golang.org\"]"), source.path("allowedHosts"));
            assertEquals("storage.googleapis.com", source.path("redirectRules").get(0).path("toHost").asText());
            assertEquals("/proxy-golang-org-prod/", source.path("redirectRules").get(0).path("pathPrefix").asText());
            assertEquals(1, page.path("candidateCount").asInt());
        } finally { DeploymentMirrorTasks.forget(task); }
    }

    @Test void legacyTasksCannotActivateSnapshotGuidanceAndOciUsageNamesAnOutputFile() throws Exception {
        var legacy = task();
        for (var role : DeploymentAgentSkills.Role.values()) {
            var skills = new DeploymentAgentSkills(legacy,role);
            assertThrows(IllegalArgumentException.class, () -> skills.read("mirror-downloads",legacy));
            assertTrue(skills.active().isEmpty());
        }
        var current = task();
        bind(current,snapshot(current,1));
        try {
            var result = DeploymentMirrorGuidance.sources(current,"OCI_REGISTRY");
            assertTrue(result.get("usage").toString().contains("output-Dockerfile"),result.toString());
            assertFalse(result.get("usage").toString().contains("output-directory"));
        } finally { DeploymentMirrorTasks.forget(current); }
    }

    @Test
    void pagesEveryFrozenCandidateInOrderWithPublicScopeAndNoCredentials() throws Exception {
        var task = task();
        String original = snapshot(task, 23);
        bind(task, original);
        try {
            var summary = JSON.valueToTree(DeploymentMirrorGuidance.summary(task));
            assertTrue(summary.path("present").asBoolean());
            assertEquals("catalog-fixture", summary.path("catalogVersion").asText());
            assertEquals("fixture-region", summary.path("region").asText());
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))),
                summary.path("policySha256").asText());
            assertEquals(DownloadPoint.values().length, summary.path("points").size());
            assertTrue(summary.path("points").findValues("candidateCount").stream().anyMatch(value -> value.asInt() == 23));
            assertFalse(summary.has("entries"));
            int offset = 0;
            var indices = new ArrayList<Integer>();
            do {
                var result = offset == 0 ? DeploymentMirrorGuidance.sources(task, "NODE_PACKAGE")
                    : DeploymentMirrorGuidance.sources(task, "NODE_PACKAGE", offset, 10);
                byte[] bytes = JSON.writeValueAsBytes(result);
                assertTrue(bytes.length <= 12 * 1024);
                var page = JSON.readTree(bytes);
                assertEquals("SUPPORTED", page.path("status").asText());
                assertEquals(offset, page.path("offset").asInt());
                assertEquals(page.path("returned").asInt(), page.path("entries").size());
                for (var entry : page.path("entries")) {
                    int index = entry.path("index").asInt();
                    indices.add(index);
                    assertEquals("source-" + index, entry.path("providerId").asText());
                    assertEquals("https://mirror.example/", entry.path("baseUrl").asText());
                    assertTrue(entry.path("credentialManaged").asBoolean());
                    assertEquals("https://origin.example/", entry.path("sourceOrigins").get(0).asText());
                    assertEquals("https://mirror.example/packages/", entry.path("routes").get(0).path("prefix").asText());
                    assertEquals("https://mirror.example/repository/", entry.path("repoTemplates").path("release").asText());
                }
                String encoded = new String(bytes, StandardCharsets.UTF_8);
                for (String forbidden : List.of("fixture-secret-reference", "fixture-password", "fixture-token", "fixture-ca", "fixture-authorization", "secretRef"))
                    assertFalse(encoded.contains(forbidden));
                assertTrue(page.path("usage").asText().startsWith("wtl_dependency NODE_PACKAGE"));
                offset = page.path("nextOffset").asInt();
                if (!page.path("hasMore").asBoolean()) break;
                assertTrue(offset > 0);
            } while (true);
            assertEquals(java.util.stream.IntStream.range(0, 23).boxed().toList(), indices);
            assertEquals(original, DeploymentMirrorTasks.policy(task));
            assertEquals("NO_FROZEN_ENDPOINTS", DeploymentMirrorGuidance.sources(task, "JAVA_JDK").get("reason"));
            assertEquals("UNKNOWN_DOWNLOAD_POINT", DeploymentMirrorGuidance.sources(task, "NODE_PACKAGE; private-input").get("reason"));
            assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorGuidance.sources(task, "NODE_PACKAGE", -1, 5));
            assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorGuidance.sources(task, "NODE_PACKAGE", 0, 0));
            assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorGuidance.sources(task, "NODE_PACKAGE", 0, 11));
        } finally { DeploymentMirrorTasks.forget(task); }
    }

    @Test
    void oversizedSingleCandidateIsExplicitAndDoesNotHideTheNextCandidate() throws Exception {
        var task = task();
        var policy = (ObjectNode) JSON.readTree(snapshot(task, 2));
        var first = (ObjectNode) policy.path("points").path("NODE_PACKAGE").get(0);
        var hosts = first.putArray("allowedHosts");
        for (int index = 0; index < 100; index++) hosts.add("h".repeat(150) + index + ".example");
        bind(task, JSON.writeValueAsString(policy));
        try {
            var page = JSON.valueToTree(DeploymentMirrorGuidance.sources(task, "NODE_PACKAGE", 0, 1));
            assertEquals(1, page.path("returned").asInt());
            assertEquals(0, page.path("entries").get(0).path("index").asInt());
            assertEquals("PUBLIC_CANDIDATE_EXCEEDS_PAGE_BYTES", page.path("entries").get(0).path("reason").asText());
            assertEquals("UNSUPPORTED", page.path("entries").get(0).path("status").asText());
            assertTrue(page.path("hasMore").asBoolean());
            assertEquals(1, page.path("nextOffset").asInt());
            var next = JSON.valueToTree(DeploymentMirrorGuidance.sources(task, "NODE_PACKAGE", 1, 1));
            assertEquals("source-1", next.path("entries").get(0).path("providerId").asText());
            assertFalse(next.path("hasMore").asBoolean());
            assertTrue(JSON.writeValueAsBytes(page).length <= 12 * 1024);
        } finally { DeploymentMirrorTasks.forget(task); }
    }

    @Test
    void bothRolesActivateBeforeFirstTurnAndRebuildWhileLegacyTasksRemainUnmodified() throws Exception {
        var current = task();
        var legacy = task();
        bind(current, snapshot(current, 1));
        try {
            for (var role : DeploymentAgentSkills.Role.values()) {
                assertTrue(DeploymentAgentSkills.catalog(role).stream().anyMatch(value -> value.get("id").equals("mirror-downloads")));
                var first = new DeploymentAgentSkills(current, role);
                assertEquals(List.of("mirror-downloads"), first.active());
                var history = new DeploymentNativeHistory();
                assertTrue(history.agentMessages("BASE" + first.system(), "first-state").getFirst().text()
                    .contains("Trusted version-owned product skill mirror-downloads"));
                var rebuilt = new DeploymentAgentSkills(current, role);
                assertEquals(first.active(), rebuilt.active());
                assertEquals(first.system(), rebuilt.system());
                assertEquals("BASE" + first.system(), new DeploymentNativeHistory()
                    .agentMessages("BASE" + rebuilt.system(), "rebuilt-state").getFirst().text());
                assertFalse(first.system().contains("mirror.example"));
                assertEquals(List.of(), new DeploymentAgentSkills(legacy, role).active());
            }
            assertEquals(Map.of("present", false), DeploymentMirrorGuidance.summary(legacy));
            assertFalse(DeploymentMirrorGuidance.present(legacy));
            assertThrows(IllegalArgumentException.class, () -> DeploymentMirrorGuidance.sources(legacy, "NODE_PACKAGE"));
        } finally { DeploymentMirrorTasks.forget(current); }
    }

    private static String snapshot(TaskContext task, int count) throws Exception {
        var policy = JSON.createObjectNode().put("formatVersion", 1).put("task", task.id().toString())
            .put("server", "fixture-server").put("catalogVersion", "catalog-fixture").put("country", "ZZ").put("region", "fixture-region");
        var points = policy.putObject("points");
        for (var point : DownloadPoint.values()) points.putArray(point.name());
        var endpoints = (com.fasterxml.jackson.databind.node.ArrayNode) points.get("NODE_PACKAGE");
        for (int index = 0; index < count; index++) {
            var endpoint = endpoints.addObject().put("providerId", "source-" + index).put("protocol", "npm")
                .put("baseUrl", "https://mirror.example/").put("official", false).put("custom", true)
                .put("secretRef", "fixture-secret-reference").put("password", "fixture-password")
                .put("token", "fixture-token").put("ca", "fixture-ca").put("Authorization", "fixture-authorization");
            endpoint.putArray("allowedHosts").add("mirror.example");
            endpoint.putArray("sourceOrigins").add("https://origin.example/?token=fixture-token");
            endpoint.putArray("routes").addObject().put("origin", "https://origin.example/")
                .put("prefix", "https://fixture-password@mirror.example/packages/?token=fixture-token#fixture-ca");
            endpoint.putObject("repoTemplates").put("release", "https://mirror.example/repository/?token=fixture-token");
        }
        return JSON.writeValueAsString(policy);
    }

    private static TaskContext task() {
        UUID id = UUID.randomUUID();
        return (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class}, (proxy, method, args) -> switch (method.getName()) {
            case "id" -> id;
            case "cancelToken" -> (CancelToken) () -> false;
            default -> throw new AssertionError("Public guidance cannot use task capability: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static void bind(TaskContext task, String original) throws Exception {
        var field = DeploymentMirrorTasks.class.getDeclaredField("ACTIVE");
        field.setAccessible(true);
        ((Map<UUID, String>) field.get(null)).put(task.id(), original);
    }
}
