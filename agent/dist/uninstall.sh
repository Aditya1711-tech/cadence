#!/usr/bin/env bash
# Remove the Cadence local agent service. Leaves the binary, local DB, and
# keychain key in place (delete those manually if you want a full wipe).
set -euo pipefail

LABEL="com.cadence.agent"
BIN_NAME="cadence-agent"

case "$(uname -s)" in
Darwin)
  PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
  launchctl unload "$PLIST" 2>/dev/null || true
  rm -f "$PLIST"
  echo "removed launchd agent: $PLIST"
  ;;
Linux)
  systemctl --user disable --now "$BIN_NAME.service" 2>/dev/null || true
  rm -f "$HOME/.config/systemd/user/$BIN_NAME.service"
  systemctl --user daemon-reload 2>/dev/null || true
  echo "removed systemd user unit"
  ;;
*)
  echo "error: unsupported OS '$(uname -s)'" >&2
  exit 1
  ;;
esac

echo "note: binary, local DB, and keychain key were left in place."
