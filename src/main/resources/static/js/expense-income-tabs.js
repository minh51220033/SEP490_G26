/**
 * ============================================================================
 * CV-DCMS EXPENSE & INCOME TABS (Cơ chế chuyển tab nhanh cho Chi phí & thu nhập)
 * Tự động tải nội dung tab qua GET fetch và cập nhật vùng #expenseIncomeTabContent
 * mà không cần tải lại toàn bộ trang hoặc mở cửa sổ mới.
 * ============================================================================
 */

(function () {
    'use strict';

    let activeExpenseIncomeRequestId = 0;

    /**
     * Xác định tab tương ứng từ URL pathname
     */
    function resolveTabFromUrl(url) {
        try {
            const parsed = new URL(url, window.location.origin);
            const path = parsed.pathname;
            if (path.startsWith('/expenses')) {
                return 'expenses';
            }
            if (path.startsWith('/payroll/advances')) {
                return 'advances';
            }
            if (path.startsWith('/payroll/commission-config') || path.startsWith('/payroll/config')) {
                return 'commission-config';
            }
            if (path.startsWith('/payroll/owner-distributions')) {
                return 'owner-distributions';
            }
            if (path === '/payroll' || path.startsWith('/payroll/')) {
                return 'payroll';
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
        const tabBtns = document.querySelectorAll('.expense-income-tab-btn');
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
    function switchExpenseIncomeTab(targetUrl, targetTab, pushState = true) {
        const contentContainer = document.getElementById('expenseIncomeTabContent');
        if (!contentContainer) {
            window.location.href = targetUrl;
            return;
        }

        const requestId = ++activeExpenseIncomeRequestId;
        contentContainer.classList.add('is-loading');

        // Cập nhật tab active tạm thời ngay khi bấm để phản hồi tức thì
        updateActiveTabButton(targetTab);

        fetch(targetUrl, {
            method: 'GET',
            headers: {
                'X-Requested-With': 'XMLHttpRequest',
                'Accept': 'text/html,application/xhtml+xml,application/xml'
            }
        })
        .then(response => {
            if (!response.ok) {
                throw new Error('Mã phản hồi HTTP: ' + response.status);
            }
            return response.text();
        })
        .then(htmlText => {
            // Kiểm tra race condition: nếu có request mới hơn thì bỏ qua kết quả request này
            if (requestId !== activeExpenseIncomeRequestId) {
                return;
            }

            const parser = new DOMParser();
            const doc = parser.parseFromString(htmlText, 'text/html');

            const newContent = doc.getElementById('expenseIncomeTabContent');
            if (!newContent) {
                // Fallback nếu server trả về trang đầy đủ nhưng không chứa #expenseIncomeTabContent
                window.location.href = targetUrl;
                return;
            }

            // Cập nhật tiêu đề trang
            if (doc.title) {
                document.title = doc.title;
            }

            // Hoán đổi nội dung vùng hiển thị
            contentContainer.innerHTML = newContent.innerHTML;
            contentContainer.classList.remove('is-loading');

            // Cập nhật history pushState
            if (pushState) {
                window.history.pushState({ tab: targetTab, url: targetUrl }, doc.title || '', targetUrl);
            }

            // Đảm bảo tab active được đánh dấu chính xác
            updateActiveTabButton(targetTab);

            // Tái kích hoạt hành vi bảng, modal, scripts
            reinitializeContent(contentContainer);
        })
        .catch(err => {
            console.error('Lỗi nạp nội dung tab:', err);
            // Fallback tải lại toàn trang nếu fetch lỗi
            window.location.href = targetUrl;
        });
    }

    /**
     * Khởi tạo lắng nghe sự kiện bấm tab và popstate
     */
    function initExpenseIncomeTabs() {
        const tabList = document.getElementById('expenseIncomeTabsList');
        if (!tabList) return;

        tabList.addEventListener('click', function (e) {
            const btn = e.target.closest('.expense-income-tab-btn');
            if (!btn) return;

            const href = btn.getAttribute('href');
            const targetTab = btn.getAttribute('data-tab');

            // Bỏ qua nếu bấm mở tab mới bằng Ctrl/Cmd
            if (e.ctrlKey || e.metaKey || e.shiftKey || e.which === 2) {
                return;
            }

            if (href && targetTab) {
                e.preventDefault();
                switchExpenseIncomeTab(href, targetTab, true);
            }
        });

        // Hỗ trợ nút Back / Forward trên trình duyệt
        window.addEventListener('popstate', function (e) {
            const currentTab = resolveTabFromUrl(window.location.href);
            if (currentTab) {
                switchExpenseIncomeTab(window.location.href, currentTab, false);
            }
        });
    }

    // Đăng ký khởi chạy khi DOM sẵn sàng
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initExpenseIncomeTabs);
    } else {
        initExpenseIncomeTabs();
    }
})();

