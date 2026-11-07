#!/bin/bash
# Builds the image from target/*.jar -> run build_jar.sh first.
set -euo pipefail
cd "$(dirname "$0")/.."

ls target/*.jar > /dev/null 2>&1 || { echo "target/*.jar not found, run .script/build_jar.sh first" >&2; exit 1; }

docker build -f .docker/Dockerfile -t outbound-gateway:0.0.1-SNAPSHOT .
