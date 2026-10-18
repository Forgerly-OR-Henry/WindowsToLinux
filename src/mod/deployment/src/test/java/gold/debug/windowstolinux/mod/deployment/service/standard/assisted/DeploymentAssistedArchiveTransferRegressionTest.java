package gold.debug.windowstolinux.mod.deployment.service.standard.assisted;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.transfer.*;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.*;
import gold.debug.windowstolinux.mod.deployment.config.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentAssistedArchiveTransferRegressionTest {
    private final ServerId server = new ServerId("original");

    private final DeploymentRemoteLayout layout = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
        "/fixture/helper", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data"));

    private final Path frontend = Path.of("fixture", "中文 前端", "frontend-source.tar.gz").toAbsolutePath().normalize();

    private final String remote = layout.candidateRoot("demo-" + "c".repeat(16)) + "/mutable/source.tar.gz";

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "OVERWRITE", "KEEP_BOTH", "SKIP"})
    void selectsExactFrontendAmongBackendAndFrontendWithoutClaimingAuthority(String choice) {
        var facts = DeploymentAssistedScope.archiveTransferFacts(archives(), intent(choice), server, layout);
        assertEquals(archives().get(1), facts.get("archive"));
        assertEquals(remote, facts.get("remoteArchivePath"));
        assertEquals(true, facts.get("canonicalTransferMatched"));
        assertFalse(facts.containsKey("commandGrant"));
        assertFalse(facts.containsKey("liveOwnership"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HASH", "BYTES", "LOCAL", "TRAILING", "DIRECTION", "SERVER", "INTENT_TARGET", "EFFECT", "ESCAPE", "REMOTE", "DUPLICATE", "EMPTY"})
    void changedTransferOrAmbiguousArchivesCannotAcquireMatchingEvidence(String mutation) {
        var original = intent("NONE");
        String action = original.exactAction();
        action = switch (mutation) {
            case "HASH" -> action.replace("a".repeat(64), "b".repeat(64));
            case "BYTES" -> action.replace("maxBytes=14592", "maxBytes=7364");
            case "LOCAL" -> action.replace("frontend-source.tar.gz", "backend--source.tar.gz");
            case "TRAILING" -> action + "\nunreviewed=true";
            default -> action;
        };
        String destination = mutation.equals("ESCAPE") ? layout.workRoot() + "/../demo-" + "c".repeat(16) + "/mutable/source.tar.gz"
            : mutation.equals("REMOTE") ? remote + ".other" : remote;
        var changed = new ActionIntent(mutation.equals("DIRECTION") ? "platform.sftp.download" : original.type(),
            mutation.equals("SERVER") ? List.of("foreign") : original.serverIds(),
            mutation.equals("INTENT_TARGET") ? "foreign" : original.target(), original.revision(), action,
            mutation.equals("EFFECT") ? "Unrelated effect" : original.effectSummary())
            .withSemantics(new ActionSemantics("deployment.candidate.upload.v1", Set.of(new ActionTarget(server.id(), "deployment-candidate", destination))));
        var observations = mutation.equals("EMPTY") ? List.<Map<String, Object>>of()
            : mutation.equals("DUPLICATE") ? List.of(archives().get(1), archives().get(1)) : archives();
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentAssistedScope.archiveTransferFacts(observations, changed, server, layout));
    }

    private ActionIntent intent(String choice) {
        var request = new TransferRequest(frontend, remote, 14592);
        var result = choice.equals("NONE") ? RemoteActions.transfer(server, "revision", request, "a".repeat(64), true)
            : RemoteActions.transfer(server, "revision", request, "a".repeat(64), true, TransferConflictChoice.valueOf(choice));
        return result.withSemantics(new ActionSemantics("deployment.candidate.upload.v1",
            Set.of(new ActionTarget(server.id(), "deployment-candidate", remote))));
    }

    private List<Map<String, Object>> archives() {
        return List.of(Map.of("localArchivePath", frontend.resolveSibling("backend-source.tar.gz").toString(),
                "archiveSha256", "b".repeat(64), "archiveBytes", 7364L, "relativeRoot", "backend"),
            Map.of("localArchivePath", frontend.toString(), "archiveSha256", "a".repeat(64),
                "archiveBytes", 14592L, "relativeRoot", "frontend"));
    }
}
