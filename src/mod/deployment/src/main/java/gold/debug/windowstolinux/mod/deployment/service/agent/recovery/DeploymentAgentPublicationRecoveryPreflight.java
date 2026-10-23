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

/** One separately reviewed read-only preflight per complete v1 environment-failure receipt; no ACK or settlement authority. */
public final class DeploymentAgentPublicationRecoveryPreflight {
    public static final String EVENT = "PUBLICATION_RECOVERY_PREFLIGHT";
    private static final Set<String> COMMON = Set.of("formatVersion", "kind", "task", "plan", "pendingIntent", "pendingIntentSha256", "prior", "action", "history");
    private final TaskContext task;
    private final DeploymentAgentActionLog journal;
    private final Eligibility eligible;
    private final UUID action;

    private DeploymentAgentPublicationRecoveryPreflight(TaskContext task, DeploymentAgentActionLog journal,
        Eligibility eligible, UUID action) {
        this.task=task;this.journal=journal;this.eligible=eligible;this.action=action;
    }

    public record Eligibility(DeploymentAgentPublicationCompensation.DiagnosticEligibility original,
        DeploymentAgentPublicationRecoveryPreflightProgram.PriorDiagnostic prior) { }

    /** The old v1 block is validated under its original source before deriving any new preflight identity. */
    public static Optional<Eligibility> eligibility(DeploymentAgentPublicationCompensation.State state,List<Event> events)throws Exception {
        var original=DeploymentAgentPublicationCompensation.diagnosticEligibility(state,events);
        if(original.isEmpty())return Optional.empty();
        var pending=original.orElseThrow().pending();
        var requests=new ArrayList<Event>();
        for(var event:events)if(event.type().equals(DeploymentAgentPublicationRecoveryDiagnostic.EVENT)) {
            var node=parse(event.detail(),16384);
            if(string(node,"kind").equals("REQUEST")&&number(node,"pendingIntent")==pending.event())requests.add(event);
        }
        if(requests.isEmpty())return Optional.empty();
        require(requests.size()==1,"Prior diagnostic request is duplicated");var first=requests.getFirst();int index=events.indexOf(first);
        var before=events.subList(0,index);var oldState=DeploymentAgentPublicationCompensation.read(before,state.plan().task());
        var old=DeploymentAgentPublicationCompensation.diagnosticEligibility(oldState,before).orElseThrow();
        require(old.pending().equals(pending)&&old.plan().equals(state.plan()),"Prior diagnostic pending binding changed");
        int end=DeploymentAgentPublicationRecoveryDiagnostic.validateBlock(events,index,old);
        var block=events.subList(index,end);var terminal=parse(block.getLast().detail(),16384);
        if(!string(terminal,"kind").equals("END"))return Optional.empty();
        Event result=null;var receipt=new StringBuilder();
        for(var event:block){var node=parse(event.detail(),16384);String kind=string(node,"kind");
            if(kind.equals("RESULT")){require(result==null,"Prior diagnostic result is duplicated");result=event;}
            if(kind.equals("RECEIPT"))receipt.append(string(node,"text"));
        }
        if(result==null)return Optional.empty();var resultNode=parse(result.detail(),16384);
        if(!resultNode.path("fixedReceiptVerified").asBoolean(false))return Optional.empty();
        require(string(resultNode,"observedOutcome").equals("SUCCEEDED")&&hash(receipt.toString()).equals(digest(resultNode,"receipt")),"Prior diagnostic receipt changed");
        var oldProgram=new DeploymentAgentPublicationRecoveryDiagnosticProgram(layout(before,state.plan()));
        UUID action=uuid(parse(first.detail(),16384),"action");
        try(var request=oldProgram.request(DeploymentAgentPublicationRecoveryDiagnostic.binding(old),action)) {
            var safe=new com.fasterxml.jackson.databind.ObjectMapper().readTree(oldProgram.validateReceipt(request,receipt.toString()).canonicalJson());
            var environment=safe.path("checks").get(0);
            require(string(environment,"id").equals("ENVIRONMENT"),"Prior diagnostic environment check missing");
            if(!string(environment,"status").equals("FAIL")||!string(environment,"reason").equals("MISMATCH")
                ||!environment.path("flag").isBoolean()||environment.path("flag").booleanValue()||safe.path("stable").asBoolean(true))return Optional.empty();
        }
        var results=new ArrayList<Event>();
        for(var event:events)if(event.type().equals(DeploymentAgentPublicationCompensation.RESULT)&&number(parse(event.detail(),16384),"intent")==pending.event())results.add(event);
        require(results.size()==1,"Original failed rollback result changed");var failed=results.getFirst();
        var prior=new DeploymentAgentPublicationRecoveryPreflightProgram.PriorDiagnostic(action,first.id(),block.getLast().id(),hash(first.detail()),hash(receipt.toString()),failed.id(),hash(failed.detail()));
        return Optional.of(new Eligibility(original.orElseThrow(),prior));
    }

    public static boolean attempted(List<Event> events,Eligibility eligible)throws Exception {
        for(var event:events)if(event.type().equals(EVENT)) {
            var node=parsed(event.detail());
            if(digest(node,"prior").equals(hash(prior(eligible.prior()).toString())))return true;
        }
        return false;
    }

    public static DeploymentAgentPublicationRecoveryPreflight open(TaskContext task, DeploymentAgentActionLog journal,
        Eligibility eligible,
        DeploymentAgentPublicationRecoveryPreflightProgram.Request request, ActionIntent intent) throws Exception {
        var events=journal.events(task);
        require(task.id().equals(eligible.original().plan().task())&&!attempted(events,eligible),"Preflight already attempted or foreign task");
        require(eligibility(DeploymentAgentPublicationCompensation.read(events,task.id()),events).filter(eligible::equals).isPresent(),"Original environment-failure evidence changed");
        var value=new DeploymentAgentPublicationRecoveryPreflight(task,journal,eligible,request.action());
        var node=value.frame("REQUEST").put("operation",DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION)
            .put("reviewRevision",eligible.original().plan().reviewRevision()).put("sourceVersion",new DeploymentAgentPublicationRecoveryPreflightProgram(layout(events,eligible.original().plan())).sourceVersion())
            .put("request",DeploymentAgentPublicationRecoveryInstallation.requestDigest(request.command()))
            .put("exactAction",hash(intent.exactAction())).put("targets",DeploymentAgentPublicationRecoveryInstallation.targetsDigest(request.resourceTargets()))
            .put("observationEnd",eligible.original().observationEnd()).put("observation",eligible.original().observationSha256()).put("prefixEnd",events.getLast().id());
        node.set("binding",binding(request.binding()));
        require(intent.revision().equals(eligible.original().plan().sha256())&&intent.serverIds().equals(List.of(eligible.original().plan().server()))
            &&intent.semantics().orElseThrow().operation().equals(DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION)
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
        require(eligibility(state,events).filter(eligible::equals).isPresent(),
            "Diagnostic original pending intent or latest observation changed");
    }

    /** Retains actual transport classification/digest even when the strict safe-receipt parser refuses it. */
    public void result(OperationResult<CommandResult> actual,DeploymentAgentPublicationRecoveryPreflightProgram.Receipt receipt) throws Exception {
        String safe=receipt==null?null:receipt.canonicalJson();
        require(safe==null||safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536,"Diagnostic safe receipt exceeds bound");
        if(safe!=null) {
            var program=new DeploymentAgentPublicationRecoveryPreflightProgram(layout(journal.events(task),eligible.original().plan()));
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

    private ObjectNode frame(String kind) {return object().put("formatVersion",2).put("kind",kind).put("task",task.id().toString())
        .put("plan",eligible.original().plan().sha256()).put("pendingIntent",eligible.original().pending().event()).put("pendingIntentSha256",hash(eligible.original().pending().frame())).put("prior",hash(prior(eligible.prior()).toString())).put("action",action.toString());}
    private void append(ObjectNode node) throws Exception {
        var events=journal.events(task);node.put("history",DeploymentAgentPublicationObservation.prefixSha256(events));String text=encoded(node);
        var proposed=new ArrayList<Event>(events);proposed.add(new Event(events.getLast().id()+1,EVENT,action.toString(),text));
        DeploymentAgentPublicationCompensation.read(proposed,task.id());
        require(events.equals(journal.events(task)),"Diagnostic history changed before append");journal.event(task,EVENT,action.toString(),text);
    }

    /** Validates only this contiguous diagnostic block; partial read-only attempts retain the original pending state. */
    static int validateBlock(List<Event> events,int start,Eligibility eligible) throws Exception {
        var plan=eligible.original().plan();var pending=eligible.original().pending();var first=events.get(start);var request=parsed(first.detail());
        fields(request,extra("operation","reviewRevision","sourceVersion","request","exactAction","targets","observationEnd","observation","prefixEnd","binding"));
        UUID action=uuid(request,"action");
        require(string(request,"kind").equals("REQUEST")&&string(request,"operation").equals(DeploymentAgentPublicationRecoveryPreflightProgram.OPERATION)
            &&string(request,"reviewRevision").equals(plan.reviewRevision())&&number(request,"observationEnd")==eligible.original().observationEnd()
            &&digest(request,"observation").equals(eligible.original().observationSha256())&&number(request,"prefixEnd")==events.get(start-1).id(),"Diagnostic request is not bound to the original latest observation");
        var before=events.subList(0,start);rejectReuse(before,action,null);
        var program=new DeploymentAgentPublicationRecoveryPreflightProgram(layout(before,plan));
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
                    &&digest(node,"prior").equals(hash(prior(eligible.prior()).toString()))
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

    static DeploymentAgentPublicationRecoveryPreflightProgram.Binding binding(Eligibility eligible) {
        return new DeploymentAgentPublicationRecoveryPreflightProgram.Binding(DeploymentAgentPublicationRecoveryDiagnostic.binding(eligible.original()),eligible.prior());
    }
    private static ObjectNode binding(DeploymentAgentPublicationRecoveryPreflightProgram.Binding b) {
        var original=b.original();var raw=object().put("server",original.server()).put("task",original.task().toString()).put("execution",original.execution().toString()).put("pendingAction",original.pendingAction().toString())
            .put("pendingIntentEvent",original.pendingIntentEvent()).put("pendingIntentSha256",original.pendingIntentSha256()).put("planSha256",original.planSha256()).put("unit",original.unit())
            .put("ownerSha256",original.ownerSha256()).put("candidate",original.candidate()).put("releaseSha256",original.releaseSha256()).put("layoutSha256",original.layoutSha256());
        var node=object();node.set("original",raw);node.set("prior",prior(b.prior()));return node;
    }
    private static ObjectNode prior(DeploymentAgentPublicationRecoveryPreflightProgram.PriorDiagnostic p) {
        return object().put("action",p.action().toString()).put("requestEvent",p.requestEvent()).put("endEvent",p.endEvent())
            .put("requestSha256",p.requestSha256()).put("receiptSha256",p.receiptSha256()).put("pendingResultEvent",p.pendingResultEvent()).put("pendingResultSha256",p.pendingResultSha256());
    }

    private static DeploymentRemoteLayout layout(List<Event> events,DeploymentAgentPublicationCompensation.Plan plan) throws Exception {
        var selected=events.stream().filter(e->e.type().equals("REMOTE_LAYOUT")).toList();require(selected.size()==1,"Diagnostic original layout is missing or duplicated");
        var event=selected.getFirst();var node=parse(event.detail(),16384);fields(node,Set.of("formatVersion","task","server","layout","layoutSha256"));
        require(node.path("formatVersion").isInt()&&node.path("formatVersion").intValue()==1,"Original layout frame version changed");
        var layout=DeploymentRemoteLayout.decode(node.path("layout").toString());
        require(event.action().equals(plan.task().toString())&&uuid(node,"task").equals(plan.task())&&string(node,"server").equals(plan.server())
            &&digest(node,"layoutSha256").equals(layout.digest())&&string(parse(plan.header()),"layout").equals(layout.digest()),"Diagnostic original layout changed");return layout;
    }
    private static void rejectReuse(List<Event> events,UUID action,UUID grant) throws Exception {
        for(var event:events)if(Set.of(EVENT,DeploymentAgentPublicationRecoveryDiagnostic.EVENT,DeploymentAgentPublicationCompensation.INTENT,DeploymentAgentPublicationRecoveryInstallation.EVENT).contains(event.type())) {
            var node=parse(event.detail(),16384);
            if(action!=null)for(String key:List.of("action","actionId"))if(node.path(key).isTextual())require(!node.path(key).textValue().equals(action.toString()),"Diagnostic action ID was reused");
            if(grant!=null&&node.path("grant").isTextual())require(!node.path("grant").textValue().equals(grant.toString()),"Diagnostic grant was reused");
        }
    }
    /** A later legacy action must not reuse an action or grant introduced by this protocol. */
    static void rejectHistoricalReuse(List<Event> events,int start,int end)throws Exception {
        for(int i=start;i<end;i++) {
            var node=parse(events.get(i).detail(),16384);String kind=string(node,"kind");
            if(kind.equals("REQUEST"))rejectPriorPreflight(events.subList(0,i),uuid(node,"action"),null);
            if(kind.equals("GRANT"))rejectPriorPreflight(events.subList(0,i),null,uuid(node,"grant"));
        }
    }
    static void rejectPriorPreflight(List<Event> events,UUID action,UUID grant)throws Exception {
        for(var event:events)if(event.type().equals(EVENT)) {
            var node=parsed(event.detail());
            if(action!=null)require(!uuid(node,"action").equals(action),"Preflight action ID was reused by a later action");
            if(grant!=null&&node.path("grant").isTextual())require(!uuid(node,"grant").equals(grant),"Preflight grant was reused by a later action");
        }
    }
    private static Set<String> extra(String...names){var fields=new HashSet<String>(COMMON);fields.addAll(List.of(names));return Set.copyOf(fields);}
    private static ObjectNode parsed(String raw)throws SQLException {require(raw.length()<=4096,"Diagnostic frame exceeds character bound");var node=parse(raw,16384);require(node.path("formatVersion").isInt()&&node.path("formatVersion").intValue()==2,"Unsupported preflight journal version");return node;}
    private static String encoded(ObjectNode node)throws SQLException {String raw=encode(node,16384);parsed(raw);return raw;}
    private static String digest(JsonNode node,String key)throws SQLException {String value=string(node,key);require(value.matches("[0-9a-f]{64}"),"Diagnostic digest required");return value;}
    private static UUID uuid(JsonNode node,String key)throws SQLException {String value=string(node,key);try{var id=UUID.fromString(value);require(id.toString().equals(value),"Canonical diagnostic UUID required");return id;}catch(IllegalArgumentException invalid){throw new SQLException("Diagnostic UUID required",invalid);}}
    private static long number(JsonNode node,String key)throws SQLException {require(node.path(key).isIntegralNumber()&&node.path(key).canConvertToLong()&&node.path(key).longValue()>0,"Diagnostic positive event required");return node.path(key).longValue();}
    private static int nonnegative(JsonNode node,String key)throws SQLException {require(node.path(key).isInt()&&node.path(key).intValue()>=0,"Diagnostic nonnegative counter required");return node.path(key).intValue();}
    private static String hash(String value){return DeploymentAgentInputs.hash(value);}
    private static void require(boolean value,String message)throws SQLException {if(!value)throw new SQLException(message);}
}
