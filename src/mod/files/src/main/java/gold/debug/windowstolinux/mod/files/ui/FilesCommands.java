package gold.debug.windowstolinux.mod.files.ui;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import gold.debug.windowstolinux.appapi.file.model.FileInfo;
import gold.debug.windowstolinux.appapi.file.model.FilePath;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.mod.files.file.model.FilesClipboard;
import gold.debug.windowstolinux.mod.files.file.model.FilesDirectory;
import gold.debug.windowstolinux.mod.files.file.model.FilesOutcome;
import gold.debug.windowstolinux.mod.files.service.FilesService;

/** Translates shared view actions into original platform-backed service tasks. / 将共享视图动作转换为原平台服务任务。 */
final class FilesCommands {
    /** Exact root action owner. / 精确根动作所有者。 */
    private final FilesWorkspace workspace;

    /** Actual service boundary. / 实际服务边界。 */
    private final FilesService service;

    /** Original observer. / 原观测器。 */
    private final FilesTasks tasks;

    /** Original unsubmitted input owner. / 原未提交输入所有者。 */
    private final FilesDialogs dialogs;

    /** Current shared catalog. / 当前共享文案目录。 */
    private final FilesMessages messages;

    /** Workspace-private immutable clipboard metadata. / 工作区私有不可变剪贴元数据。 */
    private FilesClipboard clipboard;

    /** Captures only actual service and author view ownership. / 仅捕获实际服务及作者视图归属。
     * @param workspace root / 根
     * @param service service / 服务
     * @param tasks observer / 观测器
     * @param dialogs input owner / 输入所有者
     * @param messages catalog / 文案目录 */
    FilesCommands(FilesWorkspace workspace, FilesService service, FilesTasks tasks, FilesDialogs dialogs, FilesMessages messages) {
        this.workspace = workspace;
        this.service = service;
        this.tasks = tasks;
        this.dialogs = dialogs;
        this.messages = messages;
    }

    /** Checks only clipboard presence, without claiming source authorization. / 仅核对剪贴存在，不宣称来源授权。
     * @return whether immutable metadata exists / 是否存在不可变元数据 */
    boolean hasClipboard() {
        return clipboard != null;
    }

    /** Records selection without executing any cut effect. / 记录选择，不执行任何剪切副作用。
     * @param move whether future paste moves / 后续粘贴是否移动 */
    void clip(boolean move) {
        clipboard = new FilesClipboard(move ? FilesClipboard.Mode.MOVE : FilesClipboard.Mode.COPY, workspace.active().selected());
        workspace.update();
    }

    /** Pastes metadata into the current active directory through revalidation. / 将元数据经重新校验粘贴至当前活动目录。 */
    void paste() {
        if (clipboard != null) transfer(clipboard, workspace.active());
    }

    /** Uses the same transfer for both internal drag directions. / 两个内部拖拽方向使用相同传输。
     * @param items original selected observations / 原所选观测
     * @param target original target pane / 原目标栏 */
    void move(List<FileInfo> items, FilesPane target) {
        transfer(new FilesClipboard(FilesClipboard.Mode.MOVE, items), target);
    }

    /** Collects explicit link inclusion before submitting the original transfer. / 提交原传输前收集明确链接纳入。
     * @param value immutable observed sources / 不可变观测来源
     * @param target original target pane / 原目标栏 */
    private void transfer(FilesClipboard value, FilesPane target) {
        FilePath location = target.directory();
        if (!workspace.available() || location == null) return;
        if (value.sources().stream().anyMatch(info -> info.requested().server().isPresent() && location.server().isPresent()
            && !info.requested().server().equals(location.server()))) {
            workspace.problem(new IllegalArgumentException(messages.text("differentServers")));
            return;
        }
        Optional<List<FileInfo>> included = Optional.of(List.of());
        if (value.mode() == FilesClipboard.Mode.MOVE && value.sources().stream().anyMatch(info -> info.kind() != FileInfo.Kind.LINK))
            included = dialogs.links(workspace.observedLinks());
        if (included.isEmpty() || !workspace.available()) return;
        var links = included.orElseThrow();
        mutate(() -> service.transfer(value, location, links));
    }

    /** Creates an empty direct child with an explicitly entered full name. / 以明确输入完整名称创建空直接子项。
     * @param directory whether to create a directory / 是否创建目录 */
    void createItem(boolean directory) {
        var location = workspace.active().directory();
        dialogs.name(directory ? "folder" : "file").ifPresent(name -> {
            if (workspace.available()) mutate(() -> service.create(location, name, directory));
        });
    }

    /** Renames one original observation using the entire supplied name. / 使用完整所提供名称重命名一个原观测。 */
    void rename() {
        var selected = workspace.active().selected();
        if (selected.size() != 1) return;
        dialogs.name("rename").ifPresent(name -> {
            if (workspace.available()) mutate(() -> service.rename(selected.getFirst(), name));
        });
    }

    /** Confirms link-self and platform deletion semantics for actual selected items. / 对实际所选项确认链接自身及平台删除语义。 */
    void delete() {
        var selected = workspace.active().selected();
        if (!selected.isEmpty() && dialogs.delete(selected) && workspace.available())
            mutate(() -> service.delete(selected));
    }

    /** Submits actual work, preserving failure as observable feedback. / 提交实际工作，将失败保留为可见反馈。
     * @param submit original service task submission / 原服务任务提交 */
    private void mutate(Callable<TaskHandle<FilesOutcome>> submit) {
        tasks.mutate(submit);
        workspace.update();
    }

    /** Refreshes only the active observed directory. / 仅刷新活动的已观测目录。 */
    void refresh() {
        var pane = workspace.active();
        if (pane.directory() != null) browse(pane, pane.directory());
    }

    /** Refreshes actual retained directories after returned operation facts. / 操作事实返回后刷新实际保留目录。 */
    void refreshAll() {
        for (var pane : workspace.panes()) if (pane.directory() != null) browse(pane, pane.directory());
        workspace.update();
    }

    /** Opens the original task-bound local directory selector. / 打开原任务绑定的本机目录选择器。
     * @param pane explicit target pane / 明确目标栏 */
    void chooseLocal(FilesPane pane) {
        if (!workspace.available()) return;
        long revision = pane.request();
        tasks.read(() -> service.chooseLocal(messages.text("local")), value -> display(pane, revision, value));
    }

    /** Reads real saved servers before collecting an explicit location. / 收集明确位置前读取真实已保存服务器。
     * @param pane explicit target pane / 明确目标栏 */
    void chooseRemote(FilesPane pane) {
        if (!workspace.available()) return;
        long revision = pane.request();
        tasks.read(service::servers, values -> {
            if (!pane.current(revision)) return;
            try {
                dialogs.remote(values).ifPresent(path -> {
                    if (workspace.available()) browse(pane, path);
                });
            } catch (IllegalArgumentException failure) {
                workspace.problem(failure);
            }
        });
    }

    /** Submits the current path draft on its explicitly selected host. / 在明确所选主机提交当前路径草稿。
     * @param pane explicit pane / 明确栏 */
    void openPath(FilesPane pane) {
        if (!workspace.available() || pane.directory() == null) return;
        try {
            browse(pane, new FilePath(pane.directory().server(), pane.pathDraft()));
        } catch (IllegalArgumentException failure) {
            workspace.problem(failure);
        }
    }

    /** Browses an ordinary selected directory without following a link. / 浏览所选普通目录，不跟随链接。 */
    void openSelected() {
        var pane = workspace.active();
        var selected = pane.selected();
        if (workspace.available() && selected.size() == 1 && selected.getFirst().kind() == FileInfo.Kind.DIRECTORY)
            browse(pane, selected.getFirst().requested());
    }

    /** Submits one parent navigation on the same explicit host. / 在相同明确主机提交一次父目录导航。
     * @param pane target pane / 目标栏 */
    void parent(FilesPane pane) {
        if (!workspace.available() || pane.directory() == null) return;
        try {
            browse(pane, pane.directory().parent());
        } catch (IllegalArgumentException failure) {
            workspace.problem(failure);
        }
    }

    /** Browses using a new original authorization task and ordered callback generation. / 使用新原授权任务及有序回调代次浏览。
     * @param pane target pane / 目标栏
     * @param location explicit location metadata / 明确位置元数据 */
    private void browse(FilesPane pane, FilePath location) {
        long revision = pane.request();
        tasks.read(() -> service.browse(location), value -> display(pane, revision, value));
    }

    /** Applies only the latest original directory request for this pane. / 仅应用本栏最新原目录请求。
     * @param pane original pane / 原栏
     * @param revision original request revision / 原请求修订
     * @param directory actual result / 实际结果 */
    private void display(FilesPane pane, long revision, FilesDirectory directory) {
        if (pane.current(revision)) {
            pane.display(directory);
            workspace.update();
        }
    }

    /** Requests only explicitly chosen original link continuation. / 仅请求明确选择的原链接续作。 */
    void continueLinks() {
        workspace.continuation().ifPresent(operation -> mutate(() -> service.continueLinks(operation.id(), operation.source())));
    }

    /** Revokes private clipboard when this root is explicitly released. / 本根明确释放时撤销私有剪贴。 */
    void clear() {
        clipboard = null;
    }
}
