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

        // 1. Kiểm tra prefix dạng [CĐ #1], [CĐ 1], [CĐ#1]...
        const cdMatch = cleanStageName.match(/^\[(CĐ\s*#?\d+)\]\s*(.*)$/i);
        if (cdMatch) {
            courseKey = cdMatch[1].toUpperCase().replace(/\s+/g, ' '); // Ví dụ: "CĐ #1"
            cleanStageName = cdMatch[2].trim();

            if (st.description && /chẩn đoán:/i.test(st.description)) {
                const parts = st.description.split(/chẩn đoán:\s*/i);
                if (parts[1]) {
                    courseTitle = `[${courseKey}] ${parts[1].trim()}`;
                }
            }
            if (!courseTitle) {
                courseTitle = `[${courseKey}] Quá trình điều trị`;
            }
        } else if (st.description && /^điều trị chẩn đoán:\s*/i.test(st.description.trim())) {
            const diagPart = st.description.trim().replace(/^điều trị chẩn đoán:\s*/i, '');
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
function renderPlanInlineRoadmapHtml(plan) {
    if (!plan) return '';

    const courses = groupStagesIntoCourses(plan.stages || [], plan);
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
                let cardClass = 'df-inline-stage-card mb-3';
                if (st.status === 'COMPLETED') cardClass += ' is-completed';
                else if (st.status === 'IN_PROGRESS') cardClass += ' is-in-progress';
                else cardClass += ' is-pending';

                const totalItemsInStage = (st.items && st.items.length) || 0;
                const doneItemsInStage = (st.items && st.items.filter(i => i.status === 'COMPLETED').length) || 0;

                let completedBadgeHtml = '';
                if (st.status === 'COMPLETED' || (totalItemsInStage > 0 && doneItemsInStage === totalItemsInStage)) {
                    completedBadgeHtml = `
                        <span class="badge px-2.5 py-1.5 rounded-pill bg-success-subtle text-success border border-success" id="stage-completed-badge-${st.stageId}">
                            <i class="bi bi-check2-circle me-1"></i><span>${st.completedAt ? 'Hoàn thành: ' + st.completedAt : 'Đã hoàn thành'}</span>
                        </span>
                    `;
                } else if (doneItemsInStage > 0 || st.status === 'IN_PROGRESS') {
                    completedBadgeHtml = `
                        <span class="badge px-2.5 py-1.5 rounded-pill bg-primary-subtle text-primary border border-primary" id="stage-completed-badge-${st.stageId}">
                            <i class="bi bi-arrow-repeat me-1"></i><span>Tiến độ: ${doneItemsInStage}/${totalItemsInStage} bước</span>
                        </span>
                    `;
                } else {
                    completedBadgeHtml = `
                        <span class="badge px-2.5 py-1.5 rounded-pill bg-light text-secondary border" id="stage-completed-badge-${st.stageId}">
                            <i class="bi bi-clock-history me-1"></i><span>Chưa thực hiện</span>
                        </span>
                    `;
                }

                const expectedEnd = st.expectedEndDate || plan.expectedEndDate || '---';

                let itemsRows = '';
                if (st.items && st.items.length > 0) {
                    st.items.forEach((it, idx) => {
                        let toothBadge = '<span class="text-muted">-</span>';
                        if (it.toothNumbers) {
                            const tn = String(it.toothNumbers).trim();
                            if (tn.toLowerCase().includes('toàn hàm') || tn.toLowerCase().includes('toan ham')) {
                                toothBadge = `<span class="badge bg-info-subtle text-info border">Toàn hàm</span>`;
                            } else {
                                const cleanNum = tn.startsWith('R-') || tn.startsWith('R') ? tn : `R-${tn}`;
                                toothBadge = `<span class="badge bg-primary-subtle text-primary border">${cleanNum}</span>`;
                            }
                        }
                        const completedItemHtml = it.completedAt
                            ? `<span class="badge bg-success-subtle text-success border"><i class="bi bi-check-circle-fill me-1"></i>${it.completedAt}</span>`
                            : '<span class="text-muted">---</span>';

                        const isComp = it.status === 'COMPLETED';

                        let statusBadgeHtml = '';
                        let actionBtnHtml = '';

                        if (isComp) {
                            statusBadgeHtml = `
                                <span class="badge bg-success-subtle text-success border border-success-subtle px-2.5 py-1.5 rounded-pill fw-bold d-inline-flex align-items-center gap-1 shadow-xs" style="font-size: 12px;">
                                    <i class="bi bi-check2-circle"></i>
                                    <span>Đã thực hiện</span>
                                </span>
                            `;
                            actionBtnHtml = `
                                <button type="button" class="btn btn-sm btn-outline-secondary rounded-pill px-2.5 py-1 fw-semibold shadow-xs d-inline-flex align-items-center gap-1.5"
                                        style="font-size: 12px;"
                                        title="Hoàn tác lại bước này (Chuyển về Chưa làm)"
                                        onclick="revertPlanItemStatus(${it.planItemId})">
                                    <i class="bi bi-arrow-counterclockwise text-primary fw-bold" style="font-size: 13px;"></i>
                                    <span>Hoàn tác</span>
                                </button>
                            `;
                        } else {
                            statusBadgeHtml = `
                                <span class="badge bg-light text-secondary border px-2.5 py-1.5 rounded-pill fw-semibold d-inline-flex align-items-center gap-1 shadow-xs" style="font-size: 12px;">
                                    <i class="bi bi-clock"></i>
                                    <span>Chưa làm</span>
                                </span>
                            `;
                            actionBtnHtml = `
                                <button type="button" class="btn btn-sm btn-primary rounded-pill px-3 py-1 fw-bold shadow-xs d-inline-flex align-items-center gap-1.5"
                                        style="font-size: 12px; background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%); border: none;"
                                        title="Thực hiện thủ thuật này trong ca khám hôm nay (Tự động đưa vào Dịch vụ thực hiện FT16)"
                                        onclick="executePlanItemDirectly(${it.planItemId})">
                                    <i class="bi bi-lightning-charge-fill text-warning"></i>
                                    <span>Làm trong ca này</span>
                                </button>
                            `;
                        }

                        itemsRows += `
                            <tr id="item-row-${it.planItemId}">
                                <td class="text-center text-muted">${idx + 1}</td>
                                <td>
                                    <strong class="text-dark">${it.stepName || it.serviceName}</strong>
                                    ${(it.stepName && it.stepName !== it.serviceName) ? `<span class="badge bg-light text-primary border ms-1" style="font-size: 10px;">${it.serviceName}</span>` : (it.categoryName ? `<span class="badge bg-light text-muted border ms-1" style="font-size: 10px;">${it.categoryName}</span>` : '')}
                                </td>
                                <td class="text-center">${toothBadge}</td>
                                <td class="text-center">
                                    <span class="fw-bold text-dark">${(it.quantity != null && Number(it.quantity) % 1 === 0) ? Number(it.quantity) : (it.quantity || 1)}</span>
                                    <span class="badge bg-light text-secondary border ms-1" style="font-size: 11px;">${(it.unit && it.unit.trim()) ? it.unit.trim() : 'răng'}</span>
                                </td>
                                <td class="text-center small" id="item-completed-cell-${it.planItemId}">${completedItemHtml}</td>
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
                const stageStatusText = st.status === 'COMPLETED' ? 'Hoàn thành giai đoạn' : (st.status === 'IN_PROGRESS' ? 'Đang điều trị' : 'Chưa thực hiện');

                stagesInsideCourseHtml += `
                    <div class="${cardClass}" id="stage-card-${st.stageId}">
                        <div class="d-flex flex-wrap align-items-center justify-content-between gap-3 pb-2.5 mb-2.5 border-bottom">
                            <div class="d-flex align-items-center gap-2">
                                <span class="badge bg-dark px-2.5 py-1.5 rounded-pill fw-bold" style="font-size: 11.5px;">Giai đoạn ${st.internalOrder}</span>
                                <strong class="text-dark" style="font-size: 14.5px;">${st.cleanStageName}</strong>
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
                        ${st.description ? `<div class="small text-secondary mb-2 fst-italic"><i class="bi bi-info-circle me-1"></i>${st.description}</div>` : ''}
                        <div class="table-responsive bg-white rounded-3 border">
                            <table class="table table-sm table-hover align-middle mb-0" style="font-size: 13px;">
                                <thead class="table-light">
                                    <tr>
                                        <th style="width: 45px;" class="text-center">#</th>
                                        <th>Tên bước thực hiện / Quy trình thủ thuật</th>
                                        <th style="width: 110px;" class="text-center">Vị trí răng</th>
                                        <th style="width: 95px;" class="text-center">Số lượng</th>
                                        <th style="width: 165px;" class="text-center">Ngày làm (now)</th>
                                        <th style="width: 130px;" class="text-center">Trạng thái</th>
                                        <th style="width: 140px;" class="text-center">Thao tác</th>
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
                <div class="card border rounded-3 mb-3 shadow-xs df-course-card" id="${course.courseId}-wrapper" style="border-color: #cbd5e1; overflow: hidden;">
                    <div class="df-course-header p-3 d-flex flex-wrap align-items-center justify-content-between gap-2"
                         style="cursor: pointer; background: #f8fafc; border-bottom: 1px solid #e2e8f0; user-select: none;"
                         onclick="toggleCourseAccordion('${course.courseId}')"
                         title="Nhấn để gập / mở quá trình điều trị này">
                        
                        <div class="d-flex align-items-center gap-2.5 flex-grow-1">
                            <span class="badge rounded-pill px-2.5 py-1 text-white fw-bold shadow-xs"
                                  style="background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%); font-size: 11.5px; letter-spacing: 0.3px;">
                                QUÁ TRÌNH ${course.courseIndex}
                            </span>
                            <div class="d-flex flex-column">
                                <span class="text-dark fw-bold fs-6" style="line-height: 1.35;">${course.courseTitle}</span>
                                <span class="text-muted" style="font-size: 12px;">
                                    <i class="bi bi-layers me-1"></i>${course.stages.length} giai đoạn &bull; Đã hoàn thành <strong>${completedStagesInCourse}/${course.stages.length}</strong>
                                </span>
                            </div>
                        </div>

                        <div class="d-flex align-items-center gap-2">
                            <span class="badge ${courseStatusBadgeClass} px-2.5 py-1.5 rounded-pill d-inline-flex align-items-center gap-1" style="font-size: 12px;">
                                <i class="bi ${courseStatusIcon}"></i>
                                <span>${courseStatusText}</span>
                            </span>

                            <button type="button" class="btn btn-sm btn-white rounded-circle border shadow-xs d-flex align-items-center justify-content-center p-0"
                                    style="width: 30px; height: 30px; background: #ffffff;"
                                    aria-label="Gập mở quá trình">
                                <i class="bi ${chevronIconClass} text-secondary df-course-chevron" id="chevron-${course.courseId}"></i>
                            </button>
                        </div>
                    </div>

                    <div class="df-course-body p-3" id="${course.courseId}-body" style="background: #f8fafc; display: ${bodyDisplayStyle};">
                        ${stagesInsideCourseHtml}
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
                        <i class="bi bi-diagram-3-fill me-1"></i>LỘ TRÌNH ĐIỀU TRỊ THEO CÁC QUÁ TRÌNH
                    </span>
                    <strong class="text-dark fs-6">${plan.planName}</strong>
                </div>
                <div class="d-flex flex-wrap align-items-center gap-2 small">
                    <span class="badge bg-white text-dark border px-2.5 py-1.5 rounded-pill shadow-xs">
                        <i class="bi bi-layers text-primary me-1"></i>
                        <span>Tổng cộng: </span>
                        <strong>${courses.length} quá trình</strong>
                    </span>
                    <span class="badge bg-white text-dark border px-2.5 py-1.5 rounded-pill shadow-xs">
                        <i class="bi bi-calendar-check text-primary me-1"></i>
                        <span>Dự kiến xong: </span>
                        <strong>${plan.expectedEndDate || '---'}</strong>
                    </span>
                    <span class="badge bg-light text-primary border px-2.5 py-1.5 rounded-pill fw-bold" id="plan-progress-badge-${plan.planId}">
                        ${plan.totalItems ? `${plan.completedItems}/${plan.totalItems} bước (${plan.progressPercent}%)` : `${plan.completedStages}/${plan.totalStages} giai đoạn (${plan.progressPercent}%)`}
                    </span>
                    <button type="button" class="btn btn-sm btn-outline-secondary rounded-pill px-2.5 py-1 shadow-xs d-inline-flex align-items-center gap-1 ms-1"
                            onclick="toggleAllCoursesInPlan(${plan.planId})"
                            id="btn-toggle-all-${plan.planId}"
                            title="Thu gọn hoặc mở tất cả các quá trình">
                        <i class="bi bi-arrows-collapse" id="icon-toggle-all-${plan.planId}"></i>
                        <span id="text-toggle-all-${plan.planId}">Thu gọn tất cả</span>
                    </button>
                </div>
            </div>
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

            // 2. Phân tích courseTitle để lấy CĐ prefix, ICD code và tên chẩn đoán sạch đẹp
            let rawTitle = (course.courseTitle || '').trim();
            let cdPrefix = '';
            let icdCode = '';
            let diagName = rawTitle;

            const cdMatch = diagName.match(/^\[(CĐ\s*#?\d+)\]\s*(.*)$/i);
            if (cdMatch) {
                cdPrefix = cdMatch[1].toUpperCase().replace(/\s+/g, ' ');
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
                tbodyRowsHtml = '<tr><td colspan="6" class="text-center text-muted fst-italic py-3">Chẩn đoán này chưa có bước thực hiện nào.</td></tr>';
            } else {
                let globalItemIdx = 0;
                course.stages.forEach((stage, sIdx) => {
                    const stageItems = stage.items || [];
                    const stageTotalCnt = stageItems.length;
                    const stageDoneCnt = stageItems.filter(i => i.status === 'COMPLETED').length;
                    const stageIsAllDone = stageTotalCnt > 0 && stageDoneCnt === stageTotalCnt;

                    // Nếu có nhiều giai đoạn trong cùng chẩn đoán, hiển thị 1 dòng tiêu đề phân chia giai đoạn nhẹ nhàng
                    if (multipleStages) {
                        tbodyRowsHtml += `
                            <tr class="table-light" style="border-top: 1.5px solid #e2e8f0;">
                                <td colspan="6" class="py-2 px-3 fw-bold text-dark" style="font-size: 13px;">
                                    <span class="badge bg-dark rounded-pill me-2" style="font-size: 11px;">Giai đoạn ${stage.internalOrder || (sIdx + 1)}</span>
                                    <span>${escapePlanHtml(stage.cleanStageName || stage.stageName)}</span>
                                    <span class="badge rounded-pill ms-2 ${stageIsAllDone ? 'bg-success-subtle text-success border border-success' : (stageDoneCnt > 0 ? 'bg-primary-subtle text-primary border border-primary' : 'bg-light text-secondary border')}">
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
                                toothBadge = '<span class="badge bg-info-subtle text-info border">Toàn hàm</span>';
                            } else {
                                const cleanNum = tn.startsWith('R-') || tn.startsWith('R') ? tn : `R-${tn}`;
                                toothBadge = `<span class="badge bg-primary-subtle text-primary border">${escapePlanHtml(cleanNum)}</span>`;
                            }
                        }

                        const qtyDisplay = (item.quantity != null && Number(item.quantity) % 1 === 0)
                            ? Number(item.quantity)
                            : (item.quantity || 1);

                        const completedAtHtml = (isDone && item.completedAt)
                            ? `<span class="text-success fw-semibold">${escapePlanHtml(item.completedAt)}</span>`
                            : '<span class="text-muted">---</span>';

                        const statusBadgeHtml = isDone
                            ? '<span class="badge bg-success-subtle text-success border border-success-subtle rounded-pill px-2.5 py-1.5 fw-bold"><i class="bi bi-check2-circle me-1"></i>Đã làm</span>'
                            : '<span class="badge bg-light text-secondary border rounded-pill px-2.5 py-1.5 fw-semibold"><i class="bi bi-clock me-1"></i>Chưa làm</span>';

                        tbodyRowsHtml += `
                            <tr class="${isDone ? 'ps-step-done' : 'ps-step-todo'}">
                                <td class="text-center text-muted">${globalItemIdx}</td>
                                <td>
                                    <i class="bi ${isDone ? 'bi-check-circle-fill text-success' : 'bi-circle text-secondary'} me-1"></i>
                                    <strong class="ps-step-name">${escapePlanHtml(item.stepName || item.serviceName)}</strong>
                                    ${(item.serviceName && item.stepName && item.stepName !== item.serviceName) ? `<span class="badge bg-light text-primary border ms-1" style="font-size: 10px;">${escapePlanHtml(item.serviceName)}</span>` : ''}
                                </td>
                                <td class="text-center">${toothBadge}</td>
                                <td class="text-center fw-bold">${qtyDisplay}</td>
                                <td class="text-center small">${completedAtHtml}</td>
                                <td class="text-center">${statusBadgeHtml}</td>
                            </tr>
                        `;
                    });
                });
            }

            // Mỗi chẩn đoán là 1 bảng riêng biệt bên trong Kế hoạch điều trị
            coursesHtml += `
                <div class="border rounded-3 overflow-hidden bg-white shadow-xs">
                    <div class="p-2.5 px-3 bg-light border-bottom d-flex flex-wrap align-items-center justify-content-between gap-2">
                        <div class="d-flex align-items-center gap-2 flex-wrap">
                            ${cdPrefix ? `<span class="badge bg-primary text-white rounded-pill px-2.5 py-1 fw-bold" style="font-size: 11.5px;">${escapePlanHtml(cdPrefix)}</span>` : ''}
                            <strong class="text-dark" style="font-size: 13.5px;">Chẩn đoán: ${escapePlanHtml(diagName)}</strong>
                            ${icdCode ? `<span class="badge bg-warning-subtle text-warning-emphasis border border-warning-subtle rounded-pill px-2 py-0.5"><i class="bi bi-clipboard2-pulse me-1"></i>[${escapePlanHtml(icdCode)}]</span>` : ''}
                        </div>
                        <span class="badge rounded-pill px-2.5 py-1 ${doneSteps > 0 && doneSteps === totalSteps ? 'bg-success-subtle text-success border border-success' : (doneSteps > 0 ? 'bg-primary-subtle text-primary border border-primary' : 'bg-light text-secondary border')}">
                            <i class="bi ${doneSteps > 0 && doneSteps === totalSteps ? 'bi-check2-circle' : 'bi-clock'} me-1"></i>
                            <span>${doneSteps}/${totalSteps} bước đã làm</span>
                        </span>
                    </div>
                    <div class="table-responsive">
                        <table class="table table-sm align-middle mb-0" style="font-size: 13px;">
                            <thead class="table-light">
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

        // Cùng 1 Kế hoạch điều trị thì gom chung vào 1 khối (ps-plan-card), bên trong chứa các bảng chẩn đoán tương ứng
        allDiagnosisCardsHtml += `
            <div class="ps-plan-card">
                <div class="ps-plan-head d-flex flex-wrap align-items-center justify-content-between gap-2">
                    <div class="d-flex align-items-center gap-2 flex-wrap">
                        <span class="badge bg-dark rounded-pill px-2.5 py-1.5">${escapePlanHtml(plan.planCode || 'KHDT')}</span>
                        <strong class="text-dark" style="font-size: 14.5px;">${escapePlanHtml(plan.planName)}</strong>
                    </div>
                    <div class="d-flex align-items-center gap-2">
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

/**
 * Khởi tạo render tất cả các khối lộ trình từ cache window.INITIAL_TREATMENT_PLANS
 */
function initAllPlanRoadmaps() {
    if (window.INITIAL_TREATMENT_PLANS && Array.isArray(window.INITIAL_TREATMENT_PLANS)) {
        window.INITIAL_TREATMENT_PLANS.forEach(plan => {
            const row = document.getElementById(`plan-roadmap-row-${plan.planId}`);
            if (row) {
                row.innerHTML = renderPlanInlineRoadmapHtml(plan);
                row.dataset.rendered = "true";
            }
        });
    }
    renderDiagnosisProgressTables();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initAllPlanRoadmaps);
} else {
    initAllPlanRoadmaps();
}

/**
 * Bật / tắt hiển thị khối lộ trình chi tiết từng bước
 */
function togglePlanInlineRoadmap(planId) {
    const roadmapRow = document.getElementById(`plan-roadmap-row-${planId}`);
    const chevron = document.getElementById(`chevron-plan-${planId}`);
    if (!roadmapRow) return;

    if (!roadmapRow.dataset.rendered || roadmapRow.dataset.rendered !== "true") {
        let planData = (window.INITIAL_TREATMENT_PLANS || []).find(p => String(p.planId) === String(planId));
        if (planData) {
            roadmapRow.innerHTML = renderPlanInlineRoadmapHtml(planData);
            roadmapRow.dataset.rendered = "true";
        } else {
            fetch(`/api/doctor/treatment-plans/${planId}`)
                .then(r => r.json())
                .then(dto => {
                    roadmapRow.innerHTML = renderPlanInlineRoadmapHtml(dto);
                    roadmapRow.dataset.rendered = "true";
                })
                .catch(e => console.error(e));
        }
    }

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

    const roadmapRow = document.getElementById(`plan-roadmap-row-${plan.planId}`);
    if (roadmapRow) {
        roadmapRow.innerHTML = renderPlanInlineRoadmapHtml(plan);
        roadmapRow.dataset.rendered = "true";
    }

    const parentRow = document.getElementById(`plan-row-${plan.planId}`);
    if (parentRow) {
        const progBadge = document.getElementById(`plan-progress-badge-${plan.planId}`);
        if (progBadge) {
            progBadge.innerText = (plan.totalItems && plan.totalItems > 0)
                ? `${plan.completedItems}/${plan.totalItems} bước (${plan.progressPercent}%)`
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
                ? `${plan.completedItems}/${plan.totalItems} bước`
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

    fetch(`/api/doctor/treatment-plans/items/${planItemId}/execute-in-visit?visitId=${visitId}`, {
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
    })
    .catch(err => {
        resetBtn();
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
        // Xóa ngay dòng dịch vụ tương ứng khỏi bảng Dịch vụ thực hiện tại ghế
        document.querySelectorAll(`tr[data-plan-item-id="${planItemId}"]`).forEach(el => el.remove());
        if (typeof recalcPerformedTableTotals === 'function') {
            recalcPerformedTableTotals();
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
        if (typeof window.refreshTpGrid === 'function') {
            window.refreshTpGrid();
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
}

/**
 * Chuyển kế hoạch điều trị sang bộ phận Kế toán xử lý riêng
 */
function sendPlanToAccounting(planId, planCode) {
    const code = planCode || 'KHDT';
    if (!confirm(`Xác nhận chuyển Kế hoạch điều trị [${code}] sang bộ phận Kế toán để tư vấn & thu phí lộ trình?`)) {
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
            showToast(`✓ Đã chuyển kế hoạch điều trị [${code}] sang Kế toán thành công!`);
        } else {
            alert(`✓ Đã chuyển kế hoạch điều trị [${code}] sang Kế toán thành công!`);
        }

        // Cập nhật ô hiển thị chuyển Kế toán ở cột giá dự kiến
        const box = document.getElementById(`plan-accounting-box-${planId}`);
        if (box) {
            box.innerHTML = `<span class="badge bg-success-subtle text-success border border-success d-inline-flex align-items-center gap-1 py-1 px-2" style="font-size: 11px;" title="Đã chuyển Kế toán xử lý"><i class="bi bi-check2-all text-success"></i>Đã chuyển Kế toán</span>`;
        }

        // Cập nhật nút trong cột Thao tác
        const actionBtn = document.getElementById(`btn-action-handover-${planId}`);
        if (actionBtn) {
            actionBtn.outerHTML = `<span class="badge bg-success-subtle text-success border border-success py-1.5 px-2" title="Đã chuyển Kế toán"><i class="bi bi-check2-all me-1"></i>Đã chuyển KT</span>`;
        }
    })
    .catch(err => {
        if (typeof showToast === 'function') {
            showToast(err.message, true);
        } else {
            alert('Lỗi: ' + err.message);
        }
        if (btn) {
            btn.disabled = false;
            btn.innerHTML = '<i class="bi bi-send-fill text-primary"></i>Chuyển Kế toán';
        }
    });
}
