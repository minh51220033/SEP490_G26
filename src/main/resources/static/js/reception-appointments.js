(() => {
    const api = '/reception/appointments/api';
    const $ = id => document.getElementById(id);
    const sessionName = {MORNING: 'Sáng', AFTERNOON: 'Chiều', EVENING: 'Tối', ANY: 'Chưa chốt'};
    const statusName = {PENDING: 'Chờ xác nhận', CONFIRMED: 'Đã xác nhận', CHECKED_IN: 'Đã đến',
        COMPLETED: 'Hoàn thành', CANCELLED: 'Đã hủy', NO_SHOW: 'Không đến'};
    const statusStyle = {PENDING: 'bg-warning text-dark', CONFIRMED: 'bg-primary', CHECKED_IN: 'bg-info text-dark',
        COMPLETED: 'bg-success', CANCELLED: 'bg-secondary', NO_SHOW: 'bg-danger'};
    const today = () => new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Bangkok'}).format(new Date());
    const fromDate = value => new Date(`${value}T00:00:00Z`);
    const iso = date => date.toISOString().slice(0, 10);
    const addDays = (date, count) => { const next = new Date(date); next.setUTCDate(next.getUTCDate() + count); return next; };
    const dateLabel = value => value ? value.split('-').reverse().join('/') : '—';
    const option = (value, label) => { const el = document.createElement('option'); el.value = value; el.textContent = label; return el; };
    const cell = (row, value) => { const el = document.createElement('td'); el.textContent = value == null || value === '' ? '—' : value; row.appendChild(el); return el; };

    async function request(path, config = {}) {
        const response = await fetch(api + path, {credentials: 'same-origin',
            headers: {'Content-Type': 'application/json', ...(config.headers || {})}, ...config});
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.message || body.detail || body.error || `Không thể xử lý (${response.status}).`);
        return body;
    }
    function error(message) {
        const el = $('pageError'); if (!el) return;
        el.textContent = message; el.hidden = false; el.scrollIntoView({behavior: 'smooth', block: 'center'});
    }
    function clearError() { if ($('pageError')) $('pageError').hidden = true; }
    function branches(select, options, all = false) {
        select.replaceChildren();
        if (all && options.branches.length > 1) select.appendChild(option('', 'Tất cả chi nhánh'));
        else select.appendChild(option('', 'Chọn chi nhánh'));
        options.branches.forEach(b => select.appendChild(option(b.id, b.name)));
        if (options.branches.length === 1) select.value = String(options.branches[0].id);
    }
    function badge(a, target) {
        const el = document.createElement('span'); el.className = `badge ${statusStyle[a.status] || 'bg-secondary'}`;
        el.textContent = statusName[a.status] || a.status; target.appendChild(el);
    }
    function detailLink(a, target) {
        const link = document.createElement('a'); link.href = `/reception/appointments/${a.id}`;
        link.className = 'btn btn-sm btn-outline-primary'; link.textContent = 'Chi tiết'; target.appendChild(link);
    }

    async function dashboardPage() {
        const options = await request('/options');
        const date = $('filterDate'), branch = $('filterBranch'), doctor = $('filterDoctor');
        date.value = new URLSearchParams(location.search).get('date') || today();
        branches(branch, options, true);
        const branchParam = new URLSearchParams(location.search).get('branchId');
        if (branchParam && options.branches.some(b => String(b.id) === branchParam)) branch.value = branchParam;
        async function loadDoctors() {
            doctor.replaceChildren(option('', 'Tất cả bác sĩ'));
            if (!branch.value) { doctor.disabled = true; return; }
            doctor.disabled = false;
            const list = await request(`/doctors?branchId=${encodeURIComponent(branch.value)}&date=${date.value}`);
            list.forEach(d => doctor.appendChild(option(d.id, d.name)));
        }
        async function load() {
            clearError();
            const params = new URLSearchParams({date: date.value});
            if (branch.value) params.set('branchId', branch.value);
            if (doctor.value) params.set('doctorId', doctor.value);
            const data = await request(`/dashboard?${params}`);
            const metrics = [['Tổng lịch', data.total], ['Chờ xác nhận', data.pending],
                ['Đã xác nhận', data.confirmed], ['Đã đến', data.checkedIn], ['Không đến', data.noShow]];
            $('appointmentKpis').replaceChildren(...metrics.map(([label, value]) => {
                const box = document.createElement('div'), name = document.createElement('span'), count = document.createElement('strong');
                box.className = 'appointment-kpi'; name.textContent = label; count.textContent = value;
                box.append(name, count); return box;
            }));
            const rows = $('appointmentRows'); rows.replaceChildren();
            if (!data.appointments.length) {
                const row = document.createElement('tr'); cell(row, 'Không có lịch hẹn trong ngày này.').colSpan = 7; rows.appendChild(row); return;
            }
            data.appointments.forEach(a => {
                const row = document.createElement('tr'); cell(row, a.code);
                cell(row, `${a.patientName} · ${a.patientCode}`); cell(row, sessionName[a.session] || a.session);
                cell(row, a.doctorName ? `${a.doctorName}${a.doctorUnavailable ? ' ⚠' : ''}` : 'Không yêu cầu');
                cell(row, a.source === 'ONLINE' ? 'Trực tuyến' : 'Lễ tân');
                badge(a, cell(row, '')); detailLink(a, cell(row, '')); rows.appendChild(row);
            });
        }
        branch.addEventListener('change', async () => { try { await loadDoctors(); await load(); } catch (e) { error(e.message); } });
        date.addEventListener('change', async () => { try { await loadDoctors(); await load(); } catch (e) { error(e.message); } });
        doctor.addEventListener('change', () => load().catch(e => error(e.message)));
        await loadDoctors(); await load();
    }

    async function calendarPage() {
        const options = await request('/options');
        const branch = $('calendarBranch'), view = $('calendarView'), date = $('calendarDate');
        branches(branch, options, true); date.value = today();
        async function load() {
            const reference = fromDate(date.value), month = view.value === 'month';
            const start = month ? new Date(Date.UTC(reference.getUTCFullYear(), reference.getUTCMonth(), 1)) : new Date(reference);
            const dayOfWeek = (start.getUTCDay() + 6) % 7;
            const first = addDays(start, -dayOfWeek), last = addDays(first, month ? 41 : 6);
            const params = new URLSearchParams({start: iso(first), end: iso(last)});
            if (branch.value) params.set('branchId', branch.value);
            const list = await request(`/calendar?${params}`);
            const counts = new Map();
            list.forEach(a => { const value = counts.get(a.date) || {total: 0, pending: 0};
                value.total++; if (a.status === 'PENDING') value.pending++; counts.set(a.date, value); });
            $('calendarTitle').textContent = month ? `Tháng ${reference.getUTCMonth() + 1}/${reference.getUTCFullYear()}`
                : `${dateLabel(iso(first))} – ${dateLabel(iso(last))}`;
            const grid = $('calendarGrid'); grid.replaceChildren();
            ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'].forEach(label => {
                const el = document.createElement('div'); el.className = 'appointment-calendar-header'; el.textContent = label; grid.appendChild(el);
            });
            for (let i = 0; i < (month ? 42 : 7); i++) {
                const current = addDays(first, i), key = iso(current), count = counts.get(key);
                const link = document.createElement('a'); link.className = 'appointment-day';
                if (month && current.getUTCMonth() !== reference.getUTCMonth()) link.classList.add('is-outside');
                if (key === today()) link.classList.add('is-today');
                link.href = `/reception/appointments?date=${key}${branch.value ? '&branchId=' + encodeURIComponent(branch.value) : ''}`;
                const day = document.createElement('strong'); day.textContent = current.getUTCDate(); link.appendChild(day);
                const summary = document.createElement('span'); summary.textContent = count ? `${count.total} lịch · ${count.pending} chờ` : 'Chưa có lịch';
                link.appendChild(summary); grid.appendChild(link);
            }
        }
        $('calendarPrevious').addEventListener('click', () => { const d = fromDate(date.value);
            if (view.value === 'month') { d.setUTCDate(1); d.setUTCMonth(d.getUTCMonth() - 1); }
            else d.setUTCDate(d.getUTCDate() - 7);
            date.value = iso(d); load().catch(e => error(e.message)); });
        $('calendarNext').addEventListener('click', () => { const d = fromDate(date.value);
            if (view.value === 'month') { d.setUTCDate(1); d.setUTCMonth(d.getUTCMonth() + 1); }
            else d.setUTCDate(d.getUTCDate() + 7);
            date.value = iso(d); load().catch(e => error(e.message)); });
        [branch, view, date].forEach(el => el.addEventListener('change', () => load().catch(e => error(e.message))));
        await load();
    }

    async function formPage() {
        const form = $('appointmentForm'), id = form.dataset.appointmentId || '', editing = Boolean(id);
        const options = await request('/options');
        branches($('branchId'), options);
        $('serviceId').replaceChildren(option('', 'Chưa chọn dịch vụ'));
        options.services.forEach(s => $('serviceId').appendChild(option(s.id, s.name)));
        $('appointmentDate').min = today();
        $('appointmentDate').max = iso(addDays(fromDate(today()), 90));
        let current = null;
        if (editing) {
            current = (await request(`/${id}`)).appointment;
            $('branchId').value = String(current.branchId); $('branchId').disabled = true;
            $('patientSearchBox').hidden = true; $('profileId').disabled = true;
            $('existingPatientBox').hidden = false; $('existingPatientName').textContent = `${current.patientName} (${current.patientCode})`;
            $('appointmentDate').value = current.date; $('preferredSession').value = current.session;
            $('serviceId').value = current.serviceId || ''; $('symptoms').value = current.symptoms || '';
            $('updateReasonBox').hidden = false; $('updateReason').required = true;
            $('submitAppointment').textContent = 'Lưu thay đổi';
        } else {
            const branchParam = new URLSearchParams(location.search).get('branchId');
            if (branchParam && options.branches.some(b => String(b.id) === branchParam)) $('branchId').value = branchParam;
        }
        let patientSeq = 0, availabilitySeq = 0, available = false;
        async function findPatient() {
            const token = ++patientSeq, query = $('patientSearch').value.trim(), branchId = $('branchId').value;
            const select = $('profileId'); select.replaceChildren(option('', 'Chọn hồ sơ từ kết quả tìm kiếm'));
            if (query.length < 2 || !branchId) return;
            const list = await request(`/patients?branchId=${encodeURIComponent(branchId)}&query=${encodeURIComponent(query)}`);
            if (token !== patientSeq) return;
            list.forEach(p => select.appendChild(option(p.id, `${p.name} (${p.code}) · ${p.phone}`)));
            if (!list.length) select.replaceChildren(option('', 'Không tìm thấy hồ sơ phù hợp'));
        }
        let debounce;
        $('patientSearch').addEventListener('input', () => { clearTimeout(debounce);
            debounce = setTimeout(() => findPatient().catch(e => error(e.message)), 250); });
        async function availability() {
            const token = ++availabilitySeq, branchId = $('branchId').value,
                date = $('appointmentDate').value, session = $('preferredSession').value;
            const doctor = $('doctorId'), message = $('availabilityMessage'), button = $('submitAppointment');
            available = false; button.disabled = true; doctor.disabled = true;
            doctor.replaceChildren(option('', 'Không yêu cầu đích danh'));
            message.className = 'appointment-availability';
            if (!branchId || !date || !session) { message.textContent = 'Chọn chi nhánh, ngày và buổi để xem khả năng tiếp nhận.'; return; }
            message.textContent = 'Đang kiểm tra khả năng tiếp nhận…';
            const exclude = editing ? `&excludeAppointmentId=${encodeURIComponent(id)}` : '';
            const result = await request(`/availability?branchId=${encodeURIComponent(branchId)}&date=${date}&sessionPeriod=${session}${exclude}`);
            if (token !== availabilitySeq) return;
            if (!result.available) { message.textContent = result.reason || 'Buổi khám không còn chỗ.'; message.classList.add('is-error'); return; }
            result.doctors.forEach(d => doctor.appendChild(option(d.id, `${d.name}${d.specialty ? ' · ' + d.specialty : ''} (${d.remaining} chỗ)`)));
            if (current?.doctorId && result.doctors.some(d => d.id === current.doctorId)) doctor.value = String(current.doctorId);
            doctor.disabled = false; available = true; button.disabled = false;
            message.textContent = `Buổi khám còn ${result.remainingBranch} chỗ. Bác sĩ đích danh là yêu cầu; phân công thực tế sau check-in.`;
            if (current?.doctorId && !result.doctors.some(d => d.id === current.doctorId))
                message.textContent += ' Bác sĩ cũ không khả dụng, vui lòng chọn lại.';
            message.classList.add('is-success');
        }
        $('branchId').addEventListener('change', () => { ++patientSeq; $('profileId').replaceChildren(option('', 'Chọn hồ sơ từ kết quả tìm kiếm'));
            availability().catch(e => error(e.message)); if ($('patientSearch').value.trim().length >= 2) findPatient().catch(e => error(e.message)); });
        ['appointmentDate', 'preferredSession'].forEach(field => $(field).addEventListener('change', () => availability().catch(e => error(e.message))));
        if (editing) await availability();
        form.addEventListener('submit', async event => {
            event.preventDefault(); clearError(); if (!form.reportValidity() || !available) return;
            const button = $('submitAppointment'); button.disabled = true;
            const payload = {date: $('appointmentDate').value, session: $('preferredSession').value,
                doctorId: $('doctorId').value ? Number($('doctorId').value) : null,
                serviceId: $('serviceId').value ? Number($('serviceId').value) : null,
                symptoms: $('symptoms').value.trim() || null};
            try {
                const saved = editing ? await request(`/${id}`, {method: 'PUT', body: JSON.stringify({...payload, reason: $('updateReason').value.trim()})})
                    : await request('', {method: 'POST', body: JSON.stringify({...payload,
                        branchId: Number($('branchId').value), profileId: Number($('profileId').value)})});
                location.href = `/reception/appointments/${saved.id}?saved=${editing ? 'updated' : 'created'}`;
            } catch (e) { error(e.message); button.disabled = !available; }
        });
    }

    function readableSnapshot(value) {
        if (!value) return '';
        const [date, session, doctor, status] = value.split('|');
        return `${dateLabel(date)} · ${sessionName[session] || session} · ${doctor || ''} · ${status || ''}`;
    }
    async function detailPage() {
        const id = $('appointmentDetail').dataset.appointmentId;
        const detail = await request(`/${id}`), a = detail.appointment;
        $('appointmentCode').textContent = a.code;
        const saved = new URLSearchParams(location.search).get('saved');
        if (saved) { $('successMessage').textContent = saved === 'confirmed' ? 'Đã xác nhận lịch hẹn.'
            : saved === 'cancelled' ? 'Đã hủy lịch hẹn.' : saved === 'updated' ? 'Đã lưu thay đổi.' : 'Đã tạo lịch hẹn.';
            $('successMessage').hidden = false; }
        $('doctorWarning').hidden = !a.doctorUnavailable;
        const facts = [['Mã lịch', a.code], ['Bệnh nhân', `${a.patientName} (${a.patientCode})`],
            ['Số điện thoại', a.patientPhone], ['Chi nhánh', a.branchName], ['Ngày khám', dateLabel(a.date)],
            ['Buổi khám', sessionName[a.session] || a.session], ['Bác sĩ yêu cầu', a.doctorName || 'Không yêu cầu đích danh'],
            ['Dịch vụ quan tâm', a.serviceName || 'Chưa chọn'], ['Tình trạng ban đầu', a.symptoms || 'Chưa khai báo'],
            ['Nguồn', a.source === 'ONLINE' ? 'Trực tuyến' : 'Lễ tân'], ['Trạng thái', statusName[a.status] || a.status],
            ['Số lần đổi lịch', a.rescheduleCount], ['Lý do hủy', a.cancelReason || '—']];
        facts.forEach(([name, value]) => { const box = document.createElement('div'), label = document.createElement('span'), content = document.createElement('strong');
            label.textContent = name; content.textContent = value; box.append(label, content); $('appointmentFacts').appendChild(box); });
        detail.history.forEach(h => { const item = document.createElement('li');
            const action = {CREATE: 'Tạo lịch', UPDATE: 'Sửa lịch', RESCHEDULE: 'Đổi lịch', CONFIRM: 'Xác nhận', CANCEL: 'Hủy lịch'}[h.action] || h.action;
            item.textContent = `${action} · ${readableSnapshot(h.newValue)}${h.reason ? ' · ' + h.reason : ''}`;
            $('appointmentHistory').appendChild(item); });
        if (!detail.history.length) { const item = document.createElement('li'); item.textContent = 'Chưa có thay đổi.'; $('appointmentHistory').appendChild(item); }
        if (a.status !== 'PENDING' && a.status !== 'CONFIRMED') return;
        $('appointmentActions').hidden = false; $('editLink').href = `/reception/appointments/${id}/edit`;
        if (a.status === 'PENDING') $('confirmButton').hidden = false;
        $('confirmButton').addEventListener('click', async () => {
            $('confirmBox').hidden = !$('confirmBox').hidden; $('cancelForm').hidden = true;
            if ($('confirmBox').hidden) return;
            const select = $('confirmDoctor'); select.replaceChildren(option('', 'Không yêu cầu đích danh'));
            try { const result = await request(`/availability?branchId=${a.branchId}&date=${a.date}&sessionPeriod=${a.session}&excludeAppointmentId=${id}`);
                result.doctors.forEach(d => select.appendChild(option(d.id, d.name)));
                if (a.doctorId && result.doctors.some(d => d.id === a.doctorId)) select.value = String(a.doctorId);
                if (!result.available) error(result.reason || 'Buổi đã đầy.');
            } catch (e) { error(e.message); } });
        $('confirmSubmit').addEventListener('click', async () => { clearError(); try {
            await request(`/${id}/confirm`, {method: 'POST', body: JSON.stringify({doctorId: $('confirmDoctor').value ? Number($('confirmDoctor').value) : null})});
            location.href = `/reception/appointments/${id}?saved=confirmed`;
        } catch (e) { error(e.message); } });
        $('cancelButton').addEventListener('click', () => { $('cancelForm').hidden = !$('cancelForm').hidden; $('confirmBox').hidden = true; });
        $('cancelForm').addEventListener('submit', async event => { event.preventDefault(); clearError();
            const reason = $('cancelReason').value, note = $('cancelNote').value.trim();
            if (!reason || reason === 'Khác' && !note) { error('Vui lòng chọn hoặc ghi rõ lý do hủy.'); return; }
            try { await request(`/${id}/cancel`, {method: 'POST', body: JSON.stringify({reasonCode: reason, note: note || null})});
                location.href = `/reception/appointments/${id}?saved=cancelled`;
            } catch (e) { error(e.message); } });
    }

    async function noShowsPage() {
        const options = await request('/options'), branch = $('filterBranch'); branches(branch, options, true);
        async function load() {
            const list = await request(`/no-shows${branch.value ? '?branchId=' + encodeURIComponent(branch.value) : ''}`);
            const rows = $('noShowRows'); rows.replaceChildren();
            if (!list.length) { const row = document.createElement('tr'); cell(row, 'Không có khách không đến trong 7 ngày gần đây.').colSpan = 6; rows.appendChild(row); return; }
            list.forEach(a => { const row = document.createElement('tr'); cell(row, `${dateLabel(a.date)} · ${sessionName[a.session] || a.session}`);
                cell(row, a.code); cell(row, a.patientName); cell(row, a.patientPhone); cell(row, a.branchName);
                detailLink(a, cell(row, '')); rows.appendChild(row); });
        }
        branch.addEventListener('change', () => load().catch(e => error(e.message))); await load();
    }

    const task = $('appointmentRows') ? dashboardPage() : $('calendarGrid') ? calendarPage()
        : $('appointmentForm') ? formPage() : $('appointmentDetail') ? detailPage()
        : $('noShowRows') ? noShowsPage() : Promise.resolve();
    task.catch(e => error(e.message));
})();
