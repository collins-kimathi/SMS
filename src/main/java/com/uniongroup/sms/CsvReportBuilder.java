package com.uniongroup.sms;

import java.sql.*;
import java.util.*;

// Builds CSV report data (kept separate from HTTP layer)
final class CsvReportBuilder {

    private CsvReportBuilder() {}

    // Entry point: builds CSV string based on report type
    static String build(String type, String startDate, String endDate,
                        String dbUrl, String dbUser, String dbPassword) throws SQLException {

        List<String[]> rows = new ArrayList<>();

        if ("access".equals(type)) {
            // Header row
            rows.add(new String[] {"Date", "Visitor", "Host", "Entry", "Exit", "Recorded By"});

            String sql = ""
                + "SELECT al.visit_date, v.name AS visitor_name, e.name AS employee_name, e.department, u.username, al.entry_time, al.exit_time "
                + "FROM access_logs al "
                + "JOIN visitors v ON al.visitor_id = v.visitor_id "
                + "JOIN employees e ON al.employee_id = e.employee_id "
                + "JOIN users u ON al.user_id = u.user_id "
                + "WHERE al.visit_date BETWEEN ? AND ? "
                + "ORDER BY al.visit_date DESC, al.log_id DESC";

            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                 PreparedStatement ps = connection.prepareStatement(sql)) {

                ps.setDate(1, java.sql.Date.valueOf(startDate));
                ps.setDate(2, java.sql.Date.valueOf(endDate));

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[] {
                            String.valueOf(rs.getDate("visit_date")),
                            rs.getString("visitor_name"),
                            // Combine employee + department for readability
                            rs.getString("employee_name") + " (" + rs.getString("department") + ")",
                            String.valueOf(rs.getTime("entry_time")),
                            rs.getTime("exit_time") == null ? "" : String.valueOf(rs.getTime("exit_time")),
                            rs.getString("username")
                        });
                    }
                }
            }

        } else if ("incidents".equals(type)) {

            rows.add(new String[] {"Date", "Title", "Type", "Location", "Severity", "Status", "Reported By"});

            String sql = ""
                + "SELECT DATE(reported_at) AS report_date, title, incident_type, location, severity, status, username "
                + "FROM incidents i JOIN users u ON i.user_id = u.user_id "
                + "WHERE DATE(reported_at) BETWEEN ? AND ? "
                + "ORDER BY reported_at DESC, incident_id DESC";

            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                 PreparedStatement ps = connection.prepareStatement(sql)) {

                ps.setDate(1, java.sql.Date.valueOf(startDate));
                ps.setDate(2, java.sql.Date.valueOf(endDate));

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[] {
                            String.valueOf(rs.getDate("report_date")),
                            rs.getString("title"),
                            rs.getString("incident_type"),
                            rs.getString("location"),
                            rs.getString("severity"),
                            rs.getString("status"),
                            rs.getString("username")
                        });
                    }
                }
            }

        } else if ("visitors".equals(type)) {

            rows.add(new String[] {"Date", "Visitor", "ID / Passport", "Phone", "Purpose"});

            String sql = ""
                + "SELECT DATE(created_at) AS created_date, name, national_id, phone_number, purpose_of_visit "
                + "FROM visitors "
                + "WHERE DATE(created_at) BETWEEN ? AND ? "
                + "ORDER BY created_at DESC, visitor_id DESC";

            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                 PreparedStatement ps = connection.prepareStatement(sql)) {

                ps.setDate(1, java.sql.Date.valueOf(startDate));
                ps.setDate(2, java.sql.Date.valueOf(endDate));

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[] {
                            String.valueOf(rs.getDate("created_date")),
                            rs.getString("name"),
                            rs.getString("national_id"),
                            rs.getString("phone_number"),
                            rs.getString("purpose_of_visit")
                        });
                    }
                }
            }

        } else if ("audit".equals(type)) {

            rows.add(new String[] {"Date", "Action", "Entity", "Entity ID", "User", "Details"});

            String sql = ""
                + "SELECT DATE(created_at) AS created_date, action_type, entity_type, entity_id, username, details "
                + "FROM audit_logs "
                + "WHERE DATE(created_at) BETWEEN ? AND ? "
                + "ORDER BY created_at DESC, audit_id DESC";

            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
                 PreparedStatement ps = connection.prepareStatement(sql)) {

                ps.setDate(1, java.sql.Date.valueOf(startDate));
                ps.setDate(2, java.sql.Date.valueOf(endDate));

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new String[] {
                            String.valueOf(rs.getDate("created_date")),
                            rs.getString("action_type"),
                            rs.getString("entity_type"),
                            defaultString(rs.getString("entity_id"), ""),
                            rs.getString("username"),
                            rs.getString("details")
                        });
                    }
                }
            }

        } else {
            throw new IllegalArgumentException("Unsupported report type");
        }

        // Build CSV output (quoted + escaped)
        StringBuilder builder = new StringBuilder();

        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                if (i > 0) {
                    builder.append(',');
                }
                // Escape quotes by doubling them (" -> "")
                builder.append('"')
                       .append(defaultString(row[i], "").replace("\"", "\"\""))
                       .append('"');
            }
            builder.append('\n');
        }

        return builder.toString();
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
