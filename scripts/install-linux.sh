#!/usr/bin/env bash
# Per-user install script for the Netodia tarball release.
#
# Run this from inside the unpacked tarball directory:
#   tar -xzf netodia-v0.1.0-x86_64-linux.tar.gz
#   cd netodia
#   ./install-linux.sh
#
# What this does:
#   1. Copies the `Netodia` binary to ~/.local/share/Netodia/Netodia
#   2. Drops the .desktop file at
#      ~/.local/share/applications/com.github.helpmeeadice.netodia.desktop
#   3. Drops the PNG icon at
#      ~/.local/share/icons/hicolor/256x256/apps/netodia.png
#   4. Best-effort desktop + icon cache refresh
#
# Both names match the RPM/DEB destinations — the desktop one so software
# centres merge it with the AppStream component, and both so switching
# install paths leaves no duplicate launcher or icon-cache entry.
#
# No sudo required, and the in-app updater needs no polkit prompt either:
# the install path is user-writable.
#
# Uninstall:
#   ./uninstall-linux.sh
#
# Environment overrides:
#   NETODIA_INSTALL_DIR   — install dir (default: ~/.local/share/Netodia)
#   XDG_DATA_HOME         — base for ~/.local/share

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
INSTALL_DIR="${NETODIA_INSTALL_DIR:-$DATA_HOME/Netodia}"
APPS_DIR="$DATA_HOME/applications"
ICONS_DIR="$DATA_HOME/icons/hicolor/256x256/apps"

# Verify the tarball contents we need are present next to this script.
need() {
  [[ -f "$SCRIPT_DIR/$1" ]] || { echo "ERROR: $1 missing — extracted tarball is incomplete." >&2; exit 1; }
}
need Netodia
need com.github.helpmeeadice.netodia.desktop
need netodia.png

echo "==> installing Netodia to $INSTALL_DIR"
mkdir -p "$INSTALL_DIR" "$APPS_DIR" "$ICONS_DIR"

DESKTOP_FILE="com.github.helpmeeadice.netodia.desktop"
install -m 0755 "$SCRIPT_DIR/Netodia"           "$INSTALL_DIR/Netodia"
install -m 0644 "$SCRIPT_DIR/$DESKTOP_FILE"     "$APPS_DIR/$DESKTOP_FILE"
install -m 0644 "$SCRIPT_DIR/netodia.png"       "$ICONS_DIR/netodia.png"
# Remove the icon deployed by earlier SVG-based releases.
rm -f "$DATA_HOME/icons/hicolor/scalable/apps/netodia.svg" \
      "$DATA_HOME/icons/hicolor/scalable/apps/Netodia.svg"

# Earlier per-user installs used these names; a leftover shows as a duplicate
# launcher for the same binary. Mirrored in uninstall-linux.sh.
rm -f "$APPS_DIR/Netodia.desktop" "$APPS_DIR/netodia.desktop"

# The AppStream MetaInfo isn't installed here — Netodia self-deploys it to
# ~/.local/share/metainfo/ on first launch (see desktop_integration.rs).

# LICENSE is informational. `licenses/` is not: the bundled fonts and the
# vendored winit fork compile into the binary, and Apache-2.0 §4(a) requires
# the text to travel with what it covers (see licenses/ATTRIBUTION.txt).
#
# Guard on the expansion, not the directory: nullglob is off, so an absent
# licenses/ leaves the pattern literal and `install` fails the whole script
# under `set -e` — halfway through, with the Exec rewrite still to come. `-D`
# creates the target directory, which plain `-t` will not.
LICENSE_FILES=("$SCRIPT_DIR"/licenses/*)
[[ -f "$SCRIPT_DIR/LICENSE" ]] && install -m 0644 "$SCRIPT_DIR/LICENSE" "$INSTALL_DIR/LICENSE"
[[ -e "${LICENSE_FILES[0]}" ]] && install -D -m 0644 -t "$INSTALL_DIR/licenses" "${LICENSE_FILES[@]}"

# Point Exec at the real install path — the tarballed .desktop assumes the
# binary is on $PATH. GNU sed (universal on the target distros) takes `-i`
# with no backup suffix.
#
# Only the command token, never the rest of the line: a `.*` here eats the
# ` %F` field code that makes file-opening work — silently, and only in the
# tarball, since the DEB ships this same source file verbatim.
#
# Exec is parsed with shell-like quoting, so a home directory with a space
# needs quotes or the launcher splits it into two arguments. Only when
# needed, an unquoted command being what every other source ships.
case "$INSTALL_DIR" in
  *\ *) EXEC_COMMAND="\"$INSTALL_DIR/Netodia\"" ;;
  *)    EXEC_COMMAND="$INSTALL_DIR/Netodia" ;;
esac
sed -i "s|^Exec=[^ ]*|Exec=$EXEC_COMMAND|" "$APPS_DIR/$DESKTOP_FILE"

# Best-effort: the session picks the changes up on next start regardless.
if command -v update-desktop-database >/dev/null 2>&1; then
  update-desktop-database -q "$APPS_DIR" || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
  gtk-update-icon-cache -q -t "$DATA_HOME/icons/hicolor" || true
fi

cat <<EOF

✓ Netodia installed.

  Binary    : $INSTALL_DIR/Netodia
  Launcher  : $APPS_DIR/$DESKTOP_FILE
  Icon      : $ICONS_DIR/netodia.png

Run it from your application launcher, or directly:
    $INSTALL_DIR/Netodia

Optional: add Netodia to your PATH:
    mkdir -p \$HOME/.local/bin
    ln -sf "$INSTALL_DIR/Netodia" \$HOME/.local/bin/netodia

Uninstall: ./uninstall-linux.sh (or rm -rf $INSTALL_DIR and the .desktop/icon)
EOF
