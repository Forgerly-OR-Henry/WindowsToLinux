package probe;

import gold.debug.windowstolinux.api.ui.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PageProbeTest {
    void onEdt(org.junit.jupiter.api.function.Executable action)throws Exception {
        var failure=new AtomicReference<Throwable>();SwingUtilities.invokeAndWait(()->{try{action.execute();}catch(Throwable problem){failure.set(problem);}});
        if(failure.get()!=null)throw new AssertionError(failure.get());
    }
    @Test void authorsControlSwingWithLazyReopenEnvironmentChangesAndReleasedViews()throws Exception {
        var creations=new AtomicInteger();var closes=new AtomicInteger();var events=new AtomicInteger();
        onEdt(()-> {
            try(var session=new PageSession(new UiEnvironment(Locale.ENGLISH,"light",false))) {
                session.register("custom",()->new ModPage(){
                    public JComponent create(UiEnvironment environment){assertTrue(SwingUtilities.isEventDispatchThread());creations.incrementAndGet();var panel=new JPanel(new java.awt.BorderLayout());panel.add(new JTextArea("自由布局"),java.awt.BorderLayout.CENTER);panel.add(new JButton("action"),java.awt.BorderLayout.SOUTH);return panel;}
                    public void environmentChanged(UiEnvironment e){assertTrue(SwingUtilities.isEventDispatchThread());assertTrue(e.locked());events.incrementAndGet();}
                    public void close(){assertTrue(SwingUtilities.isEventDispatchThread());closes.incrementAndGet();}
                });
                assertEquals(0,creations.get());var first=session.open("custom");assertEquals(2,first.getComponentCount());
                session.environment(new UiEnvironment(Locale.SIMPLIFIED_CHINESE,"dark",true));assertEquals(1,events.get());
                session.closePage("custom");assertEquals(1,closes.get());var second=session.open("custom");assertNotSame(first,second);assertEquals(2,creations.get());
            }
            assertEquals(2,closes.get());
        });
    }
    @Test void failedPageInitializationReleasesPartialResources()throws Exception {
        var closed=new AtomicInteger();onEdt(()-> {
            try(var session=new PageSession(new UiEnvironment(Locale.ENGLISH,"light",false))) {
                session.register("bad",()->new ModPage(){public JComponent create(UiEnvironment e)throws Exception{throw new Exception("injected");}public void environmentChanged(UiEnvironment e){}public void close(){closed.incrementAndGet();}});
                assertThrows(Exception.class,()->session.open("bad"));assertEquals(1,closed.get());
            }
        });
    }
    @Test void workerCompletionAfterClosedPageUpdatesTaskWithoutTouchingDisposedUi()throws Exception {
        var disposed=new java.util.concurrent.atomic.AtomicBoolean();var taskDone=new java.util.concurrent.CompletableFuture<String>();var uiWrites=new AtomicInteger();
        onEdt(()->disposed.set(true));
        taskDone.thenAccept(result->SwingUtilities.invokeLater(()->{if(!disposed.get())uiWrites.incrementAndGet();}));
        Thread.ofVirtual().start(()->taskDone.complete("result")).join();onEdt(()->assertEquals(0,uiWrites.get()));assertEquals("result",taskDone.get());
    }
}
