package gold.debug.windowstolinux.mod.deployment.ui.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;

import org.junit.jupiter.api.Test;
import gold.debug.windowstolinux.app.ui.display.*;
import gold.debug.windowstolinux.mod.deployment.ui.style.*;

/** Exercises the real author dialog shell without constructing a deployment or approving effects. */
class DeploymentMotionDialogTest {
    @Test
    void actualDialogMotionKeepsAuthoredPaddingInputsAndImmediateCloseAcrossDisplayVariants() throws Exception {
        String flag = System.getProperty("flatlaf.animation");
        boolean previous = DesktopMotion.preference();
        try {
            for (String language : new String[]{"zh-CN", "en"})
                for (var theme : new ThemeMode[]{ThemeMode.LIGHT, ThemeMode.DARK})
                    for (int scale : new int[]{100, 150})
                        for (boolean minimum : new boolean[]{false, true})
                            edt(() -> {
                                System.setProperty("flatlaf.animation", "true");
                                DesktopThemeService.install(theme, scale);
                                DesktopMotion.preference(true);
                                var owner = new JFrame("Deployment dialog motion");
                                owner.setSize(1280, 720);
                                owner.setVisible(true);
                                var body = new JPanel(new BorderLayout());
                                var field = new JTextField("Exact retained deployment draft", 35);
                                var document = field.getDocument();
                                body.add(field, BorderLayout.NORTH);
                                var facts = new JTextArea("Observed deployment facts / 已有部署信息\n".repeat(40));
                                facts.setEditable(false);
                                body.add(facts);
                                var closes = new AtomicInteger();
                                var cancel = new JButton(language.equals("en") ? "Cancel" : "取消");
                                var dialog = DeploymentDialogs.window(owner, "Deployment / 部署", body, cancel, 780, 540);
                                try {
                                    cancel.addActionListener(event -> {
                                        closes.incrementAndGet();
                                        dialog.dispose();
                                    });
                                    dialog.setVisible(true);
                                    var messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", Locale.forLanguageTag(language));
                                    DeploymentDialogs.refresh(dialog, messages);
                                    var shell = (JComponent) find(dialog, "deployment.dialog.shell");
                                    assertEquals(DeploymentPageStyle.scale(20), shell.getInsets().left);
                                    assertEquals(DeploymentPageStyle.scale(20), shell.getInsets().top);
                                    assertSame(document, field.getDocument());
                                    assertEquals("Exact retained deployment draft", field.getText());
                                    Rectangle area = DeploymentDialogs.workArea(dialog);
                                    if (minimum) {
                                        area.width = Math.min(area.width, 900);
                                        area.height = Math.min(area.height, 580);
                                    }
                                    DeploymentDialogs.constrain(dialog, area);
                                    assertTrue(area.contains(dialog.getBounds()));
                                    Rectangle action = SwingUtilities.convertRectangle(cancel.getParent(), cancel.getBounds(), dialog.getContentPane());
                                    assertTrue(new Rectangle(0, 0, dialog.getContentPane().getWidth(), dialog.getContentPane().getHeight()).contains(action));
                                    DesktopMotion.preference(false);
                                    cancel.doClick(0);
                                    assertEquals(1, closes.get());
                                    assertFalse(dialog.isDisplayable());
                                } finally {
                                    dialog.dispose();
                                    owner.dispose();
                                }
                                return null;
                            });
        } finally {
            edt(() -> {
                if (flag == null) System.clearProperty("flatlaf.animation");
                else System.setProperty("flatlaf.animation", flag);
                DesktopMotion.preference(previous);
                DesktopThemeService.install(ThemeMode.LIGHT, 100);
                return null;
            });
        }
    }

    private static Component find(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container parent) for (var child : parent.getComponents()) {
            var found = find(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
