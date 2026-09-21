package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.service.contract.definition.*;
import gold.debug.windowstolinux.app.service.contract.DesktopRecoveryInteraction;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.shared.deploy.contract.AutomaticDeploymentInteraction;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;
import gold.debug.windowstolinux.shared.ai.collaboration.role.DeploymentInputRoleContext;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/**
 * One grouped missing-input form with a contextual AI conversation in its right inspector. / 集中展示缺失输入的表单，右侧检查面板提供上下文 AI 对话。
 */
public final class DeploymentInputDialog implements AutomaticDeploymentInteraction, DesktopRecoveryInteraction {
    /**
     * Component or resource identity owning the operation.
     * <p>持有操作的组件或资源身份。
     */
    private final Component owner;
    /**
     * Bound ai application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的AI应用门面协作对象。
     */
    private final AiApplicationFacade service;
    /**
     * Reviewed components in the application graph.
     * <p>应用图中的已审阅组件。
     */
    private final DesktopComponentFactory components;
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;


    /**
     * Binds the optional assistant and secret-copy supplier without retaining secret values. / 绑定可选助手和秘密副本提供方，不保留秘密值。
     *
     * @param owner component or resource identity owning the operation / 持有操作的组件或资源身份
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param components reviewed components in the application graph / 应用图中的已审阅组件
     * @param messages localized message resolver / 本地化消息解析器
     */
    public DeploymentInputDialog(Component owner, AiApplicationFacade service, DesktopComponentFactory components,
                                 PageMessagePresenter messages) {
        this.owner = owner; this.service = service; this.components = components; this.messages = messages;
    }

    /**
     * Displays all current non-secret missing fields together. / 集中展示当前所有非秘密缺失字段。
     *
     * @param fields allowed or requested input field definitions / 允许或请求的输入字段定义
     * @return matching result, or empty when no admitted value exists / 匹配结果；不存在已准入内容时为空
     */
    @Override public Optional<Map<String, String>> requestInputs(List<DeploymentInputField> fields) {
        return onEdt(() -> {
            JPanel form = components.transparent(new GridBagLayout());
            Map<String, JComponent> inputs = new LinkedHashMap<>();
            int row = 0;
            for (var field : fields) {
                JComponent input = input(field);
                JPanel line = components.transparent(new BorderLayout(6, 0));
                line.add(input, BorderLayout.CENTER);
                line.add(AdvancedOptionsPane.help(messages.text(field.helpKey())), BorderLayout.EAST);
                int separator = field.id().lastIndexOf('/');
                String group = separator < 0 ? field.id() : field.id().substring(0, separator);
                if (group.startsWith("db/")) group = group.substring(3);
                components.addField(form, row++, 0, group + " · " + messages.text(field.labelKey()), line);
                inputs.put(field.id(), input);
            }
            JScrollPane formScroll = new JScrollPane(form);
            formScroll.setPreferredSize(new Dimension(520, Math.min(430, 70 + fields.size() * 48)));
            int choice = JOptionPane.showConfirmDialog(owner, formScroll, messages.text("auto.input.title"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return Optional.empty();
            Map<String, String> values = new LinkedHashMap<>();
            inputs.forEach((id, input) -> values.put(id, input instanceof JTextField text ? text.getText().trim()
                    : input instanceof JScrollPane scroll ? ((JTextArea) scroll.getViewport().getView()).getText().trim()
                    : Objects.toString(((JComboBox<?>) input).getSelectedItem(), "")));
            return Optional.of(values);
        });
    }

    /**
     * Builds J component from the supplied input inputs.
     * <p>根据所提供输入输入构建J组件。
     *
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @return j component from the supplied input inputs / 根据所提供输入输入构建J组件
     */
    private JComponent input(DeploymentInputField field) {
        if (field.id().equals("applicationDeclaration") || field.id().endsWith("/applicationDeclaration")) {
            JTextArea declaration = new JTextArea(field.value(), 12, 44);
            declaration.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            return new JScrollPane(declaration);
        }
        if (field.choices().isEmpty()) return new JTextField(field.value(), 24);
        JComboBox<String> choices = new JComboBox<>(field.choices().toArray(String[]::new));
        choices.setRenderer(new DefaultListCellRenderer() {
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
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, messages.inputChoice(field, Objects.toString(value, "")), index, selected, focus);
            }
        });
        if (!field.value().isBlank()) choices.setSelectedItem(field.value());
        return choices;
    }

    /**
     * Requires a separate affirmative decision for a concrete risk. / 要求对具体风险作出独立的肯定决策。
     *
     * @param details details / 详情
     * @return true when requires a separate affirmative decision for a concrete risk, false otherwise / 要求对具体风险作出独立的肯定决策时为 true，否则为 false
     */
    @Override public boolean confirmDatabaseReplacement(Map<String, ?> details) {
        return onEdt(() -> {
            JPanel panel = components.transparent(new BorderLayout(0, 12));
            JTextArea explanation = DesktopComponentFactory.outputArea();
            explanation.setText(messages.text("db.replaceConfirmed", details)); explanation.setRows(7); explanation.setColumns(48);
            panel.add(explanation, BorderLayout.CENTER);
            JPanel checks = components.transparent(new GridLayout(0, 1, 0, 8));
            ToggleSwitch backup = new ToggleSwitch(messages.text("db.replace.backup"), false);
            ToggleSwitch downtime = new ToggleSwitch(messages.text("db.replace.downtime"), false);
            ToggleSwitch replacement = new ToggleSwitch(messages.text("db.replace.software"), false);
            checks.add(backup); checks.add(downtime); checks.add(replacement); panel.add(checks, BorderLayout.SOUTH);
            while (JOptionPane.showConfirmDialog(owner, panel, messages.text("auto.waiting"), JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                if (backup.isSelected() && downtime.isSelected() && replacement.isSelected()) return true;
                JOptionPane.showMessageDialog(owner, messages.text("db.replace.checkAll"));
            }
            return false;
        });
    }

    /**
     * Requires a separate affirmative decision for a concrete risk. / 要求对具体风险作出独立的肯定决策。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param details details / 详情
     * @return true when requires a separate affirmative decision for a concrete risk, false otherwise / 要求对具体风险作出独立的肯定决策时为 true，否则为 false
     */
    @Override public boolean confirm(String key, Map<String, ?> details) {
        if (key.equals("environment.confirm")) {
            return gold.debug.windowstolinux.app.ui.component.SystemPreparationDialog.confirmEnvironment(owner, messages, details);
        }
        if (key.equals("environment.system.confirm")) {
            return gold.debug.windowstolinux.app.ui.component.SystemPreparationDialog.confirm(owner, messages, details);
        }
        Map<String,?> arguments = key.equals("db.conflict") ? Map.of("detail", ((List<?>)details.get("conflicts")).stream()
                .map(value -> value.toString().split("\\|",2)).map(parts -> messages.text("db.conflict."+parts[0],
                        Map.of("instance",parts.length > 1 ? parts[1] : ""))).collect(java.util.stream.Collectors.joining("\n"))) : details;
        return onEdt(() -> JOptionPane.showConfirmDialog(owner, messages.text(key, arguments), messages.text("auto.waiting"),
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
    }

    /**
     * Uses a password field and returns only a caller-owned character array. / 使用密码控件，仅返回由调用方持有的字符数组。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return encoded or copied content buffer / 编码或复制得到的内容缓冲区
     */
    @Override public char[] requestSecret(String key) {
        return onEdt(() -> {
            JPasswordField input = new JPasswordField(24);
            try {
                if (JOptionPane.showConfirmDialog(owner, input, messages.text(key), JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) throw new java.util.concurrent.CancellationException();
                return input.getPassword();
            } finally { input.setText(""); }
        });
    }

    /**
     * Schedules Swing state changes on the event dispatch thread.
     * <p>在 Swing 事件分派线程调度状态变更。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param operation operation / 操作
     * @return constructed or resolved T / 构造或解析得到的T
     * @throws IllegalStateException if the required state or runtime facility is unavailable / 所需状态或运行设施不可用时
     */
    private static <T> T onEdt(Callable<T> operation) {
        try {
            if (SwingUtilities.isEventDispatchThread()) return operation.call();
            FutureTask<T> task = new FutureTask<>(operation);
            SwingUtilities.invokeAndWait(task); return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("input dialog interrupted");
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("input dialog failed", cause);
        }
    }
    /**
     * Offers recovery without replacing the original operation. / 提供救援而不替换原操作。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @return true when offers recovery without replacing the original operation, false otherwise / 提供救援而不替换原操作时为 true，否则为 false
     */
    @Override public boolean offerSshRecovery(gold.debug.windowstolinux.app.service.server.ServerProfile server) {
        return onEdt(() -> JOptionPane.showConfirmDialog(owner, messages.text("recovery.offer"),
                messages.text("recovery.title"), JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION);
    }
    /**
     * Opens modeless controls while the service retains its operation worker. / 服务保留操作线程时打开非模态控件。
     *
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     * @param session session used for the current scoped operation / 当前限定作用域操作使用的会话
     */
    @Override public void showSshRecovery(gold.debug.windowstolinux.app.service.server.ServerProfile server,
            gold.debug.windowstolinux.app.service.contract.SshRecoverySession session) {
        onEdt(() -> new gold.debug.windowstolinux.app.ui.recovery.SshRecoveryDialog(owner, server, session, service, components, messages));
    }
}
