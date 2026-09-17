package gold.debug.windowstolinux.app.ui.setting;

import gold.debug.windowstolinux.app.ui.ai.AiPage;
import gold.debug.windowstolinux.app.ui.ai.AiProviderDialog;
import gold.debug.windowstolinux.app.ui.component.DesktopComponentFactory;
import gold.debug.windowstolinux.app.ui.deployment.single.DeploymentInputDialog;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.managed.ApplicationPresentationDialog;
import gold.debug.windowstolinux.app.ui.managed.ApplicationScanDialog;
import gold.debug.windowstolinux.app.ui.server.ServerProfileDialog;
import gold.debug.windowstolinux.app.ui.server.ServerSelectionPane;
import gold.debug.windowstolinux.app.ui.server.ServerTrustPrompt;
import gold.debug.windowstolinux.shared.model.deployment.DeploymentInputField;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Class-only catalog of live page links and isolated, reusable dialog previews. / 仅 Class 模式使用的主页面导航与隔离弹窗预览目录。 */
public final class UiDebugDialog extends JDialog {
    record Preview(String id, String label, Runnable open) {
        @Override public String toString() { return label; }
    }

    private final DesktopComponentFactory components;
    private final PageMessagePresenter messages;
    private final UiPreviewService preview;
    private final DeploymentInputDialog input;
    private final List<Preview> previews = new ArrayList<>();

    /** Uses the current theme and language without acquiring production service access. / 使用当前主题与语言，不获取真实业务服务。 */
    public UiDebugDialog(Window owner, DesktopComponentFactory components, MessageCatalog catalog, Consumer<String> navigate) {
        super(owner, catalog.text("settings.debug.open"), ModalityType.MODELESS);
        this.components = components;
        this.messages = new PageMessagePresenter(catalog);
        preview = new UiPreviewService(messages);
        input = new DeploymentInputDialog(this, preview.facade, components, messages, () -> new char[0]);
        JPanel body = components.pagePanel();
        body.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        JPanel heading = components.transparent(new BorderLayout(0, 12));
        heading.add(components.sectionHeading(messages.text("debug.pages"), messages.text("debug.pages.hint")), BorderLayout.NORTH);
        JPanel pages = components.transparent(new GridLayout(0, 3, 8, 8));
        for (String page : List.of("deployment", "components", "servers", "applications", "backup", "ai", "settings")) {
            JButton button = components.secondaryButton(messages.text("nav." + page));
            button.addActionListener(event -> navigate.accept(page)); pages.add(button);
        }
        heading.add(pages); body.add(heading, BorderLayout.NORTH);
        registerPreviews();
        JList<Preview> list = new JList<>(previews.toArray(Preview[]::new));
        list.setName("debug.previews"); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(32); list.setSelectedIndex(0);
        list.getAccessibleContext().setAccessibleName(messages.text("debug.dialogs"));
        JPanel content = components.transparent(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(18, 0, 0, 0));
        content.add(components.sectionHeading(messages.text("debug.dialogs"), messages.text("debug.dialogs.hint")), BorderLayout.NORTH);
        content.add(new JScrollPane(list)); body.add(content);
        JButton open = components.primaryButton(messages.text("debug.open"));
        open.addActionListener(event -> { if (list.getSelectedValue() != null) list.getSelectedValue().open().run(); });
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) open.doClick(); }
        });
        JPanel actions = components.transparent(new FlowLayout(FlowLayout.RIGHT)); actions.add(open); body.add(actions, BorderLayout.SOUTH);
        setContentPane(body); getRootPane().setDefaultButton(open); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(760, 720); setMinimumSize(new Dimension(660, 540)); setLocationRelativeTo(owner);
    }

    private void registerPreviews() {
        add("server.add", "auto.addServer", () -> show(new ServerProfileDialog(this, preview.facade, components, messages, null, value -> { })));
        add("server.edit", "server.edit", () -> show(new ServerProfileDialog(this, preview.facade, components, messages, preview.server, value -> { })));
        add("server.choose", "server.choose", () -> new ServerSelectionPane(preview.facade, components, messages, value -> { }).open(this));
        add("ai.add", "ai.models.add", () -> show(new AiProviderDialog(this, preview.facade, components, messages, null, () -> { })));
        add("ai.edit", "debug.ai.edit", () -> show(new AiProviderDialog(this, preview.facade, components, messages, preview.model, () -> { })));
        add("ai.setup", "auto.ai.setup", () -> JOptionPane.showMessageDialog(this,
                new AiPage(preview.facade, Optional::empty, components, messages).panel(), messages.text("auto.ai.setup"), JOptionPane.PLAIN_MESSAGE));
        add("apps.scan", "debug.apps.scan", () -> show(new ApplicationScanDialog(this, preview.facade, components, messages, value -> { })));
        add("apps.edit", "debug.apps.edit", () -> show(new ApplicationPresentationDialog(this, preview.facade, components, messages, preview.application, () -> { })));
        add("inputs", "auto.input.title", () -> input.requestInputs(List.of(
                new DeploymentInputField("web/port", "auto.field.port", "auto.help.port", "8080", List.of()),
                new DeploymentInputField("web/primary", "auto.field.primary", "auto.help.primary", "app.jar", List.of()),
                new DeploymentInputField("db/main/engine", "db.field.engine", "db.help.engine", "POSTGRESQL", List.of("POSTGRESQL", "MYSQL", "REDIS")),
                new DeploymentInputField("db/main/database", "db.field.database", "db.help.database", "preview_app", List.of()),
                new DeploymentInputField("db/main/instance", "db.field.instance", "db.help.instance", "", List.of("postgresql-16 : 5432", "postgresql-17 : 5433")),
                new DeploymentInputField("db/main/initialize", "db.field.initialize", "db.help.initialize", "", List.of("", "schema.sql")))));
        for (String key : List.of("field.masterPassword", "db.adminPassword", "db.applicationPassword")) {
            add(key, key, () -> {
                try { Arrays.fill(input.requestSecret(key), '\0'); }
                catch (CancellationException cancelled) { /* Closing a preview has no continuation. / 关闭预览后没有后续操作。 */ }
            });
        }
        add("secret.value", "secret.value.title", () -> {
            JPasswordField field = new JPasswordField(26);
            try { JOptionPane.showConfirmDialog(this, field, messages.text("secret.value.title"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE); }
            finally { field.setText(""); }
        });
        add("fingerprint", "fingerprint.confirm.title", () -> ServerTrustPrompt.confirm(this, messages, "SHA256:ExamplePreviewFingerprintOnly0123456789abcdef"));
        add("environment", "environment.confirm.title", () -> input.confirm("environment.confirm", Map.of(
                "serverId", preview.server.id(), "host", preview.server.host(), "port", 22, "username", "root")));
        for (boolean reboot : List.of(true, false)) {
            add("system." + reboot, reboot ? "debug.system.reboot" : "debug.system.finish", () -> input.confirm("environment.system.confirm",
                    Map.of("serverId", preview.server.id(), "host", preview.server.host(), "security", "ENFORCING", "reboot", reboot)));
        }
        add("db.replace", "debug.db.replace", () -> input.confirmDatabaseReplacement(Map.of("server", preview.server.id(),
                "instance", "postgresql-16/main", "current", "16", "required", ">=17", "target", "17", "data", "/var/lib/postgresql/16/main")));
        confirmation("db.conflict", "debug.db.conflict", Map.of("conflicts", List.of("data_without_package", "version_unknown")));
        confirmation("db.versionUnavailable", "debug.db.version", Map.of("required", "PostgreSQL >=17"));
        confirmation("db.multipleReplacement", "debug.db.multiple", Map.of("instance", "postgresql-16/main", "required", ">=17"));
        confirmation("db.restoreConfirmed", "debug.db.restore", Map.of("database", "PostgreSQL"));
        confirmation("db.existingSchema", "debug.db.schema", Map.of("database", "preview_app", "files", "schema.sql, migration.sql"));
        confirmation("db.nativeEndpoint", "debug.db.endpoint", Map.of());
        for (String risk : List.of("root", "experimental", "docker"))
            confirmation("auto.risk." + risk, "debug.risk." + risk, Map.of("component", "preview-web"));
        confirmMessage("component.review", "component.review.title", "component.review.body", Map.of("application", "preview-app",
                "waves", "[database] → [api, web]", "stop", "web → api → database", "start", "database → api → web",
                "supports", "api: STABLE; web: EXPERIMENTAL", "databases", "PostgreSQL"));
        confirmMessage("component.docker", "deployment.dockerRisk.title", "component.dockerRisk", Map.of("components", "api, web"));
        confirmMessage("component.experimental", "component.experimentalRisk.title", "component.experimentalRisk", Map.of("components", "web"));
        confirmMessage("backup.migration", "backup.migration.confirm.title", "backup.migration.confirm", Map.of());
        add("backup.create", "debug.backup.create", () -> confirmMessage("backup.operation.confirm.title", "backup.create.confirm", Map.of("application", "preview-app")));
        add("backup.restore", "debug.backup.restore", () -> confirmMessage("backup.operation.confirm.title", "backup.restore.confirm", Map.of("archive", "preview-backup.wtl", "target", preview.server.id())));
        add("error", "debug.error", () -> JOptionPane.showMessageDialog(this, messages.text("failure.presentation", Map.of(
                "message", messages.text("diagnostic.unexpected"), "code", "UI_PREVIEW", "operationId", "preview-only",
                "summary", messages.text("debug.blocked"), "recovery", messages.text("failure.recovery.not_required"),
                "report", messages.text("failure.report.unavailable"))), messages.text("app.name"), JOptionPane.ERROR_MESSAGE));
        add("appearance.busy", "debug.appearance.busy", () -> JOptionPane.showMessageDialog(this, messages.text("auto.appearance.busy")));
        add("help", "debug.help", () -> JOptionPane.showMessageDialog(this, messages.text("auto.help.port")));
        add("file.directory", "debug.file.directory", () -> { JFileChooser chooser = new JFileChooser(); chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY); chooser.showOpenDialog(this); });
        add("file.open", "debug.file.open", () -> new JFileChooser().showOpenDialog(this));
        add("file.save", "debug.file.save", () -> new JFileChooser().showSaveDialog(this));
    }

    private void add(String id, String key, Runnable open) { previews.add(new Preview(id, messages.text(key), open)); }
    private void confirmation(String key, String label, Map<String, ?> details) { add(key, label, () -> input.confirm(key, details)); }
    private void confirmMessage(String id, String title, String key, Map<String, ?> details) { add(id, title, () -> confirmMessage(title, key, details)); }
    private void confirmMessage(String title, String key, Map<String, ?> details) {
        JOptionPane.showConfirmDialog(this, messages.text(key, details), messages.text(title), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
    }
    private void show(JDialog dialog) { dialog.setVisible(true); }
}
