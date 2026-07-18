# BaiZe v2 Alpha 38 — Logs Dual UI

- Adds a shared `LogsUiState` and `LogsUiActions` contract.
- Adds independent Material 3 and Miuix runtime log screens.
- Reuses current service state, task phase, scheduler status and persisted task history.
- Adds refresh, Root reconnect, cleanup audit and crash diagnostic actions.
- Adds a dedicated Logs destination while keeping Settings available.
- Uses compact five-item navigation without horizontal label expansion.
- Does not change RootService, cleaner.sh, task state machines or history storage.
