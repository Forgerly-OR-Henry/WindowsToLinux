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

/** One separately reviewed current-state correction per original pending/continuation pair; no ACK or settlement authority. */
public final class DeploymentAgentPublicationCorrection {
    public static final String EVENT = "PUBLICATION_CORRECTION";
    private static final Set<String> COMMON = Set.of("formatVersion", "kind", "task", "plan", "pendingIntent", "pendingIntentSha256", "prior", "continuationRequest", "action", "history");
    private final TaskContext task;
    private final DeploymentAgentActionLog journal;
    private final Eligibility eligible;
    private final UUID action;

    private DeploymentAgentPublicationCorrection(TaskContext task, DeploymentAgentActionLog journal,
        Eligibility eligible, UUID action) {
        this.task=task;this.journal=journal;this.eligible=eligible;this.action=action;
    }

    public record Eligibility(DeploymentAgentPublicationCompensation.DiagnosticEligibility original,
        DeploymentAgentPublicationCorrectionProgram.Binding binding) { }

    /** Only a complete immutable OBSERVED three-contract followup and a newer unmet-goal graph permit correction. */
    public static Optional<Eligibility> eligibility(DeploymentAgentPublicationCompensation.State state,List<Event> events)throws Exception {
        var selected=DeploymentAgentPublicationCompensation.diagnosticEligibility(state,events);
        if(selected.isEmpty())return Optional.empty();var original=selected.orElseThrow();
        var requests=new ArrayList<Event>();
        for(var event:events)if(event.type().equals(DeploymentAgentPublicationContractDiagnosticFollowup.EVENT)) {
            var node=parse(event.detail(),16384);
            if(string(node,"kind").equals("REQUEST")&&number(node,"pendingIntent")==original.pending().event())requests.add(event);
        }
        if(requests.isEmpty())return Optional.empty();require(requests.size()==1,"Original contract followup request is duplicated");
        var first=requests.getFirst();int index=events.indexOf(first);int end=index+1;
        while(end<events.size()&&events.get(end).type().equals(DeploymentAgentPublicationContractDiagnosticFollowup.EVENT))end++;
        var through=events.subList(0,end);
        // One complete public read validates the immutable old block and its nested history before restoring its typed request.
        var verified=DeploymentAgentPublicationCompensation.read(through,state.plan().task());
        require(verified.pending().equals(Optional.of(original.pending()))&&verified.plan().equals(original.plan())&&verified.verified().isEmpty(),"Original followup pending binding changed");
        var block=events.subList(index,end);
        if(!string(parse(block.getLast().detail(),16384),"kind").equals("END")||original.observationEnd()<=block.getLast().id())return Optional.empty();
        Event result=null;var receipt=new StringBuilder();
        for(var event:block){var node=parse(event.detail(),16384);String kind=string(node,"kind");
            if(kind.equals("RESULT")){require(result==null,"Original followup result duplicated");result=event;}
            if(kind.equals("RECEIPT"))receipt.append(string(node,"text"));
        }
        if(result==null)return Optional.empty();var resultNode=parse(result.detail(),16384);
        if(!resultNode.path("fixedReceiptVerified").asBoolean(false))return Optional.empty();
        require(string(resultNode,"observedOutcome").equals("SUCCEEDED")&&hash(receipt.toString()).equals(digest(resultNode,"receipt")),"Original followup receipt changed");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var firstNode=parse(first.detail(),16384);
        var priorBinding=json.treeToValue(firstNode.path("binding"),DeploymentAgentPublicationContractDiagnosticFollowupProgram.Binding.class);
        require(json.readTree(json.writeValueAsBytes(priorBinding)).equals(firstNode.path("binding")),"Original followup typed request changed");
        var program=new DeploymentAgentPublicationContractDiagnosticFollowupProgram(layout(through,state.plan()));
        UUID action=uuid(firstNode,"action");
        try(var request=program.request(priorBinding,action)) {
            var safe=json.readTree(program.validateReceipt(request,receipt.toString()).canonicalJson());
            if(!string(safe,"status").equals("OBSERVED")||!string(safe,"phase").equals("COMPLETE")||!string(safe,"reason").equals("MATCH")||!safe.path("exitCode").isInt()||safe.path("exitCode").intValue()!=0)return Optional.empty();
            require(safe.path("checks").size()==3,"Complete native contract checks are missing");
            for(var check:safe.path("checks"))if(!string(check,"status").equals("PASS")||!string(check,"reason").equals("MATCH")||!check.path("exitCode").isInt()||check.path("exitCode").intValue()!=0)return Optional.empty();
        }
        String sourceVersion=digest(parse(first.detail(),16384),"sourceVersion");require(sourceVersion.equals(program.sourceVersion()),"Original followup source version changed");
        var prior=new DeploymentAgentPublicationCorrectionProgram.PriorFollowup(action,first.id(),block.getLast().id(),hash(first.detail()),hash(receipt.toString()),sourceVersion);
        return Optional.of(new Eligibility(original,new DeploymentAgentPublicationCorrectionProgram.Binding(priorBinding,prior,original.observationEnd(),original.observationSha256())));
    }

    /** REQUEST consumes the original pending/continuation pair, independently of later versions, followups or observations. */
    public static boolean attempted(List<Event> events,Eligibility eligible)throws Exception {
        for(var event:events)if(event.type().equals(EVENT)) {
            var node=parsed(event.detail());
            if(number(node,"pendingIntent")==eligible.original().pending().event()
                &&number(node,"continuationRequest")==eligible.binding().followup().diagnostic().prior().requestEvent())return true;
        }
        return false;
    }

    public static DeploymentAgentPublicationCorrection open(TaskContext task, DeploymentAgentActionLog journal,
        Eligibility eligible,
        DeploymentAgentPublicationCorrectionProgram.Request request, ActionIntent intent) throws Exception {
        var events=journal.events(task);
        require(task.id().equals(eligible.original().plan().task())&&!attempted(events,eligible),"Publication correction already attempted or foreign task");
        require(eligibility(DeploymentAgentPublicationCompensation.read(events,task.id()),events).filter(eligible::equals).isPresent(),"Original complete followup/current-state evidence changed");
        var value=new DeploymentAgentPublicationCorrection(task,journal,eligible,request.action());
        var node=value.frame("REQUEST").put("operation",DeploymentAgentPublicationCorrectionProgram.OPERATION)
            .put("reviewRevision",eligible.original().plan().reviewRevision()).put("sourceVersion",new DeploymentAgentPublicationCorrectionProgram(layout(events,eligible.original().plan())).sourceVersion())
            .put("request",DeploymentAgentPublicationRecoveryInstallation.requestDigest(request.command()))
            .put("exactAction",hash(intent.exactAction())).put("targets",DeploymentAgentPublicationRecoveryInstallation.targetsDigest(request.resourceTargets()))
            .put("observationEnd",eligible.original().observationEnd()).put("observation",eligible.original().observationSha256()).put("prefixEnd",events.getLast().id());
        require(intent.revision().equals(eligible.original().plan().sha256())&&intent.serverIds().equals(List.of(eligible.original().plan().server()))
            &&intent.semantics().orElseThrow().operation().equals(DeploymentAgentPublicationCorrectionProgram.OPERATION)
            &&intent.semantics().orElseThrow().targets().equals(request.resourceTargets()),"Publication correction action binding changed");
        value.append(node);return value;
    }

    public void reviewed(DeploymentReviewDecision decision,String evidence) throws Exception {
        append(frame("REVIEW").put("decision",decision.decision()).put("confirmation",decision.requiresUserConfirmation())
            .put("reason",hash(decision.reason())).put("evidence",evidence).put("rememberAuthorization",false));
    }
    public void granted(ApprovalGrant grant) throws Exception {append(frame("GRANT").put("grant",grant.id().toString()));}
    public void dispatch() throws Exception {append(frame("DISPATCH"));}

    public void verifyCurrent() throws Exception {
        var events=List.copyOf(journal.events(task));var state=DeploymentAgentPublicationCompensation.read(events,task.id());
        require(events.equals(journal.events(task)),"Correction history changed during verification");
        verifyCurrent(state,events);
        require(events.equals(journal.events(task)),"Correction history changed during verification");
    }

    /** Only the current producer boundary may supply its freshly validated immutable snapshot; never retained across callbacks. */
    void verifyCurrent(DeploymentAgentPublicationCompensation.State state,List<Event> events) throws Exception {
        int first=0;while(first<events.size()&&!(events.get(first).type().equals(EVENT)&&events.get(first).action().equals(action.toString())))first++;
        require(first<events.size(),"Publication correction request disappeared");var request=parsed(events.get(first).detail());
        // read() has already rebuilt the entire strict block and immutable old codec. Compare this instance to that validated REQUEST.
        require(state.plan().equals(eligible.original().plan())&&state.pending().equals(Optional.of(eligible.original().pending()))
            &&uuid(request,"action").equals(action)&&uuid(request,"task").equals(task.id())
            &&digest(request,"plan").equals(eligible.original().plan().sha256())
            &&number(request,"pendingIntent")==eligible.original().pending().event()
            &&digest(request,"pendingIntentSha256").equals(hash(eligible.original().pending().frame()))
            &&number(request,"continuationRequest")==eligible.binding().followup().diagnostic().prior().requestEvent()
            &&digest(request,"prior").equals(hash(binding(binding(eligible)).toString()))
            &&number(request,"observationEnd")==eligible.original().observationEnd()
            &&digest(request,"observation").equals(eligible.original().observationSha256())
            &&DeploymentAgentPublicationObservation.readLatest(events,task.id()).endEvent()==eligible.original().observationEnd(),
            "Publication correction original identity, fingerprints, pending intent or observation changed");
    }

    public void result(OperationResult<CommandResult> actual,DeploymentAgentPublicationCorrectionProgram.Receipt receipt) throws Exception {
        String safe=receipt==null?null:receipt.canonicalJson();
        require(safe==null||safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536,"Publication correction safe receipt exceeds bound");
        if(safe!=null) {
            var program=new DeploymentAgentPublicationCorrectionProgram(layout(journal.events(task),eligible.original().plan()));
            try(var expected=program.request(binding(eligible),action)) {
                require(program.parse(expected,actual).canonicalJson().equals(safe),"Publication correction receipt does not match its actual transport");
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
        .put("plan",eligible.original().plan().sha256()).put("pendingIntent",eligible.original().pending().event()).put("pendingIntentSha256",hash(eligible.original().pending().frame())).put("prior",hash(binding(binding(eligible)).toString())).put("continuationRequest",eligible.binding().followup().diagnostic().prior().requestEvent()).put("action",action.toString());}
    private void append(ObjectNode node) throws Exception {
        var events=journal.events(task);node.put("history",DeploymentAgentPublicationObservation.prefixSha256(events));String text=encoded(node);
        var proposed=new ArrayList<Event>(events);proposed.add(new Event(events.getLast().id()+1,EVENT,action.toString(),text));
        DeploymentAgentPublicationCompensation.read(proposed,task.id());
        require(events.equals(journal.events(task)),"Publication correction history changed before append");journal.event(task,EVENT,action.toString(),text);
    }

    /** Validates this contiguous correction block; the full binding is rebuilt from its immutable prefix, never trusted from a projection. */
    static int validateBlock(List<Event> events,int start,Eligibility eligible) throws Exception {
        var plan=eligible.original().plan();var pending=eligible.original().pending();var first=events.get(start);var request=parsed(first.detail());
        fields(request,extra("operation","reviewRevision","sourceVersion","request","exactAction","targets","observationEnd","observation","prefixEnd"));
        UUID action=uuid(request,"action");
        require(string(request,"kind").equals("REQUEST")&&string(request,"operation").equals(DeploymentAgentPublicationCorrectionProgram.OPERATION)
            &&string(request,"reviewRevision").equals(plan.reviewRevision())&&number(request,"observationEnd")==eligible.original().observationEnd()
            &&digest(request,"observation").equals(eligible.original().observationSha256())&&number(request,"prefixEnd")==events.get(start-1).id(),"Publication correction request is not bound to the original latest observation");
        var before=events.subList(0,start);require(!attempted(before,eligible),"Publication correction already attempted");rejectReuse(before,action,null);
        var program=new DeploymentAgentPublicationCorrectionProgram(layout(before,plan));
        try(var expected=program.request(binding(eligible),action)) {
            require(digest(request,"prior").equals(hash(binding(expected.binding()).toString()))&&digest(request,"sourceVersion").equals(program.sourceVersion())
                &&digest(request,"request").equals(DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()))
                &&digest(request,"targets").equals(DeploymentAgentPublicationRecoveryInstallation.targetsDigest(expected.resourceTargets()))
                &&digest(request,"exactAction").equals(hash(RemoteActions.command(new ServerId(plan.server()),plan.sha256(),expected.command()).exactAction())),"Publication correction source, version, target or request changed");
            String phase="REQUEST",decision=null,receiptSha=null;int parts=0,index=0;StringBuilder receipt=new StringBuilder();boolean valid=false;
            int cursor=start;
            for(;cursor<events.size()&&events.get(cursor).type().equals(EVENT);cursor++) {
                var event=events.get(cursor);var node=parsed(event.detail());
                require(event.action().equals(action.toString())&&uuid(node,"action").equals(action)&&uuid(node,"task").equals(plan.task())
                    &&digest(node,"plan").equals(plan.sha256())&&number(node,"pendingIntent")==pending.event()
                    &&digest(node,"pendingIntentSha256").equals(hash(pending.frame()))
                    &&digest(node,"prior").equals(hash(binding(binding(eligible)).toString()))
                    &&number(node,"continuationRequest")==eligible.binding().followup().diagnostic().prior().requestEvent()
                    &&digest(node,"history").equals(DeploymentAgentPublicationObservation.prefixSha256(events.subList(0,cursor))),"Publication correction identity or history changed");
                String kind=string(node,"kind");
                if(cursor==start){require(kind.equals("REQUEST"),"Publication correction request missing");continue;}
                switch(kind) {
                    case "REVIEW" -> {
                        fields(node,extra("decision","confirmation","reason","evidence","rememberAuthorization"));
                        require(phase.equals("REQUEST")&&Set.of("APPROVE","DENY").contains(string(node,"decision"))
                            &&node.path("confirmation").isBoolean()&&node.path("rememberAuthorization").isBoolean()&&!node.path("rememberAuthorization").booleanValue(),"Publication correction review is duplicated or invalid");
                        digest(node,"reason");digest(node,"evidence");decision=string(node,"decision");phase="REVIEW";
                    }
                    case "GRANT" -> {
                        fields(node,extra("grant"));require(phase.equals("REVIEW")&&"APPROVE".equals(decision),"Publication correction grant lacks independent approval");
                        rejectReuse(events.subList(0,cursor),null,uuid(node,"grant"));phase="GRANT";
                    }
                    case "DISPATCH" -> {fields(node,COMMON);require(phase.equals("GRANT"),"Publication correction dispatch lacks a fresh grant");phase="DISPATCH";}
                    case "NOT_SENT" -> {fields(node,COMMON);require(Set.of("REQUEST","REVIEW","GRANT").contains(phase),"Publication correction NOT_SENT contradicts dispatch");phase="TERMINAL";}
                    case "INTERRUPTED" -> {fields(node,COMMON);require(phase.equals("DISPATCH"),"Publication correction UNKNOWN has no dispatch");phase="TERMINAL";}
                    case "RESULT" -> {
                        fields(node,extra("observedOutcome","actual","fixedReceiptVerified","receipt","parts"));
                        require(phase.equals("DISPATCH")&&Set.of("SUCCEEDED","FAILED","CANCELLED","UNKNOWN").contains(string(node,"observedOutcome"))&&node.path("fixedReceiptVerified").isBoolean(),"Publication correction result is unbound or duplicated");
                        digest(node,"actual");valid=node.path("fixedReceiptVerified").booleanValue();parts=nonnegative(node,"parts");receiptSha=string(node,"receipt");
                        require(parts<=32&&(valid?parts>0&&string(node,"observedOutcome").equals("SUCCEEDED")&&receiptSha.matches("[0-9a-f]{64}"):parts==0&&receiptSha.equals("none")),"Publication correction receipt classification changed");phase="RESULT";
                    }
                    case "RECEIPT" -> {
                        fields(node,extra("index","text"));require(phase.equals("RESULT")&&valid&&nonnegative(node,"index")==index&&index<parts,"Publication correction safe receipt order changed");
                        String text=string(node,"text");require(!text.isEmpty()&&text.length()<=2048,"Publication correction safe receipt chunk exceeds bound");receipt.append(text);index++;
                    }
                    case "END" -> {
                        fields(node,COMMON);require(phase.equals("RESULT")&&index==parts,"Publication correction safe receipt is incomplete");
                        if(valid){require(hash(receipt.toString()).equals(receiptSha),"Publication correction safe receipt digest changed");program.validateReceipt(expected,receipt.toString());}
                        phase="TERMINAL";
                    }
                    default -> throw new SQLException("Unexpected correction frame kind");
                }
            }
            return cursor;
        }
    }

    public static DeploymentAgentPublicationCorrectionProgram.Binding binding(Eligibility eligible){return eligible.binding();}
    private static ObjectNode binding(DeploymentAgentPublicationCorrectionProgram.Binding b) {
        // Text encoding is canonicalized by the public program codec; no LongNode/IntNode structural comparison is used.
        return new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(b);
    }

    private static DeploymentRemoteLayout layout(List<Event> events,DeploymentAgentPublicationCompensation.Plan plan) throws Exception {
        var selected=events.stream().filter(e->e.type().equals("REMOTE_LAYOUT")).toList();require(selected.size()==1,"Publication correction original layout is missing or duplicated");
        var event=selected.getFirst();var node=parse(event.detail(),16384);fields(node,Set.of("formatVersion","task","server","layout","layoutSha256"));
        require(node.path("formatVersion").isInt()&&node.path("formatVersion").intValue()==1,"Original layout frame version changed");
        var layout=DeploymentRemoteLayout.decode(node.path("layout").toString());
        require(event.action().equals(plan.task().toString())&&uuid(node,"task").equals(plan.task())&&string(node,"server").equals(plan.server())
            &&digest(node,"layoutSha256").equals(layout.digest())&&string(parse(plan.header()),"layout").equals(layout.digest()),"Publication correction original layout changed");return layout;
    }
    private static void rejectReuse(List<Event> events,UUID action,UUID grant)throws Exception {
        for(var event:events) {
            if(action!=null)require(!event.action().equals(action.toString()),"Publication correction action ID was reused");
            JsonNode node;try{node=parse(event.detail(),16384);}catch(SQLException nonObject){continue;}
            if(action!=null)for(String key:List.of("action","actionId"))if(node.path(key).isTextual())require(!node.path(key).textValue().equals(action.toString()),"Publication correction action ID was reused");
            if(grant!=null)for(String key:List.of("grant","grantId"))if(node.path(key).isTextual())require(!node.path(key).textValue().equals(grant.toString()),"Publication correction grant was reused");
        }
    }
    /** A later legacy action must not reuse an action or grant introduced by this protocol. */
    static void rejectHistoricalReuse(List<Event> events,int start,int end)throws Exception {
        int first=0;while(first<events.size()&&!events.get(first).type().equals(EVENT))first++;
        if(first==events.size())return;
        for(int i=Math.max(start,first+1);i<end;i++) {
            var event=events.get(i);if(event.type().equals(EVENT))continue;var before=events.subList(0,i);
            for(var prior:before)if(prior.type().equals(EVENT))require(!prior.action().equals(event.action()),"Publication correction action reused by a later event");
            JsonNode node;try{node=parse(event.detail(),16384);}catch(SQLException notObject){continue;}
            for(String field:List.of("action","actionId"))if(node.path(field).isTextual())rejectPriorCorrection(before,uuid(node,field),null);
            for(String field:List.of("grant","grantId"))if(node.path(field).isTextual())rejectPriorCorrection(before,null,uuid(node,field));
        }
    }
    static void rejectPriorCorrection(List<Event> events,UUID action,UUID grant)throws Exception {
        for(var event:events)if(event.type().equals(EVENT)) {
            var node=parsed(event.detail());
            if(action!=null)require(!uuid(node,"action").equals(action),"Publication correction action ID was reused by a later action");
            if(grant!=null&&node.path("grant").isTextual())require(!uuid(node,"grant").equals(grant),"Publication correction grant was reused by a later action");
        }
    }
    private static Set<String> extra(String...names){var fields=new HashSet<String>(COMMON);fields.addAll(List.of(names));return Set.copyOf(fields);}
    private static ObjectNode parsed(String raw)throws SQLException {require(raw.length()<=4096,"Publication correction frame exceeds character bound");var node=parse(raw,16384);require(node.path("formatVersion").isInt()&&node.path("formatVersion").intValue()==1,"Unsupported publication correction journal version");return node;}
    private static String encoded(ObjectNode node)throws SQLException {String raw=encode(node,16384);parsed(raw);return raw;}
    private static String digest(JsonNode node,String key)throws SQLException {String value=string(node,key);require(value.matches("[0-9a-f]{64}"),"Publication correction digest required");return value;}
    private static UUID uuid(JsonNode node,String key)throws SQLException {String value=string(node,key);try{var id=UUID.fromString(value);require(id.toString().equals(value),"Canonical correction UUID required");return id;}catch(IllegalArgumentException invalid){throw new SQLException("Publication correction UUID required",invalid);}}
    private static long number(JsonNode node,String key)throws SQLException {require(node.path(key).isIntegralNumber()&&node.path(key).canConvertToLong()&&node.path(key).longValue()>0,"Publication correction positive event required");return node.path(key).longValue();}
    private static int nonnegative(JsonNode node,String key)throws SQLException {require(node.path(key).isInt()&&node.path(key).intValue()>=0,"Publication correction nonnegative counter required");return node.path(key).intValue();}
    private static String hash(String value){return DeploymentAgentInputs.hash(value);}
    private static void require(boolean value,String message)throws SQLException {if(!value)throw new SQLException(message);}
}
