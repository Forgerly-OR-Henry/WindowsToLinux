package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentHelperInstallerContractTest {
    private static final String UNTRUSTED_PRIOR = "#!/bin/sh\n# untrusted prior bytes\n";
    private static final String CHANGED_PRIOR = "#!/bin/sh\n# changed after admission\n";
    private static final String LEGACY_HELPER = "#!/bin/sh\n# fixture managed helper from a known version\n";
    private static final String LEGACY_ENTRY = "#!/bin/sh\n# fixture build entry from a known version\n";
    private static final String LEGACY_HELPER_DIGEST = "1a5970444c392ca120874bb39c8e6c0542e088b1d9a186d15affb67b05654836";
    private static final String LEGACY_ENTRY_DIGEST = "6eb9b24576e51e318044f67b1c77fc34b41c4c979cadfec1a5eb799c5122a91a";
    private Path temporary;

    @BeforeEach
    void createTaskFixtureDirectory() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.isDirectory(root.resolve(".ai-workspace"))) root = root.getParent();
        assertNotNull(root, "Project task workspace unavailable");
        temporary = Files.createDirectories(root.resolve(
            ".ai-workspace/tmp/deployment-mirrors-20261104/installer-contract/junit-" + UUID.randomUUID()));
    }

    @AfterEach
    void removeTaskFixtureDirectory() throws Exception {
        if (temporary == null) return;
        assertFalse(Files.isSymbolicLink(temporary), "Fixture root must remain task-owned");
        try (var files = Files.walk(temporary)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    @Test
    void trackedDigestsNameTheirExactDifferentByteScopesAndRejectFalseReceiptBindings() throws Exception {
        var layout = DeploymentRemoteLayout.original();
        String body = DeploymentHelperInstallerScript.render(layout);
        String tracked = DeploymentHelperInstallerScript.renderTracked(UUID.randomUUID(), UUID.randomUUID(),
            "a".repeat(64), sha(body), layout);
        String request = "# UTF-8 范围\n" + tracked + "# 额外输入\n";
        var json = new ObjectMapper();
        var evidence = json.valueToTree(DeploymentInstallerEvidence.describe(request, layout));
        var scopes = evidence.path("digestScopes");
        assertEquals(sha(request), scopes.at("/requestDigest/value").asText());
        assertEquals(request.getBytes(StandardCharsets.UTF_8).length, scopes.at("/requestDigest/utf8Bytes").asInt());
        assertEquals(sha(body), scopes.at("/bodyDigest/value").asText());
        assertEquals(sha(DeploymentManagedHelperBundle.renderScript(layout)), scopes.at("/helperDigest/value").asText());
        assertNotEquals(scopes.at("/requestDigest/value"), scopes.at("/bodyDigest/value"));
        assertNotEquals(scopes.at("/helperDigest/value"), scopes.at("/bodyDigest/value"));
        byte[] input = request.getBytes(StandardCharsets.UTF_8);
        var reconstructed = new java.io.ByteArrayOutputStream();
        for (var range : scopes.at("/bodyDigest/requestByteRanges")) {
            int start = range.path("startInclusive").asInt(), end = range.path("endExclusive").asInt();
            reconstructed.write(input, start, end - start);
        }
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), reconstructed.toByteArray());
        assertTrue(body.contains(DeploymentHelperInstallerScript.BODY_AFTER_RECEIPT));
        assertTrue(new String(reconstructed.toByteArray(), StandardCharsets.UTF_8)
            .contains("installer_prior_file() {"), "Pre-receipt guards must remain inside the body digest ranges");
        assertTrue(new String(reconstructed.toByteArray(), StandardCharsets.UTF_8)
            .contains("/usr/bin/flock -x \"$installer_lock\""), "The original lock must remain inside the body digest ranges");
        DeploymentInstallerEvidence.verify(evidence, request);
        var changed = evidence.deepCopy();
        ((ObjectNode) changed.at("/digestScopes/requestDigest")).put("value", sha(body));
        assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.verify(changed, request));
        var changedBody = evidence.deepCopy();
        ((ObjectNode) changedBody.at("/digestScopes/bodyDigest")).put("value", sha(request));
        assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.verify(changedBody, request));
        assertThrows(IllegalArgumentException.class, () -> DeploymentInstallerEvidence.describe(
            tracked.replace("BODY_SHA256=" + sha(body), "BODY_SHA256=" + "b".repeat(64)), layout));
    }

    @Test
    void trackedOperationAcceptsOnlyTheOriginalExactTaskBodyAndReceipt() throws Exception {
        var layout = DeploymentRemoteLayout.original();
        var task = UUID.randomUUID();
        var operation = UUID.randomUUID();
        String bodyDigest = sha(DeploymentHelperInstallerScript.render());
        String tracked = DeploymentHelperInstallerScript.renderTracked(task, operation, "a".repeat(64), bodyDigest, layout);
        assertEquals(operation, DeploymentHelperInstallerScript.trackedOperation(tracked, task, layout));
        for (String changed : java.util.List.of(tracked + "printf extra\\n\n",
                tracked.replace("BODY_SHA256=" + bodyDigest, "BODY_SHA256=" + "b".repeat(64)),
                tracked.replace("PHASE=SUCCEEDED", "PHASE=STARTED")))
            assertThrows(IllegalArgumentException.class, () -> DeploymentHelperInstallerScript.trackedOperation(changed, task, layout));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentHelperInstallerScript.trackedOperation(tracked, UUID.randomUUID(), layout));
    }

    @ParameterizedTest
    @ValueSource(strings = {"helper", "entry"})
    void eitherCorruptPayloadLeavesBothExistingExecutablesAndSuccessReceiptUntouched(String damaged) throws Exception {
        var fixture = fixture(false, damaged);
        var result = execute(fixture.script());
        assertEquals(64, result.exit(), result.output());
        assertEquals(DeploymentManagedHelperBundle.renderScript(), Files.readString(temporary.resolve("helper/managed-helper")));
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(), Files.readString(temporary.resolve("helper/build-entry")));
        assertFalse(Files.exists(temporary.resolve("effects")), "No publication, service or probe may follow a corrupt payload");
        String receipt = Files.readString(fixture.receipt());
        assertTrue(receipt.endsWith("PHASE=STARTED\n"));
        try (var files = Files.list(temporary.resolve("helper"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".")), "Temporary payloads must be cleaned");
        }
    }

    @Test
    void publishedFilesAreVerifiedAndProbeFailureCannotWriteSuccessReceipt() throws Exception {
        var fixture = fixture(true, "none", "absent", "none");
        var result = execute(fixture.script());
        assertEquals(29, result.exit(), result.output());
        assertEquals(DeploymentManagedHelperBundle.renderScript(), Files.readString(temporary.resolve("helper/managed-helper")));
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(), Files.readString(temporary.resolve("helper/build-entry")));
        String effects = Files.readString(temporary.resolve("effects"));
        assertEquals("publish\npublish\ndaemon-reload\nprobe\n", effects);
        assertTrue(Files.readString(fixture.receipt()).endsWith("PHASE=STARTED\n"));
    }

    @Test
    void successfulPublicationHoldsItsDirectoryLockUntilTheFinalReceipt() throws Exception {
        var fixture = fixture(false, "none", "absent", "none");
        var result = execute(fixture.script());
        assertEquals(0, result.exit(), result.output());
        assertTrue(Files.readString(fixture.receipt()).endsWith("PHASE=SUCCEEDED\n"));
        assertEquals("publish\npublish\ndaemon-reload\nprobe\nreceipt\n", Files.readString(temporary.resolve("effects")));
        String installer = DeploymentHelperInstallerScript.renderHelperInstallation()
            .replace(DeploymentHelperInstallerScript.quote(DeploymentManagedHelperBundle.renderScript()), "'<helper source>'")
            .replace(DeploymentHelperInstallerScript.quote(DeploymentManagedHelperBundle.renderBuildEntry()), "'<build entry source>'");
        assertTrue(installer.indexOf("installer_lock_trusted_directory \"$installer_lock_directory\"")
            < installer.indexOf("exec {installer_lock}< \"$installer_lock_directory\""));
        assertTrue(installer.indexOf("/usr/bin/flock -x \"$installer_lock\"")
            < installer.indexOf("helper_tmp=$"));
        assertFalse(installer.contains("flock -u"));
        assertFalse(installer.contains("exec {installer_lock}<&-"));
        assertTrue(installer.indexOf("installer_verify_executable \"$entry_tmp\"")
            < installer.indexOf("installer_publish_executable \"$entry_tmp\""));
        assertTrue(installer.lastIndexOf("PREPARE_REJECT=build-entry-installed-digest") < installer.indexOf("helper_probe="));
    }

    @Test
    void absentExecutablesPermitCleanInstallation() throws Exception {
        var fixture = fixture(false, "none", "absent", "none");
        var result = execute(fixture.script());
        assertEquals(0, result.exit(), result.output());
        assertEquals(DeploymentManagedHelperBundle.renderScript(), Files.readString(temporary.resolve("helper/managed-helper")));
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(), Files.readString(temporary.resolve("helper/build-entry")));
        assertTrue(Files.readString(fixture.receipt()).endsWith("PHASE=SUCCEEDED\n"));
    }

    @Test
    void currentExecutablesAreVerifiedWithoutReplacement() throws Exception {
        var fixture = fixture(false, "none");
        var result = execute(fixture.script());
        assertEquals(0, result.exit(), result.output());
        assertEquals("daemon-reload\nprobe\nreceipt\n", Files.readString(temporary.resolve("effects")));
    }

    @Test
    void knownLegacyUpgradeCanFinishWithANewReceiptAfterTheFirstFileWasPublished() throws Exception {
        var interrupted = fixture(false, "none", "legacy", "before-helper");
        var first = execute(interrupted.script());
        assertEquals(29, first.exit(), first.output());
        assertEquals(LEGACY_HELPER, Files.readString(temporary.resolve("helper/managed-helper")));
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(), Files.readString(temporary.resolve("helper/build-entry")));
        assertTrue(Files.readString(interrupted.receipt()).endsWith("PHASE=STARTED\n"));
        assertEquals("publish\n", Files.readString(temporary.resolve("effects")));

        var retry = fixture(false, "none", "retain", "none");
        var second = execute(retry.script());
        assertEquals(0, second.exit(), second.output());
        assertEquals(DeploymentManagedHelperBundle.renderScript(), Files.readString(temporary.resolve("helper/managed-helper")));
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(), Files.readString(temporary.resolve("helper/build-entry")));
        assertTrue(Files.readString(interrupted.receipt()).endsWith("PHASE=STARTED\n"));
        assertTrue(Files.readString(retry.receipt()).endsWith("PHASE=SUCCEEDED\n"));
        assertEquals("publish\npublish\ndaemon-reload\nprobe\nreceipt\n", Files.readString(temporary.resolve("effects")));
    }

    @Test
    void originallyAbsentTargetIsNotOverwrittenIfItAppearsDuringPublication() throws Exception {
        var fixture = fixture(false, "none", "absent", "appeared");
        var result = execute(fixture.script());
        assertNotEquals(0, result.exit(), result.output());
        assertEquals(CHANGED_PRIOR, Files.readString(temporary.resolve("helper/build-entry")));
        assertFalse(Files.exists(temporary.resolve("helper/managed-helper")));
        assertTrue(Files.readString(fixture.receipt()).endsWith("PHASE=STARTED\n"));
    }

    @Test
    void existingBootUnitOnlyReportsCandidatesAndDoesNotCreateAnAbsentWorkRoot() throws Exception {
        Path work = temporary.resolve("work");
        String resource = "/gold/debug/windowstolinux/mod/deployment/linux/helper/workspace/25-workspace-recovery.sh";
        String recovery;
        try (var source = java.util.Objects.requireNonNull(getClass().getResourceAsStream(resource))) {
            recovery = new String(source.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        String script = "set -euo pipefail\nwork_root=" + DeploymentHelperInstallerScript.quote(bashPath(work)) + "\n" + """
            reject() { exit 64; }
            assert_storage_parent() { [ "$1" = "$work_root" ]; }
            assert_root_owned_directory() { [ "$1" = "$work_root" ] && [ -d "$1" ]; }
            assert_candidate_for_deployer() { [ -f "$1/.deployer" ]; }
            initialise_controlled_roots() { printf 'unexpected initialization\n'; exit 90; }
            cleanup_candidate() { printf 'unexpected cleanup\n'; exit 91; }
            """ + recovery + "\nrecover_workspaces\n";
        var absent = execute(script);
        assertEquals(0, absent.exit(), absent.output());
        assertFalse(Files.exists(work));

        Path retained = Files.createDirectories(work.resolve("demo-0123456789abcdef"));
        Path quarantined = Files.createDirectory(work.resolve("other-0123456789abcdef"));
        Files.writeString(retained.resolve(".deployer"), "root\n");
        Files.writeString(retained.resolve("candidate-data"), "keep\n");
        var observed = execute(script);
        assertEquals(1, observed.exit(), observed.output());
        assertTrue(observed.output().contains("WORKSPACE_RETAINED=demo-0123456789abcdef"));
        assertTrue(observed.output().contains("WORKSPACE_QUARANTINED=other-0123456789abcdef"));
        assertEquals("keep\n", Files.readString(retained.resolve("candidate-data")));
        assertTrue(Files.isDirectory(quarantined));
    }

    @ParameterizedTest
    @ValueSource(strings = {"helper", "entry", "metadata", "wrong-target"})
    void untrustedPriorExecutableRejectsBeforeLayoutPackagesOrReceipt(String prior) throws Exception {
        var fixture = fixture(false, "none", prior, "none");
        var result = execute(fixture.script());
        assertEquals(64, result.exit(), result.output());
        boolean entry = prior.equals("entry");
        assertTrue(result.output().contains(prior.equals("metadata") ? "helper-prior-file-identity"
            : (entry ? "build-entry" : "helper") + "-prior-content-untrusted"), result.output());
        assertFalse(Files.exists(temporary.resolve("preflight-effects")), "No layout or package effects before admission");
        assertFalse(Files.exists(temporary.resolve("effects")), "No publication or service effects before admission");
        assertFalse(Files.exists(fixture.receipt()), "Rejected prior files must not create a STARTED receipt");
        if (!prior.equals("metadata")) assertEquals(prior.equals("wrong-target") ? LEGACY_ENTRY : UNTRUSTED_PRIOR,
            Files.readString(temporary.resolve(entry ? "helper/build-entry" : "helper/managed-helper")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lock", "staging", "staging-known"})
    void changedPriorBytesAreRecheckedUnderLockAndBeforePublication(String mutation) throws Exception {
        var fixture = fixture(false, "none", "same", mutation);
        var result = execute(fixture.script());
        assertEquals(64, result.exit(), result.output());
        boolean known = mutation.equals("staging-known");
        assertTrue(result.output().contains(known ? "installer-prior-changed" : "helper-prior-content-untrusted"), result.output());
        assertEquals(known ? LEGACY_HELPER : CHANGED_PRIOR, Files.readString(temporary.resolve("helper/managed-helper")));
        assertFalse(Files.exists(temporary.resolve("effects")), "No replacement may follow a failed locked recheck");
        if (mutation.equals("lock")) {
            assertFalse(Files.exists(temporary.resolve("preflight-effects")));
            assertFalse(Files.exists(fixture.receipt()));
        } else {
            assertTrue(Files.exists(temporary.resolve("preflight-effects")), "Earlier admitted layout/package effects are not rolled back");
            assertTrue(Files.readString(fixture.receipt()).endsWith("PHASE=STARTED\n"));
        }
    }

    @Test
    void allInstallerEntrypointsCheckPriorBytesBeforeTheirFirstSideEffect() {
        var layout = DeploymentRemoteLayout.original();
        for (String script : java.util.List.of(DeploymentHelperInstallerScript.render(layout),
                DeploymentHelperInstallerScript.renderFiles(layout), DeploymentHelperInstallerScript.renderHelperInstallation(layout))) {
            int check = script.indexOf("\ninstaller_prior_files\n");
            int lock = script.indexOf("prepare_stage=installer-lock");
            int initialization = script.indexOf(DeploymentRemoteLayoutAdmission.initialize(layout));
            assertTrue(check >= 0 && check < lock && lock < initialization);
            String underLock = script.substring(lock, initialization);
            assertTrue(underLock.contains("\ninstaller_prior_files\n") || underLock.contains("\ninstaller_capture_prior\n"));
            assertTrue(script.indexOf(DeploymentManagedHelperBundle.helperSha256(layout)) < check);
            assertTrue(script.indexOf(DeploymentManagedHelperBundle.buildEntrySha256(layout)) < check);
        }
        assertFalse(DeploymentHelperInstallerScript.renderFiles(layout).contains("helper:" + LEGACY_HELPER_DIGEST));
        String previousRelease = "helper:d10a316ac0078b4dabb286409e12845376d719578c9e2c029686deaf369cc3b4";
        assertTrue(DeploymentHelperInstallerScript.render(layout).contains(previousRelease));
        assertFalse(DeploymentHelperInstallerScript.renderFiles(layout).contains(previousRelease));
    }

    private Fixture fixture(boolean probeFailure, String damaged) throws Exception {
        return fixture(probeFailure, damaged, "same", "none");
    }

    private Fixture fixture(boolean probeFailure, String damaged, String prior, String mutation) throws Exception {
        var layout = DeploymentRemoteLayout.original();
        var operation = UUID.randomUUID();
        String original = DeploymentHelperInstallerScript.renderTracked(UUID.randomUUID(), operation, "a".repeat(64),
            sha(DeploymentHelperInstallerScript.render()), layout);
        assertTrue(original.contains("else command apt-get \"$@\";"),
            "The production legacy package entry must bypass shell functions");
        var evidence = new ObjectMapper().valueToTree(DeploymentInstallerEvidence.describe(original));
        String base = bashPath(temporary);
        StringBuilder mapped = new StringBuilder();
        for (var segment : evidence.path("segments")) {
            if (segment.path("kind").asText().equals("SHELL_SINGLE_QUOTE")) {
                String name = segment.path("source").asText();
                boolean corrupt = damaged.equals(name.equals("managedHelper") ? "helper" : "entry");
                mapped.append(DeploymentHelperInstallerScript.quote(corrupt ? "corrupted payload" : evidence.path("sources").path(name).asText()));
            } else {
                String text = segment.path("text").asText()
                    .replace(DeploymentRemoteLayoutAdmission.initialize(layout), "fixture_layout\n")
                    .replace(layout.paths().helperDirectory(), base + "/helper")
                    .replace(layout.paths().stateRoot(), base + "/state")
                    .replace("/usr/bin/stat", "fixture_stat")
                    .replace("/usr/bin/chown", "fixture_chown")
                    .replace("/usr/bin/chmod", "fixture_chmod")
                    .replace("/usr/bin/install", "fixture_install")
                    .replace("/usr/bin/sync", "fixture_sync")
                    .replace("/usr/bin/flock", "fixture_flock")
                    .replace("/usr/bin/mv", "fixture_mv")
                    .replace("command -v apt-get", "command -v fixture_packages")
                    .replace("command apt-get \"$@\"", "fixture_packages \"$@\"")
                    .replace("command dnf \"$@\"", "fixture_packages \"$@\"")
                    .replace("helper_probe=\"$('" + base + "/helper/managed-helper' probe)\"", "helper_probe=\"$(fixture_probe)\"")
                    .replace("helper:" + LEGACY_HELPER_DIGEST, "helper:" + sha(LEGACY_HELPER))
                    .replace("build-entry:" + LEGACY_ENTRY_DIGEST, "build-entry:" + sha(LEGACY_ENTRY))
                    .replace(DeploymentHelperInstallerScript.renderSecurityObservationFunctions(), "")
                    .replace(DeploymentHelperInstallerScript.renderSecurityInvariant(), "");
                mapped.append(text);
            }
        }
        Files.createDirectories(temporary.resolve("helper"));
        if (!prior.equals("absent") && !prior.equals("retain")) {
            Files.writeString(temporary.resolve("helper/managed-helper"), switch (prior) {
                case "helper" -> UNTRUSTED_PRIOR;
                case "legacy" -> LEGACY_HELPER;
                case "wrong-target" -> LEGACY_ENTRY;
                default -> DeploymentManagedHelperBundle.renderScript();
            });
            Files.writeString(temporary.resolve("helper/build-entry"), switch (prior) {
                case "entry" -> UNTRUSTED_PRIOR;
                case "legacy" -> LEGACY_ENTRY;
                default -> DeploymentManagedHelperBundle.renderBuildEntry();
            });
        }
        String hooks = "fixture=" + DeploymentHelperInstallerScript.quote(base) + "\nfixture_prior="
            + DeploymentHelperInstallerScript.quote(prior) + "\nfixture_mutation=" + DeploymentHelperInstallerScript.quote(mutation) + "\n" + """
            for file in "$fixture/helper/managed-helper" "$fixture/helper/build-entry"; do
              [ ! -e "$file" ] || /usr/bin/chmod 755 -- "$file"
            done
            id() { printf '0\n'; }
            fixture_layout() { printf 'layout\n' >> "$fixture/preflight-effects"; }
            fixture_packages() { [ "${fixture_lock_held:-}" = 1 ]; printf 'packages\n' >> "$fixture/preflight-effects"; }
            systemctl() { case "$1" in daemon-reload | enable) printf '%s\n' "$1" >> "$fixture/effects";; esac; }
            fixture_chown() { :; }
            fixture_chmod() { :; }
            fixture_sync() {
              if [[ "${@: -1}" = */.build-entry.* ]]; then
                case "$fixture_mutation" in
                  staging) printf '#!/bin/sh\n# changed after admission\n' > "$fixture/helper/managed-helper" ;;
                  staging-known) printf '#!/bin/sh\n# fixture managed helper from a known version\n' > "$fixture/helper/managed-helper" ;;
                esac
              fi
            }
            fixture_stat() {
              case "$2" in
                '%u:%g') printf '0:0\n';;
                '%u:%g:%a:%h')
                  if [ "$fixture_prior" = metadata ] && [[ "${@: -1}" = */managed-helper ]]; then printf '0:0:755:2\n'
                  else printf '0:0:755:1\n'; fi;;
                '%u:%g:%a') case "${@: -1}" in */helper-install-operations) printf '0:0:700\n';; *.receipt) printf '0:0:600\n';; *) printf '0:0:755\n';; esac;;
                '%a') printf '755\n';;
                *) /usr/bin/stat "$@";;
              esac
            }
            fixture_install() {
              local -a arguments=()
              while [ "$#" -gt 0 ]; do
                case "$1" in -o | -g | -m) shift 2;; -d | --) shift;; *) arguments+=("$1"); shift;; esac
              done
              /usr/bin/mkdir -p -- "${arguments[@]}"
            }
            # Git Bash has no flock; this fixture checks lifetime/order, while Linux uses the real directory flock.
            fixture_flock() {
              [ "$1" = -x ] && [ -e "/dev/fd/$2" ]; fixture_lock_held=1
              if [ "$fixture_mutation" = lock ]; then printf '#!/bin/sh\n# changed after admission\n' > "$fixture/helper/managed-helper"; fi
            }
            fixture_mv() {
              [ -e "/dev/fd/$installer_lock" ] && [ "/dev/fd/$installer_lock" -ef "$fixture/helper" ] || return 28
              if [ "$fixture_mutation" = before-helper ] && [[ "${@: -1}" = */managed-helper ]]; then return 29; fi
              if [ "$fixture_mutation" = appeared ] && [[ "${@: -1}" = */build-entry ]]; then
                printf '#!/bin/sh\n# changed after admission\n' > "$fixture/helper/build-entry"
              fi
              case "${@: -1}" in *.receipt) printf 'receipt\n' >> "$fixture/effects";; *) printf 'publish\n' >> "$fixture/effects";; esac
              /usr/bin/mv "$@"
            }
            fixture_probe() {
              printf 'probe\n' >> "$fixture/effects"
            """ + (probeFailure ? "  return 29\n" : "  printf 'HELPER=1\\nPROTOCOL=" + DeploymentManagedHelperBundle.PROTOCOL_VERSION + "\\n'\n") + "}\n";
        return new Fixture(hooks + mapped, temporary.resolve("state/helper-install-operations/" + operation + ".receipt"));
    }

    private Result execute(String script) throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)), "A real Bash binary is required");
        Path command = temporary.resolve("installer-fixture.sh");
        Files.writeString(command, script, StandardCharsets.UTF_8);
        var builder = new ProcessBuilder(bash, "--noprofile", "--norc", bashPath(command))
            .directory(temporary.toFile()).redirectErrorStream(true);
        builder.environment().put("TEMP", temporary.toString());
        builder.environment().put("TMP", temporary.toString());
        builder.environment().put("TMPDIR", bashPath(temporary));
        builder.environment().remove("WTL_MIRROR_POLICY");
        var process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Installer fixture timed out");
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); }
        }
    }

    private static String bashPath(Path path) {
        String value = path.toAbsolutePath().toString().replace('\\', '/');
        return value.length() > 2 && value.charAt(1) == ':' ? "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2) : value;
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private record Fixture(String script, Path receipt) {}
    private record Result(int exit, String output) {}
}
