## Plain Java + MySQL setup

This project does not use Maven or Spring Boot. It runs as a plain Java HTTP server and connects to MySQL through JDBC.

### 1. Prepare the database

Run:

```bash
mysql -u root -p < database/schema.sql
```

### 2. Add the MySQL JDBC driver

Download `mysql-connector-j-8.x.x.jar` and place it somewhere local, for example:

```bash
/home/itachi/libs/mysql-connector-j.jar
```

### 3. Compile

From the project root:

```bash
mkdir -p out
javac -cp /home/itachi/libs/mysql-connector-j.jar -d out src/main/java/com/uniongroup/sms/SmsApplication.java
cp -r src/main/resources/* out/
```

### 4. Run

```bash
java -cp out:/home/itachi/libs/mysql-connector-j.jar com.uniongroup.sms.SmsApplication
```

### 5. Optional environment variables

If you want to override the database settings:

```bash
export SMS_DB_URL='jdbc:mysql://localhost:3306/sms_db?serverTimezone=UTC'
export SMS_DB_USER='root'
export SMS_DB_PASSWORD='1234'
```

Then run the same `java` command again.
