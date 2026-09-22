package gold.debug.windowstolinux.shared.agent.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import gold.debug.windowstolinux.shared.agent.approval.SourcePatchApproval;
import gold.debug.windowstolinux.shared.agent.execution.protocol.*;
import gold.debug.windowstolinux.shared.agent.tool.AgentDeliveryPort;
import gold.debug.windowstolinux.shared.deploy.delivery.ManagedDelivery;
import gold.debug.windowstolinux.shared.deploy.task.AgentTaskControl;
import gold.debug.windowstolinux.shared.linux.command.RemoteCommandResult;
import gold.debug.windowstolinux.shared.linux.transfer.RemoteWorkspace;
import gold.debug.windowstolinux.shared.linux.workspace.*;
import gold.debug.windowstolinux.shared.model.agent.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import gold.debug.windowstolinux.shared.model.health.HealthCheck;
import gold.debug.windowstolinux.shared.model.project.*;
import gold.debug.windowstolinux.shared.model.project.application.*;
import gold.debug.windowstolinux.shared.source.browse.SourceBrowser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AutonomousDeploymentSessionTest {
    @TempDir
    Path root;

    static final String SHA = "a".repeat(64), ARTIFACT = "b".repeat(64);
    static ManagedDelivery.Component component(String id, List<String> dependencies) {
        var workload = new ApplicationWorkload(ApplicationWorkload.ExecutionMode.DAEMON, true,
                new ApplicationCommand("bin/app", List.of()), ".", List.of(), Optional.empty(), "", Optional.empty(),
                List.of(), List.of(), "", List.of());
        return new ManagedDelivery.Component(id, dependencies, new DeploymentRuntimeSpecification.ManagedProcess(
                new HealthCheck.Process(5, 1), RuntimeIdentityMode.SYSTEMD_STATIC, workload), List.of("build.zig"));
    }

    private static ManagedDelivery graph(List<ManagedDelivery.Component> components) {
        return new ManagedDelivery(components,
                new gold.debug.windowstolinux.shared.deploy.contract.ApplicationHealthGate(components.getFirst().id(),
                        new HealthCheck.Process(5, 1)));
    }

    @Test
    void readsUnsupportedProjectAndRevisesCommandFromActualFailure() throws Exception {
        Files.writeString(root.resolve("build.zig"), "// custom Zig build; no standard adapter\n");
        byte[] before = Files.readAllBytes(root.resolve("build.zig"));
        var graph = graph(List.of(component("worker", List.of())));
        var steps = new ArrayDeque<AgentProposal>(
                List.of(new AgentProposal.ListSource("", 0, 100), new AgentProposal.ReadSource("build.zig", 0, 100),
                        new AgentProposal.Plan(graph, "observed Zig project"),
                        new AgentProposal.Command("worker", "zig build", 20),
                        new AgentProposal.Command("worker", "zig build -Doptimize=ReleaseSafe", 20),
                        new AgentProposal.Seal("worker"), new AgentProposal.Deliver()));
        var remote = new FakeDelivery();
        var control = new AgentTaskControl(state -> {
        });
        AutonomousModelPort model = new AutonomousModelPort() {
            public AgentProposal decide(Map<String, Object> context, List<Map<String, String>> history, int budget) {
                var proposal = steps.remove();
                if (proposal instanceof AgentProposal.Plan)
                    assertTrue(history.stream()
                            .anyMatch(h -> h.get("tool").equals("READ") && h.get("output").contains("custom Zig")));
                if (proposal instanceof AgentProposal.Command c && c.script().contains("ReleaseSafe"))
                    assertTrue(history.stream().anyMatch(h -> h.get("output").contains("missing optimization option")));
                return proposal;
            }

            public boolean advance() {
                return false;
            }
        };
        var engine = session(model, remote, control);
        assertEquals(DeploymentStatus.SUCCEEDED, engine.run().status());
        assertEquals(2, remote.commands.size());
        assertArrayEquals(before, Files.readAllBytes(root.resolve("build.zig")));
        assertEquals(1, remote.deliveries);
    }

    @Test
    void validatesDependenciesAndWholeApplicationHealth() throws Exception {
        Files.writeString(root.resolve("build.zig"), "project\n");
        var graph = graph(List.of(component("web", List.of("api")), component("api", List.of())));
        assertEquals(List.of("api", "web"), graph.components().stream().map(ManagedDelivery.Component::id).toList());
        var steps = new ArrayDeque<AgentProposal>(List.of(new AgentProposal.ReadSource("build.zig", 0, 100),
                new AgentProposal.Plan(graph, "two components"),
                new AgentProposal.Command("web", "build web too soon", 5),
                new AgentProposal.Command("api", "build api", 5), new AgentProposal.Seal("api"),
                new AgentProposal.Command("web", "build web", 5), new AgentProposal.Seal("web"),
                new AgentProposal.Deliver()));
        var remote = new FakeDelivery();
        remote.failFirst = false;
        assertEquals(DeploymentStatus.SUCCEEDED, session(scripted(steps), remote, new AgentTaskControl(s -> {
        })).run().status());
        assertEquals(List.of("build api", "build web"), remote.commands);
        assertEquals(Set.of("api", "web"), remote.published);
        assertThrows(IllegalArgumentException.class,
                () -> graph(List.of(component("api", List.of("web")), component("web", List.of("api")))));
        assertThrows(IllegalArgumentException.class, () -> graph(List.of(component("api", List.of("missing")))));
    }

    @Test
    void unknownResultStopsWithoutReplayOrPublication() throws Exception {
        Files.writeString(root.resolve("build.zig"), "project\n");
        var graph = graph(List.of(component("api", List.of())));
        var remote = new FakeDelivery();
        remote.unknown = true;
        var control = new AgentTaskControl(s -> {
        });
        var steps = new ArrayDeque<AgentProposal>(
                List.of(new AgentProposal.ReadSource("build.zig", 0, 100), new AgentProposal.Plan(graph, "one"),
                        new AgentProposal.Command("api", "build", 5), new AgentProposal.Command("api", "build", 5)));
        assertThrows(IllegalStateException.class, () -> session(scripted(steps), remote, control).run());
        assertEquals(AgentTaskState.UNKNOWN, control.state());
        assertEquals(1, remote.commands.size());
        assertEquals(0, remote.deliveries);
    }

    @Test
    void modelCannotClaimHealthWithoutActualComponentEvidence() throws Exception {
        Files.writeString(root.resolve("build.zig"), "project\n");
        var graph = graph(List.of(component("api", List.of())));
        var remote = new FakeDelivery();
        remote.failFirst = false;
        remote.missingHealth = true;
        var steps = new ArrayDeque<AgentProposal>(List.of(new AgentProposal.ReadSource("build.zig", 0, 100),
                new AgentProposal.Plan(graph, "one"), new AgentProposal.Command("api", "build", 5),
                new AgentProposal.Seal("api"), new AgentProposal.Deliver()));
        var control = new AgentTaskControl(s -> {
        });
        assertThrows(IllegalStateException.class, () -> session(scripted(steps), remote, control).run());
        assertEquals(AgentTaskState.UNKNOWN, control.state());
    }

    private AutonomousDeploymentSession session(AutonomousModelPort model, FakeDelivery tools, AgentTaskControl control)
            throws Exception {
        var patch = new SourcePatchApproval("task", "server", AgentApprovalMode.FULL_CONTROL, (g, a,
                r) -> new AgentReview(AgentReviewDecision.ALLOW, r, a.binding(), "reviewed", List.of("sourceRevision")),
                (k, d) -> {
                    fail("unexpected user confirmation");
                    return false;
                }, event -> {
                }, control, () -> "v1");
        return new AutonomousDeploymentSession("task", Map.of(), new SourceBrowser(root), model, tools, patch, control,
                question -> Optional.empty(), event -> {
                }, event -> {
                });
    }

    @Test
    void patchInvalidatesAllComponentsAndRequiresRebuildAgainstNewRemoteRevision() throws Exception {
        Files.writeString(root.resolve("build.zig"), "original\n");
        var graph = graph(List.of(component("api", List.of()), component("web", List.of("api"))));
        var patch = new RemoteSourcePatch("build.zig", SHA, SHA,
                List.of(new RemoteSourcePatch.Edit(1, List.of("original"), List.of("fixed"))));
        var steps = new ArrayDeque<AgentProposal>(List.of(new AgentProposal.ReadSource("build.zig", 0, 100),
                new AgentProposal.Plan(graph, "two"), new AgentProposal.Command("api", "build api", 5),
                new AgentProposal.Seal("api"), new AgentProposal.Command("web", "build web", 5),
                new AgentProposal.Seal("web"), new AgentProposal.Patch("api", patch), new AgentProposal.Deliver(),
                new AgentProposal.Command("api", "rebuild api", 5), new AgentProposal.Seal("api"),
                new AgentProposal.Command("web", "rebuild web", 5), new AgentProposal.Seal("web"),
                new AgentProposal.Deliver()));
        var remote = new FakeDelivery() {
            @Override
            public String patch(String task, RemoteWorkspace workspace, RemoteSourcePatch proposal, String approval) {
                assertEquals(patch.binding(), approval);
                return "c".repeat(64);
            }

            @Override
            public RemoteCommandResult command(String task, RemoteWorkspace workspace, String revision, String script,
                    Duration timeout, long limit) {
                if (script.equals("rebuild api"))
                    assertEquals("c".repeat(64), revision);
                return super.command(task, workspace, revision, script, timeout, limit);
            }
        };
        remote.failFirst = false;
        var engine = session(scripted(steps), remote, new AgentTaskControl(s -> {
        }));
        String before = engine.revision();
        assertEquals(DeploymentStatus.SUCCEEDED, engine.run().status());
        assertEquals(1, remote.deliveries);
        assertEquals(4, remote.commands.size());
        assertNotEquals(before, engine.revision());
        assertEquals("original\n", Files.readString(root.resolve("build.zig")));
    }

    private static AutonomousModelPort scripted(Deque<AgentProposal> steps) {
        return new AutonomousModelPort() {
            public AgentProposal decide(Map<String, Object> context, List<Map<String, String>> history, int remaining) {
                return steps.remove();
            }

            public boolean advance() {
                return false;
            }
        };
    }
    private static class FakeDelivery implements AgentDeliveryPort, RemoteProjectPort {
        final List<String> commands = new ArrayList<>();

        boolean failFirst = true, unknown, missingHealth;

        int deliveries;

        Set<String> published = Set.of();
        public Map<String, String> inspect() {
            return Map.of("os", "fixture");
        }

        public Prepared prepare(ManagedDelivery.Component c) {
            return new Prepared(new RemoteWorkspace(c.id(), SHA), SHA);
        }

        public RemoteProjectPort projects() {
            return this;
        }

        public String open(String task, RemoteWorkspace workspace) {
            return SHA;
        }

        public String read(String task, RemoteWorkspace workspace, String revision, String path, int offset,
                int limit) {
            return "fixture";
        }

        public RemoteCommandResult command(String task, RemoteWorkspace workspace, String revision, String script,
                Duration timeout, long limit) {
            commands.add(script);
            boolean ok = !failFirst || commands.size() > 1;
            return new RemoteCommandResult(ok && !unknown, unknown, "", ok ? "built" : "missing optimization option",
                    "", unknown ? null : ok ? 0 : 2);
        }

        public String patch(String task, RemoteWorkspace workspace, RemoteSourcePatch patch, String approval) {
            throw new AssertionError();
        }

        public String seal(String task, RemoteWorkspace workspace, String revision) {
            return ARTIFACT;
        }

        public Result deliver(ManagedDelivery graph, Map<String, Prepared> candidates, Map<String, String> revisions,
                Map<String, String> artifacts) {
            deliveries++;
            published = Set.copyOf(artifacts.keySet());
            return new Result(DeploymentStatus.SUCCEEDED, missingHealth ? Set.of() : published,
                    "actual health fixture");
        }
    }
}
