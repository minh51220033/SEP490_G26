/**
 * ============================================================================
 * CV-DCMS CATALOG UNIFIED SMOOTH TAB SWITCHER
 * Giữ cố định Header + 4 Tab + CSS (table_style, catalog-management.css),
 * chỉ hoán đổi #catalogTabContent kèm prefetch cache khi di chuột giúp
 * chuyển tab gần như tức thì y như Quản lý kho vật tư.
 * ============================================================================
 */
(function () {
    'use strict';

    if (window.__catalogTabsPjaxInitialized) {
        return;
    }
    window.__catalogTabsPjaxInitialized = true;

    let activeRequestId = 0;
    let activeAbortController = null;
    const loadedScripts = window.__catalogLoadedScripts || (window.__catalogLoadedScripts = new Set());
    const prefetchCache = new Map();
    const CACHE_TTL_MS = 15000;
    const PAGE_BODY_CLASSES = [
        'service-category-page',
        'service-catalog-page',
        'medication-catalog-page',
        'icd10-catalog-page'
    ];

    const GLOBAL_SCRIPT_RE = /bootstrap\.bundle|\/js\/(layout|chat-widget|sidebar|header|enhanced-table|cashier-money-input|finance-signature|catalog-tabs)\.js/i;
    const EXTRA_OUTSIDE_SELECTOR = '.modal:not(#changePasswordModal), template';

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
            const p = normalizePath(s.getAttribute('src'));
            if (p) loadedScripts.add(p);
        });
        document.querySelectorAll(EXTRA_OUTSIDE_SELECTOR).forEach(el => {
            if (!el.closest('.app-shell') && !el.closest('#changePasswordModal') && !el.hasAttribute('data-catalog-pjax-extra')) {
                el.setAttribute('data-catalog-pjax-extra', 'true');
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
                        console.error('Lỗi thực thi script sau khi chuyển tab Danh mục:', err);
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
                console.error('Lỗi callback DOMContentLoaded sau khi chuyển tab Danh mục:', err);
            }
        }
    }

    function reinitializeCatalogModules() {
        if (typeof window.setupUniversalEnterpriseTable === 'function') {
            try { window.setupUniversalEnterpriseTable(); } catch (e) { console.warn(e); }
        }
        if (typeof window.initializeEnhancedTables === 'function') {
            try { window.initializeEnhancedTables(); } catch (e) { console.warn(e); }
        }
        if (typeof window.initServiceEditor === 'function') {
            try { window.initServiceEditor(); } catch (e) { console.warn(e); }
        }
    }

    function syncWrapperTabsState(targetPath, newWrapper) {
        const wrapper = document.getElementById('catalogTabsWrapper');
        if (!wrapper) return;

        if (newWrapper) {
            const newActive = newWrapper.querySelector('.catalog-tab-item.is-active');
            const activeId = newActive ? newActive.id : null;
            wrapper.querySelectorAll('.catalog-tab-item').forEach(item => {
                item.classList.toggle('is-active', activeId ? item.id === activeId : normalizePath(item.getAttribute('href')) === targetPath);
            });
            const nextRefresh = newWrapper.querySelector('.catalog-refresh-btn');
            const curRefresh = wrapper.querySelector('.catalog-refresh-btn');
            if (nextRefresh && curRefresh) {
                curRefresh.setAttribute('href', nextRefresh.getAttribute('href') || targetPath);
            }
            return;
        }

        wrapper.querySelectorAll('.catalog-tab-item').forEach(item => {
            const itemPath = normalizePath(item.getAttribute('href'));
            const matched = itemPath === targetPath;
            item.classList.toggle('is-active', matched);
        });
        const curRefresh = wrapper.querySelector('.catalog-refresh-btn');
        if (curRefresh) {
            curRefresh.setAttribute('href', targetPath);
        }
    }

    function syncPageBodyClass(nextBody) {
        if (!nextBody || !nextBody.classList) return;
        PAGE_BODY_CLASSES.forEach(cls => {
            document.body.classList.toggle(cls, nextBody.classList.contains(cls));
        });
    }

    async function fetchCatalogHtml(url, signal) {
        const cached = prefetchCache.get(url);
        if (cached && (Date.now() - cached.ts) < CACHE_TTL_MS) {
            return { html: cached.html, finalUrl: cached.finalUrl };
        }
        const response = await fetch(url, {
            method: 'GET',
            credentials: 'same-origin',
            headers: {
                'X-Requested-With': 'CatalogPjax',
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

    function prefetchCatalogUrl(rawHref) {
        if (!rawHref || rawHref === '#' || rawHref.startsWith('javascript:')) return;
        const absUrl = new URL(rawHref, window.location.origin).toString();
        if (!isSameOriginUrl(absUrl)) return;
        if (absUrl === window.location.href) return;
        const cached = prefetchCache.get(absUrl);
        if (cached && (Date.now() - cached.ts) < CACHE_TTL_MS) return;

        fetch(absUrl, {
            method: 'GET',
            credentials: 'same-origin',
            headers: { 'X-Requested-With': 'CatalogPjax', 'Accept': 'text/html' }
        }).then(res => {
            if (!res.ok) return null;
            const finalUrl = res.url || absUrl;
            if (normalizePath(finalUrl).startsWith('/login')) return null;
            return res.text().then(html => ({ html, finalUrl }));
        }).then(data => {
            if (data) prefetchCache.set(absUrl, { html: data.html, finalUrl: data.finalUrl, ts: Date.now() });
        }).catch(() => {});
    }

    async function navigateCatalogPjax(targetUrl, pushState = true, clickedLink = null, bypassCache = false) {
        if (!isSameOriginUrl(targetUrl)) {
            window.location.href = targetUrl;
            return;
        }

        const currentContainer = document.getElementById('catalogTabContent');
        if (!currentContainer) {
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

        if (clickedLink && clickedLink.classList.contains('catalog-tab-item')) {
            syncWrapperTabsState(normalizePath(targetUrl), null);
        }

        let loadingTimer = setTimeout(() => {
            if (requestId === activeRequestId) {
                currentContainer.classList.add('catalog-pjax-loading');
            }
        }, 120);

        try {
            const result = await fetchCatalogHtml(targetUrl, activeAbortController ? activeAbortController.signal : undefined);
            clearTimeout(loadingTimer);

            if (requestId !== activeRequestId) return;

            if (result.redirectLogin) {
                window.location.href = result.redirectLogin;
                return;
            }

            const finalUrl = result.finalUrl || targetUrl;
            const parser = new DOMParser();
            const doc = parser.parseFromString(result.html, 'text/html');
            const newContent = doc.getElementById('catalogTabContent');
            const newWrapper = doc.getElementById('catalogTabsWrapper');

            if (!newContent || !newWrapper) {
                window.location.href = finalUrl;
                return;
            }

            cleanupOpenOverlays();

            const collectedScripts = [];
            newContent.querySelectorAll('script').forEach(sc => {
                collectedScripts.push(sc);
                sc.remove();
            });

            const newMain = doc.querySelector('main.app-content');
            const newAppShell = doc.querySelector('.app-shell');

            // Bắt mọi script bên trong <main> nằm sau #catalogTabContent (nếu có)
            if (newMain) {
                newMain.querySelectorAll('script').forEach(sc => {
                    if (!newContent.contains(sc) && !newWrapper.contains(sc)) {
                        const src = sc.getAttribute('src');
                        if (!src || !GLOBAL_SCRIPT_RE.test(src)) {
                            collectedScripts.push(sc);
                        }
                    }
                });
            }

            const outsideNodes = [];
            doc.body.querySelectorAll(EXTRA_OUTSIDE_SELECTOR).forEach(el => {
                if ((!newAppShell || !newAppShell.contains(el)) && !newContent.contains(el)) {
                    el.querySelectorAll('script').forEach(sc => {
                        collectedScripts.push(sc);
                        sc.remove();
                    });
                    outsideNodes.push(el);
                }
            });

            doc.body.querySelectorAll('script').forEach(sc => {
                if ((!newAppShell || !newAppShell.contains(sc)) && !newContent.contains(sc)) {
                    const src = sc.getAttribute('src');
                    if (!src || !GLOBAL_SCRIPT_RE.test(src)) {
                        collectedScripts.push(sc);
                    }
                }
            });

            document.querySelectorAll('[data-catalog-pjax-extra="true"]').forEach(el => el.remove());

            syncPageBodyClass(doc.body);
            syncWrapperTabsState(normalizePath(finalUrl), newWrapper);

            currentContainer.classList.remove('catalog-pjax-loading');
            currentContainer.innerHTML = newContent.innerHTML;

            const appShell = document.querySelector('.app-shell');
            const insertAnchor = appShell ? appShell.nextSibling : null;
            outsideNodes.forEach(node => {
                const clone = document.importNode(node, true);
                clone.setAttribute('data-catalog-pjax-extra', 'true');
                if (insertAnchor) {
                    document.body.insertBefore(clone, insertAnchor);
                } else {
                    document.body.appendChild(clone);
                }
            });

            if (doc.title) {
                document.title = doc.title;
            }

            if (pushState) {
                window.history.pushState({ catalogPjax: true, url: finalUrl }, doc.title || '', finalUrl);
            }

            await executeCollectedScripts(collectedScripts);
            if (requestId !== activeRequestId) return;

            reinitializeCatalogModules();
        } catch (err) {
            clearTimeout(loadingTimer);
            if (err && err.name === 'AbortError') {
                return;
            }
            console.error('Lỗi chuyển tab Quản lý danh mục:', err);
            currentContainer.classList.remove('catalog-pjax-loading');
            window.location.href = targetUrl;
        }
    }

    document.addEventListener('mouseenter', function (e) {
        const tab = e.target && e.target.closest ? e.target.closest('#catalogTabsWrapper .catalog-tab-item') : null;
        if (tab) {
            prefetchCatalogUrl(tab.getAttribute('href'));
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
        if (!document.getElementById('catalogTabsWrapper') || !document.getElementById('catalogTabContent')) return;

        const href = link.getAttribute('href');
        if (!href || href === '#' || href.startsWith('javascript:')) return;

        const isCatalogTab = Boolean(link.closest('#catalogTabsWrapper') && link.classList.contains('catalog-tab-item'));
        const isHeaderRefresh = Boolean(link.closest('#catalogTabsWrapper') && link.classList.contains('catalog-refresh-btn'));
        const isKpiLink = Boolean(link.querySelector('.dv-kpi-card'));
        const isSegmentBtn = Boolean(link.closest('.dv-quick-segment') && link.classList.contains('dv-seg-btn'));
        const isPagination = Boolean(link.closest('.misa-pagination-footer') && link.classList.contains('misa-nav-btn') && !link.classList.contains('disabled'));
        const isFilterReset = Boolean(link.closest('.catalog-filter-panel') && link.classList.contains('btn-outline-secondary'));

        if (!isCatalogTab && !isHeaderRefresh && !isKpiLink && !isSegmentBtn && !isPagination && !isFilterReset) {
            return;
        }

        const currentUrl = window.location.pathname + window.location.search;
        const parsedTarget = new URL(href, window.location.origin);
        const targetFull = parsedTarget.pathname + parsedTarget.search;

        if (isCatalogTab && targetFull === currentUrl) {
            e.preventDefault();
            return;
        }

        e.preventDefault();
        navigateCatalogPjax(parsedTarget.toString(), true, isCatalogTab ? link : null, isHeaderRefresh);
    });

    document.addEventListener('submit', function (e) {
        const form = e.target;
        if (!form) return;

        if (String(form.method || 'get').toLowerCase() === 'post') {
            prefetchCache.clear();
            return;
        }

        if (e.defaultPrevented) return;
        if (!form.closest('.catalog-filter-panel')) return;
        if (!document.getElementById('catalogTabsWrapper') || !document.getElementById('catalogTabContent')) return;

        e.preventDefault();
        const actionAttr = form.getAttribute('action') || window.location.pathname;
        const target = new URL(actionAttr, window.location.origin);
        const params = new URLSearchParams(new FormData(form));
        target.search = params.toString();
        navigateCatalogPjax(target.toString(), true, null, true);
    });

    window.addEventListener('popstate', function () {
        if (document.getElementById('catalogTabsWrapper') && document.getElementById('catalogTabContent')) {
            navigateCatalogPjax(window.location.href, false);
        }
    });

    function bootstrap() {
        recordCurrentDocumentAssets();
        if (!window.history.state || !window.history.state.catalogPjax) {
            window.history.replaceState({ catalogPjax: true, url: window.location.href }, document.title, window.location.href);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bootstrap);
    } else {
        bootstrap();
    }
})();
