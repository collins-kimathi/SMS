#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/target/classes"
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

if [[ ! -f "$JDBC_JAR" ]]; then
    echo "MySQL JDBC jar not found at: $JDBC_JAR" >&2
    echo "Set JDBC_JAR to the correct mysql-connector-j path on this host." >&2
    exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

javac -cp "$JDBC_JAR" -d "$OUT_DIR" "$ROOT_DIR"/src/main/java/com/uniongroup/sms/*.java

exec java -cp "$OUT_DIR:$JDBC_JAR" "$MAIN_CLASS" "$PORT_VALUE"
