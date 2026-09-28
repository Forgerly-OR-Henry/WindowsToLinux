package gold.debug.windowstolinux.mod.deployment.db;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.server.ServerRef;
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.*;
import org.junit.jupiter.api.Test;

/** Verifies complete original publication templates beyond the old 1 MiB execution request bound. / 验证超过旧执行请求1 MiB上限的完整原发布模板。 */
class DeploymentPublicationTemplateCodecTest extends DeploymentGraphFixture {
    @Test
    void escapedConfigurationSurvivesLargeOriginalTemplateWithoutStringWithinStringEncoding() throws Exception {
        var component = component("service", "server", List.of());
        var graph = new DeploymentApplicationGraph("service", "service", new DeploymentHealthCheck.Tcp(8080, 20, 2),
                List.of(component));
        var entries = new ArrayList<DeploymentConfigurationEntry>();
        for (int index = 0; index < 1500; index++)
            entries.add(new DeploymentConfigurationEntry("DATA_" + index, DeploymentConfigurationScopeType.RUNTIME,
                    new DeploymentConfigurationValue.Text(("quote\"slash\\" + (char) 1).repeat(70))));
        var configuration = DeploymentConfigurationSnapshot.create("service", 1, "1", OBSERVED, entries);
        var release = new DeploymentCurrentRelease("service", "a".repeat(64), OBSERVED);
        var proposal = new DeploymentPublicationProposal(graph, List.of(
                new DeploymentUnitCommit(component.unit(), component.runtime(), release, configuration, List.of())));
        var encoded = DeploymentPublicationTemplateCodec.encodeTemplate(proposal);
        assertTrue(encoded.length() > 1_048_576, "Template must exercise the previous request limit");
        assertFalse(encoded.contains("\\\"formatVersion\\\""), "Nested documents must remain JSON nodes");
        assertEquals(proposal, DeploymentPublicationTemplateCodec.decodeTemplate(encoded, UUID.randomUUID()));

        var server = new ServerRef("server");
        var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), UUID.randomUUID(), java.util.Optional.empty(),
                "service", server, DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "f".repeat(64));
        var request = DeploymentExecutionCodec.request(identity, "deployment-publication",
                List.of(DeploymentReferenceIntent.server(server, "application:service")), java.util.Optional.empty());
        var record = new DeploymentExecutionRecord(identity, "EXECUTING", java.util.Optional.empty(), request,
                DeploymentExecutionCodec.recovery(identity, 5, "EXECUTING", java.util.Map.of(0, UUID.randomUUID())),
                java.util.Optional.empty(), false, false, OBSERVED, OBSERVED);
        var withTemplate = DeploymentExecutionCodec.withPublicationTemplate(record, proposal);
        assertTrue(withTemplate.length() > 1_048_576);
        var stored = new DeploymentExecutionRecord(identity, record.phase(), record.outcome(), withTemplate,
                record.recoveryJson(), record.resultJson(), false, false, OBSERVED, OBSERVED);
        assertEquals(proposal, DeploymentExecutionCodec.publicationTemplate(stored).orElseThrow());
    }
}
