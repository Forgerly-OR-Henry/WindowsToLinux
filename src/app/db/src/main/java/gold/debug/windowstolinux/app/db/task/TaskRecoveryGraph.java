package gold.debug.windowstolinux.app.db.task;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.db.schema.SchemaIdentity;

/** Exact code and data identities captured before a Mod task is admitted. / Mod 任务准入前捕获的精确代码及数据身份。
 * @param owner original task owner / 原任务所有者
 * @param releases original owner, required closure and actually used optional providers / 原所有者、必需闭包及实际使用的可选提供方
 * @param edges recorded required and actually used optional edges / 记录的必需及实际使用可选边
 */
public record TaskRecoveryGraph(String owner, List<Release> releases, List<Edge> edges) {
    /** Collects a recovery session's identity union; each admitted task retains its own edges. / 汇集恢复会话身份，各准入任务独立保留原边。
     * @param owner exact original Mod owner / 精确原 Mod 所有者
     * @param releases exact retained original release identities / 精确保留原发布身份
     */
    public TaskRecoveryGraph(String owner, List<Release> releases) {
        this(owner, releases, List.of());
    }

    /** Requires a complete unique identity set containing the original owner. / 要求完整唯一身份集合包含原所有者。
     * @param owner original task owner / 原任务所有者
     * @param releases original release identities / 原发布身份
     * @param edges recorded required and actually used optional edges / 记录的必需及实际使用可选边
     */
    public TaskRecoveryGraph {
        Objects.requireNonNull(owner);
        releases = List.copyOf(releases);
        edges = List.copyOf(edges);
        var owners = new HashSet<String>();
        for (var release : releases)
            if (!owners.add(release.owner()))
                throw new IllegalArgumentException("Duplicate original Mod identity");
        if (!owners.contains(owner))
            throw new IllegalArgumentException("Original task owner is absent");
        if (releases.size() > 512 || edges.size() > 32768)
            throw new IllegalArgumentException("Invalid original graph size");
        var pairs = new HashSet<String>();
        for (var edge : edges)
            if (!owners.contains(edge.caller()) || !owners.contains(edge.provider())
                || !pairs.add(edge.caller() + "\0" + edge.provider()))
                throw new IllegalArgumentException("Invalid original graph edge");
        for (String identity : owners)
            visit(identity, edges, new HashSet<>(), new HashSet<>());
    }

    /** Rejects cycles and collects all recorded reachable releases. / 拒绝循环并收集全部记录可达发布。
     * @param owner exact original Mod owner / 精确原 Mod 所有者
     * @param edges recorded required and actually used optional edges / 记录的必需及实际使用可选边
     * @param path active original dependency path / 活动原依赖路径
     * @param reached reached original identity / reached 原身份
     */
    private static void visit(String owner, List<Edge> edges, Set<String> path, Set<String> reached) {
        if (!path.add(owner)) throw new IllegalArgumentException("Original graph contains a cycle");
        if (reached.add(owner))
            for (var edge : edges) if (edge.caller().equals(owner)) visit(edge.provider(), edges, path, reached);
        path.remove(owner);
    }

    /** Original admitted dependency semantics. / 原准入依赖语义。
     */
    public enum EdgeKind {
        /** Mandatory original provider edge. / 原必需提供方边。
         */
        REQUIRED,
        /** Optional edge durably recorded before actual use. / 实际使用前持久记录的可选边。
         */
        OPTIONAL_USED
    }

    /** Exact original caller and provider edge. / 精确原调用方与提供方边。
     * @param caller authenticated original caller / 已认证原调用方
     * @param provider exact original provider / 精确原提供方
     * @param kind original admitted edge semantics / 原准入边语义
     */
    public record Edge(String caller, String provider, EdgeKind kind) {
        /** Validates and copies the exact original boundary identity. / 校验并复制精确原边界身份。
         * @param caller authenticated original caller / 已认证原调用方
         * @param provider exact original provider / 精确原提供方
         * @param kind original admitted edge semantics / 原准入边语义
         */
        public Edge {
            Objects.requireNonNull(kind);
            if (caller == null || caller.isBlank() || provider == null || provider.isBlank() || caller.equals(provider))
                throw new IllegalArgumentException("Invalid original graph edge identity");
        }
    }

    /** Extends actual optional use with the exact provider required closure. / 用精确提供方必需闭包扩展实际可选使用。
     * @param caller authenticated original caller / 已认证原调用方
     * @param provider exact original provider / 精确原提供方
     * @param kind original admitted edge semantics / 原准入边语义
     * @return verified original result / 已核验原结果
     */
    public TaskRecoveryGraph extend(String caller, TaskRecoveryGraph provider, EdgeKind kind) {
        release(caller);
        var all = new LinkedHashMap<String, Release>();
        releases.forEach(r -> all.put(r.owner(), r));
        for (var r : provider.releases()) {
            var prior = all.putIfAbsent(r.owner(), r);
            if (prior != null && !prior.sameIdentity(r)) throw new IllegalArgumentException("Original release changed");
        }
        var next = new LinkedHashSet<>(edges);
        var edge = new Edge(caller, provider.owner(), kind);
        if (edges.stream().anyMatch(e -> e.caller().equals(caller) && e.provider().equals(provider.owner()) && !e.equals(edge)))
            throw new IllegalArgumentException("Original dependency kind changed");
        next.add(edge);
        next.addAll(provider.edges());
        return new TaskRecoveryGraph(owner, List.copyOf(all.values()), List.copyOf(next));
    }

    /** Returns exact shared original Mod and schema claims. / 返回精确共享原 Mod 及数据申请。
     * @return verified original result / 已核验原结果
     */
    public List<TaskResource> claims() {
        var claims = new ArrayList<TaskResource>();
        for (var r : releases) {
            claims.add(new TaskResource("MOD", r.owner(), r.owner(), false));
            r.schema().ifPresent(s -> claims.add(new TaskResource("SCHEMA", s.generation().toString(), r.owner(), false)));
        }
        return List.copyOf(claims);
    }

    /** Extracts recorded required descendants of one original provider. / 提取一个原提供方已记录的必需后继。
     * @param provider exact original provider / 精确原提供方
     * @return verified original result / 已核验原结果
     */
    public TaskRecoveryGraph requiredClosure(String provider) {
        var owners = new HashSet<String>();
        var queue = new ArrayDeque<String>();
        queue.add(provider);
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (owners.add(next)) edges.stream().filter(e -> e.caller().equals(next) && e.kind() == EdgeKind.REQUIRED)
                .forEach(e -> queue.add(e.provider()));
        }
        return new TaskRecoveryGraph(provider, releases.stream().filter(r -> owners.contains(r.owner())).toList(),
            edges.stream().filter(e -> e.kind() == EdgeKind.REQUIRED && owners.contains(e.caller())).toList());
    }

    /** Reads one original release without consulting current selections. / 不查询当前选择，读取一个原发布。
     * @param id original Mod identity / 原 Mod 身份
     * @return retained original release / 保留原发布 */
    public Release release(String id) {
        return releases.stream().filter(value -> value.owner().equals(id)).findFirst().orElseThrow();
    }

    /** Checks that captured code and data belong to the exact initial task claims. / 核对捕获代码及数据属于精确初始任务申请。
     * @param taskOwner original task owner / 原任务所有者
     * @param version original task version / 原任务版本
     * @param claims original initial claims / 原初始申请 */
    public void requireOwner(String taskOwner, String version, List<TaskResource> claims) {
        var original = release(owner);
        var schemas = claims.stream().filter(claim -> claim.kind().equals("SCHEMA") && claim.ownerId().equals(owner))
            .map(TaskResource::id).toList();
        if (!owner.equals(taskOwner) || !original.version().equals(version)
            || !claims.contains(new TaskResource("MOD", owner, owner, false))
            || !schemas.equals(original.schema().stream().map(value -> value.generation().toString()).toList()))
            throw new IllegalArgumentException("Recovery graph differs from original task identity");
        var reached = new HashSet<String>();
        visit(owner, edges, new HashSet<>(), reached);
        if (!reached.equals(releases.stream().map(Release::owner).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalArgumentException("Original task graph contains unrelated releases");
        if (!new HashSet<>(claims.stream().filter(c -> Set.of("MOD", "SCHEMA").contains(c.kind())).toList())
            .equals(new HashSet<>(claims())))
            throw new IllegalArgumentException("Original graph and task-wide pins differ");
    }

    /** Encodes only original identities, never business values or credentials. / 仅编码原身份，不编码业务值或凭据。
     * @return fixed graph document / 固定图文档 */
    public Map<String, Object> encode() {
        var document = new LinkedHashMap<String, Object>();
        document.put("owner", owner);
        document.put("releases", releases.stream()
            .map(value -> Map.of("owner", value.owner(), "version", value.version(), "digest", value.digest(),
                "schemaVersion", value.schema().map(SchemaIdentity::schemaVersion).orElse(""),
                "schemaGeneration", value.schema().map(schema -> schema.generation().toString()).orElse("")))
            .toList());
        document.put("edges", edges.stream().map(e -> Map.of("caller", e.caller(), "provider", e.provider(),
            "kind", e.kind().name())).toList());
        return Collections.unmodifiableMap(document);
    }

    /** Decodes the exact supported shape without inferring missing versions or data. / 解码精确受支持形状，不推断缺失版本或数据。
     * @param graph original JSON graph / 原 JSON 图
     * @return validated retained identities / 已校验保留身份 */
    public static TaskRecoveryGraph decode(JsonNode graph) {
        if (!graph.isObject() || graph.size() != 3 || !graph.path("owner").isTextual()
            || !graph.path("releases").isArray())
            throw new IllegalArgumentException("Invalid original task graph");
        var releases = new ArrayList<Release>();
        for (var value : graph.path("releases")) {
            if (!value.isObject() || value.size() != 5)
                throw new IllegalArgumentException("Invalid original release shape");
            for (var field : List.of("owner", "version", "digest", "schemaVersion", "schemaGeneration"))
                if (!value.path(field).isTextual())
                    throw new IllegalArgumentException("Original release fields must be strings");
            String owner = value.path("owner").textValue();
            String version = value.path("schemaVersion").textValue();
            String generation = value.path("schemaGeneration").textValue();
            if (version.isEmpty() != generation.isEmpty())
                throw new IllegalArgumentException("Incomplete original data identity");
            var schema = version.isEmpty()
                ? Optional.<SchemaIdentity>empty()
                : Optional.of(new SchemaIdentity(owner, version, UUID.fromString(generation)));
            if (schema.isPresent() && !schema.get().generation().toString().equals(generation))
                throw new IllegalArgumentException("Noncanonical original generation");
            releases.add(
                new Release(owner, value.path("version").textValue(), value.path("digest").textValue(), schema));
        }
        if (!graph.path("edges").isArray()) throw new IllegalArgumentException("Missing original edges");
        var edges = new ArrayList<Edge>();
        for (var e : graph.path("edges")) {
            if (!e.isObject() || e.size() != 3 || !e.path("caller").isTextual() || !e.path("provider").isTextual()
                || !e.path("kind").isTextual()) throw new IllegalArgumentException("Invalid original edge shape");
            edges.add(new Edge(e.path("caller").textValue(), e.path("provider").textValue(),
                EdgeKind.valueOf(e.path("kind").textValue())));
        }
        return new TaskRecoveryGraph(graph.path("owner").textValue(), releases, edges);
    }

    /** One exact original release and its optional business generation. / 一个精确原发布及其可选业务代次。
     * @param owner original Mod identity / 原 Mod 身份
     * @param version exact running release / 精确运行发布
     * @param digest historical registration receipt retained in existing formats / 既有格式保留的历史登记凭据
     * @param schema original optional business generation / 原可选业务代次 */
    public record Release(String owner, String version, String digest, Optional<SchemaIdentity> schema) {
        /** Validates bounded original identity without accepting missing code evidence. / 校验有界原身份，不接受缺失代码依据。
         * @param owner original Mod identity / 原 Mod 身份
         * @param version exact running release / 精确运行发布
         * @param digest historical registration receipt / 历史登记凭据
         * @param schema optional original generation / 可选原代次 */
        public Release {
            Objects.requireNonNull(schema);
            if (owner == null || owner.isBlank() || owner.length() > 512 || version == null || version.isBlank()
                || version.length() > 512 || digest == null || !digest.matches("[0-9a-f]{64}")
                || schema.isPresent() && !schema.get().ownerId().equals(owner))
                throw new IllegalArgumentException("Invalid original Mod release");
        }

        /** Compares Mod identity, version and data generation without treating code receipts as identity. / 比较Mod身份、版本和数据代次，不以代码凭据作为身份。
         * @param other compared release / 待比较发布
         * @return whether the same Mod version and data are selected / 是否选定同一Mod版本及数据 */
        public boolean sameIdentity(Release other) {
            return other != null && owner.equals(other.owner()) && version.equals(other.version())
                && schema.equals(other.schema());
        }
    }
}
