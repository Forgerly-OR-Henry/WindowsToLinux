package gold.debug.windowstolinux.mod.files.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import gold.debug.windowstolinux.appapi.task.log.LogEvent;
import gold.debug.windowstolinux.appapi.task.log.LogLevel;
import gold.debug.windowstolinux.appapi.task.log.LogReadResult;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import org.junit.jupiter.api.Test;

class FilesFeedbackTest {
    @Test
    void unchangedEvidencePreservesDocumentAndSelectionWhileStateLogsAndLanguageUpdate() throws Exception {
        edt(() -> {
            var messages = new FilesMessages(Locale.SIMPLIFIED_CHINESE);
            var feedback = new FilesFeedback(messages);
            var text = (JTextArea) ((JScrollPane) feedback.getComponent(0)).getViewport().getView();
            UUID task = UUID.randomUUID();
            var running = snapshot(task, TaskState.RUNNING, "Copying original files");
            var first = record(task, "FIRST_RECORD", "Original diagnostic");
            var initialLogs = new LogReadResult(List.of(first), Optional.empty());
            feedback.observe(running, Optional.empty());
            feedback.logs(task, initialLogs);
            text.select(0, messages.text("evidence").length());
            String selection = text.getSelectedText();
            int dot = text.getCaret().getDot(), mark = text.getCaret().getMark();
            var documents = new AtomicInteger();
            var carets = new AtomicInteger();
            text.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent event) {
                    documents.incrementAndGet();
                }

                public void removeUpdate(DocumentEvent event) {
                    documents.incrementAndGet();
                }

                public void changedUpdate(DocumentEvent event) {
                    documents.incrementAndGet();
                }
            });
            text.addCaretListener(event -> carets.incrementAndGet());
            feedback.observe(snapshot(task, TaskState.RUNNING, "Copying original files"), Optional.empty());
            feedback.logs(task, new LogReadResult(List.of(first), Optional.empty()));
            feedback.render();
            assertEquals(0, documents.get());
            assertEquals(0, carets.get());
            assertEquals(selection, text.getSelectedText());
            assertEquals(dot, text.getCaret().getDot());
            assertEquals(mark, text.getCaret().getMark());

            var unknown = snapshot(task, TaskState.UNKNOWN, "Original callback returned; cleanup is unconfirmed");
            feedback.observe(unknown, Optional.empty());
            assertTrue(documents.get() > 0);
            assertTrue(text.getText().contains("UNKNOWN"));
            assertTrue(text.getText().contains(unknown.progressText()));
            assertTrue(text.getText().contains("Original diagnostic"));

            documents.set(0);
            var second = record(task, "SECOND_RECORD", "Additional original diagnostic");
            var latestLogs = new LogReadResult(List.of(second, first), Optional.empty());
            feedback.logs(task, latestLogs);
            assertTrue(documents.get() > 0);
            assertTrue(text.getText().contains("Additional original diagnostic"));
            assertTrue(text.getText().contains("Original diagnostic"));
            assertTrue(text.getText().contains("UNKNOWN"));

            documents.set(0);
            String chineseHeading = messages.text("evidence");
            messages.update(Locale.ENGLISH);
            feedback.render();
            assertTrue(documents.get() > 0);
            assertFalse(messages.text("evidence").equals(chineseHeading));
            assertTrue(text.getText().startsWith(messages.text("evidence") + "\n"));
            assertTrue(text.getText().contains(unknown.progressText()));
            assertTrue(text.getText().contains("Additional original diagnostic"));

            documents.set(0);
            carets.set(0);
            messages.update(Locale.ENGLISH);
            feedback.observe(unknown, Optional.empty());
            feedback.logs(task, latestLogs);
            feedback.render();
            assertEquals(0, documents.get());
            assertEquals(0, carets.get());
            return null;
        });
    }

    private static TaskSnapshot snapshot(UUID task, TaskState state, String progress) {
        return new TaskSnapshot(task, "gold.debug.windowstolinux.mod.files", "1.0.0", "files-copy", state,
            Optional.empty(), progress, Instant.parse("2026-10-01T00:00:00Z"), Optional.empty(), List.of());
    }

    private static LogReadResult.Record record(UUID task, String code, String message) {
        return new LogReadResult.Record(UUID.randomUUID(), Instant.parse("2026-10-01T00:00:01Z"),
            new LogEvent(LogLevel.INFO, code, message, Optional.empty(), Optional.of(task), Optional.of(task), Optional.empty()), false);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var future = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}
