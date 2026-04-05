#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/target/classes"
RESOURCES_DIR="$ROOT_DIR/src/main/resources"
MAIN_CLASS="com.uniongroup.sms.SmsApplication"
DEFAULT_JAR="/usr/share/java/mysql-connector-j-9.6.0.jar"

# Load local environment values automatically when a .env file is present.
if [[ -f "$ROOT_DIR/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$ROOT_DIR/.env"
    set +a
fi

JDBC_JAR="${JDBC_JAR:-$DEFAULT_JAR}"
PORT_VALUE="${PORT:-9090}"

require_env() {
    local name="$1"
    if [[ -z "${!name:-}" ]]; then
        echo "Missing required environment variable: $name" >&2
        exit 1
    fi
}

require_env SMS_DB_URL
require_env SMS_DB_USER
require_env SMS_DB_PASSWORD

stop_existing_server() {
    local existing_pid existing_cmd

    if ! command -v lsof >/dev/null 2>&1; then
        return
    fi

    existing_pid="$(lsof -tiTCP:"$PORT_VALUE" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -z "$existing_pid" ]]; then
        return
    fi

    existing_cmd="$(ps -p "$existing_pid" -o cmd= 2>/dev/null || true)"
    if [[ "$existing_cmd" != *"$MAIN_CLASS"* ]]; then
        echo "Port $PORT_VALUE is already in use by another process:" >&2
        echo "$existing_cmd" >&2
        exit 1
    fi

    echo "Stopping existing $MAIN_CLASS process on port $PORT_VALUE (PID $existing_pid)..."
    kill "$existing_pid"

    for _ in {1..20}; do
        if ! kill -0 "$existing_pid" 2>/dev/null; then
            return
        fi
        sleep 0.25
    done

    echo "Existing process did not stop cleanly; please stop PID $existing_pid manually." >&2
    exit 1
}

if [[ ! -f "$JDBC_JAR" ]]; then
    echo "MySQL JDBC jar not found at: $JDBC_JAR" >&2
    echo "Set JDBC_JAR to the correct mysql-connector-j path on this host." >&2
    exit 1
fi

stop_existing_server

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

javac -cp "$JDBC_JAR" -d "$OUT_DIR" "$ROOT_DIR"/src/main/java/com/uniongroup/sms/*.java

if [[ -d "$RESOURCES_DIR" ]]; then
    cp -R "$RESOURCES_DIR"/. "$OUT_DIR"/
fi

exec java -cp "$OUT_DIR:$JDBC_JAR" "$MAIN_CLASS" "$PORT_VALUE"
