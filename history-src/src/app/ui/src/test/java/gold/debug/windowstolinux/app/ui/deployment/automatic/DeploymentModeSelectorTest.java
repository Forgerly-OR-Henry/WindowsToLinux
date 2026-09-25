package gold.debug.windowstolinux.app.ui.deployment.automatic;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;

import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.i18n.*;
import gold.debug.windowstolinux.shared.model.deployment.*;
import org.junit.jupiter.api.Test;

class DeploymentModeSelectorTest {
    @Test
    void threePositionsDefaultToAssistedAndApprovalDefaultsToAutomatic() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DesktopThemeService.install(ThemeMode.LIGHT);
            var selector = new DeploymentModeSelector(new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
            assertEquals(DeploymentAutomationMode.ASSISTED, selector.mode());
            assertEquals(AgentApprovalMode.AUTOMATIC, selector.approval());
            assertTrue(approval(selector).isVisible());
            JSlider slider = slider(selector);
            assertEquals(0, slider.getMinimum());
            assertEquals(2, slider.getMaximum());
            assertTrue(slider.getSnapToTicks());
            assertTrue(slider.getPaintLabels());
            selector.restore(DeploymentAutomationMode.AGENT, AgentApprovalMode.MANUAL_REVIEW);
            assertEquals(AgentApprovalMode.MANUAL_REVIEW, selector.approval());
            selector.setBusy(true);
            assertFalse(slider.isEnabled());
            assertTrue(approval(selector).getToolTipText().contains("人工审核"));
            assertFalse(popup(selector).isVisible());
            assertFalse(button(selector).isEnabled());
            selector.setBusy(false);
            assertTrue(slider.isEnabled());
            assertTrue(selector.getPreferredSize().width < UIScale.scale(500),
                    "manual hint must not force the deployment button off screen");
            selector.setSize(470, 135);
            layout(selector);
            layout(selector);
            try {
                var image = new java.awt.image.BufferedImage(470, 135, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                graphics.setColor(UIManager.getColor("Panel.background"));
                graphics.fillRect(0, 0, 470, 135);
                selector.paint(graphics);
                graphics.dispose();
                var path = java.nio.file.Path.of("target/visual-checks/mode-agent-manual.png");
                java.nio.file.Files.createDirectories(path.getParent());
                javax.imageio.ImageIO.write(image, "png", path.toFile());
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        });
    }

    @Test
    void capsuleRetainsMouseKeyboardAndDisabledBehaviorAcrossThemes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                    DesktopThemeService.install(theme);
                    var selector = new DeploymentModeSelector(
                            new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
                    JSlider slider = slider(selector);
                    slider.setSize(slider.getPreferredSize());
                    int y = UIScale.scale(18), left = UIScale.scale(24), right = slider.getWidth() - UIScale.scale(26);
                    mouse(slider, MouseEvent.MOUSE_PRESSED, right, y);
                    mouse(slider, MouseEvent.MOUSE_RELEASED, right, y);
                    assertEquals(DeploymentAutomationMode.AGENT, selector.mode(),
                            "Clicking the far end must select Agent directly");
                    mouse(slider, MouseEvent.MOUSE_PRESSED, right, y);
                    mouse(slider, MouseEvent.MOUSE_DRAGGED, left, y);
                    mouse(slider, MouseEvent.MOUSE_RELEASED, left, y);
                    assertEquals(DeploymentAutomationMode.STATIC, selector.mode());
                    Object key = slider.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("RIGHT"));
                    slider.getActionMap().get(key)
                            .actionPerformed(new ActionEvent(slider, ActionEvent.ACTION_PERFORMED, "RIGHT"));
                    assertEquals(DeploymentAutomationMode.ASSISTED, selector.mode());
                    SwingUtilities.updateComponentTreeUI(selector);
                    assertEquals(DeploymentAutomationMode.ASSISTED, selector.mode());
                    selector.setBusy(true);
                    mouse(slider, MouseEvent.MOUSE_PRESSED, right, y);
                    mouse(slider, MouseEvent.MOUSE_RELEASED, right, y);
                    assertEquals(DeploymentAutomationMode.ASSISTED, selector.mode());
                    assertFalse(approval(selector).isEnabled());
                    selector.setBusy(false);
                    for (String locale : new String[]{"zh-CN", "en-US"}) {
                        JPanel preview = new JPanel(new GridLayout(0, 1, 0, 12));
                        preview.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
                        preview.setBackground(theme == ThemeMode.DARK
                                ? ThemePalette.dark().pageBackground()
                                : ThemePalette.light().pageBackground());
                        for (DeploymentAutomationMode mode : DeploymentAutomationMode.values()) {
                            var sample = new DeploymentModeSelector(
                                    new PageMessagePresenter(MessageCatalog.forLanguageTag(locale)));
                            sample.restore(mode, AgentApprovalMode.AUTOMATIC);
                            preview.add(sample);
                        }
                        var disabled = new DeploymentModeSelector(
                                new PageMessagePresenter(MessageCatalog.forLanguageTag(locale)));
                        disabled.restore(DeploymentAutomationMode.AGENT, AgentApprovalMode.MANUAL_REVIEW);
                        disabled.setBusy(true);
                        preview.add(disabled);
                        preview.addNotify();
                        preview.setSize(UIScale.scale(530), UIScale.scale(420));
                        layout(preview);
                        layout(preview);
                        assertTrue(preview.getComponent(0).getWidth() > 0,
                                preview.getComponent(0).getBounds().toString());
                        assertTrue(((Container) preview.getComponent(0)).getComponent(0).getHeight() > 0);
                        try {
                            var image = new java.awt.image.BufferedImage(preview.getWidth(), preview.getHeight(),
                                    java.awt.image.BufferedImage.TYPE_INT_RGB);
                            var graphics = image.createGraphics();
                            preview.printAll(graphics);
                            graphics.dispose();
                            assertTrue(
                                    java.util.stream.IntStream.range(0, image.getWidth() * image.getHeight())
                                            .anyMatch(pixel -> image.getRGB(pixel % image.getWidth(),
                                                    pixel / image.getWidth()) != image.getRGB(0, 0)),
                                    "Preview must contain painted controls");
                            var path = java.nio.file.Path.of("target/visual-checks/mode-picker-states-" + theme + "-"
                                    + locale + "-" + UIScale.getUserScaleFactor() + ".png");
                            java.nio.file.Files.createDirectories(path.getParent());
                            javax.imageio.ImageIO.write(image, "png", path.toFile());
                        } catch (java.io.IOException failure) {
                            throw new AssertionError(failure);
                        } finally {
                            preview.removeNotify();
                        }
                    }
                }
            } finally {
                DesktopThemeService.install(ThemeMode.LIGHT);
            }
        });
    }

    private static void mouse(JSlider slider, int id, int x, int y) {
        slider.dispatchEvent(new MouseEvent(slider, id, System.currentTimeMillis(),
                id == MouseEvent.MOUSE_RELEASED ? 0 : MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false,
                id == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1));
    }

    @Test
    void clicksEaseAcrossFramesAndDraggingFollowsThePointerBeforeSnapping() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        org.junit.jupiter.api.Assumptions.assumeFalse(
                Boolean.FALSE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation")));
        org.junit.jupiter.api.Assumptions.assumeFalse("false".equals(System.getProperty("flatlaf.animation")));
        var frame = new java.util.concurrent.atomic.AtomicReference<JFrame>();
        var sampler = new java.util.concurrent.atomic.AtomicReference<Timer>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var done = new java.util.concurrent.CountDownLatch(1);
        var positions = new java.util.ArrayList<Integer>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var selector = new DeploymentModeSelector(
                        new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
                JSlider slider = slider(selector);
                JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT));
                content.add(slider);
                JFrame window = new JFrame();
                frame.set(window);
                window.setContentPane(content);
                window.setSize(520, 150);
                window.setVisible(true);
                layout(content);
                layout(content);
                int initial = thumbCenter(slider), right = slider.getWidth() - UIScale.scale(26), y = UIScale.scale(16);
                mouse(slider, MouseEvent.MOUSE_PRESSED, right, y);
                mouse(slider, MouseEvent.MOUSE_RELEASED, right, y);
                assertEquals(DeploymentAutomationMode.AGENT, selector.mode());
                assertEquals(initial, thumbCenter(slider), 2,
                        "The first frame must stay at the previous visual position");
                long started = System.nanoTime();
                int[] phase = {0};
                Timer timer = new Timer(15, event -> {
                    try {
                        int position = thumbCenter(slider);
                        positions.add(position);
                        long elapsed = (System.nanoTime() - started) / 1_000_000;
                        if (phase[0] == 0 && position > initial + 8) {
                            assertTrue(position < right - 2,
                                    "There must be an intermediate frame before reaching Agent");
                            slider.setValue(0);
                            assertEquals(position, thumbCenter(slider), 2,
                                    "Reversing must continue from the displayed frame");
                            phase[0] = 1;
                        } else if (phase[0] == 1 && elapsed > 450) {
                            assertTrue(position < slider.getWidth() / 4);
                            assertTrue(positions.stream().distinct().count() >= 4);
                            mouse(slider, MouseEvent.MOUSE_PRESSED, position, y);
                            int dragX = slider.getWidth() / 3;
                            mouse(slider, MouseEvent.MOUSE_DRAGGED, dragX, y);
                            assertEquals(dragX, thumbCenter(slider), 2,
                                    "A drag must follow the pointer between the discrete stops");
                            mouse(slider, MouseEvent.MOUSE_RELEASED, dragX, y);
                            assertEquals(dragX, thumbCenter(slider), 2, "Release must animate to the closest stop");
                            phase[0] = 2;
                        } else if (phase[0] == 2 && elapsed > 750) {
                            assertEquals(DeploymentAutomationMode.ASSISTED, selector.mode());
                            assertEquals(initial, thumbCenter(slider), 2);
                            ((Timer) event.getSource()).stop();
                            done.countDown();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        ((Timer) event.getSource()).stop();
                        done.countDown();
                    }
                });
                sampler.set(timer);
                timer.start();
            });
            assertTrue(done.await(4, java.util.concurrent.TimeUnit.SECONDS), "Animation must finish");
            if (failure.get() != null)
                throw new AssertionError(failure.get());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (sampler.get() != null)
                    sampler.get().stop();
                if (frame.get() != null)
                    frame.get().dispose();
            });
        }
    }

    private static int thumbCenter(JSlider slider) {
        var image = new java.awt.image.BufferedImage(slider.getWidth(), slider.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.GRAY);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        slider.paint(graphics);
        graphics.dispose();
        int min = image.getWidth(), max = -1;
        for (int y = 0; y < Math.min(image.getHeight(), UIScale.scale(30)); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if ((rgb >> 16 & 255) >= 240 && (rgb >> 8 & 255) >= 240 && (rgb & 255) >= 240) {
                    min = Math.min(min, x);
                    max = Math.max(max, x);
                }
            }
        assertTrue(max >= min, "The rendered thumb must be visible");
        return (min + max) / 2;
    }

    @Test
    void popoverAndApprovalIconsPreserveTextAndFitBothThemesLanguagesAndScales() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            String previousScale = System.getProperty("flatlaf.uiScale");
            try {
                for (String scale : new String[]{"1.0", "1.5"})
                    for (ThemeMode theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                        for (String language : new String[]{"zh-CN", "en-US"}) {
                            System.setProperty("flatlaf.uiScale", scale);
                            DesktopThemeService.install(theme);
                            var messages = new PageMessagePresenter(MessageCatalog.forLanguageTag(language));
                            var selector = new DeploymentModeSelector(messages);
                            selector.restore(DeploymentAutomationMode.AGENT, AgentApprovalMode.AUTOMATIC);
                            JFrame window = new JFrame();
                            JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT));
                            content.add(selector);
                            window.setContentPane(content);
                            window.setSize(UIScale.scale(520), UIScale.scale(180));
                            window.setLocation(120, 280);
                            window.setVisible(true);
                            try {
                                assertFalse(slider(selector).isShowing());
                                assertEquals(messages.text("deployment.mode.AGENT"), button(selector).getText());
                                int triggerWidth = button(selector).getWidth();
                                button(selector).doClick(0);
                                JPopupMenu popup = popup(selector);
                                assertTrue(popup.isVisible());
                                assertTrue(slider(selector).isShowing());
                                assertEquals(DeploymentAutomationMode.AGENT, selector.mode());
                                popup.setSize(popup.getPreferredSize());
                                layout(popup);
                                layout(popup);
                                assertTrue(slider(selector).getHeight() >= slider(selector).getPreferredSize().height);
                                var help = descendants(popup).filter(JTextPane.class::isInstance)
                                        .map(JTextPane.class::cast).findFirst().orElseThrow();
                                assertTrue(help.modelToView2D(help.getDocument().getLength() - 1).getMaxY() <= help
                                        .getHeight(), "Popover help must not be clipped");
                                renderWithTrigger(button(selector), popup,
                                        "mode-popover-" + theme + "-" + language + "-" + scale);
                                for (AgentApprovalMode policy : new AgentApprovalMode[]{AgentApprovalMode.AUTOMATIC,
                                        AgentApprovalMode.MANUAL_REVIEW}) {
                                    selector.restore(DeploymentAutomationMode.AGENT, policy);
                                    Dimension popupSize = popup.getSize();
                                    Point popupLocation = popup.getLocationOnScreen();
                                    for (DeploymentAutomationMode mode : DeploymentAutomationMode.values()) {
                                        selector.restore(mode, policy);
                                        layout(content);
                                        layout(popup);
                                        layout(popup);
                                        assertTrue(popup.isVisible(), "Switching modes must keep the popup open");
                                        assertEquals(popupSize, popup.getSize(),
                                                "Switching modes must keep the popup size stable");
                                        assertEquals(popupLocation, popup.getLocationOnScreen(),
                                                "Switching modes must not move the popup");
                                        assertEquals(triggerWidth, button(selector).getWidth(),
                                                "Switching modes must keep the collapsed control width stable");
                                        assertTrue(
                                                button(selector).getUI()
                                                        .getPreferredSize(button(selector)).width <= triggerWidth,
                                                "Every mode label must fit without clipping");
                                        assertTrue(help.modelToView2D(help.getDocument().getLength() - 1)
                                                .getMaxY() <= help.getHeight(),
                                                "Long explanations must fit the reserved height");
                                        renderWithTrigger(button(selector), popup, "mode-popover-" + mode + "-" + policy
                                                + "-" + theme + "-" + language + "-" + scale);
                                    }
                                }
                                selector.restore(DeploymentAutomationMode.AGENT, AgentApprovalMode.AUTOMATIC);
                                popup.setVisible(false);
                                @SuppressWarnings("unchecked")
                                JComboBox<AgentApprovalMode> approval = (JComboBox<AgentApprovalMode>) approval(
                                        selector);
                                approval.showPopup();
                                var approvalPopup = (javax.swing.plaf.basic.BasicComboPopup) approval.getUI()
                                        .getAccessibleChild(approval, 0);
                                approvalPopup.setSize(approvalPopup.getPreferredSize());
                                layout(approvalPopup);
                                var icons = java.util.Collections
                                        .newSetFromMap(new java.util.IdentityHashMap<Icon, Boolean>());
                                for (AgentApprovalMode policy : AgentApprovalMode.values()) {
                                    var label = (JLabel) approval.getRenderer().getListCellRendererComponent(
                                            new JList<>(), policy, policy.ordinal(), false, false);
                                    assertEquals(
                                            messages.text("deployment.approval."
                                                    + policy.name().toLowerCase(java.util.Locale.ROOT)),
                                            label.getText());
                                    assertNotNull(label.getIcon());
                                    icons.add(label.getIcon());
                                }
                                assertEquals(3, icons.size());
                                renderWithTrigger(approval, approvalPopup,
                                        "approval-icons-" + theme + "-" + language + "-" + scale);
                                approval.hidePopup();
                                assertEquals(AgentApprovalMode.AUTOMATIC, selector.approval());
                                selector.setBusy(true);
                                assertFalse(button(selector).isEnabled());
                                assertFalse(approval.isEnabled());
                            } catch (Exception error) {
                                throw new AssertionError(error);
                            } finally {
                                window.dispose();
                            }
                        }
            } finally {
                if (previousScale == null)
                    System.clearProperty("flatlaf.uiScale");
                else
                    System.setProperty("flatlaf.uiScale", previousScale);
                DesktopThemeService.install(ThemeMode.LIGHT);
            }
        });
    }

    @Test
    void selectionStaysOpenUntilFocusLeavesAndRetainsItsValue() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        var frame = new java.util.concurrent.atomic.AtomicReference<JFrame>();
        var sampler = new java.util.concurrent.atomic.AtomicReference<Timer>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var done = new java.util.concurrent.CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT);
                var selector = new DeploymentModeSelector(
                        new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
                JButton outside = new JButton("Outside");
                JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT));
                content.add(selector);
                content.add(outside);
                JFrame window = new JFrame();
                frame.set(window);
                window.setContentPane(content);
                window.setSize(500, 150);
                window.setLocation(160, 280);
                window.setVisible(true);
                button(selector).doClick(0);
                JSlider slider = slider(selector);
                JPopupMenu popup = popup(selector);
                int right = slider.getWidth() - UIScale.scale(26), y = UIScale.scale(16);
                mouse(slider, MouseEvent.MOUSE_PRESSED, right, y);
                long[] started = {System.nanoTime()};
                int[] phase = {0};
                Timer timer = new Timer(20, event -> {
                    try {
                        long elapsed = (System.nanoTime() - started[0]) / 1_000_000;
                        if (phase[0] == 0 && elapsed > 350) {
                            assertTrue(popup.isVisible(), "Dragging must not dismiss the popup");
                            mouse(slider, MouseEvent.MOUSE_RELEASED, right, y);
                            assertTrue(popup.isVisible(), "The settling animation must remain visible");
                            started[0] = System.nanoTime();
                            phase[0] = 1;
                        } else if (phase[0] == 1 && elapsed > 400) {
                            assertTrue(popup.isVisible(), "Releasing a selected mode must keep the popup open");
                            assertEquals(DeploymentAutomationMode.AGENT, selector.mode());
                            assertEquals("Agent", button(selector).getText());
                            assertTrue(slider.isFocusOwner(), "Opening the popover must focus the slider");
                            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .dispatchEvent(new java.awt.event.KeyEvent(slider,
                                            java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0,
                                            java.awt.event.KeyEvent.VK_LEFT, java.awt.event.KeyEvent.CHAR_UNDEFINED));
                            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .dispatchEvent(new java.awt.event.KeyEvent(slider,
                                            java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0,
                                            java.awt.event.KeyEvent.VK_LEFT, java.awt.event.KeyEvent.CHAR_UNDEFINED));
                            assertEquals(DeploymentAutomationMode.ASSISTED, selector.mode(),
                                    "Popup keyboard routing must preserve arrow selection");
                            started[0] = System.nanoTime();
                            phase[0] = 2;
                        } else if (phase[0] == 2 && elapsed > 400) {
                            assertTrue(popup.isVisible(), "Keyboard selection must also keep the popup open");
                            outside.requestFocus();
                            started[0] = System.nanoTime();
                            phase[0] = 3;
                        } else if (phase[0] == 3 && elapsed > 100) {
                            assertTrue(outside.isFocusOwner(),
                                    () -> "Dismissal must preserve the new focus target; current="
                                            + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
                            assertFalse(popup.isVisible(), "Moving focus outside must dismiss the popup");
                            assertEquals(DeploymentAutomationMode.ASSISTED, selector.mode());
                            button(selector).doClick(0);
                            assertTrue(popup.isVisible());
                            assertEquals(1, slider.getValue());
                            started[0] = System.nanoTime();
                            phase[0] = 4;
                        } else if (phase[0] == 4 && elapsed > 100) {
                            assertTrue(slider.isFocusOwner());
                            Object escape = slider.getInputMap(JComponent.WHEN_FOCUSED)
                                    .get(KeyStroke.getKeyStroke("ESCAPE"));
                            slider.getActionMap().get(escape).actionPerformed(new ActionEvent(slider, 0, "ESCAPE"));
                            assertFalse(popup.isVisible());
                            button(selector).doClick(0);
                            assertTrue(popup.isVisible());
                            selector.setBusy(true);
                            assertFalse(popup.isVisible());
                            button(selector).doClick(0);
                            assertFalse(popup.isVisible());
                            ((Timer) event.getSource()).stop();
                            done.countDown();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        ((Timer) event.getSource()).stop();
                        done.countDown();
                    }
                });
                sampler.set(timer);
                timer.start();
            });
            assertTrue(done.await(4, java.util.concurrent.TimeUnit.SECONDS));
            if (failure.get() != null)
                throw new AssertionError(failure.get());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (sampler.get() != null)
                    sampler.get().stop();
                if (frame.get() != null)
                    frame.get().dispose();
            });
        }
    }

    private static void renderWithTrigger(JComponent trigger, JPopupMenu popup, String name) throws Exception {
        int padding = UIScale.scale(16), gap = UIScale.scale(10);
        int width = Math.max(trigger.getWidth(), popup.getWidth()) + padding * 2,
                height = trigger.getHeight() + popup.getHeight() + padding * 2 + gap;
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(UIManager.getColor("Panel.background"));
        graphics.fillRect(0, 0, width, height);
        var child = graphics.create(padding, padding, trigger.getWidth(), trigger.getHeight());
        trigger.printAll(child);
        child.dispose();
        child = graphics.create(padding, padding + trigger.getHeight() + gap, popup.getWidth(), popup.getHeight());
        popup.printAll(child);
        child.dispose();
        graphics.dispose();
        var path = java.nio.file.Path.of("target/visual-checks/" + name + ".png");
        java.nio.file.Files.createDirectories(path.getParent());
        javax.imageio.ImageIO.write(image, "png", path.toFile());
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents())
            if (child instanceof Container nested)
                layout(nested);
    }

    private static JPopupMenu popup(DeploymentModeSelector selector) {
        try {
            var field = DeploymentModeSelector.class.getDeclaredField("modePopup");
            field.setAccessible(true);
            return (JPopupMenu) field.get(selector);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static JSlider slider(DeploymentModeSelector selector) {
        return descendants(popup(selector)).filter(JSlider.class::isInstance).map(JSlider.class::cast).findFirst()
                .orElseThrow();
    }

    private static JButton button(DeploymentModeSelector selector) {
        return descendants(selector).filter(JButton.class::isInstance).map(JButton.class::cast)
                .filter(value -> "deployment.modePicker".equals(value.getName())).findFirst().orElseThrow();
    }

    private static JComboBox<?> approval(DeploymentModeSelector selector) {
        return descendants(selector).filter(JComboBox.class::isInstance).map(JComboBox.class::cast).findFirst()
                .orElseThrow();
    }

    private static java.util.stream.Stream<Component> descendants(Container container) {
        return java.util.Arrays.stream(container.getComponents())
                .flatMap(child -> child instanceof Container nested
                        ? java.util.stream.Stream.concat(java.util.stream.Stream.of(child), descendants(nested))
                        : java.util.stream.Stream.of(child));
    }
}
