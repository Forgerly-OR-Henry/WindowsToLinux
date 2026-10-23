package gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence;

import java.sql.SQLException;
import java.util.*;

import gold.debug.windowstolinux.mod.deployment.db.document.DeploymentJsonDocument;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog.Event;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog.Identity;

/** Pure original quota validation shared by candidate and partial-preparation evidence. */
final class DeploymentAgentPreparationBinding {
    private DeploymentAgentPreparationBinding() {
    }

    static String preparationAction(UUID task, Identity identity, Event source, Event plan, Event preparation,
                                    String layout, List<Event> events) throws SQLException {
        var quotas = events.stream().filter(event -> event.type().equals("WORKSPACE_QUOTA")).toList();
        if (quotas.isEmpty())
            return DeploymentAgentPreparationAbsence.hash(task + ":" + preparation.detail() + ":" + source.detail());
        var components = new HashSet<String>();
        Long preparedBytes = null;
        for (var quota : quotas) {
            if (quota.id() <= plan.id() || quota.id() >= preparation.id() || quota.action() == null
                || !quota.action().matches("[a-z0-9][a-z0-9-]{0,62}") || !components.add(quota.action()))
                throw new SQLException("Original preparation quota order or component is invalid");
            var data = quotaJson(quota.detail());
            requireQuotaFields(data, false);
            requireQuotaOwner(data, task, identity, source, layout, plan.detail());
            if (quota.action().equals(preparation.detail())) preparedBytes = data.path("workspaceBytes").longValue();
        }
        if (preparedBytes == null) throw new SQLException("Original preparation component quota is absent");
        return gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentCandidateCapacityException.binding(
            task, preparation.detail(), source.detail(), plan.detail(), preparedBytes);
    }

    static void requireQuotaOwner(com.fasterxml.jackson.databind.JsonNode node, UUID task, Identity identity,
                                  Event source, String layout, String plan) throws SQLException {
        for (String field : List.of("task", "server", "source", "configuration", "layout", "plan"))
            if (!node.path(field).isTextual()) throw new SQLException("Original quota identity is not textual");
        if (!node.path("task").asText().equals(task.toString()) || !node.path("server").asText().equals(identity.server())
            || !node.path("source").asText().equals(source.detail()) || !node.path("configuration").asText().equals(identity.configurationDigest())
            || !node.path("layout").asText().equals(layout) || !node.path("plan").asText().equals(plan)
            || !node.path("workspaceBytes").isIntegralNumber() || !node.path("reserveBytes").isIntegralNumber()
            || !node.path("workspaceBytes").canConvertToLong() || !node.path("reserveBytes").canConvertToLong()
            || node.path("workspaceBytes").longValue() < gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.UNIT
            || node.path("reserveBytes").longValue() < 1073741824L
            || node.path("workspaceBytes").longValue() > gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.MAXIMUM
            || node.path("reserveBytes").longValue() > gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota.MAXIMUM)
            throw new SQLException("Original workspace quota owner is unconfirmed");
    }

    static void requireQuotaFields(com.fasterxml.jackson.databind.JsonNode node, boolean candidate) throws SQLException {
        var fields = new HashSet<>(Set.of("task", "server", "source", "configuration", "layout", "plan", "workspaceBytes", "reserveBytes"));
        if (candidate) fields.addAll(Set.of("prepareAction", "candidate"));
        else fields.add("mode");
        DeploymentJsonDocument.fields(node, fields);
        if (candidate && (!node.path("prepareAction").isTextual() || !node.path("candidate").isTextual())
            || !candidate && (!node.path("mode").isTextual() || !Set.of("AUTO", "EXPLICIT").contains(node.path("mode").textValue())))
            throw new SQLException("Original quota scope is invalid");
    }

    static com.fasterxml.jackson.databind.JsonNode quotaJson(String value) throws SQLException {
        if (value.length() > 4096) throw new SQLException("Original quota evidence exceeds its boundary");
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
            if (node == null || !node.isObject()) throw new SQLException("Original quota evidence is not an object");
            return node;
        } catch (java.io.IOException invalid) {
            throw new SQLException("Original quota evidence is malformed", invalid);
        }
    }

}
