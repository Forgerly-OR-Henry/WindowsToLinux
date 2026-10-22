package gold.debug.windowstolinux.mod.deployment.ai.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;
import org.junit.jupiter.api.Test;

class DeploymentToolBudgetTest {
    @Test
    void restoredAccountingSharesNestedAttemptsButIndependentApprovalCannotChargeOrRefillIt() {
        var charged = new ArrayList<Integer>();
        try (var shared = DeploymentToolBudget.open(6, 2, charged::add)) {
            assertEquals(4, DeploymentToolBudget.remaining());
            assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("main", "read", "{}"))));
            assertNull(DeploymentToolBudget.openStandaloneIfAbsent());
            assertFalse(DeploymentToolBudget.consume(List.of(new AiToolCall("main", "read", "{}"))));
            assertEquals(List.of(3, 4), charged);
            try (var approval = DeploymentToolBudget.openIndependent(3)) {
                assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("main", "request", "{}"))));
                assertEquals(2, DeploymentToolBudget.remaining());
                assertEquals(List.of(3, 4), charged);
            }
            assertEquals(2, DeploymentToolBudget.remaining());
            assertFalse(DeploymentToolBudget.consume(List.of(new AiToolCall("nested-a", "read", "{}"),
                new AiToolCall("nested-b", "read", "{}"), new AiToolCall("nested-c", "read", "{}"))));
            assertEquals(List.of(3, 4, 6), charged);
            assertEquals(0, DeploymentToolBudget.remaining());
            assertNull(DeploymentToolBudget.openStandaloneIfAbsent());
            assertEquals(0, DeploymentToolBudget.remaining());
        }
        assertThrows(IllegalStateException.class, () -> DeploymentToolBudget.open(6, -1, charged::add));
        assertThrows(IllegalStateException.class, () -> DeploymentToolBudget.open(6, 7, charged::add));
        try (var next = DeploymentToolBudget.open(6)) {
            assertEquals(6, DeploymentToolBudget.remaining());
            assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("main", "read", "{}"))));
        }
        assertEquals(List.of(3, 4, 6), charged);
    }

    @Test
    void configuredSharedAndStandaloneScopesRemainIndependentAndNestedLoopsCannotRefill() {
        try (var standalone = DeploymentToolBudget.openStandaloneIfAbsent()) {
            assertNotNull(standalone);
            assertEquals(256, DeploymentToolBudget.maximum());
            assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("standalone", "read", "{}"))));
            assertNull(DeploymentToolBudget.openStandaloneIfAbsent());
            assertEquals(255, DeploymentToolBudget.remaining());
        }
        try (var shared = DeploymentToolBudget.open(4096)) {
            assertEquals(4096, DeploymentToolBudget.maximum());
            assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("original", "read", "{}"))));
            assertNull(DeploymentToolBudget.openStandaloneIfAbsent());
            assertEquals(4095, DeploymentToolBudget.remaining());
            assertFalse(DeploymentToolBudget.consume(List.of(new AiToolCall("original", "read", "{}"))));
            assertEquals(4094, DeploymentToolBudget.remaining());
        }
    }

    @Test
    void nestedAttemptsAndDuplicateIdsConsumeOriginalBudgetAndScopeIsReleased() {
        try (var scope = DeploymentToolBudget.open(4)) {
            assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("main", "read", "{}"))));
            assertEquals(3, DeploymentToolBudget.remaining());
            assertFalse(DeploymentToolBudget.consume(List.of(new AiToolCall("main", "read", "{}"))));
            assertEquals(2, DeploymentToolBudget.remaining());
            assertFalse(DeploymentToolBudget.consume(List.of(new AiToolCall("a", "read", "{}"), new AiToolCall("b", "read", "{}"), new AiToolCall("c", "read", "{}"))));
            assertEquals(0, DeploymentToolBudget.remaining());
        }
        try (var next = DeploymentToolBudget.open(80)) {
            assertEquals(80, DeploymentToolBudget.remaining());
        }
    }

    @Test
    void explicitLargerScopeRetainsMaximumAndCannotReplenishAfterExhaustion() {
        assertEquals(256, DeploymentToolBudget.maximum());
        assertEquals(256, DeploymentToolBudget.remaining());
        try (var scope = DeploymentToolBudget.open(512)) {
            assertEquals(512, DeploymentToolBudget.maximum());
            var main = java.util.stream.IntStream.range(0, 320)
                .mapToObj(i -> new AiToolCall("main-" + i, "read", "{}")).toList();
            assertTrue(DeploymentToolBudget.consume(main));
            assertEquals(192, DeploymentToolBudget.remaining());
            assertFalse(DeploymentToolBudget.consume(List.of(new AiToolCall("main-0", "read", "{}"))));
            assertEquals(191, DeploymentToolBudget.remaining());
            var publication = java.util.stream.IntStream.range(0, 191)
                .mapToObj(i -> new AiToolCall("publication-" + i, "inspect", "{}")).toList();
            assertTrue(DeploymentToolBudget.consume(publication));
            assertEquals(0, DeploymentToolBudget.remaining());
            assertEquals(512, DeploymentToolBudget.maximum());
            assertFalse(DeploymentToolBudget.consume(List.of(new AiToolCall("overflow", "read", "{}"))));
            assertEquals(0, DeploymentToolBudget.remaining());
            assertThrows(IllegalStateException.class, () -> DeploymentToolBudget.open(512));
        }
        assertEquals(256, DeploymentToolBudget.maximum());
        assertEquals(256, DeploymentToolBudget.remaining());
        try (var scope = DeploymentToolBudget.open(80)) {
            assertEquals(80, DeploymentToolBudget.maximum());
            assertEquals(80, DeploymentToolBudget.remaining());
        }
    }

    @Test
    void unsupportedScopeLimitsAreRejectedWithoutOpeningAScope() {
        assertThrows(IllegalStateException.class, () -> DeploymentToolBudget.open(0));
        assertThrows(IllegalStateException.class, () -> DeploymentToolBudget.open(4097));
        assertEquals(256, DeploymentToolBudget.maximum());
        assertEquals(256, DeploymentToolBudget.remaining());
    }

    @Test
    void activeScopeCannotBeReplacedAndNewScopeCannotRetainOldIdsOrCredits() {
        try (var scope = DeploymentToolBudget.open(512)) {
            assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("same-id", "read", "{}"))));
            assertThrows(IllegalStateException.class, () -> DeploymentToolBudget.open(80));
            assertEquals(512, DeploymentToolBudget.maximum());
            assertEquals(511, DeploymentToolBudget.remaining());
        }
        try (var scope = DeploymentToolBudget.open(80)) {
            assertEquals(80, DeploymentToolBudget.maximum());
            assertTrue(DeploymentToolBudget.consume(List.of(new AiToolCall("same-id", "read", "{}"))));
            assertEquals(79, DeploymentToolBudget.remaining());
        }
        assertEquals(256, DeploymentToolBudget.maximum());
        assertEquals(256, DeploymentToolBudget.remaining());
    }
}
