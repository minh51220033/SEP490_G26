const API = "/api/suppliers";
const PAGE_SIZE = 10;

const REGEX = {
    SUPPLIER_CODE: /^[A-Za-z0-9_-]{2,20}$/,
    TAX_CODE: /^[0-9]{10}(-[0-9]{3})?$/,
    PHONE: /^(0|\+84)[0-9]{9,10}$/
};

let currentPage = 0;
let totalPages = 0;
let editingId = null;

const $ = (id) => document.getElementById(id);

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

async function loadSuppliers() {
    $("supplierTable").innerHTML =
        '<tr><td colspan="7" class="empty">Đang tải dữ liệu...</td></tr>';

    const params = new URLSearchParams({
        keyword: $("keyword").value.trim(),
        page: currentPage,
        size: PAGE_SIZE,
        sortBy: "supplierName",
        direction: "asc"
    });

    if ($("active").value !== "") {
        params.set("active", $("active").value);
    }

    try {
        const data = await request(`${API}?${params.toString()}`);

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
                '<tr><td colspan="7" class="empty">Không tìm thấy nhà cung cấp phù hợp.</td></tr>';
            return;
        }

        $("supplierTable").innerHTML = data.content.map((s) => `
            <tr>
                <td><span class="code-badge">${escapeHtml(s.supplierCode)}</span></td>
                <td><strong>${escapeHtml(s.supplierName)}</strong></td>
                <td>${escapeHtml(s.taxCode || "—")}</td>
                <td>${escapeHtml(s.phoneNumber || "—")}</td>
                <td>${escapeHtml(s.address || "—")}</td>
                <td>
                    <span class="status ${s.isActive ? "status-active" : "status-inactive"}">
                        ${s.isActive ? "Đang hoạt động" : "Ngừng hoạt động"}
                    </span>
                </td>
                <td>
                    <button type="button" class="action-btn" data-action="edit" data-id="${s.supplierId}">
                        Sửa
                    </button>
                    <button type="button" class="action-btn ${s.isActive ? "danger" : ""}"
                        data-action="${s.isActive ? "deactivate" : "activate"}"
                        data-id="${s.supplierId}"
                        data-name="${escapeHtml(s.supplierName)}">
                        ${s.isActive ? "Ngừng hoạt động" : "Kích hoạt"}
                    </button>
                </td>
            </tr>
        `).join("");
    } catch (error) {
        $("supplierTable").innerHTML =
            `<tr><td colspan="7" class="empty">${escapeHtml(error.message || "Không thể tải dữ liệu.")}</td></tr>`;
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

$("modal").addEventListener("click", (e) => {
    if (e.target === $("modal")) closeForm();
});

document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && !$("modal").hidden) {
        closeForm();
    }
});

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

loadSuppliers();
