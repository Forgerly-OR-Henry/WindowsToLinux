package gold.debug.windowstolinux.app.service.file;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import gold.debug.windowstolinux.app.file.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.file.FileRepository;
import gold.debug.windowstolinux.app.service.security.DesktopSecurityService;
import gold.debug.windowstolinux.app.service.task.*;
import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.task.*;

/** Task-authorized common file service with actual approval, persistence and managed transports. / 实际审批、持久化及受管传输接通的任务授权通用文件服务。 */
public final class PlatformFileOperations implements AutoCloseable {
    private final PlatformTaskRuntime tasks;
    private final DesktopSecurityService security;
    private final FileInteraction interaction;
    private final FileBackendAccess backends;
    private final FileTransfers transfers;
    private final FileRepository repository;
    private final TaskCapabilityScope scope;
    private final String owner, version;
    /** Binds one genuine platform capability activation. / 绑定一次真实平台能力启用。 */
    public PlatformFileOperations(PlatformDatabase database, PlatformTaskRuntime tasks, DesktopSecurityService security,
                                  String owner, String version, FileInteraction interaction, FileBackendAccess backends,
                                  FileTransfers transfers) {
        this.tasks=Objects.requireNonNull(tasks);this.security=Objects.requireNonNull(security);this.owner=Objects.requireNonNull(owner);this.version=Objects.requireNonNull(version);
        this.interaction=Objects.requireNonNull(interaction);this.backends=Objects.requireNonNull(backends);this.transfers=Objects.requireNonNull(transfers);
        repository=new FileRepository(database,owner);scope=new TaskCapabilityScope(database,tasks,owner,version,Optional.empty(),"file-operation-");
    }
    /** Observes the actual File or Link. / 观测实际 File 或 Link。 */
    public FileInfo inspect(FilePath path,boolean link,TaskContext task)throws ApiException{return access(task,false,()->backends.withBackend(path,task,b->new FileEngine(b).inspect(path,link,cancel(task))));}
    /** Reads a bounded content copy. / 读取有界内容副本。 */
    public byte[] read(FilePath path,int limit,TaskContext task)throws ApiException{return access(task,false,()->backends.withBackend(path,task,b->new FileEngine(b).read(path,limit,cancel(task))));}
    /** Lists bounded filtered actual entries. / 列举有界筛选实际子项。 */
    public List<FileInfo> list(FilePath path,int limit,Optional<FileInfo.Kind> kind,String text,TaskContext task)throws ApiException {
        Objects.requireNonNull(kind);if(text==null||text.length()>4096)throw error(ProblemCode.INVALID_INPUT,"FILE_FILTER","Invalid directory filter");
        return access(task,false,()->backends.withBackend(path,task,b->new FileEngine(b).list(path,limit,kind,text,cancel(task))));
    }
    /** Executes a preplanned copy/move; cross-host content retains the existing transfer journal. / 执行预计划复制/移动，跨主机内容保留既有传输记录。 */
    public FileReport copy(FilePath from,FilePath to,boolean link,boolean move,FileChoice choice,TaskContext task)throws ApiException {
        Objects.requireNonNull(choice);
        return access(task,false,()->{
            if(from.server().isPresent()&&to.server().isPresent()&&!from.server().equals(to.server()))throw new IOException("Cross-server operations are unsupported");
            FilePath host=from.server().isPresent()?from:to;
            return backends.withBackend(host,task,backend->{
                FileBackend source=from.server().isPresent()?backend:new LocalFileBackend();
                FileBackend target=to.server().isPresent()?backend:new LocalFileBackend();
                if(from.server().isEmpty()&&to.server().isEmpty()){source=backend;target=backend;}
                FileEngine fromEngine=new FileEngine(source),toEngine=new FileEngine(target);
                boolean cross=!from.server().equals(to.server());
                var result=fromEngine.copy(from,to,link,move,choice,toEngine,decisions(task),approval(task),journal(task),cancel(task),
                    (completed,total)->{try{task.progress("文件内容传输",total==0?Optional.empty():Optional.of((double)completed/total));}catch(ApiException failure){throw new IOException("Progress could not be recorded",failure);}},
                    cross?(s,t,e)->transfers.transfer(s,t,e,task):null);
                observe(result,task);return result;
            });
        });
    }
    /** Creates or writes bounded content. / 创建或写入有界内容。 */
    public FileReport write(FilePath path,byte[] input,boolean directory,boolean createOnly,TaskContext task)throws ApiException {
        Objects.requireNonNull(input);if(input.length>64*1024*1024)throw error(ProblemCode.INVALID_INPUT,"FILE_WRITE_SIZE","Write capacity exceeded");byte[] content=input.clone();
        try{return access(task,false,()->backends.withBackend(path,task,b->{var result=new FileEngine(b).write(path,content,directory,createOnly,decisions(task),approval(task),journal(task),cancel(task));observe(result,task);return result;}));}
        finally{Arrays.fill(content,(byte)0);}
    }
    /** Creates and registers an explicitly targeted link. / 创建并登记明确指向链接。 */
    public FileReport createLink(FilePath path,String target,TaskContext task)throws ApiException {
        if(target==null||target.isBlank()||target.length()>4096||target.codePoints().anyMatch(Character::isISOControl))throw error(ProblemCode.INVALID_INPUT,"FILE_LINK_TARGET","Invalid link target");
        return access(task,false,()->backends.withBackend(path,task,b->{var result=new FileEngine(b).createLink(path,target,approval(task),journal(task),cancel(task));observe(result,task);return result;}));
    }
    /** Registers an observed link without changing it. / 登记观测链接而不修改。 */
    public void registerLink(FilePath path,TaskContext task)throws ApiException {
        access(task,false,()->backends.withBackend(path,task,b->{var engine=new FileEngine(b);FileInfo link=engine.inspect(path,true,cancel(task));FileJournal j=journal(task);UUID id=j.begin("REGISTER_LINK",link.actual(),Optional.empty(),FileActions.stamp(link));j.saveLink(link,engine.optionalTarget(link,cancel(task)));j.record(id,"COMPLETE",link.actual(),Optional.empty(),link.identity());return null;}));
    }
    /** Removes only caller-owned registry metadata. / 仅解除调用方所属登记元数据。 */
    public void unregisterLink(FilePath path,TaskContext task)throws ApiException {
        access(task,false,()->{FileJournal j=journal(task);UUID id=j.begin("UNREGISTER_LINK",path,Optional.empty(),path.key());j.removeLink(path);j.record(id,"COMPLETE",path,Optional.empty(),"unregistered");return null;});
    }
    /** Implements ordinary confirmation/waiting or independently approved single-use deletion. / 实现常规确认等待或独立审批的单次许可删除。 */
    public FileReport delete(FilePath path,boolean link,Optional<ApprovalGrant> permit,TaskContext task)throws ApiException {
        return access(task,false,()->{
            while(true){
                DeleteObservation observation=backends.withBackend(path,task,b->{var engine=new FileEngine(b);FileInfo info=engine.inspect(path,link,cancel(task));return new DeleteObservation(info,b.canRecycle(info.actual()));});
                DeleteEffect effect=observation.recyclable?DeleteEffect.RECYCLE:DeleteEffect.PERMANENT;
                FileInfo info=observation.info;
                ApprovalGrant grant;
                if(permit.isPresent()){
                    grant=permit.orElseThrow();ActionIntent reviewed=interaction.reviewed(grant,task);
                    if(reviewed.equals(FileActions.delete(info,link,DeleteEffect.PERMANENT)))effect=DeleteEffect.PERMANENT;
                    else if(!reviewed.equals(FileActions.delete(info,link,DeleteEffect.RECYCLE)))throw error(ProblemCode.ACCESS_DENIED,"FILE_DELETE_PERMIT","Deletion permit does not match the actual object or effect");
                    if(effect==DeleteEffect.RECYCLE&&!observation.recyclable)throw error(ProblemCode.UNAVAILABLE,"FILE_RECYCLE_UNAVAILABLE","Recycle permission cannot be upgraded to permanent deletion");
                }else{
                    String explanation;
                    try{explanation=interaction.explain(info,task);}catch(ApiException failure){explanation="AI 用途说明未生成："+failure.problem().eventCode();}
                    var decision=interaction.approve(FileActions.delete(info,link,effect),info,true,explanation,task);
                    if(decision.outcome()!=Outcome.SUCCEEDED||decision.value().isEmpty()){
                        if(cancel(task).requested())return stopped(path,"Task cancelled before deletion");
                        FileJournal j=journal(task);UUID wait=j.begin("DELETE_WAIT",path,Optional.empty(),FileActions.stamp(info));j.record(wait,"WAITING",path,Optional.empty(),"User declined deletion");
                        boolean resume=interaction.awaitContinue("删除未获确认，文件保留；继续时重新核对并询问，或终止任务",task);
                        j.record(wait,resume?"COMPLETE":"CANCELLED",path,Optional.empty(),resume?"Explicitly continue and revalidate":"Task terminated");
                        if(!resume)return stopped(path,"Task terminated while waiting for deletion");
                        continue;
                    }
                    grant=decision.value().orElseThrow();
                }
                DeleteEffect chosen=effect;
                return backends.withBackend(path,task,b->{
                    var engine=new FileEngine(b);FileInfo current=engine.inspect(path,link,cancel(task));
                    if(!FileActions.stamp(info).equals(FileActions.stamp(current)))throw error(ProblemCode.ACCESS_DENIED,"FILE_DELETE_CHANGED","Object changed after confirmation");
                    ActionIntent intent=FileActions.delete(current,link,chosen);
                    if(permit.isPresent())interaction.consumePermit(grant,intent,task);else interaction.consume(grant,intent,task);
                    var result=engine.delete(current,link,chosen,journal(task),cancel(task));observe(result,task);return result;
                });
            }
        });
    }
    /** Reads original task-scoped operation evidence. / 读取原任务限定操作依据。 */
    public List<FileOperation> operations(TaskContext task)throws ApiException{return access(task,true,()->repository.operations(task.id()));}
    /** Checks original observed results without replaying effects or reviving permits. / 核对原观测结果，不重放副作用或复活许可。 */
    public FileReport checkOperation(UUID operation,TaskContext task)throws ApiException {
        return access(task,true,()->{
            FileOperation original=repository.operation(operation,task.id()).orElseThrow(()->error(ProblemCode.NOT_FOUND,"FILE_OPERATION","Original operation is unavailable"));
            var latest=new LinkedHashMap<FilePath,FileOperation.Step>();for(var step:original.steps())latest.put(step.source(),step);
            var results=new ArrayList<FileReport.Item>();
            for(var step:latest.values()) {
                Outcome state=Outcome.UNKNOWN;String reason="Original stage requires explicit reconciliation";
                if(Set.of("WRITTEN","LINK_UPDATED","SOURCE_REMOVED","DELETED_ITEM","COMPLETE").contains(step.phase())){
                    FilePath observed=step.target().orElse(step.source());
                    try{
                        Optional<FileInfo> actual=backends.withBackend(observed,task,b->b.stat(observed));
                        if(step.phase().equals("DELETED_ITEM")&&actual.isEmpty()){state=Outcome.SUCCEEDED;reason="Original deletion absence confirmed";}
                        else if(actual.isPresent()&&actual.orElseThrow().identity().equals(step.detail())){state=Outcome.SUCCEEDED;reason="Original observed object identity confirmed";}
                    }catch(IOException inaccessible){reason="Original effect could not be observed";}
                }
                results.add(new FileReport.Item(step.source(),step.target(),state,reason));
            }
            if(results.isEmpty())results.add(new FileReport.Item(original.source(),original.target(),Outcome.UNKNOWN,"Operation was recorded but no effect was confirmed"));
            Outcome overall=results.stream().allMatch(r->r.outcome()==Outcome.SUCCEEDED)?Outcome.SUCCEEDED:Outcome.UNKNOWN;
            return new FileReport(operation,overall,results);
        });
    }
    private FileEngine.Decisions decisions(TaskContext task){return new FileEngine.Decisions(){public FileChoice.Directory directory(FileInfo source,FileInfo target)throws ApiException{return interaction.chooseDirectory(source,target,task);}public FileChoice.Conflict file(FileInfo source,FileInfo target)throws ApiException{return interaction.chooseFile(source,target,task);}};}
    private FileEngine.Approval approval(TaskContext task){return(source,type,plan)->{
        String exact=FileActions.stamp(source)+"\n"+digest(plan);String summary="文件操作计划："+type+"\n"+plan.substring(0,Math.min(plan.length(),4000));
        var intent=FileActions.intent(type,source,"file-v1",exact,summary);
        var decision=interaction.approve(intent,source,false,"",task);
        if(decision.outcome()!=Outcome.SUCCEEDED||decision.value().isEmpty())throw error(ProblemCode.ACCESS_DENIED,"FILE_OPERATION_DECLINED","File operation was not approved");
        interaction.consume(decision.value().orElseThrow(),intent,task);
    };}
    private FileJournal journal(TaskContext task){return new FileJournal(){
        public UUID begin(String type,FilePath source,Optional<FilePath> target,String plan)throws IOException{try{return repository.begin(task.id(),type,source,target,plan);}catch(SQLException failure){throw new IOException("File operation evidence could not be committed",failure);}}
        public void record(UUID operation,String phase,FilePath source,Optional<FilePath> target,String detail)throws IOException{try{repository.record(operation,task.id(),phase,source,target,detail);}catch(SQLException failure){throw new IOException("File phase could not be committed",failure);}}
        public List<FileInfo> links(FilePath host)throws IOException{try{return repository.links(host);}catch(SQLException failure){throw new IOException("Link registry unavailable",failure);}}
        public boolean targetMatches(FileInfo link,FileInfo target)throws IOException{try{return repository.targetMatches(link,target);}catch(SQLException failure){throw new IOException("Registered target identity unavailable",failure);}}
        public void saveLink(FileInfo link,Optional<FileInfo> target)throws IOException{try{repository.saveLink(task.id(),link,target);}catch(SQLException failure){throw new IOException("Link association could not be committed",failure);}}
        public void removeLink(FilePath link)throws IOException{try{repository.removeLink(link);}catch(SQLException failure){throw new IOException("Link association could not be removed",failure);}}
    };}
    private CancelToken cancel(TaskContext task){return tasks.operationCancellation(task);}
    private <T>T access(TaskContext task,boolean recovery,Work<T> work)throws ApiException {
        try{return scope.call(task,recovery,()->{
            security.requireUnlocked();
            try{return work.run();}catch(ApiException failure){throw failure;}catch(java.util.concurrent.CancellationException cancelled){throw error(ProblemCode.CANCELLED,"FILE_CANCELLED","File operation was cancelled");}catch(IllegalArgumentException invalid){throw error(ProblemCode.INVALID_INPUT,"FILE_REQUEST","Invalid file operation request");}catch(IOException failure){throw error(ProblemCode.IO_FAILURE,"FILE_IO","File object, permission, identity or effect could not be verified");}
        });}catch(SQLException failure){throw error(ProblemCode.IO_FAILURE,"FILE_STORAGE","File operation storage is unavailable");}
    }
    private void observe(FileReport result,TaskContext task)throws ApiException{if(result.outcome()==Outcome.UNKNOWN)tasks.reportUncertainEffect(task);}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static FileReport stopped(FilePath path,String reason){return new FileReport(UUID.randomUUID(),Outcome.CANCELLED,List.of(new FileReport.Item(path,Optional.empty(),Outcome.CANCELLED,reason)));}
    private static ApiException error(ProblemCode code,String event,String message){return new ApiException(new Problem(code,event,message,Map.of(),Optional.empty()));}
    private record DeleteObservation(FileInfo info,boolean recyclable){}
    @FunctionalInterface private interface Work<T>{T run()throws IOException,ApiException,SQLException;}
    /** Seals the activation while preserving unresolved operation evidence. / 封闭启用并保留未核对操作依据。 */
    @Override public void close()throws ApiException{scope.close();}
}
