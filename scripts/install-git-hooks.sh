#!/bin/sh
set -e

ROOT_DIR=$(git rev-parse --show-toplevel)

git config core.hooksPath "$ROOT_DIR/.githooks"
chmod +x "$ROOT_DIR/.githooks/pre-commit"

echo "Git hooks installed."
echo "Git will now use: $ROOT_DIR/.githooks"
