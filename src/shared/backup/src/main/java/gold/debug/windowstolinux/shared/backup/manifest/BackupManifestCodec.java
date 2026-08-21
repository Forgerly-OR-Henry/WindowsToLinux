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
        return MAPPER.writeValueAsBytes(BackupManifest.CURRENT_SCHEMA_VERSION.equals(manifest.schemaVersion())
                ? ManifestV4.from(manifest) : ManifestV3.from(manifest));
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
            case BackupManifest.CURRENT_SCHEMA_VERSION -> MAPPER.treeToValue(root, ManifestV4.class).toDomain();
            case BackupManifest.LEGACY_SCHEMA_VERSION -> MAPPER.treeToValue(root, ManifestV3.class).toDomain();
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

    private record ManifestV4(
            String format,
            String schemaVersion,
            String createdAtUtc,
            String applicationId,
            InventoryV4 inventory,
            List<BackupMember> members,
            BackupProvenance provenance
    ) {
        private static ManifestV4 from(BackupManifest manifest) {
            if (!BackupManifest.CURRENT_SCHEMA_VERSION.equals(manifest.schemaVersion())) {
                throw new IllegalArgumentException("schema-v4 document requires an exact manifest");
            }
            return new ManifestV4(manifest.format(), manifest.schemaVersion(), manifest.createdAtUtc(),
                    manifest.applicationId(), InventoryV4.from(manifest.inventory()), manifest.members(),
                    manifest.provenance());
        }

        private BackupManifest toDomain() {
            return new BackupManifest(format, schemaVersion, createdAtUtc, applicationId,
                    Objects.requireNonNull(inventory, "inventory").toDomain(), members, provenance);
        }
    }

    private record InventoryV4(
            List<String> releaseManifests,
            List<String> configurationSnapshots,
            List<SecretReference> secretReferences,
            List<String> persistentFiles,
            List<String> persistentVolumes,
            BackupDatabase database,
            IdentityV4 identity,
            List<String> serviceDefinitions,
            List<ComponentV4> components,
            String applicationHealthComponentId,
            BackupHealthCheck applicationHealthCheck,
            BackupRuntime runtime,
            List<String> recoveryRequirements
    ) {
        private static InventoryV4 from(BackupInventory inventory) {
            return new InventoryV4(inventory.releaseManifests(), inventory.configurationSnapshots(),
                    inventory.secretReferences(), inventory.persistentFiles(), inventory.persistentVolumes(),
                    inventory.database(), IdentityV4.from(inventory.identity()), inventory.serviceDefinitions(),
                    inventory.components().stream().map(ComponentV4::from).toList(),
                    inventory.applicationHealthComponentId(), inventory.applicationHealthCheck(),
                    inventory.runtime(), inventory.recoveryRequirements());
        }

        private BackupInventory toDomain() {
            return new BackupInventory(releaseManifests, configurationSnapshots, secretReferences,
                    persistentFiles, persistentVolumes, database,
                    Objects.requireNonNull(identity, "identity").toDomain(), serviceDefinitions,
                    Objects.requireNonNull(components, "components").stream().map(ComponentV4::toDomain).toList(),
                    applicationHealthComponentId, applicationHealthCheck, runtime, recoveryRequirements);
        }
    }

    private record IdentityV4(
            String applicationId, String serverId, String managedRoot, String releaseSetSha256
    ) {
        private static IdentityV4 from(BackupIdentity identity) {
            return new IdentityV4(identity.applicationId(), identity.serverId(), identity.managedRoot(),
                    identity.releaseSetSha256().orElseThrow(() ->
                            new IllegalArgumentException("schema-v4 identity lacks releaseSetSha256")));
        }

        private BackupIdentity toDomain() {
            return new BackupIdentity(applicationId, serverId, managedRoot, releaseSetSha256);
        }
    }

    private record ComponentV4(
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
        private static ComponentV4 from(BackupComponent component) {
            return new ComponentV4(component.componentId(), component.managedApplicationId(),
                    component.ownershipManifestSha256(), component.releaseManifestPath(),
                    component.configurationSnapshotPath(), component.serviceDefinitionPath(), component.dependsOn(),
                    component.runtime(), component.releaseSha256().orElseThrow(() ->
                            new IllegalArgumentException("schema-v4 component lacks releaseSha256")),
                    component.secretReferences().orElseThrow(() ->
                            new IllegalArgumentException("schema-v4 component lacks secretReferences")));
        }

        private BackupComponent toDomain() {
            return new BackupComponent(componentId, managedApplicationId, ownershipManifestSha256,
                    releaseManifestPath, configurationSnapshotPath, serviceDefinitionPath, dependsOn, runtime,
                    releaseSha256, secretReferences);
        }
    }

    private record ManifestV3(
            String format,
            String schemaVersion,
            String createdAtUtc,
            String applicationId,
            InventoryV3 inventory,
            List<BackupMember> members,
            BackupProvenance provenance
    ) {
        private static ManifestV3 from(BackupManifest manifest) {
            if (!BackupManifest.LEGACY_SCHEMA_VERSION.equals(manifest.schemaVersion())) {
                throw new IllegalArgumentException("schema-v3 document requires a legacy manifest");
            }
            return new ManifestV3(manifest.format(), manifest.schemaVersion(), manifest.createdAtUtc(),
                    manifest.applicationId(), InventoryV3.from(manifest.inventory()), manifest.members(),
                    manifest.provenance());
        }

        private BackupManifest toDomain() {
            return new BackupManifest(format, schemaVersion, createdAtUtc, applicationId,
                    Objects.requireNonNull(inventory, "inventory").toDomain(), members, provenance);
        }
    }

    private record InventoryV3(
            List<String> releaseManifests,
            List<String> configurationSnapshots,
            List<String> secretReferences,
            List<String> persistentFiles,
            List<String> persistentVolumes,
            BackupDatabase database,
            IdentityV3 identity,
            List<String> serviceDefinitions,
            List<ComponentV3> components,
            String applicationHealthComponentId,
            BackupHealthCheck applicationHealthCheck,
            BackupRuntime runtime,
            List<String> recoveryRequirements
    ) {
        private static InventoryV3 from(BackupInventory inventory) {
            return new InventoryV3(inventory.releaseManifests(), inventory.configurationSnapshots(),
                    inventory.legacySecretReferences(), inventory.persistentFiles(), inventory.persistentVolumes(),
                    inventory.database(), IdentityV3.from(inventory.identity()), inventory.serviceDefinitions(),
                    inventory.components().stream().map(ComponentV3::from).toList(),
                    inventory.applicationHealthComponentId(), inventory.applicationHealthCheck(),
                    inventory.runtime(), inventory.recoveryRequirements());
        }

        private BackupInventory toDomain() {
            return new BackupInventory(releaseManifests, configurationSnapshots, List.of(),
                    persistentFiles, persistentVolumes, database,
                    Objects.requireNonNull(identity, "identity").toDomain(), serviceDefinitions,
                    Objects.requireNonNull(components, "components").stream().map(ComponentV3::toDomain).toList(),
                    applicationHealthComponentId, applicationHealthCheck, runtime, recoveryRequirements,
                    secretReferences);
        }
    }

    private record IdentityV3(
            String applicationId, String serverId, String managedRoot, String releaseIdentity
    ) {
        private static IdentityV3 from(BackupIdentity identity) {
            return new IdentityV3(identity.applicationId(), identity.serverId(), identity.managedRoot(),
                    identity.legacyReleaseIdentity().orElseThrow(() ->
                            new IllegalArgumentException("schema-v3 identity lacks releaseIdentity")));
        }

        private BackupIdentity toDomain() {
            return BackupIdentity.legacy(applicationId, serverId, managedRoot, releaseIdentity);
        }
    }

    private record ComponentV3(
            String componentId,
            String managedApplicationId,
            String ownershipManifestSha256,
            String releaseManifestPath,
            String configurationSnapshotPath,
            String serviceDefinitionPath,
            List<String> dependsOn,
            BackupComponentRuntime runtime
    ) {
        private static ComponentV3 from(BackupComponent component) {
            if (component.hasExactActivationBindings()) {
                throw new IllegalArgumentException("schema-v3 component unexpectedly has exact activation bindings");
            }
            return new ComponentV3(component.componentId(), component.managedApplicationId(),
                    component.ownershipManifestSha256(), component.releaseManifestPath(),
                    component.configurationSnapshotPath(), component.serviceDefinitionPath(), component.dependsOn(),
                    component.runtime());
        }

        private BackupComponent toDomain() {
            return BackupComponent.legacy(componentId, managedApplicationId, ownershipManifestSha256,
                    releaseManifestPath, configurationSnapshotPath, serviceDefinitionPath, dependsOn, runtime);
        }
    }
}
