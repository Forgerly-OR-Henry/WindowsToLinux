package gold.debug.windowstolinux.mod.deployment.db;

import gold.debug.windowstolinux.appapi.task.ActionIntent;
import gold.debug.windowstolinux.appapi.task.ApiException;
import gold.debug.windowstolinux.appapi.task.Outcome;
import gold.debug.windowstolinux.appapi.task.ProblemCode;
import gold.debug.windowstolinux.appapi.task.TaskContext;
import gold.debug.windowstolinux.appapi.task.TaskHandle;
import gold.debug.windowstolinux.appapi.task.TaskRequest;
import gold.debug.windowstolinux.appapi.task.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.TaskState;
import gold.debug.windowstolinux.appapi.task.TaskWork;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.app.db.mod.ModDeletionLedger;
import gold.debug.windowstolinux.app.main.start.LoopbackSshServer;
import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.app.service.mod.PlatformModIntents;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.mod.StopReason;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.CancelToken;
import gold.debug.windowstolinux.mod.deployment.linux.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.*;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublicationCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentApplicationReader;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpEventListener;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real exported API calls from another CLASS Mod, with actual task admission, H2 and framed loopback SSH. / 从另一 CLASS Mod 调用真实公开API，使用实际任务准入、H2及分帧回环SSH。 */
class DeploymentApiCrossModTest extends DeploymentGraphFixture {
    private final Peer peer = new Peer();

    private final List<TransferProgress> transferObservations = new CopyOnWriteArrayList<>();

    private Path crossWorkspace;

    private UUID preparationBoot;

    /** Records real enable intents in the preparation boot; activation belongs to the next actual boot. / 在准备启动登记真实启用意图，启用归属于下一次实际启动。 */
    @Override
    protected void enable() throws Exception {
        crossWorkspace = DeploymentApiCallerFixture.prepareWorkspace(workspace(), root.resolve("crossmod-workspace"));
        try (var runtime = open()) {
            start(runtime, false);
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM mod_schema"));
            assertEquals(2, scalar(runtime, "SELECT COUNT(*) FROM mod_version WHERE origin_kind='CLASS'"));
            var intents = runtime.modIntents();
            for (String owner : List.of(OWNER, DeploymentApiCallerFixture.CALLER))
                intents.apply(intents.review(owner, new PlatformModIntents.Choice(true, Optional.of(VERSION)),
                    Optional.empty()), false);
            assertEquals(2, scalar(runtime,
                "SELECT COUNT(*) FROM mod_operation WHERE state='WAITING' AND phase='WAIT_RESTART'"));
            preparationBoot = runtime.persistence().database().bootId();
        }
    }

    /** Uses the actual coordinator to load the complete graph and archive original startup applications. / 使用实际协调器加载完整图并归档原启动应用。 */
    @Override
    protected void start(PlatformRuntime runtime, boolean enabled) throws Exception {
        var report = runtime.startDevelopmentMods(Objects.requireNonNull(crossWorkspace),
            DeploymentApiCallerFixture.pages());
        assertTrue(report.problems().isEmpty(), report.problems().toString());
        assertEquals(enabled ? Set.of(OWNER, DeploymentApiCallerFixture.CALLER) : Set.of(), report.running());
        if (enabled) {
            assertNotEquals(preparationBoot, runtime.persistence().database().bootId());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM mod_operation"),
                "Actual coordinator must archive both original enable applications");
            assertEquals(2, scalar(runtime, "SELECT COUNT(*) FROM mod_registry WHERE actual_version='" + VERSION
                + "' AND last_runtime_state='RUNNING'"));
        }
    }

    @Override
    protected PlatformInteractionService.Prompts interactionPrompts() {
        return new PlatformInteractionService.Prompts() {
            public boolean approve(ActionIntent intent, TaskSnapshot task, CancelToken token) {
                return true;
            }

            public TransferConflictChoice transferConflict(TransferConflict c, ServerRef s, TaskSnapshot t,
                                                           CancelToken token) {
                throw new AssertionError();
            }

            public TransferStallChoice transferStalled(TransferStall s, ServerRef target, TaskSnapshot t,
                                                       CancelToken token) {
                throw new AssertionError();
            }

            public String transferProgress(TransferProgress progress) {
                transferObservations.add(progress);
                return "Actual restore transfer: " + progress.stage();
            }

            public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                               CancelToken token) {
                throw new AssertionError();
            }
        };
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void originalCallerCheckpointSurvivesBetweenCallsAndUnknownRecovery(boolean unknown) throws Exception {
        enable();
        try (var ssh = new LoopbackSshServer(root, server -> {
            server.setPasswordAuthenticator(
                (user, password, session) -> user.equals("tester") && password.equals("fixture-secret"));
            server.setCommandFactory((channel, command) -> new FramedReply());
        }); var runtime = open()) {
            start(runtime, true);
            runtime.servers().saveServerProfile(
                gold.debug.windowstolinux.app.service.server.ServerProfile
                    .fromStored(new gold.debug.windowstolinux.app.db.server.StoredServerProfile("server",
                        "127.0.0.1", ssh.server().getPort(), "tester", "fixture-secret")),
                "fixture-secret".toCharArray());
            String revision = publishFixture(runtime);
            {
                var caller = new DeploymentApiCallerFixture(runtime);
                peer.uncertain = unknown;
                for (var component : graph("demo").components()) {
                    peer.active.put(component.unit().id(), "a".repeat(64));
                    peer.manifests.put(component.unit().id(), component.unit().ownershipManifestSha256());
                }
                var handle = caller.collect("demo", revision, List.of("worker:uploads"), () -> {
                    try {
                        var providerBusy = assertThrows(ApiException.class,
                            () -> runtime.modRecovery().review(OWNER, Optional.empty()));
                        assertEquals(ProblemCode.BUSY, providerBusy.problem().code());
                        assertThrows(SQLException.class,
                            () -> new ModDeletionLedger(runtime.persistence().database(), root).review(OWNER));
                        assertEquals(1, scalar(runtime,
                            "SELECT COUNT(*) FROM mod_schema WHERE mod_id='" + OWNER + "' AND state='READY'"));
                        assertEquals(0,
                            scalar(runtime, "SELECT COUNT(*) FROM mod_operation WHERE mod_id='" + OWNER + "'"));
                        assertFalse(runtime.modSessions().get(OWNER).resourcesClosed());
                        var original = (TaskContext) caller.callerClass.getField("originalTask").get(null);
                        peer.originalTask = original.id();
                        peer.originalRevision = revision;
                        for (var identity : List.of(List.of(OWNER, VERSION),
                            List.of(DeploymentApiCallerFixture.CALLER, "1.1.0-dev.2"))) {
                            var rejected = assertThrows(ApiException.class, () -> runtime.tasks()
                                .bind(identity.getFirst(), identity.getLast(), false).inspect(original));
                            assertEquals(ProblemCode.ACCESS_DENIED, rejected.problem().code());
                        }
                        assertTrue(scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CUSTOM' AND resource_id='example.deploymentcaller/checkpoint/handoff' AND released_at IS NULL") > 0);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
                waitReturned(handle);
                if (!unknown) {
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), failure(runtime, caller, handle));
                    assertEquals(Optional.of(Outcome.SUCCEEDED), handle.snapshot().outcome());
                } else {
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), failure(runtime, caller, handle));
                    assertTrue(handle.snapshot().outcome().isEmpty());
                    assertTrue(scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='CUSTOM' AND resource_id='example.deploymentcaller/checkpoint/handoff' AND released_at IS NULL") > 0);
                    int creates = peer.created.get();
                    var pending = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30,
                        TimeUnit.SECONDS);
                    assertEquals(TaskState.UNKNOWN, pending.state());
                    assertEquals(creates, peer.created.get());
                    peer.uncertain = false;
                    var finished = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30,
                        TimeUnit.SECONDS);
                    assertEquals(TaskState.ENDED, finished.state(), finished.toString());
                    assertEquals(Optional.of(Outcome.FAILED), finished.outcome());
                    assertEquals(creates, peer.created.get(), "Recovery cannot create the original archive again");
                }
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT state FROM deployment_handoff")) {
                    assertTrue(rows.next());
                    assertEquals("FINISHED", rows.getString(1));
                    assertFalse(rows.next());
                }
            }
        }
    }

    @Test
    void actualExportedContractCopiesBytesAndRejectsForgedOrExpiredTaskScopes() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            publishFixture(runtime);
            {
                var caller = new DeploymentApiCallerFixture(runtime);
                var handle = caller.boundaries();
                waitReturned(handle);
                assertEquals(TaskState.ENDED, handle.snapshot().state(), failure(runtime, caller, handle));
                assertEquals(Optional.of(Outcome.SUCCEEDED), handle.snapshot().outcome());
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }

    @Test
    void retainedCallerRootBlocksStopAndMaintenanceThenOriginalSdkRecoveryClosesOnlyItsCheckpoint() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            publishFixture(runtime);
            {
                var caller = new DeploymentApiCallerFixture(runtime);
                var handle = caller.occupancy(() -> {
                    try {
                        var busy = assertThrows(ApiException.class, () -> runtime.modSessions()
                            .get(DeploymentApiCallerFixture.CALLER).stop(StopReason.APPLICATION_RESTART));
                        assertEquals(ProblemCode.BUSY, busy.problem().code());
                        assertEquals("STOPPING",
                            runtime.modSessions().get(DeploymentApiCallerFixture.CALLER).state().name());
                        assertFalse(runtime.modSessions().get(DeploymentApiCallerFixture.CALLER).resourcesClosed());
                        var maintenance = assertThrows(ApiException.class,
                            () -> runtime.modRecovery().review(OWNER, Optional.empty()));
                        assertEquals(ProblemCode.BUSY, maintenance.problem().code());
                        assertThrows(SQLException.class,
                            () -> new ModDeletionLedger(runtime.persistence().database(), root).review(OWNER));
                        assertEquals(1, scalar(runtime,
                            "SELECT COUNT(*) FROM mod_schema WHERE mod_id='" + OWNER + "' AND state='READY'"));
                        assertEquals(0,
                            scalar(runtime, "SELECT COUNT(*) FROM mod_operation WHERE mod_id='" + OWNER + "'"));
                        assertTrue(scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id='example.deploymentcaller/checkpoint/occupancy' AND released_at IS NULL") > 0);
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
                waitReturned(handle);
                assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), failure(runtime, caller, handle));
                assertTrue(handle.snapshot().outcome().isEmpty());
                var settled = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30, TimeUnit.SECONDS);
                assertEquals(TaskState.ENDED, settled.state(), settled.toString());
                assertEquals(Optional.of(Outcome.CANCELLED), settled.outcome());
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM deployment_handoff")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1), "Read-only SDK occupancy cannot create provider handoff work");
                }
            }
        }
    }

    @ParameterizedTest(name = "cleanup receipt unknown={0}")
    @ValueSource(booleans = {true})
    void prepareAndActivateKeepCallerCheckpointAndDoNotRequireHistoricalSourceProfile(boolean unknown)
        throws Exception {
        Path sourceRoot = root;
        enable();
        var archived = new AtomicReference<gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot>();
        try (var sourceRuntime = open()) {
            start(sourceRuntime, true);
            server(sourceRuntime);
            publishFixture(sourceRuntime, true);
            run(sourceRuntime, task -> {
                archived.set(new DeploymentApplicationReader(sourceRuntime.modContexts().get(OWNER).context().db())
                    .find(new gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationRef("demo",
                        new ServerRef("server")), task)
                    .snapshot());
                return success();
            });
        }
        root = sourceRoot.resolve("restore-target");
        Files.createDirectories(root);
        try {
            Path files = root.resolve("verified-input");
            Files.createDirectories(files);
            var archive = archive(archived.get(), files);
            Path remote = root.resolve("remote");
            Files.createDirectories(remote);
            peer.filesystem = remote;
            enable();
            try (var ssh = new LoopbackSshServer(root, server -> {
                server.setPasswordAuthenticator(
                    (user, password, session) -> user.equals("tester") && password.equals("fixture-secret"));
                server.setFileSystemFactory(new VirtualFileSystemFactory(remote));
                server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
                server.setCommandFactory((channel, command) -> new FramedReply());
            }); var runtime = open()) {
                start(runtime, true);
                runtime.servers().saveServerProfile(
                    gold.debug.windowstolinux.app.service.server.ServerProfile
                        .fromStored(new gold.debug.windowstolinux.app.db.server.StoredServerProfile("target",
                            "127.0.0.1", ssh.server().getPort(), "tester", "fixture-secret")),
                    "fixture-secret".toCharArray());
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM server_profile WHERE server_profile_id='server'"));
                try (var access = scope(runtime, true);
                     var connection = access.openConnection();
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM managed_application")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1), "Archived source application rows are absent on the fresh target");
                }
                {
                    var caller = new DeploymentApiCallerFixture(runtime);
                    peer.restoreCleanupUncertain = unknown;
                    var handle = caller.restore(archive, files, () -> {
                        try {
                            assertTrue(scalar(runtime,
                                "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id='example.deploymentcaller/checkpoint/restore' AND released_at IS NULL") > 0);
                            var providerBusy = assertThrows(ApiException.class,
                                () -> runtime.modRecovery().review(OWNER, Optional.empty()));
                            assertEquals(ProblemCode.BUSY, providerBusy.problem().code());
                            assertThrows(SQLException.class,
                                () -> new ModDeletionLedger(runtime.persistence().database(), root).review(OWNER));
                            assertEquals(0,
                                scalar(runtime, "SELECT COUNT(*) FROM mod_operation WHERE mod_id='" + OWNER + "'"));
                        } catch (Exception error) {
                            throw new AssertionError(error);
                        }
                    });
                    waitReturned(handle);
                    if (unknown) {
                        assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), failure(runtime, caller, handle));
                        assertTrue(handle.snapshot().outcome().isEmpty());
                        assertTrue(scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id='example.deploymentcaller/checkpoint/restore' AND released_at IS NULL") > 0);
                        int effects = peer.restoreEffects.get();
                        int preparations = peer.restorePreparationEffects.get(),
                            publications = peer.restorePublicationEffects.get();
                        int cleanups = peer.restoreCleanupEffects.get();
                        var pending = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30,
                            TimeUnit.SECONDS);
                        assertEquals(TaskState.UNKNOWN, pending.state(), failure(runtime, caller, handle));
                        assertEquals(effects, peer.restoreEffects.get());
                        peer.restoreCleanupUncertain = false;
                        var settled = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30,
                            TimeUnit.SECONDS);
                        assertEquals(TaskState.ENDED, settled.state(), failure(runtime, caller, handle));
                        assertEquals(Optional.of(Outcome.SUCCEEDED), settled.outcome());
                        assertEquals(preparations, peer.restorePreparationEffects.get(),
                            "Recovery cannot prepare or build another candidate");
                        assertEquals(publications, peer.restorePublicationEffects.get(),
                            "ACTIVE completion cannot replay publication");
                        assertEquals(cleanups + 4, peer.restoreCleanupEffects.get(),
                            "Explicit ACTIVE completion first finishes two worker databases, its runtime and its candidate cleanup");
                        assertEquals(effects + 4, peer.restoreEffects.get(),
                            "Only four previously unattempted worker cleanup effects are allowed");
                    }
                    assertEquals(TaskState.ENDED, handle.snapshot().state(), failure(runtime, caller, handle));
                    assertEquals(Optional.of(Outcome.SUCCEEDED), handle.snapshot().outcome());
                    assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    try (var access = scope(runtime, true);
                         var connection = access.openConnection();
                         var statement = connection.createStatement();
                         var rows = statement.executeQuery(
                             "SELECT platform_task_id,phase,outcome,business_committed,cleanup_complete FROM deployment_execution")) {
                        assertTrue(rows.next());
                        assertEquals(handle.id(), rows.getObject(1, UUID.class));
                        assertEquals("FINISHED", rows.getString(2));
                        assertEquals("SUCCEEDED", rows.getString(3));
                        assertTrue(rows.getBoolean(4));
                        assertTrue(rows.getBoolean(5));
                        assertFalse(rows.next());
                    }
                    assertEquals(Set.of("worker", "web"), peer.active.keySet());
                    assertEquals(2, peer.databaseCandidates.size());
                    assertEquals(2,
                        new HashSet<>(peer.databaseCandidates.values().stream().map(value -> value.get(3)).toList())
                            .size());
                    assertEquals(peer.databaseCandidates.keySet(), peer.databaseCommitted);
                    assertEquals(peer.databaseCandidates.keySet(), peer.databaseFinished);
                    assertEquals(8, peer.restorePreparationEffects.get());
                    assertEquals(8, peer.restorePublicationEffects.get());
                    assertEquals(6, peer.restoreCleanupEffects.get());
                    assertEquals(Set.of("alpha", "beta"), peer.databaseArguments.values().stream()
                        .map(value -> value.get(5)).collect(java.util.stream.Collectors.toSet()));
                }
            }
        } finally {
            root = sourceRoot;
        }
    }

    @Test
    void ordinaryPrepareAndActivateKeepsCallerCheckpointAndDoesNotRequireHistoricalSourceProfile() throws Exception {
        prepareAndActivateKeepCallerCheckpointAndDoNotRequireHistoricalSourceProfile(false);
    }

    @ParameterizedTest(name = "first prepare return lost; durable prepared={0}")
    @ValueSource(booleans = {true, false})
    void lostFirstPrepareValueIsRecoveredByOriginalPublicCallerWithoutRepreparing(boolean prepared) throws Exception {
        Path sourceRoot = root;
        enable();
        var archived = new AtomicReference<gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot>();
        try (var sourceRuntime = open()) {
            start(sourceRuntime, true);
            server(sourceRuntime);
            publishFixture(sourceRuntime, true);
            run(sourceRuntime, task -> {
                archived.set(new DeploymentApplicationReader(sourceRuntime.modContexts().get(OWNER).context().db())
                    .find(new gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationRef("demo",
                        new ServerRef("server")), task)
                    .snapshot());
                return success();
            });
        }
        root = sourceRoot.resolve("lost-prepare-target");
        Files.createDirectories(root);
        try {
            Path files = root.resolve("original-input");
            Files.createDirectories(files);
            var archive = new HashMap<>(archive(archived.get(), files));
            archive.put("malformedRuntime", fractionalCredentialFormat(archived.get().runtime().payload()));
            Path remote = root.resolve("remote");
            Files.createDirectories(remote);
            peer.filesystem = remote;
            var interrupt = new AtomicBoolean();
            var writes = new AtomicInteger();
            var authentication = new AtomicInteger();
            var lookupEffects = new AtomicInteger(-1);
            var lookupCreates = new AtomicInteger(-1);
            var lookupCommands = new AtomicInteger(-1);
            var lookupAuthentication = new AtomicInteger(-1);
            var lookupVerified = new AtomicInteger();
            var inputMissing = new AtomicBoolean(true);
            var originalStaging = new AtomicReference<Path>();
            enable();
            try (var ssh = new LoopbackSshServer(root, server -> {
                server.setPasswordAuthenticator((user, password, session) -> {
                    authentication.incrementAndGet();
                    return user.equals("tester") && password.equals("fixture-secret");
                });
                server.setFileSystemFactory(new VirtualFileSystemFactory(remote));
                var sftp = new SftpSubsystemFactory.Builder().build();
                sftp.addSftpEventListener(new SftpEventListener() {
                    public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                        int start, int length, Throwable failure) {
                        if (failure == null) {
                            writes.incrementAndGet();
                            if (interrupt.compareAndSet(true, false))
                                session.close(true);
                        }
                    }
                });
                server.setSubsystemFactories(List.of(sftp));
                server.setCommandFactory((channel, command) -> new FramedReply());
            }); var runtime = open()) {
                start(runtime, true);
                runtime.servers().saveServerProfile(
                    gold.debug.windowstolinux.app.service.server.ServerProfile
                        .fromStored(new gold.debug.windowstolinux.app.db.server.StoredServerProfile("target",
                            "127.0.0.1", ssh.server().getPort(), "tester", "fixture-secret")),
                    "fixture-secret".toCharArray());
                {
                    var caller = new DeploymentApiCallerFixture(runtime);
                    var handle = caller.lostPrepare(archive, files, prepared, () -> {
                        if (!prepared) {
                            interrupt.set(true);
                            return;
                        }
                        // Host persistence fault only; no capability or recovery implementation enters the caller.
                        try (var connection = runtime.persistence().database().openConnection();
                             var statement = connection.createStatement()) {
                            long cutoff = System.currentTimeMillis();
                            statement.execute(
                                "ALTER TABLE platform_task_resource ADD CONSTRAINT lost_prepare_release CHECK(resource_kind<>'MOD' OR released_at IS NULL OR released_at<="
                                    + cutoff + ")");
                            Thread.sleep(5);
                        } catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    }, () -> {
                        assertEquals(0, authentication.get(), "Malformed archived metadata must not connect SSH");
                        assertEquals(0, writes.get());
                        assertEquals(0, peer.commands.get());
                        assertEquals(0, peer.restoreEffects.get());
                    }, () -> {
                        assertEquals(lookupEffects.get(), peer.restoreEffects.get(),
                            "Request lookup must not prepare again");
                        assertEquals(lookupCreates.get(), peer.candidates.size());
                        assertEquals(lookupCommands.get(), peer.commands.get(),
                            "Original request lookup must not send SSH commands");
                        assertEquals(lookupAuthentication.get(), authentication.get(),
                            "Original request lookup must not connect again");
                        if (inputMissing.get())
                            assertFalse(Files.exists(files.resolve("release-worker.pax")),
                                "Input remains absent during original request lookup");
                        lookupVerified.incrementAndGet();
                    });
                    waitReturned(handle);
                    assertEquals(TaskState.UNKNOWN, handle.snapshot().state(), failure(runtime, caller, handle));
                    assertFalse((boolean) caller.callerClass.getField("firstPrepareReturned").get(null));
                    assertNull(caller.handoff(), "First prepare must lose the original ID");
                    assertTrue(writes.get() > 0,
                        "First prepare must reach actual SFTP writes before losing its return value");
                    if (!prepared)
                        assertFalse(interrupt.get(), "The original write acknowledgement fault was triggered");
                    assertEquals(prepared ? "SERVICE_RELEASE_PERSISTENCE" : "SERVICE_RELEASE_PENDING",
                        caller.callerClass.getField("firstPrepareError").get(null),
                        failure(runtime, caller, handle));
                    if (prepared) {
                        try (var connection = runtime.persistence().database().openConnection();
                             var statement = connection.createStatement()) {
                            statement
                                .execute("ALTER TABLE platform_task_resource DROP CONSTRAINT lost_prepare_release");
                        }
                    }
                    UUID originalId;
                    try (var access = scope(runtime, true);
                         var connection = access.openConnection();
                         var statement = connection.createStatement();
                         var rows = statement.executeQuery(
                             "SELECT handoff_id,platform_task_id,state,recovery_payload FROM deployment_handoff")) {
                        assertTrue(rows.next());
                        originalId = rows.getObject(1, UUID.class);
                        assertEquals(handle.id(), rows.getObject(2, UUID.class));
                        assertEquals(prepared ? "PREPARED" : "UNKNOWN", rows.getString(3),
                            failure(runtime, caller, handle));
                        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getBinaryStream(4))
                            .path("changed").asBoolean());
                        assertFalse(rows.next());
                    }
                    assertTrue(scalar(runtime,
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id='example.deploymentcaller/checkpoint/lost-restore' AND released_at IS NULL") > 0);
                    var displaced = new LinkedHashMap<Path, Path>();
                    var originalAttributes = new HashMap<Path, java.nio.file.attribute.BasicFileAttributes>();
                    try (var paths = Files.list(files)) {
                        for (Path file : paths.toList()) {
                            if (prepared)
                                Files.delete(file);
                            else {
                                originalAttributes.put(file,
                                    Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class));
                                Path retained = file.resolveSibling(file.getFileName() + ".retained");
                                Files.move(file, retained);
                                displaced.put(file, retained);
                            }
                        }
                    }
                    lookupEffects.set(peer.restoreEffects.get());
                    lookupCreates.set(peer.candidates.size());
                    lookupCommands.set(peer.commands.get());
                    lookupAuthentication.set(authentication.get());
                    int originalWrites = writes.get();
                    if (!prepared) {
                        Path staging;
                        try (var paths = Files.walk(remote)) {
                            var retained = paths.filter(path -> path.getFileName().toString()
                                .matches("\\.wtl-transfer-[0-9a-f-]{36}\\.part")).toList();
                            assertEquals(1, retained.size());
                            staging = retained.getFirst();
                        }
                        originalStaging.set(staging);
                        byte[] retained = Files.readAllBytes(staging);
                        var pending = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30,
                            TimeUnit.SECONDS);
                        assertEquals(TaskState.UNKNOWN, pending.state(), failure(runtime, caller, handle));
                        assertEquals(originalId, caller.handoff());
                        assertArrayEquals(retained, Files.readAllBytes(staging));
                        assertEquals(originalWrites, writes.get());
                        assertEquals(lookupEffects.get(), peer.restoreEffects.get());
                        assertTrue(scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='"
                            + handle.id() + "' AND resource_kind='FILE' AND released_at IS NULL") > 0);
                        assertTrue(scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE resource_id='example.deploymentcaller/checkpoint/lost-restore' AND released_at IS NULL") > 0);
                        // Restore the same original file identities. The public provider must verify the prefix and remove the existing staging itself.
                        for (var original : displaced.entrySet()) {
                            Files.move(original.getValue(), original.getKey());
                            var before = originalAttributes.get(original.getKey());
                            var after = Files.readAttributes(original.getKey(),
                                java.nio.file.attribute.BasicFileAttributes.class);
                            assertEquals(before.fileKey(), after.fileKey());
                            assertEquals(before.lastModifiedTime(), after.lastModifiedTime());
                            assertEquals(before.size(), after.size());
                        }
                        assertArrayEquals(retained, Files.readAllBytes(staging));
                        inputMissing.set(false);
                        lookupCommands.set(peer.commands.get());
                        lookupAuthentication.set(authentication.get());
                    }
                    var settled = runtime.tasks().reconcile(handle.id()).toCompletableFuture().get(30,
                        TimeUnit.SECONDS);
                    assertEquals(TaskState.ENDED, settled.state(), failure(runtime, caller, handle));
                    assertEquals(Optional.of(prepared ? Outcome.SUCCEEDED : Outcome.CANCELLED), settled.outcome());
                    assertEquals(originalId, caller.handoff());
                    assertEquals(prepared ? 1 : 2, lookupVerified.get());
                    assertEquals(originalWrites, writes.get(),
                        "Original request recovery must never re-upload consumed inputs");
                    if (!prepared) {
                        assertFalse(Files.exists(originalStaging.get()),
                            "The public provider must physically discard its verified original staging");
                    }
                    assertEquals(lookupCreates.get(), peer.candidates.size(),
                        "Original task must keep the original candidate identities");
                    if (prepared) {
                        assertEquals(8, peer.restorePreparationEffects.get(),
                            "Original request lookup cannot prepare or build again");
                        assertEquals(8, peer.restorePublicationEffects.get(),
                            "Two original components and databases publish exactly once");
                        assertEquals(6, peer.restoreCleanupEffects.get(),
                            "Each original database/runtime/candidate is cleaned exactly once");
                    }
                    assertEquals(0,
                        scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    try (var access = scope(runtime, true);
                         var connection = access.openConnection();
                         var statement = connection.createStatement();
                         var rows = statement.executeQuery(
                             "SELECT h.handoff_id,h.state,e.outcome,e.cleanup_complete FROM deployment_handoff h JOIN deployment_execution e ON e.execution_id=h.handoff_id")) {
                        assertTrue(rows.next());
                        assertEquals(originalId, rows.getObject(1, UUID.class));
                        assertEquals("FINISHED", rows.getString(2));
                        assertEquals(prepared ? "SUCCEEDED" : "CANCELLED", rows.getString(3));
                        assertTrue(rows.getBoolean(4));
                        assertFalse(rows.next());
                    }
                }
            }
        } finally {
            root = sourceRoot;
        }
    }

    @Test
    void missingTargetCredentialAndChangedCredentialPurposeFailBeforeRestoreEffects() throws Exception {
        targetRestoreRefusals(true);
    }

    @Test
    void originalTargetConfigurationRevisionConflictFailsBeforeRestoreEffects() throws Exception {
        targetRestoreRefusals(false);
    }

    private void targetRestoreRefusals(boolean secrets) throws Exception {
        Path sourceRoot = root;
        enable();
        var archived = new AtomicReference<gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot>();
        try (var source = open()) {
            start(source, true);
            server(source);
            publishFixture(source, false, secrets);
            run(source, task -> {
                archived.set(new DeploymentApplicationReader(source.modContexts().get(OWNER).context().db())
                    .find(new gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationRef("demo",
                        new ServerRef("server")), task)
                    .snapshot());
                return success();
            });
        }
        root = sourceRoot.resolve(secrets ? "secret-refusal-target" : "configuration-refusal-target");
        Files.createDirectories(root);
        try {
            Path files = root.resolve("verified-input");
            Files.createDirectories(files);
            Path remote = root.resolve("remote");
            Files.createDirectories(remote);
            peer.filesystem = remote;
            var writes = new AtomicInteger();
            var beforePrepare = new AtomicInteger();
            enable();
            try (var ssh = new LoopbackSshServer(root, server -> {
                server.setPasswordAuthenticator(
                    (user, password, session) -> user.equals("tester") && password.equals("fixture-secret"));
                server.setFileSystemFactory(new VirtualFileSystemFactory(remote));
                var sftp = new SftpSubsystemFactory.Builder().build();
                sftp.addSftpEventListener(new SftpEventListener() {
                    public void written(ServerSession session, String handle, FileHandle file, long offset, byte[] data,
                                        int start, int length, Throwable failure) {
                        if (failure == null)
                            writes.incrementAndGet();
                    }
                });
                server.setSubsystemFactories(List.of(sftp));
                server.setCommandFactory((channel, command) -> new FramedReply());
            }); var runtime = open()) {
                start(runtime, true);
                runtime.servers().saveServerProfile(
                    gold.debug.windowstolinux.app.service.server.ServerProfile
                        .fromStored(new gold.debug.windowstolinux.app.db.server.StoredServerProfile("target",
                            "127.0.0.1", ssh.server().getPort(), "tester", "fixture-secret")),
                    "fixture-secret".toCharArray());
                assertEquals(0,
                    scalar(runtime, "SELECT COUNT(*) FROM server_profile WHERE server_profile_id='server'"));
                var conflict = gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationSnapshot
                    .create("worker", 1, "1", OBSERVED.plusSeconds(1),
                        gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigurationInput
                            .parse("PORT=9999"));
                if (!secrets)
                    runTarget(runtime, task -> {
                        new DeploymentConfigurationRepository(runtime.modContexts().get(OWNER).context().db())
                            .save(task, conflict);
                        return success();
                    });
                {
                    var caller = new DeploymentApiCallerFixture(runtime);
                    for (int variant = 0; variant < (secrets ? 2 : 1); variant++) {
                        var snapshot = archived.get();
                        if (variant == 1)
                            snapshot = new gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot(
                                snapshot.application(), snapshot.displayName(), snapshot.revisionSha256(),
                                snapshot.ownershipSha256(), snapshot.state(), snapshot.observedAt(),
                                new gold.debug.windowstolinux.mod.deployment.api.VersionedRuntime(
                                    snapshot.runtime().formatVersion(),
                                    changedCredentialPurpose(snapshot.runtime().payload())),
                                snapshot.bindings(), snapshot.healthPolicySha256());
                        var requestArchive = archive(snapshot, files);
                        var handle = caller.restoreRejected(requestArchive, files,
                            secrets ? variant == 0 ? "NOT_FOUND" : "ACCESS_DENIED" : "INCOMPATIBLE",
                            secrets
                                ? variant == 0 ? "SECRET_MISSING" : "SECRET_OWNER"
                                : "DEPLOYMENT_RESTORE_INPUT_INVALID",
                            () -> beforePrepare.set(peer.commands.get()));
                        waitReturned(handle);
                        assertEquals(TaskState.ENDED, handle.snapshot().state(), failure(runtime, caller, handle));
                        assertEquals(Outcome.SUCCEEDED,
                            handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
                        assertEquals(beforePrepare.get(), peer.commands.get(),
                            "Target input authorization must precede a new SSH connection or helper request");
                        assertEquals(0, writes.get());
                        assertEquals(0, peer.restoreEffects.get());
                        assertTrue(peer.candidates.isEmpty());
                        try (var paths = Files.walk(remote)) {
                            assertTrue(paths.noneMatch(Files::isRegularFile));
                        }
                        try (var access = scope(runtime, true);
                             var connection = access.openConnection();
                             var statement = connection.createStatement()) {
                            for (String table : List.of("deployment_handoff", "deployment_execution",
                                "managed_application"))
                                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                                    assertTrue(rows.next());
                                    assertEquals(0, rows.getLong(1), table);
                                }
                        }
                        assertEquals(0, scalar(runtime,
                            "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                    }
                }
                if (!secrets)
                    runTarget(runtime, task -> {
                        assertEquals(Optional.of(conflict),
                            new DeploymentConfigurationRepository(runtime.modContexts().get(OWNER).context().db())
                                .find(task, "worker", 1));
                        return success();
                    });
            }
        } finally {
            root = sourceRoot;
        }
    }

    private static byte[] changedCredentialPurpose(byte[] runtime) throws Exception {
        var source = java.nio.ByteBuffer.wrap(runtime);
        byte[] magic = new byte["W2L-RELEASE-3\n".length()];
        source.get(magic);
        byte[] template = new byte[source.getInt()];
        source.get(template);
        byte[] credentials = new byte[source.getInt()];
        source.get(credentials);
        assertFalse(source.hasRemaining());
        String original = new String(credentials, StandardCharsets.UTF_8);
        String changed = original.replace("deployment.restore", "deployment.other");
        assertNotEquals(original, changed);
        byte[] payload = changed.getBytes(StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(magic.length + 8 + template.length + payload.length).put(magic)
            .putInt(template.length).put(template).putInt(payload.length).put(payload).array();
    }

    private void runTarget(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, TaskWork<Void> work)
        throws Exception {
        var observed = new AtomicReference<Throwable>();
        var handle = runtime.modContexts().get(OWNER).context().tasks().submit(
            new TaskRequest("target_input_acceptance", "Original target immutable input", List.of("target"), true),
            task -> {
                try {
                    return work.run(task);
                } catch (Exception | AssertionError failure) {
                    observed.set(failure);
                    throw failure;
                }
            });
        waitReturned(handle);
        if (observed.get() != null)
            throw new AssertionError("Actual target input task failed", observed.get());
        assertEquals(TaskState.ENDED, handle.snapshot().state(), handle.snapshot().toString());
        assertEquals(Outcome.SUCCEEDED, handle.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
    }

    private static byte[] fractionalCredentialFormat(byte[] runtime) throws Exception {
        var source = java.nio.ByteBuffer.wrap(runtime);
        byte[] magic = new byte["W2L-RELEASE-3\n".length()];
        source.get(magic);
        byte[] template = new byte[source.getInt()];
        source.get(template);
        byte[] credentials = new byte[source.getInt()];
        source.get(credentials);
        assertFalse(source.hasRemaining());
        String original = new String(credentials, StandardCharsets.UTF_8);
        String malformed = original.replace("\"formatVersion\":1", "\"formatVersion\":1.5");
        assertNotEquals(original, malformed);
        byte[] changed = malformed.getBytes(StandardCharsets.UTF_8);
        return java.nio.ByteBuffer.allocate(magic.length + 8 + template.length + changed.length).put(magic)
            .putInt(template.length).put(template).putInt(changed.length).put(changed).array();
    }

    private Map<String, Object> archive(
        gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationSnapshot value, Path files)
        throws Exception {
        var bindings = value.bindings().stream()
            .map(binding -> Map.<String, Object>of("id", binding.id(), "component", binding.componentId(), "kind",
                binding.kind(), "path", binding.path(), "engine", binding.engine(), "quiescence",
                binding.needsQuiescence()))
            .toList();
        var ids = new ArrayList<>(List.of("release:worker", "release:web"));
        value.bindings().forEach(binding -> ids.add(binding.id()));
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeUTF(value.revisionSha256());
            output.write(value.runtime().payload());
            for (String id : ids) {
                byte[] payload = ("controlled-pax-content:" + id + "\n").getBytes(StandardCharsets.UTF_8);
                Files.write(files.resolve(id.replace(':', '-') + ".pax"), payload);
                output.writeUTF(id);
                output.writeInt(payload.length);
                output.write(payload);
            }
        }
        var archive = new HashMap<String, Object>();
        archive.put("application", value.application().id());
        archive.put("source", value.application().server().id());
        archive.put("display", value.displayName());
        archive.put("revision", value.revisionSha256());
        archive.put("ownership", value.ownershipSha256());
        archive.put("state", value.state().name());
        archive.put("observed", value.observedAt().toString());
        archive.put("format", value.runtime().formatVersion());
        archive.put("runtime", value.runtime().payload());
        archive.put("bindings", bindings);
        archive.put("health", value.healthPolicySha256());
        archive.put("archiveSha",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
        return Map.copyOf(archive);
    }

    private String publishFixture(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime) throws Exception {
        return publishFixture(runtime, false);
    }

    private String publishFixture(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, boolean databases)
        throws Exception {
        return publishFixture(runtime, databases, false);
    }

    private String publishFixture(gold.debug.windowstolinux.app.main.start.PlatformRuntime runtime, boolean databases,
                                  boolean withSecret) throws Exception {
        var observed = new AtomicReference<String>();
        run(runtime, task -> {
            var context = runtime.modContexts().get(OWNER).context();
            var graph = graph("demo");
            if (databases) {
                var worker = graph.components().stream().filter(value -> value.componentId().equals("worker"))
                    .findFirst().orElseThrow();
                var bindings = List.of(
                    new DeploymentDatabaseBinding("alpha", new DeploymentDatabaseConnection.Sqlite("alpha.sqlite")),
                    new DeploymentDatabaseBinding("beta", new DeploymentDatabaseConnection.Sqlite("beta.sqlite")));
                var replaced = new DeploymentApplicationGraph.Component(worker.componentId(), worker.unit(),
                    worker.runtime(), worker.dependencies(), worker.reviewedRuntime(), worker.dataPaths(),
                    new DeploymentResourceBindings(worker.resources().fileBindings(), Optional.of(bindings)));
                graph = new DeploymentApplicationGraph(graph.applicationId(), graph.healthComponentId(),
                    graph.healthCheck(), graph.components().stream()
                    .map(value -> value.componentId().equals("worker") ? replaced : value).toList());
            }
            peer.restoreComponents.clear();
            for (var component : graph.components())
                peer.restoreComponents.put(component.unit().id(), component);
            var secrets = new ArrayList<DeploymentSecretReference>();
            var references = new HashMap<DeploymentSecretReference, gold.debug.windowstolinux.appapi.secret.SecretRef>();
            if (withSecret) {
                var revision = new DeploymentSecretReference("restore-secret", 1);
                var actual = context.secrets().create("deployment.restore", new byte[]{7}, task);
                new DeploymentSecretRepository(context.db()).saveRevision(task,
                    new DeploymentSecretRevision(revision, actual.id(), OBSERVED, Optional.of(actual.purpose())));
                secrets.add(revision);
                references.put(revision, actual);
            }
            var proposal = new DeploymentPublication(graph, publications(graph, 1, secrets));
            var plan = new DeploymentPublicationPlan(UUID.randomUUID(), Optional.empty(), "demo",
                new ServerRef("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "a".repeat(64),
                DeploymentOriginalPublicationCommit.references(new ServerRef("server"), proposal, references),
                Instant.now());
            var commit = new DeploymentOriginalPublicationCommit(context);
            commit.begin(plan, proposal, task, "deployment-agent");
            assertEquals(Outcome.SUCCEEDED, commit.commitVerified(task, plan.executionId(), proposal).outcome());
            var record = new DeploymentExecutionRepository(context.db()).find(task, plan.executionId()).orElseThrow();
            assertEquals(Outcome.SUCCEEDED, commit.finishKnown(task, record).outcome());
            observed.set(new DeploymentApplicationReader(context.db())
                .find(new gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationRef("demo",
                    new ServerRef("server")), task)
                .snapshot().revisionSha256());
            return success();
        });
        return observed.get();
    }

    /** Reads only non-secret original phase facts while the real runtime remains open. / 在真实运行时仍开放时仅读取原非秘密阶段依据。 */
    private String failure(PlatformRuntime runtime, DeploymentApiCallerFixture caller, TaskHandle<?> handle)
        throws Exception {
        var detail = new StringBuilder(handle.snapshot().toString()).append(" caller assertion=")
            .append(caller.callerClass.getField("assertion").get(null)).append(" firstPrepareProvider=")
            .append(caller.callerClass.getField("firstPrepareProviderEvidence").get(null)).append(" peer verbs=")
            .append(peer.verbs).append(" restore calls=")
            .append(caller.callerClass.getField("restoreCallEvidence").get(null)).append(" transfer observations=")
            .append(transferObservations);
        try (var access = scope(runtime, true);
             var connection = access.openConnection();
             var statement = connection.prepareStatement(
                 "SELECT state,recovery_payload FROM deployment_handoff WHERE platform_task_id=?")) {
            statement.setObject(1, handle.id());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var facts = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getBinaryStream(2));
                    detail.append(" handoff=").append(rows.getString(1)).append(" changed=")
                        .append(facts.path("changed")).append(" executionStarted=")
                        .append(facts.path("executionStarted")).append(" immutableStageAttempted=")
                        .append(facts.path("immutableStageAttempted")).append(" components=")
                        .append(facts.path("components"));
                }
            }
            try (var execution = connection.prepareStatement(
                "SELECT phase,outcome,business_committed,cleanup_complete FROM deployment_execution WHERE platform_task_id=?")) {
                execution.setObject(1, handle.id());
                try (var rows = execution.executeQuery()) {
                    while (rows.next())
                        detail.append(" formal=").append(rows.getString(1)).append('/').append(rows.getString(2))
                            .append('/').append(rows.getBoolean(3)).append('/').append(rows.getBoolean(4));
                }
            }
        }
        try (var connection = runtime.persistence().database().openConnection();
             var resources = connection.prepareStatement(
                 "SELECT lease_id,resource_kind,resource_id,owner_id,access_mode,recovery_json FROM platform_task_resource WHERE task_id=? AND released_at IS NULL ORDER BY resource_kind,resource_id,lease_id")) {
            resources.setObject(1, handle.id());
            try (var rows = resources.executeQuery()) {
                while (rows.next()) {
                    detail.append(" active lease=").append(rows.getObject(1)).append('/').append(rows.getString(2))
                        .append('/').append(rows.getString(3)).append('/').append(rows.getString(4)).append('/')
                        .append(rows.getString(5));
                    var recovery = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString(6));
                    detail.append(" release=").append(recovery.path("release")).append(" protocol=")
                        .append(recovery.path("protocol"));
                    if (recovery.path("protocol").asText().equals("service.v1")) {
                        var identity = recovery.path("fields");
                        for (String field : List.of("bootId", "callId", "providerId", "providerVersion",
                            "schemaVersion", "schemaGeneration"))
                            detail.append(' ').append(field).append('=').append(identity.path(field));
                    }
                }
            }
        }
        return detail.toString();
    }

    private static void waitReturned(TaskHandle<?> handle) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (handle.snapshot().state() != TaskState.ENDED && handle.snapshot().state() != TaskState.UNKNOWN
            && System.nanoTime() < deadline)
            Thread.sleep(20);
        assertTrue(handle.snapshot().state() == TaskState.ENDED || handle.snapshot().state() == TaskState.UNKNOWN,
            handle.snapshot().toString());
    }

    private final class Peer {
        final Map<String, String> artifacts = new ConcurrentHashMap<>();

        final Set<String> maintenance = ConcurrentHashMap.newKeySet();

        final Set<String> closed = ConcurrentHashMap.newKeySet();

        final AtomicInteger created = new AtomicInteger();

        final Map<String, List<String>> candidates = new ConcurrentHashMap<>();

        final Map<String, String> steps = new ConcurrentHashMap<>();

        final Map<String, List<String>> stepRequests = new ConcurrentHashMap<>();

        final Set<String> cleaned = ConcurrentHashMap.newKeySet();

        final AtomicInteger restoreEffects = new AtomicInteger();

        final AtomicInteger restorePreparationEffects = new AtomicInteger();

        final AtomicInteger restorePublicationEffects = new AtomicInteger();

        final AtomicInteger restoreCleanupEffects = new AtomicInteger();

        final AtomicInteger commands = new AtomicInteger();

        final List<String> verbs = new CopyOnWriteArrayList<>();

        final Map<String, Boolean> running = new ConcurrentHashMap<>();

        final Map<String, String> manifests = new ConcurrentHashMap<>();

        final Map<String, DeploymentApplicationGraph.Component> restoreComponents = new ConcurrentHashMap<>();

        final Set<String> discarded = ConcurrentHashMap.newKeySet();

        final Map<String, List<String>> databaseCandidates = new ConcurrentHashMap<>();

        final Map<String, List<String>> databaseArguments = new ConcurrentHashMap<>();

        final Set<String> databaseCommitted = ConcurrentHashMap.newKeySet(),
            databaseFinished = ConcurrentHashMap.newKeySet();

        final Map<String, String> active = new ConcurrentHashMap<>();

        Path filesystem;

        UUID originalTask;

        String originalRevision;

        volatile boolean uncertain;

        volatile boolean restoreCleanupUncertain;

        String response(String script) throws Exception {
            commands.incrementAndGet();
            if (script.contains("sha256sum --check --status"))
                return "HELPER=1\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nSHA256="
                    + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
            var arguments = new ArrayList<String>();
            var matcher = Pattern.compile("'([^']*)'").matcher(script);
            while (matcher.find())
                arguments.add(matcher.group(1));
            if (arguments.size() < 2)
                throw new AssertionError("Unexpected framed command");
            String verb = arguments.get(1);
            var values = arguments.subList(2, arguments.size());
            verbs.add(verb);
            return switch (verb) {
                case "managed-target-status" -> {
                    assertEquals(3, values.size());
                    var component = restoreComponents.get(values.getFirst());
                    assertNotNull(component);
                    assertEquals(component.unit().ownershipManifestSha256(), values.get(1));
                    assertEquals("deployment", values.get(2));
                    yield "MANAGED_TARGET=1\nPRESENT=" + (active.containsKey(values.getFirst()) ? 1 : 0)
                        + "\nKIND=deployment\nRELEASE_SHA256=" + active.getOrDefault(values.getFirst(), "-") + "\n";
                }
                case "handoff-restore-preflight" -> "RESTORE_SPACE_VERIFIED=1\n";
                case "stage-config" -> "INPUT_STAGED=1\n";
                case "handoff-restore-create" -> {
                    assertEquals(4, values.size());
                    UUID.fromString(values.get(2));
                    assertTrue(values.get(3).matches("[0-9a-f]{64}"));
                    var component = restoreComponents.get(values.getFirst());
                    assertNotNull(component);
                    String candidate = values.get(1);
                    assertTrue(candidate.matches(Pattern.quote(component.unit().id()) + "-[0-9a-f]{16}"));
                    assertNull(candidates.putIfAbsent(candidate, List.copyOf(values)));
                    restoreEffects.incrementAndGet();
                    restorePreparationEffects.incrementAndGet();
                    Files.createDirectories(
                        filesystem.resolve("var/lib/windowstolinux/candidates/" + candidate + "/mutable/restore"));
                    yield "RESTORE_CANDIDATE=/var/lib/windowstolinux/candidates/" + candidate + "\n";
                }
                case "handoff-restore-file" -> {
                    assertEquals(candidates.get(values.get(1)), values.subList(0, 4));
                    Path actual = filesystem.resolve(
                        "var/lib/windowstolinux/candidates/" + values.get(1) + "/mutable/restore/" + values.get(4));
                    assertEquals(Long.parseLong(values.get(5)), Files.size(actual));
                    assertEquals(values.get(6), HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(actual))));
                    yield "RESTORE_FILE_VERIFIED=1\n";
                }
                case "handoff-restore-step", "handoff-restore-step-status" -> {
                    assertEquals(candidates.get(values.get(1)), values.subList(0, 4));
                    String key = values.get(1) + ":" + values.get(4);
                    if (verb.endsWith("-status")) {
                        assertEquals(stepRequests.get(key), values);
                        yield restoreCleanupUncertain && values.getFirst().equals("web")
                            ? ""
                            : steps.getOrDefault(key, "");
                    }
                    assertNull(stepRequests.putIfAbsent(key, List.copyOf(values)));
                    restoreEffects.incrementAndGet();
                    switch (values.get(5)) {
                        case "restore-prepare", "restore-db-create", "database-restore-candidate" ->
                            restorePreparationEffects.incrementAndGet();
                        case "restore-snapshot", "restore-mark-quiesced", "restore-start-formal",
                             "database-commit-candidate" -> restorePublicationEffects.incrementAndGet();
                        case "restore-finish", "database-finish-candidate" -> restoreCleanupEffects.incrementAndGet();
                        default -> throw new AssertionError("Unclassified restore effect " + values.get(5));
                    }
                    var action = values.subList(6, values.size());
                    String receipt = switch (values.get(5)) {
                        case "restore-prepare" -> {
                            var component = restoreComponents.get(values.getFirst());
                            assertNotNull(component);
                            assertEquals(values.get(1), action.getFirst());
                            assertEquals(UUID.fromString(values.get(2)).toString().replace("-", ""), action.get(1));
                            assertEquals(component.componentId(), action.get(2));
                            assertEquals(component.unit().id(), action.get(3));
                            assertEquals(component.unit().ownershipManifestSha256(), action.get(4));
                            assertEquals("a".repeat(64), action.get(5));
                            yield "PREPARED=1\nCOMPONENT=" + component.componentId() + "\n";
                        }
                        case "restore-snapshot" -> "SNAPSHOT=1\nPREVIOUS=0\n";
                        case "restore-mark-quiesced" -> "QUIESCED=1\n";
                        case "restore-start-formal" -> {
                            active.put(values.getFirst(), "a".repeat(64));
                            running.put(values.getFirst(), true);
                            yield "FORMAL_STARTED=1\nAPP=" + values.getFirst() + "\n";
                        }
                        case "restore-finish" -> "RESTORE_FINISHED=1\n";
                        case "restore-db-create" -> {
                            var component = restoreComponents.get(values.getFirst());
                            assertNotNull(component);
                            assertEquals(component.unit().id(), action.getFirst());
                            assertEquals(5, action.size());
                            var binding = component.resources().databaseBindings().orElseThrow().stream()
                                .filter(value -> action.get(2).equals("__database-" + value.databaseId() + ".sql"))
                                .findFirst().orElseThrow();
                            String bindingId = component.componentId() + ":" + binding.databaseId();
                            assertEquals(
                                "create-db-" + UUID.nameUUIDFromBytes(bindingId.getBytes(StandardCharsets.UTF_8))
                                    .toString().replace("-", "").substring(0, 16),
                                values.get(4));
                            assertTrue(action.get(1).matches(Pattern.quote(component.unit().id()) + "-[0-9a-f]{16}"));
                            assertTrue(action.get(3).matches("db-[0-9a-f]{32}"));
                            Path input = filesystem.resolve("var/lib/windowstolinux/candidates/" + values.get(1)
                                + "/mutable/restore/" + action.get(2));
                            assertEquals(action.get(4), HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
                            assertNull(databaseCandidates.putIfAbsent(action.get(1), List.copyOf(action)));
                            yield "DATABASE_CANDIDATE_CREATED=1\n";
                        }
                        case "database-restore-candidate" -> {
                            var original = databaseCandidates.get(action.get(2));
                            assertNotNull(original);
                            assertEquals(original.getFirst(), action.getFirst());
                            assertEquals(action.getFirst(), action.get(1));
                            assertEquals(original.get(3), action.get(3));
                            assertEquals("sqlite", action.get(4));
                            var component = restoreComponents.get(values.getFirst());
                            assertNotNull(component);
                            assertEquals(component.unit().id(), action.getFirst());
                            var binding = component.resources().databaseBindings().orElseThrow().stream()
                                .filter(value -> value.databaseId().equals(action.get(5))).findFirst()
                                .orElseThrow();
                            var sqlite = assertInstanceOf(DeploymentDatabaseConnection.Sqlite.class,
                                binding.connection());
                            assertEquals(9, action.size());
                            assertEquals(sqlite.location().type().name(), action.get(6));
                            assertEquals(sqlite.location().path().isEmpty() ? "-" : sqlite.location().path(),
                                action.get(7));
                            assertEquals(sqlite.fileName(), action.get(8));
                            assertEquals("__database-" + binding.databaseId() + ".sql", original.get(2));
                            assertNull(databaseArguments.putIfAbsent(action.get(2), List.copyOf(action)));
                            yield "CANDIDATE_ID=" + action.get(2) + "\nCONNECTION_TOKEN=" + action.get(2)
                                + "\nINTEGRITY_VERIFIED=1\nSCHEMA_READABLE=1\n";
                        }
                        case "database-commit-candidate" -> {
                            assertEquals(databaseArguments.get(action.get(2)), action);
                            assertTrue(databaseCommitted.add(action.get(2)));
                            yield "CANDIDATE_ID=" + action.get(2) + "\nCOMMITTED=1\nPREVIOUS_RETAINED=1\n";
                        }
                        case "database-finish-candidate" -> {
                            assertEquals(databaseArguments.get(action.get(2)), action);
                            assertTrue(databaseCommitted.contains(action.get(2)));
                            assertTrue(databaseFinished.add(action.get(2)));
                            yield "DATABASE_FINISHED=1\n";
                        }
                        default -> throw new AssertionError("Unexpected restore step " + values.get(5));
                    };
                    String response = "RESTORE_STEP=DONE\n" + receipt;
                    assertNull(steps.putIfAbsent(key, response));
                    yield response;
                }
                case "handoff-restore-discard", "handoff-restore-discard-status" -> {
                    assertEquals(candidates.get(values.get(1)), values);
                    Path candidate = filesystem.resolve("var/lib/windowstolinux/candidates/" + values.get(1))
                        .normalize();
                    assertTrue(candidate.startsWith(filesystem.normalize()));
                    if (verb.equals("handoff-restore-discard")) {
                        assertTrue(discarded.add(values.get(1)), "Original candidate discard cannot replay");
                        try (var paths = Files.walk(candidate)) {
                            assertFalse(paths.anyMatch(Files::isRegularFile),
                                "SDK must remove staging without publishing a final target before helper candidate cleanup");
                        }
                        try (var paths = Files.walk(candidate)) {
                            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                                Files.delete(path);
                        }
                        restoreEffects.incrementAndGet();
                        restoreCleanupEffects.incrementAndGet();
                    }
                    assertFalse(Files.exists(candidate));
                    yield discarded.contains(values.get(1)) ? "RESTORE_DISCARDED=1\n" : "";
                }
                case "handoff-restore-cleanup", "handoff-restore-cleanup-status" -> {
                    assertEquals(candidates.get(values.get(1)), values);
                    if (verb.equals("handoff-restore-cleanup")) {
                        assertTrue(cleaned.add(values.get(1)), "Original cleanup cannot be repeated");
                        Path candidate = filesystem.resolve("var/lib/windowstolinux/candidates/" + values.get(1))
                            .normalize();
                        assertTrue(candidate.startsWith(filesystem.normalize()));
                        try (var paths = Files.walk(candidate)) {
                            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                                Files.delete(path);
                        }
                        restoreEffects.incrementAndGet();
                        restoreCleanupEffects.incrementAndGet();
                    }
                    assertFalse(Files.exists(filesystem.resolve("var/lib/windowstolinux/candidates/" + values.get(1))));
                    yield cleaned.contains(values.get(1))
                        && !(restoreCleanupUncertain && values.getFirst().equals("web"))
                        ? "RESTORE_CLEANUP=1\n"
                        : "";
                }
                case "application-health" -> "HEALTHY=1\n";
                case "managed-current-status" -> {
                    assertEquals(3, values.size());
                    assertEquals("a".repeat(64), values.get(1));
                    assertEquals("deployment", values.get(2));
                    assertTrue(active.containsKey(values.getFirst()),
                        "Current release must be actual source or verified restored target");
                    yield "MANAGED_CURRENT=1\nKIND=deployment\nRELEASE_SHA256=" + active.get(values.getFirst()) + "\n";
                }
                case "inspect-runtime" -> "KIND=deployment\nMODE=DAEMON\n";
                case "lifecycle-deployment" -> {
                    assertEquals(4, values.size());
                    assertTrue(Set.of("worker", "web").contains(values.getFirst()));
                    assertTrue(Set.of("start", "stop").contains(values.get(1)));
                    assertEquals(manifests.get(values.getFirst()), values.get(2));
                    assertEquals(active.get(values.getFirst()), values.get(3),
                        "Lifecycle must bind the exact original CURRENT");
                    assertTrue(maintenance.contains(values.getFirst()),
                        "Collection lifecycle must retain its original maintenance marker");
                    running.put(values.getFirst(), !values.get(1).equals("stop"));
                    yield "LIFECYCLE=" + values.get(1) + "\n";
                }
                case "observe-deployment" -> {
                    assertEquals(2, values.size());
                    assertTrue(Set.of("worker", "web").contains(values.getFirst()));
                    assertEquals("a".repeat(64), values.get(1));
                    boolean live = running.getOrDefault(values.getFirst(), true);
                    yield "OWNER=1\nRUNNING=" + (live ? 1 : 0) + "\nENABLED=enabled\nQUERY_OK=1\nActiveState="
                        + (live ? "active" : "inactive") + "\nSubState=" + (live ? "running" : "dead")
                        + "\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID=" + (live ? 123 : 0) + "\n";
                }
                case "application-maintenance" -> {
                    assertEquals(5, values.size());
                    assertTrue(Set.of("begin", "end").contains(values.getFirst()));
                    assertEquals(manifests.get(values.get(1)), values.get(2));
                    assertTrue(values.get(3).matches("backup-[0-9a-f]{32}"));
                    assertEquals(active.get(values.get(1)), values.get(4));
                    if (values.getFirst().equals("begin"))
                        assertTrue(maintenance.add(values.get(1)));
                    else
                        assertTrue(maintenance.remove(values.get(1)));
                    yield "MAINTENANCE=" + values.getFirst() + "\n";
                }
                case "handoff-maintenance-status" ->
                    "HANDOFF_MAINTENANCE=1\nPRESENT=" + (maintenance.contains(values.getFirst()) ? 1 : 0) + "\n";
                case "handoff-operation-close", "handoff-operation-close-status" -> {
                    assertEquals(4, values.size());
                    assertEquals("demo", values.get(1));
                    assertEquals(originalTask.toString(), values.get(2));
                    assertEquals(originalRevision, values.get(3));
                    if (verb.equals("handoff-operation-close"))
                        closed.add(values.getFirst());
                    yield closed.contains(values.getFirst()) ? "HANDOFF_OPERATION_CLOSED=1\n" : "";
                }
                case "backup-create", "handoff-backup-status" -> {
                    assertEquals(9, values.size());
                    assertEquals("demo", values.get(1));
                    assertEquals(values.get(2), values.get(3));
                    assertEquals(active.get(values.get(3)), values.get(4));
                    assertEquals(manifests.get(values.get(3)), values.get(5));
                    assertTrue(Set.of("release", "file").contains(values.get(6)));
                    assertEquals(values.get(6).equals("release") ? "release" : "uploads", values.get(7));
                    assertTrue(Long.parseLong(values.get(8)) > 0);
                    assertFalse(running.getOrDefault(values.get(3), true),
                        "Producer must only run after verified source quiescence");
                    String id = artifact(values);
                    String sealed = "ARTIFACT=" + id + "\nKIND=" + values.get(6) + "\nSIZE=12\nSHA256=" + "b".repeat(64)
                        + "\n";
                    if (verb.equals("backup-create")) {
                        assertFalse(closed.contains(values.getFirst()),
                            "Closed original operation cannot start another producer");
                        created.incrementAndGet();
                        artifacts.put(id, sealed);
                        yield uncertain ? "" : sealed;
                    }
                    if (uncertain)
                        yield "";
                    yield artifacts.containsKey(id)
                        ? "HANDOFF_BACKUP=1\nPRESENT=1\nCOMPLETE=1\n" + artifacts.get(id)
                        : "HANDOFF_BACKUP=1\nPRESENT=0\n";
                }
                case "backup-discard" -> {
                    artifacts.clear();
                    yield "DISCARDED=1\n";
                }
                default -> throw new AssertionError("Unexpected helper verb " + verb);
            };
        }

        String artifact(List<String> values) throws Exception {
            var digest = MessageDigest.getInstance("SHA-256");
            for (int index : List.of(1, 2, 3, 4, 6, 7)) {
                digest.update(values.get(index).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return "artifact-" + HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        }
    }

    private final class FramedReply implements Command {
        private InputStream input;

        private OutputStream output;

        private org.apache.sshd.server.ExitCallback callback;

        private Thread worker;

        public void setInputStream(InputStream value) {
            input = value;
        }

        public void setOutputStream(OutputStream value) {
            output = value;
        }

        public void setErrorStream(OutputStream value) {
        }

        public void setExitCallback(org.apache.sshd.server.ExitCallback value) {
            callback = value;
        }

        public void start(ChannelSession channel, org.apache.sshd.server.Environment environment) {
            worker = new Thread(() -> {
                try {
                    var length = new ByteArrayOutputStream();
                    for (int next; (next = input.read()) != '\n'; ) {
                        if (next < '0' || next > '9' || length.size() >= 8)
                            throw new IOException("Invalid frame");
                        length.write(next);
                    }
                    int count = Integer.parseInt(length.toString(StandardCharsets.US_ASCII));
                    if (count < 1 || count > 16_777_216)
                        throw new IOException("Frame exceeds bounds");
                    byte[] bytes = input.readNBytes(count);
                    if (bytes.length != count)
                        throw new IOException("Truncated frame");
                    output.write(
                        peer.response(new String(bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    callback.onExit(0);
                } catch (Throwable failure) {
                    failure.printStackTrace();
                    callback.onExit(1);
                }
            }, "deployment-api-crossmod-ssh");
            worker.start();
        }

        public void destroy(ChannelSession channel) throws Exception {
            if (input != null)
                input.close();
            if (worker != null && worker != Thread.currentThread()) {
                worker.join(5000);
                assertFalse(worker.isAlive());
            }
        }
    }
}
