(() => {
    const api = '/portal/appointments/api';
    const $ = id => document.getElementById(id);
    const sessionLabel = {MORNING: 'Buổi sáng', AFTERNOON: 'Buổi chiều', EVENING: 'Buổi tối'};
    const statusLabel = {PENDING: 'Đang chờ phòng khám xác nhận', CONFIRMED: 'Đã xác nhận', CHECKED_IN: 'Đã đến',
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
        if (value !== undefined && value !== null && value !== '') {
            el.textContent = value;
        }
        row.appendChild(el);
        return el;
    }
    function dateLabel(value) {
        if (!value) return '—';
        const [year, month, day] = value.split('-');
        return `${day}/${month}/${year}`;
    }
    function formatSnapshot(raw) {
        if (!raw) return null;
        const parts = raw.split('|');
        if (parts.length < 2) return null;
        const date = dateLabel(parts[0]);
        const session = sessionLabel[parts[1]] || parts[1];
        let doctorText = 'Không yêu cầu bác sĩ đích danh';
        let statusText = '';
        for (let i = 2; i < parts.length; i++) {
            const p = parts[i];
            if (p.startsWith('doctor=')) {
                const docVal = p.substring(7);
                if (docVal && docVal !== 'null' && docVal !== 'none') {
                    doctorText = 'Bác sĩ chỉ định';
                }
            } else if (p.startsWith('status=')) {
                const statVal = p.substring(7);
                statusText = statusLabel[statVal] || statVal;
            }
        }
        return { date, session, doctorText, statusText };
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
                    tkBadge.textContent = 'Tái khám';
                    tkBadge.title = 'Lịch hẹn tái khám theo chỉ định của bác sĩ (FT18)';
                    codeTd.appendChild(tkBadge);
                }

                // Column 2: Ngày và buổi
                const dateTd = cell(row);
                dateTd.innerHTML = `<span class="fw-medium">${dateLabel(a.date)}</span> <span class="badge bg-light text-secondary border ms-1">${sessionLabel[a.session] || a.session}</span>`;

                // Column 3: Chi nhánh
                cell(row, a.branchName);

                // Column 4: Bác sĩ yêu cầu
                const doctorTd = cell(row);
                if (a.requestedDoctorName) {
                    doctorTd.innerHTML = `<i class="bi bi-person-fill text-primary me-1"></i><span>${a.requestedDoctorName}</span>`;
                } else {
                    doctorTd.innerHTML = `<span class="text-muted">Không yêu cầu</span>`;
                }
                if (a.code.startsWith('TK')) {
                    const docSub = document.createElement('small');
                    docSub.style.cssText = 'display: block; color: #7c3aed; font-size: 11px;';
                    docSub.textContent = 'Bác sĩ chỉ định tái khám';
                    doctorTd.appendChild(docSub);
                }

                // Column 5: Trạng thái
                const statusTd = cell(row);
                const badge = document.createElement('span');
                badge.className = `badge rounded-pill ${statusClass[a.status] || 'bg-secondary'}`;
                badge.style.padding = '6px 12px';
                badge.textContent = statusLabel[a.status] || a.status;
                statusTd.appendChild(badge);

                // Column 6: Thao tác
                const actionTd = cell(row);
                const link = document.createElement('a');
                link.className = 'btn btn-sm btn-outline-primary fw-medium';
                link.href = `/portal/appointments/${a.id}`;
                link.innerHTML = '<i class="bi bi-eye me-1"></i>Xem chi tiết';
                actionTd.appendChild(link);

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
            if (current.code && current.code.startsWith('TK')) {
                const notice = document.createElement('div');
                notice.className = 'alert alert-warning mb-3';
                notice.style.borderRadius = '10px';
                notice.innerHTML = '<i class="bi bi-info-circle-fill me-2"></i><strong>Lưu ý đổi lịch tái khám:</strong> Lịch này do bác sĩ chỉ định sau điều trị. Khi gửi yêu cầu đổi ngày hoặc buổi, lịch sẽ chuyển về trạng thái <strong>Chờ phòng khám xác nhận</strong> để lễ tân sắp xếp lại bác sĩ phù hợp.';
                form.insertBefore(notice, form.querySelector('.patient-form-grid') || form.firstChild);
            }
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
        if (a.code && a.code.startsWith('TK')) {
            const reExamCard = document.createElement('div');
            reExamCard.className = 'alert alert-primary mb-3';
            reExamCard.style.cssText = 'background: #f5f3ff; border: 1px solid #ddd6fe; color: #5b21b6; border-radius: 12px; padding: 14px 16px;';
            reExamCard.innerHTML = `<div class="d-flex align-items-start gap-2.5">
                <i class="bi bi-calendar2-check-fill fs-4" style="color: #7c3aed; margin-top: -2px;"></i>
                <div>
                    <h5 class="fw-bold mb-1" style="font-size: 15px; color: #5b21b6;">Lịch hẹn tái khám theo chỉ định bác sĩ (FT18)</h5>
                    <p class="mb-0 small" style="color: #6d28d9; line-height: 1.5;">Đây là lịch hẹn tái khám sau điều trị. Vui lòng theo dõi lời dặn dò của bác sĩ và đến đúng ngày để được kiểm tra tiến trình hồi phục vết thương.</p>
                </div>
            </div>`;
            $('appointmentDetail').insertBefore(reExamCard, facts);
        }
        if (a.status === 'PENDING') {
            const pendingNote = document.createElement('div');
            pendingNote.className = 'alert alert-info mb-3';
            pendingNote.innerHTML = '<i class="bi bi-hourglass-split me-1"></i> <strong>Đang chờ phòng khám xác nhận:</strong> Lễ tân sẽ kiểm tra lịch bác sĩ và liên hệ xác nhận chính thức.';
            $('appointmentDetail').insertBefore(pendingNote, facts);
        }
        const items = [
            ['Mã lịch', a.code + (a.code.startsWith('TK') ? ' (Lịch tái khám sau điều trị)' : '')],
            ['Bệnh nhân', a.patientName],
            ['Chi nhánh', a.branchName],
            ['Ngày khám', dateLabel(a.date)],
            ['Buổi khám', sessionLabel[a.session] || a.session],
            [a.code.startsWith('TK') ? 'Bác sĩ phụ trách tái khám' : 'Bác sĩ yêu cầu', a.requestedDoctorName || (a.code.startsWith('TK') ? 'Bác sĩ điều trị' : 'Không yêu cầu · lễ tân điều phối khi đến')],
            ['Dịch vụ quan tâm', a.serviceName || (a.code.startsWith('TK') ? 'Tái khám theo dõi chuyên khoa' : 'Chưa chọn')],
            [a.code.startsWith('TK') ? 'Lời dặn dò của bác sĩ' : 'Tình trạng ban đầu', a.symptoms || 'Chưa khai báo'],
            ['Nguồn lịch', a.code.startsWith('TK') ? 'Bác sĩ hẹn tại phòng khám (FT18)' : 'Đặt trực tuyến (Portal)'],
            ['Trạng thái', statusLabel[a.status] || a.status],
            ['Số lần đổi lịch', String(a.rescheduleCount)],
            ['Lý do hủy', a.cancelReason || '—']
        ];
        facts.replaceChildren();
        items.forEach(([label, value]) => {
            const box = document.createElement('div'), heading = document.createElement('span'), content = document.createElement('strong');
            heading.textContent = label; content.textContent = value; box.append(heading, content); facts.appendChild(box);
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
                RESCHEDULE: { label: 'Đổi ngày / buổi khám', badge: 'bg-warning-subtle text-warning border-warning-subtle', icon: 'bi-arrow-repeat' },
                CONFIRM: { label: 'Phòng khám xác nhận', badge: 'bg-success-subtle text-success border-success-subtle', icon: 'bi-check-circle' },
                CANCEL: { label: 'Hủy lịch hẹn', badge: 'bg-danger-subtle text-danger border-danger-subtle', icon: 'bi-x-circle' },
                COMPLETE: { label: 'Hoàn tất lượt khám', badge: 'bg-info-subtle text-info border-info-subtle', icon: 'bi-check2-all' }
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
                        <span><i class="bi bi-calendar3 me-1"></i>Ngày hẹn: <strong>${snap.date}</strong> (${snap.session})</span>
                        <span class="mx-1.5 text-muted">·</span>
                        <span><i class="bi bi-person-badge me-1"></i>${snap.doctorText}</span>
                        ${snap.statusText ? `<span class="mx-1.5 text-muted">·</span><span class="badge bg-light text-dark border">${snap.statusText}</span>` : ''}
                    </div>`;
                } else if (h.newValue) {
                    snapText = `<div class="small mt-1 text-muted">${h.newValue}</div>`;
                }

                let reasonText = '';
                if (h.reason) {
                    reasonText = `<div class="small text-muted fst-italic mt-1 ps-2 border-start border-2 border-primary-subtle">
                        <i class="bi bi-chat-quote me-1"></i>${h.reason}
                    </div>`;
                }

                item.innerHTML = `
                    <div class="d-flex align-items-center gap-2">
                        <span class="badge ${act.badge} py-1 px-2 fw-semibold">
                            <i class="bi ${act.icon} me-1"></i>${act.label}
                        </span>
                        <span class="text-muted small fw-medium">Lần #${idx + 1}</span>
                    </div>
                    ${snapText}
                    ${reasonText}
                `;
                history.appendChild(item);
            });
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
