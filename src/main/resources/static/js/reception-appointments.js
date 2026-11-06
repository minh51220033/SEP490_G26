(() => {
    const api = '/reception/appointments/api';
    const $ = id => document.getElementById(id);
    const sessionName = {MORNING: 'Buổi sáng', AFTERNOON: 'Buổi chiều', EVENING: 'Buổi tối', ANY: 'Chưa chốt'};
    const statusName = {PENDING: 'Chờ xác nhận', CONFIRMED: 'Đã xác nhận', CHECKED_IN: 'Đã đến',
        COMPLETED: 'Hoàn thành', CANCELLED: 'Đã hủy', NO_SHOW: 'Không đến'};
    const statusStyle = {PENDING: 'bg-warning text-dark', CONFIRMED: 'bg-primary', CHECKED_IN: 'bg-success',
        COMPLETED: 'bg-success', CANCELLED: 'bg-danger', NO_SHOW: 'bg-warning text-dark'};
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
        const dateInput = $('filterDate'), branch = $('filterBranch'), doctor = $('filterDoctor');
        const searchInput = $('appointmentSearch');
        const dateDisplay = $('dateDisplayLabel');
        const scopeDropdownLabel = $('currentScopeLabel');
        
        // Initial parameters
        const urlParams = new URLSearchParams(location.search);
        dateInput.value = urlParams.get('date') || today();
        branches(branch, options, true);
        const branchParam = urlParams.get('branchId');
        if (branchParam && options.branches.some(b => String(b.id) === branchParam)) {
            branch.value = branchParam;
        }

        let currentScope = 'CURRENT'; // CURRENT | ALL | CHECKED_IN | NO_SHOW | CANCELLED
        let currentKpiFilter = null; // null | 'ALL' | 'CHECKED_IN' | 'NO_SHOW' | 'CANCELLED'
        let currentAppointments = [];
        let sortDoctorAsc = true;
        let activeAppointmentIdForStatus = null;
        let currentViewMode = 'list'; // 'list' | 'week' | 'month'

        // Date Display Formatting (Thứ X  dd/MM/yyyy)
        const dayNames = ['Chủ nhật', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'];
        function updateDateDisplay() {
            if (!dateInput.value) return;
            const parts = dateInput.value.split('-');
            const d = new Date(parseInt(parts[0]), parseInt(parts[1]) - 1, parseInt(parts[2]));
            const dayName = dayNames[d.getDay()];
            const formatted = `${parts[2]}/${parts[1]}/${parts[0]}`;
            if (dateDisplay) {
                dateDisplay.textContent = `${dayName}  ${formatted}`;
            }
        }
        updateDateDisplay();

        // Date navigation buttons
        $('prevDateBtn')?.addEventListener('click', () => {
            const d = fromDate(dateInput.value);
            dateInput.value = iso(addDays(d, -1));
            updateDateDisplay();
            load().catch(e => error(e.message));
        });

        $('nextDateBtn')?.addEventListener('click', () => {
            const d = fromDate(dateInput.value);
            dateInput.value = iso(addDays(d, 1));
            updateDateDisplay();
            load().catch(e => error(e.message));
        });

        dateInput.addEventListener('change', () => {
            updateDateDisplay();
            load().catch(e => error(e.message));
        });

        // Date View Tabs
        $('tabToday')?.addEventListener('click', () => {
            dateInput.value = today();
            updateDateDisplay();
            switchView('list');
            load().catch(e => error(e.message));
        });

        $('tabWeek')?.addEventListener('click', () => {
            switchView('week');
        });

        $('tabMonth')?.addEventListener('click', () => {
            switchView('month');
        });

        // Scope Dropdown Items
        document.querySelectorAll('[data-scope]').forEach(el => {
            el.addEventListener('click', (e) => {
                e.preventDefault();
                document.querySelectorAll('[data-scope]').forEach(item => item.classList.remove('active'));
                el.classList.add('active');
                currentScope = el.getAttribute('data-scope');
                if (scopeDropdownLabel) scopeDropdownLabel.textContent = el.textContent.trim();
                applyFiltersAndRender();
            });
        });

        // KPI Pills Click -> Filter Table
        const kpiPills = [
            { id: 'kpiTotalPill', filter: 'ALL' },
            { id: 'kpiCheckedInPill', filter: 'CHECKED_IN' },
            { id: 'kpiNoShowPill', filter: 'NO_SHOW' },
            { id: 'kpiCancelledPill', filter: 'CANCELLED' }
        ];
        kpiPills.forEach(({ id, filter }) => {
            $(id)?.addEventListener('click', () => {
                if (currentKpiFilter === filter) {
                    currentKpiFilter = null;
                    $(id).classList.remove('active');
                    $('kpiTotalPill')?.classList.add('active');
                } else {
                    currentKpiFilter = filter;
                    document.querySelectorAll('.kpi-pill').forEach(p => p.classList.remove('active'));
                    $(id).classList.add('active');
                }
                applyFiltersAndRender();
            });
        });

        // Doctor list
        async function loadDoctors() {
            doctor.replaceChildren(option('', 'Tất cả bác sĩ'));
            if (!branch.value) { doctor.disabled = true; return; }
            doctor.disabled = false;
            const list = await request(`/doctors?branchId=${encodeURIComponent(branch.value)}&date=${dateInput.value}`);
            list.forEach(d => doctor.appendChild(option(d.id, d.name)));
        }

        // Sort BS Button
        $('sortDoctorBtn')?.addEventListener('click', () => {
            sortDoctorAsc = !sortDoctorAsc;
            currentAppointments.sort((a, b) => {
                const docA = a.doctorName || '';
                const docB = b.doctorName || '';
                return sortDoctorAsc ? docA.localeCompare(docB, 'vi') : docB.localeCompare(docA, 'vi');
            });
            applyFiltersAndRender();
        });

        // Print Button
        $('printAppointmentBtn')?.addEventListener('click', () => {
            window.print();
        });

        // Toggle View Buttons
        $('viewListBtn')?.addEventListener('click', () => switchView('list'));
        $('viewCalendarBtn')?.addEventListener('click', () => switchView('week'));

        function switchView(mode) {
            currentViewMode = mode;
            if (mode === 'list') {
                $('listViewContainer').style.display = 'block';
                $('calendarViewContainer').style.display = 'none';
                $('viewListBtn')?.classList.add('active', 'btn-primary');
                $('viewListBtn')?.classList.remove('btn-outline-secondary');
                $('viewCalendarBtn')?.classList.remove('active', 'btn-primary');
                $('viewCalendarBtn')?.classList.add('btn-outline-secondary');
            } else {
                $('listViewContainer').style.display = 'none';
                $('calendarViewContainer').style.display = 'block';
                $('viewCalendarBtn')?.classList.add('active', 'btn-primary');
                $('viewCalendarBtn')?.classList.remove('btn-outline-secondary');
                $('viewListBtn')?.classList.remove('active', 'btn-primary');
                $('viewListBtn')?.classList.add('btn-outline-secondary');
                renderCalendarView(mode);
            }
        }

        // Load dashboard data from API
        async function load() {
            clearError();
            const params = new URLSearchParams({ date: dateInput.value });
            if (branch.value) params.set('branchId', branch.value);
            if (doctor.value) params.set('doctorId', doctor.value);

            const data = await request(`/dashboard?${params}`);
            currentAppointments = data.appointments || [];

            // Update KPI numbers
            const totalCount = data.total || currentAppointments.length;
            const checkedInCount = data.checkedIn || currentAppointments.filter(a => a.status === 'CHECKED_IN').length;
            const noShowCount = data.noShow || currentAppointments.filter(a => a.status === 'NO_SHOW').length;
            const cancelledCount = data.cancelled !== undefined ? data.cancelled : currentAppointments.filter(a => a.status === 'CANCELLED').length;

            if ($('kpiTotal')) $('kpiTotal').textContent = totalCount;
            if ($('kpiCheckedIn')) $('kpiCheckedIn').textContent = checkedInCount;
            if ($('kpiNoShow')) $('kpiNoShow').textContent = noShowCount;
            if ($('kpiCancelled')) $('kpiCancelled').textContent = cancelledCount;

            applyFiltersAndRender();
        }

        // Apply search, scope, KPI, doctor filter and render rows
        function applyFiltersAndRender() {
            let list = [...currentAppointments];

            // 1. Doctor filter
            if (doctor.value) {
                list = list.filter(a => String(a.doctorId) === doctor.value);
            }

            // 2. Text Search
            const term = searchInput.value.trim().toLocaleLowerCase('vi');
            if (term) {
                list = list.filter(a => [a.patientName, a.patientCode, a.patientPhone, a.serviceName, a.symptoms]
                    .some(v => String(v || '').toLocaleLowerCase('vi').includes(term)));
            }

            // 3. KPI Pill Filter
            if (currentKpiFilter) {
                if (currentKpiFilter === 'CHECKED_IN') {
                    list = list.filter(a => a.status === 'CHECKED_IN');
                } else if (currentKpiFilter === 'NO_SHOW') {
                    list = list.filter(a => a.status === 'NO_SHOW');
                } else if (currentKpiFilter === 'CANCELLED') {
                    list = list.filter(a => a.status === 'CANCELLED');
                }
            } else {
                // 4. Scope Filter (if KPI filter not active)
                if (currentScope === 'CURRENT') {
                    list = list.filter(a => a.status === 'PENDING' || a.status === 'CONFIRMED' || a.status === 'CHECKED_IN');
                } else if (currentScope === 'CHECKED_IN') {
                    list = list.filter(a => a.status === 'CHECKED_IN' || a.status === 'COMPLETED');
                } else if (currentScope === 'NO_SHOW') {
                    list = list.filter(a => a.status === 'NO_SHOW');
                } else if (currentScope === 'CANCELLED') {
                    list = list.filter(a => a.status === 'CANCELLED');
                }
            }

            renderTableRows(list);
        }

        // Render Table Rows (DentalSoft Style)
        function renderTableRows(appointments) {
            const rows = $('appointmentRows');
            rows.replaceChildren();

            if (!appointments.length) {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td colspan="9" class="text-center py-5 text-muted">
                        <i class="bi bi-calendar-x display-6 d-block mb-2 text-secondary opacity-50"></i>
                        Không có lịch hẹn nào phù hợp bộ lọc.
                    </td>
                `;
                rows.appendChild(tr);
                return;
            }

            appointments.forEach((a, index) => {
                const tr = document.createElement('tr');
                if (a.doctorUnavailable || a.conflictNotes) {
                    tr.style.backgroundColor = '#fffbeb';
                }

                // 1. #
                const tdIndex = document.createElement('td');
                tdIndex.className = 'text-center text-muted fw-medium';
                tdIndex.textContent = index + 1;
                tr.appendChild(tdIndex);

                // 2. Thời gian (Ngày dd-MM-yyyy, linh hoạt giờ theo yêu cầu "không cần giờ")
                const tdTime = document.createElement('td');
                tdTime.className = 'dentalsoft-time-col';
                const timeStr = a.expectedTime ? a.expectedTime.slice(0, 5) : '';
                const parts = (a.date || '').split('-');
                const dateFormatted = parts.length === 3 ? `${parts[2]}-${parts[1]}-${parts[0]}` : a.date;
                tdTime.innerHTML = `
                    ${timeStr ? `<div class="time-val">${escapeHtml(timeStr)}</div>` : ''}
                    <div class="date-val">${escapeHtml(dateFormatted)}</div>
                `;
                tr.appendChild(tdTime);

                // 3. Mã KH (Màu xanh dương)
                const tdCode = document.createElement('td');
                tdCode.innerHTML = `<a href="/reception/patients/${a.profileId}" class="customer-code-badge">${escapeHtml(a.patientCode || 'DEMO')}</a>`;
                tr.appendChild(tdCode);

                // 4. Khách hàng (Tên đậm + tag DEMO)
                const tdPatient = document.createElement('td');
                tdPatient.innerHTML = `
                    <a href="/reception/patients/${a.profileId}" class="customer-name-link">${escapeHtml(a.patientName || 'Khách hàng')}</a>
                    <span class="demo-chip">DEMO</span>
                `;
                tr.appendChild(tdPatient);

                // 5. Số điện thoại
                const tdPhone = document.createElement('td');
                tdPhone.innerHTML = a.patientPhone ? `<a href="tel:${a.patientPhone}" class="phone-link">${escapeHtml(a.patientPhone)}</a>` : '<span class="text-muted">—</span>';
                tr.appendChild(tdPhone);

                // 6. Nội dung
                const tdService = document.createElement('td');
                tdService.className = 'text-dark fw-medium';
                tdService.textContent = a.serviceName || a.symptoms || 'Khám tổng quát';
                tr.appendChild(tdService);

                // 7. Bác sĩ
                const tdDoctor = document.createElement('td');
                const docName = a.doctorName ? (a.doctorName.startsWith('BS') ? a.doctorName : `BS. ${a.doctorName}`) : 'BS. Chưa chỉ định';
                tdDoctor.className = 'text-dark';
                tdDoctor.textContent = docName;
                if (a.doctorUnavailable) {
                    tdDoctor.innerHTML += ' <span class="badge bg-danger text-white ms-1" style="font-size: 10px;">Vắng</span>';
                }
                tr.appendChild(tdDoctor);

                // 8. Trạng thái (Nút pill đổi trạng thái nhanh chuẩn Image 2)
                const tdStatus = document.createElement('td');
                tdStatus.className = 'text-center';
                const statusMeta = getStatusMeta(a.status);
                const statusBtn = document.createElement('button');
                statusBtn.type = 'button';
                statusBtn.className = `dentalsoft-status-btn ${statusMeta.cssClass}`;
                statusBtn.title = 'Bấm để đổi trạng thái lịch hẹn';
                statusBtn.innerHTML = `
                    <div class="status-top">
                        <span>${statusMeta.label}</span>
                        <i class="bi bi-chevron-right" style="font-size: 10.5px;"></i>
                    </div>
                    <div class="status-bot">${statusMeta.sub}</div>
                `;
                statusBtn.addEventListener('click', () => openQuickStatusModal(a));
                tdStatus.appendChild(statusBtn);
                tr.appendChild(tdStatus);

                // 9. Thao tác (Icon sửa, in, hủy)
                const tdAction = document.createElement('td');
                tdAction.className = 'text-center';
                tdAction.innerHTML = `
                    <div class="d-inline-flex align-items-center gap-1.5">
                        <a href="/reception/appointments/${a.id}/edit" class="btn-action-icon text-primary" title="Sửa lịch hẹn"><i class="bi bi-pencil"></i></a>
                        <button type="button" class="btn-action-icon text-dark btn-print" title="In phiếu khám"><i class="bi bi-printer"></i></button>
                        <button type="button" class="btn-action-icon text-danger btn-delete" title="Hủy lịch hẹn"><i class="bi bi-trash"></i></button>
                    </div>
                `;
                tdAction.querySelector('.btn-print')?.addEventListener('click', () => {
                    alert(`In phiếu lịch hẹn #${a.code} cho bệnh nhân ${a.patientName}`);
                });
                tdAction.querySelector('.btn-delete')?.addEventListener('click', async () => {
                    if (confirm(`Bạn có chắc chắn muốn hủy lịch hẹn của khách hàng ${a.patientName}?`)) {
                        try {
                            await request(`/${a.id}/quick-status`, {
                                method: 'POST',
                                body: JSON.stringify({ status: 'CANCELLED', note: 'Lễ tân hủy lịch qua danh sách' })
                            });
                            await load();
                        } catch (err) {
                            error(err.message);
                        }
                    }
                });
                tr.appendChild(tdAction);

                rows.appendChild(tr);
            });
        }

        // Status metadata mapping for DentalSoft pills
        function getStatusMeta(status) {
            switch (status) {
                case 'CHECKED_IN':
                    return { label: 'Đang làm', sub: 'Đã đến', cssClass: 'checkedin' };
                case 'COMPLETED':
                    return { label: 'Đã về', sub: 'Đã xong', cssClass: 'completed' };
                case 'CONFIRMED':
                    return { label: 'Hẹn đến', sub: 'Chưa nhắc', cssClass: 'confirmed' };
                case 'CANCELLED':
                    return { label: 'Đã hủy', sub: 'Đã hủy', cssClass: 'cancelled' };
                case 'NO_SHOW':
                    return { label: 'Không đến', sub: 'Vắng', cssClass: 'noshow' };
                case 'PENDING':
                default:
                    return { label: 'Đang chờ', sub: 'Chưa nhắc', cssClass: 'waiting' };
            }
        }

        // =========================================================
        // MODAL 1: ĐỔI TRẠNG THÁI LỊCH HẸN
        // =========================================================
        const statusModalEl = $('statusQuickChangeModal');
        const statusModal = statusModalEl ? new bootstrap.Modal(statusModalEl) : null;

        function openQuickStatusModal(appointment) {
            if (!statusModal) return;
            activeAppointmentIdForStatus = appointment.id;
            $('modalStatusPatientName').textContent = appointment.patientName || 'Khách hàng';
            $('modalStatusDoctorName').textContent = appointment.doctorName ? (appointment.doctorName.startsWith('BS') ? appointment.doctorName : `BS. ${appointment.doctorName}`) : 'Chưa chỉ định';
            $('modalStatusContent').textContent = appointment.serviceName || appointment.symptoms || 'Khám & tư vấn';
            $('modalStatusNote').value = '';

            // Pre-select appropriate radio button
            const radios = document.querySelectorAll('input[name="quickStatusOpt"]');
            radios.forEach(r => {
                if (appointment.status === 'CHECKED_IN' && r.dataset.label === 'Khách hàng đến') r.checked = true;
                else if (appointment.status === 'COMPLETED' && r.dataset.label === 'Đã xong') r.checked = true;
                else if (appointment.status === 'CONFIRMED' && r.dataset.label === 'KH chưa đến') r.checked = true;
                else if (appointment.status === 'CANCELLED' && r.dataset.label === 'Hủy lịch hẹn') r.checked = true;
                else if (appointment.status === 'NO_SHOW' && r.dataset.label === 'KH không đến') r.checked = true;
                else if (appointment.status === 'PENDING' && r.dataset.label === 'Đang chờ') r.checked = true;
            });

            statusModal.show();
        }

        $('saveQuickStatusBtn')?.addEventListener('click', async () => {
            if (!activeAppointmentIdForStatus) return;
            const selectedRadio = document.querySelector('input[name="quickStatusOpt"]:checked');
            if (!selectedRadio) return;
            const targetStatus = selectedRadio.value;
            const statusLabel = selectedRadio.dataset.label;
            const note = $('modalStatusNote').value.trim();

            try {
                await request(`/${activeAppointmentIdForStatus}/quick-status`, {
                    method: 'POST',
                    body: JSON.stringify({
                        status: targetStatus,
                        note: note ? `[${statusLabel}] ${note}` : `Đổi trạng thái sang ${statusLabel}`
                    })
                });
                statusModal.hide();
                await load();
            } catch (err) {
                alert('Không thể cập nhật trạng thái: ' + err.message);
            }
        });

        // =========================================================
        // MODAL 2: THÊM LỊCH HẸN (IMAGE 3 DENTALSOFT)
        // =========================================================
        const createModalEl = $('createAppointmentModal');
        const createModal = createModalEl ? new bootstrap.Modal(createModalEl) : null;

        // Load doctors for Modal
        async function loadModalDoctors() {
            const select = $('modalDoctorId');
            if (!select) return;
            select.replaceChildren(option('', '-- Chọn bác sĩ --'));
            if (!branch.value) return;
            const list = await request(`/doctors?branchId=${encodeURIComponent(branch.value)}&date=${dateInput.value}`);
            list.forEach(d => select.appendChild(option(d.id, `BS. ${d.name}`)));
        }

        // Load services for Modal
        function loadModalServices() {
            const select = $('modalServiceId');
            if (!select) return;
            select.replaceChildren(option('', '-- Chọn dịch vụ --'));
            options.services.forEach(s => select.appendChild(option(s.id, s.name)));
        }

        $('openCreateModalBtn')?.addEventListener('click', async () => {
            if (!createModal) return;
            $('createAppointmentForm')?.reset();
            $('modalProfileId').value = '';
            $('selectedPatientChip').style.display = 'none';
            $('modalPatientSearch').value = '';
            $('modalAppointmentDate').value = dateInput.value || today();
            $('createModalSubtitle').textContent = `Ngày ${dateLabel(dateInput.value || today())}`;

            await loadModalDoctors();
            loadModalServices();
            createModal.show();
        });

        // Customer Autocomplete Search
        let patientSearchTimer;
        $('modalPatientSearch')?.addEventListener('input', (e) => {
            clearTimeout(patientSearchTimer);
            const query = e.target.value.trim();
            const resultsBox = $('modalPatientResults');
            if (query.length < 2) {
                resultsBox.style.display = 'none';
                return;
            }
            patientSearchTimer = setTimeout(async () => {
                try {
                    const patients = await request(`/patients?branchId=${encodeURIComponent(branch.value || 1)}&query=${encodeURIComponent(query)}`);
                    resultsBox.replaceChildren();
                    if (!patients.length) {
                        resultsBox.innerHTML = '<div class="p-2 text-muted small">Không tìm thấy khách hàng cũ. Bạn có thể nhập thông tin khách mới bên dưới.</div>';
                    } else {
                        patients.forEach(p => {
                            const item = document.createElement('div');
                            item.className = 'patient-autocomplete-item';
                            item.innerHTML = `<strong>${escapeHtml(p.name)}</strong> <span class="badge bg-light text-secondary border font-monospace ms-1">${escapeHtml(p.code)}</span> <span class="text-muted ms-1">· ${escapeHtml(p.phone)}</span>`;
                            item.addEventListener('click', () => {
                                $('modalProfileId').value = p.id;
                                $('modalPatientName').value = p.name;
                                $('modalPatientPhone').value = p.phone || '';
                                $('selectedPatientName').textContent = p.name;
                                $('selectedPatientCode').textContent = `(${p.code})`;
                                $('selectedPatientChip').style.display = 'flex';
                                resultsBox.style.display = 'none';
                            });
                            resultsBox.appendChild(item);
                        });
                    }
                    resultsBox.style.display = 'block';
                } catch (err) {
                    resultsBox.style.display = 'none';
                }
            }, 250);
        });

        $('clearSelectedPatientBtn')?.addEventListener('click', () => {
            $('modalProfileId').value = '';
            $('selectedPatientChip').style.display = 'none';
            $('modalPatientSearch').value = '';
            $('modalPatientName').value = '';
            $('modalPatientPhone').value = '';
            $('modalPatientAddress').value = '';
        });

        // Close search results when clicking outside
        document.addEventListener('click', (e) => {
            if (!e.target.closest('#modalPatientSearch') && !e.target.closest('#modalPatientResults')) {
                if ($('modalPatientResults')) $('modalPatientResults').style.display = 'none';
            }
        });

        // Save Appointment from Modal 2
        $('saveAppointmentBtn')?.addEventListener('click', async () => {
            const patientName = $('modalPatientName').value.trim();
            const date = $('modalAppointmentDate').value;
            const symptoms = $('modalSymptoms').value.trim();
            const doctorId = $('modalDoctorId').value ? Number($('modalDoctorId').value) : null;
            const serviceId = $('modalServiceId').value ? Number($('modalServiceId').value) : null;
            const profileId = $('modalProfileId').value ? Number($('modalProfileId').value) : null;
            const isTentative = $('modalIsTentative')?.checked || false;

            if (!patientName) {
                alert('Vui lòng nhập họ tên khách hàng.');
                $('modalPatientName').focus();
                return;
            }
            if (!date) {
                alert('Vui lòng chọn ngày hẹn.');
                return;
            }
            if (!symptoms) {
                alert('Vui lòng nhập nội dung hẹn khám.');
                $('modalSymptoms').focus();
                return;
            }

            // Expected Time (Linh hoạt: nếu chọn giờ thì ghép, nếu chọn Cả ngày thì null)
            const hour = $('modalHour')?.value;
            const minute = $('modalMinute')?.value || '00';
            const expectedTime = hour ? `${hour}:${minute}:00` : null;

            const payload = {
                branchId: Number(branch.value || 1),
                profileId: profileId,
                patientName: patientName,
                patientPhone: $('modalPatientPhone').value.trim(),
                patientAddress: $('modalPatientAddress').value.trim(),
                customerSource: $('modalCustomerSource').value || 'Vãng lai',
                doctorId: doctorId,
                date: date,
                session: hour && parseInt(hour) >= 12 ? 'AFTERNOON' : 'MORNING',
                expectedTime: expectedTime,
                serviceId: serviceId,
                symptoms: symptoms,
                internalNote: $('modalInternalNote').value.trim(),
                isTentative: isTentative
            };

            try {
                await request('', {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                createModal.hide();
                await load();
            } catch (err) {
                alert('Không thể tạo lịch hẹn: ' + err.message);
            }
        });

        // =========================================================
        // CALENDAR VIEW RENDERING (WEEK / MONTH)
        // =========================================================
        async function renderCalendarView(mode) {
            const reference = fromDate(dateInput.value);
            const isMonth = mode === 'month';
            const start = isMonth ? new Date(Date.UTC(reference.getUTCFullYear(), reference.getUTCMonth(), 1)) : new Date(reference);
            const dayOfWeek = (start.getUTCDay() + 6) % 7;
            const first = addDays(start, -dayOfWeek);
            const last = addDays(first, isMonth ? 41 : 6);

            $('calendarSectionTitle').textContent = isMonth
                ? `Tháng ${reference.getUTCMonth() + 1}/${reference.getUTCFullYear()}`
                : `Tuần từ ${dateLabel(iso(first))} đến ${dateLabel(iso(last))}`;

            const params = new URLSearchParams({ start: iso(first), end: iso(last) });
            if (branch.value) params.set('branchId', branch.value);
            if (doctor.value) params.set('doctorId', doctor.value);

            const list = await request(`/calendar?${params}`);
            const grid = $('calendarGrid');
            grid.replaceChildren();
            grid.classList.toggle('is-week', !isMonth);

            ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'].forEach(label => {
                const header = document.createElement('div');
                header.className = 'appointment-calendar-header';
                header.textContent = label;
                grid.appendChild(header);
            });

            let current = new Date(first);
            while (current <= last) {
                const dayIso = iso(current);
                const dayLink = document.createElement('div');
                dayLink.className = 'appointment-day';
                if (isMonth && current.getUTCMonth() !== reference.getUTCMonth()) {
                    dayLink.classList.add('is-outside');
                }
                if (dayIso === today()) {
                    dayLink.classList.add('is-today');
                }

                const dayHeader = document.createElement('div');
                dayHeader.className = 'd-flex justify-content-between align-items-center mb-1';
                dayHeader.innerHTML = `<span class="calendar-day-number">${current.getUTCDate()}</span>`;
                dayLink.appendChild(dayHeader);

                const events = list.filter(a => a.date === dayIso);
                events.slice(0, isMonth ? 3 : 15).forEach(a => {
                    const event = document.createElement('div');
                    event.className = 'calendar-event';
                    event.dataset.status = a.status;
                    event.style.cursor = 'pointer';
                    event.textContent = `${a.expectedTime ? a.expectedTime.slice(0, 5) : '—'} ${a.patientName || a.patientCode}`;
                    event.title = `${a.patientName} · ${a.doctorName || 'BS Chưa chỉ định'} · ${a.serviceName || a.symptoms || ''}`;
                    event.addEventListener('click', () => openQuickStatusModal(a));
                    dayLink.appendChild(event);
                });

                if (isMonth && events.length > 3) {
                    const more = document.createElement('div');
                    more.className = 'calendar-more text-primary small cursor-pointer';
                    more.textContent = `+${events.length - 3} lịch khác`;
                    more.addEventListener('click', () => {
                        dateInput.value = dayIso;
                        updateDateDisplay();
                        switchView('list');
                        load().catch(e => error(e.message));
                    });
                    dayLink.appendChild(more);
                }

                grid.appendChild(dayLink);
                current = addDays(current, 1);
            }
        }

        // Calendar Next/Prev navigation
        $('calPrev')?.addEventListener('click', () => {
            const current = fromDate(dateInput.value);
            if (currentViewMode === 'month') {
                current.setUTCDate(1);
                current.setUTCMonth(current.getUTCMonth() - 1);
            } else {
                current.setUTCDate(current.getUTCDate() - 7);
            }
            dateInput.value = iso(current);
            updateDateDisplay();
            renderCalendarView(currentViewMode);
        });

        $('calNext')?.addEventListener('click', () => {
            const current = fromDate(dateInput.value);
            if (currentViewMode === 'month') {
                current.setUTCDate(1);
                current.setUTCMonth(current.getUTCMonth() + 1);
            } else {
                current.setUTCDate(current.getUTCDate() + 7);
            }
            dateInput.value = iso(current);
            updateDateDisplay();
            renderCalendarView(currentViewMode);
        });

        // Search input debounce
        let searchTimer;
        searchInput?.addEventListener('input', () => {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(() => applyFiltersAndRender(), 200);
        });

        // Select changes
        branch.addEventListener('change', async () => {
            try {
                await loadDoctors();
                await load();
            } catch (e) {
                error(e.message);
            }
        });

        doctor.addEventListener('change', () => {
            applyFiltersAndRender();
        });

        // Initial Load
        await loadDoctors();
        await load();
    }

    async function calendarPage() {
        const options = await request('/options');
        const branch = $('calendarBranch'), view = $('calendarView'), date = $('calendarDate');
        const url = new URLSearchParams(location.search);
        branches(branch, options, true); date.value = url.get('date') || today();
        if (url.get('branchId') && [...branch.options].some(o => o.value === url.get('branchId'))) branch.value = url.get('branchId');
        view.value = url.get('view') === 'month' ? 'month' : 'week';
        async function loadCalendarDoctors() {
            const select = $('calendarDoctor'); select.replaceChildren(option('', 'Tất cả bác sĩ'));
            select.disabled = !branch.value;
            if (!branch.value) return;
            const doctors = await request(`/doctors?branchId=${encodeURIComponent(branch.value)}&date=${encodeURIComponent(date.value)}`);
            doctors.forEach(d => select.appendChild(option(d.id, d.name)));
        }
        function move(direction) {
            const current = fromDate(date.value);
            if (view.value === 'month') {
                current.setUTCDate(1);
                current.setUTCMonth(current.getUTCMonth() + direction);
            }
            else current.setUTCDate(current.getUTCDate() + direction * 7);
            date.value = iso(current);
            load().catch(e => error(e.message));
        }
        async function load() {
            const reference = fromDate(date.value), month = view.value === 'month';
            const start = month ? new Date(Date.UTC(reference.getUTCFullYear(), reference.getUTCMonth(), 1)) : new Date(reference);
            const dayOfWeek = (start.getUTCDay() + 6) % 7;
            const first = addDays(start, -dayOfWeek), last = addDays(first, month ? 41 : 6);
            const params = new URLSearchParams({start: iso(first), end: iso(last)});
            if (branch.value) params.set('branchId', branch.value);
            if ($('calendarDoctor').value) params.set('doctorId', $('calendarDoctor').value);
            const term = $('calendarSearch').value.trim().toLocaleLowerCase('vi');
            const list = (await request(`/calendar?${params}`)).filter(a => !term || [a.patientName, a.patientCode, a.patientPhone].some(v => String(v || '').toLocaleLowerCase('vi').includes(term)));
            const counts = new Map();
            list.forEach(a => {
                const value = counts.get(a.date) || {total: 0, pending: 0, tk: 0};
                value.total++;
                if (a.status === 'PENDING') value.pending++;
                if (a.code && a.code.startsWith('TK')) value.tk++;
                counts.set(a.date, value);
            });
            const grid = $('calendarGrid'); grid.replaceChildren();
            grid.classList.toggle('is-week', !month);
            $('calendarWeekLink').classList.toggle('is-current', !month);
            $('calendarMonthLink').classList.toggle('is-current', month);
            $('calendarTitle').textContent = month
                ? `Tháng ${reference.getUTCMonth() + 1}/${reference.getUTCFullYear()}`
                : `${dateLabel(iso(first))} – ${dateLabel(iso(last))}`;
            ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN'].forEach(label => { const heading = document.createElement('div'); heading.className = 'appointment-calendar-header'; heading.textContent = label; grid.appendChild(heading); });
            let current = new Date(first);
            while (current <= last) {
                const dayIso = iso(current);
                const dayLink = document.createElement('div');
                dayLink.className = 'appointment-day';
                if (month && current.getUTCMonth() !== reference.getUTCMonth()) dayLink.classList.add('is-outside');
                if (dayIso === today()) dayLink.classList.add('is-today');
                const dayNumber = document.createElement('a'); dayNumber.className = 'calendar-day-number'; dayNumber.href = `/reception/appointments?date=${dayIso}${branch.value ? '&branchId=' + encodeURIComponent(branch.value) : ''}`; dayNumber.textContent = String(current.getUTCDate());
                const count = counts.get(dayIso);
                dayLink.append(dayNumber);
                const events = list.filter(a => a.date === dayIso).sort((a,b) => (a.expectedTime || '99:99').localeCompare(b.expectedTime || '99:99'));
                events.slice(0, month ? 3 : 30).forEach(a => { const event = document.createElement('a'); event.className = 'calendar-event'; event.dataset.status = a.status; event.href = `/reception/appointments/${encodeURIComponent(a.id)}`; event.textContent = `${a.expectedTime ? a.expectedTime.slice(0,5) : '—'}  ${a.patientName || a.patientCode}`; event.title = `${a.patientName || ''} · ${a.doctorName || 'Chưa phân bác sĩ'} · ${statusName[a.status] || a.status}`; dayLink.appendChild(event); });
                if (month && events.length > 3) { const more = document.createElement('a'); more.className = 'calendar-more'; more.href = dayNumber.href; more.textContent = `+${events.length - 3} lịch`; dayLink.appendChild(more); }
                if (!month && count) { const countLabel = document.createElement('small'); countLabel.className = 'calendar-count'; countLabel.textContent = `${count.total} lịch`; dayNumber.appendChild(countLabel); }
                grid.appendChild(dayLink);
                current = addDays(current, 1);
            }
        }
        branch.addEventListener('change', async () => { try { await loadCalendarDoctors(); await load(); } catch (e) { error(e.message); } });
        $('calendarDoctor').addEventListener('change', () => load().catch(e => error(e.message)));
        $('calendarPrevious').addEventListener('click', () => move(-1));
        $('calendarNext').addEventListener('click', () => move(1));
        view.addEventListener('change', () => load().catch(e => error(e.message)));
        date.addEventListener('change', () => load().catch(e => error(e.message)));
        let searchTimer; $('calendarSearch').addEventListener('input', () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => load().catch(e => error(e.message)), 250); });
        await loadCalendarDoctors(); await load();
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
            const dateParam = new URLSearchParams(location.search).get('date');
            if (dateParam && dateParam >= today()) $('appointmentDate').value = dateParam;
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
            if (cLink) {
                cLink.hidden = false;
                const params = new URLSearchParams({branchId: String(a.branchId || ''), appointmentId: String(a.id)});
                cLink.href = `/reception/check-in?${params}`;
            }
        }
        if (a.status === 'PENDING') $('confirmButton').hidden = false;
        $('confirmButton').addEventListener('click', async () => {
            $('confirmBox').hidden = !$('confirmBox').hidden; $('cancelForm').hidden = true;
            if ($('confirmBox').hidden) return;
            $('confirmSubmit').disabled = true;
            const select = $('confirmDoctor'); select.replaceChildren(option('', 'Không yêu cầu đích danh'));
            try { const result = await request(`/availability?branchId=${a.branchId}&date=${a.date}&sessionPeriod=${a.session}&excludeAppointmentId=${id}`);
                result.doctors.forEach(d => select.appendChild(option(d.id, d.name)));
                if (a.doctorId && result.doctors.some(d => d.id === a.doctorId)) select.value = String(a.doctorId);
                if (!result.available) error(result.reason || 'Buổi đã đầy.');
                else $('confirmSubmit').disabled = false;
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
