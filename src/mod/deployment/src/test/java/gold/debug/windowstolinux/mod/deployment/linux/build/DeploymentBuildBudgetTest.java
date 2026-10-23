package gold.debug.windowstolinux.mod.deployment.linux.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Preserves one remote execution allowance across candidate attempts. / 在候选尝试之间保留同一远端执行预算。 */
class DeploymentBuildBudgetTest {
    /** Only charged remote execution reduces the shared allowance. / 仅实际计入的远端执行消耗共享预算。 */
    @Test
    void cumulativeExecutionTimeDoesNotResetBetweenCandidates() {
        var budget = new DeploymentBuildBudget(120);
        assertEquals(120, budget.remainingSeconds());
        budget.charge(Duration.ofSeconds(45).toNanos());
        assertEquals(75, budget.remainingSeconds());
        budget.charge(Duration.ofSeconds(50).toNanos());
        assertEquals(25, budget.remainingSeconds());
        budget.charge(Duration.ofSeconds(30).toNanos());
        assertEquals(0, budget.remainingSeconds());
    }
}
