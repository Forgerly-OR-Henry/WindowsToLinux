package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.mod.deployment.api.*;
import gold.debug.windowstolinux.mod.deployment.api.VersionedRuntime;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentReleaseRecord.DeploymentReleaseState;
import gold.debug.windowstolinux.mod.deployment.service.core.execution.DeploymentArchivedRuntimeCodec;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;

/** Exercises the complete archival contract, including independent health bytes. / 验证包含独立健康字节的完整归档契约。 */
class DeploymentArchivedRuntimeCodecTest extends DeploymentGraphFixture {
    /** Restores the original digest only when both release documents survive. / 仅两份发布文档均完整保留时重建原摘要。 */
    @Test
    void archivesAndChecksOriginalRuntimeAndHealth() throws Exception {
        var graph = graph("application");
        var snapshot = DeploymentReleaseSnapshot.capture(graph, publications(graph, 1, List.of()));
        String runtime = DeploymentReleaseCodec.runtime(snapshot);
        String health = DeploymentReleaseCodec.health(snapshot);
        String digest = DeploymentReleaseCodec.digest(runtime, health);
        var record = new DeploymentReleaseRecord("application",
                new DeploymentReleaseIdentity(UUID.randomUUID(), digest), UUID.randomUUID(),
                DeploymentReleaseState.ACTIVE, runtime, health, OBSERVED);

        VersionedRuntime archived = DeploymentArchivedRuntimeCodec.encode(
                new DeploymentPublicationProposal(graph, publications(graph, 1, List.of())), List.of(),
                new DeploymentReleaseRepository(nullDatabase()));
        assertEquals(3, archived.formatVersion());
        assertEquals(snapshot, DeploymentReleaseCodec
                .snapshot(DeploymentArchivedRuntimeCodec.decode("application", digest, archived)));

        byte[] changed = archived.payload();
        int templateSize = java.nio.ByteBuffer.wrap(changed, "W2L-RELEASE-3\n".length(), 4).getInt();
        changed["W2L-RELEASE-3\n".length() + 4 + templateSize - 1] ^= 1;
        assertEquals(snapshot, DeploymentReleaseCodec
                .snapshot(DeploymentArchivedRuntimeCodec.decode("application", digest, archived)));
        assertThrows(Exception.class, () -> DeploymentReleaseCodec.snapshot(
                DeploymentArchivedRuntimeCodec.decode("application", digest, new VersionedRuntime(3, changed))));
        assertThrows(IOException.class, () -> DeploymentArchivedRuntimeCodec.decode("application", digest,
                new VersionedRuntime(1, archived.payload())));
    }

    @Test
    void restoresFullTypedConfigurationAndPurposeWithoutAnySourceDatabase() throws Exception {
        var graph = graph("application");
        var secret = new DeploymentSecretReference("database-password", 7);
        var proposal = new DeploymentPublicationProposal(graph, publications(graph, 4, List.of(secret)));
        var metadata = new DeploymentSecretRevision(secret, "opaque-original-credential", OBSERVED,
                Optional.of("deployment-database"));
        var repository = new DeploymentReleaseRepository(nullDatabase());
        var runtime = DeploymentArchivedRuntimeCodec.encode(proposal, List.of(metadata), repository);
        var snapshot = publicSnapshot(proposal, runtime);
        var restored = DeploymentArchivedRuntimeCodec.restore(snapshot, repository);
        assertEquals(proposal, restored.publication());
        assertEquals(List.of(metadata), restored.secrets());
        assertEquals(proposal.units().getFirst().configuration(),
                restored.publication().units().getFirst().configuration());
        assertEquals(List.of("web:uploads", "worker:uploads"),
                snapshot.bindings().stream().map(DeploymentBinding::id).toList());
    }

    @Test
    void rejectsFractionalAndTextCoercionInUntrustedCredentialMetadata() throws Exception {
        var graph = graph("application");
        var secret = new DeploymentSecretReference("password", 1);
        var proposal = new DeploymentPublicationProposal(graph, publications(graph, 1, List.of(secret)));
        var repository = new DeploymentReleaseRepository(nullDatabase());
        var runtime = DeploymentArchivedRuntimeCodec.encode(proposal,
                List.of(new DeploymentSecretRevision(secret, "credential", OBSERVED, Optional.of("database"))),
                repository);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String field : List.of("revision", "createdAt", "identifier", "credentialId", "purpose")) {
            byte[] payload = runtime.payload();
            int prefix = "W2L-RELEASE-3\n".length();
            int template = ByteBuffer.wrap(payload, prefix, 4).getInt();
            var metadata = json.readTree(java.util.Arrays.copyOfRange(payload, prefix + 8 + template, payload.length));
            var value = (com.fasterxml.jackson.databind.node.ObjectNode) metadata.path("secrets").get(0);
            if (field.equals("revision"))
                value.put(field, 1.5);
            else if (field.equals("createdAt"))
                value.put(field, Long.toString(OBSERVED.toEpochMilli()));
            else
                value.put(field, 123);
            var changed = replaceMetadata(runtime, json.writeValueAsBytes(metadata));
            var rejected = assertThrows(IOException.class,
                    () -> DeploymentArchivedRuntimeCodec.restore(publicSnapshot(proposal, changed), repository));
            assertEquals("Invalid archived secret identity", rejected.getMessage(), field);
        }
        var rejected = assertThrows(IOException.class,
                () -> DeploymentArchivedRuntimeCodec.restore(
                        publicSnapshot(proposal,
                                replaceMetadata(runtime,
                                        "{\"formatVersion\":1.5,\"secrets\":[]}".getBytes(StandardCharsets.UTF_8))),
                        repository));
        assertEquals("Invalid archived secret metadata", rejected.getMessage());
    }

    private VersionedRuntime replaceMetadata(VersionedRuntime original, byte[] metadata) {
        byte[] old = original.payload();
        int prefix = "W2L-RELEASE-3\n".length();
        int template = ByteBuffer.wrap(old, prefix, 4).getInt();
        return new VersionedRuntime(3, ByteBuffer.allocate(prefix + 8 + template + metadata.length)
                .put(old, 0, prefix + 4 + template).putInt(metadata.length).put(metadata).array());
    }

    private DeploymentApplicationSnapshot publicSnapshot(DeploymentPublicationProposal proposal,
            VersionedRuntime runtime) throws Exception {
        var graph = proposal.graph();
        var release = DeploymentReleaseSnapshot.capture(graph, proposal.units());
        String revision = DeploymentReleaseCodec.digest(DeploymentReleaseCodec.runtime(release),
                DeploymentReleaseCodec.health(release));
        var ownership = new StringBuilder("deployment-ownership-v1\n");
        for (var component : graph.components())
            ownership.append(component.componentId()).append('\n').append(component.unit().id()).append('\n')
                    .append(component.unit().ownershipManifestSha256()).append('\n');
        var bindings = graph.components().stream()
                .flatMap(component -> component.resources().fileBindings().stream()
                        .map(file -> new DeploymentBinding(component.componentId() + ":" + file.bindingId(),
                                component.componentId(), "FILE", file.physicalPath(component.unit().id()), "", true,
                                Optional.empty())))
                .sorted(java.util.Comparator.comparing(DeploymentBinding::componentId)
                        .thenComparing(DeploymentBinding::id))
                .toList();
        return new DeploymentApplicationSnapshot(
                new DeploymentApplicationRef(graph.applicationId(), new ServerRef("server")), "Archived", revision,
                sha(ownership.toString().getBytes(StandardCharsets.UTF_8)), ApplicationRunState.RUNNING, OBSERVED,
                runtime, bindings, sha(new DeploymentHealthCodec().write(graph.healthCheck())));
    }

    private String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private gold.debug.windowstolinux.appapi.db.ModDatabase nullDatabase() {
        return new gold.debug.windowstolinux.appapi.db.ModDatabase() {
            public gold.debug.windowstolinux.appapi.db.SchemaIdentity identity() {
                throw new UnsupportedOperationException();
            }

            public java.sql.Connection openConnection(gold.debug.windowstolinux.appapi.task.TaskContext task) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
