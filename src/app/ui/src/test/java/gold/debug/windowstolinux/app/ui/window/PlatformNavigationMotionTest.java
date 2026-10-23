package gold.debug.windowstolinux.app.ui.window;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.*;

import com.formdev.flatlaf.ui.FlatBorder;
import gold.debug.windowstolinux.app.ui.control.*;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.app.ui.language.MessageCatalog;
import org.junit.jupiter.api.Test;

class PlatformNavigationMotionTest {
    @Test
    void actualSidebarFramesKeepStableIdentityDuringCollapseExpansionAndReversal() throws Exception {
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        String previousAnimation = System.getProperty("flatlaf.animation");
        try {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (int scale : new int[]{100, 150, 200}) {
                    var frame = new JFrame();
                    var shell = edt(() -> {
                        DesktopThemeService.install(theme, scale);
                        var messages = MessageCatalog.forLanguageTag("zh-CN");
                        var pages = new JTabbedPane();
                        var navigation = new PlatformNavigationShell(pages, messages, theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark());
                        for (String key : new String[]{"platform.servers", "platform.models", "settings.appearance.title"}) {
                            var page = new JPanel();
                            pages.addTab(messages.text(key), page);
                            navigation.register(page, key, "navigation/settings", 10, key.startsWith("settings"));
                        }
                        var status = new DesktopLockStatus(new gold.debug.windowstolinux.app.ui.error.PageMessagePresenter(messages), () -> {
                        });
                        status.refresh(true, true);
                        navigation.utilities().add(status);
                        frame.setContentPane(navigation);
                        frame.setSize(1080, 720);
                        frame.setLocation(100, 80);
                        frame.setAlwaysOnTop(true);
                        frame.setVisible(true);
                        frame.validate();
                        return navigation;
                    });
                    var robot = new Robot();
                    robot.waitForIdle();
                    Thread.sleep(250);
                    try {
                        record(frame, shell, robot, true, theme + "-" + scale + "-collapse");
                        record(frame, shell, robot, false, theme + "-" + scale + "-expand");
                        edt(() -> {
                            shell.collapse(true, true);
                            return null;
                        });
                        Thread.sleep(70);
                        edt(() -> {
                            int before = named(shell, "platform.sidebar").getPreferredSize().width;
                            shell.collapse(false, true);
                            assertEquals(before, named(shell, "platform.sidebar").getPreferredSize().width,
                                "Reversing must retain current position");
                            return null;
                        });
                        Thread.sleep(600);
                        assertEquals(PlatformNavigationShell.SIDEBAR_WIDTH, edt(() -> named(shell, "platform.sidebar").getWidth()));
                        System.setProperty("flatlaf.animation", "false");
                        edt(() -> {
                            shell.collapse(true, true);
                            frame.validate();
                            return null;
                        });
                        assertEquals(com.formdev.flatlaf.util.UIScale.scale(64), edt(() -> named(shell, "platform.sidebar").getWidth()));
                        if (previousAnimation == null) System.clearProperty("flatlaf.animation");
                        else System.setProperty("flatlaf.animation", previousAnimation);
                    } finally {
                        edt(() -> {
                            shell.close();
                            frame.dispose();
                            return null;
                        });
                    }
                }
        } finally {
            if (previousAnimation == null) System.clearProperty("flatlaf.animation");
            else System.setProperty("flatlaf.animation", previousAnimation);
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
                return null;
            });
        }
    }

    @Test
    void inputsAndActionsUseBorderlessSurfacesWithDifferentHoverAndSelectionColors() throws Exception {
        var width = FlatBorder.class.getDeclaredMethod("getBorderWidth", Component.class);
        width.setAccessible(true);
        edt(() -> {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                DesktopThemeService.install(theme, 100);
                var factory = new DesktopPartFactory(theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark());
                var action = factory.primaryButton("");
                var secondary = factory.secondaryButton("");
                for (JComponent component : new JComponent[]{action, secondary, new JTextField(), new JPasswordField(), new JComboBox<>(), new JSpinner()}) {
                    assertInstanceOf(FlatBorder.class, component.getBorder());
                    assertEquals(0f, width.invoke(component.getBorder(), component));
                }
                assertNotEquals(action.getBackground(), secondary.getBackground());
                assertEquals(UIManager.getColor("Panel.background"), UIManager.getColor("TextField.background"));
                var regularCard = factory.inventoryCard("", "", "", false);
                var selectedCard = factory.inventoryCard("", "", "", true);
                assertEquals(UIManager.getColor("Table.background").getRGB(), cardPixel(regularCard));
                assertEquals(UIManager.getColor("List.selectionBackground").getRGB(), cardPixel(selectedCard));
                assertNotEquals(cardPixel(regularCard), cardPixel(selectedCard));
                var normal = pixel(action);
                action.getModel().setRollover(true);
                assertNotEquals(normal, pixel(action), "Hover feedback must remain visible without a border");
            }
            return null;
        });
    }

    @Test
    void collapsedFeedbackAndSecurityCaptionsStayHorizontallyCentered() throws Exception {
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            edt(() -> {
                for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int scale : new int[]{100, 150, 200}) {
                        DesktopThemeService.install(theme, scale);
                        var navigation = new DesktopNavigationButton("Navigation");
                        navigation.setMargin(new Insets(8, 10, 8, 10));
                        navigation.setBorderPainted(false);
                        navigation.setBackground(Color.GREEN);
                        navigation.putClientProperty("FlatLaf.style", Map.of("hoverBackground", Color.GREEN, "pressedBackground", Color.GREEN));
                        var iconPosition = new java.util.concurrent.atomic.AtomicReference<Point>();
                        navigation.setIcon(new Icon() {
                            public int getIconWidth() {
                                return com.formdev.flatlaf.util.UIScale.scale(19);
                            }

                            public int getIconHeight() {
                                return getIconWidth();
                            }

                            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                                iconPosition.set(new Point(x, y));
                                graphics.setColor(Color.BLUE);
                                graphics.fillRect(x, y, getIconWidth(), getIconHeight());
                            }
                        });
                        navigation.presentation(1);
                        navigation.setSize(com.formdev.flatlaf.util.UIScale.scale(64) - 16, com.formdev.flatlaf.util.UIScale.scale(38));
                        for (boolean hover : new boolean[]{false, true}) {
                            navigation.getModel().setRollover(hover);
                            var image = render(navigation);
                            int left = image.getWidth(), right = -1;
                            for (int x = 0; x < image.getWidth(); x++)
                                if (image.getRGB(x, image.getHeight() / 2) == Color.GREEN.getRGB()) {
                                    left = Math.min(left, x);
                                    right = x;
                                }
                            assertTrue(right > left);
                            assertEquals(iconPosition.get().x + navigation.getIcon().getIconWidth() / 2d, (left + right + 1) / 2d, 1,
                                "Collapsed feedback must have equal space on both sides of the fixed icon");
                        }
                        for (String language : new String[]{"zh-CN", "en"})
                            for (boolean locked : new boolean[]{false, true}) {
                                var control = new DesktopLockStatus(new gold.debug.windowstolinux.app.ui.error.PageMessagePresenter(
                                    MessageCatalog.forLanguageTag(language)), () -> {
                                });
                                control.refresh(true, locked);
                                var action = (JButton) named(control, "security.lock");
                                action.setForeground(Color.BLUE);
                                String caption = action.getText();
                                for (double progress : new double[]{0, 1}) {
                                    control.presentation(progress);
                                    control.setSize((int) Math.round(PlatformNavigationShell.SIDEBAR_WIDTH - 16
                                        - PlatformNavigationShell.sidebarTravel() * progress), control.getPreferredSize().height);
                                    control.doLayout();
                                    var image = render(action);
                                    int left = image.getWidth(), right = -1;
                                    for (int x = 0; x < image.getWidth(); x++)
                                        for (int y = 0; y < image.getHeight(); y++) {
                                            var pixel = new Color(image.getRGB(x, y), true);
                                            if (pixel.getBlue() - pixel.getRed() > 64 && pixel.getBlue() - pixel.getGreen() > 64) {
                                                left = Math.min(left, x);
                                                right = Math.max(right, x);
                                            }
                                        }
                                    assertTrue(right > left);
                                    assertEquals(image.getWidth() / 2d, (left + right + 1) / 2d, com.formdev.flatlaf.util.UIScale.scale(1),
                                        "Lock/unlock content center: " + theme + "/" + scale + "/" + language + "/" + locked + "/" + progress);
                                    assertEquals(caption, action.getText(), "Clipping must preserve the full accessible caption");
                                }
                            }
                    }
                return null;
            });
        } finally {
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
                return null;
            });
        }
    }

    private static BufferedImage render(JComponent component) {
        var image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    @Test
    void contentRatioAndFixedShellDimensionsSurviveResizeAndUiZoom() throws Exception {
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                for (int scale : new int[]{100, 150, 200}) {
                    var frame = edt(() -> {
                        DesktopThemeService.install(theme, scale);
                        var messages = MessageCatalog.forLanguageTag("en");
                        var pages = new PlatformNavigationShell.PageDeck();
                        pages.setName("platform.pages");
                        pages.addTab("Home", new JPanel());
                        var shell = new PlatformNavigationShell(pages, messages,
                            theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark());
                        shell.home(pages.getComponentAt(0), () -> {
                        });
                        var window = new JFrame();
                        var footer = new JPanel();
                        footer.setPreferredSize(new Dimension(0, 30));
                        window.add(shell, BorderLayout.CENTER);
                        window.add(footer, BorderLayout.SOUTH);
                        DesktopWindowGeometry.install(window);
                        window.setVisible(true);
                        return window;
                    });
                    try {
                        assertContentSize(frame, 1600, 900);
                        edt(() -> {
                            frame.setSize(1728 + frame.getWidth() - frame.getContentPane().getWidth(),
                                900 + frame.getHeight() - frame.getContentPane().getHeight());
                            return null;
                        });
                        assertContentSize(frame, 1728, 972);
                        edt(() -> {
                            frame.setSize(1728 + frame.getWidth() - frame.getContentPane().getWidth(),
                                810 + frame.getHeight() - frame.getContentPane().getHeight());
                            return null;
                        });
                        assertContentSize(frame, 1440, 810);
                        edt(() -> {
                            frame.setSize(1000, 600);
                            return null;
                        });
                        assertContentSize(frame, 1280, 720);
                    } finally {
                        edt(() -> {
                            ((PlatformNavigationShell) named(frame, "platform.shell")).close();
                            frame.dispose();
                            return null;
                        });
                    }
                }
        } finally {
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
                return null;
            });
        }
    }

    private static void assertContentSize(JFrame frame, int width, int height) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (edt(() -> frame.getContentPane().getWidth() == width && frame.getContentPane().getHeight() == height))
                break;
            Thread.sleep(10);
        }
        edt(() -> {
            frame.validate();
            assertEquals(new Dimension(width, height), frame.getContentPane().getSize());
            assertEquals(160, named(frame, "platform.sidebar").getWidth());
            assertEquals(60, named(frame, "platform.header").getHeight());
            assertEquals(new Dimension(width - 160, height - 90), named(frame, "platform.pages").getSize());
            var title = named(frame, "platform.pageTitle");
            var description = named(frame, "platform.pageDescription");
            assertTrue(description.isVisible());
            assertTrue(title.getX() + title.getWidth() < description.getX(), "Description belongs to the right of the title");
            assertEquals(title.getBounds().getCenterY(), description.getBounds().getCenterY(), 1,
                "Header captions must share one horizontal row at every supported scale");
            return null;
        });
    }

    private static int cardPixel(JComponent card) {
        card.setSize(200, 116);
        card.doLayout();
        var image = new BufferedImage(200, 116, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            card.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image.getRGB(60, 40);
    }

    private static int pixel(JButton button) {
        return pixel(button, 20, 21);
    }

    private static int pixel(JButton button, int x, int y) {
        button.setSize(160, 42);
        var image = new BufferedImage(160, 42, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            button.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image.getRGB(x, y);
    }

    private static void record(JFrame frame, PlatformNavigationShell shell, Robot robot, boolean collapse, String name) throws Exception {
        var icon = edt(() -> {
            shell.collapse(collapse, true);
            return ((JButton) named(shell, "navigation.collapse")).getIcon();
        });
        var screenshots = new ArrayList<BufferedImage>();
        int previousWidth = edt(() -> named(shell, "platform.sidebar").getWidth());
        int previousSettingsY = edt(() -> named(shell, "navigation.settings").getLocationOnScreen().y);
        int stateY = edt(() -> named(shell, "security.state").getLocationOnScreen().y);
        for (int sample = 0; sample < 16; sample++) {
            Thread.sleep(33);
            var bounds = edt(() -> new Rectangle(frame.getLocationOnScreen(), frame.getSize()));
            screenshots.add(robot.createScreenCapture(bounds));
            int current = edt(() -> {
                assertSame(icon, ((JButton) named(shell, "navigation.collapse")).getIcon(), "Each frame must reuse the icon");
                assertFooterLayout(shell);
                assertEquals(stateY, named(shell, "security.state").getLocationOnScreen().y,
                    "Status must stay at the same vertical position throughout the transition");
                assertEquals(named(shell, "navigation.settings").getHeight(), named(shell, "security.lock").getHeight(),
                    "Security action must match the settings height");
                return named(shell, "platform.sidebar").getWidth();
            });
            assertTrue(collapse ? current <= previousWidth : current >= previousWidth, "Motion must be continuous and monotonic");
            previousWidth = current;
            int settingsY = edt(() -> named(shell, "navigation.settings").getLocationOnScreen().y);
            assertTrue(collapse ? settingsY <= previousSettingsY : settingsY >= previousSettingsY,
                "Settings must slide continuously with the footer height");
            previousSettingsY = settingsY;
        }
        assertEquals((collapse ? com.formdev.flatlaf.util.UIScale.scale(64) : PlatformNavigationShell.SIDEBAR_WIDTH), previousWidth);
        assertEquals("WindowsToLinux", edt(() -> ((JButton) named(shell, "navigation.logo")).getText()));
        edt(() -> {
            var state = named(shell, "security.state");
            var action = named(shell, "security.lock");
            var settings = (JButton) named(shell, "navigation.settings");
            if (collapse) {
                assertTrue(action.getY() + action.getHeight() <= state.getY(), "Collapsed action belongs above status");
                assertEquals(action.getBounds().getCenterX(), state.getBounds().getCenterX(), .5);
                int settingsGap = action.getLocationOnScreen().y - settings.getLocationOnScreen().y - settings.getHeight();
                int stateGap = state.getY() - action.getY() - action.getHeight();
                assertEquals(com.formdev.flatlaf.util.UIScale.scale(46) - settings.getHeight(), settingsGap);
                assertEquals(settingsGap, stateGap, "Settings, action and status must have equal vertical gaps");
            } else {
                var control = named(shell, "security.statusControl");
                assertEquals(settings.getWidth(), control.getWidth());
                assertTrue(Math.abs(state.getWidth() - action.getWidth()) <= 1, "Expanded status and action must split the width equally");
                assertEquals(control.getWidth(), state.getWidth() + action.getWidth());
                assertEquals(0, state.getX());
                assertEquals(state.getWidth(), action.getX());
                var iconBounds = new Rectangle();
                var insets = settings.getInsets();
                SwingUtilities.layoutCompoundLabel(settings, settings.getFontMetrics(settings.getFont()), settings.getText(), settings.getIcon(),
                    settings.getVerticalAlignment(), settings.getHorizontalAlignment(), settings.getVerticalTextPosition(), settings.getHorizontalTextPosition(),
                    new Rectangle(insets.left, insets.top, settings.getWidth() - insets.left - insets.right,
                        settings.getHeight() - insets.top - insets.bottom), iconBounds, new Rectangle(), settings.getIconTextGap());
                var label = (JLabel) state;
                assertEquals(settings.getLocationOnScreen().x + iconBounds.getCenterX(),
                    state.getLocationOnScreen().x + label.getInsets().left + label.getIcon().getIconWidth() / 2d, .5,
                    "Expanded status lock must align with the settings gear");
                assertTrue(state.getX() + state.getWidth() <= action.getX(), "Expanded status belongs left of action");
            }
            return null;
        });
        Path directory = Path.of("target", "sidebar-motion");
        Files.createDirectories(directory);
        for (int index : new int[]{0, 2, 5, 15})
            ImageIO.write(screenshots.get(index), "png", directory.resolve(name + "-" + index + ".png").toFile());
    }

    private static void assertFooterLayout(PlatformNavigationShell shell) {
        var control = (Container) named(shell, "security.statusControl");
        var state = named(shell, "security.state");
        var action = named(shell, "security.lock");
        assertFalse(state.getBounds().intersects(action.getBounds()), "Status and action must never overlap during rotation");
        var bounds = new Rectangle(control.getSize());
        assertTrue(bounds.contains(state.getBounds()), "State must remain inside the sidebar footer");
        assertTrue(bounds.contains(action.getBounds()), "Action must remain inside the sidebar footer");
    }

    @Test
    void navigationIconsStayFixedWhileFullCaptionsFadeLinearlyWithoutRecreatingActions() throws Exception {
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            edt(() -> {
                for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int scale : new int[]{100, 150, 200})
                        for (String text : new String[]{"服务器、模型及导航设置", "MMMMM navigation settings"}) {
                            DesktopThemeService.install(theme, scale);
                            var button = new DesktopNavigationButton(text);
                            button.setMargin(new Insets(8, 10, 8, 10));
                            button.setIconTextGap(10);
                            button.setBorderPainted(false);
                            button.setContentAreaFilled(false);
                            button.setOpaque(false);
                            button.setForeground(Color.RED);
                            var position = new java.util.concurrent.atomic.AtomicReference<Point>();
                            var paints = new java.util.concurrent.atomic.AtomicInteger();
                            button.setIcon(new Icon() {
                                public int getIconWidth() {
                                    return com.formdev.flatlaf.util.UIScale.scale(19);
                                }

                                public int getIconHeight() {
                                    return getIconWidth();
                                }

                                public void paintIcon(Component component, Graphics graphics, int x, int y) {
                                    position.set(new Point(x, y));
                                    paints.incrementAndGet();
                                    graphics.setColor(Color.BLUE);
                                    graphics.fillRect(x, y, getIconWidth(), getIconHeight());
                                }
                            });
                            var model = button.getModel();
                            Point originalPosition = null;
                            int previousAlpha = 255;
                            for (int sample = 0; sample <= 120; sample++) {
                                double progress = sample / 120d;
                                button.presentation(progress);
                                button.setSize((int) Math.round((PlatformNavigationShell.SIDEBAR_WIDTH - 16)
                                    - PlatformNavigationShell.sidebarTravel() * progress), com.formdev.flatlaf.util.UIScale.scale(38));
                                var image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
                                var graphics = image.createGraphics();
                                try {
                                    button.paint(graphics);
                                } finally {
                                    graphics.dispose();
                                }
                                assertEquals(sample + 1, paints.get(), "The icon must be painted at every width, including the collapsed endpoint");
                                if (originalPosition == null) originalPosition = position.get();
                                assertEquals(originalPosition, position.get(), "Caption clipping must never move the icon");
                                assertSame(model, button.getModel());
                                assertEquals(text, button.getText());
                                int textX = originalPosition.x + button.getIcon().getIconWidth() + button.getIconTextGap();
                                int alpha = 0;
                                for (int x = textX; x < image.getWidth(); x++)
                                    for (int y = 0; y < image.getHeight(); y++)
                                        alpha = Math.max(alpha, image.getRGB(x, y) >>> 24);
                                assertTrue(alpha <= previousAlpha, "Caption opacity must decrease continuously");
                                assertEquals(Math.round(255 * (1 - progress)), alpha, 2, "Caption opacity must follow the shared linear progress");
                                previousAlpha = alpha;
                            }
                        }
                return null;
            });
        } finally {
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
                return null;
            });
        }
    }

    @Test
    void logoHasNoHoverPressOrSelectionSurfaceAndSettingsInitiallyOpensTheFirstCategory() throws Exception {
        edt(() -> {
            for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK}) {
                DesktopThemeService.install(theme, 100);
                var messages = MessageCatalog.forLanguageTag("zh-CN");
                var pages = new JTabbedPane();
                var shell = new PlatformNavigationShell(pages, messages, theme == ThemeMode.LIGHT ? ThemePalette.light() : ThemePalette.dark());
                try {
                    var home = new JPanel();
                    pages.addTab("home", home);
                    shell.home(home, () -> pages.setSelectedComponent(home));
                    var password = new JPanel();
                    pages.addTab("password", password);
                    shell.register(password, "security.settings", "security/shield", 20, true);
                    var appearance = new JPanel();
                    pages.addTab("appearance", appearance);
                    shell.register(appearance, "settings.appearance.title", "navigation/settings", 10, true);
                    ((JButton) named(shell, "navigation.settings")).doClick();
                    assertSame(appearance, pages.getSelectedComponent());
                    var logo = (JButton) named(shell, "navigation.logo");
                    logo.doClick();
                    assertSame(home, pages.getSelectedComponent());
                    assertEquals(0, pixel(logo, 150, 20));
                    logo.getModel().setRollover(true);
                    assertEquals(0, pixel(logo, 150, 20));
                    logo.getModel().setArmed(true);
                    logo.getModel().setPressed(true);
                    logo.getModel().setSelected(true);
                    assertEquals(0, pixel(logo, 150, 20));
                    assertFalse(logo.isBorderPainted());
                    assertFalse(logo.isFocusPainted());
                } finally {
                    shell.close();
                }
            }
            return null;
        });
    }

    @Test
    void footerMovesContinuouslyWithoutCaptionOrLayoutPhaseSwitches() throws Exception {
        float previousZoom = com.formdev.flatlaf.util.UIScale.getZoomFactor();
        try {
            edt(() -> {
                for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int scale : new int[]{100, 150, 200})
                        for (String locale : new String[]{"zh-CN", "en"}) {
                            DesktopThemeService.install(theme, scale);
                            var control = new DesktopLockStatus(new gold.debug.windowstolinux.app.ui.error.PageMessagePresenter(
                                MessageCatalog.forLanguageTag(locale)), () -> {
                            });
                            control.refresh(true, true);
                            var state = (JLabel) named(control, "security.state");
                            var action = (JButton) named(control, "security.lock");
                            String originalState = state.getText(), originalAction = action.getText();
                            Rectangle previousState = null, previousAction = null;
                            Integer anchoredStateY = null;
                            for (int sample = 0; sample <= 240; sample++) {
                                double progress = sample / 240d;
                                control.presentation(progress);
                                control.setSize((int) Math.round((PlatformNavigationShell.SIDEBAR_WIDTH - 16)
                                    - PlatformNavigationShell.sidebarTravel() * progress), control.getPreferredSize().height);
                                control.doLayout();
                                assertEquals(originalState, state.getText(), "Keep the original caption while paint fades it");
                                assertEquals(originalAction, action.getText());
                                assertFalse(state.getBounds().intersects(action.getBounds()));
                                var bounds = new Rectangle(control.getSize());
                                assertTrue(bounds.contains(state.getBounds()));
                                assertTrue(bounds.contains(action.getBounds()));
                                int stateY = state.getY() - control.getHeight();
                                if (anchoredStateY == null) anchoredStateY = stateY;
                                assertEquals(anchoredStateY.intValue(), stateY,
                                    "Footer growth must not move the bottom-anchored state vertically");
                                assertEquals(com.formdev.flatlaf.util.UIScale.scale(38), action.getHeight());
                                assertTrue(Math.abs(action.getWidth() - state.getWidth()) <= 1);
                                double offsetX = action.getBounds().getCenterX() - state.getBounds().getCenterX();
                                double offsetY = state.getBounds().getCenterY() - action.getBounds().getCenterY();
                                double horizontalRadius = (control.getWidth() + PlatformNavigationShell.sidebarTravel() * progress) / 2;
                                assertEquals(horizontalRadius * Math.cos(progress * Math.PI / 2), offsetX, 1.0,
                                    "Horizontal action position must follow the quarter ellipse around status");
                                assertEquals(com.formdev.flatlaf.util.UIScale.scale(46) * Math.sin(progress * Math.PI / 2), offsetY, 1.0,
                                    "Vertical action position must follow the same quarter ellipse");
                                if (previousState != null) {
                                    int tolerance = com.formdev.flatlaf.util.UIScale.scale(3);
                                    assertTrue(Math.abs(state.getX() - previousState.x) <= tolerance);
                                    assertTrue(Math.abs(state.getY() - previousState.y) <= tolerance);
                                    assertTrue(Math.abs(action.getX() - previousAction.x) <= tolerance);
                                    assertTrue(Math.abs(action.getY() - previousAction.y) <= tolerance);
                                }
                                previousState = state.getBounds();
                                previousAction = action.getBounds();
                                var painted = new BufferedImage(control.getWidth(), control.getHeight(), BufferedImage.TYPE_INT_ARGB);
                                var graphics = painted.createGraphics();
                                try {
                                    control.paint(graphics);
                                } finally {
                                    graphics.dispose();
                                }
                                if (sample == 0 || sample == 120 || sample == 240) {
                                    Path directory = Path.of("target", "sidebar-motion", "footer-render");
                                    Files.createDirectories(directory);
                                    ImageIO.write(painted, "png", directory.resolve(theme + "-" + scale + "-" + locale + "-" + sample + ".png").toFile());
                                }
                            }
                        }
                return null;
            });
        } finally {
            edt(() -> {
                DesktopThemeService.install(ThemeMode.LIGHT, Math.round(previousZoom * 100));
                return null;
            });
        }
    }

    private static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container) for (var child : container.getComponents()) {
            var found = named(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static <T> T edt(java.util.concurrent.Callable<T> task) throws Exception {
        var future = new FutureTask<>(task);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}
