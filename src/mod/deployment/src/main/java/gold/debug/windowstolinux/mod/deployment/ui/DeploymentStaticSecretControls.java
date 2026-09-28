package gold.debug.windowstolinux.mod.deployment.ui;

import java.awt.GridLayout;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.*;

import gold.debug.windowstolinux.appapi.task.*;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretReference;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretRevision;
import gold.debug.windowstolinux.mod.deployment.service.core.model.DeploymentSecretSelection;
import gold.debug.windowstolinux.mod.deployment.service.standard.DeploymentStaticPageService;

/** Owns pre-publication secret creation and non-secret revision choices for one page. / 管理单页发布前秘密创建及不含明文的修订选项。 */
final class DeploymentStaticSecretControls {
    /** Owner page. / 所属页面。 */
    private final DeploymentStaticPage page;

    /** Original-task service. / 原任务服务。 */
    private final DeploymentStaticPageService service;

    /** Visible inputs and status. / 可见输入与状态。 */
    private final JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));

    /** Secret section title. / 秘密区域标题。 */
    private final JLabel title = new JLabel();

    /** Business identifier label. / 业务标识标签。 */
    private final JLabel identifierLabel = new JLabel();

    /** Immutable revision label. / 不可变修订标签。 */
    private final JLabel revisionLabel = new JLabel();

    /** Existing choice label. / 既有选项标签。 */
    private final JLabel choicesLabel = new JLabel();

    /** Safe local status. / 安全本地状态。 */
    private final JLabel state = new JLabel();

    /** Business identifier input. / 业务标识输入。 */
    private final JTextField identifier = new JTextField(24);

    /** Immutable revision input. / 不可变修订输入。 */
    private final JTextField revision = new JTextField("1", 8);

    /** Create action. / 创建操作。 */
    private final JButton create = new JButton();

    /** Reload action. / 刷新操作。 */
    private final JButton reload = new JButton();

    /** Purpose-backed business choices. / 有原用途的业务选项。 */
    private final JComboBox<DeploymentSecretSelection> listed = new JComboBox<>();

    /** Current bounded choices. / 当前有界选项。 */
    private volatile List<DeploymentSecretSelection> choices = List.of();

    /** Original read-only query. / 原只读查询。 */
    private volatile TaskHandle<List<DeploymentSecretSelection>> lookup;

    /** Original creation task. / 原创建任务。 */
    private volatile TaskHandle<DeploymentSecretRevision> creation;

    /** The user accepted plaintext creation before any later view revocation. / 用户已在后续视图撤销前接受明文创建。 */
    private volatile boolean confirmedCreation;

    /** View lifetime flag. / 视图寿命标记。 */
    private volatile boolean closed;

    /** Builds controls without obtaining any secret value. / 构建控件而不取得秘密值。
     * @param page owning view / 所属视图
     * @param service original task service / 原任务服务 */
    DeploymentStaticSecretControls(DeploymentStaticPage page, DeploymentStaticPageService service) {
        this.page = page;
        this.service = service;
        panel.add(title);
        panel.add(state);
        panel.add(identifierLabel);
        panel.add(identifier);
        panel.add(revisionLabel);
        panel.add(revision);
        panel.add(choicesLabel);
        panel.add(listed);
        panel.add(create);
        panel.add(reload);
        listed.setRenderer((list, value, index, selected, focus) -> {
            var label = new JLabel(value == null ? "" : format(value) + " (" + value.purpose() + ")");
            label.setOpaque(true);
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        create.addActionListener(ignored -> create());
        reload.addActionListener(ignored -> refresh(page.viewGeneration()));
        localize();
    }

    /** Returns the displayed creation section. / 返回可见创建区域。
     * @return owned panel / 所属面板 */
    JPanel panel() {
        return panel;
    }

    /** Returns only immutable business metadata. / 仅返回不可变业务元数据。
     * @return current choices / 当前选项 */
    List<DeploymentSecretSelection> choices() {
        return choices;
    }

    /** Replaces all locale-sensitive labels. / 更新全部随语言变化的标签。 */
    void localize() {
        title.setText(page.text("secretSection"));
        identifierLabel.setText(page.text("secretIdentifier"));
        revisionLabel.setText(page.text("secretRevision"));
        choicesLabel.setText(page.text("secretChoices"));
        create.setText(page.text("secretCreate"));
        reload.setText(page.text("secretReload"));
    }

    /** Enables creation only before a publication task owns the selected server. / 仅在发布任务占用选定服务器之前启用创建。 */
    void updateAvailability() {
        create.setEnabled(page.canCreateSecret() && creation == null);
        reload.setEnabled(!closed && page.active(page.viewGeneration()));
    }

    /** Loads existing purpose-backed choices before publication admission. / 在发布准入前读取有原用途的既有选项。
     * @param view current owner generation / 当前所属代次 */
    void refresh(long view) {
        if (closed || !page.active(view))
            return;
        try {
            page.execute(() -> {
                if (closed || !page.active(view))
                    return;
                try {
                    var unresolved = service.secretTasks().stream().filter(item -> item.state() == TaskState.UNKNOWN)
                            .map(TaskSnapshot::id).findFirst();
                    var handle = service.secretChoices();
                    lookup = handle;
                    if (!page.active(view))
                        cancel(handle);
                    handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                        if (lookup == handle)
                            lookup = null;
                        if (!page.active(view))
                            return;
                        if (failure != null || result.outcome() != Outcome.SUCCEEDED) {
                            state.setText(page.text("secretListError"));
                            return;
                        }
                        choices = result.value().orElseThrow();
                        listed.removeAllItems();
                        choices.forEach(listed::addItem);
                        state.setText(unresolved.map(id -> page.text("secretUnknown") + " " + id)
                                .orElseGet(() -> page.text("secretReady")));
                    }));
                } catch (Exception failure) {
                    SwingUtilities.invokeLater(() -> {
                        if (page.active(view))
                            state.setText(page.text("secretListError"));
                    });
                }
            });
        } catch (RejectedExecutionException unavailable) {
            // A closing view cannot own another read-only lookup.
        }
    }

    /** Starts a new immutable revision while no publication review owns the server. / 在发布审阅尚未占用服务器时启动新不可变修订。 */
    private void create() {
        if (closed || creation != null || !page.canCreateSecret())
            return;
        var server = page.selectedServer();
        if (server.isEmpty()) {
            state.setText(page.text("secretNeedServer"));
            return;
        }
        DeploymentSecretReference reference;
        try {
            reference = new DeploymentSecretReference(identifier.getText().trim(),
                    Long.parseLong(revision.getText().trim()));
        } catch (RuntimeException invalid) {
            state.setText(page.text("secretInvalidReference"));
            return;
        }
        long view = page.viewGeneration();
        confirmedCreation = false;
        page.secretBusy(true);
        create.setEnabled(false);
        try {
            page.execute(() -> {
                if (!page.active(view)) {
                    finish(view, page.text("secretCancelled"));
                    return;
                }
                try {
                    var once = new AtomicBoolean();
                    var handle = service.createSecret(server.orElseThrow(), reference, task -> {
                        if (!once.compareAndSet(false, true))
                            throw new IllegalStateException("Secret input was already requested");
                        return password(view, task);
                    });
                    creation = handle;
                    if (!page.active(view) && !confirmedCreation)
                        cancel(handle);
                    Thread.ofVirtual().name("deployment-secret-observation")
                            .start(() -> observe(handle, reference, view));
                } catch (Exception unavailable) {
                    finish(view, page.text("secretCreateError"));
                }
            });
        } catch (RejectedExecutionException unavailable) {
            finish(view, page.text("secretCancelled"));
        }
    }

    /** Requests a one-shot password on the EDT only after original task admission. / 仅在原任务准入后于 EDT 请求一次密码。
     * @param view original view generation / 原视图代次
     * @param task original executing task / 原执行任务
     * @return newly owned UTF-8 bytes / 新所属 UTF-8 字节
     * @throws Exception if the view or input is unavailable / 视图或输入不可用时 */
    private byte[] password(long view, TaskContext task) throws Exception {
        var field = page.onEdt(JPasswordField::new);
        var form = page.onEdt(() -> {
            var value = new JPanel(new GridLayout(0, 1));
            value.add(new JLabel(page.text("secretPassword")));
            value.add(field);
            return value;
        });
        boolean accepted = page.approveSecret(view, form, page.text("secretPasswordTitle"), task,
                () -> confirmedCreation = true);
        char[] chars = capturePassword(field);
        try {
            if (!accepted)
                throw new CancellationException("Secret input was cancelled");
            if (chars.length == 0 || chars.length > 262_144)
                throw new IllegalArgumentException("Secret input size is invalid");
            var buffer = ByteBuffer.allocate(chars.length * 4);
            try {
                var encoder = StandardCharsets.UTF_8.newEncoder();
                var encoded = encoder.encode(CharBuffer.wrap(chars), buffer, true);
                if (encoded.isError())
                    encoded.throwException();
                var flushed = encoder.flush(buffer);
                if (flushed.isError())
                    flushed.throwException();
                if (buffer.position() == 0 || buffer.position() > 1024 * 1024)
                    throw new IllegalArgumentException("Secret input size is invalid");
                return Arrays.copyOf(buffer.array(), buffer.position());
            } finally {
                Arrays.fill(buffer.array(), (byte) 0);
            }
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /** Captures and clears the Swing field even when the waiting worker is interrupted. / 即使等待线程中断也取得并清空 Swing 字段。
     * @param field current password control / 当前密码控件
     * @return owned characters / 所属字符
     * @throws InterruptedException if the original worker was interrupted / 原工作线程中断时
     * @throws ExecutionException if the EDT capture failed / EDT 获取失败时 */
    private static char[] capturePassword(JPasswordField field) throws InterruptedException, ExecutionException {
        var captured = new CompletableFuture<char[]>();
        var abandoned = new AtomicBoolean();
        SwingUtilities.invokeLater(() -> {
            char[] value = null;
            try {
                value = field.getPassword();
                if (abandoned.get())
                    Arrays.fill(value, '\0');
                else {
                    captured.complete(value);
                    value = null;
                }
            } catch (RuntimeException failure) {
                captured.completeExceptionally(failure);
            } finally {
                if (value != null)
                    Arrays.fill(value, '\0');
                field.setText("");
            }
        });
        try {
            return captured.get();
        } catch (InterruptedException interrupted) {
            abandoned.set(true);
            captured.thenAccept(value -> Arrays.fill(value, '\0'));
            throw interrupted;
        }
    }

    /** Observes only original durable task state; UNKNOWN never triggers a second create. / 仅观察原持久任务状态；UNKNOWN 绝不触发第二次创建。
     * @param handle original secret task / 原秘密任务
     * @param reference original business revision / 原业务修订
     * @param view original view generation / 原视图代次 */
    private void observe(TaskHandle<DeploymentSecretRevision> handle, DeploymentSecretReference reference, long view) {
        try {
            while (!closed) {
                var snapshot = handle.snapshot();
                if (snapshot.state() == TaskState.UNKNOWN || snapshot.state() == TaskState.ENDED) {
                    SwingUtilities.invokeLater(() -> {
                        if (snapshot.state() == TaskState.UNKNOWN)
                            finish(view, page.text("secretUnknown") + " " + handle.id());
                        else if (snapshot.outcome().orElse(Outcome.UNKNOWN) == Outcome.SUCCEEDED) {
                            var selected = new DeploymentSecretSelection(reference, "deployment-application");
                            if (choices.stream().noneMatch(item -> item.reference().equals(reference))) {
                                var updated = new java.util.ArrayList<>(choices);
                                updated.add(selected);
                                choices = List.copyOf(updated);
                                listed.addItem(selected);
                            }
                            finish(view, page.text("secretCreated") + " " + reference.identifier() + ":"
                                    + reference.revision());
                            if (page.active(view))
                                refresh(view);
                        } else
                            finish(view,
                                    page.text("secretCreateError") + " " + snapshot.outcome().orElse(Outcome.UNKNOWN));
                    });
                    return;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            SwingUtilities.invokeLater(() -> finish(view, page.text("secretUnknown") + " " + handle.id()));
        }
    }

    /** Releases only the current view's unanswered secret work. / 仅释放当前视图未答的秘密操作。 */
    void revoke() {
        var pending = lookup;
        if (pending != null)
            cancel(pending);
        var active = creation;
        if (active != null && !confirmedCreation)
            cancel(active);
    }

    /** Prevents later view callbacks. / 阻止后续视图回调。 */
    void close() {
        closed = true;
    }

    /** Restores controls after an observed terminal or UNKNOWN state. / 观察到终态或 UNKNOWN 后恢复控件。
     * @param view original view / 原视图
     * @param message safe localized status / 安全本地化状态 */
    private void finish(long view, String message) {
        creation = null;
        confirmedCreation = false;
        page.secretBusy(false);
        SwingUtilities.invokeLater(() -> {
            if (!closed)
                updateAvailability();
            if (page.active(view)) {
                state.setText(message);
            }
        });
    }

    /** Requests original cooperative cancellation. / 请求原任务协作取消。
     * @param handle original task / 原任务 */
    private static void cancel(TaskHandle<?> handle) {
        try {
            handle.requestCancel();
        } catch (ApiException unavailable) {
            // Original task and recovery retain the uncertainty.
        }
    }

    /** Formats only the business reference, never the opaque credential. / 仅格式化业务引用，绝不格式化不透明凭据。
     * @param selection purpose-backed choice / 有原用途的选项
     * @return identifier and revision / 标识与修订 */
    static String format(DeploymentSecretSelection selection) {
        return selection.reference().identifier() + ":" + selection.reference().revision();
    }
}
