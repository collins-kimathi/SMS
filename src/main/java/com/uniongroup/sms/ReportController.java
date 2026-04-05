package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

// Admin reporting endpoints (JSON + CSV export)
final class ReportController {

    private ReportController() {}

    // Access logs within date range
    static void handleAccessReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
            return;
        }

        String sql = ""
            + "SELECT al.log_id, al.visit_date, al.entry_time, al.exit_time, "
            + "v.name AS visitor_name, e.name AS employee_name, e.department, u.username "
            + "FROM access_logs al "
            + "JOIN visitors v ON al.visitor_id = v.visitor_id "
            + "JOIN employees e ON al.employee_id = e.employee_id "
            + "JOIN users u ON al.user_id = u.user_id "
            + "WHERE al.visit_date BETWEEN ? AND ? "
            + "ORDER BY al.visit_date DESC, al.log_id DESC";

        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setDate(1, java.sql.Date.valueOf(startDate));
            ps.setDate(2, java.sql.Date.valueOf(endDate));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String exitTime = rs.getTime("exit_time") == null ? "" : rs.getTime("exit_time").toString();

                    rows.add("{"
                        + "\"id\":" + rs.getInt("log_id") + ","
                        + "\"date\":\"" + rs.getDate("visit_date") + "\","
                        + "\"visitorName\":\"" + SmsApplication.escapeJson(rs.getString("visitor_name")) + "\","
                        + "\"host\":\"" + SmsApplication.escapeJson(rs.getString("employee_name") + " (" + rs.getString("department") + ")") + "\","
                        + "\"entryTime\":\"" + rs.getTime("entry_time") + "\","
                        + "\"exitTime\":\"" + SmsApplication.escapeJson(exitTime) + "\","
                        + "\"recordedBy\":\"" + SmsApplication.escapeJson(rs.getString("username")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    // Incident reports within date range
    static void handleIncidentReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
            return;
        }

        String sql = ""
            + "SELECT i.incident_id, i.reported_at, i.title, i.incident_type, i.location, i.severity, i.status, u.username "
            + "FROM incidents i "
            + "JOIN users u ON i.user_id = u.user_id "
            + "WHERE DATE(i.reported_at) BETWEEN ? AND ? "
            + "ORDER BY i.reported_at DESC, i.incident_id DESC";

        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setDate(1, java.sql.Date.valueOf(startDate));
            ps.setDate(2, java.sql.Date.valueOf(endDate));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add("{"
                        + "\"id\":" + rs.getInt("incident_id") + ","
                        + "\"date\":\"" + rs.getTimestamp("reported_at").toLocalDateTime().toLocalDate() + "\","
                        + "\"title\":\"" + SmsApplication.escapeJson(rs.getString("title")) + "\","
                        + "\"incidentType\":\"" + SmsApplication.escapeJson(rs.getString("incident_type")) + "\","
                        + "\"location\":\"" + SmsApplication.escapeJson(rs.getString("location")) + "\","
                        + "\"severity\":\"" + SmsApplication.escapeJson(rs.getString("severity")) + "\","
                        + "\"status\":\"" + SmsApplication.escapeJson(rs.getString("status")) + "\","
                        + "\"reportedBy\":\"" + SmsApplication.escapeJson(rs.getString("username")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    // Visitor registrations within date range
    static void handleVisitorReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
            return;
        }

        String sql = ""
            + "SELECT visitor_id, name, national_id, phone_number, purpose_of_visit, created_at "
            + "FROM visitors "
            + "WHERE DATE(created_at) BETWEEN ? AND ? "
            + "ORDER BY created_at DESC, visitor_id DESC";

        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setDate(1, java.sql.Date.valueOf(startDate));
            ps.setDate(2, java.sql.Date.valueOf(endDate));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add("{"
                        + "\"id\":" + rs.getInt("visitor_id") + ","
                        + "\"date\":\"" + rs.getTimestamp("created_at").toLocalDateTime().toLocalDate() + "\","
                        + "\"name\":\"" + SmsApplication.escapeJson(rs.getString("name")) + "\","
                        + "\"nationalId\":\"" + SmsApplication.escapeJson(rs.getString("national_id")) + "\","
                        + "\"phoneNumber\":\"" + SmsApplication.escapeJson(rs.getString("phone_number")) + "\","
                        + "\"purposeOfVisit\":\"" + SmsApplication.escapeJson(rs.getString("purpose_of_visit")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    // Audit logs within date range
    static void handleAuditReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
            return;
        }

        String sql = ""
            + "SELECT audit_id, action_type, entity_type, entity_id, details, created_at, username "
            + "FROM audit_logs "
            + "WHERE DATE(created_at) BETWEEN ? AND ? "
            + "ORDER BY created_at DESC, audit_id DESC";

        List<String> rows = new ArrayList<>();

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {

            ps.setDate(1, java.sql.Date.valueOf(startDate));
            ps.setDate(2, java.sql.Date.valueOf(endDate));

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add("{"
                        + "\"id\":" + rs.getInt("audit_id") + ","
                        + "\"date\":\"" + rs.getTimestamp("created_at").toLocalDateTime().toLocalDate() + "\","
                        + "\"actionType\":\"" + SmsApplication.escapeJson(rs.getString("action_type")) + "\","
                        + "\"entityType\":\"" + SmsApplication.escapeJson(rs.getString("entity_type")) + "\","
                        + "\"entityId\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("entity_id"), "")) + "\","
                        + "\"details\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("details"), "")) + "\","
                        + "\"username\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(rs.getString("username"), "System")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    // Export report as CSV (download response)
    static void handleExport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String type = SmsApplication.trim(query.get("type"));
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (type.isEmpty() || startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendText(exchange, 400, "Missing export parameters");
            return;
        }

        // Delegate CSV generation to builder
        String csv = CsvReportBuilder.build(
            type,
            startDate,
            endDate,
            SmsApplication.dbUrl(),
            SmsApplication.dbUser(),
            SmsApplication.dbPassword()
        );

        byte[] payload = csv.getBytes(StandardCharsets.UTF_8);

        // Configure download response headers
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition",
            "attachment; filename=\"" + type + "-report-" + startDate + "-to-" + endDate + ".csv\"");

        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().close();
    }
}
