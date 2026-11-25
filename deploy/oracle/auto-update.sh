#!/usr/bin/env bash
# Runs every 5 minutes (systemd timer, see install-auto-update.sh).
# If main on GitHub has a commit the server isn't running yet, redeploy with deploy.sh.
# The server only READS the public repo; nothing on GitHub can log into the server.
set -euo pipefail

APP_DIR="/home/ubuntu/MundoFlag"
FAILED_MARK="/home/ubuntu/.mundoflag-failed-commit"

# never run two deploys at once (e.g. a slow build overlapping the next check)
exec 9>/tmp/mundoflag-deploy.lock
flock -n 9 || exit 0

remote=$(git -C "$APP_DIR" ls-remote origin refs/heads/main | cut -f1)
running=$(git -C "$APP_DIR" rev-parse HEAD)

[ -n "$remote" ] || { echo "could not reach GitHub, will retry"; exit 0; }
[ "$remote" != "$running" ] || exit 0

# a commit that already failed to build isn't retried every 5 minutes;
# the next new commit (the fix) is tried as usual
if [ -f "$FAILED_MARK" ] && [ "$(cat "$FAILED_MARK")" = "$remote" ]; then
  exit 0
fi

echo "main moved ${running:0:7} -> ${remote:0:7}, deploying"
if bash "$APP_DIR/deploy/oracle/deploy.sh"; then
  rm -f "$FAILED_MARK"
  echo "deployed ${remote:0:7}"
else
  echo "$remote" > "$FAILED_MARK"
  echo "DEPLOY FAILED for ${remote:0:7} - the previous version keeps running" >&2
  exit 1
fi
