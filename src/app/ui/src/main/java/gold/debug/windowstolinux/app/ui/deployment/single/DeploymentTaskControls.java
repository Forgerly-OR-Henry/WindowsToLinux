package gold.debug.windowstolinux.app.ui.deployment.single;
import gold.debug.windowstolinux.app.service.contract.AutomaticDeploymentApplicationFacade;
import gold.debug.windowstolinux.app.ui.component.DesktopTaskExecutor;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.agent.AgentTaskCommandAction;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.function.Consumer;

/** Explicit pause/resume/cancel controls and persisted nonsecret task history. / 显式暂停、恢复、取消控件及持久化非秘密任务历史。 */
final class DeploymentTaskControls extends JPanel {
    /** Narrow task facade. / 窄任务门面。 */
    private final AutomaticDeploymentApplicationFacade service;
    /** Localized UI messages. / 本地化界面消息。 */
    private final PageMessagePresenter messages;
    /** Main log output. / 主日志输出。 */
    private final Consumer<String> output;
    /** Pause at the next safe boundary. / 在下一个安全边界暂停。 */
    private final JButton pause=new JButton();
    /** Resume the same snapshot. / 使用同一快照恢复。 */
    private final JButton resume=new JButton();
    /** Explicit adoption of edited model settings. / 显式采用编辑后的模型设置。 */
    private final JButton refreshModels=new JButton();
    /** Request safe termination. / 请求安全终止。 */
    private final JButton cancel=new JButton();
    /** Current state and pause reason. / 当前状态及暂停原因。 */
    private final JLabel state=new JLabel();
    /** Active task identifier. / 活动任务标识。 */
    private String taskId;
    /** Polls only process-local state while a task is active. / 仅在任务活动时轮询进程内状态。 */
    private final javax.swing.Timer timer;
    /** Supplies a fresh temporary unlock buffer on the UI thread. / 在界面线程提供新的临时解锁缓冲区。 */
    private final java.util.function.Supplier<char[]> credentials;
    /** Builds controls without starting work or polling. / 构建控件，不启动工作或轮询。
     * @param service narrow task facade / 窄任务门面
     * @param messages localized messages / 本地化消息
     * @param credentials fresh unlock buffer / 新解锁缓冲区
     * @param output visible log / 可见日志
     */
    DeploymentTaskControls(AutomaticDeploymentApplicationFacade service,PageMessagePresenter messages,Consumer<String> output,java.util.function.Supplier<char[]> credentials){
        super(new FlowLayout(FlowLayout.LEFT,6,0));this.service=service;this.messages=messages;this.output=output;this.credentials=credentials;setOpaque(false);
        pause.setText(messages.text("deployment.task.pause"));resume.setText(messages.text("deployment.task.resume"));cancel.setText(messages.text("deployment.task.cancel"));
        refreshModels.setText(messages.text("deployment.task.refreshModels"));refreshModels.addActionListener(e->command(AgentTaskCommandAction.REFRESH_MODELS));
        pause.addActionListener(e->command(AgentTaskCommandAction.PAUSE));resume.addActionListener(e->command(AgentTaskCommandAction.RESUME));cancel.addActionListener(e->command(AgentTaskCommandAction.CANCEL));
        JButton history=new JButton(messages.text("deployment.task.history"));history.addActionListener(e->history());
        add(pause);add(resume);add(refreshModels);add(cancel);add(state);add(history);timer=new javax.swing.Timer(400,e->refresh());finish();
    }
    /** Starts state observation for the selected Agent task. / 开始观察所选 Agent 任务状态。
     * @param id task identifier / 任务标识
     */
    void begin(String id){taskId=id;state.setText(messages.text("deployment.task.starting"));timer.start();}
    /** Stops task-owned polling after completion or failure. / 完成或失败后停止任务所属轮询。 */
    void finish(){if(timer!=null)timer.stop();taskId=null;pause.setEnabled(false);resume.setEnabled(false);refreshModels.setEnabled(false);cancel.setEnabled(false);state.setText("");}
    /** Dispatches an explicit user control request. / 派发显式用户控制请求。
     * @param action selected transition / 所选转换
     */
    private void command(AgentTaskCommandAction action){if(taskId==null)return;try{service.controlDeployment(taskId,action);refresh();}catch(Exception failure){output.accept(messages.safe(failure));}}
    /** Reflects safe boundary state without reading secrets or calling a model. / 反映安全边界状态，不读取秘密或调用模型。 */
    private void refresh(){
        if(taskId==null)return;
        try{var snapshot=service.deploymentTaskState(taskId);String current=snapshot.getOrDefault("state","");
            pause.setEnabled(current.equals("RUNNING"));resume.setEnabled(current.equals("PAUSED"));refreshModels.setEnabled(current.equals("PAUSED"));cancel.setEnabled(Set.of("RUNNING","PAUSED","PAUSE_REQUESTED").contains(current));
            state.setText(current.isEmpty()?messages.text("deployment.task.starting"):messages.text("deployment.task.state."+current));
            String reason=snapshot.getOrDefault("reason","");state.setToolTipText(reason.isEmpty()?null:messages.text("deployment.task.reason."+reason));
        }catch(Exception failure){timer.stop();output.accept(messages.safe(failure));}
    }
    /** Opens bounded nonsecret task history and event details. / 打开有界非秘密任务历史及事件详情。 */
    private void history(){
        DesktopTaskExecutor.run(service::deploymentTaskHistory,rows->{
            DefaultListModel<String> list=new DefaultListModel<>();rows.forEach(row->list.addElement(row.get("started_at")+" · "+row.get("server_id")+" · "+row.get("state")));
            JList<String> tasks=new JList<>(list);JTextArea details=new JTextArea(18,72);details.setEditable(false);details.setLineWrap(true);details.setWrapStyleWord(true);
            tasks.addListSelectionListener(event->{if(event.getValueIsAdjusting()||tasks.getSelectedIndex()<0)return;
                String id=rows.get(tasks.getSelectedIndex()).get("id");DesktopTaskExecutor.run(()->service.deploymentTaskEvents(id),
                    events->details.setText(events.stream().map(value->value.get("created_at")+" "+value.get("event_type")+" "+value.get("action_id")+" "+value.get("detail")).collect(java.util.stream.Collectors.joining("\n"))),
                    failure->details.setText(messages.safe(failure)));});
            JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(tasks),new JScrollPane(details));split.setResizeWeight(.35);split.setPreferredSize(new Dimension(900,480));
            JButton inspect=new JButton(messages.text("deployment.task.inspect"));
            inspect.addActionListener(event->{if(tasks.getSelectedIndex()<0)return;String id=rows.get(tasks.getSelectedIndex()).get("id");
                char[] master=credentials.get();inspect.setEnabled(false);
                DesktopTaskExecutor.run(()->{try{return service.inspectDeploymentTask(id,master);}finally{Arrays.fill(master,'\0');}},
                    observed->{inspect.setEnabled(true);details.append("\n"+messages.text("deployment.agent.unresolvedOutcome")+"\n"+observed);},
                    failure->{inspect.setEnabled(true);details.append("\n"+messages.safe(failure));});});
            JPanel historyPanel=new JPanel(new BorderLayout(0,8));historyPanel.add(split,BorderLayout.CENTER);historyPanel.add(inspect,BorderLayout.SOUTH);
            JOptionPane.showMessageDialog(this,historyPanel,messages.text("deployment.task.history"),JOptionPane.PLAIN_MESSAGE);
        },failure->output.accept(messages.safe(failure)));
    }
}
