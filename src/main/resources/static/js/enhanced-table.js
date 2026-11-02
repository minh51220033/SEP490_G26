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
            this.version = this.table.getAttribute('data-config-version') || '1.1';
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

            // Khử xung đột cuộn kép khi bảng nằm trong .fin-table-wrapper hoặc .table-responsive
            const parent = this.container.parentElement;
            if (parent && (parent.classList.contains('fin-table-wrapper') || parent.classList.contains('table-responsive'))) {
                parent.style.overflow = 'visible';
                parent.style.border = 'none';
                parent.style.background = 'transparent';
                parent.style.padding = '0';
            }
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
                    const cleanText = th.innerText.trim().toLowerCase().replace(/[^a-z0-9_]+/g, '_');
                    key = cleanText ? `col_${cleanText}` : `col_${idx}`;
                    th.setAttribute('data-column-key', key);
                }

                const title = th.innerText.trim() || `Cột ${idx + 1}`;
                const isAction = th.getAttribute('data-column-action') === 'true' || 
                                 key === 'actions' || key === 'action' || 
                                 (idx === thList.length - 1 && (title.includes('Thao tác') || title.includes('Hành động') || title === ''));
                if (isAction) {
                    th.setAttribute('data-column-action', 'true');
                    key = 'actions';
                    th.setAttribute('data-column-key', 'actions');
                }

                const isRequired = th.getAttribute('data-column-required') === 'true' || 
                                   th.getAttribute('data-column-no-hide') === 'true' || 
                                   isAction ||
                                   idx === 0;

                const isDefaultPinned = !isAction && (th.getAttribute('data-column-pinned') === 'true' || idx === 0);

                // Ưu tiên đọc minWidth từ inline style để tránh tính toán sai khi bảng giãn rộng
                const minW = parseInt(th.style.minWidth, 10) || 0;
                const naturalW = th.offsetWidth || 120;
                const defaultWidth = minW > 0 ? minW : Math.min(Math.max(naturalW, 80), 240);

                const colDef = {
                    key: key,
                    title: title || (isAction ? 'Thao tác' : `Cột ${idx + 1}`),
                    visible: true,
                    pinned: isDefaultPinned,
                    width: defaultWidth,
                    required: isRequired,
                    isAction: isAction
                };

                this.columns.push(colDef);

                // Gắn data-column-key cho tất cả các ô td tương ứng trong tbody
                this.syncCellColumnKeys(idx, key, isAction);
            });

            // Lưu snapshot mặc định ban đầu
            this.initialDefaults = JSON.parse(JSON.stringify(this.columns));
        }

        syncCellColumnKeys(colIndex, key, isAction) {
            const rows = this.table.querySelectorAll('tbody tr');
            rows.forEach(tr => {
                const cells = tr.children;
                if (cells && cells.length > colIndex && !tr.querySelector('td[colspan]')) {
                    cells[colIndex].setAttribute('data-column-key', key);
                    if (isAction) {
                        cells[colIndex].setAttribute('data-column-action', 'true');
                    }
                }
            });
        }

        /* 3. Đọc cấu hình từ LocalStorage */
        loadPreferences() {
            try {
                const savedRaw = localStorage.getItem(this.storageKey);
                if (!savedRaw) return;

                const savedData = JSON.parse(savedRaw);
                if (!savedData || !Array.isArray(savedData.columns)) return;

                const existingKeyMap = new Map();
                this.columns.forEach(c => existingKeyMap.set(c.key, c));

                const mergedColumns = [];

                savedData.columns.forEach(savedCol => {
                    if (existingKeyMap.has(savedCol.key)) {
                        const original = existingKeyMap.get(savedCol.key);
                        let colWidth = savedCol.width && savedCol.width >= 60 ? savedCol.width : original.width;
                        if (colWidth > 350 && !['reason', 'description', 'notes'].includes(savedCol.key)) {
                            colWidth = Math.min(colWidth, 260);
                        }
                        mergedColumns.push({
                            ...original,
                            visible: (original.required || original.isAction) ? true : (savedCol.visible !== false),
                            pinned: original.isAction ? false : !!savedCol.pinned,
                            width: colWidth
                        });
                        existingKeyMap.delete(savedCol.key);
                    }
                });

                existingKeyMap.forEach(col => {
                    mergedColumns.push(col);
                });

                const hasVisible = mergedColumns.some(c => c.visible);
                if (!hasVisible && mergedColumns.length > 0) {
                    mergedColumns[0].visible = true;
                }

                this.columns = mergedColumns;
            } catch (err) {
                console.warn('[EnhancedTable] Lỗi đọc cấu hình LocalStorage:', err);
            }
        }

        /* Lưu cấu hình an toàn vào LocalStorage */
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

            // Đảm bảo cột Thao tác luôn ở cuối cùng
            const actionIndex = this.columns.findIndex(c => c.isAction);
            if (actionIndex !== -1 && actionIndex !== this.columns.length - 1) {
                const actionCol = this.columns.splice(actionIndex, 1)[0];
                this.columns.push(actionCol);
            }

            // 1. Sắp xếp lại thứ tự các th và td
            this.columns.forEach(col => {
                const th = theadTr.querySelector(`th[data-column-key="${col.key}"]`);
                if (th) theadTr.appendChild(th);
            });

            const bodyRows = this.table.querySelectorAll('tbody tr');
            bodyRows.forEach(tr => {
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

            // 3. Tính toán và áp dụng Ghim cột bên trái & Ghim cột Thao tác bên phải
            this.updatePinnedOffsets();
        }

        /* Tính toán vị trí sticky left cho các cột ghim bên trái & sticky right cho Thao tác */
        updatePinnedOffsets() {
            const theadTr = this.table.querySelector('thead tr');
            if (!theadTr) return;

            // Reset trạng thái ghim cũ
            this.table.querySelectorAll('.enhanced-col-pinned, .enhanced-col-pinned-last, .enhanced-col-action').forEach(el => {
                el.classList.remove('enhanced-col-pinned', 'enhanced-col-pinned-last', 'enhanced-col-action');
                el.style.left = '';
            });

            // Chỉ ghim các cột dữ liệu về bên trái (loại trừ Thao tác)
            const pinnedVisibleCols = this.columns.filter(c => c.visible && c.pinned && !c.isAction);
            let currentOffset = 0;
            const wrapperWidth = this.wrapper.clientWidth || 1000;
            const maxPinnedWidth = wrapperWidth * 0.45; // Cột ghim bên trái không vượt quá 45% màn hình

            pinnedVisibleCols.forEach((col, idx) => {
                const th = theadTr.querySelector(`th[data-column-key="${col.key}"]`);
                if (!th) return;

                const thWidth = th.offsetWidth;
                if (currentOffset + thWidth > maxPinnedWidth && idx > 0) {
                    return;
                }

                th.classList.add('enhanced-col-pinned');
                th.style.left = currentOffset + 'px';

                const tds = this.table.querySelectorAll(`tbody td[data-column-key="${col.key}"]`);
                tds.forEach(td => {
                    td.classList.add('enhanced-col-pinned');
                    td.style.left = currentOffset + 'px';
                });

                if (idx === pinnedVisibleCols.length - 1) {
                    th.classList.add('enhanced-col-pinned-last');
                    tds.forEach(td => td.classList.add('enhanced-col-pinned-last'));
                }

                currentOffset += thWidth;
            });

            // Cố định cột Thao tác ở mép phải (sticky right)
            const actionCol = this.columns.find(c => c.isAction);
            if (actionCol) {
                const actionTh = theadTr.querySelector(`th[data-column-key="${actionCol.key}"]`);
                if (actionTh) {
                    actionTh.classList.add('enhanced-col-action');
                }
                const actionTds = this.table.querySelectorAll(`tbody td[data-column-key="${actionCol.key}"]`);
                actionTds.forEach(td => td.classList.add('enhanced-col-action'));
            }
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

            // Thêm các nút ghim nhanh trên từng tiêu đề cột th (trừ cột Thao tác)
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
            if (!col || col.isAction) return;

            col.pinned = !col.pinned;

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
                const colKey = th.getAttribute('data-column-key');
                const colDef = this.columns.find(c => c.key === colKey);
                if (colDef && colDef.isAction) return; // Không cần resizer cho cột Thao tác

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
                const newWidth = Math.max(70, Math.min(500, startWidth + diff));

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
            };

            document.addEventListener('mousemove', onMouseMove);
            document.addEventListener('mouseup', onMouseUp);
        }

        /* 7. Không sử dụng floating action bar vì cột Thao tác đã ghim cố định ở mép phải */
        setupQuickActionBar() {
            // Thanh nổi đã được thay thế bằng sticky right cho cột Thao tác
        }

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

        getRowActionElements(actionCell) {
            if (!actionCell) return [];
            return Array.from(actionCell.querySelectorAll('button:not([type="hidden"]), a[href], a.btn, a[onclick], a[data-bs-toggle]'));
        }

        shouldShowFloatingBar(row) {
            // Luôn trả về false vì cột Thao tác đã luôn hiển thị ở mép phải bảng (sticky right)
            return false;
        }

        checkAndSyncFloatingActionBar(row) {
            this.hideFloatingActionBar();
        }

        hideFloatingActionBar() {
            if (this.floatingBar) {
                this.floatingBar.classList.remove('is-visible');
            }
            this.activeHoverRow = null;
        }

        /* 8. Popover Tùy chỉnh cột */
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
                    </div>
                    <div class="enhanced-customizer-foot">
                        <button type="button" class="enhanced-reset-btn"><i class="bi bi-arrow-counterclockwise"></i> Khôi phục mặc định</button>
                        <button type="button" class="btn btn-sm btn-primary enhanced-done-btn">Xong</button>
                    </div>
                `;
                document.body.appendChild(popover);
            }
            this.popover = popover;

            this.customBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                this.toggleCustomizer();
            });

            this.popover.querySelector('.enhanced-customizer-close').addEventListener('click', (e) => {
                e.stopPropagation();
                this.closeCustomizer();
            });

            this.popover.querySelector('.enhanced-done-btn').addEventListener('click', (e) => {
                e.stopPropagation();
                this.closeCustomizer();
            });

            this.popover.querySelector('.enhanced-reset-btn').addEventListener('click', (e) => {
                e.stopPropagation();
                this.resetToDefaults();
            });

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

            document.querySelectorAll('.enhanced-customizer-popover.is-open').forEach(p => {
                if (p !== this.popover) p.classList.remove('is-open');
            });
            document.querySelectorAll('.enhanced-custom-toggle-btn.active').forEach(b => {
                if (b !== this.customBtn) b.classList.remove('active');
            });

            this.updateCustomizerList();
            this.popover.classList.add('is-open');
            this.customBtn.classList.add('active');

            this.updateCustomizerPosition();
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

            if (btnRect.bottom < 0 || btnRect.top > viewportHeight || btnRect.right < 0 || btnRect.left > viewportWidth) {
                this.closeCustomizer();
                return;
            }

            const popoverWidth = Math.min(320, viewportWidth - 24);
            this.popover.style.width = `${popoverWidth}px`;

            let left = btnRect.right - popoverWidth;
            if (left + popoverWidth > viewportWidth - 12) {
                left = viewportWidth - 12 - popoverWidth;
            }
            if (left < 12) {
                left = 12;
            }

            const gap = 6;
            const spaceBelow = viewportHeight - btnRect.bottom - gap - 12;
            const spaceAbove = btnRect.top - gap - 12;

            if (spaceBelow >= 250 || spaceBelow >= spaceAbove) {
                this.popover.setAttribute('data-placement', 'bottom');
                this.popover.style.left = `${Math.round(left)}px`;
                this.popover.style.top = `${Math.round(btnRect.bottom + gap)}px`;
                this.popover.style.bottom = 'auto';
                this.popover.style.maxHeight = `${Math.round(Math.max(160, spaceBelow))}px`;
            } else {
                this.popover.setAttribute('data-placement', 'top');
                this.popover.style.left = `${Math.round(left)}px`;
                this.popover.style.top = 'auto';
                this.popover.style.bottom = `${Math.round(viewportHeight - btnRect.top + gap)}px`;
                this.popover.style.maxHeight = `${Math.round(Math.max(160, spaceAbove))}px`;
            }
        }

        bindPopoverDismissEvents() {
            this.unbindPopoverDismissEvents();

            this.onScrollOrResizeBound = () => {
                this.updateCustomizerPosition();
            };
            window.addEventListener('resize', this.onScrollOrResizeBound, { passive: true });
            window.addEventListener('scroll', this.onScrollOrResizeBound, { passive: true, capture: true });

            this.onOutsideClickBound = (e) => {
                if (this.popover && !this.popover.contains(e.target) && this.customBtn && !this.customBtn.contains(e.target)) {
                    this.closeCustomizer();
                }
            };
            setTimeout(() => {
                document.addEventListener('click', this.onOutsideClickBound);
            }, 0);

            this.onKeyDownBound = (e) => {
                if (e.key === 'Escape' || e.key === 'Esc') {
                    this.closeCustomizer();
                }
            };
            document.addEventListener('keydown', this.onKeyDownBound);

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
                item.draggable = !col.isAction;

                const isOnlyVisible = col.visible && visibleCount <= 1;
                const disableCheckbox = col.required || col.isAction || isOnlyVisible;

                item.innerHTML = `
                    <span class="enhanced-customizer-drag" title="${col.isAction ? 'Cột cố định không thể kéo thả' : 'Kéo để đổi thứ tự'}">
                        <i class="bi ${col.isAction ? 'bi-lock-fill text-muted' : 'bi-grip-vertical'}"></i>
                    </span>
                    <input type="checkbox" class="enhanced-customizer-checkbox" ${col.visible ? 'checked' : ''} ${disableCheckbox ? 'disabled' : ''} title="${disableCheckbox ? 'Không thể ẩn cột này' : 'Bật/tắt hiển thị'}">
                    <span class="enhanced-customizer-label" title="${col.title}">${col.title} ${col.isAction ? '<small class="text-primary fw-semibold">(Cố định phải)</small>' : (col.required ? '<small class="text-muted">(Bắt buộc)</small>' : '')}</span>
                    <div class="enhanced-customizer-actions">
                        ${!col.isAction ? `
                        <button type="button" class="enhanced-customizer-action-btn enhanced-move-up-btn" title="Di chuyển lên trên" ${index === 0 ? 'disabled style="opacity: 0.3;"' : ''}>
                            <i class="bi bi-chevron-up"></i>
                        </button>
                        <button type="button" class="enhanced-customizer-action-btn enhanced-move-down-btn" title="Di chuyển xuống dưới" ${index >= this.columns.length - 2 ? 'disabled style="opacity: 0.3;"' : ''}>
                            <i class="bi bi-chevron-down"></i>
                        </button>
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

                const pinBtn = item.querySelector('.enhanced-pin-col-btn');
                if (pinBtn) {
                    pinBtn.addEventListener('click', () => {
                        this.togglePinColumn(col.key);
                    });
                }

                if (!col.isAction) {
                    this.setupDragAndDrop(item, index);
                }

                body.appendChild(item);
            });
        }

        moveColumn(fromIndex, toIndex) {
            const lastDataIndex = this.columns.some(c => c.isAction) ? this.columns.length - 2 : this.columns.length - 1;
            if (toIndex < 0 || toIndex > lastDataIndex || fromIndex > lastDataIndex) return;

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
            window.addEventListener('resize', () => {
                this.updatePinnedOffsets();
            }, { passive: true });
        }

        /* 10. Trợ năng bàn phím và thiết bị cảm ứng */
        setupTouchAndKeyboardAccessibility() {
            // Cột Thao tác đã cố định ở mép phải (sticky right) nên luôn trực quan cho cả phím và cảm ứng
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
