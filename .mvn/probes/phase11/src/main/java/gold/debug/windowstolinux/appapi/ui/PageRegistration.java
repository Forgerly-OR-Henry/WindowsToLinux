package gold.debug.windowstolinux.appapi.ui;

import javax.swing.JComponent;
import java.util.Locale;
import gold.debug.windowstolinux.appapi.task.ApiException;

public interface PageRegistration extends AutoCloseable {
    String id();
    @Override void close() throws ApiException;
}
