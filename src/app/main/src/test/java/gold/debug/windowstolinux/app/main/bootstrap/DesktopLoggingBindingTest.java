package gold.debug.windowstolinux.app.main.bootstrap;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopLoggingBindingTest {
    @Test
    void usesTheExplicitNoOperationSlf4jBinding() {
        assertEquals("org.slf4j.helpers.NOPLoggerFactory", LoggerFactory.getILoggerFactory().getClass().getName());
    }
}
