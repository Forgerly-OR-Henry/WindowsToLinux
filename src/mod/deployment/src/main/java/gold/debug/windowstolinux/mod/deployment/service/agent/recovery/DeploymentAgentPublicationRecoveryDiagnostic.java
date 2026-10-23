package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;

import java.sql.SQLException;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog.Event;

/** One read-only diagnostic attempt per original failed rollback intent. No ACK, goal or settlement is created. */
public final class DeploymentAgentPublicationRecoveryDiagnostic {
    public static final String EVENT = "PUBLICATION_RECOVERY_DIAGNOSTIC";
    private static final Set<String> COMMON = Set.of("formatVersion", "kind", "task", "plan", "pendingIntent", "pendingIntentSha256", "action", "history");
    private final TaskContext task;
    private final DeploymentAgentActionLog journal;
    private final DeploymentAgentPublicationCompensation.DiagnosticEligibility eligible;
    private final UUID action;

    private DeploymentAgentPublicationRecoveryDiagnostic(TaskContext task, DeploymentAgentActionLog journal,
        DeploymentAgentPublicationCompensation.DiagnosticEligibility eligible, UUID action) {
        this.task=task;this.journal=journal;this.eligible=eligible;this.action=action;
    }

    public static boolean attempted(List<Event> events, DeploymentAgentPublicationCompensation.Intent pending) throws Exception {
        for (var event:events) if (event.type().equals(EVENT)) {
            var node=parsed(event.detail());
            if (number(node,"pendingIntent")==pending.event()) return true;
        }
        return false;
    }

    public static DeploymentAgentPublicationRecoveryDiagnostic open(TaskContext task, DeploymentAgentActionLog journal,
        DeploymentAgentPublicationCompensation.DiagnosticEligibility eligible,
        DeploymentAgentPublicationRecoveryDiagnosticProgram.Request request, ActionIntent intent) throws Exception {
        var events=journal.events(task);
        require(task.id().equals(eligible.plan().task())&&!attempted(events,eligible.pending()),"Diagnostic already attempted or foreign task");
        var value=new DeploymentAgentPublicationRecoveryDiagnostic(task,journal,eligible,request.action());
        var node=value.frame("REQUEST").put("operation",DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION)
            .put("reviewRevision",eligible.plan().reviewRevision()).put("sourceVersion",new DeploymentAgentPublicationRecoveryDiagnosticProgram(layout(events,eligible.plan())).sourceVersion())
            .put("request",DeploymentAgentPublicationRecoveryInstallation.requestDigest(request.command()))
            .put("exactAction",hash(intent.exactAction())).put("targets",DeploymentAgentPublicationRecoveryInstallation.targetsDigest(request.resourceTargets()))
            .put("observationEnd",eligible.observationEnd()).put("observation",eligible.observationSha256()).put("prefixEnd",events.getLast().id());
        node.set("binding",binding(request.binding()));
        require(intent.revision().equals(eligible.plan().sha256())&&intent.serverIds().equals(List.of(eligible.plan().server()))
            &&intent.semantics().orElseThrow().operation().equals(DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION)
            &&intent.semantics().orElseThrow().targets().equals(request.resourceTargets()),"Diagnostic action binding changed");
        value.append(node);return value;
    }

    public void reviewed(DeploymentReviewDecision decision,String evidence) throws Exception {
        append(frame("REVIEW").put("decision",decision.decision()).put("confirmation",decision.requiresUserConfirmation())
            .put("reason",hash(decision.reason())).put("evidence",evidence).put("rememberAuthorization",false));
    }
    public void granted(ApprovalGrant grant) throws Exception {append(frame("GRANT").put("grant",grant.id().toString()));}
    public void dispatch() throws Exception {append(frame("DISPATCH"));}

    public void verifyCurrent() throws Exception {
        var events=journal.events(task);var state=DeploymentAgentPublicationCompensation.read(events,task.id());
        require(DeploymentAgentPublicationCompensation.diagnosticEligibility(state,events).filter(eligible::equals).isPresent(),
            "Diagnostic original pending intent or latest observation changed");
    }

    /** Retains actual transport classification/digest even when the strict safe-receipt parser refuses it. */
    public void result(OperationResult<CommandResult> actual,DeploymentAgentPublicationRecoveryDiagnosticProgram.Receipt receipt) throws Exception {
        String safe=receipt==null?null:receipt.canonicalJson();
        require(safe==null||safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536,"Diagnostic safe receipt exceeds bound");
        if(safe!=null) {
            var program=new DeploymentAgentPublicationRecoveryDiagnosticProgram(layout(journal.events(task),eligible.plan()));
            try(var expected=program.request(binding(eligible),action)) {
                require(program.parse(expected,actual).canonicalJson().equals(safe),"Diagnostic receipt does not match its actual transport");
            }
        }
        int count=safe==null?0:(safe.length()+2047)/2048;
        append(frame("RESULT").put("observedOutcome",actual.outcome().name()).put("actual",DeploymentAgentPublicationRecoveryInstallation.resultDigest(actual))
            .put("fixedReceiptVerified",receipt!=null).put("receipt",safe==null?"none":hash(safe)).put("parts",count));
        for(int part=0;part<count;part++)append(frame("RECEIPT").put("index",part).put("text",safe.substring(part*2048,Math.min(safe.length(),(part+1)*2048))));
        append(frame("END"));
    }

    /** A missing review/grant/send/result is retained; no request is automatically repeated. */
    public void retainInterrupted() throws Exception {
        var block=journal.events(task).stream().filter(e->e.type().equals(EVENT)&&e.action().equals(action.toString())).toList();
        if(block.stream().anyMatch(e->{try{return Set.of("RESULT","NOT_SENT","INTERRUPTED","END").contains(string(parsed(e.detail()),"kind"));}catch(Exception invalid){throw new IllegalStateException(invalid);}}))return;
        boolean dispatched=block.stream().anyMatch(e->{try{return string(parsed(e.detail()),"kind").equals("DISPATCH");}catch(Exception invalid){throw new IllegalStateException(invalid);}});
        append(frame(dispatched?"INTERRUPTED":"NOT_SENT"));
    }

    private ObjectNode frame(String kind) {return object().put("formatVersion",1).put("kind",kind).put("task",task.id().toString())
        .put("plan",eligible.plan().sha256()).put("pendingIntent",eligible.pending().event()).put("pendingIntentSha256",hash(eligible.pending().frame())).put("action",action.toString());}
    private void append(ObjectNode node) throws Exception {
        var events=journal.events(task);node.put("history",DeploymentAgentPublicationObservation.prefixSha256(events));String text=encoded(node);
        var proposed=new ArrayList<Event>(events);proposed.add(new Event(events.getLast().id()+1,EVENT,action.toString(),text));
        DeploymentAgentPublicationCompensation.read(proposed,task.id());
        require(events.equals(journal.events(task)),"Diagnostic history changed before append");journal.event(task,EVENT,action.toString(),text);
    }

    /** Validates only this contiguous diagnostic block; partial read-only attempts retain the original pending state. */
    static int validateBlock(List<Event> events,int start,DeploymentAgentPublicationCompensation.DiagnosticEligibility eligible) throws Exception {
        var plan=eligible.plan();var pending=eligible.pending();var first=events.get(start);var request=parsed(first.detail());
        fields(request,extra("operation","reviewRevision","sourceVersion","request","exactAction","targets","observationEnd","observation","prefixEnd","binding"));
        UUID action=uuid(request,"action");
        require(string(request,"kind").equals("REQUEST")&&string(request,"operation").equals(DeploymentAgentPublicationRecoveryDiagnosticProgram.OPERATION)
            &&string(request,"reviewRevision").equals(plan.reviewRevision())&&number(request,"observationEnd")==eligible.observationEnd()
            &&digest(request,"observation").equals(eligible.observationSha256())&&number(request,"prefixEnd")==events.get(start-1).id(),"Diagnostic request is not bound to the original latest observation");
        var before=events.subList(0,start);rejectReuse(before,action,null);
        var program=new DeploymentAgentPublicationRecoveryDiagnosticProgram(layout(before,plan));
        try(var expected=program.request(binding(eligible),action)) {
            require(request.path("binding").toString().equals(binding(expected.binding()).toString())&&digest(request,"sourceVersion").equals(program.sourceVersion())
                &&digest(request,"request").equals(DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()))
                &&digest(request,"targets").equals(DeploymentAgentPublicationRecoveryInstallation.targetsDigest(expected.resourceTargets()))
                &&digest(request,"exactAction").equals(hash(RemoteActions.command(new ServerId(plan.server()),plan.sha256(),expected.command()).exactAction())),"Diagnostic source, version, target or request changed");
            String phase="REQUEST",decision=null,receiptSha=null;int parts=0,index=0;StringBuilder receipt=new StringBuilder();boolean valid=false;
            int cursor=start;
            for(;cursor<events.size()&&events.get(cursor).type().equals(EVENT);cursor++) {
                var event=events.get(cursor);var node=parsed(event.detail());
                require(event.action().equals(action.toString())&&uuid(node,"action").equals(action)&&uuid(node,"task").equals(plan.task())
                    &&digest(node,"plan").equals(plan.sha256())&&number(node,"pendingIntent")==pending.event()
                    &&digest(node,"pendingIntentSha256").equals(hash(pending.frame()))
                    &&digest(node,"history").equals(DeploymentAgentPublicationObservation.prefixSha256(events.subList(0,cursor))),"Diagnostic identity or history changed");
                String kind=string(node,"kind");
                if(cursor==start){require(kind.equals("REQUEST"),"Diagnostic request missing");continue;}
                switch(kind) {
                    case "REVIEW" -> {
                        fields(node,extra("decision","confirmation","reason","evidence","rememberAuthorization"));
                        require(phase.equals("REQUEST")&&Set.of("APPROVE","DENY").contains(string(node,"decision"))
                            &&node.path("confirmation").isBoolean()&&node.path("rememberAuthorization").isBoolean()&&!node.path("rememberAuthorization").booleanValue(),"Diagnostic review is duplicated or invalid");
                        digest(node,"reason");digest(node,"evidence");decision=string(node,"decision");phase="REVIEW";
                    }
                    case "GRANT" -> {
                        fields(node,extra("grant"));require(phase.equals("REVIEW")&&"APPROVE".equals(decision),"Diagnostic grant lacks independent approval");
                        rejectReuse(events.subList(0,cursor),null,uuid(node,"grant"));phase="GRANT";
                    }
                    case "DISPATCH" -> {fields(node,COMMON);require(phase.equals("GRANT"),"Diagnostic dispatch lacks a fresh grant");phase="DISPATCH";}
                    case "NOT_SENT" -> {fields(node,COMMON);require(Set.of("REQUEST","REVIEW","GRANT").contains(phase),"Diagnostic NOT_SENT contradicts dispatch");phase="TERMINAL";}
                    case "INTERRUPTED" -> {fields(node,COMMON);require(phase.equals("DISPATCH"),"Diagnostic UNKNOWN has no dispatch");phase="TERMINAL";}
                    case "RESULT" -> {
                        fields(node,extra("observedOutcome","actual","fixedReceiptVerified","receipt","parts"));
                        require(phase.equals("DISPATCH")&&Set.of("SUCCEEDED","FAILED","CANCELLED","UNKNOWN").contains(string(node,"observedOutcome"))&&node.path("fixedReceiptVerified").isBoolean(),"Diagnostic result is unbound or duplicated");
                        digest(node,"actual");valid=node.path("fixedReceiptVerified").booleanValue();parts=nonnegative(node,"parts");receiptSha=string(node,"receipt");
                        require(parts<=32&&(valid?parts>0&&string(node,"observedOutcome").equals("SUCCEEDED")&&receiptSha.matches("[0-9a-f]{64}"):parts==0&&receiptSha.equals("none")),"Diagnostic receipt classification changed");phase="RESULT";
                    }
                    case "RECEIPT" -> {
                        fields(node,extra("index","text"));require(phase.equals("RESULT")&&valid&&nonnegative(node,"index")==index&&index<parts,"Diagnostic safe receipt order changed");
                        String text=string(node,"text");require(!text.isEmpty()&&text.length()<=2048,"Diagnostic safe receipt chunk exceeds bound");receipt.append(text);index++;
                    }
                    case "END" -> {
                        fields(node,COMMON);require(phase.equals("RESULT")&&index==parts,"Diagnostic safe receipt is incomplete");
                        if(valid){require(hash(receipt.toString()).equals(receiptSha),"Diagnostic safe receipt digest changed");program.validateReceipt(expected,receipt.toString());}
                        phase="TERMINAL";
                    }
                    default -> throw new SQLException("Unexpected diagnostic frame kind");
                }
            }
            return cursor;
        }
    }

    static DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding binding(DeploymentAgentPublicationCompensation.DiagnosticEligibility eligible) {
        var p=eligible.plan();var a=eligible.pending();var c=eligible.component();
        try {return new DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding(p.server(),p.task(),p.execution(),a.actionId(),a.event(),hash(a.frame()),p.sha256(),c.unit(),c.owner(),c.candidate(),c.release(),string(parse(p.header()),"layout"));}
        catch(SQLException invalid){throw new IllegalArgumentException(invalid);}
    }
    private static ObjectNode binding(DeploymentAgentPublicationRecoveryDiagnosticProgram.Binding b) {
        return object().put("server",b.server()).put("task",b.task().toString()).put("execution",b.execution().toString()).put("pendingAction",b.pendingAction().toString())
            .put("pendingIntentEvent",b.pendingIntentEvent()).put("pendingIntentSha256",b.pendingIntentSha256()).put("planSha256",b.planSha256()).put("unit",b.unit())
            .put("ownerSha256",b.ownerSha256()).put("candidate",b.candidate()).put("releaseSha256",b.releaseSha256()).put("layoutSha256",b.layoutSha256());
    }
    private static DeploymentRemoteLayout layout(List<Event> events,DeploymentAgentPublicationCompensation.Plan plan) throws Exception {
        var selected=events.stream().filter(e->e.type().equals("REMOTE_LAYOUT")).toList();require(selected.size()==1,"Diagnostic original layout is missing or duplicated");
        var event=selected.getFirst();var node=parsed(event.detail());fields(node,Set.of("formatVersion","task","server","layout","layoutSha256"));
        var layout=DeploymentRemoteLayout.decode(node.path("layout").toString());
        require(event.action().equals(plan.task().toString())&&uuid(node,"task").equals(plan.task())&&string(node,"server").equals(plan.server())
            &&digest(node,"layoutSha256").equals(layout.digest())&&string(parse(plan.header()),"layout").equals(layout.digest()),"Diagnostic original layout changed");return layout;
    }
    private static void rejectReuse(List<Event> events,UUID action,UUID grant) throws Exception {
        for(var event:events)if(Set.of(EVENT,DeploymentAgentPublicationCompensation.INTENT,DeploymentAgentPublicationRecoveryInstallation.EVENT).contains(event.type())) {
            var node=parsed(event.detail());
            if(action!=null)for(String key:List.of("action","actionId"))if(node.path(key).isTextual())require(!node.path(key).textValue().equals(action.toString()),"Diagnostic action ID was reused");
            if(grant!=null&&node.path("grant").isTextual())require(!node.path("grant").textValue().equals(grant.toString()),"Diagnostic grant was reused");
        }
    }
    private static Set<String> extra(String...names){var fields=new HashSet<String>(COMMON);fields.addAll(List.of(names));return Set.copyOf(fields);}
    private static ObjectNode parsed(String raw)throws SQLException {require(raw.length()<=4096,"Diagnostic frame exceeds character bound");var node=parse(raw,16384);require(node.path("formatVersion").isInt()&&node.path("formatVersion").intValue()==1,"Unsupported diagnostic journal version");return node;}
    private static String encoded(ObjectNode node)throws SQLException {String raw=encode(node,16384);parsed(raw);return raw;}
    private static String digest(JsonNode node,String key)throws SQLException {String value=string(node,key);require(value.matches("[0-9a-f]{64}"),"Diagnostic digest required");return value;}
    private static UUID uuid(JsonNode node,String key)throws SQLException {String value=string(node,key);try{var id=UUID.fromString(value);require(id.toString().equals(value),"Canonical diagnostic UUID required");return id;}catch(IllegalArgumentException invalid){throw new SQLException("Diagnostic UUID required",invalid);}}
    private static long number(JsonNode node,String key)throws SQLException {require(node.path(key).isIntegralNumber()&&node.path(key).canConvertToLong()&&node.path(key).longValue()>0,"Diagnostic positive event required");return node.path(key).longValue();}
    private static int nonnegative(JsonNode node,String key)throws SQLException {require(node.path(key).isInt()&&node.path(key).intValue()>=0,"Diagnostic nonnegative counter required");return node.path(key).intValue();}
    private static String hash(String value){return DeploymentAgentInputs.hash(value);}
    private static void require(boolean value,String message)throws SQLException {if(!value)throw new SQLException(message);}
}
