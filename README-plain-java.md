## Security Management System

This project is a plain Java + MySQL web app. It does not use Maven, Gradle, Spring Boot, or a separate frontend framework.

The app runs with:
- a Java HTTP server in [SmsApplication.java](/home/itachi/Documents/projects/SMS/src/main/java/com/uniongroup/sms/SmsApplication.java)
- static HTML/CSS/JS in [src/main/resources/static](/home/itachi/Documents/projects/SMS/src/main/resources/static)
- MySQL tables and seed data in [schema.sql](/home/itachi/Documents/projects/SMS/database/schema.sql)

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

- `admin / 1234`
- `officer / 1234`

Passwords are stored hashed in the database.

### Database setup

Make sure MySQL is running, then import the schema:

```bash
cd /home/itachi/Documents/projects/SMS
mysql -u root -p < database/schema.sql
```

If you kept the earlier local setup, the default MySQL connection used by the app is:

- database: `sms_db`
- user: `root`
- password: `1234`

### JDBC driver

This project uses the system-installed MySQL JDBC jar:

```bash
/usr/share/java/mysql-connector-j-9.6.0.jar
```

### Compile

From the project root:

```bash
cd /home/itachi/Documents/projects/SMS
rm -rf out
mkdir -p out
javac -cp /usr/share/java/mysql-connector-j-9.6.0.jar -d out src/main/java/com/uniongroup/sms/SmsApplication.java
cp -r src/main/resources/* out/
```

### Run

Run on port `9090`:

```bash
java -cp out:/usr/share/java/mysql-connector-j-9.6.0.jar com.uniongroup.sms.SmsApplication 9090
```

Then open:

```text
http://localhost:9090
```

### Optional environment variables

You can override the database connection with environment variables:

```bash
export SMS_DB_URL='jdbc:mysql://localhost:3306/sms_db?serverTimezone=UTC'
export SMS_DB_USER='root'
export SMS_DB_PASSWORD='1234'
```

Then run the same `java` command again.

### Main pages

- [login.html](/home/itachi/Documents/projects/SMS/src/main/resources/static/login.html)
- [dashboard.html](/home/itachi/Documents/projects/SMS/src/main/resources/static/dashboard.html)
- [visitor-registration.html](/home/itachi/Documents/projects/SMS/src/main/resources/static/visitor-registration.html)
- [access-control.html](/home/itachi/Documents/projects/SMS/src/main/resources/static/access-control.html)
- [incident-report.html](/home/itachi/Documents/projects/SMS/src/main/resources/static/incident-report.html)
- [users.html](/home/itachi/Documents/projects/SMS/src/main/resources/static/users.html)
- [employees.html](/home/itachi/Documents/projects/SMS/src/main/resources/static/employees.html)
- [reports.html](/home/itachi/Documents/projects/SMS/src/main/resources/static/reports.html)
- [audit-history.html](/home/itachi/Documents/projects/SMS/src/main/resources/static/audit-history.html)

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
