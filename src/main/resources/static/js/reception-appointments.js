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

    function resolveAppointmentSession(a) {
        if (!a) return 'MORNING';
        if (a.session && ['MORNING', 'AFTERNOON', 'EVENING'].includes(a.session)) {
            return a.session;
        }
        if (a.expectedTime) {
            const hour = parseInt(String(a.expectedTime).slice(0, 2), 10);
            if (hour < 12) return 'MORNING';
            if (hour < 17 || (hour === 17 && parseInt(String(a.expectedTime).slice(3, 5), 10) < 30)) return 'AFTERNOON';
            return 'EVENING';
        }
        return 'MORNING';
    }

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

    async function request(path, config = {}) {
        const headers = {'Content-Type': 'application/json', ...(config.headers || {})};
        const method = (config.method || 'GET').toUpperCase();
        if (method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS') {
            const csrf = getCsrfToken();
            if (csrf && csrf.token) {
                headers[csrf.header] = csrf.token;
            }
        }
        const response = await fetch(api + path, {
            credentials: 'same-origin',
            headers,
            ...config
        });
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

    function timeRange(appointment) {
        const start = appointment.expectedTime?.slice(0, 5);
        if (!start) return 'Linh động';
        if (!appointment.durationMinutes) return start;
        const [hours, minutes] = start.split(':').map(Number);
        const end = hours * 60 + minutes + appointment.durationMinutes;
        return `${start} – ${String(Math.floor(end / 60) % 24).padStart(2, '0')}:${String(end % 60).padStart(2, '0')}`;
    }

    function getDayAppointmentsModal() {
        let modalElement = $('calendarDayAppointmentsModal');
        if (modalElement) return modalElement;
        modalElement = document.createElement('div');
        modalElement.id = 'calendarDayAppointmentsModal';
        modalElement.className = 'modal fade';
        modalElement.tabIndex = -1;
        modalElement.setAttribute('aria-labelledby', 'calendarDayAppointmentsTitle');
        modalElement.setAttribute('aria-hidden', 'true');
        modalElement.innerHTML = `
            <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable">
                <div class="modal-content border-0 shadow-lg rounded-4 overflow-hidden">
                    <div class="modal-header modal-header-dentalsoft">
                        <div><h6 class="modal-title fw-bold mb-0" id="calendarDayAppointmentsTitle">Lịch hẹn trong ngày</h6>
                            <small class="opacity-75" id="calendarDayAppointmentsSubtitle"></small></div>
                        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Đóng"></button>
                    </div>
                    <div class="modal-body p-3 bg-light" id="calendarDayAppointmentsList"></div>
                </div>
            </div>`;
        document.body.appendChild(modalElement);
        return modalElement;
    }

    function openDayAppointmentsModal(day, appointments, openDetails) {
        const modalElement = getDayAppointmentsModal();
        const title = $('calendarDayAppointmentsTitle');
        const subtitle = $('calendarDayAppointmentsSubtitle');
        const list = $('calendarDayAppointmentsList');
        title.textContent = 'Lịch hẹn trong ngày';
        subtitle.textContent = `${dateLabel(day)} · ${appointments.length} lịch hẹn`;
        list.replaceChildren();
        const sessNames = { MORNING: 'Ca Sáng', AFTERNOON: 'Ca Chiều', EVENING: 'Ca Tối' };
        appointments
            .slice()
            .sort((left, right) => {
                const sOrder = { MORNING: 1, AFTERNOON: 2, EVENING: 3 };
                const sL = sOrder[resolveAppointmentSession(left)] || 9;
                const sR = sOrder[resolveAppointmentSession(right)] || 9;
                if (sL !== sR) return sL - sR;
                return (left.expectedTime || '99:99').localeCompare(right.expectedTime || '99:99');
            })
            .forEach(appointment => {
                const item = document.createElement('button');
                item.type = 'button';
                item.className = 'calendar-day-appointment btn w-100 text-start mb-2';
                const sessKey = resolveAppointmentSession(appointment);
                const sessLabel = sessNames[sessKey] || 'Ca Sáng';
                item.innerHTML = `<span class="calendar-day-appointment__time"><span class="cal-session-badge ${sessKey.toLowerCase()}">${escapeHtml(sessLabel)}</span></span>
                    <span class="calendar-day-appointment__main"><strong>${escapeHtml(appointment.patientName || appointment.patientCode || 'Khách hàng')}</strong>
                    <small>${escapeHtml(appointment.doctorName || 'Chưa chỉ định bác sĩ')} · ${escapeHtml(appointment.serviceName || 'Chưa chọn dịch vụ')}</small></span>
                    <span class="badge rounded-pill ${statusStyle[appointment.status] || 'bg-secondary'}">${escapeHtml(statusName[appointment.status] || appointment.status)}</span>`;
                item.addEventListener('click', () => {
                    window.bootstrap?.Modal.getOrCreateInstance(modalElement).hide();
                    openDetails(appointment);
                });
                list.appendChild(item);
            });
        window.bootstrap?.Modal.getOrCreateInstance(modalElement).show();
    }

    function openReadOnlyAppointmentDetails(appointment) {
        const detail = document.createElement('div');
        detail.className = 'modal fade';
        detail.tabIndex = -1;
        detail.setAttribute('aria-hidden', 'true');
        detail.innerHTML = `<div class="modal-dialog modal-dialog-centered"><div class="modal-content border-0 shadow-lg rounded-4 overflow-hidden">
            <div class="modal-header modal-header-dentalsoft"><h6 class="modal-title fw-bold">Chi tiết lịch hẹn</h6><button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Đóng"></button></div>
            <div class="modal-body p-4"><dl class="row mb-0 g-2"></dl></div></div></div>`;
        const values = [['Khách hàng', appointment.patientName], ['Mã BN', appointment.patientCode], ['Số điện thoại', appointment.patientPhone],
            ['Ngày hẹn', dateLabel(appointment.date)], ['Khung giờ', timeRange(appointment)], ['Bác sĩ', appointment.doctorName],
            ['Dịch vụ', appointment.serviceName], ['Trạng thái', statusName[appointment.status] || appointment.status]];
        const dl = detail.querySelector('dl');
        values.forEach(([label, value]) => {
            const dt = document.createElement('dt'), dd = document.createElement('dd');
            dt.className = 'col-4 text-muted fw-normal'; dd.className = 'col-8'; dt.textContent = label; dd.textContent = value || '—'; dl.append(dt, dd);
        });
        detail.addEventListener('hidden.bs.modal', () => detail.remove(), {once: true});
        document.body.appendChild(detail);
        window.bootstrap?.Modal.getOrCreateInstance(detail).show();
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

        let currentScope = 'ALL'; // ALL | CURRENT | CHECKED_IN | NO_SHOW | CANCELLED
        let currentKpiFilter = null; // null | 'ALL' | 'PENDING' | 'CONFIRMED' | 'CHECKED_IN' | 'COMPLETED' | 'NO_SHOW' | 'CANCELLED'
        let currentAppointments = [];
        let sortDoctorAsc = true;
        let activeAppointmentIdForStatus = null;
        let currentPeriod = 'day'; // 'day' | 'week' | 'month'
        let currentViewMode = 'list'; // 'list' | 'calendar'

        function calculateAge(dobStr) {
            if (!dobStr) return null;
            const parts = dobStr.split('-');
            if (parts.length !== 3) return null;
            const birth = new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10));
            const now = new Date();
            let age = now.getFullYear() - birth.getFullYear();
            const m = now.getMonth() - birth.getMonth();
            if (m < 0 || (m === 0 && now.getDate() < birth.getDate())) {
                age--;
            }
            return age >= 0 ? age : 0;
        }

        function resolveAppointmentSession(a) {
            if (a.session && ['MORNING', 'AFTERNOON', 'EVENING'].includes(a.session)) {
                return a.session;
            }
            if (a.expectedTime) {
                const hour = parseInt(a.expectedTime.slice(0, 2), 10);
                if (hour < 12) return 'MORNING';
                if (hour < 17 || (hour === 17 && parseInt(a.expectedTime.slice(3, 5), 10) < 30)) return 'AFTERNOON';
                return 'EVENING';
            }
            return 'MORNING';
        }

        function getPeriodBounds(refDateStr, period) {
            const ref = fromDate(refDateStr || today());
            if (period === 'week') {
                const dayOfWeek = (ref.getUTCDay() + 6) % 7;
                const start = addDays(ref, -dayOfWeek);
                const end = addDays(start, 6);
                return { start: iso(start), end: iso(end) };
            }
            if (period === 'month') {
                const start = new Date(Date.UTC(ref.getUTCFullYear(), ref.getUTCMonth(), 1));
                const end = new Date(Date.UTC(ref.getUTCFullYear(), ref.getUTCMonth() + 1, 0));
                return { start: iso(start), end: iso(end) };
            }
            const d = iso(ref);
            return { start: d, end: d };
        }

        // Date Display Formatting (Thứ X dd/MM/yyyy | Tuần dd/MM - dd/MM | Tháng MM/yyyy)
        const dayNames = ['Chủ nhật', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'];
        function formatDayWithWeekday(isoStr) {
            if (!isoStr) return '—';
            const parts = isoStr.split('-');
            if (parts.length !== 3) return isoStr;
            const d = new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10));
            return `${dayNames[d.getDay()]}, ${parts[2]}/${parts[1]}/${parts[0]}`;
        }

        function updateDateDisplay() {
            if (!dateInput.value) return;
            const parts = dateInput.value.split('-');
            const d = new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10));
            const dayName = dayNames[d.getDay()];
            const formatted = `${parts[2]}/${parts[1]}/${parts[0]}`;
            let labelText = `${dayName}, ${formatted}`;
            let periodTitle = `Danh sách lịch hẹn — ${dayName}, ${formatted}`;

            if (currentPeriod === 'week') {
                const bounds = getPeriodBounds(dateInput.value, 'week');
                const sParts = bounds.start.split('-');
                const eParts = bounds.end.split('-');
                labelText = `Tuần ${sParts[2]}/${sParts[1]} – ${eParts[2]}/${eParts[1]}/${eParts[0]}`;
                periodTitle = `Danh sách lịch hẹn — ${labelText}`;
            } else if (currentPeriod === 'month') {
                labelText = `Tháng ${parts[1]}/${parts[0]}`;
                periodTitle = `Danh sách lịch hẹn — ${labelText}`;
            }

            if (dateDisplay) {
                dateDisplay.textContent = labelText;
            }
            if ($('listPeriodTitle')) {
                $('listPeriodTitle').textContent = periodTitle;
            }
        }
        updateDateDisplay();

        dateDisplay?.addEventListener('click', () => {
            if (dateInput.showPicker) {
                dateInput.showPicker();
            } else {
                dateInput.click();
            }
        });

        function setPeriodActive(activeId) {
            ['tabToday', 'tabWeek', 'tabMonth'].forEach(id => {
                const el = $(id);
                if (el) {
                    el.classList.toggle('active', id === activeId);
                }
            });
        }

        function syncPeriodButtons() {
            if (currentPeriod === 'week') {
                setPeriodActive('tabWeek');
            } else if (currentPeriod === 'month') {
                setPeriodActive('tabMonth');
            } else if (dateInput.value === today()) {
                setPeriodActive('tabToday');
            } else {
                setPeriodActive(null);
            }
        }

        // Date navigation buttons (Dịch chuyển thông minh theo Ngày / Tuần / Tháng)
        $('prevDateBtn')?.addEventListener('click', () => {
            const d = fromDate(dateInput.value);
            if (currentPeriod === 'month') {
                d.setUTCDate(1);
                d.setUTCMonth(d.getUTCMonth() - 1);
                dateInput.value = iso(d);
            } else if (currentPeriod === 'week') {
                dateInput.value = iso(addDays(d, -7));
            } else {
                dateInput.value = iso(addDays(d, -1));
            }
            updateDateDisplay();
            syncPeriodButtons();
            load().catch(e => error(e.message));
        });

        $('nextDateBtn')?.addEventListener('click', () => {
            const d = fromDate(dateInput.value);
            if (currentPeriod === 'month') {
                d.setUTCDate(1);
                d.setUTCMonth(d.getUTCMonth() + 1);
                dateInput.value = iso(d);
            } else if (currentPeriod === 'week') {
                dateInput.value = iso(addDays(d, 7));
            } else {
                dateInput.value = iso(addDays(d, 1));
            }
            updateDateDisplay();
            syncPeriodButtons();
            load().catch(e => error(e.message));
        });

        dateInput.addEventListener('change', () => {
            updateDateDisplay();
            syncPeriodButtons();
            load().catch(e => error(e.message));
        });

        // Date View Tabs: Hôm nay / Tuần này / Tháng này (Độc lập với chế độ Danh sách / Ô lịch)
        $('tabToday')?.addEventListener('click', () => {
            currentPeriod = 'day';
            dateInput.value = today();
            if ($('advFilterDateOp')) $('advFilterDateOp').value = 'EXACT';
            updateDateDisplay();
            syncPeriodButtons();
            load().catch(e => error(e.message));
        });

        $('tabWeek')?.addEventListener('click', () => {
            currentPeriod = 'week';
            if ($('advFilterDateOp')) $('advFilterDateOp').value = 'THIS_WEEK';
            updateDateDisplay();
            syncPeriodButtons();
            load().catch(e => error(e.message));
        });

        $('tabMonth')?.addEventListener('click', () => {
            currentPeriod = 'month';
            if ($('advFilterDateOp')) $('advFilterDateOp').value = 'THIS_MONTH';
            updateDateDisplay();
            syncPeriodButtons();
            load().catch(e => error(e.message));
        });

        // Session Quick Filter Buttons on Main Toolbar
        function syncSessionButtons(sessionVal) {
            document.querySelectorAll('#sessionBtnGroup [data-session]').forEach(btn => {
                btn.classList.toggle('active', btn.getAttribute('data-session') === (sessionVal || ''));
            });
        }

        document.querySelectorAll('#sessionBtnGroup [data-session]').forEach(btn => {
            btn.addEventListener('click', () => {
                const sessionVal = btn.getAttribute('data-session') || '';
                syncSessionButtons(sessionVal);
                if ($('advFilterSession')) $('advFilterSession').value = sessionVal;
                applyFiltersAndRender();
            });
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

        // KPI Pills Click -> Filter Table (Đồng bộ 100% với cột Trạng thái)
        const kpiPills = [
            { id: 'kpiTotalPill', filter: 'ALL' },
            { id: 'kpiPendingPill', filter: 'PENDING' },
            { id: 'kpiConfirmedPill', filter: 'CONFIRMED' },
            { id: 'kpiCheckedInPill', filter: 'CHECKED_IN' },
            { id: 'kpiCompletedPill', filter: 'COMPLETED' },
            { id: 'kpiNoShowPill', filter: 'NO_SHOW' },
            { id: 'kpiCancelledPill', filter: 'CANCELLED' }
        ];
        kpiPills.forEach(({ id, filter }) => {
            $(id)?.addEventListener('click', () => {
                if (currentKpiFilter === filter && filter !== 'ALL') {
                    currentKpiFilter = null;
                    document.querySelectorAll('.kpi-pill').forEach(p => p.classList.remove('active'));
                    $('kpiTotalPill')?.classList.add('active');
                    if ($('advFilterStatus')) $('advFilterStatus').value = '';
                } else {
                    currentKpiFilter = filter;
                    document.querySelectorAll('.kpi-pill').forEach(p => p.classList.remove('active'));
                    $(id)?.classList.add('active');
                    if ($('advFilterStatus')) {
                        $('advFilterStatus').value = filter === 'ALL' ? '' : filter;
                    }
                }
                applyFiltersAndRender();
            });
        });

        // Live Search Input with Debounce 350ms + Clear button 'x'
        const btnClearSearch = $('btnClearSearch');
        function updateClearSearchVisibility() {
            if (!btnClearSearch) return;
            if (searchInput && searchInput.value.trim().length > 0) {
                btnClearSearch.classList.remove('d-none');
            } else {
                btnClearSearch.classList.add('d-none');
            }
        }
        let searchTimer;
        searchInput?.addEventListener('input', () => {
            updateClearSearchVisibility();
            clearTimeout(searchTimer);
            searchTimer = setTimeout(() => applyFiltersAndRender(), 350);
        });
        btnClearSearch?.addEventListener('click', () => {
            if (searchInput) {
                searchInput.value = '';
                updateClearSearchVisibility();
                searchInput.focus();
            }
            applyFiltersAndRender();
        });

        // Doctor list
        async function loadDoctors() {
            doctor.replaceChildren(option('', 'Tất cả bác sĩ'));
            const advDoctor = $('advFilterDoctor');
            if (advDoctor) advDoctor.replaceChildren(option('', '-- Tất cả Bác sĩ --'));
            if (!branch.value) {
                doctor.disabled = true;
                if (advDoctor) advDoctor.disabled = true;
                return;
            }
            doctor.disabled = false;
            if (advDoctor) advDoctor.disabled = false;
            const list = await request(`/doctors?branchId=${encodeURIComponent(branch.value)}&date=${dateInput.value}`);
            list.forEach(d => {
                doctor.appendChild(option(d.id, d.name));
                if (advDoctor) advDoctor.appendChild(option(d.id, d.name));
            });
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

        // Display Mode Switcher (Danh sách vs Ô lịch — hoàn toàn độc lập với Ngày / Tuần / Tháng)
        const radioModeList = $('radioModeList');
        const radioModeCalendar = $('radioModeCalendar');

        function switchView(mode) {
            currentViewMode = mode === 'calendar' ? 'calendar' : 'list';
            if (currentViewMode === 'list') {
                $('listViewContainer').style.display = 'block';
                $('calendarViewContainer').style.display = 'none';
                $('viewListBtn')?.classList.add('active', 'btn-primary');
                $('viewListBtn')?.classList.remove('btn-outline-secondary');
                $('viewCalendarBtn')?.classList.remove('active', 'btn-primary');
                $('viewCalendarBtn')?.classList.add('btn-outline-secondary');
                if (radioModeList) radioModeList.checked = true;
            } else {
                $('listViewContainer').style.display = 'none';
                $('calendarViewContainer').style.display = 'block';
                $('viewCalendarBtn')?.classList.add('active', 'btn-primary');
                $('viewCalendarBtn')?.classList.remove('btn-outline-secondary');
                $('viewListBtn')?.classList.remove('active', 'btn-primary');
                $('viewListBtn')?.classList.add('btn-outline-secondary');
                if (radioModeCalendar) radioModeCalendar.checked = true;
            }
            applyFiltersAndRender();
        }

        $('viewListBtn')?.addEventListener('click', () => switchView('list'));
        $('viewCalendarBtn')?.addEventListener('click', () => switchView('calendar'));
        radioModeList?.addEventListener('change', () => switchView('list'));
        radioModeCalendar?.addEventListener('change', () => switchView('calendar'));

        // =========================================================
        // ACCORDION ADVANCED FILTER PANEL
        // =========================================================
        const btnToggleAdv = $('btnToggleAdvancedFilter');
        const advPanel = $('advancedFilterPanel');
        const advChevron = $('advFilterChevron');
        btnToggleAdv?.addEventListener('click', () => {
            if (!advPanel) return;
            const isHidden = advPanel.style.display === 'none' || getComputedStyle(advPanel).display === 'none';
            if (isHidden) {
                advPanel.style.display = 'block';
                advChevron?.classList.add('rotate-180');
                btnToggleAdv.classList.add('active');
            } else {
                advPanel.style.display = 'none';
                advChevron?.classList.remove('rotate-180');
                btnToggleAdv.classList.remove('active');
            }
        });

        // Populate service dropdown in advanced filter
        const advFilterService = $('advFilterService');
        if (advFilterService && options.services) {
            advFilterService.replaceChildren(option('', '-- Tất cả Nhóm dịch vụ --'));
            options.services.forEach(s => {
                advFilterService.appendChild(option(s.id, s.name));
            });
        }

        // Advanced filter change events
        $('advFilterDoctor')?.addEventListener('change', (e) => {
            doctor.value = e.target.value;
            applyFiltersAndRender();
        });

        doctor.addEventListener('change', () => {
            if ($('advFilterDoctor')) $('advFilterDoctor').value = doctor.value;
            applyFiltersAndRender();
        });

        $('advFilterStatus')?.addEventListener('change', (e) => {
            const val = e.target.value;
            currentKpiFilter = val || 'ALL';
            document.querySelectorAll('.kpi-pill').forEach(p => p.classList.remove('active'));
            const pillMap = {
                ALL: 'kpiTotalPill',
                PENDING: 'kpiPendingPill',
                CONFIRMED: 'kpiConfirmedPill',
                CHECKED_IN: 'kpiCheckedInPill',
                COMPLETED: 'kpiCompletedPill',
                NO_SHOW: 'kpiNoShowPill',
                CANCELLED: 'kpiCancelledPill'
            };
            const targetPillId = pillMap[val || 'ALL'] || 'kpiTotalPill';
            $(targetPillId)?.classList.add('active');
            applyFiltersAndRender();
        });

        $('advFilterSession')?.addEventListener('change', (e) => {
            syncSessionButtons(e.target.value);
            applyFiltersAndRender();
        });
        $('advFilterService')?.addEventListener('change', () => applyFiltersAndRender());
        $('advFilterMedicalAlert')?.addEventListener('change', () => applyFiltersAndRender());

        $('advFilterDateOp')?.addEventListener('change', async (e) => {
            const val = e.target.value;
            if (val === 'TODAY') {
                currentPeriod = 'day';
                dateInput.value = today();
            } else if (val === 'TOMORROW') {
                currentPeriod = 'day';
                dateInput.value = iso(addDays(fromDate(today()), 1));
            } else if (val === 'THIS_WEEK') {
                currentPeriod = 'week';
            } else if (val === 'THIS_MONTH') {
                currentPeriod = 'month';
            } else if (val === 'EXACT') {
                currentPeriod = 'day';
            }
            updateDateDisplay();
            syncPeriodButtons();
            if (val === 'ALL') {
                await loadRangeForOperator('ALL');
            } else {
                await load();
            }
        });

        async function loadRangeForOperator(op) {
            clearError();
            const ref = fromDate(dateInput.value || today());
            let start, end;
            if (op === 'THIS_WEEK') {
                const bounds = getPeriodBounds(dateInput.value, 'week');
                start = bounds.start;
                end = bounds.end;
            } else if (op === 'THIS_MONTH') {
                const bounds = getPeriodBounds(dateInput.value, 'month');
                start = bounds.start;
                end = bounds.end;
            } else if (op === 'ALL') {
                start = iso(addDays(ref, -60));
                end = iso(addDays(ref, 60));
            } else {
                await load();
                return;
            }

            const params = new URLSearchParams({ start, end });
            if (branch.value) params.set('branchId', branch.value);

            const list = await request(`/calendar?${params}`);
            currentAppointments = list || [];
            updateKpisFromList(currentAppointments);
            applyFiltersAndRender();
        }

        function updateKpisFromList(list) {
            const totalCount = list.length;
            const pendingCount = list.filter(a => a.status === 'PENDING').length;
            const confirmedCount = list.filter(a => a.status === 'CONFIRMED').length;
            const checkedInCount = list.filter(a => a.status === 'CHECKED_IN').length;
            const completedCount = list.filter(a => a.status === 'COMPLETED').length;
            const noShowCount = list.filter(a => a.status === 'NO_SHOW').length;
            const cancelledCount = list.filter(a => a.status === 'CANCELLED').length;

            if ($('kpiTotal')) $('kpiTotal').textContent = totalCount;
            if ($('kpiPending')) $('kpiPending').textContent = pendingCount;
            if ($('kpiConfirmed')) $('kpiConfirmed').textContent = confirmedCount;
            if ($('kpiCheckedIn')) $('kpiCheckedIn').textContent = checkedInCount;
            if ($('kpiCompleted')) $('kpiCompleted').textContent = completedCount;
            if ($('kpiNoShow')) $('kpiNoShow').textContent = noShowCount;
            if ($('kpiCancelled')) $('kpiCancelled').textContent = cancelledCount;
        }

        // Active Filter Tags management
        function updateActiveFilterTags(activeTags) {
            const listEl = $('activeFilterTagsList');
            const barEl = $('activeFilterTagsBar');
            const countBadge = $('advFilterCount');
            if (!listEl) return;

            listEl.replaceChildren();
            if (activeTags.length === 0) {
                if (barEl) barEl.style.display = 'none';
                if (countBadge) {
                    countBadge.textContent = '0';
                    countBadge.classList.add('d-none');
                }
                return;
            }

            if (barEl) barEl.style.display = 'flex';
            if (countBadge) {
                countBadge.textContent = activeTags.length;
                countBadge.classList.remove('d-none');
            }

            activeTags.forEach(tag => {
                const span = document.createElement('span');
                span.className = 'filter-tag-pill';
                span.innerHTML = `
                    <span>${escapeHtml(tag.label)}: <strong>${escapeHtml(tag.value)}</strong></span>
                    <button type="button" class="btn-remove-tag" title="Xóa điều kiện này">&times;</button>
                `;
                span.querySelector('.btn-remove-tag').addEventListener('click', () => {
                    tag.onRemove();
                    applyFiltersAndRender();
                });
                listEl.appendChild(span);
            });
        }

        function resetAllFilters() {
            currentPeriod = 'day';
            dateInput.value = today();
            if ($('advFilterDateOp')) $('advFilterDateOp').value = 'EXACT';
            if ($('advFilterSession')) $('advFilterSession').value = '';
            syncSessionButtons('');
            if ($('advFilterDoctor')) $('advFilterDoctor').value = '';
            if ($('advFilterStatus')) $('advFilterStatus').value = '';
            if ($('advFilterService')) $('advFilterService').value = '';
            if ($('advFilterMedicalAlert')) $('advFilterMedicalAlert').value = '';
            if (doctor) doctor.value = '';
            if (searchInput) searchInput.value = '';
            updateClearSearchVisibility();
            currentKpiFilter = null;
            document.querySelectorAll('.kpi-pill').forEach(p => p.classList.remove('active'));
            $('kpiTotalPill')?.classList.add('active');
            updateDateDisplay();
            syncPeriodButtons();
            load().catch(e => error(e.message));
        }

        $('btnResetAdvFilter')?.addEventListener('click', resetAllFilters);
        $('btnClearAllAdvFilters')?.addEventListener('click', resetAllFilters);

        // Load data according to currentPeriod ('day' | 'week' | 'month')
        async function load() {
            clearError();
            updateDateDisplay();
            if (currentPeriod === 'week') {
                await loadRangeForOperator('THIS_WEEK');
                return;
            }
            if (currentPeriod === 'month') {
                await loadRangeForOperator('THIS_MONTH');
                return;
            }

            const params = new URLSearchParams({ date: dateInput.value });
            if (branch.value) params.set('branchId', branch.value);

            const data = await request(`/dashboard?${params}`);
            currentAppointments = data.appointments || [];
            updateKpisFromList(currentAppointments);
            applyFiltersAndRender();
        }

        // Apply search, scope, KPI, session, doctor filter and render active view (List or Calendar)
        function applyFiltersAndRender() {
            let list = [...currentAppointments];
            const activeTags = [];

            // 1. Doctor filter
            const selectedDoctor = doctor.value || $('advFilterDoctor')?.value;
            if (selectedDoctor) {
                list = list.filter(a => String(a.doctorId) === selectedDoctor);
                const docOption = doctor.querySelector(`option[value="${selectedDoctor}"]`);
                activeTags.push({
                    label: 'Bác sĩ',
                    value: docOption ? docOption.textContent.replace(/^BS\.\s*/, '') : 'Đã chọn',
                    onRemove: () => {
                        doctor.value = '';
                        if ($('advFilterDoctor')) $('advFilterDoctor').value = '';
                    }
                });
            }

            // 2. Text Search (Live Search with 350ms debounce)
            const term = searchInput.value.trim().toLocaleLowerCase('vi');
            if (term) {
                list = list.filter((a, idx) => {
                    const stt = String(idx + 1).padStart(2, '0');
                    return [stt, a.code, a.patientCode, a.patientName, a.patientPhone, a.serviceName, a.symptoms, a.doctorName]
                        .some(v => String(v || '').toLocaleLowerCase('vi').includes(term));
                });
                activeTags.push({
                    label: 'Từ khóa',
                    value: term,
                    onRemove: () => {
                        searchInput.value = '';
                        updateClearSearchVisibility();
                    }
                });
            }

            // 3. Status filter (Synchronized with KPI pills and Advanced Filter)
            const advStatus = $('advFilterStatus')?.value;
            const effectiveStatus = advStatus || (currentKpiFilter && currentKpiFilter !== 'ALL' ? currentKpiFilter : null);
            if (effectiveStatus) {
                list = list.filter(a => a.status === effectiveStatus);
                activeTags.push({
                    label: 'Trạng thái',
                    value: statusName[effectiveStatus] || effectiveStatus,
                    onRemove: () => {
                        currentKpiFilter = null;
                        if ($('advFilterStatus')) $('advFilterStatus').value = '';
                        document.querySelectorAll('.kpi-pill').forEach(p => p.classList.remove('active'));
                        $('kpiTotalPill')?.classList.add('active');
                    }
                });
            }

            // 4. Session Filter (MORNING, AFTERNOON, EVENING)
            const advSession = $('advFilterSession')?.value;
            if (advSession) {
                list = list.filter(a => resolveAppointmentSession(a) === advSession);
                const sessLabels = { MORNING: 'Ca Sáng', AFTERNOON: 'Ca Chiều', EVENING: 'Ca Tối' };
                activeTags.push({
                    label: 'Ca khám',
                    value: sessLabels[advSession] || advSession,
                    onRemove: () => {
                        if ($('advFilterSession')) $('advFilterSession').value = '';
                        syncSessionButtons('');
                    }
                });
            }

            // 5. Service Filter
            const advService = $('advFilterService')?.value;
            if (advService) {
                list = list.filter(a => String(a.serviceId) === advService);
                const svcOption = $('advFilterService')?.querySelector(`option[value="${advService}"]`);
                activeTags.push({
                    label: 'Dịch vụ',
                    value: svcOption ? svcOption.textContent : advService,
                    onRemove: () => {
                        if ($('advFilterService')) $('advFilterService').value = '';
                    }
                });
            }

            // 6. Medical Alert & Special Group Filter
            const advAlert = $('advFilterMedicalAlert')?.value;
            if (advAlert === 'HAS_ALERT') {
                list = list.filter(a => {
                    const str = `${a.symptoms || ''} ${a.serviceName || ''}`.toLowerCase();
                    return /dị ứng|tiền sử|huyết áp|tim mạch|tiểu đường|kháng sinh|lidocaine|hen suyễn/.test(str);
                });
                activeTags.push({
                    label: 'Cảnh báo',
                    value: 'Có lưu ý y tế / Dị ứng',
                    onRemove: () => {
                        if ($('advFilterMedicalAlert')) $('advFilterMedicalAlert').value = '';
                    }
                });
            } else if (advAlert === 'MINOR') {
                list = list.filter(a => {
                    const age = calculateAge(a.patientDob);
                    return age !== null && age < 18;
                });
                activeTags.push({
                    label: 'Nhóm BN',
                    value: 'Dưới 18 tuổi',
                    onRemove: () => {
                        if ($('advFilterMedicalAlert')) $('advFilterMedicalAlert').value = '';
                    }
                });
            } else if (advAlert === 'FOLLOW_UP') {
                list = list.filter(a => {
                    const str = `${a.serviceName || ''} ${a.symptoms || ''} ${a.code || ''}`.toLowerCase();
                    return a.rescheduleCount > 0 || str.includes('tái khám') || str.startsWith('tk');
                });
                activeTags.push({
                    label: 'Phân loại',
                    value: 'Lịch tái khám',
                    onRemove: () => {
                        if ($('advFilterMedicalAlert')) $('advFilterMedicalAlert').value = '';
                    }
                });
            }

            // 7. Sort by Date -> Session -> Expected Time
            const sessionOrder = { MORNING: 1, AFTERNOON: 2, EVENING: 3 };
            list.sort((a, b) => {
                const dateCmp = String(a.date || '').localeCompare(String(b.date || ''));
                if (dateCmp !== 0) return dateCmp;
                const sA = sessionOrder[resolveAppointmentSession(a)] || 9;
                const sB = sessionOrder[resolveAppointmentSession(b)] || 9;
                if (sA !== sB) return sA - sB;
                return String(a.expectedTime || '99:99').localeCompare(String(b.expectedTime || '99:99'));
            });

            updateActiveFilterTags(activeTags);
            renderTableRows(list);
            if (currentViewMode === 'calendar') {
                renderCalendarView(list);
            }
        }

        // Render Table Rows (Clean DentalSoft Style)
        function renderTableRows(appointments) {
            const rows = $('appointmentRows');
            rows.replaceChildren();

            if ($('listSummaryCount')) {
                $('listSummaryCount').textContent = `${appointments.length} lịch hẹn`;
            }

            if (!appointments.length) {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td colspan="9" class="text-center py-5 text-muted">
                        <i class="bi bi-calendar-x display-6 d-block mb-2 text-secondary opacity-50"></i>
                        Không có lịch hẹn nào phù hợp với điều kiện lọc hiện tại.
                    </td>
                `;
                rows.appendChild(tr);
                return;
            }

            const showDateGroupHeaders = currentPeriod !== 'day' || new Set(appointments.map(a => a.date)).size > 1;
            let lastDate = null;

            const sessionBadgeInfo = {
                MORNING: { label: 'Ca Sáng', icon: 'bi-sunrise-fill', cls: 'morning' },
                AFTERNOON: { label: 'Ca Chiều', icon: 'bi-sun-fill', cls: 'afternoon' },
                EVENING: { label: 'Ca Tối', icon: 'bi-moon-stars-fill', cls: 'evening' }
            };

            appointments.forEach((a, index) => {
                if (showDateGroupHeaders && a.date !== lastDate) {
                    lastDate = a.date;
                    const dayCount = appointments.filter(item => item.date === lastDate).length;
                    const groupTr = document.createElement('tr');
                    groupTr.className = 'apt-date-group-row';
                    groupTr.innerHTML = `
                        <td colspan="9">
                            <div class="d-flex align-items-center justify-content-between">
                                <span><i class="bi bi-calendar3 text-primary me-2"></i>${escapeHtml(formatDayWithWeekday(lastDate))}</span>
                                <span class="badge bg-white text-secondary border fw-semibold">${dayCount} ca hẹn</span>
                            </div>
                        </td>
                    `;
                    rows.appendChild(groupTr);
                }

                const tr = document.createElement('tr');
                tr.dataset.status = a.status || '';
                tr.dataset.doctor = a.doctorId || '';
                tr.dataset.doctorName = a.doctorName || '';
                tr.dataset.search = [a.code, a.patientCode, a.patientName, a.patientPhone, a.serviceName, a.symptoms, a.doctorName].filter(Boolean).join(' ');

                // 1. #
                const tdIndex = document.createElement('td');
                tdIndex.className = 'text-center text-muted fw-medium';
                tdIndex.textContent = index + 1;
                tr.appendChild(tdIndex);

                // 2. Ca & Giờ hẹn (Hiển thị rõ Ca Sáng / Chiều / Tối + Giờ dự kiến hoặc Linh động)
                const tdTime = document.createElement('td');
                tdTime.className = 'dentalsoft-time-col';
                const sessKey = resolveAppointmentSession(a);
                const sessMeta = sessionBadgeInfo[sessKey] || sessionBadgeInfo.MORNING;
                const timeStr = a.expectedTime ? a.expectedTime.slice(0, 5) : 'Linh động';
                const parts = (a.date || '').split('-');
                const dateFormatted = parts.length === 3 ? `${parts[2]}/${parts[1]}/${parts[0]}` : a.date;
                tdTime.innerHTML = `
                    <div>
                        <span class="apt-session-tag ${sessMeta.cls}">
                            <i class="bi ${sessMeta.icon}"></i>${sessMeta.label}
                        </span>
                    </div>
                    <div class="d-flex align-items-center gap-1 mt-0.5">
                        <span class="time-val">${escapeHtml(timeStr)}</span>
                        <span class="date-val">· ${escapeHtml(dateFormatted)}</span>
                    </div>
                `;
                tr.appendChild(tdTime);

                // 3. Mã KH
                const tdCode = document.createElement('td');
                if (a.profileId) {
                    const codeLink = document.createElement('a');
                    codeLink.setAttribute('href', `/reception/patients/${a.profileId}`);
                    codeLink.className = 'customer-code-badge';
                    codeLink.textContent = a.patientCode || `BN#${a.profileId}`;
                    tdCode.appendChild(codeLink);
                } else {
                    const codeSpan = document.createElement('span');
                    codeSpan.className = 'customer-code-badge';
                    codeSpan.textContent = a.patientCode || 'Khách mới';
                    tdCode.appendChild(codeSpan);
                }
                tr.appendChild(tdCode);

                // 4. Khách hàng
                const tdPatient = document.createElement('td');
                if (a.profileId) {
                    const nameLink = document.createElement('a');
                    nameLink.setAttribute('href', `/reception/patients/${a.profileId}`);
                    nameLink.className = 'customer-name-link';
                    nameLink.textContent = a.patientName || 'Khách hàng';
                    tdPatient.appendChild(nameLink);
                } else {
                    const nameSpan = document.createElement('span');
                    nameSpan.className = 'customer-name-link';
                    nameSpan.textContent = a.patientName || 'Khách hàng';
                    tdPatient.appendChild(nameSpan);
                }
                tr.appendChild(tdPatient);

                // 5. Số điện thoại
                const tdPhone = document.createElement('td');
                const rawPhone = a.patientPhone ? String(a.patientPhone).trim() : '';
                const cleanPhone = rawPhone.replace(/[^\d+]/g, '');
                if (/^\+?0?\d{9,11}$/.test(cleanPhone)) {
                    const phoneLink = document.createElement('a');
                    phoneLink.setAttribute('href', `tel:${cleanPhone}`);
                    phoneLink.className = 'phone-link';
                    phoneLink.textContent = rawPhone;
                    tdPhone.appendChild(phoneLink);
                } else if (rawPhone) {
                    const phoneSpan = document.createElement('span');
                    phoneSpan.className = 'text-dark';
                    phoneSpan.textContent = rawPhone;
                    tdPhone.appendChild(phoneSpan);
                } else {
                    const emptySpan = document.createElement('span');
                    emptySpan.className = 'text-muted';
                    emptySpan.textContent = '—';
                    tdPhone.appendChild(emptySpan);
                }
                tr.appendChild(tdPhone);

                // 6. Nội dung / Dịch vụ
                const tdService = document.createElement('td');
                tdService.className = 'text-dark fw-medium';
                tdService.textContent = a.serviceName || a.symptoms || 'Khám tổng quát';
                tr.appendChild(tdService);

                // 7. Bác sĩ phụ trách (Bỏ hoàn toàn nhãn [Vắng] gây hiểu nhầm)
                const tdDoctor = document.createElement('td');
                if (a.doctorName) {
                    tdDoctor.className = 'text-dark fw-medium';
                    tdDoctor.textContent = a.doctorName.startsWith('BS') ? a.doctorName : `BS. ${a.doctorName}`;
                } else {
                    tdDoctor.className = 'text-muted';
                    tdDoctor.textContent = 'Chưa chỉ định';
                }
                tr.appendChild(tdDoctor);

                // 8. Trạng thái (Chỉ hiển thị badge tĩnh rõ ràng, khớp 100% với bộ lọc, KHÔNG phải nút bấm)
                const tdStatus = document.createElement('td');
                tdStatus.className = 'text-center';
                const statusMeta = getStatusMeta(a.status);
                const statusBadge = document.createElement('span');
                statusBadge.className = `apt-status-badge ${statusMeta.cssClass}`;
                statusBadge.innerHTML = `<span class="status-dot"></span><span>${escapeHtml(statusMeta.label)}</span>`;
                tdStatus.appendChild(statusBadge);
                tr.appendChild(tdStatus);

                // 9. Thao tác (Chỉ hiển thị 3 nút: Xem chi tiết, Chỉnh sửa tại chỗ qua Modal, Xóa/Hủy)
                const tdAction = document.createElement('td');
                tdAction.className = 'text-center appointment-actions-column';
                const isMutable = a.status === 'PENDING' || a.status === 'CONFIRMED';
                tdAction.innerHTML = `
                    <div class="appointment-action-group">
                        <button type="button" class="btn-action-icon text-primary btn-details" data-appointment-id="${a.id}" title="Xem chi tiết lịch hẹn">
                            <i class="bi bi-eye"></i>
                        </button>
                        ${isMutable
                            ? `<button type="button" class="btn-action-icon text-secondary btn-edit" data-appointment-id="${a.id}" title="Sửa / Đổi lịch hẹn"><i class="bi bi-pencil"></i></button>`
                            : `<button type="button" class="btn-action-icon text-muted opacity-50" disabled title="Chỉ có thể sửa lịch hẹn khi đang Chờ xác nhận hoặc Đã xác nhận"><i class="bi bi-pencil"></i></button>`
                        }
                        ${isMutable
                            ? `<button type="button" class="btn-action-icon text-danger btn-delete" title="Hủy lịch hẹn"><i class="bi bi-trash"></i></button>`
                            : `<button type="button" class="btn-action-icon text-muted opacity-50" disabled title="Không thể hủy lịch hẹn ở trạng thái ${escapeHtml(statusMeta.label)}"><i class="bi bi-trash"></i></button>`
                        }
                    </div>
                `;
                tdAction.querySelector('.btn-details')?.addEventListener('click', () => openQuickStatusModal(a));
                tdAction.querySelector('.btn-edit')?.addEventListener('click', () => openEditAppointmentModal(a));
                tdAction.querySelector('.btn-delete')?.addEventListener('click', async () => {
                    if (confirm(`Bạn có chắc chắn muốn hủy lịch hẹn của khách hàng "${a.patientName}"?`)) {
                        try {
                            await request(`/${a.id}/quick-status`, {
                                method: 'POST',
                                body: JSON.stringify({ status: 'CANCELLED', note: 'Lễ tân hủy lịch hẹn trên danh sách' })
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

        // Status metadata mapping (Khớp 100% với tên trên thanh Status Filter Pills)
        function getStatusMeta(status) {
            switch (status) {
                case 'CONFIRMED':
                    return { label: 'Đã xác nhận', cssClass: 'status-confirmed' };
                case 'CHECKED_IN':
                    return { label: 'Đã đến', cssClass: 'status-checkedin' };
                case 'COMPLETED':
                    return { label: 'Hoàn thành', cssClass: 'status-completed' };
                case 'NO_SHOW':
                    return { label: 'Không đến', cssClass: 'status-noshow' };
                case 'CANCELLED':
                    return { label: 'Đã hủy', cssClass: 'status-cancelled' };
                case 'PENDING':
                default:
                    return { label: 'Chờ xác nhận', cssClass: 'status-pending' };
            }
        }

        // =========================================================
        // MODAL 1: CHI TIẾT LỊCH HẸN (READ-ONLY SHEET KHỚP FORM THÊM)
        // =========================================================
        const statusModalEl = $('statusQuickChangeModal');
        const statusModal = statusModalEl ? new bootstrap.Modal(statusModalEl) : null;

        function openQuickStatusModal(appointment) {
            if (!statusModal) return;
            activeAppointmentIdForStatus = appointment.id;

            // Header info
            if ($('modalDetailCode')) {
                $('modalDetailCode').textContent = appointment.code || ('LH' + appointment.id);
            }
            const badgeEl = $('modalStatusBadge');
            if (badgeEl) {
                badgeEl.className = `badge rounded-pill ${statusStyle[appointment.status] || 'bg-secondary'}`;
                badgeEl.textContent = statusName[appointment.status] || appointment.status;
            }

            const age = calculateAge(appointment.patientDob);

            // Left Column: Customer Information (Thiết kế đồng nhất tuyệt đối với Modal Thêm lịch hẹn)
            const patientCodeVal = appointment.patientCode || (appointment.profileId ? `BN#${appointment.profileId}` : 'Khách mới');
            if ($('detailPatientCode')) {
                $('detailPatientCode').value = patientCodeVal;
                $('detailPatientCode').textContent = patientCodeVal;
            }
            const patientNameVal = appointment.patientName || 'Khách hàng';
            if ($('modalStatusPatientName')) {
                $('modalStatusPatientName').value = patientNameVal;
                $('modalStatusPatientName').textContent = patientNameVal;
            }
            if ($('detailPatientDobAge')) {
                const dobStr = appointment.patientDob ? dateLabel(appointment.patientDob) : '—';
                const dobAgeVal = age !== null ? `${dobStr} (${age} tuổi)` : dobStr;
                $('detailPatientDobAge').value = dobAgeVal;
                $('detailPatientDobAge').textContent = dobAgeVal;
            }
            if ($('detailPatientGender')) {
                const genderVal = appointment.patientGender || '—';
                $('detailPatientGender').value = genderVal;
                $('detailPatientGender').textContent = genderVal;
            }
            const phone = appointment.patientPhone ? String(appointment.patientPhone).trim() : '';
            if ($('detailPatientPhoneInput')) {
                $('detailPatientPhoneInput').value = phone || '—';
            }
            if ($('detailPatientPhone')) {
                $('detailPatientPhone').href = phone ? `tel:${phone}` : '#';
                $('detailPatientPhone').textContent = phone || '';
            }
            if ($('detailPatientAddress')) {
                const addrVal = appointment.branchName || 'Khám tại phòng khám';
                $('detailPatientAddress').value = addrVal;
                $('detailPatientAddress').textContent = addrVal;
            }
            if ($('detailCustomerSource')) {
                const sourceMap = {
                    RECEPTIONIST: 'Lễ tân đặt hẹn',
                    ONLINE: 'Đặt lịch trực tuyến',
                    PATIENT: 'Đặt lịch trực tuyến'
                };
                const srcVal = sourceMap[appointment.source] || appointment.source || 'Vãng lai (Trực tiếp)';
                $('detailCustomerSource').value = srcVal;
                $('detailCustomerSource').textContent = srcVal;
            }

            // Guardian block if age < 18
            const guardianContainer = $('detailGuardianContainer');
            if (guardianContainer) {
                if (age !== null && age < 18) {
                    guardianContainer.style.display = 'block';
                    if ($('detailGuardianText')) {
                        $('detailGuardianText').textContent = 'Bệnh nhân dưới 18 tuổi — Có người giám hộ đi cùng.';
                    }
                } else {
                    guardianContainer.style.display = 'none';
                }
            }

            // Right Column: Appointment Information (Khớp cấu trúc và giao diện Form Thêm lịch hẹn)
            if ($('detailAppointmentBranch')) {
                const branchVal = appointment.branchName || 'Cơ sở phòng khám';
                $('detailAppointmentBranch').value = branchVal;
                $('detailAppointmentBranch').textContent = branchVal;
            }
            if ($('detailAppointmentDate')) {
                const dateVal = formatDayWithWeekday(appointment.date);
                $('detailAppointmentDate').value = dateVal;
                $('detailAppointmentDate').textContent = dateVal;
            }

            // Highlight Ca khám tương ứng (Ca Sáng, Ca Chiều, Ca Tối)
            const sessKey = resolveAppointmentSession(appointment);
            $('detailSessMorning')?.classList.toggle('active', sessKey === 'MORNING');
            $('detailSessAfternoon')?.classList.toggle('active', sessKey === 'AFTERNOON');
            $('detailSessEvening')?.classList.toggle('active', sessKey === 'EVENING');

            if ($('modalStatusDoctorName')) {
                const docVal = appointment.doctorName
                    ? (appointment.doctorName.startsWith('BS') ? appointment.doctorName : `BS. ${appointment.doctorName}`)
                    : 'Chưa chỉ định';
                $('modalStatusDoctorName').value = docVal;
                $('modalStatusDoctorName').textContent = docVal;
            }

            const start = appointment.expectedTime?.slice(0, 5);
            const timeStr = start ? `${start} (trong ca)` : 'Linh động trong ca';
            if ($('detailAppointmentTime')) {
                $('detailAppointmentTime').value = timeStr;
                $('detailAppointmentTime').textContent = timeStr;
            }

            if ($('detailAppointmentService')) {
                const servVal = appointment.serviceName || 'Khám tổng quát';
                $('detailAppointmentService').value = servVal;
                $('detailAppointmentService').textContent = servVal;
            }
            const rawNotes = appointment.symptoms || '';
            const noteMatch = rawNotes.match(/(?:\s*-\s*)?\[Ghi chú: ([\s\S]*?)\]$/);
            const content = rawNotes.replace(/(?:\s*-\s*)?\[Ghi chú: [\s\S]*?\]$/, '').trim();
            if ($('modalStatusContent')) {
                const contentVal = content || 'Khám nha khoa';
                $('modalStatusContent').value = contentVal;
                $('modalStatusContent').textContent = contentVal;
            }
            if ($('detailInternalNote')) {
                const noteVal = noteMatch?.[1] || appointment.cancelReason || 'Không có ghi chú thêm';
                $('detailInternalNote').value = noteVal;
                $('detailInternalNote').textContent = noteVal;
            }

            // Nút mở sửa lịch hẹn từ modal chi tiết
            const btnDetailOpenEdit = $('btnDetailOpenEdit');
            if (btnDetailOpenEdit) {
                const isMutable = appointment.status === 'PENDING' || appointment.status === 'CONFIRMED';
                btnDetailOpenEdit.style.display = isMutable ? 'inline-flex' : 'none';
                btnDetailOpenEdit.onclick = () => {
                    statusModal.hide();
                    openEditAppointmentModal(appointment);
                };
            }

            statusModal.show();
        }

        // =========================================================
        // MODAL 2: THÊM LỊCH HẸN (ĐẶT THEO CA SÁNG / CHIỀU / TỐI + GIỜ LINH ĐỘNG)
        // =========================================================
        const createModalEl = $('createAppointmentModal');
        const createModal = createModalEl ? new bootstrap.Modal(createModalEl) : null;

        const sessionHourOptions = {
            MORNING: [
                { value: '', label: 'Linh động cả ca sáng' },
                { value: '08', label: '08 giờ' },
                { value: '09', label: '09 giờ' },
                { value: '10', label: '10 giờ' },
                { value: '11', label: '11 giờ' }
            ],
            AFTERNOON: [
                { value: '', label: 'Linh động cả ca chiều' },
                { value: '13', label: '13 giờ' },
                { value: '14', label: '14 giờ' },
                { value: '15', label: '15 giờ' },
                { value: '16', label: '16 giờ' },
                { value: '17', label: '17 giờ' }
            ],
            EVENING: [
                { value: '', label: 'Linh động cả ca tối' },
                { value: '17', label: '17 giờ' },
                { value: '18', label: '18 giờ' },
                { value: '19', label: '19 giờ' },
                { value: '20', label: '20 giờ' }
            ]
        };

        function setModalSession(sessionKey) {
            const validSession = ['MORNING', 'AFTERNOON', 'EVENING'].includes(sessionKey) ? sessionKey : 'MORNING';
            if ($('modalSession')) {
                $('modalSession').value = validSession;
            }
            document.querySelectorAll('#modalSessionGroup [data-modal-session]').forEach(btn => {
                btn.classList.toggle('active', btn.getAttribute('data-modal-session') === validSession);
            });
            const hourSelect = $('modalHour');
            if (hourSelect) {
                const prevHour = hourSelect.value;
                hourSelect.replaceChildren();
                const opts = sessionHourOptions[validSession] || sessionHourOptions.MORNING;
                opts.forEach(o => hourSelect.appendChild(option(o.value, o.label)));
                if (opts.some(o => o.value === prevHour)) {
                    hourSelect.value = prevHour;
                } else {
                    hourSelect.value = '';
                }
            }
            updateCreateModalSubtitle();
        }

        function updateCreateModalSubtitle() {
            const dateVal = $('modalAppointmentDate')?.value || dateInput.value || today();
            const sessVal = $('modalSession')?.value || 'MORNING';
            const sessNames = { MORNING: 'Ca Sáng', AFTERNOON: 'Ca Chiều', EVENING: 'Ca Tối' };
            if ($('createModalSubtitle')) {
                $('createModalSubtitle').textContent = `Ngày ${dateLabel(dateVal)} · ${sessNames[sessVal] || 'Ca Sáng'}`;
            }
        }

        document.querySelectorAll('#modalSessionGroup [data-modal-session]').forEach(btn => {
            btn.addEventListener('click', () => {
                const sess = btn.getAttribute('data-modal-session') || 'MORNING';
                setModalSession(sess);
                loadModalDoctors();
            });
        });

        // Minor guardian toggle based on DOB
        $('modalPatientDob')?.addEventListener('change', (e) => {
            const val = e.target.value;
            const guardianContainer = $('modalGuardianContainer');
            if (!val || !guardianContainer) return;
            const birthDate = new Date(val);
            const now = new Date();
            let age = now.getFullYear() - birthDate.getFullYear();
            const m = now.getMonth() - birthDate.getMonth();
            if (m < 0 || (m === 0 && now.getDate() < birthDate.getDate())) {
                age--;
            }
            if (age < 18) {
                guardianContainer.style.display = 'block';
            } else {
                guardianContainer.style.display = 'none';
            }
        });

        // Load doctors for Modal
        function loadModalBranches() {
            const modalBranch = $('modalBranchId');
            if (!modalBranch) return;
            branches(modalBranch, options);
            const selectedBranch = branch.value || (options.branches.length === 1 ? String(options.branches[0].id) : '');
            if (selectedBranch && [...modalBranch.options].some(item => item.value === selectedBranch)) {
                modalBranch.value = selectedBranch;
            } else if (!modalBranch.value && modalBranch.options.length > 1) {
                modalBranch.selectedIndex = 1;
            }
        }

        async function loadModalDoctors() {
            const select = $('modalDoctorId');
            const modalBranch = $('modalBranchId');
            const appointmentDate = $('modalAppointmentDate');
            const help = $('modalDoctorHelp');
            if (!select) return;
            select.disabled = true;
            select.replaceChildren(option('', 'Đang tải bác sĩ trực…'));
            const branchId = modalBranch?.value;
            const date = appointmentDate?.value;
            const session = $('modalSession')?.value || 'MORNING';
            if (!branchId || !date) {
                select.replaceChildren(option('', 'Chọn chi nhánh và ngày hẹn trước'));
                if (help) help.textContent = 'Chọn chi nhánh và ngày hẹn để xem danh sách bác sĩ trực.';
                return;
            }
            try {
                const list = await request(`/doctors?branchId=${encodeURIComponent(branchId)}&date=${encodeURIComponent(date)}`);
                select.replaceChildren(option('', '-- Chọn bác sĩ phụ trách --'));
                list.forEach(d => select.appendChild(option(d.id, `BS. ${d.name}${d.specialty ? ` · ${d.specialty}` : ''}`)));
                select.disabled = false;
                const sessNames = { MORNING: 'Ca Sáng', AFTERNOON: 'Ca Chiều', EVENING: 'Ca Tối' };
                if (help) help.textContent = list.length
                    ? `Có ${list.length} bác sĩ trực (${sessNames[session] || 'Ca Sáng'}) ngày ${dateLabel(date)}.`
                    : `Chưa có bác sĩ phân ca trực ngày ${dateLabel(date)}.`;
            } catch (err) {
                select.replaceChildren(option('', 'Không tải được danh sách bác sĩ'));
                if (help) help.textContent = err.message || 'Không tải được danh sách bác sĩ trực.';
            }
        }

        // Load services for Modal
        function loadModalServices() {
            const select = $('modalServiceId');
            if (!select) return;
            select.replaceChildren(option('', '-- Chọn dịch vụ --'));
            options.services.forEach(s => select.appendChild(option(s.id, s.name)));
        }

        async function openCreateModalWith(targetDate, targetSession) {
            if (!createModal) return;
            $('createAppointmentForm')?.reset();
            $('modalProfileId').value = '';
            $('modalPatientCode').value = '';
            $('selectedPatientChip').style.display = 'none';
            if ($('newPatientFieldsContainer')) $('newPatientFieldsContainer').style.display = 'block';
            if ($('modalGuardianContainer')) $('modalGuardianContainer').style.display = 'none';
            $('modalPatientSearch').value = '';
            const chosenDate = targetDate || dateInput.value || today();
            const chosenSession = targetSession || $('advFilterSession')?.value || 'MORNING';
            $('modalAppointmentDate').value = chosenDate;
            loadModalBranches();
            loadModalServices();
            setModalSession(chosenSession);
            createModal.show();
            await loadModalDoctors();
        }

        $('openCreateModalBtn')?.addEventListener('click', () => {
            openCreateModalWith(dateInput.value || today(), $('advFilterSession')?.value || 'MORNING');
        });

        $('modalBranchId')?.addEventListener('change', () => loadModalDoctors());
        $('modalAppointmentDate')?.addEventListener('change', () => {
            updateCreateModalSubtitle();
            loadModalDoctors();
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
                    let patientBranchId = $('modalBranchId')?.value || $('filterBranch')?.value || '';
                    if (!patientBranchId && options?.branches?.length) {
                        patientBranchId = String(options.branches[0].id);
                        if ($('modalBranchId')) $('modalBranchId').value = patientBranchId;
                    }
                    const url = patientBranchId
                        ? `/patients?branchId=${encodeURIComponent(patientBranchId)}&query=${encodeURIComponent(query)}`
                        : `/patients?query=${encodeURIComponent(query)}`;
                    const patients = await request(url);
                    resultsBox.replaceChildren();
                    if (!patients || !patients.length) {
                        resultsBox.innerHTML = '<div class="p-2 text-muted small">Không tìm thấy khách hàng cũ. Bạn có thể nhập thông tin khách mới bên dưới.</div>';
                    } else {
                        patients.forEach(p => {
                            const item = document.createElement('div');
                            item.className = 'patient-autocomplete-item';
                            item.innerHTML = `<strong>${escapeHtml(p.name)}</strong> <span class="badge bg-light text-secondary border font-monospace ms-1">${escapeHtml(p.code)}</span> <span class="text-muted ms-1">· ${escapeHtml(p.phone)}</span>`;
                            item.addEventListener('click', () => {
                                $('modalProfileId').value = p.id;
                                $('modalPatientCode').value = p.code || '';
                                $('modalPatientName').value = p.name;
                                $('modalPatientPhone').value = p.phone || '';
                                $('selectedPatientName').textContent = p.name;
                                $('selectedPatientCode').textContent = `(${p.code})`;
                                $('selectedPatientChip').style.display = 'flex';
                                if ($('newPatientFieldsContainer')) $('newPatientFieldsContainer').style.display = 'none';
                                resultsBox.style.display = 'none';
                            });
                            resultsBox.appendChild(item);
                        });
                    }
                    resultsBox.style.display = 'block';
                } catch (err) {
                    resultsBox.innerHTML = '<div class="p-2 text-muted small">Không tìm thấy hồ sơ hoặc lỗi kết nối. Bạn có thể nhập khách mới bên dưới.</div>';
                    resultsBox.style.display = 'block';
                }
            }, 250);
        });

        $('clearSelectedPatientBtn')?.addEventListener('click', (event) => {
            event.preventDefault();
            event.stopPropagation();
            clearTimeout(patientSearchTimer);
            $('modalProfileId').value = '';
            $('modalPatientCode').value = '';
            $('selectedPatientChip').style.display = 'none';
            if ($('newPatientFieldsContainer')) $('newPatientFieldsContainer').style.display = 'block';
            $('modalPatientSearch').value = '';
            $('modalPatientSearch').disabled = false;
            $('modalPatientResults').style.display = 'none';
            $('newPatientFieldsContainer')?.querySelectorAll('input, select, textarea').forEach(field => { field.disabled = false; });
            $('modalPatientName').value = '';
            $('modalPatientPhone').value = '';
            $('modalPatientAddress').value = '';
            if ($('modalPatientDob')) $('modalPatientDob').value = '';
            if ($('modalPatientGender')) $('modalPatientGender').value = '';
            if ($('modalGuardianContainer')) $('modalGuardianContainer').style.display = 'none';
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
            const modalBranchId = $('modalBranchId').value ? Number($('modalBranchId').value) : null;
            const isTentative = $('modalIsTentative')?.checked || false;

            if (!modalBranchId) {
                alert('Vui lòng chọn chi nhánh cho lịch hẹn.');
                $('modalBranchId').focus();
                return;
            }
            if (!date) {
                alert('Vui lòng chọn ngày hẹn.');
                return;
            }
            if (!doctorId) {
                alert('Vui lòng chọn bác sĩ trực.');
                $('modalDoctorId').focus();
                return;
            }
            if (!symptoms) {
                alert('Vui lòng nhập nội dung hẹn khám.');
                $('modalSymptoms').focus();
                return;
            }

            let patientDob = null;
            let patientGender = null;
            let guardianName = null;
            let guardianPhone = null;
            let guardianRelationship = null;

            if (!profileId) {
                // Đăng ký bệnh nhân mới: BẮT BUỘC cung cấp thông tin chuẩn (chống tạo dữ liệu giả - CV-01)
                const dobVal = $('modalPatientDob')?.value;
                const genderVal = $('modalPatientGender')?.value;
                const phoneVal = $('modalPatientPhone')?.value.trim();

                if (!patientName) {
                    alert('Vui lòng nhập họ tên khách hàng.');
                    $('modalPatientName').focus();
                    return;
                }
                if (!dobVal) {
                    alert('Vui lòng chọn ngày sinh cho bệnh nhân mới.');
                    $('modalPatientDob').focus();
                    return;
                }
                if (!genderVal) {
                    alert('Vui lòng chọn giới tính cho bệnh nhân mới.');
                    $('modalPatientGender').focus();
                    return;
                }
                if (!phoneVal || !/^0\d{9,10}$/.test(phoneVal)) {
                    alert('Số điện thoại bệnh nhân phải gồm 10 hoặc 11 chữ số và bắt đầu bằng 0.');
                    $('modalPatientPhone').focus();
                    return;
                }

                patientDob = dobVal;
                patientGender = genderVal;

                // Kiểm tra người giám hộ nếu dưới 18 tuổi (BR-PAT-09)
                const birthDate = new Date(dobVal);
                const now = new Date();
                let age = now.getFullYear() - birthDate.getFullYear();
                const m = now.getMonth() - birthDate.getMonth();
                if (m < 0 || (m === 0 && now.getDate() < birthDate.getDate())) {
                    age--;
                }
                if (age < 18) {
                    guardianName = $('modalGuardianName')?.value.trim();
                    guardianPhone = $('modalGuardianPhone')?.value.trim();
                    guardianRelationship = $('modalGuardianRelationship')?.value.trim();
                    if (!guardianName || !guardianPhone || !guardianRelationship) {
                        alert('Bệnh nhân dưới 18 tuổi bắt buộc nhập họ tên, số điện thoại và quan hệ của người giám hộ (BR-PAT-09).');
                        $('modalGuardianName')?.focus();
                        return;
                    }
                    if (!/^0\d{9,10}$/.test(guardianPhone)) {
                        alert('Số điện thoại người giám hộ phải gồm 10 hoặc 11 chữ số và bắt đầu bằng 0.');
                        $('modalGuardianPhone')?.focus();
                        return;
                    }
                }
            }

            // Session & Expected Time
            const selectedSession = $('modalSession')?.value || 'MORNING';
            const hour = $('modalHour')?.value;
            const minute = $('modalMinute')?.value || '00';
            const expectedTime = hour ? `${hour}:${minute}:00` : null;

            const payload = {
                branchId: modalBranchId,
                profileId: profileId,
                patientName: patientName,
                patientPhone: $('modalPatientPhone').value.trim(),
                patientAddress: $('modalPatientAddress').value.trim(),
                customerSource: $('modalCustomerSource').value || 'Vãng lai',
                patientDob: patientDob,
                patientGender: patientGender,
                guardianName: guardianName,
                guardianPhone: guardianPhone,
                guardianRelationship: guardianRelationship,
                doctorId: doctorId,
                date: date,
                session: selectedSession,
                expectedTime: expectedTime,
                durationMinutes: Number($('modalDuration')?.value || 30),
                serviceId: serviceId,
                symptoms: symptoms,
                internalNote: $('modalInternalNote').value.trim(),
                isTentative: isTentative
            };

            const saveBtn = $('saveAppointmentBtn');
            const originalText = saveBtn.textContent;
            saveBtn.disabled = true;
            saveBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1" role="status"></span> Đang lưu...';

            try {
                await request('', {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                createModal.hide();
                await load();
            } catch (err) {
                if (err.message && err.message.includes('trùng khớp')) {
                    if (confirm(err.message + '\n\nBạn có muốn xác nhận tiếp tục tạo hồ sơ mới cho bệnh nhân này không?')) {
                        payload.confirmDuplicate = true;
                        try {
                            await request('', {
                                method: 'POST',
                                body: JSON.stringify(payload)
                            });
                            createModal.hide();
                            await load();
                            return;
                        } catch (retryErr) {
                            alert('Không thể tạo lịch hẹn: ' + retryErr.message);
                            return;
                        }
                    }
                }
                alert('Không thể tạo lịch hẹn: ' + err.message);
            } finally {
                saveBtn.disabled = false;
                saveBtn.textContent = originalText;
            }
        });

        // =========================================================
        // MODAL 3: SỬA / ĐỔI LỊCH HẸN (TẠI CHỖ QUA MODAL, THOÁNG ĐÃNG)
        // =========================================================
        const editModalEl = $('editAppointmentModal');
        const editModal = editModalEl ? new bootstrap.Modal(editModalEl) : null;
        let activeAppointmentIdForEdit = null;

        function setEditModalSession(sessionKey) {
            const validSession = ['MORNING', 'AFTERNOON', 'EVENING'].includes(sessionKey) ? sessionKey : 'MORNING';
            if ($('editSession')) {
                $('editSession').value = validSession;
            }
            document.querySelectorAll('#editSessionGroup [data-edit-session]').forEach(btn => {
                btn.classList.toggle('active', btn.getAttribute('data-edit-session') === validSession);
            });
            const hourSelect = $('editHour');
            if (hourSelect) {
                const prevHour = hourSelect.value;
                hourSelect.replaceChildren();
                const opts = sessionHourOptions[validSession] || sessionHourOptions.MORNING;
                opts.forEach(o => hourSelect.appendChild(option(o.value, o.label)));
                if (opts.some(o => o.value === prevHour)) {
                    hourSelect.value = prevHour;
                } else {
                    hourSelect.value = '';
                }
            }
        }

        document.querySelectorAll('#editSessionGroup [data-edit-session]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const sessionKey = btn.getAttribute('data-edit-session');
                setEditModalSession(sessionKey);
                const branchId = $('editBranchId')?.value || branch.value;
                const date = $('editAppointmentDate')?.value;
                if (branchId && date) {
                    await loadEditModalDoctors(branchId, date, $('editDoctorId')?.value);
                }
            });
        });

        async function loadEditModalDoctors(branchId, date, selectedDoctorId) {
            const select = $('editDoctorId');
            const help = $('editDoctorHelp');
            if (!select) return;
            select.disabled = true;
            select.replaceChildren(option('', 'Đang tải bác sĩ trực…'));
            if (!branchId || !date) {
                select.replaceChildren(option('', '-- Chưa chỉ định bác sĩ --'));
                if (help) help.textContent = 'Chọn ngày hẹn để xem danh sách bác sĩ trực.';
                select.disabled = false;
                return;
            }
            try {
                const list = await request(`/doctors?branchId=${encodeURIComponent(branchId)}&date=${encodeURIComponent(date)}`);
                select.replaceChildren(option('', '-- Chưa chỉ định bác sĩ --'));
                list.forEach(d => select.appendChild(option(d.id, `BS. ${d.name}${d.specialty ? ` · ${d.specialty}` : ''}`)));
                if (selectedDoctorId && [...select.options].some(o => o.value === String(selectedDoctorId))) {
                    select.value = String(selectedDoctorId);
                } else {
                    select.value = '';
                }
                select.disabled = false;
                const sessNames = { MORNING: 'Ca Sáng', AFTERNOON: 'Ca Chiều', EVENING: 'Ca Tối' };
                const curSess = $('editSession')?.value || 'MORNING';
                if (help) help.textContent = list.length
                    ? `Có ${list.length} bác sĩ trực (${sessNames[curSess] || 'Ca Sáng'}) ngày ${dateLabel(date)}.`
                    : `Chưa có bác sĩ phân ca trực ngày ${dateLabel(date)}.`;
            } catch (err) {
                select.replaceChildren(option('', '-- Chưa chỉ định bác sĩ --'));
                select.disabled = false;
                if (help) help.textContent = err.message || 'Không tải được danh sách bác sĩ trực.';
            }
        }

        function loadEditModalServices(selectedServiceId) {
            const select = $('editServiceId');
            if (!select || !options.services) return;
            select.replaceChildren(option('', '-- Chọn dịch vụ --'));
            options.services.forEach(s => select.appendChild(option(s.id, s.name)));
            if (selectedServiceId) {
                select.value = String(selectedServiceId);
            }
        }

        $('editAppointmentDate')?.addEventListener('change', async (e) => {
            const branchId = $('editBranchId')?.value || branch.value;
            const date = e.target.value;
            if (branchId && date) {
                await loadEditModalDoctors(branchId, date, $('editDoctorId')?.value);
            }
        });

        async function openEditAppointmentModal(appointment) {
            if (!editModal) return;
            activeAppointmentIdForEdit = appointment.id;

            if ($('editModalCode')) {
                $('editModalCode').textContent = appointment.code || ('LH' + appointment.id);
            }
            if ($('editAppointmentId')) $('editAppointmentId').value = appointment.id;
            const branchIdVal = appointment.branchId || branch.value || (options.branches[0]?.id || '');
            if ($('editBranchId')) $('editBranchId').value = branchIdVal;
            if ($('editBranchName')) $('editBranchName').value = appointment.branchName || 'Cơ sở phòng khám';

            const patientCodeVal = appointment.patientCode || (appointment.profileId ? `BN#${appointment.profileId}` : 'Khách mới');
            if ($('editPatientCode')) $('editPatientCode').value = patientCodeVal;
            if ($('editPatientName')) $('editPatientName').value = appointment.patientName || 'Khách hàng';

            const age = calculateAge(appointment.patientDob);
            if ($('editPatientDobAge')) {
                const dobStr = appointment.patientDob ? dateLabel(appointment.patientDob) : '—';
                $('editPatientDobAge').value = age !== null ? `${dobStr} (${age} tuổi)` : dobStr;
            }
            if ($('editPatientGender')) $('editPatientGender').value = appointment.patientGender || '—';
            if ($('editPatientPhone')) $('editPatientPhone').value = appointment.patientPhone || '—';

            const guardianContainer = $('editGuardianContainer');
            if (guardianContainer) {
                guardianContainer.style.display = (age !== null && age < 18) ? 'block' : 'none';
            }
            if ($('editPatientAddress')) $('editPatientAddress').value = appointment.branchName || 'Khám tại phòng khám';
            if ($('editCustomerSource')) $('editCustomerSource').value = appointment.source || 'Vãng lai (Trực tiếp)';

            const aptDate = appointment.date || today();
            if ($('editAppointmentDate')) {
                $('editAppointmentDate').value = aptDate;
                $('editAppointmentDate').min = today();
            }

            const sessKey = resolveAppointmentSession(appointment);
            setEditModalSession(sessKey);

            if (appointment.expectedTime) {
                const timeParts = String(appointment.expectedTime).slice(0, 5).split(':');
                if ($('editHour')) $('editHour').value = timeParts[0] || '';
                if ($('editMinute')) $('editMinute').value = timeParts[1] || '00';
            } else {
                if ($('editHour')) $('editHour').value = '';
                if ($('editMinute')) $('editMinute').value = '00';
            }

            loadEditModalServices(appointment.serviceId);
            await loadEditModalDoctors(branchIdVal, aptDate, appointment.doctorId);

            const rawNotes = appointment.symptoms || '';
            const noteMatch = rawNotes.match(/(?:\s*-\s*)?\[Ghi chú: ([\s\S]*?)\]$/);
            const content = rawNotes.replace(/(?:\s*-\s*)?\[Ghi chú: [\s\S]*?\]$/, '').trim();
            if ($('editSymptoms')) $('editSymptoms').value = content || 'Khám tổng quát';
            if ($('editReason')) $('editReason').value = 'Khách hàng yêu cầu đổi lịch khám';

            editModal.show();
        }

        $('saveEditAppointmentBtn')?.addEventListener('click', async () => {
            const id = activeAppointmentIdForEdit || $('editAppointmentId')?.value;
            if (!id) return;
            const date = $('editAppointmentDate')?.value;
            const session = $('editSession')?.value || 'MORNING';
            const doctorId = $('editDoctorId')?.value ? Number($('editDoctorId').value) : null;
            const serviceId = $('editServiceId')?.value ? Number($('editServiceId').value) : null;
            const symptoms = $('editSymptoms')?.value.trim();
            const reason = $('editReason')?.value.trim();

            if (!date) {
                alert('Vui lòng chọn ngày hẹn.');
                $('editAppointmentDate')?.focus();
                return;
            }
            if (!symptoms) {
                alert('Vui lòng nhập nội dung hẹn.');
                $('editSymptoms')?.focus();
                return;
            }
            if (!reason) {
                alert('Vui lòng nhập lý do thay đổi lịch hẹn.');
                $('editReason')?.focus();
                return;
            }

            const hour = $('editHour')?.value;
            const minute = $('editMinute')?.value || '00';
            const expectedTime = hour ? `${hour}:${minute}` : null;

            const payload = {
                date: date,
                session: session,
                doctorId: doctorId,
                serviceId: serviceId,
                symptoms: symptoms,
                reason: reason,
                expectedTime: expectedTime
            };

            const btn = $('saveEditAppointmentBtn');
            const originalText = btn.textContent;
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1" role="status"></span> Đang lưu...';

            try {
                await request(`/${id}`, {
                    method: 'PUT',
                    body: JSON.stringify(payload)
                });
                editModal.hide();
                await load();
            } catch (err) {
                alert('Không thể cập nhật lịch hẹn: ' + err.message);
            } finally {
                btn.disabled = false;
                btn.textContent = originalText;
            }
        });

        // =========================================================
        // CALENDAR VIEW RENDERING (THEO NGÀY / TUẦN / THÁNG + CHIA CA SÁNG / CHIỀU / TỐI)
        // =========================================================
        async function renderCalendarView(filteredList) {
            const reference = fromDate(dateInput.value || today());
            const isMonth = currentPeriod === 'month';
            const isDay = currentPeriod === 'day';

            let first, last;
            if (isMonth) {
                const start = new Date(Date.UTC(reference.getUTCFullYear(), reference.getUTCMonth(), 1));
                const dayOfWeek = (start.getUTCDay() + 6) % 7;
                first = addDays(start, -dayOfWeek);
                last = addDays(first, 41);
                if ($('calendarSectionTitle')) {
                    $('calendarSectionTitle').textContent = `Lịch hẹn Tháng ${reference.getUTCMonth() + 1}/${reference.getUTCFullYear()}`;
                }
            } else if (isDay) {
                first = new Date(reference);
                last = new Date(reference);
                if ($('calendarSectionTitle')) {
                    $('calendarSectionTitle').textContent = `Ô lịch theo Ca — ${formatDayWithWeekday(iso(reference))}`;
                }
            } else {
                const dayOfWeek = (reference.getUTCDay() + 6) % 7;
                first = addDays(reference, -dayOfWeek);
                last = addDays(first, 6);
                if ($('calendarSectionTitle')) {
                    $('calendarSectionTitle').textContent = `Lịch hẹn Tuần từ ${dateLabel(iso(first))} đến ${dateLabel(iso(last))}`;
                }
            }

            let list = Array.isArray(filteredList) ? filteredList : currentAppointments;
            if (isMonth) {
                const params = new URLSearchParams({ start: iso(first), end: iso(last) });
                if (branch.value) params.set('branchId', branch.value);
                if (doctor.value) params.set('doctorId', doctor.value);
                try {
                    list = await request(`/calendar?${params}`);
                } catch (e) {
                    list = currentAppointments;
                }
            }

            const grid = $('calendarGrid');
            if (!grid) return;
            grid.replaceChildren();
            grid.classList.toggle('is-week', !isMonth);
            grid.style.gridTemplateColumns = isDay ? 'minmax(0, 1fr)' : 'repeat(7, minmax(0, 1fr))';

            if (!isDay) {
                ['Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7', 'Chủ nhật'].forEach(label => {
                    const header = document.createElement('div');
                    header.className = 'appointment-calendar-header';
                    header.textContent = label;
                    grid.appendChild(header);
                });
            }

            let current = new Date(first);
            const isWeek = !isMonth && !isDay;
            const maxVisible = isMonth ? 4 : 6;

            while (current <= last) {
                const dayIso = iso(current);
                const dayCell = document.createElement('div');
                dayCell.className = 'appointment-day';
                if (isMonth && current.getUTCMonth() !== reference.getUTCMonth()) {
                    dayCell.classList.add('is-outside');
                }
                if (dayIso === today()) {
                    dayCell.classList.add('is-today');
                }

                const events = list.filter(a => a.date === dayIso);
                const dayHeader = document.createElement('div');
                dayHeader.className = 'd-flex justify-content-between align-items-center mb-1.5 pb-1 border-bottom';
                dayHeader.innerHTML = `
                    <span class="calendar-day-number mb-0 fw-bold">
                        ${isDay ? escapeHtml(formatDayWithWeekday(dayIso)) : `${current.getUTCDate()}/${current.getUTCMonth() + 1}`}
                        ${events.length ? `<span class="badge bg-light text-primary border ms-1">${events.length} ca</span>` : ''}
                    </span>
                `;
                const quickAddDayBtn = document.createElement('button');
                quickAddDayBtn.type = 'button';
                quickAddDayBtn.className = 'cal-quick-add';
                quickAddDayBtn.title = `Thêm lịch hẹn ngày ${dateLabel(dayIso)}`;
                quickAddDayBtn.innerHTML = '<i class="bi bi-plus"></i>';
                quickAddDayBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    openCreateModalWith(dayIso, $('advFilterSession')?.value || 'MORNING');
                });
                dayHeader.appendChild(quickAddDayBtn);
                dayCell.appendChild(dayHeader);

                // Cả hai chế độ xem Tuần và Tháng đều thiết kế thống nhất theo dạng ô lịch:
                // Hiển thị thời gian theo Ca (Sáng / Chiều / Tối) thay vì giờ, không chia rãnh ca rối mắt
                const visibleEvents = isDay ? events : events.slice(0, maxVisible);
                visibleEvents.forEach(a => {
                    const event = document.createElement('div');
                    event.className = 'calendar-event';
                    event.dataset.status = a.status;
                    event.style.cursor = 'pointer';

                    const sessKey = resolveAppointmentSession(a);
                    const sessBadgeMap = {
                        MORNING: { text: 'Sáng', cls: 'morning', full: 'Ca Sáng (08:00–12:00)' },
                        AFTERNOON: { text: 'Chiều', cls: 'afternoon', full: 'Ca Chiều (13:30–17:30)' },
                        EVENING: { text: 'Tối', cls: 'evening', full: 'Ca Tối (17:30–20:30)' }
                    };
                    const badgeInfo = sessBadgeMap[sessKey] || { text: 'Sáng', cls: 'morning', full: 'Ca Sáng' };

                    event.innerHTML = `
                        <span class="cal-session-badge ${badgeInfo.cls}">${badgeInfo.text}</span>
                        <span class="text-truncate fw-medium">${escapeHtml(a.patientName || a.patientCode)}</span>
                    `;
                    event.title = `${a.patientName || a.patientCode} · ${badgeInfo.full} · ${a.doctorName || 'Chưa chỉ định BS'}${a.serviceName ? ' · ' + a.serviceName : ''}`;
                    event.addEventListener('click', () => openQuickStatusModal(a));
                    dayCell.appendChild(event);
                });

                if (!isDay && events.length > maxVisible) {
                    const more = document.createElement('button');
                    more.type = 'button';
                    more.className = 'calendar-more text-primary small cursor-pointer border-0 bg-transparent p-0 mt-1 fw-semibold';
                    more.textContent = `+${events.length - maxVisible} ca khác`;
                    more.addEventListener('click', (ev) => {
                        ev.preventDefault();
                        ev.stopPropagation();
                        openDayAppointmentsModal(dayIso, events, openQuickStatusModal);
                    });
                    dayCell.appendChild(more);
                }

                grid.appendChild(dayCell);
                current = addDays(current, 1);
            }
        }

        // Calendar Next/Prev navigation
        $('calPrev')?.addEventListener('click', () => {
            $('prevDateBtn')?.click();
        });

        $('calNext')?.addEventListener('click', () => {
            $('nextDateBtn')?.click();
        });

        // Select changes
        branch.addEventListener('change', async () => {
            try {
                await loadDoctors();
                if (createModalEl?.classList.contains('show')) {
                    const modalBranch = $('modalBranchId');
                    if (modalBranch && branch.value) modalBranch.value = branch.value;
                    await loadModalDoctors();
                }
                await load();
            } catch (e) {
                error(e.message);
            }
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
                events.slice(0, month ? 3 : 30).forEach(a => {
                    const event = document.createElement('a');
                    event.className = 'calendar-event';
                    event.dataset.status = a.status;
                    event.href = `/reception/appointments/${encodeURIComponent(a.id)}`;
                    const sessKey = resolveAppointmentSession(a);
                    const sessBadgeMap = {
                        MORNING: { text: 'Sáng', cls: 'morning' },
                        AFTERNOON: { text: 'Chiều', cls: 'afternoon' },
                        EVENING: { text: 'Tối', cls: 'evening' }
                    };
                    const badgeInfo = sessBadgeMap[sessKey] || { text: 'Sáng', cls: 'morning' };
                    event.innerHTML = `<span class="cal-session-badge ${badgeInfo.cls}">${badgeInfo.text}</span> <span class="text-truncate">${escapeHtml(a.patientName || a.patientCode)}</span>`;
                    event.title = `${a.patientName || ''} · ${statusName[a.status] || a.status}`;
                    dayLink.appendChild(event);
                });
                if (month && events.length > 3) {
                    const more = document.createElement('button');
                    more.type = 'button';
                    more.className = 'calendar-more border-0 bg-transparent p-0';
                    more.textContent = `+${events.length - 3} lịch khác`;
                    more.setAttribute('aria-label', `Xem ${events.length - 3} lịch hẹn khác ngày ${dateLabel(dayIso)}`);
                    more.addEventListener('click', event => {
                        event.preventDefault();
                        event.stopPropagation();
                        openDayAppointmentsModal(dayIso, events, openReadOnlyAppointmentDetails);
                    });
                    dayLink.appendChild(more);
                }
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
            const queryParam = new URLSearchParams(location.search).get('query');
            if (queryParam) {
                $('patientSearch').value = queryParam;
                setTimeout(() => findPatient().catch(e => error(e.message)), 150);
            }
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
                $('doctorWarning').innerHTML = `<div><strong><i class="bi bi-info-circle-fill me-1"></i> Lưu ý điều phối:</strong> ${escapeHtml(a.conflictNotes)}</div> <a href="/reception/dispatch?branchId=${a.branchId || ''}" class="btn btn-sm btn-primary ms-3 text-nowrap"><i class="bi bi-diagram-3 me-1"></i>Đến Bàn điều phối ghế nha</a>`;
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
                    rForm.onsubmit = () => confirm('Bạn có chắc chắn muốn hoàn tác tiếp đón cho bệnh nhân này? Lịch hẹn sẽ quay về trạng thái Đã xác nhận và lượt khám chờ sẽ bị hủy.');
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
