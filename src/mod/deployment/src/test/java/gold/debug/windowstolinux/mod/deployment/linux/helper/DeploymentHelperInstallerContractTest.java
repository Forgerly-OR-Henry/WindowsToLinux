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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentHelperInstallerContractTest {
    @TempDir Path temporary;

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
        assertEquals("original helper", Files.readString(temporary.resolve("helper/managed-helper")));
        assertEquals("original entry", Files.readString(temporary.resolve("helper/build-entry")));
        assertFalse(Files.exists(temporary.resolve("effects")), "No publication, service or probe may follow a corrupt payload");
        String receipt = Files.readString(fixture.receipt());
        assertTrue(receipt.endsWith("PHASE=STARTED\n"));
        try (var files = Files.list(temporary.resolve("helper"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".")), "Temporary payloads must be cleaned");
        }
    }

    @Test
    void publishedFilesAreVerifiedAndProbeFailureCannotWriteSuccessReceipt() throws Exception {
        var fixture = fixture(true, "none");
        var result = execute(fixture.script());
        assertEquals(29, result.exit(), result.output());
        assertEquals(DeploymentManagedHelperBundle.renderScript(), Files.readString(temporary.resolve("helper/managed-helper")));
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(), Files.readString(temporary.resolve("helper/build-entry")));
        String effects = Files.readString(temporary.resolve("effects"));
        assertEquals("publish\npublish\ndaemon-reload\nenable\nprobe\n", effects);
        assertTrue(Files.readString(fixture.receipt()).endsWith("PHASE=STARTED\n"));
    }

    @Test
    void successfulPublicationHoldsItsDirectoryLockUntilTheFinalReceipt() throws Exception {
        var fixture = fixture(false, "none");
        var result = execute(fixture.script());
        assertEquals(0, result.exit(), result.output());
        assertTrue(Files.readString(fixture.receipt()).endsWith("PHASE=SUCCEEDED\n"));
        assertEquals("publish\npublish\ndaemon-reload\nenable\nprobe\nreceipt\n", Files.readString(temporary.resolve("effects")));
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
            < installer.indexOf("/usr/bin/mv -fT -- \"$helper_tmp\""));
        assertTrue(installer.lastIndexOf("PREPARE_REJECT=build-entry-installed-digest") < installer.indexOf("helper_probe="));
    }

    private Fixture fixture(boolean probeFailure, String damaged) throws Exception {
        var layout = DeploymentRemoteLayout.original();
        var operation = UUID.randomUUID();
        String original = DeploymentHelperInstallerScript.renderTracked(UUID.randomUUID(), operation, "a".repeat(64),
            sha(DeploymentHelperInstallerScript.render()), layout);
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
                    .replace(DeploymentRemoteLayoutAdmission.initialize(layout), "")
                    .replace(layout.paths().helperDirectory(), base + "/helper")
                    .replace(layout.paths().stateRoot(), base + "/state")
                    .replace("/usr/bin/stat", "fixture_stat")
                    .replace("/usr/bin/chown", "fixture_chown")
                    .replace("/usr/bin/chmod", "fixture_chmod")
                    .replace("/usr/bin/install", "fixture_install")
                    .replace("/usr/bin/sync", "fixture_sync")
                    .replace("/usr/bin/flock", "fixture_flock")
                    .replace("/usr/bin/mv", "fixture_mv")
                    .replace("helper_probe=\"$('" + base + "/helper/managed-helper' probe)\"", "helper_probe=\"$(fixture_probe)\"")
                    .replace(DeploymentHelperInstallerScript.renderSecurityObservationFunctions(), "")
                    .replace(DeploymentHelperInstallerScript.renderSecurityInvariant(), "");
                mapped.append(text);
            }
        }
        Files.createDirectories(temporary.resolve("helper"));
        Files.writeString(temporary.resolve("helper/managed-helper"), "original helper");
        Files.writeString(temporary.resolve("helper/build-entry"), "original entry");
        String hooks = "fixture=" + DeploymentHelperInstallerScript.quote(base) + "\n" + """
            id() { printf '0\n'; }
            apt-get() { [ "${fixture_lock_held:-}" = 1 ]; }
            systemctl() { case "$1" in daemon-reload | enable) printf '%s\n' "$1" >> "$fixture/effects";; esac; }
            fixture_chown() { :; }
            fixture_chmod() { :; }
            fixture_sync() { :; }
            fixture_stat() {
              case "$2" in
                '%u:%g') printf '0:0\n';;
                '%u:%g:%a:%h') printf '0:0:755:1\n';;
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
            fixture_flock() { [ "$1" = -x ] && [ -e "/dev/fd/$2" ]; fixture_lock_held=1; }
            fixture_mv() {
              [ -e "/dev/fd/$installer_lock" ] && [ "/dev/fd/$installer_lock" -ef "$fixture/helper" ] || return 28
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
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", bashPath(command)).redirectErrorStream(true).start();
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
