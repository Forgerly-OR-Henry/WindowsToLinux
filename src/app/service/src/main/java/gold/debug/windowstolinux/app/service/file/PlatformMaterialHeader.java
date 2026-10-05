package gold.debug.windowstolinux.app.service.file;

import java.util.*;

import gold.debug.windowstolinux.app.db.task.ResourceRecovery;
import gold.debug.windowstolinux.app.db.task.TaskRecoveryGraph;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;
import gold.debug.windowstolinux.appapi.file.material.*;
import gold.debug.windowstolinux.app.file.source.SourceMaterialManifest;

/** Strict original material header; filesystem location is never part of author facts. / 严格原素材头部，文件系统位置从不属于作者事实。
 * @param recovery exact immutable persisted recovery fields / 精确不可变持久恢复字段 */
record PlatformMaterialHeader(ResourceRecovery recovery) {
    /** Exact supported root fields. / 精确受支持根字段。 */
    private static final Set<String> FIELDS = Set.of("materialId", "operationId", "taskId", "producerId", "producerVersion",
        "producerDigest", "producerSchemaVersion", "producerSchemaGeneration", "consumerId", "consumerVersion",
        "consumerDigest", "consumerSchemaVersion", "consumerSchemaGeneration", "workspaceId", "physicalOwnerId",
        "physicalIdentity", "revision", "manifestSha256", "byteCount", "fileCount", "state", "recordRevision");

    /** Rejects unknown, incomplete or mismatched original facts. / 拒绝未知、不完整或不匹配原事实。
     * @param recovery original persisted header / 原持久头部 */
    PlatformMaterialHeader {
        var f = recovery.fields();
        if (!recovery.protocol().equals("material.v1") || !f.keySet().equals(FIELDS)
            || !Set.of("CAPTURING", "PUBLISHED", "RELEASE_REQUESTED", "RELEASED").contains(f.get("state"))
            || f.get("physicalIdentity").isBlank() || !f.get("physicalOwnerId").equals(f.get("producerId"))
            || !f.get("recordRevision").matches("0|[1-9][0-9]{0,18}"))
            throw new IllegalArgumentException("Invalid material root header");
        for (String key : List.of("materialId", "operationId", "taskId", "workspaceId"))
            uuid(f.get(key));
        for (String prefix : List.of("producer", "consumer")) {
            if (f.get(prefix + "Id").isBlank() || f.get(prefix + "Version").isBlank()
                || !f.get(prefix + "Digest").matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid material release identity");
            schema(f, prefix);
        }
        if (f.get("revision").isBlank() || f.get("revision").length() > 4096)
            throw new IllegalArgumentException("Invalid material revision");
        if (f.get("manifestSha256").isEmpty()) {
            if (f.get("state").equals("PUBLISHED"))
                throw new IllegalArgumentException("Published material requires a final manifest");
            if (!f.get("manifestSha256").isEmpty() || !f.get("byteCount").equals("0") || !f.get("fileCount").equals("0"))
                throw new IllegalArgumentException("Capturing material has no final manifest");
        } else {
            if (f.get("state").equals("CAPTURING"))
                throw new IllegalArgumentException("Capturing material cannot contain a final manifest");
            if (!f.get("manifestSha256").matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid material manifest digest");
            long bytes = Long.parseLong(f.get("byteCount"));
            int count = Integer.parseInt(f.get("fileCount"));
            if (bytes < 0 || count < 0 || count > SourceMaterialManifest.MAX_FILES
                || !Long.toString(bytes).equals(f.get("byteCount")) || !Integer.toString(count).equals(f.get("fileCount")))
                throw new IllegalArgumentException("Invalid material counts");
        }
        Long.parseLong(f.get("recordRevision"));
    }

    /** Builds only the first original capturing facts from trusted graph releases. / 仅从可信图发布建立首份原捕获事实。
     * @param publication original requested identities / 原请求身份
     * @param task original task / 原任务
     * @param producer trusted original producer / 可信原生产方
     * @param consumer trusted original consumer / 可信原消费方
     * @param workspace original physical workspace / 原物理工作目录
     * @param physical original native identity / 原原生身份
     * @return strict capturing header / 严格捕获头部 */
    static PlatformMaterialHeader capturing(MaterialPublication publication, UUID task, TaskRecoveryGraph.Release producer,
                                            TaskRecoveryGraph.Release consumer, UUID workspace, String physical) {
        var f = new TreeMap<String, String>();
        f.put("materialId", publication.materialId().toString());
        f.put("operationId", publication.operationId().toString());
        f.put("taskId", task.toString());
        release(f, "producer", producer);
        release(f, "consumer", consumer);
        f.put("workspaceId", workspace.toString());
        f.put("physicalOwnerId", producer.owner());
        f.put("physicalIdentity", physical);
        f.put("revision", publication.revision());
        f.put("manifestSha256", "");
        f.put("byteCount", "0");
        f.put("fileCount", "0");
        f.put("state", "CAPTURING");
        f.put("recordRevision", "0");
        return new PlatformMaterialHeader(new ResourceRecovery("material.v1", f));
    }

    /** Completes only the exact original capture. / 仅完成精确原捕获。
     * @param manifest physically verified canonical facts / 物理已核验规范事实
     * @return published header / 发布头部 */
    PlatformMaterialHeader published(SourceMaterialManifest.Manifest manifest) {
        if (!state().equals("CAPTURING"))
            throw new IllegalArgumentException("Material capture already concluded");
        var f = new TreeMap<>(recovery.fields());
        f.put("manifestSha256", manifest.sha256());
        f.put("byteCount", Long.toString(manifest.byteCount()));
        f.put("fileCount", Integer.toString(manifest.entries().size()));
        f.put("state", "PUBLISHED");
        f.put("recordRevision", Long.toString(Math.addExact(Long.parseLong(f.get("recordRevision")), 1)));
        return new PlatformMaterialHeader(new ResourceRecovery("material.v1", f));
    }

    /** Advances only the existing release lifecycle. / 仅推进既有释放生命周期。
     * @param state exact next release state / 精确下一释放状态
     * @return exact revised header / 精确修订头部 */
    PlatformMaterialHeader advance(String state) {
        if (!(state().equals("CAPTURING") && state.equals("RELEASE_REQUESTED")
            || state().equals("PUBLISHED") && state.equals("RELEASE_REQUESTED")
            || state().equals("RELEASE_REQUESTED") && state.equals("RELEASED")))
            throw new IllegalArgumentException("Invalid material state transition");
        var f = new TreeMap<>(recovery.fields());
        f.put("state", state);
        f.put("recordRevision", Long.toString(Math.addExact(Long.parseLong(f.get("recordRevision")), 1)));
        return new PlatformMaterialHeader(new ResourceRecovery("material.v1", f));
    }

    /** Returns only complete published reference metadata. / 仅返回完整发布引用元数据。
     * @return original immutable reference / 原不可变引用 */
    MaterialRef reference() {
        if (field("manifestSha256").isEmpty())
            throw new IllegalArgumentException("Material has no published reference");
        var f = recovery.fields();
        return new MaterialRef(uuid(f.get("materialId")), uuid(f.get("operationId")), uuid(f.get("taskId")),
            f.get("producerId"), f.get("producerVersion"), f.get("producerDigest"), schema(f, "producer"),
            f.get("revision"), f.get("manifestSha256"), Long.parseLong(f.get("byteCount")), Integer.parseInt(f.get("fileCount")));
    }

    /** Returns an exact original string fact. / 返回精确原字符串事实。
     * @param key supported field / 受支持字段
     * @return original value / 原值 */
    String field(String key) {
        return recovery.fields().get(key);
    }

    /** Returns the recorded lifecycle without time-based inference. / 返回记录生命周期，不据时间推导。
     * @return recorded state / 记录状态 */
    String state() {
        return field("state");
    }

    /** Exposes mutually consistent read-only original publication facts. / 暴露互相一致的只读原发布事实。
     * @return exact original state and optional final reference / 精确原状态及可选最终引用 */
    MaterialInspection inspection() {
        return new MaterialInspection(MaterialState.valueOf(state()), field("manifestSha256").isEmpty() ? Optional.empty() : Optional.of(reference()));
    }

    /** Checks both original consumer and producer against the retained task graph. / 对照保留任务图核验原消费方及生产方。
     * @param graph authenticated original graph / 已认证原图 */
    void requireGraph(TaskRecoveryGraph graph) {
        if (!graph.owner().equals(field("consumerId")))
            throw new IllegalArgumentException("Material root consumer differs");
        for (String prefix : List.of("producer", "consumer")) {
            var original = graph.release(field(prefix + "Id"));
            if (!original.version().equals(field(prefix + "Version")) || !original.digest().equals(field(prefix + "Digest"))
                || !original.schema().equals(schema(recovery.fields(), prefix)))
                throw new IllegalArgumentException("Material original program or data differs");
        }
    }

    /** Stores trusted original release facts. / 保存可信原发布事实。
     * @param fields destination fields / 目标字段
     * @param prefix producer or consumer / 生产方或消费方
     * @param original retained release / 保留发布 */
    private static void release(Map<String, String> fields, String prefix, TaskRecoveryGraph.Release original) {
        fields.put(prefix + "Id", original.owner());
        fields.put(prefix + "Version", original.version());
        fields.put(prefix + "Digest", original.digest());
        fields.put(prefix + "SchemaVersion", original.schema().map(SchemaIdentity::schemaVersion).orElse(""));
        fields.put(prefix + "SchemaGeneration", original.schema().map(value -> value.generation().toString()).orElse(""));
    }

    /** Decodes paired exact schema facts without substituting current selections. / 解码成对精确数据事实，不替换为当前选择。
     * @param fields original fields / 原字段
     * @param prefix original role / 原角色
     * @return original schema generation / 原数据代次 */
    private static Optional<SchemaIdentity> schema(Map<String, String> fields, String prefix) {
        String version = fields.get(prefix + "SchemaVersion"), generation = fields.get(prefix + "SchemaGeneration");
        if (version.isEmpty() != generation.isEmpty())
            throw new IllegalArgumentException("Incomplete material schema identity");
        return version.isEmpty() ? Optional.empty() : Optional.of(new SchemaIdentity(fields.get(prefix + "Id"), version, uuid(generation)));
    }

    /** Accepts only canonical persisted UUID spellings. / 仅接受持久 UUID 规范拼写。
     * @param value original spelling / 原拼写
     * @return exact UUID / 精确 UUID */
    static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value))
            throw new IllegalArgumentException("Noncanonical material identity");
        return id;
    }
}
