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

    let planModalInstance = null;
    let stageCounter = 0;
    let itemCounter = 0;

    document.addEventListener('DOMContentLoaded', function() {
        const planEl = document.getElementById('addTreatmentPlanModal');
        if (planEl && typeof bootstrap !== 'undefined' && bootstrap.Modal) {
            planModalInstance = bootstrap.Modal.getOrCreateInstance(planEl);
        }
    });

    const PLAN_PRESETS = {
        'IMPLANT': {
            name: 'Phác đồ Cấy ghép Implant đơn lẻ & Phục hình mão sứ',
            durationDays: 90,
            totalCost: 20000000,
            notes: 'Chụp CT ConeBeam 3D khảo sát xương hàm. Dặn dò vệ sinh kỹ lưỡng, tránh hút thuốc lá trong suốt thời gian tích hợp xương.',
            stages: [
                {
                    name: 'Giai đoạn 1: Khám, Chụp phim & Phẫu thuật cấy ghép trụ Implant',
                    days: 0,
                    desc: 'Khảo sát mật độ xương, phẫu thuật đặt trụ Implant NeoBiotech vào xương hàm',
                    items: [
                        { customName: 'Chụp X-quang CT ConeBeam 3D', tooth: '16', qty: 1, notes: 'Khảo sát thể tích xương hàm' },
                        { customName: 'Cấy ghép trụ Implant NeoBiotech', tooth: '16', qty: 1, notes: 'Đặt trụ chuẩn giải phẫu' }
                    ]
                },
                {
                    name: 'Giai đoạn 2: Tái khám, cắt chỉ & Kiểm tra tích hợp xương',
                    days: 10,
                    desc: 'Sau 7-10 ngày cắt chỉ, kiểm tra lành thương nướu và độ ổn định ban đầu',
                    items: [
                        { customName: 'Khám lại & Cắt chỉ vết mổ', tooth: '16', qty: 1, notes: 'Đánh giá lành thương nướu' }
                    ]
                },
                {
                    name: 'Giai đoạn 3: Đặt trụ lành thương (Healing Abutment) & Lấy dấu phục hình',
                    days: 60,
                    desc: 'Sau 2-3 tháng khi xương tích hợp vững chắc, bộc lộ trụ và lấy dấu kỹ thuật số',
                    items: [
                        { customName: 'Đặt trụ lành thương Healing Abutment', tooth: '16', qty: 1, notes: 'Định hình viền nướu thẩm mỹ' },
                        { customName: 'Lấy dấu cao su / Scan mẫu hàm phục hình', tooth: '16', qty: 1, notes: 'Gửi Labo chế tác mão sứ' }
                    ]
                },
                {
                    name: 'Giai đoạn 4: Gắn Abutment chính thức, lắp mão sứ & Chỉnh khớp cắn',
                    days: 75,
                    desc: 'Lắp mão toàn sứ Zirconia lên Abutment, kiểm tra tiếp xúc bên và khớp cắn chức năng',
                    items: [
                        { customName: 'Lắp Abutment & Mão răng sứ Zirconia bắt vít/gắn xi măng', tooth: '16', qty: 1, notes: 'Lực siết 30Ncm, chụp X-quang kiểm tra khít sát' }
                    ]
                }
            ]
        },
        'ORTHODONTIC': {
            name: 'Phác đồ Niềng răng chỉnh nha cố định (Mắc cài kim loại tiêu chuẩn)',
            durationDays: 540,
            totalCost: 35000000,
            notes: 'Theo dõi dịch chuyển răng định kỳ 3-4 tuần/lần. Hướng dẫn chải răng bằng bàn chải kẽ và chỉ nha khoa chuyên dụng.',
            stages: [
                {
                    name: 'Giai đoạn 1: Thu thập dữ liệu chẩn đoán, Chụp Cephalo & Lấy dấu mẫu hàm',
                    days: 0,
                    desc: 'Chụp ảnh trong/ngoài mặt, phim Panorama + Cephalo, phân tích chỉ số Steiner/Tweed',
                    items: [
                        { customName: 'Chụp phim Panorama + Cephalo đo sọ', tooth: 'Toàn hàm', qty: 1, notes: 'Phân tích góc xương và trục răng' },
                        { customName: 'Lấy mẫu thạch cao & Scan khớp cắn', tooth: 'Hai hàm', qty: 1, notes: 'Lưu hồ sơ ban đầu' }
                    ]
                },
                {
                    name: 'Giai đoạn 2: Điều trị tiền chỉnh nha (Lấy cao răng, Trám răng sâu & Nhổ răng tiền cối)',
                    days: 7,
                    desc: 'Làm sạch môi trường miệng, nhổ răng tạo khoảng theo kế hoạch điều trị (R14, 24, 34, 44)',
                    items: [
                        { customName: 'Lấy cao răng & Đánh bóng hai hàm', tooth: 'Toàn hàm', qty: 1, notes: 'Chuẩn bị nướu khỏe mạnh' },
                        { customName: 'Nhổ răng số 4 tạo khoảng chỉnh nha', tooth: '14, 24, 34, 44', qty: 4, notes: 'Nhổ răng cối nhỏ theo chỉ định' }
                    ]
                },
                {
                    name: 'Giai đoạn 3: Gắn mắc cài hai hàm & Đi dây cung làm đều cung răng (Leveling)',
                    days: 21,
                    desc: 'Gắn mắc cài kim loại Self-Ligating, đi dây NiTi 0.014 kích hoạt làm đều răng',
                    items: [
                        { customName: 'Gắn mắc cài kim loại hai hàm', tooth: 'Toàn hàm', qty: 1, notes: 'Vị trí mắc cài chuẩn trục FA' },
                        { customName: 'Đi dây cung NiTi 0.014 làm đều', tooth: 'Hai hàm', qty: 1, notes: 'Lực nhẹ liên tục' }
                    ]
                },
                {
                    name: 'Giai đoạn 4: Tái khám định kỳ, thay dây cung đóng khoảng & Chỉnh khớp cắn chi tiết',
                    days: 180,
                    desc: 'Đi dây SS 0.019x0.025 kéo đóng khoảng bằng Miniscrew / Lò xo kéo NiTi',
                    items: [
                        { customName: 'Kéo đóng khoảng bằng Miniscrew neo chặn', tooth: '16-13, 26-23', qty: 2, notes: 'Đóng khoảng nhổ răng' }
                    ]
                },
                {
                    name: 'Giai đoạn 5: Tháo mắc cài, Gắn dây cung duy trì mặt trong & Giao máng Hawley/Essix',
                    days: 540,
                    desc: 'Tháo khí cụ, làm sạch keo thừa, lấy dấu làm máng duy trì chống tái phát',
                    items: [
                        { customName: 'Tháo mắc cài & Đánh bóng làm sạch bề mặt men răng', tooth: 'Toàn hàm', qty: 1, notes: 'Bảo tồn men răng' },
                        { customName: 'Gắn cung duy trì cố định mặt trong & Giao máng duy trì Essix', tooth: 'Hai hàm', qty: 1, notes: 'Đeo tối thiểu 12-24 tháng' }
                    ]
                }
            ]
        },
        'ZIRCONIA_CROWN': {
            name: 'Phác đồ Bọc mão sứ / Cầu răng toàn sứ Zirconia CAD/CAM',
            durationDays: 7,
            totalCost: 12500000,
            notes: 'Mài cùi bảo tồn mô răng thật tối đa. Kiểm tra tủy răng sống kỹ lưỡng trước và sau khi gắn phục hình.',
            stages: [
                {
                    name: 'Giai đoạn 1: Sửa soạn cùi răng trụ, Lấy dấu cao su chính xác & Gắn răng tạm',
                    days: 0,
                    desc: 'Gây tê mài cùi răng trụ chuẩn xác, lấy dấu cao su bơm gửi xưởng Labo và gắn răng tạm',
                    items: [
                        { customName: 'Mài cùi răng & Lấy dấu cao su Labo', tooth: '14, 16', qty: 2, notes: 'Đường hoàn tất bờ vai' },
                        { customName: 'Làm răng tạm bảo vệ tủy', tooth: '14, 15, 16', qty: 3, notes: 'Bảo vệ cùi trong thời gian chờ Labo' }
                    ]
                },
                {
                    name: 'Giai đoạn 2: Thử sườn, gắn cầu răng sứ Zirconia & Chỉnh khớp cắn',
                    days: 5,
                    desc: 'Thử độ khít sát viền phục hình, màu sắc thẩm mỹ và gắn vĩnh viễn bằng xi măng Resin',
                    items: [
                        { customName: 'Cầu răng sứ Zirconia toàn sứ', tooth: '14, 15, 16', qty: 3, notes: 'Độ chịu lực 1200 MPa, thẩm mỹ tự nhiên' }
                    ]
                }
            ]
        },
        'ENDODONTIC': {
            name: 'Phác đồ Điều trị tủy răng (Nội nha) & Trám phục hồi thẩm mỹ',
            durationDays: 5,
            totalCost: 2050000,
            notes: 'Chụp phim X-quang kiểm tra độ khít sát bít ống tủy. Khuyên bệnh nhân bọc mão sứ sau điều trị tủy để bảo vệ thân răng không bị vỡ.',
            stages: [
                {
                    name: 'Giai đoạn 1: Mở tủy, tạo hình hệ thống ống tủy & Đặt thuốc diệt khuẩn',
                    days: 0,
                    desc: 'Gây tê, cô lập đê cao su, mở tủy, đo chiều dài làm việc bằng máy định vị chóp và đặt Ca(OH)2',
                    items: [
                        { customName: 'Điều trị tủy răng hàm nhiều chân (Lần 1)', tooth: '46', qty: 1, notes: 'Tạo hình bằng trâm quay NiTi' }
                    ]
                },
                {
                    name: 'Giai đoạn 2: Bơm rửa siêu âm, bít ống tủy vĩnh viễn & Trám Composite',
                    days: 4,
                    desc: 'Kiểm tra hết triệu chứng đau nhức, bít kín hệ thống ống tủy bằng Gutta-Percha và tái tạo thân răng',
                    items: [
                        { customName: 'Bít ống tủy vĩnh viễn & Trám tái tạo thân răng', tooth: '46', qty: 1, notes: 'Trám bít Gutta-Percha lèn ngang' },
                        { customName: 'Trám răng Composite thẩm mỹ xoang sâu', tooth: '46', qty: 1, notes: 'Đèn quang trùng hợp' }
                    ]
                }
            ]
        },
        'WISDOM_TOOTH': {
            name: 'Phác đồ Phẫu thuật nhổ răng khôn mọc lệch / Ngầm bằng máy Piezotome',
            durationDays: 7,
            totalCost: 2500000,
            notes: 'Kê đơn thuốc kháng sinh, giảm đau và chống phù nề. Chườm đá 24h đầu, chườm ấm từ ngày thứ 2.',
            stages: [
                {
                    name: 'Giai đoạn 1: Chụp phim Panorama, Phẫu thuật mở xương chia thân răng bằng Piezotome',
                    days: 0,
                    desc: 'Gây tê thần kinh gai Spix, rạch vạt hình bao, cắt thân chia chân răng nhẹ nhàng, khâu đóng',
                    items: [
                        { customName: 'Chụp Panorama kiểm tra vị trí ống thần kinh răng dưới', tooth: '38, 48', qty: 1, notes: 'Đánh giá độ khó và liên quan dây thần kinh V3' },
                        { customName: 'Phẫu thuật nhổ răng khôn mọc lệch hàm dưới bằng máy siêu âm Piezotome', tooth: '38', qty: 1, notes: 'Chia thân răng nhẹ nhàng, ít sang chấn' }
                    ]
                },
                {
                    name: 'Giai đoạn 2: Tái khám kiểm tra huyệt ổ răng & Cắt chỉ',
                    days: 7,
                    desc: 'Kiểm tra lành thương huyệt ổ răng, bơm rửa nước muối sinh lý và cắt chỉ khâu',
                    items: [
                        { customName: 'Tái khám & Cắt chỉ khâu miệng', tooth: '38', qty: 1, notes: 'Lành thương tốt, không viêm huyệt ổ răng' }
                    ]
                }
            ]
        },
        'PERIODONTITIS': {
            name: 'Phác đồ Điều trị viêm nha chu toàn diện & Nạo túi lợi sâu',
            durationDays: 14,
            totalCost: 3200000,
            notes: 'Hướng dẫn chải răng phương pháp Bass cải tiến. Dùng nước súc miệng Chlorhexidine 0.12% trong 10 ngày.',
            stages: [
                {
                    name: 'Giai đoạn 1: Lấy cao răng siêu âm trên/dưới nướu & Bơm rửa sát khuẩn',
                    days: 0,
                    desc: 'Làm sạch mảng bám vôi răng, loại bỏ yếu tố kích thích nướu',
                    items: [
                        { customName: 'Lấy cao răng & Đánh bóng hai hàm', tooth: 'Toàn hàm', qty: 1, notes: 'Đầu rung siêu âm êm ái' }
                    ]
                },
                {
                    name: 'Giai đoạn 2: Nạo túi lợi sâu (Root Planing) & Bơm thuốc kháng sinh tại chỗ',
                    days: 7,
                    desc: 'Gây tê từng phần, dùng cây nạo nha chu Gracey làm nhẵn bề mặt chân răng dưới nướu',
                    items: [
                        { customName: 'Làm nhẵn chân răng & Nạo túi lợi 1/4 hàm', tooth: 'Hàm trên & Hàm dưới', qty: 4, notes: 'Dùng cây Gracey chuyên dụng' }
                    ]
                }
            ]
        }
    };

    let selectedPlanServicesForTotal = [];

    function populatePlanServicePicker() {
        const picker = document.getElementById('planServicePricePicker');
        if (!picker) return;
        let html = '<option value="">-- Chọn dịch vụ nha khoa để lấy giá tổng --</option>';
        if (typeof availableServicesCatalog !== 'undefined' && availableServicesCatalog && availableServicesCatalog.length > 0) {
            availableServicesCatalog.forEach(s => {
                const p = parseFloat(s.basePrice) || 0;
                html += `<option value="${s.serviceId}" data-price="${p}" data-name="${s.serviceName}">${s.serviceName} — ${formatVnd(p)}</option>`;
            });
        }
        picker.innerHTML = html;
    }

    function onSelectPlanServiceForTotal(selectEl) {
        if (!selectEl || !selectEl.value) return;
        const opt = selectEl.options[selectEl.selectedIndex];
        const price = parseFloat(opt.getAttribute('data-price')) || 0;
        const qty = parseFloat(document.getElementById('planServicePriceQty')?.value) || 1;
        // If no services added to badge list yet, immediately set overall cost to this service's total price
        if (selectedPlanServicesForTotal.length === 0 && price > 0) {
            const overallCostInput = document.getElementById('planOverallCostInput');
            if (overallCostInput) {
                overallCostInput.value = Math.round(price * qty);
                onPlanOverallCostChange();
            }
        }
    }

    function recalcSelectedServicesTotal() {
        const picker = document.getElementById('planServicePricePicker');
        if (selectedPlanServicesForTotal.length === 0 && picker && picker.value) {
            onSelectPlanServiceForTotal(picker);
        }
    }

    function addSelectedServiceToPlanTotal() {
        const picker = document.getElementById('planServicePricePicker');
        if (!picker || !picker.value) {
            showToast('Vui lòng chọn một dịch vụ trong danh sách!', true);
            return;
        }
        const opt = picker.options[picker.selectedIndex];
        const serviceId = picker.value;
        const serviceName = opt.getAttribute('data-name') || opt.textContent;
        const price = parseFloat(opt.getAttribute('data-price')) || 0;
        const qty = parseFloat(document.getElementById('planServicePriceQty')?.value) || 1;

        selectedPlanServicesForTotal.push({
            serviceId,
            serviceName,
            price,
            qty
        });
        renderSelectedPlanServicesBadges();
    }

    function removeSelectedServiceFromPlanTotal(idx) {
        selectedPlanServicesForTotal.splice(idx, 1);
        renderSelectedPlanServicesBadges();
    }

    function renderSelectedPlanServicesBadges() {
        const container = document.getElementById('planSelectedServicesList');
        if (!container) return;
        if (selectedPlanServicesForTotal.length === 0) {
            container.innerHTML = '';
            return;
        }
        let sum = 0;
        let html = '';
        selectedPlanServicesForTotal.forEach((item, idx) => {
            const sub = item.price * item.qty;
            sum += sub;
            html += `
                <span class="badge bg-white text-dark border border-primary-subtle px-2.5 py-1.5 rounded-pill d-inline-flex align-items-center gap-1.5 shadow-xs">
                    <i class="bi bi-check-circle-fill text-primary"></i>
                    <span>${item.serviceName} (x${item.qty}): <strong class="text-primary">${formatVnd(sub)}</strong></span>
                    <button type="button" class="btn-close" style="font-size: 9px;" onclick="removeSelectedServiceFromPlanTotal(${idx})"></button>
                </span>
            `;
        });
        container.innerHTML = html;
        const overallCostInput = document.getElementById('planOverallCostInput');
        if (overallCostInput) {
            overallCostInput.value = Math.round(sum);
            onPlanOverallCostChange();
        }
    }

    function syncPriceFromVisitPerformedServices() {
        const psRows = document.querySelectorAll('#performedServicesTableBody tr[id^="ps-row-"]');
        if (!psRows || psRows.length === 0) {
            showToast('Chưa có dịch vụ thực hiện nào trong ca khám để đồng bộ giá!', true);
            return;
        }
        selectedPlanServicesForTotal = [];
        psRows.forEach(r => {
            const nameEl = r.querySelector('.fw-bold.text-dark');
            const name = nameEl ? nameEl.textContent.trim() : 'Dịch vụ ca khám';
            // Find price from availableServicesCatalog
            let price = 0;
            let serviceId = '';
            if (typeof availableServicesCatalog !== 'undefined' && availableServicesCatalog) {
                const found = availableServicesCatalog.find(s => s.serviceName.toLowerCase() === name.toLowerCase());
                if (found) {
                    price = parseFloat(found.basePrice) || 0;
                    serviceId = found.serviceId;
                }
            }
            const tds = r.querySelectorAll('td');
            const qty = tds.length > 4 ? (parseFloat(tds[4].textContent.trim()) || 1) : 1;
            if (price > 0) {
                selectedPlanServicesForTotal.push({ serviceId, serviceName: name, price, qty });
            }
        });
        renderSelectedPlanServicesBadges();
        showToast('Đã đồng bộ giá tổng từ các dịch vụ trong ca khám!');
    }

    // =========================================================================
    // LIÊN KẾT CHẨN ĐOÁN CA KHÁM (FT14) VỚI KẾ HOẠCH ĐIỀU TRỊ (FT15)
    // =========================================================================
    function getVisitDiagnosesFromDom() {
        const rows = document.querySelectorAll('#diagnosisTableBody tr[id^="diag-row-"]');
        const list = [];
        rows.forEach(r => {
            const diagId = r.id.replace('diag-row-', '');
            const teethAttr = r.getAttribute('data-teeth') || '';
            const firstTd = r.querySelector('td:nth-child(1)')?.textContent.trim() || 'Toàn hàm';
            const icdCode = r.getAttribute('data-icd') || r.querySelector('.df-list-code')?.textContent.trim() || '';
            const diagText = r.getAttribute('data-text') || r.querySelector('td:nth-child(3) .fw-bold')?.textContent.trim() || '';
            const isMain = r.querySelector('.badge-main') !== null || r.getAttribute('data-type') === 'MAIN';
            const cleanTeeth = teethAttr || (firstTd.startsWith('R-') ? firstTd.substring(2) : (firstTd !== 'Toàn hàm' ? firstTd : ''));
            list.push({
                diagId,
                icdCode,
                diagText,
                toothDisplay: firstTd,
                cleanTeeth,
                isMain
            });
        });
        return list;
    }

    function renderModalLinkedDiagnoses(preselectedDiagId = null) {
        const container = document.getElementById('modalLinkedDiagnosesContainer');
        if (!container) return;
        const diags = getVisitDiagnosesFromDom();
        if (diags.length === 0) {
            container.innerHTML = `
                <div class="small text-muted fst-italic py-1">
                    <i class="bi bi-info-circle me-1"></i>Ca khám hiện chưa ghi nhận chẩn đoán nào ở bảng Chẩn đoán (FT14). Bác sĩ có thể nhập trực tiếp bên dưới.
                </div>
            `;
            return;
        }

        let html = '';
        diags.forEach((d, idx) => {
            const shouldSelect = preselectedDiagId
                ? (String(d.diagId) === String(preselectedDiagId))
                : (d.isMain || idx === 0);
            const typeBadge = d.isMain
                ? '<span class="badge bg-danger-subtle text-danger border border-danger-subtle" style="font-size:10px;">★ Chính</span>'
                : '<span class="badge bg-warning-subtle text-dark border border-warning-subtle" style="font-size:10px;">⊕ Kèm theo</span>';

            html += `
                <label class="d-inline-flex align-items-center gap-2 px-3 py-2 rounded-3 border bg-white shadow-xs diag-link-chip"
                       style="cursor: pointer; font-size: 14px; transition: all 0.15s; border-width: 1.5px !important;"
                       data-diag-id="${d.diagId}"
                       data-icd="${d.icdCode}"
                       data-text="${d.diagText}"
                       data-teeth="${d.cleanTeeth}"
                       data-tooth-display="${d.toothDisplay}">
                    <input type="checkbox" class="form-check-input m-0 diag-link-checkbox"
                           style="width: 18px; height: 18px; cursor: pointer;"
                           ${shouldSelect ? 'checked' : ''}
                           onchange="onToggleLinkedDiagnosisChip()">
                    ${typeBadge}
                    <span class="badge bg-primary text-white px-2 py-1 rounded-2 fw-bold"><i class="bi bi-bullseye me-1"></i>${d.toothDisplay}</span>
                    <span class="badge bg-light text-primary border font-monospace px-2 py-1" style="font-size: 13px;">${d.icdCode}</span>
                    <strong class="text-dark">${d.diagText}</strong>
                </label>
            `;
        });
        container.innerHTML = html;
        onToggleLinkedDiagnosisChip(false);
    }

    function onToggleLinkedDiagnosisChip(showFeedback = false) {
        const chips = document.querySelectorAll('#modalLinkedDiagnosesContainer .diag-link-chip');
        const selectedParts = [];
        const teethParts = [];

        chips.forEach(chip => {
            const cb = chip.querySelector('.diag-link-checkbox');
            if (cb && cb.checked) {
                chip.style.borderColor = '#0284c7';
                chip.style.backgroundColor = '#e0f2fe';
                const icd = chip.getAttribute('data-icd') || '';
                const text = chip.getAttribute('data-text') || '';
                const toothDisp = chip.getAttribute('data-tooth-display') || '';
                const cleanTeeth = chip.getAttribute('data-teeth') || '';
                selectedParts.push(`[${icd}] ${text} (${toothDisp})`);
                if (cleanTeeth && !teethParts.includes(cleanTeeth)) {
                    teethParts.push(cleanTeeth);
                }
            } else {
                chip.style.borderColor = '#dee2e6';
                chip.style.backgroundColor = '#ffffff';
            }
        });

        const diagInput = document.getElementById('planDiagnosisInput');
        if (diagInput && selectedParts.length > 0) {
            diagInput.value = selectedParts.join(' ; ');
        }

        const teethInput = document.getElementById('planLinkedTeethInput');
        if (teethInput && teethParts.length > 0) {
            teethInput.value = teethParts.join(', ');
            syncPlanTeethToEmptyItems(teethInput.value);
        }
    }

    function selectAllVisitDiagnosesForPlan() {
        document.querySelectorAll('#modalLinkedDiagnosesContainer .diag-link-checkbox').forEach(cb => {
            cb.checked = true;
        });
        onToggleLinkedDiagnosisChip(true);
        showToast('Đã liên kết toàn bộ chẩn đoán trong ca khám vào kế hoạch!');
    }

    function syncPlanTeethToEmptyItems(teethVal) {
        if (!teethVal) return;
        document.querySelectorAll('#planStagesContainer .item-tooth').forEach(inp => {
            if (!inp.value || inp.value.trim() === '') {
                inp.value = teethVal;
            }
        });
    }

    function autoSuggestPresetFromDiagnosis() {
        const diagVal = (document.getElementById('planDiagnosisInput')?.value || '').toLowerCase();
        const teethVal = document.getElementById('planLinkedTeethInput')?.value || '';
        if (!diagVal) {
            showToast('Vui lòng chọn ít nhất 1 chẩn đoán để hệ thống gợi ý phác đồ!', true);
            return;
        }
        let suggestedKey = 'ENDODONTIC';
        if (diagVal.includes('k01') || diagVal.includes('răng khôn') || diagVal.includes('mọc kẹt') || diagVal.includes('mọc ngầm')) {
            suggestedKey = 'WISDOM_TOOTH';
        } else if (diagVal.includes('k00') || diagVal.includes('vô răng') || diagVal.includes('mất răng')) {
            suggestedKey = 'IMPLANT';
        } else if (diagVal.includes('k05') || diagVal.includes('nha chu') || diagVal.includes('viêm lợi') || diagVal.includes('cao răng')) {
            suggestedKey = 'PERIODONTITIS';
        } else if (diagVal.includes('k07') || diagVal.includes('lệch') || diagVal.includes('khớp cắn') || diagVal.includes('chỉnh nha')) {
            suggestedKey = 'ORTHODONTIC';
        } else if (diagVal.includes('k02') || diagVal.includes('k04') || diagVal.includes('sâu') || diagVal.includes('tủy')) {
            suggestedKey = 'ENDODONTIC';
        }
        applyPlanPreset(suggestedKey);
        if (teethVal) {
            document.querySelectorAll('#planStagesContainer .item-tooth').forEach(inp => {
                inp.value = teethVal;
            });
        }
    }

    function openPlanModalFromDiagnoses() {
        if (typeof switchMainTab === 'function') {
            switchMainTab('tab-treatment-plan');
        }
        openAddPlanModal(null, true);
    }

    function openPlanModalForSingleDiagRow(trEl) {
        if (!trEl) return;
        const diagId = trEl.id ? trEl.id.replace('diag-row-', '') : null;
        if (typeof switchMainTab === 'function') {
            switchMainTab('tab-treatment-plan');
        }
        openAddPlanModal(diagId, false);
        setTimeout(() => {
            autoSuggestPresetFromDiagnosis();
        }, 120);
    }

    function openAddPlanModal(preselectedDiagId = null, selectAllDiags = false) {
        const pathParts = window.location.pathname.split('/');
        const examIdx = pathParts.indexOf('examination');
        const visitId = examIdx !== -1 && pathParts[examIdx + 1] ? pathParts[examIdx + 1] : '';
        let url = visitId ? `/doctor/examination/${visitId}/treatment-plans/create` : '/doctor/treatment-plans/create';
        if (preselectedDiagId) {
            url += `?diagId=${preselectedDiagId}`;
        }
        window.location.href = url;
    }

    function addPlanStage(stageName = '', daysFromStart = 0, stageDesc = '', insertAfterStageId = null) {
        stageCounter++;
        const stageId = stageCounter;
        const container = document.getElementById('planStagesContainer');

        const card = document.createElement('div');
        card.className = 'df-plan-stage-card';
        card.id = `plan-stage-card-${stageId}`;
        card.setAttribute('data-stage-id', stageId);

        card.innerHTML = `
            <div class="df-plan-stage-card-header">
                <div class="d-flex align-items-center gap-2.5 flex-grow-1" style="min-width: 0;">
                    <span class="badge bg-dark rounded-pill fw-bold stage-order-badge px-3 py-2" style="font-size: 13.5px; flex-shrink: 0;">Giai đoạn ${stageId}</span>
                    <input type="text" class="df-plan-stage-title-input stage-name-input"
                           placeholder="Nhập tên giai đoạn (VD: Khám & Cấy ghép trụ Implant)..."
                           value="${stageName || `Giai đoạn ${stageId}`}">
                </td>
                <div class="d-flex align-items-center gap-2" style="flex-shrink: 0;">
                    <div class="input-group" style="width: 245px;">
                        <span class="input-group-text bg-white text-secondary fw-semibold" style="font-size: 13px;">Cách bắt đầu:</span>
                        <input type="number" class="form-control text-center fw-bold text-primary stage-days-input"
                               style="font-size: 15px; height: 40px;"
                               value="${daysFromStart}" min="0" placeholder="0">
                        <span class="input-group-text bg-white text-secondary fw-semibold" style="font-size: 13px;">ngày</span>
                    </td>
                    <button type="button" class="btn btn-outline-danger px-2.5 py-1.5 rounded-3 ms-1" onclick="removePlanStage(${stageId})" title="Xóa giai đoạn này">
                        <i class="bi bi-trash3-fill"></i>
                    </button>
                </td>
            </td>
            <div class="p-3.5" style="padding: 18px 20px;">
                <div class="mb-3">
                    <input type="text" class="form-control stage-desc-input"
                           style="height: 42px; font-size: 14px; background: #f8fafc;"
                           placeholder="Mô tả mục tiêu lâm sàng của giai đoạn này (VD: Làm sạch môi trường miệng, nhổ răng tạo khoảng)..."
                           value="${stageDesc}">
                </td>

                <div class="table-responsive rounded-3 border mb-3">
                    <table class="df-plan-items-table">
                        <thead>
                            <tr>
                                <th style="width: 44%;">Tên bước thực hiện / Thủ thuật chuyên môn</th>
                                <th style="width: 175px;" class="text-center">Vị trí răng</th>
                                <th style="width: 95px;" class="text-center">Số lượng</th>
                                <th style="width: 30%;">Ghi chú kỹ thuật / Hướng xử trí</th>
                                <th style="width: 65px;" class="text-center">Xóa</th>
                            </tr>
                        </thead>
                        <tbody class="stage-items-tbody" id="stage-tbody-${stageId}">
                        </tbody>
                    </table>
                </td>

                <div class="d-flex align-items-center justify-content-between flex-wrap gap-2 pt-1">
                    <button type="button" class="df-btn-add-item" onclick="addPlanItem(${stageId})">
                        <i class="bi bi-plus-circle-fill"></i>
                        <span>+ Thêm bước / thủ thuật vào giai đoạn này</span>
                    </button>
                    <button type="button" class="btn btn-primary fw-bold px-3.5 py-2 rounded-3 d-inline-flex align-items-center gap-2 shadow-xs"
                            style="background: #0284c7; border: none; font-size: 13.5px;"
                            onclick="insertPlanStageAfter(${stageId})"
                            title="Tạo thêm một giai đoạn mới chèn ngay bên dưới giai đoạn này">
                        <i class="bi bi-node-plus-fill fs-6"></i>
                        <span>+ Tạo giai đoạn tiếp theo (Ngay dưới giai đoạn này)</span>
                    </button>
                </td>
            </td>
        `;

        if (insertAfterStageId) {
            const refCard = document.getElementById(`plan-stage-card-${insertAfterStageId}`);
            if (refCard && refCard.nextSibling) {
                container.insertBefore(card, refCard.nextSibling);
            } else {
                container.appendChild(card);
            }
        } else {
            container.appendChild(card);
        }

        renumberPlanStages();
        recalculatePlanTotal();
        return stageId;
    }

    function insertPlanStageAfter(currentStageId) {
        const refCard = document.getElementById(`plan-stage-card-${currentStageId}`);
        let prevDays = 0;
        if (refCard) {
            prevDays = parseInt(refCard.querySelector('.stage-days-input')?.value) || 0;
        }
        const nextDays = prevDays + 7;
        const newStageId = addPlanStage('', nextDays, '', currentStageId);
        const linkedTeeth = document.getElementById('planLinkedTeethInput')?.value || '';
        addPlanItem(newStageId, '', '', linkedTeeth, 1, '');
        const newCard = document.getElementById(`plan-stage-card-${newStageId}`);
        if (newCard) {
            newCard.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            const titleInput = newCard.querySelector('.stage-name-input');
            if (titleInput) titleInput.focus();
        }
    }

    function renumberPlanStages() {
        const cards = document.querySelectorAll('#planStagesContainer .df-plan-stage-card');
        cards.forEach((card, index) => {
            const order = index + 1;
            const badge = card.querySelector('.stage-order-badge');
            if (badge) {
                badge.textContent = `Giai đoạn ${order}`;
            }
            const nameInput = card.querySelector('.stage-name-input');
            if (nameInput && /^Giai đoạn \d+$/.test(nameInput.value.trim())) {
                nameInput.value = `Giai đoạn ${order}`;
            }
        });
    }

    function removePlanStage(stageId) {
        const card = document.getElementById(`plan-stage-card-${stageId}`);
        if (card) {
            card.remove();
            renumberPlanStages();
            recalculatePlanTotal();
        }
    }

    function addPlanItem(stageId, serviceId = '', customName = '', tooth = '', qty = 1, notes = '') {
        itemCounter++;
        const itemId = itemCounter;
        const tbody = document.getElementById(`stage-tbody-${stageId}`);
        if (!tbody) return;

        if (!tooth) {
            tooth = document.getElementById('planLinkedTeethInput')?.value || '';
        }

        let matchedPrice = 0;
        let optHtml = '<option value="">-- Chọn dịch vụ trong danh mục hoặc tự gõ bên dưới --</option>';
        if (typeof availableServicesCatalog !== 'undefined' && availableServicesCatalog && availableServicesCatalog.length > 0) {
            availableServicesCatalog.forEach(s => {
                const isSel = (serviceId && String(s.serviceId) === String(serviceId)) || (!serviceId && customName && s.serviceName.toLowerCase() === customName.toLowerCase());
                if (isSel) {
                    matchedPrice = parseFloat(s.basePrice) || 0;
                }
                const priceHint = s.basePrice ? ` (${formatVnd(s.basePrice)})` : '';
                optHtml += `<option value="${s.serviceId}" data-price="${s.basePrice || 0}" data-name="${s.serviceName}" ${isSel ? 'selected' : ''}>${s.serviceName}${priceHint}</option>`;
            });
        }

        const numQty = parseFloat(qty) || 1;

        const tr = document.createElement('tr');
        tr.id = `plan-item-row-${itemId}`;
        tr.setAttribute('data-catalog-price', matchedPrice);
        tr.innerHTML = `
            <td style="padding: 12px 14px;">
                <span class="badge bg-light text-primary border fw-bold" style="font-size: 12px; letter-spacing: 0.3px;">${plan.planCode}</span>
            </td>
            <td style="padding: 12px 14px;">
                <div class="fw-bold text-dark" style="font-size: 13.5px; line-height: 1.45;">${plan.planName}</div>
            </td>
            <td style="padding: 12px 14px;">
                <div class="small text-dark fw-semibold" style="white-space: nowrap;">
                    <i class="bi bi-calendar-event me-1 text-primary"></i>
                    <span>${startStr}</span>
                    <span class="text-muted"> &rarr; </span>
                    <span>${endStr}</span>
                </div>
            </td>
            <td style="padding: 12px 14px;">
                <div class="d-flex align-items-center justify-content-between mb-1 small">
                    <span class="fw-semibold text-secondary" id="plan-stages-text-${plan.planId}">${plan.completedStages}/${plan.totalStages} giai Ä‘oáº¡n</span>
                    <strong class="text-primary" id="plan-progress-percent-${plan.planId}">${plan.progressPercent}%</strong>
                </div>
                <div class="progress" style="height: 6px;">
                    <div class="progress-bar bg-success" role="progressbar"
                         id="plan-progress-bar-${plan.planId}"
                         style="width: ${plan.progressPercent}%;"
                         aria-valuenow="${plan.progressPercent}" aria-valuemin="0" aria-valuemax="100">
                    </div>
                </div>
            </td>
            <td style="padding: 12px 14px;" class="text-end">
                <strong class="text-primary fs-6 d-block">
                    ${formatVnd(plan.finalEstimatedCost)}
                </strong>
                <span class="text-muted" style="font-size: 11px;">
                    <i class="bi bi-send-check text-primary me-0.5"></i>Chuyá»ƒn Káº¿ toÃ¡n
                </span>
            </td>
            <td style="padding: 12px 14px;" class="text-center" id="plan-status-cell-${plan.planId}">
                <div class="dropdown d-inline-block">
                    <button class="btn btn-sm dropdown-toggle rounded-pill px-2.5 py-1 fw-bold border shadow-xs d-inline-flex align-items-center gap-1 ${plan.statusBadgeClass}"
                            type="button" data-bs-toggle="dropdown" aria-expanded="false"
                            title="Nháº¥n Ä‘á»ƒ Ä‘á»•i tráº¡ng thÃ¡i phÃ¡c Ä‘á»“">
                        <span>${plan.statusDisplayName}</span>
                    </button>
                    <ul class="dropdown-menu dropdown-menu-end shadow-sm border py-1 rounded-3" style="font-size: 13px; min-width: 175px;">
                        <li><h6 class="dropdown-header text-uppercase text-muted" style="font-size: 10px;">Cáº­p nháº­t tráº¡ng thÃ¡i</h6></li>
                        <li><a class="dropdown-item py-1.5 d-flex align-items-center gap-2" href="javascript:void(0)" onclick="updatePlanStatusDirectly(${plan.planId}, 'ACCEPTED')"><span class="badge bg-info-subtle text-info border">BN Ä‘Ã£ Ä‘á»“ng Ã½</span></a></li>
                        <li><a class="dropdown-item py-1.5 d-flex align-items-center gap-2" href="javascript:void(0)" onclick="updatePlanStatusDirectly(${plan.planId}, 'IN_PROGRESS')"><span class="badge bg-primary-subtle text-primary border">Äang Ä‘iá»u trá»‹</span></a></li>
                        <li><a class="dropdown-item py-1.5 d-flex align-items-center gap-2" href="javascript:void(0)" onclick="updatePlanStatusDirectly(${plan.planId}, 'COMPLETED')"><span class="badge bg-success-subtle text-success border">HoÃ n thÃ nh phÃ¡c Ä‘á»“</span></a></li>
                        <li><hr class="dropdown-divider my-1"></li>
                        <li><a class="dropdown-item py-1.5 d-flex align-items-center gap-2 text-danger" href="javascript:void(0)" onclick="updatePlanStatusDirectly(${plan.planId}, 'CANCELLED')"><span class="badge bg-danger-subtle text-danger border">Há»§y káº¿ hoáº¡ch</span></a></li>
                    </ul>
                </div>
            </td>
            <td style="padding: 12px 14px;" class="text-center">
                <div class="d-flex align-items-center justify-content-center gap-1.5">
                    <button type="button" class="df-btn-roadmap"
                            title="Xem &amp; cáº­p nháº­t lá»™ trÃ¬nh cÃ¡c bÆ°á»›c lÃ¢m sÃ ng (SCR-15.3)"
                            onclick="togglePlanInlineRoadmap(${plan.planId})">
                        <i class="bi bi-diagram-3-fill"></i>
                        <span>Lá»™ trÃ¬nh</span>
                        <i class="bi bi-chevron-down" style="font-size: 11px;" id="chevron-plan-${plan.planId}"></i>
                    </button>
                    <button type="button" class="df-btn-plan-delete"
                            title="XÃ³a káº¿ hoáº¡ch nÃ y"
                            onclick="deleteTreatmentPlan(${plan.planId})">
                        <i class="bi bi-trash3-fill"></i>
                    </button>
                </div>
            </td>
        `;

        tbody.appendChild(tr);
        recalculatePlanTotal();
    }

    function removePlanItem(btn) {
        const tr = btn.closest('tr');
        if (tr) tr.remove();
        recalculatePlanTotal(true);
    }

    function onPlanItemServiceChange(selectEl, itemId) {
        const tr = document.getElementById(`plan-item-row-${itemId}`);
        if (!tr) return;

        const opt = selectEl.options[selectEl.selectedIndex];
        if (opt && opt.value) {
            const name = opt.getAttribute('data-name');
            const price = parseFloat(opt.getAttribute('data-price')) || 0;

            const nameInput = tr.querySelector('.item-custom-name');
            if (nameInput) nameInput.value = name || '';

            tr.setAttribute('data-catalog-price', price);
            recalculatePlanTotal(true);
        } else {
            tr.setAttribute('data-catalog-price', '0');
            recalculatePlanTotal(true);
        }
    }

    function onPlanOverallCostChange() {
        const overallCostInput = document.getElementById('planOverallCostInput');
        const cost = overallCostInput ? (parseFloat(overallCostInput.value) || 0) : 0;
        const totalDisplay = document.getElementById('planTotalEstimatedCostDisplay');
        if (totalDisplay) {
            totalDisplay.innerText = formatVnd(cost);
        }
    }

    function recalculatePlanTotal(syncFromCatalogItems = false) {
        const stageCards = document.querySelectorAll('#planStagesContainer .df-plan-stage-card');
        const itemRows = document.querySelectorAll('#planStagesContainer .stage-items-tbody tr');

        let sumCatalogCost = 0;
        itemRows.forEach(r => {
            const qty = parseFloat(r.querySelector('.item-qty')?.value) || 1;
            const catPrice = parseFloat(r.getAttribute('data-catalog-price')) || 0;
            sumCatalogCost += (qty * catPrice);
        });

        const summaryText = document.getElementById('planStageSummaryText');
        if (summaryText) {
            summaryText.innerText = `${stageCards.length} giai đoạn • ${itemRows.length} bước thực hiện`;
        }

        // Only overwrite overallCostInput from step catalog services if no package/service badges were explicitly picked
        if (syncFromCatalogItems && selectedPlanServicesForTotal.length === 0 && sumCatalogCost > 0) {
            const overallCostInput = document.getElementById('planOverallCostInput');
            if (overallCostInput) {
                overallCostInput.value = Math.round(sumCatalogCost);
            }
        }
        onPlanOverallCostChange();
    }

    function applyPlanPreset(presetKey) {
        if (!presetKey) return;
        const preset = PLAN_PRESETS[presetKey];
        if (!preset) return;

        const selectEl = document.getElementById('planPresetSelect');
        if (selectEl && selectEl.value !== presetKey) {
            selectEl.value = presetKey;
        }

        document.getElementById('planNameInput').value = preset.name;
        document.getElementById('planNotesInput').value = preset.notes;

        const startDateInput = document.getElementById('planStartDateInput');
        let baseDate = new Date();
        if (startDateInput && startDateInput.value) {
            baseDate = new Date(startDateInput.value);
        }
        const endDate = new Date(baseDate);
        endDate.setDate(endDate.getDate() + (preset.durationDays || 30));
        const ey = endDate.getFullYear();
        const em = String(endDate.getMonth() + 1).padStart(2, '0');
        const ed = String(endDate.getDate()).padStart(2, '0');
        document.getElementById('planEndDateInput').value = `${ey}-${em}-${ed}`;

        const container = document.getElementById('planStagesContainer');
        container.innerHTML = '';
        stageCounter = 0;
        itemCounter = 0;

        const linkedTeeth = document.getElementById('planLinkedTeethInput')?.value || '';

        preset.stages.forEach(st => {
            const stageId = addPlanStage(st.name, st.days, st.desc);
            st.items.forEach(it => {
                let matchedServiceId = '';
                if (typeof availableServicesCatalog !== 'undefined' && availableServicesCatalog) {
                    const found = availableServicesCatalog.find(s => s.serviceName.toLowerCase().includes(it.customName.toLowerCase()) || it.customName.toLowerCase().includes(s.serviceName.toLowerCase()));
                    if (found) {
                        matchedServiceId = found.serviceId;
                    }
                }
                const toothToUse = linkedTeeth || it.tooth || '';
                addPlanItem(stageId, matchedServiceId, it.customName, toothToUse, it.qty, it.notes);
            });
        });

        if (preset.totalCost) {
            const overallCostInput = document.getElementById('planOverallCostInput');
            if (overallCostInput) {
                overallCostInput.value = preset.totalCost;
                onPlanOverallCostChange();
            }
        }

        showToast(`Đã áp dụng mẫu phác đồ chuẩn: ${preset.name}`);
    }

    function submitTreatmentPlan(e) {
        e.preventDefault();

        const patientId = document.getElementById('planPatientId').value;
        const branchId = document.getElementById('planBranchId').value;
        const doctorId = document.getElementById('planDoctorSelect').value;
        const planName = document.getElementById('planNameInput').value.trim();
        const diagnosisText = document.getElementById('planDiagnosisInput').value.trim();
        const status = document.getElementById('planStatusSelect').value;
        const startDate = document.getElementById('planStartDateInput').value;
        const expectedEndDate = document.getElementById('planEndDateInput').value;
        const notes = document.getElementById('planNotesInput').value.trim();

        const stageCards = document.querySelectorAll('#planStagesContainer .df-plan-stage-card');
        if (stageCards.length === 0) {
            showToast('Kế hoạch điều trị phải có ít nhất 1 giai đoạn!', true);
            return;
        }

        const stages = [];
        let calculatedItemsSum = 0;

        stageCards.forEach((c, sIdx) => {
            const stageName = c.querySelector('.stage-name-input').value.trim() || `Giai đoạn ${sIdx + 1}`;
            const days = parseInt(c.querySelector('.stage-days-input').value) || 0;
            const desc = c.querySelector('.stage-desc-input').value.trim();

            const itemRows = c.querySelectorAll('.stage-items-tbody tr');
            const items = [];

            itemRows.forEach(r => {
                const sel = r.querySelector('.item-service-select');
                const customName = r.querySelector('.item-custom-name').value.trim();
                const tooth = r.querySelector('.item-tooth').value.trim();
                const qty = parseFloat(r.querySelector('.item-qty').value) || 1;
                const catPrice = parseFloat(r.getAttribute('data-catalog-price')) || 0;
                const itemNotes = r.querySelector('.item-notes').value.trim();

                if (customName || (sel && sel.value)) {
                    calculatedItemsSum += (qty * catPrice);
                    items.push({
                        serviceId: sel && sel.value ? parseInt(sel.value) : null,
                        customServiceName: customName,
                        toothNumbers: tooth || null,
                        quantity: qty,
                        estimatedPrice: catPrice,
                        status: 'PLANNED',
                        notes: itemNotes || null
                    });
                }
            });

            stages.push({
                stageOrder: sIdx + 1,
                stageName: stageName,
                estimatedDaysFromStart: days,
                description: desc || null,
                status: 'PENDING',
                items: items
            });
        });

        const manualGrandTotal = parseFloat(document.getElementById('planOverallCostInput')?.value) || 0;
        const grandTotal = manualGrandTotal > 0 ? manualGrandTotal : calculatedItemsSum;

        const payload = {
            patientId: patientId ? parseInt(patientId) : null,
            branchId: branchId ? parseInt(branchId) : 1,
            doctorId: doctorId ? parseInt(doctorId) : null,
            planName: planName,
            diagnosisText: diagnosisText,
            totalEstimatedCost: grandTotal,
            finalEstimatedCost: grandTotal,
            status: status,
            startDate: startDate || null,
            expectedEndDate: expectedEndDate || null,
            notes: notes || null,
            stages: stages
        };

        const submitBtn = document.getElementById('submitPlanBtn');
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span> Đang lưu kế hoạch...';

        fetch('/api/doctor/treatment-plans', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        })
        .then(res => {
            if (!res.ok) {
                return res.json().then(err => { throw new Error(err.message || 'Lỗi khi lưu kế hoạch điều trị'); });
            }
            return res.json();
        })
        .then(result => {
            showToast('Đã tạo kế hoạch điều trị & đồng bộ giá tổng sang Kế toán thành công!');
            addPlanRowToTable(result.data);
            if (planModalInstance) {
                planModalInstance.hide();
            }
        })
        .catch(err => {
            showToast(err.message, true);
        })
        .finally(() => {
            submitBtn.disabled = false;
            submitBtn.innerHTML = '<i class="bi bi-check-circle-fill me-1"></i> Lưu kế hoạch điều trị';
        });
    }

    function addPlanRowToTable(plan) {
        const emptyRow = document.getElementById('emptyPlanRow');
        if (emptyRow) emptyRow.remove();

        const tbody = document.getElementById('treatmentPlansTableBody');
        const tr = document.createElement('tr');
        tr.id = `plan-row-${plan.planId}`;
        tr.className = 'df-plan-tr-hover';

        const diagDisplay = plan.diagnosisText
            ? `<div class="mb-1"><span class="badge bg-primary-subtle text-primary border border-primary-subtle fw-semibold" style="font-size: 11px;"><i class="bi bi-clipboard2-pulse-fill me-1"></i>Chẩn đoán: ${plan.diagnosisText}</span></td>`
            : '';
        const notesDisplay = plan.notes ? `<div class="small text-muted"><i class="bi bi-info-circle me-1"></i>${plan.notes}</div>` : '';
        const startStr = plan.startDate || '---';
        const endStr = plan.expectedEndDate || '---';

        tr.innerHTML = `
            <td style="padding: 12px 14px;">
                <span class="badge bg-light text-primary border fw-bold" style="font-size: 12px; letter-spacing: 0.3px;">${plan.planCode}</span>
            </td>
            <td style="padding: 12px 14px;">
                <div class="fw-bold text-dark" style="font-size: 13.5px; line-height: 1.45;">${plan.planName}</div>
            </td>
            <td style="padding: 12px 14px;">
                <div class="small text-dark fw-semibold" style="white-space: nowrap;">
                    <i class="bi bi-calendar-event me-1 text-primary"></i>
                    <span>${startStr}</span>
                    <span class="text-muted"> &rarr; </span>
                    <span>${endStr}</span>
                </div>
            </td>
            <td style="padding: 12px 14px;">
                <div class="d-flex align-items-center justify-content-between mb-1 small">
                    <span class="fw-semibold text-secondary" id="plan-stages-text-${plan.planId}">${plan.completedStages}/${plan.totalStages} giai Ä‘oáº¡n</span>
                    <strong class="text-primary" id="plan-progress-percent-${plan.planId}">${plan.progressPercent}%</strong>
                </div>
                <div class="progress" style="height: 6px;">
                    <div class="progress-bar bg-success" role="progressbar"
                         id="plan-progress-bar-${plan.planId}"
                         style="width: ${plan.progressPercent}%;"
                         aria-valuenow="${plan.progressPercent}" aria-valuemin="0" aria-valuemax="100">
                    </div>
                </div>
            </td>
            <td style="padding: 12px 14px;" class="text-end">
                <strong class="text-primary fs-6 d-block">
                    ${formatVnd(plan.finalEstimatedCost)}
                </strong>
                <span class="text-muted" style="font-size: 11px;">
                    <i class="bi bi-send-check text-primary me-0.5"></i>Chuyá»ƒn Káº¿ toÃ¡n
                </span>
            </td>
            <td style="padding: 12px 14px;" class="text-center" id="plan-status-cell-${plan.planId}">
                <div class="dropdown d-inline-block">
                    <button class="btn btn-sm dropdown-toggle rounded-pill px-2.5 py-1 fw-bold border shadow-xs d-inline-flex align-items-center gap-1 ${plan.statusBadgeClass}"
                            type="button" data-bs-toggle="dropdown" aria-expanded="false"
                            title="Nháº¥n Ä‘á»ƒ Ä‘á»•i tráº¡ng thÃ¡i phÃ¡c Ä‘á»“">
                        <span>${plan.statusDisplayName}</span>
                    </button>
                    <ul class="dropdown-menu dropdown-menu-end shadow-sm border py-1 rounded-3" style="font-size: 13px; min-width: 175px;">
                        <li><h6 class="dropdown-header text-uppercase text-muted" style="font-size: 10px;">Cáº­p nháº­t tráº¡ng thÃ¡i</h6></li>
                        <li><a class="dropdown-item py-1.5 d-flex align-items-center gap-2" href="javascript:void(0)" onclick="updatePlanStatusDirectly(${plan.planId}, 'ACCEPTED')"><span class="badge bg-info-subtle text-info border">BN Ä‘Ã£ Ä‘á»“ng Ã½</span></a></li>
                        <li><a class="dropdown-item py-1.5 d-flex align-items-center gap-2" href="javascript:void(0)" onclick="updatePlanStatusDirectly(${plan.planId}, 'IN_PROGRESS')"><span class="badge bg-primary-subtle text-primary border">Äang Ä‘iá»u trá»‹</span></a></li>
                        <li><a class="dropdown-item py-1.5 d-flex align-items-center gap-2" href="javascript:void(0)" onclick="updatePlanStatusDirectly(${plan.planId}, 'COMPLETED')"><span class="badge bg-success-subtle text-success border">HoÃ n thÃ nh phÃ¡c Ä‘á»“</span></a></li>
                        <li><hr class="dropdown-divider my-1"></li>
                        <li><a class="dropdown-item py-1.5 d-flex align-items-center gap-2 text-danger" href="javascript:void(0)" onclick="updatePlanStatusDirectly(${plan.planId}, 'CANCELLED')"><span class="badge bg-danger-subtle text-danger border">Há»§y káº¿ hoáº¡ch</span></a></li>
                    </ul>
                </div>
            </td>
            <td style="padding: 12px 14px;" class="text-center">
                <div class="d-flex align-items-center justify-content-center gap-1.5">
                    <button type="button" class="df-btn-roadmap"
                            title="Xem &amp; cáº­p nháº­t lá»™ trÃ¬nh cÃ¡c bÆ°á»›c lÃ¢m sÃ ng (SCR-15.3)"
                            onclick="togglePlanInlineRoadmap(${plan.planId})">
                        <i class="bi bi-diagram-3-fill"></i>
                        <span>Lá»™ trÃ¬nh</span>
                        <i class="bi bi-chevron-down" style="font-size: 11px;" id="chevron-plan-${plan.planId}"></i>
                    </button>
                    <button type="button" class="df-btn-plan-delete"
                            title="XÃ³a káº¿ hoáº¡ch nÃ y"
                            onclick="deleteTreatmentPlan(${plan.planId})">
                        <i class="bi bi-trash3-fill"></i>
                    </button>
                </div>
            </td>
        `;

        const roadmapDiv = document.createElement('div');
        roadmapDiv.id = `plan-roadmap-row-${plan.planId}`;
        roadmapDiv.className = 'df-plan-roadmap-row border rounded-3 shadow-xs mb-3';
        roadmapDiv.style.cssText = 'display: none; width: 100%; max-width: 100%; background: #f8fafc; overflow: hidden;';
        roadmapDiv.innerHTML = renderPlanInlineRoadmapHtml(plan);

        const standaloneArea = document.getElementById('planRoadmapStandaloneArea');
        if (standaloneArea) {
            standaloneArea.prepend(roadmapDiv);
        }
        tbody.prepend(tr);
        updatePlanCountBadge();
    }

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
                                </td>
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
                                </td>
                            `;
                        }

                        itemsRows += `
                            <tr id="item-row-${it.planItemId}">
                                <td class="text-center text-muted">${idx + 1}</td>
                                <td>
                                    <strong class="text-dark">${it.serviceName}</strong>
                                    ${it.categoryName ? `<span class="badge bg-light text-muted border ms-1" style="font-size: 10px;">${it.categoryName}</span>` : ''}
                                </div>
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
                const stageStatusText = st.status === 'COMPLETED' ? 'Hoàn thành giai đoạn' : (st.status === 'IN_PROGRESS' ? 'Đang thực hiện' : 'Chưa thực hiện');

                stagesHtml += `
                    <div class="${cardClass}" id="stage-card-${st.stageId}">
                        <div class="d-flex flex-wrap align-items-center justify-content-between gap-3 pb-2.5 mb-2.5 border-bottom">
                            <div class="d-flex align-items-center gap-2">
                                <span class="badge bg-dark px-2.5 py-1.5 rounded-pill fw-bold">Giai đoạn ${st.stageOrder}</span>
                                <strong class="text-dark" style="font-size: 15px;">${st.stageName}</strong>
                            </td>
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
                            </td>
                        </td>
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
                        </td>
                    </td>
                `;
            });
        } else {
            stagesHtml = '<div class="text-center text-muted py-3">Phác đồ này chưa có giai đoạn nào.</div>';
        }

        return `
            <div class="p-0 border-0">
                <div class="df-inline-roadmap-container p-3 p-md-4">
                    <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3 pb-2 border-bottom">
                        <div class="d-flex align-items-center gap-2">
                            <span class="badge bg-primary text-white px-2.5 py-1 rounded-pill fw-bold">
                                <i class="bi bi-diagram-3-fill me-1"></i>LỘ TRÌNH CHI TIẾT TỪNG BƯỚC
                            </span>
                            <strong class="text-dark fs-6">${plan.planName}</strong>
                        </td>
                        <div class="d-flex flex-wrap align-items-center gap-2 small">
                            <span class="badge bg-white text-dark border px-2.5 py-1.5 rounded-pill shadow-xs">
                                <i class="bi bi-calendar-check text-primary me-1"></i>
                                <span>Dự kiến xong: </span>
                                <strong>${plan.expectedEndDate || '---'}</strong>
                            </span>
                            <span class="badge bg-light text-primary border px-2.5 py-1.5 rounded-pill fw-bold" id="plan-progress-badge-${plan.planId}">
                                ${plan.completedStages}/${plan.totalStages} giai đoạn (${plan.progressPercent}%)
                            </span>
                        </td>
                    </td>
                    <div id="stages-container-${plan.planId}">
                        ${stagesHtml}
                    </div>
                </td>
            </td>
        `;
    }

    function togglePlanInlineRoadmap(planId) {
        const roadmapRow = document.getElementById(`plan-roadmap-row-${planId}`);
        const chevron = document.getElementById(`chevron-plan-${planId}`);
        if (!roadmapRow) return;

        const isHidden = window.getComputedStyle(roadmapRow).display === 'none';
        if (isHidden) {
            // Ẩn các lộ trình khác để tập trung vào phác đồ đang chọn
            document.querySelectorAll('.df-plan-roadmap-row').forEach(el => el.style.display = 'none');
            document.querySelectorAll('[id^="chevron-plan-"]').forEach(el => { el.classList.remove('bi-chevron-up'); el.classList.add('bi-chevron-down'); });

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

    function updatePlanInlineView(plan) {
        if (!plan || !plan.planId) return;

        // 1. Cập nhật chi tiết bảng lộ trình
        const roadmapRow = document.getElementById(`plan-roadmap-row-${plan.planId}`);
        if (roadmapRow) {
            roadmapRow.innerHTML = renderPlanInlineRoadmapHtml(plan);
        }

        // 2. Cập nhật thanh tiến độ và thông tin ở dòng cha
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

            // Cập nhật nút dropdown trạng thái
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
            // 1. Cập nhật KHDT lộ trình
            if (result.plan) {
                updatePlanInlineView(result.plan);
            }
            // 2. Thêm dòng mới vào bảng Dịch vụ thực hiện FT16 bên dưới
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
        const summaryBadge = document.getElementById('treatmentSummaryBadge');
        if (summaryBadge) {
            const diagCount = document.querySelectorAll('#diagnosisTableBody tr[id^="diag-row-"]').length;
            summaryBadge.innerText = `${diagCount} chẩn đoán`;
        }
    }