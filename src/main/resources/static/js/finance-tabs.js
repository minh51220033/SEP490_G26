/**
 * ============================================================================
 * CV-DCMS FINANCE UNIFIED PJAX TABS & QUICK DATE ROUTER
 * Chuyển đổi mượt giữa các tab (Tổng quan, Hóa đơn, Công nợ, Tiền tạm ứng,
 * Hoàn tiền, Lịch sử thanh toán, Chi phí vận hành, Thanh toán NCC, Bảng lương...)
 * và giữa 2 nhóm [ Khách hàng ] <-> [ Phòng khám ] không tải lại toàn trang.
 * ============================================================================
 */
(function () {
    'use strict';

    if (window.__finTabsPjaxInitialized) {
        return;
    }
    window.__finTabsPjaxInitialized = true;

    let activeRequestId = 0;
    let activeAbortController = null;
    const loadedScripts = window.__finLoadedScripts || (window.__finLoadedScripts = new Set());
    const initialStyles = window.__finInitialStyles || (window.__finInitialStyles = new Set());

    function normalizePath(urlStr) {
        try {
            const u = new URL(urlStr, window.location.origin);
            return u.pathname;
        } catch (e) {
            return String(urlStr || '');
        }
    }

    function isSameOriginUrl(urlStr) {
        try {
            const u = new URL(urlStr, window.location.origin);
            return u.origin === window.location.origin;
        } catch (e) {
            return false;
        }
    }

    const GLOBAL_SCRIPT_RE = /bootstrap\.bundle|\/js\/(layout|chat-widget|sidebar|header|enhanced-table|cashier-money-input|finance-signature|finance-tabs|cashier-tabs|expense-income-tabs)\.js/i;
    const EXTRA_OUTSIDE_SELECTOR = '.modal:not(#changePasswordModal), .fin-modal-backdrop, .fin-modal-overlay, #paymentSuccessToast, template';

    function recordCurrentDocumentAssets() {
        document.querySelectorAll('script[src]').forEach(s => {
            const path = normalizePath(s.getAttribute('src'));
            if (path) loadedScripts.add(path);
        });
        document.querySelectorAll('head style:not([data-fin-pjax-style])').forEach(st => {
            const txt = (st.textContent || '').trim();
            if (txt) initialStyles.add(txt);
        });
        document.querySelectorAll(EXTRA_OUTSIDE_SELECTOR).forEach(el => {
            if (!el.closest('.app-shell') && !el.closest('#changePasswordModal') && !el.hasAttribute('data-fin-pjax-extra')) {
                el.setAttribute('data-fin-pjax-extra', 'true');
            }
        });
    }

    function cleanupOpenOverlays() {
        document.querySelectorAll('.modal.show').forEach(modalEl => {
            if (window.bootstrap && window.bootstrap.Modal) {
                const inst = window.bootstrap.Modal.getInstance(modalEl);
                if (inst) {
                    try { inst.hide(); inst.dispose(); } catch (e) { /* ignore */ }
                }
            }
            modalEl.classList.remove('show');
            modalEl.style.display = 'none';
        });
        document.querySelectorAll('.modal-backdrop').forEach(bd => bd.remove());
        document.body.classList.remove('modal-open');
        document.body.style.removeProperty('overflow');
        document.body.style.removeProperty('padding-right');
    }

    function syncHeadStylesheets(docHead) {
        if (!docHead) return Promise.resolve();
        const existingLinks = new Set();
        document.head.querySelectorAll('link[rel="stylesheet"][href]').forEach(link => {
            existingLinks.add(normalizePath(link.getAttribute('href')));
        });

        const financeLink = Array.from(document.head.querySelectorAll('link[rel="stylesheet"][href]'))
            .find(l => /\/css\/finance\.css/i.test(l.getAttribute('href') || ''));

        const loadPromises = [];
        docHead.querySelectorAll('link[rel="stylesheet"][href]').forEach(link => {
            const href = link.getAttribute('href');
            const norm = normalizePath(href);
            if (!norm || existingLinks.has(norm)) return;
            existingLinks.add(norm);

            const newLink = document.createElement('link');
            newLink.rel = 'stylesheet';
            newLink.href = href;
            const p = new Promise(resolve => {
                const timer = setTimeout(resolve, 1200);
                newLink.addEventListener('load', () => { clearTimeout(timer); resolve(); }, { once: true });
                newLink.addEventListener('error', () => { clearTimeout(timer); resolve(); }, { once: true });
            });
            loadPromises.push(p);
            if (financeLink && financeLink.parentNode === document.head) {
                document.head.insertBefore(newLink, financeLink);
            } else {
                document.head.appendChild(newLink);
            }
        });

        document.head.querySelectorAll('style[data-fin-pjax-style="true"]').forEach(el => el.remove());
        docHead.querySelectorAll('style').forEach(st => {
            const txt = (st.textContent || '').trim();
            if (!txt || initialStyles.has(txt)) return;
            const clone = document.createElement('style');
            clone.setAttribute('data-fin-pjax-style', 'true');
            clone.textContent = st.textContent;
            document.head.appendChild(clone);
        });

        return loadPromises.length ? Promise.all(loadPromises) : Promise.resolve();
    }

    function syncSidebarActiveState(doc) {
        const currentSidebar = document.querySelector('.app-sidebar');
        const nextSidebar = doc.querySelector('.app-sidebar');
        if (!currentSidebar || !nextSidebar) return;

        const nextActiveHrefs = new Set();
        nextSidebar.querySelectorAll('a.active[href], a[href].nav-link.active').forEach(a => {
            nextActiveHrefs.add(normalizePath(a.getAttribute('href')));
        });
        currentSidebar.querySelectorAll('a[href]').forEach(a => {
            const p = normalizePath(a.getAttribute('href'));
            if (nextActiveHrefs.has(p)) {
                a.classList.add('active');
            } else {
                a.classList.remove('active');
            }
        });
    }

    function loadExternalScript(src) {
        const norm = normalizePath(src);
        if (!norm || loadedScripts.has(norm) || GLOBAL_SCRIPT_RE.test(norm)) {
            return Promise.resolve();
        }
        return new Promise(resolve => {
            const s = document.createElement('script');
            s.src = src;
            s.async = false;
            const timer = setTimeout(resolve, 2000);
            s.addEventListener('load', () => {
                clearTimeout(timer);
                loadedScripts.add(norm);
                resolve();
            }, { once: true });
            s.addEventListener('error', () => {
                clearTimeout(timer);
                resolve();
            }, { once: true });
            document.body.appendChild(s);
        });
    }

    async function executeCollectedScripts(scripts) {
        const domReadyCallbacks = [];
        const origDocAddEventListener = document.addEventListener;

        document.addEventListener = function (type, listener, options) {
            if (type === 'DOMContentLoaded' && typeof listener === 'function') {
                domReadyCallbacks.push(listener);
                return;
            }
            return origDocAddEventListener.call(this, type, listener, options);
        };

        try {
            for (const oldScript of scripts) {
                const src = oldScript.getAttribute('src');
                if (src) {
                    await loadExternalScript(src);
                } else if ((oldScript.textContent || '').trim()) {
                    const newScript = document.createElement('script');
                    Array.from(oldScript.attributes).forEach(attr => {
                        if (attr.name !== 'src') newScript.setAttribute(attr.name, attr.value);
                    });
                    newScript.textContent = oldScript.textContent;
                    try {
                        document.body.appendChild(newScript);
                        newScript.remove();
                    } catch (err) {
                        console.error('Lỗi thực thi script sau khi chuyển tab Tài chính:', err);
                    }
                }
            }
        } finally {
            document.addEventListener = origDocAddEventListener;
        }

        for (const cb of domReadyCallbacks) {
            try {
                cb.call(document, new Event('DOMContentLoaded'));
            } catch (err) {
                console.error('Lỗi callback DOMContentLoaded sau khi chuyển tab:', err);
            }
        }
    }

    function reinitializeSharedModules(mainEl) {
        if (typeof window.initializeEnhancedTables === 'function') {
            try { window.initializeEnhancedTables(); } catch (e) { console.warn(e); }
        }
        if (typeof window.initCashierMoneyInputs === 'function') {
            try { window.initCashierMoneyInputs(document); } catch (e) { console.warn(e); }
        }
        if (typeof window.initFinanceSignatures === 'function') {
            try { window.initFinanceSignatures(document); } catch (e) { console.warn(e); }
        }
        if (typeof window.initFinanceQuickDateSelects === 'function') {
            try { window.initFinanceQuickDateSelects(mainEl || document); } catch (e) { console.warn(e); }
        }
    }

    function applyOptimisticActiveState(clickedLink) {
        const wrapper = document.getElementById('financeTabsWrapper');
        if (!wrapper || !clickedLink) return;
        if (clickedLink.classList.contains('finance-subtab-btn')) {
            wrapper.querySelectorAll('.finance-subtab-btn').forEach(el => el.classList.remove('active'));
            clickedLink.classList.add('active');
        } else if (clickedLink.classList.contains('finance-group-btn')) {
            wrapper.querySelectorAll('.finance-group-btn').forEach(el => el.classList.remove('active'));
            clickedLink.classList.add('active');
        }
    }

    async function navigateFinancePjax(targetUrl, pushState = true, clickedLink = null) {
        if (!isSameOriginUrl(targetUrl)) {
            window.location.href = targetUrl;
            return;
        }

        const currentMain = document.querySelector('main.app-content');
        if (!currentMain) {
            window.location.href = targetUrl;
            return;
        }

        recordCurrentDocumentAssets();

        if (activeAbortController) {
            try { activeAbortController.abort(); } catch (e) { /* ignore */ }
        }
        activeAbortController = typeof AbortController === 'function' ? new AbortController() : null;
        const requestId = ++activeRequestId;

        if (clickedLink) {
            applyOptimisticActiveState(clickedLink);
        }

        const activeContentZone = document.getElementById('cashierTabContent') || document.getElementById('expenseIncomeTabContent') || currentMain;
        if (activeContentZone) {
            activeContentZone.classList.add('fin-pjax-loading', 'is-loading');
        }

        try {
            const response = await fetch(targetUrl, {
                method: 'GET',
                credentials: 'same-origin',
                headers: {
                    'X-Requested-With': 'FinancePjax',
                    'Accept': 'text/html,application/xhtml+xml'
                },
                signal: activeAbortController ? activeAbortController.signal : undefined
            });

            if (requestId !== activeRequestId) return;

            if (!response.ok) {
                throw new Error('HTTP ' + response.status);
            }

            const finalUrl = response.url || targetUrl;
            if (normalizePath(finalUrl).startsWith('/login')) {
                window.location.href = finalUrl;
                return;
            }

            const html = await response.text();
            if (requestId !== activeRequestId) return;

            const parser = new DOMParser();
            const doc = parser.parseFromString(html, 'text/html');
            const newMain = doc.querySelector('main.app-content');
            const newWrapper = doc.getElementById('financeTabsWrapper');

            if (!newMain || !newWrapper) {
                window.location.href = finalUrl;
                return;
            }

            await syncHeadStylesheets(doc.head);
            if (requestId !== activeRequestId) return;

            cleanupOpenOverlays();

            const collectedScripts = [];
            newMain.querySelectorAll('script').forEach(sc => {
                collectedScripts.push(sc);
                sc.remove();
            });

            const outsideNodes = [];
            const newAppShell = doc.querySelector('.app-shell');
            doc.body.querySelectorAll(EXTRA_OUTSIDE_SELECTOR).forEach(el => {
                if ((!newAppShell || !newAppShell.contains(el)) && !newMain.contains(el)) {
                    el.querySelectorAll('script').forEach(sc => {
                        collectedScripts.push(sc);
                        sc.remove();
                    });
                    outsideNodes.push(el);
                }
            });

            doc.body.querySelectorAll('script').forEach(sc => {
                if ((!newAppShell || !newAppShell.contains(sc)) && !newMain.contains(sc)) {
                    const src = sc.getAttribute('src');
                    if (!src || !GLOBAL_SCRIPT_RE.test(src)) {
                        collectedScripts.push(sc);
                    }
                }
            });

            document.querySelectorAll('[data-fin-pjax-extra="true"]').forEach(el => el.remove());

            currentMain.className = newMain.className;
            currentMain.innerHTML = newMain.innerHTML;

            const appShell = document.querySelector('.app-shell');
            const insertAnchor = appShell ? appShell.nextSibling : null;
            outsideNodes.forEach(node => {
                const clone = document.importNode(node, true);
                clone.setAttribute('data-fin-pjax-extra', 'true');
                if (insertAnchor) {
                    document.body.insertBefore(clone, insertAnchor);
                } else {
                    document.body.appendChild(clone);
                }
            });

            syncSidebarActiveState(doc);

            if (doc.title) {
                document.title = doc.title;
            }

            if (pushState) {
                window.history.pushState({ finPjax: true, url: finalUrl }, doc.title || '', finalUrl);
            }

            if (window.scrollY > 120) {
                window.scrollTo({ top: 0, behavior: 'instant' });
            }

            await executeCollectedScripts(collectedScripts);
            if (requestId !== activeRequestId) return;

            reinitializeSharedModules(currentMain);
        } catch (err) {
            if (err && err.name === 'AbortError') {
                return;
            }
            console.error('Lỗi chuyển tab Tài chính:', err);
            if (activeContentZone) {
                activeContentZone.classList.remove('fin-pjax-loading', 'is-loading');
            }
            window.location.href = targetUrl;
        }
    }

    document.addEventListener('click', function (e) {
        if (e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) {
            return;
        }

        const link = e.target.closest('a[href]');
        if (!link) return;
        if (link.target && link.target !== '_self') return;
        if (link.hasAttribute('download')) return;

        const href = link.getAttribute('href');
        if (!href || href === '#' || href.startsWith('javascript:')) return;

        const isGroupTab = Boolean(link.closest('#financeTabsWrapper') && link.classList.contains('finance-group-btn'));
        const isSubTab = Boolean(link.closest('#financeTabsWrapper') && link.classList.contains('finance-subtab-btn'));
        const isHeaderRefresh = Boolean(link.closest('#financeTabsWrapper') && link.classList.contains('btn-outline-secondary'));
        const isPagination = Boolean(link.closest('.finance-page .misa-pagination-footer') && link.classList.contains('misa-nav-btn') && !link.classList.contains('disabled'));
        const isFilterReset = Boolean(link.closest('.finance-page .fin-filter-container') && link.classList.contains('fin-btn-outline'));

        if (!isGroupTab && !isSubTab && !isHeaderRefresh && !isPagination && !isFilterReset) {
            return;
        }

        const currentUrl = window.location.pathname + window.location.search;
        const parsedTarget = new URL(href, window.location.origin);
        const targetFull = parsedTarget.pathname + parsedTarget.search;

        if ((isGroupTab || isSubTab) && targetFull === currentUrl) {
            e.preventDefault();
            return;
        }

        e.preventDefault();
        navigateFinancePjax(parsedTarget.toString(), true, (isGroupTab || isSubTab) ? link : null);
    });

    document.addEventListener('submit', function (e) {
        if (e.defaultPrevented) return;
        const form = e.target;
        if (!form || !form.classList || !form.classList.contains('fin-filter-bar')) return;
        if (!form.closest('.finance-page')) return;
        if (String(form.method || 'get').toLowerCase() !== 'get') return;
        if (!document.getElementById('financeTabsWrapper')) return;

        e.preventDefault();
        const actionAttr = form.getAttribute('action') || window.location.pathname;
        const target = new URL(actionAttr, window.location.origin);
        const params = new URLSearchParams(new FormData(form));
        target.search = params.toString();
        navigateFinancePjax(target.toString(), true);
    });

    window.addEventListener('popstate', function () {
        if (document.getElementById('financeTabsWrapper')) {
            navigateFinancePjax(window.location.href, false);
        }
    });

    window.financePjaxNavigate = function (url) {
        navigateFinancePjax(url, true);
    };

    window.changePageSize = function (newSize) {
        const url = new URL(window.location.href);
        url.searchParams.set('size', newSize);
        url.searchParams.set('page', '0');
        if (document.getElementById('financeTabsWrapper')) {
            navigateFinancePjax(url.toString(), true);
        } else {
            window.location.href = url.toString();
        }
    };

    function getQuickDateRange(preset) {
        const pad = (n) => String(n).padStart(2, '0');
        const toIsoDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
        const now = new Date();
        if (preset === 'today') {
            const todayStr = toIsoDate(now);
            return { from: todayStr, to: todayStr };
        }
        if (preset === 'yesterday') {
            const y = new Date(now);
            y.setDate(y.getDate() - 1);
            const yStr = toIsoDate(y);
            return { from: yStr, to: yStr };
        }
        if (preset === 'week') {
            const past = new Date(now);
            past.setDate(past.getDate() - 6);
            return { from: toIsoDate(past), to: toIsoDate(now) };
        }
        if (preset === 'month') {
            const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1);
            return { from: toIsoDate(startOfMonth), to: toIsoDate(now) };
        }
        if (preset === 'clear') {
            return { from: '', to: '' };
        }
        return null;
    }

    function detectQuickDatePreset(fromVal, toVal) {
        if (!fromVal || !toVal) return '';
        const presets = ['today', 'yesterday', 'week', 'month'];
        for (const preset of presets) {
            const range = getQuickDateRange(preset);
            if (range && range.from === fromVal && range.to === toVal) {
                return preset;
            }
        }
        return '';
    }

    function updateQuickDateSelectStyle(selectEl) {
        if (!selectEl) return;
        const hasVal = Boolean(selectEl.value);
        selectEl.classList.toggle('is-placeholder', !hasVal);
        selectEl.classList.toggle('has-value', hasVal);
    }

    window.setFinanceQuickDate = function (fromId, toId, preset) {
        const fromInput = document.getElementById(fromId);
        const toInput = document.getElementById(toId);
        if (!fromInput || !toInput) return;
        const range = getQuickDateRange(preset);
        if (!range) return;
        fromInput.value = range.from;
        toInput.value = range.to;
    };

    window.handleFinanceQuickDateChange = function (selectEl) {
        if (!selectEl) return;
        const fromId = selectEl.getAttribute('data-quick-date-from');
        const toId = selectEl.getAttribute('data-quick-date-to');
        const preset = selectEl.value;
        if (!preset) {
            updateQuickDateSelectStyle(selectEl);
            return;
        }
        window.setFinanceQuickDate(fromId, toId, preset);
        if (preset === 'clear') {
            selectEl.value = '';
        }
        updateQuickDateSelectStyle(selectEl);
    };

    window.initFinanceQuickDateSelects = function (root) {
        const scope = root || document;
        const selects = scope.querySelectorAll('.fin-quick-date-select[data-quick-date-from][data-quick-date-to]');
        selects.forEach(selectEl => {
            const fromId = selectEl.getAttribute('data-quick-date-from');
            const toId = selectEl.getAttribute('data-quick-date-to');
            const fromInput = document.getElementById(fromId);
            const toInput = document.getElementById(toId);

            const syncFromInputs = () => {
                if (!fromInput || !toInput) return;
                selectEl.value = detectQuickDatePreset(fromInput.value, toInput.value);
                updateQuickDateSelectStyle(selectEl);
            };

            syncFromInputs();

            if (fromInput && !fromInput.dataset.quickDateBound) {
                fromInput.dataset.quickDateBound = 'true';
                fromInput.addEventListener('change', syncFromInputs);
            }
            if (toInput && !toInput.dataset.quickDateBound) {
                toInput.dataset.quickDateBound = 'true';
                toInput.addEventListener('change', syncFromInputs);
            }
        });
    };

    function bootstrap() {
        recordCurrentDocumentAssets();
        window.initFinanceQuickDateSelects(document);
        if (!window.history.state || !window.history.state.finPjax) {
            window.history.replaceState({ finPjax: true, url: window.location.href }, document.title, window.location.href);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bootstrap);
    } else {
        bootstrap();
    }
})();
