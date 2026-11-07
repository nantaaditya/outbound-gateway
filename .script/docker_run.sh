#!/bin/bash
# Runs the image. Configuration comes from .env/dev.env (copy .env/dev.env.example and fill it in).
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=".env/dev.env"
[ -f "$ENV_FILE" ] || { echo "$ENV_FILE not found, copy .env/dev.env.example to it first" >&2; exit 1; }

docker rm -f outbound-gateway > /dev/null 2>&1 || true

docker run -d --cpus="0.5" --memory="768m" -p 8080:8080 -p 1000:1000 \
  --env-file "$ENV_FILE" --name outbound-gateway outbound-gateway:0.0.1-SNAPSHOT
