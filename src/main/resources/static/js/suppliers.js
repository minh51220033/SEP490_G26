/**
 * Module Frontend cho màn hình Quản lý Nhà cung cấp & Công nợ (/suppliers — templates/suppliers/list.html).
 *
 * Chức năng chính:
 * - Tải danh sách chi nhánh được phép truy cập (/api/branches/accessible) vào dropdown #branchSelect.
 * - Tải đồng thời danh sách NCC (/api/suppliers) và tổng hợp công nợ động (/api/inventory/supplier-payments/debts),
 *   kèm tìm kiếm debounce, lọc trạng thái, phân trang và chống race-condition bằng AbortController.
 * - Thêm mới / Chỉnh sửa / Ngừng hoạt động / Kích hoạt lại NCC (Modal #modal, Form #supplierForm).
 * - Thanh toán công nợ NCC (chung hoặc đối ứng theo phiếu nhập) kèm chống double-submit qua clientRequestId
 *   (Modal #supplierPayModal, Form #supplierPayForm).
 */
const API = "/api/suppliers";
let pageSize = 10;

/** Biểu thức chính quy kiểm tra định dạng dữ liệu đầu vào phía client (đồng bộ với DTO/Service backend). */
const REGEX = {
    SUPPLIER_CODE: /^[A-Za-z0-9_-]{2,30}$/,
    TAX_CODE: /^[0-9]{10}(-[0-9]{3})?$/,
    PHONE: /^(0|\+84)[0-9]{9,10}$/,
    REFERENCE_NO: /^[A-Za-z0-9._/-]{1,100}$/
};

/** Trạng thái phân trang, bản ghi đang sửa, chi nhánh đang chọn, map công nợ theo supplierId và AbortController hủy request cũ. */
let currentPage = 0;
let totalPages = 0;
let editingId = null;
let currentBranchId = null;
let debtMap = new Map();
let listAbortController = null;

/** Hàm rút gọn document.getElementById(id). */
const $ = (id) => document.getElementById(id);

/**
 * Định dạng số tiền sang chuẩn tiền tệ Việt Nam (VNĐ, không phần thập phân).
 * @param {number|string} value - Giá trị tiền cần định dạng
 * @returns {string} Chuỗi tiền tệ (VD: "1.500.000 ₫")
 */
const money = (value) => new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0
}).format(Number(value || 0));

/**
 * Tạo hàm debounce trì hoãn thực thi callback sau khoảng thời gian không có thao tác mới.
 * Dùng cho ô tìm kiếm #keyword để giảm số lần gọi API khi người dùng gõ liên tục.
 * @param {Function} fn - Hàm cần trì hoãn
 * @param {number} [delayMs=350] - Thời gian chờ (ms)
 */
function debounce(fn, delayMs = 350) {
    let timer = null;
    return (...args) => {
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => fn(...args), delayMs);
    };
}

/**
 * Hiển thị thanh thông báo trạng thái (#message) trên giao diện (success / error / info).
 * @param {string} message - Nội dung thông báo (rỗng sẽ ẩn banner)
 * @param {string} [type=""] - Loại thông báo CSS ("success", "error", "info")
 */
function showMessage(message, type = "") {
    const el = $("message");
    if (!el) return;
    el.textContent = message;
    el.className = `message ${type}`;
    el.hidden = !message;
}

/**
 * Wrapper gọi fetch JSON gửi kèm cookie phiên (same-origin) và chuẩn hóa xử lý lỗi HTTP.
 * - 401/403: Ném thông báo hết phiên hoặc không đủ quyền.
 * - 400 (validation): Gắn err.fieldErrors từ data.errors để hiển thị lỗi inline cạnh từng input.
 * @param {string} url - Đường dẫn API
 * @param {RequestInit} [options={}] - Tùy chọn fetch (method, body, signal...)
 * @returns {Promise<any>} Dữ liệu JSON trả về từ server
 */
async function request(url, options = {}) {
    const response = await fetch(url, {
        ...options,
        headers: {
            "Content-Type": "application/json",
            ...(options.headers || {})
        },
        credentials: "same-origin"
    });

    const text = await response.text();
    let data = null;

    try {
        data = text ? JSON.parse(text) : null;
    } catch {
        data = null;
    }

    if (!response.ok) {
        if (response.status === 401) {
            throw new Error("Phiên đăng nhập đã hết hạn hoặc không hợp lệ. Vui lòng đăng nhập lại.");
        }
        if (response.status === 403) {
            throw new Error("Bạn không có quyền thực hiện thao tác này.");
        }

        const err = new Error(
            (data?.errors ? Object.values(data.errors).join(" ") : "") ||
            data?.message ||
            "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau."
        );
        err.fieldErrors = data?.errors || null;
        throw err;
    }

    return data;
}

/**
 * Escape các ký tự đặc biệt HTML (&, <, >, ", ') để chống XSS khi render nội dung vào innerHTML.
 * @param {any} value - Giá trị cần escape
 * @returns {string} Chuỗi an toàn để chèn HTML
 */
function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[char]);
}

/**
 * Khởi tạo danh sách chi nhánh được phép truy cập cho bộ lọc #branchSelect từ API /api/branches/accessible.
 * Chọn chi nhánh đầu tiên làm currentBranchId, khóa dropdown nếu user không có quyền đổi chi nhánh,
 * sau đó gọi loadSuppliers() để tải dữ liệu ban đầu.
 */
async function initBranches() {
    const branchSelect = $("branchSelect");
    if (!branchSelect) {
        await loadSuppliers();
        return;
    }
    try {
        const accessData = await request("/api/branches/accessible");
        const branches = Array.isArray(accessData?.branches) ? accessData.branches : [];
        if (branches.length > 0) {
            branchSelect.innerHTML = branches.map(b =>
                `<option value="${escapeHtml(b.branchId)}">${escapeHtml(b.branchCode ? b.branchCode + " - " : "")}${escapeHtml(b.branchName)}</option>`
            ).join("");
            branchSelect.disabled = !accessData?.branchSelectionAllowed;
            currentBranchId = Number(branches[0].branchId);
        } else {
            branchSelect.innerHTML = '<option value="">Chưa có chi nhánh</option>';
            branchSelect.disabled = true;
        }
    } catch {
        branchSelect.innerHTML = '<option value="">Chi nhánh mặc định</option>';
        branchSelect.disabled = true;
    }
    await loadSuppliers();
}

/**
 * Tải danh sách nhà cung cấp kết hợp số liệu công nợ động theo chi nhánh đang chọn và render bảng #supplierTable.
 *
 * Xử lý thực tế:
 * 1. Hủy request đang chạy trước đó qua listAbortController (chống ghi đè dữ liệu cũ khi gõ tìm kiếm nhanh).
 * 2. Gọi song song (Promise.all):
 *    - GET /api/suppliers (phân trang, lọc #keyword, #active, sắp xếp theo supplierName ASC).
 *    - GET /api/inventory/supplier-payments/debts?branchId=... (tổng nhập, đã trả, còn nợ) rồi lưu vào debtMap.
 * 3. Cập nhật thanh phân trang (#totalCount, #lblSuppliersRange, #lblSuppliersTotal, #supplierPageSizeSelect,
 *    các nút #supplierFirstBtn, #supplierPrevBtn, #supplierNextBtn, #supplierLastBtn).
 * 4. Render các dòng bảng #supplierTable kèm 3 nút thao tác: Thanh toán (disable khi còn nợ <= 0), Sửa, Ngừng/Kích hoạt.
 *
 * @param {number} [page=currentPage] - Chỉ số trang cần tải (bắt đầu từ 0)
 */
async function loadSuppliers(page = currentPage) {
    if (listAbortController) {
        try { listAbortController.abort(); } catch { /* ignore */ }
    }
    listAbortController = new AbortController();
    const signal = listAbortController.signal;

    $("supplierTable").innerHTML =
        '<tr><td colspan="8" class="empty">Đang tải dữ liệu...</td></tr>';

    currentPage = page;
    const kw = $("keyword").value.trim();
    const params = new URLSearchParams({
        keyword: kw,
        page: currentPage,
        size: pageSize,
        sortBy: "supplierName",
        direction: "asc"
    });

    if ($("active").value !== "") {
        params.set("active", $("active").value);
    }

    try {
        const [data, debtList] = await Promise.all([
            request(`${API}?${params.toString()}`, { signal }),
            currentBranchId
                ? request(`/api/inventory/supplier-payments/debts?branchId=${currentBranchId}&keyword=${encodeURIComponent(kw)}`, { signal }).catch(() => [])
                : Promise.resolve([])
        ]);

        debtMap.clear();
        (Array.isArray(debtList) ? debtList : []).forEach(d => {
            debtMap.set(Number(d.supplierId), d);
        });

        const totalElements = Number(data?.totalElements || 0);
        totalPages = Number(data?.totalPages || 0);
        if ($("totalCount")) $("totalCount").textContent = `${totalElements} nhà cung cấp`;

        const fromItem = totalElements === 0 ? 0 : (currentPage * pageSize) + 1;
        const toItem = Math.min((currentPage + 1) * pageSize, totalElements);

        if ($("lblSuppliersRange")) {
            $("lblSuppliersRange").textContent = totalElements === 0 ? "0 - 0" : `${fromItem} - ${toItem}`;
        }
        if ($("lblSuppliersTotal")) {
            $("lblSuppliersTotal").textContent = totalElements;
        }
        if ($("supplierPageSizeSelect")) {
            $("supplierPageSizeSelect").value = pageSize;
        }

        const isFirst = currentPage <= 0 || totalElements === 0;
        const isLast = currentPage >= totalPages - 1 || totalElements === 0;

        const firstBtn = $("supplierFirstBtn");
        if (firstBtn) {
            firstBtn.disabled = isFirst;
            firstBtn.classList.toggle("disabled", isFirst);
        }
        const prevBtn = $("supplierPrevBtn") || $("prevBtn");
        if (prevBtn) {
            prevBtn.disabled = isFirst;
            prevBtn.classList.toggle("disabled", isFirst);
        }
        const nextBtn = $("supplierNextBtn") || $("nextBtn");
        if (nextBtn) {
            nextBtn.disabled = isLast;
            nextBtn.classList.toggle("disabled", isLast);
        }
        const lastBtn = $("supplierLastBtn");
        if (lastBtn) {
            lastBtn.disabled = isLast;
            lastBtn.classList.toggle("disabled", isLast);
        }

        if (!data?.content || data.content.length === 0) {
            $("supplierTable").innerHTML =
                '<tr><td colspan="8" class="empty">Không tìm thấy nhà cung cấp phù hợp.</td></tr>';
            return;
        }

        $("supplierTable").innerHTML = data.content.map((s) => {
            const debtInfo = debtMap.get(Number(s.supplierId));
            const totalImport = Number(debtInfo?.totalImportAmount || 0);
            const totalPaid = Number(debtInfo?.totalPaidAmount || 0);
            const remainingDebt = Number(debtInfo?.remainingDebt || 0);

            return `
            <tr>
                <td><span class="code-badge">${escapeHtml(s.supplierCode)}</span></td>
                <td>
                    <strong>${escapeHtml(s.supplierName)}</strong>
                    <div style="font-size:12px; color:#64748b;">${escapeHtml(s.address || "—")}</div>
                </td>
                <td>
                    <div>MST: ${escapeHtml(s.taxCode || "—")}</div>
                    <div style="font-size:12px; color:#475569;">ĐT: ${escapeHtml(s.phoneNumber || "—")}</div>
                </td>
                <td><strong>${money(totalImport)}</strong></td>
                <td style="color:#15803d; font-weight:600;">${money(totalPaid)}</td>
                <td style="color:${remainingDebt > 0 ? "#b91c1c" : "#15803d"}; font-weight:700;">${money(remainingDebt)}</td>
                <td>
                    <span class="status ${s.isActive ? "status-active" : "status-inactive"}">
                        ${s.isActive ? "Đang hoạt động" : "Ngừng hoạt động"}
                    </span>
                </td>
                <td>
                    <button type="button" class="action-btn" data-action="pay" data-id="${s.supplierId}" ${remainingDebt <= 0 ? "disabled" : ""}>
                        Thanh toán
                    </button>
                    <button type="button" class="action-btn" data-action="edit" data-id="${s.supplierId}">
                        Sửa
                    </button>
                    <button type="button" class="action-btn ${s.isActive ? "danger" : ""}"
                        data-action="${s.isActive ? "deactivate" : "activate"}"
                        data-id="${s.supplierId}"
                        data-name="${escapeHtml(s.supplierName)}">
                        ${s.isActive ? "Ngừng" : "Kích hoạt"}
                    </button>
                </td>
            </tr>
        `}).join("");
    } catch (error) {
        if (error.name === "AbortError") return;
        $("supplierTable").innerHTML =
            `<tr><td colspan="8" class="empty">${escapeHtml(error.message || "Không thể tải dữ liệu.")}</td></tr>`;
        showMessage(error.message, "error");
    }
}

/**
 * Mở modal Thêm mới / Chỉnh sửa nhà cung cấp (#modal, form #supplierForm).
 * Nếu truyền đối tượng supplier sẽ điền sẵn dữ liệu vào các ô nhập và đặt editingId; ngược lại mở ở chế độ thêm mới.
 * @param {Object|null} [supplier=null] - Dữ liệu NCC cần sửa (null nếu thêm mới)
 */
function openForm(supplier = null) {
    editingId = supplier?.supplierId ?? null;
    $("supplierForm").reset();
    clearFieldErrors();

    $("formTitle").textContent = editingId
        ? "Chỉnh sửa nhà cung cấp"
        : "Thêm nhà cung cấp";

    $("supplierCode").readOnly = false;

    if (supplier) {
        $("supplierCode").value = supplier.supplierCode ?? "";
        $("supplierName").value = supplier.supplierName ?? "";
        $("taxCode").value = supplier.taxCode ?? "";
        $("phoneNumber").value = supplier.phoneNumber ?? "";
        $("address").value = supplier.address ?? "";
    }

    $("modal").hidden = false;
}

/** Đóng modal Thêm / Sửa nhà cung cấp (#modal). */
function closeForm() {
    $("modal").hidden = true;
}

/**
 * Mở modal Thanh toán công nợ nhà cung cấp (#supplierPayModal, form #supplierPayForm).
 *
 * Xử lý thực tế:
 * 1. Tra cứu công nợ từ debtMap; nếu không còn công nợ (<= 0) thì báo thông tin và không mở modal.
 * 2. Hiển thị tổng nhập, đã trả, còn nợ (#payTotalImportDisplay, #payTotalPaidDisplay, #payRemainingDebtDisplay)
 *    và điền mặc định số tiền thanh toán (#payAmountInput) bằng toàn bộ công nợ còn lại.
 * 3. Tải danh sách phiếu nhập kho còn nợ của NCC từ GET /api/inventory/receipts vào dropdown #payReceiptSelect
 *    để người dùng có thể chọn thanh toán đối ứng theo phiếu nhập hoặc để trống để trả công nợ chung.
 *
 * @param {number|string} supplierId - ID nhà cung cấp cần thanh toán
 */
async function openSupplierPayModal(supplierId) {
    const debtInfo = debtMap.get(Number(supplierId));
    if (!debtInfo || Number(debtInfo.remainingDebt || 0) <= 0) {
        showMessage("Nhà cung cấp này hiện không còn công nợ tại chi nhánh đang chọn.", "info");
        return;
    }
    $("supplierPayForm").reset();
    $("paySupplierId").value = String(supplierId);
    $("paySupplierNameDisplay").textContent = `${debtInfo.supplierCode} - ${debtInfo.supplierName}`;
    $("payTotalImportDisplay").textContent = money(debtInfo.totalImportAmount);
    $("payTotalPaidDisplay").textContent = money(debtInfo.totalPaidAmount);
    $("payRemainingDebtDisplay").textContent = money(debtInfo.remainingDebt);
    $("payAmountInput").value = Number(debtInfo.remainingDebt);
    $("payAmountInput").max = String(debtInfo.remainingDebt);

    const receiptSel = $("payReceiptSelect");
    receiptSel.innerHTML = '<option value="">-- Thanh toán công nợ chung --</option>';
    if (currentBranchId) {
        try {
            const res = await request(`/api/inventory/receipts?branchId=${currentBranchId}&supplierId=${supplierId}&page=0&size=100`);
            const unpaid = (res?.content || []).filter(r => Number(r.remainingDebt || 0) > 0);
            receiptSel.innerHTML += unpaid.map(r =>
                `<option value="${escapeHtml(r.receiptId)}" data-debt="${escapeHtml(r.remainingDebt)}">${escapeHtml(r.receiptCode)} (${escapeHtml(r.receiptDate)}) - Còn nợ: ${money(r.remainingDebt)}</option>`
            ).join("");
        } catch { /* ignore */ }
    }
    $("supplierPayModal").hidden = false;
}

/** Xóa toàn bộ thông báo lỗi inline dưới các trường của form #supplierForm. */
function clearFieldErrors() {
    [
        "supplierCode",
        "supplierName",
        "taxCode",
        "phoneNumber",
        "address"
    ].forEach((field) => {
        const el = $(`${field}Error`);
        if (el) el.textContent = "";
    });
}

/**
 * Gán thông báo lỗi inline cho một trường cụ thể (phần tử #<field>Error).
 * @param {string} field - Tên trường (supplierCode, supplierName, taxCode, phoneNumber, address)
 * @param {string} msg - Nội dung thông báo lỗi
 */
function setFieldError(field, msg) {
    const el = $(`${field}Error`);
    if (el) el.textContent = msg;
}

/**
 * Kiểm tra hợp lệ dữ liệu form Thêm / Sửa nhà cung cấp phía client trước khi gọi API.
 * Kiểm tra bắt buộc và regex cho supplierCode, supplierName, taxCode, phoneNumber, address.
 * @param {Object} payload - Dữ liệu đã trim từ #supplierForm
 * @returns {boolean} true nếu hợp lệ, false nếu có ít nhất 1 lỗi
 */
function validateSupplierPayload(payload) {
    let valid = true;
    clearFieldErrors();

    if (!payload.supplierCode) {
        setFieldError("supplierCode", "Mã nhà cung cấp không được để trống.");
        valid = false;
    } else if (!REGEX.SUPPLIER_CODE.test(payload.supplierCode)) {
        setFieldError("supplierCode", "Mã NCC từ 2-30 ký tự, chỉ gồm chữ, số, dấu - hoặc _.");
        valid = false;
    }

    if (!payload.supplierName || payload.supplierName.length < 2) {
        setFieldError("supplierName", "Tên nhà cung cấp phải từ 2 đến 100 ký tự.");
        valid = false;
    }

    if (!payload.taxCode) {
        setFieldError("taxCode", "Mã số thuế không được để trống.");
        valid = false;
    } else if (!REGEX.TAX_CODE.test(payload.taxCode)) {
        setFieldError("taxCode", "Mã số thuế gồm 10 chữ số hoặc định dạng 10 số-3 số.");
        valid = false;
    }

    if (!payload.phoneNumber) {
        setFieldError("phoneNumber", "Số điện thoại không được để trống.");
        valid = false;
    } else if (!REGEX.PHONE.test(payload.phoneNumber)) {
        setFieldError("phoneNumber", "Số điện thoại bắt đầu bằng 0 hoặc +84, theo sau 9-10 chữ số.");
        valid = false;
    }

    if (!payload.address) {
        setFieldError("address", "Địa chỉ không được để trống.");
        valid = false;
    }

    return valid;
}

/**
 * Hiển thị lỗi trả về từ server: điền lỗi chi tiết theo từng trường (error.fieldErrors)
 * và hiển thị thông báo tổng quát lên banner #message.
 * @param {Error & {fieldErrors?: Object}} error - Lỗi ném ra từ hàm request()
 */
function showValidationErrors(error) {
    clearFieldErrors();
    if (error.fieldErrors && typeof error.fieldErrors === "object") {
        Object.entries(error.fieldErrors).forEach(([field, msg]) => {
            setFieldError(field, msg);
        });
    }
    showMessage(error.message, "error");
}

/* ==================== SỰ KIỆN MỞ / ĐÓNG MODAL ==================== */
// Nút "+ Thêm nhà cung cấp" (#addBtn) mở modal thêm mới; #closeBtn và #cancelBtn đóng #modal.
$("addBtn").addEventListener("click", () => openForm());
$("closeBtn").addEventListener("click", closeForm);
$("cancelBtn").addEventListener("click", closeForm);

// Nút đóng (#closePayModalBtn) và nút Hủy (#cancelPayModalBtn) của modal thanh toán #supplierPayModal.
if ($("closePayModalBtn")) $("closePayModalBtn").addEventListener("click", () => { $("supplierPayModal").hidden = true; });
if ($("cancelPayModalBtn")) $("cancelPayModalBtn").addEventListener("click", () => { $("supplierPayModal").hidden = true; });

// Khi chọn một phiếu nhập đối ứng trong #payReceiptSelect, tự động điền số tiền còn nợ của phiếu đó vào #payAmountInput.
if ($("payReceiptSelect")) {
    $("payReceiptSelect").addEventListener("change", (e) => {
        const opt = e.target.options[e.target.selectedIndex];
        if (opt?.dataset?.debt) {
            $("payAmountInput").value = Number(opt.dataset.debt);
        }
    });
}

/**
 * Xử lý submit form Thanh toán công nợ (#supplierPayForm, nút xác nhận #confirmPayBtn):
 * - Validate số tiền > 0 và định dạng số chứng từ (#payRefInput).
 * - Sinh clientRequestId (UUID) và disable #confirmPayBtn trong suốt quá trình gửi để chống double-submit.
 * - Gửi POST /api/inventory/supplier-payments; khi thành công thì đóng #supplierPayModal, hiện mã phiếu chi và tải lại bảng.
 */
if ($("supplierPayForm")) {
    $("supplierPayForm").addEventListener("submit", async (e) => {
        e.preventDefault();
        const btn = $("confirmPayBtn");
        if (btn.disabled || !currentBranchId) return;

        const supplierId = Number($("paySupplierId").value);
        const amount = Number($("payAmountInput").value);
        const refNo = $("payRefInput").value.trim();

        if (!Number.isFinite(amount) || amount <= 0) {
            showMessage("Số tiền thanh toán phải lớn hơn 0.", "error");
            return;
        }
        if (refNo && !REGEX.REFERENCE_NO.test(refNo)) {
            showMessage("Số chứng từ không hợp lệ.", "error");
            return;
        }

        const clientRequestId = (typeof crypto !== "undefined" && crypto.randomUUID)
            ? crypto.randomUUID()
            : `req_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;

        btn.disabled = true;
        try {
            const saved = await request("/api/inventory/supplier-payments", {
                method: "POST",
                body: JSON.stringify({
                    branchId: currentBranchId,
                    supplierId,
                    receiptId: $("payReceiptSelect").value ? Number($("payReceiptSelect").value) : null,
                    paymentDate: new Date().toISOString().slice(0, 10),
                    paymentMethod: $("payMethodSelect").value,
                    amount,
                    referenceNo: refNo || null,
                    notes: $("payNotesInput").value.trim() || null,
                    clientRequestId
                })
            });
            $("supplierPayModal").hidden = true;
            showMessage(`Đã thanh toán ${money(saved.amount)} (Mã phiếu: ${saved.paymentCode}) thành công.`, "success");
            await loadSuppliers();
        } catch (err) {
            showMessage(err.message, "error");
        } finally {
            btn.disabled = false;
        }
    });
}

// Đóng #modal khi click ra ngoài vùng nền mờ (backdrop) và đóng modal đang mở khi nhấn phím Escape.
$("modal").addEventListener("click", (e) => {
    if (e.target === $("modal")) closeForm();
});

document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
        if (!$("modal").hidden) closeForm();
        if ($("supplierPayModal") && !$("supplierPayModal").hidden) $("supplierPayModal").hidden = true;
    }
});

/* ==================== SỰ KIỆN BỘ LỌC, TÌM KIẾM & PHÂN TRANG ==================== */
// Đổi chi nhánh tính công nợ (#branchSelect) → tải lại danh sách & công nợ theo chi nhánh mới.
if ($("branchSelect")) {
    $("branchSelect").addEventListener("change", () => {
        currentBranchId = Number($("branchSelect").value) || null;
        loadSuppliers();
    });
}

// Nút "Tìm kiếm" (#searchBtn) và nút "Xóa lọc" (#clearBtn) đặt lại về trang đầu (page 0) và tải lại bảng.
$("searchBtn").addEventListener("click", () => {
    currentPage = 0;
    loadSuppliers();
});

if ($("clearBtn")) {
    $("clearBtn").addEventListener("click", () => {
        $("keyword").value = "";
        $("active").value = "";
        currentPage = 0;
        loadSuppliers();
    });
}

// Tự động tìm kiếm sau 350ms ngừng gõ trên ô #keyword, hỗ trợ nhấn Enter tìm ngay và lọc tức thì khi đổi #active.
const debouncedSupplierSearch = debounce(() => {
    currentPage = 0;
    loadSuppliers();
}, 350);

$("keyword").addEventListener("input", debouncedSupplierSearch);

$("keyword").addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
        event.preventDefault();
        currentPage = 0;
        loadSuppliers();
    }
});

$("active").addEventListener("change", () => {
    currentPage = 0;
    loadSuppliers();
});

// Cụm 4 nút điều hướng phân trang (#supplierFirstBtn, #supplierPrevBtn, #supplierNextBtn, #supplierLastBtn)
// và dropdown đổi số dòng/trang (#supplierPageSizeSelect).
$("supplierFirstBtn")?.addEventListener("click", () => {
    if (currentPage > 0) {
        loadSuppliers(0);
    }
});

$("supplierPrevBtn")?.addEventListener("click", () => {
    if (currentPage > 0) {
        loadSuppliers(currentPage - 1);
    }
});

$("supplierNextBtn")?.addEventListener("click", () => {
    if (currentPage < totalPages - 1) {
        loadSuppliers(currentPage + 1);
    }
});

$("supplierLastBtn")?.addEventListener("click", () => {
    if (totalPages > 0) {
        loadSuppliers(totalPages - 1);
    }
});

$("supplierPageSizeSelect")?.addEventListener("change", (e) => {
    pageSize = parseInt(e.target.value, 10) || 10;
    loadSuppliers(0); // Bắt buộc reset về trang đầu khi đổi số lượng hiển thị
});

/* ==================== SỰ KIỆN THAO TÁC DÒNG TRONG BẢNG & SUBMIT FORM NCC ==================== */
/**
 * Event delegation trên #supplierTable cho các nút button[data-action]:
 * - data-action="pay": Mở modal thanh toán công nợ (#supplierPayModal).
 * - data-action="edit": Gọi GET /api/suppliers/{id} và mở modal chỉnh sửa (#modal).
 * - data-action="deactivate" / "activate": Xác nhận người dùng rồi gọi POST /api/suppliers/{id}/{action} và tải lại bảng.
 */
$("supplierTable").addEventListener("click", async (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button) return;

    const id = button.dataset.id;
    const action = button.dataset.action;
    const name = button.dataset.name || "nhà cung cấp này";

    try {
        if (action === "pay") {
            await openSupplierPayModal(id);
            return;
        }
        if (action === "edit") {
            const supplier = await request(`${API}/${id}`);
            openForm(supplier);
            return;
        }

        const isDeactivate = action === "deactivate";
        const confirmation = isDeactivate
            ? `Bạn có chắc chắn muốn ngừng hoạt động "${name}" không?`
            : `Bạn có chắc chắn muốn kích hoạt lại "${name}" không?`;

        if (!window.confirm(confirmation)) return;

        await request(`${API}/${id}/${action}`, {
            method: "POST"
        });

        showMessage(
            isDeactivate
                ? "Đã ngừng hoạt động nhà cung cấp."
                : "Đã kích hoạt nhà cung cấp.",
            "success"
        );

        await loadSuppliers();
    } catch (error) {
        showMessage(error.message, "error");
    }
});

/**
 * Xử lý submit form Thêm / Sửa nhà cung cấp (#supplierForm, nút lưu #saveBtn):
 * - Chuẩn hóa mã NCC chữ hoa, kiểm tra hợp lệ qua validateSupplierPayload().
 * - Disable #saveBtn chống gửi trùng; gọi POST /api/suppliers (thêm mới) hoặc PUT /api/suppliers/{editingId} (cập nhật).
 * - Thành công: đóng modal, hiển thị thông báo và tải lại trang đầu danh sách; lỗi: hiển thị lỗi inline qua showValidationErrors().
 */
$("supplierForm").addEventListener("submit", async (event) => {
    event.preventDefault();

    const payload = {
        supplierCode: $("supplierCode").value.trim().toUpperCase(),
        supplierName: $("supplierName").value.trim(),
        taxCode: $("taxCode").value.trim(),
        phoneNumber: $("phoneNumber").value.trim(),
        address: $("address").value.trim()
    };

    if (!validateSupplierPayload(payload)) {
        return;
    }

    const saveButton = $("saveBtn");
    saveButton.disabled = true;

    try {
        await request(
            editingId ? `${API}/${editingId}` : API,
            {
                method: editingId ? "PUT" : "POST",
                body: JSON.stringify(payload)
            }
        );

        closeForm();
        showMessage(
            editingId
                ? "Cập nhật thông tin nhà cung cấp thành công."
                : "Thêm nhà cung cấp mới thành công.",
            "success"
        );

        currentPage = 0;
        await loadSuppliers();
    } catch (error) {
        showValidationErrors(error);
    } finally {
        saveButton.disabled = false;
    }
});

// Khởi chạy trang: tải danh sách chi nhánh được phép truy cập trước, sau đó tự động tải bảng NCC & công nợ.
initBranches();
