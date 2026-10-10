package gold.debug.windowstolinux.mod.deployment.service.agent.review;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentHelperReviewContract;
import gold.debug.windowstolinux.mod.deployment.linux.helper.DeploymentManagedHelperBundle;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainCatalog;
import gold.debug.windowstolinux.mod.deployment.service.core.model.toolchain.DeploymentToolchainType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeploymentAgentToolchainPreparationEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

    private final ServerId server = new ServerId("toolchain-evidence-server");

    private String invocation(DeploymentToolchainType ecosystem) {
        String branch = DeploymentToolchainCatalog.defaults().branches(ecosystem).getFirst().version();
        return "'" + DeploymentManagedHelperBundle.PATH + "' prepare-toolchains 'prepare' '" + ecosystem.name()
            + "' '" + branch + "' '-' '60' '-'";
    }

    private ObjectNode facts(String script) {
        try (var request = new CommandRequest(script, new byte[0], Duration.ofSeconds(75), 65536)) {
            return json.valueToTree(Map.of("operation", "deployment.toolchain.prepare.v1",
                "installedHelperPath", DeploymentManagedHelperBundle.PATH,
                "installedHelperSha256", DeploymentManagedHelperBundle.EXPECTED_SHA256,
                "platformContractRevision", DeploymentHelperReviewContract.REVISION,
                "inputKind", "EMPTY", "serverTargets", List.of(server.id()),
                "resourceTargets", List.of(new ActionTarget(server.id(), "deployment-build", "/usr/local/lib/windowstolinux/toolchains")),
                "businessRevision", "current-route", "exactAction", RemoteActions.command(server, "current-route", request).exactAction()));
        }
    }

    @ParameterizedTest
    @EnumSource(value = DeploymentToolchainType.class, names = {"C", "CPP"}, mode = EnumSource.Mode.EXCLUDE)
    void everyDownloadEcosystemGetsTheSameCompleteExpandedProgramAndHonestEffects(DeploymentToolchainType ecosystem) throws Exception {
        var selected = DeploymentAgentToolchainPreparationEvidence.select(facts(DeploymentManagedHelperBundle.verifiedCommand(invocation(ecosystem)))).orElseThrow();
        String system = selected.systemSource();
        assertTrue(system.contains("SHIPPED_CATALOG = {\n"));
        assertTrue(system.contains("/usr/bin/python3 -I - \"$@\" <<'WTL_OFFICIAL_TOOLCHAINS'\n"));
        assertTrue(system.endsWith("\nWTL_OFFICIAL_TOOLCHAINS\n}\n"));
        String root = "/gold/debug/windowstolinux/mod/deployment/linux/helper/toolchain/";
        for (String resource : List.of("10-release-metadata.py", "20-installation-boundaries.py", "30-managed-installation.py", "40-binding-protocol.py")) {
            try (var input = DeploymentManagedHelperBundle.class.getResourceAsStream(root + resource)) {
                assertNotNull(input);
                String content = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (content.contains("# @compat:centos-repositories@\n")) {
                    try (var inserted = DeploymentManagedHelperBundle.class.getResourceAsStream(root + "centos-source-repositories.py")) {
                        assertNotNull(inserted);
                        content = content.replaceAll("(?m)^[ \\t]*# @compat:centos-repositories@\\n",
                            java.util.regex.Matcher.quoteReplacement(new String(inserted.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n")));
                    }
                }
                assertTrue(system.contains(content), "Complete expanded source must retain " + resource);
            }
        }
        assertFalse(system.contains("# @compat:"));
        assertTrue(system.contains("checksum_text(url + '.sha256')"));
        assertTrue(system.contains("if official.hexdigest() != expected:"));
        assertTrue(system.contains("str(source / 'install.sh')"));
        String meaning = selected.facts().get("meaning").toString();
        assertTrue(meaning.contains("checksum value have not been observed"));
        assertTrue(meaning.contains("does not verify a signature"));
        assertTrue(meaning.contains("not a full installed-tree rehash"));
        assertTrue(meaning.contains("not the project build sandbox"));
        for (var location : (List<?>) selected.facts().get("locations")) {
            var item = (Map<?, ?>) location;
            int first = ((Number) item.get("startLine")).intValue(), last = ((Number) item.get("endLine")).intValue();
            String[] lines = system.substring(system.indexOf("prepare_official_toolchains() {")).split("\n");
            assertTrue(lines[first - 1].startsWith(item.get("entry").toString()));
            assertTrue(last >= first && last <= lines.length);
        }
        assertEquals(ecosystem.name(), ((Map<?, ?>) selected.facts().get("invocation")).get("ecosystem"));
        assertEquals(DeploymentAgentToolchainPreparationEvidence.select(facts(DeploymentManagedHelperBundle.verifiedCommand(invocation(DeploymentToolchainType.JAVA)))).orElseThrow().systemSource(), system);
    }

    @Test
    void completeGuardAndCanonicalFrameRejectInlineEffectsAndAlternateSpellings() {
        String invocation = invocation(DeploymentToolchainType.RUST);
        String guarded = DeploymentManagedHelperBundle.verifiedCommand(invocation);
        for (String script : List.of(invocation, guarded.replace("-perm /022", "-perm /000"),
            DeploymentManagedHelperBundle.verifiedCommand(invocation + "; printf unsafe"),
            DeploymentManagedHelperBundle.verifiedCommand(invocation + "\n'" + DeploymentManagedHelperBundle.PATH + "' probe"),
            DeploymentManagedHelperBundle.verifiedCommand(invocation.replace(" prepare-toolchains ", " 'prepare-toolchains' ")),
            DeploymentManagedHelperBundle.verifiedCommand(invocation.replace("'prepare'", "prepare")),
            DeploymentManagedHelperBundle.verifiedCommand(invocation.replace("'60'", "'060'")),
            DeploymentManagedHelperBundle.verifiedCommand(invocation.replace("'-' '60'", "'file:///tmp/source' '60'"))))
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainPreparationEvidence.select(facts(script)));
    }

    @Test
    void existingTypedVersionSyntaxAdmitsJavaBuildSuffixAndOtherCanonicalStableForms() {
        for (var ecosystem : List.of(DeploymentToolchainType.JAVA, DeploymentToolchainType.NODE, DeploymentToolchainType.GO)) {
            String branch = DeploymentToolchainCatalog.defaults().branches(ecosystem).getFirst().version();
            String exact = branch + (branch.contains(".") ? ".1" : ".0.1") + (ecosystem == DeploymentToolchainType.JAVA ? "-b08" : "+8");
            String current = invocation(ecosystem).replace("'" + branch + "' '-'", "'" + branch + "' '" + exact + "'");
            var selected = DeploymentAgentToolchainPreparationEvidence.select(facts(DeploymentManagedHelperBundle.verifiedCommand(current))).orElseThrow();
            assertEquals(exact, ((Map<?, ?>) selected.facts().get("invocation")).get("exactVersion"));
        }
    }

    @Test
    void currentRequestEncodingRejectsNonemptyInputMalformedHeadersAndFalseTargets() {
        var original = facts(DeploymentManagedHelperBundle.verifiedCommand(invocation(DeploymentToolchainType.RUST)));
        String action = original.path("exactAction").asText();
        for (String altered : List.of(action.replace("timeout=PT1M15S", "timeout=PT75S"),
            action.replace("outputLimitBytes=65536", "outputLimitBytes=065536"),
            action.replace("stdinBytes=0", "stdinBytes=1"), action.replace("stdinSha256=", "stdinSha256=0"),
            action.replace("script:\n", "script:\n\n"), action + "\n")) {
            var current = original.deepCopy().put("exactAction", altered);
            assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainPreparationEvidence.select(current));
        }
        var changedTarget = original.deepCopy();
        ((ObjectNode) changedTarget.path("resourceTargets").get(0)).put("id", "/tmp/owned");
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainPreparationEvidence.select(changedTarget));
        var unknown = original.deepCopy().put("operation", "deployment.agent.helper.v1");
        assertTrue(DeploymentAgentToolchainPreparationEvidence.select(unknown).isEmpty());
        unknown.set(DeploymentAgentToolchainPreparationEvidence.FIELD, json.createObjectNode());
        assertThrows(IllegalArgumentException.class, () -> DeploymentAgentToolchainPreparationEvidence.select(unknown));
    }
}
