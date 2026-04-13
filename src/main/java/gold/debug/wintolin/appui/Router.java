package gold.debug.wintolin.appui;

import gold.debug.wintolin.appui.home.Home;
import gold.debug.wintolin.appui.welcome.UserNotes;
import gold.debug.wintolin.appui.welcome.Welcome;
import gold.debug.wintolin.exceptionanderror.MethodUtils;
import gold.debug.wintolin.exceptionanderror.MyException;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** 应用路由器 */
public class Router {

    /* ---------------- 路由常量 ---------------- */

    /** 欢迎页路由键 */
    public static final String ROUTE_WELCOME = "welcome";

    /** 主页路由键 */
    public static final String ROUTE_HOME = "home";

    /* ---------------- 错误码常量 ---------------- */

    private static final String ERROR_ROUTE_KEY_EMPTY = "ERROR_ROUTE_KEY_EMPTY";
    private static final String ERROR_ROUTE_DUPLICATE = "ERROR_ROUTE_DUPLICATE";
    private static final String ERROR_ROUTE_NOT_FOUND = "ERROR_ROUTE_NOT_FOUND";
    private static final String ERROR_PAGE_NULL = "ERROR_PAGE_NULL";
    private static final String ERROR_DIALOG_CREATE = "ERROR_DIALOG_CREATE";

    /* ---------------- Method 常量：供 MyException 使用 ---------------- */

    private static final Method METHOD_INIT_FRAME =
            MethodUtils.getCurrentMethod(Router.class, "initFrame");

    private static final Method METHOD_INIT_ROUTES =
            MethodUtils.getCurrentMethod(Router.class, "initRoutes");

    private static final Method METHOD_REGISTER =
            MethodUtils.getCurrentMethod(Router.class, "register", String.class, JComponent.class);

    private static final Method METHOD_NAVIGATE =
            MethodUtils.getCurrentMethod(Router.class, "navigate", String.class);

    private static final Method METHOD_START =
            MethodUtils.getCurrentMethod(Router.class, "start");

    private static final Method METHOD_OPEN_USER_NOTES =
            MethodUtils.getCurrentMethod(Router.class, "openUserNotes");

    private static final Method METHOD_ON_DISAGREE =
            MethodUtils.getCurrentMethod(Router.class, "onDisagree");

    /* ---------------- 窗口与路由核心字段 ---------------- */

    /** 应用主窗口 */
    private final JFrame frame;

    /** 页面根容器，内部承载所有页面 */
    private final JPanel root;

    /** 卡片布局管理器，用于在多个页面之间切换显示 */
    private final CardLayout cards;

    /** 路由表：routeKey -> 页面组件 */
    private final Map<String, JComponent> routes = new LinkedHashMap<>();

    /** 当前路由，仅用于记录当前页状态，便于调试或后续扩展 */
    private String currentRoute;

    /**
     * 构造 Router，并完成窗口基础初始化与页面注册。
     *
     * @param title 主窗口标题
     */
    public Router(String title) {
        this.frame = new JFrame(title);
        this.cards = new CardLayout();
        this.root = new JPanel(cards);

        try {
            initFrame();
            initRoutes();
            frame.setContentPane(root);
        } catch (MyException e) {
            PopUp.showErrorDialog(e, frame);
            throw e;
        }
    }

    /** 初始化主窗口基础属性 */
    private void initFrame() {
        try {
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setMinimumSize(new Dimension(900, 600));
            frame.setLocationRelativeTo(null);
        } catch (Exception e) {
            MyException.fail(
                    Router.class,
                    METHOD_INIT_FRAME,
                    "初始化主窗口基础属性失败。",
                    MyException.ERROR_DEFAULT_CODE,
                    e
            );
        }
    }

    /**
     * 初始化并注册当前系统全部页面。
     *
     * <p>后续如新增页面，优先在这里集中注册，避免构造函数持续膨胀。</p>
     */
    private void initRoutes() {
        try {
            register(ROUTE_WELCOME, new Welcome(this::openUserNotes));
            register(ROUTE_HOME, new Home());
        } catch (MyException e) {
            throw e;
        } catch (Exception e) {
            MyException.fail(
                    Router.class,
                    METHOD_INIT_ROUTES,
                    "初始化路由表失败。",
                    MyException.ERROR_DEFAULT_CODE,
                    e
            );
        }
    }

    /**
     * 注册一个页面到路由表与 CardLayout 中。
     *
     * @param routeKey 路由唯一标识
     * @param page     页面组件
     */
    public void register(String routeKey, JComponent page) {
        if (routeKey == null || routeKey.isBlank()) {
            MyException.fail(
                    Router.class,
                    METHOD_REGISTER,
                    "注册路由失败：routeKey 不能为空。",
                    ERROR_ROUTE_KEY_EMPTY
            );
        }

        if (page == null) {
            MyException.fail(
                    Router.class,
                    METHOD_REGISTER,
                    "注册路由失败：页面组件不能为空。",
                    ERROR_PAGE_NULL
            );
        }

        if (routes.containsKey(routeKey)) {
            MyException.fail(
                    Router.class,
                    METHOD_REGISTER,
                    "注册路由失败：检测到重复路由 [" + routeKey + "]。",
                    ERROR_ROUTE_DUPLICATE
            );
        }

        try {
            routes.put(routeKey, page);
            root.add(page, routeKey);
        } catch (Exception e) {
            MyException.fail(
                    Router.class,
                    METHOD_REGISTER,
                    "注册路由失败：页面加入根容器时发生异常，路由为 [" + routeKey + "]。",
                    MyException.ERROR_DEFAULT_CODE,
                    e
            );
        }
    }

    /**
     * 切换到指定路由页面。
     *
     * @param routeKey 目标路由键
     */
    public void navigate(String routeKey) {
        if (!routes.containsKey(routeKey)) {
            MyException.fail(
                    Router.class,
                    METHOD_NAVIGATE,
                    "页面跳转失败：未注册的路由 [" + routeKey + "]。",
                    ERROR_ROUTE_NOT_FOUND
            );
        }

        try {
            cards.show(root, routeKey);
            currentRoute = routeKey;
        } catch (Exception e) {
            MyException.fail(
                    Router.class,
                    METHOD_NAVIGATE,
                    "页面跳转失败：切换到路由 [" + routeKey + "] 时发生异常。",
                    MyException.ERROR_DEFAULT_CODE,
                    e
            );
        }
    }

    /**
     * 启动应用。
     *
     * <p>默认进入欢迎页，然后显示主窗口。</p>
     */
    public void start() {
        try {
            navigate(ROUTE_WELCOME);
            frame.setVisible(true);
        } catch (MyException e) {
            PopUp.showErrorDialog(e, frame);
            throw e;
        } catch (Exception e) {
            MyException myException = new MyException(
                    Router.class,
                    METHOD_START,
                    "应用启动失败。",
                    MyException.ERROR_DEFAULT_CODE,
                    e
            );
            PopUp.showErrorDialog(myException, frame);
            throw myException;
        }
    }

    /* ---------------- 业务流程 ---------------- */

    /**
     * 打开用户须知弹窗。
     *
     * <p>同意后跳转到主页；不同意则弹提示并返回欢迎页。</p>
     */
    public void openUserNotes() {
        try {
            UserNotes dialog = new UserNotes(
                    frame,
                    () -> navigate(ROUTE_HOME),
                    this::onDisagree
            );
            dialog.setVisible(true);
        } catch (MyException e) {
            PopUp.showErrorDialog(e, frame);
            throw e;
        } catch (Exception e) {
            MyException myException = new MyException(
                    Router.class,
                    METHOD_OPEN_USER_NOTES,
                    "打开用户须知弹窗失败。",
                    ERROR_DIALOG_CREATE,
                    e
            );
            PopUp.showErrorDialog(myException, frame);
            throw myException;
        }
    }

    /**
     * 用户拒绝用户须知后的处理逻辑：
     * 先提示，再返回欢迎页。
     */
    private void onDisagree() {
        try {
            PopUp.userNotesDisagree(frame);
            navigate(ROUTE_WELCOME);
        } catch (MyException e) {
            PopUp.showErrorDialog(e, frame);
            throw e;
        } catch (Exception e) {
            MyException myException = new MyException(
                    Router.class,
                    METHOD_ON_DISAGREE,
                    "处理用户须知拒绝逻辑失败。",
                    MyException.ERROR_DEFAULT_CODE,
                    e
            );
            PopUp.showErrorDialog(myException, frame);
            throw myException;
        }
    }

    /**
     * 获取主窗口。
     *
     * @return 主窗口对象
     */
    public JFrame getFrame() {
        return frame;
    }

    /**
     * 获取当前路由。
     *
     * @return 当前路由键
     */
    public String getCurrentRoute() {
        return currentRoute;
    }
}