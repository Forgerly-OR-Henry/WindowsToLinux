package gold.debug.windowstolinux.app.ui.setting;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.*;

import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.control.DesktopCardList;
import gold.debug.windowstolinux.app.ui.control.DesktopPartFactory;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;
import gold.debug.windowstolinux.app.ui.language.LocaleBinding;
import gold.debug.windowstolinux.app.ui.task.DesktopTaskRunner;

/** Separate platform and Mod permutations with commit-before-render semantics. / 分区入口排列，提交后才渲染。 */
public final class NavigationOrderPane extends JPanel implements AutoCloseable, Scrollable, gold.debug.windowstolinux.app.ui.control.PendingCardEdits {
    /** Independently persisted platform and Mod order sections. / 独立持久化的平台及 Mod 顺序分区。 */
    private final List<Section<?>> sections = new ArrayList<>();

    /** Themed controls shared by order sections. / 顺序分区共用的主题控件。 */
    private final DesktopPartFactory components;

    /** Localized labels and safe persistence failure presentation. / 本地化标签及安全持久化失败呈现。 */
    private final PageMessagePresenter messages;

    /** Privacy and disposal state blocking section operations. / 阻止分区操作的隐私及销毁状态。 */
    private boolean locked = true, closed;

    /** Creates the appearance-owned editor. / 创建外观所属编辑器。
     * @param components current controls / 当前控件
     * @param messages safe localized messages / 安全本地化消息 */
    public NavigationOrderPane(DesktopPartFactory components, PageMessagePresenter messages) {
        super();
        this.components = components;
        this.messages = messages;
        setName("navigation.order");
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
    }

    /** Adds one independently persisted section. / 添加独立持久化分区。
     * @param key localized section key / 本地化分区键
     * @param load confirmed complete preference / 已确认完整偏好
     * @param visible projection retaining current visibility / 保留当前可见性的投影
     * @param defaults default permutation / 默认排列
     * @param label display text / 展示文字
     * @param save original transaction / 原事务
     * @param applied committed display update / 已提交展示更新
     * @param <T> stable identity / 稳定身份 */
    public <T> void section(String key, Callable<List<T>> load, Function<List<T>, List<T>> visible,
                            Function<List<T>, List<T>> defaults, Function<T, String> label,
                            Save<T> save, Consumer<List<T>> applied) {
        var section = new Section<>(key, load, visible, defaults, label, save, applied);
        sections.add(section);
        add(section);
        section.reload();
        revalidate();
    }

    /** Reloads when entering appearance or publication changes. / 进入外观或发布变化时重读。 */
    public void refresh() {
        if (locked || closed) return;
        sections.forEach(section -> section.session.savePending().thenAccept(ok -> {
            if (ok) section.reload();
        }));
    }

    @Override
    public boolean hasPendingEdits() {
        return sections.stream().anyMatch(section -> section.session.hasPendingEdits());
    }

    @Override
    public java.util.concurrent.CompletionStage<Boolean> saveBeforeLeave() {
        java.util.concurrent.CompletionStage<Boolean> result = java.util.concurrent.CompletableFuture.completedFuture(true);
        for (var section : sections)
            result = result.thenCompose(ok -> ok ? section.session.saveBeforeLeave()
                : java.util.concurrent.CompletableFuture.completedFuture(false));
        return result;
    }

    /** Measures the natural card stack. / 测量卡片栈的自然尺寸。
     * @return preferred viewport dimensions / 首选视口尺寸 */
    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    /** Keeps cards within the appearance viewport. / 将卡片保持在外观视口内。
     * @return always true / 始终为真 */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    /** Allows vertical scrolling through long sections. / 允许长分区纵向滚动。
     * @return false / 假 */
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    /** Scrolls by one card. / 按一个卡片高度滚动。
     * @param visible current viewport / 当前视口
     * @param orientation scroll axis / 滚动轴
     * @param direction scroll direction / 滚动方向
     * @return scaled card height / 缩放后的卡片高度 */
    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return UIScale.scale(48);
    }

    /** Scrolls by a viewport. / 按一个视口高度滚动。
     * @param visible current viewport / 当前视口
     * @param orientation scroll axis / 滚动轴
     * @param direction scroll direction / 滚动方向
     * @return viewport height / 视口高度 */
    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return visible.height;
    }

    /** Applies privacy and invalidates responses crossing lock generations. / 应用隐私并作废跨锁代响应。
     * @param value current lock / 当前锁定 */
    public void setLocked(boolean value) {
        locked = value;
        sections.forEach(section -> {
            section.epoch++;

            section.session.setLocked(value);
            section.controls();
        });
        if (!locked) refresh();
    }

    /** Disables sections and invalidates pending order callbacks. / 禁用分区并使待处理顺序回调失效。 */
    @Override
    public void close() {
        closed = true;
        sections.forEach(section -> {
            section.epoch++;
            section.render();
        });
    }

    /** Narrow transaction accepting a complete snapshot and visible permutation. / 接收完整快照和可见排列的窄事务。
     * @param <T> identity type / 身份类型 */
    @FunctionalInterface
    public interface Save<T> {
        /** Commits one permutation. / 提交一次排列。
         * @param expected confirmed order / 已确认顺序
         * @param requested visible permutation / 可见排列
         * @return committed full order / 已提交完整顺序
         * @throws Exception if commit cannot be confirmed / 无法确认提交时 */
        List<T> save(List<T> expected, List<T> requested) throws Exception;
    }

    /** Owns one order snapshot and its independently committed visible projection. / 持有一个顺序快照及其独立提交的可见投影。
     * @param <T> stable entrance identity / 稳定入口身份 */
    private final class Section<T> extends JPanel {
        /** Reads the complete confirmed order. / 读取完整已确认顺序。 */
        private final Callable<List<T>> load;

        /** Visible and default order projections retaining stable identities. / 保留稳定身份的可见及默认顺序投影。 */
        private final Function<List<T>, List<T>> visible, defaults;

        /** Original snapshot-aware order transaction. / 原快照校验顺序事务。 */
        private final Save<T> save;

        /** Updates the shell after a confirmed read or commit. / 确认读取或提交后更新外壳。 */
        private final Consumer<List<T>> applied;

        /** Current visible identities in confirmed order. / 已确认顺序中的当前可见身份。 */
        private final DesktopCardList<T> list = new DesktopCardList<>(new DefaultListModel<>(), 48);

        /** Compact empty state instead of an unused list box. / 紧凑空状态，替代闲置列表框。 */
        private final JLabel empty;

        /** Width-following list viewport. / 随宽度变化的列表视口。 */
        private final JScrollPane scroll;

        /** Move-up, move-down and reset controls for this section. / 本分区的上移、下移及重置控件。 */
        private final JButton up, down, reset;

        /** Localized persistence status and safe failure text. / 本地化持久化状态及安全失败文字。 */
        private final JLabel status = new JLabel();

        /** Last complete order confirmed by persistence. / 持久化上次确认的完整顺序。 */
        private final gold.debug.windowstolinux.app.ui.control.CardOrderSession<T> session;

        /** In-flight operation and confirmed-load readiness flags. / 执行中操作及已确认加载就绪标志。 */
        private boolean busy, loaded;

        /** Generation invalidating callbacks from earlier privacy states. / 作废早先隐私状态回调的代数。 */
        private long epoch;

        /** Binds a section to the existing persistence transaction and shell update. / 将分区绑定到既有持久化事务及外壳更新。
         * @param key localized section label / 本地化分区标签
         * @param load complete confirmed order reader / 完整已确认顺序读取器
         * @param visible current visible projection / 当前可见投影
         * @param defaults default visible order / 默认可见顺序
         * @param label identity display text / 身份展示文字
         * @param save snapshot-aware transaction / 快照校验事务
         * @param applied confirmed shell update / 已确认外壳更新 */
        private Section(String key, Callable<List<T>> load, Function<List<T>, List<T>> visible,
                        Function<List<T>, List<T>> defaults, Function<T, String> label, Save<T> save,
                        Consumer<List<T>> applied) {
            super(new BorderLayout());
            this.load = load;
            this.visible = visible;
            this.defaults = defaults;
            this.save = save;
            this.applied = applied;
            session = new gold.debug.windowstolinux.app.ui.control.CardOrderSession<>(this, messages,
                (expected, requested) -> save.save(expected, visible.apply(requested)), load, applied, this::render);
            setName(key);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
            var editor = components.card(new BorderLayout(0, UIScale.scale(12)));
            editor.add(messages.bind(new JLabel(), key), BorderLayout.NORTH);
            list.setName(key + ".list");
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setOpaque(false);
            list.setFixedCellHeight(UIScale.scale(56));
            list.setVisibleRowCount(1);
            list.setCellRenderer((view, value, index, selected, focused) -> {
                var text = new JLabel(value == null ? "" : label.apply(value));
                text.putClientProperty("html.disable", true);
                text.setForeground(view.isEnabled() ? view.getForeground() : UIManager.getColor("Label.disabledForeground"));
                return text;
            });
            components.cardList(list, true);
            list.reorder(this::commit, () -> !locked && !closed && !busy && !session.busy() && loaded);
            scroll = new JScrollPane(list);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(56));
            empty = messages.bind(new JLabel(), "navigation.empty");
            empty.setName(key + ".empty");
            empty.setForeground(UIManager.getColor("Label.disabledForeground"));
            empty.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
            var body = components.transparent(new BorderLayout());
            body.add(scroll);
            body.add(empty, BorderLayout.SOUTH);
            editor.add(body, BorderLayout.CENTER);
            up = messages.bind(components.secondaryButton(""), "navigation.up");
            up.setName(key + ".up");
            down = messages.bind(components.secondaryButton(""), "navigation.down");
            down.setName(key + ".down");
            reset = messages.bind(components.secondaryButton(""), "navigation.reset");
            reset.setName(key + ".reset");
            up.addActionListener(event -> move(-1));
            down.addActionListener(event -> move(1));
            for (int delta : new int[]{-1, 1}) {
                String action = delta < 0 ? "alt UP" : "alt DOWN";
                list.getInputMap().put(KeyStroke.getKeyStroke(action), action);
                list.getActionMap().put(action, new AbstractAction() {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        move(delta);
                    }
                });
            }
            reset.addActionListener(event -> commit(defaults.apply(visible.apply(session.draft()))));
            var buttons = components.transparent(new FlowLayout(FlowLayout.LEADING, 6, 0));
            buttons.add(up);
            buttons.add(down);
            buttons.add(reset);
            var footer = components.transparent(new BorderLayout(0, 6));
            footer.add(buttons, BorderLayout.NORTH);
            footer.add(status);
            editor.add(footer, BorderLayout.SOUTH);
            add(editor);
            list.addListSelectionListener(event -> controls());
            controls();
        }

        /** Keeps a section at its content height. / 将分区保持在内容高度。
         * @return flexible width and natural height / 可变宽度及自然高度 */
        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        /** Allows a narrow viewport without horizontal overflow. / 允许窄视口而不横向溢出。
         * @return flexible width and natural height / 可变宽度及自然高度 */
        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        /** Requests a bounded swap of the selected visible entrance. / 请求对所选可见入口进行有界交换。
         * @param delta relative destination offset / 相对目标偏移 */
        private void move(int delta) {
            var requested = new ArrayList<>(visible.apply(session.draft()));
            int index = list.getSelectedIndex();
            if (index < 0 || index + delta < 0 || index + delta >= requested.size()) return;
            java.util.Collections.swap(requested, index, index + delta);
            commit(List.copyOf(requested));
        }

        /** Loads confirmed order and rejects responses crossing privacy generations. / 加载已确认顺序并拒绝跨隐私代的响应。 */
        private void reload() {
            if (locked || closed || busy || session.busy() || session.hasPendingEdits()) return;
            busy = true;
            long generation = epoch;
            controls();
            DesktopTaskRunner.run(load, value -> {
                busy = false;
                if (closed || locked) return;
                if (epoch != generation) {
                    reload();
                    return;
                }
                loaded = true;
                session.accept(value);
                applied.accept(session.confirmed());
                render();
            }, failure -> {
                busy = false;
                if (closed || locked) return;
                loaded = false;
                LocaleBinding.unbind(status);
                status.setText(messages.safe(failure));
                controls();
            });
        }

        /** Commits against the original full snapshot before applying visible order. / 针对原完整快照提交后才应用可见顺序。
         * @param requested visible identity permutation / 可见身份排列 */
        private void commit(List<T> requested) {
            if (locked || closed || busy || session.busy() || !loaded) return;
            var current = visible.apply(session.draft());
            if (requested.size() != current.size() || !new java.util.HashSet<>(current).equals(new java.util.HashSet<>(requested)))
                throw new IllegalArgumentException("Invalid visible permutation");
            var remaining = requested.iterator();
            var full = session.draft().stream().map(id -> current.contains(id) ? remaining.next() : id).toList();
            session.edit(full, true);
        }

        /** Renders confirmed visible identities while retaining selection. / 渲染已确认可见身份并保留选择。 */
        private void render() {
            T selected = list.getSelectedValue();
            var model = new DefaultListModel<T>();
            if (!closed) visible.apply(session.draft()).forEach(model::addElement);
            list.setModel(model);
            list.setVisibleRowCount(Math.max(1, Math.min(5, model.size())));
            list.setSelectedValue(selected, true);
            revalidate();
            controls();
        }

        /** Admits order edits only after an unlocked idle confirmed load. / 仅在解锁、空闲且确认加载后允许顺序编辑。 */
        private void controls() {
            boolean enabled = !locked && !closed && !busy && !session.busy() && loaded;
            int index = list.getSelectedIndex();
            list.setEnabled(enabled);
            if (locked || closed) list.cancelInteraction();
            empty.setVisible(loaded && list.getModel().getSize() == 0);
            up.setEnabled(enabled && index > 0);
            down.setEnabled(enabled && index >= 0 && index + 1 < list.getModel().getSize());
            reset.setEnabled(enabled && list.getModel().getSize() > 0);
        }

    }
}
