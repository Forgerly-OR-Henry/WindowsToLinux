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
    private final JButton browse;
    private final PageMessagePresenter messages;
    private final Consumer<DeploymentSourceInput> selected;
    private final AutomaticDeploymentApplicationFacade service;
    private final Timer debounce;
    private long revision;
    private boolean restoring;

    /** Creates the source interaction inside a rounded card. / 在圆角卡片内创建源码交互。 */
    public DeploymentSourceCard(AutomaticDeploymentApplicationFacade service, DesktopComponentFactory c, PageMessagePresenter messages, Consumer<DeploymentSourceInput> selected) {
        super(new BorderLayout(0, 8)); setOpaque(false); this.messages = messages; this.selected = selected;
        this.service = service;
        browse = c.secondaryButton(messages.text("source.drop"));
        browse.putClientProperty("JButton.buttonType", null); browse.putClientProperty("FlatLaf.style", "arc: 16");
        browse.setIcon(DesktopIcons.icon("folder-open", 30, browse::getForeground));
        browse.setPreferredSize(new Dimension(0, 72)); browse.addActionListener(event -> choose()); add(browse);
        input.putClientProperty("JTextField.placeholderText", messages.text("source.paste"));
        input.getAccessibleContext().setAccessibleName(messages.text("source.paste"));
        JPanel bottom = c.transparent(new BorderLayout(8, 6)); bottom.add(input);
        JButton clear = c.secondaryButton(messages.text("source.clear")); clear.addActionListener(event -> input.setText(""));
        bottom.add(clear, BorderLayout.EAST); bottom.add(status, BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        debounce = new Timer(300, event -> identify()); debounce.setRepeats(false);
        input.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { changed(); }
            public void removeUpdate(DocumentEvent event) { changed(); }
            public void changedUpdate(DocumentEvent event) { changed(); }
        });
        input.addActionListener(event -> { debounce.stop(); identify(); });
        TransferHandler transfer = new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return input.isEnabled() && (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
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
                } catch (Exception failure) { status.setText(messages.text("source.invalid")); return false; }
            }
        };
        setTransferHandler(transfer); input.setTransferHandler(transfer); browse.setTransferHandler(transfer);
    }

    /** Restores an accepted source without erasing advanced Git references. / 恢复已接受的源码，不清除高级 Git 引用。 */
    public void restore(String value) {
        revision++; debounce.stop();
        restoring = true; input.setText(value); restoring = false;
        status.setText(""); browse.setText(messages.text("source.drop"));
    }

    /** Captures raw input including a value awaiting validation. / 捕获包括尚待验证值的原始输入。 */
    public String inputText() { return input.getText(); }

    private void changed() {
        if (restoring) return;
        revision++; debounce.stop(); selected.accept(new DeploymentSourceInput(Optional.empty(), "", 0, ""));
        status.setText(input.getText().isBlank() ? "" : messages.text("source.detecting"));
        if (!input.getText().isBlank()) debounce.start();
        else browse.setText(messages.text("source.drop"));
    }

    private void identify() {
        String text = input.getText(); long expected = revision;
        if (text.isBlank() || service == null) return;
        DesktopTaskExecutor.run(() -> service.identifyDeploymentSource(text), value -> {
            if (revision != expected) return;
            selected.accept(value);
            browse.setText(value.directory().map(path -> path.getFileName() == null ? path.toString() : path.getFileName().toString())
                    .orElse(messages.text("auto.git")));
            browse.setToolTipText(text); status.setText(messages.text(value.directory().isPresent() ? "source.folderReady" : "source.gitReady"));
        }, failure -> { if (revision == expected) status.setText(messages.text("source.invalid")); });
    }

    private void choose() {
        JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) input.setText(chooser.getSelectedFile().toString());
    }
}
