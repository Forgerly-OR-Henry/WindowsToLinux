package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument.*;

import java.sql.SQLException;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.*;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.appapi.task.result.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.ai.review.DeploymentReviewDecision;
import gold.debug.windowstolinux.mod.deployment.db.execution.DeploymentExecutionRecord;
import gold.debug.windowstolinux.mod.deployment.service.agent.contract.DeploymentAgentInputs;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog.Event;

/** The fixed publication program's original-task installation history, independent of toolchain state. */
public final class DeploymentAgentPublicationRecoveryInstallation {
    public static final String EVENT = "PUBLICATION_RECOVERY_PROGRAM";
    public record Attempt(UUID action, String operation, long requestEvent, String request, String exactAction, String targets,
                          boolean reviewed, boolean granted, boolean dispatched, boolean ended) { }
    /** installAction retains a blocking prior install; a proven denied, unsent request remains only in history. */
    public record State(String binding, Optional<Attempt> attempt, Optional<UUID> installAction, boolean ready,
                        boolean mayInstall, String installTask, String installOperation) { }
    private final TaskContext task;
    private final DeploymentAgentActionLog journal;
    private final String binding;

    private DeploymentAgentPublicationRecoveryInstallation(TaskContext task, DeploymentAgentActionLog journal, String binding) {
        this.task=task; this.journal=journal; this.binding=binding;
    }

    public static DeploymentAgentPublicationRecoveryInstallation open(TaskContext task, DeploymentAgentActionLog journal,
        DeploymentExecutionRecord formal, String reviewRevision, DeploymentAgentPublicationRecoveryProgram program) throws Exception {
        var events=journal.events(task); var saved=journal.find(task).orElseThrow();
        var old=events.stream().filter(e->e.type().equals(EVENT)).toList();
        var before=old.isEmpty()?events:events.stream().filter(e->e.id()<old.getFirst().id()).toList();
        var node=object().put("formatVersion",1).put("kind","OPEN").put("task",task.id().toString()).put("execution",formal.identity().executionId().toString())
            .put("server",formal.identity().server().id()).put("identity",hash(saved.identity().toString())).put("target",saved.identity().targetDigest())
            .put("reviewRevision",reviewRevision).put("layout",program.manifest().get("layoutSha256")).put("request",hash(formal.requestJson()))
            .put("version",program.version()).put("path",program.path()).put("source",hash(program.source())).put("loader",hash(program.loader()))
            .put("installer",hash(program.installer())).put("manifest",program.version()).put("prefix",prefix(before));
        String text=encoded(node);
        if(old.isEmpty()) journal.event(task,EVENT,task.id().toString(),text);
        else require(old.getFirst().detail().equals(text),"Original installation identity/source/review changed");
        var state=read(journal.events(task));
        return new DeploymentAgentPublicationRecoveryInstallation(task,journal,state.binding());
    }

    public State state() throws Exception { return read(journal.events(task)); }

    /** Finishes only an unsent request or records a missing acknowledgement as UNKNOWN. It never retries an install. */
    public void retainInterrupted() throws Exception {
        var state=state();
        if(state.attempt().isPresent()&&!state.attempt().orElseThrow().ended()) {
            var attempt=state.attempt().orElseThrow();
            append(frame("RESULT",attempt.action()).put("resolution",attempt.dispatched()?"UNKNOWN":"NOT_SENT")
                .put("receipt","none").put("status","UNKNOWN").put("installTask","-").put("installOperation","-"));
        }
    }

    public void request(DeploymentAgentPublicationRecoveryProgram.Request request, ActionIntent intent) throws Exception {
        var state=state();
        require(state.attempt().isEmpty()||state.attempt().orElseThrow().ended(),"An installation request is still active");
        require(request.task().equals(task.id())&&Set.of(DeploymentAgentPublicationRecoveryProgram.READINESS,DeploymentAgentPublicationRecoveryProgram.INSTALL).contains(request.operation()),"Foreign store request");
        require(!request.operation().equals(DeploymentAgentPublicationRecoveryProgram.INSTALL)||state.mayInstall()&&state.installAction().isEmpty(),"An unknown or prior install cannot be repeated");
        append(frame("REQUEST",request.action()).put("operation",request.operation()).put("request",requestDigest(request.command()))
            .put("exactAction",hash(intent.exactAction())).put("targets",targetsDigest(request.resourceTargets())));
    }

    public void reviewed(UUID action,DeploymentReviewDecision decision,String evidence) throws Exception {
        digest(evidence); append(frame("REVIEW",action).put("decision",decision.decision()).put("confirmation",decision.requiresUserConfirmation())
            .put("reason",hash(decision.reason())).put("evidence",evidence));
    }
    public void granted(UUID action,ApprovalGrant grant) throws Exception { append(frame("GRANT",action).put("grant",grant.id().toString())); }
    public void dispatch(UUID action) throws Exception { append(frame("DISPATCH",action)); }

    public void result(UUID action,OperationResult<CommandResult> actual,DeploymentAgentPublicationRecoveryProgram.StoreReceipt receipt) throws Exception {
        var state=state();
        if(receipt!=null) {
            boolean ownerMatches=state.installTask().equals("-")||state.installTask().equals(receipt.installTask())&&state.installOperation().equals(receipt.installOperation());
            boolean attemptedMatches=state.installAction().isEmpty()||receipt.installTask().equals(task.id().toString())&&receipt.installOperation().equals(state.installAction().orElseThrow().toString().replace("-",""));
            if(receipt.status().equals("READY")? !ownerMatches||!attemptedMatches : state.installAction().isPresent()||!state.installTask().equals("-")) receipt=null;
        }
        append(frame("RESULT",action).put("resolution",receipt==null?"UNKNOWN":"ACKNOWLEDGED").put("receipt",resultDigest(actual))
            .put("status",receipt==null?"UNKNOWN":receipt.status()).put("installTask",receipt==null?"-":receipt.installTask())
            .put("installOperation",receipt==null?"-":receipt.installOperation()));
    }

    private ObjectNode frame(String kind,UUID action) { return object().put("formatVersion",1).put("kind",kind).put("task",task.id().toString())
        .put("binding",binding).put("action",action.toString()); }
    private void append(ObjectNode node) throws Exception {
        var events=journal.events(task);node.put("history",prefix(events));String text=encoded(node);
        // Validate the proposed append before making it durable.
        var proposed=new ArrayList<Event>(events);proposed.add(new Event(events.getLast().id()+1,EVENT,task.id().toString(),text));read(proposed);
        require(events.equals(journal.events(task)),"Installation history changed before append");journal.event(task,EVENT,task.id().toString(),text);
    }

    public static boolean present(List<Event> events) { return events.stream().anyMatch(e->e.type().equals(EVENT)); }
    public static void requireNoPending(List<Event> events) throws Exception {
        if(!present(events))return;
        var state=read(events);require(state.ready()&&state.attempt().map(Attempt::ended).orElse(false),"Publication program installation is unresolved");
    }

    public static State read(List<Event> events) throws Exception {
        prefix(events);var selected=events.stream().filter(e->e.type().equals(EVENT)).toList();require(!selected.isEmpty(),"Installation history is absent");
        var first=selected.getFirst();var opened=parsed(first.detail());
        fields(opened,Set.of("formatVersion","kind","task","execution","server","identity","target","reviewRevision","layout","request","version","path","source","loader","installer","manifest","prefix"));
        require(text(opened,"kind").equals("OPEN")&&first.action().equals(text(opened,"task")),"Foreign installation header");uuid(opened,"task");uuid(opened,"execution");
        for(String key:List.of("identity","target","reviewRevision","layout","request","version","source","loader","installer","manifest","prefix"))digest(text(opened,key));
        require(text(opened,"prefix").equals(prefix(events.stream().filter(e->e.id()<first.id()).toList())),"Installation origin prefix changed");
        String binding=hash(first.detail()),ownerTask="-",ownerOperation="-";Attempt current=null;UUID install=null;boolean ready=false,mayInstall=false;
        var actions=new HashSet<UUID>();var grants=new HashSet<UUID>();String verdict="";
        for(int i=1;i<selected.size();i++) {
            var event=selected.get(i);var node=parsed(event.detail());
            require(event.action().equals(text(opened,"task"))&&text(node,"task").equals(text(opened,"task"))&&text(node,"binding").equals(binding)
                &&text(node,"history").equals(prefix(events.stream().filter(e->e.id()<event.id()).toList())),"Installation history binding changed");
            String kind=text(node,"kind");UUID action=uuid(node,"action");var extra=new HashSet<String>(Set.of("formatVersion","kind","task","binding","action","history"));
            switch(kind) {
                case "REQUEST" -> {
                    extra.addAll(Set.of("operation","request","exactAction","targets"));fields(node,extra);
                    require(current==null||current.ended(),"Concurrent installation attempts");require(actions.add(action),"Replayed installation action");
                    String operation=text(node,"operation");require(Set.of(DeploymentAgentPublicationRecoveryProgram.READINESS,DeploymentAgentPublicationRecoveryProgram.INSTALL).contains(operation),"Unknown store operation");
                    if(operation.equals(DeploymentAgentPublicationRecoveryProgram.INSTALL)){require(mayInstall&&install==null,"Install is not an observed first missing version");install=action;}
                    for(String key:List.of("request","exactAction","targets"))digest(text(node,key));
                    current=new Attempt(action,operation,event.id(),text(node,"request"),text(node,"exactAction"),text(node,"targets"),false,false,false,false);
                    verdict="";ready=false;mayInstall=false;
                }
                case "REVIEW" -> {
                    extra.addAll(Set.of("decision","confirmation","reason","evidence"));fields(node,extra);require(current!=null&&current.action().equals(action)&&!current.reviewed()&&!current.ended(),"Unexpected installation review");
                    verdict=text(node,"decision");require(Set.of("APPROVE","DENY").contains(verdict)&&node.path("confirmation").isBoolean(),"Invalid independent installation review");digest(text(node,"reason"));digest(text(node,"evidence"));
                    current=copy(current,true,false,false,false);
                }
                case "GRANT" -> {
                    extra.add("grant");fields(node,extra);require(current!=null&&current.action().equals(action)&&current.reviewed()&&verdict.equals("APPROVE")&&!current.granted()&&!current.ended(),"Install has no independent approval");
                    require(grants.add(uuid(node,"grant")),"A store grant was reused");current=copy(current,true,true,false,false);
                }
                case "DISPATCH" -> {
                    fields(node,extra);require(current!=null&&current.action().equals(action)&&current.granted()&&!current.dispatched()&&!current.ended(),"Store dispatch is not granted");current=copy(current,true,true,true,false);
                }
                case "RESULT" -> {
                    extra.addAll(Set.of("resolution","receipt","status","installTask","installOperation"));fields(node,extra);
                    require(current!=null&&current.action().equals(action)&&!current.ended(),"Store result is duplicated or foreign");
                    String resolution=text(node,"resolution"),status=text(node,"status");
                    if(resolution.equals("ACKNOWLEDGED")) {
                        require(current.dispatched(),"Unsent store acknowledgement");digest(text(node,"receipt"));
                        if(status.equals("READY")) {
                            String observedTask=text(node,"installTask"),observedOperation=text(node,"installOperation");UUID.fromString(observedTask);require(observedOperation.matches("[0-9a-f]{32}"),"Missing installed owner operation");
                            require(ownerTask.equals("-")||ownerTask.equals(observedTask)&&ownerOperation.equals(observedOperation),"Installed immutable version owner changed");
                            ownerTask=observedTask;ownerOperation=observedOperation;
                            if(install!=null)require(ownerTask.equals(text(opened,"task"))&&ownerOperation.equals(install.toString().replace("-","")),"Unknown install does not match this ready version receipt");
                            ready=true;mayInstall=false;
                        } else {require(status.equals("MISSING")&&current.operation().equals(DeploymentAgentPublicationRecoveryProgram.READINESS)&&install==null&&ownerTask.equals("-"),"Unknown or previously ready install cannot become missing/reinstallable");mayInstall=true;ready=false;}
                    } else {
                        require(Set.of("UNKNOWN","NOT_SENT").contains(resolution)&&status.equals("UNKNOWN")&&text(node,"installTask").equals("-")&&text(node,"installOperation").equals("-"),"Invalid store uncertainty");
                        require(!resolution.equals("NOT_SENT")||!current.dispatched(),"A dispatched store action cannot become not sent");
                        if(!text(node,"receipt").equals("none"))digest(text(node,"receipt"));ready=false;mayInstall=false;
                        if(resolution.equals("NOT_SENT")&&current.operation().equals(DeploymentAgentPublicationRecoveryProgram.INSTALL)
                            &&current.reviewed()&&verdict.equals("DENY")&&!current.granted()&&!current.dispatched()
                            &&ownerTask.equals("-")&&ownerOperation.equals("-")&&action.equals(install))install=null;
                    }
                    current=copy(current,current.reviewed(),current.granted(),current.dispatched(),true);
                }
                default -> throw new SQLException("Unknown installation event kind");
            }
        }
        return new State(binding,Optional.ofNullable(current),Optional.ofNullable(install),ready,mayInstall,ownerTask,ownerOperation);
    }

    private static Attempt copy(Attempt a,boolean review,boolean grant,boolean dispatch,boolean ended){return new Attempt(a.action(),a.operation(),a.requestEvent(),a.request(),a.exactAction(),a.targets(),review,grant,dispatch,ended);}
    static String requestDigest(CommandRequest request)throws SQLException{return hash(encode(object().put("formatVersion",1).put("script",hash(request.script())).put("stdin",bytes(request.stdin())).put("stdinBytes",request.stdin().length).put("timeout",request.timeout().toString()).put("outputLimitBytes",request.outputLimitBytes())));}
    static String targetsDigest(Set<ActionTarget> targets)throws SQLException{var node=object().put("formatVersion",1);var array=node.putArray("targets");targets.stream().sorted(Comparator.comparing(ActionTarget::serverId).thenComparing(ActionTarget::kind).thenComparing(ActionTarget::id)).forEach(t->array.addObject().put("server",t.serverId()).put("kind",t.kind()).put("id",t.id()));return hash(encode(node));}
    static String resultDigest(OperationResult<CommandResult> result)throws SQLException{var node=object().put("formatVersion",1).put("outcome",result.outcome().name());if(result.value().isPresent()){var value=result.value().orElseThrow();node.put("stdout",hash(value.stdout())).put("stderr",hash(value.stderr())).put("truncated",value.truncated());value.exitCode().ifPresentOrElse(v->node.put("exitCode",v),()->node.putNull("exitCode"));}else node.putNull("value");result.problem().ifPresentOrElse(p->node.put("problemCode",p.code().name()).put("eventCode",p.eventCode()),()->node.putNull("problemCode").putNull("eventCode"));return hash(encode(node));}
    static String hash(String value){return DeploymentAgentInputs.hash(value);}
    private static String bytes(byte[] value)throws SQLException{try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}catch(java.security.NoSuchAlgorithmException e){throw new SQLException(e);}}
    private static String prefix(List<Event> events)throws Exception{return DeploymentAgentPublicationObservation.prefixSha256(events);}
    private static ObjectNode parsed(String text)throws SQLException{require(text.length()<=4096,"Installation frame exceeds character bound");var node=parse(text,16384);require(node.path("formatVersion").intValue()==1,"Unsupported installation format");return node;}
    private static String encoded(ObjectNode node)throws SQLException{String text=encode(node,16384);parsed(text);return text;}
    private static String text(JsonNode node,String key)throws SQLException{return string(node,key);}
    private static void digest(String value)throws SQLException{require(value.matches("[0-9a-f]{64}"),"Installation digest required");}
    private static void require(boolean condition,String message)throws SQLException{if(!condition)throw new SQLException(message);}
}
