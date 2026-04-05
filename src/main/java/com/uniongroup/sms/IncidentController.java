package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.*;
import java.util.*;

// Incident management (listing, creation, updates)
final class IncidentController {

    private IncidentController() {}

    // Return all incidents with reporter info
    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SmsApplication.requireAuthenticated(exchange);

        String sql = ""
            + "SELECT i.incident_id, i.title, i.incident_type, i.location, i.status, i.action_taken, "
            + "i.description, i.severity, i.reported_at, u.user_id, u.username "
            + "FROM incidents i "
            + "JOIN users u ON i.user_id = u.user_id "
            + "ORDER BY i.incident_id DESC";

        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                rows.add("{"
                    + "\"id\":" + rs.getInt("incident_id") + ","
                    + "\"title\":\"" + SmsApplication.escapeJson(rs.getString("title")) + "\","
                    + "\"incidentType\":\"" + SmsApplication.escapeJson(rs.getString("incident_type")) + "\","
                    + "\"location\":\"" + SmsApplication.escapeJson(rs.getString("location")) + "\","
                    + "\"status\":\"" + SmsApplication.escapeJson(rs.getString("status")) + "\","
                    + "\"actionTaken\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("action_taken"), "")) + "\","
                    + "\"description\":\"" + SmsApplication.escapeJson(rs.getString("description")) + "\","
                    + "\"severity\":\"" + SmsApplication.escapeJson(rs.getString("severity")) + "\","
                    + "\"date\":\"" + rs.getTimestamp("reported_at").toLocalDateTime().toLocalDate() + "\","
                    + "\"reportedBy\":\"" + SmsApplication.escapeJson(rs.getString("username")) + "\","
                    + "\"userId\":" + rs.getInt("user_id")
                    + "}");
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    // Create new incident (restricted to security officers)
    static void handleCreate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);

        String title = SmsApplication.trim(form.get("title"));
        String incidentType = SmsApplication.trim(form.get("incidentType"));
        String location = SmsApplication.trim(form.get("location"));
        String severity = SmsApplication.trim(form.get("severity"));
        String status = SmsApplication.trim(form.get("status"));
        String actionTaken = SmsApplication.trim(form.get("actionTaken"));
        String description = SmsApplication.trim(form.get("description"));

        if (title.isEmpty() || incidentType.isEmpty() || location.isEmpty()
            || severity.isEmpty() || status.isEmpty() || description.isEmpty()) {

            SmsApplication.sendJson(exchange, 400, "{\"message\":\"All incident fields are required\"}");
            return;
        }

        String sql = "INSERT INTO incidents (title, incident_type, location, status, action_taken, description, severity, user_id, reported_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireSecurityOfficer(session);

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, title);
                ps.setString(2, incidentType);
                ps.setString(3, location);
                ps.setString(4, status);
                ps.setString(5, actionTaken);
                ps.setString(6, description);
                ps.setString(7, severity);
                ps.setInt(8, session.userId);

                // Normalize timestamp (drop nanos for consistency)
                ps.setTimestamp(9, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
                ps.executeUpdate();
            }

            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "incident", title, "Created incident " + title);
        }

        SmsApplication.sendJson(exchange, 201, "{\"message\":\"Incident report saved successfully\"}");
    }

    // Update incident (restricted to incident managers)
    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);

        int incidentId = SmsApplication.parseRequiredInt(form.get("incidentId"), "Incident is required");

        String title = SmsApplication.trim(form.get("title"));
        String incidentType = SmsApplication.trim(form.get("incidentType"));
        String location = SmsApplication.trim(form.get("location"));
        String severity = SmsApplication.trim(form.get("severity"));
        String status = SmsApplication.trim(form.get("status"));
        String actionTaken = SmsApplication.trim(form.get("actionTaken"));
        String description = SmsApplication.trim(form.get("description"));

        if (title.isEmpty() || incidentType.isEmpty() || location.isEmpty()
            || severity.isEmpty() || status.isEmpty() || description.isEmpty()) {

            SmsApplication.sendJson(exchange, 400, "{\"message\":\"All incident fields are required\"}");
            return;
        }

        String sql = "UPDATE incidents SET title = ?, incident_type = ?, location = ?, status = ?, action_taken = ?, description = ?, severity = ? WHERE incident_id = ?";

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireIncidentManager(session);

            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, title);
                ps.setString(2, incidentType);
                ps.setString(3, location);
                ps.setString(4, status);
                ps.setString(5, actionTaken);
                ps.setString(6, description);
                ps.setString(7, severity);
                ps.setInt(8, incidentId);

                if (ps.executeUpdate() == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"Incident not found\"}");
                    return;
                }
            }

            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "incident", String.valueOf(incidentId), "Updated incident " + title);
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Incident updated successfully\"}");
    }
}