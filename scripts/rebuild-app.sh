#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

docker compose build app
docker compose up -d --force-recreate --no-deps app
