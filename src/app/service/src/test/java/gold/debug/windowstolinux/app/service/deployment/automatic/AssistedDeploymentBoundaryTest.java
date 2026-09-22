package gold.debug.windowstolinux.app.service.deployment.automatic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;

import gold.debug.windowstolinux.app.service.server.ServerProfile;
import gold.debug.windowstolinux.shared.deploy.approval.DeploymentApprovalGate;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.security.CredentialStorageMode;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedActionModelPort;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedActionSession;
import gold.debug.windowstolinux.shared.standard.deploy.assistance.AssistedExecutionPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Timeout(20)
class AssistedDeploymentBoundaryTest {
    @TempDir
    Path root;

    final ServerProfile server = new ServerProfile("server", "example.invalid", 22, "deploy", "ref",
            CredentialStorageMode.MASTER_PASSWORD);

    final AutomaticDeploymentInteraction human = new AutomaticDeploymentInteraction() {
        public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
            throw new AssertionError();
        }

        public boolean confirm(String key, Map<String, ?> details) {
            throw new AssertionError("full control must not ask human for permitted operations");
        }

        public char[] requestSecret(String key) {
            throw new AssertionError();
        }
    };
    @Test
    void databaseIntentAlwaysComesFromTheUserBeforeCommandApproval() {
        for (boolean accepted : List.of(false, true)) {
            var confirmations = new ArrayList<String>();
            var input = new AutomaticDeploymentInteraction() {
                public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
                    throw new AssertionError();
                }

                public boolean confirm(String key, Map<String, ?> details) {
                    confirmations.add(key);
                    assertEquals(Map.of("database", "existing", "files", "migration.sql"), details);
                    return accepted;
                }

                public char[] requestSecret(String key) {
                    throw new AssertionError();
                }
            };
            var interaction = new AssistedDeploymentInteraction(input, null);
            for (String key : List.of("db.existingSchema", "db.sqliteInitialization"))
                assertEquals(accepted,
                        interaction.confirm(key, Map.of("database", "existing", "files", "migration.sql")));
            assertEquals(List.of("db.existingSchema", "db.sqliteInitialization"), confirmations);
            assertFalse(interaction.confirmDatabaseReplacement(Map.of()));
        }
    }

    @Test
    void actualDiagnosticFeedbackChangesTheNextActionAndReviewBinding() throws Exception {
        Files.writeString(root.resolve("app.txt"), "source");
        var selected = new ArrayList<AgentToolType>();
        var reviews = new ArrayList<AgentAction>();
        var models = new AssistedActionModelPort() {
            public AgentDecision decide(String goal, List<AgentAction> actions, List<String> history, int remaining) {
                var chosen = actions.stream()
                        .filter(a -> selected.isEmpty()
                                ? a.tool() == AgentToolType.SERVICE_STATUS
                                : a.tool() == AgentToolType.RESTART_SERVICE)
                        .findFirst().orElseThrow();
                if (!selected.isEmpty()) {
                    assertTrue(history.getLast().contains("STOPPED"));
                    assertEquals("STOPPED", chosen.evidence().get("observed/state"));
                }
                selected.add(chosen.tool());
                return new AgentDecision(AgentDecisionType.EXECUTE, chosen.id(), chosen.binding(),
                        "actual state informs next step");
            }

            public AgentReview review(String goal, AgentAction action, AgentRiskLevel local) {
                reviews.add(action);
                return new AgentReview(AgentReviewDecision.ALLOW, local, action.binding(), "owned exact action",
                        List.copyOf(action.evidence().keySet()));
            }

            public boolean advanceDeployment() {
                throw new AssertionError();
            }
        };
        var boundary = new AssistedDeploymentBoundary("task", server, AgentApprovalMode.FULL_CONTROL, models,
                new AgentTaskControl(state -> {
                }), human, event -> {
                }, () -> true);
        boundary.freeze(root);
        boundary.register("status", AgentToolType.SERVICE_STATUS, Map.of("application", "demo"), () -> true,
                () -> new AgentObservation(true, true, Map.of("state", "STOPPED")));
        boundary.register("restart", AgentToolType.RESTART_SERVICE, Map.of("application", "demo"), () -> true,
                () -> new AgentObservation(true, true, Map.of("changed", "true", "state", "RUNNING")));
        assertTrue(boundary.recover(Map.of("failure", "service stopped")));
        assertEquals("done",
                boundary.execute(AgentToolType.DEPLOY_TRANSACTION, Map.of("application", "demo"), () -> "done"));
        assertEquals(List.of(AgentToolType.SERVICE_STATUS, AgentToolType.RESTART_SERVICE), selected);
        assertEquals(3, reviews.size());
        assertEquals("RUNNING", reviews.getLast().evidence().get("observed/state"));
    }

    @Test
    void sourceMutationDuringApprovalInvalidatesTheWrite() throws Exception {
        Path file = root.resolve("app.txt");
        Files.writeString(file, "original");
        var ref = new AtomicReference<AgentTaskControl>();
        var control = new AgentTaskControl(state -> {
            if (state == AgentTaskState.PAUSED)
                ref.get().cancel();
        });
        ref.set(control);
        var models = new AssistedActionModelPort() {
            public AgentDecision decide(String g, List<AgentAction> actions, List<String> history, int remaining) {
                var a = actions.getFirst();
                return new AgentDecision(AgentDecisionType.EXECUTE, a.id(), a.binding(), "deploy");
            }

            public AgentReview review(String g, AgentAction a, AgentRiskLevel risk) throws Exception {
                Files.writeString(file, "changed");
                return new AgentReview(AgentReviewDecision.ALLOW, risk, a.binding(), "reviewed old source",
                        List.copyOf(a.evidence().keySet()));
            }

            public boolean advanceDeployment() {
                throw new AssertionError();
            }
        };
        var boundary = new AssistedDeploymentBoundary("task", server, AgentApprovalMode.FULL_CONTROL, models, control,
                human, event -> {
                }, () -> true);
        boundary.freeze(root);
        assertThrows(CancellationException.class,
                () -> boundary.execute(AgentToolType.DEPLOY_TRANSACTION, Map.of("application", "demo"), () -> {
                    throw new AssertionError("changed source executed");
                }));
    }

    @Test
    void cancellationAfterSuccessfulPublicationPreservesTheActualResult() throws Exception {
        Files.writeString(root.resolve("app.txt"), "source");
        var control = new AgentTaskControl(state -> {
        });
        var models = new AssistedActionModelPort() {
            public AgentDecision decide(String g, List<AgentAction> actions, List<String> h, int left) {
                var a = actions.getFirst();
                return new AgentDecision(AgentDecisionType.EXECUTE, a.id(), a.binding(), "deploy");
            }

            public AgentReview review(String g, AgentAction a, AgentRiskLevel risk) {
                return new AgentReview(AgentReviewDecision.ALLOW, risk, a.binding(), "owned",
                        List.copyOf(a.evidence().keySet()));
            }

            public boolean advanceDeployment() {
                throw new AssertionError();
            }
        };
        var boundary = new AssistedDeploymentBoundary("task", server, AgentApprovalMode.FULL_CONTROL, models, control,
                human, event -> {
                }, () -> true);
        boundary.freeze(root);
        assertEquals("publication-verified",
                boundary.execute(AgentToolType.DEPLOY_TRANSACTION, Map.of("application", "demo"), () -> {
                    control.cancel();
                    return "publication-verified";
                }));
        control.finish(AgentTaskState.SUCCEEDED);
        assertEquals(AgentTaskState.SUCCEEDED, control.state());
    }
}
