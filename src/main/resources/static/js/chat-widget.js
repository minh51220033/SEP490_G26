/**
 * CV-DCMS — Floating Realtime Chat Widget & Global Unread Badge Synchronizer
 * - Cập nhật tự động theo thời gian thực Badge số tin nhắn chưa đọc tại Sidebar ("Tin nhắn trực tuyến")
 *   và trên biểu tượng Tài khoản người dùng (User Icon ở Sidebar & Topbar) cho RIÊNG từng tài khoản.
 * - Đối với Bệnh nhân (BENH_NHAN): giao diện bong bóng tin nhắn tối giản (ẩn tên người gửi, ẩn badge vai trò,
 *   chỉ giữ nội dung, thời gian, trạng thái Đã chỉnh sửa / Thu hồi).
 * - Hỗ trợ: Chỉnh sửa tin nhắn (trong 10 phút), Thu hồi tin nhắn (trong 10 phút),
 *   Xóa 1 tin nhắn ở phía tôi và Xóa toàn bộ cuộc trò chuyện ở phía tôi.
 */
(function () {
    'use strict';

    if (window.__CVDCMS_CHAT_WIDGET_INITIALIZED__) {
        return;
    }
    window.__CVDCMS_CHAT_WIDGET_INITIALIZED__ = true;

    const DEFAULT_GREETING = 'Xin chào! Bạn cần chúng tôi hỗ trợ điều gì?';
    const DEFAULT_FIRST_MSG_NOTICE = 'Cảm ơn bạn đã liên hệ! Tin nhắn của bạn đã được gửi đến đội ngũ nhân viên tại cơ sở. Vui lòng chờ trong giây lát, chúng tôi sẽ phản hồi sớm nhất có thể.';
    const RECALLED_TEXT = 'Tin nhắn đã được thu hồi';
    const TEN_MINUTES_MS = 10 * 60 * 1000;

    const state = {
        userId: null,
        username: null,
        fullName: null,
        roleCode: null,
        roleLabel: null,
        isAdmin: false,
        canAccessInbox: false,
        canSearchRecipients: false,
        canHandlePatients: false,
        branchId: null,
        branchName: 'Cơ sở nha khoa',
        conversationId: null,
        homeConversationId: null,
        activeTargetName: null,
        activeTargetRoleLabel: null,
        unreadCount: 0,
        totalUnreadCount: 0,
        greetingMessage: DEFAULT_GREETING,
        firstMessageSystemNotice: DEFAULT_FIRST_MSG_NOTICE,
        messages: [],
        knownMessageIds: new Set(),
        knownClientIds: new Set(),
        editingMessageId: null,
        isOpen: false,
        isRecipientPickerOpen: false,
        recipientDebounceTimer: null,
        recipientAbortController: null,
        recipientRequestSeq: 0,
        isSending: false,
        stompClient: null,
        connected: false,
        reconnectTimer: null,
        reconnectAttempts: 0
    };

    function escapeHtml(str) {
        if (str === null || str === undefined) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function formatTime(isoStr) {
        if (!isoStr) return '';
        try {
            const d = new Date(isoStr);
            if (isNaN(d.getTime())) return String(isoStr);
            const pad = (n) => String(n).padStart(2, '0');
            return `${pad(d.getHours())}:${pad(d.getMinutes())} ${pad(d.getDate())}/${pad(d.getMonth() + 1)}`;
        } catch (e) {
            return '';
        }
    }

    function canUserEditOrRecall(msg) {
        if (!msg || !msg.messageId || msg.isRecalled) return false;
        if (Number(msg.senderId) !== Number(state.userId)) return false;
        if (msg.canEditOrRecall === false) return false;
        if (msg.sentAt) {
            const sentMs = new Date(msg.sentAt).getTime();
            if (!isNaN(sentMs) && (Date.now() - sentMs) > TEN_MINUTES_MS) {
                return false;
            }
        }
        return true;
    }

    /**
     * Cập nhật toàn bộ Badge số lượng tin nhắn chưa đọc trên Sidebar ("Tin nhắn trực tuyến")
     * và trên Icon Tài khoản người dùng (Sidebar Footer + Header User Pill).
     */
    function updateGlobalChatUnreadBadges(count) {
        const safeCount = Math.max(0, Number(count || 0));
        state.totalUnreadCount = safeCount;
        const display = safeCount > 99 ? '99+' : String(safeCount);
        const badges = document.querySelectorAll('[data-chat-unread-badge]');
        badges.forEach((el) => {
            if (safeCount > 0) {
                el.textContent = display;
                el.hidden = false;
            } else {
                el.textContent = '0';
                el.hidden = true;
            }
        });
    }

    async function refreshGlobalChatUnreadCount() {
        try {
            const resp = await fetch('/api/chat/unread-count', {
                headers: { 'Accept': 'application/json' }
            });
            if (!resp.ok) return 0;
            const data = await resp.json();
            if (data && data.authenticated) {
                const count = Number(data.totalUnreadCount || 0);
                updateGlobalChatUnreadBadges(count);
                return count;
            }
        } catch (e) {
            // Bỏ qua lỗi mạng tạm thời
        }
        return state.totalUnreadCount;
    }

    window.updateGlobalChatUnreadBadges = updateGlobalChatUnreadBadges;
    window.refreshGlobalChatUnreadCount = refreshGlobalChatUnreadCount;

    function loadScriptOnce(src) {
        return new Promise((resolve, reject) => {
            if (document.querySelector(`script[src="${src}"]`)) {
                resolve();
                return;
            }
            const s = document.createElement('script');
            s.src = src;
            s.async = true;
            s.onload = () => resolve();
            s.onerror = () => reject(new Error('Không thể tải script: ' + src));
            document.head.appendChild(s);
        });
    }

    async function ensureWebSocketLibraries() {
        if (typeof window.SockJS === 'undefined') {
            await loadScriptOnce('https://cdn.jsdelivr.net/npm/sockjs-client@1/dist/sockjs.min.js');
        }
        if (typeof window.Stomp === 'undefined') {
            await loadScriptOnce('https://cdn.jsdelivr.net/npm/stompjs@2.3.3/lib/stomp.min.js');
        }
    }

    function buildWidgetDom() {
        if (document.getElementById('cvdcmsFloatingChatWidget')) {
            return;
        }

        const wrapper = document.createElement('div');
        wrapper.id = 'cvdcmsFloatingChatWidget';
        wrapper.className = 'cvdcms-chat-widget';

        const searchUserBtnHtml = state.canSearchRecipients
            ? `<button type="button" id="cvdcmsWidgetSearchUserBtn" class="btn btn-sm btn-light text-primary fw-semibold py-1 px-2" style="font-size:11px;border-radius:8px;" title="Tìm các tài khoản trong cùng cơ sở để gửi tin nhắn">
                   <i class="bi bi-person-plus-fill me-1"></i>Tìm người nhận
               </button>`
            : '';

        const inboxBtnHtml = state.canAccessInbox
            ? `<a href="/chat" class="btn btn-sm btn-outline-light fw-semibold py-1 px-2" style="font-size:11px;border-radius:8px;" title="Mở Hộp thư quản lý tin nhắn cơ sở">
                   <i class="bi bi-inbox-fill"></i>
               </a>`
            : '';

        const clearConvBtnHtml = `<button type="button" id="cvdcmsWidgetClearConvBtn" class="btn btn-sm btn-outline-light py-1 px-2" style="font-size:11px;border-radius:8px;" title="Xóa cuộc trò chuyện ở phía tôi">
               <i class="bi bi-trash3"></i>
           </button>`;

        const patientRoleOptionHtml = state.canHandlePatients
            ? `<option value="BENH_NHAN">Bệnh nhân</option>`
            : '';

        const recipientPanelHtml = state.canSearchRecipients
            ? `<div id="cvdcmsWidgetRecipientPanel" class="cvdcms-widget-recipient-panel" hidden>
                   <div class="d-flex align-items-center justify-content-between">
                       <span class="fw-bold text-dark" style="font-size:12px;">
                           <i class="bi bi-people-fill text-primary me-1"></i>Chọn người nhận cùng cơ sở
                       </span>
                       <button type="button" id="cvdcmsWidgetResetHomeConvBtn" class="btn btn-link btn-sm p-0 text-decoration-none" style="font-size:11px;">
                           Đóng tìm kiếm
                       </button>
                   </div>
                   <div class="d-flex gap-1">
                       <input type="search" id="cvdcmsWidgetRecipientKeyword" class="form-control form-control-sm"
                              style="font-size:12px;" placeholder="Tìm tên, SĐT, username...">
                       <select id="cvdcmsWidgetRecipientRole" class="form-select form-select-sm" style="font-size:12px;max-width:125px;">
                           <option value="ALL">Mọi vai trò</option>
                           <option value="LE_TAN">Lễ tân</option>
                           <option value="BAC_SI">Bác sĩ</option>
                           <option value="QUAN_LY_KHO">QL Kho</option>
                           <option value="KE_TOAN">Kế toán</option>
                           ${patientRoleOptionHtml}
                           <option value="ADMIN">Admin</option>
                       </select>
                   </div>
                   <div id="cvdcmsWidgetRecipientList" class="cvdcms-widget-recipient-list"></div>
               </div>`
            : '';

        wrapper.innerHTML = `
            <div id="cvdcmsChatWidgetPopup" class="cvdcms-chat-popup" hidden aria-label="Khung chat trực tuyến">
                <div class="cvdcms-chat-popup__header">
                    <div class="overflow-hidden">
                        <h6 class="cvdcms-chat-popup__title text-truncate" id="cvdcmsWidgetHeaderTitle">
                            <i class="bi bi-chat-dots-fill me-1"></i>Hỗ trợ Trực tuyến
                        </h6>
                        <p class="cvdcms-chat-popup__subtitle text-truncate" id="cvdcmsWidgetBranchSubtitle">
                            <i class="bi bi-geo-alt-fill"></i>
                            <span>${escapeHtml(state.branchName || 'Cơ sở nha khoa')}</span>
                        </p>
                    </div>
                    <div class="d-flex align-items-center gap-1 flex-shrink-0">
                        ${searchUserBtnHtml}
                        ${inboxBtnHtml}
                        ${clearConvBtnHtml}
                        <button type="button" id="cvdcmsChatWidgetCloseBtn" class="btn btn-sm text-white p-1" aria-label="Đóng khung chat">
                            <i class="bi bi-x-lg"></i>
                        </button>
                    </div>
                </div>

                ${recipientPanelHtml}

                <div id="cvdcmsChatWidgetMessages" class="cvdcms-chat-popup__body"></div>

                <div class="cvdcms-chat-popup__footer">
                    <textarea id="cvdcmsChatWidgetInput" class="cvdcms-chat-popup__input" rows="1"
                              placeholder="Nhập tin nhắn..."></textarea>
                    <button type="button" id="cvdcmsChatWidgetSendBtn" class="cvdcms-chat-popup__send" aria-label="Gửi tin nhắn">
                        <i class="bi bi-send-fill"></i>
                    </button>
                </div>
            </div>

            <div id="cvdcmsChatFabWrapper" class="cvdcms-chat-fab-wrapper">
                <button type="button" id="cvdcmsChatWidgetFab" class="cvdcms-chat-fab" title="Chat trực tiếp theo cơ sở">
                    <i class="bi bi-chat-dots-fill" id="cvdcmsChatWidgetFabIcon"></i>
                    <span id="cvdcmsChatWidgetUnreadBadge" class="cvdcms-chat-fab__badge" hidden>0</span>
                </button>
                <button type="button" id="cvdcmsChatDismissBtn" class="cvdcms-chat-fab__dismiss" title="Tạm ẩn hộp chat" aria-label="Tạm ẩn hộp chat">
                    <i class="bi bi-x"></i>
                </button>
            </div>

            <div id="cvdcmsChatUndoToast" class="cvdcms-chat-undo-toast" hidden>
                <div class="cvdcms-chat-undo-toast__content">
                    <i class="bi bi-info-circle me-1 text-primary"></i>
                    <span>Đã ẩn hộp chat</span>
                </div>
                <button type="button" id="cvdcmsChatUndoBtn" class="cvdcms-chat-undo-toast__btn">
                    Hoàn tác
                </button>
            </div>

            <button type="button" id="cvdcmsChatRestoreTrigger" class="cvdcms-chat-restore-pill" title="Mở lại hộp chat" aria-label="Mở lại hộp chat" hidden>
                <i class="bi bi-chat-dots-fill"></i>
                <span>Chat</span>
            </button>
        `;

        document.body.appendChild(wrapper);

        const fab = document.getElementById('cvdcmsChatWidgetFab');
        const closeBtn = document.getElementById('cvdcmsChatWidgetCloseBtn');
        const clearConvBtn = document.getElementById('cvdcmsWidgetClearConvBtn');
        const sendBtn = document.getElementById('cvdcmsChatWidgetSendBtn');
        const input = document.getElementById('cvdcmsChatWidgetInput');
        const searchUserBtn = document.getElementById('cvdcmsWidgetSearchUserBtn');
        const resetHomeBtn = document.getElementById('cvdcmsWidgetResetHomeConvBtn');
        const recKeywordInput = document.getElementById('cvdcmsWidgetRecipientKeyword');
        const recRoleSelect = document.getElementById('cvdcmsWidgetRecipientRole');
        const msgContainer = document.getElementById('cvdcmsChatWidgetMessages');

        fab.addEventListener('click', togglePopup);
        closeBtn.addEventListener('click', () => setPopupOpen(false));
        sendBtn.addEventListener('click', handleSendMessage);
        if (clearConvBtn) {
            clearConvBtn.addEventListener('click', handleDeleteConversationForSelf);
        }

        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSendMessage();
            }
        });

        if (msgContainer) {
            msgContainer.addEventListener('click', handleMessageContainerClick);
        }

        if (searchUserBtn) {
            searchUserBtn.addEventListener('click', toggleRecipientPicker);
        }
        if (resetHomeBtn) {
            resetHomeBtn.addEventListener('click', () => {
                setRecipientPickerOpen(false);
            });
        }
        if (recKeywordInput) {
            recKeywordInput.addEventListener('input', () => {
                if (state.recipientDebounceTimer) clearTimeout(state.recipientDebounceTimer);
                state.recipientDebounceTimer = setTimeout(loadWidgetRecipients, 350);
            });
        }
        if (recRoleSelect) {
            recRoleSelect.addEventListener('change', loadWidgetRecipients);
        }

        const fabWrapper = document.getElementById('cvdcmsChatFabWrapper');
        const dismissBtn = document.getElementById('cvdcmsChatDismissBtn');
        const undoToast = document.getElementById('cvdcmsChatUndoToast');
        const undoBtn = document.getElementById('cvdcmsChatUndoBtn');
        const restoreTrigger = document.getElementById('cvdcmsChatRestoreTrigger');
        let undoToastTimer = null;

        function showChatWidget() {
            if (undoToastTimer) {
                clearTimeout(undoToastTimer);
                undoToastTimer = null;
            }
            if (undoToast) undoToast.hidden = true;
            if (restoreTrigger) restoreTrigger.hidden = true;
            if (fabWrapper) fabWrapper.style.display = '';
            try {
                sessionStorage.removeItem('cvdcms_chat_dismissed');
            } catch (e) {}
        }

        function hideChatWidget(showToast = true) {
            setPopupOpen(false);
            if (fabWrapper) fabWrapper.style.display = 'none';
            try {
                sessionStorage.setItem('cvdcms_chat_dismissed', '1');
            } catch (e) {}

            if (showToast && undoToast) {
                undoToast.hidden = false;
                if (restoreTrigger) restoreTrigger.hidden = true;
                if (undoToastTimer) clearTimeout(undoToastTimer);
                undoToastTimer = setTimeout(() => {
                    undoToast.hidden = true;
                    if (restoreTrigger) restoreTrigger.hidden = false;
                }, 7000);
            } else if (restoreTrigger) {
                restoreTrigger.hidden = false;
            }
        }

        if (dismissBtn) {
            dismissBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                hideChatWidget(true);
            });
        }

        if (undoBtn) {
            undoBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                showChatWidget();
            });
        }

        if (restoreTrigger) {
            restoreTrigger.addEventListener('click', (e) => {
                e.stopPropagation();
                showChatWidget();
            });
        }

        try {
            if (sessionStorage.getItem('cvdcms_chat_dismissed') === '1') {
                hideChatWidget(false);
            }
        } catch (e) {}
    }

    function toggleRecipientPicker() {
        setRecipientPickerOpen(!state.isRecipientPickerOpen);
    }

    function setRecipientPickerOpen(open) {
        state.isRecipientPickerOpen = open;
        const panel = document.getElementById('cvdcmsWidgetRecipientPanel');
        if (!panel) return;
        panel.hidden = !open;
        if (open) {
            const kwInput = document.getElementById('cvdcmsWidgetRecipientKeyword');
            if (kwInput) kwInput.focus();
            loadWidgetRecipients();
        }
    }

    async function loadWidgetRecipients() {
        const listEl = document.getElementById('cvdcmsWidgetRecipientList');
        if (!listEl) return;

        if (state.recipientAbortController) {
            try { state.recipientAbortController.abort(); } catch (e) { /* ignore */ }
        }
        const abortController = new AbortController();
        state.recipientAbortController = abortController;
        const requestSeq = ++state.recipientRequestSeq;

        const kwInput = document.getElementById('cvdcmsWidgetRecipientKeyword');
        const roleSelect = document.getElementById('cvdcmsWidgetRecipientRole');
        const params = new URLSearchParams();
        if (kwInput && kwInput.value.trim() !== '') {
            params.set('keyword', kwInput.value.trim());
        }
        if (roleSelect && roleSelect.value !== 'ALL') {
            params.set('role', roleSelect.value);
        }
        params.set('page', '0');
        params.set('size', '30');

        listEl.innerHTML = `<div class="p-2 text-center text-muted" style="font-size:11.5px;">Đang tìm tài khoản cùng cơ sở...</div>`;

        try {
            const resp = await fetch(`/api/chat/recipients?${params.toString()}`, {
                headers: { 'Accept': 'application/json' },
                signal: abortController.signal
            });
            if (requestSeq !== state.recipientRequestSeq) return;
            if (!resp.ok) throw new Error('Không thể tải danh sách người nhận');
            const users = await resp.json();
            if (requestSeq !== state.recipientRequestSeq) return;

            if (!Array.isArray(users) || users.length === 0) {
                listEl.innerHTML = `<div class="p-2 text-center text-muted" style="font-size:11.5px;">Không có tài khoản phù hợp trong cơ sở.</div>`;
                return;
            }

            let html = '';
            for (const u of users) {
                const name = u.fullName || u.username || ('#' + u.userId);
                const roleCode = u.roleCode || 'BENH_NHAN';
                const roleLabel = u.roleLabel || 'Bệnh nhân';
                const profileInfo = u.patientProfilesSummary ? ` • Hồ sơ: ${u.patientProfilesSummary}` : '';
                html += `
                    <div class="d-flex align-items-center justify-content-between px-2 py-1 rounded hover-bg-light"
                         style="cursor:pointer;border-bottom:1px solid #f1f5f9;"
                         data-widget-target-uid="${u.userId}"
                         data-widget-target-bid="${u.branchId || ''}">
                        <div class="overflow-hidden me-2">
                            <div class="d-flex align-items-center gap-1">
                                <span class="fw-semibold text-dark text-truncate" style="font-size:12px;">${escapeHtml(name)}</span>
                                <span class="chat-role-badge chat-role-badge--${escapeHtml(roleCode)}" style="font-size:10px;padding:1px 6px;">${escapeHtml(roleLabel)}</span>
                            </div>
                            <div class="text-muted text-truncate" style="font-size:10.5px;" title="${escapeHtml(u.patientProfilesSummary || '')}">
                                ${escapeHtml(u.username ? '@' + u.username : '')} ${escapeHtml(u.phoneNumber ? '• ' + u.phoneNumber : '')}${escapeHtml(profileInfo)}
                            </div>
                        </div>
                        <span class="badge bg-primary text-white" style="font-size:10px;">Chọn</span>
                    </div>
                `;
            }
            listEl.innerHTML = html;

            listEl.querySelectorAll('[data-widget-target-uid]').forEach((row) => {
                row.addEventListener('click', async () => {
                    const targetUid = Number(row.getAttribute('data-widget-target-uid'));
                    const targetBidAttr = row.getAttribute('data-widget-target-bid');
                    const targetBid = targetBidAttr ? Number(targetBidAttr) : null;
                    if (targetUid) {
                        await selectWidgetRecipient(targetUid, targetBid);
                    }
                });
            });
        } catch (err) {
            if (err && err.name === 'AbortError') return;
            if (requestSeq !== state.recipientRequestSeq) return;
            listEl.innerHTML = `<div class="p-2 text-center text-danger" style="font-size:11.5px;">Lỗi tải danh sách tài khoản.</div>`;
        }
    }

    async function selectWidgetRecipient(targetUserId, branchId) {
        try {
            const resp = await fetch('/api/chat/conversations/start', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'application/json'
                },
                body: JSON.stringify({ targetUserId, branchId })
            });
            if (!resp.ok) {
                const err = await resp.json().catch(() => ({}));
                throw new Error(err.message || 'Không thể mở hội thoại với người nhận này.');
            }
            const data = await resp.json();
            state.conversationId = data.conversationId;
            state.activeTargetName = data.counterpartFullName || data.initiatorFullName || 'Người nhận';
            state.activeTargetRoleLabel = data.counterpartRoleLabel || data.initiatorRoleLabel || '';
            state.messages = Array.isArray(data.messages) ? data.messages : [];
            state.knownMessageIds.clear();
            state.knownClientIds.clear();
            for (const m of state.messages) {
                if (m.messageId) state.knownMessageIds.add(Number(m.messageId));
            }

            const titleEl = document.getElementById('cvdcmsWidgetHeaderTitle');
            if (titleEl) {
                titleEl.innerHTML = `<i class="bi bi-person-check-fill me-1"></i>${escapeHtml(state.activeTargetName)} (${escapeHtml(state.activeTargetRoleLabel)})`;
            }

            setRecipientPickerOpen(false);
            renderMessages();
            const input = document.getElementById('cvdcmsChatWidgetInput');
            if (input) input.focus();
        } catch (e) {
            alert(e.message || 'Không thể chọn người nhận.');
        }
    }

    function updateUnreadBadge() {
        const badge = document.getElementById('cvdcmsChatWidgetUnreadBadge');
        if (badge) {
            if (state.unreadCount > 0 && !state.isOpen) {
                badge.textContent = state.unreadCount > 99 ? '99+' : String(state.unreadCount);
                badge.hidden = false;
            } else {
                badge.hidden = true;
            }
        }
        updateGlobalChatUnreadBadges(state.unreadCount);
    }

    function togglePopup() {
        setPopupOpen(!state.isOpen);
    }

    function setPopupOpen(open) {
        state.isOpen = open;
        const popup = document.getElementById('cvdcmsChatWidgetPopup');
        const input = document.getElementById('cvdcmsChatWidgetInput');
        if (!popup) return;

        popup.hidden = !open;
        if (open) {
            state.unreadCount = 0;
            updateUnreadBadge();
            scrollToBottom();
            if (input) input.focus();
            if (state.conversationId) {
                markConversationReadOnServer(state.conversationId);
            }
        }
    }

    function renderMessages() {
        const container = document.getElementById('cvdcmsChatWidgetMessages');
        if (!container) return;

        const isPatientRole = (state.roleCode || '').toUpperCase() === 'BENH_NHAN'
            || (state.roleCode || '').toUpperCase() === 'PATIENT';

        const headerText = state.activeTargetName
            ? `Đang nhắn tin với ${state.activeTargetName}`
            : (state.canSearchRecipients && !state.canHandlePatients
                ? 'Hãy bấm "Tìm người nhận" để chọn đồng nghiệp cùng cơ sở cần gửi tin nhắn.'
                : (state.greetingMessage || DEFAULT_GREETING));

        let html = `
            <div class="cvdcms-chat-greeting">
                <div class="fw-bold text-primary mb-1">
                    <i class="bi bi-stars me-1"></i>${escapeHtml(state.branchName || 'CV-DCMS')}
                </div>
                <div>${escapeHtml(headerText)}</div>
            </div>
        `;

        for (const msg of state.messages) {
            const isMine = Number(msg.senderId) === Number(state.userId);
            const isRecalled = Boolean(msg.isRecalled);
            const isEdited = Boolean(msg.isEdited) && !isRecalled;
            const isEditingThis = state.editingMessageId && Number(state.editingMessageId) === Number(msg.messageId);

            const rowClasses = [
                'chat-msg-row',
                isMine ? 'chat-msg-row--outgoing' : 'chat-msg-row--incoming',
                isPatientRole ? 'chat-msg-row--patient-view' : ''
            ].filter(Boolean).join(' ');

            // Yêu cầu số 4: Với vai trò Bệnh nhân, KHÔNG hiển thị tên người gửi và KHÔNG hiển thị badge vai trò trên bong bóng chat
            let senderHeaderHtml = '';
            if (!isPatientRole) {
                const roleCode = msg.senderRoleCode || 'BENH_NHAN';
                const roleLabel = msg.senderRoleLabel || 'Bệnh nhân';
                const senderName = msg.senderFullName || msg.senderUsername || 'Người dùng';
                senderHeaderHtml = `
                    <div class="chat-msg-sender">
                        <span>${escapeHtml(senderName)}</span>
                        <span class="chat-role-badge chat-role-badge--${escapeHtml(roleCode)}">${escapeHtml(roleLabel)}</span>
                    </div>
                `;
            }

            const displayText = isRecalled ? RECALLED_TEXT : (msg.messageText || '');
            const bubbleClass = isRecalled
                ? 'chat-msg-bubble chat-msg-bubble--recalled'
                : 'chat-msg-bubble';

            let bodyHtml;
            if (isEditingThis) {
                bodyHtml = `
                    <div class="chat-msg-bubble">
                        <div class="chat-inline-edit-box">
                            <textarea class="chat-inline-edit-textarea" data-edit-input="${escapeHtml(msg.messageId)}">${escapeHtml(msg.messageText || '')}</textarea>
                            <div class="chat-inline-edit-actions">
                                <button type="button" class="chat-inline-edit-btn chat-inline-edit-btn--cancel" data-msg-action="cancel-edit" data-msg-id="${escapeHtml(msg.messageId)}">Hủy</button>
                                <button type="button" class="chat-inline-edit-btn chat-inline-edit-btn--save" data-msg-action="save-edit" data-msg-id="${escapeHtml(msg.messageId)}">Lưu</button>
                            </div>
                        </div>
                    </div>
                `;
            } else {
                bodyHtml = `<div class="${bubbleClass}">${escapeHtml(displayText)}</div>`;
            }

            const editedTagHtml = isEdited
                ? `<span class="chat-msg-edited-tag">(Đã chỉnh sửa)</span>`
                : '';

            const allowEditRecall = canUserEditOrRecall(msg);
            let actionsHtml = '';
            if (msg.messageId && !isEditingThis) {
                const editBtn = allowEditRecall
                    ? `<button type="button" class="chat-msg-action-btn" data-msg-action="edit" data-msg-id="${escapeHtml(msg.messageId)}" title="Chỉnh sửa tin nhắn (trong 10 phút)">
                           <i class="bi bi-pencil"></i><span>Sửa</span>
                       </button>`
                    : '';
                const recallBtn = allowEditRecall
                    ? `<button type="button" class="chat-msg-action-btn chat-msg-action-btn--danger" data-msg-action="recall" data-msg-id="${escapeHtml(msg.messageId)}" title="Thu hồi tin nhắn đối với mọi người (trong 10 phút)">
                           <i class="bi bi-arrow-counterclockwise"></i><span>Thu hồi</span>
                       </button>`
                    : '';
                const deleteSelfBtn = `<button type="button" class="chat-msg-action-btn chat-msg-action-btn--danger" data-msg-action="delete-self" data-msg-id="${escapeHtml(msg.messageId)}" title="Xóa tin nhắn ở phía tôi">
                        <i class="bi bi-trash3"></i><span>Xóa ở phía tôi</span>
                    </button>`;

                actionsHtml = `<div class="chat-msg-actions">${editBtn}${recallBtn}${deleteSelfBtn}</div>`;
            }

            html += `
                <div class="${rowClasses}" data-message-id="${escapeHtml(msg.messageId || '')}">
                    ${senderHeaderHtml}
                    ${bodyHtml}
                    <div class="chat-msg-time">${editedTagHtml}${escapeHtml(formatTime(msg.sentAt))}</div>
                    ${actionsHtml}
                </div>
            `;

            if (msg.firstMessageInConversation) {
                const noticeText = msg.systemNotice || state.firstMessageSystemNotice || DEFAULT_FIRST_MSG_NOTICE;
                html += `
                    <div class="chat-system-notice">
                        <i class="bi bi-info-circle-fill me-1"></i>${escapeHtml(noticeText)}
                    </div>
                `;
            }
        }

        container.innerHTML = html;
        if (!state.editingMessageId) {
            scrollToBottom();
        }
    }

    async function handleMessageContainerClick(e) {
        const btn = e.target.closest('[data-msg-action]');
        if (!btn) return;
        const action = btn.getAttribute('data-msg-action');
        const messageId = Number(btn.getAttribute('data-msg-id'));
        if (!messageId) return;

        if (action === 'edit') {
            const targetMsg = state.messages.find((m) => Number(m.messageId) === messageId);
            if (!canUserEditOrRecall(targetMsg)) {
                alert('Tin nhắn đã quá thời hạn 10 phút hoặc không thể chỉnh sửa.');
                renderMessages();
                return;
            }
            state.editingMessageId = messageId;
            renderMessages();
            const textarea = document.querySelector(`[data-edit-input="${messageId}"]`);
            if (textarea) {
                textarea.focus();
                textarea.setSelectionRange(textarea.value.length, textarea.value.length);
            }
        } else if (action === 'cancel-edit') {
            state.editingMessageId = null;
            renderMessages();
        } else if (action === 'save-edit') {
            const textarea = document.querySelector(`[data-edit-input="${messageId}"]`);
            const newText = textarea ? textarea.value.trim() : '';
            if (!newText) {
                alert('Nội dung tin nhắn chỉnh sửa không được để trống.');
                return;
            }
            await submitEditMessage(messageId, newText);
        } else if (action === 'recall') {
            const targetMsg = state.messages.find((m) => Number(m.messageId) === messageId);
            if (!canUserEditOrRecall(targetMsg)) {
                alert('Tin nhắn đã quá thời hạn 10 phút hoặc không thể thu hồi.');
                renderMessages();
                return;
            }
            if (!window.confirm('Bạn có chắc chắn muốn thu hồi tin nhắn này đối với tất cả mọi người không?')) {
                return;
            }
            await submitRecallMessage(messageId);
        } else if (action === 'delete-self') {
            if (!window.confirm('Xóa tin nhắn này ở phía tài khoản của bạn? (Người khác vẫn nhìn thấy bình thường)')) {
                return;
            }
            await submitDeleteMessageForSelf(messageId);
        }
    }

    async function submitEditMessage(messageId, newText) {
        try {
            const resp = await fetch(`/api/chat/messages/${encodeURIComponent(messageId)}`, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'application/json'
                },
                body: JSON.stringify({ messageText: newText })
            });
            const data = await resp.json().catch(() => ({}));
            if (!resp.ok) {
                throw new Error(data.message || 'Không thể chỉnh sửa tin nhắn.');
            }
            state.editingMessageId = null;
            applyMessageMutation(data);
        } catch (err) {
            alert(err.message || 'Chỉnh sửa tin nhắn thất bại.');
        }
    }

    async function submitRecallMessage(messageId) {
        try {
            const resp = await fetch(`/api/chat/messages/${encodeURIComponent(messageId)}/recall`, {
                method: 'POST',
                headers: { 'Accept': 'application/json' }
            });
            const data = await resp.json().catch(() => ({}));
            if (!resp.ok) {
                throw new Error(data.message || 'Không thể thu hồi tin nhắn.');
            }
            state.editingMessageId = null;
            applyMessageMutation(data);
        } catch (err) {
            alert(err.message || 'Thu hồi tin nhắn thất bại.');
        }
    }

    async function submitDeleteMessageForSelf(messageId) {
        try {
            const resp = await fetch(`/api/chat/messages/${encodeURIComponent(messageId)}/self`, {
                method: 'DELETE',
                headers: { 'Accept': 'application/json' }
            });
            const data = await resp.json().catch(() => ({}));
            if (!resp.ok) {
                throw new Error(data.message || 'Không thể xóa tin nhắn ở phía bạn.');
            }
            state.messages = state.messages.filter((m) => Number(m.messageId) !== Number(messageId));
            state.knownMessageIds.delete(Number(messageId));
            if (typeof data.totalUnreadCount === 'number') {
                state.unreadCount = data.totalUnreadCount;
                updateUnreadBadge();
            }
            renderMessages();
        } catch (err) {
            alert(err.message || 'Xóa tin nhắn thất bại.');
        }
    }

    async function handleDeleteConversationForSelf() {
        if (!state.conversationId) {
            alert('Chưa có lịch sử cuộc trò chuyện để xóa.');
            return;
        }
        if (!window.confirm('Bạn có chắc chắn muốn xóa toàn bộ lịch sử cuộc trò chuyện này ở phía tài khoản của bạn không? (Dữ liệu của phòng khám và người khác không bị ảnh hưởng)')) {
            return;
        }
        try {
            const resp = await fetch(`/api/chat/conversations/${encodeURIComponent(state.conversationId)}/self`, {
                method: 'DELETE',
                headers: { 'Accept': 'application/json' }
            });
            const data = await resp.json().catch(() => ({}));
            if (!resp.ok) {
                throw new Error(data.message || 'Không thể xóa cuộc trò chuyện ở phía bạn.');
            }
            state.messages = [];
            state.knownMessageIds.clear();
            state.knownClientIds.clear();
            state.editingMessageId = null;
            state.unreadCount = typeof data.totalUnreadCount === 'number' ? data.totalUnreadCount : 0;
            updateUnreadBadge();
            renderMessages();
        } catch (err) {
            alert(err.message || 'Xóa cuộc trò chuyện thất bại.');
        }
    }

    function applyMessageMutation(updatedMsg) {
        if (!updatedMsg || !updatedMsg.messageId) return;
        const idx = state.messages.findIndex((m) => Number(m.messageId) === Number(updatedMsg.messageId));
        if (idx >= 0) {
            state.messages[idx] = Object.assign({}, state.messages[idx], updatedMsg);
            renderMessages();
        }
    }

    function scrollToBottom() {
        const container = document.getElementById('cvdcmsChatWidgetMessages');
        if (container) {
            container.scrollTop = container.scrollHeight;
        }
    }

    function appendOrUpdateMessage(msg) {
        if (!msg) return;

        if (msg.eventType === 'EDIT' || msg.eventType === 'RECALL') {
            applyMessageMutation(msg);
            return;
        }

        if (msg.conversationId && !state.conversationId) {
            state.conversationId = msg.conversationId;
        }

        if (state.conversationId && Number(msg.conversationId) !== Number(state.conversationId)) {
            if (Number(msg.senderId) !== Number(state.userId)) {
                state.unreadCount += 1;
                updateUnreadBadge();
            }
            return;
        }

        if (msg.messageId && state.knownMessageIds.has(Number(msg.messageId))) {
            applyMessageMutation(msg);
            return;
        }
        if (msg.clientMessageId && state.knownClientIds.has(String(msg.clientMessageId))) {
            const existing = state.messages.find((m) => m.clientMessageId === msg.clientMessageId);
            if (existing) {
                Object.assign(existing, msg);
                if (msg.messageId) state.knownMessageIds.add(Number(msg.messageId));
                renderMessages();
                return;
            }
        }

        if (msg.messageId) {
            state.knownMessageIds.add(Number(msg.messageId));
        }
        if (msg.clientMessageId) {
            state.knownClientIds.add(String(msg.clientMessageId));
        }

        state.messages.push(msg);
        renderMessages();

        const isFromOther = Number(msg.senderId) !== Number(state.userId);
        if (isFromOther) {
            if (state.isOpen && state.conversationId) {
                markConversationReadOnServer(state.conversationId);
            } else {
                state.unreadCount += 1;
                updateUnreadBadge();
            }
        }
    }

    async function loadMyConversationHistory() {
        try {
            const resp = await fetch('/api/chat/my-conversation', {
                headers: { 'Accept': 'application/json' }
            });
            if (!resp.ok) return;
            const data = await resp.json();

            state.conversationId = data.conversationId || null;
            state.homeConversationId = state.conversationId;
            state.branchId = data.branchId || state.branchId;
            state.branchName = data.branchName || state.branchName;
            state.unreadCount = Number(data.unreadCount || 0);
            state.greetingMessage = data.greetingMessage || DEFAULT_GREETING;
            state.firstMessageSystemNotice = data.firstMessageSystemNotice || DEFAULT_FIRST_MSG_NOTICE;

            const titleEl = document.getElementById('cvdcmsWidgetHeaderTitle');
            if (titleEl && !state.activeTargetName) {
                titleEl.innerHTML = `<i class="bi bi-chat-dots-fill me-1"></i>Hỗ trợ Trực tuyến`;
            }

            const subtitleEl = document.getElementById('cvdcmsWidgetBranchSubtitle');
            if (subtitleEl && state.branchName) {
                subtitleEl.innerHTML = `<i class="bi bi-geo-alt-fill"></i><span>${escapeHtml(state.branchName)}</span>`;
            }

            state.messages = Array.isArray(data.messages) ? data.messages : [];
            state.knownMessageIds.clear();
            state.knownClientIds.clear();
            for (const m of state.messages) {
                if (m.messageId) state.knownMessageIds.add(Number(m.messageId));
                if (m.clientMessageId) state.knownClientIds.add(String(m.clientMessageId));
            }

            renderMessages();
            updateUnreadBadge();
        } catch (err) {
            console.warn('Không thể tải lịch sử hội thoại widget:', err);
        }
    }

    async function markConversationReadOnServer(conversationId) {
        if (!conversationId) return;
        try {
            const resp = await fetch(`/api/chat/conversations/${encodeURIComponent(conversationId)}/read`, {
                method: 'POST',
                headers: { 'Accept': 'application/json' }
            });
            if (resp.ok) {
                const data = await resp.json().catch(() => ({}));
                if (typeof data.totalUnreadCount === 'number') {
                    state.unreadCount = data.totalUnreadCount;
                    updateUnreadBadge();
                } else {
                    refreshGlobalChatUnreadCount();
                }
            }
        } catch (e) {
            // Bỏ qua lỗi mạng tạm thời
        }
    }

    async function handleSendMessage() {
        const input = document.getElementById('cvdcmsChatWidgetInput');
        const sendBtn = document.getElementById('cvdcmsChatWidgetSendBtn');
        if (!input || state.isSending) return;

        const text = input.value.trim();
        if (!text) return;

        state.isSending = true;
        if (sendBtn) sendBtn.disabled = true;

        const clientMessageId = 'wmsg_' + Date.now() + '_' + Math.random().toString(36).substring(2, 8);
        const payload = {
            conversationId: state.conversationId,
            messageText: text,
            clientMessageId: clientMessageId
        };

        input.value = '';

        try {
            const resp = await fetch('/api/chat/messages', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'application/json'
                },
                body: JSON.stringify(payload)
            });

            if (!resp.ok) {
                const errData = await resp.json().catch(() => ({}));
                throw new Error(errData.message || 'Không thể gửi tin nhắn. Vui lòng thử lại.');
            }

            const savedMsg = await resp.json();
            appendOrUpdateMessage(savedMsg);
        } catch (err) {
            input.value = text;
            alert(err.message || 'Gửi tin nhắn thất bại. Vui lòng kiểm tra kết nối mạng.');
        } finally {
            state.isSending = false;
            if (sendBtn) sendBtn.disabled = false;
            input.focus();
        }
    }

    async function connectWebSocket(isPatientMode) {
        try {
            await ensureWebSocketLibraries();
        } catch (e) {
            console.warn('Không thể tải thư viện WebSocket cho widget:', e);
            return;
        }

        if (state.stompClient && state.connected) {
            return;
        }

        const socket = new window.SockJS('/ws-chat', null, {
            transports: ['websocket', 'xhr-streaming', 'xhr-polling']
        });
        const stomp = window.Stomp.over(socket);
        stomp.debug = null;

        stomp.connect(
            {},
            function () {
                state.stompClient = stomp;
                state.connected = true;
                if (state.reconnectAttempts > 0) {
                    if (isPatientMode) {
                        loadMyConversationHistory();
                    } else {
                        refreshGlobalChatUnreadCount();
                    }
                }
                state.reconnectAttempts = 0;

                stomp.subscribe('/user/queue/chat/messages', function (frame) {
                    try {
                        const msg = JSON.parse(frame.body);
                        if (isPatientMode) {
                            appendOrUpdateMessage(msg);
                        } else {
                            refreshGlobalChatUnreadCount();
                        }
                    } catch (err) {
                        console.error('Lỗi đọc tin nhắn STOMP:', err);
                    }
                });

                // Đối với nhân viên / Admin đang ở trang ngoài /chat: đăng ký topic cơ sở/admin để cập nhật Badge Sidebar & User Icon tức thì
                if (!isPatientMode) {
                    if (state.isAdmin) {
                        stomp.subscribe('/topic/chat/admin', function () {
                            refreshGlobalChatUnreadCount();
                        });
                    } else if (state.canHandlePatients && state.branchId) {
                        stomp.subscribe(`/topic/chat/branch/${state.branchId}`, function () {
                            refreshGlobalChatUnreadCount();
                        });
                    }
                }
            },
            function () {
                state.connected = false;
                scheduleReconnect(isPatientMode);
            }
        );
    }

    function scheduleReconnect(isPatientMode) {
        if (state.reconnectTimer) {
            clearTimeout(state.reconnectTimer);
        }
        state.reconnectAttempts += 1;
        const delay = Math.min(2000 * Math.pow(1.5, Math.min(state.reconnectAttempts, 6)), 15000);
        state.reconnectTimer = setTimeout(() => {
            connectWebSocket(isPatientMode);
        }, delay);
    }

    function buildGuestWidgetDom() {
        if (document.getElementById('cvdcmsFloatingChatWidget')) {
            return;
        }

        const wrapper = document.createElement('div');
        wrapper.id = 'cvdcmsFloatingChatWidget';
        wrapper.className = 'cvdcms-chat-widget';
        wrapper.innerHTML = `
            <div id="cvdcmsChatPopup" class="cvdcms-chat-popup cvdcms-chat-popup--guest" aria-hidden="true" hidden>
                <div class="cvdcms-chat-popup__header">
                    <div class="d-flex align-items-center justify-content-between">
                        <div class="d-flex align-items-center gap-2">
                            <span class="rounded-circle bg-white text-primary d-inline-flex align-items-center justify-content-center fw-bold"
                                  style="width:34px;height:34px;font-size:15px;">
                                <i class="bi bi-chat-heart-fill"></i>
                            </span>
                            <div>
                                <div class="fw-bold text-white" style="font-size:13.5px;line-height:1.2;">Tư vấn trực tuyến CV-DCMS</div>
                                <div class="text-white-50" style="font-size:11.5px;">Đội ngũ Lễ tân &amp; Bác sĩ Nha khoa Cường Việt</div>
                            </div>
                        </div>
                        <button type="button" id="cvdcmsChatCloseBtn" class="btn btn-sm text-white p-1" aria-label="Đóng khung chat">
                            <i class="bi bi-x-lg"></i>
                        </button>
                    </div>
                </div>
                <div class="cvdcms-chat-body p-3 d-flex flex-column justify-content-between" style="min-height:260px;background:#f8fafc;">
                    <div class="bg-white border rounded-3 p-3 shadow-sm">
                        <div class="fw-bold text-dark mb-1" style="font-size:13.5px;">
                            <i class="bi bi-stars text-primary me-1"></i>Xin chào Quý khách!
                        </div>
                        <p class="text-muted mb-2" style="font-size:12.5px;line-height:1.55;">
                            Hệ thống hỗ trợ trò chuyện trực tuyến thời gian thực với đội ngũ Lễ tân và Bác sĩ theo từng cơ sở phòng khám.
                        </p>
                        <p class="text-muted mb-0" style="font-size:12.5px;line-height:1.55;">
                            Vui lòng đăng nhập tài khoản để gửi tin nhắn và lưu lịch sử tư vấn nha khoa của bạn.
                        </p>
                    </div>
                    <div class="d-grid gap-2 mt-3">
                        <a href="/login" class="btn btn-primary fw-semibold btn-sm py-2">
                            <i class="bi bi-box-arrow-in-right me-1"></i>Đăng nhập để trò chuyện ngay
                        </a>
                        <a href="/register" class="btn btn-outline-primary fw-semibold btn-sm py-2">
                            <i class="bi bi-person-plus me-1"></i>Đăng ký tài khoản bệnh nhân
                        </a>
                    </div>
                </div>
            </div>
            <button type="button" id="cvdcmsChatFabBtn" class="cvdcms-chat-fab" aria-label="Mở khung hỗ trợ trực tuyến" title="Chat tư vấn trực tuyến">
                <i class="bi bi-chat-dots-fill"></i>
            </button>
        `;
        document.body.appendChild(wrapper);

        const fabBtn = document.getElementById('cvdcmsChatFabBtn');
        const closeBtn = document.getElementById('cvdcmsChatCloseBtn');
        const popup = document.getElementById('cvdcmsChatPopup');

        function toggleGuestPopup(forceState) {
            state.isOpen = typeof forceState === 'boolean' ? forceState : !state.isOpen;
            if (popup) {
                popup.hidden = !state.isOpen;
                popup.classList.toggle('is-open', state.isOpen);
                popup.setAttribute('aria-hidden', String(!state.isOpen));
            }
        }
        if (fabBtn) fabBtn.addEventListener('click', () => toggleGuestPopup());
        if (closeBtn) closeBtn.addEventListener('click', () => toggleGuestPopup(false));
    }

    async function initWidget() {
        try {
            const resp = await fetch('/api/chat/context', {
                headers: { 'Accept': 'application/json' }
            });
            if (!resp.ok) {
                buildGuestWidgetDom();
                return;
            }
            const contentType = resp.headers.get('content-type') || '';
            if (!contentType.includes('application/json')) {
                buildGuestWidgetDom();
                return;
            }
            const ctx = await resp.json();
            if (!ctx || ctx.authenticated !== true) {
                buildGuestWidgetDom();
                return;
            }

            state.userId = ctx.userId;
            state.username = ctx.username;
            state.fullName = ctx.fullName;
            state.roleCode = ctx.roleCode;
            state.roleLabel = ctx.roleLabel;
            state.isAdmin = Boolean(ctx.isAdmin);
            state.canAccessInbox = Boolean(ctx.canAccessInbox);
            state.canSearchRecipients = Boolean(ctx.canSearchRecipients);
            state.canHandlePatients = Boolean(ctx.canHandlePatients);
            state.branchId = ctx.branchId;
            state.branchName = ctx.branchName || 'Cơ sở nha khoa';
            state.greetingMessage = ctx.greetingMessage || DEFAULT_GREETING;
            state.firstMessageSystemNotice = ctx.firstMessageSystemNotice || DEFAULT_FIRST_MSG_NOTICE;

            // Đồng bộ ngay Badge tin nhắn chưa đọc trên Sidebar và User Account Icon
            if (typeof ctx.totalUnreadCount === 'number') {
                updateGlobalChatUnreadBadges(ctx.totalUnreadCount);
            } else {
                refreshGlobalChatUnreadCount();
            }

            const roleCode = (ctx.roleCode || '').toUpperCase();
            const isPatient = roleCode === 'BENH_NHAN' || roleCode === 'PATIENT';

            if (!isPatient) {
                // Đối với Nhân viên / Admin: nếu đang ở các màn hình ngoài /chat thì kết nối WebSocket ngầm
                // để tự động cập nhật Badge Sidebar & User Icon ngay khi có tin nhắn mới mà không cần F5.
                if (!window.location.pathname.startsWith('/chat')) {
                    await connectWebSocket(false);
                }
                return;
            }

            buildWidgetDom();
            await loadMyConversationHistory();
            await connectWebSocket(true);
        } catch (err) {
            console.warn('Khởi tạo Floating Chat Widget thất bại:', err);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', initWidget);
    } else {
        initWidget();
    }
})();
