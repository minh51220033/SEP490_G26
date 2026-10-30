document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('portalRecordForm');
    if (!form) return;
    const id = form.dataset.recordId || null;
    const error = document.getElementById('formError');
    const button = document.getElementById('saveButton');
    const modal = bootstrap.Modal.getOrCreateInstance(document.getElementById('duplicateModal'));

    function values(confirmDuplicate = false) {
        const data = new FormData(form);
        const read = name => (data.get(name) || '').trim();
        return {
            fullName: read('fullName'), dob: read('dob'), gender: read('gender'),
            phoneNumber: read('phoneNumber'), address: read('address'),
            branchId: Number(read('branchId')), guardianName: read('guardianName'),
            guardianPhone: read('guardianPhone'),
            guardianRelationship: read('guardianRelationship'), idCardNumber: read('idCardNumber'),
            healthInsuranceNumber: read('healthInsuranceNumber'), confirmDuplicate
        };
    }

    function showError(message) {
        error.textContent = message;
        error.hidden = false;
        error.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    async function save(confirmDuplicate) {
        button.disabled = true;
        try {
            const response = await fetch(id ? `/portal/patients/${encodeURIComponent(id)}` : '/portal/patients', {
                method: id ? 'PATCH' : 'POST', headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin', body: JSON.stringify(values(confirmDuplicate))
            });
            if (!response.ok) {
                const body = await response.json().catch(() => ({}));
                throw new Error(body.detail || body.message || 'Không thể lưu hồ sơ. Vui lòng kiểm tra lại dữ liệu.');
            }
            const record = await response.json();
            const avatar = form.querySelector('#avatar')?.files?.[0];
            if (avatar) {
                const upload = new FormData();
                upload.append('avatar', avatar);
                const uploaded = await fetch(`/portal/patients/${encodeURIComponent(record.id)}/avatar`, {
                    method: 'POST', credentials: 'same-origin', body: upload
                });
                if (!uploaded.ok) throw new Error('Đã lưu thông tin nhưng không thể tải ảnh đại diện. Vui lòng thử lại.');
            }
            window.location.assign(`/portal/records/${encodeURIComponent(record.id)}?saved=1`);
        } catch (cause) { showError(cause.message); }
        finally { button.disabled = false; }
    }

    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (!form.reportValidity()) return;
        error.hidden = true;
        button.disabled = true;
        try {
            const data = values();
            const query = new URLSearchParams({ fullName: data.fullName, dob: data.dob,
                phoneNumber: data.phoneNumber });
            if (id) query.set('excludeId', id);
            const response = await fetch(`/portal/patients/duplicate-candidates?${query}`, { credentials: 'same-origin' });
            if (!response.ok) throw new Error('Không thể kiểm tra hồ sơ trùng. Vui lòng thử lại.');
            const result = await response.json();
            if (result.duplicate) modal.show(); else await save(false);
        } catch (cause) { showError(cause.message); }
        finally { button.disabled = false; }
    });

    document.getElementById('confirmDuplicate').addEventListener('click', async () => {
        modal.hide();
        await save(true);
    });
});
