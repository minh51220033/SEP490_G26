/**
 * ============================================================================
 * CV-DCMS FINANCE UNIFIED SMOOTH TAB SWITCHER
 * Giữ cố định Header + Thanh chuyển nhóm [Khách hàng | Phòng khám] + Tabs,
 * chỉ hoán đổi vùng nội dung bên dưới kèm prefetch cache khi di chuột giúp
 * chuyển tab Tài chính mượt tức thì như Quản lý kho vật tư.
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
    const prefetchCache = new Map();
    const CACHE_TTL_MS = 15000;
    const PAGE_MAIN_CLASSES = ['cashier-page', 'expense-income-page', 'patient-finance-dashboard'];

    const GLOBAL_SCRIPT_RE = /bootstrap\.bundle|\/js\/(layout|chat-widget|sidebar|header|enhanced-table|cashier-money-input|finance-signature|finance-tabs|cashier-tabs|expense-income-tabs)\.js/i;
    const EXTRA_OUTSIDE_SELECTOR = '.modal:not(#changePasswordModal), .fin-modal-backdrop, .fin-modal-overlay, #paymentSuccessToast, template';

    function normalizePath(urlStr) {
        try {
            return new URL(urlStr, window.location.origin).pathname;
        } catch (e) {
            return String(urlStr || '');
        }
    }

    function isSameOriginUrl(urlStr) {
        try {
            return new URL(urlStr, window.location.origin).origin === window.location.origin;
        } catch (e) {
            return false;
        }
    }

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
            loadPromises.push(new Promise(resolve => {
                const timer = setTimeout(resolve, 1200);
                newLink.addEventListener('load', () => { clearTimeout(timer); resolve(); }, { once: true });
                newLink.addEventListener('error', () => { clearTimeout(timer); resolve(); }, { once: true });
            }));
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

    function reinitializeSharedModules(scopeEl) {
        if (typeof window.setupUniversalEnterpriseTable === 'function') {
            try { window.setupUniversalEnterpriseTable(); } catch (e) { console.warn(e); }
        }
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
            try { window.initFinanceQuickDateSelects(scopeEl || document); } catch (e) { console.warn(e); }
        }
    }

    function applyOptimisticActiveState(clickedLink) {
        const wrapper = document.getElementById('financeTabsWrapper');
        if (!wrapper || !clickedLink) return;
        if (clickedLink.classList.contains('finance-subtab-btn')) {
            wrapper.querySelectorAll('.finance-subtab-btn').forEach(el => {
                const isTarget = el === clickedLink;
                el.classList.toggle('active', isTarget);
            });
        } else if (clickedLink.classList.contains('finance-group-btn')) {
            wrapper.querySelectorAll('.finance-group-btn').forEach(el => {
                const isTarget = el === clickedLink;
                el.classList.toggle('active', isTarget);
                el.setAttribute('aria-selected', isTarget ? 'true' : 'false');
            });
        }
    }

    function syncMainPageClasses(currentMain, newMain) {
        if (!currentMain || !newMain) return;
        PAGE_MAIN_CLASSES.forEach(cls => {
            currentMain.classList.toggle(cls, newMain.classList.contains(cls));
        });
    }

    function syncHeaderPartial(newWrapper, finalUrl) {
        const curWrapper = document.getElementById('financeTabsWrapper');
        if (!curWrapper || !newWrapper) return;

        // 1. Cập nhật nút hành động bên phải (Lập phiếu chi mới / Làm mới)
        const curBtns = curWrapper.querySelector('#financeHeaderActionButtons');
        const newBtns = newWrapper.querySelector('#financeHeaderActionButtons');
        if (curBtns && newBtns) {
            curBtns.innerHTML = newBtns.innerHTML;
        }

        // 2. Đồng bộ trạng thái active 2 nút chuyển nhóm [ Khách hàng | Phòng khám ]
        const curGroups = curWrapper.querySelectorAll('.finance-group-btn');
        const newGroups = newWrapper.querySelectorAll('.finance-group-btn');
        if (curGroups.length === newGroups.length) {
            curGroups.forEach((btn, idx) => {
                const isActive = newGroups[idx].classList.contains('active');
                btn.classList.toggle('active', isActive);
                btn.setAttribute('aria-selected', isActive ? 'true' : 'false');
            });
        }

        // 3. Nếu chuyển cùng nhóm -> chỉ cập nhật class .active trên hàng tab; nếu đổi nhóm -> thay hàng .finance-subtabs-nav
        const curNav = curWrapper.querySelector('.finance-subtabs-nav');
        const newNav = newWrapper.querySelector('.finance-subtabs-nav');
        if (curNav && newNav) {
            const curLabel = curNav.getAttribute('aria-label') || '';
            const newLabel = newNav.getAttribute('aria-label') || '';
            if (curLabel === newLabel) {
                const newActiveHref = newNav.querySelector('.finance-subtab-btn.active')?.getAttribute('href');
                const targetPath = newActiveHref ? normalizePath(newActiveHref) : normalizePath(finalUrl);
                curNav.querySelectorAll('.finance-subtab-btn').forEach(btn => {
                    btn.classList.toggle('active', normalizePath(btn.getAttribute('href')) === targetPath);
                });
            } else {
                curNav.replaceWith(document.importNode(newNav, true));
            }
        } else if (!curNav && newNav) {
            curWrapper.appendChild(document.importNode(newNav, true));
        }
    }

    async function fetchFinanceHtml(url, signal) {
        const cached = prefetchCache.get(url);
        if (cached && (Date.now() - cached.ts) < CACHE_TTL_MS) {
            return { html: cached.html, finalUrl: cached.finalUrl };
        }
        const response = await fetch(url, {
            method: 'GET',
            credentials: 'same-origin',
            headers: {
                'X-Requested-With': 'FinancePjax',
                'Accept': 'text/html,application/xhtml+xml'
            },
            signal
        });
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        const finalUrl = response.url || url;
        if (normalizePath(finalUrl).startsWith('/login')) {
            return { redirectLogin: finalUrl };
        }
        const html = await response.text();
        if (new URL(url, window.location.origin).search === '') {
            prefetchCache.set(url, { html, finalUrl, ts: Date.now() });
        }
        return { html, finalUrl };
    }

    function prefetchFinanceUrl(rawHref) {
        if (!rawHref || rawHref === '#' || rawHref.startsWith('javascript:')) return;
        const absUrl = new URL(rawHref, window.location.origin).toString();
        if (!isSameOriginUrl(absUrl)) return;
        if (absUrl === window.location.href) return;
        const cached = prefetchCache.get(absUrl);
        if (cached && (Date.now() - cached.ts) < CACHE_TTL_MS) return;

        fetch(absUrl, {
            method: 'GET',
            credentials: 'same-origin',
            headers: { 'X-Requested-With': 'FinancePjax', 'Accept': 'text/html' }
        }).then(res => {
            if (!res.ok) return null;
            const finalUrl = res.url || absUrl;
            if (normalizePath(finalUrl).startsWith('/login')) return null;
            return res.text().then(html => ({ html, finalUrl }));
        }).then(data => {
            if (data) prefetchCache.set(absUrl, { html: data.html, finalUrl: data.finalUrl, ts: Date.now() });
        }).catch(() => {});
    }

    async function navigateFinancePjax(targetUrl, pushState = true, clickedLink = null, bypassCache = false) {
        if (!isSameOriginUrl(targetUrl)) {
            window.location.href = targetUrl;
            return;
        }

        const currentMain = document.querySelector('main.app-content');
        if (!currentMain) {
            window.location.href = targetUrl;
            return;
        }

        if (bypassCache) {
            prefetchCache.delete(targetUrl);
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

        const activeContentZone = document.getElementById('cashierTabContent') || document.getElementById('expenseIncomeTabContent');
        let loadingTimer = setTimeout(() => {
            if (requestId === activeRequestId && activeContentZone) {
                activeContentZone.classList.add('fin-pjax-loading', 'is-loading');
            }
        }, 120);

        try {
            const result = await fetchFinanceHtml(targetUrl, activeAbortController ? activeAbortController.signal : undefined);
            clearTimeout(loadingTimer);

            if (requestId !== activeRequestId) return;

            if (result.redirectLogin) {
                window.location.href = result.redirectLogin;
                return;
            }

            const finalUrl = result.finalUrl || targetUrl;
            const parser = new DOMParser();
            const doc = parser.parseFromString(result.html, 'text/html');
            const newMain = doc.querySelector('main.app-content');
            const newWrapper = doc.getElementById('financeTabsWrapper');
            const newContentZone = doc.getElementById('cashierTabContent') || doc.getElementById('expenseIncomeTabContent');

            if (!newMain || !newWrapper || !newContentZone) {
                window.location.href = finalUrl;
                return;
            }

            await syncHeadStylesheets(doc.head);
            if (requestId !== activeRequestId) return;

            cleanupOpenOverlays();

            const collectedScripts = [];
            const newAppShell = doc.querySelector('.app-shell');

            newContentZone.querySelectorAll('script').forEach(sc => {
                collectedScripts.push(sc);
                sc.remove();
            });

            newMain.querySelectorAll('script').forEach(sc => {
                if (!newContentZone.contains(sc) && !newWrapper.contains(sc)) {
                    const src = sc.getAttribute('src');
                    if (!src || !GLOBAL_SCRIPT_RE.test(src)) {
                        collectedScripts.push(sc);
                    }
                }
            });

            const outsideNodes = [];
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

            // Xóa các alert cũ ở mức page trước khi đồng bộ alert mới (nếu có)
            currentMain.querySelectorAll(':scope > .alert').forEach(el => el.remove());
            const curWrapper = document.getElementById('financeTabsWrapper');
            if (curWrapper) {
                newMain.querySelectorAll(':scope > .alert').forEach(alertEl => {
                    currentMain.insertBefore(document.importNode(alertEl, true), curWrapper);
                });
            }

            syncMainPageClasses(currentMain, newMain);
            syncHeaderPartial(newWrapper, finalUrl);

            // Chỉ hoán đổi vùng nội dung bên dưới #financeTabsWrapper để Header & Tabs đứng yên tuyệt đối
            if (activeContentZone) {
                activeContentZone.classList.remove('fin-pjax-loading', 'is-loading');
                if (activeContentZone.id === newContentZone.id) {
                    activeContentZone.className = newContentZone.className;
                    activeContentZone.innerHTML = newContentZone.innerHTML;
                } else {
                    const importedZone = document.importNode(newContentZone, true);
                    activeContentZone.replaceWith(importedZone);
                }
            } else {
                currentMain.innerHTML = newMain.innerHTML;
            }

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

            await executeCollectedScripts(collectedScripts);
            if (requestId !== activeRequestId) return;

            reinitializeSharedModules(document.getElementById('cashierTabContent') || document.getElementById('expenseIncomeTabContent') || currentMain);
        } catch (err) {
            clearTimeout(loadingTimer);
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

    document.addEventListener('mouseenter', function (e) {
        const tab = e.target && e.target.closest ? e.target.closest('#financeTabsWrapper a.finance-subtab-btn, #financeTabsWrapper a.finance-group-btn') : null;
        if (tab) {
            prefetchFinanceUrl(tab.getAttribute('href'));
        }
    }, true);

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
        const isHeaderRefresh = Boolean(link.closest('#financeTabsWrapper') && (link.classList.contains('fin-refresh-btn') || link.classList.contains('btn-outline-secondary')));
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
        navigateFinancePjax(parsedTarget.toString(), true, (isGroupTab || isSubTab) ? link : null, isHeaderRefresh);
    });

    document.addEventListener('submit', function (e) {
        const form = e.target;
        if (!form) return;

        if (String(form.method || 'get').toLowerCase() === 'post') {
            prefetchCache.clear();
            return;
        }

        if (e.defaultPrevented) return;
        if (!form.classList || !form.classList.contains('fin-filter-bar')) return;
        if (!form.closest('.finance-page')) return;
        if (!document.getElementById('financeTabsWrapper')) return;

        e.preventDefault();
        const actionAttr = form.getAttribute('action') || window.location.pathname;
        const target = new URL(actionAttr, window.location.origin);
        const params = new URLSearchParams(new FormData(form));
        target.search = params.toString();
        navigateFinancePjax(target.toString(), true, null, true);
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
            navigateFinancePjax(url.toString(), true, null, true);
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
