package gold.debug.windowstolinux.mod.deployment.service.core.model.build;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

class DeploymentWorkspaceQuotaTest {
    private static final long GIB = 1024L * 1024 * 1024;

    private final DeploymentWorkspaceQuota.Policy policy = new DeploymentWorkspaceQuota.Policy(128 * GIB, GIB);

    private DeploymentWorkspaceQuota.Input input(String id, long source, long minimum, long preferred) {
        return new DeploymentWorkspaceQuota.Input(id, 1024, source, DeploymentWorkspaceQuota.Request.automatic(minimum, preferred));
    }

    @Test
    void actualSourceDemandAndHostCapacitySelectDifferentBudgets() {
        var small = DeploymentWorkspaceQuota.select(List.of(input("a", 128 * 1024 * 1024, 0, GIB)), 20 * GIB, policy, DeploymentWorkspaceQuota.Cost.AGENT).get("a");
        var large = DeploymentWorkspaceQuota.select(List.of(input("a", 2 * GIB, GIB, 4 * GIB)), 20 * GIB, policy, DeploymentWorkspaceQuota.Cost.AGENT).get("a");
        var constrained = DeploymentWorkspaceQuota.select(List.of(input("a", 2 * GIB, GIB, 4 * GIB)), 8 * GIB, policy, DeploymentWorkspaceQuota.Cost.AGENT).get("a");
        assertTrue(small.bytes() < large.bytes());
        assertTrue(constrained.bytes() < large.bytes());
        assertTrue(2 * constrained.bytes() + GIB <= 8 * GIB);
    }

    @Test
    void explicitFourGiBIsExactAndCannotBeClipped() {
        var explicit = new DeploymentWorkspaceQuota.Input("a", 1024, DeploymentWorkspaceQuota.UNIT, DeploymentWorkspaceQuota.Request.explicit(4 * GIB));
        assertEquals(4 * GIB, DeploymentWorkspaceQuota.select(List.of(explicit), 10 * GIB, policy, DeploymentWorkspaceQuota.Cost.AGENT).get("a").bytes());
        assertThrows(IllegalArgumentException.class, () -> DeploymentWorkspaceQuota.select(List.of(explicit), 8 * GIB, policy, DeploymentWorkspaceQuota.Cost.AGENT));
    }

    @Test
    void graphReservesAllOtherMinimumsAndHeadroomOnce() {
        var selected = DeploymentWorkspaceQuota.select(List.of(input("b", GIB, GIB, 6 * GIB), input("a", GIB, GIB, 6 * GIB)), 10 * GIB, policy, DeploymentWorkspaceQuota.Cost.AGENT);
        assertEquals(Set.of("a", "b"), selected.keySet());
        assertTrue(selected.values().stream().mapToLong(value -> 2 * value.bytes()).sum() + GIB <= 10 * GIB);
        assertTrue(selected.get("b").bytes() >= 2 * GIB);
    }

    @Test
    void sourceArithmeticUnknownAndOverflowRemainFailures() {
        assertThrows(ArithmeticException.class, () -> DeploymentWorkspaceQuota.sourceFloor(Long.MAX_VALUE, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> DeploymentWorkspaceQuota.sourceFloor(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> DeploymentWorkspaceQuota.select(List.of(input("a", GIB, GIB, GIB)), -1, policy, DeploymentWorkspaceQuota.Cost.AGENT));
        assertThrows(IllegalArgumentException.class, () -> DeploymentWorkspaceQuota.Request.explicit(DeploymentWorkspaceQuota.MAXIMUM + 1));
    }

    @Test
    void standardCostPreservesThreeArchiveCopies() {
        assertEquals(6 * GIB, DeploymentWorkspaceQuota.hostCost(2 * GIB, GIB, DeploymentWorkspaceQuota.Cost.STANDARD));
        assertEquals(2 * GIB, DeploymentWorkspaceQuota.hostCost(2 * GIB, GIB, DeploymentWorkspaceQuota.Cost.AGENT));
    }

    @Test
    void graphReportsExactCapacityForThreeExplicitFourGiBBudgets() {
        var inputs = List.of("backend", "frontend", "scorer").stream().map(id ->
            new DeploymentWorkspaceQuota.Input(id, 1024, DeploymentWorkspaceQuota.UNIT,
                DeploymentWorkspaceQuota.Request.explicit(4 * GIB))).toList();
        long observed = 2002104L * 4096;
        var refusal = assertThrows(DeploymentWorkspaceQuota.InsufficientCapacity.class,
            () -> DeploymentWorkspaceQuota.select(inputs, observed, policy, DeploymentWorkspaceQuota.Cost.STANDARD));
        assertEquals(observed, refusal.availableBytes());
        assertEquals(13 * GIB, refusal.requiredBytes());
    }
}
