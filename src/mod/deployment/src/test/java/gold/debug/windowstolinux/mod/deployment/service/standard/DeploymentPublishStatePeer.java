package gold.debug.windowstolinux.mod.deployment.service.standard;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

import gold.debug.windowstolinux.appapi.linux.*;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;

/** Tracks actual old content, process activity and task-owned snapshots for boundary acceptance. / 为边界验收跟踪实际旧内容、进程活动及任务所属快照。 */
final class DeploymentPublishStatePeer {
    final DeploymentAssistedBuildPeer builds = new DeploymentAssistedBuildPeer(false);

    final Set<String> running = new HashSet<>();

    final Map<String, Snapshot> snapshots = new HashMap<>();

    final Map<String, String> maintenance = new HashMap<>();

    int rollbacks, observations;

    DeploymentPublishStatePeer() {
        builds.publicationAttempts = true;
    }

    record Snapshot(String owner, UUID operation, Optional<String> current, boolean running) {
    }

    RemoteSession session(TaskContext task) throws ApiException {
        var original = builds.session(task);
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                List<String> values = method.getName().equals("execute")
                    ? quoted(((CommandRequest) args[0]).script())
                    : List.of();
                if (values.size() > 2) {
                    var intercepted = command(values, task);
                    if (intercepted.isPresent()) {
                        assertNotNull(args[1]);
                        builds.commands.add(((CommandRequest) args[0]).script());
                        return intercepted.orElseThrow();
                    }
                }
                Object result;
                try {
                    result = method.invoke(original, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
                if (values.size() > 2 && values.get(1).equals("publish-deployment")) {
                    assertEquals(TaskResult.SUCCEEDED, ((OperationResult<?>) result).outcome());
                    running.add(values.get(2));
                    maintenance.put(values.get(2), values.get(4));
                }
                if (values.size() > 3 && values.get(1).equals("application-maintenance")
                    && values.get(2).equals("end")) {
                    maintenance.remove(values.get(3));
                    var saved = snapshots.get(values.get(3));
                    if (saved != null && saved.current().isEmpty())
                        snapshots.remove(values.get(3));
                }
                return result;
            });
    }

    private Optional<OperationResult<CommandResult>> command(List<String> values, TaskContext task) {
        String verb = values.get(1), unit = values.get(2);
        if (verb.equals("retain")) {
            snapshots.remove(unit);
            return Optional.of(reply("RETAINED=1\n"));
        }
        if (Set.of("snapshot-deployment", "snapshot-deployment-attempt").contains(verb)) {
            assertEquals(task.id(), UUID.fromString(values.get(4)));
            UUID operation = verb.endsWith("-attempt") ? UUID.fromString(values.get(5)) : task.id();
            var current = Optional.ofNullable(builds.current.get(unit));
            if (verb.endsWith("-attempt")) {
                assertEquals(7, values.size());
                assertNotEquals(task.id(), operation);
                assertEquals(current.orElse("empty"), values.get(6));
            } else
                assertEquals(5, values.size());
            snapshots.put(unit, new Snapshot(values.get(3), operation, current, running.contains(unit)));
            return Optional.of(reply(current.isEmpty()
                ? "PREVIOUS=0\n"
                : "SNAPSHOT_TOKEN=" + operation + "\nPREVIOUS=1\nPREVIOUS_RUNNING="
                + (running.contains(unit) ? "1" : "0") + "\nPREVIOUS_DIGEST=" + current.orElseThrow()
                + "\nPREVIOUS_KIND=deployment\n"));
        }
        if (verb.equals("lifecycle-deployment")) {
            assertEquals("stop", values.get(3));
            assertEquals(builds.current.get(unit), values.get(5));
            assertTrue(running.remove(unit));
            return Optional.of(reply("LIFECYCLE=stop\n"));
        }
        if (Set.of("rollback-deployment", "rollback-deployment-first").contains(verb)) {
            var saved = snapshots.remove(unit);
            assertNotNull(saved);
            assertEquals(saved.owner(), values.get(4));
            if (saved.current().isPresent()) {
                assertEquals(6, values.size());
                assertEquals(saved.operation(), UUID.fromString(values.get(5)));
                builds.current.put(unit, saved.current().orElseThrow());
            } else {
                assertEquals(5, values.size());
                builds.current.remove(unit);
            }
            if (saved.running())
                running.add(unit);
            else
                running.remove(unit);
            rollbacks++;
            return Optional.of(reply("ROLLED_BACK=1\n"));
        }
        if (Set.of("snapshot-discard-task", "snapshot-discard-attempt").contains(verb)) {
            var saved = snapshots.remove(unit);
            assertNotNull(saved);
            assertEquals(saved.owner(), values.get(3));
            assertEquals(task.id(), UUID.fromString(values.get(4)));
            UUID operation = verb.endsWith("-attempt") ? UUID.fromString(values.get(5)) : task.id();
            assertEquals(saved.operation(), operation);
            assertEquals(saved.current(), Optional.ofNullable(builds.current.get(unit)));
            return Optional.of(reply("SNAPSHOT_DISCARDED=1\n"));
        }
        if (verb.equals("inspect-runtime")) {
            assertTrue(builds.current.containsKey(unit));
            return Optional.of(reply("KIND=deployment\nMODE=DAEMON\n"));
        }
        if (verb.equals("observe-deployment")) {
            assertTrue(builds.current.containsKey(unit));
            observations++;
            boolean active = running.contains(unit);
            return Optional.of(reply("OWNER=1\nRUNNING=" + (active ? "1" : "0")
                + "\nENABLED=enabled\nQUERY_OK=1\nActiveState=" + (active ? "active" : "inactive") + "\nSubState="
                + (active ? "running" : "dead") + "\nResult=success\nExecMainCode=0\nExecMainStatus=0\nMainPID="
                + (active ? "123" : "0") + "\n"));
        }
        if (Set.of("publication-original-status", "publication-original-status-attempt").contains(verb))
            return Optional.of(status(values, task));
        return Optional.empty();
    }

    private OperationResult<CommandResult> status(List<String> values, TaskContext task) {
        boolean attempt = values.get(1).endsWith("-attempt");
        String unit = values.get(2);
        assertEquals(task.id(), UUID.fromString(values.get(4)));
        UUID operation = attempt ? UUID.fromString(values.get(5)) : task.id();
        String candidate = values.get(attempt ? 6 : 5);
        var saved = snapshots.get(unit);
        boolean snapshot = saved != null && saved.current().isPresent();
        if (snapshot) {
            assertEquals(operation, saved.operation());
            assertEquals(values.get(3), saved.owner());
        }
        builds.statusReads++;
        var current = Optional.ofNullable(builds.current.get(unit));
        if (saved != null && saved.current().isEmpty() && current.isEmpty() && !builds.present.contains(candidate)
            && !maintenance.containsKey(unit) && !builds.active) {
            assertEquals(operation, saved.operation());
            assertEquals(values.get(3), saved.owner());
            assertFalse(saved.running());
            snapshots.remove(unit);
        }
        return reply("PUBLICATION_STATUS=1\nCURRENT=" + current.orElse("none") + "\nCURRENT_KIND="
            + (current.isPresent() ? "deployment" : "none") + "\nCURRENT_MODE="
            + (current.isPresent() ? "DAEMON" : "none") + "\nSNAPSHOT=" + (snapshot ? operation : "none")
            + "\nSNAPSHOT_OLD=" + (snapshot ? saved.current().orElseThrow() : "none") + "\nSNAPSHOT_KIND="
            + (snapshot ? "deployment" : "none") + "\nSNAPSHOT_RUNNING="
            + (snapshot ? saved.running() ? "1" : "0" : "none") + "\nMAINTENANCE="
            + (maintenance.containsKey(unit) ? "deployment-" + maintenance.get(unit) : "none") + "\nCANDIDATE="
            + (builds.present.contains(candidate) ? "owned" : "absent") + "\nBUILD_ACTIVE="
            + (builds.active ? "yes" : "no") + "\nUNIT_PRESENT=" + (current.isPresent() ? "yes" : "no")
            + "\nCONTAINER_PRESENT=not-applicable\nRETAINED_COUNT=" + (current.isPresent() ? "1" : "0") + "\n");
    }

    private static List<String> quoted(String script) {
        var values = new ArrayList<String>();
        var matcher = Pattern.compile("'([^']*)'").matcher(script);
        while (matcher.find())
            values.add(matcher.group(1));
        return values;
    }

    private static OperationResult<CommandResult> reply(String value) {
        return new OperationResult<>(TaskResult.SUCCEEDED,
            Optional.of(new CommandResult(Optional.of(0), value, "", false)), Optional.empty());
    }
}
