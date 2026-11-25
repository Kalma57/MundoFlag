#!/usr/bin/env bash
# Installs the automatic update: every 5 minutes the server checks GitHub's main
# and redeploys when there is a new commit. Run again after changing auto-update.sh.
#   bash install-auto-update.sh
# Logs:    journalctl -u mundoflag-update -n 50
# Pause:   sudo systemctl stop mundoflag-update.timer   (start = resume)
set -euo pipefail
cd "$(dirname "$0")"

# the timer runs a copy outside the repo, so a git pull never swaps the script mid-run
sudo install -m 755 auto-update.sh /usr/local/bin/mundoflag-auto-update

sudo tee /etc/systemd/system/mundoflag-update.service >/dev/null <<'EOF'
[Unit]
Description=MundoFlag: deploy new commits from GitHub main
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=oneshot
User=ubuntu
ExecStart=/usr/local/bin/mundoflag-auto-update
Nice=10
EOF

sudo tee /etc/systemd/system/mundoflag-update.timer >/dev/null <<'EOF'
[Unit]
Description=MundoFlag: check GitHub main every 5 minutes

[Timer]
OnBootSec=3min
OnUnitActiveSec=5min

[Install]
WantedBy=timers.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now mundoflag-update.timer
systemctl list-timers mundoflag-update.timer --no-pager
