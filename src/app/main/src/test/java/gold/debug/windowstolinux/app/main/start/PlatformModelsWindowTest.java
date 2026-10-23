package gold.debug.windowstolinux.app.main.start;

import static gold.debug.windowstolinux.app.main.start.PlatformWindowTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.InputEvent;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ai.model.*;
import gold.debug.windowstolinux.app.service.ai.*;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.error.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import gold.debug.windowstolinux.app.ui.window.PlatformFrame;
import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

class PlatformModelsWindowTest {
    @TempDir
    Path root;

    private static PlatformFrame window(PlatformRuntime runtime, ThemeMode theme, int scale) throws Exception {
        var service = runtime.models();
        return onEdt(() -> {
            DesktopThemeService.install(theme, scale);
            var frame = new PlatformFrame(runtime.security(), MessageCatalog.forLanguageTag("zh-CN"),
                theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light(),
                new UiEnvironment(Locale.SIMPLIFIED_CHINESE, theme.name(), false), FailureReportStore.disabled());
            frame.installModels(service);
            ((JTabbedPane) named(frame, "platform.pages")).setSelectedComponent(named(frame, "platform.models"));
            frame.setLocation(10, 10);
            frame.setSize(1600, 900);
            frame.setVisible(true);
            return frame;
        });
    }

    private static void seed(PlatformRuntime runtime) throws Exception {
        var service = PlatformModelServiceTest.setup(runtime);
        for (String id : List.of("general", "vision", "voice-live")) {
            var endpoint = URI.create(id.equals("voice-live") ? "wss://api.example.test/realtime?intent=transcription" : "https://api.example.test/v1/responses");
            service.save(new AiModelProfile(id, endpoint, id + "-model", "ai/" + id, ModelReasoningLevel.DEFAULT,
                    ModelProtocol.OPENAI_RESPONSES), switch (id) {
                    case "general" -> "语言通用模型";
                    case "vision" -> "视觉模型";
                    default -> "实时语音模型";
                },
                "isolated-ui-fixture".toCharArray(), 4096);
            var info = service.listAiConfigurations().stream().filter(row -> row.profile().id().equals(id)).findFirst().orElseThrow();
            for (var capability : id.equals("voice-live") ? List.of(AiCapabilityType.VOICE_REALTIME)
                : id.equals("general") ? List.of(AiCapabilityType.TEXT, AiCapabilityType.TOOL_CALLING, AiCapabilityType.VISION)
                : List.of(AiCapabilityType.TEXT, AiCapabilityType.VISION))
                runtime.persistence().aiProfiles().recordVerification(info.profile().stored(), info.revision(), capability, Instant.now());
            if (id.equals("general")) {
                runtime.persistence().aiProfiles().recordVerification(info.profile().stored(), info.revision(), AiCapabilityType.TOOL_CALLING,
                    new AiCapabilityCheck(AiCapabilityStatus.FAILED, "MODEL_PROBE_ANSWER", Optional.of(Instant.now())));
                runtime.persistence().aiProfiles().recordVerification(info.profile().stored(), info.revision(), AiCapabilityType.VOICE_FILE,
                    new AiCapabilityCheck(AiCapabilityStatus.UNSUPPORTED, "MODEL_PROBE_UNSUPPORTED", Optional.of(Instant.now())));
            }
        }
        service.saveAiPurpose(AiPurposeType.DEPLOYMENT, List.of(new AiPurposeAssignment("general")));
        service.saveAiPurpose(AiPurposeType.VISION, List.of(new AiPurposeAssignment("vision"), new AiPurposeAssignment("general")));
    }

    @Test
    void realSwingThreeStatesRenderAtBothThemesScalesAndWindowSizes() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            seed(runtime);
            for (var theme : List.of(ThemeMode.LIGHT, ThemeMode.DARK))
                for (int scale : new int[]{100, 150}) {
                    var frame = window(runtime, theme, scale);
                    try {
                        await(() -> named(frame, "ai.inventory").isEnabled());
                        snapshot(frame, "full", theme, scale);
                        var inventory = (JList<?>) onEdt(() -> named(frame, "ai.inventory"));
                        click(inventory, 0);
                        await(() -> descendants(frame).anyMatch(c -> "models.details".equals(c.getName())));
                        onEdt(() -> {
                            assertEquals(5, descendants(frame).filter(c -> c.getName() != null && c.getName().startsWith("models.verify.")).count());
                            assertFalse(descendants(frame).anyMatch(c -> "ai.open.VISION".equals(c.getName())));
                            assertEquals(0, ((JComboBox<?>) named(frame, "models.checks")).getItemCount());
                            assertNotNull(((JLabel) named(frame, "models.details.title")).getIcon());
                            return null;
                        });
                        snapshot(frame, "detail", theme, scale);
                        onEdt(() -> {
                            ((JButton) named(frame, "models.collapse")).doClick(0);
                            return null;
                        });
                        onEdt(() -> {
                            ((JButton) named(frame, "ai.open.VISION")).doClick(0);
                            return null;
                        });
                        await(() -> named(frame, "ai.purpose").isShowing());
                        snapshot(frame, "purpose", theme, scale);
                        onEdt(() -> {
                            assertFalse(descendants(frame).anyMatch(c -> c.getName() != null && Set.of("ai.purpose.save", "ai.purpose.cancel", "model.fileEndpoint", "model.realtimeEndpoint").contains(c.getName())));
                            assertFalse(descendants((Container) named(frame, "platform.models"))
                                .anyMatch(c -> c instanceof JButton button && "×".equals(button.getText())));
                            return null;
                        });
                    } finally {
                        onEdt(() -> {
                            frame.dispose();
                            return null;
                        });
                    }
                }
        }
    }

    private static void snapshot(PlatformFrame frame, String state, ThemeMode theme, int scale) throws Exception {
        onEdt(() -> {
            frame.setSize(1600, 900);
            frame.validate();
            int navigationGray = theme == ThemeMode.DARK ? 20 : 250;
            assertEquals(new Color(navigationGray, navigationGray, navigationGray),
                named(frame, "platform.sidebar").getBackground());
            assertNotEquals(named(frame, "platform.sidebar").getBackground(), named(frame, "platform.models").getBackground());
            assertInventoryHeadingCentered(frame);
            assertCardSurfaces(frame, state, theme);
            if (state.equals("detail")) assertCapabilityColumnsAligned(frame);
            capture(frame, "ai-" + state + "-" + theme + "-" + scale + "-normal.png");
            var minimum = frame.getMinimumSize();
            frame.setSize(minimum);
            frame.validate();
            assertInventoryHeadingCentered(frame);
            assertCardSurfaces(frame, state, theme);
            if (state.equals("detail")) assertCapabilityColumnsAligned(frame);
            if (state.equals("purpose"))
                for (var type : AiPurposeType.values()) {
                    var button = (JButton) named(frame, "ai.open." + type.name());
                    assertEquals(new Rectangle(0, 0, button.getWidth(), button.getHeight()), button.getVisibleRect());
                }
            capture(frame, "ai-" + state + "-" + theme + "-" + scale + "-minimum.png");
            frame.setSize(1600, 900);
            frame.validate();
            return null;
        });
    }

    private static void assertInventoryHeadingCentered(PlatformFrame frame) {
        var title = (JLabel) named(frame, "ai.inventory.title");
        var button = (JButton) named(frame, "ai.models.add");
        var titleBounds = SwingUtilities.convertRectangle(title.getParent(), title.getBounds(), frame);
        var buttonBounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), frame);
        assertTrue(Math.abs(titleBounds.getCenterY() - buttonBounds.getCenterY()) <= 1,
            () -> "Inventory title and action centers differ: " + titleBounds + " / " + buttonBounds);
    }

    private static void assertCardSurfaces(PlatformFrame frame, String state, ThemeMode theme) {
        var palette = theme == ThemeMode.DARK ? ThemePalette.dark() : ThemePalette.light();
        var inventory = (JList<?>) named(frame, "ai.inventory");
        var outer = (JComponent) named(frame, "ai.inventory.surface");
        assertEquals(palette.cardBackground(), outer.getBackground());
        assertEquals(UIScale.scale(16), outer.getInsets().left);
        assertEquals(UIScale.scale(14), outer.getInsets().top);
        assertEquals(palette.cardBackground(), inventory.getBackground());
        var scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, inventory);
        assertEquals(palette.cardBackground(), scroll.getViewport().getBackground());
        var bar = scroll.getVerticalScrollBar();
        var add = named(frame, "ai.models.add");
        if (!state.equals("full"))
            assertEquals(SwingUtilities.convertPoint(bar, bar.getWidth(), 0, frame).x,
                SwingUtilities.convertPoint(add, add.getWidth(), 0, frame).x);
        if (state.equals("detail")) {
            assertEquals(palette.cardBackground(), named(frame, "models.details.surface").getBackground());
            for (String section : List.of("configuration", "capabilities", "tasks"))
                assertEquals(palette.cardBackground(), named(frame, "models." + section + ".surface").getBackground());
            descendants((Container) named(frame, "models.capabilities"))
                .filter(JSeparator.class::isInstance).forEach(line -> assertEquals(palette.pageBackground(), line.getForeground()));
        } else if (state.equals("purpose"))
            assertEquals(palette.cardBackground(), named(frame, "ai.purpose.surface").getBackground());
    }

    private static void assertCapabilityColumnsAligned(PlatformFrame frame) {
        int stateX = -1, buttonX = -1;
        for (var type : AiCapabilityType.values()) {
            var name = named(frame, "models.capability." + type.name());
            var state = (JLabel) named(frame, "models.state." + type.name());
            var button = named(frame, "models.verify." + type.name());
            assertNotNull(state.getIcon());
            if (stateX < 0) {
                stateX = state.getX();
                buttonX = button.getX();
            }
            assertEquals(stateX, state.getX());
            assertEquals(buttonX, button.getX());
            assertTrue(name.getX() + name.getWidth() <= state.getX());
            assertTrue(state.getX() + state.getWidth() <= button.getX());
        }
        assertEquals(4, descendants((Container) named(frame, "models.capabilities")).filter(c -> c instanceof JSeparator).count());
    }

    @Test
    void taskRemainsOwnedByItsModelWhileDetailsChangeAndEditingIsDisabled() throws Exception {
        try (var fixture = new PlatformAiServiceTest.Fixture(); var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            var service = PlatformModelServiceTest.setup(runtime);
            var first = PlatformModelServiceTest.profile(fixture);
            service.save(first, "Same name", "key".toCharArray(), 128);
            service.save(new AiModelProfile("other", fixture.uri("/chat"), "fixture-model", "ai/other"), "Same name", "key".toCharArray(), 128);
            PlatformModelServiceTest.answer(fixture);
            var original = fixture.handler.get();
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            fixture.handler.set(exchange -> {
                entered.countDown();
                try {
                    release.await(8, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
                original.serve(exchange);
            });
            var frame = window(runtime, ThemeMode.LIGHT, 100);
            try {
                await(() -> named(frame, "ai.inventory").isEnabled());
                var list = (JList<?>) onEdt(() -> named(frame, "ai.inventory"));
                click(list, 0);
                onEdt(() -> {
                    ((JButton) named(frame, "models.verify.TEXT")).doClick(0);
                    return null;
                });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                await(() -> !named(frame, "ai.models.edit").isEnabled());
                assertEquals(1, service.checks("sample").size());
                assertTrue(service.checks("other").isEmpty());
                click(list, 1);
                await(() -> ((JComboBox<?>) named(frame, "models.checks")).getItemCount() == 0);
                assertEquals(1, service.checks("sample").size());
                onEdt(() -> {
                    ((JButton) named(frame, "models.collapse")).doClick(0);
                    return null;
                });
                assertEquals(1, service.checks("sample").size());
            } finally {
                release.countDown();
                await(() -> service.checks("sample").isEmpty());
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    @Test
    void leavingAndNormalCloseFlushDraftBeforeNavigationAndServiceShutdown() throws Exception {
        try (var runtime = PlatformRuntime.open(root, PlatformStartupTest.PROTECTION)) {
            seed(runtime);
            var service = runtime.models();
            var persistence = runtime.persistence();
            var shutdown = new java.util.concurrent.atomic.AtomicBoolean();
            var frame = window(runtime, ThemeMode.LIGHT, 100);
            try {
                await(() -> named(frame, "ai.inventory").isEnabled());
                var list = (JList<?>) onEdt(() -> named(frame, "ai.inventory"));
                var pages = (JTabbedPane) onEdt(() -> named(frame, "platform.pages"));
                var modelPage = onEdt(pages::getSelectedComponent);
                var other = onEdt(() -> java.util.stream.IntStream.range(0, pages.getTabCount()).mapToObj(pages::getComponentAt)
                    .filter(component -> component != modelPage).findFirst().orElseThrow());
                onEdt(() -> {
                    list.setSelectedIndex(0);
                    list.getActionMap().get("alt DOWN").actionPerformed(null);
                    return null;
                });
                assertEquals(List.of("general", "vision", "voice-live"), service.listAiConfigurations().stream().map(row -> row.profile().id()).toList());
                onEdt(() -> {
                    pages.setSelectedComponent(other);
                    return null;
                });
                await(() -> pages.getSelectedComponent() == other);
                assertEquals(List.of("vision", "general", "voice-live"), service.listAiConfigurations().stream().map(row -> row.profile().id()).toList());
                onEdt(() -> {
                    pages.setSelectedComponent(modelPage);
                    list.setSelectedValue("general", false);
                    list.getActionMap().get("alt UP").actionPerformed(null);
                    frame.onExit(() -> {
                        assertFalse(runtime.security().securityState().unlocked());
                        assertEquals(List.of("general", "vision", "voice-live"), persistence.aiProfiles().listNamed().stream().map(row -> row.id()).toList());
                        shutdown.set(true);
                    });
                    frame.dispatchEvent(new java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING));
                    return null;
                });
                await(() -> !frame.isDisplayable());
                assertTrue(shutdown.get());
            } finally {
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    private static void click(JList<?> list, int row) throws Exception {
        var point = onEdt(() -> {
            var bounds = list.getCellBounds(row, row);
            var pointOnList = new Point(bounds.width / 2, bounds.y + bounds.height / 2);
            SwingUtilities.convertPointToScreen(pointOnList, list);
            return pointOnList;
        });
        var robot = new Robot();
        robot.setAutoDelay(40);
        robot.mouseMove(point.x, point.y);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }
}
