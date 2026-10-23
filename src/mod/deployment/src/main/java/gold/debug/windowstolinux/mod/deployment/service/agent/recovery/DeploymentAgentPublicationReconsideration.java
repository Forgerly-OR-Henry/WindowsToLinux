package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** A durable, single human admission to a new independent review of an undispatched denied correction. */
public final class DeploymentAgentPublicationReconsideration {
    public static final String EVENT="PUBLICATION_RECONSIDERATION";
    public static final String TITLE="原发布纠错：一次重新独立审核";
    public static final String ALLOW="允许重新审核";
    public static final String NOTICE="原独立审核已拒绝，拒因正文未知。仅允许一次新的独立APP审核；本回答不是批准或grant，不允许重放旧动作。新动作仍须完整独立APP及全新grant。复议命令回执不替代旧ACK、不直接结清；结清仍须新整图目标证明及独立清理。";
    private static final String FORMAT="WTL_AGENT_PUBLICATION_RECONSIDERATION_1";
    private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder()
        .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final List<String> QUESTION_FIELDS=List.of("format","task","execution","questionId","planSha256","pendingIntent","continuationRequest","deniedRequest","deniedReview","deniedNotSent","deniedAction","sourceVersion","requestSha256","deniedRequestDetailSha256","reasonSha256","bindingSha256","server","component","unit","candidate","releaseSha256","layoutSha256","notice");
    private static final Set<String> COMMON=Set.of("formatVersion","kind","task","plan","pendingIntent","pendingIntentSha256","continuationRequest","deniedRequest","denial","action","questionSha256","history");
    private final TaskContext task;
    private final DeploymentAgentActionLog journal;
    private final Eligibility eligible;
    private final UUID admission;
    private final String prompt;
    private DeploymentAgentPublicationCorrection.Eligibility fresh;
    private UUID commandAction;

    public record Denied(Event request,Event review,Event notSent,UUID action,String sourceVersion,String requestSha256,
        String exactActionSha256,String targetsSha256,String reasonSha256,String bindingSha256) { }
    public record Eligibility(DeploymentAgentPublicationCorrection.Eligibility correction,Denied denied) { }
    public record Question(UUID task,UUID questionId,String questionSha256) { }

    private DeploymentAgentPublicationReconsideration(TaskContext task,DeploymentAgentActionLog journal,Eligibility eligible,UUID admission,String prompt){
        this.task=task;this.journal=journal;this.eligible=eligible;this.admission=admission;this.prompt=prompt;
    }

    /** Only the complete original DENY/NOT_SENT qualifies; its saved graph is historical, never asserted as current. */
    public static Optional<Eligibility> eligibility(DeploymentAgentPublicationCompensation.State state,List<Event> events)throws Exception {
        if(state.pending().isEmpty()||state.verified().isPresent()||state.pending().orElseThrow().verb()!=DeploymentAgentPublicationCompensation.Verb.GUARDED_ROLLBACK)return Optional.empty();
        var requests=new ArrayList<Event>();
        for(var event:events)if(event.type().equals(DeploymentAgentPublicationCorrection.EVENT)){
            var node=parse(event.detail(),16384);
            if(string(node,"kind").equals("REQUEST")&&number(node,"pendingIntent")==state.pending().orElseThrow().event())requests.add(event);
        }
        if(requests.isEmpty())return Optional.empty();require(requests.size()==1,"Denied correction request is duplicated");
        var first=requests.getFirst();int start=events.indexOf(first),end=start+1;
        while(end<events.size()&&events.get(end).type().equals(DeploymentAgentPublicationCorrection.EVENT))end++;
        var block=events.subList(start,end);
        if(block.size()!=3||!string(parse(block.get(1).detail(),16384),"kind").equals("REVIEW")
            ||!string(parse(block.get(2).detail(),16384),"kind").equals("NOT_SENT"))return Optional.empty();
        var review=parse(block.get(1).detail(),16384);
        if(!string(review,"decision").equals("DENY")||!review.path("confirmation").isBoolean()||review.path("confirmation").booleanValue()
            ||!review.path("rememberAuthorization").isBoolean()||review.path("rememberAuthorization").booleanValue())return Optional.empty();
        var before=events.subList(0,start);var originalState=DeploymentAgentPublicationCompensation.read(before,state.plan().task());
        var original=DeploymentAgentPublicationCorrection.eligibility(originalState,before).orElseThrow(()->new SQLException("Denied correction original eligibility disappeared"));
        var checked=DeploymentAgentPublicationCompensation.read(events.subList(0,end),state.plan().task());
        require(checked.plan().equals(state.plan())&&checked.pending().equals(state.pending())&&checked.completed()==state.completed()
            &&checked.verified().isEmpty(),"Denied correction no longer has the original pending identity");
        var node=parse(first.detail(),16384);
        var denied=new Denied(first,block.get(1),block.get(2),uuid(node,"action"),digest(node,"sourceVersion"),digest(node,"request"),
            digest(node,"exactAction"),digest(node,"targets"),digest(review,"reason"),digest(node,"prior"));
        require(denied.bindingSha256().equals(bindingSha(DeploymentAgentPublicationCorrection.binding(original))),"Denied correction binding changed");
        return Optional.of(new Eligibility(original,denied));
    }

    /** Called only after this read's public old codec has validated the complete original correction block. */
    static Optional<Eligibility> validatedDenial(DeploymentAgentPublicationCompensation.State state,List<Event> events,
        int start,int end,DeploymentAgentPublicationCorrection.Eligibility original)throws Exception {
        if(end-start!=3)return Optional.empty();
        var request=events.get(start);var review=events.get(start+1);var notSent=events.get(start+2);
        var first=parse(request.detail(),16384);var reviewed=parse(review.detail(),16384);var last=parse(notSent.detail(),16384);
        if(!string(first,"kind").equals("REQUEST")||!string(reviewed,"kind").equals("REVIEW")||!string(last,"kind").equals("NOT_SENT")
            ||!string(reviewed,"decision").equals("DENY")||!reviewed.path("confirmation").isBoolean()||reviewed.path("confirmation").booleanValue()
            ||!reviewed.path("rememberAuthorization").isBoolean()||reviewed.path("rememberAuthorization").booleanValue())return Optional.empty();
        require(state.plan().equals(original.original().plan())&&state.pending().equals(Optional.of(original.original().pending()))
            &&state.verified().isEmpty(),"Validated denial does not belong to the current original pending identity");
        var denied=new Denied(request,review,notSent,uuid(first,"action"),digest(first,"sourceVersion"),digest(first,"request"),
            digest(first,"exactAction"),digest(first,"targets"),digest(reviewed,"reason"),digest(first,"prior"));
        require(denied.bindingSha256().equals(bindingSha(DeploymentAgentPublicationCorrection.binding(original))),"Validated denial binding changed");
        return Optional.of(new Eligibility(original,denied));
    }

    /** ASK consumes the immutable task/pending/continuation/denied-request key, even on cancellation or interruption. */
    public static boolean attempted(List<Event> events,Eligibility eligible)throws Exception {
        for(var event:events)if(event.type().equals(EVENT)){
            var node=parsed(event.detail());
            if(number(node,"pendingIntent")==eligible.correction().original().pending().event()
                &&number(node,"continuationRequest")==continuation(eligible)&&number(node,"deniedRequest")==eligible.denied().request().id())return true;
        }
        return false;
    }
    public static DeploymentAgentPublicationCorrectionProgram.Binding binding(Eligibility eligible){return DeploymentAgentPublicationCorrection.binding(eligible.correction());}

    public static DeploymentAgentPublicationReconsideration beginAdmission(TaskContext task,DeploymentAgentActionLog journal,Eligibility eligible)throws Exception {
        var events=List.copyOf(journal.events(task));var state=DeploymentAgentPublicationCompensation.read(events,task.id());
        require(task.id().equals(state.plan().task())&&!task.cancelToken().requested()&&!attempted(events,eligible)
            &&eligibility(state,events).filter(eligible::equals).isPresent(),"Human reconsideration is foreign, cancelled, changed or already consumed");
        UUID id=UUID.randomUUID();rejectReuse(events,id,null);String prompt=question(eligible,id);
        var value=new DeploymentAgentPublicationReconsideration(task,journal,eligible,id,prompt);
        value.append(value.frame("ASK").put("question",prompt));return value;
    }
    public String question(){return prompt;}

    /** Pure bridge check: SHA is the complete canonical prompt UTF-8, not admission or execution authority. */
    public static Question validateQuestion(UUID task,String title,String prompt)throws Exception {
        require(TITLE.equals(title)&&prompt!=null&&prompt.length()<=2000,"Foreign or oversized reconsideration question");
        JsonNode node=JSON.readTree(prompt);fields(node,Set.copyOf(QUESTION_FIELDS));
        require(node.toString().equals(prompt)&&FORMAT.equals(string(node,"format"))&&uuid(node,"task").equals(task)&&NOTICE.equals(string(node,"notice")),"Reconsideration question is not canonical or task-bound");
        var names=new ArrayList<String>();node.fieldNames().forEachRemaining(names::add);require(names.equals(QUESTION_FIELDS),"Reconsideration question field order changed");
        uuid(node,"execution");UUID question=uuid(node,"questionId");UUID denied=uuid(node,"deniedAction");require(!question.equals(denied),"Reconsideration question ID was reused");
        for(String key:List.of("planSha256","sourceVersion","requestSha256","deniedRequestDetailSha256","reasonSha256","bindingSha256","releaseSha256","layoutSha256"))digest(node,key);
        long pending=number(node,"pendingIntent"),continuation=number(node,"continuationRequest"),request=number(node,"deniedRequest"),review=number(node,"deniedReview"),notSent=number(node,"deniedNotSent");
        require(pending<continuation&&continuation<request&&request<review&&review<notSent,"Reconsideration question event order changed");
        for(String key:List.of("server","component","unit","candidate"))require(string(node,key).matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"),"Reconsideration question identity is invalid");
        return new Question(task,question,hash(prompt));
    }
    public static boolean matchesQuestion(UUID task,String title,String prompt){try{validateQuestion(task,title,prompt);return true;}catch(Exception invalid){return false;}}

    public void verifyAdmission()throws Exception {
        var events=List.copyOf(journal.events(task));var state=DeploymentAgentPublicationCompensation.read(events,task.id());
        // The fresh complete read has already rebuilt the old denial and validated this ASK against it.
        requireSameOriginal(state,events,eligible);
        require(!task.cancelToken().requested(),"Human admission cancellation changed");
        var frames=own(events);require(frames.size()==1&&string(parsed(frames.getFirst().detail()),"kind").equals("ASK")
            &&string(parsed(frames.getFirst().detail()),"question").equals(prompt)
            &&digest(parsed(frames.getFirst().detail()),"denial").equals(denialSha(eligible))
            &&digest(parsed(frames.getFirst().detail()),"questionSha256").equals(hash(prompt))
            &&events.equals(journal.events(task)),"Human admission is no longer the same unanswered ASK");
    }
    /** Null is recorded cancellation. Non-matching text is retained only as classification, UTF-8 size and SHA. */
    public boolean answer(String input)throws Exception {
        verifyAdmission();String classification=input==null?"CANCELLED":ALLOW.equals(input)?ALLOW:"OTHER";
        byte[] bytes=input==null?null:input.getBytes(StandardCharsets.UTF_8);
        append(frame("ANSWER").put("answer",classification).put("inputSha256",input==null?"none":hash(input)).put("inputUtf8Bytes",bytes==null?0:bytes.length));
        return ALLOW.equals(classification);
    }

    public void bindRequest(DeploymentAgentPublicationCorrection.Eligibility fresh,DeploymentAgentPublicationCorrectionProgram.Request request,ActionIntent intent)throws Exception {
        var events=List.copyOf(journal.events(task));var state=DeploymentAgentPublicationCompensation.read(events,task.id());
        require(!task.cancelToken().requested()&&DeploymentAgentPublicationCorrection.eligibility(state,events).filter(fresh::equals).isPresent(),"Reconsideration fresh original graph changed");
        var answer=answerEvent(events);require(string(parsed(answer.detail()),"answer").equals(ALLOW)&&fresh.original().observationEnd()>answer.id()
            &&sameScope(eligible,fresh)&&request.binding().equals(DeploymentAgentPublicationCorrection.binding(fresh)),"Human answer does not bind a newer complete graph in the original scope");
        var fixed=new DeploymentAgentPublicationCorrectionProgram(layout(events,state.plan()));
        try(var expected=fixed.request(request.binding(),request.action())){
            require(fixed.sourceVersion().equals(eligible.denied().sourceVersion())
                &&DeploymentAgentPublicationRecoveryInstallation.requestDigest(request.command()).equals(DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()))
                &&request.resourceTargets().equals(expected.resourceTargets()),"Reconsideration changed the fixed denied source or request");
        }
        require(intent.revision().equals(state.plan().sha256())&&intent.serverIds().equals(List.of(state.plan().server()))
            &&intent.semantics().orElseThrow().operation().equals(DeploymentAgentPublicationCorrectionProgram.OPERATION)
            &&intent.semantics().orElseThrow().targets().equals(request.resourceTargets())
            &&intent.exactAction().equals(RemoteActions.command(new ServerId(state.plan().server()),state.plan().sha256(),request.command()).exactAction()),"Reconsideration exact action or targets changed");
        rejectReuse(events,request.action(),null);this.fresh=fresh;this.commandAction=request.action();
        append(frame("REQUEST").put("commandAction",request.action().toString()).put("operation",DeploymentAgentPublicationCorrectionProgram.OPERATION)
            .put("sourceVersion",fixed.sourceVersion()).put("request",DeploymentAgentPublicationRecoveryInstallation.requestDigest(request.command()))
            .put("exactAction",hash(intent.exactAction())).put("targets",DeploymentAgentPublicationRecoveryInstallation.targetsDigest(request.resourceTargets()))
            .put("reviewRevision",state.plan().reviewRevision()).put("prior",bindingSha(request.binding()))
            .put("observationEnd",fresh.original().observationEnd()).put("observation",fresh.original().observationSha256()).put("prefixEnd",events.getLast().id()));
    }
    public void reviewed(DeploymentReviewDecision decision,String evidence)throws Exception {append(commandFrame("REVIEW").put("decision",decision.decision()).put("confirmation",decision.requiresUserConfirmation()).put("reason",hash(decision.reason())).put("evidence",evidence).put("rememberAuthorization",false));}
    public void granted(ApprovalGrant grant)throws Exception {append(commandFrame("GRANT").put("grant",grant.id().toString()));}
    public void dispatch()throws Exception {append(commandFrame("DISPATCH"));}
    public void result(OperationResult<CommandResult> actual,DeploymentAgentPublicationCorrectionProgram.Receipt receipt)throws Exception {
        String safe=receipt==null?null:receipt.canonicalJson();require(safe==null||safe.getBytes(StandardCharsets.UTF_8).length<=65536,"Reconsideration safe receipt exceeds bound");
        if(safe!=null){var fixed=new DeploymentAgentPublicationCorrectionProgram(layout(journal.events(task),eligible.correction().original().plan()));try(var request=fixed.request(DeploymentAgentPublicationCorrection.binding(fresh),commandAction)){require(fixed.parse(request,actual).canonicalJson().equals(safe),"Reconsideration receipt differs from actual transport");}}
        int parts=safe==null?0:(safe.length()+2047)/2048;
        append(commandFrame("RESULT").put("observedOutcome",actual.outcome().name()).put("actual",DeploymentAgentPublicationRecoveryInstallation.resultDigest(actual)).put("fixedReceiptVerified",receipt!=null).put("receipt",safe==null?"none":hash(safe)).put("parts",parts));
        for(int i=0;i<parts;i++)append(commandFrame("RECEIPT").put("index",i).put("text",safe.substring(i*2048,Math.min(safe.length(),(i+1)*2048))));
        append(commandFrame("END"));
    }
    public void retainInterrupted()throws Exception {
        var frames=own(journal.events(task));if(frames.isEmpty())return;String last=string(parsed(frames.getLast().detail()),"kind");
        if(Set.of("NOT_SENT","INTERRUPTED","ABORTED","END","RESULT","RECEIPT").contains(last))return;
        if(last.equals("ASK")){append(frame("ANSWER").put("answer","INTERRUPTED").put("inputSha256","none").put("inputUtf8Bytes",0));return;}
        if(last.equals("ANSWER")){if(string(parsed(frames.getLast().detail()),"answer").equals(ALLOW))append(frame("ABORTED"));return;}
        append(commandFrame(last.equals("DISPATCH")?"INTERRUPTED":"NOT_SENT"));
    }
    public void verifyCurrent()throws Exception {
        var events=List.copyOf(journal.events(task));var state=DeploymentAgentPublicationCompensation.read(events,task.id());
        require(events.equals(journal.events(task)),"Reconsideration history changed during verification");verifyCurrent(state,events);
        require(events.equals(journal.events(task)),"Reconsideration history changed during verification");
    }
    /** Same fresh producer boundary only; never retained across review/host/send callbacks. */
    void verifyCurrent(DeploymentAgentPublicationCompensation.State state,List<Event> events)throws Exception {
        require(fresh!=null&&commandAction!=null&&!task.cancelToken().requested(),"Reconsideration has no current authorized request");
        var requests=own(events).stream().filter(e->{try{return string(parsed(e.detail()),"kind").equals("REQUEST");}catch(SQLException invalid){throw new IllegalStateException(invalid);}}).toList();
        require(requests.size()==1,"Reconsideration request disappeared or duplicated");var node=parsed(requests.getFirst().detail());
        require(state.plan().equals(fresh.original().plan())&&state.pending().equals(Optional.of(fresh.original().pending()))&&state.verified().isEmpty()
            &&uuid(node,"commandAction").equals(commandAction)&&uuid(node,"action").equals(admission)&&digest(node,"questionSha256").equals(hash(prompt))
            &&digest(node,"prior").equals(bindingSha(DeploymentAgentPublicationCorrection.binding(fresh)))&&number(node,"observationEnd")==fresh.original().observationEnd()
            &&digest(node,"observation").equals(fresh.original().observationSha256())&&DeploymentAgentPublicationObservation.readLatest(events,task.id()).endEvent()==fresh.original().observationEnd(),"Reconsideration original identity, request or graph changed");
    }
    public Map<String,?> facts(){
        if(fresh==null||commandAction==null)throw new IllegalStateException("Reconsideration has no human-confirmed request");
        return Map.of("question",prompt,"questionSha256",hash(prompt),"humanAnswer",ALLOW,"humanAnswerMeaning","Permission for one new independent APP review only; never an APP verdict, grant, replay or settlement authority",
            "originalDeniedRequest",eligible.denied().request().id(),"originalDeniedReview",eligible.denied().review().id(),"originalNotSent",eligible.denied().notSent().id(),
            "deniedReasonSha256",eligible.denied().reasonSha256(),"deniedReasonBody","UNKNOWN_NOT_RETAINED","fixedSourceVersion",eligible.denied().sourceVersion());
    }

    private List<Event> own(List<Event> events){return events.stream().filter(e->e.type().equals(EVENT)&&e.action().equals(admission.toString())).toList();}
    private Event answerEvent(List<Event> events)throws Exception {var matches=own(events).stream().filter(e->{try{return string(parsed(e.detail()),"kind").equals("ANSWER");}catch(SQLException invalid){throw new IllegalStateException(invalid);}}).toList();require(matches.size()==1,"Reconsideration exact human answer is missing or duplicated");return matches.getFirst();}
    private ObjectNode frame(String kind){return common(eligible,admission,hash(prompt),kind);}
    private ObjectNode commandFrame(String kind)throws SQLException {require(commandAction!=null,"Reconsideration command action is absent");return frame(kind).put("commandAction",commandAction.toString());}
    private void append(ObjectNode node)throws Exception {
        var events=List.copyOf(journal.events(task));node.put("history",DeploymentAgentPublicationObservation.prefixSha256(events));String raw=encoded(node);
        var proposed=new ArrayList<Event>(events);proposed.add(new Event(events.getLast().id()+1,EVENT,admission.toString(),raw));
        DeploymentAgentPublicationCompensation.read(proposed,task.id());require(events.equals(journal.events(task)),"Reconsideration history changed before append");journal.event(task,EVENT,admission.toString(),raw);
    }

    /** Replays actual admission segments; whole-graph prefixes are never filtered, rewritten or synthesized. */
    static int validateBlock(List<Event> events,int start,DeploymentAgentPublicationCompensation.State state,Eligibility eligible)throws Exception {
        var at=parsed(events.get(start).detail());UUID admission=uuid(at,"action");int ask=-1;
        for(int i=0;i<=start;i++)if(events.get(i).type().equals(EVENT)&&events.get(i).action().equals(admission.toString())&&string(parsed(events.get(i).detail()),"kind").equals("ASK")){require(ask<0,"Reconsideration ASK duplicated");ask=i;}
        require(ask>=0,"Reconsideration ASK is missing");var beforeAsk=events.subList(0,ask);
        requireSameOriginal(state,beforeAsk,eligible);
        require(!attempted(beforeAsk,eligible),"Reconsideration original opportunity was already consumed");rejectReuse(beforeAsk,admission,null);
        var askNode=parsed(events.get(ask).detail());String prompt=string(askNode,"question");var question=validateQuestion(state.plan().task(),TITLE,prompt);
        require(question.questionId().equals(admission)&&prompt.equals(question(eligible,admission)),"Reconsideration question does not describe the immutable original denial");
        int end=start;while(end<events.size()&&events.get(end).type().equals(EVENT))end++;
        String phase="NONE",decision=null,receiptSha=null;long answer=0;UUID command=null;int parts=0,index=0;boolean valid=false;StringBuilder receipt=new StringBuilder();
        DeploymentAgentPublicationCorrectionProgram.Request expected=null;
        try {
            for(int cursor=ask;cursor<end;cursor++){
                var event=events.get(cursor);
                if(!event.type().equals(EVENT)){
                    require(phase.equals("ALLOWED")&&(event.type().startsWith("PUBLICATION_OBSERVATION_")||Set.of("HEALTH_STATUS_QUERY_REQUEST","HEALTH_STATUS_QUERY_APPROVED","HEALTH_STATUS_QUERY_DENIED","HEALTH_STATUS_QUERY_RESULT").contains(event.type())),"Unrelated evidence interrupts human reconsideration");continue;
                }
                var node=parsed(event.detail());String kind=string(node,"kind");
                require(event.action().equals(admission.toString())&&uuid(node,"action").equals(admission)&&uuid(node,"task").equals(state.plan().task())
                    &&digest(node,"plan").equals(state.plan().sha256())&&number(node,"pendingIntent")==state.pending().orElseThrow().event()
                    &&digest(node,"pendingIntentSha256").equals(hash(state.pending().orElseThrow().frame()))&&number(node,"continuationRequest")==continuation(eligible)
                    &&number(node,"deniedRequest")==eligible.denied().request().id()&&digest(node,"denial").equals(denialSha(eligible))
                    &&digest(node,"questionSha256").equals(question.questionSha256())&&digest(node,"history").equals(DeploymentAgentPublicationObservation.prefixSha256(events.subList(0,cursor))),"Reconsideration identity, original denial, question or history changed");
                switch(kind){
                    case "ASK" -> {fields(node,extra("question"));require(cursor==ask&&phase.equals("NONE"),"Reconsideration ASK is out of order");phase="ASK";}
                    case "ANSWER" -> {
                        fields(node,extra("answer","inputSha256","inputUtf8Bytes"));require(phase.equals("ASK"),"Reconsideration answer is duplicated or unrequested");String value=string(node,"answer");int length=nonnegative(node,"inputUtf8Bytes");String input=string(node,"inputSha256");
                        require(Set.of(ALLOW,"CANCELLED","OTHER","INTERRUPTED").contains(value),"Reconsideration answer classification is invalid");
                        if(value.equals(ALLOW))require(input.equals(hash(ALLOW))&&length==ALLOW.getBytes(StandardCharsets.UTF_8).length,"Reconsideration answer is not the exact human value");
                        else if(value.equals("OTHER"))require(input.matches("[0-9a-f]{64}")&&!input.equals(hash(ALLOW)),"Reconsideration refused input changed");
                        else require(input.equals("none")&&length==0,"Reconsideration cancellation invented input");
                        answer=event.id();phase=value.equals(ALLOW)?"ALLOWED":"TERMINAL";
                    }
                    case "ABORTED" -> {fields(node,COMMON);require(phase.equals("ALLOWED"),"Reconsideration abort is out of order");phase="TERMINAL";}
                    case "REQUEST" -> {
                        fields(node,commandExtra("operation","sourceVersion","request","exactAction","targets","reviewRevision","prior","observationEnd","observation","prefixEnd"));
                        require(phase.equals("ALLOWED"),"Reconsideration request lacks an exact new human answer");command=uuid(node,"commandAction");rejectReuse(events.subList(0,cursor),command,null);
                        var beforeRequest=events.subList(0,cursor);var barrier=new DeploymentAgentPublicationCompensation.State(state.plan(),state.completed(),state.pending(),answer,Optional.empty());
                        var selected=DeploymentAgentPublicationCompensation.diagnosticEligibility(barrier,beforeRequest).orElseThrow(()->new SQLException("Reconsideration lacks a newer complete unmet-goal graph"));
                        var old=binding(eligible);
                        var fresh=new DeploymentAgentPublicationCorrection.Eligibility(selected,new DeploymentAgentPublicationCorrectionProgram.Binding(
                            old.followup(),old.prior(),selected.observationEnd(),selected.observationSha256()));
                        require(sameScope(eligible,fresh)&&fresh.original().observationEnd()>answer,"Reconsideration changed the original correction scope");
                        var program=new DeploymentAgentPublicationCorrectionProgram(layout(beforeRequest,state.plan()));expected=program.request(DeploymentAgentPublicationCorrection.binding(fresh),command);
                        require(string(node,"operation").equals(DeploymentAgentPublicationCorrectionProgram.OPERATION)&&digest(node,"reviewRevision").equals(state.plan().reviewRevision())
                            &&digest(node,"sourceVersion").equals(program.sourceVersion())&&program.sourceVersion().equals(eligible.denied().sourceVersion())
                            &&digest(node,"prior").equals(bindingSha(expected.binding()))&&number(node,"observationEnd")==fresh.original().observationEnd()
                            &&digest(node,"observation").equals(fresh.original().observationSha256())&&number(node,"prefixEnd")==events.get(cursor-1).id()
                            &&digest(node,"request").equals(DeploymentAgentPublicationRecoveryInstallation.requestDigest(expected.command()))
                            &&digest(node,"targets").equals(DeploymentAgentPublicationRecoveryInstallation.targetsDigest(expected.resourceTargets()))
                            &&digest(node,"exactAction").equals(hash(RemoteActions.command(new ServerId(state.plan().server()),state.plan().sha256(),expected.command()).exactAction())),"Reconsideration fixed source, request, binding or targets changed");phase="REQUEST";
                    }
                    case "REVIEW" -> {fields(node,commandExtra("decision","confirmation","reason","evidence","rememberAuthorization"));require(phase.equals("REQUEST")&&Set.of("APPROVE","DENY").contains(string(node,"decision"))&&node.path("confirmation").isBoolean()&&node.path("rememberAuthorization").isBoolean()&&!node.path("rememberAuthorization").booleanValue(),"Reconsideration independent review is missing or duplicated");digest(node,"reason");digest(node,"evidence");decision=string(node,"decision");phase="REVIEW";}
                    case "GRANT" -> {fields(node,commandExtra("grant"));require(phase.equals("REVIEW")&&"APPROVE".equals(decision),"Human reconsideration cannot supply an APP approval");rejectReuse(events.subList(0,cursor),null,uuid(node,"grant"));phase="GRANT";}
                    case "DISPATCH" -> {fields(node,commandExtra());require(phase.equals("GRANT"),"Reconsideration dispatch lacks a new grant");phase="DISPATCH";}
                    case "NOT_SENT" -> {fields(node,commandExtra());require(Set.of("REQUEST","REVIEW","GRANT").contains(phase),"Reconsideration NOT_SENT contradicts dispatch");phase="TERMINAL";}
                    case "INTERRUPTED" -> {fields(node,commandExtra());require(phase.equals("DISPATCH"),"Reconsideration interrupted without dispatch");phase="TERMINAL";}
                    case "RESULT" -> {fields(node,commandExtra("observedOutcome","actual","fixedReceiptVerified","receipt","parts"));require(phase.equals("DISPATCH")&&Set.of("SUCCEEDED","FAILED","CANCELLED","UNKNOWN").contains(string(node,"observedOutcome"))&&node.path("fixedReceiptVerified").isBoolean(),"Reconsideration result is unbound or duplicated");digest(node,"actual");valid=node.path("fixedReceiptVerified").booleanValue();parts=nonnegative(node,"parts");receiptSha=string(node,"receipt");require(parts<=32&&(valid?parts>0&&string(node,"observedOutcome").equals("SUCCEEDED")&&receiptSha.matches("[0-9a-f]{64}"):parts==0&&receiptSha.equals("none")),"Reconsideration receipt classification changed");phase="RESULT";}
                    case "RECEIPT" -> {fields(node,commandExtra("index","text"));require(phase.equals("RESULT")&&valid&&nonnegative(node,"index")==index&&index<parts,"Reconsideration receipt order changed");String text=string(node,"text");require(!text.isEmpty()&&text.length()<=2048,"Reconsideration safe receipt chunk exceeds bound");receipt.append(text);index++;}
                    case "END" -> {fields(node,commandExtra());require(phase.equals("RESULT")&&index==parts,"Reconsideration receipt is incomplete");if(valid){require(hash(receipt.toString()).equals(receiptSha),"Reconsideration receipt digest changed");new DeploymentAgentPublicationCorrectionProgram(layout(events.subList(0,ask),state.plan())).validateReceipt(expected,receipt.toString());}phase="TERMINAL";}
                    default -> throw new SQLException("Unexpected reconsideration frame kind");
                }
                if(!Set.of("ASK","ANSWER","ABORTED","REQUEST").contains(kind))require(command!=null&&uuid(node,"commandAction").equals(command),"Reconsideration command action changed");
            }
            return end;
        }finally{if(expected!=null)expected.close();}
    }

    private static ObjectNode common(Eligibility eligible,UUID action,String question,String kind){return object().put("formatVersion",1).put("kind",kind).put("task",eligible.correction().original().plan().task().toString()).put("plan",eligible.correction().original().plan().sha256()).put("pendingIntent",eligible.correction().original().pending().event()).put("pendingIntentSha256",hash(eligible.correction().original().pending().frame())).put("continuationRequest",continuation(eligible)).put("deniedRequest",eligible.denied().request().id()).put("denial",denialSha(eligible)).put("action",action.toString()).put("questionSha256",question);}
    private static long continuation(Eligibility eligible){return binding(eligible).followup().diagnostic().prior().requestEvent();}
    private static boolean sameScope(Eligibility eligible,DeploymentAgentPublicationCorrection.Eligibility fresh){var old=binding(eligible);var current=DeploymentAgentPublicationCorrection.binding(fresh);return eligible.correction().original().plan().equals(fresh.original().plan())&&eligible.correction().original().pending().equals(fresh.original().pending())&&old.followup().equals(current.followup())&&old.prior().equals(current.prior());}
    private static void requireSameOriginal(DeploymentAgentPublicationCompensation.State state,List<Event> events,Eligibility eligible)throws SQLException {
        require(state.plan().equals(eligible.correction().original().plan())&&state.pending().equals(Optional.of(eligible.correction().original().pending()))
            &&state.verified().isEmpty()&&events.contains(eligible.denied().request())&&events.contains(eligible.denied().review())&&events.contains(eligible.denied().notSent()),
            "Reconsideration original scope or immutable denied frames changed");
    }
    private static String denialSha(Eligibility eligible){return hash(eligible.denied().request().detail()+"\n"+eligible.denied().review().detail()+"\n"+eligible.denied().notSent().detail());}
    private static String bindingSha(DeploymentAgentPublicationCorrectionProgram.Binding binding){return hash(JSON.valueToTree(binding).toString());}
    private static String question(Eligibility eligible,UUID id)throws Exception {
        var original=eligible.correction().original();var plan=original.plan();var component=original.component();var denied=eligible.denied();
        var node=JSON.createObjectNode().put("format",FORMAT).put("task",plan.task().toString()).put("execution",plan.execution().toString()).put("questionId",id.toString()).put("planSha256",plan.sha256())
            .put("pendingIntent",original.pending().event()).put("continuationRequest",continuation(eligible)).put("deniedRequest",denied.request().id()).put("deniedReview",denied.review().id()).put("deniedNotSent",denied.notSent().id())
            .put("deniedAction",denied.action().toString()).put("sourceVersion",denied.sourceVersion()).put("requestSha256",denied.requestSha256()).put("deniedRequestDetailSha256",hash(denied.request().detail())).put("reasonSha256",denied.reasonSha256()).put("bindingSha256",denied.bindingSha256())
            .put("server",plan.server()).put("component",component.id()).put("unit",component.unit()).put("candidate",component.candidate()).put("releaseSha256",component.release()).put("layoutSha256",binding(eligible).followup().diagnostic().continuation().original().layoutSha256()).put("notice",NOTICE);
        String text=node.toString();require(validateQuestion(plan.task(),TITLE,text).questionId().equals(id),"Invalid reconsideration prompt");return text;
    }
    private static DeploymentRemoteLayout layout(List<Event> events,DeploymentAgentPublicationCompensation.Plan plan)throws Exception {
        var selected=events.stream().filter(e->e.type().equals("REMOTE_LAYOUT")).toList();require(selected.size()==1,"Original reconsideration layout is missing or duplicated");var event=selected.getFirst();var node=parse(event.detail(),16384);fields(node,Set.of("formatVersion","task","server","layout","layoutSha256"));var layout=DeploymentRemoteLayout.decode(node.path("layout").toString());
        require(node.path("formatVersion").isInt()&&node.path("formatVersion").intValue()==1&&event.action().equals(plan.task().toString())&&uuid(node,"task").equals(plan.task())&&string(node,"server").equals(plan.server())&&digest(node,"layoutSha256").equals(layout.digest())&&string(parse(plan.header()),"layout").equals(layout.digest()),"Original reconsideration layout changed");return layout;
    }
    private static void rejectReuse(List<Event> events,UUID action,UUID grant)throws Exception {
        for(var event:events){if(action!=null)require(!event.action().equals(action.toString()),"Reconsideration action ID was reused");JsonNode node;try{node=parse(event.detail(),16384);}catch(SQLException notObject){continue;}
            if(action!=null)for(String key:List.of("action","actionId","commandAction"))if(node.path(key).isTextual())require(!node.path(key).textValue().equals(action.toString()),"Reconsideration action ID was reused");
            if(grant!=null)for(String key:List.of("grant","grantId"))if(node.path(key).isTextual())require(!node.path(key).textValue().equals(grant.toString()),"Reconsideration grant was reused");}
    }
    static void rejectHistoricalReuse(List<Event> events)throws Exception {
        var actions=new HashSet<String>();var grants=new HashSet<String>();
        for(var event:events){
            if(!event.type().equals(EVENT))require(!actions.contains(event.action()),"Reconsideration action reused by a later event");
            JsonNode node;try{node=parse(event.detail(),16384);}catch(SQLException notObject){continue;}
            if(event.type().equals(EVENT)){String kind=string(node,"kind");if(kind.equals("ASK"))require(actions.add(uuid(node,"action").toString()),"Reconsideration admission reused");if(kind.equals("REQUEST"))require(actions.add(uuid(node,"commandAction").toString()),"Reconsideration command action reused");if(kind.equals("GRANT"))require(grants.add(uuid(node,"grant").toString()),"Reconsideration grant reused");continue;}
            for(String key:List.of("action","actionId","commandAction"))if(node.path(key).isTextual())require(!actions.contains(node.path(key).textValue()),"Reconsideration action reused by a later event");
            for(String key:List.of("grant","grantId"))if(node.path(key).isTextual())require(!grants.contains(node.path(key).textValue()),"Reconsideration grant reused by a later event");
        }
    }
    private static Set<String> extra(String...names){var all=new HashSet<String>(COMMON);all.addAll(List.of(names));return Set.copyOf(all);}
    private static Set<String> commandExtra(String...names){var all=new HashSet<String>(extra(names));all.add("commandAction");return Set.copyOf(all);}
    private static ObjectNode parsed(String raw)throws SQLException {require(raw.length()<=4096,"Reconsideration frame exceeds character bound");var node=parse(raw,16384);require(node.path("formatVersion").isInt()&&node.path("formatVersion").intValue()==1,"Unsupported reconsideration journal version");return node;}
    private static String encoded(ObjectNode node)throws SQLException {String raw=encode(node,16384);parsed(raw);return raw;}
    private static String digest(JsonNode node,String key)throws SQLException {String value=string(node,key);require(value.matches("[0-9a-f]{64}"),"Reconsideration digest required");return value;}
    private static long number(JsonNode node,String key)throws SQLException {require(node.path(key).isIntegralNumber()&&node.path(key).canConvertToLong()&&node.path(key).longValue()>0,"Reconsideration positive event required");return node.path(key).longValue();}
    private static int nonnegative(JsonNode node,String key)throws SQLException {require(node.path(key).isInt()&&node.path(key).intValue()>=0,"Reconsideration nonnegative size required");return node.path(key).intValue();}
    private static String hash(String value){return DeploymentAgentInputs.hash(value);}
    private static void require(boolean value,String message)throws SQLException {if(!value)throw new SQLException(message);}
}
