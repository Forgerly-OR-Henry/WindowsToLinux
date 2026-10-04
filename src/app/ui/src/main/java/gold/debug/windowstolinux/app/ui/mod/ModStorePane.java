package gold.debug.windowstolinux.app.ui.mod;

import java.awt.BorderLayout;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.mod.store.ModStoreGateway;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreService;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.control.DesktopCardList;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;

/** Explicit store search, confirmation and installation with no polling. / 明确商店搜索、确认及安装，无轮询。 */
public final class ModStorePane extends JPanel implements AutoCloseable {
    /** Store query and installation composition. / 商店查询及安装装配。 */
    private final ModStoreService service;

    /** Localized status and safe failure presentation. / 本地化状态及安全失败呈现。 */
    private final PageMessagePresenter messages;

    /** Refresh callback after a confirmed successful installation. / 确认安装成功后的刷新回调。 */
    private final Runnable installed;

    /** User-entered search keyword retained across retries. / 重试期间保留的用户搜索词。 */
    private final JTextField search = new JTextField();

    /** Current advertised release results. / 当前声明的发布结果。 */
    private final DefaultListModel<ModStoreGateway.Release> rows = new DefaultListModel<>();

    /** Single-release selection for details and confirmation. / 用于详情及确认的单发布选择。 */
    private final DesktopCardList<ModStoreGateway.Release> list = new DesktopCardList<>(rows, 48);

    /** Plain-text details of the selected release. / 所选发布的纯文本详情。 */
    private final JTextArea details = new JTextArea();

    /** Localized query and installation status. / 本地化查询及安装状态。 */
    private final JLabel status = new JLabel();

    /** Explicit query retry and confirmed installation controls. / 明确查询重试及确认安装控件。 */
    private final JButton retry, download;

    /** Transfer progress, indeterminate when size is unknown. / 传输进度，大小未知时为不定状态。 */
    private final JProgressBar progress = new JProgressBar(0, 100);

    /** Privacy, disposal, in-flight operation and first-query flags. / 隐私、销毁、执行中操作及首次查询标志。 */
    private boolean locked = true, closed, busy, queried;

    /** Generation rejecting callbacks crossing privacy or disposal boundaries. / 拒绝跨隐私或销毁边界回调的代数。 */
    private long epoch;

    /** Currently owned installation confirmation dialog. / 当前所属安装确认对话框。 */
    private JDialog confirmation;

    /** Constructs a query-free view; the first unlock initiates one query. / 构造时不查询，首次解锁发起一次查询。
     * @param service actual store composition / 实际商店装配
     * @param components themed controls / 主题控件
     * @param messages localized messages / 本地化消息
     * @param installed installed-list refresh / 安装清单刷新 */
    public ModStorePane(ModStoreService service, DesktopPartFactory components, PageMessagePresenter messages, Runnable installed) {
        super(new BorderLayout(12, 12));
        this.service = service;
        this.messages = messages;
        this.installed = installed;
        setName("mods.store");
        components.stylePage(this);
        setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
        search.setName("store.search");
        search.addActionListener(event -> query());
        retry = messages.bind(components.secondaryButton(""), "store.retry");
        retry.setName("store.retry");
        retry.addActionListener(event -> query());
        download = messages.bind(components.primaryButton(""), "store.install");
        download.setName("store.install");
        download.addActionListener(event -> confirm());
        var header = new JPanel(new BorderLayout(8, 8));
        header.add(messages.bind(new JLabel(), "store.search"), BorderLayout.WEST);
        header.add(search);
        header.add(retry, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);
        list.setName("store.results");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer((view, value, index, selected, focused) -> {
            var renderer = new DefaultListCellRenderer();
            renderer.putClientProperty("html.disable", true);
            return renderer.getListCellRendererComponent(view, value == null ? "" : value.title() + " · " + value.version(), index, selected, focused);
        });
        components.cardList(list, false);
        list.addListSelectionListener(event -> {
            var selected = list.getSelectedValue();
            details.setText(selected == null ? "" : selected.title() + "\n" + selected.owner() + " · " + selected.version() + "\n\n" + selected.description());
            controls();
        });
        details.setName("store.details");
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(list), new JScrollPane(details));
        split.setResizeWeight(.4);
        add(split);
        progress.setName("store.progress");
        progress.setStringPainted(true);
        progress.setVisible(false);
        var footer = new JPanel(new BorderLayout(8, 8));
        footer.add(download, BorderLayout.EAST);
        footer.add(status);
        footer.add(progress, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        if (!service.canInstall()) messages.bind(status, "store.classMode");
        controls();
    }

    /** Searches or retries once, preserving the typed keyword. / 搜索或重试一次，保留输入搜索词。 */
    public void query() {
        if (locked || closed || busy) return;
        String keyword = search.getText();
        long generation = epoch;
        busy = true;
        queried = true;
        controls();
        messages.bind(status, "store.searching");
        DesktopTaskRunner.run(() -> service.query(keyword), result -> {
            busy = false;
            if (closed || locked || epoch != generation) {
                controls();
                return;
            }
            rows.clear();
            result.releases().forEach(rows::addElement);
            messages.bind(status, rows.isEmpty() ? "store.empty" : service.canInstall() ? "store.ready" : "store.classMode");
            controls();
        }, failure -> {
            busy = false;
            if (closed || locked || epoch != generation) {
                controls();
                return;
            }
            rows.clear();
            messages.bind(status, "store.unavailable");
            controls();
        });
    }

    /** Confirms the selected release and rejects stale approval before installation. / 确认所选发布并在安装前拒绝过期批准。 */
    private void confirm() {
        if (locked || closed || busy || !service.canInstall() || list.getSelectedValue() == null) return;
        var release = list.getSelectedValue();
        long generation = epoch;
        var choice = new JOptionPane(messages.text("store.confirm") + "\n" + release.title() + " · " + release.version(),
            JOptionPane.QUESTION_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        confirmation = choice.createDialog(this, messages.text("store.install"));
        confirmation.setName("store.confirmation");
        try {
            confirmation.setVisible(true);
        } finally {
            if (confirmation != null) confirmation.dispose();
            confirmation = null;
        }
        if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(choice.getValue()) || locked || closed || busy || epoch != generation)
            return;
        busy = true;
        controls();
        progress.setValue(0);
        progress.setIndeterminate(true);
        progress.setVisible(true);
        messages.bind(status, "store.downloading");
        DesktopTaskRunner.run(() -> service.install(release, value -> SwingUtilities.invokeLater(() -> {
            if (closed || locked || epoch != generation) return;
            progress.setIndeterminate(value.total() == 0);
            if (value.total() > 0) progress.setValue((int) (100.0 * value.completed() / value.total()));
        })), result -> {
            busy = false;
            progress.setVisible(false);
            if (closed || locked || epoch != generation) {
                controls();
                return;
            }
            if (result.outcome() == TaskResult.SUCCEEDED) {
                messages.bind(status, "store.installed");
                installed.run();
            } else {
                LocaleBinding.unbind(status);
                status.setText(result.problem().map(messages::safe).orElse(messages.text("store.failed")));
            }
            controls();
        }, failure -> {
            busy = false;
            progress.setVisible(false);
            if (closed || locked || epoch != generation) {
                controls();
                return;
            }
            LocaleBinding.unbind(status);
            status.setText(messages.safe(failure));
            controls();
        });
    }

    /** Clears private results at lock boundaries. / 在锁定边界清除私有结果。
     * @param value privacy state / 隐私状态 */
    public void setLocked(boolean value) {
        locked = value;
        epoch++;
        if (locked) {
            LocaleBinding.unbind(status);
            status.setText("");
            progress.setVisible(false);
            queried = false;
            if (confirmation != null) confirmation.dispose();
        }
        controls();
        if (!locked && !queried && !busy) query();
    }

    /** Enables controls only for an unlocked idle view with required capability. / 仅为具备必需能力的已解锁空闲界面启用控件。 */
    private void controls() {
        boolean active = !locked && !closed && !busy;
        search.setEnabled(active);
        retry.setEnabled(active);
        list.setEnabled(active);
        download.setEnabled(active && service.canInstall() && list.getSelectedValue() != null);
    }

    /** Disposes confirmation and private results, invalidating pending callbacks. / 销毁确认及私有结果，使待处理回调失效。 */
    @Override
    public void close() {
        closed = true;
        epoch++;
        if (confirmation != null) confirmation.dispose();
        rows.clear();
        details.setText("");
        LocaleBinding.unbind(status);
        status.setText("");
        progress.setVisible(false);
        controls();
    }
}
