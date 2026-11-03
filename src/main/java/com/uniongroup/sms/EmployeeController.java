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

        String sql = "SELECT e.employee_id, e.name, e.department, e.position, e.phone_number, e.email, e.subsidiary_id, s.subsidiary_name " +
            "FROM employees e LEFT JOIN subsidiaries s ON e.subsidiary_id = s.subsidiary_id ORDER BY e.name ASC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                rows.add("{"
                    + "\"id\":" + rs.getInt("employee_id") + ","
                    + "\"name\":\"" + SmsApplication.escapeJson(rs.getString("name")) + "\","
                    + "\"department\":\"" + SmsApplication.escapeJson(rs.getString("department")) + "\","
                    + "\"position\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("position"), "")) + "\","
                    + "\"phoneNumber\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("phone_number"), "")) + "\","
                    + "\"email\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("email"), "")) + "\","
                    + "\"subsidiaryId\":" + rs.getInt("subsidiary_id") + ","
                    + "\"subsidiaryName\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("subsidiary_name"), "")) + "\""
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
        String position = SmsApplication.trim(form.get("position"));
        String email = SmsApplication.trim(form.get("email"));
        String subsidiaryId = SmsApplication.trim(form.get("subsidiaryId"));

        if (name.isEmpty() || department.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Employee name and department are required\"}");
            return;
        }

        String sql = "INSERT INTO employees (name, department, phone_number, position, email, subsidiary_id) VALUES (?, ?, ?, ?, ?, ?)";

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setString(1, name);
            ps.setString(2, department);
            ps.setString(3, phone);
            ps.setString(4, position);
            ps.setString(5, email);
            if (subsidiaryId.isEmpty()) ps.setNull(6, java.sql.Types.INTEGER); else ps.setInt(6, Integer.parseInt(subsidiaryId));
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
        String position = SmsApplication.trim(form.get("position"));
        String email = SmsApplication.trim(form.get("email"));
        String subsidiaryId = SmsApplication.trim(form.get("subsidiaryId"));

        if (name.isEmpty() || department.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Employee name and department are required\"}");
            return;
        }

        String sql = "UPDATE employees SET name = ?, department = ?, phone_number = ?, position = ?, email = ?, subsidiary_id = ? WHERE employee_id = ?";

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setString(1, name);
            ps.setString(2, department);
            ps.setString(3, phone);
            ps.setString(4, position);
            ps.setString(5, email);
            if (subsidiaryId.isEmpty()) ps.setNull(6, java.sql.Types.INTEGER); else ps.setInt(6, Integer.parseInt(subsidiaryId));
            ps.setInt(7, employeeId);

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