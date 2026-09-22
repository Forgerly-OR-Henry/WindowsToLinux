package gold.debug.windowstolinux.app.main.startup;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import gold.debug.windowstolinux.app.service.contract.definition.AutomaticDeploymentRequest;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in acceptance of repository multilingual fixtures through the product. / 通过产品执行仓库多语言夹具的可选实机验收。 */
@EnabledIfSystemProperty(named = "managed.runtime.polyglot", matches = "true")
class MultilingualLiveDeploymentAcceptanceTest {
    @TempDir
    Path temporaryDirectory;

    @org.junit.jupiter.api.extension.RegisterExtension
    final org.junit.jupiter.api.extension.TestWatcher failureEvidence = new org.junit.jupiter.api.extension.TestWatcher() {
        @Override
        public void testFailed(org.junit.jupiter.api.extension.ExtensionContext context, Throwable failure) {
            System.out.println("POLYGLOT_FAILED " + context.getDisplayName());
            failure.printStackTrace(System.out);
        }
    };

    @Test
    void inspectsTargetBeforeDeployment() throws Exception {
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory)) {
            System.out.println("POLYGLOT_TARGET " + context.trustedServer());
            System.out.println("POLYGLOT_CAPABILITIES " + context.inspectDeploymentCapabilities());
        }
    }

    @Test
    void preparesTargetThroughProduct() throws Exception {
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory)) {
            var result = context.prepareEnvironmentWithSystemConfirmation(plan -> true);
            System.out.println("POLYGLOT_PREPARATION " + result);
            org.junit.jupiter.api.Assertions.assertFalse(result.evidence().isBlank());
        }
    }

    @Test
    void deploysTaskBoardThroughAutomaticEntry() throws Exception {
        String id = "wtl-polyglot-tasks-" + Long.toUnsignedString(System.nanoTime(), 36);
        Path source = copyFixture("task-board", id);
        String host = System.getProperty("managed.ssh.host");
        int apiPort = 45101, webPort = 45100;
        Files.writeString(source.resolve("frontend/public/runtime-config.json"),
                "{\"apiBase\":\"http://" + host + ":" + apiPort + "\"}\n");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("backend/version", "21");
        values.put("backend/port", Integer.toString(apiPort));
        values.put("backend/healthEndpoint", "http://127.0.0.1:" + apiPort + "/healthz");
        values.put("backend/exposure", "EXTERNAL");
        values.put("backend/configuration",
                "HOST=0.0.0.0;PORT=" + apiPort + ";WEB_ORIGIN=http://" + host + ":" + webPort);
        values.put("frontend/version", "24");
        values.put("frontend/port", Integer.toString(webPort));
        values.put("frontend/dependencies", "backend");
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("state"))) {
            try (AutoCloseable cleanup = context::stopTestApplications) {
                var profile = context.service.findServerProfile("typed-live").orElseThrow();
                var result = context.service.deployAutomatically(
                        new AutomaticDeploymentRequest(Optional.of(source), Optional.empty(), profile, values),
                        masterPassword(), interaction(), fingerprint -> true,
                        message -> System.out.println("POLYGLOT_PROGRESS " + message));
                System.out.println("POLYGLOT_DEPLOY " + result);
                assertEquals(DeploymentStatus.SUCCEEDED, result.status());
                assertHttp("http://" + host + ":" + apiPort + "/healthz", "ok");
                assertHttp("http://" + host + ":" + webPort + "/", "任务");
                String api = "http://" + host + ":" + apiPort;
                var created = requestJson(api + "/api/tasks",
                        Map.of("projectId", 1, "title", "实机部署持久化验证", "description", "Java + TypeScript", "ownerId", 1,
                                "priority", 2, "labels", List.of("live"), "dependsOn", List.of(), "dueDate",
                                "2030-10-01", "version", 0, "actorId", 1));
                assertTrue(created.path("id").asLong() > 0, created::toString);
                System.out.println("POLYGLOT_BUSINESS task-board created task " + created.path("id").asLong());
                for (var action : List.of(gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.STOP,
                        gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.START,
                        gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.RESTART)) {
                    var observed = context.lifecycleMulti(id, java.util.Set.of("backend", "frontend"), action);
                    System.out.println("POLYGLOT_LIFECYCLE " + action + " " + observed);
                    assertTrue(observed.accepted(), observed::toString);
                    assertEquals(
                            action == gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.STOP
                                    ? gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState.STOPPED
                                    : gold.debug.windowstolinux.shared.model.lifecycle.ApplicationRuntimeState.RUNNING,
                            observed.runtimeState());
                }
                assertHttp(api + "/api/tasks/" + created.path("id").asLong() + "?projectId=1", "实机部署持久化验证");
                System.out.println(
                        "POLYGLOT_BUSINESS task-board create + stop/start/restart + SQLite persistence passed");
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"file-transfer", "asset-lending"})
    void deploysGatewayAndBackendThroughAutomaticEntry(String project) throws Exception {
        selectedProject(project);
        String id = "wtl-polyglot-" + project + "-" + Long.toUnsignedString(System.nanoTime(), 36);
        Path source = copyFixture(project, id);
        String host = System.getProperty("managed.ssh.host");
        int webPort = project.equals("file-transfer") ? 45110 : 45120, apiPort = webPort + 1;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("backend/port", Integer.toString(apiPort));
        values.put("backend/healthMode", "HTTP");
        values.put("backend/healthEndpoint", "http://127.0.0.1:" + apiPort + "/healthz");
        values.put("backend/exposure", "INTERNAL");
        values.put("backend/bindAddress", "127.0.0.1");
        values.put("backend/configuration", "HOST=127.0.0.1;PORT=" + apiPort);
        values.put("web/version", "24");
        values.put("web/port", Integer.toString(webPort));
        values.put("web/healthMode", "HTTP");
        values.put("web/healthEndpoint", "http://127.0.0.1:" + webPort + "/readyz");
        values.put("web/exposure", "EXTERNAL");
        values.put("web/configuration", "HOST=0.0.0.0;PORT=" + webPort + ";API_URL=http://127.0.0.1:" + apiPort);
        values.put("web/dependencies", "backend");
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("state"));
                AutoCloseable cleanup = context::stopTestApplications) {
            var result = context.service.deployAutomatically(
                    new AutomaticDeploymentRequest(Optional.of(source), Optional.empty(),
                            context.service.findServerProfile("typed-live").orElseThrow(), values),
                    masterPassword(), interaction(), fingerprint -> true,
                    message -> System.out.println("POLYGLOT_PROGRESS " + message));
            System.out.println("POLYGLOT_DEPLOY " + project + " " + result);
            assertEquals(DeploymentStatus.SUCCEEDED, result.status());
            String url = "http://" + host + ":" + webPort;
            assertHttp(url + "/readyz", "ok");
            assertHttp(url + "/", "<!doctype html>");
            String resource = project.equals("file-transfer") ? "/api/folders" : "/api/categories";
            var created = requestJson(url + resource, Map.of("name", "实机跨语言验证"));
            assertTrue(created.path("id").asLong() > 0, created::toString);
            String persistedUrl;
            byte[] fileContent = new byte[65536];
            if (project.equals("file-transfer")) {
                new java.util.Random(20260920).nextBytes(fileContent);
                String sha = java.util.HexFormat.of()
                        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fileContent));
                var upload = requestJson(url + "/api/uploads", Map.of("folderId", created.path("id").asLong(), "name",
                        "实机文件.bin", "size", fileContent.length, "sha256", sha, "chunkSize", fileContent.length));
                String uploadUrl = url + "/api/uploads/" + upload.path("id").asText();
                transferBytes(uploadUrl + "/chunks/0", "PUT", fileContent);
                var version = requestJson(uploadUrl + "/complete", Map.of());
                persistedUrl = url + "/api/versions/" + version.path("id").asText() + "/download";
                assertArrayEquals(fileContent, transferBytes(persistedUrl, "GET", null));
            } else {
                var asset = requestJson(url + "/api/assets",
                        Map.of("name", "实机借还资产", "serial", "LIVE-001", "categoryId", created.path("id").asLong()));
                long assetId = asset.path("id").asLong();
                assertTrue(assetId > 0, asset::toString);
                var loan = requestJson(url + "/api/loans", Map.of("assetIds", List.of(assetId), "borrower", "林同学",
                        "dueDate", "2030-01-01", "purpose", "实机借还", "actor", "林同学", "requestId", "live-loan"));
                persistedUrl = url + "/api/loans/" + loan.path("id").asText();
                for (String action : List.of("submit", "approve", "checkout"))
                    requestJson(persistedUrl + "/" + action, Map.of("actor", "陈老师", "requestId", "live-" + action));
                var returned = requestJson(persistedUrl + "/return", Map.of("actor", "陈老师", "requestId", "live-return",
                        "items", List.of(Map.of("assetId", assetId, "condition", "good", "note", "完好"))));
                assertEquals("closed", returned.path("status").asText(), returned::toString);
            }
            var restarted = context.lifecycleMulti(id, java.util.Set.of("backend", "web"),
                    gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.RESTART);
            assertTrue(restarted.accepted(), restarted::toString);
            assertHttp(url + resource, "实机跨语言验证");
            if (project.equals("file-transfer"))
                assertArrayEquals(fileContent, transferBytes(persistedUrl, "GET", null));
            else
                assertHttp(persistedUrl, "closed");
            System.out.println("POLYGLOT_BUSINESS " + project + " gateway "
                    + (project.equals("file-transfer") ? "upload/download bytes" : "asset loan/return")
                    + " + restart + SQLite persistence passed");
        }
    }

    @Test
    void deploysSurveyScoringThroughAutomaticEntry() throws Exception {
        String id = "wtl-polyglot-survey-" + Long.toUnsignedString(System.nanoTime(), 36);
        Path source = copyFixture("survey-scoring", id);
        String host = System.getProperty("managed.ssh.host");
        int webPort = 45140, apiPort = 45141;
        Files.writeString(source.resolve("frontend/public/runtime-config.json"),
                "{\"apiBase\":\"http://" + host + ":" + apiPort + "\"}\n");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("scorer/configuration", "HOST=127.0.0.1;PORT=18142");
        values.put("backend/port", Integer.toString(apiPort));
        values.put("backend/healthMode", "HTTP");
        values.put("backend/healthEndpoint", "http://127.0.0.1:" + apiPort + "/healthz");
        values.put("backend/exposure", "EXTERNAL");
        values.put("backend/configuration", "HOST=0.0.0.0;PORT=" + apiPort + ";WEB_ORIGIN=http://" + host + ":"
                + webPort + ";SCORER_URL=http://127.0.0.1:18142");
        values.put("backend/dependencies", "scorer");
        values.put("frontend/version", "24");
        values.put("frontend/port", Integer.toString(webPort));
        values.put("frontend/dependencies", "backend");
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("state"));
                AutoCloseable cleanup = context::stopTestApplications) {
            var result = context.service.deployAutomatically(
                    new AutomaticDeploymentRequest(Optional.of(source), Optional.empty(),
                            context.service.findServerProfile("typed-live").orElseThrow(), values),
                    masterPassword(), interaction(), fingerprint -> true,
                    message -> System.out.println("POLYGLOT_PROGRESS " + message));
            System.out.println("POLYGLOT_DEPLOY survey-scoring " + result);
            assertEquals(DeploymentStatus.SUCCEEDED, result.status());
            String api = "http://" + host + ":" + apiPort;
            assertHttp("http://" + host + ":" + webPort + "/", "问卷");
            var scored = requestJson(api + "/api/submissions", Map.of("revisionId", 1, "requestId", "live-score-001",
                    "respondent", "林同学", "answers",
                    Map.of("participated", "yes", "quality", 4, "support", List.of("docs", "peer"), "friction", 2)));
            assertTrue(scored.path("id").asLong() > 0, scored::toString);
            assertEquals(84.21, scored.at("/result/score").asDouble(), 0.001, scored::toString);
            var restarted = context.lifecycleMulti(id, java.util.Set.of("scorer", "backend", "frontend"),
                    gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.RESTART);
            assertTrue(restarted.accepted(), restarted::toString);
            assertHttp(api + "/api/submissions/" + scored.path("id").asLong(), "84.21");
            System.out.println(
                    "POLYGLOT_BUSINESS survey-scoring Kotlin/Ruby score + restart + SQLite persistence passed");
        }
    }

    private Path copyFixture(String project, String name) throws Exception {
        Path original = MultilingualDeploymentPlanningTest.repositoryRoot()
                .resolve("test/multi-language/success-" + project);
        Path target = temporaryDirectory.resolve("sources").resolve(name);
        try (var paths = Files.walk(original)) {
            for (Path path : paths.toList()) {
                Path output = target.resolve(original.relativize(path));
                if (Files.isDirectory(path))
                    Files.createDirectories(output);
                else
                    Files.copy(path, output);
            }
        }
        return target;
    }

    @Test
    void deploysCsvQueueAndAnalyzerThroughAutomaticEntry() throws Exception {
        String id = "wtl-polyglot-csv-" + Long.toUnsignedString(System.nanoTime(), 36);
        Path source = copyFixture("csv-inspector", id);
        String host = System.getProperty("managed.ssh.host");
        Path declaration = source.resolve("web/windowstolinux-application.properties");
        Files.writeString(declaration, Files.readString(declaration).replace("18130", "45130"));
        Map<String, String> values = Map.of("web/dependencies", "analyzer", "web/configuration",
                "ANALYZER_URL=http://127.0.0.1:18131", "analyzer/configuration", "HOST=127.0.0.1;PORT=18131");
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("state"));
                AutoCloseable cleanup = context::stopTestApplications) {
            var request = new AutomaticDeploymentRequest(Optional.of(source), Optional.empty(),
                    context.service.findServerProfile("typed-live").orElseThrow(), values);
            var result = context.service.deployAutomatically(request, masterPassword(), interaction(),
                    fingerprint -> true, message -> System.out.println("POLYGLOT_PROGRESS " + message));
            System.out.println("POLYGLOT_DEPLOY csv-inspector " + result);
            assertEquals(DeploymentStatus.SUCCEEDED, result.status());
            String url = "http://" + host + ":45130";
            assertHttp(url + "/", "CSV");
            var worker = getJson(url + "/api/worker");
            assertTrue(worker.path("pid").asLong() > 0, worker::toString);
            var job = requestJson(url + "/api/jobs",
                    Map.of("datasetId", "demo", "templateId", "basic", "requestId", "live-csv-001"));
            String jobUrl = url + "/api/jobs/" + job.path("id").asText();
            com.fasterxml.jackson.databind.JsonNode completed = job;
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(40).toNanos();
            while (!completed.path("status").asText().equals("completed") && System.nanoTime() < deadline) {
                Thread.sleep(250);
                completed = getJson(jobUrl);
                assertFalse(completed.path("status").asText().equals("failed"), completed::toString);
            }
            assertEquals("completed", completed.path("status").asText(), completed::toString);
            assertEquals(3, completed.at("/summary/rows").asInt());
            assertEquals(6, completed.at("/summary/issueCount").asInt());
            assertEquals(1, completed.at("/summary/validRows").asInt());
            var restarted = context.lifecycleMulti(id, java.util.Set.of("web", "analyzer"),
                    gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction.RESTART);
            assertTrue(restarted.accepted(), restarted::toString);
            assertNotEquals(worker.path("pid").asLong(), getJson(url + "/api/worker").path("pid").asLong());
            assertHttp(jobUrl, "completed");
            assertHttp(jobUrl + "/export", "issueCount");
            System.out.println(
                    "POLYGLOT_BUSINESS csv-inspector PHP queue/Python report + unified restart + SQLite persistence passed");
        }
    }

    private static byte[] transferBytes(String url, String method, byte[] body) throws Exception {
        var connection = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod(method);
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/octet-stream");
                connection.setFixedLengthStreamingMode(body.length);
                try (var output = connection.getOutputStream()) {
                    output.write(body);
                }
            }
            assertEquals(200, connection.getResponseCode(), url);
            try (var input = connection.getInputStream()) {
                return input.readAllBytes();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode getJson(String url) throws Exception {
        var connection = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        try {
            assertEquals(200, connection.getResponseCode(), url);
            try (var input = connection.getInputStream()) {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            }
        } finally {
            connection.disconnect();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"log-analyzer", "binary-inspector", "directory-diff"})
    void deploysOnDemandMixedApplications(String project) throws Exception {
        selectedProject(project);
        String id = "wtl-polyglot-" + project + "-" + Long.toUnsignedString(System.nanoTime(), 36);
        Path source = copyFixture(project, id);
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(temporaryDirectory.resolve("state"))) {
            var result = context.service.deployAutomatically(
                    new AutomaticDeploymentRequest(Optional.of(source), Optional.empty(),
                            context.service.findServerProfile("typed-live").orElseThrow(), Map.of()),
                    masterPassword(), interaction(), fingerprint -> true,
                    message -> System.out.println("POLYGLOT_PROGRESS " + message));
            System.out.println("POLYGLOT_DEPLOY " + project + " " + result);
            assertEquals(DeploymentStatus.SUCCEEDED, result.status());
            assertEquals(id, result.applicationId());
            assertEquals(1, result.handoffs().size());
            var entry = assertInstanceOf(
                    gold.debug.windowstolinux.app.service.deployment.single.DeploymentHandoff.ApplicationEntry.class,
                    result.handoffs().values().iterator().next());
            assertFalse(entry.usage().lifecycle());
            String arguments = switch (project) {
                case "log-analyzer" -> " --input samples/events.log --format json";
                case "binary-inspector" -> " --input samples/normal.bin --format json";
                case "directory-diff" ->
                    " --db data/snapshots.db --format json snapshot --root samples/baseline --name live-baseline";
                default -> throw new AssertionError(project);
            };
            var command = executePublishedEntry(context, entry.command() + arguments);
            assertTrue(command.succeeded(), command::failureEvidence);
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(command.output());
            assertEquals("complete", json.path("status").asText(), json::toString);
            if (project.equals("log-analyzer")) {
                assertEquals(7, json.at("/summary/lines").asInt());
                assertEquals(6, json.at("/summary/matched").asInt());
                assertEquals(3, json.at("/summary/levels/ERROR").asInt());
            } else if (project.equals("binary-inspector")) {
                assertEquals(5, json.at("/summary/records").asInt());
                assertEquals("7", json.at("/summary/sum").asText());
                var rejected = executePublishedEntry(context,
                        entry.command() + " --input samples/corrupt.bin --format json");
                assertEquals(2, rejected.exitStatus(), rejected::failureEvidence);
            } else {
                var history = executePublishedEntry(context,
                        entry.command() + " --db data/snapshots.db --format json list");
                assertTrue(history.succeeded(), history::failureEvidence);
                assertTrue(history.output().contains("live-baseline"), history::output);
            }
            System.out.println("POLYGLOT_BUSINESS " + project + " installed entry and mixed-language sample passed");
        }
    }

    private static void selectedProject(String project) {
        String projects = System.getProperty("managed.polyglot.projects", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                projects.isBlank() || List.of(projects.split(",")).contains(project),
                "project not selected for this live run");
    }

    private static gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult executePublishedEntry(
            LiveTypedDeploymentContext context, String command) throws Exception {
        assertTrue(command.startsWith("sudo /usr/local/lib/windowstolinux/managed-helper app-run "));
        return withSshCommands(context,
                executor -> executor.execProtocol(command, java.time.Duration.ofMinutes(3), true));
    }

    @Test
    @EnabledIfSystemProperty(named = "managed.runtime.polyglotRepair", matches = "true")
    void cleansConfirmedInterruptedCandidatesThroughProductAdapter() throws Exception {
        String supplied = System.getProperty("managed.cleanup.candidates");
        assertNotNull(supplied, "explicit candidate identities required");
        try (LiveTypedDeploymentContext context = new LiveTypedDeploymentContext(
                temporaryDirectory.resolve("repair-state"))) {
            withSshCommands(context, executor -> {
                var adapter = new gold.debug.windowstolinux.shared.linux.sshd.execution.protocol.CandidateWorkspaceExecutor(
                        executor);
                for (String declaration : supplied.split(",")) {
                    String[] fields = declaration.split(":", -1);
                    assertEquals(2, fields.length);
                    assertTrue(fields[0].startsWith("wtl-polyglot-"));
                    var candidate = new gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace(fields[0],
                            fields[1]);
                    var cleaned = adapter.cleanup(candidate);
                    assertTrue(cleaned.succeeded(), cleaned::evidence);
                    System.out.println("POLYGLOT_REPAIR " + candidate.candidateId() + " " + cleaned.evidence());
                }
                return null;
            });
        }
    }

    @FunctionalInterface
    private interface SshAction<T> {
        T apply(gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor commands) throws Exception;
    }

    private static <T> T withSshCommands(LiveTypedDeploymentContext context, SshAction<T> action) throws Exception {
        try (var client = org.apache.sshd.client.SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier((session, address, key) -> context.trustedServer().hostKeySha256()
                    .equals(org.apache.sshd.common.config.keys.KeyUtils
                            .getFingerPrint(org.apache.sshd.common.digest.BuiltinDigests.sha256, key)));
            client.start();
            try (var session = client
                    .connect("root", System.getProperty("managed.ssh.host"), Integer.getInteger("managed.ssh.port", 22))
                    .verify(java.time.Duration.ofSeconds(20)).getSession()) {
                session.addPasswordIdentity(System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD"));
                session.auth().verify(java.time.Duration.ofSeconds(20));
                return action
                        .apply(new gold.debug.windowstolinux.shared.linux.sshd.command.SshCommandExecutor(session));
            }
        }
    }

    private static AutomaticDeploymentInteraction interaction() {
        return new AutomaticDeploymentInteraction() {
            public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                Map<String, String> answers = new LinkedHashMap<>();
                for (var field : fields) {
                    System.out.println("POLYGLOT_QUESTION " + field);
                    if (field.id().endsWith("/dependencies"))
                        answers.put(field.id(), "");
                    else if (field.id().endsWith("/healthOwner") && field.choices().contains("frontend"))
                        answers.put(field.id(), "frontend");
                    else if (field.id().endsWith("/healthOwner") && field.choices().contains("web"))
                        answers.put(field.id(), "web");
                    else
                        throw new AssertionError("unresolved deployment input: " + field);
                }
                return Optional.of(answers);
            }

            public boolean confirm(String key, Map<String, ?> details) {
                System.out.println("POLYGLOT_AUTHORIZED " + key + " " + details);
                return true;
            }

            public char[] requestSecret(String key) {
                throw new AssertionError("unexpected secret request: " + key);
            }
        };
    }

    private static char[] masterPassword() {
        return System.getenv().getOrDefault("WINDOWSTOLINUX_TEST_MASTER_PASSWORD", "typed-live-acceptance-master")
                .toCharArray();
    }

    private static void assertHttp(String url, String expected) throws Exception {
        var connection = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        try {
            assertEquals(200, connection.getResponseCode(), url);
            try (var input = connection.getInputStream()) {
                assertTrue(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains(expected),
                        url);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode requestJson(String url, Map<String, Object> body)
            throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var connection = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        try {
            try (var output = connection.getOutputStream()) {
                output.write(mapper.writeValueAsBytes(body));
            }
            assertEquals(200, connection.getResponseCode(), url);
            try (var input = connection.getInputStream()) {
                return mapper.readTree(input);
            }
        } finally {
            connection.disconnect();
        }
    }
}
