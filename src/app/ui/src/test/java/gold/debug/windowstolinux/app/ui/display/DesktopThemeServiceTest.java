package gold.debug.windowstolinux.app.ui.display;

import gold.debug.windowstolinux.app.ui.shell.DesktopFrame;

import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class DesktopThemeServiceTest {
    @Test void dropdownWidthsStayStableAcrossSelectionsTextChangesAndAsyncOptions() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(()->{
            String previousScale=System.getProperty("flatlaf.uiScale");
            try {
                for(String scale:new String[]{"1.0","1.5"})for(ThemeMode theme:new ThemeMode[]{ThemeMode.LIGHT,ThemeMode.DARK}){
                    System.setProperty("flatlaf.uiScale",scale);DesktopThemeService.install(theme);
                    var choices=new javax.swing.JComboBox<>(new String[]{"A","Initial longest choice"});
                    var delayed=new javax.swing.JComboBox<String>();
                    var content=new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
                    content.add(choices);content.add(delayed);
                    var window=new javax.swing.JFrame();window.setContentPane(content);window.setSize(700,200);window.setVisible(true);
                    try {
                        assertInstanceOf(DesktopComboBoxUi.class,choices.getUI());
                        assertInstanceOf(DesktopComboBoxUi.class,delayed.getUI());
                        int choiceWidth=choices.getWidth(),delayedWidth=delayed.getWidth();
                        choices.setSelectedIndex(1);layout(content);assertEquals(choiceWidth,choices.getWidth());
                        String longer="Server refreshed with a much longer descriptive name than the original options";
                        choices.setModel(new javax.swing.DefaultComboBoxModel<>(new String[]{"B",longer}));
                        choices.setSelectedIndex(1);layout(content);
                        assertEquals(choiceWidth,choices.getWidth(),"Replacing labels must not resize an existing dropdown");
                        delayed.addItem(longer);layout(content);
                        assertEquals(delayedWidth,delayed.getWidth(),"Loading options after opening the page must not shift its layout");
                        choices.showPopup();
                        var popup=(javax.swing.plaf.basic.BasicComboPopup)choices.getUI().getAccessibleChild(choices,0);
                        assertTrue(popup.getWidth()>choices.getWidth(),"The expanded list must still fit longer options");
                        assertEquals(longer,choices.getSelectedItem());choices.hidePopup();
                        choices.setEditable(true);choices.setSelectedItem(longer+longer);layout(content);
                        assertEquals(choiceWidth,choices.getWidth(),"Editing text must retain the dropdown width");
                        assertEquals(longer+longer,choices.getSelectedItem());
                    } finally { choices.hidePopup();window.dispose(); }
                }
            } finally {
                if(previousScale==null)System.clearProperty("flatlaf.uiScale");else System.setProperty("flatlaf.uiScale",previousScale);
                DesktopThemeService.install(ThemeMode.LIGHT);
            }
        });
    }

    @Test void sharedComboStylePreservesLocalizedRenderersPopupSelectionAndThemeChanges() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(()->{
            for(ThemeMode theme:new ThemeMode[]{ThemeMode.LIGHT,ThemeMode.DARK})for(String language:new String[]{"zh-CN","en-US"}){
                DesktopThemeService.install(theme);
                var messages=new gold.debug.windowstolinux.app.ui.i18n.PageMessagePresenter(
                    gold.debug.windowstolinux.app.ui.i18n.MessageCatalog.forLanguageTag(language));
                var combo=new javax.swing.JComboBox<>(gold.debug.windowstolinux.shared.model.deployment.AgentApprovalMode.values());
                combo.setSelectedIndex(1);messages.localize(combo,"deployment.approval.");
                var renderer=combo.getRenderer();
                var content=new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT,16,16));content.add(combo);
                var window=new javax.swing.JFrame();window.setContentPane(content);window.setSize(400,240);window.setVisible(true);
                try {
                    combo.showPopup();
                    var popup=(javax.swing.plaf.basic.BasicComboPopup)combo.getUI().getAccessibleChild(combo,0);
                    var list=popup.getList();
                    assertEquals(1,list.getSelectedIndex());
                    assertTrue(list.getCellBounds(0,0).height>=list.getFontMetrics(list.getFont()).getHeight()+8);
                    var label=(javax.swing.JLabel)list.getCellRenderer().getListCellRendererComponent(list,combo.getItemAt(1),1,true,false);
                    assertEquals(messages.text("deployment.approval.automatic"),label.getText());
                    assertEquals(combo.getItemAt(1),combo.getSelectedItem());
                    popup.setSize(popup.getPreferredSize());layout(popup);
                    assertTrue(popup.getHeight()>list.getCellBounds(0,2).height);
                    int width=Math.max(combo.getWidth(),popup.getWidth())+32,height=combo.getHeight()+popup.getHeight()+40;
                    var image=new java.awt.image.BufferedImage(width,height,java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics=image.createGraphics();graphics.setColor(theme==ThemeMode.DARK?ThemePalette.dark().pageBackground():ThemePalette.light().pageBackground());graphics.fillRect(0,0,width,height);
                    var controlGraphics=graphics.create(16,12,combo.getWidth(),combo.getHeight());combo.printAll(controlGraphics);controlGraphics.dispose();
                    var popupGraphics=graphics.create(16,combo.getHeight()+20,popup.getWidth(),popup.getHeight());popup.printAll(popupGraphics);popupGraphics.dispose();graphics.dispose();
                    var path=java.nio.file.Path.of("target/visual-checks/dropdown-"+theme+"-"+language+".png");
                    java.nio.file.Files.createDirectories(path.getParent());javax.imageio.ImageIO.write(image,"png",path.toFile());
                    combo.hidePopup();combo.setSelectedIndex(2);
                    SwingUtilities.updateComponentTreeUI(content);
                    assertSame(renderer,combo.getRenderer());assertEquals(2,combo.getSelectedIndex());
                    combo.setEnabled(false);assertFalse(combo.isEnabled());
                    var editable=new javax.swing.JComboBox<>(new String[]{"first","second"});editable.setEditable(true);editable.setSelectedItem("custom");
                    SwingUtilities.updateComponentTreeUI(editable);assertEquals("custom",editable.getSelectedItem());
                } catch(Exception error){throw new AssertionError(error);}
                finally {combo.hidePopup();window.dispose();}
            }
            DesktopThemeService.install(ThemeMode.LIGHT);
        });
    }

    private static void layout(java.awt.Container container){
        container.doLayout();
        for(java.awt.Component child:container.getComponents())if(child instanceof java.awt.Container nested)layout(nested);
    }
    @Test
    void installsFlatLightLookAndFeelAndSharedGeometry() {
        DesktopThemeService.install(ThemeMode.LIGHT);

        assertInstanceOf(FlatLightLaf.class, UIManager.getLookAndFeel());
        assertEquals(12, UIManager.get("Component.arc"));
        assertEquals(12, UIManager.get("Button.arc"));
    }

    @Test
    void installsFlatDarkLookAndFeelForTheDarkAppearance() {
        DesktopThemeService.install(ThemeMode.DARK);

        assertInstanceOf(FlatDarkLaf.class, UIManager.getLookAndFeel());
        assertEquals(ThemeMode.LIGHT, SystemThemeResolver.effectiveTheme(ThemeMode.LIGHT));
        assertEquals(ThemeMode.DARK, SystemThemeResolver.effectiveTheme(ThemeMode.DARK));
    }

    @Test
    void keepsNavigationFullHeightAlongsideTheWorkspace() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<DesktopFrame> frame = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            DesktopFrame desktopFrame = new DesktopFrame(null);
            frame.set(desktopFrame);
            var root = desktopFrame.getContentPane();
            root.setSize(1060, 680); root.doLayout();
            var layout = (java.awt.BorderLayout) root.getLayout();
            var sidebar = layout.getLayoutComponent(java.awt.BorderLayout.WEST);
            var workspace = layout.getLayoutComponent(java.awt.BorderLayout.CENTER);
            assertEquals(0, sidebar.getY());
            assertEquals(root.getHeight(), sidebar.getHeight());
            assertEquals(sidebar.getX() + sidebar.getWidth(), workspace.getX());
        });

        SwingUtilities.invokeAndWait(() -> frame.get().dispose());
    }
}
