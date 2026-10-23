package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.*;
import gold.debug.windowstolinux.mod.deployment.db.execution.*;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.linux.build.DeploymentBuildCommandChannel;
import gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentCommandReviewRouter;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog.Event;
import gold.debug.windowstolinux.mod.deployment.service.core.delivery.DeploymentGraphPublisher;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import gold.debug.windowstolinux.mod.deployment.service.core.publish.DeploymentPublishResult;

/** One bounded original-Agent compensation producer. No model-selected command or arbitrary program is accepted. */
public final class DeploymentAgentPublicationRecovery implements DeploymentCurrentCommandEvidence {
    private static final ObjectMapper JSON=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final ModContext original;
    private final RemoteSession session;
    private final ServerId server;
    private final TaskContext task;
    private final String revision;
    private final DeploymentAgentActionLog journal;
    private final DeploymentAgentActionLog.Snapshot saved;
    private final DeploymentPublishResult proposal;
    private final Map<String,DeploymentGraphPublisher.Candidate> candidates;
    private final List<Event> origin;
    private final DeploymentAgentPublicationRecoveryProgram program;
    private final DeploymentCommandReviewScope reviewed;
    private Pending pending;

    private static final class Pending {
        final String operation;
        final UUID action;
        final DeploymentUnitIdentity unit;
        final ActionIntent intent;
        final CommandRequest request;
        final DeploymentAgentPublicationCompensation.Plan plan;
        final DeploymentAgentPublicationRecoveryProgram.Request programRequest;
        final DeploymentAgentPublicationRecoveryInstallation installation;
        final String requestDigest;
        DeploymentReviewDecision decision;
        String evidence;
        DeploymentAgentPublicationCorrection correction;
        DeploymentAgentPublicationCorrectionProgram.Request correctionRequest;
        DeploymentAgentPublicationReconsideration reconsideration;
        DeploymentAgentPublicationCorrectionProgram.Request reconsiderationRequest;
        DeploymentAgentPublicationContractDiagnosticFollowup contractFollowup;
        DeploymentAgentPublicationContractDiagnosticFollowupProgram.Request followupRequest;
        DeploymentAgentPublicationContractDiagnostic contractDiagnostic;
        DeploymentAgentPublicationContractDiagnosticProgram.Request contractRequest;
        DeploymentAgentPublicationContinuation continuation;
        DeploymentAgentPublicationContinuationProgram.Request continuationRequest;
        DeploymentAgentPublicationContinuation.Eligibility continuationEligibility;
        DeploymentAgentPublicationRecoveryPreflight preflight;
        DeploymentAgentPublicationRecoveryPreflightProgram.Request preflightRequest;
        DeploymentAgentPublicationRecoveryPreflight.Eligibility preflightEligibility;
        DeploymentAgentPublicationRecoveryDiagnostic diagnostic;
        DeploymentAgentPublicationRecoveryDiagnosticProgram.Request diagnosticRequest;
        DeploymentAgentPublicationCompensation.DiagnosticEligibility diagnosticEligibility;
        Pending(String operation,UUID action,DeploymentUnitIdentity unit,ActionIntent intent,CommandRequest request,
            DeploymentAgentPublicationCompensation.Plan plan,DeploymentAgentPublicationRecoveryProgram.Request programRequest,
            DeploymentAgentPublicationRecoveryInstallation installation)throws Exception {
            this.operation=operation;this.action=action;this.unit=unit;this.intent=intent;this.request=request;this.plan=plan;
            this.programRequest=programRequest;this.installation=installation;this.requestDigest=DeploymentAgentPublicationRecoveryInstallation.requestDigest(request);
        }
    }

    public DeploymentAgentPublicationRecovery(ModContext original,DeploymentCommandReviewScope base,RemoteSession session,ServerId server,
        TaskContext task,String revision,DeploymentAgentActionLog.Snapshot saved,DeploymentPublishResult proposal,
        Map<String,DeploymentGraphPublisher.Candidate> candidates,DeploymentAgentActionLog journal)throws Exception {
        this.original=original;this.session=session;this.server=server;this.task=task;this.revision=revision;this.saved=saved;this.proposal=proposal;
        this.candidates=Map.copyOf(candidates);this.journal=journal;
        var events=journal.events(task);var first=events.stream().filter(e->e.type().equals(DeploymentAgentPublicationRecoveryInstallation.EVENT)
            ||e.type().equals(DeploymentAgentPublicationCompensation.PLAN)).findFirst();
        this.origin=List.copyOf(first.isEmpty()?events:events.stream().filter(e->e.id()<first.orElseThrow().id()).toList());
        this.program=new DeploymentAgentPublicationRecoveryProgram(proposal.graph().components().getFirst().unit().layout());
        this.reviewed=base.withCurrentEvidence(this);
        checkOriginal();
    }

    /** True means durable VERIFIED only; the caller must close its session/workflow before local FAILED settlement. */
    public boolean run() throws Exception {
        boolean hasPlan=journal.events(task).stream().anyMatch(e->e.type().equals(DeploymentAgentPublicationCompensation.PLAN));
        DeploymentAgentPublicationCompensation.Plan plan;
        if(hasPlan) {
            var state=DeploymentAgentPublicationCompensation.read(journal.events(task),task.id());plan=state.plan();
            DeploymentAgentPublicationCompensation.requireCurrent(original,task,journal,plan);
        } else {
            var formal=new DeploymentExecutionStore(original.db()).findForTask(task).orElseThrow();
            var installation=DeploymentAgentPublicationRecoveryInstallation.open(task,journal,formal,revision,program);
            installation.retainInterrupted();
            if(!ensureInstalled(installation))return false;
            plan=DeploymentAgentPublicationCompensation.begin(original,task,journal);
        }
        var current=DeploymentAgentPublicationCompensation.read(journal.events(task),task.id());
        var preflight=DeploymentAgentPublicationRecoveryPreflight.eligibility(current,journal.events(task));
        if(preflight.isPresent()&&!DeploymentAgentPublicationRecoveryPreflight.attempted(journal.events(task),preflight.orElseThrow())) {
            preflight(preflight.orElseThrow());
            return false; // Every v2 preflight invocation ends UNKNOWN; later explicit reconciliation retains its ordinary guards.
        }
        var continuation=DeploymentAgentPublicationContinuation.eligibility(current,journal.events(task));
        if(continuation.isPresent()&&!DeploymentAgentPublicationContinuation.attempted(journal.events(task),continuation.orElseThrow())) {
            if(!continueCurrent(continuation.orElseThrow()))return false;
            current=DeploymentAgentPublicationCompensation.read(journal.events(task),task.id());
        }
        var contract=DeploymentAgentPublicationContractDiagnostic.eligibility(current,journal.events(task));
        if(contract.isPresent()&&!DeploymentAgentPublicationContractDiagnostic.attempted(journal.events(task),contract.orElseThrow())) {
            contractDiagnostic(contract.orElseThrow());
            return false; // A read-only contract diagnostic never resolves the old ACK or enters cleanup in this invocation.
        }
        var diagnostic=DeploymentAgentPublicationCompensation.diagnosticEligibility(current,journal.events(task));
        if(diagnostic.isPresent()&&!DeploymentAgentPublicationRecoveryDiagnostic.attempted(journal.events(task),diagnostic.orElseThrow().pending())) {
            diagnose(diagnostic.orElseThrow());
            return false; // This invocation ends UNKNOWN even after a complete read-only diagnostic receipt.
        }
        int limit=proposal.graph().components().size()*2+1;
        for(int count=0;count<limit;count++) {
            var state=DeploymentAgentPublicationCompensation.read(journal.events(task),task.id());
            if(state.verified().isPresent())return true;
            if(state.pending().isPresent()) {
                var reconsideration=DeploymentAgentPublicationReconsideration.eligibility(state,journal.events(task));
                if(reconsideration.isPresent()&&!DeploymentAgentPublicationReconsideration.attempted(journal.events(task),reconsideration.orElseThrow())) {
                    if(!reconsiderCurrent(reconsideration.orElseThrow()))return false;
                    continue; // Only a post-answer fresh native goal proof can reach the original independent cleanup path.
                }
                if(!observe())return false;
                var fresh=DeploymentAgentPublicationCompensation.read(journal.events(task),task.id());
                var followup=DeploymentAgentPublicationContractDiagnosticFollowup.eligibility(fresh,journal.events(task));
                if(followup.isPresent()&&!DeploymentAgentPublicationContractDiagnosticFollowup.attempted(journal.events(task),followup.orElseThrow())) {
                    contractFollowup(followup.orElseThrow());
                    return false;
                }
                var correction=DeploymentAgentPublicationCorrection.eligibility(fresh,journal.events(task));
                if(correction.isPresent()&&!DeploymentAgentPublicationCorrection.attempted(journal.events(task),correction.orElseThrow())) {
                    correctCurrent(correction.orElseThrow());
                    return false; // A correction receipt never resolves the old ACK; a later independently reviewed graph is required.
                }
                DeploymentAgentPublicationCompensation.observedOnly(task,journal,state.pending().orElseThrow());
                // The old command's missing/UNKNOWN ACK remains unchanged; no modification is resent.
                continue;
            }
            var next=DeploymentAgentPublicationCompensation.next(state);
            if(next.isEmpty()) {
                if(!observe())return false;
                DeploymentAgentPublicationCompensation.verify(original,task,journal);return true;
            }
            if(!execute(plan,next.orElseThrow()))return false;
        }
        return false;
    }

    private boolean continueCurrent(DeploymentAgentPublicationContinuation.Eligibility eligible)throws Exception {
        var unit=proposal.graph().components().stream().filter(p->p.componentId().equals(eligible.original().component().id())).findFirst().orElseThrow().unit();
        var diagnosticProgram=new DeploymentAgentPublicationContinuationProgram(unit.layout());
        try(var request=diagnosticProgram.request(DeploymentAgentPublicationContinuation.binding(eligible),UUID.randomUUID())) {
            var intent=RemoteActions.command(server,eligible.original().plan().sha256(),request.command())
                .withSemantics(new ActionSemantics(DeploymentAgentPublicationContinuationProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationContinuation.open(task,journal,eligible,request,intent);
            pending=new Pending(DeploymentAgentPublicationContinuationProgram.OPERATION,request.action(),unit,intent,request.command(),eligible.original().plan(),null,null);
            pending.continuation=durable;pending.continuationRequest=request;pending.continuationEligibility=eligible;
            try {
                var grant=DeploymentCommandReviewRouter.approve(reviewed.interaction(),intent,request.command(),task);
                if(grant.outcome()!=TaskResult.SUCCEEDED){durable.retainInterrupted();return false;}
                require(pending.decision!=null&&pending.decision.decision().equals("APPROVE")&&pending.evidence!=null,"Independent diagnostic decision is missing");
                durable.granted(grant.value().orElseThrow());verify(intent,request.command(),task);
                durable.dispatch();verify(intent,request.command(),task);
                OperationResult<CommandResult> actual;
                try {actual=session.execute(request.command(),grant.value().orElseThrow());}
                catch(ApiException unavailable){actual=new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(unavailable.problem()));}
                DeploymentAgentPublicationContinuationProgram.Receipt receipt=null;
                try {receipt=diagnosticProgram.parse(request,actual);}catch(RuntimeException unconfirmed){/* Preserve actual digest without accepting unsafe or partial output. */}
                durable.result(actual,receipt);
                return receipt!=null&&JSON.readTree(receipt.canonicalJson()).path("status").asText().equals("ROLLED_BACK");
            } catch(ApiException unavailable) {durable.retainInterrupted();}
            catch(Exception unavailable) {durable.retainInterrupted();throw unavailable;}
            finally {pending=null;}
        }
        return false;
    }

    private void correctCurrent(DeploymentAgentPublicationCorrection.Eligibility eligible)throws Exception {
        var unit=proposal.graph().components().stream().filter(p->p.componentId().equals(eligible.original().component().id())).findFirst().orElseThrow().unit();
        var diagnosticProgram=new DeploymentAgentPublicationCorrectionProgram(unit.layout());
        try(var request=diagnosticProgram.request(DeploymentAgentPublicationCorrection.binding(eligible),UUID.randomUUID())) {
            var intent=RemoteActions.command(server,eligible.original().plan().sha256(),request.command())
                .withSemantics(new ActionSemantics(DeploymentAgentPublicationCorrectionProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationCorrection.open(task,journal,eligible,request,intent);
            pending=new Pending(DeploymentAgentPublicationCorrectionProgram.OPERATION,request.action(),unit,intent,request.command(),eligible.original().plan(),null,null);
            pending.correction=durable;pending.correctionRequest=request;
            try {
                var grant=DeploymentCommandReviewRouter.approve(reviewed.interaction(),intent,request.command(),task);
                if(grant.outcome()!=TaskResult.SUCCEEDED){durable.retainInterrupted();return;}
                require(pending.decision!=null&&pending.decision.decision().equals("APPROVE")&&pending.evidence!=null,"Independent diagnostic decision is missing");
                durable.granted(grant.value().orElseThrow());verify(intent,request.command(),task);
                durable.dispatch();verify(intent,request.command(),task);
                OperationResult<CommandResult> actual;
                try {actual=session.execute(request.command(),grant.value().orElseThrow());}
                catch(ApiException unavailable){actual=new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(unavailable.problem()));}
                DeploymentAgentPublicationCorrectionProgram.Receipt receipt=null;
                try {receipt=diagnosticProgram.parse(request,actual);}catch(RuntimeException unconfirmed){/* Preserve actual digest without accepting unsafe or partial output. */}
                durable.result(actual,receipt);
            } catch(ApiException unavailable) {durable.retainInterrupted();}
            catch(Exception unavailable) {durable.retainInterrupted();throw unavailable;}
            finally {pending=null;}
        }
    }

    private boolean reconsiderCurrent(DeploymentAgentPublicationReconsideration.Eligibility eligible)throws Exception {
        var durable=DeploymentAgentPublicationReconsideration.beginAdmission(task,journal,eligible);
        try {
            checkOriginal();durable.verifyAdmission();
            var answer=reviewed.interaction().requestText(DeploymentAgentPublicationReconsideration.TITLE,durable.question(),false,task);
            checkOriginal();durable.verifyAdmission();
            if(!durable.answer(answer.outcome()==TaskResult.SUCCEEDED?answer.value().orElse(null):null))return false;
            if(!observe()){durable.retainInterrupted();return false;}
            var events=journal.events(task);var state=DeploymentAgentPublicationCompensation.read(events,task.id());
            var fresh=DeploymentAgentPublicationCorrection.eligibility(state,events);
            if(fresh.isEmpty()) {
                // No new request: only the original guarded, post-answer native goal proof may advance.
                DeploymentAgentPublicationCompensation.observedOnly(task,journal,state.pending().orElseThrow());
                return true;
            }
            var selected=fresh.orElseThrow();
            var unit=proposal.graph().components().stream().filter(p->p.componentId().equals(selected.original().component().id())).findFirst().orElseThrow().unit();
            var fixed=new DeploymentAgentPublicationCorrectionProgram(unit.layout());
            try(var request=fixed.request(DeploymentAgentPublicationCorrection.binding(selected),UUID.randomUUID())) {
                var intent=RemoteActions.command(server,selected.original().plan().sha256(),request.command())
                    .withSemantics(new ActionSemantics(DeploymentAgentPublicationCorrectionProgram.OPERATION,request.resourceTargets()));
                durable.bindRequest(selected,request,intent);
                pending=new Pending(DeploymentAgentPublicationCorrectionProgram.OPERATION,request.action(),unit,intent,request.command(),selected.original().plan(),null,null);
                pending.reconsideration=durable;pending.reconsiderationRequest=request;
                try {
                    var grant=DeploymentCommandReviewRouter.approve(reviewed.interaction(),intent,request.command(),task);
                    if(grant.outcome()!=TaskResult.SUCCEEDED){durable.retainInterrupted();return false;}
                    require(pending.decision!=null&&pending.decision.decision().equals("APPROVE")&&pending.evidence!=null,"Independent reconsideration decision is missing");
                    durable.granted(grant.value().orElseThrow());verify(intent,request.command(),task);
                    durable.dispatch();verify(intent,request.command(),task);
                    OperationResult<CommandResult> actual;
                    try {actual=session.execute(request.command(),grant.value().orElseThrow());}
                    catch(ApiException unavailable){actual=new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(unavailable.problem()));}
                    DeploymentAgentPublicationCorrectionProgram.Receipt receipt=null;
                    try {receipt=fixed.parse(request,actual);}catch(RuntimeException unconfirmed){/* Keep uncertain transport without inventing a safe receipt. */}
                    durable.result(actual,receipt);
                } finally {pending=null;}
            }
        }catch(ApiException unavailable){durable.retainInterrupted();}
        catch(Exception unavailable){durable.retainInterrupted();throw unavailable;}
        return false; // A reconsidered command receipt is never an original ACK or settlement proof.
    }

    private void contractFollowup(DeploymentAgentPublicationContractDiagnosticFollowup.Eligibility eligible)throws Exception {
        var unit=proposal.graph().components().stream().filter(p->p.componentId().equals(eligible.original().component().id())).findFirst().orElseThrow().unit();
        var diagnosticProgram=new DeploymentAgentPublicationContractDiagnosticFollowupProgram(unit.layout());
        try(var request=diagnosticProgram.request(DeploymentAgentPublicationContractDiagnosticFollowup.binding(eligible),UUID.randomUUID())) {
            var intent=RemoteActions.command(server,eligible.original().plan().sha256(),request.command())
                .withSemantics(new ActionSemantics(DeploymentAgentPublicationContractDiagnosticFollowupProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationContractDiagnosticFollowup.open(task,journal,eligible,request,intent);
            pending=new Pending(DeploymentAgentPublicationContractDiagnosticFollowupProgram.OPERATION,request.action(),unit,intent,request.command(),eligible.original().plan(),null,null);
            pending.contractFollowup=durable;pending.followupRequest=request;
            try {
                var grant=DeploymentCommandReviewRouter.approve(reviewed.interaction(),intent,request.command(),task);
                if(grant.outcome()!=TaskResult.SUCCEEDED){durable.retainInterrupted();return;}
                require(pending.decision!=null&&pending.decision.decision().equals("APPROVE")&&pending.evidence!=null,"Independent diagnostic decision is missing");
                durable.granted(grant.value().orElseThrow());verify(intent,request.command(),task);
                durable.dispatch();verify(intent,request.command(),task);
                OperationResult<CommandResult> actual;
                try {actual=session.execute(request.command(),grant.value().orElseThrow());}
                catch(ApiException unavailable){actual=new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(unavailable.problem()));}
                DeploymentAgentPublicationContractDiagnosticFollowupProgram.Receipt receipt=null;
                try {receipt=diagnosticProgram.parse(request,actual);}catch(RuntimeException unconfirmed){/* Preserve actual digest without accepting unsafe or partial output. */}
                durable.result(actual,receipt);
            } catch(ApiException unavailable) {durable.retainInterrupted();}
            catch(Exception unavailable) {durable.retainInterrupted();throw unavailable;}
            finally {pending=null;}
        }
    }

    private void contractDiagnostic(DeploymentAgentPublicationContractDiagnostic.Eligibility eligible)throws Exception {
        var unit=proposal.graph().components().stream().filter(p->p.componentId().equals(eligible.original().component().id())).findFirst().orElseThrow().unit();
        var diagnosticProgram=new DeploymentAgentPublicationContractDiagnosticProgram(unit.layout());
        try(var request=diagnosticProgram.request(DeploymentAgentPublicationContractDiagnostic.binding(eligible),UUID.randomUUID())) {
            var intent=RemoteActions.command(server,eligible.original().plan().sha256(),request.command())
                .withSemantics(new ActionSemantics(DeploymentAgentPublicationContractDiagnosticProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationContractDiagnostic.open(task,journal,eligible,request,intent);
            pending=new Pending(DeploymentAgentPublicationContractDiagnosticProgram.OPERATION,request.action(),unit,intent,request.command(),eligible.original().plan(),null,null);
            pending.contractDiagnostic=durable;pending.contractRequest=request;
            try {
                var grant=DeploymentCommandReviewRouter.approve(reviewed.interaction(),intent,request.command(),task);
                if(grant.outcome()!=TaskResult.SUCCEEDED){durable.retainInterrupted();return;}
                require(pending.decision!=null&&pending.decision.decision().equals("APPROVE")&&pending.evidence!=null,"Independent diagnostic decision is missing");
                durable.granted(grant.value().orElseThrow());verify(intent,request.command(),task);
                durable.dispatch();verify(intent,request.command(),task);
                OperationResult<CommandResult> actual;
                try {actual=session.execute(request.command(),grant.value().orElseThrow());}
                catch(ApiException unavailable){actual=new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(unavailable.problem()));}
                DeploymentAgentPublicationContractDiagnosticProgram.Receipt receipt=null;
                try {receipt=diagnosticProgram.parse(request,actual);}catch(RuntimeException unconfirmed){/* Preserve actual digest without accepting unsafe or partial output. */}
                durable.result(actual,receipt);
            } catch(ApiException unavailable) {durable.retainInterrupted();}
            catch(Exception unavailable) {durable.retainInterrupted();throw unavailable;}
            finally {pending=null;}
        }
    }

    private void preflight(DeploymentAgentPublicationRecoveryPreflight.Eligibility eligible)throws Exception {
        var unit=proposal.graph().components().stream().filter(p->p.componentId().equals(eligible.original().component().id())).findFirst().orElseThrow().unit();
        var diagnosticProgram=new DeploymentAgentPublicationRecoveryPreflightProgram(unit.layout());
        try(var request=diagnosticProgram.request(DeploymentAgentPublicationRecoveryPreflight.binding(eligible),UUID.randomUUID())) {
            var intent=RemoteActions.command(server,eligible.original().plan().sha256(),request.command())
                .withSemantics(new ActionSemantics(DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationRecoveryPreflight.open(task,journal,eligible,request,intent);
            pending=new Pending(DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION,request.action(),unit,intent,request.command(),eligible.original().plan(),null,null);
            pending.preflight=durable;pending.preflightRequest=request;pending.preflightEligibility=eligible;
            try {
                var grant=DeploymentCommandReviewRouter.approve(reviewed.interaction(),intent,request.command(),task);
                if(grant.outcome()!=TaskResult.SUCCEEDED){durable.retainInterrupted();return;}
                require(pending.decision!=null&&pending.decision.decision().equals("APPROVE")&&pending.evidence!=null,"Independent diagnostic decision is missing");
                durable.granted(grant.value().orElseThrow());verify(intent,request.command(),task);
                durable.dispatch();verify(intent,request.command(),task);
                OperationResult<CommandResult> actual;
                try {actual=session.execute(request.command(),grant.value().orElseThrow());}
                catch(ApiException unavailable){actual=new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(unavailable.problem()));}
                DeploymentAgentPublicationRecoveryPreflightProgram.Receipt receipt=null;
                try {receipt=diagnosticProgram.parse(request,actual);}catch(RuntimeException unconfirmed){/* Preserve actual digest without accepting unsafe or partial output. */}
                durable.result(actual,receipt);
            } catch(ApiException unavailable) {durable.retainInterrupted();}
            catch(Exception unavailable) {durable.retainInterrupted();throw unavailable;}
            finally {pending=null;}
        }
    }

    private void diagnose(DeploymentAgentPublicationCompensation.DiagnosticEligibility eligible)throws Exception {
        var unit=proposal.graph().components().stream().filter(p->p.componentId().equals(eligible.component().id())).findFirst().orElseThrow().unit();
        var diagnosticProgram=new DeploymentAgentPublicationRecoveryDiagnosticProgram(unit.layout());
        try(var request=diagnosticProgram.request(DeploymentAgentPublicationRecoveryDiagnostic.binding(eligible),UUID.randomUUID())) {
            var intent=RemoteActions.command(server,eligible.plan().sha256(),request.command())
                .withSemantics(new ActionSemantics(DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION,request.resourceTargets()));
            var durable=DeploymentAgentPublicationRecoveryDiagnostic.open(task,journal,eligible,request,intent);
            pending=new Pending(DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION,request.action(),unit,intent,request.command(),eligible.plan(),null,null);
            pending.diagnostic=durable;pending.diagnosticRequest=request;pending.diagnosticEligibility=eligible;
            try {
                var grant=DeploymentCommandReviewRouter.approve(reviewed.interaction(),intent,request.command(),task);
                if(grant.outcome()!=TaskResult.SUCCEEDED){durable.retainInterrupted();return;}
                require(pending.decision!=null&&pending.decision.decision().equals("APPROVE")&&pending.evidence!=null,"Independent diagnostic decision is missing");
                durable.granted(grant.value().orElseThrow());verify(intent,request.command(),task);
                durable.dispatch();verify(intent,request.command(),task);
                OperationResult<CommandResult> actual;
                try {actual=session.execute(request.command(),grant.value().orElseThrow());}
                catch(ApiException unavailable){actual=new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(unavailable.problem()));}
                DeploymentAgentPublicationRecoveryDiagnosticProgram.Receipt receipt=null;
                try {receipt=diagnosticProgram.parse(request,actual);}catch(RuntimeException unconfirmed){/* Preserve actual digest without accepting unsafe or partial output. */}
                durable.result(actual,receipt);
            } catch(ApiException unavailable) {durable.retainInterrupted();}
            catch(Exception unavailable) {durable.retainInterrupted();throw unavailable;}
            finally {pending=null;}
        }
    }

    private boolean ensureInstalled(DeploymentAgentPublicationRecoveryInstallation installation)throws Exception {
        var unit=proposal.graph().components().getFirst().unit();
        try(var request=program.readiness(unit,task.id(),UUID.randomUUID())) {
            if(!store(installation,request))return false;
        }
        if(installation.state().ready())return true;
        if(!installation.state().mayInstall())return false;
        try(var request=program.install(unit,task.id(),UUID.randomUUID())) {
            return store(installation,request)&&installation.state().ready();
        }
    }

    private boolean store(DeploymentAgentPublicationRecoveryInstallation installation,DeploymentAgentPublicationRecoveryProgram.Request request)throws Exception {
        var intent=RemoteActions.command(server,installation.state().binding(),request.command()).withSemantics(new ActionSemantics(request.operation(),request.resourceTargets()));
        installation.request(request,intent);
        pending=new Pending(request.operation(),request.action(),request.unit(),intent,request.command(),null,request,installation);
        try {
            var grant=DeploymentCommandReviewRouter.approve(reviewed.interaction(),intent,request.command(),task);
            if(grant.outcome()!=TaskResult.SUCCEEDED){installation.retainInterrupted();return false;}
            require(pending.decision!=null&&pending.decision.decision().equals("APPROVE"),"Independent store decision is missing");
            installation.granted(request.action(),grant.value().orElseThrow());verify(intent,request.command(),task);
            installation.dispatch(request.action());verify(intent,request.command(),task);
            OperationResult<CommandResult> actual;
            try { actual=session.execute(request.command(),grant.value().orElseThrow()); }
            catch(ApiException unavailable){actual=new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(unavailable.problem()));}
            DeploymentAgentPublicationRecoveryProgram.StoreReceipt receipt=null;
            try {receipt=program.parseStoreReceipt(request,actual);}catch(RuntimeException unconfirmed){/* Retain the actual bounded transport as UNKNOWN. */}
            installation.result(request.action(),actual,receipt);
            return installation.state().ready()||installation.state().mayInstall();
        } catch(Exception stopped) {installation.retainInterrupted();throw stopped;}
        finally {pending=null;}
    }

    private boolean execute(DeploymentAgentPublicationCompensation.Plan plan,DeploymentAgentPublicationCompensation.ActionStep step)throws Exception {
        var unit=proposal.graph().components().stream().filter(p->p.componentId().equals(step.component().id())).findFirst().orElseThrow().unit();
        UUID action=UUID.randomUUID();
        if(step.verb()==DeploymentAgentPublicationCompensation.Verb.GUARDED_ROLLBACK) {
            try(var request=program.guardedFirstRollback(unit,task.id(),plan.execution(),step.component().candidate(),step.component().release(),plan.sha256(),action)) {
                return execute(plan,step,unit,action,request.operation(),request.resourceTargets(),request.command(),request);
            }
        }
        try(var command=cleanup(unit,step.component().candidate())) {
            return execute(plan,step,unit,action,DeploymentAgentPublicationCompensation.CLEANUP_OPERATION,cleanupTargets(unit,step.component().candidate()),command,null);
        }
    }

    private boolean execute(DeploymentAgentPublicationCompensation.Plan plan,DeploymentAgentPublicationCompensation.ActionStep step,DeploymentUnitIdentity unit,
        UUID action,String operation,Set<ActionTarget> targets,CommandRequest command,DeploymentAgentPublicationRecoveryProgram.Request fixed)throws Exception {
        var intent=RemoteActions.command(server,plan.sha256(),command).withSemantics(new ActionSemantics(operation,targets));
        pending=new Pending(operation,action,unit,intent,command,plan,fixed,null);
        try {
            var grant=DeploymentCommandReviewRouter.approve(reviewed.interaction(),intent,command,task);
            if(grant.outcome()!=TaskResult.SUCCEEDED)return false;
            require(pending.decision!=null&&pending.decision.decision().equals("APPROVE")&&pending.evidence!=null,"Independent compensation decision is missing");
            verify(intent,command,task);
            var durable=DeploymentAgentPublicationCompensation.intend(original,task,journal,plan,action,intent,command,pending.decision,pending.evidence,grant.value().orElseThrow());
            verify(intent,command,task);
            OperationResult<CommandResult> actual;
            try {actual=session.execute(command,grant.value().orElseThrow());}catch(ApiException unavailable){actual=new OperationResult<>(TaskResult.UNKNOWN,Optional.empty(),Optional.of(unavailable.problem()));}
            boolean valid=false;
            try {
                if(fixed!=null){program.parseRollbackReceipt(fixed,actual);valid=true;}
                // The existing cleanup channel permits stderr with a complete exit-0 receipt; retain its actual digest as well.
                else valid=actual.outcome()==TaskResult.SUCCEEDED&&actual.value().filter(v->v.exitCode().equals(Optional.of(0))&&!v.truncated()&&v.stdout().equals("CANDIDATE_CLEANED=1\n")).isPresent();
            }catch(RuntimeException unconfirmed){/* No parsed receipt; the original action remains unknown. */}
            DeploymentAgentPublicationCompensation.acknowledge(task,journal,durable,actual,valid);
            return valid;
        } finally {pending=null;}
    }

    private boolean observe()throws Exception {
        var formal=new DeploymentExecutionStore(original.db()).findForTask(task).orElseThrow();
        DeploymentAgentPublicationObservation.observe(reviewed,session,server,task,revision,formal,proposal,candidates,journal);
        return DeploymentAgentPublicationObservation.readLatest(journal.events(task),task.id()).observationComplete();
    }

    @Override public Map<String,?> facts(ActionIntent intent,CommandRequest request,TaskContext supplied)throws ApiException {
        verify(intent,request,supplied);
        var value=pending;
        var facts=new LinkedHashMap<String,Object>();facts.put("authority","NONE");facts.put("purpose","ORIGINAL_FIRST_NATIVE_PARTIAL_PUBLICATION_FAILURE_COMPENSATION");
        facts.put("task",task.id().toString());facts.put("execution",execution().identity().executionId().toString());facts.put("application",proposal.graph().applicationId());
        facts.put("server",server.id());facts.put("targetSha256",saved.identity().targetDigest());facts.put("layoutSha256",value.unit.layout().digest());facts.put("modelReviewRevision",revision);
        facts.put("operation",value.operation);facts.put("actionId",value.action.toString());facts.put("unit",value.unit.id());facts.put("expectedOwnerSha256",value.unit.ownershipManifestSha256());
        facts.put("ownershipMeaning","Original proposal owner; actual sidecar guards or original-status receipts must verify remote ownership independently");
        facts.put("originalStateMeaning","Unique original snapshot pair proves no prior CURRENT/managed unit; it does not prove empty disk, release root or persistent data");
        facts.put("requestSha256",value.requestDigest);facts.put("planSha256",value.plan==null?"NOT_CREATED":value.plan.sha256());
        if(value.reconsiderationRequest!=null) {
            facts.put("purpose","HUMAN_CONFIRMED_ONCE_ONLY_INDEPENDENT_RECONSIDERATION_OF_DENIED_CORRECTION");
            facts.put("reconsiderationBinding",value.reconsiderationRequest.binding());
            facts.put("reconsiderationEffectPolicy","The original DENY and NOT_SENT remain unchanged. One recorded exact human answer only permits this independent APP review after a newer independently reviewed complete whole graph. It is not an approval, grant or permission to execute. The same fixed source, complete request and exact targets require a new independent APP decision and fresh single-use host grant. All results remain UNKNOWN; no old ACK, cleanup or settlement follows automatically.");
        } else if(value.correctionRequest!=null) {
            facts.put("purpose","ONCE_CURRENT_STATE_CORRECTION_AFTER_COMPLETE_OBSERVED_NATIVE_CONTRACT_FOLLOWUP");
            facts.put("correctionSourceVersion",new DeploymentAgentPublicationCorrectionProgram(value.unit.layout()).sourceVersion());
            facts.put("correctionBinding",value.correctionRequest.binding());
            facts.put("correctionEffectPolicy","One request per immutable original pending intent and original continuation REQUEST, never reset by later versions, actions, followups, denial, interruption or observations. Complete old source/receipts and the newer unmet-goal graph are rebound. Independently review the complete launcher/stdin and exact targets before a fresh single-use host grant. The fixed program rechecks both locks, all six historical fingerprint expectations and three native contracts before its one guarded first rollback; the candidate is retained for separately approved cleanup. Every result leaves this invocation UNKNOWN without replacing the old ACK, incrementing completed, reinstalling, cleaning or settling. Only a later independent whole-graph goal may permit the original OBSERVED_ONLY and separate cleanup/FAILED settlement guards.");
        } else if(value.followupRequest!=null) {
            facts.put("purpose","ONCE_READ_ONLY_FOLLOWUP_AFTER_COMPLETE_UNKNOWN_CONTROL_DIAGNOSTIC");
            facts.put("followupSourceVersion",new DeploymentAgentPublicationContractDiagnosticFollowupProgram(value.unit.layout()).sourceVersion());
            facts.put("followupBinding",value.followupRequest.binding());
            facts.put("followupEffectPolicy","One request per immutable original diagnostic REQUEST, independent of source version, new action or later observation. The old source/receipt is decoded unchanged. This fresh whole-graph still fails the pending goal; complete actual launcher/stdin and exact targets need independent APP and a new single-use host grant. Every result leaves this invocation UNKNOWN without mutation, installation, ACK replacement, cleanup or settlement. Later explicit reconciliation retains the normal fresh whole-graph goal guards.");
        } else if(value.contractRequest!=null) {
            facts.put("purpose","READ_ONLY_NATIVE_CONTRACT_DIAGNOSTIC_AFTER_UNKNOWN_CURRENT_STATE_CONTINUATION");
            facts.put("contractDiagnosticSourceVersion",new DeploymentAgentPublicationContractDiagnosticProgram(value.unit.layout()).sourceVersion());
            facts.put("contractDiagnosticBinding",value.contractRequest.binding());
            facts.put("fingerprintMeaning","The six fingerprints are immutable historical expectations from the verified v2/continuation binding, not measurements supplied by the later whole-graph status. The fixed read-only guard must revalidate them independently.");
            facts.put("contractDiagnosticEffectPolicy","One request per original pending intent and original continuation request. Complete actual launcher/stdin and exact targets require independent APP and a fresh single-use grant. Only the three fixed native contracts are checked. Any OBSERVED, REFUSED, missing, interrupted or UNKNOWN receipt leaves this invocation UNKNOWN, the original ACK/pending/completed unchanged, and cannot mutate, reinstall, clean, finalize or archive. A later explicit reconciliation retains the original newer whole-graph goal guards.");
        } else if(value.continuationRequest!=null) {
            var binding=value.continuationRequest.binding();
            facts.put("purpose","ONCE_ONLY_CURRENT_STATE_CONTINUATION_AFTER_COMPLETE_VERIFIED_PREFLIGHT");
            facts.put("continuationSourceVersion",new DeploymentAgentPublicationContinuationProgram(value.unit.layout()).sourceVersion());
            facts.put("currentStateBinding",binding);
            facts.put("continuationEffectPolicy","One new independently reviewed current-state guarded rollback, never a replay or replacement of the original FAILED/UNKNOWN ACK. The fixed guard must revalidate all six preflight fingerprints and native parameters/unit/data rules under its locks. No result settles the original pending action. A new independent APP whole-graph observation must prove its goal, then each candidate cleanup needs its own APP and fresh grant; only the final whole graph can authorize verified FAILED local settlement. Any refused, missing or unknown result is never resent.");
        } else if(value.preflightRequest!=null) {
            var original=value.preflightEligibility.original();var prior=value.preflightEligibility.prior();
            facts.put("purpose","READ_ONLY_V2_PREFLIGHT_AFTER_VERIFIED_ORIGINAL_V1_ENVIRONMENT_FAILURE");
            facts.put("preflightSourceVersion",new DeploymentAgentPublicationRecoveryPreflightProgram(value.unit.layout()).sourceVersion());
            facts.put("priorDiagnostic",Map.of("action",prior.action().toString(),"requestEvent",prior.requestEvent(),"endEvent",prior.endEvent(),"requestSha256",prior.requestSha256(),"receiptSha256",prior.receiptSha256(),"pendingResultEvent",prior.pendingResultEvent(),"pendingResultSha256",prior.pendingResultSha256()));
            facts.put("pendingIntentEvent",original.pending().event());facts.put("pendingAction",original.pending().actionId().toString());
            facts.put("pendingIntentSha256",DeploymentAgentPublicationRecoveryInstallation.hash(original.pending().frame()));
            facts.put("originalObservationEnd",original.observationEnd());facts.put("originalObservationSha256",original.observationSha256());
            facts.put("environmentMeaning","The fixed Bash prelude verifies the SDK exact /dev/null BASH_ENV and ENV sentinels and refuses LD key presence before removing only those sentinels. Isolated Python then enumerates its complete bounded environment and refuses exported-function presence or incomplete classification before any diagnostic file, lock or systemctl check. Only fixed categories are reported, never names or values.");
            facts.put("preflightEffectPolicy","Read-only checks only; one attempt per complete v1 environment-failure receipt. This invocation remains UNKNOWN and cannot resolve the old ACK, mutate, clean, finish, archive, reinstall or replay a rollback.");
        } else if(value.diagnosticRequest!=null) {
            var diagnosticProgram=new DeploymentAgentPublicationRecoveryDiagnosticProgram(value.unit.layout());
            facts.put("purpose","READ_ONLY_DIAGNOSTIC_OF_ORIGINAL_FAILED_UNKNOWN_ROLLBACK");
            facts.put("diagnosticSourceVersion",diagnosticProgram.sourceVersion());
            facts.put("pendingIntentEvent",value.diagnosticRequest.binding().pendingIntentEvent());
            facts.put("pendingAction",value.diagnosticRequest.binding().pendingAction().toString());
            facts.put("pendingIntentSha256",value.diagnosticRequest.binding().pendingIntentSha256());
            facts.put("originalObservationEnd",value.diagnosticEligibility.observationEnd());
            facts.put("originalObservationSha256",value.diagnosticEligibility.observationSha256());
            var status=value.diagnosticEligibility.status();
            facts.put("latestOriginalStatus",Map.ofEntries(Map.entry("current",status.current().orElse("none")),Map.entry("currentKind",status.currentKind()),
                Map.entry("currentMode",status.currentMode()),Map.entry("snapshot",status.snapshot()),Map.entry("previousDigest",status.previousDigest().orElse("none")),
                Map.entry("previousKind",status.previousKind()),Map.entry("previousRunning",status.previousRunning().map(Object::toString).orElse("none")),
                Map.entry("maintenance",status.maintenance()),Map.entry("candidate",status.candidate()),Map.entry("buildActive",status.buildActive()),
                Map.entry("unitPresent",status.unitPresent()),Map.entry("containerPresent",status.containerPresent().map(Object::toString).orElse("none")),Map.entry("retainedCount",status.retainedCount())));
            facts.put("statusMeaning","Latest complete original-task historical status receipt after the failed rollback; not a new remote measurement or diagnostic permission");
            facts.put("diagnosticEffectPolicy","Read-only fixed checks; one attempt per pending intent; this invocation remains UNKNOWN and cannot acknowledge, clean, finish or archive");
        } else {
            facts.put("programVersion",program.version());facts.put("programSourceSha256",DeploymentAgentPublicationRecoveryInstallation.hash(program.source()));
        }
        facts.put("resultState","UNKNOWN_FOR_THIS_PENDING_REQUEST");facts.put("freshHostGrant","STILL_REQUIRED_AFTER_INDEPENDENT_APP");
        if(value.plan!=null) {
            var component=value.plan.components().stream().filter(c->c.unit().equals(value.unit.id())).findFirst().orElseThrow();
            facts.put("component",component.id());facts.put("candidate",component.candidate());facts.put("expectedReleaseSha256",component.release());
            facts.put("initialStatus",Map.of("current",component.initial().current().orElse("none"),"unitPresent",component.initial().unitPresent(),
                "maintenance",component.initial().maintenance(),"candidate",component.initial().candidate(),"retainedCount",component.initial().retainedCount()));
            facts.put("pendingAckPolicy","UNKNOWN modification is never resent; later approved status may prove its goal without inventing its ACK");
        }
        if(value.reconsideration!=null) {
            var current=Map.of("originalPublicationCompensation",Map.copyOf(facts),"publicationReconsideration",value.reconsideration.facts());
            try {value.evidence=DeploymentAgentPublicationRecoveryInstallation.hash(JSON.writeValueAsString(current));}catch(Exception invalid){throw stop();}
            return current;
        }
        try {value.evidence=DeploymentAgentPublicationRecoveryInstallation.hash(JSON.writeValueAsString(facts));}catch(Exception invalid){throw stop();}
        return Map.of("originalPublicationCompensation",Map.copyOf(facts));
    }

    @Override public String reviewInstructions(){return pending!=null&&pending.reconsiderationRequest!=null
        ? "Independently review the complete fixed correction launcher/stdin, exact targets, immutable denied request and original pending/continuation history, recorded one-time human question and exact answer, and the newer independently reviewed whole graph. The original denial reason is only known to the extent actually retained; never invent its missing body. Human confirmation permits a fresh review only and supplies neither an APP verdict nor a host grant. A new independent APP verdict and exact fresh single-use grant are mandatory. Every result remains UNKNOWN; old DENY/NOT_SENT/ACK records are immutable and no cleanup or settlement follows automatically."
        : pending!=null&&pending.correctionRequest!=null
        ? "Independently review this new fixed current-state correction, complete launcher/stdin, exact targets, complete immutable OBSERVED three-native-contract followup and original FAILED/UNKNOWN pending/continuation receipts. It is a new bounded guarded first rollback, not a replay or rewritten ACK; both locks, six historical fingerprints and three native contracts must be rechecked before mutation. REQUEST consumes its one attempt for the original pending/continuation pair even after denial or interruption. Every result leaves this invocation UNKNOWN; a later separately reviewed whole-graph goal, each separately approved candidate cleanup and final whole-graph verification are still required for FAILED settlement. No installation or old action replay is authorized; a fresh exact host grant remains necessary after APP."
        : pending!=null&&pending.followupRequest!=null
        ? "Independently review this new fixed read-only contract followup launcher and complete stdin, exact targets, the complete immutable old UNKNOWN/CONTROL/UNCONFIRMED diagnostic receipt, original FAILED/UNKNOWN rollback and continuation, and the newer whole-graph unmet goal. This does not retry or rewrite the old diagnostic, mutation, source version or ACK. REQUEST consumes its one attempt even after denial or interruption. Every result leaves this invocation UNKNOWN; no mutation, installation, cleanup or settlement is authorized. A fresh exact host grant remains necessary after independent APP."
        : pending!=null&&pending.contractRequest!=null
        ? "Independently review the complete fixed read-only native-contract diagnostic launcher and stdin, exact targets, original FAILED/UNKNOWN rollback, complete unknown continuation receipt and later unmet-goal whole-graph status. Six fingerprints are historical bound expectations, not new observations. FULL_PARAMETER_VALIDATION, MANAGED_DATA_LINKS and UNIT_RENDER_MATCH are real read-only guard checks; no result supplies mutation or settlement authority. This one invocation remains UNKNOWN even after all three PASS. It cannot replay, install, clean, replace an ACK or settle. Every dispatch still requires an independent APP decision and a fresh exact single-use host grant."
        : pending!=null&&pending.continuationRequest!=null
        ? "Review the complete fixed current-state continuation launcher and stdin, exact targets, original immutable FAILED/UNKNOWN intent/result, verified v2 receipt and six fingerprints. This is a new bounded mutation under freshly checked current state, not authorization to replay the old command. Full native guard validation is required even though the read-only preflight intrinsically left three checks UNKNOWN. Every action requires an independent APP decision and a fresh exact host grant. The original ACK remains unchanged; this new receipt never settles it. Only a newer independently reviewed whole-graph status may prove its goal before independently reviewed cleanups and final FAILED settlement."
        : pending!=null&&pending.preflightRequest!=null
        ? "The fixed v2 preflight is bound to the complete original v1 ENVIRONMENT failure and original FAILED/UNKNOWN rollback. Review the complete actual CommandEvidence launcher and stdin, exact targets, old receipt and new source identity independently. The Bash prelude checks SDK exact /dev/null sentinels and refuses LD key presence before Python. After removing only those two sentinels, isolated Python completely classifies its bounded environment and refuses exported-function presence or incomplete classification before diagnostic guards. Only fixed categories are reported. Intrinsic incomplete checks remain UNKNOWN. No receipt authorizes a mutation, ACK replacement, cleanup or settlement. Independent APP and a fresh single-use exact host grant are required; this invocation returns UNKNOWN."
        : pending!=null&&pending.diagnosticRequest!=null
        ? "The product-owned facts bind one fixed read-only diagnostic of this original FAILED/UNKNOWN rollback. Independently review the complete actual CommandEvidence executable input and exact targets; these facts never authorize dispatch. FULL_PARAMETER_VALIDATION, UNIT_RENDER_MATCH and MANAGED_DATA_LINKS are explicitly UNKNOWN, not waived guard checks. The expected sourceVersion is the product's SHA-256 of actual stdin, not an independent remote measurement. This invocation remains UNKNOWN after any receipt and cannot retry a mutation, clean, finish or archive. Independent APP and a fresh single-use exact host grant remain required."
        : "The product-owned originalPublicationCompensation facts bind this exact original task and fixed request, without authorizing it or asserting its pending result. Review complete CommandEvidence source/targets independently. Every action still needs an independent APP decision and a fresh single-use host grant. Only native DAEMON first partial publication is eligible; persistent data/root absence is not claimed. An unknown modification cannot be retried.";}

    @Override public void reviewed(ActionIntent intent,CommandRequest request,TaskContext supplied,DeploymentReviewDecision decision)throws ApiException {
        verify(intent,request,supplied);
        pending.decision=decision;
        if(pending.correction!=null)try {
            pending.correction.reviewed(decision,pending.evidence);
            if(decision.decision().equals("DENY"))
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentReviewDenied(
                    original,task,"COMMAND",DeploymentAgentInputs.hash(intent.exactAction()),decision.reason());
        }catch(Exception invalid){throw stop();}
        if(pending.reconsideration!=null)try {
            pending.reconsideration.reviewed(decision,pending.evidence);
            if(decision.decision().equals("DENY"))
                gold.debug.windowstolinux.mod.deployment.service.feedback.DeploymentDiagnostics.agentReviewDenied(
                    original,task,"COMMAND",DeploymentAgentInputs.hash(intent.exactAction()),decision.reason());
        }catch(Exception invalid){throw stop();}
        if(pending.contractFollowup!=null)try {pending.contractFollowup.reviewed(decision,pending.evidence);}catch(Exception invalid){throw stop();}
        if(pending.contractDiagnostic!=null)try {pending.contractDiagnostic.reviewed(decision,pending.evidence);}catch(Exception invalid){throw stop();}
        if(pending.continuation!=null)try {pending.continuation.reviewed(decision,pending.evidence);}catch(Exception invalid){throw stop();}
        if(pending.preflight!=null)try {pending.preflight.reviewed(decision,pending.evidence);}catch(Exception invalid){throw stop();}
        if(pending.diagnostic!=null)try {pending.diagnostic.reviewed(decision,pending.evidence);}catch(Exception invalid){throw stop();}
        if(pending.installation!=null)try {pending.installation.reviewed(pending.action,decision,pending.evidence);}catch(Exception invalid){throw stop();}
    }

    @Override public void verify(ActionIntent intent,CommandRequest request,TaskContext supplied)throws ApiException {
        try {
            require(supplied==task&&pending!=null&&pending.intent.equals(intent)&&pending.requestDigest.equals(DeploymentAgentPublicationRecoveryInstallation.requestDigest(request)),"Actual recovery request changed");
            checkOriginal();
            var correctionEvents=pending.correctionRequest==null?null:List.copyOf(journal.events(task));
            var reconsiderationEvents=pending.reconsiderationRequest==null?null:List.copyOf(journal.events(task));
            DeploymentAgentPublicationCompensation.State current=null;
            if(pending.plan!=null)current=DeploymentAgentPublicationCompensation.requireCurrent(original,task,journal,pending.plan);
            if(pending.reconsiderationRequest!=null) {
                require(current!=null&&reconsiderationEvents.equals(journal.events(task)),"Reconsideration history changed across current-state validation");
                pending.reconsideration.verifyCurrent(current,reconsiderationEvents);
                var fixed=new DeploymentAgentPublicationCorrectionProgram(pending.unit.layout());
                try(var expected=fixed.request(pending.reconsiderationRequest.binding(),pending.action)) {
                    require(expected.resourceTargets().equals(intent.semantics().orElseThrow().targets())
                        &&pending.operation.equals(DeploymentAgentPublicationCorrectionProgram.OPERATION)
                        &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()).equals(pending.requestDigest),"Actual fixed reconsideration request changed");
                }
                require(reconsiderationEvents.equals(journal.events(task)),"Reconsideration history changed during request verification");
            } else if(pending.correctionRequest!=null) {
                require(current!=null&&correctionEvents.equals(journal.events(task)),"Correction history changed across current-state validation");
                pending.correction.verifyCurrent(current,correctionEvents);
                var fixed=new DeploymentAgentPublicationCorrectionProgram(pending.unit.layout());
                try(var expected=fixed.request(pending.correctionRequest.binding(),pending.action)) {
                    require(expected.resourceTargets().equals(intent.semantics().orElseThrow().targets())
                        &&pending.operation.equals(DeploymentAgentPublicationCorrectionProgram.OPERATION)
                        &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()).equals(pending.requestDigest),"Actual fixed correction request changed");
                }
                require(correctionEvents.equals(journal.events(task)),"Correction history changed during request verification");
            } else if(pending.followupRequest!=null) {
                pending.contractFollowup.verifyCurrent();
                var fixed=new DeploymentAgentPublicationContractDiagnosticFollowupProgram(pending.unit.layout());
                try(var expected=fixed.request(pending.followupRequest.binding(),pending.action)) {
                    require(expected.resourceTargets().equals(intent.semantics().orElseThrow().targets())
                        &&pending.operation.equals(DeploymentAgentPublicationContractDiagnosticFollowupProgram.OPERATION)
                        &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()).equals(pending.requestDigest),"Actual fixed contract followup request changed");
                }
            } else if(pending.contractRequest!=null) {
                pending.contractDiagnostic.verifyCurrent();
                var fixed=new DeploymentAgentPublicationContractDiagnosticProgram(pending.unit.layout());
                try(var expected=fixed.request(pending.contractRequest.binding(),pending.action)) {
                    require(expected.resourceTargets().equals(intent.semantics().orElseThrow().targets())
                        &&pending.operation.equals(DeploymentAgentPublicationContractDiagnosticProgram.OPERATION)
                        &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()).equals(pending.requestDigest),"Actual fixed contract diagnostic request changed");
                }
            } else if(pending.continuationRequest!=null) {
                pending.continuation.verifyCurrent();
                var fixed=new DeploymentAgentPublicationContinuationProgram(pending.unit.layout());
                try(var expected=fixed.request(pending.continuationRequest.binding(),pending.action)) {
                    require(expected.resourceTargets().equals(intent.semantics().orElseThrow().targets())&&pending.operation.equals(DeploymentAgentPublicationContinuationProgram.OPERATION)
                        &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()).equals(pending.requestDigest),"Actual current-state continuation request changed");
                }
            } else if(pending.preflightRequest!=null) {
                pending.preflight.verifyCurrent();
                var preflightProgram=new DeploymentAgentPublicationRecoveryPreflightProgram(pending.unit.layout());
                try(var expected=preflightProgram.request(pending.preflightRequest.binding(),pending.action)) {
                    require(expected.resourceTargets().equals(intent.semantics().orElseThrow().targets())
                        &&pending.operation.equals(DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION)
                        &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()).equals(pending.requestDigest),"Actual fixed preflight request changed");
                }
            } else if(pending.diagnosticRequest!=null) {
                pending.diagnostic.verifyCurrent();
                var diagnosticProgram=new DeploymentAgentPublicationRecoveryDiagnosticProgram(pending.unit.layout());
                try(var expected=diagnosticProgram.request(pending.diagnosticRequest.binding(),pending.action)) {
                    require(expected.resourceTargets().equals(intent.semantics().orElseThrow().targets())
                        &&pending.operation.equals(DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION)
                        &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()).equals(pending.requestDigest),"Actual fixed diagnostic request changed");
                }
            } else if(pending.programRequest!=null) {
                try(var expected=pending.operation.equals(DeploymentAgentPublicationRecoveryProgram.READINESS)?program.readiness(pending.unit,task.id(),pending.action)
                    :pending.operation.equals(DeploymentAgentPublicationRecoveryProgram.INSTALL)?program.install(pending.unit,task.id(),pending.action)
                    :program.guardedFirstRollback(pending.unit,task.id(),pending.plan.execution(),pending.programRequest.rollback().orElseThrow().candidate(),pending.programRequest.rollback().orElseThrow().expectedRelease(),pending.plan.sha256(),pending.action)) {
                    require(expected.operation().equals(pending.operation)&&expected.resourceTargets().equals(intent.semantics().orElseThrow().targets())
                        &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()).equals(pending.requestDigest),"Actual fixed program builder binding changed");
                }
            } else {
                var component=pending.plan.components().stream().filter(c->c.unit().equals(pending.unit.id())).findFirst().orElseThrow();
                try(var expected=cleanup(pending.unit,component.candidate())) {
                    require(cleanupTargets(pending.unit,component.candidate()).equals(intent.semantics().orElseThrow().targets())
                        &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected).equals(pending.requestDigest),"Actual fixed cleanup builder binding changed");
                }
            }
        }catch(Exception invalid){throw stop();}
    }

    private void checkOriginal()throws Exception {
        var events=journal.events(task);var current=journal.find(task).orElseThrow();var formal=execution();
        require(current.identity().equals(saved.identity())&&current.executionSemantics()==saved.executionSemantics()&&events.size()>=origin.size()&&events.subList(0,origin.size()).equals(origin),"Original compensation evidence changed");
        var immutableTypes=Set.of("SOURCE","PLAN","PLAN_APPLICATION","TARGET_PROFILE","REMOTE_TARGET","REMOTE_LAYOUT","CANDIDATE","CANDIDATE_QUOTA","WORKSPACE_QUOTA",
            "CURRENT_SOURCE","SEAL_EXECUTING","SEAL_RESULT","SEALED_ARTIFACT","SEALED_TOOLS","ARTIFACT_INVALIDATED","SNAPSHOT_EXECUTING","SNAPSHOT_RESULT",
            "PUBLISH_EXECUTING","PUBLISH_RESULT","DELIVERY_EXECUTING","DELIVERY_RESULT","PATCH_EXECUTING","PATCH_RESULT","COMMAND_EXECUTING","CAPABILITY_EXECUTING");
        require(events.stream().filter(e->immutableTypes.contains(e.type())).toList().equals(origin.stream().filter(e->immutableTypes.contains(e.type())).toList()),"Original source/plan/candidate/publication interval changed");
        var template=new DeploymentExecutionStore(original.db()).publicationTemplate(formal).orElseThrow();
        DeploymentAgentPublicationCompensation.requireInitial(task.id(),current,formal,template,origin);
        require(formal.phase().equals("EXECUTING")&&formal.outcome().filter(v->v!=TaskResult.UNKNOWN).isEmpty()&&template.equals(new DeploymentPublishProposal(proposal.graph(),proposal.units())),"Original compensation formal proposal changed");
        require(DeploymentAgentInputs.targetDigest(server,session).equals(saved.identity().targetDigest()),"Original compensation target changed");
        var profiles=origin.stream().filter(e->e.type().equals("TARGET_PROFILE")).map(Event::detail).distinct().toList();
        require(profiles.size()==1&&profiles.getFirst().equals(DeploymentAgentInputs.targetProfile(original.servers().get(server,task))),"Original compensation profile changed");
        DeploymentFixedCommandApproval.requireRoute(original,task,revision);
        var command=original.tasks().findCheckpoint(task,gold.debug.windowstolinux.mod.deployment.linux.command.DeploymentModelCommandRunner.KEY);
        require(command.isEmpty()||command.orElseThrow().snapshot().released(),"Original model command remains unresolved");
        var layout=proposal.graph().components().getFirst().unit().layout();
        require(!gold.debug.windowstolinux.mod.deployment.service.agent.toolchain.DeploymentAgentToolchainProgramState.hasUnconfirmed(original.tasks(),task,server.id(),layout,events),"Original controller remains unresolved");
        for(var event:events)if(Set.of("AGENT_DATABASE_INDEX","AGENT_SECRET_INDEX").contains(event.type())) {
            var child=original.tasks().findCheckpoint(task,event.action());require(child.isEmpty()||child.orElseThrow().snapshot().released(),"Original indexed resource remains unresolved");
        }
    }

    private DeploymentExecutionRecord execution()throws ApiException {
        try{return new DeploymentExecutionStore(original.db()).findForTask(task).orElseThrow();}catch(Exception unavailable){throw stop();}
    }
    private CommandRequest cleanup(DeploymentUnitIdentity unit,String candidate) {
        String command=java.util.stream.Stream.of(unit.layout().helperPath(),"candidate-cleanup-task",unit.id(),candidate,task.id().toString())
            .map(DeploymentBuildCommandChannel::shellQuote).collect(java.util.stream.Collectors.joining(" "));
        return new CommandRequest(DeploymentManagedHelperBundle.verifiedCommand(command,unit.layout()),new byte[0],Duration.ofSeconds(90),4096);
    }
    private Set<ActionTarget> cleanupTargets(DeploymentUnitIdentity unit,String candidate){return Set.of(new ActionTarget(server.id(),"task",task.id().toString()),
        new ActionTarget(server.id(),"application",unit.id()),new ActionTarget(server.id(),"deployment-release",unit.releaseRoot()),
        new ActionTarget(server.id(),"deployment-build",unit.layout().candidateRoot(candidate)),new ActionTarget(server.id(),"deployment-unit",unit.systemdUnit()));}
    private static ApiException stop(){return new ApiException(new Problem(ProblemCode.ACCESS_DENIED,"DEPLOYMENT_AGENT_COMPENSATION_BINDING_CHANGED","Original compensation binding is unavailable",Map.of(),Optional.empty()));}
    private static void require(boolean condition,String message)throws SQLException{if(!condition)throw new SQLException(message);}
}
