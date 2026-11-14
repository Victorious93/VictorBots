#!/usr/bin/env bash
# Installs the VictorBots PC runner on Ubuntu 22.04/24.04 (Python 3.10+, no pip packages).
# Usage: sudo bash install-ubuntu.sh --host 192.168.1.20 [--port 8443] [--workspace /home/me/work/app] [--user victorbots]
#        bash install-ubuntu.sh --prefix /tmp/vbtest --host 127.0.0.1 --no-systemd      (unprivileged test install)
# It generates a self-signed certificate for --host, a random bearer token (shown ONCE; only its hash is
# stored), a config with ONE read-only workspace, and a systemd unit. It never opens a firewall port.
set -euo pipefail
HOST=""; PORT=8443; WORKSPACE=""; USER_NAME=victorbots; PREFIX=""; SYSTEMD=1; UPSTREAM=""
while [ $# -gt 0 ]; do case "$1" in
  --host) HOST="$2"; shift 2;; --port) PORT="$2"; shift 2;; --workspace) WORKSPACE="$2"; shift 2;;
  --user) USER_NAME="$2"; shift 2;; --prefix) PREFIX="$2"; shift 2;; --no-systemd) SYSTEMD=0; shift;;
  --inference-upstream) UPSTREAM="$2"; shift 2;;
  *) echo "unknown option $1" >&2; exit 2;; esac; done
[ -n "$HOST" ] || { echo "--host (the LAN/VPN IP or DNS name the phone will use) is required" >&2; exit 2; }
command -v python3 >/dev/null && command -v openssl >/dev/null || { echo "python3 and openssl are required" >&2; exit 1; }
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3,9) else 1)' || { echo "Python 3.9+ required" >&2; exit 1; }
SRC="$(cd "$(dirname "$0")" && pwd)"
BIN="$PREFIX/opt/victorbots-runner"; ETC="$PREFIX/etc/victorbots-runner"; VAR="$PREFIX/var/lib/victorbots-runner"
mkdir -p "$BIN" "$ETC" "$VAR"
install -m 0644 "$SRC/victorbots_runner.py" "$BIN/victorbots_runner.py"
if [ "$SYSTEMD" = 1 ] && ! id "$USER_NAME" >/dev/null 2>&1; then useradd --system --home "$VAR" --shell /usr/sbin/nologin "$USER_NAME"; fi
if [ ! -f "$ETC/cert.pem" ]; then
  SAN="DNS:$HOST"; [[ "$HOST" =~ ^[0-9.]+$ || "$HOST" == *:* ]] && SAN="IP:$HOST"
  openssl req -x509 -newkey rsa:3072 -nodes -keyout "$ETC/key.pem" -out "$ETC/cert.pem" -days 825 -subj "/CN=victorbots-runner" -addext "subjectAltName=$SAN" 2>/dev/null
  chmod 0600 "$ETC/key.pem"
fi
TOKEN="$(python3 -c 'import secrets;print(secrets.token_urlsafe(32))')"
HASH="$(printf '%s' "$TOKEN" | python3 "$BIN/victorbots_runner.py" --config /dev/null --hash-token)"
FP="$(openssl x509 -in "$ETC/cert.pem" -outform DER | sha256sum | cut -d' ' -f1 | tr a-f A-F)"
WS_JSON="{}"; [ -n "$WORKSPACE" ] && WS_JSON="{\"main\":{\"path\":\"$WORKSPACE\",\"mode\":\"ro\"}}"
UP_JSON=""; [ -n "$UPSTREAM" ] && UP_JSON=",\"inference_upstream\":\"$UPSTREAM\""
if [ ! -f "$ETC/config.json" ]; then
cat > "$ETC/config.json" <<JSON
{
  "listen": {"host": "$HOST", "port": $PORT, "cert": "$ETC/cert.pem", "key": "$ETC/key.pem"},
  "token_sha256": "$HASH",
  "state_dir": "$VAR",
  "workspaces": $WS_JSON,
  "commands": {},
  "connectors": {}$UP_JSON
}
JSON
else
  echo "Existing config kept: $ETC/config.json (the token below does NOT apply to it; edit token_sha256 yourself)." >&2
fi
chown -R "$USER_NAME" "$VAR" 2>/dev/null || true
chgrp "$USER_NAME" "$ETC/key.pem" "$ETC/config.json" 2>/dev/null && chmod 0640 "$ETC/config.json" && chmod 0640 "$ETC/key.pem" || true
if [ "$SYSTEMD" = 1 ]; then
cat > /etc/systemd/system/victorbots-runner.service <<UNIT
[Unit]
Description=VictorBots PC runner
After=network-online.target
[Service]
User=$USER_NAME
ExecStart=/usr/bin/python3 $BIN/victorbots_runner.py --config $ETC/config.json
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=read-only
PrivateTmp=true
ReadWritePaths=$VAR
[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload; systemctl enable --now victorbots-runner.service
fi
cat <<MSG

VictorBots runner installed under ${PREFIX:-/}.
  Address for the phone : https://$HOST:$PORT/v1
  Access token (shown once, store it in the phone's PC profile): $TOKEN
  Certificate SHA-256   : $FP   <- paste this as the profile's certificate fingerprint
  Config                : $ETC/config.json  (workspaces are read-only until you edit them; commands: none)
  Firewall              : NOT changed. Allow TCP $PORT from your phone/VPN only, e.g.  sudo ufw allow from <phone-or-vpn-subnet> to any port $PORT proto tcp
MSG
