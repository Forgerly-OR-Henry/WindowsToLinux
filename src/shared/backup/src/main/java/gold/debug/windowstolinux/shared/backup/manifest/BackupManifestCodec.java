package gold.debug.windowstolinux.shared.backup.manifest;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

/**
 * Strict and deterministic JSON codec for {@code manifest.json}. / 用于 {@code manifest.json} 的严格确定性 JSON 编解码器。
 */
public final class BackupManifestCodec {
    /**
     * JSON mapper for backup manifest codec.
     * <p>备份清单编解码器使用的 JSON 映射器。
     */
    private static final ObjectMapper MAPPER = createMapper();

    /**
     * Encodes one validated manifest as deterministic UTF-8 JSON. / 将已校验清单编码为确定性 UTF-8 JSON。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @return one validated manifest as deterministic UTF-8 JSON / 将已校验清单编码为确定性 UTF-8 JSON
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] write(BackupManifest manifest) throws IOException {
        manifest = Objects.requireNonNull(manifest, "manifest");
        return MAPPER.writeValueAsBytes(ManifestV6.from(manifest));
    }

    /**
     * Decodes strict UTF-8 JSON and rejects unknown, duplicate or trailing content. / 解码严格 UTF-8 JSON并拒绝未知、重复或尾随内容。
     *
     * @param document document / 文档
     * @return strict UTF-8 JSON and rejects unknown, duplicate or trailing content / 严格 UTF-8 JSON并拒绝未知、重复或尾随内容
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public BackupManifest read(byte[] document) throws IOException {
        Objects.requireNonNull(document, "document");
        JsonNode root = MAPPER.readValue(document, JsonNode.class);
        if (root == null || !root.isObject()) {
            throw new IOException("backup manifest root must be one JSON object");
        }
        JsonNode schema = root.get("schemaVersion");
        if (schema == null || !schema.isTextual()) {
            throw new IOException("backup manifest schemaVersion is missing or invalid");
        }
        return switch (schema.textValue()) {
            case BackupManifest.CURRENT_SCHEMA_VERSION -> MAPPER.treeToValue(root, ManifestV6.class).toDomain();
            default -> throw new IOException("unsupported backup manifest schema version");
        };
    }

    /**
     * Encodes the signed payload with an explicit unsigned marker. / 使用显式未签名标记编码签名载荷。
     *
     * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
     * @return the signed payload with an explicit unsigned marker / 使用显式未签名标记编码签名载荷
     * @throws IOException if the required file or stream operation fails / 所需文件或流操作失败时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public byte[] signaturePayload(BackupManifest manifest) throws IOException {
        return write(Objects.requireNonNull(manifest, "manifest").withProvenance(BackupProvenance.unsigned()));
    }

    /**
     * Creates mapper.
     * <p>创建映射器。
     *
     * @return mapper / 映射器
     */
    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(1_048_576)
                        .maxDocumentLength(2_097_152).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        return new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /**
     * Represents the persisted version-six backup manifest layout.
     * <p>表示持久化的第六版备份清单布局。
     *
     * @param format format / 格式
     * @param schemaVersion the configuration schema version / 配置模式版本
     * @param createdAtUtc created at utc / 已创建时刻UTC
     * @param applicationId managed application identifier / 受管应用标识
     * @param inventory inventory / 清单
     * @param members members / 成员集合
     * @param provenance provenance / 来源证据
     */
    private record ManifestV6(String format, String schemaVersion, String createdAtUtc, String applicationId,
            InventoryV6 inventory, List<BackupMember> members, BackupProvenance provenance) {
        /**
         * Reconstructs this typed contract from the supplied source representation.
         * <p>根据所提供的源表示重建当前类型化契约。
         *
         * @param manifest validated ownership or backup inventory document / 已验证归属或备份资源清单文档
         * @return constructed or resolved manifest V 6 / 构造或解析得到的清单V6
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        private static ManifestV6 from(BackupManifest manifest) {
            if (!BackupManifest.CURRENT_SCHEMA_VERSION.equals(manifest.schemaVersion())) {
                throw new IllegalArgumentException("schema-v6 document requires an exact manifest");
            }
            return new ManifestV6(manifest.format(), manifest.schemaVersion(), manifest.createdAtUtc(),
                    manifest.applicationId(), InventoryV6.from(manifest.inventory()), manifest.members(),
                    manifest.provenance());
        }

        /**
         * Converts the current contract to domain.
         * <p>将当前契约转换为领域。
         *
         * @return constructed or resolved backup manifest / 构造或解析得到的备份清单
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        private BackupManifest toDomain() {
            return new BackupManifest(format, schemaVersion, createdAtUtc, applicationId,
                    Objects.requireNonNull(inventory, "inventory").toDomain(), members, provenance);
        }
    }

    /**
     * Represents the version-six backup inventory wire fields.
     * <p>表示第六版备份资源清单的传输字段。
     *
     * @param releaseManifests release manifests / 发布清单集合
     * @param configurationSnapshots configuration snapshots / 配置快照集合
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     * @param persistentFiles persistent files / 持久化文件集合
     * @param persistentVolumes persistent volumes / 持久化卷集合
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param identity identity / 身份
     * @param serviceDefinitions service definitions / 服务定义集合
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param applicationHealthComponentId application health component id / 应用健康组件标识
     * @param applicationHealthCheck independently reviewed whole-application probe / 独立审阅的整应用探测
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param recoveryRequirements recovery requirements / 恢复要求集合
     */
    private record InventoryV6(List<String> releaseManifests, List<String> configurationSnapshots,
            List<SecretReference> secretReferences, List<String> persistentFiles, List<String> persistentVolumes,
            BackupDatabase database, IdentityV6 identity, List<String> serviceDefinitions, List<ComponentV6> components,
            String applicationHealthComponentId, BackupHealthCheck applicationHealthCheck, BackupRuntime runtime,
            List<String> recoveryRequirements) {
        /**
         * Reconstructs this typed contract from the supplied source representation.
         * <p>根据所提供的源表示重建当前类型化契约。
         *
         * @param inventory inventory / 清单
         * @return constructed or resolved inventory V 6 / 构造或解析得到的清单V6
         */
        private static InventoryV6 from(BackupInventory inventory) {
            return new InventoryV6(inventory.releaseManifests(), inventory.configurationSnapshots(),
                    inventory.secretReferences(), inventory.persistentFiles(), inventory.persistentVolumes(),
                    inventory.database(), IdentityV6.from(inventory.identity()), inventory.serviceDefinitions(),
                    inventory.components().stream().map(ComponentV6::from).toList(),
                    inventory.applicationHealthComponentId(), inventory.applicationHealthCheck(), inventory.runtime(),
                    inventory.recoveryRequirements());
        }

        /**
         * Converts the current contract to domain.
         * <p>将当前契约转换为领域。
         *
         * @return constructed or resolved backup inventory / 构造或解析得到的备份清单
         * @throws NullPointerException if a required input is absent / 必需输入缺失时
         */
        private BackupInventory toDomain() {
            return new BackupInventory(releaseManifests, configurationSnapshots, secretReferences, persistentFiles,
                    persistentVolumes, database, Objects.requireNonNull(identity, "identity").toDomain(),
                    serviceDefinitions,
                    Objects.requireNonNull(components, "components").stream().map(ComponentV6::toDomain).toList(),
                    applicationHealthComponentId, applicationHealthCheck, runtime, recoveryRequirements);
        }
    }

    /**
     * Represents the version-six backup ownership and release identity fields.
     * <p>表示第六版备份的归属及发布身份字段。
     *
     * @param applicationId managed application identifier / 受管应用标识
     * @param serverId persisted server identifier / 持久化服务器标识
     * @param managedRoot managed root / 受管根目录
     * @param releaseSetSha256 release set sha 256 / 发布集合SHA256
     */
    private record IdentityV6(String applicationId, String serverId, String managedRoot, String releaseSetSha256) {
        /**
         * Reconstructs this typed contract from the supplied source representation.
         * <p>根据所提供的源表示重建当前类型化契约。
         *
         * @param identity identity / 身份
         * @return constructed or resolved identity V 6 / 构造或解析得到的身份V6
         */
        private static IdentityV6 from(BackupIdentity identity) {
            return new IdentityV6(identity.applicationId(), identity.serverId(), identity.managedRoot(),
                    identity.releaseSetSha256().orElseThrow(
                            () -> new IllegalArgumentException("schema-v6 identity lacks releaseSetSha256")));
        }

        /**
         * Converts the current contract to domain.
         * <p>将当前契约转换为领域。
         *
         * @return constructed or resolved backup identity / 构造或解析得到的备份身份
         */
        private BackupIdentity toDomain() {
            return new BackupIdentity(applicationId, serverId, managedRoot, releaseSetSha256);
        }
    }

    /**
     * Represents the version-six component configuration and dependency fields.
     * <p>表示第六版组件配置及依赖字段。
     *
     * @param componentId identifier within the reviewed component graph / 已审阅组件图内的标识
     * @param managedApplicationId managed application id / 受管应用标识
     * @param ownershipManifestSha256 digest binding the managed resource to its ownership manifest / 将受管资源绑定到归属清单的摘要
     * @param releaseManifestPath release manifest path / 发布清单路径
     * @param configurationSnapshotPath configuration snapshot path / 配置快照路径
     * @param serviceDefinitionPath service definition path / 服务定义路径
     * @param dependsOn depends on / 依赖对应
     * @param runtime reviewed language, process and health specification / 已审阅的语言、进程及健康规格
     * @param releaseSha256 identity digest of the exact successful release / 精确成功发布的身份摘要
     * @param secretReferences immutable identifiers and revisions of required secrets / 所需秘密的不可变标识及修订
     */
    private record ComponentV6(String componentId, String managedApplicationId, String ownershipManifestSha256,
            String releaseManifestPath, String configurationSnapshotPath, String serviceDefinitionPath,
            List<String> dependsOn, BackupComponentRuntime runtime, String releaseSha256,
            List<SecretReference> secretReferences) {
        /**
         * Reconstructs this typed contract from the supplied source representation.
         * <p>根据所提供的源表示重建当前类型化契约。
         *
         * @param component component / 组件
         * @return constructed or resolved component V 6 / 构造或解析得到的组件V6
         */
        private static ComponentV6 from(BackupComponent component) {
            return new ComponentV6(component.componentId(), component.managedApplicationId(),
                    component.ownershipManifestSha256(), component.releaseManifestPath(),
                    component.configurationSnapshotPath(), component.serviceDefinitionPath(), component.dependsOn(),
                    component.runtime(),
                    component.releaseSha256()
                            .orElseThrow(() -> new IllegalArgumentException("schema-v6 component lacks releaseSha256")),
                    component.secretReferences().orElseThrow(
                            () -> new IllegalArgumentException("schema-v6 component lacks secretReferences")));
        }

        /**
         * Converts the current contract to domain.
         * <p>将当前契约转换为领域。
         *
         * @return constructed or resolved backup component / 构造或解析得到的备份组件
         */
        private BackupComponent toDomain() {
            return new BackupComponent(componentId, managedApplicationId, ownershipManifestSha256, releaseManifestPath,
                    configurationSnapshotPath, serviceDefinitionPath, dependsOn, runtime, releaseSha256,
                    secretReferences);
        }
    }

}
