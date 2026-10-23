package gold.debug.windowstolinux.mod.deployment.ui.agent;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import javax.swing.*;

import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRef;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRemote;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRequest;

/** Owns the actual credential-free pinned Git input controls. / 持有实际不含凭据的固定Git输入控件。 */
final class DeploymentAgentGitForm {
    /** Reviewed project name. / 经审阅项目名称。 */
    private final JTextField name = new JTextField(28);

    /** Credential-free remote. / 不含凭据远端。 */
    private final JTextField url = new JTextField(28);

    /** Explicit reference category. / 明确引用类别。 */
    private final JComboBox<String> kind = new JComboBox<>(new String[]{"DEFAULT", "BRANCH", "TAG", "COMMIT"});

    /** Exact original reference. / 精确原引用。 */
    private final JTextField reference = new JTextField(28);

    /** Actual native executable. / 实际原生可执行文件。 */
    private final JTextField executable = new JTextField(28);

    /** Explicit local repository allowance. / 明确本地仓库允许。 */
    private final JCheckBox local = new JCheckBox();

    /** Captured immutable source input. / 抓取的不可变来源输入。
     * @param request exact source / 精确来源
     * @param executable actual binary / 实际程序
     * @param name reviewed display name / 经审阅展示名称 */
    record Input(DeploymentGitRequest request, Path executable, String name) {
    }

    /** Creates the bounded source controls. / 创建有界来源控件。 */
    DeploymentAgentGitForm() {
        kind.addActionListener(ignored -> reference.setEnabled(!"DEFAULT".equals(kind.getSelectedItem())));
        url.setEditable(false);
    }

    /** Adds the actual localized controls in source row order. / 按来源行顺序添加实际本地化控件。
     * @param row owning page label attachment / 所属页面标签连接 */
    void add(BiConsumer<String, JComponent> row) {
        row.accept("projectName", name);
        row.accept("gitUrl", url);
        row.accept("gitKind", kind);
        row.accept("gitRef", reference);
        row.accept("gitExecutable", executable);
        row.accept("allowLocal", local);
    }

    /** Applies actual source and locale availability. / 应用实际来源及语言可用性。
     * @param enabled whether original edits are admitted / 是否接纳原编辑
     * @param git whether Git is selected / 是否选择Git
     * @param text actual localized checkbox label / 实际本地化复选标签 */
    void configure(boolean enabled, boolean git, String text) {
        name.setEnabled(enabled && git);
        url.setEnabled(enabled && git);
        kind.setEnabled(enabled && git);
        executable.setEnabled(enabled && git);
        local.setEnabled(enabled && git);
        reference.setEnabled(enabled && git && !"DEFAULT".equals(kind.getSelectedItem()));
        local.setText(text);
    }

    /** Loads only explicitly accepted remote metadata without fetching it. / 仅加载明确接受的远端元数据，不获取来源。
     * @param value accepted remote or explicit clear / 已接受远端或明确清除 */
    void loadRemote(String value) {
        url.setText(value);
    }

    /** Reads the original remote display for exact captured metadata comparison. / 读取原远端显示用于精确抓取元数据比较。
     * @return displayed metadata / 显示元数据 */
    String remote() {
        return url.getText().trim();
    }

    /** Captures validated fixed source values on the owning EDT. / 在所属EDT抓取已校验固定来源值。
     * @return immutable original source / 不可变原来源 */
    Input capture() {
        String projectName = name.getText().trim();
        if (projectName.isBlank()) throw new IllegalArgumentException("Project name missing");
        var remote = DeploymentGitRemote.parse(url.getText().trim());
        DeploymentGitRef ref = switch ((String) kind.getSelectedItem()) {
            case "DEFAULT" -> new DeploymentGitRef.DefaultBranch();
            case "BRANCH" -> new DeploymentGitRef.Branch(reference.getText().trim());
            case "TAG" -> new DeploymentGitRef.Tag(reference.getText().trim());
            case "COMMIT" -> new DeploymentGitRef.Commit(reference.getText().trim());
            default -> throw new IllegalArgumentException("Git reference missing");
        };
        var request = new DeploymentGitRequest(remote, ref, remote.host().map(Set::of).orElseGet(Set::of),
            2L * 1024 * 1024 * 1024, local.isSelected());
        var binary = Path.of(executable.getText().trim());
        if (!binary.isAbsolute()) throw new IllegalArgumentException("Native Git path must be absolute");
        return new Input(request, binary, projectName);
    }
}
