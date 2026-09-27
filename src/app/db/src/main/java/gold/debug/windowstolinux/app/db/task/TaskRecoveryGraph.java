package gold.debug.windowstolinux.app.db.task;

import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.db.SchemaIdentity;

/** Exact code and data identities captured before a Mod task is admitted. / Mod 任务准入前捕获的精确代码及数据身份。
 * @param owner original task owner / 原任务所有者
 * @param releases original owner and all transitive declared providers / 原所有者及全部传递声明提供方 */
public record TaskRecoveryGraph(String owner, List<Release> releases) {
    /** Requires a complete unique identity set containing the original owner. / 要求完整唯一身份集合包含原所有者。
     * @param owner original task owner / 原任务所有者
     * @param releases original release identities / 原发布身份 */
    public TaskRecoveryGraph {
        Objects.requireNonNull(owner);
        releases = List.copyOf(releases);
        var owners = new HashSet<String>();
        for (var release : releases)
            if (!owners.add(release.owner()))
                throw new IllegalArgumentException("Duplicate original Mod identity");
        if (!owners.contains(owner))
            throw new IllegalArgumentException("Original task owner is absent");
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
    }

    /** Encodes only original identities, never business values or credentials. / 仅编码原身份，不编码业务值或凭据。
     * @return fixed graph document / 固定图文档 */
    public Map<String, Object> encode() {
        return Map.of("owner", owner, "releases", releases.stream()
                .map(value -> Map.of("owner", value.owner(), "version", value.version(), "digest", value.digest(),
                        "schemaVersion", value.schema().map(SchemaIdentity::schemaVersion).orElse(""),
                        "schemaGeneration", value.schema().map(schema -> schema.generation().toString()).orElse("")))
                .toList());
    }

    /** Decodes the exact supported shape without inferring missing versions or data. / 解码精确受支持形状，不推断缺失版本或数据。
     * @param graph original JSON graph / 原 JSON 图
     * @return validated retained identities / 已校验保留身份 */
    public static TaskRecoveryGraph decode(JsonNode graph) {
        if (!graph.isObject() || graph.size() != 2 || !graph.path("owner").isTextual()
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
        return new TaskRecoveryGraph(graph.path("owner").textValue(), releases);
    }

    /** One exact original release and its optional business generation. / 一个精确原发布及其可选业务代次。
     * @param owner original Mod identity / 原 Mod 身份
     * @param version exact running release / 精确运行发布
     * @param digest original complete program digest / 原完整程序摘要
     * @param schema original optional business generation / 原可选业务代次 */
    public record Release(String owner, String version, String digest, Optional<SchemaIdentity> schema) {
        /** Validates bounded original identity without accepting missing code evidence. / 校验有界原身份，不接受缺失代码依据。
         * @param owner original Mod identity / 原 Mod 身份
         * @param version exact running release / 精确运行发布
         * @param digest original program digest / 原程序摘要
         * @param schema optional original generation / 可选原代次 */
        public Release {
            Objects.requireNonNull(schema);
            if (owner == null || owner.isBlank() || owner.length() > 512 || version == null || version.isBlank()
                    || version.length() > 512 || digest == null || !digest.matches("[0-9a-f]{64}")
                    || schema.isPresent() && !schema.get().ownerId().equals(owner))
                throw new IllegalArgumentException("Invalid original Mod release");
        }
    }
}
