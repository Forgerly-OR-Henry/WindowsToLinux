package gold.debug.windowstolinux.app.ui.control;

import java.awt.Component;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;

/** One independently counted draft with a confirmed persistence baseline. / 独立计数草稿及已确认持久化基线。 */
public final class CardOrderSession<T> implements PendingCardEdits {
    /** Complete snapshot transaction. / 完整快照事务。 */
    @FunctionalInterface
    public interface Writer<T> {
        List<T> save(List<T> expected, List<T> requested) throws Exception;
    }

    private final Component owner;

    private final PageMessagePresenter messages;

    private final Writer<T> writer;

    private final Callable<List<T>> reader;

    private final Consumer<List<T>> applied;

    private final Runnable changed;

    private List<T> confirmed = List.of(), draft = List.of();

    private int changes;

    private boolean loaded, locked;

    private CompletableFuture<Boolean> saving;

    public CardOrderSession(Component owner, PageMessagePresenter messages, Writer<T> writer,
                            Callable<List<T>> reader, Consumer<List<T>> applied, Runnable changed) {
        this.owner = owner;
        this.messages = messages;
        this.writer = writer;
        this.reader = reader;
        this.applied = applied;
        this.changed = changed;
    }

    public List<T> draft() {
        return draft;
    }

    public List<T> confirmed() {
        return confirmed;
    }

    public int changes() {
        return changes;
    }

    public boolean busy() {
        return saving != null;
    }

    public boolean loaded() {
        return loaded;
    }

    public void setLocked(boolean value) {
        locked = value;
        changed.run();
    }

    /** A refresh cannot replace an unsaved draft. / 刷新不能替换未保存草稿。 */
    public void accept(List<T> value) {
        if (busy() || hasPendingEdits()) return;
        confirmed = draft = List.copyOf(value);
        loaded = true;
        changed.run();
    }

    /** Counts only an effective order change; membership edits only mark dirty. / 仅实际排序计数，成员编辑只标脏。 */
    public void edit(List<T> value, boolean reorder) {
        if (!loaded || locked || busy()) return;
        var next = List.copyOf(value);
        if (next.equals(draft)) return;
        draft = next;
        if (reorder) changes++;
        changed.run();
        if (reorder && changes % 3 == 0) save(false);
    }

    @Override
    public boolean hasPendingEdits() {
        return !draft.equals(confirmed);
    }

    /** Flushes without ending the current session, for refresh/member changes. / 刷新或成员变化前补存，不结束会话。 */
    public CompletionStage<Boolean> savePending() {
        return save(false);
    }

    @Override
    public CompletionStage<Boolean> saveBeforeLeave() {
        return save(true);
    }

    private CompletionStage<Boolean> save(boolean leave) {
        if (saving != null)
            return saving.thenCompose(ok -> ok && leave ? save(true) : CompletableFuture.completedFuture(ok));
        if (locked) return CompletableFuture.completedFuture(true);
        if (!hasPendingEdits()) {
            if (leave) changes = 0;
            return CompletableFuture.completedFuture(true);
        }
        var expected = confirmed;
        var requested = draft;
        var completion = new CompletableFuture<Boolean>();
        saving = completion;
        changed.run();
        DesktopTaskRunner.run(() -> writer.save(expected, requested), value -> {
            confirmed = draft = List.copyOf(value);
            if (leave) changes = 0;
            saving = null;
            applied.accept(confirmed);
            changed.run();
            completion.complete(true);
        }, failure -> {
            saving = null;
            changed.run();
            if (locked) {
                completion.complete(false);
                return;
            }
            if (conflict(failure)) {
                Object[] options = {messages.text("order.keepDraft"), messages.text("order.reload")};
                int choice = DesktopDialogs.showOptionDialog(owner, messages.text("order.conflict"),
                    messages.text("order.saveFailed"), JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
                    null, options, options[0]);
                if (choice == 1 && !locked) {
                    saving = completion;
                    changed.run();
                    DesktopTaskRunner.run(reader, value -> {
                        confirmed = draft = List.copyOf(value);
                        changes = 0;
                        loaded = true;
                        saving = null;
                        applied.accept(confirmed);
                        changed.run();
                        completion.complete(false);
                    }, reloadFailure -> {
                        saving = null;
                        changed.run();
                        error(reloadFailure);
                        completion.complete(false);
                    });
                    return;
                }
            } else error(failure);
            completion.complete(false);
        });
        return completion;
    }

    private void error(Exception failure) {
        DesktopDialogs.showMessageDialog(owner, messages.safe(failure), messages.text("order.saveFailed"), JOptionPane.ERROR_MESSAGE);
    }

    private static boolean conflict(Throwable failure) {
        for (Throwable value = failure; value != null; value = value.getCause())
            if (value instanceof SQLException sql && "40001".equals(sql.getSQLState())) return true;
            else if (value instanceof gold.debug.windowstolinux.appapi.task.result.ApiException api
                && java.util.Set.of("ORDER_BASELINE_CONFLICT", "MOD_NAVIGATION_CHANGED").contains(api.problem().eventCode()))
                return true;
        return false;
    }
}
