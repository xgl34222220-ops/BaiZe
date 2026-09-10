# libsu service 6.0.0 local maintenance

Source: https://github.com/topjohnwu/libsu/tree/6.0.0/service

Copyright John Wu; Apache License 2.0 (see LICENSE). BaiZe continues to use the
unmodified Maven `core:6.0.0` artifact. Only the service component is compiled from
these sources; the upstream service AAR is removed to avoid duplicate classes.

Local behavior changes to RootService.java and RootServiceManager.java:

- Report unavailable Root / startup task failure instead of silently returning.
- Clear pending binds and launch flags after a failed startup; retain a per-attempt
  token so a delayed failure cannot cancel a newer attempt.
- Bound startup to 30 seconds when the daemon never broadcasts its Binder.
- Remove pending binds on unbind, so cancelled Activities are not reconnected later.
- Remove the receiver registration branch below BaiZe minSdk 26; retain the
  permission guard and NOT_EXPORTED flag.
- Process a snapshot of pending requests because callbacks can modify the queue.
- Complete pending requests with a failure when daemon handshake/binding fails after
  its broadcast, rather than leaving requests without an active startup timeout.
- Remove disconnected records before callbacks so synchronous rebinds survive;
  repeated binds of an already tracked connection do not add extra references.
- Skip queued launch tasks whose startup attempt was cancelled or timed out, and
  ignore broadcasts when no matching daemon/non-daemon request is pending.
- Expose optional `RootService.Connection.onBindingFailed` reasons for unavailable
  Root, shell/startup failure, startup timeout, Binder handshake failure and null
  binding. Existing `ServiceConnection` callbacks remain supported on API 26+.

The startup timeout covers waiting for a broadcast; it does not interrupt a
synchronous Binder call that blocks the client main thread. Cancellation prevents
not-yet-started launch tasks, not an already running root process. Broadcasts do
not carry an attempt token, so this patch does not authenticate a late broadcast
as belonging to a particular startup attempt. No daemon is forcibly stopped.

Related upstream proposal: https://github.com/topjohnwu/libsu/pull/211
This is based on the stable 6.0.0 sources, not an unmerged fork dependency.
The task-failure, cancellation and missing-broadcast paths are covered by
`RootServiceStartupTest`. `RootServiceRecoveryTest` also exercises manager
broadcasts with fake Binder services: failed handshake/bind, callback cancellation
and rebind, daemon/non-daemon isolation, failure reasons, queued-task cancellation
and reference cleanup. These are Robolectric client state-machine tests, not
rooted-device IPC tests; a device is still needed to validate vendor ROM startup
restrictions and actual process death/recovery.

`src/main/assets/main.jar` is the unchanged 6.0.0 bootstrap artifact; the source
RootServerMain.java and IRootServiceManager.aidl are included unchanged. If either
bootstrap input changes, regenerate main.jar using the upstream build procedure.

The four service classes using upstream core internals carry a scoped RestrictedApi
suppression: they are still one pinned libsu implementation, now compiled as app
sources. No app-wide lint rule or baseline is disabled. HiddenAPIs' existing
private-API suppression is expressed as individual annotation values.
