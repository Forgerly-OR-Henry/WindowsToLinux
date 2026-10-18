package gold.debug.windowstolinux.mod.deployment.service.standard;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.app.db.connect.PlatformDatabase;
import gold.debug.windowstolinux.app.db.init.DatabaseProtection;
import gold.debug.windowstolinux.app.db.log.LogStore;
import gold.debug.windowstolinux.appapi.ai.model.AiGenerationAttempt;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;

/** Read-only numeric audit of retained real-run logs; no model or SSH capability is opened. */
public final class DeploymentAssistedUsageAudit {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DeploymentAssistedUsageAudit() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Supply the task's usage audit manifest");
        Path workspace = Path.of("").toRealPath();
        Path evidence = workspace.resolve(".ai-workspace/evidence/assisted-regression-20261018").toRealPath();
        Path manifest = Path.of(arguments[0]).toRealPath();
        if (!manifest.startsWith(evidence))
            throw new IllegalArgumentException("Audit manifest is outside current evidence");
        var sources = JSON.readTree(manifest.toFile());
        if (!sources.isArray() || sources.isEmpty() || sources.size() > 128)
            throw new IllegalArgumentException("Bounded explicit audit roots required");
        var protectionFactory = DeploymentStaticLiveAcceptanceTest.class.getDeclaredMethod("currentUserProtection");
        protectionFactory.setAccessible(true);
        var protection = (DatabaseProtection) protectionFactory.invoke(null);
        Path data = workspace.resolve("src/app/db/data").toRealPath();
        int records = 0;
        for (var source : sources) {
            Path state = Path.of(source.path("state").asText()).toRealPath();
            String run = source.path("run").asText();
            String project = source.path("project").asText();
            if (!state.startsWith(data) || !(state.getFileName().toString().startsWith("assisted-live-")
                || state.getFileName().toString().startsWith("ai-live-")) || !run.matches("[a-z0-9-]{1,96}")
                || !Set.of("task-board", "file-transfer", "asset-lending", "csv-inspector", "survey-scoring",
                "log-analyzer", "directory-diff", "binary-inspector").contains(project))
                throw new IllegalArgumentException("Audit source identity differs");
            int rootRecords = 0;
            try (var database = PlatformDatabase.open(state.resolve("data"), protection)) {
                var logs = new LogStore(database);
                for (var target : logs.targets()) {
                    if (!target.owner().equals("gold.debug.windowstolinux.mod.deployment")) continue;
                    for (var record : logs.read(target)) {
                        if (!record.eventCode().equals("DEPLOYMENT_AI_USAGE")) continue;
                        rootRecords++;
                        var row = JSON.createObjectNode().put("run", run).put("project", project)
                            .put("state", state.toString()).put("record", record.id().toString())
                            .put("task", String.valueOf(record.taskId())).put("truncated", record.truncated());
                        if (record.details() == null || record.truncated()) {
                            row.put("traceStatus", "UNAVAILABLE");
                        } else {
                            Map<String, String> facts = new LinkedHashMap<>();
                            JSON.readTree(record.details()).fields().forEachRemaining(field -> facts.put(field.getKey(), field.getValue().asText()));
                            row.put("purpose", facts.get("purpose")).put("invocation", facts.get("invocation"));
                            var attempts = row.putArray("attempts");
                            if (facts.getOrDefault("generationAttemptCount", "UNKNOWN").equals("UNKNOWN")) {
                                row.put("traceStatus", "UNAVAILABLE");
                            } else {
                                for (var attempt : AiGenerationAttempt.fromProblem(new Problem(ProblemCode.UNAVAILABLE,
                                    "USAGE_AUDIT", "Retained numeric provider metadata", facts, Optional.empty()))) {
                                    var value = attempts.addObject().put("id", attempt.generationId().toString()).put("number", attempt.number())
                                        .put("model", attempt.modelId()).put("protocol", attempt.protocol())
                                        .put("outcome", attempt.outcome().name()).put("event", attempt.eventCode());
                                    var usage = value.putObject("usage");
                                    metric(usage, "inputTokens", attempt.usage().inputTokens());
                                    metric(usage, "outputTokens", attempt.usage().outputTokens());
                                    metric(usage, "cachedInputTokens", attempt.usage().cachedInputTokens());
                                    metric(usage, "cacheMissInputTokens", attempt.usage().cacheMissInputTokens());
                                    metric(usage, "reasoningOutputTokens", attempt.usage().reasoningOutputTokens());
                                }
                                row.put("traceStatus", "TYPED");
                            }
                        }
                        System.out.println("ASSISTED_USAGE_AUDIT " + JSON.writeValueAsString(row));
                    }
                }
            }
            records += rootRecords;
            System.out.println("ASSISTED_USAGE_AUDIT_ROOT run=" + run + " records=" + rootRecords + " modelDispatches=0 sshDispatches=0");
        }
        System.out.println("ASSISTED_USAGE_AUDIT_COMPLETE roots=" + sources.size() + " records=" + records);
    }

    private static void metric(ObjectNode target, String field, Optional<Long> count) {
        if (count.isPresent()) target.put(field, count.orElseThrow());
        else target.putNull(field);
    }
}
