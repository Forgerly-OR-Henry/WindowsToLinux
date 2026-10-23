package gold.debug.windowstolinux.mod.deployment.db.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import gold.debug.windowstolinux.app.main.start.PlatformRuntime;
import gold.debug.windowstolinux.appapi.db.ModDatabase;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.ref.ResourceRefResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseIdentity;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseOrigin;
import gold.debug.windowstolinux.mod.deployment.db.release.DeploymentReleaseStore;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentHistoryArchive;
import gold.debug.windowstolinux.mod.deployment.service.core.application.DeploymentApplicationReader;
import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentRecovery;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCompensation;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationObservation;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryInstallation;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryProgram;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecovery;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflight;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnostic;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowup;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrection;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationReconsideration;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationCorrectionProgram;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticFollowupProgram;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContractDiagnosticProgram;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuation;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationContinuationProgram;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryPreflightProgram;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnostic;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPublicationRecoveryDiagnosticProgram;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentOriginalPublishStatus;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentOriginalPublishCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.DeploymentResourceBindings;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real H2/CLASS transaction fixtures; no model or SSH peer is started. / 真实H2/CLASS事务夹具，不启动模型或SSH替身。 */
class DeploymentAgentHistoryArchiveTest extends DeploymentAgentArchiveFixture {
    @ParameterizedTest
    @ValueSource(strings={"identity","source-version","pending-intent","reused-grant"})
    void readonlyDiagnosticRejectsChangedOriginalBindingAndReusedGrant(String change)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());
                originalPublication(runtime,task,false);var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);
                var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.empty(),Optional.of(DeploymentAgentPublicationCompensation.failure())),false);
                var oldResult=log.events(task).getLast();
                assertTrue(DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).isEmpty(),"Original baseline is not a later observation");
                compensationObservation(context,task,true);
                var eligible=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                var unit=new DeploymentExecutionStore(context.db()).publicationTemplate(new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow()).orElseThrow().graph().components().getFirst().unit();
                var program=new DeploymentAgentPublicationRecoveryDiagnosticProgram(unit.layout());
                try(var request=program.request(diagnosticBinding(eligible),UUID.randomUUID())) {
                    var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(unit.server(),plan.sha256(),request.command())
                        .withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION,request.resourceTargets()));
                    var diagnostic=DeploymentAgentPublicationRecoveryDiagnostic.open(task,log,eligible,request,intent);
                    if(change.equals("reused-grant")) {
                        diagnostic.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Local diagnostic fixture"),"5".repeat(64));
                        var before=log.events(task);assertThrows(Exception.class,()->diagnostic.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(old.grant())));assertEquals(before,log.events(task));diagnostic.retainInterrupted();
                    } else {
                        var before=log.events(task);var forged=new ArrayList<>(before);var last=forged.getLast();var node=DeploymentJsonDocument.parse(last.detail());
                        switch(change){case "identity"->node.put("task",UUID.randomUUID().toString());case "source-version"->node.put("sourceVersion","0".repeat(64));case "pending-intent"->node.put("pendingIntent",old.event()+1);default->throw new AssertionError(change);}
                        forged.set(forged.size()-1,new DeploymentAgentActionLog.Event(last.id(),last.type(),last.action(),DeploymentJsonDocument.encode(node)));
                        assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.read(forged,task.id()));assertEquals(before,log.events(task));diagnostic.retainInterrupted();
                    }
                }
                assertEquals(Optional.of(old),DeploymentAgentPublicationCompensation.read(log.events(task),task.id()).pending());assertTrue(log.events(task).contains(oldResult));assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                compensationObservation(context,task,false);DeploymentAgentPublicationCompensation.observedOnly(task,log,old);
                completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"refused-followup","denied-request","interrupted-request"})
    void correctionRequiresCompleteFollowupAndConsumesDeniedOrInterruptedRequest(String scenario)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());
                var layout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(),task,layout);
                originalPublication(runtime,task,false);var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);
                var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.empty(),Optional.of(DeploymentAgentPublicationCompensation.failure())),false);
                var oldResult=log.events(task).getLast();compensationObservation(context,task,true);
                var original=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                appendReadyPreflightFixture(task,log,original,layout);appendUnknownContinuationFixture(task,log,layout);
                compensationObservation(context,task,true);appendContractControlFixture(task,log,layout,true);
                compensationObservation(context,task,true);var authority=appendObservedFollowupFixture(task,log,layout,!scenario.equals("refused-followup"));
                var immutable=List.copyOf(log.events(task));
                assertTrue(DeploymentAgentPublicationCorrection.eligibility(DeploymentAgentPublicationCompensation.read(immutable,task.id()),immutable).isEmpty());
                compensationObservation(context,task,true);
                var selected=DeploymentAgentPublicationCorrection.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task));
                if(scenario.equals("refused-followup"))assertTrue(selected.isEmpty());
                else {
                    var eligible=selected.orElseThrow();var program=new DeploymentAgentPublicationCorrectionProgram(layout);
                    UUID historicalAction=UUID.fromString(log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryDiagnostic.EVENT)).findFirst().orElseThrow().action());
                    try(var reused=program.request(DeploymentAgentPublicationCorrection.binding(eligible),historicalAction)) {
                        var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(plan.server()),plan.sha256(),reused.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationCorrectionProgram.OPERATION,reused.resourceTargets()));
                        var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationCorrection.open(task,log,eligible,reused,intent));assertEquals(before,log.events(task));
                    }
                    try(var request=program.request(DeploymentAgentPublicationCorrection.binding(eligible),UUID.randomUUID())) {
                        var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(plan.server()),plan.sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationCorrectionProgram.OPERATION,request.resourceTargets()));
                        var durable=DeploymentAgentPublicationCorrection.open(task,log,eligible,request,intent);
                        var before=log.events(task);
                        for(String field:List.of("task","sourceVersion","prior","continuationRequest")) {
                            var forged=new ArrayList<>(before);var event=forged.getLast();var node=DeploymentJsonDocument.parse(event.detail());
                            if(field.equals("task"))node.put(field,UUID.randomUUID().toString());else if(field.equals("continuationRequest"))node.put(field,1);else node.put(field,"0".repeat(64));
                            forged.set(forged.size()-1,new DeploymentAgentActionLog.Event(event.id(),event.type(),event.action(),DeploymentJsonDocument.encode(node)));
                            assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.read(forged,task.id()));assertEquals(before,log.events(task));
                        }
                        boolean interrupted=scenario.equals("interrupted-request");
                        durable.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision(interrupted?"APPROVE":"DENY",false,"Local correction decision"),"5".repeat(64));
                        if(interrupted) {
                            var beforeGrant=log.events(task);assertThrows(Exception.class,()->durable.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(authority.get("grant"))));assertEquals(beforeGrant,log.events(task));
                            durable.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID()));durable.dispatch();
                        }
                        durable.retainInterrupted();assertTrue(DeploymentAgentPublicationCorrection.attempted(log.events(task),eligible));
                        var attempted=log.events(task);try(var again=program.request(DeploymentAgentPublicationCorrection.binding(eligible),UUID.randomUUID())) {
                            var next=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(plan.server()),plan.sha256(),again.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationCorrectionProgram.OPERATION,again.resourceTargets()));
                            assertThrows(Exception.class,()->DeploymentAgentPublicationCorrection.open(task,log,eligible,again,next));assertEquals(attempted,log.events(task));
                        }
                        assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.observedOnly(task,log,old));assertEquals(attempted,log.events(task));
                    }
                }
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());assertTrue(state.verified().isEmpty());
                assertEquals(immutable,log.events(task).subList(0,immutable.size()));assertTrue(log.events(task).contains(oldResult));assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                compensationObservation(context,task,false);DeploymentAgentPublicationCompensation.observedOnly(task,log,old);completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));return success();
            }, java.time.Duration.ofSeconds(60));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"identity","source-version","prior-receipt","old-result","old-observation","reused-grant","reused-action","repeat","non-control"})
    void contractFollowupKeepsOldCodecAndRejectsChangedOrConsumedEvidence(String change)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());
                var layout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(),task,layout);
                originalPublication(runtime,task,false);var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);
                var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.empty(),Optional.of(DeploymentAgentPublicationCompensation.failure())),false);
                var oldResult=log.events(task).getLast();compensationObservation(context,task,true);
                var original=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                var prior=appendReadyPreflightFixture(task,log,original,layout);appendUnknownContinuationFixture(task,log,layout);compensationObservation(context,task,true);
                var authority=appendContractControlFixture(task,log,layout,!change.equals("non-control"));var immutable=List.copyOf(log.events(task));
                assertEquals(Optional.of(old),DeploymentAgentPublicationCompensation.read(immutable,task.id()).pending());
                assertTrue(DeploymentAgentPublicationContractDiagnosticFollowup.eligibility(DeploymentAgentPublicationCompensation.read(immutable,task.id()),immutable).isEmpty());
                if(!change.equals("old-observation"))compensationObservation(context,task,true);
                var selected=DeploymentAgentPublicationContractDiagnosticFollowup.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task));
                if(Set.of("old-observation","non-control").contains(change))assertTrue(selected.isEmpty());
                else {
                    var eligible=selected.orElseThrow();var program=new DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout);
                    UUID action=change.equals("reused-action")?UUID.fromString(log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryDiagnostic.EVENT)).findFirst().orElseThrow().action()):UUID.randomUUID();
                    try(var request=program.request(DeploymentAgentPublicationContractDiagnosticFollowup.binding(eligible),action)) {
                        var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(plan.server()),plan.sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationContractDiagnosticFollowupProgram.OPERATION,request.resourceTargets()));
                        if(change.equals("reused-action")){var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationContractDiagnosticFollowup.open(task,log,eligible,request,intent));assertEquals(before,log.events(task));}
                        else {
                            var durable=DeploymentAgentPublicationContractDiagnosticFollowup.open(task,log,eligible,request,intent);
                            if(change.equals("reused-grant")) {
                                durable.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Local followup review"),"5".repeat(64));
                                var before=log.events(task);assertThrows(Exception.class,()->durable.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(authority.get("grant"))));assertEquals(before,log.events(task));
                            } else if(change.equals("repeat")) {
                                var before=log.events(task);try(var again=program.request(DeploymentAgentPublicationContractDiagnosticFollowup.binding(eligible),UUID.randomUUID())) {
                                    var next=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(plan.server()),plan.sha256(),again.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationContractDiagnosticFollowupProgram.OPERATION,again.resourceTargets()));
                                    assertThrows(Exception.class,()->DeploymentAgentPublicationContractDiagnosticFollowup.open(task,log,eligible,again,next));assertEquals(before,log.events(task));
                                }
                            } else {
                                var before=log.events(task);var forged=new ArrayList<>(before);var event=forged.getLast();var node=DeploymentJsonDocument.parse(event.detail());
                                if(change.equals("identity"))node.put("task",UUID.randomUUID().toString());
                                else if(change.equals("source-version"))node.put("sourceVersion","0".repeat(64));
                                else if(change.equals("prior-receipt"))((com.fasterxml.jackson.databind.node.ObjectNode)node.path("binding").path("prior")).put("receiptSha256","0".repeat(64));
                                else if(change.equals("old-result"))((com.fasterxml.jackson.databind.node.ObjectNode)node.path("binding").path("diagnostic").path("continuation")).put("pendingResultSha256","0".repeat(64));
                                else throw new AssertionError(change);
                                forged.set(forged.size()-1,new DeploymentAgentActionLog.Event(event.id(),event.type(),event.action(),DeploymentJsonDocument.encode(node)));
                                assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.read(forged,task.id()));assertEquals(before,log.events(task));
                            }
                            durable.retainInterrupted();assertTrue(DeploymentAgentPublicationContractDiagnosticFollowup.attempted(log.events(task),eligible));
                            var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.observedOnly(task,log,old));assertEquals(before,log.events(task));
                        }
                    }
                }
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());assertTrue(state.verified().isEmpty());
                assertEquals(immutable,log.events(task).subList(0,immutable.size()));assertTrue(log.events(task).contains(oldResult));assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                compensationObservation(context,task,false);DeploymentAgentPublicationCompensation.observedOnly(task,log,old);completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"identity","source-version","prior-receipt","old-result","reused-grant","reused-action","repeat","old-observation"})
    void contractDiagnosticRequiresNewObservationAndRejectsChangedHistory(String change)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());
                var capturedLayout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(),task,capturedLayout);
                originalPublication(runtime,task,false);var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);
                var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.empty(),Optional.of(DeploymentAgentPublicationCompensation.failure())),false);
                var originalResult=log.events(task).getLast();compensationObservation(context,task,true);
                var original=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                var unit=new DeploymentExecutionStore(context.db()).publicationTemplate(new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow()).orElseThrow().graph().components().getFirst().unit();
                var prior=appendReadyPreflightFixture(task,log,original,unit.layout());
                appendUnknownContinuationFixture(task,log,unit.layout());
                assertTrue(DeploymentAgentPublicationContractDiagnostic.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).isEmpty());
                compensationObservation(context,task,true);
                var v1=log.events(task).stream().filter(e->Set.of(DeploymentAgentPublicationRecoveryDiagnostic.EVENT,DeploymentAgentPublicationRecoveryPreflight.EVENT,DeploymentAgentPublicationContinuation.EVENT).contains(e.type())).toList();
                var selected=DeploymentAgentPublicationContractDiagnostic.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task));
                {
                    var eligible=selected.orElseThrow();var program=new DeploymentAgentPublicationContractDiagnosticProgram(unit.layout());
                    try(var request=program.request(DeploymentAgentPublicationContractDiagnostic.binding(eligible),change.equals("reused-action")?UUID.fromString(v1.getFirst().action()):UUID.randomUUID())) {
                        var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(unit.server(),plan.sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationContractDiagnosticProgram.OPERATION,request.resourceTargets()));
                        if(change.equals("reused-action")){var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationContractDiagnostic.open(task,log,eligible,request,intent));assertEquals(before,log.events(task));}
                        else {
                            var preflight=DeploymentAgentPublicationContractDiagnostic.open(task,log,eligible,request,intent);
                            if(change.equals("old-observation")) {
                                var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.observedOnly(task,log,old));assertEquals(before,log.events(task));
                            }else if(change.equals("reused-grant")) {
                                preflight.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Local preflight fixture"),"5".repeat(64));
                                var before=log.events(task);assertThrows(Exception.class,()->preflight.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(prior.get("grant"))));assertEquals(before,log.events(task));
                            }else if(change.equals("repeat")) {
                                var before=log.events(task);try(var again=program.request(DeploymentAgentPublicationContractDiagnostic.binding(eligible),UUID.randomUUID())) {
                                    var next=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(unit.server(),plan.sha256(),again.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationContractDiagnosticProgram.OPERATION,again.resourceTargets()));
                                    assertThrows(Exception.class,()->DeploymentAgentPublicationContractDiagnostic.open(task,log,eligible,again,next));assertEquals(before,log.events(task));
                                }
                            }else {
                                var before=log.events(task);var forged=new ArrayList<>(before);var event=forged.getLast();var node=DeploymentJsonDocument.parse(event.detail());
                                if(change.equals("identity"))node.put("task",UUID.randomUUID().toString());
                                else if(change.equals("source-version"))node.put("sourceVersion","0".repeat(64));
                                else if(change.equals("prior-receipt"))((com.fasterxml.jackson.databind.node.ObjectNode)node.path("binding").path("prior")).put("receiptSha256","0".repeat(64));
                                else if(change.equals("old-result"))((com.fasterxml.jackson.databind.node.ObjectNode)node.path("binding").path("continuation")).put("pendingResultSha256","0".repeat(64));
                                else throw new AssertionError(change);
                                forged.set(forged.size()-1,new DeploymentAgentActionLog.Event(event.id(),event.type(),event.action(),DeploymentJsonDocument.encode(node)));
                                assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.read(forged,task.id()));assertEquals(before,log.events(task));
                            }
                            preflight.retainInterrupted();
                        }
                    }
                }
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());long barrier=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationContractDiagnostic.EVENT)).reduce((a,b)->b).map(DeploymentAgentActionLog.Event::id).orElse(v1.getLast().id());assertEquals(barrier,state.lastActionEvent());
                assertEquals(v1,log.events(task).stream().filter(e->Set.of(DeploymentAgentPublicationRecoveryDiagnostic.EVENT,DeploymentAgentPublicationRecoveryPreflight.EVENT,DeploymentAgentPublicationContinuation.EVENT).contains(e.type())).toList());assertTrue(log.events(task).contains(originalResult));assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                compensationObservation(context,task,false);DeploymentAgentPublicationCompensation.observedOnly(task,log,old);completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"identity","source-version","prior-receipt","current-fingerprint","old-result","reused-grant","reused-action","repeat","old-observation"})
    void continuationRejectsChangedCurrentStateAndReusedAuthority(String change)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());
                originalPublication(runtime,task,false);var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);
                var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.empty(),Optional.of(DeploymentAgentPublicationCompensation.failure())),false);
                var originalResult=log.events(task).getLast();compensationObservation(context,task,true);
                var original=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                var unit=new DeploymentExecutionStore(context.db()).publicationTemplate(new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow()).orElseThrow().graph().components().getFirst().unit();
                var prior=appendReadyPreflightFixture(task,log,original,unit.layout());
                var v1=log.events(task).stream().filter(e->Set.of(DeploymentAgentPublicationRecoveryDiagnostic.EVENT,DeploymentAgentPublicationRecoveryPreflight.EVENT).contains(e.type())).toList();
                var selected=DeploymentAgentPublicationContinuation.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task));
                {
                    var eligible=selected.orElseThrow();var program=new DeploymentAgentPublicationContinuationProgram(unit.layout());
                    try(var request=program.request(DeploymentAgentPublicationContinuation.binding(eligible),change.equals("reused-action")?UUID.fromString(v1.getFirst().action()):UUID.randomUUID())) {
                        var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(unit.server(),plan.sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationContinuationProgram.OPERATION,request.resourceTargets()));
                        if(change.equals("reused-action")){var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationContinuation.open(task,log,eligible,request,intent));assertEquals(before,log.events(task));}
                        else {
                            var preflight=DeploymentAgentPublicationContinuation.open(task,log,eligible,request,intent);
                            if(change.equals("old-observation")) {
                                var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.observedOnly(task,log,old));assertEquals(before,log.events(task));
                            }else if(change.equals("reused-grant")) {
                                preflight.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Local preflight fixture"),"5".repeat(64));
                                var before=log.events(task);assertThrows(Exception.class,()->preflight.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(prior.get("grant"))));assertEquals(before,log.events(task));
                            }else if(change.equals("repeat")) {
                                var before=log.events(task);try(var again=program.request(DeploymentAgentPublicationContinuation.binding(eligible),UUID.randomUUID())) {
                                    var next=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(unit.server(),plan.sha256(),again.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationContinuationProgram.OPERATION,again.resourceTargets()));
                                    assertThrows(Exception.class,()->DeploymentAgentPublicationContinuation.open(task,log,eligible,again,next));assertEquals(before,log.events(task));
                                }
                            }else {
                                var before=log.events(task);var forged=new ArrayList<>(before);var event=forged.getLast();var node=DeploymentJsonDocument.parse(event.detail());
                                if(change.equals("identity"))node.put("task",UUID.randomUUID().toString());
                                else if(change.equals("source-version"))node.put("sourceVersion","0".repeat(64));
                                else if(change.equals("prior-receipt"))((com.fasterxml.jackson.databind.node.ObjectNode)node.path("binding").path("preflight")).put("receiptSha256","0".repeat(64));
                                else if(change.equals("current-fingerprint"))((com.fasterxml.jackson.databind.node.ObjectNode)node.path("binding").path("current")).put("unitSha256","0".repeat(64));
                                else if(change.equals("old-result"))((com.fasterxml.jackson.databind.node.ObjectNode)node.path("binding")).put("pendingResultSha256","0".repeat(64));
                                else throw new AssertionError(change);
                                forged.set(forged.size()-1,new DeploymentAgentActionLog.Event(event.id(),event.type(),event.action(),DeploymentJsonDocument.encode(node)));
                                assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.read(forged,task.id()));assertEquals(before,log.events(task));
                            }
                            preflight.retainInterrupted();
                        }
                    }
                }
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());long barrier=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationContinuation.EVENT)).reduce((a,b)->b).map(DeploymentAgentActionLog.Event::id).orElse(originalResult.id());assertEquals(barrier,state.lastActionEvent());
                assertEquals(v1,log.events(task).stream().filter(e->Set.of(DeploymentAgentPublicationRecoveryDiagnostic.EVENT,DeploymentAgentPublicationRecoveryPreflight.EVENT).contains(e.type())).toList());assertTrue(log.events(task).contains(originalResult));assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                compensationObservation(context,task,false);DeploymentAgentPublicationCompensation.observedOnly(task,log,old);completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"identity","source-version","prior-receipt","reused-grant","reused-action","repeat","legacy-incomplete","legacy-not-env-failure"})
    void preflightRejectsChangedHistoryIdentityAndReusedAuthority(String change)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());
                originalPublication(runtime,task,false);var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);
                var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.empty(),Optional.of(DeploymentAgentPublicationCompensation.failure())),false);
                var originalResult=log.events(task).getLast();compensationObservation(context,task,true);
                var original=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                var unit=new DeploymentExecutionStore(context.db()).publicationTemplate(new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow()).orElseThrow().graph().components().getFirst().unit();
                String legacy=change.equals("legacy-incomplete")?"INCOMPLETE":change.equals("legacy-not-env-failure")?"NOT_ENV_FAILURE":"ENV_FAIL";
                var prior=appendV1DiagnosticFixture(task,log,original,unit.layout(),legacy);
                var v1=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryDiagnostic.EVENT)).toList();
                var selected=DeploymentAgentPublicationRecoveryPreflight.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task));
                if(!legacy.equals("ENV_FAIL"))assertTrue(selected.isEmpty());
                else {
                    var eligible=selected.orElseThrow();var program=new DeploymentAgentPublicationRecoveryPreflightProgram(unit.layout());
                    try(var request=program.request(preflightBinding(eligible),change.equals("reused-action")?prior.get("action"):UUID.randomUUID())) {
                        var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(unit.server(),plan.sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION,request.resourceTargets()));
                        if(change.equals("reused-action")){var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationRecoveryPreflight.open(task,log,eligible,request,intent));assertEquals(before,log.events(task));}
                        else {
                            var preflight=DeploymentAgentPublicationRecoveryPreflight.open(task,log,eligible,request,intent);
                            if(change.equals("reused-grant")) {
                                preflight.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Local preflight fixture"),"5".repeat(64));
                                var before=log.events(task);assertThrows(Exception.class,()->preflight.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(prior.get("grant"))));assertEquals(before,log.events(task));
                            }else if(change.equals("repeat")) {
                                var before=log.events(task);try(var again=program.request(preflightBinding(eligible),UUID.randomUUID())) {
                                    var next=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(unit.server(),plan.sha256(),again.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION,again.resourceTargets()));
                                    assertThrows(Exception.class,()->DeploymentAgentPublicationRecoveryPreflight.open(task,log,eligible,again,next));assertEquals(before,log.events(task));
                                }
                            }else {
                                var before=log.events(task);var forged=new ArrayList<>(before);var event=forged.getLast();var node=DeploymentJsonDocument.parse(event.detail());
                                if(change.equals("identity"))node.put("task",UUID.randomUUID().toString());
                                else if(change.equals("source-version"))node.put("sourceVersion","0".repeat(64));
                                else if(change.equals("prior-receipt"))((com.fasterxml.jackson.databind.node.ObjectNode)node.path("binding").path("prior")).put("receiptSha256","0".repeat(64));
                                else throw new AssertionError(change);
                                forged.set(forged.size()-1,new DeploymentAgentActionLog.Event(event.id(),event.type(),event.action(),DeploymentJsonDocument.encode(node)));
                                assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.read(forged,task.id()));assertEquals(before,log.events(task));
                            }
                            preflight.retainInterrupted();
                        }
                    }
                }
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());assertEquals(originalResult.id(),state.lastActionEvent());
                assertEquals(v1,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryDiagnostic.EVENT)).toList());assertTrue(log.events(task).contains(originalResult));assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                compensationObservation(context,task,false);DeploymentAgentPublicationCompensation.observedOnly(task,log,old);completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));return success();
            });
        }
    }

    /** Authentic original TaskWork/journal; APP model and host/SSH are local capability peers, not real remote acceptance. */
    @ParameterizedTest
    @ValueSource(strings={"complete","unknown","timeout","goal-already"})
    void correctionEveryResultRetainsUnknownUntilFreshGoalAndIndependentCleanup(String scenario)throws Exception {
        correctionScenario(scenario);
    }

    @Test
    void correctionDenialRetainsRedactedReasonWithoutGrantOrReplay()throws Exception {
        correctionScenario("deny");
    }

    @Test
    void reconsiderationHumanRefusalConsumesAdmissionWithoutApp()throws Exception {correctionScenario("reconsider-refuse");}

    @Test
    void reconsiderationCancellationConsumesAdmissionWithoutApp()throws Exception {correctionScenario("reconsider-cancel");}

    @Test
    void reconsiderationConfirmedDenialRetainsUnknownWithoutGrant()throws Exception {correctionScenario("reconsider-app-deny");}

    @Test
    void reconsiderationConfirmedApprovalNeedsFreshGrantAndLaterGoal()throws Exception {correctionScenario("reconsider-approve");}

    @Test
    void reconsiderationConfirmedGoalSkipsCorrectionMutation()throws Exception {correctionScenario("reconsider-goal");}

    private void correctionScenario(String scenario)throws Exception {
        boolean reconsiderationCase=scenario.startsWith("reconsider-");
        boolean initiallyDenied=scenario.equals("deny")||reconsiderationCase;
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                long fixtureStarted=System.nanoTime();
                java.util.function.Consumer<String> fixturePhase=name->{System.out.println("CORRECTION_FIXTURE_PHASE scenario="+scenario+" phase="+name+" elapsedNanos="+(System.nanoTime()-fixtureStarted));try{task.progress("Correction fixture "+name,Optional.empty());}catch(gold.debug.windowstolinux.appapi.task.result.ApiException unavailable){throw new AssertionError("Fixture phase could not be recorded",unavailable);}};
                fixturePhase.accept("TASK_WORK_BEGIN");
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());var store=new DeploymentExecutionStore(context.db());
                var server=new ServerId("server");String fingerprint="local-diagnostic-peer";
                var capturedLayout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(),task,capturedLayout);
                originalPublication(runtime,task,false,DeploymentAgentInputs.hash(server.id()+":"+fingerprint),DeploymentAgentInputs.targetProfile(context.servers().get(server,task)));
                var formal=store.findForTask(task).orElseThrow();var template=store.publicationTemplate(formal).orElseThrow();var unit=template.graph().components().getFirst().unit();
                assertEquals(capturedLayout,gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.original(context,task,log.find(task).orElseThrow().identity(),log.events(task)));
                var installedProgram=new DeploymentAgentPublicationRecoveryProgram(unit.layout());
                var installation=DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"b".repeat(64),installedProgram);
                try(var ready=installedProgram.readiness(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,ready);installation.result(ready.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"));}
                try(var install=installedProgram.install(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,install);installation.result(install.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),install.action().toString().replace("-","")));}
                var installationFrames=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList();
                var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(70),"PUBLICATION_RECOVERY_FAILURE=UNKNOWN\n","",false)),Optional.of(new Problem(ProblemCode.IO_FAILURE,"SSH_COMMAND_NONZERO","Local rollback peer returned a nonzero exit",Map.of(),Optional.empty()))),false);
                var oldResult=log.events(task).getLast();compensationObservation(context,task,true);
                var eligible=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                appendReadyPreflightFixture(task,log,eligible,unit.layout());
                appendUnknownContinuationFixture(task,log,unit.layout());
                compensationObservation(context,task,true);appendContractControlFixture(task,log,unit.layout(),true);
                compensationObservation(context,task,true);appendObservedFollowupFixture(task,log,unit.layout(),true);
                var immutableOld=List.copyOf(log.events(task));fixturePhase.accept("OLD_IMMUTABLE_HISTORY_READY");
                var program=new DeploymentAgentPublicationCorrectionProgram(unit.layout());
                var correctionBinding=new AtomicReference<DeploymentAgentPublicationCorrectionProgram.Binding>();
                String denialReason="Correction denied: bounded public reason\npassword=LOCAL_PASSWORD_SENTINEL\napi_key=LOCAL_API_KEY_SENTINEL\n"+"Retained explanation. ".repeat(60);
                var deniedExactAction=new AtomicReference<String>();
                var allowGoal=new java.util.concurrent.atomic.AtomicBoolean(scenario.equals("goal-already"));
                var reviews=new java.util.concurrent.atomic.AtomicInteger();var grants=new java.util.concurrent.atomic.AtomicInteger();var sends=new java.util.concurrent.atomic.AtomicInteger();
                var humanQuestions=new java.util.concurrent.atomic.AtomicInteger();var reconsiderationReviews=new java.util.concurrent.atomic.AtomicInteger();var correctionGrants=new java.util.concurrent.atomic.AtomicInteger();
                var granted=new AtomicReference<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant>();var expectedCommand=new AtomicReference<String>();var diagnosticAction=new AtomicReference<UUID>();var correctionSends=new java.util.concurrent.atomic.AtomicInteger();var cleanups=new java.util.concurrent.atomic.AtomicInteger();var uniqueGrants=new java.util.HashSet<UUID>();
                var ai=(gold.debug.windowstolinux.appapi.ai.AiService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ai.AiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class},(p,m,a)->{if(m.getName().equals("configurationRevision"))return "b".repeat(64);throw new AssertionError("No provider call in local diagnostic fixture");});
                var reviewedContext=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},(p,m,a)->m.getName().equals("ai")?ai:invoke(m,context,a));
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel model=(instructions,facts,intent,command,revision,supplied)->{
                    assertTrue(supplied==task);reviews.incrementAndGet();
                    var node=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts);
                    if(node.has("publicationCorrection")) {
                        assertEquals(plan.sha256(),intent.revision());
                        assertEquals(program.source(),node.path("completeExecutableInput").asText());assertEquals(program.sourceVersion(),node.at("/publicationCorrection/expectedSourceVersion").asText());
                        assertEquals(old.event(),node.at("/publicationCorrection/binding/followup/diagnostic/continuation/original/pendingIntentEvent").longValue());
                        diagnosticAction.set(UUID.fromString(node.at("/publicationCorrection/action").asText()));expectedCommand.set(command.script());
                        deniedExactAction.set(DeploymentAgentInputs.hash(intent.exactAction()));
                        try(var decoded=program.decode(command)){correctionBinding.set(decoded.binding());assertEquals(old.event(),decoded.binding().followup().diagnostic().continuation().original().pendingIntentEvent());}
                    }
                    boolean reconsidered=node.path("currentCommandEvidence").has("publicationReconsideration");
                    if(reconsidered) {
                        reconsiderationReviews.incrementAndGet();assertEquals(1,humanQuestions.get());
                        assertTrue(node.path("currentCommandEvidence").path("publicationReconsideration").isObject());
                    }
                    boolean denied=node.has("publicationCorrection")&&(reconsidered?scenario.equals("reconsider-app-deny"):initiallyDenied);
                    return new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel.Result(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision(denied?"DENY":"APPROVE",false,denied?denialReason:"Local diagnostic decision"),Map.of());
                };
                var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
                    if(m.getName().equals("requestText")) {
                        assertTrue(a[3]==task);assertEquals(false,a[2]);assertEquals(1,humanQuestions.incrementAndGet());
                        var question=DeploymentAgentPublicationReconsideration.validateQuestion(task.id(),(String)a[0],(String)a[1]);
                        assertEquals(task.id(),question.task());assertTrue(question.questionSha256().matches("[0-9a-f]{64}"));
                        assertEquals(0,correctionGrants.get());assertEquals(0,correctionSends.get());
                        assertEquals(3,reviews.get());assertEquals(2,grants.get());assertEquals(2,sends.get());
                        if(scenario.equals("reconsider-refuse"))return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(DeploymentAgentPublicationReconsideration.ALLOW+" "),Optional.empty());
                        if(scenario.equals("reconsider-goal"))allowGoal.set(true);
                        if(scenario.equals("reconsider-app-deny")||scenario.equals("reconsider-approve")||scenario.equals("reconsider-goal"))return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(DeploymentAgentPublicationReconsideration.ALLOW),Optional.empty());
                        return new OperationResult<>(TaskResult.CANCELLED,Optional.empty(),Optional.empty());
                    }
                    if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("Diagnostic cannot bypass APP");
                    var reviewedIntent=(gold.debug.windowstolinux.appapi.task.action.ActionIntent)a[0];
                    if(reviewedIntent.semantics().map(v->v.operation().equals(DeploymentAgentPublicationCorrectionProgram.OPERATION)).orElse(false))correctionGrants.incrementAndGet();
                    grants.incrementAndGet();var value=new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID());assertTrue(uniqueGrants.add(value.id()));granted.set(value);return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(value),Optional.empty());
                });
                var session=(gold.debug.windowstolinux.appapi.linux.session.RemoteSession)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class},(p,m,a)->{
                    if(m.getName().equals("facts"))return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("Linux","x86_64",fingerprint);
                    if(!m.getName().equals("execute"))throw new AssertionError("No mutation or other remote capability");
                    var command=(gold.debug.windowstolinux.appapi.linux.command.CommandRequest)a[0];assertEquals(granted.get(),a[1]);sends.incrementAndGet();
                    if(command.script().contains("'publication-original-status'")) {
                        boolean retained=!allowGoal.get()&&command.script().contains("'"+unit.id()+"'");
                        String output="PUBLICATION_STATUS=1\nCURRENT="+(retained?plan.components().getFirst().release():"none")+"\nCURRENT_KIND="+(retained?"deployment":"none")+"\nCURRENT_MODE="+(retained?"DAEMON":"none")+"\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\nCANDIDATE="+(cleanups.get()==2?"absent":"owned")+"\nBUILD_ACTIVE=no\nUNIT_PRESENT="+(retained?"yes":"no")+"\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT="+(retained?1:0)+"\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",false)),Optional.empty());
                    }
                    if(command.script().contains("'candidate-cleanup-task'")) {cleanups.incrementAndGet();return localReceipt("CANDIDATE_CLEANED=1\n");}
                    assertEquals(expectedCommand.get(),command.script());correctionSends.incrementAndGet();
                    try(var expected=program.request(correctionBinding.get(),diagnosticAction.get())) {
                        assertEquals(expected.command().script(),command.script());assertTrue(java.util.Arrays.equals(expected.command().stdin(),command.stdin()));
                        if(scenario.equals("timeout"))return new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(new Problem(ProblemCode.UNKNOWN,"LOCAL_CONTINUATION_TIMEOUT","Local peer timeout",Map.of(),Optional.empty())));
                        String output=DeploymentAgentPublicationCorrectionProgram.FRAME+correctionSafeReceipt(program,expected,scenario.equals("unknown"))+"\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",false)),Optional.empty());
                    }
                });
                var scope=new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope(reviewedContext,host,task,"MANUAL_REVIEW",false,()->"b".repeat(64),null,gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE,()->model);
                var candidates=new java.util.LinkedHashMap<String,gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate>();
                for(var component:plan.components())candidates.put(component.id(),new gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate(component.candidate(),"f".repeat(64),"e".repeat(64),"0".repeat(64),false,Map.of()));
                var producer=new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log);
                fixturePhase.accept("FIRST_RECONCILE_BEGIN");
                boolean firstResult=producer.run();fixturePhase.accept("FIRST_RECONCILE_END");
                if(scenario.equals("goal-already")) {
                    assertTrue(firstResult);assertEquals(0,correctionSends.get());assertEquals(2,cleanups.get());
                    assertTrue(log.events(task).stream().noneMatch(e->e.type().equals(DeploymentAgentPublicationCorrection.EVENT)));
                    assertEquals(TaskResult.FAILED,new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task)).outcome());return success();
                }
                assertFalse(firstResult);
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());
                assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());assertTrue(state.verified().isEmpty());
                assertEquals(0,cleanups.get());assertEquals(3,reviews.get());assertEquals(initiallyDenied?2:3,grants.get());
                assertEquals(initiallyDenied?2:3,sends.get());assertEquals(initiallyDenied?0:1,correctionSends.get());
                assertTrue(state.lastActionEvent()>immutableOld.getLast().id());
                var correctionEvents=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationCorrection.EVENT)).toList();
                assertFalse(correctionEvents.isEmpty());var afterAttempt=List.copyOf(correctionEvents);
                var deniedDiagnostics=new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(context).read(task.id());
                assertTrue(deniedDiagnostics.problem().isEmpty());
                var denialRecords=deniedDiagnostics.records().stream().filter(r->r.event().eventCode().equals("DEPLOYMENT_AGENT_REVIEW_DENIED")).toList();
                if(initiallyDenied) {
                    assertEquals(1,denialRecords.size());var record=denialRecords.getFirst();String message=record.event().message();
                    assertEquals(Optional.of(task.id()),record.event().taskId());assertFalse(record.truncated());
                    assertTrue(message.startsWith("type=COMMAND action="+deniedExactAction.get()+" Correction denied: bounded public reason"));
                    assertTrue(message.contains("[credential content excluded]"));
                    assertFalse(message.contains("LOCAL_PASSWORD_SENTINEL"));assertFalse(message.contains("LOCAL_API_KEY_SENTINEL"));
                    assertTrue(message.length()<=512+"type=COMMAND action=".length()+64+1);
                    assertTrue(message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=2048);
                    assertTrue(message.chars().noneMatch(Character::isISOControl));assertFalse(message.contains("WTL-SSH-COMMAND-1"));
                    assertEquals(List.of("REQUEST","REVIEW","NOT_SENT"),correctionEvents.stream().map(e->{try{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.detail()).path("kind").asText();}catch(Exception invalid){throw new AssertionError(invalid);}}).toList());
                } else assertTrue(denialRecords.isEmpty());
                assertEquals(immutableOld,log.events(task).subList(0,immutableOld.size()));assertTrue(log.events(task).contains(oldResult));
                assertEquals(installationFrames,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList());
                assertTrue(log.events(task).stream().noneMatch(e->Set.of("AI_PUBLICATION_FINISHED","AI_PUBLICATION_CLEANED","AI_RECOVERY_SETTLED").contains(e.type())));
                assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.observedOnly(task,log,old));assertEquals(before,log.events(task));
                if(scenario.equals("reconsider-refuse"))correctionReconsiderationRejectsEffectfulPrefixes(before,task.id());
                fixturePhase.accept("REPEAT_UNMET_RECONCILE_BEGIN");
                if(initiallyDenied) {
                    boolean reconsideredResult=new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run();
                    if(scenario.equals("reconsider-goal")) {
                        assertTrue(reconsideredResult);assertEquals(1,humanQuestions.get());assertEquals(0,reconsiderationReviews.get());
                        assertEquals(0,correctionGrants.get());assertEquals(0,correctionSends.get());assertEquals(2,cleanups.get());
                        assertEquals(afterAttempt,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationCorrection.EVENT)).toList());
                        assertTrue(log.events(task).contains(oldResult));
                        var admitted=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationReconsideration.EVENT)).toList();
                        assertTrue(admitted.stream().noneMatch(e->{try{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.detail()).path("kind").asText().equals("REQUEST");}catch(Exception invalid){throw new AssertionError(invalid);}}));
                        assertTrue(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()).verified().isPresent());
                        assertEquals(TaskResult.FAILED,new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task)).outcome());return success();
                    }
                    assertFalse(reconsideredResult);
                } else assertThrows(Exception.class,()->new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run());
                fixturePhase.accept("REPEAT_UNMET_RECONCILE_END");
                assertEquals(afterAttempt,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationCorrection.EVENT)).toList());assertEquals(0,cleanups.get());
                if(initiallyDenied) {
                    var retainedDiagnostics=new gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics(context).read(task.id());
                    assertTrue(retainedDiagnostics.problem().isEmpty());
                    var retainedDenials=retainedDiagnostics.records().stream().filter(r->r.event().eventCode().equals("DEPLOYMENT_AGENT_REVIEW_DENIED")).toList();
                    assertEquals(scenario.equals("reconsider-app-deny")?2:1,retainedDenials.size());
                    assertTrue(retainedDenials.containsAll(denialRecords));
                    for(var record:retainedDenials) {assertFalse(record.event().message().contains("LOCAL_PASSWORD_SENTINEL"));assertFalse(record.event().message().contains("LOCAL_API_KEY_SENTINEL"));}
                    assertEquals(1,humanQuestions.get());
                    assertEquals(scenario.equals("reconsider-approve")?1:0,correctionGrants.get());
                    assertEquals(scenario.equals("reconsider-approve")?1:0,correctionSends.get());
                    if(!reconsiderationCase||scenario.equals("reconsider-refuse")||scenario.equals("reconsider-cancel")){assertEquals(2,grants.get());assertEquals(2,sends.get());}
                    var reconsidered=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationReconsideration.EVENT)).toList();
                    var kinds=reconsidered.stream().map(e->{try{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.detail()).path("kind").asText();}catch(Exception invalid){throw new AssertionError(invalid);}}).toList();
                    assertEquals(1,kinds.stream().filter("ASK"::equals).count());assertEquals(1,kinds.stream().filter("ANSWER"::equals).count());
                    boolean reviewAllowed=scenario.equals("reconsider-app-deny")||scenario.equals("reconsider-approve");
                    assertEquals(reviewAllowed?1:0,reconsiderationReviews.get());assertEquals(reviewAllowed?1:0,kinds.stream().filter("REQUEST"::equals).count());
                    assertEquals(scenario.equals("reconsider-approve")?1:0,kinds.stream().filter("GRANT"::equals).count());
                    assertEquals(scenario.equals("reconsider-approve")?1:0,kinds.stream().filter("DISPATCH"::equals).count());
                    var unsettled=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());
                    assertEquals(Optional.of(old),unsettled.pending());assertEquals(0,unsettled.completed());assertTrue(unsettled.verified().isEmpty());
                    assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                    if(reconsiderationCase) {
                        int nativeGrants=correctionGrants.get(),nativeSends=correctionSends.get(),independentReviews=reconsiderationReviews.get();
                        assertThrows(Exception.class,()->new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run());
                        assertEquals(1,humanQuestions.get());assertEquals(nativeGrants,correctionGrants.get());assertEquals(nativeSends,correctionSends.get());assertEquals(independentReviews,reconsiderationReviews.get());
                        assertEquals(reconsidered,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationReconsideration.EVENT)).toList());
                    }
                }
                allowGoal.set(true);fixturePhase.accept("FINAL_GOAL_RECONCILE_BEGIN");
                assertTrue(new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run());
                fixturePhase.accept("FINAL_GOAL_RECONCILE_END");
                assertEquals(initiallyDenied?(scenario.equals("reconsider-approve")?1:0):1,correctionSends.get());assertEquals(2,cleanups.get());
                assertEquals(grants.get(),uniqueGrants.size());assertEquals(reviews.get(),grants.get()+(initiallyDenied?1:0)+(scenario.equals("reconsider-app-deny")?1:0));
                assertEquals(immutableOld,log.events(task).subList(0,immutableOld.size()));
                assertTrue(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()).verified().isPresent());
                fixturePhase.accept("LOCAL_FAILED_TAIL_BEGIN");
                assertEquals(TaskResult.FAILED,new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task)).outcome());
                fixturePhase.accept("LOCAL_FAILED_TAIL_END");return success();
            }, java.time.Duration.ofSeconds(90));
        }
    }

    /** Pure alternate prefixes; no event is inserted into the fixture database. */
    private void correctionReconsiderationRejectsEffectfulPrefixes(List<DeploymentAgentActionLog.Event> denied,UUID task)throws Exception {
        assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.read(denied,UUID.randomUUID()));
        var selected=denied.stream().filter(e->e.type().equals(DeploymentAgentPublicationCorrection.EVENT)).toList();
        assertEquals(3,selected.size());var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var prior=denied.stream().filter(e->e.id()<selected.getFirst().id()).toList();
        for(String terminal:List.of("GRANT","DISPATCH","UNKNOWN")) {
            var alternate=new ArrayList<>(prior);alternate.add(selected.getFirst());
            var review=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(selected.get(1).detail());review.put("decision","APPROVE");
            correctionAlternateFrame(alternate,review,selected.getFirst().action());
            var common=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(selected.getLast().detail());
            correctionAlternateFrame(alternate,common.deepCopy().put("kind","GRANT").put("grant",UUID.randomUUID().toString()),selected.getFirst().action());
            if(!terminal.equals("GRANT"))correctionAlternateFrame(alternate,common.deepCopy().put("kind","DISPATCH"),selected.getFirst().action());
            if(terminal.equals("UNKNOWN")) {
                correctionAlternateFrame(alternate,common.deepCopy().put("kind","RESULT").put("observedOutcome","UNKNOWN").put("actual","f".repeat(64)).put("fixedReceiptVerified",false).put("receipt","none").put("parts",0),selected.getFirst().action());
                correctionAlternateFrame(alternate,common.deepCopy().put("kind","END"),selected.getFirst().action());
            }
            var state=DeploymentAgentPublicationCompensation.read(alternate,task);
            assertTrue(DeploymentAgentPublicationReconsideration.eligibility(state,alternate).isEmpty(),terminal+" is never an unsent independent denial");
        }
    }

    private void correctionAlternateFrame(List<DeploymentAgentActionLog.Event> events,com.fasterxml.jackson.databind.node.ObjectNode frame,String action)throws Exception {
        frame.put("history",DeploymentAgentPublicationObservation.prefixSha256(events));
        events.add(new DeploymentAgentActionLog.Event(events.getLast().id()+1,DeploymentAgentPublicationCorrection.EVENT,action,frame.toString()));
    }

    /** Authentic original TaskWork/journal; APP model and host/SSH are local capability peers, not real remote acceptance. */
    @ParameterizedTest
    @ValueSource(strings={"complete","partial","timeout","deny","refused","goal-already"})
    void contractFollowupFreshObservationAndEveryResultPreserveUnknownWithoutReplay(String scenario)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                long fixtureStarted=System.nanoTime();
                java.util.function.Consumer<String> fixturePhase=name->{System.out.println("FOLLOWUP_FIXTURE_PHASE scenario="+scenario+" phase="+name+" elapsedNanos="+(System.nanoTime()-fixtureStarted));try{task.progress("Followup fixture "+name,Optional.empty());}catch(gold.debug.windowstolinux.appapi.task.result.ApiException unavailable){throw new AssertionError("Fixture phase could not be recorded",unavailable);}};
                fixturePhase.accept("TASK_WORK_BEGIN");
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());var store=new DeploymentExecutionStore(context.db());
                var server=new ServerId("server");String fingerprint="local-diagnostic-peer";
                var capturedLayout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(),task,capturedLayout);
                originalPublication(runtime,task,false,DeploymentAgentInputs.hash(server.id()+":"+fingerprint),DeploymentAgentInputs.targetProfile(context.servers().get(server,task)));
                var formal=store.findForTask(task).orElseThrow();var template=store.publicationTemplate(formal).orElseThrow();var unit=template.graph().components().getFirst().unit();
                assertEquals(capturedLayout,gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.original(context,task,log.find(task).orElseThrow().identity(),log.events(task)));
                var installedProgram=new DeploymentAgentPublicationRecoveryProgram(unit.layout());
                var installation=DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"b".repeat(64),installedProgram);
                try(var ready=installedProgram.readiness(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,ready);installation.result(ready.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"));}
                try(var install=installedProgram.install(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,install);installation.result(install.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),install.action().toString().replace("-","")));}
                var installationFrames=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList();
                var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(70),"PUBLICATION_RECOVERY_FAILURE=UNKNOWN\n","",false)),Optional.of(new Problem(ProblemCode.IO_FAILURE,"SSH_COMMAND_NONZERO","Local rollback peer returned a nonzero exit",Map.of(),Optional.empty()))),false);
                var oldResult=log.events(task).getLast();compensationObservation(context,task,true);
                var eligible=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                appendReadyPreflightFixture(task,log,eligible,unit.layout());
                appendUnknownContinuationFixture(task,log,unit.layout());
                compensationObservation(context,task,true);appendContractControlFixture(task,log,unit.layout(),true);
                var immutableOld=List.copyOf(log.events(task));fixturePhase.accept("OLD_IMMUTABLE_HISTORY_READY");
                var program=new DeploymentAgentPublicationContractDiagnosticFollowupProgram(unit.layout());
                var followupBinding=new AtomicReference<DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding>();
                var allowGoal=new java.util.concurrent.atomic.AtomicBoolean(scenario.equals("goal-already"));
                var reviews=new java.util.concurrent.atomic.AtomicInteger();var grants=new java.util.concurrent.atomic.AtomicInteger();var sends=new java.util.concurrent.atomic.AtomicInteger();
                var granted=new AtomicReference<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant>();var expectedCommand=new AtomicReference<String>();var diagnosticAction=new AtomicReference<UUID>();var followupSends=new java.util.concurrent.atomic.AtomicInteger();var cleanups=new java.util.concurrent.atomic.AtomicInteger();var uniqueGrants=new java.util.HashSet<UUID>();
                var ai=(gold.debug.windowstolinux.appapi.ai.AiService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ai.AiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class},(p,m,a)->{if(m.getName().equals("configurationRevision"))return "b".repeat(64);throw new AssertionError("No provider call in local diagnostic fixture");});
                var reviewedContext=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},(p,m,a)->m.getName().equals("ai")?ai:invoke(m,context,a));
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel model=(instructions,facts,intent,command,revision,supplied)->{
                    assertTrue(supplied==task);reviews.incrementAndGet();
                    var node=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts);
                    if(node.has("publicationContractDiagnosticFollowup")) {
                        assertEquals(plan.sha256(),intent.revision());
                        assertEquals(program.source(),node.path("completeExecutableInput").asText());assertEquals(program.sourceVersion(),node.at("/publicationContractDiagnosticFollowup/expectedSourceVersion").asText());
                        assertEquals(old.event(),node.at("/publicationContractDiagnosticFollowup/binding/diagnostic/continuation/original/pendingIntentEvent").longValue());
                        diagnosticAction.set(UUID.fromString(node.at("/publicationContractDiagnosticFollowup/action").asText()));expectedCommand.set(command.script());
                        try(var decoded=program.decode(command)){followupBinding.set(decoded.binding());assertEquals(old.event(),decoded.binding().diagnostic().continuation().original().pendingIntentEvent());}
                    }
                    return new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel.Result(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision(scenario.equals("deny")&&node.has("publicationContractDiagnosticFollowup")?"DENY":"APPROVE",false,"Local diagnostic decision"),Map.of());
                };
                var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
                    if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("Diagnostic cannot bypass APP");
                    grants.incrementAndGet();var value=new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID());assertTrue(uniqueGrants.add(value.id()));granted.set(value);return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(value),Optional.empty());
                });
                var session=(gold.debug.windowstolinux.appapi.linux.session.RemoteSession)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class},(p,m,a)->{
                    if(m.getName().equals("facts"))return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("Linux","x86_64",fingerprint);
                    if(!m.getName().equals("execute"))throw new AssertionError("No mutation or other remote capability");
                    var command=(gold.debug.windowstolinux.appapi.linux.command.CommandRequest)a[0];assertEquals(granted.get(),a[1]);sends.incrementAndGet();
                    if(command.script().contains("'publication-original-status'")) {
                        boolean retained=!allowGoal.get()&&command.script().contains("'"+unit.id()+"'");
                        String output="PUBLICATION_STATUS=1\nCURRENT="+(retained?plan.components().getFirst().release():"none")+"\nCURRENT_KIND="+(retained?"deployment":"none")+"\nCURRENT_MODE="+(retained?"DAEMON":"none")+"\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\nCANDIDATE="+(cleanups.get()==2?"absent":"owned")+"\nBUILD_ACTIVE=no\nUNIT_PRESENT="+(retained?"yes":"no")+"\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT="+(retained?1:0)+"\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",false)),Optional.empty());
                    }
                    if(command.script().contains("'candidate-cleanup-task'")) {cleanups.incrementAndGet();return localReceipt("CANDIDATE_CLEANED=1\n");}
                    assertEquals(expectedCommand.get(),command.script());followupSends.incrementAndGet();
                    try(var expected=program.request(followupBinding.get(),diagnosticAction.get())) {
                        assertEquals(expected.command().script(),command.script());assertTrue(java.util.Arrays.equals(expected.command().stdin(),command.stdin()));
                        if(scenario.equals("timeout"))return new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(new Problem(ProblemCode.UNKNOWN,"LOCAL_CONTINUATION_TIMEOUT","Local peer timeout",Map.of(),Optional.empty())));
                        String output=scenario.equals("partial")?"PUBLICATION_CONTRACT_DIAGNOSTIC_FOLLOWUP={":"PUBLICATION_CONTRACT_DIAGNOSTIC_FOLLOWUP="+contractFollowupSafeReceipt(program,expected,scenario.equals("refused"))+"\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",scenario.equals("partial"))),Optional.empty());
                    }
                });
                var scope=new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope(reviewedContext,host,task,"MANUAL_REVIEW",false,()->"b".repeat(64),null,gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE,()->model);
                var candidates=new java.util.LinkedHashMap<String,gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate>();
                for(var component:plan.components())candidates.put(component.id(),new gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate(component.candidate(),"f".repeat(64),"e".repeat(64),"0".repeat(64),false,Map.of()));
                var producer=new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log);
                fixturePhase.accept("FIRST_RECONCILE_BEGIN");
                boolean firstResult=producer.run();fixturePhase.accept("FIRST_RECONCILE_END");
                if(scenario.equals("goal-already")) {
                    assertTrue(firstResult);assertEquals(0,followupSends.get());assertEquals(2,cleanups.get());
                    assertTrue(log.events(task).stream().noneMatch(e->e.type().equals(DeploymentAgentPublicationContractDiagnosticFollowup.EVENT)));
                    assertEquals(TaskResult.FAILED,new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task)).outcome());return success();
                }
                assertFalse(firstResult);
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());
                assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());assertTrue(state.verified().isEmpty());
                assertEquals(0,cleanups.get());assertEquals(3,reviews.get());assertEquals(scenario.equals("deny")?2:3,grants.get());
                assertEquals(scenario.equals("deny")?2:3,sends.get());assertEquals(scenario.equals("deny")?0:1,followupSends.get());
                assertTrue(state.lastActionEvent()>immutableOld.getLast().id());
                var followupEvents=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationContractDiagnosticFollowup.EVENT)).toList();
                assertFalse(followupEvents.isEmpty());var afterAttempt=List.copyOf(followupEvents);
                assertEquals(immutableOld,log.events(task).subList(0,immutableOld.size()));assertTrue(log.events(task).contains(oldResult));
                assertEquals(installationFrames,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList());
                assertTrue(log.events(task).stream().noneMatch(e->Set.of("AI_PUBLICATION_FINISHED","AI_PUBLICATION_CLEANED","AI_RECOVERY_SETTLED").contains(e.type())));
                assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.observedOnly(task,log,old));assertEquals(before,log.events(task));
                fixturePhase.accept("REPEAT_UNMET_RECONCILE_BEGIN");
                assertThrows(Exception.class,()->new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run());
                fixturePhase.accept("REPEAT_UNMET_RECONCILE_END");
                assertEquals(afterAttempt,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationContractDiagnosticFollowup.EVENT)).toList());assertEquals(0,cleanups.get());
                allowGoal.set(true);fixturePhase.accept("FINAL_GOAL_RECONCILE_BEGIN");
                assertTrue(new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run());
                fixturePhase.accept("FINAL_GOAL_RECONCILE_END");
                assertEquals(scenario.equals("deny")?0:1,followupSends.get());assertEquals(2,cleanups.get());
                assertEquals(grants.get(),uniqueGrants.size());assertEquals(reviews.get(),grants.get()+(scenario.equals("deny")?1:0));
                assertEquals(immutableOld,log.events(task).subList(0,immutableOld.size()));
                assertTrue(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()).verified().isPresent());
                fixturePhase.accept("LOCAL_FAILED_TAIL_BEGIN");
                assertEquals(TaskResult.FAILED,new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task)).outcome());
                fixturePhase.accept("LOCAL_FAILED_TAIL_END");return success();
            }, java.time.Duration.ofSeconds(60));
        }
    }

    /** Authentic original TaskWork/journal; APP model and host/SSH are local capability peers, not real remote acceptance. */
    @ParameterizedTest
    @ValueSource(strings={"complete","partial","timeout","deny","refused"})
    void contractDiagnosticAlwaysEndsUnknownWithoutMutationOrReplay(String scenario)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());var store=new DeploymentExecutionStore(context.db());
                var server=new ServerId("server");String fingerprint="local-diagnostic-peer";
                var capturedLayout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(),task,capturedLayout);
                originalPublication(runtime,task,false,DeploymentAgentInputs.hash(server.id()+":"+fingerprint),DeploymentAgentInputs.targetProfile(context.servers().get(server,task)));
                var formal=store.findForTask(task).orElseThrow();var template=store.publicationTemplate(formal).orElseThrow();var unit=template.graph().components().getFirst().unit();
                assertEquals(capturedLayout,gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.original(context,task,log.find(task).orElseThrow().identity(),log.events(task)));
                var installedProgram=new DeploymentAgentPublicationRecoveryProgram(unit.layout());
                var installation=DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"b".repeat(64),installedProgram);
                try(var ready=installedProgram.readiness(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,ready);installation.result(ready.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"));}
                try(var install=installedProgram.install(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,install);installation.result(install.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),install.action().toString().replace("-","")));}
                var installationFrames=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList();
                var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(70),"PUBLICATION_RECOVERY_FAILURE=UNKNOWN\n","",false)),Optional.of(new Problem(ProblemCode.IO_FAILURE,"SSH_COMMAND_NONZERO","Local rollback peer returned a nonzero exit",Map.of(),Optional.empty()))),false);
                var oldResult=log.events(task).getLast();compensationObservation(context,task,true);
                var eligible=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                appendReadyPreflightFixture(task,log,eligible,unit.layout());
                appendUnknownContinuationFixture(task,log,unit.layout());
                var immutableOld=List.copyOf(log.events(task));compensationObservation(context,task,true);
                var contractEligible=DeploymentAgentPublicationContractDiagnostic.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                var program=new DeploymentAgentPublicationContractDiagnosticProgram(unit.layout());
                var reviews=new java.util.concurrent.atomic.AtomicInteger();var grants=new java.util.concurrent.atomic.AtomicInteger();var sends=new java.util.concurrent.atomic.AtomicInteger();
                var granted=new AtomicReference<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant>();var expectedCommand=new AtomicReference<String>();var diagnosticAction=new AtomicReference<UUID>();var continuationSends=new java.util.concurrent.atomic.AtomicInteger();var cleanups=new java.util.concurrent.atomic.AtomicInteger();var uniqueGrants=new java.util.HashSet<UUID>();
                var ai=(gold.debug.windowstolinux.appapi.ai.AiService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ai.AiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class},(p,m,a)->{if(m.getName().equals("configurationRevision"))return "b".repeat(64);throw new AssertionError("No provider call in local diagnostic fixture");});
                var reviewedContext=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},(p,m,a)->m.getName().equals("ai")?ai:invoke(m,context,a));
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel model=(instructions,facts,intent,command,revision,supplied)->{
                    assertTrue(supplied==task);reviews.incrementAndGet();
                    var node=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts);
                    if(node.has("publicationContractDiagnostic")) {
                        assertEquals(plan.sha256(),intent.revision());
                        assertEquals(program.source(),node.path("completeExecutableInput").asText());assertEquals(program.sourceVersion(),node.at("/publicationContractDiagnostic/expectedSourceVersion").asText());
                        assertEquals(old.event(),node.at("/publicationContractDiagnostic/binding/continuation/original/pendingIntentEvent").longValue());
                        diagnosticAction.set(UUID.fromString(node.at("/publicationContractDiagnostic/action").asText()));expectedCommand.set(command.script());
                    }
                    return new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel.Result(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision(scenario.equals("deny")&&node.has("publicationContractDiagnostic")?"DENY":"APPROVE",false,"Local diagnostic decision"),Map.of());
                };
                var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
                    if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("Diagnostic cannot bypass APP");
                    grants.incrementAndGet();var value=new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID());assertTrue(uniqueGrants.add(value.id()));granted.set(value);return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(value),Optional.empty());
                });
                var session=(gold.debug.windowstolinux.appapi.linux.session.RemoteSession)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class},(p,m,a)->{
                    if(m.getName().equals("facts"))return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("Linux","x86_64",fingerprint);
                    if(!m.getName().equals("execute"))throw new AssertionError("No mutation or other remote capability");
                    var command=(gold.debug.windowstolinux.appapi.linux.command.CommandRequest)a[0];assertEquals(granted.get(),a[1]);sends.incrementAndGet();
                    if(command.script().contains("'publication-original-status'")) {
                        String output="PUBLICATION_STATUS=1\nCURRENT=none\nCURRENT_KIND=none\nCURRENT_MODE=none\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\nCANDIDATE="+(cleanups.get()==2?"absent":"owned")+"\nBUILD_ACTIVE=no\nUNIT_PRESENT=no\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=0\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",false)),Optional.empty());
                    }
                    if(command.script().contains("'candidate-cleanup-task'")) {cleanups.incrementAndGet();return localReceipt("CANDIDATE_CLEANED=1\n");}
                    assertEquals(expectedCommand.get(),command.script());continuationSends.incrementAndGet();
                    try(var expected=program.request(DeploymentAgentPublicationContractDiagnostic.binding(contractEligible),diagnosticAction.get())) {
                        assertEquals(expected.command().script(),command.script());assertTrue(java.util.Arrays.equals(expected.command().stdin(),command.stdin()));
                        if(scenario.equals("timeout"))return new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(new Problem(ProblemCode.UNKNOWN,"LOCAL_CONTINUATION_TIMEOUT","Local peer timeout",Map.of(),Optional.empty())));
                        String output=scenario.equals("partial")?"PUBLICATION_CONTRACT_DIAGNOSTIC={":"PUBLICATION_CONTRACT_DIAGNOSTIC="+contractDiagnosticSafeReceipt(program,expected,scenario.equals("refused"))+"\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",scenario.equals("partial"))),Optional.empty());
                    }
                });
                var scope=new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope(reviewedContext,host,task,"MANUAL_REVIEW",false,()->"b".repeat(64),null,gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE,()->model);
                var candidates=new java.util.LinkedHashMap<String,gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate>();
                for(var component:plan.components())candidates.put(component.id(),new gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate(component.candidate(),"f".repeat(64),"e".repeat(64),"0".repeat(64),false,Map.of()));
                var producer=new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log);
                assertFalse(producer.run());
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());
                assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());assertTrue(state.verified().isEmpty());
                assertEquals(0,cleanups.get());assertEquals(1,reviews.get());assertEquals(scenario.equals("deny")?0:1,grants.get());
                assertEquals(scenario.equals("deny")?0:1,sends.get());assertEquals(sends.get(),continuationSends.get());
                assertTrue(state.lastActionEvent()>contractEligible.original().observationEnd());
                assertTrue(DeploymentAgentPublicationContractDiagnostic.attempted(log.events(task),contractEligible));
                assertEquals(immutableOld,log.events(task).subList(0,immutableOld.size()));assertTrue(log.events(task).contains(oldResult));
                assertEquals(installationFrames,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList());
                assertTrue(log.events(task).stream().noneMatch(e->Set.of("AI_PUBLICATION_FINISHED","AI_PUBLICATION_CLEANED","AI_RECOVERY_SETTLED").contains(e.type())));
                assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                var before=log.events(task);assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.observedOnly(task,log,old));assertEquals(before,log.events(task));
                // A separate later reconciliation obtains fresh approved whole-graph goals and cleanups; it never repeats this diagnostic.
                assertTrue(new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run());
                assertEquals(scenario.equals("deny")?0:1,continuationSends.get());assertEquals(2,cleanups.get());
                assertEquals(grants.get(),uniqueGrants.size());assertEquals(reviews.get(),grants.get()+(scenario.equals("deny")?1:0));
                assertEquals(immutableOld,log.events(task).subList(0,immutableOld.size()));
                assertTrue(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()).verified().isPresent());
                assertEquals(TaskResult.FAILED,new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task)).outcome());
                return success();
            });
        }
    }

    /** Authentic original TaskWork/journal; APP model and host/SSH are local capability peers, not real remote acceptance. */
    @ParameterizedTest
    @ValueSource(strings={"complete","partial","timeout","deny","refused"})
    void currentStateContinuationNeverReplaysAndNeedsFreshWholeGraph(String scenario)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());var store=new DeploymentExecutionStore(context.db());
                var server=new ServerId("server");String fingerprint="local-diagnostic-peer";
                var capturedLayout=gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigCapture.remoteLayout(context.tasks(),task,capturedLayout);
                originalPublication(runtime,task,false,DeploymentAgentInputs.hash(server.id()+":"+fingerprint),DeploymentAgentInputs.targetProfile(context.servers().get(server,task)));
                var formal=store.findForTask(task).orElseThrow();var template=store.publicationTemplate(formal).orElseThrow();var unit=template.graph().components().getFirst().unit();
                assertEquals(capturedLayout,gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.original(context,task,log.find(task).orElseThrow().identity(),log.events(task)));
                var installedProgram=new DeploymentAgentPublicationRecoveryProgram(unit.layout());
                var installation=DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"b".repeat(64),installedProgram);
                try(var ready=installedProgram.readiness(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,ready);installation.result(ready.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"));}
                try(var install=installedProgram.install(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,install);installation.result(install.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),install.action().toString().replace("-","")));}
                var installationFrames=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList();
                var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(70),"PUBLICATION_RECOVERY_FAILURE=UNKNOWN\n","",false)),Optional.of(new Problem(ProblemCode.IO_FAILURE,"SSH_COMMAND_NONZERO","Local rollback peer returned a nonzero exit",Map.of(),Optional.empty()))),false);
                var oldResult=log.events(task).getLast();compensationObservation(context,task,true);
                var eligible=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                appendReadyPreflightFixture(task,log,eligible,unit.layout());
                var originalV1=log.events(task).stream().filter(e->Set.of(DeploymentAgentPublicationRecoveryDiagnostic.EVENT,DeploymentAgentPublicationRecoveryPreflight.EVENT).contains(e.type())).toList();
                var preflightEligible=DeploymentAgentPublicationContinuation.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                var program=new DeploymentAgentPublicationContinuationProgram(unit.layout());
                var reviews=new java.util.concurrent.atomic.AtomicInteger();var grants=new java.util.concurrent.atomic.AtomicInteger();var sends=new java.util.concurrent.atomic.AtomicInteger();
                var granted=new AtomicReference<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant>();var expectedCommand=new AtomicReference<String>();var diagnosticAction=new AtomicReference<UUID>();var continuationSends=new java.util.concurrent.atomic.AtomicInteger();var cleanups=new java.util.concurrent.atomic.AtomicInteger();var uniqueGrants=new java.util.HashSet<UUID>();
                var ai=(gold.debug.windowstolinux.appapi.ai.AiService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ai.AiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class},(p,m,a)->{if(m.getName().equals("configurationRevision"))return "b".repeat(64);throw new AssertionError("No provider call in local diagnostic fixture");});
                var reviewedContext=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},(p,m,a)->m.getName().equals("ai")?ai:invoke(m,context,a));
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel model=(instructions,facts,intent,command,revision,supplied)->{
                    assertTrue(supplied==task);reviews.incrementAndGet();
                    var node=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts);
                    if(node.has("publicationRecoveryContinuation")) {
                        assertEquals(plan.sha256(),intent.revision());
                        assertEquals(program.source(),node.path("completeExecutableInput").asText());assertEquals(program.sourceVersion(),node.at("/publicationRecoveryContinuation/expectedSourceVersion").asText());
                        assertEquals(old.event(),node.at("/publicationRecoveryContinuation/binding/original/pendingIntentEvent").longValue());
                        diagnosticAction.set(UUID.fromString(node.at("/publicationRecoveryContinuation/action").asText()));expectedCommand.set(command.script());
                    }
                    return new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel.Result(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision(scenario.equals("deny")&&node.has("publicationRecoveryContinuation")?"DENY":"APPROVE",false,"Local diagnostic decision"),Map.of());
                };
                var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
                    if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("Diagnostic cannot bypass APP");
                    grants.incrementAndGet();var value=new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID());assertTrue(uniqueGrants.add(value.id()));granted.set(value);return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(value),Optional.empty());
                });
                var session=(gold.debug.windowstolinux.appapi.linux.session.RemoteSession)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class},(p,m,a)->{
                    if(m.getName().equals("facts"))return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("Linux","x86_64",fingerprint);
                    if(!m.getName().equals("execute"))throw new AssertionError("No mutation or other remote capability");
                    var command=(gold.debug.windowstolinux.appapi.linux.command.CommandRequest)a[0];assertEquals(granted.get(),a[1]);sends.incrementAndGet();
                    if(command.script().contains("'publication-original-status'")) {
                        String output="PUBLICATION_STATUS=1\nCURRENT=none\nCURRENT_KIND=none\nCURRENT_MODE=none\nSNAPSHOT=none\nSNAPSHOT_OLD=none\nSNAPSHOT_KIND=none\nSNAPSHOT_RUNNING=none\nMAINTENANCE=none\nCANDIDATE="+(cleanups.get()==2?"absent":"owned")+"\nBUILD_ACTIVE=no\nUNIT_PRESENT=no\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=0\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",false)),Optional.empty());
                    }
                    if(command.script().contains("'candidate-cleanup-task'")) {cleanups.incrementAndGet();return localReceipt("CANDIDATE_CLEANED=1\n");}
                    assertEquals(expectedCommand.get(),command.script());continuationSends.incrementAndGet();
                    try(var expected=program.request(DeploymentAgentPublicationContinuation.binding(preflightEligible),diagnosticAction.get())) {
                        assertEquals(expected.command().script(),command.script());assertTrue(java.util.Arrays.equals(expected.command().stdin(),command.stdin()));
                        if(scenario.equals("timeout"))return new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(new Problem(ProblemCode.UNKNOWN,"LOCAL_CONTINUATION_TIMEOUT","Local peer timeout",Map.of(),Optional.empty())));
                        String output=scenario.equals("partial")?"PUBLICATION_CONTINUATION={":"PUBLICATION_CONTINUATION="+continuationSafeReceipt(program,expected,scenario.equals("refused"))+"\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",scenario.equals("partial"))),Optional.empty());
                    }
                });
                var scope=new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope(reviewedContext,host,task,"MANUAL_REVIEW",false,()->"b".repeat(64),null,gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE,()->model);
                var candidates=new java.util.LinkedHashMap<String,gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate>();
                for(var component:plan.components())candidates.put(component.id(),new gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate(component.candidate(),"f".repeat(64),"e".repeat(64),"0".repeat(64),false,Map.of()));
                var producer=new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log);
                assertEquals(scenario.equals("complete"),producer.run());
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());
                if(!scenario.equals("complete")) {
                    assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());assertTrue(state.verified().isEmpty());assertEquals(0,cleanups.get());
                    assertTrue(state.lastActionEvent()>oldResult.id());assertEquals(1,reviews.get());assertEquals(scenario.equals("deny")?0:1,grants.get());
                    assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));
                    // This later explicit run uses new independently approved whole-graph receipts; it never resends the continuation.
                    assertTrue(new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run());
                }
                assertEquals(scenario.equals("deny")?0:1,continuationSends.get());assertEquals(2,cleanups.get());assertEquals(grants.get(),uniqueGrants.size());assertEquals(reviews.get(),grants.get()+(scenario.equals("deny")?1:0));
                assertTrue(DeploymentAgentPublicationContinuation.attempted(log.events(task),preflightEligible));
                assertEquals(originalV1,log.events(task).stream().filter(e->Set.of(DeploymentAgentPublicationRecoveryDiagnostic.EVENT,DeploymentAgentPublicationRecoveryPreflight.EVENT).contains(e.type())).toList());assertTrue(log.events(task).contains(oldResult));
                assertEquals(installationFrames,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList());
                assertTrue(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()).verified().isPresent());
                assertTrue(log.events(task).stream().noneMatch(e->Set.of("AI_PUBLICATION_FINISHED","AI_PUBLICATION_CLEANED","AI_RECOVERY_SETTLED").contains(e.type())));
                String window=switch(scenario){case "complete"->"REFERENCE_ACK_LOST";case "partial"->"FAILED";case "deny"->"FINISHED";default->"UNKNOWN";};
                var id=store.findForTask(task).orElseThrow().identity().executionId();
                if(window.equals("UNKNOWN")) store.recordUncommittedOutcome(task,id,TaskResult.UNKNOWN,
                    new Problem(ProblemCode.UNKNOWN,"FIXTURE_ORIGINAL_UNKNOWN","Original publication remains unknown",Map.of(),Optional.empty()),java.time.Instant.now());
                if(window.equals("REFERENCE_ACK_LOST")) {
                    var once=new java.util.concurrent.atomic.AtomicBoolean();var servers=context.servers();
                    var losing=(gold.debug.windowstolinux.appapi.server.ServerService)Proxy.newProxyInstance(servers.getClass().getClassLoader(),
                        new Class<?>[]{gold.debug.windowstolinux.appapi.server.ServerService.class},(proxy,method,args)->{
                            var result=invoke(method,servers,args);
                            if(method.getName().equals("resolve")&&once.compareAndSet(false,true))throw new ApiException(new Problem(ProblemCode.UNKNOWN,"FIXTURE_REFERENCE_ACK_LOST","Local fixture acknowledgement",Map.of(),Optional.empty()));
                            return result;
                        });
                    var losingContext=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},
                        (proxy,method,args)->method.getName().equals("servers")?losing:invoke(method,context,args));
                    assertEquals(TaskResult.UNKNOWN,new DeploymentOriginalPublishCommit(losingContext).completeUncommittedVerified(task,id,TaskResult.FAILED,DeploymentAgentPublicationCompensation.failure()).outcome());
                    assertEquals(Optional.of(TaskResult.FAILED),store.findForTask(task).orElseThrow().outcome());
                } else if(!window.equals("UNKNOWN")) {
                    assertEquals(TaskResult.FAILED,new DeploymentOriginalPublishCommit(context).completeUncommittedVerified(task,id,TaskResult.FAILED,DeploymentAgentPublicationCompensation.failure()).outcome());
                    if(window.equals("FINISHED"))assertEquals(TaskResult.FAILED,new DeploymentOriginalPublishCommit(context).finishKnown(task,store.findForTask(task).orElseThrow()).outcome());
                }
                var linux=new java.util.concurrent.atomic.AtomicInteger();var localAiCalls=new java.util.concurrent.atomic.AtomicInteger();
                var localOnly=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},(proxy,method,args)->{
                    if(method.getName().equals("linux")){linux.incrementAndGet();throw new AssertionError("Verified local tail cannot obtain Linux");}
                    if(method.getName().equals("ai")){localAiCalls.incrementAndGet();throw new AssertionError("Verified local tail cannot obtain AI");}
                    return invoke(method,context,args);
                });
                var recovery=new DeploymentAgentRecovery(localOnly,log);
                assertEquals(TaskResult.FAILED,recovery.reconcile(task,log.find(task).orElseThrow()).outcome());
                var settled=log.events(task);assertEquals(TaskResult.FAILED,recovery.reconcile(task,log.find(task).orElseThrow()).outcome());
                assertEquals(settled,log.events(task));assertEquals(0,linux.get());assertEquals(0,localAiCalls.get());
                assertEquals(DeploymentAgentPublicationCompensation.FAILURE,store.problem(store.findForTask(task).orElseThrow()).orElseThrow().eventCode());
                assertEquals(TaskResult.FAILED,new DeploymentAgentHistoryArchive(context).settle(task,new OperationResult<>(TaskResult.FAILED,Optional.empty(),Optional.of(DeploymentAgentPublicationCompensation.failure()))).outcome());

                return success();
            });
        }
    }

    /** Authentic original TaskWork/journal; APP model and host/SSH are local capability peers, not real remote acceptance. */
    @ParameterizedTest
    @ValueSource(strings={"complete","partial","timeout","deny"})
    void environmentFailurePreflightEndsUnknownWithoutMutationOrAutomaticReplay(String scenario)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());var store=new DeploymentExecutionStore(context.db());
                var server=new ServerId("server");String fingerprint="local-diagnostic-peer";
                originalPublication(runtime,task,false,DeploymentAgentInputs.hash(server.id()+":"+fingerprint),DeploymentAgentInputs.targetProfile(context.servers().get(server,task)));
                var formal=store.findForTask(task).orElseThrow();var template=store.publicationTemplate(formal).orElseThrow();var unit=template.graph().components().getFirst().unit();
                var installedProgram=new DeploymentAgentPublicationRecoveryProgram(unit.layout());
                var installation=DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"b".repeat(64),installedProgram);
                try(var ready=installedProgram.readiness(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,ready);installation.result(ready.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"));}
                try(var install=installedProgram.install(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,install);installation.result(install.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),install.action().toString().replace("-","")));}
                var installationFrames=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList();
                var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(70),"PUBLICATION_RECOVERY_FAILURE=UNKNOWN\n","",false)),Optional.of(new Problem(ProblemCode.IO_FAILURE,"SSH_COMMAND_NONZERO","Local rollback peer returned a nonzero exit",Map.of(),Optional.empty()))),false);
                var oldResult=log.events(task).getLast();compensationObservation(context,task,true);
                var eligible=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                appendV1DiagnosticFixture(task,log,eligible,unit.layout(),"ENV_FAIL");
                var originalV1=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryDiagnostic.EVENT)).toList();
                var preflightEligible=DeploymentAgentPublicationRecoveryPreflight.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                var program=new DeploymentAgentPublicationRecoveryPreflightProgram(unit.layout());
                var reviews=new java.util.concurrent.atomic.AtomicInteger();var grants=new java.util.concurrent.atomic.AtomicInteger();var sends=new java.util.concurrent.atomic.AtomicInteger();
                var granted=new AtomicReference<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant>();var expectedCommand=new AtomicReference<String>();var diagnosticAction=new AtomicReference<UUID>();
                var ai=(gold.debug.windowstolinux.appapi.ai.AiService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ai.AiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class},(p,m,a)->{if(m.getName().equals("configurationRevision"))return "b".repeat(64);throw new AssertionError("No provider call in local diagnostic fixture");});
                var reviewedContext=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},(p,m,a)->m.getName().equals("ai")?ai:invoke(m,context,a));
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel model=(instructions,facts,intent,command,revision,supplied)->{
                    assertTrue(supplied==task);assertEquals(plan.sha256(),intent.revision());reviews.incrementAndGet();
                    var node=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts);
                    assertEquals(program.source(),node.path("completeExecutableInput").asText());assertEquals(program.sourceVersion(),node.at("/publicationRecoveryPreflight/expectedSourceVersion").asText());
                    assertEquals(old.event(),node.at("/publicationRecoveryPreflight/binding/original/pendingIntentEvent").longValue());
                    diagnosticAction.set(UUID.fromString(node.at("/publicationRecoveryPreflight/action").asText()));expectedCommand.set(command.script());
                    return new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel.Result(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision(scenario.equals("deny")?"DENY":"APPROVE",false,"Local diagnostic decision"),Map.of());
                };
                var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
                    if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("Diagnostic cannot bypass APP");
                    grants.incrementAndGet();var value=new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID());granted.set(value);return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(value),Optional.empty());
                });
                var session=(gold.debug.windowstolinux.appapi.linux.session.RemoteSession)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class},(p,m,a)->{
                    if(m.getName().equals("facts"))return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("Linux","x86_64",fingerprint);
                    if(!m.getName().equals("execute"))throw new AssertionError("No mutation or other remote capability");
                    var command=(gold.debug.windowstolinux.appapi.linux.command.CommandRequest)a[0];assertEquals(expectedCommand.get(),command.script());assertEquals(granted.get(),a[1]);sends.incrementAndGet();
                    try(var expected=program.request(preflightBinding(preflightEligible),diagnosticAction.get())) {
                        assertEquals(expected.command().script(),command.script());assertTrue(java.util.Arrays.equals(expected.command().stdin(),command.stdin()));
                        if(scenario.equals("timeout"))return new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(new Problem(ProblemCode.UNKNOWN,"LOCAL_DIAGNOSTIC_TIMEOUT","Local peer timeout",Map.of(),Optional.empty())));
                        String output=scenario.equals("partial")?"PUBLICATION_RECOVERY_PREFLIGHT={":"PUBLICATION_RECOVERY_PREFLIGHT="+preflightSafeReceipt(program,expected)+"\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",scenario.equals("partial"))),Optional.empty());
                    }
                });
                var scope=new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope(reviewedContext,host,task,"MANUAL_REVIEW",false,()->"b".repeat(64),null,gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE,()->model);
                var candidates=new java.util.LinkedHashMap<String,gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate>();
                for(var component:plan.components())candidates.put(component.id(),new gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate(component.candidate(),"f".repeat(64),"e".repeat(64),"0".repeat(64),false,Map.of()));
                assertFalse(new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run());
                assertEquals(1,reviews.get());assertEquals(scenario.equals("deny")?0:1,grants.get());assertEquals(scenario.equals("deny")?0:1,sends.get());
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());assertEquals(oldResult.id(),state.lastActionEvent());assertTrue(state.verified().isEmpty());
                assertTrue(DeploymentAgentPublicationRecoveryPreflight.attempted(log.events(task),preflightEligible));assertEquals(originalV1,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryDiagnostic.EVENT)).toList());assertTrue(log.events(task).contains(oldResult));
                assertEquals(installationFrames,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList());
                assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));assertTrue(log.events(task).stream().noneMatch(e->Set.of("AI_PUBLICATION_FINISHED","AI_PUBLICATION_CLEANED","AI_RECOVERY_SETTLED",DeploymentAgentPublicationCompensation.VERIFIED).contains(e.type())));
                try(var repeated=program.request(preflightBinding(preflightEligible),UUID.randomUUID())){assertThrows(Exception.class,()->DeploymentAgentPublicationRecoveryPreflight.open(task,log,preflightEligible,repeated,gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server,plan.sha256(),repeated.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION,repeated.resourceTargets()))));}
                // A future explicit normal observation can still prove the goal; the diagnostic is never a permanent latch.
                compensationObservation(context,task,false);DeploymentAgentPublicationCompensation.observedOnly(task,log,old);assertTrue(log.events(task).contains(oldResult));
                completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));
                return success();
            });
        }
    }

    /** Authentic original TaskWork/journal; APP model and host/SSH are local capability peers, not real remote acceptance. */
    @ParameterizedTest
    @ValueSource(strings={"complete","partial","timeout","deny"})
    void failedRollbackDiagnosticEndsUnknownWithoutMutationOrAutomaticReplay(String scenario)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());var store=new DeploymentExecutionStore(context.db());
                var server=new ServerId("server");String fingerprint="local-diagnostic-peer";
                originalPublication(runtime,task,false,DeploymentAgentInputs.hash(server.id()+":"+fingerprint),DeploymentAgentInputs.targetProfile(context.servers().get(server,task)));
                var formal=store.findForTask(task).orElseThrow();var template=store.publicationTemplate(formal).orElseThrow();var unit=template.graph().components().getFirst().unit();
                var installedProgram=new DeploymentAgentPublicationRecoveryProgram(unit.layout());
                var installation=DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"b".repeat(64),installedProgram);
                try(var ready=installedProgram.readiness(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,ready);installation.result(ready.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"));}
                try(var install=installedProgram.install(unit,task.id(),UUID.randomUUID())){installationFixtureDispatch(installation,install);installation.result(install.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),install.action().toString().replace("-","")));}
                var installationFrames=log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList();
                var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);var old=compensationIntent(context,task,plan,UUID.randomUUID());
                DeploymentAgentPublicationCompensation.acknowledge(task,log,old,new OperationResult<>(TaskResult.FAILED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(70),"PUBLICATION_RECOVERY_FAILURE=UNKNOWN\n","",false)),Optional.of(new Problem(ProblemCode.IO_FAILURE,"SSH_COMMAND_NONZERO","Local rollback peer returned a nonzero exit",Map.of(),Optional.empty()))),false);
                var oldResult=log.events(task).getLast();compensationObservation(context,task,true);
                var eligible=DeploymentAgentPublicationCompensation.diagnosticEligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
                var program=new DeploymentAgentPublicationRecoveryDiagnosticProgram(unit.layout());
                var reviews=new java.util.concurrent.atomic.AtomicInteger();var grants=new java.util.concurrent.atomic.AtomicInteger();var sends=new java.util.concurrent.atomic.AtomicInteger();
                var granted=new AtomicReference<gold.debug.windowstolinux.appapi.task.action.ApprovalGrant>();var expectedCommand=new AtomicReference<String>();var diagnosticAction=new AtomicReference<UUID>();
                var ai=(gold.debug.windowstolinux.appapi.ai.AiService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.ai.AiService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.ai.AiService.class},(p,m,a)->{if(m.getName().equals("configurationRevision"))return "b".repeat(64);throw new AssertionError("No provider call in local diagnostic fixture");});
                var reviewedContext=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},(p,m,a)->m.getName().equals("ai")?ai:invoke(m,context,a));
                gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel model=(instructions,facts,intent,command,revision,supplied)->{
                    assertTrue(supplied==task);assertEquals(plan.sha256(),intent.revision());reviews.incrementAndGet();
                    var node=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(facts);
                    assertEquals(program.source(),node.path("completeExecutableInput").asText());assertEquals(program.sourceVersion(),node.at("/publicationRecoveryDiagnostic/expectedSourceVersion").asText());
                    assertEquals(old.event(),node.at("/publicationRecoveryDiagnostic/binding/pendingIntentEvent").longValue());
                    diagnosticAction.set(UUID.fromString(node.at("/publicationRecoveryDiagnostic/action").asText()));expectedCommand.set(command.script());
                    return new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewModel.Result(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision(scenario.equals("deny")?"DENY":"APPROVE",false,"Local diagnostic decision"),Map.of());
                };
                var host=(gold.debug.windowstolinux.appapi.task.action.InteractionService)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.task.action.InteractionService.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.task.action.InteractionService.class},(p,m,a)->{
                    if(!m.getName().equals("approveReviewedAction"))throw new AssertionError("Diagnostic cannot bypass APP");
                    grants.incrementAndGet();var value=new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID());granted.set(value);return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(value),Optional.empty());
                });
                var session=(gold.debug.windowstolinux.appapi.linux.session.RemoteSession)Proxy.newProxyInstance(gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class.getClassLoader(),new Class<?>[]{gold.debug.windowstolinux.appapi.linux.session.RemoteSession.class},(p,m,a)->{
                    if(m.getName().equals("facts"))return new gold.debug.windowstolinux.appapi.linux.session.RemoteFacts("Linux","x86_64",fingerprint);
                    if(!m.getName().equals("execute"))throw new AssertionError("No mutation or other remote capability");
                    var command=(gold.debug.windowstolinux.appapi.linux.command.CommandRequest)a[0];assertEquals(expectedCommand.get(),command.script());assertEquals(granted.get(),a[1]);sends.incrementAndGet();
                    try(var expected=program.request(diagnosticBinding(eligible),diagnosticAction.get())) {
                        assertEquals(expected.command().script(),command.script());assertTrue(java.util.Arrays.equals(expected.command().stdin(),command.stdin()));
                        if(scenario.equals("timeout"))return new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(new Problem(ProblemCode.UNKNOWN,"LOCAL_DIAGNOSTIC_TIMEOUT","Local peer timeout",Map.of(),Optional.empty())));
                        String output=scenario.equals("partial")?"PUBLICATION_RECOVERY_DIAGNOSTIC={":"PUBLICATION_RECOVERY_DIAGNOSTIC="+diagnosticSafeReceipt(program,expected)+"\n";
                        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),output,"",scenario.equals("partial"))),Optional.empty());
                    }
                });
                var scope=new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCommandReviewScope(reviewedContext,host,task,"MANUAL_REVIEW",false,()->"b".repeat(64),null,gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentCurrentCommandEvidence.NONE,()->model);
                var candidates=new java.util.LinkedHashMap<String,gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate>();
                for(var component:plan.components())candidates.put(component.id(),new gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher.Candidate(component.candidate(),"f".repeat(64),"e".repeat(64),"0".repeat(64),false,Map.of()));
                assertFalse(new DeploymentAgentPublicationRecovery(reviewedContext,scope,session,server,task,"b".repeat(64),log.find(task).orElseThrow(),new DeploymentPublishResult(template.graph(),template.units()),candidates,log).run());
                assertEquals(1,reviews.get());assertEquals(scenario.equals("deny")?0:1,grants.get());assertEquals(scenario.equals("deny")?0:1,sends.get());
                var state=DeploymentAgentPublicationCompensation.read(log.events(task),task.id());assertEquals(Optional.of(old),state.pending());assertEquals(0,state.completed());assertEquals(oldResult.id(),state.lastActionEvent());assertTrue(state.verified().isEmpty());
                assertTrue(DeploymentAgentPublicationRecoveryDiagnostic.attempted(log.events(task),old));assertTrue(log.events(task).contains(oldResult));
                assertEquals(installationFrames,log.events(task).stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)).toList());
                assertFalse(DeploymentAgentActionLog.effectsSettled(log.events(task)));assertTrue(log.events(task).stream().noneMatch(e->Set.of("AI_PUBLICATION_FINISHED","AI_PUBLICATION_CLEANED","AI_RECOVERY_SETTLED",DeploymentAgentPublicationCompensation.VERIFIED).contains(e.type())));
                try(var repeated=program.request(diagnosticBinding(eligible),UUID.randomUUID())){assertThrows(Exception.class,()->DeploymentAgentPublicationRecoveryDiagnostic.open(task,log,eligible,repeated,gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server,plan.sha256(),repeated.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION,repeated.resourceTargets()))));}
                // A future explicit normal observation can still prove the goal; the diagnostic is never a permanent latch.
                compensationObservation(context,task,false);DeploymentAgentPublicationCompensation.observedOnly(task,log,old);assertTrue(log.events(task).contains(oldResult));
                completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));
                return success();
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "FAILED", "FINISHED", "REFERENCE_ACK_LOST"})
    void verifiedCompensationResumesOnlyLocalFailureTail(String window) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true); server(runtime);
            run(runtime, task -> {
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());var store=new DeploymentExecutionStore(context.db());
                originalPublication(runtime,task,false);
                var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);completeCompensation(context,task,plan,false);
                var id=store.findForTask(task).orElseThrow().identity().executionId();
                if(window.equals("UNKNOWN")) store.recordUncommittedOutcome(task,id,TaskResult.UNKNOWN,
                    new Problem(ProblemCode.UNKNOWN,"FIXTURE_ORIGINAL_UNKNOWN","Original publication remains unknown",Map.of(),Optional.empty()),java.time.Instant.now());
                if(window.equals("REFERENCE_ACK_LOST")) {
                    var once=new java.util.concurrent.atomic.AtomicBoolean();var servers=context.servers();
                    var losing=(gold.debug.windowstolinux.appapi.server.ServerService)Proxy.newProxyInstance(servers.getClass().getClassLoader(),
                        new Class<?>[]{gold.debug.windowstolinux.appapi.server.ServerService.class},(proxy,method,args)->{
                            var result=invoke(method,servers,args);
                            if(method.getName().equals("resolve")&&once.compareAndSet(false,true))throw new ApiException(new Problem(ProblemCode.UNKNOWN,"FIXTURE_REFERENCE_ACK_LOST","Local fixture acknowledgement",Map.of(),Optional.empty()));
                            return result;
                        });
                    var losingContext=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},
                        (proxy,method,args)->method.getName().equals("servers")?losing:invoke(method,context,args));
                    assertEquals(TaskResult.UNKNOWN,new DeploymentOriginalPublishCommit(losingContext).completeUncommittedVerified(task,id,TaskResult.FAILED,DeploymentAgentPublicationCompensation.failure()).outcome());
                    assertEquals(Optional.of(TaskResult.FAILED),store.findForTask(task).orElseThrow().outcome());
                } else if(!window.equals("UNKNOWN")) {
                    assertEquals(TaskResult.FAILED,new DeploymentOriginalPublishCommit(context).completeUncommittedVerified(task,id,TaskResult.FAILED,DeploymentAgentPublicationCompensation.failure()).outcome());
                    if(window.equals("FINISHED"))assertEquals(TaskResult.FAILED,new DeploymentOriginalPublishCommit(context).finishKnown(task,store.findForTask(task).orElseThrow()).outcome());
                }
                var linux=new java.util.concurrent.atomic.AtomicInteger();var ai=new java.util.concurrent.atomic.AtomicInteger();
                var localOnly=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},(proxy,method,args)->{
                    if(method.getName().equals("linux")){linux.incrementAndGet();throw new AssertionError("Verified local tail cannot obtain Linux");}
                    if(method.getName().equals("ai")){ai.incrementAndGet();throw new AssertionError("Verified local tail cannot obtain AI");}
                    return invoke(method,context,args);
                });
                var recovery=new DeploymentAgentRecovery(localOnly,log);
                assertEquals(TaskResult.FAILED,recovery.reconcile(task,log.find(task).orElseThrow()).outcome());
                var settled=log.events(task);assertEquals(TaskResult.FAILED,recovery.reconcile(task,log.find(task).orElseThrow()).outcome());
                assertEquals(settled,log.events(task));assertEquals(0,linux.get());assertEquals(0,ai.get());
                assertEquals(DeploymentAgentPublicationCompensation.FAILURE,store.problem(store.findForTask(task).orElseThrow()).orElseThrow().eventCode());
                assertEquals(TaskResult.FAILED,new DeploymentAgentHistoryArchive(context).settle(task,new OperationResult<>(TaskResult.FAILED,Optional.empty(),Optional.of(DeploymentAgentPublicationCompensation.failure()))).outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending-action", "pending-install", "foreign-failure"})
    void pendingPublicationRecoveryNeverResendsUnknownMutation(String state) throws Exception {
        enable();
        try (var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());var store=new DeploymentExecutionStore(context.db());
                originalPublication(runtime,task,false);
                if(state.equals("pending-install")) {
                    var formal=store.findForTask(task).orElseThrow();var unit=store.publicationTemplate(formal).orElseThrow().graph().components().getFirst().unit();
                    var program=new DeploymentAgentPublicationRecoveryProgram(unit.layout());
                    var installation=DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"b".repeat(64),program);
                    try(var ready=program.readiness(unit,task.id(),UUID.randomUUID())) {
                        installationFixtureDispatch(installation,ready);
                        installation.result(ready.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"));
                    }
                    UUID originalAction=UUID.randomUUID();
                    try(var install=program.install(unit,task.id(),originalAction)) {
                        installationFixtureDispatch(installation,install);
                        installation.result(install.action(),new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(DeploymentAgentPublicationCompensation.failure())),null);
                    }
                    var unchanged=log.events(task);
                    try(var repeated=program.install(unit,task.id(),UUID.randomUUID())) {
                        assertThrows(Exception.class,()->installation.request(repeated,gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(unit.server(),installation.state().binding(),repeated.command())));
                    }
                    assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.begin(context,task,log));assertEquals(unchanged,log.events(task));
                    assertTrue(!installation.state().ready()&&!installation.state().mayInstall());
                    var contradictory=new ArrayList<>(unchanged);var unknown=contradictory.getLast();
                    var falseNotSent=DeploymentJsonDocument.parse(unknown.detail());falseNotSent.put("resolution","NOT_SENT");
                    contradictory.set(contradictory.size()-1,new DeploymentAgentActionLog.Event(unknown.id(),unknown.type(),unknown.action(),DeploymentJsonDocument.encode(falseNotSent)));
                    assertThrows(Exception.class,()->DeploymentAgentPublicationRecoveryInstallation.read(contradictory));
                    for(var unconfirmed:List.of(new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"),
                        new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",UUID.randomUUID().toString(),originalAction.toString().replace("-","")),
                        new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),UUID.randomUUID().toString().replace("-","")))) {
                        try(var query=program.readiness(unit,task.id(),UUID.randomUUID())) {
                            installationFixtureDispatch(installation,query);installation.result(query.action(),localReceipt(),unconfirmed);
                        }
                        assertFalse(installation.state().ready());assertFalse(installation.state().mayInstall());assertEquals(Optional.of(originalAction),installation.state().installAction());
                        var retained=log.events(task);
                        try(var repeated=program.install(unit,task.id(),UUID.randomUUID())) {assertThrows(Exception.class,()->installationFixtureRequest(installation,repeated));}
                        assertEquals(retained,log.events(task));
                    }
                    // Only a new read-only receipt for the original version/task/install operation closes this fixture's uncertainty.
                    try(var query=program.readiness(unit,task.id(),UUID.randomUUID())) {
                        installationFixtureDispatch(installation,query);
                        installation.result(query.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),originalAction.toString().replace("-","")));
                    }
                    assertTrue(installation.state().ready());
                    var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);completeCompensation(context,task,plan,false);
                    new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));
                } else if(state.equals("pending-action")) {
                    var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);var intent=compensationIntent(context,task,plan,UUID.randomUUID());
                    var malformed=new OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult>(TaskResult.SUCCEEDED,
                        Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),"MALFORMED_FIXED_RECEIPT\n","fixture stderr",false)),Optional.empty());
                    DeploymentAgentPublicationCompensation.acknowledge(task,log,intent,malformed,false);
                    var actual=DeploymentJsonDocument.parse(log.events(task).getLast().detail());assertEquals("UNKNOWN",DeploymentJsonDocument.string(actual,"resolution"));
                    assertEquals("SUCCEEDED",DeploymentJsonDocument.string(actual,"observedOutcome"));assertTrue(!actual.path("fixedReceiptVerified").booleanValue());
                    var digest=DeploymentJsonDocument.object().put("formatVersion",1).put("outcome","SUCCEEDED").put("stdout",DeploymentAgentInputs.hash("MALFORMED_FIXED_RECEIPT\n"))
                        .put("stderr",DeploymentAgentInputs.hash("fixture stderr")).put("truncated",false).put("exitCode",0).putNull("problemCode").putNull("eventCode");
                    assertEquals(DeploymentAgentInputs.hash(DeploymentJsonDocument.encode(digest)),DeploymentJsonDocument.string(actual,"receipt"));
                    var unchanged=log.events(task);assertThrows(Exception.class,()->compensationIntent(context,task,plan,UUID.randomUUID()));
                    assertThrows(Exception.class,()->DeploymentAgentPublicationCompensation.acknowledge(task,log,intent,localReceipt()));assertEquals(unchanged,log.events(task));
                    compensationObservation(context,task,false);DeploymentAgentPublicationCompensation.observedOnly(task,log,intent);
                    completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));
                } else {
                    var formal=store.findForTask(task).orElseThrow();
                    var foreign=new Problem(ProblemCode.IO_FAILURE,"FOREIGN_KNOWN_FAILURE","Unrelated fixed fixture failure",Map.of(),Optional.empty());
                    assertEquals(TaskResult.FAILED,new DeploymentOriginalPublishCommit(context).completeUncommittedVerified(task,formal.identity().executionId(),TaskResult.FAILED,foreign).outcome());
                    var calls=new java.util.concurrent.atomic.AtomicInteger();
                    var localOnly=(ModContext)Proxy.newProxyInstance(ModContext.class.getClassLoader(),new Class<?>[]{ModContext.class},(proxy,method,args)->{
                        if(Set.of("linux","ai").contains(method.getName())){calls.incrementAndGet();throw new AssertionError("Known failure without Verified cannot dispatch");}return invoke(method,context,args);
                    });
                    assertEquals(TaskResult.UNKNOWN,new DeploymentAgentRecovery(localOnly,log).reconcile(task,log.find(task).orElseThrow()).outcome());
                    assertEquals(0,calls.get());assertEquals("FOREIGN_KNOWN_FAILURE",store.problem(store.findForTask(task).orElseThrow()).orElseThrow().eventCode());
                    // Close only real fixture reservations; do not fabricate Agent settlement or a successful archive.
                    new DeploymentOriginalPublishCommit(context).finishKnown(task,store.findForTask(task).orElseThrow());
                    assertTrue(!DeploymentAgentActionLog.effectsSettled(log.events(task)));
                }
                return success();
            });
        }
    }

    /** Real TaskWork and journal, with local codec receipts only; no APP peer, host consumption or SSH execution. */
    @ParameterizedTest
    @ValueSource(strings={"approve-new","deny-again","reused-action","reused-grant","changed-version","changed-review","approved-not-sent"})
    void deniedUnsentInstallationRequiresFreshIndependentAttempt(String scenario)throws Exception {
        enable();
        try(var runtime=open()) {
            start(runtime,true);server(runtime);
            run(runtime,task->{
                var context=runtime.modContexts().get(OWNER).context();var log=new DeploymentAgentActionLog(context.db());var store=new DeploymentExecutionStore(context.db());
                originalPublication(runtime,task,false);var formal=store.findForTask(task).orElseThrow();var unit=store.publicationTemplate(formal).orElseThrow().graph().components().getFirst().unit();
                var program=new DeploymentAgentPublicationRecoveryProgram(unit.layout());var installation=DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"b".repeat(64),program);
                UUID firstReadinessGrant=UUID.randomUUID();
                try(var query=program.readiness(unit,task.id(),UUID.randomUUID())) {
                    installationFixtureDispatch(installation,query,firstReadinessGrant);installation.result(query.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"));
                }
                UUID firstInstall=UUID.randomUUID();boolean approvedUnsent=scenario.equals("approved-not-sent");
                var unsentInstallActions=new java.util.HashSet<String>();unsentInstallActions.add(firstInstall.toString());
                try(var request=program.install(unit,task.id(),firstInstall)) {
                    installationFixtureRequest(installation,request);installation.reviewed(request.action(),new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision(approvedUnsent?"APPROVE":"DENY",false,"Local unsent installation fixture"),"5".repeat(64));
                    installation.retainInterrupted();
                }
                var preserved=log.events(task);var notSent=DeploymentJsonDocument.parse(preserved.getLast().detail());
                assertEquals("NOT_SENT",DeploymentJsonDocument.string(notSent,"resolution"));assertFalse(installation.state().ready());assertFalse(installation.state().mayInstall());
                assertEquals(approvedUnsent?Optional.of(firstInstall):Optional.empty(),installation.state().installAction());
                assertFalse(installation.state().attempt().orElseThrow().granted());assertFalse(installation.state().attempt().orElseThrow().dispatched());
                assertThrows(Exception.class,()->installation.granted(firstInstall,new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(UUID.randomUUID())));
                assertThrows(Exception.class,()->installation.dispatch(firstInstall));
                try(var tooSoon=program.install(unit,task.id(),UUID.randomUUID())) {assertThrows(Exception.class,()->installationFixtureRequest(installation,tooSoon));}
                assertEquals(preserved,log.events(task));
                if(scenario.equals("changed-version")) {
                    var paths=unit.layout().paths();var other=new DeploymentAgentPublicationRecoveryProgram(new gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout(
                        new gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig.RemotePaths(paths.helperDirectory()+"-next",paths.applicationsRoot()+"-next",paths.stateRoot()+"-next",paths.configurationRoot()+"-next",paths.dataRoot()+"-next")));
                    assertFalse(program.version().equals(other.version()));assertThrows(Exception.class,()->DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"b".repeat(64),other));
                    assertEquals(preserved,log.events(task));
                }
                if(scenario.equals("changed-review")) {
                    assertThrows(Exception.class,()->DeploymentAgentPublicationRecoveryInstallation.open(task,log,formal,"c".repeat(64),program));assertEquals(preserved,log.events(task));
                }
                // A subsequent caller must obtain another independently reviewed strict MISSING before proposing an install.
                try(var query=program.readiness(unit,task.id(),UUID.randomUUID())) {
                    installationFixtureDispatch(installation,query);installation.result(query.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("MISSING","-","-"));
                }
                if(approvedUnsent) {
                    assertFalse(installation.state().mayInstall());assertEquals(Optional.of(firstInstall),installation.state().installAction());
                    var retained=log.events(task);try(var repeated=program.install(unit,task.id(),UUID.randomUUID())) {assertThrows(Exception.class,()->installationFixtureRequest(installation,repeated));}assertEquals(retained,log.events(task));
                    // Finish the local receipt fixture through a read-only exact owner receipt, without dispatching another install.
                    try(var query=program.readiness(unit,task.id(),UUID.randomUUID())) {
                        installationFixtureDispatch(installation,query);installation.result(query.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),firstInstall.toString().replace("-","")));
                    }
                } else {
                    assertTrue(installation.state().mayInstall());assertTrue(installation.state().installAction().isEmpty());
                    if(scenario.equals("reused-action")) {
                        var retained=log.events(task);try(var repeated=program.install(unit,task.id(),firstInstall)) {assertThrows(Exception.class,()->installationFixtureRequest(installation,repeated));}assertEquals(retained,log.events(task));
                    }
                    UUID secondInstall=UUID.randomUUID();
                    try(var request=program.install(unit,task.id(),secondInstall)) {
                        installationFixtureRequest(installation,request);installation.reviewed(secondInstall,new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision(scenario.equals("deny-again")?"DENY":"APPROVE",false,"New local installation decision"),"6".repeat(64));
                        if(scenario.equals("deny-again")) {
                            unsentInstallActions.add(secondInstall.toString());
                            installation.retainInterrupted();assertFalse(installation.state().ready());assertFalse(installation.state().mayInstall());assertTrue(installation.state().installAction().isEmpty());
                            assertFalse(installation.state().attempt().orElseThrow().granted());assertFalse(installation.state().attempt().orElseThrow().dispatched());
                            var retained=log.events(task);try(var automaticRetry=program.install(unit,task.id(),UUID.randomUUID())) {assertThrows(Exception.class,()->installationFixtureRequest(installation,automaticRetry));}assertEquals(retained,log.events(task));
                        } else {
                            if(scenario.equals("reused-grant")) {
                                var retained=log.events(task);assertThrows(Exception.class,()->installation.granted(secondInstall,new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(firstReadinessGrant)));assertEquals(retained,log.events(task));
                            }
                            UUID freshGrant=UUID.randomUUID();assertFalse(firstReadinessGrant.equals(freshGrant));installation.granted(secondInstall,new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(freshGrant));installation.dispatch(secondInstall);
                            installation.result(secondInstall,localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),secondInstall.toString().replace("-","")));
                            assertEquals(Optional.of(secondInstall),installation.state().installAction());
                        }
                    }
                    if(scenario.equals("deny-again")) {
                        // A separately observed immutable cache closes this codec fixture; neither denied install was dispatched.
                        try(var query=program.readiness(unit,task.id(),UUID.randomUUID())) {
                            installationFixtureDispatch(installation,query);installation.result(query.action(),localReceipt(),new DeploymentAgentPublicationRecoveryProgram.StoreReceipt("READY",task.id().toString(),UUID.randomUUID().toString().replace("-","")));
                        }
                    }
                }
                assertEquals(preserved,log.events(task).subList(0,preserved.size()));assertTrue(installation.state().ready());
                long deniedInstallDispatches=0;
                for(var event:log.events(task))if(event.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)) {
                    var frame=DeploymentJsonDocument.parse(event.detail());
                    if(frame.path("kind").asText().equals("DISPATCH")&&unsentInstallActions.contains(frame.path("action").asText()))deniedInstallDispatches++;
                }
                assertEquals(0,deniedInstallDispatches);
                var plan=DeploymentAgentPublicationCompensation.begin(context,task,log);completeCompensation(context,task,plan,false);new DeploymentAgentHistoryArchive(context).settle(task,finishCompensation(context,task));
                return success();
            });
            valid(runtime);
        }
    }

    private static OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> localReceipt(){return new OperationResult<>(TaskResult.SUCCEEDED,
        Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),"LOCAL_RECEIPT_FIXTURE=1\n","",false)),Optional.empty());}

    /** Installation codec fixture only: no real APP, grant consumption, SSH, sidecar parser or registry integration is claimed. */
    private static void installationFixtureDispatch(DeploymentAgentPublicationRecoveryInstallation installation,DeploymentAgentPublicationRecoveryProgram.Request request)throws Exception {
        installationFixtureDispatch(installation,request,UUID.randomUUID());
    }

    private static void installationFixtureRequest(DeploymentAgentPublicationRecoveryInstallation installation,DeploymentAgentPublicationRecoveryProgram.Request request)throws Exception {
        var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(request.unit().server(),installation.state().binding(),request.command())
            .withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(request.operation(),request.resourceTargets()));
        installation.request(request,intent);
    }

    private static void installationFixtureDispatch(DeploymentAgentPublicationRecoveryInstallation installation,DeploymentAgentPublicationRecoveryProgram.Request request,UUID grant)throws Exception {
        installationFixtureRequest(installation,request);installation.reviewed(request.action(),new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Local installation receipt fixture"),"5".repeat(64));
        installation.granted(request.action(),new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(grant));installation.dispatch(request.action());
    }

    @Test
    void failedCompensationProofClosesOnlyOriginalDelivery() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true); server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                originalPublication(runtime, task, false);
                var plan = DeploymentAgentPublicationCompensation.begin(context, task, new DeploymentAgentActionLog(context.db()));
                completeCompensation(context, task, plan, false);
                var result = finishCompensation(context, task);
                var events = new DeploymentAgentActionLog(context.db()).events(task);
                assertTrue(!DeploymentAgentActionLog.effectsSettled(events));
                assertTrue(events.stream().noneMatch(e -> Set.of("DELIVERY_RESULT", "AI_PUBLICATION_FINISHED", DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED).contains(e.type())));
                var archive = new DeploymentAgentHistoryArchive(context);
                var formal = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
                try (var held = context.db().openConnection(task)) {
                    assertFalse(context.tasks().resourcesReleased(task));
                    assertEquals(TaskResult.UNKNOWN, archive.settle(task, result).outcome());
                    assertEquals(events, new DeploymentAgentActionLog(context.db()).events(task));
                    assertEquals(formal, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow());
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                }
                assertTrue(context.tasks().resourcesReleased(task));
                assertEquals(TaskResult.FAILED, archive.settle(task, result).outcome());
                assertEquals(TaskResult.FAILED, archive.retained(task).orElseThrow().outcome());
                try (var connection = context.db().openConnection(task)) {
                    var receipt = DeploymentOperationReceipts.read(connection, task.id()).orElseThrow();
                    assertEquals(DeploymentAgentPublicationCompensation.FAILURE, receipt.eventCode());
                    assertTrue(receipt.applicationId().isEmpty() && receipt.release().isEmpty());
                }
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM managed_application"));
                assertTrue(new DeploymentAgentActionLog(context.db()).find(task).isEmpty());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot-owner", "snapshot-result", "snapshot-duplicate", "delivery-duplicate", "all-current", "all-empty", "source", "unknown-action", "reused-grant", "new-intent-after-verified", "latest-observation-incomplete", "foreign-proof"})
    void failedCompensationRejectsChangedOrUnresolvedEvidence(String change) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true); server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context(); var log = new DeploymentAgentActionLog(context.db());
                originalPublication(runtime, task, false);
                var initialEvents = log.events(task);
                if (change.startsWith("snapshot") || change.equals("delivery-duplicate") || change.equals("all-current") || change.equals("all-empty")) {
                    if (change.equals("all-current") || change.equals("all-empty")) compensationObservation(context, task, change.equals("all-current") ? 2 : 3);
                    if (change.equals("snapshot-duplicate")) log.event(task, "SNAPSHOT_RESULT", "web", "none:0:none:none");
                    else if (change.equals("delivery-duplicate")) log.event(task, "DELIVERY_EXECUTING", "2".repeat(64), "1");
                    else if (change.startsWith("snapshot")) try (var connection = context.db().openConnection(task); var update = connection.prepareStatement(
                        "UPDATE deployment_agent_event SET event_detail=? WHERE task_id=? AND event_type=?")) {
                        update.setString(1, change.equals("snapshot-owner") ? "0".repeat(64) : "none:1:none:none");
                        update.setString(2, task.id().toString()); update.setString(3, change.equals("snapshot-owner") ? "SNAPSHOT_EXECUTING" : "SNAPSHOT_RESULT"); update.executeUpdate();
                    }
                    assertThrows(Exception.class, () -> DeploymentAgentPublicationCompensation.begin(context, task, log));
                    requireReservedOriginalReferences(context, task);
                    // Restore deliberately corrupted fixture bytes only; this is not a recovery implementation.
                    restoreEvents(context, task, initialEvents);
                    var valid = DeploymentAgentPublicationCompensation.begin(context, task, log);
                    completeCompensation(context, task, valid, false);
                    new DeploymentAgentHistoryArchive(context).settle(task, finishCompensation(context, task));
                } else {
                    var plan = DeploymentAgentPublicationCompensation.begin(context, task, log);
                    if (change.equals("unknown-action") || change.equals("reused-grant")) {
                        var grant = UUID.randomUUID(); var intent = compensationIntent(context, task, plan, grant);
                        if (change.equals("reused-grant")) {
                            DeploymentAgentPublicationCompensation.acknowledge(task, log, intent, new OperationResult<>(TaskResult.SUCCEEDED,
                                Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0), "LOCAL_RECEIPT_FIXTURE=1\n", "", false)), Optional.empty()));
                            var unchanged = log.events(task);
                            assertThrows(Exception.class, () -> compensationIntent(context, task, plan, grant));
                            assertEquals(unchanged, log.events(task));
                        } else {
                            DeploymentAgentPublicationCompensation.acknowledge(task, log, intent, new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(DeploymentAgentPublicationCompensation.failure())));
                            assertTrue(DeploymentAgentPublicationCompensation.read(log.events(task), task.id()).pending().isPresent());
                            assertThrows(Exception.class, () -> compensationIntent(context, task, plan, UUID.randomUUID()));
                            assertThrows(Exception.class, () -> DeploymentAgentPublicationCompensation.verify(context, task, log));
                            requireReservedOriginalReferences(context, task);
                            compensationObservation(context, task, false);
                            DeploymentAgentPublicationCompensation.observedOnly(task, log, intent);
                        }
                        completeCompensation(context, task, plan, false);
                        new DeploymentAgentHistoryArchive(context).settle(task, finishCompensation(context, task));
                    } else {
                        completeCompensation(context, task, plan, false);
                        var result = finishCompensation(context, task);
                        var settledEvents = log.events(task);
                        if (change.equals("source")) try (var connection = context.db().openConnection(task); var update = connection.prepareStatement(
                            "UPDATE deployment_agent_event SET event_detail=? WHERE task_id=? AND event_type='SOURCE'")) {
                            update.setString(1, "0".repeat(64)); update.setString(2, task.id().toString()); update.executeUpdate();
                        }
                        if (change.equals("new-intent-after-verified")) log.event(task, "CAPABILITY_EXECUTING", "foreign", "1");
                        if (change.equals("latest-observation-incomplete")) {
                            var old = log.events(task).stream().filter(e -> e.type().equals(DeploymentAgentPublicationObservation.BEGIN)).reduce((a,b) -> b).orElseThrow();
                            log.event(task, old.type(), old.action(), old.detail());
                        }
                        if (change.equals("foreign-proof")) log.event(task, DeploymentAgentPublicationCompensation.DELIVERY_SETTLED, "0".repeat(64), "{}");
                        assertThrows(Exception.class, () -> new DeploymentAgentHistoryArchive(context).settle(task, result));
                        assertTrue(log.find(task).isPresent());
                        assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                        restoreEvents(context, task, settledEvents);
                        new DeploymentAgentHistoryArchive(context).settle(task, result);
                    }
                }
                assertTrue(log.find(task).isEmpty());
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                return success();
            });
        }
    }

    @Test
    void compensationObservationResolvesGoalWithoutInventingOriginalAck() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true); server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context(); var log = new DeploymentAgentActionLog(context.db());
                originalPublication(runtime, task, false);
                var plan = DeploymentAgentPublicationCompensation.begin(context, task, log);
                completeCompensation(context, task, plan, true);
                var events = log.events(task);
                assertEquals(1, events.stream().filter(e -> e.type().equals(DeploymentAgentPublicationCompensation.RESULT)
                    && e.detail().contains("\"resolution\":\"UNKNOWN\"")).count());
                assertEquals(1, events.stream().filter(e -> e.type().equals(DeploymentAgentPublicationCompensation.RESULT)
                    && e.detail().contains("\"resolution\":\"OBSERVED_ONLY\"")).count());
                assertTrue(DeploymentAgentPublicationCompensation.verified(task.id(), log.find(task).orElseThrow(), new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow(),
                    new DeploymentExecutionStore(context.db()).publicationTemplate(new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow()).orElseThrow(), events).event() > plan.readyEvent());
                assertEquals(TaskResult.FAILED, new DeploymentAgentHistoryArchive(context).settle(task, finishCompensation(context, task)).outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"proof", "receipt", "events", "task", "execution", "before_commit", "lost_ack"})
    void failedCompensationArchiveIsAtomicAndReceiptReplayIsLocal(String point) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true); server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context(); var log = new DeploymentAgentActionLog(context.db());
                originalPublication(runtime, task, false);
                var plan = DeploymentAgentPublicationCompensation.begin(context, task, log);
                completeCompensation(context, task, plan, false);
                var result = finishCompensation(context, task); var events = log.events(task);
                var original = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
                assertThrows(Exception.class, () -> new DeploymentAgentHistoryArchive(withDatabase(context, fault(context.db(), point))).settle(task, result));
                if (!point.equals("lost_ack")) {
                    assertEquals(events, log.events(task));
                    assertEquals(original, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow());
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                }
                var strict = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (Set.of("linux", "ai", "file").contains(method.getName())) throw new AssertionError("Local settlement must not redispatch " + method.getName());
                    return invoke(method, context, args);
                });
                assertEquals(TaskResult.FAILED, new DeploymentAgentHistoryArchive(strict).settle(task, result).outcome());
                assertEquals(TaskResult.FAILED, new DeploymentAgentHistoryArchive(strict).retained(task).orElseThrow().outcome());
                return success();
            });
            valid(runtime);
        }
    }

    private static void requireReservedOriginalReferences(ModContext context, TaskContext task) throws Exception {
        assertTrue(context.tasks().resourcesReleased(task));
        var store = new DeploymentExecutionStore(context.db());
        var references = store.references(store.findForTask(task).orElseThrow());
        assertTrue(!references.isEmpty());
        for (var entry : references.entrySet()) {
            var intent = entry.getKey();
            assertEquals(gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent.DeploymentRefKind.SERVER, intent.kind());
            var reference = context.servers().findReference(new ServerId(intent.resourceId()), intent.businessKey(), task).orElseThrow();
            assertEquals(entry.getValue().orElseThrow(), reference.id());
            assertEquals(new ServerId(intent.resourceId()), reference.server());
            assertEquals(intent.businessKey(), reference.businessKey());
            assertEquals(gold.debug.windowstolinux.appapi.task.ref.ResourceRefState.RESERVED, reference.state());
        }
    }

    private static OperationResult<gold.debug.windowstolinux.appapi.linux.command.CommandResult> localReceipt(String stdout) {
        return new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),stdout,"",false)),Optional.empty());
    }
    /** Strict typed fixture only; no remote check or historical current-state fingerprint is asserted. */
    private static Map<String,UUID> appendReadyPreflightFixture(TaskContext task,DeploymentAgentActionLog log,DeploymentAgentPublicationCompensation.DiagnosticEligibility original,
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout)throws Exception {
        appendV1DiagnosticFixture(task,log,original,layout,"ENV_FAIL");
        var eligible=DeploymentAgentPublicationRecoveryPreflight.eligibility(DeploymentAgentPublicationCompensation.read(log.events(task),task.id()),log.events(task)).orElseThrow();
        var program=new DeploymentAgentPublicationRecoveryPreflightProgram(layout);UUID action=UUID.randomUUID(),grant=UUID.randomUUID();
        try(var request=program.request(preflightBinding(eligible),action)) {
            var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(original.plan().server()),original.plan().sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationRecoveryPreflight.open(task,log,eligible,request,intent);durable.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Complete local v2 preflight"),"5".repeat(64));durable.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(grant));durable.dispatch();
            var json=new com.fasterxml.jackson.databind.ObjectMapper();var safe=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(preflightSafeReceipt(program,request));
            for(var row:safe.path("checks"))if(!Set.of("FULL_PARAMETER_VALIDATION","UNIT_RENDER_MATCH","MANAGED_DATA_LINKS").contains(row.path("id").asText())) {
                var check=(com.fasterxml.jackson.databind.node.ObjectNode)row;check.put("status","PASS").put("reason","MATCH").put("flag",true);
                if(Set.of("CANDIDATE_OWNER","CANDIDATE_TASK","MAINTENANCE_OWNER","RELEASE_OWNER","PARAMETERS_FILE","UNIT_FILE").contains(check.path("id").asText()))check.put("sha256",DeploymentAgentInputs.hash("fixture:"+check.path("id").asText()));
            }
            String canonical=program.validateReceipt(request,safe.toString()).canonicalJson();var actual=localReceipt("PUBLICATION_RECOVERY_PREFLIGHT="+canonical+"\n");durable.result(actual,program.parse(request,actual));
        }
        return Map.of("action",action,"grant",grant);
    }
    private static Map<String,UUID> appendContractControlFixture(TaskContext task,DeploymentAgentActionLog log,
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout,boolean control)throws Exception {
        var events=log.events(task);var eligible=DeploymentAgentPublicationContractDiagnostic.eligibility(DeploymentAgentPublicationCompensation.read(events,task.id()),events).orElseThrow();
        var program=new DeploymentAgentPublicationContractDiagnosticProgram(layout);UUID action=UUID.randomUUID(),grant=UUID.randomUUID();
        try(var request=program.request(DeploymentAgentPublicationContractDiagnostic.binding(eligible),action)) {
            var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(eligible.original().plan().server()),eligible.original().plan().sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationContractDiagnosticProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationContractDiagnostic.open(task,log,eligible,request,intent);
            durable.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Immutable original contract review"),"4".repeat(64));durable.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(grant));durable.dispatch();
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(contractDiagnosticSafeReceipt(program,request,false));
            if(control){node.put("status","UNKNOWN").put("phase","CONTROL").put("reason","UNCONFIRMED").putNull("exitCode");for(var check:node.path("checks"))((com.fasterxml.jackson.databind.node.ObjectNode)check).put("status","UNKNOWN").put("reason","NOT_CHECKED").putNull("exitCode");}
            String safe=program.validateReceipt(request,node.toString()).canonicalJson();var actual=localReceipt(DeploymentAgentPublicationContractDiagnosticProgram.FRAME+safe+"\n");durable.result(actual,program.parse(request,actual));
        }
        return Map.of("action",action,"grant",grant);
    }
    private static Map<String,UUID> appendObservedFollowupFixture(TaskContext task,DeploymentAgentActionLog log,
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout,boolean allPass)throws Exception {
        var events=log.events(task);var eligible=DeploymentAgentPublicationContractDiagnosticFollowup.eligibility(DeploymentAgentPublicationCompensation.read(events,task.id()),events).orElseThrow();
        var program=new DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout);UUID action=UUID.randomUUID(),grant=UUID.randomUUID();
        try(var request=program.request(DeploymentAgentPublicationContractDiagnosticFollowup.binding(eligible),action)) {
            var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(eligible.original().plan().server()),eligible.original().plan().sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationContractDiagnosticFollowupProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationContractDiagnosticFollowup.open(task,log,eligible,request,intent);
            durable.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Immutable complete native contract review"),"4".repeat(64));durable.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(grant));durable.dispatch();
            String safe=contractFollowupSafeReceipt(program,request,!allPass);var actual=localReceipt(DeploymentAgentPublicationContractDiagnosticFollowupProgram.FRAME+safe+"\n");durable.result(actual,program.parse(request,actual));
        }
        return Map.of("action",action,"grant",grant);
    }
    private static String correctionSafeReceipt(DeploymentAgentPublicationCorrectionProgram program,
        DeploymentAgentPublicationCorrectionProgram.Request request,boolean unknown)throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var safe=json.createObjectNode().put("format",DeploymentAgentPublicationCorrectionProgram.FORMAT).put("sourceVersion",program.sourceVersion()).put("action",request.action().toString());safe.set("binding",json.valueToTree(request.binding()));
        safe.put("status",unknown?"UNKNOWN":"ROLLED_BACK").put("check",unknown?"INTERNAL":"COMPLETE").put("mutationStarted",true);
        for(String key:List.of("currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained"))if(unknown)safe.putNull(key);else safe.put(key,true);
        return program.validateReceipt(request,safe.toString()).canonicalJson();
    }

    private static String contractFollowupSafeReceipt(DeploymentAgentPublicationContractDiagnosticFollowupProgram program,
        DeploymentAgentPublicationContractDiagnosticFollowupProgram.Request request,boolean refused)throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var safe=json.createObjectNode().put("format",DeploymentAgentPublicationContractDiagnosticFollowupProgram.FORMAT).put("sourceVersion",program.sourceVersion()).put("action",request.action().toString());safe.set("binding",json.valueToTree(request.binding()));
        safe.put("status",refused?"REFUSED":"OBSERVED").put("phase",refused?"FINGERPRINTS":"COMPLETE").put("reason",refused?"REJECTED":"MATCH");if(refused)safe.put("exitCode",64);else safe.put("exitCode",0);
        var checks=safe.putArray("checks");for(String id:List.of("FULL_PARAMETER_VALIDATION","MANAGED_DATA_LINKS","UNIT_RENDER_MATCH")){var row=checks.addObject().put("id",id).put("status",refused?"UNKNOWN":"PASS").put("reason",refused?"NOT_CHECKED":"MATCH");if(refused)row.putNull("exitCode");else row.put("exitCode",0);}
        return program.validateReceipt(request,safe.toString()).canonicalJson();
    }

    private static Map<String,UUID> appendUnknownContinuationFixture(TaskContext task,DeploymentAgentActionLog log,
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout)throws Exception {
        var events=log.events(task);var eligible=DeploymentAgentPublicationContinuation.eligibility(DeploymentAgentPublicationCompensation.read(events,task.id()),events).orElseThrow();
        var program=new DeploymentAgentPublicationContinuationProgram(layout);UUID action=UUID.randomUUID(),grant=UUID.randomUUID();
        try(var request=program.request(DeploymentAgentPublicationContinuation.binding(eligible),action)) {
            var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(eligible.original().plan().server()),eligible.original().plan().sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationContinuationProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationContinuation.open(task,log,eligible,request,intent);
            durable.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Original local unknown continuation"),"4".repeat(64));durable.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(grant));durable.dispatch();
            var safe=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(continuationSafeReceipt(program,request,false));
            safe.put("status","UNKNOWN").put("check","INTERNAL").put("mutationStarted",true);
            for(String key:List.of("currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained"))safe.putNull(key);
            String canonical=program.validateReceipt(request,safe.toString()).canonicalJson();var actual=localReceipt("PUBLICATION_CONTINUATION="+canonical+"\n");durable.result(actual,program.parse(request,actual));
        }
        return Map.of("action",action,"grant",grant);
    }
    private static String contractDiagnosticSafeReceipt(DeploymentAgentPublicationContractDiagnosticProgram program,
        DeploymentAgentPublicationContractDiagnosticProgram.Request request,boolean refused)throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var safe=json.createObjectNode().put("format",DeploymentAgentPublicationContractDiagnosticProgram.FORMAT)
            .put("sourceVersion",program.sourceVersion()).put("action",request.action().toString());safe.set("binding",json.valueToTree(request.binding()));
        safe.put("status",refused?"REFUSED":"OBSERVED").put("phase",refused?"FINGERPRINTS":"COMPLETE").put("reason",refused?"REJECTED":"MATCH");
        if(refused)safe.put("exitCode",64);else safe.put("exitCode",0);
        var checks=safe.putArray("checks");for(String id:List.of("FULL_PARAMETER_VALIDATION","MANAGED_DATA_LINKS","UNIT_RENDER_MATCH")) {
            var row=checks.addObject().put("id",id).put("status",refused?"UNKNOWN":"PASS").put("reason",refused?"NOT_CHECKED":"MATCH");if(refused)row.putNull("exitCode");else row.put("exitCode",0);
        }
        return program.validateReceipt(request,safe.toString()).canonicalJson();
    }

    private static String continuationSafeReceipt(DeploymentAgentPublicationContinuationProgram program,DeploymentAgentPublicationContinuationProgram.Request request,boolean refused)throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var n=json.createObjectNode().put("format",DeploymentAgentPublicationContinuationProgram.FORMAT).put("sourceVersion",program.sourceVersion()).put("action",request.action().toString());n.set("binding",json.valueToTree(request.binding()));
        n.put("status",refused?"REFUSED":"ROLLED_BACK").put("check",refused?"FINGERPRINTS":"COMPLETE").put("mutationStarted",!refused);
        for(String key:List.of("currentAbsent","unitAbsent","releaseAbsent","maintenanceAbsent","candidateRetained"))if(refused)n.putNull(key);else n.put(key,true);
        return program.validateReceipt(request,n.toString()).canonicalJson();
    }

    private static DeploymentAgentPublicationRecoveryPreflightProgram.Binding preflightBinding(DeploymentAgentPublicationRecoveryPreflight.Eligibility eligible)throws Exception {
        return new DeploymentAgentPublicationRecoveryPreflightProgram.Binding(diagnosticBinding(eligible.original()),eligible.prior());
    }

    /** Complete old v1 history is retained under the immutable old codec; no remote observation is claimed. */
    private static Map<String,UUID> appendV1DiagnosticFixture(TaskContext task,DeploymentAgentActionLog log,DeploymentAgentPublicationCompensation.DiagnosticEligibility eligible,
        gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout layout,String mode)throws Exception {
        var program=new DeploymentAgentPublicationRecoveryDiagnosticProgram(layout);UUID action=UUID.randomUUID(),grant=UUID.randomUUID();
        try(var request=program.request(diagnosticBinding(eligible),action)) {
            var intent=gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(eligible.plan().server()),eligible.plan().sha256(),request.command()).withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION,request.resourceTargets()));
            var diagnostic=DeploymentAgentPublicationRecoveryDiagnostic.open(task,log,eligible,request,intent);
            diagnostic.reviewed(new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE",false,"Original local v1 diagnostic"),"6".repeat(64));diagnostic.granted(new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(grant));diagnostic.dispatch();
            if(mode.equals("INCOMPLETE")){diagnostic.retainInterrupted();return Map.of("action",action,"grant",grant);}
            String safe=diagnosticSafeReceipt(program,request);
            if(mode.equals("ENV_FAIL")) {
                var node=(com.fasterxml.jackson.databind.node.ObjectNode)new com.fasterxml.jackson.databind.ObjectMapper().readTree(safe);node.put("stable",false);
                for(var check:node.path("checks")) {
                    var entry=(com.fasterxml.jackson.databind.node.ObjectNode)check;
                    if(entry.path("id").asText().equals("ENVIRONMENT"))entry.put("status","FAIL").put("reason","MISMATCH").put("flag",false);
                    if(entry.path("id").asText().equals("STABILITY"))entry.put("status","UNKNOWN").put("reason","DRIFT");
                }
                safe=program.validateReceipt(request,node.toString()).canonicalJson();
            }
            var actual=new OperationResult<>(TaskResult.SUCCEEDED,Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0),"PUBLICATION_RECOVERY_DIAGNOSTIC="+safe+"\n","",false)),Optional.<Problem>empty());
            diagnostic.result(actual,program.parse(request,actual));
        }
        return Map.of("action",action,"grant",grant);
    }

    /** Synthetic fixed receipt only; no assertion that the real guard, stability or provider was exercised. */
    private static String preflightSafeReceipt(DeploymentAgentPublicationRecoveryPreflightProgram program,DeploymentAgentPublicationRecoveryPreflightProgram.Request request)throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var node=json.createObjectNode().put("format",DeploymentAgentPublicationRecoveryPreflightProgram.FORMAT).put("sourceVersion",program.sourceVersion()).put("action",request.action().toString());
        node.set("binding",json.valueToTree(request.binding()));node.put("stage","GUARD_CHECKS");
        node.putObject("environment").put("bashEnv","DEV_NULL").put("env","DEV_NULL").put("ldPreload","ABSENT").put("ldLibraryPath","ABSENT").put("exportedFunctions","ABSENT").put("snapshot","COMPLETE").put("sanitizedSentinels",true).put("pythonStarted",true);
        var checks=node.putArray("checks");
        for(String id:DeploymentAgentPublicationRecoveryPreflightProgram.CHECK_IDS) {
            boolean pass=Set.of("ENVIRONMENT","ROOT_IDENTITY","STABILITY").contains(id);var check=checks.addObject().put("id",id).put("status",pass?"PASS":"UNKNOWN")
                .put("reason",pass?"MATCH":Set.of("FULL_PARAMETER_VALIDATION","UNIT_RENDER_MATCH","MANAGED_DATA_LINKS").contains(id)?"NOT_IMPLEMENTED":"NOT_CHECKED").putNull("number");
            if(pass)check.put("flag",true);else check.putNull("flag");check.putNull("sha256");
        }
        node.put("stable",true);return program.validateReceipt(request,json.writeValueAsString(node)).canonicalJson();
    }

    private static DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding diagnosticBinding(DeploymentAgentPublicationCompensation.DiagnosticEligibility eligible)throws Exception {
        var p=eligible.plan();var a=eligible.pending();var c=eligible.component();
        return new DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(p.server(),p.task(),p.execution(),a.actionId(),a.event(),DeploymentAgentInputs.hash(a.frame()),p.sha256(),c.unit(),c.owner(),c.candidate(),c.release(),DeploymentJsonDocument.string(DeploymentJsonDocument.parse(p.header()),"layout"));
    }

    /** Local safe receipt fixture only, not a remote observation or proof that diagnostic checks passed. */
    private static String diagnosticSafeReceipt(DeploymentAgentPublicationRecoveryDiagnosticProgram program,DeploymentAgentPublicationRecoveryDiagnosticProgram.Request request)throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var node=json.createObjectNode().put("format",DeploymentAgentPublicationRecoveryDiagnosticProgram.FORMAT).put("sourceVersion",program.sourceVersion()).put("action",request.action().toString());
        node.set("binding",json.valueToTree(request.binding()));var checks=node.putArray("checks");
        for(String id:DeploymentAgentPublicationRecoveryDiagnosticProgram.CHECK_IDS) {
            boolean stable=id.equals("STABILITY");var check=checks.addObject().put("id",id).put("status",stable?"PASS":"UNKNOWN")
                .put("reason",stable?"MATCH":Set.of("FULL_PARAMETER_VALIDATION","UNIT_RENDER_MATCH","MANAGED_DATA_LINKS").contains(id)?"NOT_IMPLEMENTED":"NOT_CHECKED").putNull("number");
            if(stable)check.put("flag",true);else check.putNull("flag");check.putNull("sha256");
        }
        node.put("stable",true);String safe=json.writeValueAsString(node);return program.validateReceipt(request,safe).canonicalJson();
    }

    private static DeploymentAgentPublicationCompensation.Intent compensationIntent(ModContext context, TaskContext task,
        DeploymentAgentPublicationCompensation.Plan plan, UUID grant) throws Exception {
        var log = new DeploymentAgentActionLog(context.db()); var state = DeploymentAgentPublicationCompensation.read(log.events(task), task.id());
        var step = DeploymentAgentPublicationCompensation.next(state).orElseThrow(); var component = step.component();
        String script = "local-fixture-only:" + component.id() + ":" + step.verb();
        boolean cleanup = step.verb() == DeploymentAgentPublicationCompensation.Verb.CLEANUP_CANDIDATE;
        if (cleanup) {
            var store = new DeploymentExecutionStore(context.db());
            var unit = store.publicationTemplate(store.findForTask(task).orElseThrow()).orElseThrow().graph().components().stream().filter(p -> p.componentId().equals(component.id())).findFirst().orElseThrow().unit();
            String command = java.util.stream.Stream.of(unit.layout().helperPath(), "candidate-cleanup-task", unit.id(), component.candidate(), task.id().toString())
                .map(gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
            script = gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle.verifiedCommand(command, unit.layout());
        }
        try (var request = new gold.debug.windowstolinux.appapi.linux.command.CommandRequest(script, new byte[0], java.time.Duration.ofSeconds(cleanup ? 90 : 30), 4096)) {
            var action = gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId(plan.server()), plan.sha256(), request)
                .withSemantics(new gold.debug.windowstolinux.appapi.task.action.ActionSemantics(step.verb() == DeploymentAgentPublicationCompensation.Verb.GUARDED_ROLLBACK
                    ? DeploymentAgentPublicationCompensation.ROLLBACK_OPERATION : DeploymentAgentPublicationCompensation.CLEANUP_OPERATION,
                    Set.of(new gold.debug.windowstolinux.appapi.task.action.ActionTarget(plan.server(), "deployment-release", component.releaseRoot()))));
            return DeploymentAgentPublicationCompensation.intend(context, task, log, plan, UUID.randomUUID(), action, request,
                new gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision("APPROVE", false, "Local receipt fixture"), "5".repeat(64),
                new gold.debug.windowstolinux.appapi.task.action.ApprovalGrant(grant));
        }
    }

    private static void completeCompensation(ModContext context, TaskContext task, DeploymentAgentPublicationCompensation.Plan plan, boolean unknownFirst) throws Exception {
        var log = new DeploymentAgentActionLog(context.db());
        int count = plan.components().size() + (int) plan.components().stream().filter(c -> c.initial().current().isPresent()).count();
        for (int i = DeploymentAgentPublicationCompensation.read(log.events(task), task.id()).completed(); i < count; i++) {
            var intent = compensationIntent(context, task, plan, UUID.randomUUID());
            if (i == 0 && unknownFirst) {
                DeploymentAgentPublicationCompensation.acknowledge(task, log, intent, new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(DeploymentAgentPublicationCompensation.failure())));
                compensationObservation(context, task, false);
                DeploymentAgentPublicationCompensation.observedOnly(task, log, intent);
            } else DeploymentAgentPublicationCompensation.acknowledge(task, log, intent, new OperationResult<>(TaskResult.SUCCEEDED,
                Optional.of(new gold.debug.windowstolinux.appapi.linux.command.CommandResult(Optional.of(0), "LOCAL_RECEIPT_FIXTURE=1\n", "", false)), Optional.empty()));
        }
        compensationObservation(context, task, false);
        DeploymentAgentPublicationCompensation.verify(context, task, log);
    }

    private static OperationResult<Void> finishCompensation(ModContext context, TaskContext task) throws Exception {
        var store = new DeploymentExecutionStore(context.db()); var log = new DeploymentAgentActionLog(context.db());
        var id = store.findForTask(task).orElseThrow().identity().executionId();
        var commit = new DeploymentOriginalPublishCommit(context);
        assertEquals(TaskResult.FAILED, commit.completeUncommittedVerified(task, id, TaskResult.FAILED, DeploymentAgentPublicationCompensation.failure()).outcome());
        assertEquals(TaskResult.FAILED, commit.finishKnown(task, store.findForTask(task).orElseThrow()).outcome());
        log.event(task, "AI_PUBLICATION_CLEANED", "application", "ABSENT");
        log.event(task, "AI_RECOVERY_SETTLED", task.id().toString(), "FAILED:COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED");
        log.state(task, "FAILED");
        return new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(DeploymentAgentPublicationCompensation.failure()));
    }

    /** Local codec/transaction fixture only: no APP, host grant, SSH or remote result is claimed exercised. */
    private static void compensationObservation(ModContext context, TaskContext task, boolean initial) throws Exception {
        compensationObservation(context, task, initial ? 1 : 0);
    }

    private static void compensationObservation(ModContext context, TaskContext task, int mode) throws Exception {
        boolean initial = mode != 0;
        var log = new DeploymentAgentActionLog(context.db()); var store = new DeploymentExecutionStore(context.db());
        var formal = store.findForTask(task).orElseThrow(); var template = store.publicationTemplate(formal).orElseThrow();
        var prefix = log.events(task); String invocation = UUID.randomUUID().toString();
        var begin = DeploymentJsonDocument.object().put("formatVersion", 1).put("kind", "BEGIN").put("invocation", invocation)
            .put("task", task.id().toString()).put("execution", formal.identity().executionId().toString()).put("server", "server").put("application", "application")
            .put("reviewRevision", "b".repeat(64)).put("templateSha256", DeploymentAgentInputs.hash(formal.requestJson()))
            .put("proposalSha256", DeploymentAgentInputs.hash(new DeploymentPublishResult(template.graph(), template.units()).toString()))
            .put("prefixEndEvent", prefix.getLast().id()).put("prefixSha256", DeploymentAgentPublicationObservation.prefixSha256(prefix));
        var names = begin.putArray("components"); template.graph().components().forEach(p -> names.add(p.componentId()));
        String beginText = DeploymentAgentPublicationObservation.encodeFrame(begin);
        log.event(task, DeploymentAgentPublicationObservation.BEGIN, invocation, beginText); long beginId = log.events(task).getLast().id();
        var ids = new ArrayList<Long>(); int index = 0;
        for (var part : template.graph().components()) {
            String component = part.componentId(); var commit = template.units().stream().filter(u -> u.unit().equals(part.unit())).findFirst().orElseThrow();
            String candidate = prefix.stream().filter(e -> e.type().equals("CANDIDATE") && e.action().equals(component)).findFirst().orElseThrow().detail();
            boolean current = mode == 2 || mode == 1 && index++ == 0;
            var status = new DeploymentOriginalPublishStatus(current ? Optional.of(commit.release().sha256()) : Optional.empty(), current ? "deployment" : "none",
                current ? part.reviewedRuntime().workload().mode().name() : "none", false, Optional.empty(), "none", Optional.empty(), current, initial, false, current, Optional.empty(), current ? 1 : 0);
            String exact = DeploymentAgentInputs.hash(invocation + component + ":exact"), review = DeploymentAgentInputs.hash(invocation + component + ":review");
            String evidence = "6".repeat(64), request = "7".repeat(64), result = "8".repeat(64), statusSha = DeploymentAgentPublicationObservation.statusSha256(status);
            log.event(task, "HEALTH_STATUS_QUERY_REQUEST", review, "exactAction=" + exact + ";evidence=" + evidence + ";requestSha256=" + request); long requestId = log.events(task).getLast().id();
            log.event(task, "HEALTH_STATUS_QUERY_APPROVED", review, "exactAction=" + exact + ";evidence=" + evidence + ";reasonSha256=" + "9".repeat(64));
            log.event(task, "HEALTH_STATUS_QUERY_RESULT", exact, "resultSha256=" + result + ";review=" + review + ";evidence=" + evidence + ";statusSha256=" + statusSha); long resultId = log.events(task).getLast().id();
            var row = DeploymentJsonDocument.object().put("formatVersion", 1).put("kind", "COMPONENT").put("invocation", invocation).put("beginEvent", beginId)
                .put("beginSha256", DeploymentAgentInputs.hash(beginText)).put("component", component).put("unit", part.unit().id()).put("expectedOwnerSha256", part.unit().ownershipManifestSha256())
                .put("candidate", candidate).put("releaseSha256", commit.release().sha256()).put("layoutSha256", part.unit().layout().digest()).put("missingReason", "NONE");
            var snapshots = row.putArray("originalSnapshotEvents"); prefix.stream().filter(e -> e.action().equals(component) && Set.of("SNAPSHOT_EXECUTING", "SNAPSHOT_RESULT").contains(e.type()))
                .forEach(e -> snapshots.addObject().put("id", e.id()).put("type", e.type()).put("detailSha256", DeploymentAgentInputs.hash(e.detail())));
            var receipt = row.putObject("receipt").put("requestEvent", requestId).put("resultEvent", resultId).put("exactActionSha256", exact).put("requestSha256", request)
                .put("reviewSha256", review).put("evidenceSha256", evidence).put("resultSha256", result).put("statusSha256", statusSha);
            receipt.putObject("status").put("current", status.current().orElse("none")).put("currentKind", status.currentKind()).put("currentMode", status.currentMode()).put("snapshot", false)
                .put("previousDigest", "none").put("previousKind", "none").put("previousRunning", "none").put("maintenance", status.maintenance()).put("candidate", status.candidate())
                .put("buildActive", false).put("unitPresent", status.unitPresent()).put("containerPresent", "none").put("retainedCount", status.retainedCount());
            log.event(task, DeploymentAgentPublicationObservation.COMPONENT, invocation, DeploymentAgentPublicationObservation.encodeFrame(row)); ids.add(log.events(task).getLast().id());
        }
        var end = DeploymentJsonDocument.object().put("formatVersion", 1).put("kind", "END").put("invocation", invocation).put("beginEvent", beginId).put("beginSha256", DeploymentAgentInputs.hash(beginText))
            .put("observationComplete", true).put("outcome", "UNKNOWN").put("authority", "NONE"); var endIds = end.putArray("componentEvents"); ids.forEach(endIds::add);
        log.event(task, DeploymentAgentPublicationObservation.END, invocation, DeploymentAgentPublicationObservation.encodeFrame(end));
    }

    @Test
    void originalRecoveryProducerArchivesSuccessKeepsSharedBusinessAndNeverDispatchesAfterReopen() throws Exception {
        enable();
        var originals = new ArrayList<UUID>();
        var creating = new AtomicReference<UUID>();
        var release = new AtomicReference<DeploymentReleaseIdentity>();
        var foreign = new AtomicReference<UUID>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            for (int attempt = 0; attempt < 2; attempt++)
                run(runtime, task -> {
                    var context = runtime.modContexts().get(OWNER).context();
                    if (foreign.get() == null) {
                        var held = context.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest("foreign-fixture", "Foreign retained history", List.of(), false), other -> {
                            var log = new DeploymentAgentActionLog(context.db());
                            log.create(other, new DeploymentAgentActionLog.Identity("foreign", "c".repeat(64), "d".repeat(64), "MANUAL_REVIEW", true));
                            log.state(other, "SUCCEEDED");
                            return success();
                        });
                        foreign.set(held.id());
                        assertEquals(TaskResult.SUCCEEDED, held.completion().toCompletableFuture().get(10, TimeUnit.SECONDS).outcome());
                    }
                    var id = originalSuccess(runtime, task);
                    var log = new DeploymentAgentActionLog(context.db());
                    assertTrue(log.events(task).stream().anyMatch(e -> e.type().equals("STATE") && e.detail().equals("UNKNOWN")));
                    assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                    assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                    assertTrue(log.events(task).stream().noneMatch(e -> e.type().equals("DELIVERY_RESULT") || e.type().equals(DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED)));
                    var record = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
                    var business = new DeploymentReleaseStore(context.db()).list(task, "application").getFirst();
                    if (creating.get() == null) {
                        creating.set(id);
                        release.set(business.identity());
                    } else {
                        assertEquals(creating.get(), business.executionId());
                        assertEquals(release.get(), business.identity());
                        assertTrue(!record.identity().executionId().equals(business.executionId()));
                    }
                    var archive = new DeploymentAgentHistoryArchive(context);
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertTrue(log.find(task).isEmpty());
                    assertTrue(new DeploymentExecutionStore(context.db()).findForTask(task).isEmpty());
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_event WHERE task_id='" + task.id() + "'"));
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task WHERE task_id='" + foreign.get() + "'"));
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(task.id(), task).isPresent());
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                    originals.add(task.id());
                    return success();
                });
            assertEquals(3, scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE state='ACTIVE'"));
            valid(runtime);
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var strict = (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class}, (proxy, method, args) -> {
                    if (Set.of("linux", "ai", "file").contains(method.getName()))
                        throw new AssertionError("Receipt reuse cannot dispatch " + method.getName());
                    return invoke(method, context, args);
                });
                for (UUID original : originals) {
                    assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(strict).archiveRecoveredHistory(task, original).outcome());
                    try (var connection = context.db().openConnection(task)) {
                        var receipt = DeploymentOperationReceipts.read(connection, original).orElseThrow();
                        assertEquals(release.get(), receipt.release().orElseThrow());
                        DeploymentOperationReceipts.validateBusiness(connection, receipt);
                    }
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(original, task).isPresent());
                }
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task WHERE task_id='" + foreign.get() + "'"));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void originalDeliveryLostCommitAcknowledgementReusesTheCommittedReceiptWithoutRepeatingRecovery() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                originalSuccess(runtime, task);
                assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                var uncertain = assertThrows(ApiException.class, () -> new DeploymentAgentHistoryArchive(withDatabase(context, fault(context.db(), "lost_ack"))).settle(task, success()));
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", uncertain.problem().eventCode());
                assertTrue(new DeploymentAgentActionLog(context.db()).find(task).isEmpty());
                assertTrue(new DeploymentExecutionStore(context.db()).findForTask(task).isEmpty());
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"proof", "receipt", "events", "task", "execution", "before_commit"})
    void originalDeliveryProofAndAllCleanupWritesRollBackTogether(String point) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                originalSuccess(runtime, task);
                assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                var log = new DeploymentAgentActionLog(context.db());
                var events = log.events(task);
                var formal = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
                assertThrows(Exception.class, () -> new DeploymentAgentHistoryArchive(withDatabase(context, fault(context.db(), point))).settle(task, success()));
                assertEquals(events, log.events(task));
                assertEquals(formal, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"command", "prepare", "capability", "inspect", "multi_delivery", "later_same_action", "bad_proof", "unknown_state", "failed_state", "busy", "source", "configuration", "layout", "revision", "seal", "invalidated", "missing_component", "foreign_candidate", "review_chain"})
    void originalDeliveryRecoveryRejectsUnresolvedOrChangedEvidenceWithoutDeleting(String kind) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                originalSuccess(runtime, task);
                assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                var log = new DeploymentAgentActionLog(context.db());
                var original = log.events(task);
                var delivery = original.stream().filter(e -> e.type().equals("DELIVERY_EXECUTING")).findFirst().orElseThrow();
                gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint held = null;
                switch (kind) {
                    case "command", "prepare", "capability" ->
                        log.event(task, kind.toUpperCase(Locale.ROOT) + "_EXECUTING", "pending", "original");
                    case "inspect" -> log.event(task, "INSPECT_REQUESTED", "original", "pending");
                    case "multi_delivery", "later_same_action" ->
                        log.event(task, "DELIVERY_EXECUTING", kind.equals("later_same_action") ? delivery.action() : "f".repeat(64), "1");
                    case "bad_proof" ->
                        log.event(task, DeploymentAgentActionLog.DELIVERY_RECOVERY_SETTLED, delivery.action(), "{}");
                    case "unknown_state", "failed_state" ->
                        log.state(task, kind.equals("unknown_state") ? "UNKNOWN" : "FAILED");
                    case "busy" ->
                        held = context.tasks().beginCheckpoint(task, "fixture.held", "{\"formatVersion\":1}");
                    case "invalidated" -> log.event(task, "ARTIFACT_INVALIDATED", "web", "original");
                    case "review_chain" ->
                        log.event(task, "RECOVERY_REVIEW_REBOUND", "b".repeat(64), "broken predecessor");
                    default -> {
                        String type = switch (kind) {
                            case "source" -> "CURRENT_SOURCE";
                            case "configuration" -> "WORKSPACE_QUOTA";
                            case "layout" -> "REMOTE_LAYOUT";
                            case "revision" -> "DELIVERY_EXECUTING";
                            case "seal", "missing_component" -> "SEALED_ARTIFACT";
                            case "foreign_candidate" -> "CANDIDATE";
                            default -> throw new AssertionError(kind);
                        };
                        var selected = original.stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
                        String detail = kind.equals("revision") ? "3" : kind.equals("configuration")
                            ? selected.detail().replace("b".repeat(64), "e".repeat(64)) : kind.equals("layout")
                            ? selected.detail().replace("\"layoutSha256\":\"", "\"layoutSha256\":\"f") : kind.equals("foreign_candidate")
                            ? "foreign-" + "f".repeat(16) : "9".repeat(64);
                        try (var connection = context.db().openConnection(task); var update = connection.prepareStatement(
                            kind.equals("missing_component") ? "DELETE FROM deployment_agent_event WHERE event_id=? AND task_id=?"
                                : "UPDATE deployment_agent_event SET event_detail=? WHERE event_id=? AND task_id=?")) {
                            int index = 1;
                            if (!kind.equals("missing_component")) update.setString(index++, detail);
                            update.setLong(index++, selected.id());
                            update.setString(index, task.id().toString());
                            assertEquals(1, update.executeUpdate());
                        }
                    }
                }
                var modified = log.events(task);
                try {
                    if (held == null)
                        assertThrows(Exception.class, () -> new DeploymentAgentHistoryArchive(context).settle(task, success()), kind);
                    else
                        assertEquals(TaskResult.UNKNOWN, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                    assertEquals(modified, log.events(task));
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_execution"));
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                } finally {
                    if (held != null) held.close();
                    restoreEvents(context, task, original);
                    log.state(task, "SUCCEEDED");
                }
                assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "uncommitted", "unclean", "foreign_task", "template_release", "whole_release", "invalidated_before_delivery"})
    void originalDeliveryProofRequiresTheExactFinishedSuccessfulFormalTemplate(String kind) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                originalSuccess(runtime, task);
                assertEquals(TaskResult.SUCCEEDED, finishOriginal(context, task).outcome());
                var log = new DeploymentAgentActionLog(context.db());
                var store = new DeploymentExecutionStore(context.db());
                var formal = store.findForTask(task).orElseThrow();
                var template = store.publicationTemplate(formal).orElseThrow();
                var identity = kind.equals("foreign_task") ? new DeploymentExecutionIdentity(formal.identity().executionId(), UUID.randomUUID(), Optional.empty(), "application", new ServerId("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "a".repeat(64)) : formal.identity();
                var invalid = new DeploymentExecutionRecord(identity, kind.equals("unclean") || kind.equals("unknown") ? "EXECUTING" : formal.phase(),
                    Optional.of(kind.equals("unknown") ? TaskResult.UNKNOWN : TaskResult.SUCCEEDED), formal.requestJson(), formal.recoveryJson(), formal.resultJson(),
                    !kind.equals("uncommitted") && !kind.equals("unknown"), !kind.equals("unclean") && !kind.equals("unknown"), formal.createdAt(), formal.updatedAt());
                if (kind.equals("template_release")) {
                    var changed = template.units().stream().map(u -> new DeploymentUnitCommit(u.unit(), u.runtime(), new DeploymentCurrentRelease(u.unit().id(), "f".repeat(64), u.release().publishedAt()), u.configuration(), u.secrets())).toList();
                    template = new DeploymentPublishProposal(template.graph(), changed);
                }
                if (kind.equals("whole_release")) {
                    var graph = template.graph();
                    template = new DeploymentPublishProposal(new DeploymentApplicationGraph(graph.applicationId(), graph.healthComponentId(),
                        new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Process(9, 1), graph.components()), template.units());
                    var request = (com.fasterxml.jackson.databind.node.ObjectNode) DeploymentJsonDocument.parse(formal.requestJson(), 144 * 1_048_576);
                    request.set("publicationTemplate", DeploymentJsonDocument.parse(gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishTemplateCodec.encodeTemplate(template),
                        gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishTemplateCodec.LIMIT));
                    invalid = new DeploymentExecutionRecord(identity, formal.phase(), formal.outcome(), request.toString(), formal.recoveryJson(), formal.resultJson(),
                        formal.businessCommitted(), formal.cleanupComplete(), formal.createdAt(), formal.updatedAt());
                }
                var events = log.events(task);
                if (kind.equals("invalidated_before_delivery")) {
                    var changed = new ArrayList<DeploymentAgentActionLog.Event>();
                    boolean inserted = false;
                    for (var event : events) {
                        changed.add(new DeploymentAgentActionLog.Event(event.id() * 10, event.type(), event.action(), event.detail()));
                        if (!inserted && event.type().equals("SEALED_ARTIFACT")) {
                            changed.add(new DeploymentAgentActionLog.Event(event.id() * 10 + 1, "ARTIFACT_INVALIDATED", event.action(), "command"));
                            inserted = true;
                        }
                    }
                    events = changed;
                }
                var selected = template;
                var selectedFormal = invalid;
                var selectedEvents = events;
                assertThrows(Exception.class, () -> DeploymentAgentActionLog.originalDeliveryProof(task.id(), log.find(task).orElseThrow(), selectedFormal, selected, selectedEvents));
                assertEquals(formal, store.findForTask(task).orElseThrow());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                new DeploymentAgentHistoryArchive(context).settle(task, success());
                return success();
            });
        }
    }

    private UUID originalSuccess(PlatformRuntime runtime, TaskContext task) throws Exception {
        return originalPublication(runtime, task, true);
    }

    private UUID originalPublication(PlatformRuntime runtime, TaskContext task, boolean successful) throws Exception {
        return originalPublication(runtime,task,successful,"a".repeat(64),"a".repeat(64));
    }

    private UUID originalPublication(PlatformRuntime runtime, TaskContext task, boolean successful,String target,String profile) throws Exception {
        var context = runtime.modContexts().get(OWNER).context();
        var log = new DeploymentAgentActionLog(context.db());
        log.create(task, successful ? agentIdentity() : new DeploymentAgentActionLog.Identity("server", target, "b".repeat(64), "MANUAL_REVIEW", false), Optional.of("a".repeat(64)));
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentLayouts.record(log, task, new ServerId("server"), layout);
        String archive = "c".repeat(64), source = "d".repeat(64), current = "e".repeat(64), artifact = "f".repeat(64), plan = "1".repeat(64);
        log.event(task, "SOURCE", archive, source);
        log.event(task, "TARGET_PROFILE", "", profile);
        log.event(task, "PLAN_APPLICATION", "", "application");
        log.event(task, "PLAN", "", plan);
        var parts = graph("application").components().stream().map(p -> new DeploymentApplicationGraph.Part(p.componentId(),
            DeploymentUnitIdentity.forManaged("application-" + p.componentId(), new ServerId("server"), p.unit().ownershipManifestSha256()), p.runtime(), p.dependencies(), p.reviewedRuntime(), p.dataPaths(),
            new DeploymentResourceBindings(p.resources().fileBindings(), Optional.of(List.of())))).toList();
        var graph = new DeploymentApplicationGraph("application", "web", graph("application").healthCheck(), parts);
        var commits = publications(graph, 1, List.of()).stream().map(u -> {
            var p = graph.components().stream().filter(v -> v.unit().equals(u.unit())).findFirst().orElseThrow();
            String digest = DeploymentAgentInputs.hash("agent-release:" + artifact + ":" + current + ":" + u.configuration().sha256() + ":" + p.reviewedRuntime() + ":" + p.resources());
            return new DeploymentUnitCommit(u.unit(), u.runtime(), new DeploymentCurrentRelease(u.unit().id(), digest, u.release().publishedAt()), u.configuration(), u.secrets());
        }).toList();
        for (var part : parts) {
            String id = part.componentId();
            var workspace = new DeploymentRemoteWorkspace(part.unit().id(), archive, layout);
            String binding = gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.binding(task.id(), id, source, plan, 1073741824L);
            var quota = DeploymentJsonDocument.object().put("task", task.id().toString()).put("server", "server").put("source", source).put("configuration", "b".repeat(64))
                .put("layout", layout.digest()).put("plan", plan).put("workspaceBytes", 1073741824L).put("reserveBytes", 1073741824L).put("mode", "AUTO");
            log.event(task, "WORKSPACE_QUOTA", id, quota.toString());
            log.event(task, "PREPARE_EXECUTING", binding, id);
            quota.remove("mode");
            quota.put("prepareAction", binding).put("candidate", workspace.candidateId());
            log.event(task, "CANDIDATE_QUOTA", id, quota.toString());
            log.event(task, "PREPARE_RESULT", binding, current);
            log.event(task, "CANDIDATE", id, workspace.candidateId());
            log.event(task, "CURRENT_SOURCE", id, current);
            String seal = DeploymentAgentInputs.hash(id + ":" + current + ":seal");
            log.event(task, "SEAL_EXECUTING", seal, current);
            log.event(task, "SEAL_RESULT", seal, artifact);
            log.event(task, "SEALED_ARTIFACT", id, artifact);
        }
        log.event(task, "DELIVERY_EXECUTING", "2".repeat(64), "1");
        for (var part : parts) log.event(task, "SEALED_TOOLS", part.componentId(), "0".repeat(64) + ":false");
        var proposal = new DeploymentPublishResult(graph, commits);
        var id = UUID.randomUUID();
        var store = new DeploymentExecutionStore(context.db());
        var identity = new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerId("server"), DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, target);
        var intents = DeploymentOriginalPublishCommit.references(new ServerId("server"), proposal, Map.of());
        store.begin(task, identity, "deployment-agent", intents, Optional.empty(), OBSERVED);
        var refs = new ArrayList<UUID>();
        for (var intent : intents) {
            var ref = context.servers().reserve(new ServerId("server"), intent.businessKey(), task);
            refs.add(ref.id());
            store.recordReference(task, id, intent, ref.id(), OBSERVED.plusSeconds(1));
        }
        store.startEffects(task, id, OBSERVED.plusSeconds(2));
        store.recordPublicationTemplate(task, id, new DeploymentPublishProposal(graph, commits), OBSERVED.plusSeconds(3));
        log.event(task, "AI_PUBLICATION", "application", "MODEL_GENERATED_COMMANDS");
        if (!successful) {
            for (var part : parts) {
                log.event(task, "SNAPSHOT_EXECUTING", part.componentId(), part.unit().ownershipManifestSha256());
                log.event(task, "SNAPSHOT_RESULT", part.componentId(), "none:0:none:none");
                log.event(task, "PUBLISH_EXECUTING", part.componentId(), commits.stream().filter(u -> u.unit().equals(part.unit())).findFirst().orElseThrow().release().sha256());
            }
            log.state(task, "UNKNOWN");
            compensationObservation(context, task, true);
            return id;
        }
        log.event(task, "STAGE_EXECUTING", "web", "original");
        log.event(task, "STAGE_RESULT", "web", "staged");
        log.event(task, "AI_PUBLICATION_VERIFIED", "application", "HEALTHY");
        log.event(task, "AI_PUBLICATION_FINISHED", "application", "MODEL_ACCEPTED_VERIFIED_RESULT");
        store.commitApplication(task, id, graph, commits, OBSERVED.plusSeconds(4));
        for (UUID ref : refs) context.servers().resolve(ref, ResourceRefResult.REFERENCED, task);
        log.event(task, "AI_PUBLICATION_CLEANED", "application", "ABSENT");
        log.state(task, "UNKNOWN");
        return id;
    }

    @SuppressWarnings("unchecked")
    private OperationResult<Void> finishOriginal(ModContext context, TaskContext task) throws Exception {
        var recovery = new DeploymentAgentRecovery(context, new DeploymentAgentActionLog(context.db()));
        var method = DeploymentAgentRecovery.class.getDeclaredMethod("finishRecovered", TaskContext.class, DeploymentExecutionRecord.class);
        method.setAccessible(true);
        try {
            var result = (OperationResult<Void>) method.invoke(recovery, task, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow());
            if (result.outcome() == TaskResult.UNKNOWN) {
                var store = new DeploymentExecutionStore(context.db());
                var formal = store.findForTask(task).orElseThrow();
                var log = new DeploymentAgentActionLog(context.db());
                try {
                    DeploymentAgentActionLog.originalDeliveryProof(task.id(), log.find(task).orElseThrow(), formal, store.publicationTemplate(formal).orElseThrow(), log.events(task));
                } catch (Exception failure) {
                    throw new SQLException("Actual recovery finish remained " + result.problem().map(Problem::eventCode).orElse("UNKNOWN"), failure);
                }
                throw new SQLException("Actual recovery finish remained " + result.problem().map(Problem::eventCode).orElse("UNKNOWN"));
            }
            return result;
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception exception) throw exception;
            if (failed.getCause() instanceof Error error) throw error;
            throw failed;
        }
    }

    private void restoreEvents(ModContext context, TaskContext task, List<DeploymentAgentActionLog.Event> original) throws Exception {
        try (var connection = context.db().openConnection(task)) {
            try (var delete = connection.prepareStatement("DELETE FROM deployment_agent_event WHERE task_id=?")) {
                delete.setString(1, task.id().toString());
                delete.executeUpdate();
            }
            for (var event : original)
                try (var insert = connection.prepareStatement("INSERT INTO deployment_agent_event(event_id,task_id,action_id,event_type,event_detail,created_at) VALUES(?,?,?,?,?,?)")) {
                    insert.setLong(1, event.id());
                    insert.setString(2, task.id().toString());
                    insert.setString(3, event.action());
                    insert.setString(4, event.type());
                    insert.setString(5, event.detail());
                    insert.setLong(6, System.currentTimeMillis());
                    assertEquals(1, insert.executeUpdate());
                }
        }
    }

    @Test
    void platformRecoverySuccessKeepsOriginalCompensatedFailureAndHistoricalCleanupIsIdempotent() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var original = context.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest(
                gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.TASK_TYPE, "Recovered history", List.of("server"), true), task -> {
                var journal = new DeploymentAgentActionLog(context.db());
                journal.create(task, agentIdentity());
                var id = UUID.randomUUID();
                var identity = new DeploymentExecutionIdentity(id, task.id(), Optional.empty(), "application", new ServerId("server"),
                    DeploymentExecutionIdentity.DeploymentExecutionMode.AGENT, "a".repeat(64));
                var store = new DeploymentExecutionStore(context.db());
                store.begin(task, identity, "deployment-agent", List.of(intent()), Optional.empty(), OBSERVED);
                var ref = context.servers().reserve(new ServerId("server"), intent().businessKey(), task);
                store.recordReference(task, id, intent(), ref.id(), OBSERVED.plusSeconds(1));
                store.startEffects(task, id, OBSERVED.plusSeconds(2));
                var reason = new Problem(ProblemCode.IO_FAILURE, "ORIGINAL_DEPLOYMENT_FAILED", "Verified compensation", Map.of(), Optional.empty());
                store.recordUncommittedOutcome(task, id, TaskResult.FAILED, reason, OBSERVED.plusSeconds(3));
                context.servers().resolve(ref.id(), ResourceRefResult.NOT_REFERENCED, task);
                store.finishCleanup(task, id, OBSERVED.plusSeconds(4));
                journal.event(task, "AI_PUBLICATION_CLEANED", "application", "ABSENT");
                journal.state(task, "FAILED");
                return success(); // Platform certifies recovery closure rather than App publication.
            });
            assertEquals(TaskResult.SUCCEEDED, original.completion().toCompletableFuture().get(15, TimeUnit.SECONDS).outcome());
            run(runtime, current -> {
                var archive = new DeploymentAgentHistoryArchive(context);
                var result = archive.archiveRecoveredHistory(current, original.id());
                assertEquals(TaskResult.FAILED, result.outcome());
                assertEquals("ORIGINAL_DEPLOYMENT_FAILED", result.problem().orElseThrow().eventCode());
                assertEquals(result, archive.archiveRecoveredHistory(current, original.id()));
                assertEquals(0, count(runtime, current, "SELECT COUNT(*) FROM managed_application"));
                assertEquals(0, count(runtime, current, "SELECT COUNT(*) FROM deployment_execution"));
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void noExecutionRequiresActualRecoverySettlementProof() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentAgentActionLog(context.db());
                journal.create(task, agentIdentity());
                journal.state(task, "FAILED");
                var failed = new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(ProblemCode.IO_FAILURE,
                    "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED", "Constructed uncompensated failure", Map.of(), Optional.empty())));
                assertThrows(SQLException.class, () -> new DeploymentAgentHistoryArchive(context).settle(task, failed));
                assertTrue(journal.find(task).isPresent());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                return success();
            });
        }
    }

    @Test
    void restartAfterLocalCommitBeforePlatformEndUsesOnlyReceiptAndNoOriginalDispatch() throws Exception {
        enable();
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", System.getProperty("java.class.path"), getClass().getName(), "--settled-crash", root.toString())
            .redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue(), new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
        UUID original = UUID.fromString(Files.readString(root.resolve("settled-original")));
        try (var runtime = open()) {
            var pages = new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry(new gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageHost() {
                public void show(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key, gold.debug.windowstolinux.appapi.ui.page.PageSpec spec, javax.swing.JComponent component) {
                    throw new AssertionError("No UI expected");
                }

                public void focus(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key) {
                }

                public void remove(gold.debug.windowstolinux.app.ui.mod.ModPageRegistry.PageKey key) {
                }

                public void environment(gold.debug.windowstolinux.appapi.ui.page.UiEnvironment environment) {
                }
            }, new gold.debug.windowstolinux.appapi.ui.page.UiEnvironment(Locale.SIMPLIFIED_CHINESE, "LIGHT", false));
            var report = runtime.startDevelopmentMods(workspace(), pages);
            assertTrue(report.running().isEmpty());
            assertEquals(List.of("MOD_RECOVERY_READY"), report.problems().stream().map(Problem::eventCode).toList());
            var ended = runtime.tasks().reconcile(original).toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.ENDED, ended.state());
            assertEquals(Optional.of(TaskResult.SUCCEEDED), ended.outcome());
            assertEquals(0, scalar(runtime, "SELECT COUNT(*) FROM platform_task_resource WHERE task_id='" + original + "'"));
        }
    }

    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("--settled-crash")) throw new IllegalArgumentException();
        try {
            var fixture = new DeploymentAgentHistoryArchiveTest();
            fixture.root = Path.of(args[1]);
            var runtime = fixture.open();
            fixture.start(runtime, true);
            fixture.server(runtime);
            var context = runtime.modContexts().get(OWNER).context();
            var task = context.tasks().submit(new gold.debug.windowstolinux.appapi.task.model.TaskRequest(
                gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs.TASK_TYPE, "Settlement crash gap", List.of("server"), true), original -> {
                fixture.preparedSuccess(runtime, original);
                new DeploymentAgentHistoryArchive(context).settle(original, fixture.success());
                Files.writeString(fixture.root.resolve("settled-original"), original.id().toString());
                return new OperationResult<Void>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                    "CONSTRUCTED_AFTER_SETTLEMENT_CRASH", "Platform ending interrupted after Mod commit", Map.of(), Optional.empty())));
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (task.snapshot().state() != gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN
                && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(gold.debug.windowstolinux.appapi.task.model.TaskState.UNKNOWN, task.snapshot().state());
            assertTrue(Files.isRegularFile(fixture.root.resolve("settled-original")));
            assertEquals(task.id().toString(), Files.readString(fixture.root.resolve("settled-original")));
            assertTrue(!task.completion().toCompletableFuture().isDone(), "The platform original remains unfinished at the crash");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    @Test
    void successRemovesWorkingRowsAndKeepsBusinessAndSharedCreatingOriginAcrossReopen() throws Exception {
        enable();
        var tasks = new ArrayList<UUID>();
        var origin = new AtomicReference<UUID>();
        var release = new AtomicReference<DeploymentReleaseIdentity>();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            for (int index = 0; index < 2; index++)
                run(runtime, task -> {
                    var id = preparedSuccess(runtime, task);
                    tasks.add(task.id());
                    var context = runtime.modContexts().get(OWNER).context();
                    var store = new DeploymentExecutionStore(context.db());
                    var history = new DeploymentReleaseStore(context.db()).list(task, "application").getFirst();
                    if (origin.get() == null) {
                        origin.set(id);
                        release.set(history.identity());
                    } else {
                        assertEquals(origin.get(), history.executionId());
                        assertEquals(release.get(), history.identity());
                    }
                    var archive = new DeploymentAgentHistoryArchive(context);
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertEquals(TaskResult.SUCCEEDED, archive.settle(task, success()).outcome());
                    assertTrue(store.findForTask(task).isEmpty());
                    assertTrue(new DeploymentAgentActionLog(context.db()).find(task).isEmpty());
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_event"));
                    verify(runtime, task, graph("application"), publications(graph("application"), 1, List.of()));
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(task.id(), task).isPresent());
                    return success();
                });
            assertEquals(3, scalar(runtime, "SELECT COUNT(*) FROM mod_server_reference WHERE state='ACTIVE'"));
            valid(runtime);
        }
        try (var runtime = open()) {
            start(runtime, true);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                for (UUID original : tasks) {
                    try (var connection = context.db().openConnection(task)) {
                        var receipt = DeploymentOperationReceipts.read(connection, original).orElseThrow();
                        assertEquals(release.get(), receipt.release().orElseThrow());
                        DeploymentOperationReceipts.validateBusiness(connection, receipt);
                        assertEquals(origin.get(), DeploymentReleaseOrigin.read(connection,
                            DeploymentReleaseStore.read(connection, "application", release.get().id()).orElseThrow()).executionId());
                    }
                    assertTrue(new DeploymentApplicationReader(context.db()).findForCompletedTask(original, task).isPresent());
                }
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"receipt", "events", "task", "execution", "before_commit"})
    void anyWriteFailureKeepsOriginalWorkingEvidenceAndRollsBackTheReceipt(String point) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                preparedSuccess(runtime, task);
                var context = runtime.modContexts().get(OWNER).context();
                var original = new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow();
                var events = new DeploymentAgentActionLog(context.db()).events(task);
                var fault = new DeploymentAgentHistoryArchive(withDatabase(context, fault(context.db(), point)));
                assertThrows(Exception.class, () -> fault.settle(task, success()));
                assertEquals(original, new DeploymentExecutionStore(context.db()).findForTask(task).orElseThrow());
                assertEquals(events, new DeploymentAgentActionLog(context.db()).events(task));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                new DeploymentAgentHistoryArchive(context).settle(task, success());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void lostCommitAcknowledgementReadsReceiptWithoutReconstructingOrRepeatingWork() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                preparedSuccess(runtime, task);
                var context = runtime.modContexts().get(OWNER).context();
                var failure = assertThrows(ApiException.class, () -> new DeploymentAgentHistoryArchive(
                    withDatabase(context, commitFault(context.db()))).settle(task, success()));
                assertEquals("DEPLOYMENT_COMMIT_UNKNOWN", failure.problem().eventCode());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_execution"));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task"));
                assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                assertEquals(TaskResult.SUCCEEDED, new DeploymentAgentHistoryArchive(context).retained(task).orElseThrow().outcome());
                return success();
            });
            valid(runtime);
        }
    }

    @Test
    void preformalCompensationCanSettleFailureWithoutCreatingAnApplication() throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentAgentActionLog(context.db());
                journal.create(task, agentIdentity());
                journal.event(task, "AI_RECOVERY_SETTLED", task.id().toString(), "FAILED:COMMAND_NATIVE_DATABASE_SECRET_CANDIDATE_SOURCE_SETTLED");
                journal.state(task, "FAILED");
                var failed = new OperationResult<Void>(TaskResult.FAILED, Optional.empty(), Optional.of(new Problem(
                    ProblemCode.IO_FAILURE, "DEPLOYMENT_AGENT_PREFORMAL_RECONCILED", "Verified no remaining effects", Map.of(), Optional.empty())));
                assertEquals(TaskResult.FAILED, new DeploymentAgentHistoryArchive(context).settle(task, failed).outcome());
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM managed_application"));
                assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_release"));
                try (var connection = context.db().openConnection(task)) {
                    var receipt = DeploymentOperationReceipts.read(connection, task.id()).orElseThrow();
                    assertEquals(TaskResult.FAILED, receipt.outcome());
                    assertTrue(receipt.release().isEmpty());
                }
                return success();
            });
            valid(runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "FAILED", "PENDING", "BUSY"})
    void unresolvedStateEffectOrExplicitResourceCannotBeRemoved(String state) throws Exception {
        enable();
        try (var runtime = open()) {
            start(runtime, true);
            server(runtime);
            run(runtime, task -> {
                preparedSuccess(runtime, task);
                var context = runtime.modContexts().get(OWNER).context();
                var journal = new DeploymentAgentActionLog(context.db());
                if (Set.of("UNKNOWN", "FAILED").contains(state)) journal.state(task, state);
                if (state.equals("PENDING")) journal.event(task, "COMMAND_EXECUTING", "pending-command", "original");
                gold.debug.windowstolinux.appapi.task.recover.TaskCheckpoint held = state.equals("BUSY")
                    ? context.tasks().beginCheckpoint(task, "fixture.held", "{\"formatVersion\":1}") : null;
                try {
                    if (held == null)
                        assertThrows(SQLException.class, () -> new DeploymentAgentHistoryArchive(context).settle(task, success()));
                    else
                        assertEquals(TaskResult.UNKNOWN, new DeploymentAgentHistoryArchive(context).settle(task, success()).outcome());
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_execution"));
                    assertEquals(1, count(runtime, task, "SELECT COUNT(*) FROM deployment_agent_task"));
                    assertEquals(0, count(runtime, task, "SELECT COUNT(*) FROM deployment_operation_receipt"));
                } finally {
                    if (held != null) held.close();
                }
                if (state.equals("PENDING")) journal.event(task, "COMMAND_RESULT", "pending-command", "verified");
                journal.state(task, "SUCCEEDED");
                new DeploymentAgentHistoryArchive(context).settle(task, success());
                return success();
            });
        }
    }

    private static ModContext withDatabase(ModContext original, ModDatabase database) {
        return (ModContext) Proxy.newProxyInstance(ModContext.class.getClassLoader(), new Class<?>[]{ModContext.class},
            (proxy, method, args) -> method.getName().equals("db") ? database : invoke(method, original, args));
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
    }

    private static ModDatabase fault(ModDatabase original, String point) {
        return new ModDatabase() {
            public gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity identity() throws ApiException {
                return original.identity();
            }

            public Connection openConnection(TaskContext task) throws SQLException, ApiException {
                var connection = original.openConnection(task);
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("commit") && point.equals("lost_ack")) {
                        invoke(method, connection, args);
                        throw new SQLException("Committed original settlement acknowledgement lost");
                    }
                    if (method.getName().equals("commit") && point.equals("before_commit"))
                        throw new SQLException("Pre-commit fixture failure");
                    if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                        boolean fail = point.equals("proof") && sql.startsWith("INSERT INTO deployment_agent_event")
                            || point.equals("receipt") && sql.startsWith("INSERT INTO deployment_operation_receipt")
                            || point.equals("events") && sql.startsWith("DELETE FROM deployment_agent_event")
                            || point.equals("task") && sql.startsWith("DELETE FROM deployment_agent_task")
                            || point.equals("execution") && sql.startsWith("DELETE FROM deployment_execution");
                        if (fail) throw new SQLException("Cleanup statement fixture failure");
                    }
                    return invoke(method, connection, args);
                });
            }
        };
    }
}
