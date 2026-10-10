package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace;
import gold.debug.windowstolinux.mod.deployment.service.core.model.language.ProjectLanguageFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.*;
import gold.debug.windowstolinux.mod.deployment.service.core.config.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.resource.path.*;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlan;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;
import org.junit.jupiter.api.*;

/** Executes only rendered lifecycle fragments in this task's local temporary directory; no Cargo, API or SSH. / 仅在任务本地临时目录执行生命周期渲染片段，不调用 Cargo、API 或 SSH。 */
class DeploymentAgentCargoArtifactLifecycleTest {
    private static final DeploymentRemoteWorkspace WORKSPACE = new DeploymentRemoteWorkspace("cargo-local", "a".repeat(64));

    private final List<Path> owned = new ArrayList<>();

    @AfterEach
    void removeOwnedFixtures() throws Exception {
        Path base = workspaceRoot().resolve(".ai-workspace/tmp").toAbsolutePath().normalize();
        for (Path root : owned) {
            assertTrue(root.toAbsolutePath().normalize().startsWith(base));
            assertFalse(Files.isSymbolicLink(root));
            try (var members = Files.walk(root)) {
                for (Path member : members.sorted(Comparator.reverseOrder()).toList()) Files.delete(member);
            }
        }
    }

    @Test
    void onlyExactCargoRecipeHasLifecycleAndSemanticsAreNotObservedSuccess() throws Exception {
        var cargo = lifecycle("", "run.sh", "");
        assertEquals(true, cargo.semantics().get("semanticsOnly"));
        assertEquals(".w2l/bin/demo", cargo.semantics().get("runtimeArtifactRelativePath"));
        assertEquals("target", cargo.semantics().get("temporaryBuildTreeRelativePath"));
        assertFalse(cargo.semantics().containsKey("exists"));
        assertFalse(cargo.semantics().containsKey("sealed"));
        var other = new DeploymentProjectFacts(fixture(), "demo", DeploymentProjectKind.GO_SERVICE,
            DeploymentBuildToolType.GO_MODULE, ProjectLanguageFacts.empty(), List.of(), List.of(), List.of());
        assertTrue(DeploymentAgentCargoArtifactLifecycle.forRecipe(other, runtime("run.sh", "")).isEmpty());
    }

    @Test
    void frozenSourceTargetsAreRefusedBeforeAnyBuildAndRemainUntouched() throws Exception {
        Path root = fixture();
        Files.createDirectories(root.resolve("app/nested"));
        var cargo = lifecycle("nested", "run.sh", "");
        cargo.validateSource(root, "app");
        for (String target : List.of("app/target", "app/nested/target")) {
            Path existing = root.resolve(target);
            Files.createDirectory(existing);
            Files.writeString(existing.resolve("user-data"), "retain");
            assertThrows(IllegalArgumentException.class, () -> cargo.validateSource(root, "app"));
            assertEquals("retain", Files.readString(existing.resolve("user-data")));
            Files.delete(existing.resolve("user-data"));
            Files.delete(existing);
        }
    }

    @Test
    void runtimeAndHealthReferencesToRecipeTargetRequireCustomCommands() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> lifecycle("", "target/release/demo", ""));
        assertThrows(IllegalArgumentException.class, () -> lifecycle("nested", "run.sh", "nested/target"));
        var facts = facts(fixture(), "");
        var health = new DeploymentHealthCheck.Command(new DeploymentCommand("target/release/demo", List.of()), "ok", 10);
        var rust = new DeploymentRuntimeDefinition.RustService("1.95.0", "demo", "src/main.rs", health)
            .withWorkload(workload("run.sh", ""));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentCargoArtifactLifecycle.forRecipe(facts, rust));
        var args = workload("run.sh", "");
        var referenced = new DeploymentWorkload(args.mode(), true, new DeploymentCommand("run.sh", List.of("--helper=target/release/demo")),
            "", List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentCargoArtifactLifecycle.forRecipe(facts,
            runtime("run.sh", "").withWorkload(referenced)));
    }

    @Test
    void sourceSeedsAndLiteralConfigurationPathsProtectTargetButManagedDestinationsDoNot() throws Exception {
        var cargo = lifecycle("", "run.sh", "");
        var managed = new DeploymentFileBinding("data", new DeploymentStoragePath("target/data", DeploymentStoragePath.AccessMode.READ_WRITE, "cargo-data-v1", true),
            DeploymentStorageLocation.defaults(), DeploymentStorageLocation.StorageResourceType.FILE, "", "");
        cargo.validateReferences(part(List.of(managed), List.of(new DeploymentConfigEntry("LABEL", DeploymentConfigScopeType.RUNTIME,
            new DeploymentConfigValue.Text("target business label")))));
        var seed = new DeploymentFileBinding("config", new DeploymentStoragePath("config/values", DeploymentStoragePath.AccessMode.READ_ONLY, "cargo-config-v1", true),
            DeploymentStorageLocation.defaults(), DeploymentStorageLocation.StorageResourceType.CONFIGURATION, "target/generated.json", "b".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> cargo.validateReferences(part(List.of(seed), List.of())));
        assertThrows(IllegalArgumentException.class, () -> cargo.validateReferences(part(List.of(), List.of(
            new DeploymentConfigEntry("HELPER_PATH", DeploymentConfigScopeType.RUNTIME, new DeploymentConfigValue.Text("target/release/demo"))))));
    }

    @Test
    void sourceAndOutputParentSymlinksRefuseBeforeAnyTargetRemoval() throws Exception {
        var sourceLink = shell("", "", """
            mkdir -p "$fixture_candidate/source-app-real"
            printf retain >"$fixture_candidate/source-app-real/user-data"
            rmdir "$WTL_SOURCE/app"
            ln -s "$fixture_candidate/source-app-real" "$WTL_SOURCE/app" || exit 77
            test -L "$WTL_SOURCE/app" || exit 77
            """, "");
        Assumptions.assumeTrue(sourceLink.exit() != 77, "Local filesystem symlink semantics unavailable");
        assertNotEquals(0, sourceLink.exit(), sourceLink.output());
        assertEquals("retain", Files.readString(sourceLink.root().resolve("candidate/source-app-real/user-data")));
        var outputLink = shell("", """
            mv "$source/.w2l" "$source/w2l-real"
            ln -s "$source/w2l-real" "$source/.w2l" || exit 77
            test -L "$source/.w2l" || exit 77
            """, "", "");
        Assumptions.assumeTrue(outputLink.exit() != 77, "Local filesystem symlink semantics unavailable");
        assertNotEquals(0, outputLink.exit(), outputLink.output());
        assertTrue(Files.exists(outputLink.root().resolve("candidate/mutable/source/target/release/demo")));
        assertFalse(outputLink.output().contains("AGENT_CARGO_TARGET_REMOVED=1"));
    }

    @Test
    void realHardlinkedCargoTargetIsRemovedAndUnrelatedHardlinksRemainRejected() throws Exception {
        var result = shell("nested", "", "", """
            test ! -e "$source/nested/target"
            test "$(stat -c '%h' "$source/outside-a")" = 2
            test "$(stat -c '%h' "$source/outside-b")" = 2
            test "$(stat -c '%h' "$source/nested/.w2l/bin/demo")" = 1
            cmp -- "$source/nested/.w2l/bin/demo" "$fixture_candidate/expected-artifact"
            """);
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("AGENT_CARGO_TARGET_REMOVED=1"));
    }

    @Test
    void hardlinkedOrByteMismatchedRetainedArtifactNeverTriggersCleanup() throws Exception {
        for (String mutation : List.of("rm -- \"$retained\"; ln -- \"$original\" \"$retained\"\n",
            "printf '#!/bin/sh\\nprintf different\\n' >\"$retained\"\n")) {
            var result = shell("", mutation, "", "");
            assertNotEquals(0, result.exit(), result.output());
            assertTrue(Files.exists(result.root().resolve("candidate/mutable/source/target/release/demo")));
            assertFalse(result.output().contains("AGENT_CARGO_TARGET_REMOVED=1"));
        }
    }

    @Test
    void failedRecipeStopsBeforeLifecycleRemovalOrSuccessReceipt() throws Exception {
        var result = shell("", "false\n", "", "");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(Files.exists(result.root().resolve("candidate/mutable/source/target/release/demo")));
        assertFalse(result.output().contains("AGENT_CARGO_TARGET_REMOVED=1"));
    }

    @Test
    void wrongOutputBoundaryCannotRemoveAnyTarget() throws Exception {
        var result = shell("", "WTL_OUTPUT=\"$source/elsewhere\"\n", "", "");
        assertNotEquals(0, result.exit(), result.output());
        assertTrue(Files.exists(result.root().resolve("candidate/mutable/source/target/release/demo")));
        assertFalse(result.output().contains("AGENT_CARGO_TARGET_REMOVED=1"));
    }

    @Test
    void preexistingRemoteSourceTargetFailsBeforeRecipeBody() throws Exception {
        var result = shell("", "", "mkdir -p \"$WTL_SOURCE/app/target\"; printf retain >\"$WTL_SOURCE/app/target/user-data\"\n", "");
        assertNotEquals(0, result.exit(), result.output());
        assertEquals("retain", Files.readString(result.root().resolve("candidate/mutable/input-source/app/target/user-data")));
        assertFalse(Files.exists(result.root().resolve("candidate/mutable/source/target")));
    }

    private DeploymentAgentCargoArtifactLifecycle lifecycle(String directory, String entry, String working) throws Exception {
        return DeploymentAgentCargoArtifactLifecycle.forRecipe(facts(fixture(), directory), runtime(entry, working)).orElseThrow();
    }

    private static DeploymentProjectFacts facts(Path root, String directory) {
        return new DeploymentProjectFacts(root, "demo", DeploymentProjectKind.RUST_SERVICE, DeploymentBuildToolType.CARGO_LOCKED,
            ProjectLanguageFacts.empty(), List.of(), List.of(), List.of()).inBundle(root, directory, "demo");
    }

    private static DeploymentRuntimeDefinition runtime(String entry, String working) {
        return new DeploymentRuntimeDefinition.RustService("1.95.0", "demo", "src/main.rs",
            new DeploymentHealthCheck.Http(URI.create("http://127.0.0.1:8080/"), 200, 10)).withWorkload(workload(entry, working));
    }

    private static DeploymentWorkload workload(String entry, String working) {
        return new DeploymentWorkload(DeploymentWorkload.ExecutionMode.DAEMON, true, new DeploymentCommand(entry, List.of()), working,
            List.of(), Optional.empty(), "", Optional.empty(), List.of(), List.of());
    }

    private static DeploymentAgentPlan.Part part(List<DeploymentFileBinding> bindings, List<DeploymentConfigEntry> configuration) {
        return new DeploymentAgentPlan.Part("cli", "app", List.of(), List.of("o1"), runtime("run.sh", ""),
            new DeploymentResourceBindings(bindings, Optional.of(List.of())), configuration, Optional.of(List.of()), List.of());
    }

    private record ShellResult(Path root, int exit, String output) {
    }

    private ShellResult shell(String directory, String mutation, String beforeSource, String assertions) throws Exception {
        Path bash = List.of(Path.of("E:/Program/Git/bin/bash.exe"), Path.of("C:/Program Files/Git/bin/bash.exe"), Path.of("/bin/bash"))
            .stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(bash != null, "Local Bash required for real filesystem lifecycle checks");
        Path root = fixture();
        var cargo = DeploymentAgentCargoArtifactLifecycle.forRecipe(facts(root, directory), runtime("run.sh", "")).orElseThrow();
        String body = """
            set -euo pipefail
            PATH=/usr/bin:/bin
            fixture_candidate="$(pwd -P)/candidate"
            WTL_SOURCE="$fixture_candidate/mutable/input-source"
            WTL_OUTPUT="$fixture_candidate/mutable/source"
            source="$WTL_OUTPUT"
            mkdir -p "$WTL_SOURCE/app" "$WTL_OUTPUT"
            verify_build_command() { test -f "$1" && test -x "$1"; }
            """ + "mkdir -p \"$WTL_SOURCE/app\"" + (directory.isEmpty() ? "" : "/" + SafeBuildScriptEnvelope.shellQuote(directory)) + "\n"
            + beforeSource + cargo.before(WORKSPACE, "app")
            + "build=\"$source\"" + (directory.isEmpty() ? "" : "/" + SafeBuildScriptEnvelope.shellQuote(directory)) + "\n"
            + """
            mkdir -p "$build/target/release/deps" "$build/.w2l/bin"
            original="$build/target/release/demo"
            retained="$build/.w2l/bin/demo"
            printf '#!/bin/sh\\nprintf artifact-bytes\\n' >"$original"
            chmod 700 "$original"
            ln "$original" "$build/target/release/deps/demo-hash"
            cp "$original" "$retained"
            cp "$original" "$fixture_candidate/expected-artifact"
            test -x "$original" && test -x "$retained"
            test "$(stat -c '%h' -- "$original")" = 2
            test "$(stat -c '%h' -- "$retained")" = 1
            printf unrelated >"$source/outside-a"
            ln "$source/outside-a" "$source/outside-b"
            """ + mutation + cargo.after(WORKSPACE) + assertions;
        // Only the fixed product candidate prefix is mapped to this owned local fixture; lifecycle code is unchanged.
        body = body.replace(SafeBuildScriptEnvelope.shellQuote(WORKSPACE.candidateRoot() + "/mutable/input-source"), "\"$WTL_SOURCE\"")
            .replace(SafeBuildScriptEnvelope.shellQuote(WORKSPACE.candidateRoot() + "/mutable/source"), "\"$source\"");
        Path output = root.resolve("receipt.txt");
        var builder = new ProcessBuilder(bash.toString(), "--noprofile", "--norc", "-s").directory(root.toFile())
            .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().remove("BASH_ENV");
        builder.environment().remove("ENV");
        builder.environment().put("PATH", "/usr/bin:/bin");
        builder.environment().put("MSYS", "winsymlinks:nativestrict");
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(body.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) throw new AssertionError("Local lifecycle fixture timed out");
            return new ShellResult(root, process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    private Path fixture() throws Exception {
        Path base = workspaceRoot().resolve(".ai-workspace/tmp");
        Files.createDirectories(base);
        Path root = Files.createTempDirectory(base, "agent-cargo-lifecycle-").toAbsolutePath().normalize();
        assertTrue(root.startsWith(base.toAbsolutePath().normalize()));
        owned.add(root);
        return root;
    }

    private static Path workspaceRoot() {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.isDirectory(root.resolve(".mvn"))) root = root.getParent();
        return Objects.requireNonNull(root, "Repository workspace root unavailable");
    }
}
