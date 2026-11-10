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
        activeTab: "ALL",
        page: 0,
        pageSize: 10,
        totalPages: 0,

        lookups: {
            staffList: [],
            doctorList: [],
            patients: [],
            appointments: [],
            visits: [],
            prescriptions: []
        },

        currentTaskDetail: null,
        tasksAbortController: null,
        keywordDebounceTimer: null
    };

    const TAB_META = {
        ALL: {
            title: "Danh sách tất cả công việc Chăm sóc Khách hàng (SCR-44.1)",
            subtitle: "Tổng hợp toàn bộ công việc CSKH tại chi nhánh: hỏi thăm sau điều trị, nhắc thuốc, nhắc lịch hẹn, trả góp và phản ánh."
        },
        TODAY: {
            title: "Công việc CSKH cần xử lý hôm nay / Quá hạn / Hẹn gọi lại",
            subtitle: "Ưu tiên liên hệ các bệnh nhân đến hạn hôm nay, quá hạn hoặc đang có lịch hẹn gọi lại (next_retry_at)."
        },
        POST_TREATMENT: {
            title: "Nhóm bệnh nhân Sau điều trị & Nhắc dùng thuốc (SCR-44.2)",
            subtitle: "Hỏi thăm sức khỏe bệnh nhân sau thủ thuật/phẫu thuật, đối chiếu chẩn đoán & lời dặn (#tab-instructions) và nhắc uống thuốc theo đơn."
        },
        APPOINTMENT_REMINDER: {
            title: "Nhóm bệnh nhân Nhắc lịch hẹn sắp tới (SCR-44.2)",
            subtitle: "Liên hệ nhắc bệnh nhân chuẩn bị đến khám đúng ngày giờ hẹn hoặc hỗ trợ chuyển đổi lịch hẹn (FT05)."
        },
        INSTALLMENT_REMINDER: {
            title: "Nhóm bệnh nhân Nhắc lịch thanh toán trả góp (SCR-44.2)",
            subtitle: "Nhắc lịch thanh toán định kỳ cho các gói điều trị trả góp (Implant, Chỉnh nha, Phục hình sứ)."
        },
        NO_SHOW_RECALL: {
            title: "Nhóm bệnh nhân Lịch hẹn lỡ - Gọi lại đặt lịch (SCR-44.2)",
            subtitle: "Liên hệ lại bệnh nhân trễ hẹn / không đến khám hoặc chưa bắt máy để hỗ trợ đặt lại lịch hẹn."
        },
        NO_SERVICE_FOLLOWUP: {
            title: "Nhóm khách hàng Thăm khám nhưng chưa làm dịch vụ (SCR-44.2)",
            subtitle: "Chăm sóc, giải đáp thắc mắc cho khách hàng đã tư vấn/khám nhưng chưa quyết định thực hiện dịch vụ."
        },
        FEEDBACK_COMPLAINT: {
            title: "Nhóm Tiếp nhận Phản ánh & Khiếu nại dịch vụ (SCR-44.2)",
            subtitle: "Theo dõi và xử lý kịp thời các phản ánh về chất lượng dịch vụ hoặc triệu chứng bất thường sau điều trị."
        },
        ESCALATED: {
            title: "Danh sách trường hợp Chuyển Bác sĩ hỗ trợ chuyên môn (SCR-44.5)",
            subtitle: "Các ca bệnh nhân có dấu hiệu sưng đau, dị ứng thuốc hoặc thắc mắc chuyên môn cần Bác sĩ trực tiếp cho ý kiến xử lý."
        }
    };

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
        ["kpiCardTotal", "kpiCardDue", "kpiCardEscalated", "kpiCardCompleted"].forEach(id => {
            const el = $(id);
            if (el) {
                el.classList.add("is-loading");
                el.classList.remove("is-error");
            }
        });
        $("statTotalTasks").textContent = "—";
        $("statDueTasks").textContent = "—";
        $("statEscalatedTasks").textContent = "—";
        $("statCompletedTasks").textContent = "—";
        $("statTotalTasksSub").textContent = "Đang tải dữ liệu...";
        $("statDueTasksSub").textContent = "Đang kiểm tra lịch hẹn...";
        $("statEscalatedTasksSub").textContent = "Đang kiểm tra ca chuyển BS...";
        $("statCompletedTasksSub").textContent = "Đang tính tỷ lệ hoàn thành...";
    }

    function setKpiError(msg = "Không thể tải số liệu") {
        ["kpiCardTotal", "kpiCardDue", "kpiCardEscalated", "kpiCardCompleted"].forEach(id => {
            const el = $(id);
            if (el) {
                el.classList.remove("is-loading");
                el.classList.add("is-error");
            }
        });
        $("statTotalTasks").textContent = "—";
        $("statDueTasks").textContent = "—";
        $("statEscalatedTasks").textContent = "—";
        $("statCompletedTasks").textContent = "—";
        $("statTotalTasksSub").textContent = msg;
        $("statDueTasksSub").textContent = msg;
        $("statEscalatedTasksSub").textContent = msg;
        $("statCompletedTasksSub").textContent = msg;
    }

    async function loadSummary() {
        setKpiLoading();
        try {
            const params = new URLSearchParams();
            if (state.branchId) params.set("branchId", state.branchId);
            const sum = await request(`/api/customer-care/summary?${params}`);

            ["kpiCardTotal", "kpiCardDue", "kpiCardEscalated", "kpiCardCompleted"].forEach(id => {
                const el = $(id);
                if (el) el.classList.remove("is-loading", "is-error");
            });

            const total = Number(sum?.totalTasks ?? 0);
            const active = Number(sum?.activeTasks ?? 0);
            const dueOrOverdue = Number(sum?.dueOrOverdueTasks ?? 0);
            const overdue = Number(sum?.overdueTasks ?? 0);
            const retryPending = Number(sum?.retryPendingCount ?? 0);
            const escalated = Number(sum?.escalatedTasks ?? 0);
            const waitingDoc = Number(sum?.waitingDoctorResponseCount ?? 0);
            const docResponded = Number(sum?.doctorRespondedCount ?? 0);
            const completed = Number(sum?.completedTasks ?? 0);
            const rate = Number(sum?.completionRate ?? 0);

            $("statTotalTasks").textContent = total.toLocaleString("vi-VN");
            $("statTotalTasksSub").textContent = total > 0
                ? `${active} công việc đang chờ/đang thực hiện`
                : "Chưa có công việc CSKH tại chi nhánh";

            $("statDueTasks").textContent = dueOrOverdue.toLocaleString("vi-VN");
            $("statDueTasksSub").textContent = dueOrOverdue > 0
                ? `${overdue} quá hạn · ${retryPending} ca hẹn gọi lại`
                : (retryPending > 0 ? `${retryPending} ca đang hẹn gọi lại` : "Không có công việc quá hạn");

            $("statEscalatedTasks").textContent = escalated.toLocaleString("vi-VN");
            $("statEscalatedTasksSub").textContent = escalated > 0
                ? `${waitingDoc} chờ BS phản hồi · ${docResponded} đã phản hồi`
                : "Không có ca chờ Bác sĩ xử lý";

            $("statCompletedTasks").textContent = `${completed.toLocaleString("vi-VN")} (${rate}%)`;
            $("statCompletedTasksSub").textContent = total > 0
                ? `Hoàn thành ${completed}/${total} công việc CSKH`
                : "Chưa phát sinh dữ liệu hoàn thành";

            const groupCounts = sum?.groupCounts || {};
            Object.entries(groupCounts).forEach(([grp, cnt]) => {
                const badgeEl = $(`badge_${grp}`);
                if (badgeEl) badgeEl.textContent = Number(cnt || 0);
                const optEl = $(`opt_tab_${grp}`);
                if (optEl) {
                    const baseLabel = optEl.dataset.label || optEl.textContent.split("(")[0].trim();
                    optEl.textContent = `${baseLabel} (${cnt || 0})`;
                }
            });
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

            await refreshAllData();
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
            state.activeTab === "HISTORY" ? Promise.resolve() : loadTasks(0)
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
    // CẤU HÌNH CỘT BẢNG CSKH & COLUMN CUSTOMIZER (THEO CHUẨN CV-DCMS ENTERPRISE)
    // =========================================================================
    const CC_COLUMNS_KEY = "cvdcms_cc_table_columns_v5";
    const DEFAULT_CC_COLUMNS = [
        { id: "taskCode", title: "Mã CV", width: 120, visible: true, required: true },
        { id: "patient", title: "Bệnh nhân", width: 220, visible: true, required: true },
        { id: "careGroup", title: "Phân nhóm & Nguồn", width: 200, visible: true },
        { id: "due", title: "Hạn / Gọi lại", width: 150, visible: true },
        { id: "notes", title: "Chẩn đoán & Lời dặn", width: 280, visible: true },
        { id: "staff", title: "Phụ trách", width: 160, visible: true },
        { id: "interaction", title: "Tương tác gần nhất", width: 170, visible: true },
        { id: "status", title: "Trạng thái", width: 140, visible: true },
        { id: "actions", title: "Thao tác", width: 330, visible: true, required: true }
    ];

    let ccColumns = loadCcColumns();

    function loadCcColumns() {
        try {
            const raw = localStorage.getItem(CC_COLUMNS_KEY);
            if (raw) {
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed) && parsed.length === DEFAULT_CC_COLUMNS.length) {
                    const actCol = parsed.find(c => c.id === "actions");
                    if (actCol && (!actCol.width || actCol.width < 330)) {
                        actCol.width = 330;
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

    function renderCcTableHeader() {
        const theadTr = $("ccTasksHeaderRow");
        if (!theadTr) return;
        theadTr.innerHTML = "";

        const visibleCols = ccColumns.filter(c => c.visible);
        visibleCols.forEach((col, idx) => {
            const th = document.createElement("th");
            th.style.width = col.width + "px";
            th.style.minWidth = col.width + "px";
            th.innerHTML = `
                <div class="dv-th-inner">
                    <span class="cell-ellipsis" title="${col.title}">${col.title}</span>
                </div>
                <span class="col-resizer" data-col-index="${idx}"></span>
            `;
            theadTr.appendChild(th);
        });

        // Track Thao tác nổi bám mép phải khi cuộn ngang (chuẩn 100% /doctor/daily-visits)
        const trackTh = document.createElement("th");
        trackTh.className = "dv-floating-actions-track";
        theadTr.appendChild(trackTh);

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
            $("lblTasksTotal").textContent = totalElements;
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

    async function loadTasks(page = state.page) {
        state.page = page;
        renderCcTableHeader();
        const visibleCols = ccColumns.filter(c => c.visible);
        const tbody = $("tasksTableBody");
        tbody.innerHTML = `<tr><td colspan="${visibleCols.length + 1}" class="empty">Đang tải danh sách công việc CSKH...</td></tr>`;

        if (state.tasksAbortController) {
            state.tasksAbortController.abort();
        }
        const controller = new AbortController();
        state.tasksAbortController = controller;

        const params = new URLSearchParams({
            page,
            size: state.pageSize,
            tabGroup: state.activeTab === "HISTORY" ? "ALL" : state.activeTab
        });
        if (state.branchId) params.set("branchId", state.branchId);
        if ($("filterStaffId").value) params.set("assignedStaffId", $("filterStaffId").value);
        if ($("filterStatus").value) params.set("status", $("filterStatus").value);
        if ($("filterFromDate").value) params.set("fromDate", $("filterFromDate").value);
        if ($("filterToDate").value) params.set("toDate", $("filterToDate").value);
        const kw = $("keyword").value.trim();
        if (kw) params.set("keyword", kw);

        try {
            const data = await request(`/api/customer-care/tasks?${params}`, { signal: controller.signal });
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.totalPages = Number(data?.totalPages || 0);
            const totalElements = Number(data?.totalElements || 0);

            if ($("tasksTotalBadge")) $("tasksTotalBadge").textContent = `${totalElements} công việc`;

            if (!rows.length) {
                tbody.innerHTML = `<tr><td colspan="${visibleCols.length}" class="empty">Không có công việc CSKH nào phù hợp với bộ lọc hiện tại.</td></tr>`;
                renderCcPagination(0, 0, 0);
                return;
            }

            tbody.innerHTML = rows.map(t => {
                const cells = [];
                visibleCols.forEach(col => {
                    if (col.id === "taskCode") {
                        cells.push(`
                            <td style="width:${col.width}px;">
                                <span class="font-mono-code">${escapeHtml(t.taskCode)}</span>
                                <div style="font-size:11px;color:#64748b;margin-top:2px;">${escapeHtml(t.branchCode || "")}</div>
                            </td>
                        `);
                    } else if (col.id === "patient") {
                        cells.push(`
                            <td class="patient-cell" style="width:${col.width}px;">
                                <span class="cell-ellipsis" title="${escapeHtml(t.patientName)}"><strong>${escapeHtml(t.patientName)}</strong></span>
                                <small>Mã BN: <span class="font-mono-code" style="font-size:11px;padding:1px 5px;">${escapeHtml(t.patientCode)}</span></small>
                                <small>SĐT: ${escapeHtml(t.patientPhone || "—")}</small>
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
                            <td style="width:${col.width}px;">
                                <span class="badge ${getCareGroupBadgeClass(t.careGroup)}">${escapeHtml(t.careGroupLabel)}</span>
                                <div style="margin-top:3px;">${refs.join(" ")}</div>
                                ${t.doctorName ? `<div style="font-size:11px;color:#475569;margin-top:2px;">BS: <strong>${escapeHtml(t.doctorName)}</strong></div>` : ""}
                            </td>
                        `);
                    } else if (col.id === "due") {
                        const dueTag = t.overdue
                            ? '<span class="due-tag due-overdue">Quá hạn</span>'
                            : (t.dueToday ? '<span class="due-tag due-today">Đến hạn hôm nay</span>' : "");
                        const retryHtml = t.nextRetryAt
                            ? `<span class="retry-tag">Gọi lại: ${escapeHtml(formatDateTime(t.nextRetryAt))}</span>`
                            : "";
                        cells.push(`
                            <td style="width:${col.width}px;">
                                <strong>${escapeHtml(t.dueDate || "—")}</strong>
                                <div>${dueTag}</div>
                                ${retryHtml}
                            </td>
                        `);
                    } else if (col.id === "notes") {
                        const diagHtml = t.diagnosisSummary
                            ? `<div style="font-size:11.5px;color:#1e293b;margin-top:3px;" class="cell-ellipsis" title="${escapeHtml(t.diagnosisSummary)}"><strong>CĐ:</strong> ${escapeHtml(t.diagnosisSummary)}</div>`
                            : "";
                        const instructionsText = t.treatmentInstructions || t.prescriptionAdvice;
                        const instructionsHtml = instructionsText
                            ? `<div style="font-size:11.5px;color:#0369a1;margin-top:3px;background:#f0f9ff;padding:2px 6px;border-radius:4px;" class="cell-ellipsis" title="${escapeHtml(instructionsText)}"><strong>Lời dặn:</strong> ${escapeHtml(instructionsText)}</div>`
                            : "";
                        const docReplyHtml = t.doctorResponse
                            ? `<div class="doc-reply-snippet cell-ellipsis" title="${escapeHtml(t.doctorResponse)}"><strong>BS ${escapeHtml(t.escalatedToDoctorName || "")}:</strong> ${escapeHtml(t.doctorResponse)}</div>`
                            : (t.status === "ESCALATED_TO_DOCTOR"
                                ? `<div class="retry-tag" style="background:#fef2f2;border-color:#fecaca;color:#b91c1c;">Chờ Bác sĩ ${escapeHtml(t.escalatedToDoctorName || "")} phản hồi</div>`
                                : "");
                        cells.push(`
                            <td style="width:${col.width}px;">
                                <div class="cell-ellipsis" title="${escapeHtml(t.notes || "—")}">${escapeHtml(t.notes || "—")}</div>
                                ${diagHtml}
                                ${instructionsHtml}
                                ${docReplyHtml}
                            </td>
                        `);
                    } else if (col.id === "staff") {
                        cells.push(`
                            <td style="width:${col.width}px;">
                                <div><small style="color:#64748b;">CSKH:</small> <strong>${escapeHtml(t.assignedStaffName || "Chưa gán")}</strong></div>
                                ${t.escalatedToDoctorName ? `<div style="margin-top:2px;color:#b91c1c;"><small>Bác sĩ:</small> <strong>${escapeHtml(t.escalatedToDoctorName)}</strong></div>` : ""}
                            </td>
                        `);
                    } else if (col.id === "interaction") {
                        cells.push(`
                            <td style="width:${col.width}px;">
                                ${formatChannelBadge(t.lastChannel, t.lastContactResult)}
                                ${t.lastContactAt ? `<div style="font-size:11px;color:#64748b;margin-top:2px;">${escapeHtml(formatDateTime(t.lastContactAt))} (${t.interactionCount} lần)</div>` : ""}
                            </td>
                        `);
                    } else if (col.id === "status") {
                        cells.push(`
                            <td style="width:${col.width}px;">
                                <span class="badge ${getStatusBadgeClass(t.status)}">${escapeHtml(t.statusLabel)}</span>
                            </td>
                        `);
                    } else if (col.id === "actions") {
                        const actBtns = `
                            <button type="button" class="btn btn-primary btn-sm fw-bold px-2.5 py-1 rounded-2 shadow-xs d-inline-flex align-items-center gap-1" data-record-interaction="${t.taskId}" title="Chi tiết & Ghi kết quả liên hệ" style="font-size:12px;background:linear-gradient(135deg, #0284c7, #2563eb);border:none;">
                                <i class="bi bi-pencil-square"></i> Ghi KQ
                            </button>
                            <button type="button" class="btn btn-outline-danger btn-sm fw-bold px-2 py-1 rounded-2 shadow-xs d-inline-flex align-items-center gap-1" data-escalate-task="${t.taskId}" title="Chuyển Bác sĩ hỗ trợ chuyên môn" style="font-size:12px;">
                                <i class="bi bi-arrow-up-right-circle"></i> Chuyển BS
                            </button>
                            <button type="button" class="btn btn-outline-secondary btn-sm fw-bold px-2 py-1 rounded-2 shadow-xs d-inline-flex align-items-center gap-1" data-view-history="${t.profileId}" title="Xem lịch sử chăm sóc bệnh nhân" style="font-size:12px;">
                                <i class="bi bi-clock-history"></i> Lịch sử
                            </button>
                            ${(t.status === "ESCALATED_TO_DOCTOR" || t.escalatedToDoctorId) && (state.careRole === "BAC_SI" || state.careRole === "ADMIN") ? `
                                <button type="button" class="btn btn-danger btn-sm fw-bold px-2 py-1 rounded-2 shadow-xs d-inline-flex align-items-center gap-1" data-doctor-response="${t.taskId}" title="Bác sĩ nhập phản hồi chuyên môn" style="font-size:12px;">
                                    <i class="bi bi-chat-dots-fill"></i> Phản hồi
                                </button>
                            ` : ""}
                            <button type="button" class="btn btn-outline-secondary btn-sm px-2 py-1 rounded-2 shadow-xs" data-edit-task="${t.taskId}" title="Sửa / Phân công lại" style="font-size:12px;">
                                <i class="bi bi-pencil"></i>
                            </button>
                        `;
                        cells.push(`
                            <td class="text-center" style="width:${col.width}px;min-width:${col.width}px;">
                                <div class="d-flex align-items-center justify-content-center gap-1.5 flex-wrap" onclick="event.stopPropagation()">
                                    ${actBtns}
                                </div>
                            </td>
                        `);
                    }
                });

                // Cụm nút nổi bám mép phải viewport khi hover dòng (chuẩn 100% theo /doctor/daily-visits)
                const floatingBarHtml = `
                    <td class="dv-floating-actions-track">
                        <div class="dv-floating-actions-bar" onclick="event.stopPropagation()">
                            <button type="button" class="btn btn-primary btn-sm fw-bold px-2.5 py-1 rounded-2 shadow-xs d-inline-flex align-items-center gap-1" data-record-interaction="${t.taskId}" title="Chi tiết & Ghi kết quả liên hệ" style="font-size:12px;background:linear-gradient(135deg, #0284c7, #2563eb);border:none;">
                                <i class="bi bi-pencil-square"></i> Ghi KQ
                            </button>
                            <button type="button" class="btn btn-outline-danger btn-sm fw-bold px-2 py-1 rounded-2 shadow-xs d-inline-flex align-items-center gap-1" data-escalate-task="${t.taskId}" title="Chuyển Bác sĩ hỗ trợ chuyên môn" style="font-size:12px;">
                                <i class="bi bi-arrow-up-right-circle"></i> Chuyển BS
                            </button>
                            <button type="button" class="btn btn-outline-secondary btn-sm fw-bold px-2 py-1 rounded-2 shadow-xs d-inline-flex align-items-center gap-1" data-view-history="${t.profileId}" title="Xem lịch sử chăm sóc bệnh nhân" style="font-size:12px;">
                                <i class="bi bi-clock-history"></i> Lịch sử
                            </button>
                            ${(t.status === "ESCALATED_TO_DOCTOR" || t.escalatedToDoctorId) && (state.careRole === "BAC_SI" || state.careRole === "ADMIN") ? `
                                <button type="button" class="btn btn-danger btn-sm fw-bold px-2 py-1 rounded-2 shadow-xs d-inline-flex align-items-center gap-1" data-doctor-response="${t.taskId}" title="Bác sĩ nhập phản hồi chuyên môn" style="font-size:12px;">
                                    <i class="bi bi-chat-dots-fill"></i> Phản hồi
                                </button>
                            ` : ""}
                            <button type="button" class="btn btn-outline-secondary btn-sm px-2 py-1 rounded-2 shadow-xs" data-edit-task="${t.taskId}" title="Sửa / Phân công lại" style="font-size:12px;">
                                <i class="bi bi-pencil"></i>
                            </button>
                        </div>
                    </td>
                `;
                cells.push(floatingBarHtml);

                return `<tr class="dv-data-row">${cells.join("")}</tr>`;
            }).join("");

            renderCcPagination(totalElements, page, state.totalPages);
        } catch (err) {
            if (err.name === "AbortError") return;
            tbody.innerHTML = `<tr><td colspan="${visibleCols.length + 1}" class="empty">${escapeHtml(err.message)}</td></tr>`;
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

        const tomorrow = new Date();
        tomorrow.setDate(tomorrow.getDate() + 1);
        $("formDueDate").value = task?.dueDate || tomorrow.toISOString().slice(0, 10);

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
            if (state.activeTab && state.activeTab !== "ALL" && state.activeTab !== "TODAY" && state.activeTab !== "ESCALATED" && state.activeTab !== "HISTORY") {
                const targetGroup = state.activeTab === "POST_TREATMENT" ? "POST_TREATMENT_FOLLOWUP" : state.activeTab;
                if ([...$("formCareGroup").options].some(o => o.value === targetGroup)) {
                    $("formCareGroup").value = targetGroup;
                }
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
            await Promise.all([loadSummary(), loadTasks(editId ? state.page : 0)]);
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            btn.disabled = false;
        }
    }

    async function handleAutoGenerateTasks() {
        const branchId = state.branchId || Number($("branchId").value) || 1;
        const btn = $("autoGenerateBtn");
        btn.disabled = true;
        btn.textContent = "Đang tổng hợp...";
        try {
            const res = await request(`/api/customer-care/tasks/auto-generate?branchId=${branchId}`, {
                method: "POST"
            });
            showMessage(res.message || "Đã tổng hợp công việc CSKH.", res.totalCreated > 0 ? "success" : "info");
            await Promise.all([loadSummary(), loadTasks(0)]);
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            btn.disabled = false;
            btn.textContent = "Tổng hợp tự động";
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

    async function openInteractionModal(taskId) {
        try {
            const t = await request(`/api/customer-care/tasks/${taskId}`);
            state.currentTaskDetail = t;

            $("intTaskId").value = t.taskId;
            $("interactionModalTitle").textContent = `Chi tiết công việc & Ghi kết quả liên hệ: ${t.taskCode}`;

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
            if (t.doctorResponse || t.status === "ESCALATED_TO_DOCTOR") {
                docAlert.hidden = false;
                docAlert.innerHTML = `
                    <div><strong>Thông tin chuyển Bác sĩ chuyên môn (${escapeHtml(t.escalatedToDoctorName || "Bác sĩ")}):</strong></div>
                    <div style="margin-top:4px;">• <strong>Triệu chứng/Ghi chú:</strong> ${escapeHtml(t.notes || "—")}</div>
                    <div style="margin-top:4px;">• <strong>Chỉ định/Phản hồi của Bác sĩ:</strong> ${
                        t.doctorResponse
                            ? `<strong>${escapeHtml(t.doctorResponse)}</strong>`
                            : '<em>Đang chờ Bác sĩ nhập phản hồi chuyên môn...</em>'
                    }</div>
                `;
            } else {
                docAlert.hidden = true;
            }

            $("interactionForm").reset();
            $("intTaskId").value = t.taskId;
            $("intChannel").value = "PHONE_CALL";
            $("intContactResult").value = "CONNECTED";
            updateRetryFieldRequirement();

            renderTaskInteractionsList(t.interactions || []);
            $("interactionModal").hidden = false;
            const scrollBody = document.querySelector("#interactionModal .modal-body-scroll");
            if (scrollBody) scrollBody.scrollTop = 0;
        } catch (err) {
            showMessage(err.message, "error");
        }
    }

    function renderTaskInteractionsList(interactions) {
        const container = $("taskInteractionsList");
        if (!interactions || !interactions.length) {
            container.innerHTML = '<p class="empty" style="padding:14px;">Chưa có lần liên hệ nào được ghi nhận cho công việc này.</p>';
            return;
        }
        container.innerHTML = interactions.map(i => `
            <div class="timeline-item result-${escapeHtml(i.contactResult)}">
                <div class="timeline-item-head">
                    <div>
                        <span class="badge badge-group-appt">${escapeHtml(i.channelLabel)}</span>
                        <span class="badge ${i.contactResult === "CONNECTED" || i.contactResult === "SCHEDULED_APPOINTMENT" ? "status-completed" : "status-progress"}">
                            ${escapeHtml(i.contactResultLabel)}
                        </span>
                    </div>
                    <span class="timeline-time">${escapeHtml(formatDateTime(i.createdAt))}</span>
                </div>
                <p class="timeline-notes">${escapeHtml(i.notes)}</p>
                <div class="timeline-footer">
                    <span>Thực hiện bởi: <strong>${escapeHtml(i.staffName || "Nhân viên CSKH")}</strong></span>
                    ${i.nextRetryAt ? `<span style="color:#b45309;font-weight:700;"> · Hẹn gọi lại: ${escapeHtml(formatDateTime(i.nextRetryAt))}</span>` : ""}
                </div>
            </div>
        `).join("");
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
            await Promise.all([loadSummary(), loadTasks(state.page)]);
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
            $("escReason").value = t.notes || "";
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
            await Promise.all([loadSummary(), loadTasks(state.page)]);
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            btn.disabled = false;
        }
    }

    async function openDoctorResponseModal(taskId) {
        try {
            const t = await request(`/api/customer-care/tasks/${taskId}`);
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
                    <strong>Triệu chứng / Vấn đề bệnh nhân báo:</strong><br>
                    ${escapeHtml(t.notes || "—")}
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
            await Promise.all([loadSummary(), loadTasks(state.page)]);
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            btn.disabled = false;
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

            $("patientTimelineContainer").innerHTML = interactions.length ? interactions.map(i => `
                <div class="timeline-item result-${escapeHtml(i.contactResult)}">
                    <div class="timeline-item-head">
                        <div>
                            <span class="code">${escapeHtml(i.taskCode)}</span>
                            <span class="badge badge-group-post">${escapeHtml(i.careGroupLabel || "")}</span>
                            <span class="badge badge-group-appt">${escapeHtml(i.channelLabel)}</span>
                            <span class="badge ${i.contactResult === "CONNECTED" || i.contactResult === "SCHEDULED_APPOINTMENT" ? "status-completed" : "status-progress"}">
                                ${escapeHtml(i.contactResultLabel)}
                            </span>
                        </div>
                        <span class="timeline-time">${escapeHtml(formatDateTime(i.createdAt))}</span>
                    </div>
                    <p class="timeline-notes">${escapeHtml(i.notes)}</p>
                    <div class="timeline-footer">
                        <span>Nhân viên CSKH: <strong>${escapeHtml(i.staffName || "—")}</strong> (${escapeHtml(i.branchName || "")})</span>
                        ${i.nextRetryAt ? `<span style="color:#b45309;font-weight:700;"> · Lịch hẹn gọi lại: ${escapeHtml(formatDateTime(i.nextRetryAt))}</span>` : ""}
                    </div>
                </div>
            `).join("") : '<p class="empty">Bệnh nhân này chưa có lịch sử tương tác liên hệ nào.</p>';

            $("patientTasksContainer").innerHTML = tasks.length ? tasks.map(t => {
                const examUrl = t.examinationDetailUrl || (t.visitId ? `/doctor/examination/${t.visitId}/diagnoses#tab-instructions` : null);
                return `
                <div class="patient-task-card">
                    <div style="display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:6px;">
                        <div>
                            <span class="code">${escapeHtml(t.taskCode)}</span> ·
                            <span class="badge ${getCareGroupBadgeClass(t.careGroup)}">${escapeHtml(t.careGroupLabel)}</span>
                        </div>
                        <span class="badge ${getStatusBadgeClass(t.status)}">${escapeHtml(t.statusLabel)}</span>
                    </div>
                    <div style="font-size:13px;margin-bottom:6px;">${escapeHtml(t.notes || "—")}</div>
                    ${t.diagnosisSummary ? `<div style="font-size:12px;color:#1e293b;margin-bottom:4px;"><strong>Chẩn đoán:</strong> ${escapeHtml(t.diagnosisSummary)}</div>` : ""}
                    ${t.treatmentInstructions ? `<div style="font-size:12px;color:#0369a1;margin-bottom:4px;"><strong>Lời dặn ĐT:</strong> ${escapeHtml(t.treatmentInstructions)}</div>` : ""}
                    ${t.doctorResponse ? `<div class="doc-reply-snippet"><strong>Phản hồi BS ${escapeHtml(t.escalatedToDoctorName || "")}:</strong> ${escapeHtml(t.doctorResponse)}</div>` : ""}
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px;font-size:12px;color:#64748b;flex-wrap:wrap;gap:6px;">
                        <span>Hạn: <strong>${escapeHtml(t.dueDate || "—")}</strong> · Phụ trách: <strong>${escapeHtml(t.assignedStaffName || "—")}</strong></span>
                        <div style="display:flex;gap:6px;">
                            ${examUrl ? `<a href="${escapeHtml(examUrl)}" target="_blank" rel="noopener" class="button button-secondary button-sm">Hồ sơ khám</a>` : ""}
                            <button type="button" class="button button-primary button-sm" data-record-interaction="${t.taskId}">Chi tiết / Ghi KQ</button>
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
    // 8. CHUYỂN ĐỔI TABS & GẮN SỰ KIỆN
    // =========================================================================

    function switchTab(tab) {
        state.activeTab = tab;
        const tabSelect = $("filterTabGroup");
        if (tabSelect && tabSelect.value !== tab) {
            tabSelect.value = tab;
        }
        document.querySelectorAll(".tab").forEach(btn => {
            btn.classList.toggle("active", btn.dataset.tab === tab);
        });

        const isHistory = tab === "HISTORY";
        if ($("tasksPanel")) $("tasksPanel").hidden = isHistory;
        if ($("historyPanel")) $("historyPanel").hidden = !isHistory;

        // Ẩn/hiện bộ lọc chi tiết công việc khi chuyển sang tab Lịch sử BN
        document.querySelectorAll(".task-filter-field").forEach(el => {
            el.style.display = isHistory ? "none" : "";
        });

        if (!isHistory) {
            const meta = TAB_META[tab] || TAB_META.ALL;
            if ($("tasksPanelTitle")) $("tasksPanelTitle").textContent = meta.title || "";
            if ($("tasksPanelSubtitle")) $("tasksPanelSubtitle").textContent = meta.subtitle || "";
            loadTasks(0);
        } else {
            const histSelect = $("historyPatientSelect");
            const selectedPatient = (histSelect ? histSelect.value : "")
                || (state.lookups.patients[0]?.profileId ? String(state.lookups.patients[0].profileId) : "");
            if (selectedPatient) {
                loadPatientHistory(selectedPatient);
            }
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        safeOn("filterTabGroup", "change", () => {
            if ($("filterStatus")) $("filterStatus").value = "";
            switchTab($("filterTabGroup").value);
        });

        document.querySelectorAll(".tab").forEach(btn => {
            btn.addEventListener("click", () => {
                if ($("filterStatus")) $("filterStatus").value = "";
                switchTab(btn.dataset.tab);
            });
        });

        document.querySelectorAll("[data-kpi-tab]").forEach(card => {
            const activate = () => {
                const target = card.dataset.kpiTab;
                if (target === "COMPLETED_FILTER") {
                    if ($("filterStatus")) $("filterStatus").value = "COMPLETED";
                    switchTab("ALL");
                } else {
                    if ($("filterStatus")) $("filterStatus").value = "";
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

        safeOn("branchId", "change", async () => {
            state.branchId = $("branchId").value ? Number($("branchId").value) : null;
            await refreshAllData();
        });

        safeOn("searchBtn", "click", () => loadTasks(0));
        safeOn("clearFilterBtn", "click", () => {
            if ($("keyword")) $("keyword").value = "";
            if ($("filterStaffId")) $("filterStaffId").value = "";
            if ($("filterStatus")) $("filterStatus").value = "";
            if ($("filterFromDate")) $("filterFromDate").value = "";
            if ($("filterToDate")) $("filterToDate").value = "";
            loadTasks(0);
        });

        safeOn("keyword", "input", () => {
            clearTimeout(state.keywordDebounceTimer);
            state.keywordDebounceTimer = setTimeout(() => {
                loadTasks(0);
            }, 350);
        });

        safeOn("keyword", "keydown", (e) => {
            if (e.key === "Enter") {
                e.preventDefault();
                clearTimeout(state.keywordDebounceTimer);
                loadTasks(0);
            }
        });
        safeOn("filterStaffId", "change", () => loadTasks(0));
        safeOn("filterStatus", "change", () => loadTasks(0));

        safeOn("tasksPrevBtn", "click", () => loadTasks(Math.max(0, state.page - 1)));
        safeOn("tasksNextBtn", "click", () => loadTasks(state.page + 1));

        safeOn("openCreateTaskBtn", "click", () => openTaskFormModal());
        safeOn("autoGenerateBtn", "click", handleAutoGenerateTasks);
        safeOn("refreshBtn", "click", async () => {
            await refreshAllData();
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

        // Đảm bảo click vào bên trong dialog không bao giờ kích hoạt đóng backdrop
        document.querySelectorAll(".cc-modal-dialog, .modal-dialog").forEach(dialog => {
            dialog.addEventListener("click", (e) => {
                e.stopPropagation();
            });
        });

        ["taskFormModal", "interactionModal", "escalateModal", "doctorResponseModal"].forEach(modalId => {
            const modalEl = $(modalId);
            if (modalEl) {
                modalEl.addEventListener("click", (e) => {
                    if (e.target === modalEl) modalEl.hidden = true;
                });
            }
        });

        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape") {
                ["taskFormModal", "interactionModal", "escalateModal", "doctorResponseModal"].forEach(modalId => {
                    const modalEl = $(modalId);
                    if (modalEl && !modalEl.hidden) modalEl.hidden = true;
                });
            }
        });

        // Delegated actions in tables
        document.body.addEventListener("click", async (e) => {
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

            const histBtn = e.target.closest("[data-view-history]");
            if (histBtn) {
                const profileId = histBtn.dataset.viewHistory;
                $("historyPatientSelect").value = String(profileId);
                switchTab("HISTORY");
                return;
            }

            const editBtn = e.target.closest("[data-edit-task]");
            if (editBtn) {
                try {
                    const t = await request(`/api/customer-care/tasks/${editBtn.dataset.editTask}`);
                    openTaskFormModal(t);
                } catch (err) {
                    showMessage(err.message, "error");
                }
            }
        });

        loadBranchesAndInit();
    });
})();
