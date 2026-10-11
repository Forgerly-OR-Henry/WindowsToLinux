package gold.debug.windowstolinux.mod.deployment.linux.helper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentConfig;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.linux.command.contract.DeploymentModelCommandIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeploymentDirectoryInstallationTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void installersAndReceiptsUseTheirExactLayoutAndKeepCompleteSources(boolean custom) throws Exception {
        var layout = custom ? layout("A") : DeploymentRemoteLayout.original();
        String installer = DeploymentHelperInstallerScript.render(layout);
        String files = DeploymentHelperInstallerScript.renderFiles(layout);
        var task = UUID.randomUUID();
        var operation = UUID.randomUUID();
        String tracked = DeploymentHelperInstallerScript.renderTracked(task, operation, "a".repeat(64), "b".repeat(64), layout);
        for (String script : List.of(installer, files, tracked)) {
            syntax(script);
            assertFalse(script.contains("@wtl:"));
            assertTrue(script.contains(layout.helperPath()));
            assertTrue(script.contains(DeploymentManagedHelperBundle.helperSha256(layout)));
            assertTrue(script.indexOf("host-layout-conflict") < script.indexOf("prepare_stage=platform-packages"));
        }
        var json = new ObjectMapper();
        var evidence = json.valueToTree(DeploymentInstallerEvidence.describe(installer, layout));
        assertEquals(installer, DeploymentInstallerEvidence.reconstruct(evidence));
        DeploymentInstallerEvidence.verify(evidence, installer);
        assertEquals(files, DeploymentInstallerEvidence.reconstruct(json.valueToTree(DeploymentInstallerEvidence.describeFiles(files, layout))));
        assertEquals(DeploymentManagedHelperBundle.renderScript(layout), evidence.at("/sources/managedHelper").textValue());
        assertEquals(DeploymentManagedHelperBundle.renderBuildEntry(layout), evidence.at("/sources/buildEntry").textValue());
        assertTrue(tracked.contains(layout.helperInstallOperationsRoot()));
        syntax(DeploymentHelperInstallerScript.renderStatus(operation, layout));
        String modelStatus = DeploymentHelperInstallerScript.renderReceiptStatus(operation, layout, layout.modelCommandOperationsRoot());
        assertTrue(modelStatus.contains(layout.modelCommandOperationsRoot()));
        assertFalse(modelStatus.contains(layout.helperInstallOperationsRoot()));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentHelperInstallerScript.renderReceiptStatus(operation, layout, "/tmp/arbitrary"));
    }

    @Test
    void customReviewContractAndInputEvidenceRequireTheSameRenderedHelper() {
        var first = layout("A");
        var second = layout("B");
        String body = "'" + first.helperPath() + "' probe";
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(body, first);
        assertEquals(body, DeploymentHelperReviewContract.verifiedInvocation(guarded, first));
        assertThrows(IllegalArgumentException.class, () -> DeploymentHelperReviewContract.verifiedInvocation(guarded, second));
        String contract = DeploymentHelperReviewContract.text(first);
        assertTrue(contract.contains(first.helperPath()));
        assertTrue(contract.contains(first.buildEntry()));
        assertTrue(contract.contains(DeploymentManagedHelperBundle.helperSha256(first)));
        assertFalse(contract.contains(DeploymentManagedHelperBundle.EXPECTED_SHA256));
        byte[] bytes = "printf 'ok\\n'\n".getBytes(StandardCharsets.UTF_8);
        String digest = DeploymentModelCommandIdentity.digest(new String(bytes, StandardCharsets.UTF_8));
        String invocation = "'" + first.helperPath() + "' 'agent-run' 'app' 'candidate' 'task' '" + "a".repeat(64)
            + "' '30' '1024' 'ordinary' '" + digest + "' '-'";
        assertEquals(digest, DeploymentAgentCommandInputBinding.facts(invocation, bytes,
            new String(bytes, StandardCharsets.UTF_8), first).get("stdinSha256"));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentAgentCommandInputBinding.facts(invocation, bytes, new String(bytes, StandardCharsets.UTF_8), second));
    }

    @Test
    void modelWrapperBindsOriginalLayoutWithoutChangingModelBody() throws Exception {
        var first = layout("A");
        String body = "printf '/var/lib/windowstolinux is user text\\n'\n";
        var task = UUID.randomUUID();
        var operation = UUID.randomUUID();
        String digest = DeploymentModelCommandIdentity.digest(body);
        String envelope = DeploymentModelCommandIdentity.envelope(task, operation, digest, body, "deploy", first);
        syntax(envelope);
        var facts = DeploymentModelCommandIdentity.executableFacts(envelope, first);
        assertEquals(body, facts.get("modelScript"));
        assertEquals(first.modelCommandOperationsRoot(), facts.get("productReceiptDirectory"));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentModelCommandIdentity.executableFacts(envelope, layout("B")));
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentModelCommandIdentity.executableFacts(envelope.replace(body, "printf 'changed\\n'\n"), first));
        assertTrue(DeploymentModelCommandIdentity.header(task, operation, digest, first).contains(first.digest()));
        assertEquals(DeploymentModelCommandIdentity.header(task, operation, digest),
            DeploymentModelCommandIdentity.header(task, operation, digest, DeploymentRemoteLayout.original()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"fresh", "same", "different", "legacy-default", "legacy-custom", "foreign", "dropins", "symlink", "original-root"})
    void hostAdmissionChecksActualAnchorBeforeManagedRootEffects(String scenario) throws Exception {
        var selected = scenario.equals("legacy-default") ? DeploymentRemoteLayout.original() : layout("A");
        String base = bashPath(temporary);
        String unit = base + "/etc/systemd/system/windowstolinux-workspace-recovery.service";
        String original = scenario.startsWith("legacy") ? DeploymentRemoteLayoutAdmission.originalUnit()
            : scenario.equals("different") ? DeploymentRemoteLayoutAdmission.unit(layout("B"))
            : scenario.equals("foreign") ? "[Unit]\nDescription=foreign\n"
            : DeploymentRemoteLayoutAdmission.unit(selected);
        String hooks = "fixture_id() { printf '0\\n'; }\nfixture_chown() { :; }\n"
            + "fixture_stat() { case \"$2\" in '%u:%g') printf '0:0\\n';; '%u:%g:%a') printf '0:0:644\\n';; *) /usr/bin/stat \"$@\";; esac; }\n"
            + "fixture_systemctl() { case \"$*\" in *DropInPaths*) "
            + (scenario.equals("dropins") ? "printf 'foreign.conf\\n';;" : "printf '\\n';;")
            + " *) printf '\\n';; esac; }\n";
        String setup = "mkdir -p '" + base + "/etc/systemd/system' '" + base + "/srv'\n";
        if (!scenario.equals("fresh") && !scenario.equals("dropins") && !scenario.equals("original-root")) {
            Files.createDirectories(temporary.resolve("etc/systemd/system"));
            Files.writeString(temporary.resolve("etc/systemd/system/windowstolinux-workspace-recovery.service"),
                map(original, base), StandardCharsets.UTF_8);
        }
        if (scenario.equals("symlink"))
            setup += "mv '" + unit + "' '" + unit + ".original'\nln -s '" + unit + ".original' '" + unit + "'\n";
        if (scenario.equals("original-root"))
            setup += "mkdir -p '" + base + "/var/lib/windowstolinux'\n";
        String admission = map(DeploymentRemoteLayoutAdmission.initialize(selected), base)
            .replace("/usr/bin/stat", "fixture_stat").replace("/usr/bin/chown", "fixture_chown")
            .replace("/usr/bin/systemctl", "fixture_systemctl").replace("$(id -u)", "$(fixture_id -u)");
        int exit = execute(hooks + setup + admission);
        boolean accepted = List.of("fresh", "same", "legacy-default").contains(scenario);
        assertEquals(accepted ? 0 : 64, exit);
        assertFalse(Files.exists(temporary.resolve("srv/wtl.helper-A")), "Admission must not initialize helper roots");
        try (var files = Files.list(temporary.resolve("etc/systemd/system"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".windowstolinux-layout.")), "Temporary anchor evidence leaked");
        }
        if (scenario.equals("fresh"))
            assertEquals(map(DeploymentRemoteLayoutAdmission.unit(selected), base), Files.readString(temporary.resolve("etc/systemd/system/windowstolinux-workspace-recovery.service")));
        if (List.of("same", "different", "foreign", "legacy-default", "legacy-custom").contains(scenario))
            assertEquals(map(original, base), Files.readString(temporary.resolve("etc/systemd/system/windowstolinux-workspace-recovery.service")), "Existing anchor changed");
    }

    private static String map(String source, String base) {
        var roots = java.util.regex.Pattern.compile("/usr/local/lib/windowstolinux|/etc/opt/windowstolinux/apps|/var/opt/windowstolinux/apps|/opt/windowstolinux/apps|/var/lib/windowstolinux|/etc/systemd|/srv/wtl");
        return roots.matcher(source).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(base + match.group()))
            .replace("in / /etc ", "in / '" + base + "/etc' ")
            .replace("in / /srv", "in / '" + base + "/srv'");
    }

    private static String bashPath(Path path) {
        String value = path.toAbsolutePath().toString().replace('\\', '/');
        return value.length() > 2 && value.charAt(1) == ':' ? "/" + Character.toLowerCase(value.charAt(0)) + value.substring(2) : value;
    }

    private static DeploymentRemoteLayout layout(String suffix) {
        return new DeploymentRemoteLayout(new DeploymentConfig.RemotePaths("/srv/wtl.helper-" + suffix,
            "/srv/wtl.apps-" + suffix, "/srv/wtl.state-" + suffix, "/srv/wtl.config-" + suffix, "/srv/wtl.data-" + suffix));
    }

    private static void syntax(String script) throws Exception { assertEquals(0, run(script, "-n")); }
    private static int execute(String script) throws Exception { return run(script, "-e"); }
    private static int run(String script, String mode) throws Exception {
        var builder = new ProcessBuilder(System.getProperty("managed.test.bash", "/bin/bash"), "--noprofile", "--norc", mode, "-s")
            .redirectErrorStream(true);
        builder.environment().put("MSYS", "winsymlinks:lnk");
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) { input.write(script.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Local shell check timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0 && process.exitValue() != 64) fail(output);
            return process.exitValue();
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(10, TimeUnit.SECONDS)); }
        }
    }
}
