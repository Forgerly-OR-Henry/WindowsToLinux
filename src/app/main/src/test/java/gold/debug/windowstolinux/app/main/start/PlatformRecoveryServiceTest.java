package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformTaskAdmissionIntegrationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.microsoft.playwright.BrowserContext;
import gold.debug.windowstolinux.app.ai.model.AiCapabilityType;
import gold.debug.windowstolinux.app.ai.model.AiPurposeType;
import gold.debug.windowstolinux.app.linux.ssh.recover.BrowserRecoverySnapshot;
import gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery;
import gold.debug.windowstolinux.app.linux.ssh.recover.SshRecoverySession;
import gold.debug.windowstolinux.app.linux.ssh.recover.browser.BrowserProcess;
import gold.debug.windowstolinux.app.linux.ssh.recover.browser.PlaywrightTerminalSession;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlatformRecoveryServiceTest {
    static SshRecovery.Server selection(ServerProfile profile) {
        return new SshRecovery.Server(profile.id(), profile.host(), profile.sshPort(), profile.username(),
            profile.credentialKey(), profile.displayName());
    }

    @TempDir
    Path root;

    static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.call() && System.nanoTime() < deadline)
            Thread.sleep(15);
        assertTrue(condition.call(), "Expected rescue state did not arrive");
    }

    static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    static PlaywrightTerminalSession browser(SshRecoverySession session) throws Exception {
        return (PlaywrightTerminalSession) field(field(session, "browser"), "browser");
    }

    static TaskSnapshot task(PlatformRuntime runtime, SshRecoverySession session) throws Exception {
        return ((TaskHandle<?>) field(session, "handle")).snapshot();
    }

    static com.fasterxml.jackson.databind.JsonNode summary(PlatformRuntime runtime, UUID id) throws Exception {
        try (var c = runtime.persistence().database().openConnection();
             var p = c.prepareStatement(
                 "SELECT details FROM runtime_log WHERE category='browser_recovery' AND task_id=?")) {
            p.setObject(1, id);
            try (var r = p.executeQuery()) {
                assertTrue(r.next());
                var details = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.getString(1));
                assertFalse(r.next());
                return details;
            }
        }
    }

    static ServerProfile save(PlatformRuntime runtime, int port) throws Exception {
        PlatformServerServiceTest.setup(runtime);
        runtime.started();
        var profile = PlatformServerServiceTest.profile("server", port);
        runtime.servers().saveServerProfile(profile, "fixture-secret".toCharArray());
        return profile;
    }

    static int vacantPort() throws Exception {
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void defaultRuntimeUsesActualHostTaskTrustAuthenticationAndFixedReadOnlyExecution() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var profile = save(runtime, remote.listener.server().getPort());
            var session = runtime.recovery().start(new gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery.Server(profile.id(), profile.host(), profile.sshPort(), profile.username(), profile.credentialKey(), profile.displayName()));
            await(() -> task(runtime, session).state() == TaskState.ENDED);
            assertEquals(BrowserRecoverySnapshot.State.RECOVERED, session.snapshot().state());
            assertEquals(Optional.of(TaskResult.SUCCEEDED), task(runtime, session).outcome());
            assertEquals("@platform", task(runtime, session).ownerId());
            assertEquals(runtime.version(), task(runtime, session).ownerVersion());
            assertEquals(1, remote.authentications.get());
            assertEquals(1, remote.commands.get());
            assertTrue(browser(session).processes().isEmpty());
            assertTrue(browser(session).resourcesClosed());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM mod_registry"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM browser_recovery_task"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM browser_recovery_event"));
            var archived = summary(runtime, UUID.fromString(session.snapshot().id()));
            assertEquals(SshRecovery.TASK_TYPE, archived.path("protocol").asText());
            assertEquals("RECOVERED", archived.path("lastObservedState").asText());
            var remoteCapability = runtime.capabilities().remoteCapabilities("@platform", runtime.version());
            var linux = (gold.debug.windowstolinux.app.service.linux.PlatformLinuxService) remoteCapability.linux();
            var wrongType = runtime.tasks().bind("@platform", runtime.version(), false).submit(request("server"),
                context -> {
                    assertEquals("RESCUE_TASK_REQUIRED", assertThrows(ApiException.class,
                        () -> linux.verifyRecovery(
                            new gold.debug.windowstolinux.appapi.server.model.ServerId("server"), context))
                        .problem().eventCode());
                    return success(null);
                });
            assertEquals(TaskResult.SUCCEEDED,
                wrongType.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).outcome());
            assertEquals(1, remote.commands.get());
        }
    }

    @Test
    void summaryFailureRetriesOnlyOriginalMetadataWithoutRepeatingSuccessfulSshProbe() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var profile = save(runtime, remote.listener.server().getPort());
            sql(runtime.persistence().database(),
                "ALTER TABLE runtime_log ADD CONSTRAINT rescue_archive_fixture CHECK(category<>'browser_recovery')");
            var session = runtime.recovery().start(new gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery.Server(profile.id(), profile.host(), profile.sshPort(), profile.username(), profile.credentialKey(), profile.displayName()));
            await(() -> task(runtime, session).state() == TaskState.FINISHING);
            var id = UUID.fromString(session.snapshot().id());
            assertEquals(1, remote.commands.get());
            assertTrue(browser(session).resourcesClosed());
            assertEquals(1, runtime.recovery().pending().size());
            assertEquals(1, count(runtime.persistence().database(), "SELECT COUNT(*) FROM browser_recovery_task"));
            assertTrue(count(runtime.persistence().database(), "SELECT COUNT(*) FROM browser_recovery_event") > 0);
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM runtime_log WHERE task_id='"
                + id + "' AND category IN ('platform_task','browser_recovery')"));
            assertThrows(java.sql.SQLException.class,
                () -> runtime.persistence().recovery().savePlatform(id, profile.id(), "RECOVERED", "", "late"));
            sql(runtime.persistence().database(), "ALTER TABLE runtime_log DROP CONSTRAINT rescue_archive_fixture");
            var ended = runtime.recovery().reconcile(id, true).toCompletableFuture().get(8, TimeUnit.SECONDS);
            assertEquals(Optional.of(TaskResult.SUCCEEDED), ended.outcome());
            assertEquals(1, remote.commands.get());
            assertEquals(1, remote.authentications.get());
            assertEquals("RECOVERED", summary(runtime, id).path("lastObservedState").asText());
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM browser_recovery_event"));
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task"));
        }
    }

    @Test
    void authenticationRejectionStopsWithoutBrowserOrRepeatedChecks() throws Exception {
        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(root.resolve("ssh"));
             var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var profile = save(runtime, remote.listener.server().getPort());
            runtime.servers().saveServerProfile(profile, "wrong-password".toCharArray());
            var session = runtime.recovery().start(new gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery.Server(profile.id(), profile.host(), profile.sshPort(), profile.username(), profile.credentialKey(), profile.displayName()));
            await(() -> task(runtime, session).state() == TaskState.ENDED);
            assertEquals(BrowserRecoverySnapshot.State.FAILED, session.snapshot().state());
            assertEquals("authentication", session.snapshot().messageCode());
            assertEquals("SSH_AUTHENTICATION_FAILED", session.snapshot().failure().orElseThrow().eventCode());
            var completed = ((TaskHandle<?>) field(session, "handle")).completion().toCompletableFuture().get(5,
                TimeUnit.SECONDS);
            assertEquals(session.snapshot().failure(), completed.problem());
            assertTrue(remote.authentications.get() > 0);
            assertEquals(0, count(runtime.persistence().database(), "SELECT COUNT(*) FROM platform_task_resource"));
            assertEquals(1, count(runtime.persistence().database(),
                "SELECT COUNT(*) FROM runtime_log WHERE category='platform_task' AND event_code='PLATFORM_TASK_FAILED'"));
            assertEquals(0, remote.commands.get());
            assertTrue(browser(session).processes().isEmpty());
        }
    }

    @Test
    void actualBrowserHoldsServerAcrossLockAndCancellationClosesOriginalResources() throws Exception {
        try (var runtime = PlatformRuntime.open(root.resolve("data"), PlatformStartupTest.PROTECTION)) {
            var profile = save(runtime, vacantPort());
            var service = runtime.recovery();
            var session = service.start(selection(profile));
            try {
                await(() -> session.snapshot().state() == BrowserRecoverySnapshot.State.WAITING_TERMINAL);
                assertSame(session, service.start(selection(profile)));
                assertEquals(1, service.pending().size());
                assertTrue(browser(session).processes().getFirst().alive());
                assertThrows(java.sql.SQLException.class, () -> runtime.servers().delete(profile.id()));
                runtime.security().lock();
                assertThrows(RuntimeException.class, session::snapshot);
                assertTrue(browser(session).processes().getFirst().alive());
                runtime.security().unlock(PASSWORD.toCharArray());
                session.close();
                await(() -> task(runtime, session).state() == TaskState.ENDED);
                var observed = session.snapshot();
                assertEquals(Optional.of(TaskResult.CANCELLED), task(runtime, session).outcome(),
                    "Rescue state=" + observed.state() + ", event=" + observed.messageCode() + ", problem="
                        + observed.failure().map(problem -> problem.code() + "/" + problem.eventCode()).orElse("none"));
                assertTrue(browser(session).processes().stream().noneMatch(BrowserProcess::alive));
                assertEquals(0, count(runtime.persistence().database(),
                    "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
            } finally {
                if (!runtime.security().securityState().unlocked())
                    runtime.security().unlock(PASSWORD.toCharArray());
                session.close();
                await(() -> task(runtime, session).state() == TaskState.ENDED);
            }
        }
    }

    @Test
    void realModelAndTerminalRequireExactApprovalAndJournalBeforeSingleSubmission() throws Exception {
        for (boolean journalFailure : List.of(false, true)) {
            var directory = root.resolve("flow-" + journalFailure);
            int port = vacantPort();
            try (var model = new PlatformAiServiceTest.Fixture();
                 var runtime = PlatformRuntime.open(directory.resolve("data"), PlatformStartupTest.PROTECTION)) {
                var profile = save(runtime, port);
                PlatformAiServiceTest.model(runtime, model, "rescue", "/chat", AiCapabilityType.TEXT, 256);
                PlatformAiServiceTest.route(runtime, AiPurposeType.DEPLOYMENT, "rescue");
                String reply = PlatformRecoveryModelsTest.response(
                    "{\"command\":\"printf fixture\",\"reason\":\"local fixture\",\"expected\":\"read-only marker\",\"highImpact\":false}");
                model.handler.set(exchange -> PlatformAiServiceTest.Fixture.reply(exchange, 200, reply));
                var session = runtime.recovery().start(new gold.debug.windowstolinux.app.linux.ssh.recover.SshRecovery.Server(profile.id(), profile.host(), profile.sshPort(), profile.username(), profile.credentialKey(), profile.displayName()));
                var submitted = new AtomicInteger();
                try {
                    await(() -> session.snapshot().state() == BrowserRecoverySnapshot.State.WAITING_TERMINAL);
                    var actual = browser(session);
                    ((ExecutorService) field(actual, "worker")).submit(() -> {
                        var context = (BrowserContext) field(actual, "context");
                        context.exposeFunction("fixtureSubmitted", args -> {
                            submitted.incrementAndGet();
                            return null;
                        });
                        context.pages().getFirst().setContent(
                            """
                                <pre data-wtl-terminal tabindex="0" style="width:640px;height:180px">Local console $</pre>
                                <script>let line='';const terminal=document.querySelector('pre');
                                terminal.addEventListener('keydown', e => {if(e.key==='Enter') {
                                  const marker=line.match(/WTL_DONE_[a-f0-9]+/)[0];terminal.textContent=marker+':0';
                                  line='';window.fixtureSubmitted();
                                } else if(e.key.length===1) line+=e.key;});</script>
                                """);
                        return null;
                    }).get(5, TimeUnit.SECONDS);
                    var target = session.terminals().getFirst();
                    session.bindTerminal(target.id(), profile.id(), true, false);
                    await(() -> session.snapshot().state() == BrowserRecoverySnapshot.State.AWAITING_CONFIRMATION);
                    assertEquals(0, submitted.get());
                    assertEquals(1, model.calls.get());
                    assertThrows(IllegalArgumentException.class,
                        () -> session.bindTerminal(target.id(), "wrong-server", true, false));
                    if (journalFailure)
                        sql(runtime.persistence().database(),
                            "ALTER TABLE browser_recovery_event ADD CONSTRAINT fixture_submission CHECK(event_code<>'submitted')");
                    assertTrue(session.snapshot().action().orElseThrow().highImpact());
                    session.confirmAction(session.snapshot().confirmation(), false);
                    Thread.sleep(400);
                    assertEquals(0, submitted.get(), "Separate impact approval is required");
                    session.confirmAction(session.snapshot().confirmation(), true);
                    if (journalFailure) {
                        await(() -> task(runtime, session).state() == TaskState.UNKNOWN && actual.resourcesClosed());
                        assertEquals(0, submitted.get());
                        assertEquals("journalUnavailable", session.snapshot().messageCode());
                        var id = UUID.fromString(session.snapshot().id());
                        assertThrows(ApiException.class, () -> runtime.recovery().reconcile(id, false));
                        sql(runtime.persistence().database(),
                            "ALTER TABLE browser_recovery_event DROP CONSTRAINT fixture_submission");
                        assertEquals(TaskState.ENDED, runtime.recovery().reconcile(id, true).toCompletableFuture()
                            .get(5, TimeUnit.SECONDS).state());
                    } else {
                        await(() -> submitted.get() == 1);
                        try (var remote = new PlatformRemoteCapabilitiesTest.Remote(directory.resolve("ssh"), port)) {
                            await(() -> task(runtime, session).state() == TaskState.ENDED);
                            assertEquals(BrowserRecoverySnapshot.State.RECOVERED, session.snapshot().state());
                            assertFalse(session.snapshot().resultUnknown());
                            assertEquals(1, remote.commands.get());
                            assertEquals(1, submitted.get());
                            assertEquals(1, model.calls.get());
                        }
                        assertEquals(1, summary(runtime, UUID.fromString(session.snapshot().id()))
                            .path("submittedCount").asInt());
                    }
                    assertEquals(0,
                        count(runtime.persistence().database(), "SELECT COUNT(*) FROM browser_recovery_event"));
                    assertEquals(0,
                        count(runtime.persistence().database(), "SELECT COUNT(*) FROM browser_recovery_task"));
                    assertTrue(actual.processes().stream().noneMatch(BrowserProcess::alive));
                    assertEquals(0, count(runtime.persistence().database(),
                        "SELECT COUNT(*) FROM platform_task_resource WHERE released_at IS NULL"));
                } finally {
                    sql(runtime.persistence().database(),
                        "ALTER TABLE browser_recovery_event DROP CONSTRAINT IF EXISTS fixture_submission");
                    session.close();
                    await(() -> task(runtime, session).state() == TaskState.ENDED
                        || task(runtime, session).state() == TaskState.UNKNOWN);
                    if (task(runtime, session).state() == TaskState.UNKNOWN) {
                        await(() -> browser(session).resourcesClosed());
                        runtime.recovery().reconcile(UUID.fromString(session.snapshot().id()), true)
                            .toCompletableFuture().get(5, TimeUnit.SECONDS);
                    }
                }
            }
        }
    }
}
