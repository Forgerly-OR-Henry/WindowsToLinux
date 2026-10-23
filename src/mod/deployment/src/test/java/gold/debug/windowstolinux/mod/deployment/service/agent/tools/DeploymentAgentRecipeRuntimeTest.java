package gold.debug.windowstolinux.mod.deployment.service.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlan;
import gold.debug.windowstolinux.mod.deployment.service.agent.protocol.DeploymentAgentPlanProtocol;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.DeploymentProjectFacts;
import gold.debug.windowstolinux.mod.deployment.service.core.model.project.kind.DeploymentProjectKind;
import gold.debug.windowstolinux.mod.deployment.service.core.model.runtime.DeploymentRuntimeDefinition;
import gold.debug.windowstolinux.mod.deployment.service.deploy.analyze.core.DeploymentAnalysisFlow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeploymentAgentRecipeRuntimeTest {
    @TempDir
    Path directory;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rubyAndPhpZeroPortRecipesReachExistingRuntimeValidationAndPreservePublication() throws Exception {
        var part = part();
        var ruby = rubyFacts();
        for (String mode : java.util.List.of("source", "bundle")) {
            var runtime = assertInstanceOf(DeploymentRuntimeDefinition.RubyService.class,
                DeploymentAgentRecipeRuntime.resolve(mode.equals("bundle") ? bundlerFacts() : ruby, part,
                    recipe("3.3.5", mode, "server.rb", 0)));
            assertEquals(0, runtime.servicePort());
            assertEquals(mode, runtime.artifactName());
            assertEquals("server.rb", runtime.entrypoint());
            assertEquals(part.runtime().workload(), runtime.workload());
        }
        var php = assertInstanceOf(DeploymentRuntimeDefinition.PhpService.class,
            DeploymentAgentRecipeRuntime.resolve(phpFacts(), part, recipe("8.3.5", "source", "server.php", 0)));
        assertEquals(0, php.servicePort());
        assertEquals("source", php.artifactName());
        assertEquals("server.php", php.entrypoint());
        assertEquals(part.runtime().workload(), php.workload());
    }

    @Test
    void rubyInvalidShapesPathsAndPortsKeepBoundedTypeSpecificRejection() throws Exception {
        var facts = rubyFacts();
        var part = part();
        var invalid = java.util.List.of(
            recipe("3.3.5", "bundle", "server.rb", 8080),
            recipe("3.3.5", "source", "ruby server.rb", 0),
            recipe("3.3.5", "source", "../server.rb", 0),
            recipe("3.3.5", "source", "server.rb", -1));
        var reasons = java.util.List.of("Ruby runtime must be one reviewed Bundler or CLI shape",
            "entrypoint must be a bounded safe relative path", "entrypoint must be a bounded safe relative path",
            "Expected integer for servicePort in [0, 65535]");
        for (int index = 0; index < invalid.size(); index++) {
            var input = invalid.get(index);
            var rejected = assertThrows(IllegalArgumentException.class, () -> DeploymentAgentRecipeRuntime.resolve(facts, part, input));
            String feedback = rejected.getMessage();
            assertTrue(feedback.contains("recipe for RUBY_SERVICE rejected:"), feedback);
            assertTrue(feedback.contains(reasons.get(index)), feedback);
            assertTrue(feedback.contains("primary=bundle or source"), feedback);
            assertTrue(feedback.contains("a .rb file with servicePort=0"), feedback);
            assertTrue(feedback.length() <= 1000);
            assertFalse(feedback.contains("ruby server.rb"));
            assertFalse(feedback.contains("../server.rb"));
            assertFalse(feedback.contains("3.3.5"), "Feedback must not echo submitted recipe values");
            assertEquals(feedback, gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentFeedback.safe("TOOL_prepare_tools", feedback));
        }
        assertThrows(IllegalArgumentException.class,
            () -> DeploymentAgentRecipeRuntime.resolve(phpFacts(), part, recipe("8.3.5", "public", "server.php", 0)));
    }

    @Test
    void numericRecipeRejectionDoesNotEchoSubmittedValue() throws Exception {
        Path root = Files.createDirectory(directory.resolve("static"));
        Files.writeString(root.resolve("index.html"), "<!doctype html><title>demo</title>\n");
        var facts = facts(root, DeploymentProjectKind.STATIC_SITE);
        String submitted = "private-unstructured-value";
        var rejected = assertThrows(IllegalArgumentException.class,
            () -> DeploymentAgentRecipeRuntime.resolve(facts, part(), recipe(submitted, "public", "", 0)));
        assertTrue(rejected.getMessage().contains("recipe for STATIC_SITE rejected:"));
        assertTrue(rejected.getMessage().contains("version must be the decimal major version required by this recipe"));
        assertTrue(rejected.getMessage().contains("primary=bounded relative build output directory"));
        assertFalse(rejected.getMessage().contains(submitted));
        assertTrue(rejected.getMessage().length() <= 1000);
    }

    private DeploymentProjectFacts rubyFacts() throws Exception {
        Path root = Files.createDirectory(directory.resolve("ruby"));
        Files.writeString(root.resolve("windowstolinux-ruby.properties"), "rubyVersion=3.3.5\nentrypoint=server.rb\n");
        Files.writeString(root.resolve("server.rb"), "require 'socket'\nputs 'ok'\n");
        return facts(root, DeploymentProjectKind.RUBY_SERVICE);
    }

    private DeploymentProjectFacts phpFacts() throws Exception {
        Path root = Files.createDirectory(directory.resolve("php"));
        Files.writeString(root.resolve("windowstolinux-php.properties"), "phpVersion=8.3.5\ndocumentRoot=source\nentrypoint=server.php\n");
        Files.writeString(root.resolve("server.php"), "<?php echo 'ok';\n");
        return facts(root, DeploymentProjectKind.PHP_SERVICE);
    }

    private DeploymentProjectFacts bundlerFacts() throws Exception {
        Path root = Files.createDirectory(directory.resolve("bundler"));
        Files.writeString(root.resolve(".ruby-version"), "3.3.5\n");
        Files.writeString(root.resolve("Gemfile"), "source 'https://rubygems.org'\n");
        Files.writeString(root.resolve("Gemfile.lock"), "BUNDLED WITH\n   2.5.0\n");
        Files.writeString(root.resolve("config.ru"), "run ->(env) { [200, {}, ['ok']] }\n");
        Files.writeString(root.resolve("server.rb"), "require 'socket'\nputs 'ok'\n");
        return facts(root, DeploymentProjectKind.RUBY_SERVICE);
    }

    private DeploymentProjectFacts facts(Path root, DeploymentProjectKind kind) {
        var report = new DeploymentAnalysisFlow().analyze(root, kind, "demo");
        assertTrue(report.rejections().isEmpty(), report.rejections().toString());
        var facts = report.facts().orElseThrow();
        assertEquals(kind, facts.projectType());
        return facts;
    }

    private ObjectNode recipe(String version, String primary, String secondary, int port) {
        return json.createObjectNode().put("version", version).put("primary", primary).put("secondary", secondary)
            .put("jvmTarget", "").put("servicePort", port);
    }

    private DeploymentAgentPlan.Part part() throws Exception {
        return DeploymentAgentPlanProtocol.parse(json.readTree("""
            {"applicationId":"demo","components":[{"id":"api","sourceRoot":".","dependencies":[],"evidence":["o1"],
             "backend":"PROCESS","mode":"DAEMON","entrypoint":"run.sh","arguments":[],"workingDirectory":"",
             "health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10},
             "ports":[],"resources":[],"configuration":[],"databases":[],"secretReferences":[]}],
             "applicationHealth":{"component":"api","health":{"type":"HTTP","url":"http://127.0.0.1:8080/","status":200,"timeout":10}}}
            """)).components().getFirst();
    }
}
