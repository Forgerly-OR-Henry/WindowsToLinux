package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperInstallerScript;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;

class DeploymentAgentHelperReadinessTest {
    @Test
    void completeCurrentTaskFactsAllowAbsentRootsAndAbsentHistoricalReceipts() {
        assertObservation("READY", "CURRENT_TASK_READY", "MATCH", "MATCH", "MATCH", "VALID_OR_ABSENT", "ABSENT",
            DeploymentAgentHelperReadiness.Status.READY, DeploymentAgentHelperReadiness.Reason.CURRENT_TASK_READY);
        assertObservation("READY", "CURRENT_TASK_READY", "MATCH", "MATCH", "MATCH", "VALID_OR_ABSENT", "SETTLED",
            DeploymentAgentHelperReadiness.Status.READY, DeploymentAgentHelperReadiness.Reason.CURRENT_TASK_READY);
    }

    @Test
    void absenceRequiresCompleteSafeFactsAndNoUnsettledReceipt() {
        assertObservation("MISSING", "HELPER_MISSING", "ABSENT", "MATCH", "MATCH", "VALID_OR_ABSENT", "ABSENT",
            DeploymentAgentHelperReadiness.Status.MISSING, DeploymentAgentHelperReadiness.Reason.HELPER_MISSING);
        assertObservation("MISSING", "BUILD_ENTRY_MISSING", "MATCH", "ABSENT", "MATCH", "VALID_OR_ABSENT", "SETTLED",
            DeploymentAgentHelperReadiness.Status.MISSING, DeploymentAgentHelperReadiness.Reason.BUILD_ENTRY_MISSING);
        assertObservation("MISSING", "DEPENDENCY_MISSING", "MATCH", "MATCH", "ABSENT", "VALID_OR_ABSENT", "SETTLED",
            DeploymentAgentHelperReadiness.Status.MISSING, DeploymentAgentHelperReadiness.Reason.DEPENDENCY_MISSING);
        assertObservation("UNKNOWN", "INSTALLATION_UNSETTLED", "ABSENT", "ABSENT", "ABSENT", "VALID_OR_ABSENT", "STARTED",
            DeploymentAgentHelperReadiness.Status.UNKNOWN, DeploymentAgentHelperReadiness.Reason.INSTALLATION_UNSETTLED);
        assertObservation("UNKNOWN", "OBSERVATION_UNCONFIRMED", "ABSENT", "MATCH", "MATCH", "UNOBSERVED", "ABSENT",
            DeploymentAgentHelperReadiness.Status.UNKNOWN, DeploymentAgentHelperReadiness.Reason.OBSERVATION_UNCONFIRMED);
        assertObservation("UNSAFE", "UNSAFE_PATH", "ABSENT", "MATCH", "MATCH", "UNSAFE", "SETTLED",
            DeploymentAgentHelperReadiness.Status.UNSAFE, DeploymentAgentHelperReadiness.Reason.UNSAFE_PATH);
        assertObservation("MISMATCH", "BUILD_ENTRY_MISMATCH", "ABSENT", "DIFFERENT", "MATCH", "VALID_OR_ABSENT", "ABSENT",
            DeploymentAgentHelperReadiness.Status.MISMATCH, DeploymentAgentHelperReadiness.Reason.BUILD_ENTRY_MISMATCH);
    }

    @Test
    void invalidOrIncompleteEnumerationCannotBecomeMissing() {
        assertObservation("UNKNOWN", "RECEIPT_INVALID", "ABSENT", "MATCH", "MATCH", "VALID_OR_ABSENT", "INVALID",
            DeploymentAgentHelperReadiness.Status.UNKNOWN, DeploymentAgentHelperReadiness.Reason.RECEIPT_INVALID);
        assertObservation("UNKNOWN", "RECEIPT_LIMIT", "ABSENT", "MATCH", "MATCH", "VALID_OR_ABSENT", "LIMIT",
            DeploymentAgentHelperReadiness.Status.UNKNOWN, DeploymentAgentHelperReadiness.Reason.RECEIPT_LIMIT);
        assertObservation("UNKNOWN", "OBSERVATION_UNCONFIRMED", "ABSENT", "MATCH", "MATCH", "VALID_OR_ABSENT", "UNOBSERVED",
            DeploymentAgentHelperReadiness.Status.UNKNOWN, DeploymentAgentHelperReadiness.Reason.OBSERVATION_UNCONFIRMED);
    }

    @Test
    void duplicateExtraTrailingMissingAndUnknownFieldsAreRejected() {
        String valid = ready();
        for (String invalid : new String[]{
            valid + "HELPER=MATCH\n", valid + "STDOUT=secret\n", valid + "ignored suffix", valid + "\n",
            valid.replace("DEPENDENCIES=MATCH\n", ""), valid.replace("ROOTS=VALID_OR_ABSENT", "ROOTS=SAFE"),
            valid.replace("HELPER=MATCH", "HELPER=MATCH\u0000"), valid.replace("\n", "\r\n"),
            valid.replace("RECEIPTS=ABSENT", "RECEIPTS=RECOVERED"),
            valid.replace("PACKAGED_PROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION, "OBSERVED_PROTOCOL=16")
        }) {
            assertEquals(new DeploymentAgentHelperReadiness.Observation(DeploymentAgentHelperReadiness.Status.UNKNOWN,
                DeploymentAgentHelperReadiness.Reason.INVALID_RECORD), parse(invalid), invalid);
        }
    }

    @Test
    void claimedStatusMustAgreeWithEveryFixedFact() {
        for (String contradictory : new String[]{
            ready().replace("HELPER=MATCH", "HELPER=ABSENT"),
            ready().replace("BUILD_ENTRY=MATCH", "BUILD_ENTRY=DIFFERENT"),
            ready().replace("RECEIPTS=ABSENT", "RECEIPTS=STARTED"),
            ready().replace("REASON=CURRENT_TASK_READY", "REASON=HELPER_MISSING"),
            ready().replace("STATUS=READY", "STATUS=MISSING")
        }) {
            assertEquals(DeploymentAgentHelperReadiness.Reason.INVALID_RECORD, parse(contradictory).reason());
        }
    }

    @Test
    void truncationUnknownExitFailureAndStderrNeverProveAbsence() {
        String missing = record("MISSING", "HELPER_MISSING", "ABSENT", "MATCH", "MATCH", "VALID_OR_ABSENT", "ABSENT");
        for (CommandResult result : new CommandResult[]{
            new CommandResult(Optional.of(0), missing, "", true),
            new CommandResult(Optional.empty(), missing, "", false),
            new CommandResult(Optional.of(64), missing, "", false),
            new CommandResult(Optional.of(0), missing, "metadata error", false)
        }) {
            assertEquals(new DeploymentAgentHelperReadiness.Observation(DeploymentAgentHelperReadiness.Status.UNKNOWN,
                DeploymentAgentHelperReadiness.Reason.OBSERVATION_UNCONFIRMED), DeploymentAgentHelperReadiness.parse(result));
        }
        assertEquals(DeploymentAgentHelperReadiness.Status.UNKNOWN, DeploymentAgentHelperReadiness.parse(null).status());
    }

    @Test
    void renderedProbePinsBothBytesAndOnlyLocatesTheSharedDependencies() {
        String script = DeploymentAgentHelperReadiness.script();
        assertTrue(script.contains("managed_file '" + DeploymentManagedHelperBundle.PATH + "' '"
            + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "'"));
        assertTrue(script.contains("managed_file '" + DeploymentManagedHelperBundle.BUILD_ENTRY_PATH + "' '"
            + DeploymentManagedHelperBundle.buildEntrySha256() + "'"));
        assertTrue(script.contains("for dependency in " + String.join(" ", DeploymentHelperInstallerScript.RUNTIME_COMMANDS) + "; do"));
        assertTrue(script.contains("builtin command -v \"$dependency\""));
        for (String forbidden : new String[]{"--version", "managed-helper workspace-recover", "systemctl start", "systemctl enable",
            "systemd-run --", "apt-get", "dnf install", "eval ", "source ", "\"$executable\" --"})
            assertFalse(script.contains(forbidden), forbidden);
        assertFalse(script.contains("OBSERVED_PROTOCOL="));
        assertFalse(script.contains("/usr/bin/cat -- \"$receipt_file\""));
    }

    @Test
    void renderedProbeIsValidBashWithoutExecutingIt() throws Exception {
        runBash(DeploymentAgentHelperReadiness.script(), true);
    }

    @Test
    void actualPathGuardsAcceptMergedSystemAndExecutableLinksWithoutRunningThem() throws Exception {
        runBash(guardFixture() + """
            mkdir -p "$fixture/usr/bin"
            ln -s usr/bin "$fixture/bin"
            printf '#!/bin/bash\\ntouch "$WTL_EXECUTION_SENTINEL"\\n' > "$fixture/usr/bin/python3.11"
            chmod 755 "$fixture/usr/bin/python3.11"
            ln -s python3.11 "$fixture/usr/bin/python3"
            export WTL_EXECUTION_SENTINEL="$fixture/executed"
            trusted_executable "$fixture/bin/python3"
            [ "$checked" = SAFE ] || { printf 'unexpected=%s\\n' "$checked"; exit 1; }
            [ ! -e "$WTL_EXECUTION_SENTINEL" ]
            managed_parents "$fixture/bin/helper"
            [ "$checked" = UNSAFE ]
            """, false);
    }

    @Test
    void actualPathGuardsRejectWritableIntermediateLinkTargetAndOversizedLinkChain() throws Exception {
        runBash(guardFixture() + """
            mkdir -p "$fixture/trusted" "$fixture/writable"
            printf '#!/bin/bash\\nexit 0\\n' > "$fixture/trusted/tool"
            chmod 755 "$fixture/trusted/tool"
            ln -s ../trusted/tool "$fixture/writable/hop"
            ln -s writable/hop "$fixture/entry"
            chmod 777 "$fixture/writable"
            trusted_executable "$fixture/entry"
            [ "$checked" = UNSAFE ] || exit 1
            chmod 755 "$fixture/writable"
            trusted_executable "$fixture/entry"
            [ "$checked" = SAFE ] || exit 2
            for number in {1..41}; do next=$((number + 1)); ln -s "link$next" "$fixture/link$number"; done
            ln -s trusted/tool "$fixture/link42"
            trusted_executable "$fixture/link1"
            [ "$checked" = UNKNOWN ] || exit 3
            """, false);
    }

    @Test
    void genuineAbsenceRequiresSuccessfulSafeParentEnumeration() throws Exception {
        runBash(guardFixture() + """
            mkdir -p "$fixture/trusted"
            confirm_absent "$fixture/trusted/missing/tool"
            [ "$checked" = MISSING ] || exit 1
            chmod 777 "$fixture/trusted"
            confirm_absent "$fixture/trusted/missing/tool"
            [ "$checked" = UNSAFE ] || exit 2
            """, false);
        runBash(guardFixture().replace("/usr/bin/find -H", "fixture_find -H") + """
            fixture_find() { return 64; }
            confirm_absent "$fixture/absent"
            [ "$checked" = UNKNOWN ] || exit 3
            """, false);
    }

    @Test
    void actualReceiptScanPreservesStartedEvenAlongsideMalformedEntries() throws Exception {
        String operation = "12345678-1234-1234-1234-123456789abc";
        String receipt = DeploymentHelperInstallerScript.receipt(java.util.UUID.fromString(operation),
            java.util.UUID.fromString(operation), "a".repeat(64), "b".repeat(64), "STARTED");
        runBash(guardFixture() + receiptFixture(operation, receipt)
            + "printf 'private contents not to be read' > \"$fixture/receipts/unknown-file\"\n"
            + receiptScan() + "\n[ \"$receipts\" = STARTED ] || exit 1\n", false);
    }

    @Test
    void actualReceiptScanRejectsAdditionalFieldsAndUnterminatedRecords() throws Exception {
        String operation = "12345678-1234-1234-1234-123456789abc";
        String receipt = DeploymentHelperInstallerScript.receipt(java.util.UUID.fromString(operation),
            java.util.UUID.fromString(operation), "a".repeat(64), "b".repeat(64), "SUCCEEDED");
        runBash(guardFixture() + receiptFixture(operation, receipt)
            + receiptScan() + "\n[ \"$receipts\" = SETTLED ] || exit 1\n"
            + "printf 'EXTRA=1\\n' >> \"$fixture/receipts/" + operation + ".receipt\"\n"
            + receiptScan() + "\n[ \"$receipts\" = INVALID ] || exit 2\n", false);
        runBash(guardFixture() + receiptFixture(operation, receipt)
            + "truncate -s -1 \"$fixture/receipts/" + operation + ".receipt\"\n"
            + receiptScan() + "\n[ \"$receipts\" = INVALID ] || exit 3\n", false);
    }

    private static String receiptFixture(String operation, String receipt) {
        return "mkdir -p \"$fixture/receipts\"\nchmod 700 \"$fixture/receipts\"\ncat <<'WTL_RECEIPT' > \"$fixture/receipts/"
            + operation + ".receipt\"\n" + receipt + "WTL_RECEIPT\nchmod 600 \"$fixture/receipts/" + operation + ".receipt\"\n";
    }

    private static String receiptScan() {
        String script = DeploymentAgentHelperReadiness.script();
        return script.substring(script.indexOf("receipt_directory="), script.lastIndexOf("emit\n"))
            .replace("receipt_directory=" + DeploymentHelperInstallerScript.RECEIPT_DIRECTORY,
                "receipt_directory=\"$fixture/receipts\"")
            .replace("/usr/bin/stat", "fixture_stat");
    }

    private static String guardFixture() {
        String script = DeploymentAgentHelperReadiness.script();
        String guards = script.substring(script.indexOf("# WTL_READINESS_GUARDS_BEGIN"), script.indexOf("# WTL_READINESS_GUARDS_END"));
        return """
            set -euo pipefail
            fixture=$(mktemp -d "$(pwd -P)/.wtl-readiness-fixture.XXXXXX")
            trap 'rm -rf -- "$fixture"' EXIT
            chmod 755 "$fixture"
            # Only ownership is virtual root; every mode and link uses the actual filesystem.
            fixture_stat() {
              local format="$2"
              if [ "$format" = '%%u:%%g' ]; then printf '0:0\\n'
              else /usr/bin/stat "$@"; fi
            }
            """.formatted() + guards.replace("/usr/bin/stat", "fixture_stat");
    }

    private static void runBash(String script, boolean syntaxOnly) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)), "Configured local Bash is unavailable");
        if (!syntaxOnly) assumeFilesystemCapabilities(bash);
        BashExecution execution = executeBash(bash, script, syntaxOnly);
        assertEquals(0, execution.exitCode(), execution.output());
    }

    private static void assumeFilesystemCapabilities(String bash) throws Exception {
        BashExecution capability = executeBash(bash, """
            set -uo pipefail
            export LC_ALL=C
            unsupported() { printf 'UNSUPPORTED=%s\\n' "$1"; exit 1; }
            parent=$(pwd -P) || unsupported WORKING_DIRECTORY
            while true; do
              [ ! -L "$parent" ] && [ -d "$parent" ] || unsupported PROTECTED_PARENT
              mode=$(/usr/bin/stat -c '%a' -- "$parent") || unsupported PARENT_STAT
              [[ "$mode" =~ ^[0-7]{3,4}$ ]] && (( (8#$mode & 022) == 0 )) || unsupported PARENT_MODE
              [ "$parent" != / ] || break
              parent="${parent%/*}"; [ -n "$parent" ] || parent=/
            done
            fixture=$(mktemp -d "$(pwd -P)/.wtl-readiness-capability.XXXXXX") || unsupported TEMP_DIRECTORY
            trap 'rm -rf -- "$fixture"' EXIT
            mkdir "$fixture/directory" || unsupported DIRECTORY
            printf '#!/bin/bash\\nexit 0\\n' > "$fixture/target" || unsupported REGULAR_FILE
            ln -s target "$fixture/link" || unsupported NATIVE_SYMLINK
            [ -L "$fixture/link" ] || unsupported SYMLINK_TEST
            [ "$(/usr/bin/readlink -- "$fixture/link")" = target ] || unsupported READLINK
            [ "$(/usr/bin/stat -c '%F' -- "$fixture/link")" = 'symbolic link' ] || unsupported SYMLINK_STAT
            [ "$(/usr/bin/stat -c '%d:%i' -- "$fixture/link")" != "$(/usr/bin/stat -c '%d:%i' -- "$fixture/target")" ] || unsupported SYMLINK_INODE
            [ "$(/usr/bin/stat -c '%h' -- "$fixture/target")" = 1 ] || unsupported INITIAL_LINK_COUNT
            ln "$fixture/target" "$fixture/hardlink" || unsupported HARDLINK
            [ "$(/usr/bin/stat -c '%h' -- "$fixture/target")" = 2 ] || unsupported LINK_COUNT
            [ "$(/usr/bin/stat -c '%d:%i' -- "$fixture/hardlink")" = "$(/usr/bin/stat -c '%d:%i' -- "$fixture/target")" ] || unsupported HARDLINK_INODE
            chmod 755 "$fixture/directory" || unsupported CHMOD_755
            [ "$(/usr/bin/stat -c '%a' -- "$fixture/directory")" = 755 ] || unsupported MODE_755
            chmod 777 "$fixture/directory" || unsupported CHMOD_777
            [ "$(/usr/bin/stat -c '%a' -- "$fixture/directory")" = 777 ] || unsupported MODE_777
            chmod 700 "$fixture/directory" || unsupported CHMOD_700
            [ "$(/usr/bin/stat -c '%a' -- "$fixture/directory")" = 700 ] || unsupported MODE_700
            chmod 600 "$fixture/target" || unsupported CHMOD_600
            [ "$(/usr/bin/stat -c '%a' -- "$fixture/target")" = 600 ] || unsupported MODE_600
            chmod 755 "$fixture/target" || unsupported EXECUTABLE_MODE
            [ "$(/usr/bin/stat -c '%a' -- "$fixture/target")" = 755 ] && [ -x "$fixture/target" ] || unsupported EXECUTABLE_STAT
            printf 'CAPABILITIES=NATIVE_LINKS_AND_POSIX_MODES\\n'
            """, false);
        Assumptions.assumeTrue(capability.exitCode() == 0
                && capability.output().equals("CAPABILITIES=NATIVE_LINKS_AND_POSIX_MODES\n"),
            "Real filesystem fixture requires native symlinks, link counts and POSIX chmod/stat semantics: " + capability.output());
    }

    private record BashExecution(int exitCode, String output) {
    }

    private static BashExecution executeBash(String bash, String script, boolean syntaxOnly) throws Exception {
        var command = new java.util.ArrayList<String>(java.util.List.of(bash, "--noprofile", "--norc"));
        if (syntaxOnly) command.add("-n");
        command.add("-s");
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("MSYS", "winsymlinks:nativestrict");
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new BashExecution(process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void assertObservation(String status, String reason, String helper, String entry, String dependencies,
                                          String roots, String receipts, DeploymentAgentHelperReadiness.Status expectedStatus,
                                          DeploymentAgentHelperReadiness.Reason expectedReason) {
        assertEquals(new DeploymentAgentHelperReadiness.Observation(expectedStatus, expectedReason),
            parse(record(status, reason, helper, entry, dependencies, roots, receipts)));
    }

    private static DeploymentAgentHelperReadiness.Observation parse(String stdout) {
        return DeploymentAgentHelperReadiness.parse(new CommandResult(Optional.of(0), stdout, "", false));
    }

    private static String ready() {
        return record("READY", "CURRENT_TASK_READY", "MATCH", "MATCH", "MATCH", "VALID_OR_ABSENT", "ABSENT");
    }

    private static String record(String status, String reason, String helper, String entry, String dependencies,
                                 String roots, String receipts) {
        return "FORMAT=WTL_AGENT_HELPER_READINESS_1\nSTATUS=" + status + "\nREASON=" + reason + "\nPACKAGED_PROTOCOL="
            + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\nHELPER=" + helper + "\nBUILD_ENTRY=" + entry
            + "\nDEPENDENCIES=" + dependencies + "\nROOTS=" + roots + "\nRECEIPTS=" + receipts + "\n";
    }
}
