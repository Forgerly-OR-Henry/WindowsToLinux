package gold.debug.windowstolinux.mod.deployment.ui;

import java.nio.file.Path;
import java.util.Set;

import javax.swing.*;

import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticWorkflow;
import gold.debug.windowstolinux.mod.deployment.file.git.*;

/** Holds visible Git-only inputs and constructs one credential-free reviewed request. / 持有仅 Git 可见输入并构建一项不含凭据的经审阅请求。 */
final class DeploymentGitSourceForm {
    /** Git-only rows. / 仅 Git 行。 */
    private final JPanel panel = DeploymentPageStyle.form();

    /** Credential-free original URL. / 不含凭据的原 URL。 */
    private final JTextField url = new JTextField(24);

    /** Typed selected reference. / 类型化选定引用。 */
    private final JComboBox<String> kind = new JComboBox<>(new String[]{"DEFAULT", "BRANCH", "TAG", "COMMIT"});

    /** Branch, tag or full commit text. / 分支、标签或完整提交文本。 */
    private final JTextField ref = new JTextField(24);

    /** Explicit native executable path. / 明确原生可执行文件路径。 */
    private final JTextField executable = new JTextField(24);

    /** Controlled local file permission. / 受控本地文件许可。 */
    private final JCheckBox permitLocal = new JCheckBox();

    /** Localized field labels. / 本地化字段标签。 */
    private final JLabel urlLabel = new JLabel(), kindLabel = new JLabel(), refLabel = new JLabel(),
        executableLabel = new JLabel();

    /** Owner page used only for current messages. / 仅用于当前消息的所属页。 */
    private final DeploymentStaticPage page;

    /** Builds hidden Git-only controls; no process starts here. / 构建默认隐藏的仅 Git 控件；此处不启动进程。
     * @param page original owner page / 原所属页 */
    DeploymentGitSourceForm(DeploymentStaticPage page) {
        this.page = page;
        panel.setName("deployment.git");
        url.setName("deployment.git.url");
        executable.setName("deployment.git.executable");
        DeploymentPageStyle.field(panel, urlLabel, url);
        DeploymentPageStyle.field(panel, kindLabel, kind);
        DeploymentPageStyle.field(panel, refLabel, ref);
        DeploymentPageStyle.field(panel, executableLabel, executable);
        panel.add(permitLocal);
        kind.setRenderer((list, value, index, selected, focus) -> {
            var label = new JLabel(value == null ? "" : page.text("gitKind." + value));
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        kind.addActionListener(ignored -> ref.setEnabled(!"DEFAULT".equals(kind.getSelectedItem())));
        ref.setEnabled(false);
        localize();
        panel.setVisible(false);
    }

    /** Returns the Git rows for the parent layout. / 返回供父布局使用的 Git 行。
     * @return original panel / 原面板 */
    JPanel panel() {
        return panel;
    }

    /** Shows only the selected mode's inputs without discarding edits. / 仅显示选定模式输入而不丢弃编辑。
     * @param visible whether Git mode is selected / 是否选定 Git 模式 */
    void visible(boolean visible) {
        panel.setVisible(visible);
    }

    /** Refreshes only labels when the host locale changes. / 宿主语言改变时仅刷新标签。 */
    void localize() {
        urlLabel.setText(page.text("gitUrl"));
        kindLabel.setText(page.text("gitKind"));
        refLabel.setText(page.text("gitRef"));
        executableLabel.setText(page.text("gitExecutable"));
        permitLocal.setText(page.text("allowLocalGit"));
        kind.repaint();
    }

    /** Reads a typed input before task admission, retaining no credential in the request. / 任务准入前读取类型化输入，请求不保留凭据。
     * @return exact Git source choice / 精确 Git 来源选择 */
    DeploymentStaticWorkflow.GitSource source() {
        var remote = DeploymentGitRemote.parse(url.getText().trim());
        var reference = switch ((String) kind.getSelectedItem()) {
            case "DEFAULT" -> new DeploymentGitRef.DefaultBranch();
            case "BRANCH" -> new DeploymentGitRef.Branch(ref.getText().trim());
            case "TAG" -> new DeploymentGitRef.Tag(ref.getText().trim());
            case "COMMIT" -> new DeploymentGitRef.Commit(ref.getText().trim());
            default -> throw new IllegalArgumentException("Unknown Git reference kind");
        };
        var hosts = remote.host().map(Set::of).orElseGet(Set::of);
        var request = new DeploymentGitRequest(remote, reference, hosts, 2L * 1024 * 1024 * 1024,
            permitLocal.isSelected());
        var path = Path.of(executable.getText().trim());
        if (!path.isAbsolute())
            throw new IllegalArgumentException("Native Git executable path must be absolute");
        return new DeploymentStaticWorkflow.GitSource(request, path);
    }
}
