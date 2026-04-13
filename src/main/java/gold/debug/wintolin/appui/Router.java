package gold.debug.wintolin.appui;

import gold.debug.wintolin.appui.home.Home;
import gold.debug.wintolin.appui.welcome.UserNotes;
import gold.debug.wintolin.appui.welcome.Welcome;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

//
public class Router {

    private final JFrame frame;
    private final JPanel root;
    private final CardLayout cards;

    // 路由表：routeKey -> 页面组件
    private final Map<String, JComponent> routes = new LinkedHashMap<>();

    // 当前路由（调试用）
    private String currentRoute;

    public Router(String title) {
        this.frame = new JFrame(title);
        this.cards = new CardLayout();
        this.root = new JPanel(cards);

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 600));
        frame.setLocationRelativeTo(null);

        // 注册页面（你以后新增页面，只需要加一行 register）
        register("welcome", new Welcome(this::openUserNotes));
        register("home", new Home());

        frame.setContentPane(root);
    }

    /* 注册路由 */
    public void register(String routeKey, JComponent page) {
        if (routeKey == null || routeKey.isBlank()) {
            throw new IllegalArgumentException("routeKey 不能为空");
        }
        if (routes.containsKey(routeKey)) {
            throw new IllegalStateException("重复注册路由：" + routeKey);
        }
        routes.put(routeKey, page);
        root.add(page, routeKey); // CardLayout 的 name 就是 routeKey
    }

    /* 跳转到指定路由 */
    public void navigate(String routeKey) {
        if (!routes.containsKey(routeKey)) {
            // 这里也可以改成 PopUp.error(...)，看你风格
            throw new IllegalArgumentException("未注册的路由：" + routeKey);
        }
        cards.show(root, routeKey);
        currentRoute = routeKey;
    }

    /* 启动应用 */
    public void start() {
        navigate("welcome");
        frame.setVisible(true);
    }

    /* ---------------- 业务流程 ---------------- */
    public void openUserNotes() {
        UserNotes dialog = new UserNotes(
                frame,
                () -> navigate("home"),   // 同意 -> home
                this::onDisagree          // 不同意 -> 提示并回 welcome
        );
        dialog.setVisible(true);
    }

    private void onDisagree() {
        PopUp.userNotesDisagree(frame);
        navigate("welcome");
    }

    public JFrame getFrame() {
        return frame;
    }

    public String getCurrentRoute() {
        return currentRoute;
    }
}