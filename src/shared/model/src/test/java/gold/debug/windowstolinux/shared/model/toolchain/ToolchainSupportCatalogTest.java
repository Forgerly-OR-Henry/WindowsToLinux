package gold.debug.windowstolinux.shared.model.toolchain;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainEcosystemType.*;
import static gold.debug.windowstolinux.shared.model.toolchain.ToolchainRequirement.PurposeType.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolchainSupportCatalogTest {
    private final ToolchainSupportCatalog catalog = ToolchainSupportCatalog.defaults();
    private ToolchainRequirement request(ToolchainEcosystemType ecosystem, String version) {
        return ToolchainRequirement.declared(ecosystem, version, "fixture", BUILD);
    }
    private List<String> candidates(ToolchainEcosystemType ecosystem, String version) {
        return catalog.candidates(request(ecosystem, version)).stream().map(ToolchainSupportCatalog.Branch::version).toList();
    }

    @Test void recognizesPastFutureAndPreviewWithoutApprovingThem() {
        for (var ecosystem : ToolchainEcosystemType.values()) {
            for (String version : List.of("0.1", "999.1.2", "999.1.2-rc1"))
                assertTrue(request(ecosystem, version).version().isPresent(), ecosystem + " " + version);
        }
        assertFalse(catalog.permits(ToolchainVersion.parse(JAVA, "16").orElseThrow()));
        assertTrue(candidates(JAVA, "99").isEmpty());
        assertEquals(ToolchainRequirement.ConstraintType.UNRESOLVED, request(KOTLIN, "${kotlin.version}").constraint());
        assertTrue(candidates(RUST, "nightly").isEmpty());
    }
    @Test void choosesOnlyTwoHigherBranchesAndNeverChangesAnExactSupportedBranch() {
        assertEquals(List.of("17", "21"), candidates(JAVA, "16"));
        assertEquals(List.of("25"), candidates(JAVA, "22"));
        assertEquals(List.of("16", "18"), candidates(NODE, "15"));
        assertEquals(List.of("10"), candidates(DOTNET, "9.0.100"));
        assertEquals(List.of("1.9"), candidates(KOTLIN, "1.9.10"));
        assertEquals(List.of("1.18", "1.19"), candidates(GO, ">=1.18.5"));
    }
    @Test void parsesJavaEightAliasesAndOrdersUpdatesNumerically() {
        var legacy = ToolchainVersion.parse(JAVA, "1.8.0_202").orElseThrow();
        assertTrue(legacy.sameRelease(ToolchainVersion.parse(JAVA, "8u202").orElseThrow()));
        assertTrue(legacy.compareTo(ToolchainVersion.parse(JAVA, "8u99").orElseThrow()) > 0);
        assertTrue(ToolchainVersion.parse(PYTHON, "3.10").orElseThrow()
                .compareTo(ToolchainVersion.parse(PYTHON, "3.9").orElseThrow()) > 0);
    }
    @Test void preservesSourceAndExactPatchMinimumAndSeriesSemantics() {
        assertTrue(request(GO, ">=1.18.5").accepts(ToolchainVersion.parse(GO, "go1.18.7").orElseThrow()));
        assertFalse(request(GO, ">=1.18.5").accepts(ToolchainVersion.parse(GO, "1.18.1").orElseThrow()));
        assertFalse(request(KOTLIN, "1.9.20").accepts(ToolchainVersion.parse(KOTLIN, "1.9.21").orElseThrow()));
        assertTrue(request(PYTHON, "3.8").accepts(ToolchainVersion.parse(PYTHON, "3.8.20").orElseThrow()));
        assertEquals("${kotlin.version}", request(KOTLIN, "${kotlin.version}").declaration());
    }
    @Test void checksEveryCatalogBoundaryAndStandardOrdering() {
        for (var ecosystem : ToolchainEcosystemType.values()) {
            var branches = catalog.branches(ecosystem);
            assertFalse(branches.isEmpty());
            for (var branch : List.of(branches.getFirst(), branches.get(branches.size()/2), branches.getLast()))
                assertTrue(catalog.permits(branch.identity()));
        }
        assertEquals(List.of("99", "11", "17"), catalog.branches(C).stream().map(ToolchainSupportCatalog.Branch::version).toList());
        assertFalse(catalog.permits(ToolchainVersion.parse(NODE, "24.0.0-rc1").orElseThrow()));
    }
    @Test void appendsFutureBranchWithoutChangingVersionOrCandidateLogic() {
        var branches = new ArrayList<>(catalog.branches());
        branches.add(new ToolchainSupportCatalog.Branch(JAVA, "29", ToolchainSupportCatalog.ReleaseType.LTS,
                ToolchainSupportCatalog.MaintenanceStatus.MAINTAINED, ToolchainSupportCatalog.InstallationType.TEMURIN));
        var future = new ToolchainSupportCatalog("test-future", branches);
        assertEquals(List.of("29"), future.candidates(request(JAVA, "28")).stream().map(ToolchainSupportCatalog.Branch::version).toList());
        assertFalse(catalog.permits(ToolchainVersion.parse(JAVA, "29.0.1").orElseThrow()));
        assertTrue(future.permits(ToolchainVersion.parse(JAVA, "29.0.1").orElseThrow()));
        assertThrows(IllegalArgumentException.class, () -> new ToolchainSupportCatalog("duplicate", List.of(branches.getFirst(), branches.getFirst())));
    }
}
