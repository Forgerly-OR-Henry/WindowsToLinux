package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.core.model.application.DeploymentUnitIdentity;
import org.junit.jupiter.api.Test;

class DeploymentAgentPublicationRecoveryProgramTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DeploymentRemoteLayout layout = DeploymentRemoteLayout.original();
    private final DeploymentAgentPublicationRecoveryProgram program = new DeploymentAgentPublicationRecoveryProgram(layout);
    private final DeploymentUnitIdentity unit = DeploymentUnitIdentity.forManaged("backend", new ServerId("original-host"), "a".repeat(64), layout);
    private final UUID task = UUID.fromString("072bc074-4bce-4c14-b425-f2ff965c7d40");
    private final UUID action = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    private final UUID execution = UUID.fromString("45e3a9a4-a6b8-403a-89f5-605e11eeeca0");

    @Test
    void fixedInstallerExposesCompleteDecodedSourceAndCanonicalBoundRequests() throws Exception {
        try (var request = program.install(unit, task, action)) {
            String body = new String(request.command().stdin(), StandardCharsets.UTF_8);
            String sourceLine = body.lines().filter(line -> line.startsWith("CONTROLLER_SOURCE = ")).findFirst().orElseThrow();
            String loaderLine = body.lines().filter(line -> line.startsWith("LOADER_SOURCE = ")).findFirst().orElseThrow();
            assertEquals(program.source(), JSON.readTree(sourceLine.substring("CONTROLLER_SOURCE = ".length())).textValue());
            assertEquals(program.loader(), JSON.readTree(loaderLine.substring("LOADER_SOURCE = ".length())).textValue());
            var facts = DeploymentAgentPublicationRecoveryProgram.reviewFacts(request.operation(), request.command(), layout);
            var structure = JSON.valueToTree(program.installationEvidence());
            assertEquals(body, DeploymentAgentPublicationRecoveryProgram.reconstructInstallationEvidence(structure));
            assertEquals("/completeExecutableInput/derived/controller", JSON.valueToTree(facts.get("completeDecodedSource")).path("pointer").asText());
            assertEquals("/completeExecutableInput/sources/loader", JSON.valueToTree(facts.get("completeLoaderSource")).path("pointer").asText());
            assertEquals("4bce53b129711ad351344107e8f298b2588c20f0ec82f9856d9b5e3b19eb2021", program.version());
            // These default-layout anchors were read from frozen pre-change compiled artifacts, not this implementation.
            assertEquals(165726, program.source().getBytes(StandardCharsets.UTF_8).length);
            assertEquals("477ca1395417bbf1ec024c7e427066775da8d7861f2d2ee759896d598cd59ca2", DeploymentAgentPublicationRecoveryProgram.sha256(program.source()));
            assertEquals(7586, program.loader().getBytes(StandardCharsets.UTF_8).length);
            assertEquals("46dc35ea0c0c9b2eb5d2f1d4903891d9a62a391d8c80182a01efc9efdc7d0151", DeploymentAgentPublicationRecoveryProgram.sha256(program.loader()));
            assertEquals(189420, body.getBytes(StandardCharsets.UTF_8).length);
            assertEquals("0a16f4fccb224865bbcb42a4472070f54ae7f04a786f18e1bc73bda7bdb94d22", DeploymentAgentPublicationRecoveryProgram.sha256(body));
            assertEquals(request.command().stdin().length, facts.get("stdinBytes"));
            assertEquals(program.manifest().get("sourceSha256"), DeploymentAgentPublicationRecoveryProgram.sha256(program.source()));
            assertTrue(program.source().getBytes(StandardCharsets.UTF_8).length < 1048576);
            // Counts real rendered facts and their JSON string escaping, not a live provider request or approval.
            String serialized = JSON.writeValueAsString(Map.of("completeExecutableInput", structure, "publicationRecoveryProgram", facts));
            assertTrue(serialized.length() < 1048576);
            assertTrue(JSON.writeValueAsBytes(Map.of("role", "user", "content", serialized)).length < 1048576);
            assertNotEquals(layout.toolchains() + "/agent-controller-programs", program.root());
            assertFalse(request.command().script().contains("prepare"));
            assertFalse(request.command().script().contains("bind"));
        }
        try (var request = program.guardedFirstRollback(unit, task, execution, "backend-0123456789abcdef", "b".repeat(64), "c".repeat(64), action)) {
            assertEquals(0, request.command().stdin().length);
            assertTrue(request.resourceTargets().stream().anyMatch(target -> target.kind().equals("deployment-maintenance")));
            var rollbackFacts = JSON.valueToTree(DeploymentAgentPublicationRecoveryProgram.reviewFacts(request.operation(), request.command(), layout));
            DeploymentAgentPublicationRecoveryProgram.verifyRollbackSourceEvidence(rollbackFacts.path("sourceView"), program.source(), program.loader());
            assertEquals("/publicationRecoveryProgram/sourceView/derived/controller", rollbackFacts.at("/completeDecodedSource/pointer").asText());
            assertFalse(rollbackFacts.path("sourceView").has("segments"), "No installation wrapper in a rollback source view");
            for (int change = 0; change < 4; change++) {
                try (var invalid = new CommandRequest(request.command().script() + (change == 0 ? " extra" : ""),
                    change == 1 ? new byte[]{1} : new byte[0], Duration.ofSeconds(change == 2 ? 181 : 180), change == 3 ? 8193 : 8192)) {
                    assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationRecoveryProgram.reviewFacts(request.operation(), invalid, layout));
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> program.guardedFirstRollback(unit, task, execution, "foreign-0123456789abcdef", "b".repeat(64), "c".repeat(64), action));
    }

    @Test
    void installationEvidenceRejectsStructuralAndCanonicalDrift() throws Exception {
        for (String drift : List.of("FORMAT", "EXTRA", "MISSING", "TYPE", "REFERENCE", "ORDER", "ENCODING", "SOURCE",
            "WRAPPER", "DERIVED_HASH", "HASH", "LENGTH", "FLOAT_LENGTH", "UNICODE", "SEGMENT_EXTRA", "ACTUAL_INPUT")) {
            ObjectNode changed = JSON.valueToTree(program.installationEvidence());
            var segments = (com.fasterxml.jackson.databind.node.ArrayNode) changed.path("segments");
            switch (drift) {
                case "FORMAT" -> changed.put("format", "UNREVIEWED_FORMAT");
                case "EXTRA" -> changed.put("permission", "APPROVE");
                case "MISSING" -> ((ObjectNode) changed.path("sources")).remove("loader");
                case "TYPE" -> ((ObjectNode) changed.path("sources")).put("bash", 1);
                case "REFERENCE" -> ((ObjectNode) segments.get(1)).put("source", "bash");
                case "ORDER" -> { var first = segments.get(1).deepCopy(); segments.set(1, segments.get(3)); segments.set(3, first); }
                case "ENCODING" -> ((ObjectNode) segments.get(1)).put("kind", "SHELL_SINGLE_QUOTE");
                case "SOURCE" -> ((ObjectNode) changed.path("sources")).put("loader", program.loader() + "\n# changed");
                case "WRAPPER" -> ((ObjectNode) changed.at("/derived/controller/segments/0")).put("text", "# replaced\n");
                case "DERIVED_HASH" -> ((ObjectNode) changed.at("/derived/controller")).put("sha256", "a".repeat(64));
                case "HASH" -> changed.put("sha256", "a".repeat(64));
                case "LENGTH" -> changed.put("characters", true);
                case "FLOAT_LENGTH" -> changed.put("utf8Bytes", 189420.0);
                case "UNICODE" -> ((ObjectNode) changed.path("sources")).put("bash", "\ud800");
                case "SEGMENT_EXTRA" -> ((ObjectNode) segments.get(0)).put("authority", "TRUSTED");
                case "ACTUAL_INPUT" -> { }
                default -> throw new AssertionError(drift);
            }
            String actual = program.installer() + (drift.equals("ACTUAL_INPUT") ? " " : "");
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationRecoveryProgram.verifyInstallationEvidence(changed, actual), drift);
        }
        for (String drift : List.of("BASH", "LOADER", "EXTRA", "LENGTH", "REFERENCE")) {
            ObjectNode changed = JSON.valueToTree(program.rollbackSourceEvidence());
            switch (drift) {
                case "BASH" -> ((ObjectNode) changed.path("sources")).put("bash", "changed");
                case "LOADER" -> ((ObjectNode) changed.path("sources")).put("loader", "changed");
                case "EXTRA" -> changed.putArray("segments");
                case "LENGTH" -> ((ObjectNode) changed.path("loaderIdentity")).put("characters", false);
                case "REFERENCE" -> ((ObjectNode) changed.at("/derived/controller/segments/1")).put("source", "loader");
                default -> throw new AssertionError(drift);
            }
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentPublicationRecoveryProgram.verifyRollbackSourceEvidence(changed, program.source(), program.loader()), drift);
        }
    }

    @Test
    void storeReceiptsRejectAmbiguousUnsafeMissingAndForeignIdentityFrames() throws Exception {
        try (var request = program.readiness(unit, task, action)) {
            var node = store(request, "MISSING");
            assertEquals("MISSING", program.parseStoreReceipt(request, result("PUBLICATION_RECOVERY_STORE=", node)).status());
            for (String invalid : List.of("extra", "task", "sourceBytes", "sourceSha256", "status", "missing")) {
                var changed = node.deepCopy();
                switch (invalid) {
                    case "extra" -> changed.put("unsafe", true);
                    case "task" -> changed.put("task", UUID.randomUUID().toString());
                    case "sourceBytes" -> changed.put("sourceBytes", "1");
                    case "sourceSha256" -> changed.put("sourceSha256", "0".repeat(64));
                    case "status" -> changed.put("status", "READY");
                    default -> changed.remove("contract");
                }
                assertThrows(IllegalArgumentException.class, () -> program.parseStoreReceipt(request, result("PUBLICATION_RECOVERY_STORE=", changed)), invalid);
            }
            String duplicate = "PUBLICATION_RECOVERY_STORE=" + node.toString().replaceFirst("\\{", "{\"task\":\"" + task + "\",") + "\n";
            assertThrows(IllegalArgumentException.class, () -> program.parseStoreReceipt(request, successful(new CommandResult(Optional.of(0), duplicate, "", false))));
        }
        try (var request = program.install(unit, task, action)) {
            var missing = store(request, "MISSING").put("requestedInstallOperation", action.toString().replace("-", ""));
            assertThrows(IllegalArgumentException.class, () -> program.parseStoreReceipt(request, result("PUBLICATION_RECOVERY_STORE=", missing)));
            var ready = store(request, "READY").put("requestedInstallOperation", action.toString().replace("-", ""));
            assertEquals("READY", program.parseStoreReceipt(request, result("PUBLICATION_RECOVERY_STORE=", ready)).status());
        }
    }

    @Test
    void boundedRollbackReceiptDoesNotClaimSettlementAndRejectsPartialOrReplayedResults() throws Exception {
        try (var request = program.guardedFirstRollback(unit, task, execution, "backend-0123456789abcdef", "b".repeat(64), "c".repeat(64), action)) {
            var node = JSON.createObjectNode().put("format", "WTL_AGENT_PUBLICATION_ROLLBACK_1")
                .put("version", program.version()).put("sourceSha256", program.manifest().get("sourceSha256")).put("layoutSha256", layout.digest())
                .put("task", task.toString()).put("action", action.toString().replace("-", "")).put("execution", execution.toString())
                .put("unit", unit.id()).put("owner", unit.ownershipManifestSha256()).put("candidate", "backend-0123456789abcdef")
                .put("release", "b".repeat(64)).put("planSha256", "c".repeat(64)).put("currentAbsent", true).put("unitAbsent", true)
                .put("releaseAbsent", true).put("maintenanceAbsent", true);
            assertEquals(task, program.parseRollbackReceipt(request, result("PUBLICATION_RECOVERY_ROLLBACK=", node)).task());
            assertFalse(node.has("taskEnded"));
            for (String invalid : List.of("task", "action", "execution", "owner", "candidate", "release", "planSha256", "maintenanceAbsent", "extra", "missing")) {
                var changed = node.deepCopy();
                if (invalid.equals("extra")) changed.put("settled", true);
                else if (invalid.equals("missing")) changed.remove("currentAbsent");
                else if (invalid.equals("maintenanceAbsent")) changed.put(invalid, false);
                else changed.put(invalid, "changed");
                assertThrows(IllegalArgumentException.class, () -> program.parseRollbackReceipt(request, result("PUBLICATION_RECOVERY_ROLLBACK=", changed)), invalid);
            }
            String body = "PUBLICATION_RECOVERY_ROLLBACK=" + node + "\n";
            for (CommandResult invalid : List.of(new CommandResult(Optional.of(0), body, "", true),
                new CommandResult(Optional.empty(), body, "", false), new CommandResult(Optional.of(70), body, "", false),
                new CommandResult(Optional.of(0), body, "unconfirmed", false), new CommandResult(Optional.of(0), body + body, "", false)))
                assertThrows(IllegalArgumentException.class, () -> program.parseRollbackReceipt(request, successful(invalid)));
        }
    }

    private ObjectNode store(DeploymentAgentPublicationRecoveryProgram.Request request, String status) {
        return JSON.createObjectNode().put("format", DeploymentAgentPublicationRecoveryProgram.REVISION)
            .put("contract", DeploymentAgentPublicationRecoveryProgram.REVISION).put("version", program.version())
            .put("sourceSha256", program.manifest().get("sourceSha256")).put("sourceBytes", program.source().getBytes(StandardCharsets.UTF_8).length)
            .put("layoutSha256", layout.digest()).put("path", program.path()).put("task", request.task().toString())
            .put("action", request.action().toString().replace("-", "")).put("status", status)
            .put("installTask", status.equals("READY") ? task.toString() : "-")
            .put("installOperation", status.equals("READY") ? action.toString().replace("-", "") : "-");
    }

    private static OperationResult<CommandResult> result(String prefix, ObjectNode node) {
        return successful(new CommandResult(Optional.of(0), prefix + node + "\n", "", false));
    }

    private static OperationResult<CommandResult> successful(CommandResult result) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(result), Optional.empty());
    }
}
