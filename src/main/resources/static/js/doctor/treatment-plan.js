/**
 * treatment-plan.js - Quản lý Kế hoạch & Lộ trình điều trị (FT15)
 * Dành riêng cho màn hình Bệnh án (diagnoses.html / treatment-plans.html)
 */

if (typeof window.formatNumber !== 'function') {
    window.formatNumber = function(amount) {
        if (!amount && amount !== 0) return '0';
        return Math.round(amount).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",");
    };
}
if (typeof window.formatVnd !== 'function') {
    window.formatVnd = function(amount) {
        if (!amount && amount !== 0) return '0 đ';
        return window.formatNumber(amount) + ' đ';
    };
}

/**
 * Chuyển hướng sang trang chuyên dụng tạo kế hoạch điều trị mới (treatment-plan-create.html)
 */
function openAddPlanModal(preselectedDiagId = null) {
    const pathParts = window.location.pathname.split('/');
    const examIdx = pathParts.indexOf('examination');
    const visitId = examIdx !== -1 && pathParts[examIdx + 1] ? pathParts[examIdx + 1] : '';
    let url = visitId ? `/doctor/examination/${visitId}/treatment-plans/create` : '/doctor/treatment-plans/create';
    if (preselectedDiagId) {
        url += `?diagId=${preselectedDiagId}`;
    }
    window.location.href = url;
}

function openPlanModalForDiagnosticTab() {
    openAddPlanModal();
}

function openPlanModalForSingleDiagRow(trEl) {
    if (!trEl) return;
    const diagId = trEl.id ? trEl.id.replace('diag-row-', '') : null;
    openAddPlanModal(diagId);
}

/**
 * Bật / tắt hiển thị khối lộ trình chi tiết từng bước
 */
function togglePlanInlineRoadmap(planId) {
    const roadmapRow = document.getElementById(`plan-roadmap-row-${planId}`);
    const chevron = document.getElementById(`chevron-plan-${planId}`);
    if (!roadmapRow) return;

    const isHidden = window.getComputedStyle(roadmapRow).display === 'none';
    if (isHidden) {
        document.querySelectorAll('.df-plan-roadmap-row').forEach(el => el.style.display = 'none');
        document.querySelectorAll('[id^="chevron-plan-"]').forEach(el => {
            el.classList.remove('bi-chevron-up');
            el.classList.add('bi-chevron-down');
        });

        roadmapRow.style.display = 'block';
        if (chevron) {
            chevron.classList.remove('bi-chevron-down');
            chevron.classList.add('bi-chevron-up');
        }
        roadmapRow.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    } else {
        roadmapRow.style.display = 'none';
        if (chevron) {
            chevron.classList.remove('bi-chevron-up');
            chevron.classList.add('bi-chevron-down');
        }
    }
}

/**
 * Render HTML lộ trình chi tiết từng bước cho 1 kế hoạch
 */
function renderPlanInlineRoadmapHtml(plan) {
    let stagesHtml = '';
    if (plan.stages && plan.stages.length > 0) {
        plan.stages.forEach(st => {
            let cardClass = 'df-inline-stage-card';
            if (st.status === 'COMPLETED') cardClass += ' is-completed';
            else if (st.status === 'IN_PROGRESS') cardClass += ' is-in-progress';
            else cardClass += ' is-pending';

            const completedBadgeHtml = st.completedAt
                ? `<span class="badge px-2.5 py-1.5 rounded-pill bg-success-subtle text-success border border-success" id="stage-completed-badge-${st.stageId}">
                       <i class="bi bi-clock-history me-1"></i><span>Ngày làm: ${st.completedAt}</span>
                   </span>`
                : `<span class="badge px-2.5 py-1.5 rounded-pill bg-light text-secondary border" id="stage-completed-badge-${st.stageId}">
                       <i class="bi bi-clock-history me-1"></i><span>Chưa thực hiện</span>
                   </span>`;

            const expectedEnd = st.expectedEndDate || plan.expectedEndDate || '---';

            let itemsRows = '';
            if (st.items && st.items.length > 0) {
                st.items.forEach((it, idx) => {
                    const toothBadge = it.toothNumbers ? `<span class="badge bg-primary-subtle text-primary border">R${it.toothNumbers}</span>` : '<span class="text-muted">-</span>';
                    const completedItemHtml = it.completedAt
                        ? `<span class="badge bg-success-subtle text-success border"><i class="bi bi-check-circle-fill me-1"></i>${it.completedAt}</span>`
                        : '<span class="text-muted">---</span>';

                    const isComp = it.status === 'COMPLETED';

                    let actionBtnHtml = '';
                    if (isComp) {
                        actionBtnHtml = `
                            <div class="d-inline-flex align-items-center gap-1.5">
                                <span class="badge bg-success-subtle text-success border border-success px-2.5 py-1 rounded-pill fw-bold">
                                    <i class="bi bi-check2-circle me-1"></i>Đã thực hiện
                                </span>
                                <button type="button" class="btn btn-sm btn-outline-secondary p-0 px-1.5 py-0.5 rounded-pill"
                                        title="Hoàn tác lại bước này (Chưa làm)"
                                        onclick="revertPlanItemStatus(${it.planItemId})">
                                    <i class="bi bi-arrow-counterclockwise" style="font-size: 11px;"></i>
                                </button>
                            </div>
                        `;
                    } else {
                        actionBtnHtml = `
                            <div class="d-inline-flex align-items-center gap-1.5">
                                <button type="button" class="btn btn-sm btn-primary rounded-pill px-3 py-1 fw-bold shadow-xs d-inline-flex align-items-center gap-1.5"
                                        style="font-size: 12px; background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%); border: none;"
                                        title="Thực hiện thủ thuật này trong ca khám hôm nay (Tự động đưa vào Dịch vụ thực hiện FT16)"
                                        onclick="executePlanItemDirectly(${it.planItemId})">
                                    <i class="bi bi-lightning-charge-fill text-warning"></i>
                                    <span>Làm trong ca này</span>
                                </button>
                            </div>
                        `;
                    }

                    itemsRows += `
                        <tr id="item-row-${it.planItemId}">
                            <td class="text-center text-muted">${idx + 1}</td>
                            <td>
                                <strong class="text-dark">${it.serviceName}</strong>
                                ${it.categoryName ? `<span class="badge bg-light text-muted border ms-1" style="font-size: 10px;">${it.categoryName}</span>` : ''}
                            </td>
                            <td class="text-center">${toothBadge}</td>
                            <td class="text-center fw-bold">${it.quantity}</td>
                            <td class="text-center small" id="item-completed-cell-${it.planItemId}">${completedItemHtml}</td>
                            <td class="text-center" id="item-action-cell-${it.planItemId}">${actionBtnHtml}</td>
                        </tr>
                    `;
                });
            } else {
                itemsRows = '<tr><td colspan="6" class="text-center text-muted py-2 fst-italic">Giai đoạn này chưa có danh mục bước thực hiện chi tiết.</td></tr>';
            }

            const stageStatusClass = st.status === 'COMPLETED' ? 'bg-success-subtle text-success border border-success fw-bold' : (st.status === 'IN_PROGRESS' ? 'bg-primary-subtle text-primary border border-primary fw-bold' : 'bg-light text-secondary border');
            const stageStatusIcon = st.status === 'COMPLETED' ? 'bi-check-circle-fill' : (st.status === 'IN_PROGRESS' ? 'bi-arrow-repeat' : 'bi-clock');
            const stageStatusText = st.status === 'COMPLETED' ? 'Hoàn thành giai đoạn' : (st.status === 'IN_PROGRESS' ? 'Đang điều trị' : 'Chưa thực hiện');

            stagesHtml += `
                <div class="${cardClass}" id="stage-card-${st.stageId}">
                    <div class="d-flex flex-wrap align-items-center justify-content-between gap-3 pb-2.5 mb-2.5 border-bottom">
                        <div class="d-flex align-items-center gap-2">
                            <span class="badge bg-dark px-2.5 py-1.5 rounded-pill fw-bold">Giai đoạn ${st.stageOrder}</span>
                            <strong class="text-dark" style="font-size: 15px;">${st.stageName}</strong>
                        </div>
                        <div class="d-flex flex-wrap align-items-center gap-2">
                            ${completedBadgeHtml}
                            <span class="badge bg-white text-dark border px-2.5 py-1.5 rounded-pill shadow-xs">
                                <i class="bi bi-calendar-check text-primary me-1"></i>
                                <span>Dự kiến xong: </span>
                                <strong>${expectedEnd}</strong>
                            </span>
                            <span class="badge px-3 py-1.5 rounded-pill shadow-xs d-inline-flex align-items-center gap-1.5 ${stageStatusClass}" id="stage-status-badge-${st.stageId}">
                                <i class="bi ${stageStatusIcon}"></i>
                                <span>${stageStatusText}</span>
                            </span>
                        </div>
                    </div>
                    ${st.description ? `<div class="small text-secondary mb-2 fst-italic">${st.description}</div>` : ''}
                    <div class="table-responsive bg-white rounded-3 border">
                        <table class="table table-sm table-hover align-middle mb-0" style="font-size: 13px;">
                            <thead class="table-light">
                                <tr>
                                    <th style="width: 45px;" class="text-center">#</th>
                                    <th>Tên bước thực hiện / Quy trình thủ thuật</th>
                                    <th style="width: 110px;" class="text-center">Vị trí răng</th>
                                    <th style="width: 75px;" class="text-center">SL</th>
                                    <th style="width: 165px;" class="text-center">Ngày làm (now)</th>
                                    <th style="width: 205px;" class="text-center">Trạng thái &amp; Thao tác</th>
                                </tr>
                            </thead>
                            <tbody id="stage-items-body-${st.stageId}">
                                ${itemsRows}
                            </tbody>
                        </table>
                    </div>
                </div>
            `;
        });
    }

    return `
        <div class="df-inline-roadmap-container p-3 p-md-4" style="width: 100%; max-width: 100%; box-sizing: border-box; overflow-x: hidden;">
            <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3 pb-2 border-bottom">
                <div class="d-flex align-items-center gap-2">
                    <span class="badge bg-primary text-white px-2.5 py-1 rounded-pill fw-bold">
                        <i class="bi bi-diagram-3-fill me-1"></i>LỘ TRÌNH CHI TIẾT TỪNG BƯỚC
                    </span>
                    <strong class="text-dark fs-6">${plan.planName}</strong>
                </div>
                <div class="d-flex flex-wrap align-items-center gap-2 small">
                    <span class="badge bg-white text-dark border px-2.5 py-1.5 rounded-pill shadow-xs">
                        <i class="bi bi-calendar-check text-primary me-1"></i>
                        <span>Dự kiến xong: </span>
                        <strong>${plan.expectedEndDate || '---'}</strong>
                    </span>
                    <span class="badge bg-light text-primary border px-2.5 py-1.5 rounded-pill fw-bold" id="plan-progress-badge-${plan.planId}">
                        ${plan.completedStages}/${plan.totalStages} giai đoạn (${plan.progressPercent}%)
                    </span>
                </div>
            </div>
            <div id="stages-container-${plan.planId}">
                ${stagesHtml}
            </div>
        </div>
    `;
}

/**
 * Cập nhật giao diện inline khi thay đổi trạng thái
 */
function updatePlanInlineView(plan) {
    if (!plan || !plan.planId) return;

    const roadmapRow = document.getElementById(`plan-roadmap-row-${plan.planId}`);
    if (roadmapRow) {
        roadmapRow.innerHTML = renderPlanInlineRoadmapHtml(plan);
    }

    const parentRow = document.getElementById(`plan-row-${plan.planId}`);
    if (parentRow) {
        const progBadge = document.getElementById(`plan-progress-badge-${plan.planId}`);
        if (progBadge) {
            progBadge.innerText = `${plan.completedStages}/${plan.totalStages} giai đoạn (${plan.progressPercent}%)`;
        }
        const progPercent = document.getElementById(`plan-progress-bar-${plan.planId}`);
        if (progPercent && plan.progressPercent !== undefined) {
            progPercent.style.width = `${plan.progressPercent}%`;
            progPercent.setAttribute('aria-valuenow', plan.progressPercent);
        }
        const progPercentText = document.getElementById(`plan-progress-percent-${plan.planId}`);
        if (progPercentText && plan.progressPercent !== undefined) {
            progPercentText.innerText = `${plan.progressPercent}%`;
        }
        const stageCountText = document.getElementById(`plan-stages-text-${plan.planId}`);
        if (stageCountText && plan.totalStages !== undefined) {
            stageCountText.innerText = `${plan.completedStages}/${plan.totalStages} giai đoạn`;
        }

        const statusCell = document.getElementById(`plan-status-cell-${plan.planId}`);
        if (statusCell && plan.statusDisplayName) {
            const btn = statusCell.querySelector('button.dropdown-toggle');
            if (btn) {
                btn.className = `btn btn-sm dropdown-toggle rounded-pill px-2.5 py-1 fw-bold border shadow-xs d-inline-flex align-items-center gap-1 ${plan.statusBadgeClass}`;
                btn.innerHTML = `<span>${plan.statusDisplayName}</span>`;
            }
        }
    }
}

/**
 * Cập nhật trạng thái kế hoạch trực tiếp từ dropdown
 */
function updatePlanStatusDirectly(planId, newStatus) {
    fetch(`/api/doctor/treatment-plans/${planId}/status?status=${newStatus}`, {
        method: 'PUT'
    })
    .then(res => {
        if (!res.ok) {
            return res.json().then(err => { throw new Error(err.message || 'Lỗi khi cập nhật trạng thái'); });
        }
        return res.json();
    })
    .then(result => {
        showToast('Đã cập nhật trạng thái kế hoạch thành công!');
        if (result.data) {
            updatePlanInlineView(result.data);
        }
    })
    .catch(err => {
        showToast(err.message, true);
    });
}

function changeStageStatus(stageId, status) {
    fetch(`/api/doctor/treatment-plans/stages/${stageId}/status?status=${status}`, {
        method: 'PUT'
    })
    .then(res => {
        if (!res.ok) {
            return res.json().then(err => { throw new Error(err.message || 'Lỗi khi cập nhật trạng thái giai đoạn'); });
        }
        return res.json();
    })
    .then(result => {
        showToast('Đã cập nhật trạng thái giai đoạn & ghi nhận thời gian thực hiện (now())!');
        if (result.data) {
            updatePlanInlineView(result.data);
        }
    })
    .catch(err => {
        showToast(err.message, true);
    });
}

/**
 * Bác sĩ nhấn "Làm trong ca này": liên kết trực tiếp bước KHDT vào ca khám hiện tại (FT15 -> FT16)
 */
function executePlanItemDirectly(planItemId) {
    const visitIdInput = document.getElementById('visitId') || document.getElementById('treatVisitId');
    const visitId = visitIdInput ? visitIdInput.value : null;
    if (!visitId) {
        showToast('Không xác định được ca khám hiện tại!', true);
        return;
    }

    fetch(`/api/doctor/treatment-plans/items/${planItemId}/execute-in-visit?visitId=${visitId}`, {
        method: 'POST'
    })
    .then(res => {
        if (!res.ok) {
            return res.json().then(err => { throw new Error(err.message || 'Lỗi khi ghi nhận dịch vụ thực hiện'); });
        }
        return res.json();
    })
    .then(result => {
        showToast('Đã đưa dịch vụ vào ca khám hôm nay & hoàn thành bước trong phác đồ!');
        if (result.plan) {
            updatePlanInlineView(result.plan);
        }
        if (result.performedService && typeof addPerformedRowToTable === 'function') {
            addPerformedRowToTable(result.performedService);
            if (typeof recalcPerformedTableTotals === 'function') {
                recalcPerformedTableTotals();
            }
        } else if (typeof refreshPerformedServicesTable === 'function') {
            refreshPerformedServicesTable(visitId);
        }
    })
    .catch(err => {
        showToast(err.message, true);
    });
}

/**
 * Hoàn tác bước KHDT về lại Chưa làm
 */
function revertPlanItemStatus(planItemId) {
    if (!confirm('Bạn có chắc chắn muốn hoàn tác bước này về Chưa làm?')) return;
    fetch(`/api/doctor/treatment-plans/items/${planItemId}/status?status=PLANNED`, {
        method: 'PUT'
    })
    .then(res => {
        if (!res.ok) {
            return res.json().then(err => { throw new Error(err.message || 'Lỗi khi hoàn tác'); });
        }
        return res.json();
    })
    .then(result => {
        showToast('Đã hoàn tác bước thủ thuật về Chưa làm!');
        if (result.data) {
            updatePlanInlineView(result.data);
        }
    })
    .catch(err => {
        showToast(err.message, true);
    });
}

/**
 * Xóa kế hoạch điều trị
 */
function deleteTreatmentPlan(planId) {
    if (!confirm('Bạn có chắc chắn muốn xóa kế hoạch điều trị này?')) {
        return;
    }

    fetch(`/api/doctor/treatment-plans/${planId}`, {
        method: 'DELETE'
    })
    .then(res => {
        if (!res.ok) {
            return res.json().then(err => { throw new Error(err.message || 'Không thể xóa kế hoạch'); });
        }
        return res.json();
    })
    .then(result => {
        showToast('Đã xóa kế hoạch điều trị thành công!');
        const row = document.getElementById(`plan-row-${planId}`);
        if (row) row.remove();
        const roadmapRow = document.getElementById(`plan-roadmap-row-${planId}`);
        if (roadmapRow) roadmapRow.remove();
        updatePlanCountBadge();
    })
    .catch(err => {
        showToast(err.message, true);
    });
}

function updatePlanCountBadge() {
    const rows = document.querySelectorAll('#treatmentPlansTableBody tr[id^="plan-row-"]');
    const badge = document.getElementById('planCountBadge');
    if (badge) {
        badge.innerText = `${rows.length} kế hoạch`;
    }
}
