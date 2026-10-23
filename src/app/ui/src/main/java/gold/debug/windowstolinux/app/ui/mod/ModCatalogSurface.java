package gold.debug.windowstolinux.app.ui.mod;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import com.formdev.flatlaf.ui.FlatEmptyBorder;
import gold.debug.windowstolinux.app.ui.control.DesktopIcons;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.language.LocalizedMessage;

/** Fixed catalog controls and independently scrolling details. / 固定清单工具栏及独立滚动详情。 */
final class ModCatalogSurface extends JPanel {
    private final DesktopPartFactory components;

    private final PageMessagePresenter messages;

    private final JPanel left, right, leftBody, rightBody, heading, footerHost;

    private final JPanel catalogHeader, titleLine, searchLine, caption;

    private final JLabel count = new JLabel();

    private final JLabel title = new ModTextLabel("", 24, true, false), identity = new ModTextLabel("", 14, false, true);

    private final JButton collapse;

    private final JToggleButton installed = new JToggleButton(), store = new JToggleButton();

    private final JScrollPane detailScroll;

    private final boolean storeMode;

    private final JTextField search;

    private Runnable modeAction = () -> {
    };

    private Runnable collapseAction = () -> {
    };

    private boolean expanded;

    ModCatalogSurface(DesktopPartFactory components, PageMessagePresenter messages, JList<?> list,
                      JTextField search, JButton refresh, boolean storeMode) {
        this.components = components;
        this.messages = messages;
        this.storeMode = storeMode;
        this.search = search;
        setLayout(null);
        setOpaque(false);
        left = components.outerCard(new BorderLayout(0, UIScale.scale(18)));
        right = components.outerCard(new BorderLayout(0, UIScale.scale(18)));
        left.setName(storeMode ? "store.inventoryCard" : "mods.inventoryCard");
        right.setName(storeMode ? "store.detailCard" : "mods.detailCard");
        leftBody = components.transparent(new BorderLayout(0, UIScale.scale(8)));
        rightBody = components.transparent(new BorderLayout(0, UIScale.scale(12)));
        left.add(leftBody, BorderLayout.CENTER);
        right.add(rightBody, BorderLayout.CENTER);
        footerHost = components.transparent(new BorderLayout());
        heading = components.transparent(new BorderLayout(UIScale.scale(8), 0));
        catalogHeader = components.transparent(new BorderLayout(0, UIScale.scale(12)));
        titleLine = components.transparent(new BorderLayout(UIScale.scale(8), 0));
        var label = messages.bind(new ModTextLabel("", 20, true, false), "modui.all");
        caption = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, UIScale.scale(8), 0));
        caption.setOpaque(false);
        caption.add(label);
        count.setName(storeMode ? "store.count" : "mods.count");
        caption.add(count);
        titleLine.add(caption, BorderLayout.CENTER);
        var modes = components.transparent(new java.awt.GridLayout(1, 2, 0, 0));
        modes.setName("modui.modes");
        messages.bind(installed, "mods.installed");
        messages.bind(store, "store.title");
        installed.setName("modui.installed");
        store.setName("modui.store");
        var group = new ButtonGroup();
        group.add(installed);
        group.add(store);
        installed.setSelected(!storeMode);
        store.setSelected(storeMode);
        installed.putClientProperty("JButton.buttonType", "roundRect");
        store.putClientProperty("JButton.buttonType", "roundRect");
        for (var mode : new JToggleButton[]{installed, store})
            mode.putClientProperty("FlatLaf.style", "selectedBackground: $Table.background; "
                + "selectedForeground: $Component.accentColor; margin: 6,12,6,12");
        installed.addActionListener(event -> switchMode(false));
        store.addActionListener(event -> switchMode(true));
        modes.add(installed);
        modes.add(store);
        titleLine.add(modes, BorderLayout.EAST);
        catalogHeader.add(titleLine, BorderLayout.NORTH);
        components.styleSearchField(search);
        search.putClientProperty("JTextField.placeholderText", messages.text("modui.search"));
        search.putClientProperty("JTextField.leadingIcon", DesktopIcons.icon("action/search", 16,
            () -> UIManager.getColor("Label.disabledForeground")));
        searchLine = components.transparent(new BorderLayout(UIScale.scale(8), 0));
        searchLine.add(search, BorderLayout.CENTER);
        searchLine.add(refresh, BorderLayout.EAST);
        catalogHeader.add(searchLine, BorderLayout.SOUTH);
        leftBody.add(catalogHeader, BorderLayout.NORTH);
        components.cardInventory(list);
        var listScroll = new JScrollPane(list);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setName(storeMode ? "store.inventoryScroll" : "mods.inventoryScroll");
        leftBody.add(listScroll, BorderLayout.CENTER);
        var icon = new JLabel(DesktopIcons.icon("mod/puzzle", 32,
            () -> UIManager.getColor("Component.accentColor")));
        heading.add(icon, BorderLayout.WEST);
        var names = components.transparent(new BorderLayout(0, UIScale.scale(3)));
        title.putClientProperty("html.disable", true);
        identity.putClientProperty("html.disable", true);
        names.add(title, BorderLayout.NORTH);
        names.add(identity, BorderLayout.CENTER);
        heading.add(names, BorderLayout.CENTER);
        collapse = messages.bind(components.secondaryButton(""), "modui.collapse");
        collapse.setName(storeMode ? "store.collapse" : "mods.collapse");
        collapse.setMargin(new java.awt.Insets(8, 22, 8, 22));
        collapse.addActionListener(event -> collapseAction.run());
        var collapseBox = components.transparent(new java.awt.GridBagLayout());
        collapseBox.add(collapse);
        heading.add(collapseBox, BorderLayout.EAST);
        rightBody.add(heading, BorderLayout.NORTH);
        detailScroll = new JScrollPane();
        detailScroll.setName(storeMode ? "store.detailScroll" : "mods.detailScroll");
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        detailScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        detailScroll.getVerticalScrollBar().putClientProperty("desktop.inventory.scrollbar", true);
        detailScroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(20));
        components.cardScroll(detailScroll);
        rightBody.add(detailScroll, BorderLayout.CENTER);
        add(left);
        add(right);
        expanded(false);
    }

    private void switchMode(boolean requestedStore) {
        installed.setSelected(!storeMode);
        store.setSelected(storeMode);
        if (requestedStore != storeMode) modeAction.run();
    }

    void modeAction(Runnable action) {
        modeAction = action;
    }

    void count(int value) {
        LocaleBinding.bind(count, LocalizedMessage.of("modui.count", "count", value), messages.catalog());
    }

    void details(JComponent content) {
        var body = new DetailViewport();
        body.add(content, BorderLayout.NORTH);
        detailScroll.setViewportView(body);
    }

    void detailHeading(String name, String id, Runnable action) {
        title.setText(name);
        title.setToolTipText(name);
        identity.setText(id);
        identity.setToolTipText(id);
        collapseAction = action;
    }

    void footer(JComponent footer) {
        footerHost.removeAll();
        footerHost.add(footer, BorderLayout.CENTER);
        footerHost.setVisible(true);
        revalidate();
    }

    void expanded(boolean value) {
        expanded = value;
        right.setVisible(value);
        var owner = value ? right : left;
        if (footerHost.getParent() != owner) {
            if (footerHost.getParent() != null) footerHost.getParent().remove(footerHost);
            owner.add(footerHost, BorderLayout.SOUTH);
        }
        footerHost.setVisible(footerHost.getComponentCount() != 0);
        revalidate();
        repaint();
    }

    @Override
    public void doLayout() {
        updateGaps();
        var insets = getInsets();
        int x = insets.left, y = insets.top;
        int width = Math.max(0, getWidth() - insets.left - insets.right);
        int height = Math.max(0, getHeight() - insets.top - insets.bottom);
        int gap = UIScale.scale(18);
        int leftWidth = expanded ? Math.max(0, (int) Math.round((width - gap) * .4)) : width;
        if (expanded) {
            int maximum = Math.max(0, width - gap - UIScale.scale(280));
            var cardInsets = left.getInsets();
            leftWidth = Math.min(maximum, Math.max(leftWidth,
                titleLine.getPreferredSize().width + cardInsets.left + cardInsets.right));
        }
        int rightX = x + leftWidth + gap;
        int rightWidth = Math.max(0, width - leftWidth - gap);
        left.setBounds(x, y, leftWidth, height);
        right.setBounds(rightX, y, rightWidth, height);
        var owner = expanded ? right : left;
        var cardInsets = owner.getInsets();
        int footerWidth = Math.max(0, owner.getWidth() - cardInsets.left - cardInsets.right);
        footerHost.setSize(footerWidth, Math.max(1, footerHost.getHeight()));
        footerHost.doLayout();
        heading.setPreferredSize(new Dimension(0, catalogHeader.getPreferredSize().height));
    }

    private void updateGaps() {
        ((BorderLayout) left.getLayout()).setVgap(UIScale.scale(18));
        ((BorderLayout) right.getLayout()).setVgap(UIScale.scale(18));
        ((BorderLayout) leftBody.getLayout()).setVgap(UIScale.scale(8));
        ((BorderLayout) rightBody.getLayout()).setVgap(UIScale.scale(12));
        ((BorderLayout) heading.getLayout()).setHgap(UIScale.scale(8));
        ((BorderLayout) catalogHeader.getLayout()).setVgap(UIScale.scale(12));
        ((BorderLayout) titleLine.getLayout()).setHgap(UIScale.scale(8));
        ((BorderLayout) searchLine.getLayout()).setHgap(UIScale.scale(8));
        ((java.awt.FlowLayout) caption.getLayout()).setHgap(UIScale.scale(8));
        search.putClientProperty("JTextField.placeholderText", messages.text("modui.search"));
        detailScroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(20));
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(UIScale.scale(1000), UIScale.scale(600));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setBorder(new FlatEmptyBorder(18, 22, 18, 22));
    }

    private static final class DetailViewport extends JPanel implements Scrollable {
        DetailViewport() {
            super(new BorderLayout());
            setOpaque(false);
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return UIScale.scale(20);
        }

        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(UIScale.scale(20), visible.height - UIScale.scale(20));
        }

        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
