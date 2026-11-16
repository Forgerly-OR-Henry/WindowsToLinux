package gold.debug.windowstolinux.mod.deployment.service.deploy.toolchain;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gold.debug.windowstolinux.appapi.linux.action.RemoteActions;
import gold.debug.windowstolinux.appapi.linux.command.CommandRequest;
import gold.debug.windowstolinux.appapi.server.model.ServerId;
import gold.debug.windowstolinux.appapi.task.action.ActionSemantics;
import gold.debug.windowstolinux.appapi.task.action.ActionTarget;
import gold.debug.windowstolinux.mod.deployment.config.DeploymentRemoteLayout;
import gold.debug.windowstolinux.mod.deployment.service.core.model.capability.DeploymentEcosystemToolType;
import org.junit.jupiter.api.Test;

final class DeploymentNativeAptDependenciesTest {
    private static final ServerId SERVER = new ServerId("native-apt");
    private static final String TASK = "00000000-0000-0000-0000-000000000001";
    private static final String OP = "2".repeat(32);
    private static final long SECONDS = 120;
    private static final DeploymentRemoteLayout LAYOUT = DeploymentRemoteLayout.original();
    private static final String PROFILE = "NATIVE_BUILD_APT_3";
    private static final String LEGACY = DeploymentToolchainDependencies.PROFILE;

    @Test
    void profileAndTargetsUseExactlyTheNamedMissingAptRoots() {
        var types = List.of(DeploymentEcosystemToolType.CMAKE, DeploymentEcosystemToolType.NINJA,
            DeploymentEcosystemToolType.C_COMPILER, DeploymentEcosystemToolType.CPP_COMPILER);
        var names = List.of("cmake", "ninja-build", "gcc", "g++");
        for (int mask = 1; mask <= 15; mask++) {
            var missing = EnumSet.noneOf(DeploymentEcosystemToolType.class);
            var expected = new ArrayList<String>();
            for (int index = 0; index < types.size(); index++) {
                if ((mask & (1 << index)) != 0) {
                    missing.add(types.get(index));
                    expected.add(names.get(index));
                }
            }
            String profile = DeploymentToolchainDependencies.nativeAptProfile(missing);
            assertEquals("NATIVE_BUILD_APT_" + mask, profile);
            assertEquals(expected, DeploymentToolchainDependencies.packages("APT", profile));
            assertThrows(IllegalArgumentException.class, () -> DeploymentToolchainDependencies.packages("DNF", profile));
            var expectedTargets = new HashSet<>(DeploymentToolchainDependencies.lifecycleTargets(SERVER, OP, LAYOUT));
            expectedTargets.add(new ActionTarget(SERVER.id(), "system-package-index", "APT:configured-authenticated-sources"));
            expectedTargets.add(new ActionTarget(SERVER.id(), "system-package-transaction", "APT:authenticated-dependency-closure:" + profile));
            for (String name : expected) expectedTargets.add(new ActionTarget(SERVER.id(), "system-package", name));
            assertEquals(expectedTargets, DeploymentToolchainDependencies.dependencyTargets(SERVER, profile, OP, LAYOUT));
        }
        assertThrows(IllegalArgumentException.class, () -> DeploymentToolchainDependencies.nativeAptProfile(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> DeploymentToolchainDependencies.nativeAptProfile(
            Set.of(DeploymentEcosystemToolType.CMAKE, DeploymentEcosystemToolType.JAVA)));
        for (String invalid : List.of("NATIVE_BUILD_APT_0", "NATIVE_BUILD_APT_03", "NATIVE_BUILD_APT_16",
                "NATIVE_BUILD_APT_3 ", "NATIVE_BUILD_APT_gcc", LEGACY))
            assertFalse(DeploymentToolchainDependencies.isScopedNativeAptProfile(invalid), invalid);
    }

    @Test
    void exactNativeScopeBindsSourceTargetsAndReceiptWithoutChangingLegacy() throws Exception {
        String source = DeploymentToolchainDependencies.dependencies(LAYOUT, PROFILE);
        String original = DeploymentToolchainDependencies.dependencies(LAYOUT);
        assertEquals(original, DeploymentToolchainDependencies.dependencies(LAYOUT, LEGACY));
        assertEquals(original, DeploymentToolchainDependencies.dependencies(LAYOUT, "PHP"));
        assertTrue(source.contains("\nDEPENDENCY_SCOPED_PROFILE = \"" + PROFILE + "\"\ndef dependency_native_apt_scope("));
        assertNotEquals(DeploymentToolchainDependencies.workerDigest(LAYOUT),
            DeploymentToolchainDependencies.workerDigest(LAYOUT, PROFILE));
        var targets = DeploymentToolchainDependencies.dependencyTargets(SERVER, PROFILE, OP, LAYOUT);
        var legacyTargets = DeploymentToolchainDependencies.dependencyTargets(SERVER, LEGACY, OP, LAYOUT);
        var json = new ObjectMapper();
        try (var request = request(PROFILE, source); var otherSource = request(PROFILE, original);
             var otherScope = request("NATIVE_BUILD_APT_4", source); var legacy = request(LEGACY, original)) {
            var facts = DeploymentToolchainDependencies.inspect(intent(request, targets), request, LAYOUT);
            assertEquals(PROFILE, facts.get("profile"));
            var transaction = json.valueToTree(facts.get("nativeTransaction"));
            assertEquals(json.valueToTree(Map.of("APT", List.of("cmake", "ninja-build"))), transaction.path("requestedPackages"));
            assertTrue(transaction.path("effects").asText().contains("upgrade installed transitive packages"));
            assertTrue(json.valueToTree(facts.get("locations")).toString().contains("dependency_native_apt_scope"));
            assertThrows(IllegalArgumentException.class, () ->
                DeploymentToolchainDependencies.inspect(intent(otherSource, targets), otherSource, LAYOUT));
            assertThrows(IllegalArgumentException.class, () ->
                DeploymentToolchainDependencies.inspect(intent(otherScope, targets), otherScope, LAYOUT));
            assertThrows(IllegalArgumentException.class, () ->
                DeploymentToolchainDependencies.inspect(intent(request, legacyTargets), request, LAYOUT));
            String receipt = DependencyReceiptFixture.receipt(request, "APT", LAYOUT);
            assertTrue(accepted(receipt, PROFILE));
            assertFalse(accepted(receipt.replace("\"APT\"", "\"DNF\""), PROFILE));
            assertFalse(accepted(receipt.replace("\"cmake\":\"1.0\"", "\"gcc\":\"1.0\""), PROFILE));
            assertFalse(accepted(receipt.replace(DeploymentToolchainDependencies.workerDigest(LAYOUT, PROFILE),
                DeploymentToolchainDependencies.workerDigest(LAYOUT)), PROFILE));
            assertFalse(accepted(receipt, LEGACY));
            assertTrue(accepted(DependencyReceiptFixture.receipt(legacy, "DNF", LAYOUT), LEGACY));
        }
    }

    private static CommandRequest request(String profile, String source) {
        return new CommandRequest(DeploymentToolchainDependencies.command(
            DeploymentToolchainDependencies.frame(profile, OP, SECONDS, SERVER, TASK)),
            source.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(SECONDS), 65536);
    }

    private static gold.debug.windowstolinux.appapi.task.action.ActionIntent intent(CommandRequest request,
            Set<ActionTarget> targets) {
        return RemoteActions.command(SERVER, "a".repeat(64), request)
            .withSemantics(new ActionSemantics(DeploymentToolchainDependencies.OPERATION, targets));
    }

    private static boolean accepted(String receipt, String profile) {
        return DeploymentToolchainDependencies.dependencyReceipt(receipt, profile, OP, SECONDS, SERVER, TASK, LAYOUT);
    }
}
