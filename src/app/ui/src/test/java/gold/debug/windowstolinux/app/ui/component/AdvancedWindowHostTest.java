package gold.debug.windowstolinux.app.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.ui.display.ThemePalette;
import gold.debug.windowstolinux.app.ui.i18n.*;
import org.junit.jupiter.api.Test;

class AdvancedWindowHostTest {
    @Test
    void dockedAndFloatingInspectorsPreserveWorkspaceAndCloseWithTheirOwner() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame();
            JPanel workspace = new JPanel();
            var pane = new AdvancedOptionsPane(workspace, new DesktopComponentFactory(ThemePalette.light()),
                    new PageMessagePresenter(MessageCatalog.forLanguageTag("zh-CN")));
            pane.field("field.sshPort", new JTextField("2222"));
            JPanel root = new JPanel(new BorderLayout());
            root.add(pane);
            frame.setContentPane(root);
            frame.setBounds(0, 0, 520, 360);
            var controller = new AdvancedWindowHost(frame, root);
            controller.activate(pane);
            try {
                frame.setVisible(true);
                frame.validate();
                Dimension size = workspace.getSize();
                pane.setExpanded(true);
                frame.validate();
                assertEquals(size, workspace.getSize());
                assertEquals(840, frame.getWidth());
                pane.setExpanded(true);
                assertEquals(840, frame.getWidth());
                pane.setExpanded(false);
                frame.validate();
                assertEquals(520, frame.getWidth());
                Rectangle screen = frame.getGraphicsConfiguration().getBounds();
                frame.setLocation(screen.x + screen.width - 530, screen.y);
                pane.setExpanded(true);
                frame.validate();
                assertEquals(520, frame.getWidth());
                assertEquals(size, workspace.getSize());
                assertTrue(java.util.Arrays.stream(frame.getOwnedWindows()).anyMatch(Window::isShowing));
                pane.setExpanded(false);
                assertTrue(java.util.Arrays.stream(frame.getOwnedWindows()).noneMatch(Window::isShowing));
            } finally {
                controller.close();
                frame.dispose();
            }
        });
    }
}
