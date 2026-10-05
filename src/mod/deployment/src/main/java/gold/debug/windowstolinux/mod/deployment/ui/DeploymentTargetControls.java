package gold.debug.windowstolinux.mod.deployment.ui;

import java.awt.BorderLayout;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.result.ApiException;
import gold.debug.windowstolinux.appapi.ui.page.PlatformPageKind;

/** Maintains explicit target identity and navigates only to fixed public host destinations. / 保持明确目标身份，仅导航到固定公开宿主目标。 */
final class DeploymentTargetControls {
    /** Opens only the fixed public owner destinations. / 仅打开固定公开所属目标。 */
    @FunctionalInterface
    interface PlatformNavigation {
        /** Opens the original host target without exposing host controls. / 打开原宿主目标，不暴露宿主控件。
         * @param target fixed target / 固定目标
         * @throws ApiException if the original host rejects access / 原宿主拒绝访问时 */
        void open(PlatformPageKind target) throws ApiException;
    }

    /** Original local labels. / 原本地标签。 */
    private final Function<String, String> text;

    /** Original finite public navigation. / 原有限公开导航。 */
    private final PlatformNavigation navigation;

    /** Actual configured choices. / 实际配置选项。 */
    private final JComboBox<ServerInfo> servers;

    /** Safe failure display. / 安全失败显示。 */
    private final Consumer<String> feedback;

    /** Fixed target actions and public metadata. / 固定目标操作及公开元数据。 */
    private final JPanel panel = new JPanel(new BorderLayout());

    /** Original clear action. / 原清除操作。 */
    private final JButton clear = new JButton();

    /** Explicit refresh action. / 明确刷新操作。 */
    private final JButton refresh = new JButton();

    /** Fixed host destinations. / 固定宿主目标。 */
    private final JButton serversPage = new JButton(), modelsPage = new JButton(), tasksPage = new JButton();

    /** Public summary with no SSH observation. / 不进行 SSH 观察的公开摘要。 */
    private final JTextArea summary = new JTextArea(4, 24);

    /** Model updates cannot become user choices. / 模型更新不能变成用户选择。 */
    private boolean updating;

    /** Binds existing explicit target controls. / 绑定既有明确目标控件。
     * @param text labels / 标签
     * @param navigation owner-bound finite navigation / 所属绑定有限导航
     * @param servers original server choices / 原服务器选项
     * @param changed original review invalidation / 原审阅撤销
     * @param reload explicit list refresh / 明确列表刷新
     * @param feedback safe feedback / 安全反馈 */
    DeploymentTargetControls(Function<String, String> text, PlatformNavigation navigation,
                             JComboBox<ServerInfo> servers, Runnable changed, Runnable reload, Consumer<String> feedback) {
        this.text = text;
        this.navigation = java.util.Objects.requireNonNull(navigation);
        this.servers = servers;
        this.feedback = feedback;
        clear.setName("deployment.server.clear");
        refresh.setName("deployment.server.refresh");
        serversPage.setName("deployment.platform.servers");
        modelsPage.setName("deployment.platform.models");
        tasksPage.setName("deployment.platform.tasks");
        var actions = new JPanel();
        actions.add(clear);
        actions.add(refresh);
        actions.add(serversPage);
        actions.add(modelsPage);
        actions.add(tasksPage);
        panel.add(actions, BorderLayout.NORTH);
        summary.setName("deployment.server.summary");
        summary.setEditable(false);
        summary.setLineWrap(true);
        summary.setWrapStyleWord(true);
        panel.add(summary, BorderLayout.CENTER);
        clear.addActionListener(ignored -> servers.setSelectedItem(null));
        refresh.addActionListener(ignored -> reload.run());
        serversPage.addActionListener(ignored -> open(PlatformPageKind.SERVERS));
        modelsPage.addActionListener(ignored -> open(PlatformPageKind.AI_MODELS));
        tasksPage.addActionListener(ignored -> open(PlatformPageKind.TASKS));
        servers.addActionListener(ignored -> {
            if (!updating) {
                updateSummary();
                changed.run();
            }
        });
        localize();
    }

    /** Returns actual target rows. / 返回实际目标行。
     * @return panel / 面板 */
    JPanel panel() {
        return panel;
    }

    /** Replaces observations while retaining only an explicit exact server identity. / 替换观察值，仅保留明确精确服务器身份。
     * @param values actual configured records / 实际配置记录 */
    void replace(List<ServerInfo> values) {
        var prior = servers.getSelectedItem() instanceof ServerInfo selected ? selected.ref() : null;
        updating = true;
        try {
            servers.removeAllItems();
            values.forEach(servers::addItem);
            servers.setSelectedItem(prior == null ? null : values.stream().filter(server -> server.ref().equals(prior)).findFirst().orElse(null));
        } finally {
            updating = false;
        }
        updateSummary();
    }

    /** Shows only public configured and observed facts; absent observations stay absent. / 仅显示公开配置及观察事实，缺失观察仍为缺失。 */
    private void updateSummary() {
        if (!(servers.getSelectedItem() instanceof ServerInfo server)) {
            summary.setText(text.apply("target.select"));
            return;
        }
        summary.setText(server.name() + " / " + server.ref().id() + "\n" + server.username() + "@" + server.host() + ":" + server.port()
            + "\n" + text.apply("target.fingerprint") + ": " + server.trustedFingerprint().orElse(text.apply("target.unobserved"))
            + "\n" + text.apply("target.observed") + ": " + server.observedAt().map(Object::toString).orElse(text.apply("target.unobserved")));
    }

    /** Opens a fixed public destination on the original EDT owner boundary. / 在原 EDT 所属边界打开固定公开目标。
     * @param target fixed destination / 固定目标 */
    private void open(PlatformPageKind target) {
        try {
            navigation.open(target);
        } catch (ApiException unavailable) {
            feedback.accept(text.apply("target.unavailable") + " " + unavailable.problem().eventCode());
        }
    }

    /** Updates editing without implicitly choosing a target. / 更新编辑能力，不隐式选择目标。
     * @param enabled unlocked idle original owner / 未锁定空闲原所属者
     * @param clearAllowed whether an unanswered original target may be revoked / 是否可撤销未答原目标 */
    void availability(boolean enabled, boolean clearAllowed) {
        servers.setEnabled(enabled);
        clear.setEnabled(clearAllowed && servers.getSelectedItem() != null);
        refresh.setEnabled(enabled);
        serversPage.setEnabled(enabled);
        modelsPage.setEnabled(enabled);
        tasksPage.setEnabled(enabled);
    }

    /** Updates labels while keeping the same target and controls. / 更新标签，保留同一目标及控件。 */
    void localize() {
        clear.setText(text.apply("target.clear"));
        refresh.setText(text.apply("target.refresh"));
        serversPage.setText(text.apply("target.servers"));
        modelsPage.setText(text.apply("target.models"));
        tasksPage.setText(text.apply("target.tasks"));
        updateSummary();
    }
}
