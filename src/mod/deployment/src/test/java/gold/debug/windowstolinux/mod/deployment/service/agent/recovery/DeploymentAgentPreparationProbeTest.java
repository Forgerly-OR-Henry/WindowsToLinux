package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe.Reason;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe.Stage;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe.UnknownObservation;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationAbsence;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs Bash locally with explicit root/utility metadata substitutes and one emulated link; never asserts real root or remote acceptance. */
class DeploymentAgentPreparationProbeTest {
    @TempDir
    Path root;

    @Test
    void originalRenderedProbeIsValidBashWithoutExecutingIt() throws Exception {
        var result = bash(DeploymentAgentPreparationProbe.script(captured()), true);
        assertEquals(0, result.exitCode(), result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABSENT", "PRESENT", "ACTIVE_UNIT", "MOUNT", "LOOP", "UNSAFE_PARENT", "FAILED_ENUMERATION", "DANGLING_LINK", "UNTRUSTED_TOOL", "NON_ROOT", "PATH_RECHECK"})
    void actualGuardedBashRequiresAbsentCandidateAndEveryKernelObservation(String condition) throws Exception {
        var captured = captured();
        String script = DeploymentAgentPreparationProbe.script(captured)
            .replace("candidate='" + captured.workspace().candidateRoot() + "'", "candidate=\"$fixture_path/candidate\"")
            .replace("/usr/bin/stat", "fixture_stat").replace("/usr/bin/id -u", "fixture_id -u")
            .replace("/usr/bin/systemctl", "fixture_systemctl").replace("/usr/bin/findmnt", "fixture_findmnt")
            .replace("/usr/sbin/losetup", "fixture_losetup").replace("/usr/bin/find -H", "fixture_find -H")
            .replace("for tool in fixture_stat", "trusted_executable() { checked=SAFE; [ \"$condition\" != UNTRUSTED_TOOL ] || checked=UNKNOWN; }\nfor tool in fixture_stat");
        String fixture = "condition='" + condition + "'\n" + """
            fixture_path=$(pwd -P) || exit 69
            case "$condition" in
              PRESENT) mkdir -- "$fixture_path/candidate" || exit 69 ;;
              DANGLING_LINK)
                /usr/bin/ln -s -- nonexistent "$fixture_path/candidate" || exit 70
                [ -L "$fixture_path/candidate" ] || exit 70
                [ "$(/usr/bin/readlink -- "$fixture_path/candidate")" = nonexistent ] || exit 70
                [ ! -e "$fixture_path/candidate" ] || exit 70 ;;
            esac
            # Only metadata ownership/modes and systemd/mount/loop utilities are substituted.
            # Real Bash -e/-L, path traversal, readlink and directory enumeration remain active.
            fixture_stat() {
              if [ "$2" = '%u:%g' ]; then printf '0:0\n'
              elif [ "$2" = '%a' ]; then
                if [ "$condition" = UNSAFE_PARENT ] && [ "$4" = "$fixture_path" ]; then printf '777\n'; else printf '755\n'; fi
              else /usr/bin/stat "$@"; fi
            }
            fixture_id() { if [ "$condition" = NON_ROOT ]; then printf '1\n'; else printf '0\n'; fi; }
            fixture_systemctl() {
              case "$*" in *"windowstolinux-build-demo-api-cccccccccccccccc.service"*) ;; *) exit 65 ;; esac
              if [ "$condition" = ACTIVE_UNIT ]; then printf 'LoadState=loaded\nActiveState=active\nControlGroup=/system.slice/active\n'
              else printf 'LoadState=not-found\nActiveState=inactive\nControlGroup=\n'; fi
            }
            fixture_findmnt() { printf '/\n'; if [ "$condition" = MOUNT ]; then printf '%s/mutable\n' "$candidate"; fi; }
            fixture_losetup() {
              if [ "$condition" = LOOP ]; then printf '%s/.workspace.ext4 (deleted)\n' "$candidate"; fi
              # Local filesystem race before the final path check, never a production mutation.
              if [ "$condition" = PATH_RECHECK ]; then /usr/bin/mkdir -- "$candidate" || return 64; fi
            }
            fixture_find() { if [ "$condition" = FAILED_ENUMERATION ]; then return 64; fi; /usr/bin/find "$@"; }
            """;
        var result = bash(fixture + script, false, condition.equals("DANGLING_LINK"));
        if (condition.equals("DANGLING_LINK"))
            Assumptions.assumeTrue(result.exitCode() != 70, "Local Bash cannot create and read its controlled emulated symbolic link");
        assertEquals(0, result.exitCode(), result.output());
        var observed = new CommandResult(Optional.of(result.exitCode()), result.output(), "", false);
        if (condition.equals("ABSENT")) {
            assertTrue(DeploymentAgentPreparationProbe.absent(captured, observed), result.output());
            assertEquals(DeploymentAgentPreparationProbe.absentOutput(captured), result.output());
            assertTrue(DeploymentAgentPreparationProbe.unknown(captured, observed).isEmpty());
        } else {
            assertFalse(DeploymentAgentPreparationProbe.absent(captured, observed), result.output());
            assertEquals(expected(condition), DeploymentAgentPreparationProbe.unknown(captured, observed).orElseThrow(), result.output());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAGE", "REASON", "PAIR", "FOREIGN", "TRUNCATED", "NONZERO", "STDERR", "INCOMPLETE", "EXTRA"})
    void unknownDiagnosticRequiresTheEntireBoundCleanProtocol(String change) throws Exception {
        var captured = captured();
        String output = DeploymentAgentPreparationProbe.unknownOutput(captured, new UnknownObservation(Stage.MOUNTS, Reason.CANDIDATE_MOUNT_PRESENT));
        output = switch (change) {
            case "STAGE" -> output.replace("UNKNOWN_STAGE=MOUNTS", "UNKNOWN_STAGE=FAKE_PASSWORD_MARKER");
            case "REASON" ->
                output.replace("UNKNOWN_REASON=CANDIDATE_MOUNT_PRESENT", "UNKNOWN_REASON=FAKE_PASSWORD_MARKER");
            case "PAIR" -> output.replace("UNKNOWN_STAGE=MOUNTS", "UNKNOWN_STAGE=TOOLS");
            case "FOREIGN" -> output.replace(captured.task().toString(), UUID.randomUUID().toString());
            case "INCOMPLETE" -> output.substring(0, output.indexOf("UNKNOWN_REASON="));
            case "EXTRA" -> output + "SOURCE=FAKE_PASSWORD_MARKER\n";
            default -> output;
        };
        var observed = new CommandResult(Optional.of(change.equals("NONZERO") ? 1 : 0), output,
            change.equals("STDERR") ? "FAKE_PASSWORD_MARKER" : "", change.equals("TRUNCATED"));
        assertTrue(DeploymentAgentPreparationProbe.unknown(captured, observed).isEmpty());
        assertFalse(DeploymentAgentPreparationProbe.absent(captured, observed));
    }

    private static UnknownObservation expected(String condition) {
        return switch (condition) {
            case "UNTRUSTED_TOOL" -> new UnknownObservation(Stage.TOOLS, Reason.TOOL_UNCONFIRMED);
            case "NON_ROOT" -> new UnknownObservation(Stage.USER, Reason.USER_UNCONFIRMED);
            case "UNSAFE_PARENT" -> new UnknownObservation(Stage.PATH, Reason.PARENTS_UNCONFIRMED);
            case "PRESENT", "DANGLING_LINK", "FAILED_ENUMERATION" ->
                new UnknownObservation(Stage.PATH, Reason.PATH_ABSENCE_UNCONFIRMED);
            case "ACTIVE_UNIT" -> new UnknownObservation(Stage.BUILD_UNIT, Reason.BUILD_STATE_UNCONFIRMED);
            case "MOUNT" -> new UnknownObservation(Stage.MOUNTS, Reason.CANDIDATE_MOUNT_PRESENT);
            case "LOOP" -> new UnknownObservation(Stage.LOOPS, Reason.CANDIDATE_LOOP_PRESENT);
            case "PATH_RECHECK" -> new UnknownObservation(Stage.PATH_RECHECK, Reason.PATH_RECHECK_UNCONFIRMED);
            default -> throw new AssertionError(condition);
        };
    }

    private static DeploymentAgentPreparationAbsence.Captured captured() throws Exception {
        UUID task = UUID.randomUUID();
        var layout = DeploymentRemoteLayout.original();
        var identity = new DeploymentAgentActionLog.Identity("original-server", "d".repeat(64), "a".repeat(64), "FULL_CONTROL", true);
        var json = new ObjectMapper();
        var node = json.createObjectNode().put("formatVersion", 1).put("task", task.toString()).put("server", identity.server());
        node.set("layout", json.readTree(layout.canonical()));
        node.put("layoutSha256", layout.digest());
        var events = List.of(new DeploymentAgentActionLog.Event(1, "REMEMBER_AUTHORIZATION", "rememberAuthorization.v1", "true"),
            new DeploymentAgentActionLog.Event(2, "REMOTE_TARGET", "", "f".repeat(64)),
            new DeploymentAgentActionLog.Event(3, "REMOTE_LAYOUT", task.toString(), node.toString()),
            new DeploymentAgentActionLog.Event(4, "SOURCE", "c".repeat(64), "b".repeat(64)),
            new DeploymentAgentActionLog.Event(5, "TARGET_PROFILE", "", "f".repeat(64)),
            new DeploymentAgentActionLog.Event(6, "PLAN_APPLICATION", "", "demo"),
            new DeploymentAgentActionLog.Event(7, "PLAN", "", "e".repeat(64)),
            new DeploymentAgentActionLog.Event(8, "PREPARE_EXECUTING", DeploymentAgentPreparationAbsence.hash(task + ":api:" + "b".repeat(64)), "api"),
            new DeploymentAgentActionLog.Event(9, "STATE", "", "UNKNOWN"));
        return DeploymentAgentPreparationAbsence.capture(task, new DeploymentAgentActionLog.Snapshot(identity, "UNKNOWN", 4), layout, events).orElseThrow();
    }

    private Result bash(String input, boolean syntaxOnly) throws Exception {
        return bash(input, syntaxOnly, false);
    }

    private Result bash(String input, boolean syntaxOnly, boolean emulatedLink) throws Exception {
        String executable = System.getProperty("managed.test.bash", System.getProperty("os.name").startsWith("Windows")
            ? "C:\\Program Files\\Git\\bin\\bash.exe" : "/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(Path.of(executable)), "Configured local Bash is unavailable");
        var command = new java.util.ArrayList<>(List.of(executable, "--noprofile", "--norc"));
        if (syntaxOnly) command.add("-n");
        command.add("-s");
        var script = Files.createTempFile(root, "probe-input-", ".sh");
        Files.writeString(script, input, StandardCharsets.UTF_8);
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectInput(script.toFile()).redirectErrorStream(true);
        builder.environment().put("MSYS", emulatedLink ? "winsymlinks" : "winsymlinks:nativestrict");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Controlled local Bash must terminate");
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
            Files.deleteIfExists(script);
        }
    }

    private record Result(int exitCode, String output) {
    }
}
