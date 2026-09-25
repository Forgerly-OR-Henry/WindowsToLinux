package gold.debug.windowstolinux.api.ui;

import javax.swing.JComponent;
import java.util.Locale;
import gold.debug.windowstolinux.api.task.ApiException;

public interface ModPage extends AutoCloseable {
    JComponent create(UiEnvironment environment) throws Exception;
    void environmentChanged(UiEnvironment environment);
    @Override void close() throws Exception;
}
