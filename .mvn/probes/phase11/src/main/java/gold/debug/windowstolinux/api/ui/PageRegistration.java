package gold.debug.windowstolinux.api.ui;

import javax.swing.JComponent;
import java.util.Locale;
import gold.debug.windowstolinux.api.task.ApiException;

public interface PageRegistration extends AutoCloseable {
    String id();
    @Override void close() throws ApiException;
}
