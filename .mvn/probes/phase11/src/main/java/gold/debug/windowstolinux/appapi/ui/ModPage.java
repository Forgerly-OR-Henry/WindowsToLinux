package gold.debug.windowstolinux.appapi.ui;

import javax.swing.JComponent;
import java.util.Locale;
import gold.debug.windowstolinux.appapi.task.ApiException;

public interface ModPage extends AutoCloseable {
    JComponent create(UiEnvironment environment) throws Exception;
    void environmentChanged(UiEnvironment environment);
    @Override void close() throws Exception;
}
