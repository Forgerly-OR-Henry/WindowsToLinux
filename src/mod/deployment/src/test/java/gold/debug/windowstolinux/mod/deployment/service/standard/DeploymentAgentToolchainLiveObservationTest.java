package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.json.JsonMapper;
import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.model.TaskRequest;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in product file observations; no command, mutation, recovery or model call. */
@EnabledIfSystemProperty(named = "managed.runtime.staticLive", matches = "true")
class DeploymentAgentToolchainLiveObservationTest extends DeploymentStaticLiveAcceptanceTest {
    @Test
    void observesSelectedSlotAndOriginalOperationFilesThroughProductQueries() throws Exception {
        String[] operations = System.getProperty("managed.live.observedToolchainOperations", "").split(",");
        assertTrue(operations.length >= 1 && operations.length <= 4);
        for (String operation : operations) assertTrue(operation.matches("[a-f0-9]{32}"));
        String selected = System.getProperty("managed.live.observedToolchainDirectory", "");
        assertTrue(selected.matches("/usr/local/lib/windowstolinux/toolchains/versions/(java|node|python|dotnet|kotlin|go|rust|php|ruby)-[a-f0-9]{64}"));
        var server = new ServerId("static-live");
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            String password = System.getenv("WINDOWSTOLINUX_TEST_SSH_PASSWORD");
            assertNotNull(password);
            var profile = new ServerProfile(server.id(), System.getProperty("managed.ssh.host"),
                Integer.getInteger("managed.ssh.port", 22), System.getProperty("managed.ssh.user", "root"), "static-live-secret");
            char[] secret = password.toCharArray();
            try {
                runtime.servers().saveServerProfile(profile, secret);
            } finally {
                Arrays.fill(secret, '\0');
            }
            assertEquals(TaskResult.SUCCEEDED, runtime.servers().check(profile).completion().toCompletableFuture().get(90, TimeUnit.SECONDS).outcome());
            var context = runtime.modContexts().get(OWNER).context();
            var handle = context.tasks().submit(new TaskRequest("agent-toolchain-file-observation", "Read-only toolchain file evidence", List.of(server.id()), false), task -> {
                var queries = context.file().queries();
                var json = JsonMapper.builder().enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
                for (String operation : operations) {
                    var base = FilePath.remote(server, "/usr/local/lib/windowstolinux/toolchains/work/agent-" + operation);
                    var entries = queries.list(base, 32, task);
                    var names = entries.stream().map(entry -> entry.requested().path().substring(base.path().length() + 1)).sorted().toList();
                    System.out.println("AGENT_TOOLCHAIN_FILE_OBSERVATION operation=" + operation + " entries=" + names);
                    if (Boolean.getBoolean("managed.live.observedToolchainLifecycle")) {
                        var identity = json.readTree(queries.readFile(base.child("identity.json"), 4096, task));
                        assertEquals(operation, identity.path("operation").asText());
                        assertTrue(identity.path("programSha256").asText().matches("[a-f0-9]{64}"));
                        for (String key : List.of("uid", "gid"))
                            assertTrue(identity.path(key).isIntegralNumber() && identity.path(key).longValue() >= 0 && identity.path(key).longValue() < 4294967295L);
                        boolean assigned = identity.path("uid").longValue() > 0 && identity.path("gid").longValue() > 0;
                        byte[] ready = queries.readFile(base.child("ready.json"), 1024, task);
                        if (ready.length > 0) {
                            var value = json.readTree(ready);
                            assertEquals(operation, value.path("operation").asText());
                            assertEquals(identity.path("programSha256"), value.path("programSha256"));
                            assertEquals(identity.path("uid"), value.path("uid"));
                            assertEquals(identity.path("gid"), value.path("gid"));
                            assertTrue(assigned && value.path("invocationId").asText().matches("[a-f0-9]{32}"));
                        }
                        System.out.println("AGENT_TOOLCHAIN_LIFECYCLE_OBSERVATION operation=" + operation + " identityAssigned=" + assigned
                            + " ready=" + (ready.length == 0 ? "EMPTY" : "BOUND") + " startIntent=" + names.contains("start-intent.json"));
                    }
                    if (names.contains("placeholder.json")) {
                        var data = json.readTree(queries.readFile(base.child("placeholder.json"), 4096, task));
                        assertEquals(operation, data.path("operation").asText());
                        assertEquals(selected, data.path("directory").asText());
                        assertTrue(data.path("identity").isArray() && data.path("identity").size() == 2);
                        for (var number : data.path("identity"))
                            assertTrue(number.isIntegralNumber() && number.longValue() > 0);
                        System.out.println("AGENT_TOOLCHAIN_PLACEHOLDER operation=" + operation + " selectedSlotMatches=true reportedIdentity=" + data.path("identity"));
                    }
                }
                var slot = FilePath.remote(server, selected);
                var info = queries.getFile(slot, task);
                assertEquals(slot, info.actual());
                assertTrue(info.links().isEmpty());
                var entries = queries.list(slot, 32, task);
                var names = entries.stream().map(entry -> entry.requested().path().substring(slot.path().length() + 1)).sorted().toList();
                System.out.println("AGENT_TOOLCHAIN_SLOT_OBSERVATION directory=" + selected + " kind=" + info.kind() + " entries=" + names);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.empty(), Optional.empty());
            });
            assertEquals(TaskResult.SUCCEEDED, handle.completion().toCompletableFuture().get(2, TimeUnit.MINUTES).outcome());
        }
    }
}
