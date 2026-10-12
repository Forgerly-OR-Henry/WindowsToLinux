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
        return guards + candidateDeclaration + "\n"
            + "if [ ! -e \"$candidate\" ] && [ ! -L \"$candidate\" ]; then\n" + absence + "exit 0\nfi\n"
            + """
            for tool in /usr/bin/stat /usr/bin/readlink /usr/bin/find /usr/bin/id /usr/bin/cat /usr/bin/sha256sum /usr/bin/findmnt /usr/sbin/losetup; do
              trusted_executable "$tool"
              [ "$checked" = SAFE ] || exit 64
            done
            [ "$(/usr/bin/id -u)" = 0 ] && [ "${SUDO_USER:-root}" = root ] || exit 64
            managed_parents "$candidate"
            [ "$checked" = SAFE ] || exit 64
            path_metadata "$candidate" directory
            [ "$checked" = SAFE ] || exit 64
            private_file() {
              path_metadata "$1" file
              [ "$checked" = SAFE ] && [ "$(/usr/bin/stat -c '%h' -- "$1")" = 1 ] || exit 64
            }
            for control in .deployer .agent-task .workspace-device .workspace-limit .purpose .workspace.ext4; do
              private_file "$candidate/$control"
            done
            [ "$(/usr/bin/cat -- "$candidate/.deployer")" = root ] && [ "$(/usr/bin/stat -c '%s' -- "$candidate/.deployer")" = 5 ] || exit 64
            [ "$(/usr/bin/cat -- "$candidate/.purpose")" = build ] && [ "$(/usr/bin/stat -c '%s' -- "$candidate/.purpose")" = 6 ] || exit 64
            children=$(/usr/bin/find "$candidate" -mindepth 1 -maxdepth 1 -printf '%f\n') || exit 64
            [ "${#children}" -le 4096 ] || exit 64
            while IFS= read -r child; do
              case "$child" in .deployer | .agent-task | .workspace-device | .workspace-limit | .purpose | .workspace.ext4 | mutable | .agent-mode) ;;
                *) exit 64 ;;
              esac
            done <<< "$children"
            if [ -e "$candidate/.agent-mode" ] || [ -L "$candidate/.agent-mode" ]; then
              private_file "$candidate/.agent-mode"
              [ "$(/usr/bin/cat -- "$candidate/.agent-mode")" = protected-source ] || exit 64
            fi
            image="$candidate/.workspace.ext4"
            device=$(/usr/bin/cat -- "$candidate/.workspace-device") || exit 64
            limit=$(/usr/bin/cat -- "$candidate/.workspace-limit") || exit 64
            [[ "$device" =~ ^/dev/loop[0-9]+$ ]] && [[ "$limit" =~ ^[0-9]{1,12}$ ]] || exit 64
            [ "$limit" -ge 67108864 ] && [ "$limit" -le 137438953472 ] && [ "$(/usr/bin/stat -c '%s' -- "$image")" = "$limit" ] || exit 64
            [ "$(/usr/sbin/losetup --noheadings --raw --output BACK-FILE "$device")" = "$image" ] || exit 64
            mounts=$(/usr/bin/findmnt --kernel --noheadings --raw --output TARGET,SOURCE,FSTYPE) || exit 64
            [ "${#mounts}" -le 65536 ] || exit 64
            count=0
            while IFS=' ' read -r target source filesystem extra; do
              [[ "$target" = /* ]] && [ -z "$extra" ] || exit 64
              case "$target" in "$candidate" | "$candidate"/*)
                [ "$target" = "$candidate/mutable" ] && [ "$source" = "$device" ] && [ "$filesystem" = ext4 ] || exit 64
                count=$((count + 1)) ;;
                *) case "$source" in "$device" | "$device"\\[*\\]) exit 64 ;; esac ;;
              esac
            done <<< "$mounts"
            [ "$count" = 1 ] || exit 64
            loops=$(/usr/sbin/losetup --list --noheadings --raw --output NAME,BACK-FILE) || exit 64
            [ "${#loops}" -le 65536 ] || exit 64
            count=0
            while IFS=' ' read -r loop backing extra; do
              [[ "$loop" =~ ^/dev/loop[0-9]+$ ]] && [[ "$backing" = /* ]] && [ -z "$extra" ] || exit 64
              case "$backing" in "$candidate" | "$candidate"/*)
                [ "$loop" = "$device" ] && [ "$backing" = "$image" ] || exit 64
                count=$((count + 1)) ;;
              esac
              [ "$loop" != "$device" ] || [ "$backing" = "$image" ] || exit 64
            done <<< "$loops"
            [ "$count" = 1 ] || exit 64
            path_metadata "$candidate/mutable" directory
            [ "$checked" = SAFE ] || exit 64
            children=$(/usr/bin/find "$candidate/mutable" -mindepth 1 -maxdepth 1 -printf '%f\n') || exit 64
            [ "${#children}" -le 4096 ] || exit 64
            while IFS= read -r child; do
              case "$child" in source.tar.gz | input-source | source) ;; *) exit 64 ;; esac
            done <<< "$children"
            private_file "$candidate/mutable/source.tar.gz"
            """
            + "[ \"$(/usr/bin/cat -- \"$candidate/.agent-task\")\" = '" + captured.task() + "' ] && [ \"$(/usr/bin/stat -c '%s' -- \"$candidate/.agent-task\")\" = 37 ] || exit 64\n"
            + "archive=$(/usr/bin/sha256sum -- \"$candidate/mutable/source.tar.gz\") || exit 64\n"
            + "[ \"${archive%% *}\" = '" + captured.workspace().archiveSha256() + "' ] || exit 64\n"
            + "status=$(\n" + DeploymentManagedHelperBundle.verifiedCommand(query, captured.layout()) + "\n) || exit 64\n"
            + "[ \"$status\" = 'CANDIDATE_STATE=owned\nBUILD_ACTIVE=no' ] || exit 64\n"
            + "printf '%s' '" + presentOutput(captured) + "'\n";
    }
}
