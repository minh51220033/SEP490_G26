const API = "/api/suppliers";
const PAGE_SIZE = 10;

const REGEX = {
    SUPPLIER_CODE: /^[A-Za-z0-9_-]{2,20}$/,
    TAX_CODE: /^[0-9]{10}(-[0-9]{3})?$/,
    PHONE: /^(0|\+84)[0-9]{9,10}$/,
    REFERENCE_NO: /^[A-Za-z0-9._/-]{1,100}$/
};

let currentPage = 0;
let totalPages = 0;
let editingId = null;
let currentBranchId = null;
let debtMap = new Map();
let listAbortController = null;

const $ = (id) => document.getElementById(id);

const money = (value) => new Intl.NumberFormat("vi-VN", {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0
}).format(Number(value || 0));

function debounce(fn, delayMs = 350) {
    let timer = null;
    return (...args) => {
        if (timer) clearTimeout(timer);
        timer = setTimeout(() => fn(...args), delayMs);
    };
}

function showMessage(message, type = "") {
    const el = $("message");
    if (!el) return;
    el.textContent = message;
    el.className = `message ${type}`;
    el.hidden = !message;
}

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

function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
        "&": "&amp;",
        "<": "&lt;",
        ">": "&gt;",
        '"': "&quot;",
        "'": "&#39;"
    })[char]);
}

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

async function loadSuppliers() {
    if (listAbortController) {
        try { listAbortController.abort(); } catch { /* ignore */ }
    }
    listAbortController = new AbortController();
    const signal = listAbortController.signal;

    $("supplierTable").innerHTML =
        '<tr><td colspan="8" class="empty">Đang tải dữ liệu...</td></tr>';

    const kw = $("keyword").value.trim();
    const params = new URLSearchParams({
        keyword: kw,
        page: currentPage,
        size: PAGE_SIZE,
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

        totalPages = Number(data?.totalPages || 0);
        $("totalCount").textContent = `${Number(data?.totalElements || 0)} nhà cung cấp`;

        $("pageInfo").textContent =
            totalPages === 0
                ? "Trang 0 / 0"
                : `Trang ${currentPage + 1} / ${totalPages}`;

        $("prevBtn").disabled = currentPage <= 0;
        $("nextBtn").disabled = totalPages === 0 || currentPage >= totalPages - 1;

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

function closeForm() {
    $("modal").hidden = true;
}

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

function setFieldError(field, msg) {
    const el = $(`${field}Error`);
    if (el) el.textContent = msg;
}

function validateSupplierPayload(payload) {
    let valid = true;
    clearFieldErrors();

    if (!payload.supplierCode) {
        setFieldError("supplierCode", "Mã nhà cung cấp không được để trống.");
        valid = false;
    } else if (!REGEX.SUPPLIER_CODE.test(payload.supplierCode)) {
        setFieldError("supplierCode", "Mã NCC từ 2-20 ký tự, chỉ gồm chữ, số, dấu - hoặc _.");
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

function showValidationErrors(error) {
    clearFieldErrors();
    if (error.fieldErrors && typeof error.fieldErrors === "object") {
        Object.entries(error.fieldErrors).forEach(([field, msg]) => {
            setFieldError(field, msg);
        });
    }
    showMessage(error.message, "error");
}

$("addBtn").addEventListener("click", () => openForm());
$("closeBtn").addEventListener("click", closeForm);
$("cancelBtn").addEventListener("click", closeForm);

if ($("closePayModalBtn")) $("closePayModalBtn").addEventListener("click", () => { $("supplierPayModal").hidden = true; });
if ($("cancelPayModalBtn")) $("cancelPayModalBtn").addEventListener("click", () => { $("supplierPayModal").hidden = true; });

if ($("payReceiptSelect")) {
    $("payReceiptSelect").addEventListener("change", (e) => {
        const opt = e.target.options[e.target.selectedIndex];
        if (opt?.dataset?.debt) {
            $("payAmountInput").value = Number(opt.dataset.debt);
        }
    });
}

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

$("modal").addEventListener("click", (e) => {
    if (e.target === $("modal")) closeForm();
});

document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
        if (!$("modal").hidden) closeForm();
        if ($("supplierPayModal") && !$("supplierPayModal").hidden) $("supplierPayModal").hidden = true;
    }
});

if ($("branchSelect")) {
    $("branchSelect").addEventListener("change", () => {
        currentBranchId = Number($("branchSelect").value) || null;
        loadSuppliers();
    });
}

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

$("prevBtn").addEventListener("click", () => {
    if (currentPage > 0) {
        currentPage--;
        loadSuppliers();
    }
});

$("nextBtn").addEventListener("click", () => {
    if (currentPage < totalPages - 1) {
        currentPage++;
        loadSuppliers();
    }
});

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

initBranches();
