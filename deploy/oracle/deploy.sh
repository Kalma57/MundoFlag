#!/usr/bin/env bash
# Pull the latest main and (re)start MundoFlag. Safe to run again for every update.
set -euo pipefail
cd "$(dirname "$0")"

git -C ../.. fetch origin main
git -C ../.. checkout main
git -C ../.. pull --ff-only origin main

if [ ! -f .env ]; then
  printf 'SITE_ADDRESS=:80\nDAILY_QUIZ_KEY=\nSTATS_KEY=\n' > .env
fi
chmod 600 .env   # holds the daily-quiz key: readable by this user only

docker compose up -d --build
docker image prune -f >/dev/null
docker compose ps
