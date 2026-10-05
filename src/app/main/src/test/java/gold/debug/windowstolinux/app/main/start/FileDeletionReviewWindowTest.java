package gold.debug.windowstolinux.app.main.start;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.task.TaskInteractionPresenter;
import gold.debug.windowstolinux.appapi.file.action.DeleteEffect;
import gold.debug.windowstolinux.appapi.task.action.ActionIntent;
import gold.debug.windowstolinux.appapi.task.model.TaskSnapshot;
import gold.debug.windowstolinux.appapi.task.model.TaskState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileDeletionReviewWindowTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ordinaryDialogOffersOnlyAvailableEffectsAndReturnsTheExplicitChoice(boolean recyclable) throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        var frame = PlatformWindowTest.onEdt(() -> {
            var owner = new JFrame();
            owner.setSize(320, 200);
            owner.setVisible(true);
            return owner;
        });
        var worker = Executors.newSingleThreadExecutor();
        try {
            var task = new TaskSnapshot(UUID.randomUUID(), "review-test", "1.2", "presentation", TaskState.RUNNING,
                Optional.empty(), "", Instant.now(), Optional.empty(), List.of());
            var recycle = new ActionIntent("file-delete", List.of(), "local:test", "file-v1", "delete Link RECYCLE\nidentity", "Link: owned fixture");
            var permanent = new ActionIntent("file-delete", List.of(), "local:test", "file-v1", "delete Link PERMANENT\nidentity", "Link: owned fixture");
            Map<DeleteEffect, ActionIntent> choices = recyclable
                ? Map.of(DeleteEffect.RECYCLE, recycle, DeleteEffect.PERMANENT, permanent) : Map.of(DeleteEffect.PERMANENT, permanent);
            for (String choice : recyclable ? List.of("移入回收站", "永久删除", "暂不处理", "关闭") : List.of("永久删除", "暂不处理", "关闭")) {
                var presenter = new TaskInteractionPresenter(frame, MessageCatalog.forLanguageTag("zh-CN"));
                var review = worker.submit(() -> presenter.delete(choices, "metadata-only explanation", task, () -> false));
                PlatformWindowTest.await(() -> dialog(frame) != null);
                PlatformWindowTest.onEdt(() -> {
                    var reviewDialog = dialog(frame);
                    var text = ((JTextArea) PlatformWindowTest.named(reviewDialog, "task.review.exactAction")).getText();
                    assertTrue(text.contains(recyclable ? "请选择移入回收站或永久删除" : "此对象无法移入回收站"));
                    assertTrue(text.contains("无法通过回收站还原"));
                    assertTrue(text.contains("保留其指向的对象"));
                    assertTrue(text.contains("metadata-only explanation"));
                    assertEquals("暂不处理", reviewDialog.getRootPane().getDefaultButton().getText());
                    assertEquals(recyclable, button(reviewDialog, "移入回收站") != null);
                    if (choice.equals("关闭")) reviewDialog.dispose();
                    else button(reviewDialog, choice).doClick(0);
                    return null;
                });
                assertEquals(choice.equals("移入回收站") ? DeleteEffect.RECYCLE : choice.equals("永久删除") ? DeleteEffect.PERMANENT : null,
                    review.get(8, TimeUnit.SECONDS));
                PlatformWindowTest.await(() -> dialog(frame) == null);
            }
        } finally {
            PlatformWindowTest.onEdt(() -> {
                for (var window : frame.getOwnedWindows()) window.dispose();
                frame.dispose();
                return null;
            });
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(8, TimeUnit.SECONDS));
        }
    }

    @Test
    void actualReviewPreservesExactActionAndShowsLocalizedRecoveryBeforeConsent() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        var frame = PlatformWindowTest.onEdt(() -> {
            var owner = new JFrame();
            owner.setSize(320, 200);
            owner.setVisible(true);
            return owner;
        });
        var worker = Executors.newSingleThreadExecutor();
        try {
            var task = new TaskSnapshot(UUID.randomUUID(), "review-test", "1.2", "presentation", TaskState.RUNNING,
                Optional.empty(), "", Instant.now(), Optional.empty(), List.of());
            for (String kind : List.of("File", "Link")) {
                for (String effect : List.of("RECYCLE", "PERMANENT")) {
                    String exact = "delete " + kind + " " + effect + "\n" + "a".repeat(64) + "\nrequested=bG9jYWw6dGVzdA";
                    var intent = new ActionIntent("file-delete", List.of(), "local:test", "file-v1", exact,
                        "Exact object description");
                    var presenter = new TaskInteractionPresenter(frame, MessageCatalog.forLanguageTag("zh-CN"));
                    var review = worker.submit(() -> presenter.approve(intent, task, () -> false));
                    PlatformWindowTest.await(() -> dialog(frame) != null);
                    PlatformWindowTest.onEdt(() -> {
                        var reviewDialog = dialog(frame);
                        var text = ((JTextArea) PlatformWindowTest.named(reviewDialog, "task.review.exactAction")).getText();
                        assertTrue(text.contains(exact));
                        assertTrue(text.contains(intent.target()));
                        assertTrue(text.contains(kind.equals("Link") ? "保留其指向的对象" : "实际文件或目录"));
                        assertTrue(text.contains(effect.equals("PERMANENT") ? "无法通过回收站还原" : "对象将进入账户回收站"));
                        var defaultButton = reviewDialog.getRootPane().getDefaultButton();
                        assertEquals("取消", defaultButton.getText());
                        defaultButton.doClick(0);
                        return null;
                    });
                    assertFalse(review.get(8, TimeUnit.SECONDS));
                    PlatformWindowTest.await(() -> dialog(frame) == null);
                }
            }
        } finally {
            PlatformWindowTest.onEdt(() -> {
                for (var window : frame.getOwnedWindows()) window.dispose();
                frame.dispose();
                return null;
            });
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(8, TimeUnit.SECONDS));
        }
    }

    private static JDialog dialog(JFrame frame) {
        for (var window : frame.getOwnedWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()) return dialog;
        }
        return null;
    }

    private static JButton button(Component component, String text) {
        if (component instanceof JButton button && button.getText().equals(text)) return button;
        if (component instanceof Container container)
            for (Component child : container.getComponents()) {
                var found = button(child, text);
                if (found != null) return found;
            }
        return null;
    }
}
