package gold.debug.windowstolinux.api.ui;

import javax.swing.JComponent;
import java.util.Locale;
import gold.debug.windowstolinux.api.task.ApiException;

public interface UiService {
    PageRegistration register(PageSpec spec, PageFactory factory) throws ApiException;
    void open(String localPageId) throws ApiException;
    UiEnvironment environment();
}
