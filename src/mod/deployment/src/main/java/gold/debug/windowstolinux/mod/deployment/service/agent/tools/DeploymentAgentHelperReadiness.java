package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import java.util.*;

import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;

/** Fresh read-only readiness for this Agent task, without global installation or recovery claims. */
public final class DeploymentAgentHelperReadiness {
    public static final String OPERATION = "deployment.agent.helper.readiness.v1";

    private static final String FORMAT = "WTL_AGENT_HELPER_READINESS_1";

    public enum Status {READY, MISSING, MISMATCH, UNSAFE, UNKNOWN}

    public enum Reason {
        CURRENT_TASK_READY, HELPER_MISSING, BUILD_ENTRY_MISSING, DEPENDENCY_MISSING,
        HELPER_MISMATCH, BUILD_ENTRY_MISMATCH, DEPENDENCY_MISMATCH, UNSAFE_PATH,
        INSTALLATION_UNSETTLED, RECEIPT_INVALID, RECEIPT_LIMIT, OBSERVATION_UNCONFIRMED, INVALID_RECORD
    }

    public record Observation(Status status, Reason reason) {
        public Observation {
            Objects.requireNonNull(status);
            Objects.requireNonNull(reason);
        }
    }

    private DeploymentAgentHelperReadiness() {
    }

    /** No helper, recovery, package-manager or ecosystem executable is invoked. */
    public static String script() {
        return """
            set -uo pipefail
            export LC_ALL=C PATH=/usr/sbin:/usr/bin:/sbin:/bin
            unset BASH_ENV ENV CDPATH
            cd -- / || exit 64
            helper=UNOBSERVED entry=UNOBSERVED dependencies=UNOBSERVED roots=UNOBSERVED receipts=UNOBSERVED
            # WTL_READINESS_GUARDS_BEGIN
            path_metadata() {
              checked=SAFE
              [ ! -L "$1" ] || { checked=UNSAFE; return; }
              if [ "$2" = directory ]; then [ -d "$1" ] || { checked=UNSAFE; return; }
              else [ -f "$1" ] || { checked=UNSAFE; return; }; fi
              owner=$(/usr/bin/stat -c '%%u:%%g' -- "$1") || { checked=UNKNOWN; return; }
              mode=$(/usr/bin/stat -c '%%a' -- "$1") || { checked=UNKNOWN; return; }
              [ "$owner" = 0:0 ] && [[ "$mode" =~ ^[0-7]{3,4}$ ]] || { checked=UNSAFE; return; }
              (( (8#$mode & 022) == 0 )) || { checked=UNSAFE; return; }
            }
            managed_parents() {
              local ancestor
              [[ "$1" = /* ]] || { checked=UNSAFE; return; }
              ancestor="${1%%/*}"
              [ -n "$ancestor" ] || ancestor=/
              while true; do
                if [ -e "$ancestor" ] || [ -L "$ancestor" ]; then
                  path_metadata "$ancestor" directory
                  [ "$checked" = SAFE ] || return
                else
                  confirm_absent "$ancestor"
                  [ "$checked" = MISSING ] || return
                fi
                [ "$ancestor" != / ] || break
                ancestor="${ancestor%%/*}"
                [ -n "$ancestor" ] || ancestor=/
              done
              checked=SAFE
            }
            trusted_system_path() {
              local executable="$1" kind="$2" resolved=/ component target link_owner hops=0 steps=0
              local -a pending parts
              checked=SAFE
              [[ "$executable" = /* ]] || { checked=UNSAFE; return; }
              path_metadata / directory
              [ "$checked" = SAFE ] || return
              IFS=/ read -r -a pending <<< "$executable"
              while [ "${#pending[@]}" -gt 0 ]; do
                steps=$((steps + 1)); [ "$steps" -le 512 ] || { checked=UNKNOWN; return; }
                component="${pending[0]}"; pending=("${pending[@]:1}")
                case "$component" in '' | .) continue ;; ..) resolved="${resolved%%/*}"; [ -n "$resolved" ] || resolved=/; continue ;; esac
                executable="${resolved%%/}/$component"
                if [ -L "$executable" ]; then
                  hops=$((hops + 1)); [ "$hops" -le 40 ] || { checked=UNKNOWN; return; }
                  link_owner=$(/usr/bin/stat -c '%%u:%%g' -- "$executable") || { checked=UNKNOWN; return; }
                  [ "$link_owner" = 0:0 ] || { checked=UNSAFE; return; }
                  target=$(/usr/bin/readlink -- "$executable") || { checked=UNKNOWN; return; }
                  [[ -n "$target" && "$target" != *$'\\n'* ]] || { checked=UNKNOWN; return; }
                  [[ "$target" != /* ]] || resolved=/
                  IFS=/ read -r -a parts <<< "$target"
                  pending=("${parts[@]}" "${pending[@]}")
                  continue
                fi
                [ -e "$executable" ] || { if [ "$hops" -gt 0 ]; then checked=UNSAFE; else checked=MISSING; fi; return; }
                if [ "${#pending[@]}" -gt 0 ]; then path_metadata "$executable" directory
                else path_metadata "$executable" "$kind"; fi
                [ "$checked" = SAFE ] || return
                resolved="$executable"
              done
              if [ "$kind" = file ]; then [ -x "$resolved" ] || checked=MISMATCH; fi
            }
            trusted_executable() {
              trusted_system_path "$1" file
            }
            confirm_absent() {
              local path="$1" prefix=/ component parent listing
              local -a components
              checked=UNKNOWN
              [[ "$path" = /* ]] || return
              IFS=/ read -r -a components <<< "$path"
              for component in "${components[@]}"; do
                [ -n "$component" ] || continue
                parent="$prefix"; prefix="${prefix%%/}/$component"
                if [ ! -e "$prefix" ] && [ ! -L "$prefix" ]; then
                  trusted_system_path "$parent" directory
                  [ "$checked" = SAFE ] || return
                  listing=$(/usr/bin/find -H "$parent" -mindepth 1 -maxdepth 1 -name "$component" -printf 'PRESENT\\n') || { checked=UNKNOWN; return; }
                  if [ -z "$listing" ]; then checked=MISSING; else checked=UNKNOWN; fi
                  return
                fi
              done
              checked=UNKNOWN
            }
            # WTL_READINESS_GUARDS_END
            classify() {
              status=READY reason=CURRENT_TASK_READY
              case "$receipts" in
                STARTED) status=UNKNOWN reason=INSTALLATION_UNSETTLED; return ;;
                INVALID) status=UNKNOWN reason=RECEIPT_INVALID; return ;;
                LIMIT) status=UNKNOWN reason=RECEIPT_LIMIT; return ;;
              esac
              if [[ "$helper $entry $dependencies $roots $receipts" = *UNOBSERVED* ]]; then status=UNKNOWN reason=OBSERVATION_UNCONFIRMED; return; fi
              if [[ "$helper $entry $dependencies $roots $receipts" = *UNSAFE* ]]; then status=UNSAFE reason=UNSAFE_PATH; return; fi
              if [ "$helper" = DIFFERENT ]; then status=MISMATCH reason=HELPER_MISMATCH
              elif [ "$entry" = DIFFERENT ]; then status=MISMATCH reason=BUILD_ENTRY_MISMATCH
              elif [ "$dependencies" = DIFFERENT ]; then status=MISMATCH reason=DEPENDENCY_MISMATCH
              elif [ "$helper" = ABSENT ]; then status=MISSING reason=HELPER_MISSING
              elif [ "$entry" = ABSENT ]; then status=MISSING reason=BUILD_ENTRY_MISSING
              elif [ "$dependencies" = ABSENT ]; then status=MISSING reason=DEPENDENCY_MISSING; fi
            }
            emit() {
              classify
              printf 'FORMAT=%s\nSTATUS=%%s\nREASON=%%s\nPACKAGED_PROTOCOL=%d\nHELPER=%%s\nBUILD_ENTRY=%%s\nDEPENDENCIES=%%s\nROOTS=%%s\nRECEIPTS=%%s\n' "$status" "$reason" "$helper" "$entry" "$dependencies" "$roots" "$receipts"
            }
            managed_file() {
              local file="$1" expected="$2" digest
              managed_parents "$file"
              if [ "$checked" != SAFE ]; then file_fact="$checked"; [ "$file_fact" != UNKNOWN ] || file_fact=UNOBSERVED; return; fi
              if [ ! -e "$file" ] && [ ! -L "$file" ]; then
                confirm_absent "$file"
                case "$checked" in MISSING) file_fact=ABSENT ;; UNSAFE) file_fact=UNSAFE ;; *) file_fact=UNOBSERVED ;; esac
                return
              fi
              path_metadata "$file" file
              if [ "$checked" != SAFE ]; then file_fact="$checked"; [ "$file_fact" != UNKNOWN ] || file_fact=UNOBSERVED; return; fi
              if [ "$mode" != 755 ]; then file_fact=DIFFERENT; return; fi
              digest=$(/usr/bin/sha256sum -- "$file") || { file_fact=UNOBSERVED; return; }
              digest="${digest%%%% *}"
              [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || { file_fact=UNOBSERVED; return; }
              if [ "$digest" = "$expected" ]; then file_fact=MATCH; else file_fact=DIFFERENT; fi
            }
            # Bootstrap tools are the same fixed protected system metadata tools used by host probes.
            for tool in /usr/bin/stat /usr/bin/readlink /usr/bin/sha256sum /usr/bin/find /usr/bin/id; do
              trusted_executable "$tool"
              [ "$checked" = SAFE ] || { emit; exit 0; }
            done
            root_user=$(/usr/bin/id -u) || { emit; exit 0; }
            [ "$root_user" = 0 ] || { emit; exit 0; }
            managed_file '%s' '%s'; helper="$file_fact"
            managed_file '%s' '%s'; entry="$file_fact"
            dependencies=MATCH
            for directory in /usr/sbin /usr/bin /sbin /bin; do
              trusted_system_path "$directory" directory
              if [ "$checked" = MISSING ]; then confirm_absent "$directory"; fi
              case "$checked" in UNKNOWN) dependencies=UNOBSERVED ;; UNSAFE | MISMATCH) [ "$dependencies" = UNOBSERVED ] || dependencies=UNSAFE ;; esac
            done
            for dependency in %s; do
              executable=$(builtin command -v "$dependency") || executable=
              if [ -z "$executable" ]; then
                dependency_fact=ABSENT
                for directory in /usr/sbin /usr/bin /sbin /bin; do
                  confirm_absent "$directory/$dependency"
                  case "$checked" in MISSING) ;; UNSAFE) [ "$dependency_fact" = UNOBSERVED ] || dependency_fact=UNSAFE ;; *) dependency_fact=UNOBSERVED ;; esac
                done
              else
                trusted_executable "$executable"
                if [ "$checked" = MISSING ]; then confirm_absent "$executable"; fi
                case "$checked" in SAFE) dependency_fact=MATCH ;; MISSING) dependency_fact=ABSENT ;; MISMATCH) dependency_fact=DIFFERENT ;; UNKNOWN) dependency_fact=UNOBSERVED ;; *) dependency_fact=UNSAFE ;; esac
              fi
              case "$dependency_fact" in
                UNSAFE) [ "$dependencies" = UNOBSERVED ] || dependencies=UNSAFE ;;
                UNOBSERVED) dependencies=UNOBSERVED ;;
                DIFFERENT) case "$dependencies" in MATCH | ABSENT) dependencies=DIFFERENT ;; esac ;;
                ABSENT) [ "$dependencies" != MATCH ] || dependencies=ABSENT ;;
              esac
            done
            roots=VALID_OR_ABSENT
            for root in /var/lib/windowstolinux /var/lib/windowstolinux/work /var/lib/windowstolinux/snapshots /var/lib/windowstolinux/backups /opt/windowstolinux/apps /etc/opt/windowstolinux/apps /var/opt/windowstolinux/apps; do
              managed_parents "$root"
              if [ "$checked" = SAFE ]; then
                if [ -e "$root" ] || [ -L "$root" ]; then path_metadata "$root" directory
                else confirm_absent "$root"; fi
              fi
              case "$checked" in UNSAFE) [ "$roots" = UNOBSERVED ] || roots=UNSAFE ;; UNKNOWN) roots=UNOBSERVED ;; esac
            done
            receipt_directory=/var/lib/windowstolinux/helper-install-operations
            managed_parents "$receipt_directory"
            case "$checked" in
              UNSAFE) receipts=UNSAFE ;;
              UNKNOWN) receipts=UNOBSERVED ;;
              SAFE)
                if [ ! -e "$receipt_directory" ] && [ ! -L "$receipt_directory" ]; then
                  confirm_absent "$receipt_directory"
                  case "$checked" in MISSING) receipts=ABSENT ;; UNSAFE) receipts=UNSAFE ;; *) receipts=UNOBSERVED ;; esac
                else
                  path_metadata "$receipt_directory" directory
                  if [ "$checked" = SAFE ] && [ "$mode" = 700 ]; then receipts=SETTLED
                  elif [ "$checked" = UNKNOWN ]; then receipts=UNOBSERVED
                  else receipts=UNSAFE; fi
                fi ;;
            esac
            if [ "$receipts" = SETTLED ]; then
              receipt_count=0 receipt_started=0 receipt_invalid=0 receipt_unsafe=0 receipt_unknown=0 receipt_limit=0
              shopt -s lastpipe
              /usr/bin/find -P "$receipt_directory" -mindepth 1 -maxdepth 1 -print0 |
              while IFS= read -r -d '' receipt_file; do
                receipt_count=$((receipt_count + 1))
                if [ "$receipt_count" -gt 64 ]; then receipt_limit=1; break; fi
                operation="${receipt_file##*/}"
                if [[ ! "$operation" =~ ^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}[.]receipt$ ]]; then receipt_invalid=1; continue; fi
                operation="${operation%%.receipt}"
                path_metadata "$receipt_file" file
                if [ "$checked" = UNKNOWN ]; then receipt_unknown=1; continue; fi
                if [ "$checked" != SAFE ] || [ "$mode" != 600 ]; then receipt_unsafe=1; continue; fi
                receipt_size=$(/usr/bin/stat -c '%%s' -- "$receipt_file") || { receipt_unknown=1; continue; }
                receipt_stamp=$(/usr/bin/stat -c '%%d:%%i:%%s:%%Y:%%Z' -- "$receipt_file") || { receipt_unknown=1; continue; }
                if [[ ! "$receipt_size" =~ ^[0-9]+$ ]] || [ "$receipt_size" -gt 1024 ]; then receipt_invalid=1; continue; fi
                receipt_lines=() receipt_bytes=0
                if ! { while IFS= read -r -n 1025 line || [ -n "$line" ]; do
                  receipt_lines+=("$line"); receipt_bytes=$((receipt_bytes + ${#line} + 1))
                  [ "$receipt_bytes" -le 1024 ] && [ "${#receipt_lines[@]}" -le 7 ] || break
                done; } < "$receipt_file"; then receipt_unknown=1; continue; fi
                if [ "${#receipt_lines[@]}" -ne 7 ] || [ "$receipt_bytes" -ne "$receipt_size" ]; then receipt_invalid=1; continue; fi
                if [ "${receipt_lines[0]}" != FORMAT=1 ] ||
                  [[ ! "${receipt_lines[1]}" =~ ^TASK=[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$ ]] ||
                  [ "${receipt_lines[2]}" != "OPERATION=$operation" ] ||
                  [[ ! "${receipt_lines[3]}" =~ ^SERVER_SHA256=[0-9a-f]{64}$ ]] ||
                  [[ ! "${receipt_lines[4]}" =~ ^BODY_SHA256=[0-9a-f]{64}$ ]] ||
                  [[ ! "${receipt_lines[5]}" =~ ^HELPER_SHA256=[0-9a-f]{64}$ ]] ||
                  [[ ! "${receipt_lines[6]}" =~ ^PHASE=(STARTED|SUCCEEDED)$ ]]; then receipt_invalid=1; continue; fi
                current_stamp=$(/usr/bin/stat -c '%%d:%%i:%%s:%%Y:%%Z' -- "$receipt_file") || { receipt_unknown=1; continue; }
                if [ "$current_stamp" != "$receipt_stamp" ]; then receipt_unknown=1; continue; fi
                [ "${receipt_lines[6]}" != PHASE=STARTED ] || receipt_started=1
              done
              scan_exit=$?
              [ "$scan_exit" -eq 0 ] || receipt_unknown=1
              if [ "$receipt_limit" -eq 1 ]; then receipts=LIMIT
              elif [ "$receipt_started" -eq 1 ]; then receipts=STARTED
              elif [ "$receipt_unknown" -eq 1 ]; then receipts=UNOBSERVED
              elif [ "$receipt_invalid" -eq 1 ]; then receipts=INVALID
              elif [ "$receipt_unsafe" -eq 1 ]; then receipts=UNSAFE; fi
            fi
            emit
            """.formatted(FORMAT, DeploymentManagedHelperBundle.PROTOCOL_VERSION,
            DeploymentManagedHelperBundle.PATH, DeploymentManagedHelperBundle.EXPECTED_SHA256,
            DeploymentManagedHelperBundle.BUILD_ENTRY_PATH, DeploymentManagedHelperBundle.buildEntrySha256(),
            String.join(" ", DeploymentHelperInstallerScript.RUNTIME_COMMANDS));
    }

    /** Malformed or incomplete observations never establish absence or permit installation. */
    public static Observation parse(CommandResult result) {
        if (result == null || result.truncated() || !result.exitCode().equals(Optional.of(0)) || !result.stderr().isEmpty())
            return new Observation(Status.UNKNOWN, Reason.OBSERVATION_UNCONFIRMED);
        String output = result.stdout();
        if (output.length() > 2048 || !output.endsWith("\n") || output.indexOf('\r') >= 0 || output.indexOf('\0') >= 0)
            return new Observation(Status.UNKNOWN, Reason.INVALID_RECORD);
        var fields = new HashMap<String, String>();
        for (String line : output.substring(0, output.length() - 1).split("\n", -1)) {
            int at = line.indexOf('=');
            if (at < 1 || fields.putIfAbsent(line.substring(0, at), line.substring(at + 1)) != null)
                return new Observation(Status.UNKNOWN, Reason.INVALID_RECORD);
        }
        if (!fields.keySet().equals(Set.of("FORMAT", "STATUS", "REASON", "PACKAGED_PROTOCOL", "HELPER", "BUILD_ENTRY", "DEPENDENCIES", "ROOTS", "RECEIPTS"))
            || !FORMAT.equals(fields.get("FORMAT")) || !Integer.toString(DeploymentManagedHelperBundle.PROTOCOL_VERSION).equals(fields.get("PACKAGED_PROTOCOL"))
            || !Set.of("MATCH", "ABSENT", "DIFFERENT", "UNSAFE", "UNOBSERVED").contains(fields.get("HELPER"))
            || !Set.of("MATCH", "ABSENT", "DIFFERENT", "UNSAFE", "UNOBSERVED").contains(fields.get("BUILD_ENTRY"))
            || !Set.of("MATCH", "ABSENT", "DIFFERENT", "UNSAFE", "UNOBSERVED").contains(fields.get("DEPENDENCIES"))
            || !Set.of("VALID_OR_ABSENT", "UNSAFE", "UNOBSERVED").contains(fields.get("ROOTS"))
            || !Set.of("ABSENT", "SETTLED", "STARTED", "INVALID", "LIMIT", "UNSAFE", "UNOBSERVED").contains(fields.get("RECEIPTS")))
            return new Observation(Status.UNKNOWN, Reason.INVALID_RECORD);
        Observation expected = classify(fields);
        if (!expected.status().name().equals(fields.get("STATUS")) || !expected.reason().name().equals(fields.get("REASON")))
            return new Observation(Status.UNKNOWN, Reason.INVALID_RECORD);
        return expected;
    }

    private static Observation classify(Map<String, String> fields) {
        var receipt = fields.get("RECEIPTS");
        if (receipt.equals("STARTED")) return new Observation(Status.UNKNOWN, Reason.INSTALLATION_UNSETTLED);
        if (receipt.equals("INVALID")) return new Observation(Status.UNKNOWN, Reason.RECEIPT_INVALID);
        if (receipt.equals("LIMIT")) return new Observation(Status.UNKNOWN, Reason.RECEIPT_LIMIT);
        var facts = List.of(fields.get("HELPER"), fields.get("BUILD_ENTRY"), fields.get("DEPENDENCIES"), fields.get("ROOTS"), receipt);
        if (facts.contains("UNOBSERVED")) return new Observation(Status.UNKNOWN, Reason.OBSERVATION_UNCONFIRMED);
        if (facts.contains("UNSAFE")) return new Observation(Status.UNSAFE, Reason.UNSAFE_PATH);
        if (fields.get("HELPER").equals("DIFFERENT")) return new Observation(Status.MISMATCH, Reason.HELPER_MISMATCH);
        if (fields.get("BUILD_ENTRY").equals("DIFFERENT"))
            return new Observation(Status.MISMATCH, Reason.BUILD_ENTRY_MISMATCH);
        if (fields.get("DEPENDENCIES").equals("DIFFERENT"))
            return new Observation(Status.MISMATCH, Reason.DEPENDENCY_MISMATCH);
        if (fields.get("HELPER").equals("ABSENT")) return new Observation(Status.MISSING, Reason.HELPER_MISSING);
        if (fields.get("BUILD_ENTRY").equals("ABSENT"))
            return new Observation(Status.MISSING, Reason.BUILD_ENTRY_MISSING);
        if (fields.get("DEPENDENCIES").equals("ABSENT"))
            return new Observation(Status.MISSING, Reason.DEPENDENCY_MISSING);
        return new Observation(Status.READY, Reason.CURRENT_TASK_READY);
    }
}
