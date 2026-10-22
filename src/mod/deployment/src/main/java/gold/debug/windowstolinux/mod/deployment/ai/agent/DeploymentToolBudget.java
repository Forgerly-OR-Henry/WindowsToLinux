package gold.debug.windowstolinux.mod.deployment.ai.agent;

import java.util.*;
import java.util.function.IntConsumer;

import gold.debug.windowstolinux.appapi.ai.model.AiToolCall;

/** Shares one attempt budget across the main coordinator and its nested publication loops. / 主协调线程及嵌套发布循环共用一次工具尝试预算。 */
public final class DeploymentToolBudget implements AutoCloseable {
    private static final ThreadLocal<DeploymentToolBudget> CURRENT = new ThreadLocal<>();

    private final Thread coordinator = Thread.currentThread();

    private final Set<String> ids = new HashSet<>();

    private final int maximum;

    private int remaining;

    private final DeploymentToolBudget parent;

    private final IntConsumer accounting;

    private DeploymentToolBudget(int maximum, int alreadyUsed, DeploymentToolBudget parent, IntConsumer accounting) {
        if (maximum < 1 || maximum > gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().sharedToolAttempts()
            || alreadyUsed < 0 || alreadyUsed > maximum || CURRENT.get() != parent)
            throw new IllegalStateException("Invalid nested tool budget scope");
        this.parent = parent;
        this.accounting = Objects.requireNonNull(accounting);
        this.maximum = maximum;
        remaining = maximum - alreadyUsed;
        CURRENT.set(this);
    }

    public static DeploymentToolBudget open(int maximum) {
        return open(maximum, 0, used -> {});
    }

    /** Restores only the owning protocol's actual attempts and reports every subsequent charge. */
    public static DeploymentToolBudget open(int maximum, int alreadyUsed, IntConsumer accounting) {
        return new DeploymentToolBudget(maximum, alreadyUsed, null, accounting);
    }

    /** Owns a bounded standalone scope only when no enclosing coordinator owns one. / 仅在没有外层协调预算时持有独立有界作用域。
     * @return owned default scope, or null while the enclosing scope remains responsible / 自有默认作用域；已有外层预算时返回null且不回填 */
    public static DeploymentToolBudget openStandaloneIfAbsent() {
        return CURRENT.get() == null ? open(standaloneMaximum()) : null;
    }

    /** Owns an independent allowance while preserving the enclosing protocol's exact balance. */
    public static DeploymentToolBudget openIndependent(int maximum) {
        return new DeploymentToolBudget(maximum, 0, CURRENT.get(), used -> {});
    }

    public static int maximum() {
        var scope = CURRENT.get();
        return scope == null ? standaloneMaximum() : scope.maximum;
    }

    public static int remaining() {
        var scope = CURRENT.get();
        return scope == null ? standaloneMaximum() : scope.remaining;
    }

    private static int standaloneMaximum() {
        return gold.debug.windowstolinux.mod.deployment.config.DeploymentConfigLoader.current().ai().execution().standaloneToolAttempts();
    }

    public static boolean consume(List<AiToolCall> calls) {
        var scope = CURRENT.get();
        if (scope == null) return true;
        boolean accepted = calls.size() <= scope.remaining;
        scope.remaining = Math.max(0, scope.remaining - calls.size());
        for (var call : calls) if (!scope.ids.add(call.id())) accepted = false;
        scope.accounting.accept(scope.maximum - scope.remaining);
        return accepted;
    }

    @Override
    public void close() {
        if (Thread.currentThread() != coordinator || CURRENT.get() != this)
            throw new IllegalStateException("Tool budget scope owner changed");
        if (parent == null) CURRENT.remove();
        else CURRENT.set(parent);
    }
}
