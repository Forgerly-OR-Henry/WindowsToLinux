package gold.debug.windowstolinux.app.ui.deployment.automatic;

import gold.debug.windowstolinux.app.service.contract.definition.DeploymentSourceInput;
import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Folder and Git input with asynchronous detection and native drag/paste support. / 支持后台识别及原生拖入粘贴的文件夹和 Git 输入。
 */
public final class DeploymentSourceCard extends JPanel {
    /**
     * Swing control for input.
     * <p>输入对应的 Swing 控件。
     */
    private final JTextField input = new JTextField();
    /**
     * Swing control for status.
     * <p>状态对应的 Swing 控件。
     */
    private final JLabel status = new JLabel();
    /**
     * Swing control for entry.
     * <p>条目对应的 Swing 控件。
     */
    private final JPanel entry = new JPanel(new CardLayout());
    /**
     * Swing control for browse.
     * <p>浏览对应的 Swing 控件。
     */
    private final JButton browse;
    /**
     * Swing control for clear.
     * <p>清空对应的 Swing 控件。
     */
    private final JButton clear;
    /**
     * Bound page message presenter collaborator for localized message resolver.
     * <p>处理本地化消息解析器的页面消息展示器协作对象。
     */
    private final PageMessagePresenter messages;
    /**
     * Explicitly selected item or state.
     * <p>显式选择的项目或状态。
     */
    private final Consumer<DeploymentSourceInput> selected;
    /**
     * Bound automatic deployment application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的自动部署应用门面协作对象。
     */
    private final AutomaticDeploymentApplicationFacade service;
    /**
     * Swing event-thread timer for debounce.
     * <p>防抖使用的 Swing 事件线程定时器。
     */
    private final Timer debounce;
    /**
     * Immutable configuration or secret revision number.
     * <p>不可变配置或秘密修订号。
     */
    private long revision;
    /**
     * Restoring.
     * <p>恢复中。
     */
    private boolean restoring;
    /**
     * Selected value.
     * <p>已选内容。
     */
    private String selectedValue = "";

    /**
     * Creates the source interaction inside a rounded card. / 在圆角卡片内创建源码交互。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param c the themed desktop component factory / 主题化桌面组件工厂
     * @param messages localized message resolver / 本地化消息解析器
     * @param selected explicitly selected item or state / 显式选择的项目或状态
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     */
    public DeploymentSourceCard(AutomaticDeploymentApplicationFacade service, DesktopComponentFactory c, PageMessagePresenter messages, Consumer<DeploymentSourceInput> selected) {
        super(new BorderLayout(0, 8)); setOpaque(false); this.messages = messages; this.selected = selected;
        this.service = service;
        browse = c.secondaryButton(messages.text("source.drop"));
        browse.setIcon(DesktopIcons.icon("folder-open", 30, browse::getForeground));
        browse.setDisabledIcon(browse.getIcon());
        browse.setHorizontalTextPosition(SwingConstants.CENTER); browse.setVerticalTextPosition(SwingConstants.BOTTOM);
        browse.setIconTextGap(10);
        browse.setPreferredSize(new Dimension(0, com.formdev.flatlaf.util.UIScale.scale(88)));
        browse.addActionListener(event -> choose()); add(browse);
        input.putClientProperty("JTextField.placeholderText", messages.text("source.paste"));
        input.getAccessibleContext().setAccessibleName(messages.text("source.paste"));
        status.setPreferredSize(new Dimension(0, status.getFontMetrics(status.getFont()).getHeight()));
        entry.setOpaque(false); entry.add(input, "input"); entry.add(status, "selected");
        JPanel bottom = c.transparent(new BorderLayout(8, 0)); bottom.add(entry);
        clear = c.secondaryButton(messages.text("source.clear")); clear.setEnabled(false);
        clear.addActionListener(event -> {
            restore("", false); selected.accept(new DeploymentSourceInput(Optional.empty(), "", 0, ""));
        });
        bottom.add(clear, BorderLayout.EAST); add(bottom, BorderLayout.SOUTH);
        debounce = new Timer(300, event -> identify()); debounce.setRepeats(false);
        input.getDocument().addDocumentListener(new DocumentListener() {
            /**
             * Inserts update.
             * <p>插入更新。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            public void insertUpdate(DocumentEvent event) { changed(); }
            /**
             * Removes update.
             * <p>移除更新。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            public void removeUpdate(DocumentEvent event) { changed(); }
            /**
             * Responds to a document change by updating the associated form state.
             * <p>响应文档变更并更新关联表单状态。
             *
             * @param event state or UI event being processed / 正在处理的状态或 UI 事件
             */
            public void changedUpdate(DocumentEvent event) { changed(); }
        });
        input.addActionListener(event -> { debounce.stop(); identify(); });
        TransferHandler transfer = new TransferHandler() {
            /**
             * Reports whether the import condition holds for this contract.
             * <p>判断当前契约是否满足导入条件。
             *
             * @param support exact support level and validation scope / 精确支持等级与验证范围
             * @return true when import condition holds for this contract, false otherwise / 当前契约是否满足导入条件时为 true，否则为 false
             */
            @Override public boolean canImport(TransferSupport support) {
                return selectedValue.isEmpty() && input.isEnabled() && (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || support.isDataFlavorSupported(DataFlavor.stringFlavor));
            }
            /**
             * Tests the import data predicate against the supplied evidence.
             * <p>根据所提供证据检查导入数据条件。
             *
             * @param support exact support level and validation scope / 精确支持等级与验证范围
             * @return true when import data predicate against the supplied evidence, false otherwise / 根据所提供证据检查导入数据条件时为 true，否则为 false
             * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
             */
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        List<?> files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                        if (files.size() != 1 || !(files.getFirst() instanceof File file)) throw new IllegalArgumentException("one folder required");
                        input.setText(file.toString());
                    } else input.setText((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor));
                    return true;
                } catch (Exception failure) { invalid(); return false; }
            }
        };
        setTransferHandler(transfer); input.setTransferHandler(transfer); browse.setTransferHandler(transfer);
    }

    /**
     * Restores accepted or pending input without notifying advanced Git fields. / 恢复已接受或待确认输入，不触发高级 Git 字段变更。
     *
     * @param value candidate content accepted or rejected by this contract / 由当前契约接收或拒绝的候选内容
     * @param accepted accepted / 已接受
     */
    public void restore(String value, boolean accepted) {
        revision++; debounce.stop();
        selectedValue = accepted ? value : "";
        restoring = true; input.setText(accepted ? "" : value); restoring = false;
        status.setText(""); refresh();
    }

    /**
     * Captures raw input including a value awaiting validation. / 捕获包括尚待验证值的原始输入。
     *
     * @return input text text / 输入文本文本
     */
    public String inputText() { return selectedValue.isEmpty() ? input.getText() : selectedValue; }

    /**
     * Refreshes deployment source card.
     * <p>刷新部署源码卡片。
     */
    private void refresh() {
        boolean occupied = !selectedValue.isEmpty();
        ((CardLayout) entry.getLayout()).show(entry, occupied ? "selected" : "input"); input.setEnabled(!occupied);
        browse.setEnabled(!occupied); browse.setText(occupied ? selectedValue : messages.text("source.drop"));
        browse.setToolTipText(occupied ? selectedValue : null);
        clear.setEnabled(occupied || !input.getText().isEmpty());
        revalidate(); repaint();
    }

    /**
     * Invalidates the prior source selection, advances the revision and schedules source identification.
     * <p>使此前源码选择失效、递增修订，并安排源码识别。
     */
    private void changed() {
        if (restoring) return;
        revision++; debounce.stop(); selected.accept(new DeploymentSourceInput(Optional.empty(), "", 0, ""));
        status.setText(input.getText().isBlank() ? "" : messages.text("source.detecting"));
        browse.setText(input.getText().isBlank() ? messages.text("source.drop") : status.getText());
        browse.setToolTipText(null);
        clear.setEnabled(!input.getText().isEmpty());
        if (!input.getText().isBlank()) debounce.start();
    }

    /**
     * Identifies the current source asynchronously and ignores responses for obsolete input revisions.
     * <p>异步识别当前源码，并忽略过期输入修订的响应。
     */
    private void identify() {
        String text = input.getText(); long expected = revision;
        if (!selectedValue.isEmpty() || text.isBlank() || service == null) return;
        DesktopTaskExecutor.run(() -> service.identifyDeploymentSource(text), value -> {
            if (revision != expected) return;
            selectedValue = value.directory().map(Object::toString).orElse(value.gitAddress());
            restoring = true; input.setText(""); restoring = false;
            refresh(); status.setText(messages.text(value.directory().isPresent() ? "source.folderReady" : "source.gitReady"));
            selected.accept(value);
        }, failure -> { if (revision == expected) invalid(); });
    }

    /**
     * Creates the owning module's failure for rejected input or evidence.
     * <p>为被拒绝输入或证据创建所属模块的失败。
     */
    private void invalid() {
        browse.setText(messages.text("source.invalid")); browse.setToolTipText(messages.text("source.invalid"));
    }

    /**
     * Chooses deployment source card.
     * <p>选择部署源码卡片。
     */
    private void choose() {
        if (!selectedValue.isEmpty()) return;
        JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) input.setText(chooser.getSelectedFile().toString());
    }
}
