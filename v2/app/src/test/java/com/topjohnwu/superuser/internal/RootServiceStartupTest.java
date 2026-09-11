package com.topjohnwu.superuser.internal;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class RootServiceStartupTest {
    @Before public void resetManager() {
        ReflectionHelpers.setStaticField(RootServiceManager.class, "mInstance", null);
        Utils.context = RuntimeEnvironment.getApplication();
    }

    private static class Connection implements ServiceConnection {
        int failures;
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {}
        @Override public void onServiceDisconnected(ComponentName name) { failures++; }
        @Override public void onNullBinding(ComponentName name) { failures++; }
    }

    @Test public void failedCancelledAndTimedOutStartsCanBeRetriedWithoutStaleCallbacks() {
        Intent intent = new Intent().setComponent(new ComponentName(
            RuntimeEnvironment.getApplication().getPackageName(), "example.Root"))
            .addCategory(RootService.CATEGORY_DAEMON_MODE);
        Connection first = new Connection();
        Shell.Task startup = RootService.bindOrTask(intent, Runnable::run, first);
        assertNotNull(startup);
        startup.shellDied();
        ShadowLooper.idleMainLooper();
        assertEquals(1, first.failures);

        Connection cancelled = new Connection();
        Shell.Task old = RootService.bindOrTask(intent, Runnable::run, cancelled);
        assertNotNull(old);
        RootService.unbind(cancelled);
        Connection next = new Connection();
        Shell.Task retry = RootService.bindOrTask(intent, Runnable::run, next);
        assertNotNull(retry);
        old.shellDied();
        ShadowLooper.idleMainLooper();
        assertEquals(0, next.failures);
        assertEquals(0, cancelled.failures);

        // No daemon broadcast: the request must fail, not stay pending forever.
        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS);
        assertEquals(1, next.failures);
        assertEquals(0, cancelled.failures);
        Connection afterTimeout = new Connection();
        assertNotNull(RootService.bindOrTask(intent, Runnable::run, afterTimeout));
        RootService.unbind(afterTimeout);
        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS);
        assertEquals(0, afterTimeout.failures);
    }
}
