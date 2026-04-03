let currentSession = null;

function getSession() {
    return currentSession;
}

function setSession(session) {
    currentSession = session;
}

function clearSession() {
    currentSession = null;
}

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
        admin: new Set(["dashboard", "users", "reports"]),
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

    if (page === "login" && session) {
        window.location.href = "dashboard.html";
        return false;
    }

    if (session && !canAccessPage(page, session)) {
        window.location.href = "dashboard.html";
        return false;
    }

    return true;
}

function applyRolePermissions() {
    const session = getSession();
    if (!session) {
        return;
    }

    const role = normalizedRole(session.role);
    const restrictedPages = role === "admin"
        ? new Set(["visitor-registration.html", "access-control.html", "incident-report.html"])
        : new Set(["users.html", "reports.html"]);

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

async function loadAccessReport(startDate, endDate) {
    return api(`/api/reports/access?startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}`);
}

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
            </tr>
        `).join("")
        : tableEmptyRow(4, "No visitors registered yet.");

    return visitors;
}

async function renderAccessSelects() {
    const entrySelect = document.getElementById("visitorId");
    const exitSelect = document.getElementById("exitVisitorId");
    const employeeSelect = document.getElementById("employeeId");
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

async function renderAccessTable(targetId = "accessTable") {
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
            </tr>
        `).join("")
        : tableEmptyRow(6, "No access activity recorded yet.");

    return logs;
}

async function renderIncidentTable() {
    const table = document.getElementById("incidentTable");
    if (!table) {
        return [];
    }

    const incidents = await loadIncidents();
    table.innerHTML = incidents.length
        ? incidents.map((incident) => `
            <tr>
                <td>${incident.date}</td>
                <td>${incident.title}</td>
                <td>${incident.severity}</td>
                <td>${incident.reportedBy}</td>
                <td>${incident.description}</td>
            </tr>
        `).join("")
        : tableEmptyRow(5, "No incidents logged yet.");

    return incidents;
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
                    <p>${incident.severity} severity</p>
                    <p>${incident.date} by ${incident.reportedBy}</p>
                </div>
            `).join("")
            : "No incidents logged yet.";
    }

    await renderAccessTable("dashboardAccessTable");

    const securityActions = document.querySelectorAll("[href=\"visitor-registration.html\"], [href=\"access-control.html\"], [href=\"incident-report.html\"]");
    const userActions = document.querySelectorAll("[href=\"users.html\"], [href=\"reports.html\"]");
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

async function renderReportsTable(rows = []) {
    const table = document.getElementById("reportsTable");
    if (!table) {
        return;
    }

    table.innerHTML = rows.length
        ? rows.map((log) => `
            <tr>
                <td>${log.date}</td>
                <td>${log.visitorName}</td>
                <td>${log.host}</td>
                <td>${String(log.entryTime).slice(0, 5)}</td>
                <td>${log.exitTime ? String(log.exitTime).slice(0, 5) : "Still in building"}</td>
                <td>${log.recordedBy}</td>
            </tr>
        `).join("")
        : tableEmptyRow(6, "No records found for the selected date range.");
}

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
                    severity: document.getElementById("severity").value,
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

        try {
            await api("/api/users/update", {
                method: "POST",
                headers: {
                    "Content-Type": "application/x-www-form-urlencoded"
                },
                body: formBody({
                    targetUserId: document.getElementById("editUserId").value,
                    username: document.getElementById("editUsername").value.trim(),
                    password: document.getElementById("editPassword").value.trim(),
                    role: document.getElementById("editRole").value
                })
            });

            editForm.reset();
            document.getElementById("editUserId").value = "";
            await renderUsersTable();
            message("userMessage", "User updated successfully.");
        } catch (error) {
            message("userMessage", error.message, true);
        }
    });
}

function bindReportForm() {
    const form = document.getElementById("reportForm");
    if (!form) {
        return;
    }

    form.addEventListener("submit", async (event) => {
        event.preventDefault();
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
            const rows = await loadAccessReport(startDate, endDate);
            await renderReportsTable(rows);
            message("reportMessage", `Report generated successfully. ${rows.length} record(s) found.`);
        } catch (error) {
            message("reportMessage", error.message, true);
        }
    });
}

async function initializePage(page) {
    switch (page) {
        case "dashboard":
            await renderDashboard();
            break;
        case "visitor-registration":
            bindVisitorForm();
            await renderVisitorTable();
            break;
        case "access-control":
            bindAccessForms();
            await renderAccessSelects();
            await renderAccessTable();
            break;
        case "incident-report":
            bindIncidentForm();
            await renderIncidentTable();
            break;
        case "users":
            bindUserForms();
            await renderUsersTable();
            break;
        case "reports":
            bindReportForm();
            await renderReportsTable([]);
            break;
        case "login":
            bindLogin();
            break;
        case "index":
            window.setTimeout(() => {
                window.location.href = getSession() ? "dashboard.html" : "login.html";
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
        const targetId = page === "login" ? "loginMessage" : page === "visitor-registration" ? "visitorMessage" : page === "access-control" ? "accessMessage" : page === "incident-report" ? "incidentMessage" : page === "users" ? "userMessage" : page === "reports" ? "reportMessage" : null;
        if (targetId) {
            message(targetId, error.message || "Failed to load data.", true);
        }
        console.error(error);
    }
});
