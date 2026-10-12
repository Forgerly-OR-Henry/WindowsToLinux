package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.*;

import com.fasterxml.jackson.databind.*;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.publish.DeploymentCandidateChannel.CapacityObservation;
import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentWorkspaceQuota;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;

/** Produced only after confirmed preparation prerequisites and before candidate creation. Never an unsent-install proof. */
public final class DeploymentAgentCandidateCapacityException extends Exception {
    public static final String EVENT = "PREPARE_CAPACITY_REJECTED";

    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final TaskContext task;

    private final String component, source, configuration, layout, server;

    private final DeploymentWorkspaceQuota.Selection quota;

    private final CapacityObservation capacity;

    private final long requiredBytes;

    DeploymentAgentCandidateCapacityException(TaskContext task, String component, String source, String configuration,
                                              DeploymentRemoteLayout layout, gold.debug.windowstolinux.appapi.server.model.ServerId server, DeploymentWorkspaceQuota.Selection quota, CapacityObservation capacity, long requiredBytes) {
        super("WORKSPACE_HOST_INSUFFICIENT: verified candidate capacity is below the accepted budget");
        this.task = Objects.requireNonNull(task);
        this.component = component;
        this.source = source;
        this.configuration = configuration;
        this.layout = layout.digest();
        this.server = server.id();
        this.quota = quota;
        this.capacity = capacity;
        this.requiredBytes = requiredBytes;
        if (!capacity.server().equals(server.id()) || !capacity.task().equals(task.id()) || !capacity.revision().equals(configuration) || !capacity.layoutSha256().equals(this.layout)
            || requiredBytes < Math.addExact(Math.multiplyExact(quota.bytes(), 2), quota.policy().reserveBytes())
            || capacity.availableBytes() < 0 || capacity.availableBytes() >= required())
            throw new IllegalArgumentException("Foreign capacity boundary");
    }

    private long required() {
        return requiredBytes;
    }

    public boolean matches(TaskContext original, String id, String sourceRevision, long selectedBytes) {
        return task == original && component.equals(id) && source.equals(sourceRevision) && quota.bytes() == selectedBytes;
    }

    public String feedback() {
        return "WORKSPACE_HOST_INSUFFICIENT DATA: " + JSON.createObjectNode().put("component", component)
            .put("availableBytes", capacity.availableBytes()).put("requiredBytes", required()).put("workspaceBytes", quota.bytes())
            .put("hostReserveBytes", quota.policy().reserveBytes()).put("candidateCreated", false)
            .put("earlierPreparation", "CONFIRMED_COMPLETED").put("meaning", "Accepted candidate budget cannot fit the current verified capacity. No candidate was created by this attempt.");
    }

    public String proof(DeploymentAgentActionLog.Event preparation, String plan) {
        return JSON.createObjectNode().put("formatVersion", 1).put("task", task.id().toString()).put("component", component)
            .put("server", server).put("source", source).put("configuration", configuration).put("layout", layout).put("plan", plan)
            .put("prepareEventId", preparation.id()).put("prepareAction", preparation.action()).put("workspaceBytes", quota.bytes())
            .put("availableBytes", capacity.availableBytes()).put("requiredBytes", required()).put("reserveBytes", quota.policy().reserveBytes())
            .put("capacityRequest", capacity.requestSha256()).put("capacityResult", capacity.resultSha256())
            .put("stage", "CONFIRMED_PREREQUISITES_BEFORE_CREATE").toString();
    }

    /** Validates the original attempt and frozen quota; hashes bind observations and convey no authority. */
    public static boolean matches(DeploymentAgentActionLog.Event event, List<DeploymentAgentActionLog.Event> events) {
        try {
            if (!EVENT.equals(event.type()) || event.detail().length() > 4096) return false;
            var data = JSON.readTree(event.detail());
            var keys = new HashSet<String>();
            data.fieldNames().forEachRemaining(keys::add);
            if (!keys.equals(Set.of("formatVersion", "task", "component", "server", "source", "configuration", "layout", "plan", "prepareEventId", "prepareAction",
                "workspaceBytes", "availableBytes", "requiredBytes", "reserveBytes", "capacityRequest", "capacityResult", "stage"))
                || !data.path("formatVersion").isInt() || data.path("formatVersion").intValue() != 1 || !data.path("stage").asText().equals("CONFIRMED_PREREQUISITES_BEFORE_CREATE"))
                return false;
            for (String key : List.of("workspaceBytes", "availableBytes", "requiredBytes", "reserveBytes", "prepareEventId"))
                if (!data.path(key).isIntegralNumber() || !data.path(key).canConvertToLong()) return false;
            for (String key : List.of("source", "configuration", "layout", "plan", "prepareAction", "capacityRequest", "capacityResult"))
                if (!data.path(key).isTextual() || !data.path(key).textValue().matches("[0-9a-f]{64}")) return false;
            long bytes = data.path("workspaceBytes").longValue(), available = data.path("availableBytes").longValue(), reserve = data.path("reserveBytes").longValue();
            new DeploymentWorkspaceQuota.Policy(DeploymentWorkspaceQuota.MAXIMUM, reserve);
            if (bytes < DeploymentWorkspaceQuota.UNIT || bytes > DeploymentWorkspaceQuota.MAXIMUM || available < 0
                || available >= data.path("requiredBytes").longValue() || data.path("requiredBytes").longValue() < Math.addExact(Math.multiplyExact(bytes, 2), reserve))
                return false;
            var prepare = events.stream().filter(value -> value.id() == data.path("prepareEventId").longValue()).findFirst().orElseThrow();
            if (!prepare.type().equals("PREPARE_EXECUTING") || prepare.id() >= event.id() || !prepare.action().equals(event.action())
                || !prepare.action().equals(data.path("prepareAction").asText()) || !prepare.detail().equals(data.path("component").asText()))
                return false;
            if (events.stream().filter(value -> value.type().equals(EVENT) && JSONPrepareId(value.detail()) == prepare.id()).count() != 1)
                return false;
            if (events.stream().anyMatch(value -> value.id() > prepare.id() && value.id() < event.id() && value.action().equals(prepare.action())
                && Set.of("PREPARE_EXECUTING", "PREPARE_RESULT", "PREPARE_NOT_SENT", EVENT).contains(value.type())))
                return false;
            var prefix = events.stream().filter(value -> value.id() < prepare.id()).toList();
            var source = prefix.stream().filter(value -> value.type().equals("SOURCE")).reduce((first, last) -> last).orElseThrow();
            var plan = prefix.stream().filter(value -> value.type().equals("PLAN")).reduce((first, last) -> last).orElseThrow();
            if (!source.detail().equals(data.path("source").asText()) || !plan.detail().equals(data.path("plan").asText()))
                return false;
            if (events.stream().noneMatch(value -> value.id() < event.id() && value.type().equals("HELPER_READINESS_OBSERVED") && value.detail().startsWith("READY:")))
                return false;
            var quota = prefix.stream().filter(value -> value.type().equals("WORKSPACE_QUOTA") && value.action().equals(prepare.detail())).reduce((first, last) -> last).orElseThrow();
            if (quota.id() < plan.id()) return false;
            var saved = JSON.readTree(quota.detail());
            var allQuotas = new LinkedHashMap<String, Long>();
            var created = new HashSet<String>();
            for (var value : prefix) {
                if (value.type().equals("CANDIDATE")) created.add(value.action());
                if (value.type().equals("WORKSPACE_QUOTA") && value.id() > plan.id())
                    allQuotas.put(value.action(), JSON.readTree(value.detail()).path("workspaceBytes").longValue());
            }
            long graphRequired = reserve;
            for (var entry : allQuotas.entrySet())
                if (!created.contains(entry.getKey()))
                    graphRequired = Math.addExact(graphRequired, Math.multiplyExact(entry.getValue(), 2));
            if (graphRequired != data.path("requiredBytes").longValue()) return false;
            for (String key : List.of("task", "server", "source", "configuration", "layout", "plan"))
                if (!saved.path(key).equals(data.path(key))) return false;
            if (saved.path("workspaceBytes").longValue() != bytes || saved.path("reserveBytes").longValue() != reserve)
                return false;
            return event.action().equals(binding(UUID.fromString(data.path("task").asText()), prepare.detail(), data.path("source").asText(), data.path("plan").asText(), bytes));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static long JSONPrepareId(String text) {
        try {
            return JSON.readTree(text).path("prepareEventId").longValue();
        } catch (Exception invalid) {
            return -1;
        }
    }

    public static String binding(UUID task, String component, String source, String plan, long bytes) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((task + ":" + component + ":" + source + ":" + plan + ":" + bytes).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
