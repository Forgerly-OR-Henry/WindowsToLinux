package gold.debug.windowstolinux.app.ui.deployment.single;

import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.deployment.*;
import javax.swing.*;
import java.awt.*;
import java.util.Hashtable;

/** Standard three-position deployment slider with independent approval policy. / 标准三档部署滑块及独立审批策略。 */
final class DeploymentModeSelector extends JPanel {
    /** Discrete AI intensity control. / 离散 AI 强度控件。 */
    private final JSlider slider=new JSlider(0,2,0);
    /** Agent human-confirmation policy. / Agent 人工确认策略。 */
    private final JComboBox<AgentApprovalMode> approval=new JComboBox<>(AgentApprovalMode.values());
    /** Current mode explanation. / 当前模式说明。 */
    private final JTextArea description=new JTextArea(2,30);
    /** Localized messages. / 本地化消息。 */
    private final PageMessagePresenter messages;
    /** Builds standard controls without invoking models. / 构建标准控件且不调用模型。
     * @param messages localized messages / 本地化消息
     */
    DeploymentModeSelector(PageMessagePresenter messages){
        super(new BorderLayout(10,3));this.messages=messages;setOpaque(false);
        slider.setName("deployment.automation");slider.setMajorTickSpacing(1);slider.setSnapToTicks(true);slider.setPaintTicks(true);slider.setPaintLabels(true);
        slider.setPreferredSize(new Dimension(270,44));slider.setMinimumSize(new Dimension(220,44));
        var labels=new Hashtable<Integer,JLabel>();for(var mode:DeploymentAutomationMode.values())labels.put(mode.ordinal(),new JLabel(messages.text("deployment.mode."+mode.name())));
        slider.setLabelTable(labels);slider.getAccessibleContext().setAccessibleName(messages.text("deployment.mode.label"));
        approval.setName("deployment.approval");approval.setSelectedItem(AgentApprovalMode.AUTOMATIC);
        messages.localize(approval,"deployment.approval.");approval.getAccessibleContext().setAccessibleName(messages.text("deployment.approval.label"));
        JPanel row=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));row.setOpaque(false);row.add(slider);row.add(approval);
        description.setEditable(false);description.setOpaque(false);description.setLineWrap(true);description.setWrapStyleWord(true);
        description.setFont(UIManager.getFont("Label.font"));description.setForeground(UIManager.getColor("Label.foreground"));description.setFocusable(false);
        add(row);add(description,BorderLayout.SOUTH);slider.addChangeListener(event->update());approval.addActionListener(event->update());update();
    }
    /** Returns the selected mode. / 返回所选模式。
     * @return selected deployment mode / 所选部署模式
     */
    DeploymentAutomationMode mode(){return DeploymentAutomationMode.values()[slider.getValue()];}
    /** Returns the selected approval strategy. / 返回所选审批策略。
     * @return confirmation policy / 确认策略
     */
    AgentApprovalMode approval(){return (AgentApprovalMode)approval.getSelectedItem();}
    /** Restores only in-session selections. / 仅恢复会话内选择。
     * @param mode deployment mode / 部署模式
     * @param policy confirmation policy / 确认策略
     */
    void restore(DeploymentAutomationMode mode,AgentApprovalMode policy){slider.setValue(mode.ordinal());approval.setSelectedItem(policy);update();}
    /** Locks both policy controls during an active task. / 活动任务期间锁定两个策略控件。
     * @param busy task activity / 任务活动状态
     */
    void setBusy(boolean busy){slider.setEnabled(!busy);approval.setEnabled(!busy);}
    /** Displays current policy and the manual-review workload warning. / 展示当前策略及人工复审工作量提示。 */
    private void update(){
        approval.setVisible(mode()==DeploymentAutomationMode.AGENT);
        String key=mode()==DeploymentAutomationMode.AGENT&&approval()==AgentApprovalMode.MANUAL_REVIEW
            ?"deployment.approval.manualHint":"deployment.mode.description."+mode().name();
        description.setRows(mode()==DeploymentAutomationMode.AGENT&&approval()==AgentApprovalMode.MANUAL_REVIEW?2:1);
        description.setText(messages.text(key));description.setToolTipText(messages.text(key));revalidate();
    }
}
