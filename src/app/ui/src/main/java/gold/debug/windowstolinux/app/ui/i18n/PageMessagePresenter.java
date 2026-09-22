package gold.debug.windowstolinux.app.ui.i18n;

import java.awt.Component;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;

import gold.debug.windowstolinux.app.ui.diagnostic.DesktopFailurePresenter;
import gold.debug.windowstolinux.app.ui.diagnostic.FailureReportStore;
import gold.debug.windowstolinux.shared.model.lifecycle.LifecycleObservation;

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

    /**
     * Formats the supplied failure using shared safe presentation and records only bounded diagnostic metadata.
     * <p>使用共享安全展示格式化所提供失败，并仅记录有界诊断元数据。
     *
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     * @return the supplied failure using shared safe presentation and records only bounded diagnostic metadata / 使用共享安全展示格式化所提供失败，并仅记录有界诊断元数据
     */
    public String safe(gold.debug.windowstolinux.shared.model.failure.FailureDescriptor failure) {
        return failures.present(failure);
    }

    /**
     * Localizes display choices while preserving the submitted protocol value. / 本地化展示选项，同时保留所提交的协议值。
     *
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @return input choice text / 输入选项文本
     */
    public String inputChoice(gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField field,
            String value) {
        if (value.isBlank())
            return field.labelKey().equals("db.field.initialize") ? text("db.initialize.none") : value;
        return switch (field.labelKey()) {
            case "auto.field.type" -> text("project.type." + value.toLowerCase(Locale.ROOT));
            case "db.field.engine" -> text("db.engine." + value.toLowerCase(Locale.ROOT));
            case "field.healthMode" -> text("health.mode." + value.toLowerCase(Locale.ROOT));
            case "field.containerEngine" -> text("container.engine." + value.toLowerCase(Locale.ROOT));
            case "field.databaseReviewMode" -> text("database.review.mode." + value.toLowerCase(Locale.ROOT));
            default -> value;
        };
    }

    /**
     * Formats a localized message with optional raw diagnostic text. / 格式化本地化消息与可选原始诊断文本。
     *
     * @param message localized explanation / 本地化说明
     * @param diagnostic bounded non-secret detail for diagnostic reporting / 用于诊断报告的有界非秘密详情
     * @return a localized message with optional raw diagnostic text / 本地化消息与可选原始诊断文本
     */
    public String localized(gold.debug.windowstolinux.shared.model.message.LocalizedMessage message,
            String diagnostic) {
        String headline = messages.text(message);
        return diagnostic == null || diagnostic.isBlank() ? headline : headline + "\n" + diagnostic;
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
     * Formats a lifecycle observation. / 格式化生命周期观测。
     *
     * @param observation observation / 观测
     * @return a lifecycle observation / 生命周期观测
     */
    public String lifecycle(LifecycleObservation observation) {
        if (observation == null) {
            return text("lifecycle.noObservation");
        }
        return text("lifecycle.observation", Map.of("runtime",
                text("runtime.state." + observation.runtimeState().name().toLowerCase(Locale.ROOT)), "autostart",
                text("autostart.state." + observation.autostartState().name().toLowerCase(Locale.ROOT)), "ownership",
                text(observation.ownershipVerified() ? "ownership.verified" : "ownership.unverified"), "evidence",
                observation.evidence()));
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
