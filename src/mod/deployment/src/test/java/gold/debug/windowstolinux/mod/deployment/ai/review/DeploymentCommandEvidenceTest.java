package gold.debug.windowstolinux.mod.deployment.ai.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.LinkedHashMap;
import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import gold.debug.windowstolinux.mod.deployment.ai.DeploymentAiJson;
import gold.debug.windowstolinux.mod.deployment.ai.NativeAiFixture;
import gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentCommandProtocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import org.junit.jupiter.api.Test;

class DeploymentCommandEvidenceTest {
    @Test
    void retainedIsolationEvidenceSurvivesCommandReplacementAndConflictingFactsFailBeforeGrant() throws Exception {
        var taskId = UUID.randomUUID();
        var task = (gold.debug.windowstolinux.appapi.task.run.TaskContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.run.TaskContext.class}, (p0, m, a) -> {
                if (m.getName().equals("id")) return taskId;
                throw new AssertionError(m.getName());
            });
        var ai = (gold.debug.windowstolinux.appapi.ai.AiService) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class}, (p0, m, a) -> {
                if (m.getName().equals("configurationRevision")) return "fixture-route";
                throw new AssertionError("No provider call: " + m.getName());
            });
        var tasks = (gold.debug.windowstolinux.appapi.task.TaskService) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (p0, m, a) -> {
                if (m.getName().equals("findCheckpoint")) return java.util.Optional.empty();
                throw new AssertionError(m.getName());
            });
        var ui = (gold.debug.windowstolinux.appapi.ui.UiService) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class}, (p0, m, a) -> {
                if (m.getName().equals("environment")) return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH, "LIGHT", false);
                throw new AssertionError(m.getName());
            });
        var logs = (gold.debug.windowstolinux.appapi.task.log.LogService) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.log.LogService.class}, (p0, m, a) -> {
                if (m.getName().equals("register")) return new gold.debug.windowstolinux.appapi.task.log.LogTarget(UUID.randomUUID());
                if (m.getName().equals("write")) return null;
                throw new AssertionError(m.getName());
            });
        var context = (gold.debug.windowstolinux.appapi.mod.ModContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class}, (p0, m, a) -> switch (m.getName()) {
                case "ai" -> ai; case "tasks" -> tasks; case "ui" -> ui; case "logs" -> logs;
                default -> throw new AssertionError(m.getName());
            });
        var grants = new java.util.concurrent.atomic.AtomicInteger();
        var host = (gold.debug.windowstolinux.appapi.task.action.InteractionService) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class}, (p0, m, a) -> {
                grants.incrementAndGet(); throw new AssertionError("DENY cannot request a grant");
            });
        var reviews = new java.util.concurrent.atomic.AtomicInteger();
        var verified = new java.util.concurrent.atomic.AtomicInteger();
        DeploymentCurrentCommandEvidence retained = new DeploymentCurrentCommandEvidence() {
            public Map<String, ?> facts(gold.debug.windowstolinux.appapi.task.action.ActionIntent i, CommandRequest q,
                    gold.debug.windowstolinux.appapi.task.run.TaskContext t) { return Map.of("agentIsolation", "actual-protected-proof"); }
            public void verify(gold.debug.windowstolinux.appapi.task.action.ActionIntent i, CommandRequest q,
                    gold.debug.windowstolinux.appapi.task.run.TaskContext t) { assertSame(task, t); verified.incrementAndGet(); }
        };
        DeploymentCommandReviewModel reviewer = (instructions, facts, intent, request, revision, supplied) -> {
            reviews.incrementAndGet();
            assertEquals("actual-protected-proof", ((Map<?, ?>) facts.get("currentCommandEvidence")).get("agentIsolation"));
            assertEquals("fresh-command-observer", ((Map<?, ?>) facts.get("currentCommandEvidence")).get("producer"));
            return new DeploymentCommandReviewModel.Result(new DeploymentReviewDecision("DENY", false, "controlled independent refusal"), Map.of());
        };
        var scope = new DeploymentCommandReviewScope(context, host, task, "AUTOMATIC", false, () -> "fixture-route", null,
            DeploymentCurrentCommandEvidence.NONE, () -> reviewer, retained);
        var replaced = scope.withCurrentEvidence((i, q, t) -> Map.of("discarded", "old"))
            .withCurrentEvidence((i, q, t) -> Map.of("producer", "fresh-command-observer"));
        try (var request = new CommandRequest("printf controlled-readonly", new byte[0], Duration.ofSeconds(30), 1024)) {
            var intent = RemoteActions.command(new ServerId("controlled-host"), "fixture-route", request)
                .withSemantics(new ActionSemantics("deployment.candidate.capacity.v1", Set.of(new ActionTarget("controlled-host", "host-inspection", "capacity"))));
            assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class, () ->
                gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter.approve(replaced.interaction(), intent, request, task));
            assertEquals(1, reviews.get()); assertTrue(verified.get() > 0); assertEquals(0, grants.get());
            var conflict = scope.withCurrentEvidence((i, q, t) -> Map.of("agentIsolation", "forged"));
            assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class, () ->
                gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter.approve(conflict.interaction(), intent, request, task));
            assertEquals(1, reviews.get()); assertEquals(0, grants.get());
        }
    }

    @Test
    void isolationProbeSuppliesCompleteInputAndRejectsChangedTargetsOrBody() throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var policy = gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.trusted(
            "controlled-isolation", "new-app", layout, java.util.List.of(
                new gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.ProtectedApplication(
                    UUID.randomUUID(), "old-app", layout.digest(), java.util.Set.of("old-app-backend"), java.util.Set.of(), java.util.Map.of(), java.util.Set.of())))
            .minimumResources(2415919104L, 1);
        UUID task = UUID.randomUUID();
        var component = new gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.ResolvedComponent(
            "backend", "new-app-backend", "new-app-backend-1234567890abcdef",
            java.util.Set.of(layout.candidateRoot("new-app-backend-1234567890abcdef"), layout.applicationRoot("new-app-backend"),
                layout.configurationRoot("new-app-backend"), layout.dataRoot("new-app-backend"), layout.snapshotRoot("new-app-backend", task),
                layout.applicationLockRoot("new-app-backend"), "/etc/systemd/system/windowstolinux-new-app-backend.service",
                "/etc/systemd/system/windowstolinux-build-new-app-backend-1234567890abcdef.service"),
            java.util.Set.of(new gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.Port("TCP", 48937)),
            268435456L, 1073741824L, "a".repeat(64), "b".repeat(64));
        var scope = new gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationPolicy.PlannedScope(
            task, "controlled-host", "c".repeat(64), "d".repeat(64), "e".repeat(64), layout.digest(), "f".repeat(64),
            java.util.List.of(component), java.util.List.of(), 2415919104L, 1);
        var probe = new gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe(policy);
        var server = new gold.debug.windowstolinux.appapi.server.model.ServerId(scope.server());
        try (var request = probe.request(scope, UUID.randomUUID())) {
            var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, scope.configurationSha256(), request.command())
                .withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(
                    gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.OPERATION, request.resourceTargets()));
            assertFalse(DeploymentFixedCommandApproval.registered(intent));
            assertDoesNotThrow(() -> DeploymentAgentCommandProtocol.requireIsolationAllowed(request.command(), layout));
            var facts = DeploymentAgentCommandProtocol.facts(intent, request.command(), layout);
            assertEquals(probe.source(), facts.get("completeExecutableInput"));
            assertTrue(facts.containsKey("agentIsolation"));
            var wrong = intent.withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(
                gold.debug.windowstolinux.mod.deployment.service.agent.isolation.DeploymentAgentIsolationProbe.OPERATION,
                java.util.Set.of(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "task", task.toString()))));
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentCommandProtocol.facts(wrong, request.command(), layout));
            try (var changed = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(request.command().script(),
                (probe.source() + "\n# modified").getBytes(java.nio.charset.StandardCharsets.UTF_8), request.command().timeout(), request.command().outputLimitBytes())) {
                var altered = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, scope.configurationSha256(), changed)
                    .withSemantics(intent.semantics().orElseThrow());
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentCommandProtocol.requireIsolationAllowed(changed, layout));
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentCommandProtocol.facts(altered, changed, layout));
            }
        }
    }

    @Test
    void publicationCorrectionDeliversCompleteSourceAndNeedsIndependentFreshGrant()throws Exception {
        var layout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var program=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrectionProgram(layout);
        var fixture=new NativeAiFixture();var json=new ObjectMapper();var server=new ServerId("original-host");
        var ui=(gold.debug.windowstolinux.appapi.ui.UiService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ui.UiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class},(p,m,a)->{
            if(m.getName().equals("environment"))return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH,"LIGHT",false);throw new AssertionError(m.getName());
        });
        var reviewContext=(gold.debug.windowstolinux.appapi.mod.ModContext)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.mod.ModContext.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class},(p,m,a)->{
            if(m.getName().equals("ui"))return ui;try{return m.invoke(fixture.context,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        var original=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(server.id(),fixture.task.id(),UUID.randomUUID(),UUID.randomUUID(),17,"a".repeat(64),"b".repeat(64),"backend","c".repeat(64),"backend-0123456789abcdef","d".repeat(64),layout.digest());
        var prior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.PriorPreflight(UUID.randomUUID(),30,38,"e".repeat(64),"f".repeat(64));
        var current=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.CurrentFingerprints("1".repeat(64),"2".repeat(64),"3".repeat(64),"4".repeat(64),"5".repeat(64),"6".repeat(64));
        var continuation=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.Binding(original,18,"7".repeat(64),prior,current);
        var oldProgram=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram(layout);
        var continuationPrior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.PriorContinuation(UUID.randomUUID(),40,46,"8".repeat(64),"9".repeat(64),oldProgram.sourceVersion());
        var diagnosticBinding=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.Binding(continuation,continuationPrior,50,"0".repeat(64));
        var oldDiagnosticProgram=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram(layout);
        var diagnosticPrior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram.PriorDiagnostic(UUID.randomUUID(),60,68,"1".repeat(64),"2".repeat(64),oldDiagnosticProgram.sourceVersion());
        var followupBinding=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding(diagnosticBinding,diagnosticPrior,70,"3".repeat(64));
        var oldFollowupProgram=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout);
        var followupPrior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrectionProgram.PriorFollowup(UUID.randomUUID(),80,88,"4".repeat(64),"5".repeat(64),oldFollowupProgram.sourceVersion());
        var binding=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrectionProgram.Binding(followupBinding,followupPrior,90,"6".repeat(64));
        var agent=new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(fixture.context,fixture.task,
            new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence(){
                public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding(){return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding(server.id(),"local-native-review-target","e".repeat(64));}
                public Map<String,gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources(){return Map.of();}
                public Object receipts(){return Map.of("events",List.of());}
            });
        var grants=new java.util.HashSet<UUID>();var reviewedActions=new java.util.HashSet<String>();
        var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
            if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("APP cannot be bypassed by host.approve");
            var intent=(gold.debug.windowstolinux.appapi.task.action.ActionIntent)a[0];
            @SuppressWarnings("unchecked") var supplied=(java.util.Optional<CommandRequest>)a[1];
            assertEquals(RemoteActions.command(server,intent.revision(),supplied.orElseThrow()).exactAction(),intent.exactAction());
            assertTrue(reviewedActions.add(intent.exactAction()));UUID id=UUID.randomUUID();assertTrue(grants.add(id));
            return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(id)),java.util.Optional.empty());
        });
        String approve="{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Local complete diagnostic review\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}";
        String deny="{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Local diagnostic denial\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[],\"discussionStatus\":\"CLOSED\"}";
        for(int attempt=0;attempt<3;attempt++)try(var request=program.request(binding,UUID.randomUUID())) {
            var intent=RemoteActions.command(server,binding.followup().diagnostic().continuation().original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrectionProgram.OPERATION,request.resourceTargets()));
            var facts=json.valueToTree(DeploymentAgentCommandProtocol.facts(intent,request.command(),layout));
            assertEquals("EXECUTABLE_SCRIPT",facts.path("inputKind").asText());assertEquals(program.source(),facts.path("completeExecutableInput").asText());
            assertEquals(program.sourceVersion(),facts.at("/publicationCorrection/expectedSourceVersion").asText());assertFalse(facts.path("publicationCorrection").has("completeReadonlySource"));assertFalse(facts.has("publicationRecoveryProgram"));assertFalse(facts.has("publicationRecoveryDiagnostic"));assertEquals(followupPrior.receiptSha256(),facts.at("/publicationCorrection/binding/prior/receiptSha256").asText());assertFalse(facts.has("publicationContractDiagnostic"));assertFalse(facts.has("installedHelperPath"));
            if(attempt==0) {
                fixture.nativeReply(new AiToolCall("diagnostic-deny","submit_result",deny));
                var refusal=assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,()->DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED",refusal.problem().eventCode());assertTrue(grants.isEmpty());continue;
            }
            // Page the complete real launcher as well as stdin through the actual native review tools.
            for(int offset=0;offset<request.command().script().length();) {
                int end=Math.min(offset+4000,request.command().script().length());
                fixture.nativeReply(new AiToolCall("preflight-launcher-"+attempt+"-"+offset,"request",json.writeValueAsString(Map.of("part","script","offset",offset,"limit",end-offset))));offset=end;
            }
            for(int offset=0;offset<program.source().length();) {
                int end=Math.min(offset+4000,program.source().length());
                fixture.nativeReply(new AiToolCall("diagnostic-page-"+attempt+"-"+offset,"evidence",json.writeValueAsString(Map.of("pointer","/completeExecutableInput","offset",offset,"limit",end-offset))));offset=end;
            }
            fixture.nativeReply(new AiToolCall("diagnostic-approve-"+attempt,"submit_result",approve));
            assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()).outcome());
            assertEquals(attempt,grants.size());assertTrue(fixture.replies.isEmpty());
            byte[] changed=request.command().stdin();changed[changed.length-1]^=1;
            try(var drift=new CommandRequest(request.command().script(),changed,request.command().timeout(),request.command().outputLimitBytes())) {
                assertThrows(IllegalArgumentException.class,()->DeploymentAgentCommandProtocol.facts(intent,drift,layout));
            }
            var wrong=new java.util.HashSet<>(request.resourceTargets());wrong.remove(wrong.iterator().next());
            assertThrows(IllegalArgumentException.class,()->DeploymentAgentCommandProtocol.facts(RemoteActions.command(server,binding.followup().diagnostic().continuation().original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrectionProgram.OPERATION,wrong)),request.command(),layout));
        }
        assertEquals(2,grants.size());assertEquals(2,reviewedActions.size());
        assertEquals(program.sourceVersion(),json.readTree(fixture.requests.getFirst().messages().get(1).text()).at("/currentRequest/publicationCorrection/expectedSourceVersion").asText());
        // Existing native review fixture and synthetic host grants only; no SDK consumption, provider or SSH is exercised.
    }

    @Test
    void contractDiagnosticFollowupDeliversCompleteSourceAndRequiresIndependentFreshGrant()throws Exception {
        var layout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var program=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout);
        var fixture=new NativeAiFixture();var json=new ObjectMapper();var server=new ServerId("original-host");
        var ui=(gold.debug.windowstolinux.appapi.ui.UiService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ui.UiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class},(p,m,a)->{
            if(m.getName().equals("environment"))return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH,"LIGHT",false);throw new AssertionError(m.getName());
        });
        var reviewContext=(gold.debug.windowstolinux.appapi.mod.ModContext)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.mod.ModContext.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class},(p,m,a)->{
            if(m.getName().equals("ui"))return ui;try{return m.invoke(fixture.context,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        var original=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(server.id(),fixture.task.id(),UUID.randomUUID(),UUID.randomUUID(),17,"a".repeat(64),"b".repeat(64),"backend","c".repeat(64),"backend-0123456789abcdef","d".repeat(64),layout.digest());
        var prior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.PriorPreflight(UUID.randomUUID(),30,38,"e".repeat(64),"f".repeat(64));
        var current=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.CurrentFingerprints("1".repeat(64),"2".repeat(64),"3".repeat(64),"4".repeat(64),"5".repeat(64),"6".repeat(64));
        var continuation=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.Binding(original,18,"7".repeat(64),prior,current);
        var oldProgram=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram(layout);
        var continuationPrior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.PriorContinuation(UUID.randomUUID(),40,46,"8".repeat(64),"9".repeat(64),oldProgram.sourceVersion());
        var diagnosticBinding=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.Binding(continuation,continuationPrior,50,"0".repeat(64));
        var oldDiagnosticProgram=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram(layout);
        var diagnosticPrior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram.PriorDiagnostic(UUID.randomUUID(),60,68,"1".repeat(64),"2".repeat(64),oldDiagnosticProgram.sourceVersion());
        var binding=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding(diagnosticBinding,diagnosticPrior,70,"3".repeat(64));
        var agent=new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(fixture.context,fixture.task,
            new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence(){
                public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding(){return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding(server.id(),"local-native-review-target","e".repeat(64));}
                public Map<String,gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources(){return Map.of();}
                public Object receipts(){return Map.of("events",List.of());}
            });
        var grants=new java.util.HashSet<UUID>();var reviewedActions=new java.util.HashSet<String>();
        var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
            if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("APP cannot be bypassed by host.approve");
            var intent=(gold.debug.windowstolinux.appapi.task.action.ActionIntent)a[0];
            @SuppressWarnings("unchecked") var supplied=(java.util.Optional<CommandRequest>)a[1];
            assertEquals(RemoteActions.command(server,intent.revision(),supplied.orElseThrow()).exactAction(),intent.exactAction());
            assertTrue(reviewedActions.add(intent.exactAction()));UUID id=UUID.randomUUID();assertTrue(grants.add(id));
            return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(id)),java.util.Optional.empty());
        });
        String approve="{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Local complete diagnostic review\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}";
        String deny="{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Local diagnostic denial\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[],\"discussionStatus\":\"CLOSED\"}";
        for(int attempt=0;attempt<3;attempt++)try(var request=program.request(binding,UUID.randomUUID())) {
            var intent=RemoteActions.command(server,binding.diagnostic().continuation().original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram.OPERATION,request.resourceTargets()));
            var facts=json.valueToTree(DeploymentAgentCommandProtocol.facts(intent,request.command(),layout));
            assertEquals("EXECUTABLE_SCRIPT",facts.path("inputKind").asText());assertEquals(program.source(),facts.path("completeExecutableInput").asText());
            assertEquals(program.sourceVersion(),facts.at("/publicationContractDiagnosticFollowup/expectedSourceVersion").asText());assertFalse(facts.path("publicationContractDiagnosticFollowup").has("completeReadonlySource"));assertFalse(facts.has("publicationRecoveryProgram"));assertFalse(facts.has("publicationRecoveryDiagnostic"));assertEquals(diagnosticPrior.receiptSha256(),facts.at("/publicationContractDiagnosticFollowup/binding/prior/receiptSha256").asText());assertFalse(facts.has("publicationContractDiagnostic"));assertFalse(facts.has("installedHelperPath"));
            if(attempt==0) {
                fixture.nativeReply(new AiToolCall("diagnostic-deny","submit_result",deny));
                var refusal=assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,()->DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED",refusal.problem().eventCode());assertTrue(grants.isEmpty());continue;
            }
            // Page the complete real launcher as well as stdin through the actual native review tools.
            for(int offset=0;offset<request.command().script().length();) {
                int end=Math.min(offset+4000,request.command().script().length());
                fixture.nativeReply(new AiToolCall("preflight-launcher-"+attempt+"-"+offset,"request",json.writeValueAsString(Map.of("part","script","offset",offset,"limit",end-offset))));offset=end;
            }
            for(int offset=0;offset<program.source().length();) {
                int end=Math.min(offset+4000,program.source().length());
                fixture.nativeReply(new AiToolCall("diagnostic-page-"+attempt+"-"+offset,"evidence",json.writeValueAsString(Map.of("pointer","/completeExecutableInput","offset",offset,"limit",end-offset))));offset=end;
            }
            fixture.nativeReply(new AiToolCall("diagnostic-approve-"+attempt,"submit_result",approve));
            assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()).outcome());
            assertEquals(attempt,grants.size());assertTrue(fixture.replies.isEmpty());
            byte[] changed=request.command().stdin();changed[changed.length-1]^=1;
            try(var drift=new CommandRequest(request.command().script(),changed,request.command().timeout(),request.command().outputLimitBytes())) {
                assertThrows(IllegalArgumentException.class,()->DeploymentAgentCommandProtocol.facts(intent,drift,layout));
            }
            var wrong=new java.util.HashSet<>(request.resourceTargets());wrong.remove(wrong.iterator().next());
            assertThrows(IllegalArgumentException.class,()->DeploymentAgentCommandProtocol.facts(RemoteActions.command(server,binding.diagnostic().continuation().original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram.OPERATION,wrong)),request.command(),layout));
        }
        assertEquals(2,grants.size());assertEquals(2,reviewedActions.size());
        assertEquals(program.sourceVersion(),json.readTree(fixture.requests.getFirst().messages().get(1).text()).at("/currentRequest/publicationContractDiagnosticFollowup/expectedSourceVersion").asText());
        // Existing native review fixture and synthetic host grants only; no SDK consumption, provider or SSH is exercised.
    }

    @Test
    void publicationContractDiagnosticDeliversCompleteSourceAndRequiresFreshHostGrant()throws Exception {
        var layout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var program=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram(layout);
        var fixture=new NativeAiFixture();var json=new ObjectMapper();var server=new ServerId("original-host");
        var ui=(gold.debug.windowstolinux.appapi.ui.UiService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ui.UiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class},(p,m,a)->{
            if(m.getName().equals("environment"))return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH,"LIGHT",false);throw new AssertionError(m.getName());
        });
        var reviewContext=(gold.debug.windowstolinux.appapi.mod.ModContext)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.mod.ModContext.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class},(p,m,a)->{
            if(m.getName().equals("ui"))return ui;try{return m.invoke(fixture.context,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        var original=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(server.id(),fixture.task.id(),UUID.randomUUID(),UUID.randomUUID(),17,"a".repeat(64),"b".repeat(64),"backend","c".repeat(64),"backend-0123456789abcdef","d".repeat(64),layout.digest());
        var prior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.PriorPreflight(UUID.randomUUID(),30,38,"e".repeat(64),"f".repeat(64));
        var current=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.CurrentFingerprints("1".repeat(64),"2".repeat(64),"3".repeat(64),"4".repeat(64),"5".repeat(64),"6".repeat(64));
        var continuation=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.Binding(original,18,"7".repeat(64),prior,current);
        var oldProgram=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram(layout);
        var continuationPrior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.PriorContinuation(UUID.randomUUID(),40,46,"8".repeat(64),"9".repeat(64),oldProgram.sourceVersion());
        var binding=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.Binding(continuation,continuationPrior,50,"0".repeat(64));
        var agent=new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(fixture.context,fixture.task,
            new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence(){
                public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding(){return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding(server.id(),"local-native-review-target","e".repeat(64));}
                public Map<String,gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources(){return Map.of();}
                public Object receipts(){return Map.of("events",List.of());}
            });
        var grants=new java.util.HashSet<UUID>();var reviewedActions=new java.util.HashSet<String>();
        var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
            if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("APP cannot be bypassed by host.approve");
            var intent=(gold.debug.windowstolinux.appapi.task.action.ActionIntent)a[0];
            @SuppressWarnings("unchecked") var supplied=(java.util.Optional<CommandRequest>)a[1];
            assertEquals(RemoteActions.command(server,intent.revision(),supplied.orElseThrow()).exactAction(),intent.exactAction());
            assertTrue(reviewedActions.add(intent.exactAction()));UUID id=UUID.randomUUID();assertTrue(grants.add(id));
            return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(id)),java.util.Optional.empty());
        });
        String approve="{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Local complete diagnostic review\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}";
        String deny="{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Local diagnostic denial\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[],\"discussionStatus\":\"CLOSED\"}";
        for(int attempt=0;attempt<3;attempt++)try(var request=program.request(binding,UUID.randomUUID())) {
            var intent=RemoteActions.command(server,binding.continuation().original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.OPERATION,request.resourceTargets()));
            var facts=json.valueToTree(DeploymentAgentCommandProtocol.facts(intent,request.command(),layout));
            assertEquals("EXECUTABLE_SCRIPT",facts.path("inputKind").asText());assertEquals(program.source(),facts.path("completeExecutableInput").asText());
            assertEquals(program.sourceVersion(),facts.at("/publicationContractDiagnostic/expectedSourceVersion").asText());assertFalse(facts.path("publicationContractDiagnostic").has("completeReadonlySource"));assertFalse(facts.has("publicationRecoveryProgram"));assertFalse(facts.has("publicationRecoveryDiagnostic"));assertEquals(continuationPrior.receiptSha256(),facts.at("/publicationContractDiagnostic/binding/prior/receiptSha256").asText());assertFalse(facts.has("installedHelperPath"));
            if(attempt==0) {
                fixture.nativeReply(new AiToolCall("diagnostic-deny","submit_result",deny));
                var refusal=assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,()->DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED",refusal.problem().eventCode());assertTrue(grants.isEmpty());continue;
            }
            // Page the complete real launcher as well as stdin through the actual native review tools.
            for(int offset=0;offset<request.command().script().length();) {
                int end=Math.min(offset+4000,request.command().script().length());
                fixture.nativeReply(new AiToolCall("preflight-launcher-"+attempt+"-"+offset,"request",json.writeValueAsString(Map.of("part","script","offset",offset,"limit",end-offset))));offset=end;
            }
            for(int offset=0;offset<program.source().length();) {
                int end=Math.min(offset+4000,program.source().length());
                fixture.nativeReply(new AiToolCall("diagnostic-page-"+attempt+"-"+offset,"evidence",json.writeValueAsString(Map.of("pointer","/completeExecutableInput","offset",offset,"limit",end-offset))));offset=end;
            }
            fixture.nativeReply(new AiToolCall("diagnostic-approve-"+attempt,"submit_result",approve));
            assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()).outcome());
            assertEquals(attempt,grants.size());assertTrue(fixture.replies.isEmpty());
            var wrong=new java.util.HashSet<>(request.resourceTargets());wrong.remove(wrong.iterator().next());
            assertThrows(IllegalArgumentException.class,()->DeploymentAgentCommandProtocol.facts(RemoteActions.command(server,binding.continuation().original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram.OPERATION,wrong)),request.command(),layout));
        }
        assertEquals(2,grants.size());assertEquals(2,reviewedActions.size());
        assertEquals(program.sourceVersion(),json.readTree(fixture.requests.getFirst().messages().get(1).text()).at("/currentRequest/publicationContractDiagnostic/expectedSourceVersion").asText());
        // Existing native review fixture and synthetic host grants only; no SDK consumption, provider or SSH is exercised.
    }

    @Test
    void currentStateContinuationDeliversCompleteSourceAndRequiresFreshHostGrant()throws Exception {
        var layout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var program=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram(layout);
        var fixture=new NativeAiFixture();var json=new ObjectMapper();var server=new ServerId("original-host");
        var ui=(gold.debug.windowstolinux.appapi.ui.UiService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ui.UiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class},(p,m,a)->{
            if(m.getName().equals("environment"))return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH,"LIGHT",false);throw new AssertionError(m.getName());
        });
        var reviewContext=(gold.debug.windowstolinux.appapi.mod.ModContext)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.mod.ModContext.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class},(p,m,a)->{
            if(m.getName().equals("ui"))return ui;try{return m.invoke(fixture.context,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        var original=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(server.id(),fixture.task.id(),UUID.randomUUID(),UUID.randomUUID(),17,"a".repeat(64),"b".repeat(64),"backend","c".repeat(64),"backend-0123456789abcdef","d".repeat(64),layout.digest());
        var prior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.PriorPreflight(UUID.randomUUID(),30,38,"e".repeat(64),"f".repeat(64));
        var current=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.CurrentFingerprints("1".repeat(64),"2".repeat(64),"3".repeat(64),"4".repeat(64),"5".repeat(64),"6".repeat(64));
        var binding=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.Binding(original,18,"7".repeat(64),prior,current);
        var agent=new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(fixture.context,fixture.task,
            new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence(){
                public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding(){return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding(server.id(),"local-native-review-target","e".repeat(64));}
                public Map<String,gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources(){return Map.of();}
                public Object receipts(){return Map.of("events",List.of());}
            });
        var grants=new java.util.HashSet<UUID>();var reviewedActions=new java.util.HashSet<String>();
        var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
            if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("APP cannot be bypassed by host.approve");
            var intent=(gold.debug.windowstolinux.appapi.task.action.ActionIntent)a[0];
            @SuppressWarnings("unchecked") var supplied=(java.util.Optional<CommandRequest>)a[1];
            assertEquals(RemoteActions.command(server,intent.revision(),supplied.orElseThrow()).exactAction(),intent.exactAction());
            assertTrue(reviewedActions.add(intent.exactAction()));UUID id=UUID.randomUUID();assertTrue(grants.add(id));
            return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(id)),java.util.Optional.empty());
        });
        String approve="{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Local complete diagnostic review\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}";
        String deny="{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Local diagnostic denial\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[],\"discussionStatus\":\"CLOSED\"}";
        for(int attempt=0;attempt<3;attempt++)try(var request=program.request(binding,UUID.randomUUID())) {
            var intent=RemoteActions.command(server,binding.original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.OPERATION,request.resourceTargets()));
            var facts=json.valueToTree(DeploymentAgentCommandProtocol.facts(intent,request.command(),layout));
            assertEquals("EXECUTABLE_SCRIPT",facts.path("inputKind").asText());assertEquals(program.source(),facts.path("completeExecutableInput").asText());
            assertEquals(program.sourceVersion(),facts.at("/publicationRecoveryContinuation/expectedSourceVersion").asText());assertFalse(facts.path("publicationRecoveryContinuation").has("completeReadonlySource"));assertFalse(facts.has("publicationRecoveryProgram"));assertFalse(facts.has("publicationRecoveryDiagnostic"));assertEquals(prior.receiptSha256(),facts.at("/publicationRecoveryContinuation/binding/preflight/receiptSha256").asText());assertFalse(facts.has("installedHelperPath"));
            if(attempt==0) {
                fixture.nativeReply(new AiToolCall("diagnostic-deny","submit_result",deny));
                var refusal=assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,()->DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED",refusal.problem().eventCode());assertTrue(grants.isEmpty());continue;
            }
            // Page the complete real launcher as well as stdin through the actual native review tools.
            for(int offset=0;offset<request.command().script().length();) {
                int end=Math.min(offset+4000,request.command().script().length());
                fixture.nativeReply(new AiToolCall("preflight-launcher-"+attempt+"-"+offset,"request",json.writeValueAsString(Map.of("part","script","offset",offset,"limit",end-offset))));offset=end;
            }
            for(int offset=0;offset<program.source().length();) {
                int end=Math.min(offset+4000,program.source().length());
                fixture.nativeReply(new AiToolCall("diagnostic-page-"+attempt+"-"+offset,"evidence",json.writeValueAsString(Map.of("pointer","/completeExecutableInput","offset",offset,"limit",end-offset))));offset=end;
            }
            fixture.nativeReply(new AiToolCall("diagnostic-approve-"+attempt,"submit_result",approve));
            assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()).outcome());
            assertEquals(attempt,grants.size());assertTrue(fixture.replies.isEmpty());
            var wrong=new java.util.HashSet<>(request.resourceTargets());wrong.remove(wrong.iterator().next());
            assertThrows(IllegalArgumentException.class,()->DeploymentAgentCommandProtocol.facts(RemoteActions.command(server,binding.original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram.OPERATION,wrong)),request.command(),layout));
        }
        assertEquals(2,grants.size());assertEquals(2,reviewedActions.size());
        assertEquals(program.sourceVersion(),json.readTree(fixture.requests.getFirst().messages().get(1).text()).at("/currentRequest/publicationRecoveryContinuation/expectedSourceVersion").asText());
        // Existing native review fixture and synthetic host grants only; no SDK consumption, provider or SSH is exercised.
    }

    @Test
    void readonlyPublicationPreflightDeliversCompleteSourceAndRequiresFreshHostGrant()throws Exception {
        var layout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var program=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflightProgram(layout);
        var fixture=new NativeAiFixture();var json=new ObjectMapper();var server=new ServerId("original-host");
        var ui=(gold.debug.windowstolinux.appapi.ui.UiService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ui.UiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class},(p,m,a)->{
            if(m.getName().equals("environment"))return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH,"LIGHT",false);throw new AssertionError(m.getName());
        });
        var reviewContext=(gold.debug.windowstolinux.appapi.mod.ModContext)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.mod.ModContext.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class},(p,m,a)->{
            if(m.getName().equals("ui"))return ui;try{return m.invoke(fixture.context,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        var original=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(server.id(),fixture.task.id(),UUID.randomUUID(),UUID.randomUUID(),17,"a".repeat(64),"b".repeat(64),"backend","c".repeat(64),"backend-0123456789abcdef","d".repeat(64),layout.digest());
        var prior=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflightProgram.PriorDiagnostic(UUID.randomUUID(),20,28,"e".repeat(64),"f".repeat(64),18,"1".repeat(64));
        var binding=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflightProgram.Binding(original,prior);
        var agent=new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(fixture.context,fixture.task,
            new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence(){
                public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding(){return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding(server.id(),"local-native-review-target","e".repeat(64));}
                public Map<String,gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources(){return Map.of();}
                public Object receipts(){return Map.of("events",List.of());}
            });
        var grants=new java.util.HashSet<UUID>();var reviewedActions=new java.util.HashSet<String>();
        var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
            if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("APP cannot be bypassed by host.approve");
            var intent=(gold.debug.windowstolinux.appapi.task.action.ActionIntent)a[0];
            @SuppressWarnings("unchecked") var supplied=(java.util.Optional<CommandRequest>)a[1];
            assertEquals(RemoteActions.command(server,intent.revision(),supplied.orElseThrow()).exactAction(),intent.exactAction());
            assertTrue(reviewedActions.add(intent.exactAction()));UUID id=UUID.randomUUID();assertTrue(grants.add(id));
            return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(id)),java.util.Optional.empty());
        });
        String approve="{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Local complete diagnostic review\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}";
        String deny="{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Local diagnostic denial\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[],\"discussionStatus\":\"CLOSED\"}";
        for(int attempt=0;attempt<3;attempt++)try(var request=program.request(binding,UUID.randomUUID())) {
            var intent=RemoteActions.command(server,binding.original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION,request.resourceTargets()));
            var facts=json.valueToTree(DeploymentAgentCommandProtocol.facts(intent,request.command(),layout));
            assertEquals("EXECUTABLE_SCRIPT",facts.path("inputKind").asText());assertEquals(program.source(),facts.path("completeExecutableInput").asText());
            assertEquals(program.sourceVersion(),facts.at("/publicationRecoveryPreflight/expectedSourceVersion").asText());assertFalse(facts.path("publicationRecoveryPreflight").has("completeReadonlySource"));assertFalse(facts.has("publicationRecoveryProgram"));assertFalse(facts.has("publicationRecoveryDiagnostic"));assertEquals(prior.receiptSha256(),facts.at("/publicationRecoveryPreflight/binding/prior/receiptSha256").asText());assertFalse(facts.has("installedHelperPath"));
            if(attempt==0) {
                fixture.nativeReply(new AiToolCall("diagnostic-deny","submit_result",deny));
                var refusal=assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,()->DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED",refusal.problem().eventCode());assertTrue(grants.isEmpty());continue;
            }
            // Page the complete real launcher as well as stdin through the actual native review tools.
            for(int offset=0;offset<request.command().script().length();) {
                int end=Math.min(offset+4000,request.command().script().length());
                fixture.nativeReply(new AiToolCall("preflight-launcher-"+attempt+"-"+offset,"request",json.writeValueAsString(Map.of("part","script","offset",offset,"limit",end-offset))));offset=end;
            }
            for(int offset=0;offset<program.source().length();) {
                int end=Math.min(offset+4000,program.source().length());
                fixture.nativeReply(new AiToolCall("diagnostic-page-"+attempt+"-"+offset,"evidence",json.writeValueAsString(Map.of("pointer","/completeExecutableInput","offset",offset,"limit",end-offset))));offset=end;
            }
            fixture.nativeReply(new AiToolCall("diagnostic-approve-"+attempt,"submit_result",approve));
            assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()).outcome());
            assertEquals(attempt,grants.size());assertTrue(fixture.replies.isEmpty());
            var wrong=new java.util.HashSet<>(request.resourceTargets());wrong.remove(wrong.iterator().next());
            assertThrows(IllegalArgumentException.class,()->DeploymentAgentCommandProtocol.facts(RemoteActions.command(server,binding.original().planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION,wrong)),request.command(),layout));
        }
        assertEquals(2,grants.size());assertEquals(2,reviewedActions.size());
        assertEquals(program.sourceVersion(),json.readTree(fixture.requests.getFirst().messages().get(1).text()).at("/currentRequest/publicationRecoveryPreflight/expectedSourceVersion").asText());
        // Existing native review fixture and synthetic host grants only; no SDK consumption, provider or SSH is exercised.
    }

    @Test
    void readonlyPublicationDiagnosticDeliversCompleteSourceAndRequiresFreshHostGrant()throws Exception {
        var layout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var program=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram(layout);
        var fixture=new NativeAiFixture();var json=new ObjectMapper();var server=new ServerId("original-host");
        var ui=(gold.debug.windowstolinux.appapi.ui.UiService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ui.UiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ui.UiService.class},(p,m,a)->{
            if(m.getName().equals("environment"))return new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(java.util.Locale.ENGLISH,"LIGHT",false);throw new AssertionError(m.getName());
        });
        var reviewContext=(gold.debug.windowstolinux.appapi.mod.ModContext)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.mod.ModContext.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class},(p,m,a)->{
            if(m.getName().equals("ui"))return ui;try{return m.invoke(fixture.context,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
        });
        var binding=new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(server.id(),fixture.task.id(),UUID.randomUUID(),UUID.randomUUID(),17,"a".repeat(64),"b".repeat(64),"backend","c".repeat(64),"backend-0123456789abcdef","d".repeat(64),layout.digest());
        var agent=new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(fixture.context,fixture.task,
            new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence(){
                public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding(){return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding(server.id(),"local-native-review-target","e".repeat(64));}
                public Map<String,gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources(){return Map.of();}
                public Object receipts(){return Map.of("events",List.of());}
            });
        var grants=new java.util.HashSet<UUID>();var reviewedActions=new java.util.HashSet<String>();
        var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)java.lang.reflect.Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
            if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("APP cannot be bypassed by host.approve");
            var intent=(gold.debug.windowstolinux.appapi.task.action.ActionIntent)a[0];
            @SuppressWarnings("unchecked") var supplied=(java.util.Optional<CommandRequest>)a[1];
            assertEquals(RemoteActions.command(server,intent.revision(),supplied.orElseThrow()).exactAction(),intent.exactAction());
            assertTrue(reviewedActions.add(intent.exactAction()));UUID id=UUID.randomUUID();assertTrue(grants.add(id));
            return new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,java.util.Optional.of(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(id)),java.util.Optional.empty());
        });
        String approve="{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Local complete diagnostic review\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}";
        String deny="{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Local diagnostic denial\",\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[],\"discussionStatus\":\"CLOSED\"}";
        for(int attempt=0;attempt<3;attempt++)try(var request=program.request(binding,UUID.randomUUID())) {
            var intent=RemoteActions.command(server,binding.planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION,request.resourceTargets()));
            var facts=json.valueToTree(DeploymentAgentCommandProtocol.facts(intent,request.command(),layout));
            assertEquals("EXECUTABLE_SCRIPT",facts.path("inputKind").asText());assertEquals(program.source(),facts.path("completeExecutableInput").asText());
            assertEquals(program.sourceVersion(),facts.at("/publicationRecoveryDiagnostic/expectedSourceVersion").asText());assertFalse(facts.path("publicationRecoveryDiagnostic").has("completeReadonlySource"));assertFalse(facts.has("publicationRecoveryProgram"));assertFalse(facts.has("installedHelperPath"));
            if(attempt==0) {
                fixture.nativeReply(new AiToolCall("diagnostic-deny","submit_result",deny));
                var refusal=assertThrows(gold.debug.windowstolinux.appapi.task.result.ApiException.class,()->DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()));
                assertEquals("DEPLOYMENT_COMMAND_REVIEW_DENIED",refusal.problem().eventCode());assertTrue(grants.isEmpty());continue;
            }
            for(int offset=0;offset<program.source().length();) {
                int end=Math.min(offset+4000,program.source().length());
                fixture.nativeReply(new AiToolCall("diagnostic-page-"+attempt+"-"+offset,"evidence",json.writeValueAsString(Map.of("pointer","/completeExecutableInput","offset",offset,"limit",end-offset))));offset=end;
            }
            fixture.nativeReply(new AiToolCall("diagnostic-approve-"+attempt,"submit_result",approve));
            assertEquals(gold.debug.windowstolinux.appapi.task.result.TaskResult.SUCCEEDED,DeploymentFixedCommandApproval.approve(reviewContext,host,intent,request.command(),fixture.task,"fixture-route","AUTOMATIC",false,agent,DeploymentCurrentCommandEvidence.NONE,true,agent.commandProtocol()).outcome());
            assertEquals(attempt,grants.size());assertTrue(fixture.replies.isEmpty());
            var wrong=new java.util.HashSet<>(request.resourceTargets());wrong.remove(wrong.iterator().next());
            assertThrows(IllegalArgumentException.class,()->DeploymentAgentCommandProtocol.facts(RemoteActions.command(server,binding.planSha256(),request.command()).withSemantics(new ActionSemantics(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION,wrong)),request.command(),layout));
        }
        assertEquals(2,grants.size());assertEquals(2,reviewedActions.size());
        assertEquals(program.sourceVersion(),json.readTree(fixture.requests.getFirst().messages().get(1).text()).at("/currentRequest/publicationRecoveryDiagnostic/expectedSourceVersion").asText());
        // Existing native review fixture and synthetic host grants only; no SDK consumption, provider or SSH is exercised.
    }

    @Test
    void publicationRecoveryFitsActualStandaloneAndAgentNativeRequestEnvelopes() throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var program = new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram(layout);
        var server = new gold.debug.windowstolinux.appapi.server.model.ServerId("original-host");
        var unit = gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity
            .forManaged("backend", server, "a".repeat(64), layout);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var codec = gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec.forProtocol(
            gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS);
        var fixture = new NativeAiFixture();
        var agent = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(
            fixture.context, fixture.task,
            new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence() {
                public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding() {
                    return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding(
                        server.id(), "verified-test-target", "b".repeat(64));
                }

                public Map<String, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources() {
                    return Map.of();
                }

                public Object receipts() {
                    return Map.of("events", List.of());
                }
            });
        String instruction = agent.responseInstructions()
            + gold.debug.windowstolinux.mod.deployment.ai.skill.DeploymentSkillResources.text(
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentFixedCommandApproval.class, "command-review.md")
            + "\n" + gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.REVIEW_CONTRACT;
        String denial = "{\"decision\":\"DENY\",\"requiresUserConfirmation\":false,\"reason\":\"Local capacity fixture\","
            + "\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[],\"discussionStatus\":\"CLOSED\"}";
        UUID action = UUID.randomUUID();
        var commands = List.of(program.readiness(unit, fixture.task.id(), action), program.install(unit, fixture.task.id(), action),
            program.guardedFirstRollback(unit, fixture.task.id(), UUID.randomUUID(), "backend-0123456789abcdef",
                "c".repeat(64), "d".repeat(64), action));
        try {
            for (var command : commands) {
                var intent = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, "d".repeat(64), command.command())
                    .withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(command.operation(), command.resourceTargets()));
                var facts = new LinkedHashMap<String, Object>(DeploymentAgentCommandProtocol.facts(intent, command.command(), layout));
                facts.put("authorizationPolicy", "AUTOMATIC");
                facts.put("effectSummary", intent.effectSummary());
                facts.put("presentationLocale", "zh-CN");
                // Explicit extra capacity fixture, not fabricated live Plan/status evidence.
                facts.put("currentCommandEvidence", Map.of("authority", "NONE", "capacityFixture", "x".repeat(16384)));
                fixture.nativeReply(new AiToolCall("standalone-" + fixture.requests.size(), "submit_result", denial));
                new DeploymentAiJson(fixture.context).askCommand(instruction, facts, "fixture-route", fixture.task, node -> false);
                var standalone = fixture.requests.getLast();
                assertEquals(json.valueToTree(facts), json.readTree(standalone.messages().getLast().text()));
                fixture.nativeReply(new AiToolCall("agent-" + fixture.requests.size(), "submit_result", denial));
                assertEquals("DENY", agent.review(instruction, facts, intent, command.command(), "fixture-route", fixture.task)
                    .decision().decision());
                var nativeRequest = fixture.requests.getLast();
                var current = json.readTree(nativeRequest.messages().get(1).text());
                var source = current.at("/currentRequest/publicationRecoveryProgram/completeDecodedSource");
                assertEquals(program.manifest().get("sourceSha256"), source.path("sha256").asText());
                assertEquals(program.source().length(), source.path("characters").intValue());
                for (var captured : List.of(standalone, nativeRequest)) {
                    byte[] encoded = codec.encode("deepseek-flash", captured, 8192,
                        gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.NONE, Map.of());
                    assertTrue(encoded.length <= captured.maxRequestBytes());
                    assertEquals(1048576, captured.maxRequestBytes());
                    System.out.println("PUBLICATION_RECOVERY_ENVELOPE operation=" + command.operation()
                        + " mode=" + (captured == standalone ? "standalone" : "agent") + " bytes=" + encoded.length);
                    Arrays.fill(encoded, (byte) 0);
                }
            }
        } finally {
            commands.forEach(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.Request::close);
        }
        assertEquals(6, fixture.requests.size(), "Only local captured requests, with no host or remote capability");
    }

    @Test
    void publicationRecoveryReviewIncludesCompleteDecodedFixedProgramAndRejectsChangedInput() throws Exception {
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var program = new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram(layout);
        var unit = gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity
            .forManaged("backend", new ServerId("original-host"), "a".repeat(64), layout);
        UUID task = UUID.randomUUID(), action = UUID.randomUUID();
        var requests = java.util.List.of(program.readiness(unit, task, action), program.install(unit, task, action),
            program.guardedFirstRollback(unit, task, UUID.randomUUID(), "backend-0123456789abcdef", "b".repeat(64), "c".repeat(64), action));
        try {
            for (var request : requests) {
                var intent = RemoteActions.command(unit.server(), "d".repeat(64), request.command())
                    .withSemantics(new ActionSemantics(request.operation(), request.resourceTargets()));
                assertFalse(DeploymentFixedCommandApproval.registered(intent));
                var facts = DeploymentAgentCommandProtocol.facts(intent, request.command(), layout);
                var node = new ObjectMapper().valueToTree(facts);
                assertEquals("EXECUTABLE_SCRIPT", node.path("inputKind").asText());
                if (request.operation().equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.INSTALL)) {
                    assertEquals(program.installer(), gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram
                        .reconstructInstallationEvidence(node.path("completeExecutableInput")));
                    assertEquals("/completeExecutableInput/derived/controller", node.at("/publicationRecoveryProgram/completeDecodedSource/pointer").asText());
                    assertEquals("/completeExecutableInput/sources/loader", node.at("/publicationRecoveryProgram/completeLoaderSource/pointer").asText());
                    assertFalse(node.at("/publicationRecoveryProgram/completeDecodedSource").isTextual());
                    assertFalse(node.at("/publicationRecoveryProgram/completeLoaderSource").isTextual());
                    assertEquals(program.loader(), node.at("/completeExecutableInput/sources/loader").textValue());
                    assertTrue(node.at("/completeExecutableInput/sources/bash").textValue().contains("rollback_deployment_first"));
                } else if (request.operation().equals(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.ROLLBACK)) {
                    var view = node.at("/publicationRecoveryProgram/sourceView");
                    gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram
                        .verifyRollbackSourceEvidence(view, program.source(), program.loader());
                    assertFalse(node.at("/publicationRecoveryProgram/completeDecodedSource").isTextual());
                    assertFalse(node.at("/publicationRecoveryProgram/completeLoaderSource").isTextual());
                    assertFalse(view.has("segments"));
                    assertEquals("", node.path("completeExecutableInput").asText());
                } else {
                    assertEquals(program.source(), node.at("/publicationRecoveryProgram/completeDecodedSource").asText());
                    assertEquals(program.loader(), node.at("/publicationRecoveryProgram/completeLoaderSource").asText());
                    assertFalse(node.path("publicationRecoveryProgram").has("sourceView"));
                    assertEquals("", node.path("completeExecutableInput").asText());
                }
                byte[] json = new ObjectMapper().writeValueAsBytes(facts);
                assertTrue(json.length < 1048576); // Actual facts; final provider envelope is checked by its normal codec.
                var foreign = intent.withSemantics(new ActionSemantics(request.operation(),
                    Set.of(new ActionTarget(unit.server().id(), "task", UUID.randomUUID().toString()))));
                assertThrows(IllegalArgumentException.class, () -> DeploymentAgentCommandProtocol.facts(foreign, request.command(), layout));
                try (var changed = new CommandRequest(request.command().script(), "changed".getBytes(StandardCharsets.UTF_8),
                    request.command().timeout(), request.command().outputLimitBytes())) {
                    assertThrows(IllegalArgumentException.class, () -> DeploymentAgentCommandProtocol.facts(intent, changed, layout));
                }
            }
        } finally {
            requests.forEach(gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.Request::close);
        }
        publicationInstallationUsesActualNativeDeliveryGate(layout);
    }

    private static void publicationInstallationUsesActualNativeDeliveryGate(
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout) throws Exception {
        var json = new ObjectMapper(); var fixture = new NativeAiFixture();
        var server = new ServerId("original-host");
        var program = new gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram(layout);
        var unit = gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity
            .forManaged("backend", server, "a".repeat(64), layout);
        var agent = new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval(
            fixture.context, fixture.task, new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Evidence() {
                public gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding binding() {
                    return new gold.debug.windowstolinux.mod.deployment.service.agent.review.DeploymentAgentApproval.Binding(
                        server.id(), "verified-test-target", "b".repeat(64));
                }
                public Map<String, gold.debug.windowstolinux.mod.deployment.file.DeploymentSourceBrowser> sources() { return Map.of(); }
                public Object receipts() { return Map.of("events", List.of()); }
            });
        String approve = "{\"decision\":\"APPROVE\",\"requiresUserConfirmation\":false,\"reason\":\"Local delivery fixture\","
            + "\"missingEvidence\":[],\"adjustments\":[],\"candidateCommands\":[]}";
        try (var command = program.install(unit, fixture.task.id(), UUID.randomUUID())) {
            var intent = RemoteActions.command(server, "d".repeat(64), command.command())
                .withSemantics(new ActionSemantics(command.operation(), command.resourceTargets()));
            var facts = DeploymentAgentCommandProtocol.facts(intent, command.command(), layout);
            var structure = json.valueToTree(program.installationEvidence());
            var leaves = List.of("/sources/bash", "/sources/loader", "/derived/controller/segments/0/text",
                "/derived/controller/segments/2/text", "/segments/0/text", "/segments/2/text", "/segments/4/text");
            fixture.nativeReply(new AiToolCall("premature", "submit_result", approve));
            int calls = 0, reducedPages = 0;
            for (String pointer : leaves) {
                String body = structure.at(pointer).textValue();
                for (int start = 0; start < body.length();) {
                    int end = Math.min(body.length(), start + 8000);
                    String wrapped;
                    while (true) {
                        var page = Map.of("offset", start, "next", end, "total", body.length(), "hasMore", end < body.length(),
                            "sha256", gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram.sha256(body),
                            "text", body.substring(start, end));
                        wrapped = json.writeValueAsString(Map.of("trust", "UNTRUSTED_DATA", "reviewBinding", "a".repeat(64), "value", page));
                        if (wrapped.length() <= 14000) break;
                        if (end == Math.min(body.length(), start + 8000)) reducedPages++;
                        end = start + (end - start) / 2; assertTrue(end > start);
                    }
                    fixture.nativeReply(new AiToolCall("source-page-" + calls++, "evidence", json.writeValueAsString(
                        Map.of("pointer", "/completeExecutableInput" + pointer, "offset", start, "limit", end - start))));
                    start = end;
                }
            }
            fixture.nativeReply(new AiToolCall("complete", "submit_result", approve));
            // Pair the fixture's opaque handles with measured Chat Completions assistant states.
            var continuationStates = new LinkedHashMap<UUID, String>();
            var preparedReplies = new java.util.ArrayList<>(fixture.replies);
            fixture.replies.clear();
            for (var prepared : preparedReplies) {
                var response = prepared.value().orElseThrow();
                var state = json.createObjectNode().put("role", "assistant").put("content", response.text());
                var wireCalls = state.putArray("tool_calls");
                for (var call : response.toolCalls()) wireCalls.addObject().put("id", call.id()).put("type", "function")
                    .putObject("function").put("name", call.name()).put("arguments", call.arguments());
                String encodedState = json.writeValueAsString(state);
                var handle = new gold.debug.windowstolinux.appapi.ai.model.AiContinuation(UUID.randomUUID(),
                    encodedState.getBytes(StandardCharsets.UTF_8).length);
                continuationStates.put(handle.id(), encodedState);
                var boundResponse = new gold.debug.windowstolinux.appapi.ai.model.AiResponse(response.text(), response.modelId(),
                    response.finishReason(), response.usage(), response.toolCalls(), java.util.Optional.of(handle));
                fixture.replies.add(new gold.debug.windowstolinux.appapi.task.result.OperationResult<>(prepared.outcome(),
                    java.util.Optional.of(boundResponse), prepared.problem()));
            }
            var decision = agent.review(agent.responseInstructions(), facts, intent, command.command(), "fixture-route", fixture.task);
            assertEquals("APPROVE", decision.decision().decision());
            assertEquals(calls + 2, fixture.requests.size(), "Premature APPROVE must not bypass executable delivery");
            assertTrue(fixture.requests.stream().flatMap(r -> r.messages().stream()).flatMap(m -> m.toolResult().stream())
                .anyMatch(r -> r.text().contains("EXECUTABLE_INPUT_NOT_DELIVERED")));
            var last = fixture.requests.getLast();
            assertFalse(last.messages().stream().flatMap(m -> m.toolResult().stream()).anyMatch(r -> r.callId().equals("source-page-0")),
                "Delivery accumulation is not a claim that old pages remain visible or understood");
            assertTrue(fixture.replies.isEmpty());
            var codec = gold.debug.windowstolinux.app.ai.model.ModelProtocolCodec.forProtocol(
                gold.debug.windowstolinux.app.ai.model.ModelProtocol.OPENAI_CHAT_COMPLETIONS);
            for (var captured : fixture.requests) {
                byte[] encoded = codec.encode("deepseek-flash", captured, 8192,
                    gold.debug.windowstolinux.app.ai.model.ModelReasoningLevel.NONE, continuationStates);
                assertTrue(encoded.length <= captured.maxRequestBytes(), "Actual whole native page request must fit the unchanged transport cap");
                Arrays.fill(encoded, (byte) 0);
            }
            // NativeAiFixture has no host/grant capability; this is only the independent review result.
            System.out.println("PUBLICATION_INSTALL_EVIDENCE_PAGES calls=" + calls + " reducedFrom8000=" + reducedPages
                + " currentHistoryOmitsFirstSourcePage=true hostAuthority=NONE");
        }
    }

    private static final String PREFIX = "'" + DeploymentManagedHelperBundle.PATH + "' prepare-toolchains 'bind' '";

    @Test
    void modelReceiptStatusFactsBindExactRequestAndOriginalCheckpoint() throws Exception {
        var json = new ObjectMapper();
        UUID taskId = UUID.randomUUID(), operation = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        var server = new ServerId("receipt-host");
        String revision = "a".repeat(64), body = "b".repeat(64), marker = "PASSWORD_SENTINEL_NEVER_RETAIN";
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var original = json.createObjectNode().put("version", 1).put("task", taskId.toString()).put("server", server.id())
            .put("fingerprint", "verified-host").put("modelRevision", revision);
        original.putArray("commands").addObject().put("operation", operation.toString()).put("body", body).put("seconds", 10).put("phase", "DISPATCHED");
        var snapshot = new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot[]{
            new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(2, original.toString(), false)};
        var checkpoint = new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint() {
            public Snapshot snapshot() {
                return snapshot[0];
            }

            public Snapshot advance(Snapshot expected, String payload) {
                throw new AssertionError("Read evidence cannot advance state");
            }

            public void close() {
                throw new AssertionError("Read evidence cannot release state");
            }
        };
        var task = (gold.debug.windowstolinux.appapi.task.run.TaskContext) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.task.run.TaskContext.class},
            (p0, method, args) -> {
                assertEquals("id", method.getName());
                return taskId;
            });
        var tasks = (gold.debug.windowstolinux.appapi.task.TaskService) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.task.TaskService.class}, (p0, method, args) -> {
                assertEquals("findCheckpoint", method.getName());
                assertSame(task, args[0]);
                return gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.KEY.equals(args[1])
                    ? java.util.Optional.of(checkpoint) : java.util.Optional.empty();
            });
        var context = (gold.debug.windowstolinux.appapi.mod.ModContext) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{gold.debug.windowstolinux.appapi.mod.ModContext.class},
            (p0, method, args) -> {
                assertEquals("tasks", method.getName());
                return tasks;
            });
        String script = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity
            .receiptStatus(taskId, operation, body, layout);
        var targets = Set.of(new ActionTarget(server.id(), "task", taskId.toString()));
        try (var request = new CommandRequest(script, new byte[0], Duration.ofSeconds(30), 1024)) {
            var intent = RemoteActions.command(server, revision, request).withSemantics(new ActionSemantics("deployment.model.command.status.v1", targets));
            var facts = json.valueToTree(DeploymentCommandEvidence.facts(intent, request, layout, context, task));
            var receipt = facts.path("modelCommandReceiptStatus");
            assertEquals(taskId.toString(), receipt.path("task").asText());
            assertEquals(operation.toString(), receipt.path("operation").asText());
            assertEquals(body, receipt.path("bodySha256").asText());
            assertEquals(revision, receipt.path("businessRevision").asText());
            assertEquals(layout.digest(), receipt.path("layoutSha256").asText());
            assertEquals("DISPATCHED", receipt.path("localPhase").asText());
            assertFalse(receipt.path("remoteObserved").asBoolean());
            assertEquals("NONE", receipt.path("authority").asText());
            assertEquals("EMPTY", facts.path("inputKind").asText());
            assertFalse(facts.has("modelCommand"));
            assertFalse(facts.has("completeExecutableInput"));
            assertTrue(receipt.at("/outputContract/output").asText().contains("arbitrary receipt bytes and hex are never output"));
            for (String invalid : java.util.List.of("INTENT", "ENDED", "FOREIGN_TASK", "RELEASED", "BODY", "SERVER", "REVISION", "NONCANONICAL_OPERATION", "DUPLICATE", "MALFORMED")) {
                var changed = original.deepCopy();
                boolean released = false;
                switch (invalid) {
                    case "INTENT" ->
                        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("commands").get(0)).put("phase", "INTENT");
                    case "ENDED" ->
                        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("commands").get(0)).put("phase", "ENDED").put("exit", "0");
                    case "FOREIGN_TASK" -> changed.put("task", UUID.randomUUID().toString());
                    case "RELEASED" -> released = true;
                    case "BODY" ->
                        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("commands").get(0)).put("body", "c".repeat(64));
                    case "SERVER" -> changed.put("server", "other-host");
                    case "REVISION" -> changed.put("modelRevision", "c".repeat(64));
                    case "NONCANONICAL_OPERATION" ->
                        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("commands").get(0))
                            .put("operation", operation.toString().toUpperCase(java.util.Locale.ROOT));
                    case "DUPLICATE" ->
                        ((com.fasterxml.jackson.databind.node.ArrayNode) changed.path("commands")).add(changed.path("commands").get(0).deepCopy());
                    default -> {
                    }
                }
                snapshot[0] = new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(3,
                    invalid.equals("MALFORMED") ? "{\"password\":\"" + marker : changed.toString(), released);
                var failure = assertThrows(java.io.IOException.class, () -> DeploymentCommandEvidence.facts(intent, request, layout, context, task), invalid);
                assertEquals("Original model command receipt binding is invalid", failure.getMessage());
                assertNull(failure.getCause());
                assertFalse(failure.toString().contains(marker));
            }
            snapshot[0] = new gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint.Snapshot(2, original.toString(), false);
            var foreign = intent.withSemantics(new ActionSemantics("deployment.model.command.status.v1",
                Set.of(new ActionTarget(server.id(), "task", UUID.randomUUID().toString()))));
            assertThrows(java.io.IOException.class, () -> DeploymentCommandEvidence.facts(foreign, request, layout, context, task));
            for (int change = 0; change < 5; change++) {
                try (var changed = new CommandRequest(change == 0 ? script + "\nprintf extra" : script,
                    change == 1 ? marker.getBytes(StandardCharsets.UTF_8) : new byte[0],
                    Duration.ofSeconds(change == 2 ? 31 : 30), change == 3 ? 1025 : 1024)) {
                    var changedIntent = RemoteActions.command(server, change == 4 ? "c".repeat(64) : revision, changed)
                        .withSemantics(new ActionSemantics("deployment.model.command.status.v1", targets));
                    assertThrows(java.io.IOException.class, () -> DeploymentCommandEvidence.facts(changedIntent, changed, layout, context, task));
                }
            }
            var different = new gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout(
                new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths("/srv/receipt/lib", "/srv/receipt/apps",
                    "/srv/receipt/state", "/srv/receipt/config", "/srv/receipt/data"));
            assertThrows(java.io.IOException.class, () -> DeploymentCommandEvidence.facts(intent, request, different, context, task));
        }
    }

    @Test
    void versionedControllerInstallerKeepsCompleteExecutableInputAndReadinessHasEmptyStdin() throws Exception {
        var json = new ObjectMapper();
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        for (String phase : java.util.List.of("readiness", "install")) {
            var current = gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainProgramStoreTest.current(
                phase, UUID.randomUUID().toString(), "a".repeat(32), java.util.List.of(), layout);
            String body = current.path("completeExecutableInput").asText("");
            try (var request = new CommandRequest(current.path("exactAction").asText().split("\\nscript:\\n", 2)[1], body.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60), 65536)) {
                var targets = new java.util.HashSet<ActionTarget>();
                for (var item : current.path("resourceTargets"))
                    targets.add(json.treeToValue(item, ActionTarget.class));
                var intent = RemoteActions.command(new ServerId("original-server"), "c".repeat(64), request).withSemantics(new ActionSemantics(current.path("operation").asText(), targets));
                var facts = DeploymentAgentCommandProtocol.facts(intent, request);
                assertEquals(current.path("inputKind").asText(), facts.get("inputKind"));
                assertEquals(intent.exactAction(), facts.get("exactAction"));
                if (phase.equals("install")) assertEquals(body, facts.get("completeExecutableInput"));
                else assertFalse(facts.containsKey("completeExecutableInput"));
            }
        }
    }

    @Test
    void ordinaryInstalledRequestsDropAtLeastNinetyPercentWithoutChangingActualBytes() throws Exception {
        var json = new ObjectMapper();
        for (String suffix : java.util.List.of("", "\nprintf 'additional visible command'\n")) {
            String command = DeploymentManagedHelperBundle.verifiedCommand("'" + DeploymentManagedHelperBundle.PATH + "' build-preflight 'ordinary'" + suffix);
            try (var request = new CommandRequest(command, new byte[0], Duration.ofSeconds(30), 8192)) {
                var action = RemoteActions.command(new ServerId("test-server"), "frozen", request).withSemantics(new ActionSemantics(
                    "deployment.build.preflight.v1", Set.of(new ActionTarget("test-server", "deployment-build", "/fixture/candidate"))));
                var compact = DeploymentCommandEvidence.facts(action, request);
                var old = new java.util.LinkedHashMap<>(compact);
                old.put("installedHelperScript", DeploymentManagedHelperBundle.renderScript());
                int before = json.writeValueAsBytes(old).length + 8000;
                int after = json.writeValueAsBytes(compact).length + 8000
                    + gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract.text().getBytes(StandardCharsets.UTF_8).length;
                assertTrue(after <= before / 10, "Compact review including fixed policy must reduce bytes by at least 90%");
                assertEquals(action.exactAction(), compact.get("exactAction"));
                assertEquals(command, request.script());
                if (!suffix.isEmpty())
                    assertTrue(compact.get("exactAction").toString().contains("additional visible command"));
                System.out.println("APPROVAL_INPUT_SIZE operation=build-preflight beforeBytes=" + before + " afterBytes=" + after);
            }
        }
    }

    @Test
    void installerAndLargeExecutableStdinRemainCompleteAndTargetSetsAreSorted() throws Exception {
        var server = new ServerId("test-server");
        var workspace = new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace("demo", "a".repeat(64));
        String installer = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.render();
        for (var input : java.util.List.of(installer, "printf 'review actual dynamic body'\n".repeat(4000))) {
            boolean installing = input.equals(installer);
            String script = installing ? "exec /bin/bash --noprofile --norc -s"
                : DeploymentManagedHelperBundle.verifiedCommand(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol.script(
                workspace,
                60, 16, 256, 8192, "ordinary", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8))), "-", java.util.List.of(java.util.List.of("marker"))));
            try (var request = new CommandRequest(script, input.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30), 8192)) {
                var action = RemoteActions.command(server, "frozen", request).withSemantics(new ActionSemantics(
                    installing ? "deployment.helper.install.v1" : "deployment.build.run.v1", Set.of(
                    new ActionTarget(server.id(), "deployment-build", "z"), new ActionTarget(server.id(), "deployment-build", "a"),
                    new ActionTarget(server.id(), "deployment-build", workspace.candidateRoot()))));
                var facts = new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(action, request));
                if (installing) {
                    gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.verify(facts.path("completeExecutableInput"), input);
                    assertTrue(new ObjectMapper().writeValueAsBytes(facts.path("completeExecutableInput")).length < new ObjectMapper().writeValueAsBytes(input).length);
                } else assertEquals(input, facts.path("completeExecutableInput").asText());
                assertEquals(workspace.candidateRoot(), facts.at("/resourceTargets/0/id").asText());
                assertEquals("a", facts.at("/resourceTargets/1/id").asText());
                assertEquals("z", facts.at("/resourceTargets/2/id").asText());
                if (!installing) {
                    var missingCandidate = action.withSemantics(new ActionSemantics("deployment.build.run.v1", Set.of(
                        new ActionTarget(server.id(), "deployment-build", "a"), new ActionTarget(server.id(), "deployment-build", "z"))));
                    assertThrows(IllegalArgumentException.class, () -> DeploymentCommandEvidence.facts(missingCandidate, request));
                }
                assertFalse(facts.has("installedHelperScript"));
                System.out.println("APPROVAL_RETAINED_INPUT kind=" + (installing ? "INSTALLER" : "LARGE_DYNAMIC")
                    + " executableBytes=" + input.getBytes(StandardCharsets.UTF_8).length);
            }
        }
    }

    @Test
    void installedHelperEvidenceRejectsMissingOrChangedIntegrityGuard() {
        String invocation = "'" + DeploymentManagedHelperBundle.PATH + "' build-preflight 'ordinary'";
        String current = DeploymentManagedHelperBundle.verifiedCommand(invocation);
        for (String script : java.util.List.of(invocation, current.replace(DeploymentManagedHelperBundle.EXPECTED_SHA256, "a".repeat(64)))) {
            try (var request = new CommandRequest(script, new byte[0], Duration.ofSeconds(30), 8192)) {
                var actual = RemoteActions.command(new ServerId("test-server"), "frozen", request).withSemantics(new ActionSemantics(
                    "deployment.build.preflight.v1", Set.of(new ActionTarget("test-server", "deployment-build", DeploymentManagedHelperBundle.PATH))));
                assertThrows(IllegalArgumentException.class, () -> DeploymentCommandEvidence.facts(actual, request));
            }
        }
    }

    @Test
    void trackedInstallerEvidenceReconstructsEveryByteAndRejectsTampering() throws Exception {
        var json = new ObjectMapper();
        String input = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript.renderTracked(
            UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), "b".repeat(64));
        var valid = json.valueToTree(gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.describe(input));
        assertArrayEquals(input.getBytes(StandardCharsets.UTF_8),
            gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.reconstruct(valid).getBytes(StandardCharsets.UTF_8));
        for (String alteration : java.util.List.of("source", "literal", "missing", "order")) {
            var changed = valid.deepCopy();
            switch (alteration) {
                case "source" ->
                    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("sources")).put("managedHelper", "changed");
                case "literal" ->
                    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("segments").get(0)).put("text", "printf 'changed'\n");
                case "missing" -> ((com.fasterxml.jackson.databind.node.ArrayNode) changed.path("segments")).remove(2);
                case "order" ->
                    ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("segments").get(1)).put("source", "buildEntry");
            }
            assertThrows(IllegalArgumentException.class, () -> gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.verify(changed, input));
        }
        assertThrows(IllegalArgumentException.class, () -> gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentInstallerEvidence.describe(input.replace("helper_tmp=$", "altered_tmp=$")));
        System.out.println("APPROVAL_LOSSLESS_INSTALLER executableBytes=" + input.getBytes(StandardCharsets.UTF_8).length
            + " oldJsonBytes=" + json.writeValueAsBytes(input).length + " orderedJsonBytes=" + json.writeValueAsBytes(valid).length);
    }

    @Test
    void modelBodyIsSeparatedOnlyAfterExactEnvelopeAndTaskValidation() throws Exception {
        UUID task = UUID.randomUUID(), operation = UUID.randomUUID();
        String modelBody = "builtin printf 'actual model body'";
        String bodySha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(modelBody.getBytes(StandardCharsets.UTF_8)));
        var renderer = gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.class
            .getDeclaredMethod("envelope", UUID.class, UUID.class, String.class, String.class, String.class);
        renderer.setAccessible(true);
        String executable = (String) renderer.invoke(null, task, operation, bodySha, modelBody, "Observed prerequisite gap");
        var server = new ServerId("test-server");
        for (String launcher : java.util.List.of(gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.LAUNCHER,
            "exec /bin/bash --noprofile --norc -s")) {
            try (var request = new CommandRequest(launcher, executable.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60), 8192)) {
                var intent = RemoteActions.command(server, "frozen", request).withSemantics(new ActionSemantics(
                    "deployment.model.command.v1", Set.of(new ActionTarget(server.id(), "task", task.toString()))));
                if (!launcher.equals(gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity.LAUNCHER)) {
                    assertThrows(IllegalArgumentException.class, () -> DeploymentCommandEvidence.facts(intent, request));
                    continue;
                }
                var facts = new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request));
                assertEquals(modelBody, facts.at("/modelCommand/modelScript").asText());
                assertEquals(bodySha, facts.at("/modelCommand/bodySha256").asText());
                assertEquals(executable, facts.path("completeExecutableInput").asText());
                assertEquals("Observed prerequisite gap", facts.at("/modelCommand/rationale").asText());
                var changedTask = intent.withSemantics(new ActionSemantics("deployment.model.command.v1",
                    Set.of(new ActionTarget(server.id(), "task", UUID.randomUUID().toString()))));
                assertThrows(IllegalArgumentException.class, () -> DeploymentCommandEvidence.facts(changedTask, request));
            }
        }
    }

    @Test
    void decodedBindingPreservesExactPatchVersionAndProvenanceWithoutClaimingLiveVerification() throws Exception {
        String source = "https://example.test/official-jdk.tar.gz";
        String payload = "WTL-TOOLS-1\tcurrent\nJAVA\tRUNTIME\tMjE=\tcG9tLnhtbA==\tSERIES\t21\t21.0.12.1+1\t"
            + "/usr/local/lib/windowstolinux/toolchains/versions/java-" + "a".repeat(64) + "\tMANAGED\t"
            + Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8)) + "\t" + "b".repeat(64) + "\n";
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(PREFIX + encoded + "'"),
            new byte[0], Duration.ofSeconds(30), 8192)) {
            var intent = intent(request);
            var facts = new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request));
            assertEquals(intent.exactAction(), facts.path("exactAction").asText());
            var binding = facts.path("decodedToolchainBinding");
            assertEquals("current", binding.path("catalogRevision").asText());
            assertEncodingEvidence(binding, encoded, payload);
            assertTrue(binding.path("bindingSha256").asText().matches("[a-f0-9]{64}"));
            var tool = binding.path("toolchains").get(0);
            assertEquals("21.0.12.1+1", tool.path("version").asText());
            assertEquals(source, tool.path("source").asText());
            assertEquals("b".repeat(64), tool.path("sha256").asText());
            assertFalse(binding.has("verified"));
            assertEquals("EMPTY", facts.path("inputKind").asText());
            String serialized = new ObjectMapper().writeValueAsString(facts);
            assertFalse(facts.has("installedHelperScript"));
            assertFalse(facts.has("installedBuildEntryScript"));
            assertEquals("managed-helper-review.v2", facts.path("platformContractRevision").asText());
        }
    }

    @Test
    void buildCarriesTheExactReadableBindingAndRejectsTrailingShell() throws Exception {
        String payload = "WTL-TOOLS-1\tcurrent\nDOTNET\tBUILD\tMTAuMC4zMDA=\tZ2xvYmFsLmpzb24=\tEXACT\t10.0.300\t10.0.300\t"
            + "/usr/local/lib/windowstolinux/toolchains/versions/dotnet-" + "a".repeat(64) + "\tMANAGED\t"
            + Base64.getEncoder().encodeToString("https://example.test/official-sdk.tar.gz".getBytes(StandardCharsets.UTF_8))
            + "\t" + "b".repeat(64) + "\n";
        String encoded = Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String body = "printf approved\n";
        String bodySha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        var workspace = new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace("demo", "a".repeat(64));
        String script = gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildOutputProtocol.script(
            workspace,
            60, 16, 256, 8192, "ordinary", bodySha, encoded, java.util.List.of(java.util.List.of("marker")));
        var server = new ServerId("test-server");
        for (String suffix : java.util.List.of("", "; printf changed")) {
            try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script + suffix), body.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60), 8192)) {
                var intent = RemoteActions.command(server, "frozen", request).withSemantics(new ActionSemantics("deployment.build.run.v1",
                    Set.of(new ActionTarget(server.id(), "deployment-build", workspace.candidateRoot()))));
                if (!suffix.isEmpty()) {
                    assertThrows(IllegalArgumentException.class, () -> DeploymentCommandEvidence.facts(intent, request));
                    continue;
                }
                var facts = new ObjectMapper().valueToTree(DeploymentCommandEvidence.facts(intent, request));
                assertEquals("10.0.300", facts.at("/decodedToolchainBinding/toolchains/0/version").asText());
                assertEquals("DOTNET", facts.at("/decodedToolchainBinding/toolchains/0/ecosystem").asText());
                assertEncodingEvidence(facts.path("decodedToolchainBinding"), encoded, payload);
                assertEquals("printf approved\n", facts.path("completeExecutableInput").asText());
                assertFalse(facts.path("decodedToolchainBinding").has("verified"));
            }
        }
        try (var wrongDigest = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script.replace(bodySha, "c".repeat(64))),
            body.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(60), 8192)) {
            var intent = RemoteActions.command(server, "frozen", wrongDigest).withSemantics(new ActionSemantics("deployment.build.run.v1",
                Set.of(new ActionTarget(server.id(), "deployment-build", workspace.candidateRoot()))));
            assertThrows(IllegalArgumentException.class, () -> DeploymentCommandEvidence.facts(intent, wrongDigest));
        }
    }

    @Test
    void malformedOrNonliteralBindingNeverProducesReadableTrustedEvidence() {
        String[] scripts = {PREFIX + "e30='; echo other", PREFIX + "e30='", PREFIX + "/////w=='"};
        for (int index = 0; index < scripts.length; index++) {
            String script = scripts[index];
            try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(script), new byte[0], Duration.ofSeconds(30), 8192)) {
                Class<? extends Exception> expected = index == 2 ? java.nio.charset.CharacterCodingException.class : IllegalArgumentException.class;
                assertThrows(expected, () -> DeploymentCommandEvidence.facts(intent(request), request));
            }
        }
    }

    @Test
    void noncanonicalBase64NeverProducesEvidenceEvenWhenDecodedBindingIsValid() throws Exception {
        String payload = "WTL-TOOLS-1\tcurrent\nJAVA\tRUNTIME\tMjE=\tcG9tLnhtbA==\tSERIES\t21\t21.0.12.1+1\t"
            + "/usr/local/lib/windowstolinux/toolchains/versions/java-" + "a".repeat(64) + "\tMANAGED\t"
            + Base64.getEncoder().encodeToString("https://example.test/jdk.tar.gz".getBytes(StandardCharsets.UTF_8))
            + "\t" + "b".repeat(64) + "\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(bytes);
        assertTrue(encoded.endsWith("="), "Fixture must exercise padding");
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int last = encoded.indexOf('=') - 1;
        String changedPadBits = encoded.substring(0, last)
            + alphabet.charAt(alphabet.indexOf(encoded.charAt(last)) ^ 1) + encoded.substring(last + 1);
        try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(PREFIX + encoded + "'"), new byte[0], Duration.ofSeconds(30), 8192)) {
            DeploymentCommandEvidence.facts(intent(request), request);
        }
        for (String altered : java.util.List.of(encoded.replace("=", ""), changedPadBits)) {
            assertArrayEquals(bytes, Base64.getDecoder().decode(altered));
            try (var request = new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(PREFIX + altered + "'"), new byte[0], Duration.ofSeconds(30), 8192)) {
                assertThrows(IllegalArgumentException.class, () -> DeploymentCommandEvidence.facts(intent(request), request));
            }
        }
    }

    private static void assertEncodingEvidence(com.fasterxml.jackson.databind.JsonNode binding, String encoded, String payload) {
        assertEquals("BASE64", binding.path("encoding").asText());
        assertEquals(encoded.length(), binding.path("encodedCharacters").asInt());
        int decodedBytes = payload.getBytes(StandardCharsets.UTF_8).length;
        assertEquals(decodedBytes, binding.path("decodedBytes").asInt());
        assertEquals(4 * ((decodedBytes + 2) / 3), binding.path("encodedCharacters").asInt());
        assertEquals(0, binding.path("encodedCharacters").asInt() % 4);
    }

    private static gold.debug.windowstolinux.appapi.task.action.ActionIntent intent(CommandRequest request) {
        var server = new ServerId("test-server");
        return RemoteActions.command(server, "frozen", request).withSemantics(new ActionSemantics("deployment.toolchain.bind.v1",
            Set.of(new ActionTarget(server.id(), "deployment-build", "/usr/local/lib/windowstolinux/toolchains"))));
    }
}
