let currentSession = null;
let incidentCache = [];

// Browser-side session cache so each page can make role decisions without refetching constantly.
function getSession() {
    return currentSession;
}

function setSession(session) {
    currentSession = session;
}

function clearSession() {
    currentSession = null;
}

// Shared flash-message helper used across forms and management pages.
function message(elementId, text, isError = false) {
    const element = document.getElementById(elementId);
    if (!element) {
        return;
    }

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

function tableEmptyRow(colspan, text) {
    return `<tr><td colspan="${colspan}" class="table-empty">${text}</td></tr>`;
}

function formatToday() {
    return new Intl.DateTimeFormat("en-GB", {
        dateStyle: "full"
    }).format(new Date());
}

function normalizedRole(role) {
    return String(role || "")
        .toLowerCase()
        .replace(/\s+/g, "");
}

// Route guard rules for each role. The backend still enforces these permissions too.
function canAccessPage(page, session) {
    const publicPages = new Set(["login", "index"]);
    if (publicPages.has(page)) {
        return true;
    }

    if (!session) {
        return false;
    }

    const role = normalizedRole(session.role);
    const allowedPages = {
        admin: new Set(["dashboard", "users", "employees", "reports", "audit-history"]),
        security: new Set(["dashboard", "visitor-registration", "access-control", "incident-report"]),
        securityofficer: new Set(["dashboard", "visitor-registration", "access-control", "incident-report"])
    };

    return (allowedPages[role] || new Set(["dashboard"])).has(page);
}

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

// Keep the navigation aligned with the current role so users only see allowed pages.
function applyRolePermissions() {
    const session = getSession();
    if (!session) {
        return;
    }

    const role = normalizedRole(session.role);
    const restrictedPages = role === "admin"
        ? new Set(["visitor-registration.html", "access-control.html", "incident-report.html"])
        : new Set(["users.html", "employees.html", "reports.html", "audit-history.html"]);

    // Hide links the current role cannot use so the nav mirrors backend authorization.
    document.querySelectorAll("a[href]").forEach((link) => {
        if (restrictedPages.has(link.getAttribute("href"))) {
            link.classList.add("hidden");
        }
    });
}

function bindLogout() {
    document.querySelectorAll("[data-logout]").forEach((link) => {
        link.addEventListener("click", async (event) => {
            event.preventDefault();
            try {
                await api("/api/logout", { method: "POST" });
            } catch (_error) {
                // Clear client state even if the server session is already gone.
            }
            clearSession();
            window.location.href = "login.html";
        });
    });
}

// Generic fetch wrapper for all backend API requests.
async function api(path, options = {}) {
    const response = await fetch(path, {
        credentials: "same-origin",
        ...options
    });
    const contentType = response.headers.get("Content-Type") || "";
    const payload = response.status === 204
        ? null
        : contentType.includes("application/json")
            ? await response.json()
            : await response.text();

    if (!response.ok) {
        const messageText = typeof payload === "string" ? payload : payload.message || "Request failed";
        throw new Error(messageText);
    }

    return payload;
}

function formBody(values) {
    return new URLSearchParams(values);
}

// ---------------------------------------------------------------------
// Data loading helpers
// ---------------------------------------------------------------------

async function loadVisitors() {
    return api("/api/visitors");
}

async function loadEmployees() {
    return api("/api/employees");
}

async function loadAccessLogs() {
    return api("/api/access-logs");
}

async function loadIncidents() {
    return api("/api/incidents");
}

async function loadUsers() {
    return api("/api/users");
}

async function loadEmployeeDirectory() {
    return api("/api/employees");
}

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

async function renderVisitorTable() {
    const table = document.getElementById("visitorTable");
    if (!table) {
        return [];
    }

    const visitors = await loadVisitors();
    table.innerHTML = visitors.length
        ? visitors.map((visitor) => `
            <tr>
                <td>${visitor.name}</td>
                <td>${visitor.nationalId}</td>
                <td>${visitor.phoneNumber}</td>
                <td>${visitor.purposeOfVisit}</td>
                <td>
                    <div class="inline-actions">
                        <button type="button" class="btn btn-secondary btn-small" data-edit-visitor="${visitor.id}">Edit</button>
                        <button type="button" class="btn btn-danger btn-small" data-delete-visitor="${visitor.id}">Delete</button>
                    </div>
                </td>
            </tr>
        `).join("")
        : tableEmptyRow(5, "No visitors registered yet.");

    bindVisitorRowActions(visitors);
    return visitors;
}

async function renderAccessSelects() {
    const entrySelect = document.getElementById("visitorId");
    const exitSelect = document.getElementById("exitVisitorId");
    const employeeSelect = document.getElementById("employeeId");
    const editVisitorSelect = document.getElementById("editVisitorId");
    const editEmployeeSelect = document.getElementById("editEmployeeId");
    const [visitors, employees, logs] = await Promise.all([
        loadVisitors(),
        loadEmployees(),
        loadAccessLogs()
    ]);

    if (entrySelect) {
        entrySelect.innerHTML = `
            <option value="">Select registered visitor</option>
            ${visitors.map((visitor) => `<option value="${visitor.id}">${visitor.name} (${visitor.nationalId})</option>`).join("")}
        `;
    }

    if (employeeSelect) {
        employeeSelect.innerHTML = `
            <option value="">Select host employee</option>
            ${employees.map((employee) => `<option value="${employee.id}">${employee.name} (${employee.department})</option>`).join("")}
        `;
    }

    if (editVisitorSelect) {
        editVisitorSelect.innerHTML = `
            <option value="">Select visitor record</option>
            ${visitors.map((visitor) => `<option value="${visitor.id}">${visitor.name} (${visitor.nationalId})</option>`).join("")}
        `;
    }

    if (editEmployeeSelect) {
        editEmployeeSelect.innerHTML = `
            <option value="">Select host employee</option>
            ${employees.map((employee) => `<option value="${employee.id}">${employee.name} (${employee.department})</option>`).join("")}
        `;
    }

    if (exitSelect) {
        const activeIds = new Set(logs.filter((log) => !log.exitTime).map((log) => String(log.visitorId)));
        const activeVisitors = visitors.filter((visitor) => activeIds.has(String(visitor.id)));
        exitSelect.innerHTML = `
            <option value="">Select active visitor</option>
            ${activeVisitors.map((visitor) => `<option value="${visitor.id}">${visitor.name} (${visitor.nationalId})</option>`).join("")}
        `;
    }

    return { visitors, employees, logs };
}

async function renderAccessTable(targetId = "accessTable", editable = true) {
    const table = document.getElementById(targetId);
    if (!table) {
        return [];
    }

    const logs = await loadAccessLogs();
    table.innerHTML = logs.length
        ? logs.map((log) => `
            <tr>
                <td>${log.date}</td>
                <td>${log.visitorName}</td>
                <td>${log.host}</td>
                <td>${String(log.entryTime).slice(0, 5)}</td>
                <td>${log.exitTime ? String(log.exitTime).slice(0, 5) : "Still in building"}</td>
                <td>${log.recordedBy}</td>
                ${editable ? `
                <td>
                    <div class="inline-actions">
                        <button type="button" class="btn btn-secondary btn-small" data-edit-access="${log.id}">Edit</button>
                        <button type="button" class="btn btn-danger btn-small" data-delete-access="${log.id}">Delete</button>
                    </div>
                </td>
                ` : ""}
            </tr>
        `).join("")
        : tableEmptyRow(editable ? 7 : 6, "No access activity recorded yet.");

    if (editable) {
        bindAccessRowActions(logs);
    }
    return logs;
}

async function renderIncidentTable() {
    const table = document.getElementById("incidentTable");
    if (!table) {
        return [];
    }

    const incidents = await loadIncidents();
    incidentCache = incidents;
    return renderIncidentRows(incidents);
}

function renderIncidentRows(incidents) {
    const table = document.getElementById("incidentTable");
    if (!table) {
        return [];
    }

    table.innerHTML = incidents.length
        ? incidents.map((incident) => `
            <tr>
                <td>${incident.date}</td>
                <td>${incident.title}</td>
                <td>${incident.incidentType}</td>
                <td>${incident.location}</td>
                <td>${incident.severity}</td>
                <td>${incident.status}</td>
                <td>${incident.reportedBy}</td>
                <td>${incident.actionTaken || "-"}</td>
                <td>${incident.description}</td>
                <td>
                    <div class="inline-actions">
                        <button type="button" class="btn btn-secondary btn-small" data-edit-incident="${incident.id}">Edit</button>
                    </div>
                </td>
            </tr>
        `).join("")
        : tableEmptyRow(10, "No incidents logged yet.");

    bindIncidentRowActions(incidents);
    return incidents;
}

function filterIncidents() {
    const status = (document.getElementById("incidentFilterStatus")?.value || "").trim();
    const date = (document.getElementById("incidentFilterDate")?.value || "").trim();

    const filteredIncidents = incidentCache.filter((incident) => {
        const statusMatch = !status || incident.status === status;
        const dateMatch = !date || incident.date === date;
        return statusMatch && dateMatch;
    });

    renderIncidentRows(filteredIncidents);

    if (status || date) {
        message("incidentMessage", `Filter applied. ${filteredIncidents.length} incident(s) matched.`);
    }
}

async function renderDashboard() {
    const session = getSession();
    const [visitors, accessLogs, incidents] = await Promise.all([
        loadVisitors(),
        loadAccessLogs(),
        loadIncidents()
    ]);
    const activeVisitors = accessLogs.filter((log) => !log.exitTime);

    const sessionUser = document.getElementById("sessionUser");
    const currentDate = document.getElementById("currentDate");
    const visitorCount = document.getElementById("visitorCount");
    const openVisitsCount = document.getElementById("openVisitsCount");
    const incidentCount = document.getElementById("incidentCount");
    const activeVisitorsCount = document.getElementById("activeVisitorsCount");
    const incidentPreview = document.getElementById("incidentPreview");

    if (sessionUser) {
        sessionUser.textContent = session ? session.username : "Operations Desk";
    }
    const sessionRole = document.getElementById("sessionRole");
    if (sessionRole) {
        sessionRole.textContent = session ? session.role : "-";
    }
    if (currentDate) {
        currentDate.textContent = formatToday();
    }
    if (visitorCount) {
        visitorCount.textContent = String(visitors.length);
    }
    if (openVisitsCount) {
        openVisitsCount.textContent = String(activeVisitors.length);
    }
    if (incidentCount) {
        incidentCount.textContent = String(incidents.length);
    }
    if (activeVisitorsCount) {
        activeVisitorsCount.textContent = String(activeVisitors.length);
    }
    if (incidentPreview) {
        const recent = incidents.slice(0, 3);
        incidentPreview.innerHTML = recent.length
            ? recent.map((incident) => `
                <div class="record-item">
                    <strong>${incident.title}</strong>
                    <p>${incident.incidentType} at ${incident.location}</p>
                    <p>${incident.severity} severity, ${incident.status}</p>
                    <p>${incident.date} by ${incident.reportedBy}</p>
                </div>
            `).join("")
            : "No incidents logged yet.";
    }

    await renderAccessTable("dashboardAccessTable", false);

    const securityActions = document.querySelectorAll("[href=\"visitor-registration.html\"], [href=\"access-control.html\"], [href=\"incident-report.html\"]");
    const userActions = document.querySelectorAll("[href=\"users.html\"], [href=\"employees.html\"], [href=\"reports.html\"], [href=\"audit-history.html\"]");
    const role = normalizedRole(session ? session.role : "");

    securityActions.forEach((element) => {
        if (role === "admin") {
            element.classList.add("hidden");
        }
    });

    userActions.forEach((element) => {
        if (role !== "admin") {
            element.classList.add("hidden");
        }
    });

}

async function renderUsersTable() {
    const table = document.getElementById("usersTable");
    if (!table) {
        return [];
    }

    const users = await loadUsers();
    const session = getSession();
    table.innerHTML = users.length
        ? users.map((user) => `
            <tr>
                <td>${user.userId}</td>
                <td>${user.username}</td>
                <td>${user.role}</td>
                <td>
                    <div class="inline-actions">
                        <button type="button" class="btn btn-secondary btn-small" data-edit-user="${user.userId}">Edit</button>
                        <button type="button" class="btn btn-danger btn-small" data-delete-user="${user.userId}" ${session && session.userId === user.userId ? "disabled" : ""}>Delete</button>
                    </div>
                </td>
            </tr>
        `).join("")
        : tableEmptyRow(4, "No users found.");

    bindUserRowActions(users);
    return users;
}

async function renderEmployeesTable() {
    const table = document.getElementById("employeesTable");
    if (!table) {
        return [];
    }

    const employees = await loadEmployeeDirectory();
    table.innerHTML = employees.length
        ? employees.map((employee) => `
            <tr>
                <td>${employee.id}</td>
                <td>${employee.name}</td>
                <td>${employee.department}</td>
                <td>${employee.phoneNumber || "-"}</td>
                <td>
                    <div class="inline-actions">
                        <button type="button" class="btn btn-secondary btn-small" data-edit-employee="${employee.id}">Edit</button>
                        <button type="button" class="btn btn-danger btn-small" data-delete-employee="${employee.id}">Delete</button>
                    </div>
                </td>
            </tr>
        `).join("")
        : tableEmptyRow(5, "No employees found.");

    bindEmployeeRowActions(employees);
    return employees;
}

function renderReportsHeader(type) {
    const head = document.getElementById("reportsHead");
    if (!head) {
        return;
    }

    const headings = {
        access: ["Date", "Visitor", "Host", "Entry", "Exit", "Recorded By"],
        incidents: ["Date", "Title", "Type", "Location", "Severity", "Status", "Reported By"],
        visitors: ["Date", "Visitor", "ID / Passport", "Phone", "Purpose"],
        audit: ["Date", "Action", "Entity", "Entity ID", "User", "Details"]
    };

    head.innerHTML = `<tr>${(headings[type] || headings.access).map((label) => `<th>${label}</th>`).join("")}</tr>`;
}

// Reports share one renderer so each report type follows the same table workflow.
async function renderReportsTable(rows = [], type = "access") {
    const table = document.getElementById("reportsTable");
    if (!table) {
        return;
    }

    // One renderer supports all report types so the page can switch views without duplicating tables.
    renderReportsHeader(type);

    const renderers = {
        access: (log) => `
            <tr>
                <td>${log.date}</td>
                <td>${log.visitorName}</td>
                <td>${log.host}</td>
                <td>${String(log.entryTime).slice(0, 5)}</td>
                <td>${log.exitTime ? String(log.exitTime).slice(0, 5) : "Still in building"}</td>
                <td>${log.recordedBy}</td>
            </tr>
        `,
        incidents: (incident) => `
            <tr>
                <td>${incident.date}</td>
                <td>${incident.title}</td>
                <td>${incident.incidentType}</td>
                <td>${incident.location}</td>
                <td>${incident.severity}</td>
                <td>${incident.status}</td>
                <td>${incident.reportedBy}</td>
            </tr>
        `,
        visitors: (visitor) => `
            <tr>
                <td>${visitor.date}</td>
                <td>${visitor.name}</td>
                <td>${visitor.nationalId}</td>
                <td>${visitor.phoneNumber}</td>
                <td>${visitor.purposeOfVisit}</td>
            </tr>
        `,
        audit: (entry) => `
            <tr>
                <td>${entry.date}</td>
                <td>${entry.actionType}</td>
                <td>${entry.entityType}</td>
                <td>${entry.entityId || "-"}</td>
                <td>${entry.username}</td>
                <td>${entry.details}</td>
            </tr>
        `
    };

    const colspans = { access: 6, incidents: 7, visitors: 5, audit: 6 };

    table.innerHTML = rows.length
        ? rows.map(renderers[type] || renderers.access).join("")
        : tableEmptyRow(colspans[type] || 6, "No records found for the selected date range.");
}

async function renderAuditTable(rows = []) {
    const table = document.getElementById("auditTable");
    if (!table) {
        return;
    }

    table.innerHTML = rows.length
        ? rows.map((entry) => `
            <tr>
                <td>${entry.date}</td>
                <td>${entry.actionType}</td>
                <td>${entry.entityType}</td>
                <td>${entry.entityId || "-"}</td>
                <td>${entry.username}</td>
                <td>${entry.details}</td>
            </tr>
        `).join("")
        : tableEmptyRow(6, "No audit entries found for the selected date range.");
}

// ---------------------------------------------------------------------
// Row action bindings for edit/delete flows
// ---------------------------------------------------------------------
function bindUserRowActions(users) {
    document.querySelectorAll("[data-edit-user]").forEach((button) => {
        button.addEventListener("click", () => {
            const userId = Number(button.getAttribute("data-edit-user"));
            const user = users.find((item) => item.userId === userId);
            if (!user) {
                return;
            }

            document.getElementById("editUserId").value = String(user.userId);
            document.getElementById("editUsername").value = user.username;
            document.getElementById("editRole").value = user.role;
            document.getElementById("editPassword").value = "";
            message("userMessage", `Editing ${user.username}. Update the fields and save changes.`);
        });
    });

    document.querySelectorAll("[data-delete-user]").forEach((button) => {
        button.addEventListener("click", async () => {
            const session = getSession();
            const targetUserId = button.getAttribute("data-delete-user");

            if (!window.confirm("Delete this user account?")) {
                return;
            }

            try {
                await api("/api/users/delete", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded"
                    },
                    body: formBody({
                        targetUserId
                    })
                });

                await renderUsersTable();
                message("userMessage", "User deleted successfully.");
            } catch (error) {
                message("userMessage", error.message, true);
            }
        });
    });
}

function bindVisitorRowActions(visitors) {
    document.querySelectorAll("[data-edit-visitor]").forEach((button) => {
        button.addEventListener("click", () => {
            const visitorId = Number(button.getAttribute("data-edit-visitor"));
            const visitor = visitors.find((item) => item.id === visitorId);
            if (!visitor) {
                return;
            }

            document.getElementById("editVisitorId").value = String(visitor.id);
            document.getElementById("editVisitorName").value = visitor.name;
            document.getElementById("editVisitorNationalId").value = visitor.nationalId;
            document.getElementById("editVisitorPhoneNumber").value = visitor.phoneNumber;
            document.getElementById("editVisitorPurposeOfVisit").value = visitor.purposeOfVisit;
            message("visitorMessage", `Editing ${visitor.name}. Update the details and save changes.`);
        });
    });

    document.querySelectorAll("[data-delete-visitor]").forEach((button) => {
        button.addEventListener("click", async () => {
            const visitorId = button.getAttribute("data-delete-visitor");

            if (!window.confirm("Delete this visitor record?")) {
                return;
            }

            try {
                await api("/api/visitors/delete", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded"
                    },
                    body: formBody({
                        visitorId
                    })
                });

                await renderVisitorTable();
                message("visitorMessage", "Visitor deleted successfully.");
            } catch (error) {
                message("visitorMessage", error.message, true);
            }
        });
    });
}

function bindEmployeeRowActions(employees) {
    document.querySelectorAll("[data-edit-employee]").forEach((button) => {
        button.addEventListener("click", () => {
            const employeeId = Number(button.getAttribute("data-edit-employee"));
            const employee = employees.find((item) => item.id === employeeId);
            if (!employee) {
                return;
            }

            document.getElementById("editEmployeeId").value = String(employee.id);
            document.getElementById("editEmployeeName").value = employee.name;
            document.getElementById("editEmployeeDepartment").value = employee.department;
            document.getElementById("editEmployeePhone").value = employee.phoneNumber || "";
            message("employeeMessage", `Editing ${employee.name}. Update the fields and save changes.`);
        });
    });

    document.querySelectorAll("[data-delete-employee]").forEach((button) => {
        button.addEventListener("click", async () => {
            const employeeId = button.getAttribute("data-delete-employee");

            if (!window.confirm("Delete this employee record?")) {
                return;
            }

            try {
                await api("/api/employees/delete", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded"
                    },
                    body: formBody({
                        employeeId
                    })
                });

                await renderEmployeesTable();
                message("employeeMessage", "Employee deleted successfully.");
            } catch (error) {
                message("employeeMessage", error.message, true);
            }
        });
    });
}

// ---------------------------------------------------------------------
// Form bindings for each page
// ---------------------------------------------------------------------
function bindLogin() {
    const form = document.getElementById("loginForm");
    if (!form) {
        return;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const username = document.getElementById("username").value.trim();
        const password = document.getElementById("password").value.trim();

        if (!username || !password) {
            message("loginMessage", "Enter both username and password to continue.", true);
            return;
        }

        try {
            const session = await api("/api/login", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({ username, password })
            });

            setSession(session);
            message("loginMessage", "Sign-in successful. Redirecting...");
            window.setTimeout(() => {
                window.location.href = "dashboard.html";
            }, 300);
        } catch (error) {
            message("loginMessage", error.message, true);
        }
    });
}

function bindVisitorForm() {
    const form = document.getElementById("visitorForm");
    if (!form) {
        return;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const session = getSession();

        try {
            await api("/api/visitors", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    name: document.getElementById("name").value.trim(),
                    nationalId: document.getElementById("nationalId").value.trim(),
                    phoneNumber: document.getElementById("phoneNumber").value.trim(),
                    purposeOfVisit: document.getElementById("purposeOfVisit").value.trim()
                })
            });

            form.reset();
            await renderVisitorTable();
            message("visitorMessage", "Visitor registered successfully.");
        } catch (error) {
            message("visitorMessage", error.message, true);
        }
    });
}

function bindAccessForms() {
    const entryForm = document.getElementById("entryForm");
    const exitForm = document.getElementById("exitForm");

    if (entryForm) {
        entryForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const session = getSession();

            try {
                await api("/api/access-logs/entry", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded"
                    },
                    body: formBody({
                        visitorId: document.getElementById("visitorId").value,
                        employeeId: document.getElementById("employeeId").value
                    })
                });

                entryForm.reset();
                await renderAccessSelects();
                await renderAccessTable();
                message("accessMessage", "Entry recorded successfully.");
            } catch (error) {
                message("accessMessage", error.message, true);
            }
        });
    }

    if (exitForm) {
        exitForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const session = getSession();

            try {
                await api("/api/access-logs/exit", {
                    method: "POST",
                    headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                    },
                    body: formBody({
                        visitorId: document.getElementById("exitVisitorId").value
                    })
                });

                exitForm.reset();
                await renderAccessSelects();
                await renderAccessTable();
                message("accessMessage", "Exit recorded successfully.");
            } catch (error) {
                message("accessMessage", error.message, true);
            }
        });
    }
}

function bindAccessRowActions(logs) {
    document.querySelectorAll("[data-edit-access]").forEach((button) => {
        button.addEventListener("click", () => {
            const logId = Number(button.getAttribute("data-edit-access"));
            const log = logs.find((item) => item.id === logId);
            if (!log) {
                return;
            }

            document.getElementById("editLogId").value = String(log.id);
            document.getElementById("editVisitorId").value = String(log.visitorId);
            document.getElementById("editEmployeeId").value = String(log.employeeId);
            document.getElementById("editVisitDate").value = log.date;
            document.getElementById("editEntryTime").value = String(log.entryTime).slice(0, 5);
            document.getElementById("editExitTime").value = log.exitTime ? String(log.exitTime).slice(0, 5) : "";
            message("accessMessage", `Editing access record #${log.id}. Update the fields and save changes.`);
        });
    });

    document.querySelectorAll("[data-delete-access]").forEach((button) => {
        button.addEventListener("click", async () => {
            const logId = button.getAttribute("data-delete-access");

            if (!window.confirm("Delete this access record?")) {
                return;
            }

            try {
                await api("/api/access-logs/delete", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/x-www-form-urlencoded"
                    },
                    body: formBody({
                        logId
                    })
                });

                await renderAccessSelects();
                await renderAccessTable();
                message("accessMessage", "Access record deleted successfully.");
            } catch (error) {
                message("accessMessage", error.message, true);
            }
        });
    });
}

function bindIncidentForm() {
    const form = document.getElementById("incidentForm");
    if (!form) {
        return;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const session = getSession();

        try {
            await api("/api/incidents", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    title: document.getElementById("title").value.trim(),
                    incidentType: document.getElementById("incidentType").value,
                    location: document.getElementById("location").value.trim(),
                    severity: document.getElementById("severity").value,
                    status: document.getElementById("status").value,
                    actionTaken: document.getElementById("actionTaken").value.trim(),
                    description: document.getElementById("description").value.trim()
                })
            });

            form.reset();
            await renderIncidentTable();
            message("incidentMessage", "Incident report saved successfully.");
        } catch (error) {
            message("incidentMessage", error.message, true);
        }
    });
}

function bindIncidentRowActions(incidents) {
    document.querySelectorAll("[data-edit-incident]").forEach((button) => {
        button.addEventListener("click", () => {
            const incidentId = Number(button.getAttribute("data-edit-incident"));
            const incident = incidents.find((item) => item.id === incidentId);
            if (!incident) {
                return;
            }

            document.getElementById("editIncidentId").value = String(incident.id);
            document.getElementById("editTitle").value = incident.title;
            document.getElementById("editIncidentType").value = incident.incidentType;
            document.getElementById("editLocation").value = incident.location;
            document.getElementById("editSeverity").value = incident.severity;
            document.getElementById("editStatus").value = incident.status;
            document.getElementById("editDescription").value = incident.description;
            document.getElementById("editActionTaken").value = incident.actionTaken || "";
            message("incidentMessage", `Editing incident #${incident.id}. Update the fields and save your changes.`);
            document.getElementById("incidentEditForm").scrollIntoView({ behavior: "smooth", block: "start" });
        });
    });
}

function bindIncidentEditForm() {
    const form = document.getElementById("incidentEditForm");
    if (!form) {
        return;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();

        if (!document.getElementById("editIncidentId").value) {
            message("incidentMessage", "Select an incident from the log before saving changes.", true);
            return;
        }

        try {
            await api("/api/incidents/update", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    incidentId: document.getElementById("editIncidentId").value,
                    title: document.getElementById("editTitle").value.trim(),
                    incidentType: document.getElementById("editIncidentType").value,
                    location: document.getElementById("editLocation").value.trim(),
                    severity: document.getElementById("editSeverity").value,
                    status: document.getElementById("editStatus").value,
                    actionTaken: document.getElementById("editActionTaken").value.trim(),
                    description: document.getElementById("editDescription").value.trim()
                })
            });

            form.reset();
            document.getElementById("editIncidentId").value = "";
            await renderIncidentTable();
            message("incidentMessage", "Incident updated successfully.");
        } catch (error) {
            message("incidentMessage", error.message, true);
        }
    });
}

function bindIncidentFilters() {
    const form = document.getElementById("incidentFilterForm");
    const resetButton = document.getElementById("resetIncidentFilters");
    if (!form) {
        return;
    }

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        filterIncidents();
    });

    if (resetButton) {
        resetButton.addEventListener("click", () => {
            form.reset();
            renderIncidentRows(incidentCache);
            message("incidentMessage", "Incident filters cleared.");
        });
    }
}

function bindUserForms() {
    const createForm = document.getElementById("userCreateForm");
    const editForm = document.getElementById("userEditForm");
    if (!createForm || !editForm) {
        return;
    }

    createForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const session = getSession();

        try {
            await api("/api/users", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    username: document.getElementById("newUsername").value.trim(),
                    password: document.getElementById("newPassword").value.trim(),
                    role: document.getElementById("newRole").value
                })
            });

            createForm.reset();
            await renderUsersTable();
            message("userMessage", "User created successfully.");
        } catch (error) {
            message("userMessage", error.message, true);
        }
    });

    editForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const session = getSession();
        const targetUserId = Number(document.getElementById("editUserId").value);

        try {
            const result = await api("/api/users/update", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    targetUserId: String(targetUserId),
                    username: document.getElementById("editUsername").value.trim(),
                    password: document.getElementById("editPassword").value.trim(),
                    role: document.getElementById("editRole").value
                })
            });

            editForm.reset();
            document.getElementById("editUserId").value = "";
            await renderUsersTable();
            message("userMessage", result.message || "User updated successfully.");
            if (result.sessionRevoked && result.updatedCurrentUser && session && targetUserId === session.userId) {
                clearSession();
                window.setTimeout(() => {
                    window.location.href = "login.html";
                }, 500);
            }
        } catch (error) {
            message("userMessage", error.message, true);
        }
    });
}

function bindVisitorEditForm() {
    const form = document.getElementById("visitorEditForm");
    if (!form) {
        return;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();

        try {
            await api("/api/visitors/update", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    visitorId: document.getElementById("editVisitorId").value,
                    name: document.getElementById("editVisitorName").value.trim(),
                    nationalId: document.getElementById("editVisitorNationalId").value.trim(),
                    phoneNumber: document.getElementById("editVisitorPhoneNumber").value.trim(),
                    purposeOfVisit: document.getElementById("editVisitorPurposeOfVisit").value.trim()
                })
            });

            form.reset();
            document.getElementById("editVisitorId").value = "";
            await renderVisitorTable();
            await renderAccessSelects();
            message("visitorMessage", "Visitor updated successfully.");
        } catch (error) {
            message("visitorMessage", error.message, true);
        }
    });
}

function bindAccessEditForm() {
    const form = document.getElementById("accessEditForm");
    if (!form) {
        return;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();

        try {
            await api("/api/access-logs/update", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    logId: document.getElementById("editLogId").value,
                    visitorId: document.getElementById("editVisitorId").value,
                    employeeId: document.getElementById("editEmployeeId").value,
                    visitDate: document.getElementById("editVisitDate").value,
                    entryTime: document.getElementById("editEntryTime").value,
                    exitTime: document.getElementById("editExitTime").value
                })
            });

            form.reset();
            document.getElementById("editLogId").value = "";
            await renderAccessSelects();
            await renderAccessTable();
            message("accessMessage", "Access record updated successfully.");
        } catch (error) {
            message("accessMessage", error.message, true);
        }
    });
}

function bindEmployeeForms() {
    const createForm = document.getElementById("employeeCreateForm");
    const editForm = document.getElementById("employeeEditForm");
    if (!createForm || !editForm) {
        return;
    }

    createForm.addEventListener("submit", async (event) => {
        event.preventDefault();

        try {
            await api("/api/employees/create", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    name: document.getElementById("employeeName").value.trim(),
                    department: document.getElementById("employeeDepartment").value.trim(),
                    phoneNumber: document.getElementById("employeePhone").value.trim()
                })
            });

            createForm.reset();
            await renderEmployeesTable();
            message("employeeMessage", "Employee created successfully.");
        } catch (error) {
            message("employeeMessage", error.message, true);
        }
    });

    editForm.addEventListener("submit", async (event) => {
        event.preventDefault();

        try {
            await api("/api/employees/update", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    employeeId: document.getElementById("editEmployeeId").value,
                    name: document.getElementById("editEmployeeName").value.trim(),
                    department: document.getElementById("editEmployeeDepartment").value.trim(),
                    phoneNumber: document.getElementById("editEmployeePhone").value.trim()
                })
            });

            editForm.reset();
            document.getElementById("editEmployeeId").value = "";
            await renderEmployeesTable();
            await renderAccessSelects();
            message("employeeMessage", "Employee updated successfully.");
        } catch (error) {
            message("employeeMessage", error.message, true);
        }
    });
}

// Reports and audit screens share the same date-driven pattern, but point to different endpoints.
function bindReportForm() {
    const form = document.getElementById("reportForm");
    const exportButton = document.getElementById("exportReport");
    if (!form) {
        return;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const reportType = document.getElementById("reportType").value;
        const startDate = document.getElementById("reportStartDate").value;
        const endDate = document.getElementById("reportEndDate").value;

        if (!startDate || !endDate) {
            message("reportMessage", "Select both start date and end date.", true);
            return;
        }

        if (startDate > endDate) {
            message("reportMessage", "Start date cannot be after end date.", true);
            return;
        }

        try {
            const loaders = {
                access: loadAccessReport,
                incidents: loadIncidentReport,
                visitors: loadVisitorReport,
                audit: loadAuditReport
            };
            const rows = await (loaders[reportType] || loadAccessReport)(startDate, endDate);
            await renderReportsTable(rows, reportType);
            message("reportMessage", `Report generated successfully. ${rows.length} record(s) found.`);
        } catch (error) {
            message("reportMessage", error.message, true);
        }
    });

    if (exportButton) {
        exportButton.addEventListener("click", () => {
            const reportType = document.getElementById("reportType").value;
            const startDate = document.getElementById("reportStartDate").value;
            const endDate = document.getElementById("reportEndDate").value;

            if (!startDate || !endDate) {
                message("reportMessage", "Select both start date and end date before exporting.", true);
                return;
            }

            if (startDate > endDate) {
                message("reportMessage", "Start date cannot be after end date.", true);
                return;
            }

            window.location.href = `/api/reports/export?type=${encodeURIComponent(reportType)}&startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`;
        });
    }
}

function bindAuditForm() {
    const form = document.getElementById("auditForm");
    const exportButton = document.getElementById("exportAuditHistory");
    if (!form) {
        return;
    }

    const range = defaultAuditRange();
    document.getElementById("auditStartDate").value = range.startDate;
    document.getElementById("auditEndDate").value = range.endDate;

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const startDate = document.getElementById("auditStartDate").value;
        const endDate = document.getElementById("auditEndDate").value;

        if (!startDate || !endDate) {
            message("auditMessage", "Select both start date and end date.", true);
            return;
        }

        if (startDate > endDate) {
            message("auditMessage", "Start date cannot be after end date.", true);
            return;
        }

        try {
            const rows = await loadAuditReport(startDate, endDate);
            await renderAuditTable(rows);
            message("auditMessage", `Audit history loaded successfully. ${rows.length} record(s) found.`);
        } catch (error) {
            message("auditMessage", error.message, true);
        }
    });

    if (exportButton) {
        exportButton.addEventListener("click", () => {
            const startDate = document.getElementById("auditStartDate").value;
            const endDate = document.getElementById("auditEndDate").value;

            if (!startDate || !endDate) {
                message("auditMessage", "Select both start date and end date before exporting.", true);
                return;
            }

            window.location.href = `/api/reports/export?type=audit&startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`;
        });
    }
}

async function initializePage(page) {
    // Page-specific setup is centralized here so each HTML file only needs a data-page attribute.
    switch (page) {
        case "dashboard":
            await renderDashboard();
            break;
        case "visitor-registration":
            bindVisitorForm();
            bindVisitorEditForm();
            await renderVisitorTable();
            break;
        case "access-control":
            bindAccessForms();
            bindAccessEditForm();
            await renderAccessSelects();
            await renderAccessTable();
            break;
        case "incident-report":
            bindIncidentForm();
            bindIncidentEditForm();
            bindIncidentFilters();
            await renderIncidentTable();
            break;
        case "users":
            bindUserForms();
            await renderUsersTable();
            break;
        case "employees":
            bindEmployeeForms();
            await renderEmployeesTable();
            break;
        case "reports":
            bindReportForm();
            await renderReportsTable([], "access");
            break;
        case "audit-history":
            bindAuditForm();
            await renderAuditTable([]);
            document.getElementById("auditForm").dispatchEvent(new Event("submit"));
            break;
        case "login":
            bindLogin();
            break;
        case "index":
            window.setTimeout(() => {
                window.location.href = "login.html";
            }, 200);
            break;
        default:
            break;
    }
}

document.addEventListener("DOMContentLoaded", async () => {
    const page = document.body.dataset.page || "";

    bindLogout();

    try {
        try {
            setSession(await api("/api/session"));
        } catch (error) {
            clearSession();
            if (!/Login required/i.test(error.message)) {
                throw error;
            }
        }

        if (!protectRoute(page)) {
            return;
        }

        applyRolePermissions();
        await initializePage(page);
    } catch (error) {
        const targetId = page === "login" ? "loginMessage" : page === "visitor-registration" ? "visitorMessage" : page === "access-control" ? "accessMessage" : page === "incident-report" ? "incidentMessage" : page === "users" ? "userMessage" : page === "employees" ? "employeeMessage" : page === "reports" ? "reportMessage" : page === "audit-history" ? "auditMessage" : null;
        if (targetId) {
            message(targetId, error.message || "Failed to load data.", true);
        }
        console.error(error);
    }
});
