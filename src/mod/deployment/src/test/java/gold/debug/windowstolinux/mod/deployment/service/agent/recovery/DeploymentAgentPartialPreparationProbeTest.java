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
import java.util.Map;
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
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationCompensation;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Executes only controlled local Bash metadata fixtures; no real host, helper or server. */
class DeploymentAgentPartialPreparationProbeTest {
    @TempDir
    Path root;

    @Test
    void fixedBeforeAndAfterScriptsParseAndKeepOriginalHelperBytes() throws Exception {
        var c = captured();
        assertEquals(0, bash(DeploymentAgentPartialPreparationProbe.script(c, false), true).exitCode());
        assertEquals(0, bash(DeploymentAgentPartialPreparationProbe.script(c, true), true).exitCode());
        assertEquals(DeploymentAgentPreparationProbe.script(c.absenceBinding()), DeploymentAgentPartialPreparationProbe.script(c, true));
        assertFalse(DeploymentAgentPartialPreparationProbe.script(c, false).contains("\nrm "));
        assertFalse(DeploymentAgentPartialPreparationProbe.script(c, false).contains("--detach"));
    }

    @Test
    void failedRejectionDiagnosticWriteKeepsOriginalExitCode() throws Exception {
        String script = DeploymentAgentPartialPreparationProbe.script(captured(), false);
        int start = script.indexOf("reject() {"), end = script.indexOf('\n', start);
        assertTrue(start >= 0 && end > start);
        assertEquals(-1, script.indexOf("reject() {", end));
        var result = bash("set -e\n" + script.substring(start, end) + "\nexec 2>&-\nreject FILES NON_PRIVATE_FILE\n", false);
        assertEquals(64, result.exitCode());
        assertEquals("", result.output());
        assertEquals("", result.error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OWNED", "FOREIGN_TASK", "SAME_PREFIX", "MISSING_ARCHIVE", "HARDLINK", "BUILDER", "IMAGE", "RESTORE", "ACTIVE_BUILD", "EXTRA_BIND", "SHARED_DEVICE", "EXTRA_LOOP", "WRONG_BACKING", "ABSENT", "ABSENT_MOUNT", "ABSENT_LOOP"})
    void completeArchiveAndExactOwnershipRejectEveryUnexplainedEffect(String condition) throws Exception {
        var c = captured();
        String script = DeploymentAgentPartialPreparationProbe.script(c, false)
            .replace("candidate='" + c.workspace().candidateRoot() + "'", "candidate=\"$fixture_path/candidate\"")
            .replace("/usr/bin/stat", "fixture_stat").replace("/usr/bin/id -u", "fixture_id -u")
            .replace("/usr/bin/systemctl", "fixture_systemctl").replace("/usr/bin/findmnt", "fixture_findmnt")
            .replace("/usr/sbin/losetup", "fixture_losetup")
            .replace("for tool in fixture_stat", "trusted_executable() { checked=SAFE; }\nfor tool in fixture_stat");
        int start = script.indexOf("status=$(\n"), finish = script.indexOf("[ \"$status\" = ", start);
        script = script.substring(0, start) + "status='CANDIDATE_STATE=owned\nBUILD_ACTIVE=no'\n[ \"$condition\" != ACTIVE_BUILD ] || status='CANDIDATE_STATE=owned\nBUILD_ACTIVE=yes'\n" + script.substring(finish);
        String fixture = "condition='" + condition + "'\nexpected_task='" + c.task() + "'\n" + """
            fixture_path=$(pwd -P) || exit 69
            if [[ "$condition" != ABSENT* ]]; then
              mkdir -p -- "$fixture_path/candidate/mutable" || exit 69
              printf 'root\n' > "$fixture_path/candidate/.deployer"
              printf '%s\n' "$expected_task" > "$fixture_path/candidate/.agent-task"
              printf 'build\n' > "$fixture_path/candidate/.purpose"
              printf '/dev/loop7\n' > "$fixture_path/candidate/.workspace-device"
              printf '67108864\n' > "$fixture_path/candidate/.workspace-limit"
              : > "$fixture_path/candidate/.workspace.ext4"
              printf 'controlled archive\n' > "$fixture_path/candidate/mutable/source.tar.gz"
              case "$condition" in
                FOREIGN_TASK) printf '00000000-0000-0000-0000-000000000000\n' > "$fixture_path/candidate/.agent-task" ;;
                SAME_PREFIX) printf 'changed archive\n' > "$fixture_path/candidate/mutable/source.tar.gz" ;;
                MISSING_ARCHIVE) rm -- "$fixture_path/candidate/mutable/source.tar.gz" ;;
                BUILDER) : > "$fixture_path/candidate/.builder-intent" ;;
                IMAGE) : > "$fixture_path/candidate/.image-engine" ;;
                RESTORE) mkdir -- "$fixture_path/candidate/mutable/restore-activation" ;;
              esac
            fi
            fixture_stat() {
              if [ "$2" = '%u:%g' ]; then printf '0:0\n'
              elif [ "$2" = '%a' ]; then printf '755\n'
              elif [ "$2" = '%s' ] && [[ "$4" = */.workspace.ext4 ]]; then printf '67108864\n'
              elif [ "$2" = '%h' ] && [ "$condition" = HARDLINK ] && [[ "$4" = */source.tar.gz ]]; then printf '2\n'
              else /usr/bin/stat "$@"; fi
            }
            fixture_id() { printf '0\n'; }
            fixture_systemctl() { printf 'LoadState=not-found\nActiveState=inactive\nControlGroup=\n'; }
            fixture_findmnt() {
              if [ "$*" = '--kernel --noheadings --raw --output TARGET' ]; then
                printf '/\n'
                [ "$condition" != ABSENT_MOUNT ] || printf '%s/mutable\n' "$candidate"
                return 0
              fi
              printf '/ /dev/root ext4\n'
              printf '%s/mutable /dev/loop7 ext4\n' "$candidate"
              [ "$condition" != EXTRA_BIND ] || printf '%s/mutable/alias /dev/root ext4\n' "$candidate"
              [ "$condition" != SHARED_DEVICE ] || printf '/opt/shared /dev/loop7[/other] ext4\n'
              return 0
            }
            fixture_losetup() {
              if [[ "$*" = *'--output BACK-FILE /dev/loop7' ]]; then
                if [ "$condition" = WRONG_BACKING ]; then printf '/opt/foreign.ext4\n'; else printf '%s/.workspace.ext4\n' "$candidate"; fi
              elif [[ "$*" = *'--output NAME,BACK-FILE'* ]]; then
                printf '/dev/loop7 %s/.workspace.ext4\n' "$candidate"
                [ "$condition" != EXTRA_LOOP ] || printf '/dev/loop8 %s/unexplained.ext4\n' "$candidate"
              elif [ "$condition" = ABSENT_LOOP ]; then printf '%s/.workspace.ext4 (deleted)\n' "$candidate"; fi
              return 0
            }
            """;
        var result = bash(fixture + script, false);
        var observed = new CommandResult(Optional.of(result.exitCode()), result.output(), result.error(), false);
        if (condition.equals("OWNED"))
            assertTrue(DeploymentAgentPartialPreparationProbe.present(c, observed), result.error());
        else if (condition.equals("ABSENT"))
            assertTrue(DeploymentAgentPartialPreparationProbe.absent(c, observed), result.output() + result.error());
        else {
            assertFalse(DeploymentAgentPartialPreparationProbe.present(c, observed), result.output());
            assertFalse(DeploymentAgentPartialPreparationProbe.absent(c, observed), result.output());
            if (!condition.startsWith("ABSENT")) {
                var expected = Map.ofEntries(Map.entry("FOREIGN_TASK", "TASK_MISMATCH"), Map.entry("SAME_PREFIX", "ARCHIVE_SHA_MISMATCH"),
                    Map.entry("MISSING_ARCHIVE", "UNEXPLAINED_MUTABLE_ENTRY"), Map.entry("HARDLINK", "NON_PRIVATE_FILE"),
                    Map.entry("BUILDER", "UNEXPLAINED_ROOT_ENTRY"), Map.entry("IMAGE", "UNEXPLAINED_ROOT_ENTRY"),
                    Map.entry("RESTORE", "UNEXPLAINED_MUTABLE_ENTRY"), Map.entry("ACTIVE_BUILD", "NOT_OWNED_OR_BUILD_ACTIVE"),
                    Map.entry("EXTRA_BIND", "UNEXPLAINED_CANDIDATE_MOUNT"), Map.entry("SHARED_DEVICE", "SHARED_DEVICE_MOUNT"),
                    Map.entry("EXTRA_LOOP", "UNEXPLAINED_CANDIDATE_LOOP"), Map.entry("WRONG_BACKING", "BACKING_FILE_MISMATCH"));
                assertEquals(64, observed.exitCode().orElseThrow());
                var rejection = DeploymentAgentPartialPreparationProbe.rejection(observed).orElseThrow(() -> new AssertionError(result.error()));
                assertEquals(expected.get(condition), rejection.reason().name());
                assertEquals("", observed.stdout());
                assertFalse(observed.stderr().contains(root.toString()));
                assertFalse(observed.stderr().contains(c.task().toString()));
            }
        }
        if (condition.equals("OWNED") || condition.equals("ABSENT")) {
            assertEquals("", observed.stderr());
            assertEquals(condition.equals("OWNED") ? DeploymentAgentPartialPreparationProbe.presentOutput(c) : DeploymentAgentPartialPreparationProbe.absentOutput(c), observed.stdout());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXACT", "UNKNOWN_ENUM", "PAIR_MISMATCH", "APPENDED", "PREFIXED", "CRLF", "STDOUT", "TRUNCATED", "NONZERO", "MISSING_NEWLINE", "EXIT_ABSENT"})
    void fixedRejectionsExposeOnlyStaticStageAndReason(String condition) throws Exception {
        String output = "FORMAT=WTL_AGENT_PARTIAL_REJECTION_1\nSTAGE=FILES\nREASON=NON_PRIVATE_FILE\n";
        output = switch (condition) {
            case "UNKNOWN_ENUM" -> output.replace("NON_PRIVATE_FILE", "UNTRUSTED_REMOTE_VALUE");
            case "PAIR_MISMATCH" -> output.replace("STAGE=FILES", "STAGE=MOUNTS");
            case "APPENDED" -> output + "password=FAKE_PASSWORD_MARKER\n";
            case "PREFIXED" -> "password=FAKE_PASSWORD_MARKER\n" + output;
            case "CRLF" -> output.replace("\n", "\r\n");
            case "MISSING_NEWLINE" -> output.stripTrailing();
            default -> output;
        };
        var observed = new CommandResult(condition.equals("EXIT_ABSENT") ? Optional.empty() : Optional.of(condition.equals("NONZERO") ? 1 : 64),
            condition.equals("STDOUT") ? "unexpected" : "", output, condition.equals("TRUNCATED"));
        var diagnostic = DeploymentAgentPartialPreparationProbe.rejection(observed);
        assertEquals(condition.equals("EXACT"), diagnostic.isPresent());
        if (diagnostic.isPresent()) {
            assertEquals(DeploymentAgentPartialPreparationProbe.Stage.FILES, diagnostic.orElseThrow().stage());
            assertEquals(DeploymentAgentPartialPreparationProbe.Reason.NON_PRIVATE_FILE, diagnostic.orElseThrow().reason());
        }
        var c = captured();
        assertFalse(DeploymentAgentPartialPreparationProbe.present(c, observed));
        assertFalse(DeploymentAgentPartialPreparationProbe.absent(c, observed));
        var matcher = java.util.regex.Pattern.compile("reject ([A-Z]+) ([A-Z_]+)").matcher(DeploymentAgentPartialPreparationProbe.script(c, false));
        var reasons = java.util.EnumSet.noneOf(DeploymentAgentPartialPreparationProbe.Reason.class);
        while (matcher.find()) {
            var result = new CommandResult(Optional.of(64), "", "FORMAT=WTL_AGENT_PARTIAL_REJECTION_1\nSTAGE=" + matcher.group(1)
                + "\nREASON=" + matcher.group(2) + "\n", false);
            reasons.add(DeploymentAgentPartialPreparationProbe.rejection(result).orElseThrow().reason());
        }
        assertEquals(java.util.EnumSet.allOf(DeploymentAgentPartialPreparationProbe.Reason.class), reasons);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ARCHIVE", "TRUNCATED", "STDERR", "NONZERO", "UNKNOWN_EXIT", "TASK", "EVENTS"})
    void onlyCompleteBoundCleanOutputCanSupportProof(String change) throws Exception {
        var c = captured();
        String output = DeploymentAgentPartialPreparationProbe.presentOutput(c);
        if (change.equals("ARCHIVE"))
            output = output.replace(c.workspace().archiveSha256(), c.workspace().archiveSha256().substring(0, 16) + "0".repeat(48));
        if (change.equals("TASK")) output = output.replace(c.task().toString(), UUID.randomUUID().toString());
        if (change.equals("EVENTS"))
            output = output.replace(DeploymentAgentPreparationAbsence.digest(c.events()), "0".repeat(64));
        var result = new CommandResult(change.equals("UNKNOWN_EXIT") ? Optional.empty() : Optional.of(change.equals("NONZERO") ? 1 : 0), output, change.equals("STDERR") ? "unknown" : "", change.equals("TRUNCATED"));
        assertFalse(DeploymentAgentPartialPreparationProbe.present(c, result));
    }

    private static DeploymentAgentPreparationCompensation.Captured captured() throws Exception {
        UUID task = UUID.fromString("191bab33-4c43-400a-816d-f4ba78f4b785");
        var layout = DeploymentRemoteLayout.original();
        var identity = new DeploymentAgentActionLog.Identity("original-server", "a".repeat(64), "d".repeat(64), "FULL_CONTROL", true);
        String snapshot = "b".repeat(64), archive = DeploymentAgentPreparationAbsence.hash("controlled archive\n");
        var node = new ObjectMapper().createObjectNode().put("formatVersion", 1).put("task", task.toString()).put("server", identity.server());
        node.set("layout", new ObjectMapper().readTree(layout.canonical()));
        node.put("layoutSha256", layout.digest());
        var events = List.of(new DeploymentAgentActionLog.Event(1, "REMEMBER_AUTHORIZATION", "rememberAuthorization.v1", "true"),
            new DeploymentAgentActionLog.Event(2, "REMOTE_TARGET", "", "f".repeat(64)), new DeploymentAgentActionLog.Event(3, "REMOTE_LAYOUT", task.toString(), node.toString()),
            new DeploymentAgentActionLog.Event(4, "SOURCE", archive, snapshot), new DeploymentAgentActionLog.Event(5, "TARGET_PROFILE", "", "e".repeat(64)),
            new DeploymentAgentActionLog.Event(98, "PLAN_APPLICATION", "", "demo"), new DeploymentAgentActionLog.Event(99, "PLAN", "", "e".repeat(64)),
            new DeploymentAgentActionLog.Event(118, "PREPARE_EXECUTING", DeploymentAgentPreparationAbsence.hash(task + ":api:" + snapshot), "api"),
            new DeploymentAgentActionLog.Event(119, "HELPER_READINESS_OBSERVED", "f".repeat(64), "MISSING:HELPER_MISSING"),
            new DeploymentAgentActionLog.Event(120, "HELPER_READINESS_OBSERVED", "f".repeat(64), "READY:CURRENT_TASK_READY"), new DeploymentAgentActionLog.Event(121, "STATE", "", "UNKNOWN"));
        return DeploymentAgentPreparationCompensation.capture(task, new DeploymentAgentActionLog.Snapshot(identity, "UNKNOWN", 4), layout, events).orElseThrow();
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
