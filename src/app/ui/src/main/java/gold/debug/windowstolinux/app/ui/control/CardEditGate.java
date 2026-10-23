package gold.debug.windowstolinux.app.ui.control;

import java.awt.*;
import java.util.concurrent.*;

/** Traverses page owners once, sharing the same asynchronous departure gate. / 单次遍历页面所有者，共用异步离开门禁。 */
public final class CardEditGate {
    private CardEditGate() {
    }

    public static CompletionStage<Boolean> save(Component component) {
        if (component instanceof PendingCardEdits edits) return edits.saveBeforeLeave();
        CompletionStage<Boolean> result = CompletableFuture.completedFuture(true);
        if (component instanceof Container container)
            for (var child : container.getComponents())
                result = result.thenCompose(ok -> ok ? save(child) : CompletableFuture.completedFuture(false));
        return result;
    }

    public static boolean dirty(Component component) {
        if (component instanceof PendingCardEdits edits) return edits.hasPendingEdits();
        if (component instanceof Container container)
            for (var child : container.getComponents()) if (dirty(child)) return true;
        return false;
    }
}
