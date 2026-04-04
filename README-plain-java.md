## Security Management System

This project is a plain Java + MySQL web app. It does not use Maven, Gradle, Spring Boot, or a separate frontend framework.

The app runs with:
- a Java HTTP server in `src/main/java/com/uniongroup/sms/SmsApplication.java`
- static HTML/CSS/JS in `src/main/resources/static`
- MySQL tables and seed data in `database/schema.sql`

### Current features

- login with server-side sessions stored in MySQL
- role-based access for `Admin` and `Security Officer`
- visitor registration with duplicate ID protection
- employee management
- access control with entry, exit, edit, and delete
- incident reporting with edit and filter support
- user management
- reports for:
  - access logs
  - incident reports
  - visitor registrations
  - audit trail
- CSV export for reports
- audit logging for major create, update, delete, login, and logout actions
- password hashing with PBKDF2

### Default accounts

The schema seeds two accounts:

- `admin / Admin@Union2026!`
- `officer / Officer@Union2026!`

Passwords are stored hashed in the database.
Change these passwords after first login if you deploy the system anywhere public.

### Database setup

Make sure MySQL is running, then import the schema:

```bash
mysql -u root -p < database/schema.sql
```

The application now requires environment variables for database access and will not start without them.

### JDBC driver

This project can use a system-installed MySQL JDBC jar such as:

```bash
/usr/share/java/mysql-connector-j-9.6.0.jar
```

### Compile

Set the required environment variables first:

```bash
export SMS_DB_URL='jdbc:mysql://localhost:3306/sms_db?serverTimezone=UTC'
export SMS_DB_USER='root'
export SMS_DB_PASSWORD='replace_with_a_strong_password'
export PORT='9090'
export JDBC_JAR='/usr/share/java/mysql-connector-j-9.6.0.jar'
```

If you do not want to export them every time, create a local `.env` file:

```bash
cp .env.example .env
```

Then edit `.env` with your actual values. The deploy script will load it automatically.

Then compile from the project root:

```bash
rm -rf target/classes
mkdir -p target/classes
javac -cp "$JDBC_JAR" -d target/classes src/main/java/com/uniongroup/sms/*.java
```

### Run

Run on the configured port:

```bash
java -cp target/classes:"$JDBC_JAR" com.uniongroup.sms.SmsApplication "${PORT:-9090}"
```

Then open:

```text
http://localhost:9090
```

### Deploy script

You can also use the included deploy script:

```bash
chmod +x scripts/deploy.sh
./scripts/deploy.sh
```

The script validates:

- `SMS_DB_URL`
- `SMS_DB_USER`
- `SMS_DB_PASSWORD`
- `JDBC_JAR`

The script compiles Java into `target/classes`. Static frontend files are served directly from `src/main/resources/static` during local development.

### Main pages

- `login.html`
- `dashboard.html`
- `visitor-registration.html`
- `access-control.html`
- `incident-report.html`
- `users.html`
- `employees.html`
- `reports.html`
- `audit-history.html`

### Role access

`Admin` can access:
- dashboard
- user management
- employee management
- reports
- audit history

`Security Officer` can access:
- dashboard
- visitor registration
- access control
- incident reporting

### Quick test flow

1. Log in as `admin`.
2. Open `User Management` and `Employee Management`.
3. Open `Reports` and generate a report.
4. Open `Audit History` and confirm actions appear.
5. Log out.
6. Log in as `officer`.
7. Register a visitor.
8. Record visitor entry and exit.
9. Create and edit an incident.

### Helpful MySQL checks

After testing, you can inspect the database:

```bash
mysql -u root -p
```

Then:

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
