package gold.debug.windowstolinux.app.ui.display;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;

class DesktopControlsMotionTest {
    @Test
    void drawerFramesKeepOwnerLayoutResponsibilitiesAndSkipUnchangedBounds() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        edt(() -> {
            var layouts = new AtomicInteger();
            var drawer = new AtomicReference<DesktopDrawer>();
            var inventory = new JPanel(null);
            var detail = new JPanel();
            var footer = new JButton("Footer");
            inventory.add(footer);
            var owner = new JPanel(null) {
                @Override
                public boolean isShowing() {
                    return true;
                }

                @Override
                public void doLayout() {
                    layouts.incrementAndGet();
                    drawer.get().layout(inventory, new Rectangle(0, 0, getWidth(), getHeight()), 400, 16);
                    footer.setBounds(0, 0, inventory.getWidth(), 30);
                }
            };
            owner.add(inventory);
            owner.add(detail);
            owner.setSize(1000, 600);
            drawer.set(new DesktopDrawer(owner, detail));
            try {
                System.setProperty("flatlaf.animation", "true");
                DesktopMotion.preference(true);
                owner.doLayout();
                int initialLayouts = layouts.get();
                drawer.get().expanded(true);
                assertEquals(initialLayouts, layouts.get(), "The unchanged first frame must reuse current geometry");
                DesktopMotion.preference(false);
                assertEquals(initialLayouts + 1, layouts.get());
                assertEquals(400, inventory.getWidth());
                assertEquals(400, footer.getWidth(), "Owner-specific dependent layout must run with the new inventory width");
                assertEquals(416, detail.getX());
                assertEquals(584, detail.getWidth());
                drawer.get().reset(true);
                assertEquals(initialLayouts + 1, layouts.get(), "Identical integer bounds must not relayout the owner");
            } finally {
                drawer.get().close();
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
            }
            return null;
        });
    }

    @Test
    void popupMotionMovesActualHitBoundsWithoutRepeatingContentLayout() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        edt(() -> {
            var layouts = new AtomicInteger();
            var content = new JButton("Action");
            var pane = new DesktopRevealPane(content);
            pane.setLayout(new BorderLayout() {
                @Override
                public void layoutContainer(Container target) {
                    layouts.incrementAndGet();
                    super.layoutContainer(target);
                }
            });
            pane.remove(content);
            pane.add(content, BorderLayout.CENTER);
            var window = new JFrame("Popup motion bounds");
            try {
                System.setProperty("flatlaf.animation", "true");
                DesktopMotion.preference(true);
                window.setContentPane(pane);
                window.setSize(240, 140);
                window.setVisible(true);
                pane.doLayout();
                int initialLayouts = layouts.get();
                var event = new java.awt.event.HierarchyEvent(pane, java.awt.event.HierarchyEvent.HIERARCHY_CHANGED,
                    pane, null, java.awt.event.HierarchyEvent.SHOWING_CHANGED);
                for (var listener : pane.getHierarchyListeners()) listener.hierarchyChanged(event);
                int offset = com.formdev.flatlaf.util.UIScale.scale(6);
                assertEquals(offset, content.getY());
                assertSame(pane, SwingUtilities.getDeepestComponentAt(pane, 20, 0));
                assertSame(content, SwingUtilities.getDeepestComponentAt(pane, 20, offset + 2));
                assertEquals(initialLayouts, layouts.get());
                pane.doLayout();
                assertEquals(offset, content.getY(), "External layout must apply the offset once without accumulating it");
                DesktopMotion.preference(false);
                assertEquals(0, content.getY());
                assertSame(content, SwingUtilities.getDeepestComponentAt(pane, 20, 2));
                assertEquals(initialLayouts + 1, layouts.get(), "The final frame must move bounds without a full layout");
            } finally {
                pane.close();
                window.dispose();
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
            }
            return null;
        });
    }

    @Test
    void changingTheExpandedTargetOrReversingRetainsTheCurrentInventoryWidth() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var target = new AtomicInteger(400);
        var gap = new AtomicInteger(16);
        var drawer = new AtomicReference<DesktopDrawer>();
        var inventory = edt(JPanel::new);
        var detail = edt(JPanel::new);
        var owner = edt(() -> new JPanel(null) {
            @Override
            public void doLayout() {
                if (drawer.get() != null)
                    drawer.get().layout(inventory, new Rectangle(0, 0, getWidth(), getHeight()), target.get(), gap.get());
            }
        });
        var frame = edt(() -> {
            System.setProperty("flatlaf.animation", "true");
            DesktopMotion.preference(true);
            drawer.set(new DesktopDrawer(owner, detail));
            owner.add(inventory);
            owner.add(detail);
            var window = new JFrame("Drawer target motion");
            window.setContentPane(owner);
            window.setSize(1000, 600);
            window.setVisible(true);
            drawer.get().expanded(true);
            return window;
        });
        try {
            Thread.sleep(65);
            edt(() -> {
                owner.doLayout();
                int width = inventory.getWidth();
                int detailX = detail.getX();
                target.set(550);
                owner.doLayout();
                assertEquals(width, inventory.getWidth());
                drawer.get().expanded(false);
                target.set(300);
                gap.set(0);
                owner.doLayout();
                assertEquals(width, inventory.getWidth());
                assertEquals(detailX, detail.getX());
                DesktopMotion.preference(false);
                owner.doLayout();
                assertEquals(owner.getWidth(), inventory.getWidth());
                assertFalse(detail.isVisible());
                return null;
            });
        } finally {
            edt(() -> {
                drawer.get().close();
                frame.dispose();
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
                return null;
            });
        }
    }

    @Test
    void overflowingTabWheelAlsoStopsImmediatelyWhenGlobalMotionIsDisabled() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var tabs = edt(() -> {
            System.setProperty("flatlaf.animation", "true");
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            DesktopMotion.preference(true);
            var value = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
            for (int i = 0; i < 20; i++) value.addTab("Long tab / 页签 " + i, new JPanel());
            return value;
        });
        var frame = edt(() -> {
            var window = new JFrame("Tab wheel policy");
            window.setContentPane(tabs);
            window.setSize(440, 300);
            window.setVisible(true);
            window.validate();
            return window;
        });
        try {
            Thread.sleep(80);
            Point finalPosition = edt(() -> {
                var viewport = java.util.Arrays.stream(tabs.getComponents()).filter(JViewport.class::isInstance)
                    .map(JViewport.class::cast).findFirst().orElseThrow();
                tabs.dispatchEvent(new java.awt.event.MouseWheelEvent(tabs, java.awt.event.MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(), 0, viewport.getX() + 20, viewport.getY() + 10, 0, false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 4));
                DesktopMotion.preference(false);
                assertTrue(viewport.getViewPosition().x > 0, "Native tab scroll must finish its target");
                return viewport.getViewPosition();
            });
            Thread.sleep(260);
            edt(() -> {
                var viewport = java.util.Arrays.stream(tabs.getComponents()).filter(JViewport.class::isInstance)
                    .map(JViewport.class::cast).findFirst().orElseThrow();
                assertEquals(finalPosition, viewport.getViewPosition());
                return null;
            });
        } finally {
            edt(() -> {
                frame.dispose();
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
                return null;
            });
        }
    }

    @Test
    void globalOffStopsExistingProgressSwitchTabsAndPopupWithoutChangingActionSemantics() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        var actions = new AtomicInteger();
        var bar = edt(() -> new JProgressBar(0, 100));
        var toggle = edt(() -> new JCheckBox("Animation switch"));
        var tabs = edt(JTabbedPane::new);
        var first = edt(JPanel::new);
        var second = edt(JPanel::new);
        var button = edt(() -> new JButton("Open"));
        var frame = edt(() -> {
            System.setProperty("flatlaf.animation", "true");
            DesktopThemeService.install(ThemeMode.LIGHT, 100);
            DesktopMotion.preference(true);
            // Reinstall controls created before the theme, retaining original models and listeners.
            tabs.addTab("First", first);
            tabs.addTab("Second", second);
            var content = new JPanel(new BorderLayout());
            var controls = new JPanel();
            controls.add(toggle);
            controls.add(bar);
            controls.add(button);
            content.add(controls, BorderLayout.NORTH);
            content.add(tabs);
            SwingUtilities.updateComponentTreeUI(content);
            var window = new JFrame("Control motion verification");
            window.setContentPane(content);
            window.setSize(700, 440);
            window.setVisible(true);
            return window;
        });
        try {
            edt(() -> {
                assertInstanceOf(DesktopProgressBarUi.class, bar.getUI());
                assertInstanceOf(DesktopTabbedPaneUi.class, tabs.getUI());
                bar.setValue(10);
                paint(bar);
                bar.setValue(90);
                bar.setStringPainted(true);
                bar.setString("90%");
                paint(bar);
                assertEquals(90, bar.getValue());
                assertEquals("90%", bar.getString());
                toggle.doClick(0);
                paint(toggle);
                toggle.doClick(0);
                paint(toggle);
                tabs.setSelectedComponent(second);
                tabs.setSelectedComponent(first);
                tabs.setSelectedComponent(second);
                assertEquals(2, tabs.getTabCount());
                assertSame(second, tabs.getSelectedComponent());
                DesktopMotion.preference(false);
                assertEquals(0, DesktopMotion.runningCount());
                assertFalse(toggle.isSelected());
                bar.setIndeterminate(true);
                bar.setString("Busy");
                return null;
            });
            var staticFrame = edt(() -> paint(bar));
            Thread.sleep(180);
            assertArrayEquals(staticFrame, edt(() -> paint(bar)), "Unknown progress must become a static busy indication");
            edt(() -> {
                var popup = new JPopupMenu();
                var item = new JMenuItem("Confirm");
                item.addActionListener(event -> actions.incrementAndGet());
                popup.add(item);
                popup.show(button, 0, button.getHeight());
                assertTrue(popup.isVisible());
                item.doClick(0);
                popup.setVisible(false);
                assertEquals(1, actions.get());
                var dialog = new JDialog(frame, "Decision", false);
                dialog.getRootPane().putClientProperty("desktop.motion", true);
                var cancel = new JButton("Cancel");
                cancel.addActionListener(event -> {
                    actions.incrementAndGet();
                    dialog.dispose();
                });
                var content = new JPanel();
                content.add(new JTextField("Retained draft", 20));
                content.add(cancel);
                dialog.setContentPane(content);
                dialog.getRootPane().setDefaultButton(cancel);
                dialog.pack();
                dialog.setVisible(true);
                assertTrue(SwingUtilities.isDescendingFrom(content, dialog));
                cancel.doClick(0);
                assertFalse(dialog.isDisplayable());
                assertEquals(2, actions.get());
                assertEquals(0, DesktopMotion.runningCount());
                return null;
            });
        } finally {
            edt(() -> {
                frame.dispose();
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
                return null;
            });
        }
    }

    private static int[] paint(JComponent component) {
        var image = new BufferedImage(Math.max(1, component.getWidth()), Math.max(1, component.getHeight()), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
