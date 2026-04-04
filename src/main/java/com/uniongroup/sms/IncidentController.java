package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Handles incident listing, creation, and updates.
final class IncidentController {

    private IncidentController() {
    }

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
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add("{"
                    + "\"id\":" + resultSet.getInt("incident_id") + ","
                    + "\"title\":\"" + SmsApplication.escapeJson(resultSet.getString("title")) + "\","
                    + "\"incidentType\":\"" + SmsApplication.escapeJson(resultSet.getString("incident_type")) + "\","
                    + "\"location\":\"" + SmsApplication.escapeJson(resultSet.getString("location")) + "\","
                    + "\"status\":\"" + SmsApplication.escapeJson(resultSet.getString("status")) + "\","
                    + "\"actionTaken\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(resultSet.getString("action_taken"), "")) + "\","
                    + "\"description\":\"" + SmsApplication.escapeJson(resultSet.getString("description")) + "\","
                    + "\"severity\":\"" + SmsApplication.escapeJson(resultSet.getString("severity")) + "\","
                    + "\"date\":\"" + resultSet.getTimestamp("reported_at").toLocalDateTime().toLocalDate() + "\","
                    + "\"reportedBy\":\"" + SmsApplication.escapeJson(resultSet.getString("username")) + "\","
                    + "\"userId\":" + resultSet.getInt("user_id")
                    + "}");
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

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

        if (title.isEmpty() || incidentType.isEmpty() || location.isEmpty() || severity.isEmpty() || status.isEmpty() || description.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"All incident fields are required\"}");
            return;
        }

        String sql = "INSERT INTO incidents (title, incident_type, location, status, action_taken, description, severity, user_id, reported_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireSecurityOfficer(session);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, title);
                statement.setString(2, incidentType);
                statement.setString(3, location);
                statement.setString(4, status);
                statement.setString(5, actionTaken);
                statement.setString(6, description);
                statement.setString(7, severity);
                statement.setInt(8, session.userId);
                statement.setTimestamp(9, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
                statement.executeUpdate();
            }
            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "incident", title, "Created incident " + title);
        }

        SmsApplication.sendJson(exchange, 201, "{\"message\":\"Incident report saved successfully\"}");
    }

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

        if (title.isEmpty() || incidentType.isEmpty() || location.isEmpty() || severity.isEmpty() || status.isEmpty() || description.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"All incident fields are required\"}");
            return;
        }

        String sql = "UPDATE incidents SET title = ?, incident_type = ?, location = ?, status = ?, action_taken = ?, description = ?, severity = ? WHERE incident_id = ?";

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireIncidentManager(session);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, title);
                statement.setString(2, incidentType);
                statement.setString(3, location);
                statement.setString(4, status);
                statement.setString(5, actionTaken);
                statement.setString(6, description);
                statement.setString(7, severity);
                statement.setInt(8, incidentId);

                int updated = statement.executeUpdate();
                if (updated == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"Incident not found\"}");
                    return;
                }
            }
            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "incident", String.valueOf(incidentId), "Updated incident " + title);
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Incident updated successfully\"}");
    }
}
