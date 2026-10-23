package gold.debug.windowstolinux.app.ui.display;

import java.awt.Component;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.HierarchyListener;
import java.beans.PropertyChangeListener;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatEmptyBorder;
import com.formdev.flatlaf.ui.FlatScrollPaneUI;

/** Applies the card viewport convention to host and Mod Swing lists alike. / 为宿主及 Mod 的 Swing 清单统一应用卡片视口规范。 */
public final class DesktopScrollPaneUi extends FlatScrollPaneUI {
    /** Explicit detail-scroll opt-in; ordinary panes retain their original surface. / 详情滚动区显式接入，普通滚动面板保留原表面。 */
    public static final String CARD_SURFACE = "desktop.card.contentSurface";

    private final java.util.Map<JComponent, java.awt.Color> surfaceBackgrounds = new java.util.IdentityHashMap<>();

    private JViewport observedViewport;

    private Component observedView;

    private JScrollBar markedBar;

    private Object originalBarMarker;

    private int originalVerticalPolicy, originalHorizontalPolicy;

    private Border originalViewportBorder, cardViewportBorder;

    private boolean styled, changing;

    private final ContainerAdapter contents = new ContainerAdapter() {
        @Override
        public void componentAdded(ContainerEvent event) {
            observeView();
        }

        @Override
        public void componentRemoved(ContainerEvent event) {
            observeView();
        }
    };

    private final HierarchyListener hierarchy = event -> reconcile();

    private final PropertyChangeListener viewProperties = event -> {
        switch (event.getPropertyName()) {
            case "name", "List.isFileList", DesktopCardSupport.CARDS, DesktopListCard.OUTLINED -> reconcile();
            default -> {
            }
        }
    };

    private final PropertyChangeListener properties = event -> {
        if (changing) return;
        switch (event.getPropertyName()) {
            case "viewport" -> observeViewport();
            case "verticalScrollBar" -> reconcile();
            case "horizontalScrollBar", CARD_SURFACE -> reconcile();
            case "componentOrientation" -> {
                if (styled) cardViewportBorder = border();
                reconcile();
            }
            case "viewportBorder" -> {
                if (styled) {
                    originalViewportBorder = scrollpane.getViewportBorder();
                    cardViewportBorder = border();
                    reconcile();
                }
            }
            case "verticalScrollBarPolicy" -> {
                if (styled) {
                    originalVerticalPolicy = scrollpane.getVerticalScrollBarPolicy();
                    reconcile();
                }
            }
            case "horizontalScrollBarPolicy" -> {
                if (styled) {
                    originalHorizontalPolicy = scrollpane.getHorizontalScrollBarPolicy();
                    reconcile();
                }
            }
            default -> {
            }
        }
    };

    public static ComponentUI createUI(JComponent component) {
        return new DesktopScrollPaneUi();
    }

    @Override
    public void installUI(JComponent component) {
        super.installUI(component);
        scrollpane.addPropertyChangeListener(properties);
        scrollpane.addHierarchyListener(hierarchy);
        observeViewport();
    }

    private void observeViewport() {
        if (observedViewport != null) observedViewport.removeContainerListener(contents);
        observedViewport = scrollpane.getViewport();
        if (observedViewport != null) observedViewport.addContainerListener(contents);
        observeView();
    }

    private void observeView() {
        if (observedView != null) observedView.removePropertyChangeListener(viewProperties);
        observedView = observedViewport == null ? null : observedViewport.getView();
        if (observedView != null) observedView.addPropertyChangeListener(viewProperties);
        reconcile();
    }

    private Border border() {
        int gap = DesktopCardSupport.VIEWPORT_GAP;
        boolean leftToRight = scrollpane.getComponentOrientation().isLeftToRight();
        return BorderFactory.createCompoundBorder(originalViewportBorder,
            new FlatEmptyBorder(0, leftToRight ? 0 : gap, 0, leftToRight ? gap : 0));
    }

    private void reconcile() {
        if (changing || scrollpane == null) return;
        changing = true;
        try {
            refreshSurface();
            if (!DesktopCardSupport.isCardView(observedView)) {
                restore();
                return;
            }
            if (!styled) {
                originalVerticalPolicy = scrollpane.getVerticalScrollBarPolicy();
                originalHorizontalPolicy = scrollpane.getHorizontalScrollBarPolicy();
                originalViewportBorder = scrollpane.getViewportBorder();
                cardViewportBorder = border();
                styled = true;
            }
            scrollpane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
            scrollpane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            if (scrollpane.getViewportBorder() != cardViewportBorder) scrollpane.setViewportBorder(cardViewportBorder);
            if (markedBar != scrollpane.getVerticalScrollBar()) {
                unmark();
                markedBar = scrollpane.getVerticalScrollBar();
                if (markedBar != null) {
                    originalBarMarker = markedBar.getClientProperty(DesktopCardSupport.SCROLLBAR);
                    markedBar.putClientProperty(DesktopCardSupport.SCROLLBAR, true);
                }
            }
        } finally {
            changing = false;
        }
    }

    /** Changes only colors; range models, viewport position and list geometry remain untouched. / 仅改颜色，保留范围模型、视口位置及清单几何。 */
    private void refreshSurface() {
        boolean inventory = observedView instanceof JComponent view
            && Boolean.TRUE.equals(view.getClientProperty(DesktopListCard.OUTLINED));
        if (!inventory && !Boolean.TRUE.equals(scrollpane.getClientProperty(CARD_SURFACE))) {
            restoreSurface();
            return;
        }
        var background = UIManager.getColor("List.cardBackground");
        surface(scrollpane, background);
        surface(observedViewport, background);
        surface(scrollpane.getVerticalScrollBar(), background);
        surface(scrollpane.getHorizontalScrollBar(), background);
        if (inventory) surface((JComponent) observedView, background);
    }

    private void surface(JComponent component, java.awt.Color background) {
        if (component == null) return;
        surfaceBackgrounds.putIfAbsent(component, component.getBackground());
        component.setBackground(background);
    }

    private void restoreSurface() {
        surfaceBackgrounds.forEach(JComponent::setBackground);
        surfaceBackgrounds.clear();
    }

    private void unmark() {
        if (markedBar == null) return;
        if (Boolean.TRUE.equals(markedBar.getClientProperty(DesktopCardSupport.SCROLLBAR)))
            markedBar.putClientProperty(DesktopCardSupport.SCROLLBAR, originalBarMarker);
        markedBar = null;
        originalBarMarker = null;
    }

    private void restore() {
        unmark();
        if (!styled) return;
        if (scrollpane.getVerticalScrollBarPolicy() == JScrollPane.VERTICAL_SCROLLBAR_ALWAYS)
            scrollpane.setVerticalScrollBarPolicy(originalVerticalPolicy);
        if (scrollpane.getHorizontalScrollBarPolicy() == JScrollPane.HORIZONTAL_SCROLLBAR_NEVER)
            scrollpane.setHorizontalScrollBarPolicy(originalHorizontalPolicy);
        if (scrollpane.getViewportBorder() == cardViewportBorder) scrollpane.setViewportBorder(originalViewportBorder);
        originalViewportBorder = cardViewportBorder = null;
        styled = false;
    }

    @Override
    public void uninstallUI(JComponent component) {
        scrollpane.removePropertyChangeListener(properties);
        scrollpane.removeHierarchyListener(hierarchy);
        if (observedViewport != null) observedViewport.removeContainerListener(contents);
        if (observedView != null) observedView.removePropertyChangeListener(viewProperties);
        observedViewport = null;
        observedView = null;
        changing = true;
        try {
            restore();
            restoreSurface();
        } finally {
            changing = false;
        }
        super.uninstallUI(component);
    }
}
