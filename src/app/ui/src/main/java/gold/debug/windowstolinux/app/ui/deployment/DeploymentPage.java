package gold.debug.windowstolinux.app.ui.deployment;

import gold.debug.windowstolinux.app.ui.component.DesktopComponents;
import gold.debug.windowstolinux.app.ui.i18n.MessageCatalog;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridLayout;

/**
 * Provides the {@code DeploymentPage} implementation.
 *
 * <p>提供 {@code DeploymentPage} 实现。
 */
public final class DeploymentPage {
    private DeploymentPage() {
    }

    /**
     * Creates a value through {@code create}.
     *
     * <p>通过 {@code create} 创建值。
     *
 * @param c the {@code c} value / {@code c} 值
 * @param m the {@code m} value / {@code m} 值
 * @param projectType the {@code projectType} value / {@code projectType} 值
     * @param healthMode the {@code healthMode} value / {@code healthMode} 值
     * @param healthEndpoint the {@code healthEndpoint} value / {@code healthEndpoint} 值
     * @param expectedStatus the {@code expectedStatus} value / {@code expectedStatus} 值
     * @param timeout the {@code timeout} value / {@code timeout} 值
     * @param stability the {@code stability} value / {@code stability} 值
     * @param accessUrl the {@code accessUrl} value / {@code accessUrl} 值
     * @param rootBuild the {@code rootBuild} value / {@code rootBuild} 值
 * @param output the {@code output} value / {@code output} 值
 * @param runtimePrimary the {@code runtimePrimary} value / {@code runtimePrimary} 值
 * @param runtimeSecondary the {@code runtimeSecondary} value / {@code runtimeSecondary} 值
 * @param jvmArguments the {@code jvmArguments} value / {@code jvmArguments} 值
 * @param applicationArguments the {@code applicationArguments} value / {@code applicationArguments} 值
 * @param containerEngine the {@code containerEngine} value / {@code containerEngine} 值
 * @param containerPorts the {@code containerPorts} value / {@code containerPorts} 值
 * @param containerVolumes the {@code containerVolumes} value / {@code containerVolumes} 值
 * @param configurationEntries the {@code configurationEntries} value / {@code configurationEntries} 值
     * @param chooseSource the {@code chooseSource} value / {@code chooseSource} 值
     * @param configureServer the {@code configureServer} value / {@code configureServer} 值
     * @param deploy the {@code deploy} value / {@code deploy} 值
     * @return the operation result / 操作结果
     */
    public static JPanel create(DesktopComponents c, MessageCatalog m, JComboBox<?> projectType, JComboBox<?> healthMode,
                                JTextField healthEndpoint, JTextField expectedStatus, JTextField timeout,
                                JTextField stability, JTextField accessUrl, JCheckBox rootBuild, JTextArea output,
                                JTextField runtimePrimary, JTextField runtimeSecondary, JTextField jvmArguments,
                                JTextField applicationArguments, JComboBox<?> containerEngine, JTextField containerPorts,
                                JTextField containerVolumes, JTextField configurationEntries,
                                Runnable chooseSource, Runnable configureServer, Runnable deploy) {
        JPanel panel = c.pagePanel();
        JPanel steps = c.transparent(new GridLayout(1, 3, 12, 0));
        JButton choose = c.primaryButton(m.text("button.analyzeSource"));
        choose.addActionListener(event -> chooseSource.run());
        JButton configure = c.secondaryButton(m.text("button.goServer"));
        configure.addActionListener(event -> configureServer.run());
        JButton publish = c.primaryButton(m.text("button.reviewDeploy"));
        publish.addActionListener(event -> deploy.run());
        steps.add(c.stepCard("01", m.text("step.analyze.title"), m.text("step.analyze.description"), choose));
        steps.add(c.stepCard("02", m.text("step.target.title"), m.text("step.target.description"), configure));
        steps.add(c.stepCard("03", m.text("step.deploy.title"), m.text("step.deploy.description"), publish));
        panel.add(steps, BorderLayout.NORTH);
        JPanel body = c.transparent(new BorderLayout(0, 12));
        JPanel health = c.card(new BorderLayout(0, 12));
        health.add(c.sectionHeading(m.text("section.deployHealth.title"),
                m.text("section.deployHealth.description")), BorderLayout.NORTH);
        JPanel form = c.transparent(new GridBagLayout());
        c.addField(form, 0, 0, m.text("field.healthMode"), healthMode);
        c.addField(form, 0, 1, m.text("field.healthEndpoint"), healthEndpoint);
        c.addField(form, 1, 0, m.text("field.expectedStatus"), expectedStatus);
        c.addField(form, 1, 1, m.text("field.timeout"), timeout);
        c.addField(form, 2, 0, m.text("field.tcpStability"), stability);
        c.addField(form, 2, 1, m.text("field.userAccessUrl"), accessUrl);
        health.add(form, BorderLayout.CENTER);
        JPanel risk = c.transparent(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rootBuild.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        risk.add(rootBuild);
        health.add(risk, BorderLayout.SOUTH);
        JPanel runtime = c.card(new BorderLayout(0, 12));
        runtime.add(c.sectionHeading(m.text("section.runtime.title"), m.text("section.runtime.description")), BorderLayout.NORTH);
        JPanel runtimeForm = c.transparent(new GridBagLayout());
        c.addField(runtimeForm, 0, 0, m.text("field.projectType"), projectType);
        c.addField(runtimeForm, 0, 1, m.text("field.runtimePrimary"), runtimePrimary);
        c.addField(runtimeForm, 1, 0, m.text("field.runtimeSecondary"), runtimeSecondary);
        c.addField(runtimeForm, 1, 1, m.text("field.jvmArguments"), jvmArguments);
        c.addField(runtimeForm, 2, 0, m.text("field.applicationArguments"), applicationArguments);
        c.addField(runtimeForm, 2, 1, m.text("field.containerEngine"), containerEngine);
        c.addField(runtimeForm, 3, 0, m.text("field.containerPorts"), containerPorts);
        c.addField(runtimeForm, 3, 1, m.text("field.containerVolumes"), containerVolumes);
        c.addField(runtimeForm, 4, 0, m.text("field.configurationEntries"), configurationEntries);
        runtime.add(runtimeForm, BorderLayout.CENTER);
        JPanel forms = c.transparent(new GridLayout(2, 1, 0, 12));
        forms.add(health);
        forms.add(runtime);
        body.add(forms, BorderLayout.NORTH);
        body.add(c.outputCard(m.text("section.execution.title"), m.text("section.execution.description"), output),
                BorderLayout.CENTER);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }
}
