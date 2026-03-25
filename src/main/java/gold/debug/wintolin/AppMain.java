package gold.debug.wintolin;

import com.formdev.flatlaf.FlatDarkLaf;
import gold.debug.wintolin.appui.Router;

import javax.swing.*;

public class AppMain {
    public static void main(String[] args) {
        FlatDarkLaf.setup();

        SwingUtilities.invokeLater(() -> {
            Router router = new Router("Demo App");
            router.start();
        });
    }
}

