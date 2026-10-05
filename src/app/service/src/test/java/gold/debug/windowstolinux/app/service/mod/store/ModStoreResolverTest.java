package gold.debug.windowstolinux.app.service.mod.store;

import static org.junit.jupiter.api.Assertions.*;
import static gold.debug.windowstolinux.app.service.mod.store.StoreDeclarations.*;

import java.util.*;
import java.util.concurrent.CancellationException;

import gold.debug.windowstolinux.app.service.mod.store.ModStoreSelection.*;
import org.junit.jupiter.api.Test;

class ModStoreResolverTest {
    @Test
    void diamondBacktracksUpperConsumerRatherThanCachingProviderFailure() throws Exception {
        var plan = solve(catalog(release("root", "1.0.0", Map.of("a", "[1.0.0,3.0.0)", "b", "1.0.0")),
                release("a", "2.0.0", Map.of("d", "2.0.0")), release("a", "1.0.0", Map.of("d", "1.0.0")),
                release("b", "1.0.0", Map.of("d", "1.0.0")), release("d", "1.0.0", Map.of()), release("d", "2.0.0", Map.of())),
            "root", Set.of(), Map.of());
        assertTrue(plan.valid(), plan.toString());
        assertEquals("1.0.0", selected(plan, "a"));
        assertEquals("1.0.0", selected(plan, "d"));
        assertEquals(1, plan.items().stream().filter(item -> item.key().owner().equals("d")).count());
    }

    @Test
    void transitiveJavaIncompatibilityBacktracksConsumer() throws Exception {
        var plan = solve(catalog(release("root", "1.0.0", Map.of("a", "[1.0.0,3.0.0)")),
            release("a", "2.0.0", Map.of("d", "2.0.0")), release("a", "1.0.0", Map.of("d", "1.0.0")),
            release("d", "2.0.0", Map.of(), Map.of(), 22), release("d", "1.0.0", Map.of())), "root", Set.of(), Map.of());
        assertTrue(plan.valid());
        assertEquals("1.0.0", selected(plan, "a"));
        assertEquals("1.0.0", selected(plan, "d"));
    }

    @Test
    void prereleaseAdmissionIsCheckedForEveryOriginalRange() throws Exception {
        var plan = solve(catalog(release("root", "1.0.0", Map.of("a", "1.0.0", "d", "[1.0.0-alpha,2.0.0)")),
                release("a", "1.0.0", Map.of("d", "[1.0.0,2.0.0)")), release("d", "1.0.0-beta", Map.of())),
            "root", Set.of(), Map.of());
        assertFalse(plan.valid());
        assertTrue(plan.conflicts().stream().anyMatch(c -> c.detail().contains("root@1.0.0") && c.detail().contains("a@1.0.0")));
    }

    @Test
    void selectedOptionalIsHardConstraintButUnselectedBranchIsAbsent() throws Exception {
        var sources = catalog(release("root", "1.0.0", Map.of("d", "1.0.0"), Map.of("extra", "1.0.0"), 21),
            release("extra", "1.0.0", Map.of("d", "2.0.0")), release("d", "1.0.0", Map.of()), release("d", "2.0.0", Map.of()));
        var base = solve(sources, "root", Set.of(), Map.of());
        assertTrue(base.valid());
        assertTrue(base.items().stream().noneMatch(item -> item.key().owner().equals("extra")));
        var selected = solve(sources, "root", Set.of(new OptionalKey("root", "extra")), Map.of());
        assertFalse(selected.valid());
        assertEquals(Set.of(new OptionalKey("root", "extra")), selected.selection().optionals());
    }

    @Test
    void highCycleAndMissingSourceBothBacktrackWithoutStaleEdges() throws Exception {
        for (String provider : List.of("root", "missing")) {
            var plan = solve(catalog(release("root", "1.0.0", Map.of("a", "[1.0.0,3.0.0)")),
                release("a", "2.0.0", Map.of(provider, "1.0.0")), release("a", "1.0.0", Map.of())), "root", Set.of(), Map.of());
            assertTrue(plan.valid());
            assertEquals("1.0.0", selected(plan, "a"));
            assertTrue(plan.relations().stream().noneMatch(r -> r.key().consumer().equals("a")));
        }
    }

    @Test
    void exactPinNeverFallsBackToAnotherVersion() throws Exception {
        var plan = solve(catalog(release("root", "1.0.0", Map.of("a", "[1.0.0,3.0.0)")),
                release("a", "2.0.0", Map.of("missing", "1.0.0")), release("a", "1.0.0", Map.of())),
            "root", Set.of(), Map.of("a", "2.0.0"));
        assertFalse(plan.valid());
        assertEquals("2.0.0", plan.selection().pins().get("a"));
    }

    @Test
    void changedConsumerMustKeepSelectedRelationAndUpdateExactRangeEvidence() throws Exception {
        var plan = solve(catalog(release("root", "1.0.0", Map.of("a", "[1.0.0,3.0.0)")),
            release("a", "2.0.0", Map.of()), release("a", "1.0.0", Map.of(), Map.of("extra", "1.0.0"), 21),
            release("extra", "1.0.0", Map.of())), "root", Set.of(new OptionalKey("a", "extra")), Map.of());
        assertTrue(plan.valid());
        assertEquals("1.0.0", selected(plan, "a"));
        var edge = plan.relations().stream().filter(r -> r.key().equals(new OptionalKey("a", "extra"))).findFirst().orElseThrow();
        assertEquals("1.0.0", edge.consumerVersion());
        assertEquals("1.0.0", edge.range());
        assertTrue(edge.selected());
    }

    @Test
    void unreachableOptionalIntentIsRejectedRatherThanDropped() throws Exception {
        var plan = solve(catalog(release("root", "1.0.0", Map.of())), "root", Set.of(new OptionalKey("unused", "extra")), Map.of());
        assertFalse(plan.valid());
        assertEquals("MOD_STORE_OPTIONAL_UNREACHABLE", plan.conflicts().getLast().code());
    }

    @Test
    void verifiedInstalledIdentityIsPreferredAndOnlyNecessaryRelaxationsRemain() throws Exception {
        var a1 = release("a", "1.0.0", Map.of("d", "1.0.0"));
        var x1 = release("x", "1.0.0", Map.of());
        var remote = catalog(release("root", "1.0.0", Map.of("a", "[1.0.0,3.0.0)", "d", "2.0.0", "x", "[1.0.0,3.0.0)")),
            a1, release("a", "2.0.0", Map.of("d", "2.0.0")), x1, release("x", "2.0.0", Map.of()),
            release("d", "1.0.0", Map.of()), release("d", "2.0.0", Map.of()));
        var sources = new ModStoreCatalog(1, BASE, remote.releases(), List.of(
            new ModStoreCatalog.Installed(a1.manifest(), "0".repeat(64), false, true),
            new ModStoreCatalog.Installed(x1.manifest(), "1".repeat(64), false, true)), List.of());
        var plan = solve(sources, "root", Set.of(), Map.of());
        assertTrue(plan.valid());
        assertEquals("2.0.0", selected(plan, "a"));
        assertEquals("1.0.0", selected(plan, "x"));
    }

    @Test
    void sameIdentityDifferentCompleteDeclarationCannotShadowRemoteRoot() throws Exception {
        var remote = release("root", "1.0.0", Map.of());
        var local = release("root", "1.0.0", Map.of("extra", "1.0.0"));
        var sources = new ModStoreCatalog(1, BASE, List.of(remote), List.of(
            new ModStoreCatalog.Installed(local.manifest(), "0".repeat(64), true, true)), List.of());
        var plan = solve(sources, "root", Set.of(), Map.of());
        assertFalse(plan.valid());
        assertEquals("MOD_STORE_DECLARATION_CONFLICT", plan.conflicts().getFirst().code());
    }

    @Test
    void verifiedLocalProviderDoesNotNeedRemoteCatalogEntry() throws Exception {
        var local = release("provider", "1.0.0", Map.of());
        var sources = new ModStoreCatalog(1, BASE, List.of(release("root", "1.0.0", Map.of("provider", "1.0.0"))), List.of(
            new ModStoreCatalog.Installed(local.manifest(), "0".repeat(64), false, true)), List.of());
        var plan = solve(sources, "root", Set.of(), Map.of());
        assertTrue(plan.valid());
        assertEquals(ModStorePlan.Source.INSTALLED, plan.items().getFirst().source());
    }

    @Test
    void cancellationAndBudgetDoNotClaimNoSolution() throws Exception {
        var sources = catalog(release("root", "1.0.0", Map.of("a", "1.0.0")), release("a", "1.0.0", Map.of()));
        var selection = new ModStoreSelection(new ReleaseKey("root", "1.0.0"), Set.of(), Map.of());
        assertThrows(CancellationException.class, () -> new ModStoreResolver(sources, selection, API, 21, () -> true, 100).resolve(2));
        var incomplete = new ModStoreResolver(sources, selection, API, 21, () -> false, 1).resolve(2);
        assertFalse(incomplete.valid());
        assertEquals("MOD_STORE_RESOLUTION_INCOMPLETE", incomplete.conflicts().getFirst().code());
        assertEquals("Finite search budget exhausted; no no-solution conclusion", incomplete.conflicts().getFirst().detail());
        assertTrue(incomplete.items().isEmpty());
        assertTrue(incomplete.relations().isEmpty());
    }
}
