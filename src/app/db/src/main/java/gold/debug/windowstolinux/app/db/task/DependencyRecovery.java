package gold.debug.windowstolinux.app.db.task;

import java.util.*;

import gold.debug.windowstolinux.appapi.db.SchemaIdentity;

/** One synchronous dependency call's exact provider and retained data generation. / 一次同步依赖调用的精确提供方及保留数据代次。
 * @param boot physical application boot / 物理应用启动
 * @param call original call identity / 原调用身份
 * @param provider exact provider Mod / 精确提供方 Mod
 * @param version actual loaded release / 实际加载发布
 * @param schema selected optional data generation / 选定可选数据代次 */
public record DependencyRecovery(UUID boot, UUID call, String provider, String version,
        Optional<SchemaIdentity> schema) {
    /** Validates ownership before any claim can be recorded. / 任一申请登记前校验归属。
     * @param boot current physical boot / 当前物理启动
     * @param call original call UUID / 原调用 UUID
     * @param provider exact provider / 精确提供方
     * @param version loaded release / 加载发布
     * @param schema exact optional generation / 精确可选代次 */
    public DependencyRecovery {
        Objects.requireNonNull(boot);
        Objects.requireNonNull(call);
        Objects.requireNonNull(schema);
        if (provider == null || provider.isBlank() || provider.length() > 512 || version == null || version.isBlank()
                || version.length() > 512 || schema.isPresent() && !schema.get().ownerId().equals(provider))
            throw new IllegalArgumentException("Invalid dependency identity");
    }

    /** Returns the exact shared Mod and Schema claims, with no additional servers. / 返回精确共享 Mod 及 Schema 申请，不增加服务器。
     * @return complete provider claims / 完整提供方申请 */
    public List<TaskResource> claims() {
        var result = new ArrayList<TaskResource>();
        result.add(new TaskResource("MOD", provider, provider, false));
        schema.ifPresent(
                value -> result.add(new TaskResource("SCHEMA", value.generation().toString(), provider, false)));
        return List.copyOf(result);
    }

    /** Encodes only stable identity, never callback arguments or results. / 仅编码稳定身份，不编码回调参数或结果。
     * @return original resource recovery envelope / 原资源恢复封套 */
    public ResourceRecovery encode() {
        return new ResourceRecovery("service.v1",
                Map.of("bootId", boot.toString(), "callId", call.toString(), "providerId", provider, "providerVersion",
                        version, "schemaVersion", schema.map(SchemaIdentity::schemaVersion).orElse(""),
                        "schemaGeneration", schema.map(value -> value.generation().toString()).orElse("")));
    }

    /** Decodes the exact supported shape without supplying missing facts. / 解码精确受支持形状，不补造缺失事实。
     * @param value original resource metadata / 原资源元数据
     * @return exact original call / 精确原调用 */
    public static DependencyRecovery decode(ResourceRecovery value) {
        var f = value.fields();
        if (!value.protocol().equals("service.v1") || !f.keySet().equals(
                Set.of("bootId", "callId", "providerId", "providerVersion", "schemaVersion", "schemaGeneration")))
            throw new IllegalArgumentException("Invalid dependency checkpoint");
        Optional<SchemaIdentity> schema = f.get("schemaVersion").isEmpty()
                ? Optional.empty()
                : Optional.of(new SchemaIdentity(f.get("providerId"), f.get("schemaVersion"),
                        UUID.fromString(f.get("schemaGeneration"))));
        var decoded = new DependencyRecovery(UUID.fromString(f.get("bootId")), UUID.fromString(f.get("callId")),
                f.get("providerId"), f.get("providerVersion"), schema);
        if (!decoded.encode().equals(value))
            throw new IllegalArgumentException("Noncanonical dependency checkpoint");
        return decoded;
    }
}
