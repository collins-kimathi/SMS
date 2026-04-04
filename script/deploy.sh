#!/usr/bin/env bash

# Compatibility wrapper for people who type "script/deploy.sh" by habit.
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/scripts/deploy.sh" "$@"
