-- Core database used by the Java server.
CREATE DATABASE IF NOT EXISTS sms_db;
USE sms_db;

-- Application users who can sign in to the system.
CREATE TABLE IF NOT EXISTS users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL
);

-- Visitors registered by security officers before access is recorded.
CREATE TABLE IF NOT EXISTS visitors (
    visitor_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    national_id VARCHAR(50) NOT NULL UNIQUE,
    phone_number VARCHAR(20) NOT NULL,
    purpose_of_visit VARCHAR(255) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Employees who can be selected as the host for a visitor.
CREATE TABLE IF NOT EXISTS employees (
    employee_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    department VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20)
);

-- Entry and exit records linking visitors, host employees, and the officer who recorded the visit.
CREATE TABLE IF NOT EXISTS access_logs (
    log_id INT AUTO_INCREMENT PRIMARY KEY,
    visitor_id INT NOT NULL,
    employee_id INT NOT NULL,
    user_id INT NOT NULL,
    visit_date DATE NOT NULL,
    entry_time TIME NOT NULL,
    exit_time TIME NULL,
    FOREIGN KEY (visitor_id) REFERENCES visitors(visitor_id),
    FOREIGN KEY (employee_id) REFERENCES employees(employee_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Incident records captured by security staff.
CREATE TABLE IF NOT EXISTS incidents (
    incident_id INT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(150) NOT NULL,
    incident_type VARCHAR(50) NOT NULL,
    location VARCHAR(150) NOT NULL,
    status VARCHAR(30) NOT NULL,
    action_taken TEXT NULL,
    description TEXT NOT NULL,
    severity VARCHAR(20) NOT NULL,
    user_id INT NOT NULL,
    reported_at DATETIME NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- Server-side login sessions stored in MySQL rather than in browser-only state.
CREATE TABLE IF NOT EXISTS sessions (
    session_id VARCHAR(128) PRIMARY KEY,
    user_id INT NOT NULL,
    username VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL,
    expires_at DATETIME NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
);

-- Audit log used by the admin audit screen and reports.
CREATE TABLE IF NOT EXISTS audit_logs (
    audit_id INT AUTO_INCREMENT PRIMARY KEY,
    user_id INT NULL,
    username VARCHAR(50) NOT NULL,
    action_type VARCHAR(30) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(100) NULL,
    details TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL
);

-- Starter accounts for the first login after setup.
INSERT INTO users (username, password, role)
VALUES
    ('admin', 'pbkdf2$120000$kQk15HYP0JxviSD0GV8I4Q$8qNL7LC5mRPUdqsZQMBuY_iU5GDu_klB7CbuNI-RubI', 'Admin'),
    ('officer', 'pbkdf2$120000$0o9GlaVREIZpnu78VPRlJA$TOkwY37-xQV88QbEdlyoj4foPzmnDCFRfKgmlgd16JI', 'Security')
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    role = VALUES(role);

-- Sample employees so access control has host options immediately after setup.
INSERT INTO employees (name, department, phone_number)
SELECT 'Mary Wanjiku', 'Operations', '+254700111222'
WHERE NOT EXISTS (SELECT 1 FROM employees WHERE name = 'Mary Wanjiku' AND department = 'Operations');

INSERT INTO employees (name, department, phone_number)
SELECT 'Brian Ouma', 'IT', '+254700333444'
WHERE NOT EXISTS (SELECT 1 FROM employees WHERE name = 'Brian Ouma' AND department = 'IT');

INSERT INTO employees (name, department, phone_number)
SELECT 'Kevin Langat', 'Finance', '+254700555666'
WHERE NOT EXISTS (SELECT 1 FROM employees WHERE name = 'Kevin Langat' AND department = 'Finance');

INSERT INTO employees (name, department, phone_number)
SELECT 'Joan Naliaka', 'Human Resources', '+254700777888'
WHERE NOT EXISTS (SELECT 1 FROM employees WHERE name = 'Joan Naliaka' AND department = 'Human Resources');
