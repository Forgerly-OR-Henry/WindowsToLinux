package gold.debug.windowstolinux.mod.deployment.linux;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferRequest;
import gold.debug.windowstolinux.appapi.linux.transfer.TransferResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.CancelToken;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceArchive;
import gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceMaterial;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentPublishChannel;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentBuildLimits;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseBinding;
import gold.debug.windowstolinux.mod.deployment.service.core.model.database.DeploymentDatabaseConnection;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.DeploymentStorageLocation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.DeploymentStandardPlanner;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentPlanAction;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.DeploymentReviewConsent;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises actual request construction with public controlled transport, not remote execution or host authority. */
class DeploymentRuntimePathsTest {
    private static final ServerId SERVER = new ServerId("Server-Exact-520");

    private static final String REVISION = "e".repeat(64);

    private static final String ARCHIVE_SHA = "a".repeat(64);

    private static final UUID TASK = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private static final DeploymentBuildLimits LIMITS = new DeploymentBuildLimits(120, 32, 512, 65536,
        64L * 1024 * 1024, false);

    private static final DeploymentRemoteLayout LAYOUT = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/srv/wtl/helper", "/srv/wtl/apps", "/srv/wtl/state", "/srv/wtl/config", "/srv/wtl/data"));

    @TempDir
    Path local;

    @Test
    void workspaceAndStorageUseCapturedRootsWithoutChangingCandidateIdentity() {
        var original = new DeploymentRemoteWorkspace("demo", ARCHIVE_SHA);
        var captured = new DeploymentRemoteWorkspace("demo", ARCHIVE_SHA, LAYOUT);
        assertEquals(original.candidateId(), captured.candidateId());
        assertEquals("/srv/wtl/state/work/demo-" + "a".repeat(16), captured.candidateRoot());
        UUID operation = UUID.fromString("22222222-2222-4222-8222-222222222222");
        assertEquals(new DeploymentRemoteWorkspace("demo", ARCHIVE_SHA, Optional.of(operation)).candidateId(),
            new DeploymentRemoteWorkspace("demo", ARCHIVE_SHA, Optional.of(operation), LAYOUT).candidateId());
        var defaults = DeploymentStorageLocation.defaults();
        assertEquals("/srv/wtl/config/demo/files/settings", defaults.resolve("demo",
            DeploymentStorageLocation.StorageResourceType.CONFIGURATION, "settings", LAYOUT));
        assertEquals("/srv/wtl/data/demo/files/uploads", defaults.resolve("demo",
            DeploymentStorageLocation.StorageResourceType.FILE, "uploads", LAYOUT));
        assertEquals("/srv/wtl/data/demo/databases/orders", defaults.resolve("demo",
            DeploymentStorageLocation.StorageResourceType.DATABASE, "orders", LAYOUT));
        assertEquals("/srv/wtl/apps/demo/assets", DeploymentStorageLocation.custom("/srv/wtl/apps/demo/assets")
            .resolve("demo", DeploymentStorageLocation.StorageResourceType.FILE, "assets", LAYOUT));
        assertThrows(IllegalArgumentException.class, () -> DeploymentStorageLocation.custom("/opt/windowstolinux/apps/demo/assets")
            .resolve("demo", DeploymentStorageLocation.StorageResourceType.FILE, "assets", LAYOUT));
    }

    @Test
    void buildCommandsTargetsInputAndIntegrityGuardShareOneLayoutAfterRebound() throws Exception {
        var peer = new ControlledPeer(request -> "");
        var guard = new AtomicInteger();
        var channel = new DeploymentBuildCommandChannel(peer.session, peer, SERVER, peer.task, REVISION,
            new DeploymentBuildBudget(3600), guard::incrementAndGet, LAYOUT);
        assertEquals(TaskResult.SUCCEEDED, channel.prepare(DeploymentToolchainType.GO, "1.24", "1.24.9", 60, "-").outcome());
        assertEquals(TaskResult.SUCCEEDED, channel.bind("e30=").outcome());
        assertEquals(TaskResult.SUCCEEDED, channel.preflight("ordinary").outcome());
        var workspace = new DeploymentRemoteWorkspace("demo", ARCHIVE_SHA, LAYOUT);
        byte[] input = "printf 'fixed build\\n'\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(TaskResult.SUCCEEDED, channel.build(workspace, LIMITS, 60, "ordinary",
            new String(input, StandardCharsets.UTF_8), "-", List.of(List.of("marker"))).outcome());
        assertEquals(TaskResult.SUCCEEDED, channel.rebound("f".repeat(64)).preflight("ordinary").outcome());
        assertEquals(5, guard.get());
        assertTarget(peer.commands.get(0).intent(), "deployment-build", LAYOUT.toolchains());
        assertTarget(peer.commands.get(1).intent(), "deployment-build", LAYOUT.toolchains());
        assertTarget(peer.commands.get(2).intent(), "deployment-build", LAYOUT.helperPath());
        var built = peer.commands.get(3);
        assertTarget(built.intent(), "deployment-build", workspace.candidateRoot());
        assertArrayEquals(input, built.input());
        String body = verifiedBody(built.script());
        assertTrue(body.startsWith("exec /usr/bin/python3 -I -c "));
        assertTrue(body.contains("'/srv/wtl/helper/managed-helper' build-run 'demo' '" + workspace.candidateId() + "'"));
        assertEquals("f".repeat(64), peer.commands.get(4).intent().revision());
        peer.commands.forEach(command -> assertGuard(command.script()));
    }

    @Test
    void unchangedHelperDirectoryStillBindsTheChangedApplicationAndStateRoots() throws Exception {
        var mixed = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            DeploymentRemoteLayout.original().paths().helperDirectory(), LAYOUT.paths().applicationsRoot(),
            LAYOUT.paths().stateRoot(), LAYOUT.paths().configurationRoot(), LAYOUT.paths().dataRoot()));
        var peer = new ControlledPeer(request -> "");
        var channel = new DeploymentBuildCommandChannel(peer.session, peer, SERVER, peer.task, REVISION,
            new DeploymentBuildBudget(3600), mixed);
        assertEquals(TaskResult.SUCCEEDED, channel.preflight("ordinary").outcome());
        var command = peer.commands.getFirst();
        assertTarget(command.intent(), "deployment-build", DeploymentManagedHelperBundle.PATH);
        assertEquals("'" + DeploymentManagedHelperBundle.PATH + "' build-preflight 'ordinary'",
            DeploymentManagedHelperBundle.verifiedBody(command.script(), mixed));
        assertTrue(command.script().contains(DeploymentManagedHelperBundle.helperSha256(mixed)));
        assertNotEquals(DeploymentManagedHelperBundle.helperSha256(DeploymentRemoteLayout.original()),
            DeploymentManagedHelperBundle.helperSha256(mixed));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedBody(command.script()));
    }

    @Test
    void candidateCreationUploadAndVerificationUseTheSameNondefaultEndpoint() throws Exception {
        try (var archive = archive()) {
            var workspace = new DeploymentRemoteWorkspace("demo", archive.sha256(), LAYOUT);
            var peer = new ControlledPeer(request -> {
                String body = verifiedBody(request.script());
                if (body.contains("candidate-create-task")) return "CANDIDATE=" + workspace.candidateRoot() + "\n";
                if (body.contains("printf 'BYTES='")) return "CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\nBYTES="
                    + archive.byteCount() + "\nSHA256=" + archive.sha256() + "\nOWNER=0\n";
                return "CANDIDATE_STATE=owned\nBUILD_ACTIVE=no\n";
            });
            var guard = new AtomicInteger();
            var channel = new DeploymentCandidateChannel(peer.session, peer, SERVER, peer.task, REVISION,
                new DeploymentBuildBudget(3600), guard::incrementAndGet, LAYOUT);
            assertEquals(workspace, channel.create(workspace, LIMITS.maxWorkspaceBytes()).value().orElseThrow());
            assertEquals(TaskResult.SUCCEEDED, channel.upload(archive, workspace).outcome());
            assertEquals(workspace, channel.verify(workspace, archive).value().orElseThrow());
            assertEquals(4, guard.get());
            assertEquals(3, peer.commands.size());
            peer.commands.forEach(command -> {
                assertGuard(command.script());
                assertTarget(command.intent(), "deployment-candidate", workspace.candidateRoot());
            });
            var transfer = peer.uploads.getFirst();
            assertEquals(archive.path(), transfer.localPath());
            assertEquals("/srv/wtl/state/work/" + workspace.candidateId() + "/mutable/source.tar.gz", transfer.remoteAbsolutePath());
            assertEquals(archive.byteCount(), transfer.maxBytes());
            assertTarget(peer.intents.stream().filter(intent -> intent.type().equals("platform.sftp.upload")).findFirst().orElseThrow(),
                "deployment-candidate", transfer.remoteAbsolutePath());
        }
    }

    @Test
    void mismatchedWorkspaceOrUnitFailsBeforeReviewGuardOrTransport() throws Exception {
        var peer = new ControlledPeer(request -> {
            throw new AssertionError("No command may dispatch");
        });
        var guard = new AtomicInteger();
        var workspace = new DeploymentRemoteWorkspace("demo", ARCHIVE_SHA);
        var build = new DeploymentBuildCommandChannel(peer.session, peer, SERVER, peer.task, REVISION,
            new DeploymentBuildBudget(3600), guard::incrementAndGet, LAYOUT);
        assertThrows(IllegalArgumentException.class, () -> build.build(workspace, LIMITS, 60, "ordinary", "fixed", "-", List.of(List.of("marker"))));
        assertThrows(IllegalArgumentException.class, () -> build.send("deployment.build.preflight.v1", "x",
            "'" + DeploymentManagedHelperBundle.PATH + "' build-preflight 'ordinary'", new byte[0], Duration.ofSeconds(20), 4096));
        var candidates = new DeploymentCandidateChannel(peer.session, peer, SERVER, peer.task, REVISION,
            new DeploymentBuildBudget(3600), guard::incrementAndGet, LAYOUT);
        assertThrows(IllegalArgumentException.class, () -> candidates.create(workspace, LIMITS.maxWorkspaceBytes()));
        try (var archive = archive()) {
            assertThrows(IllegalArgumentException.class, () -> candidates.upload(archive, workspace));
            assertThrows(IllegalArgumentException.class, () -> candidates.verify(workspace, archive));
        }
        var publication = new DeploymentPublishChannel(peer.session, peer, SERVER, peer.task, REVISION,
            guard::incrementAndGet, LAYOUT);
        var changed = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths("/srv/wtl/other-helper",
            LAYOUT.paths().applicationsRoot(), LAYOUT.paths().stateRoot(), LAYOUT.paths().configurationRoot(), LAYOUT.paths().dataRoot()));
        var unit = DeploymentUnitIdentity.forManaged("demo", SERVER, "b".repeat(64), changed);
        assertEquals(LAYOUT.applicationRoot("demo"), unit.releaseRoot());
        assertThrows(IllegalArgumentException.class, () -> publication.stageConfiguration(unit, "c".repeat(64), "systemd", new byte[0]));
        assertEquals(0, guard.get());
        assertTrue(peer.intents.isEmpty());
        assertTrue(peer.commands.isEmpty());
        assertTrue(peer.uploads.isEmpty());
    }

    @Test
    void publicationUsesUnitLayoutForHelperGuardReleaseTargetAndSqliteDefault() throws Exception {
        var peer = new ControlledPeer(request -> verifiedBody(request.script()).contains("'stage-config'")
            ? "INPUT_STAGED=1\n" : "PUBLISHED=1\n");
        var unit = DeploymentUnitIdentity.forManaged("demo", SERVER, "b".repeat(64), LAYOUT);
        var channel = new DeploymentPublishChannel(peer.session, peer, SERVER, peer.task, REVISION, LAYOUT);
        byte[] configuration = "PORT=8080\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(TaskResult.SUCCEEDED, channel.stageConfiguration(unit, "c".repeat(64), "systemd", configuration).outcome());
        var resources = new DeploymentResourceBindings(List.of(), Optional.of(List.of(new DeploymentDatabaseBinding("orders",
            new DeploymentDatabaseConnection.Sqlite("orders.db")))));
        var facts = facts(local);
        assertEquals(TaskResult.SUCCEEDED, channel.publish(unit, facts, runtime(), resources, "c".repeat(64), Map.of(),
            "demo-" + "a".repeat(16), "d".repeat(64), "-", false).outcome());
        assertArrayEquals(configuration, peer.commands.getFirst().input());
        peer.commands.forEach(command -> assertGuard(command.script()));
        assertEquals(Set.of(new ActionTarget(SERVER.id(), "deployment-release", "/srv/wtl/apps/demo"),
                new ActionTarget(SERVER.id(), "deployment-configuration", LAYOUT.configurationRoot("demo") + "/" + "c".repeat(64) + "/systemd.env")),
            peer.commands.getFirst().intent().semantics().orElseThrow().targets());
        assertEquals(Set.of(new ActionTarget(SERVER.id(), "deployment-release", "/srv/wtl/apps/demo"),
                new ActionTarget(SERVER.id(), "deployment-candidate", LAYOUT.candidateRoot("demo-" + "a".repeat(16)))),
            peer.commands.get(1).intent().semantics().orElseThrow().targets());
        assertTrue(verifiedBody(peer.commands.get(1).script()).contains("'/srv/wtl/data/demo/databases/orders/orders.db'"));
        assertFalse(verifiedBody(peer.commands.get(1).script()).contains("/var/opt/windowstolinux"));
    }

    @Test
    void snapshotsDeclareCapturedReleaseAndExactRootOrAttemptSnapshotTargets() throws Exception {
        var peer = new ControlledPeer(request -> "PREVIOUS=0\n");
        var unit = DeploymentUnitIdentity.forManaged("demo", SERVER, "b".repeat(64), LAYOUT);
        var channel = new DeploymentPublishChannel(peer.session, peer, SERVER, peer.task, REVISION, LAYOUT);
        assertEquals(TaskResult.SUCCEEDED, channel.snapshot(unit, runtime()).outcome());
        var operation = java.util.UUID.randomUUID();
        assertEquals(TaskResult.SUCCEEDED, channel.snapshot(unit, runtime(), operation, Optional.empty()).outcome());
        for (int i = 0; i < 2; i++) {
            var token = i == 0 ? peer.task.id() : operation;
            assertEquals(Set.of(new ActionTarget(SERVER.id(), "deployment-release", unit.releaseRoot()),
                    new ActionTarget(SERVER.id(), "deployment-snapshot", LAYOUT.snapshotRoot("demo", token))),
                peer.commands.get(i).intent().semantics().orElseThrow().targets());
            assertGuard(peer.commands.get(i).script());
        }
    }

    @Test
    void retentionDeclaresBothReleaseAndContainerCredentialCleanupRoots() throws Exception {
        var peer = new ControlledPeer(request -> "RETAINED=1\n");
        var unit = DeploymentUnitIdentity.forManaged("demo", SERVER, "b".repeat(64), LAYOUT);
        var channel = new DeploymentPublishChannel(peer.session, peer, SERVER, peer.task, REVISION, LAYOUT);
        assertEquals(TaskResult.SUCCEEDED, channel.retain(unit).outcome());
        assertEquals(Set.of(new ActionTarget(SERVER.id(), "deployment-release", unit.releaseRoot()),
                new ActionTarget(SERVER.id(), "deployment-secret", LAYOUT.configurationRoot("demo") + "/.container-credentials")),
            peer.commands.getFirst().intent().semantics().orElseThrow().targets());
        assertGuard(peer.commands.getFirst().script());
    }

    @Test
    void maintenanceEndDeclaresActualApplicationLockDirectory() throws Exception {
        var peer = new ControlledPeer(request -> "MAINTENANCE=end\n");
        var unit = DeploymentUnitIdentity.forManaged("demo", SERVER, "b".repeat(64), LAYOUT);
        var channel = new DeploymentPublishChannel(peer.session, peer, SERVER, peer.task, REVISION, LAYOUT);
        assertEquals(TaskResult.SUCCEEDED, channel.endMaintenance(unit, "d".repeat(64)).outcome());
        assertEquals(Set.of(new ActionTarget(SERVER.id(), "deployment-release", unit.releaseRoot()),
                new ActionTarget(SERVER.id(), "deployment-application-locks", LAYOUT.applicationLockRoot("demo"))),
            peer.commands.getFirst().intent().semantics().orElseThrow().targets());
        assertGuard(peer.commands.getFirst().script());
    }

    @Test
    void secretStagingDeclaresTheExactCapturedSecretRevisionDestination() throws Exception {
        var peer = new ControlledPeer(request -> "INPUT_STAGED=1\n");
        var unit = DeploymentUnitIdentity.forManaged("demo", SERVER, "b".repeat(64), LAYOUT);
        var channel = new DeploymentPublishChannel(peer.session, peer, SERVER, peer.task, REVISION, LAYOUT);
        byte[] value = {1, 2, 3};
        assertEquals(TaskResult.SUCCEEDED, channel.stageSecret(unit, "db-secret", 2, value).outcome());
        assertEquals(Set.of(new ActionTarget(SERVER.id(), "deployment-release", unit.releaseRoot()),
                new ActionTarget(SERVER.id(), "deployment-secret", LAYOUT.configurationRoot("demo") + "/secrets/db-secret/2/value")),
            peer.commands.getFirst().intent().semantics().orElseThrow().targets());
        assertArrayEquals(new byte[]{1, 2, 3}, value);
        java.util.Arrays.fill(value, (byte) 0);
    }

    @Test
    void capacityProbesCapturedStateAncestorsWithoutCreatingDirectories() throws Exception {
        var peer = new ControlledPeer(request -> "1024 4096\n");
        var channel = new DeploymentCandidateChannel(peer.session, peer, SERVER, peer.task, REVISION,
            new DeploymentBuildBudget(3600), LAYOUT);
        assertEquals(4194304L, channel.availableBytes().value().orElseThrow());
        var command = peer.commands.getFirst();
        assertTarget(command.intent(), "deployment-workspace", "/srv/wtl/state");
        assertTrue(command.script().contains("for wtl_capacity_component in 'srv' 'wtl' 'state'; do"));
        assertTrue(command.script().contains("test ! -L \"$wtl_capacity_next\""));
        assertTrue(command.script().contains("test \"$(stat -c '%u:%g' -- \"$wtl_capacity_next\")\" = 0:0"));
        assertTrue(command.script().contains("stat -f -c '%a %S' -- \"$wtl_capacity_directory\""));
        assertFalse(command.script().contains("mkdir"));
        assertFalse(command.script().contains("'/var/lib'"));
    }

    @Test
    void legacyEntrypointsKeepLiteralOriginalPathsAndHelperGuard() throws Exception {
        assertEquals(DeploymentRemoteLayout.original(), new DeploymentRemoteWorkspace("demo", ARCHIVE_SHA).layout());
        assertEquals("/var/lib/windowstolinux/work/demo-" + "a".repeat(16), new DeploymentRemoteWorkspace("demo", ARCHIVE_SHA).candidateRoot());
        assertEquals("/opt/windowstolinux/apps/demo", DeploymentStorageLocation.installationRoot("demo"));
        assertEquals("/etc/opt/windowstolinux/apps/demo", DeploymentStorageLocation.configurationRoot("demo"));
        assertEquals("/var/opt/windowstolinux/apps/demo", DeploymentStorageLocation.dataRoot("demo"));
        var peer = new ControlledPeer(request -> request.script().contains("build-preflight") ? "" : "1024 4096\n");
        assertEquals(TaskResult.SUCCEEDED, new DeploymentBuildCommandChannel(peer.session, peer, SERVER, peer.task,
            REVISION, new DeploymentBuildBudget(3600)).preflight("ordinary").outcome());
        assertEquals("'" + DeploymentManagedHelperBundle.PATH + "' build-preflight 'ordinary'",
            DeploymentManagedHelperBundle.verifiedBody(peer.commands.getFirst().script()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedBody(peer.commands.getFirst().script(), LAYOUT));
        assertEquals(4194304L, new DeploymentCandidateChannel(peer.session, peer, SERVER, peer.task, REVISION,
            new DeploymentBuildBudget(3600)).availableBytes().value().orElseThrow());
        assertEquals("set -euo pipefail\ntest -d '/var/lib' && test ! -L '/var/lib'\nstat -f -c '%a %S' -- '/var/lib'\n",
            peer.commands.get(1).script());
    }

    @Test
    void standardRendererCarriesRequestLayoutAndRejectsAnUnboundOldEntry() throws Exception {
        try (var archive = archive()) {
            var material = new DeploymentSourceMaterial("source", snapshot(local), List.of(), List.of());
            var request = new ReviewedDeploymentRequest(SERVER, facts(local), material, archive,
                DeploymentConfigSnapshot.create("demo", 1, "v1", Instant.EPOCH, List.of()), List.of(),
                new DeploymentResourceBindings(List.of(), Optional.of(List.of())), runtime(), Optional.empty(), LIMITS,
                new DeploymentReviewConsent("demo", SERVER.id(), material.snapshot().sha256(), archive.sha256(), Instant.EPOCH),
                false, true, LAYOUT);
            var plan = new ReviewedDeploymentPlan(request, List.of(DeploymentPlanAction.VERIFY_SOURCE_IDENTITY,
                DeploymentPlanAction.CHECK_HEALTH, DeploymentPlanAction.ROLLBACK_ON_FAILURE));
            var planner = new DeploymentStandardPlanner();
            var task = new ControlledPeer(command -> "").task;
            assertThrows(IllegalArgumentException.class, () -> planner.render(plan, task));
            var rendered = planner.render(plan, task, LAYOUT);
            assertEquals(TaskResult.SUCCEEDED, rendered.outcome());
            String candidate = "/srv/wtl/state/work/demo-" + archive.sha256().substring(0, 16);
            assertTrue(rendered.value().orElseThrow().buildScript().contains("candidate='" + candidate + "'"));
            assertTrue(rendered.value().orElseThrow().buildScript().contains("source='" + candidate + "/mutable/source'"));
            assertFalse(rendered.value().orElseThrow().buildScript().contains("/var/lib/windowstolinux/work/"));
        }
    }

    private DeploymentSourceArchive archive() throws Exception {
        byte[] content = "controlled archive bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(local.resolve("source.tar.gz"), content);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        return new DeploymentSourceArchive(snapshot(local), digest, content.length, content.length,
            TASK, "9".repeat(64), "");
    }

    /** Only public immutable handle facts are stubbed; no platform lifecycle or archive validation is claimed. */
    private static FileSnapshot snapshot(Path directory) {
        return new FileSnapshot() {
            public UUID id() {
                return TASK;
            }

            public String sha256() {
                return "9".repeat(64);
            }

            public Path directory() {
                return directory;
            }

            public void close() {
            }
        };
    }

    private static DeploymentRuntimeDefinition runtime() {
        return new DeploymentRuntimeDefinition.GoService("1.24", "demo", "main.go", new DeploymentHealthCheck.Tcp(8080, 10, 2));
    }

    private static DeploymentProjectFacts facts(Path root) {
        return new DeploymentProjectFacts(root, "demo", DeploymentProjectKind.GO_SERVICE,
            DeploymentBuildToolType.GO_MODULE, List.of(), List.of(), List.of());
    }

    private static String verifiedBody(String script) {
        return DeploymentManagedHelperBundle.verifiedBody(script, LAYOUT);
    }

    private static void assertGuard(String script) {
        String body = verifiedBody(script);
        assertEquals(DeploymentManagedHelperBundle.verifiedCommand(body, LAYOUT), script);
        assertTrue(script.contains(DeploymentManagedHelperBundle.helperSha256(LAYOUT)));
        assertThrows(IllegalArgumentException.class, () -> DeploymentManagedHelperBundle.verifiedBody(script));
    }

    private static void assertTarget(ActionIntent intent, String kind, String path) {
        assertEquals(Set.of(new ActionTarget(SERVER.id(), kind, path)), intent.semantics().orElseThrow().targets());
    }

    private record SentCommand(String script, byte[] input, ActionIntent intent) {
    }

    /** Grants, command replies, upload replies and task identity are controlled; unexpected public calls fail. */
    private static final class ControlledPeer implements DeploymentCommandReview {
        private final List<ActionIntent> intents = new ArrayList<>();

        private final List<SentCommand> commands = new ArrayList<>();

        private final List<TransferRequest> uploads = new ArrayList<>();

        private final TaskContext task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
            new Class<?>[]{TaskContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "id" -> TASK;
                case "cancelToken" -> (CancelToken) () -> false;
                default -> throw new AssertionError("Unexpected task method: " + method.getName());
            });

        private final RemoteSession session;

        private ApprovalGrant pendingGrant;

        private ActionIntent pendingIntent;

        private ControlledPeer(Function<CommandRequest, String> response) {
            session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
                new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                    if (!Set.of("execute", "upload").contains(method.getName()))
                        throw new AssertionError("Unexpected session method: " + method.getName());
                    assertNotNull(pendingGrant, "Dispatch requires controlled approval");
                    assertSame(pendingGrant, args[1]);
                    if (method.getName().equals("execute")) {
                        var request = (CommandRequest) args[0];
                        assertEquals(RemoteActions.command(SERVER, pendingIntent.revision(), request)
                            .withSemantics(pendingIntent.semantics().orElseThrow()), pendingIntent);
                        commands.add(new SentCommand(request.script(), request.stdin(), pendingIntent));
                        return success(new CommandResult(Optional.of(0), response.apply(request), "", false));
                    }
                    if (method.getName().equals("upload")) {
                        var request = (TransferRequest) args[0];
                        String digest = pendingIntent.exactAction().split("\n", 3)[1].substring("sha256=".length());
                        assertEquals(RemoteActions.transfer(SERVER, pendingIntent.revision(), request, digest, true)
                            .withSemantics(pendingIntent.semantics().orElseThrow()), pendingIntent);
                        uploads.add(request);
                        return success(new TransferResult(request.maxBytes(), digest, request.localPath(), request.remoteAbsolutePath()));
                    }
                    throw new AssertionError("Unexpected session method: " + method.getName());
                });
        }

        public OperationResult<ApprovalGrant> approveCommand(ActionIntent intent, CommandRequest request, TaskContext actual) {
            assertEquals(RemoteActions.command(SERVER, intent.revision(), request)
                .withSemantics(intent.semantics().orElseThrow()), intent);
            return grant(intent, actual);
        }

        public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext actual) {
            assertEquals("platform.sftp.upload", intent.type());
            return grant(intent, actual);
        }

        private OperationResult<ApprovalGrant> grant(ActionIntent intent, TaskContext actual) {
            assertSame(task, actual);
            pendingIntent = intent;
            pendingGrant = new ApprovalGrant(UUID.randomUUID());
            intents.add(intent);
            return success(pendingGrant);
        }

        public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext actual) {
            throw new AssertionError("No text or credentials may be requested");
        }
    }

    private static <T> OperationResult<T> success(T value) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(value), Optional.empty());
    }
}
