# Security Management System

A plain Java and MySQL web application for managing security operations. The server uses Java's built-in HTTP server; the interface is static HTML, CSS, and JavaScript. There is no Maven or Gradle build.

## Features

- User accounts, roles, and database-backed sessions
- Employee, visitor, and access log management
- Incident reporting and audit history
- CCTV, alarms, notifications, subsidiaries, and operational reports
- CSV report export

## Requirements

- JDK 21 or newer (`java` and `javac`)
- MySQL 8 or newer, running locally or reachable over the network
- MySQL Connector/J jar
- A shell to use the included launcher (`PowerShell` on Windows or `sh` on Linux/macOS)
- MySQL Connector/J (the JDBC driver; MySQL Server alone does not include the Java driver)

## Setup (Windows, Linux, and macOS)

Install a JDK 21 or newer and MySQL 8 or newer. Start MySQL, then create and seed the database (enter your MySQL root password when prompted):

```sh
mysql -u root -p < database/schema.sql
```

The schema creates `sms_db` and starter accounts. Create a dedicated MySQL user with access to that database, or configure another existing user in `.env`.

Copy `.env.example` to `.env` and set the connection URL, username, and password. The default URL works for a local MySQL server on port 3306:

```text
SMS_DB_URL=jdbc:mysql://localhost:3306/sms_db?serverTimezone=UTC
SMS_DB_USER=sms_user
SMS_DB_PASSWORD=your_password
PORT=9090
```

Place a `mysql-connector-j*.jar` file under `lib/`, or set `JDBC_JAR` in `.env` to its path. The project includes a copy under `lib/`.

Start the app from the project directory:

```powershell
.\setup_sms.ps1
```

```sh
sh ./run.sh
```

Open <http://localhost:9090>. Both launchers compile the Java source before starting the server. To use another port, set `PORT` in `.env`.

For Windows, install a JDK if needed:

```powershell
winget install Microsoft.OpenJDK.21
```

Open a new PowerShell window and confirm Java is available:

```powershell
java -version
javac -version
```

Create local configuration:

```powershell
Copy-Item .env.example .env
notepad .env
```

The Windows script discovers the included JDBC driver automatically.

## Run manually

The launchers load `.env` automatically. For a manual run, configure `SMS_DB_URL`, `SMS_DB_USER`, and `SMS_DB_PASSWORD`, then compile and run (replace the jar path if needed). On Windows use `;` between classpath entries; on Linux/macOS use `:`.

```powershell
$jdbc = (Get-ChildItem .\lib -Filter 'mysql-connector-j*.jar' -Recurse | Select-Object -First 1).FullName
New-Item -ItemType Directory -Force .\target\classes | Out-Null
$sources = Get-ChildItem .\src\main\java -Filter '*.java' -Recurse | ForEach-Object FullName
javac -cp $jdbc -d .\target\classes $sources
java -cp ".\target\classes;$jdbc" com.uniongroup.sms.SmsApplication 9090
```

## Starter accounts

The schema seeds these accounts for local development:

| Role | Username | Password |
|---|---|---|
| Administrator | `admin` | `Admin@Union2026!` |
| Security officer | `officer` | `Officer@Union2026!` |
| Management | `manager` | `Officer@Union2026!` |

Change these passwords before using the system outside a local development environment.

## Configuration and repository hygiene

- Copy `.env.example` to `.env` and keep real credentials in `.env` only. `.env` is ignored by Git.
- Never commit database passwords, production data, or private keys.
- Build output is written to `target/`, which is also ignored by Git.
- The schema is in `database/schema.sql`; static pages are in `src/main/resources/static/`.

## License

No license is currently specified. Add a `LICENSE` file before publishing this repository if you want others to have explicit permission to use, modify, or distribute the code.
