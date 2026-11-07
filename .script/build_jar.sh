#!/bin/bash
# Builds target/*.jar. Runs the tests too (they need Docker for Testcontainers); pass -DskipTests to skip them.
set -euo pipefail
cd "$(dirname "$0")/.."

mvn clean install "$@"
