(() => {
let refreshTimer;

function initialiseQueue() {
    const content = document.getElementById('reception-content-area');
    if (!content || content.dataset.queueInitialised === 'true') return;
    content.dataset.queueInitialised = 'true';
    let board = document.getElementById('queueRows');
    const filter = document.getElementById('doctorFilter');
    const branch = document.getElementById('queueConfig')?.textContent.trim();
    const modalEl = document.getElementById('queueActionModal');
    const formEl = document.getElementById('queueActionForm');
    const titleEl = document.getElementById('queueActionModalLabel');
    const subtitleEl = document.getElementById('queueActionModalSubtitle');
    const patientNameEl = document.getElementById('modalActionPatientName');
    const visitCodeEl = document.getElementById('modalActionVisitCode');
    const ticketNoEl = document.getElementById('modalActionTicketNumber');
    const presetsContainer = document.getElementById('queuePresetOptions');
    const reasonInput = document.getElementById('queueActionReasonInput');
    const submitBtn = document.getElementById('modalActionSubmitBtn');

    if (!board || !branch) return;

    const PRESETS = {
        'PAUSE': {
            title: 'Tạm dừng số khám',
            subtitle: 'Bệnh nhân tạm vắng mặt; chọn lý do để lưu vết và đưa vào cột Tạm dừng.',
            btnText: 'Xác nhận tạm dừng',
            btnClass: 'btn-secondary',
            options: [
                'Bệnh nhân có việc ra ngoài tạm thời',
                'Chờ người nhà đến đón bệnh nhân',
                'Bệnh nhân cần đi vệ sinh hoặc nghỉ ngơi',
                'Bác sĩ yêu cầu tạm dừng ca khám'
            ]
        },
        'EMERGENCY': {
            title: '🚨 Kích hoạt ca cấp cứu',
            subtitle: 'Chuyển lượt khám sang trạng thái Cấp cứu để ưu tiên gọi vào phòng khám ngay lập tức.',
            btnText: 'Xác nhận cấp cứu',
            btnClass: 'btn-danger',
            options: [
                'Ca cấp cứu đau răng cấp tính cần xử lý ngay',
                'Chấn thương vùng hàm mặt chảy máu nhiều',
                'Đau nhức dữ dội không thể chờ đợi',
                'Viêm tủy cấp tính sưng đau hàm'
            ]
        },
        'PRIORITIZE': {
            title: '⭐ Áp dụng ưu tiên phục vụ',
            subtitle: 'Nâng điểm ưu tiên để hệ thống tự động xếp lên đầu danh sách chờ của bác sĩ.',
            btnText: 'Xác nhận ưu tiên',
            btnClass: 'btn-warning text-dark',
            options: [
                'Bệnh nhân cao tuổi (trên 65 tuổi) cần hỗ trợ',
                'Trẻ nhỏ quấy khóc cần ưu tiên khám trước',
                'Phụ nữ mang thai hoặc người khuyết tật',
                'Bệnh nhân có hẹn đặc biệt cần hỗ trợ sớm'
            ]
        },
        'CALL': {
            title: 'Gọi vượt thứ tự hàng chờ',
            subtitle: 'Bác sĩ hoặc lễ tân gọi ca này trước các số khác trong hàng; cần lưu lý do điều phối.',
            btnText: 'Xác nhận gọi vượt',
            btnClass: 'btn-primary',
            options: [
                'Bác sĩ yêu cầu gọi trước ca khám này',
                'Bệnh nhân có lịch bận đột xuất đã trao đổi',
                'Ca tái khám kiểm tra nhanh theo hẹn BS',
                'Đã chuẩn bị sẵn ghế và dụng cụ chuyên khoa'
            ]
        }
    };

    function bindQueueActions() {
        document.querySelectorAll('.btn-queue-action').forEach(btn => {
            btn.onclick = (e) => {
                e.preventDefault();
                const ticketId = btn.dataset.ticketId;
                const action = btn.dataset.action;
                const patientName = btn.dataset.patientName || 'Bệnh nhân';
                const ticketNo = btn.dataset.displayNumber || '--';
                const visitCode = btn.dataset.visitCode || '';

                if (!ticketId || !action || !modalEl) return;

                const config = PRESETS[action] || {
                    title: 'Thao tác số khám',
                    subtitle: 'Ghi nhận lý do điều phối.',
                    btnText: 'Xác nhận',
                    btnClass: 'btn-primary',
                    options: ['Yêu cầu điều phối chuyên môn phòng khám']
                };

                // Update modal elements
                if (titleEl) titleEl.innerHTML = `<i class="bi bi-gear-fill text-primary me-1.5"></i> ${config.title}`;
                if (subtitleEl) subtitleEl.textContent = config.subtitle;
                if (patientNameEl) patientNameEl.textContent = patientName;
                if (visitCodeEl) visitCodeEl.textContent = `Mã khám: ${visitCode}`;
                if (ticketNoEl) ticketNoEl.textContent = ticketNo;

                if (submitBtn) {
                    submitBtn.textContent = config.btnText;
                    submitBtn.className = `btn btn-sm fw-bold px-3 ${config.btnClass}`;
                }

                // Setup presets
                if (presetsContainer) {
                    presetsContainer.innerHTML = '';
                    config.options.forEach((optText, idx) => {
                        const optDiv = document.createElement('label');
                        optDiv.className = 'd-flex align-items-center gap-2 p-2 border rounded-2 bg-light cursor-pointer small';
                        optDiv.style.cursor = 'pointer';
                        optDiv.innerHTML = `
                            <input type="radio" name="presetReasonRadio" class="form-check-input mt-0" value="${optText}" ${idx === 0 ? 'checked' : ''}>
                            <span>${optText}</span>
                        `;
                        optDiv.querySelector('input').addEventListener('change', () => {
                            if (reasonInput) reasonInput.value = optText;
                        });
                        presetsContainer.appendChild(optDiv);
                    });
                }

                if (reasonInput) {
                    reasonInput.value = config.options[0] || '';
                }

                if (formEl) {
                    formEl.action = `/reception/queue/${ticketId}/${action}`;
                }

                if (window.bootstrap?.Modal) {
                    const modal = window.bootstrap.Modal.getOrCreateInstance(modalEl);
                    modal.show();
                }
            };
        });
    }

    function applyFilter() {
        if (!filter) return;
        board.querySelectorAll('[data-doctor]').forEach(item => {
            item.hidden = Boolean(filter.value) && item.dataset.doctor !== filter.value;
        });
    }

    if (filter) {
        filter.addEventListener('change', applyFilter);
    }

    bindQueueActions();

    async function refresh() {
        if (board.querySelector('details[open]') || board.contains(document.activeElement) || document.querySelector('.modal.show')) {
            return;
        }
        try {
            const response = await fetch('/reception/queue/rows?branchId=' + encodeURIComponent(branch), {cache: 'no-store'});
            if (!response.ok) return;
            const html = await response.text();
            const parsed = new DOMParser().parseFromString(html, 'text/html');
            const fresh = parsed.getElementById('queueRows');
            if (fresh) {
                board.replaceWith(fresh);
                board = fresh;
                applyFilter();
                bindQueueActions();
            }
        } catch (_) {
            return;
        }
    }

    refreshTimer = window.setInterval(refresh, 10000);
}

function cleanupQueue() {
    if (refreshTimer) {
        window.clearInterval(refreshTimer);
        refreshTimer = null;
    }
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiseQueue, {once: true});
else initialiseQueue();
document.addEventListener('reception:content-loaded', initialiseQueue);
document.addEventListener('reception:content-will-change', cleanupQueue);
})();
