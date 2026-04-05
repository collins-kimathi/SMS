package com.uniongroup.sms;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;
import java.util.*;

// Handles visitor CRUD operations
final class VisitorController {

    private VisitorController() {}

    // Fetch all visitors
    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SmsApplication.requireAuthenticated(exchange);

        String sql = "SELECT visitor_id, name, national_id, phone_number, purpose_of_visit FROM visitors ORDER BY visitor_id DESC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {

            while (rs.next()) {
                rows.add("{"
                    + "\"id\":" + rs.getInt("visitor_id") + ","
                    + "\"name\":\"" + SmsApplication.escapeJson(rs.getString("name")) + "\","
                    + "\"nationalId\":\"" + SmsApplication.escapeJson(rs.getString("national_id")) + "\","
                    + "\"phoneNumber\":\"" + SmsApplication.escapeJson(rs.getString("phone_number")) + "\","
                    + "\"purposeOfVisit\":\"" + SmsApplication.escapeJson(rs.getString("purpose_of_visit")) + "\""
                    + "}");
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    // Create new visitor
    static void handleCreate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);

        String name = SmsApplication.trim(form.get("name"));
        String nationalId = SmsApplication.trim(form.get("nationalId"));
        String phone = SmsApplication.trim(form.get("phoneNumber"));
        String purpose = SmsApplication.trim(form.get("purposeOfVisit"));

        if (name.isEmpty() || nationalId.isEmpty() || phone.isEmpty() || purpose.isEmpty()) {
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
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, nationalId);
                ps.setString(3, phone);
                ps.setString(4, purpose);
                ps.executeUpdate();
            }

            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "visitor", nationalId, "Registered visitor " + name);
        }

        SmsApplication.sendJson(exchange, 201, "{\"message\":\"Visitor registered successfully\"}");
    }

    // Update existing visitor
    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireSecurityOfficer(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);

        int visitorId = SmsApplication.parseRequiredInt(form.get("visitorId"), "Visitor is required");
        String name = SmsApplication.trim(form.get("name"));
        String nationalId = SmsApplication.trim(form.get("nationalId"));
        String phone = SmsApplication.trim(form.get("phoneNumber"));
        String purpose = SmsApplication.trim(form.get("purposeOfVisit"));

        if (name.isEmpty() || nationalId.isEmpty() || phone.isEmpty() || purpose.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"All visitor fields are required\"}");
            return;
        }

        try (Connection connection = SmsApplication.getConnection()) {

            if (SmsApplication.visitorExists(connection, nationalId, visitorId)) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Another visitor already uses that ID\"}");
                return;
            }

            String sql = "UPDATE visitors SET name = ?, national_id = ?, phone_number = ?, purpose_of_visit = ? WHERE visitor_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, name);
                ps.setString(2, nationalId);
                ps.setString(3, phone);
                ps.setString(4, purpose);
                ps.setInt(5, visitorId);

                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"Visitor not found\"}");
                    return;
                }
            }

            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "visitor", String.valueOf(visitorId), "Updated visitor " + name);
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Visitor updated successfully\"}");
    }

    // Delete visitor
    static void handleDelete(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireSecurityOfficer(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int visitorId = SmsApplication.parseRequiredInt(form.get("visitorId"), "Visitor is required");

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement("DELETE FROM visitors WHERE visitor_id = ?")) {

            ps.setInt(1, visitorId);

            if (ps.executeUpdate() == 0) {
                SmsApplication.sendJson(exchange, 404, "{\"message\":\"Visitor not found\"}");
                return;
            }

            SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "visitor", String.valueOf(visitorId), "Deleted visitor record");

        } catch (SQLException e) {
            if (e.getErrorCode() == 1451) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Visitor cannot be deleted because there are related access logs\"}");
                return;
            }
            throw e;
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Visitor deleted successfully\"}");
    }
}