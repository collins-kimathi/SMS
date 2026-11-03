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
- PowerShell 7 or Windows PowerShell 5.1 to use the included Windows scripts

## Run on Windows

Open PowerShell in the project directory. Install a JDK if needed:

```powershell
winget install Microsoft.OpenJDK.21
```

Open a new PowerShell window and confirm Java is available:

```powershell
java -version
javac -version
```

Create and seed the database (enter your MySQL root password when prompted):

```powershell
Get-Content .\database\schema.sql | mysql -u root -p
```

Create local configuration and edit it with the credentials for a MySQL user that can access `sms_db`:

```powershell
Copy-Item .env.example .env
notepad .env
```

Place a `mysql-connector-j*.jar` file somewhere under `lib\`. The deploy script discovers it automatically. If you keep the jar elsewhere, set `JDBC_JAR` in `.env` to its full path.

Start the server:

```powershell
.\setup_sms.ps1
```

The script compiles the Java source and starts the server on port 9090 by default. Open <http://localhost:9090>. To use another port, set `PORT` in `.env`.

You can run `script\deploy.ps1` directly after creating `.env` and placing the JDBC jar.

## Run manually

From the project root, configure `SMS_DB_URL`, `SMS_DB_USER`, and `SMS_DB_PASSWORD` in the environment, then compile and run (replace the jar path if needed):

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
