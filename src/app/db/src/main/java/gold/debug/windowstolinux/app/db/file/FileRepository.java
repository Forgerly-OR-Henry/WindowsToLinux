package gold.debug.windowstolinux.app.db.file;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.appapi.file.*;
import gold.debug.windowstolinux.appapi.server.ServerRef;

/** Owner/task-scoped durable file operations and link associations. / 所有者/任务限定的持久文件操作及链接关联。 */
public final class FileRepository {
    private final PlatformDatabase database;
    private final String owner;
    /** Binds the actual capability owner. / 绑定实际能力所有者。 */
    public FileRepository(PlatformDatabase database, String owner) { this.database = Objects.requireNonNull(database); this.owner = Objects.requireNonNull(owner); }
    /** Commits a bounded operation before any filesystem effect. / 文件副作用前提交有界操作。 */
    public UUID begin(UUID task, String type, FilePath source, Optional<FilePath> target, String plan) throws SQLException {
        if (plan.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("Plan is too large");
        UUID id = UUID.randomUUID();
        try (var c = database.openConnection(); var s = c.prepareStatement("INSERT INTO file_operation(operation_id,task_id,owner_id,operation_type,phase,source_host,source_path,target_host,target_path,plan_text,updated_at) VALUES(?,?,?,?,'PLANNED',?,?,?,?,?,?)")) {
            s.setObject(1,id); s.setObject(2,task); s.setString(3,owner); s.setString(4,type); bindPath(s,5,source); bindOptional(s,7,target); s.setString(9,plan); s.setLong(10,System.currentTimeMillis()); s.executeUpdate();
        }
        return id;
    }
    /** Records dispatch and observation in one database transaction. / 在单个事务中记录发出及观测。 */
    public void record(UUID id, UUID task, String phase, FilePath source, Optional<FilePath> target, String detail) throws SQLException {
        if (!phase.matches("[A-Z_]{1,40}") || detail.length() > 16384) throw new IllegalArgumentException("Invalid file evidence");
        try (var c = database.openConnection()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("UPDATE file_operation SET phase=?,updated_at=? WHERE operation_id=? AND task_id=? AND owner_id=?")) {
                    s.setString(1,phase); s.setLong(2,System.currentTimeMillis()); s.setObject(3,id); s.setObject(4,task); s.setString(5,owner);
                    if (s.executeUpdate()!=1) throw new SQLException("Original operation ownership differs");
                }
                try (var s = c.prepareStatement("INSERT INTO file_operation_step(operation_id,phase,source_host,source_path,target_host,target_path,detail,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                    s.setObject(1,id); s.setString(2,phase); bindPath(s,3,source); bindOptional(s,5,target); s.setString(7,detail); s.setLong(8,System.currentTimeMillis()); s.executeUpdate();
                }
                c.commit();
            } catch (SQLException failure) { try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); } throw failure; }
        }
    }
    /** Lists the caller's registered links on one host. / 列举调用方在单台主机的登记链接。 */
    public List<FileInfo> links(FilePath host) throws SQLException {
        var result = new ArrayList<FileInfo>();
        try (var c = database.openConnection(); var s = c.prepareStatement("SELECT * FROM file_link WHERE owner_id=? AND host_id=? ORDER BY link_path LIMIT 100001")) {
            s.setString(1,owner); s.setString(2,host.server().map(ServerRef::id).orElse(""));
            try (var r=s.executeQuery()) { while(r.next()) {
                if(result.size()==100000) throw new SQLException("Link registry exceeds capacity");
                FilePath path=path(r.getString("host_id"),r.getString("link_path"));
                result.add(new FileInfo(path,path,FileInfo.Kind.LINK,r.getLong("byte_count"),Instant.ofEpochMilli(r.getLong("modified_at")),r.getString("link_identity"),Optional.of(r.getString("link_target")),List.of()));
            } }
        }
        return List.copyOf(result);
    }
    /** Saves an observed association without granting write authority. / 保存观测关联，不授予写权限。 */
    public void saveLink(UUID task, FileInfo link, Optional<FileInfo> target) throws SQLException {
        if(link.kind()!=FileInfo.Kind.LINK) throw new IllegalArgumentException("Only links may be registered");
        try(var c=database.openConnection(); var s=c.prepareStatement("MERGE INTO file_link(owner_id,host_id,link_path,link_identity,link_target,target_path,target_identity,byte_count,modified_at,task_id,last_state,updated_at) KEY(owner_id,host_id,link_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,owner); bindPath(s,2,link.actual()); s.setString(4,link.identity()); s.setString(5,link.target().orElseThrow()); s.setString(6,target.map(t->t.actual().path()).orElse(null)); s.setString(7,target.map(FileInfo::identity).orElse(null)); s.setLong(8,link.bytes()); s.setLong(9,link.modified().toEpochMilli()); s.setObject(10,task); s.setString(11,target.isPresent()?"CONFIRMED":"BROKEN"); s.setLong(12,System.currentTimeMillis()); s.executeUpdate();
        }
    }
    /** Removes only the bound owner's association. / 仅解除绑定所有者的关联。 */
    public void removeLink(FilePath link) throws SQLException {
        try(var c=database.openConnection(); var s=c.prepareStatement("DELETE FROM file_link WHERE owner_id=? AND host_id=? AND link_path=?")) { s.setString(1,owner); bindPath(s,2,link); s.executeUpdate(); }
    }
    /** Compares the original association's target identity without altering it. / 比较原关联目标身份而不修改。 */
    public boolean targetMatches(FileInfo link,FileInfo target)throws SQLException{
        try(var c=database.openConnection();var s=c.prepareStatement("SELECT target_path,target_identity FROM file_link WHERE owner_id=? AND host_id=? AND link_path=?")){
            s.setString(1,owner);bindPath(s,2,link.actual());try(var r=s.executeQuery()){return r.next()&&target.actual().path().equals(r.getString(1))&&target.identity().equals(r.getString(2));}
        }
    }
    /** Lists one bounded page of original task operations, never executing them. / 有界列举原任务操作，绝不执行。 */
    public List<FileOperation> operations(UUID task) throws SQLException {
        var values=new ArrayList<UUID>();
        try(var c=database.openConnection(); var s=c.prepareStatement("SELECT operation_id FROM file_operation WHERE task_id=? AND owner_id=? ORDER BY updated_at,operation_id LIMIT 201")) { s.setObject(1,task);s.setString(2,owner);try(var r=s.executeQuery()){while(r.next()){if(values.size()==200) throw new SQLException("Operation page exceeds capacity");values.add(r.getObject(1,UUID.class));}} }
        var result=new ArrayList<FileOperation>(); for(UUID id:values) result.add(operation(id,task).orElseThrow());return List.copyOf(result);
    }
    /** Reads exact original evidence for the bound task and owner. / 读取绑定任务及所有者的精确原依据。 */
    public Optional<FileOperation> operation(UUID id, UUID task) throws SQLException {
        try(var c=database.openConnection();var s=c.prepareStatement("SELECT * FROM file_operation WHERE operation_id=? AND task_id=? AND owner_id=?")) {
            s.setObject(1,id);s.setObject(2,task);s.setString(3,owner);
            try(var r=s.executeQuery()) {
                if(!r.next())return Optional.empty();var steps=new ArrayList<FileOperation.Step>();
                try(var q=c.prepareStatement("SELECT * FROM file_operation_step WHERE operation_id=? ORDER BY step_id LIMIT 100001")){q.setObject(1,id);try(var e=q.executeQuery()){while(e.next()){if(steps.size()==100000)throw new SQLException("Evidence capacity exceeded");steps.add(new FileOperation.Step(e.getString("phase"),path(e.getString("source_host"),e.getString("source_path")),optionalPath(e.getString("target_host"),e.getString("target_path")),e.getString("detail")));}}}
                return Optional.of(new FileOperation(id,task,r.getString("operation_type"),r.getString("phase"),path(r.getString("source_host"),r.getString("source_path")),optionalPath(r.getString("target_host"),r.getString("target_path")),steps));
            }
        }
    }
    private static void bindPath(PreparedStatement s,int index,FilePath path)throws SQLException{s.setString(index,path.server().map(ServerRef::id).orElse(""));s.setString(index+1,path.path());}
    private static void bindOptional(PreparedStatement s,int index,Optional<FilePath> path)throws SQLException{if(path.isPresent())bindPath(s,index,path.orElseThrow());else{s.setString(index,null);s.setString(index+1,null);}}
    private static FilePath path(String host,String path){return new FilePath(host==null||host.isEmpty()?Optional.empty():Optional.of(new ServerRef(host)),path);}
    private static Optional<FilePath> optionalPath(String host,String value){return value==null?Optional.empty():Optional.of(path(host,value));}
}
