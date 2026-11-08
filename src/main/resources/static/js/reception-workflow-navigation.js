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

            const replacement = document.importNode(nextContent, true);
            replacement.id = 'reception-content-area';
            replacement.classList.add('reception-content-enter');
            currentContent.replaceWith(replacement);
            document.title = parsed.title || document.title;
            if (pushState) window.history.pushState({receptionWorkflow: true}, '', targetUrl.href);
            markCurrentStep(pathOf(targetUrl.href), replacement);
            await initialiseTargetScripts(parsed);
            document.dispatchEvent(new CustomEvent('reception:content-loaded', {detail: {url: targetUrl.href}}));
            replacement.addEventListener('animationend', () => replacement.classList.remove('reception-content-enter'), {once: true});
            window.scrollTo({top: 0, behavior: 'smooth'});
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
