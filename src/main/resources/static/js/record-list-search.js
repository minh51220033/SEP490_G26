(function () {
    'use strict';
    document.querySelectorAll('[data-record-list]').forEach(root => {
        const form = root.querySelector('[data-live-search]');
        const input = form && form.querySelector('input[name="query"]');
        if (!input) return;
        let timer;
        let pending;
        let sequence = 0;
        input.addEventListener('input', () => {
            window.clearTimeout(timer);
            timer = window.setTimeout(async () => {
                if (pending) pending.abort();
                pending = new AbortController();
                const current = ++sequence;
                const url = new URL(form.action, window.location.href);
                new FormData(form).forEach((value, key) => {
                    if (String(value).trim()) url.searchParams.set(key, value);
                });
                url.searchParams.delete('page');
                root.classList.add('is-searching');
                try {
                    const response = await fetch(url, { signal: pending.signal, credentials: 'same-origin' });
                    if (!response.ok || current !== sequence) return;
                    const html = new DOMParser().parseFromString(await response.text(), 'text/html');
                    const fresh = html.querySelector('[data-record-list]');
                    if (!fresh || current !== sequence) return;
                    for (const selector of ['[data-list-results]', '[data-list-count]', '[data-filter-chips]']) {
                        const existing = root.querySelector(selector);
                        const replacement = fresh.querySelector(selector);
                        if (existing && replacement) existing.replaceWith(replacement);
                        else if (existing && !replacement) existing.remove();
                        else if (!existing && replacement && selector === '[data-filter-chips]') {
                            const anchor = form.closest('.emr-panel__head--filter') || form;
                            anchor.insertAdjacentElement('afterend', replacement);
                        }
                    }
                    window.history.replaceState({}, '', url);
                } catch (error) {
                    if (error.name !== 'AbortError') root.classList.add('search-error');
                } finally {
                    if (current === sequence) root.classList.remove('is-searching');
                }
            }, 300);
        });
    });
}());
