package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationAbsence;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperReadiness;

/** Fixed read-only metadata, independent of the managed helper and without candidate mutation. */
public final class DeploymentAgentPreparationProbe {
    public static final String OPERATION = "deployment.agent.preparation.absence.v1";

    private static final String UNKNOWN = "STATUS=UNKNOWN\nPATH=UNCONFIRMED\nBUILD=UNCONFIRMED\nMOUNTS=UNCONFIRMED\nLOOPS=UNCONFIRMED\n";

    public enum Stage {TOOLS, USER, PATH, BUILD_UNIT, MOUNTS, LOOPS, PATH_RECHECK}

    public enum Reason {
        TOOL_UNCONFIRMED(Stage.TOOLS), USER_UNCONFIRMED(Stage.USER), PARENTS_UNCONFIRMED(Stage.PATH),
        PATH_ABSENCE_UNCONFIRMED(Stage.PATH), BUILD_QUERY_UNCONFIRMED(Stage.BUILD_UNIT), BUILD_STATE_UNCONFIRMED(Stage.BUILD_UNIT),
        MOUNT_QUERY_UNCONFIRMED(Stage.MOUNTS), MOUNT_OUTPUT_LIMIT(Stage.MOUNTS), MOUNT_TARGET_INVALID(Stage.MOUNTS), CANDIDATE_MOUNT_PRESENT(Stage.MOUNTS),
        LOOP_QUERY_UNCONFIRMED(Stage.LOOPS), LOOP_OUTPUT_LIMIT(Stage.LOOPS), LOOP_BACKING_INVALID(Stage.LOOPS), CANDIDATE_LOOP_PRESENT(Stage.LOOPS),
        PATH_RECHECK_UNCONFIRMED(Stage.PATH_RECHECK);

        private final Stage stage;

        Reason(Stage stage) {
            this.stage = stage;
        }
    }

    public record UnknownObservation(Stage stage, Reason reason) {
        public UnknownObservation {
            if (stage == null || reason == null || stage != reason.stage)
                throw new IllegalArgumentException("Preparation observation stage and reason differ");
        }
    }

    public enum StdoutKind {EMPTY, EXACT_ABSENT, EXACT_UNKNOWN, INVALID}

    public enum Mismatch {NONE, OUTPUT_LIMIT, LINE_ENDING, HEADER_FIELDS, HEADER_BINDING, STATUS_FIELDS, TAIL_FIELDS, INVALID_ENUM, PAIR_MISMATCH, EXTRA_CONTENT}

    public record StdoutShape(StdoutKind kind, Mismatch mismatch, Optional<UnknownObservation> unknown) {
        public StdoutShape {
            if (kind == null || mismatch == null || unknown == null || (kind == StdoutKind.EXACT_UNKNOWN) != unknown.isPresent()
                || (kind == StdoutKind.INVALID) != (mismatch != Mismatch.NONE))
                throw new IllegalArgumentException("Preparation stdout shape is inconsistent");
        }
    }

    private DeploymentAgentPreparationProbe() {
    }

    public static CommandRequest request(DeploymentAgentPreparationAbsence.Captured captured) {
        byte[] input = script(captured).getBytes(StandardCharsets.UTF_8);
        try {
            return new CommandRequest(DeploymentAgentHelperInstallerScript.LAUNCHER, input, Duration.ofSeconds(30), 4096);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    public static String requestDigest(DeploymentAgentPreparationAbsence.Captured captured) {
        try (var request = request(captured)) {
            return DeploymentAgentPreparationAbsence.hash(RemoteActions.command(new ServerId(captured.snapshot().identity().server()),
                captured.snapshot().identity().configurationDigest(), request).exactAction());
        }
    }

    public static String absentOutput(DeploymentAgentPreparationAbsence.Captured captured) {
        return header(captured) + "STATUS=ABSENT\nPATH=ABSENT\nBUILD=ABSENT\nMOUNTS=ABSENT\nLOOPS=ABSENT\n";
    }

    public static boolean absent(DeploymentAgentPreparationAbsence.Captured captured, CommandResult result) {
        return result != null && !result.truncated() && result.exitCode().equals(Optional.of(0)) && result.stderr().isEmpty()
            && absentOutput(captured).equals(result.stdout());
    }

    public static String unknownOutput(DeploymentAgentPreparationAbsence.Captured captured, UnknownObservation observation) {
        return header(captured) + UNKNOWN + "UNKNOWN_STAGE=" + observation.stage() + "\nUNKNOWN_REASON=" + observation.reason() + "\n";
    }

    /** Diagnostic-only UNKNOWN projection accepts stderr; absence authorization still requires an empty stderr. */
    public static Optional<UnknownObservation> unknown(DeploymentAgentPreparationAbsence.Captured captured, CommandResult result) {
        if (result == null || result.truncated() || !result.exitCode().equals(Optional.of(0))) return Optional.empty();
        return stdoutShape(captured, result.stdout()).unknown();
    }

    /** Classifies only stdout bytes already decoded by the host, without projecting received field values. */
    public static StdoutShape stdoutShape(DeploymentAgentPreparationAbsence.Captured captured, String stdout) {
        if (stdout.isEmpty()) return new StdoutShape(StdoutKind.EMPTY, Mismatch.NONE, Optional.empty());
        if (stdout.length() > 4096) return invalid(Mismatch.OUTPUT_LIMIT);
        if (stdout.indexOf('\r') >= 0 || !stdout.endsWith("\n")) return invalid(Mismatch.LINE_ENDING);
        String expectedHeader = header(captured);
        String[] header = expectedHeader.split("\n");
        String[] fields = stdout.split("\n", -1);
        if (!stdout.startsWith("FORMAT=") && stdout.contains(expectedHeader)) return invalid(Mismatch.EXTRA_CONTENT);
        if (fields.length < header.length + 1) return invalid(Mismatch.HEADER_FIELDS);
        for (int index = 0; index < header.length; index++) {
            String key = header[index].substring(0, header[index].indexOf('=') + 1);
            if (!fields[index].startsWith(key)) return invalid(Mismatch.HEADER_FIELDS);
            if (!fields[index].equals(header[index])) return invalid(Mismatch.HEADER_BINDING);
        }
        if (fields.length < 12) return invalid(Mismatch.STATUS_FIELDS);
        boolean absent = fields[6].equals("STATUS=ABSENT");
        if (!absent && !fields[6].equals("STATUS=UNKNOWN")) return invalid(Mismatch.STATUS_FIELDS);
        String status = absent ? "ABSENT" : "UNCONFIRMED";
        if (!fields[7].equals("PATH=" + status) || !fields[8].equals("BUILD=" + status)
            || !fields[9].equals("MOUNTS=" + status) || !fields[10].equals("LOOPS=" + status))
            return invalid(Mismatch.STATUS_FIELDS);
        if (absent)
            return fields.length == 12 ? new StdoutShape(StdoutKind.EXACT_ABSENT, Mismatch.NONE, Optional.empty()) : invalid(Mismatch.EXTRA_CONTENT);
        if (fields.length < 14) return invalid(Mismatch.TAIL_FIELDS);
        if (fields.length > 14) return invalid(Mismatch.EXTRA_CONTENT);
        if (!fields[11].startsWith("UNKNOWN_STAGE=") || !fields[12].startsWith("UNKNOWN_REASON="))
            return invalid(Mismatch.TAIL_FIELDS);
        Stage stage;
        Reason reason;
        try {
            stage = Stage.valueOf(fields[11].substring("UNKNOWN_STAGE=".length()));
            reason = Reason.valueOf(fields[12].substring("UNKNOWN_REASON=".length()));
        } catch (IllegalArgumentException invalid) {
            return invalid(Mismatch.INVALID_ENUM);
        }
        if (stage != reason.stage) return invalid(Mismatch.PAIR_MISMATCH);
        return new StdoutShape(StdoutKind.EXACT_UNKNOWN, Mismatch.NONE, Optional.of(new UnknownObservation(stage, reason)));
    }

    private static StdoutShape invalid(Mismatch mismatch) {
        return new StdoutShape(StdoutKind.INVALID, mismatch, Optional.empty());
    }

    private static String header(DeploymentAgentPreparationAbsence.Captured captured) {
        return "FORMAT=WTL_AGENT_PREPARATION_ABSENCE_1\nTASK=" + captured.task() + "\nPREPARE_EVENT=" + captured.preparation().id()
            + "\nCANDIDATE=" + captured.workspace().candidateId() + "\nLAYOUT_SHA256=" + captured.layout().digest()
            + "\nEVENTS_SHA256=" + DeploymentAgentPreparationAbsence.digest(captured.events()) + "\n";
    }

    public static String script(DeploymentAgentPreparationAbsence.Captured captured) {
        // Reuse the existing protected system-path and permission/absence guards, not its helper checks.
        String readiness = DeploymentAgentHelperReadiness.script(captured.layout());
        String begin = "# WTL_READINESS_GUARDS_BEGIN\n";
        String end = "# WTL_READINESS_GUARDS_END";
        int first = readiness.indexOf(begin);
        int last = readiness.indexOf(end);
        if (first < 0 || last <= first || readiness.indexOf(begin, first + 1) >= 0 || readiness.indexOf(end, last + 1) >= 0)
            throw new IllegalStateException("Preparation metadata guards are unavailable");
        String guards = readiness.substring(first + begin.length(), last);
        String candidate = captured.workspace().candidateRoot();
        String unit = "windowstolinux-build-" + captured.workspace().candidateId() + ".service";
        return "set -uo pipefail\nexport LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin\nunset BASH_ENV ENV CDPATH\ncd -- / || exit 64\n"
            + guards + "\n" + "candidate='" + candidate + "'\nunit='" + unit + "'\n"
            + "emit_unknown() { printf '%s' '" + header(captured) + UNKNOWN
            + "'; printf 'UNKNOWN_STAGE=%s\\nUNKNOWN_REASON=%s\\n' \"$1\" \"$2\"; exit 0; }\n"
            + """
            for tool in /usr/bin/stat /usr/bin/readlink /usr/bin/find /usr/bin/id /usr/bin/systemctl /usr/bin/findmnt /usr/sbin/losetup; do
              trusted_executable "$tool"
              [ "$checked" = SAFE ] || emit_unknown TOOLS TOOL_UNCONFIRMED
            done
            [ "$(/usr/bin/id -u)" = 0 ] || emit_unknown USER USER_UNCONFIRMED
            managed_parents "$candidate"
            [ "$checked" = SAFE ] || emit_unknown PATH PARENTS_UNCONFIRMED
            confirm_absent "$candidate"
            [ "$checked" = MISSING ] || emit_unknown PATH PATH_ABSENCE_UNCONFIRMED
            # Even an inactive loaded unit is retained: this narrow branch expects no original build unit.
            unit_info=$(/usr/bin/systemctl show --no-pager --property=LoadState --property=ActiveState --property=ControlGroup -- "$unit" 2>/dev/null)
            unit_exit=$?
            [ "$unit_exit" = 0 ] || [ "$unit_exit" = 4 ] || emit_unknown BUILD_UNIT BUILD_QUERY_UNCONFIRMED
            unit_load= unit_active= unit_group= unit_fields=0
            while IFS= read -r line; do
              case "$line" in
                LoadState=not-found) [ -z "$unit_load" ] || emit_unknown BUILD_UNIT BUILD_STATE_UNCONFIRMED; unit_load=not-found ;;
                ActiveState=inactive) [ -z "$unit_active" ] || emit_unknown BUILD_UNIT BUILD_STATE_UNCONFIRMED; unit_active=inactive ;;
                ControlGroup=) [ -z "$unit_group" ] || emit_unknown BUILD_UNIT BUILD_STATE_UNCONFIRMED; unit_group=empty ;;
                *) emit_unknown BUILD_UNIT BUILD_STATE_UNCONFIRMED ;;
              esac
              unit_fields=$((unit_fields + 1))
            done <<< "$unit_info"
            [ "$unit_fields" = 3 ] && [ "$unit_load" = not-found ] && [ "$unit_active" = inactive ] && [ "$unit_group" = empty ] || emit_unknown BUILD_UNIT BUILD_STATE_UNCONFIRMED
            mounts=$(/usr/bin/findmnt --kernel --noheadings --raw --output TARGET) || emit_unknown MOUNTS MOUNT_QUERY_UNCONFIRMED
            [ "${#mounts}" -le 65536 ] || emit_unknown MOUNTS MOUNT_OUTPUT_LIMIT
            while IFS= read -r mount; do
              [[ "$mount" = /* ]] || emit_unknown MOUNTS MOUNT_TARGET_INVALID
              case "$mount" in "$candidate" | "$candidate"/*) emit_unknown MOUNTS CANDIDATE_MOUNT_PRESENT ;; esac
            done <<< "$mounts"
            loops=$(/usr/sbin/losetup --list --noheadings --raw --output BACK-FILE) || emit_unknown LOOPS LOOP_QUERY_UNCONFIRMED
            [ "${#loops}" -le 65536 ] || emit_unknown LOOPS LOOP_OUTPUT_LIMIT
            if [ -n "$loops" ]; then
              while IFS= read -r backing; do
                [[ "$backing" = /* ]] || emit_unknown LOOPS LOOP_BACKING_INVALID
                case "$backing" in "$candidate" | "$candidate"/*) emit_unknown LOOPS CANDIDATE_LOOP_PRESENT ;; esac
              done <<< "$loops"
            fi
            # Recheck the original literal path after all kernel metadata observations.
            confirm_absent "$candidate"
            [ "$checked" = MISSING ] || emit_unknown PATH_RECHECK PATH_RECHECK_UNCONFIRMED
            """ + "printf '%s' '" + absentOutput(captured) + "'\n";
    }
}
