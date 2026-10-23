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

    public enum FileRole {DEPLOYER, AGENT_TASK, WORKSPACE_DEVICE, WORKSPACE_LIMIT, PURPOSE, WORKSPACE_IMAGE, AGENT_MODE, SOURCE_ARCHIVE}

    public enum MetadataStatus {OBSERVED, UNOBSERVED, UNCONFIRMED}

    public enum FileKind {REGULAR, DIRECTORY, SYMLINK, OTHER, UNCONFIRMED}

    public enum RootOwner {ROOT, NON_ROOT, UNCONFIRMED}

    public enum GroupOtherWrite {NO, YES, UNCONFIRMED}

    public enum FileLinks {SINGLE, MULTIPLE, UNCONFIRMED}

    /** New-time metadata only; neither ownership admission nor a compensation receipt. */
    public record FileMetadata(FileRole role, MetadataStatus status, FileKind kind, RootOwner owner,
                               GroupOtherWrite groupOtherWrite, FileLinks links) {
        public FileMetadata {
            java.util.Objects.requireNonNull(role);
            java.util.Objects.requireNonNull(status);
            java.util.Objects.requireNonNull(kind);
            java.util.Objects.requireNonNull(owner);
            java.util.Objects.requireNonNull(groupOtherWrite);
            java.util.Objects.requireNonNull(links);
            if (status == MetadataStatus.OBSERVED
                ? kind == FileKind.UNCONFIRMED || owner == RootOwner.UNCONFIRMED || groupOtherWrite == GroupOtherWrite.UNCONFIRMED || links == FileLinks.UNCONFIRMED
                : kind != FileKind.UNCONFIRMED || owner != RootOwner.UNCONFIRMED || groupOtherWrite != GroupOtherWrite.UNCONFIRMED || links != FileLinks.UNCONFIRMED)
                throw new IllegalArgumentException("Invalid private-file metadata classification");
        }

        private String line() {
            return "ROLE=" + role + ";STATUS=" + status + ";KIND=" + kind + ";OWNER=" + owner + ";GROUP_OTHER_WRITE=" + groupOtherWrite + ";LINKS=" + links;
        }

        private boolean accepted() {
            return status == MetadataStatus.OBSERVED && kind == FileKind.REGULAR && owner == RootOwner.ROOT
                && groupOtherWrite == GroupOtherWrite.NO && links == FileLinks.SINGLE;
        }
    }

    public static CommandRequest fileMetadataRequest(DeploymentAgentPreparationCompensation.Captured captured) {
        byte[] input = fileMetadataScript(captured).getBytes(StandardCharsets.UTF_8);
        try {
            return new CommandRequest(DeploymentAgentHelperInstallerScript.LAUNCHER, input, Duration.ofSeconds(30), 4096);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    public static String fileMetadataRequestDigest(DeploymentAgentPreparationCompensation.Captured captured) {
        try (var request = fileMetadataRequest(captured)) {
            return DeploymentAgentPreparationAbsence.hash(RemoteActions.command(new ServerId(captured.snapshot().identity().server()),
                captured.snapshot().identity().configurationDigest(), request).exactAction());
        }
    }

    /** Keeps the original probe and its persisted request digests byte-for-byte unchanged. */
    public static String fileMetadataScript(DeploymentAgentPreparationCompensation.Captured captured) {
        String original = DeploymentAgentPreparationProbe.script(captured.absenceBinding());
        String declaration = "candidate='" + captured.workspace().candidateRoot() + "'";
        int end = original.indexOf(declaration);
        if (end < 1 || original.indexOf(declaration, end + 1) >= 0)
            throw new IllegalStateException("Fixed metadata guards are unavailable");
        return original.substring(0, end) + declaration + "\n" + """
            # Companion observation only: no original preparation or cleanup admission.
            exec 2>/dev/null
            parent_ok=1
            for tool in /usr/bin/stat /usr/bin/readlink /usr/bin/id; do
              trusted_executable "$tool"
              [ "$checked" = SAFE ] || parent_ok=0
            done
            if [ "$parent_ok" = 1 ]; then
              [ "$(/usr/bin/id -u)" = 0 ] && [ "${SUDO_USER:-root}" = root ] || parent_ok=0
              managed_parents "$candidate"
              [ "$checked" = SAFE ] || parent_ok=0
              path_metadata "$candidate" directory
              [ "$checked" = SAFE ] || parent_ok=0
            fi
            mutable_ok=0
            if [ "$parent_ok" = 1 ]; then
              path_metadata "$candidate/mutable" directory
              [ "$checked" != SAFE ] || mutable_ok=1
            fi
            printf 'FORMAT=WTL_AGENT_PRIVATE_FILE_METADATA_1\\n'
            metadata_exit=0
            metadata_file() {
              local role="$1" file="$2" allowed="$3" record= status=UNCONFIRMED kind=UNCONFIRMED owner=UNCONFIRMED group_other_write=UNCONFIRMED links=UNCONFIRMED
              local raw_kind= raw_uid= raw_gid= raw_mode= raw_links=
              if [ "$allowed" = 1 ]; then
                if record=$(LC_ALL=C /usr/bin/stat -c '%F:%u:%g:%a:%h' -- "$file"); then
                  if [ "${#record}" -le 256 ] && [[ "$record" != *$'\\n'* ]] && [[ "$record" != *$'\\r'* ]] &&
                    [[ "$record" =~ ^[^:]+:(0|[1-9][0-9]{0,19}):(0|[1-9][0-9]{0,19}):[0-7]{3,4}:[1-9][0-9]{0,19}$ ]]; then
                    IFS=: read -r raw_kind raw_uid raw_gid raw_mode raw_links <<< "$record"
                    status=OBSERVED
                    case "$raw_kind" in 'regular file' | 'regular empty file') kind=REGULAR ;; directory) kind=DIRECTORY ;; 'symbolic link') kind=SYMLINK ;; *) kind=OTHER ;; esac
                    owner=NON_ROOT; [ "$raw_uid:$raw_gid" != 0:0 ] || owner=ROOT
                    group_other_write=YES; (( (8#$raw_mode & 022) != 0 )) || group_other_write=NO
                    links=MULTIPLE; [ "$raw_links" != 1 ] || links=SINGLE
                  fi
                else
                  status=UNOBSERVED
                fi
              fi
              if [ "$status:$kind:$owner:$group_other_write:$links" != OBSERVED:REGULAR:ROOT:NO:SINGLE ]; then metadata_exit=64; fi
              printf 'ROLE=%s;STATUS=%s;KIND=%s;OWNER=%s;GROUP_OTHER_WRITE=%s;LINKS=%s\\n' "$role" "$status" "$kind" "$owner" "$group_other_write" "$links"
            }
            metadata_file DEPLOYER "$candidate/.deployer" "$parent_ok"
            metadata_file AGENT_TASK "$candidate/.agent-task" "$parent_ok"
            metadata_file WORKSPACE_DEVICE "$candidate/.workspace-device" "$parent_ok"
            metadata_file WORKSPACE_LIMIT "$candidate/.workspace-limit" "$parent_ok"
            metadata_file PURPOSE "$candidate/.purpose" "$parent_ok"
            metadata_file WORKSPACE_IMAGE "$candidate/.workspace.ext4" "$parent_ok"
            metadata_file AGENT_MODE "$candidate/.agent-mode" "$parent_ok"
            metadata_file SOURCE_ARCHIVE "$candidate/mutable/source.tar.gz" "$mutable_ok"
            exit "$metadata_exit"
            """;
    }

    /** Strict finite protocol; raw values and parser exceptions never leave this method. */
    public static Optional<java.util.List<FileMetadata>> fileMetadata(CommandResult result) {
        if (result == null || result.truncated() || result.exitCode().isEmpty() || !result.stderr().isEmpty()
            || result.stdout().length() > 4096 || !java.util.Set.of(0, 64).contains(result.exitCode().orElseThrow()))
            return Optional.empty();
        String[] lines = result.stdout().split("\n", -1);
        if (lines.length != FileRole.values().length + 2 || !lines[0].equals("FORMAT=WTL_AGENT_PRIVATE_FILE_METADATA_1")
            || !lines[lines.length - 1].isEmpty()) return Optional.empty();
        try {
            var rows = new java.util.ArrayList<FileMetadata>();
            for (var role : FileRole.values()) {
                String[] fields = lines[role.ordinal() + 1].split(";", -1);
                if (fields.length != 6 || !fields[0].equals("ROLE=" + role)
                    || !fields[1].startsWith("STATUS=") || !fields[2].startsWith("KIND=") || !fields[3].startsWith("OWNER=")
                    || !fields[4].startsWith("GROUP_OTHER_WRITE=") || !fields[5].startsWith("LINKS="))
                    return Optional.empty();
                var row = new FileMetadata(role, MetadataStatus.valueOf(fields[1].substring(7)), FileKind.valueOf(fields[2].substring(5)),
                    RootOwner.valueOf(fields[3].substring(6)), GroupOtherWrite.valueOf(fields[4].substring(18)), FileLinks.valueOf(fields[5].substring(6)));
                if (!row.line().equals(lines[role.ordinal() + 1])) return Optional.empty();
                rows.add(row);
            }
            if (result.exitCode().orElseThrow() != (rows.stream().allMatch(FileMetadata::accepted) ? 0 : 64))
                return Optional.empty();
            return Optional.of(java.util.List.copyOf(rows));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public enum PreparationStage {UPLOADED, CREATED_WITHOUT_ARCHIVE, ABSENT}

    public static String stageOutput(DeploymentAgentPreparationCompensation.Captured captured, PreparationStage stage) {
        String status = switch (stage) {
            case UPLOADED -> "OWNED_UPLOADED_INACTIVE";
            case CREATED_WITHOUT_ARCHIVE -> "OWNED_CREATED_WITHOUT_ARCHIVE_INACTIVE";
            case ABSENT -> "ABSENT";
        };
        return "FORMAT=WTL_AGENT_PREPARATION_STAGE_1\nTASK=" + captured.task() + "\nPREPARE_EVENT=" + captured.preparation().id()
            + "\nCANDIDATE=" + captured.workspace().candidateId() + "\nLAYOUT_SHA256=" + captured.layout().digest()
            + "\nEVENTS_SHA256=" + DeploymentAgentPreparationAbsence.digest(captured.events())
            + "\nEXPECTED_ARCHIVE_SHA256=" + captured.workspace().archiveSha256() + "\nSTATUS=" + status + "\n";
    }

    public static Optional<PreparationStage> stage(DeploymentAgentPreparationCompensation.Captured captured, CommandResult result) {
        if (result == null || result.truncated() || !result.exitCode().equals(Optional.of(0)) || !result.stderr().isEmpty())
            return Optional.empty();
        return Arrays.stream(PreparationStage.values()).filter(value -> stageOutput(captured, value).equals(result.stdout())).findFirst();
    }

    public static CommandRequest stageRequest(DeploymentAgentPreparationCompensation.Captured captured, long workspaceBytes) {
        byte[] input = stageScript(captured, workspaceBytes).getBytes(StandardCharsets.UTF_8);
        try {
            return new CommandRequest(DeploymentAgentHelperInstallerScript.LAUNCHER, input, Duration.ofSeconds(30), 4096);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    public static String stageRequestDigest(DeploymentAgentPreparationCompensation.Captured captured, long workspaceBytes) {
        try (var request = stageRequest(captured, workspaceBytes)) {
            return DeploymentAgentPreparationAbsence.hash(RemoteActions.command(new ServerId(captured.snapshot().identity().server()),
                captured.snapshot().identity().configurationDigest(), request).exactAction());
        }
    }

    /** One complete new observation; legacy probe bytes and all legacy receipts remain unchanged. */
    public static String stageScript(DeploymentAgentPreparationCompensation.Captured captured, long workspaceBytes) {
        if (workspaceBytes < 67108864L || workspaceBytes > 137438953472L)
            throw new IllegalArgumentException("Original workspace quota is outside helper bounds");
        String original = script(captured, false);
        String typed = replaceOne(original, "printf '%s' '" + absentOutput(captured) + "'\n",
            "printf '%s' '" + stageOutput(captured, PreparationStage.ABSENT) + "'\n");
        typed = replaceOne(typed, "/usr/bin/sha256sum /usr/bin/findmnt", "/usr/bin/sha256sum /usr/bin/od /usr/bin/findmnt");
        String inventory = """
            # Hex records preserve filename boundaries, including embedded newlines; no raw names are emitted.
            exact_entries() {
              local directory="$1" kind="$2" encoded byte name=
              encoded=$(/usr/bin/find -P "$directory" -mindepth 1 -maxdepth 1 -printf '%f\\0' | /usr/bin/od -An -v -tx1) || reject WORKSPACE MUTABLE_LIST_FAILED
              [ "${#encoded}" -le 32768 ] || reject WORKSPACE MUTABLE_LIST_LIMIT
              for byte in $encoded; do
                [[ "$byte" =~ ^[0-9a-f]{2}$ ]] || reject WORKSPACE UNEXPLAINED_MUTABLE_ENTRY
                if [ "$byte" = 00 ]; then
                  case "$kind:$name" in
            """;
        for (String name : java.util.List.of(".deployer", ".agent-task", ".workspace-device", ".workspace-limit", ".purpose", ".workspace.ext4", "mutable"))
            inventory += "ROOT:" + java.util.HexFormat.of().formatHex(name.getBytes(StandardCharsets.UTF_8)) + ") ;;\n";
        inventory += "MUTABLE:" + java.util.HexFormat.of().formatHex("lost+found".getBytes(StandardCharsets.UTF_8)) + ") ;;\n" + """
                    *) reject WORKSPACE UNEXPLAINED_MUTABLE_ENTRY ;;
                  esac
                  name=
                else name="$name$byte"; [ "${#name}" -le 510 ] || reject WORKSPACE UNEXPLAINED_MUTABLE_ENTRY
                fi
              done
              [ -z "$name" ] || reject WORKSPACE UNEXPLAINED_MUTABLE_ENTRY
            }
            control_stamps() {
              local control
              for control in .deployer .agent-task .workspace-device .workspace-limit .purpose .workspace.ext4; do
                /usr/bin/stat -c '%d:%i:%f:%u:%g:%a:%h:%s:%y:%z' -- "$candidate/$control" || return 1
              done
            }
            """;
        String initial = """
            path_metadata "$candidate/mutable" directory
            # Stamp the same controls before their content checks.
            [ "$checked" = SAFE ] || reject WORKSPACE UNSAFE_MUTABLE
            candidate_stamp=$(/usr/bin/stat -c '%d:%i:%f:%u:%g:%a:%h:%s:%y:%z' -- "$candidate") || reject WORKSPACE STANDARD_DIRECTORY_METADATA_UNCONFIRMED
            mutable_stamp=$(/usr/bin/stat -c '%d:%i:%f:%u:%g:%a:%h:%s:%y:%z' -- "$candidate/mutable") || reject WORKSPACE STANDARD_DIRECTORY_METADATA_UNCONFIRMED
            original_controls=$(control_stamps) || reject FILES NON_PRIVATE_FILE
            [ "${#candidate_stamp}" -le 256 ] && [ "${#mutable_stamp}" -le 256 ] && [ "${#original_controls}" -le 2048 ] || reject WORKSPACE STANDARD_DIRECTORY_METADATA_UNCONFIRMED
            """;
        typed = replaceOne(typed, "private_file() {\n", inventory + initial + "private_file() {\n");
        String createdAdmission = """
            preparation_form=UPLOADED
            confirm_absent "$candidate/mutable/source.tar.gz"
            if [ "$checked" = MISSING ]; then
              preparation_form=CREATED_WITHOUT_ARCHIVE
              exact_entries "$candidate" ROOT
              exact_entries "$candidate/mutable" MUTABLE
              confirm_absent "$candidate/.agent-mode"
              [ "$checked" = MISSING ] || reject WORKSPACE MODE_MISMATCH
            """
            + "  [[ \"$device\" =~ ^/dev/loop(0|[1-9][0-9]*)$ ]] && [ \"$limit\" = '" + workspaceBytes + "' ] || reject WORKSPACE DEVICE_OR_LIMIT_INVALID\n"
            + "  [ \"$(/usr/bin/stat -c '%s' -- \"$candidate/.workspace-device\")\" = \"$((${#device} + 1))\" ] && [ \"$(/usr/bin/stat -c '%s' -- \"$candidate/.workspace-limit\")\" = \"$((${#limit} + 1))\" ] || reject WORKSPACE DEVICE_OR_LIMIT_INVALID\nfi\n";
        String mutableGuard = "path_metadata \"$candidate/mutable\" directory\n[ \"$checked\" = SAFE ] || reject WORKSPACE UNSAFE_MUTABLE\n";
        typed = replaceOne(typed, mutableGuard, mutableGuard + createdAdmission);
        typed = replaceOne(typed, "while IFS= read -r child; do\n  case \"$child\" in\n    source.tar.gz | input-source | source) ;;\n",
            "if [ -n \"$children\" ]; then\nwhile IFS= read -r child; do\n  case \"$child\" in\n    source.tar.gz | input-source | source) [ \"$preparation_form\" = UPLOADED ] || reject WORKSPACE UNEXPLAINED_MUTABLE_ENTRY ;;\n");
        typed = replaceOne(typed, "done <<< \"$children\"\nprivate_file \"$candidate/mutable/source.tar.gz\"\n",
            "done <<< \"$children\"\nfi\nif [ \"$preparation_form\" = UPLOADED ]; then private_file \"$candidate/mutable/source.tar.gz\"; fi\n");
        String archive = "archive=$(/usr/bin/sha256sum -- \"$candidate/mutable/source.tar.gz\") || reject ARCHIVE ARCHIVE_READ_FAILED\n"
            + "[ \"${archive%% *}\" = '" + captured.workspace().archiveSha256() + "' ] || reject ARCHIVE ARCHIVE_SHA_MISMATCH\n";
        typed = replaceOne(typed, archive, "if [ \"$preparation_form\" = UPLOADED ]; then\n" + archive + "fi\n");
        String finalChecks = """
            if [ "$preparation_form" = CREATED_WITHOUT_ARCHIVE ]; then
              exact_entries "$candidate" ROOT
              exact_entries "$candidate/mutable" MUTABLE
              confirm_absent "$candidate/.agent-mode"
              [ "$checked" = MISSING ] || reject WORKSPACE MODE_MISMATCH
              confirm_absent "$candidate/mutable/source.tar.gz"
              [ "$checked" = MISSING ] || reject FILES NON_PRIVATE_FILE
              [ "$candidate_stamp" = "$(/usr/bin/stat -c '%d:%i:%f:%u:%g:%a:%h:%s:%y:%z' -- "$candidate")" ] &&
                [ "$mutable_stamp" = "$(/usr/bin/stat -c '%d:%i:%f:%u:%g:%a:%h:%s:%y:%z' -- "$candidate/mutable")" ] &&
                [ "$original_controls" = "$(control_stamps)" ] || reject WORKSPACE STANDARD_DIRECTORY_CHANGED
            """
            + "printf '%s' '" + stageOutput(captured, PreparationStage.CREATED_WITHOUT_ARCHIVE) + "'\nelse\n"
            + "printf '%s' '" + stageOutput(captured, PreparationStage.UPLOADED) + "'\nfi\n";
        return replaceOne(typed, "printf '%s' '" + presentOutput(captured) + "'\n", finalChecks);
    }

    private static String replaceOne(String source, String original, String replacement) {
        int first = source.indexOf(original);
        if (first < 0 || source.indexOf(original, first + 1) >= 0)
            throw new IllegalStateException("Fixed preparation-stage source is unavailable");
        return source.substring(0, first) + replacement + source.substring(first + original.length());
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
