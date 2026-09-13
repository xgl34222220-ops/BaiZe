package com.topjohnwu.superuser.internal;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;
import com.topjohnwu.superuser.ipc.RootService.BindingFailure;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class RootServiceRecoveryTest {
    private static class Connection implements RootService.Connection {
        int connected;
        int disconnected;
        final List<BindingFailure> failures = new ArrayList<>();
        Runnable onConnected;
        Runnable onDisconnected;
        Runnable onFailure;

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            connected++;
            if (onConnected != null) onConnected.run();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            disconnected++;
            if (onDisconnected != null) onDisconnected.run();
        }
        @Override public void onBindingFailed(ComponentName name, BindingFailure reason) {
            failures.add(reason);
            if (onFailure != null) onFailure.run();
        }
    }

    private static class Manager extends IRootServiceManager.Stub {
        int binds;
        int unbinds;
        boolean failConnect;
        boolean failBind;
        boolean nullBinding;
        @Override public void broadcast(int uid) {}
        @Override public void stop(ComponentName name, int uid) {}
        @Override public void connect(IBinder binder) throws RemoteException {
            if (failConnect) throw new RemoteException("connect failed");
        }
        @Override public IBinder bind(Intent intent) throws RemoteException {
            binds++;
            if (failBind) throw new RemoteException("died during bind");
            return nullBinding ? null : new Binder();
        }
        @Override public void unbind(ComponentName name) { unbinds++; }
    }

    @Before public void resetManager() {
        ReflectionHelpers.setStaticField(RootServiceManager.class, "mInstance", null);
        Utils.context = RuntimeEnvironment.getApplication();
        // Resolve only the application shadow, not Shadows' removed framework overloads.
        org.robolectric.shadows.ShadowApplication appShadow =
                org.robolectric.shadow.api.Shadow.extract(RuntimeEnvironment.getApplication());
        // The production broadcaster is root; the shadow sender needs its guarded permission.
        appShadow.grantPermissions(android.Manifest.permission.BROADCAST_PACKAGE_REMOVED);
    }

    private Intent intent(String service, boolean daemon) {
        Intent intent = new Intent().setComponent(new ComponentName(
                RuntimeEnvironment.getApplication().getPackageName(), service));
        if (daemon) intent.addCategory(RootService.CATEGORY_DAEMON_MODE);
        return intent;
    }

    private Shell.Task bind(Intent intent, ServiceConnection connection) {
        return RootService.bindOrTask(intent, Runnable::run, connection);
    }

    private void broadcast(Manager manager, boolean daemon) {
        RuntimeEnvironment.getApplication().sendBroadcast(
                RootServiceManager.getBroadcastIntent(manager, daemon));
        ShadowLooper.idleMainLooper();
    }

    @Test public void bindFailureAfterBroadcastTerminatesEveryPendingRequestAndAllowsRetry() {
        Intent primary = intent("example.Primary", true);
        Intent cache = intent("example.Cache", true);
        Connection first = new Connection();
        Connection second = new Connection();
        assertNotNull(bind(primary, first));
        assertNull(bind(cache, second));
        Manager dead = new Manager();
        dead.failBind = true;
        broadcast(dead, true);
        assertEquals(List.of(BindingFailure.BIND_FAILED), first.failures);
        assertEquals(List.of(BindingFailure.BIND_FAILED), second.failures);
        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS);
        assertEquals(1, first.failures.size());
        assertEquals(1, second.failures.size());

        assertNotNull(bind(primary, first));
        Manager healthy = new Manager();
        broadcast(healthy, true);
        assertEquals(1, first.connected);
        RootService.unbind(first);
        assertEquals(1, healthy.unbinds);
    }

    @Test public void failedHandshakeCannotLeaveARequestWaitingUntilTimeout() {
        Intent intent = intent("example.Root", true);
        Connection connection = new Connection();
        assertNotNull(bind(intent, connection));
        Manager manager = new Manager();
        manager.failConnect = true;
        broadcast(manager, true);
        assertEquals(List.of(BindingFailure.BIND_FAILED), connection.failures);
        assertNotNull(bind(intent, connection));
        RootService.unbind(connection);
        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS);
        assertEquals(1, connection.failures.size());
    }

    @Test public void disconnectCallbackCanRebindWithoutLosingTheNewConnection() {
        Intent intent = intent("example.Root", true);
        Connection connection = new Connection();
        assertNotNull(bind(intent, connection));
        Manager manager = new Manager();
        broadcast(manager, true);
        connection.onDisconnected = () -> assertNull(bind(intent, connection));

        Message stop = Message.obtain();
        stop.what = RootServiceManager.MSG_STOP;
        stop.obj = intent.getComponent();
        stop.arg1 = 1;
        RootServiceManager.getInstance().handleMessage(stop);
        assertEquals(2, connection.connected);
        assertEquals(2, manager.binds);
        connection.onDisconnected = null;
        RootService.unbind(connection);
        assertEquals(1, manager.unbinds);
    }

    @Test public void callbackCanCancelAnotherPendingRequest() {
        Intent firstIntent = intent("example.First", true);
        Connection first = new Connection();
        Connection second = new Connection();
        assertNotNull(bind(firstIntent, first));
        assertNull(bind(intent("example.Second", true), second));
        first.onConnected = () -> RootService.unbind(second);
        Manager manager = new Manager();
        broadcast(manager, true);
        assertEquals(1, manager.binds);
        assertEquals(0, second.connected);
        assertTrue(second.failures.isEmpty());
        RootService.unbind(first);
    }

    @Test public void failureCallbackCanStartNewAttemptWithoutOldFailureRemovingIt() {
        Intent intent = intent("example.Root", true);
        Connection connection = new Connection();
        assertNotNull(bind(intent, connection));
        List<Shell.Task> retries = new ArrayList<>();
        connection.onFailure = () -> retries.add(bind(intent, connection));
        Manager dead = new Manager();
        dead.failBind = true;
        broadcast(dead, true);
        assertEquals(1, retries.size());
        assertNotNull(retries.get(0));
        connection.onFailure = null;
        broadcast(new Manager(), true);
        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS);
        assertEquals(1, connection.connected);
        assertEquals(List.of(BindingFailure.BIND_FAILED), connection.failures);
        RootService.unbind(connection);
    }

    @Test public void daemonBroadcastDoesNotFinishNonDaemonStartup() {
        Connection daemon = new Connection();
        Connection remote = new Connection();
        assertNotNull(bind(intent("example.Daemon", true), daemon));
        assertNotNull(bind(intent("example.Remote", false), remote));
        broadcast(new Manager(), true);
        assertEquals(1, daemon.connected);
        assertEquals(0, remote.connected);
        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS);
        assertTrue(daemon.failures.isEmpty());
        assertEquals(List.of(BindingFailure.STARTUP_TIMEOUT), remote.failures);
        RootService.unbind(daemon);
    }

    @Test public void cancelledAndTimedOutTasksCannotLaunchLater() throws Exception {
        Intent intent = intent("example.Root", true);
        Connection cancelled = new Connection();
        Shell.Task old = bind(intent, cancelled);
        assertNotNull(old);
        RootService.unbind(cancelled);
        Connection next = new Connection();
        Shell.Task retry = bind(intent, next);
        assertNotNull(retry);
        ByteArrayOutputStream commands = new ByteArrayOutputStream();
        old.run(commands, new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0]));
        old.shellDied();
        ShadowLooper.idleMainLooper();
        assertEquals(0, commands.size());
        assertTrue(cancelled.failures.isEmpty());
        assertTrue(next.failures.isEmpty());

        ShadowLooper.idleMainLooper(31, TimeUnit.SECONDS);
        assertEquals(List.of(BindingFailure.STARTUP_TIMEOUT), next.failures);
        retry.run(commands, new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0]));
        assertEquals(0, commands.size());
    }

    @Test public void rootUnavailableAndShellFailureHaveDifferentReasons() {
        Intent intent = intent("example.Root", true);
        Connection connection = new Connection();
        RootServiceManager.StartupTask unavailable =
                (RootServiceManager.StartupTask) bind(intent, connection);
        assertNotNull(unavailable);
        unavailable.rootUnavailable();
        ShadowLooper.idleMainLooper();
        Shell.Task failed = bind(intent, connection);
        assertNotNull(failed);
        failed.shellDied();
        ShadowLooper.idleMainLooper();
        assertEquals(List.of(BindingFailure.ROOT_UNAVAILABLE, BindingFailure.STARTUP_FAILED),
                connection.failures);
    }

    @Test public void nullBinderHasAnExplicitFailureAndCanBeRetried() {
        Intent intent = intent("example.Root", true);
        Connection connection = new Connection();
        assertNotNull(bind(intent, connection));
        Manager manager = new Manager();
        manager.nullBinding = true;
        broadcast(manager, true);
        assertEquals(List.of(BindingFailure.NULL_BINDING), connection.failures);
        manager.nullBinding = false;
        assertNull(bind(intent, connection));
        assertEquals(1, connection.connected);
        RootService.unbind(connection);
    }

    @Test public void repeatedBindOfSameConnectionDoesNotLeakAReference() {
        Intent intent = intent("example.Root", true);
        Connection connection = new Connection();
        assertNotNull(bind(intent, connection));
        assertNull(bind(intent, connection));
        Manager manager = new Manager();
        broadcast(manager, true);
        assertNull(bind(intent, connection));
        assertEquals(1, connection.connected);
        RootService.unbind(connection);
        assertEquals(1, manager.unbinds);
    }

    @Test @Config(sdk = 26) public void legacyConnectionReceivesFailureOnAndroidEight() {
        int[] failures = {0};
        ServiceConnection legacy = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {}
            @Override public void onServiceDisconnected(ComponentName name) { failures[0]++; }
        };
        assertNotNull(bind(intent("example.Root", true), legacy));
        Manager manager = new Manager();
        manager.nullBinding = true;
        broadcast(manager, true);
        assertEquals(1, failures[0]);
    }
}
