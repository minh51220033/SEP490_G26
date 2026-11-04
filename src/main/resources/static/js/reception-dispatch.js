document.addEventListener('DOMContentLoaded', () => {
    function normalize(str) {
        return (str || '').toString().toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'd').trim();
    }

    // 1. Search Filter across Grid, Kanban cards, and Ticket Table
    const search = document.getElementById('visitSearch');
    const cards = document.querySelectorAll('.dispatch-visit-card[data-search]');
    const kanbanCards = document.querySelectorAll('.kanban-card-item[data-search]');
    const ticketRows = document.querySelectorAll('.dispatch-table tbody tr[data-search]');

    if (search) {
        search.addEventListener('input', () => {
            const raw = search.value.trim();
            const query = normalize(raw);
            cards.forEach(card => {
                const text = normalize(card.dataset.search || card.textContent);
                card.hidden = Boolean(query) && !text.includes(query);
            });
            kanbanCards.forEach(card => {
                const text = normalize(card.dataset.search || card.textContent);
                card.style.display = (Boolean(query) && !text.includes(query)) ? 'none' : '';
            });
            ticketRows.forEach(row => {
                const text = normalize(row.dataset.search || row.textContent);
                row.hidden = Boolean(query) && !text.includes(query);
            });
        });
    }

    // 2. View Mode Switcher (Kanban vs Grid)
    const btnKanban = document.getElementById('btnViewKanban');
    const btnGrid = document.getElementById('btnViewGrid');
    const kanbanSection = document.getElementById('viewKanbanSection');
    const gridSection = document.getElementById('viewGridSection');

    if (btnKanban && btnGrid && kanbanSection && gridSection) {
        btnKanban.addEventListener('click', () => {
            btnKanban.classList.add('btn-primary', 'active');
            btnKanban.classList.remove('btn-outline-secondary');
            btnGrid.classList.remove('btn-primary', 'active');
            btnGrid.classList.add('btn-outline-secondary');
            kanbanSection.style.display = '';
            gridSection.style.display = 'none';
        });

        btnGrid.addEventListener('click', () => {
            btnGrid.classList.add('btn-primary', 'active');
            btnGrid.classList.remove('btn-outline-secondary');
            btnKanban.classList.remove('btn-primary', 'active');
            btnKanban.classList.add('btn-outline-secondary');
            kanbanSection.style.display = 'none';
            gridSection.style.display = '';
        });
    }

    // 3. Ticket Table Filter by Doctor
    const filter = document.getElementById('queueDoctor');
    if (filter) {
        const rows = document.querySelectorAll('.dispatch-table tbody tr[data-doctor]');
        const doctors = new Map();
        rows.forEach(row => {
            const docId = row.dataset.doctor;
            const docName = row.dataset.doctorName;
            if (docId && docName && docName !== 'Chưa phân bác sĩ') {
                doctors.set(docId, docName);
            }
        });
        [...doctors.entries()].sort((a, b) => a[1].localeCompare(b[1], 'vi')).forEach(([id, name]) => {
            filter.add(new Option(name, id));
        });
        filter.addEventListener('change', () => {
            rows.forEach(row => {
                row.hidden = Boolean(filter.value) && row.dataset.doctor !== filter.value;
            });
        });
    }

    // 4. SCR-08.2: Popup Phân công / Đổi Bác sĩ & Ghế khám
    const assignModalEl = document.getElementById('dispatchAssignModal');
    let assignModal = null;
    if (assignModalEl && typeof bootstrap !== 'undefined' && bootstrap.Modal) {
        assignModal = bootstrap.Modal.getOrCreateInstance(assignModalEl);
    }
    function closeFallbackModal() {
        if (assignModal || !assignModalEl) return;
        assignModalEl.classList.remove('show');
        assignModalEl.style.display = 'none';
        assignModalEl.setAttribute('aria-hidden', 'true');
    }
    if (assignModalEl) {
        assignModalEl.querySelectorAll('[data-bs-dismiss="modal"]').forEach(button =>
            button.addEventListener('click', closeFallbackModal));
        assignModalEl.addEventListener('click', event => {
            if (event.target === assignModalEl) closeFallbackModal();
        });
        document.addEventListener('keydown', event => {
            if (event.key === 'Escape') closeFallbackModal();
        });
    }

    const modalForm = document.getElementById('modalAssignForm');
    const modalPatientName = document.getElementById('modalPatientName');
    const modalPatientCode = document.getElementById('modalPatientCode');
    const modalVisitCode = document.getElementById('modalVisitCode');
    const modalSymptoms = document.getElementById('modalSymptoms');
    const modalDoctorAdvice = document.getElementById('modalDoctorAdvice');
    const modalDoctorSelect = document.getElementById('modalDoctorSelect');
    const modalRoomSelect = document.getElementById('modalRoomSelect');
    const modalChairSelect = document.getElementById('modalChairSelect');
    const modalReasonInput = document.getElementById('modalReasonInput');

    // Filter chair options according to selected room
    function filterChairsForRoom(selectedRoomId) {
        if (!modalChairSelect) return;
        const options = modalChairSelect.querySelectorAll('option[data-room]');
        options.forEach(opt => {
            const roomMatch = !selectedRoomId || opt.dataset.room === selectedRoomId;
            opt.hidden = !roomMatch;
            opt.disabled = !roomMatch;
        });
        const currentSelectedOpt = modalChairSelect.options[modalChairSelect.selectedIndex];
        if (currentSelectedOpt && currentSelectedOpt.dataset.room && selectedRoomId && currentSelectedOpt.dataset.room !== selectedRoomId) {
            modalChairSelect.value = '';
        }
    }

    if (modalRoomSelect) {
        modalRoomSelect.addEventListener('change', () => {
            filterChairsForRoom(modalRoomSelect.value);
        });
    }

    document.querySelectorAll('.btn-open-assign').forEach(btn => {
        btn.addEventListener('click', () => {
            const visitId = btn.dataset.visitId;
            const visitCode = btn.dataset.visitCode;
            const patientName = btn.dataset.patientName;
            const patientCode = btn.dataset.patientCode;
            const symptoms = btn.dataset.symptoms;
            const requestedDoctor = btn.dataset.requestedDoctor;
            const requestedDoctorName = btn.dataset.requestedDoctorName;
            const currentDoctor = btn.dataset.currentDoctor;
            const currentRoom = btn.dataset.currentRoom;
            const currentChair = btn.dataset.currentChair;

            if (modalForm) {
                modalForm.action = '/reception/dispatch/' + encodeURIComponent(visitId) + '/assign';
            }
            if (modalPatientName) modalPatientName.textContent = patientName || 'Bệnh nhân';
            if (modalVisitCode) modalVisitCode.textContent = visitCode || '';
            if (modalPatientCode) modalPatientCode.textContent = 'Mã BN: ' + (patientCode || '—');
            if (modalSymptoms) modalSymptoms.textContent = 'Triệu chứng: ' + (symptoms || 'Khám tổng quát');

            if (modalDoctorAdvice) {
                modalDoctorAdvice.replaceChildren();
                if (requestedDoctor && requestedDoctorName) {
                    modalDoctorAdvice.className = 'p-2.5 rounded-3 small mb-3 border bg-primary-subtle text-primary border-primary-subtle';
                    const icon = document.createElement('i');
                    icon.className = 'bi bi-star-fill text-warning me-1.5';
                    const strong = document.createElement('strong');
                    strong.textContent = 'Khách yêu cầu đích danh: ';
                    const docSpan = document.createElement('span');
                    docSpan.className = 'fw-bold';
                    docSpan.textContent = requestedDoctorName;
                    const br = document.createElement('br');
                    const note = document.createElement('span');
                    note.className = 'text-muted';
                    note.style.fontSize = '11px';
                    note.textContent = ' (Được giữ trong hàng chờ của bác sĩ này trừ khi có lý do đổi)';
                    modalDoctorAdvice.append(icon, strong, docSpan, br, note);
                } else {
                    modalDoctorAdvice.className = 'p-2.5 rounded-3 small mb-3 border bg-success-subtle text-success border-success-subtle';
                    const icon = document.createElement('i');
                    icon.className = 'bi bi-shuffle me-1.5';
                    const strong = document.createElement('strong');
                    strong.textContent = 'Không yêu cầu đích danh: ';
                    const text = document.createTextNode('Hệ thống sẽ tự động gán vào bác sĩ đang có ít người chờ nhất tại ca trực hiện tại.');
                    modalDoctorAdvice.append(icon, strong, text);
                }
            }

            if (modalDoctorSelect) {
                if (currentDoctor) {
                    modalDoctorSelect.value = currentDoctor;
                } else if (requestedDoctor) {
                    modalDoctorSelect.value = requestedDoctor;
                } else {
                    modalDoctorSelect.value = ''; // Auto-assign least waiting
                }
            }

            if (modalRoomSelect) modalRoomSelect.value = currentRoom || '';
            filterChairsForRoom(currentRoom || '');
            if (modalChairSelect) modalChairSelect.value = currentChair || '';
            if (modalReasonInput) modalReasonInput.value = '';

            if (assignModal) {
                assignModal.show();
            } else if (assignModalEl) {
                assignModalEl.classList.add('show');
                assignModalEl.style.display = 'block';
                assignModalEl.setAttribute('aria-hidden', 'false');
            }
        });
    });
});
