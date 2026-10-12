package gold.debug.windowstolinux.mod.deployment.service.agent.recovery;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationAbsence;
import gold.debug.windowstolinux.mod.deployment.service.agent.recovery.evidence.DeploymentAgentPreparationCompensation;
import gold.debug.windowstolinux.mod.deployment.service.agent.tools.DeploymentAgentHelperInstallerScript;

/** Read-only admission for one completely uploaded prepare gap; incomplete uploads remain UNKNOWN. */
public final class DeploymentAgentPartialPreparationProbe {
    public static final String OPERATION = "deployment.agent.preparation.partial-observation.v1";

    public enum Stage {EXECUTABLES, OWNERSHIP, FILES, WORKSPACE, MOUNTS, LOOPS, ARCHIVE, HELPER}

    public enum Reason {
        UNTRUSTED_EXECUTABLE(Stage.EXECUTABLES), NON_EXCLUSIVE_ROOT(Stage.OWNERSHIP), UNSAFE_PARENTS(Stage.OWNERSHIP),
        UNSAFE_CANDIDATE(Stage.OWNERSHIP), NON_PRIVATE_FILE(Stage.FILES), DEPLOYER_MISMATCH(Stage.OWNERSHIP),
        PURPOSE_MISMATCH(Stage.WORKSPACE), ROOT_LIST_FAILED(Stage.WORKSPACE), ROOT_LIST_LIMIT(Stage.WORKSPACE),
        UNEXPLAINED_ROOT_ENTRY(Stage.WORKSPACE), MODE_MISMATCH(Stage.WORKSPACE), DEVICE_READ_FAILED(Stage.WORKSPACE),
        LIMIT_READ_FAILED(Stage.WORKSPACE), DEVICE_OR_LIMIT_INVALID(Stage.WORKSPACE), IMAGE_SIZE_MISMATCH(Stage.WORKSPACE),
        BACKING_FILE_MISMATCH(Stage.LOOPS), MOUNT_QUERY_FAILED(Stage.MOUNTS), MOUNT_QUERY_LIMIT(Stage.MOUNTS),
        MOUNT_ROW_INVALID(Stage.MOUNTS), UNEXPLAINED_CANDIDATE_MOUNT(Stage.MOUNTS), SHARED_DEVICE_MOUNT(Stage.MOUNTS),
        MOUNT_COUNT_MISMATCH(Stage.MOUNTS), LOOP_QUERY_FAILED(Stage.LOOPS), LOOP_QUERY_LIMIT(Stage.LOOPS),
        LOOP_ROW_INVALID(Stage.LOOPS), UNEXPLAINED_CANDIDATE_LOOP(Stage.LOOPS), DEVICE_BACKING_MISMATCH(Stage.LOOPS),
        LOOP_COUNT_MISMATCH(Stage.LOOPS), UNSAFE_MUTABLE(Stage.WORKSPACE), MUTABLE_LIST_FAILED(Stage.WORKSPACE),
        MUTABLE_LIST_LIMIT(Stage.WORKSPACE), UNEXPLAINED_MUTABLE_ENTRY(Stage.WORKSPACE), TASK_MISMATCH(Stage.OWNERSHIP),
        STANDARD_DIRECTORY_UNSAFE(Stage.WORKSPACE), STANDARD_DIRECTORY_METADATA_UNCONFIRMED(Stage.WORKSPACE),
        STANDARD_DIRECTORY_VOLUME_MISMATCH(Stage.WORKSPACE), STANDARD_DIRECTORY_SCAN_FAILED(Stage.WORKSPACE),
        STANDARD_DIRECTORY_NONEMPTY(Stage.WORKSPACE), STANDARD_DIRECTORY_CHANGED(Stage.WORKSPACE),
        ARCHIVE_READ_FAILED(Stage.ARCHIVE), ARCHIVE_SHA_MISMATCH(Stage.ARCHIVE), QUERY_FAILED(Stage.HELPER),
        NOT_OWNED_OR_BUILD_ACTIVE(Stage.HELPER);

        private final Stage stage;

        Reason(Stage stage) {
            this.stage = stage;
        }
    }

    public record Rejection(Stage stage, Reason reason) {
        public Rejection {
            if (stage == null || reason == null || stage != reason.stage)
                throw new IllegalArgumentException("Partial observation stage and reason differ");
        }
    }

    private DeploymentAgentPartialPreparationProbe() {
    }

    public static CommandRequest request(DeploymentAgentPreparationCompensation.Captured captured, boolean after) {
        byte[] input = script(captured, after).getBytes(StandardCharsets.UTF_8);
        try {
            return new CommandRequest(DeploymentAgentHelperInstallerScript.LAUNCHER, input, Duration.ofSeconds(30), 4096);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    public static String requestDigest(DeploymentAgentPreparationCompensation.Captured captured, boolean after) {
        try (var request = request(captured, after)) {
            return DeploymentAgentPreparationAbsence.hash(RemoteActions.command(new ServerId(captured.snapshot().identity().server()),
                captured.snapshot().identity().configurationDigest(), request).exactAction());
        }
    }

    public static String absentOutput(DeploymentAgentPreparationCompensation.Captured captured) {
        return DeploymentAgentPreparationProbe.absentOutput(captured.absenceBinding());
    }

    public static String presentOutput(DeploymentAgentPreparationCompensation.Captured captured) {
        return "FORMAT=WTL_AGENT_UPLOADED_PREPARATION_1\nTASK=" + captured.task() + "\nPREPARE_EVENT=" + captured.preparation().id()
            + "\nCANDIDATE=" + captured.workspace().candidateId() + "\nLAYOUT_SHA256=" + captured.layout().digest()
            + "\nEVENTS_SHA256=" + DeploymentAgentPreparationAbsence.digest(captured.events())
            + "\nARCHIVE_SHA256=" + captured.workspace().archiveSha256() + "\nSTATUS=OWNED_UPLOADED_INACTIVE\n";
    }

    public static boolean absent(DeploymentAgentPreparationCompensation.Captured captured, CommandResult result) {
        return DeploymentAgentPreparationProbe.absent(captured.absenceBinding(), result);
    }

    public static boolean present(DeploymentAgentPreparationCompensation.Captured captured, CommandResult result) {
        return result != null && !result.truncated() && result.exitCode().equals(Optional.of(0)) && result.stderr().isEmpty()
            && presentOutput(captured).equals(result.stdout());
    }

    /** Static rejection metadata is diagnostic only; it can never authorize compensation. */
    public static Optional<Rejection> rejection(CommandResult result) {
        if (result == null || result.truncated() || !result.exitCode().equals(Optional.of(64)) || !result.stdout().isEmpty()
            || result.stderr().length() > 160) return Optional.empty();
        String[] fields = result.stderr().split("\n", -1);
        if (fields.length != 4 || !fields[0].equals("FORMAT=WTL_AGENT_PARTIAL_REJECTION_1") || !fields[1].startsWith("STAGE=")
            || !fields[2].startsWith("REASON=") || !fields[3].isEmpty()) return Optional.empty();
        try {
            var stage = Stage.valueOf(fields[1].substring("STAGE=".length()));
            var reason = Reason.valueOf(fields[2].substring("REASON=".length()));
            if (stage != reason.stage) return Optional.empty();
            return Optional.of(new Rejection(stage, reason));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public static String script(DeploymentAgentPreparationCompensation.Captured captured, boolean after) {
        String absence = DeploymentAgentPreparationProbe.script(captured.absenceBinding());
        if (after) return absence;
        String candidateDeclaration = "candidate='" + captured.workspace().candidateRoot() + "'";
        int guardEnd = absence.indexOf(candidateDeclaration);
        if (guardEnd < 1 || absence.indexOf(candidateDeclaration, guardEnd + 1) >= 0)
            throw new IllegalStateException("Fixed absence guards are unavailable");
        String guards = absence.substring(0, guardEnd);
        String query = "'" + captured.layout().helperPath() + "' 'candidate-query-task' '" + captured.workspace().applicationId()
            + "' '" + captured.workspace().candidateId() + "' '" + captured.task() + "'";
        return guards + "reject() { printf 'FORMAT=WTL_AGENT_PARTIAL_REJECTION_1\\nSTAGE=%s\\nREASON=%s\\n' \"$1\" \"$2\" >&2 || :; exit 64; }\n" + candidateDeclaration + "\n"
            + "if [ ! -e \"$candidate\" ] && [ ! -L \"$candidate\" ]; then\n" + absence + "exit 0\nfi\n"
            + """
            for tool in /usr/bin/stat /usr/bin/readlink /usr/bin/find /usr/bin/id /usr/bin/cat /usr/bin/sha256sum /usr/bin/findmnt /usr/sbin/losetup; do
              trusted_executable "$tool"
              [ "$checked" = SAFE ] || reject EXECUTABLES UNTRUSTED_EXECUTABLE
            done
            [ "$(/usr/bin/id -u)" = 0 ] && [ "${SUDO_USER:-root}" = root ] || reject OWNERSHIP NON_EXCLUSIVE_ROOT
            managed_parents "$candidate"
            [ "$checked" = SAFE ] || reject OWNERSHIP UNSAFE_PARENTS
            path_metadata "$candidate" directory
            [ "$checked" = SAFE ] || reject OWNERSHIP UNSAFE_CANDIDATE
            private_file() {
              path_metadata "$1" file
              [ "$checked" = SAFE ] && [ "$(/usr/bin/stat -c '%h' -- "$1")" = 1 ] || reject FILES NON_PRIVATE_FILE
            }
            for control in .deployer .agent-task .workspace-device .workspace-limit .purpose .workspace.ext4; do
              private_file "$candidate/$control"
            done
            [ "$(/usr/bin/cat -- "$candidate/.deployer")" = root ] && [ "$(/usr/bin/stat -c '%s' -- "$candidate/.deployer")" = 5 ] || reject OWNERSHIP DEPLOYER_MISMATCH
            [ "$(/usr/bin/cat -- "$candidate/.purpose")" = build ] && [ "$(/usr/bin/stat -c '%s' -- "$candidate/.purpose")" = 6 ] || reject WORKSPACE PURPOSE_MISMATCH
            children=$(/usr/bin/find "$candidate" -mindepth 1 -maxdepth 1 -printf '%f\n') || reject WORKSPACE ROOT_LIST_FAILED
            [ "${#children}" -le 4096 ] || reject WORKSPACE ROOT_LIST_LIMIT
            while IFS= read -r child; do
              case "$child" in .deployer | .agent-task | .workspace-device | .workspace-limit | .purpose | .workspace.ext4 | mutable | .agent-mode) ;;
                *) reject WORKSPACE UNEXPLAINED_ROOT_ENTRY ;;
              esac
            done <<< "$children"
            if [ -e "$candidate/.agent-mode" ] || [ -L "$candidate/.agent-mode" ]; then
              private_file "$candidate/.agent-mode"
              [ "$(/usr/bin/cat -- "$candidate/.agent-mode")" = protected-source ] || reject WORKSPACE MODE_MISMATCH
            fi
            image="$candidate/.workspace.ext4"
            device=$(/usr/bin/cat -- "$candidate/.workspace-device") || reject WORKSPACE DEVICE_READ_FAILED
            limit=$(/usr/bin/cat -- "$candidate/.workspace-limit") || reject WORKSPACE LIMIT_READ_FAILED
            [[ "$device" =~ ^/dev/loop[0-9]+$ ]] && [[ "$limit" =~ ^[0-9]{1,12}$ ]] || reject WORKSPACE DEVICE_OR_LIMIT_INVALID
            [ "$limit" -ge 67108864 ] && [ "$limit" -le 137438953472 ] && [ "$(/usr/bin/stat -c '%s' -- "$image")" = "$limit" ] || reject WORKSPACE IMAGE_SIZE_MISMATCH
            [ "$(/usr/sbin/losetup --noheadings --raw --output BACK-FILE "$device")" = "$image" ] || reject LOOPS BACKING_FILE_MISMATCH
            mounts=$(/usr/bin/findmnt --kernel --noheadings --raw --output TARGET,SOURCE,FSTYPE) || reject MOUNTS MOUNT_QUERY_FAILED
            [ "${#mounts}" -le 65536 ] || reject MOUNTS MOUNT_QUERY_LIMIT
            count=0
            while IFS=' ' read -r target source filesystem extra; do
              [[ "$target" = /* ]] && [ -z "$extra" ] || reject MOUNTS MOUNT_ROW_INVALID
              case "$target" in "$candidate" | "$candidate"/*)
                [ "$target" = "$candidate/mutable" ] && [ "$source" = "$device" ] && [ "$filesystem" = ext4 ] || reject MOUNTS UNEXPLAINED_CANDIDATE_MOUNT
                count=$((count + 1)) ;;
                *) case "$source" in "$device" | "$device"\\[*\\]) reject MOUNTS SHARED_DEVICE_MOUNT ;; esac ;;
              esac
            done <<< "$mounts"
            [ "$count" = 1 ] || reject MOUNTS MOUNT_COUNT_MISMATCH
            loops=$(/usr/sbin/losetup --list --noheadings --raw --output NAME,BACK-FILE) || reject LOOPS LOOP_QUERY_FAILED
            [ "${#loops}" -le 65536 ] || reject LOOPS LOOP_QUERY_LIMIT
            count=0
            while IFS=' ' read -r loop backing extra; do
              [[ "$loop" =~ ^/dev/loop[0-9]+$ ]] && [[ "$backing" = /* ]] && [ -z "$extra" ] || reject LOOPS LOOP_ROW_INVALID
              case "$backing" in "$candidate" | "$candidate"/*)
                [ "$loop" = "$device" ] && [ "$backing" = "$image" ] || reject LOOPS UNEXPLAINED_CANDIDATE_LOOP
                count=$((count + 1)) ;;
              esac
              [ "$loop" != "$device" ] || [ "$backing" = "$image" ] || reject LOOPS DEVICE_BACKING_MISMATCH
            done <<< "$loops"
            [ "$count" = 1 ] || reject LOOPS LOOP_COUNT_MISMATCH
            path_metadata "$candidate/mutable" directory
            [ "$checked" = SAFE ] || reject WORKSPACE UNSAFE_MUTABLE
            children=$(/usr/bin/find "$candidate/mutable" -mindepth 1 -maxdepth 1 -printf '%f\n') || reject WORKSPACE MUTABLE_LIST_FAILED
            [ "${#children}" -le 4096 ] || reject WORKSPACE MUTABLE_LIST_LIMIT
            while IFS= read -r child; do
              case "$child" in
                source.tar.gz | input-source | source) ;;
                lost+found)
                  directory="$candidate/mutable/lost+found"
                  path_metadata "$directory" directory
                  [ "$checked" = SAFE ] && [ "$mode" = 700 ] || reject WORKSPACE STANDARD_DIRECTORY_UNSAFE
                  directory_stamp=$(/usr/bin/stat -c '%d:%i:%F:%u:%g:%a:%h:%s:%y:%z' -- "$directory") || reject WORKSPACE STANDARD_DIRECTORY_METADATA_UNCONFIRMED
                  [ "${#directory_stamp}" -le 256 ] && [[ "$directory_stamp" != *$'\\n'* ]] &&
                    [[ "$directory_stamp" =~ ^[0-9]{1,20}:[0-9]{1,20}:directory:0:0:700:2:[0-9]{1,20}: ]] || reject WORKSPACE STANDARD_DIRECTORY_METADATA_UNCONFIRMED
                  mutable_device=$(/usr/bin/stat -c '%d' -- "$candidate/mutable") || reject WORKSPACE STANDARD_DIRECTORY_METADATA_UNCONFIRMED
                  [[ "$mutable_device" =~ ^[0-9]{1,20}$ ]] && [ "${directory_stamp%%:*}" = "$mutable_device" ] || reject WORKSPACE STANDARD_DIRECTORY_VOLUME_MISMATCH
                  directory_entries=$(/usr/bin/find -P "$directory" -mindepth 1 -maxdepth 1 -printf 'ENTRY\\n' -quit) || reject WORKSPACE STANDARD_DIRECTORY_SCAN_FAILED
                  [ -z "$directory_entries" ] || reject WORKSPACE STANDARD_DIRECTORY_NONEMPTY
                  current_directory_stamp=$(/usr/bin/stat -c '%d:%i:%F:%u:%g:%a:%h:%s:%y:%z' -- "$directory") || reject WORKSPACE STANDARD_DIRECTORY_METADATA_UNCONFIRMED
                  [ "$current_directory_stamp" = "$directory_stamp" ] || reject WORKSPACE STANDARD_DIRECTORY_CHANGED ;;
                *) reject WORKSPACE UNEXPLAINED_MUTABLE_ENTRY ;;
              esac
            done <<< "$children"
            private_file "$candidate/mutable/source.tar.gz"
            """
            + "[ \"$(/usr/bin/cat -- \"$candidate/.agent-task\")\" = '" + captured.task() + "' ] && [ \"$(/usr/bin/stat -c '%s' -- \"$candidate/.agent-task\")\" = 37 ] || reject OWNERSHIP TASK_MISMATCH\n"
            + "archive=$(/usr/bin/sha256sum -- \"$candidate/mutable/source.tar.gz\") || reject ARCHIVE ARCHIVE_READ_FAILED\n"
            + "[ \"${archive%% *}\" = '" + captured.workspace().archiveSha256() + "' ] || reject ARCHIVE ARCHIVE_SHA_MISMATCH\n"
            + "status=$(\n" + DeploymentManagedHelperBundle.verifiedCommand(query, captured.layout()) + "\n) || reject HELPER QUERY_FAILED\n"
            + "[ \"$status\" = 'CANDIDATE_STATE=owned\nBUILD_ACTIVE=no' ] || reject HELPER NOT_OWNED_OR_BUILD_ACTIVE\n"
            + "printf '%s' '" + presentOutput(captured) + "'\n";
    }
}
