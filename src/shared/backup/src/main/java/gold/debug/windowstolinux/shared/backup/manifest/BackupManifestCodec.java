package gold.debug.windowstolinux.shared.backup.manifest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import gold.debug.windowstolinux.shared.config.secretref.SecretReference;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Strict and deterministic JSON codec for {@code manifest.json}. / 用于 {@code manifest.json} 的严格确定性 JSON 编解码器。 */
public final class BackupManifestCodec {
    private static final ObjectMapper MAPPER = createMapper();

    /** Encodes one validated manifest as deterministic UTF-8 JSON. / 将已校验清单编码为确定性 UTF-8 JSON。 */
    public byte[] write(BackupManifest manifest) throws IOException {
        manifest = Objects.requireNonNull(manifest, "manifest");
        return MAPPER.writeValueAsBytes(ManifestV6.from(manifest));
    }

    /** Decodes strict UTF-8 JSON and rejects unknown, duplicate or trailing content. / 解码严格 UTF-8 JSON并拒绝未知、重复或尾随内容。 */
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

    /** Encodes the signed payload with an explicit unsigned marker. / 使用显式未签名标记编码签名载荷。 */
    public byte[] signaturePayload(BackupManifest manifest) throws IOException {
        return write(Objects.requireNonNull(manifest, "manifest").withProvenance(BackupProvenance.unsigned()));
    }

    private static ObjectMapper createMapper() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(24).maxStringLength(1_048_576).maxDocumentLength(2_097_152).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    private record ManifestV6(
            String format,
            String schemaVersion,
            String createdAtUtc,
            String applicationId,
            InventoryV6 inventory,
            List<BackupMember> members,
            BackupProvenance provenance
    ) {
        private static ManifestV6 from(BackupManifest manifest) {
            if (!BackupManifest.CURRENT_SCHEMA_VERSION.equals(manifest.schemaVersion())) {
                throw new IllegalArgumentException("schema-v6 document requires an exact manifest");
            }
            return new ManifestV6(manifest.format(), manifest.schemaVersion(), manifest.createdAtUtc(),
                    manifest.applicationId(), InventoryV6.from(manifest.inventory()), manifest.members(),
                    manifest.provenance());
        }

        private BackupManifest toDomain() {
            return new BackupManifest(format, schemaVersion, createdAtUtc, applicationId,
                    Objects.requireNonNull(inventory, "inventory").toDomain(), members, provenance);
        }
    }

    private record InventoryV6(
            List<String> releaseManifests,
            List<String> configurationSnapshots,
            List<SecretReference> secretReferences,
            List<String> persistentFiles,
            List<String> persistentVolumes,
            BackupDatabase database,
            IdentityV6 identity,
            List<String> serviceDefinitions,
            List<ComponentV6> components,
            String applicationHealthComponentId,
            BackupHealthCheck applicationHealthCheck,
            BackupRuntime runtime,
            List<String> recoveryRequirements
    ) {
        private static InventoryV6 from(BackupInventory inventory) {
            return new InventoryV6(inventory.releaseManifests(), inventory.configurationSnapshots(),
                    inventory.secretReferences(), inventory.persistentFiles(), inventory.persistentVolumes(),
                    inventory.database(), IdentityV6.from(inventory.identity()), inventory.serviceDefinitions(),
                    inventory.components().stream().map(ComponentV6::from).toList(),
                    inventory.applicationHealthComponentId(), inventory.applicationHealthCheck(),
                    inventory.runtime(), inventory.recoveryRequirements());
        }

        private BackupInventory toDomain() {
            return new BackupInventory(releaseManifests, configurationSnapshots, secretReferences,
                    persistentFiles, persistentVolumes, database,
                    Objects.requireNonNull(identity, "identity").toDomain(), serviceDefinitions,
                    Objects.requireNonNull(components, "components").stream().map(ComponentV6::toDomain).toList(),
                    applicationHealthComponentId, applicationHealthCheck, runtime, recoveryRequirements);
        }
    }

    private record IdentityV6(
            String applicationId, String serverId, String managedRoot, String releaseSetSha256
    ) {
        private static IdentityV6 from(BackupIdentity identity) {
            return new IdentityV6(identity.applicationId(), identity.serverId(), identity.managedRoot(),
                    identity.releaseSetSha256().orElseThrow(() ->
                            new IllegalArgumentException("schema-v6 identity lacks releaseSetSha256")));
        }

        private BackupIdentity toDomain() {
            return new BackupIdentity(applicationId, serverId, managedRoot, releaseSetSha256);
        }
    }

    private record ComponentV6(
            String componentId,
            String managedApplicationId,
            String ownershipManifestSha256,
            String releaseManifestPath,
            String configurationSnapshotPath,
            String serviceDefinitionPath,
            List<String> dependsOn,
            BackupComponentRuntime runtime,
            String releaseSha256,
            List<SecretReference> secretReferences
    ) {
        private static ComponentV6 from(BackupComponent component) {
            return new ComponentV6(component.componentId(), component.managedApplicationId(),
                    component.ownershipManifestSha256(), component.releaseManifestPath(),
                    component.configurationSnapshotPath(), component.serviceDefinitionPath(), component.dependsOn(),
                    component.runtime(), component.releaseSha256().orElseThrow(() ->
                            new IllegalArgumentException("schema-v6 component lacks releaseSha256")),
                    component.secretReferences().orElseThrow(() ->
                            new IllegalArgumentException("schema-v6 component lacks secretReferences")));
        }

        private BackupComponent toDomain() {
            return new BackupComponent(componentId, managedApplicationId, ownershipManifestSha256,
                    releaseManifestPath, configurationSnapshotPath, serviceDefinitionPath, dependsOn, runtime,
                    releaseSha256, secretReferences);
        }
    }

}
