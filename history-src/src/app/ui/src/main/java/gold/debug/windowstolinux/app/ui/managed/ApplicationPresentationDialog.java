package gold.debug.windowstolinux.app.ui.managed;

import java.awt.*;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.contract.ManagedApplicationFacade;
import gold.debug.windowstolinux.app.service.execution.lifecycle.ApplicationSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;

/**
 * Edits local application presentation in an independent window. / 在独立窗口中编辑本地应用显示设置。
 */
public final class ApplicationPresentationDialog extends JDialog {
    /**
     * Builds a form whose validation errors retain the entered values. / 创建验证失败时保留输入的表单。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param saved saved / 已保存
     */
    public ApplicationPresentationDialog(Window owner, ManagedApplicationFacade service, DesktopComponentFactory c,
            PageMessagePresenter messages, ApplicationSummary application, Runnable saved) {
        super(owner, messages.text("apps.edit"), ModalityType.APPLICATION_MODAL);
        JTextField name = new JTextField(application.name(), 28);
        JTextField url = new JTextField(application.accessUrl().map(value -> value.url().toString()).orElse(""), 28);
        JComboBox<String> category = new JComboBox<>(new String[]{"WEBSITE", "APP"});
        category.setSelectedItem(application.category());
        category.setEnabled(application.external());
        category.setRenderer(new DefaultListCellRenderer() {
            /**
             * Returns list cell renderer component.
             * <p>返回列表Cell渲染器组件。
             *
             * @param list list / 列表
             * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
             * @param index index / 索引
             * @param selected explicitly selected item or state / 显式选择的项目或状态
             * @param focus focus / 焦点
             * @return list cell renderer component / 列表Cell渲染器组件
             */
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                return super.getListCellRendererComponent(list, messages.text("apps.category." + value), index,
                        selected, focus);
            }
        });
        JPanel form = c.card(new GridBagLayout());
        c.addField(form, 0, 0, messages.text("apps.name"), name);
        c.addField(form, 1, 0, messages.text("apps.type"), category);
        c.addField(form, 2, 0, messages.text("field.userAccessUrl"), url);
        JLabel status = new JLabel();
        JButton save = c.primaryButton(messages.text("apps.save"));
        save.addActionListener(event -> {
            String enteredName = name.getText(), enteredCategory = (String) category.getSelectedItem(),
                    enteredUrl = url.getText();
            save.setEnabled(false);
            name.setEnabled(false);
            category.setEnabled(false);
            url.setEnabled(false);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            DesktopTaskExecutor.run(() -> {
                service.saveApplicationPresentation(application.key(), enteredName, enteredCategory, enteredUrl);
                return true;
            }, result -> {
                saved.run();
                dispose();
            }, failure -> {
                save.setEnabled(true);
                name.setEnabled(true);
                category.setEnabled(application.external());
                url.setEnabled(true);
                setDefaultCloseOperation(DISPOSE_ON_CLOSE);
                status.setText(messages.safe(failure));
            });
        });
        JPanel body = c.pagePanel();
        body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        body.add(form, BorderLayout.NORTH);
        body.add(status);
        JPanel actions = c.transparent(new FlowLayout(FlowLayout.RIGHT));
        actions.add(save);
        body.add(actions, BorderLayout.SOUTH);
        setContentPane(body);
        setSize(580, 320);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }
}
