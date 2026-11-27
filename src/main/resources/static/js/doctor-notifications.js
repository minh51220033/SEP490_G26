/**
 * Đồng bộ badge thông báo cho Bác sĩ trên Sidebar + hiện toast khi có thông báo mới.
 */
(function () {
    'use strict';

    if (window.__CVDCMS_DOCTOR_NOTIF_INIT__) return;
    window.__CVDCMS_DOCTOR_NOTIF_INIT__ = true;

    let lastTopId = null;
    let initialized = false;

    function updateBadges(unreadCount) {
        const count = Number(unreadCount || 0);
        const text = count > 99 ? '99+' : String(count);
        document.querySelectorAll('[data-doctor-notif-badge]').forEach(function (el) {
            if (count > 0) {
                el.textContent = text;
                el.hidden = false;
                el.style.display = '';
            } else {
                el.textContent = '0';
                el.hidden = true;
            }
        });
    }

    function showDesktopToast(notif) {
        if (!notif || !notif.title) return;
        let wrap = document.getElementById('dfDoctorNotifToastWrap');
        if (!wrap) {
            wrap = document.createElement('div');
            wrap.id = 'dfDoctorNotifToastWrap';
            wrap.style.cssText = 'position:fixed;right:20px;bottom:84px;z-index:1090;display:flex;flex-direction:column;gap:8px;max-width:360px;pointer-events:none;';
            document.body.appendChild(wrap);
        }
        const card = document.createElement('div');
        card.style.cssText = 'pointer-events:auto;background:#ffffff;border:1px solid #bfdbfe;border-left:4px solid #0284c7;border-radius:12px;padding:12px 14px;box-shadow:0 10px 25px rgba(15,23,42,.14);cursor:pointer;font-family:Roboto,Be Vietnam Pro,sans-serif;';
        const titleEl = document.createElement('div');
        titleEl.style.cssText = 'font-weight:700;font-size:13px;color:#0f172a;margin-bottom:2px;';
        titleEl.textContent = notif.title;
        const msgEl = document.createElement('div');
        msgEl.style.cssText = 'font-size:12px;color:#475569;line-height:1.4;';
        msgEl.textContent = notif.message || '';
        card.appendChild(titleEl);
        card.appendChild(msgEl);
        card.addEventListener('click', function () {
            window.location.href = notif.actionUrl || '/doctor/notifications';
        });
        wrap.appendChild(card);
        setTimeout(function () {
            card.remove();
        }, 6500);
    }

    async function pollUnreadCount() {
        const badges = document.querySelectorAll('[data-doctor-notif-badge]');
        if (!badges || badges.length === 0) return;
        try {
            const res = await fetch('/api/doctor/notifications?limit=5', {
                headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
            });
            if (!res.ok) return;
            const data = await res.json();
            updateBadges(data.unreadCount);
            const list = Array.isArray(data.notifications) ? data.notifications : [];
            const top = list.length > 0 ? list[0] : null;
            if (top && top.notificationId) {
                if (initialized && lastTopId !== null && Number(top.notificationId) !== Number(lastTopId) && !top.isRead) {
                    showDesktopToast(top);
                }
                lastTopId = Number(top.notificationId);
            }
            initialized = true;
        } catch (e) { /* bỏ qua lỗi mạng tạm thời */ }
    }

    window.DFDoctorNotif = {
        refreshBadge: pollUnreadCount,
        setBadgeCount: updateBadges
    };

    document.addEventListener('DOMContentLoaded', function () {
        if (document.querySelectorAll('[data-doctor-notif-badge]').length === 0) return;
        pollUnreadCount();
        setInterval(pollUnreadCount, 20000);
    });
})();
