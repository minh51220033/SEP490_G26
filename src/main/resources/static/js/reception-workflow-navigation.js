(() => {
    const workflowPaths = new Set([
        '/reception/appointments', '/reception/check-in', '/reception/dispatch', '/reception/queue'
    ]);
    let navigating = false;
    let bootstrapReady;

    function pathOf(url) {
        return new URL(url, window.location.origin).pathname.replace(/\/$/, '') || '/';
    }

    function isWorkflowUrl(url) {
        return workflowPaths.has(pathOf(url));
    }

    function ensureStyles() {
        if (document.getElementById('reception-workflow-navigation-styles')) return;
        const link = document.createElement('link');
        link.id = 'reception-workflow-navigation-styles';
        link.rel = 'stylesheet';
        link.href = '/css/reception-workflow-navigation.css';
        document.head.appendChild(link);
    }

    async function ensureTargetStylesheets(parsed) {
        const links = [...parsed.querySelectorAll('link[rel="stylesheet"][href]')];
        const tasks = links.map(link => {
            const href = link.getAttribute('href');
            if (!href) return Promise.resolve();
            const targetPath = pathOf(href);
            const alreadyInHead = [...document.head.querySelectorAll('link[rel="stylesheet"][href]')]
                .some(el => pathOf(el.getAttribute('href')) === targetPath);
            if (alreadyInHead) return Promise.resolve();
            return new Promise(resolve => {
                const headLink = document.createElement('link');
                headLink.rel = 'stylesheet';
                headLink.href = href;
                headLink.onload = () => resolve();
                headLink.onerror = () => resolve();
                document.head.appendChild(headLink);
                setTimeout(resolve, 200);
            });
        });
        await Promise.all(tasks);
    }

    function syncSidebarAndHeader(parsed, targetPath) {
        const newTopbarTitle = parsed.querySelector('.app-topbar h1, .app-topbar .topbar-title, header h1');
        const curTopbarTitle = document.querySelector('.app-topbar h1, .app-topbar .topbar-title, header h1');
        if (newTopbarTitle && curTopbarTitle) {
            curTopbarTitle.textContent = newTopbarTitle.textContent;
        }
        const incomingSidebar = parsed.querySelector('aside.app-sidebar');
        const currentSidebar = document.querySelector('aside.app-sidebar');
        if (incomingSidebar && currentSidebar) {
            currentSidebar.innerHTML = incomingSidebar.innerHTML;
        }
    }

    function ensureBootstrap() {
        if (window.bootstrap) return Promise.resolve();
        if (bootstrapReady) return bootstrapReady;
        const existing = [...document.scripts].find(script => script.src.includes('bootstrap.bundle.min.js'));
        bootstrapReady = new Promise((resolve, reject) => {
            const script = existing || document.createElement('script');
            const complete = () => window.bootstrap ? resolve() : reject(new Error('Bootstrap chưa sẵn sàng.'));
            script.addEventListener('load', complete, {once: true});
            script.addEventListener('error', () => reject(new Error('Không thể tải Bootstrap.')), {once: true});
            if (!existing) {
                script.src = 'https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/js/bootstrap.bundle.min.js';
                document.body.appendChild(script);
            }
        });
        return bootstrapReady;
    }

    function markCurrentStep(path, scope = document) {
        scope.querySelectorAll('[data-reception-stepper] a[href]').forEach(link => {
            const current = pathOf(link.href) === path;
            link.toggleAttribute('aria-current', current);
            link.classList.toggle('active', current && link.classList.contains('dispatch-step-pill'));
        });
    }

    function loadScript(source) {
        return new Promise((resolve, reject) => {
            const script = document.createElement('script');
            script.src = source;
            script.async = false;
            script.dataset.receptionDynamic = 'true';
            script.onload = resolve;
            script.onerror = () => reject(new Error(`Không thể tải ${source}`));
            document.body.appendChild(script);
        });
    }

    function runInlineScript(source) {
        const script = document.createElement('script');
        script.dataset.receptionDynamic = 'true';
        script.textContent = source;
        document.body.appendChild(script);
        script.remove();
    }

    async function initialiseTargetScripts(parsed) {
        const configs = [...parsed.querySelectorAll('script[type="application/json"]')];
        configs.forEach(config => {
            if (!config.id) return;
            document.getElementById(config.id)?.remove();
            const copy = document.importNode(config, true);
            copy.dataset.receptionDynamic = 'true';
            document.body.appendChild(copy);
        });

        const pageScripts = [...parsed.querySelectorAll('script[src]')]
            .map(script => script.getAttribute('src'))
            .filter(source => source && /^\/js\/reception-(appointments|dispatch|queue)\.js(?:\?|$)/.test(source));
        for (const source of pageScripts) await loadScript(source);

        [...parsed.querySelectorAll('script:not([src]):not([type]), script[type="text/javascript"]')]
            .map(script => script.textContent.trim())
            .filter(Boolean)
            .forEach(runInlineScript);
    }

    async function navigate(url, pushState) {
        const targetUrl = new URL(url, window.location.origin);
        if (!isWorkflowUrl(targetUrl.href) || navigating) return;
        navigating = true;
        document.dispatchEvent(new CustomEvent('reception:content-will-change'));
        try {
            await ensureBootstrap();
            const response = await fetch(targetUrl.href, {
                credentials: 'same-origin',
                headers: {'X-Requested-With': 'XMLHttpRequest', 'Accept': 'text/html'}
            });
            if (!response.ok) throw new Error(`Không thể tải màn hình (${response.status})`);
            const parsed = new DOMParser().parseFromString(await response.text(), 'text/html');
            const nextContent = parsed.querySelector('main.app-content');
            const currentContent = document.getElementById('reception-content-area');
            if (!nextContent || !currentContent) throw new Error('Không tìm thấy vùng nội dung tiếp đón.');

            await ensureTargetStylesheets(parsed);

            // Clean up any open Bootstrap modal backdrop before swapping content
            document.querySelectorAll('.modal-backdrop').forEach(el => el.remove());
            document.body.classList.remove('modal-open');
            document.body.style.removeProperty('overflow');
            document.body.style.removeProperty('padding-right');

            const replacement = document.importNode(nextContent, true);
            replacement.id = 'reception-content-area';
            replacement.classList.add('reception-content-enter');
            currentContent.replaceWith(replacement);
            document.title = parsed.title || document.title;
            syncSidebarAndHeader(parsed, pathOf(targetUrl.href));
            if (pushState) window.history.pushState({receptionWorkflow: true}, '', targetUrl.href);
            markCurrentStep(pathOf(targetUrl.href), replacement);
            try {
                await initialiseTargetScripts(parsed);
            } catch (scriptErr) {
                console.warn('Reception workflow script warning:', scriptErr);
            }
            document.dispatchEvent(new CustomEvent('reception:content-loaded', {detail: {url: targetUrl.href}}));
            replacement.addEventListener('animationend', () => replacement.classList.remove('reception-content-enter'), {once: true});
            window.scrollTo({top: 0, behavior: 'instant'});
        } catch (error) {
            // The normal browser navigation remains a reliable fallback for an unavailable fragment.
            window.location.assign(targetUrl.href);
        } finally {
            navigating = false;
        }
    }

    function interceptStepper(event) {
        const link = event.target.closest('[data-reception-stepper] a[href]');
        if (!link || event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        if (!isWorkflowUrl(link.href)) return;
        event.preventDefault();
        navigate(link.href, true);
    }

    ensureStyles();
    document.addEventListener('click', interceptStepper);
    window.addEventListener('popstate', () => {
        if (isWorkflowUrl(window.location.href)) navigate(window.location.href, false);
    });
    markCurrentStep(pathOf(window.location.href));
})();
