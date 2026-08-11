package gold.debug.windowstolinux.app.ui.managed;

import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleAction;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.function.Function;

/**
 * Provides the {@code ManagedPage} implementation.
 *
 * <p>提供 {@code ManagedPage} 实现。
 */
public final class ManagedPage {
    private ManagedPage() {
    }

    /**
     * Creates a value through {@code create}.
     *
     * <p>通过 {@code create} 创建值。
     *
     * @param c the {@code c} value / {@code c} 值
     * @param m the {@code m} value / {@code m} 值
     * @param applicationId the {@code applicationId} value / {@code applicationId} 值
     * @param output the {@code output} value / {@code output} 值
     * @param refresh the {@code refresh} value / {@code refresh} 值
     * @param lifecycleButton the {@code lifecycleButton} value / {@code lifecycleButton} 值
     * @return the operation result / 操作结果
     */
    public static JPanel create(DesktopComponents c, MessageCatalog m, JTextField applicationId,
                                JTextArea output, Runnable refresh, Function<LifecycleAction, JButton> lifecycleButton) {
        JPanel panel = c.pagePanel();
        JPanel controls = c.card(new BorderLayout(0, 12));
        controls.add(c.sectionHeading(m.text("section.lifecycle.title"), m.text("section.lifecycle.description")),
                BorderLayout.NORTH);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton refreshButton = c.secondaryButton(m.text("button.refreshApplications"));
        refreshButton.addActionListener(event -> refresh.run());
        actions.add(refreshButton);
        actions.add(new JLabel(m.text("field.applicationId")));
        actions.add(applicationId);
        actions.add(lifecycleButton.apply(LifecycleAction.REFRESH_STATUS));
        actions.add(lifecycleButton.apply(LifecycleAction.START));
        actions.add(lifecycleButton.apply(LifecycleAction.STOP));
        actions.add(lifecycleButton.apply(LifecycleAction.RESTART));
        actions.add(lifecycleButton.apply(LifecycleAction.ENABLE_AUTOSTART));
        actions.add(lifecycleButton.apply(LifecycleAction.DISABLE_AUTOSTART));
        controls.add(actions, BorderLayout.CENTER);
        panel.add(controls, BorderLayout.NORTH);
        panel.add(c.outputCard(m.text("section.applicationOutput.title"),
                m.text("section.applicationOutput.description"), output), BorderLayout.CENTER);
        return panel;
    }
}
