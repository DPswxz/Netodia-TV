#!/usr/bin/env bash
# Uninstall the per-user Netodia install created by install-linux.sh.
#
# Per-user data (queue.json, settings.json, netodia.db, artwork/) is
# left in place at $XDG_DATA_HOME/Netodia/ — that directory is shared
# with `dnf`-installed builds and contains the user's library, so we
# don't touch it. To wipe data too, follow the printed hint.
#
# Environment overrides:
#   NETODIA_INSTALL_DIR   — install dir (default: ~/.local/share/Netodia)
#   XDG_DATA_HOME         — base for ~/.local/share

set -euo pipefail

DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
INSTALL_DIR="${NETODIA_INSTALL_DIR:-$DATA_HOME/Netodia}"
APPS_DIR="$DATA_HOME/applications"
ICONS_DIR="$DATA_HOME/icons/hicolor/scalable/apps"

removed=0
remove() {
  if [[ -e "$1" ]]; then
    rm -f "$1"
    echo "  removed $1"
    removed=$((removed + 1))
  fi
}

# `remove` can't take the third-party licence dir — its contents are whatever
# `licenses/` held at package time, so removing them by name here would drift
# the moment a fourth file lands there.
remove_dir() {
  if [[ -d "$1" ]]; then
    rm -rf "$1"
    echo "  removed $1"
    removed=$((removed + 1))
  fi
}

echo "==> uninstalling Netodia from $INSTALL_DIR"
remove "$INSTALL_DIR/Netodia"
remove "$INSTALL_DIR/LICENSE"
remove_dir "$INSTALL_DIR/licenses"
# Try to rmdir the install dir; non-fatal if user data is mixed in.
[[ -d "$INSTALL_DIR" ]] && rmdir --ignore-fail-on-non-empty "$INSTALL_DIR" 2>/dev/null || true

remove "$APPS_DIR/com.github.helpmeeadice.netodia.desktop"
# Legacy: earlier per-user installs used `Netodia.desktop` (capital M)
# and then `netodia.desktop`. Mirrors the `Netodia.svg` cleanup below.
remove "$APPS_DIR/Netodia.desktop"
remove "$APPS_DIR/netodia.desktop"
# AppStream MetaInfo — Netodia self-deploys this to the per-user
# metainfo dir on first launch (see desktop_integration.rs).
remove "$DATA_HOME/metainfo/com.github.helpmeeadice.netodia.metainfo.xml"
remove "$ICONS_DIR/netodia.svg"
# Legacy: earlier dev builds installed the icon as `Netodia.svg`
# (uppercase). Best-effort cleanup so a re-install with the corrected
# lowercase name doesn't leave a stale capitalised entry behind.
remove "$ICONS_DIR/Netodia.svg"

# Symlink under ~/.local/bin/ — only created if the user opted in
# during install (mentioned in install script's tail). Best-effort.
[[ -L "$HOME/.local/bin/netodia" ]] && remove "$HOME/.local/bin/netodia" || true

# Cache refresh.
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database -q "$APPS_DIR" || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -q -t "$DATA_HOME/icons/hicolor" || true
fi

if [[ "$removed" -eq 0 ]]; then
  echo "Nothing to remove — Netodia wasn't installed at $INSTALL_DIR."
  exit 0
fi

cat <<EOF

✓ Uninstalled $removed file(s).

User data (library, settings, queue) is preserved at:
    $DATA_HOME/Netodia/

To wipe everything including data, also run:
    rm -rf "$DATA_HOME/Netodia"
EOF
