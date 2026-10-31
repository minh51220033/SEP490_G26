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
        REFERENCE_NO: /^[A-Za-z0-9._/-]{1,100}$/
    };

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
        paymentsTotalPages: 0,

        // Quản lý ngữ cảnh bảng ký điện tử (receipt-draft, issue-draft, transfer-draft, receipt-existing, issue-existing)
        signContext: null,
        signTargetId: null,
        hasSignatureStrokes: false
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
            const data = await request(`/api/inventory?${params}`, { signal: getAbortSignal("inventory") });
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
            if (error.name === "AbortError") return;
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
            $("batchesPage").textContent = `Trang ${state.batchesTotalPages ? page + 1 : 0} / ${state.batchesTotalPages}`;
            $("batchesPrev").disabled = page <= 0;
            $("batchesNext").disabled = page + 1 >= state.batchesTotalPages;
        } catch (error) {
            if (error.name === "AbortError") return;
            $("batchesTable").innerHTML = `<tr><td colspan="9" class="empty">${escapeHtml(error.message)}</td></tr>`;
            showMessage(error.message, "error");
        }
    }

    // =========================================================================
    // 3. TAB PHIẾU NHẬP KHO (UPLOAD HÓA ĐƠN, THANH TOÁN, KÝ ĐIỆN TỬ, LỊCH SỬ, PDF)
    // =========================================================================

    function buildMaterialOptionsHtml(selectedId = "", customList = null) {
        const sourceList = Array.isArray(customList) ? customList : state.activeMaterials;
        if (!sourceList.length && !selectedId) {
            return '<option value="">-- Không tìm thấy vật tư phù hợp --</option>';
        }
        // Luôn bảo toàn vật tư đang được chọn ở dòng hiện tại ngay cả khi từ khóa tìm kiếm mới không chứa nó
        const merged = [...sourceList];
        if (selectedId && !merged.some(m => String(m.materialId) === String(selectedId))) {
            const existing = state.activeMaterials.find(m => String(m.materialId) === String(selectedId));
            if (existing) merged.unshift(existing);
        }
        return '<option value="">-- Chọn vật tư --</option>' +
            merged.map(m => {
                const sel = String(m.materialId) === String(selectedId) ? "selected" : "";
                return `<option value="${escapeHtml(m.materialId)}" data-unit="${escapeHtml(m.unit)}" ${sel}>${escapeHtml(m.materialCode)} - ${escapeHtml(m.materialName)} (${escapeHtml(m.unit)})</option>`;
            }).join("");
    }

    function bindSearchableMaterialSelect(searchInput, selectEl) {
        if (!searchInput || !selectEl) return;
        const performSearch = debounce(async () => {
            const kw = searchInput.value.trim();
            const currentVal = selectEl.value;
            if (!kw) {
                selectEl.innerHTML = buildMaterialOptionsHtml(currentVal, state.activeMaterials);
                return;
            }
            try {
                const res = await request(`/api/materials?keyword=${encodeURIComponent(kw)}&active=true&page=0&size=30`);
                const items = Array.isArray(res?.content) ? res.content : [];
                items.forEach(item => {
                    if (!state.activeMaterials.some(m => m.materialId === item.materialId)) {
                        state.activeMaterials.push(item);
                    }
                });
                selectEl.innerHTML = buildMaterialOptionsHtml(currentVal, items);
            } catch {
                const filtered = state.activeMaterials.filter(m =>
                    `${m.materialCode} ${m.materialName}`.toLowerCase().includes(kw.toLowerCase())
                );
                selectEl.innerHTML = buildMaterialOptionsHtml(currentVal, filtered);
            }
        }, 300);

        searchInput.addEventListener("input", performSearch);
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
                $("invoicePreviewStatus").textContent = "✓ Đã tải lên hợp lệ";
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

    function addReceiptRow() {
        const tr = document.createElement("tr");
        tr.className = "receipt-item-row";
        tr.innerHTML = `
            <td>
                <div class="mat-picker-wrap">
                    <input type="search" class="mat-search-input" placeholder="Gõ mã / tên để lọc nhanh vật tư..." aria-label="Tìm vật tư">
                    <select class="material-id" required aria-label="Chọn vật tư">
                        ${buildMaterialOptionsHtml()}
                    </select>
                </div>
            </td>
            <td class="mat-unit-cell">—</td>
            <td><input class="batch-number" maxlength="50" pattern="^[A-Za-z0-9._/-]{2,50}$" title="Số lô từ 2-50 ký tự (chữ, số, -, _, ., /)" required placeholder="VD: LO-2026-01" aria-label="Số lô"></td>
            <td><input class="expiry-date" type="date" required aria-label="Hạn sử dụng"></td>
            <td><input class="quantity" type="number" min="1" step="1" inputmode="numeric" required value="1" aria-label="Số lượng"></td>
            <td><input class="unit-price" type="number" min="0" step="1" inputmode="numeric" required value="0" aria-label="Đơn giá"></td>
            <td class="line-total">0 ₫</td>
            <td><button class="remove-row" type="button" title="Xóa dòng" aria-label="Xóa dòng">×</button></td>`;
        $("receiptItems").appendChild(tr);

        const searchInput = tr.querySelector(".mat-search-input");
        const matSelect = tr.querySelector(".material-id");
        bindSearchableMaterialSelect(searchInput, matSelect);

        matSelect.addEventListener("change", () => {
            const opt = matSelect.options[matSelect.selectedIndex];
            tr.querySelector(".mat-unit-cell").textContent = opt?.dataset?.unit || "—";
        });

        tr.querySelectorAll(".quantity, .unit-price").forEach(input => input.addEventListener("input", calculateReceiptTotal));
        tr.querySelector(".remove-row").addEventListener("click", () => {
            const allRows = document.querySelectorAll(".receipt-item-row");
            if (allRows.length > 1) {
                tr.remove();
            } else {
                searchInput.value = "";
                matSelect.innerHTML = buildMaterialOptionsHtml("");
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
        clearUploadedInvoice();
        clearDraftSignature("receipt");
        if ($("receiptPaidAmount")) $("receiptPaidAmount").value = "0";
        if ($("receiptPaymentMethod")) $("receiptPaymentMethod").value = "BANK_TRANSFER";
        if ($("receiptPaymentRefNo")) $("receiptPaymentRefNo").value = "";
        if ($("receiptResult")) $("receiptResult").hidden = true;
        addReceiptRow();
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
            signatureData: $("receiptSignatureData")?.value || null,
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
                        <strong>✓ Nhập kho thành công</strong> — Mã phiếu: <strong>${escapeHtml(saved.receiptCode)}</strong>
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

    function renderSignatureCell(signatureUrl, signedByName, recordId, type) {
        if (signatureUrl) {
            return `<span class="status status-good" title="Người ký: ${escapeHtml(signedByName || "")}">Đã ký (${escapeHtml(signedByName || "NV")})</span>`;
        }
        const attr = type === "receipt" ? `data-sign-receipt="${recordId}"` : `data-sign-issue="${recordId}"`;
        return `<button type="button" class="button button-secondary button-sm" ${attr}>Ký điện tử</button>`;
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
                    <td>${renderSignatureCell(r.signatureUrl, r.signedByName, r.receiptId, "receipt")}</td>
                    <td>
                        <div class="row-actions">
                            <button type="button" class="button button-secondary button-sm" data-view-receipt="${r.receiptId}">Chi tiết</button>
                            <a href="/api/inventory/receipts/${r.receiptId}/pdf?download=true" class="button button-secondary button-sm">PDF</a>
                            <button type="button" class="button button-secondary button-sm" data-print-url="/inventory/receipts/${r.receiptId}/print">In A4</button>
                        </div>
                    </td>
                </tr>`;
            }).join("") : '<tr><td colspan="9" class="empty">Chưa có phiếu nhập kho phù hợp.</td></tr>';

            $("receiptHistoryPage").textContent = `Trang ${state.receiptsTotalPages ? page + 1 : 0} / ${state.receiptsTotalPages}`;
            $("receiptHistoryPrev").disabled = page <= 0;
            $("receiptHistoryNext").disabled = page + 1 >= state.receiptsTotalPages;
        } catch (error) {
            if (error.name === "AbortError") return;
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

            const signatureBlock = r.signatureUrl
                ? `<div style="display:flex; align-items:center; gap:14px;">
                       <img src="${escapeHtml(r.signatureUrl)}" alt="Chữ ký điện tử" class="detail-signature-img">
                       <div>
                           <div>Người ký: <strong>${escapeHtml(r.signedByName || r.receiverName || "—")}</strong></div>
                           <div style="font-size:12px; color:#64748b;">Thời gian ký: ${escapeHtml(formatDateTime(r.signedAt))}</div>
                           <span class="status status-good" style="margin-top:4px; display:inline-block;">Chữ ký hợp lệ (Đã khóa)</span>
                       </div>
                   </div>`
                : `<div style="display:flex; align-items:center; justify-content:space-between; gap:10px;">
                       <span style="color:#64748b;">Phiếu nhập này chưa được ký điện tử.</span>
                       <button type="button" class="button button-primary button-sm" data-sign-receipt="${r.receiptId}">Ký điện tử ngay</button>
                   </div>`;

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
                <div class="detail-attachments-grid">
                    <div class="detail-attachment-card">
                        <h4>Ảnh / Chứng từ hóa đơn</h4>
                        ${invoiceBlock}
                    </div>
                    <div class="detail-attachment-card">
                        <h4>Chữ ký điện tử người lập</h4>
                        ${signatureBlock}
                    </div>
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

    function addIssueOrTransferRow(containerId, isTransfer = false) {
        const tbody = $(containerId);
        if (!tbody) return;
        const tr = document.createElement("tr");
        tr.className = isTransfer ? "transfer-item-row" : "issue-item-row";
        const matClass = isTransfer ? "transfer-material-id" : "issue-material-id";

        tr.innerHTML = `
            <td>
                <div class="mat-picker-wrap">
                    <input type="search" class="mat-search-input" placeholder="Gõ mã / tên để lọc nhanh vật tư..." aria-label="Tìm vật tư">
                    <select class="${matClass}" required aria-label="Chọn vật tư">
                        ${buildMaterialOptionsHtml()}
                    </select>
                </div>
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

        const searchInput = tr.querySelector(".mat-search-input");
        const matSelect = tr.querySelector(`.${matClass}`);
        const batchSelect = tr.querySelector(".batch-id");
        const availCell = tr.querySelector(".avail-stock");

        bindSearchableMaterialSelect(searchInput, matSelect);

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
                searchInput.value = "";
                matSelect.innerHTML = buildMaterialOptionsHtml("");
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
        clearDraftSignature("issue");
        addIssueOrTransferRow("issueItems", false);
    }

    function resetTransferForm() {
        $("transferForm").reset();
        $("transferDate").value = new Date().toISOString().slice(0, 10);
        populateDestinationBranches();
        $("transferItems").innerHTML = "";
        clearDraftSignature("transfer");
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

        const payload = {
            branchId: Number(state.branchId),
            issueType: $("issueType").value,
            issueDate: $("issueDate").value,
            notes: $("issueNotes").value.trim() || null,
            signatureData: $("issueSignatureData")?.value || null,
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
            signatureData: $("transferSignatureData")?.value || null,
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
            const data = await request(`/api/inventory/issues?${params}`, { signal: getAbortSignal("issues") });
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
                    <td><strong>${escapeHtml(fmtQty(si.totalQuantity))}</strong> (${escapeHtml(si.itemCount)} dòng)</td>
                    <td>${money(si.totalEstimatedValue)}</td>
                    <td>${renderSignatureCell(si.signatureUrl, si.signedByName, si.issueId, "issue")}</td>
                    <td>${escapeHtml(si.notes || "—")}</td>
                    <td>
                        <div class="row-actions">
                            <button type="button" class="button button-secondary button-sm" data-view-issue="${si.issueId}">Chi tiết</button>
                            <a href="/api/inventory/issues/${si.issueId}/pdf?download=true" class="button button-secondary button-sm">PDF</a>
                            <button type="button" class="button button-secondary button-sm" data-print-url="/inventory/issues/${si.issueId}/print">In A4</button>
                        </div>
                    </td>
                </tr>`;
            }).join("") : '<tr><td colspan="9" class="empty">Chưa có phiếu xuất kho phù hợp.</td></tr>';

            $("issueHistoryPage").textContent = `Trang ${state.issuesTotalPages ? page + 1 : 0} / ${state.issuesTotalPages}`;
            $("issueHistoryPrev").disabled = page <= 0;
            $("issueHistoryNext").disabled = page + 1 >= state.issuesTotalPages;
        } catch (error) {
            if (error.name === "AbortError") return;
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

            $("transferHistoryPage").textContent = `Trang ${state.transfersTotalPages ? page + 1 : 0} / ${state.transfersTotalPages}`;
            $("transferHistoryPrev").disabled = page <= 0;
            $("transferHistoryNext").disabled = page + 1 >= state.transfersTotalPages;
        } catch (error) {
            if (error.name === "AbortError") return;
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

            const signatureBlock = si.signatureUrl
                ? `<div style="display:flex; align-items:center; gap:14px;">
                       <img src="${escapeHtml(si.signatureUrl)}" alt="Chữ ký điện tử" class="detail-signature-img">
                       <div>
                           <div>Người ký: <strong>${escapeHtml(si.signedByName || si.issuerName || "—")}</strong></div>
                           <div style="font-size:12px; color:#64748b;">Thời gian ký: ${escapeHtml(formatDateTime(si.signedAt))}</div>
                           <span class="status status-good" style="margin-top:4px; display:inline-block;">Chữ ký hợp lệ (Đã khóa)</span>
                       </div>
                   </div>`
                : `<div style="display:flex; align-items:center; justify-content:space-between; gap:10px;">
                       <span style="color:#64748b;">Phiếu xuất này chưa được ký điện tử.</span>
                       <button type="button" class="button button-primary button-sm" data-sign-issue="${si.issueId}">Ký điện tử ngay</button>
                   </div>`;

            $("detailModalBody").innerHTML = `
                <div class="detail-meta-grid">
                    <div class="detail-meta-item"><span>Mã phiếu</span><strong>${escapeHtml(si.issueCode)}</strong></div>
                    <div class="detail-meta-item"><span>Ngày lập</span><strong>${escapeHtml(si.issueDate)}</strong></div>
                    <div class="detail-meta-item"><span>Loại giao dịch</span><strong>${escapeHtml(si.issueType)}</strong></div>
                    <div class="detail-meta-item"><span>Chi nhánh xuất</span><strong>${escapeHtml(si.branchName)}</strong></div>
                    ${isTransfer ? `<div class="detail-meta-item"><span>Chi nhánh nhận</span><strong>${escapeHtml(si.destinationBranchName || "—")}</strong></div>` : ""}
                    <div class="detail-meta-item"><span>Người lập phiếu</span><strong>${escapeHtml(si.issuerName || "—")}</strong></div>
                    <div class="detail-meta-item"><span>Tổng SL xuất</span><strong>${escapeHtml(fmtQty(si.totalQuantity))}</strong></div>
                    <div class="detail-meta-item"><span>Tổng giá trị vốn</span><strong>${money(si.totalEstimatedValue)}</strong></div>
                </div>
                ${si.notes ? `<p style="margin-bottom:10px;"><strong>Ghi chú:</strong> ${escapeHtml(si.notes)}</p>` : ""}
                <div class="detail-attachment-card" style="margin-bottom:14px;">
                    <h4>Chữ ký điện tử người lập phiếu xuất</h4>
                    ${signatureBlock}
                </div>
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
    // A4. BẢNG KÝ ĐIỆN TỬ (HTML5 CANVAS - MOUSE, TOUCH, STYLUS)
    // =========================================================================

    function initSignatureCanvas() {
        const canvas = $("signatureCanvas");
        if (!canvas) return;
        const ctx = canvas.getContext("2d");

        const resetCtxStyle = () => {
            ctx.strokeStyle = "#0f172a";
            ctx.lineWidth = 2.4;
            ctx.lineCap = "round";
            ctx.lineJoin = "round";
        };
        resetCtxStyle();

        let drawing = false;
        let lastX = 0;
        let lastY = 0;

        const getPoint = (e) => {
            const rect = canvas.getBoundingClientRect();
            const scaleX = canvas.width / (rect.width || canvas.width);
            const scaleY = canvas.height / (rect.height || canvas.height);
            let clientX = e.clientX;
            let clientY = e.clientY;
            if (e.touches && e.touches.length > 0) {
                clientX = e.touches[0].clientX;
                clientY = e.touches[0].clientY;
            }
            return {
                x: (clientX - rect.left) * scaleX,
                y: (clientY - rect.top) * scaleY
            };
        };

        const startDraw = (e) => {
            if (e.cancelable) e.preventDefault();
            drawing = true;
            state.hasSignatureStrokes = true;
            const pt = getPoint(e);
            lastX = pt.x;
            lastY = pt.y;
            ctx.beginPath();
            ctx.moveTo(lastX, lastY);
            ctx.lineTo(lastX + 0.1, lastY + 0.1);
            ctx.stroke();
        };

        const moveDraw = (e) => {
            if (!drawing) return;
            if (e.cancelable) e.preventDefault();
            const pt = getPoint(e);
            ctx.beginPath();
            ctx.moveTo(lastX, lastY);
            ctx.lineTo(pt.x, pt.y);
            ctx.stroke();
            lastX = pt.x;
            lastY = pt.y;
        };

        const endDraw = () => {
            drawing = false;
        };

        canvas.addEventListener("mousedown", startDraw);
        canvas.addEventListener("mousemove", moveDraw);
        window.addEventListener("mouseup", endDraw);

        canvas.addEventListener("touchstart", startDraw, { passive: false });
        canvas.addEventListener("touchmove", moveDraw, { passive: false });
        window.addEventListener("touchend", endDraw);
    }

    function clearSignatureCanvas() {
        const canvas = $("signatureCanvas");
        if (!canvas) return;
        const ctx = canvas.getContext("2d");
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        state.hasSignatureStrokes = false;
    }

    function openSignatureModal(context, targetId = null) {
        state.signContext = context;
        state.signTargetId = targetId;
        clearSignatureCanvas();
        if ($("signatureModal")) $("signatureModal").hidden = false;
    }

    function clearDraftSignature(formKind) {
        if (formKind === "receipt") {
            if ($("receiptSignatureData")) $("receiptSignatureData").value = "";
            if ($("receiptSignPreview")) { $("receiptSignPreview").src = ""; $("receiptSignPreview").hidden = true; }
            if ($("receiptSignStatus")) $("receiptSignStatus").textContent = "Chưa ký (Có thể ký ngay hoặc ký sau trong lịch sử phiếu)";
            if ($("clearReceiptSignBtn")) $("clearReceiptSignBtn").hidden = true;
        } else if (formKind === "issue") {
            if ($("issueSignatureData")) $("issueSignatureData").value = "";
            if ($("issueSignPreview")) { $("issueSignPreview").src = ""; $("issueSignPreview").hidden = true; }
            if ($("issueSignStatus")) $("issueSignStatus").textContent = "Chưa ký (Có thể ký ngay hoặc ký sau trong lịch sử phiếu)";
            if ($("clearIssueSignBtn")) $("clearIssueSignBtn").hidden = true;
        } else if (formKind === "transfer") {
            if ($("transferSignatureData")) $("transferSignatureData").value = "";
            if ($("transferSignPreview")) { $("transferSignPreview").src = ""; $("transferSignPreview").hidden = true; }
            if ($("transferSignStatus")) $("transferSignStatus").textContent = "Chưa ký";
            if ($("clearTransferSignBtn")) $("clearTransferSignBtn").hidden = true;
        }
    }

    async function confirmSignature() {
        const canvas = $("signatureCanvas");
        if (!canvas || !state.hasSignatureStrokes) {
            showMessage("Vui lòng vẽ chữ ký rõ ràng lên vùng ký trước khi bấm Xác nhận.", "warning");
            return;
        }
        const dataUrl = canvas.toDataURL("image/png");
        const btn = $("confirmSignatureBtn");

        if (state.signContext === "receipt-draft") {
            $("receiptSignatureData").value = dataUrl;
            $("receiptSignPreview").src = dataUrl;
            $("receiptSignPreview").hidden = false;
            $("receiptSignStatus").textContent = "✓ Đã ký nháp (Sẽ lưu cùng phiếu nhập)";
            $("clearReceiptSignBtn").hidden = false;
            $("signatureModal").hidden = true;
            return;
        }
        if (state.signContext === "issue-draft") {
            $("issueSignatureData").value = dataUrl;
            $("issueSignPreview").src = dataUrl;
            $("issueSignPreview").hidden = false;
            $("issueSignStatus").textContent = "✓ Đã ký nháp (Sẽ lưu cùng phiếu xuất)";
            $("clearIssueSignBtn").hidden = false;
            $("signatureModal").hidden = true;
            return;
        }
        if (state.signContext === "transfer-draft") {
            $("transferSignatureData").value = dataUrl;
            $("transferSignPreview").src = dataUrl;
            $("transferSignPreview").hidden = false;
            $("transferSignStatus").textContent = "✓ Đã ký nháp (Sẽ lưu cùng phiếu điều chuyển)";
            $("clearTransferSignBtn").hidden = false;
            $("signatureModal").hidden = true;
            return;
        }

        btn.disabled = true;
        try {
            if (state.signContext === "receipt-existing" && state.signTargetId) {
                const updated = await request(`/api/inventory/receipts/${state.signTargetId}/sign`, {
                    method: "POST",
                    body: JSON.stringify({ signatureData: dataUrl })
                });
                $("signatureModal").hidden = true;
                showMessage(`Đã ký điện tử phiếu nhập ${updated.receiptCode} bởi ${updated.signedByName}.`, "success");
                await loadReceiptHistory(state.receiptsPage);
                if (!$("detailModal").hidden) await viewReceiptDetail(state.signTargetId);
            } else if (state.signContext === "issue-existing" && state.signTargetId) {
                const updated = await request(`/api/inventory/issues/${state.signTargetId}/sign`, {
                    method: "POST",
                    body: JSON.stringify({ signatureData: dataUrl })
                });
                $("signatureModal").hidden = true;
                showMessage(`Đã ký điện tử phiếu xuất ${updated.issueCode} bởi ${updated.signedByName}.`, "success");
                await loadIssueHistory(state.issuesPage);
                await loadTransferHistory(state.transfersPage);
                if (!$("detailModal").hidden) await viewIssueDetail(state.signTargetId);
            }
        } catch (err) {
            showMessage(err.message || "Không thể lưu chữ ký điện tử.", "error");
        } finally {
            btn.disabled = false;
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

            $("materialsPage").textContent = `Trang ${state.materialsTotalPages ? page + 1 : 0} / ${state.materialsTotalPages}`;
            $("materialsPrev").disabled = page <= 0;
            $("materialsNext").disabled = page + 1 >= state.materialsTotalPages;
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

            $("paymentHistoryPage").textContent = `Trang ${state.paymentsTotalPages ? page + 1 : 0} / ${state.paymentsTotalPages}`;
            $("paymentHistoryPrev").disabled = page <= 0;
            $("paymentHistoryNext").disabled = page + 1 >= state.paymentsTotalPages;
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

    document.addEventListener("DOMContentLoaded", () => {
        const todayStr = new Date().toISOString().slice(0, 10);
        if ($("receiptDate")) $("receiptDate").value = todayStr;
        if ($("issueDate")) $("issueDate").value = todayStr;
        if ($("transferDate")) $("transferDate").value = todayStr;
        if ($("payDate")) $("payDate").value = todayStr;

        initSignatureCanvas();
        addReceiptRow();
        addIssueOrTransferRow("issueItems", false);
        addIssueOrTransferRow("transferItems", true);

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

        $("branchId").addEventListener("change", async () => {
            state.branchId = Number($("branchId").value) || null;
            populateDestinationBranches();
            await refreshBranchData();
        });

        $("searchBtn").addEventListener("click", triggerGlobalKeywordSearch);

        if ($("clearSearchBtn")) {
            $("clearSearchBtn").addEventListener("click", () => {
                $("keyword").value = "";
                $("lowStock").checked = false;
                triggerGlobalKeywordSearch();
            });
        }

        const debouncedGlobalSearch = debounce(triggerGlobalKeywordSearch, 350);
        $("keyword").addEventListener("input", debouncedGlobalSearch);
        $("keyword").addEventListener("keydown", e => {
            if (e.key === "Enter") { e.preventDefault(); triggerGlobalKeywordSearch(); }
        });

        // A1: Invoice file upload & preview bindings
        if ($("invoiceFileInput")) {
            $("invoiceFileInput").addEventListener("change", (e) => {
                const file = e.target.files?.[0];
                if (file) handleInvoiceFileUpload(file);
            });
        }
        if ($("clearUploadedInvoiceBtn")) {
            $("clearUploadedInvoiceBtn").addEventListener("click", clearUploadedInvoice);
        }
        if ($("zoomUploadedInvoiceBtn")) {
            $("zoomUploadedInvoiceBtn").addEventListener("click", () => {
                openImageLightbox($("attachmentUrl").value, "Hóa đơn đính kèm phiếu nhập");
            });
        }
        if ($("invoicePreviewImg")) {
            $("invoicePreviewImg").addEventListener("click", () => {
                openImageLightbox($("attachmentUrl").value, "Hóa đơn đính kèm phiếu nhập");
            });
        }

        // A2: Initial payment bindings
        if ($("receiptPaidAmount")) {
            $("receiptPaidAmount").addEventListener("input", calculateReceiptTotal);
        }
        if ($("payFullReceiptBtn")) {
            $("payFullReceiptBtn").addEventListener("click", () => {
                const total = computeCurrentReceiptTotalNumber();
                if ($("receiptPaidAmount")) {
                    $("receiptPaidAmount").value = String(total);
                    calculateReceiptTotal();
                }
            });
        }

        // A4: Digital signature modal bindings
        if ($("openReceiptSignBtn")) $("openReceiptSignBtn").addEventListener("click", () => openSignatureModal("receipt-draft"));
        if ($("clearReceiptSignBtn")) $("clearReceiptSignBtn").addEventListener("click", () => clearDraftSignature("receipt"));
        if ($("openIssueSignBtn")) $("openIssueSignBtn").addEventListener("click", () => openSignatureModal("issue-draft"));
        if ($("clearIssueSignBtn")) $("clearIssueSignBtn").addEventListener("click", () => clearDraftSignature("issue"));
        if ($("openTransferSignBtn")) $("openTransferSignBtn").addEventListener("click", () => openSignatureModal("transfer-draft"));
        if ($("clearTransferSignBtn")) $("clearTransferSignBtn").addEventListener("click", () => clearDraftSignature("transfer"));

        if ($("clearSignatureCanvasBtn")) $("clearSignatureCanvasBtn").addEventListener("click", clearSignatureCanvas);
        if ($("closeSignatureModalBtn")) $("closeSignatureModalBtn").addEventListener("click", () => { $("signatureModal").hidden = true; });
        if ($("cancelSignatureModalBtn")) $("cancelSignatureModalBtn").addEventListener("click", () => { $("signatureModal").hidden = true; });
        if ($("confirmSignatureBtn")) $("confirmSignatureBtn").addEventListener("click", confirmSignature);

        if ($("closeLightboxBtn")) $("closeLightboxBtn").addEventListener("click", () => { $("imageLightboxModal").hidden = true; });
        if ($("closeLightboxFooterBtn")) $("closeLightboxFooterBtn").addEventListener("click", () => { $("imageLightboxModal").hidden = true; });

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
            const receiptDebt = opt?.dataset?.debt ? Number(opt.dataset.debt) : null;
            updatePaymentDebtBanner($("paySupplierId").value, receiptDebt);
            if (receiptDebt != null) {
                $("payAmount").value = receiptDebt;
            }
        });

        $("openQuickSupplierBtn").addEventListener("click", () => openQuickSupplierModal());
        $("closeQuickSupplierBtn").addEventListener("click", () => { $("quickSupplierModal").hidden = true; });
        $("cancelQuickSupplierBtn").addEventListener("click", () => { $("quickSupplierModal").hidden = true; });

        $("closeDetailModalBtn").addEventListener("click", () => { $("detailModal").hidden = true; });
        $("closeDetailModalFooterBtn")?.addEventListener("click", () => { $("detailModal").hidden = true; });

        ["materialModal", "paymentModal", "quickSupplierModal", "detailModal", "signatureModal", "imageLightboxModal"].forEach(modalId => {
            const modalEl = $(modalId);
            if (modalEl) {
                modalEl.addEventListener("click", (e) => {
                    if (e.target === modalEl) modalEl.hidden = true;
                });
            }
        });

        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape") {
                ["materialModal", "paymentModal", "quickSupplierModal", "detailModal", "signatureModal", "imageLightboxModal"].forEach(modalId => {
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

            const signReceiptBtn = e.target.closest("[data-sign-receipt]");
            if (signReceiptBtn) {
                openSignatureModal("receipt-existing", Number(signReceiptBtn.dataset.signReceipt));
                return;
            }

            const signIssueBtn = e.target.closest("[data-sign-issue]");
            if (signIssueBtn) {
                openSignatureModal("issue-existing", Number(signIssueBtn.dataset.signIssue));
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

        $("refreshBtn").addEventListener("click", async () => {
            await refreshBranchData();
            showMessage("Đã làm mới dữ liệu kho.", "success");
        });

        loadBranches();
    });
})();
