/**
 * reception-workflow-navigation.js
 * Quản lý thanh điều hướng tiếp đón (Stepper) giữa 4 màn hình:
 * 1. Lịch hẹn (/reception/appointments)
 * 2. Tiếp đón & Check-in (/reception/check-in)
 * 3. Điều phối ghế nha (/reception/dispatch)
 * 4. Hàng chờ gọi số (/reception/queue)
 *
 * Cho phép trình duyệt điều hướng tự nhiên chuẩn HTTP, bảo toàn state,
 * triệt tiêu hoàn toàn hiện tượng giật màn hình / FOUC / double reload do AJAX DOM swap.
 */
(() => {
    function pathOf(url) {
        try {
            return new URL(url, window.location.origin).pathname.replace(/\/$/, '') || '/';
        } catch (e) {
            return url || '';
        }
    }

    function markCurrentStep() {
        const curPath = pathOf(window.location.href);
        document.querySelectorAll('[data-reception-stepper] a[href]').forEach(link => {
            const isCurrent = pathOf(link.href) === curPath;
            if (isCurrent) {
                link.setAttribute('aria-current', 'step');
            } else {
                link.removeAttribute('aria-current');
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', markCurrentStep);
    } else {
        markCurrentStep();
    }
})();
