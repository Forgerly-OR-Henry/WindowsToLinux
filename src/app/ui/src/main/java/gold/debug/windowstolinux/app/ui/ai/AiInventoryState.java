package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.shared.model.ai.*;
import java.util.*;

/** Nonsecret inline-editor state preserved across appearance changes. / 外观变化时保留的非秘密内联编辑状态。
 * @param purpose open purpose, or empty / 打开的用途或空
 * @param draft unsaved ordered membership / 未保存有序成员
 * @param original saved membership at edit start / 开始编辑时的成员
 * @param selected inventory selection / 清单选择
 * @param selectedPurpose purpose selection / 用途选择
 * @param scroll inventory scroll offset / 清单滚动位置
 * @param purposeScroll purpose scroll offset / 用途滚动位置
 */
public record AiInventoryState(Optional<AiPurposeType> purpose,List<AiPurposeAssignment> draft,
        List<AiPurposeAssignment> original,String selected,String selectedPurpose,int scroll,int purposeScroll) {
    /** Freezes editable lists. / 冻结可编辑列表。
     * @param purpose open purpose / 打开用途
     * @param draft edited members / 编辑成员
     * @param original original members / 原成员
     * @param selected inventory selection / 清单选择
     * @param selectedPurpose purpose selection / 用途选择
     * @param scroll inventory offset / 清单位置
     * @param purposeScroll purpose offset / 用途位置
     */
    public AiInventoryState { draft=List.copyOf(draft); original=List.copyOf(original); }
    /** Creates a closed editor state. / 创建关闭的编辑状态。
     * @return initial state / 初始状态
     */
    public static AiInventoryState empty() { return new AiInventoryState(Optional.empty(),List.of(),List.of(),"","",0,0); }
}
