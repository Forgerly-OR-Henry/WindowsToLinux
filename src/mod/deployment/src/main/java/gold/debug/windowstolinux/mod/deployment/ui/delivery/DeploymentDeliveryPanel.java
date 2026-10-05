package gold.debug.windowstolinux.mod.deployment.ui.delivery;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.util.Locale;
import java.util.ResourceBundle;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.ui.page.UiEnvironment;
import gold.debug.windowstolinux.mod.deployment.api.DeploymentApplicationUsage;

/** Reuses verified component-qualified delivery without decoding opaque runtimes. / 复用已核实且带组件身份的交付，不解码不透明运行数据。 */
public final class DeploymentDeliveryPanel extends JPanel {
    /** Actual desktop action boundary, replaceable by a controlled interaction fixture. / 实际桌面动作边界，可由受控交互夹具替换。 */
    public interface DeliveryActions {
        /** Copies exactly the selected verified public text. / 仅复制所选已核实公开文本。
         * @param value selected public command or endpoint / 所选公开命令或端点
         * @throws Exception if the actual clipboard action fails / 实际剪贴板动作失败时 */
        void copy(String value) throws Exception;

        /** Opens exactly one independently validated HTTP(S) target. / 仅打开一项独立校验的HTTP(S)目标。
         * @param target selected legal browser target / 所选合法浏览器目标
         * @throws Exception if the actual desktop action fails / 实际桌面动作失败时 */
        void open(URI target) throws Exception;
    }

    /** Actual desktop side effects. / 实际桌面副作用。 */
    private final DeliveryActions actions;

    /** Component-qualified endpoint choices. / 带组件身份的端点选项。 */
    private final JComboBox<EndpointChoice> endpoints = new JComboBox<>();

    /** Component-qualified fixed helper commands. / 带组件身份的固定helper命令。 */
    private final JComboBox<CommandChoice> commands = new JComboBox<>();

    /** Original application and declaration details. / 原应用及声明详情。 */
    private final JTextArea facts = new JTextArea(4, 36);

    /** Explicit endpoint copy action. / 明确端点复制动作。 */
    private final JButton copyEndpoint = new JButton();

    /** HTTP(S)-only open action. / 仅HTTP(S)的打开动作。 */
    private final JButton open = new JButton();

    /** Explicit fixed command copy action. / 明确固定命令复制动作。 */
    private final JButton copyCommand = new JButton();

    /** Bounded localized failure feedback. / 有界本地化失败反馈。 */
    private final JLabel status = new JLabel();

    /** Current original verified facts. / 当前原已核实事实。 */
    private DeploymentApplicationUsage usage;

    /** Current original locale. / 当前原语言。 */
    private ResourceBundle messages;

    /** Current page admission gate. / 当前页面准入门槛。 */
    private boolean enabled;

    /** Current host security lock. / 当前宿主安全锁。 */
    private boolean locked;

    /** Uses the real system clipboard and default desktop browser. / 使用实际系统剪贴板与默认桌面浏览器。 */
    public DeploymentDeliveryPanel() {
        this(new DeliveryActions() {
            /** Copies public text through the actual system clipboard. / 通过实际系统剪贴板复制公开文本。
             * @param value selected public text / 所选公开文本 */
            @Override
            public void copy(String value) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
            }

            /** Opens the validated target through the actual desktop. / 通过实际桌面打开已校验目标。
             * @param target validated HTTP(S) target / 已校验HTTP(S)目标
             * @throws Exception if the desktop action fails / 桌面动作失败时 */
            @Override
            public void open(URI target) throws Exception {
                Desktop.getDesktop().browse(target);
            }
        });
    }

    /** Binds only the concrete desktop action boundary. / 仅绑定具体桌面动作边界。
     * @param actions actual or controlled desktop actions / 实际或受控桌面动作 */
    public DeploymentDeliveryPanel(DeliveryActions actions) {
        super(new BorderLayout(6, 6));
        this.actions = java.util.Objects.requireNonNull(actions);
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.delivery.delivery", Locale.SIMPLIFIED_CHINESE);
        setName("delivery.panel");
        endpoints.setName("delivery.endpoints");
        commands.setName("delivery.commands");
        facts.setName("delivery.facts");
        copyEndpoint.setName("delivery.copyEndpoint");
        open.setName("delivery.open");
        copyCommand.setName("delivery.copyCommand");
        facts.setEditable(false);
        facts.setLineWrap(true);
        facts.setWrapStyleWord(true);
        var rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        var endpointRow = new JPanel();
        endpointRow.add(endpoints);
        endpointRow.add(copyEndpoint);
        endpointRow.add(open);
        rows.add(endpointRow);
        var commandRow = new JPanel();
        commandRow.add(commands);
        commandRow.add(copyCommand);
        rows.add(commandRow);
        add(new JScrollPane(facts), BorderLayout.CENTER);
        add(rows, BorderLayout.SOUTH);
        add(status, BorderLayout.NORTH);
        endpoints.addActionListener(ignored -> availability());
        copyEndpoint.addActionListener(ignored -> perform(false, true));
        open.addActionListener(ignored -> perform(true, true));
        copyCommand.addActionListener(ignored -> perform(false, false));
        localize();
    }

    /** Shows only the supplied exact verified publication's public usage. / 仅展示所提供精确已核实发布的公开用法。
     * @param value exact currently verified original usage / 精确当前已核实原用法 */
    public void show(DeploymentApplicationUsage value) {
        requireEdt();
        clear();
        if (locked)
            return;
        usage = java.util.Objects.requireNonNull(value);
        for (var component : value.components()) {
            for (var endpoint : component.endpoints())
                endpoints.addItem(new EndpointChoice(component.id(), endpoint));
            component.command().ifPresent(command -> commands.addItem(new CommandChoice(component.id(), command)));
        }
        render();
        availability();
    }

    /** Clears previous selection and every old actionable delivery value. / 清空先前选择及全部旧可操作交付值。 */
    public void clear() {
        requireEdt();
        usage = null;
        endpoints.removeAllItems();
        commands.removeAllItems();
        facts.setText("");
        status.setText("");
        availability();
    }

    /** Applies the host locale and clears actionable content on locking. / 应用宿主语言并在锁定时清空可操作内容。
     * @param environment actual current host environment / 实际当前宿主环境 */
    public void environment(UiEnvironment environment) {
        requireEdt();
        locked = environment.locked();
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.delivery.delivery", environment.locale());
        if (locked)
            clear();
        localize();
        render();
        availability();
    }

    /** Gates concrete desktop actions with the owning page's state. / 以所属页面状态控制具体桌面动作。
     * @param value whether the original page allows actions / 原页面是否允许动作 */
    public void enabled(boolean value) {
        requireEdt();
        enabled = value;
        availability();
    }

    /** Performs only the currently selected legal action under the actual gate. / 在实际门槛下仅执行当前所选合法动作。
     * @param browser whether to open HTTP(S) / 是否打开HTTP(S)
     * @param endpoint whether the selected value is an endpoint / 所选值是否为端点 */
    private void perform(boolean browser, boolean endpoint) {
        if (!enabled || locked || usage == null)
            return;
        try {
            if (endpoint) {
                var selected = (EndpointChoice) endpoints.getSelectedItem();
                if (selected == null)
                    return;
                if (browser) {
                    if (selected.endpoint().browser().isEmpty())
                        return;
                    actions.open(selected.endpoint().browser().orElseThrow());
                } else
                    actions.copy(selected.endpoint().value());
            } else {
                var selected = (CommandChoice) commands.getSelectedItem();
                if (selected == null)
                    return;
                actions.copy(selected.command());
            }
            status.setText(messages.getString(browser ? "opened" : "copied"));
        } catch (Exception failure) {
            status.setText(messages.getString("failed"));
        }
    }

    /** Renders mode, availability and automatic startup without source arguments. / 不含源码参数地呈现模式、可用性与自动启动。 */
    private void render() {
        if (usage == null)
            return;
        var value = new StringBuilder(usage.application().id()).append(" · ").append(usage.application().server().id())
            .append('\n').append(usage.revisionSha256());
        for (var component : usage.components()) {
            value.append('\n').append(component.id()).append(" · ").append(messages.getString(component.mode().name()))
                .append(" · ").append(messages.getString("state." + component.state()))
                .append(" · ").append(messages.getString("autostart." + component.autostart()));
            if (!component.workers().isEmpty())
                value.append(" · ").append(messages.getString("workers")).append(String.join(", ", component.workers()));
        }
        facts.setText(value.toString());
    }

    /** Updates all actionable selectors from exact original facts. / 按精确原事实更新全部可操作选择器。 */
    private void availability() {
        boolean allowed = enabled && !locked && usage != null;
        endpoints.setEnabled(allowed && endpoints.getItemCount() != 0);
        commands.setEnabled(allowed && commands.getItemCount() != 0);
        copyEndpoint.setEnabled(allowed && endpoints.getSelectedItem() != null);
        copyCommand.setEnabled(allowed && commands.getSelectedItem() != null);
        var selected = (EndpointChoice) endpoints.getSelectedItem();
        open.setEnabled(allowed && selected != null && selected.endpoint().browser().isPresent());
    }

    /** Uses current localized names on real action controls. / 在实际动作控件使用当前本地化名称。 */
    private void localize() {
        copyEndpoint.setText(messages.getString("copyEndpoint"));
        copyCommand.setText(messages.getString("copyCommand"));
        open.setText(messages.getString("open"));
        endpoints.getAccessibleContext().setAccessibleName(messages.getString("endpoints"));
        commands.getAccessibleContext().setAccessibleName(messages.getString("commands"));
        facts.getAccessibleContext().setAccessibleName(messages.getString("facts"));
    }

    /** Rejects mutations outside the owning Swing thread. / 拒绝所属Swing线程之外的修改。 */
    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Delivery mutations require EDT");
    }

    /** Endpoint choice keeps its original component identity. / 端点选项保留原组件身份。
     * @param component original component / 原组件
     * @param endpoint exact public endpoint / 精确公开端点 */
    private record EndpointChoice(String component, DeploymentApplicationUsage.Endpoint endpoint) {
        /** Returns visible public endpoint text. / 返回可见公开端点文本。
         * @return component-qualified endpoint / 带组件身份的端点 */
        @Override
        public String toString() {
            return component + " · " + endpoint.id() + " · " + endpoint.value();
        }
    }

    /** Command choice keeps its original component identity. / 命令选项保留原组件身份。
     * @param component original component / 原组件
     * @param command fixed helper command / 固定helper命令 */
    private record CommandChoice(String component, String command) {
        /** Shows a component identity without duplicating command text. / 显示组件身份而不重复命令文本。
         * @return original component / 原组件 */
        @Override
        public String toString() {
            return component;
        }
    }
}
