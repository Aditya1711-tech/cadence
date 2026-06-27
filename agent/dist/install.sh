#!/usr/bin/env bash
# Install the Cadence local agent as a per-user background service.
# macOS -> launchd LaunchAgent; Linux -> systemd user unit. Starts on login and
# restarts on crash. Re-runnable (idempotent). See README.md.
set -euo pipefail

LABEL="com.cadence.agent"
BIN_NAME="cadence-agent"
DIST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$(cd "$DIST_DIR/.." && pwd)" # the agent/ go module
PREFIX="${CADENCE_PREFIX:-$HOME/.local/bin}"

# 1. Locate or build the binary.
BINARY="${CADENCE_BINARY:-}"
if [[ -z "$BINARY" ]]; then
  if [[ -x "$MODULE_DIR/$BIN_NAME" ]]; then
    BINARY="$MODULE_DIR/$BIN_NAME"
  elif command -v go >/dev/null 2>&1; then
    echo "building $BIN_NAME ..."
    (cd "$MODULE_DIR" && go build -o "$BIN_NAME" ./cmd/cadence-agent)
    BINARY="$MODULE_DIR/$BIN_NAME"
  else
    echo "error: no prebuilt binary found and Go is not installed; set CADENCE_BINARY" >&2
    exit 1
  fi
fi

# 2. Install the binary to a stable location.
mkdir -p "$PREFIX"
install -m 0755 "$BINARY" "$PREFIX/$BIN_NAME"
TARGET="$PREFIX/$BIN_NAME"
echo "installed binary -> $TARGET"

# 3. Install the platform service unit.
case "$(uname -s)" in
Darwin)
  LOGDIR="$HOME/Library/Logs/Cadence"
  mkdir -p "$LOGDIR"
  PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
  mkdir -p "$(dirname "$PLIST")"
  sed -e "s#__BINARY__#$TARGET#g" -e "s#__LOGDIR__#$LOGDIR#g" \
    "$DIST_DIR/launchd/$LABEL.plist" >"$PLIST"
  launchctl unload "$PLIST" 2>/dev/null || true
  launchctl load "$PLIST"
  echo "launchd agent loaded: $PLIST"
  ;;
Linux)
  UNIT_DIR="$HOME/.config/systemd/user"
  mkdir -p "$UNIT_DIR"
  sed -e "s#__BINARY__#$TARGET#g" \
    "$DIST_DIR/systemd/$BIN_NAME.service" >"$UNIT_DIR/$BIN_NAME.service"
  systemctl --user daemon-reload
  systemctl --user enable --now "$BIN_NAME.service"
  # survive logout (headless/SSH sessions)
  loginctl enable-linger "$USER" 2>/dev/null || true
  echo "systemd user unit enabled: $UNIT_DIR/$BIN_NAME.service"
  ;;
*)
  echo "error: unsupported OS '$(uname -s)' (service install supports macOS + Linux)" >&2
  echo "       the binary still runs in the foreground: $TARGET" >&2
  exit 1
  ;;
esac

echo "done. health check: curl -s http://127.0.0.1:47821/healthz"
