package gold.debug.windowstolinux.app.ui.mod;

import java.util.*;
import javax.swing.table.AbstractTableModel;

import gold.debug.windowstolinux.app.mod.config.ModManifest;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreCatalog;
import gold.debug.windowstolinux.app.service.mod.store.ModStoreGateway;
import gold.debug.windowstolinux.app.service.mod.store.ModStorePlan;
import gold.debug.windowstolinux.app.ui.error.PageMessagePresenter;

/** Presents exact resolved and unresolved owners without performing dependency resolution. / 展示精确已解析及未解析所有者，不执行依赖解析。 */
final class ModStorePlanModel extends AbstractTableModel {
    /** Current localized display support. / 当前本地化展示支持。 */
    private final PageMessagePresenter messages;

    /** Actual catalog used by the displayed plan. / 展示计划使用的实际目录。 */
    private ModStoreCatalog catalog;

    /** Actual immutable plan. / 实际不可变计划。 */
    private ModStorePlan plan;

    /** Stable owner rows including unresolved conflicts and optional relations. / 包含未解析冲突及可选关系的稳定所有者行。 */
    private List<String> owners = List.of();

    /** Captures display support only. / 仅捕获展示支持。
     * @param messages current message support / 当前消息支持 */
    ModStorePlanModel(PageMessagePresenter messages) {
        this.messages = messages;
    }

    /** Replaces the display with actual service facts. / 用实际服务事实替换展示。
     * @param catalog original full catalog / 原完整目录
     * @param plan original resolved plan / 原解析计划 */
    void display(ModStoreCatalog catalog, ModStorePlan plan) {
        this.catalog = catalog;
        this.plan = plan;
        var rows = new LinkedHashSet<String>();
        if (plan != null) {
            rows.add(plan.selection().root().owner());
            rows.addAll(plan.selection().pins().keySet());
            manifest(plan.selection().root().owner()).ifPresent(value -> value.dependencies().forEach(dependency -> rows.add(dependency.id())));
            plan.selection().optionals().forEach(key -> {
                rows.add(key.consumer());
                rows.add(key.dependency());
            });
            plan.items().forEach(item -> rows.add(item.key().owner()));
            plan.relations().forEach(relation -> rows.add(relation.key().dependency()));
            plan.conflicts().forEach(conflict -> rows.add(conflict.owner()));
        }
        owners = List.copyOf(rows);
        fireTableDataChanged();
    }

    /** Returns one original owner. / 返回一个原所有者。
     * @param row actual model row / 实际模型行
     * @return exact owner / 精确所有者 */
    String owner(int row) {
        return owners.get(row);
    }

    /** Finds actual resolved metadata for an owner. / 查找所有者的实际解析元数据。
     * @param owner exact owner / 精确所有者
     * @return actual item, when resolved / 解析成功时的实际项目 */
    Optional<ModStorePlan.Item> item(String owner) {
        return plan == null ? Optional.empty() : plan.items().stream().filter(item -> item.key().owner().equals(owner)).findFirst();
    }

    /** Returns the user pin or actual service-selected version. / 返回用户固定版本或实际服务选择的版本。
     * @param owner exact owner / 精确所有者
     * @return version or an empty string when unresolved / 版本，未解析时为空串 */
    String version(String owner) {
        if (plan.selection().root().owner().equals(owner)) return plan.selection().root().version();
        return plan.selection().pins().getOrDefault(owner, item(owner).map(item -> item.key().version()).orElse(""));
    }

    /** Finds only metadata matching the displayed exact version. / 仅查找匹配展示精确版本的元数据。
     * @param owner exact owner / 精确所有者
     * @return actual strict manifest / 实际严格清单 */
    Optional<ModManifest> manifest(String owner) {
        return item(owner).filter(item -> item.key().version().equals(version(owner))).map(ModStorePlan.Item::manifest).or(() -> catalog.releases().stream()
            .filter(release -> release.owner().equals(owner) && release.version().equals(version(owner)))
            .map(ModStoreGateway.Release::manifest).findFirst()).or(() -> catalog.installed().stream()
            .filter(installed -> installed.manifest().id().equals(owner) && installed.manifest().version().toString().equals(version(owner)))
            .map(ModStoreCatalog.Installed::manifest).findFirst());
    }

    /** Returns one localized relationship role without changing the plan. / 返回本地化关系角色，不改变计划。
     * @param owner exact owner / 精确所有者
     * @return actual role / 实际角色 */
    private String role(String owner) {
        if (plan.selection().root().owner().equals(owner)) return messages.text("store.main");
        if (item(owner).map(ModStorePlan.Item::requiredForRoot).orElse(false)) return messages.text("store.required");
        if (!plan.valid() && manifest(plan.selection().root().owner()).map(value -> value.dependencies().stream()
            .anyMatch(dependency -> dependency.id().equals(owner) && !dependency.optional())).orElse(false))
            return messages.text("store.requiredPending");
        if (plan.relations().stream().anyMatch(relation -> relation.selected() && !relation.optional() && relation.key().dependency().equals(owner)))
            return messages.text("store.branchRequired");
        return messages.text(item(owner).isPresent() ? "store.optional"
            : plan.selection().optionals().stream().anyMatch(key -> key.dependency().equals(owner)) ? "store.optionalDraft" : "store.unselected");
    }

    /** Returns stable visible row count. / 返回稳定可见行数。
     * @return row count / 行数 */
    @Override
    public int getRowCount() {
        return owners.size();
    }

    /** Returns the declared facts column count. / 返回声明事实列数。
     * @return column count / 列数 */
    @Override
    public int getColumnCount() {
        return 5;
    }

    /** Resolves column headings from the current catalog. / 从当前文案目录解析列标题。
     * @param column model column / 模型列
     * @return current localized heading / 当前本地化标题 */
    @Override
    public String getColumnName(int column) {
        return messages.text(new String[]{"store.owner", "store.role", "store.version", "store.source", "store.installedState"}[column]);
    }

    /** Returns actual plan and catalog facts. / 返回实际计划及目录事实。
     * @param row model row / 模型行
     * @param column model column / 模型列
     * @return plain display fact / 纯文本展示事实 */
    @Override
    public Object getValueAt(int row, int column) {
        String owner = owner(row);
        return switch (column) {
            case 0 -> owner;
            case 1 -> role(owner);
            case 2 -> version(owner);
            case 3 ->
                item(owner).map(item -> messages.text("store.source." + item.source().name().toLowerCase(Locale.ROOT))).orElse("—");
            default -> catalog.installed().stream().filter(value -> value.manifest().id().equals(owner)
                    && value.manifest().version().toString().equals(version(owner))).findFirst().map(value ->
                    messages.text("store.installed." + (value.enabled() ? "enabled" : "disabled") + "." + (value.selected() ? "selected" : "unselected")))
                .orElse(messages.text("store.notInstalled"));
        };
    }
}
