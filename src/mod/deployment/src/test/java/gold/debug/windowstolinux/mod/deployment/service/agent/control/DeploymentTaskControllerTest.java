package gold.debug.windowstolinux.mod.deployment.service.agent.control;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import gold.debug.windowstolinux.appapi.mod.ModContext;
import gold.debug.windowstolinux.appapi.mod.lifecycle.ModLaunchMode;
import gold.debug.windowstolinux.appapi.task.control.*;
import gold.debug.windowstolinux.appapi.ui.UiService;
import gold.debug.windowstolinux.appapi.ui.page.UiNavigationRequest;
import gold.debug.windowstolinux.mod.deployment.service.agent.control.DeploymentAgentControl;
import org.junit.jupiter.api.Test;

class DeploymentTaskControllerTest {
    @Test void interventionOnlyNavigatesToTheOriginalModTaskOnEdt() throws Exception {
        UUID id=UUID.randomUUID(); var opened=new AtomicInteger(); var controlled=new AtomicInteger();
        var ui=(UiService)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{UiService.class},(proxy,method,args)->{
            assertEquals("open",method.getName()); assertTrue(SwingUtilities.isEventDispatchThread());
            assertEquals("workspace",args[0]);
            var request=(UiNavigationRequest<?>)args[1];
            assertEquals("intervene-task",request.destination()); assertEquals(id,request.context().orElseThrow());
            opened.incrementAndGet(); return null;
        });
        var context=(ModContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ModContext.class},(proxy,method,args)->switch(method.getName()){
            case "ui" -> ui; case "launchMode" -> ModLaunchMode.NORMAL;
            default -> throw new AssertionError("No business or approval capability may be used: "+method.getName());
        });
        var control=new DeploymentTaskController(context, task->Optional.of(DeploymentAgentControl.State.RUNNING),
            (task,command)->controlled.incrementAndGet());
        control.request(id,TaskCommand.INTERVENE);
        assertEquals(1,opened.get()); assertEquals(0,controlled.get());
        assertEquals(TaskControlSnapshot.State.RUNNING,control.snapshot(id).state());
    }
}
