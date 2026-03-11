#!/system/bin/sh
set -eu

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PATH="$PREFIX/bin:/system/bin:/system/xbin:$PATH"
export PREFIX
export HOME="$HOME_DIR"
export PATH

PACKAGE="@mmmbuto/codex-cli-termux"
TARGET_VERSION="${1:-latest}"
APP_SERVER_URL="ws://127.0.0.1:8765"
PACKAGE_DIR="$PREFIX/lib/node_modules/@mmmbuto/codex-cli-termux"
PACKAGE_BIN_DIR="$PACKAGE_DIR/bin"
PACKAGE_LAUNCHER="$PACKAGE_BIN_DIR/codex"
PACKAGE_EXEC_LAUNCHER="$PACKAGE_BIN_DIR/codex-exec"
WRAPPER_CLI="$PREFIX/bin/codex"
WRAPPER_EXEC="$PREFIX/bin/codex-exec"
PROXY_SCRIPT="$HOME_DIR/.codex-termux-proxy.sh"

say() {
  printf '%s\n' "$*"
}

require_normal_termux_user() {
  if [ "$(id -u)" = "0" ]; then
    say "Please run codex-update from the normal Termux user, not from su." >&2
    exit 1
  fi
}

stop_existing_codex() {
  pkill -f 'codex app-server --listen ws://127.0.0.1:8765' >/dev/null 2>&1 || true
  pkill -f '/@mmmbuto/codex-cli-termux/bin/codex.bin' >/dev/null 2>&1 || true
}

rewrite_launcher() {
  launcher="$1"
  tmp="$launcher.tmp"
  {
    printf '%s\n' '#!/system/bin/sh'
    tail -n +2 "$launcher"
  } > "$tmp"
  mv "$tmp" "$launcher"
  chmod 755 "$launcher"
}

write_wrapper() {
  target="$1"
  launcher="$2"
  tmp="$target.tmp"
  {
    printf '%s\n' '#!/system/bin/sh'
    printf '%s\n' "exec /system/bin/sh $launcher \"\$@\""
  } > "$tmp"
  rm -f "$target"
  mv "$tmp" "$target"
  chmod 755 "$target"
}

main() {
  require_normal_termux_user

  if [ -f "$PROXY_SCRIPT" ]; then
    # shellcheck disable=SC1090
    . "$PROXY_SCRIPT"
  fi

  was_running=0
  if ss -ltn 2>/dev/null | grep -q '127.0.0.1:8765'; then
    was_running=1
  fi

  stop_existing_codex

  say "Updating $PACKAGE@$TARGET_VERSION ..."
  npm install -g "$PACKAGE@$TARGET_VERSION"

  rewrite_launcher "$PACKAGE_LAUNCHER"
  rewrite_launcher "$PACKAGE_EXEC_LAUNCHER"
  write_wrapper "$WRAPPER_CLI" "$PACKAGE_LAUNCHER"
  write_wrapper "$WRAPPER_EXEC" "$PACKAGE_EXEC_LAUNCHER"

  say "Verifying codex ..."
  "$WRAPPER_CLI" --version
  "$WRAPPER_CLI" exec --help >/dev/null 2>&1 || true

  if [ "$was_running" = "1" ]; then
    say "Restarting app-server ..."
    nohup "$WRAPPER_CLI" app-server --listen "$APP_SERVER_URL" >/dev/null 2>&1 &
  fi

  say "codex-update finished."
}

main "$@"
