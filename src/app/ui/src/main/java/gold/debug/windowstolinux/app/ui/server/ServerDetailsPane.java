package gold.debug.windowstolinux.app.ui.server;

import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.*;

import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.service.server.ServerSummary;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;

/** Selected connection metadata and dated evidence, without reading credentials. / 所选连接元数据与带时间证据，不读取凭据。 */
final class ServerDetailsPane extends JPanel implements Scrollable {
    /** Current localized captions. / 当前本地化文案。 */
    private final PageMessagePresenter messages;

    /** Selected identity without duplicated check evidence. / 所选身份，不重复检查证据。 */
    private final JLabel title = new JLabel(), endpoint = new JLabel();

    /** Non-secret connection field values. / 非秘密连接字段值。 */
    private final JLabel[] values = {new JLabel(), new JLabel(), new JLabel(), new JLabel()};

    /** Arranges selected metadata above existing operation and evidence controls. / 将所选元数据排列在已有操作与证据控件上方。
     * @param components themed factory / 主题工厂
     * @param messages safe localized captions / 安全本地化文案
     * @param inventoryHeader aligned inventory title and search / 对齐的清单标题与搜索区
     * @param actions original actions / 原操作
     * @param records current authentication result / 当前认证结果
     * @param collapse clears the original selection / 清空原选择 */
    ServerDetailsPane(DesktopPartFactory components, PageMessagePresenter messages,
                      JComponent inventoryHeader, JComponent actions, JComponent records, Runnable collapse) {
        super(new BorderLayout(0, UIScale.scale(20)));
        this.messages = messages;
        components.stylePage(this);
        setBorder(new FlatEmptyBorder(0, 24, 16, 24));
        setName("servers.details");
        var upper = components.transparent(new BorderLayout(0, UIScale.scale(22)));
        var heading = new JPanel(new BorderLayout(UIScale.scale(16), 0)) {
            /** Aligns identity controls with the inventory header at every scale. / 各缩放下将身份控件与清单标题区对齐。 */
            @Override
            public Dimension getPreferredSize() {
                var size = super.getPreferredSize();
                size.height = Math.max(size.height, inventoryHeader.getPreferredSize().height);
                return size;
            }
        };
        heading.setOpaque(false);
        var copy = components.transparent(new GridBagLayout());
        title.setFont(title.getFont().deriveFont(Font.BOLD, UIScale.scale(24f)));
        title.setName("servers.details.title");
        endpoint.setName("servers.details.endpoint");
        endpoint.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN, UIScale.scale(14f)));
        for (var label : new JLabel[]{title, endpoint}) label.putClientProperty("html.disable", true);
        var line = new GridBagConstraints();
        line.gridx = 0;
        line.gridy = 0;
        line.weightx = 1;
        line.fill = GridBagConstraints.HORIZONTAL;
        copy.add(title, line);
        line.gridy = 1;
        line.insets = UIScale.scale(new Insets(4, 0, 0, 0));
        copy.add(endpoint, line);
        var icon = new JLabel(DesktopIcons.icon("server/server", 32, () -> UIManager.getColor("Component.focusColor")));
        icon.setName("servers.details.icon");
        icon.setBorder(new FlatEmptyBorder(12, 10, 12, 10));
        heading.add(icon, BorderLayout.WEST);
        heading.add(copy, BorderLayout.CENTER);
        var collapseButton = messages.bind(components.secondaryButton(""), "server.collapseDetails");
        collapseButton.setName("servers.collapseDetails");
        collapseButton.setMargin(new Insets(8, 22, 8, 22));
        collapseButton.addActionListener(event -> collapse.run());
        var collapseGroup = components.transparent(new GridBagLayout());
        collapseGroup.add(collapseButton);
        heading.add(collapseGroup, BorderLayout.EAST);
        upper.add(heading, BorderLayout.NORTH);
        var fields = components.card(new GridLayout(1, 4, UIScale.scale(16), UIScale.scale(12)));
        fields.setName("servers.details.fields");
        var dividers = new JSeparator[values.length - 1];
        for (int i = 0; i < values.length; i++) {
            var field = components.transparent(new BorderLayout(UIScale.scale(16), 0));
            field.setName("servers.details.cell." + i);
            var content = components.transparent(new BorderLayout(0, UIScale.scale(8)));
            String key = new String[]{"field.host", "field.sshPort", "field.sshUser", "server.credentials"}[i];
            var caption = messages.bind(components.description(""), key);
            caption.setName("servers.details.caption." + i);
            content.add(caption, BorderLayout.NORTH);
            values[i].putClientProperty("html.disable", true);
            values[i].setName("servers.details.field." + i);
            content.add(values[i]);
            field.add(content);
            if (i + 1 < values.length) {
                var divider = components.divider();
                divider.setName("servers.details.divider." + i);
                divider.setOrientation(SwingConstants.VERTICAL);
                dividers[i] = divider;
                field.add(divider, BorderLayout.EAST);
            }
            fields.add(field);
        }
        var responsive = new JPanel(new BorderLayout()) {
            /** Preserves stacked field content and separates adjacent columns. / 保留字段内上下布局，以竖线分隔相邻列。 */
            @Override
            public void doLayout() {
                boolean narrow = getWidth() < UIScale.scale(560);
                var grid = (GridLayout) fields.getLayout();
                grid.setRows(narrow ? 2 : 1);
                grid.setColumns(narrow ? 2 : 4);
                for (int i = 0; i < dividers.length; i++) dividers[i].setVisible((i + 1) % grid.getColumns() != 0);
                super.doLayout();
            }

            /** Reserves the original one-row or two-row metadata height. / 保留原有单行或双行字段区域高度。 */
            @Override
            public Dimension getPreferredSize() {
                boolean narrow = getWidth() > 0 && getWidth() < UIScale.scale(560);
                return new Dimension(0, UIScale.scale(narrow ? 144 : 84));
            }
        };
        responsive.setOpaque(false);
        responsive.add(fields);
        upper.add(responsive, BorderLayout.CENTER);
        upper.add(actions, BorderLayout.SOUTH);
        add(upper, BorderLayout.NORTH);
        var resultRegion = components.transparent(new BorderLayout());
        resultRegion.add(records, BorderLayout.NORTH);
        add(resultRegion, BorderLayout.CENTER);
    }

    /** Displays only the current saved selection and observation. / 仅展示当前保存的选择与观测。
     * @param summary selected saved summary or none / 所选保存摘要或无选择 */
    void show(ServerSummary summary) {
        title.setFont(title.getFont().deriveFont(Font.BOLD, UIScale.scale(24f)));
        endpoint.setFont(UIManager.getFont("Label.font").deriveFont(Font.PLAIN, UIScale.scale(14f)));
        var profile = summary == null ? null : summary.profile();
        title.setText(profile == null ? messages.text("server.choose") : profile.displayName());
        title.setToolTipText(title.getText());
        endpoint.setText(profile == null ? " " : endpoint(summary));
        endpoint.setToolTipText(endpoint.getText());
        String[] content = profile == null ? new String[]{"—", "—", "—", "—"}
            : new String[]{profile.host(), Integer.toString(profile.sshPort()), profile.username(), messages.text("server.credentialConfigured")};
        for (int i = 0; i < values.length; i++) {
            values[i].setText(content[i]);
            values[i].setToolTipText(content[i]);
        }
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
        return UIScale.scale(20);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
        return Math.max(UIScale.scale(20), visibleRect.height - UIScale.scale(20));
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return getParent() instanceof JViewport viewport && viewport.getHeight() > getPreferredSize().height;
    }

    /** Formats the selected connection without exposing credentials. / 格式化所选连接而不暴露凭据。
     * @param summary original saved summary / 原保存摘要
     * @return non-secret connection address / 非秘密连接地址 */
    static String endpoint(ServerSummary summary) {
        var profile = summary.profile();
        return profile.username() + "@" + profile.host() + ":" + profile.sshPort();
    }

    /** Formats the actual observation time in the local zone. / 按本地时区格式化实际观测时间。
     * @param summary original saved summary / 原保存摘要
     * @return dated evidence or empty marker / 带时间证据或空标记 */
    static String checkedAt(ServerSummary summary) {
        return summary.checkedAt().map(value -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault()).format(value)).orElse("—");
    }

    /** Describes historical authentication evidence without implying liveness. / 描述历史认证证据而不暗示实时状态。
     * @param summary original observation / 原观测
     * @param messages localized captions / 本地化文案
     * @return dated-check status caption / 带时间检查状态文案 */
    static String status(ServerSummary summary, PageMessagePresenter messages) {
        return messages.text(summary.checkedAt().isEmpty() ? "server.card.unchecked"
            : summary.connected() ? "server.card.passed" : "server.card.failed");
    }

    /** Selects readable check-evidence colors for the active theme. / 为当前主题选择可读检查证据颜色。
     * @param summary original observation or none / 原观测或无观测
     * @return current evidence color / 当前证据颜色 */
    static Color statusColor(ServerSummary summary) {
        if (summary == null || summary.checkedAt().isEmpty()) return UIManager.getColor("Label.foreground");
        boolean dark = com.formdev.flatlaf.FlatLaf.isLafDark();
        return summary.connected() ? new Color(dark ? 0x68D391 : 0x14804A) : new Color(dark ? 0xFF9292 : 0xC23939);
    }
}
