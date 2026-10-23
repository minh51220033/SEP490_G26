(() => {
    "use strict";

    const $ = (id) => document.getElementById(id);

    const REGEX = {
        BATCH_NUMBER: /^[A-Za-z0-9._/-]{2,50}$/,
        INVOICE_NO: /^[A-Za-z0-9._/-]{1,50}$/,
        MATERIAL_CODE: /^[A-Za-z0-9_-]{3,20}$/,
        SUPPLIER_CODE: /^[A-Za-z0-9_-]{2,20}$/,
        TAX_CODE: /^[0-9]{10}(-[0-9]{3})?$/,
        PHONE: /^(0|\+84)[0-9]{9,10}$/,
        REFERENCE_NO: /^[A-Za-z0-9._/-]{1,80}$/
    };

    const state = {
        branchId: null,
        branchSelectionAllowed: false,
        allActiveBranches: [],
        activeMaterials: [],
        activeSuppliers: [],
        availableBatches: [],

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

    function requireBranch() {
        if (!state.branchId) {
            showMessage("Không có chi nhánh khả dụng hoặc bạn chưa được phân công chi nhánh.", "warning");
            return false;
        }
        return true;
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
            // Fallback: truy vấn từng nguồn độc lập bằng Promise.allSettled (với size <= 100 hợp lệ)
            const [invRes, lowRes, availBatchesRes, debtsRes] = await Promise.allSettled([
                request(`/api/inventory?branchId=${state.branchId}&page=0&size=1`),
                request(`/api/inventory?branchId=${state.branchId}&lowStock=true&page=0&size=1`),
                request(`/api/inventory/batches/available?branchId=${state.branchId}`),
                request(`/api/inventory/supplier-payments/debts?branchId=${state.branchId}`)
            ]);

            const updateCardState = (cardId, ok) => {
                const el = $(cardId);
                if (!el) return;
                el.classList.remove("is-loading");
                el.classList.toggle("is-error", !ok);
            };

            if (invRes.status === "fulfilled") {
                updateCardState("kpiCardTotalItems", true);
                const total = Number(invRes.value?.totalElements ?? 0);
                $("statTotalItems").textContent = total.toLocaleString("vi-VN");
                $("statTotalItemsSub").textContent = "Tổng danh mục vật tư tại chi nhánh";
            } else {
                updateCardState("kpiCardTotalItems", false);
                $("statTotalItems").textContent = "—";
                $("statTotalItemsSub").textContent = "Không tải được s��� liệu tồn kho";
            }

            if (lowRes.status === "fulfilled") {
                updateCardState("kpiCardLowStock", true);
                const low = Number(lowRes.value?.totalElements ?? 0);
                $("statLowStock").textContent = low.toLocaleString("vi-VN");
                $("statLowStockSub").textContent = low > 0 ? "Chạm hoặc dưới ngưỡng tối thiểu" : "Tất cả mặt hàng đều đủ định mức";
            } else {
                updateCardState("kpiCardLowStock", false);
                $("statLowStock").textContent = "—";
                $("statLowStockSub").textContent = "Không tải được cảnh báo tồn";
            }

            if (availBatchesRes.status === "fulfilled") {
                updateCardState("kpiCardExpiring", true);
                const batchRows = Array.isArray(availBatchesRes.value) ? availBatchesRes.value : [];
                const expired = batchRows.filter(b => Number(b.quantityRemaining || 0) > 0 && b.expiryWarning === "EXPIRED").length;
                const soon = batchRows.filter(b => Number(b.quantityRemaining || 0) > 0 && b.expiryWarning === "EXPIRING_SOON").length;
                const expCount = expired + soon;
                $("statExpiringBatches").textContent = expCount.toLocaleString("vi-VN");
                $("statExpiringBatchesSub").textContent = expCount > 0
                    ? `${expired} lô quá hạn · ${soon} lô ≤ 30 ngày`
                    : "Không có lô hàng tồn sắp/đã hết hạn";
            } else {
                updateCardState("kpiCardExpiring", false);
                $("statExpiringBatches").textContent = "—";
                $("statExpiringBatchesSub").textContent = "Không tải được dữ liệu lô hàng";
            }

            if (debtsRes.status === "fulfilled") {
                updateCardState("kpiCardDebt", true);
                const debtRows = Array.isArray(debtsRes.value) ? debtsRes.value : [];
                const totalDebt = debtRows.reduce((acc, d) => acc + Number(d.remainingDebt || 0), 0);
                const suppliersWithDebt = debtRows.filter(d => Number(d.remainingDebt || 0) > 0).length;
                $("statTotalDebt").textContent = money(totalDebt);
                $("statTotalDebtSub").textContent = totalDebt > 0
                    ? `Đang nợ ${suppliersWithDebt} nhà cung cấp`
                    : "Không có công nợ tồn đọng";
            } else {
                updateCardState("kpiCardDebt", false);
                $("statTotalDebt").textContent = "—";
                $("statTotalDebtSub").textContent = "Không tải được công nợ NCC";
            }
        }
    }

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

            if (state.branchSelectionAllowed) {
                select.disabled = false;
                $("fixedBranch").hidden = true;
                select.value = String(branches[0].branchId);
            } else {
                select.disabled = true;
                $("fixedBranch").hidden = false;
                $("fixedBranchName").textContent = branches.length === 1
                    ? branches[0].branchName
                    : `${branches[0].branchName} (chi nhánh được phân công)`;
                select.value = String(branches[0].branchId);
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
            refreshMaterialSelectsInForms();
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

    async function loadInventory(page = state.inventoryPage) {
        if (!requireBranch()) return;
        state.inventoryPage = page;
        $("inventoryTable").innerHTML = '<tr><td colspan="8" class="empty">Đang tải dữ liệu...</td></tr>';
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
            const data = await request(`/api/inventory?${params}`);
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.inventoryTotalPages = Number(data?.totalPages || 0);
            $("inventoryTable").innerHTML = rows.length ? rows.map(item => {
                const current = Number(item.currentStock ?? 0);
                const threshold = Number(item.minStockThreshold ?? 0);
                const outOfStock = current <= 0;
                const low = current <= threshold;
                const badgeClass = outOfStock ? "status-danger" : (low ? "status-warning" : "status-good");
                const badgeLabel = outOfStock ? "Hết hàng (0)" : (low ? "Sắp hết" : "Đủ hàng");
                return `<tr>
                    <td class="code">${escapeHtml(item.materialCode)}</td>
                    <td><strong>${escapeHtml(item.materialName)}</strong></td>
                    <td>${escapeHtml(item.category)}</td>
                    <td>${escapeHtml(item.unit)}</td>
                    <td class="${low ? "stock-low" : "stock-good"}">${escapeHtml(fmtQty(item.currentStock))}</td>
                    <td>${escapeHtml(fmtQty(item.minStockThreshold))}</td>
                    <td>${escapeHtml(formatDateTime(item.lastUpdated))}</td>
                    <td><span class="status ${badgeClass}">${badgeLabel}</span></td>
                </tr>`;
            }).join("") : '<tr><td colspan="8" class="empty">Không có dữ liệu tồn kho phù hợp.</td></tr>';
            $("inventoryPage").textContent = `Trang ${state.inventoryTotalPages ? page + 1 : 0} / ${state.inventoryTotalPages}`;
            $("inventoryPrev").disabled = page <= 0;
            $("inventoryNext").disabled = page + 1 >= state.inventoryTotalPages;
        } catch (error) {
            $("inventoryTable").innerHTML = `<tr><td colspan="8" class="empty">${escapeHtml(error.message)}</td></tr>`;
            showMessage(error.message, "error");
        }
    }

    // =========================================================================
    // 2. TAB LÔ & HẠN SỬ DỤNG
    // =========================================================================

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
            const data = await request(`/api/inventory/batches?${params}`);
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
            $("batchesPage").textContent = `Trang ${state.batchesTotalPages ? page + 1 : 0} / ${state.batchesTotalPages}`;
            $("batchesPrev").disabled = page <= 0;
            $("batchesNext").disabled = page + 1 >= state.batchesTotalPages;
        } catch (error) {
            $("batchesTable").innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
            showMessage(error.message, "error");
        }
    }

    // =========================================================================
    // 3. TAB PHIẾU NHẬP KHO (FORM + LỊCH SỬ + CHI TIẾT)
    // =========================================================================

    function buildMaterialOptionsHtml(selectedId = "") {
        if (!state.activeMaterials.length) {
            return '<option value="">-- Chưa có vật tư (Hãy thêm ở Tab 6) --</option>';
        }
        return '<option value="">-- Chọn vật tư --</option>' +
            state.activeMaterials.map(m => {
                const sel = String(m.materialId) === String(selectedId) ? "selected" : "";
                return `<option value="${escapeHtml(m.materialId)}" data-unit="${escapeHtml(m.unit)}" ${sel}>${escapeHtml(m.materialCode)} - ${escapeHtml(m.materialName)} (${escapeHtml(m.unit)})</option>`;
            }).join("");
    }

    function refreshMaterialSelectsInForms() {
        document.querySelectorAll(".receipt-item-row .material-id").forEach(sel => {
            const currentVal = sel.value;
            sel.innerHTML = buildMaterialOptionsHtml(currentVal);
        });
        document.querySelectorAll(".issue-item-row .issue-material-id, .transfer-item-row .transfer-material-id").forEach(sel => {
            const currentVal = sel.value;
            sel.innerHTML = buildMaterialOptionsHtml(currentVal);
        });
    }

    function addReceiptRow() {
        const tr = document.createElement("tr");
        tr.className = "receipt-item-row";
        tr.innerHTML = `
            <td>
                <select class="material-id" required aria-label="Chọn vật tư">
                    ${buildMaterialOptionsHtml()}
                </select>
            </td>
            <td class="mat-unit-cell">—</td>
            <td><input class="batch-number" maxlength="50" pattern="^[A-Za-z0-9._/-]{2,50}$" title="Số lô từ 2-50 ký tự (chữ, số, -, _, ., /)" required placeholder="VD: LO-2026-01" aria-label="Số lô"></td>
            <td><input class="expiry-date" type="date" required aria-label="Hạn sử dụng"></td>
            <td><input class="quantity" type="number" min="1" step="1" inputmode="numeric" required value="1" aria-label="Số lượng"></td>
            <td><input class="unit-price" type="number" min="0" step="1" inputmode="numeric" required value="0" aria-label="Đơn giá"></td>
            <td class="line-total">0 ₫</td>
            <td><button class="remove-row" type="button" title="Xóa dòng" aria-label="Xóa dòng">×</button></td>`;
        $("receiptItems").appendChild(tr);

        const matSelect = tr.querySelector(".material-id");
        matSelect.addEventListener("change", () => {
            const opt = matSelect.options[matSelect.selectedIndex];
            tr.querySelector(".mat-unit-cell").textContent = opt?.dataset?.unit || "—";
        });

        tr.querySelectorAll("input").forEach(input => input.addEventListener("input", calculateReceiptTotal));
        tr.querySelector(".remove-row").addEventListener("click", () => {
            const allRows = document.querySelectorAll(".receipt-item-row");
            if (allRows.length > 1) {
                tr.remove();
            } else {
                matSelect.value = "";
                tr.querySelector(".mat-unit-cell").textContent = "—";
                tr.querySelector(".batch-number").value = "";
                tr.querySelector(".expiry-date").value = "";
                tr.querySelector(".quantity").value = "1";
                tr.querySelector(".unit-price").value = "0";
                showMessage("Phiếu nhập cần ít nhất 1 dòng vật tư. Đã làm mới dòng hiện tại.", "info");
            }
            calculateReceiptTotal();
        });
        calculateReceiptTotal();
    }

    function resetReceiptForm() {
        $("receiptForm").reset();
        $("receiptDate").value = new Date().toISOString().slice(0, 10);
        $("receiptItems").innerHTML = "";
        if ($("receiptResult")) $("receiptResult").hidden = true;
        addReceiptRow();
        calculateReceiptTotal();
    }

    function calculateReceiptTotal() {
        let total = 0;
        document.querySelectorAll(".receipt-item-row").forEach(row => {
            const quantity = Number(row.querySelector(".quantity").value || 0);
            const price = Number(row.querySelector(".unit-price").value || 0);
            const line = quantity * price;
            total += line;
            row.querySelector(".line-total").textContent = money(line);
        });
        $("receiptTotal").textContent = money(total);
    }

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

        const rows = [...document.querySelectorAll(".receipt-item-row")];
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

        const payload = {
            branchId: Number(state.branchId),
            supplierId: Number($("supplierId").value),
            supplierInvoiceNo: invoiceNo || null,
            receiptDate: $("receiptDate").value,
            notes: $("receiptNotes").value.trim() || null,
            attachmentUrl: $("attachmentUrl").value.trim() || null,
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
            result.innerHTML = `<strong>✓ Nhập kho thành công</strong> — Mã phiếu: <strong>${escapeHtml(saved.receiptCode)}</strong> · Tổng tiền: <strong>${money(saved.totalAmount)}</strong>`;
            result.hidden = false;
            showMessage(`Đã tạo phiếu nhập ${saved.receiptCode}. Tồn kho và lô đã được cập nhật.`, "success");
            await Promise.all([refreshBranchData(), loadReceiptHistory(0)]);
        } catch (error) {
            showMessage(error.message || "Không thể tạo phiếu nhập.", "error");
        } finally {
            button.disabled = false;
            button.textContent = "✓ Xác nhận & Lưu phiếu nhập";
        }
    }

    async function loadReceiptHistory(page = state.receiptsPage) {
        if (!requireBranch()) return;
        state.receiptsPage = page;
        $("receiptHistoryTable").innerHTML = '<tr><td colspan="9" class="empty">Đang tải lịch sử phiếu nhập...</td></tr>';

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
            const data = await request(`/api/inventory/receipts?${params}`);
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.receiptsTotalPages = Number(data?.totalPages || 0);

            $("receiptHistoryTable").innerHTML = rows.length ? rows.map(r => {
                const debt = Number(r.remainingDebt || 0);
                return `<tr>
                    <td class="code">${escapeHtml(r.receiptCode)}</td>
                    <td>${escapeHtml(r.receiptDate)}</td>
                    <td><strong>${escapeHtml(r.supplierName || "—")}</strong></td>
                    <td>${escapeHtml(r.supplierInvoiceNo || "—")}</td>
                    <td>${escapeHtml(r.receiverName || "—")}</td>
                    <td><strong>${money(r.totalAmount)}</strong></td>
                    <td class="stock-good">${money(r.paidAmount)}</td>
                    <td class="${debt > 0 ? "stock-low" : ""}">${money(debt)}</td>
                    <td>
                        <div class="row-actions">
                            <button type="button" class="button button-secondary button-sm" data-view-receipt="${r.receiptId}">Xem chi tiết</button>
                        </div>
                    </td>
                </tr>`;
            }).join("") : '<tr><td colspan="9" class="empty">Chưa có phiếu nhập kho phù hợp.</td></tr>';

            $("receiptHistoryPage").textContent = `Trang ${state.receiptsTotalPages ? page + 1 : 0} / ${state.receiptsTotalPages}`;
            $("receiptHistoryPrev").disabled = page <= 0;
            $("receiptHistoryNext").disabled = page + 1 >= state.receiptsTotalPages;
        } catch (error) {
            $("receiptHistoryTable").innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

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

            $("detailModalBody").innerHTML = `
                <div class="detail-meta-grid">
                    <div class="detail-meta-item"><span>Mã phiếu nhập</span><strong>${escapeHtml(r.receiptCode)}</strong></div>
                    <div class="detail-meta-item"><span>Ngày nhập</span><strong>${escapeHtml(r.receiptDate)}</strong></div>
                    <div class="detail-meta-item"><span>Kho chi nhánh</span><strong>${escapeHtml(r.branchName)}</strong></div>
                    <div class="detail-meta-item"><span>Nhà cung cấp</span><strong>${escapeHtml(r.supplierCode)} - ${escapeHtml(r.supplierName)}</strong></div>
                    <div class="detail-meta-item"><span>Số hóa đơn NCC</span><strong>${escapeHtml(r.supplierInvoiceNo || "—")}</strong></div>
                    <div class="detail-meta-item"><span>Người nhận kho</span><strong>${escapeHtml(r.receiverName || "—")}</strong></div>
                    <div class="detail-meta-item"><span>Tổng tiền phiếu</span><strong>${money(r.totalAmount)}</strong></div>
                    <div class="detail-meta-item"><span>Đã thanh toán</span><strong class="stock-good">${money(r.paidAmount)}</strong></div>
                    <div class="detail-meta-item"><span>Còn nợ</span><strong class="stock-low">${money(r.remainingDebt)}</strong></div>
                </div>
                ${r.notes ? `<p><strong>Ghi chú:</strong> ${escapeHtml(r.notes)}</p>` : ""}
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr><th>#</th><th>Mã VT</th><th>Tên vật tư</th><th>ĐVT</th><th>Số lô</th><th>Hạn dùng</th><th>SL</th><th>Đơn giá</th><th>Thành tiền</th></tr>
                        </thead>
                        <tbody>${itemsHtml || '<tr><td colspan="9" class="empty">Không có dòng chi tiết</td></tr>'}</tbody>
                    </table>
                </div>
            `;
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

    function addIssueOrTransferRow(containerId, isTransfer = false) {
        const tbody = $(containerId);
        if (!tbody) return;
        const tr = document.createElement("tr");
        tr.className = isTransfer ? "transfer-item-row" : "issue-item-row";
        const matClass = isTransfer ? "transfer-material-id" : "issue-material-id";

        tr.innerHTML = `
            <td>
                <select class="${matClass}" required aria-label="Chọn vật tư">
                    ${buildMaterialOptionsHtml()}
                </select>
            </td>
            <td>
                <select class="batch-id" aria-label="Chọn lô xuất">
                    <option value="">-- Tự động theo FEFO (Hạn gần nhất trước) --</option>
                </select>
            </td>
            <td class="avail-stock">0</td>
            <td><input class="quantity" type="number" min="1" step="1" inputmode="numeric" required value="1" aria-label="Số lượng"></td>
            <td><input class="item-notes" maxlength="255" placeholder="Ghi chú dòng (tùy chọn)"></td>
            <td><button class="remove-row" type="button" title="Xóa dòng">×</button></td>
        `;
        tbody.appendChild(tr);

        const matSelect = tr.querySelector(`.${matClass}`);
        const batchSelect = tr.querySelector(".batch-id");
        const availCell = tr.querySelector(".avail-stock");

        const updateRowAvailability = () => {
            const matId = Number(matSelect.value) || null;
            const allowExpired = !isTransfer && $("issueType")?.value === "DISCARD_EXPIRED";
            const currentBatch = batchSelect.value;
            batchSelect.innerHTML = buildBatchOptionsForMaterial(matId, allowExpired);
            if (currentBatch && [...batchSelect.options].some(o => o.value === currentBatch)) {
                batchSelect.value = currentBatch;
            }
            const avail = computeAvailableForMaterial(matId, batchSelect.value, allowExpired);
            availCell.textContent = fmtQty(avail);
            availCell.className = `avail-stock ${avail > 0 ? "stock-good" : "stock-low"}`;
        };

        matSelect.addEventListener("change", () => {
            batchSelect.value = "";
            updateRowAvailability();
        });
        batchSelect.addEventListener("change", updateRowAvailability);

        tr.querySelector(".remove-row").addEventListener("click", () => {
            if (tbody.querySelectorAll("tr").length > 1) {
                tr.remove();
            } else {
                matSelect.value = "";
                batchSelect.innerHTML = '<option value="">-- Tự động theo FEFO (Hạn gần nhất trước) --</option>';
                availCell.textContent = "0";
                availCell.className = "avail-stock";
                tr.querySelector(".quantity").value = "1";
                tr.querySelector(".item-notes").value = "";
                showMessage("Phiếu cần ít nhất 1 dòng vật tư. Đã làm mới dòng hiện tại.", "info");
            }
        });
    }

    function resetIssueForm() {
        $("issueForm").reset();
        $("issueDate").value = new Date().toISOString().slice(0, 10);
        $("issueItems").innerHTML = "";
        addIssueOrTransferRow("issueItems", false);
    }

    function resetTransferForm() {
        $("transferForm").reset();
        $("transferDate").value = new Date().toISOString().slice(0, 10);
        populateDestinationBranches();
        $("transferItems").innerHTML = "";
        addIssueOrTransferRow("transferItems", true);
    }

    function refreshIssueAndTransferRows() {
        document.querySelectorAll(".issue-item-row, .transfer-item-row").forEach(tr => {
            const matSelect = tr.querySelector("select");
            if (matSelect) matSelect.dispatchEvent(new Event("change"));
        });
    }

    async function submitIssue(event) {
        event.preventDefault();
        if (!requireBranch()) return;
        const button = $("submitIssue");
        const rows = [...document.querySelectorAll(".issue-item-row")];
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

        const payload = {
            branchId: Number(state.branchId),
            issueType: $("issueType").value,
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
            button.textContent = "✓ Xác nhận xuất kho";
        }
    }

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
            button.textContent = "⇄ Xác nhận điều chuyển";
        }
    }

    async function loadIssueHistory(page = state.issuesPage) {
        if (!requireBranch()) return;
        state.issuesPage = page;
        $("issueHistoryTable").innerHTML = '<tr><td colspan="9" class="empty">Đang tải lịch sử phiếu xuất...</td></tr>';

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
            const data = await request(`/api/inventory/issues?${params}`);
            const rows = Array.isArray(data?.content) ? data.content : [];
            state.issuesTotalPages = Number(data?.totalPages || 0);

            $("issueHistoryTable").innerHTML = rows.length ? rows.map(si => {
                const isDiscard = si.issueType === "DISCARD_EXPIRED";
                const typeLabel = isDiscard ? "Xuất hủy hết hạn" : "Xuất sử dụng nội bộ";
                const typeCls = isDiscard ? "status-danger" : "status-info";
                return `<tr>
                    <td class="code">${escapeHtml(si.issueCode)}</td>
                    <td>${escapeHtml(si.issueDate)}</td>
                    <td><span class="status ${typeCls}">${escapeHtml(typeLabel)}</span></td>
                    <td>${escapeHtml(si.issuerName || "—")}</td>
                    <td>${escapeHtml(si.itemCount)} dòng</td>
                    <td><strong>${escapeHtml(fmtQty(si.totalQuantity))}</strong></td>
                    <td>${money(si.totalEstimatedValue)}</td>
                    <td>${escapeHtml(si.notes || "—")}</td>
                    <td><button type="button" class="button button-secondary button-sm" data-view-issue="${si.issueId}">Xem chi tiết</button></td>
                </tr>`;
            }).join("") : '<tr><td colspan="9" class="empty">Chưa có phiếu xuất kho phù hợp.</td></tr>';

            $("issueHistoryPage").textContent = `Trang ${state.issuesTotalPages ? page + 1 : 0} / ${state.issuesTotalPages}`;
            $("issueHistoryPrev").disabled = page <= 0;
            $("issueHistoryNext").disabled = page + 1 >= state.issuesTotalPages;
        } catch (error) {
            $("issueHistoryTable").innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

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
            const data = await request(`/api/inventory/transfers?${params}`);
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
                    <td><button type="button" class="button button-secondary button-sm" data-view-issue="${si.issueId}">Xem chi tiết</button></td>
                </tr>`;
            }).join("") : '<tr><td colspan="9" class="empty">Chưa có giao dịch điều chuyển kho.</td></tr>';

            $("transferHistoryPage").textContent = `Trang ${state.transfersTotalPages ? page + 1 : 0} / ${state.transfersTotalPages}`;
            $("transferHistoryPrev").disabled = page <= 0;
            $("transferHistoryNext").disabled = page + 1 >= state.transfersTotalPages;
        } catch (error) {
            $("transferHistoryTable").innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

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
                    <div class="detail-meta-item"><span>Loại giao dịch</span><strong>${escapeHtml(si.issueType)}</strong></div>
                    <div class="detail-meta-item"><span>Chi nhánh nguồn</span><strong>${escapeHtml(si.branchName)}</strong></div>
                    <div class="detail-meta-item"><span>Chi nhánh đích</span><strong>${escapeHtml(si.destinationBranchName || "Nội bộ chi nhánh")}</strong></div>
                    <div class="detail-meta-item"><span>Người thực hiện</span><strong>${escapeHtml(si.issuerName || "—")}</strong></div>
                </div>
                ${si.notes ? `<p><strong>Ghi chú:</strong> ${escapeHtml(si.notes)}</p>` : ""}
                <div class="table-wrap">
                    <table>
                        <thead>
                            <tr><th>#</th><th>Mã VT</th><th>Tên vật tư</th><th>ĐVT</th><th>Số lô xuất</th><th>Hạn dùng</th><th>SL xuất</th><th>Giá vốn</th><th>Thành tiền vốn</th><th>Ghi chú</th></tr>
                        </thead>
                        <tbody>${itemsHtml || '<tr><td colspan="10" class="empty">Không có dòng chi tiết</td></tr>'}</tbody>
                    </table>
                </div>
            `;
            $("detailModal").hidden = false;
        } catch (error) {
            showMessage(error.message, "error");
        }
    }

    // =========================================================================
    // 6. TAB DANH MỤC VẬT TƯ & 7. TAB NHÓM VẬT TƯ
    // =========================================================================

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
                request(`/api/materials?${params}`),
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

            $("materialsPage").textContent = `Trang ${state.materialsTotalPages ? page + 1 : 0} / ${state.materialsTotalPages}`;
            $("materialsPrev").disabled = page <= 0;
            $("materialsNext").disabled = page + 1 >= state.materialsTotalPages;
        } catch (error) {
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
    // 8. TAB NHÀ CUNG CẤP & THANH TOÁN CÔNG NỢ NCC
    // =========================================================================

    async function loadSupplierDebts() {
        if (!requireBranch()) return;
        $("supplierDebtTable").innerHTML = '<tr><td colspan="10" class="empty">Đang tải dữ liệu công nợ...</td></tr>';
        const params = new URLSearchParams({ branchId: state.branchId });
        const keyword = $("keyword").value.trim();
        if (keyword) params.set("keyword", keyword);

        try {
            const list = await request(`/api/inventory/supplier-payments/debts?${params}`);
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
            $("supplierDebtTable").innerHTML = `<tr><td colspan="10" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

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
            const data = await request(`/api/inventory/supplier-payments?${params}`);
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

            $("paymentHistoryPage").textContent = `Trang ${state.paymentsTotalPages ? page + 1 : 0} / ${state.paymentsTotalPages}`;
            $("paymentHistoryPrev").disabled = page <= 0;
            $("paymentHistoryNext").disabled = page + 1 >= state.paymentsTotalPages;
        } catch (error) {
            $("supplierPaymentHistoryTable").innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
        }
    }

    async function openPaymentModal(preselectedSupplierId = "", suggestedAmount = "") {
        $("paymentForm").reset();
        $("payDate").value = new Date().toISOString().slice(0, 10);
        if (preselectedSupplierId) {
            $("paySupplierId").value = String(preselectedSupplierId);
            await loadUnpaidReceiptsForPaymentModal(preselectedSupplierId);
        } else {
            $("payReceiptId").innerHTML = '<option value="">-- Thanh toán công nợ chung --</option>';
        }
        if (suggestedAmount && Number(suggestedAmount) > 0) {
            $("payAmount").value = Number(suggestedAmount);
        }
        $("paymentModal").hidden = false;
    }

    async function loadUnpaidReceiptsForPaymentModal(supplierId) {
        const receiptSelect = $("payReceiptId");
        receiptSelect.innerHTML = '<option value="">-- Thanh toán công nợ chung --</option>';
        if (!supplierId || !state.branchId) return;
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

    async function submitPaymentForm(event) {
        event.preventDefault();
        if (!requireBranch()) return;
        const btn = $("savePaymentBtn");
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
        const payload = {
            branchId: Number(state.branchId),
            supplierId: Number($("paySupplierId").value),
            receiptId: $("payReceiptId").value ? Number($("payReceiptId").value) : null,
            paymentDate: $("payDate").value,
            paymentMethod: $("payMethod").value,
            amount,
            referenceNo: refNo || null,
            notes: $("payNotes").value.trim() || null
        };
        btn.disabled = true;
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
        }
    }

    function openQuickSupplierModal(supplier = null) {
        $("quickSupplierForm").reset();
        if ($("qsEditSupplierId")) $("qsEditSupplierId").value = supplier?.supplierId || "";
        if ($("quickSupplierModalTitle")) {
            $("quickSupplierModalTitle").textContent = supplier ? "Chỉnh sửa nhà cung cấp" : "Thêm nhanh nhà cung cấp";
        }
        $("qsCode").value = supplier?.supplierCode || "";
        $("qsName").value = supplier?.supplierName || "";
        $("qsTaxCode").value = supplier?.taxCode || "";
        $("qsPhone").value = supplier?.phoneNumber || "";
        $("qsAddress").value = supplier?.address || "";
        $("quickSupplierModal").hidden = false;
    }

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
        if (!REGEX.TAX_CODE.test(payload.taxCode)) {
            showMessage("Mã số thuế phải gồm 10 chữ số hoặc định dạng 10 số - 3 số (VD: 0101234567 hoặc 0101234567-001).", "warning");
            $("qsTaxCode").focus();
            return;
        }
        if (!REGEX.PHONE.test(payload.phoneNumber)) {
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

        if (tab === "receipt") loadReceiptHistory(0);
        if (tab === "issue") loadIssueHistory(0);
        if (tab === "transfer") loadTransferHistory(0);
        if (tab === "materials") loadMaterialsTable(0);
        if (tab === "categories") loadCategoriesTable();
        if (tab === "supplier-payments") {
            loadSupplierDebts();
            loadPaymentHistory(0);
        }
    }

    function handleKpiCardActivate(target) {
        if (target === "inventory-all") {
            $("lowStock").checked = false;
            switchTab("inventory");
            loadInventory(0);
        } else if (target === "inventory-low") {
            $("lowStock").checked = true;
            switchTab("inventory");
            loadInventory(0);
        } else if (target === "batches-expiring") {
            const limitDate = new Date();
            limitDate.setDate(limitDate.getDate() + 30);
            $("expiryFrom").value = "";
            $("expiryTo").value = limitDate.toISOString().slice(0, 10);
            switchTab("batches");
            loadBatches(0);
        } else if (target === "supplier-debt") {
            switchTab("supplier-payments");
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        const todayStr = new Date().toISOString().slice(0, 10);
        if ($("receiptDate")) $("receiptDate").value = todayStr;
        if ($("issueDate")) $("issueDate").value = todayStr;
        if ($("transferDate")) $("transferDate").value = todayStr;
        if ($("payDate")) $("payDate").value = todayStr;

        addReceiptRow();
        addIssueOrTransferRow("issueItems", false);
        addIssueOrTransferRow("transferItems", true);

        document.querySelectorAll(".tab").forEach(button =>
            button.addEventListener("click", () => switchTab(button.dataset.tab))
        );

        // KPI Cards click & keyboard navigation
        document.querySelectorAll("[data-kpi-target]").forEach(card => {
            card.addEventListener("click", () => handleKpiCardActivate(card.dataset.kpiTarget));
            card.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    handleKpiCardActivate(card.dataset.kpiTarget);
                }
            });
        });

        $("branchId").addEventListener("change", async () => {
            state.branchId = Number($("branchId").value) || null;
            populateDestinationBranches();
            await refreshBranchData();
        });

        $("searchBtn").addEventListener("click", () => {
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
        });

        if ($("clearSearchBtn")) {
            $("clearSearchBtn").addEventListener("click", () => {
                $("keyword").value = "";
                $("lowStock").checked = false;
                $("searchBtn").click();
            });
        }

        $("keyword").addEventListener("keydown", e => {
            if (e.key === "Enter") { e.preventDefault(); $("searchBtn").click(); }
        });

        $("lowStock").addEventListener("change", () => loadInventory(0));
        $("filterBatchesBtn").addEventListener("click", () => loadBatches(0));
        if ($("clearBatchesFilterBtn")) {
            $("clearBatchesFilterBtn").addEventListener("click", () => {
                $("expiryFrom").value = "";
                $("expiryTo").value = "";
                loadBatches(0);
            });
        }

        $("filterReceiptsBtn").addEventListener("click", () => loadReceiptHistory(0));
        $("filterIssuesBtn").addEventListener("click", () => loadIssueHistory(0));
        $("filterTransfersBtn").addEventListener("click", () => loadTransferHistory(0));
        $("filterPaymentsBtn").addEventListener("click", () => loadPaymentHistory(0));

        $("issueType").addEventListener("change", refreshIssueAndTransferRows);
        $("matCategoryFilter").addEventListener("change", () => loadMaterialsTable(0));
        $("matActiveFilter").addEventListener("change", () => loadMaterialsTable(0));

        // Pagination buttons
        $("inventoryPrev").addEventListener("click", () => loadInventory(Math.max(0, state.inventoryPage - 1)));
        $("inventoryNext").addEventListener("click", () => loadInventory(state.inventoryPage + 1));
        $("batchesPrev").addEventListener("click", () => loadBatches(Math.max(0, state.batchesPage - 1)));
        $("batchesNext").addEventListener("click", () => loadBatches(state.batchesPage + 1));
        $("receiptHistoryPrev").addEventListener("click", () => loadReceiptHistory(Math.max(0, state.receiptsPage - 1)));
        $("receiptHistoryNext").addEventListener("click", () => loadReceiptHistory(state.receiptsPage + 1));
        $("issueHistoryPrev").addEventListener("click", () => loadIssueHistory(Math.max(0, state.issuesPage - 1)));
        $("issueHistoryNext").addEventListener("click", () => loadIssueHistory(state.issuesPage + 1));
        $("transferHistoryPrev").addEventListener("click", () => loadTransferHistory(Math.max(0, state.transfersPage - 1)));
        $("transferHistoryNext").addEventListener("click", () => loadTransferHistory(state.transfersPage + 1));
        $("materialsPrev").addEventListener("click", () => loadMaterialsTable(Math.max(0, state.materialsPage - 1)));
        $("materialsNext").addEventListener("click", () => loadMaterialsTable(state.materialsPage + 1));
        $("paymentHistoryPrev").addEventListener("click", () => loadPaymentHistory(Math.max(0, state.paymentsPage - 1)));
        $("paymentHistoryNext").addEventListener("click", () => loadPaymentHistory(state.paymentsPage + 1));

        // Add row, Reset & Form submits
        $("addReceiptItem").addEventListener("click", addReceiptRow);
        $("addIssueItem").addEventListener("click", () => addIssueOrTransferRow("issueItems", false));
        $("addTransferItem").addEventListener("click", () => addIssueOrTransferRow("transferItems", true));

        if ($("resetReceiptBtn")) $("resetReceiptBtn").addEventListener("click", resetReceiptForm);
        if ($("resetIssueBtn")) $("resetIssueBtn").addEventListener("click", resetIssueForm);
        if ($("resetTransferBtn")) $("resetTransferBtn").addEventListener("click", resetTransferForm);

        $("receiptForm").addEventListener("submit", submitReceipt);
        $("issueForm").addEventListener("submit", submitIssue);
        $("transferForm").addEventListener("submit", submitTransfer);
        $("materialForm").addEventListener("submit", submitMaterialForm);
        $("paymentForm").addEventListener("submit", submitPaymentForm);
        $("quickSupplierForm").addEventListener("submit", submitQuickSupplierForm);

        // Modals open/close
        $("openAddMaterialBtn").addEventListener("click", () => openMaterialModal());
        $("addMaterialFromCategoryBtn").addEventListener("click", () => openMaterialModal());
        $("closeMaterialModalBtn").addEventListener("click", () => { $("materialModal").hidden = true; });
        $("cancelMaterialModalBtn").addEventListener("click", () => { $("materialModal").hidden = true; });

        $("openPaymentModalBtn").addEventListener("click", () => openPaymentModal());
        $("closePaymentModalBtn").addEventListener("click", () => { $("paymentModal").hidden = true; });
        $("cancelPaymentModalBtn").addEventListener("click", () => { $("paymentModal").hidden = true; });

        $("paySupplierId").addEventListener("change", (e) => loadUnpaidReceiptsForPaymentModal(e.target.value));
        $("payReceiptId").addEventListener("change", (e) => {
            const opt = e.target.options[e.target.selectedIndex];
            if (opt?.dataset?.debt) {
                $("payAmount").value = Number(opt.dataset.debt);
            }
        });

        $("openQuickSupplierBtn").addEventListener("click", () => openQuickSupplierModal());
        $("closeQuickSupplierBtn").addEventListener("click", () => { $("quickSupplierModal").hidden = true; });
        $("cancelQuickSupplierBtn").addEventListener("click", () => { $("quickSupplierModal").hidden = true; });

        $("closeDetailModalBtn").addEventListener("click", () => { $("detailModal").hidden = true; });
        $("closeDetailModalFooterBtn").addEventListener("click", () => { $("detailModal").hidden = true; });

        // Close modals on backdrop click or Escape key
        ["materialModal", "paymentModal", "quickSupplierModal", "detailModal"].forEach(modalId => {
            const modalEl = $(modalId);
            if (modalEl) {
                modalEl.addEventListener("click", (e) => {
                    if (e.target === modalEl) modalEl.hidden = true;
                });
            }
        });

        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape") {
                ["materialModal", "paymentModal", "quickSupplierModal", "detailModal"].forEach(modalId => {
                    const modalEl = $(modalId);
                    if (modalEl && !modalEl.hidden) modalEl.hidden = true;
                });
            }
        });

        // Delegated table actions
        document.body.addEventListener("click", async (e) => {
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

        $("refreshBtn").addEventListener("click", async () => {
            await refreshBranchData();
            showMessage("Đã làm mới dữ liệu kho.", "success");
        });

        loadBranches();
    });
})();
