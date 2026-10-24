package gold.debug.windowstolinux.mod.deployment.ui.application;

import java.awt.BorderLayout;
import java.util.Optional;
import java.util.function.Function;
import javax.swing.*;

import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentDialogs;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

/** Application forms reuse the same controls in production and capability-free previews. / 生产与无能力预览复用同一应用表单控件。 */
public final class DeploymentApplicationForms {
    /** Prevents helper instances. / 禁止工具实例。 */
    private DeploymentApplicationForms() {
    }

    /** Original editable metadata controls. / 原可编辑元数据控件。
     * @param panel complete form / 完整表单
     * @param name original name / 原名称
     * @param category original category / 原类别
     * @param url original access URL / 原访问地址 */
    public record Editor(JPanel panel, JTextField name, JComboBox<String> category, JTextField url) {
    }

    /** Creates the actual metadata form without admitting an edit. / 创建实际元数据表单，不接纳编辑。
     * @param name original name / 原名称
     * @param category original category / 原类别
     * @param accessUrl original access URL / 原访问地址
     * @param editableCategory whether category is editable / 类别是否可编辑
     * @param texts live labels / 实时标签
     * @return retained original controls / 保留的原控件 */
    public static Editor editor(String name, String category, Optional<String> accessUrl, boolean editableCategory, Function<String, String> texts) {
        var panel = DeploymentDialogs.form();
        var nameField = new JTextField(name, 24);
        nameField.setName("applications.editor.name");
        var categories = new JComboBox<>(new String[]{"APP", "WEBSITE"});
        categories.setSelectedItem(category);
        categories.setEnabled(editableCategory);
        var url = new JTextField(accessUrl.orElse(""), 24);
        url.setName("applications.editor.url");
        url.setEnabled(category.equals("WEBSITE"));
        categories.addActionListener(ignored -> url.setEnabled("WEBSITE".equals(categories.getSelectedItem())));
        panel.add(label("name", texts));
        panel.add(nameField);
        panel.add(label("category", texts));
        panel.add(categories);
        panel.add(label("accessUrl", texts));
        panel.add(url);
        return new Editor(panel, nameField, categories, url);
    }

    /** Creates the actual release-choice content. / 创建实际发布选择内容。
     * @param choices original complete release choices / 原完整发布选项
     * @param texts live labels / 实时标签
     * @return retained choice form / 保留的选择表单 */
    public static JPanel history(JComboBox<?> choices, Function<String, String> texts) {
        var body = DeploymentDialogs.form();
        choices.setToolTipText(java.util.Objects.toString(choices.getSelectedItem(), ""));
        choices.addActionListener(event -> choices.setToolTipText(java.util.Objects.toString(choices.getSelectedItem(), "")));
        body.add(DeploymentDialogs.fact(() -> texts.apply("chooseHistory")));
        body.add(choices);
        return body;
    }

    /** Builds the actual discovery surface with a fixed adoption action row. / 构造实际发现界面并固定接管操作行。
     * @param scan original scan controls / 原扫描控件
     * @param choices original observed candidates / 原观测候选
     * @param issues original observation messages / 原观测消息
     * @param adoption original adoption actions / 原接管操作
     * @return original discovery body / 原发现内容 */
    public static JPanel discovery(JComponent scan, JComponent choices, JTextArea issues, JComponent adoption) {
        var body = DeploymentDialogs.form();
        body.add(scan);
        body.add(choices);
        body.add(DeploymentPageStyle.scroll(issues));
        body.add(adoption);
        body.putClientProperty("deployment.dialog.fixedActions", adoption);
        return body;
    }

    /** Creates the actual gray log viewport with fixed refresh and cancellation actions. / 创建实际灰底日志视口并固定刷新及取消操作。
     * @param tasks original task text / 原任务文字
     * @param actions original task actions / 原任务操作
     * @return original task window body / 原任务窗口内容 */
    public static JPanel logs(JTextArea tasks, JComponent actions) {
        var body = new JPanel(new BorderLayout(0, DeploymentPageStyle.scale(12)));
        tasks.putClientProperty("deployment.style.log", true);
        var scroll = new JScrollPane(tasks, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        body.add(scroll);
        body.add(actions, BorderLayout.SOUTH);
        body.putClientProperty("deployment.dialog.fixedActions", actions);
        body.putClientProperty("deployment.dialog.scrollManaged", true);
        body.putClientProperty("deployment.style.window", true);
        return body;
    }

    /** Displays the complete reviewed association before a decision. / 在决定前展示完整经审阅关联。
     * @param value localized exact association facts / 本地化精确关联事实
     * @return complete wrapping review text / 完整换行审阅文字 */
    public static JTextArea reattachment(java.util.function.Supplier<String> value) {
        var body = DeploymentDialogs.fact(value);
        body.setRows(14);
        body.setColumns(70);
        return body;
    }

    /** Retains a label key through appearance changes. / 外观变化时保留标签键。
     * @param key original catalog key / 原文案键
     * @param texts current labels / 当前标签
     * @return original labeled control / 原标签控件 */
    private static JLabel label(String key, Function<String, String> texts) {
        var label = new JLabel(texts.apply(key));
        label.putClientProperty("deployment.style.title", key);
        return label;
    }
}
