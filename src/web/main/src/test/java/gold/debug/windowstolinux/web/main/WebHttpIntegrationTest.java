package gold.debug.windowstolinux.web.main;

import gold.debug.windowstolinux.web.service.contract.WebJson;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WebHttpIntegrationTest {
    @TempDir Path root;
    private final HttpClient client = HttpClient.newBuilder().build();
    @org.junit.jupiter.api.AfterEach void closeClient() { client.close(); }

    @Test void loopbackApiRejectsCrossOriginSimpleFormsAndOwnerInjection() throws Exception {
        try (var runtime = start()) {
            var health = send(runtime, "GET", "/api/v1/health", null, null, null);
            assertEquals(200, health.statusCode());
            assertEquals("internal-test", WebJson.read(health.body()).path("mode").asText());
            assertEquals(403, send(runtime, "POST", "/api/v1/servers", "{}", "https://evil.invalid", "application/json").statusCode());
            assertEquals(415, send(runtime, "POST", "/api/v1/servers", "name=a", runtime.origin(), "application/x-www-form-urlencoded").statusCode());
            assertEquals(403, send(runtime, "POST", "/api/v1/servers", "{}", null, "application/json").statusCode());
            String injection = "{\"name\":\"server\",\"host\":\"test.invalid\",\"username\":\"root\",\"password\":\"synthetic\",\"workspaceId\":\"other\"}";
            assertEquals(400, send(runtime, "POST", "/api/v1/servers", injection, runtime.origin(), "application/json").statusCode());
            assertEquals(404, send(runtime, "GET", "/login", null, null, null).statusCode());
            assertEquals(405, send(runtime, "GET", "/api/v1/secrets", null, null, null).statusCode());
            assertTrue(health.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertTrue(health.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
        }
    }

    @Test void serverPasswordsNeverReturnAndPlainMetadataSurvivesRestart() throws Exception {
        String id;
        try (var runtime = start()) {
            var response = send(runtime, "POST", "/api/v1/servers", "{\"name\":\"Test server\",\"host\":\"test.invalid\",\"port\":22,\"username\":\"root\",\"password\":\"synthetic-secret\"}", runtime.origin(), "application/json");
            assertEquals(201, response.statusCode(), response.body());
            assertFalse(response.body().contains("synthetic-secret")); assertFalse(response.body().contains("secret_id"));
            id = WebJson.read(response.body()).path("id").asText();
            assertTrue(WebJson.read(response.body()).path("credentialConfigured").asBoolean());
        }
        try (var runtime = start()) {
            var response = send(runtime, "GET", "/api/v1/servers", null, null, null);
            assertEquals(id, WebJson.read(response.body()).get(0).path("id").asText());
            assertFalse(response.body().contains("synthetic-secret"));
        }
    }

    @Test void uploadedSourceCanBeAnalyzedAsADurableBackgroundTaskWithoutSsh() throws Exception {
        try (var runtime = start()) {
            JsonNode source = json(send(runtime, "POST", "/api/v1/sources", "{\"name\":\"sample\"}", runtime.origin(), "application/json"));
            String id = source.path("id").asText();
            assertEquals(200, send(runtime, "PUT", "/api/v1/sources/" + id + "/files?path=index.html", "<h1>Hello</h1>", runtime.origin(), "application/octet-stream").statusCode());
            assertEquals(200, send(runtime, "POST", "/api/v1/sources/" + id + "/complete", "{}", runtime.origin(), "application/json").statusCode());
            JsonNode task = json(send(runtime, "POST", "/api/v1/tasks", "{\"kind\":\"ANALYZE\",\"input\":{\"sourceId\":\"" + id + "\"}}", runtime.origin(), "application/json"));
            String taskId = task.path("id").asText(); JsonNode snapshot = null;
            for (int i = 0; i < 300; i++) {
                snapshot = json(send(runtime, "GET", "/api/v1/tasks/" + taskId, null, null, null));
                if (snapshot.path("state").asText().equals("SUCCEEDED")) break; Thread.sleep(10);
            }
            assertEquals("SUCCEEDED", snapshot.path("state").asText(), snapshot.toString());
            assertEquals("STATIC_SITE", snapshot.path("result").path("components").get(0).path("types").get(0).asText());
            var events = send(runtime, "GET", "/api/v1/tasks/" + taskId + "/events?after=1", null, null, null);
            assertTrue(events.body().contains("event: task")); assertFalse(events.body().contains("id: 1\n"));
            assertFalse(snapshot.toString().contains(root.toString()));
        }
    }

    @Test void secondInstanceFailsWithoutCorruptingActiveDatabase() throws Exception {
        try (var first = start()) {
            assertThrows(Exception.class, this::start);
            assertEquals(200, send(first, "GET", "/api/v1/health", null, null, null).statusCode());
        }
        try (var reopened = start()) { assertEquals(200, send(reopened, "GET", "/api/v1/health", null, null, null).statusCode()); }
    }

    @Test void coldBackupRecoversFromRejectedSchemaUpgradeWithoutAnEmbeddedMasterKey() throws Exception {
        try(var runtime=start()) {
            assertEquals(200,send(runtime,"PUT","/api/v1/preferences","{\"theme\":\"dark\"}",runtime.origin(),"application/json").statusCode());
        }
        Path data=root.resolve("data"),backup=root.resolve("cold-backup");copy(data,backup);
        var source = new org.sqlite.SQLiteDataSource(); source.setUrl("jdbc:sqlite:" + data.resolve("windowstolinuxweb.db"));
        new org.springframework.jdbc.core.JdbcTemplate(source).execute("PRAGMA user_version=999");
        assertThrows(Exception.class,this::start);
        Files.move(data,root.resolve("rejected-upgrade"));copy(backup,data);
        try(var restored=start()) {assertEquals("dark",json(send(restored,"GET","/api/v1/preferences",null,null,null)).path("theme").asText());}
        assertTrue(Files.isRegularFile(root.resolve("rejected-upgrade/windowstolinuxweb.db")));
        Path keyFile;
        try (var keys = Files.list(root.resolve("keys"))) { keyFile = keys.filter(path -> path.toString().endsWith(".key")).findFirst().orElseThrow(); }
        byte[] originalKey = Files.readAllBytes(keyFile), wrongKey = originalKey.clone(); wrongKey[0] ^= 1;
        Files.write(keyFile, wrongKey); assertThrows(Exception.class, this::start); Files.write(keyFile, originalKey);
        try(var restored=start()) {assertEquals(200,send(restored,"GET","/api/v1/health",null,null,null).statusCode());}
        try(var paths=Files.walk(backup)) {assertFalse(paths.anyMatch(path->path.getFileName().toString().endsWith(".key")));}
    }

    @Test void restartRemovesOnlyUnregisteredOwnedBackupDirectories() throws Exception {
        try(var running=start()){assertEquals(200,send(running,"GET","/api/v1/health",null,null,null).statusCode());}
        Path backups=root.resolve("data/backups");
        var files=new gold.debug.windowstolinux.web.file.workspace.WebWorkspace(backups,new gold.debug.windowstolinux.web.file.quota.UploadQuota(4L<<30,8L<<30,16L<<30,10000,240),64L<<20);
        var address=new gold.debug.windowstolinux.web.file.workspace.WorkspaceAddress("internal",java.util.UUID.randomUUID().toString());
        Path orphan=files.create(address);Path unknown=Files.createDirectories(orphan.getParent().resolve(java.util.UUID.randomUUID().toString()));
        Files.writeString(unknown.resolve("keep.txt"),"not-owned");
        try(var running=start()){assertFalse(Files.exists(orphan));assertEquals("not-owned",Files.readString(unknown.resolve("keep.txt")));}
    }

    @Test void terminalEventsDrainAllBatchesAndLastEventIdTakesPrecedence() throws Exception {
        try (var runtime = start()) {
            var tasks = runtime.bean(gold.debug.windowstolinux.web.db.persistence.repository.WebTaskRepository.class);
            var scope = gold.debug.windowstolinux.web.db.WebPersistence.INTERNAL_SCOPE;
            String id = java.util.UUID.randomUUID().toString();
            tasks.create(scope, id, "ANALYZE", "{}", java.util.List.of(), false, null, null, null);
            for (int index = 0; index < 510; index++) tasks.event(scope, id, "PROGRESS", "CHECKING", "{}");
            assertTrue(tasks.transition(scope, id, java.util.Set.of("QUEUED"), "SUCCEEDED", "{}", null));
            var all = send(runtime, "GET", "/api/v1/tasks/" + id + "/events", null, null, null);
            assertEquals(512, all.body().lines().filter(line -> line.startsWith("id: ")).count());
            assertTrue(all.body().contains("id: 512\n"));
            var request = HttpRequest.newBuilder(URI.create(runtime.origin() + "/api/v1/tasks/" + id + "/events?after=1"))
                    .header("Last-Event-ID", "510").GET().build();
            var replay = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(2, replay.body().lines().filter(line -> line.startsWith("id: ")).count());
            assertTrue(replay.body().contains("SUCCEEDED"));
        }
    }

    @Test void streamLimitHeartbeatAndDisconnectPreserveBackgroundWork() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var runtime = WebTestApplication.start(root, java.util.Map.of("w2l.http.streams", 1,
                "w2l.http.heartbeat", "50ms", "w2l.http.event-poll", "10ms"))) {
            var scheduler = runtime.bean(gold.debug.windowstolinux.web.task.scheduler.WebTaskScheduler.class);
            var context = runtime.bean(gold.debug.windowstolinux.web.service.contract.WebRequestContext.class);
            var operation = new gold.debug.windowstolinux.web.service.contract.PreparedWebOperation("ANALYZE", WebJson.object(),
                    java.util.List.of(), java.util.List.of(), false, null, null, null,
                    interaction -> { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); return WebJson.object().put("done", true); });
            String id = scheduler.submit(context, operation);
            var request = HttpRequest.newBuilder(URI.create(runtime.origin() + "/api/v1/tasks/" + id + "/events")).GET().build();
            try (var stream = client.send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
                 var lines = new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
                assertEquals(503, send(runtime, "GET", "/api/v1/tasks/" + id + "/events", null, null, null).statusCode());
                boolean heartbeat = false;
                for (String line; (line = lines.readLine()) != null;) {
                    if (line.equals(": heartbeat")) { heartbeat = true; break; }
                }
                assertTrue(heartbeat);
            } finally { release.countDown(); }
            JsonNode snapshot = null;
            for (int i = 0; i < 200; i++) {
                snapshot = scheduler.get(context, id);
                if (snapshot.path("state").asText().equals("SUCCEEDED")) break;
                Thread.sleep(10);
            }
            assertEquals("SUCCEEDED", snapshot.path("state").asText());
        } finally { release.countDown(); }
    }

    @Test void configuredJsonLimitAndFrameworkFailuresUseSafeErrorContract() throws Exception {
        try (var runtime = WebTestApplication.start(root, java.util.Map.of("w2l.http.json-bytes", 128))) {
            var oversized = send(runtime, "PUT", "/api/v1/preferences", "{\"theme\":\"" + "a".repeat(200) + "\"}", runtime.origin(), "application/json");
            assertEquals(413, oversized.statusCode());
            var malformed = send(runtime, "PUT", "/api/v1/preferences", "{", runtime.origin(), "application/json");
            assertEquals(400, malformed.statusCode());
            var wrongMethod = send(runtime, "POST", "/api/v1/health", "{}", runtime.origin(), "application/json");
            assertEquals(405, wrongMethod.statusCode());
            for (var response : java.util.List.of(oversized, malformed, wrongMethod)) {
                assertTrue(WebJson.read(response.body()).has("code"), response.body());
                assertFalse(response.body().contains(root.toString()));
                assertFalse(response.body().contains("Exception"));
            }
        }
    }

    private static void copy(Path from,Path to) throws Exception {
        try(var paths=Files.walk(from)) {
            for(Path source:paths.toList()) {
                Path target=to.resolve(from.relativize(source));
                if(Files.isDirectory(source))Files.createDirectories(target);else Files.copy(source,target);
            }
        }
    }

    private WebTestApplication start() { return WebTestApplication.start(root, java.util.Map.of()); }
    private HttpResponse<String> send(WebTestApplication runtime, String method, String path, String body, String origin, String type) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(runtime.origin() + path)).timeout(java.time.Duration.ofSeconds(10));
        if (origin != null) request.header("Origin", origin).header("X-W2L-Client", "web");
        if (type != null) request.header("Content-Type", type);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode json(HttpResponse<String> response) { assertTrue(response.statusCode() < 300, response.body()); return WebJson.read(response.body()); }
}
