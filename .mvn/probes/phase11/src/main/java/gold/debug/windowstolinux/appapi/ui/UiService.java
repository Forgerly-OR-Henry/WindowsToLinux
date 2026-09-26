package gold.debug.windowstolinux.appapi.ui;

import javax.swing.JComponent;
import java.util.Locale;
import gold.debug.windowstolinux.appapi.task.ApiException;

public interface UiService {
    PageRegistration register(PageSpec spec, PageFactory factory) throws ApiException;
    void open(String localPageId) throws ApiException;
    UiEnvironment environment();
}
