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

// Handles visitor registration and maintenance endpoints.
final class VisitorController {

    private VisitorController() {
    }

    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SmsApplication.requireAuthenticated(exchange);
        String sql = "SELECT visitor_id, name, national_id, phone_number, purpose_of_visit FROM visitors ORDER BY visitor_id DESC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add("{"
                    + "\"id\":" + resultSet.getInt("visitor_id") + ","
                    + "\"name\":\"" + SmsApplication.escapeJson(resultSet.getString("name")) + "\","
                    + "\"nationalId\":\"" + SmsApplication.escapeJson(resultSet.getString("national_id")) + "\","
                    + "\"phoneNumber\":\"" + SmsApplication.escapeJson(resultSet.getString("phone_number")) + "\","
                    + "\"purposeOfVisit\":\"" + SmsApplication.escapeJson(resultSet.getString("purpose_of_visit")) + "\""
                    + "}");
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleCreate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        String name = SmsApplication.trim(form.get("name"));
        String nationalId = SmsApplication.trim(form.get("nationalId"));
        String phoneNumber = SmsApplication.trim(form.get("phoneNumber"));
        String purposeOfVisit = SmsApplication.trim(form.get("purposeOfVisit"));

        if (name.isEmpty() || nationalId.isEmpty() || phoneNumber.isEmpty() || purposeOfVisit.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"All visitor fields are required\"}");
            return;
        }

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireSecurityOfficer(session);

            if (SmsApplication.visitorExists(connection, nationalId)) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Visitor already registered\"}");
                return;
            }

            String sql = "INSERT INTO visitors (name, national_id, phone_number, purpose_of_visit) VALUES (?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                statement.setString(2, nationalId);
                statement.setString(3, phoneNumber);
                statement.setString(4, purposeOfVisit);
                statement.executeUpdate();
            }
            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "visitor", nationalId, "Registered visitor " + name);
        }

        SmsApplication.sendJson(exchange, 201, "{\"message\":\"Visitor registered successfully\"}");
    }

    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireSecurityOfficer(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int visitorId = SmsApplication.parseRequiredInt(form.get("visitorId"), "Visitor is required");
        String name = SmsApplication.trim(form.get("name"));
        String nationalId = SmsApplication.trim(form.get("nationalId"));
        String phoneNumber = SmsApplication.trim(form.get("phoneNumber"));
        String purposeOfVisit = SmsApplication.trim(form.get("purposeOfVisit"));

        if (name.isEmpty() || nationalId.isEmpty() || phoneNumber.isEmpty() || purposeOfVisit.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"All visitor fields are required\"}");
            return;
        }

        try (Connection connection = SmsApplication.getConnection()) {
            if (SmsApplication.visitorExists(connection, nationalId, visitorId)) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Another visitor already uses that ID\"}");
                return;
            }

            String sql = "UPDATE visitors SET name = ?, national_id = ?, phone_number = ?, purpose_of_visit = ? WHERE visitor_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                statement.setString(2, nationalId);
                statement.setString(3, phoneNumber);
                statement.setString(4, purposeOfVisit);
                statement.setInt(5, visitorId);
                int updated = statement.executeUpdate();
                if (updated == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"Visitor not found\"}");
                    return;
                }
            }
            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "visitor", String.valueOf(visitorId), "Updated visitor " + name);
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Visitor updated successfully\"}");
    }

    static void handleDelete(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireSecurityOfficer(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int visitorId = SmsApplication.parseRequiredInt(form.get("visitorId"), "Visitor is required");

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM visitors WHERE visitor_id = ?")) {
            statement.setInt(1, visitorId);
            int deleted = statement.executeUpdate();
            if (deleted == 0) {
                SmsApplication.sendJson(exchange, 404, "{\"message\":\"Visitor not found\"}");
                return;
            }
            SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "visitor", String.valueOf(visitorId), "Deleted visitor record");
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1451) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Visitor cannot be deleted because there are related access logs\"}");
                return;
            }
            throw exception;
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Visitor deleted successfully\"}");
    }
}
