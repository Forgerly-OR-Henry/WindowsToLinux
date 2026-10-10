package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalConversation;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildBudget;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.ContainerEngineType;
import org.junit.jupiter.api.Test;

/** Local native review and controlled transport only; no API or SSH execution. / 仅本地原生审核与受控传输，不执行 API 或 SSH。 */
class DeploymentAgentHostChannelTest {
    private static final String REVISION = "a".repeat(64);

    private static final ServerId SERVER = new ServerId("baseline-server");

    private static final String BASELINE = "FORMAT=WTL_AGENT_BASELINE_1\nDISTRO_ID=ubuntu\nDISTRO_VARIANT=\nVERSION=24.04\nARCH=x86_64\nPACKAGE_MANAGER=apt\nPACKAGE_ARCH=amd64\nSYSTEMD=1\n";

    @Test
    void baselineContainsOnlyFixedNecessaryQueriesAndProtectedOsReader() {
        String script = DeploymentAgentHostProbe.baseline();
        assertTrue(script.startsWith("exec /usr/bin/env -i"));
        assertTrue(script.contains("/bin/bash --noprofile --norc -s"));
        assertTrue(script.contains("probe_parents / || exit 64"));
        assertTrue(script.contains("[ ! -L \"$os_release\" ] && probe_file \"$os_release\" || exit 64"));
        assertTrue(script.contains("case \"$key\" in ID | VARIANT_ID | VERSION_ID)"));
        for (String key : List.of("ID", "VARIANT_ID", "VERSION_ID"))
            assertTrue(script.contains(key + "=\"$(os_release_value " + key + " \"$os_release\")\" || exit 64"));
        assertTrue(script.contains("[ -x /usr/bin/uname ] && probe_file /usr/bin/uname || exit 64\n/usr/bin/uname -m || exit 64"));
        assertTrue(script.contains("if [ -e /usr/bin/dpkg ] || [ -L /usr/bin/dpkg ]; then"));
        assertTrue(script.contains("[ -x /usr/bin/dpkg ] && probe_file /usr/bin/dpkg || exit 64\n  /usr/bin/dpkg --print-architecture || exit 64"));
        assertTrue(script.contains("elif [ -e /usr/bin/rpm ] || [ -L /usr/bin/rpm ]; then"));
        assertTrue(script.contains("[ -x /usr/bin/rpm ] && probe_file /usr/bin/rpm || exit 64\n  /usr/bin/rpm --eval '%{_arch}' || exit 64"));
        for (String query : List.of("probe_command uname", "probe_command dpkg", "probe_command rpm"))
            assertFalse(script.contains(query));
        assertTrue(script.contains("if probe_command systemctl"));
        for (String excluded : List.of("compgen", "python", "venv", "node", "java", "npm", "mvn", "dotnet", "composer", "ruby", "--version", "--help", "ld-linux", "/proc/cpuinfo", "firewall", "nft", "ufw", "getenforce", "docker", "podman"))
            assertFalse(script.contains(excluded), excluded);
    }

    @Test
    void strictBaselineRejectsMissingDuplicateUnknownMalformedAndCutReceipts() {
        for (String invalid : List.of(BASELINE.replace("SYSTEMD=1\n", ""), BASELINE + "SYSTEMD=1\n",
            BASELINE.replace("SYSTEMD=1", "DISTRO_ID=ubuntu"), BASELINE.replace("SYSTEMD=1", "TOOL_JAVA=21"),
            BASELINE.replace("SYSTEMD=1", "SYSTEMD=unknown"), BASELINE.replace("ARCH=x86_64", "ARCH=x86 64"),
            BASELINE.substring(0, BASELINE.length() - 1), BASELINE.replace("\n", "\r\n"), BASELINE + "x".repeat(4096)))
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHostParser.baseline(invalid));
    }

    @Test
    void limitedFactsKeepUnknownCoverageAndNeverClaimCompletePlatformAdmission() {
        var observed = new DeploymentAgentHostFacts(DeploymentAgentHostParser.baseline(BASELINE), Optional.empty());
        assertEquals(Set.of("DISTRO_ID", "DISTRO_VARIANT", "VERSION", "ARCH", "PACKAGE_MANAGER", "PACKAGE_ARCH", "SYSTEMD"), observed.baseline().keySet());
        assertEquals("", observed.baseline().get("DISTRO_VARIANT"));
        var facts = observed.facts();
        var assessmentCoverage = (Map<?, ?>) facts.get("assessmentCoverage");
        assertEquals("NOT_PERFORMED_BY_THIS_OBSERVATION", assessmentCoverage.get("platform"));
        assertEquals("NOT_PERFORMED_BY_THIS_OBSERVATION", assessmentCoverage.get("runtimeBoundary"));
        assertFalse(facts.containsKey("platformAssessment"));
        assertFalse(facts.containsKey("runtimeBoundaryAssessment"));
        var coverage = (Map<?, ?>) facts.get("coverage");
        for (String key : List.of("tools", "cpu", "security", "capacity", "container"))
            assertEquals("UNKNOWN", coverage.get(key));
        assertFalse(facts.containsKey("selectedContainer"));
        assertFalse(facts.toString().contains("javaMajorVersions"));
        assertFalse(facts.toString().contains("dockerAvailable=false"));
    }

    @Test
    void ordinaryRuntimeAlwaysReceivesFreshBaselineReviewWithoutEcosystemOrContainerQueries() throws Exception {
        var controlled = new Controlled();
        var runtime = new DeploymentRuntimeDefinition.StaticSite("public", new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:8080/"), 200, 10));
        assertEquals(TaskResult.SUCCEEDED, controlled.channel.forRuntime(runtime).outcome());
        assertEquals(TaskResult.SUCCEEDED, controlled.channel.baseline().outcome());
        assertEquals(2, controlled.reviews.get());
        assertEquals(2, controlled.sends.get());
        assertEquals(List.of(DeploymentAgentHostProbe.baseline(), DeploymentAgentHostProbe.baseline()), controlled.scripts);
    }

    @Test
    void selectedContainerOnlyQueriesItsOwnEngineUnderSeparateIndependentReview() throws Exception {
        for (var engine : ContainerEngineType.values()) {
            var controlled = new Controlled();
            controlled.response = request -> reply(request.script().equals(DeploymentAgentHostProbe.baseline()) ? BASELINE : container(engine));
            var runtime = new DeploymentRuntimeDefinition.Container(engine, Map.of(8080, 8080), List.of(), new DeploymentHealthCheck.Tcp(8080, 10, 1));
            var result = controlled.channel.forRuntime(runtime);
            assertEquals(TaskResult.SUCCEEDED, result.outcome());
            assertEquals(2, controlled.reviews.get());
            assertEquals(List.of(DeploymentAgentHostProbe.baseline(), DeploymentAgentHostProbe.container(engine)), controlled.scripts);
            String script = controlled.scripts.getLast();
            assertTrue(script.contains(engine.name().toLowerCase(Locale.ROOT) + " info"));
            assertFalse(script.contains(engine == ContainerEngineType.DOCKER ? "podman" : "docker"));
            assertFalse(script.contains("compgen"));
            assertFalse(script.contains("venv"));
            var facts = result.value().orElseThrow().selectedContainer().orElseThrow();
            assertEquals(engine.name(), facts.get("engine"));
            assertTrue(facts.get("initializationImpact").contains("may initialize"));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentHostParser.container(container(engine), engine == ContainerEngineType.DOCKER ? ContainerEngineType.PODMAN : ContainerEngineType.DOCKER));
        }
    }

    @Test
    void validNativeModelDenyIsFinalAndNeverDispatches() throws Exception {
        var fixture = new NativeAiFixture();
        fixture.configurationRevision = REVISION;
        fixture.nativeReply(new AiToolCall("deny-baseline", "submit_result", "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled denial of this exact baseline\"}"));
        var host = (InteractionService) Proxy.newProxyInstance(InteractionService.class.getClassLoader(), new Class<?>[]{InteractionService.class},
            (proxy, method, args) -> {
                throw new AssertionError("Denied native review must never request a host grant");
            });
        var ui = (UiService) Proxy.newProxyInstance(UiService.class.getClassLoader(), new Class<?>[]{UiService.class},
            (proxy, method, args) -> new UiEnvironment(Locale.ENGLISH, "LIGHT", false));
        var context = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            if (method.getName().equals("ui")) return ui;
            try {
                return method.invoke(fixture.context, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        var conversation = new DeploymentApprovalConversation(context, fixture.task, SERVER, "fixture-fingerprint", REVISION, REVISION);
        var reviewed = new DeploymentCommandReviewScope(context, host, fixture.task, "FULL_CONTROL", true, () -> REVISION, () -> conversation);
        var sends = new AtomicInteger();
        var session = session(request -> {
            sends.incrementAndGet();
            return reply(BASELINE);
        });
        var channel = new DeploymentAgentHostChannel(session, reviewed.interaction(), SERVER, fixture.task, REVISION, new DeploymentBuildBudget(120), DeploymentSendGuard.NONE);
        var denied = assertThrows(ApiException.class, channel::baseline);
        assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
        assertEquals(1, fixture.requests.size());
        assertEquals(0, sends.get());
        var request = fixture.requests.getFirst();
        assertTrue(request.messages().getLast().text().contains("deployment.candidate.host-probe.v1"));
        assertTrue(request.messages().getLast().text().contains("WTL_AGENT_HOST_OBSERVATION"));
    }

    @Test
    void configurationChangedAfterReviewCannotDispatch() {
        var controlled = new Controlled();
        controlled.afterReview = () -> controlled.configurationCurrent.set(false);
        var failure = assertThrows(ApiException.class, controlled.channel::baseline);
        assertEquals("DEPLOYMENT_AGENT_CONFIGURATION_CHANGED", failure.problem().eventCode());
        assertEquals(1, controlled.reviews.get());
        assertEquals(0, controlled.sends.get());
    }

    @Test
    void cancellationBeforeOrDuringReviewCannotDispatch() throws Exception {
        var before = new Controlled();
        before.cancelled.set(true);
        assertEquals(TaskResult.CANCELLED, before.channel.baseline().outcome());
        assertEquals(0, before.reviews.get());
        assertEquals(0, before.sends.get());
        var during = new Controlled();
        during.afterReview = () -> during.cancelled.set(true);
        assertEquals(TaskResult.CANCELLED, during.channel.baseline().outcome());
        assertEquals(1, during.reviews.get());
        assertEquals(0, during.sends.get());
    }

    @Test
    void incompleteOrMalformedTransportReceiptNeverBecomesObservedFactsOrReplays() throws Exception {
        for (var response : List.of(reply(BASELINE.replace("SYSTEMD=1\n", "")),
            new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), BASELINE, "", true)), Optional.<Problem>empty()),
            new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.empty(), BASELINE, "", false)), Optional.<Problem>empty()))) {
            var controlled = new Controlled();
            controlled.response = request -> response;
            var result = controlled.channel.baseline();
            assertEquals(TaskResult.FAILED, result.outcome());
            assertTrue(result.value().isEmpty());
            assertEquals(1, controlled.reviews.get());
            assertEquals(1, controlled.sends.get());
        }
    }

    private static String container(ContainerEngineType engine) {
        return "FORMAT=WTL_AGENT_CONTAINER_1\nENGINE=" + engine.name() + "\nCLIENT=1\nOPERATIONAL=1\nQUADLET="
            + (engine == ContainerEngineType.DOCKER ? "NOT_APPLICABLE" : "1") + "\n";
    }

    private static OperationResult<CommandResult> reply(String output) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), output, "", false)), Optional.empty());
    }

    private static RemoteSession session(Function<CommandRequest, OperationResult<CommandResult>> response) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(), new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
            assertEquals("execute", method.getName());
            assertNotNull(args[1]);
            return response.apply((CommandRequest) args[0]);
        });
    }

    private static final class Controlled {
        final AtomicInteger reviews = new AtomicInteger();

        final AtomicInteger sends = new AtomicInteger();

        final AtomicBoolean cancelled = new AtomicBoolean();

        final AtomicBoolean configurationCurrent = new AtomicBoolean(true);

        final List<String> scripts = new ArrayList<>();

        Runnable afterReview = () -> {
        };

        Function<CommandRequest, OperationResult<CommandResult>> response = request -> reply(BASELINE);

        final DeploymentAgentHostChannel channel;

        Controlled() {
            UUID id = UUID.randomUUID();
            var task = (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(), new Class<?>[]{TaskContext.class}, (proxy, method, args) -> switch (method.getName()) {
                case "id" -> id;
                case "cancelToken" -> (CancelToken) cancelled::get;
                default -> throw new AssertionError(method.getName());
            });
            var review = (DeploymentCommandReview) Proxy.newProxyInstance(DeploymentCommandReview.class.getClassLoader(), new Class<?>[]{DeploymentCommandReview.class}, (proxy, method, args) -> {
                assertEquals("approveCommand", method.getName(), "The router must send the complete request for independent review");
                var intent = (gold.debug.windowstolinux.appapi.task.action.ActionIntent) args[0];
                var request = (CommandRequest) args[1];
                assertSame(task, args[2]);
                assertEquals(RemoteActions.command(SERVER, REVISION, request).exactAction(), intent.exactAction());
                assertEquals(Set.of(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(SERVER.id(), "deployment-host", SERVER.id())), intent.semantics().orElseThrow().targets());
                assertEquals(4096, request.outputLimitBytes());
                assertArrayEquals(new byte[0], request.stdin());
                reviews.incrementAndGet();
                afterReview.run();
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())), Optional.empty());
            });
            channel = new DeploymentAgentHostChannel(session(request -> {
                sends.incrementAndGet();
                scripts.add(request.script());
                return response.apply(request);
            }), review, SERVER, task, REVISION, new DeploymentBuildBudget(7200), () -> {
                if (!configurationCurrent.get()) throw new ApiException(new Problem(ProblemCode.ACCESS_DENIED,
                    "DEPLOYMENT_AGENT_CONFIGURATION_CHANGED", "Original configuration changed", Map.of(), Optional.empty()));
            });
        }
    }
}
