/**
 * ============================================================================
 * CV-DCMS CASHIER TABS (Cơ chế chuyển tab nhanh cho phân hệ Thu ngân)
 * Tự động tải nội dung tab qua GET fetch và cập nhật vùng #cashierTabContent
 * mà không cần tải lại toàn bộ trang hoặc mở cửa sổ mới.
 * ============================================================================
 */

(function () {
    'use strict';

    let activeCashierRequestId = 0;

    /**
     * Xác định tab tương ứng từ URL pathname
     */
    function resolveTabFromUrl(url) {
        try {
            const parsed = new URL(url, window.location.origin);
            const path = parsed.pathname;
            if (path.startsWith('/payments/dashboard') || path.startsWith('/payments/patient/')) {
                return 'dashboard';
            }
            if (path.startsWith('/invoices')) {
                return 'invoices';
            }
            if (path.startsWith('/payments/debts')) {
                return 'debts';
            }
            if (path.startsWith('/payments/deposits')) {
                return 'deposits';
            }
            if (path.startsWith('/payments/refunds')) {
                return 'refunds';
            }
            if (path === '/payments' || path.startsWith('/payments/')) {
                return 'payments';
            }
        } catch (e) {
            console.warn('Lỗi phân tích URL tab:', e);
        }
        return null;
    }

    /**
     * Cập nhật trạng thái active trên thanh tab
     */
    function updateActiveTabButton(targetTab) {
        const tabBtns = document.querySelectorAll('.cashier-tab-btn');
        tabBtns.forEach(btn => {
            const tabKey = btn.getAttribute('data-tab');
            if (tabKey === targetTab) {
                btn.classList.add('active');
            } else {
                btn.classList.remove('active');
            }
        });
    }

    /**
     * Tái kích hoạt các script và Enhanced Table sau khi hoán đổi tab content
     */
    function reinitializeContent(container) {
        if (!container) return;

        // 1. Thực thi các thẻ <script> nằm trong nội dung mới nạp
        const scripts = container.querySelectorAll('script');
        scripts.forEach(oldScript => {
            const newScript = document.createElement('script');
            Array.from(oldScript.attributes).forEach(attr => {
                newScript.setAttribute(attr.name, attr.value);
            });
            newScript.textContent = oldScript.textContent;
            oldScript.parentNode.replaceChild(newScript, oldScript);
        });

        // 2. Kích hoạt lại Enhanced Table (ghim cột, kéo thả độ rộng cột, toolbar)
        if (typeof window.initializeEnhancedTables === 'function') {
            try {
                window.initializeEnhancedTables();
            } catch (e) {
                console.warn('Cảnh báo khởi tạo Enhanced Table:', e);
            }
        }
    }

    /**
     * Nạp nội dung của tab mục tiêu qua GET fetch
     */
    function switchCashierTab(targetUrl, targetTab, pushState = true) {
        const contentContainer = document.getElementById('cashierTabContent');
        if (!contentContainer) {
            window.location.href = targetUrl;
            return;
        }

        const requestId = ++activeCashierRequestId;
        contentContainer.classList.add('is-loading');

        // Cập nhật tab active tạm thời ngay khi bấm để phản hồi tức thì
        if (targetTab) {
            updateActiveTabButton(targetTab);
        }

        fetch(targetUrl, {
            method: 'GET',
            headers: {
                'X-Requested-With': 'CashierTabSwitch'
            }
        })
            .then(res => {
                if (!res.ok) {
                    throw new Error('HTTP ' + res.status + ' ' + res.statusText);
                }
                return res.text();
            })
            .then(html => {
                // Ngăn chặn Race Condition: Nếu người dùng bấm liên tiếp nhiều tab,
                // chỉ hiển thị kết quả của tab được chọn cuối cùng.
                if (requestId !== activeCashierRequestId) {
                    return;
                }

                const parser = new DOMParser();
                const doc = parser.parseFromString(html, 'text/html');
                const newContent = doc.getElementById('cashierTabContent');

                if (!newContent) {
                    // Fallback nếu trang đích không có cấu trúc #cashierTabContent
                    window.location.href = targetUrl;
                    return;
                }

                // Dọn dẹp modal hoặc backdrop còn sót lại từ tab trước
                document.querySelectorAll('.modal-backdrop').forEach(bd => bd.remove());
                document.body.classList.remove('modal-open');
                document.body.style.removeProperty('overflow');
                document.body.style.removeProperty('padding-right');

                // Cập nhật vùng nội dung
                contentContainer.innerHTML = newContent.innerHTML;
                contentContainer.classList.remove('is-loading');

                // Cập nhật tiêu đề tài liệu nếu có
                if (doc.title) {
                    document.title = doc.title;
                }

                // Cập nhật URL trên thanh địa chỉ để hỗ trợ F5 và Back/Forward
                if (pushState) {
                    window.history.pushState({ cashierTab: targetTab, url: targetUrl }, '', targetUrl);
                }

                // Đảm bảo nút tab active đồng bộ chính xác
                const finalTab = targetTab || resolveTabFromUrl(targetUrl);
                if (finalTab) {
                    updateActiveTabButton(finalTab);
                }

                // Tái khởi tạo script, form handler và bảng tương tác
                reinitializeContent(contentContainer);
            })
            .catch(err => {
                console.error('Lỗi nạp tab thu ngân:', err);
                contentContainer.classList.remove('is-loading');
                // Nếu tải nội dung lỗi, cho phép mở URL gốc
                window.location.href = targetUrl;
            });
    }

    /**
     * Đăng ký sự kiện click trên các nút tab
     */
    function initCashierTabs() {
        const nav = document.getElementById('cashierTabsList');
        if (!nav) return;

        nav.addEventListener('click', function (e) {
            const link = e.target.closest('.cashier-tab-btn');
            if (!link) return;

            const targetUrl = link.getAttribute('href');
            if (!targetUrl || targetUrl === '#' || targetUrl.startsWith('javascript:')) {
                return;
            }

            // Kiểm tra nếu bấm vào chính tab hiện tại thì bỏ qua
            const currentFullUrl = window.location.pathname + window.location.search;
            const targetFullUrl = new URL(targetUrl, window.location.origin).pathname + new URL(targetUrl, window.location.origin).search;
            if (currentFullUrl === targetFullUrl) {
                e.preventDefault();
                return;
            }

            e.preventDefault();
            const targetTab = link.getAttribute('data-tab') || resolveTabFromUrl(targetUrl);
            switchCashierTab(targetUrl, targetTab, true);
        });

        // Xử lý sự kiện Back / Forward của trình duyệt
        window.addEventListener('popstate', function (e) {
            const currentUrl = window.location.href;
            const tabKey = (e.state && e.state.cashierTab) ? e.state.cashierTab : resolveTabFromUrl(currentUrl);
            if (tabKey) {
                switchCashierTab(currentUrl, tabKey, false);
            } else {
                window.location.reload();
            }
        });
    }

    /**
     * Điền nhanh khoảng ngày vào 2 input Từ ngày / Đến ngày cho các bộ lọc Thu ngân
     */
    window.setFinanceQuickDate = function (fromId, toId, preset) {
        const fromInput = document.getElementById(fromId);
        const toInput = document.getElementById(toId);
        if (!fromInput || !toInput) return;

        const pad = (n) => String(n).padStart(2, '0');
        const toIsoDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

        const now = new Date();
        if (preset === 'today') {
            const todayStr = toIsoDate(now);
            fromInput.value = todayStr;
            toInput.value = todayStr;
        } else if (preset === 'yesterday') {
            const y = new Date(now);
            y.setDate(y.getDate() - 1);
            const yStr = toIsoDate(y);
            fromInput.value = yStr;
            toInput.value = yStr;
        } else if (preset === 'week') {
            const past = new Date(now);
            past.setDate(past.getDate() - 6);
            fromInput.value = toIsoDate(past);
            toInput.value = toIsoDate(now);
        } else if (preset === 'month') {
            const startOfMonth = new Date(now.getFullYear(), now.getMonth(), 1);
            fromInput.value = toIsoDate(startOfMonth);
            toInput.value = toIsoDate(now);
        } else if (preset === 'clear') {
            fromInput.value = '';
            toInput.value = '';
        }
    };

    // Khởi chạy khi DOM sẵn sàng
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initCashierTabs);
    } else {
        initCashierTabs();
    }
})();

