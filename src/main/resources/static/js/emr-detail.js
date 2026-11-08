/**
 * emr-detail.js - Xử lý tương tác giao diện Bệnh án điện tử MS: BA-18
 * Đồng bộ với chuẩn Bộ Y Tế & DentalFlow AMIS UI
 */
(function () {
    'use strict';

    // 1. CHUYỂN ĐỔI GIỮA CÁC TỜ PHIẾU (Bìa, BA-18, Tổng kết, Đính kèm, Xác thực)
    const docButtons = Array.from(document.querySelectorAll('[data-doc-target]'));
    const docPapers = Array.from(document.querySelectorAll('[data-emr-document]'));

    function selectDocument(code, updateUrl) {
        if (!code) return;
        const exists = docPapers.some(p => p.dataset.emrDocument === code);
        const target = exists ? code : (docPapers[0] && docPapers[0].dataset.emrDocument);
        if (!target) return;

        docButtons.forEach(btn => {
            btn.classList.toggle('is-active', btn.dataset.docTarget === target);
        });

        docPapers.forEach(paper => {
            paper.classList.toggle('is-active', paper.dataset.emrDocument === target);
        });

        if (updateUrl && window.history && window.history.replaceState) {
            const url = new URL(window.location.href);
            url.searchParams.set('document', target);
            window.history.replaceState({}, '', url);
            window.scrollTo(0, 0);
        }
    }

    docButtons.forEach(btn => {
        btn.addEventListener('click', () => {
            selectDocument(btn.dataset.docTarget, true);
        });
    });

    const initialDoc = new URLSearchParams(window.location.search).get('document') ||
        (docButtons[0] && docButtons[0].dataset.docTarget);
    selectDocument(initialDoc, false);

    // 2. TÍNH TOÁN & ĐIỀN CÁC Ô VUÔNG KÝ TỰ (Tuổi, CCCD 12 số)
    const ageInput = document.getElementById('dentalAgeInput');
    const ageGroup = document.querySelector('.age-box-group');
    function updateAgeBoxes(val) {
        const ageStr = String(val || '').padStart(2, '0');
        const b1 = document.getElementById('boxAge1');
        const b2 = document.getElementById('boxAge2');
        if (b1) b1.textContent = ageStr.length >= 2 ? ageStr.charAt(ageStr.length - 2) : '';
        if (b2) b2.textContent = ageStr.length >= 1 ? ageStr.charAt(ageStr.length - 1) : '';
    }
    if (ageInput) {
        if (ageInput.value) updateAgeBoxes(ageInput.value);
        ageInput.addEventListener('input', () => updateAgeBoxes(ageInput.value));
    } else if (ageGroup && ageGroup.dataset.dob) {
        try {
            const dobYear = parseInt(ageGroup.dataset.dob.substring(0, 4), 10);
            if (!isNaN(dobYear) && dobYear > 1900) {
                const currentYear = new Date().getFullYear();
                updateAgeBoxes(Math.max(0, currentYear - dobYear));
            }
        } catch (_) { /* Bỏ qua nếu định dạng ngày khác */ }
    }

    const cccdInput = document.getElementById('dentalIdCardInput');
    const cccdGroup = document.querySelector('.cccd-box-group');
    function updateCccdBoxes(val) {
        if (!cccdGroup) return;
        const cccd = String(val || '').trim();
        const boxes = cccdGroup.querySelectorAll('.char-box');
        for (let i = 0; i < boxes.length; i++) {
            boxes[i].textContent = (i < cccd.length) ? cccd.charAt(i) : '';
        }
    }
    if (cccdInput) {
        updateCccdBoxes(cccdInput.value);
        cccdInput.addEventListener('input', () => updateCccdBoxes(cccdInput.value));
    } else if (cccdGroup && cccdGroup.dataset.cccd) {
        updateCccdBoxes(cccdGroup.dataset.cccd);
    }

    // 3. SƠ ĐỒ RĂNG MS: BA-18 CHUẨN BỘ Y TẾ (2 TẦNG CHỮ THẬP)
    // Thu thập danh sách chẩn đoán và thủ thuật can thiệp theo từng số răng
    const toothInterventions = new Map(); // key: toothNumber (e.g. '16'), value: { diagnoses: [], services: [] }

    function getOrCreateTooth(tooth) {
        const clean = String(tooth).trim();
        if (!toothInterventions.has(clean)) {
            toothInterventions.set(clean, { diagnoses: [], services: [] });
        }
        return toothInterventions.get(clean);
    }

    // A. Phân tích chẩn đoán theo răng
    const diagnoses = Array.isArray(window.emrToothDiagnoses) ? window.emrToothDiagnoses : [];
    diagnoses.forEach(d => {
        const rawTeeth = String(d.toothNumbers || '').match(/\b[1-8][1-8]\b/g) || [];
        const diagLabel = (d.diagnosisText || '') + (d.icd10Code ? ' (' + d.icd10Code + ')' : '');
        rawTeeth.forEach(t => {
            const item = getOrCreateTooth(t);
            if (diagLabel && !item.diagnoses.includes(diagLabel)) {
                item.diagnoses.push(diagLabel);
            }
        });
    });

    // B. Phân tích thủ thuật / dịch vụ đã thực hiện theo răng
    const services = Array.isArray(window.emrPerformedServices) ? window.emrPerformedServices : [];
    services.forEach(s => {
        const rawTeeth = String(s.toothNumbers || '').match(/\b[1-8][1-8]\b/g) || [];
        const serviceLabel = s.serviceName || '';
        rawTeeth.forEach(t => {
            const item = getOrCreateTooth(t);
            if (serviceLabel && !item.services.includes(serviceLabel)) {
                item.services.push(serviceLabel);
            }
        });
    });

    // C. Phân tích odontogram nếu có
    try {
        const chart = typeof window.emrOdontogram === 'string'
            ? JSON.parse(window.emrOdontogram) : window.emrOdontogram;
        if (chart && typeof chart === 'object') {
            const entries = Array.isArray(chart) ? chart.map(i => [i.tooth || i.number, i.status]) : Object.entries(chart);
            for (const [tooth, val] of entries) {
                if (/^[1-8][1-8]$/.test(String(tooth))) {
                    const item = getOrCreateTooth(tooth);
                    if (val && typeof val === 'string' && val !== 'HEALTHY' && !item.diagnoses.includes(val)) {
                        item.diagnoses.push(val);
                    }
                }
            }
        }
    } catch (_) {}

    // D. Đánh dấu các ô răng trên cả 2 bảng chữ thập (Vĩnh viễn & Răng sữa)
    const allToothCells = document.querySelectorAll('.tooth-cell[data-tooth]');
    allToothCells.forEach(cell => {
        const tooth = cell.dataset.tooth;
        const data = toothInterventions.get(tooth);
        if (data && (data.diagnoses.length > 0 || data.services.length > 0)) {
            cell.classList.add('has-intervention');

            // Tạo Tooltip hiển thị chi tiết khi hover
            const details = [];
            if (data.diagnoses.length > 0) details.push('Bệnh lý: ' + data.diagnoses.join(', '));
            if (data.services.length > 0) details.push('Thủ thuật: ' + data.services.join(', '));
            const tooltipTitle = 'Răng ' + tooth + ' — ' + details.join(' | ');

            cell.setAttribute('title', tooltipTitle);
            cell.setAttribute('data-bs-toggle', 'tooltip');
            cell.setAttribute('data-bs-placement', 'top');
        }
    });

    // E. Điền dòng/bảng tổng hợp danh sách các răng can thiệp ngay dưới sơ đồ
    const summaryTarget = document.getElementById('toothInterventionSummaryText');
    const summaryInput = document.getElementById('inputToothInterventionSummary');
    const summaryEntries = [];
    const plainEntries = [];
    toothInterventions.forEach((val, tooth) => {
        if (val.diagnoses.length > 0 || val.services.length > 0) {
            const parts = [];
            if (val.services.length > 0) parts.push(val.services.join(', '));
            if (val.diagnoses.length > 0) parts.push(val.diagnoses.join(', '));
            summaryEntries.push('<strong>Răng ' + tooth + '</strong>: ' + parts.join(' (' ) + (parts.length > 1 ? ')' : ''));
            plainEntries.push('Răng ' + tooth + ': ' + parts.join(' (' ) + (parts.length > 1 ? ')' : ''));
        }
    });

    if (summaryTarget) {
        if (summaryEntries.length > 0) {
            summaryTarget.innerHTML = summaryEntries.join(' &nbsp;·&nbsp; ');
        } else {
            summaryTarget.innerHTML = '<span class="text-muted fst-italic">Không ghi nhận can thiệp thủ thuật theo răng trong ca khám này.</span>';
        }
    }

    if (summaryInput && !summaryInput.value.trim() && plainEntries.length > 0) {
        summaryInput.value = plainEntries.join('; ');
    }

    // Xử lý các ô tích chọn linh hoạt (Gender, PatientType, ReferralSource, Paraclinical)
    document.querySelectorAll('.emr-check-toggle[data-gender-choice]').forEach(el => {
        el.addEventListener('click', () => {
            const val = el.dataset.genderChoice;
            const container = el.closest('.moh-info-row') || el.closest('form') || document;
            const hidden = container.querySelector('input[name="gender"]') || document.getElementById('inputDentalGender');
            if (hidden) hidden.value = val;
            const bMale = container.querySelector('.box-gender-male') || document.getElementById('boxGenderMale');
            const bFemale = container.querySelector('.box-gender-female') || document.getElementById('boxGenderFemale');
            if (bMale) bMale.textContent = (val === 'Nam' ? '✓' : '');
            if (bFemale) bFemale.textContent = (val === 'Nữ' ? '✓' : '');
        });
    });

    document.querySelectorAll('.emr-check-toggle[data-type-choice]').forEach(el => {
        el.addEventListener('click', () => {
            const val = el.dataset.typeChoice;
            const hidden = document.getElementById('inputDentalPatientType');
            if (hidden) hidden.value = val;
            const bBhyt = document.getElementById('boxTypeBhyt');
            const bFee = document.getElementById('boxTypeFee');
            const bFree = document.getElementById('boxTypeFree');
            const bOther = document.getElementById('boxTypeOther');
            if (bBhyt) bBhyt.textContent = (val === 'BHYT' ? '✓' : '');
            if (bFee) bFee.textContent = (val === 'FEE' ? '✓' : '');
            if (bFree) bFree.textContent = (val === 'FREE' ? '✓' : '');
            if (bOther) bOther.textContent = (val === 'OTHER' ? '✓' : '');
        });
    });

    document.querySelectorAll('.emr-check-toggle[data-referral-choice]').forEach(el => {
        el.addEventListener('click', () => {
            const val = el.dataset.referralChoice;
            const hidden = document.getElementById('inputDentalReferralSource');
            if (hidden) hidden.value = val;
            const bMed = document.getElementById('boxRefMedical');
            const bSelf = document.getElementById('boxRefSelf');
            if (bMed) bMed.textContent = (val === 'MEDICAL' ? '✓' : '');
            if (bSelf) bSelf.textContent = (val === 'SELF' ? '✓' : '');
        });
    });

    document.querySelectorAll('.emr-check-toggle[data-paraclinical-choice]').forEach(el => {
        el.addEventListener('click', () => {
            const val = el.dataset.paraclinicalChoice;
            const hidden = document.getElementById('inputDentalParaclinicalRequired');
            if (hidden) hidden.value = val;
            const bNone = document.getElementById('boxParaNone');
            const bYes = document.getElementById('boxParaYes');
            if (bNone) bNone.textContent = (val === 'NONE' ? '✓' : '');
            if (bYes) bYes.textContent = (val === 'YES' ? '✓' : '');
        });
    });

    // 4. KÍCH HOẠT BOOTSTRAP TOOLTIPS NẾU CÓ
    if (window.bootstrap && window.bootstrap.Tooltip) {
        document.querySelectorAll('[data-bs-toggle="tooltip"]').forEach(el => {
            new window.bootstrap.Tooltip(el);
        });
    }

    // 5. NÚT IN TÀI LIỆU
    document.querySelectorAll('[data-emr-print]').forEach(btn => {
        btn.addEventListener('click', () => {
            window.print();
        });
    });

    // 6. TOGGLE ACCORDION HỒ SƠ CHÍNH (Item 8)
    window.toggleMainDocsAccordion = function() {
        const content = document.getElementById('contentMainDocs');
        const header = document.getElementById('headingMainDocs');
        if (content) {
            const isHidden = content.style.display === 'none';
            content.style.display = isHidden ? '' : 'none';
            if (header) {
                header.classList.toggle('is-collapsed', !isHidden);
            }
        }
    };

    // 7. XỬ LÝ 3 CHẾ ĐỘ XEM: XEM | CHỈNH SỬA | BẢN IN (Item 9)
    const modeRadios = Array.from(document.querySelectorAll('input[name="emrDisplayMode"]'));
    const canvasRight = document.getElementById('emrCanvasRight');
    const inlineSaveGroup = document.getElementById('emrInlineSaveGroup');
    const btnSubmitInline = document.getElementById('btnSubmitInlineActiveDoc');

    function applyDisplayMode(mode) {
        if (!mode) mode = 'view';

        // Update radio inputs and container styles
        modeRadios.forEach(r => {
            r.checked = (r.value === mode);
            const parentLabel = r.closest('.emr-mode-radio-label');
            if (parentLabel) {
                parentLabel.classList.toggle('is-active', r.value === mode);
            }
        });

        if (canvasRight) {
            canvasRight.classList.toggle('is-edit-mode', mode === 'edit');
            canvasRight.classList.toggle('is-print-mode', mode === 'print');
        }

        const emrPage = document.querySelector('.emr-page') || document.querySelector('.app-content');
        if (emrPage) {
            emrPage.classList.toggle('is-print-mode', mode === 'print');
        }

        document.querySelectorAll('.emr-paper').forEach(paper => {
            paper.classList.toggle('is-print-mode', mode === 'print');
        });

        if (inlineSaveGroup) {
            inlineSaveGroup.classList.toggle('d-none', mode !== 'edit');
        }

        // Bật/tắt thuộc tính readonly cho toàn bộ ô nhập inline tùy theo chế độ
        const inlineFields = canvasRight ? canvasRight.querySelectorAll('.emr-inline-input, .emr-inline-textarea') : [];
        inlineFields.forEach(field => {
            if (mode === 'edit') {
                field.removeAttribute('readonly');
            } else {
                field.setAttribute('readonly', 'readonly');
            }
        });
    }

    modeRadios.forEach(radio => {
        radio.addEventListener('change', () => {
            applyDisplayMode(radio.value);
            // Cập nhật URL parameter mà không reload trang
            if (window.history && window.history.replaceState) {
                const url = new URL(window.location.href);
                url.searchParams.set('mode', radio.value);
                window.history.replaceState({}, '', url);
            }
        });
    });

    // Tự động kích hoạt mode từ URL nếu có (?mode=edit)
    const urlParams = new URLSearchParams(window.location.search);
    const initialMode = urlParams.get('mode') || 'view';
    applyDisplayMode(initialMode);

    // Xử lý nút [💾 Lưu thay đổi] chế độ chỉnh sửa trực tiếp (Inline Save)
    if (btnSubmitInline) {
        btnSubmitInline.addEventListener('click', () => {
            const activePaper = document.querySelector('.emr-paper.is-active');
            if (!activePaper) return;
            const form = activePaper.querySelector('form.emr-doc-form');
            if (form) {
                btnSubmitInline.innerHTML = '<span class="spinner-border spinner-border-sm me-1" role="status" aria-hidden="true"></span> Đang lưu...';
                btnSubmitInline.disabled = true;
                form.submit();
            }
        });
    }

}());
