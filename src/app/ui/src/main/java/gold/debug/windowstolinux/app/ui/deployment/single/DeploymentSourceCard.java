package gold.debug.windowstolinux.app.ui.deployment.single;

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

/** Folder and Git input with asynchronous detection and native drag/paste support. / 支持后台识别及原生拖入粘贴的文件夹和 Git 输入。 */
public final class DeploymentSourceCard extends JPanel {
    private final JTextField input = new JTextField();
    private final JLabel status = new JLabel();
    private final JPanel entry = new JPanel(new CardLayout());
    private final JButton browse;
    private final JButton clear;
    private final PageMessagePresenter messages;
    private final Consumer<DeploymentSourceInput> selected;
    private final AutomaticDeploymentApplicationFacade service;
    private final Timer debounce;
    private long revision;
    private boolean restoring;
    private String selectedValue = "";

    /** Creates the source interaction inside a rounded card. / 在圆角卡片内创建源码交互。 */
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
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
        });
        input.addActionListener(event -> { debounce.stop(); identify(); });
        TransferHandler transfer = new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return selectedValue.isEmpty() && input.isEnabled() && (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                        || support.isDataFlavorSupported(DataFlavor.stringFlavor));
            }
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

    /** Restores accepted or pending input without notifying advanced Git fields. / 恢复已接受或待确认输入，不触发高级 Git 字段变更。 */
    public void restore(String value, boolean accepted) {
        revision++; debounce.stop();
        selectedValue = accepted ? value : "";
        restoring = true; input.setText(accepted ? "" : value); restoring = false;
        status.setText(""); refresh();
    }

    /** Captures raw input including a value awaiting validation. / 捕获包括尚待验证值的原始输入。 */
    public String inputText() { return selectedValue.isEmpty() ? input.getText() : selectedValue; }

    private void refresh() {
        boolean occupied = !selectedValue.isEmpty();
        ((CardLayout) entry.getLayout()).show(entry, occupied ? "selected" : "input"); input.setEnabled(!occupied);
        browse.setEnabled(!occupied); browse.setText(occupied ? selectedValue : messages.text("source.drop"));
        browse.setToolTipText(occupied ? selectedValue : null);
        clear.setEnabled(occupied || !input.getText().isEmpty());
        revalidate(); repaint();
    }

    private void changed() {
        if (restoring) return;
        revision++; debounce.stop(); selected.accept(new DeploymentSourceInput(Optional.empty(), "", 0, ""));
        status.setText(input.getText().isBlank() ? "" : messages.text("source.detecting"));
        browse.setText(input.getText().isBlank() ? messages.text("source.drop") : status.getText());
        browse.setToolTipText(null);
        clear.setEnabled(!input.getText().isEmpty());
        if (!input.getText().isBlank()) debounce.start();
    }

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

    private void invalid() {
        browse.setText(messages.text("source.invalid")); browse.setToolTipText(messages.text("source.invalid"));
    }

    private void choose() {
        if (!selectedValue.isEmpty()) return;
        JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) input.setText(chooser.getSelectedFile().toString());
    }
}
