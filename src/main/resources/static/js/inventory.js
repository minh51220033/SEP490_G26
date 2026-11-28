/**
 * @fileoverview Script điều khiển toàn bộ giao diện Quản lý kho vật tư ({@code inventory/list.html}).
 * <p>Kết nối REST API của các module:</p>
 * <ul>
 *   <li>Tồn kho &amp; KPI tổng quan: {@code /api/inventory}, {@code /api/inventory/summary}, {@code /api/inventory/picker-materials}</li>
 *   <li>Lô &amp; Hạn sử dụng: {@code /api/inventory/batches}, {@code /api/inventory/batches/available}</li>
 *   <li>Phiếu nhập / xuất / điều chuyển: {@code /api/inventory/receipts}, {@code /api/inventory/issues}, {@code /api/inventory/transfers}</li>
 *   <li>Danh mục vật tư &amp; Nhóm vật tư: {@code /api/materials}</li>
 *   <li>Nhà cung cấp &amp; Thanh toán công nợ: {@code /api/suppliers}, {@code /api/inventory/supplier-payments}</li>
 * </ul>
 */
(() => {
    "use strict";

    /** Tiện ích rút gọn {@code document.getElementById}. */
    const $ = (id) => document.getElementById(id);

    /** Gắn sự kiện an toàn cho phần tử DOM (bỏ qua nếu phần tử không tồn tại do phân quyền). */
    const safeOn = (id, event, handler, options) => {
        const el = typeof id === "string" ? $(id) : id;
        if (el && typeof el.addEventListener === "function") {
            el.addEventListener(event, handler, options);
            return el;
        }
        return null;
    };

    /** Tập biểu thức chính quy validate phía client khớp quy tắc Backend (số lô, hóa đơn, mã VT, mã NCC, MST, SĐT, số chứng từ). */
    const REGEX = {
        BATCH_NUMBER: /^[A-Za-z0-9._/-]{2,50}$/,
        INVOICE_NO: /^[A-Za-z0-9._/-]{1,50}$/,
        MATERIAL_CODE: /^[A-Za-z0-9_-]{3,20}$/,
        SUPPLIER_CODE: /^[A-Za-z0-9_-]{2,20}$/,
        TAX_CODE: /^[0-9]{10}(-[0-9]{3})?$/,
        PHONE: /^(0|\+84)[0-9]{9,10}$/,
        REFERENCE_NO: /^[A-Za-z0-9._/-]{1,100}$/
    };

    /** Trạng thái dùng chung của trang kho (chi nhánh hiện tại, danh mục cache, tab đang mở và chỉ số phân trang của từng bảng). */
    const state = {
        branchId: null,
        branchSelectionAllowed: false,
        allActiveBranches: [],
        activeMaterials: [],
        activeSuppliers: [],
        availableBatches: [],
        supplierDebtsMap: new Map(),

        activeTab: "inventory",
        pageSize: 10,

        inventoryPage: 0,
        inventoryTotalPages: 0,

        batchesPage: 0,
        batchesTotalPages: 0,

        receiptsPage: 0,
        receiptsTotalPages: 0,

        issuesPage: 0,
        issuesTotalPages: 0,

        transfersPage: 0,
        transfersTotalPages: 0,

        materialsPage: 0,
        materialsTotalPages: 0,

        paymentsPage: 0,
        paymentsTotalPages: 0
    };

    // Quản lý AbortController theo từng nhóm truy vấn để tránh kết quả cũ ghi đè kết quả mới
    const abortControllers = new Map();
    function getAbortSignal(key) {
        if (abortControllers.has(key)) {
            try { abortControllers.get(key).abort(); } catch { /* ignore */ }
        }
        const ctrl = new AbortController();
        abortControllers.set(key, ctrl);
        return ctrl.signal;
    }

    function debounce(fn, delayMs = 350) {
        let timer = null;
        return (...args) => {
            if (timer) clearTimeout(timer);
            timer = setTimeout(() => fn(...args), delayMs);
        };
    }

    const money = (value) => new Intl.NumberFormat("vi-VN", {
        style: "currency",
        currency: "VND",
        maximumFractionDigits: 0
    }).format(Number(value || 0));

    const fmtQty = (value) => {
        if (value == null) return "0";
        const n = Number(value);
        return Number.isNaN(n) ? String(value) : n.toLocaleString("vi-VN", { maximumFractionDigits: 3 });
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

    /**
     * Gọi REST API nội bộ kèm cookie phiên ({@code same-origin}), tự động parse JSON và chuẩn hóa thông báo lỗi tiếng Việt.
     * Tự động không gắn {@code Content-Type: application/json} khi gửi {@link FormData}.
     * @param {string} url đường dẫn API
     * @param {RequestInit} [options] cấu hình fetch (method, body, signal, headers)
     * @returns {Promise<any>} dữ liệu JSON hoặc chuỗi phản hồi từ server
     */
    async function request(url, options = {}) {
        const isFormData = options.body instanceof FormData;
        const headers = isFormData
            ? { ...(options.headers || {}) }
            : { "Content-Type": "application/json", ...(options.headers || {}) };

        const response = await fetch(url, {
            credentials: "same-origin",
            ...options,
            headers
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

    function requireBranch() {
        if (!state.branchId) {
            showMessage("Không có chi nhánh khả dụng hoặc bạn chưa được phân công chi nhánh.", "warning");
            return false;
        }
        return true;
    }

    function openImageLightbox(url, title = "Chứng từ / Hóa đơn đính kèm") {
        if (!url) return;
        if (url.toLowerCase().endsWith(".pdf")) {
            window.open(url, "_blank", "noopener");
            return;
        }
        if ($("lightboxTitle")) $("lightboxTitle").textContent = title;
        if ($("lightboxImage")) $("lightboxImage").src = url;
        if ($("lightboxDownloadLink")) $("lightboxDownloadLink").href = url;
        if ($("imageLightboxModal")) $("imageLightboxModal").hidden = false;
    }

    /**
     * Mở trang xem trước &amp; in phiếu chuẩn A4 trong tab mới kèm cờ {@code autoprint=true} để tự động gọi {@code window.print()}.
     * Nếu trình duyệt chặn popup, hiển thị liên kết mở thủ công trên thanh thông báo.
     * Được gọi từ nút "In A4" trên bảng lịch sử phiếu nhập / xuất / điều chuyển.
     * @param {string} baseUrl URL trang in (ví dụ {@code /inventory/receipts/{id}/print})
     */
    function printInventoryDocument(baseUrl) {
        if (!baseUrl) return;
        const separator = baseUrl.includes("?") ? "&" : "?";
        const targetUrl = `${baseUrl}${separator}autoprint=true`;

        // Gọi trực tiếp trong sự kiện click của user để tránh bị chặn popup
        const printWin = window.open(targetUrl, "_blank");
        if (!printWin || printWin.closed || typeof printWin.closed === "undefined") {
            showMessage(
                `Trình duyệt đã chặn cửa sổ in tự động. <a href="${escapeHtml(targetUrl)}" target="_blank" rel="noopener" style="color:#0284c7;text-decoration:underline;font-weight:600;">Nhấn vào đây để mở và in phiếu</a>.`,
                "warning"
            );
        } else {
            try {
                printWin.focus();
            } catch (e) {
                // bỏ qua nếu lỗi cross-window focus
            }
        }
    }

    // =========================================================================
    // 0. LOAD CHI NHÁNH, DANH MỤC VẬT TƯ, NHÀ CUNG CẤP & KPI SUMMARY CARDS
    // =========================================================================

    function setKpiLoading() {
        const cards = ["kpiCardTotalItems", "kpiCardLowStock", "kpiCardExpiring", "kpiCardDebt"];
        cards.forEach(id => {
            const el = $(id);
            if (el) {
                el.classList.add("is-loading");
                el.classList.remove("is-error");
            }
        });
        if ($("statTotalItems")) $("statTotalItems").textContent = "—";
        if ($("statLowStock")) $("statLowStock").textContent = "—";
        if ($("statExpiringBatches")) $("statExpiringBatches").textContent = "—";
        if ($("statTotalDebt")) $("statTotalDebt").textContent = "—";
        if ($("statTotalItemsSub")) $("statTotalItemsSub").textContent = "Đang tải dữ liệu...";
        if ($("statLowStockSub")) $("statLowStockSub").textContent = "Đang kiểm tra tồn kho...";
        if ($("statExpiringBatchesSub")) $("statExpiringBatchesSub").textContent = "Đang kiểm tra hạn dùng...";
        if ($("statTotalDebtSub")) $("statTotalDebtSub").textContent = "Đang tính công nợ...";
    }

    function setKpiError(errorMsg = "Không thể tải số liệu") {
        const cards = ["kpiCardTotalItems", "kpiCardLowStock", "kpiCardExpiring", "kpiCardDebt"];
        cards.forEach(id => {
            const el = $(id);
            if (el) {
                el.classList.remove("is-loading");
                el.classList.add("is-error");
            }
        });
        if ($("statTotalItems")) $("statTotalItems").textContent = "—";
        if ($("statLowStock")) $("statLowStock").textContent = "—";
        if ($("statExpiringBatches")) $("statExpiringBatches").textContent = "—";
        if ($("statTotalDebt")) $("statTotalDebt").textContent = "—";
        if ($("statTotalItemsSub")) $("statTotalItemsSub").textContent = errorMsg;
        if ($("statLowStockSub")) $("statLowStockSub").textContent = errorMsg;
        if ($("statExpiringBatchesSub")) $("statExpiringBatchesSub").textContent = errorMsg;
        if ($("statTotalDebtSub")) $("statTotalDebtSub").textContent = errorMsg;
    }

    /**
     * Tải và cập nhật 4 thẻ KPI tổng quan kho đầu trang từ {@code GET /api/inventory/summary?branchId=...}:
     * (1) Tổng mặt hàng trong kho, (2) Cảnh báo sắp hết/hết tồn, (3) Lô hết hạn/sắp hết hạn, (4) Tổng công nợ NCC.
     * Được gọi khi đổi chi nhánh, làm mới dữ liệu hoặc sau khi lập phiếu nhập/xuất/điều chuyển/thanh toán.
     */
    async function updateSummaryStats() {
        if (!state.branchId) {
            setKpiError("Chưa chọn chi nhánh");
            return;
        }
        setKpiLoading();
        try {
            const summary = await request(`/api/inventory/summary?branchId=${state.branchId}`);
            const cards = ["kpiCardTotalItems", "kpiCardLowStock", "kpiCardExpiring", "kpiCardDebt"];
            cards.forEach(id => {
                const el = $(id);
                if (el) {
                    el.classList.remove("is-loading", "is-error");
                }
            });

            const totalItems = Number(summary?.totalItems ?? 0);
            const lowStockItems = Number(summary?.lowStockItems ?? 0);
            const outOfStockItems = Number(summary?.outOfStockItems ?? 0);
            const expiringOrExpired = Number(summary?.expiringOrExpiredBatches ?? 0);
            const expiredBatches = Number(summary?.expiredBatches ?? 0);
            const expiringSoonBatches = Number(summary?.expiringSoonBatches ?? 0);
            const totalDebt = Number(summary?.totalSupplierDebt ?? 0);
            const suppliersWithDebt = Number(summary?.suppliersWithDebtCount ?? 0);

            if ($("statTotalItems")) $("statTotalItems").textContent = totalItems.toLocaleString("vi-VN");
            if ($("statTotalItemsSub")) {
                $("statTotalItemsSub").textContent = totalItems > 0
                    ? `${Math.max(0, totalItems - lowStockItems)} mặt hàng đủ định mức`
                    : "Chưa có vật tư trong kho chi nhánh";
            }

            if ($("statLowStock")) $("statLowStock").textContent = lowStockItems.toLocaleString("vi-VN");
            if ($("statLowStockSub")) {
                $("statLowStockSub").textContent = lowStockItems > 0
                    ? (outOfStockItems > 0 ? `Trong đó ${outOfStockItems} mặt hàng đã hết tồn (0)` : "Cần lập phiếu nhập bổ sung")
                    : "Tất cả mặt hàng đều trên ngưỡng tối thiểu";
            }

            if ($("statExpiringBatches")) $("statExpiringBatches").textContent = expiringOrExpired.toLocaleString("vi-VN");
            if ($("statExpiringBatchesSub")) {
                $("statExpiringBatchesSub").textContent = expiringOrExpired > 0
                    ? `${expiredBatches} lô quá hạn · ${expiringSoonBatches} lô ≤ 30 ngày`
                    : "Không có lô hàng tồn nào sắp/đã hết hạn";
            }

            if ($("statTotalDebt")) $("statTotalDebt").textContent = money(totalDebt);
            if ($("statTotalDebtSub")) {
                $("statTotalDebtSub").textContent = totalDebt > 0
                    ? `Đang nợ ${suppliersWithDebt} nhà cung cấp`
                    : "Không có công nợ tồn đọng tại chi nhánh";
            }
        } catch {
            setKpiError("Không tải được số liệu tổng hợp");
        }
    }

    /**
     * Tải danh sách chi nhánh người dùng được phép truy cập ({@code GET /api/branches/accessible}) và toàn bộ chi nhánh
     * đang hoạt động ({@code GET /api/branches/active}) để dựng dropdown chọn cơ sở và chi nhánh đích điều chuyển.
     * Sau khi xác định {@code state.branchId}, đồng thời nạp danh mục vật tư, danh mục NCC và làm mới toàn bộ dữ liệu kho.
     * Được gọi khi khởi tạo trang trong {@code DOMContentLoaded}.
     */
    async function loadBranches() {
        const select = $("branchId");
        setKpiLoading();
        try {
            const [accessData, allActive] = await Promise.all([
                request("/api/branches/accessible"),
                request("/api/branches/active").catch(() => [])
            ]);

            const branches = Array.isArray(accessData?.branches) ? accessData.branches : [];
            state.branchSelectionAllowed = Boolean(accessData?.branchSelectionAllowed);
            state.allActiveBranches = Array.isArray(allActive) && allActive.length ? allActive : branches;
            select.innerHTML = "";

            if (!branches.length) {
                select.innerHTML = '<option value="">Không có chi nhánh được phép truy cập</option>';
                select.disabled = true;
                $("fixedBranch").hidden = true;
                state.branchId = null;
                setKpiError("Chưa phân công chi nhánh");
                showMessage("Tài khoản hiện tại chưa có chi nhánh được phép truy cập. Hãy kiểm tra phân công chi nhánh.", "warning");
                return;
            }

            branches.forEach(branch => {
                const option = document.createElement("option");
                option.value = branch.branchId;
                option.textContent = `${branch.branchCode ? branch.branchCode + " - " : ""}${branch.branchName}`;
                select.appendChild(option);
            });

            const urlBranchId = Number(new URLSearchParams(window.location.search).get("branchId") || 0); const currentBranchIdAttr = urlBranchId || Number(document.body?.dataset?.currentBranchId || 0);
            const preferredBranch = branches.find(b => Number(b.branchId) === currentBranchIdAttr) || branches[0];

            if (state.branchSelectionAllowed) {
                select.disabled = false;
                $("fixedBranch").hidden = true;
                select.value = String(preferredBranch.branchId);
            } else {
                select.disabled = true;
                $("fixedBranch").hidden = false;
                $("fixedBranchName").textContent = branches.length === 1
                    ? branches[0].branchName
                    : `${preferredBranch.branchName} (chi nhánh được phân công)`;
                select.value = String(preferredBranch.branchId);
            }

            state.branchId = Number(select.value);
            populateDestinationBranches();

            await Promise.all([
                loadActiveMaterialsCatalog(),
                loadSuppliersCatalog(),
                refreshBranchData()
            ]);
        } catch (error) {
            select.innerHTML = '<option value="">Không tải được chi nhánh</option>';
            select.disabled = true;
            setKpiError("Lỗi kết nối máy chủ");
            showMessage(error.message || "Không thể tải danh sách chi nhánh.", "error");
        }
    }

    function populateDestinationBranches() {
        const destSelect = $("destinationBranchId");
        if (!destSelect) return;
        const others = state.allActiveBranches.filter(b => Number(b.branchId) !== Number(state.branchId));
        destSelect.innerHTML = '<option value="">-- Chọn chi nhánh đích nhận hàng --</option>' +
            others.map(b => `<option value="${escapeHtml(b.branchId)}">${escapeHtml(b.branchCode ? b.branchCode + " - " : "")}${escapeHtml(b.branchName)}</option>`).join("");
    }

    async function loadActiveMaterialsCatalog() {
        try {
            const list = await request("/api/materials/active");
            state.activeMaterials = Array.isArray(list) ? list : [];
        } catch {
            state.activeMaterials = [];
        }
    }

    async function loadSuppliersCatalog() {
        try {
            const data = await request("/api/suppliers?page=0&size=100&sortBy=supplierName&direction=asc");
            const allSuppliers = Array.isArray(data?.content) ? data.content : [];
            state.activeSuppliers = allSuppliers.filter(s => s.isActive !== false);

            const activeOptions = '<option value="">-- Chọn nhà cung cấp --</option>' +
                state.activeSuppliers.map(s => `<option value="${escapeHtml(s.supplierId)}">${escapeHtml(s.supplierCode)} - ${escapeHtml(s.supplierName)}</option>`).join("");

            const filterOptions = '<option value="">Tất cả nhà cung cấp</option>' +
                allSuppliers.map(s => `<option value="${escapeHtml(s.supplierId)}">${escapeHtml(s.supplierCode)} - ${escapeHtml(s.supplierName)}</option>`).join("");

            if ($("supplierId")) $("supplierId").innerHTML = activeOptions;
            if ($("paySupplierId")) $("paySupplierId").innerHTML = activeOptions;
            if ($("receiptFilterSupplier")) $("receiptFilterSupplier").innerHTML = filterOptions;
            if ($("paymentFilterSupplier")) $("paymentFilterSupplier").innerHTML = filterOptions;
        } catch {
            if ($("supplierId")) $("supplierId").innerHTML = '<option value="">Không tải được NCC</option>';
        }
    }

    async function loadAvailableBatchesForBranch() {
        if (!state.branchId) return;
        try {
            const list = await request(`/api/inventory/batches/available?branchId=${state.branchId}`);
            state.availableBatches = Array.isArray(list) ? list : [];
            refreshIssueAndTransferRows();
        } catch {
            state.availableBatches = [];
        }
    }

    async function refreshBranchData() {
        if (!state.branchId) return;
        await Promise.all([
            loadInventory(0),
            loadBatches(0),
            loadAvailableBatchesForBranch(),
            updateSummaryStats()
        ]);
        if (state.activeTab === "receipt") loadReceiptHistory(0);
        if (state.activeTab === "issue") loadIssueHistory(0);
        if (state.activeTab === "transfer") loadTransferHistory(0);
        if (state.activeTab === "materials") loadMaterialsTable(0);
        if (state.activeTab === "categories") loadCategoriesTable();
        if (state.activeTab === "supplier-payments") {
            loadSupplierDebts();
            loadPaymentHistory(0);
        }
    }

    // =========================================================================
    // 1. TAB TỒN KHO
    // =========================================================================

    // =========================================================================
    // CẤU HÌNH CỘT BẢNG TỒN KHO & COLUMN CUSTOMIZER (THEO CHUẨN CV-DCMS ENTERPRISE)
    // =========================================================================
    const INV_COLUMNS_KEY = "cvdcms_inv_table_columns_v2";
    const DEFAULT_INV_COLUMNS = [
        { id: "code", title: "Mã vật tư", width: 140, visible: true, required: true },
        { id: "name", title: "Tên vật tư", width: 260, visible: true, required: true },
        { id: "category", title: "Nhóm vật tư", width: 180, visible: true },
        { id: "unit", title: "Đơn vị", width: 100, visible: true },
        { id: "stock", title: "Tồn hiện tại", width: 130, visible: true },
        { id: "minThreshold", title: "Ngưỡng tối thiểu", width: 140, visible: true },
        { id: "updated", title: "Cập nhật gần nhất", width: 180, visible: true },
        { id: "status", title: "Trạng thái", width: 150, visible: true }
    ];

    let invColumns = loadInvColumns();

    function loadInvColumns() {
        try {
            const raw = localStorage.getItem(INV_COLUMNS_KEY);
            if (raw) {
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed) && parsed.length === DEFAULT_INV_COLUMNS.length) {
                    return parsed;
                }
            }
        } catch (e) {}
        return JSON.parse(JSON.stringify(DEFAULT_INV_COLUMNS));
    }

    function saveInvColumns() {
        try {
            localStorage.setItem(INV_COLUMNS_KEY, JSON.stringify(invColumns));
        } catch (e) {}
    }

    function renderInvTableHeader() {
        const theadTr = $("inventoryHeaderRow");
        if (!theadTr) return;
        theadTr.innerHTML = "";

        invColumns.forEach((col, idx) => {
            if (!col.visible) return;
            const th = document.createElement("th");
            th.style.width = col.width + "px";
            th.innerHTML = `
                <div class="d-flex align-items-center justify-content-between">
                    <span class="cell-ellipsis" title="${col.title}">${col.title}</span>
                </div>
                <span class="col-resizer" data-col-index="${idx}"></span>
            `;
            theadTr.appendChild(th);
        });

        setupInvColResizers();
    }

    function setupInvColResizers() {
        const resizers = document.querySelectorAll("#inventoryHeaderRow .col-resizer");
        resizers.forEach(resizer => {
            resizer.addEventListener("mousedown", function(e) {
                e.preventDefault();
                const colIdx = parseInt(this.getAttribute("data-col-index"), 10);
                const col = invColumns[colIdx];
                const startX = e.pageX;
                const startWidth = col.width;
                this.classList.add("is-resizing");

                function onMouseMove(moveEvent) {
                    const diff = moveEvent.pageX - startX;
                    let newWidth = startWidth + diff;
                    if (newWidth < 70) newWidth = 70;
                    if (newWidth > 400) newWidth = 400;
                    col.width = newWidth;
                    const th = document.querySelectorAll("#inventoryHeaderRow th")[colIdx];
                    if (th) th.style.width = newWidth + "px";
                }

                function onMouseUp() {
                    document.removeEventListener("mousemove", onMouseMove);
                    document.removeEventListener("mouseup", onMouseUp);
                    resizer.classList.remove("is-resizing");
                    saveInvColumns();
                    loadInventory(state.inventoryPage);
                }

                document.addEventListener("mousemove", onMouseMove);
                document.addEventListener("mouseup", onMouseUp);
            });
        });
    }

    window.openInvColumnDrawer = function() {
        renderInvDrawerColumns();
        if ($("invDrawerOverlay")) $("invDrawerOverlay").style.display = "block";
        if ($("invColumnDrawer")) $("invColumnDrawer").classList.add("show");
    };

    window.closeInvColumnDrawer = function() {
        if ($("invDrawerOverlay")) $("invDrawerOverlay").style.display = "none";
        if ($("invColumnDrawer")) $("invColumnDrawer").classList.remove("show");
    };

    window.filterInvDrawerColumns = function() {
        const kw = ($("drawerSearchInvCol")?.value || "").toLowerCase().trim();
        const items = document.querySelectorAll("#drawerInvColsList .drawer-item");
        items.forEach(it => {
            const txt = it.innerText.toLowerCase();
            it.style.display = txt.includes(kw) ? "flex" : "none";
        });
    };

    window.resetInvDefaultColumns = function() {
        invColumns = JSON.parse(JSON.stringify(DEFAULT_INV_COLUMNS));
        saveInvColumns();
        renderInvTableHeader();
        renderInvDrawerColumns();
        loadInventory(state.inventoryPage);
    };

    function renderInvDrawerColumns() {
        const list = $("drawerInvColsList");
        if (!list) return;
        list.innerHTML = "";

        invColumns.forEach((col, idx) => {
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
                saveInvColumns();
                renderInvTableHeader();
                loadInventory(state.inventoryPage);
            });

            item.addEventListener("dragstart", (e) => e.dataTransfer.setData("text/plain", idx));
            item.addEventListener("dragover", (e) => e.preventDefault());
            item.addEventListener("drop", (e) => {
                e.preventDefault();
                const fromIdx = parseInt(e.dataTransfer.getData("text/plain"), 10);
                const toIdx = idx;
                const moved = invColumns.splice(fromIdx, 1)[0];
                invColumns.splice(toIdx, 0, moved);
                saveInvColumns();
                renderInvTableHeader();
                renderInvDrawerColumns();
                loadInventory(state.inventoryPage);
            });

            list.appendChild(item);
        });
    }

    /**
     * Cập nhật thanh phân trang chuẩn MISA AMIS (tổng số bản ghi, khoảng hiển thị {@code from - to}, dropdown pageSize
     * và trạng thái bật/tắt 4 nút điều hướng Đầu / Trước / Sau / Cuối) cho từng bảng dữ liệu trong 8 tab.
     * @param {string} labelPrefix tiền tố ID nhãn (ví dụ {@code "Inventory"}, {@code "Batches"}, {@code "Receipts"})
     * @param {number} page chỉ số trang hiện tại (0-based)
     * @param {number} totalElements tổng số bản ghi khớp bộ lọc
     * @param {number} totalPages tổng số trang
     * @param {string|null} [idPrefix] tiền tố ID nút/select nếu khác {@code labelPrefix.toLowerCase()}
     */
    function updateMisaPagination(labelPrefix, page, totalElements, totalPages, idPrefix = null) {
        const idPfx = idPrefix || labelPrefix.toLowerCase();
        const fromItem = totalElements === 0 ? 0 : (page * state.pageSize) + 1;
        const toItem = Math.min((page + 1) * state.pageSize, totalElements);

        const rangeEl = $(`lbl${labelPrefix}Range`);
        if (rangeEl) rangeEl.textContent = totalElements === 0 ? "0 - 0" : `${fromItem} - ${toItem}`;

        const totalEl = $(`lbl${labelPrefix}Total`);
        if (totalEl) totalEl.textContent = totalElements;

        const sizeSelect = $(`${idPfx}PageSizeSelect`);
        if (sizeSelect) sizeSelect.value = state.pageSize;

        const isFirst = page <= 0 || totalElements === 0;
        const isLast = page >= totalPages - 1 || totalElements === 0;

        const firstBtn = $(`${idPfx}First`);
        if (firstBtn) {
            firstBtn.disabled = isFirst;
            firstBtn.classList.toggle("disabled", isFirst);
        }
        const prevBtn = $(`${idPfx}Prev`);
        if (prevBtn) {
            prevBtn.disabled = isFirst;
            prevBtn.classList.toggle("disabled", isFirst);
        }
        const nextBtn = $(`${idPfx}Next`);
        if (nextBtn) {
            nextBtn.disabled = isLast;
            nextBtn.classList.toggle("disabled", isLast);
        }
        const lastBtn = $(`${idPfx}Last`);
        if (lastBtn) {
            lastBtn.disabled = isLast;
            lastBtn.classList.toggle("disabled", isLast);
        }
    }

    window.onInvPageSizeChange = function(newSize) {
        state.pageSize = parseInt(newSize, 10) || 10;
        loadInventory(0);
    };
    window.onBatchesPageSizeChange = function(newSize) {
        state.pageSize = parseInt(newSize, 10) || 10;
        loadBatches(0);
    };
    window.onReceiptsPageSizeChange = function(newSize) {
        state.pageSize = parseInt(newSize, 10) || 10;
        loadReceiptHistory(0);
    };
    window.onIssuesPageSizeChange = function(newSize) {
        state.pageSize = parseInt(newSize, 10) || 10;
        loadIssueHistory(0);
    };
    window.onTransfersPageSizeChange = function(newSize) {
        state.pageSize = parseInt(newSize, 10) || 10;
        loadTransferHistory(0);
    };
    window.onMaterialsPageSizeChange = function(newSize) {
        state.pageSize = parseInt(newSize, 10) || 10;
        loadMaterialsTable(0);
    };
    window.onPaymentsPageSizeChange = function(newSize) {
        state.pageSize = parseInt(newSize, 10) || 10;
        loadPaymentHistory(0);
    };

    /**
     * Tải danh sách tồn kho tổng hợp phân trang tại chi nhánh hiện tại ({@code GET /api/inventory})
     * theo từ khóa tìm kiếm và checkbox lọc "Sắp hết / chạm ngưỡng", sau đó render các cột đang bật trong {@code invColumns}.
     * Sử dụng {@code AbortController} để hủy yêu cầu cũ khi người dùng gõ tìm kiếm liên tục.
     * @param {number} [page=state.inventoryPage] chỉ số trang cần tải (0-based)
     */
    async function loadInventory(page = state.inventoryPage) {
        if (!requireBranch()) return;
        state.inventoryPage = page;
        renderInvTableHeader();
        const visibleCols = invColumns.filter(c => c.visible);
        $("inventoryTable").innerHTML = `<tr><td colspan="${visibleCols.length}" class="empty">Đang tải dữ liệu...</td></tr>`;
        const params = new URLSearchParams({
            branchId: state.branchId,
            page,
            size: state.pageSize,
            sortBy: "lastUpdated",
            direction: "desc"
        });
        const keyword = $("keyword").value.trim();
        if (keyword) params.set("keyword", keyword);
        if ($("lowStock").checked) params.set("lowStock", "true");

        try {
            const data = await request(`/api/inventory?${params}`, { signal: getAbortSignal("inventory") });
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.inventoryTotalPages = Number(data?.totalPages || 0);
            const totalElements = Number(data?.totalElements || rows.length);

            $("inventoryTable").innerHTML = rows.length ? rows.map(item => {
                const current = Number(item.currentStock ?? 0);
                const threshold = Number(item.minStockThreshold ?? 0);
                const outOfStock = current <= 0;
                const low = current <= threshold;
                const badgeClass = outOfStock ? "status-danger" : (low ? "status-warning" : "status-good");
                const badgeLabel = outOfStock ? "Hết hàng (0)" : (low ? "Sắp hết" : "Đủ hàng");

                const cells = [];
                visibleCols.forEach(col => {
                    if (col.id === "code") {
                        cells.push(`<td style="width:${col.width}px;"><span class="font-mono-code">${escapeHtml(item.materialCode)}</span></td>`);
                    } else if (col.id === "name") {
                        cells.push(`<td style="width:${col.width}px;"><span class="cell-ellipsis" title="${escapeHtml(item.materialName)}"><strong>${escapeHtml(item.materialName)}</strong></span></td>`);
                    } else if (col.id === "category") {
                        cells.push(`<td style="width:${col.width}px;"><span class="cell-ellipsis" title="${escapeHtml(item.category)}">${escapeHtml(item.category)}</span></td>`);
                    } else if (col.id === "unit") {
                        cells.push(`<td style="width:${col.width}px;">${escapeHtml(item.unit)}</td>`);
                    } else if (col.id === "stock") {
                        cells.push(`<td style="width:${col.width}px;" class="${low ? "stock-low" : "stock-good"} fw-bold">${escapeHtml(fmtQty(item.currentStock))}</td>`);
                    } else if (col.id === "minThreshold") {
                        cells.push(`<td style="width:${col.width}px;">${escapeHtml(fmtQty(item.minStockThreshold))}</td>`);
                    } else if (col.id === "updated") {
                        cells.push(`<td style="width:${col.width}px;"><small class="text-secondary">${escapeHtml(formatDateTime(item.lastUpdated))}</small></td>`);
                    } else if (col.id === "status") {
                        cells.push(`<td style="width:${col.width}px;"><span class="status ${badgeClass}">${badgeLabel}</span></td>`);
                    }
                });
                return `<tr>${cells.join("")}</tr>`;
            }).join("") : `<tr><td colspan="${visibleCols.length}" class="empty">Không có dữ liệu tồn kho phù hợp.</td></tr>`;

            updateMisaPagination("Inventory", page, totalElements, state.inventoryTotalPages);
        } catch (error) {
            if (error.name === "AbortError") return;
            $("inventoryTable").innerHTML = `<tr><td colspan="${visibleCols.length}" class="empty">${escapeHtml(error.message)}</td></tr>`;
            showMessage(error.message, "error");
        }
    }
    // =========================================================================
    // 2. TAB LÔ & HẠN SỬ DỤNG
    // =========================================================================

    /**
     * Tải danh sách lô vật tư &amp; hạn sử dụng phân trang tại chi nhánh ({@code GET /api/inventory/batches}),
     * sắp xếp theo hạn sử dụng tăng dần (FEFO) và hỗ trợ lọc theo từ khóa cùng khoảng ngày {@code expiryFrom} – {@code expiryTo}.
     * Hiển thị nhãn cảnh báo: Đã hết tồn (0), Đã hết hạn, Sắp hết hạn (≤ 30 ngày) hoặc Còn hạn.
     * @param {number} [page=state.batchesPage] chỉ số trang cần tải (0-based)
     */
    async function loadBatches(page = state.batchesPage) {
        if (!requireBranch()) return;
        state.batchesPage = page;
        $("batchesTable").innerHTML = '<tr><td colspan="9" class="empty">Đang tải dữ liệu...</td></tr>';
        const params = new URLSearchParams({
            branchId: state.branchId,
            page,
            size: state.pageSize,
            sortBy: "expiryDate",
            direction: "asc"
        });
        const keyword = $("keyword").value.trim();
        if (keyword) params.set("keyword", keyword);
        if ($("expiryFrom").value) params.set("expiryFrom", $("expiryFrom").value);
        if ($("expiryTo").value) params.set("expiryTo", $("expiryTo").value);

        try {
            const data = await request(`/api/inventory/batches?${params}`, { signal: getAbortSignal("batches") });
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.batchesTotalPages = Number(data?.totalPages || 0);
            $("batchesTable").innerHTML = rows.length ? rows.map(item => {
                const warning = item.expiryWarning || "NONE";
                const outOfStock = Number(item.quantityRemaining || 0) <= 0;
                const label = outOfStock ? "Đã hết tồn (0)"
                    : warning === "EXPIRED" ? "Đã hết hạn"
                        : warning === "EXPIRING_SOON" ? `Sắp hết hạn (${item.daysToExpiry} ngày)` : "Còn hạn";
                const cls = outOfStock ? "status-info"
                    : warning === "EXPIRED" ? "status-danger"
                        : warning === "EXPIRING_SOON" ? "status-warning" : "status-good";
                return `<tr>
                    <td class="code">${escapeHtml(item.materialCode)}</td>
                    <td><strong>${escapeHtml(item.materialName)}</strong></td>
                    <td><span class="code">${escapeHtml(item.batchNumber)}</span></td>
                    <td>${escapeHtml(item.expiryDate)}</td>
                    <td class="${outOfStock ? "stock-low" : "stock-good"}">${escapeHtml(fmtQty(item.quantityRemaining))}</td>
                    <td>${escapeHtml(item.unit)}</td>
                    <td>${money(item.importPrice)}</td>
                    <td>${escapeHtml(item.supplierName || "—")}</td>
                    <td><span class="status ${cls}">${escapeHtml(label)}</span></td>
                </tr>`;
            }).join("") : '<tr><td colspan="9" class="empty">Không có lô vật tư phù hợp.</td></tr>';
            const totalBatches = Number(data?.totalElements || 0);
            updateMisaPagination("Batches", page, totalBatches, state.batchesTotalPages);
        } catch (error) {
            if (error.name === "AbortError") return;
            $("batchesTable").innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
            showMessage(error.message, "error");
        }
    }

    // =========================================================================
    // 3. TAB PHIẾU NHẬP KHO (UPLOAD HÓA ĐƠN, THANH TOÁN, LỊCH SỬ, PDF)
    // =========================================================================

    function reindexAndUpdateVoucherRows(formType) {
        const isReceipt = formType === "receipt";
        const isTransfer = formType === "transfer";
        const tbodyId = isReceipt ? "receiptItems" : (isTransfer ? "transferItems" : "issueItems");
        const rowSelector = isReceipt ? ".receipt-item-row" : (isTransfer ? ".transfer-item-row" : ".issue-item-row");
        const matInputSelector = isReceipt ? ".material-id" : (isTransfer ? ".transfer-material-id" : ".issue-material-id");
        const countElId = isReceipt ? "receiptSelectedCount" : (isTransfer ? "transferSelectedCount" : "issueSelectedCount");
        const colSpan = isReceipt ? 10 : 9;
        const emptyText = isReceipt
            ? 'Chưa có vật tư nào trong phiếu nhập. Nhấn nút <strong>"+ Chọn vật tư"</strong> ở góc phải phía trên để chọn vật tư từ danh mục.'
            : (isTransfer
                ? 'Chưa có vật tư nào trong phiếu điều chuyển. Nhấn nút <strong>"+ Chọn vật tư"</strong> để chọn vật tư cần điều chuyển.'
                : 'Chưa có vật tư nào trong phiếu xuất. Nhấn nút <strong>"+ Chọn vật tư"</strong> để chọn vật tư cần xuất kho.');

        const tbody = $(tbodyId);
        if (!tbody) return;

        const itemRows = Array.from(tbody.querySelectorAll(rowSelector));
        const emptyRow = tbody.querySelector(".voucher-empty-row");

        if (itemRows.length === 0) {
            if (!emptyRow) {
                tbody.innerHTML = `<tr class="voucher-empty-row"><td colspan="${colSpan}"><i class="bi bi-inbox me-1"></i>${emptyText}</td></tr>`;
            }
            const countEl = $(countElId);
            if (countEl) countEl.textContent = "0";
            return;
        }

        if (emptyRow) {
            emptyRow.remove();
        }

        const distinctMatIds = new Set();
        itemRows.forEach((tr, idx) => {
            const sttCell = tr.querySelector(".row-stt");
            if (sttCell) sttCell.textContent = String(idx + 1);
            const matIdVal = Number(tr.querySelector(matInputSelector)?.value || tr.dataset.materialId);
            if (matIdVal > 0) {
                distinctMatIds.add(matIdVal);
            }
        });

        const countEl = $(countElId);
        if (countEl) countEl.textContent = String(distinctMatIds.size);
    }

    async function handleInvoiceFileUpload(file) {
        if (!file) return;
        const maxBytes = 5 * 1024 * 1024;
        if (file.size > maxBytes) {
            showMessage("File hóa đơn vượt quá dung lượng tối đa 5MB. Vui lòng chọn file nhỏ hơn.", "warning");
            if ($("invoiceFileInput")) $("invoiceFileInput").value = "";
            return;
        }
        const ext = (file.name.split(".").pop() || "").toLowerCase();
        if (!["png", "jpg", "jpeg", "webp", "pdf"].includes(ext)) {
            showMessage("Định dạng file không hỗ trợ. Chỉ chấp nhận .png, .jpg, .jpeg, .webp, .pdf.", "warning");
            if ($("invoiceFileInput")) $("invoiceFileInput").value = "";
            return;
        }

        const formData = new FormData();
        formData.append("file", file);

        if ($("invoicePreviewCard")) $("invoicePreviewCard").hidden = false;
        if ($("invoicePreviewName")) $("invoicePreviewName").textContent = file.name;
        if ($("invoicePreviewStatus")) {
            $("invoicePreviewStatus").textContent = "Đang tải file hóa đơn lên hệ thống...";
            $("invoicePreviewStatus").style.color = "#0284c7";
        }

        try {
            const res = await request("/api/inventory/receipts/upload-invoice", {
                method: "POST",
                body: formData
            });
            const uploadedUrl = res?.attachmentUrl || "";
            $("attachmentUrl").value = uploadedUrl;
            if ($("invoicePreviewStatus")) {
                $("invoicePreviewStatus").textContent = "Đã tải lên hợp lệ";
                $("invoicePreviewStatus").style.color = "#15803d";
            }
            const isPdf = uploadedUrl.toLowerCase().endsWith(".pdf");
            if ($("invoicePreviewImg")) {
                if (!isPdf && uploadedUrl) {
                    $("invoicePreviewImg").src = uploadedUrl;
                    $("invoicePreviewImg").hidden = false;
                } else {
                    $("invoicePreviewImg").hidden = true;
                }
            }
            if ($("zoomUploadedInvoiceBtn")) {
                $("zoomUploadedInvoiceBtn").hidden = !uploadedUrl;
                $("zoomUploadedInvoiceBtn").textContent = isPdf ? "Xem PDF" : "Phóng to";
            }
        } catch (err) {
            $("attachmentUrl").value = "";
            if ($("invoiceFileInput")) $("invoiceFileInput").value = "";
            if ($("invoicePreviewCard")) $("invoicePreviewCard").hidden = true;
            showMessage(err.message || "Tải file hóa đơn thất bại.", "error");
        }
    }

    function clearUploadedInvoice() {
        if ($("invoiceFileInput")) $("invoiceFileInput").value = "";
        if ($("attachmentUrl")) $("attachmentUrl").value = "";
        if ($("invoicePreviewCard")) $("invoicePreviewCard").hidden = true;
        if ($("invoicePreviewImg")) {
            $("invoicePreviewImg").src = "";
            $("invoicePreviewImg").hidden = true;
        }
    }

    function addReceiptRow(materialObj, afterRow = null) {
        if (!materialObj || !materialObj.materialId) return null;
        const tbody = $("receiptItems");
        if (!tbody) return null;

        const emptyRow = tbody.querySelector(".voucher-empty-row");
        if (emptyRow) emptyRow.remove();

        const mid = Number(materialObj.materialId);
        const tr = document.createElement("tr");
        tr.className = "receipt-item-row";
        tr.dataset.materialId = String(mid);
        tr.innerHTML = `
            <td class="row-stt" style="text-align:center; font-weight:700; color:#64748b;">1</td>
            <td>
                <input type="hidden" class="material-id" value="${escapeHtml(mid)}">
                <span class="voucher-mat-code-badge">${escapeHtml(materialObj.materialCode || "—")}</span>
            </td>
            <td>
                <div class="voucher-mat-name">${escapeHtml(materialObj.materialName || "—")}</div>
                ${materialObj.category ? `<div class="voucher-mat-sub">${escapeHtml(materialObj.category)}</div>` : ""}
            </td>
            <td style="text-align:center;"><span class="voucher-mat-unit">${escapeHtml(materialObj.unit || "—")}</span></td>
            <td><input class="batch-number" maxlength="50" pattern="^[A-Za-z0-9._/-]{2,50}$" title="Số lô từ 2-50 ký tự (chữ, số, -, _, ., /)" required placeholder="VD: LO-2026-01" aria-label="Số lô"></td>
            <td><input class="expiry-date" type="date" required aria-label="Hạn sử dụng"></td>
            <td><input class="quantity" type="number" min="1" step="1" inputmode="numeric" required value="1" aria-label="Số lượng"></td>
            <td><input class="unit-price" type="number" min="0" step="1" inputmode="numeric" required value="0" aria-label="Đơn giá"></td>
            <td class="line-total fw-bold" style="text-align:right;">0 ₫</td>
            <td style="text-align:center;">
                <div class="voucher-row-actions">
                    <button class="add-batch-row-btn" type="button" title="Thêm lô nhập khác cho vật tư này"><i class="bi bi-layers-half"></i>+ Lô</button>
                    <button class="remove-row" type="button" title="Xóa dòng vật tư" aria-label="Xóa dòng">×</button>
                </div>
            </td>`;

        if (afterRow && afterRow.parentNode === tbody) {
            afterRow.insertAdjacentElement("afterend", tr);
        } else {
            tbody.appendChild(tr);
        }

        tr.querySelectorAll(".quantity, .unit-price").forEach(input => input.addEventListener("input", calculateReceiptTotal));

        tr.querySelector(".add-batch-row-btn").addEventListener("click", () => {
            const newBatchRow = addReceiptRow(materialObj, tr);
            if (newBatchRow) {
                const batchInput = newBatchRow.querySelector(".batch-number");
                if (batchInput) batchInput.focus();
            }
            showMessage(`Đã thêm dòng nhập lô mới cho vật tư "${materialObj.materialName}". Vui lòng nhập số lô khác.`, "info");
        });

        tr.querySelector(".remove-row").addEventListener("click", () => {
            tr.remove();
            reindexAndUpdateVoucherRows("receipt");
            calculateReceiptTotal();
        });

        reindexAndUpdateVoucherRows("receipt");
        calculateReceiptTotal();
        return tr;
    }

    function resetReceiptForm() {
        $("receiptForm").reset();
        $("receiptDate").value = new Date().toISOString().slice(0, 10);
        $("receiptItems").innerHTML = "";
        clearUploadedInvoice();
        if ($("receiptPaidAmount")) $("receiptPaidAmount").value = "0";
        if ($("receiptPaymentMethod")) $("receiptPaymentMethod").value = "BANK_TRANSFER";
        if ($("receiptPaymentRefNo")) $("receiptPaymentRefNo").value = "";
        if ($("receiptResult")) $("receiptResult").hidden = true;
        reindexAndUpdateVoucherRows("receipt");
        calculateReceiptTotal();
    }

    function computeCurrentReceiptTotalNumber() {
        let total = 0;
        document.querySelectorAll(".receipt-item-row").forEach(row => {
            const quantity = Number(row.querySelector(".quantity").value || 0);
            const price = Number(row.querySelector(".unit-price").value || 0);
            const line = quantity * price;
            total += line;
            row.querySelector(".line-total").textContent = money(line);
        });
        return total;
    }

    function calculateReceiptTotal() {
        const total = computeCurrentReceiptTotalNumber();
        const paid = Math.max(0, Number($("receiptPaidAmount")?.value || 0));
        const remaining = Math.max(0, total - paid);

        if ($("receiptTotal")) $("receiptTotal").textContent = money(total);
        if ($("receiptPaidDisplay")) $("receiptPaidDisplay").textContent = money(paid);
        if ($("receiptRemainingDebtDisplay")) {
            $("receiptRemainingDebtDisplay").textContent = money(remaining);
            $("receiptRemainingDebtDisplay").className = remaining > 0 ? "stock-low" : "stock-good";
        }
    }

    /**
     * Xử lý submit biểu mẫu lập Phiếu nhập kho ({@code #receiptForm} → {@code POST /api/inventory/receipts}).
     * Quy trình: kiểm tra số hóa đơn NCC, tự động tải lên tệp hóa đơn bắt buộc nếu chưa upload xong,
     * validate từng dòng vật tư (không trùng mã VT + số lô, regex số lô, HSD ≥ ngày nhập, SL nguyên &gt; 0, đơn giá ≥ 0),
     * kiểm tra số tiền thanh toán ngay ≤ tổng tiền nhập, gửi JSON lên server, hiển thị hộp kết quả kèm nút In A4 / Xuất PDF
     * và làm mới lại tồn kho + lịch sử phiếu nhập.
     * @param {SubmitEvent} event sự kiện submit của form {@code #receiptForm}
     */
    async function submitReceipt(event) {
        event.preventDefault();
        if (!requireBranch()) return;
        const button = $("submitReceipt");
        const result = $("receiptResult");
        const invoiceNo = $("supplierInvoiceNo").value.trim();

        if (invoiceNo && !REGEX.INVOICE_NO.test(invoiceNo)) {
            showMessage("Số hóa đơn NCC không hợp lệ (chỉ gồm chữ, số, dấu -, _, ., / và tối đa 50 ký tự).", "warning");
            $("supplierInvoiceNo").focus();
            return;
        }

        // Nếu người dùng đã chọn file trong input nhưng chưa kịp chờ upload xong
        const fileEl = $("invoiceFileInput");
        if (!$("attachmentUrl").value.trim() && fileEl?.files?.length) {
            await handleInvoiceFileUpload(fileEl.files[0]);
        }

        const attachmentUrl = $("attachmentUrl").value.trim();
        if (!attachmentUrl) {
            showMessage("Bắt buộc phải tải lên file ảnh hoặc chứng từ hóa đơn hợp lệ trước khi hoàn tất phiếu nhập kho.", "warning");
            if (fileEl) fileEl.focus();
            return;
        }

        const rows = [...document.querySelectorAll(".receipt-item-row")];
        if (!rows.length) {
            showMessage("Phiếu nhập kho chưa có vật tư nào. Vui lòng nhấn '+ Chọn vật tư' để thêm vật tư vào phiếu.", "warning");
            return;
        }
        const items = rows.map(row => ({
            materialId: Number(row.querySelector(".material-id").value),
            batchNumber: row.querySelector(".batch-number").value.trim().toUpperCase(),
            expiryDate: row.querySelector(".expiry-date").value,
            quantity: Number(row.querySelector(".quantity").value),
            unitPrice: Number(row.querySelector(".unit-price").value)
        }));

        if (items.some(i => !Number.isInteger(i.materialId) || i.materialId <= 0)) {
            showMessage("Vui lòng chọn vật tư cho tất cả các dòng nhập.", "warning");
            return;
        }

        // Kiểm tra trùng vật tư + cùng số lô trong cùng phiếu nhập
        const seenBatchKeys = new Set();
        for (const item of items) {
            const key = `${item.materialId}::${item.batchNumber}`;
            if (seenBatchKeys.has(key)) {
                showMessage(`Số lô ${item.batchNumber} bị lặp lại trên cùng một vật tư trong phiếu nhập. Vui lòng gộp số lượng vào 1 dòng.`, "warning");
                return;
            }
            seenBatchKeys.add(key);
        }

        const invalidBatch = items.find(i => !REGEX.BATCH_NUMBER.test(i.batchNumber));
        if (invalidBatch) {
            showMessage("Số lô phải từ 2-50 ký tự, chỉ gồm chữ, số và các ký tự -, _, ., / (không chứa khoảng trắng).", "warning");
            return;
        }
        if (items.some(i => !i.expiryDate || i.expiryDate < $("receiptDate").value)) {
            showMessage("Hạn sử dụng phải có và không được trước ngày nhập.", "warning");
            return;
        }
        if (items.some(i => !Number.isInteger(i.quantity) || i.quantity <= 0)) {
            showMessage("Số lượng nhập ở mỗi dòng phải là số nguyên lớn hơn 0.", "warning");
            return;
        }
        if (items.some(i => !Number.isFinite(i.unitPrice) || i.unitPrice < 0)) {
            showMessage("Đơn giá nhập không được âm.", "warning");
            return;
        }

        const totalAmount = computeCurrentReceiptTotalNumber();
        const paidAmount = Number($("receiptPaidAmount")?.value || 0);
        if (!Number.isFinite(paidAmount) || paidAmount < 0) {
            showMessage("Số tiền đã thanh toán không được nhỏ hơn 0.", "warning");
            $("receiptPaidAmount")?.focus();
            return;
        }
        if (paidAmount > totalAmount) {
            showMessage(`Số tiền đã thanh toán (${money(paidAmount)}) không được vượt quá tổng tiền phiếu nhập (${money(totalAmount)}).`, "warning");
            $("receiptPaidAmount")?.focus();
            return;
        }

        const paymentRefNo = $("receiptPaymentRefNo")?.value?.trim() || "";
        if (paymentRefNo && !REGEX.REFERENCE_NO.test(paymentRefNo)) {
            showMessage("Số chứng từ thanh toán chỉ gồm chữ, số, dấu ., _, /, - và tối đa 100 ký tự.", "warning");
            $("receiptPaymentRefNo")?.focus();
            return;
        }

        const payload = {
            branchId: Number(state.branchId),
            supplierId: Number($("supplierId").value),
            supplierInvoiceNo: invoiceNo || null,
            receiptDate: $("receiptDate").value,
            notes: $("receiptNotes").value.trim() || null,
            attachmentUrl,
            paidAmount,
            paymentMethod: $("receiptPaymentMethod")?.value || "BANK_TRANSFER",
            paymentReferenceNo: paymentRefNo || null,
            items
        };
        if (!payload.supplierId) {
            showMessage("Vui lòng chọn nhà cung cấp.", "warning");
            return;
        }

        button.disabled = true;
        button.textContent = "Đang lưu...";
        result.hidden = true;
        try {
            const saved = await request("/api/inventory/receipts", {
                method: "POST",
                body: JSON.stringify(payload)
            });
            resetReceiptForm();
            result.innerHTML = `
                <div style="display:flex; justify-content:space-between; align-items:center; flex-wrap:wrap; gap:10px;">
                    <div>
                        <strong>Nhập kho thành công</strong> — Mã phiếu: <strong>${escapeHtml(saved.receiptCode)}</strong>
                        · Tổng tiền: <strong>${money(saved.totalAmount)}</strong>
                        · Đã thanh toán: <strong class="stock-good">${money(saved.paidAmount)}</strong>
                        · Còn nợ: <strong class="stock-low">${money(saved.remainingDebt)}</strong>
                    </div>
                    <div class="row-actions">
                        <a href="/inventory/receipts/${saved.receiptId}/print" class="button button-secondary button-sm">Xem & in phiếu A4</a>
                        <a href="/api/inventory/receipts/${saved.receiptId}/pdf?download=true" class="button button-primary button-sm">Xuất PDF</a>
                    </div>
                </div>`;
            result.hidden = false;
            showMessage(`Đã tạo phiếu nhập ${saved.receiptCode}. Tồn kho, thanh toán và công nợ đã được cập nhật.`, "success");
            await Promise.all([refreshBranchData(), loadReceiptHistory(0)]);
        } catch (error) {
            showMessage(error.message || "Không thể tạo phiếu nhập.", "error");
        } finally {
            button.disabled = false;
            button.textContent = "Xác nhận & Lưu phiếu nhập";
        }
    }

    function renderInvoiceCell(attachmentUrl, receiptCode) {
        if (!attachmentUrl) {
            return '<span class="status status-warning">Chưa có</span>';
        }
        const isPdf = attachmentUrl.toLowerCase().endsWith(".pdf");
        if (isPdf) {
            return `<a href="${escapeHtml(attachmentUrl)}" target="_blank" rel="noopener" class="button button-secondary button-sm">Xem PDF</a>`;
        }
        return `
            <div style="display:flex; align-items:center; gap:6px;">
                <img src="${escapeHtml(attachmentUrl)}" alt="Hóa đơn ${escapeHtml(receiptCode)}"
                     class="invoice-thumb-small" data-zoom-image="${escapeHtml(attachmentUrl)}"
                     data-zoom-title="Hóa đơn phiếu nhập ${escapeHtml(receiptCode)}">
                <button type="button" class="button button-secondary button-sm"
                        data-zoom-image="${escapeHtml(attachmentUrl)}"
                        data-zoom-title="Hóa đơn phiếu nhập ${escapeHtml(receiptCode)}">Phóng to</button>
            </div>`;
    }

    /**
     * Tải và phân trang lịch sử phiếu nhập kho tại chi nhánh ({@code GET /api/inventory/receipts})
     * theo từ khóa, nhà cung cấp ({@code #receiptFilterSupplier}) và khoảng ngày nhập.
     * Render bảng kèm thumbnail/PDF hóa đơn, tổng tiền, đã trả, còn nợ và các nút Chi tiết / PDF / In A4.
     * @param {number} [page=state.receiptsPage] chỉ số trang cần tải (0-based)
     */
    async function loadReceiptHistory(page = state.receiptsPage) {
        if (!requireBranch()) return;
        state.receiptsPage = page;
        $("receiptHistoryTable").innerHTML = '<tr><td colspan="8" class="empty">Đang tải lịch sử phiếu nhập...</td></tr>';

        const params = new URLSearchParams({
            branchId: state.branchId,
            page,
            size: state.pageSize,
            sortBy: "receiptDate",
            direction: "desc"
        });
        const keyword = $("keyword").value.trim();
        if (keyword) params.set("keyword", keyword);
        if ($("receiptFilterSupplier").value) params.set("supplierId", $("receiptFilterSupplier").value);
        if ($("receiptFromDate").value) params.set("fromDate", $("receiptFromDate").value);
        if ($("receiptToDate").value) params.set("toDate", $("receiptToDate").value);

        try {
            const data = await request(`/api/inventory/receipts?${params}`, { signal: getAbortSignal("receipts") });
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.receiptsTotalPages = Number(data?.totalPages || 0);

            $("receiptHistoryTable").innerHTML = rows.length ? rows.map(r => {
                const debt = Number(r.remainingDebt || 0);
                return `<tr>
                    <td class="code">${escapeHtml(r.receiptCode)}</td>
                    <td>${escapeHtml(r.receiptDate)}</td>
                    <td>
                        <strong>${escapeHtml(r.supplierName || "—")}</strong>
                        ${r.supplierInvoiceNo ? `<div style="font-size:11.5px; color:#64748b;">HĐ: ${escapeHtml(r.supplierInvoiceNo)}</div>` : ""}
                    </td>
                    <td>${renderInvoiceCell(r.attachmentUrl, r.receiptCode)}</td>
                    <td><strong>${money(r.totalAmount)}</strong></td>
                    <td class="stock-good">${money(r.paidAmount)}</td>
                    <td class="${debt > 0 ? "stock-low" : "stock-good"}">${money(debt)}</td>
                    <td>
                        <div class="row-actions">
                            <button type="button" class="button button-secondary button-sm" data-view-receipt="${r.receiptId}">Chi tiết</button>
                            <a href="/api/inventory/receipts/${r.receiptId}/pdf?download=true" class="button button-secondary button-sm">PDF</a>
                            <button type="button" class="button button-secondary button-sm" data-print-url="/inventory/receipts/${r.receiptId}/print">In A4</button>
                        </div>
                    </td>
                </tr>`;
            }).join("") : '<tr><td colspan="8" class="empty">Chưa có phiếu nhập kho phù hợp.</td></tr>';

            const totalReceipts = Number(data?.totalElements || 0);
            updateMisaPagination("Receipts", page, totalReceipts, state.receiptsTotalPages, "receiptHistory");
        } catch (error) {
            if (error.name === "AbortError") return;
            $("receiptHistoryTable").innerHTML = `<tr><td colspan="8" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

    /**
     * Tải chi tiết một phiếu nhập kho ({@code GET /api/inventory/receipts/{receiptId}}) và hiển thị trong Modal chi tiết ({@code #detailModal})
     * gồm thông tin chung, ảnh/PDF hóa đơn đính kèm, bảng chi tiết vật tư/lô và các nút Tải PDF / Xem bản in A4.
     * @param {number|string} receiptId mã định danh phiếu nhập kho
     */
    async function viewReceiptDetail(receiptId) {
        try {
            const r = await request(`/api/inventory/receipts/${receiptId}`);
            $("detailModalTitle").textContent = `Chi tiết phiếu nhập kho: ${r.receiptCode}`;
            const itemsHtml = (r.items || []).map((item, idx) => `
                <tr>
                    <td>${idx + 1}</td>
                    <td class="code">${escapeHtml(item.materialCode)}</td>
                    <td><strong>${escapeHtml(item.materialName)}</strong></td>
                    <td>${escapeHtml(item.unit)}</td>
                    <td><span class="code">${escapeHtml(item.batchNumber)}</span></td>
                    <td>${escapeHtml(item.expiryDate)}</td>
                    <td>${escapeHtml(fmtQty(item.quantity))}</td>
                    <td>${money(item.unitPrice)}</td>
                    <td><strong>${money(item.lineTotal)}</strong></td>
                </tr>
            `).join("");

            const invoiceBlock = r.attachmentUrl
                ? (r.attachmentUrl.toLowerCase().endsWith(".pdf")
                    ? `<a href="${escapeHtml(r.attachmentUrl)}" target="_blank" rel="noopener" class="button button-secondary button-sm">Mở chứng từ PDF</a>`
                    : `<div style="display:flex; align-items:center; gap:12px;">
                           <img src="${escapeHtml(r.attachmentUrl)}" alt="Ảnh hóa đơn" class="detail-invoice-img"
                                data-zoom-image="${escapeHtml(r.attachmentUrl)}"
                                data-zoom-title="Hóa đơn phiếu nhập ${escapeHtml(r.receiptCode)}">
                           <button type="button" class="button button-secondary button-sm"
                                   data-zoom-image="${escapeHtml(r.attachmentUrl)}"
                                   data-zoom-title="Hóa đơn phiếu nhập ${escapeHtml(r.receiptCode)}">Phóng to ảnh hóa đơn</button>
                       </div>`)
                : `<span style="color:#94a3b8;">Không có ảnh hóa đơn</span>`;

            $("detailModalBody").innerHTML = `
                <div class="detail-meta-grid">
                    <div class="detail-meta-item"><span>Mã phiếu nhập</span><strong>${escapeHtml(r.receiptCode)}</strong></div>
                    <div class="detail-meta-item"><span>Ngày nhập</span><strong>${escapeHtml(r.receiptDate)}</strong></div>
                    <div class="detail-meta-item"><span>Kho chi nhánh</span><strong>${escapeHtml(r.branchName)}</strong></div>
                    <div class="detail-meta-item"><span>Nhà cung cấp</span><strong>${escapeHtml(r.supplierCode)} - ${escapeHtml(r.supplierName)}</strong></div>
                    <div class="detail-meta-item"><span>Số hóa đơn NCC</span><strong>${escapeHtml(r.supplierInvoiceNo || "—")}</strong></div>
                    <div class="detail-meta-item"><span>Người lập phiếu</span><strong>${escapeHtml(r.receiverName || "—")}</strong></div>
                    <div class="detail-meta-item"><span>Tổng tiền phiếu</span><strong>${money(r.totalAmount)}</strong></div>
                    <div class="detail-meta-item"><span>Đã thanh toán</span><strong class="stock-good">${money(r.paidAmount)}</strong></div>
                    <div class="detail-meta-item"><span>Công nợ còn lại</span><strong class="${Number(r.remainingDebt || 0) > 0 ? "stock-low" : "stock-good"}">${money(r.remainingDebt)}</strong></div>
                </div>
                ${r.notes ? `<p style="margin-bottom:10px;"><strong>Ghi chú:</strong> ${escapeHtml(r.notes)}</p>` : ""}
                <div class="detail-attachment-card" style="margin: 12px 0;">
                    <h4>Ảnh / Chứng từ hóa đơn</h4>
                    ${invoiceBlock}
                </div>
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr><th>#</th><th>Mã VT</th><th>Tên vật tư</th><th>ĐVT</th><th>Số lô</th><th>Hạn dùng</th><th>SL</th><th>Đơn giá</th><th>Thành tiền</th></tr>
                        </thead>
                        <tbody>${itemsHtml || '<tr><td colspan="9" class="empty">Không có dòng chi tiết</td></tr>'}</tbody>
                    </table>
                </div>
            `;

            if ($("detailModalActions")) {
                $("detailModalActions").innerHTML = `
                    <a href="/api/inventory/receipts/${r.receiptId}/pdf?download=true" class="button button-primary">Tải PDF phiếu nhập</a>
                    <a href="/inventory/receipts/${r.receiptId}/print" class="button button-secondary">Xem bản in A4</a>
                    <button type="button" class="button button-secondary" id="closeDetailModalFooterBtn">Đóng</button>
                `;
                $("closeDetailModalFooterBtn")?.addEventListener("click", () => { $("detailModal").hidden = true; });
            }

            $("detailModal").hidden = false;
        } catch (error) {
            showMessage(error.message, "error");
        }
    }

    // =========================================================================
    // 4. TAB PHIẾU XUẤT KHO & 5. TAB ĐIỀU CHUYỂN CHI NHÁNH
    // =========================================================================

    function buildBatchOptionsForMaterial(materialId, allowExpired = false) {
        if (!materialId) {
            return '<option value="">-- Tự động theo FEFO (Hạn gần nhất trước) --</option>';
        }
        const batches = state.availableBatches.filter(b => {
            if (Number(b.materialId) !== Number(materialId)) return false;
            if (Number(b.quantityRemaining || 0) <= 0) return false;
            if (!allowExpired && b.expiryWarning === "EXPIRED") return false;
            return true;
        });

        return '<option value="">-- Tự động theo FEFO (Hạn gần nhất trước) --</option>' +
            batches.map(b => `<option value="${escapeHtml(b.batchId)}" data-avail="${escapeHtml(b.quantityRemaining)}">Lô ${escapeHtml(b.batchNumber)} · HSD: ${escapeHtml(b.expiryDate)} · Tồn: ${escapeHtml(fmtQty(b.quantityRemaining))} ${escapeHtml(b.unit || "")}</option>`).join("");
    }

    function computeAvailableForMaterial(materialId, batchId, allowExpired = false) {
        if (!materialId) return 0;
        if (batchId) {
            const found = state.availableBatches.find(b => Number(b.batchId) === Number(batchId));
            return found ? Number(found.quantityRemaining || 0) : 0;
        }
        return state.availableBatches
            .filter(b => Number(b.materialId) === Number(materialId) && (allowExpired || b.expiryWarning !== "EXPIRED"))
            .reduce((sum, b) => sum + Number(b.quantityRemaining || 0), 0);
    }

    function addIssueOrTransferRow(containerId, isTransfer = false, materialObj = null) {
        if (!materialObj || !materialObj.materialId) return null;
        const tbody = $(containerId);
        if (!tbody) return null;

        const emptyRow = tbody.querySelector(".voucher-empty-row");
        if (emptyRow) emptyRow.remove();

        const mid = Number(materialObj.materialId);
        const tr = document.createElement("tr");
        tr.className = isTransfer ? "transfer-item-row" : "issue-item-row";
        tr.dataset.materialId = String(mid);
        const matClass = isTransfer ? "transfer-material-id" : "issue-material-id";

        tr.innerHTML = `
            <td class="row-stt" style="text-align:center; font-weight:700; color:#64748b;">1</td>
            <td>
                <input type="hidden" class="${matClass}" value="${escapeHtml(mid)}">
                <span class="voucher-mat-code-badge">${escapeHtml(materialObj.materialCode || "—")}</span>
            </td>
            <td>
                <div class="voucher-mat-name">${escapeHtml(materialObj.materialName || "—")}</div>
                ${materialObj.category ? `<div class="voucher-mat-sub">${escapeHtml(materialObj.category)}</div>` : ""}
            </td>
            <td style="text-align:center;"><span class="voucher-mat-unit">${escapeHtml(materialObj.unit || "—")}</span></td>
            <td>
                <select class="batch-id" aria-label="Chọn lô xuất">
                    <option value="">-- Tự động theo FEFO (Hạn gần nhất trước) --</option>
                </select>
            </td>
            <td class="avail-stock" style="text-align:right;">0</td>
            <td><input class="quantity" type="number" min="1" step="1" inputmode="numeric" required value="1" aria-label="Số lượng"></td>
            <td><input class="item-notes" maxlength="255" placeholder="Ghi chú dòng (tùy chọn)"></td>
            <td style="text-align:center;"><button class="remove-row" type="button" title="Xóa dòng">×</button></td>
        `;
        tbody.appendChild(tr);

        const batchSelect = tr.querySelector(".batch-id");
        const availCell = tr.querySelector(".avail-stock");

        const updateRowAvailability = () => {
            const allowExpired = !isTransfer && $("issueType")?.value === "DISCARD_EXPIRED";
            const currentBatch = batchSelect.value;
            batchSelect.innerHTML = buildBatchOptionsForMaterial(mid, allowExpired);
            if (currentBatch && [...batchSelect.options].some(o => o.value === currentBatch)) {
                batchSelect.value = currentBatch;
            }
            const avail = computeAvailableForMaterial(mid, batchSelect.value, allowExpired);
            availCell.textContent = fmtQty(avail);
            availCell.className = `avail-stock ${avail > 0 ? "stock-good" : "stock-low"}`;
        };

        tr._updateRowAvailability = updateRowAvailability;
        batchSelect.addEventListener("change", updateRowAvailability);
        updateRowAvailability();

        tr.querySelector(".remove-row").addEventListener("click", () => {
            tr.remove();
            reindexAndUpdateVoucherRows(isTransfer ? "transfer" : "issue");
        });

        reindexAndUpdateVoucherRows(isTransfer ? "transfer" : "issue");
        return tr;
    }

    function resetIssueForm() {
        $("issueForm").reset();
        $("issueDate").value = new Date().toISOString().slice(0, 10);
        $("issueItems").innerHTML = "";
        syncIssueReasonVisibility();
        reindexAndUpdateVoucherRows("issue");
    }

    function resetTransferForm() {
        $("transferForm").reset();
        $("transferDate").value = new Date().toISOString().slice(0, 10);
        populateDestinationBranches();
        $("transferItems").innerHTML = "";
        reindexAndUpdateVoucherRows("transfer");
    }

    function syncIssueReasonVisibility() {
        const typeEl = $("issueType");
        const wrapEl = $("issueReasonFieldWrap");
        const inputEl = $("issueReason");
        const errEl = $("issueReasonError");
        if (!typeEl || !wrapEl || !inputEl) return;
        const isOther = typeEl.value === "OTHER";
        wrapEl.hidden = !isOther;
        inputEl.required = isOther;
        if (!isOther) {
            inputEl.value = "";
            if (errEl) errEl.hidden = true;
        }
    }

    function refreshIssueAndTransferRows() {
        syncIssueReasonVisibility();
        document.querySelectorAll(".issue-item-row, .transfer-item-row").forEach(tr => {
            if (typeof tr._updateRowAvailability === "function") {
                tr._updateRowAvailability();
            }
        });
    }

    /**
     * Xử lý submit biểu mẫu lập Phiếu xuất kho ({@code #issueForm} → {@code POST /api/inventory/issues}).
     * Quy trình: kiểm tra danh sách dòng vật tư, số lượng nguyên &gt; 0, đối chiếu tổng SL yêu cầu với tồn khả dụng
     * (theo từng lô đã chọn và tổng theo vật tư, chỉ cho phép lô hết hạn khi {@code issueType === "DISCARD_EXPIRED"}),
     * bắt buộc lý do khi chọn {@code OTHER}, gửi request tạo phiếu và làm mới tồn kho + lịch sử xuất.
     * @param {SubmitEvent} event sự kiện submit của form {@code #issueForm}
     */
    async function submitIssue(event) {
        event.preventDefault();
        if (!requireBranch()) return;
        const button = $("submitIssue");
        const rows = [...document.querySelectorAll(".issue-item-row")];
        if (!rows.length) {
            showMessage("Phiếu xuất kho chưa có vật tư nào. Vui lòng nhấn '+ Chọn vật tư' để thêm vật tư cần xuất.", "warning");
            return;
        }
        const items = rows.map(row => ({
            materialId: Number(row.querySelector(".issue-material-id").value),
            batchId: row.querySelector(".batch-id").value ? Number(row.querySelector(".batch-id").value) : null,
            quantity: Number(row.querySelector(".quantity").value),
            notes: row.querySelector(".item-notes").value.trim() || null
        }));

        if (items.some(i => !i.materialId || i.materialId <= 0)) {
            showMessage("Vui lòng chọn vật tư cần xuất ở tất cả các dòng.", "warning");
            return;
        }
        if (items.some(i => !Number.isInteger(i.quantity) || i.quantity <= 0)) {
            showMessage("Số lượng xuất ở mỗi dòng phải là số nguyên lớn hơn 0.", "warning");
            return;
        }

        // Kiểm tra tổng số lượng xuất theo vật tư không vượt tồn khả dụng
        const allowExpired = $("issueType").value === "DISCARD_EXPIRED";
        const requestedByMat = new Map();
        for (const item of items) {
            requestedByMat.set(item.materialId, (requestedByMat.get(item.materialId) || 0) + item.quantity);
            if (item.batchId) {
                const batchAvail = computeAvailableForMaterial(item.materialId, item.batchId, allowExpired);
                if (item.quantity > batchAvail) {
                    showMessage(`Số lượng xuất (${item.quantity}) vượt quá tồn khả dụng của lô đã chọn (${batchAvail}).`, "warning");
                    return;
                }
            }
        }
        for (const [matId, totalReq] of requestedByMat.entries()) {
            const totalAvail = computeAvailableForMaterial(matId, null, allowExpired);
            if (totalReq > totalAvail) {
                const matObj = state.activeMaterials.find(m => Number(m.materialId) === Number(matId));
                showMessage(`Vật tư ${matObj ? matObj.materialName : matId} không đủ tồn khả dụng (Yêu cầu: ${totalReq}, Khả dụng: ${totalAvail}).`, "warning");
                return;
            }
        }

        const selectedIssueType = $("issueType").value;
        const rawIssueReason = $("issueReason") ? $("issueReason").value.trim() : "";
        if (selectedIssueType === "OTHER") {
            if (!rawIssueReason) {
                if ($("issueReasonError")) $("issueReasonError").hidden = false;
                showMessage("Vui lòng nhập lý do xuất kho khi chọn Mục đích khác.", "warning");
                $("issueReason")?.focus();
                return;
            }
            if ($("issueReasonError")) $("issueReasonError").hidden = true;
        }
        const payload = {
            branchId: Number(state.branchId),
            issueType: selectedIssueType,
            issueReason: selectedIssueType === "OTHER" ? rawIssueReason : null,
            issueDate: $("issueDate").value,
            notes: $("issueNotes").value.trim() || null,
            items
        };

        button.disabled = true;
        button.textContent = "Đang xử lý...";
        try {
            const saved = await request("/api/inventory/issues", {
                method: "POST",
                body: JSON.stringify(payload)
            });
            showMessage(`Đã lập phiếu xuất ${saved.issueCode} thành công! Tồn kho đã được trừ.`, "success");
            resetIssueForm();
            await Promise.all([refreshBranchData(), loadIssueHistory(0)]);
        } catch (error) {
            showMessage(error.message || "Không thể tạo phiếu xuất kho.", "error");
        } finally {
            button.disabled = false;
            button.textContent = "Xác nhận xuất kho";
        }
    }

    /**
     * Xử lý submit biểu mẫu lập Phiếu điều chuyển vật tư liên chi nhánh ({@code #transferForm} → {@code POST /api/inventory/transfers}).
     * Kiểm tra chi nhánh đích khác chi nhánh nguồn, danh sách dòng vật tư và số lượng nguyên &gt; 0,
     * sau đó gửi payload với {@code issueType: "TRANSFER"} và làm mới tồn kho + lịch sử điều chuyển.
     * @param {SubmitEvent} event sự kiện submit của form {@code #transferForm}
     */
    async function submitTransfer(event) {
        event.preventDefault();
        if (!requireBranch()) return;
        const destBranchId = Number($("destinationBranchId").value);
        if (!destBranchId) {
            showMessage("Vui lòng chọn chi nhánh đích nhận hàng.", "warning");
            return;
        }
        if (destBranchId === Number(state.branchId)) {
            showMessage("Chi nhánh đích nhận hàng phải khác chi nhánh xuất hiện tại.", "warning");
            return;
        }
        const button = $("submitTransfer");
        const rows = [...document.querySelectorAll(".transfer-item-row")];
        if (!rows.length) {
            showMessage("Phiếu điều chuyển chưa có vật tư nào. Vui lòng nhấn '+ Chọn vật tư' để thêm vật tư cần điều chuyển.", "warning");
            return;
        }
        const items = rows.map(row => ({
            materialId: Number(row.querySelector(".transfer-material-id").value),
            batchId: row.querySelector(".batch-id").value ? Number(row.querySelector(".batch-id").value) : null,
            quantity: Number(row.querySelector(".quantity").value),
            notes: row.querySelector(".item-notes").value.trim() || null
        }));

        if (items.some(i => !i.materialId || i.materialId <= 0)) {
            showMessage("Vui lòng chọn vật tư điều chuyển ở tất cả các dòng.", "warning");
            return;
        }
        if (items.some(i => !Number.isInteger(i.quantity) || i.quantity <= 0)) {
            showMessage("Số lượng điều chuyển ở mỗi dòng phải là số nguyên lớn hơn 0.", "warning");
            return;
        }

        const payload = {
            branchId: Number(state.branchId),
            issueType: "TRANSFER",
            destinationBranchId: destBranchId,
            issueDate: $("transferDate").value,
            notes: $("transferNotes").value.trim() || null,
            items
        };

        button.disabled = true;
        button.textContent = "Đang điều chuyển...";
        try {
            const saved = await request("/api/inventory/transfers", {
                method: "POST",
                body: JSON.stringify(payload)
            });
            showMessage(`Đã điều chuyển thành công (Mã phiếu: ${saved.issueCode}) sang ${saved.destinationBranchName}!`, "success");
            resetTransferForm();
            await Promise.all([refreshBranchData(), loadTransferHistory(0)]);
        } catch (error) {
            showMessage(error.message || "Không thể điều chuyển vật tư.", "error");
        } finally {
            button.disabled = false;
            button.textContent = "Xác nhận điều chuyển";
        }
    }

    /**
     * Tải và phân trang lịch sử phiếu xuất kho ({@code GET /api/inventory/issues?excludeTransfer=true})
     * theo từ khóa, loại xuất kho ({@code #issueFilterType}) và khoảng ngày.
     * Render bảng kèm badge phân loại (Nội bộ, Đơn thuốc, Hoàn kho đơn thuốc, Hủy hết hạn, Mục đích khác) và nút Chi tiết / PDF / In A4.
     * @param {number} [page=state.issuesPage] chỉ số trang cần tải (0-based)
     */
    async function loadIssueHistory(page = state.issuesPage) {
        if (!requireBranch()) return;
        state.issuesPage = page;
        $("issueHistoryTable").innerHTML = '<tr><td colspan="8" class="empty">Đang tải lịch sử phiếu xuất...</td></tr>';

        const params = new URLSearchParams({
            branchId: state.branchId,
            excludeTransfer: "true",
            page,
            size: state.pageSize,
            sortBy: "issueDate",
            direction: "desc"
        });
        const keyword = $("keyword").value.trim();
        if (keyword) params.set("keyword", keyword);
        if ($("issueFilterType").value) params.set("issueType", $("issueFilterType").value);
        if ($("issueFromDate").value) params.set("fromDate", $("issueFromDate").value);
        if ($("issueToDate").value) params.set("toDate", $("issueToDate").value);

        try {
            const data = await request(`/api/inventory/issues?${params}`, { signal: getAbortSignal("issues") });
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.issuesTotalPages = Number(data?.totalPages || 0);

            $("issueHistoryTable").innerHTML = rows.length ? rows.map(si => {
                const isDiscard = si.issueType === "DISCARD_EXPIRED";
                const isOther = si.issueType === "OTHER";
                const isPrescOut = si.issueType === "PRESCRIPTION_OUT";
                const isPrescReturn = si.issueType === "PRESCRIPTION_RETURN";
                const typeLabel = isDiscard ? "Xuất hủy hết hạn"
                    : (isPrescOut ? "Xuất theo đơn thuốc"
                    : (isPrescReturn ? "Hoàn kho đơn thuốc"
                    : (isOther ? "Mục đích khác" : "Xuất sử dụng nội bộ")));
                const typeCls = isDiscard ? "status-danger"
                    : (isPrescOut ? "status-primary"
                    : (isPrescReturn ? "status-success"
                    : (isOther ? "status-warning" : "status-info")));
                const prescTag = si.prescriptionCode ? `<div class="small text-primary fw-semibold">Đơn thuốc: ${escapeHtml(si.prescriptionCode)}</div>` : "";
                const noteOrReasonHtml = ((isOther || isPrescOut || isPrescReturn) && si.issueReason)
                    ? `${prescTag}<div><strong>Lý do:</strong> ${escapeHtml(si.issueReason)}</div>${si.notes ? `<div class="text-muted small">${escapeHtml(si.notes)}</div>` : ""}`
                    : `${prescTag}${escapeHtml(si.notes || "—")}`;
                return `<tr>
                    <td class="code">${escapeHtml(si.issueCode)}</td>
                    <td>${escapeHtml(si.issueDate)}</td>
                    <td><span class="status ${typeCls}">${escapeHtml(typeLabel)}</span></td>
                    <td>${escapeHtml(si.issuerName || "—")}</td>
                    <td><strong>${escapeHtml(fmtQty(si.totalQuantity))}</strong> (${escapeHtml(si.itemCount)} dòng)</td>
                    <td>${money(si.totalEstimatedValue)}</td>
                    <td>${noteOrReasonHtml}</td>
                    <td>
                        <div class="row-actions">
                            <button type="button" class="button button-secondary button-sm" data-view-issue="${si.issueId}">Chi tiết</button>
                            <a href="/api/inventory/issues/${si.issueId}/pdf?download=true" class="button button-secondary button-sm">PDF</a>
                            <button type="button" class="button button-secondary button-sm" data-print-url="/inventory/issues/${si.issueId}/print">In A4</button>
                        </div>
                    </td>
                </tr>`;
            }).join("") : '<tr><td colspan="8" class="empty">Chưa có phiếu xuất kho phù hợp.</td></tr>';

            const totalIssues = Number(data?.totalElements || 0);
            updateMisaPagination("Issues", page, totalIssues, state.issuesTotalPages, "issueHistory");
        } catch (error) {
            if (error.name === "AbortError") return;
            $("issueHistoryTable").innerHTML = `<tr><td colspan="8" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

    /**
     * Tải và phân trang lịch sử giao dịch điều chuyển kho ({@code GET /api/inventory/transfers})
     * liên quan đến chi nhánh hiện tại (hiển thị hướng "Chuyển đi (-)" hoặc "Nhận về (+)") kèm nút Chi tiết / PDF / In A4.
     * @param {number} [page=state.transfersPage] chỉ số trang cần tải (0-based)
     */
    async function loadTransferHistory(page = state.transfersPage) {
        if (!requireBranch()) return;
        state.transfersPage = page;
        $("transferHistoryTable").innerHTML = '<tr><td colspan="9" class="empty">Đang tải lịch sử điều chuyển...</td></tr>';

        const params = new URLSearchParams({
            branchId: state.branchId,
            page,
            size: state.pageSize,
            sortBy: "issueDate",
            direction: "desc"
        });
        const keyword = $("keyword").value.trim();
        if (keyword) params.set("keyword", keyword);
        if ($("transferFromDate").value) params.set("fromDate", $("transferFromDate").value);
        if ($("transferToDate").value) params.set("toDate", $("transferToDate").value);

        try {
            const data = await request(`/api/inventory/transfers?${params}`, { signal: getAbortSignal("transfers") });
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.transfersTotalPages = Number(data?.totalPages || 0);

            $("transferHistoryTable").innerHTML = rows.length ? rows.map(si => {
                const isOutbound = Number(si.branchId) === Number(state.branchId);
                const dirLabel = isOutbound ? "Chuyển đi (-)" : "Nhận về (+)";
                const dirCls = isOutbound ? "status-warning" : "status-good";
                return `<tr>
                    <td class="code">${escapeHtml(si.issueCode)}</td>
                    <td>${escapeHtml(si.issueDate)}</td>
                    <td><strong>${escapeHtml(si.branchName)}</strong></td>
                    <td><strong>${escapeHtml(si.destinationBranchName || "—")}</strong></td>
                    <td><span class="status ${dirCls}">${escapeHtml(dirLabel)}</span></td>
                    <td>${escapeHtml(si.issuerName || "—")}</td>
                    <td><strong>${escapeHtml(fmtQty(si.totalQuantity))}</strong></td>
                    <td>${money(si.totalEstimatedValue)}</td>
                    <td>
                        <div class="row-actions">
                            <button type="button" class="button button-secondary button-sm" data-view-issue="${si.issueId}">Chi tiết</button>
                            <a href="/api/inventory/transfers/${si.issueId}/pdf?download=true" class="button button-secondary button-sm">PDF</a>
                            <button type="button" class="button button-secondary button-sm" data-print-url="/inventory/transfers/${si.issueId}/print">In A4</button>
                        </div>
                    </td>
                </tr>`;
            }).join("") : '<tr><td colspan="9" class="empty">Chưa có giao dịch điều chuyển kho.</td></tr>';

            const totalTransfers = Number(data?.totalElements || 0);
            updateMisaPagination("Transfers", page, totalTransfers, state.transfersTotalPages, "transferHistory");
        } catch (error) {
            if (error.name === "AbortError") return;
            $("transferHistoryTable").innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

    /**
     * Tải chi tiết một phiếu xuất kho hoặc phiếu điều chuyển ({@code GET /api/inventory/issues/{issueId}})
     * và hiển thị trong Modal chi tiết ({@code #detailModal}) cùng bảng lô đã xuất và liên kết tải PDF / xem bản in A4.
     * @param {number|string} issueId mã định danh phiếu xuất / điều chuyển
     */
    async function viewIssueDetail(issueId) {
        try {
            const si = await request(`/api/inventory/issues/${issueId}`);
            const isTransfer = si.issueType === "TRANSFER";
            $("detailModalTitle").textContent = isTransfer
                ? `Chi tiết phiếu điều chuyển kho: ${si.issueCode}`
                : `Chi tiết phiếu xuất kho: ${si.issueCode}`;

            const itemsHtml = (si.items || []).map((item, idx) => `
                <tr>
                    <td>${idx + 1}</td>
                    <td class="code">${escapeHtml(item.materialCode)}</td>
                    <td><strong>${escapeHtml(item.materialName)}</strong></td>
                    <td>${escapeHtml(item.unit)}</td>
                    <td><span class="code">${escapeHtml(item.batchNumber || "—")}</span></td>
                    <td>${escapeHtml(item.expiryDate || "—")}</td>
                    <td><strong>${escapeHtml(fmtQty(item.quantity))}</strong></td>
                    <td>${money(item.importPrice)}</td>
                    <td>${money(item.lineValue)}</td>
                    <td>${escapeHtml(item.notes || "—")}</td>
                </tr>
            `).join("");

            $("detailModalBody").innerHTML = `
                <div class="detail-meta-grid">
                    <div class="detail-meta-item"><span>Mã phiếu</span><strong>${escapeHtml(si.issueCode)}</strong></div>
                    <div class="detail-meta-item"><span>Ngày lập</span><strong>${escapeHtml(si.issueDate)}</strong></div>
                    <div class="detail-meta-item"><span>Loại giao dịch</span><strong>${escapeHtml(si.issueType === "TRANSFER" ? "Điều chuyển liên chi nhánh" : (si.issueType === "DISCARD_EXPIRED" ? "Xuất hủy vật tư hết hạn" : (si.issueType === "PRESCRIPTION_OUT" ? "Xuất theo đơn thuốc (FEFO)" : (si.issueType === "PRESCRIPTION_RETURN" ? "Hoàn kho do sửa/hủy đơn thuốc" : (si.issueType === "OTHER" ? "Mục đích khác" : "Xuất sử dụng nội bộ")))))}</strong></div>
                    ${si.prescriptionCode ? `<div class="detail-meta-item"><span>Mã đơn thuốc</span><strong>${escapeHtml(si.prescriptionCode)}</strong></div>` : ""}
                    ${si.issueReason ? `<div class="detail-meta-item"><span>Lý do xuất kho</span><strong>${escapeHtml(si.issueReason)}</strong></div>` : ""}
                    <div class="detail-meta-item"><span>Chi nhánh xuất</span><strong>${escapeHtml(si.branchName)}</strong></div>
                    ${isTransfer ? `<div class="detail-meta-item"><span>Chi nhánh nhận</span><strong>${escapeHtml(si.destinationBranchName || "—")}</strong></div>` : ""}
                    <div class="detail-meta-item"><span>Người lập phiếu</span><strong>${escapeHtml(si.issuerName || "—")}</strong></div>
                    <div class="detail-meta-item"><span>Tổng SL xuất</span><strong>${escapeHtml(fmtQty(si.totalQuantity))}</strong></div>
                    <div class="detail-meta-item"><span>Tổng giá trị vốn</span><strong>${money(si.totalEstimatedValue)}</strong></div>
                </div>
                ${si.notes ? `<p style="margin-bottom:10px;"><strong>Ghi chú:</strong> ${escapeHtml(si.notes)}</p>` : ""}
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr><th>#</th><th>Mã VT</th><th>Tên vật tư</th><th>ĐVT</th><th>Số lô xuất</th><th>Hạn dùng</th><th>SL xuất</th><th>Giá vốn</th><th>Thành tiền vốn</th><th>Ghi chú</th></tr>
                        </thead>
                        <tbody>${itemsHtml || '<tr><td colspan="10" class="empty">Không có dòng chi tiết</td></tr>'}</tbody>
                    </table>
                </div>
            `;

            if ($("detailModalActions")) {
                const printUrl = isTransfer ? `/inventory/transfers/${si.issueId}/print` : `/inventory/issues/${si.issueId}/print`;
                const pdfUrl = isTransfer ? `/api/inventory/transfers/${si.issueId}/pdf?download=true` : `/api/inventory/issues/${si.issueId}/pdf?download=true`;
                const pdfLabel = isTransfer ? "Tải PDF điều chuyển" : "Tải PDF phiếu xuất";
                $("detailModalActions").innerHTML = `
                    <a href="${pdfUrl}" class="button button-primary">${pdfLabel}</a>
                    <a href="${printUrl}" class="button button-secondary">Xem bản in A4</a>
                    <button type="button" class="button button-secondary" id="closeDetailModalFooterBtn">Đóng</button>
                `;
                $("closeDetailModalFooterBtn")?.addEventListener("click", () => { $("detailModal").hidden = true; });
            }

            $("detailModal").hidden = false;
        } catch (error) {
            showMessage(error.message, "error");
        }
    }

    // =========================================================================
    // 6. TAB DANH MỤC VẬT TƯ & 7. TAB NHÓM VẬT TƯ
    // =========================================================================

    /**
     * Tải và phân trang bảng Danh mục vật tư ({@code GET /api/materials}) đồng thời cập nhật danh sách nhóm vật tư
     * ({@code GET /api/materials/categories}) cho bộ lọc và gợi ý datalist.
     * @param {number} [page=state.materialsPage] chỉ số trang cần tải (0-based)
     */
    async function loadMaterialsTable(page = state.materialsPage) {
        state.materialsPage = page;
        $("materialsTable").innerHTML = '<tr><td colspan="7" class="empty">Đang tải danh mục vật tư...</td></tr>';

        const params = new URLSearchParams({
            page,
            size: state.pageSize,
            sortBy: "materialName",
            direction: "asc"
        });
        const keyword = $("keyword").value.trim();
        if (keyword) params.set("keyword", keyword);
        if ($("matCategoryFilter").value) params.set("category", $("matCategoryFilter").value);
        if ($("matActiveFilter").value !== "") params.set("active", $("matActiveFilter").value);

        try {
            const [data, categories] = await Promise.all([
                request(`/api/materials?${params}`, { signal: getAbortSignal("materials") }),
                request("/api/materials/categories").catch(() => [])
            ]);

            populateCategoryFilterAndDatalist(categories);

            const rows = Array.isArray(data?.content) ? data.content : [];
            state.materialsTotalPages = Number(data?.totalPages || 0);

            $("materialsTable").innerHTML = rows.length ? rows.map(m => `
                <tr>
                    <td class="code">${escapeHtml(m.materialCode)}</td>
                    <td><strong>${escapeHtml(m.materialName)}</strong></td>
                    <td>${escapeHtml(m.category)}</td>
                    <td>${escapeHtml(m.unit)}</td>
                    <td>${escapeHtml(fmtQty(m.minStockThreshold))}</td>
                    <td>
                        <span class="status ${m.isActive ? "status-good" : "status-danger"}">
                            ${m.isActive ? "Đang sử dụng" : "Ngừng sử dụng"}
                        </span>
                    </td>
                    <td>
                        <div class="row-actions">
                            <button type="button" class="button button-secondary button-sm" data-edit-material="${m.materialId}">Sửa</button>
                            <button type="button" class="button ${m.isActive ? "button-danger-outline" : "button-secondary"} button-sm"
                                data-toggle-material="${m.materialId}" data-active="${m.isActive}" data-name="${escapeHtml(m.materialName)}">
                                ${m.isActive ? "Ngừng sử dụng" : "Kích hoạt"}
                            </button>
                        </div>
                    </td>
                </tr>
            `).join("") : '<tr><td colspan="7" class="empty">Không tìm thấy vật tư phù hợp.</td></tr>';

            const totalMaterials = Number(data?.totalElements || 0);
            updateMisaPagination("Materials", page, totalMaterials, state.materialsTotalPages);
        } catch (error) {
            if (error.name === "AbortError") return;
            $("materialsTable").innerHTML = `<tr><td colspan="7" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

    function populateCategoryFilterAndDatalist(categories) {
        const list = Array.isArray(categories) ? categories : [];
        const currentFilter = $("matCategoryFilter")?.value || "";
        if ($("matCategoryFilter")) {
            $("matCategoryFilter").innerHTML = '<option value="">Tất cả nhóm vật tư</option>' +
                list.map(c => `<option value="${escapeHtml(c.categoryName)}">${escapeHtml(c.categoryName)} (${c.totalMaterials})</option>`).join("");
            $("matCategoryFilter").value = currentFilter;
        }
        if ($("categorySuggestions")) {
            $("categorySuggestions").innerHTML = list.map(c => `<option value="${escapeHtml(c.categoryName)}"></option>`).join("");
        }
    }

    async function loadCategoriesTable() {
        $("categoriesTable").innerHTML = '<tr><td colspan="5" class="empty">Đang tải nhóm vật tư...</td></tr>';
        try {
            const list = await request("/api/materials/categories");
            populateCategoryFilterAndDatalist(list);
            $("categoriesTable").innerHTML = list.length ? list.map((c, idx) => `
                <tr>
                    <td>${idx + 1}</td>
                    <td><strong>${escapeHtml(c.categoryName)}</strong></td>
                    <td>${escapeHtml(c.totalMaterials)} vật tư</td>
                    <td><span class="status status-good">${escapeHtml(c.activeMaterials)} đang hoạt động</span></td>
                    <td>
                        <div class="row-actions">
                            <button type="button" class="button button-secondary button-sm" data-filter-cat="${escapeHtml(c.categoryName)}">Xem vật tư</button>
                            <button type="button" class="button button-secondary button-sm" data-rename-cat="${escapeHtml(c.categoryName)}">Đổi tên nhóm</button>
                        </div>
                    </td>
                </tr>
            `).join("") : '<tr><td colspan="5" class="empty">Chưa có nhóm vật tư.</td></tr>';
        } catch (error) {
            $("categoriesTable").innerHTML = `<tr><td colspan="5" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

    function openMaterialModal(material = null, prefillCategory = "") {
        $("materialForm").reset();
        $("editMaterialId").value = material?.materialId || "";
        $("materialModalTitle").textContent = material ? "Chỉnh sửa vật tư" : "Thêm vật tư mới";
        $("matCode").value = material?.materialCode || "";
        $("matName").value = material?.materialName || "";
        $("matCategory").value = material?.category || prefillCategory || "";
        $("matUnit").value = material?.unit || "Hộp";
        $("matThreshold").value = material?.minStockThreshold ?? 10;
        $("materialModal").hidden = false;
    }

    /**
     * Xử lý submit Modal thêm mới hoặc chỉnh sửa vật tư ({@code #materialForm} → {@code POST /api/materials} hoặc {@code PUT /api/materials/{id}}).
     * Kiểm tra định dạng mã vật tư ({@code REGEX.MATERIAL_CODE}) và ngưỡng tồn tối thiểu nguyên không âm,
     * sau đó lưu xuống server và làm mới đồng thời danh mục vật tư, bảng vật tư và bảng nhóm vật tư.
     * @param {SubmitEvent} event sự kiện submit của form {@code #materialForm}
     */
    async function submitMaterialForm(event) {
        event.preventDefault();
        const editId = $("editMaterialId").value;
        const payload = {
            materialCode: $("matCode").value.trim().toUpperCase(),
            materialName: $("matName").value.trim(),
            category: $("matCategory").value.trim(),
            unit: $("matUnit").value.trim(),
            minStockThreshold: Number($("matThreshold").value || 0)
        };
        if (!REGEX.MATERIAL_CODE.test(payload.materialCode)) {
            showMessage("Mã vật tư phải từ 3-20 ký tự, chỉ gồm chữ cái, số, dấu gạch ngang (-) hoặc gạch dưới (_).", "warning");
            $("matCode").focus();
            return;
        }
        if (!Number.isInteger(payload.minStockThreshold) || payload.minStockThreshold < 0) {
            showMessage("Ngưỡng cảnh báo tồn tối thiểu phải là số nguyên không âm.", "warning");
            return;
        }
        const btn = $("saveMaterialBtn");
        btn.disabled = true;
        try {
            await request(editId ? `/api/materials/${editId}` : "/api/materials", {
                method: editId ? "PUT" : "POST",
                body: JSON.stringify(payload)
            });
            $("materialModal").hidden = true;
            showMessage(editId ? "Đã cập nhật vật tư." : "Đã thêm vật tư mới.", "success");
            await Promise.all([loadActiveMaterialsCatalog(), loadMaterialsTable(0), loadCategoriesTable()]);
        } catch (error) {
            showMessage(error.message, "error");
        } finally {
            btn.disabled = false;
        }
    }

    // =========================================================================
    // 8. TAB NHÀ CUNG CẤP & THANH TOÁN CÔNG NỢ NCC (A3)
    // =========================================================================

    /**
     * Tải bảng tổng hợp công nợ nhà cung cấp tại chi nhánh hiện tại ({@code GET /api/inventory/supplier-payments/debts}),
     * lưu vào {@code state.supplierDebtsMap} để phục vụ validate khi thanh toán và render bảng công nợ kèm nút Thanh toán / Sửa / Ngừng.
     */
    async function loadSupplierDebts() {
        if (!requireBranch()) return;
        $("supplierDebtTable").innerHTML = '<tr><td colspan="10" class="empty">Đang tải dữ liệu công nợ...</td></tr>';
        const params = new URLSearchParams({ branchId: state.branchId });
        const keyword = $("keyword").value.trim();
        if (keyword) params.set("keyword", keyword);

        try {
            const list = await request(`/api/inventory/supplier-payments/debts?${params}`, { signal: getAbortSignal("debts") });
            state.supplierDebtsMap.clear();
            (list || []).forEach(s => state.supplierDebtsMap.set(Number(s.supplierId), s));

            $("supplierDebtTable").innerHTML = list.length ? list.map(s => {
                const debt = Number(s.remainingDebt || 0);
                return `<tr>
                    <td class="code">${escapeHtml(s.supplierCode)}</td>
                    <td><strong>${escapeHtml(s.supplierName)}</strong></td>
                    <td>${escapeHtml(s.taxCode || "—")}</td>
                    <td>${escapeHtml(s.phoneNumber || "—")}</td>
                    <td>${escapeHtml(s.receiptCount)} phiếu</td>
                    <td><strong>${money(s.totalImportAmount)}</strong></td>
                    <td class="stock-good">${money(s.totalPaidAmount)}</td>
                    <td class="${debt > 0 ? "stock-low" : "stock-good"}">${money(debt)}</td>
                    <td><span class="status ${s.isActive ? "status-good" : "status-danger"}">${s.isActive ? "Hoạt động" : "Ngừng"}</span></td>
                    <td>
                        <div class="row-actions">
                            <button type="button" class="button button-primary button-sm"
                                data-pay-supplier="${s.supplierId}" data-debt="${debt}"
                                ${debt <= 0 ? "disabled" : ""}>
                                Thanh toán
                            </button>
                            <button type="button" class="button button-secondary button-sm"
                                data-edit-supplier="${s.supplierId}">
                                Sửa
                            </button>
                            <button type="button" class="button ${s.isActive ? "button-danger-outline" : "button-secondary"} button-sm"
                                data-toggle-supplier="${s.supplierId}" data-active="${s.isActive}" data-name="${escapeHtml(s.supplierName)}">
                                ${s.isActive ? "Ngừng" : "Mở lại"}
                            </button>
                        </div>
                    </td>
                </tr>`;
            }).join("") : '<tr><td colspan="10" class="empty">Không có nhà cung cấp phù hợp.</td></tr>';
        } catch (error) {
            if (error.name === "AbortError") return;
            $("supplierDebtTable").innerHTML = `<tr><td colspan="10" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

    /**
     * Tải và phân trang lịch sử các phiếu chi thanh toán nhà cung cấp ({@code GET /api/inventory/supplier-payments})
     * theo từ khóa, nhà cung cấp ({@code #paymentFilterSupplier}) và khoảng ngày chi.
     * @param {number} [page=state.paymentsPage] chỉ số trang cần tải (0-based)
     */
    async function loadPaymentHistory(page = state.paymentsPage) {
        if (!requireBranch()) return;
        state.paymentsPage = page;
        $("supplierPaymentHistoryTable").innerHTML = '<tr><td colspan="9" class="empty">Đang tải lịch sử thanh toán...</td></tr>';

        const params = new URLSearchParams({
            branchId: state.branchId,
            page,
            size: state.pageSize,
            sortBy: "paymentDate",
            direction: "desc"
        });
        const keyword = $("keyword").value.trim();
        if (keyword) params.set("keyword", keyword);
        if ($("paymentFilterSupplier").value) params.set("supplierId", $("paymentFilterSupplier").value);
        if ($("paymentFromDate").value) params.set("fromDate", $("paymentFromDate").value);
        if ($("paymentToDate").value) params.set("toDate", $("paymentToDate").value);

        try {
            const data = await request(`/api/inventory/supplier-payments?${params}`, { signal: getAbortSignal("payments") });
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.paymentsTotalPages = Number(data?.totalPages || 0);

            $("supplierPaymentHistoryTable").innerHTML = rows.length ? rows.map(p => `
                <tr>
                    <td class="code">${escapeHtml(p.paymentCode)}</td>
                    <td>${escapeHtml(p.paymentDate)}</td>
                    <td><strong>${escapeHtml(p.supplierName)}</strong></td>
                    <td>${escapeHtml(p.receiptCode || "Công nợ chung")}</td>
                    <td><span class="status status-info">${p.paymentMethod === "CASH" ? "Tiền mặt" : "Chuyển khoản"}</span></td>
                    <td class="stock-good"><strong>${money(p.amount)}</strong></td>
                    <td>${escapeHtml(p.referenceNo || "—")}</td>
                    <td>${escapeHtml(p.paidByName || "—")}</td>
                    <td>${escapeHtml(p.notes || "—")}</td>
                </tr>
            `).join("") : '<tr><td colspan="9" class="empty">Chưa có giao dịch thanh toán nhà cung cấp.</td></tr>';

            const totalPayments = Number(data?.totalElements || 0);
            updateMisaPagination("Payments", page, totalPayments, state.paymentsTotalPages, "paymentHistory");
        } catch (error) {
            if (error.name === "AbortError") return;
            $("supplierPaymentHistoryTable").innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

    function updatePaymentDebtBanner(supplierId, selectedReceiptDebt = null) {
        const banner = $("paymentDebtBanner");
        if (!banner) return;
        const summary = state.supplierDebtsMap.get(Number(supplierId));
        if (!summary) {
            banner.hidden = true;
            return;
        }
        banner.hidden = false;
        const maxAllowed = selectedReceiptDebt != null
            ? Math.min(Number(selectedReceiptDebt), Number(summary.remainingDebt || 0))
            : Number(summary.remainingDebt || 0);
        if ($("payBannerTotalImport")) $("payBannerTotalImport").textContent = money(summary.totalImportAmount);
        if ($("payBannerTotalPaid")) $("payBannerTotalPaid").textContent = money(summary.totalPaidAmount);
        if ($("payBannerRemainingDebt")) $("payBannerRemainingDebt").textContent = money(maxAllowed);
        if ($("payAmount")) $("payAmount").max = String(Math.max(0, maxAllowed));
    }

    async function openPaymentModal(preselectedSupplierId = "", suggestedAmount = "") {
        $("paymentForm").reset();
        $("payDate").value = new Date().toISOString().slice(0, 10);
        if (!state.supplierDebtsMap.size && state.branchId) {
            await loadSupplierDebts();
        }
        if (preselectedSupplierId) {
            $("paySupplierId").value = String(preselectedSupplierId);
            await loadUnpaidReceiptsForPaymentModal(preselectedSupplierId);
            updatePaymentDebtBanner(preselectedSupplierId, null);
        } else {
            $("payReceiptId").innerHTML = '<option value="">-- Thanh toán công nợ chung --</option>';
            if ($("paymentDebtBanner")) $("paymentDebtBanner").hidden = true;
        }
        if (suggestedAmount && Number(suggestedAmount) > 0) {
            $("payAmount").value = Number(suggestedAmount);
        }
        $("paymentModal").hidden = false;
    }

    async function loadUnpaidReceiptsForPaymentModal(supplierId) {
        const receiptSelect = $("payReceiptId");
        receiptSelect.innerHTML = '<option value="">-- Thanh toán công nợ chung --</option>';
        if (!supplierId || !state.branchId) {
            updatePaymentDebtBanner(null, null);
            return;
        }
        updatePaymentDebtBanner(supplierId, null);
        try {
            const data = await request(`/api/inventory/receipts?branchId=${state.branchId}&supplierId=${supplierId}&page=0&size=100`);
            const receipts = (data?.content || []).filter(r => Number(r.remainingDebt || 0) > 0);
            receiptSelect.innerHTML += receipts.map(r =>
                `<option value="${escapeHtml(r.receiptId)}" data-debt="${escapeHtml(r.remainingDebt)}">${escapeHtml(r.receiptCode)} (${escapeHtml(r.receiptDate)}) - Còn nợ: ${money(r.remainingDebt)}</option>`
            ).join("");
        } catch {
            // ignore
        }
    }

    /**
     * Xử lý submit Modal lập phiếu thanh toán công nợ NCC ({@code #paymentForm} → {@code POST /api/inventory/supplier-payments}).
     * Validate nhà cung cấp, số chứng từ, số tiền &gt; 0 và không vượt công nợ còn lại của phiếu nhập hoặc tổng công nợ NCC;
     * gắn {@code clientRequestId} (UUID) chống double-submit, sau đó làm mới công nợ, lịch sử chi trả và KPI tổng quan.
     * @param {SubmitEvent} event sự kiện submit của form {@code #paymentForm}
     */
    async function submitPaymentForm(event) {
        event.preventDefault();
        if (!requireBranch()) return;
        const btn = $("savePaymentBtn");
        if (btn.disabled) return;

        const supplierId = Number($("paySupplierId").value);
        if (!supplierId) {
            showMessage("Vui lòng chọn nhà cung cấp cần thanh toán.", "warning");
            return;
        }
        const refNo = $("payRefNo").value.trim();
        if (refNo && !REGEX.REFERENCE_NO.test(refNo)) {
            showMessage("Số chứng từ / Mã giao dịch không hợp lệ (chỉ gồm chữ, số, dấu -, _, ., /).", "warning");
            $("payRefNo").focus();
            return;
        }
        const amount = Number($("payAmount").value);
        if (!Number.isFinite(amount) || amount <= 0) {
            showMessage("Số tiền thanh toán phải lớn hơn 0.", "warning");
            return;
        }

        const summary = state.supplierDebtsMap.get(supplierId);
        const receiptOpt = $("payReceiptId").options[$("payReceiptId").selectedIndex];
        const receiptDebt = receiptOpt?.dataset?.debt ? Number(receiptOpt.dataset.debt) : null;
        if (receiptDebt != null && amount > receiptDebt) {
            showMessage(`Số tiền thanh toán (${money(amount)}) vượt quá công nợ còn lại của phiếu nhập đã chọn (${money(receiptDebt)}).`, "warning");
            return;
        }
        if (summary && amount > Number(summary.remainingDebt || 0)) {
            showMessage(`Số tiền thanh toán (${money(amount)}) vượt quá tổng công nợ còn lại của nhà cung cấp (${money(summary.remainingDebt)}).`, "warning");
            return;
        }

        const clientRequestId = (typeof crypto !== "undefined" && crypto.randomUUID)
            ? crypto.randomUUID()
            : `req_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;

        const payload = {
            branchId: Number(state.branchId),
            supplierId,
            receiptId: $("payReceiptId").value ? Number($("payReceiptId").value) : null,
            paymentDate: $("payDate").value,
            paymentMethod: $("payMethod").value,
            amount,
            referenceNo: refNo || null,
            notes: $("payNotes").value.trim() || null,
            clientRequestId
        };
        btn.disabled = true;
        btn.textContent = "Đang xử lý...";
        try {
            const saved = await request("/api/inventory/supplier-payments", {
                method: "POST",
                body: JSON.stringify(payload)
            });
            $("paymentModal").hidden = true;
            showMessage(`Đã lập phiếu thanh toán ${saved.paymentCode} (${money(saved.amount)}) thành công!`, "success");
            await Promise.all([loadSupplierDebts(), loadPaymentHistory(0), updateSummaryStats()]);
            if (state.activeTab === "receipt") loadReceiptHistory(state.receiptsPage);
        } catch (error) {
            showMessage(error.message, "error");
        } finally {
            btn.disabled = false;
            btn.textContent = "Xác nhận thanh toán";
        }
    }

    function openQuickSupplierModal(supplier = null) {
        $("quickSupplierForm").reset();
        if ($("qsEditSupplierId")) $("qsEditSupplierId").value = supplier?.supplierId || "";
        if ($("quickSupplierTitle")) {
            $("quickSupplierTitle").textContent = supplier ? "Chỉnh sửa nhà cung cấp" : "Thêm mới Nhà cung cấp";
        }
        $("qsCode").value = supplier?.supplierCode || "";
        $("qsName").value = supplier?.supplierName || "";
        $("qsTaxCode").value = supplier?.taxCode || "";
        $("qsPhone").value = supplier?.phoneNumber || "";
        $("qsAddress").value = supplier?.address || "";
        $("quickSupplierModal").hidden = false;
    }

    /**
     * Xử lý submit Modal thêm mới hoặc chỉnh sửa nhanh Nhà cung cấp ({@code #quickSupplierForm} → {@code POST/PUT /api/suppliers}).
     * Kiểm tra regex mã NCC, mã số thuế và số điện thoại, sau đó làm mới dropdown NCC và bảng công nợ.
     * @param {SubmitEvent} event sự kiện submit của form {@code #quickSupplierForm}
     */
    async function submitQuickSupplierForm(event) {
        event.preventDefault();
        const editId = $("qsEditSupplierId")?.value || "";
        const payload = {
            supplierCode: $("qsCode").value.trim().toUpperCase(),
            supplierName: $("qsName").value.trim(),
            taxCode: $("qsTaxCode").value.trim(),
            phoneNumber: $("qsPhone").value.trim(),
            address: $("qsAddress").value.trim()
        };
        if (!REGEX.SUPPLIER_CODE.test(payload.supplierCode)) {
            showMessage("Mã NCC phải từ 2-20 ký tự (chữ, số, dấu - hoặc _).", "warning");
            $("qsCode").focus();
            return;
        }
        if (payload.taxCode && !REGEX.TAX_CODE.test(payload.taxCode)) {
            showMessage("Mã số thuế phải gồm 10 chữ số hoặc định dạng 10 số - 3 số (VD: 0101234567 hoặc 0101234567-001).", "warning");
            $("qsTaxCode").focus();
            return;
        }
        if (payload.phoneNumber && !REGEX.PHONE.test(payload.phoneNumber)) {
            showMessage("Số điện thoại không hợp lệ (bắt đầu bằng 0 hoặc +84, theo sau 9-10 chữ số).", "warning");
            $("qsPhone").focus();
            return;
        }

        const btn = $("saveQuickSupplierBtn");
        btn.disabled = true;
        try {
            await request(editId ? `/api/suppliers/${editId}` : "/api/suppliers", {
                method: editId ? "PUT" : "POST",
                body: JSON.stringify(payload)
            });
            $("quickSupplierModal").hidden = true;
            showMessage(editId ? "Đã cập nhật thông tin nhà cung cấp." : "Đã thêm nhà cung cấp mới thành công.", "success");
            await Promise.all([loadSuppliersCatalog(), loadSupplierDebts()]);
        } catch (error) {
            showMessage(error.message, "error");
        } finally {
            btn.disabled = false;
        }
    }

    // =========================================================================
    // SWITCH TABS & EVENT BINDINGS
    // =========================================================================

    /**
     * Chuyển đổi giữa 8 tab chức năng Quản lý kho ({@code inventory}, {@code batches}, {@code receipt}, {@code issue},
     * {@code transfer}, {@code materials}, {@code categories}, {@code supplier-payments}), ẩn/hiện panel tương ứng
     * và tự động nạp dữ liệu mới nhất cho tab vừa chọn.
     * @param {string} tab tên khóa tab cần hiển thị
     */
    function switchTab(tab) {
        state.activeTab = tab;
        const panels = {
            "inventory": "inventoryPanel",
            "batches": "batchesPanel",
            "receipt": "receiptPanel",
            "issue": "issuePanel",
            "transfer": "transferPanel",
            "materials": "materialsPanel",
            "categories": "categoriesPanel",
            "supplier-payments": "supplierPaymentsPanel"
        };
        Object.entries(panels).forEach(([key, panelId]) => {
            const el = $(panelId);
            if (el) el.hidden = key !== tab;
        });

        document.querySelectorAll(".tab").forEach(button =>
            button.classList.toggle("active", button.dataset.tab === tab)
        );

        if (state.branchId) {
            if (tab === "receipt") loadReceiptHistory(0);
            if (tab === "issue") loadIssueHistory(0);
            if (tab === "transfer") loadTransferHistory(0);
            if (tab === "supplier-payments") {
                loadSupplierDebts();
                loadPaymentHistory(0);
            }
        }
        if (tab === "materials") loadMaterialsTable(0);
        if (tab === "categories") loadCategoriesTable();

        const appView = document.querySelector('.inventory-app-view');
        if (appView) {
            appView.scrollTop = 0;
        }
    }

    function handleKpiCardActivate(target) {
        if (target === "inventory-all") {
            if ($("lowStock")) $("lowStock").checked = false;
            switchTab("inventory");
            loadInventory(0);
        } else if (target === "inventory-low") {
            if ($("lowStock")) $("lowStock").checked = true;
            switchTab("inventory");
            loadInventory(0);
        } else if (target === "batches-expiring") {
            const limitDate = new Date();
            limitDate.setDate(limitDate.getDate() + 30);
            if ($("expiryFrom")) $("expiryFrom").value = "";
            if ($("expiryTo")) $("expiryTo").value = limitDate.toISOString().slice(0, 10);
            switchTab("batches");
            loadBatches(0);
        } else if (target === "supplier-debt") {
            switchTab("supplier-payments");
        }
    }

    function triggerGlobalKeywordSearch() {
        loadInventory(0);
        loadBatches(0);
        if (state.activeTab === "receipt") loadReceiptHistory(0);
        if (state.activeTab === "issue") loadIssueHistory(0);
        if (state.activeTab === "transfer") loadTransferHistory(0);
        if (state.activeTab === "materials") loadMaterialsTable(0);
        if (state.activeTab === "supplier-payments") {
            loadSupplierDebts();
            loadPaymentHistory(0);
        }
    }


    // Thu gon / mo rong cac phan trong tab quan ly kho
    window.toggleInventorySection = function (sectionKey) {
        const contentEl = document.getElementById('content-' + sectionKey);
        const chevronEl = document.getElementById('chevron-' + sectionKey);
        const barEl = document.getElementById('bar-' + sectionKey);
        if (!contentEl) return;

        const isHidden = window.getComputedStyle(contentEl).display === 'none';
        if (isHidden) {
            contentEl.style.display = 'block';
            if (chevronEl) {
                chevronEl.className = 'bi bi-chevron-down df-foldable-chevron text-primary';
            }
            if (barEl) {
                barEl.classList.add('is-expanded');
            }
            try {
                localStorage.setItem('cv_inv_collapse_' + sectionKey, 'expanded');
            } catch (e) {}
            setTimeout(() => {
                if (barEl) barEl.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            }, 60);
        } else {
            contentEl.style.display = 'none';
            if (chevronEl) {
                chevronEl.className = 'bi bi-chevron-right df-foldable-chevron text-secondary';
            }
            if (barEl) {
                barEl.classList.remove('is-expanded');
            }
            try {
                localStorage.setItem('cv_inv_collapse_' + sectionKey, 'collapsed');
            } catch (e) {}
        }
    };

    function initInventorySectionCollapse() {
        const sections = [
            'receipt-create', 'receipt-history',
            'issue-create', 'issue-history',
            'transfer-create', 'transfer-history',
            'supplier-debt', 'supplier-history'
        ];
        sections.forEach(function (key) {
            try {
                const savedState = localStorage.getItem('cv_inv_collapse_' + key);
                if (savedState === 'collapsed') {
                    const contentEl = document.getElementById('content-' + key);
                    const chevronEl = document.getElementById('chevron-' + key);
                    const barEl = document.getElementById('bar-' + key);
                    if (contentEl) contentEl.style.display = 'none';
                    if (chevronEl) chevronEl.className = 'bi bi-chevron-right df-foldable-chevron text-secondary';
                    if (barEl) barEl.classList.remove('is-expanded');
                }
            } catch (e) {}
        });
    }

    /**
     * Khởi tạo toàn bộ trạng thái mặc định (ngày hiện tại cho các form, trạng thái thu gọn section, số thứ tự dòng phiếu),
     * gắn toàn bộ event listener cho tabs, KPI cards, tìm kiếm toàn cục, phân trang 7 bảng, Material Picker Modal,
     * các form/modal CRUD và delegated table actions, sau đó gọi {@link loadBranches} để bắt đầu tải dữ liệu kho.
     */
    document.addEventListener("DOMContentLoaded", () => {
        const todayStr = new Date().toISOString().slice(0, 10);
        if ($("receiptDate")) $("receiptDate").value = todayStr;
        if ($("issueDate")) $("issueDate").value = todayStr;
        if ($("transferDate")) $("transferDate").value = todayStr;
        if ($("payDate")) $("payDate").value = todayStr;

        initInventorySectionCollapse();
        reindexAndUpdateVoucherRows("receipt");
        reindexAndUpdateVoucherRows("issue");
        reindexAndUpdateVoucherRows("transfer");

        document.querySelectorAll(".tab").forEach(button =>
            button.addEventListener("click", () => switchTab(button.dataset.tab))
        );

        document.querySelectorAll("[data-kpi-target]").forEach(card => {
            card.addEventListener("click", () => handleKpiCardActivate(card.dataset.kpiTarget));
            card.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    handleKpiCardActivate(card.dataset.kpiTarget);
                }
            });
        });

        safeOn("branchId", "change", async () => {
            state.branchId = Number($("branchId").value) || null;
            populateDestinationBranches();
            await refreshBranchData();
        });

        safeOn("searchBtn", "click", triggerGlobalKeywordSearch);

        if ($("clearSearchBtn")) {
            safeOn("clearSearchBtn", "click", () => {
                $("keyword").value = "";
                if ($("lowStock")) $("lowStock").checked = false;
                triggerGlobalKeywordSearch();
            });
        }

        const debouncedGlobalSearch = debounce(triggerGlobalKeywordSearch, 350);
        safeOn("keyword", "input", debouncedGlobalSearch);
        safeOn("keyword", "keydown", e => {
            if (e.key === "Enter") { e.preventDefault(); triggerGlobalKeywordSearch(); }
        });

        // A1: Invoice file upload & preview bindings
        if ($("invoiceFileInput")) {
            safeOn("invoiceFileInput", "change", (e) => {
                const file = e.target.files?.[0];
                if (file) handleInvoiceFileUpload(file);
            });
        }
        if ($("clearUploadedInvoiceBtn")) {
            safeOn("clearUploadedInvoiceBtn", "click", clearUploadedInvoice);
        }
        if ($("zoomUploadedInvoiceBtn")) {
            safeOn("zoomUploadedInvoiceBtn", "click", () => {
                openImageLightbox($("attachmentUrl").value, "Hóa đơn đính kèm phiếu nhập");
            });
        }
        if ($("invoicePreviewImg")) {
            safeOn("invoicePreviewImg", "click", () => {
                openImageLightbox($("attachmentUrl").value, "Hóa đơn đính kèm phiếu nhập");
            });
        }

        // A2: Initial payment bindings
        if ($("receiptPaidAmount")) {
            safeOn("receiptPaidAmount", "input", calculateReceiptTotal);
        }
        if ($("payFullReceiptBtn")) {
            safeOn("payFullReceiptBtn", "click", () => {
                const total = computeCurrentReceiptTotalNumber();
                if ($("receiptPaidAmount")) {
                    $("receiptPaidAmount").value = String(total);
                    calculateReceiptTotal();
                }
            });
        }

        if ($("closeLightboxBtn")) safeOn("closeLightboxBtn", "click", () => { $("imageLightboxModal").hidden = true; });
        if ($("closeLightboxFooterBtn")) safeOn("closeLightboxFooterBtn", "click", () => { $("imageLightboxModal").hidden = true; });

        safeOn("lowStock", "change", () => loadInventory(0));
        safeOn("filterBatchesBtn", "click", () => loadBatches(0));
        if ($("clearBatchesFilterBtn")) {
            safeOn("clearBatchesFilterBtn", "click", () => {
                if ($("expiryFrom")) $("expiryFrom").value = "";
                $("expiryTo").value = "";
                loadBatches(0);
            });
        }

        safeOn("filterReceiptsBtn", "click", () => loadReceiptHistory(0));
        safeOn("filterIssuesBtn", "click", () => loadIssueHistory(0));
        safeOn("filterTransfersBtn", "click", () => loadTransferHistory(0));
        safeOn("filterPaymentsBtn", "click", () => loadPaymentHistory(0));

        safeOn("issueType", "change", refreshIssueAndTransferRows);
        safeOn("matCategoryFilter", "change", () => loadMaterialsTable(0));
        safeOn("matActiveFilter", "change", () => loadMaterialsTable(0));

        // Pagination buttons (Cụm 4 nút phân trang chuẩn MISA AMIS: |< < > >|)
        safeOn("inventoryFirst", "click", () => loadInventory(0));
        safeOn("inventoryPrev", "click", () => loadInventory(Math.max(0, state.inventoryPage - 1)));
        safeOn("inventoryNext", "click", () => { if (state.inventoryPage < state.inventoryTotalPages - 1) loadInventory(state.inventoryPage + 1); });
        safeOn("inventoryLast", "click", () => { if (state.inventoryTotalPages > 0) loadInventory(state.inventoryTotalPages - 1); });

        safeOn("batchesFirst", "click", () => loadBatches(0));
        safeOn("batchesPrev", "click", () => loadBatches(Math.max(0, state.batchesPage - 1)));
        safeOn("batchesNext", "click", () => { if (state.batchesPage < state.batchesTotalPages - 1) loadBatches(state.batchesPage + 1); });
        safeOn("batchesLast", "click", () => { if (state.batchesTotalPages > 0) loadBatches(state.batchesTotalPages - 1); });

        safeOn("receiptHistoryFirst", "click", () => loadReceiptHistory(0));
        safeOn("receiptHistoryPrev", "click", () => loadReceiptHistory(Math.max(0, state.receiptsPage - 1)));
        safeOn("receiptHistoryNext", "click", () => { if (state.receiptsPage < state.receiptsTotalPages - 1) loadReceiptHistory(state.receiptsPage + 1); });
        safeOn("receiptHistoryLast", "click", () => { if (state.receiptsTotalPages > 0) loadReceiptHistory(state.receiptsTotalPages - 1); });

        safeOn("issueHistoryFirst", "click", () => loadIssueHistory(0));
        safeOn("issueHistoryPrev", "click", () => loadIssueHistory(Math.max(0, state.issuesPage - 1)));
        safeOn("issueHistoryNext", "click", () => { if (state.issuesPage < state.issuesTotalPages - 1) loadIssueHistory(state.issuesPage + 1); });
        safeOn("issueHistoryLast", "click", () => { if (state.issuesTotalPages > 0) loadIssueHistory(state.issuesTotalPages - 1); });

        safeOn("transferHistoryFirst", "click", () => loadTransferHistory(0));
        safeOn("transferHistoryPrev", "click", () => loadTransferHistory(Math.max(0, state.transfersPage - 1)));
        safeOn("transferHistoryNext", "click", () => { if (state.transfersPage < state.transfersTotalPages - 1) loadTransferHistory(state.transfersPage + 1); });
        safeOn("transferHistoryLast", "click", () => { if (state.transfersTotalPages > 0) loadTransferHistory(state.transfersTotalPages - 1); });

        safeOn("materialsFirst", "click", () => loadMaterialsTable(0));
        safeOn("materialsPrev", "click", () => loadMaterialsTable(Math.max(0, state.materialsPage - 1)));
        safeOn("materialsNext", "click", () => { if (state.materialsPage < state.materialsTotalPages - 1) loadMaterialsTable(state.materialsPage + 1); });
        safeOn("materialsLast", "click", () => { if (state.materialsTotalPages > 0) loadMaterialsTable(state.materialsTotalPages - 1); });

        safeOn("paymentHistoryFirst", "click", () => loadPaymentHistory(0));
        safeOn("paymentHistoryPrev", "click", () => loadPaymentHistory(Math.max(0, state.paymentsPage - 1)));
        safeOn("paymentHistoryNext", "click", () => { if (state.paymentsPage < state.paymentsTotalPages - 1) loadPaymentHistory(state.paymentsPage + 1); });
        safeOn("paymentHistoryLast", "click", () => { if (state.paymentsTotalPages > 0) loadPaymentHistory(state.paymentsTotalPages - 1); });

        // =========================================================================
        // MODAL TÌM KIẾM & CHỌN NHIỀU VẬT TƯ KHO (NHẬP / XUẤT / ĐIỀU CHUYỂN)
        // =========================================================================
        const pickerState = {
            targetFormType: "receipt",
            keyword: "",
            category: "ALL",
            stockFilter: "ALL",
            page: 0,
            size: 20,
            hasMore: false,
            totalElements: 0,
            items: [],
            selectedMap: new Map(),
            existingMaterialIds: new Set(),
            abortController: null,
            requestSeq: 0
        };

        function getExistingMaterialIdsInForm(formType) {
            const containerSelector = formType === "receipt"
                ? "#receiptItems .receipt-item-row .material-id"
                : (formType === "transfer"
                    ? "#transferItems .transfer-item-row .transfer-material-id"
                    : "#issueItems .issue-item-row .issue-material-id");
            const ids = new Set();
            document.querySelectorAll(containerSelector).forEach(el => {
                const val = Number(el.value);
                if (val && val > 0) {
                    ids.add(val);
                }
            });
            return ids;
        }

        function syncPickerExistingMaterials() {
            pickerState.existingMaterialIds = getExistingMaterialIdsInForm(pickerState.targetFormType);
            for (const existingId of pickerState.existingMaterialIds) {
                if (pickerState.selectedMap.has(existingId)) {
                    pickerState.selectedMap.delete(existingId);
                }
            }
        }

        function updateMaterialPickerSelectedCounter() {
            syncPickerExistingMaterials();
            const countEl = $("matPickerSelectedCount");
            if (countEl) {
                countEl.textContent = String(pickerState.selectedMap.size);
            }
            const confirmBtn = $("confirmMaterialPickerBtn");
            if (confirmBtn) {
                confirmBtn.disabled = pickerState.selectedMap.size === 0;
            }
        }

        /**
         * Mở Modal tìm kiếm &amp; chọn nhiều vật tư ({@code #materialPickerModal}) cho loại phiếu đích
         * ({@code "receipt"}, {@code "issue"}, hoặc {@code "transfer"}), tự chọn bộ lọc tồn kho mặc định phù hợp
         * (tất cả / khả dụng / hết hạn) và gọi {@link loadMaterialPickerItems}.
         * @param {"receipt"|"issue"|"transfer"} targetFormType loại phiếu đang lập
         */
        function openMaterialPickerModal(targetFormType) {
            if (!requireBranch()) return;
            pickerState.targetFormType = targetFormType || "receipt";
            pickerState.keyword = "";
            pickerState.category = "ALL";
            pickerState.page = 0;
            pickerState.items = [];
            pickerState.selectedMap.clear();
            syncPickerExistingMaterials();

            if ($("matPickerKeywordInput")) $("matPickerKeywordInput").value = "";
            if ($("matPickerCategorySelect")) $("matPickerCategorySelect").value = "ALL";

            let defaultFilter = "ALL";
            let titleText = "Chọn vật tư nhập kho";
            let subText = "Tìm kiếm theo mã, tên, nhóm vật tư và chọn một hoặc nhiều vật tư để đưa vào Phiếu nhập kho";

            if (targetFormType === "issue") {
                const isDiscard = $("issueType")?.value === "DISCARD_EXPIRED";
                defaultFilter = isDiscard ? "EXPIRED_ONLY" : "AVAILABLE_STOCK";
                titleText = "Chọn vật tư xuất kho";
                subText = isDiscard
                    ? "Hiển thị ưu tiên các vật tư có lô hết hạn cần xuất hủy tại cơ sở"
                    : "Tìm kiếm và chọn một hoặc nhiều vật tư còn tồn khả dụng để đưa vào Phiếu xuất kho";
            } else if (targetFormType === "transfer") {
                defaultFilter = "AVAILABLE_STOCK";
                titleText = "Chọn vật tư điều chuyển kho";
                subText = "Tìm kiếm và chọn một hoặc nhiều vật tư còn hạn sử dụng để điều chuyển sang chi nhánh đích";
            }

            pickerState.stockFilter = defaultFilter;
            if ($("matPickerStockFilterSelect")) $("matPickerStockFilterSelect").value = defaultFilter;
            if ($("materialPickerModalTitle")) {
                $("materialPickerModalTitle").innerHTML = `<i class="bi bi-box-seam-fill me-2 text-primary"></i>${escapeHtml(titleText)}`;
            }
            if ($("materialPickerModalSubtitle")) {
                $("materialPickerModalSubtitle").textContent = subText;
            }

            updateMaterialPickerSelectedCounter();
            if ($("materialPickerModal")) {
                $("materialPickerModal").hidden = false;
            }
            if ($("matPickerKeywordInput")) {
                $("matPickerKeywordInput").focus();
            }
            loadMaterialPickerItems(false);
        }

        function closeMaterialPickerModal() {
            if ($("materialPickerModal")) {
                $("materialPickerModal").hidden = true;
            }
        }

        /**
         * Gọi {@code GET /api/inventory/picker-materials} theo chi nhánh, từ khóa, nhóm vật tư và bộ lọc tồn kho
         * (dùng {@code AbortController} + {@code requestSeq} chống race condition), cập nhật danh sách nhóm và render bảng chọn.
         * @param {boolean} [append=false] {@code true} để nối thêm trang kế tiếp ("Tải thêm vật tư"), {@code false} để tải lại từ trang 0
         */
        async function loadMaterialPickerItems(append = false) {
            if (!state.branchId) return;
            const tbody = $("matPickerTableBody");
            if (!tbody) return;

            if (!append) {
                pickerState.page = 0;
                pickerState.items = [];
                tbody.innerHTML = '<tr><td colspan="7" class="empty">Đang tải danh sách vật tư...</td></tr>';
            }

            if (pickerState.abortController) {
                try { pickerState.abortController.abort(); } catch (e) {}
            }
            const abortController = new AbortController();
            pickerState.abortController = abortController;
            const seq = ++pickerState.requestSeq;

            const params = new URLSearchParams({
                branchId: String(state.branchId),
                stockFilter: pickerState.stockFilter || "ALL",
                page: String(pickerState.page),
                size: String(pickerState.size)
            });
            if (pickerState.keyword && pickerState.keyword.trim() !== "") {
                params.set("keyword", pickerState.keyword.trim());
            }
            if (pickerState.category && pickerState.category !== "ALL") {
                params.set("category", pickerState.category);
            }

            try {
                const data = await request(`/api/inventory/picker-materials?${params.toString()}`, {
                    signal: abortController.signal
                });
                if (seq !== pickerState.requestSeq) return;

                const pageContent = Array.isArray(data?.content) ? data.content : [];
                pickerState.items = append ? pickerState.items.concat(pageContent) : pageContent;
                pickerState.hasMore = Boolean(data?.hasMore);
                pickerState.totalElements = Number(data?.totalElements || pickerState.items.length);

                const catSelect = $("matPickerCategorySelect");
                if (catSelect && Array.isArray(data?.categories)) {
                    const currentCat = pickerState.category || "ALL";
                    catSelect.innerHTML = '<option value="ALL">Tất cả nhóm vật tư</option>' +
                        data.categories.map(c => `<option value="${escapeHtml(c)}" ${c === currentCat ? "selected" : ""}>${escapeHtml(c)}</option>`).join("");
                }

                renderMaterialPickerTable();
            } catch (err) {
                if (err && err.name === "AbortError") return;
                if (seq !== pickerState.requestSeq) return;
                tbody.innerHTML = `<tr><td colspan="7" class="empty text-danger">${escapeHtml(err.message || "Không thể tải danh sách vật tư.")}</td></tr>`;
            }
        }

        function renderMaterialPickerTable() {
            const tbody = $("matPickerTableBody");
            if (!tbody) return;

            syncPickerExistingMaterials();

            if ($("matPickerTotalInfo")) {
                const existingCount = pickerState.existingMaterialIds.size;
                const extraNote = existingCount > 0 ? ` · Đã có trong phiếu: ${existingCount}` : "";
                $("matPickerTotalInfo").textContent = `Hiển thị ${pickerState.items.length} / ${pickerState.totalElements} vật tư${extraNote}`;
            }
            if ($("matPickerLoadMoreWrap")) {
                $("matPickerLoadMoreWrap").hidden = !pickerState.hasMore;
            }

            if (!pickerState.items.length) {
                tbody.innerHTML = '<tr><td colspan="7" class="empty">Không tìm thấy vật tư nào phù hợp với điều kiện lọc.</td></tr>';
                return;
            }

            tbody.innerHTML = pickerState.items.map(m => {
                const mid = Number(m.materialId);
                const isAlreadyInVoucher = pickerState.existingMaterialIds.has(mid);
                const isNewlySelected = !isAlreadyInVoucher && pickerState.selectedMap.has(mid);
                const curStock = Number(m.currentStock || 0);
                const availStock = Number(m.availableStock || 0);
                const expStock = Number(m.expiredStock || 0);
                const minThresh = Number(m.minStockThreshold || 0);
                const isLow = curStock <= minThresh;

                let batchInfoHtml = '<span class="text-muted small">Chưa có lô tồn</span>';
                if (availStock > 0 && m.nearestExpiryDate) {
                    batchInfoHtml = `<span class="status status-good">Khả dụng: ${escapeHtml(fmtQty(availStock))} · HSD: ${escapeHtml(m.nearestExpiryDate)}</span>`;
                } else if (expStock > 0) {
                    batchInfoHtml = `<span class="status status-danger">Lô hết hạn: ${escapeHtml(fmtQty(expStock))}</span>`;
                }

                const stateBadgeHtml = isAlreadyInVoucher
                    ? '<span class="mat-picker-in-voucher-badge" title="Vật tư này đã có trong bảng chi tiết của phiếu"><i class="bi bi-check2-all"></i>Đã có trong phiếu</span>'
                    : (isNewlySelected
                        ? '<span class="mat-picker-selected-tag"><i class="bi bi-check-lg"></i>Đã chọn</span>'
                        : "");

                const rowClass = isAlreadyInVoucher
                    ? "is-already-in-voucher"
                    : (isNewlySelected ? "is-selected" : "");

                return `
                    <tr class="mat-picker-row ${rowClass}" data-picker-mat-id="${mid}" data-already-in-voucher="${isAlreadyInVoucher ? "true" : "false"}">
                        <td style="text-align:center;">
                            <input type="checkbox" class="mat-picker-checkbox" data-picker-check="${mid}"
                                   ${(isAlreadyInVoucher || isNewlySelected) ? "checked" : ""}
                                   ${isAlreadyInVoucher ? 'disabled title="Vật tư này đã có trong phiếu"' : ""}>
                        </td>
                        <td class="code">${escapeHtml(m.materialCode)}</td>
                        <td>
                            <strong>${escapeHtml(m.materialName)}</strong>
                            ${stateBadgeHtml}
                        </td>
                        <td>${escapeHtml(m.category || "—")}</td>
                        <td>${escapeHtml(m.unit || "—")}</td>
                        <td style="text-align:right;" class="${curStock <= 0 ? "stock-low" : (isLow ? "stock-low" : "stock-good")} fw-bold">
                            ${escapeHtml(fmtQty(curStock))}
                        </td>
                        <td>${batchInfoHtml}</td>
                    </tr>
                `;
            }).join("");
        }

        function togglePickerMaterialSelection(materialId, forceState = null) {
            const mid = Number(materialId);
            syncPickerExistingMaterials();
            if (pickerState.existingMaterialIds.has(mid)) {
                showMessage("Vật tư này đã có trong phiếu, không thể chọn trùng.", "info");
                renderMaterialPickerTable();
                return;
            }
            const itemObj = pickerState.items.find(m => Number(m.materialId) === mid) || pickerState.selectedMap.get(mid);
            if (!itemObj) return;

            const shouldSelect = forceState !== null ? Boolean(forceState) : !pickerState.selectedMap.has(mid);
            if (shouldSelect) {
                pickerState.selectedMap.set(mid, itemObj);
            } else {
                pickerState.selectedMap.delete(mid);
            }
            updateMaterialPickerSelectedCounter();
            renderMaterialPickerTable();
        }

        // Fallback backward-compatibility safe no-op
        window.refreshMaterialSelectsInForms = function() {};

        /**
         * Xác nhận thêm tất cả vật tư đã tick trong {@code pickerState.selectedMap} (loại trừ vật tư đã tồn tại trong phiếu)
         * vào bảng chi tiết tương ứng (phiếu nhập / xuất / điều chuyển), đánh lại STT, tính lại tổng tiền và đóng modal.
         */
        function confirmMaterialPickerSelection() {
            syncPickerExistingMaterials();
            const selectedItems = Array.from(pickerState.selectedMap.values())
                .filter(item => !pickerState.existingMaterialIds.has(Number(item.materialId)));
            if (!selectedItems.length) {
                showMessage("Vui lòng chọn ít nhất 1 vật tư chưa có trong phiếu để thêm.", "warning");
                return;
            }

            selectedItems.forEach(item => {
                if (!state.activeMaterials.some(m => Number(m.materialId) === Number(item.materialId))) {
                    state.activeMaterials.push({
                        materialId: item.materialId,
                        materialCode: item.materialCode,
                        materialName: item.materialName,
                        category: item.category,
                        unit: item.unit,
                        minStockThreshold: item.minStockThreshold,
                        isActive: true
                    });
                }
            });

            const formType = pickerState.targetFormType;
            let addedCount = 0;
            const currentVoucherMatIds = getExistingMaterialIdsInForm(formType);
            let firstAddedRow = null;

            for (const mat of selectedItems) {
                const midNum = Number(mat.materialId);
                if (currentVoucherMatIds.has(midNum)) {
                    continue;
                }

                let newRow = null;
                if (formType === "receipt") {
                    newRow = addReceiptRow(mat);
                } else if (formType === "transfer") {
                    newRow = addIssueOrTransferRow("transferItems", true, mat);
                } else {
                    newRow = addIssueOrTransferRow("issueItems", false, mat);
                }

                if (newRow) {
                    if (!firstAddedRow) firstAddedRow = newRow;
                    currentVoucherMatIds.add(midNum);
                    addedCount++;
                }
            }

            if (formType === "receipt") {
                calculateReceiptTotal();
            }
            reindexAndUpdateVoucherRows(formType);

            closeMaterialPickerModal();
            if (firstAddedRow) {
                const focusTarget = firstAddedRow.querySelector(".batch-number, .quantity");
                if (focusTarget) focusTarget.focus();
            }
            if (addedCount > 0) {
                showMessage(`Đã thêm ${addedCount} vật tư vào bảng chi tiết của phiếu.`, "success");
            }
        }

        safeOn("openReceiptMaterialPickerBtn", "click", () => openMaterialPickerModal("receipt"));
        safeOn("openIssueMaterialPickerBtn", "click", () => openMaterialPickerModal("issue"));
        safeOn("openTransferMaterialPickerBtn", "click", () => openMaterialPickerModal("transfer"));
        safeOn("closeMaterialPickerModalBtn", "click", closeMaterialPickerModal);
        safeOn("cancelMaterialPickerModalBtn", "click", closeMaterialPickerModal);
        safeOn("confirmMaterialPickerBtn", "click", confirmMaterialPickerSelection);

        const debouncedPickerSearch = debounce(() => {
            pickerState.keyword = $("matPickerKeywordInput")?.value || "";
            loadMaterialPickerItems(false);
        }, 300);
        safeOn("matPickerKeywordInput", "input", debouncedPickerSearch);
        safeOn("matPickerCategorySelect", "change", (e) => {
            pickerState.category = e.target.value || "ALL";
            loadMaterialPickerItems(false);
        });
        safeOn("matPickerStockFilterSelect", "change", (e) => {
            pickerState.stockFilter = e.target.value || "ALL";
            loadMaterialPickerItems(false);
        });
        safeOn("matPickerLoadMoreBtn", "click", () => {
            pickerState.page += 1;
            loadMaterialPickerItems(true);
        });
        safeOn("matPickerSelectAllPageBtn", "click", () => {
            syncPickerExistingMaterials();
            pickerState.items.forEach(m => {
                const mid = Number(m.materialId);
                if (!pickerState.existingMaterialIds.has(mid)) {
                    pickerState.selectedMap.set(mid, m);
                }
            });
            updateMaterialPickerSelectedCounter();
            renderMaterialPickerTable();
        });
        safeOn("matPickerClearSelectionBtn", "click", () => {
            pickerState.selectedMap.clear();
            updateMaterialPickerSelectedCounter();
            renderMaterialPickerTable();
        });
        if ($("matPickerTableBody")) {
            $("matPickerTableBody").addEventListener("click", (e) => {
                const row = e.target.closest("[data-picker-mat-id]");
                if (!row) return;
                const mid = Number(row.getAttribute("data-picker-mat-id"));
                if (e.target.matches("[data-picker-check]")) {
                    togglePickerMaterialSelection(mid, e.target.checked);
                } else {
                    togglePickerMaterialSelection(mid);
                }
            });
        }

        safeOn("resetReceiptBtn", "click", resetReceiptForm);
        safeOn("resetIssueBtn", "click", resetIssueForm);
        safeOn("resetTransferBtn", "click", resetTransferForm);

        safeOn("receiptForm", "submit", submitReceipt);
        safeOn("issueForm", "submit", submitIssue);
        safeOn("transferForm", "submit", submitTransfer);
        safeOn("materialForm", "submit", submitMaterialForm);
        safeOn("paymentForm", "submit", submitPaymentForm);
        safeOn("quickSupplierForm", "submit", submitQuickSupplierForm);

        // Modals open/close
        safeOn("openAddMaterialBtn", "click", () => openMaterialModal());
        safeOn("addMaterialFromCategoryBtn", "click", () => openMaterialModal());
        safeOn("closeMaterialModalBtn", "click", () => { $("materialModal").hidden = true; });
        safeOn("cancelMaterialModalBtn", "click", () => { $("materialModal").hidden = true; });

        safeOn("openPaymentModalBtn", "click", () => openPaymentModal());
        safeOn("closePaymentModalBtn", "click", () => { $("paymentModal").hidden = true; });
        safeOn("cancelPaymentModalBtn", "click", () => { $("paymentModal").hidden = true; });

        safeOn("paySupplierId", "change", (e) => loadUnpaidReceiptsForPaymentModal(e.target.value));
        safeOn("payReceiptId", "change", (e) => {
            const opt = e.target.options[e.target.selectedIndex];
            const receiptDebt = opt?.dataset?.debt ? Number(opt.dataset.debt) : null;
            updatePaymentDebtBanner($("paySupplierId").value, receiptDebt);
            if (receiptDebt != null) {
                $("payAmount").value = receiptDebt;
            }
        });

        safeOn("openQuickSupplierBtn", "click", () => openQuickSupplierModal());
        safeOn("closeQuickSupplierBtn", "click", () => { $("quickSupplierModal").hidden = true; });
        safeOn("cancelQuickSupplierBtn", "click", () => { $("quickSupplierModal").hidden = true; });

        safeOn("closeDetailModalBtn", "click", () => { $("detailModal").hidden = true; });
        $("closeDetailModalFooterBtn")?.addEventListener("click", () => { $("detailModal").hidden = true; });

        ["materialModal", "paymentModal", "quickSupplierModal", "detailModal", "imageLightboxModal", "materialPickerModal"].forEach(modalId => {
            const modalEl = $(modalId);
            if (modalEl) {
                modalEl.addEventListener("click", (e) => {
                    if (e.target === modalEl) modalEl.hidden = true;
                });
            }
        });

        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape") {
                ["materialModal", "paymentModal", "quickSupplierModal", "detailModal", "imageLightboxModal", "materialPickerModal"].forEach(modalId => {
                    const modalEl = $(modalId);
                    if (modalEl && !modalEl.hidden) modalEl.hidden = true;
                });
            }
        });

        // Delegated table actions
        document.body.addEventListener("click", async (e) => {
            const zoomBtn = e.target.closest("[data-zoom-image]");
            if (zoomBtn) {
                openImageLightbox(zoomBtn.dataset.zoomImage, zoomBtn.dataset.zoomTitle || "Chứng từ hóa đơn");
                return;
            }

            const printDocBtn = e.target.closest("[data-print-url]");
            if (printDocBtn) {
                printInventoryDocument(printDocBtn.dataset.printUrl);
                return;
            }

            const viewReceiptBtn = e.target.closest("[data-view-receipt]");
            if (viewReceiptBtn) {
                await viewReceiptDetail(viewReceiptBtn.dataset.viewReceipt);
                return;
            }

            const viewIssueBtn = e.target.closest("[data-view-issue]");
            if (viewIssueBtn) {
                await viewIssueDetail(viewIssueBtn.dataset.viewIssue);
                return;
            }

            const editMatBtn = e.target.closest("[data-edit-material]");
            if (editMatBtn) {
                try {
                    const m = await request(`/api/materials/${editMatBtn.dataset.editMaterial}`);
                    openMaterialModal(m);
                } catch (err) {
                    showMessage(err.message, "error");
                }
                return;
            }

            const toggleMatBtn = e.target.closest("[data-toggle-material]");
            if (toggleMatBtn) {
                const id = toggleMatBtn.dataset.toggleMaterial;
                const isActive = toggleMatBtn.dataset.active === "true";
                const matName = toggleMatBtn.dataset.name || "vật tư này";
                const confirmText = isActive
                    ? `Bạn có chắc muốn ngừng sử dụng "${matName}"?`
                    : `Kích hoạt lại vật tư "${matName}"?`;
                if (!window.confirm(confirmText)) return;
                const action = isActive ? "deactivate" : "activate";
                try {
                    await request(`/api/materials/${id}/${action}`, { method: "POST" });
                    showMessage(isActive ? "Đã ngừng sử dụng vật tư." : "Đã kích hoạt lại vật tư.", "success");
                    await Promise.all([loadActiveMaterialsCatalog(), loadMaterialsTable(state.materialsPage), loadCategoriesTable()]);
                } catch (err) {
                    showMessage(err.message, "error");
                }
                return;
            }

            const filterCatBtn = e.target.closest("[data-filter-cat]");
            if (filterCatBtn) {
                const cat = filterCatBtn.dataset.filterCat;
                switchTab("materials");
                $("matCategoryFilter").value = cat;
                await loadMaterialsTable(0);
                return;
            }

            const renameCatBtn = e.target.closest("[data-rename-cat]");
            if (renameCatBtn) {
                const oldCat = renameCatBtn.dataset.renameCat;
                const newCat = window.prompt(`Nhập tên mới cho nhóm vật tư "${oldCat}":`, oldCat);
                if (!newCat || newCat.trim() === oldCat) return;
                try {
                    const res = await request("/api/materials/categories/rename", {
                        method: "PUT",
                        body: JSON.stringify({ oldCategory: oldCat, newCategory: newCat.trim() })
                    });
                    showMessage(`Đã đổi tên nhóm "${oldCat}" thành "${newCat.trim()}" (${res.updatedMaterials} vật tư).`, "success");
                    await Promise.all([loadCategoriesTable(), loadActiveMaterialsCatalog()]);
                } catch (err) {
                    showMessage(err.message, "error");
                }
                return;
            }

            const paySuppBtn = e.target.closest("[data-pay-supplier]");
            if (paySuppBtn) {
                await openPaymentModal(paySuppBtn.dataset.paySupplier, paySuppBtn.dataset.debt);
                return;
            }

            const editSuppBtn = e.target.closest("[data-edit-supplier]");
            if (editSuppBtn) {
                try {
                    const s = await request(`/api/suppliers/${editSuppBtn.dataset.editSupplier}`);
                    openQuickSupplierModal(s);
                } catch (err) {
                    showMessage(err.message, "error");
                }
                return;
            }

            const toggleSuppBtn = e.target.closest("[data-toggle-supplier]");
            if (toggleSuppBtn) {
                const id = toggleSuppBtn.dataset.toggleSupplier;
                const isActive = toggleSuppBtn.dataset.active === "true";
                const suppName = toggleSuppBtn.dataset.name || "nhà cung cấp này";
                const confirmText = isActive
                    ? `Bạn có chắc muốn ngừng hợp tác với nhà cung cấp "${suppName}"?`
                    : `Mở lại trạng thái hợp tác với nhà cung cấp "${suppName}"?`;
                if (!window.confirm(confirmText)) return;
                const action = isActive ? "deactivate" : "activate";
                try {
                    await request(`/api/suppliers/${id}/${action}`, { method: "POST" });
                    showMessage(isActive ? "Đã ngừng hợp tác nhà cung cấp." : "Đã kích hoạt lại nhà cung cấp.", "success");
                    await Promise.all([loadSuppliersCatalog(), loadSupplierDebts()]);
                } catch (err) {
                    showMessage(err.message, "error");
                }
            }
        });

        safeOn("refreshBtn", "click", async () => {
            await refreshBranchData();
            showMessage("Đã làm mới dữ liệu kho.", "success");
        });

        const nav = document.querySelector('nav.tabs[aria-label="Chức năng quản lý kho"]');
        const initialTab = nav?.querySelector('.tab.active') || nav?.querySelector('.tab');
        if (initialTab && initialTab.dataset.tab) {
            switchTab(initialTab.dataset.tab);
        }

        loadBranches();
    });
})();
