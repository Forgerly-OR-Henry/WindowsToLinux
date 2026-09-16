package gold.debug.windowstolinux.app.ui.managed;

import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.*;
import java.awt.*;

/** Edits local application presentation in an independent window. / 在独立窗口中编辑本地应用显示设置。 */
public final class ApplicationPresentationDialog extends JDialog {
    /** Builds a form whose validation errors retain the entered values. / 创建验证失败时保留输入的表单。 */
    public ApplicationPresentationDialog(Window owner, ManagedApplicationFacade service, DesktopComponentFactory c,
                                         PageMessagePresenter messages, ApplicationSummary application, Runnable saved) {
        super(owner, messages.text("apps.edit"), ModalityType.APPLICATION_MODAL);
        JTextField name = new JTextField(application.name(), 28);
        JTextField url = new JTextField(application.accessUrl().map(value -> value.url().toString()).orElse(""), 28);
        JComboBox<String> category = new JComboBox<>(new String[]{"WEBSITE", "APP"});
        category.setSelectedItem(application.category());
        category.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, messages.text("apps.category." + value), index, selected, focus);
            }
        });
        JPanel form = c.card(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("apps.name"), name);
        c.addField(form, 1, 0, messages.text("apps.type"), category);
        c.addField(form, 2, 0, messages.text("field.userAccessUrl"), url);
        JLabel status = new JLabel();
        JButton save = c.primaryButton(messages.text("apps.save"));
        save.addActionListener(event -> {
            String enteredName = name.getText(), enteredCategory = (String) category.getSelectedItem(), enteredUrl = url.getText();
            save.setEnabled(false); name.setEnabled(false); category.setEnabled(false); url.setEnabled(false);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            DesktopTaskExecutor.run(() -> { service.saveApplicationPresentation(application.key(), enteredName, enteredCategory, enteredUrl); return true; },
                    result -> { saved.run(); dispose(); }, failure -> {
                        save.setEnabled(true); name.setEnabled(true); category.setEnabled(true); url.setEnabled(true);
                        setDefaultCloseOperation(DISPOSE_ON_CLOSE); status.setText(messages.safe(failure));
                    });
        });
        JPanel body = c.pagePanel(); body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20)); body.add(form, BorderLayout.NORTH);
        body.add(status); JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT)); actions.add(save); body.add(actions, BorderLayout.SOUTH);
        setContentPane(body); setSize(580, 320); setLocationRelativeTo(owner); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }
}
