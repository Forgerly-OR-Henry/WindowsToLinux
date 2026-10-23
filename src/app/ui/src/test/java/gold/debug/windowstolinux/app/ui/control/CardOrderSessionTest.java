package gold.debug.windowstolinux.app.ui.control;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import gold.debug.windowstolinux.app.ui.error.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import org.junit.jupiter.api.Test;

class CardOrderSessionTest {
    private static final List<String> INITIAL = List.of("a", "b", "c"), FIRST = List.of("b", "a", "c"),
        SECOND = List.of("b", "c", "a"), THIRD = List.of("c", "b", "a");

    private static final PageMessagePresenter MESSAGES = new PageMessagePresenter(MessageCatalog.forLanguageTag("en"), FailureReportStore.disabled());

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static boolean flush(CardOrderSession<?> session) throws Exception {
        return edt(session::saveBeforeLeave).toCompletableFuture().get(8, TimeUnit.SECONDS);
    }

    private static CardOrderSession<String> session(AtomicReference<List<String>> stored, AtomicInteger writes) {
        return new CardOrderSession<>(null, MESSAGES, (expected, requested) -> {
            assertEquals(stored.get(), expected);
            writes.incrementAndGet();
            stored.set(requested);
            return requested;
        }, stored::get, ignored -> {
        }, () -> {
        });
    }

    @Test
    void eightDomainsCountEffectiveReordersAndFlushResidualMembershipIndependently() throws Exception {
        var domains = new ArrayList<CardOrderSession<String>>();
        var values = new ArrayList<AtomicReference<List<String>>>();
        var counts = new ArrayList<AtomicInteger>();
        for (int i = 0; i < 8; i++) {
            var stored = new AtomicReference<>(INITIAL);
            var writes = new AtomicInteger();
            var session = edt(() -> session(stored, writes));
            edt(() -> {
                session.accept(INITIAL);
                session.edit(FIRST, true);
                session.edit(FIRST, true);
                session.edit(SECOND, true);
                return null;
            });
            assertEquals(INITIAL, stored.get());
            assertEquals(2, edt(session::changes));
            domains.add(session);
            values.add(stored);
            counts.add(writes);
        }
        edt(() -> {
            domains.getFirst().edit(THIRD, true);
            return null;
        });
        assertTrue(flush(domains.getFirst()));
        assertEquals(THIRD, values.getFirst().get());
        assertEquals(1, counts.getFirst().get());
        assertEquals(0, edt(domains.getFirst()::changes));
        for (int i = 1; i < 8; i++) {
            assertEquals(INITIAL, values.get(i).get());
            assertTrue(flush(domains.get(i)));
            assertEquals(SECOND, values.get(i).get());
            assertEquals(1, counts.get(i).get());
        }
        var membership = domains.get(2);
        edt(() -> {
            membership.edit(List.of("b", "c"), false);
            return null;
        });
        assertEquals(0, edt(membership::changes));
        assertTrue(flush(membership));
        assertEquals(List.of("b", "c"), values.get(2).get());
        assertFalse(edt(DesktopTaskRunner::hasActiveTasks));
    }

    @Test
    void busySaveRejectsDuplicateEditsAndLockRetainsResidualDraft() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stored = new AtomicReference<>(INITIAL);
        var session = edt(() -> new CardOrderSession<String>(null, MESSAGES, (expected, requested) -> {
            entered.countDown();
            assertTrue(release.await(6, TimeUnit.SECONDS));
            assertEquals(stored.get(), expected);
            stored.set(requested);
            return requested;
        }, stored::get, ignored -> {
        }, () -> {
        }));
        try {
            edt(() -> {
                session.accept(INITIAL);
                session.edit(FIRST, true);
                session.edit(SECOND, true);
                session.edit(THIRD, true);
                return null;
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            edt(() -> {
                session.edit(INITIAL, true);
                assertEquals(THIRD, session.draft());
                return null;
            });
        } finally {
            release.countDown();
        }
        assertTrue(flush(session));
        edt(() -> {
            session.edit(FIRST, true);
            session.setLocked(true);
            return null;
        });
        assertTrue(flush(session));
        assertEquals(THIRD, stored.get());
        assertTrue(edt(session::hasPendingEdits));
        assertEquals(1, edt(session::changes));
        edt(() -> {
            session.accept(INITIAL);
            assertEquals(FIRST, session.draft());
            session.setLocked(false);
            return null;
        });
        assertTrue(flush(session));
        assertEquals(FIRST, stored.get());
        assertEquals(0, edt(session::changes));
    }

    @Test
    void failureKeepsDraftAndConflictRequiresExplicitReload() throws Exception {
        var stored = new AtomicReference<>(INITIAL);
        var reject = new AtomicBoolean(true);
        var conflict = new AtomicBoolean();
        var attempts = new AtomicInteger();
        var session = edt(() -> new CardOrderSession<String>(null, MESSAGES, (expected, requested) -> {
            attempts.incrementAndGet();
            if (conflict.get()) throw new SQLException("fixture conflict", "40001");
            if (reject.getAndSet(false)) throw new IOException("fixture failed write");
            stored.set(requested);
            return requested;
        }, stored::get, ignored -> {
        }, () -> {
        }));
        edt(() -> {
            session.accept(INITIAL);
            session.edit(FIRST, true);
            return null;
        });
        var pending = edt(session::saveBeforeLeave).toCompletableFuture();
        choose("OK");
        assertFalse(pending.get(5, TimeUnit.SECONDS));
        assertEquals(1, attempts.get());
        assertTrue(edt(session::hasPendingEdits));
        assertEquals(1, edt(session::changes));
        assertTrue(flush(session));
        edt(() -> {
            session.edit(SECOND, true);
            return null;
        });
        conflict.set(true);
        stored.set(THIRD);
        pending = edt(session::saveBeforeLeave).toCompletableFuture();
        choose("Keep draft");
        assertFalse(pending.get(5, TimeUnit.SECONDS));
        assertEquals(SECOND, edt(session::draft));
        pending = edt(session::saveBeforeLeave).toCompletableFuture();
        choose("Reload");
        assertFalse(pending.get(5, TimeUnit.SECONDS));
        assertEquals(THIRD, edt(session::draft));
        assertFalse(edt(session::hasPendingEdits));
    }

    private static void choose(String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            if (edt(() -> {
                for (var window : Window.getWindows())
                    if (window instanceof JDialog && window.isShowing()) {
                        var button = find(window, text);
                        if (button != null) {
                            button.doClick(0);
                            return true;
                        }
                    }
                return false;
            })) return;
            Thread.sleep(20);
        }
        fail("Missing dialog option: " + text);
    }

    private static JButton find(Container root, String text) {
        for (var child : root.getComponents()) {
            if (child instanceof JButton button && (text.equals(button.getText()) || text.equals("OK"))) return button;
            if (child instanceof Container nested) {
                var found = find(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
