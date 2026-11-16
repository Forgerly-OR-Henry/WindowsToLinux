package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
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
    private static final String R33_HELPER = "#!/bin/sh\n# fixture for the R33 helper release\n";
    private static final String R35_HELPER = "#!/bin/sh\n# fixture for the R35 helper release\n";
    private static final String LEGACY_HELPER_DIGEST = "1a5970444c392ca120874bb39c8e6c0542e088b1d9a186d15affb67b05654836";
    private static final String LEGACY_ENTRY_DIGEST = "6eb9b24576e51e318044f67b1c77fc34b41c4c979cadfec1a5eb799c5122a91a";
    private static final String R33_HELPER_DIGEST = "65686d1c5fe7dc22fdf4529bae7d14822477452826cbcbd52697570b89928d07";
    private static final String R35_HELPER_DIGEST = "7a61bae1181f600a3a5df9675a878e6359cd3cc3f9823981df7e0db467dee8a9";
    private static final String R37_HELPER_DIGEST = "7ec53edbc9295fe8b964aa7756079e44d8e51b6ca6bfe83585e7dbeb9dca4a67";
    private static final String R41_HELPER_DIGEST = "a68639329a6fb5b5d4af6e8132a2097807266ef96e3b09809f3620570b79dfe5";
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
    void prerequisiteObservationParsesOnlyConclusivePackageStates() throws Exception {
        var point = gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint.APT_UBUNTU;
        String program = DeploymentHelperInstallerScript.renderPrerequisiteObservation(point)
            .replace("[ \"$EUID\" -eq 0 ] && apt_prerequisite_metadata", "apt_prerequisite_metadata")
            .replace("/usr/bin/stat", "fixture_stat")
            .replace("/usr/bin/env -i LC_ALL=C /usr/bin/timeout 15s", "fixture_invoke");
        int call = program.lastIndexOf("\napt_prerequisites_observe\n");
        assertTrue(call > 0);
        String fixtures = """
            apt_prerequisite_metadata() { return 0; }
            apt_prerequisite_os() { return 0; }
            apt_prerequisite_file() { return 0; }
            files_trusted_directory() { return 0; }
            files_trusted_runtime_path() { [ "$scenario" != unsafe ] || [ "$1" != /usr/bin/dpkg-query ]; }
            type() { printf '/usr/bin/%s\\n' "${3}"; }
            fixture_stat() {
              if [ "$scenario" = drift ] && [ -e "$fixture/queried" ]; then printf 'changed\\n'
              else printf 'record\\n'; fi
            }
            fixture_invoke() {
              if [ "$1" = /usr/bin/dpkg ]; then printf 'amd64\\n'; return 0; fi
              if [ "$scenario" = drift ]; then : > "$fixture/queried"; fi
              if [ "$scenario" = unknown ]; then printf 'unexpected warning\\n' >&2; fi
              local package
              for package in @EXACT_PACKAGES@; do
                if [ "$package" = sqlite3 ]; then
                  case "$scenario" in
                    missing) printf 'dpkg-query: no packages found matching sqlite3\\n' >&2; continue ;;
                    partial) printf 'sqlite3\\tamd64\\thalf-configured\\tok\\t1:3.45.1-1\\n'; continue ;;
                    multiarch) printf 'sqlite3\\tarm64\\tinstalled\\tok\\t3.45.1-1\\n'; continue ;;
                  esac
                fi
                printf '%s\\tamd64\\tinstalled\\tok\\t1:3.45.1-1\\n' "$package"
              done
              [ "$scenario" != missing ]
            }
            """.replace("@EXACT_PACKAGES@", DeploymentHelperInstallerScript.APT_PACKAGES);
        for (String scenario : List.of("ready", "missing", "partial", "multiarch", "unsafe", "unknown", "drift")) {
            var result = execute("scenario=" + scenario + "\nfixture=" + DeploymentHelperInstallerScript.quote(bashPath(temporary)) + "\n"
                + program.substring(0, call + 1) + fixtures + program.substring(call + 1));
            String expected = switch (scenario) {
                case "ready" -> "APT_PREREQUISITES=READY\n";
                case "missing" -> "APT_PREREQUISITES=MISSING\n";
                default -> "";
            };
            assertEquals(expected, result.output(), scenario);
            assertEquals(expected.isEmpty() ? 64 : 0, result.exit(), scenario);
        }
    }

    @Test
    void prerequisiteMetadataGuardRejectsBeforeObservation() throws Exception {
        var point = gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint.APT_UBUNTU;
        String program = DeploymentHelperInstallerScript.renderPrerequisiteObservation(point)
            .replace("[ \"$EUID\" -eq 0 ] && apt_prerequisite_metadata", "apt_prerequisite_metadata")
            .replace("[ ! -L \"$path\" ] && [ -O \"$path\" ] && [ -d \"$path\" ] && [ -x \"$path\" ]", ":")
            .replace("[ ! -L /usr/bin/perl ] && [ -O /usr/bin/perl ] && [ -f /usr/bin/perl ] && [ -x /usr/bin/perl ]", ":")
            .replace("LC_ALL=C /usr/bin/perl -T -f", "LC_ALL=C fixture_perl -T -f")
            .replace("$(/usr/bin/stat ", "$(fixture_stat ")
            .replace("$(/usr/bin/readlink ", "$(fixture_readlink ")
            .replace("/usr/bin/env -i LC_ALL=C /usr/bin/timeout 15s", "fixture_invoke");
        int call = program.lastIndexOf("\napt_prerequisites_observe\n");
        assertTrue(call > 0);
        String fixtures = """
            fixture_perl() {
              [ "$#" -eq 2 ] && [ "$1" = -T ] && [ "$2" = -f ] || return 1
              printf 'metadata\\n' >> "$fixture/calls"
              {
                printf '%s\\n' 'BEGIN {
                  my $case = $ENV{FIXTURE_METADATA_CASE};
                  my %mode = (
                    "/" => 0040755, "/usr" => 0040755, "/usr/bin" => 0040755, "/usr/libexec" => 0040755,
                    "/usr/bin/perl" => 0100755, "/usr/bin/stat" => 0120777, "/usr/bin/readlink" => 0100755,
                    "/usr/libexec/meta-stat" => 0120777, "/usr/libexec/coreutils" => 0100755,
                  );
                  my %target = ("/usr/bin/stat" => "../libexec/meta-stat", "/usr/libexec/meta-stat" => "/usr/libexec/coreutils");
                  $mode{"/usr/bin"} = 0040775 if $case eq "writable-directory";
                  $mode{"/usr/libexec"} = 0040775 if $case eq "writable-symlink-directory";
                  $mode{"/usr/libexec/coreutils"} = 0100757 if $case eq "writable-target";
                  $mode{"/usr/bin/readlink"} = 0100644 if $case eq "non-executable";
                  $target{"/usr/libexec/meta-stat"} = "../bin/stat" if $case eq "symlink-loop";
                  *CORE::GLOBAL::lstat = sub {
                    my ($entry) = @_;
                    return unless exists $mode{$entry};
                    my $uid = $case eq "non-root-link" && $entry eq "/usr/bin/stat" ? 12 : 0;
                    my $gid = $case eq "non-root-group" && $entry eq "/usr" ? 34 : 0;
                    return (0, 0, $mode{$entry}, 0, $uid, $gid);
                  };
                  *CORE::GLOBAL::readlink = sub { return $target{$_[0]}; };
                }'
                while IFS= read -r line; do printf '%s\\n' "$line"; done
              } | FIXTURE_METADATA_CASE="$scenario" /usr/bin/perl "$@"
            }
            apt_prerequisite_os() { printf 'os\\n' >> "$fixture/calls"; fixture_stat; fixture_readlink; }
            apt_prerequisite_file() { return 0; }
            files_trusted_directory() { return 0; }
            files_trusted_runtime_path() { return 0; }
            fixture_stat() { printf 'stat\\n' >> "$fixture/calls"; }
            fixture_readlink() { printf 'readlink\\n' >> "$fixture/calls"; }
            fixture_invoke() {
              printf '%s\\n' "$1" >> "$fixture/calls"
              if [ "$1" = /usr/bin/dpkg ]; then printf 'amd64\\n'; else return 2; fi
            }
            """;
        Path calls = temporary.resolve("calls");
        for (String scenario : List.of("trusted-symlink", "writable-directory", "writable-symlink-directory", "writable-target",
            "non-executable", "non-root-link", "non-root-group", "symlink-loop")) {
            Files.deleteIfExists(calls);
            var result = execute("scenario=" + scenario + "\nfixture=" + DeploymentHelperInstallerScript.quote(bashPath(temporary)) + "\n"
                + program.substring(0, call + 1) + fixtures + program.substring(call + 1));
            assertEquals(64, result.exit(), scenario);
            assertEquals("", result.output(), scenario);
            List<String> expected = scenario.equals("trusted-symlink")
                ? List.of("metadata", "os", "stat", "readlink", "stat", "/usr/bin/dpkg", "/usr/bin/dpkg-query")
                : List.of("metadata");
            assertEquals(expected, Files.readAllLines(calls), scenario);
        }
    }

    @Test
    void packageFreeTrackedInstallerBindsItsReadOnlyChecksAndOriginalReceipt() throws Exception {
        var layout = DeploymentRemoteLayout.original();
        var point = gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint.APT_UBUNTU;
        var task = UUID.randomUUID();
        var operation = UUID.randomUUID();
        String body = DeploymentHelperInstallerScript.readyBodyDigest(layout, point);
        String original = DeploymentHelperInstallerScript.renderTrackedReady(task, operation, "a".repeat(64), body, layout, point);
        var evidence = new ObjectMapper().valueToTree(DeploymentInstallerEvidence.describe(original, layout));
        assertEquals(original, DeploymentInstallerEvidence.reconstruct(evidence));
        assertTrue(DeploymentInstallerEvidence.hasReadyBodyDigest(evidence, layout));
        assertEquals(body, evidence.at("/digestScopes/bodyDigest/value").asText());
        assertEquals(sha(original), DeploymentHelperInstallerScript.readyRequestDigest(original));
        assertFalse(DeploymentInstallerEvidence.usesSharedMirrorBootstrap(evidence));
        String preflight = original.substring(0, original.indexOf("prepare_stage=helper-payload-staging"));
        assertFalse(preflight.contains("deployment_apt"));
        assertFalse(preflight.contains("wtl_apt"));
        assertFalse(preflight.contains("apt-get"));
        int beforeLock = original.indexOf("prepare_stage=platform-prerequisites-before-lock");
        int metadataAdmission = original.indexOf("\napt_prerequisite_metadata || exit 64\n");
        int priorAdmission = original.indexOf("\ninstaller_prior_files\n");
        int firstCheck = original.indexOf("prepare_stage=platform-prerequisites\n");
        int lastCheck = original.lastIndexOf("[ \"$(apt_prerequisites_observe)\"");
        assertTrue(beforeLock > 0 && beforeLock < original.indexOf("/usr/bin/flock -x \"$installer_lock\""));
        assertTrue(metadataAdmission > 0 && metadataAdmission < priorAdmission && priorAdmission < beforeLock);
        assertTrue(firstCheck > original.indexOf("/usr/bin/flock -x \"$installer_lock\""));
        assertTrue(firstCheck < original.indexOf("prepare_stage=installer-receipt\n"));
        assertTrue(lastCheck > original.indexOf("helper_probe="));
        assertTrue(lastCheck < original.indexOf("prepare_stage=installer-receipt-terminal\n"));
        assertEquals(new DeploymentHelperInstallerScript.TrackedRequest(operation, "a".repeat(64), true),
            DeploymentHelperInstallerScript.trackedRequest(original, task, layout, java.util.Optional.of(point)));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentHelperInstallerScript.trackedRequest(original + ":\n", task, layout, java.util.Optional.of(point)));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentHelperInstallerScript.trackedRequest(original, task, layout, java.util.Optional.empty()));
    }

    @Test
    void earlierAptReadyDigestsRemainAvailableOnlyForReconciliation() throws Exception {
        var layout = DeploymentRemoteLayout.original();
        var ubuntu = gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint.APT_UBUNTU;
        var debian = gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint.APT_DEBIAN;
        var task = UUID.fromString("11111111-2222-4333-8444-555555555555");
        var operation = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        String server = "9".repeat(64);
        var bodyDigests = java.util.Map.of(ubuntu, "0a06e70a700b17dbcc7955a6a7742f50026eea31a8866ad52bcd521e25e13dc5",
            debian, "2000ad0b2dff9c78c0cc0863468ee793e7438aa0d65eb84aeafa7cbe5c1d5a46");
        var requestDigests = java.util.Map.of(ubuntu, "b12a96ce27c2065b8726b0b0d94165ea5efceea14a873c62086a31a03b69d600",
            debian, "d27999dd9ddbbf9262485dcc64db73c93e984511d84bafe715eee6c5afa0f69d");
        for (var point : List.of(ubuntu, debian)) {
            String earlierBody = DeploymentHelperInstallerScript.previouslySentReady(DeploymentHelperInstallerScript.renderReady(layout, point), point);
            assertEquals(bodyDigests.get(point), sha(earlierBody));
            String current = DeploymentHelperInstallerScript.renderTrackedReady(task, operation, server, bodyDigests.get(point), layout, point);
            String earlier = DeploymentHelperInstallerScript.previouslySentReady(current, point);
            assertEquals(requestDigests.get(point), DeploymentHelperInstallerScript.readyRequestDigest(earlier));
            assertThrows(IllegalArgumentException.class,
                () -> DeploymentHelperInstallerScript.trackedRequest(earlier, task, layout, java.util.Optional.of(point)));
        }
        try (var fixture = new gold.debug.windowstolinux.mod.deployment.ai.review.MirroredInstallerTestFixture()) {
            String priorBody = bodyDigests.get(ubuntu);
            String current = DeploymentHelperInstallerScript.renderTrackedReady(fixture.task.id(), operation, server, priorBody, layout, ubuntu);
            String earlier = DeploymentHelperInstallerScript.previouslySentReady(current, ubuntu);
            String digest = DeploymentHelperInstallerScript.readyRequestDigest(earlier);
            assertTrue(DeploymentHelperInstallerScript.matchesTrackedRequest(fixture.task, operation, server, priorBody, digest, layout, true));
            assertFalse(DeploymentHelperInstallerScript.matchesTrackedRequest(fixture.task, operation, server, priorBody, digest, layout, false));
            assertFalse(DeploymentHelperInstallerScript.matchesTrackedRequest(fixture.task, operation, server, priorBody,
                DeploymentHelperInstallerScript.readyRequestDigest(current), layout, true));
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
    void scopedTrackedInstallerRejectsAnotherLivePlatformBeforeTheReceiptOrPackages() throws Exception {
        var layout = DeploymentRemoteLayout.original();
        var point = java.util.Optional.of(gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint.APT_UBUNTU);
        String tracked = DeploymentHelperInstallerScript.renderTracked(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
            DeploymentHelperInstallerScript.bodyDigest(layout, point), layout, point);
        int firstEffect = tracked.indexOf("installer_capture_prior\n");
        assertTrue(firstEffect > tracked.indexOf("PREPARE_REJECT=platform-package-scope"));
        assertTrue(tracked.indexOf("prepare_stage=installer-receipt") > firstEffect);
        String prefix = tracked.substring(0, firstEffect);
        int guardEnd = prefix.indexOf("\n", prefix.indexOf("PREPARE_REJECT=platform-package-scope"));
        String stage = prefix.substring(0, guardEnd + 1) + "printf 'passed-platform-scope\\n'\n";
        String fixture = """
            id() { printf '0\\n'; }
            systemctl() { return 0; }
            wtl_system_point() { printf '%s\\n' "$fixture_live_platform"; }
            """;
        for (String live : java.util.List.of("DNF_FEDORA", "APT_DEBIAN", "")) {
            var mismatch = execute("fixture_live_platform='" + live + "'\n" + fixture + stage);
            assertEquals(64, mismatch.exit(), mismatch.output());
            assertTrue(mismatch.output().contains("PREPARE_REJECT=platform-package-scope"));
            assertFalse(mismatch.output().contains("passed-platform-scope"));
        }
        var matched = execute("fixture_live_platform=APT_UBUNTU\n" + fixture + stage);
        assertEquals(0, matched.exit(), matched.output());
        assertTrue(matched.output().contains("passed-platform-scope"));
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

    @ParameterizedTest
    @ValueSource(strings = {R33_HELPER_DIGEST, R35_HELPER_DIGEST, R37_HELPER_DIGEST, R41_HELPER_DIGEST})
    void previousHelperIsTrustedOnlyForTheOriginalLayoutUpgrade(String digest) {
        var original = DeploymentRemoteLayout.original();
        var custom = new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths(
            "/fixture/lib", "/fixture/apps", "/fixture/state", "/fixture/config", "/fixture/data"));
        String prior = "helper:" + digest;
        var ubuntu = java.util.Optional.of(gold.debug.windowstolinux.mod.deployment.service.mirror.DownloadPoint.APT_UBUNTU);
        assertTrue(DeploymentHelperInstallerScript.renderTracked(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64),
            DeploymentHelperInstallerScript.bodyDigest(original, ubuntu), original, ubuntu).contains(prior));
        assertTrue(DeploymentHelperInstallerScript.renderHelperInstallation(original).contains(prior));
        assertFalse(DeploymentHelperInstallerScript.renderFiles(original).contains(prior));
        assertFalse(DeploymentHelperInstallerScript.render(custom, ubuntu).contains(prior));
        assertFalse(DeploymentHelperInstallerScript.renderHelperInstallation(custom).contains(prior));
        assertFalse(DeploymentHelperInstallerScript.render(original, ubuntu).contains("build-entry:" + digest));
    }

    @ParameterizedTest
    @ValueSource(strings = {"r33", "r35"})
    void originalLayoutUpgradeReplacesPreviousHelperAndKeepsCurrentBuildEntry(String prior) throws Exception {
        var fixture = fixture(false, "none", prior, "none");
        var result = execute(fixture.script());
        assertEquals(0, result.exit(), result.output());
        assertEquals(DeploymentManagedHelperBundle.renderScript(), Files.readString(temporary.resolve("helper/managed-helper")));
        assertEquals("publish\ndaemon-reload\nprobe\nreceipt\n", Files.readString(temporary.resolve("effects")));
        assertTrue(Files.readString(fixture.receipt()).endsWith("PHASE=SUCCEEDED\n"));
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
        for (String previousRelease : List.of(
            "helper:bcb63c832f1dc89a19de021d3f7a0fed0cb2a028097a9c3f285a2d3336b69c15",
            "helper:5269a09f9fc4e49209ef30c95d9d52fb5f92e67bfd024471f491014ee78c8bca",
            "helper:d10a316ac0078b4dabb286409e12845376d719578c9e2c029686deaf369cc3b4")) {
            assertTrue(DeploymentHelperInstallerScript.render(layout).contains(previousRelease));
            assertFalse(DeploymentHelperInstallerScript.renderFiles(layout).contains(previousRelease));
        }
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
                    .replace("helper:" + R33_HELPER_DIGEST, "helper:" + sha(R33_HELPER))
                    .replace("helper:" + R35_HELPER_DIGEST, "helper:" + sha(R35_HELPER))
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
                case "r33" -> R33_HELPER;
                case "r35" -> R35_HELPER;
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
