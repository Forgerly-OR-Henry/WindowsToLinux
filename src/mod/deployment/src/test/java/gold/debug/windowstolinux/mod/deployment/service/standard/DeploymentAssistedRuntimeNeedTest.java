package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.file.snapshot.FileSnapshot;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentWorkflowAuthorization;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.file.*;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentCommandReview;
import gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainDependencies;
import gold.debug.windowstolinux.mod.deployment.service.standard.assisted.DeploymentAssistedScope;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.plan.ReviewedPlanIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.*;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.*;
import gold.debug.windowstolinux.mod.deployment.linux.build.*;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAssistedRuntimeNeedTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"APPROVE", "DENY", "PLAN", "SOURCE", "ARCHIVE", "LAYOUT", "TASK", "SERVER", "REVISION", "ROUTE",
        "NO_PLAN", "NO_RECEIPT", "UNBOUND_OBSERVER", "OBSERVER_PLAN", "OBSERVER_SAME_TOOLS", "OBSERVER_SOURCE", "VERSION", "DIRECTORY", "ORIGIN", "SHA", "REQUIREMENT",
        "CATALOG", "MISSING", "EXTRA", "CANONICAL", "STDIN", "SIBLING_SAME_TOOLS", "POST_PLAN", "POST_SAME_PLAN", "POST_ROUTE"})
    void componentBindingsRequireTheirCurrentPlanAndExactPreparationReceipts(String mutation) throws Exception {
        Files.createDirectories(directory.resolve("frontend")); Files.createDirectories(directory.resolve("backend"));
        Files.writeString(directory.resolve("frontend/package.json"), "{\"engines\":{\"node\":\">=24 <25\"},\"scripts\":{\"build\":\"vite build\"}}");
        Files.writeString(directory.resolve("backend/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>backend</artifactId><version>1</version><properties><java.version>21</java.version></properties></project>");
        var sourceId = new AtomicReference<>(UUID.randomUUID()); var archiveId = new AtomicReference<>(UUID.randomUUID());
        var source = snapshot(sourceId, "a".repeat(64));
        var material = new DeploymentSourceMaterial("task-board", source, List.of("backend/pom.xml", "frontend/package.json"), List.of());
        var frontArchive = new DeploymentSourceArchive(snapshot(archiveId, "c".repeat(64)), "b".repeat(64), 1, 1, sourceId.get(), source.sha256(), "frontend");
        var backArchive = new DeploymentSourceArchive(snapshot(new AtomicReference<>(UUID.randomUUID()), "e".repeat(64)), "d".repeat(64), 1, 1, sourceId.get(), source.sha256(), "backend");
        var fixture = new NativeAiFixture(); var server = new ServerId("fixture"); var layout = DeploymentRemoteLayout.original();
        var frontend = DeploymentAssistedApprovalBudgetLiveTest.staticSitePlan(material, frontArchive, "wtl-task-frontend", layout, server);
        var sibling = DeploymentAssistedApprovalBudgetLiveTest.staticSitePlan(material, frontArchive, "wtl-other-frontend", layout, server);
        var backend = javaComponentPlan(material, backArchive, server);
        var scopeRef = new AtomicReference<DeploymentAssistedScope>();
        var freshGrant = new AtomicReference<ApprovalGrant>();
        var exactIntent = new AtomicReference<ActionIntent>(); var exactRequest = new AtomicReference<CommandRequest>();
        var frontendPhase = new AtomicBoolean(); var grants = new AtomicInteger(); var binds = new AtomicInteger();
        var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class}, (p,m,a) -> {
            if (m.getName().equals("openReviewedWorkflow")) return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> {}), Optional.empty());
            assertEquals("approveReviewedAction", m.getName()); assertSame(exactIntent.get(), a[0]);
            assertSame(exactRequest.get(), ((Optional<?>) a[1]).orElseThrow()); assertSame(fixture.task, a[4]); grants.incrementAndGet();
            if (frontendPhase.get() && mutation.equals("POST_PLAN")) scopeRef.get().bindPreparation(backend, fixture.task);
            if (frontendPhase.get() && mutation.equals("POST_SAME_PLAN")) scopeRef.get().bindPreparation(new ReviewedDeploymentPlan(frontend.request(), frontend.steps()), fixture.task);
            if (frontendPhase.get() && mutation.equals("POST_ROUTE")) fixture.configurationRevision = "changed";
            freshGrant.set(new ApprovalGrant(UUID.randomUUID()));
            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(freshGrant.get()), Optional.empty());
        });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (p,m,a) -> {
            if (m.getName().equals("interaction")) return host;
            if (m.getName().equals("ui")) return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class}, (q,n,b) -> {
                assertEquals("environment", n.getName()); return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(Locale.ENGLISH, "LIGHT", false);
            });
            try { return m.invoke(fixture.context, a); } catch (InvocationTargetException e) { throw e.getCause(); }
        });
        var scope = new DeploymentAssistedScope(context, fixture.task, fixture.configurationRevision, "AUTOMATIC", true); scopeRef.set(scope);
        try (var permission = DeploymentWorkflowAuthorization.open(context, fixture.task, server, fixture.configurationRevision, "AUTOMATIC", true, "Deploy original Java backend and static frontend")) {
            scope.workflowOpened(permission); scope.sourceCaptured(server, material, fixture.task);
            scope.archiveCaptured(material, backArchive, backend.request().facts().toolchainRequirements(), fixture.task);
            scope.archiveCaptured(material, frontArchive, frontend.request().facts().toolchainRequirements(), fixture.task);
            class Flow {
                OperationResult<DeploymentResolvedToolchains> run(ReviewedDeploymentPlan plan, boolean front) throws Exception {
                    frontendPhase.set(front); scope.bindPreparation(plan, fixture.task);
                    var boundObserver = scope.preparationObserver(plan, fixture.task);
                    var tools = preparedTools(plan);
                    var review = (DeploymentCommandReview) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DeploymentCommandReview.class}, (p,m,a) -> {
                        assertEquals("approveCommand", m.getName()); var original = (ActionIntent) a[0]; var command = (CommandRequest) a[1];
                        if (!original.semantics().orElseThrow().operation().equals("deployment.toolchain.bind.v1"))
                            return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())), Optional.empty());
                        var currentTools = tools;
                        if (front && Set.of("VERSION", "DIRECTORY", "ORIGIN", "SHA", "REQUIREMENT", "CATALOG", "MISSING", "EXTRA").contains(mutation)) {
                            var selections = new ArrayList<>(tools.selections()); var first = selections.getFirst(); var need = first.requirement();
                            if (mutation.equals("REQUIREMENT")) need = new ToolchainRequirement(need.ecosystem(), need.declaration()+" changed", need.source(), need.purpose(), need.constraint(), need.version());
                            selections.set(0, new DeploymentResolvedToolchains.Selection(need,
                                mutation.equals("VERSION") ? DeploymentToolchainVersion.parse(need.ecosystem(), "24.22.2").orElseThrow() : first.version(),
                                mutation.equals("DIRECTORY") ? first.directory()+"-changed" : first.directory(),
                                mutation.equals("ORIGIN") ? DeploymentResolvedToolchains.OriginType.SYSTEM : first.origin(), first.source(), mutation.equals("SHA") ? "f".repeat(64) : first.sha256()));
                            if (mutation.equals("MISSING")) selections.removeLast(); if (mutation.equals("EXTRA")) selections.add(preparedTools(backend).selections().getFirst());
                            currentTools = new DeploymentResolvedToolchains(mutation.equals("CATALOG") ? "changed" : tools.catalogRevision(), selections);
                        }
                        String canonical = DeploymentToolchainBindingCodec.encode(currentTools) + (front && mutation.equals("CANONICAL") ? "\n" : "");
                        String encoded = Base64.getEncoder().encodeToString(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        var actualLayout = front && mutation.equals("LAYOUT") ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths("/srv/helper", "/srv/apps", "/srv/state", "/srv/config", "/srv/data")) : layout;
                        try (var actual = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand("'"+actualLayout.helperPath()+"' prepare-toolchains 'bind' '"+encoded+"'", actualLayout),
                            front && mutation.equals("STDIN") ? new byte[]{1} : new byte[0], command.timeout(), command.outputLimitBytes())) {
                            var actualServer = front && mutation.equals("SERVER") ? new ServerId("other") : server;
                            String actualRevision = front && mutation.equals("REVISION") ? "f".repeat(64) : original.revision();
                            if (front && mutation.equals("SIBLING_SAME_TOOLS")) {
                                scope.bindPreparation(sibling, fixture.task); actualRevision = ReviewedPlanIdentity.from(sibling.request());
                            }
                            var intent = RemoteActions.command(actualServer, actualRevision, actual).withSemantics(new ActionSemantics(
                                "deployment.toolchain.bind.v1", Set.of(new ActionTarget(actualServer.id(), "deployment-build", actualLayout.toolchains()))));
                            if (front && mutation.equals("PLAN")) scope.bindPreparation(backend, fixture.task);
                            if (front && mutation.equals("SOURCE")) sourceId.set(UUID.randomUUID()); if (front && mutation.equals("ARCHIVE")) archiveId.set(UUID.randomUUID());
                            if (front && mutation.equals("ROUTE")) fixture.configurationRevision = "changed";
                            if (front && mutation.equals("NO_PLAN")) { var field=DeploymentAssistedScope.class.getDeclaredField("toolchainPlan"); field.setAccessible(true); field.set(scope,null); }
                            exactIntent.set(intent); exactRequest.set(actual);
                            fixture.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("bind-"+(front ? "front" : "back"), "submit_result",
                                "{\"decision\":\""+(front && mutation.equals("DENY") ? "DENY" : "APPROVE")+"\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled independent binding verdict\"}"));
                            var result = ((DeploymentCommandReview) scope.interaction()).approveCommand(intent, actual, front && mutation.equals("TASK") ? new NativeAiFixture().task : fixture.task);
                            var facts = new ObjectMapper().valueToTree(scope.facts(intent, actual, fixture.task));
                            assertEquals(plan.request().facts().applicationId(), facts.at("/currentToolchainPlan/application").asText());
                            assertEquals(plan.request().facts().applicationId(), facts.at("/currentToolchainBindingStage/application").asText());
                            assertEquals(DeploymentToolchainBindingCodec.identity(tools), facts.at("/currentToolchainBindingStage/bindingSha256").asText());
                            assertEquals(tools.selections().size(), facts.at("/currentToolchainBindingStage/catalogPrepared").size());
                            assertEquals(front ? "frontend" : "backend", facts.at("/currentToolchainPlan/relativeRoot").asText());
                            assertEquals(2, facts.at("/sourceArchiveObservations/archives").size(), "Sibling source context is retained");
                            if (front) assertTrue(facts.at("/currentToolchainPlan/runtimeDependencyReason").asText().contains("Python 3.12"));
                            return result;
                        }
                    });
                    var peer = (RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{RemoteSession.class}, (p,m,a) -> {
                        if (m.getName().equals("facts")) return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("linux", "amd64", "SHA256:controlled");
                        assertEquals("execute", m.getName()); String script = DeploymentManagedHelperBundle.verifiedBody(((CommandRequest)a[0]).script());
                        String output;
                        if (script.contains("'bind' '")) {
                            assertSame(freshGrant.get(), a[1]); assertEquals(exactRequest.get().script(), ((CommandRequest)a[0]).script());
                            assertArrayEquals(exactRequest.get().stdin(), ((CommandRequest)a[0]).stdin());
                            binds.incrementAndGet(); output="TOOLCHAIN_BINDING="+DeploymentToolchainBindingCodec.identity(tools)+"\n";
                        }
                        else {
                            var diagnostic=DeploymentToolchainDiagnostics.decode(((CommandRequest)a[0]).stdin());
                            var selected=tools.selections().stream().filter(s -> s.requirement().ecosystem().name().equals(diagnostic.preparationArguments().get("ecosystem"))).findFirst().orElseThrow();
                            output="TOOLCHAIN="+selected.requirement().ecosystem()+"|"+selected.version().text()+"|"+selected.directory()+"|"+selected.origin()+"|"+selected.source()+"|"+selected.sha256()+"\n";
                        }
                        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), output, "", false)), Optional.empty());
                    });
                    var channel=new DeploymentBuildCommandChannel(peer, review, server, fixture.task, ReviewedPlanIdentity.from(plan.request()), new DeploymentBuildBudget(3600),
                        gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE, layout, new DeploymentPreparationObserver() {
                            @Override public void toolsPrepared(ServerId observedServer, DeploymentResolvedToolchains observed, gold.debug.windowstolinux.appapi.task.run.TaskContext task) throws ApiException {
                                if (front && mutation.equals("NO_RECEIPT")) return;
                                if (front && mutation.equals("UNBOUND_OBSERVER")) { scope.preparationObserver().toolsPrepared(observedServer, observed, task); return; }
                                if (front && mutation.equals("OBSERVER_PLAN")) scope.bindPreparation(backend, task);
                                if (front && mutation.equals("OBSERVER_SAME_TOOLS")) scope.bindPreparation(sibling, task);
                                if (front && mutation.equals("OBSERVER_SOURCE")) archiveId.set(UUID.randomUUID());
                                boundObserver.toolsPrepared(observedServer, observed, task);
                            }
                        });
                    return new gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentToolchainSetup(channel, DeploymentToolchainCatalog.defaults())
                        .prepare(gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentProjectToolchainRequirements.from(plan.request().facts(), plan.request().runtime()), 0, 120);
                }
            }
            var flow = new Flow(); assertEquals(TaskResult.SUCCEEDED, flow.run(backend,false).outcome());
            if (mutation.equals("APPROVE")) assertEquals(TaskResult.SUCCEEDED, flow.run(frontend,true).outcome());
            else {
                var refused=assertThrows(Exception.class, () -> flow.run(frontend,true));
                assertTrue(refused instanceof ApiException || refused instanceof IllegalArgumentException, refused::toString);
                if (mutation.equals("DENY")) assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", ((ApiException)refused).problem().eventCode());
            }
            assertEquals(mutation.equals("APPROVE") || mutation.startsWith("POST_") ? 2 : 1,grants.get());
            assertEquals(mutation.equals("APPROVE") ? 2 : 1,binds.get(), "Rejected frontend never dispatches its binding");
            assertEquals(mutation.equals("APPROVE") || mutation.equals("DENY") || mutation.startsWith("POST_") ? 2 : 1,fixture.requests.size());
        }
    }

    private ReviewedDeploymentPlan javaComponentPlan(DeploymentSourceMaterial material, DeploymentSourceArchive archive, ServerId server) throws Exception {
        var facts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(directory.resolve("backend"), "wtl-task-backend",
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.SPRING_BOOT,
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.MAVEN, List.of(),List.of(),List.of())
            .withToolchains(new gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.toolchain.ToolchainSpecChecker().inspect(directory.resolve("backend")));
        return new ReviewedDeploymentPlan(new ReviewedDeploymentRequest(server,facts,material,archive,
            gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot.create("wtl-task-backend",1,"fixture",java.time.Instant.EPOCH,List.of()),
            List.of(),new gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings(List.of(),Optional.of(List.of())),
            new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition.SpringBoot("21",
                new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Process(5,1)),Optional.empty(),
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(),
            new DeploymentReviewConsent("wtl-task-backend",server.id(),material.snapshot().sha256(),archive.sha256(),java.time.Instant.EPOCH),false,true,DeploymentRemoteLayout.original()),
            List.of(DeploymentPlanAction.VERIFY_SOURCE_IDENTITY,DeploymentPlanAction.CHECK_HEALTH,DeploymentPlanAction.ROLLBACK_ON_FAILURE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void originalAssistedPreparationCarriesCapturedLayoutToItsFirstActualReview(boolean custom) throws Exception {
        Files.createDirectories(directory.resolve("frontend"));
        Files.writeString(directory.resolve("frontend/package.json"), "{\"scripts\":{\"build\":\"vite build\"}}");
        var sourceId = new AtomicReference<>(UUID.randomUUID());
        var archiveId = new AtomicReference<>(UUID.randomUUID());
        var material = new DeploymentSourceMaterial("survey", snapshot(sourceId, "a".repeat(64)), List.of("frontend/package.json"), List.of());
        var archive = new DeploymentSourceArchive(snapshot(archiveId, "c".repeat(64)), "b".repeat(64), 1, 1, sourceId.get(), "a".repeat(64), "frontend");
        var fixture = new NativeAiFixture();
        var layout = custom ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/custom/helper", "/srv/custom/apps", "/srv/custom/state", "/srv/custom/config", "/srv/custom/data")) : DeploymentRemoteLayout.original();
        var json = new ObjectMapper();
        var payload = json.createObjectNode().put("formatVersion", 1).put("task", fixture.task.id().toString()).put("layoutSha256", layout.digest());
        payload.set("layout", json.readTree(layout.canonical()));
        var checkpoint = (gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.class}, (proxy, method, args) -> {
                if (method.getName().equals("snapshot")) return new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(0, payload.toString(), true);
                throw new AssertionError(method.getName());
            });
        var approvalCheckpoints = NativeAiFixture.approvalCheckpoints(fixture.task);
        var tasks = (gold.debug.windowstolinux.appapi.task.TaskService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (proxy, method, args) -> {
                if (method.getName().equals("findCheckpoint") && args[0] == fixture.task && args[1].equals("deployment.config.remote-layout.v1"))
                    return Optional.of(checkpoint);
                if (args[0] == fixture.task && gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentApprovalPolicies.KEY.equals(args[1]))
                    return method.invoke(approvalCheckpoints, args);
                throw new AssertionError(method.getName());
            });
        var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class},
            (proxy, method, args) -> {
                if (method.getName().equals("openReviewedWorkflow")) return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> {}), Optional.empty());
                throw new AssertionError("Independent DENY must precede host action admission: " + method.getName());
            });
        var db = (gold.debug.windowstolinux.appapi.db.ModDatabase) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.db.ModDatabase.class}, (proxy, method, args) -> { throw new AssertionError(method.getName()); });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "tasks" -> tasks;
                case "interaction" -> host;
                case "db" -> db;
                case "ui" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class},
                    (unused, uiMethod, values) -> new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
                default -> method.invoke(fixture.context, args);
            };
        });
        var server = new ServerId("fixture");
        var scope = new DeploymentAssistedScope(context, fixture.task, fixture.configurationRevision, "AUTOMATIC", true);
        try (var permission = DeploymentWorkflowAuthorization.open(context, fixture.task, server, fixture.configurationRevision, "AUTOMATIC", true, "Original source deployment")) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, material, fixture.task);
            var plan = DeploymentAssistedApprovalBudgetLiveTest.staticSitePlan(material, archive, "wtl-survey", layout, server);
            scope.archiveCaptured(material, archive, plan.request().facts().toolchainRequirements(), fixture.task);
            fixture.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("stop", "submit_result",
                "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Stop before dispatch\"}"));
            var type = Class.forName("gold.debug.windowstolinux.mod.deployment.service.standard.assisted.publish.DeploymentAssistedPublishBuild");
            var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            var build = constructor.newInstance(scope, new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.build.DeploymentStandardCandidateRunner());
            var prepare = type.getDeclaredMethod("prepare", plan.getClass(), gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class,
                gold.debug.windowstolinux.appapi.task.run.TaskContext.class); prepare.setAccessible(true);
            var remote = (gold.debug.windowstolinux.appapi.linux.session.RemoteSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class}, (proxy, method, args) -> { throw new AssertionError("No transport: " + method.getName()); });
            var failure = assertThrows(InvocationTargetException.class, () -> prepare.invoke(build, plan, remote, fixture.task));
            assertInstanceOf(ApiException.class, failure.getCause());
            assertEquals(1, fixture.requests.size());
            assertTrue(fixture.requests.getFirst().toString().contains(layout.helperPath()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"VALID", "REVISION", "SERVER", "SOURCE", "ARCHIVE_HANDLE", "TASK", "WORKFLOW", "BRANCH", "ECOSYSTEM", "EXACT", "DENY", "HOST_MISSING", "HOST_CLEARED", "HOST_REVISION", "HOST_TASK"})
    void runtimeNeedIsBoundToThePlanAndSourceWithoutRewritingSourceDeclarations(String mutation) throws Exception {
        runtimeNeed(mutation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NATIVE_VALID", "NATIVE_DENY", "NATIVE_APPROVE", "NATIVE_SOURCE", "NATIVE_ARCHIVE", "NATIVE_LAYOUT", "NATIVE_PLAN", "NATIVE_TASK", "NATIVE_NO_GAP", "NATIVE_CLEARED", "NATIVE_NO_PLAN", "NATIVE_WIDER_PROFILE", "NATIVE_OTHER_SCOPE"})
    void nativeDependenciesRequireTheCurrentTypedPlanAndGap(String mutation) throws Exception {
        runtimeNeed(mutation);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NATIVE_POST_SOURCE", "NATIVE_POST_ROUTE"})
    void nativeDependenciesRecheckCurrentAuthorityAfterApproval(String mutation) throws Exception {
        runtimeNeed(mutation);
    }

    private void runtimeNeed(String mutation) throws Exception {
        Files.createDirectories(directory.resolve("frontend"));
        Files.writeString(directory.resolve("frontend/package.json"), "{\"engines\":{\"node\":\">=24 <25\"},\"scripts\":{\"build\":\"vite build\"}}");
        var sourceId = new AtomicReference<>(UUID.randomUUID());
        var archiveId = new AtomicReference<>(UUID.randomUUID());
        FileSnapshot source = snapshot(sourceId, "a".repeat(64));
        FileSnapshot archived = snapshot(archiveId, "c".repeat(64));
        var material = new DeploymentSourceMaterial("survey", source, List.of("frontend/package.json"), List.of());
        var archive = new DeploymentSourceArchive(archived, "b".repeat(64), 1, 1, sourceId.get(), "a".repeat(64), "frontend");
        var fixture = new NativeAiFixture();
        var grants = new AtomicInteger();
        var exactNativeIntent = new AtomicReference<ActionIntent>();
        var exactNativeRequest = new AtomicReference<gold.debug.windowstolinux.appapi.linux.command.CommandRequest>();
        var host = (InteractionService) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{InteractionService.class},
            (proxy, method, args) -> {
                if (method.getName().equals("openReviewedWorkflow"))
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of((WorkflowGrant) () -> {
                    }), Optional.empty());
                if (method.getName().equals("approveReviewedAction") && (mutation.equals("NATIVE_APPROVE") || mutation.startsWith("NATIVE_POST_"))) {
                    assertSame(exactNativeIntent.get(), args[0]);
                    assertSame(exactNativeRequest.get(), ((Optional<?>) args[1]).orElseThrow());
                    assertSame(fixture.task, args[4]);
                    grants.incrementAndGet();
                    if (mutation.equals("NATIVE_POST_SOURCE")) archiveId.set(UUID.randomUUID());
                    return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())), Optional.empty());
                }
                grants.incrementAndGet();
                throw new AssertionError("No host permission in evidence-only test");
            });
        var routeAi = (gold.debug.windowstolinux.appapi.ai.AiService) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class}, (proxy, method, args) -> {
                if (method.getName().equals("configurationRevision") && mutation.equals("NATIVE_POST_ROUTE") && grants.get() > 0) return "changed-after-native-grant";
                try { return method.invoke(fixture.context.ai(), args); } catch (InvocationTargetException error) { throw error.getCause(); }
            });
        var context = (ModContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> {
                if (method.getName().equals("ai")) return routeAi;
                if (method.getName().equals("interaction")) return host;
                if (method.getName().equals("ui")) return Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class}, (unused, uiMethod, values) -> {
                        if (uiMethod.getName().equals("environment"))
                            return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false);
                        throw new AssertionError(uiMethod.getName());
                    });
                try {
                    return method.invoke(fixture.context, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
        var server = new ServerId("fixture");
        var scope = new DeploymentAssistedScope(context, fixture.task, fixture.configurationRevision, "AUTOMATIC", true);
        var layout = mutation.equals("NATIVE_LAYOUT") ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
            "/srv/other/helper", "/srv/other/apps", "/srv/other/state", "/srv/other/config", "/srv/other/data")) : DeploymentRemoteLayout.original();
        try (var permission = DeploymentWorkflowAuthorization.open(context, fixture.task, server, fixture.configurationRevision,
            "AUTOMATIC", true, "Deploy selected survey including its reviewed runtime dependencies")) {
            scope.workflowOpened(permission);
            scope.sourceCaptured(server, material, fixture.task);
            var initialPlan = DeploymentAssistedApprovalBudgetLiveTest.staticSitePlan(material, archive, "wtl-survey", layout,
                mutation.equals("SERVER") ? new ServerId("other") : server);
            var plan = mutation.startsWith("HOST_") || mutation.startsWith("NATIVE_") ? companionPlan(initialPlan) : initialPlan;
            scope.archiveCaptured(material, archive, plan.request().facts().toolchainRequirements(), fixture.task);
            String identity = ReviewedPlanIdentity.from(plan.request());
            var observer = scope;
            if (mutation.equals("SOURCE")) sourceId.set(UUID.randomUUID());
            if (mutation.equals("ARCHIVE_HANDLE")) archiveId.set(UUID.randomUUID());
            if (mutation.equals("WORKFLOW")) permission.close();
            if (Set.of("REVISION", "SERVER", "SOURCE", "ARCHIVE_HANDLE", "TASK", "WORKFLOW", "NATIVE_LAYOUT").contains(mutation)) {
                assertThrows(IllegalArgumentException.class, () -> observer.toolchainPreparationPlanned(plan,
                    mutation.equals("REVISION") ? "d".repeat(64) : identity,
                    mutation.equals("TASK") ? new NativeAiFixture().task : fixture.task));
            } else {
                observer.toolchainPreparationPlanned(plan, identity, fixture.task);
                if (mutation.startsWith("NATIVE_")) {
                    if (!mutation.equals("NATIVE_NO_GAP")) observer.hostPrerequisitesObserved(plan, identity, hostFacts(false), preparedTools(plan), fixture.task);
                    if (mutation.equals("NATIVE_CLEARED")) observer.hostPrerequisitesObserved(plan, identity, hostFacts(true), preparedTools(plan), fixture.task);
                    if (mutation.equals("NATIVE_SOURCE")) sourceId.set(UUID.randomUUID());
                    if (mutation.equals("NATIVE_ARCHIVE")) archiveId.set(UUID.randomUUID());
                    if (mutation.equals("NATIVE_NO_PLAN")) { var field = DeploymentAssistedScope.class.getDeclaredField("toolchainPlan"); field.setAccessible(true); field.set(scope, null); }
                    var actualLayout = mutation.equals("NATIVE_LAYOUT") ? new DeploymentRemoteLayout(new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(
                        "/srv/other/helper", "/srv/other/apps", "/srv/other/state", "/srv/other/config", "/srv/other/data")) : layout;
                    String profile = switch (mutation) {
                        case "NATIVE_WIDER_PROFILE" -> "NATIVE_BUILD";
                        case "NATIVE_OTHER_SCOPE" -> "NATIVE_BUILD_APT_4";
                        default -> "NATIVE_BUILD_APT_3";
                    };
                    String program = DeploymentToolchainDependencies.dependencies(actualLayout, profile);
                    try (var command = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(
                        DeploymentToolchainDependencies.command(List.of(profile, "a".repeat(32), "60", server.id(), fixture.task.id().toString())),
                        program.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.time.Duration.ofSeconds(60), 65536)) {
                        var intent = RemoteActions.command(server, mutation.equals("NATIVE_PLAN") ? "d".repeat(64) : identity, command).withSemantics(new ActionSemantics(
                            DeploymentToolchainDependencies.OPERATION,
                            DeploymentToolchainDependencies.dependencyTargets(server, profile, "a".repeat(32), actualLayout)));
                        exactNativeIntent.set(intent); exactNativeRequest.set(command);
                        var supplied = mutation.equals("NATIVE_TASK") ? new NativeAiFixture().task : fixture.task;
                        if (Set.of("NATIVE_VALID", "NATIVE_DENY", "NATIVE_APPROVE").contains(mutation) || mutation.startsWith("NATIVE_POST_")) {
                            var facts = new ObjectMapper().valueToTree(scope.facts(intent, command, supplied));
                            assertTrue(facts.at("/currentNativeBuildPrerequisites/observedGap/nativeBuildPrerequisitesMissing").asBoolean());
                            assertEquals(List.of("CMAKE", "NINJA"), new ObjectMapper().convertValue(
                                facts.at("/currentNativeBuildPrerequisites/observedGap/missingNativeBuildTools"), List.class));
                            assertEquals(profile, facts.at("/currentNativeBuildPrerequisites/fixedTransaction/profile").asText());
                            assertTrue(scope.reviewInstructions(intent, command, supplied).contains("maintainer scripts"));
                            if (mutation.equals("NATIVE_APPROVE") || mutation.startsWith("NATIVE_POST_")) {
                                fixture.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("approve-native", "submit_result",
                                    "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled fixed transaction\"}"));
                                if (mutation.startsWith("NATIVE_POST_")) {
                                    var refused = assertThrows(ApiException.class, () -> ((DeploymentCommandReview) scope.interaction()).approveCommand(intent, command, supplied));
                                    assertEquals(mutation.equals("NATIVE_POST_SOURCE") ? "DEPLOYMENT_COMMAND_REVIEW_INVALID" : "DEPLOYMENT_ASSISTED_CONFIGURATION_CHANGED", refused.problem().eventCode());
                                } else {
                                    var result = ((DeploymentCommandReview) scope.interaction()).approveCommand(intent, command, supplied);
                                    assertEquals(TaskResult.SUCCEEDED, result.outcome());
                                    assertTrue(result.value().isPresent());
                                }
                            }
                            if (mutation.equals("NATIVE_DENY")) {
                                fixture.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("deny-native", "submit_result",
                                    "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Controlled original refusal\"}"));
                                var denied = assertThrows(ApiException.class, () -> ((DeploymentCommandReview) scope.interaction()).approveCommand(intent, command, supplied));
                                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED", denied.problem().eventCode());
                            }
                        } else assertThrows(RuntimeException.class, () -> scope.facts(intent, command, supplied));
                    }
                    assertEquals(mutation.equals("NATIVE_APPROVE") || mutation.startsWith("NATIVE_POST_") ? 1 : 0, grants.get());
                    return;
                }

                if (mutation.startsWith("HOST_")) {
                    var tools = preparedTools(plan);
                    if (mutation.equals("HOST_REVISION") || mutation.equals("HOST_TASK")) {
                        assertThrows(IllegalArgumentException.class, () -> observer.hostPrerequisitesObserved(plan,
                            mutation.equals("HOST_REVISION") ? "d".repeat(64) : identity,
                            hostFacts(false), tools, mutation.equals("HOST_TASK") ? new NativeAiFixture().task : fixture.task));
                    } else {
                        observer.hostPrerequisitesObserved(plan, identity, hostFacts(false), tools, fixture.task);
                        try (var command = DeploymentAssistedApprovalBudgetLiveTest.preparation(layout, "PYTHON", "3.12")) {
                            var intent = RemoteActions.command(server, identity, command).withSemantics(new ActionSemantics(
                                "deployment.toolchain.prepare.v1", Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains()))));
                            var observed = new ObjectMapper().valueToTree(scope.effectFacts(intent, fixture.task));
                            assertEquals("wtl-survey", observed.at("/hostPrerequisiteObservations/applications/0/application").asText());
                            assertTrue(observed.at("/hostPrerequisiteObservations/applications/0/requiredCapability").asText().contains("CMake 3.25"));
                            assertEquals(identity, observed.at("/hostPrerequisiteObservations/applications/0/reviewedPlanSha256").asText());
                            if (mutation.equals("HOST_CLEARED")) {
                                observer.hostPrerequisitesObserved(plan, identity, hostFacts(true), tools, fixture.task);
                                assertFalse(scope.effectFacts(intent, fixture.task).containsKey("hostPrerequisiteObservations"));
                            }
                        }
                    }
                    assertTrue(fixture.requests.isEmpty());
                    assertEquals(0, grants.get());
                    return;
                }
                String ecosystem = mutation.equals("ECOSYSTEM") ? "RUBY" : "PYTHON";
                String branch = mutation.equals("ECOSYSTEM") ? "3.3" : mutation.equals("BRANCH") ? "3.13" : "3.12";
                try (var request = DeploymentAssistedApprovalBudgetLiveTest.preparation(layout, ecosystem, branch)) {
                    var actual = request;
                    if (mutation.equals("EXACT"))
                        actual = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(
                            request.script().replace("'3.12' '-'", "'3.12' '3.12.1'"), new byte[0], request.timeout(), request.outputLimitBytes());
                    try (var complete = actual) {
                        var intent = RemoteActions.command(server, identity, complete).withSemantics(new ActionSemantics(
                            "deployment.toolchain.prepare.v1", Set.of(new ActionTarget(server.id(), "deployment-build", layout.toolchains()))));
                        if (!Set.of("VALID", "DENY").contains(mutation))
                            assertThrows(IllegalArgumentException.class, () -> scope.facts(intent, complete, fixture.task));
                        else {
                            scope.preparationObserver(plan, fixture.task).hostObservedBeforePreparation(identity, hostFacts(false), fixture.task);
                            var facts = new ObjectMapper().valueToTree(scope.facts(intent, complete, fixture.task));
                            assertEquals("StaticSite", facts.at("/currentToolchainPlan/runtimeKind").asText());
                            assertEquals(identity, facts.at("/currentToolchainPlan/reviewedPlanSha256").asText());
                            assertEquals(identity, facts.at("/currentPrePreparationHost/reviewedPlanSha256").asText());
                            assertEquals("24.04", facts.at("/currentPrePreparationHost/platform/version").asText());
                            assertEquals("3.12", facts.at("/currentPrePreparationHost/observedTools/pythonVenvBranches/0").asText());
                            assertFalse(facts.has("hostPrerequisiteObservations"));
                            var application = facts.at("/reviewedApplicationSources/applications/0");
                            assertEquals("wtl-survey", application.path("application").asText());
                            assertEquals("frontend", application.path("relativeRoot").asText());
                            assertEquals("a".repeat(64), application.path("sourceManifestSha256").asText());
                            assertEquals("b".repeat(64), application.path("archiveSha256").asText());
                            assertEquals("survey", facts.at("/sourceSelection/displayName").asText());
                            assertTrue(facts.at("/currentToolchainPlan/runtimeDependencyReason").asText().contains("Python 3.12"));
                            var needs = facts.at("/currentToolchainPlan/requirements");
                            assertTrue(java.util.stream.StreamSupport.stream(needs.spliterator(), false)
                                .anyMatch(value -> value.path("ecosystem").asText().equals("PYTHON") && value.path("derivation").asText().equals("REVIEWED_RUNTIME")));
                            var declarations = facts.at("/sourceArchiveObservations/archives/0/sourceToolchainRequirements");
                            assertTrue(java.util.stream.StreamSupport.stream(declarations.spliterator(), false)
                                .noneMatch(value -> value.path("ecosystem").asText().equals("PYTHON")));
                            if (mutation.equals("DENY")) {
                                for (int attempt = 0; attempt < 2; attempt++) {
                                    fixture.nativeReply(new gold.debug.windowstolinux.appapi.ai.model.AiToolCall("deny-" + attempt, "submit_result",
                                        "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Independent refusal\"}"));
                                    assertThrows(ApiException.class, () -> ((DeploymentCommandReview) scope.interaction()).approveCommand(intent, complete, fixture.task));
                                }
                                assertEquals(2, fixture.requests.size());
                            }
                        }
                    }
                }
            }
            if (!mutation.equals("DENY")) assertTrue(fixture.requests.isEmpty());
            assertEquals(0, grants.get());
        }
    }


    private static gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan companionPlan(
        gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan initial) {
        var r = initial.request();
        var w = r.runtime().workload();
        var workload = new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload(
            w.mode(), w.reviewed(), w.command(), w.workingDirectory(), w.endpoints(), w.verification(), w.expectedOutput(),
            w.client(), w.inputs(), List.of(new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCompanion(
                "worker", "native", gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCompanion.BuildType.CMAKE_SERVICE,
                ".w2l/bin/worker", "NATIVE_HELPER")), w.buildDirectory(), w.workers());
        var request = new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentRequest(
            r.server(), r.facts(), r.material(), r.archive(), r.configuration(), r.secretReferences(), r.resources(),
            r.runtime().withWorkload(workload), r.userAccessUrl(), r.limits(), r.review(), r.containerDaemonRiskAccepted(),
            r.experimentalAdapterRiskAccepted(), r.layout());
        return new gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan(request, initial.steps());
    }

    private static gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains preparedTools(
        gold.debug.windowstolinux.mod.deployment.service.standard.shared.deploy.contract.ReviewedDeploymentPlan plan) {
        var catalog = gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog.defaults();
        var selections = gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain.DeploymentProjectToolchainRequirements
            .from(plan.request().facts(), plan.request().runtime()).stream().map(need ->
                new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.Selection(need,
                    gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainVersion.parse(need.ecosystem(),
                        need.ecosystem().name().equals("JAVA") ? "21.0.13+9" : need.ecosystem().name().equals("NODE") ? "24.0.0" : "3.12.0").orElseThrow(),
                    "/opt/fixture/" + need.ecosystem().name().toLowerCase(Locale.ROOT),
                    gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains.OriginType.MANAGED,
                    "fixture", "e".repeat(64))).toList();
        return new gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentResolvedToolchains(catalog.revision(), selections);
    }

    private static gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentLinuxCapabilityFacts hostFacts(boolean ready) {
        var values = new HashMap<String, String>(Map.of("DISTRO_ID", "ubuntu", "VERSION", "24.04", "ARCH", "x86_64",
            "PACKAGE_MANAGER", "apt", "PACKAGE_ARCH", "amd64", "SYSTEMD", "1", "CPU_LEVEL", "x86-64-v1",
            "PYTHON3", "1", "PYTHON_VERSIONS", "3.12"));
        values.put("TOOL_C_COMPILER", "13.3.0");
        values.put("TOOL_CPP_COMPILER", "13.3.0");
        if (ready) {
            values.put("TOOL_CMAKE", "3.28.3");
            values.put("TOOL_NINJA", "1.11.1");
        }
        return gold.debug.windowstolinux.mod.deployment.linux.probe.DeploymentPlatformCapabilityParser.fromValues(values, "fixture");
    }

    private FileSnapshot snapshot(AtomicReference<UUID> id, String sha) {
        return (FileSnapshot) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FileSnapshot.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "id" -> id.get();
                case "sha256" -> sha;
                case "directory" -> directory.toAbsolutePath();
                default -> throw new AssertionError(method.getName());
            });
    }
}
