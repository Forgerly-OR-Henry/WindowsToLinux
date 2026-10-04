package gold.debug.windowstolinux.app.db.file;

import java.sql.*;
import java.time.Instant;
import java.util.*;

import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.appapi.file.action.FileOperation;
import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.server.model.ServerId;

/** Owner/task-scoped durable file operations and link associations. / 所有者/任务限定的持久文件操作及链接关联。 */
public final class FileStore {
    /** existing platform database owner / 既有平台数据库所有者.
     */
    private final PlatformDatabase database;

    /** exact activation owner / 精确启用所有者.
     */
    private final String owner;

    /** Exact loaded release owning operation evidence. / 操作依据所属的精确已加载发布版本。 */
    private final String version;

    /** Binds the actual capability owner. / 绑定实际能力所有者。
     * @param database existing platform database owner / 既有平台数据库所有者
     * @param owner exact activation owner / 精确启用所有者
     * @param version exact loaded release / 精确已加载发布版本
     */
    public FileStore(PlatformDatabase database, String owner, String version) {
        this.database = Objects.requireNonNull(database);
        this.owner = Objects.requireNonNull(owner);
        this.version = Objects.requireNonNull(version);
    }

    /** Commits a bounded operation before any filesystem effect. / 文件副作用前提交有界操作。
     * @param task original authentic task identity / 原真实任务身份
     * @param type exact operation category / 精确操作类别
     * @param source original observed source object / 原观测来源对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param plan exact preapproved operation plan / 精确预批准操作计划
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public UUID begin(UUID task, String type, FilePath source, Optional<FilePath> target, String plan) throws SQLException {
        return begin(task, type, source, target, plan, Optional.empty());
    }

    /** Commits new authorization evidence linked to the original operation and task. / 提交关联原操作及任务的新授权依据。
     * @param task current ordinary authorized task / 当前普通授权任务
     * @param original verified original operation owned by this release / 此发布所属的已核实原操作
     * @param plan exact newly approved unfinished link plan / 精确新获批的未完成链接计划
     * @return new continuation operation identity / 新续作操作身份
     * @throws SQLException if ownership or durable insertion cannot be confirmed / 归属或持久插入无法确认时 */
    public UUID beginContinuation(UUID task, FileOperation original, String plan) throws SQLException {
        if (ownedOperation(original.id()).isEmpty()) throw new SQLException("Original release ownership differs");
        return begin(task, "MAINTAIN_LINKS", original.source(), original.target(), plan, Optional.of(original));
    }

    /** Stores the original plan and optional parent in one pre-effect insertion. / 在单次副作用前插入中保存原计划及可选父操作。
     * @param task current genuine task identity / 当前真实任务身份
     * @param type actual operation category / 实际操作类别
     * @param source original source position / 原来源位置
     * @param target chosen destination when present / 存在时的选择目标
     * @param plan exact authorized plan / 精确授权计划
     * @param parent verified original operation for explicit continuation / 明确续作的已核实原操作
     * @return committed new operation identity / 已提交的新操作身份
     * @throws SQLException if the insertion cannot be committed / 插入无法提交时 */
    private UUID begin(UUID task, String type, FilePath source, Optional<FilePath> target, String plan, Optional<FileOperation> parent) throws SQLException {
        if (plan.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("Plan is too large");
        UUID id = UUID.randomUUID();
        try (var c = database.openConnection(); var s = c.prepareStatement("INSERT INTO file_operation(operation_id,task_id,owner_id,owner_version,parent_operation_id,original_task_id,operation_type,phase,source_host,source_path,target_host,target_path,plan_text,updated_at) VALUES(?,?,?,?,?,?,?,'PLANNED',?,?,?,?,?,?)")) {
            s.setObject(1, id);
            s.setObject(2, task);
            s.setString(3, owner);
            s.setString(4, version);
            s.setObject(5, parent.map(FileOperation::id).orElse(null));
            s.setObject(6, parent.map(FileOperation::taskId).orElse(null));
            s.setString(7, type);
            bindPath(s, 8, source);
            bindOptional(s, 10, target);
            s.setString(12, plan);
            s.setLong(13, System.currentTimeMillis());
            s.executeUpdate();
        }
        return id;
    }

    /** Records dispatch and observation in one database transaction. / 在单个事务中记录发出及观测。
     * @param id original durable operation identity / 原持久操作身份
     * @param task original authentic task identity / 原真实任务身份
     * @param phase confirmed or pending effect stage / 已确认或待核对副作用阶段
     * @param source original observed source object / 原观测来源对象
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @param detail bounded evidence or diagnostic detail / 有界依据或诊断详情
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public void record(UUID id, UUID task, String phase, FilePath source, Optional<FilePath> target, String detail) throws SQLException {
        if (!phase.matches("[A-Z_]{1,40}") || detail.length() > 16384)
            throw new IllegalArgumentException("Invalid file evidence");
        try (var c = database.openConnection()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("UPDATE file_operation SET phase=?,updated_at=? WHERE operation_id=? AND task_id=? AND owner_id=? AND owner_version=?")) {
                    s.setString(1, phase);
                    s.setLong(2, System.currentTimeMillis());
                    s.setObject(3, id);
                    s.setObject(4, task);
                    s.setString(5, owner);
                    s.setString(6, version);
                    if (s.executeUpdate() != 1) throw new SQLException("Original operation ownership differs");
                }
                try (var s = c.prepareStatement("INSERT INTO file_operation_step(operation_id,phase,source_host,source_path,target_host,target_path,detail,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                    s.setObject(1, id);
                    s.setString(2, phase);
                    bindPath(s, 3, source);
                    bindOptional(s, 5, target);
                    s.setString(7, detail);
                    s.setLong(8, System.currentTimeMillis());
                    s.executeUpdate();
                }
                c.commit();
            } catch (SQLException failure) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    /** Lists the caller's registered links on one host. / 列举调用方在单台主机的登记链接。
     * @param host host-qualified registry scope / 带主机登记作用域
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public List<FileInfo> links(FilePath host) throws SQLException {
        var result = new ArrayList<FileInfo>();
        try (var c = database.openConnection(); var s = c.prepareStatement("SELECT * FROM file_link WHERE owner_id=? AND host_id=? ORDER BY link_path LIMIT 100001")) {
            s.setString(1, owner);
            s.setString(2, host.server().map(ServerId::id).orElse(""));
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if (result.size() == 100000) throw new SQLException("Link registry exceeds capacity");
                    FilePath path = path(r.getString("host_id"), r.getString("link_path"));
                    result.add(new FileInfo(path, path, FileInfo.Kind.LINK, r.getLong("byte_count"), Instant.ofEpochMilli(r.getLong("modified_at")), r.getString("link_identity"), Optional.of(r.getString("link_target")), List.of()));
                }
            }
        }
        return List.copyOf(result);
    }

    /** Saves an observed association without granting write authority. / 保存观测关联，不授予写权限。
     * @param task original authentic task identity / 原真实任务身份
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public void saveLink(UUID task, FileInfo link, Optional<FileInfo> target) throws SQLException {
        if (link.kind() != FileInfo.Kind.LINK) throw new IllegalArgumentException("Only links may be registered");
        try (var c = database.openConnection(); var s = c.prepareStatement("MERGE INTO file_link(owner_id,host_id,link_path,link_identity,link_target,target_path,target_identity,byte_count,modified_at,task_id,last_state,updated_at) KEY(owner_id,host_id,link_path) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, owner);
            bindPath(s, 2, link.actual());
            s.setString(4, link.identity());
            s.setString(5, link.target().orElseThrow());
            s.setString(6, target.map(t -> t.actual().path()).orElse(null));
            s.setString(7, target.map(FileInfo::identity).orElse(null));
            s.setLong(8, link.bytes());
            s.setLong(9, link.modified().toEpochMilli());
            s.setObject(10, task);
            s.setString(11, target.isPresent() ? "CONFIRMED" : "UNVERIFIED");
            s.setLong(12, System.currentTimeMillis());
            s.executeUpdate();
        }
    }

    /** Removes only the bound owner's association. / 仅解除绑定所有者的关联。
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public void removeLink(FilePath link) throws SQLException {
        try (var c = database.openConnection(); var s = c.prepareStatement("DELETE FROM file_link WHERE owner_id=? AND host_id=? AND link_path=?")) {
            s.setString(1, owner);
            bindPath(s, 2, link);
            s.executeUpdate();
        }
    }

    /** Compares the original association's target identity without altering it. / 比较原关联目标身份而不修改。
     * @param link whether to operate on the link itself / 是否操作链接自身
     * @param target exact planned destination or link target / 精确预定位置或链接目标
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public boolean targetMatches(FileInfo link, FileInfo target) throws SQLException {
        try (var c = database.openConnection(); var s = c.prepareStatement("SELECT target_path,target_identity FROM file_link WHERE owner_id=? AND host_id=? AND link_path=?")) {
            s.setString(1, owner);
            bindPath(s, 2, link.actual());
            try (var r = s.executeQuery()) {
                return r.next() && target.actual().path().equals(r.getString(1)) && target.identity().equals(r.getString(2));
            }
        }
    }

    /** Lists one bounded page of original task operations, never executing them. / 有界列举原任务操作，绝不执行。
     * @param task original authentic task identity / 原真实任务身份
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public List<FileOperation> operations(UUID task) throws SQLException {
        var values = new ArrayList<UUID>();
        try (var c = database.openConnection(); var s = c.prepareStatement("SELECT operation_id FROM file_operation WHERE task_id=? AND owner_id=? AND owner_version=? ORDER BY updated_at,operation_id LIMIT 201")) {
            s.setObject(1, task);
            s.setString(2, owner);
            s.setString(3, version);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    if (values.size() == 200) throw new SQLException("Operation page exceeds capacity");
                    values.add(r.getObject(1, UUID.class));
                }
            }
        }
        var result = new ArrayList<FileOperation>();
        for (UUID id : values) result.add(operation(id, task).orElseThrow());
        return List.copyOf(result);
    }

    /** Reads exact original evidence for the bound task and owner. / 读取绑定任务及所有者的精确原依据。
     * @param id original durable operation identity / 原持久操作身份
     * @param task original authentic task identity / 原真实任务身份
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    public Optional<FileOperation> operation(UUID id, UUID task) throws SQLException {
        return ownedOperation(id).filter(operation -> operation.taskId().equals(task));
    }

    /** Reads original evidence by exact activation release for a newly authorized continuation task. / 按精确启用发布读取新授权续作任务所需原依据。
     * @param id original durable operation identity / 原持久操作身份
     * @return owner and release matched evidence or absence / 所有者及发布匹配的依据或不存在
     * @throws SQLException if original evidence cannot be read / 原依据无法读取时 */
    public Optional<FileOperation> ownedOperation(UUID id) throws SQLException {
        try (var c = database.openConnection(); var s = c.prepareStatement("SELECT * FROM file_operation WHERE operation_id=? AND owner_id=? AND owner_version=?")) {
            s.setObject(1, id);
            s.setString(2, owner);
            s.setString(3, version);
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                var steps = new ArrayList<FileOperation.Step>();
                try (var q = c.prepareStatement("SELECT * FROM file_operation_step WHERE operation_id=? ORDER BY step_id LIMIT 100001")) {
                    q.setObject(1, id);
                    try (var e = q.executeQuery()) {
                        while (e.next()) {
                            if (steps.size() == 100000) throw new SQLException("Evidence capacity exceeded");
                            steps.add(new FileOperation.Step(e.getString("phase"), path(e.getString("source_host"), e.getString("source_path")), optionalPath(e.getString("target_host"), e.getString("target_path")), e.getString("detail")));
                        }
                    }
                }
                return Optional.of(new FileOperation(id, r.getObject("task_id", UUID.class), r.getString("operation_type"), r.getString("phase"), path(r.getString("source_host"), r.getString("source_path")), optionalPath(r.getString("target_host"), r.getString("target_path")), steps));
            }
        }
    }

    /** Binds a host key and its exact absolute path into adjacent SQL parameters. / 将主机键及精确绝对位置绑定至相邻 SQL 参数。
     * @param s prepared SQL statement / 预编译 SQL 语句
     * @param index first SQL parameter position for this path pair / 此路径值对的首个 SQL 参数位置
     * @param path exact host-qualified position / 精确带主机位置
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private static void bindPath(PreparedStatement s, int index, FilePath path) throws SQLException {
        s.setString(index, path.server().map(ServerId::id).orElse(""));
        s.setString(index + 1, path.path());
    }

    /** Binds an optional destination as two complete values or two nulls. / 将可选目标绑定成两个完整值或两个空值。
     * @param s prepared SQL statement / 预编译 SQL 语句
     * @param index first SQL parameter position for this path pair / 此路径值对的首个 SQL 参数位置
     * @param path exact host-qualified position / 精确带主机位置
     * @throws SQLException if actual identity, admission, I/O or durable evidence cannot be verified / 实际身份、准入、I/O或持久依据无法核实时
     */
    private static void bindOptional(PreparedStatement s, int index, Optional<FilePath> path) throws SQLException {
        if (path.isPresent()) bindPath(s, index, path.orElseThrow());
        else {
            s.setString(index, null);
            s.setString(index + 1, null);
        }
    }

    /** Reconstructs the host-qualified absolute location from durable evidence. / 从持久依据重建带主机绝对位置。
     * @param host host-qualified registry scope / 带主机登记作用域
     * @param path exact host-qualified position / 精确带主机位置
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private static FilePath path(String host, String path) {
        return new FilePath(host == null || host.isEmpty() ? Optional.empty() : Optional.of(new ServerId(host)), path);
    }

    /** Preserves original destination absence when reconstructing durable evidence. / 重建持久依据时保留原目标缺失状态。
     * @param host host-qualified registry scope / 带主机登记作用域
     * @param value content whose exact digest is required / 需要精确摘要的内容
     * @return exact bounded result of the documented operation / 已描述操作的精确有界结果
     */
    private static Optional<FilePath> optionalPath(String host, String value) {
        return value == null ? Optional.empty() : Optional.of(path(host, value));
    }
}
