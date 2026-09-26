package probe;

import gold.debug.windowstolinux.appapi.ui.*;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;

public final class PageSession implements AutoCloseable {
    private final Map<String,PageFactory> factories=new LinkedHashMap<>();
    private final Map<String,ModPage> pages=new LinkedHashMap<>();
    private UiEnvironment environment;
    public PageSession(UiEnvironment environment) {this.environment=environment;}
    private static void edt(){if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("EDT required");}
    public void register(String id,PageFactory factory){edt();if(factories.putIfAbsent(id,factory)!=null)throw new IllegalArgumentException("Duplicate page");}
    public JComponent open(String id)throws Exception {
        edt();if(pages.containsKey(id))throw new IllegalStateException("Already open");
        var factory=factories.get(id);if(factory==null)throw new IllegalArgumentException("Not registered");
        var page=factory.create();
        try {var view=page.create(environment);if(view==null)throw new IllegalStateException("Missing view");pages.put(id,page);return view;}
        catch(Exception failure){try{page.close();}catch(Exception closing){failure.addSuppressed(closing);}throw failure;}
    }
    public void closePage(String id)throws Exception {edt();var page=pages.get(id);if(page!=null){page.close();pages.remove(id);}}
    public void environment(UiEnvironment next){edt();environment=next;pages.values().forEach(page->page.environmentChanged(next));}
    @Override public void close()throws Exception {
        edt();for(var id:java.util.List.copyOf(pages.keySet()))closePage(id);factories.clear();
    }
}
