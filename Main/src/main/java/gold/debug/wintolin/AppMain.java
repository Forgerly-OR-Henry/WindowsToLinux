package gold.debug.wintolin;

import com.formdev.flatlaf.FlatDarkLaf;
import gold.debug.wintolin.appui.Router;
import gold.debug.wintolin.appui.test.DeployTestFrame;

import javax.swing.*;

public class AppMain {
    public static void main(String[] args) {
        FlatDarkLaf.setup();

        // 测试用
        SwingUtilities.invokeLater(() -> {
            DeployTestFrame frame = new DeployTestFrame();
            frame.setVisible(true);
        });

        /*SwingUtilities.invokeLater(() -> {
            Router router = new Router("Demo App");
            router.start();
        });*/
    }
}

