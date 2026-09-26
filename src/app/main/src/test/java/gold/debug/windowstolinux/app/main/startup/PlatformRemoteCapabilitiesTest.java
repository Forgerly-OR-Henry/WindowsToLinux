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

import gold.debug.windowstolinux.api.linux.*;
import gold.debug.windowstolinux.api.server.ServerRef;
import gold.debug.windowstolinux.api.task.*;
import gold.debug.windowstolinux.app.db.entity.StoredServerProfile;
import gold.debug.windowstolinux.app.service.task.PlatformInteractionService;
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
        final List<ActionIntent> approved = new CopyOnWriteArrayList<>();

        volatile java.util.function.Predicate<ActionIntent> policy = intent -> true;
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
            Files.createDirectories(root);
            files = Files.createDirectories(root.resolve("remote"));
            var bash = Path.of(System.getProperty("managed.test.bash", "/bin/bash"));
            assertTrue(Files.isRegularFile(bash), "Local integration requires managed.test.bash");
            listener = new LoopbackSshServer(root, server -> {
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
