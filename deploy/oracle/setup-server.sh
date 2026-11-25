#!/usr/bin/env bash
# One-time setup of a fresh Oracle Cloud Ubuntu VM for MundoFlag.
# Run on the VM as the default "ubuntu" user:  bash setup-server.sh
set -euo pipefail

REPO_URL="${REPO_URL:-https://github.com/Kalma57/MundoFlag.git}"
APP_DIR="$HOME/MundoFlag"

echo "==> System updates (security patches install themselves from now on)"
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get -y upgrade
sudo DEBIAN_FRONTEND=noninteractive apt-get -y install unattended-upgrades git ca-certificates curl

echo "==> Docker"
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sudo sh
  sudo usermod -aG docker "$USER"
fi

echo "==> Firewall: allow only SSH, HTTP and HTTPS"
# Oracle's Ubuntu images ship iptables rules that reject everything except SSH,
# so web traffic has to be let in here as well as in the cloud console.
sudo iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || sudo iptables -I INPUT 5 -p tcp --dport 80 -j ACCEPT
sudo iptables -C INPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null || sudo iptables -I INPUT 5 -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save

echo "==> SSH: keys only, no passwords, no root login"
sudo sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/; s/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sudo systemctl restart ssh

echo "==> Code"
if [ ! -d "$APP_DIR" ]; then
  git clone "$REPO_URL" "$APP_DIR"
fi

echo
echo "Done. Log out and back in (so the docker group applies), then run:"
echo "  cd $APP_DIR/deploy/oracle && bash deploy.sh"
echo "and, so every new commit on main deploys by itself within 5 minutes:"
echo "  bash install-auto-update.sh"
