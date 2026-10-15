// ---------------------------------------------------------------------
// Global variables
// ---------------------------------------------------------------------

let currentSession = null; // Stores the currently logged-in user's session info
let incidentCache = [];    // Local cache of incident records for filtering

// ---------------------------------------------------------------------
// Session management helpers
// ---------------------------------------------------------------------

// Get the current session object
function getSession() {
    return currentSession;
}

// Set the current session object
function setSession(session) {
    currentSession = session;
}

// Clear the session (logout)
function clearSession() {
    currentSession = null;
}

// ---------------------------------------------------------------------
// UI helper functions
// ---------------------------------------------------------------------

// Display flash messages in the UI
// elementId: target element
// text: message content
// isError: if true, style as an error
function message(elementId, text, isError = false) {
    const element = document.getElementById(elementId);
    if (!element) return;

    element.textContent = text;
    element.classList.remove("hidden", "alert-success");

    if (isError) {
        element.style.background = "rgba(239, 68, 68, 0.14)";
        element.style.border = "1px solid rgba(239, 68, 68, 0.28)";
        element.style.color = "#fecaca";
    } else {
        element.style.background = "";
        element.style.border = "";
        element.style.color = "";
        element.classList.add("alert-success");
    }
}

// Returns a table row with a message when the table is empty
function tableEmptyRow(colspan, text) {
    return `<tr><td colspan="${colspan}" class="table-empty">${text}</td></tr>`;
}

// Formats today's date in "en-GB" format
function formatToday() {
    return new Intl.DateTimeFormat("en-GB", { dateStyle: "full" }).format(new Date());
}

// Normalizes a role string for comparison
function normalizedRole(role) {
    return String(role || "").toLowerCase().replace(/\s+/g, "");
}

function escapeHtml(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;");
}

function formatDateLabel(value) {
    if (!value) return "-";
    const date = new Date(`${value}T00:00:00`);
    if (Number.isNaN(date.getTime())) {
        return value;
    }
    return new Intl.DateTimeFormat("en-GB", { dateStyle: "medium" }).format(date);
}

function reportEndpoint(type, startDate, endDate) {
    const params = `startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`;
    const map = {
        access: `/api/reports/access?${params}`,
        incidents: `/api/reports/incidents?${params}`,
        visitors: `/api/reports/visitors?${params}`,
        audit: `/api/reports/audit?${params}`
    };
    return map[type] || map.access;
}

// ---------------------------------------------------------------------
// Role-based route access
// ---------------------------------------------------------------------

// Check if a user with a given session can access a page
function canAccessPage(page, session) {
    const publicPages = new Set(["login", "index"]);
    if (publicPages.has(page)) return true;

    if (!session) return false;

    const role = normalizedRole(session.role);
    const allowedPages = {
        admin: new Set(["dashboard", "users", "employees", "reports", "audit-history"]),
        security: new Set(["dashboard", "visitor-registration", "access-control", "incident-report"]),
        securityofficer: new Set(["dashboard", "visitor-registration", "access-control", "incident-report"])
    };

    return (allowedPages[role] || new Set(["dashboard"])).has(page);
}

// Redirect users if they cannot access the page
function protectRoute(page) {
    const session = getSession();

    if (!session && !canAccessPage(page, null)) {
        window.location.href = "login.html";
        return false;
    }

    if (session && !canAccessPage(page, session)) {
        window.location.href = "dashboard.html";
        return false;
    }

    return true;
}

// Apply role-based UI changes (hide restricted links)
function applyRolePermissions() {
    const session = getSession();
    if (!session) return;

    const role = normalizedRole(session.role);
    const restrictedPages = role === "admin"
        ? new Set(["visitor-registration.html", "access-control.html", "incident-report.html"])
        : new Set(["users.html", "employees.html", "reports.html", "audit-history.html"]);

    document.querySelectorAll("a[href]").forEach((link) => {
        if (restrictedPages.has(link.getAttribute("href"))) {
            link.classList.add("hidden");
        }
    });
}

// ---------------------------------------------------------------------
// Logout handling
// ---------------------------------------------------------------------

function bindLogout() {
    document.querySelectorAll("[data-logout]").forEach((link) => {
        link.addEventListener("click", async (event) => {
            event.preventDefault();
            try {
                await api("/api/logout", { method: "POST" });
            } catch (_error) {
                // Ignore errors; clear client-side state anyway
            }
            clearSession();
            window.location.href = "login.html";
        });
    });
}

// ---------------------------------------------------------------------
// API helpers
// ---------------------------------------------------------------------

// Generic fetch wrapper for backend API requests
async function api(path, options = {}) {
    const response = await fetch(path, { credentials: "same-origin", ...options });
    const contentType = response.headers.get("Content-Type") || "";
    const payload = response.status === 204
        ? null
        : contentType.includes("application/json") ? await response.json() : await response.text();

    if (!response.ok) {
        const messageText = typeof payload === "string" ? payload : payload.message || "Request failed";
        throw new Error(messageText);
    }

    return payload;
}

// Converts an object to URLSearchParams for form submissions
function formBody(values) {
    return new URLSearchParams(values);
}

// ---------------------------------------------------------------------
// Data loading functions
// ---------------------------------------------------------------------

async function loadVisitors() { return api("/api/visitors"); }
async function loadEmployees() { return api("/api/employees"); }
async function loadAccessLogs() { return api("/api/access-logs"); }
async function loadIncidents() { return api("/api/incidents"); }
async function loadUsers() { return api("/api/users"); }
async function loadEmployeeDirectory() { return api("/api/employees"); }

async function loadAccessReport(startDate, endDate) {
    return api(`/api/reports/access?startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`);
}
async function loadIncidentReport(startDate, endDate) {
    return api(`/api/reports/incidents?startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`);
}
async function loadVisitorReport(startDate, endDate) {
    return api(`/api/reports/visitors?startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`);
}
async function loadAuditReport(startDate, endDate) {
    return api(`/api/reports/audit?startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`);
}

// Default audit date range (last 7 days)
function defaultAuditRange() {
    const end = new Date();
    const start = new Date();
    start.setDate(end.getDate() - 6);
    return {
        startDate: start.toISOString().slice(0, 10),
        endDate: end.toISOString().slice(0, 10)
    };
}

// ---------------------------------------------------------------------
// Table rendering helpers
// ---------------------------------------------------------------------

// Render visitor table
async function renderVisitorTable() {
    const target = document.getElementById("visitorTable");
    if (!target) return;

    const visitors = await loadVisitors();
    if (!visitors.length) {
        target.innerHTML = tableEmptyRow(5, "No visitors registered yet.");
        bindVisitorRowActions(visitors);
        return;
    }

    target.innerHTML = visitors.map((visitor) => `
        <tr>
            <td>${escapeHtml(visitor.name)}</td>
            <td>${escapeHtml(visitor.nationalId)}</td>
            <td>${escapeHtml(visitor.phoneNumber)}</td>
            <td>${escapeHtml(visitor.purposeOfVisit)}</td>
            <td>
                <button type="button" class="btn btn-secondary" data-edit-visitor="${visitor.id}">Edit</button>
                <button type="button" class="btn btn-danger" data-delete-visitor="${visitor.id}">Delete</button>
            </td>
        </tr>
    `).join("");

    bindVisitorRowActions(visitors);
}

// Render access dropdowns
async function renderAccessSelects() {
    const [visitors, employees, logs] = await Promise.all([
        loadVisitors(),
        loadEmployeeDirectory(),
        loadAccessLogs()
    ]);
    const activeVisitorIds = new Set(
        logs.filter((log) => !log.exitTime).map((log) => Number(log.visitorId))
    );

    const visitorOptions = visitors.map((visitor) => (
        `<option value="${visitor.id}">${escapeHtml(visitor.name)} (${escapeHtml(visitor.nationalId)})</option>`
    )).join("");
    const activeVisitorOptions = visitors
        .filter((visitor) => activeVisitorIds.has(Number(visitor.id)))
        .map((visitor) => (
            `<option value="${visitor.id}">${escapeHtml(visitor.name)} (${escapeHtml(visitor.nationalId)})</option>`
        ))
        .join("");
    const employeeOptions = employees.map((employee) => (
        `<option value="${employee.id}">${escapeHtml(employee.name)} (${escapeHtml(employee.department)})</option>`
    )).join("");

    [document.getElementById("visitorId"), document.getElementById("editVisitorId")].forEach((select) => {
        if (!select) return;
        select.innerHTML = `<option value="">Select visitor</option>${visitorOptions}`;
    });

    const exitVisitorSelect = document.getElementById("exitVisitorId");
    if (exitVisitorSelect) {
        exitVisitorSelect.innerHTML = `<option value="">Select visitor</option>${activeVisitorOptions}`;
    }

    [document.getElementById("employeeId"), document.getElementById("editEmployeeId")].forEach((select) => {
        if (!select) return;
        select.innerHTML = `<option value="">Select employee</option>${employeeOptions}`;
    });
}

// Render access table
async function renderAccessTable(targetId = "accessTable", editable = true) {
    const target = document.getElementById(targetId);
    if (!target) return;

    const logs = await loadAccessLogs();
    if (!logs.length) {
        target.innerHTML = tableEmptyRow(editable ? 7 : 6, "No access logs recorded yet.");
        if (editable) bindAccessRowActions(logs);
        return;
    }

    target.innerHTML = logs.map((log) => `
        <tr>
            <td>${formatDateLabel(log.date)}</td>
            <td>${escapeHtml(log.visitorName)}</td>
            <td>${escapeHtml(log.host)}</td>
            <td>${escapeHtml(log.entryTime)}</td>
            <td>${escapeHtml(log.exitTime || "-")}</td>
            <td>${escapeHtml(log.recordedBy)}</td>
            ${editable ? `
            <td>
                <button type="button" class="btn btn-secondary" data-edit-access="${log.id}">Edit</button>
                <button type="button" class="btn btn-danger" data-delete-access="${log.id}">Delete</button>
            </td>` : ""}
        </tr>
    `).join("");

    if (editable) {
        bindAccessRowActions(logs);
    }
}

// Render incident table
async function renderIncidentTable() {
    incidentCache = await loadIncidents();
    renderIncidentRows(incidentCache);
}

// Render incidents rows
function renderIncidentRows(incidents) {
    const target = document.getElementById("incidentTable");
    if (!target) return;

    if (!incidents.length) {
        target.innerHTML = tableEmptyRow(10, "No incidents found.");
        bindIncidentRowActions(incidents);
        return;
    }

    target.innerHTML = incidents.map((incident) => `
        <tr>
            <td>${formatDateLabel(incident.date)}</td>
            <td>${escapeHtml(incident.title)}</td>
            <td>${escapeHtml(incident.incidentType)}</td>
            <td>${escapeHtml(incident.location)}</td>
            <td>${escapeHtml(incident.severity)}</td>
            <td>${escapeHtml(incident.status)}</td>
            <td>${escapeHtml(incident.reportedBy)}</td>
            <td>${escapeHtml(incident.actionTaken || "-")}</td>
            <td>${escapeHtml(incident.description)}</td>
            <td>
                <button type="button" class="btn btn-secondary" data-edit-incident="${incident.id}">Edit</button>
            </td>
        </tr>
    `).join("");

    bindIncidentRowActions(incidents);
}

// Filter incidents based on status/date
function filterIncidents() {
    const status = document.getElementById("incidentFilterStatus")?.value || "";
    const date = document.getElementById("incidentFilterDate")?.value || "";

    const filtered = incidentCache.filter((incident) => {
        const statusMatch = !status || incident.status === status;
        const dateMatch = !date || incident.date === date;
        return statusMatch && dateMatch;
    });

    renderIncidentRows(filtered);
}

// Render dashboard overview
async function renderDashboard() {
    const session = getSession();
    const [visitors, logs, incidents] = await Promise.all([
        loadVisitors(),
        loadAccessLogs(),
        loadIncidents()
    ]);

    const activeVisitors = logs.filter((log) => !log.exitTime);
    const recentIncidents = incidents.slice(0, 5);

    const sessionUser = document.getElementById("sessionUser");
    const sessionRole = document.getElementById("sessionRole");
    const currentDate = document.getElementById("currentDate");
    const activeVisitorsCount = document.getElementById("activeVisitorsCount");
    const visitorCount = document.getElementById("visitorCount");
    const openVisitsCount = document.getElementById("openVisitsCount");
    const incidentCount = document.getElementById("incidentCount");
    const incidentPreview = document.getElementById("incidentPreview");

    if (sessionUser) sessionUser.textContent = session?.username || "Operations Desk";
    if (sessionRole) sessionRole.textContent = session?.role || "-";
    if (currentDate) currentDate.textContent = formatToday();
    if (activeVisitorsCount) activeVisitorsCount.textContent = String(activeVisitors.length);
    if (visitorCount) visitorCount.textContent = String(visitors.length);
    if (openVisitsCount) openVisitsCount.textContent = String(activeVisitors.length);
    if (incidentCount) incidentCount.textContent = String(incidents.length);

    if (incidentPreview) {
        if (!recentIncidents.length) {
            incidentPreview.classList.add("empty-state");
            incidentPreview.innerHTML = "No incidents logged yet.";
        } else {
            incidentPreview.classList.remove("empty-state");
            incidentPreview.innerHTML = recentIncidents.map((incident) => `
                <div class="record-item">
                    <strong>${escapeHtml(incident.title)}</strong>
                    <p>${escapeHtml(incident.status)} | ${formatDateLabel(incident.date)} | ${escapeHtml(incident.location)}</p>
                </div>
            `).join("");
        }
    }

    await renderAccessTable("dashboardAccessTable", false);
}

// Render users table
async function renderUsersTable() {
    const target = document.getElementById("usersTable");
    if (!target) return;

    const users = await loadUsers();

    if (!users.length) {
        target.innerHTML = tableEmptyRow(4, "No users found.");
        bindUserRowActions(users);
        return;
    }

    target.innerHTML = users.map((user) => `
        <tr>
            <td>${user.userId}</td>
            <td>${user.username}</td>
            <td>${user.role}</td>
            <td>
                <button type="button" class="btn btn-secondary" data-edit-user="${user.userId}">Edit</button>
                <button type="button" class="btn btn-danger" data-delete-user="${user.userId}">Delete</button>
            </td>
        </tr>
    `).join("");

    bindUserRowActions(users);
}

// Render employees table
async function renderEmployeesTable() {
    const target = document.getElementById("employeesTable");
    if (!target) return;

    const employees = await loadEmployees();
    if (!employees.length) {
        target.innerHTML = tableEmptyRow(5, "No employees found.");
        bindEmployeeRowActions(employees);
        return;
    }

    target.innerHTML = employees.map((employee) => `
        <tr>
            <td>${employee.id}</td>
            <td>${escapeHtml(employee.name)}</td>
            <td>${escapeHtml(employee.department)}</td>
            <td>${escapeHtml(employee.phoneNumber || "-")}</td>
            <td>
                <button type="button" class="btn btn-secondary" data-edit-employee="${employee.id}">Edit</button>
                <button type="button" class="btn btn-danger" data-delete-employee="${employee.id}">Delete</button>
            </td>
        </tr>
    `).join("");

    bindEmployeeRowActions(employees);
}

// Render reports header
function renderReportsHeader(type) {
    const head = document.getElementById("reportsHead");
    if (!head) return;

    const headers = {
        access: ["Date", "Visitor", "Host", "Entry Time", "Exit Time", "Recorded By"],
        incidents: ["Date", "Title", "Type", "Location", "Severity", "Status", "Reported By"],
        visitors: ["Date", "Name", "ID / Passport", "Phone", "Purpose"],
        audit: ["Date", "Action", "Entity", "Entity ID", "User", "Details"]
    };

    head.innerHTML = `<tr>${(headers[type] || headers.access).map((label) => `<th>${label}</th>`).join("")}</tr>`;
}

// Render reports table
async function renderReportsTable(rows = [], type = "access") {
    const target = document.getElementById("reportsTable");
    if (!target) return;

    renderReportsHeader(type);

    if (!rows.length) {
        const colspan = { access: 6, incidents: 7, visitors: 5, audit: 6 }[type] || 6;
        target.innerHTML = tableEmptyRow(colspan, "No report data found for that date range.");
        return;
    }

    if (type === "access") {
        target.innerHTML = rows.map((row) => `
            <tr>
                <td>${formatDateLabel(row.date)}</td>
                <td>${escapeHtml(row.visitorName)}</td>
                <td>${escapeHtml(row.host)}</td>
                <td>${escapeHtml(row.entryTime)}</td>
                <td>${escapeHtml(row.exitTime || "-")}</td>
                <td>${escapeHtml(row.recordedBy)}</td>
            </tr>
        `).join("");
        return;
    }

    if (type === "incidents") {
        target.innerHTML = rows.map((row) => `
            <tr>
                <td>${formatDateLabel(row.date)}</td>
                <td>${escapeHtml(row.title)}</td>
                <td>${escapeHtml(row.incidentType)}</td>
                <td>${escapeHtml(row.location)}</td>
                <td>${escapeHtml(row.severity)}</td>
                <td>${escapeHtml(row.status)}</td>
                <td>${escapeHtml(row.reportedBy)}</td>
            </tr>
        `).join("");
        return;
    }

    if (type === "visitors") {
        target.innerHTML = rows.map((row) => `
            <tr>
                <td>${formatDateLabel(row.date)}</td>
                <td>${escapeHtml(row.name)}</td>
                <td>${escapeHtml(row.nationalId)}</td>
                <td>${escapeHtml(row.phoneNumber)}</td>
                <td>${escapeHtml(row.purposeOfVisit)}</td>
            </tr>
        `).join("");
        return;
    }

    target.innerHTML = rows.map((row) => `
        <tr>
            <td>${formatDateLabel(row.date)}</td>
            <td>${escapeHtml(row.actionType)}</td>
            <td>${escapeHtml(row.entityType)}</td>
            <td>${escapeHtml(row.entityId || "-")}</td>
            <td>${escapeHtml(row.username)}</td>
            <td>${escapeHtml(row.details)}</td>
        </tr>
    `).join("");
}

// Render audit table
async function renderAuditTable(rows = []) {
    const target = document.getElementById("auditTable");
    if (!target) return;

    if (!rows.length) {
        target.innerHTML = tableEmptyRow(6, "No audit entries found for that date range.");
        return;
    }

    target.innerHTML = rows.map((row) => `
        <tr>
            <td>${formatDateLabel(row.date)}</td>
            <td>${escapeHtml(row.actionType)}</td>
            <td>${escapeHtml(row.entityType)}</td>
            <td>${escapeHtml(row.entityId || "-")}</td>
            <td>${escapeHtml(row.username)}</td>
            <td>${escapeHtml(row.details)}</td>
        </tr>
    `).join("");
}

// ---------------------------------------------------------------------
// Row actions (edit/delete)
// ---------------------------------------------------------------------

function bindUserRowActions(users) {
    document.querySelectorAll("[data-edit-user]").forEach((button) => {
        button.addEventListener("click", () => {
            const userId = Number(button.dataset.editUser);
            const user = users.find((entry) => entry.userId === userId);
            if (!user) return;

            const userIdField = document.getElementById("editUserId");
            const usernameField = document.getElementById("editUsername");
            const passwordField = document.getElementById("editPassword");
            const roleField = document.getElementById("editRole");

            if (userIdField) userIdField.value = String(user.userId);
            if (usernameField) usernameField.value = user.username;
            if (passwordField) passwordField.value = "";
            if (roleField) roleField.value = user.role;

            message("userMessage", `Editing user "${user.username}".`);
        });
    });

    document.querySelectorAll("[data-delete-user]").forEach((button) => {
        button.addEventListener("click", async () => {
            const userId = Number(button.dataset.deleteUser);
            const user = users.find((entry) => entry.userId === userId);
            if (!user) return;

            const confirmed = window.confirm(`Delete user "${user.username}"?`);
            if (!confirmed) return;

            try {
                await api("/api/users/delete", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ targetUserId: userId })
                });

                message("userMessage", `User "${user.username}" deleted successfully.`);
                await renderUsersTable();
            } catch (error) {
                message("userMessage", error.message || "Failed to delete user.", true);
            }
        });
    });
}
function bindVisitorRowActions(visitors) {
    document.querySelectorAll("[data-edit-visitor]").forEach((button) => {
        button.addEventListener("click", () => {
            const visitor = visitors.find((entry) => entry.id === Number(button.dataset.editVisitor));
            if (!visitor) return;

            document.getElementById("editVisitorId").value = String(visitor.id);
            document.getElementById("editVisitorName").value = visitor.name;
            document.getElementById("editVisitorNationalId").value = visitor.nationalId;
            document.getElementById("editVisitorPhoneNumber").value = visitor.phoneNumber;
            document.getElementById("editVisitorPurposeOfVisit").value = visitor.purposeOfVisit;

            message("visitorMessage", `Editing visitor "${visitor.name}".`);
        });
    });

    document.querySelectorAll("[data-delete-visitor]").forEach((button) => {
        button.addEventListener("click", async () => {
            const visitor = visitors.find((entry) => entry.id === Number(button.dataset.deleteVisitor));
            if (!visitor) return;

            if (!window.confirm(`Delete visitor "${visitor.name}"?`)) {
                return;
            }

            try {
                await api("/api/visitors/delete", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ visitorId: visitor.id })
                });
                message("visitorMessage", `Visitor "${visitor.name}" deleted successfully.`);
                await renderVisitorTable();
            } catch (error) {
                message("visitorMessage", error.message || "Failed to delete visitor.", true);
            }
        });
    });
}
function bindEmployeeRowActions(employees) {
    document.querySelectorAll("[data-edit-employee]").forEach((button) => {
        button.addEventListener("click", () => {
            const employee = employees.find((entry) => entry.id === Number(button.dataset.editEmployee));
            if (!employee) return;

            document.getElementById("editEmployeeId").value = String(employee.id);
            document.getElementById("editEmployeeName").value = employee.name;
            document.getElementById("editEmployeeDepartment").value = employee.department;
            document.getElementById("editEmployeePhone").value = employee.phoneNumber || "";

            message("employeeMessage", `Editing employee "${employee.name}".`);
        });
    });

    document.querySelectorAll("[data-delete-employee]").forEach((button) => {
        button.addEventListener("click", async () => {
            const employee = employees.find((entry) => entry.id === Number(button.dataset.deleteEmployee));
            if (!employee) return;

            if (!window.confirm(`Delete employee "${employee.name}"?`)) {
                return;
            }

            try {
                await api("/api/employees/delete", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ employeeId: employee.id })
                });
                message("employeeMessage", `Employee "${employee.name}" deleted successfully.`);
                await renderEmployeesTable();
            } catch (error) {
                message("employeeMessage", error.message || "Failed to delete employee.", true);
            }
        });
    });
}
function bindAccessRowActions(logs) {
    document.querySelectorAll("[data-edit-access]").forEach((button) => {
        button.addEventListener("click", () => {
            const log = logs.find((entry) => entry.id === Number(button.dataset.editAccess));
            if (!log) return;

            document.getElementById("editLogId").value = String(log.id);
            document.getElementById("editVisitorId").value = String(log.visitorId);
            document.getElementById("editEmployeeId").value = String(log.employeeId);
            document.getElementById("editVisitDate").value = log.date;
            document.getElementById("editEntryTime").value = String(log.entryTime || "").slice(0, 5);
            document.getElementById("editExitTime").value = log.exitTime ? String(log.exitTime).slice(0, 5) : "";

            message("accessMessage", `Editing access record for "${log.visitorName}".`);
        });
    });

    document.querySelectorAll("[data-delete-access]").forEach((button) => {
        button.addEventListener("click", async () => {
            const log = logs.find((entry) => entry.id === Number(button.dataset.deleteAccess));
            if (!log) return;

            if (!window.confirm(`Delete access record for "${log.visitorName}" on ${log.date}?`)) {
                return;
            }

            try {
                await api("/api/access-logs/delete", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ logId: log.id })
                });
                message("accessMessage", "Access record deleted successfully.");
                await Promise.all([renderAccessSelects(), renderAccessTable()]);
            } catch (error) {
                message("accessMessage", error.message || "Failed to delete access record.", true);
            }
        });
    });
}
function bindIncidentRowActions(incidents) {
    document.querySelectorAll("[data-edit-incident]").forEach((button) => {
        button.addEventListener("click", () => {
            const incident = incidents.find((entry) => entry.id === Number(button.dataset.editIncident));
            if (!incident) return;

            document.getElementById("editIncidentId").value = String(incident.id);
            document.getElementById("editTitle").value = incident.title;
            document.getElementById("editIncidentType").value = incident.incidentType;
            document.getElementById("editLocation").value = incident.location;
            document.getElementById("editSeverity").value = incident.severity;
            document.getElementById("editStatus").value = incident.status;
            document.getElementById("editDescription").value = incident.description;
            document.getElementById("editActionTaken").value = incident.actionTaken || "";

            message("incidentMessage", `Editing incident "${incident.title}".`);
        });
    });
}

// ---------------------------------------------------------------------
// Form bindings
// ---------------------------------------------------------------------

function bindLogin() {
    const form = document.getElementById("loginForm");
    if (!form) return;

    form.addEventListener("submit", async (event) => {
        event.preventDefault();

        const formData = new FormData(form);
        const username = String(formData.get("username") || "").trim();
        const password = String(formData.get("password") || "").trim();

        if (!username || !password) {
            message("loginMessage", "Enter both username and password.", true);
            return;
        }

        try {
            const session = await api("/api/login", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                },
                body: formBody({ username, password })
            });

            setSession(session);
            message("loginMessage", "Login successful. Redirecting...");
            window.location.href = "dashboard.html";
        } catch (error) {
            clearSession();
            message("loginMessage", error.message || "Login failed.", true);
        }
    });
}
function bindUserForms() {
    const createForm = document.getElementById("userCreateForm");
    if (createForm) {
        createForm.addEventListener("submit", async (event) => {
            event.preventDefault();

            const username = document.getElementById("newUsername")?.value.trim() || "";
            const password = document.getElementById("newPassword")?.value.trim() || "";
            const role = document.getElementById("newRole")?.value || "";

            if (!username || !password || !role) {
                message("userMessage", "All user fields are required.", true);
                return;
            }

            try {
                await api("/api/users", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ username, password, role })
                });

                createForm.reset();
                message("userMessage", `User "${username}" created successfully.`);
                await renderUsersTable();
            } catch (error) {
                message("userMessage", error.message || "Failed to create user.", true);
            }
        });
    }

    const editForm = document.getElementById("userEditForm");
    if (editForm) {
        editForm.addEventListener("submit", async (event) => {
            event.preventDefault();

            const targetUserId = document.getElementById("editUserId")?.value || "";
            const username = document.getElementById("editUsername")?.value.trim() || "";
            const password = document.getElementById("editPassword")?.value.trim() || "";
            const role = document.getElementById("editRole")?.value || "";

            if (!targetUserId) {
                message("userMessage", "Select a user to edit first.", true);
                return;
            }

            if (!username || !role) {
                message("userMessage", "Username and role are required.", true);
                return;
            }

            try {
                const result = await api("/api/users/update", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ targetUserId, username, password, role })
                });

                editForm.reset();
                const hiddenIdField = document.getElementById("editUserId");
                if (hiddenIdField) hiddenIdField.value = "";

                if (result.updatedCurrentUser) {
                    clearSession();
                    message("userMessage", "Your account changed. Please sign in again.");
                    window.location.href = "login.html";
                    return;
                }

                const suffix = result.sessionRevoked ? " Active sessions were revoked." : "";
                message("userMessage", `User "${username}" updated successfully.${suffix}`);
                await renderUsersTable();
            } catch (error) {
                message("userMessage", error.message || "Failed to update user.", true);
            }
        });
    }
}
function bindVisitorForm() {
    const createForm = document.getElementById("visitorForm");
    if (createForm) {
        createForm.addEventListener("submit", async (event) => {
            event.preventDefault();

            const name = document.getElementById("name")?.value.trim() || "";
            const nationalId = document.getElementById("nationalId")?.value.trim() || "";
            const phoneNumber = document.getElementById("phoneNumber")?.value.trim() || "";
            const purposeOfVisit = document.getElementById("purposeOfVisit")?.value.trim() || "";

            if (!name || !nationalId || !phoneNumber || !purposeOfVisit) {
                message("visitorMessage", "All visitor fields are required.", true);
                return;
            }

            try {
                await api("/api/visitors", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ name, nationalId, phoneNumber, purposeOfVisit })
                });
                createForm.reset();
                message("visitorMessage", `Visitor "${name}" registered successfully.`);
                await renderVisitorTable();
            } catch (error) {
                message("visitorMessage", error.message || "Failed to save visitor.", true);
            }
        });
    }

    const editForm = document.getElementById("visitorEditForm");
    if (editForm) {
        editForm.addEventListener("submit", async (event) => {
            event.preventDefault();

            const visitorId = document.getElementById("editVisitorId")?.value || "";
            const name = document.getElementById("editVisitorName")?.value.trim() || "";
            const nationalId = document.getElementById("editVisitorNationalId")?.value.trim() || "";
            const phoneNumber = document.getElementById("editVisitorPhoneNumber")?.value.trim() || "";
            const purposeOfVisit = document.getElementById("editVisitorPurposeOfVisit")?.value.trim() || "";

            if (!visitorId) {
                message("visitorMessage", "Select a visitor to edit first.", true);
                return;
            }

            try {
                await api("/api/visitors/update", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ visitorId, name, nationalId, phoneNumber, purposeOfVisit })
                });
                editForm.reset();
                document.getElementById("editVisitorId").value = "";
                message("visitorMessage", `Visitor "${name}" updated successfully.`);
                await renderVisitorTable();
            } catch (error) {
                message("visitorMessage", error.message || "Failed to update visitor.", true);
            }
        });
    }
}
function bindAccessForms() {
    const entryForm = document.getElementById("entryForm");
    if (entryForm) {
        entryForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const visitorId = document.getElementById("visitorId")?.value || "";
            const employeeId = document.getElementById("employeeId")?.value || "";

            if (!visitorId || !employeeId) {
                message("accessMessage", "Select both visitor and host employee.", true);
                return;
            }

            try {
                await api("/api/access-logs/entry", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ visitorId, employeeId })
                });
                entryForm.reset();
                message("accessMessage", "Visitor entry recorded successfully.");
                await Promise.all([renderAccessSelects(), renderAccessTable()]);
            } catch (error) {
                message("accessMessage", error.message || "Failed to record entry.", true);
            }
        });
    }

    const exitForm = document.getElementById("exitForm");
    if (exitForm) {
        exitForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const visitorId = document.getElementById("exitVisitorId")?.value || "";

            if (!visitorId) {
                message("accessMessage", "Select a visitor to record exit.", true);
                return;
            }

            try {
                await api("/api/access-logs/exit", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ visitorId })
                });
                exitForm.reset();
                message("accessMessage", "Visitor exit recorded successfully.");
                await Promise.all([renderAccessSelects(), renderAccessTable()]);
            } catch (error) {
                message("accessMessage", error.message || "Failed to record exit.", true);
            }
        });
    }

    const editForm = document.getElementById("accessEditForm");
    if (editForm) {
        editForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const logId = document.getElementById("editLogId")?.value || "";
            const visitorId = document.getElementById("editVisitorId")?.value || "";
            const employeeId = document.getElementById("editEmployeeId")?.value || "";
            const visitDate = document.getElementById("editVisitDate")?.value || "";
            const entryTime = document.getElementById("editEntryTime")?.value || "";
            const exitTime = document.getElementById("editExitTime")?.value || "";

            if (!logId) {
                message("accessMessage", "Select an access record to edit first.", true);
                return;
            }

            try {
                await api("/api/access-logs/update", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ logId, visitorId, employeeId, visitDate, entryTime, exitTime })
                });
                editForm.reset();
                document.getElementById("editLogId").value = "";
                message("accessMessage", "Access record updated successfully.");
                await Promise.all([renderAccessSelects(), renderAccessTable()]);
            } catch (error) {
                message("accessMessage", error.message || "Failed to update access record.", true);
            }
        });
    }
}
function bindIncidentForm() {
    const form = document.getElementById("incidentForm");
    if (!form) return;

    form.addEventListener("submit", async (event) => {
        event.preventDefault();

        const title = document.getElementById("title")?.value.trim() || "";
        const incidentType = document.getElementById("incidentType")?.value || "";
        const location = document.getElementById("location")?.value.trim() || "";
        const severity = document.getElementById("severity")?.value || "";
        const status = document.getElementById("status")?.value || "";
        const description = document.getElementById("description")?.value.trim() || "";
        const actionTaken = document.getElementById("actionTaken")?.value.trim() || "";

        if (!title || !incidentType || !location || !severity || !status || !description) {
            message("incidentMessage", "All incident fields are required.", true);
            return;
        }

        try {
            await api("/api/incidents", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                },
                body: formBody({ title, incidentType, location, severity, status, description, actionTaken })
            });
            form.reset();
            message("incidentMessage", `Incident "${title}" saved successfully.`);
            await renderIncidentTable();
        } catch (error) {
            message("incidentMessage", error.message || "Failed to save incident.", true);
        }
    });
}
function bindIncidentEditForm() {
    const form = document.getElementById("incidentEditForm");
    if (!form) return;

    form.addEventListener("submit", async (event) => {
        event.preventDefault();

        const incidentId = document.getElementById("editIncidentId")?.value || "";
        const title = document.getElementById("editTitle")?.value.trim() || "";
        const incidentType = document.getElementById("editIncidentType")?.value || "";
        const location = document.getElementById("editLocation")?.value.trim() || "";
        const severity = document.getElementById("editSeverity")?.value || "";
        const status = document.getElementById("editStatus")?.value || "";
        const description = document.getElementById("editDescription")?.value.trim() || "";
        const actionTaken = document.getElementById("editActionTaken")?.value.trim() || "";

        if (!incidentId) {
            message("incidentMessage", "Select an incident to edit first.", true);
            return;
        }

        try {
            await api("/api/incidents/update", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                },
                body: formBody({ incidentId, title, incidentType, location, severity, status, description, actionTaken })
            });
            form.reset();
            document.getElementById("editIncidentId").value = "";
            message("incidentMessage", `Incident "${title}" updated successfully.`);
            await renderIncidentTable();
        } catch (error) {
            message("incidentMessage", error.message || "Failed to update incident.", true);
        }
    });
}
function bindIncidentFilters() {
    const filterForm = document.getElementById("incidentFilterForm");
    if (filterForm) {
        filterForm.addEventListener("submit", (event) => {
            event.preventDefault();
            filterIncidents();
        });
    }

    document.getElementById("resetIncidentFilters")?.addEventListener("click", () => {
        if (filterForm) {
            filterForm.reset();
        }
        renderIncidentRows(incidentCache);
    });
}

function bindEmployeeForms() {
    const createForm = document.getElementById("employeeCreateForm");
    if (createForm) {
        createForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const name = document.getElementById("employeeName")?.value.trim() || "";
            const department = document.getElementById("employeeDepartment")?.value.trim() || "";
            const phoneNumber = document.getElementById("employeePhone")?.value.trim() || "";

            if (!name || !department) {
                message("employeeMessage", "Employee name and department are required.", true);
                return;
            }

            try {
                await api("/api/employees/create", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ name, department, phoneNumber })
                });
                createForm.reset();
                message("employeeMessage", `Employee "${name}" created successfully.`);
                await renderEmployeesTable();
            } catch (error) {
                message("employeeMessage", error.message || "Failed to create employee.", true);
            }
        });
    }

    const editForm = document.getElementById("employeeEditForm");
    if (editForm) {
        editForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const employeeId = document.getElementById("editEmployeeId")?.value || "";
            const name = document.getElementById("editEmployeeName")?.value.trim() || "";
            const department = document.getElementById("editEmployeeDepartment")?.value.trim() || "";
            const phoneNumber = document.getElementById("editEmployeePhone")?.value.trim() || "";

            if (!employeeId) {
                message("employeeMessage", "Select an employee to edit first.", true);
                return;
            }

            try {
                await api("/api/employees/update", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
                    },
                    body: formBody({ employeeId, name, department, phoneNumber })
                });
                editForm.reset();
                document.getElementById("editEmployeeId").value = "";
                message("employeeMessage", `Employee "${name}" updated successfully.`);
                await renderEmployeesTable();
            } catch (error) {
                message("employeeMessage", error.message || "Failed to update employee.", true);
            }
        });
    }
}

function bindReports() {
    const form = document.getElementById("reportForm");
    const typeField = document.getElementById("reportType");
    const startField = document.getElementById("reportStartDate");
    const endField = document.getElementById("reportEndDate");
    if (!form || !typeField || !startField || !endField) return;

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const type = typeField.value;
        const startDate = startField.value;
        const endDate = endField.value;

        try {
            const rows = await api(reportEndpoint(type, startDate, endDate));
            await renderReportsTable(rows, type);
            message("reportMessage", `Loaded ${type} report successfully.`);
        } catch (error) {
            message("reportMessage", error.message || "Failed to load report.", true);
        }
    });

    document.getElementById("exportReport")?.addEventListener("click", () => {
        const type = typeField.value;
        const startDate = startField.value;
        const endDate = endField.value;
        if (!startDate || !endDate) {
            message("reportMessage", "Select both start and end dates before exporting.", true);
            return;
        }
        window.location.href = `/api/reports/export?type=${encodeURIComponent(type)}&startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`;
    });
}

function bindAuditHistory() {
    const form = document.getElementById("auditForm");
    const startField = document.getElementById("auditStartDate");
    const endField = document.getElementById("auditEndDate");
    if (!form || !startField || !endField) return;

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const startDate = startField.value;
        const endDate = endField.value;

        try {
            const rows = await loadAuditReport(startDate, endDate);
            await renderAuditTable(rows);
            message("auditMessage", "Audit history loaded successfully.");
        } catch (error) {
            message("auditMessage", error.message || "Failed to load audit history.", true);
        }
    });

    document.getElementById("exportAuditHistory")?.addEventListener("click", () => {
        const startDate = startField.value;
        const endDate = endField.value;
        if (!startDate || !endDate) {
            message("auditMessage", "Select both start and end dates before exporting.", true);
            return;
        }
        window.location.href = `/api/reports/export?type=audit&startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`;
    });
}

// ---------------------------------------------------------------------
// Page bootstrapping
// ---------------------------------------------------------------------

async function restoreSession() {
    try {
        const session = await api("/api/session");
        setSession(session);
        return session;
    } catch (_error) {
        clearSession();
        return null;
    }
}

document.addEventListener("DOMContentLoaded", async () => {
    const page = document.body?.dataset?.page || "";

    // ❌ Disable auto-login completely
    let session = await restoreSession();

    // ✅ LOGIN PAGE (always accessible, no redirect)
    if (page === "login") {
        bindLogin();
        return;
    }

    // ✅ Protect all other pages
    if (!protectRoute(page)) {
        return;
    }

    applyRolePermissions();
    bindLogout();

    if (page === "users") {
        bindUserForms();
        await renderUsersTable();
        return;
    }

    if (page === "employees") {
        bindEmployeeForms();
        await renderEmployeesTable();
        return;
    }

    if (page === "visitor-registration") {
        bindVisitorForm();
        await renderVisitorTable();
        return;
    }

    if (page === "access-control") {
        bindAccessForms();
        await Promise.all([renderAccessSelects(), renderAccessTable()]);
        return;
    }

    if (page === "incident-report") {
        bindIncidentForm();
        bindIncidentEditForm();
        bindIncidentFilters();
        await renderIncidentTable();
        return;
    }

    if (page === "dashboard") {
        await renderDashboard();
        return;
    }

    if (page === "reports") {
        const range = defaultAuditRange();
        const startField = document.getElementById("reportStartDate");
        const endField = document.getElementById("reportEndDate");

        if (startField) startField.value = range.startDate;
        if (endField) endField.value = range.endDate;

        bindReports();
        await renderReportsTable([], document.getElementById("reportType")?.value || "access");
        return;
    }

    if (page === "audit-history") {
        const range = defaultAuditRange();
        const startField = document.getElementById("auditStartDate");
        const endField = document.getElementById("auditEndDate");

        if (startField) startField.value = range.startDate;
        if (endField) endField.value = range.endDate;

        bindAuditHistory();

        try {
            const rows = await loadAuditReport(range.startDate, range.endDate);
            await renderAuditTable(rows);
        } catch (error) {
            message("auditMessage", error.message || "Failed to load audit history.", true);
        }
    }
});