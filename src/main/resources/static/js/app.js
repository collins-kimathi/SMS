const SESSION_KEY = "sms-session";

function getSession() {
    const raw = localStorage.getItem(SESSION_KEY);
    if (!raw) {
        return null;
    }

    try {
        return JSON.parse(raw);
    } catch (_error) {
        return null;
    }
}

function setSession(session) {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

function clearSession() {
    localStorage.removeItem(SESSION_KEY);
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

function protectRoute(page) {
    const publicPages = new Set(["login", "index"]);
    const session = getSession();

    if (!publicPages.has(page) && !session) {
        window.location.href = "login.html";
        return false;
    }

    if (page === "login" && session) {
        window.location.href = "dashboard.html";
        return false;
    }

    return true;
}

function bindLogout() {
    document.querySelectorAll("[data-logout]").forEach((link) => {
        link.addEventListener("click", (event) => {
            event.preventDefault();
            clearSession();
            window.location.href = "login.html";
        });
    });
}

async function api(path, options = {}) {
    const response = await fetch(path, options);
    const contentType = response.headers.get("Content-Type") || "";
    const payload = contentType.includes("application/json")
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
                        employeeId: document.getElementById("employeeId").value,
                        userId: session ? String(session.userId) : ""
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
                    description: document.getElementById("description").value.trim(),
                    userId: session ? String(session.userId) : ""
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

    if (!protectRoute(page)) {
        return;
    }

    bindLogout();

    try {
        await initializePage(page);
    } catch (error) {
        const targetId = page === "login" ? "loginMessage" : page === "visitor-registration" ? "visitorMessage" : page === "access-control" ? "accessMessage" : page === "incident-report" ? "incidentMessage" : null;
        if (targetId) {
            message(targetId, error.message || "Failed to load data.", true);
        }
        console.error(error);
    }
});
