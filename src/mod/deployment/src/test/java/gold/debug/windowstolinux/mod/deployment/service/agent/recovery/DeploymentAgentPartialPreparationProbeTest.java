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
        var observed = fixture(c, condition);
        if (condition.equals("OWNED"))
            assertTrue(DeploymentAgentPartialPreparationProbe.present(c, observed), observed.stderr());
        else if (condition.equals("ABSENT"))
            assertTrue(DeploymentAgentPartialPreparationProbe.absent(c, observed), observed.stdout() + observed.stderr());
        else {
            assertFalse(DeploymentAgentPartialPreparationProbe.present(c, observed), observed.stdout());
            assertFalse(DeploymentAgentPartialPreparationProbe.absent(c, observed), observed.stdout());
            if (!condition.startsWith("ABSENT")) {
                var expected = Map.ofEntries(Map.entry("FOREIGN_TASK", "TASK_MISMATCH"), Map.entry("SAME_PREFIX", "ARCHIVE_SHA_MISMATCH"),
                    Map.entry("MISSING_ARCHIVE", "UNEXPLAINED_MUTABLE_ENTRY"), Map.entry("HARDLINK", "NON_PRIVATE_FILE"),
                    Map.entry("BUILDER", "UNEXPLAINED_ROOT_ENTRY"), Map.entry("IMAGE", "UNEXPLAINED_ROOT_ENTRY"),
                    Map.entry("RESTORE", "UNEXPLAINED_MUTABLE_ENTRY"), Map.entry("ACTIVE_BUILD", "NOT_OWNED_OR_BUILD_ACTIVE"),
                    Map.entry("EXTRA_BIND", "UNEXPLAINED_CANDIDATE_MOUNT"), Map.entry("SHARED_DEVICE", "SHARED_DEVICE_MOUNT"),
                    Map.entry("EXTRA_LOOP", "UNEXPLAINED_CANDIDATE_LOOP"), Map.entry("WRONG_BACKING", "BACKING_FILE_MISMATCH"));
                assertEquals(64, observed.exitCode().orElseThrow());
                var rejection = DeploymentAgentPartialPreparationProbe.rejection(observed).orElseThrow(() -> new AssertionError(observed.stderr()));
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
    @ValueSource(strings = {"OWNED", "MISSING_ARCHIVE", "ABSENT", "LF_CREATED"})
    void stageObservationDistinguishesUploadedCreatedAndAbsent(String condition) throws Exception {
        var c = captured();
        var result = fixture(c, condition, true);
        var expected = condition.equals("OWNED") ? DeploymentAgentPartialPreparationProbe.PreparationStage.UPLOADED
            : condition.equals("ABSENT") ? DeploymentAgentPartialPreparationProbe.PreparationStage.ABSENT
            : DeploymentAgentPartialPreparationProbe.PreparationStage.CREATED_WITHOUT_ARCHIVE;
        assertEquals(Optional.of(expected), DeploymentAgentPartialPreparationProbe.stage(c, result), result.stdout() + result.stderr());
        assertEquals(DeploymentAgentPartialPreparationProbe.stageOutput(c, expected), result.stdout());
        assertFalse(DeploymentAgentPartialPreparationProbe.present(c, result));
        assertFalse(DeploymentAgentPartialPreparationProbe.absent(c, result));
        if (expected == DeploymentAgentPartialPreparationProbe.PreparationStage.CREATED_WITHOUT_ARCHIVE) {
            assertEquals("", result.stderr());
            assertEquals(64, fixture(c, condition).exitCode().orElseThrow(), "The original uploaded-only producer remains unchanged");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATED_PART", "CREATED_SOURCE", "CREATED_NEWLINE", "CREATED_MODE", "CREATED_QUOTA", "CREATED_STAT_FAILED", "FOREIGN_TASK", "SHARED_DEVICE", "ACTIVE_BUILD"})
    void createdStageRejectsUnboundOrUncertainState(String condition) throws Exception {
        var c = captured();
        var result = fixture(c, condition, true);
        assertEquals(Optional.empty(), DeploymentAgentPartialPreparationProbe.stage(c, result), condition);
        assertEquals(64, result.exitCode().orElseThrow(), result.stderr());
        assertEquals("", result.stdout());
        assertFalse(result.stderr().contains(root.toString()));
    }

    @Test
    void stageConsumerRetainsExactOutputAndEmptyStderrBoundaries() throws Exception {
        var c = captured();
        String output = DeploymentAgentPartialPreparationProbe.stageOutput(c, DeploymentAgentPartialPreparationProbe.PreparationStage.CREATED_WITHOUT_ARCHIVE);
        for (var bad : List.of(new CommandResult(Optional.of(0), output + "\n", "", false),
            new CommandResult(Optional.of(0), output.stripTrailing(), "", false),
            new CommandResult(Optional.of(0), output.replace(c.task().toString(), UUID.randomUUID().toString()), "", false),
            new CommandResult(Optional.of(0), output.replace(c.layout().digest(), "0".repeat(64)), "", false),
            new CommandResult(Optional.of(0), output.replace("STATUS=OWNED_CREATED_WITHOUT_ARCHIVE_INACTIVE", "STATUS=UNOBSERVED"), "", false),
            new CommandResult(Optional.of(0), output, "FAKE_SECRET_MARKER", false),
            new CommandResult(Optional.of(64), output, "", false), new CommandResult(Optional.empty(), output, "", false),
            new CommandResult(Optional.of(0), output, "", true)))
            assertTrue(DeploymentAgentPartialPreparationProbe.stage(c, bad).isEmpty());
        for (String condition : List.of("LEGACY_EXTRA_LF", "LEGACY_STDERR", "LEGACY_NUL")) {
            var actual = fixture(c, condition, true);
            assertTrue(DeploymentAgentPartialPreparationProbe.stage(c, actual).isEmpty(), condition);
            if (condition.equals("LEGACY_EXTRA_LF")) assertTrue(actual.stdout().endsWith("\n\n"));
            else assertFalse(actual.stderr().isEmpty(), "Actual producer stderr must reach the strict consumer");
        }
        try (var old = DeploymentAgentPartialPreparationProbe.request(c, false);
             var typed = DeploymentAgentPartialPreparationProbe.stageRequest(c, 67108864L)) {
            assertFalse(Arrays.equals(old.stdin(), typed.stdin()));
            assertFalse(DeploymentAgentPartialPreparationProbe.requestDigest(c, false)
                .equals(DeploymentAgentPartialPreparationProbe.stageRequestDigest(c, 67108864L)));
        }
    }

    private CommandResult fixture(DeploymentAgentPreparationCompensation.Captured c, String condition) throws Exception {
        return fixture(c, condition, false);
    }

    private CommandResult fixture(DeploymentAgentPreparationCompensation.Captured c, String condition, boolean typed) throws Exception {
        String script = (typed ? DeploymentAgentPartialPreparationProbe.stageScript(c, 67108864L) : DeploymentAgentPartialPreparationProbe.script(c, false))
            .replace("candidate='" + c.workspace().candidateRoot() + "'", "candidate=\"$fixture_path/candidate\"")
            .replace("/usr/bin/stat", "fixture_stat").replace("/usr/bin/id -u", "fixture_id -u")
            .replace("/usr/bin/systemctl", "fixture_systemctl").replace("/usr/bin/findmnt", "fixture_findmnt")
            .replace("/usr/sbin/losetup", "fixture_losetup")
            .replace("/usr/bin/find", "fixture_find")
            .replace("for tool in fixture_stat", "trusted_executable() { checked=SAFE; }\nfor tool in fixture_stat");
        int start = script.indexOf("status=$(\n"), finish = script.indexOf("[ \"$status\" = ", start);
        script = script.substring(0, start) + "status='CANDIDATE_STATE=owned\nBUILD_ACTIVE=no'\ncase \"$condition\" in ACTIVE_BUILD | LF_ACTIVE_BUILD) status='CANDIDATE_STATE=owned\nBUILD_ACTIVE=yes' ;; esac\n" + script.substring(finish);
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
                FOREIGN_TASK | LF_FOREIGN_TASK) printf '00000000-0000-0000-0000-000000000000\n' > "$fixture_path/candidate/.agent-task" ;;
                SAME_PREFIX | LF_ARCHIVE) printf 'changed archive\n' > "$fixture_path/candidate/mutable/source.tar.gz" ;;
                MISSING_ARCHIVE | CREATED_* | LF_CREATED) rm -- "$fixture_path/candidate/mutable/source.tar.gz" ;;
                BUILDER) : > "$fixture_path/candidate/.builder-intent" ;;
                IMAGE) : > "$fixture_path/candidate/.image-engine" ;;
                RESTORE) mkdir -- "$fixture_path/candidate/mutable/restore-activation" ;;
              esac
              case "$condition" in
                CREATED_PART) : > "$fixture_path/candidate/mutable/.wtl-transfer-controlled.part" ;;
                CREATED_SOURCE) mkdir -- "$fixture_path/candidate/mutable/input-source" ;;
                CREATED_NEWLINE) : > "$fixture_path/candidate/"$'.purpose\\n.deployer' ;;
                CREATED_MODE) printf 'protected-source\\n' > "$fixture_path/candidate/.agent-mode" ;;
                CREATED_QUOTA) printf '134217728\\n' > "$fixture_path/candidate/.workspace-limit" ;;
                LEGACY_NUL) printf '%s\\0' "$expected_task" > "$fixture_path/candidate/.agent-task" ;;
              esac
              if [[ "$condition" = LF_* ]]; then
                case "$condition" in
                  LF_FILE) : > "$fixture_path/candidate/mutable/lost+found" ;;
                  LF_SYMLINK) mkdir -- "$fixture_path/foreign-directory"; ln -s -- "$fixture_path/foreign-directory" "$fixture_path/candidate/mutable/lost+found" || exit 69 ;;
                  *) mkdir -- "$fixture_path/candidate/mutable/lost+found" ;;
                esac
                case "$condition" in
                  LF_FILE_ENTRY) printf 'retained recovery data\n' > "$fixture_path/candidate/mutable/lost+found/retained" ;;
                  LF_DIR_ENTRY) mkdir -- "$fixture_path/candidate/mutable/lost+found/retained" ;;
                  LF_UNKNOWN_SIBLING) mkdir -- "$fixture_path/candidate/mutable/another-entry" ;;
                esac
              fi
            fi
            fixture_stat() {
              if [[ "$4" = */mutable/lost+found ]]; then
                case "$2" in
                  '%u:%g') if [ "$condition" = LF_OWNER ]; then printf '1000:1000\n'; else printf '0:0\n'; fi; return 0 ;;
                  '%a') if [ "$condition" = LF_MODE ]; then printf '755\n'; else printf '700\n'; fi; return 0 ;;
                  '%d:%i:%F:%u:%g:%a:%h:%s:%y:%z')
                    [ "$condition" != LF_STAT_FAILED ] || return 1
                    [ "$condition" != LF_POST_STAT_FAILED ] || [ ! -e "$fixture_path/stamp-changed" ] || return 1
                    case "$condition" in
                      LF_INVALID_STAMP) printf 'UNTRUSTED_METADATA\n'; return 0 ;;
                      LF_STAMP_LIMIT) printf '%0300d\n' 0; return 0 ;;
                      LF_STAMP_NEWLINE) printf '12345:11:directory:0:0:700:2:16384:one\ntwo\n'; return 0 ;;
                    esac
                    local device=12345 links=2 instant='2026-10-12 00:00:00.000000000 +0000'
                    [ "$condition" != LF_VOLUME ] || device=54321
                    [ "$condition" != LF_LINKS ] || links=3
                    [ ! -e "$fixture_path/stamp-changed" ] || instant='2026-10-12 00:00:00.000000001 +0000'
                    printf '%s:11:directory:0:0:700:%s:16384:%s:%s\n' "$device" "$links" "$instant" "$instant"
                    return 0 ;;
                esac
              fi
              if [ "$condition" = CREATED_STAT_FAILED ] && [ "$2" = '%s' ] && [[ "$4" = */.workspace-limit ]]; then return 1; fi
              if [ "$2" = '%u:%g' ]; then printf '0:0\n'
              elif [ "$2" = '%a' ]; then printf '755\n'
              elif [ "$2" = '%d' ] && [[ "$4" = */mutable ]]; then printf '12345\n'
              elif [ "$2" = '%s' ] && [[ "$4" = */.workspace.ext4 ]]; then printf '67108864\n'
              elif [ "$2" = '%h' ] && [ "$condition" = HARDLINK ] && [[ "$4" = */source.tar.gz ]]; then printf '2\n'
              else /usr/bin/stat "$@"; fi
            }
            fixture_find() {
              if [ "$1" = -P ] && [ "$2" = "$candidate/mutable/lost+found" ]; then
                [ "$condition" != LF_FIND_FAILED ] || return 1
                /usr/bin/find "$@"
                local result=$?
                case "$condition" in LF_CHANGED | LF_POST_STAT_FAILED) : > "$fixture_path/stamp-changed" ;; esac
                return "$result"
              fi
              /usr/bin/find "$@"
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
              [ "$condition" != LF_EXTRA_MOUNT ] || printf '%s/mutable/lost+found /dev/foreign ext4\n' "$candidate"
              [ "$condition" != LF_SHARED_DEVICE ] || printf '/opt/shared /dev/loop7[/other] ext4\n'
              return 0
            }
            fixture_losetup() {
              if [[ "$*" = *'--output BACK-FILE /dev/loop7' ]]; then
                if [ "$condition" = WRONG_BACKING ]; then printf '/opt/foreign.ext4\n'; else printf '%s/.workspace.ext4\n' "$candidate"; fi
              elif [[ "$*" = *'--output NAME,BACK-FILE'* ]]; then
                printf '/dev/loop7 %s/.workspace.ext4\n' "$candidate"
                [ "$condition" != EXTRA_LOOP ] || printf '/dev/loop8 %s/unexplained.ext4\n' "$candidate"
                [ "$condition" != LF_EXTRA_LOOP ] || printf '/dev/loop8 %s/mutable/lost+found/unexplained.ext4\n' "$candidate"
              elif [ "$condition" = ABSENT_LOOP ]; then printf '%s/.workspace.ext4 (deleted)\n' "$candidate"; fi
              return 0
            }
            """;
        if (typed && condition.equals("LEGACY_EXTRA_LF")) {
            String footer = "printf '%s' '" + DeploymentAgentPartialPreparationProbe.stageOutput(c, DeploymentAgentPartialPreparationProbe.PreparationStage.UPLOADED) + "'\n";
            assertEquals(1, script.split(java.util.regex.Pattern.quote(footer), -1).length - 1);
            script = script.replace(footer, footer + "printf '\\n'\n");
        }
        if (typed && condition.equals("LEGACY_STDERR"))
            script += "printf 'FAKE_SECRET_MARKER\\n' >&2\n";
        String directory = "case-" + UUID.randomUUID();
        var result = bash("mkdir -- '" + directory + "' || exit 69\ncd -- '" + directory + "' || exit 69\n" + fixture + script, false, condition.equals("LF_SYMLINK"));
        return new CommandResult(Optional.of(result.exitCode()), result.output(), result.error(), false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LF_EMPTY", "LF_FILE_ENTRY", "LF_DIR_ENTRY", "LF_SYMLINK", "LF_FILE", "LF_OWNER", "LF_MODE", "LF_LINKS", "LF_VOLUME",
        "LF_STAT_FAILED", "LF_FIND_FAILED", "LF_CHANGED", "LF_UNKNOWN_SIBLING", "LF_EXTRA_MOUNT", "LF_SHARED_DEVICE", "LF_EXTRA_LOOP",
        "LF_FOREIGN_TASK", "LF_ARCHIVE", "LF_ACTIVE_BUILD", "LF_INVALID_STAMP", "LF_STAMP_LIMIT", "LF_STAMP_NEWLINE", "LF_POST_STAT_FAILED"})
    void standardExt4DirectoryRequiresEmptyPrivateStableMetadataAndOriginalBindings(String condition) throws Exception {
        var c = captured();
        var observed = fixture(c, condition);
        assertEquals(condition.equals("LF_EMPTY"), DeploymentAgentPartialPreparationProbe.present(c, observed), observed.stderr());
        assertFalse(DeploymentAgentPartialPreparationProbe.absent(c, observed));
        if (condition.equals("LF_EMPTY")) {
            assertEquals(DeploymentAgentPartialPreparationProbe.presentOutput(c), observed.stdout());
            assertEquals("", observed.stderr());
        } else {
            assertEquals(64, observed.exitCode().orElseThrow(), observed.stderr());
            var expected = switch (condition) {
                case "LF_FILE_ENTRY", "LF_DIR_ENTRY" -> "STANDARD_DIRECTORY_NONEMPTY";
                case "LF_SYMLINK", "LF_FILE", "LF_OWNER", "LF_MODE" -> "STANDARD_DIRECTORY_UNSAFE";
                case "LF_LINKS", "LF_STAT_FAILED", "LF_INVALID_STAMP", "LF_STAMP_LIMIT", "LF_STAMP_NEWLINE",
                     "LF_POST_STAT_FAILED" -> "STANDARD_DIRECTORY_METADATA_UNCONFIRMED";
                case "LF_VOLUME" -> "STANDARD_DIRECTORY_VOLUME_MISMATCH";
                case "LF_FIND_FAILED" -> "STANDARD_DIRECTORY_SCAN_FAILED";
                case "LF_CHANGED" -> "STANDARD_DIRECTORY_CHANGED";
                case "LF_UNKNOWN_SIBLING" -> "UNEXPLAINED_MUTABLE_ENTRY";
                case "LF_EXTRA_MOUNT" -> "UNEXPLAINED_CANDIDATE_MOUNT";
                case "LF_SHARED_DEVICE" -> "SHARED_DEVICE_MOUNT";
                case "LF_EXTRA_LOOP" -> "UNEXPLAINED_CANDIDATE_LOOP";
                case "LF_FOREIGN_TASK" -> "TASK_MISMATCH";
                case "LF_ARCHIVE" -> "ARCHIVE_SHA_MISMATCH";
                case "LF_ACTIVE_BUILD" -> "NOT_OWNED_OR_BUILD_ACTIVE";
                default -> throw new AssertionError(condition);
            };
            assertEquals(expected, DeploymentAgentPartialPreparationProbe.rejection(observed).orElseThrow(() -> new AssertionError(observed.stderr())).reason().name());
            assertEquals("", observed.stdout());
            assertFalse(observed.stderr().contains(root.toString()));
            assertFalse(observed.stderr().contains("retained recovery data"));
        }
    }

    @Test
    void previousExactOwnedAndAbsentResultsRemainUnchanged() throws Exception {
        var c = captured();
        var owned = fixture(c, "OWNED");
        var absent = fixture(c, "ABSENT");
        assertTrue(DeploymentAgentPartialPreparationProbe.present(c, owned), owned.stderr());
        assertEquals(DeploymentAgentPartialPreparationProbe.presentOutput(c), owned.stdout());
        assertTrue(DeploymentAgentPartialPreparationProbe.absent(c, absent), absent.stdout() + absent.stderr());
        assertEquals(DeploymentAgentPartialPreparationProbe.absentOutput(c), absent.stdout());
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

    @Test
    void privateFileMetadataObservationReportsOnlyFixedSameCallFacts() throws Exception {
        var captured = captured();
        String originalDigest = DeploymentAgentPartialPreparationProbe.requestDigest(captured, false);
        for (String condition : List.of("SAFE", "DIRECTORY", "SYMLINK", "HARDLINK", "MISSING", "MODE", "OWNER", "STAT_FAIL", "MALFORMED", "MUTABLE_LINK", "PARENTS")) {
            String name = "metadata-" + UUID.randomUUID();
            String prefix = """
                candidate="$fixture_path/CASE_NAME"
                calls="$fixture_path/CASE_NAME.calls"
                mkdir -p "$candidate/mutable"
                for file in .deployer .agent-task .workspace-device .workspace-limit .purpose .workspace.ext4 .agent-mode mutable/source.tar.gz; do
                  printf 'public-body-FAKE_SECRET_MARKER' > "$candidate/$file"
                done
                condition='CONDITION'
                case "$condition" in
                  DIRECTORY) rm "$candidate/.deployer"; mkdir "$candidate/.deployer" ;;
                  SYMLINK) rm "$candidate/.agent-task"; ln -s .purpose "$candidate/.agent-task" ;;
                  HARDLINK) rm "$candidate/.workspace.ext4"; ln "$candidate/.purpose" "$candidate/.workspace.ext4" ;;
                  MISSING) rm "$candidate/.purpose" ;;
                  MUTABLE_LINK) mv "$candidate/mutable" "$candidate/other"; ln -s other "$candidate/mutable" ;;
                esac
                trusted_executable() { checked=SAFE; }
                fixture_id() { printf '0\\n'; }
                managed_parents() { checked=SAFE; [ "$condition" != PARENTS ] || checked=UNSAFE; }
                path_metadata() { checked=UNSAFE; [ ! -L "$1" ] && [ -d "$1" ] && checked=SAFE; return 0; }
                fixture_stat() {
                  printf '1\\n' >> "$calls"
                  if [ "$condition" = STAT_FAIL ] && [[ "$4" = */.workspace.ext4 ]]; then printf 'FAKE_SECRET_MARKER' >&2; return 1; fi
                  if [ "$condition" = MALFORMED ] && [[ "$4" = */.workspace.ext4 ]]; then printf 'FAKE_SECRET_MARKER\\n'; return 0; fi
                  local value kind uid gid mode links
                  value=$(/usr/bin/stat "$@") || return 1
                  IFS=: read -r kind uid gid mode links <<< "$value"
                  # Windows/root and mode are explicit fixture substitutes; type and nlink use real GNU stat.
                  uid=0; gid=0; mode=444
                  if [ "$condition" = MODE ] && [[ "$4" = */.workspace.ext4 ]]; then mode=777; fi
                  if [ "$condition" = OWNER ] && [[ "$4" = */.workspace.ext4 ]]; then uid=42; fi
                  printf '%s:%s:%s:%s:%s\\n' "$kind" "$uid" "$gid" "$mode" "$links"
                }
                """.replace("CASE_NAME", name).replace("CONDITION", condition);
            String script = "fixture_path=$(pwd -P)\n" + DeploymentAgentPartialPreparationProbe.fileMetadataScript(captured)
                .replace("/usr/bin/stat", "fixture_stat").replace("/usr/bin/id", "fixture_id")
                .replace("# Companion observation only:", prefix + "\n# Companion observation only:");
            var result = bash(script, false, true);
            var observed = new CommandResult(Optional.of(result.exitCode()), result.output(), result.error(), false);
            var rows = DeploymentAgentPartialPreparationProbe.fileMetadata(observed).orElseThrow(() -> new AssertionError(condition + ":" + result));
            assertEquals(8, rows.size());
            assertFalse(DeploymentAgentPartialPreparationProbe.present(captured, observed));
            assertFalse(DeploymentAgentPartialPreparationProbe.absent(captured, observed));
            assertFalse(result.output().contains("FAKE_SECRET_MARKER"));
            assertEquals("", result.error());
            assertFalse(result.output().contains(name));
            var image = rows.get(5);
            switch (condition) {
                case "SAFE" -> assertEquals(0, result.exitCode());
                case "DIRECTORY" ->
                    assertEquals(DeploymentAgentPartialPreparationProbe.FileKind.DIRECTORY, rows.get(0).kind());
                case "SYMLINK" ->
                    assertEquals(DeploymentAgentPartialPreparationProbe.FileKind.SYMLINK, rows.get(1).kind());
                case "HARDLINK" ->
                    assertEquals(DeploymentAgentPartialPreparationProbe.FileLinks.MULTIPLE, image.links());
                case "MISSING" ->
                    assertEquals(DeploymentAgentPartialPreparationProbe.MetadataStatus.UNOBSERVED, rows.get(4).status());
                case "MODE" ->
                    assertEquals(DeploymentAgentPartialPreparationProbe.GroupOtherWrite.YES, image.groupOtherWrite());
                case "OWNER" -> assertEquals(DeploymentAgentPartialPreparationProbe.RootOwner.NON_ROOT, image.owner());
                case "STAT_FAIL" ->
                    assertEquals(DeploymentAgentPartialPreparationProbe.MetadataStatus.UNOBSERVED, image.status());
                case "MALFORMED" ->
                    assertEquals(DeploymentAgentPartialPreparationProbe.MetadataStatus.UNCONFIRMED, image.status());
                case "MUTABLE_LINK" ->
                    assertEquals(DeploymentAgentPartialPreparationProbe.MetadataStatus.UNCONFIRMED, rows.get(7).status());
                case "PARENTS" ->
                    assertTrue(rows.stream().allMatch(row -> row.status() == DeploymentAgentPartialPreparationProbe.MetadataStatus.UNCONFIRMED));
                default -> throw new AssertionError(condition);
            }
            if (!condition.equals("SAFE")) assertEquals(64, result.exitCode());
            var counter = root.resolve(name + ".calls");
            assertEquals(condition.equals("PARENTS") ? 0 : condition.equals("MUTABLE_LINK") ? 7 : 8,
                Files.exists(counter) ? Files.readAllLines(counter).size() : 0, "One lstat per permitted role, no re-query");
            Files.deleteIfExists(counter);
        }
        assertEquals(originalDigest, DeploymentAgentPartialPreparationProbe.requestDigest(captured, false));
    }

    @Test
    void privateFileMetadataProtocolRejectsMalformedAndForeignOutput() {
        String output = privateMetadataOutput();
        var good = new CommandResult(Optional.of(0), output, "", false);
        assertTrue(DeploymentAgentPartialPreparationProbe.fileMetadata(good).isPresent());
        String rejected = output.replaceFirst("KIND=REGULAR", "KIND=SYMLINK");
        assertTrue(DeploymentAgentPartialPreparationProbe.fileMetadata(new CommandResult(Optional.of(64), rejected, "", false)).isPresent());
        for (String invalid : List.of(output + "password=FAKE_SECRET_MARKER\n", output.replace("DEPLOYER", "OTHER_ROLE"),
            output.replace("KIND=REGULAR", "KIND=FAKE_SECRET_MARKER"), output.replace("STATUS=OBSERVED", "STATUS=UNOBSERVED"),
            output.replace("ROLE=AGENT_TASK", "ROLE=DEPLOYER"), output.replace("GROUP_OTHER_WRITE=NO", "GROUP_OTHER_WRITE=NO;RAW=password"),
            output.replace("\n", "\r\n"), output.stripTrailing(), output.replace("ROOT", "ROOT\u0000"), output.repeat(8)))
            assertTrue(DeploymentAgentPartialPreparationProbe.fileMetadata(new CommandResult(Optional.of(0), invalid, "", false)).isEmpty());
        for (var invalid : List.of(new CommandResult(Optional.empty(), output, "", false), new CommandResult(Optional.of(1), output, "", false),
            new CommandResult(Optional.of(64), output, "", false), new CommandResult(Optional.of(0), output, "password=FAKE_SECRET_MARKER", false),
            new CommandResult(Optional.of(0), output, "", true)))
            assertTrue(DeploymentAgentPartialPreparationProbe.fileMetadata(invalid).isEmpty());
    }

    private static String privateMetadataOutput() {
        var output = new StringBuilder("FORMAT=WTL_AGENT_PRIVATE_FILE_METADATA_1\n");
        for (var role : DeploymentAgentPartialPreparationProbe.FileRole.values())
            output.append("ROLE=").append(role)
                .append(";STATUS=OBSERVED;KIND=REGULAR;OWNER=ROOT;GROUP_OTHER_WRITE=NO;LINKS=SINGLE\n");
        return output.toString();
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
