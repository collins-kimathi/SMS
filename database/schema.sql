CREATE DATABASE IF NOT EXISTS sms_db;
USE sms_db;

CREATE TABLE IF NOT EXISTS users (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL
);

CREATE TABLE IF NOT EXISTS visitors (
    visitor_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    national_id VARCHAR(50) NOT NULL UNIQUE,
    phone_number VARCHAR(20) NOT NULL,
    purpose_of_visit VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS employees (
    employee_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    department VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20)
);

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

INSERT INTO users (username, password, role)
VALUES
    ('admin', '1234', 'Admin'),
    ('officer', '1234', 'Security')
ON DUPLICATE KEY UPDATE
    password = VALUES(password),
    role = VALUES(role);

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
