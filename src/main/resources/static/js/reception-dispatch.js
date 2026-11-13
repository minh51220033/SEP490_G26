(() => {
    function openModalSafe(modalEl) {
        if (!modalEl) return;
        if (typeof window.bootstrap !== 'undefined' && window.bootstrap.Modal) {
            window.bootstrap.Modal.getOrCreateInstance(modalEl).show();
            return;
        }
        modalEl.classList.add('show');
        modalEl.style.display = 'block';
        modalEl.removeAttribute('aria-hidden');
        document.body.classList.add('modal-open');
    }

    function closeModalSafe(modalEl) {
        if (!modalEl) return;
        if (typeof window.bootstrap !== 'undefined' && window.bootstrap.Modal) {
            const inst = window.bootstrap.Modal.getInstance(modalEl);
            if (inst) inst.hide();
        }
        modalEl.classList.remove('show');
        modalEl.style.display = 'none';
        modalEl.setAttribute('aria-hidden', 'true');
        document.body.classList.remove('modal-open');
    }

    function openDrawerSafe(drawerEl) {
        if (!drawerEl) return;
        if (drawerEl.classList.contains('modal')) {
            openModalSafe(drawerEl);
            return;
        }
        if (typeof window.bootstrap !== 'undefined' && window.bootstrap.Offcanvas) {
            window.bootstrap.Offcanvas.getOrCreateInstance(drawerEl).show();
            return;
        }
        drawerEl.classList.add('show');
        drawerEl.style.visibility = 'visible';
    }

    function normalize(str) {
        return (str || '').toString().toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'd').trim();
    }

    function renderActiveKeywordChip(keyword) {
        const container = document.getElementById('dispatchActiveFilterChips');
        if (!container) return;
        const existing = container.querySelector('.filter-chip[data-filter-type="keyword"]');
        if (!keyword) {
            if (existing) existing.remove();
            return;
        }
        if (existing) {
            const strong = existing.querySelector('strong');
            if (strong) strong.textContent = keyword;
        } else {
            const chip = document.createElement('span');
            chip.className = 'badge rounded-pill bg-primary-subtle text-primary border border-primary-subtle px-2.5 py-1.5 d-inline-flex align-items-center gap-1.5 filter-chip';
            chip.dataset.filterType = 'keyword';
            chip.innerHTML = `<span>Từ khóa: <strong></strong></span><button type="button" class="btn-close btn-remove-chip" data-filter-type="keyword" style="font-size: 9px;" aria-label="Xóa lọc từ khóa"></button>`;
            chip.querySelector('strong').textContent = keyword;
            container.appendChild(chip);
        }
    }

    function applyDispatchSearch(rawKeyword) {
        const searchInput = document.getElementById('visitSearch');
        const btnClearSearch = document.getElementById('btnClearSearch');
        const tableRows = document.querySelectorAll('.dispatch-table-row');
        const kanbanCards = document.querySelectorAll('.kanban-card-item');
        const raw = (rawKeyword !== undefined ? rawKeyword : (searchInput ? searchInput.value : '')).trim();
        const query = normalize(raw);

        if (searchInput && searchInput.value !== raw) {
            searchInput.value = raw;
        }
        if (btnClearSearch) {
            btnClearSearch.classList.toggle('d-none', raw.length === 0);
        }
        tableRows.forEach(row => {
            const text = normalize(row.dataset.search || row.textContent);
            row.hidden = Boolean(query) && !text.includes(query);
        });
        kanbanCards.forEach(card => {
            const text = normalize(card.dataset.search || card.textContent);
            card.style.display = (Boolean(query) && !text.includes(query)) ? 'none' : '';
        });
        renderActiveKeywordChip(raw);
    }

    function handleOpenAssign(btn) {
        const assignModalEl = document.getElementById('dispatchAssignModal');
        const modalAssignForm = document.getElementById('modalAssignForm');
        const modalPatientName = document.getElementById('modalPatientName');
        const modalPatientCode = document.getElementById('modalPatientCode');
        const modalVisitCode = document.getElementById('modalVisitCode');
        const modalSymptoms = document.getElementById('modalSymptoms');
        const modalDoctorAdvice = document.getElementById('modalDoctorAdvice');
        const modalDoctorSelect = document.getElementById('modalDoctorSelect');
        const modalRoomSelect = document.getElementById('modalRoomSelect');
        const modalChairSelect = document.getElementById('modalChairSelect');
        const modalReasonInput = document.getElementById('modalReasonInput');
        const modalNoteInput = document.getElementById('modalNoteInput');
        const modalPriorityCheckbox = document.getElementById('modalPriorityCheckbox');
        const modalIssueTicketCheckbox = document.getElementById('modalIssueTicketCheckbox');

        const visitId = btn.dataset.visitId;
        const visitCode = btn.dataset.visitCode;
        const patientName = btn.dataset.patientName;
        const patientCode = btn.dataset.patientCode;
        const symptoms = btn.dataset.symptoms;
        const isEmergency = btn.dataset.isEmergency === 'true';
        const requestedDoctor = btn.dataset.requestedDoctor;
        const requestedDoctorName = btn.dataset.requestedDoctorName;
        const currentDoctor = btn.dataset.currentDoctor;
        const currentRoom = btn.dataset.currentRoom;
        const currentChair = btn.dataset.currentChair;

        if (modalAssignForm) modalAssignForm.action = `/reception/dispatch/${visitId}/assign`;
        if (modalPatientName) modalPatientName.textContent = patientName || '';
        if (modalPatientCode) modalPatientCode.textContent = `Mã BN: ${patientCode || '—'}`;
        if (modalVisitCode) modalVisitCode.textContent = visitCode || '';
        if (modalSymptoms) modalSymptoms.textContent = `Triệu chứng ban đầu: ${symptoms || '—'}`;
        if (modalReasonInput) modalReasonInput.value = '';
        if (modalNoteInput) modalNoteInput.value = '';
        if (modalRoomSelect) modalRoomSelect.value = currentRoom || '';
        if (modalChairSelect) modalChairSelect.value = currentChair || '';
        if (modalPriorityCheckbox) modalPriorityCheckbox.checked = isEmergency;
        if (modalIssueTicketCheckbox) modalIssueTicketCheckbox.checked = true;

        if (modalDoctorSelect) {
            if (currentDoctor) {
                modalDoctorSelect.value = currentDoctor;
                if (modalDoctorAdvice) {
                    modalDoctorAdvice.classList.remove('d-none');
                    modalDoctorAdvice.className = 'dispatch-modal-advice bg-primary-subtle text-primary border-primary-subtle';
                    modalDoctorAdvice.innerHTML = `<i class="bi bi-info-circle-fill me-1"></i> Bác sĩ hiện tại đang phân công. Có thể chọn lại bác sĩ khác nếu cần điều phối lại.`;
                }
            } else if (isEmergency) {
                let bestDoc = '';
                let minWait = 9999;
                Array.from(modalDoctorSelect.options).forEach(opt => {
                    if (!opt.value || opt.disabled) return;
                    const w = parseInt(opt.dataset.waiting || '0', 10);
                    if (w < minWait) {
                        minWait = w;
                        bestDoc = opt.value;
                    }
                });
                modalDoctorSelect.value = bestDoc;
                if (modalDoctorAdvice) {
                    modalDoctorAdvice.classList.remove('d-none');
                    modalDoctorAdvice.className = 'dispatch-modal-advice bg-danger-subtle text-danger-emphasis border-danger-subtle';
                    modalDoctorAdvice.innerHTML = `<i class="bi bi-lightning-charge-fill text-danger me-1"></i> <strong>Ca cấp cứu:</strong> Hệ thống tự động gợi ý Bác sĩ trực khả dụng để ưu tiên cấp cứu ngay.`;
                }
            } else if (requestedDoctor) {
                modalDoctorSelect.value = requestedDoctor;
                if (modalDoctorAdvice) {
                    modalDoctorAdvice.classList.remove('d-none');
                    modalDoctorAdvice.className = 'dispatch-modal-advice bg-warning-subtle text-warning-emphasis border-warning-subtle';
                    modalDoctorAdvice.innerHTML = `<i class="bi bi-star-fill text-warning me-1"></i> <strong>Bác sĩ yêu cầu từ lịch hẹn:</strong> ${requestedDoctorName || 'Đích danh'}. Giữ nguyên theo nguyện vọng của bệnh nhân.`;
                }
            } else {
                let bestDoc = '';
                let minWait = 9999;
                Array.from(modalDoctorSelect.options).forEach(opt => {
                    if (!opt.value || opt.disabled) return;
                    const w = parseInt(opt.dataset.waiting || '0', 10);
                    if (w < minWait) {
                        minWait = w;
                        bestDoc = opt.value;
                    }
                });
                modalDoctorSelect.value = bestDoc;
                const selectedOpt = modalDoctorSelect.options[modalDoctorSelect.selectedIndex];
                const docText = selectedOpt ? selectedOpt.text : '';
                if (modalDoctorAdvice) {
                    modalDoctorAdvice.classList.remove('d-none');
                    modalDoctorAdvice.className = 'dispatch-modal-advice bg-success-subtle text-success-emphasis border-success-subtle';
                    modalDoctorAdvice.innerHTML = `<i class="bi bi-shuffle me-1"></i> <strong>Cân bằng tải:</strong> Gợi ý phân vào Bác sĩ đang ít người chờ nhất (${docText}).`;
                }
            }
        }

        // Lọc ghế theo phòng nếu có phòng được chọn
        if (modalRoomSelect && modalChairSelect) {
            const filterChairs = () => {
                const selRoom = modalRoomSelect.value;
                Array.from(modalChairSelect.options).forEach(opt => {
                    if (!opt.value) return;
                    const optRoom = opt.dataset.room;
                    if (!selRoom || !optRoom || optRoom === selRoom) {
                        opt.hidden = false;
                        opt.disabled = false;
                    } else {
                        opt.hidden = true;
                        opt.disabled = true;
                    }
                });
            };
            modalRoomSelect.onchange = filterChairs;
            filterChairs();
        }

        openModalSafe(assignModalEl);
    }

    function handleOpenTransfer(btn) {
        const transferModalEl = document.getElementById('dispatchTransferModal');
        const modalTransferForm = document.getElementById('modalTransferForm');
        const modalTransferPatientName = document.getElementById('modalTransferPatientName');
        const modalTransferVisitCode = document.getElementById('modalTransferVisitCode');
        const modalTransferCurrentDoctor = document.getElementById('modalTransferCurrentDoctor');
        const modalTransferDoctorSelect = document.getElementById('modalTransferDoctorSelect');
        const modalTransferReasonInput = document.getElementById('modalTransferReasonInput');

        const visitId = btn.dataset.visitId;
        const visitCode = btn.dataset.visitCode;
        const patientName = btn.dataset.patientName;
        const currentDoctor = btn.dataset.currentDoctor;
        const currentDoctorName = btn.dataset.currentDoctorName;

        if (modalTransferForm) modalTransferForm.action = `/reception/dispatch/${visitId}/assign`;
        if (modalTransferPatientName) modalTransferPatientName.textContent = patientName || '';
        if (modalTransferVisitCode) modalTransferVisitCode.textContent = visitCode || '';
        if (modalTransferCurrentDoctor) {
            modalTransferCurrentDoctor.innerHTML = `Bác sĩ hiện tại: <strong class="text-dark">${currentDoctorName || 'Chưa rõ'}</strong>`;
        }

        if (modalTransferDoctorSelect) {
            modalTransferDoctorSelect.value = '';
            Array.from(modalTransferDoctorSelect.options).forEach(opt => {
                if (opt.value && opt.value === currentDoctor) {
                    opt.disabled = true;
                    opt.hidden = true;
                } else if (opt.value) {
                    const waiting = parseInt(opt.dataset.waiting || '0', 10);
                    if (waiting >= 8) {
                        opt.disabled = true;
                    } else {
                        opt.disabled = false;
                        opt.hidden = false;
                    }
                }
            });
        }

        if (modalTransferReasonInput) {
            modalTransferReasonInput.value = '';
        }

        openModalSafe(transferModalEl);
    }

    function populateTicketData(btn) {
        const ticketNumber = btn.dataset.ticketNumber || '01';
        const patientName = btn.dataset.patientName || '—';
        const patientCode = btn.dataset.patientCode || '—';
        const doctorName = btn.dataset.doctorName || 'Theo điều phối';
        const rawRoom = btn.dataset.roomName || 'Phòng khám Nha khoa';
        const chairName = btn.dataset.chairName || 'Ghế tiêu chuẩn';
        const issuedTime = btn.dataset.issuedTime || new Date().toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' });
        const isEmergency = btn.dataset.isEmergency === 'true';
        const isPriority = btn.dataset.isPriority === 'true' || String(ticketNumber).startsWith('P-');

        const roomDisplay = rawRoom.startsWith('Phòng khám:') ? rawRoom : `Phòng khám: ${rawRoom}`;

        const numEl = document.getElementById('k80TicketNumber');
        const roomEl = document.getElementById('k80RoomDisplay');
        const pCodeEl = document.getElementById('k80PatientCode');
        const pNameEl = document.getElementById('k80PatientName');
        const docEl = document.getElementById('k80DoctorName');
        const chairEl = document.getElementById('k80ChairName');
        const timeEl = document.getElementById('k80IssuedTime');
        const footerRoomEl = document.getElementById('k80FooterRoom');
        const priBadgeEl = document.getElementById('k80PriorityBadge');

        if (numEl) numEl.textContent = ticketNumber;
        if (roomEl) roomEl.textContent = roomDisplay;
        if (pCodeEl) pCodeEl.textContent = patientCode;
        if (pNameEl) pNameEl.textContent = patientName;
        if (docEl) docEl.textContent = doctorName;
        if (chairEl) chairEl.textContent = chairName;
        if (timeEl) timeEl.textContent = issuedTime;
        if (footerRoomEl) footerRoomEl.textContent = rawRoom;

        if (priBadgeEl) {
            if (isEmergency) {
                priBadgeEl.className = 'badge bg-danger text-white fw-bold mb-2';
                priBadgeEl.textContent = 'CA CẤP CỨU - ƯU TIÊN SỐ 1';
            } else if (isPriority) {
                priBadgeEl.className = 'badge bg-warning text-dark fw-bold mb-2';
                priBadgeEl.textContent = 'KHÁCH HÀNG ƯU TIÊN (P)';
            } else {
                priBadgeEl.className = 'd-none';
            }
        }
    }

    function handleOpenK80PrintModal(btn) {
        const k80ModalEl = document.getElementById('k80TicketModal');
        populateTicketData(btn);
        openModalSafe(k80ModalEl);
    }

    function printA6ReceiptInPlace() {
        const num = document.getElementById('k80TicketNumber')?.textContent?.trim() || '01';
        const room = document.getElementById('k80RoomDisplay')?.textContent?.trim() || 'Phòng khám';
        const pCode = document.getElementById('k80PatientCode')?.textContent?.trim() || '—';
        const pName = document.getElementById('k80PatientName')?.textContent?.trim() || '—';
        const docName = document.getElementById('k80DoctorName')?.textContent?.trim() || 'Theo điều phối';
        const chair = document.getElementById('k80ChairName')?.textContent?.trim() || 'Ghế tiêu chuẩn';
        const time = document.getElementById('k80IssuedTime')?.textContent?.trim() || '--:--';
        const priBadge = document.getElementById('k80PriorityBadge');
        let badgeHtml = '';
        if (priBadge && !priBadge.classList.contains('d-none')) {
            const isEmg = priBadge.classList.contains('bg-danger');
            badgeHtml = `<div class="badge-urgent ${isEmg ? 'badge-emergency' : 'badge-priority'}">${priBadge.textContent}</div>`;
        }

        let iframe = document.getElementById('k80HiddenPrintFrame');
        if (!iframe) {
            iframe = document.createElement('iframe');
            iframe.id = 'k80HiddenPrintFrame';
            iframe.style.position = 'fixed';
            iframe.style.right = '0';
            iframe.style.bottom = '0';
            iframe.style.width = '0';
            iframe.style.height = '0';
            iframe.style.border = '0';
            document.body.appendChild(iframe);
        }

        const doc = iframe.contentWindow.document;
        doc.open();
        doc.write(`<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <title>Phiếu khám A6 - Nha Khoa Cường Việt</title>
    <style>
        @page {
            size: 105mm 148mm;
            margin: 5mm 6mm;
        }
        * {
            box-sizing: border-box;
            -webkit-print-color-adjust: exact !important;
            print-color-adjust: exact !important;
        }
        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            background: #fff;
            font-family: Arial, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            color: #0f172a;
        }
        .a6-ticket-box {
            width: 100%;
            max-width: 93mm;
            margin: 0 auto;
            padding: 8px 10px;
            text-align: center;
            border: 1.5px solid #cbd5e1;
            border-radius: 8px;
        }
        .clinic-title {
            font-size: 13.5px;
            font-weight: 800;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin: 0 0 2px;
        }
        .clinic-subtitle {
            font-size: 10.5px;
            color: #64748b;
            padding-bottom: 6px;
            border-bottom: 1.5px dashed #cbd5e1;
            margin-bottom: 8px;
        }
        .ticket-label {
            font-size: 12px;
            font-weight: 700;
            text-transform: uppercase;
            letter-spacing: 1px;
            color: #475569;
            margin-top: 4px;
        }
        .ticket-number {
            font-size: 56px;
            font-weight: 900;
            line-height: 1;
            color: #0284c7;
            margin: 6px 0;
            letter-spacing: 1.5px;
        }
        .badge-urgent {
            display: inline-block;
            padding: 4px 12px;
            font-size: 11.5px;
            font-weight: 800;
            border-radius: 6px;
            margin-bottom: 8px;
        }
        .badge-emergency {
            background: #ef4444;
            color: #fff;
        }
        .badge-priority {
            background: #f59e0b;
            color: #000;
        }
        .room-box {
            background: #f1f5f9;
            border: 1px solid #cbd5e1;
            border-radius: 6px;
            padding: 6px 10px;
            font-size: 14px;
            font-weight: 700;
            color: #0f172a;
            margin: 8px 0;
        }
        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 12.5px;
            text-align: left;
            margin: 8px 0;
        }
        table tr {
            border-bottom: 1px dashed #e2e8f0;
        }
        table tr:last-child {
            border-bottom: none;
        }
        td {
            padding: 5px 2px;
        }
        .td-label {
            color: #64748b;
            width: 35%;
            font-weight: 500;
        }
        .td-val {
            text-align: right;
            font-weight: 700;
            color: #0f172a;
        }
        .footer-note {
            margin-top: 8px;
            padding-top: 6px;
            border-top: 1.5px dashed #cbd5e1;
            font-size: 11px;
            color: #64748b;
            line-height: 1.4;
        }
    </style>
</head>
<body>
    <div class="a6-ticket-box">
        <div class="clinic-title">Phòng Khám Nha Khoa Cường Việt</div>
        <div class="clinic-subtitle">Hệ thống Nha khoa Kỹ thuật cao CV-DCMS</div>
        <div class="ticket-label">SỐ THỨ TỰ KHÁM</div>
        <div class="ticket-number">${num}</div>
        ${badgeHtml}
        <div class="room-box">${room}</div>
        <table>
            <tbody>
                <tr><td class="td-label">Mã BN:</td><td class="td-val" style="font-family: monospace;">${pCode}</td></tr>
                <tr><td class="td-label">Họ và tên:</td><td class="td-val">${pName}</td></tr>
                <tr><td class="td-label">Bác sĩ:</td><td class="td-val" style="color: #0284c7;">${docName}</td></tr>
                <tr><td class="td-label">Ghế nha:</td><td class="td-val">${chair}</td></tr>
                <tr><td class="td-label">Thời gian:</td><td class="td-val" style="color: #64748b; font-weight: normal;">${time}</td></tr>
            </tbody>
        </table>
        <div class="footer-note">
            <div>Vui lòng theo dõi màn hình TV hoặc lắng nghe loa gọi số.</div>
            <div>Cảm ơn Quý khách đã tin tưởng và đồng hành cùng Nha Khoa Cường Việt!</div>
            <div style="font-weight: bold; margin-top: 3px;">Phiếu chỉ có giá trị trong ngày.</div>
        </div>
    </div>
</body>
</html>`);
        doc.close();
        setTimeout(() => {
            iframe.contentWindow.focus();
            iframe.contentWindow.print();
        }, 120);
    }

    function handleOpenRowDrawer(row) {
        const drawerEl = document.getElementById('receptionDetailDrawer');
        if (!drawerEl) return;
        const setTxt = (id, val) => {
            const el = document.getElementById(id);
            if (el) el.textContent = val || '—';
        };
        setTxt('drawerVisitCode', row.dataset.visitCode);
        setTxt('drawerPatientName', row.dataset.patientName);
        setTxt('drawerPatientCode', row.dataset.patientCode);
        setTxt('drawerPhone', row.dataset.phone);
        setTxt('drawerTicketNumber', row.dataset.ticketNumber);
        setTxt('drawerDoctorName', row.dataset.doctorName);
        const roomName = row.dataset.roomName || 'Phòng khám Nha khoa';
        const chairName = row.dataset.chairName || 'Ghế tiêu chuẩn';
        setTxt('drawerRoomChair', `Phòng khám: ${roomName} - ${chairName}`);
        setTxt('drawerCheckInTime', row.dataset.checkinTime);
        setTxt('drawerSymptoms', row.dataset.symptoms);
        openDrawerSafe(drawerEl);
    }

    function initialiseDispatch() {
        const content = document.getElementById('reception-content-area');
        if (!content) return;
        if (content.dataset.dispatchInitialised === 'true') return;
        content.dataset.dispatchInitialised = 'true';

        if (typeof window.setupUniversalEnterpriseTable === 'function') {
            window.setupUniversalEnterpriseTable();
        }

        const searchInput = document.getElementById('visitSearch');
        const btnClearSearch = document.getElementById('btnClearSearch');

        // Chuyển đổi chế độ xem Kanban vs Bảng dữ liệu
        const btnViewKanban = document.getElementById('btnViewKanban');
        const btnViewGrid = document.getElementById('btnViewGrid');
        const viewKanbanSection = document.getElementById('viewKanbanSection');
        const viewGridSection = document.getElementById('viewGridSection');

        if (btnViewKanban && btnViewGrid && viewKanbanSection && viewGridSection) {
            btnViewKanban.addEventListener('click', (e) => {
                e.preventDefault();
                btnViewKanban.classList.add('active');
                btnViewGrid.classList.remove('active');
                viewKanbanSection.style.display = 'block';
                viewGridSection.style.display = 'none';
            });

            btnViewGrid.addEventListener('click', (e) => {
                e.preventDefault();
                btnViewGrid.classList.add('active');
                btnViewKanban.classList.remove('active');
                viewKanbanSection.style.display = 'none';
                viewGridSection.style.display = 'block';
            });
        }

        if (searchInput) {
            searchInput.addEventListener('input', () => {
                applyDispatchSearch(searchInput.value);
            });

            searchInput.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') {
                    e.preventDefault();
                    const filterKeywordInput = document.getElementById('filterKeywordInput');
                    const filterForm = document.getElementById('filterForm');
                    if (filterKeywordInput && filterForm) {
                        filterKeywordInput.value = searchInput.value.trim();
                        filterForm.submit();
                    }
                }
            });
        }

        if (btnClearSearch && searchInput) {
            btnClearSearch.addEventListener('click', () => {
                applyDispatchSearch('');
                const filterKeywordInput = document.getElementById('filterKeywordInput');
                const filterForm = document.getElementById('filterForm');
                if (filterKeywordInput && filterForm && filterKeywordInput.value) {
                    filterKeywordInput.value = '';
                    filterForm.submit();
                }
            });
        }

        const modalTransferForm = document.getElementById('modalTransferForm');
        const modalTransferReasonInput = document.getElementById('modalTransferReasonInput');
        if (modalTransferForm && modalTransferReasonInput) {
            modalTransferForm.addEventListener('submit', (e) => {
                const reason = modalTransferReasonInput.value.trim();
                if (reason.length < 10) {
                    e.preventDefault();
                    alert('Vui lòng ghi rõ lý do chuyển bác sĩ (ít nhất 10 ký tự) để phục vụ kiểm toán hệ thống.');
                    modalTransferReasonInput.focus();
                }
            });
        }

        // MISA AMIS Pagination handler
        const pageSizeSelect = document.getElementById('pageSizeSelect');
        const filterForm = document.getElementById('filterForm');
        const pageSizeInput = document.getElementById('pageSizeInput');
        const pageIndexInput = document.getElementById('pageIndexInput');
        if (filterForm) {
            filterForm.addEventListener('submit', function () {
                if (pageIndexInput) {
                    pageIndexInput.value = '0';
                }
            });
        }
        if (pageSizeSelect && filterForm && pageSizeInput) {
            pageSizeSelect.addEventListener('change', function () {
                pageSizeInput.value = this.value;
                if (pageIndexInput) {
                    pageIndexInput.value = '0';
                }
                filterForm.submit();
            });
        }
    }

    // Event delegation toàn cục cho toàn bộ nút thao tác, modal, chip lọc và click hàng mở Drawer
    document.addEventListener('click', (e) => {
        const btnAssign = e.target.closest('.btn-open-assign');
        if (btnAssign) {
            handleOpenAssign(btnAssign);
            return;
        }

        const btnTransfer = e.target.closest('.btn-open-transfer');
        if (btnTransfer) {
            handleOpenTransfer(btnTransfer);
            return;
        }

        const btnPrintK80 = e.target.closest('.btn-print-k80');
        if (btnPrintK80) {
            e.preventDefault();
            populateTicketData(btnPrintK80);
            printA6ReceiptInPlace();
            return;
        }

        const btnPrintNow = e.target.closest('#btnPrintK80Now');
        if (btnPrintNow) {
            e.preventDefault();
            printA6ReceiptInPlace();
            return;
        }

        const btnRemoveChip = e.target.closest('.btn-remove-chip');
        if (btnRemoveChip) {
            e.preventDefault();
            const filterType = btnRemoveChip.dataset.filterType || 'keyword';
            if (filterType === 'keyword') {
                applyDispatchSearch('');
                const filterKeywordInput = document.getElementById('filterKeywordInput');
                const filterForm = document.getElementById('filterForm');
                if (filterKeywordInput && filterForm && filterKeywordInput.value) {
                    filterKeywordInput.value = '';
                    filterForm.submit();
                }
            }
            return;
        }

        const btnClose = e.target.closest('[data-bs-dismiss="modal"]');
        if (btnClose) {
            const modal = btnClose.closest('.modal');
            if (modal) closeModalSafe(modal);
            return;
        }

        // Click vào hàng (trừ checkbox / button / link / action cell) mở Drawer chi tiết tiếp đón
        const row = e.target.closest('.dispatch-table-row, .reception-history-row');
        if (row && !e.target.closest('button, a, input, select, textarea, label, form, .dv-actions-cell-always, .dv-floating-actions-bar')) {
            handleOpenRowDrawer(row);
        }
    });

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initialiseDispatch);
    } else {
        initialiseDispatch();
    }
    document.addEventListener('reception:content-loaded', initialiseDispatch);
})();
