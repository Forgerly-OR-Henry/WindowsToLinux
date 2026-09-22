package gold.debug.windowstolinux.app.ui.backup;

import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.*;

import gold.debug.windowstolinux.app.service.contract.BackupApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.AdvancedOptionsPane;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;

/**
 * Owns saved backup/migration choices and keeps their exact identifiers in the advanced drawer. / 持有已保存的备份和迁移选项，并在高级面板中保留其精确标识。
 */
final class BackupSelectionPane extends JPanel {
    /**
     * Bound backup application facade collaborator for application service used by the caller.
     * <p>处理调用方使用的应用服务的备份应用门面协作对象。
     */
    private final BackupApplicationFacade service;

    /**
     * Structured failure occurrence retained for safe reporting.
     * <p>保留用于安全报告的结构化失败实例。
     */
    private final Consumer<Exception> failure;

    /**
     * Swing control for application id.
     * <p>应用标识对应的 Swing 控件。
     */
    private final JTextField applicationId = new JTextField();

    /**
     * Swing control for target server id.
     * <p>目标服务器标识对应的 Swing 控件。
     */
    private final JTextField targetServerId = new JTextField();

    /**
     * Applications.
     * <p>应用集合。
     */
    private final JComboBox<SavedChoice> applications = new JComboBox<>();

    /**
     * Server-profile and authenticated-session service.
     * <p>服务器资料及已认证会话服务。
     */
    private final JComboBox<SavedChoice> servers = new JComboBox<>();

    /**
     * Swing control for application row.
     * <p>应用数据行对应的 Swing 控件。
     */
    private final JPanel applicationRow;

    /**
     * Swing control for server row.
     * <p>服务器数据行对应的 Swing 控件。
     */
    private final JPanel serverRow;

    /**
     * Whether saved choices are being loaded and selection callbacks must be deferred.
     * <p>是否正在加载已保存选项且须延后选择回调。
     */
    private boolean loading;

    /**
     * Binds the supplied dependencies and state for backup selection pane.
     * <p>为备份选择面板绑定传入的依赖及状态。
     *
     * @param service application service used by the caller / 调用方使用的应用服务
     * @param messages localized message resolver / 本地化消息解析器
     * @param failure structured failure occurrence retained for safe reporting / 保留用于安全报告的结构化失败实例
     */
    BackupSelectionPane(BackupApplicationFacade service, PageMessagePresenter messages, Consumer<Exception> failure) {
        super(new GridBagLayout());
        this.service = service;
        this.failure = failure;
        setOpaque(false);
        applicationRow = row(messages.text("backup.field.application"), applications);
        serverRow = row(messages.text("backup.field.server"), servers);
        var constraints = new GridBagConstraints();
        constraints.gridwidth = GridBagConstraints.REMAINDER;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        add(applicationRow, constraints);
        add(serverRow, constraints);
        applications.addActionListener(event -> select(applications, applicationId));
        servers.addActionListener(event -> select(servers, targetServerId));
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing())
                refresh();
        });
    }

    /**
     * Returns managed application identifier.
     * <p>返回受管应用标识。
     *
     * @return managed application identifier / 受管应用标识
     */
    String applicationId() {
        return applicationId.getText();
    }

    /**
     * Returns target server id.
     * <p>返回目标服务器标识。
     *
     * @return target server id / 目标服务器标识
     */
    String targetServerId() {
        return targetServerId.getText();
    }

    /**
     * Restores backup selection pane.
     * <p>恢复备份选择面板。
     *
     * @param application managed target with its server and ownership identity / 携带服务器及归属身份的受管目标
     * @param server server identity or selected server configuration / 服务器身份或所选服务器配置
     */
    void restore(String application, String server) {
        applicationId.setText(application);
        targetServerId.setText(server);
    }

    /**
     * Registers application and target-server identifier fields in the advanced pane.
     * <p>在高级面板中登记应用及目标服务器标识字段。
     *
     * @param advanced advanced / 高级
     */
    void advanced(AdvancedOptionsPane advanced) {
        advanced.field("backup.field.applicationId", applicationId);
        advanced.field("backup.field.targetServerId", targetServerId);
    }

    /**
     * Shows the application and server selectors required by the selected backup task.
     * <p>显示所选备份任务需要的应用及服务器选择器。
     *
     * @param selected explicitly selected item or state / 显式选择的项目或状态
     */
    void task(int selected) {
        applicationRow.setVisible(selected != 1);
        serverRow.setVisible(selected != 0);
        revalidate();
    }

    /**
     * Disables selection and identifier editing while a backup task is running.
     * <p>备份任务运行期间禁用选择及标识编辑。
     *
     * @param busy whether a page action is in progress and conflicting controls must remain disabled / 页面动作是否正在进行且冲突控件须保持禁用
     */
    void busy(boolean busy) {
        applications.setEnabled(!busy);
        servers.setEnabled(!busy);
        applicationId.setEnabled(!busy);
        targetServerId.setEnabled(!busy);
    }

    /**
     * Refreshes backup selection pane.
     * <p>刷新备份选择面板。
     */
    void refresh() {
        if (service == null || loading)
            return;
        loading = true;
        DesktopTaskExecutor.run(() -> new SavedLists(
                service.listManagedApplicationSummaries().stream()
                        .map(value -> new SavedChoice(value.application().id(),
                                value.application().id() + "  ·  " + value.application().server().host()))
                        .toList(),
                service.listServerProfiles().stream()
                        .map(value -> new SavedChoice(value.id(), value.id() + "  ·  " + value.host())).toList()),
                lists -> {
                    fill(applications, lists.applications(), applicationId);
                    fill(servers, lists.servers(), targetServerId);
                    loading = false;
                }, error -> {
                    loading = false;
                    failure.accept(error);
                });
    }

    /**
     * Selects backup selection pane.
     * <p>选择备份选择面板。
     *
     * @param choices choices / 选项集合
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     */
    private void select(JComboBox<SavedChoice> choices, JTextField field) {
        if (!loading && choices.getSelectedItem() instanceof SavedChoice choice)
            field.setText(choice.id());
    }

    /**
     * Refreshes saved choices while preserving the identifier currently entered in the field.
     * <p>刷新已保存选项，并保留字段中当前输入的标识。
     *
     * @param choices choices / 选项集合
     * @param values ordered contents supplied to the current conversion or validation / 提供给当前转换或校验的有序内容
     * @param field field name or input definition being validated / 正在校验的字段名或输入定义
     */
    private void fill(JComboBox<SavedChoice> choices, List<SavedChoice> values, JTextField field) {
        String selected = field.getText();
        choices.removeAllItems();
        values.forEach(choices::addItem);
        choices.setSelectedIndex(-1);
        values.stream().filter(value -> value.id().equals(selected)).findFirst().ifPresent(choices::setSelectedItem);
        if (selected.isBlank() && !values.isEmpty()) {
            choices.setSelectedIndex(0);
            field.setText(values.getFirst().id());
        }
    }

    /**
     * Builds a transparent selector row and associates its label with the input control.
     * <p>构建透明选择器行，并将标签与输入控件关联。
     *
     * @param label label / 标签
     * @param input source content consumed by this operation / 当前操作消费的源内容
     * @return a transparent selector row and associates its label with the input control / 透明选择器行，并将标签与输入控件关联
     */
    private static JPanel row(String label, JComponent input) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        JLabel title = new JLabel(label);
        title.setLabelFor(input);
        row.add(title, BorderLayout.WEST);
        row.add(input, BorderLayout.CENTER);
        return row;
    }
    /**
     * Represents a persisted item displayed in the backup selection controls.
     * <p>表示备份选择控件中展示的持久化选项。
     *
     * @param id stable identifier within the owning registry / 所属登记表内的稳定标识
     * @param label label / 标签
     */
    private record SavedChoice(String id, String label) {
        /**
         * Returns label.
         * <p>返回标签。
         *
         * @return label / 标签
         */
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Groups persisted choices loaded for the backup selection controls.
     * <p>组合备份选择控件加载的持久化选项。
     *
     * @param applications applications / 应用集合
     * @param servers server-profile and authenticated-session service / 服务器资料及已认证会话服务
     */
    private record SavedLists(List<SavedChoice> applications, List<SavedChoice> servers) {
    }
}
