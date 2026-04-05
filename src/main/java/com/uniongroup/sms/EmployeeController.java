package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;
import java.util.*;

// Employee directory management (CRUD)
final class EmployeeController {

    private EmployeeController() {}

    // List all employees
    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SmsApplication.requireAuthenticated(exchange);

        String sql = "SELECT employee_id, name, department, phone_number FROM employees ORDER BY name ASC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                rows.add("{"
                    + "\"id\":" + rs.getInt("employee_id") + ","
                    + "\"name\":\"" + SmsApplication.escapeJson(rs.getString("name")) + "\","
                    + "\"department\":\"" + SmsApplication.escapeJson(rs.getString("department")) + "\","
                    + "\"phoneNumber\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("phone_number"), "")) + "\""
                    + "}");
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    // Create employee (admin only)
    static void handleCreate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);

        String name = SmsApplication.trim(form.get("name"));
        String department = SmsApplication.trim(form.get("department"));
        String phone = SmsApplication.trim(form.get("phoneNumber"));

        if (name.isEmpty() || department.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Employee name and department are required\"}");
            return;
        }

        String sql = "INSERT INTO employees (name, department, phone_number) VALUES (?, ?, ?)";

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setString(1, name);
            ps.setString(2, department);
            ps.setString(3, phone);
            ps.executeUpdate();

            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "employee", name, "Created employee " + name);
        }

        SmsApplication.sendJson(exchange, 201, "{\"message\":\"Employee created successfully\"}");
    }

    // Update employee (admin only)
    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);

        int employeeId = SmsApplication.parseRequiredInt(form.get("employeeId"), "Employee is required");
        String name = SmsApplication.trim(form.get("name"));
        String department = SmsApplication.trim(form.get("department"));
        String phone = SmsApplication.trim(form.get("phoneNumber"));

        if (name.isEmpty() || department.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Employee name and department are required\"}");
            return;
        }

        String sql = "UPDATE employees SET name = ?, department = ?, phone_number = ? WHERE employee_id = ?";

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setString(1, name);
            ps.setString(2, department);
            ps.setString(3, phone);
            ps.setInt(4, employeeId);

            if (ps.executeUpdate() == 0) {
                SmsApplication.sendJson(exchange, 404, "{\"message\":\"Employee not found\"}");
                return;
            }

            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "employee", String.valueOf(employeeId), "Updated employee " + name);
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Employee updated successfully\"}");
    }

    // Delete employee (blocked if referenced in logs)
    static void handleDelete(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int employeeId = SmsApplication.parseRequiredInt(form.get("employeeId"), "Employee is required");

        String sql = "DELETE FROM employees WHERE employee_id = ?";

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setInt(1, employeeId);

            if (ps.executeUpdate() == 0) {
                SmsApplication.sendJson(exchange, 404, "{\"message\":\"Employee not found\"}");
                return;
            }

            SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "employee", String.valueOf(employeeId), "Deleted employee record");

        } catch (SQLException e) {
            // Foreign key constraint (linked records exist)
            if (e.getErrorCode() == 1451) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Employee cannot be deleted because there are related access logs\"}");
                return;
            }
            throw e;
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Employee deleted successfully\"}");
    }
}