package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe.Reason;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe.Stage;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe.Mismatch;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe.StdoutKind;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.DeploymentAgentPreparationProbe.UnknownObservation;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentActionLog;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationAbsence;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
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
    @ValueSource(strings = {"ABSENT", "PRESENT", "ACTIVE_UNIT", "MOUNT", "LOOP", "UNSAFE_PARENT", "FAILED_ENUMERATION", "DANGLING_LINK", "UNTRUSTED_TOOL", "NON_ROOT", "PATH_RECHECK", "ARGUMENT_CONFLICT"})
    void actualGuardedBashRequiresAbsentCandidateAndEveryKernelObservation(String condition) throws Exception {
        var captured = captured();
        String script = DeploymentAgentPreparationProbe.script(captured)
            .replace("candidate='" + captured.workspace().candidateRoot() + "'", "candidate=\"$fixture_path/candidate\"")
            .replace("/usr/bin/stat", "fixture_stat").replace("/usr/bin/id -u", "fixture_id -u")
            .replace("/usr/bin/systemctl", "fixture_systemctl").replace("/usr/bin/findmnt", "fixture_findmnt")
            .replace("/usr/sbin/losetup", "fixture_losetup").replace("/usr/bin/find -H", "fixture_find -H")
            .replace("for tool in fixture_stat", "trusted_executable() { checked=SAFE; [ \"$condition\" != UNTRUSTED_TOOL ] || checked=UNKNOWN; }\nfor tool in fixture_stat");
        if (condition.equals("ARGUMENT_CONFLICT"))
            script = script.replace("fixture_findmnt --kernel --noheadings", "fixture_findmnt --kernel --list --noheadings");
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
            fixture_findmnt() {
              # util-linux findmnt treats --list and --raw as mutually exclusive output formats.
              local has_list=0 has_raw=0 option
              for option in "$@"; do
                case "$option" in --list) has_list=1 ;; --raw) has_raw=1 ;; esac
              done
              if [ "$has_list" = 1 ] && [ "$has_raw" = 1 ]; then
                printf 'fixture: mutually exclusive output formats\n' >&2
                return 64
              fi
              printf '/\n'
              if [ "$condition" = MOUNT ]; then printf '%s/mutable\n' "$candidate"; fi
            }
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
        var observed = new CommandResult(Optional.of(result.exitCode()), result.output(), result.error(), false);
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
    @ValueSource(strings = {"TRUNCATED", "NONZERO", "MISSING_EXIT"})
    void unknownProjectionPreservesExitAndTruncationGates(String change) throws Exception {
        var captured = captured();
        String output = DeploymentAgentPreparationProbe.unknownOutput(captured, new UnknownObservation(Stage.MOUNTS, Reason.CANDIDATE_MOUNT_PRESENT));
        var observed = new CommandResult(change.equals("MISSING_EXIT") ? Optional.empty() : Optional.of(change.equals("NONZERO") ? 1 : 0),
            output, "password=FAKE_PASSWORD_MARKER", change.equals("TRUNCATED"));
        assertEquals(StdoutKind.EXACT_UNKNOWN, DeploymentAgentPreparationProbe.stdoutShape(captured, output).kind());
        assertTrue(DeploymentAgentPreparationProbe.unknown(captured, observed).isEmpty());
        assertFalse(DeploymentAgentPreparationProbe.absent(captured, observed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "FORMAT", "TASK", "PREPARE", "CANDIDATE", "LAYOUT", "EVENTS", "HEADER_FIELD", "HEADER_SHORT", "STATUS_FIELD", "STATUS_VALUE",
        "TAIL_FIELD", "TAIL_SHORT", "NO_NEWLINE", "CRLF", "STAGE_ENUM", "REASON_ENUM", "PAIR", "PREFIX", "SUFFIX", "DUPLICATE", "BLANK", "OUTPUT_LIMIT"})
    void stdoutShapeReportsOnlyFixedMismatchCategories(String change) throws Exception {
        var captured = captured();
        String original = DeploymentAgentPreparationProbe.unknownOutput(captured, new UnknownObservation(Stage.MOUNTS, Reason.CANDIDATE_MOUNT_PRESENT));
        String output = switch (change) {
            case "EMPTY" -> "";
            case "FORMAT" -> original.replace("FORMAT=WTL_AGENT_PREPARATION_ABSENCE_1", "FORMAT=FAKE_PASSWORD_MARKER");
            case "TASK" -> original.replace(captured.task().toString(), UUID.randomUUID().toString());
            case "PREPARE" ->
                original.replace("PREPARE_EVENT=" + captured.preparation().id(), "PREPARE_EVENT=FAKE_PASSWORD_MARKER");
            case "CANDIDATE" ->
                original.replace("CANDIDATE=" + captured.workspace().candidateId(), "CANDIDATE=FAKE_PASSWORD_MARKER");
            case "LAYOUT" ->
                original.replace("LAYOUT_SHA256=" + captured.layout().digest(), "LAYOUT_SHA256=FAKE_PASSWORD_MARKER");
            case "EVENTS" ->
                original.replace("EVENTS_SHA256=" + DeploymentAgentPreparationAbsence.digest(captured.events()), "EVENTS_SHA256=FAKE_PASSWORD_MARKER");
            case "HEADER_FIELD" -> original.replace("TASK=", "TASK_REPEAT=");
            case "HEADER_SHORT" -> original.substring(0, original.indexOf("TASK="));
            case "STATUS_FIELD" -> original.replace("STATUS=UNKNOWN", "STATUS=FAKE_PASSWORD_MARKER");
            case "STATUS_VALUE" -> original.replace("PATH=UNCONFIRMED", "PATH=ABSENT");
            case "TAIL_FIELD" -> original.replace("UNKNOWN_REASON=", "UNKNOWN_STAGE=");
            case "TAIL_SHORT" -> original.substring(0, original.indexOf("UNKNOWN_REASON="));
            case "NO_NEWLINE" -> original.substring(0, original.length() - 1);
            case "CRLF" -> original.replace("\n", "\r\n");
            case "STAGE_ENUM" -> original.replace("UNKNOWN_STAGE=MOUNTS", "UNKNOWN_STAGE=FAKE_PASSWORD_MARKER");
            case "REASON_ENUM" ->
                original.replace("UNKNOWN_REASON=CANDIDATE_MOUNT_PRESENT", "UNKNOWN_REASON=FAKE_PASSWORD_MARKER");
            case "PAIR" -> original.replace("UNKNOWN_STAGE=MOUNTS", "UNKNOWN_STAGE=TOOLS");
            case "PREFIX" -> "FAKE_PASSWORD_MARKER\n" + original;
            case "SUFFIX" -> original + "FAKE_PASSWORD_MARKER\n";
            case "DUPLICATE" -> original + "UNKNOWN_STAGE=MOUNTS\n";
            case "BLANK" -> original + "\n";
            case "OUTPUT_LIMIT" -> original + "FAKE_PASSWORD_MARKER".repeat(4096);
            default -> throw new AssertionError(change);
        };
        var shape = DeploymentAgentPreparationProbe.stdoutShape(captured, output);
        assertEquals(change.equals("EMPTY") ? StdoutKind.EMPTY : StdoutKind.INVALID, shape.kind());
        Mismatch mismatch = switch (change) {
            case "EMPTY" -> Mismatch.NONE;
            case "FORMAT", "TASK", "PREPARE", "CANDIDATE", "LAYOUT", "EVENTS" -> Mismatch.HEADER_BINDING;
            case "HEADER_FIELD", "HEADER_SHORT" -> Mismatch.HEADER_FIELDS;
            case "STATUS_FIELD", "STATUS_VALUE" -> Mismatch.STATUS_FIELDS;
            case "TAIL_FIELD", "TAIL_SHORT" -> Mismatch.TAIL_FIELDS;
            case "NO_NEWLINE", "CRLF" -> Mismatch.LINE_ENDING;
            case "STAGE_ENUM", "REASON_ENUM" -> Mismatch.INVALID_ENUM;
            case "PAIR" -> Mismatch.PAIR_MISMATCH;
            case "PREFIX", "SUFFIX", "DUPLICATE", "BLANK" -> Mismatch.EXTRA_CONTENT;
            case "OUTPUT_LIMIT" -> Mismatch.OUTPUT_LIMIT;
            default -> throw new AssertionError(change);
        };
        assertEquals(mismatch, shape.mismatch());
        assertFalse(shape.toString().contains("FAKE_PASSWORD_MARKER"));
        var result = new CommandResult(Optional.of(0), output, "password=FAKE_PASSWORD_MARKER", false);
        assertTrue(DeploymentAgentPreparationProbe.unknown(captured, result).isEmpty());
        assertFalse(DeploymentAgentPreparationProbe.absent(captured, result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "ABSENT"})
    void actualNativeFrameAndOriginalLauncherKeepSensitiveStderrSeparateWithoutSettling(String status) throws Exception {
        var captured = captured();
        String output = status.equals("UNKNOWN")
            ? DeploymentAgentPreparationProbe.unknownOutput(captured, new UnknownObservation(Stage.PATH, Reason.PATH_ABSENCE_UNCONFIRMED))
            : DeploymentAgentPreparationProbe.absentOutput(captured);
        String error = "password=FAKE_PASSWORD_MARKER 源码";
        String fixture = "builtin printf '%s' '" + output + "'\nbuiltin printf '%s' '" + error + "' >&2\n";
        try (var request = new CommandRequest(DeploymentAgentHelperInstallerScript.LAUNCHER, fixture.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(10), 4096)) {
            var frameType = Class.forName("gold.debug.windowstolinux.app.linux.ssh.connect.SshCommandFrame");
            var constructor = frameType.getDeclaredConstructor(CommandRequest.class);
            constructor.setAccessible(true);
            var write = frameType.getDeclaredMethod("writeTo", OutputStream.class);
            write.setAccessible(true);
            var command = frameType.getDeclaredField("COMMAND");
            command.setAccessible(true);
            try (var frame = (AutoCloseable) constructor.newInstance(request); var wire = new ByteArrayOutputStream()) {
                write.invoke(frame, wire);
                byte[] bytes = wire.toByteArray();
                Path entry = Files.createTempFile(root, "wire-command-", ".sh");
                Result observed;
                try {
                    Files.writeString(entry, (String) command.get(null), StandardCharsets.UTF_8);
                    observed = bash(List.of(bashExecutable(), "--noprofile", "--norc", entry.toString()), bytes, false);
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                    Files.deleteIfExists(entry);
                }
                assertEquals(0, observed.exitCode(), observed.error());
                assertEquals(output, observed.output());
                assertEquals(error, observed.error());
                var result = new CommandResult(Optional.of(0), observed.output(), observed.error(), false);
                assertEquals(status.equals("UNKNOWN") ? StdoutKind.EXACT_UNKNOWN : StdoutKind.EXACT_ABSENT,
                    DeploymentAgentPreparationProbe.stdoutShape(captured, result.stdout()).kind());
                assertEquals(status.equals("UNKNOWN"), DeploymentAgentPreparationProbe.unknown(captured, result).isPresent());
                assertFalse(DeploymentAgentPreparationProbe.absent(captured, result), "Even exact ABSENT stdout cannot clear a nonempty stderr");
            }
        }
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
            case "ARGUMENT_CONFLICT" -> new UnknownObservation(Stage.MOUNTS, Reason.MOUNT_QUERY_UNCONFIRMED);
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
        var command = new java.util.ArrayList<>(List.of(bashExecutable(), "--noprofile", "--norc"));
        if (syntaxOnly) command.add("-n");
        command.add("-s");
        return bash(command, input.getBytes(StandardCharsets.UTF_8), emulatedLink);
    }

    private static String bashExecutable() {
        String executable = System.getProperty("managed.test.bash", System.getProperty("os.name").startsWith("Windows")
            ? "C:\\Program Files\\Git\\bin\\bash.exe" : "/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(Path.of(executable)), "Configured local Bash is unavailable");
        return executable;
    }

    private Result bash(List<String> command, byte[] input, boolean emulatedLink) throws Exception {
        var script = Files.createTempFile(root, "probe-input-", ".sh");
        var stdout = Files.createTempFile(root, "probe-stdout-", ".out");
        var stderr = Files.createTempFile(root, "probe-stderr-", ".err");
        Files.write(script, input);
        var builder = new ProcessBuilder(command).directory(root.toFile()).redirectInput(script.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().put("MSYS", emulatedLink ? "winsymlinks" : "winsymlinks:nativestrict");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Controlled local Bash must terminate");
            return new Result(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8), Files.readString(stderr, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
            Files.deleteIfExists(script);
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private record Result(int exitCode, String output, String error) {
    }
}
