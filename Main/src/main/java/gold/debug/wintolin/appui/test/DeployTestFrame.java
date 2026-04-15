package gold.debug.wintolin.appui.test;

import gold.debug.wintolin.attribute.language.AJava;
import gold.debug.wintolin.attribute.system.ALinux;
import gold.debug.wintolin.attribute.system.APackageInfo;
import gold.debug.wintolin.exceptionanderror.MyException;
import gold.debug.wintolin.tools.language.tjava.TJava;
import gold.debug.wintolin.tools.system.linux.TLinux;
import gold.debug.wintolin.tools.system.to.TPacker;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试/演示窗口：
 * 1. 支持多项目标签页
 * 2. 支持 Linux 连接测试
 * 3. 支持 Java 项目检查
 * 4. 支持打包 -> 上传 -> Linux 解包部署
 *
 * 说明：
 * - 当前仅用于测试，不做任何本地配置持久化
 * - 当前 SSH 端口沿用后端默认值 22
 * - 当前仅接 Java 项目能力
 */
public final class DeployTestFrame extends JFrame {

    private final JTabbedPane tabbedPane = new JTabbedPane();
    private int projectIndex = 1;

    public DeployTestFrame() {
        super("WindowsToLinux - 测试部署窗口");
        initFrame();
        initTopBar();
        addProjectTab(null);
    }

    private void initFrame() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 760));
        setSize(1280, 820);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        add(tabbedPane, BorderLayout.CENTER);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnectAllTabs();
            }
        });
    }

    private void initTopBar() {
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        topBar.setBorder(new EmptyBorder(8, 10, 8, 10));

        JButton addTabButton = new JButton("新增项目");
        addTabButton.putClientProperty("JButton.buttonType", "roundRect");
        addTabButton.addActionListener(e -> addProjectTab(null));

        JButton closeTabButton = new JButton("关闭当前项目");
        closeTabButton.putClientProperty("JButton.buttonType", "roundRect");
        closeTabButton.addActionListener(e -> removeCurrentTab());

        topBar.add(addTabButton);
        topBar.add(closeTabButton);

        add(topBar, BorderLayout.NORTH);
    }

    private void addProjectTab(String title) {
        String tabTitle = (title == null || title.isBlank())
                ? "项目 " + projectIndex++
                : title.trim();

        ProjectDeployPanel panel = new ProjectDeployPanel(tabTitle);
        tabbedPane.addTab(tabTitle, panel);
        tabbedPane.setSelectedComponent(panel);
    }

    private void removeCurrentTab() {
        Component selected = tabbedPane.getSelectedComponent();
        if (selected instanceof ProjectDeployPanel panel) {
            panel.disconnectQuietly();
        }

        int index = tabbedPane.getSelectedIndex();
        if (index >= 0) {
            tabbedPane.removeTabAt(index);
        }

        if (tabbedPane.getTabCount() == 0) {
            addProjectTab(null);
        }
    }

    private void disconnectAllTabs() {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            Component c = tabbedPane.getComponentAt(i);
            if (c instanceof ProjectDeployPanel panel) {
                panel.disconnectQuietly();
            }
        }
    }

    /**
     * 单个项目面板
     */
    private static final class ProjectDeployPanel extends JPanel {

        private final String panelName;

        private final JTextField tfProjectName = new JTextField();
        private final JTextField tfSourceDir = new JTextField();
        private final JTextField tfLocalTempDir = new JTextField();
        private final JTextField tfArchiveBaseName = new JTextField();
        private final JTextField tfExpectedRootName = new JTextField();

        private final JTextField tfLinuxHost = new JTextField();
        private final JTextField tfLinuxUser = new JTextField();
        private final JPasswordField pfLinuxPassword = new JPasswordField();

        private final JTextField tfRemoteTempDir = new JTextField();
        private final JTextField tfRemoteTargetDir = new JTextField();

        private final JCheckBox cbDeployJavaEnv = new JCheckBox("部署 Java 环境", true);
        private final JCheckBox cbBackupBeforeReplace = new JCheckBox("替换前备份旧目录", true);

        private final JTextArea taExcludeRules = new JTextArea(5, 20);
        private final JTextArea taLog = new JTextArea();

        private final JLabel lbConnectState = new JLabel("未连接");
        private final JLabel lbLinuxInfo = new JLabel("发行版：未知");
        private final JLabel lbJavaInfo = new JLabel("Java 项目信息：未检测");

        private final JButton btnBrowseSource = new JButton("选择源码目录");
        private final JButton btnBrowseLocalTemp = new JButton("选择本地临时目录");
        private final JButton btnConnect = new JButton("测试连接");
        private final JButton btnDisconnect = new JButton("断开连接");
        private final JButton btnDetectJava = new JButton("检测 Java 项目");
        private final JButton btnDeploy = new JButton("上传并部署");
        private final JButton btnClearLog = new JButton("清空日志");

        private ALinux activeLinux;
        private AJava lastJavaInfo;
        private APackageInfo lastPackageInfo;

        private SwingWorker<Void, String> currentWorker;

        private ProjectDeployPanel(String panelName) {
            super(new BorderLayout(12, 12));
            this.panelName = panelName;
            initDefaults();
            initView();
            bindActions();
        }

        private void initDefaults() {
            tfProjectName.setText(panelName);
            tfLocalTempDir.setText(Paths.get(System.getProperty("java.io.tmpdir"), "wintolin").toString());
            tfRemoteTempDir.setText("/tmp/wintolin");
            tfRemoteTargetDir.setText("/opt/" + safeName(panelName));
            taLog.setEditable(false);
            taLog.setLineWrap(true);
            taLog.setWrapStyleWord(true);

            taExcludeRules.setText("""
                    .git
                    .idea
                    target
                    out
                    """);
        }

        private void initView() {
            setBorder(new EmptyBorder(12, 12, 12, 12));

            JPanel left = new JPanel();
            left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));

            left.add(buildProjectPanel());
            left.add(Box.createVerticalStrut(10));
            left.add(buildLinuxPanel());
            left.add(Box.createVerticalStrut(10));
            left.add(buildRemotePanel());
            left.add(Box.createVerticalStrut(10));
            left.add(buildOptionPanel());
            left.add(Box.createVerticalStrut(10));
            left.add(buildActionPanel());

            JScrollPane leftScroll = new JScrollPane(left);
            leftScroll.setBorder(null);
            leftScroll.getVerticalScrollBar().setUnitIncrement(16);

            JPanel right = new JPanel(new BorderLayout(10, 10));
            right.add(buildStatusPanel(), BorderLayout.NORTH);
            right.add(buildLogPanel(), BorderLayout.CENTER);

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScroll, right);
            splitPane.setResizeWeight(0.42);
            splitPane.setBorder(null);

            add(splitPane, BorderLayout.CENTER);
        }

        private JPanel buildProjectPanel() {
            JPanel panel = createSection("项目基础信息");

            btnBrowseSource.putClientProperty("JButton.buttonType", "roundRect");
            btnBrowseLocalTemp.putClientProperty("JButton.buttonType", "roundRect");

            panel.add(createRow("项目名称", tfProjectName, null));
            panel.add(createRow("源码目录", tfSourceDir, btnBrowseSource));
            panel.add(createRow("本地临时目录", tfLocalTempDir, btnBrowseLocalTemp));
            panel.add(createRow("归档名称", tfArchiveBaseName, null));
            panel.add(createRow("解包根目录名", tfExpectedRootName, null));

            JLabel excludeLabel = new JLabel("排除规则（每行一条）");
            JScrollPane excludeScroll = new JScrollPane(taExcludeRules);
            excludeScroll.setPreferredSize(new Dimension(100, 110));

            panel.add(excludeLabel);
            panel.add(Box.createVerticalStrut(6));
            panel.add(excludeScroll);

            return panel;
        }

        private JPanel buildLinuxPanel() {
            JPanel panel = createSection("Linux 连接信息");

            panel.add(createRow("主机/IP", tfLinuxHost, null));
            panel.add(createRow("用户名", tfLinuxUser, null));
            panel.add(createRow("密码", pfLinuxPassword, null));

            return panel;
        }

        private JPanel buildRemotePanel() {
            JPanel panel = createSection("远端部署信息");

            panel.add(createRow("远端临时目录", tfRemoteTempDir, null));
            panel.add(createRow("远端目标目录", tfRemoteTargetDir, null));

            return panel;
        }

        private JPanel buildOptionPanel() {
            JPanel panel = createSection("部署选项");
            panel.add(cbDeployJavaEnv);
            panel.add(Box.createVerticalStrut(6));
            panel.add(cbBackupBeforeReplace);
            return panel;
        }

        private JPanel buildActionPanel() {
            JPanel panel = createSection("操作");

            styleActionButton(btnConnect);
            styleActionButton(btnDisconnect);
            styleActionButton(btnDetectJava);
            styleActionButton(btnDeploy);
            styleActionButton(btnClearLog);

            JPanel row1 = new JPanel(new GridLayout(1, 2, 10, 0));
            row1.setOpaque(false);
            row1.add(btnConnect);
            row1.add(btnDisconnect);

            JPanel row2 = new JPanel(new GridLayout(1, 2, 10, 0));
            row2.setOpaque(false);
            row2.add(btnDetectJava);
            row2.add(btnDeploy);

            JPanel row3 = new JPanel(new GridLayout(1, 1));
            row3.setOpaque(false);
            row3.add(btnClearLog);

            panel.add(row1);
            panel.add(Box.createVerticalStrut(8));
            panel.add(row2);
            panel.add(Box.createVerticalStrut(8));
            panel.add(row3);

            return panel;
        }

        private JPanel buildStatusPanel() {
            JPanel panel = createSection("状态");
            panel.add(new JLabel("连接状态："));
            panel.add(lbConnectState);
            panel.add(Box.createVerticalStrut(8));
            panel.add(lbLinuxInfo);
            panel.add(Box.createVerticalStrut(8));
            panel.add(lbJavaInfo);
            return panel;
        }

        private JPanel buildLogPanel() {
            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setBorder(new TitledBorder("执行日志"));
            JScrollPane scrollPane = new JScrollPane(taLog);
            wrapper.add(scrollPane, BorderLayout.CENTER);
            return wrapper;
        }

        private void bindActions() {
            btnBrowseSource.addActionListener(e -> chooseDirectory(tfSourceDir, true));
            btnBrowseLocalTemp.addActionListener(e -> chooseDirectory(tfLocalTempDir, false));

            btnConnect.addActionListener(e -> runConnectTask());
            btnDisconnect.addActionListener(e -> {
                disconnectQuietly();
                appendLog("已断开当前 SSH 连接。");
            });
            btnDetectJava.addActionListener(e -> runDetectJavaTask());
            btnDeploy.addActionListener(e -> runDeployTask());
            btnClearLog.addActionListener(e -> taLog.setText(""));
        }

        private void runConnectTask() {
            if (isBusy()) {
                appendLog("已有任务正在执行，请稍后。");
                return;
            }

            currentWorker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    setBusy(true);
                    publish("开始测试 Linux SSH 连接...");

                    ALinux linux = buildLinuxFromInput();
                    TLinux.isIPRight(linux);
                    TLinux.connectSSH(linux);
                    TLinux.getDistribution(linux);

                    activeLinux = linux;

                    publish("SSH 连接成功。");
                    publish("识别到 Linux 发行版：" + linux.getLinuxDistribution()
                            + (blankToEmpty(linux.getLinuxVersion()).isBlank()
                            ? ""
                            : " " + linux.getLinuxVersion()));
                    return null;
                }

                @Override
                protected void process(List<String> chunks) {
                    chunks.forEach(ProjectDeployPanel.this::appendLog);
                }

                @Override
                protected void done() {
                    setBusy(false);
                    try {
                        get();
                        refreshLinuxLabels();
                    } catch (Exception e) {
                        handleWorkerException("连接测试失败", e);
                    }
                }
            };

            currentWorker.execute();
        }

        private void runDetectJavaTask() {
            if (isBusy()) {
                appendLog("已有任务正在执行，请稍后。");
                return;
            }

            currentWorker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    setBusy(true);
                    Path sourcePath = requireSourcePath();
                    publish("开始检测 Java 项目：" + sourcePath);

                    AJava aJava = TJava.windowsVersionCheck(sourcePath);
                    lastJavaInfo = aJava;

                    publishJavaInfo(aJava);
                    return null;
                }

                @Override
                protected void process(List<String> chunks) {
                    chunks.forEach(ProjectDeployPanel.this::appendLog);
                }

                @Override
                protected void done() {
                    setBusy(false);
                    try {
                        get();
                        refreshJavaLabel();
                    } catch (Exception e) {
                        handleWorkerException("Java 项目检测失败", e);
                    }
                }

                private void publishJavaInfo(AJava aJava) {
                    publish("检测完成：");
                    publish(" - 是否 Maven 项目：" + aJava.isMaven());
                    publish(" - 项目要求 Java 版本：" + safeText(aJava.getProjectJavaVersion()));
                    publish(" - 项目版本来源：" + safeText(aJava.getProjectJavaVersionSource()));
                    publish(" - 本机 java 版本：" + safeText(aJava.getLocalJavaVersion()));
                    publish(" - 本机 javac 版本：" + safeText(aJava.getLocalJavacVersion()));
                    publish(" - 本机 Maven 版本：" + safeText(aJava.getLocalMavenVersion()));
                    publish(" - 兼容性：" + (aJava.isVersionCompatible() ? "兼容" : "需关注"));
                    publish(" - 说明：" + safeText(aJava.getCompatibilityMessage()));
                }
            };

            currentWorker.execute();
        }

        private void runDeployTask() {
            if (isBusy()) {
                appendLog("已有任务正在执行，请稍后。");
                return;
            }

            currentWorker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() {
                    setBusy(true);

                    Path sourcePath = requireSourcePath();
                    publish("开始执行部署流程...");
                    publish("源码目录：" + sourcePath);

                    ALinux linux = ensureConnectedLinux();
                    refreshLinuxInfoFromBackend(linux);

                    publish("步骤 1/5：检测 Java 项目信息");
                    AJava aJava = TJava.windowsVersionCheck(sourcePath);
                    lastJavaInfo = aJava;
                    publish("Java 项目检测完成。项目 Java 版本：" + safeText(aJava.getProjectJavaVersion()));

                    if (cbDeployJavaEnv.isSelected()) {
                        publish("步骤 2/5：部署 Linux Java 环境");
                        TJava.linuxDeploy(aJava, linux);
                        publish("Linux Java 环境部署完成。");
                    } else {
                        publish("步骤 2/5：已跳过 Linux Java 环境部署。");
                    }

                    publish("步骤 3/5：Windows 端打包源码");
                    APackageInfo packageInfo = buildPackageInfo(sourcePath);
                    TPacker.windowsPacker(packageInfo);
                    publish("打包完成：" + safeText(packageInfo.getLocalArchiveFileName()));
                    publish("归档大小：" + packageInfo.getLocalArchiveSize() + " bytes");
                    publish("归档 SHA-256：" + safeText(packageInfo.getLocalArchiveSha256()));
                    publish("打包文件数：" + packageInfo.getPackedFileCount());

                    publish("步骤 4/5：上传归档到 Linux");
                    TPacker.packageTransfer(packageInfo, linux);
                    publish("上传完成，远端归档：" + safeText(packageInfo.getRemoteArchivePath()));
                    publish("远端 SHA-256：" + safeText(packageInfo.getRemoteArchiveSha256()));

                    publish("步骤 5/5：Linux 解包并替换部署目录");
                    TPacker.linuxUnpacker(packageInfo, linux);
                    publish("部署完成。最终目录：" + safeText(packageInfo.getFinalDeployPath()));
                    publish("是否替换成功：" + packageInfo.isReplaceSuccess());

                    if (packageInfo.getBackupPath() != null && !packageInfo.getBackupPath().isBlank()) {
                        publish("备份目录：" + packageInfo.getBackupPath());
                    }

                    lastPackageInfo = packageInfo;
                    return null;
                }

                @Override
                protected void process(List<String> chunks) {
                    chunks.forEach(ProjectDeployPanel.this::appendLog);
                }

                @Override
                protected void done() {
                    setBusy(false);
                    try {
                        get();
                        refreshLinuxLabels();
                        refreshJavaLabel();
                        appendLog("全部流程执行完成。");
                    } catch (Exception e) {
                        handleWorkerException("部署失败", e);
                    }
                }
            };

            currentWorker.execute();
        }

        private void chooseDirectory(JTextField targetField, boolean sourceDir) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle(sourceDir ? "选择源码目录" : "选择本地临时目录");

            int result = chooser.showOpenDialog(this);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }

            String path = chooser.getSelectedFile().getAbsolutePath();
            targetField.setText(path);

            if (sourceDir) {
                Path sourcePath = Paths.get(path);
                String dirName = sourcePath.getFileName() == null ? "project" : sourcePath.getFileName().toString();

                if (tfArchiveBaseName.getText().isBlank()) {
                    tfArchiveBaseName.setText(dirName);
                }
                if (tfExpectedRootName.getText().isBlank()) {
                    tfExpectedRootName.setText(dirName);
                }
                if (tfProjectName.getText().isBlank() || tfProjectName.getText().startsWith("项目 ")) {
                    tfProjectName.setText(dirName);
                }
                appendLog("已选择源码目录：" + path);
            } else {
                appendLog("已选择本地临时目录：" + path);
            }
        }

        private ALinux buildLinuxFromInput() {
            ALinux linux = new ALinux();
            linux.setIp(tfLinuxHost.getText().trim());
            linux.setUser(tfLinuxUser.getText().trim());
            linux.setPassword(new String(pfLinuxPassword.getPassword()));
            return linux;
        }

        private Path requireSourcePath() {
            String text = tfSourceDir.getText().trim();
            if (text.isBlank()) {
                throw new IllegalArgumentException("请先选择源码目录。");
            }

            Path sourcePath = Paths.get(text);
            if (!Files.exists(sourcePath) || !Files.isDirectory(sourcePath)) {
                throw new IllegalArgumentException("源码目录不存在或不是有效目录：" + sourcePath);
            }
            return sourcePath;
        }

        private ALinux ensureConnectedLinux() {
            if (activeLinux != null && activeLinux.getSession() != null && activeLinux.getSession().isConnected()) {
                return activeLinux;
            }

            appendLog("当前未连接，自动开始建立 SSH 连接...");
            ALinux linux = buildLinuxFromInput();
            TLinux.isIPRight(linux);
            TLinux.connectSSH(linux);
            TLinux.getDistribution(linux);
            activeLinux = linux;
            appendLog("自动连接成功。");
            return linux;
        }

        private void refreshLinuxInfoFromBackend(ALinux linux) {
            TLinux.getDistribution(linux);
        }

        private APackageInfo buildPackageInfo(Path sourcePath) {
            APackageInfo info = new APackageInfo();

            String localTemp = tfLocalTempDir.getText().trim();
            if (localTemp.isBlank()) {
                localTemp = Paths.get(System.getProperty("java.io.tmpdir"), "wintolin").toString();
            }

            String archiveBaseName = tfArchiveBaseName.getText().trim();
            if (archiveBaseName.isBlank()) {
                archiveBaseName = sourcePath.getFileName() == null ? "project" : sourcePath.getFileName().toString();
            }

            String expectedRootName = tfExpectedRootName.getText().trim();
            if (expectedRootName.isBlank()) {
                expectedRootName = sourcePath.getFileName() == null ? "project" : sourcePath.getFileName().toString();
            }

            info.setLocalSourceDirectory(sourcePath);
            info.setLocalTempDirectory(Paths.get(localTemp));
            info.setArchiveBaseName(archiveBaseName);
            info.setExpectedRootDirectoryName(expectedRootName);
            info.setRemoteTempDirectory(tfRemoteTempDir.getText().trim());
            info.setRemoteTargetDirectory(tfRemoteTargetDir.getText().trim());
            info.setBackupTargetBeforeReplace(cbBackupBeforeReplace.isSelected());
            info.setExcludeRules(readExcludeRules());

            return info;
        }

        private List<String> readExcludeRules() {
            List<String> rules = new ArrayList<>();
            String[] lines = taExcludeRules.getText().split("\\R");
            for (String line : lines) {
                String value = line.trim();
                if (!value.isBlank()) {
                    rules.add(value);
                }
            }
            return rules;
        }

        private void refreshLinuxLabels() {
            boolean connected = activeLinux != null
                    && activeLinux.getSession() != null
                    && activeLinux.getSession().isConnected();

            lbConnectState.setText(connected ? "已连接" : "未连接");

            if (connected) {
                lbLinuxInfo.setText("发行版："
                        + safeText(String.valueOf(activeLinux.getLinuxDistribution()))
                        + " "
                        + safeText(activeLinux.getLinuxVersion()));
            } else {
                lbLinuxInfo.setText("发行版：未知");
            }
        }

        private void refreshJavaLabel() {
            if (lastJavaInfo == null) {
                lbJavaInfo.setText("Java 项目信息：未检测");
                return;
            }

            String text = "Java 项目信息："
                    + (lastJavaInfo.isMaven() ? "Maven" : "非 Maven")
                    + " | 项目版本=" + safeText(lastJavaInfo.getProjectJavaVersion())
                    + " | 本机 Java=" + safeText(lastJavaInfo.getLocalJavaVersion());
            lbJavaInfo.setText(text);
        }

        private void setBusy(boolean busy) {
            SwingUtilities.invokeLater(() -> {
                btnBrowseSource.setEnabled(!busy);
                btnBrowseLocalTemp.setEnabled(!busy);
                btnConnect.setEnabled(!busy);
                btnDisconnect.setEnabled(!busy);
                btnDetectJava.setEnabled(!busy);
                btnDeploy.setEnabled(!busy);
            });
        }

        private boolean isBusy() {
            return currentWorker != null && !currentWorker.isDone();
        }

        private void disconnectQuietly() {
            if (activeLinux == null) {
                refreshLinuxLabels();
                return;
            }

            try {
                TLinux.disconnectSSH(activeLinux);
            } catch (Exception ignored) {
            } finally {
                activeLinux = null;
                refreshLinuxLabels();
            }
        }

        private void handleWorkerException(String title, Exception exception) {
            Throwable real = exception;
            if (exception instanceof java.util.concurrent.ExecutionException && exception.getCause() != null) {
                real = exception.getCause();
            }

            if (real instanceof MyException myException) {
                appendLog("失败：" + myException.getFormattedMessage());
                JOptionPane.showMessageDialog(
                        this,
                        myException.getFormattedMessage(),
                        title,
                        JOptionPane.ERROR_MESSAGE
                );
                return;
            }

            String message = real.getMessage() == null ? real.getClass().getName() : real.getMessage();
            appendLog("失败：" + message);
            JOptionPane.showMessageDialog(
                    this,
                    message,
                    title,
                    JOptionPane.ERROR_MESSAGE
            );
        }

        private void appendLog(String message) {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            taLog.append("[" + time + "] " + message + System.lineSeparator());
            taLog.setCaretPosition(taLog.getDocument().getLength());
        }

        private JPanel createSection(String title) {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createTitledBorder(title));
            panel.setAlignmentX(Component.LEFT_ALIGNMENT);
            return panel;
        }

        private JPanel createRow(String labelText, JComponent field, JComponent extraButton) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setOpaque(false);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

            JLabel label = new JLabel(labelText);
            label.setPreferredSize(new Dimension(105, 28));

            row.add(label, BorderLayout.WEST);
            row.add(field, BorderLayout.CENTER);
            if (extraButton != null) {
                row.add(extraButton, BorderLayout.EAST);
            }
            return row;
        }

        private void styleActionButton(JButton button) {
            button.putClientProperty("JButton.buttonType", "roundRect");
            button.setFocusPainted(false);
        }

        private static String safeName(String value) {
            if (value == null || value.isBlank()) {
                return "project";
            }
            return value.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
        }

        private static String safeText(String value) {
            return value == null || value.isBlank() ? "未知" : value;
        }

        private static String blankToEmpty(String value) {
            return value == null ? "" : value;
        }
    }
}