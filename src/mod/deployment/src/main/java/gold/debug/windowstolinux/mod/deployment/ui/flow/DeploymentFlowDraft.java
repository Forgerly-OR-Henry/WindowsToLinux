package gold.debug.windowstolinux.mod.deployment.ui.flow;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import javax.swing.*;

import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.mod.deployment.file.git.*;
import gold.debug.windowstolinux.mod.deployment.service.core.source.DeploymentSourceSelectionService.LocalLocation;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.contract.DeploymentStandardInputs;
import gold.debug.windowstolinux.mod.deployment.ui.preference.DeploymentPreferenceSelection;
import gold.debug.windowstolinux.mod.deployment.ui.source.DeploymentLocalSourceControls;

/** One nonsecret source, target and preference draft owned by the unified flow. / 统一流程持有的一份非秘密来源、目标及偏好草稿。 */
public final class DeploymentFlowDraft {
    public enum SourceMode {LOCAL, GIT}

    public final JTextField name = new JTextField(24), remote = new JTextField(24), reference = new JTextField(24), executable = new JTextField(24);

    public final JComboBox<SourceMode> sourceMode = new JComboBox<>(SourceMode.values());

    public final JComboBox<String> referenceKind = new JComboBox<>(new String[]{"DEFAULT", "BRANCH", "TAG", "COMMIT"});

    public final JCheckBox permitLocal = new JCheckBox();

    public final JComboBox<ServerInfo> servers = new JComboBox<>();

    public DeploymentLocalSourceControls local;

    public DeploymentPreferenceSelection preferences;

    public String mode = "ASSISTED";

    public boolean frozen;

    public boolean gitSelected() {
        return sourceMode.getSelectedItem() == SourceMode.GIT;
    }

    public Optional<LocalLocation> localLocation() {
        return gitSelected() ? Optional.empty() : local.selection();
    }

    public ServerInfo server() {
        return servers.getSelectedItem() instanceof ServerInfo value ? value : null;
    }

    public boolean ready() {
        return !local.busy() && (gitSelected() ? !remote.getText().isBlank() : local.selection().isPresent());
    }

    /** Captures metadata only; actual file and Git authority still belongs to the admitted worker. / 仅抓取元数据；实际文件及Git权限仍属于准入工作线程。 */
    public DeploymentStandardInputs.GitSource gitSource(long archiveBytes) {
        var address = DeploymentGitRemote.parse(remote.getText().trim());
        DeploymentGitRef selected = switch ((String) referenceKind.getSelectedItem()) {
            case "DEFAULT" -> new DeploymentGitRef.DefaultBranch();
            case "BRANCH" -> new DeploymentGitRef.Branch(reference.getText().trim());
            case "TAG" -> new DeploymentGitRef.Tag(reference.getText().trim());
            case "COMMIT" -> new DeploymentGitRef.Commit(reference.getText().trim());
            default -> throw new IllegalArgumentException("Git reference missing");
        };
        var binary = Path.of(executable.getText().trim());
        if (!binary.isAbsolute()) throw new IllegalArgumentException("Native Git executable path must be absolute");
        return new DeploymentStandardInputs.GitSource(new DeploymentGitRequest(address, selected,
            address.host().map(Set::of).orElseGet(Set::of), archiveBytes, permitLocal.isSelected()), binary);
    }
}
