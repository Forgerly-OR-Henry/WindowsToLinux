package gold.debug.windowstolinux.app.ui.deployment.automatic;

import com.formdev.flatlaf.ui.FlatSliderUI;
import com.formdev.flatlaf.util.UIScale;
import gold.debug.windowstolinux.app.ui.component.DesktopIcons;
import gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter;
import gold.debug.windowstolinux.shared.model.deployment.*;
import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.View;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.ActionEvent;
import java.util.Hashtable;

/** Compact mode picker with a popover slider and illustrated approval choices. / 带弹出滑块及审批图标的紧凑模式选择器。 */
final class DeploymentModeSelector extends JPanel {
    /** Discrete AI intensity control. / 离散 AI 强度控件。 */
    private final JSlider slider=new ModeSlider();
    /** Collapsed control showing only the current mode. / 收起时仅显示当前模式的控件。 */
    private final JButton modeButton=new JButton();
    /** Floating mode selection surface. / 浮动模式选择面板。 */
    private final JPopupMenu modePopup=new JPopupMenu();
    /** Current mode heading inside the popover. / 弹出面板内的当前模式标题。 */
    private final JLabel modeHeading=new JLabel("",SwingConstants.CENTER);
    /** Agent human-confirmation policy. / Agent 人工确认策略。 */
    private final JComboBox<AgentApprovalMode> approval=new JComboBox<>(AgentApprovalMode.values());
    /** Current mode explanation. / 当前模式说明。 */
    private final JTextPane description=new JTextPane();
    /** Localized messages. / 本地化消息。 */
    private final PageMessagePresenter messages;
    /** Builds standard controls without invoking models. / 构建标准控件且不调用模型。
     * @param messages localized messages / 本地化消息
     */
    DeploymentModeSelector(PageMessagePresenter messages){
        super(new BorderLayout(10,3));this.messages=messages;setOpaque(false);
        slider.setName("deployment.automation");slider.setMajorTickSpacing(1);slider.setSnapToTicks(true);slider.setPaintTicks(false);slider.setPaintLabels(true);
        slider.setPreferredSize(UIScale.scale(new Dimension(220,48)));slider.setMinimumSize(UIScale.scale(new Dimension(200,48)));
        var labels=new Hashtable<Integer,JLabel>();for(var mode:DeploymentAutomationMode.values())labels.put(mode.ordinal(),new JLabel(messages.text("deployment.mode."+mode.name())));
        slider.setLabelTable(labels);slider.getAccessibleContext().setAccessibleName(messages.text("deployment.mode.label"));
        approval.setName("deployment.approval");approval.setSelectedItem(AgentApprovalMode.AUTOMATIC);
        messages.localize(approval,"deployment.approval.");approval.getAccessibleContext().setAccessibleName(messages.text("deployment.approval.label"));
        installApprovalIcons();
        modeButton.setName("deployment.modePicker");modeButton.putClientProperty("JButton.buttonType","roundRect");
        modeButton.setMargin(new Insets(5,12,5,10));modeButton.setHorizontalTextPosition(SwingConstants.LEFT);
        modeButton.setIcon(DesktopIcons.icon("chevron-down",12,modeButton::getForeground));modeButton.setIconTextGap(UIScale.scale(8));
        Dimension modeButtonSize=new Dimension();
        for(var mode:DeploymentAutomationMode.values()){
            modeButton.setText(messages.text("deployment.mode."+mode.name()));
            Dimension preferred=modeButton.getPreferredSize();
            modeButtonSize.width=Math.max(modeButtonSize.width,preferred.width);
            modeButtonSize.height=Math.max(modeButtonSize.height,preferred.height);
        }
        modeButton.setPreferredSize(modeButtonSize);
        modeButton.addActionListener(event->togglePopup());
        JPanel row=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0));row.setOpaque(false);row.add(modeButton);row.add(approval);
        description.setEditable(false);description.setOpaque(false);description.setBorder(null);description.setMargin(new Insets(0,0,0,0));
        description.setFont(UIManager.getFont("Label.font"));description.setForeground(UIManager.getColor("Label.foreground"));description.setFocusable(false);
        modeHeading.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        modeHeading.setForeground(UIManager.getColor("Component.focusColor"));
        JPanel heading=new JPanel(new BorderLayout(0,2));heading.setOpaque(false);heading.add(modeHeading,BorderLayout.NORTH);heading.add(description);
        JPanel contents=new JPanel(new BorderLayout(0,2));contents.setOpaque(false);contents.setBorder(BorderFactory.createEmptyBorder(4,6,2,6));
        contents.add(heading,BorderLayout.NORTH);contents.add(slider);modePopup.setLayout(new BorderLayout());modePopup.add(contents);modePopup.setName("deployment.modePopup");
        modePopup.getAccessibleContext().setAccessibleName(messages.text("deployment.mode.label"));
        installPopupInteractions();
        add(row);slider.addChangeListener(event->update());
        approval.addActionListener(event->update());update();
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
    void setBusy(boolean busy){if(busy)hidePopup();modeButton.setEnabled(!busy);slider.setEnabled(!busy);approval.setEnabled(!busy);}

    /** Removes the floating window when its page is removed. / 页面移除时清理浮动窗口。 */
    @Override public void removeNotify(){hidePopup();super.removeNotify();}

    /** Adds the reference hand, shield and warning shield while preserving localized text. / 添加参考界面的手掌、盾牌及警示盾牌，并保留本地化文字。 */
    private void installApprovalIcons(){
        var renderer=approval.getRenderer();
        var icons=new java.util.EnumMap<AgentApprovalMode,Icon>(AgentApprovalMode.class);
        icons.put(AgentApprovalMode.MANUAL_REVIEW,DesktopIcons.icon("hand",16,approval::getForeground));
        icons.put(AgentApprovalMode.AUTOMATIC,DesktopIcons.icon("shield",16,approval::getForeground));
        icons.put(AgentApprovalMode.FULL_CONTROL,DesktopIcons.icon("shield-alert",16,()->
            approval.isEnabled()?new Color(234,108,36):UIManager.getColor("Label.disabledForeground")));
        approval.setRenderer((list,value,index,selected,focus)->{
            JLabel label=(JLabel)renderer.getListCellRendererComponent(list,value,index,selected,focus);
            label.setIcon(icons.get(value));label.setIconTextGap(UIScale.scale(8));return label;
        });
    }

    /** Opens the slider above the compact control or dismisses the existing popover. / 在紧凑控件上方展开滑块，或关闭已打开的面板。 */
    private void togglePopup(){
        if(modePopup.isVisible()){hidePopup();return;}
        if(!modeButton.isEnabled())return;
        SwingUtilities.updateComponentTreeUI(modePopup);
        modePopup.setBackground(UIManager.getColor("ComboBox.popupBackground"));
        modeHeading.setForeground(UIManager.getColor("Component.focusColor"));
        modeHeading.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        update();
        modePopup.show(modeButton,0,-modePopup.getPreferredSize().height-UIScale.scale(6));
        SwingUtilities.invokeLater(()->{if(modePopup.isVisible())slider.requestFocusInWindow();});
    }

    /** Dismisses the panel. / 关闭面板。 */
    private void hidePopup(){modePopup.setVisible(false);}

    /** Keeps selections open until focus leaves, Escape is pressed or an outside click dismisses the popup. / 切档后保持展开，焦点移出、Escape 或外部点击时收起。 */
    private void installPopupInteractions(){
        slider.addFocusListener(new FocusAdapter(){
            /** Dismisses only when focus moves outside the popup. / 焦点移出弹出面板时才收起。
             * @param event focus transfer / 焦点转移事件
             */
            @Override public void focusLost(FocusEvent event){
                SwingUtilities.invokeLater(()->{
                    Component next=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                    if(modePopup.isVisible()&&(next==null||!SwingUtilities.isDescendingFrom(next,modePopup))){
                        hidePopup();
                        // Swing restores the pre-popup focus; retain the user's new destination. / Swing 会恢复弹出前的焦点，此处保留用户的新目标。
                        if(next!=null)next.requestFocusInWindow();
                    }
                });
            }
        });
        slider.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"),"closeModePopup");
        slider.getActionMap().put("closeModePopup",new AbstractAction(){
            /** Returns keyboard focus to the collapsed control. / 将键盘焦点返回收起后的控件。
             * @param event keyboard dismissal / 键盘收起事件
             */
            @Override public void actionPerformed(ActionEvent event){hidePopup();modeButton.requestFocusInWindow();}
        });
        modePopup.addPopupMenuListener(new PopupMenuListener(){
            /** Marks the control as expanded. / 标记控件已展开。
             * @param event popup visibility / 面板可见性事件
             */
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent event){modeButton.setSelected(true);}
            /** Cleans up dismissal without stealing focus from an outside click. / 清理关闭状态，不抢夺外部点击目标的焦点。
             * @param event popup visibility / 面板可见性事件
             */
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event){modeButton.setSelected(false);}
            /** Clears the expanded state after an outside click or Escape. / 外部点击或 Escape 后清除展开状态。
             * @param event popup cancellation / 面板取消事件
             */
            @Override public void popupMenuCanceled(PopupMenuEvent event){modeButton.setSelected(false);}
        });
    }
    /** Displays current policy and the manual-review workload warning. / 展示当前策略及人工复审工作量提示。 */
    private void update(){
        approval.setVisible(mode()==DeploymentAutomationMode.AGENT);
        String key=mode()==DeploymentAutomationMode.AGENT&&approval()==AgentApprovalMode.MANUAL_REVIEW
            ?"deployment.approval.manualHint":"deployment.mode.description."+mode().name();
        // Reserve the longest explanation for this approval policy before switching modes. / 按当前审批策略预留最长说明的高度，避免切档时弹窗跳动。
        description.setText(messages.text(approval()==AgentApprovalMode.MANUAL_REVIEW
            ?"deployment.approval.manualHint":"deployment.mode.description.AGENT"));
        int width=UIScale.scale(220);
        View view=description.getUI().getRootView(description);view.setSize(width,Float.MAX_VALUE);
        int height=Math.max(description.getFontMetrics(description.getFont()).getHeight()*2,(int)Math.ceil(view.getPreferredSpan(View.Y_AXIS)));
        description.setPreferredSize(new Dimension(width,height));
        description.setText(messages.text(key));
        SimpleAttributeSet centered=new SimpleAttributeSet();StyleConstants.setAlignment(centered,StyleConstants.ALIGN_CENTER);
        description.setParagraphAttributes(centered,false);
        String title=messages.text("deployment.mode."+mode().name());
        modeButton.setText(title);modeHeading.setText(title);
        modeButton.getAccessibleContext().setAccessibleName(messages.text("deployment.mode.label")+": "+title);
        modeButton.getAccessibleContext().setAccessibleDescription(messages.text(key));
        approval.setToolTipText(approval()==AgentApprovalMode.MANUAL_REVIEW?messages.text("deployment.approval.manualHint"):messages.text("deployment.approval.label"));
        slider.getAccessibleContext().setAccessibleDescription(title);
        slider.repaint();revalidate();
        if(modePopup.isVisible()&&!modePopup.getSize().equals(modePopup.getPreferredSize())){
            boolean focused=slider.isFocusOwner();modePopup.pack();
            Point origin=modeButton.getLocationOnScreen();
            modePopup.setLocation(origin.x,origin.y-modePopup.getPreferredSize().height-UIScale.scale(6));
            if(focused)slider.requestFocusInWindow();
        }
    }

    /** Keeps native slider input while restoring its capsule appearance after theme changes. / 保留原生滑块输入，主题切换后恢复胶囊外观。 */
    private static final class ModeSlider extends JSlider {
        /** Creates the three discrete positions, initially assisted. / 创建三个离散档位，默认AI 辅助。 */
        private ModeSlider(){super(0,2,DeploymentAutomationMode.ASSISTED.ordinal());}

        /** Reinstalls the theme-aware delegate without resetting the value. / 重新安装主题绘制器且不重置取值。 */
        @Override public void updateUI(){setUI(new ModeSliderUI());}

        /** Stops any transition when the control leaves its window. / 控件移出窗口时停止过渡动画。 */
        @Override public void removeNotify(){((ModeSliderUI)getUI()).finishAnimation();super.removeNotify();}
    }

    /** Paints the capsule and position dots while FlatLaf owns input, focus and the thumb. / 绘制胶囊和档位圆点，输入、焦点与滑块由 FlatLaf 管理。 */
    private static final class ModeSliderUI extends FlatSliderUI {
        /** Continuous visual position, independent of the selected discrete value. / 独立于离散选中值的连续视觉位置。 */
        private double displayedValue=Double.NaN;
        /** Event-thread transition timer. / 事件线程上的过渡定时器。 */
        private Timer animation;

        /** Starts the visual state at the selected value without an entrance animation. / 以所选档位初始化视觉状态，不播放入场动画。
         * @param component the new slider / 新安装的滑块
         */
        @Override public void installUI(JComponent component){super.installUI(component);displayedValue=slider.getValue();}

        /** Stops callbacks before a theme change uninstalls this delegate. / 主题更换卸载绘制器之前停止回调。
         * @param component the departing slider / 即将卸载的滑块
         */
        @Override public void uninstallUI(JComponent component){finishAnimation();super.uninstallUI(component);}

        /** Animates discrete changes without emitting intermediate model values. / 对离散切换添加动画，不发出中间模型值。
         * @param control the deployment slider / 部署滑块
         * @return the native listener with visual transitions / 带视觉过渡的原生监听器
         */
        @Override protected ChangeListener createChangeListener(JSlider control){
            ChangeListener delegate=super.createChangeListener(control);
            return event->{
                double previous=visualValue();
                delegate.stateChanged(event);
                if(!isDragging()){
                    displayedValue=previous;
                    animateTo(control.getValue());
                }
            };
        }

        /** Keeps the thumb under the pointer until release instead of snapping during a drag. / 拖动时滑块跟随指针，松开后再吸附。
         */
        @Override protected void calculateThumbLocation(){
            if(!isDragging()||!slider.getValueIsAdjusting())super.calculateThumbLocation();
        }

        /** Extends native pointer handling with continuous dragging and animated track clicks. / 为原生指针处理添加连续拖动与轨道点击动画。
         * @param control the deployment slider / 部署滑块
         * @return pointer handling with smooth visual positions / 带平滑视觉位置的指针处理器
         */
        @Override protected TrackListener createTrackListener(JSlider control){
            return new FlatTrackListener(){
                /** Distinguishes a synthesized track-click drag from actual pointer movement. / 区分轨道点击合成的拖动与实际指针移动。 */
                private boolean pressing;
                /** Lets FlatLaf retain focus and click-to-position behavior. / 保留 FlatLaf 的焦点及点击定位行为。
                 * @param event the pointer press / 指针按下事件
                 */
                @Override public void mousePressed(MouseEvent event){
                    pressing=true;
                    try {super.mousePressed(event);} finally {pressing=false;}
                }
                /** Follows actual movement immediately and eases synthesized clicks. / 立即跟随实际移动，对合成点击执行缓动。
                 * @param event the pointer movement / 指针移动事件
                 */
                @Override public void mouseDragged(MouseEvent event){
                    super.mouseDragged(event);
                    if(!isDragging()||!slider.isEnabled())return;
                    int left=xPositionForValue(slider.getMinimum()),right=xPositionForValue(slider.getMaximum());
                    double value=slider.getMinimum()+(thumbRect.getCenterX()-left)
                        /Math.max(1,right-left)*(slider.getMaximum()-slider.getMinimum());
                    value=Math.max(slider.getMinimum(),Math.min(slider.getMaximum(),value));
                    if(pressing)animateTo(value);
                    else {stopAnimation();displayedValue=value;slider.repaint();}
                }
            };
        }

        /** Returns the continuous displayed value, initialized from the current model. / 返回连续显示值，初值取自当前模型。
         * @return the current visual value / 当前视觉值
         */
        private double visualValue(){return Double.isNaN(displayedValue)?slider.getValue():displayedValue;}

        /** Maps the visual value to the current scaled track. / 将视觉值映射到当前缩放后的轨道。
         * @return the horizontal thumb center / 滑块中心的横坐标
         */
        private int visualCenter(){
            int left=xPositionForValue(slider.getMinimum()),right=xPositionForValue(slider.getMaximum());
            return (int)Math.round(left+(right-left)*(visualValue()-slider.getMinimum())
                /(slider.getMaximum()-slider.getMinimum()));
        }

        /** Retargets from the displayed frame, honoring reduced-motion settings. / 从当前显示帧重新设定目标，并遵守减少动画的设置。
         * @param target the desired visual value / 目标视觉值
         */
        private void animateTo(double target){
            double from=visualValue();stopAnimation();
            if(!slider.isShowing()||!slider.isEnabled()||Math.abs(target-from)<0.001
                ||Boolean.FALSE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.clientAreaAnimation"))
                ||"false".equals(System.getProperty("flatlaf.animation"))){
                displayedValue=target;slider.repaint();return;
            }
            long started=System.nanoTime();
            animation=new Timer(15,event->{
                if(!slider.isShowing()||!slider.isEnabled()){finishAnimation();return;}
                double progress=Math.min(1,(System.nanoTime()-started)/200_000_000.0);
                double eased=1-Math.pow(1-progress,3);
                displayedValue=from+(target-from)*eased;slider.repaint();
                if(progress>=1)stopAnimation();
            });
            animation.start();
        }

        /** Cancels the timer while retaining the current frame for retargeting. / 取消定时器并保留当前帧，供反向过渡使用。 */
        private void stopAnimation(){if(animation!=null){animation.stop();animation=null;}}

        /** Settles on the model value and releases the timer. / 回到模型档位并释放定时器。 */
        private void finishAnimation(){stopAnimation();displayedValue=slider.getValue();slider.repaint();}

        /** Applies local dimensions and theme colors without changing other sliders. / 应用局部尺寸与主题颜色，不影响其他滑块。
         * @param control the deployment slider / 部署滑块
         */
        @Override protected void installDefaults(JSlider control){
            super.installDefaults(control);
            trackWidth=26;thumbSize=new Dimension(22,22);focusWidth=2;
            trackValueColor=UIManager.getColor("Component.focusColor");
            trackColor=UIManager.getColor("Component.borderColor");
            thumbColor=Color.WHITE;hoverThumbColor=Color.WHITE;pressedThumbColor=new Color(245,247,250);
            thumbBorderColor=UIManager.getColor("Component.borderColor");
        }

        /** Keeps a circular thumb even when mode labels are visible. / 显示档位标签时仍使用圆形滑块。
         * @return true for a circular thumb / 返回 true 以使用圆形滑块
         */
        @Override protected boolean isRoundThumb(){return true;}

        /** Draws the filled track and dots at the same coordinates used by native input. / 按原生输入坐标绘制填充轨道与档位圆点。
         * @param graphics the paint surface / 绘制表面
         */
        @Override public void paintTrack(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
                int height=UIScale.scale(trackWidth),radius=height/2;
                int left=xPositionForValue(slider.getMinimum())-radius;
                int right=xPositionForValue(slider.getMaximum())+radius;
                int center=visualCenter();
                int y=trackRect.y+(trackRect.height-height)/2;
                g.setColor(slider.isEnabled()?getTrackColor():disabledTrackColor);
                g.fillRoundRect(left,y,right-left,height,height,height);
                if(slider.isEnabled()){
                    g.setColor(getTrackValueColor());
                    g.fillRoundRect(left,y,center-left+radius,height,height,height);
                }
                int dot=UIScale.scale(4);
                for(int value=slider.getMinimum();value<=slider.getMaximum();value++){
                    boolean filled=slider.isEnabled()&&xPositionForValue(value)<=center;
                    g.setColor(filled?new Color(255,255,255,150):UIManager.getColor("Label.disabledForeground"));
                    g.fillOval(xPositionForValue(value)-dot/2,y+(height-dot)/2,dot,dot);
                }
            } finally {g.dispose();}
        }

        /** Moves the native thumb artwork with the animated fill. / 使原生滑块图形与填充动画同步移动。
         * @param graphics the paint surface / 绘制表面
         */
        @Override public void paintThumb(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();
            try {g.translate(visualCenter()-thumbRect.getCenterX(),0);super.paintThumb(g);}
            finally {g.dispose();}
        }

        /** Highlights the selected mode and refreshes label colors for the current theme. / 高亮所选模式，并根据当前主题刷新标签颜色。
         * @param graphics the paint surface / 绘制表面
         */
        @Override public void paintLabels(Graphics graphics){
            var labels=slider.getLabelTable();
            for(int value=slider.getMinimum();value<=slider.getMaximum();value++){
                JLabel label=(JLabel)labels.get(value);
                label.setEnabled(slider.isEnabled());
                label.setForeground(slider.isEnabled()&&value==slider.getValue()
                    ?getTrackValueColor():UIManager.getColor("Label.foreground"));
            }
            super.paintLabels(graphics);
        }
    }
}
