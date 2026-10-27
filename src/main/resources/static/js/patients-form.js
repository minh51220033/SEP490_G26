document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('patientForm');
    if (!form) return;
    const id = form.dataset.patientId || null;
    const error = document.getElementById('formError');
    const saveButton = document.getElementById('saveButton');
    const modalElement = document.getElementById('duplicateModal');
    const modal = bootstrap.Modal.getOrCreateInstance(modalElement);
    const rows = document.getElementById('duplicateRows');
    const dobInput = document.getElementById('dob');
    const guardianNotice = document.getElementById('guardianNotice');
    const guardianFields = ['guardianName', 'guardianPhone', 'guardianRelationship']
        .map(name => form.elements.namedItem(name));

    function localIsoDate(date) {
        const pad = value => String(value).padStart(2, '0');
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
    }

    function updateGuardianRequirement() {
        dobInput.max = localIsoDate(new Date());
        if (!dobInput.value) {
            dobInput.setCustomValidity('');
            guardianFields.forEach(field => field.required = false);
            guardianNotice.hidden = true;
            return;
        }
        const birthDate = new Date(`${dobInput.value}T00:00:00`);
        const today = new Date();
        today.setHours(0, 0, 0, 0);
        dobInput.setCustomValidity(birthDate > today ? 'Ngày sinh không được lớn hơn ngày hiện tại.' : '');
        const adultDate = new Date(birthDate);
        adultDate.setFullYear(adultDate.getFullYear() + 18);
        const minor = adultDate > today;
        guardianFields.forEach(field => field.required = minor);
        guardianNotice.hidden = !minor;
    }

    dobInput.addEventListener('change', updateGuardianRequirement);
    updateGuardianRequirement();

    function values(confirmDuplicate = false) {
        const data = new FormData(form);
        const read = name => String(data.get(name) || '').trim();
        return {
            fullName: read('fullName'),
            dob: data.get('dob'),
            gender: data.get('gender'),
            phoneNumber: read('phoneNumber'),
            address: read('address'),
            branchId: Number(data.get('branchId')),
            guardianName: read('guardianName'),
            guardianPhone: read('guardianPhone'),
            guardianRelationship: read('guardianRelationship'),
            idCardNumber: read('idCardNumber'),
            healthInsuranceNumber: read('healthInsuranceNumber'),
            confirmDuplicate
        };
    }

    function showError(message) {
        error.textContent = message;
        error.hidden = false;
        error.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    function displayCandidates(candidates) {
        const current = values();
        document.getElementById('currentCandidate').textContent =
            `${current.fullName} · ${current.dob} · ${current.phoneNumber}`;
        rows.replaceChildren();
        for (const candidate of candidates) {
            const tr = document.createElement('tr');
            for (const value of [candidate.code, candidate.fullName, candidate.dob || '—',
                candidate.phoneNumber, candidate.matchedFields.join(', ')]) {
                const td = document.createElement('td');
                td.textContent = value;
                tr.append(td);
            }
            const linkCell = document.createElement('td');
            const link = document.createElement('a');
            link.href = `/reception/patients/${encodeURIComponent(candidate.id)}`;
            link.target = '_blank';
            link.rel = 'noopener';
            link.textContent = 'Xem hồ sơ';
            linkCell.append(link);
            tr.append(linkCell);
            rows.append(tr);
        }
        modal.show();
    }

    async function save(confirmDuplicate) {
        error.hidden = true;
        saveButton.disabled = true;
        try {
            const response = await fetch(id ? `/patients/${encodeURIComponent(id)}` : '/patients', {
                method: id ? 'PATCH' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                body: JSON.stringify(values(confirmDuplicate))
            });
            if (!response.ok) {
                const body = await response.json().catch(() => ({}));
                throw new Error(body.detail || body.message ||
                    (response.status === 409 ? 'Có hồ sơ tương tự. Vui lòng kiểm tra lại.' : 'Không thể lưu hồ sơ. Vui lòng kiểm tra dữ liệu.'));
            }
            const patient = await response.json();
            window.location.assign(`/reception/patients/${encodeURIComponent(patient.id)}?saved=1`);
        } catch (cause) {
            showError(cause.message);
        } finally {
            saveButton.disabled = false;
        }
    }

    form.addEventListener('submit', async event => {
        event.preventDefault();
        updateGuardianRequirement();
        if (!form.reportValidity()) return;
        error.hidden = true;
        saveButton.disabled = true;
        try {
            const data = values();
            const query = new URLSearchParams({ fullName: data.fullName, dob: data.dob,
                phoneNumber: data.phoneNumber });
            if (id) query.set('excludeId', id);
            const response = await fetch(`/patients/duplicate-candidates?${query}`, { credentials: 'same-origin' });
            if (!response.ok) throw new Error('Không thể kiểm tra hồ sơ trùng. Vui lòng thử lại.');
            const candidates = await response.json();
            if (candidates.length) displayCandidates(candidates);
            else await save(false);
        } catch (cause) {
            showError(cause.message);
        } finally {
            saveButton.disabled = false;
        }
    });

    document.getElementById('confirmDuplicate').addEventListener('click', async () => {
        modal.hide();
        await save(true);
    });
});
