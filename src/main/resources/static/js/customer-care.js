(() => {
    "use strict";

    const $ = (id) => document.getElementById(id);

    const safeOn = (id, event, handler, options) => {
        const el = typeof id === "string" ? $(id) : id;
        if (el && typeof el.addEventListener === "function") {
            el.addEventListener(event, handler, options);
            return el;
        }
        return null;
    };

    const state = {
        branchId: null,
        careRole: "ADMIN",
        isAdmin: false,
        branchSelectionAllowed: true,
        currentStaffId: null,
        activeTab: "ACTIONABLE",
        viewMode: "LIST",
        dueFilter: "ALL",
        doctorResponseFilter: "ALL",
        myTasksOnly: false,
        page: 0,
        pageSize: 10,
        totalPages: 0,

        calMode: "week",
        calRefDate: getVietnamTodayYmd(),
        calendarTasks: [],

        notifications: [],
        unreadNotifCount: 0,

        wsClient: null,
        wsConnected: false,
        wsReconnectAttempt: 0,
        wsReconnectTimer: null,
        wsBranchSub: null,
        wsUserSub: null,
        wsSyncDebounceTimer: null,
        lastToastAt: 0,

        lookups: {
            staffList: [],
            doctorList: [],
            patients: [],
            appointments: [],
            visits: [],
            prescriptions: []
        },

        currentTaskDetail: null,
        tasksById: {},
        tasksAbortController: null,
        calendarAbortController: null,
        keywordDebounceTimer: null
    };

    const TAB_META = {
        ACTIONABLE: {
            title: "Công việc CSKH cần xử lý (SCR-44.1)",
            subtitle: "Ưu tiên theo hạn xử lý (Quá hạn -> Hôm nay -> Sắp tới) gồm công việc Chờ liên hệ và Đang chăm sóc / Hẹn gọi lại."
        },
        ESCALATED: {
            title: "Danh sách ca Chuyển Bác sĩ hỗ trợ chuyên môn (SCR-44.5)",
            subtitle: "Theo dõi các ca cần Bác sĩ tư vấn triệu chứng/chỉ định và phân biệt ca đang chờ phản hồi với ca đã có phản hồi."
        },
        COMPLETED: {
            title: "Công việc CSKH đã hoàn thành",
            subtitle: "Lưu trữ toàn bộ công việc CSKH đã liên hệ xong và đóng hồ sơ chăm sóc."
        },
        CANCELLED: {
            title: "Công việc CSKH đã hủy",
            subtitle: "Danh sách công việc CSKH đã hủy không tiếp tục thực hiện."
        },
        HISTORY: {
            title: "Lịch sử Chăm sóc Khách hàng (SCR-44.6)",
            subtitle: "Tra cứu timeline tương tác và các công việc CSKH của từng bệnh nhân."
        }
    };

    function getVietnamTodayYmd() {
        try {
            return new Intl.DateTimeFormat("en-CA", {
                timeZone: "Asia/Ho_Chi_Minh",
                year: "numeric",
                month: "2-digit",
                day: "2-digit"
            }).format(new Date());
        } catch {
            return new Date().toISOString().slice(0, 10);
        }
    }

    function parseYmdUtc(ymd) {
        const parts = String(ymd || "").split("-").map(Number);
        if (parts.length !== 3 || parts.some(isNaN)) {
            const today = getVietnamTodayYmd().split("-").map(Number);
            return new Date(Date.UTC(today[0], today[1] - 1, today[2]));
        }
        return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
    }

    function formatUtcDateYmd(dateObj) {
        const y = dateObj.getUTCFullYear();
        const m = String(dateObj.getUTCMonth() + 1).padStart(2, "0");
        const d = String(dateObj.getUTCDate()).padStart(2, "0");
        return `${y}-${m}-${d}`;
    }

    function addDaysYmd(ymd, deltaDays) {
        const d = parseYmdUtc(ymd);
        d.setUTCDate(d.getUTCDate() + deltaDays);
        return formatUtcDateYmd(d);
    }

    function formatYmdVi(ymd) {
        if (!ymd || !ymd.includes("-")) return ymd || "";
        const [y, m, d] = ymd.split("-");
        return `${d}/${m}/${y}`;
    }

    const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, ch => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    }[ch]));

    function formatDateTime(value) {
        if (!value) return "—";
        return String(value).replace("T", " ").slice(0, 16);
    }

    function showMessage(text, type = "info") {
        const el = $("message");
        if (!el) return;
        el.textContent = text;
        el.className = `notice notice-${type}`;
        el.hidden = !text;
    }

    async function request(url, options = {}) {
        const response = await fetch(url, {
            credentials: "same-origin",
            headers: { "Content-Type": "application/json", ...(options.headers || {}) },
            ...options
        });
        const raw = await response.text();
        let body = null;
        try { body = raw ? JSON.parse(raw) : null; } catch { body = raw; }
        if (!response.ok) {
            const fieldErr = body?.errors ? Object.values(body.errors).join(" ") : "";
            const message = fieldErr || body?.message || body?.detail || (typeof body === "string" ? body : null)
                || `Yêu cầu thất bại (${response.status}).`;
            if (response.status === 401) throw new Error("Phiên đăng nhập hết hạn. Vui lòng đăng nhập lại.");
            throw new Error(message);
        }
        return body;
    }

    // =========================================================================
    // 1. KPI SUMMARY CARDS (SCR-44.1)
    // =========================================================================

    function setKpiLoading() {
        ["kpiCardTotal", "kpiCardDue", "kpiCardToday", "kpiCardEscalated"].forEach(id => {
            const el = $(id);
            if (el) {
                el.classList.add("is-loading");
                el.classList.remove("is-error");
            }
        });
        if ($("statTotalTasks")) $("statTotalTasks").textContent = "—";
        if ($("statDueTasks")) $("statDueTasks").textContent = "—";
        if ($("statTodayTasks")) $("statTodayTasks").textContent = "—";
        if ($("statEscalatedTasks")) $("statEscalatedTasks").textContent = "—";
        if ($("statTotalTasksSub")) $("statTotalTasksSub").textContent = "Đang tải dữ liệu...";
        if ($("statDueTasksSub")) $("statDueTasksSub").textContent = "Đang kiểm tra quá hạn...";
        if ($("statTodayTasksSub")) $("statTodayTasksSub").textContent = "Đang kiểm tra lịch hôm nay...";
        if ($("statEscalatedTasksSub")) $("statEscalatedTasksSub").textContent = "Đang kiểm tra ca chuyển BS...";
    }

    function setKpiError(msg = "Không thể tải số liệu") {
        ["kpiCardTotal", "kpiCardDue", "kpiCardToday", "kpiCardEscalated"].forEach(id => {
            const el = $(id);
            if (el) {
                el.classList.remove("is-loading");
                el.classList.add("is-error");
            }
        });
        if ($("statTotalTasks")) $("statTotalTasks").textContent = "—";
        if ($("statDueTasks")) $("statDueTasks").textContent = "—";
        if ($("statTodayTasks")) $("statTodayTasks").textContent = "—";
        if ($("statEscalatedTasks")) $("statEscalatedTasks").textContent = "—";
        if ($("statTotalTasksSub")) $("statTotalTasksSub").textContent = msg;
        if ($("statDueTasksSub")) $("statDueTasksSub").textContent = msg;
        if ($("statTodayTasksSub")) $("statTodayTasksSub").textContent = msg;
        if ($("statEscalatedTasksSub")) $("statEscalatedTasksSub").textContent = msg;
    }

    async function loadSummary() {
        setKpiLoading();
        try {
            const params = new URLSearchParams();
            if (state.branchId) params.set("branchId", state.branchId);
            const sum = await request(`/api/customer-care/summary?${params}`);

            ["kpiCardTotal", "kpiCardDue", "kpiCardToday", "kpiCardEscalated"].forEach(id => {
                const el = $(id);
                if (el) el.classList.remove("is-loading", "is-error");
            });

            const openAll = Number(sum?.openAllCount ?? sum?.activeTasks ?? 0);
            const active = Number(sum?.activeTasks ?? 0);
            const upcoming = Number(sum?.actionableUpcomingCount ?? sum?.upcomingTasks ?? 0);
            const overdue = Number(sum?.actionableOverdueCount ?? sum?.overdueTasks ?? 0);
            const todayCount = Number(sum?.actionableTodayCount ?? sum?.dueTodayTasks ?? 0);
            const retryPending = Number(sum?.retryPendingCount ?? 0);
            const escalated = Number(sum?.escalatedTasks ?? 0);
            const waitingDoc = Number(sum?.waitingDoctorResponseCount ?? 0);
            const docResponded = Number(sum?.doctorRespondedCount ?? 0);

            if ($("statTotalTasks")) $("statTotalTasks").textContent = openAll.toLocaleString("vi-VN");
            if ($("statTotalTasksSub")) {
                $("statTotalTasksSub").textContent = openAll > 0
                    ? `${active} cần xử lý · ${escalated} chờ BS · ${upcoming} sắp tới`
                    : "Không có công việc CSKH đang mở";
            }

            if ($("statDueTasks")) $("statDueTasks").textContent = overdue.toLocaleString("vi-VN");
            if ($("statDueTasksSub")) {
                $("statDueTasksSub").textContent = overdue > 0
                    ? `${overdue} ca quá hạn · ${retryPending} ca hẹn gọi lại`
                    : (retryPending > 0 ? `${retryPending} ca đang hẹn gọi lại` : "Không có công việc quá hạn");
            }

            if ($("statTodayTasks")) $("statTodayTasks").textContent = todayCount.toLocaleString("vi-VN");
            if ($("statTodayTasksSub")) {
                $("statTodayTasksSub").textContent = todayCount > 0
                    ? `${todayCount} công việc đến hạn trong ngày hôm nay`
                    : "Chưa có công việc đến hạn hôm nay";
            }

            if ($("statEscalatedTasks")) $("statEscalatedTasks").textContent = escalated.toLocaleString("vi-VN");
            if ($("statEscalatedTasksSub")) {
                $("statEscalatedTasksSub").textContent = escalated > 0
                    ? `${waitingDoc} chờ BS phản hồi · ${docResponded} đã phản hồi`
                    : "Không có ca chờ Bác sĩ xử lý";
            }

            const groupCounts = sum?.groupCounts || {};
            Object.entries(groupCounts).forEach(([grp, cnt]) => {
                const badgeEl = $(`badge_${grp}`);
                if (badgeEl) badgeEl.textContent = Number(cnt || 0);
            });
            if ($("badge_MY_TASKS") && groupCounts.MY_TASKS == null) {
                $("badge_MY_TASKS").textContent = Number(sum?.myTasksCount ?? 0);
            }
        } catch {
            setKpiError("Không tải được thống kê CSKH");
        }
    }

    // =========================================================================
    // 2. LOAD CHI NHÁNH & DANH MỤC TRA CỨU (LOOKUPS)
    // =========================================================================

    async function loadBranchesAndInit() {
        const branchSelect = $("branchId");
        setKpiLoading();
        try {
            const data = await request("/api/customer-care/branches");
            const branches = Array.isArray(data?.branches) ? data.branches : [];
            state.careRole = data?.careRole || "ADMIN";
            state.isAdmin = Boolean(data?.isAdmin);
            state.branchSelectionAllowed = Boolean(data?.branchSelectionAllowed);
            state.currentStaffId = data?.currentStaffId || null;

            branchSelect.innerHTML = "";
            if (state.isAdmin) {
                const allOpt = document.createElement("option");
                allOpt.value = "";
                allOpt.textContent = "Tất cả cơ sở / chi nhánh";
                branchSelect.appendChild(allOpt);
            }

            branches.forEach(b => {
                const opt = document.createElement("option");
                opt.value = b.branchId;
                opt.textContent = `${b.branchCode ? b.branchCode + " - " : ""}${b.branchName}`;
                branchSelect.appendChild(opt);
            });

            if (branches.length > 0) {
                branchSelect.value = String(branches[0].branchId);
                state.branchId = Number(branches[0].branchId);
            } else {
                state.branchId = null;
            }

            branchSelect.disabled = !state.branchSelectionAllowed;
            if (!state.branchSelectionAllowed) {
                branchSelect.title = "Bạn chỉ được xem và tiếp nhận chăm sóc bệnh nhân tại cơ sở đang công tác";
            }

            if ($("histBranchFilter")) {
                $("histBranchFilter").innerHTML = '<option value="">Tất cả cơ sở</option>' +
                    branches.map(b => `<option value="${b.branchId}">${escapeHtml(b.branchCode ? b.branchCode + " - " : "")}${escapeHtml(b.branchName)}</option>`).join("");
            }

            await refreshAllData();
            await loadNotifications();
            initWebSocket();

            // Mở trực tiếp task nếu đi từ Notification URL ?taskId=...
            const urlTaskId = new URLSearchParams(window.location.search).get("taskId");
            if (urlTaskId && /^\d+$/.test(urlTaskId)) {
                await openInteractionModal(Number(urlTaskId));
            }
        } catch (err) {
            branchSelect.innerHTML = '<option value="">Không tải được chi nhánh</option>';
            setKpiError("Lỗi kết nối máy chủ");
            showMessage(err.message || "Không thể tải danh sách chi nhánh.", "error");
        }
    }

    async function loadLookups() {
        try {
            const params = new URLSearchParams();
            if (state.branchId) params.set("branchId", state.branchId);
            const data = await request(`/api/customer-care/lookups?${params}`);

            state.lookups.staffList = Array.isArray(data?.staffList) ? data.staffList : [];
            state.lookups.doctorList = Array.isArray(data?.doctorList) ? data.doctorList : [];
            state.lookups.patients = Array.isArray(data?.patients) ? data.patients : [];
            state.lookups.appointments = Array.isArray(data?.appointments) ? data.appointments : [];
            state.lookups.visits = Array.isArray(data?.visits) ? data.visits : [];
            state.lookups.prescriptions = Array.isArray(data?.prescriptions) ? data.prescriptions : [];

            populateLookupSelects();
        } catch {
            // ignore lookup error
        }
    }

    function populateLookupSelects() {
        const staffOpts = state.lookups.staffList.map(s =>
            `<option value="${escapeHtml(s.staffId)}">${escapeHtml(s.staffCode)} - ${escapeHtml(s.fullName)} (${escapeHtml(s.positionTitle || "Nhân viên")})</option>`
        ).join("");

        const doctorOpts = state.lookups.doctorList.map(d =>
            `<option value="${escapeHtml(d.staffId)}">${escapeHtml(d.staffCode)} - ${escapeHtml(d.fullName)} (${escapeHtml(d.positionTitle || "Bác sĩ")})</option>`
        ).join("");

        const patientOpts = state.lookups.patients.map(p =>
            `<option value="${escapeHtml(p.profileId)}">${escapeHtml(p.patientCode)} - ${escapeHtml(p.fullName)} (${escapeHtml(p.phoneNumber)})</option>`
        ).join("");

        if ($("filterStaffId")) {
            const cur = $("filterStaffId").value;
            $("filterStaffId").innerHTML = '<option value="">Tất cả nhân viên</option>' + staffOpts;
            $("filterStaffId").value = cur;
        }
        if ($("filterDoctorId")) {
            const curDoc = $("filterDoctorId").value;
            $("filterDoctorId").innerHTML = '<option value="">Tất cả bác sĩ</option>' + doctorOpts;
            $("filterDoctorId").value = curDoc;
        }
        if ($("calFilterStaff")) {
            const curCalS = $("calFilterStaff").value;
            $("calFilterStaff").innerHTML = '<option value="">Tất cả nhân viên</option>' + staffOpts;
            $("calFilterStaff").value = curCalS;
        }
        if ($("calFilterDoctor")) {
            const curCalD = $("calFilterDoctor").value;
            $("calFilterDoctor").innerHTML = '<option value="">Tất cả bác sĩ</option>' + doctorOpts;
            $("calFilterDoctor").value = curCalD;
        }
        if ($("histStaffFilter")) {
            const curHStaff = $("histStaffFilter").value;
            $("histStaffFilter").innerHTML = '<option value="">Tất cả nhân viên</option>' + staffOpts;
            $("histStaffFilter").value = curHStaff;
        }
        if ($("histDoctorFilter")) {
            const curHDoc = $("histDoctorFilter").value;
            $("histDoctorFilter").innerHTML = '<option value="">Tất cả bác sĩ</option>' + doctorOpts;
            $("histDoctorFilter").value = curHDoc;
        }
        if ($("formAssignedStaffId")) {
            $("formAssignedStaffId").innerHTML = '<option value="">-- Giao cho nhân viên --</option>' + staffOpts;
        }
        if ($("escDoctorId")) {
            $("escDoctorId").innerHTML = '<option value="">-- Chọn Bác sĩ tiếp nhận --</option>' + doctorOpts;
        }
        if ($("formProfileId")) {
            $("formProfileId").innerHTML = '<option value="">-- Chọn bệnh nhân --</option>' + patientOpts;
        }
        if ($("historyPatientSelect")) {
            const curHist = $("historyPatientSelect").value;
            $("historyPatientSelect").innerHTML = '<option value="">-- Chọn bệnh nhân --</option>' + patientOpts;
            if (curHist) $("historyPatientSelect").value = curHist;
        }

        populatePatientLinkedRecords($("formProfileId")?.value || "");
    }

    function populatePatientLinkedRecords(profileId) {
        const pid = Number(profileId) || null;

        const visits = pid
            ? state.lookups.visits.filter(v => Number(v.profileId) === pid)
            : state.lookups.visits;
        if ($("formVisitId")) {
            $("formVisitId").innerHTML = '<option value="">-- Không liên kết ca khám --</option>' +
                visits.map(v => `<option value="${escapeHtml(v.visitId)}">${escapeHtml(v.visitCode)} (${escapeHtml(formatDateTime(v.checkInAt))}) - ${escapeHtml(v.patientName || "")}${v.status ? ` [${escapeHtml(v.status)}]` : ""}</option>`).join("");
        }

        const prescriptions = pid
            ? state.lookups.prescriptions.filter(pr => Number(pr.profileId) === pid)
            : state.lookups.prescriptions;
        if ($("formPrescriptionId")) {
            $("formPrescriptionId").innerHTML = '<option value="">-- Không liên kết đơn thuốc --</option>' +
                prescriptions.map(pr => `<option value="${escapeHtml(pr.prescriptionId)}">${escapeHtml(pr.prescriptionCode)} - ${escapeHtml(pr.diagnosisText || "")}</option>`).join("");
        }

        const appointments = pid
            ? state.lookups.appointments.filter(a => Number(a.profileId) === pid)
            : state.lookups.appointments;
        if ($("formAppointmentId")) {
            $("formAppointmentId").innerHTML = '<option value="">-- Không liên kết lịch hẹn --</option>' +
                appointments.map(a => `<option value="${escapeHtml(a.appointmentId)}">${escapeHtml(a.appointmentCode)} (Ngày ${escapeHtml(a.appointmentDate)}) - ${escapeHtml(a.serviceName || "Khám nha khoa")}</option>`).join("");
        }

        // Nếu chỉ có 1 ca khám của bệnh nhân đó, tự động gợi ý chọn và hiển thị preview khám lâm sàng
        if (pid && visits.length > 0 && $("formVisitId") && !$("formVisitId").value) {
            $("formVisitId").value = String(visits[0].visitId);
            if (visits[0].prescriptionId && $("formPrescriptionId") && !$("formPrescriptionId").value) {
                $("formPrescriptionId").value = String(visits[0].prescriptionId);
            }
            previewSelectedVisitExamination(visits[0].visitId);
        } else {
            previewSelectedVisitExamination($("formVisitId")?.value || "");
        }
    }

    async function previewSelectedVisitExamination(visitId) {
        const previewEl = $("formVisitExamPreview");
        if (!previewEl) return;
        const vid = Number(visitId) || null;
        if (!vid) {
            previewEl.hidden = true;
            previewEl.innerHTML = "";
            return;
        }

        previewEl.hidden = false;
        previewEl.innerHTML = "Đang tải thông tin chẩn đoán & lời dặn từ hồ sơ khám...";
        try {
            const exam = await request(`/api/customer-care/visits/${vid}/examination-summary`);
            const diagText = exam.diagnosisSummary || exam.prescriptionDiagnosis || "Chưa cập nhật chẩn đoán";
            const instrText = exam.treatmentInstructions || exam.prescriptionAdvice || exam.visitNotes || "Chưa có lời dặn sau điều trị";
            const meds = Array.isArray(exam.prescriptionItems) && exam.prescriptionItems.length
                ? exam.prescriptionItems.map(i => `${i.drugName} (${i.dosage || ""})`).join(", ")
                : null;
            const detailUrl = exam.examinationDetailUrl || `/doctor/examination/${vid}/diagnoses#tab-instructions`;

            previewEl.innerHTML = `
                <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:6px;">
                    <strong>Hồ sơ Ca khám ${escapeHtml(exam.visitCode || "#" + vid)} (${escapeHtml(exam.visitStatus || "—")}) · BS: ${escapeHtml(exam.doctor?.fullName || "—")}</strong>
                    <a href="${escapeHtml(detailUrl)}" target="_blank" rel="noopener" class="button button-secondary button-sm">
                        Xem Chẩn đoán &amp; Lời dặn
                    </a>
                </div>
                <div>• <strong>Chẩn đoán:</strong> ${escapeHtml(diagText)}</div>
                <div style="margin-top:3px;">• <strong>Lời dặn / Hướng dẫn sau điều trị:</strong> <span style="color:#0369a1;font-weight:600;">${escapeHtml(instrText)}</span></div>
                ${meds ? `<div style="margin-top:3px;">• <strong>Đơn thuốc:</strong> ${escapeHtml(meds)}</div>` : ""}
                <div style="margin-top:6px;">
                    <button type="button" class="button button-secondary button-sm" id="copyVisitInstructionsToNotesBtn">
                        Chèn chẩn đoán &amp; lời dặn vào ghi chú CSKH
                    </button>
                </div>
            `;
            const copyBtn = $("copyVisitInstructionsToNotesBtn");
            if (copyBtn) {
                copyBtn.addEventListener("click", () => {
                    const notesEl = $("formNotes");
                    if (notesEl) {
                        notesEl.value = `Hỏi thăm sau điều trị ca khám ${exam.visitCode || "#" + vid} — Chẩn đoán: ${diagText}. Lời dặn bác sĩ: ${instrText}`.slice(0, 1000);
                    }
                });
            }
        } catch {
            previewEl.hidden = true;
        }
    }

    async function refreshAllData() {
        await Promise.all([
            loadLookups(),
            loadSummary(),
            state.activeTab === "HISTORY"
                ? loadGeneralHistory(generalHistoryState.page)
                : (state.viewMode === "CALENDAR" ? loadCalendar() : loadTasks(state.page))
        ]);
    }

    // =========================================================================
    // 3. DANH SÁCH CÔNG VIỆC CSKH & BỘ LỌC PHÂN NHÓM (SCR-44.1, SCR-44.2, SCR-44.5)
    // =========================================================================

    function getCareGroupBadgeClass(careGroup) {
        switch (careGroup) {
            case "POST_TREATMENT_FOLLOWUP": return "badge-group-post";
            case "MEDICATION_REMINDER": return "badge-group-med";
            case "APPOINTMENT_REMINDER": return "badge-group-appt";
            case "INSTALLMENT_REMINDER": return "badge-group-installment";
            case "NO_SHOW_RECALL":
            case "NO_SERVICE_FOLLOWUP": return "badge-group-noshow";
            case "FEEDBACK_COMPLAINT": return "badge-group-feedback";
            default: return "badge-group-post";
        }
    }

    function getStatusBadgeClass(status) {
        switch (status) {
            case "PENDING": return "status-pending";
            case "IN_PROGRESS": return "status-progress";
            case "ESCALATED_TO_DOCTOR": return "status-escalated";
            case "COMPLETED": return "status-completed";
            case "CANCELLED": return "status-cancelled";
            default: return "status-pending";
        }
    }

    function formatChannelBadge(channel, result) {
        if (!channel && !result) {
            return '<span style="color:#94a3b8;font-size:12px;">Chưa có liên hệ</span>';
        }
        const chMap = {
            PHONE_CALL: "Gọi điện",
            ZALO: "Zalo",
            SMS: "SMS",
            IN_PERSON: "Trực tiếp"
        };
        const resMap = {
            CONNECTED: { label: "Đã kết nối", cls: "status-completed" },
            BUSY: { label: "Máy bận", cls: "status-progress" },
            NO_ANSWER: { label: "Không nghe máy", cls: "status-progress" },
            SCHEDULED_APPOINTMENT: { label: "Đã hẹn lịch", cls: " badge-group-appt" }
        };
        const rInfo = resMap[result] || { label: result || "—", cls: "status-pending" };
        return `
            <div><strong>${escapeHtml(chMap[channel] || channel)}</strong></div>
            <span class="badge ${rInfo.cls}" style="margin-top:3px;">${escapeHtml(rInfo.label)}</span>
        `;
    }

    // =========================================================================
    // VÒNG ĐỜI CÔNG VIỆC: COMPLETED / CANCELLED = ĐÃ ĐÓNG (KHÓA XỬ LÝ)
    // =========================================================================
    function isTaskClosed(t) {
        return !!t && (t.status === "COMPLETED" || t.status === "CANCELLED");
    }

    function canReopenTask(t) {
        return !!t && t.status === "COMPLETED" && state.careRole === "ADMIN";
    }

    function statusLabelOf(status) {
        return ({
            PENDING: "Chờ liên hệ",
            IN_PROGRESS: "Đang chăm sóc",
            ESCALATED_TO_DOCTOR: "Chờ Bác sĩ",
            COMPLETED: "Đã hoàn thành",
            CANCELLED: "Đã hủy"
        })[status] || status || "";
    }

    // =========================================================================
    // 3. VERTICAL CONNECTED TIMELINE CHUYÊN NGHIỆP & DRAWER LỊCH SỬ BỆNH NHÂN
    // =========================================================================
    function getChannelIcon(channel) {
        switch (channel) {
            case "PHONE_CALL": return "bi-telephone";
            case "ZALO": return "bi-chat-text";
            case "SMS": return "bi-chat-dots";
            case "IN_PERSON": return "bi-people";
            default: return "bi-chat-left";
        }
    }

    function classifyInteractionKind(i) {
        const notes = String(i?.notes || "");
        if (notes.startsWith("[Chuyển BS")) return { label: "Chuyển Bác sĩ", cls: "res-escalated", nodeCls: "node-escalated", icon: "bi-arrow-up-right-circle" };
        if (notes.startsWith("[Phản hồi BS")) return { label: "Phản hồi Bác sĩ", cls: "res-doctor", nodeCls: "node-doctor", icon: "bi-chat-dots" };
        if (notes.startsWith("[Mở lại công việc")) return { label: "Mở lại công việc", cls: "res-reopened", nodeCls: "node-reopened", icon: "bi-arrow-counterclockwise" };
        return null;
    }

    function getNodeClassFromResult(result) {
        if (result === "CONNECTED" || result === "SCHEDULED_APPOINTMENT") return "node-connected";
        if (result === "BUSY" || result === "NO_ANSWER") return "node-busy";
        return "node-pending";
    }

    function getCardClassFromResult(result) {
        if (result === "CONNECTED") return "res-connected";
        if (result === "SCHEDULED_APPOINTMENT") return "res-scheduled";
        if (result === "BUSY") return "res-busy";
        if (result === "NO_ANSWER") return "res-noanswer";
        return "";
    }

    function getResultBadge(result, label) {
        const safeLabel = label || result || "—";
        if (result === "CONNECTED" || result === "SCHEDULED_APPOINTMENT") {
            return `<span class="badge bg-success-subtle text-success border border-success-subtle fw-bold">${escapeHtml(safeLabel)}</span>`;
        }
        if (result === "BUSY" || result === "NO_ANSWER") {
            return `<span class="badge bg-warning-subtle text-warning-emphasis border border-warning-subtle fw-bold">${escapeHtml(safeLabel)}</span>`;
        }
        return `<span class="badge bg-secondary-subtle text-secondary border border-secondary-subtle fw-bold">${escapeHtml(safeLabel)}</span>`;
    }

    /**
     * Render Vertical Connected Timeline chuyên nghiệp:
     * - Đường nối dọc (cc-vtl-line) và mũi tên chỉ hướng (cc-vtl-arrow) từ lần mới nhất đến cũ hơn.
     * - Marker node tròn (cc-vtl-node) hiển thị số thứ tự Lần #X hoặc icon nghiệp vụ.
     * - Card có viền màu và màu nền theo kết quả tương tác (res-connected, res-busy, res-escalated...).
     * - Nút Xem chi tiết / Thu gọn được style Bootstrap chuẩn (cc-vtl-toggle-btn).
     * - Mở rộng độc lập từng card khi bấm, không mở toàn bộ card cùng lúc.
     */
    function renderVerticalConnectedTimeline(interactions, options = {}) {
        const list = Array.isArray(interactions) ? interactions : [];
        if (!list.length) {
            return `<div class="hist-empty-state"><i class="bi bi-inbox d-block mb-2"></i><span>${escapeHtml(options.emptyText || "Chưa có tương tác nào được ghi nhận.")}</span></div>`;
        }

        const total = options.totalCount != null ? Number(options.totalCount) : list.length;
        const pageOffset = options.pageOffset || 0;
        const prefix = options.idPrefix || "vtl";
        const isGeneral = Boolean(options.isGeneral);

        return list.map((i, idx) => {
            const seq = Math.max(1, total - (pageOffset + idx));
            const kind = classifyInteractionKind(i);
            const cardResCls = kind ? kind.cls : getCardClassFromResult(i.contactResult);
            const nodeResCls = kind ? kind.nodeCls : getNodeClassFromResult(i.contactResult);
            const nodeIcon = kind ? `<i class="bi ${kind.icon}"></i>` : `#${seq}`;
            const rowId = `${prefix}-${i.interactionId ?? idx}`;

            // Nút Bệnh nhân (dành riêng cho giao diện Lịch sử chung để mở Drawer riêng của BN)
            const patientBtnHtml = isGeneral && i.profileId ? `
                <button type="button" class="cc-vtl-patient-btn" data-open-patient-drawer="${i.profileId}" title="Mở Drawer lịch sử riêng của bệnh nhân ${escapeHtml(i.patientName || '')}">
                    <i class="bi bi-person-fill"></i> ${escapeHtml(i.patientName || 'Bệnh nhân')}
                    <span class="font-mono-code" style="font-size:10px;padding:1px 4px;">${escapeHtml(i.patientCode || '')}</span>
                </button>
            ` : "";

            // Nút Mã công việc CSKH liên quan
            const taskBtnHtml = i.taskId ? `
                <button type="button" class="cc-vtl-task-btn" data-open-task-modal="${i.taskId}" title="Mở chi tiết công việc CSKH ${escapeHtml(i.taskCode || '')}">
                    <i class="bi bi-clipboard2-check"></i> ${escapeHtml(i.taskCode || '#' + i.taskId)}
                </button>
            ` : "";

            // Phân nhóm CSKH
            const careGroupBadge = i.careGroupLabel ? `
                <span class="badge ${getCareGroupBadgeClass(i.taskType)}">${escapeHtml(i.careGroupLabel)}</span>
            ` : "";

            // Kênh / Hình thức liên hệ
            const channelBadge = `
                <span class="badge badge-group-appt"><i class="bi ${getChannelIcon(i.channel)} me-1"></i>${escapeHtml(kind ? kind.label : (i.channelLabel || i.channel || "—"))}</span>
            `;

            // Kết quả tương tác
            const resultBadgeHtml = kind ? "" : getResultBadge(i.contactResult, i.contactResultLabel);

            // Ghi chú tóm tắt
            const notesText = i.notes || "—";
            const retryNotice = i.nextRetryAt ? `
                <span class="text-warning-emphasis small fw-bold"><i class="bi bi-clock-history me-1"></i>Hẹn gọi lại: ${escapeHtml(formatDateTime(i.nextRetryAt))}</span>
            ` : "";

            return `
                <div class="cc-vtl-item">
                    <div class="cc-vtl-line"></div>
                    <div class="cc-vtl-arrow" title="Tương tác trước đó"><i class="bi bi-arrow-down-short"></i></div>
                    <div class="cc-vtl-node ${nodeResCls}" title="Lần tương tác thứ ${seq}">
                        ${nodeIcon}
                    </div>

                    <div class="cc-vtl-card ${cardResCls}">
                        <div class="cc-vtl-header">
                            <div class="cc-vtl-header-left">
                                <span class="cc-vtl-seq-badge">Lần #${seq}</span>
                                <span class="cc-vtl-time"><i class="bi bi-calendar-event text-secondary"></i>${escapeHtml(formatDateTime(i.createdAt))}</span>
                                <span class="cc-vtl-staff"><i class="bi bi-person-check text-primary"></i><strong>${escapeHtml(i.staffName || "Nhân viên CSKH")}</strong></span>
                                ${patientBtnHtml}
                                ${taskBtnHtml}
                            </div>
                            <div class="d-flex align-items-center gap-1 flex-wrap">
                                ${channelBadge}
                                ${careGroupBadge}
                                ${resultBadgeHtml}
                            </div>
                        </div>

                        <div class="cc-vtl-summary is-clamped" id="${rowId}-summary">
                            ${escapeHtml(notesText)}
                        </div>

                        <div class="cc-vtl-footer">
                            <div>${retryNotice}</div>
                            <button type="button" class="cc-vtl-toggle-btn" data-vtl-toggle="${rowId}" aria-expanded="false">
                                <span>Xem chi tiết</span> <i class="bi bi-chevron-down"></i>
                            </button>
                        </div>

                        <div class="cc-vtl-detail" id="${rowId}" hidden>
                            <div class="cc-vtl-detail-grid">
                                <div>
                                    <span>Thời gian thực hiện</span>
                                    <strong>${escapeHtml(formatDateTime(i.createdAt))}</strong>
                                </div>
                                <div>
                                    <span>Người thực hiện</span>
                                    <strong>${escapeHtml(i.staffName || "—")}${i.staffPosition ? ` (${escapeHtml(i.staffPosition)})` : ""}</strong>
                                </div>
                                <div>
                                    <span>Hình thức liên hệ</span>
                                    <strong>${escapeHtml(i.channelLabel || i.channel || "—")}</strong>
                                </div>
                                <div>
                                    <span>Kết quả tương tác</span>
                                    <strong>${escapeHtml(i.contactResultLabel || i.contactResult || "—")}</strong>
                                </div>
                                <div>
                                    <span>Lịch hẹn gọi lại (next_retry_at)</span>
                                    <strong>${i.nextRetryAt ? escapeHtml(formatDateTime(i.nextRetryAt)) : "Không có"}</strong>
                                </div>
                                <div>
                                    <span>Công việc CSKH liên quan</span>
                                    <strong>${escapeHtml(i.taskCode || "—")}${i.careGroupLabel ? ` · ${escapeHtml(i.careGroupLabel)}` : ""}${i.taskStatus ? ` · [${escapeHtml(statusLabelOf(i.taskStatus))}]` : ""}</strong>
                                </div>
                                ${i.branchName ? `
                                <div>
                                    <span>Cơ sở / Chi nhánh</span>
                                    <strong>${escapeHtml(i.branchName)}</strong>
                                </div>
                                ` : ""}
                                ${i.patientName ? `
                                <div>
                                    <span>Bệnh nhân</span>
                                    <strong>${escapeHtml(i.patientName)} (${escapeHtml(i.patientCode || "")}) · SĐT: ${escapeHtml(i.patientPhone || "—")}</strong>
                                </div>
                                ` : ""}
                            </div>
                            <div class="cc-vtl-full-notes">
                                <span>Nội dung trao đổi / Ghi chú của nhân viên / Phản hồi của bệnh nhân:</span>
                                <p>${escapeHtml(notesText)}</p>
                            </div>
                        </div>
                    </div>
                </div>
            `;
        }).join("");
    }

    function toggleVtlRow(btn) {
        const targetId = btn?.dataset?.vtlToggle;
        const detail = targetId ? document.getElementById(targetId) : null;
        if (!detail) return;
        const willOpen = detail.hidden;
        detail.hidden = !willOpen;
        btn.setAttribute("aria-expanded", willOpen ? "true" : "false");
        btn.classList.toggle("is-open", willOpen);
        const label = btn.querySelector("span");
        if (label) label.textContent = willOpen ? "Thu gọn" : "Xem chi tiết";
        const summary = document.getElementById(`${targetId}-summary`);
        if (summary) {
            summary.classList.toggle("is-clamped", !willOpen);
        }
    }

    // =========================================================================
    // OFFCANVAS RIGHT DRAWER: LỊCH SỬ TƯƠNG TÁC RIÊNG CỦA BỆNH NHÂN (SCR-44.6)
    // =========================================================================
    const patientDrawerState = {
        profileId: null,
        patient: null,
        allInteractions: [],
        allTasks: [],
        filteredInteractions: [],
        visibleLimit: 10
    };

    async function openPatientHistoryDrawer(profileId) {
        const pid = Number(profileId);
        if (!pid) return;

        patientDrawerState.profileId = pid;
        patientDrawerState.visibleLimit = 10;

        if ($("patientHistoryDrawerKeyword")) $("patientHistoryDrawerKeyword").value = "";
        if ($("patientHistoryDrawerTaskFilter")) $("patientHistoryDrawerTaskFilter").value = "";
        if ($("patientHistoryDrawerResultFilter")) $("patientHistoryDrawerResultFilter").value = "";

        if ($("patientHistoryDrawerOverlay")) $("patientHistoryDrawerOverlay").style.display = "block";
        if ($("patientHistoryDrawer")) $("patientHistoryDrawer").classList.add("show");

        if ($("patientHistoryDrawerName")) $("patientHistoryDrawerName").textContent = "Đang tải dữ liệu...";
        if ($("patientHistoryDrawerCode")) $("patientHistoryDrawerCode").textContent = "—";
        if ($("patientHistoryDrawerPhone")) $("patientHistoryDrawerPhone").textContent = "—";
        if ($("patientHistoryDrawerBranch")) $("patientHistoryDrawerBranch").textContent = "—";
        if ($("patientHistoryDrawerTotalBadge")) $("patientHistoryDrawerTotalBadge").textContent = "Đang tải...";
        if ($("patientHistoryDrawerTimeline")) {
            $("patientHistoryDrawerTimeline").innerHTML = '<div class="p-4 text-center text-muted"><div class="spinner-border spinner-border-sm text-primary me-2"></div>Đang tải timeline tương tác bệnh nhân...</div>';
        }
        if ($("patientHistoryDrawerEmpty")) $("patientHistoryDrawerEmpty").hidden = true;
        if ($("patientHistoryDrawerMoreWrap")) $("patientHistoryDrawerMoreWrap").hidden = true;

        try {
            const data = await request(`/api/customer-care/patients/${pid}/history`);
            const p = data?.patient || {};
            patientDrawerState.patient = p;
            patientDrawerState.allInteractions = Array.isArray(data?.interactions) ? data.interactions : [];
            patientDrawerState.allTasks = Array.isArray(data?.tasks) ? data.tasks : [];

            const fullName = p.fullName || "Bệnh nhân #" + pid;
            if ($("patientHistoryDrawerName")) $("patientHistoryDrawerName").textContent = fullName;
            if ($("patientHistoryDrawerCode")) $("patientHistoryDrawerCode").textContent = p.patientCode || "—";
            if ($("patientHistoryDrawerPhone")) $("patientHistoryDrawerPhone").textContent = p.phoneNumber || "—";
            if ($("patientHistoryDrawerAvatar")) {
                const initial = fullName.trim().charAt(0).toUpperCase();
                $("patientHistoryDrawerAvatar").textContent = initial || "BN";
            }

            const firstBranchName = patientDrawerState.allTasks[0]?.branchName || p.branchName || "";
            if ($("patientHistoryDrawerBranch")) $("patientHistoryDrawerBranch").textContent = firstBranchName || "Cơ sở điều trị";

            if ($("patientHistoryDrawerTaskFilter")) {
                const taskOpts = '<option value="">Tất cả công việc CSKH (' + patientDrawerState.allTasks.length + ')</option>' +
                    patientDrawerState.allTasks.map(t =>
                        `<option value="${escapeHtml(t.taskId)}">${escapeHtml(t.taskCode)} - ${escapeHtml(t.careGroupLabel || '')} [${escapeHtml(t.statusLabel || '')}]</option>`
                    ).join("");
                $("patientHistoryDrawerTaskFilter").innerHTML = taskOpts;
            }

            applyPatientDrawerFilters();
        } catch (err) {
            if ($("patientHistoryDrawerTimeline")) {
                $("patientHistoryDrawerTimeline").innerHTML = `<div class="p-4 text-center text-danger"><i class="bi bi-exclamation-triangle me-1"></i>${escapeHtml(err.message)}</div>`;
            }
        }
    }

    function closePatientHistoryDrawer() {
        if ($("patientHistoryDrawerOverlay")) $("patientHistoryDrawerOverlay").style.display = "none";
        if ($("patientHistoryDrawer")) $("patientHistoryDrawer").classList.remove("show");
        patientDrawerState.profileId = null;
    }

    function applyPatientDrawerFilters() {
        const kw = ($("patientHistoryDrawerKeyword")?.value || "").trim().toLowerCase();
        const taskId = $("patientHistoryDrawerTaskFilter")?.value || "";
        const result = $("patientHistoryDrawerResultFilter")?.value || "";

        let filtered = patientDrawerState.allInteractions.filter(i => {
            if (taskId && String(i.taskId) !== String(taskId)) return false;
            if (result && i.contactResult !== result) return false;
            if (kw) {
                const notes = (i.notes || "").toLowerCase();
                const staff = (i.staffName || "").toLowerCase();
                const taskCode = (i.taskCode || "").toLowerCase();
                if (!notes.includes(kw) && !staff.includes(kw) && !taskCode.includes(kw)) return false;
            }
            return true;
        });

        patientDrawerState.filteredInteractions = filtered;
        renderPatientDrawerTimeline();
    }

    function renderPatientDrawerTimeline() {
        const total = patientDrawerState.allInteractions.length;
        const filtered = patientDrawerState.filteredInteractions;
        const limit = patientDrawerState.visibleLimit;

        if ($("patientHistoryDrawerTotalBadge")) {
            $("patientHistoryDrawerTotalBadge").textContent = `${total} tương tác`;
        }

        const container = $("patientHistoryDrawerTimeline");
        const emptyEl = $("patientHistoryDrawerEmpty");
        const moreWrap = $("patientHistoryDrawerMoreWrap");

        if (!filtered.length) {
            if (container) container.innerHTML = "";
            if (emptyEl) emptyEl.hidden = false;
            if (moreWrap) moreWrap.hidden = true;
            return;
        }

        if (emptyEl) emptyEl.hidden = true;

        const shown = filtered.slice(0, limit);
        if (container) {
            container.innerHTML = renderVerticalConnectedTimeline(shown, {
                isGeneral: false,
                idPrefix: "vtl-pdrawer",
                totalCount: filtered.length,
                pageOffset: 0
            });
        }

        if (moreWrap) {
            const hasMore = filtered.length > limit;
            moreWrap.hidden = !hasMore;
            const remaining = filtered.length - limit;
            const btn = $("patientHistoryDrawerMoreBtn");
            if (btn) btn.innerHTML = `<i class="bi bi-arrow-down-circle me-1"></i>Xem thêm ${remaining} tương tác cũ hơn`;
        }
    }

    // =========================================================================
    // QUẢN LÝ LỊCH SỬ CHĂM SÓC KHÁCH HÀNG CHUNG (TAB LỊCH SỬ BN)
    // =========================================================================
    const generalHistoryState = {
        page: 0,
        pageSize: 15,
        totalPages: 0,
        totalElements: 0,
        timeRange: "ALL",
        fromDate: "",
        toDate: "",
        keyword: "",
        branchId: "",
        staffId: "",
        doctorId: "",
        channel: "ALL",
        contactResult: "ALL",
        careGroup: "ALL",
        abortController: null,
        debounceTimer: null
    };

    async function loadGeneralHistory(page = generalHistoryState.page) {
        generalHistoryState.page = page;

        const container = $("histTimelineContainer");
        const emptyState = $("histEmptyState");
        if (container) {
            container.innerHTML = '<div class="p-4 text-center text-muted"><div class="spinner-border spinner-border-sm text-primary me-2"></div>Đang tải lịch sử tương tác...</div>';
        }
        if (emptyState) emptyState.hidden = true;

        if (generalHistoryState.abortController) {
            generalHistoryState.abortController.abort();
        }
        const controller = new AbortController();
        generalHistoryState.abortController = controller;

        const params = new URLSearchParams({
            page: String(page),
            size: String(generalHistoryState.pageSize),
            timeRange: generalHistoryState.timeRange
        });

        const effectiveBranch = state.isAdmin
            ? (generalHistoryState.branchId || state.branchId || "")
            : (state.branchId || "");
        if (effectiveBranch) params.set("branchId", String(effectiveBranch));

        if (generalHistoryState.keyword) params.set("keyword", generalHistoryState.keyword);
        if (generalHistoryState.timeRange === "CUSTOM") {
            if (generalHistoryState.fromDate) params.set("fromDate", generalHistoryState.fromDate);
            if (generalHistoryState.toDate) params.set("toDate", generalHistoryState.toDate);
        }
        if (generalHistoryState.staffId) params.set("staffId", generalHistoryState.staffId);
        if (generalHistoryState.doctorId) params.set("doctorId", generalHistoryState.doctorId);
        if (generalHistoryState.channel && generalHistoryState.channel !== "ALL") params.set("channel", generalHistoryState.channel);
        if (generalHistoryState.contactResult && generalHistoryState.contactResult !== "ALL") params.set("contactResult", generalHistoryState.contactResult);
        if (generalHistoryState.careGroup && generalHistoryState.careGroup !== "ALL") params.set("careGroupFilter", generalHistoryState.careGroup);

        try {
            const data = await request(`/api/customer-care/interactions?${params}`, { signal: controller.signal });
            const rows = Array.isArray(data?.content) ? data.content : [];
            const pageMeta = data?.page && typeof data.page === "object" ? data.page : data;
            const total = Number(pageMeta?.totalElements ?? data?.totalElements ?? rows.length ?? 0);
            const totalPages = Number(pageMeta?.totalPages ?? data?.totalPages ?? (total > 0 ? Math.ceil(total / generalHistoryState.pageSize) : 0));

            generalHistoryState.totalElements = total;
            generalHistoryState.totalPages = totalPages;

            if ($("histTotalBadge")) {
                $("histTotalBadge").textContent = `${total.toLocaleString("vi-VN")} tương tác`;
            }

            renderGeneralHistoryPagination(total, page, totalPages);

            if (!rows.length) {
                if (container) container.innerHTML = "";
                if (emptyState) emptyState.hidden = false;
                return;
            }

            if (emptyState) emptyState.hidden = true;
            if (container) {
                container.innerHTML = renderVerticalConnectedTimeline(rows, {
                    isGeneral: true,
                    idPrefix: "vtl-gen",
                    totalCount: total,
                    pageOffset: page * generalHistoryState.pageSize
                });
            }
        } catch (err) {
            if (err.name === "AbortError") return;
            if (container) {
                container.innerHTML = `<div class="p-4 text-center text-danger"><i class="bi bi-exclamation-triangle me-1"></i>${escapeHtml(err.message)}</div>`;
            }
        }
    }

    function renderGeneralHistoryPagination(totalElements, page, totalPages) {
        const fromItem = totalElements === 0 ? 0 : (page * generalHistoryState.pageSize) + 1;
        const toItem = Math.min((page + 1) * generalHistoryState.pageSize, totalElements);

        if ($("lblHistRange")) {
            $("lblHistRange").textContent = totalElements === 0 ? "0 - 0" : `${fromItem} - ${toItem}`;
        }
        if ($("lblHistTotal")) {
            $("lblHistTotal").textContent = totalElements.toLocaleString("vi-VN");
        }
        if ($("histPageSizeSelect")) {
            $("histPageSizeSelect").value = String(generalHistoryState.pageSize);
        }

        const isFirst = page <= 0 || totalElements === 0;
        const isLast = page >= totalPages - 1 || totalElements === 0;

        const firstBtn = $("histFirstBtn");
        if (firstBtn) { firstBtn.disabled = isFirst; firstBtn.classList.toggle("disabled", isFirst); }
        const prevBtn = $("histPrevBtn");
        if (prevBtn) { prevBtn.disabled = isFirst; prevBtn.classList.toggle("disabled", isFirst); }
        const nextBtn = $("histNextBtn");
        if (nextBtn) { nextBtn.disabled = isLast; nextBtn.classList.toggle("disabled", isLast); }
        const lastBtn = $("histLastBtn");
        if (lastBtn) { lastBtn.disabled = isLast; lastBtn.classList.toggle("disabled", isLast); }
    }

    // =========================================================================
    // CẤU HÌNH CỘT BẢNG CSKH & COLUMN CUSTOMIZER (THEO CHUẨN CV-DCMS ENTERPRISE)
    // =========================================================================
    const CC_COLUMNS_KEY = "cvdcms_cc_table_columns_v7";
    const DEFAULT_CC_COLUMNS = [
        { id: "taskCode", title: "Mã CV", width: 110, visible: true, required: true },
        { id: "patient", title: "Bệnh nhân", width: 180, visible: true, required: true },
        { id: "careGroup", title: "Phân nhóm & Nguồn", width: 165, visible: true },
        { id: "due", title: "Hạn / Gọi lại", width: 125, visible: true },
        { id: "notes", title: "Chẩn đoán & Lời dặn", width: 250, visible: true },
        { id: "staff", title: "Phụ trách", width: 135, visible: true },
        { id: "interaction", title: "Tương tác gần nhất", width: 155, visible: true },
        { id: "status", title: "Trạng thái", width: 115, visible: true },
        { id: "actions", title: "Thao tác", width: 285, visible: true, required: true }
    ];

    let ccColumns = loadCcColumns();

    function loadCcColumns() {
        try {
            const raw = localStorage.getItem(CC_COLUMNS_KEY);
            if (raw) {
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed) && parsed.length === DEFAULT_CC_COLUMNS.length) {
                    const actCol = parsed.find(c => c.id === "actions");
                    if (actCol && (!actCol.width || actCol.width < 280)) {
                        actCol.width = 285;
                    }
                    return parsed;
                }
            }
        } catch (e) {}
        return JSON.parse(JSON.stringify(DEFAULT_CC_COLUMNS));
    }

    function saveCcColumns() {
        try {
            localStorage.setItem(CC_COLUMNS_KEY, JSON.stringify(ccColumns));
        } catch (e) {}
    }

    function getStickyPatientLeft(visibleCols) {
        const tc = visibleCols.find(c => c.id === "taskCode");
        return tc ? Number(tc.width || 110) : 0;
    }

    function renderCcTableHeader() {
        const theadTr = $("ccTasksHeaderRow");
        if (!theadTr) return;
        theadTr.innerHTML = "";

        const visibleCols = ccColumns.filter(c => c.visible);
        const patientLeft = getStickyPatientLeft(visibleCols);

        visibleCols.forEach((col, idx) => {
            const th = document.createElement("th");
            th.style.width = col.width + "px";
            th.style.minWidth = col.width + "px";
            th.style.maxWidth = col.width + "px";
            if (col.id === "taskCode") {
                th.className = "cc-col-sticky-taskcode";
            } else if (col.id === "patient") {
                th.className = "cc-col-sticky-patient";
                th.style.left = patientLeft + "px";
            } else if (col.id === "actions") {
                th.className = "cc-col-sticky-actions text-center";
            }
            th.innerHTML = `
                <div class="dv-th-inner">
                    <span class="cell-ellipsis" title="${col.title}">${col.title}</span>
                </div>
                <span class="col-resizer" data-col-index="${idx}"></span>
            `;
            theadTr.appendChild(th);
        });

        setupCcColResizers();
    }

    function setupCcColResizers() {
        const resizers = document.querySelectorAll("#ccTasksHeaderRow .col-resizer");
        resizers.forEach(resizer => {
            resizer.addEventListener("mousedown", function(e) {
                e.preventDefault();
                const colIdx = parseInt(this.getAttribute("data-col-index"), 10);
                const col = ccColumns[colIdx];
                const startX = e.pageX;
                const startWidth = col.width;
                this.classList.add("is-resizing");

                function onMouseMove(moveEvent) {
                    const diff = moveEvent.pageX - startX;
                    let newWidth = startWidth + diff;
                    if (newWidth < 70) newWidth = 70;
                    if (newWidth > 400) newWidth = 400;
                    col.width = newWidth;
                    const th = document.querySelectorAll("#ccTasksHeaderRow th")[colIdx];
                    if (th) th.style.width = newWidth + "px";
                }

                function onMouseUp() {
                    document.removeEventListener("mousemove", onMouseMove);
                    document.removeEventListener("mouseup", onMouseUp);
                    resizer.classList.remove("is-resizing");
                    saveCcColumns();
                    loadTasks(state.page);
                }

                document.addEventListener("mousemove", onMouseMove);
                document.addEventListener("mouseup", onMouseUp);
            });
        });
    }

    window.openCcColumnDrawer = function() {
        renderCcDrawerColumns();
        if ($("ccDrawerOverlay")) $("ccDrawerOverlay").style.display = "block";
        if ($("ccColumnDrawer")) $("ccColumnDrawer").classList.add("show");
    };

    window.closeCcColumnDrawer = function() {
        if ($("ccDrawerOverlay")) $("ccDrawerOverlay").style.display = "none";
        if ($("ccColumnDrawer")) $("ccColumnDrawer").classList.remove("show");
    };

    window.filterCcDrawerColumns = function() {
        const kw = ($("drawerSearchCcCol")?.value || "").toLowerCase().trim();
        const items = document.querySelectorAll("#drawerCcColsList .drawer-item");
        items.forEach(it => {
            const txt = it.innerText.toLowerCase();
            it.style.display = txt.includes(kw) ? "flex" : "none";
        });
    };

    window.resetCcDefaultColumns = function() {
        ccColumns = JSON.parse(JSON.stringify(DEFAULT_CC_COLUMNS));
        saveCcColumns();
        renderCcTableHeader();
        renderCcDrawerColumns();
        loadTasks(state.page);
    };

    function renderCcDrawerColumns() {
        const list = $("drawerCcColsList");
        if (!list) return;
        list.innerHTML = "";

        ccColumns.forEach((col, idx) => {
            const item = document.createElement("div");
            item.className = "drawer-item";
            item.draggable = true;
            item.innerHTML = `
                <i class="bi bi-grip-vertical text-muted fs-6"></i>
                <input type="checkbox" class="form-check-input mt-0 me-2" ${col.visible ? "checked" : ""} ${col.required ? "disabled" : ""}>
                <span class="small fw-bold text-dark flex-grow-1">${col.title} ${col.required ? '<span class="text-danger">*</span>' : ""}</span>
            `;

            const chk = item.querySelector("input[type=checkbox]");
            chk.addEventListener("change", (e) => {
                col.visible = e.target.checked;
                saveCcColumns();
                renderCcTableHeader();
                loadTasks(state.page);
            });

            item.addEventListener("dragstart", (e) => e.dataTransfer.setData("text/plain", idx));
            item.addEventListener("dragover", (e) => e.preventDefault());
            item.addEventListener("drop", (e) => {
                e.preventDefault();
                const fromIdx = parseInt(e.dataTransfer.getData("text/plain"), 10);
                const toIdx = idx;
                const moved = ccColumns.splice(fromIdx, 1)[0];
                ccColumns.splice(toIdx, 0, moved);
                saveCcColumns();
                renderCcTableHeader();
                renderCcDrawerColumns();
                loadTasks(state.page);
            });

            list.appendChild(item);
        });
    }

    function renderCcPagination(totalElements, page, totalPages) {
        state.totalPages = totalPages;
        state.page = page;

        // Công thức tính fromItem và toItem chuẩn 1-indexed (xử lý an toàn khi totalElements = 0)
        const fromItem = totalElements === 0 ? 0 : (page * state.pageSize) + 1;
        const toItem = Math.min((page + 1) * state.pageSize, totalElements);

        if ($("lblTasksRange")) {
            $("lblTasksRange").textContent = totalElements === 0 ? "0 - 0" : `${fromItem} - ${toItem}`;
        }
        if ($("lblTasksTotal")) {
            $("lblTasksTotal").textContent = totalElements.toLocaleString("vi-VN");
        }
        if ($("tasksTotalBadge")) {
            $("tasksTotalBadge").textContent = totalElements.toLocaleString("vi-VN");
        }
        if ($("tasksPageSizeSelect")) {
            $("tasksPageSizeSelect").value = state.pageSize;
        }

        // Trạng thái vô hiệu hóa cụm 4 nút điều hướng (|< < > >|)
        const isFirst = page <= 0 || totalElements === 0;
        const isLast = page >= totalPages - 1 || totalElements === 0;

        const firstBtn = $("tasksFirstBtn");
        if (firstBtn) {
            firstBtn.disabled = isFirst;
            firstBtn.classList.toggle("disabled", isFirst);
        }
        const prevBtn = $("tasksPrevBtn");
        if (prevBtn) {
            prevBtn.disabled = isFirst;
            prevBtn.classList.toggle("disabled", isFirst);
        }
        const nextBtn = $("tasksNextBtn");
        if (nextBtn) {
            nextBtn.disabled = isLast;
            nextBtn.classList.toggle("disabled", isLast);
        }
        const lastBtn = $("tasksLastBtn");
        if (lastBtn) {
            lastBtn.disabled = isLast;
            lastBtn.classList.toggle("disabled", isLast);
        }
    }

    window.onCcPageSizeChange = function(newSize) {
        state.pageSize = parseInt(newSize, 10) || 10;
        loadTasks(0); // Bắt buộc reset về trang đầu khi đổi số lượng hiển thị
    };

    window.goToCcPage = function(p) {
        if (p < 0 || (state.totalPages && p >= state.totalPages)) return;
        loadTasks(p);
    };

    window.goToCcPrevPage = function() {
        if (state.page > 0) {
            loadTasks(state.page - 1);
        }
    };

    window.goToCcNextPage = function() {
        if (state.totalPages > 0 && state.page < state.totalPages - 1) {
            loadTasks(state.page + 1);
        }
    };

    window.goToCcLastPage = function() {
        if (state.totalPages > 0) {
            loadTasks(Math.max(0, state.totalPages - 1));
        }
    };

    function openContentPreviewModal(taskId) {
        const t = state.tasksById[String(taskId)];
        const modal = $("ccContentPreviewModal");
        if (!t || !modal) return;

        const subEl = $("ccPreviewModalSub");
        if (subEl) {
            subEl.textContent = `${t.taskCode || ""} · ${t.patientName || ""} (${t.patientCode || ""}) · SĐT: ${t.patientPhone || "—"}`;
        }

        const sections = [];
        if (t.notes) {
            sections.push(`
                <div class="p-3 rounded-3 border bg-light">
                    <div class="fw-bold text-secondary small text-uppercase mb-1">Ghi chú công việc CSKH</div>
                    <div class="text-dark">${escapeHtml(t.notes)}</div>
                </div>
            `);
        }
        if (t.escalationReason) {
            sections.push(`
                <div class="p-3 rounded-3 border" style="background:#fff7ed;border-color:#fed7aa !important;">
                    <div class="fw-bold small text-uppercase mb-1" style="color:#c2410c;">Lý do chuyển Bác sĩ (${escapeHtml(t.escalatedByName || "CSKH")})</div>
                    <div style="color:#9a3412;">${escapeHtml(t.escalationReason)}</div>
                </div>
            `);
        }
        if (t.diagnosisSummary) {
            sections.push(`
                <div class="p-3 rounded-3 border bg-white">
                    <div class="fw-bold text-dark small text-uppercase mb-1">Chẩn đoán lâm sàng</div>
                    <div class="text-dark">${escapeHtml(t.diagnosisSummary)}</div>
                </div>
            `);
        }
        const instr = t.treatmentInstructions || t.prescriptionAdvice;
        if (instr) {
            sections.push(`
                <div class="p-3 rounded-3 border" style="background:#f0f9ff;border-color:#bae6fd !important;">
                    <div class="fw-bold small text-uppercase mb-1" style="color:#0369a1;">Lời dặn &amp; Hướng dẫn sau điều trị</div>
                    <div style="color:#0c4a6e;">${escapeHtml(instr)}</div>
                </div>
            `);
        }
        if (t.doctorResponse) {
            sections.push(`
                <div class="p-3 rounded-3 border" style="background:#f0fdf4;border-color:#bbf7d0 !important;">
                    <div class="fw-bold small text-uppercase mb-1" style="color:#15803d;">Phản hồi chuyên môn (${escapeHtml(t.escalatedToDoctorName || "Bác sĩ")})</div>
                    <div style="color:#166534;">${escapeHtml(t.doctorResponse)}</div>
                </div>
            `);
        }
        if (!sections.length) {
            sections.push('<div class="text-muted">Chưa có nội dung chẩn đoán hoặc ghi chú bổ sung.</div>');
        }

        const bodyEl = $("ccPreviewModalBody");
        if (bodyEl) bodyEl.innerHTML = sections.join("");
        modal.hidden = false;
    }

    function getTaskNotesSummary(t) {
        if (!t) return { main: "—", tag: "", tagCls: "", hasMore: false };

        const instructionsText = (t.treatmentInstructions || t.prescriptionAdvice || "").trim();
        const diagnosis = (t.diagnosisSummary || "").trim();
        const rawNotes = (t.notes || "").trim();
        const escReason = (t.escalationReason || "").trim();
        const docReply = (t.doctorResponse || "").trim();

        const fullContentCount = [rawNotes, escReason, diagnosis, instructionsText, docReply].filter(Boolean).length;
        const isLongText = (rawNotes.length > 40) || (instructionsText.length > 35) || (diagnosis.length > 35) || (docReply.length > 35);
        const hasMore = fullContentCount > 1 || isLongText;

        // 1. Phản hồi Bác sĩ
        if (docReply) {
            return {
                main: docReply,
                tag: `BS ${t.escalatedToDoctorName || ""}: Đã phản hồi`,
                tagCls: "is-doc",
                hasMore
            };
        }

        // 2. Chờ Bác sĩ phản hồi
        if (t.status === "ESCALATED_TO_DOCTOR" || escReason) {
            return {
                main: escReason || rawNotes || "Chờ bác sĩ hỗ trợ chuyên môn",
                tag: "Chờ BS phản hồi",
                tagCls: "is-wait",
                hasMore
            };
        }

        // 3. Tách nội dung chính gọn gàng từ rawNotes (loại bỏ phần ghép dài dòng)
        let cleanMain = rawNotes;
        if (cleanMain) {
            if (cleanMain.includes(" --- ")) {
                cleanMain = cleanMain.split(" --- ")[0];
            } else if (cleanMain.includes(" | ")) {
                cleanMain = cleanMain.split(" | ")[0];
            }
        } else if (instructionsText) {
            cleanMain = instructionsText;
        } else if (diagnosis) {
            cleanMain = diagnosis;
        } else {
            cleanMain = "Theo dõi & Chăm sóc định kỳ";
        }

        let tag = "";
        let tagCls = "";
        if (diagnosis) {
            tag = diagnosis.length > 25 ? diagnosis.substring(0, 22) + "..." : diagnosis;
        } else if (instructionsText) {
            tag = instructionsText.length > 25 ? instructionsText.substring(0, 22) + "..." : instructionsText;
        }

        return {
            main: cleanMain,
            tag,
            tagCls,
            hasMore
        };
    }

    async function loadTasks(page = state.page) {
        state.page = page;
        renderCcTableHeader();
        const visibleCols = ccColumns.filter(c => c.visible);
        const patientLeft = getStickyPatientLeft(visibleCols);
        const tbody = $("tasksTableBody");
        tbody.innerHTML = `<tr><td colspan="${visibleCols.length}" class="empty">Đang tải danh sách công việc CSKH...</td></tr>`;

        if (state.tasksAbortController) {
            state.tasksAbortController.abort();
        }
        const controller = new AbortController();
        state.tasksAbortController = controller;

        const params = new URLSearchParams({
            page,
            size: state.pageSize,
            tabGroup: state.activeTab === "HISTORY" ? "ACTIONABLE" : state.activeTab
        });
        if (state.branchId) params.set("branchId", state.branchId);
        if (state.activeTab === "ACTIONABLE" && state.dueFilter && state.dueFilter !== "ALL") {
            params.set("dueFilter", state.dueFilter);
        }
        if (state.activeTab === "ESCALATED" && state.doctorResponseFilter && state.doctorResponseFilter !== "ALL") {
            params.set("doctorResponseFilter", state.doctorResponseFilter);
        }
        if (state.myTasksOnly) params.set("myTasksOnly", "true");
        if ($("filterCareGroup")?.value) params.set("careGroupFilter", $("filterCareGroup").value);
        if ($("filterStaffId")?.value) params.set("assignedStaffId", $("filterStaffId").value);
        if ($("filterDoctorId")?.value) params.set("escalatedToDoctorId", $("filterDoctorId").value);
        if ($("filterStatus")?.value) params.set("status", $("filterStatus").value);
        if ($("filterFromDate")?.value) params.set("fromDate", $("filterFromDate").value);
        if ($("filterToDate")?.value) params.set("toDate", $("filterToDate").value);
        const kw = $("keyword")?.value.trim();
        if (kw) params.set("keyword", kw);

        try {
            const data = await request(`/api/customer-care/tasks?${params}`, { signal: controller.signal });
            const rows = Array.isArray(data?.content) ? data.content : (Array.isArray(data) ? data : []);

            let totalElements = 0;
            if (data?.page && typeof data.page.totalElements === "number") {
                totalElements = data.page.totalElements;
            } else if (typeof data?.totalElements === "number") {
                totalElements = data.totalElements;
            } else if (typeof data?.total === "number") {
                totalElements = data.total;
            } else {
                totalElements = rows.length;
            }
            if (totalElements < rows.length) {
                totalElements = rows.length;
            }

            let totalPages = 1;
            if (data?.page && typeof data.page.totalPages === "number") {
                totalPages = data.page.totalPages;
            } else if (typeof data?.totalPages === "number") {
                totalPages = data.totalPages;
            } else {
                totalPages = Math.max(1, Math.ceil(totalElements / state.pageSize));
            }
            state.totalPages = totalPages;

            state.tasksById = {};
            rows.forEach(item => {
                if (item && item.taskId != null) {
                    state.tasksById[String(item.taskId)] = item;
                }
            });

            if ($("tasksTotalBadge")) $("tasksTotalBadge").textContent = totalElements.toLocaleString("vi-VN");

            if (!rows.length) {
                tbody.innerHTML = `<tr><td colspan="${visibleCols.length}" class="empty">Không có công việc CSKH nào phù hợp với bộ lọc hiện tại.</td></tr>`;
                renderCcPagination(0, 0, 0);
                return;
            }

            tbody.innerHTML = rows.map(t => {
                const cells = [];
                visibleCols.forEach(col => {
                    const sizeStyle = `width:${col.width}px;min-width:${col.width}px;max-width:${col.width}px;`;
                    if (col.id === "taskCode") {
                        cells.push(`
                            <td class="cc-col-sticky-taskcode" style="${sizeStyle}">
                                <span class="font-mono-code">${escapeHtml(t.taskCode)}</span>
                                <div class="cc-clamp-1" style="font-size:11px;color:#64748b;margin-top:2px;">${escapeHtml(t.branchCode || "")}</div>
                            </td>
                        `);
                    } else if (col.id === "patient") {
                        cells.push(`
                            <td class="patient-cell cc-col-sticky-patient" style="${sizeStyle}left:${patientLeft}px;">
                                <div class="cc-clamp-1 fw-bold text-dark" style="font-size:13px;" title="${escapeHtml(t.patientName)}">${escapeHtml(t.patientName)}</div>
                                <div class="cc-clamp-1 text-muted" style="font-size:11.5px;margin-top:2px;" title="Mã BN: ${escapeHtml(t.patientCode)} · SĐT: ${escapeHtml(t.patientPhone || '—')}">
                                    <span class="font-mono-code" style="font-size:10.5px;padding:1px 4px;">${escapeHtml(t.patientCode)}</span> · ${escapeHtml(t.patientPhone || "—")}
                                </div>
                            </td>
                        `);
                    } else if (col.id === "careGroup") {
                        const refs = [];
                        if (t.visitCode) {
                            const examUrl = t.examinationDetailUrl || (t.visitId ? `/doctor/examination/${t.visitId}/diagnoses#tab-instructions` : null);
                            if (examUrl) {
                                refs.push(`<a class="source-ref" href="${escapeHtml(examUrl)}" target="_blank" rel="noopener" title="Xem hồ sơ khám">Ca: <strong>${escapeHtml(t.visitCode)}</strong></a>`);
                            } else {
                                refs.push(`<span class="source-ref">Ca: <strong>${escapeHtml(t.visitCode)}</strong></span>`);
                            }
                        }
                        if (t.prescriptionCode) refs.push(`<span class="source-ref">Thuốc: <strong>${escapeHtml(t.prescriptionCode)}</strong></span>`);
                        if (t.appointmentCode) refs.push(`<span class="source-ref">Hẹn: <strong>${escapeHtml(t.appointmentCode)}</strong></span>`);
                        cells.push(`
                            <td style="${sizeStyle}">
                                <div class="d-flex align-items-center gap-1">
                                    <span class="badge ${getCareGroupBadgeClass(t.careGroup)}">${escapeHtml(t.careGroupLabel)}</span>
                                </div>
                                ${refs.length ? `<div class="cc-clamp-1" style="font-size:11px;margin-top:3px;" title="${escapeHtml([t.visitCode, t.prescriptionCode, t.appointmentCode].filter(Boolean).join(' · '))}">${refs.join(" ")}</div>` : (t.doctorName ? `<div class="cc-clamp-1" style="font-size:11px;color:#64748b;margin-top:2px;">BS: <strong>${escapeHtml(t.doctorName)}</strong></div>` : "")}
                            </td>
                        `);
                    } else if (col.id === "due") {
                        let dueTag = "";
                        if (isTaskClosed(t)) {
                            dueTag = "";
                        } else if (t.dueBucket === "OVERDUE" || t.overdue) {
                            dueTag = '<span class="cc-due-badge is-overdue">Quá hạn</span>';
                        } else if (t.dueBucket === "TODAY" || t.dueToday) {
                            dueTag = '<span class="cc-due-badge is-today">Hôm nay</span>';
                        } else if (t.dueBucket === "UPCOMING") {
                            dueTag = '<span class="cc-due-badge is-upcoming">Sắp tới</span>';
                        }
                        cells.push(`
                            <td style="${sizeStyle}">
                                <div class="d-flex align-items-center gap-1">
                                    <strong style="font-size:12.5px;">${escapeHtml(t.dueDate || "—")}</strong>
                                    ${dueTag}
                                </div>
                                ${t.nextRetryAt ? `<div class="retry-tag cc-clamp-1" style="font-size:10.5px;margin-top:2px;" title="Gọi lại: ${escapeHtml(formatDateTime(t.nextRetryAt))}">Gọi lại: ${escapeHtml(formatDateTime(t.nextRetryAt))}</div>` : ""}
                            </td>
                        `);
                    } else if (col.id === "notes") {
                        const noteData = getTaskNotesSummary(t);
                        const tooltipParts = [
                            t.notes ? `Ghi chú: ${t.notes}` : null,
                            t.escalationReason ? `Lý do chuyển BS: ${t.escalationReason}` : null,
                            t.diagnosisSummary ? `Chẩn đoán: ${t.diagnosisSummary}` : null,
                            t.treatmentInstructions || t.prescriptionAdvice ? `Lời dặn: ${t.treatmentInstructions || t.prescriptionAdvice}` : null,
                            t.doctorResponse ? `Phản hồi BS: ${t.doctorResponse}` : null
                        ].filter(Boolean);
                        const fullTooltip = tooltipParts.join("\n\n");

                        cells.push(`
                            <td style="${sizeStyle}">
                                <div class="cc-compact-notes-cell" data-preview-task="${t.taskId}" title="${escapeHtml(fullTooltip ? fullTooltip + '\n\n(Bấm để xem đầy đủ)' : 'Bấm để xem chi tiết')}">
                                    <div class="cc-compact-notes-main" title="${escapeHtml(noteData.main)}">${escapeHtml(noteData.main)}</div>
                                    <div class="cc-compact-notes-sub">
                                        ${noteData.tag ? `<span class="cc-compact-notes-tag ${noteData.tagCls}" title="${escapeHtml(noteData.tag)}">${escapeHtml(noteData.tag)}</span>` : '<span class="text-muted" style="font-size:11px;">Theo dõi CSKH</span>'}
                                        ${noteData.hasMore ? `<span class="cc-compact-notes-more"><i class="bi bi-eye"></i> Chi tiết</span>` : ''}
                                    </div>
                                </div>
                            </td>
                        `);
                    } else if (col.id === "staff") {
                        cells.push(`
                            <td style="${sizeStyle}">
                                <div class="cc-clamp-1" style="font-size:12px;" title="CSKH: ${escapeHtml(t.assignedStaffName || 'Chưa gán')}"><span style="color:#64748b;">CSKH:</span> <strong>${escapeHtml(t.assignedStaffName || 'Chưa gán')}</strong></div>
                                ${t.escalatedToDoctorName ? `<div class="cc-clamp-1 text-danger" style="font-size:11.5px;margin-top:2px;" title="BS: ${escapeHtml(t.escalatedToDoctorName)}"><span>BS:</span> <strong>${escapeHtml(t.escalatedToDoctorName)}</strong></div>` : ""}
                            </td>
                        `);
                    } else if (col.id === "interaction") {
                        cells.push(`
                            <td style="${sizeStyle}">
                                <div>${formatChannelBadge(t.lastChannel, t.lastContactResult)}</div>
                                ${t.lastContactAt ? `<div class="cc-clamp-1" style="font-size:11px;color:#64748b;margin-top:2px;">${escapeHtml(formatDateTime(t.lastContactAt))} (${t.interactionCount} lần)</div>` : ""}
                            </td>
                        `);
                    } else if (col.id === "status") {
                        cells.push(`
                            <td style="${sizeStyle}">
                                <span class="badge ${getStatusBadgeClass(t.status)}">${escapeHtml(t.statusLabel)}</span>
                            </td>
                        `);
                    } else if (col.id === "actions") {
                        const closed = isTaskClosed(t);
                        const showDocReply = !closed && (t.status === "ESCALATED_TO_DOCTOR" || t.escalatedToDoctorId) && (state.careRole === "BAC_SI" || state.careRole === "ADMIN");
                        let actBtns = "";
                        if (closed) {
                            actBtns = `
                                <button type="button" class="btn btn-outline-primary btn-sm cc-act-btn" data-record-interaction="${t.taskId}" title="Xem chi tiết công việc đã đóng (chỉ xem)">
                                    <i class="bi bi-eye"></i> Chi tiết
                                </button>
                                <button type="button" class="btn btn-outline-secondary btn-sm cc-act-btn" data-view-history="${t.profileId}" title="Xem lịch sử chăm sóc bệnh nhân">
                                    <i class="bi bi-clock-history"></i> Lịch sử
                                </button>
                                ${canReopenTask(t) ? `
                                    <button type="button" class="btn btn-outline-warning btn-sm cc-act-btn" data-reopen-task="${t.taskId}" title="Mở lại công việc (chỉ Admin, bắt buộc lý do)">
                                        <i class="bi bi-arrow-counterclockwise"></i> Mở lại
                                    </button>
                                ` : ""}
                            `;
                        } else if (showDocReply) {
                            actBtns = `
                                <button type="button" class="btn btn-danger btn-sm cc-act-btn fw-bold" data-doctor-response="${t.taskId}" title="Bác sĩ nhập phản hồi chuyên môn">
                                    <i class="bi bi-chat-dots-fill"></i> Phản hồi
                                </button>
                                <button type="button" class="btn btn-primary btn-sm cc-act-btn" data-record-interaction="${t.taskId}" title="Chi tiết & Ghi kết quả liên hệ" style="background:linear-gradient(135deg, #0284c7, #2563eb);border:none;">
                                    <i class="bi bi-pencil-square"></i> Ghi KQ
                                </button>
                                <button type="button" class="btn btn-outline-secondary btn-sm cc-act-btn" data-view-history="${t.profileId}" title="Xem lịch sử chăm sóc bệnh nhân">
                                    <i class="bi bi-clock-history"></i> Lịch sử
                                </button>
                                <button type="button" class="btn btn-outline-secondary btn-sm cc-act-btn cc-act-icon-btn" data-edit-task="${t.taskId}" title="Sửa / Phân công lại">
                                    <i class="bi bi-pencil"></i>
                                </button>
                            `;
                        } else {
                            actBtns = `
                                <button type="button" class="btn btn-primary btn-sm cc-act-btn" data-record-interaction="${t.taskId}" title="Chi tiết & Ghi kết quả liên hệ" style="background:linear-gradient(135deg, #0284c7, #2563eb);border:none;">
                                    <i class="bi bi-pencil-square"></i> Ghi KQ
                                </button>
                                <button type="button" class="btn btn-outline-danger btn-sm cc-act-btn" data-escalate-task="${t.taskId}" title="Chuyển Bác sĩ hỗ trợ chuyên môn">
                                    <i class="bi bi-arrow-up-right-circle"></i> Chuyển BS
                                </button>
                                <button type="button" class="btn btn-outline-secondary btn-sm cc-act-btn" data-view-history="${t.profileId}" title="Xem lịch sử chăm sóc bệnh nhân">
                                    <i class="bi bi-clock-history"></i> Lịch sử
                                </button>
                                <button type="button" class="btn btn-outline-secondary btn-sm cc-act-btn cc-act-icon-btn" data-edit-task="${t.taskId}" title="Sửa / Phân công lại">
                                    <i class="bi bi-pencil"></i>
                                </button>
                            `;
                        }
                        cells.push(`
                            <td class="text-center cc-col-sticky-actions" style="${sizeStyle}">
                                <div class="cc-action-btn-group" onclick="event.stopPropagation()">
                                    ${actBtns}
                                </div>
                            </td>
                        `);
                    }
                });

                return `<tr class="dv-data-row">${cells.join("")}</tr>`;
            }).join("");

            renderCcPagination(totalElements, page, state.totalPages);
        } catch (err) {
            if (err.name === "AbortError") return;
            tbody.innerHTML = `<tr><td colspan="${visibleCols.length}" class="empty">${escapeHtml(err.message)}</td></tr>`;
            showMessage(err.message, "error");
        }
    }

    window.toggleCcAdvFilter = function() {
        const panel = $("advFilterPanel");
        const btn = $("btnToggleAdvFilter");
        if (!panel) return;
        panel.classList.toggle("show");
        if (btn) btn.classList.toggle("is-open", panel.classList.contains("show"));
    };

    // =========================================================================
    // 4. MODAL LẬP LỊCH / PHÂN CÔNG CHĂM SÓC & TỰ ĐỘNG TỔNG HỢP (SCR-44.3)
    // =========================================================================

    function openTaskFormModal(task = null) {
        $("taskForm").reset();
        $("editTaskId").value = task?.taskId || "";
        $("taskFormModalTitle").textContent = task
            ? `Cập nhật / Phân công công việc CSKH: ${task.taskCode}`
            : "Lập lịch / Phân công chăm sóc khách hàng (SCR-44.3)";

        $("formDueDate").value = task?.dueDate || addDaysYmd(getVietnamTodayYmd(), 1);

        if (task) {
            $("formProfileId").value = String(task.profileId || "");
            populatePatientLinkedRecords(task.profileId);
            $("formCareGroup").value = task.careGroup || task.taskType || "POST_TREATMENT_FOLLOWUP";
            $("formAssignedStaffId").value = task.assignedStaffId ? String(task.assignedStaffId) : "";
            $("formVisitId").value = task.visitId ? String(task.visitId) : "";
            $("formPrescriptionId").value = task.prescriptionId ? String(task.prescriptionId) : "";
            $("formAppointmentId").value = task.appointmentId ? String(task.appointmentId) : "";
            $("formNotes").value = task.notes || "";
            previewSelectedVisitExamination(task.visitId);
        } else {
            const filterCare = $("filterCareGroup")?.value;
            if (filterCare && [...$("formCareGroup").options].some(o => o.value === filterCare)) {
                $("formCareGroup").value = filterCare;
            }
            populatePatientLinkedRecords("");
        }

        $("taskFormModal").hidden = false;
    }

    async function submitTaskForm(event) {
        event.preventDefault();
        const editId = $("editTaskId").value;
        const branchId = state.branchId || Number($("branchId").value) || 1;
        const profileId = Number($("formProfileId").value);
        const dueDate = $("formDueDate").value;
        const notes = $("formNotes").value.trim();

        if (!profileId) {
            showMessage("Vui lòng chọn bệnh nhân cần chăm sóc.", "warning");
            return;
        }
        if (!dueDate) {
            showMessage("Vui lòng chọn hạn hoàn thành (Due Date).", "warning");
            return;
        }
        if (!notes || notes.length < 3) {
            showMessage("Vui lòng nhập nội dung ghi chú công việc CSKH (tối thiểu 3 ký tự).", "warning");
            return;
        }

        const payload = {
            branchId,
            profileId,
            careGroup: $("formCareGroup").value,
            taskType: $("formCareGroup").value,
            dueDate,
            assignedStaffId: $("formAssignedStaffId").value ? Number($("formAssignedStaffId").value) : null,
            visitId: $("formVisitId").value ? Number($("formVisitId").value) : null,
            prescriptionId: $("formPrescriptionId").value ? Number($("formPrescriptionId").value) : null,
            appointmentId: $("formAppointmentId").value ? Number($("formAppointmentId").value) : null,
            notes
        };

        const btn = $("saveTaskFormBtn");
        btn.disabled = true;
        try {
            const saved = await request(editId ? `/api/customer-care/tasks/${editId}` : "/api/customer-care/tasks", {
                method: editId ? "PUT" : "POST",
                body: JSON.stringify(payload)
            });
            $("taskFormModal").hidden = true;
            showMessage(
                editId
                    ? `Đã cập nhật công việc ${saved.taskCode} thành công.`
                    : `Đã lập lịch công việc CSKH ${saved.taskCode} cho bệnh nhân ${saved.patientName}.`,
                "success"
            );
            await Promise.all([
                loadSummary(),
                state.viewMode === "CALENDAR" ? loadCalendar() : loadTasks(editId ? state.page : 0)
            ]);
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            btn.disabled = false;
        }
    }

    // =========================================================================
    // 5. MODAL CHI TIẾT CÔNG VIỆC & GHI KẾT QUẢ LIÊN HỆ (SCR-44.4)
    // =========================================================================

    function updateRetryFieldRequirement() {
        const resultVal = $("intContactResult").value;
        const retryInput = $("intNextRetryAt");
        const retryLabel = $("retryDateTimeLabel");
        const retryHint = $("retryHint");
        const nextStatusSelect = $("intNextStatus");

        const needsRetry = resultVal === "NO_ANSWER" || resultVal === "BUSY";
        retryInput.required = needsRetry;

        if (needsRetry) {
            retryLabel.innerHTML = 'Thời gian hẹn gọi lại (next_retry_at) <strong style="color:#dc2626;">* Bắt buộc</strong>';
            retryHint.className = "field-hint required-alert";
            retryHint.textContent = "Bắt buộc chọn thời gian gọi lại khi bệnh nhân Máy bận hoặc Không bắt máy.";
            nextStatusSelect.value = "IN_PROGRESS";
            if (!retryInput.value) {
                const suggested = new Date(Date.now() + 2 * 60 * 60 * 1000);
                const tzOffset = suggested.getTimezoneOffset() * 60000;
                retryInput.value = new Date(suggested - tzOffset).toISOString().slice(0, 16);
            }
        } else {
            retryLabel.textContent = "Thời gian hẹn gọi lại (next_retry_at - tùy chọn)";
            retryHint.className = "field-hint";
            retryHint.textContent = "Chỉ bắt buộc khi Không bắt máy hoặc Máy bận.";
            nextStatusSelect.value = "COMPLETED";
            retryInput.value = "";
        }
    }

    function renderInteractionExaminationBox(t) {
        const box = $("interactionExaminationBox");
        if (!box) return;

        const hasClinicalInfo = Boolean(
            t.visitId || t.diagnosisSummary || t.treatmentInstructions || t.prescriptionId || (t.prescriptionItems && t.prescriptionItems.length)
        );
        if (!hasClinicalInfo) {
            box.hidden = true;
            box.innerHTML = "";
            return;
        }

        const examUrl = t.examinationDetailUrl || (t.visitId ? `/doctor/examination/${t.visitId}/diagnoses#tab-instructions` : null);
        const diagHtml = (Array.isArray(t.diagnoses) && t.diagnoses.length)
            ? `<ul style="margin:4px 0 0 18px;padding:0;">` + t.diagnoses.map(d =>
                `<li><strong>[${escapeHtml(d.diagnosisType || "MAIN")}]</strong> ${d.icd10Code ? `<code>${escapeHtml(d.icd10Code)}</code> - ` : ""}${escapeHtml(d.diagnosisText || "")}${d.toothNumbers ? ` <em>(Răng: ${escapeHtml(d.toothNumbers)})</em>` : ""}</li>`
              ).join("") + `</ul>`
            : `<span>${escapeHtml(t.diagnosisSummary || t.prescriptionDiagnosis || "Chưa ghi nhận chẩn đoán")}</span>`;

        const instructions = t.treatmentInstructions || t.prescriptionAdvice || t.visitNotes || "Chưa có lời dặn sau điều trị";

        const medsHtml = (Array.isArray(t.prescriptionItems) && t.prescriptionItems.length)
            ? `<ul style="margin:4px 0 0 18px;padding:0;">` + t.prescriptionItems.map(m =>
                `<li><strong>${escapeHtml(m.drugName)}</strong> — SL: ${escapeHtml(m.quantity)} ${escapeHtml(m.unit || "")} · Liều dùng: ${escapeHtml(m.dosage || "")} (${escapeHtml(m.usageInstruction || "")})</li>`
              ).join("") + `</ul>`
            : (t.prescriptionSummary ? `<span>${escapeHtml(t.prescriptionSummary)}</span>` : null);

        box.hidden = false;
        box.innerHTML = `
            <div style="display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:8px;border-bottom:1px solid #e2e8f0;padding-bottom:8px;">
                <div>
                    <strong style="font-size:14px;color:#0f172a;">Dữ liệu Khám lâm sàng, Chẩn đoán &amp; Lời dặn điều trị</strong>
                    ${t.doctorName ? `<span style="margin-left:8px;font-size:12.5px;color:#475569;">· Bác sĩ điều trị: <strong>${escapeHtml(t.doctorName)}</strong></span>` : ""}
                </div>
                ${examUrl ? `
                    <a href="${escapeHtml(examUrl)}" target="_blank" rel="noopener" class="button button-secondary button-sm" style="text-decoration:none;">
                        Xem Hồ sơ Khám &amp; Lời dặn
                    </a>
                ` : ""}
            </div>
            <div style="font-size:13px;line-height:1.55;color:#1e293b;">
                ${t.chiefComplaint || t.visitSymptoms ? `<div>• <strong>Lý do khám / Triệu chứng ban đầu:</strong> ${escapeHtml(t.chiefComplaint || t.visitSymptoms)}</div>` : ""}
                <div style="margin-top:4px;">• <strong>Chẩn đoán lâm sàng:</strong> ${diagHtml}</div>
                <div style="margin-top:6px;padding:8px 10px;background:#e0f2fe;border-left:4px solid #0284c7;border-radius:4px;">
                    <strong>Lời dặn / Hướng dẫn chăm sóc sau điều trị:</strong><br>
                    <span>${escapeHtml(instructions)}</span>
                </div>
                ${medsHtml ? `<div style="margin-top:6px;">• <strong>Đơn thuốc (${escapeHtml(t.prescriptionCode || "Toa thuốc")}):</strong> ${medsHtml}</div>` : ""}
                ${t.followupDate ? `<div style="margin-top:4px;">• <strong>Ngày hẹn tái khám theo đơn:</strong> <strong>${escapeHtml(t.followupDate)}</strong></div>` : ""}
            </div>
        `;
    }

    function renderInteractionTaskHeaderAndAlert(t) {
        $("interactionTaskMeta").innerHTML = `
            <div class="detail-meta-item"><span>Mã công việc</span><strong class="code">${escapeHtml(t.taskCode)}</strong></div>
            <div class="detail-meta-item"><span>Bệnh nhân</span><strong>${escapeHtml(t.patientName)} (${escapeHtml(t.patientCode)})</strong></div>
            <div class="detail-meta-item"><span>Số điện thoại</span><strong>${escapeHtml(t.patientPhone || "—")}</strong></div>
            <div class="detail-meta-item"><span>Phân nhóm CSKH</span><strong>${escapeHtml(t.careGroupLabel)}</strong></div>
            <div class="detail-meta-item"><span>Hạn hoàn thành</span><strong>${escapeHtml(t.dueDate || "—")}</strong></div>
            <div class="detail-meta-item"><span>Trạng thái hiện tại</span><strong>${escapeHtml(t.statusLabel)}</strong></div>
            <div class="detail-meta-item"><span>Ca khám / Đơn thuốc</span><strong>${escapeHtml(t.visitCode || "—")} ${t.prescriptionCode ? " · " + escapeHtml(t.prescriptionCode) : ""}</strong></div>
            <div class="detail-meta-item"><span>Lịch hẹn liên kết</span><strong>${escapeHtml(t.appointmentCode ? `${t.appointmentCode} (${t.appointmentDate || ""})` : "—")}</strong></div>
            <div class="detail-meta-item"><span>Nhân viên phụ trách</span><strong>${escapeHtml(t.assignedStaffName || "Chưa gán")}</strong></div>
        `;

        renderInteractionExaminationBox(t);

        const docAlert = $("interactionDoctorAlert");
        if (t.doctorResponse || t.status === "ESCALATED_TO_DOCTOR" || t.escalationReason) {
            docAlert.hidden = false;
            docAlert.innerHTML = `
                <div><strong>Thông tin chuyển Bác sĩ chuyên môn (${escapeHtml(t.escalatedToDoctorName || "Bác sĩ")}):</strong></div>
                <div style="margin-top:4px;">• <strong>Ghi chú CSKH ban đầu:</strong> ${escapeHtml(t.notes || "—")}</div>
                ${t.escalationReason ? `<div style="margin-top:4px;">• <strong>Lý do chuyển Bác sĩ:</strong> ${escapeHtml(t.escalationReason)}</div>` : ""}
                <div style="margin-top:4px;">• <strong>Chỉ định/Phản hồi của Bác sĩ:</strong> ${
                    t.doctorResponse
                        ? `<strong>${escapeHtml(t.doctorResponse)}</strong>`
                        : '<em>Đang chờ Bác sĩ nhập phản hồi chuyên môn...</em>'
                }</div>
            `;
        } else {
            docAlert.hidden = true;
        }
    }

    function applyInteractionModalLockState(t) {
        const form = $("interactionForm");
        if (!form) return;
        const closed = isTaskClosed(t);

        let notice = $("interactionClosedNotice");
        if (!notice) {
            notice = document.createElement("div");
            notice.id = "interactionClosedNotice";
            notice.className = "cc-closed-notice";
            form.parentNode.insertBefore(notice, form);
        }

        form.hidden = closed;
        const saveBtn = $("saveInteractionBtn");
        if (saveBtn) saveBtn.disabled = closed;
        const escBtn = $("switchToEscalateFromIntBtn");
        if (escBtn) escBtn.disabled = closed;

        if (closed) {
            const isDone = t.status === "COMPLETED";
            notice.hidden = false;
            notice.innerHTML = `
                <div class="d-flex align-items-start justify-content-between gap-2 flex-wrap">
                    <div>
                        <strong><i class="bi bi-lock-fill me-1"></i>Công việc ${escapeHtml(t.taskCode)} ${isDone ? "đã hoàn thành và được đóng" : "đã bị hủy"}.</strong>
                        <div class="small mt-1">Không thể ghi nhận thêm kết quả liên hệ, chuyển Bác sĩ hoặc chỉnh sửa. Bạn vẫn có thể xem chi tiết và lịch sử tương tác bên dưới.</div>
                    </div>
                    ${canReopenTask(t) ? `<button type="button" class="btn btn-outline-warning btn-sm fw-bold" data-reopen-task="${t.taskId}"><i class="bi bi-arrow-counterclockwise me-1"></i>Mở lại công việc</button>` : ""}
                </div>
            `;
        } else {
            notice.hidden = true;
            notice.innerHTML = "";
        }
    }

    async function openInteractionModal(taskId) {
        try {
            const t = await request(`/api/customer-care/tasks/${taskId}`);
            state.currentTaskDetail = t;

            $("intTaskId").value = t.taskId;
            $("interactionModalTitle").textContent = isTaskClosed(t)
                ? `Chi tiết công việc (chỉ xem): ${t.taskCode}`
                : `Chi tiết công việc & Ghi kết quả liên hệ: ${t.taskCode}`;

            renderInteractionTaskHeaderAndAlert(t);

            $("interactionForm").reset();
            $("intTaskId").value = t.taskId;
            $("intChannel").value = "PHONE_CALL";
            $("intContactResult").value = "CONNECTED";
            updateRetryFieldRequirement();
            applyInteractionModalLockState(t);

            renderTaskInteractionsList(t.interactions || []);
            $("interactionModal").hidden = false;
            const scrollBody = document.querySelector("#interactionModal .modal-body-scroll");
            if (scrollBody) scrollBody.scrollTop = 0;
        } catch (err) {
            showMessage(err.message, "error");
        }
    }

    async function refreshOpenInteractionModalIfMatches(taskId) {
        const modal = $("interactionModal");
        if (!modal || modal.hidden) return;
        if (String($("intTaskId")?.value || "") !== String(taskId || "")) return;
        try {
            const t = await request(`/api/customer-care/tasks/${taskId}`);
            state.currentTaskDetail = t;
            renderInteractionTaskHeaderAndAlert(t);
            applyInteractionModalLockState(t);
            renderTaskInteractionsList(t.interactions || []);
        } catch {
            // ignore silent background sync error
        }
    }

    function renderTaskInteractionsList(interactions) {
        const container = $("taskInteractionsList");
        if (!container) return;
        container.innerHTML = renderVerticalConnectedTimeline(interactions, {
            idPrefix: "vtl-task",
            isGeneral: false,
            totalCount: interactions ? interactions.length : 0,
            emptyText: "Chưa có lần liên hệ nào được ghi nhận cho công việc này."
        });
    }

    async function submitInteractionForm(event) {
        event.preventDefault();
        const taskId = $("intTaskId").value;
        const contactResult = $("intContactResult").value;
        const nextRetryRaw = $("intNextRetryAt").value;
        const notes = $("intNotes").value.trim();

        if (!notes || notes.length < 3) {
            showMessage("Vui lòng nhập nội dung ghi chú kết quả liên hệ (tối thiểu 3 ký tự).", "warning");
            $("intNotes").focus();
            return;
        }

        if ((contactResult === "NO_ANSWER" || contactResult === "BUSY") && !nextRetryRaw) {
            showMessage("Khi kết quả liên hệ là Không bắt máy hoặc Máy bận, bắt buộc phải chọn Thời gian hẹn gọi lại (next_retry_at)!", "warning");
            $("intNextRetryAt").focus();
            return;
        }

        const payload = {
            channel: $("intChannel").value,
            contactResult,
            notes,
            nextRetryAt: nextRetryRaw ? (nextRetryRaw.length === 16 ? `${nextRetryRaw}:00` : nextRetryRaw) : null,
            nextStatus: $("intNextStatus").value
        };

        const btn = $("saveInteractionBtn");
        btn.disabled = true;
        try {
            const updated = await request(`/api/customer-care/tasks/${taskId}/interactions`, {
                method: "POST",
                body: JSON.stringify(payload)
            });
            $("interactionModal").hidden = true;
            showMessage(`Đã lưu kết quả liên hệ cho công việc ${updated.taskCode} thành công!`, "success");
            await Promise.all([
                loadSummary(),
                state.viewMode === "CALENDAR" ? loadCalendar() : loadTasks(state.page)
            ]);
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            btn.disabled = false;
        }
    }

    // =========================================================================
    // 6. CHUYỂN BÁC SĨ HỖ TRỢ CHUYÊN MÔN & PHẢN HỒI BÁC SĨ (SCR-44.5)
    // =========================================================================

    async function openEscalateModal(taskId) {
        try {
            const t = await request(`/api/customer-care/tasks/${taskId}`);
            if (isTaskClosed(t)) {
                showMessage(`Công việc ${t.taskCode} đã đóng (${t.statusLabel || t.status}), không thể chuyển Bác sĩ.`, "warning");
                return;
            }
            $("escalateForm").reset();
            $("escTaskId").value = t.taskId;
            $("escTaskSummary").innerHTML = `
                <div><strong>Mã công việc:</strong> <span class="code">${escapeHtml(t.taskCode)}</span> · <strong>Bệnh nhân:</strong> ${escapeHtml(t.patientName)} (${escapeHtml(t.patientPhone || "")})</div>
                <div style="margin-top:4px;"><strong>Nhóm CSKH:</strong> ${escapeHtml(t.careGroupLabel)} ${t.visitCode ? `· Ca khám: <strong>${escapeHtml(t.visitCode)}</strong>` : ""} ${t.prescriptionCode ? `· Đơn thuốc: <strong>${escapeHtml(t.prescriptionCode)}</strong>` : ""}</div>
                ${t.diagnosisSummary ? `<div style="margin-top:4px;"><strong>Chẩn đoán:</strong> ${escapeHtml(t.diagnosisSummary)}</div>` : ""}
                ${t.treatmentInstructions ? `<div style="margin-top:4px;"><strong>Lời dặn điều trị:</strong> ${escapeHtml(t.treatmentInstructions)}</div>` : ""}
            `;
            if (t.escalatedToDoctorId) {
                $("escDoctorId").value = String(t.escalatedToDoctorId);
            } else if (t.doctorId) {
                $("escDoctorId").value = String(t.doctorId);
            } else if (state.lookups.doctorList.length > 0) {
                $("escDoctorId").value = String(state.lookups.doctorList[0].staffId);
            }
            $("escReason").value = t.escalationReason || "";
            $("escalateModal").hidden = false;
        } catch (err) {
            showMessage(err.message, "error");
        }
    }

    async function submitEscalateForm(event) {
        event.preventDefault();
        const taskId = $("escTaskId").value;
        const doctorId = Number($("escDoctorId").value);
        const reason = $("escReason").value.trim();

        if (!doctorId) {
            showMessage("Vui lòng chọn Bác sĩ tiếp nhận hỗ trợ chuyên môn.", "warning");
            return;
        }
        if (!reason || reason.length < 5) {
            showMessage("Vui lòng mô tả triệu chứng / lý do cần Bác sĩ tư vấn (tối thiểu 5 ký tự).", "warning");
            $("escReason").focus();
            return;
        }

        const btn = $("saveEscalateBtn");
        btn.disabled = true;
        try {
            const updated = await request(`/api/customer-care/tasks/${taskId}/escalate`, {
                method: "POST",
                body: JSON.stringify({
                    escalatedToDoctorId: doctorId,
                    escalationReason: reason
                })
            });
            $("escalateModal").hidden = true;
            showMessage(`Đã chuyển công việc ${updated.taskCode} cho Bác sĩ ${updated.escalatedToDoctorName} xử lý chuyên môn.`, "success");
            await Promise.all([
                loadSummary(),
                state.viewMode === "CALENDAR" ? loadCalendar() : loadTasks(state.page)
            ]);
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            btn.disabled = false;
        }
    }

    async function openDoctorResponseModal(taskId) {
        try {
            const t = await request(`/api/customer-care/tasks/${taskId}`);
            if (isTaskClosed(t)) {
                showMessage(`Công việc ${t.taskCode} đã đóng (${t.statusLabel || t.status}), không thể cập nhật phản hồi chuyên môn.`, "warning");
                return;
            }
            $("doctorResponseForm").reset();
            $("docRespTaskId").value = t.taskId;
            const examUrl = t.examinationDetailUrl || (t.visitId ? `/doctor/examination/${t.visitId}/diagnoses#tab-instructions` : null);
            $("docRespTaskSummary").innerHTML = `
                <div><strong>Mã CV:</strong> <span class="code">${escapeHtml(t.taskCode)}</span> · <strong>Bệnh nhân:</strong> ${escapeHtml(t.patientName)} (${escapeHtml(t.patientPhone || "—")})</div>
                <div style="margin-top:4px;"><strong>Bác sĩ phụ trách:</strong> ${escapeHtml(t.escalatedToDoctorName || t.doctorName || "—")} · <strong>Chuyển lúc:</strong> ${escapeHtml(formatDateTime(t.escalatedAt))}</div>
                ${t.diagnosisSummary ? `<div style="margin-top:4px;"><strong>Chẩn đoán ca khám:</strong> ${escapeHtml(t.diagnosisSummary)}</div>` : ""}
                ${t.treatmentInstructions ? `<div style="margin-top:4px;"><strong>Lời dặn hiện tại:</strong> ${escapeHtml(t.treatmentInstructions)}</div>` : ""}
                ${examUrl ? `<div style="margin-top:4px;"><a href="${escapeHtml(examUrl)}" target="_blank" rel="noopener">Mở hồ sơ khám &amp; lời dặn</a></div>` : ""}
                <div style="margin-top:6px;padding:8px;background:#fff;border-radius:6px;border:1px solid #cbd5e1;">
                    <div><strong>Ghi chú CSKH ban đầu:</strong> ${escapeHtml(t.notes || "—")}</div>
                    ${t.escalationReason ? `<div style="margin-top:4px;color:#9a3412;"><strong>Lý do chuyển Bác sĩ:</strong> ${escapeHtml(t.escalationReason)}</div>` : ""}
                </div>
            `;
            $("docResponseText").value = t.doctorResponse || "";
            $("docNextStatus").value = "ESCALATED_TO_DOCTOR";
            $("doctorResponseModal").hidden = false;
        } catch (err) {
            showMessage(err.message, "error");
        }
    }

    async function submitDoctorResponseForm(event) {
        event.preventDefault();
        const taskId = $("docRespTaskId").value;
        const responseText = $("docResponseText").value.trim();
        const nextStatus = $("docNextStatus").value;

        if (!responseText || responseText.length < 5) {
            showMessage("Vui lòng nhập nội dung chỉ định / phản hồi chuyên môn của Bác sĩ (ít nhất 5 ký tự).", "warning");
            $("docResponseText").focus();
            return;
        }

        const btn = $("saveDoctorResponseBtn");
        btn.disabled = true;
        try {
            const updated = await request(`/api/customer-care/tasks/${taskId}/doctor-response`, {
                method: "POST",
                body: JSON.stringify({
                    doctorResponse: responseText,
                    nextStatus
                })
            });
            $("doctorResponseModal").hidden = true;
            showMessage(`Đã lưu phản hồi chuyên môn của Bác sĩ cho công việc ${updated.taskCode}.`, "success");
            await Promise.all([
                loadSummary(),
                state.viewMode === "CALENDAR" ? loadCalendar() : loadTasks(state.page)
            ]);
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            btn.disabled = false;
        }
    }

    // =========================================================================
    // 6B. MỞ LẠI CÔNG VIỆC ĐÃ HOÀN THÀNH (CHỈ ADMIN) - BẮT BUỘC NHẬP LÝ DO
    // =========================================================================

    function openReopenModal(taskId) {
        const t = state.tasksById[String(taskId)];
        if (!t) return;
        if (state.careRole !== "ADMIN") {
            showMessage("Chỉ Quản trị viên (Admin) mới có quyền mở lại công việc CSKH đã hoàn thành.", "error");
            return;
        }
        if (t.status !== "COMPLETED") {
            showMessage("Chỉ có thể mở lại công việc đang ở trạng thái Đã hoàn thành.", "warning");
            return;
        }
        $("reopenForm").reset();
        $("reopenTaskId").value = String(t.taskId);
        if ($("reopenTaskSummary")) {
            $("reopenTaskSummary").innerHTML = `
                <div><strong>Mã CV:</strong> ${escapeHtml(t.taskCode)} · <strong>Bệnh nhân:</strong> ${escapeHtml(t.patientName)} (${escapeHtml(t.patientCode)})</div>
                <div class="small text-muted mt-1">Trạng thái hiện tại: <span class="badge bg-success-subtle text-success">Đã hoàn thành</span> · Người mở lại: Quản trị viên</div>
            `;
        }
        $("reopenModal").hidden = false;
        $("reopenReason")?.focus();
    }

    async function submitReopenForm(event) {
        event.preventDefault();
        const taskId = $("reopenTaskId").value;
        const reason = $("reopenReason").value.trim();

        if (!reason || reason.length < 5) {
            showMessage("Vui lòng nhập lý do mở lại công việc (tối thiểu 5 ký tự).", "warning");
            $("reopenReason")?.focus();
            return;
        }

        const saveBtn = $("saveReopenBtn");
        if (saveBtn) {
            saveBtn.disabled = true;
            saveBtn.textContent = "Đang xử lý...";
        }

        try {
            const updated = await request(`/api/customer-care/tasks/${taskId}/reopen`, {
                method: "POST",
                body: JSON.stringify({ reason })
            });
            $("reopenModal").hidden = true;
            showMessage(`Đã mở lại công việc ${updated.taskCode || ""}. Công việc đã được chuyển về danh sách Cần xử lý.`, "success");

            // Đóng Task Detail Modal nếu đang mở
            if ($("interactionModal") && !$("interactionModal").hidden) {
                $("interactionModal").hidden = true;
            }

            await Promise.all([
                loadSummary(),
                loadTasks(state.page),
                state.viewMode === "CALENDAR" ? loadCalendar() : Promise.resolve()
            ]);
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            if (saveBtn) {
                saveBtn.disabled = false;
                saveBtn.textContent = "Xác nhận mở lại";
            }
        }
    }

    // =========================================================================
    // 7. LỊCH SỬ CHĂM SÓC BỆNH NHÂN TIMELINE (SCR-44.6)
    // =========================================================================

    async function loadPatientHistory(profileId) {
        if (!profileId) {
            showMessage("Vui lòng chọn bệnh nhân để xem lịch sử chăm sóc.", "warning");
            return;
        }
        $("historyPatientSelect").value = String(profileId);
        $("patientTimelineContainer").innerHTML = '<p class="empty">Đang tải timeline tương tác...</p>';
        $("patientTasksContainer").innerHTML = '<p class="empty">Đang tải danh sách công việc...</p>';

        try {
            const data = await request(`/api/customer-care/patients/${profileId}/history`);
            const p = data?.patient || {};
            const interactions = Array.isArray(data?.interactions) ? data.interactions : [];
            const tasks = Array.isArray(data?.tasks) ? data.tasks : [];

            const summaryCard = $("historyPatientSummary");
            summaryCard.hidden = false;
            summaryCard.innerHTML = `
                <div class="meta-box"><span>Mã bệnh nhân</span><strong class="code">${escapeHtml(p.patientCode)}</strong></div>
                <div class="meta-box"><span>Họ và tên</span><strong>${escapeHtml(p.fullName)}</strong></div>
                <div class="meta-box"><span>Số điện thoại</span><strong>${escapeHtml(p.phoneNumber || "—")}</strong></div>
                <div class="meta-box"><span>Ngày sinh / Giới tính</span><strong>${escapeHtml(p.dob || "—")} (${escapeHtml(p.gender || "—")})</strong></div>
                <div class="meta-box"><span>Địa chỉ</span><strong>${escapeHtml(p.address || "—")}</strong></div>
                <div class="meta-box"><span>Tổng tương tác CSKH</span><strong>${interactions.length} lần tương tác / ${tasks.length} công việc</strong></div>
            `;

            $("timelineCount").textContent = interactions.length;
            $("patientTasksCount").textContent = tasks.length;

            $("patientTimelineContainer").innerHTML = renderCompactTimeline(interactions, {
                idPrefix: "tl-hist",
                showGroup: true,
                emptyText: "Bệnh nhân này chưa có lịch sử tương tác liên hệ nào."
            });

            $("patientTasksContainer").innerHTML = tasks.length ? tasks.map(t => {
                const examUrl = t.examinationDetailUrl || (t.visitId ? `/doctor/examination/${t.visitId}/diagnoses#tab-instructions` : null);
                const closed = isTaskClosed(t);
                return `
                <div class="patient-task-card ${closed ? "is-closed" : ""}">
                    <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:6px;">
                        <div class="text-truncate">
                            <span class="code">${escapeHtml(t.taskCode)}</span> ·
                            <span class="badge ${getCareGroupBadgeClass(t.careGroup)}">${escapeHtml(t.careGroupLabel)}</span>
                        </div>
                        <span class="badge ${getStatusBadgeClass(t.status)} text-nowrap">${escapeHtml(t.statusLabel)}</span>
                    </div>
                    <div class="cc-clamp-2" style="font-size:13px;margin-bottom:6px;" title="${escapeHtml(t.notes || "")}">${escapeHtml(t.notes || "—")}</div>
                    ${t.doctorResponse ? `<div class="doc-reply-snippet cc-clamp-1" title="${escapeHtml(t.doctorResponse)}"><strong>Phản hồi BS ${escapeHtml(t.escalatedToDoctorName || "")}:</strong> ${escapeHtml(t.doctorResponse)}</div>` : ""}
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px;font-size:12px;color:#64748b;flex-wrap:wrap;gap:6px;">
                        <span>Hạn: <strong>${escapeHtml(t.dueDate || "—")}</strong> · Phụ trách: <strong>${escapeHtml(t.assignedStaffName || "—")}</strong> · ${Number(t.interactionCount || 0)} lần tương tác</span>
                        <div style="display:flex;gap:6px;">
                            ${examUrl ? `<a href="${escapeHtml(examUrl)}" target="_blank" rel="noopener" class="button button-secondary button-sm">Hồ sơ khám</a>` : ""}
                            <button type="button" class="button ${closed ? "button-secondary" : "button-primary"} button-sm" data-record-interaction="${t.taskId}">${closed ? "Xem chi tiết" : "Chi tiết / Ghi KQ"}</button>
                        </div>
                    </div>
                </div>
            `;
            }).join("") : '<p class="empty">Chưa có công việc CSKH cho bệnh nhân này.</p>';
        } catch (err) {
            showMessage(err.message, "error");
        }
    }

    // =========================================================================
    // 8. CHUYỂN ĐỔI TABS, VIEW MODE (DANH SÁCH / Ô LỊCH) & BỘ LỌC
    // =========================================================================

    function updateSubFilterStripsVisibility() {
        const isHistory = state.activeTab === "HISTORY";
        const isCalendar = state.viewMode === "CALENDAR";
        if ($("dueFilterStrip")) {
            $("dueFilterStrip").hidden = isHistory || isCalendar || state.activeTab !== "ACTIONABLE";
        }
        if ($("doctorRespFilterStrip")) {
            $("doctorRespFilterStrip").hidden = isHistory || isCalendar || state.activeTab !== "ESCALATED";
        }
    }

    function switchViewMode(mode) {
        state.viewMode = mode === "CALENDAR" ? "CALENDAR" : "LIST";
        if ($("viewModeListBtn")) $("viewModeListBtn").classList.toggle("active", state.viewMode === "LIST");
        if ($("viewModeCalendarBtn")) $("viewModeCalendarBtn").classList.toggle("active", state.viewMode === "CALENDAR");

        if (state.viewMode === "CALENDAR" && state.activeTab === "HISTORY") {
            state.activeTab = "ACTIONABLE";
            document.querySelectorAll(".tab").forEach(btn => {
                btn.classList.toggle("active", btn.dataset.tab === "ACTIONABLE");
            });
        }

        const isHistory = state.activeTab === "HISTORY";
        if ($("tasksPanel")) $("tasksPanel").hidden = state.viewMode !== "LIST" || isHistory;
        if ($("calendarPanel")) $("calendarPanel").hidden = state.viewMode !== "CALENDAR";
        if ($("historyPanel")) $("historyPanel").hidden = state.viewMode !== "LIST" || !isHistory;

        updateSubFilterStripsVisibility();

        if (state.viewMode === "CALENDAR") {
            loadCalendar();
        } else if (!isHistory) {
            loadTasks(0);
        }
    }

    function switchTab(tab) {
        state.activeTab = tab;
        document.querySelectorAll(".tab").forEach(btn => {
            btn.classList.toggle("active", btn.dataset.tab === tab);
        });

        const isHistory = tab === "HISTORY";
        if (isHistory && state.viewMode === "CALENDAR") {
            state.viewMode = "LIST";
            if ($("viewModeListBtn")) $("viewModeListBtn").classList.add("active");
            if ($("viewModeCalendarBtn")) $("viewModeCalendarBtn").classList.remove("active");
        }

        if ($("tasksPanel")) $("tasksPanel").hidden = isHistory || state.viewMode === "CALENDAR";
        if ($("calendarPanel")) $("calendarPanel").hidden = state.viewMode !== "CALENDAR" || isHistory;
        if ($("historyPanel")) $("historyPanel").hidden = !isHistory;

        document.querySelectorAll(".task-filter-field").forEach(el => {
            el.style.display = isHistory ? "none" : "";
        });

        updateSubFilterStripsVisibility();

        if (!isHistory) {
            const meta = TAB_META[tab] || TAB_META.ACTIONABLE;
            if ($("tasksPanelTitle")) $("tasksPanelTitle").textContent = meta.title || "";
            if ($("tasksPanelSubtitle")) $("tasksPanelSubtitle").textContent = meta.subtitle || "";
            if (state.viewMode === "CALENDAR") {
                loadCalendar();
            } else {
                loadTasks(0);
            }
        } else {
            loadGeneralHistory(0);
        }
    }

    // =========================================================================
    // 9. LỊCH CÔNG VIỆC CSKH (TUẦN / THÁNG) - ĐỒNG BỘ PHONG CÁCH /reception/appointments
    // =========================================================================

    function getCalendarRange() {
        const ref = parseYmdUtc(state.calRefDate);
        if (state.calMode === "month") {
            const year = ref.getUTCFullYear();
            const month = ref.getUTCMonth();
            const firstOfMonth = new Date(Date.UTC(year, month, 1));
            const lastOfMonth = new Date(Date.UTC(year, month + 1, 0));
            const firstDow = (firstOfMonth.getUTCDay() + 6) % 7; // Mon=0..Sun=6
            const gridStart = formatUtcDateYmd(new Date(Date.UTC(year, month, 1 - firstDow)));
            const lastDow = (lastOfMonth.getUTCDay() + 6) % 7;
            const gridEnd = formatUtcDateYmd(new Date(Date.UTC(year, month + 1, 6 - lastDow)));
            return {
                startDate: gridStart,
                endDate: gridEnd,
                monthIndex: month,
                year,
                title: `Tháng ${month + 1}/${year}`
            };
        }
        const dow = (ref.getUTCDay() + 6) % 7; // Mon=0..Sun=6
        const monday = addDaysYmd(formatUtcDateYmd(ref), -dow);
        const sunday = addDaysYmd(monday, 6);
        return {
            startDate: monday,
            endDate: sunday,
            title: `Tuần ${formatYmdVi(monday)} – ${formatYmdVi(sunday)}`
        };
    }

    async function loadCalendar() {
        const grid = $("ccCalendarGrid");
        if (!grid) return;

        const range = getCalendarRange();
        if ($("calRangeTitle")) $("calRangeTitle").textContent = range.title;
        if ($("calWeekBtn")) $("calWeekBtn").classList.toggle("active", state.calMode === "week");
        if ($("calMonthBtn")) $("calMonthBtn").classList.toggle("active", state.calMode === "month");

        grid.innerHTML = '<div class="p-4 text-center text-muted" style="grid-column:1 / -1;">Đang tải lịch công việc CSKH...</div>';

        if (state.calendarAbortController) {
            state.calendarAbortController.abort();
        }
        const controller = new AbortController();
        state.calendarAbortController = controller;

        const params = new URLSearchParams({
            startDate: range.startDate,
            endDate: range.endDate
        });
        if (state.branchId) params.set("branchId", state.branchId);
        if (state.myTasksOnly) params.set("myTasksOnly", "true");
        if ($("calFilterStaff")?.value) params.set("assignedStaffId", $("calFilterStaff").value);
        if ($("calFilterDoctor")?.value) params.set("escalatedToDoctorId", $("calFilterDoctor").value);
        if ($("calFilterCareGroup")?.value) params.set("careGroupFilter", $("calFilterCareGroup").value);
        if ($("calFilterStatus")?.value) params.set("status", $("calFilterStatus").value);

        try {
            const list = await request(`/api/customer-care/calendar?${params}`, { signal: controller.signal });
            state.calendarTasks = Array.isArray(list) ? list : [];
            state.calendarTasks.forEach(t => {
                if (t && t.taskId != null) state.tasksById[String(t.taskId)] = t;
            });
            renderCalendarGrid(range);
        } catch (err) {
            if (err.name === "AbortError") return;
            grid.innerHTML = `<div class="p-4 text-center text-danger" style="grid-column:1 / -1;">${escapeHtml(err.message)}</div>`;
        }
    }

    /**
     * Màu card thống nhất giữa Danh sách & Lịch - xác định theo TRẠNG THÁI trước, rồi mới tới hạn xử lý:
     * Hoàn thành (xanh lá) > Đã hủy (xám) > Chờ Bác sĩ (tím) > Quá hạn (đỏ) > Hôm nay (cam) > Sắp tới (xanh nhạt).
     */
    function getCalCardStatusClass(t) {
        if (t.status === "COMPLETED") return "cc-cal-completed";
        if (t.status === "CANCELLED") return "cc-cal-cancelled";
        if (t.status === "ESCALATED_TO_DOCTOR") return "cc-cal-escalated";
        const today = getVietnamTodayYmd();
        const due = t.dueDate || "";
        if (t.dueBucket === "OVERDUE" || (due && due < today)) return "cc-cal-overdue";
        if (t.dueBucket === "TODAY" || due === today) return "cc-cal-today";
        return "cc-cal-upcoming";
    }

    function getCalStatusPillClass(status) {
        switch (status) {
            case "PENDING": return "cc-pill-pending";
            case "IN_PROGRESS": return "cc-pill-progress";
            case "ESCALATED_TO_DOCTOR": return "cc-pill-escalated";
            case "COMPLETED": return "cc-pill-completed";
            case "CANCELLED": return "cc-pill-cancelled";
            default: return "cc-pill-pending";
        }
    }

    function shortTaskCode(code) {
        const s = String(code || "");
        return s.length > 12 ? "…" + s.slice(-10) : s;
    }

    function renderCalendarCard(t, extraClass = "", dataAttr = "data-cal-task-id") {
        const timeText = t.nextRetryAt && !isTaskClosed(t) ? String(t.nextRetryAt).replace("T", " ").slice(11, 16) : "";
        return `
            <button type="button" class="cc-cal-card ${getCalCardStatusClass(t)} ${extraClass}" ${dataAttr}="${t.taskId}"
                    title="${escapeHtml(`${t.taskCode} · ${t.patientName || ""} · ${t.careGroupLabel || ""} (${t.statusLabel || ""})`)}">
                <div class="d-flex align-items-center justify-content-between gap-1">
                    <span class="cc-cal-card-code">${escapeHtml(shortTaskCode(t.taskCode))}</span>
                    <span class="cc-cal-pill ${getCalStatusPillClass(t.status)}">${escapeHtml(t.statusLabel || statusLabelOf(t.status))}</span>
                </div>
                <div class="cc-cal-card-patient">${escapeHtml(t.patientName || "—")}</div>
                <div class="cc-cal-card-meta">${escapeHtml(t.careGroupLabel || "")}</div>
                <div class="cc-cal-card-meta"><i class="bi bi-person me-1"></i>${escapeHtml(t.assignedStaffName || "Chưa gán")}${timeText ? ` · <i class="bi bi-telephone-forward"></i> ${escapeHtml(timeText)}` : ""}</div>
            </button>
        `;
    }

    function renderCalendarGrid(range) {
        const grid = $("ccCalendarGrid");
        if (!grid) return;

        const dayHeaders = ["Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7", "Chủ nhật"];
        const headerHtml = dayHeaders.map(d => `<div class="cc-cal-header">${d}</div>`).join("");

        const tasksByDate = {};
        state.calendarTasks.forEach(t => {
            const d = t.dueDate || (t.nextRetryAt ? String(t.nextRetryAt).slice(0, 10) : null);
            if (!d) return;
            if (!tasksByDate[d]) tasksByDate[d] = [];
            tasksByDate[d].push(t);
        });

        const todayYmd = getVietnamTodayYmd();
        const isWeek = state.calMode !== "month";
        const maxCards = isWeek ? 4 : 2;
        const cells = [];
        let cursor = range.startDate;
        let guard = 0;
        while (cursor <= range.endDate && guard < 42) {
            guard++;
            const dayTasks = tasksByDate[cursor] || [];
            const dateObj = parseYmdUtc(cursor);
            const dayNum = dateObj.getUTCDate();
            const isOutside = !isWeek && dateObj.getUTCMonth() !== range.monthIndex;
            const isToday = cursor === todayYmd;

            const shown = dayTasks.slice(0, maxCards);
            const remaining = dayTasks.length - shown.length;
            const cardsHtml = shown.map(t => renderCalendarCard(t)).join("");
            const moreBtn = remaining > 0
                ? `<button type="button" class="cc-cal-more-btn" data-cal-more-date="${cursor}">+ ${remaining} công việc khác</button>`
                : "";
            const dayLabel = isWeek
                ? `${String(dayNum).padStart(2, "0")}/${String(dateObj.getUTCMonth() + 1).padStart(2, "0")}`
                : (dayNum === 1 ? `${dayNum}/${dateObj.getUTCMonth() + 1}` : String(dayNum));

            cells.push(`
                <div class="cc-cal-day ${isWeek ? "is-week" : ""} ${isToday ? "is-today" : ""} ${isOutside ? "is-outside" : ""}">
                    <div class="cc-cal-day-head">
                        <span class="cc-cal-day-num">${dayLabel}${isToday ? ' <small class="cc-cal-today-tag">Hôm nay</small>' : ""}</span>
                        ${dayTasks.length > 0 ? `<span class="cc-cal-day-count">${dayTasks.length}</span>` : ""}
                    </div>
                    ${cardsHtml}
                    ${moreBtn}
                    ${!dayTasks.length && isWeek ? '<div class="cc-cal-empty">Không có công việc</div>' : ""}
                </div>
            `);
            cursor = addDaysYmd(cursor, 1);
        }

        grid.innerHTML = headerHtml + cells.join("");
    }

    function openDayTasksModal(ymd) {
        const modal = $("ccDayTasksModal");
        const listEl = $("ccDayTasksModalList");
        if (!modal || !listEl) return;

        const dayTasks = state.calendarTasks.filter(t => (t.dueDate || (t.nextRetryAt ? String(t.nextRetryAt).slice(0, 10) : "")) === ymd);
        if ($("ccDayTasksModalTitle")) $("ccDayTasksModalTitle").textContent = `Công việc CSKH ngày ${formatYmdVi(ymd)}`;
        if ($("ccDayTasksModalSub")) $("ccDayTasksModalSub").textContent = `Tổng cộng ${dayTasks.length} công việc`;

        listEl.innerHTML = dayTasks.length
            ? `<div class="cc-day-modal-list">${dayTasks.map(t => renderCalendarCard(t, "", "data-cal-modal-task-id")).join("")}</div>`
            : '<div class="text-muted text-center p-3">Không có công việc trong ngày này.</div>';
        modal.hidden = false;
    }

    // =========================================================================
    // 10. NOTIFICATION CENTER BỀN VỮNG (CHUÔNG THÔNG BÁO + READ STATE)
    // =========================================================================

    async function loadNotifications() {
        try {
            const data = await request("/api/customer-care/notifications?limit=30");
            state.notifications = Array.isArray(data?.notifications) ? data.notifications : [];
            state.unreadNotifCount = Number(data?.unreadCount ?? 0);
            renderNotificationCenter();
        } catch {
            // ignore silent notification error
        }
    }

    function renderNotificationCenter() {
        const badge = $("ccNotifUnreadBadge");
        if (badge) {
            if (state.unreadNotifCount > 0) {
                badge.textContent = state.unreadNotifCount > 99 ? "99+" : String(state.unreadNotifCount);
                badge.hidden = false;
            } else {
                badge.hidden = true;
            }
        }

        const list = $("ccNotifList");
        if (!list) return;
        if (!state.notifications.length) {
            list.innerHTML = '<div class="cc-notif-empty">Chưa có thông báo CSKH nào.</div>';
            return;
        }

        list.innerHTML = state.notifications.map(n => `
            <button type="button" class="cc-notif-item ${n.isRead ? "" : "is-unread"}"
                    data-notif-id="${n.notificationId}" data-notif-task="${n.taskId || ""}">
                <div class="cc-notif-item-head">
                    <span class="cc-notif-item-title">${escapeHtml(n.title || "Thông báo CSKH")}</span>
                    <span class="cc-notif-item-time">${escapeHtml(formatDateTime(n.createdAt))}</span>
                </div>
                <div class="cc-notif-item-msg">${escapeHtml(n.message || "")}</div>
            </button>
        `).join("");
    }

    async function handleNotificationClick(notificationId, taskId) {
        try {
            const target = state.notifications.find(n => String(n.notificationId) === String(notificationId));
            if (target && !target.isRead) {
                await request(`/api/customer-care/notifications/${notificationId}/read`, { method: "POST" });
                target.isRead = true;
                state.unreadNotifCount = Math.max(0, state.unreadNotifCount - 1);
                renderNotificationCenter();
            }
        } catch {
            // ignore
        }

        if ($("ccNotifDropdown")) $("ccNotifDropdown").hidden = true;
        if (taskId) {
            await openInteractionModal(Number(taskId));
        }
    }

    async function markAllNotificationsRead() {
        try {
            await request("/api/customer-care/notifications/read-all", { method: "POST" });
            state.notifications.forEach(n => { n.isRead = true; });
            state.unreadNotifCount = 0;
            renderNotificationCenter();
        } catch (err) {
            showMessage(err.message, "error");
        }
    }

    // =========================================================================
    // 11. REAL-TIME WEBSOCKET STOMP (/ws-chat) + TOAST + LIGHT FALLBACK SYNC
    // =========================================================================

    function setWsStatusBadge(status, text) {
        const el = $("ccWsStatusBadge");
        if (!el) return;
        el.className = `cc-ws-status is-${status}`;
        el.innerHTML = `<span class="cc-ws-dot"></span><span>${escapeHtml(text)}</span>`;
    }

    function showRealtimeToast(evt) {
        const container = $("ccToastContainer");
        if (!container || !evt) return;
        const now = Date.now();
        if (now - state.lastToastAt < 1500) return;
        state.lastToastAt = now;

        while (container.children.length >= 3) {
            container.removeChild(container.firstChild);
        }

        const toast = document.createElement("div");
        toast.className = "cc-toast";
        toast.innerHTML = `
            <div class="cc-toast-title">${escapeHtml(evt.title || "Cập nhật CSKH Real-time")}</div>
            <div class="cc-toast-body">${escapeHtml(evt.summary || evt.taskCode || "")}</div>
        `;
        if (evt.taskId) {
            toast.style.cursor = "pointer";
            toast.addEventListener("click", () => {
                toast.remove();
                openInteractionModal(evt.taskId);
            });
        }
        container.appendChild(toast);
        setTimeout(() => {
            if (toast.parentNode) toast.remove();
        }, 5000);
    }

    function handleIncomingRealtimeEvent(payload) {
        let evt = null;
        try {
            evt = typeof payload === "string" ? JSON.parse(payload) : payload;
        } catch {
            return;
        }
        if (!evt) return;

        if (evt.title || evt.summary) {
            showRealtimeToast(evt);
        }

        if (evt.taskId) {
            refreshOpenInteractionModalIfMatches(evt.taskId);
        }

        clearTimeout(state.wsSyncDebounceTimer);
        state.wsSyncDebounceTimer = setTimeout(() => {
            loadSummary();
            loadNotifications();
            if (state.viewMode === "CALENDAR") {
                loadCalendar();
            } else if (state.activeTab !== "HISTORY") {
                loadTasks(state.page);
            }
        }, 350);
    }

    function subscribeWsTopics() {
        const client = state.wsClient;
        if (!client || !state.wsConnected) return;

        if (state.wsUserSub) {
            try { state.wsUserSub.unsubscribe(); } catch {}
            state.wsUserSub = null;
        }
        if (state.wsBranchSub) {
            try { state.wsBranchSub.unsubscribe(); } catch {}
            state.wsBranchSub = null;
        }

        state.wsUserSub = client.subscribe("/user/queue/customer-care/notifications", (frame) => {
            handleIncomingRealtimeEvent(frame.body);
        });

        const topic = state.branchId
            ? `/topic/customer-care/branch/${state.branchId}`
            : (state.isAdmin ? "/topic/customer-care/admin" : null);
        if (topic) {
            state.wsBranchSub = client.subscribe(topic, (frame) => {
                handleIncomingRealtimeEvent(frame.body);
            });
        }
    }

    function scheduleWsReconnect() {
        if (state.wsReconnectTimer) return;
        const delay = Math.min(30000, 1000 * Math.pow(2, state.wsReconnectAttempt));
        state.wsReconnectAttempt = Math.min(state.wsReconnectAttempt + 1, 5);
        setWsStatusBadge("reconnecting", "Đang kết nối lại...");
        state.wsReconnectTimer = setTimeout(() => {
            state.wsReconnectTimer = null;
            initWebSocket(true);
        }, delay);
    }

    function initWebSocket(isReconnect = false) {
        if (typeof SockJS === "undefined" || typeof Stomp === "undefined") {
            setWsStatusBadge("offline", "Thiếu thư viện WS");
            return;
        }

        try {
            const socket = new SockJS("/ws-chat");
            const client = Stomp.over(socket);
            client.debug = () => {};
            state.wsClient = client;

            client.connect({}, () => {
                state.wsConnected = true;
                state.wsReconnectAttempt = 0;
                setWsStatusBadge("connected", "Trực tuyến");
                subscribeWsTopics();
                if (isReconnect) {
                    refreshAllData();
                    loadNotifications();
                }
            }, () => {
                state.wsConnected = false;
                scheduleWsReconnect();
            });
        } catch {
            state.wsConnected = false;
            scheduleWsReconnect();
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        // Main status tabs
        document.querySelectorAll(".tab").forEach(btn => {
            btn.addEventListener("click", () => {
                if ($("filterStatus")) $("filterStatus").value = "";
                switchTab(btn.dataset.tab);
            });
        });

        // KPI card click shortcuts
        document.querySelectorAll("[data-kpi-tab]").forEach(card => {
            const activate = () => {
                const target = card.dataset.kpiTab;
                if ($("filterStatus")) $("filterStatus").value = "";
                if (target === "ACTIONABLE_OVERDUE") {
                    state.dueFilter = "OVERDUE";
                    document.querySelectorAll("[data-due-filter]").forEach(b => b.classList.toggle("active", b.dataset.dueFilter === "OVERDUE"));
                    switchTab("ACTIONABLE");
                } else if (target === "ACTIONABLE_TODAY") {
                    state.dueFilter = "TODAY";
                    document.querySelectorAll("[data-due-filter]").forEach(b => b.classList.toggle("active", b.dataset.dueFilter === "TODAY"));
                    switchTab("ACTIONABLE");
                } else if (target === "ACTIONABLE") {
                    state.dueFilter = "ALL";
                    document.querySelectorAll("[data-due-filter]").forEach(b => b.classList.toggle("active", b.dataset.dueFilter === "ALL"));
                    switchTab("ACTIONABLE");
                } else {
                    switchTab(target);
                }
            };
            card.addEventListener("click", activate);
            card.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    activate();
                }
            });
        });

        // View mode switcher: Danh sách | Ô lịch
        safeOn("viewModeListBtn", "click", () => switchViewMode("LIST"));
        safeOn("viewModeCalendarBtn", "click", () => switchViewMode("CALENDAR"));

        // Việc của tôi toggle
        safeOn("myTasksToggleBtn", "click", () => {
            state.myTasksOnly = !state.myTasksOnly;
            $("myTasksToggleBtn").classList.toggle("active", state.myTasksOnly);
            if (state.viewMode === "CALENDAR") {
                loadCalendar();
            } else {
                loadTasks(0);
            }
        });

        // Sub-filter strips
        document.querySelectorAll("[data-due-filter]").forEach(btn => {
            btn.addEventListener("click", () => {
                state.dueFilter = btn.dataset.dueFilter || "ALL";
                document.querySelectorAll("[data-due-filter]").forEach(b => b.classList.toggle("active", b === btn));
                loadTasks(0);
            });
        });

        document.querySelectorAll("[data-doc-resp-filter]").forEach(btn => {
            btn.addEventListener("click", () => {
                state.doctorResponseFilter = btn.dataset.docRespFilter || "ALL";
                document.querySelectorAll("[data-doc-resp-filter]").forEach(b => b.classList.toggle("active", b === btn));
                loadTasks(0);
            });
        });

        // Branch change
        safeOn("branchId", "change", async () => {
            state.branchId = $("branchId").value ? Number($("branchId").value) : null;
            subscribeWsTopics();
            await refreshAllData();
        });

        // Filters & Search
        safeOn("clearFilterBtn", "click", () => {
            if ($("keyword")) $("keyword").value = "";
            if ($("filterCareGroup")) $("filterCareGroup").value = "";
            if ($("filterStaffId")) $("filterStaffId").value = "";
            if ($("filterDoctorId")) $("filterDoctorId").value = "";
            if ($("filterStatus")) $("filterStatus").value = "";
            if ($("filterFromDate")) $("filterFromDate").value = "";
            if ($("filterToDate")) $("filterToDate").value = "";
            loadTasks(0);
        });

        safeOn("keyword", "input", () => {
            clearTimeout(state.keywordDebounceTimer);
            state.keywordDebounceTimer = setTimeout(() => {
                if (state.viewMode === "LIST") loadTasks(0);
            }, 350);
        });

        safeOn("keyword", "keydown", (e) => {
            if (e.key === "Enter") {
                e.preventDefault();
                clearTimeout(state.keywordDebounceTimer);
                if (state.viewMode === "LIST") loadTasks(0);
            }
        });
        safeOn("filterCareGroup", "change", () => loadTasks(0));
        safeOn("filterStaffId", "change", () => loadTasks(0));
        safeOn("filterDoctorId", "change", () => loadTasks(0));
        safeOn("filterStatus", "change", () => loadTasks(0));
        safeOn("filterFromDate", "change", () => loadTasks(0));
        safeOn("filterToDate", "change", () => loadTasks(0));

        // Calendar navigation & filters
        safeOn("calPrevBtn", "click", () => {
            if (state.calMode === "month") {
                const d = parseYmdUtc(state.calRefDate);
                d.setUTCMonth(d.getUTCMonth() - 1, 1);
                state.calRefDate = formatUtcDateYmd(d);
            } else {
                state.calRefDate = addDaysYmd(state.calRefDate, -7);
            }
            loadCalendar();
        });
        safeOn("calNextBtn", "click", () => {
            if (state.calMode === "month") {
                const d = parseYmdUtc(state.calRefDate);
                d.setUTCMonth(d.getUTCMonth() + 1, 1);
                state.calRefDate = formatUtcDateYmd(d);
            } else {
                state.calRefDate = addDaysYmd(state.calRefDate, 7);
            }
            loadCalendar();
        });
        safeOn("calTodayBtn", "click", () => {
            state.calRefDate = getVietnamTodayYmd();
            loadCalendar();
        });
        safeOn("calWeekBtn", "click", () => {
            state.calMode = "week";
            loadCalendar();
        });
        safeOn("calMonthBtn", "click", () => {
            state.calMode = "month";
            loadCalendar();
        });
        safeOn("calFilterStaff", "change", () => loadCalendar());
        safeOn("calFilterDoctor", "change", () => loadCalendar());
        safeOn("calFilterCareGroup", "change", () => loadCalendar());
        safeOn("calFilterStatus", "change", () => loadCalendar());

        // Notification Center events
        safeOn("ccNotifBellBtn", "click", (e) => {
            e.stopPropagation();
            const dd = $("ccNotifDropdown");
            if (!dd) return;
            dd.hidden = !dd.hidden;
            if (!dd.hidden) loadNotifications();
        });
        safeOn("ccNotifMarkAllBtn", "click", markAllNotificationsRead);
        document.addEventListener("click", (e) => {
            const wrap = $("ccNotifBellWrap");
            const dd = $("ccNotifDropdown");
            if (wrap && dd && !dd.hidden && !wrap.contains(e.target)) {
                dd.hidden = true;
            }
        });

        safeOn("openCreateTaskBtn", "click", () => openTaskFormModal());
        safeOn("refreshBtn", "click", async () => {
            await refreshAllData();
            await loadNotifications();
            showMessage("Đã làm mới dữ liệu Chăm sóc khách hàng.", "success");
        });

        safeOn("formProfileId", "change", (e) => populatePatientLinkedRecords(e.target.value));
        if ($("formVisitId")) {
            safeOn("formVisitId", "change", (e) => previewSelectedVisitExamination(e.target.value));
        }
        safeOn("intContactResult", "change", updateRetryFieldRequirement);

        safeOn("loadHistoryBtn", "click", () => loadPatientHistory($("historyPatientSelect").value));
        safeOn("historyPatientSelect", "change", (e) => {
            if (e.target.value) loadPatientHistory(e.target.value);
        });

        // Form Submits
        safeOn("taskForm", "submit", submitTaskForm);
        safeOn("interactionForm", "submit", submitInteractionForm);
        safeOn("escalateForm", "submit", submitEscalateForm);
        safeOn("doctorResponseForm", "submit", submitDoctorResponseForm);
        safeOn("reopenForm", "submit", submitReopenForm);

        // Chuyển nhanh từ Modal Ghi kết quả sang Modal Chuyển Bác sĩ
        safeOn("switchToEscalateFromIntBtn", "click", async () => {
            const taskId = $("intTaskId").value;
            $("interactionModal").hidden = true;
            if (taskId) await openEscalateModal(taskId);
        });

        // Close modal buttons
        safeOn("closeTaskFormModalBtn", "click", () => { $("taskFormModal").hidden = true; });
        safeOn("cancelTaskFormModalBtn", "click", () => { $("taskFormModal").hidden = true; });
        safeOn("closeInteractionModalBtn", "click", () => { $("interactionModal").hidden = true; });
        safeOn("cancelInteractionModalBtn", "click", () => { $("interactionModal").hidden = true; });
        safeOn("closeEscalateModalBtn", "click", () => { $("escalateModal").hidden = true; });
        safeOn("cancelEscalateModalBtn", "click", () => { $("escalateModal").hidden = true; });
        safeOn("closeDoctorResponseModalBtn", "click", () => { $("doctorResponseModal").hidden = true; });
        safeOn("cancelDoctorResponseModalBtn", "click", () => { $("doctorResponseModal").hidden = true; });
        safeOn("closeContentPreviewModalBtn", "click", () => { $("ccContentPreviewModal").hidden = true; });
        safeOn("cancelContentPreviewModalBtn", "click", () => { $("ccContentPreviewModal").hidden = true; });
        safeOn("closeDayTasksModalBtn", "click", () => { $("ccDayTasksModal").hidden = true; });
        safeOn("closeReopenModalBtn", "click", () => { $("reopenModal").hidden = true; });
        safeOn("cancelReopenModalBtn", "click", () => { $("reopenModal").hidden = true; });

        // Patient History Right Offcanvas Drawer events
        safeOn("closePatientHistoryDrawerBtn", "click", closePatientHistoryDrawer);
        safeOn("patientHistoryDrawerOverlay", "click", closePatientHistoryDrawer);
        safeOn("patientHistoryDrawerKeyword", "input", () => {
            clearTimeout(patientDrawerState.debounceTimer);
            patientDrawerState.debounceTimer = setTimeout(applyPatientDrawerFilters, 300);
        });
        safeOn("patientHistoryDrawerTaskFilter", "change", applyPatientDrawerFilters);
        safeOn("patientHistoryDrawerResultFilter", "change", applyPatientDrawerFilters);
        safeOn("patientHistoryDrawerMoreBtn", "click", () => {
            patientDrawerState.visibleLimit += 10;
            renderPatientDrawerTimeline();
        });

        // General History Dashboard events
        safeOn("histKeyword", "input", () => {
            clearTimeout(generalHistoryState.debounceTimer);
            generalHistoryState.debounceTimer = setTimeout(() => loadGeneralHistory(0), 350);
        });
        safeOn("histApplyCustomDateBtn", "click", () => loadGeneralHistory(0));
        safeOn("histToggleAdvBtn", "click", () => {
            const p = $("histAdvPanel");
            if (p) p.classList.toggle("show");
        });
        safeOn("histClearBtn", "click", () => {
            if ($("histKeyword")) $("histKeyword").value = "";
            if ($("histBranchFilter")) $("histBranchFilter").value = "";
            if ($("histStaffFilter")) $("histStaffFilter").value = "";
            if ($("histDoctorFilter")) $("histDoctorFilter").value = "";
            if ($("histChannelFilter")) $("histChannelFilter").value = "";
            if ($("histResultFilter")) $("histResultFilter").value = "";
            if ($("histCareGroupFilter")) $("histCareGroupFilter").value = "";
            loadGeneralHistory(0);
        });
        safeOn("histRefreshBtn", "click", () => loadGeneralHistory(generalHistoryState.page));
        safeOn("histBranchFilter", "change", () => loadGeneralHistory(0));
        safeOn("histStaffFilter", "change", () => loadGeneralHistory(0));
        safeOn("histDoctorFilter", "change", () => loadGeneralHistory(0));
        safeOn("histChannelFilter", "change", () => loadGeneralHistory(0));
        safeOn("histResultFilter", "change", () => loadGeneralHistory(0));
        safeOn("histCareGroupFilter", "change", () => loadGeneralHistory(0));
        safeOn("histPageSizeSelect", "change", (e) => {
            generalHistoryState.pageSize = Number(e.target.value) || 15;
            loadGeneralHistory(0);
        });
        safeOn("histFirstBtn", "click", () => loadGeneralHistory(0));
        safeOn("histPrevBtn", "click", () => {
            if (generalHistoryState.page > 0) loadGeneralHistory(generalHistoryState.page - 1);
        });
        safeOn("histNextBtn", "click", () => {
            if (generalHistoryState.page < generalHistoryState.totalPages - 1) loadGeneralHistory(generalHistoryState.page + 1);
        });
        safeOn("histLastBtn", "click", () => {
            if (generalHistoryState.totalPages > 0) loadGeneralHistory(generalHistoryState.totalPages - 1);
        });
        document.querySelectorAll("[data-hist-timerange]").forEach(btn => {
            btn.addEventListener("click", () => {
                generalHistoryState.timeRange = btn.dataset.histTimerange || "ALL";
                document.querySelectorAll("[data-hist-timerange]").forEach(b => b.classList.toggle("active", b === btn));
                const customWrap = $("histCustomDateWrap");
                if (customWrap) customWrap.style.display = generalHistoryState.timeRange === "CUSTOM" ? "inline-flex" : "none";
                loadGeneralHistory(0);
            });
        });

        // Đảm bảo click vào bên trong dialog không bao giờ kích hoạt đóng backdrop
        document.querySelectorAll(".cc-modal-dialog, .modal-dialog").forEach(dialog => {
            dialog.addEventListener("click", (e) => {
                e.stopPropagation();
            });
        });

        ["taskFormModal", "interactionModal", "escalateModal", "doctorResponseModal", "ccContentPreviewModal", "ccDayTasksModal", "reopenModal"].forEach(modalId => {
            const modalEl = $(modalId);
            if (modalEl) {
                modalEl.addEventListener("click", (e) => {
                    if (e.target === modalEl) modalEl.hidden = true;
                });
            }
        });

        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape") {
                ["taskFormModal", "interactionModal", "escalateModal", "doctorResponseModal", "ccContentPreviewModal", "ccDayTasksModal", "reopenModal"].forEach(modalId => {
                    const modalEl = $(modalId);
                    if (modalEl && !modalEl.hidden) modalEl.hidden = true;
                });
                if ($("ccNotifDropdown")) $("ccNotifDropdown").hidden = true;
                closePatientHistoryDrawer();
            }
        });

        // Delegated actions in tables, calendar, and notification dropdown
        document.body.addEventListener("click", async (e) => {
            const notifEl = e.target.closest("[data-notif-id]");
            if (notifEl) {
                await handleNotificationClick(notifEl.dataset.notifId, notifEl.dataset.notifTask);
                return;
            }
            const calCard = e.target.closest("[data-cal-task-id]");
            if (calCard) {
                await openInteractionModal(calCard.dataset.calTaskId);
                return;
            }
            const calMore = e.target.closest("[data-cal-more-date]");
            if (calMore) {
                openDayTasksModal(calMore.dataset.calMoreDate);
                return;
            }
            const calModalCard = e.target.closest("[data-cal-modal-task-id]");
            if (calModalCard) {
                if ($("ccDayTasksModal")) $("ccDayTasksModal").hidden = true;
                await openInteractionModal(calModalCard.dataset.calModalTaskId);
                return;
            }

            const previewEl = e.target.closest("[data-preview-task]");
            if (previewEl) {
                openContentPreviewModal(previewEl.dataset.previewTask);
                return;
            }
            const recordBtn = e.target.closest("[data-record-interaction]");
            if (recordBtn) {
                await openInteractionModal(recordBtn.dataset.recordInteraction);
                return;
            }

            const escalateBtn = e.target.closest("[data-escalate-task]");
            if (escalateBtn) {
                await openEscalateModal(escalateBtn.dataset.escalateTask);
                return;
            }

            const docRespBtn = e.target.closest("[data-doctor-response]");
            if (docRespBtn) {
                await openDoctorResponseModal(docRespBtn.dataset.doctorResponse);
                return;
            }

            const reopenBtn = e.target.closest("[data-reopen-task]");
            if (reopenBtn) {
                openReopenModal(reopenBtn.dataset.reopenTask);
                return;
            }

            const histBtn = e.target.closest("[data-view-history]");
            if (histBtn) {
                const profileId = histBtn.dataset.viewHistory;
                if (profileId) {
                    openPatientHistoryDrawer(Number(profileId));
                }
                return;
            }

            const patientDrawerBtn = e.target.closest("[data-open-patient-drawer]");
            if (patientDrawerBtn) {
                openPatientHistoryDrawer(Number(patientDrawerBtn.dataset.openPatientDrawer));
                return;
            }

            const taskModalBtn = e.target.closest("[data-open-task-modal]");
            if (taskModalBtn) {
                await openInteractionModal(taskModalBtn.dataset.openTaskModal);
                return;
            }

            const vtlToggleBtn = e.target.closest("[data-vtl-toggle]");
            if (vtlToggleBtn) {
                toggleVtlRow(vtlToggleBtn);
                return;
            }

            const editBtn = e.target.closest("[data-edit-task]");
            if (editBtn) {
                try {
                    const t = await request(`/api/customer-care/tasks/${editBtn.dataset.editTask}`);
                    if (isTaskClosed(t)) {
                        showMessage(`Công việc ${t.taskCode} đã đóng, không thể chỉnh sửa.`, "warning");
                        return;
                    }
                    openTaskFormModal(t);
                } catch (err) {
                    showMessage(err.message, "error");
                }
            }
        }, true);

        // Fallback light sync every 60s when tab is visible
        setInterval(() => {
            if (!document.hidden) {
                loadSummary();
                loadNotifications();
                if (state.viewMode === "CALENDAR") {
                    loadCalendar();
                } else if (state.activeTab !== "HISTORY") {
                    loadTasks(state.page);
                }
            }
        }, 60000);

        loadBranchesAndInit();
    });
})();
