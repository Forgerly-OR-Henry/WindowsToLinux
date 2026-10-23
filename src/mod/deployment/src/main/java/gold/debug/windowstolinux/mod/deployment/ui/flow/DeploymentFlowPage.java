package gold.debug.windowstolinux.mod.deployment.ui.flow;

import java.awt.*;
import java.awt.datatransfer.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.*;
import javax.swing.event.*;

import gold.debug.windowstolinux.appapi.server.model.ServerInfo;
import gold.debug.windowstolinux.appapi.task.result.TaskResult;
import gold.debug.windowstolinux.appapi.task.run.TaskHandle;
import gold.debug.windowstolinux.appapi.ui.page.*;
import gold.debug.windowstolinux.mod.deployment.db.setting.DeploymentPreferenceStore.Selection;
import gold.debug.windowstolinux.mod.deployment.file.git.DeploymentGitRemote;
import gold.debug.windowstolinux.mod.deployment.service.agent.DeploymentAgentPageService;
import gold.debug.windowstolinux.mod.deployment.service.core.DeploymentApprovalPolicyState;
import gold.debug.windowstolinux.mod.deployment.service.standard.shared.DeploymentStandardPageService;
import gold.debug.windowstolinux.mod.deployment.ui.agent.DeploymentAgentPage;
import gold.debug.windowstolinux.mod.deployment.ui.preference.DeploymentPreferenceSelection;
import gold.debug.windowstolinux.mod.deployment.ui.source.DeploymentLocalSourceControls;
import gold.debug.windowstolinux.mod.deployment.ui.standard.DeploymentStandardPage;
import gold.debug.windowstolinux.mod.deployment.ui.style.DeploymentPageStyle;

/** One deployment flow owns the only source/target draft and routes to the original engines. / 唯一部署流程持有唯一来源/目标草稿，并路由至原执行器。 */
public final class DeploymentFlowPage implements ModPage {
    private final DeploymentStandardPageService standardService;

    private final DeploymentAgentPageService agentService;

    private final DeploymentFlowDraft draft = new DeploymentFlowDraft();

    private final ExecutorService actions = Executors.newSingleThreadExecutor(work -> {
        var thread = new Thread(work, "deployment-unified-flow");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicLong generation = new AtomicLong();

    private final JPanel root = new JPanel(new BorderLayout()), sourceContent = new JPanel(new BorderLayout(0, 12));

    private final JPanel sourceRoutes = new JPanel(new CardLayout()), results = new JPanel(new CardLayout());

    private final JPanel agentAdvanced = DeploymentPageStyle.form();

    private final JButton mode = new JButton(), approval = new JButton(), models = new JButton(), start = new JButton();

    private final JButton server = new JButton(), clearServer = new JButton(), refreshServers = new JButton(), manageServers = new JButton();

    private final JButton clearGit = new JButton(), sourceAccept = new JButton(), retryPreferences = new JButton();

    private final JTextField sourceInput = new JTextField();

    private final JLabel gitLocation = new JLabel(), problem = new JLabel(), policyStatus = new JLabel();

    private DeploymentStandardPage standard;

    private DeploymentAgentPage agent;

    private DeploymentPageStyle.Advanced advanced;

    private JDialog serverPicker;

    private JScrollPane selectionScroll;

    private JComponent projectSelection;

    private JPopupMenu modePopup, approvalPopup;

    private ResourceBundle messages;

    private UiEnvironment environment;

    private javax.swing.Timer observer;

    private TaskHandle<?> preferenceLookup;

    private DeploymentApprovalPolicyState policy;

    private UUID observedTask;

    private String observedMode = "ASSISTED", navigationMode;

    private String capturedPolicy = "AUTOMATIC";

    private Boolean advancedUnavailable;

    private boolean closed, loading, created, requestingPolicy;

    public DeploymentFlowPage(DeploymentStandardPageService standard, DeploymentAgentPageService agent) {
        standardService = Objects.requireNonNull(standard);
        agentService = Objects.requireNonNull(agent);
    }

    @Override
    public JComponent create(UiEnvironment environment) {
        requireEdt();
        if (created || closed) throw new IllegalStateException("Flow page already used");
        created = true;
        this.environment = environment;
        catalog();
        root.setName("deployment.flow");
        root.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        draft.preferences = new DeploymentPreferenceSelection(actions, standardService::savePreferences,
            () -> draft.mode, selection -> {
            draft.mode = selection.mode();
            if (standard != null) selectSurface();
        }, this::showProblem, this::refreshControls);
        draft.local = new DeploymentLocalSourceControls(standardService.sources().selection(), this::text, actions,
            generation::get, view -> !closed && !this.environment.locked() && generation.get() == view,
            value -> {
                value.ifPresent(location -> {
                    if (draft.name.getText().isBlank()) draft.name.setText(location.displayName());
                });
                refreshControls();
            }, this::showProblem, this::refreshControls);
        standard = new DeploymentStandardPage(standardService, draft);
        agent = new DeploymentAgentPage(agentService, draft);
        standard.create(environment);
        agent.create(environment);
        draft.servers.setName("deployment.flow.serverSelection");
        draft.name.setName("deployment.sourceName");
        results.add(standard.flowResults(), "STANDARD");
        results.add(agent.flowResults(), "AGENT");
        buildSource();
        var left = new JPanel(new BorderLayout(0, 16));
        left.setOpaque(false);
        var selections = new SelectionRegion();
        selections.setOpaque(false);
        var constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.weightx = 1;
        constraints.weighty = 0.56;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.insets = new Insets(0, 0, 16, 0);
        selections.add(DeploymentPageStyle.outer(sourceContent), constraints);
        constraints.gridy = 1;
        constraints.weighty = 0.44;
        constraints.insets = new Insets(0, 0, 0, 0);
        selections.add(buildTarget(), constraints);
        selectionScroll = DeploymentPageStyle.scroll(selections);
        selectionScroll.setName("deployment.flow.selections.scroll");
        left.add(selectionScroll, BorderLayout.CENTER);
        left.add(buildActions(), BorderLayout.SOUTH);
        var right = new JPanel(new BorderLayout(0, 12));
        right.setOpaque(false);
        right.add(heading("style.results", "log"), BorderLayout.NORTH);
        var logWindow = DeploymentPageStyle.logSurface(results);
        logWindow.setName("deployment.flow.logWindow");
        right.add(logWindow, BorderLayout.CENTER);
        var taskFooter = new JPanel(new BorderLayout(8, 6));
        taskFooter.setOpaque(false);
        var reload = new JButton(text("refresh"));
        reload.setName("deployment.flow.refreshTasks");
        reload.addActionListener(ignored -> {
            standard.flowRefresh();
            agent.flowRefresh();
        });
        taskFooter.add(reload, BorderLayout.EAST);
        retryPreferences.setName("deployment.flow.retryPreferences");
        retryPreferences.addActionListener(ignored -> loadPreferences());
        taskFooter.add(retryPreferences, BorderLayout.NORTH);
        var diagnostics = new JPanel(new GridLayout(0, 1));
        diagnostics.setOpaque(false);
        diagnostics.add(policyStatus);
        diagnostics.add(problem);
        taskFooter.add(diagnostics, BorderLayout.CENTER);
        right.add(taskFooter, BorderLayout.SOUTH);
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, DeploymentPageStyle.outer(right)) {
            private int measuredWidth = -1, measuredHeight = -1;

            @Override
            public void doLayout() {
                if (measuredWidth != getWidth() || measuredHeight != getHeight()) {
                    measuredWidth = getWidth();
                    measuredHeight = getHeight();
                    setDividerLocation(0.60);
                }
                super.doLayout();
            }
        };
        split.setName("deployment.flow.columns");
        split.setResizeWeight(0.60);
        split.setDividerSize(16);
        split.setBorder(BorderFactory.createEmptyBorder());
        left.setMinimumSize(new Dimension(0, 0));
        split.getRightComponent().setMinimumSize(new Dimension(0, 0));
        root.add(split, BorderLayout.CENTER);
        SwingUtilities.invokeLater(() -> {
            if (!closed) split.setDividerLocation(0.60);
        });
        observer = new javax.swing.Timer(150, ignored -> observe());
        observer.start();
        selectSurface();
        localize();
        loadPreferences();
        standard.flowRefreshServers();
        return root;
    }

    private JComponent heading(String key, String icon) {
        var line = new JPanel(new BorderLayout(8, 0));
        line.setOpaque(false);
        line.add(new JLabel(DeploymentPageStyle.icon(icon, 26)), BorderLayout.WEST);
        var title = DeploymentPageStyle.title(key, 20);
        title.setName("deployment.flow.title." + key);
        line.add(title, BorderLayout.CENTER);
        return line;
    }

    private void buildSource() {
        sourceContent.setOpaque(false);
        sourceContent.setName("deployment.flow.source");
        var top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        top.setOpaque(false);
        top.add(heading("flow.source", "folder"));
        projectSelection = standard.flowProjects();
        top.add(projectSelection);
        sourceContent.add(top, BorderLayout.NORTH);
        draft.local.tile(DeploymentPageStyle.icon("folder", 36));
        sourceRoutes.setOpaque(false);
        sourceRoutes.add(draft.local.panel(), "LOCAL");
        var selectedGit = new JPanel(new BorderLayout(10, 12));
        selectedGit.setOpaque(false);
        var gitIcon = new JLabel(DeploymentPageStyle.icon("folder", 36), SwingConstants.CENTER);
        selectedGit.add(gitIcon, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(10, 0));
        footer.setOpaque(false);
        footer.add(gitLocation, BorderLayout.CENTER);
        clearGit.setName("deployment.flow.sourceClear");
        clearGit.addActionListener(ignored -> {
            if (draft.frozen) return;
            draft.remote.setText("");
            draft.sourceMode.setSelectedItem(DeploymentFlowDraft.SourceMode.LOCAL);
            refreshControls();
        });
        footer.add(clearGit, BorderLayout.EAST);
        selectedGit.add(footer, BorderLayout.SOUTH);
        sourceRoutes.add(selectedGit, "GIT");
        sourceContent.add(sourceRoutes, BorderLayout.CENTER);
        sourceInput.setName("deployment.flow.sourceInput");
        sourceInput.addActionListener(ignored -> acceptSource(sourceInput.getText()));
        var input = new JPanel(new BorderLayout(10, 0));
        input.setOpaque(false);
        input.add(sourceInput, BorderLayout.CENTER);
        sourceAccept.setName("deployment.flow.sourceAccept");
        sourceAccept.addActionListener(ignored -> acceptSource(sourceInput.getText()));
        input.add(sourceAccept, BorderLayout.EAST);
        sourceContent.add(input, BorderLayout.SOUTH);
        var transfer = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return !closed && !DeploymentFlowPage.this.environment.locked() && !draft.frozen && !draft.ready()
                    && (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || support.isDataFlavorSupported(DataFlavor.stringFlavor));
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
                        return draft.local.importSelection(support.getTransferable());
                    return acceptSource((String) support.getTransferable().getTransferData(DataFlavor.stringFlavor));
                } catch (Exception invalid) {
                    showProblem(text("source.invalid"));
                    return false;
                }
            }
        };
        sourceContent.setTransferHandler(transfer);
        sourceInput.setTransferHandler(transfer);
        draft.local.tileTransfer(transfer);
        draft.sourceMode.addActionListener(ignored -> refreshControls());
    }

    private boolean acceptSource(String candidate) {
        if (closed || environment.locked() || draft.frozen || draft.ready() || draft.local.busy()) return false;
        String value = candidate.trim();
        if (value.isBlank() || value.contains("\n") || value.contains("\r")) return false;
        try {
            if (value.startsWith("https://") || value.startsWith("http://") || value.startsWith("ssh://") || value.startsWith("git://") || value.startsWith("git@")) {
                DeploymentGitRemote.parse(value);
                draft.remote.setText(value);
                draft.sourceMode.setSelectedItem(DeploymentFlowDraft.SourceMode.GIT);
                if (draft.name.getText().isBlank()) {
                    String name = value.substring(value.lastIndexOf('/') + 1).replaceFirst("\\.git$", "");
                    if (!name.isBlank()) draft.name.setText(name);
                }
            } else if (!draft.local.importSelection(new StringSelection(value))) return false;
            sourceInput.setText("");
            refreshControls();
            return true;
        } catch (RuntimeException invalid) {
            showProblem(text("source.invalid"));
            return false;
        }
    }

    private JComponent buildTarget() {
        var content = new JPanel(new BorderLayout(0, 12));
        content.setOpaque(false);
        content.add(heading("flow.target", "server"), BorderLayout.NORTH);
        server.setName("deployment.flow.chooseServer");
        server.setIcon(DeploymentPageStyle.icon("server", 36));
        server.setVerticalTextPosition(SwingConstants.BOTTOM);
        server.setHorizontalTextPosition(SwingConstants.CENTER);
        server.setPreferredSize(new Dimension(240, 100));
        server.addActionListener(ignored -> chooseServer());
        content.add(server, BorderLayout.CENTER);
        var footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        var links = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        links.setOpaque(false);
        refreshServers.addActionListener(ignored -> standard.flowRefreshServers());
        refreshServers.setName("deployment.server.refresh");
        manageServers.setName("deployment.platform.servers");
        manageServers.addActionListener(ignored -> openPlatform(PlatformPageKind.SERVERS));
        links.add(refreshServers);
        links.add(manageServers);
        footer.add(links, BorderLayout.WEST);
        clearServer.setName("deployment.flow.clearServer");
        clearServer.addActionListener(ignored -> {
            if (!draft.frozen) draft.servers.setSelectedItem(null);
        });
        footer.add(clearServer, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
        return DeploymentPageStyle.outer(content);
    }

    private JComponent buildActions() {
        var bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        mode.setName("deployment.flow.mode");
        approval.setName("deployment.flow.approval");
        mode.setIcon(DeploymentPageStyle.icon("settings", 20));
        approval.setIcon(DeploymentPageStyle.icon("shield", 20));
        mode.addActionListener(ignored -> showModes());
        approval.addActionListener(ignored -> showApprovals());
        bar.add(mode);
        bar.add(approval);
        var advancedContent = DeploymentPageStyle.form();
        advancedContent.add(standard.flowAdvanced());
        agentAdvanced.add(agent.flowAdvanced());
        advancedContent.add(agentAdvanced);
        advanced = new DeploymentPageStyle.Advanced(root, advancedContent);
        advanced.button().setIcon(DeploymentPageStyle.icon("settings", 20));
        models.addActionListener(ignored -> openPlatform(PlatformPageKind.AI_MODELS));
        models.setName("deployment.platform.models");
        start.setName("deployment.flow.start");
        start.putClientProperty("deployment.style.primary", true);
        start.addActionListener(ignored -> submit());
        bar.add(models);
        bar.add(advanced.button());
        bar.add(start);
        return DeploymentPageStyle.outer(bar);
    }

    private void showModes() {
        if (!mode.isEnabled()) return;
        modePopup = new JPopupMenu();
        var body = new JPanel(new BorderLayout(8, 10));
        body.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        var heading = new JLabel(text("flow.mode." + draft.mode));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        body.add(heading, BorderLayout.NORTH);
        var choices = new JPanel(new GridLayout(1, 3, 4, 0));
        var group = new ButtonGroup();
        for (String value : java.util.List.of("STATIC", "ASSISTED", "AGENT")) {
            var button = new JToggleButton(text("flow.mode." + value), draft.mode.equals(value));
            button.setName("deployment.flow.mode." + value);
            group.add(button);
            choices.add(button);
            button.addActionListener(ignored -> {
                if (!mode.isEnabled()) return;
                navigationMode = null;
                draft.mode = value;
                draft.preferences.changed();
                selectSurface();
                refreshControls();
            });
        }
        body.add(choices, BorderLayout.CENTER);
        modePopup.add(body);
        DeploymentPageStyle.refresh(body, messages);
        modePopup.show(mode, 0, -modePopup.getPreferredSize().height - 6);
    }

    private void showApprovals() {
        if (!approval.isEnabled()) return;
        approvalPopup = new JPopupMenu();
        var group = new ButtonGroup();
        String active = draft.frozen && policy != null ? policy.mode() : draft.preferences.approval();
        for (String value : java.util.List.of("MANUAL_REVIEW", "AUTOMATIC", "FULL_CONTROL")) {
            var item = new JRadioButtonMenuItem(text("approval." + value), active.equals(value));
            item.setName("deployment.flow.approval." + value);
            group.add(item);
            approvalPopup.add(item);
            item.addActionListener(ignored -> {
                if (draft.frozen) requestPolicy(value);
                else draft.preferences.chooseApproval(value);
            });
        }
        if (!draft.frozen) {
            approvalPopup.addSeparator();
            approvalPopup.add(draft.preferences.authorizationControl());
        }
        approvalPopup.show(approval, 0, -approvalPopup.getPreferredSize().height - 6);
    }

    private void requestPolicy(String value) {
        if (requestingPolicy || policy == null || !policy.editable() || policy.pendingMode().isPresent() || observedTask == null)
            return;
        requestingPolicy = true;
        var original = observedTask;
        var originalMode = observedMode;
        long expected = policy.revision();
        actions.execute(() -> {
            try {
                if (originalMode.equals("AGENT")) agentService.requestApprovalPolicyChange(original, value, expected);
                else standardService.requestApprovalPolicyChange(original, value, expected);
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> showProblem(text("flow.approval.failed")));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    requestingPolicy = false;
                    observe();
                });
            }
        });
        refreshControls();
    }

    private void submit() {
        if (!start.isEnabled()) return;
        observedMode = draft.mode;
        capturedPolicy = draft.preferences.approval();
        observedTask = null;
        policy = null;
        problem.setText("");
        if (draft.mode.equals("AGENT")) agent.flowSubmit();
        else standard.flowSubmit();
        observe();
    }

    private void observe() {
        if (closed || standard == null) return;
        boolean active = standard.flowBusy() || agent.flowBusy();
        boolean prior = draft.frozen;
        draft.frozen = active;
        UUID current = observedMode.equals("AGENT") ? agent.flowTask() : standard.flowTask();
        if (current != null) observedTask = current;
        if (active && observedTask != null && !environment.locked()) {
            try {
                var actual = observedMode.equals("AGENT") ? agentService.approvalPolicyState(observedTask) : standardService.approvalPolicyState(observedTask);
                if (policy != null || actual.editable() || actual.revision() > 0 || actual.pendingMode().isPresent() || actual.problem().isPresent())
                    policy = actual;
            } catch (RuntimeException unavailable) {
                policy = null;
            }
        }
        if (prior != active) {
            if (modePopup != null) modePopup.setVisible(false);
            if (approvalPopup != null) approvalPopup.setVisible(false);
            if (!active) loadPreferences();
            standard.flowAvailability();
        }
        refreshControls();
    }

    private void selectSurface() {
        if (standard == null || agent == null) return;
        ((CardLayout) results.getLayout()).show(results, draft.mode.equals("AGENT") ? "AGENT" : "STANDARD");
        standard.flowMode(draft.mode);
        agentAdvanced.setVisible(draft.mode.equals("AGENT"));
    }

    private void refreshControls() {
        if (closed || standard == null || draft.local == null || messages == null) return;
        boolean edit = !environment.locked() && !draft.frozen && draft.preferences.ready() && !loading;
        boolean sourceBusy = standard.flowSourceBusy();
        mode.setEnabled(edit && !sourceBusy);
        mode.setText(text("flow.mode." + draft.mode) + "  ⌄");
        draft.preferences.editable(edit);
        String selectedPolicy = draft.frozen ? policy == null ? capturedPolicy : policy.mode() : draft.preferences.approval();
        approval.setText(text("approval." + selectedPolicy) + "  ⌄");
        approval.setEnabled(!environment.locked() && !requestingPolicy && (draft.frozen ? policy != null && policy.editable() && policy.pendingMode().isEmpty() : edit));
        approval.setVisible(!draft.mode.equals("STATIC"));
        start.setEnabled(edit && !sourceBusy && draft.ready() && draft.server() != null);
        server.setEnabled(edit && !sourceBusy);
        var selected = draft.server();
        server.setText(selected == null ? text("flow.target.select") : "<html><center>" + html(selected.name()) + "<br>" + html(selected.username() + "@" + selected.host() + ":" + selected.port()) + "</center></html>");
        clearServer.setEnabled(edit && selected != null);
        refreshServers.setEnabled(edit && !sourceBusy);
        manageServers.setEnabled(edit && !sourceBusy);
        models.setEnabled(!environment.locked());
        sourceInput.setEnabled(edit && !sourceBusy && !draft.ready());
        sourceAccept.setEnabled(sourceInput.isEnabled());
        retryPreferences.setVisible(!draft.preferences.ready() && !draft.frozen && !loading);
        retryPreferences.setEnabled(!environment.locked() && !loading);
        clearGit.setEnabled(edit && draft.gitSelected());
        draft.local.availability(edit, edit, !draft.gitSelected());
        gitLocation.setText(draft.remote.getText());
        ((CardLayout) sourceRoutes.getLayout()).show(sourceRoutes, draft.gitSelected() ? "GIT" : "LOCAL");
        boolean unavailable = environment.locked() || draft.frozen;
        if (advanced != null && !Objects.equals(advancedUnavailable, unavailable)) {
            advanced.environment(messages, unavailable);
            advancedUnavailable = unavailable;
        }
        standard.flowAvailability();
        policyStatus.setText(policy != null && draft.frozen ? policy.pendingMode().map(value -> text("flow.approval.pending") + " " + text("approval." + value))
            .orElseGet(() -> policy.problem().orElse("")) : "");
    }

    private void chooseServer() {
        if (!server.isEnabled()) return;
        var choices = new DefaultListModel<ServerInfo>();
        var list = new JList<>(choices);
        list.setName("deployment.flow.serverPicker.list");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(62);
        list.setCellRenderer((view, value, index, selected, focus) -> {
            var label = new JLabel("<html>" + html(value.name()) + "<br>" + html(value.username() + "@" + value.host() + ":" + value.port()) + "</html>", DeploymentPageStyle.icon("server", 30), SwingConstants.LEFT);
            label.setOpaque(true);
            label.setBackground(selected ? view.getSelectionBackground() : view.getBackground());
            label.setForeground(selected ? view.getSelectionForeground() : view.getForeground());
            label.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
            return label;
        });
        var search = new JTextField();
        search.setName("deployment.flow.serverPicker.search");
        search.putClientProperty("JTextField.placeholderText", text("flow.target.search"));
        Runnable filter = () -> {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            choices.clear();
            for (int index = 0; index < draft.servers.getItemCount(); index++) {
                var value = draft.servers.getItemAt(index);
                if ((value.name() + " " + value.host() + " " + value.username()).toLowerCase(Locale.ROOT).contains(query))
                    choices.addElement(value);
            }
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) {
                filter.run();
            }

            public void removeUpdate(DocumentEvent event) {
                filter.run();
            }

            public void changedUpdate(DocumentEvent event) {
                filter.run();
            }
        });
        filter.run();
        var body = new JPanel(new BorderLayout(0, 12));
        body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        body.add(search, BorderLayout.NORTH);
        body.add(DeploymentPageStyle.scroll(list), BorderLayout.CENTER);
        var choose = new JButton(text("flow.target.use"));
        choose.setEnabled(false);
        list.addListSelectionListener(ignored -> choose.setEnabled(list.getSelectedValue() != null));
        choose.addActionListener(ignored -> {
            if (!environment.locked() && !draft.frozen && list.getSelectedValue() != null) {
                var requested = list.getSelectedValue().ref();
                ServerInfo current = null;
                for (int index = 0; index < draft.servers.getItemCount(); index++) {
                    var value = draft.servers.getItemAt(index);
                    if (value.ref().equals(requested)) current = value;
                }
                if (current != null) draft.servers.setSelectedItem(current);
                else showProblem(text("target.unavailable"));
            }
            serverPicker.dispose();
            refreshControls();
        });
        body.add(choose, BorderLayout.SOUTH);
        serverPicker = new JDialog(SwingUtilities.getWindowAncestor(root), text("flow.target.select"), Dialog.ModalityType.MODELESS);
        serverPicker.setName("deployment.flow.serverPicker");
        serverPicker.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        serverPicker.add(body);
        serverPicker.setSize(DeploymentPageStyle.scale(520), DeploymentPageStyle.scale(440));
        serverPicker.setLocationRelativeTo(root);
        DeploymentPageStyle.refresh(body, messages);
        serverPicker.setVisible(true);
    }

    private void loadPreferences() {
        if (closed || environment.locked() || loading || draft.frozen) return;
        loading = true;
        refreshControls();
        long view = generation.get();
        actions.execute(() -> {
            try {
                var handle = standardService.preferences();
                preferenceLookup = handle;
                if (closed || this.environment.locked()) handle.requestCancel();
                handle.completion().whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                    preferenceLookup = null;
                    loading = false;
                    if (closed || environment.locked() || generation.get() != view) return;
                    if (failure == null && result != null && result.outcome() == TaskResult.SUCCEEDED && result.value().isPresent()) {
                        var selected = result.value().orElseThrow();
                        if (navigationMode != null)
                            selected = new Selection(navigationMode, selected.approval(), selected.rememberAuthorization());
                        navigationMode = null;
                        draft.preferences.load(selected);
                        selectSurface();
                    } else showProblem(text("preferenceError"));
                    refreshControls();
                }));
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    loading = false;
                    showProblem(text("preferenceError"));
                    refreshControls();
                });
            }
        });
    }

    @Override
    public void restoreNavigation(String id) {
        requireEdt();
        if (draft.frozen) return;
        if (id.equals("agent-deployment")) navigationMode = "AGENT";
        else if (id.equals("static-deployment")) navigationMode = "STATIC";
        if (created && navigationMode != null) {
            draft.mode = navigationMode;
            selectSurface();
            refreshControls();
        }
    }

    @Override
    public String navigationId() {
        return "deployment-flow";
    }

    @Override
    public void environmentChanged(UiEnvironment environment) {
        requireEdt();
        this.environment = environment;
        catalog();
        if (environment.locked()) {
            generation.incrementAndGet();
            draft.local.revoke();
            if (serverPicker != null) serverPicker.dispose();
            if (modePopup != null) modePopup.setVisible(false);
            if (approvalPopup != null) approvalPopup.setVisible(false);
        }
        standard.environmentChanged(environment);
        agent.environmentChanged(environment);
        localize();
        if (!environment.locked() && !draft.preferences.ready()) loadPreferences();
        if (!environment.locked() && draft.servers.getItemCount() == 0) standard.flowRefreshServers();
        refreshControls();
    }

    private void localize() {
        draft.preferences.localize(this::text);
        draft.local.localize();
        models.setText(text("target.models"));
        start.setText(text("flow.start"));
        clearServer.setText(text("target.clear"));
        clearGit.setText(text("source.clear"));
        refreshServers.setText(text("target.refresh"));
        manageServers.setText(text("target.servers"));
        sourceAccept.setText(text("flow.source.accept"));
        retryPreferences.setText(text("workspace.retry"));
        sourceInput.putClientProperty("JTextField.placeholderText", text("flow.source.input"));
        server.setPreferredSize(new Dimension(DeploymentPageStyle.scale(240), DeploymentPageStyle.scale(100)));
        if (projectSelection != null) {
            projectSelection.setPreferredSize(null);
            var preferred = projectSelection.getPreferredSize();
            int textWidth = projectSelection.getFontMetrics(projectSelection.getFont()).stringWidth(text("project.none"));
            projectSelection.setPreferredSize(new Dimension(Math.max(DeploymentPageStyle.scale(185), textWidth + DeploymentPageStyle.scale(48)), preferred.height));
        }
        DeploymentPageStyle.refresh(root, messages);
        if (selectionScroll != null) {
            selectionScroll.getViewport().setOpaque(true);
            selectionScroll.getViewport().setBackground(UIManager.getColor("Panel.background"));
        }
        if (advanced != null) advanced.environment(messages, environment.locked() || draft.frozen);
        refreshControls();
    }

    private void catalog() {
        messages = ResourceBundle.getBundle("gold.debug.windowstolinux.mod.deployment.ui.deployment-page", environment.locale(), ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
    }

    private String text(String key) {
        return messages.getString(key);
    }

    private void showProblem(String message) {
        if (!closed) {
            problem.setText(message);
            problem.setToolTipText(message);
        }
    }

    private void openPlatform(PlatformPageKind page) {
        try {
            standardService.openPlatform(page);
        } catch (Exception failure) {
            showProblem(text("target.unavailable"));
        }
    }

    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Flow requires EDT");
    }

    /** Keeps selection cards within their viewport while allowing vertical reachability. */
    private static final class SelectionRegion extends JPanel implements Scrollable {
        SelectionRegion() {
            super(new GridBagLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() != null && getPreferredSize().height <= getParent().getHeight();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int axis, int direction) {
            return DeploymentPageStyle.scale(18);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int axis, int direction) {
            return visible.height;
        }
    }

    @Override
    public void close() {
        requireEdt();
        if (closed) return;
        closed = true;
        generation.incrementAndGet();
        if (observer != null) observer.stop();
        if (serverPicker != null) serverPicker.dispose();
        if (modePopup != null) modePopup.setVisible(false);
        if (approvalPopup != null) approvalPopup.setVisible(false);
        if (advanced != null) advanced.close();
        if (draft.preferences != null) draft.preferences.close();
        if (draft.local != null) draft.local.revoke();
        if (standard != null) standard.close();
        if (agent != null) agent.close();
        if (preferenceLookup != null) try {
            preferenceLookup.requestCancel();
        } catch (Exception retained) { /* Original metadata task retains its outcome. */ }
        actions.shutdown();
    }
}
