package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainInputEvidence;
import gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainPrograms;
import org.junit.jupiter.api.Test;

class DeploymentAgentToolchainConversationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ServerId SERVER = new ServerId("original-server");

    private static final String REVISION = "c".repeat(64);

    private static final String OPERATION = "a".repeat(32);

    private static final String FIELD = DeploymentAgentToolchainInputEvidence.FIELD;

    @Test
    void realApprovalGatewaySubmitsCompleteCurrentProgramsAndKeepsEachDenialFinal() throws Exception {
        var fixture = new NativeAiFixture();
        var accesses = new AtomicInteger();
        var host = (InteractionService) Proxy.newProxyInstance(InteractionService.class.getClassLoader(),
            new Class<?>[]{InteractionService.class}, (proxy, method, args) -> {
                accesses.incrementAndGet();
                throw new AssertionError("Denied command cannot obtain a host grant");
            });
        var ui = (UiService) Proxy.newProxyInstance(UiService.class.getClassLoader(), new Class<?>[]{UiService.class},
            (proxy, method, args) -> {
                if (method.getName().equals("environment")) return new UiEnvironment(Locale.ENGLISH, "LIGHT", false);
                throw new AssertionError(method.getName());
            });
        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("ui")) return ui;
                if (method.getName().equals("linux") || method.getName().equals("interaction")) {
                    accesses.incrementAndGet();
                    throw new AssertionError("Denied command cannot obtain an execution capability");
                }
                return method.invoke(fixture.context, args);
            });
        var review = conversation(context, fixture);
        var ai = new DeploymentAiJson(context, review);
        String prefix = null;
        for (String phase : List.of("prepare", "dependencies", "resolve")) {
            boolean dependencies = phase.equals("dependencies");
            fixture.nativeReply(new AiToolCall("deny-" + phase, "submit_result",
                "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Current enforced boundary is insufficient\"}"));
            try (var command = command(phase)) {
                var intent = intent(phase, command);
                var denied = assertThrows(ApiException.class, () -> DeploymentFixedCommandApproval.approve(context,
                    host, intent, command, fixture.task, "fixture-route", "AUTOMATIC", false, ai));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
                var request = fixture.requests.getLast();
                var current = JSON.readTree(request.messages().getLast().text());
                assertEquals(dependencies ? DeploymentAgentToolchainPrograms.dependencies() : phase.equals("resolve") ? DeploymentAgentToolchainPrograms.resolver() : DeploymentAgentToolchainPrograms.controller(),
                    current.path("completeExecutableInput").asText());
                assertEquals(intent.exactAction(), current.path("exactAction").asText());
                assertEquals(REVISION, current.path("businessRevision").asText());
                assertEquals("fixture-route", current.at("/reviewSession/configurationRevision").asText());
                assertTrue(current.path(FIELD).path("inputMatchesRenderer").asBoolean());
                assertEquals(dependencies ? "NATIVE_BUILD" : "RUST", current.path(FIELD).path("profile").asText());
                assertEquals(intent.semantics().orElseThrow().targets().size(), current.path("resourceTargets").size());
                assertFalse(current.has("installedHelperPath"));
                assertFalse(current.path(FIELD).has("approved"));
                if (prefix == null) prefix = request.messages().getFirst().text();
                else
                    assertEquals(prefix, request.messages().getFirst().text(), "Stable Agent policy does not change with the operation");
                assertTrue(prefix.contains("OS package maintenance effects are root effects"));
                assertTrue(prefix.contains("native resolver's authenticated dependency closure"));
                assertTrue(prefix.contains("not the non-root vendor worker requirement"));
                assertTrue(prefix.contains("The transaction target grants no permission by itself"));
                assertEquals(dependencies ? 2 : 0, intent.semantics().orElseThrow().targets().stream()
                    .filter(target -> target.kind().equals("system-package-transaction")).count());
                assertTrue(prefix.contains("Every stage still needs a fresh independent verdict"));
            }
        }
        assertEquals(3, fixture.requests.size(), "Each valid DENY consumes exactly one fresh review");
        assertEquals(0, accesses.get());
        assertTrue(fixture.replies.isEmpty());
    }

    @Test
    void transcriptRejectsChangedCurrentBytesTargetsMetadataAndOriginalSession() throws Exception {
        var fixture = new NativeAiFixture();
        var conversation = conversation(fixture.context, fixture);
        Map<String, Object> facts;
        try (var command = command(false)) {
            facts = DeploymentCommandEvidence.facts(intent(false, command), command);
        }
        var supplied = new LinkedHashMap<>(facts);
        supplied.put(FIELD, Map.of("inputMatchesRenderer", true));
        assertThrows(IllegalArgumentException.class, () -> conversation.facts("Current command", supplied, "fixture-route", fixture.task));
        ObjectNode current = JSON.valueToTree(conversation.facts("Current command", facts, "fixture-route", fixture.task));
        assertDoesNotThrow(() -> conversation.messages(current.toString(), "fixture-route", fixture.task));
        for (String alteration : List.of("source", "argv", "targets", "metadata", "session", "phase")) {
            var changed = current.deepCopy();
            switch (alteration) {
                case "source" ->
                    changed.put("completeExecutableInput", current.path("completeExecutableInput").asText() + "\n# changed");
                case "argv" ->
                    changed.put("exactAction", current.path("exactAction").asText().replace(" -I -S ", " -I "));
                case "targets" -> changed.withArray("resourceTargets").remove(0);
                case "metadata" -> ((ObjectNode) changed.get(FIELD)).put("inputMatchesRenderer", false);
                case "session" -> ((ObjectNode) changed.get("reviewSession")).put("task", UUID.randomUUID().toString());
                case "phase" -> changed.put("operation", "deployment.agent.toolchain.dependencies.v1");
                default -> throw new AssertionError(alteration);
            }
            assertThrows(IllegalArgumentException.class, () -> conversation.messages(changed.toString(), "fixture-route", fixture.task), alteration);
        }
        var foreign = new NativeAiFixture();
        assertThrows(Exception.class, () -> conversation.messages(current.toString(), "fixture-route", foreign.task));
        fixture.configurationRevision = "changed-route";
        assertThrows(Exception.class, () -> conversation.messages(current.toString(), "fixture-route", fixture.task));
        assertTrue(fixture.requests.isEmpty(), "Consistency failures stop before any model request");
    }

    private static DeploymentApprovalConversation conversation(ModContext context, NativeAiFixture fixture) {
        return new DeploymentApprovalConversation(context, fixture.task, SERVER, "original-fingerprint", "b".repeat(64), "fixture-route");
    }

    private static CommandRequest command(boolean dependencies) {
        return command(dependencies ? "dependencies" : "prepare");
    }

    private static CommandRequest command(String phase) {
        var arguments = phase.equals("dependencies") ? List.of("NATIVE_BUILD", OPERATION)
            : phase.equals("resolve") ? List.of("resolve", "RUST", "1.95", "1.95.0", "60", OPERATION)
            : List.of("prepare", "RUST", "1.95", "1.95.0", "60", "-", OPERATION,
            gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainReleaseTest.encoded("RUST", "1.95", "1.95.0", OPERATION));
        String source = phase.equals("dependencies") ? DeploymentAgentToolchainPrograms.dependencies()
            : phase.equals("resolve") ? DeploymentAgentToolchainPrograms.resolver() : DeploymentAgentToolchainPrograms.controller();
        return new CommandRequest(DeploymentAgentToolchainPrograms.command(arguments), source.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(75), 65536);
    }

    private static ActionIntent intent(boolean dependencies, CommandRequest command) {
        return intent(dependencies ? "dependencies" : "prepare", command);
    }

    private static ActionIntent intent(String phase, CommandRequest command) {
        var targets = new HashSet<ActionTarget>();
        if (phase.equals("dependencies")) {
            for (String manager : List.of("APT", "DNF")) {
                targets.add(new ActionTarget(SERVER.id(), "system-package-index", manager + ":configured-authenticated-sources"));
                targets.add(new ActionTarget(SERVER.id(), "system-package-transaction", manager + ":authenticated-dependency-closure:NATIVE_BUILD"));
                for (String name : DeploymentAgentToolchainPrograms.packages(manager, "NATIVE_BUILD"))
                    targets.add(new ActionTarget(SERVER.id(), "system-package", name));
            }
            targets.add(new ActionTarget(SERVER.id(), "system-repository", "DNF:centos:crb:transaction-only"));
        } else if (phase.equals("resolve")) {
            targets.add(new ActionTarget(SERVER.id(), "deployment-host", SERVER.id()));
            targets.add(new ActionTarget(SERVER.id(), "toolchain-source", "RUST:1.95:1.95.0"));
        } else {
            String root = "/usr/local/lib/windowstolinux/toolchains";
            for (String path : List.of(root, root + "/versions", root + "/bindings", root + "/work/agent-" + OPERATION, root + "/.prepare.lock"))
                targets.add(new ActionTarget(SERVER.id(), "deployment-build", path));
            targets.add(new ActionTarget(SERVER.id(), "systemd-unit", "windowstolinux-toolchain-" + OPERATION + ".service"));
        }
        return RemoteActions.command(SERVER, REVISION, command).withSemantics(new ActionSemantics(
            "deployment.agent.toolchain." + phase + ".v1", Set.copyOf(targets)));
    }
}
