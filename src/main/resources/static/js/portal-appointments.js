(() => {
    const api = '/portal/appointments/api';
    const $ = id => document.getElementById(id);
    const sessionLabel = { MORNING: 'Buổi sáng (08:00 – 12:00)', AFTERNOON: 'Buổi chiều (13:30 – 17:30)', EVENING: 'Buổi tối (18:00 – 21:00)' };
    const statusLabel = {
        PENDING: 'Đang chờ xác nhận',
        CONFIRMED: 'Đã xác nhận',
        CHECKED_IN: 'Đã đến phòng khám',
        COMPLETED: 'Đã hoàn thành',
        CANCELLED: 'Đã hủy',
        NO_SHOW: 'Không đến'
    };
    const statusClass = {
        PENDING: 'bg-warning text-dark border-warning-subtle',
        CONFIRMED: 'bg-primary text-white',
        CHECKED_IN: 'bg-info text-white',
        COMPLETED: 'bg-success text-white',
        CANCELLED: 'bg-danger text-white',
        NO_SHOW: 'bg-secondary text-white'
    };
    const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g,
        char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]);

    function getCsrfToken() {
        const meta = document.querySelector('meta[name="_csrf"]');
        if (meta && meta.getAttribute('content')) {
            return {
                header: document.querySelector('meta[name="_csrf_header"]')?.getAttribute('content') || 'X-CSRF-TOKEN',
                token: meta.getAttribute('content')
            };
        }
        const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
        if (match) {
            return {
                header: 'X-XSRF-TOKEN',
                token: decodeURIComponent(match[1])
            };
        }
        return null;
    }

    async function request(path, options = {}) {
        const csrf = getCsrfToken();
        const headers = {
            'Content-Type': 'application/json',
            ...(csrf && csrf.token ? { [csrf.header]: csrf.token } : {}),
            ...(options.headers || {})
        };
        const response = await fetch(api + path, {
            credentials: 'same-origin',
            ...options,
            headers
        });
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.detail || body.message || body.error || `Không thể xử lý yêu cầu (${response.status}).`);
        return body;
    }

    function error(message) {
        const el = $('pageError');
        if (!el) return;
        el.innerHTML = `<i class="bi bi-exclamation-triangle-fill me-2 fs-5"></i><span>${escapeHtml(message)}</span>`;
        el.hidden = false;
        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }

    function clearError() { if ($('pageError')) $('pageError').hidden = true; }

    function option(value, label) {
        const el = document.createElement('option');
        el.value = value; el.textContent = label;
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
        if (!select) return;
        select.replaceChildren(option('', placeholder));
        entries.forEach(entry => select.appendChild(option(entry.id, label(entry))));
    }

    /* =========================================================================
       1. MÀN HÌNH DANH SÁCH LỊCH HẸN (listPage)
       ========================================================================= */
    async function listPage() {
        const options = await request('/options');
        const pillsContainer = $('familyPillsRow');
        let currentProfileFilter = new URLSearchParams(location.search).get('profileId') || '';
        let currentStatusFilter = '';
        let allAppointmentsCache = [];

        // Render Family Member Pills
        if (pillsContainer && options.profiles && options.profiles.length > 0) {
            options.profiles.forEach(p => {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = `family-pill-btn ${currentProfileFilter === String(p.id) ? 'active' : ''}`;
                btn.dataset.profileId = String(p.id);
                btn.innerHTML = `
                    <i class="bi ${p.self ? 'bi-person-fill' : 'bi-person-heart'} me-1.5 ${p.self ? 'text-primary' : 'text-purple'}"></i>
                    <span>${escapeHtml(p.name)}</span>
                    <span class="badge ${p.self ? 'bg-light text-primary border' : 'text-white'}" style="${!p.self ? 'background-color: #7c3aed;' : ''}; font-size: 11px;">
                        ${escapeHtml(p.relation || (p.self ? 'Bản thân' : 'Phụ thuộc'))}
                    </span>
                `;
                btn.addEventListener('click', () => {
                    pillsContainer.querySelectorAll('.family-pill-btn').forEach(b => b.classList.remove('active'));
                    btn.classList.add('active');
                    currentProfileFilter = String(p.id);
                    renderFilteredAppointments();
                });
                pillsContainer.appendChild(btn);
            });

            // Handle "Tất cả gia đình" button
            const allBtn = pillsContainer.querySelector('[data-profile-id=""]');
            if (allBtn) {
                if (currentProfileFilter) allBtn.classList.remove('active');
                allBtn.addEventListener('click', () => {
                    pillsContainer.querySelectorAll('.family-pill-btn').forEach(b => b.classList.remove('active'));
                    allBtn.classList.add('active');
                    currentProfileFilter = '';
                    renderFilteredAppointments();
                });
            }
        }

        // Handle Status Filter Buttons
        const statusRow = $('statusFilterRow');
        if (statusRow) {
            statusRow.querySelectorAll('.status-pill-btn').forEach(btn => {
                btn.addEventListener('click', () => {
                    statusRow.querySelectorAll('.status-pill-btn').forEach(b => b.classList.remove('active'));
                    btn.classList.add('active');
                    currentStatusFilter = btn.dataset.status || '';
                    renderFilteredAppointments();
                });
            });
        }

        async function fetchAppointments() {
            const rows = $('appointmentRows');
            rows.innerHTML = `<tr><td colspan="6" class="text-muted text-center py-5">
                <div class="spinner-border spinner-border-sm text-primary me-2" role="status"></div>
                Đang tải danh sách lịch hẹn…
            </td></tr>`;
            try {
                // Fetch all appointments for family hub
                allAppointmentsCache = await request('');
                if ($('allCountBadge')) $('allCountBadge').textContent = String(allAppointmentsCache.length);
                renderFilteredAppointments();
            } catch (e) {
                error(e.message);
                rows.innerHTML = `<tr><td colspan="6" class="text-danger text-center py-4">
                    <i class="bi bi-exclamation-circle me-2"></i>Không thể tải danh sách lịch hẹn.
                </td></tr>`;
            }
        }

        function renderFilteredAppointments() {
            const rows = $('appointmentRows');
            rows.replaceChildren();

            let filtered = allAppointmentsCache;

            // 1. Filter by profile
            if (currentProfileFilter) {
                filtered = filtered.filter(a => String(a.profileId) === currentProfileFilter);
            }

            // 2. Filter by status
            if (currentStatusFilter === 'UPCOMING') {
                filtered = filtered.filter(a => a.status === 'PENDING' || a.status === 'CONFIRMED');
            } else if (currentStatusFilter === 'RE_EXAM') {
                filtered = filtered.filter(a => a.code && a.code.startsWith('TK'));
            } else if (currentStatusFilter) {
                filtered = filtered.filter(a => a.status === currentStatusFilter);
            }

            if ($('showingCount')) $('showingCount').textContent = String(filtered.length);

            if (!filtered || filtered.length === 0) {
                const emptyRow = document.createElement('tr');
                emptyRow.innerHTML = `
                    <td colspan="6" class="text-center py-5 text-muted">
                        <i class="bi bi-calendar-x display-6 d-block mb-3 text-secondary opacity-50"></i>
                        <h6 class="fw-bold text-dark">Chưa có lịch hẹn nào phù hợp</h6>
                        <p class="small text-muted mb-3">Bạn có thể tạo lịch khám nha khoa mới cho mình hoặc thành viên trong gia đình.</p>
                        <a href="/portal/appointments/new" class="btn btn-sm btn-primary px-3 py-2 fw-semibold" style="background-color: #1e40af; border-color: #1e40af;">
                            <i class="bi bi-calendar-plus me-1.5"></i>Đặt lịch khám mới ngay
                        </a>
                    </td>
                `;
                rows.appendChild(emptyRow);
                return;
            }

            filtered.forEach(a => {
                const tr = document.createElement('tr');

                // Cột 1: Mã lịch hẹn
                const codeTd = document.createElement('td');
                const isReExam = a.code && a.code.startsWith('TK');
                codeTd.innerHTML = `
                    <span class="patient-code-badge d-inline-block">${escapeHtml(a.code)}</span>
                    ${isReExam ? `<div class="badge mt-1.5 d-inline-flex align-items-center" style="background: #ede9fe; color: #6d28d9; border: 1px solid #c4b5fd; font-size: 11px;">
                        <i class="bi bi-arrow-repeat me-1"></i>Tái khám
                    </div>` : ''}
                `;
                tr.appendChild(codeTd);

                // Cột 2: Người đi khám (Avatar + Tên + Tag người phụ thuộc / bản thân)
                const patientTd = document.createElement('td');
                const firstChar = (a.patientName || 'A').charAt(0);
                patientTd.innerHTML = `
                    <div class="d-flex align-items-center gap-2.5">
                        <div class="patient-avatar-mini">${escapeHtml(firstChar)}</div>
                        <div>
                            <strong class="text-dark d-block" style="font-size: 14px;">${escapeHtml(a.patientName)}</strong>
                            <small class="text-muted"><i class="bi bi-person me-1"></i>Hồ sơ bệnh nhân</small>
                        </div>
                    </div>
                `;
                tr.appendChild(patientTd);

                // Cột 3: Ngày & Buổi hẹn
                const dateTd = document.createElement('td');
                dateTd.innerHTML = `
                    <div class="d-flex align-items-center gap-1.5 mb-1">
                        <i class="bi bi-calendar3 text-primary me-1"></i>
                        <strong class="text-dark">${escapeHtml(dateLabel(a.date))}</strong>
                    </div>
                    <div class="small text-muted">
                        <span class="badge bg-light text-secondary border me-1.5">${escapeHtml(a.session === 'MORNING' ? 'Sáng' : a.session === 'AFTERNOON' ? 'Chiều' : 'Tối')}</span>
                        <span><i class="bi bi-clock me-1"></i>${a.expectedTime ? a.expectedTime.slice(0, 5) : 'Chưa chốt giờ'}</span>
                    </div>
                `;
                tr.appendChild(dateTd);

                // Cột 4: Cơ sở & Bác sĩ
                const branchTd = document.createElement('td');
                branchTd.innerHTML = `
                    <div class="text-dark fw-medium mb-1">
                        <i class="bi bi-geo-alt-fill text-danger me-1"></i>${escapeHtml(a.branchName)}
                    </div>
                    <div class="small text-muted">
                        ${a.requestedDoctorName
                            ? `<i class="bi bi-person-fill text-primary me-1"></i>BS. ${escapeHtml(a.requestedDoctorName)}`
                            : `<i class="bi bi-shuffle text-secondary me-1"></i>Lễ tân điều phối`}
                    </div>
                `;
                tr.appendChild(branchTd);

                // Cột 5: Trạng thái
                const statusTd = document.createElement('td');
                statusTd.innerHTML = `
                    <span class="badge rounded-pill ${statusClass[a.status] || 'bg-secondary'} px-3 py-1.5 fw-semibold" style="font-size: 12px;">
                        ${escapeHtml(statusLabel[a.status] || a.status)}
                    </span>
                `;
                tr.appendChild(statusTd);

                // Cột 6: Thao tác
                const actionTd = document.createElement('td');
                actionTd.className = 'text-end pe-3';
                actionTd.innerHTML = `
                    <a href="/portal/appointments/${a.id}" class="btn btn-sm btn-outline-primary fw-semibold px-2.5 py-1.5 d-inline-flex align-items-center">
                        <i class="bi bi-eye me-1.5"></i>Chi tiết
                    </a>
                `;
                tr.appendChild(actionTd);

                rows.appendChild(tr);
            });
        }

        await fetchAppointments();
    }

    /* =========================================================================
       2. MÀN HÌNH ĐẶT / ĐỔI LỊCH HẸN (formPage)
       ========================================================================= */
    async function formPage() {
        const form = $('appointmentForm');
        const id = form.dataset.appointmentId || '';
        const editing = Boolean(id);
        const preSelectedProfileId = form.dataset.selectedProfileId || new URLSearchParams(location.search).get('profileId') || '';
        const options = await request('/options');

        setSelect($('branchId'), options.branches, 'Chọn chi nhánh khám', b => b.name);
        setSelect($('serviceId'), options.services, 'Chưa chọn dịch vụ cụ thể · Khám tổng quát (30 phút)', s => `${s.name} · ${s.durationMinutes} phút`);
        $('appointmentDate').min = localDate();
        $('appointmentDate').max = localDate(90);

        let current = null;
        let selectedProfile = null;

        // Render Profile Selection Cards (FT35)
        const cardsGrid = $('profileCardsGrid');
        if (cardsGrid && !editing) {
            cardsGrid.replaceChildren();
            if (!options.profiles || options.profiles.length === 0) {
                cardsGrid.innerHTML = `
                    <div class="alert alert-warning p-3 w-100 rounded-3">
                        <i class="bi bi-exclamation-triangle-fill me-2 fs-5 text-warning"></i>
                        <strong>Chưa có hồ sơ bệnh nhân:</strong> Vui lòng tạo hồ sơ bệnh nhân trước khi đặt lịch.
                        <a href="/portal/records/new" class="btn btn-sm btn-primary ms-3 text-nowrap"><i class="bi bi-person-plus me-1"></i>Tạo hồ sơ ngay</a>
                    </div>
                `;
                $('submitAppointment').disabled = true;
            } else {
                // Determine which profile to select
                let targetId = preSelectedProfileId;
                if (!targetId || !options.profiles.some(p => String(p.id) === String(targetId))) {
                    targetId = String(options.profiles[0].id);
                }

                options.profiles.forEach(p => {
                    const card = document.createElement('div');
                    const isSelected = String(p.id) === String(targetId);
                    card.className = `profile-card-option ${isSelected ? 'selected' : ''}`;
                    card.dataset.profileId = String(p.id);

                    const firstChar = (p.name || 'A').charAt(0);
                    card.innerHTML = `
                        <div class="radio-indicator"></div>
                        <div class="patient-avatar-mini" style="width: 44px; height: 44px; font-size: 16px;">
                            ${p.avatarUrl ? `<img src="${escapeHtml(p.avatarUrl)}" alt="${escapeHtml(p.name)}">` : `<span>${escapeHtml(firstChar)}</span>`}
                        </div>
                        <div class="profile-card-info">
                            <h4>${escapeHtml(p.name)}</h4>
                            <div class="d-flex align-items-center gap-1.5 flex-wrap">
                                <span class="badge ${p.self ? 'bg-primary' : ''}" style="${!p.self ? 'background-color: #7c3aed;' : ''}">${escapeHtml(p.relation || (p.self ? 'Bản thân' : 'Phụ thuộc'))}</span>
                                <span class="patient-code-badge" style="font-size: 11px; padding: 2px 6px;">${escapeHtml(p.code)}</span>
                            </div>
                            <small class="text-muted d-block mt-1"><i class="bi bi-telephone me-1"></i>${escapeHtml(p.phone || 'Dùng chung SĐT')}</small>
                        </div>
                    `;

                    card.addEventListener('click', () => {
                        cardsGrid.querySelectorAll('.profile-card-option').forEach(c => c.classList.remove('selected'));
                        card.classList.add('selected');
                        $('profileId').value = String(p.id);
                    });

                    cardsGrid.appendChild(card);
                });

                $('profileId').value = String(targetId);
            }
        }

        if (editing) {
            const detail = await request(`/${id}`);
            current = detail.appointment;
            if ($('step1Container')) $('step1Container').hidden = true;
            $('branchId').value = current.branchId;
            $('appointmentDate').value = current.date;
            $('preferredSession').value = current.session;
            $('branchId').disabled = true;
            $('serviceField').hidden = true;
            $('symptomsSection').hidden = true;
            $('submitAppointment').innerHTML = '<i class="bi bi-check2-circle me-2"></i>Lưu ngày / buổi mới';
        } else {
            if (options.branches && options.branches.length > 0 && !$('branchId').value) {
                $('branchId').value = String(options.branches[0].id);
            }
            if (!$('appointmentDate').value) {
                const now = new Date();
                const offset = now.getHours() >= 17 ? 1 : 0;
                $('appointmentDate').value = localDate(offset);
            }
            if (!$('preferredSession').value) {
                const hour = new Date().getHours();
                if (hour < 11) $('preferredSession').value = 'MORNING';
                else if (hour < 16) $('preferredSession').value = 'AFTERNOON';
                else $('preferredSession').value = 'EVENING';
            }
        }

        let available = false;
        let sequence = 0;

        async function loadSlots(token) {
            const select = $('slotTime');
            const previous = select.value || (current?.expectedTime || '').slice(0, 5);
            select.disabled = true;
            select.replaceChildren(option('', 'Đang kiểm tra giờ khám…'));
            const params = new URLSearchParams({
                branchId: $('branchId').value,
                date: $('appointmentDate').value,
                sessionPeriod: $('preferredSession').value
            });
            if ($('doctorId').value) params.set('doctorId', $('doctorId').value);
            if (!editing && $('serviceId').value) params.set('serviceId', $('serviceId').value);
            if (editing) params.set('excludeAppointmentId', id);

            let list = [];
            try {
                list = await request(`/slots?${params}`);
            } catch (err) {
                console.warn('Cannot load granular slots:', err);
            }
            if (token !== sequence) return;
            select.replaceChildren();

            if (list && list.length > 0) {
                select.appendChild(option('', 'Chọn giờ hẹn (hoặc linh hoạt theo buổi)'));
                list.forEach(slot => select.appendChild(option(slot.time.slice(0, 5), `${slot.time.slice(0, 5)} · còn ${slot.remaining} chỗ`)));
                if (previous && list.some(slot => slot.time.slice(0, 5) === previous)) {
                    select.value = previous;
                } else {
                    select.value = list[0].time.slice(0, 5);
                }
                select.disabled = false;
                $('submitAppointment').disabled = false;
            } else if (available) {
                select.appendChild(option('', 'Linh hoạt trong buổi khám (Lễ tân sắp xếp khi đến)'));
                select.value = '';
                select.disabled = false;
                $('submitAppointment').disabled = false;
                $('availabilityMessage').innerHTML = '<i class="bi bi-check-circle-fill fs-5 me-2 text-success"></i><span>Buổi khám còn chỗ tiếp nhận. Giờ khám chính xác sẽ được lễ tân điều phối linh hoạt khi bạn đến phòng khám.</span>';
                $('availabilityMessage').className = 'appointment-availability is-success d-flex align-items-center';
            } else {
                select.appendChild(option('', 'Không còn giờ phù hợp'));
                select.disabled = true;
                $('submitAppointment').disabled = true;
                $('availabilityMessage').innerHTML = '<i class="bi bi-x-circle-fill fs-5 me-2 text-danger"></i><span>Không còn giờ phù hợp trong buổi này. Vui lòng chọn buổi khám hoặc bác sĩ khác.</span>';
                $('availabilityMessage').className = 'appointment-availability is-error d-flex align-items-center';
            }
        }

        async function availability() {
            const token = ++sequence;
            const branchId = $('branchId').value, date = $('appointmentDate').value, session = $('preferredSession').value;
            const doctor = $('doctorId'), message = $('availabilityMessage'), button = $('submitAppointment');
            available = false; button.disabled = true; doctor.disabled = true;
            $('slotTime').disabled = true; $('slotTime').replaceChildren(option('', 'Chọn ngày và buổi trước'));
            doctor.replaceChildren(option('', 'Không yêu cầu đích danh · Lễ tân điều phối'));
            message.className = 'appointment-availability d-flex align-items-center gap-2';

            if (!branchId || !date || !session) {
                message.innerHTML = '<i class="bi bi-info-circle-fill fs-5 me-2 text-primary"></i><span>Vui lòng chọn cơ sở, ngày và buổi khám để kiểm tra khả năng tiếp nhận.</span>';
                return;
            }
            message.innerHTML = '<div class="spinner-border spinner-border-sm text-primary me-2"></div><span>Đang kiểm tra khả năng tiếp nhận…</span>';
            const exclude = editing ? `&excludeAppointmentId=${encodeURIComponent(id)}` : '';
            const result = await request(`/availability?branchId=${encodeURIComponent(branchId)}&date=${encodeURIComponent(date)}&sessionPeriod=${encodeURIComponent(session)}${exclude}`);
            if (token !== sequence) return;

            if (!result.available) {
                message.innerHTML = `<i class="bi bi-exclamation-octagon-fill fs-5 me-2 text-danger"></i><span>${escapeHtml(result.reason || 'Buổi này phòng khám đã kín chỗ.')}</span>`;
                message.className = 'appointment-availability is-error d-flex align-items-center';
                return;
            }

            result.doctors.forEach(d => doctor.appendChild(option(d.id, `${d.name}${d.specialty ? ' · ' + d.specialty : ''} (${d.remaining} chỗ)`)));
            if (current?.requestedDoctorId && result.doctors.some(d => d.id === current.requestedDoctorId)) {
                doctor.value = String(current.requestedDoctorId);
            }

            doctor.disabled = false;
            available = true; button.disabled = false;
            message.innerHTML = `<i class="bi bi-check-circle-fill fs-5 me-2 text-success"></i><span>Buổi khám còn <strong>${result.remainingBranch} chỗ</strong>. Bạn có thể chọn bác sĩ đích danh hoặc để lễ tân điều phối khi đến.</span>`;
            message.className = 'appointment-availability is-success d-flex align-items-center';
            await loadSlots(token);
        }

        async function loadPortalSessions(branchId) {
            const select = $('preferredSession');
            if (!select || !branchId) return;
            const prev = select.value;
            try {
                const res = await fetch(`/api/public/booking/sessions?branchId=${encodeURIComponent(branchId)}`);
                const list = await res.json().catch(() => []);
                if (Array.isArray(list) && list.length > 0) {
                    select.replaceChildren();
                    list.forEach(s => {
                        const opt = document.createElement('option');
                        opt.value = s.session;
                        opt.textContent = s.label || s.name;
                        select.appendChild(opt);
                    });
                    if (prev && list.some(s => s.session === prev)) {
                        select.value = prev;
                    } else {
                        select.value = list[0].session;
                    }
                }
            } catch (err) {
                console.warn(err);
            }
        }

        $('branchId').addEventListener('change', async () => {
            await loadPortalSessions($('branchId').value);
            availability().catch(e => error(e.message));
        });
        ['appointmentDate', 'preferredSession'].forEach(field =>
            $(field).addEventListener('change', () => {
                if (field === 'appointmentDate' && $('appointmentDate').value && $('appointmentDate').value < localDate()) {
                    alert('Không thể chọn ngày trong quá khứ. Hệ thống đã tự động chuyển về ngày hôm nay.');
                    $('appointmentDate').value = localDate();
                }
                availability().catch(e => error(e.message));
            }));
        ['doctorId', 'serviceId'].forEach(field => $(field).addEventListener('change', () => {
            if (available) loadSlots(sequence).catch(e => error(e.message));
        }));

        if ($('branchId').value) {
            await loadPortalSessions($('branchId').value);
        }

        if (editing) {
            await availability();
        } else if ($('branchId').value && $('appointmentDate').value && $('preferredSession').value) {
            await availability().catch(e => error(e.message));
        }

        let key = sessionStorage.getItem('ft06-booking-key');
        if (!key) {
            if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
                key = crypto.randomUUID();
            } else {
                key = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
                    const r = Math.random() * 16 | 0;
                    return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
                });
            }
            sessionStorage.setItem('ft06-booking-key', key);
        }

        form.addEventListener('submit', async event => {
            event.preventDefault(); clearError();
            if (!form.reportValidity() || !available) return;
            const button = $('submitAppointment'); button.disabled = true;
            try {
                const rawTime = $('slotTime')?.value?.trim();
                const payload = {
                    date: $('appointmentDate').value,
                    session: $('preferredSession').value,
                    expectedTime: rawTime && rawTime.length >= 4 ? rawTime : null,
                    doctorId: $('doctorId').value ? Number($('doctorId').value) : null
                };
                let saved;
                if (editing) {
                    saved = await request(`/${id}/reschedule`, { method: 'PUT', body: JSON.stringify({ ...payload, reason: 'Bệnh nhân đổi lịch trực tuyến' }) });
                } else {
                    saved = await request('', {
                        method: 'POST',
                        body: JSON.stringify({
                            ...payload,
                            profileId: Number($('profileId').value),
                            branchId: Number($('branchId').value),
                            serviceId: $('serviceId').value ? Number($('serviceId').value) : null,
                            symptoms: $('symptoms').value.trim() || null,
                            requestKey: key
                        })
                    });
                    sessionStorage.removeItem('ft06-booking-key');
                }
                location.href = `/portal/appointments/${saved.id}?saved=${editing ? 'changed' : 'created'}`;
            } catch (e) { error(e.message); button.disabled = !available; }
        });
    }

    /* =========================================================================
       3. MÀN HÌNH CHI TIẾT LỊCH HẸN (detailPage)
       ========================================================================= */
    async function detailPage() {
        const id = $('appointmentDetail').dataset.appointmentId;
        const detail = await request(`/${id}`), a = detail.appointment;
        $('appointmentCode').textContent = a.code;
        const saved = new URLSearchParams(location.search).get('saved');
        if (saved) {
            $('successMessage').innerHTML = `<i class="bi bi-check-circle-fill me-2 fs-5"></i><span>${saved === 'cancelled' ? 'Đã hủy lịch hẹn thành công.'
                : saved === 'changed' ? 'Đã gửi yêu cầu đổi lịch hẹn. Lễ tân sẽ xác nhận lại.'
                    : 'Đã gửi yêu cầu đặt lịch khám thành công. Lễ tân phòng khám sẽ sớm xác nhận.'}</span>`;
            $('successMessage').hidden = false;
        }

        const facts = $('appointmentFacts');
        const items = [
            ['Mã lịch hẹn', a.code + (a.code.startsWith('TK') ? ' (Lịch tái khám chỉ định FT18)' : '')],
            ['Người đi khám', a.patientName],
            ['Cơ sở khám', a.branchName],
            ['Ngày khám', dateLabel(a.date)],
            ['Buổi khám', sessionLabel[a.session] || a.session],
            ['Giờ hẹn dự kiến', a.expectedTime ? a.expectedTime.slice(0, 5) : 'Linh hoạt trong buổi'],
            ['Bác sĩ phụ trách', a.requestedDoctorName || (a.code.startsWith('TK') ? 'Bác sĩ điều trị' : 'Không yêu cầu đích danh · Lễ tân điều phối')],
            ['Dịch vụ quan tâm', a.serviceName || 'Khám & tư vấn tổng quát'],
            ['Tình trạng ban đầu / Lời dặn', a.symptoms || 'Chưa ghi chú'],
            ['Trạng thái', statusLabel[a.status] || a.status],
            ['Số lần đổi lịch', `${a.rescheduleCount} / 3 lần tối đa`],
            ['Lý do hủy', a.cancelReason || '—']
        ];
        facts.replaceChildren();
        items.forEach(([label, value]) => {
            const box = document.createElement('div'), heading = document.createElement('span'), content = document.createElement('strong');
            heading.textContent = label; content.textContent = value; box.append(heading, content); facts.appendChild(box);
        });

        // Timeline History
        const history = $('appointmentHistory');
        if (history) {
            history.replaceChildren();
            if (!detail.history || !detail.history.length) {
                const empty = document.createElement('li');
                empty.className = 'text-muted small py-2';
                empty.textContent = 'Chưa có lịch sử thay đổi.';
                history.appendChild(empty);
            } else {
                detail.history.forEach((h, idx) => {
                    const item = document.createElement('li');
                    item.className = 'timeline-entry mb-3';
                    item.innerHTML = `
                        <div class="d-flex align-items-center gap-2">
                            <span class="badge bg-primary-subtle text-primary border py-1 px-2.5 fw-semibold">
                                <i class="bi bi-clock-history me-1.5"></i>${escapeHtml(h.action)}
                            </span>
                            <span class="text-muted small">${h.createdAt ? escapeHtml(h.createdAt.replace('T', ' ').slice(0, 16)) : `#${idx + 1}`}</span>
                        </div>
                        ${h.reason ? `<div class="small text-muted fst-italic mt-1.5 ps-2 border-start border-2 border-primary-subtle">${escapeHtml(h.reason)}</div>` : ''}
                    `;
                    history.appendChild(item);
                });
            }
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
            if (!reason || reason === 'Khác' && !note) { error('Vui lòng chọn hoặc ghi rõ lý do hủy lịch.'); return; }
            try {
                await request(`/${id}/cancel`, { method: 'POST', body: JSON.stringify({ reason: note ? `${reason}: ${note}` : reason }) });
                location.href = `/portal/appointments/${id}?saved=cancelled`;
            } catch (e) { error(e.message); }
        });
    }

    const task = $('appointmentRows') ? listPage() : $('appointmentForm') ? formPage()
        : $('appointmentDetail') ? detailPage() : Promise.resolve();
    task.catch(e => error(e.message));
})();
