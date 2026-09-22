package gold.debug.windowstolinux.shared.model.toolchain;

import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ToolchainBindingCodecTest {
    @Test
    void freezesOriginalDeclarationAndActualIdentityAcrossCatalogUpdates() {
        var requirement = ToolchainRequirement.declared(JAVA, "1.8", "pom.xml",
                ToolchainRequirement.PurposeType.LANGUAGE_TARGET);
        var set = new ResolvedToolchainSet("test-old",
                List.of(new ResolvedToolchainSet.Selection(requirement,
                        ToolchainVersion.parse(JAVA, "8u202").orElseThrow(),
                        "/usr/local/lib/windowstolinux/toolchains/versions/java-" + "a".repeat(64),
                        ResolvedToolchainSet.OriginType.MANAGED, "https://api.adoptium.net/fixture", "b".repeat(64))));
        String encoded = ToolchainBindingCodec.encode(set);
        assertEquals(set, ToolchainBindingCodec.decode(encoded));
        assertEquals("1.8", ToolchainBindingCodec.decode(encoded).selections().getFirst().requirement().declaration());
        assertEquals(ToolchainBindingCodec.identity(set),
                ToolchainBindingCodec.identity(ToolchainBindingCodec.decode(encoded)));
        assertNotEquals(ToolchainBindingCodec.identity(set),
                ToolchainBindingCodec.identity(new ResolvedToolchainSet("test-new", set.selections())));
        assertThrows(IllegalArgumentException.class,
                () -> ToolchainBindingCodec.decode(encoded.replace("WTL-TOOLS-1", "WTL-TOOLS-99")));
        assertThrows(IllegalArgumentException.class,
                () -> ToolchainBindingCodec.decode(encoded.replace("/versions/java-", "/../java-")));
    }

    @Test
    void resolvesOfficialPatchesWithinReviewedBranches() {
        var catalog = ToolchainSupportCatalog.defaults();
        var policy = new ToolchainSelectionPolicy(catalog);
        for (var ecosystem : List.of(JAVA, NODE, PYTHON, DOTNET, KOTLIN, GO, RUST, PHP, RUBY)) {
            var branches = catalog.branches(ecosystem);
            for (var branch : List.of(branches.getFirst(), branches.get(branches.size() / 2), branches.getLast())) {
                var req = ToolchainRequirement.declared(ecosystem, branch.version(), "fixture",
                        ToolchainRequirement.PurposeType.BUILD);
                String base = branch.version() + (ecosystem.branchSegments() == 1 ? ".0" : "");
                var first = ToolchainVersion.parse(ecosystem, base + ".2").orElseThrow();
                var last = ToolchainVersion.parse(ecosystem, base + ".12").orElseThrow();
                var preview = ToolchainVersion.parse(ecosystem, base + ".13-rc1").orElseThrow();
                assertEquals(last, policy.resolve(req, branch, List.of(first, preview, last)),
                        ecosystem + branch.version());
            }
        }
        var minimum = ToolchainRequirement.declared(GO, ">=1.18.99", "go.mod",
                ToolchainRequirement.PurposeType.LANGUAGE_TARGET);
        var candidates = catalog.candidates(minimum);
        assertThrows(IllegalArgumentException.class, () -> policy.resolve(minimum, candidates.getFirst(),
                List.of(ToolchainVersion.parse(GO, "1.18.10").orElseThrow())));
        assertEquals("1.19.1", policy
                .resolve(minimum, candidates.getLast(), List.of(ToolchainVersion.parse(GO, "1.19.1").orElseThrow()))
                .text());

    }
}
