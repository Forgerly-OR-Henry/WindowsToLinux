package gold.debug.windowstolinux.app.main.startup;

import static gold.debug.windowstolinux.app.main.startup.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.shared.model.server.ServerIdentity;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformRemoteCapabilitiesTest {
    @TempDir
    Path root;

    static final ServerRef SERVER = new ServerRef("server");

    static final class Reviews implements PlatformInteractionService.Prompts {
        java.util.function.Function<gold.debug.windowstolinux.appapi.linux.TransferConflict, gold.debug.windowstolinux.appapi.linux.TransferConflictChoice> conflictPolicy = conflict -> gold.debug.windowstolinux.appapi.linux.TransferConflictChoice.TERMINATE;
        public gold.debug.windowstolinux.appapi.linux.TransferConflictChoice transferConflict(
                gold.debug.windowstolinux.appapi.linux.TransferConflict conflict,
                gold.debug.windowstolinux.appapi.server.ServerRef server, TaskSnapshot task,
                CancellationToken cancellation) {
            return conflictPolicy.apply(conflict);
        }
        final List<ActionIntent> approved = new CopyOnWriteArrayList<>();

        volatile java.util.function.Predicate<ActionIntent> policy = intent -> true;

        java.util.function.Function<TransferStall, TransferStallChoice> stallPolicy = stall -> TransferStallChoice.TERMINATE;
        public gold.debug.windowstolinux.appapi.linux.TransferStallChoice transferStalled(
                gold.debug.windowstolinux.appapi.linux.TransferStall stall,
                gold.debug.windowstolinux.appapi.server.ServerRef server, TaskSnapshot task,
                CancellationToken cancellation) {
            return stallPolicy.apply(stall);
        }

        public String transferProgress(gold.debug.windowstolinux.appapi.linux.TransferProgress progress) {
            return progress.stage().name();
        }

        public boolean approve(ActionIntent intent, TaskSnapshot task, CancellationToken cancellation) {
            approved.add(intent);
            return policy.test(intent);
        }

        public char[] text(String title, String prompt, boolean secret, TaskSnapshot task,
                CancellationToken cancellation) {
            return null;
        }
    }

    static final class Remote implements AutoCloseable {
        final LoopbackSshServer listener;

        final Path files;

        final AtomicInteger commands = new AtomicInteger();

        final AtomicInteger authentications = new AtomicInteger();
        Remote(Path root) throws Exception {
            this(root, 0);
        }

        Remote(Path root, int port) throws Exception {
            Files.createDirectories(root);
            files = Files.createDirectories(root.resolve("remote"));
            var bash = Path.of(System.getProperty("managed.test.bash", "/bin/bash"));
            assertTrue(Files.isRegularFile(bash), "Local integration requires managed.test.bash");
            listener = new LoopbackSshServer(root, server -> {
                server.setPort(port);
                server.setFileSystemFactory(new VirtualFileSystemFactory(files));
                server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));
                server.setPasswordAuthenticator((user, password, session) -> {
                    authentications.incrementAndGet();
                    return user.equals("alice") && password.equals("fixture-secret");
                });
                server.setCommandFactory((channel, command) -> {
                    commands.incrementAndGet();
                    Path entry = root.resolve("wire-" + UUID.randomUUID() + ".sh");
                    Files.writeString(entry, command, StandardCharsets.UTF_8);
                    return new ProcessShellFactory("fixture",
                            List.of(bash.toString(), "--noprofile", "--norc", entry.toString())) {
                        @Override
                        protected List<String> resolveEffectiveCommand(ChannelSession ignored, String name,
                                List<String> arguments) {
                            return arguments;
                        }
                    }.createShell(channel);
                });
            });
        }

        void configure(PlatformRuntime runtime) throws Exception {
            initialize(runtime, false);
            runtime.persistence().servers().saveServerProfile(new StoredServerProfile("server", "127.0.0.1",
                    listener.server().getPort(), "alice", "credential", "本机回环"));
        }

        public void close() throws Exception {
            listener.close();
        }
    }

    static CommandRequest command(String script) {
        return new CommandRequest(script, new byte[0], Duration.ofSeconds(5), 8192);
    }

    static ApprovalGrant approve(PlatformRuntime.RemoteCapabilities capabilities, CommandRequest command,
            TaskContext task) throws Exception {
        return capabilities.interaction().approve(RemoteActions.command(SERVER, "revision-1", command), task).value()
                .orElseThrow();
    }

    static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static <T> OperationResult<T> result(TaskHandle<T> task) throws Exception {
        return task.completion().toCompletableFuture().get(12, TimeUnit.SECONDS);
    }

    @Test
    void actualDefaultCapabilitiesReviewExactCommandsTransfersAndRetainAuthorizationAcrossLock() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.remoteCapabilities(OWNER, VERSION)) {
                byte[] bytes = "文件内容\0with bytes".getBytes(StandardCharsets.UTF_8);
                var source = root.resolve("源 文件.bin");
                Files.write(source, bytes);
                var received = root.resolve("下载 文件.bin");
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    try (var session = capabilities.linux().connect(SERVER, context)) {
                        assertTrue(session.facts().fingerprint().startsWith("SHA256:"));
                        assertEquals("", session.facts().operatingSystem());
                        var request = command("builtin printf 'approved output'");
                        var grant = approve(capabilities, request, context);
                        assertEquals(ProblemCode.ACCESS_DENIED,
                                assertThrows(ApiException.class,
                                        () -> session.execute(request, new ApprovalGrant(grant.id()))).problem()
                                        .code());
                        assertThrows(ApiException.class,
                                () -> session.execute(command("builtin printf 'changed'"), grant));
                        assertThrows(ApiException.class, () -> session.execute(new CommandRequest(request.script(),
                                new byte[]{1}, request.timeout(), request.outputLimitBytes()), grant));
                        assertEquals(0, remote.commands.get());
                        runtime.security().lock();
                        try {
                            assertEquals("approved output",
                                    session.execute(request, grant).value().orElseThrow().stdout());
                        } finally {
                            runtime.security().unlock(PASSWORD.toCharArray());
                        }
                        assertThrows(ApiException.class, () -> session.execute(request, grant));
                        var upload = new TransferRequest(source, "/远端 文件.bin", bytes.length);
                        var uploadGrant = capabilities.interaction()
                                .approve(RemoteActions.transfer(SERVER, "revision-2", upload, digest(bytes), true),
                                        context)
                                .value().orElseThrow();
                        assertThrows(ApiException.class, () -> session.download(upload, uploadGrant));
                        assertThrows(ApiException.class, () -> session
                                .upload(new TransferRequest(source, "/unreviewed", bytes.length), uploadGrant));
                        assertEquals(Outcome.SUCCEEDED, session.upload(upload, uploadGrant).outcome());
                        var download = new TransferRequest(received, "/远端 文件.bin", bytes.length);
                        var downloadGrant = capabilities.interaction()
                                .approve(RemoteActions.transfer(SERVER, "revision-3", download, digest(bytes), false),
                                        context)
                                .value().orElseThrow();
                        assertEquals(Outcome.SUCCEEDED, session.download(download, downloadGrant).outcome());
                        return success(null);
                    }
                });
                assertEquals(Outcome.SUCCEEDED, result(task).outcome());
                assertArrayEquals(bytes, Files.readAllBytes(received));
                assertArrayEquals(bytes, Files.readAllBytes(remote.files.resolve("远端 文件.bin")));
                assertEquals(4, reviews.approved.size());
                assertEquals(1, remote.commands.get());
                assertEquals(0, runtime.security().securityState().activeTasks());
                assertTrue(runtime.persistence().servers().findServer("server").isPresent());
                try (var c = runtime.persistence().database().openConnection();
                        var p = c.prepareStatement(
                                "SELECT recovery_json,released_at,owner_id FROM platform_task_resource WHERE task_id=? AND resource_kind='FILE'")) {
                    p.setObject(1, task.id());
                    try (var rows = p.executeQuery()) {
                        int checkpoints = 0;
                        while (rows.next()) {
                            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(rows.getString(1));
                            assertEquals("sftp.v1", data.path("protocol").asText());
                            var fields = data.path("fields");
                            assertEquals("server", fields.path("serverId").asText());
                            assertTrue(fields.path("fingerprint").asText().startsWith("SHA256:"));
                            assertEquals(digest(bytes), fields.path("source.sha256").asText());
                            assertEquals(OWNER, rows.getString(3));
                            assertNotNull(rows.getObject(2));
                            checkpoints++;
                        }
                        assertEquals(2, checkpoints);
                    }
                }
            }
        }
    }

    @Test
    void failedDurableCheckpointNeverCreatesStagingAndOriginalTaskMustReconcile() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setInteractionPresenter(new Reviews());
            var source = Files.writeString(root.resolve("source"), "new");
            try (var c = runtime.persistence().database().openConnection(); var s = c.createStatement()) {
                s.execute("ALTER TABLE platform_task_resource ADD CONSTRAINT reject_sftp CHECK(resource_kind<>'FILE')");
            }
            try (var capabilities = runtime.remoteCapabilities(OWNER, VERSION)) {
                var original = new AtomicReference<TaskContext>();
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var handle = binding.submit(request("server"), task -> {
                    original.set(task);
                    try (var session = capabilities.linux().connect(SERVER, task)) {
                        var request = new TransferRequest(source, "/file", 3);
                        var grant = capabilities.interaction().approve(
                                RemoteActions.transfer(SERVER, "revision", request, digest("new".getBytes()), true),
                                task).value().orElseThrow();
                        var result = session.upload(request, grant);
                        assertEquals(Outcome.FAILED, result.outcome(), result.toString());
                        assertEquals("checkpoint", result.problem().orElseThrow().context().get("phase"));
                    }
                    return success(null);
                });
                state(handle, TaskState.UNKNOWN);
                PlatformServerReferenceTest.callbackReturned(runtime, original.get());
                try (var files = Files.list(remote.files)) {
                    assertEquals(0, files.count());
                }
                assertEquals(0, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE resource_kind='FILE'"));
                try (var c = runtime.persistence().database().openConnection(); var s = c.createStatement()) {
                    s.execute("ALTER TABLE platform_task_resource DROP CONSTRAINT reject_sftp");
                }
                try (var recovery = binding.registerRecovery("fixture", (previous, task) -> {
                    capabilities.closeTaskResources(task);
                    return success(null);
                })) {
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(handle.id()).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).state());
                }
                assertEquals(0, remote.commands.get());
            }
        }
    }

    @Test
    void exactOperationApprovalStillRequiresAnIndependentSameNameBusinessChoice() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            var choices = new AtomicInteger();
            reviews.conflictPolicy = conflict -> {
                assertEquals(3, conflict.incomingBytes());
                return switch (choices.getAndIncrement()) {
                    case 0 -> TransferConflictChoice.KEEP_BOTH;
                    case 1 -> TransferConflictChoice.OVERWRITE;
                    default -> TransferConflictChoice.TERMINATE;
                };
            };
            runtime.setInteractionPresenter(reviews);
            var source = Files.writeString(root.resolve("input.txt"), "new");
            Files.writeString(remote.files.resolve("file.txt"), "old");
            String digest = HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest("new".getBytes(StandardCharsets.UTF_8)));
            try (var capabilities = runtime.remoteCapabilities(OWNER, VERSION)) {
                var handle = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), task -> {
                    try (var session = capabilities.linux().connect(SERVER, task)) {
                        var request = new TransferRequest(source, "/file.txt", 3);
                        for (int i = 0; i < 3; i++) {
                            var grant = capabilities.interaction()
                                    .approve(RemoteActions.transfer(SERVER, "revision", request, digest, true), task)
                                    .value().orElseThrow();
                            var transferred = session.upload(request, grant);
                            assertEquals(i == 2 ? Outcome.CANCELLED : Outcome.SUCCEEDED, transferred.outcome(),
                                    transferred.toString());
                            if (i == 0)
                                assertEquals("/file (1).txt", transferred.value().orElseThrow().remoteAbsolutePath());
                        }
                    }
                    return success(null);
                });
                assertEquals(Outcome.SUCCEEDED, result(handle).outcome());
                assertEquals(3, choices.get());
                assertEquals("new", Files.readString(remote.files.resolve("file.txt")));
                assertEquals("new", Files.readString(remote.files.resolve("file (1).txt")));
                assertEquals(0, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            }
        }
    }

    @Test
    void disconnectedStagingRetainsDurableFileFactsAfterGenericTaskReconciliation() throws Exception {
        var interrupt = new AtomicBoolean(true);
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setInteractionPresenter(new Reviews());
            ((SftpSubsystemFactory) remote.listener.server().getSubsystemFactories().getFirst())
                    .addSftpEventListener(new org.apache.sshd.sftp.server.SftpEventListener() {
                        public void written(org.apache.sshd.server.session.ServerSession session, String handle,
                                org.apache.sshd.sftp.server.FileHandle file, long offset, byte[] data, int start,
                                int length, Throwable failure) {
                            if (failure == null && interrupt.compareAndSet(true, false))
                                session.close(true);
                        }
                    });
            var source = Files.write(root.resolve("source"), new byte[100000]);
            var binding = runtime.tasks().bind(OWNER, VERSION, false);
            var original = new AtomicReference<TaskContext>();
            try (var capabilities = runtime.remoteCapabilities(OWNER, VERSION)) {
                var task = binding.submit(request("server"), context -> {
                    original.set(context);
                    try (var session = capabilities.linux().connect(SERVER, context)) {
                        var request = new TransferRequest(source, "/file", 100000);
                        var grant = capabilities.interaction().approve(
                                RemoteActions.transfer(SERVER, "revision", request, digest(new byte[100000]), true),
                                context).value().orElseThrow();
                        assertEquals(Outcome.UNKNOWN, session.upload(request, grant).outcome());
                    }
                    return success(null);
                });
                state(task, TaskState.UNKNOWN);
                PlatformServerReferenceTest.callbackReturned(runtime, original.get());
                Path temporary;
                try (var c = runtime.persistence().database().openConnection();
                        var p = c.prepareStatement(
                                "SELECT lease_id,recovery_json FROM platform_task_resource WHERE task_id=? AND resource_kind='FILE' AND released_at IS NULL")) {
                    p.setObject(1, task.id());
                    try (var row = p.executeQuery()) {
                        assertTrue(row.next());
                        var facts = new com.fasterxml.jackson.databind.ObjectMapper().readTree(row.getString(2))
                                .path("fields");
                        assertEquals("server", facts.path("serverId").asText());
                        assertEquals(digest(new byte[100000]), facts.path("source.sha256").asText());
                        assertEquals("/file", facts.path("selectedRemote").asText());
                        String name = ".wtl-transfer-" + UUID.fromString(facts.path("transferId").asText()) + ".part";
                        assertEquals("/" + name, facts.path("temporaryPath").asText());
                        temporary = remote.files.resolve(name);
                        assertFalse(row.next());
                    }
                }
                assertEquals(16384, Files.size(temporary));
                assertFalse(Files.exists(remote.files.resolve("file")));
                var resume = new AtomicBoolean();
                try (var registration = binding.registerRecovery("fixture", (previous, context) -> {
                    capabilities.closeTaskResources(context);
                    if (resume.get()) {
                        var checkpoints = capabilities.linux().pendingTransfers(context);
                        assertEquals(1, checkpoints.size());
                        var checkpoint = checkpoints.getFirst();
                        try (var session = capabilities.linux().connect(SERVER, context)) {
                            var grant = capabilities.interaction()
                                    .approve(RemoteActions.recoverTransfer(checkpoint, "recovery", true), context)
                                    .value().orElseThrow();
                            assertEquals(Outcome.SUCCEEDED, session.resumeTransfer(checkpoint.id(), grant).outcome());
                            assertTrue(capabilities.linux().pendingTransfers(context).isEmpty());
                        }
                    }
                    return success(null);
                })) {
                    assertEquals(TaskState.FINISHING, runtime.tasks().reconcile(task.id()).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).state());
                    assertFalse(task.completion().toCompletableFuture().isDone());
                    assertTrue(Files.exists(temporary));
                    assertThrows(java.sql.SQLException.class, runtime::close);
                    resume.set(true);
                    assertEquals(TaskState.ENDED, runtime.tasks().reconcile(task.id()).toCompletableFuture()
                            .get(10, TimeUnit.SECONDS).state());
                }
                assertFalse(Files.exists(temporary));
                assertArrayEquals(new byte[100000], Files.readAllBytes(remote.files.resolve("file")));
            }
        }
    }

    @Test
    void declinedReviewAndForeignOrUnadmittedTaskContextsNeverDispatch() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            reviews.policy = intent -> !intent.type().equals("platform.ssh.execute");
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.remoteCapabilities(OWNER, VERSION);
                    var foreign = runtime.remoteCapabilities("other.owner", VERSION)) {
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    assertThrows(ApiException.class, () -> foreign.linux().connect(SERVER, context));
                    assertThrows(ApiException.class,
                            () -> capabilities.linux().connect(new ServerRef("missing"), context));
                    try (var session = capabilities.linux().connect(SERVER, context)) {
                        assertEquals(Outcome.CANCELLED,
                                capabilities.interaction()
                                        .approve(RemoteActions.command(SERVER, "1", command("echo refused")), context)
                                        .outcome());
                    }
                    return success(null);
                });
                assertEquals(Outcome.SUCCEEDED, result(task).outcome());
                assertEquals(0, remote.commands.get());
                assertEquals(1, remote.authentications.get());
            }
        }
    }

    @Test
    void changedHostKeyDoesNotPromptForReplacementAndFailedAuthenticationDoesNotSaveTrust() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            var reviews = new Reviews();
            runtime.setInteractionPresenter(reviews);
            try (var capabilities = runtime.remoteCapabilities(OWNER, VERSION)) {
                try (var operation = runtime.security().enterOperation();
                        var credentials = runtime.security().openCredentials()) {
                    credentials.save("credential", "wrong-password".toCharArray());
                }
                var failed = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    assertThrows(ApiException.class, () -> capabilities.linux().connect(SERVER, context));
                    return success(null);
                });
                assertEquals(Outcome.SUCCEEDED, result(failed).outcome());
                assertTrue(runtime.persistence().servers().findServer("server").isEmpty());
                runtime.persistence().servers().saveServer(
                        new ServerIdentity("server", "127.0.0.1", remote.listener.server().getPort(), "SHA256:wrong"));
                reviews.approved.clear();
                var changed = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), context -> {
                    assertThrows(ApiException.class, () -> capabilities.linux().connect(SERVER, context));
                    return success(null);
                });
                assertEquals(Outcome.SUCCEEDED, result(changed).outcome());
                assertTrue(reviews.approved.isEmpty());
                assertEquals(0, runtime.security().securityState().activeTasks());
            }
        }
    }

    @Test
    void unclosedSessionKeepsFinishingAndCannotStartMoreWorkAfterCallbackReturn() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setInteractionPresenter(new Reviews());
            try (var capabilities = runtime.remoteCapabilities(OWNER, VERSION)) {
                var retained = new AtomicReference<RemoteSession>();
                var context = new AtomicReference<TaskContext>();
                var task = runtime.tasks().bind(OWNER, VERSION, false).submit(request("server"), active -> {
                    context.set(active);
                    retained.set(capabilities.linux().connect(SERVER, active));
                    return success(null);
                });
                try {
                    state(task, TaskState.FINISHING);
                    assertFalse(task.completion().toCompletableFuture().isDone());
                    assertThrows(ApiException.class, () -> capabilities.interaction()
                            .approve(RemoteActions.command(SERVER, "1", command("echo late")), context.get()));
                    assertThrows(ApiException.class, () -> capabilities.linux().connect(SERVER, context.get()));
                    assertThrows(java.sql.SQLException.class, runtime::close);
                } finally {
                    if (retained.get() != null)
                        retained.get().close();
                }
                assertEquals(Outcome.SUCCEEDED, result(task).outcome());
                assertEquals(0, remote.commands.get());
            }
        }
    }

    @Test
    void platformUnknownOverridesAuthorSuccessAndOnlyExplicitRecoveryReleasesServer() throws Exception {
        try (var remote = new Remote(root.resolve("ssh"));
                var runtime = PlatformRuntime.open(root.resolve("db"), PlatformStartupTest.PROTECTION)) {
            remote.configure(runtime);
            runtime.setInteractionPresenter(new Reviews());
            try (var capabilities = runtime.remoteCapabilities(OWNER, VERSION)) {
                var binding = runtime.tasks().bind(OWNER, VERSION, false);
                var original = new AtomicReference<TaskContext>();
                var callbackReturned = new CountDownLatch(1);
                var task = binding.submit(request("server"), context -> {
                    original.set(context);
                    try (var session = capabilities.linux().connect(SERVER, context)) {
                        var slow = new CommandRequest(
                                "deadline=$((SECONDS+2)); while ((SECONDS < deadline)); do :; done", new byte[0],
                                Duration.ofMillis(300), 16);
                        assertEquals(Outcome.UNKNOWN,
                                session.execute(slow, approve(capabilities, slow, context)).outcome());
                        assertThrows(ApiException.class, () -> capabilities.linux().connect(SERVER, context));
                    } finally {
                        callbackReturned.countDown();
                    }
                    return success("author cannot erase platform uncertainty");
                });
                assertTrue(callbackReturned.await(10, TimeUnit.SECONDS));
                state(task, TaskState.UNKNOWN);
                assertFalse(task.completion().toCompletableFuture().isDone());
                assertEquals(1, runtime.security().securityState().activeTasks());
                try (var recovery = binding.registerRecovery("fixture", (previous, context) -> {
                    assertSame(original.get(), context);
                    capabilities.closeTaskResources(context);
                    try (var session = capabilities.linux().connect(SERVER, context)) {
                        var inspect = command("builtin printf 'reconciled'");
                        assertEquals("reconciled", session.execute(inspect, approve(capabilities, inspect, context))
                                .value().orElseThrow().stdout());
                    }
                    return success(null);
                })) {
                    CompletionStage<TaskSnapshot> reconciliation = null;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (reconciliation == null) {
                        try {
                            reconciliation = runtime.tasks().reconcile(task.id());
                        } catch (ApiException busy) {
                            if (busy.problem().code() != ProblemCode.BUSY || System.nanoTime() >= deadline)
                                throw busy;
                            Thread.sleep(5);
                        }
                    }
                    assertEquals(TaskState.ENDED,
                            reconciliation.toCompletableFuture().get(10, TimeUnit.SECONDS).state());
                }
                assertEquals(Outcome.SUCCEEDED, result(task).outcome());
                assertEquals(2, remote.commands.get());
                assertEquals(0, runtime.security().securityState().activeTasks());
            }
        }
    }
}
