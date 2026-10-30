/**
 * ============================================================================
 * CV-DCMS ENHANCED TABLE SYSTEM (Thư viện bảng tương tác cao cấp)
 * Tự động kích hoạt trên các bảng có thuộc tính [data-enhanced-table]
 * ============================================================================
 */

(function () {
    'use strict';

    // Lấy thông tin user hiện tại từ context HTML nếu có
    function getCurrentUserId() {
        const bodyUser = document.body.getAttribute('data-user-id');
        if (bodyUser) return bodyUser;
        const metaUser = document.querySelector('meta[name="current-user"]');
        if (metaUser) return metaUser.getAttribute('content');
        return 'default_user';
    }

    class EnhancedTable {
        constructor(tableElement) {
            this.table = tableElement;
            this.tableId = this.table.getAttribute('data-enhanced-table') || 'enhanced_table';
            this.userId = this.table.getAttribute('data-user-id') || getCurrentUserId();
            this.version = this.table.getAttribute('data-config-version') || '1.0';
            this.storageKey = `cvdcms_table_pref_${this.userId}_${this.tableId}_v${this.version}`;

            // Trạng thái cấu hình cột
            this.columns = []; // Danh sách đối tượng cột { key, title, visible, pinned, width, required, isAction }
            this.initialDefaults = []; // Lưu mặc định ban đầu để khôi phục
            this.activeHoverRow = null;
            this.hoverHideTimeout = null;
            this.isResizing = false;

            this.init();
        }

        init() {
            this.setupContainers();
            this.inspectColumns();
            this.loadPreferences();
            this.renderToolbar();
            this.applyPreferencesToDOM();
            this.setupQuickActionBar();
            this.setupResizers();
            this.setupCustomizerPopover();
            this.setupScrollAndResizeSync();
            this.setupTouchAndKeyboardAccessibility();

            // Đánh dấu bảng đã khởi tạo
            this.table.setAttribute('data-enhanced-initialized', 'true');
        }

        /* 1. Bao bọc cấu trúc bảng nếu chưa có wrapper */
        setupContainers() {
            let wrapper = this.table.closest('.enhanced-table-wrapper');
            if (!wrapper) {
                wrapper = document.createElement('div');
                wrapper.className = 'enhanced-table-wrapper';
                this.table.parentNode.insertBefore(wrapper, this.table);
                wrapper.appendChild(this.table);
            }
            this.wrapper = wrapper;

            let container = this.wrapper.closest('.enhanced-table-container');
            if (!container) {
                container = document.createElement('div');
                container.className = 'enhanced-table-container';
                this.wrapper.parentNode.insertBefore(container, this.wrapper);
                container.appendChild(this.wrapper);
            }
            this.container = container;
        }

        /* 2. Quét thông tin và khóa ổn định data-column-key của tất cả các cột */
        inspectColumns() {
            const thead = this.table.querySelector('thead');
            if (!thead) return;

            const headerRow = thead.querySelector('tr');
            if (!headerRow) return;

            const thList = Array.from(headerRow.querySelectorAll('th'));
            this.columns = [];

            thList.forEach((th, idx) => {
                let key = th.getAttribute('data-column-key');
                if (!key) {
                    // Tự sinh key nếu chưa có dựa trên tiêu đề hoặc vị trí
                    const cleanText = th.innerText.trim().toLowerCase().replace(/[^a-z0-9_]+/g, '_');
                    key = cleanText ? `col_${cleanText}` : `col_${idx}`;
                    th.setAttribute('data-column-key', key);
                }

                const title = th.innerText.trim() || `Cột ${idx + 1}`;
                const isAction = th.getAttribute('data-column-action') === 'true' || 
                                 key === 'actions' || key === 'action' || 
                                 idx === thList.length - 1 && (title.includes('Thao tác') || title.includes('Hành động') || title === '');
                if (isAction) {
                    th.setAttribute('data-column-action', 'true');
                }

                const isRequired = th.getAttribute('data-column-required') === 'true' || 
                                   th.getAttribute('data-column-no-hide') === 'true' || 
                                   idx === 0; // Cột đầu thường là mã/định danh

                const isDefaultPinned = th.getAttribute('data-column-pinned') === 'true' || 
                                       (idx === 0 && !isAction);

                const defaultWidth = th.offsetWidth || 120;

                const colDef = {
                    key: key,
                    title: title || 'Thao tác',
                    visible: true,
                    pinned: isDefaultPinned,
                    width: defaultWidth,
                    required: isRequired,
                    isAction: isAction
                };

                this.columns.push(colDef);

                // Gắn data-column-key cho tất cả các ô td tương ứng trong tbody
                this.syncCellColumnKeys(idx, key);
            });

            // Lưu snapshot mặc định ban đầu
            this.initialDefaults = JSON.parse(JSON.stringify(this.columns));
        }

        syncCellColumnKeys(colIndex, key) {
            const col = this.columns[colIndex];
            const rows = this.table.querySelectorAll('tbody tr');
            rows.forEach(tr => {
                const cells = tr.children;
                if (cells && cells.length > colIndex && !tr.querySelector('td[colspan]')) {
                    cells[colIndex].setAttribute('data-column-key', key);
                    if (col && col.isAction) {
                        cells[colIndex].setAttribute('data-column-action', 'true');
                    }
                }
            });
        }

        /* 3. Đọc cấu hình từ LocalStorage (với kiểm tra hợp lệ và di chuyển cấu trúc) */
        loadPreferences() {
            try {
                const savedRaw = localStorage.getItem(this.storageKey);
                if (!savedRaw) return;

                const savedData = JSON.parse(savedRaw);
                if (!savedData || !Array.isArray(savedData.columns)) return;

                const existingKeyMap = new Map();
                this.columns.forEach(c => existingKeyMap.set(c.key, c));

                const mergedColumns = [];

                // Áp dụng thứ tự và thuộc tính từ cấu hình lưu
                savedData.columns.forEach(savedCol => {
                    if (existingKeyMap.has(savedCol.key)) {
                        const original = existingKeyMap.get(savedCol.key);
                        mergedColumns.push({
                            ...original,
                            visible: original.required ? true : (savedCol.visible !== false),
                            pinned: !!savedCol.pinned,
                            width: savedCol.width && savedCol.width >= 60 ? savedCol.width : original.width
                        });
                        existingKeyMap.delete(savedCol.key);
                    }
                });

                // Bổ sung các cột mới trong code chưa có trong LocalStorage
                existingKeyMap.forEach(col => {
                    mergedColumns.push(col);
                });

                // Đảm bảo không bị ẩn toàn bộ cột
                const hasVisible = mergedColumns.some(c => c.visible);
                if (!hasVisible && mergedColumns.length > 0) {
                    mergedColumns[0].visible = true;
                }

                this.columns = mergedColumns;
            } catch (err) {
                console.warn('[EnhancedTable] Lỗi đọc cấu hình LocalStorage:', err);
            }
        }

        /* Lưu cấu hình an toàn vào LocalStorage (chỉ lưu UI, không lưu dữ liệu nhạy cảm) */
        savePreferences() {
            try {
                const payload = {
                    version: this.version,
                    updatedAt: Date.now(),
                    columns: this.columns.map(c => ({
                        key: c.key,
                        visible: c.visible,
                        pinned: c.pinned,
                        width: c.width
                    }))
                };
                localStorage.setItem(this.storageKey, JSON.stringify(payload));
            } catch (err) {
                console.warn('[EnhancedTable] Không thể lưu cấu hình LocalStorage:', err);
            }
        }

        /* 4. Áp dụng thứ tự, ẩn/hiện, chiều rộng và ghim vào DOM */
        applyPreferencesToDOM() {
            const theadTr = this.table.querySelector('thead tr');
            if (!theadTr) return;

            // 1. Sắp xếp lại thứ tự các th và td
            this.columns.forEach(col => {
                const th = theadTr.querySelector(`th[data-column-key="${col.key}"]`);
                if (th) theadTr.appendChild(th);
            });

            const bodyRows = this.table.querySelectorAll('tbody tr');
            bodyRows.forEach(tr => {
                // Bỏ qua hàng có colspan (hàng rỗng hoặc thông báo)
                if (tr.querySelector('td[colspan]')) return;

                this.columns.forEach(col => {
                    const td = tr.querySelector(`td[data-column-key="${col.key}"]`);
                    if (td) tr.appendChild(td);
                });
            });

            // 2. Áp dụng ẩn / hiện & chiều rộng
            let visibleCount = 0;
            this.columns.forEach(col => {
                const th = theadTr.querySelector(`th[data-column-key="${col.key}"]`);
                const tds = this.table.querySelectorAll(`tbody td[data-column-key="${col.key}"]`);

                const displayVal = col.visible ? '' : 'none';
                if (th) {
                    th.style.display = displayVal;
                    if (col.width && col.visible) {
                        th.style.width = col.width + 'px';
                        th.style.minWidth = col.width + 'px';
                    }
                }

                tds.forEach(td => {
                    td.style.display = displayVal;
                    if (col.width && col.visible) {
                        td.style.maxWidth = (col.width + 40) + 'px';
                    }
                });

                if (col.visible) visibleCount++;
            });

            // Cập nhật colspan cho các hàng rỗng / tổng cộng nếu có
            this.table.querySelectorAll('tbody tr td[colspan]').forEach(td => {
                td.colSpan = Math.max(1, visibleCount);
            });

            // 3. Tính toán và áp dụng Ghim cột bên trái
            this.updatePinnedOffsets();

            // 4. Đồng bộ lại thanh thao tác nhanh nếu đang có dòng hover
            if (this.activeHoverRow) {
                this.checkAndSyncFloatingActionBar(this.activeHoverRow);
            }
        }

        /* Tính toán vị trí sticky left cho các cột ghim */
        updatePinnedOffsets() {
            const theadTr = this.table.querySelector('thead tr');
            if (!theadTr) return;

            // Reset trạng thái ghim cũ
            this.table.querySelectorAll('.enhanced-col-pinned, .enhanced-col-pinned-last').forEach(el => {
                el.classList.remove('enhanced-col-pinned', 'enhanced-col-pinned-last');
                el.style.left = '';
            });

            const pinnedVisibleCols = this.columns.filter(c => c.visible && c.pinned);
            let currentOffset = 0;
            const wrapperWidth = this.wrapper.clientWidth || 1000;
            const maxPinnedWidth = wrapperWidth * 0.6; // Không cho phép cột ghim chiếm quá 60% chiều rộng hiển thị

            pinnedVisibleCols.forEach((col, idx) => {
                const th = theadTr.querySelector(`th[data-column-key="${col.key}"]`);
                if (!th) return;

                const thWidth = th.offsetWidth;
                if (currentOffset + thWidth > maxPinnedWidth && idx > 0) {
                    // Quá giới hạn màn hình hẹp, ngừng ghim các cột tiếp theo để đảm bảo tính khả dụng
                    return;
                }

                th.classList.add('enhanced-col-pinned');
                th.style.left = currentOffset + 'px';

                const tds = this.table.querySelectorAll(`tbody td[data-column-key="${col.key}"]`);
                tds.forEach(td => {
                    td.classList.add('enhanced-col-pinned');
                    td.style.left = currentOffset + 'px';
                });

                // Đánh dấu cột ghim cuối cùng để vẽ bóng / viền phân tách
                if (idx === pinnedVisibleCols.length - 1) {
                    th.classList.add('enhanced-col-pinned-last');
                    tds.forEach(td => td.classList.add('enhanced-col-pinned-last'));
                }

                currentOffset += thWidth;
            });
        }

        /* 5. Tạo thanh công cụ trên bảng với nút "Tùy chỉnh cột" */
        renderToolbar() {
            let toolbar = this.container.querySelector('.enhanced-table-toolbar');
            if (!toolbar) {
                toolbar = document.createElement('div');
                toolbar.className = 'enhanced-table-toolbar';
                this.container.insertBefore(toolbar, this.wrapper);
            }
            this.toolbar = toolbar;

            // Kiểm tra xem đã có nút tùy chỉnh chưa
            let customBtn = this.toolbar.querySelector('.enhanced-custom-toggle-btn');
            if (!customBtn) {
                customBtn = document.createElement('button');
                customBtn.type = 'button';
                customBtn.className = 'enhanced-toolbar-btn enhanced-custom-toggle-btn';
                customBtn.innerHTML = '<i class="bi bi-gear-wide-connected text-primary"></i> <span>Tùy chỉnh cột</span>';
                customBtn.title = 'Ẩn / hiện cột, đổi thứ tự và ghim cột';
                this.toolbar.appendChild(customBtn);
            }
            this.customBtn = customBtn;

            // Thêm các nút ghim nhanh trên từng tiêu đề cột th
            const thList = this.table.querySelectorAll('thead th');
            thList.forEach(th => {
                const colKey = th.getAttribute('data-column-key');
                const colDef = this.columns.find(c => c.key === colKey);
                if (!colDef || colDef.isAction) return;

                let pinBtn = th.querySelector('.enhanced-pin-btn');
                if (!pinBtn) {
                    pinBtn = document.createElement('button');
                    pinBtn.type = 'button';
                    pinBtn.className = 'enhanced-pin-btn' + (colDef.pinned ? ' is-pinned' : '');
                    pinBtn.title = colDef.pinned ? 'Bỏ ghim cột' : 'Ghim cột này về bên trái';
                    pinBtn.innerHTML = colDef.pinned ? '<i class="bi bi-pin-angle-fill"></i>' : '<i class="bi bi-pin-angle"></i>';
                    
                    pinBtn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        this.togglePinColumn(colKey);
                    });

                    th.appendChild(pinBtn);
                }
            });
        }

        togglePinColumn(colKey) {
            const col = this.columns.find(c => c.key === colKey);
            if (!col) return;

            col.pinned = !col.pinned;

            // Cập nhật icon trên th
            const th = this.table.querySelector(`thead th[data-column-key="${colKey}"]`);
            if (th) {
                const pinBtn = th.querySelector('.enhanced-pin-btn');
                if (pinBtn) {
                    pinBtn.className = 'enhanced-pin-btn' + (col.pinned ? ' is-pinned' : '');
                    pinBtn.title = col.pinned ? 'Bỏ ghim cột' : 'Ghim cột này về bên trái';
                    pinBtn.innerHTML = col.pinned ? '<i class="bi bi-pin-angle-fill"></i>' : '<i class="bi bi-pin-angle"></i>';
                }
            }

            this.updatePinnedOffsets();
            this.savePreferences();
            this.updateCustomizerList();
        }

        /* 6. Co giãn chiều rộng cột (Column Resizer) */
        setupResizers() {
            const thList = this.table.querySelectorAll('thead th');
            thList.forEach(th => {
                let resizer = th.querySelector('.enhanced-col-resizer');
                if (!resizer) {
                    resizer = document.createElement('div');
                    resizer.className = 'enhanced-col-resizer';
                    th.appendChild(resizer);
                }

                resizer.addEventListener('mousedown', (e) => this.onResizerMouseDown(e, th));
            });
        }

        onResizerMouseDown(e, th) {
            e.preventDefault();
            e.stopPropagation();

            const colKey = th.getAttribute('data-column-key');
            const col = this.columns.find(c => c.key === colKey);
            if (!col) return;

            this.isResizing = true;
            document.body.classList.add('is-table-resizing');
            const resizer = th.querySelector('.enhanced-col-resizer');
            if (resizer) resizer.classList.add('is-resizing');

            const startX = e.pageX;
            const startWidth = th.offsetWidth;

            const onMouseMove = (moveEvent) => {
                if (!this.isResizing) return;
                const diff = moveEvent.pageX - startX;
                const newWidth = Math.max(70, startWidth + diff);

                th.style.width = newWidth + 'px';
                th.style.minWidth = newWidth + 'px';
                col.width = newWidth;

                const tds = this.table.querySelectorAll(`tbody td[data-column-key="${colKey}"]`);
                tds.forEach(td => {
                    td.style.maxWidth = (newWidth + 40) + 'px';
                });

                this.updatePinnedOffsets();
            };

            const onMouseUp = () => {
                this.isResizing = false;
                document.body.classList.remove('is-table-resizing');
                if (resizer) resizer.classList.remove('is-resizing');

                document.removeEventListener('mousemove', onMouseMove);
                document.removeEventListener('mouseup', onMouseUp);

                this.savePreferences();
                if (this.activeHoverRow) {
                    this.checkAndSyncFloatingActionBar(this.activeHoverRow);
                }
            };

            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);
        }

        /* 7. Thanh thao tác nhanh nổi sát mép phải nhìn thấy (Requirement B) */
        setupQuickActionBar() {
            let floatingBar = this.wrapper.querySelector('.enhanced-floating-actions');
            if (!floatingBar) {
                floatingBar = document.createElement('div');
                floatingBar.className = 'enhanced-floating-actions';
                floatingBar.setAttribute('role', 'toolbar');
                floatingBar.setAttribute('aria-label', 'Thao tác nhanh cho dòng hiện tại');
                this.wrapper.appendChild(floatingBar);
            }
            this.floatingBar = floatingBar;

            // Xử lý hover vào hàng trong tbody
            const tbody = this.table.querySelector('tbody');
            if (!tbody) return;

            tbody.addEventListener('mouseover', (e) => {
                const tr = e.target.closest('tr');
                if (!tr || tr.querySelector('td[colspan]')) return;
                this.checkAndSyncFloatingActionBar(tr);
            });

            tbody.addEventListener('mouseout', (e) => {
                const related = e.relatedTarget;
                if (related && (this.floatingBar.contains(related) || (this.activeHoverRow && this.activeHoverRow.contains(related)))) {
                    return;
                }
                this.hideFloatingActionBarWithDelay();
            });

            // Khi chuột di vào thanh nút nổi: không bị ẩn
            this.floatingBar.addEventListener('mouseenter', () => {
                if (this.hoverHideTimeout) {
                    clearTimeout(this.hoverHideTimeout);
                    this.hoverHideTimeout = null;
                }
            });

            this.floatingBar.addEventListener('mouseleave', (e) => {
                const related = e.relatedTarget;
                if (related && this.activeHoverRow && this.activeHoverRow.contains(related)) {
                    return;
                }
                this.hideFloatingActionBarWithDelay();
            });
        }

        /* Tìm ô chứa thao tác gốc của một dòng */
        getActionCell(row) {
            if (!row) return null;
            let cell = row.querySelector('td[data-column-action="true"]');
            if (cell) return cell;

            cell = row.querySelector('td[data-column-key="actions"], td[data-column-key="action"]');
            if (cell) return cell;

            const actionCol = this.columns.find(c => c.isAction);
            if (actionCol) {
                cell = row.querySelector(`td[data-column-key="${actionCol.key}"]`);
                if (cell) return cell;
            }

            const nonColspanCells = Array.from(row.children).filter(td => !td.hasAttribute('colspan'));
            return nonColspanCells.length > 0 ? nonColspanCells[nonColspanCells.length - 1] : null;
        }

        /* Lấy danh sách các nút/link thao tác hợp lệ, loại trừ nhân đôi (form lặp với button con) */
        getRowActionElements(actionCell) {
            if (!actionCell) return [];

            // Chọn các nút bấm, thẻ liên kết thực tế (không chọn thẻ <form> vì sẽ gây trùng với <button> bên trong)
            const candidates = Array.from(actionCell.querySelectorAll(
                'button:not([type="hidden"]), a[href], a.btn, a[onclick], a[data-bs-toggle], input[type="submit"]'
            ));

            // Nếu có form không có button con (rất hiếm), bổ sung form đó
            const formsWithoutSubmit = Array.from(actionCell.querySelectorAll('form')).filter(f => !f.querySelector('button, input[type="submit"]'));
            formsWithoutSubmit.forEach(f => candidates.push(f));

            const uniqueElements = [];
            const seen = new Set();

            for (const el of candidates) {
                // Tuyệt đối không lấy các phần tử từ thanh nổi hoặc popover
                if (el.closest('.enhanced-floating-actions') || el.closest('.enhanced-customizer-popover')) {
                    continue;
                }

                // Bỏ qua phần tử ẩn
                if (el.type === 'hidden' || el.style.display === 'none' || el.hasAttribute('hidden') || el.closest('[style*="display: none"]')) {
                    continue;
                }

                if (seen.has(el)) continue;

                // Kiểm tra có nội dung chữ, icon hoặc tooltip
                const hasText = el.innerText.trim().length > 0;
                const hasIcon = el.querySelector('i, svg') !== null;
                const hasTitle = (el.title || el.getAttribute('aria-label') || '').trim().length > 0;
                if (!hasText && !hasIcon && !hasTitle) {
                    continue;
                }

                seen.add(el);
                uniqueElements.push(el);
            }

            return uniqueElements;
        }

        /* Kiểm tra điều kiện thực tế để hiển thị thanh thao tác nhanh */
        shouldShowFloatingBar(row) {
            if (!row || this.isResizing) return false;
            if (row.querySelector('td[colspan]')) return false;

            // Điều kiện 1: Bảng phải có cuộn ngang
            const wrapper = this.wrapper;
            const hasHorizontalScroll = wrapper.scrollWidth > wrapper.clientWidth + 2;
            if (!hasHorizontalScroll) {
                // Bảng vừa khung nhìn -> KHÔNG HIỆN thanh nổi
                return false;
            }

            // Điều kiện 2: Ô thao tác gốc tồn tại
            const actionCell = this.getActionCell(row);
            if (!actionCell) return false;

            // Điều kiện 3: Có ít nhất một hành động hợp lệ theo quyền / trạng thái bản ghi
            const actionElements = this.getRowActionElements(actionCell);
            if (actionElements.length === 0) {
                return false;
            }

            // Điều kiện 4: Kiểm tra tọa độ thực tế của nhóm nút so với khung nhìn thấy của wrapper
            const wrapperRect = wrapper.getBoundingClientRect();

            // Tính toán vùng bị che khuất bởi các cột đang ghim bên trái
            let leftPinnedBoundary = wrapperRect.left;
            const pinnedThList = this.table.querySelectorAll('thead th.enhanced-col-pinned');
            pinnedThList.forEach(th => {
                const colKey = th.getAttribute('data-column-key');
                const colDef = this.columns.find(c => c.key === colKey);
                // Nếu chính cột thao tác được ghim, nó không phải là vật cản che chính nó
                if (colDef && colDef.isAction) return;

                const thRect = th.getBoundingClientRect();
                if (thRect.right > leftPinnedBoundary) {
                    leftPinnedBoundary = thRect.right;
                }
            });

            const rightBoundary = wrapperRect.right;

            // Kiểm tra xem tất cả các nút gốc có nằm TRỌN VẸN trong vùng nhìn thấy [leftPinnedBoundary, rightBoundary] không
            let allButtonsFullyVisible = true;
            for (const btn of actionElements) {
                const r = btn.getBoundingClientRect();
                if (r.width === 0 && r.height === 0) continue;

                // Nút bị khuất/bị cắt mép trái (bởi cột ghim) hoặc mép phải (do cuộn ngang)
                const isCutLeft = (r.left < leftPinnedBoundary - 2);
                const isCutRight = (r.right > rightBoundary + 2);

                if (isCutLeft || isCutRight) {
                    allButtonsFullyVisible = false;
                    break;
                }
            }

            // Nếu toàn bộ nút gốc đã nhìn thấy đầy đủ -> KHÔNG HIỆN thanh nổi (tránh trùng lặp)
            if (allButtonsFullyVisible) {
                return false;
            }

            // Có ít nhất 1 nút bị che khuất -> CẦN HIỆN thanh nổi
            return true;
        }

        /* Đồng bộ hiển thị / ẩn thanh nổi linh hoạt theo trạng thái cuộn và vị trí */
        checkAndSyncFloatingActionBar(row) {
            if (!row) {
                this.hideFloatingActionBar();
                return;
            }

            if (this.hoverHideTimeout) {
                clearTimeout(this.hoverHideTimeout);
                this.hoverHideTimeout = null;
            }

            this.activeHoverRow = row;

            if (this.shouldShowFloatingBar(row)) {
                this.renderFloatingActionBarContent(row);
                this.updateFloatingBarPosition();
                this.floatingBar.classList.add('is-visible');
            } else {
                this.hideFloatingActionBar();
            }
        }

        /* Dựng danh sách nút bấm nổi duy nhất (không nhân bản ID, không lặp sự kiện) */
        renderFloatingActionBarContent(row) {
            const actionCell = this.getActionCell(row);
            if (!actionCell) return;

            const actionElements = this.getRowActionElements(actionCell);
            this.floatingBar.innerHTML = '';

            actionElements.forEach(elem => {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = elem.className || 'btn btn-sm btn-outline-primary';
                btn.innerHTML = elem.innerHTML;
                btn.title = elem.title || elem.getAttribute('aria-label') || elem.innerText.trim();

                // Chuyển tiếp click trực tiếp về phần tử gốc duy nhất
                btn.addEventListener('click', (ev) => {
                    ev.preventDefault();
                    ev.stopPropagation();

                    if (elem.tagName === 'A' && elem.href && !elem.onclick && !elem.getAttribute('data-bs-toggle')) {
                        if (elem.target === '_blank') {
                            window.open(elem.href, '_blank');
                        } else {
                            window.location.href = elem.href;
                        }
                    } else {
                        // Kích hoạt click trực tiếp lên nút gốc (đảm bảo chạy đúng confirm, modal, và CSRF)
                        elem.click();
                    }
                });

                this.floatingBar.appendChild(btn);
            });
        }

        updateFloatingBarPosition() {
            if (!this.activeHoverRow || !this.floatingBar) return;

            const row = this.activeHoverRow;
            const rowTop = row.offsetTop;
            const rowHeight = row.offsetHeight;
            const barHeight = this.floatingBar.offsetHeight || 38;

            // Vị trí dọc: Căn giữa dòng đang hover
            const topPos = rowTop + (rowHeight - barHeight) / 2;
            this.floatingBar.style.top = `${topPos}px`;

            // Vị trí ngang: Luôn nằm sát mép phải PHẦN BẢNG ĐANG NHÌN THẤY
            const scrollLeft = this.wrapper.scrollLeft;
            const visibleWidth = this.wrapper.clientWidth;
            const barWidth = this.floatingBar.offsetWidth || 180;

            const leftPos = scrollLeft + visibleWidth - barWidth - 8;
            this.floatingBar.style.left = `${Math.max(0, leftPos)}px`;
        }

        hideFloatingActionBarWithDelay() {
            if (this.hoverHideTimeout) clearTimeout(this.hoverHideTimeout);
            this.hoverHideTimeout = setTimeout(() => {
                this.hideFloatingActionBar();
            }, 120);
        }

        hideFloatingActionBar() {
            if (this.floatingBar) {
                this.floatingBar.classList.remove('is-visible');
            }
            this.activeHoverRow = null;
        }

        /* 8. Popover Tùy chỉnh cột (Requirement D) - Portal ra body & định vị động */
        setupCustomizerPopover() {
            let popover = document.body.querySelector(`.enhanced-customizer-popover[data-table-id="${this.tableId}"]`);
            if (!popover) {
                popover = document.createElement('div');
                popover.className = 'enhanced-customizer-popover';
                popover.setAttribute('data-table-id', this.tableId);
                popover.setAttribute('role', 'dialog');
                popover.setAttribute('aria-modal', 'false');
                popover.setAttribute('aria-label', 'Tùy chỉnh cột bảng');
                popover.innerHTML = `
                    <div class="enhanced-customizer-head">
                        <h6><i class="bi bi-layout-three-columns text-primary"></i> Tùy chỉnh cột bảng</h6>
                        <button type="button" class="enhanced-customizer-close" aria-label="Đóng">&times;</button>
                    </div>
                    <div class="enhanced-customizer-body">
                        <!-- Danh sách cột render động tại đây -->
                    </div>
                    <div class="enhanced-customizer-foot">
                        <button type="button" class="enhanced-reset-btn"><i class="bi bi-arrow-counterclockwise"></i> Khôi phục mặc định</button>
                        <button type="button" class="btn btn-sm btn-primary enhanced-done-btn">Xong</button>
                    </div>
                `;
                document.body.appendChild(popover);
            }
            this.popover = popover;

            // Nút mở/đóng menu
            this.customBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                this.toggleCustomizer();
            });

            // Nút đóng X
            this.popover.querySelector('.enhanced-customizer-close').addEventListener('click', (e) => {
                e.stopPropagation();
                this.closeCustomizer();
            });

            // Nút Xong
            this.popover.querySelector('.enhanced-done-btn').addEventListener('click', (e) => {
                e.stopPropagation();
                this.closeCustomizer();
            });

            // Nút Khôi phục mặc định
            this.popover.querySelector('.enhanced-reset-btn').addEventListener('click', (e) => {
                e.stopPropagation();
                this.resetToDefaults();
            });

            // Ngăn chặn sự kiện click bên trong popover nổi bọt ra document
            this.popover.addEventListener('click', (e) => {
                e.stopPropagation();
            });

            this.updateCustomizerList();
        }

        toggleCustomizer() {
            if (this.popover && this.popover.classList.contains('is-open')) {
                this.closeCustomizer();
            } else {
                this.openCustomizer();
            }
        }

        openCustomizer() {
            if (!this.popover || !this.customBtn) return;

            // Đóng bất kỳ popover nào khác đang mở
            document.querySelectorAll('.enhanced-customizer-popover.is-open').forEach(p => {
                if (p !== this.popover) {
                    p.classList.remove('is-open');
                }
            });
            document.querySelectorAll('.enhanced-custom-toggle-btn.active').forEach(b => {
                if (b !== this.customBtn) {
                    b.classList.remove('active');
                }
            });

            this.updateCustomizerList();
            this.popover.classList.add('is-open');
            this.customBtn.classList.add('active');

            // Định vị chính xác popover theo tọa độ thực tế của nút
            this.updateCustomizerPosition();

            // Đăng ký các sự kiện theo dõi vị trí và đóng tự động
            this.bindPopoverDismissEvents();
        }

        closeCustomizer() {
            if (!this.popover) return;
            this.popover.classList.remove('is-open');
            if (this.customBtn) {
                this.customBtn.classList.remove('active');
            }
            this.unbindPopoverDismissEvents();
        }

        updateCustomizerPosition() {
            if (!this.popover || !this.popover.classList.contains('is-open') || !this.customBtn) return;

            const btnRect = this.customBtn.getBoundingClientRect();
            const viewportWidth = window.innerWidth;
            const viewportHeight = window.innerHeight;

            // Nếu nút đã cuộn hoàn toàn khỏi khung nhìn (viewport), tự đóng popover
            if (btnRect.bottom < 0 || btnRect.top > viewportHeight || btnRect.right < 0 || btnRect.left > viewportWidth) {
                this.closeCustomizer();
                return;
            }

            // Chiều rộng popover (tối đa bằng chiều rộng màn hình trừ lề 24px)
            const popoverWidth = Math.min(320, viewportWidth - 24);
            this.popover.style.width = `${popoverWidth}px`;

            // Mặc định căn lề mép phải theo nút "Tùy chỉnh cột":
            let left = btnRect.right - popoverWidth;

            // Giới hạn trong viewport, cách lề tối thiểu 12px
            if (left + popoverWidth > viewportWidth - 12) {
                left = viewportWidth - 12 - popoverWidth;
            }
            if (left < 12) {
                left = 12;
            }

            // Tính toán khoảng trống phía dưới và phía trên
            const gap = 6;
            const spaceBelow = viewportHeight - btnRect.bottom - gap - 12;
            const spaceAbove = btnRect.top - gap - 12;

            // Kiểm tra hướng mở: mở dưới nếu còn đủ chỗ (>= 250px hoặc rộng hơn phía trên)
            if (spaceBelow >= 250 || spaceBelow >= spaceAbove) {
                this.popover.setAttribute('data-placement', 'bottom');
                this.popover.style.left = `${Math.round(left)}px`;
                this.popover.style.top = `${Math.round(btnRect.bottom + gap)}px`;
                this.popover.style.bottom = 'auto';
                this.popover.style.maxHeight = `${Math.round(Math.max(160, spaceBelow))}px`;
            } else {
                // Tự động mở lên trên nếu thiếu chỗ phía dưới
                this.popover.setAttribute('data-placement', 'top');
                this.popover.style.left = `${Math.round(left)}px`;
                this.popover.style.top = 'auto';
                this.popover.style.bottom = `${Math.round(viewportHeight - btnRect.top + gap)}px`;
                this.popover.style.maxHeight = `${Math.round(Math.max(160, spaceAbove))}px`;
            }
        }

        bindPopoverDismissEvents() {
            this.unbindPopoverDismissEvents();

            // 1. Cập nhật vị trí khi cuộn trang, cuộn bảng hoặc thay đổi kích thước cửa sổ
            this.onScrollOrResizeBound = () => {
                this.updateCustomizerPosition();
            };
            window.addEventListener('resize', this.onScrollOrResizeBound, { passive: true });
            window.addEventListener('scroll', this.onScrollOrResizeBound, { passive: true, capture: true });

            // 2. Đóng khi click ra ngoài (Outside Click)
            this.onOutsideClickBound = (e) => {
                if (this.popover && !this.popover.contains(e.target) && this.customBtn && !this.customBtn.contains(e.target)) {
                    this.closeCustomizer();
                }
            };
            setTimeout(() => {
                document.addEventListener('click', this.onOutsideClickBound);
            }, 0);

            // 3. Đóng khi bấm phím Escape
            this.onKeyDownBound = (e) => {
                if (e.key === 'Escape' || e.key === 'Esc') {
                    this.closeCustomizer();
                }
            };
            document.addEventListener('keydown', this.onKeyDownBound);

            // 4. Đóng khi có Bootstrap Modal mở lên để không bị đè lên modal
            this.onModalShowBound = () => {
                this.closeCustomizer();
            };
            document.addEventListener('show.bs.modal', this.onModalShowBound);
        }

        unbindPopoverDismissEvents() {
            if (this.onScrollOrResizeBound) {
                window.removeEventListener('resize', this.onScrollOrResizeBound);
                window.removeEventListener('scroll', this.onScrollOrResizeBound, { capture: true });
                this.onScrollOrResizeBound = null;
            }
            if (this.onOutsideClickBound) {
                document.removeEventListener('click', this.onOutsideClickBound);
                this.onOutsideClickBound = null;
            }
            if (this.onKeyDownBound) {
                document.removeEventListener('keydown', this.onKeyDownBound);
                this.onKeyDownBound = null;
            }
            if (this.onModalShowBound) {
                document.removeEventListener('show.bs.modal', this.onModalShowBound);
                this.onModalShowBound = null;
            }
        }

        updateCustomizerList() {
            const body = this.popover.querySelector('.enhanced-customizer-body');
            if (!body) return;

            body.innerHTML = '';
            const visibleCount = this.columns.filter(c => c.visible).length;

            this.columns.forEach((col, index) => {
                const item = document.createElement('div');
                item.className = 'enhanced-customizer-item';
                item.setAttribute('data-col-key', col.key);
                item.draggable = true;

                const isOnlyVisible = col.visible && visibleCount <= 1;
                const disableCheckbox = col.required || isOnlyVisible;

                item.innerHTML = `
                    <span class="enhanced-customizer-drag" title="Kéo để đổi thứ tự"><i class="bi bi-grip-vertical"></i></span>
                    <input type="checkbox" class="enhanced-customizer-checkbox" ${col.visible ? 'checked' : ''} ${disableCheckbox ? 'disabled' : ''} title="${disableCheckbox ? 'Không thể ẩn cột này' : 'Bật/tắt hiển thị'}">
                    <span class="enhanced-customizer-label" title="${col.title}">${col.title} ${col.required ? '<small class="text-muted">(Bắt buộc)</small>' : ''}</span>
                    <div class="enhanced-customizer-actions">
                        <button type="button" class="enhanced-customizer-action-btn enhanced-move-up-btn" title="Di chuyển lên trên" ${index === 0 ? 'disabled style="opacity: 0.3;"' : ''}>
                            <i class="bi bi-chevron-up"></i>
                        </button>
                        <button type="button" class="enhanced-customizer-action-btn enhanced-move-down-btn" title="Di chuyển xuống dưới" ${index === this.columns.length - 1 ? 'disabled style="opacity: 0.3;"' : ''}>
                            <i class="bi bi-chevron-down"></i>
                        </button>
                        ${!col.isAction ? `
                        <button type="button" class="enhanced-customizer-action-btn enhanced-pin-col-btn ${col.pinned ? 'active text-primary' : ''}" title="${col.pinned ? 'Bỏ ghim' : 'Ghim bên trái'}">
                            <i class="bi ${col.pinned ? 'bi-pin-angle-fill' : 'bi-pin-angle'}"></i>
                        </button>` : ''}
                    </div>
                `;

                // Xử lý bật/tắt checkbox
                const checkbox = item.querySelector('.enhanced-customizer-checkbox');
                checkbox.addEventListener('change', () => {
                    col.visible = checkbox.checked;
                    this.applyPreferencesToDOM();
                    this.savePreferences();
                    this.updateCustomizerList();
                });

                // Di chuyển lên / xuống
                const upBtn = item.querySelector('.enhanced-move-up-btn');
                if (upBtn) {
                    upBtn.addEventListener('click', () => {
                        this.moveColumn(index, index - 1);
                    });
                }

                const downBtn = item.querySelector('.enhanced-move-down-btn');
                if (downBtn) {
                    downBtn.addEventListener('click', () => {
                        this.moveColumn(index, index + 1);
                    });
                }

                // Nút ghim trong popover
                const pinBtn = item.querySelector('.enhanced-pin-col-btn');
                if (pinBtn) {
                    pinBtn.addEventListener('click', () => {
                        this.togglePinColumn(col.key);
                    });
                }

                // Xử lý kéo thả HTML5 Drag and Drop
                this.setupDragAndDrop(item, index);

                body.appendChild(item);
            });
        }

        moveColumn(fromIndex, toIndex) {
            if (toIndex < 0 || toIndex >= this.columns.length) return;
            const movedCol = this.columns.splice(fromIndex, 1)[0];
            this.columns.splice(toIndex, 0, movedCol);

            this.applyPreferencesToDOM();
            this.savePreferences();
            this.updateCustomizerList();
            this.updateCustomizerPosition();
        }

        setupDragAndDrop(itemElem, index) {
            itemElem.addEventListener('dragstart', (e) => {
                e.dataTransfer.setData('text/plain', index);
                itemElem.classList.add('is-dragging');
            });

            itemElem.addEventListener('dragend', () => {
                itemElem.classList.remove('is-dragging');
            });

            itemElem.addEventListener('dragover', (e) => {
                e.preventDefault();
                itemElem.style.borderTop = '2px solid #0284c7';
            });

            itemElem.addEventListener('dragleave', () => {
                itemElem.style.borderTop = '';
            });

            itemElem.addEventListener('drop', (e) => {
                e.preventDefault();
                itemElem.style.borderTop = '';
                const fromIndex = parseInt(e.dataTransfer.getData('text/plain'), 10);
                if (!isNaN(fromIndex) && fromIndex !== index) {
                    this.moveColumn(fromIndex, index);
                }
            });
        }

        /* Khôi phục mặc định */
        resetToDefaults() {
            if (!confirm('Khôi phục lại thứ tự, độ rộng và hiển thị mặc định của bảng này?')) return;
            localStorage.removeItem(this.storageKey);
            this.columns = JSON.parse(JSON.stringify(this.initialDefaults));
            this.applyPreferencesToDOM();
            this.updateCustomizerList();
            this.updateCustomizerPosition();
        }

        /* 9. Đồng bộ khi cuộn ngang/dọc và thay đổi kích thước cửa sổ */
        setupScrollAndResizeSync() {
            const onScrollOrResize = () => {
                if (this.activeHoverRow) {
                    this.checkAndSyncFloatingActionBar(this.activeHoverRow);
                }
            };

            this.wrapper.addEventListener('scroll', onScrollOrResize, { passive: true });
            window.addEventListener('resize', () => {
                this.updatePinnedOffsets();
                onScrollOrResize();
            }, { passive: true });
        }

        /* 10. Trợ năng bàn phím và thiết bị cảm ứng */
        setupTouchAndKeyboardAccessibility() {
            // Khi bàn phím focus vào bất kỳ nút nào trong dòng
            this.table.addEventListener('focusin', (e) => {
                const tr = e.target.closest('tr');
                if (tr && !tr.querySelector('td[colspan]')) {
                    this.checkAndSyncFloatingActionBar(tr);
                }
            });

            // Hỗ trợ thiết bị cảm ứng (touch tap vào dòng)
            this.table.addEventListener('click', (e) => {
                if ('ontouchstart' in window || navigator.maxTouchPoints > 0) {
                    const tr = e.target.closest('tbody tr');
                    if (tr && !tr.querySelector('td[colspan]')) {
                        this.checkAndSyncFloatingActionBar(tr);
                    }
                }
            });
        }
    }

    // Khởi tạo tự động khi trang tải xong
    function initializeEnhancedTables() {
        const tables = document.querySelectorAll('table[data-enhanced-table]');
        tables.forEach(table => {
            if (!table.getAttribute('data-enhanced-initialized')) {
                new EnhancedTable(table);
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initializeEnhancedTables);
    } else {
        initializeEnhancedTables();
    }

    // Export ra window để cho phép gọi lại khi Ajax cập nhật nội dung
    window.EnhancedTable = EnhancedTable;
    window.initializeEnhancedTables = initializeEnhancedTables;

})();
