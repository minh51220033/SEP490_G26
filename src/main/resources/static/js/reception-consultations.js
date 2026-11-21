(() => {
    const apiBase = '/reception/consultations/api';
    const aptApiBase = '/reception/appointments/api';
    const POLL_INTERVAL_MS = 30000;

    const root = document.getElementById('reception-consultation-area');
    if (!root) return;

    const $ = id => document.getElementById(id);

    const statusLabels = {
        NEW: 'Yêu cầu mới',
        CONTACTED: 'Đã liên hệ',
        CONVERTED: 'Đã chuyển lịch',
        CLOSED: 'Đã đóng',
        CANCELLED: 'Đã đóng'
    };

    const actionLabels = {
        CLAIM: 'Nhận xử lý',
        CONTACT: 'Ghi nhận liên hệ',
        HANDOVER: 'Bàn giao phụ trách',
        CONVERT: 'Chuyển thành lịch hẹn',
        CLOSE: 'Đóng yêu cầu'
    };

    const contactResultLabels = {
        CONNECTED: 'Đã nghe máy / Trao đổi',
        NO_ANSWER: 'Không nghe máy',
        BUSY: 'Máy bận',
        WRONG_NUMBER: 'Sai số',
        CALLBACK_LATER: 'Hẹn gọi lại sau',
        DECLINED: 'Từ chối / Hủy nhu cầu'
    };

    const sessionLabels = {
        MORNING: 'Buổi sáng',
        AFTERNOON: 'Buổi chiều',
        EVENING: 'Buổi tối'
    };

    const state = {
        branchId: root.getAttribute('data-initial-branch-id') || '',
        status: '',
        keyword: '',
        createdFrom: '',
        createdTo: '',
        page: 0,
        size: 12,
        totalPages: 0,
        canManage: root.getAttribute('data-can-manage') === 'true',
        canAssignDoctor: root.getAttribute('data-can-assign-doctor') === 'true',
        isAdmin: root.getAttribute('data-is-admin') === 'true',
        currentUserId: Number(root.getAttribute('data-current-user-id') || '0'),
        selectedRequestId: null,
        selectedDetailSnapshot: null,
        selectedProfileId: null,
        pendingConvertKey: null,
        lastSummarySignature: null,
        options: { branches: [], services: [] }
    };

    let pollTimerId = null;
    let isPolling = false;
    let searchDebounceId = null;
    let drawerInstance = null;

    function getCsrfHeaders() {
        const tokenMeta = document.querySelector('meta[name="_csrf"]');
        const headerMeta = document.querySelector('meta[name="_csrf_header"]');
        if (tokenMeta && headerMeta && tokenMeta.content && headerMeta.content) {
            return { [headerMeta.content]: tokenMeta.content };
        }
        const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
        if (match) {
            return { 'X-XSRF-TOKEN': decodeURIComponent(match[1]) };
        }
        return {};
    }

    async function apiFetch(url, options = {}) {
        const method = (options.method || 'GET').toUpperCase();
        const headers = {
            Accept: 'application/json',
            ...(method !== 'GET' ? { 'Content-Type': 'application/json', ...getCsrfHeaders() } : {}),
            ...(options.headers || {})
        };
        const res = await fetch(url, {
            credentials: 'same-origin',
            ...options,
            headers
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
            const msg = body.message || body.detail || body.error || `Không thể xử lý (${res.status}).`;
            const err = new Error(msg);
            err.status = res.status;
            throw err;
        }
        return body;
    }

    function generateUuidKey() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') {
            return window.crypto.randomUUID();
        }
        const chars = 'abcdef0123456789';
        let out = 'cv-';
        for (let i = 0; i < 30; i++) {
            out += chars.charAt(Math.floor(Math.random() * chars.length));
        }
        return out;
    }

    function formatDate(val) {
        if (!val) return '—';
        const parts = String(val).slice(0, 10).split('-');
        return parts.length === 3 ? `${parts[2]}/${parts[1]}/${parts[0]}` : String(val);
    }

    function formatDateTime(val) {
        if (!val) return '—';
        const s = String(val);
        const datePart = formatDate(s.slice(0, 10));
        const timePart = s.length >= 16 ? s.slice(11, 16) : '';
        return timePart ? `${datePart} ${timePart}` : datePart;
    }

    function showPageError(msg) {
        const el = $('pageErrorBanner');
        if (!el) return;
        el.textContent = msg;
        el.classList.remove('d-none');
    }

    function clearPageError() {
        const el = $('pageErrorBanner');
        if (el) el.classList.add('d-none');
    }

    function showDrawerAlert(msg, isSuccess = false) {
        const el = $('drawerAlertBox');
        if (!el) return;
        el.className = `alert ${isSuccess ? 'alert-success' : 'alert-danger'} rounded-3 py-2 px-3 small`;
        el.textContent = msg;
        el.classList.remove('d-none');
    }

    function clearDrawerAlert() {
        const el = $('drawerAlertBox');
        if (el) el.classList.add('d-none');
    }

    function isDrawerOpen() {
        const el = $('consultationDetailDrawer');
        return Boolean(el && el.classList.contains('show'));
    }

    function resetConvertKey() {
        state.pendingConvertKey = null;
    }

    async function loadOptions() {
        const opts = await apiFetch(`${apiBase}/options`);
        state.options = opts;
        state.canManage = Boolean(opts.canManage);
        state.canAssignDoctor = Boolean(opts.canAssignDoctor);

        const branchSelect = $('consultBranchFilter');
        if (branchSelect) {
            branchSelect.replaceChildren();
            const list = Array.isArray(opts.branches) ? opts.branches : [];
            if (state.isAdmin || list.length > 1) {
                const optAll = document.createElement('option');
                optAll.value = '';
                optAll.textContent = state.isAdmin ? 'Tất cả chi nhánh' : 'Tất cả chi nhánh được phân công';
                branchSelect.appendChild(optAll);
            }
            list.forEach(b => {
                const o = document.createElement('option');
                o.value = String(b.id);
                o.textContent = b.name;
                branchSelect.appendChild(o);
            });
            if (list.length === 1 && !state.isAdmin) {
                state.branchId = String(list[0].id);
            }
            if (state.branchId && !list.some(b => String(b.id) === String(state.branchId))) {
                state.branchId = list.length === 1 ? String(list[0].id) : '';
            }
            branchSelect.value = state.branchId || '';
        }

        const svcSelect = $('convService');
        if (svcSelect) {
            svcSelect.replaceChildren();
            const emptyOpt = document.createElement('option');
            emptyOpt.value = '';
            emptyOpt.textContent = 'Khám & tư vấn tổng quát (không chọn dịch vụ)';
            svcSelect.appendChild(emptyOpt);
            (opts.services || []).forEach(s => {
                const o = document.createElement('option');
                o.value = String(s.id);
                o.textContent = `${s.name} (${s.durationMinutes} phút${s.bookableOnline ? '' : ' · Tại quầy'})`;
                svcSelect.appendChild(o);
            });
        }
    }

    function summarySignature(summary) {
        if (!summary) return '';
        return [
            summary.newCount,
            summary.unclaimedNewCount,
            summary.contactedCount,
            summary.convertedCount,
            summary.closedCount,
            summary.latestUpdatedAt || ''
        ].join('|');
    }

    function updateSummaryUi(summary) {
        if (!summary) return;
        state.lastSummarySignature = summarySignature(summary);
        const newCnt = Number(summary.newCount || 0);
        const contactedCnt = Number(summary.contactedCount || 0);
        const convertedCnt = Number(summary.convertedCount || 0);
        const closedCnt = Number(summary.closedCount || 0);
        const allCnt = newCnt + contactedCnt + convertedCnt + closedCnt;

        if ($('cntAll')) $('cntAll').textContent = String(allCnt);
        if ($('cntNew')) $('cntNew').textContent = String(newCnt);
        if ($('cntContacted')) $('cntContacted').textContent = String(contactedCnt);
        if ($('cntConverted')) $('cntConverted').textContent = String(convertedCnt);
        if ($('cntClosed')) $('cntClosed').textContent = String(closedCnt);

        const badge = $('headerOpenBadge');
        if (badge) {
            badge.textContent = `${newCnt} mới`;
            badge.classList.toggle('d-none', newCnt <= 0);
        }
    }

    function buildListUrl() {
        const params = new URLSearchParams();
        if (state.branchId) params.set('branchId', state.branchId);
        if (state.status) params.set('status', state.status);
        if (state.keyword) params.set('keyword', state.keyword);
        if (state.createdFrom) params.set('createdFrom', state.createdFrom);
        if (state.createdTo) params.set('createdTo', state.createdTo);
        params.set('page', String(state.page));
        params.set('size', String(state.size));
        return `${apiBase}?${params.toString()}`;
    }

    function createStatusBadge(status) {
        const norm = status === 'CANCELLED' ? 'CLOSED' : (status || 'NEW');
        const span = document.createElement('span');
        span.className = `rc-status-pill rc-status-${norm}`;
        span.textContent = statusLabels[norm] || norm;
        return span;
    }

    function renderList(pageData) {
        const items = Array.isArray(pageData.items) ? pageData.items : [];
        state.totalPages = Number(pageData.totalPages || 0);
        updateSummaryUi(pageData.summary);

        if ($('resultCountText')) {
            $('resultCountText').textContent = `${pageData.totalElements || 0} yêu cầu`;
        }

        const isEmpty = items.length === 0;
        $('emptyState').classList.toggle('d-none', !isEmpty);
        $('desktopTableCard').classList.toggle('d-none', isEmpty);
        $('consultMobileCards').classList.toggle('d-none', isEmpty);
        $('paginationBar').classList.toggle('d-none', (pageData.totalPages || 0) <= 1);

        if ($('paginationInfo')) {
            $('paginationInfo').textContent = `Trang ${(pageData.page || 0) + 1} / ${Math.max(1, pageData.totalPages || 1)}`;
        }
        if ($('btnPrevPage')) $('btnPrevPage').disabled = (pageData.page || 0) <= 0;
        if ($('btnNextPage')) $('btnNextPage').disabled = (pageData.page || 0) + 1 >= (pageData.totalPages || 1);

        renderDesktopRows(items);
        renderMobileCards(items);
    }

    function renderDesktopRows(items) {
        const tbody = $('consultTableBody');
        if (!tbody) return;
        tbody.replaceChildren();

        items.forEach(item => {
            const tr = document.createElement('tr');
            tr.className = 'dv-data-row';

            // 1. Mã + ngày tạo
            const tdCode = document.createElement('td');
            const codePill = document.createElement('div');
            codePill.className = 'dv-code-pill d-inline-block mb-1';
            codePill.textContent = item.requestCode;
            const createdDiv = document.createElement('div');
            createdDiv.className = 'small text-muted';
            createdDiv.textContent = formatDateTime(item.createdAt);
            tdCode.append(codePill, createdDiv);

            // 2. Khách & SĐT
            const tdGuest = document.createElement('td');
            const nameEl = document.createElement('div');
            nameEl.className = 'fw-bold text-dark text-truncate';
            nameEl.textContent = item.guestName;
            const phoneEl = document.createElement('div');
            phoneEl.className = 'small text-primary fw-semibold';
            phoneEl.textContent = item.guestPhone;
            tdGuest.append(nameEl, phoneEl);

            // 3. Nhu cầu khám
            const tdNeed = document.createElement('td');
            const whenEl = document.createElement('div');
            whenEl.className = 'small fw-semibold text-dark';
            const timeTxt = item.preferredTime ? ` · ${String(item.preferredTime).slice(0, 5)}` : '';
            whenEl.textContent = `${item.branchName || ''} · ${formatDate(item.preferredDate)} (${sessionLabels[item.preferredSession] || item.preferredSession}${timeTxt})`;
            const svcDocEl = document.createElement('div');
            svcDocEl.className = 'small text-muted text-truncate';
            svcDocEl.textContent = `${item.serviceName || 'Khám tổng quát'}${item.preferredDoctorName ? ' · BS: ' + item.preferredDoctorName : ''}`;
            tdNeed.append(whenEl, svcDocEl);

            // 4. Phụ trách
            const tdHandler = document.createElement('td');
            const hName = document.createElement('div');
            hName.className = 'small fw-semibold text-dark';
            hName.textContent = item.handledByName ? item.handledByName : 'Chưa có người nhận';
            const upd = document.createElement('div');
            upd.className = 'small text-muted';
            upd.textContent = formatDateTime(item.updatedAt);
            tdHandler.append(hName, upd);

            // 5. Trạng thái
            const tdStatus = document.createElement('td');
            tdStatus.appendChild(createStatusBadge(item.status));

            // 6. Thao tác
            const tdAction = document.createElement('td');
            tdAction.className = 'text-end';
            const btnWrap = document.createElement('div');
            btnWrap.className = 'd-inline-flex gap-2';

            if (state.canManage && !item.handledByUserId && item.status !== 'CONVERTED' && item.status !== 'CLOSED') {
                const claimBtn = document.createElement('button');
                claimBtn.type = 'button';
                claimBtn.className = 'btn btn-sm rounded-3 fw-semibold';
                claimBtn.style.cssText = 'background:#e0f2fe;color:#0369a1;border:1px solid #bae6fd;';
                claimBtn.textContent = 'Nhận';
                claimBtn.addEventListener('click', () => handleQuickClaim(item.requestId));
                btnWrap.appendChild(claimBtn);
            }

            const viewBtn = document.createElement('button');
            viewBtn.type = 'button';
            viewBtn.className = 'btn btn-sm btn-outline-primary rounded-3 fw-semibold';
            viewBtn.textContent = 'Chi tiết';
            viewBtn.addEventListener('click', () => openConsultationDrawer(item.requestId));
            btnWrap.appendChild(viewBtn);

            tdAction.appendChild(btnWrap);
            tr.append(tdCode, tdGuest, tdNeed, tdHandler, tdStatus, tdAction);
            tbody.appendChild(tr);
        });
    }

    function renderMobileCards(items) {
        const container = $('consultMobileCards');
        if (!container) return;
        container.replaceChildren();

        items.forEach(item => {
            const card = document.createElement('div');
            card.className = 'dv-grid-card p-3';

            const top = document.createElement('div');
            top.className = 'd-flex align-items-center justify-content-between gap-2 mb-2';
            const codeSpan = document.createElement('span');
            codeSpan.className = 'dv-code-pill';
            codeSpan.textContent = item.requestCode;
            top.append(codeSpan, createStatusBadge(item.status));

            const guestLine = document.createElement('div');
            guestLine.className = 'fw-bold text-dark';
            guestLine.textContent = `${item.guestName} · ${item.guestPhone}`;

            const infoLine = document.createElement('div');
            infoLine.className = 'small text-muted mt-1';
            infoLine.textContent = `${item.branchName} · ${formatDate(item.preferredDate)} (${sessionLabels[item.preferredSession] || item.preferredSession})`;

            const handlerLine = document.createElement('div');
            handlerLine.className = 'small text-secondary mt-1';
            handlerLine.textContent = `Phụ trách: ${item.handledByName || 'Chưa nhận'}`;

            const btnRow = document.createElement('div');
            btnRow.className = 'd-flex justify-content-end gap-2 mt-2';
            const openBtn = document.createElement('button');
            openBtn.type = 'button';
            openBtn.className = 'btn btn-sm btn-outline-primary rounded-3 fw-semibold';
            openBtn.textContent = 'Xem & xử lý';
            openBtn.addEventListener('click', () => openConsultationDrawer(item.requestId));
            btnRow.appendChild(openBtn);

            card.append(top, guestLine, infoLine, handlerLine, btnRow);
            container.appendChild(card);
        });
    }

    async function fetchAndRenderList(showLoadingSpinner = true) {
        if (showLoadingSpinner) {
            $('loadingState').classList.remove('d-none');
        }
        clearPageError();
        try {
            const data = await apiFetch(buildListUrl());
            renderList(data);
        } catch (err) {
            showPageError(err.message);
        } finally {
            $('loadingState').classList.add('d-none');
        }
    }

    async function handleQuickClaim(requestId) {
        clearPageError();
        try {
            await apiFetch(`${apiBase}/${requestId}/claim`, {
                method: 'POST',
                body: JSON.stringify({})
            });
            await fetchAndRenderList(false);
        } catch (err) {
            showPageError(err.message);
            await fetchAndRenderList(false);
        }
    }

    async function openConsultationDrawer(requestId) {
        state.selectedRequestId = requestId;
        state.selectedProfileId = null;
        resetConvertKey();
        clearDrawerAlert();
        $('drawerStaleBanner').classList.add('d-none');
        $('handoverFormBox').classList.add('d-none');
        $('closeFormBox').classList.add('d-none');

        if (!drawerInstance && window.bootstrap) {
            drawerInstance = new window.bootstrap.Offcanvas($('consultationDetailDrawer'));
        }
        if (drawerInstance) {
            drawerInstance.show();
        }
        await loadConsultationDetail(requestId, true);
    }

    async function loadConsultationDetail(requestId, refreshVerify = true) {
        try {
            const detail = await apiFetch(`${apiBase}/${requestId}`);
            populateDrawer(detail);
            if (refreshVerify && detail.item && detail.item.status !== 'CONVERTED' && detail.item.status !== 'CLOSED') {
                await runPatientVerification(detail.item.guestPhone || '');
            }
            if (detail.canAssignDoctor && detail.item && detail.item.branchId && detail.item.preferredDate) {
                await loadDoctorOptions(detail.item.branchId, detail.item.preferredDate);
            }
        } catch (err) {
            showDrawerAlert(err.message, false);
        }
    }

    function populateDrawer(detail) {
        const item = detail.item;
        state.selectedDetailSnapshot = {
            requestId: item.requestId,
            branchId: item.branchId,
            status: item.status,
            handledByUserId: item.handledByUserId,
            updatedAt: item.updatedAt
        };
        state.canManage = Boolean(detail.canManage);
        state.canAssignDoctor = Boolean(detail.canAssignDoctor);

        $('drawerTitle').textContent = `Yêu cầu ${item.requestCode}`;
        $('drawerSubtitle').textContent = `Gửi lúc ${formatDateTime(item.createdAt)}`;

        const badge = $('drawerStatusBadge');
        badge.className = `rc-status-pill rc-status-${item.status === 'CANCELLED' ? 'CLOSED' : item.status}`;
        badge.textContent = statusLabels[item.status] || item.status;

        $('drawerBranchName').textContent = item.branchName || '—';
        $('dGuestName').textContent = item.guestName || '—';
        $('dGuestPhone').textContent = item.guestPhone || '—';
        const timeTxt = item.preferredTime ? ` · ${String(item.preferredTime).slice(0, 5)}` : '';
        $('dPreferredWhen').textContent = `${formatDate(item.preferredDate)} · ${sessionLabels[item.preferredSession] || item.preferredSession}${timeTxt}`;
        $('dPreferredService').textContent = item.serviceName || 'Khám & tư vấn tổng quát';
        $('dPreferredDoctor').textContent = item.preferredDoctorName || 'Không chọn bác sĩ cụ thể';

        const docBadge = $('dPreferredDoctorStatusBadge');
        if (item.preferredDoctorId && detail.preferredDoctorEligible !== null) {
            docBadge.classList.remove('d-none');
            if (detail.preferredDoctorEligible) {
                docBadge.className = 'badge bg-success-subtle text-success border border-success-subtle';
                docBadge.textContent = 'Đang trực ca';
            } else {
                docBadge.className = 'badge bg-warning-subtle text-warning border border-warning-subtle';
                docBadge.textContent = 'Không trực / Không hợp lệ';
            }
        } else {
            docBadge.classList.add('d-none');
        }

        $('dGuestNotes').textContent = item.notes || 'Không có ghi chú thêm.';
        $('dHandlerSummary').textContent = item.handledByName
            ? `Phụ trách: ${item.handledByName} (nhận lúc ${formatDateTime(item.claimedAt)})`
            : 'Chưa có lễ tân nhận xử lý';

        const isTerminal = item.status === 'CONVERTED' || item.status === 'CLOSED' || item.status === 'CANCELLED';
        const isHandledByOther = Boolean(item.handledByUserId && item.handledByUserId !== state.currentUserId && !state.isAdmin);

        $('btnClaimConsultation').classList.toggle('d-none', !state.canManage || isTerminal || Boolean(item.handledByUserId));
        $('btnToggleHandover').classList.toggle('d-none', !state.canManage || isTerminal || isHandledByOther);

        // Terminal info banner
        const termCard = $('terminalBannerCard');
        const apptLink = $('terminalAppointmentLink');
        if (isTerminal) {
            termCard.classList.remove('d-none');
            if (item.status === 'CONVERTED') {
                $('terminalTitle').textContent = `Đã chuyển thành lịch hẹn #${item.convertedAppointmentCode || ''}`;
                $('terminalDesc').textContent = `Chuyển đổi lúc ${formatDateTime(item.convertedAt)} bởi ${item.handledByName || 'Lễ tân'}.`;
                if (item.convertedAppointmentId) {
                    apptLink.href = `/reception/appointments/${item.convertedAppointmentId}`;
                    apptLink.classList.remove('d-none');
                } else {
                    apptLink.classList.add('d-none');
                }
            } else {
                $('terminalTitle').textContent = 'Yêu cầu tư vấn đã đóng';
                $('terminalDesc').textContent = `Lý do: ${item.closeReason || 'Không ghi nhận'} (${formatDateTime(item.closedAt)})`;
                apptLink.classList.add('d-none');
            }
        } else {
            termCard.classList.add('d-none');
        }

        const editable = state.canManage && !isTerminal && !isHandledByOther;
        $('contactSectionCard').classList.toggle('d-none', !editable);
        $('verifyAndConvertSection').classList.toggle('d-none', !editable);
        $('closeSectionCard').classList.toggle('d-none', !editable);

        // Điền sẵn form bước 3 & 4 (chỉ khi người dùng mới mở Drawer, không phá khi polling)
        $('verifyQueryInput').value = item.guestPhone || '';
        $('npName').value = item.guestName || '';
        $('npPhone').value = item.guestPhone || '';
        $('convDate').value = item.preferredDate || '';
        $('convSession').value = item.preferredSession || 'AFTERNOON';
        $('convTime').value = item.preferredTime ? String(item.preferredTime).slice(0, 5) : '';
        $('chkFlexibleTime').checked = !item.preferredTime;
        $('convTime').disabled = $('chkFlexibleTime').checked;
        $('convService').value = item.serviceId ? String(item.serviceId) : '';
        $('convSymptoms').value = item.notes || '';
        $('chkCreateNewPatient').checked = false;
        $('newPatientFieldsBox').classList.add('d-none');

        const docSelect = $('convDoctorId');
        docSelect.disabled = !state.canAssignDoctor;
        $('convDoctorHint').textContent = state.canAssignDoctor
            ? 'Có quyền điều phối: có thể chọn bác sĩ trực ca hoặc để tự động phân bổ.'
            : 'Chỉ Admin hoặc Điều phối lâm sàng mới được gán bác sĩ thủ công; hệ thống sẽ tự động phân bổ.';

        renderTimeline(detail.logs || []);
    }

    function renderTimeline(logs) {
        const container = $('drawerLogsTimeline');
        container.replaceChildren();
        if (!logs.length) {
            const p = document.createElement('div');
            p.className = 'small text-muted';
            p.textContent = 'Chưa có lịch sử thao tác nào.';
            container.appendChild(p);
            return;
        }
        logs.forEach(log => {
            const item = document.createElement('div');
            item.className = 'rc-timeline-item';

            const head = document.createElement('div');
            head.className = 'd-flex justify-content-between align-items-center gap-2 small';
            const title = document.createElement('strong');
            title.className = 'text-dark';
            const statusDiff = log.oldStatus && log.newStatus && log.oldStatus !== log.newStatus
                ? ` (${log.oldStatus} → ${log.newStatus})`
                : '';
            title.textContent = `${actionLabels[log.action] || log.action}${statusDiff}`;

            const timeSpan = document.createElement('span');
            timeSpan.className = 'text-muted';
            timeSpan.textContent = formatDateTime(log.createdAt);
            head.append(title, timeSpan);

            const meta = document.createElement('div');
            meta.className = 'small text-secondary';
            const resultTxt = log.contactResult ? ` · Kết quả: ${contactResultLabels[log.contactResult] || log.contactResult}` : '';
            const targetTxt = log.targetName ? ` → Bàn giao cho: ${log.targetName}` : '';
            meta.textContent = `Thực hiện: ${log.actorName || 'Hệ thống'}${targetTxt}${resultTxt}`;

            item.append(head, meta);
            if (log.notes) {
                const noteEl = document.createElement('div');
                noteEl.className = 'small text-dark mt-1';
                noteEl.textContent = log.notes;
                item.appendChild(noteEl);
            }
            container.appendChild(item);
        });
    }

    async function loadDoctorOptions(branchId, dateVal) {
        const docSelect = $('convDoctorId');
        if (!docSelect) return;
        try {
            const list = await apiFetch(`${aptApiBase}/doctors?branchId=${encodeURIComponent(branchId)}&date=${encodeURIComponent(dateVal)}`);
            docSelect.replaceChildren();
            const autoOpt = document.createElement('option');
            autoOpt.value = '';
            autoOpt.textContent = 'Tự động phân bổ theo ca trực';
            docSelect.appendChild(autoOpt);
            (Array.isArray(list) ? list : []).forEach(d => {
                const o = document.createElement('option');
                o.value = String(d.id);
                o.textContent = `${d.name}${d.specialty ? ' · ' + d.specialty : ''} (Còn ${d.remaining})`;
                docSelect.appendChild(o);
            });
        } catch (_) {
        }
    }

    async function runPatientVerification(queryOverride) {
        if (!state.selectedRequestId) return;
        const banner = $('verifyMessageBanner');
        const listEl = $('verifyCandidatesList');
        listEl.replaceChildren();
        banner.classList.add('d-none');
        state.selectedProfileId = null;

        try {
            const params = new URLSearchParams();
            if (queryOverride) params.set('query', queryOverride);
            const res = await apiFetch(`${apiBase}/${state.selectedRequestId}/verify-patient?${params.toString()}`);

            banner.textContent = res.message || '';
            banner.className = `alert py-2 px-3 small rounded-3 mb-2 ${
                res.status === 'AMBIGUOUS_CONFLICT'
                    ? 'alert-warning'
                    : (res.status === 'SINGLE_MATCH' ? 'alert-info' : 'alert-secondary')
            }`;
            banner.classList.remove('d-none');

            const candidates = Array.isArray(res.candidates) ? res.candidates : [];
            candidates.forEach(c => {
                const card = document.createElement('label');
                card.className = 'rc-candidate-card d-flex align-items-start gap-2';

                const radio = document.createElement('input');
                radio.type = 'radio';
                radio.name = 'verifiedPatientProfile';
                radio.className = 'form-check-input mt-1';
                radio.value = String(c.profileId);
                // Tuyệt đối không tự chọn sẵn khi có nhiều hồ sơ hoặc không nhất quán
                radio.checked = false;
                radio.addEventListener('change', () => {
                    state.selectedProfileId = c.profileId;
                    $('chkCreateNewPatient').checked = false;
                    $('newPatientFieldsBox').classList.add('d-none');
                    resetConvertKey();
                    listEl.querySelectorAll('.rc-candidate-card').forEach(el => el.classList.remove('is-selected'));
                    card.classList.add('is-selected');
                });

                const info = document.createElement('div');
                info.className = 'small';
                const top = document.createElement('div');
                top.className = 'fw-bold text-dark';
                top.textContent = `${c.fullName} (${c.patientCode})`;
                const sub = document.createElement('div');
                sub.className = 'text-muted';
                sub.textContent = `SĐT: ${c.phoneNumber} · Sinh: ${formatDate(c.dob)} · ${c.gender || ''} · ${c.branchName || ''}${c.crossBranch ? ' [Hồ sơ liên cơ sở]' : ''}`;
                info.append(top, sub);

                card.append(radio, info);
                listEl.appendChild(card);
            });
        } catch (err) {
            banner.textContent = err.message;
            banner.className = 'alert alert-danger py-2 px-3 small rounded-3 mb-2';
            banner.classList.remove('d-none');
        }
    }

    function bindEvents() {
        $('btnManualRefresh')?.addEventListener('click', () => fetchAndRenderList(true));
        $('btnReloadDrawerDetail')?.addEventListener('click', () => {
            if (state.selectedRequestId) {
                $('drawerStaleBanner').classList.add('d-none');
                loadConsultationDetail(state.selectedRequestId, true);
            }
        });

        $('consultSearchInput')?.addEventListener('input', e => {
            const val = e.target.value.trim();
            $('btnClearConsultSearch').style.display = val ? 'inline-flex' : 'none';
            if (searchDebounceId) clearTimeout(searchDebounceId);
            searchDebounceId = setTimeout(() => {
                state.keyword = val;
                state.page = 0;
                fetchAndRenderList(true);
            }, 300);
        });

        $('btnClearConsultSearch')?.addEventListener('click', () => {
            $('consultSearchInput').value = '';
            $('btnClearConsultSearch').style.display = 'none';
            state.keyword = '';
            state.page = 0;
            fetchAndRenderList(true);
        });

        $('consultBranchFilter')?.addEventListener('change', e => {
            state.branchId = e.target.value;
            state.page = 0;
            fetchAndRenderList(true);
        });
        $('consultFromDate')?.addEventListener('change', e => {
            state.createdFrom = e.target.value;
            state.page = 0;
            fetchAndRenderList(true);
        });
        $('consultToDate')?.addEventListener('change', e => {
            state.createdTo = e.target.value;
            state.page = 0;
            fetchAndRenderList(true);
        });

        const resetFilters = () => {
            state.status = '';
            state.keyword = '';
            state.createdFrom = '';
            state.createdTo = '';
            state.page = 0;
            if ($('consultSearchInput')) $('consultSearchInput').value = '';
            if ($('btnClearConsultSearch')) $('btnClearConsultSearch').style.display = 'none';
            if ($('consultFromDate')) $('consultFromDate').value = '';
            if ($('consultToDate')) $('consultToDate').value = '';
            document.querySelectorAll('#statusFilterGroup .dv-seg-btn').forEach(b => {
                b.classList.toggle('active', b.getAttribute('data-status') === '');
            });
            fetchAndRenderList(true);
        };
        $('btnResetFilters')?.addEventListener('click', resetFilters);
        $('btnEmptyReset')?.addEventListener('click', resetFilters);

        document.querySelectorAll('#statusFilterGroup .dv-seg-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('#statusFilterGroup .dv-seg-btn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                state.status = btn.getAttribute('data-status') || '';
                state.page = 0;
                fetchAndRenderList(true);
            });
        });

        $('btnPrevPage')?.addEventListener('click', () => {
            if (state.page > 0) {
                state.page -= 1;
                fetchAndRenderList(true);
            }
        });
        $('btnNextPage')?.addEventListener('click', () => {
            if (state.page + 1 < state.totalPages) {
                state.page += 1;
                fetchAndRenderList(true);
            }
        });

        // Claim
        $('btnClaimConsultation')?.addEventListener('click', async () => {
            clearDrawerAlert();
            try {
                const detail = await apiFetch(`${apiBase}/${state.selectedRequestId}/claim`, {
                    method: 'POST',
                    body: JSON.stringify({})
                });
                populateDrawer(detail);
                showDrawerAlert('Đã nhận phụ trách yêu cầu tư vấn.', true);
                fetchAndRenderList(false);
            } catch (err) {
                showDrawerAlert(err.message, false);
                fetchAndRenderList(false);
            }
        });

        // Handover
        $('btnToggleHandover')?.addEventListener('click', async () => {
            $('handoverFormBox').classList.toggle('d-none');
            if (!$('handoverFormBox').classList.contains('d-none')) {
                try {
                    const list = await apiFetch(`${apiBase}/${state.selectedRequestId}/assignees`);
                    const sel = $('handoverTargetSelect');
                    sel.replaceChildren();
                    (Array.isArray(list) ? list : [])
                        .filter(u => Number(u.userId) !== state.currentUserId)
                        .forEach(u => {
                            const opt = document.createElement('option');
                            opt.value = String(u.userId);
                            opt.textContent = `${u.fullName} (@${u.username})`;
                            sel.appendChild(opt);
                        });
                } catch (err) {
                    showDrawerAlert(err.message, false);
                }
            }
        });
        $('btnCancelHandover')?.addEventListener('click', () => $('handoverFormBox').classList.add('d-none'));
        $('btnSubmitHandover')?.addEventListener('click', async () => {
            clearDrawerAlert();
            const targetUserId = $('handoverTargetSelect').value;
            const reason = $('handoverReasonInput').value.trim();
            if (!targetUserId || !reason) {
                showDrawerAlert('Vui lòng chọn nhân sự nhận bàn giao và nhập lý do.', false);
                return;
            }
            try {
                const detail = await apiFetch(`${apiBase}/${state.selectedRequestId}/handover`, {
                    method: 'POST',
                    body: JSON.stringify({ targetUserId: Number(targetUserId), reason })
                });
                $('handoverReasonInput').value = '';
                $('handoverFormBox').classList.add('d-none');
                populateDrawer(detail);
                showDrawerAlert('Đã bàn giao yêu cầu thành công.', true);
                fetchAndRenderList(false);
            } catch (err) {
                showDrawerAlert(err.message, false);
            }
        });

        // Contact log
        $('contactForm')?.addEventListener('submit', async e => {
            e.preventDefault();
            clearDrawerAlert();
            const contactResult = $('contactResultSelect').value;
            const notes = $('contactNotesInput').value.trim();
            if (!notes) {
                showDrawerAlert('Vui lòng nhập ghi chú cuộc gọi liên hệ.', false);
                return;
            }
            try {
                const detail = await apiFetch(`${apiBase}/${state.selectedRequestId}/contact`, {
                    method: 'POST',
                    body: JSON.stringify({ contactResult, notes })
                });
                $('contactNotesInput').value = '';
                populateDrawer(detail);
                showDrawerAlert('Đã lưu lịch sử liên hệ.', true);
                fetchAndRenderList(false);
            } catch (err) {
                showDrawerAlert(err.message, false);
            }
        });

        // Verify patient
        $('btnVerifyPatient')?.addEventListener('click', () => {
            runPatientVerification($('verifyQueryInput').value.trim());
        });

        $('chkCreateNewPatient')?.addEventListener('change', e => {
            const on = e.target.checked;
            $('newPatientFieldsBox').classList.toggle('d-none', !on);
            if (on) {
                state.selectedProfileId = null;
                document.querySelectorAll('#verifyCandidatesList input[type="radio"]').forEach(r => { r.checked = false; });
                document.querySelectorAll('.rc-candidate-card').forEach(el => el.classList.remove('is-selected'));
            }
            resetConvertKey();
        });

        $('chkFlexibleTime')?.addEventListener('change', e => {
            $('convTime').disabled = e.target.checked;
            if (e.target.checked) $('convTime').value = '';
            resetConvertKey();
        });

        ['convDate', 'convSession', 'convTime', 'convService', 'convDoctorId', 'convSymptoms', 'chkIsTentative',
            'npName', 'npPhone', 'npDob', 'npGender', 'npAddress', 'npIdCard', 'npGuardianName', 'npGuardianPhone', 'npGuardianRel']
            .forEach(id => {
                $(id)?.addEventListener('input', resetConvertKey);
                $(id)?.addEventListener('change', resetConvertKey);
            });

        $('convDate')?.addEventListener('change', e => {
            if (state.canAssignDoctor && state.selectedDetailSnapshot && state.selectedDetailSnapshot.branchId && e.target.value) {
                loadDoctorOptions(state.selectedDetailSnapshot.branchId, e.target.value);
            }
        });

        // Convert to Appointment
        $('btnConvertToAppointment')?.addEventListener('click', async () => {
            clearDrawerAlert();
            const createNew = $('chkCreateNewPatient').checked;
            if (!state.selectedProfileId && !createNew) {
                showDrawerAlert('Vui lòng chọn hồ sơ bệnh nhân đã xác minh hoặc tích tạo hồ sơ mới.', false);
                return;
            }
            if (!state.pendingConvertKey) {
                state.pendingConvertKey = generateUuidKey();
            }

            const serviceVal = $('convService').value;
            const doctorVal = $('convDoctorId').value;
            const payload = {
                requestKey: state.pendingConvertKey,
                profileId: createNew ? null : state.selectedProfileId,
                createNewPatient: createNew,
                patientName: createNew ? $('npName').value.trim() : null,
                patientPhone: createNew ? $('npPhone').value.trim() : null,
                patientDob: createNew && $('npDob').value ? $('npDob').value : null,
                patientGender: createNew ? $('npGender').value : null,
                patientAddress: createNew ? $('npAddress').value.trim() : null,
                idCardNumber: createNew && $('npIdCard').value.trim() ? $('npIdCard').value.trim() : null,
                guardianName: createNew && $('npGuardianName').value.trim() ? $('npGuardianName').value.trim() : null,
                guardianPhone: createNew && $('npGuardianPhone').value.trim() ? $('npGuardianPhone').value.trim() : null,
                guardianRelationship: createNew && $('npGuardianRel').value.trim() ? $('npGuardianRel').value.trim() : null,
                date: $('convDate').value || null,
                session: $('convSession').value || null,
                expectedTime: $('chkFlexibleTime').checked || !$('convTime').value ? null : $('convTime').value,
                flexibleSessionTime: $('chkFlexibleTime').checked,
                serviceId: serviceVal ? Number(serviceVal) : null,
                clearService: !serviceVal,
                doctorId: state.canAssignDoctor && doctorVal ? Number(doctorVal) : null,
                symptoms: $('convSymptoms').value.trim() || null,
                isTentative: $('chkIsTentative').checked
            };

            const btn = $('btnConvertToAppointment');
            btn.disabled = true;
            try {
                const res = await apiFetch(`${apiBase}/${state.selectedRequestId}/convert`, {
                    method: 'POST',
                    body: JSON.stringify(payload)
                });
                state.pendingConvertKey = null;
                populateDrawer(res.detail);
                showDrawerAlert(res.message || 'Chuyển thành lịch hẹn thành công.', true);
                fetchAndRenderList(false);
            } catch (err) {
                showDrawerAlert(err.message, false);
                if (err.status === 409) {
                    fetchAndRenderList(false);
                }
            } finally {
                btn.disabled = false;
            }
        });

        // Close
        $('btnToggleCloseBox')?.addEventListener('click', () => $('closeFormBox').classList.toggle('d-none'));
        $('btnCancelClose')?.addEventListener('click', () => $('closeFormBox').classList.add('d-none'));
        $('btnConfirmClose')?.addEventListener('click', async () => {
            clearDrawerAlert();
            const reason = $('closeReasonInput').value.trim();
            if (!reason) {
                showDrawerAlert('Vui lòng nhập lý do đóng yêu cầu.', false);
                return;
            }
            try {
                const detail = await apiFetch(`${apiBase}/${state.selectedRequestId}/close`, {
                    method: 'POST',
                    body: JSON.stringify({ reason })
                });
                $('closeReasonInput').value = '';
                $('closeFormBox').classList.add('d-none');
                populateDrawer(detail);
                showDrawerAlert('Đã đóng yêu cầu tư vấn.', true);
                fetchAndRenderList(false);
            } catch (err) {
                showDrawerAlert(err.message, false);
            }
        });
    }

    // Polling nền 30 giây: ưu tiên gọi /summary (không chứa PII), chỉ tải lại bảng giữ nguyên filter/trang khi có thay đổi
    async function pollBackground() {
        if (isPolling || document.visibilityState === 'hidden') return;
        isPolling = true;
        try {
            const sumUrl = state.branchId
                ? `${apiBase}/summary?branchId=${encodeURIComponent(state.branchId)}`
                : `${apiBase}/summary`;
            const sum = await apiFetch(sumUrl);
            const nextSig = summarySignature(sum);
            const changed = state.lastSummarySignature !== null && nextSig !== state.lastSummarySignature;
            updateSummaryUi(sum);

            if (!changed) {
                return;
            }

            const data = await apiFetch(buildListUrl());
            renderList(data);

            if (isDrawerOpen() && state.selectedDetailSnapshot && state.selectedRequestId) {
                const fresh = (data.items || []).find(it => it.requestId === state.selectedRequestId);
                const snap = state.selectedDetailSnapshot;
                if (fresh && (fresh.status !== snap.status
                        || fresh.handledByUserId !== snap.handledByUserId
                        || fresh.updatedAt !== snap.updatedAt)) {
                    $('drawerStaleBanner').classList.remove('d-none');
                }
            }
        } catch (_) {
        } finally {
            isPolling = false;
        }
    }

    function startPolling() {
        if (pollTimerId) {
            clearInterval(pollTimerId);
            pollTimerId = null;
        }
        pollTimerId = setInterval(pollBackground, POLL_INTERVAL_MS);
    }

    document.addEventListener('DOMContentLoaded', async () => {
        bindEvents();
        try {
            await loadOptions();
            await fetchAndRenderList(true);
        } catch (err) {
            showPageError(err.message);
        }
        startPolling();
    });
})();
