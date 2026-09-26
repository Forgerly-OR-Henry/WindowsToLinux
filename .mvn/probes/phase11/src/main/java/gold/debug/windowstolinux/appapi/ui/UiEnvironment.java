package gold.debug.windowstolinux.appapi.ui;

import javax.swing.JComponent;
import java.util.Locale;
import gold.debug.windowstolinux.appapi.task.ApiException;

public record UiEnvironment(Locale locale, String themeId, boolean locked) {}
