/**
 * CV-DCMS · QUẢN LÝ KẾT QUẢ PHIM X-QUANG NANOPIX (FT12)
 * SCR-12.1: Danh sách & Trình xem ảnh (Viewer)
 * SCR-12.2: Danh sách kết quả chưa khớp hồ sơ
 * SCR-12.3: Form đối chiếu & Liên kết kết quả
 * SCR-12.4: Trạng thái đồng bộ & Giám sát kết nối
 */

(function () {
    "use strict";

    const state = {
        activeTab: "ARCHIVE",
        page: 0,
        pageSize: 12,
        totalPages: 0,
        currentXray: null,
        viewer: {
            zoom: 1.0,
            rotation: 0,
            invert: false,
            brightness: 100,
            contrast: 100,
            panX: 0,
            panY: 0,
            isDragging: false,
            startX: 0,
            startY: 0
        }
    };

    const $ = (id) => document.getElementById(id);

    async function apiRequest(url, options = {}) {
        const res = await fetch(url, {
            credentials: "same-origin",
            headers: {
                "Accept": "application/json",
                ...(options.headers || {})
            },
            ...options
        });

        if (!res.ok) {
            let errorMsg = `Lỗi yêu cầu (${res.status})`;
            try {
                const data = await res.json();
                errorMsg = data.message || data.error || errorMsg;
            } catch (_) {
                try {
                    const text = await res.text();
                    if (text) errorMsg = text;
                } catch (_) {}
            }
            if (res.status === 401) {
                errorMsg = "Phiên làm việc đã hết hạn hoặc chưa đăng nhập. Vui lòng đăng nhập lại.";
            } else if (res.status === 403) {
                errorMsg = "Bạn không có quyền truy cập hoặc thao tác trên cơ sở này.";
            }
            throw new Error(errorMsg);
        }
        return res.json();
    }

    function showToast(message, type = "info") {
        const toast = $("xrayToast");
        if (!toast) return;
        toast.textContent = message;
        toast.className = `xray-toast toast-${type} show`;
        clearTimeout(toast._timer);
        toast._timer = setTimeout(() => {
            toast.className = "xray-toast";
        }, 3500);
    }

    function escapeHtml(str) {
        if (!str) return "";
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }

    // =========================================================================
    // 1. TẢI DỮ LIỆU & THỐNG KÊ (SCR-12.4)
    // =========================================================================

    async function loadStats() {
        try {
            const stats = await apiRequest("/api/xray/stats");
            $("statTotalCount").textContent = stats.totalCount || 0;
            $("statMatchedCount").textContent = stats.matchedCount || 0;
            $("statUnmatchedCount").textContent = stats.unmatchedCount || 0;
            $("unmatchedTabBadge").textContent = stats.unmatchedCount || 0;

            if (stats.unmatchedCount > 0) {
                $("unmatchedTabBadge").classList.add("badge-alert");
            } else {
                $("unmatchedTabBadge").classList.remove("badge-alert");
            }

            $("statAdapterStatus").textContent = stats.adapterStatus === "ONLINE" ? "Sẵn sàng (Online)" : "Chờ / Kiểm tra";
            $("monitorStatusText").textContent = stats.adapterStatus === "ONLINE" ? "🟢 ĐANG HOẠT ĐỘNG BÌNH THƯỜNG" : "🟡 CHỜ KẾT NỐI";
            $("monitorLastSync").textContent = stats.lastSyncTime || "—";
            $("monitorDeviceSN").textContent = stats.deviceSerialNumber || "EN1E0408A032";
            $("monitorDeviceModel").textContent = stats.deviceModel || "NanoPix1 (Eighteeth)";
            $("monitorStorageDir").textContent = stats.storageDirectory || "uploads/nanopix/";
            $("monitorMessage").textContent = stats.connectionMessage || "Sẵn sàng tiếp nhận ảnh X-quang.";
        } catch (err) {
            console.error("Lỗi khi tải thống kê NanoPix:", err);
        }
    }

    // =========================================================================
    // 2. KHO PHIM X-QUANG & BỘ LỌC (SCR-12.1)
    // =========================================================================

    async function loadArchive(page = state.page) {
        state.page = page;
        const container = $("xrayCardsGrid");
        container.innerHTML = '<div style="grid-column: 1 / -1; text-align: center; padding: 40px; color: #64748b;">Đang tải danh sách phim X-quang...</div>';

        const status = $("filterStatus").value || "ALL";
        const keyword = $("filterKeyword").value.trim();
        const fromDate = $("filterFromDate").value;
        const toDate = $("filterToDate").value;

        const params = new URLSearchParams({
            page,
            size: state.pageSize,
            status
        });
        if (keyword) params.set("keyword", keyword);
        if (fromDate) params.set("fromDate", fromDate);
        if (toDate) params.set("toDate", toDate);

        try {
            const data = await apiRequest(`/api/xray/list?${params.toString()}`);
            const items = data.content || [];
            state.totalPages = data.totalPages || 0;

            $("pageInfoText").textContent = `Trang ${state.totalPages ? page + 1 : 0} / ${state.totalPages} (Tổng ${data.totalElements || 0} phim)`;
            $("prevPageBtn").disabled = page <= 0;
            $("nextPageBtn").disabled = page + 1 >= state.totalPages;

            if (!items.length) {
                container.innerHTML = '<div style="grid-column: 1 / -1; text-align: center; padding: 48px; background: #fff; border-radius: 12px; border: 1px dashed #cbd5e1; color: #64748b;">Không tìm thấy phim X-quang nào phù hợp với bộ lọc hiện tại.</div>';
                return;
            }

            container.innerHTML = items.map(item => {
                const isMatched = item.syncStatus === "MATCHED";
                const badgeCls = isMatched ? "badge-matched" : (item.syncStatus === "ERROR" ? "badge-error" : "badge-unmatched");
                const patientDisplay = isMatched ? item.patientName : (item.rawPatientName ? `${item.rawPatientName} (Chưa đối chiếu)` : "Chưa xác định");

                return `
                    <div class="xray-card">
                        <div class="xray-thumb-wrap" data-view-id="${item.syncLogId}">
                            <img class="xray-thumb" src="${escapeHtml(item.imageUrl)}" alt="X-ray ${escapeHtml(item.externalStudyUid)}" loading="lazy">
                            <span class="badge ${badgeCls} status-badge-float">${escapeHtml(item.syncStatusLabel)}</span>
                            <div class="xray-thumb-overlay">
                                🔍 Nhấn để Soi phim
                            </div>
                        </div>
                        <div class="xray-card-body">
                            <div>
                                <span class="study-uid-code" title="Mã tham chiếu máy chụp">${escapeHtml(item.externalStudyUid)}</span>
                                <h4 class="patient-title" title="${escapeHtml(patientDisplay)}">
                                    ${escapeHtml(patientDisplay)}
                                </h4>
                                <div class="xray-meta-row">
                                    <span>Ca khám:</span>
                                    <strong>${escapeHtml(item.visitCode || "—")}</strong>
                                </div>
                                <div class="xray-meta-row">
                                    <span>Vị trí răng:</span>
                                    <strong>${escapeHtml(item.toothNumbers || "Toàn hàm / Không rõ")}</strong>
                                </div>
                                <div class="xray-meta-row">
                                    <span>Chụp lúc:</span>
                                    <span>${escapeHtml(item.capturedAtFormatted || "—")}</span>
                                </div>
                            </div>
                            <div class="xray-card-actions">
                                <button type="button" class="button button-secondary button-sm" data-view-id="${item.syncLogId}">
                                    🔍 Soi phim
                                </button>
                                <button type="button" class="button ${isMatched ? 'button-secondary' : 'button-primary'} button-sm" data-reconcile-id="${item.syncLogId}">
                                    ${isMatched ? '✏️ Đổi ca' : '🔗 Đối chiếu'}
                                </button>
                            </div>
                        </div>
                    </div>
                `;
            }).join("");
        } catch (err) {
            container.innerHTML = `<div style="grid-column: 1 / -1; text-align: center; padding: 40px; color: #dc2626;">Lỗi tải dữ liệu: ${escapeHtml(err.message)}</div>`;
            showToast(err.message, "error");
        }
    }

    // =========================================================================
    // 3. DANH SÁCH CHƯA KHỚP HỒ SƠ (SCR-12.2)
    // =========================================================================

    async function loadUnmatched() {
        const tbody = $("unmatchedTableBody");
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:28px;color:#64748b;">Đang tải danh sách phim chưa đối chiếu...</td></tr>';

        const keyword = $("unmatchedKeyword").value.trim();
        const params = new URLSearchParams();
        if (keyword) params.set("keyword", keyword);

        try {
            const list = await apiRequest(`/api/xray/unmatched?${params.toString()}`);
            if (!list.length) {
                tbody.innerHTML = '<tr><td colspan="7" style="text-align:center;padding:32px;color:#15803d;font-weight:700;">✓ Tuyệt vời! Tất cả kết quả phim X-quang NanoPix đã được đối chiếu khớp hồ sơ bệnh nhân.</td></tr>';
                return;
            }

            tbody.innerHTML = list.map(item => `
                <tr>
                    <td style="width: 70px;">
                        <img class="mini-thumb" src="${escapeHtml(item.imageUrl)}" alt="thumb" data-view-id="${item.syncLogId}">
                    </td>
                    <td>
                        <strong class="study-uid-code">${escapeHtml(item.externalStudyUid)}</strong>
                        <div style="font-size:11px;color:#64748b;">Sensor: ${escapeHtml(item.deviceSerialNumber || "NanoPix1")}</div>
                    </td>
                    <td>
                        <strong style="color:#0f172a;">${escapeHtml(item.rawPatientName || item.externalPatientId || "Không có tên trên máy")}</strong>
                        ${item.externalPatientId ? `<div style="font-size:11.5px;color:#0284c7;">Mã nguồn: ${escapeHtml(item.externalPatientId)}</div>` : ""}
                    </td>
                    <td>${escapeHtml(item.capturedAtFormatted || "—")}</td>
                    <td>
                        <span class="badge badge-unmatched">Chưa khớp</span>
                    </td>
                    <td>
                        <span style="font-size:12px;color:#b45309;">${escapeHtml(item.errorMessage || "Chưa gắn vào ca khám nào")}</span>
                    </td>
                    <td>
                        <button type="button" class="button button-primary button-sm" data-reconcile-id="${item.syncLogId}">
                            🔗 Đối chiếu ngay
                        </button>
                    </td>
                </tr>
            `).join("");
        } catch (err) {
            tbody.innerHTML = `<tr><td colspan="7" style="text-align:center;padding:28px;color:#dc2626;">Lỗi tải danh sách: ${escapeHtml(err.message)}</td></tr>`;
            showToast(err.message, "error");
        }
    }

    // =========================================================================
    // 4. TRÌNH SOI PHIM X-QUANG CHUYÊN NGHIỆP / VIEWER (SCR-12.1)
    // =========================================================================

    async function openViewerModal(syncLogId) {
        try {
            const item = await apiRequest(`/api/xray/${syncLogId}`);
            state.currentXray = item;

            // Reset viewer controls
            state.viewer = {
                zoom: 1.0,
                rotation: 0,
                invert: false,
                brightness: 100,
                contrast: 100,
                panX: 0,
                panY: 0,
                isDragging: false,
                startX: 0,
                startY: 0
            };

            $("viewerModalTitle").textContent = `Soi phim X-quang NanoPix · Study UID: ${item.externalStudyUid}`;
            $("viewerMainImg").src = item.imageUrl;

            // Info panel
            $("viewerPatientName").textContent = item.patientName || "Chưa xác định";
            $("viewerPatientCode").textContent = item.patientCode || "—";
            $("viewerPatientDob").textContent = item.patientDob || "—";
            $("viewerVisitCode").textContent = item.visitCode || "Chưa gắn ca khám";
            $("viewerToothNumbers").textContent = item.toothNumbers || "Toàn hàm / Chưa xác định";
            $("viewerCapturedAt").textContent = item.capturedAtFormatted || "—";
            $("viewerSyncTime").textContent = item.syncTimeFormatted || "—";
            $("viewerDeviceSN").textContent = `${item.deviceModel || 'NanoPix1'} (S/N: ${item.deviceSerialNumber || 'EN1E0408A032'})`;
            $("viewerNotes").textContent = item.notes || "Không có ghi chú.";

            // Reset filters on DOM
            $("viewerBrightnessRange").value = 100;
            $("viewerContrastRange").value = 100;
            $("viewerInvertBtn").classList.remove("active");

            applyViewerTransform();
            $("viewerModal").hidden = false;
        } catch (err) {
            showToast(err.message, "error");
        }
    }

    function applyViewerTransform() {
        const img = $("viewerMainImg");
        if (!img) return;

        const v = state.viewer;
        const filterStr = `brightness(${v.brightness}%) contrast(${v.contrast}%) ${v.invert ? 'invert(100%)' : ''}`;
        const transformStr = `translate(${v.panX}px, ${v.panY}px) scale(${v.zoom}) rotate(${v.rotation}deg)`;

        img.style.filter = filterStr;
        img.style.transform = transformStr;
    }

    // =========================================================================
    // 5. FORM ĐỐI CHIẾU & LIÊN KẾT KẾT QUẢ (SCR-12.3)
    // =========================================================================

    async function openReconcileModal(syncLogId) {
        try {
            const item = await apiRequest(`/api/xray/${syncLogId}`);
            state.currentXray = item;

            $("reconcileSyncLogId").value = item.syncLogId;
            $("reconcileStudyUid").textContent = item.externalStudyUid;
            $("reconcileRawName").textContent = item.rawPatientName || item.externalPatientId || "(Không có tên trên máy NanoPix)";
            $("reconcileCapturedAt").textContent = item.capturedAtFormatted || "—";
            $("reconcileDevice").textContent = `${item.deviceModel || 'NanoPix1'} (${item.deviceSerialNumber || 'EN1E0408A032'})`;
            $("reconcilePreviewImg").src = item.imageUrl;

            $("reconcileToothNumbers").value = item.toothNumbers || "";
            $("reconcileNotes").value = item.notes || "";

            // Nạp danh sách bệnh nhân nếu chưa có
            await loadPatientsForReconcile();

            // Nếu đã từng liên kết hồ sơ thì chọn lại
            if (item.profileId) {
                $("reconcileProfileSelect").value = String(item.profileId);
                await handlePatientChange(item.profileId, item.visitId, item.orderItemId);
            } else {
                $("reconcileProfileSelect").value = "";
                $("reconcileVisitSelect").innerHTML = '<option value="">-- Vui lòng chọn bệnh nhân trước --</option>';
                $("reconcileOrderItemSelect").innerHTML = '<option value="">-- Tùy chọn chỉ định CLS --</option>';
            }

            $("reconcileModal").hidden = false;
        } catch (err) {
            showToast(err.message, "error");
        }
    }

    async function loadPatientsForReconcile() {
        const select = $("reconcileProfileSelect");
        if (select.options.length > 1) return; // Đã tải trước đó

        try {
            // Lấy danh sách bệnh nhân từ API customer-care lookups hoặc patient search
            const res = await apiRequest("/api/customer-care/lookups?branchId=");
            const patients = res.patients || [];
            select.innerHTML = '<option value="">-- Tìm & Chọn Bệnh nhân --</option>' +
                patients.map(p => `<option value="${p.profileId}">${escapeHtml(p.fullName)} (${escapeHtml(p.patientCode || 'Mã BN')}) - ${escapeHtml(p.phoneNumber || '')}</option>`).join("");
        } catch (_) {
            select.innerHTML = '<option value="">-- Vui lòng nhập ID bệnh nhân --</option>';
        }
    }

    async function handlePatientChange(profileId, preselectVisitId = null, preselectOrderItemId = null) {
        const visitSelect = $("reconcileVisitSelect");
        const itemSelect = $("reconcileOrderItemSelect");

        if (!profileId) {
            visitSelect.innerHTML = '<option value="">-- Vui lòng chọn bệnh nhân trước --</option>';
            itemSelect.innerHTML = '<option value="">-- Tùy chọn chỉ định CLS --</option>';
            return;
        }

        visitSelect.innerHTML = '<option value="">Đang tải lượt khám...</option>';
        try {
            const data = await apiRequest(`/api/xray/lookups/patient-visits/${profileId}`);
            const visits = data.visits || [];
            const orders = data.orders || [];

            if (!visits.length) {
                visitSelect.innerHTML = '<option value="">Bệnh nhân này chưa có lượt khám nào</option>';
            } else {
                visitSelect.innerHTML = '<option value="">-- Chọn ca khám tương ứng --</option>' +
                    visits.map(v => `<option value="${v.visitId}" ${preselectVisitId && String(v.visitId) === String(preselectVisitId) ? 'selected' : ''}>Ca khám ${escapeHtml(v.visitCode)} (${escapeHtml(v.checkInAt)}) - Trạng thái: ${escapeHtml(v.status)}</option>`).join("");
            }

            if (!orders.length) {
                itemSelect.innerHTML = '<option value="">Không có chỉ định X-quang đang chờ (Sẽ tạo liên kết trực tiếp ca khám)</option>';
            } else {
                itemSelect.innerHTML = '<option value="">-- Tùy chọn gắn vào Chỉ định CLS --</option>' +
                    orders.map(o => `<option value="${o.orderItemId}" ${preselectOrderItemId && String(o.orderItemId) === String(preselectOrderItemId) ? 'selected' : ''}>${escapeHtml(o.serviceName)} (Răng: ${escapeHtml(o.toothNumbers || 'Toàn hàm')})</option>`).join("");
            }
        } catch (err) {
            visitSelect.innerHTML = '<option value="">Lỗi tải lượt khám</option>';
        }
    }

    async function submitReconcileForm(event) {
        event.preventDefault();
        const syncLogId = $("reconcileSyncLogId").value;
        const profileId = $("reconcileProfileSelect").value;
        const visitId = $("reconcileVisitSelect").value;
        const orderItemId = $("reconcileOrderItemSelect").value;
        const toothNumbers = $("reconcileToothNumbers").value.trim();
        const notes = $("reconcileNotes").value.trim();

        if (!profileId) {
            showToast("Vui lòng chọn hồ sơ bệnh nhân.", "warning");
            $("reconcileProfileSelect").focus();
            return;
        }
        if (!visitId) {
            showToast("Vui lòng chọn ca khám tương ứng của bệnh nhân.", "warning");
            $("reconcileVisitSelect").focus();
            return;
        }

        const payload = {
            profileId: Number(profileId),
            visitId: Number(visitId),
            orderItemId: orderItemId ? Number(orderItemId) : null,
            toothNumbers,
            notes
        };

        const btn = $("submitReconcileBtn");
        btn.disabled = true;
        btn.textContent = "⏳ Đang đối chiếu...";

        try {
            await apiRequest(`/api/xray/${syncLogId}/reconcile`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });

            $("reconcileModal").hidden = true;
            showToast("Đối chiếu và liên kết phim X-quang thành công!", "success");
            await Promise.all([loadStats(), loadArchive(), loadUnmatched()]);
        } catch (err) {
            showToast(err.message, "error");
        } finally {
            btn.disabled = false;
            btn.textContent = "✓ Xác nhận liên kết hồ sơ";
        }
    }

    // =========================================================================
    // 6. NẠP PHIM X-QUANG TỪ NANOPIX (SCR-12.4 CONTROLLED INGESTION)
    // =========================================================================

    function openIngestModal() {
        $("ingestForm").reset();
        const now = new Date();
        const timeStr = now.getFullYear().toString() +
            String(now.getMonth() + 1).padStart(2, "0") +
            String(now.getDate()).padStart(2, "0") + "_" +
            String(now.getHours()).padStart(2, "0") +
            String(now.getMinutes()).padStart(2, "0") +
            String(now.getSeconds()).padStart(2, "0");

        $("ingestStudyUid").value = `NANOPIX_${timeStr}`;
        $("ingestDeviceSN").value = "EN1E0408A032";
        $("ingestDeviceModel").value = "NanoPix1";

        const tzOffset = now.getTimezoneOffset() * 60000;
        $("ingestCapturedAt").value = new Date(now - tzOffset).toISOString().slice(0, 16);

        $("ingestModal").hidden = false;
    }

    async function submitIngestForm(event) {
        event.preventDefault();
        const fileInput = $("ingestFileInput");
        if (!fileInput.files || !fileInput.files[0]) {
            showToast("Vui lòng chọn file ảnh chụp X-quang từ máy NanoPix.", "warning");
            return;
        }

        const formData = new FormData();
        formData.append("file", fileInput.files[0]);
        formData.append("externalStudyUid", $("ingestStudyUid").value.trim());
        formData.append("rawPatientName", $("ingestRawName").value.trim());
        formData.append("externalPatientId", $("ingestPatientCode").value.trim());
        formData.append("deviceSerialNumber", $("ingestDeviceSN").value.trim());
        formData.append("deviceModel", $("ingestDeviceModel").value.trim());
        formData.append("toothNumbers", $("ingestToothNumbers").value.trim());
        formData.append("notes", $("ingestNotes").value.trim());
        if ($("ingestCapturedAt").value) {
            formData.append("capturedAt", `${$("ingestCapturedAt").value}:00`);
        }

        const btn = $("submitIngestBtn");
        btn.disabled = true;
        btn.textContent = "⏳ Đang nạp ảnh...";

        try {
            const res = await apiRequest("/api/xray/ingest", {
                method: "POST",
                body: formData
            });

            $("ingestModal").hidden = true;
            showToast(`Nạp ảnh X-quang thành công! Trạng thái: ${res.syncStatusLabel}`, "success");
            await Promise.all([loadStats(), loadArchive(0), loadUnmatched()]);
        } catch (err) {
            showToast(err.message, "error");
        } finally {
            btn.disabled = false;
            btn.textContent = "✓ Nạp ảnh vào hệ thống";
        }
    }

    // =========================================================================
    // 7. GẮN SỰ KIỆN & KHỞI TẠO TRANG
    // =========================================================================

    function switchTab(tabName) {
        state.activeTab = tabName;
        document.querySelectorAll(".tab").forEach(t => {
            t.classList.toggle("active", t.dataset.tab === tabName);
        });

        $("archivePanel").hidden = tabName !== "ARCHIVE";
        $("unmatchedPanel").hidden = tabName !== "UNMATCHED";
        $("monitorPanel").hidden = tabName !== "MONITOR";

        if (tabName === "ARCHIVE") {
            loadArchive(state.page);
        } else if (tabName === "UNMATCHED") {
            loadUnmatched();
        } else if (tabName === "MONITOR") {
            loadStats();
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        // Chuyển Tab
        document.querySelectorAll(".tab").forEach(tab => {
            tab.addEventListener("click", () => switchTab(tab.dataset.tab));
        });

        // Bộ lọc Kho phim (SCR-12.1)
        $("searchBtn").addEventListener("click", () => loadArchive(0));
        $("resetFilterBtn").addEventListener("click", () => {
            $("filterKeyword").value = "";
            $("filterStatus").value = "ALL";
            $("filterFromDate").value = "";
            $("filterToDate").value = "";
            loadArchive(0);
        });
        $("filterKeyword").addEventListener("keydown", (e) => {
            if (e.key === "Enter") {
                e.preventDefault();
                loadArchive(0);
            }
        });
        $("filterStatus").addEventListener("change", () => loadArchive(0));

        // Phân trang
        $("prevPageBtn").addEventListener("click", () => loadArchive(Math.max(0, state.page - 1)));
        $("nextPageBtn").addEventListener("click", () => loadArchive(state.page + 1));

        // Bộ lọc chưa khớp
        $("unmatchedSearchBtn").addEventListener("click", loadUnmatched);
        $("unmatchedKeyword").addEventListener("keydown", (e) => {
            if (e.key === "Enter") {
                e.preventDefault();
                loadUnmatched();
            }
        });

        // Quick Topbar Buttons
        $("openIngestBtn").addEventListener("click", openIngestModal);
        $("checkConnectionBtn").addEventListener("click", async () => {
            await loadStats();
            showToast("Kiểm tra kết nối NanoPix: Bộ điều hợp sẵn sàng tiếp nhận ảnh.", "success");
        });
        $("refreshAllBtn").addEventListener("click", async () => {
            await Promise.all([loadStats(), loadArchive(0), loadUnmatched()]);
            showToast("Đã làm mới dữ liệu X-quang.", "success");
        });

        // Viewer Toolbar Buttons
        $("viewerZoomInBtn").addEventListener("click", () => {
            state.viewer.zoom = Math.min(4.0, state.viewer.zoom + 0.25);
            applyViewerTransform();
        });
        $("viewerZoomOutBtn").addEventListener("click", () => {
            state.viewer.zoom = Math.max(0.5, state.viewer.zoom - 0.25);
            applyViewerTransform();
        });
        $("viewerResetBtn").addEventListener("click", () => {
            state.viewer.zoom = 1.0;
            state.viewer.rotation = 0;
            state.viewer.panX = 0;
            state.viewer.panY = 0;
            applyViewerTransform();
        });
        $("viewerRotateLeftBtn").addEventListener("click", () => {
            state.viewer.rotation = (state.viewer.rotation - 90) % 360;
            applyViewerTransform();
        });
        $("viewerRotateRightBtn").addEventListener("click", () => {
            state.viewer.rotation = (state.viewer.rotation + 90) % 360;
            applyViewerTransform();
        });
        $("viewerInvertBtn").addEventListener("click", () => {
            state.viewer.invert = !state.viewer.invert;
            $("viewerInvertBtn").classList.toggle("active", state.viewer.invert);
            applyViewerTransform();
        });
        $("viewerBrightnessRange").addEventListener("input", (e) => {
            state.viewer.brightness = e.target.value;
            applyViewerTransform();
        });
        $("viewerContrastRange").addEventListener("input", (e) => {
            state.viewer.contrast = e.target.value;
            applyViewerTransform();
        });
        $("viewerDownloadBtn").addEventListener("click", () => {
            if (!state.currentXray || !state.currentXray.imageUrl) return;
            const a = document.createElement("a");
            a.href = state.currentXray.imageUrl;
            a.download = `XRAY_${state.currentXray.externalStudyUid}.jpg`;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        });

        // Drag to Pan Viewer
        const stage = $("viewerStage");
        stage.addEventListener("mousedown", (e) => {
            state.viewer.isDragging = true;
            state.viewer.startX = e.clientX - state.viewer.panX;
            state.viewer.startY = e.clientY - state.viewer.panY;
        });
        window.addEventListener("mousemove", (e) => {
            if (!state.viewer.isDragging) return;
            state.viewer.panX = e.clientX - state.viewer.startX;
            state.viewer.panY = e.clientY - state.viewer.startY;
            applyViewerTransform();
        });
        window.addEventListener("mouseup", () => {
            state.viewer.isDragging = false;
        });
        stage.addEventListener("wheel", (e) => {
            e.preventDefault();
            const delta = e.deltaY < 0 ? 0.15 : -0.15;
            state.viewer.zoom = Math.min(5.0, Math.max(0.4, state.viewer.zoom + delta));
            applyViewerTransform();
        }, { passive: false });

        // Reconciliation events
        $("reconcileProfileSelect").addEventListener("change", (e) => handlePatientChange(e.target.value));
        $("reconcileForm").addEventListener("submit", submitReconcileForm);

        // Controlled Ingestion form
        $("ingestForm").addEventListener("submit", submitIngestForm);

        // Close Modals
        document.querySelectorAll("[data-close-modal]").forEach(btn => {
            btn.addEventListener("click", () => {
                const target = btn.dataset.closeModal;
                if ($(target)) $(target).hidden = true;
            });
        });

        // Delegated clicks for view and reconcile
        document.body.addEventListener("click", (e) => {
            const viewTarget = e.target.closest("[data-view-id]");
            if (viewTarget) {
                openViewerModal(viewTarget.dataset.viewId);
                return;
            }

            const recTarget = e.target.closest("[data-reconcile-id]");
            if (recTarget) {
                openReconcileModal(recTarget.dataset.reconcileId);
                return;
            }
        });

        // ESC to close modal
        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape") {
                ["viewerModal", "reconcileModal", "ingestModal"].forEach(m => {
                    const el = $(m);
                    if (el && !el.hidden) el.hidden = true;
                });
            }
        });

        // Stop propagation inside modal dialog
        document.querySelectorAll(".xray-modal-dialog").forEach(dialog => {
            dialog.addEventListener("click", (e) => e.stopPropagation());
        });

        // Initialize
        loadStats();
        loadArchive(0);
    });
})();
