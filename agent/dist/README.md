# Cadence agent — background service (P1-A.9)

Installs the local agent as a per-user service that starts on login and
restarts on crash. No root required.

- **macOS:** a launchd LaunchAgent (`~/Library/LaunchAgents/com.cadence.agent.plist`).
  `RunAtLoad` starts it at login; `KeepAlive` restarts it on crash.
  `ProcessType=Background` + `LowPriorityIO` keep it under the idle-CPU budget.
- **Linux:** a systemd **user** unit (`~/.config/systemd/user/cadence-agent.service`).
  `Restart=on-failure` handles crashes; `loginctl enable-linger` makes it survive
  logout. `Nice`/`CPUWeight`/`MemoryMax` cap resource use.
- **Windows:** not covered by this task (launchd/systemd only). The binary runs
  fine in the foreground; auto-start via Task Scheduler / a Windows service is a
  later seam.

## Install

```sh
agent/dist/install.sh
```

It builds `cmd/cadence-agent` (or reuses an existing `agent/cadence-agent`, or
`CADENCE_BINARY`), installs it to `~/.local/bin` (override with `CADENCE_PREFIX`),
writes the platform unit with paths substituted, and enables it.

Verify:

```sh
curl -s http://127.0.0.1:47821/healthz
```

## Logs

- macOS: `~/Library/Logs/Cadence/cadence-agent.log` (+ `.err.log`)
- Linux: `journalctl --user -u cadence-agent -f`

## Uninstall

```sh
agent/dist/uninstall.sh
```

Removes the service unit only; the binary, local DB, and keychain key are left
in place.

## Status

The unit files and scripts are authored and syntax-checked. **Runtime install /
restart-on-crash / survive-logout behavior must be verified on macOS and Linux**
(this stream is developed on Windows — see the build log and project memory).
