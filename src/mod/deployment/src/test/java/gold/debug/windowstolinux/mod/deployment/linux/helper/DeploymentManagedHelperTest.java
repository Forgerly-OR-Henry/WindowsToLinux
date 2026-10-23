package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.linux.command.CommandResult;
import gold.debug.windowstolinux.appapi.linux.session.RemoteSession;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.action.ApprovalGrant;
import gold.debug.windowstolinux.appapi.task.action.InteractionService;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.task.result.OperationResult;
import gold.debug.windowstolinux.appapi.task.result.Problem;
import gold.debug.windowstolinux.appapi.task.result.ProblemCode;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentManagedHelperTest {
    @TempDir
    Path temporary;

    @Test
    void defaultInstallersPinCurrentSharedRuntimeBytes() throws Exception {
        assertEquals("ed9611481988bab576df004de4d78666032d563c7354f54b1b5b93a3d774e52a",
            digest(DeploymentHelperInstallerScript.render()));
        assertEquals("6bec322a909ac1145a10b8855176551436b27aeb370541b771f73b5e0c87afd7",
            digest(DeploymentHelperInstallerScript.renderHelperInstallation()));
        assertEquals("3fb66b69e7bae8b66da238e994613577642d5921c87a336314858abe92bef3b2",
            digest(DeploymentHelperInstallerScript.renderTracked(task().id(),
                UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), "a".repeat(64),
                digest(DeploymentHelperInstallerScript.render()))));
    }

    @Test
    void originalInstallationStatusDeclaresOnlyItsReceiptAndInspectedDirectories() throws Exception {
        var operation = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        var layout = gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original();
        var server = new ServerId("original");
        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(ok("original receipt", ""));
        var requests = new ArrayList<CommandRequest>();
        var intents = new ArrayList<ActionIntent>();
        var helper = new DeploymentManagedHelper(session(replies, requests), reviews(intents), server, task(), "revision");
        assertEquals(TaskResult.SUCCEEDED, helper.installationStatus(operation).outcome());
        assertEquals(1, requests.size());
        assertEquals(DeploymentHelperInstallerScript.renderStatus(operation),
            new String(requests.getFirst().stdin(), StandardCharsets.UTF_8));
        assertEquals(DeploymentManagedHelper.installationStatusTargets(server, layout, operation),
            intents.getFirst().semantics().orElseThrow().targets());
        var paths = intents.getFirst().semantics().orElseThrow().targets().stream()
            .map(gold.debug.windowstolinux.appapi.task.action.ActionTarget::id).collect(java.util.stream.Collectors.toSet());
        assertTrue(paths.contains(layout.helperInstallOperationsRoot() + "/" + operation + ".receipt"));
        assertTrue(paths.containsAll(DeploymentRemoteLayoutAdmission.ancestors(layout.paths().stateRoot())));
        assertFalse(paths.contains(layout.helperPath()));
    }

    @Test
    void filesInstallerEvidenceRetainsBothSourcesAndAllWrapperBytesWithoutRecoveryRegistration() throws Exception {
        String script = DeploymentHelperInstallerScript.renderFiles();
        var json = new ObjectMapper();
        var evidence = json.valueToTree(DeploymentInstallerEvidence.describeFiles(script));
        assertEquals(script, DeploymentInstallerEvidence.reconstruct(evidence));
        DeploymentInstallerEvidence.verify(evidence, script);
        assertEquals(DeploymentManagedHelperBundle.renderScript(), evidence.path("sources").path("managedHelper").asText());
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(), evidence.path("sources").path("buildEntry").asText());
        assertEquals(5, evidence.path("segments").size());
        StringBuilder wrapper = new StringBuilder();
        for (var segment : evidence.path("segments"))
            if (segment.path("kind").asText().equals("LITERAL")) wrapper.append(segment.path("text").asText());
        assertFalse(wrapper.toString().contains("windowstolinux-workspace-recovery.service"));
        assertFalse(wrapper.toString().contains("workspace-recover"));
        for (String mutation : List.of("systemctl enable", "systemctl disable", "systemctl daemon-reload", "recovery_unit="))
            assertFalse(wrapper.toString().contains(mutation));
        assertTrue(wrapper.toString().contains("systemctl list-unit-files"));
        assertTrue(wrapper.toString().contains("SECURITY_BEFORE="));
        assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.describe(script));
        assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.describeFiles(DeploymentHelperInstallerScript.render()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.describeFiles(script
            .replace(DeploymentHelperInstallerScript.quote(DeploymentManagedHelperBundle.renderBuildEntry()), "'changed'")));
        String extended = "# current additional wrapper\n" + script + "printf 'additional-effect\\n'\n";
        var extendedEvidence = json.valueToTree(DeploymentInstallerEvidence.describeFiles(extended));
        assertEquals(extended, DeploymentInstallerEvidence.reconstruct(extendedEvidence));
        assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.verify(evidence, extended));
        Path command = temporary.resolve("files-installer.sh");
        Files.writeString(command, script);
        assertBashSyntax(System.getProperty("managed.test.bash", "/bin/bash"), command);
    }

    @Test
    void filesInstallerRejectsConflictsBeforePackagesAndOnlyLinksMissingTargets() {
        var evidence = new ObjectMapper().valueToTree(DeploymentInstallerEvidence.describeFiles(DeploymentHelperInstallerScript.renderFiles()));
        String prefix = evidence.path("segments").get(0).path("text").asText();
        String middle = evidence.path("segments").get(2).path("text").asText();
        String tail = evidence.path("segments").get(4).path("text").asText();
        assertTrue(prefix.indexOf("files_check\nfiles_check_runtime") < prefix.indexOf("apt-get -o"));
        assertTrue(prefix.indexOf("PREPARE_REJECT=files-install-not-required") < prefix.indexOf("apt-get -o"));
        assertTrue(prefix.contains("if [ \"$runtime_missing\" -ne 0 ]; then"));
        assertTrue(prefix.contains("[ ! -L \"$1\" ] && [ -f \"$1\" ] && [ -x \"$1\" ]"));
        assertTrue(prefix.contains("= '0:0:755:1'"));
        assertTrue(prefix.contains("PREPARE_REJECT=helper-file-conflict"));
        assertTrue(prefix.contains("PREPARE_REJECT=build-entry-conflict"));
        assertTrue(prefix.contains("PREPARE_REJECT=runtime-command-ownership"));
        assertTrue(prefix.contains("if [ ! -e '" + DeploymentManagedHelperBundle.PATH + "' ]; then"));
        assertTrue(middle.contains("if [ ! -e '" + DeploymentManagedHelperBundle.BUILD_ENTRY_PATH + "' ]; then"));
        assertTrue(middle.indexOf("/usr/bin/ln -T -- \"$helper_tmp\"") < middle.indexOf("/usr/bin/rm -- \"$helper_tmp\""));
        assertTrue(tail.indexOf("/usr/bin/ln -T -- \"$entry_tmp\"") < tail.indexOf("/usr/bin/rm -- \"$entry_tmp\""));
        assertTrue(tail.indexOf("/usr/bin/rm -- \"$entry_tmp\"") < tail.indexOf("files_check\n"));
        assertTrue(tail.indexOf("files_check\n") < tail.indexOf("helper_probe="));
        assertFalse((prefix + middle + tail).contains("/usr/bin/install -o root"));
        assertFalse((prefix + middle + tail).contains("/usr/bin/ln -f"));
        assertTrue(DeploymentHelperInstallerScript.RUNTIME_COMMANDS.containsAll(List.of("ln", "tr", "sqlite3", "systemd-run")));
    }

    @Test
    void filesRuntimeGuardChecksEveryLinkDirectoryAndBoundsTraversalWithoutInvokingRuntime() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)), "A real Bash binary is required");
        Path fixtureDirectory = Files.createDirectory(temporary.resolve("runtime-paths"));
        String fixturePath = fixtureDirectory.toAbsolutePath().toString().replace('\\', '/');
        if (fixturePath.matches("^[A-Za-z]:/.*"))
            fixturePath = "/" + Character.toLowerCase(fixturePath.charAt(0)) + fixturePath.substring(2);
        String rendered = DeploymentHelperInstallerScript.renderFiles();
        String guard = rendered.substring(rendered.indexOf("files_trusted_directory() {"), rendered.indexOf("files_check_one() {"));
        String script = "set -euo pipefail\numask 022\nfixture=" + DeploymentHelperInstallerScript.quote(fixturePath) + "\n" + """
            # Only ownership and ancestors outside this fixture are virtual; fixture links and modes are real.
            fixture_stat() {
              local format="$2" file="$4"
              if [ "$format" = '%u:%g' ]; then printf '0:0\\n'
              elif [[ "$file" = "$fixture"* ]]; then /usr/bin/stat "$@"
              else printf '755\\n'; fi
            }
            """ + guard.replace("/usr/bin/stat", "fixture_stat") + """
            mkdir -p "$fixture/usr/bin" "$fixture/etc/alternatives" "$fixture/srv/public-hop"
            chmod 755 "$fixture" "$fixture/usr" "$fixture/usr/bin" "$fixture/etc" "$fixture/etc/alternatives" "$fixture/srv" "$fixture/srv/public-hop"
            printf '#!/bin/bash\\nprintf executed > "$WTL_EXECUTION_SENTINEL"\\n' > "$fixture/usr/bin/curl.real"
            chmod 755 "$fixture/usr/bin/curl.real"
            export WTL_EXECUTION_SENTINEL="$fixture/executed"
            [ "$(/usr/bin/stat -c '%a' -- "$fixture/usr/bin/curl.real")" = 755 ] || exit 77
            ln -s usr/bin "$fixture/bin" || exit 77
            [ -L "$fixture/bin" ] || exit 77
            ln -s "$fixture/etc/alternatives/curl" "$fixture/usr/bin/curl" || exit 77
            ln -s ../../usr/bin/curl.real "$fixture/etc/alternatives/curl" || exit 77
            [ -L "$fixture/usr/bin/curl" ] && [ -L "$fixture/etc/alternatives/curl" ] || exit 77
            files_trusted_runtime_path "$fixture/bin/curl" || { printf 'MERGED_ALTERNATIVES_REJECTED\\n'; exit 1; }
            [ ! -e "$WTL_EXECUTION_SENTINEL" ] || exit 2
            rm -- "$fixture/usr/bin/curl"
            ln -s ../../srv/public-hop/bridge "$fixture/usr/bin/curl" || exit 77
            ln -s "$fixture/usr/bin/curl.real" "$fixture/srv/public-hop/bridge" || exit 77
            [ -L "$fixture/usr/bin/curl" ] && [ -L "$fixture/srv/public-hop/bridge" ] || exit 77
            files_trusted_runtime_path "$fixture/usr/bin/curl" || { printf 'PROTECTED_HOP_REJECTED\\n'; exit 3; }
            chmod 777 "$fixture/srv/public-hop"
            [ "$(/usr/bin/stat -c '%a' -- "$fixture/srv/public-hop")" = 777 ] || exit 77
            if files_trusted_runtime_path "$fixture/usr/bin/curl"; then printf 'WRITABLE_HOP_ACCEPTED\\n'; exit 4; fi
            [ ! -e "$WTL_EXECUTION_SENTINEL" ] || exit 5
            chmod 755 "$fixture/srv/public-hop"
            files_trusted_runtime_path "$fixture/usr/bin/curl" || exit 6
            for number in {1..41}; do next=$((number + 1)); ln -s "hop$next" "$fixture/hop$number" || exit 77; done
            ln -s usr/bin/curl.real "$fixture/hop42" || exit 77
            [ -L "$fixture/hop1" ] && [ -L "$fixture/hop42" ] || exit 77
            if files_trusted_runtime_path "$fixture/hop1"; then printf 'OVERSIZED_LINK_CHAIN_ACCEPTED\\n'; exit 7; fi
            dot_path=
            for ((number=0; number<513; number++)); do dot_path="$dot_path/."; done
            if files_trusted_runtime_path "$fixture/usr/bin$dot_path/curl.real"; then printf 'OVERSIZED_COMPONENT_CHAIN_ACCEPTED\\n'; exit 8; fi
            [ ! -e "$WTL_EXECUTION_SENTINEL" ] || exit 9
            printf 'TRUSTED_PATH_CASES=PASS\\n'
            """;
        var builder = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true);
        builder.environment().put("MSYS", "winsymlinks:nativestrict");
        Process process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(script.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "Runtime guard fixture timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            org.junit.jupiter.api.Assumptions.assumeTrue(process.exitValue() != 77,
                "Fixture requires actual symbolic links and Unix mode semantics: " + output);
            assertEquals(0, process.exitValue(), output);
            assertEquals("TRUSTED_PATH_CASES=PASS\n", output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void filesInstallUsesItsExactIndependentReviewAndOriginalPostInstallProbe() throws Exception {
        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(ok("package log\n" + installTail(), ""));
        replies.add(ok(probeOutput(), ""));
        var requests = new ArrayList<CommandRequest>();
        var intents = new ArrayList<ActionIntent>();
        var guarded = new java.util.concurrent.atomic.AtomicInteger();
        var helper = new DeploymentManagedHelper(session(replies, requests), reviews(intents),
            new ServerId("Server-Files"), task(), "files-revision", guarded::incrementAndGet);
        assertEquals(TaskResult.SUCCEEDED, helper.installFiles().outcome());
        assertEquals(2, guarded.get());
        assertEquals(List.of("deployment.helper.install.files.v1", "deployment.helper.probe.v1"),
            intents.stream().map(i -> i.semantics().orElseThrow().operation()).toList());
        assertEquals(DeploymentHelperInstallerScript.renderFiles(), new String(requests.getFirst().stdin(), StandardCharsets.UTF_8));
        assertEquals(gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(new ServerId("Server-Files"),
            "files-revision", requests.getFirst()).withSemantics(intents.getFirst().semantics().orElseThrow()), intents.getFirst());
    }

    @Test
    void filesInstallDenialGuardFailureAndUnknownNeverSendOrReplay() throws Exception {
        var requests = new ArrayList<CommandRequest>();
        var interaction = reviews(new ArrayList<>());
        var deniedProblem = new Problem(ProblemCode.ACCESS_DENIED, "FILES_REVIEW_DENIED", "Denied", Map.of(), Optional.empty());
        var denied = new DeploymentManagedHelper(session(new ArrayDeque<>(), requests), interaction,
            new ServerId("files"), task(), "revision", gold.debug.windowstolinux.mod.deployment.linux.command.dispatch.DeploymentSendGuard.NONE,
            (intent, request, original) -> new OperationResult<>(TaskResult.FAILED, Optional.empty(), Optional.of(deniedProblem)));
        var result = denied.installFiles();
        assertEquals(TaskResult.FAILED, result.outcome());
        assertSame(deniedProblem, result.problem().orElseThrow());
        assertTrue(requests.isEmpty());
        var guardFailure = new DeploymentManagedHelper(session(new ArrayDeque<>(), requests), interaction,
            new ServerId("files"), task(), "revision", () -> {
            throw new ApiException(deniedProblem);
        });
        assertSame(deniedProblem, assertThrows(ApiException.class, guardFailure::installFiles).problem());
        assertTrue(requests.isEmpty());
        var unknownProblem = new Problem(ProblemCode.UNKNOWN, "FILES_EFFECT_UNCONFIRMED", "Unconfirmed", Map.of(), Optional.empty());
        for (var response : List.of(new OperationResult<CommandResult>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(unknownProblem)),
            new OperationResult<CommandResult>(TaskResult.CANCELLED, Optional.empty(), Optional.empty()),
            ok("incomplete", ""))) {
            var replies = new ArrayDeque<OperationResult<CommandResult>>();
            replies.add(response);
            var helper = new DeploymentManagedHelper(session(replies, requests), interaction, new ServerId("files"), task(), "revision");
            int before = requests.size();
            assertEquals(response.outcome() == TaskResult.CANCELLED ? TaskResult.CANCELLED : TaskResult.UNKNOWN, helper.installFiles().outcome());
            assertEquals(before + 1, requests.size());
        }
    }

    private static String digest(String script) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(script.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void packagedBundleMatchesItsDigestAndPassesBashSyntax() throws Exception {
        byte[] bytes = DeploymentManagedHelperBundle.renderScript().getBytes(StandardCharsets.UTF_8);
        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        String script = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(script.contains("# @compat:"));
        assertTrue(script.contains("prepare-toolchains)"));
        assertTrue(script.contains("backup-create)"));
        assertTrue(script.contains("agent-open)"));
        assertTrue(script.contains("database-restore-candidate)"));
        assertTrue(script.contains("printf 'HELPER=1\\nPROTOCOL=%s\\n'"));
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        assertTrue(Files.isExecutable(Path.of(bash)), "A real Bash binary is required for this contract test");
        Path file = temporary.resolve("managed-helper");
        Files.write(file, bytes);
        assertBashSyntax(bash, file);
        Path installer = temporary.resolve("managed-installer");
        String installation = DeploymentHelperInstallerScript.render();
        assertTrue(installation.indexOf("installer_trusted_path \"$installer_parent\" directory") < installation.indexOf("/usr/bin/install -d"));
        assertTrue(installation.indexOf("installer_trusted_path \"$installer_file\" file") < installation.indexOf("/usr/bin/mv -fT"));
        assertTrue(installation.contains("(( (8#$installer_mode & 022) == 0 ))"));
        assertTrue(installation.contains("acl sudo iproute2 sqlite3\n"));
        assertTrue(installation.contains("acl sudo iproute sqlite\n"));
        Files.writeString(installer, installation);
        assertBashSyntax(bash, installer);
    }

    @Test
    void trackedInstallerPersistsOriginalIdentityBeforePackagesAndQueriesWithoutHelper() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        var task = UUID.fromString("12345678-1234-1234-1234-123456789012");
        var operation = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        String serverDigest = "a".repeat(64), bodyDigest = "b".repeat(64);
        String script = DeploymentHelperInstallerScript.renderTracked(task, operation, serverDigest, bodyDigest);
        String status = DeploymentHelperInstallerScript.renderStatus(operation);
        assertTrue(script.indexOf("PHASE=STARTED") < script.indexOf("apt-get"));
        assertTrue(script.indexOf("PHASE=SUCCEEDED") < script.lastIndexOf("HELPER_INSTALL=1"));
        assertTrue(script.contains("bootstrap_trusted_directory /var/lib"));
        assertTrue(script.contains("set -C; printf"));
        assertTrue(status.contains("/usr/bin/cat -- \"$bootstrap_receipt\""));
        assertFalse(status.contains("python"));
        assertFalse(status.contains("install -d"));
        assertFalse(status.contains("managed-helper'"));
        assertTrue(DeploymentHelperInstallerScript.receipt(task, operation, serverDigest, bodyDigest, "SUCCEEDED")
            .contains("HELPER_SHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256));
        Path installer = temporary.resolve("tracked-installer");
        Path query = temporary.resolve("tracked-status");
        Files.writeString(installer, script);
        Files.writeString(query, status);
        assertBashSyntax(bash, installer);
        assertBashSyntax(bash, query);
    }

    private static void assertBashSyntax(String bash, Path file) throws Exception {
        Process process = new ProcessBuilder(bash, "--noprofile", "--norc", "-n", file.toString()).start();
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(),
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void exactApprovedInstallThenProbeAcceptsOnlyThePackagedIdentity() throws Exception {
        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(ok("package log\n" + installTail(), "Created symlink\n"));
        replies.add(ok(probeOutput(), ""));
        var requests = new ArrayList<CommandRequest>();
        var intents = new ArrayList<ActionIntent>();
        var helper = new DeploymentManagedHelper(session(replies, requests), reviews(intents),
            new ServerId("Server-Exact"), task(), "reviewed-plan-1");
        var result = helper.install();
        assertEquals(TaskResult.SUCCEEDED, result.outcome());
        assertEquals(DeploymentManagedHelperBundle.EXPECTED_SHA256, result.value().orElseThrow().sha256());
        assertEquals(2, requests.size());
        assertEquals(List.of("deployment.helper.install.v1", "deployment.helper.probe.v1"),
            intents.stream().map(i -> i.semantics().orElseThrow().operation()).toList());
        assertEquals("Server-Exact", intents.getFirst().serverIds().getFirst());
        assertEquals(java.util.Set.of(
            new gold.debug.windowstolinux.appapi.task.action.ActionTarget("Server-Exact", "deployment-helper", DeploymentManagedHelperBundle.PATH),
            new gold.debug.windowstolinux.appapi.task.action.ActionTarget("Server-Exact", "file", DeploymentManagedHelperBundle.BUILD_ENTRY_PATH),
            new gold.debug.windowstolinux.appapi.task.action.ActionTarget("Server-Exact", "file", DeploymentRemoteLayoutAdmission.UNIT_PATH)),
            intents.getFirst().semantics().orElseThrow().targets());
        assertEquals(java.util.Set.of(new gold.debug.windowstolinux.appapi.task.action.ActionTarget("Server-Exact", "deployment-helper", DeploymentManagedHelperBundle.PATH)),
            intents.getLast().semantics().orElseThrow().targets());
        String installer = new String(requests.getFirst().stdin(), StandardCharsets.UTF_8);
        assertTrue(installer.contains("[ \"$(id -u)\" -eq 0 ] || exit 64"));
        assertTrue(installer.contains("/usr/bin/sha256sum --check --status"));
        assertTrue(installer.contains("PREPARE_REJECT=host-layout-conflict"));
        assertTrue(installer.endsWith("printf 'HELPER_INSTALL=1\\nPROTOCOL=16\\nSHA256="
            + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\\n'\n"));
    }

    @Test
    void installedEntryDigestStopsBeforeUnitRegistrationWhenTheActualFileDiffers() throws Exception {
        String script = DeploymentHelperInstallerScript.renderHelperInstallation();
        String binding = DeploymentHelperInstallerScript.quote(DeploymentManagedHelperBundle.buildEntrySha256()) + " "
            + DeploymentHelperInstallerScript.quote(DeploymentManagedHelperBundle.BUILD_ENTRY_PATH) + " | /usr/bin/sha256sum --check --status";
        int bound = script.indexOf(binding);
        assertTrue(bound >= 0);
        String check = script.substring(script.lastIndexOf("printf ", bound), script.indexOf('\n', bound));
        assertTrue(script.indexOf(check) > script.indexOf("/usr/bin/sha256sum --check --status"));
        assertTrue(script.indexOf(check) < script.indexOf("systemctl enable windowstolinux-workspace-recovery.service"));
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        Path entry = temporary.resolve("installed-entry");
        String entryPath = entry.toAbsolutePath().toString().replace('\\', '/');
        if (entryPath.matches("^[A-Za-z]:/.*"))
            entryPath = "/" + Character.toLowerCase(entryPath.charAt(0)) + entryPath.substring(2);
        String localCheck = check.replace(DeploymentHelperInstallerScript.quote(DeploymentManagedHelperBundle.BUILD_ENTRY_PATH),
            DeploymentHelperInstallerScript.quote(entryPath));
        Path command = temporary.resolve("entry-check.sh");
        Files.writeString(command, "set -euo pipefail\n" + localCheck + "\nprintf 'UNIT_REGISTRATION_REACHED\n'\n");
        for (boolean changed : List.of(false, true)) {
            Files.writeString(entry, DeploymentManagedHelperBundle.renderBuildEntry() + (changed ? "# changed\n" : ""));
            Process process = new ProcessBuilder(bash, "--noprofile", "--norc", command.toString()).start();
            try {
                assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
                if (changed) assertNotEquals(0, process.exitValue());
                else assertEquals(0, process.exitValue());
                assertEquals(changed ? "" : "UNIT_REGISTRATION_REACHED\n", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void trackedInstallRetainsItsOriginalTargetAndExactCommandBinding() throws Exception {
        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(ok("tracked receipt", ""));
        var requests = new ArrayList<CommandRequest>();
        var intents = new ArrayList<ActionIntent>();
        var server = new ServerId("Server-Exact");
        var original = task();
        var operation = UUID.randomUUID();
        String body = DeploymentHelperInstallerScript.renderTracked(original.id(), operation, "a".repeat(64),
            digest(DeploymentHelperInstallerScript.render()));
        var helper = new DeploymentManagedHelper(session(replies, requests), reviews(intents), server, original, "tracked-revision");
        assertEquals(TaskResult.SUCCEEDED, helper.installTracked(body.getBytes(StandardCharsets.UTF_8), () -> {
        }).outcome());
        assertEquals(1, intents.size());
        var actual = intents.getFirst();
        assertEquals("deployment.helper.install.tracked.v1", actual.semantics().orElseThrow().operation());
        assertEquals(gold.debug.windowstolinux.appapi.linux.action.RemoteActions.command(server, "tracked-revision", requests.getFirst())
            .withSemantics(actual.semantics().orElseThrow()), actual);
        assertEquals(DeploymentManagedHelper.trackedInstallationTargets(server,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout.original(), operation),
            actual.semantics().orElseThrow().targets());
        assertTrue(actual.semantics().orElseThrow().targets().contains(
            new gold.debug.windowstolinux.appapi.task.action.ActionTarget(server.id(), "file",
                DeploymentHelperInstallerScript.RECEIPT_DIRECTORY + "/" + operation + ".receipt")));
    }

    @Test
    void trackedInstallerRejectsForeignOrAlteredReceiptsBeforeReviewDispatchAndEffects() throws Exception {
        var requests = new ArrayList<CommandRequest>();
        var intents = new ArrayList<ActionIntent>();
        var helper = new DeploymentManagedHelper(session(new ArrayDeque<>(), requests), reviews(intents),
            new ServerId("original"), task(), "revision");
        String digest = digest(DeploymentHelperInstallerScript.render());
        String valid = DeploymentHelperInstallerScript.renderTracked(task().id(), UUID.randomUUID(), "a".repeat(64), digest);
        var dispatched = new java.util.concurrent.atomic.AtomicInteger();
        for (String changed : List.of(valid + "printf extra\\n\n", valid.replace("BODY_SHA256=" + digest, "BODY_SHA256=" + "b".repeat(64)),
                DeploymentHelperInstallerScript.renderTracked(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), digest)))
            assertThrows(IllegalArgumentException.class,
                () -> helper.installTracked(changed.getBytes(StandardCharsets.UTF_8), dispatched::incrementAndGet));
        assertEquals(0, dispatched.get());
        assertTrue(intents.isEmpty());
        assertTrue(requests.isEmpty());
    }

    @Test
    void malformedProbeAndUnconfirmedInstallNeverCauseReplayOrSuccess() throws Exception {
        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(ok("FORGED=1\n" + probeOutput(), ""));
        var requests = new ArrayList<CommandRequest>();
        var helper = new DeploymentManagedHelper(session(replies, requests), reviews(new ArrayList<>()),
            new ServerId("exact"), task(), "revision");
        assertEquals(TaskResult.FAILED, helper.probe().outcome());
        assertEquals(1, requests.size());

        replies.add(
            new OperationResult<>(TaskResult.UNKNOWN, Optional.empty(), Optional.of(new Problem(ProblemCode.UNKNOWN,
                "SSH_COMMAND_EXIT_UNKNOWN", "Unconfirmed", Map.of(), Optional.empty()))));
        assertEquals(TaskResult.UNKNOWN, helper.install().outcome());
        assertEquals(2, requests.size());
    }

    @Test
    void platformPrerequisiteCheckStopsBeforeHelperButIdentityProbeRemainsIndependent() throws Exception {
        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(ok(probeOutput(), ""));
        replies.add(ok(probeOutput(), ""));
        var requests = new ArrayList<CommandRequest>();
        var helper = new DeploymentManagedHelper(session(replies, requests), reviews(new ArrayList<>()),
            new ServerId("exact"), task(), "revision");
        assertEquals(TaskResult.SUCCEEDED, helper.probePlatform().outcome());
        assertEquals(TaskResult.SUCCEEDED, helper.probe().outcome());
        String guard = requests.getFirst().script().lines().filter(line -> line.contains("/usr/bin/sqlite3"))
            .findFirst().orElseThrow();
        assertTrue(requests.getFirst().script().indexOf(guard) < requests.getFirst().script().indexOf("managed-helper' probe"));
        assertFalse(requests.getLast().script().contains("sqlite3"), "Identity and recovery do not need database tools");
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        assertTrue(Files.isExecutable(Path.of(bash)));
        var paths = List.of(temporary.resolve("missing-client"), temporary, Path.of(bash).toAbsolutePath());
        for (int index = 0; index < paths.size(); index++) {
            String path = paths.get(index).toString().replace('\\', '/');
            if (path.matches("^[A-Za-z]:/.*")) path = "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2);
            String quoted = "'" + path.replace("'", "'\\''") + "'";
            Path script = temporary.resolve("platform-check-" + index + ".sh");
            Files.writeString(script, "set -euo pipefail\n" + guard.replace("/usr/bin/sqlite3", quoted)
                + "\nprintf 'AFTER_CHECK\\n'\n");
            Process process = new ProcessBuilder(bash, "--noprofile", "--norc", script.toString()).start();
            try {
                assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(index == 2 ? 0 : 3, process.exitValue(),
                    new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(index == 2 ? "AFTER_CHECK\n" : "",
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void approvalCancellationAndConfirmedPreDispatchCancellationDoNotInstall() throws Exception {
        var requests = new ArrayList<CommandRequest>();
        var denied = new InteractionService() {
            @Override
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) {
                return new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty());
            }

            @Override
            public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task) {
                throw new AssertionError("No text input expected");
            }
        };
        var helper = new DeploymentManagedHelper(session(new ArrayDeque<>(), requests), denied, new ServerId("exact"),
            task(), "revision");
        assertEquals(TaskResult.CANCELLED, helper.install().outcome());
        assertTrue(requests.isEmpty());

        var replies = new ArrayDeque<OperationResult<CommandResult>>();
        replies.add(new OperationResult<>(TaskResult.CANCELLED, Optional.empty(), Optional.empty()));
        helper = new DeploymentManagedHelper(session(replies, requests), reviews(new ArrayList<>()),
            new ServerId("exact"), task(), "revision");
        assertEquals(TaskResult.CANCELLED, helper.install().outcome());
        assertEquals(1, requests.size());
    }

    @Test
    void incompleteInstallAndPostInstallProbeNeverCauseASecondInstall() throws Exception {
        var badInstall = List.of(new CommandResult(Optional.of(0), installTail(), "", true),
            new CommandResult(Optional.empty(), installTail(), "", false),
            new CommandResult(Optional.of(0), "incomplete", "", false));
        for (var result : badInstall) {
            var replies = new ArrayDeque<OperationResult<CommandResult>>();
            replies.add(new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(result), Optional.empty()));
            var requests = new ArrayList<CommandRequest>();
            var helper = new DeploymentManagedHelper(session(replies, requests), reviews(new ArrayList<>()),
                new ServerId("exact"), task(), "revision");
            assertEquals(TaskResult.UNKNOWN, helper.install().outcome());
            assertEquals(1, requests.size());
        }
        for (var later : List.of(
            new OperationResult<CommandResult>(TaskResult.CANCELLED, Optional.empty(), Optional.empty()),
            ok("PROTOCOL=9\n", ""))) {
            var replies = new ArrayDeque<OperationResult<CommandResult>>();
            replies.add(ok(installTail(), "Package warning\n"));
            replies.add(later);
            var requests = new ArrayList<CommandRequest>();
            var helper = new DeploymentManagedHelper(session(replies, requests), reviews(new ArrayList<>()),
                new ServerId("exact"), task(), "revision");
            assertEquals(TaskResult.UNKNOWN, helper.install().outcome());
            assertEquals(2, requests.size());
        }
    }

    @Test
    void hostAdmissionDenialKeepsItsOriginalProblemAndNeverProbes() {
        var denied = new Problem(ProblemCode.ACCESS_DENIED, "REMOTE_GRANT_REJECTED", "Grant rejected", Map.of(),
            Optional.empty());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        RemoteSession session = (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("execute")) {
                    calls.incrementAndGet();
                    throw new ApiException(denied);
                }
                throw new AssertionError(method.getName());
            });
        var helper = new DeploymentManagedHelper(session, reviews(new ArrayList<>()), new ServerId("exact"), task(),
            "revision");
        ApiException failure = assertThrows(ApiException.class, helper::install);
        assertSame(denied, failure.problem());
        assertEquals(1, calls.get());
    }

    private static TaskContext task() {
        return (TaskContext) Proxy.newProxyInstance(TaskContext.class.getClassLoader(),
            new Class<?>[]{TaskContext.class}, (proxy, method, args) -> {
                if (method.getName().equals("id"))
                    return UUID.fromString("12345678-1234-1234-1234-123456789012");
                if (method.getName().equals("cancelToken"))
                    return (gold.debug.windowstolinux.appapi.task.run.CancelToken) () -> false;
                throw new AssertionError(method.getName());
            });
    }

    private static InteractionService reviews(List<ActionIntent> intents) {
        return new InteractionService() {
            @Override
            public OperationResult<ApprovalGrant> approve(ActionIntent intent, TaskContext task) {
                intents.add(intent);
                return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new ApprovalGrant(UUID.randomUUID())),
                    Optional.empty());
            }

            @Override
            public OperationResult<String> requestText(String title, String prompt, boolean secret, TaskContext task) {
                throw new AssertionError("No input expected");
            }
        };
    }

    private static RemoteSession session(ArrayDeque<OperationResult<CommandResult>> replies,
                                         List<CommandRequest> requests) {
        return (RemoteSession) Proxy.newProxyInstance(RemoteSession.class.getClassLoader(),
            new Class<?>[]{RemoteSession.class}, (proxy, method, args) -> {
                if (method.getName().equals("execute")) {
                    requests.add(((CommandRequest) args[0]).snapshot());
                    return replies.removeFirst();
                }
                if (method.getName().equals("close"))
                    throw new AssertionError("Borrowed session must remain caller-owned");
                throw new AssertionError(method.getName());
            });
    }

    private static OperationResult<CommandResult> ok(String out, String err) {
        return new OperationResult<>(TaskResult.SUCCEEDED, Optional.of(new CommandResult(Optional.of(0), out, err, false)),
            Optional.empty());
    }

    private static String installTail() {
        return "HELPER_INSTALL=1\nPROTOCOL=16\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
    }

    private static String probeOutput() {
        return "HELPER=1\nPROTOCOL=16\nSHA256=" + DeploymentManagedHelperBundle.EXPECTED_SHA256 + "\n";
    }
}
