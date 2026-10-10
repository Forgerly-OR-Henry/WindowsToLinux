package gold.debug.windowstolinux.mod.deployment.service.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.*;
import gold.debug.windowstolinux.mod.deployment.service.deploy.build.script.SafeBuildScriptEnvelope;
import org.junit.jupiter.api.*;

class DeploymentAgentRuntimeValidationTest {
    private final ObjectMapper json = new ObjectMapper();

    private final List<Path> owned = new ArrayList<>();

    @Test
    void nativeValidationRequiresAssessmentAndRejectsCallerChosenExecutableFields() throws Exception {
        var args = json.createObjectNode().put("component", "cli");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("validate_runtime", args));
        args.putObject("assessment").put("suitability", "Current original invocation facts").put("differences", "No manual result substituted")
            .put("resolution", "RESOLVED").put("decision", "Verify exact current declaration").putArray("basis").add("o1");
        var assessed = assertInstanceOf(DeploymentAgentProposal.Assessed.class, DeploymentAgentProtocol.parse("validate_runtime", args));
        assertEquals("cli", assertInstanceOf(DeploymentAgentProposal.ValidateRuntime.class, assessed.action()).component());
        assertTrue(DeploymentAgentProtocol.critical("validate_runtime"));
        assertFalse(DeploymentAgentProtocol.available("validate_runtime", Map.of()));
        assertTrue(DeploymentAgentProtocol.available("validate_runtime", Map.of("components", List.of("cli"))));
        assertEquals(gold.debug.windowstolinux.mod.deployment.ai.agent.DeploymentToolScheduler.Execution.EXCLUSIVE,
            DeploymentAgentProtocol.execution("validate_runtime"));
        for (String field : List.of("script", "entrypoint", "arguments", "workingDirectory", "expected", "seconds")) {
            var invalid = args.deepCopy().put(field, "caller-selected");
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentProtocol.parse("validate_runtime", invalid));
        }
    }

    @Test
    void deploymentBindingsIndirectEntriesAndWebRemainOutsideCandidateEquivalence() throws Exception {
        assertEquals("", DeploymentAgentRuntimeValidation.inapplicable(part("tool", List.of(), "", "ok")));
        var longLiteral = part("tool", List.of("'".repeat(4000), "'".repeat(4000)), "", "ok");
        assertTrue(DeploymentAgentRuntimeValidation.inapplicable(longLiteral).contains("command budget"));
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentRuntimeValidation.script(longLiteral));
        for (String field : List.of("configuration", "secretReferences", "databases", "resources")) {
            var node = declaration("tool", List.of(), "", "ok");
            var component = (ObjectNode) node.path("components").get(0);
            switch (field) {
                case "configuration" ->
                    component.putArray(field).addObject().put("key", "RUNTIME_LABEL").put("value", "deployed");
                case "secretReferences" ->
                    component.putArray(field).addObject().put("identifier", "runtime-value").put("revision", 1);
                case "databases" -> component.putNull(field);
                case "resources" -> component.putArray(field).addObject().put("id", "store").put("path", "data/state")
                    .put("access", "READ_WRITE").put("schema", "state-v1").put("reversible", false)
                    .put("type", "FILE").put("seedFile", "").put("digest", "");
            }
            assertFalse(DeploymentAgentRuntimeValidation.inapplicable(DeploymentAgentPlanProtocol.parse(node).components().getFirst()).isEmpty(), field);
        }
        assertThrows(IllegalArgumentException.class, () -> part("", List.of(), "", "ok"));
        var container = declaration("tool", List.of(), "", "ok");
        ((ObjectNode) container.path("components").get(0)).put("backend", "DOCKER");
        assertFalse(DeploymentAgentRuntimeValidation.inapplicable(DeploymentAgentPlanProtocol.parse(container).components().getFirst()).isEmpty());
        var web = declaration("tool", List.of(), "", "ok");
        var component = (ObjectNode) web.path("components").get(0);
        component.put("mode", "DAEMON").putObject("health").put("type", "HTTP").put("url", "http://localhost:8080/health")
            .put("status", 200).put("timeout", 30);
        assertFalse(DeploymentAgentRuntimeValidation.inapplicable(DeploymentAgentPlanProtocol.parse(web).components().getFirst()).isEmpty());
    }

    @Test
    void literalArgumentsAndWorkingDirectoryExecuteWithoutShellInterpretation() throws Exception {
        String literal = "quoted ' $() ; * [ value";
        var part = part(".w2l/bin/tool", List.of(literal), "work dir", literal + "|work dir");
        var result = shell(part, "printf '%s|%s\\n' \"$1\" \"${PWD##*/}\"\n");
        assertEquals(0, result.exit(), result.stderr());
        assertEquals(literal + "|work dir\n", result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void nonzeroStderrOnlyLiteralMismatchAndOversizedStdoutCannotPass() throws Exception {
        var part = part("tool", List.of(), "", "needle*[literal]");
        var nonzero = shell(part, "printf '%s\\n' 'needle*[literal]'\nexit 7\n");
        assertEquals(7, nonzero.exit());
        var stderrOnly = shell(part, "printf '%s\\n' 'needle*[literal]' >&2\n");
        assertEquals(65, stderrOnly.exit());
        assertTrue(stderrOnly.stderr().contains("RUNTIME_VALIDATION_EXPECTED_STDOUT_MISSING"));
        var notLiteral = shell(part, "printf '%s\\n' needleZZliteral\n");
        assertEquals(65, notLiteral.exit());
        var overflow = shell(part("tool", List.of(), "", "x"), "head -c 65537 /dev/zero | tr '\\0' x\n");
        assertEquals(65, overflow.exit());
        assertEquals(65536, overflow.stdout().length());
        assertTrue(overflow.stderr().contains("RUNTIME_VALIDATION_STDOUT_TOO_LARGE"));
    }

    static ObjectNode declaration(String entry, List<String> arguments, String directory, String expected) throws Exception {
        var json = new ObjectMapper();
        var node = (ObjectNode) json.readTree("""
            {"applicationId":"controlled","explanation":"Current literal runtime declaration","components":[{"id":"cli","sourceRoot":".","dependencies":[],"evidence":["o1"],
            "backend":"PROCESS","mode":"ON_DEMAND","entrypoint":"tool","arguments":[],"workingDirectory":"",
            "health":{"type":"COMMAND","entrypoint":"tool","arguments":[],"expected":"ok","timeout":30},
            "ports":[],"resources":[],"configuration":[],"databases":[],"secretReferences":[]}],
            "applicationHealth":{"component":"cli","health":{"type":"COMMAND","entrypoint":"tool","arguments":[],"expected":"ok","timeout":30}}}
            """);
        var component = (ObjectNode) node.path("components").get(0);
        component.put("entrypoint", entry).put("workingDirectory", directory);
        var health = (ObjectNode) component.path("health");
        health.put("entrypoint", entry).put("expected", expected);
        var values = health.putArray("arguments");
        arguments.forEach(values::add);
        ((ObjectNode) node.path("applicationHealth")).set("health", health.deepCopy());
        return node;
    }

    private DeploymentAgentPlan.Part part(String entry, List<String> arguments, String directory, String expected) throws Exception {
        return DeploymentAgentPlanProtocol.parse(declaration(entry, arguments, directory, expected)).components().getFirst();
    }

    private record ShellResult(int exit, String stdout, String stderr) {
    }

    private ShellResult shell(DeploymentAgentPlan.Part part, String program) throws Exception {
        Path bash = List.of(Path.of("E:/Program/Git/bin/bash.exe"), Path.of("C:/Program Files/Git/bin/bash.exe"), Path.of("/bin/bash"))
            .stream().filter(Files::isRegularFile).findFirst().orElse(null);
        Assumptions.assumeTrue(bash != null, "Local Bash required for exact candidate script execution");
        Path workspace = Path.of("").toAbsolutePath().normalize();
        while (workspace != null && !Files.isRegularFile(workspace.resolve("src/mod/deployment/mod.json")))
            workspace = workspace.getParent();
        assertNotNull(workspace);
        Path base = Files.createDirectories(workspace.resolve(".ai-workspace/tmp"));
        Path fixture = Files.createTempDirectory(base, "agent-runtime-validation-").toAbsolutePath().normalize();
        assertTrue(fixture.startsWith(base.toAbsolutePath().normalize()));
        owned.add(fixture);
        String entry = ((gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentHealthCheck.Command) part.runtime().healthCheck()).command().entrypoint();
        String body = "set -euo pipefail\nWTL_OUTPUT=\"$(pwd -P)/output\"\nmkdir -p \"$WTL_OUTPUT\"\n"
            + "mkdir -p \"$(dirname -- \"$WTL_OUTPUT\"/" + SafeBuildScriptEnvelope.shellQuote(entry) + ")\" \"$WTL_OUTPUT\"/"
            + SafeBuildScriptEnvelope.shellQuote(part.runtime().workload().workingDirectory().isEmpty() ? "." : part.runtime().workload().workingDirectory()) + "\n"
            + "printf '%s' " + SafeBuildScriptEnvelope.shellQuote("#!/bin/bash\n" + program) + " >\"$WTL_OUTPUT\"/" + SafeBuildScriptEnvelope.shellQuote(entry) + "\n"
            + "chmod 700 -- \"$WTL_OUTPUT\"/" + SafeBuildScriptEnvelope.shellQuote(entry) + "\n"
            + DeploymentAgentRuntimeValidation.script(part);
        Path stdout = fixture.resolve("stdout.txt"), stderr = fixture.resolve("stderr.txt");
        var builder = new ProcessBuilder(bash.toString(), "--noprofile", "--norc", "-s").directory(fixture.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().remove("BASH_ENV");
        builder.environment().remove("ENV");
        builder.environment().put("PATH", "/usr/bin:/bin");
        var process = builder.start();
        try {
            try (var input = process.getOutputStream()) {
                input.write(body.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Local candidate fixture timeout");
            return new ShellResult(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @AfterEach
    void removeOwnedFixtures() throws Exception {
        for (Path path : owned) {
            Path resolved = path.toRealPath();
            assertEquals(path, resolved);
            assertTrue(resolved.getParent().getFileName().toString().equals("tmp") && resolved.getFileName().toString().startsWith("agent-runtime-validation-"));
            try (var files = Files.walk(resolved)) {
                for (Path child : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(child);
            }
        }
    }
}
