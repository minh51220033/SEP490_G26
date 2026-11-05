(() => {
    const api = '/reception/appointments/api';
    const $ = id => document.getElementById(id);
    const sessionName = {MORNING: 'Buổi sáng', AFTERNOON: 'Buổi chiều', EVENING: 'Buổi tối', ANY: 'Chưa chốt'};
    const statusName = {PENDING: 'Chờ xác nhận', CONFIRMED: 'Đã xác nhận', CHECKED_IN: 'Đã đến',
        COMPLETED: 'Hoàn thành', CANCELLED: 'Đã hủy', NO_SHOW: 'Không đến'};
    const statusStyle = {PENDING: 'bg-warning text-dark', CONFIRMED: 'bg-primary', CHECKED_IN: 'bg-info text-dark',
        COMPLETED: 'bg-success', CANCELLED: 'bg-secondary', NO_SHOW: 'bg-danger'};
    const today = () => new Intl.DateTimeFormat('sv-SE', {timeZone: 'Asia/Bangkok'}).format(new Date());
    const fromDate = value => new Date(`${value}T00:00:00Z`);
    const iso = date => date.toISOString().slice(0, 10);
    const addDays = (date, count) => { const next = new Date(date); next.setUTCDate(next.getUTCDate() + count); return next; };
    const dateLabel = value => value ? value.split('-').reverse().join('/') : '—';
    const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g,
        char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[char]);
    const option = (value, label) => { const el = document.createElement('option'); el.value = value; el.textContent = label; return el; };
    const cell = (row, value) => {
        const el = document.createElement('td');
        if (value !== undefined && value !== null && value !== '') {
            el.textContent = value;
        }
        row.appendChild(el);
        return el;
    };

    function formatSnapshot(raw) {
        if (!raw) return null;
        const parts = raw.split('|');
        if (parts.length < 2) return null;
        const date = dateLabel(parts[0]);
        const session = sessionName[parts[1]] || parts[1];
        let doctorText = 'Không chỉ định bác sĩ';
        let statusText = '';
        let timeText = '';
        for (let i = 2; i < parts.length; i++) {
            const p = parts[i];
            if (p.startsWith('doctor=')) {
                const docVal = p.substring(7);
                if (docVal && docVal !== 'null' && docVal !== 'none') {
                    doctorText = 'Bác sĩ chỉ định';
                }
            } else if (p.startsWith('status=')) {
                const statVal = p.substring(7);
                statusText = statusName[statVal] || statVal;
            } else if (p.startsWith('time=')) {
                timeText = p.substring(5) === 'null' ? '' : p.substring(5).slice(0, 5);
            }
        }
        return { date, session, doctorText, statusText, timeText };
    }

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
        const el = document.createElement('span'); el.className = `badge rounded-pill ${statusStyle[a.status] || 'bg-secondary'}`;
        el.style.padding = '6px 12px';
        el.textContent = statusName[a.status] || a.status; target.appendChild(el);
    }
    function detailLink(a, target) {
        const link = document.createElement('a'); link.href = `/reception/appointments/${a.id}`;
        link.className = 'btn btn-sm btn-outline-primary fw-medium';
        link.innerHTML = '<i class="bi bi-eye me-1"></i>Chi tiết'; target.appendChild(link);
    }

    async function dashboardPage() {
        const options = await request('/options');
        const date = $('filterDate'), branch = $('filterBranch'), doctor = $('filterDoctor'), type = $('filterType');
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
            const metrics = [
                ['Tổng lịch', data.total, 'bi-calendar-check', '#0284c7'],
                ['Chờ xác nhận', data.pending, 'bi-hourglass-split', '#d97706'],
                ['Đã xác nhận', data.confirmed, 'bi-check2-circle', '#2563eb'],
                ['Đã đến', data.checkedIn, 'bi-person-check', '#059669'],
                ['Không đến', data.noShow, 'bi-x-circle', '#dc2626']
            ];
            $('appointmentKpis').replaceChildren(...metrics.map(([label, value, icon, color]) => {
                const box = document.createElement('div');
                box.className = 'appointment-kpi';
                box.innerHTML = `
                    <div class="d-flex align-items-center justify-content-between mb-1">
                        <span class="text-secondary small fw-semibold">${label}</span>
                        <i class="bi ${icon}" style="color: ${color}; font-size: 1.1rem;"></i>
                    </div>
                    <strong style="color: ${color}; font-size: 1.6rem;">${value}</strong>
                `;
                return box;
            }));
            const rows = $('appointmentRows'); rows.replaceChildren();
            let appointments = data.appointments;
            if (type && type.value === 'LH') {
                appointments = appointments.filter(a => !a.code.startsWith('TK'));
            } else if (type && type.value === 'TK') {
                appointments = appointments.filter(a => a.code.startsWith('TK'));
            }
            if (!appointments.length) {
                const row = document.createElement('tr'); cell(row, 'Không có lịch hẹn trong ngày hoặc phù hợp bộ lọc.').colSpan = 7; rows.appendChild(row); return;
            }
            appointments.forEach(a => {
                const row = document.createElement('tr');
                if (a.doctorUnavailable || a.conflictNotes) {
                    row.style.background = '#fffbeb';
                }

                // Column 1: Mã lịch
                const codeTd = cell(row);
                const codeSpan = document.createElement('span');
                codeSpan.className = 'fw-bold text-dark font-monospace';
                codeSpan.textContent = a.code;
                codeTd.appendChild(codeSpan);
                if (a.code.startsWith('TK')) {
                    const tkBadge = document.createElement('span');
                    tkBadge.className = 'badge ms-1.5';
                    tkBadge.style.cssText = 'background: #ede9fe; color: #6d28d9; border: 1px solid #c4b5fd; font-size: 11px;';
                    tkBadge.textContent = 'Tái khám (FT18)';
                    tkBadge.title = 'Lịch hẹn tái khám sau điều trị (FT18)';
                    codeTd.appendChild(tkBadge);
                }

                // Column 2: Bệnh nhân
                const patientTd = cell(row);
                patientTd.innerHTML = `
                    <div class="d-flex align-items-center gap-2">
                        <div class="patient-card-avatar" style="width: 32px; height: 32px; font-size: 13px;">${escapeHtml((a.patientName || 'B').trim().charAt(0).toUpperCase())}</div>
                        <div>
                            <strong class="text-dark d-block">${escapeHtml(a.patientName)}</strong>
                            <span class="badge bg-light text-secondary border font-monospace" style="font-size: 10.5px;">${escapeHtml(a.patientCode)}</span>
                        </div>
                    </div>
                `;

                // Column 3: Buổi
                const sessionTd = cell(row);
                sessionTd.innerHTML = `<span class="badge bg-light text-dark border px-2 py-1">${sessionName[a.session] || a.session}</span>`;
                sessionTd.appendChild(document.createTextNode(` · ${a.expectedTime ? a.expectedTime.slice(0, 5) : 'Chưa chốt giờ'}`));

                // Column 4: Bác sĩ yêu cầu
                const doctorCell = cell(row);
                if (a.doctorName) {
                    doctorCell.innerHTML = `<div><i class="bi bi-person-fill text-primary me-1"></i><span class="fw-medium">${escapeHtml(a.doctorName)}</span>${a.doctorUnavailable ? ' <span class="badge bg-danger text-white ms-1">Vắng</span>' : ''}</div>`;
                } else {
                    doctorCell.innerHTML = `<span class="text-muted fst-italic">Không yêu cầu</span>${a.doctorUnavailable ? ' <span class="badge bg-danger text-white ms-1">Đầy chỗ</span>' : ''}`;
                }
                if (a.conflictNotes) {
                    doctorCell.title = a.conflictNotes;
                    const isCheckedIn = a.status === 'CHECKED_IN';
                    const textClass = isCheckedIn ? 'text-warning-emphasis fw-medium' : 'text-danger';
                    const iconClass = isCheckedIn ? 'bi-info-circle-fill text-warning' : 'bi-exclamation-triangle-fill text-danger';
                    doctorCell.innerHTML += `<small class="${textClass} d-block mt-0.5"><i class="bi ${iconClass} me-0.5"></i>${escapeHtml(a.conflictNotes)}</small>`;
                }

                // Column 5: Nguồn
                const sourceTd = cell(row);
                if (a.code.startsWith('TK')) {
                    sourceTd.innerHTML = '<span class="badge" style="background:#f3e8ff;color:#7e22ce;border:1px solid #d8b4fe;"><i class="bi bi-stethoscope me-1"></i>BS (Tái khám)</span>';
                } else if (a.source === 'ONLINE') {
                    sourceTd.innerHTML = '<span class="badge bg-light text-primary border"><i class="bi bi-globe me-1"></i>Portal</span>';
                } else {
                    sourceTd.innerHTML = '<span class="badge bg-light text-secondary border"><i class="bi bi-person-badge me-1"></i>Lễ tân</span>';
                }

                // Column 6: Trạng thái
                badge(a, cell(row));

                // Column 7: Thao tác
                const actionCell = cell(row);
                actionCell.className = 'text-end pe-3';
                const actionGroup = document.createElement('div');
                actionGroup.className = 'd-inline-flex align-items-center gap-1.5 justify-content-end';
                detailLink(a, actionGroup);
                if (a.status === 'CONFIRMED') {
                    const checkInLink = document.createElement('a');
                    checkInLink.href = `/reception/check-in?branchId=${a.branchId || ''}`;
                    checkInLink.className = 'btn btn-sm btn-success py-1 px-2.5 rounded-2 fw-semibold';
                    checkInLink.title = 'Bệnh nhân đã đến, bấm để Tiếp đón & Check-in ngay';
                    checkInLink.innerHTML = '<i class="bi bi-person-check-fill me-1"></i>Tiếp đón';
                    actionGroup.appendChild(checkInLink);
                } else if (a.status === 'CHECKED_IN') {
                    const dispatchLink = document.createElement('a');
                    dispatchLink.href = `/reception/dispatch?branchId=${a.branchId || ''}`;
                    dispatchLink.className = 'btn btn-sm btn-primary py-1 px-2.5 rounded-2 fw-semibold';
                    dispatchLink.title = 'Bệnh nhân đã check-in, bấm để vào Bàn điều phối ghế & Bác sĩ';
                    dispatchLink.innerHTML = '<i class="bi bi-diagram-3 me-1"></i>Điều phối';
                    actionGroup.appendChild(dispatchLink);
                }
                const canFix = (a.status === 'PENDING' || a.status === 'CONFIRMED');
                if (a.doctorUnavailable || a.conflictNotes) {
                    const fixLink = document.createElement('a');
                    fixLink.href = `/reception/appointments/${a.id}/edit`;
                    fixLink.className = 'btn btn-sm btn-warning text-dark py-1 px-2 rounded-2 fw-semibold';
                    fixLink.title = `Khắc phục xung đột: ${a.conflictNotes || 'Bác sĩ không khả dụng'}`;
                    fixLink.innerHTML = '<i class="bi bi-tools me-1"></i>Khắc phục';
                    if (canFix) actionGroup.appendChild(fixLink);
                }
                actionCell.appendChild(actionGroup);
                rows.appendChild(row);
            });
        }
        branch.addEventListener('change', async () => { try { await loadDoctors(); await load(); } catch (e) { error(e.message); } });
        date.addEventListener('change', async () => { try { await loadDoctors(); await load(); } catch (e) { error(e.message); } });
        doctor.addEventListener('change', () => load().catch(e => error(e.message)));
        if (type) type.addEventListener('change', () => load().catch(e => error(e.message)));
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
            list.forEach(a => {
                const value = counts.get(a.date) || {total: 0, pending: 0, tk: 0};
                value.total++;
                if (a.status === 'PENDING') value.pending++;
                if (a.code && a.code.startsWith('TK')) value.tk++;
                counts.set(a.date, value);
            });
            const grid = $('calendarGrid'); grid.replaceChildren();
            let current = new Date(first);
            while (current <= last) {
                const dayIso = iso(current);
                const dayLink = document.createElement('a');
                dayLink.className = 'appointment-day';
                dayLink.href = `/reception/appointments?date=${dayIso}${branch.value ? '&branchId=' + branch.value : ''}`;
                if (month && current.getUTCMonth() !== reference.getUTCMonth()) dayLink.classList.add('is-outside');
                if (dayIso === today()) dayLink.classList.add('is-today');
                const dayNumber = document.createElement('strong'); dayNumber.textContent = String(current.getUTCDate());
                const dayInfo = document.createElement('span');
                const count = counts.get(dayIso);
                if (count) {
                    dayInfo.innerHTML = `<strong>${count.total}</strong> lịch${count.pending ? ` <small class="text-warning">(${count.pending} chờ)</small>` : ''}${count.tk ? ` <small style="color:#7c3aed;">(${count.tk} TK)</small>` : ''}`;
                } else {
                    dayInfo.textContent = '0 lịch';
                }
                dayLink.append(dayNumber, dayInfo);
                grid.appendChild(dayLink);
                current = addDays(current, 1);
            }
        }
        branch.addEventListener('change', () => load().catch(e => error(e.message)));
        view.addEventListener('change', () => load().catch(e => error(e.message)));
        date.addEventListener('change', () => load().catch(e => error(e.message)));
        await load();
    }

    async function formPage() {
        const form = $('appointmentForm');
        const id = form.dataset.appointmentId || '';
        const editing = Boolean(id);
        const options = await request('/options');
        branches($('branchId'), options);
        setSelect($('serviceId'), options.services, 'Chưa chọn dịch vụ · 30 phút', s => `${s.name} · ${s.durationMinutes} phút`);
        $('appointmentDate').min = today();
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
        let currentSessionsOverview = [];

        async function loadSessionOverview() {
            const branchId = $('branchId').value;
            const date = $('appointmentDate').value;
            const container = $('sessionLoadCard');
            const bars = $('sessionLoadBars');
            if (!container || !bars) return;
            if (!branchId || !date) {
                container.hidden = true;
                return;
            }
            try {
                const list = await request(`/sessions-overview?branchId=${encodeURIComponent(branchId)}&date=${encodeURIComponent(date)}`);
                currentSessionsOverview = list || [];
                container.hidden = false;
                if ($('sessionLoadDate')) $('sessionLoadDate').textContent = dateLabel(date);
                bars.replaceChildren();
                currentSessionsOverview.forEach(s => {
                    const badgeClass = s.loadStatus === 'LOW' ? 'bg-success-subtle text-success border border-success'
                        : s.loadStatus === 'BALANCED' ? 'bg-primary-subtle text-primary border border-primary'
                        : s.loadStatus === 'HIGH' ? 'bg-warning-subtle text-warning-emphasis border border-warning'
                        : s.loadStatus === 'FULL' ? 'bg-danger-subtle text-danger border border-danger'
                        : 'bg-light text-secondary border';
                    const statusText = s.loadStatus === 'LOW' ? 'Vắng (Khuyên dùng)'
                        : s.loadStatus === 'BALANCED' ? 'Bình thường'
                        : s.loadStatus === 'HIGH' ? 'Sắp đầy'
                        : s.loadStatus === 'FULL' ? 'Đã đủ định mức'
                        : 'Không trực';
                    const pill = document.createElement('div');
                    pill.className = `p-2 rounded-2 ${badgeClass} d-flex align-items-center justify-content-between flex-fill`;
                    pill.style.minWidth = '160px';
                    pill.innerHTML = `
                        <div>
                            <strong style="font-size: 13px;">${escapeHtml(s.sessionName)}</strong>
                            <div style="font-size: 11.5px;">${s.loadStatus === 'OFF' ? 'Không có ca trực' : s.bookedCount + '/' + s.capacity + ' ca (' + s.remaining + ' chỗ)'}</div>
                        </div>
                        <span class="badge ${s.loadStatus === 'LOW' ? 'bg-success' : s.loadStatus === 'HIGH' ? 'bg-warning text-dark' : s.loadStatus === 'FULL' ? 'bg-danger' : 'bg-secondary'}" style="font-size: 11px;">${statusText}</span>
                    `;
                    bars.appendChild(pill);
                });
                updateRecommendation();
            } catch (e) {
                container.hidden = true;
            }
        }

        function updateRecommendation() {
            const recEl = $('sessionRecommendation');
            if (!recEl) return;
            const currentSession = $('preferredSession').value;
            const found = currentSessionsOverview.find(s => s.session === currentSession);
            if (found && found.recommendation && (found.loadStatus === 'HIGH' || found.loadStatus === 'FULL' || found.loadStatus === 'LOW')) {
                recEl.hidden = false;
                recEl.className = found.loadStatus === 'HIGH' ? 'mt-2 text-warning-emphasis small p-2 rounded bg-warning-subtle border border-warning'
                    : found.loadStatus === 'FULL' ? 'mt-2 text-danger small p-2 rounded bg-danger-subtle border border-danger'
                    : 'mt-2 text-success small p-2 rounded bg-success-subtle border border-success';
                recEl.innerHTML = `<i class="bi bi-lightbulb-fill me-1"></i><strong>Gợi ý điều phối ca:</strong> ${escapeHtml(found.recommendation)}`;
            } else {
                recEl.hidden = true;
            }
        }

        async function loadSlots(token) {
            const select = $('slotTime');
            const previous = select.value || (current?.expectedTime || '').slice(0, 5);
            select.disabled = true; select.replaceChildren(option('', 'Đang tải gợi ý giờ…'));
            const params = new URLSearchParams({branchId: $('branchId').value, date: $('appointmentDate').value,
                sessionPeriod: $('preferredSession').value});
            if ($('doctorId').value) params.set('doctorId', $('doctorId').value);
            if ($('serviceId').value) params.set('serviceId', $('serviceId').value);
            if (editing) params.set('excludeAppointmentId', id);
            let list = [];
            try {
                list = await request(`/slots?${params}`);
            } catch (e) {
                list = [];
            }
            if (token !== availabilitySeq) return;
            select.replaceChildren(option('', 'Linh động trong ca (Chưa chốt giờ)'));
            list.forEach(slot => select.appendChild(option(slot.time.slice(0, 5), `${slot.time.slice(0, 5)} · còn ${slot.remaining} chỗ`)));
            if (previous && list.some(slot => slot.time.slice(0, 5) === previous)) {
                select.value = previous;
            } else if (previous) {
                select.appendChild(option(previous, `${previous} (Giờ dự kiến đã lưu)`));
                select.value = previous;
            }
            select.disabled = false;
            // The appointment can still be submitted if the session is available
            $('submitAppointment').disabled = !available;
            if (!list.length && available) {
                $('availabilityMessage').textContent += ' (Lịch hẹn tiếp nhận linh động theo ca, sắp xếp khi bệnh nhân đến khám).';
            }
        }

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
            $('slotTime').disabled = true; $('slotTime').replaceChildren(option('', 'Linh động trong ca (Chưa chốt giờ)'));
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
            updateRecommendation();
            await loadSlots(token);
        }

        $('branchId').addEventListener('change', () => {
            ++patientSeq; $('profileId').replaceChildren(option('', 'Chọn hồ sơ từ kết quả tìm kiếm'));
            loadSessionOverview().catch(e => error(e.message));
            availability().catch(e => error(e.message));
            if ($('patientSearch').value.trim().length >= 2) findPatient().catch(e => error(e.message));
        });
        $('appointmentDate').addEventListener('change', () => {
            loadSessionOverview().catch(e => error(e.message));
            availability().catch(e => error(e.message));
        });
        $('preferredSession').addEventListener('change', () => {
            availability().catch(e => error(e.message));
        });
        ['doctorId', 'serviceId'].forEach(field => $(field).addEventListener('change', () => {
            if (available) loadSlots(availabilitySeq).catch(e => error(e.message));
        }));

        if (editing) {
            await loadSessionOverview();
            await availability();
        } else if ($('branchId').value && $('appointmentDate').value) {
            await loadSessionOverview();
        }

        form.addEventListener('submit', async event => {
            event.preventDefault(); clearError(); if (!form.reportValidity() || !available) return;
            const button = $('submitAppointment'); button.disabled = true;
            const payload = {date: $('appointmentDate').value, session: $('preferredSession').value,
                expectedTime: $('slotTime').value || null,
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

    function setSelect(select, entries, placeholder, label) {
        select.replaceChildren(option('', placeholder));
        entries.forEach(entry => select.appendChild(option(entry.id, label(entry))));
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
        if (a.conflictNotes) {
            if (a.status === 'CHECKED_IN') {
                $('doctorWarning').className = 'alert alert-info d-flex align-items-center justify-content-between mb-3';
                $('doctorWarning').innerHTML = `<div><strong><i class="bi bi-info-circle-fill me-1"></i> Lưu ý điều phối:</strong> ${escapeHtml(a.conflictNotes)}</div> <a href="/reception/dispatch?branchId=${a.branchId || ''}" class="btn btn-sm btn-primary ms-3 text-nowrap"><i class="bi bi-diagram-3 me-1"></i>Vào Điều phối FT08</a>`;
                $('doctorWarning').hidden = false;
            } else if (a.status === 'PENDING' || a.status === 'CONFIRMED') {
                $('doctorWarning').className = 'alert alert-warning mb-3';
                $('doctorWarning').innerHTML = `<strong><i class="bi bi-exclamation-triangle-fill me-1"></i> Lưu ý xung đột lịch:</strong> ${escapeHtml(a.conflictNotes)}. <a href="/reception/appointments/${id}/edit" class="alert-link ms-2">Khắc phục ngay</a>`;
                $('doctorWarning').hidden = false;
            } else {
                $('doctorWarning').hidden = true;
            }
        }
        const facts = [
            ['Mã lịch', a.code + (a.code.startsWith('TK') ? ' (Lịch tái khám sau điều trị - FT18)' : '')],
            ['Bệnh nhân', `${a.patientName} (${a.patientCode})`],
            ['Số điện thoại', a.patientPhone], ['Chi nhánh', a.branchName], ['Ngày khám', dateLabel(a.date)],
            ['Buổi khám', sessionName[a.session] || a.session], ['Giờ hẹn', a.expectedTime ? a.expectedTime.slice(0, 5) : 'Chưa chốt giờ'],
            ['Thời lượng dự kiến', a.durationMinutes ? `${a.durationMinutes} phút` : 'Chưa rõ'],
            ['Bác sĩ yêu cầu', a.doctorName || 'Không yêu cầu đích danh'],
            ['Dịch vụ quan tâm', a.serviceName || (a.code.startsWith('TK') ? 'Tái khám theo dõi chuyên khoa' : 'Chưa chọn')],
            [a.code.startsWith('TK') ? 'Nội dung dặn dò tái khám' : 'Tình trạng ban đầu', a.symptoms || 'Chưa khai báo'],
            ['Nguồn', a.code.startsWith('TK') ? 'Bác sĩ hẹn tái khám (FT18)' : (a.source === 'ONLINE' ? 'Trực tuyến (Portal)' : 'Lễ tân')],
            ['Trạng thái', statusName[a.status] || a.status],
            ['Số lần đổi lịch', String(a.rescheduleCount)], ['Lý do hủy', a.cancelReason || '—']];
        if (a.conflictNotes) {
            facts.splice(4, 0, ['Cảnh báo xung đột', a.conflictNotes]);
        }
        $('appointmentFacts').replaceChildren();
        facts.forEach(([name, value]) => {
            const box = document.createElement('div'), label = document.createElement('span'), content = document.createElement('strong');
            label.textContent = name; content.textContent = value; box.append(label, content); $('appointmentFacts').appendChild(box);
        });

        // Timeline rendering for Appointment History (Replaces ugly raw database strings)
        const history = $('appointmentHistory');
        history.replaceChildren();
        if (!detail.history || !detail.history.length) {
            const empty = document.createElement('li');
            empty.className = 'text-muted small py-2';
            empty.textContent = 'Chưa có lịch sử thay đổi.';
            history.appendChild(empty);
        } else {
            const actionInfo = {
                CREATE: { label: 'Tạo lịch hẹn', badge: 'bg-primary-subtle text-primary border-primary-subtle', icon: 'bi-calendar-plus' },
                UPDATE: { label: 'Cập nhật lịch', badge: 'bg-info-subtle text-info border-info-subtle', icon: 'bi-pencil-square' },
                RESCHEDULE: { label: 'Đổi ngày / buổi khám', badge: 'bg-warning-subtle text-warning border-warning-subtle', icon: 'bi-arrow-repeat' },
                CONFIRM: { label: 'Lễ tân xác nhận', badge: 'bg-success-subtle text-success border-success-subtle', icon: 'bi-check-circle' },
                CANCEL: { label: 'Hủy lịch hẹn', badge: 'bg-danger-subtle text-danger border-danger-subtle', icon: 'bi-x-circle' },
                COMPLETE: { label: 'Hoàn tất lượt khám', badge: 'bg-info-subtle text-info border-info-subtle', icon: 'bi-check2-all' }
                ,CHECK_IN: { label: 'Bệnh nhân đã đến', badge: 'bg-info-subtle text-info border-info-subtle', icon: 'bi-person-check' }
                ,NO_SHOW: { label: 'Bệnh nhân không đến', badge: 'bg-danger-subtle text-danger border-danger-subtle', icon: 'bi-person-x' }
                ,REVERT_CHECK_IN: { label: 'Hoàn tác tiếp đón', badge: 'bg-warning-subtle text-warning border-warning-subtle', icon: 'bi-arrow-counterclockwise' }
            };

            detail.history.forEach((h, idx) => {
                const item = document.createElement('li');
                item.className = 'timeline-entry mb-3';
                item.style.listStyle = 'none';

                const act = actionInfo[h.action] || { label: h.action, badge: 'bg-secondary-subtle text-secondary border', icon: 'bi-clock-history' };
                const snap = formatSnapshot(h.newValue);

                let snapText = '';
                if (snap) {
                    snapText = `<div class="small mt-1 text-secondary">
                        <span><i class="bi bi-calendar3 me-1"></i>Ngày hẹn: <strong>${escapeHtml(snap.date)}</strong> (${escapeHtml(snap.session)})${snap.timeText ? ' · ' + escapeHtml(snap.timeText) : ''}</span>
                        <span class="mx-1.5 text-muted">·</span>
                        <span><i class="bi bi-person-badge me-1"></i>${escapeHtml(snap.doctorText)}</span>
                        ${snap.statusText ? `<span class="mx-1.5 text-muted">·</span><span class="badge bg-light text-dark border">${escapeHtml(snap.statusText)}</span>` : ''}
                    </div>`;
                } else if (h.newValue) {
                    snapText = `<div class="small mt-1 text-muted">${escapeHtml(h.newValue)}</div>`;
                }

                let reasonText = '';
                if (h.reason) {
                    reasonText = `<div class="small text-muted fst-italic mt-1 ps-2 border-start border-2 border-primary-subtle">
                        <i class="bi bi-chat-quote me-1"></i>${escapeHtml(h.reason)}
                    </div>`;
                }

                item.innerHTML = `
                    <div class="d-flex align-items-center gap-2">
                        <span class="badge ${act.badge} py-1 px-2 fw-semibold">
                            <i class="bi ${act.icon} me-1"></i>${escapeHtml(act.label)}
                        </span>
                        <span class="text-muted small fw-medium">${h.createdAt ? escapeHtml(h.createdAt.replace('T', ' ').slice(0, 16)) : `Lần #${idx + 1}`}</span>
                    </div>
                    ${snapText}
                    ${reasonText}
                `;
                history.appendChild(item);
            });
        }

        if (a.status === 'CHECKED_IN') {
            const checkedInActions = $('checkedInActions');
            if (checkedInActions) {
                checkedInActions.hidden = false;
                const dLink = $('dispatchLink');
                if (dLink) dLink.href = `/reception/dispatch?branchId=${a.branchId || ''}`;
                const rBranch = $('revertBranchId');
                if (rBranch) rBranch.value = a.branchId || '';
                const rForm = $('revertCheckInForm');
                if (rForm) {
                    rForm.action = `/reception/check-in/appointments/${id}/revert`;
                    rForm.onsubmit = () => confirm('Bạn có chắc chắn muốn hoàn tác tiếp đón cho bệnh nhân này? Lịch hẹn sẽ quay về trạng thái Đã xác nhận và lượt khám chờ tại FT08 sẽ bị hủy.');
                }
            }
            return;
        }
        if (a.status !== 'PENDING' && a.status !== 'CONFIRMED') return;
        $('appointmentActions').hidden = false; $('editLink').href = `/reception/appointments/${id}/edit`;
        if (a.status === 'CONFIRMED') {
            const cLink = $('checkInDirectLink');
            if (cLink) { cLink.hidden = false; cLink.href = `/reception/check-in?branchId=${a.branchId || ''}`; }
        }
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
            list.forEach(a => { const row = document.createElement('tr'); cell(row, `${dateLabel(a.date)} · ${sessionName[a.session] || a.session} · ${a.expectedTime ? a.expectedTime.slice(0, 5) : 'Chưa chốt giờ'}`);
                cell(row, a.code); cell(row, a.patientName); cell(row, a.patientPhone); cell(row, a.branchName);
                detailLink(a, cell(row)); rows.appendChild(row); });
        }
        branch.addEventListener('change', () => load().catch(e => error(e.message))); await load();
    }

    const task = $('appointmentRows') ? dashboardPage() : $('calendarGrid') ? calendarPage()
        : $('appointmentForm') ? formPage() : $('appointmentDetail') ? detailPage()
        : $('noShowRows') ? noShowsPage() : Promise.resolve();
    task.catch(e => error(e.message));
})();
