/**
 * ============================================================================
 * CV-DCMS MEMBERSHIP & PROMOTIONS SMOOTH TAB SWITCHER
 * Giữ cố định Header + 3 tab (Thẻ bệnh nhân, Hạng thẻ & Tích điểm, Khuyến mãi),
 * chỉ hoán đổi #membershipTabContent kèm prefetch cache khi rê chuột giúp
 * chuyển tab mượt tức thì không giật trang.
 * ============================================================================
 */
(function () {
    'use strict';

    if (window.__membershipTabsPjaxInitialized) {
        return;
    }
    window.__membershipTabsPjaxInitialized = true;

    let activeRequestId = 0;
    let activeAbortController = null;
    const loadedScripts = window.__membershipLoadedScripts || (window.__membershipLoadedScripts = new Set());
    const initialStyles = window.__membershipInitialStyles || (window.__membershipInitialStyles = new Set());
    const prefetchCache = new Map();
    const CACHE_TTL_MS = 15000;

    const GLOBAL_SCRIPT_RE = /bootstrap\.bundle|\/js\/(layout|chat-widget|sidebar|header|enhanced-table|cashier-money-input|finance-signature|membership-tabs)\.js/i;
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
        document.querySelectorAll('head style:not([data-membership-pjax-style])').forEach(st => {
            const txt = (st.textContent || '').trim();
            if (txt) initialStyles.add(txt);
        });
        document.querySelectorAll(EXTRA_OUTSIDE_SELECTOR).forEach(el => {
            if (!el.closest('.app-shell') && !el.closest('#changePasswordModal') && !el.hasAttribute('data-membership-pjax-extra')) {
                el.setAttribute('data-membership-pjax-extra', 'true');
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
            document.head.appendChild(newLink);
        });

        document.head.querySelectorAll('style[data-membership-pjax-style="true"]').forEach(el => el.remove());
        docHead.querySelectorAll('style').forEach(st => {
            const txt = (st.textContent || '').trim();
            if (!txt || initialStyles.has(txt)) return;
            const clone = document.createElement('style');
            clone.setAttribute('data-membership-pjax-style', 'true');
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
        nextSidebar.querySelectorAll('a.is-active[href], a.active[href]').forEach(a => {
            nextActiveHrefs.add(normalizePath(a.getAttribute('href')));
        });
        currentSidebar.querySelectorAll('a[href]').forEach(a => {
            const p = normalizePath(a.getAttribute('href'));
            if (nextActiveHrefs.has(p)) {
                a.classList.add('is-active');
            } else {
                a.classList.remove('is-active');
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
                        console.error('Lỗi thực thi script Thành viên & Ưu đãi:', err);
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
                console.error('Lỗi callback DOMContentLoaded Thành viên & Ưu đãi:', err);
            }
        }
    }

    function reinitializeModules() {
        if (typeof window.setupUniversalEnterpriseTable === 'function') {
            try { window.setupUniversalEnterpriseTable(); } catch (e) { console.warn(e); }
        }
        if (typeof window.initializeEnhancedTables === 'function') {
            try { window.initializeEnhancedTables(); } catch (e) { console.warn(e); }
        }
    }

    function syncWrapperState(targetPath, newWrapper) {
        const wrapper = document.getElementById('membershipTabsWrapper');
        if (!wrapper) return;

        if (newWrapper) {
            const newActive = newWrapper.querySelector('.membership-tab-btn.active');
            const activeId = newActive ? newActive.id : null;
            wrapper.querySelectorAll('.membership-tab-btn').forEach(btn => {
                const matched = activeId ? btn.id === activeId : normalizePath(btn.getAttribute('href')) === targetPath;
                btn.classList.toggle('active', matched);
                if (matched) {
                    btn.setAttribute('aria-current', 'page');
                } else {
                    btn.removeAttribute('aria-current');
                }
            });
            const newRefresh = newWrapper.querySelector('#membershipRefreshBtn');
            const curRefresh = wrapper.querySelector('#membershipRefreshBtn');
            if (newRefresh && curRefresh) {
                curRefresh.setAttribute('href', newRefresh.getAttribute('href') || targetPath);
            }
            return;
        }

        wrapper.querySelectorAll('.membership-tab-btn').forEach(btn => {
            const matched = normalizePath(btn.getAttribute('href')) === targetPath;
            btn.classList.toggle('active', matched);
            if (matched) {
                btn.setAttribute('aria-current', 'page');
            } else {
                btn.removeAttribute('aria-current');
            }
        });
        const curRefresh = wrapper.querySelector('#membershipRefreshBtn');
        if (curRefresh) {
            curRefresh.setAttribute('href', targetPath);
        }
    }

    async function fetchMembershipHtml(url, signal) {
        const cached = prefetchCache.get(url);
        if (cached && (Date.now() - cached.ts) < CACHE_TTL_MS) {
            return { html: cached.html, finalUrl: cached.finalUrl };
        }
        const response = await fetch(url, {
            method: 'GET',
            credentials: 'same-origin',
            headers: {
                'X-Requested-With': 'MembershipPjax',
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

    function prefetchMembershipUrl(rawHref) {
        if (!rawHref || rawHref === '#' || rawHref.startsWith('javascript:')) return;
        const absUrl = new URL(rawHref, window.location.origin).toString();
        if (!isSameOriginUrl(absUrl) || absUrl === window.location.href) return;
        const cached = prefetchCache.get(absUrl);
        if (cached && (Date.now() - cached.ts) < CACHE_TTL_MS) return;

        fetch(absUrl, {
            method: 'GET',
            credentials: 'same-origin',
            headers: { 'X-Requested-With': 'MembershipPjax', 'Accept': 'text/html' }
        }).then(res => {
            if (!res.ok) return null;
            const finalUrl = res.url || absUrl;
            if (normalizePath(finalUrl).startsWith('/login')) return null;
            return res.text().then(html => ({ html, finalUrl }));
        }).then(data => {
            if (data) prefetchCache.set(absUrl, { html: data.html, finalUrl: data.finalUrl, ts: Date.now() });
        }).catch(() => {});
    }

    async function navigateMembershipPjax(targetUrl, pushState = true, clickedLink = null, bypassCache = false) {
        if (!isSameOriginUrl(targetUrl)) {
            window.location.href = targetUrl;
            return;
        }

        const currentContent = document.getElementById('membershipTabContent');
        if (!currentContent) {
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

        if (clickedLink && clickedLink.classList.contains('membership-tab-btn')) {
            syncWrapperState(normalizePath(targetUrl), null);
        }

        let loadingTimer = setTimeout(() => {
            if (requestId === activeRequestId) {
                currentContent.classList.add('membership-pjax-loading');
            }
        }, 120);

        try {
            const result = await fetchMembershipHtml(targetUrl, activeAbortController ? activeAbortController.signal : undefined);
            clearTimeout(loadingTimer);

            if (requestId !== activeRequestId) return;

            if (result.redirectLogin) {
                window.location.href = result.redirectLogin;
                return;
            }

            const finalUrl = result.finalUrl || targetUrl;
            const parser = new DOMParser();
            const doc = parser.parseFromString(result.html, 'text/html');
            const newContent = doc.getElementById('membershipTabContent');
            const newWrapper = doc.getElementById('membershipTabsWrapper');

            if (!newContent || !newWrapper) {
                window.location.href = finalUrl;
                return;
            }

            await syncHeadStylesheets(doc.head);
            if (requestId !== activeRequestId) return;

            cleanupOpenOverlays();

            const collectedScripts = [];
            newContent.querySelectorAll('script').forEach(sc => {
                collectedScripts.push(sc);
                sc.remove();
            });

            const newMain = doc.querySelector('main.app-content');
            const newAppShell = doc.querySelector('.app-shell');

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

            document.querySelectorAll('[data-membership-pjax-extra="true"]').forEach(el => el.remove());

            syncWrapperState(normalizePath(finalUrl), newWrapper);

            currentContent.classList.remove('membership-pjax-loading');
            currentContent.innerHTML = newContent.innerHTML;

            const appShell = document.querySelector('.app-shell');
            const insertAnchor = appShell ? appShell.nextSibling : null;
            outsideNodes.forEach(node => {
                const clone = document.importNode(node, true);
                clone.setAttribute('data-membership-pjax-extra', 'true');
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
                window.history.pushState({ membershipPjax: true, url: finalUrl }, doc.title || '', finalUrl);
            }

            await executeCollectedScripts(collectedScripts);
            if (requestId !== activeRequestId) return;

            reinitializeModules();
        } catch (err) {
            clearTimeout(loadingTimer);
            if (err && err.name === 'AbortError') {
                return;
            }
            console.error('Lỗi chuyển tab Thành viên & Ưu đãi:', err);
            currentContent.classList.remove('membership-pjax-loading');
            window.location.href = targetUrl;
        }
    }

    document.addEventListener('mouseenter', function (e) {
        const tab = e.target && e.target.closest ? e.target.closest('#membershipTabsWrapper .membership-tab-btn') : null;
        if (tab) {
            prefetchMembershipUrl(tab.getAttribute('href'));
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
        if (!document.getElementById('membershipTabsWrapper') || !document.getElementById('membershipTabContent')) return;

        const href = link.getAttribute('href');
        if (!href || href === '#' || href.startsWith('javascript:')) return;

        const norm = normalizePath(href);
        const isMembershipTab = Boolean(link.closest('#membershipTabsWrapper') && link.classList.contains('membership-tab-btn'));
        const isHeaderRefresh = Boolean(link.closest('#membershipTabsWrapper') && link.classList.contains('membership-refresh-btn'));
        const isCrossShortcut = Boolean(
            link.closest('#membershipTabContent .fin-header') &&
            (norm === '/reception/memberships' || norm === '/admin/membership-tiers' || norm === '/admin/promotions')
        );
        const isFilterReset = Boolean(link.closest('#membershipTabContent .fin-card') && link.classList.contains('fin-btn-outline') && norm === '/admin/promotions');

        if (!isMembershipTab && !isHeaderRefresh && !isCrossShortcut && !isFilterReset) {
            return;
        }

        const currentUrl = window.location.pathname + window.location.search;
        const parsedTarget = new URL(href, window.location.origin);
        const targetFull = parsedTarget.pathname + parsedTarget.search;

        if (isMembershipTab && targetFull === currentUrl) {
            e.preventDefault();
            return;
        }

        e.preventDefault();
        navigateMembershipPjax(parsedTarget.toString(), true, isMembershipTab ? link : null, isHeaderRefresh);
    });

    document.addEventListener('submit', function (e) {
        const form = e.target;
        if (!form) return;

        if (String(form.method || 'get').toLowerCase() === 'post') {
            prefetchCache.clear();
            return;
        }

        if (e.defaultPrevented) return;
        if (!form.closest('#membershipTabContent')) return;
        if (!document.getElementById('membershipTabsWrapper')) return;

        e.preventDefault();
        const actionAttr = form.getAttribute('action') || window.location.pathname;
        const target = new URL(actionAttr, window.location.origin);
        const params = new URLSearchParams(new FormData(form));
        target.search = params.toString();
        navigateMembershipPjax(target.toString(), true, null, true);
    });

    window.addEventListener('popstate', function () {
        if (document.getElementById('membershipTabsWrapper') && document.getElementById('membershipTabContent')) {
            navigateMembershipPjax(window.location.href, false);
        }
    });

    function bootstrap() {
        recordCurrentDocumentAssets();
        if (!window.history.state || !window.history.state.membershipPjax) {
            window.history.replaceState({ membershipPjax: true, url: window.location.href }, document.title, window.location.href);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', bootstrap);
    } else {
        bootstrap();
    }
})();
