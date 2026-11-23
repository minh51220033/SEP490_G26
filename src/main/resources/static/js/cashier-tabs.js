/**
 * CV-DCMS CASHIER TABS (Compat shim - đã hợp nhất vào finance-tabs.js)
 */
(function () {
    'use strict';
    if (window.__finTabsPjaxInitialized) {
        return;
    }
    if (document.readyState !== 'loading' && typeof window.initFinanceQuickDateSelects === 'function') {
        window.initFinanceQuickDateSelects(document);
    }
})();
