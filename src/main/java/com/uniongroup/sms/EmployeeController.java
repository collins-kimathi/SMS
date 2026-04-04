package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Handles employee directory endpoints.
final class EmployeeController {

    private EmployeeController() {
    }

    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SmsApplication.requireAuthenticated(exchange);
        String sql = "SELECT employee_id, name, department, phone_number FROM employees ORDER BY name ASC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add("{"
                    + "\"id\":" + resultSet.getInt("employee_id") + ","
                    + "\"name\":\"" + SmsApplication.escapeJson(resultSet.getString("name")) + "\","
                    + "\"department\":\"" + SmsApplication.escapeJson(resultSet.getString("department")) + "\","
                    + "\"phoneNumber\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(resultSet.getString("phone_number"), "")) + "\""
                    + "}");
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleCreate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        String name = SmsApplication.trim(form.get("name"));
        String department = SmsApplication.trim(form.get("department"));
        String phoneNumber = SmsApplication.trim(form.get("phoneNumber"));

        if (name.isEmpty() || department.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Employee name and department are required\"}");
            return;
        }

        String sql = "INSERT INTO employees (name, department, phone_number) VALUES (?, ?, ?)";
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, department);
            statement.setString(3, phoneNumber);
            statement.executeUpdate();
            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "employee", name, "Created employee " + name);
        }

        SmsApplication.sendJson(exchange, 201, "{\"message\":\"Employee created successfully\"}");
    }

    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int employeeId = SmsApplication.parseRequiredInt(form.get("employeeId"), "Employee is required");
        String name = SmsApplication.trim(form.get("name"));
        String department = SmsApplication.trim(form.get("department"));
        String phoneNumber = SmsApplication.trim(form.get("phoneNumber"));

        if (name.isEmpty() || department.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Employee name and department are required\"}");
            return;
        }

        String sql = "UPDATE employees SET name = ?, department = ?, phone_number = ? WHERE employee_id = ?";
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, department);
            statement.setString(3, phoneNumber);
            statement.setInt(4, employeeId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                SmsApplication.sendJson(exchange, 404, "{\"message\":\"Employee not found\"}");
                return;
            }
            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "employee", String.valueOf(employeeId), "Updated employee " + name);
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Employee updated successfully\"}");
    }

    static void handleDelete(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int employeeId = SmsApplication.parseRequiredInt(form.get("employeeId"), "Employee is required");

        String sql = "DELETE FROM employees WHERE employee_id = ?";
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, employeeId);
            int deleted = statement.executeUpdate();
            if (deleted == 0) {
                SmsApplication.sendJson(exchange, 404, "{\"message\":\"Employee not found\"}");
                return;
            }
            SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "employee", String.valueOf(employeeId), "Deleted employee record");
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1451) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Employee cannot be deleted because there are related access logs\"}");
                return;
            }
            throw exception;
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Employee deleted successfully\"}");
    }
}
