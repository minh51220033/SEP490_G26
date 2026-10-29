(() => {
    const api = '/portal/appointments/api';
    const $ = id => document.getElementById(id);
    const sessionLabel = {MORNING: 'Buổi sáng', AFTERNOON: 'Buổi chiều', EVENING: 'Buổi tối'};
    const statusLabel = {PENDING: 'Chờ lễ tân xác nhận', CONFIRMED: 'Đã xác nhận', CHECKED_IN: 'Đã đến',
        COMPLETED: 'Đã hoàn thành', CANCELLED: 'Đã hủy', NO_SHOW: 'Không đến'};
    const statusClass = {PENDING: 'bg-warning text-dark', CONFIRMED: 'bg-primary', CHECKED_IN: 'bg-info text-dark',
        COMPLETED: 'bg-success', CANCELLED: 'bg-secondary', NO_SHOW: 'bg-danger'};

    async function request(path, options = {}) {
        const response = await fetch(api + path, {
            credentials: 'same-origin',
            headers: {'Content-Type': 'application/json', ...(options.headers || {})},
            ...options
        });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.detail || body.message || body.error || `Không thể xử lý yêu cầu (${response.status}).`);
        return body;
    }
    function error(message) {
        const el = $('pageError');
        if (!el) return;
        el.textContent = message;
        el.hidden = false;
        el.scrollIntoView({behavior: 'smooth', block: 'center'});
    }
    function clearError() { if ($('pageError')) $('pageError').hidden = true; }
    function option(value, label) {
        const el = document.createElement('option');
        el.value = value; el.textContent = label;
        return el;
    }
    function cell(row, value) {
        const el = document.createElement('td');
        el.textContent = value == null || value === '' ? '—' : value;
        row.appendChild(el);
        return el;
    }
    function dateLabel(value) {
        if (!value) return '—';
        const [year, month, day] = value.split('-');
        return `${day}/${month}/${year}`;
    }
    function localDate(offset = 0) {
        const d = new Date();
        d.setDate(d.getDate() + offset);
        return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, '0'), String(d.getDate()).padStart(2, '0')].join('-');
    }
    function setSelect(select, entries, placeholder, label) {
        select.replaceChildren(option('', placeholder));
        entries.forEach(entry => select.appendChild(option(entry.id, label(entry))));
    }

    async function listPage() {
        const options = await request('/options');
        const filter = $('profileFilter');
        setSelect(filter, options.profiles, 'Chọn hồ sơ', p => `${p.name} (${p.code})`);
        const selected = new URLSearchParams(location.search).get('profileId');
        filter.value = options.profiles.some(p => String(p.id) === selected) ? selected : String(options.profiles[0]?.id || '');
        async function load() {
            const rows = $('appointmentRows');
            rows.replaceChildren();
            if (!filter.value) {
                const row = document.createElement('tr'); cell(row, 'Bạn chưa có hồ sơ bệnh nhân. Hãy tạo hồ sơ trước khi đặt lịch.').colSpan = 6;
                rows.appendChild(row); return;
            }
            const list = await request(`?profileId=${encodeURIComponent(filter.value)}`);
            if (!list.length) {
                const row = document.createElement('tr'); cell(row, 'Hồ sơ này chưa có lịch hẹn.').colSpan = 6;
                rows.appendChild(row); return;
            }
            list.forEach(a => {
                const row = document.createElement('tr');
                cell(row, a.code);
                cell(row, `${dateLabel(a.date)} · ${sessionLabel[a.session] || a.session}`);
                cell(row, a.branchName);
                cell(row, a.requestedDoctorName || 'Không yêu cầu');
                const status = cell(row, '');
                const badge = document.createElement('span');
                badge.className = `badge ${statusClass[a.status] || 'bg-secondary'}`;
                badge.textContent = statusLabel[a.status] || a.status;
                status.appendChild(badge);
                const action = cell(row, '');
                const link = document.createElement('a');
                link.className = 'btn btn-sm btn-outline-primary'; link.href = `/portal/appointments/${a.id}`;
                link.textContent = 'Xem chi tiết'; action.appendChild(link);
                rows.appendChild(row);
            });
        }
        filter.addEventListener('change', () => load().catch(e => error(e.message)));
        await load();
    }

    async function formPage() {
        const form = $('appointmentForm');
        const id = form.dataset.appointmentId || '';
        const editing = Boolean(id);
        const options = await request('/options');
        setSelect($('profileId'), options.profiles, 'Chọn hồ sơ', p => `${p.name} (${p.code})`);
        setSelect($('branchId'), options.branches, 'Chọn chi nhánh', b => b.name);
        setSelect($('serviceId'), options.services, 'Chưa chọn dịch vụ', s => s.name);
        $('appointmentDate').min = localDate();
        $('appointmentDate').max = localDate(90);
        let current = null;
        if (editing) {
            const detail = await request(`/${id}`);
            current = detail.appointment;
            $('profileId').value = current.profileId;
            $('branchId').value = current.branchId;
            $('appointmentDate').value = current.date;
            $('preferredSession').value = current.session;
            $('profileId').disabled = true;
            $('branchId').disabled = true;
            $('serviceField').hidden = true;
            $('symptomsSection').hidden = true;
            $('submitAppointment').textContent = 'Lưu lịch mới';
        } else {
            const profileParam = new URLSearchParams(location.search).get('profileId');
            if (profileParam && options.profiles.some(p => String(p.id) === profileParam)) $('profileId').value = profileParam;
        }
        let available = false;
        let sequence = 0;
        async function availability() {
            const token = ++sequence;
            const branchId = $('branchId').value, date = $('appointmentDate').value, session = $('preferredSession').value;
            const doctor = $('doctorId'), message = $('availabilityMessage'), button = $('submitAppointment');
            available = false; button.disabled = true; doctor.disabled = true;
            doctor.replaceChildren(option('', 'Không yêu cầu bác sĩ'));
            message.className = 'appointment-availability';
            if (!branchId || !date || !session) {
                message.textContent = 'Chọn chi nhánh, ngày và buổi để xem khả năng tiếp nhận.';
                return;
            }
            message.textContent = 'Đang kiểm tra khả năng tiếp nhận…';
            const exclude = editing ? `&excludeAppointmentId=${encodeURIComponent(id)}` : '';
            const result = await request(`/availability?branchId=${encodeURIComponent(branchId)}&date=${encodeURIComponent(date)}&sessionPeriod=${encodeURIComponent(session)}${exclude}`);
            if (token !== sequence) return;
            if (!result.available) {
                message.textContent = result.reason || 'Buổi này không còn chỗ.';
                message.classList.add('is-error'); return;
            }
            result.doctors.forEach(d => doctor.appendChild(option(d.id, `${d.name}${d.specialty ? ' · ' + d.specialty : ''} (${d.remaining} chỗ)`)));
            if (current?.requestedDoctorId && result.doctors.some(d => d.id === current.requestedDoctorId))
                doctor.value = String(current.requestedDoctorId);
            doctor.disabled = false;
            available = true; button.disabled = false;
            message.textContent = `Buổi khám còn ${result.remainingBranch} chỗ. Bạn có thể chọn bác sĩ đích danh hoặc để lễ tân điều phối khi đến.`;
            if (current?.requestedDoctorId && !result.doctors.some(d => d.id === current.requestedDoctorId))
                message.textContent += ' Bác sĩ trong lịch cũ không khả dụng ở buổi mới; hãy chọn lại bác sĩ.';
            message.classList.add('is-success');
        }
        ['branchId', 'appointmentDate', 'preferredSession'].forEach(field =>
            $(field).addEventListener('change', () => availability().catch(e => error(e.message))));
        if (editing) await availability();
        let key = sessionStorage.getItem('ft06-booking-key');
        if (!key) { key = crypto.randomUUID(); sessionStorage.setItem('ft06-booking-key', key); }
        form.addEventListener('submit', async event => {
            event.preventDefault(); clearError();
            if (!form.reportValidity() || !available) return;
            const button = $('submitAppointment'); button.disabled = true;
            try {
                const payload = {date: $('appointmentDate').value, session: $('preferredSession').value,
                    doctorId: $('doctorId').value ? Number($('doctorId').value) : null};
                let saved;
                if (editing) {
                    saved = await request(`/${id}/reschedule`, {method: 'PUT', body: JSON.stringify({...payload, reason: 'Bệnh nhân đổi lịch trực tuyến'})});
                } else {
                    saved = await request('', {method: 'POST', body: JSON.stringify({...payload,
                        profileId: Number($('profileId').value), branchId: Number($('branchId').value),
                        serviceId: $('serviceId').value ? Number($('serviceId').value) : null,
                        symptoms: $('symptoms').value.trim() || null, requestKey: key})});
                    sessionStorage.removeItem('ft06-booking-key');
                }
                location.href = `/portal/appointments/${saved.id}?saved=${editing ? 'changed' : 'created'}`;
            } catch (e) { error(e.message); button.disabled = !available; }
        });
    }

    async function detailPage() {
        const id = $('appointmentDetail').dataset.appointmentId;
        const detail = await request(`/${id}`), a = detail.appointment;
        $('appointmentCode').textContent = a.code;
        const saved = new URLSearchParams(location.search).get('saved');
        if (saved) {
            $('successMessage').textContent = saved === 'cancelled' ? 'Đã hủy lịch hẹn.'
                : saved === 'changed' ? 'Đã gửi lịch mới. Lễ tân sẽ xác nhận lại.'
                : 'Đã gửi yêu cầu đặt lịch. Lễ tân sẽ xác nhận.';
            $('successMessage').hidden = false;
        }
        const facts = $('appointmentFacts');
        const items = [
            ['Mã lịch', a.code], ['Bệnh nhân', a.patientName], ['Chi nhánh', a.branchName],
            ['Ngày khám', dateLabel(a.date)], ['Buổi khám', sessionLabel[a.session] || a.session],
            ['Bác sĩ yêu cầu', a.requestedDoctorName || 'Không yêu cầu · lễ tân điều phối khi đến'],
            ['Dịch vụ quan tâm', a.serviceName || 'Chưa chọn'], ['Tình trạng ban đầu', a.symptoms || 'Chưa khai báo'],
            ['Trạng thái', statusLabel[a.status] || a.status], ['Số lần đổi lịch', String(a.rescheduleCount)],
            ['Lý do hủy', a.cancelReason || '—']
        ];
        items.forEach(([label, value]) => {
            const box = document.createElement('div'), heading = document.createElement('span'), content = document.createElement('strong');
            heading.textContent = label; content.textContent = value; box.append(heading, content); facts.appendChild(box);
        });
        const history = $('appointmentHistory');
        detail.history.forEach(h => {
            const item = document.createElement('li');
            item.textContent = `${h.action === 'CREATE' ? 'Đặt lịch' : h.action === 'RESCHEDULE' ? 'Đổi lịch' : 'Hủy lịch'} · ${h.newValue}${h.reason ? ' · ' + h.reason : ''}`;
            history.appendChild(item);
        });
        if (!detail.history.length) {
            const item = document.createElement('li'); item.textContent = 'Chưa có thay đổi.'; history.appendChild(item);
        }
        if (a.status === 'PENDING' || a.status === 'CONFIRMED') {
            $('appointmentActions').hidden = false;
            $('changeLink').href = `/portal/appointments/${id}/change`;
        }
        $('cancelButton').addEventListener('click', () => { $('cancelForm').hidden = !$('cancelForm').hidden; });
        $('cancelForm').addEventListener('submit', async event => {
            event.preventDefault(); clearError();
            const reason = $('cancelReason').value;
            const note = $('cancelNote').value.trim();
            if (!reason || reason === 'Khác' && !note) { error('Vui lòng chọn hoặc ghi rõ lý do hủy.'); return; }
            try {
                await request(`/${id}/cancel`, {method: 'POST', body: JSON.stringify({reason: note ? `${reason}: ${note}` : reason})});
                location.href = `/portal/appointments/${id}?saved=cancelled`;
            } catch (e) { error(e.message); }
        });
    }

    const task = $('appointmentRows') ? listPage() : $('appointmentForm') ? formPage()
        : $('appointmentDetail') ? detailPage() : Promise.resolve();
    task.catch(e => error(e.message));
})();
