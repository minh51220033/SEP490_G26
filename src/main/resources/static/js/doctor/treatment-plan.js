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

if (typeof window.dfConfirm !== 'function') {
    window.dfConfirm = function(message, options) {
        const opts = options || {};
        const title = opts.title || 'Xác nhận thao tác';
        const confirmText = opts.confirmText || 'Đồng ý';
        const cancelText = opts.cancelText || 'Hủy';
        const variant = opts.variant || 'primary';
        const iconMap = {
            danger: 'bi-exclamation-triangle-fill',
            warning: 'bi-exclamation-circle-fill',
            primary: 'bi-question-circle-fill'
        };
        const btnMap = {
            danger: 'btn-danger',
            warning: 'btn-warning text-dark',
            primary: 'btn-primary'
        };

        return new Promise((resolve) => {
            if (typeof bootstrap === 'undefined' || !bootstrap.Modal) {
                resolve(true);
                return;
            }
            let modalEl = document.getElementById('dfGlobalConfirmModal');
            if (!modalEl) {
                modalEl = document.createElement('div');
                modalEl.id = 'dfGlobalConfirmModal';
                modalEl.className = 'modal fade df-confirm-modal';
                modalEl.tabIndex = -1;
                modalEl.setAttribute('aria-hidden', 'true');
                modalEl.innerHTML = `
                    <div class="modal-dialog modal-dialog-centered">
                        <div class="modal-content">
                            <div class="modal-header border-0 pb-0 pt-3 px-4">
                                <div class="d-flex align-items-center gap-3">
                                    <span id="dfConfirmIconWrap" class="df-confirm-icon is-primary">
                                        <i id="dfConfirmIcon" class="bi bi-question-circle-fill"></i>
                                    </span>
                                    <h6 class="modal-title fw-bold text-dark mb-0" id="dfConfirmTitle"></h6>
                                </div>
                                <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Đóng"></button>
                            </div>
                            <div class="modal-body px-4 py-3">
                                <div id="dfConfirmMessage" class="df-confirm-msg"></div>
                            </div>
                            <div class="modal-footer border-0 pt-0 pb-3 px-4 gap-2">
                                <button type="button" class="btn btn-light border px-3 fw-semibold" id="dfConfirmCancelBtn" data-bs-dismiss="modal"></button>
                                <button type="button" class="btn px-3 fw-semibold" id="dfConfirmOkBtn"></button>
                            </div>
                        </div>
                    </div>`;
                document.body.appendChild(modalEl);
            }

            const titleEl = modalEl.querySelector('#dfConfirmTitle');
            const msgEl = modalEl.querySelector('#dfConfirmMessage');
            const iconWrap = modalEl.querySelector('#dfConfirmIconWrap');
            const iconEl = modalEl.querySelector('#dfConfirmIcon');
            const cancelBtn = modalEl.querySelector('#dfConfirmCancelBtn');
            const okBtn = modalEl.querySelector('#dfConfirmOkBtn');

            if (titleEl) titleEl.textContent = title;
            if (msgEl) msgEl.textContent = message || '';
            if (iconWrap) iconWrap.className = 'df-confirm-icon is-' + (variant === 'danger' || variant === 'warning' ? variant : 'primary');
            if (iconEl) iconEl.className = 'bi ' + (opts.icon || iconMap[variant] || iconMap.primary);
            if (cancelBtn) cancelBtn.textContent = cancelText;
            if (okBtn) {
                okBtn.textContent = confirmText;
                okBtn.className = 'btn px-3 fw-semibold ' + (btnMap[variant] || btnMap.primary);
            }

            let settled = false;
            const modal = bootstrap.Modal.getInstance(modalEl) || new bootstrap.Modal(modalEl);
            const onConfirm = () => {
                if (settled) return;
                settled = true;
                modal.hide();
                resolve(true);
            };
            const onHidden = () => {
                okBtn?.removeEventListener('click', onConfirm);
                modalEl.removeEventListener('hidden.bs.modal', onHidden);
                if (!settled) {
                    settled = true;
                    resolve(false);
                }
            };
            okBtn?.addEventListener('click', onConfirm, { once: true });
            modalEl.addEventListener('hidden.bs.modal', onHidden, { once: true });
            modal.show();
        });
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

// Lưu trữ các Quá trình điều trị đang ở trạng thái Gập (collapsed)
const COLLAPSED_COURSE_IDS = new Set();

/**
 * Bật / tắt gập mở một Quá trình điều trị (Course Accordion)
 */
function toggleCourseAccordion(courseId) {
    const body = document.getElementById(`${courseId}-body`);
    const chevron = document.getElementById(`chevron-${courseId}`);
    if (!body) return;

    const isHidden = window.getComputedStyle(body).display === 'none';
    if (isHidden) {
        body.style.display = 'block';
        COLLAPSED_COURSE_IDS.delete(courseId);
        if (chevron) {
            chevron.classList.remove('bi-chevron-down');
            chevron.classList.add('bi-chevron-up');
        }
    } else {
        body.style.display = 'none';
        COLLAPSED_COURSE_IDS.add(courseId);
        if (chevron) {
            chevron.classList.remove('bi-chevron-up');
            chevron.classList.add('bi-chevron-down');
        }
    }
}

/**
 * Thu gọn hoặc Mở tất cả các Quá trình điều trị trong 1 kế hoạch
 */
function toggleAllCoursesInPlan(planId) {
    const container = document.getElementById(`plan-roadmap-row-${planId}`);
    if (!container) return;
    const bodies = container.querySelectorAll('.df-course-body');
    const chevrons = container.querySelectorAll('.df-course-chevron');
    const btnText = document.getElementById(`text-toggle-all-${planId}`);
    const btnIcon = document.getElementById(`icon-toggle-all-${planId}`);

    let anyOpen = false;
    bodies.forEach(b => {
        if (window.getComputedStyle(b).display !== 'none') anyOpen = true;
    });

    if (anyOpen) {
        bodies.forEach(b => {
            b.style.display = 'none';
            const cid = b.id.replace('-body', '');
            COLLAPSED_COURSE_IDS.add(cid);
        });
        chevrons.forEach(c => {
            c.classList.remove('bi-chevron-up');
            c.classList.add('bi-chevron-down');
        });
        if (btnText) btnText.innerText = 'Mở tất cả quá trình';
        if (btnIcon) {
            btnIcon.classList.remove('bi-arrows-collapse');
            btnIcon.classList.add('bi-arrows-expand');
        }
    } else {
        bodies.forEach(b => {
            b.style.display = 'block';
            const cid = b.id.replace('-body', '');
            COLLAPSED_COURSE_IDS.delete(cid);
        });
        chevrons.forEach(c => {
            c.classList.remove('bi-chevron-down');
            c.classList.add('bi-chevron-up');
        });
        if (btnText) btnText.innerText = 'Thu gọn tất cả';
        if (btnIcon) {
            btnIcon.classList.remove('bi-arrows-expand');
            btnIcon.classList.add('bi-arrows-collapse');
        }
    }
}

/**
 * Phân tách & Nhóm các giai đoạn (stages) thành từng Quá trình điều trị độc lập
 */
function groupStagesIntoCourses(stages, plan) {
    if (!stages || stages.length === 0) return [];

    const coursesMap = new Map();
    let courseSeq = 1;

    stages.forEach(st => {
        let courseKey = null;
        let courseTitle = null;
        let cleanStageName = (st.stageName || '').trim();

        // 1. Kiểm tra prefix dạng [CĐ #1], [SV #1]...
        const cdMatch = cleanStageName.match(/^\[((?:CĐ|SV)\s*#?\d+)\]\s*(.*)$/i);
        if (cdMatch) {
            courseKey = cdMatch[1].toUpperCase().replace(/\s+/g, ' '); // Ví dụ: "CĐ #1" hoặc "SV #1"
            cleanStageName = cdMatch[2].trim();

            if (st.description && /(?:chẩn đoán|dịch vụ):\s*/i.test(st.description)) {
                const parts = st.description.split(/(?:chẩn đoán|dịch vụ):\s*/i);
                if (parts[1]) {
                    courseTitle = `[${courseKey}] ${parts[1].trim()}`;
                }
            }
            if (!courseTitle) {
                courseTitle = `[${courseKey}] Quá trình điều trị`;
            }
        } else if (st.description && /^(?:điều trị chẩn đoán|dịch vụ):\s*/i.test(st.description.trim())) {
            const diagPart = st.description.trim().replace(/^(?:điều trị chẩn đoán|dịch vụ):\s*/i, '');
            courseKey = 'DIAG_' + diagPart;
            courseTitle = diagPart;
        } else {
            courseKey = 'DEFAULT_COURSE';
            courseTitle = (plan && plan.planName) ? `Quá trình: ${plan.planName}` : 'Quá trình điều trị chính';
        }

        if (!coursesMap.has(courseKey)) {
            coursesMap.set(courseKey, {
                courseId: `course-${plan ? plan.planId : '0'}-${courseSeq++}`,
                courseIndex: coursesMap.size + 1,
                courseKey: courseKey,
                courseTitle: courseTitle,
                stages: []
            });
        }

        const course = coursesMap.get(courseKey);
        course.stages.push({
            ...st,
            cleanStageName: cleanStageName || st.stageName,
            internalOrder: course.stages.length + 1
        });
    });

    return Array.from(coursesMap.values());
}

/**
 * Render HTML lộ trình chi tiết tách thành nhiều quá trình điều trị gập mở
 */
function renderPlanInlineRoadmapHtml(plan, options = {}) {
    if (!plan) return '';
    const omitHeader = options && options.omitHeader === true;

    const courses = groupStagesIntoCourses(plan.stages || [], plan);
    const waitingCount = Number(window.DESK_WAITING_COUNT || 0);
    const bookedSrvId = window.BOOKED_SERVICE && window.BOOKED_SERVICE.serviceId
        ? Number(window.BOOKED_SERVICE.serviceId)
        : null;
    let primarySlotClaimed = Boolean(bookedSrvId);
    let simAddonMinutes = 0;
    let coursesHtml = '';

    if (courses.length === 0) {
        coursesHtml = '<div class="text-center py-4 text-muted fst-italic">Kế hoạch này chưa có giai đoạn hoặc bước điều trị nào.</div>';
    } else {
        courses.forEach(course => {
            const totalStagesInCourse = course.stages.length;
            const completedStagesInCourse = course.stages.filter(s => s.status === 'COMPLETED').length;
            const hasInProgressStage = course.stages.some(s => s.status === 'IN_PROGRESS');
            const isAllCompleted = totalStagesInCourse > 0 && completedStagesInCourse === totalStagesInCourse;

            let courseStatusBadgeClass = 'bg-light text-secondary border';
            let courseStatusText = 'Chưa thực hiện';
            let courseStatusIcon = 'bi-clock';

            if (isAllCompleted) {
                courseStatusBadgeClass = 'bg-success text-white fw-bold shadow-xs';
                courseStatusText = 'Đã hoàn thành';
                courseStatusIcon = 'bi-check-all';
            } else if (hasInProgressStage || completedStagesInCourse > 0) {
                courseStatusBadgeClass = 'bg-primary-subtle text-primary border border-primary fw-bold';
                courseStatusText = 'Đang điều trị';
                courseStatusIcon = 'bi-arrow-repeat';
            }

            const isCollapsed = COLLAPSED_COURSE_IDS.has(course.courseId);
            const bodyDisplayStyle = isCollapsed ? 'none' : 'block';
            const chevronIconClass = isCollapsed ? 'bi-chevron-down' : 'bi-chevron-up';

            let stagesInsideCourseHtml = '';
            course.stages.forEach(st => {
                let cardClass = 'tp-flat-stage';
                if (st.status === 'COMPLETED') cardClass += ' is-completed';
                else if (st.status === 'IN_PROGRESS') cardClass += ' is-in-progress';

                const totalItemsInStage = (st.items && st.items.length) || 0;
                const doneItemsInStage = (st.items && st.items.filter(i => i.status === 'COMPLETED').length) || 0;
                const expectedEnd = st.expectedEndDate || plan.expectedEndDate || '---';

                let itemsRows = '';
                if (st.items && st.items.length > 0) {
                    st.items.forEach((it, idx) => {
                        let toothBadge = '<span class="text-muted">-</span>';
                        if (it.toothNumbers) {
                            const tn = String(it.toothNumbers).trim();
                            if (tn.toLowerCase().includes('toàn hàm') || tn.toLowerCase().includes('toan ham')) {
                                toothBadge = `<span class="chip-tooth">Toàn hàm</span>`;
                            } else {
                                const cleanNum = tn.startsWith('R-') || tn.startsWith('R') ? tn : `R-${tn}`;
                                toothBadge = `<span class="chip-tooth df-num">${cleanNum}</span>`;
                            }
                        }
                        const completedItemHtml = it.completedAt
                            ? `<span class="badge bg-success-subtle text-success border df-num"><i class="bi bi-check-circle-fill me-1"></i>${it.completedAt}</span>`
                            : '<span class="text-muted">---</span>';

                        const isComp = it.status === 'COMPLETED';
                        const isInProg = it.status === 'IN_PROGRESS';

                        let statusBadgeHtml = '';
                        let actionBtnHtml = '';

                        if (isComp) {
                            statusBadgeHtml = `
                                <span class="badge bg-success-subtle text-success border border-success-subtle px-2 py-1 rounded-pill fw-bold d-inline-flex align-items-center gap-1" style="font-size: 12px;">
                                    <i class="bi bi-check2-circle"></i>
                                    <span>Đã thực hiện</span>
                                </span>
                            `;
                            actionBtnHtml = `
                                <button type="button" class="btn btn-sm btn-outline-secondary rounded-circle d-inline-flex align-items-center justify-content-center p-0"
                                        style="width: 34px; height: 34px;"
                                        title="Hoàn tác lại hạng mục này (Chuyển về Chưa làm)"
                                        aria-label="Hoàn tác"
                                        onclick="revertPlanItemStatus(${it.planItemId})">
                                    <i class="bi bi-arrow-counterclockwise text-primary fw-bold" style="font-size: 14px;"></i>
                                </button>
                            `;
                        } else {
                            statusBadgeHtml = isInProg
                                ? `
                                    <span class="badge bg-primary-subtle text-primary border border-primary-subtle px-2 py-1 rounded-pill fw-semibold d-inline-flex align-items-center gap-1" style="font-size: 12px;">
                                        <i class="bi bi-play-circle-fill"></i>
                                        <span>Đang điều trị</span>
                                    </span>
                                `
                                : `
                                    <span class="badge bg-light text-secondary border px-2 py-1 rounded-pill fw-semibold d-inline-flex align-items-center gap-1" style="font-size: 12px;">
                                        <i class="bi bi-clock"></i>
                                        <span>Chưa làm</span>
                                    </span>
                                `;
                            actionBtnHtml = `
                                <div class="d-inline-flex align-items-center justify-content-center gap-2 text-nowrap">
                                    <button type="button" class="btn btn-sm btn-outline-primary rounded-circle d-inline-flex align-items-center justify-content-center p-0"
                                            style="width: 34px; height: 34px;"
                                            title="${isInProg ? 'Tiếp tục thực hiện theo từng bước' : 'Thực hiện theo từng bước quy trình'}"
                                            aria-label="${isInProg ? 'Tiếp tục bước' : 'Theo bước'}"
                                            onclick="startPlanItemWorkflow(${it.planItemId}, ${it.serviceId}, ${st.stageId ? Number(st.stageId) : 'null'})">
                                        <i class="bi bi-list-check" style="font-size: 15px;"></i>
                                    </button>
                                    <button type="button" class="btn btn-sm df-btn-tonal-primary rounded-circle d-inline-flex align-items-center justify-content-center p-0"
                                            style="width: 34px; height: 34px;"
                                            title="Làm nhanh (hoàn tất ngay dịch vụ này)"
                                            aria-label="Làm nhanh"
                                            onclick="executePlanItemDirectly(${it.planItemId})">
                                        <i class="bi bi-lightning-charge-fill" style="font-size: 14px;"></i>
                                    </button>
                                </div>
                            `;
                        }

                        const isBookedItem = Boolean(bookedSrvId && it.serviceId && Number(it.serviceId) === bookedSrvId);
                        const isParaclinical = Boolean(it.isParaclinical);
                        const itemMins = Number(it.estimatedDurationMinutes) > 0 ? Number(it.estimatedDurationMinutes) : 30;
                        const allowAddon = it.allowSameVisit !== false;
                        let isDeferredHint = false;
                        if (isComp || isInProg || isBookedItem || isParaclinical) {
                            isDeferredHint = false;
                            if (isInProg && !isBookedItem && !isParaclinical) {
                                if (!primarySlotClaimed) {
                                    primarySlotClaimed = true;
                                } else {
                                    simAddonMinutes += itemMins;
                                }
                            }
                        } else if (!primarySlotClaimed) {
                            primarySlotClaimed = true;
                            isDeferredHint = false;
                        } else if (!allowAddon) {
                            isDeferredHint = true;
                        } else if (waitingCount > 0 && (itemMins > 45 || (simAddonMinutes + itemMins) > 60)) {
                            isDeferredHint = true;
                        } else {
                            isDeferredHint = false;
                            simAddonMinutes += itemMins;
                        }
                        const deferredTitle = !allowAddon
                            ? 'Dịch vụ cấu hình không làm gộp trong ca — hẹn buổi sau'
                            : `Đang có ${waitingCount} bệnh nhân chờ — dịch vụ làm thêm (~${itemMins} phút) vượt ngưỡng 45p/DV hoặc 60p tổng làm thêm`;
                        const bookedBadgeHtml = isBookedItem
                            ? '<span class="badge bg-info-subtle text-info-emphasis border border-info-subtle ms-1" style="font-size: 10.5px;"><i class="bi bi-lock-fill me-1"></i>Đã đặt lịch</span>'
                            : (isDeferredHint
                                ? `<span class="badge bg-warning-subtle text-warning-emphasis border border-warning-subtle ms-1" style="font-size: 10.5px;" title="${deferredTitle}"><i class="bi bi-calendar2-week me-1"></i>Hẹn ca sau</span>`
                                : '');

                        itemsRows += `
                            <tr id="item-row-${it.planItemId}">
                                <td class="text-center text-muted df-num">${idx + 1}</td>
                                <td>
                                    <strong class="text-dark">${it.stepName || it.serviceName}</strong>
                                    ${(it.stepName && it.stepName !== it.serviceName) ? `<span class="badge bg-light text-primary border ms-1" style="font-size: 10px;">${it.serviceName}</span>` : (it.categoryName ? `<span class="badge bg-light text-muted border ms-1" style="font-size: 10px;">${it.categoryName}</span>` : '')}
                                    ${bookedBadgeHtml}
                                </td>
                                <td class="text-center">${toothBadge}</td>
                                <td class="text-center df-num">
                                    <span class="fw-bold text-dark">${(it.quantity != null && Number(it.quantity) % 1 === 0) ? Number(it.quantity) : (it.quantity || 1)}</span>
                                    <span class="badge bg-light text-secondary border ms-1" style="font-size: 11px;">${(it.unit && it.unit.trim()) ? it.unit.trim() : 'răng'}</span>
                                </td>
                                <td class="text-center small df-num" id="item-completed-cell-${it.planItemId}">${completedItemHtml}</td>
                                <td class="text-center" id="item-status-cell-${it.planItemId}">${statusBadgeHtml}</td>
                                <td class="text-center" id="item-action-cell-${it.planItemId}">${actionBtnHtml}</td>
                            </tr>
                        `;
                    });
                } else {
                    itemsRows = '<tr><td colspan="7" class="text-center text-muted py-2 fst-italic">Giai đoạn này chưa có danh mục bước thực hiện chi tiết.</td></tr>';
                }

                const stageStatusClass = st.status === 'COMPLETED' ? 'bg-success-subtle text-success border border-success fw-bold' : (st.status === 'IN_PROGRESS' ? 'bg-primary-subtle text-primary border border-primary fw-bold' : 'bg-light text-secondary border');
                const stageStatusIcon = st.status === 'COMPLETED' ? 'bi-check-circle-fill' : (st.status === 'IN_PROGRESS' ? 'bi-arrow-repeat' : 'bi-clock');
                const stageProgressSuffix = totalItemsInStage > 0 && (st.status === 'COMPLETED' || st.status === 'IN_PROGRESS' || doneItemsInStage > 0)
                    ? ` (${doneItemsInStage}/${totalItemsInStage})`
                    : '';
                const stageStatusText = (st.status === 'COMPLETED' ? 'Hoàn thành' : (st.status === 'IN_PROGRESS' ? 'Đang thực hiện' : 'Chưa thực hiện')) + stageProgressSuffix;

                stagesInsideCourseHtml += `
                    <div class="${cardClass}" id="stage-card-${st.stageId}">
                        <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 pb-2 mb-2 border-bottom">
                            <div class="d-flex align-items-center gap-2">
                                <span class="badge bg-light text-dark border px-2 py-1 rounded-pill fw-semibold df-num" style="font-size: 11.5px;">Giai đoạn ${st.internalOrder}</span>
                                <strong class="text-dark" style="font-size: 14px;">${st.cleanStageName}</strong>
                            </div>
                            <div class="d-flex flex-wrap align-items-center gap-2">
                                <span class="badge bg-white text-dark border px-2 py-1 rounded-pill df-num">
                                    <i class="bi bi-calendar-check text-primary me-1"></i>
                                    <span>Dự kiến xong: </span>
                                    <strong>${expectedEnd}</strong>
                                </span>
                                <span class="badge px-3 py-1 rounded-pill d-inline-flex align-items-center gap-1 df-num ${stageStatusClass}" id="stage-status-badge-${st.stageId}">
                                    <i class="bi ${stageStatusIcon}"></i>
                                    <span>${stageStatusText}</span>
                                </span>
                            </div>
                        </div>
                        ${st.description ? `<div class="small text-secondary mb-2 fst-italic"><i class="bi bi-info-circle me-1"></i>${st.description}</div>` : ''}
                        <div class="table-responsive bg-white">
                            <table class="table df-clean-table align-middle mb-0" data-disable-floating-actions="true" style="font-size: 13px;">
                                <thead>
                                    <tr>
                                        <th style="width: 45px;" class="text-center">#</th>
                                        <th>Dịch vụ / Thủ thuật điều trị</th>
                                        <th style="width: 110px;" class="text-center">Vị trí răng</th>
                                        <th style="width: 95px;" class="text-center">Số lượng</th>
                                        <th style="width: 165px;" class="text-center">Ngày thực hiện</th>
                                        <th style="width: 130px;" class="text-center">Trạng thái</th>
                                        <th style="width: 105px;" class="text-center">Thao tác</th>
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

            coursesHtml += `
                <div class="tp-flat-course" id="${course.courseId}-wrapper">
                    <div class="py-2 d-flex flex-wrap align-items-center justify-content-between gap-2"
                         style="cursor: pointer; user-select: none;"
                         onclick="toggleCourseAccordion('${course.courseId}')"
                         title="Nhấn để gập / mở quá trình điều trị này">
                        
                        <div class="d-flex align-items-center gap-2 flex-grow-1">
                            <span class="badge rounded-pill px-2 py-1 fw-bold df-num"
                                  style="background: #e0f2fe; color: #0369a1; border: 1px solid #bae6fd; font-size: 11.5px;">
                                Quá trình ${course.courseIndex}
                            </span>
                            <div class="d-flex flex-column">
                                <span class="text-dark fw-bold" style="font-size: 14.5px; line-height: 1.35;">${course.courseTitle}</span>
                                <span class="df-meta-tertiary df-num">
                                    <i class="bi bi-layers me-1"></i>${course.stages.length} giai đoạn &bull; Đã hoàn thành <strong>${completedStagesInCourse}/${course.stages.length}</strong>
                                </span>
                            </div>
                        </div>

                        <div class="d-flex align-items-center gap-2">
                            <span class="badge ${courseStatusBadgeClass} px-2 py-1 rounded-pill d-inline-flex align-items-center gap-1" style="font-size: 12px;">
                                <i class="bi ${courseStatusIcon}"></i>
                                <span>${courseStatusText}</span>
                            </span>

                            <button type="button" class="btn btn-sm btn-white rounded-circle border d-flex align-items-center justify-content-center p-0"
                                    style="width: 30px; height: 30px; background: #ffffff; border-color: #e2e8f0 !important;"
                                    aria-label="Gập mở quá trình">
                                <i class="bi ${chevronIconClass} text-secondary df-course-chevron" id="chevron-${course.courseId}"></i>
                            </button>
                        </div>
                    </div>

                    <div class="pt-2" id="${course.courseId}-body" style="display: ${bodyDisplayStyle};">
                        ${stagesInsideCourseHtml}
                    </div>
                </div>
            `;
        });
    }

    const headerHtml = omitHeader ? '' : `
            <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3 pb-2 border-bottom">
                <div class="d-flex align-items-center gap-2">
                    <span class="badge rounded-pill px-2 py-1 fw-semibold" style="background: #e0f2fe; color: #0369a1; border: 1px solid #bae6fd;">
                        <i class="bi bi-diagram-3-fill me-1"></i>Lộ trình điều trị
                    </span>
                    <strong class="text-dark" style="font-size: 15px;">${escapePlanHtml(plan.planName)}</strong>
                </div>
                <div class="d-flex flex-wrap align-items-center gap-2 small">
                    <span class="badge bg-white text-dark border px-2 py-1 rounded-pill df-num">
                        <i class="bi bi-layers text-primary me-1"></i>
                        <span>Tổng cộng: </span>
                        <strong>${courses.length} quá trình</strong>
                    </span>
                    <span class="badge bg-white text-dark border px-2 py-1 rounded-pill df-num">
                        <i class="bi bi-calendar-check text-primary me-1"></i>
                        <span>Dự kiến xong: </span>
                        <strong>${plan.expectedEndDate || '---'}</strong>
                    </span>
                    <span class="badge bg-light text-primary border px-2 py-1 rounded-pill fw-bold df-num" id="plan-progress-badge-${plan.planId}">
                        ${plan.totalItems ? `${plan.completedItems}/${plan.totalItems} hạng mục (${plan.progressPercent}%)` : `${plan.completedStages}/${plan.totalStages} giai đoạn (${plan.progressPercent}%)`}
                    </span>
                    <button type="button" class="btn btn-sm btn-outline-secondary rounded-pill px-2 py-1 d-inline-flex align-items-center gap-1 ms-1"
                            onclick="toggleAllCoursesInPlan(${plan.planId})"
                            id="btn-toggle-all-${plan.planId}"
                            title="Thu gọn hoặc mở tất cả các quá trình">
                        <i class="bi bi-arrows-collapse" id="icon-toggle-all-${plan.planId}"></i>
                        <span id="text-toggle-all-${plan.planId}">Thu gọn tất cả</span>
                    </button>
                </div>
            </div>`;

    return `
        <div class="${omitHeader ? 'df-inline-roadmap-container' : 'df-inline-roadmap-container p-3 p-md-4'}" style="width: 100%; max-width: 100%; box-sizing: border-box; overflow-x: hidden;">
            ${headerHtml}
            <div id="stages-container-${plan.planId}">
                ${coursesHtml}
            </div>
        </div>
    `;
}

function escapePlanHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

/**
 * Render tiến độ thực hiện theo từng Chẩn đoán độc lập (1 chẩn đoán 1 bảng)
 * Dành cho tab Dịch vụ thực hiện (#tab-performed-services)
 */
function renderDiagnosisProgressTables() {
    const container = document.getElementById('diagnosisProgressContainer');
    if (!container) return;

    const plans = (window.INITIAL_TREATMENT_PLANS || []).filter(p => p && p.status !== 'CANCELLED');
    if (!plans || plans.length === 0) {
        container.innerHTML = '<div class="text-center py-4 text-muted fst-italic">Bệnh nhân chưa có kế hoạch hoặc chẩn đoán điều trị nào.</div>';
        return;
    }

    let allDiagnosisCardsHtml = '';
    let totalServicesDoneAcrossAll = 0;

    plans.forEach(plan => {
        const courses = groupStagesIntoCourses(plan.stages || [], plan);
        if (!courses || courses.length === 0) return;

        let planTotalSteps = 0;
        let planDoneSteps = 0;
        let coursesHtml = '';

        courses.forEach(course => {
            // 1. Tính toán tiến độ của riêng chẩn đoán / quá trình này
            let totalSteps = 0;
            let doneSteps = 0;

            if (course.stages && course.stages.length > 0) {
                course.stages.forEach(st => {
                    if (st.items && st.items.length > 0) {
                        st.items.forEach(it => {
                            totalSteps++;
                            planTotalSteps++;
                            if (it.status === 'COMPLETED') {
                                doneSteps++;
                                planDoneSteps++;
                                totalServicesDoneAcrossAll++;
                            }
                        });
                    }
                });
            }

            // 2. Phân tích courseTitle để lấy CĐ/SV prefix, ICD code và tên sạch đẹp
            let rawTitle = (course.courseTitle || '').trim();
            let cdPrefix = '';
            let isServicePrefix = false;
            let icdCode = '';
            let diagName = rawTitle;

            const cdMatch = diagName.match(/^\[((?:CĐ|SV)\s*#?\d+)\]\s*(.*)$/i);
            if (cdMatch) {
                cdPrefix = cdMatch[1].toUpperCase().replace(/\s+/g, ' ');
                isServicePrefix = cdPrefix.startsWith('SV');
                diagName = cdMatch[2].trim();
            }

            const icdMatch = diagName.match(/\[([A-Z]\d{2}(?:\.\d+)?)\]\s*(.*)/i);
            if (icdMatch) {
                icdCode = icdMatch[1];
                diagName = icdMatch[2].trim();
            }

            if (!diagName && plan.planName) {
                diagName = plan.planName;
            }

            // 3. Render các hàng của bảng trực tiếp
            let tbodyRowsHtml = '';
            const multipleStages = course.stages && course.stages.length > 1;

            if (!course.stages || course.stages.length === 0 || totalSteps === 0) {
                tbodyRowsHtml = '<tr><td colspan="6" class="text-center text-muted fst-italic py-3">Mục này chưa có bước thực hiện nào.</td></tr>';
            } else {
                let globalItemIdx = 0;
                course.stages.forEach((stage, sIdx) => {
                    const stageItems = stage.items || [];
                    const stageTotalCnt = stageItems.length;
                    const stageDoneCnt = stageItems.filter(i => i.status === 'COMPLETED').length;
                    const stageIsAllDone = stageTotalCnt > 0 && stageDoneCnt === stageTotalCnt;

                    // Nếu có nhiều giai đoạn trong cùng mục, hiển thị 1 dòng tiêu đề phân chia giai đoạn nhẹ nhàng
                    if (multipleStages) {
                        tbodyRowsHtml += `
                            <tr class="table-light" style="border-top: 1px solid #e2e8f0;">
                                <td colspan="6" class="py-2 px-3 fw-semibold text-dark" style="font-size: 13px; background: #f8fafc;">
                                    <span class="badge bg-white text-dark border rounded-pill me-2 df-num" style="font-size: 11px;">Giai đoạn ${stage.internalOrder || (sIdx + 1)}</span>
                                    <span>${escapePlanHtml(stage.cleanStageName || stage.stageName)}</span>
                                    <span class="badge rounded-pill ms-2 df-num ${stageIsAllDone ? 'bg-success-subtle text-success border border-success' : (stageDoneCnt > 0 ? 'bg-primary-subtle text-primary border border-primary' : 'bg-light text-secondary border')}">
                                        ${stageDoneCnt}/${stageTotalCnt} bước đã làm
                                    </span>
                                </td>
                            </tr>
                        `;
                    }

                    stageItems.forEach((item) => {
                        globalItemIdx++;
                        const isDone = item.status === 'COMPLETED';

                        let toothBadge = '<span class="text-muted">-</span>';
                        if (item.toothNumbers) {
                            const tn = String(item.toothNumbers).trim();
                            if (tn.toLowerCase().includes('toàn hàm') || tn.toLowerCase().includes('toan ham')) {
                                toothBadge = '<span class="chip-tooth">Toàn hàm</span>';
                            } else {
                                const cleanNum = tn.startsWith('R-') || tn.startsWith('R') ? tn : `R-${tn}`;
                                toothBadge = `<span class="chip-tooth df-num">${escapePlanHtml(cleanNum)}</span>`;
                            }
                        }

                        const qtyDisplay = (item.quantity != null && Number(item.quantity) % 1 === 0)
                            ? Number(item.quantity)
                            : (item.quantity || 1);

                        const completedAtHtml = (isDone && item.completedAt)
                            ? `<span class="text-success fw-semibold df-num">${escapePlanHtml(item.completedAt)}</span>`
                            : '<span class="text-muted">---</span>';

                        const statusBadgeHtml = isDone
                            ? '<span class="badge bg-success-subtle text-success border border-success-subtle rounded-pill px-2 py-1 fw-bold"><i class="bi bi-check2-circle me-1"></i>Đã làm</span>'
                            : '<span class="badge bg-light text-secondary border rounded-pill px-2 py-1 fw-semibold"><i class="bi bi-clock me-1"></i>Chưa làm</span>';

                        tbodyRowsHtml += `
                            <tr class="${isDone ? 'ps-step-done' : 'ps-step-todo'}">
                                <td class="text-center text-muted df-num">${globalItemIdx}</td>
                                <td>
                                    <i class="bi ${isDone ? 'bi-check-circle-fill text-success' : 'bi-circle text-secondary'} me-1"></i>
                                    <strong class="ps-step-name">${escapePlanHtml(item.stepName || item.serviceName)}</strong>
                                    ${(item.serviceName && item.stepName && item.stepName !== item.serviceName) ? `<span class="badge bg-light text-primary border ms-1" style="font-size: 10px;">${escapePlanHtml(item.serviceName)}</span>` : ''}
                                </td>
                                <td class="text-center">${toothBadge}</td>
                                <td class="text-center fw-bold df-num">${qtyDisplay}</td>
                                <td class="text-center small df-num">${completedAtHtml}</td>
                                <td class="text-center">${statusBadgeHtml}</td>
                            </tr>
                        `;
                    });
                });
            }

            // Mỗi dịch vụ / chẩn đoán là 1 bảng riêng biệt bên trong Kế hoạch điều trị
            coursesHtml += `
                <div class="border rounded-3 overflow-hidden bg-white" style="border-color: #e2e8f0 !important;">
                    <div class="p-2 px-3 border-bottom d-flex flex-wrap align-items-center justify-content-between gap-2" style="background: #f8fafc; border-color: #e2e8f0 !important;">
                        <div class="d-flex align-items-center gap-2 flex-wrap">
                            ${cdPrefix ? `<span class="badge rounded-pill px-2 py-1 fw-bold df-num" style="background: #e0f2fe; color: #0369a1; border: 1px solid #bae6fd; font-size: 11.5px;">${escapePlanHtml(cdPrefix)}</span>` : ''}
                            <strong class="text-dark" style="font-size: 13.5px;">${isServicePrefix ? 'Dịch vụ:' : 'Chẩn đoán:'} ${escapePlanHtml(diagName)}</strong>
                            ${icdCode ? `<span class="badge bg-warning-subtle text-warning-emphasis border border-warning-subtle rounded-pill px-2 py-1 df-num"><i class="bi bi-clipboard2-pulse me-1"></i>[${escapePlanHtml(icdCode)}]</span>` : ''}
                        </div>
                        <span class="badge rounded-pill px-2 py-1 df-num ${doneSteps > 0 && doneSteps === totalSteps ? 'bg-success-subtle text-success border border-success' : (doneSteps > 0 ? 'bg-primary-subtle text-primary border border-primary' : 'bg-light text-secondary border')}">
                            <i class="bi ${doneSteps > 0 && doneSteps === totalSteps ? 'bi-check2-circle' : 'bi-clock'} me-1"></i>
                            <span>${doneSteps}/${totalSteps} bước đã làm</span>
                        </span>
                    </div>
                    <div class="table-responsive">
                        <table class="table df-clean-table align-middle mb-0" data-disable-floating-actions="true" style="font-size: 13px;">
                            <thead>
                            <tr>
                                <th style="width: 45px;" class="text-center">#</th>
                                <th>Bước thực hiện / Dịch vụ</th>
                                <th style="width: 110px;" class="text-center">Vị trí răng</th>
                                <th style="width: 90px;" class="text-center">Số lượng</th>
                                <th style="width: 160px;" class="text-center">Ngày thực hiện</th>
                                <th style="width: 130px;" class="text-center">Trạng thái</th>
                            </tr>
                            </thead>
                            <tbody>
                                ${tbodyRowsHtml}
                            </tbody>
                        </table>
                    </div>
                </div>
            `;
        });

        const planPct = planTotalSteps > 0 ? Math.round((planDoneSteps / planTotalSteps) * 100) : 0;

        // Cùng 1 Kế hoạch điều trị thì gom chung vào 1 khối (ps-plan-card), bên trong chứa các bảng tương ứng
        allDiagnosisCardsHtml += `
            <div class="ps-plan-card">
                <div class="ps-plan-head d-flex flex-wrap align-items-center justify-content-between gap-2">
                    <div class="d-flex align-items-center gap-2 flex-wrap">
                        <span class="badge rounded-pill px-2 py-1 fw-bold df-num" style="background: #f1f5f9; color: #0369a1; border: 1px solid #cbd5e1;">${escapePlanHtml(plan.planCode || 'KHDT')}</span>
                        <strong class="text-dark" style="font-size: 14.5px;">${escapePlanHtml(plan.planName)}</strong>
                    </div>
                    <div class="d-flex align-items-center gap-2 df-num">
                        <span class="small text-secondary fw-semibold">${planDoneSteps}/${planTotalSteps} bước đã làm</span>
                        <div class="progress" style="width: 110px; height: 6px;">
                            <div class="progress-bar bg-success" role="progressbar" style="width: ${planPct}%"></div>
                        </div>
                        <strong class="text-success small">${planPct}%</strong>
                    </div>
                </div>
                <div class="p-3 bg-white d-flex flex-column gap-3">
                    ${coursesHtml}
                </div>
            </div>
        `;
    });

    container.innerHTML = allDiagnosisCardsHtml || '<div class="text-center py-4 text-muted fst-italic">Chưa có giai đoạn điều trị nào được thiết lập.</div>';

    const totalBadge = document.getElementById('psTotalServicesDoneBadge');
    if (totalBadge) {
        totalBadge.innerText = `${totalServicesDoneAcrossAll} dịch vụ đã làm`;
    }
}
window.renderDiagnosisProgressTables = renderDiagnosisProgressTables;

window._activeTreatmentPlanId = null;

/**
 * Render giao diện Chi tiết Kế hoạch điều trị (Master-Detail View)
 */
function renderTreatmentPlanDetailHtml(plan) {
    if (!plan) return '';

    const sectionEl = document.getElementById('treatmentPlanSection');
    const visitToken = sectionEl ? (sectionEl.getAttribute('data-visit-token') || '') : '';
    const isClinicalLocked = sectionEl && sectionEl.getAttribute('data-clinical-locked') === 'true';

    const courses = groupStagesIntoCourses(plan.stages || [], plan);
    const pct = Number(plan.progressPercent || 0);
    const progressText = (plan.totalItems && plan.totalItems > 0)
        ? `${plan.completedItems || 0}/${plan.totalItems} hạng mục (${pct}%)`
        : `${plan.completedStages || 0}/${plan.totalStages || 0} giai đoạn (${pct}%)`;

    const statusBadgeClass = plan.statusBadgeClass || 'bg-light text-secondary border';
    const statusDisplayName = plan.statusDisplayName || 'Đang xử lý';
    const installmentCount = Array.isArray(plan.installments) ? plan.installments.length : 0;

    const editLinkHtml = (visitToken && !isClinicalLocked)
        ? `<a href="/doctor/examination/${encodeURIComponent(visitToken)}/treatment-plans/create?planId=${plan.planId}"
              class="df-btn-plan-detail text-decoration-none"
              title="Chỉnh sửa kế hoạch điều trị">
               <i class="bi bi-pencil-square"></i>
               <span>Chỉnh sửa</span>
           </a>`
        : '';

    const notesSectionHtml = (plan.diagnosisText || plan.notes)
        ? `<div class="tp-detail-notes">
               ${plan.diagnosisText ? `<div class="small mb-1"><span class="fw-semibold text-secondary">Chẩn đoán liên quan:</span> <span class="text-dark">${escapePlanHtml(plan.diagnosisText)}</span></div>` : ''}
               ${plan.notes ? `<div class="small mb-0"><span class="fw-semibold text-secondary">Ghi chú:</span> <span class="text-dark">${escapePlanHtml(plan.notes)}</span></div>` : ''}
           </div>`
        : '';

    const roadmapBodyHtml = renderPlanInlineRoadmapHtml(plan, { omitHeader: true });

    return `
        <div class="tp-detail-summary-grid">
            <div>
                <div class="df-meta-tertiary mb-1"><i class="bi bi-calendar-event text-primary me-1"></i>Thời gian thực hiện</div>
                <div class="fw-semibold text-dark df-num" style="font-size: 13.5px;">
                    ${escapePlanHtml(plan.startDate || '---')} &rarr; ${escapePlanHtml(plan.expectedEndDate || '---')}
                </div>
                <div class="df-meta-tertiary mt-1 df-num">${courses.length} quá trình điều trị</div>
            </div>
            <div>
                <div class="df-meta-tertiary mb-1"><i class="bi bi-graph-up-arrow text-primary me-1"></i>Tiến độ điều trị</div>
                <div class="d-flex align-items-center justify-content-between mb-1">
                    <span class="fw-bold text-primary df-num" style="font-size: 13.5px;" id="plan-progress-badge-${plan.planId}">${progressText}</span>
                </div>
                <div class="progress" style="height: 6px;">
                    <div class="progress-bar bg-success" role="progressbar" style="width: ${pct}%" aria-valuenow="${pct}" aria-valuemin="0" aria-valuemax="100"></div>
                </div>
            </div>
            <div>
                <div class="df-meta-tertiary mb-1"><i class="bi bi-cash-coin text-primary me-1"></i>Chi phí dự kiến</div>
                <div class="fw-bold text-primary df-num" style="font-size: 15px;">${window.formatVnd(plan.finalEstimatedCost || 0)}</div>
                <div class="d-flex flex-wrap align-items-center gap-1 mt-1">
                    <span class="badge bg-success-subtle text-success border border-success d-inline-flex align-items-center gap-1 py-1 px-2" style="font-size: 11px;">
                        <i class="bi bi-check2-all text-success"></i>Đã lưu Kế toán
                    </span>
                    ${plan.isInstallment ? `<span class="badge bg-info-subtle text-info border border-info d-inline-flex align-items-center gap-1 py-1 px-2 df-num" style="font-size: 11px;"><i class="bi bi-calendar2-range"></i>Trả theo đợt (${installmentCount} đợt)</span>` : ''}
                </div>
            </div>
        </div>

        ${notesSectionHtml}

        <div id="plan-roadmap-row-${plan.planId}">
            ${roadmapBodyHtml}
        </div>
    `;
}

/**
 * Khởi tạo render bảng tiến độ tab Dịch vụ thực hiện từ cache window.INITIAL_TREATMENT_PLANS
 */
function initAllPlanRoadmaps() {
    renderDiagnosisProgressTables();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAllPlanRoadmaps);
} else {
    initAllPlanRoadmaps();
}

function syncTpDetailTopbar(isOpen, plan) {
    const countBadge = document.getElementById('planCountBadge');
    const backWrap = document.getElementById('tpDetailBackWrap');
    const codeEl = document.getElementById('tpDetailHeaderCode');
    const nameEl = document.getElementById('tpDetailHeaderName');
    if (countBadge) countBadge.classList.toggle('d-none', !!isOpen);
    if (backWrap) backWrap.classList.toggle('d-none', !isOpen);
    if (codeEl) codeEl.textContent = isOpen && plan ? (plan.planCode || '') : '';
    if (nameEl) nameEl.textContent = isOpen && plan ? (plan.planName || '') : '';
}

/**
 * Mở chi tiết kế hoạch điều trị & ẩn danh sách
 */
function openTreatmentPlanDetail(planId) {
    if (!planId) return;
    const listView = document.getElementById('tpListView');
    const detailView = document.getElementById('tpDetailView');
    const detailContent = document.getElementById('tpDetailContent');
    if (!detailView || !detailContent) return;

    window._activeTreatmentPlanId = String(planId);
    if (listView) listView.style.display = 'none';
    detailView.style.display = 'block';

    const scrollToSectionTop = () => {
        const sectionEl = document.getElementById('treatmentPlanSection');
        if (!sectionEl) return;
        setTimeout(() => {
            const targetTop = sectionEl.getBoundingClientRect().top + window.pageYOffset - 90;
            window.scrollTo({ top: Math.max(0, targetTop), behavior: 'smooth' });
        }, 40);
    };

    const planData = (window.INITIAL_TREATMENT_PLANS || []).find(p => String(p.planId) === String(planId));
    if (planData) {
        syncTpDetailTopbar(true, planData);
        detailContent.innerHTML = renderTreatmentPlanDetailHtml(planData);
        scrollToSectionTop();
        return;
    }

    syncTpDetailTopbar(true, null);
    detailContent.innerHTML = `
        <div class="text-center py-5 text-muted">
            <div class="spinner-border spinner-border-sm text-primary me-2" role="status"></div>
            <span>Đang tải chi tiết kế hoạch điều trị...</span>
        </div>
    `;

    fetch(`/api/doctor/treatment-plans/${planId}`)
        .then(r => {
            if (!r.ok) {
                throw new Error('Không thể tải chi tiết kế hoạch điều trị');
            }
            return r.json();
        })
        .then(dto => {
            if (!Array.isArray(window.INITIAL_TREATMENT_PLANS)) {
                window.INITIAL_TREATMENT_PLANS = [];
            }
            const idx = window.INITIAL_TREATMENT_PLANS.findIndex(p => String(p.planId) === String(dto.planId));
            if (idx !== -1) window.INITIAL_TREATMENT_PLANS[idx] = dto;
            else window.INITIAL_TREATMENT_PLANS.push(dto);

            if (String(window._activeTreatmentPlanId) === String(planId)) {
                syncTpDetailTopbar(true, dto);
                detailContent.innerHTML = renderTreatmentPlanDetailHtml(dto);
                scrollToSectionTop();
            }
        })
        .catch(e => {
            console.error(e);
            if (typeof showToast === 'function') {
                showToast(e.message || 'Lỗi tải chi tiết kế hoạch điều trị', true);
            }
            closeTreatmentPlanDetail();
        });
}
window.openTreatmentPlanDetail = openTreatmentPlanDetail;

/**
 * Đóng chi tiết và quay lại danh sách kế hoạch điều trị
 */
function closeTreatmentPlanDetail() {
    window._activeTreatmentPlanId = null;
    const listView = document.getElementById('tpListView');
    const detailView = document.getElementById('tpDetailView');
    const detailContent = document.getElementById('tpDetailContent');
    syncTpDetailTopbar(false, null);
    if (detailView) detailView.style.display = 'none';
    if (detailContent) detailContent.innerHTML = '';
    if (listView) listView.style.display = 'block';
    if (typeof window.refreshTpGrid === 'function') {
        window.refreshTpGrid();
    }
}
window.closeTreatmentPlanDetail = closeTreatmentPlanDetail;

/**
 * Alias giữ tương thích cho các lời gọi cũ
 */
function togglePlanInlineRoadmap(planId) {
    openTreatmentPlanDetail(planId);
}
window.togglePlanInlineRoadmap = togglePlanInlineRoadmap;

/**
 * Cập nhật giao diện inline khi thay đổi trạng thái
 */
function updatePlanInlineView(plan) {
    if (!plan || !plan.planId) return;

    if (window.INITIAL_TREATMENT_PLANS && Array.isArray(window.INITIAL_TREATMENT_PLANS)) {
        const idx = window.INITIAL_TREATMENT_PLANS.findIndex(p => String(p.planId) === String(plan.planId));
        if (idx !== -1) {
            window.INITIAL_TREATMENT_PLANS[idx] = plan;
        } else {
            window.INITIAL_TREATMENT_PLANS.push(plan);
        }
    }

    if (window._activeTreatmentPlanId && String(window._activeTreatmentPlanId) === String(plan.planId)) {
        syncTpDetailTopbar(true, plan);
        const detailContent = document.getElementById('tpDetailContent');
        if (detailContent) {
            detailContent.innerHTML = renderTreatmentPlanDetailHtml(plan);
        }
    }

    const parentRow = document.getElementById(`plan-row-${plan.planId}`);
    if (parentRow) {
        const progBadge = document.getElementById(`plan-progress-badge-${plan.planId}`);
        if (progBadge) {
            progBadge.innerText = (plan.totalItems && plan.totalItems > 0)
                ? `${plan.completedItems}/${plan.totalItems} hạng mục (${plan.progressPercent}%)`
                : `${plan.completedStages}/${plan.totalStages} giai đoạn (${plan.progressPercent}%)`;
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
        if (stageCountText) {
            stageCountText.innerText = (plan.totalItems && plan.totalItems > 0)
                ? `${plan.completedItems}/${plan.totalItems} hạng mục`
                : `${plan.completedStages}/${plan.totalStages} giai đoạn`;
        }

        const statusCell = document.getElementById(`plan-status-cell-${plan.planId}`);
        if (statusCell && plan.statusDisplayName) {
            const btn = statusCell.querySelector('button.dropdown-toggle');
            if (btn) {
                btn.className = `btn btn-sm dropdown-toggle rounded-pill px-2 py-1 fw-bold border shadow-sm d-inline-flex align-items-center gap-1 ${plan.statusBadgeClass}`;
                btn.innerHTML = `<span>${plan.statusDisplayName}</span>`;
            }
        }
        if (plan.status) {
            parentRow.setAttribute('data-status', plan.status);
        }
        if (typeof window.refreshTpGrid === 'function') {
            window.refreshTpGrid();
        }
    }

    if (typeof renderDiagnosisProgressTables === 'function') {
        renderDiagnosisProgressTables();
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
        if (window.FT43 && typeof window.FT43.reload === 'function') {
            window.FT43.reload();
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
        if (window.FT43 && typeof window.FT43.reload === 'function') {
            window.FT43.reload();
        }
    })
    .catch(err => {
        showToast(err.message, true);
    });
}

/**
 * Bác sĩ nhấn "Làm trong ca này": liên kết trực tiếp bước KHDT vào ca khám hiện tại (FT15 -> FT16)
 */
function executePlanItemDirectly(planItemId, patientConfirmed = false) {
    window._executingPlanItems = window._executingPlanItems || new Set();
    if (window._executingPlanItems.has(planItemId)) {
        return;
    }
    const clickedBtn = typeof event !== 'undefined' && event && event.target ? event.target.closest('button') : null;
    if (clickedBtn) {
        clickedBtn.disabled = true;
        clickedBtn.dataset.origHtml = clickedBtn.innerHTML;
        clickedBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span> Đang xử lý...';
    }
    window._executingPlanItems.add(planItemId);
    const resetBtn = () => {
        window._executingPlanItems.delete(planItemId);
        if (clickedBtn) {
            clickedBtn.disabled = false;
            if (clickedBtn.dataset.origHtml) clickedBtn.innerHTML = clickedBtn.dataset.origHtml;
        }
    };
    const visitIdInput = document.getElementById('visitId') || document.getElementById('treatVisitId');
    const visitId = visitIdInput ? visitIdInput.value : null;
    if (!visitId) {
        resetBtn();
        showToast('Không xác định được ca khám hiện tại!', true);
        return;
    }

    fetch(`/api/doctor/treatment-plans/items/${planItemId}/execute-in-visit?visitId=${visitId}&patientConfirmed=${patientConfirmed ? 'true' : 'false'}`, {
        method: 'POST'
    })
    .then(res => {
        if (!res.ok) {
            resetBtn();
            return res.json().then(err => { throw new Error(err.message || 'Lỗi khi ghi nhận dịch vụ thực hiện'); });
        }
        return res.json();
    })
    .then(result => {
        resetBtn();
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
        if (window.FT43 && typeof window.FT43.reload === 'function') {
            window.FT43.reload();
        }
    })
    .catch(err => {
        resetBtn();
        const msg = err && err.message ? err.message : 'Lỗi khi ghi nhận dịch vụ thực hiện';
        if (msg.startsWith('PATIENT_CONFIRM_REQUIRED:') && !patientConfirmed) {
            const cleanMsg = msg.replace(/^PATIENT_CONFIRM_REQUIRED:\s*/, '');
            window.dfConfirm(cleanMsg + '\n\nXác nhận bệnh nhân đồng ý và thực hiện luôn dịch vụ này trong ca khám hiện tại?', {
                title: 'Xác nhận thực hiện dịch vụ phát sinh',
                confirmText: 'Xác nhận thực hiện',
                cancelText: 'Để buổi sau',
                variant: 'primary'
            }).then(ok => {
                if (ok) executePlanItemDirectly(planItemId, true);
            });
            return;
        }
        showToast(msg.replace(/^PATIENT_CONFIRM_REQUIRED:\s*/, ''), true);
    });
}

/**
 * Mở tab Quy trình thực hiện để khám & cập nhật từng bước cho hạng mục KHDT (FT43 <-> FT15)
 */
function startPlanItemWorkflow(planItemId, serviceId, planStageId, patientConfirmed = false) {
    if (typeof window.openTreatmentPlanStepWorkflow === 'function') {
        window.openTreatmentPlanStepWorkflow(planItemId, serviceId, planStageId, patientConfirmed);
        return;
    }
    const visitIdInput = document.getElementById('visitId') || document.getElementById('treatVisitId');
    const visitId = visitIdInput ? visitIdInput.value : null;
    if (!visitId) {
        showToast('Không xác định được ca khám hiện tại!', true);
        return;
    }
    const headers = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
    const tokenMeta = document.querySelector('meta[name="_csrf"]');
    const headerMeta = document.querySelector('meta[name="_csrf_header"]');
    if (tokenMeta && headerMeta && tokenMeta.content && headerMeta.content) {
        headers[headerMeta.content] = tokenMeta.content;
    }
    fetch('/api/doctor/workflows/execution/start', {
        method: 'POST',
        headers,
        body: JSON.stringify({
            visitId: Number(visitId),
            serviceId: serviceId ? Number(serviceId) : null,
            planItemId: planItemId ? Number(planItemId) : null,
            planStageId: planStageId ? Number(planStageId) : null,
            patientConfirmed: !!patientConfirmed
        })
    })
    .then(res => res.json().then(body => {
        if (!res.ok) throw new Error(body.message || 'Không thể khởi tạo quy trình cho hạng mục này.');
        return body;
    }))
    .then(dto => {
        if (typeof window.switchMainTab === 'function') {
            window.switchMainTab('tab-workflow-execution');
        }
        if (window.FT43 && typeof window.FT43.selectExecution === 'function') {
            window.FT43.selectExecution(dto.executionId);
        }
    })
    .catch(err => {
        const msg = err && err.message ? err.message : 'Không thể khởi tạo quy trình cho hạng mục này.';
        if (msg.startsWith('PATIENT_CONFIRM_REQUIRED:') && !patientConfirmed) {
            const cleanMsg = msg.replace(/^PATIENT_CONFIRM_REQUIRED:\s*/, '');
            window.dfConfirm(cleanMsg + '\n\nXác nhận bệnh nhân đồng ý và thực hiện luôn dịch vụ này trong ca khám hiện tại?', {
                title: 'Xác nhận mở quy trình dịch vụ phát sinh',
                confirmText: 'Xác nhận mở quy trình',
                cancelText: 'Để buổi sau',
                variant: 'primary'
            }).then(ok => {
                if (ok) startPlanItemWorkflow(planItemId, serviceId, planStageId, true);
            });
            return;
        }
        showToast(msg.replace(/^PATIENT_CONFIRM_REQUIRED:\s*/, ''), true);
    });
}
window.startPlanItemWorkflow = startPlanItemWorkflow;

/**
 * Hoàn tác bước KHDT về lại Chưa làm
 */
async function revertPlanItemStatus(planItemId) {
    const confirmed = await window.dfConfirm('Bạn có chắc chắn muốn hoàn tác bước này về trạng thái "Chưa làm"?\nTiến độ phác đồ và quy trình điều trị liên kết sẽ được đặt lại.', {
        title: 'Hoàn tác bước thủ thuật',
        confirmText: 'Hoàn tác',
        cancelText: 'Hủy',
        variant: 'warning'
    });
    if (!confirmed) return;
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
        // Xóa ngay dòng dịch vụ tương ứng khỏi bảng Dịch vụ thực hiện tại ghế
        document.querySelectorAll(`tr[data-plan-item-id="${planItemId}"]`).forEach(el => el.remove());
        if (typeof recalcPerformedTableTotals === 'function') {
            recalcPerformedTableTotals();
        }
        if (window.FT43 && typeof window.FT43.reload === 'function') {
            window.FT43.reload();
        }
    })
    .catch(err => {
        showToast(err.message, true);
    });
}

/**
 * Xóa kế hoạch điều trị
 */
async function deleteTreatmentPlan(planId) {
    const confirmed = await window.dfConfirm('Bạn có chắc chắn muốn xóa kế hoạch điều trị này?', {
        title: 'Xóa kế hoạch điều trị',
        confirmText: 'Xóa kế hoạch',
        cancelText: 'Hủy',
        variant: 'danger'
    });
    if (!confirmed) {
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
        if (window._activeTreatmentPlanId && String(window._activeTreatmentPlanId) === String(planId)) {
            closeTreatmentPlanDetail();
        }
        if (Array.isArray(window.INITIAL_TREATMENT_PLANS)) {
            window.INITIAL_TREATMENT_PLANS = window.INITIAL_TREATMENT_PLANS.filter(p => String(p.planId) !== String(planId));
        }
        if (typeof renderDiagnosisProgressTables === 'function') {
            renderDiagnosisProgressTables();
        }
        updatePlanCountBadge();
        if (typeof window.refreshTpGrid === 'function') {
            window.refreshTpGrid();
        }
        if (window.FT43 && typeof window.FT43.reload === 'function') {
            window.FT43.reload();
        }
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
    const tabBadge = document.getElementById('tabBadge-tab-treatment-plan');
    if (tabBadge) {
        tabBadge.innerText = String(rows.length);
    }
}

/**
 * Chuyển kế hoạch điều trị sang bộ phận Kế toán xử lý riêng
 */
async function sendPlanToAccounting(planId, planCode) {
    const code = planCode || 'KHDT';
    const confirmed = await window.dfConfirm(`Xác nhận chuyển Kế hoạch điều trị [${code}] sang bộ phận Kế toán để tư vấn & thu phí lộ trình?`, {
        title: 'Chuyển kế hoạch sang Kế toán',
        confirmText: 'Chuyển Kế toán',
        cancelText: 'Hủy',
        variant: 'primary'
    });
    if (!confirmed) {
        return;
    }

    const btn = document.getElementById(`btn-send-plan-${planId}`);
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Đang chuyển...';
    }

    fetch(`/api/doctor/treatment-plans/${planId}/handover-accounting`, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(res => {
        if (!res.ok) {
            return res.json().then(err => { throw new Error(err.message || 'Không thể chuyển kế hoạch sang Kế toán'); });
        }
        return res.json();
    })
    .then(result => {
        if (typeof showToast === 'function') {
            showToast(`Đã chuyển kế hoạch điều trị [${code}] sang Kế toán thành công!`);
        }

        // Cập nhật ô hiển thị chuyển Kế toán ở cột giá dự kiến
        const box = document.getElementById(`plan-accounting-box-${planId}`);
        if (box) {
            box.innerHTML = `<span class="badge bg-success-subtle text-success border border-success d-inline-flex align-items-center gap-1 py-1 px-2" style="font-size: 11px;" title="Đã chuyển Kế toán xử lý"><i class="bi bi-check2-all text-success"></i>Đã chuyển Kế toán</span>`;
        }

        // Cập nhật nút trong cột Thao tác
        const actionBtn = document.getElementById(`btn-action-handover-${planId}`);
        if (actionBtn) {
            actionBtn.outerHTML = `<span class="badge bg-success-subtle text-success border border-success py-1 px-2" title="Đã chuyển Kế toán"><i class="bi bi-check2-all me-1"></i>Đã chuyển KT</span>`;
        }
    })
    .catch(err => {
        if (typeof showToast === 'function') {
            showToast(err.message, true);
        } else {
            console.error('Lỗi chuyển kế hoạch sang Kế toán:', err);
        }
        if (btn) {
            btn.disabled = false;
            btn.innerHTML = '<i class="bi bi-send-fill text-primary"></i>Chuyển Kế toán';
        }
    });
}
