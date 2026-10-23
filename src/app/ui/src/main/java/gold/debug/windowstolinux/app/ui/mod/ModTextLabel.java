package gold.debug.windowstolinux.app.ui.mod;

import java.awt.Font;
import javax.swing.JLabel;
import javax.swing.UIManager;

import com.formdev.flatlaf.util.UIScale;

/** Plain text with a logical font size that survives Swing UI reinstallation. / 重装 Swing UI 后保留逻辑字号的纯文本。 */
final class ModTextLabel extends JLabel {
    private final int size;

    private final boolean bold, muted;

    ModTextLabel(String text, int size, boolean bold, boolean muted) {
        super(text);
        this.size = size;
        this.bold = bold;
        this.muted = muted;
        putClientProperty("html.disable", true);
        updateUI();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (size == 0) return;
        Font base = UIManager.getFont("Label.font");
        if (base != null) setFont(base.deriveFont(bold ? Font.BOLD : Font.PLAIN, UIScale.scale((float) size)));
        setForeground(UIManager.getColor(muted ? "Label.disabledForeground" : "Label.foreground"));
    }
}
