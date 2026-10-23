package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.secret.DeploymentSecretRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real task/file/secret custody with a Java RemoteSession peer; no SSH connection or provider calls. */
class DeploymentAgentCapabilitiesTest extends DeploymentRuntimeFixture {
    private final List<ActionIntent> approvals = new CopyOnWriteArrayList<>();

    private boolean allowBusiness = true;

    private final ObjectMapper json = new ObjectMapper();

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                approvals.add(intent);
                return allowBusiness;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task, CancelToken cancellation) {
                assertTrue(secret, "Completed source declarations require no guessed answers");
                return "controlled-sensitive-value".toCharArray();
            }

            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server, TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError();
            }
        };
    }

    @Test
    void sharedRecipePreflightRejectsMissingRejectedPreviewAndStaleRootsWithoutRemoteEffects() throws Exception {
        run("index.html", "<html>original</html>\n", (context, task, server, material, journal) -> {
            var peer = peer(request -> {
                throw new AssertionError("Preflight and source analysis must not send any remote command");
            });
            try (var sources = new DeploymentAgentSources(context, task, material)) {
                var projects = new DeploymentAgentProjectChannel(peer, context.interaction(), server, task, "a".repeat(64), () -> "a".repeat(64));
                var tools = new DeploymentAgentBuildTools(context, task, peer, server, "a".repeat(64), () -> {
                }, sources, projects);
                var part = part(List.of(), List.of());
                var recipe = capability("prepare_tools", "{\"recipe\":{\"version\":\"\",\"primary\":\"public\",\"secondary\":\"\",\"jvmTarget\":\"\",\"servicePort\":0}}");
                assertFalse(json.valueToTree(tools.state(part)).path("analysis").path("present").asBoolean());
                assertFalse(json.valueToTree(tools.state(part)).path("recipeApplicable").asBoolean());
                assertThrows(IllegalArgumentException.class, () -> tools.validateCapability(recipe, part));
                String rejected = tools.run(capability("analyze", "{\"type\":\"PYTHON_SERVICE\"}"), part, null, "a".repeat(64));
                assertTrue(rejected.contains("admission=REJECTED"), rejected);
                assertFalse(json.valueToTree(tools.state(part)).path("analysis").path("admissible").asBoolean());
                assertThrows(IllegalArgumentException.class, () -> tools.validateCapability(recipe, part));
                String preview = tools.run(capability("analyze", "{\"type\":\"RECOGNITION_PREVIEW\"}"), part, null, "a".repeat(64));
                assertTrue(preview.contains("admission=RECOGNITION_PREVIEW"), preview);
                assertThrows(IllegalArgumentException.class, () -> tools.validateCapability(recipe, part));
                tools.run(capability("analyze", "{\"type\":\"STATIC_SITE\"}"), part, null, "a".repeat(64));
                var state = json.valueToTree(tools.state(part));
                assertEquals("STATIC_SITE", state.path("analysis").path("projectType").asText());
                assertTrue(state.path("analysis").path("admissible").asBoolean());
                assertFalse(state.path("recipeApplicable").asBoolean());
                assertDoesNotThrow(() -> tools.validateCapability(recipe, part));
                assertThrows(IllegalArgumentException.class, () -> tools.validateCapability(recipe, part(List.of(), List.of(), "public")));
                var invalidRecipe = capability("prepare_tools", "{\"recipe\":{\"version\":\"\",\"primary\":\"public\",\"secondary\":\"\",\"jvmTarget\":\"\",\"servicePort\":-1}}");
                assertThrows(IllegalArgumentException.class, () -> tools.validateCapability(invalidRecipe, part));
                assertThrows(IllegalArgumentException.class, () -> tools.validateCapability(capability("build", "{\"seconds\":60}"), part));
                assertTrue(approvals.isEmpty(), "Local validation and analysis grant no authority and request no human approval");
                assertTrue(tools.tools("api").selections().isEmpty(), "A valid preflight does not install or bind tools");
            }
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4})
    void standardRecipeUsesCurrentProtectedSourceAndCanBeSelectedAgainAfterPatch(int buildExit) throws Exception {
        run("index.html", "<html>original</html>\n", (context, task, server, material, journal) -> {
            var revision = new AtomicReference<>(DeploymentAgentSourceRevision.digest(material.snapshot().directory()));
            var scripts = new ArrayList<String>();
            var peer = peer(request -> {
                if (request.script().contains("'agent-open'") || request.script().contains("'agent-patch'"))
                    return reply("SOURCE_REVISION=" + revision.get() + "\n");
                if (request.script().contains("build-preflight")) return reply("");
                if (request.script().contains("prepare-toolchains 'prepare'"))
                    return reply("TOOLCHAIN=PYTHON|3.12.9|/usr/local/lib/windowstolinux/toolchains/versions/python-" + "e".repeat(64) + "|MANAGED|https://official.example/python|" + "e".repeat(64) + "\n");
                if (request.script().contains("prepare-toolchains 'bind'")) {
                    var matcher = java.util.regex.Pattern.compile("'bind' '([A-Za-z0-9+/=]+)'").matcher(request.script());
                    assertTrue(matcher.find());
                    var decoded = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.decode(new String(Base64.getDecoder().decode(matcher.group(1)), java.nio.charset.StandardCharsets.UTF_8));
                    return reply("TOOLCHAIN_BINDING=" + gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainBindingCodec.identity(decoded) + "\n");
                }
                assertTrue(request.script().contains("'agent-run'"), request.script());
                String script = new String(request.stdin(), java.nio.charset.StandardCharsets.UTF_8);
                if (script.equals("printf 'later command'")) {
                    scripts.add(script);
                    return new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.of(2), "Expected error case left the entire command nonzero\n", "", false)),
                        Optional.of(new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO", "Controlled later command", Map.of(), Optional.of(task.id()))));
                }
                assertTrue(script.contains("WTL_AGENT_SOURCE=\"$WTL_SOURCE\""));
                assertTrue(script.contains("cp -R -- \"$WTL_AGENT_SOURCE/.\""));
                scripts.add(script);
                return buildExit == 0 ? reply("ARTIFACT=.\n")
                    : new OperationResult<>(TaskResult.FAILED, Optional.of(new CommandResult(Optional.of(buildExit), "Known recipe failure after touching output\n", "", false)),
                    Optional.of(new Problem(ProblemCode.IO_FAILURE, "SSH_COMMAND_NONZERO", "Controlled nonzero exit", Map.of(), Optional.of(task.id()))));
            });
            try (var sources = new DeploymentAgentSources(context, task, material)) {
                var projects = new DeploymentAgentProjectChannel(peer, context.interaction(), server, task, "a".repeat(64), () -> "a".repeat(64));
                var workspace = new DeploymentRemoteWorkspace("demo-api", "b".repeat(64));
                var candidate = new DeploymentAgentDeliveryPort.Prepared(workspace, projects.open(workspace, "ordinary"));
                var part = part(List.of(), List.of());
                var tools = new DeploymentAgentBuildTools(context, task, peer, server, "a".repeat(64), () -> {
                }, sources, projects);
                analyzeAndBuild(tools, part, candidate, revision.get(), buildExit);
                assertEquals(1, tools.executed("api").selections().size());
                var ready = json.valueToTree(tools.state(part));
                assertTrue(ready.path("recipeApplicable").asBoolean());
                assertEquals(json.readTree("{\"version\":\"\",\"primary\":\"public\",\"secondary\":\"\",\"jvmTarget\":\"\",\"servicePort\":0}"),
                    ready.path("selectedRecipe").path("parameters"));
                assertEquals(1, ready.path("selectedToolchains").path("selectionCount").asInt());
                assertEquals("PYTHON", ready.path("selectedToolchains").path("ecosystems").get(0).asText());
                assertEquals(ready.path("selectedToolchains").path("bindingSha256"), ready.path("executedToolchains").path("bindingSha256"));
                assertEquals(buildExit, ready.path("latestKnownBuild").path("exitStatus").asInt());
                assertEquals(buildExit == 0, ready.path("latestKnownBuild").path("succeeded").asBoolean());
                assertTrue(ready.path("latestKnownBuild").path("bindingCurrent").asBoolean());
                assertTrue(ready.path("latestKnownBuild").path("outputCurrent").asBoolean());
                assertEquals(buildExit == 0, ready.path("latestKnownBuild").path("successfulRunCurrent").asBoolean());
                if (buildExit == 0) assertDoesNotThrow(() -> projects.requireSealable(candidate, revision.get()));
                else
                    assertThrows(IllegalArgumentException.class, () -> projects.requireSealable(candidate, revision.get()));
                assertTrue(ready.toString().length() < 6000);
                assertFalse(ready.toString().contains("official.example"));
                assertFalse(ready.toString().contains("/usr/local"));
                int reviewed = approvals.size();
                for (int i = 0; i < 10; i++) assertEquals(ready, json.valueToTree(tools.state(part)));
                assertEquals(reviewed, approvals.size(), "Reading local facts grants no fresh approval or sends any command");
                assertEquals(1, scripts.size());
                assertEquals(2, projects.command(candidate, revision.get(), "printf 'later command'", Duration.ofSeconds(60)).exitStatus());
                var superseded = json.valueToTree(tools.state(part)).path("latestKnownBuild");
                assertEquals(buildExit, superseded.path("exitStatus").asInt(), "Historical build result remains available");
                assertTrue(superseded.path("bindingCurrent").asBoolean(), "Recipe metadata can remain current after another command");
                assertFalse(superseded.path("outputCurrent").asBoolean());
                assertFalse(superseded.path("successfulRunCurrent").asBoolean());
                assertThrows(IllegalArgumentException.class, () -> projects.requireSealable(candidate, revision.get()));
                var changedRoot = json.valueToTree(tools.state(part(List.of(), List.of(), "public")));
                assertFalse(changedRoot.path("analysis").path("rootCurrent").asBoolean());
                assertFalse(changedRoot.path("recipeApplicable").asBoolean());
                var changedWorkload = json.valueToTree(tools.state(part(List.of(), List.of(), ".", "other-run.sh")));
                assertTrue(changedWorkload.path("analysis").path("sourceCurrent").asBoolean());
                assertFalse(changedWorkload.path("selectedRecipe").path("workloadCurrent").asBoolean());
                assertFalse(changedWorkload.path("recipeApplicable").asBoolean());
                assertThrows(IllegalArgumentException.class, () -> tools.validateCapability(capability("build", "{\"seconds\":60}"),
                    part(List.of(), List.of(), ".", "other-run.sh")));
                tools.run(capability("analyze", "{\"type\":\"STATIC_SITE\"}"), part, null, revision.get());
                var reanalyzed = json.valueToTree(tools.state(part));
                assertFalse(reanalyzed.path("selectedRecipe").path("present").asBoolean());
                assertFalse(reanalyzed.path("recipeApplicable").asBoolean());
                assertTrue(reanalyzed.path("latestKnownBuild").path("present").asBoolean(), "Reanalysis cannot erase a known historical build receipt");
                String before = hash("<html>original</html>\n");
                var patch = new DeploymentAgentPatch("index.html", before, revision.get(), List.of(new DeploymentAgentPatch.Edit(1, List.of("<html>original</html>"), List.of("<html>revised</html>"))));
                var expected = sources.predict("api", patch);
                assertThrows(IllegalStateException.class, () -> sources.promote("api", expected, "c".repeat(64)));
                assertEquals(material, sources.material("api"));
                revision.set(expected.revision());
                sources.promote("api", expected, projects.patch(candidate, patch));
                var stale = json.valueToTree(tools.state(part));
                assertFalse(stale.path("analysis").path("sourceCurrent").asBoolean());
                assertFalse(stale.path("recipeApplicable").asBoolean());
                assertFalse(stale.path("executedBinding").path("sourceCurrent").asBoolean());
                assertFalse(stale.path("latestKnownBuild").path("sourceCurrent").asBoolean());
                assertEquals(buildExit, stale.path("latestKnownBuild").path("exitStatus").asInt(), "Old known exit is data, not current readiness");
                assertFalse(stale.path("latestKnownBuild").path("outputCurrent").asBoolean());
                assertEquals("<html>original</html>\n", Files.readString(material.snapshot().directory().resolve("index.html")));
                assertEquals("<html>revised</html>\n", Files.readString(sources.material("api").snapshot().directory().resolve("index.html")));
                assertThrows(IllegalArgumentException.class, () -> tools.run(capability("build", "{\"seconds\":60}"), part, candidate, revision.get()));
                assertThrows(IllegalArgumentException.class, () -> tools.validateCapability(capability("prepare_tools", "{\"recipe\":{\"version\":\"\",\"primary\":\"public\",\"secondary\":\"\",\"jvmTarget\":\"\",\"servicePort\":0}}"), part));
                tools.replaceConfiguration("f".repeat(64));
                analyzeAndBuild(tools, part, candidate, revision.get(), buildExit);
                var rebuilt = json.valueToTree(tools.state(part));
                assertTrue(rebuilt.path("recipeApplicable").asBoolean());
                assertTrue(rebuilt.path("latestKnownBuild").path("sourceCurrent").asBoolean());
                assertNotEquals(ready.path("latestKnownBuild").path("sourceSnapshot"), rebuilt.path("latestKnownBuild").path("sourceSnapshot"));
                assertTrue(approvals.stream().anyMatch(v -> v.revision().equals("f".repeat(64)) && v.semantics().orElseThrow().operation().equals("deployment.toolchain.prepare.v1")));
                assertEquals(3, scripts.size());
            }
        });
    }

    @Test
    void sqliteAndExactSecretReferencesRequireBusinessConsentAndSecureSelection() throws Exception {
        run("windowstolinux-db.properties", "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\ndb.main.initialize=schema.sql\n", (context, task, server, material, journal) -> {
            try (var sources = new DeploymentAgentSources(context, task, material)) {
                var resources = new DeploymentAgentResourceTools(context, task, server, peer(request -> {
                    throw new AssertionError("SQLite local preparation has no remote effects");
                }), sources, journal);
                var part = part(List.of(), List.of());
                var candidate = new DeploymentAgentDeliveryPort.Prepared(new DeploymentRemoteWorkspace("demo-api", "b".repeat(64)), "c".repeat(64));
                resources.run(candidate.workspace().applicationId(), capability("database_inspect", "{\"scope\":\"SOURCE\"}"), part, candidate, "a".repeat(64));
                allowBusiness = false;
                assertThrows(Exception.class, () -> resources.run(candidate.workspace().applicationId(), capability("databases", "{\"selections\":[]}"), part, candidate, "b".repeat(64)));
                allowBusiness = true;
                assertTrue(resources.run(candidate.workspace().applicationId(), capability("databases", "{\"selections\":[]}"), part, candidate, "b".repeat(64)).contains("DATABASES_PREPARED"));
                assertThrows(IllegalArgumentException.class, () -> resources.resolve(part));
                var resolved = resources.resolve(part(List.of("main"), List.of()));
                assertEquals(1, resolved.resources().databaseBindings().orElseThrow().size());
                var ref = new DeploymentSecretRef("runtime-key", 1);
                journal.event(task, "CAPABILITY_EXECUTING", "d".repeat(64), "secret_create");
                String created = resources.run(candidate.workspace().applicationId(), capability("secret_create", "{\"identifier\":\"runtime-key\",\"revision\":1,\"purpose\":\"deployment-runtime\"}"), part, candidate, "d".repeat(64));
                assertFalse(created.contains("controlled-sensitive-value"));
                assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("secret_create", "{\"identifier\":\"runtime-key\",\"revision\":1,\"purpose\":\"deployment-runtime\"}"), part, candidate, "d".repeat(64)));
                assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("secret_select", "{\"identifier\":\"runtime-key\",\"revision\":1,\"purpose\":\"deployment-database\"}"), part, candidate, "d".repeat(64)));
                assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("secret_select", "{\"identifier\":\"runtime-key\",\"revision\":2,\"purpose\":\"deployment-runtime\"}"), part, candidate, "d".repeat(64)));
                var selected = resources.resolve(part(List.of("main"), List.of(ref)));
                assertEquals(Set.of(ref), resources.credentials(selected).keySet());
                assertFalse(journal.events(task).toString().contains("controlled-sensitive-value"));
                // Simulate interruption after the durable child settled but before the root result.
                resources.releaseKnown();
                assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
                assertTrue(journal.events(task).stream().anyMatch(v -> v.type().equals("CAPABILITY_RESULT") && v.detail().equals("ORIGINAL_RECEIPTS_SETTLED")));
                // Indexed intent before child creation proves absence without a second create call.
                journal.event(task, "CAPABILITY_EXECUTING", "e".repeat(64), "secret_create");
                journal.event(task, "AGENT_SECRET_INDEX", "deployment-secret-revision." + "f".repeat(32), "e".repeat(64) + ":deployment-runtime");
                resources.releaseKnown();
                assertTrue(DeploymentAgentActionLog.effectsSettled(journal.events(task)));
            }
            assertTrue(approvals.stream().anyMatch(v -> v.semantics().orElseThrow().operation().equals("db.sqlite-initialization")));
        });
    }

    @Test
    void changedDatabaseSourceRequiresNewReviewAndNeverReplaysInitialization() throws Exception {
        run("windowstolinux-db.properties", "db=main\ndb.main.engine=SQLITE\ndb.main.path=state/store.db\ndb.main.initialize=schema.sql\n", (context, task, server, material, journal) -> {
            try (var sources = new DeploymentAgentSources(context, task, material)) {
                var resources = new DeploymentAgentResourceTools(context, task, server, peer(request -> {
                    throw new AssertionError("No remote database replay");
                }), sources, journal);
                var part = part(List.of(), List.of());
                var candidate = new DeploymentAgentDeliveryPort.Prepared(new DeploymentRemoteWorkspace("demo-api", "b".repeat(64)), "c".repeat(64));
                resources.run(candidate.workspace().applicationId(), capability("database_inspect", "{\"scope\":\"SOURCE\"}"), part, candidate, "a".repeat(64));
                resources.run(candidate.workspace().applicationId(), capability("databases", "{\"selections\":[]}"), part, candidate, "b".repeat(64));
                var unrelated = new DeploymentAgentPatch("notes.txt", hash("old\n"), DeploymentAgentSourceRevision.digest(sources.material("api").snapshot().directory()), List.of(new DeploymentAgentPatch.Edit(1, List.of("old"), List.of("new"))));
                var copy = sources.predict("api", unrelated);
                sources.promote("api", copy, copy.revision());
                assertThrows(IllegalArgumentException.class, () -> resources.resolve(part(List.of("main"), List.of())));
                assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("databases", "{\"selections\":[]}"), part, candidate, "c".repeat(64)));
                resources.run(candidate.workspace().applicationId(), capability("database_inspect", "{\"scope\":\"SOURCE\"}"), part, candidate, "c".repeat(64));
                assertTrue(resources.run(candidate.workspace().applicationId(), capability("databases", "{\"selections\":[]}"), part, candidate, "c".repeat(64)).contains("no database effect replayed"));
                assertEquals(1, resources.resolve(part(List.of("main"), List.of())).resources().databaseBindings().orElseThrow().size());
                var sql = new DeploymentAgentPatch("schema.sql", hash("CREATE TABLE example(id INTEGER);\n"), DeploymentAgentSourceRevision.digest(sources.material("api").snapshot().directory()), List.of(new DeploymentAgentPatch.Edit(1, List.of("CREATE TABLE example(id INTEGER);"), List.of("CREATE TABLE revised(id INTEGER);"))));
                var revised = sources.predict("api", sql);
                sources.promote("api", revised, revised.revision());
                resources.run(candidate.workspace().applicationId(), capability("database_inspect", "{\"scope\":\"SOURCE\"}"), part, candidate, "d".repeat(64));
                assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("databases", "{\"selections\":[]}"), part, candidate, "d".repeat(64)));
                assertEquals(1, approvals.stream().filter(v -> v.semantics().orElseThrow().operation().equals("db.sqlite-initialization")).count());
                assertTrue(approvals.stream().anyMatch(v -> v.semantics().orElseThrow().operation().equals("db.changed-source")));
            }
        });
    }

    @Test
    void truncatedCommandAndSealCannotEstablishSuccessAndUnknownVersionsRemainBlocked() throws Exception {
        run("index.html", "<html/>\n", (context, task, server, material, journal) -> {
            var peer = peer(request -> request.script().contains("'agent-open'")
                ? reply("SOURCE_REVISION=" + "a".repeat(64) + "\n")
                : new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), "DIGEST=" + "b".repeat(64) + "\n", "", true)), Optional.empty()));
            var channel = new DeploymentAgentProjectChannel(peer, context.interaction(), server, task, "a".repeat(64), () -> "a".repeat(64));
            var workspace = new DeploymentRemoteWorkspace("demo-api", "b".repeat(64));
            var candidate = new DeploymentAgentDeliveryPort.Prepared(workspace, channel.open(workspace, "ordinary"));
            var result = channel.command(candidate, candidate.revision(), "printf result", java.time.Duration.ofSeconds(60));
            assertFalse(result.known());
            assertFalse(result.succeeded());
            assertThrows(Exception.class, () -> channel.seal(candidate, candidate.revision()));
            assertEquals(4, journal.find(task).orElseThrow().executionSemantics());
            try (var connection = context.db().openConnection(task); var statement = connection.prepareStatement("UPDATE deployment_agent_task SET execution_semantics_version=99 WHERE task_id=?")) {
                statement.setString(1, task.id().toString());
                assertEquals(1, statement.executeUpdate());
            }
            assertThrows(Exception.class, () -> journal.find(task));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"POSTGRESQL", "MYSQL", "MARIADB", "REDIS"})
    void observesExistingDatabaseFamiliesOnTheOriginalChannelAndRejectsInventedTargets(String engine) throws Exception {
        run("windowstolinux-db.properties", "db=main\ndb.main.engine=" + engine + "\ndb.main.database=application\ndb.main.username=application\ndb.main.environmentPrefix=APP\ndb.main.passwordEnvironment=APP_PASSWORD\n", (context, task, server, material, journal) -> {
            var reads = new AtomicInteger();
            var peer = peer(request -> {
                if (request.script().contains(" probe"))
                    return reply("HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n");
                reads.incrementAndGet();
                return reply(frame(Map.of("status", "OK", "engine", engine, "instanceCount", "0", "conflictCount", "0")));
            });
            try (var sources = new DeploymentAgentSources(context, task, material)) {
                var resources = new DeploymentAgentResourceTools(context, task, server, peer, sources, journal);
                var candidate = new DeploymentAgentDeliveryPort.Prepared(new DeploymentRemoteWorkspace("demo-api", "b".repeat(64)), "c".repeat(64));
                String sourceOnly = resources.run(candidate.workspace().applicationId(), capability("database_inspect", "{\"scope\":\"SOURCE\"}"), part(List.of(), List.of()), null, "a".repeat(64));
                assertTrue(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentFeedback.safe("TOOL_database_inspect", sourceOnly).contains("NOT_OBSERVED"));
                assertEquals(0, reads.get());
                assertThrows(IllegalArgumentException.class, () -> resources.validatePreparation(part(List.of(), List.of())));
                String observed = resources.run(candidate.workspace().applicationId(), capability("database_inspect", "{\"scope\":\"REMOTE\"}"), part(List.of(), List.of()), candidate, "a".repeat(64));
                assertTrue(gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentFeedback.safe("TOOL_database_inspect", observed).contains(engine));
                assertEquals(1, reads.get());
                resources.validatePreparation(part(List.of(), List.of()));
                assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("databases", "{\"selections\":[{\"databaseId\":\"main\",\"instanceId\":\"invented\"}]}"), part(List.of(), List.of()), candidate, "a".repeat(64)));
                assertEquals(1, reads.get(), "Invented target cannot trigger DB effects");
                resources.run(candidate.workspace().applicationId(), capability("database_inspect", "{\"scope\":\"SOURCE\"}"), part(List.of(), List.of()), null, "b".repeat(64));
                assertThrows(IllegalArgumentException.class, () -> resources.validatePreparation(part(List.of(), List.of())), "SOURCE cannot relabel earlier remote instances as current observed inventory");
                assertEquals(1, reads.get());
            }
        });
    }

    private void analyzeAndBuild(DeploymentAgentBuildTools tools, DeploymentAgentPlan.Part part, DeploymentAgentDeliveryPort.Prepared candidate, String revision, int expectedExit) throws Exception {
        String analysis = tools.run(capability("analyze", "{\"type\":\"STATIC_SITE\"}"), part, null, revision);
        assertTrue(analysis.contains("STATIC_SITE"), analysis);
        var recipe = capability("prepare_tools", "{\"recipe\":{\"version\":\"\",\"primary\":\"public\",\"secondary\":\"\",\"jvmTarget\":\"\",\"servicePort\":0}}");
        tools.validateCapability(recipe, part);
        tools.run(recipe, part, candidate, revision);
        assertTrue(tools.run(capability("build", "{\"seconds\":60}"), part, candidate, revision).contains("exit=" + expectedExit));
    }

    private DeploymentAgentPlan.Part part(List<String> databases, List<DeploymentSecretRef> secrets) throws Exception {
        return part(databases, secrets, ".");
    }

    private DeploymentAgentPlan.Part part(List<String> databases, List<DeploymentSecretRef> secrets, String sourceRoot) throws Exception {
        return part(databases, secrets, sourceRoot, "run.sh");
    }

    private DeploymentAgentPlan.Part part(List<String> databases, List<DeploymentSecretRef> secrets, String sourceRoot, String entrypoint) throws Exception {
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("""
            {"applicationId":"demo","components":[{"id":"api","sourceRoot":".","dependencies":[],"evidence":["o1"],
            "backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"",
            "health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10},"ports":[],"resources":[],"configuration":[],"databases":[],"secretReferences":[]}],
            "applicationHealth":{"component":"api","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10}}}
            """);
        var c = (com.fasterxml.jackson.databind.node.ObjectNode) node.path("components").get(0);
        c.put("sourceRoot", sourceRoot);
        c.put("entrypoint", entrypoint);
        c.set("databases", json.valueToTree(databases));
        c.set("secretReferences", json.valueToTree(secrets));
        return DeploymentAgentPlanProtocol.parse(node).components().getFirst();
    }

    private DeploymentAgentProposal.Capability capability(String name, String arguments) throws Exception {
        return new DeploymentAgentProposal.Capability(name, "api", json.readTree(arguments));
    }

    private void run(String name, String content, Scenario action) throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        Files.writeString(source.resolve(name), content);
        Files.writeString(source.resolve("notes.txt"), "old\n");
        if (name.equals("index.html"))
            Files.writeString(Files.createDirectories(source.resolve("public")).resolve("style.css"), "body {color:black}");
        if (content.contains("initialize="))
            Files.writeString(source.resolve("schema.sql"), "CREATE TABLE example(id INTEGER);\n");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("capability-server");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture")), "fixture".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = context.tasks().submit(new TaskRequest("agent-capability-local-test", "Local capability scenario", List.of(server.id()), true), task -> {
                try {
                    var journal = new DeploymentAgentActionLog(context.db());
                    journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), "a".repeat(64), "MANUAL_REVIEW", true));
                    var selection = context.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(context.file()).prepare(selection, 1048576, 100, task)) {
                        action.run(context, task, server, material, journal);
                    }
                    journal.state(task, "FAILED");
                    context.file().closeTaskResources(task);
                    assertTrue(context.tasks().resourcesReleased(task));
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    throw error;
                } finally {
                    if (failure.get() != null) {
                        try {
                            new DeploymentAgentActionLog(context.db()).state(task, "FAILED");
                            context.file().closeTaskResources(task);
                        } catch (Exception cleanup) {
                            failure.get().addSuppressed(cleanup);
                        }
                    }
                    finished.complete(null);
                }
            });
            finished.get(30, TimeUnit.SECONDS);
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(30, TimeUnit.SECONDS).outcome(), () -> String.valueOf(failure.get()));
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void run(gold.debug.windowstolinux.appapi.mod.ModContext context, TaskContext task, ServerId server, DeploymentSourceMaterial material, DeploymentAgentActionLog journal) throws Exception;
    }

    @FunctionalInterface
    private interface Reply {
        OperationResult<CommandResult> run(CommandRequest request) throws Exception;
    }

    private static RemoteSession peer(Reply reply) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
            if (method.getName().equals("execute")) return reply.run((CommandRequest) args[0]);
            if (method.getName().equals("close")) return null;
            throw new AssertionError("No SSH or other session operations");
        });
    }

    private static OperationResult<CommandResult> reply(String output) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), output, "", false)), Optional.empty());
    }

    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static String frame(Map<String, String> values) {
        return values.entrySet().stream().map(v -> v.getKey() + "=" + Base64.getEncoder().encodeToString(v.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\n").collect(java.util.stream.Collectors.joining());
    }
}
