# Security Management System

A plain Java + MySQL web application for managing:

- user accounts
- employees
- visitors
- access logs
- incident reports
- audit history
- operational reports

This project does not use Maven, Gradle, Spring Boot, or a frontend framework. It runs as a lightweight Java HTTP server with static HTML, CSS, and JavaScript.

## Features

- login with PBKDF2-hashed passwords
- database-backed server sessions
- role-based access control for `Admin` and `Security Officer`
- visitor registration with duplicate ID protection
- employee management
- access entry, exit, edit, and delete workflows
- incident creation, editing, and filtering
- user management
- reports for:
  - access logs
  - incidents
  - visitors
  - audit trail
- CSV export for reports
- audit logging for important system actions

## Tech stack

- Java
- MySQL
- JDBC
- HTML
- CSS
- Vanilla JavaScript

## Default accounts

The schema seeds these starter accounts:

- `admin / Admin@Union2026!`
- `officer / Officer@Union2026!`

Change them immediately after first login in any real deployment.

## Project structure

- `src/main/java/com/uniongroup/sms/SmsApplication.java`
- `src/main/resources/static/`
- `database/schema.sql`

## Requirements

Before running the project, make sure you have:

- Java installed
- MySQL installed and running
- MySQL Connector/J JDBC jar available on your machine

## Setup

### 1. Clone the repository

```bash
git clone <your-repo-url>
cd <your-repo-folder>
```

### 2. Create and seed the database

```bash
mysql -u root -p < database/schema.sql
```

The application now requires database environment variables and will not start without them:

```bash
export SMS_DB_URL='jdbc:mysql://your-db-host:3306/sms_db?serverTimezone=UTC'
export SMS_DB_USER='your_db_user'
export SMS_DB_PASSWORD='replace_with_a_strong_password'
export PORT='9090'
export JDBC_JAR='/path/to/mysql-connector-j.jar'
```

Or create a local `.env` file from the example:

```bash
cp .env.example .env
```

Then edit `.env` with your real values. The deploy script will load it automatically.

### 3. Find your MySQL JDBC jar

You need the path to the MySQL Connector/J jar.

Examples:

- Linux:
  - `/usr/share/java/mysql-connector-j-9.6.0.jar`
- macOS or custom install:
  - wherever you placed `mysql-connector-j-*.jar`

### 4. Compile

Replace `/path/to/mysql-connector-j.jar` with your actual jar path:

```bash
mkdir -p target/classes
javac -cp /path/to/mysql-connector-j.jar -d target/classes src/main/java/com/uniongroup/sms/*.java
```

### 5. Run

```bash
java -cp target/classes:/path/to/mysql-connector-j.jar com.uniongroup.sms.SmsApplication 9090
```

Then open:

```text
http://localhost:9090
```

### 6. Simple deploy script

You can also run the app with the included script after setting the required environment variables:

```bash
chmod +x scripts/deploy.sh
./scripts/deploy.sh
```

The script:
- checks required environment variables
- checks that the JDBC jar exists
- compiles the app
- starts the server

For local development, the frontend is served directly from `src/main/resources/static`, so no `out/` folder is required.

## Role access

### Admin

- dashboard
- user management
- employee management
- reports
- audit history

### Security Officer

- dashboard
- visitor registration
- access control
- incident reporting

## Main pages

- `login.html`
- `dashboard.html`
- `visitor-registration.html`
- `access-control.html`
- `incident-report.html`
- `users.html`
- `employees.html`
- `reports.html`
- `audit-history.html`

## Quick test flow

1. Log in as `admin`.
2. Change the seeded account passwords.
3. Open `User Management` and create or edit a user.
4. Open `Employee Management` and add an employee.
5. Open `Reports` and generate a report.
6. Open `Audit History` and confirm actions are recorded.
7. Log out.
8. Log in as `officer`.
9. Register a visitor.
10. Record visitor entry and exit.
11. Create and edit an incident.

## Helpful database checks

You can inspect the tables directly in MySQL:

```sql
USE sms_db;
SHOW TABLES;
SELECT * FROM users;
SELECT * FROM visitors;
SELECT * FROM employees;
SELECT * FROM access_logs;
SELECT * FROM incidents;
SELECT * FROM sessions;
SELECT * FROM audit_logs;
```

## Notes

- Sessions are stored in MySQL.
- Reports can be exported as CSV.
- Passwords are stored hashed, not in plain text.
- Database credentials are read from environment variables only.
- Java classes are compiled into `target/classes`.
- Frontend source files are served directly from `src/main/resources/static` during local runs.

## Additional guide

If you want a more detailed local run guide, see:

- `README-plain-java.md`
