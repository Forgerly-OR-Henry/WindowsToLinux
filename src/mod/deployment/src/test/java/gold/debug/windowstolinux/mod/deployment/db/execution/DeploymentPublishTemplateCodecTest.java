package gold.debug.windowstolinux.mod.deployment.db.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.db.DeploymentGraphFixture;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishProposal;
import gold.debug.windowstolinux.mod.deployment.db.publish.DeploymentPublishTemplateCodec;
import gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefIntent;
import gold.debug.windowstolinux.mod.deployment.db.reference.DeploymentRefKey;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigEntry;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigScopeType;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigSnapshot;
import gold.debug.windowstolinux.mod.deployment.service.core.config.DeploymentConfigValue;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentApplicationGraph;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentCurrentRelease;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitCommit;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck;
import org.junit.jupiter.api.Test;

/** Verifies complete original publication templates beyond the old 1 MiB execution request bound. / 验证超过旧执行请求1 MiB上限的完整原发布模板。 */
class DeploymentPublishTemplateCodecTest extends DeploymentGraphFixture {
    @Test
    void originalVersionOneCanonicalBytesRemainFrozen() throws Exception {
        var component = component("service", "server", List.of());
        var graph = new DeploymentApplicationGraph("service", "service", new DeploymentHealthCheck.Tcp(8080, 20, 2),
            List.of(component));
        var configuration = DeploymentConfigSnapshot.create("service", 1, "1", OBSERVED, List.of());
        var proposal = new DeploymentPublishProposal(graph,
            List.of(new DeploymentUnitCommit(component.unit(), component.runtime(),
                new DeploymentCurrentRelease("service", "a".repeat(64), OBSERVED), configuration, List.of())));
        var encoded = DeploymentPublishTemplateCodec.encodeTemplate(proposal);
        assertEquals("fa382bb5cbeefbfaef68f4fdfffedc8fab62e49ca2937275487aeaa303f76841", HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(encoded.getBytes(StandardCharsets.UTF_8))));
        assertEquals(proposal, DeploymentPublishTemplateCodec.decodeTemplate(encoded, UUID.randomUUID()));
    }

    @Test
    void escapedConfigurationSurvivesLargeOriginalTemplateWithoutStringWithinStringEncoding() throws Exception {
        var component = component("service", "server", List.of());
        var graph = new DeploymentApplicationGraph("service", "service", new DeploymentHealthCheck.Tcp(8080, 20, 2),
            List.of(component));
        var entries = new ArrayList<DeploymentConfigEntry>();
        for (int index = 0; index < 1500; index++)
            entries.add(new DeploymentConfigEntry("DATA_" + index, DeploymentConfigScopeType.RUNTIME,
                new DeploymentConfigValue.Text(("quote\"slash\\" + (char) 1).repeat(70))));
        var configuration = DeploymentConfigSnapshot.create("service", 1, "1", OBSERVED, entries);
        var release = new DeploymentCurrentRelease("service", "a".repeat(64), OBSERVED);
        var proposal = new DeploymentPublishProposal(graph, List.of(
            new DeploymentUnitCommit(component.unit(), component.runtime(), release, configuration, List.of())));
        var encoded = DeploymentPublishTemplateCodec.encodeTemplate(proposal);
        assertTrue(encoded.indexOf("\"publishedAt\"") < encoded.indexOf("\"runtime\""),
            "Existing version-one field order is a durable canonical byte contract");
        assertTrue(encoded.length() > 1_048_576, "Template must exercise the previous request limit");
        assertFalse(encoded.contains("\\\"formatVersion\\\""), "Nested documents must remain JSON nodes");
        assertEquals(proposal, DeploymentPublishTemplateCodec.decodeTemplate(encoded, UUID.randomUUID()));

        var server = new ServerId("server");
        var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), UUID.randomUUID(), java.util.Optional.empty(),
            "service", server, DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC, "f".repeat(64));
        var request = DeploymentExecutionCodec.request(identity, "deployment-publication",
            List.of(DeploymentRefIntent.server(server, "application:service")), java.util.Optional.empty());
        var record = new DeploymentExecutionRecord(identity, "EXECUTING", java.util.Optional.empty(), request,
            DeploymentExecutionCodec.recovery(identity, "EXECUTING", java.util.Map.of(0, UUID.randomUUID())),
            java.util.Optional.empty(), false, false, OBSERVED, OBSERVED);
        var withTemplate = DeploymentExecutionCodec.withPublicationTemplate(record, proposal);
        assertTrue(withTemplate.length() > 1_048_576);
        var stored = new DeploymentExecutionRecord(identity, record.phase(), record.outcome(), withTemplate,
            record.recoveryJson(), record.resultJson(), false, false, OBSERVED, OBSERVED);
        assertEquals(proposal, DeploymentExecutionCodec.publicationTemplate(stored).orElseThrow());
    }

    @Test
    void completeTwoUnitGraphUsesOneRuntimeManifestAndExactPerUnitConfigurations() throws Exception {
        var graph = graph("whole-app");
        var units = new ArrayList<DeploymentUnitCommit>();
        for (var component : graph.components()) {
            var id = component.unit().id();
            var entries = new ArrayList<DeploymentConfigEntry>();
            entries.add(new DeploymentConfigEntry("NAME", DeploymentConfigScopeType.BUILD,
                new DeploymentConfigValue.Text(id + "\"\\" + (char) 1)));
            for (int index = 0; index < 600; index++)
                entries.add(new DeploymentConfigEntry("ESCAPED_BUILD_" + index,
                    DeploymentConfigScopeType.BUILD,
                    new DeploymentConfigValue.Text(("q\"\\" + (char) 1).repeat(200))));
            var config = DeploymentConfigSnapshot.create(id, 1, "1", OBSERVED, entries);
            units.add(new DeploymentUnitCommit(component.unit(), component.runtime(),
                new DeploymentCurrentRelease(id, (id.equals("web") ? "a" : "b").repeat(64), OBSERVED), config,
                List.of()));
        }
        var proposal = new DeploymentPublishProposal(graph, units);
        var encoded = DeploymentPublishTemplateCodec.encodeTemplate(proposal);
        assertEquals(2, com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(encoded)
            .path("formatVersion").intValue());
        var parsed = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(encoded);
        assertEquals(2, parsed.path("runtime").path("components").size());
        assertFalse(parsed.path("units").get(0).has("runtime"),
            "The complete graph must not be duplicated for every component");
        assertEquals(proposal, DeploymentPublishTemplateCodec.decodeTemplate(encoded, UUID.randomUUID()));
        var server = new ServerId("server");
        var identity = new DeploymentExecutionIdentity(UUID.randomUUID(), UUID.randomUUID(), java.util.Optional.empty(),
            graph.applicationId(), server, DeploymentExecutionIdentity.DeploymentExecutionMode.STATIC,
            "f".repeat(64));
        var request = DeploymentExecutionCodec.request(identity, "deployment-publication",
            List.of(DeploymentRefKey.application(server, graph.applicationId()),
                DeploymentRefKey.unit(server, "api"), DeploymentRefKey.unit(server, "web")),
            java.util.Optional.empty());
        assertEquals(DeploymentExecutionCodec.VERSION, com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(request)
            .path("formatVersion").intValue());
        var original = new DeploymentExecutionRecord(identity, "EXECUTING", java.util.Optional.empty(), request,
            DeploymentExecutionCodec.recovery(identity, "EXECUTING",
                java.util.Map.of(0, UUID.randomUUID(), 1, UUID.randomUUID(), 2, UUID.randomUUID())),
            java.util.Optional.empty(), false, false, OBSERVED, OBSERVED);
        var retained = new DeploymentExecutionRecord(identity, original.phase(), original.outcome(),
            DeploymentExecutionCodec.withPublicationTemplate(original, proposal), original.recoveryJson(),
            original.resultJson(), false, false, OBSERVED, OBSERVED);
        assertTrue(retained.requestJson().getBytes(StandardCharsets.UTF_8).length > 1_048_576);
        assertEquals(proposal, DeploymentExecutionCodec.publicationTemplate(retained).orElseThrow());
    }
}
