package gold.debug.windowstolinux.api.ui;

import javax.swing.JComponent;
import java.util.Locale;
import gold.debug.windowstolinux.api.task.ApiException;

public record PageSpec(String id, String titleKey, String defaultTitle, boolean settingsPage) {}
