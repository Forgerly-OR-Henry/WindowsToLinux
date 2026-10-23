package gold.debug.windowstolinux.mod.deployment.service.deploy.build.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeBuildScriptEnvelopeTest {
    @TempDir
    Path root;

    @Test
    void agentRecipeCopiesCurrentComponentSourceInsteadOfOriginalArchiveAndRejectsOutsideRoots() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        var facts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(root, "fixture",
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.STATIC_SITE,
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.STATIC_SITE_BUILD, List.of(), List.of(), List.of());
        var workspace = new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace("fixture", "a".repeat(64));
        String envelope = SafeBuildScriptEnvelope.wrap(facts, workspace,
            gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(), "");
        String branch = envelope.substring(envelope.indexOf("if [ -n \"${WTL_AGENT_SOURCE:-}\" ]; then"), envelope.indexOf("\nelse\n", envelope.indexOf("if [ -n "))) + "\nfi\n";
        Path mutable = Files.createDirectories(root.resolve("mutable"));
        Path input = Files.createDirectories(mutable.resolve("input-source/api"));
        Files.writeString(input.resolve("entry.txt"), "current patched source\n");
        Path shared = Files.createDirectories(mutable.resolve("input-source/samples"));
        Files.writeString(shared.resolve("sample.bin"), "shared sample\n");
        Path previous = Files.createDirectories(mutable.resolve("source"));
        Files.writeString(previous.resolve("assembled-launcher"), "old assembled output\n");
        String path = mutable.toString().replace('\\', '/');
        if (path.matches("^[A-Za-z]:/.*")) path = "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2);
        for (boolean outside : List.of(false, true)) {
            // Only the Windows fixture's read-only predicate is simulated; copy and confinement use real Bash/files.
            String setup = "set -eu\nexport PATH=/usr/bin:/bin\nmutable=" + SafeBuildScriptEnvelope.shellQuote(path)
                + "\nsource=$mutable/source\nWTL_SOURCE=$mutable/input-source\nWTL_OUTPUT=$source\nWTL_AGENT_SOURCE="
                + (outside ? "$mutable" : "$WTL_SOURCE/api") + "\n"
                + "test() { if [ \"$1\" = ! ] && [ \"$2\" = -w ] && [ \"$3\" = \"$WTL_AGENT_SOURCE\" ]; then return 0; fi; builtin test \"$@\"; }\n";
            var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
            try {
                try (var stream = process.getOutputStream()) {
                    stream.write((setup + branch).getBytes(StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(outside ? 64 : 0, process.exitValue(), output);
                assertEquals("current patched source\n", Files.readString(input.resolve("entry.txt")));
                if (!outside) {
                    assertEquals("current patched source\n", Files.readString(mutable.resolve("source/entry.txt")));
                    assertTrue(!Files.exists(mutable.resolve("source/assembled-launcher")), "Recipe resets the whole previous output");
                    assertTrue(!Files.exists(mutable.resolve("source/samples")), "Only the selected subtree is copied");
                    assertEquals("shared sample\n", Files.readString(shared.resolve("sample.bin")), "Complete WTL_SOURCE remains available");
                }
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void companionBuildRejectsEveryUnsafeToolBeforeLaunchingForDifferentOwnerLanguages() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        Path tools = Files.createDirectory(root.resolve("tools"));
        Files.createDirectory(root.resolve("native"));
        for (String tool : List.of("cmake", "ninja", "cc", "c++", "readelf", "ldd"))
            Files.writeString(tools.resolve(tool), "#!/bin/sh\nprintf 'SHOULD_NOT_EXECUTE\\n'\n");
        String sourcePath = root.toString().replace('\\', '/');
        if (sourcePath.matches("^[A-Za-z]:/.*"))
            sourcePath = "/" + Character.toLowerCase(sourcePath.charAt(0)) + sourcePath.substring(2);
        var workload = new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload(
            gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentWorkload.ExecutionMode.DAEMON, true,
            gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCommand.primary(), "", List.of(),
            java.util.Optional.empty(), "", java.util.Optional.empty(), List.of(), List.of(
            new gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCompanion("native", "native",
                gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentCompanion.BuildType.CMAKE_SERVICE,
                ".w2l/bin/worker", "NATIVE_HELPER")));
        var requirements = List.of(
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.declared(
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.C, "11", "fixture",
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.PurposeType.LANGUAGE_TARGET),
            gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.declared(
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType.CPP, "20", "fixture",
                gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.ToolchainRequirement.PurposeType.LANGUAGE_TARGET));
        for (var owner : List.of(
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.PYTHON_SERVICE,
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.RUST_SERVICE)) {
            var facts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(
                root, "fixture", owner, owner == gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.PYTHON_SERVICE
                ? gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.PYTHON_STDLIB
                : gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.CARGO_LOCKED,
                List.of(), List.of(), List.of()).withToolchains(requirements);
            for (String rejected : List.of("cmake", "ninja", "cc", "c++", "readelf", "ldd")) {
                String script = "set -eu\nsource=" + SafeBuildScriptEnvelope.shellQuote(sourcePath)
                    + "\nexport PATH=\"$source/tools:/usr/bin:/bin\"\n/usr/bin/chmod +x \"$source\"/tools/*\n"
                    + "mutable=/unrelated-controlled-workspace\nrejected=$(/usr/bin/readlink -f -- \"$(builtin command -v "
                    + SafeBuildScriptEnvelope.shellQuote(rejected) + ")\")\n" + """
                    fixture_stat() {
                      case "$2" in
                        %u) printf '0' ;;
                        %a) if [ "$4" = "$rejected" ]; then printf '777'; else printf '755'; fi ;;
                        *) return 1 ;;
                      esac
                    }
                    run() { printf 'SHOULD_NOT_LAUNCH\\n'; exit 97; }
                    """ + SafeBuildScriptEnvelope.commandGuards().replace("/usr/bin/stat", "fixture_stat")
                    + CompanionBuildScript.render(workload, facts);
                var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
                try {
                    try (var input = process.getOutputStream()) {
                        input.write(script.getBytes(StandardCharsets.UTF_8));
                    }
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(65, process.exitValue(), owner + ": " + rejected + ": " + output);
                    assertEquals("BUILD_TOOL_REJECTED=" + rejected + "\n", output);
                } finally {
                    if (process.isAlive()) process.destroyForcibly().waitFor();
                }
            }
        }
    }

    @Test
    void cmakeUsesOnlyReviewedCompilerLanguagesAndPinnedToolArguments() {
        var c = gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType.C;
        var cpp = gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType.CPP;
        for (var languages : List.of(java.util.Set.of(c), java.util.Set.of(cpp), java.util.Set.of(c, cpp))) {
            String script = SafeBuildScriptEnvelope.cmakeToolArguments(languages);
            assertTrue(script.contains("require_build_tool ninja\n") && script.contains("-DCMAKE_MAKE_PROGRAM=$cmake_ninja"));
            assertEquals(languages.contains(c), script.contains("require_build_tool cc\n"));
            assertEquals(languages.contains(c), script.contains("-DCMAKE_C_COMPILER=$cmake_c"));
            assertEquals(languages.contains(cpp), script.contains("require_build_tool c++\n"));
            assertEquals(languages.contains(cpp), script.contains("-DCMAKE_CXX_COMPILER=$cmake_cpp"));
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> SafeBuildScriptEnvelope.cmakeToolArguments(java.util.Set.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> SafeBuildScriptEnvelope.cmakeToolArguments(java.util.Set.of(
                gold.debug.windowstolinux.mod.deployment.service.core.model.language.SourceLanguageType.PYTHON)));
    }

    @Test
    void identifiesMissingOrRejectedKnownToolsWithoutExecutingThemOrPrintingUnknownValues() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        for (String tool : List.of("php", "java", "node", "python3", "cargo", "dotnet", "ruby", "cmake", "ninja")) {
            for (String scenario : List.of("missing", "rejected", "safe", "unknown")) {
                String script = "set -eu\nexport PATH=/nonexistent-wtl-tool-directory\nscenario=" + scenario + "\n"
                    + "tool=" + tool + "\n" + SafeBuildScriptEnvelope.commandGuards() + """
                    verify_build_command() { [ "$scenario" != rejected ]; }
                    if [ "$scenario" != missing ]; then eval "$tool() { printf 'SHOULD_NOT_EXECUTE\\n'; }"; fi
                    if [ "$scenario" = unknown ]; then require_build_tool protected-fixture-value; else require_build_tool "$tool"; fi
                    """;
                var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
                try {
                    try (var input = process.getOutputStream()) {
                        input.write(script.getBytes(StandardCharsets.UTF_8));
                    }
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(switch (scenario) {
                        case "missing" -> 69;
                        case "rejected" -> 65;
                        case "unknown" -> 64;
                        default -> 0;
                    }, process.exitValue(), output);
                    assertEquals(switch (scenario) {
                        case "missing" -> "BUILD_MISSING_TOOL=" + tool + "\n";
                        case "rejected" -> "BUILD_TOOL_REJECTED=" + tool + "\n";
                        default -> "";
                    }, output);
                } finally {
                    if (process.isAlive()) process.destroyForcibly().waitFor();
                }
            }
        }
    }

    @Test
    void reportsRecipeIdentityBeforeAnActualBuildFailureWithoutReportingSuccess() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        var facts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(root, "fixture",
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.NODE_SERVICE,
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.NPM, List.of(), List.of(), List.of());
        var workspace = new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace("fixture", "a".repeat(64));
        String script = SafeBuildScriptEnvelope.wrap(facts, workspace,
                gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(),
                "printf 'COMPILER_FAILURE\\n'\nfalse\nprintf 'SHOULD_NOT_RUN\\n'")
            .replace(workspace.candidateRoot(), root.resolve("candidate").toString().replace('\\', '/'));
        String setup = """
            export PATH=/usr/bin:/bin
            test() { if [ "$1" = -f ]; then return 0; fi; builtin test "$@"; }
            sha256sum() { printf '%s\\n' '%s'; }
            tar() { case "$1" in -tzf) echo entry ;; -tvzf) echo '-file' ;; --extract) : ;; esac; }
            """.formatted("%s", "a".repeat(64));
        var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
        try {
            try (var input = process.getOutputStream()) {
                input.write((setup + script).getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(1, process.exitValue(), output);
            assertTrue(output.contains("BUILD_TOOL=NPM\n") && output.contains("COMPILER_FAILURE\n"), output);
            assertTrue(!output.contains("SHOULD_NOT_RUN"), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @Test
    void checksHostCommandAndAllAncestorsWhilePermittingConfinedProjectTools() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        Path executable = root.resolve("tool.sh");
        Files.writeString(executable, "#!/bin/sh\nexit 0\n");
        String path = executable.toString().replace('\\', '/');
        if (path.matches("^[A-Za-z]:/.*")) path = "/" + Character.toLowerCase(path.charAt(0)) + path.substring(2);
        for (String scenario : List.of("safe", "unowned", "writable-file", "writable-parent", "writable-root", "project")) {
            String script = "set -eu\nexport PATH=/usr/bin:/bin\nfile=" + SafeBuildScriptEnvelope.shellQuote(path)
                + "\n/usr/bin/chmod +x -- \"$file\"\nresolved=$(/usr/bin/readlink -f -- \"$file\")\n"
                + "unsafe_parent=${resolved%/*}\nmutable=/fixture/mutable\nscenario=" + scenario + "\n"
                + """
                if [ "$scenario" = project ]; then mutable="$unsafe_parent"; fi
                fixture_stat() {
                  case "$2" in
                    %u) if [ "$scenario" = unowned ] || [ "$scenario" = project ]; then printf '1000'; else printf '0'; fi ;;
                    %a) if { [ "$scenario" = writable-file ] && [ "$4" = "$resolved" ]; } || { [ "$scenario" = writable-parent ] && [ "$4" = "$unsafe_parent" ]; } || { [ "$scenario" = writable-root ] && [ "$4" = / ]; }; then printf '777'; else printf '755'; fi ;;
                    *) return 1 ;;
                  esac
                }
                """
                + SafeBuildScriptEnvelope.commandGuards().replace("/usr/bin/stat", "fixture_stat")
                + "if verify_build_command \"$file\"; then exit 0; else exit 1; fi\n";
            var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(script.getBytes(StandardCharsets.UTF_8));
                }
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(List.of("safe", "project").contains(scenario) ? 0 : 1, process.exitValue(), scenario + ": " + output);
            } finally {
                if (process.isAlive()) process.destroyForcibly().waitFor();
            }
        }
    }

    @Test
    void executionGuardsReportOnlyKnownBasenamesAndNeverRetryOrExposeRejectedPaths() throws Exception {
        String bash = System.getProperty("managed.test.bash", "/bin/bash");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of(bash)));
        var facts = new gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts(root, "fixture",
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind.NODE_SERVICE,
            gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentBuildToolType.NPM, List.of(), List.of(), List.of());
        var workspace = new gold.debug.windowstolinux.mod.deployment.service.core.model.build.DeploymentRemoteWorkspace("fixture", "a".repeat(64));
        for (String entry : List.of("run", "retry_run 3")) {
            for (String tool : List.of("python3.12", "ninja", "protected-fixture-value")) {
                String command = "verify_build_command() { return 1; }\n" + entry + " /private-fixture-tools/" + tool
                    + "\nprintf 'SHOULD_NOT_RUN\\n'";
                String script = SafeBuildScriptEnvelope.wrap(facts, workspace,
                        gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().build().limits(), command)
                    .replace(workspace.candidateRoot(), root.resolve("candidate").toString().replace('\\', '/'));
                String setup = """
                    export PATH=/usr/bin:/bin
                    test() { if [ "$1" = -f ]; then return 0; fi; builtin test "$@"; }
                    sha256sum() { printf '%s\\n' '%s'; }
                    tar() { case "$1" in -tzf) echo entry ;; -tvzf) echo '-file' ;; --extract) : ;; esac; }
                    """.formatted("%s", "a".repeat(64));
                var process = new ProcessBuilder(bash, "--noprofile", "--norc", "-s").redirectErrorStream(true).start();
                try {
                    try (var input = process.getOutputStream()) {
                        input.write((setup + script).getBytes(StandardCharsets.UTF_8));
                    }
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals(65, process.exitValue(), output);
                    if (tool.equals("protected-fixture-value"))
                        assertTrue(!output.contains("BUILD_MISSING_TOOL="), output);
                    else
                        assertTrue(output.contains("BUILD_MISSING_TOOL=" + (tool.equals("python3.12") ? "python3" : tool) + "\n"), output);
                    for (String hidden : List.of("private-fixture-tools", "protected-fixture-value", "BUILD_RETRY=", "SHOULD_NOT_RUN"))
                        assertTrue(!output.contains(hidden), output);
                } finally {
                    if (process.isAlive()) process.destroyForcibly().waitFor();
                }
            }
        }
    }
}
