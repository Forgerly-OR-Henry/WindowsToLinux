package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.db.server.StoredServerProfile;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.*;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.mod.deployment.DeploymentRuntimeFixture;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentFeedback;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection;
import org.junit.jupiter.api.Test;

/** Actual frozen source and resource preparation with a rejecting local peer; no API or SSH. */
class DeploymentAgentDatabaseSourceTest extends DeploymentRuntimeFixture {
    private final ObjectMapper json = new ObjectMapper();

    private final AtomicInteger textRequests = new AtomicInteger();

    private final AtomicInteger remoteSends = new AtomicInteger();

    private final AtomicInteger businessApprovals = new AtomicInteger();

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken cancellation) {
                businessApprovals.incrementAndGet();
                return true;
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task, CancelToken cancellation) {
                textRequests.incrementAndGet();
                throw new AssertionError("The model must resolve source facts; no fixture supplies text answers");
            }

            public TransferConflictChoice transferConflict(TransferConflict conflict, ServerId server, TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("No transfer");
            }

            public TransferStallChoice transferStalled(TransferStall stall, ServerId server, TaskSnapshot task, CancelToken cancellation) {
                throw new AssertionError("No transfer");
            }

            public String transferProgress(TransferProgress progress) {
                throw new AssertionError("No transfer");
            }
        };
    }

    @Test
    void rootDeclarationNeedsExplicitSelectionAndPreservesActualRelativeCustomSqlitePath() throws Exception {
        run(Map.of("windowstolinux-db.properties", "db=snapshots\ndb.snapshots.engine=SQLITE\ndb.snapshots.path=data/snapshots.db\n",
            "cli/store.py", "import sqlite3\nconnection = sqlite3.connect('data/snapshots.db')\n"), (context, task, server, material, journal, sources, resources) -> {
            var part = part("cli", "cli", null);
            var candidate = candidate("cli", material);
            var initial = json.readTree(resources.run(candidate.workspace().applicationId(), capability("database_inspect", "cli", "{\"scope\":\"SOURCE\"}"), part, candidate, "a".repeat(64)));
            assertEquals("NEEDS_SOURCE_SELECTION", initial.path("status").asText());
            assertTrue(initial.path("databases").isEmpty(), "Unknown resource state cannot become none or a ready inventory");
            assertEquals("snapshots", initial.path("candidateManifests").get(0).path("databaseIds").get(0).asText());
            assertTrue(DeploymentAgentFeedback.safe("TOOL_database_inspect", initial.toString()).contains("NEEDS_SOURCE_SELECTION"));
            assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("databases", "cli", "{\"selections\":[]}"), part, candidate, "b".repeat(64)));
            var selected = json.readTree(resources.run(candidate.workspace().applicationId(), selection("cli", ".", "snapshots"), part, candidate, "c".repeat(64)));
            assertEquals("SOURCE_INSPECTED", selected.path("status").asText());
            assertEquals(material.snapshot().sha256(), selected.path("sourceRevision").asText());
            assertEquals("snapshots", selected.path("databases").get(0).path("databaseId").asText());
            assertEquals("data/snapshots.db", selected.path("databases").get(0).path("sqlitePath").asText());
            assertTrue(DeploymentAgentFeedback.safe("TOOL_database_select_source", selected.toString()).contains("data/snapshots.db"));
            assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), selection("cli", ".", "missing"), part, candidate, "2".repeat(64)));
            assertEquals("SOURCE_INSPECTED", json.readTree(resources.run(candidate.workspace().applicationId(), capability("database_inspect", "cli", "{\"scope\":\"SOURCE\"}"), part, candidate, "d".repeat(64))).path("status").asText(), "An unchanged explicit selection survives a fresh inspection");
            resources.run(candidate.workspace().applicationId(), capability("databases", "cli", "{\"selections\":[]}"), part, candidate, "e".repeat(64));
            var resolved = resources.resolve(part("cli", "cli", List.of("snapshots")));
            assertEquals("cli", resolved.sourceRoot(), "Resource selection never rewrites the build root");
            var binding = resolved.resources().databaseBindings().orElseThrow().getFirst();
            var sqlite = (DeploymentDatabaseConnection.Sqlite) binding.connection();
            assertEquals("data/snapshots.db", sqlite.accessPath());
            assertEquals("/opt/windowstolinux/apps/demo-cli/persistent/databases/snapshots/snapshots.db", sqlite.physicalPath(candidate.workspace().applicationId(), binding.databaseId()));
            var repeated = selection("cli", ".", "snapshots");
            var arguments = (ObjectNode) repeated.arguments().deepCopy();
            ((ObjectNode) arguments.path("sourceSelection")).putArray("declarationEvidence").add("o3");
            ((ObjectNode) arguments.path("sourceSelection")).putArray("consumptionEvidence").add("o4");
            resources.run(candidate.workspace().applicationId(), new DeploymentAgentProposal.Capability(repeated.name(), repeated.component(), arguments), part, candidate, "f".repeat(64));
            assertTrue(resources.run(candidate.workspace().applicationId(), capability("databases", "cli", "{\"selections\":[]}"), part, candidate, "1".repeat(64)).contains("no database effect replayed"), "New original READ IDs do not change database effect inputs");
            assertEquals(0, businessApprovals.get(), "Complete declarations without schema/endpoint review need no manufactured business consent");
            assertEquals(0, textRequests.get());
            assertEquals(0, remoteSends.get());
        });
    }

    @Test
    void selectedSubsetNormalizesOnlySourceFilesAndDuplicateComponentOwnershipCannotPrepare() throws Exception {
        run(Map.of("resources/windowstolinux-db.properties", "db=snapshots,unused\ndb.snapshots.engine=SQLITE\ndb.snapshots.path=data/snapshots.db\ndb.snapshots.initialize=schema.sql\ndb.snapshots.seed=seed.db\ndb.unused.engine=SQLITE\ndb.unused.path=unused.db\n",
            "resources/schema.sql", "CREATE TABLE snapshots(id INTEGER PRIMARY KEY);\n", "resources/seed.db", "",
            "cli/store.py", "import sqlite3\n", "native/main.cpp", "int main() {return 0;}\n"), (context, task, server, material, journal, sources, resources) -> {
            var cli = part("cli", "cli", null);
            var candidate = candidate("cli", material);
            var selected = json.readTree(resources.run(candidate.workspace().applicationId(), selection("cli", "resources", "snapshots"), cli, candidate, "a".repeat(64)));
            assertEquals(1, selected.path("databases").size());
            assertEquals("snapshots", selected.path("databases").get(0).path("databaseId").asText());
            assertEquals("resources/schema.sql", selected.path("databases").get(0).path("initializationFiles").get(0).asText());
            assertEquals("resources/seed.db", selected.path("databases").get(0).path("sqliteSeedFile").asText());
            resources.run(candidate.workspace().applicationId(), capability("databases", "cli", "{\"selections\":[]}"), cli, candidate, "c".repeat(64));
            var sqlite = (DeploymentDatabaseConnection.Sqlite) resources.resolve(part("cli", "cli", List.of("snapshots"))).resources().databaseBindings().orElseThrow().getFirst().connection();
            assertEquals(List.of("resources/schema.sql"), sqlite.initializationFiles());
            assertEquals("resources/seed.db", sqlite.seedFile());
            assertEquals("data/snapshots.db", sqlite.accessPath());
            assertEquals(1, businessApprovals.get(), "Source selection does not supply required schema-effect consent");
            var worker = part("worker", "native", null);
            resources.run(candidate.workspace().applicationId(), selection("worker", "resources", "snapshots"), worker, candidate("worker", material), "b".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("databases", "worker", "{\"selections\":[]}"), worker, candidate("worker", material), "d".repeat(64)));
            assertEquals(1, businessApprovals.get(), "A duplicate owner receives no extra schema consent or resource preparation");
            assertThrows(IllegalArgumentException.class, () -> resources.resolve(part("worker", "native", List.of("snapshots"))));
            assertEquals(0, textRequests.get());
            assertEquals(0, remoteSends.get());
        });
    }

    @Test
    void incompleteSourceFactsNeverRequestTextOrCreateReadyInventoryAndChangedSelectionCannotReuseBindings() throws Exception {
        run(Map.of("cli/windowstolinux-db.properties", "db=snapshots\ndb.snapshots.engine=SQLITE\ndb.snapshots.path=${UNRESOLVED}\n",
            "windowstolinux-db.properties", "db=snapshots\ndb.snapshots.engine=SQLITE\ndb.snapshots.path=data/snapshots.db\n",
            "other/windowstolinux-db.properties", "db=snapshots\ndb.snapshots.engine=SQLITE\ndb.snapshots.path=other/snapshots.db\n",
            "cli/store.py", "import sqlite3\n"), (context, task, server, material, journal, sources, resources) -> {
            var part = part("cli", "cli", null);
            var candidate = candidate("cli", material);
            var incomplete = json.readTree(resources.run(candidate.workspace().applicationId(), capability("database_inspect", "cli", "{\"scope\":\"SOURCE\"}"), part, candidate, "a".repeat(64)));
            assertEquals("NEEDS_DECLARATION", incomplete.path("status").asText());
            assertFalse(incomplete.path("questions").isEmpty());
            assertTrue(incomplete.path("databases").isEmpty());
            assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("databases", "cli", "{\"selections\":[]}"), part, candidate, "b".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), selection("cli", ".", "missing"), part, candidate, "c".repeat(64)));
            resources.run(candidate.workspace().applicationId(), selection("cli", ".", "snapshots"), part, candidate, "d".repeat(64));
            resources.run(candidate.workspace().applicationId(), capability("databases", "cli", "{\"selections\":[]}"), part, candidate, "e".repeat(64));
            resources.run(candidate.workspace().applicationId(), selection("cli", "other", "snapshots"), part, candidate, "f".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> resources.resolve(part("cli", "cli", List.of("snapshots"))));
            assertThrows(IllegalArgumentException.class, () -> resources.run(candidate.workspace().applicationId(), capability("databases", "cli", "{\"selections\":[]}"), part, candidate, "1".repeat(64)));
            assertEquals(0, textRequests.get());
            assertEquals(0, remoteSends.get());
        });
    }

    @Test
    void emptySourceDeclarationsRemainSourceFactsWithoutCandidateOrRemoteInventory() throws Exception {
        run(Map.of("cli/Cargo.toml", "[package]\nname='binary-inspector'\nversion='0.1.0'\n",
            "cli/src/main.rs", "fn main() { println!(\"inspect files\"); }\n"), (context, task, server, material, journal, sources, resources) -> {
            var part = part("cli", "cli", null);
            var observed = json.readTree(resources.run("demo-cli", capability("database_inspect", "cli", "{\"scope\":\"SOURCE\"}"), part, null, "a".repeat(64)));
            assertEquals("SOURCE", observed.path("scope").asText());
            assertEquals("SOURCE_INSPECTED", observed.path("status").asText());
            assertEquals("NOT_OBSERVED", observed.path("serverInventory").asText());
            assertEquals("EXPLICIT_MANIFEST_AND_SUPPORTED_CONVENTIONS_ONLY", observed.path("sourceCoverage").asText());
            assertEquals(material.snapshot().sha256(), observed.path("sourceRevision").asText());
            assertTrue(observed.path("databases").isEmpty());
            assertFalse(observed.has("businessDatabaseAbsent"));
            assertTrue(DeploymentAgentFeedback.safe("TOOL_database_inspect", observed.toString()).contains("NOT_OBSERVED"));
            assertThrows(IllegalArgumentException.class, () -> resources.validatePreparation(part));
            assertTrue(resources.resolve(part("cli", "cli", List.of())).resources().databaseBindings().orElseThrow().isEmpty(),
                "The model may independently declare no database from sufficient READ facts, without database preparation");
            var patch = new DeploymentAgentPatch("cli/Cargo.toml", sources.browser("cli").contentSha256("cli/Cargo.toml"),
                DeploymentAgentSourceRevision.digest(material.snapshot().directory()),
                List.of(new DeploymentAgentPatch.Edit(3, List.of("version='0.1.0'"), List.of("version='0.2.0'"))));
            var derivative = sources.predict("cli", patch);
            sources.promote("cli", derivative, derivative.revision());
            var current = json.readTree(resources.run("demo-cli", capability("database_inspect", "cli", "{\"scope\":\"SOURCE\"}"), part, null, "b".repeat(64)));
            assertEquals(sources.material("cli").snapshot().sha256(), current.path("sourceRevision").asText());
            assertNotEquals(observed.path("sourceRevision").asText(), current.path("sourceRevision").asText());
            assertEquals("NOT_OBSERVED", current.path("serverInventory").asText());
            assertEquals(0, remoteSends.get());
            assertEquals(0, businessApprovals.get());
            assertEquals(0, textRequests.get());
        });
    }

    @Test
    void sourceNativeDeclarationsAndSelectionCannotAuthorizeNativePreparation() throws Exception {
        run(Map.of("windowstolinux-db.properties", "db=main\ndb.main.engine=POSTGRESQL\ndb.main.database=application\ndb.main.username=application\ndb.main.environmentPrefix=APP\ndb.main.passwordEnvironment=APP_PASSWORD\n",
            "cli/store.py", "endpoint = 'postgresql://localhost/application'\n"), (context, task, server, material, journal, sources, resources) -> {
            var part = part("cli", "cli", null);
            var selected = json.readTree(resources.run("demo-cli", selection("cli", ".", "main"), part, null, "a".repeat(64)));
            assertEquals("SOURCE_INSPECTED", selected.path("status").asText());
            assertEquals("NOT_OBSERVED", selected.path("serverInventory").asText());
            assertEquals("POSTGRESQL", selected.path("databases").get(0).path("engine").asText());
            assertFalse(selected.path("databases").get(0).has("instances"));
            assertFalse(selected.path("databases").get(0).has("instanceIds"));
            assertTrue(DeploymentAgentFeedback.safe("TOOL_database_select_source", selected.toString()).contains("POSTGRESQL"));
            var inspected = json.readTree(resources.run("demo-cli", capability("database_inspect", "cli", "{\"scope\":\"SOURCE\"}"), part, null, "b".repeat(64)));
            assertEquals(".", inspected.path("declarationRoot").asText());
            assertThrows(IllegalArgumentException.class, () -> resources.validatePreparation(part));
            assertThrows(IllegalArgumentException.class, () -> resources.run("demo-cli", capability("databases", "cli", "{\"selections\":[{\"databaseId\":\"main\",\"instanceId\":\"\"}]}"),
                part, candidate("cli", material), "c".repeat(64)));
            assertEquals(0, remoteSends.get());
            assertEquals(0, businessApprovals.get());
            assertEquals(0, textRequests.get());
        });
    }

    @Test
    void malformedSourceDeclarationAndMissingScopeFailWithoutRemotePreparation() throws Exception {
        run(Map.of("cli/windowstolinux-db.properties", "db=main\ndb.main.engine=INVALID\n"), (context, task, server, material, journal, sources, resources) -> {
            var part = part("cli", "cli", null);
            assertThrows(IllegalArgumentException.class, () -> resources.run("demo-cli", capability("database_inspect", "cli", "{}"), part, null, "a".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> resources.run("demo-cli", capability("database_inspect", "cli", "{\"scope\":\"SOURCE\"}"), part, null, "b".repeat(64)));
            assertThrows(IllegalArgumentException.class, () -> resources.validatePreparation(part));
            assertEquals(0, remoteSends.get());
            assertEquals(0, businessApprovals.get());
        });
    }

    @Test
    void sourceEndpointAndSchemaObligationsRemainVisibleWithoutBusinessApproval() throws Exception {
        run(Map.of("cli/application.properties", "spring.datasource.url=jdbc:postgresql://db.example.invalid/application\nspring.datasource.username=app\nspring.sql.init.mode=always\n",
            "cli/schema.sql", "CREATE TABLE sample (id INTEGER);\n"), (context, task, server, material, journal, sources, resources) -> {
            var observed = json.readTree(resources.run("demo-cli", capability("database_inspect", "cli", "{\"scope\":\"SOURCE\"}"), part("cli", "cli", null), null, "a".repeat(64)));
            assertEquals("SOURCE_INSPECTED", observed.path("status").asText());
            assertTrue(observed.path("endpointConfirmationRequired").asBoolean());
            assertTrue(observed.path("schemaReviewRequired").asBoolean());
            assertEquals("NOT_OBSERVED", observed.path("serverInventory").asText());
            assertEquals(0, businessApprovals.get());
            assertEquals(0, textRequests.get());
            assertEquals(0, remoteSends.get());
        });
    }

    private DeploymentAgentProposal.Capability selection(String component, String directory, String id) throws Exception {
        var args = (ObjectNode) json.readTree("""
            {"component":"%s","sourceSelection":{"declarationRoot":"%s","databaseIds":["%s"],"declarationEvidence":["o1"],"consumptionEvidence":["o2"]},
            "assessment":{"basis":["o1","o2"],"suitability":"Actual frozen declaration and component consumer","differences":"Separate build and declaration roots","resolution":"RESOLVED","decision":"Explicit source assignment only"}}
            """.formatted(component, directory, id));
        return (DeploymentAgentProposal.Capability) ((DeploymentAgentProposal.Assessed) DeploymentAgentProtocol.parse("database_select_source", args)).action();
    }

    private DeploymentAgentProposal.Capability capability(String name, String component, String arguments) throws Exception {
        return new DeploymentAgentProposal.Capability(name, component, json.readTree(arguments));
    }

    private DeploymentAgentPlan.Part part(String component, String sourceRoot, List<String> databases) throws Exception {
        var plan = (ObjectNode) json.readTree("""
            {"applicationId":"demo","components":[{"id":"%s","sourceRoot":"%s","dependencies":[],"evidence":["o2"],"backend":"PROCESS","mode":"ON_DEMAND",
            "entrypoint":"run.sh","arguments":[],"workingDirectory":"","health":{"type":"COMMAND","entrypoint":"run.sh","arguments":[],"expected":"usage:","timeout":10},
            "ports":[],"resources":[],"configuration":[],"databases":null,"secretReferences":[]}],
            "applicationHealth":{"component":"%s","health":{"type":"COMMAND","entrypoint":"run.sh","arguments":[],"expected":"usage:","timeout":10}}}
            """.formatted(component, sourceRoot, component));
        ((ObjectNode) plan.path("components").get(0)).set("databases", databases == null ? json.nullNode() : json.valueToTree(databases));
        return DeploymentAgentPlanProtocol.parse(plan).components().getFirst();
    }

    private static DeploymentAgentDeliveryPort.Prepared candidate(String component, DeploymentSourceMaterial material) {
        return new DeploymentAgentDeliveryPort.Prepared(new DeploymentRemoteWorkspace("demo-" + component, "b".repeat(64)), material.snapshot().sha256());
    }

    private void run(Map<String, String> files, Scenario scenario) throws Exception {
        Path source = Files.createDirectories(root.resolve("source"));
        for (var file : files.entrySet()) {
            Path path = source.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            var server = new ServerId("database-source-only");
            runtime.servers().saveServerProfile(ServerProfile.fromStored(new StoredServerProfile(server.id(), "127.0.0.1", 22, "root", "fixture")), "fixture".toCharArray());
            var context = runtime.modContexts().get(OWNER).context();
            var failure = new AtomicReference<Throwable>();
            var finished = new CompletableFuture<Void>();
            var handle = context.tasks().submit(new TaskRequest("agent-database-source-local", "Explicit database source ownership", List.of(server.id()), true), task -> {
                try {
                    var journal = new DeploymentAgentActionLog(context.db());
                    journal.create(task, new DeploymentAgentActionLog.Identity(server.id(), "b".repeat(64), "a".repeat(64), "MANUAL_REVIEW", true));
                    var selection = context.file().sources().selectDirectory(source, task);
                    try (var material = new DeploymentSourceSetup(context.file()).prepare(selection, 1048576, 100, task);
                         var sources = new DeploymentAgentSources(context, task, material)) {
                        var peer = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                            if (method.getName().equals("close")) return null;
                            remoteSends.incrementAndGet();
                            throw new AssertionError("SQLite source/preparation must not issue remote operations");
                        });
                        scenario.run(context, task, server, material, journal, sources, new DeploymentAgentResourceTools(context, task, server, peer, sources, journal));
                    }
                    journal.state(task, "FAILED");
                    context.file().closeTaskResources(task);
                    assertTrue(context.tasks().resourcesReleased(task));
                    return new OperationResult<Void>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
                } catch (Exception | AssertionError error) {
                    failure.set(error);
                    try {
                        new DeploymentAgentActionLog(context.db()).state(task, "FAILED");
                        context.file().closeTaskResources(task);
                    } catch (Exception cleanup) {
                        error.addSuppressed(cleanup);
                    }
                    throw error;
                } finally {
                    finished.complete(null);
                }
            });
            finished.get(30, TimeUnit.SECONDS);
            if (failure.get() != null)
                throw new AssertionError("Actual local database source scenario failed", failure.get());
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
        }
    }

    @FunctionalInterface
    private interface Scenario {
        void run(ModContext context, TaskContext task, ServerId server, DeploymentSourceMaterial material,
                 DeploymentAgentActionLog journal, DeploymentAgentSources sources, DeploymentAgentResourceTools resources) throws Exception;
    }
}
