package gold.debug.windowstolinux.app.ui.diagnostic;

import java.awt.Component;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;

import gold.debug.windowstolinux.app.ui.i18n.LocaleBinding;
import gold.debug.windowstolinux.app.ui.i18n.LocalizedMessage;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;

/**
 * Shared localization and diagnostic formatting for independent page controllers. / 独立页面控制器共享的本地化与诊断格式化。
 */
public final class PageMessagePresenter {
    /**
     * Localized message resolver.
     * <p>本地化消息解析器。
     */
    private final MessageCatalog messages;

    /**
     * Bound desktop failure presenter collaborator for failures.
     * <p>处理失败集合的Desktop失败展示器协作对象。
     */
    private final DesktopFailurePresenter failures;

    /**
     * Creates page message support. / 创建页面消息支持。
     *
     * @param messages localized message resolver / 本地化消息解析器
     */
    public PageMessagePresenter(MessageCatalog messages) {
        this(messages, FailureReportStore.disabled());
    }

    /**
     * Creates page message support backed by safe local diagnostics. / 创建由安全本地诊断支持的页面消息支持。
     *
     * @param messages localized message resolver / 本地化消息解析器
     * @param reports reports / 报告集合
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public PageMessagePresenter(MessageCatalog messages, FailureReportStore reports) {
        this.messages = Objects.requireNonNull(messages, "messages");
        this.failures = new DesktopFailurePresenter(messages::text, reports);
    }

    /**
     * Resolves one message key. / 解析一项消息键。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @return one message key / 一项消息键
     */
    public String text(String key) {
        return messages.text(key);
    }

    /**
     * Resolves one parameterized message key. / 解析一项带参数消息键。
     *
     * @param key lookup key within the current contract / 当前契约内的查找键
     * @param arguments literal arguments passed to the fixed command or message template / 传给固定命令或消息模板的字面参数
     * @return one parameterized message key / 一项带参数消息键
     */
    public String text(String key, Map<String, ?> arguments) {
        return messages.text(key, arguments);
    }

    /**
     * Returns the selected catalog. / 返回已选消息目录。
     *
     * @return the selected catalog / 已选消息目录
     */
    public MessageCatalog catalog() {
        return messages;
    }

    /** Binds display text to an explicit key without replacing the component. / 将展示文本绑定到明确的键，不替换组件。
     * @param <T> actual Swing component type / 实际 Swing 组件类型
     * @param component display component / 展示组件
     * @param key declared message key / 声明的消息键
     * @return original component / 原组件 */
    public <T extends javax.swing.JComponent> T bind(T component, String key) {
        return LocaleBinding.bind(component, LocalizedMessage.of(key), messages);
    }

    /**
     * Formats the supplied failure using shared safe presentation and records only bounded diagnostic metadata.
     * <p>使用共享安全展示格式化所提供失败，并仅记录有界诊断元数据。
     *
     * @param exception original exception being classified or translated / 正在分类或转换的原始异常
     * @return the supplied failure using shared safe presentation and records only bounded diagnostic metadata / 使用共享安全展示格式化所提供失败，并仅记录有界诊断元数据
     */
    public String safe(Exception exception) {
        return failures.present(exception);
    }

    /** Formats public safe evidence without invoking the legacy report store on every UI refresh. / 格式化公开安全证据，不在每次 UI 刷新时调用旧报告存储。
     * @param problem original platform diagnostic / 原平台诊断
     * @return safe message, event and optional diagnostic identity / 安全消息、事件及可选诊断身份 */
    public String safe(gold.debug.windowstolinux.appapi.task.Problem problem) {
        return failures.present(problem);
    }

    /**
     * Opens the diagnostic directory when supported. / 平台支持时打开诊断目录。
     *
     * @return true when opens the diagnostic directory when supported, false otherwise / 平台支持时打开诊断目录时为 true，否则为 false
     */
    public boolean openDiagnosticsDirectory() {
        return failures.openDiagnosticsDirectory();
    }

    /**
     * Returns the copyable diagnostic directory path. / 返回可复制的诊断目录路径。
     *
     * @return the copyable diagnostic directory path / 可复制的诊断目录路径
     */
    public String diagnosticsPath() {
        return failures.diagnosticsPath();
    }

    /**
     * Installs a localized enum renderer. / 安装本地化枚举渲染器。
     *
     * @param <T> type of the contract payload / 契约载荷的类型
     * @param comboBox combo box / 组合框
     * @param prefix prefix / 前缀
     */
    public <T extends Enum<T>> void localize(JComboBox<T> comboBox, String prefix) {
        comboBox.setRenderer(new DefaultListCellRenderer() {
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
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                Object label = value instanceof Enum<?> item
                        ? text(prefix + item.name().toLowerCase(Locale.ROOT))
                        : value;
                return super.getListCellRendererComponent(list, label, index, selected, focus);
            }
        });
    }
}
