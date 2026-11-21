#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ROOT_DIR"

if [ ! -f .env ]; then
    cp .env.example .env
    echo "Created .env from .env.example. Edit it with your MySQL credentials, then run ./run.sh again." >&2
    exit 1
fi

# Load simple KEY=value configuration without evaluating it as shell code.
while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *=*) ;; *) continue ;; esac
    key=${line%%=*}
    value=${line#*=}
    key=$(printf '%s' "$key" | tr -d ' \t\r')
    case "$key" in SMS_DB_URL|SMS_DB_USER|SMS_DB_PASSWORD|PORT|JDBC_JAR)
        eval "provided=\${$key+x}"
        if [ "${provided:-}" != x ]; then
            # Strip matching single or double quotes, as EnvConfig does.
            case "$value" in
                \"*\") value=${value#\"}; value=${value%\"} ;;
                \'*\') value=${value#\'}; value=${value%\'} ;;
            esac
            export "$key=$value"
        fi
        ;;
    esac
done < .env

: "${SMS_DB_URL:?Set SMS_DB_URL in .env}"
: "${SMS_DB_USER:?Set SMS_DB_USER in .env}"
: "${SMS_DB_PASSWORD:?Set SMS_DB_PASSWORD in .env}"
PORT=${PORT:-9090}
case "$PORT" in *[!0-9]*|'') echo "PORT must be a number from 1 to 65535." >&2; exit 1 ;; esac
if [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
    echo "PORT must be a number from 1 to 65535." >&2
    exit 1
fi

JDBC_JAR=${JDBC_JAR:-}
if [ -z "$JDBC_JAR" ]; then
    for candidate in "$ROOT_DIR"/lib/mysql-connector-j*.jar; do
        if [ -f "$candidate" ]; then JDBC_JAR=$candidate; break; fi
    done
fi
case "$JDBC_JAR" in /*) ;; *) [ -z "$JDBC_JAR" ] || JDBC_JAR="$ROOT_DIR/$JDBC_JAR" ;; esac
if [ -z "$JDBC_JAR" ] || [ ! -f "$JDBC_JAR" ]; then
    echo "MySQL Connector/J jar not found. Put mysql-connector-j*.jar under lib/ or set JDBC_JAR in .env." >&2
    exit 1
fi

JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
JAVAC=${JAVA_HOME:+$JAVA_HOME/bin/}javac
command -v "$JAVA" >/dev/null 2>&1 || { echo "Install a JDK (java and javac) and set JAVA_HOME." >&2; exit 1; }
command -v "$JAVAC" >/dev/null 2>&1 || { echo "Install a JDK (java and javac) and set JAVA_HOME." >&2; exit 1; }

mkdir -p target/classes
find src/main/java -type f -name '*.java' -print > target/sources.list
"$JAVAC" -encoding UTF-8 -d target/classes @target/sources.list
cp -R src/main/resources/. target/classes/
exec "$JAVA" -cp "target/classes:$JDBC_JAR" com.uniongroup.sms.SmsApplication "$PORT"
