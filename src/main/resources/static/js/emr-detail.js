(function () {
    'use strict';

    const page = document.querySelector('.emr-detail-page');
    if (!page) return;

    const documentButtons = Array.from(page.querySelectorAll('[data-doc-target]'));
    const documents = Array.from(page.querySelectorAll('[data-emr-document]'));
    const modeButtons = Array.from(page.querySelectorAll('[data-emr-mode]'));
    const titleTargets = Array.from(page.querySelectorAll('[data-current-doc-title]'));
    const params = new URLSearchParams(window.location.search);

    const toothStates = new Map();
    for (const diagnosis of (window.emrToothDiagnoses || [])) {
        const teeth = String(diagnosis.toothNumbers || '').match(/\b[1-4][1-8]\b/g) || [];
        for (const tooth of teeth) {
            const next = diagnosis.type === 'MAIN' ? 'main' : 'secondary';
            if (next === 'main' || !toothStates.has(tooth)) toothStates.set(tooth, next);
        }
    }
    try {
        const chart = typeof window.emrOdontogram === 'string'
            ? JSON.parse(window.emrOdontogram) : window.emrOdontogram;
        if (chart && typeof chart === 'object') {
            const entries = Array.isArray(chart) ? chart.map(item => [item.tooth || item.number, item.status]) : Object.entries(chart);
            for (const [tooth, value] of entries) {
                if (/^[1-4][1-8]$/.test(String(tooth)) && !toothStates.has(String(tooth))) {
                    toothStates.set(String(tooth), 'recorded');
                }
            }
        }
    } catch (_) { /* Bản ghi cũ có thể chứa sơ đồ ở định dạng khác. */ }
    page.querySelectorAll('.emr-tooth-chart [data-tooth]').forEach(cell => {
        const state = toothStates.get(cell.dataset.tooth);
        if (state) cell.classList.add('is-' + state);
    });

    function availableDocument(code) {
        return documents.some((item) => item.dataset.emrDocument === code);
    }

    function selectDocument(code, updateUrl) {
        const targetCode = availableDocument(code) ? code : (documents[0] && documents[0].dataset.emrDocument);
        if (!targetCode) return;
        documentButtons.forEach((button) => button.classList.toggle('is-active', button.dataset.docTarget === targetCode));
        documents.forEach((document) => document.classList.toggle('is-active', document.dataset.emrDocument === targetCode));
        const selectedButton = documentButtons.find((button) => button.dataset.docTarget === targetCode);
        titleTargets.forEach((target) => { target.textContent = selectedButton ? selectedButton.dataset.docTitle : targetCode; });
        if (updateUrl) {
            const next = new URL(window.location.href);
            next.searchParams.set('document', targetCode);
            window.history.replaceState({}, '', next);
        }
    }

    function selectMode(mode, updateUrl) {
        const editButton = modeButtons.find((button) => button.dataset.emrMode === 'edit');
        let targetMode = ['view', 'edit', 'print'].includes(mode) ? mode : 'view';
        if (targetMode === 'edit' && editButton && editButton.disabled) targetMode = 'view';
        page.classList.remove('emr-mode-view', 'emr-mode-edit', 'emr-mode-print');
        page.classList.add('emr-mode-' + targetMode);
        modeButtons.forEach((button) => button.classList.toggle('is-active', button.dataset.emrMode === targetMode));
        if (updateUrl) {
            const next = new URL(window.location.href);
            next.searchParams.set('mode', targetMode);
            window.history.replaceState({}, '', next);
        }
    }

    documentButtons.forEach((button) => button.addEventListener('click', () => selectDocument(button.dataset.docTarget, true)));
    modeButtons.forEach((button) => button.addEventListener('click', () => {
        if (!button.disabled) selectMode(button.dataset.emrMode, true);
    }));
    page.querySelectorAll('[data-emr-print]').forEach((button) => button.addEventListener('click', () => {
        selectMode('print', false);
        window.setTimeout(() => window.print(), 80);
    }));

    selectDocument(params.get('document') || (documentButtons[0] && documentButtons[0].dataset.docTarget), false);
    selectMode(params.get('mode') || 'view', false);
}());
