package gold.debug.windowstolinux.app.ui.ai;

import gold.debug.windowstolinux.app.service.contract.AiApplicationFacade;
import gold.debug.windowstolinux.app.service.ai.AiProviderSummary;
import gold.debug.windowstolinux.app.ui.component.*;
import gold.debug.windowstolinux.app.ui.deployment.ReviewContext;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.ai.collaboration.invocation.AiRoleInvocationResult;
import gold.debug.windowstolinux.shared.ai.collaboration.role.ProjectAnalysisRoleContext;
import gold.debug.windowstolinux.shared.model.ai.*;
import javax.swing.*;
import java.util.*;

/** Model inventory, inline purpose selection and explicit model probes. / 模型清单、内联用途选择及显式模型测试。 */
public final class AiPage {
    /** Application boundary. / 应用边界。 */
    private final AiApplicationFacade service;
    /** Previously reviewed source facts. / 已审阅源码事实。 */
    private final ReviewContext reviewContext;
    /** Localized messages. / 本地化消息。 */
    private final PageMessagePresenter messages;
    /** Shared component factory. / 共用控件工厂。 */
    private final DesktopComponentFactory c;
    /** Short-lived unlock input. / 短生命周期解锁输入。 */
    private final JPasswordField masterPassword=new JPasswordField(20);
    /** Advice output. / 建议输出。 */
    private final JTextArea output=DesktopComponentFactory.outputArea();
    /** Explicit request cancellation. / 显式请求取消。 */
    private final JButton cancel;
    /** Page container. / 页面容器。 */
    private final AdvancedOptionsPane panel;
    /** Model list and purpose editor. / 模型列表及用途编辑器。 */
    private final AiModelInventoryPane inventory;
    /** Active request. / 活动请求。 */
    private DesktopTaskHandle task;
    /** Request guard. / 请求保护。 */
    private boolean busy;
    /** Creates the AI settings page. / 创建 AI 设置页面。
     * @param service application boundary / 应用边界
     * @param reviewContext reviewed source context / 已审阅源码上下文
     * @param c component factory / 控件工厂
     * @param messages localized messages / 本地化消息
     */
    public AiPage(AiApplicationFacade service,ReviewContext reviewContext,DesktopComponentFactory c,PageMessagePresenter messages){
        this.service=service;this.reviewContext=reviewContext;this.c=c;this.messages=messages;
        inventory=new AiModelInventoryPane(service,c,messages,this::edit);
        panel=new AdvancedOptionsPane(inventory,c,messages);
        panel.field("field.masterPassword",masterPassword);
        for(var capability:AiCapabilityType.values()){
            JButton test=c.secondaryButton(messages.text("ai.capability.test."+capability.name()));
            test.addActionListener(event->test(capability));panel.addOption(test);
        }
        JButton explain=c.primaryButton(messages.text("button.requestAi"));explain.addActionListener(event->explain());panel.addOption(explain);
        cancel=c.secondaryButton(messages.text("button.cancel"));cancel.setEnabled(false);
        cancel.addActionListener(event->{if(task!=null){task.cancel();output.setText(messages.text("ai.models.cancelling"));}});
        panel.addOption(cancel);output.setRows(16);panel.addOption(new JScrollPane(output));
        panel.addHierarchyListener(event->{if((event.getChangeFlags()&java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0&&panel.isShowing())inventory.refresh();});
    }
    /** Returns the page component. / 返回页面控件。
     * @return page / 页面
     */
    public JPanel panel(){return panel;}
    /** Captures transient UI state. / 捕获临时界面状态。
     * @return state snapshot / 状态快照
     */
    public AiPageState captureState(){return new AiPageState(masterPassword.getPassword(),output.getText(),inventory.capture());}
    /** Restores transient UI state. / 恢复临时界面状态。
     * @param state captured state / 捕获状态
     */
    public void restoreState(AiPageState state){
        char[] password=state.masterPassword();try{masterPassword.setText(new String(password));}finally{Arrays.fill(password,'\0');}
        output.setText(state.output());inventory.restore(state.inventory());
    }
    /** Opens model connection editing. / 打开模型连接编辑。
     * @param existing existing model or absent / 既有模型或空
     */
    private void edit(AiProviderSummary existing){
        if(busy||service==null)return;
        new AiProviderDialog(SwingUtilities.getWindowAncestor(panel),service,c,messages,existing,inventory::refresh).setVisible(true);
    }
    /** Tests only the selected model and capability. / 仅测试所选模型及能力。
     * @param capability tested capability / 测试能力
     */
    private void test(AiCapabilityType capability){
        var model=inventory.current();if(busy||service==null||model==null)return;
        char[] master=masterPassword.getPassword();setBusy(true);output.setText(messages.text("ai.models.testing"));
        task=DesktopTaskExecutor.submit(()->{try{service.testAiCapability(model.profile().id(),capability,master);return true;}finally{Arrays.fill(master,'\0');}},
            done->{setBusy(false);output.setText(messages.text("ai.capability.passed"));inventory.refresh();},
            failure->{setBusy(false);output.setText(messages.safe(failure));});
    }
    /** Requests explicit bounded source explanation. / 请求显式有界源码解释。 */
    private void explain(){
        if(busy||service==null)return;var preparation=reviewContext.reviewedPreparation();
        if(preparation.isEmpty()||preparation.get().assessment().facts().isEmpty()){output.setText(messages.text("ai.analyzeFirst"));return;}
        char[] master=masterPassword.getPassword();setBusy(true);output.setText(messages.text("ai.requesting"));
        task=DesktopTaskExecutor.submit(()->{try{return service.invokeAiRole(ProjectAnalysisRoleContext.from(preparation.get().assessment().facts().orElseThrow()),master);}
            finally{Arrays.fill(master,'\0');}},
            result->{setBusy(false);output.setText(result.map(this::evidenceText).orElseGet(()->messages.text("ai.status.noEnabledProviders")));},
            failure->{boolean cancelled=task!=null&&task.cancelled();setBusy(false);output.setText(cancelled?messages.text("ai.models.cancelled"):messages.safe(failure));});
    }
    /** Locks editing while a request is active. / 请求活动期间锁定编辑。
     * @param value active state / 活动状态
     */
    private void setBusy(boolean value){busy=value;panel.setBusy(value);inventory.setLocked(value);cancel.setEnabled(value);if(!value)task=null;}
    /** Formats validated evidence for display. / 格式化已校验展示证据。
     * @param result invocation result / 调用结果
     * @return readable evidence / 可读证据
     */
    private String evidenceText(AiRoleInvocationResult result){
        var e=result.evidence();
        return messages.text("ai.roleEvidence",Map.of("provider",e.providerId(),"model",e.model(),
            "status",messages.text("ai.invocation."+e.status().name().toLowerCase(Locale.ROOT)),"digest",e.inputSha256(),"validation",e.validationDetail(),
            "decision",e.output().map(v->messages.text("ai.decision."+v.decision().name().toLowerCase(Locale.ROOT))).orElse("-"),
            "summary",e.output().map(v->v.summary()).orElse("-")));
    }
}
