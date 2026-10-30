/**
 * CV-DCMS — Floating Realtime Chat Widget (Góc dưới bên phải toàn hệ thống)
 * - Chỉ hiển thị khi người dùng đã đăng nhập (/api/chat/context -> authenticated === true).
 * - Singleton guard chống gắn trùng sự kiện hoặc nhân đôi DOM.
 * - Đối với Bệnh nhân (BENH_NHAN): chỉ chat với đội ngũ Lễ tân, Bác sĩ thuộc cơ sở gắn với tài khoản và Admin.
 * - Đối với các vai trò khác (LE_TAN, BAC_SI, QUAN_LY_KHO, KE_TOAN, ADMIN): hỗ trợ tìm kiếm tài khoản
 *   trong cùng cơ sở ngay trên widget để chủ động gửi tin nhắn cho người muốn gửi
 *   (trong đó chỉ Lễ tân, Bác sĩ và Admin mới được phép nhận và phản hồi tin nhắn của Bệnh nhân).
 */
(function () {
    'use strict';

    if (window.__CVDCMS_CHAT_WIDGET_INITIALIZED__) {
        return;
    }
    window.__CVDCMS_CHAT_WIDGET_INITIALIZED__ = true;

    const DEFAULT_GREETING = 'Xin chào! Bạn cần chúng tôi hỗ trợ điều gì?';
    const DEFAULT_FIRST_MSG_NOTICE = 'Cảm ơn bạn đã liên hệ! Tin nhắn của bạn đã được gửi đến đội ngũ nhân viên tại cơ sở. Vui lòng chờ trong giây lát, chúng tôi sẽ phản hồi sớm nhất có thể.';

    const state = {
        userId: null,
        username: null,
        fullName: null,
        roleCode: null,
        roleLabel: null,
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
        greetingMessage: DEFAULT_GREETING,
        firstMessageSystemNotice: DEFAULT_FIRST_MSG_NOTICE,
        messages: [],
        knownMessageIds: new Set(),
        knownClientIds: new Set(),
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

            <button type="button" id="cvdcmsChatWidgetFab" class="cvdcms-chat-fab" title="Chat trực tiếp theo cơ sở">
                <i class="bi bi-chat-dots-fill" id="cvdcmsChatWidgetFabIcon"></i>
                <span id="cvdcmsChatWidgetUnreadBadge" class="cvdcms-chat-fab__badge" hidden>0</span>
            </button>
        `;

        document.body.appendChild(wrapper);

        const fab = document.getElementById('cvdcmsChatWidgetFab');
        const closeBtn = document.getElementById('cvdcmsChatWidgetCloseBtn');
        const sendBtn = document.getElementById('cvdcmsChatWidgetSendBtn');
        const input = document.getElementById('cvdcmsChatWidgetInput');
        const searchUserBtn = document.getElementById('cvdcmsWidgetSearchUserBtn');
        const resetHomeBtn = document.getElementById('cvdcmsWidgetResetHomeConvBtn');
        const recKeywordInput = document.getElementById('cvdcmsWidgetRecipientKeyword');
        const recRoleSelect = document.getElementById('cvdcmsWidgetRecipientRole');

        fab.addEventListener('click', togglePopup);
        closeBtn.addEventListener('click', () => setPopupOpen(false));
        sendBtn.addEventListener('click', handleSendMessage);

        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSendMessage();
            }
        });

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
        if (!badge) return;
        if (state.unreadCount > 0 && !state.isOpen) {
            badge.textContent = state.unreadCount > 99 ? '99+' : String(state.unreadCount);
            badge.hidden = false;
        } else {
            badge.hidden = true;
        }
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

        const headerText = state.activeTargetName
            ? `Đang nhắn tin với ${state.activeTargetName} (${state.activeTargetRoleLabel || 'Cùng cơ sở'})`
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
            const rowClass = isMine ? 'chat-msg-row chat-msg-row--outgoing' : 'chat-msg-row chat-msg-row--incoming';
            const roleCode = msg.senderRoleCode || 'BENH_NHAN';
            const roleLabel = msg.senderRoleLabel || 'Bệnh nhân';
            const senderName = msg.senderFullName || msg.senderUsername || 'Người dùng';

            html += `
                <div class="${rowClass}" data-message-id="${escapeHtml(msg.messageId || '')}">
                    <div class="chat-msg-sender">
                        <span>${escapeHtml(senderName)}</span>
                        <span class="chat-role-badge chat-role-badge--${escapeHtml(roleCode)}">${escapeHtml(roleLabel)}</span>
                    </div>
                    <div class="chat-msg-bubble">${escapeHtml(msg.messageText)}</div>
                    <div class="chat-msg-time">${escapeHtml(formatTime(msg.sentAt))}</div>
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
        scrollToBottom();
    }

    function scrollToBottom() {
        const container = document.getElementById('cvdcmsChatWidgetMessages');
        if (container) {
            container.scrollTop = container.scrollHeight;
        }
    }

    function appendOrUpdateMessage(msg) {
        if (!msg) return;

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
            await fetch(`/api/chat/conversations/${encodeURIComponent(conversationId)}/read`, {
                method: 'POST',
                headers: { 'Accept': 'application/json' }
            });
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

    async function connectWebSocket() {
        try {
            await ensureWebSocketLibraries();
        } catch (e) {
            console.warn('Không thể tải thư viện WebSocket cho widget:', e);
            return;
        }

        if (state.stompClient && state.connected) {
            return;
        }

        const socket = new window.SockJS('/ws-chat');
        const stomp = window.Stomp.over(socket);
        stomp.debug = null;

        stomp.connect(
            {},
            function () {
                state.stompClient = stomp;
                state.connected = true;
                if (state.reconnectAttempts > 0) {
                    loadMyConversationHistory();
                }
                state.reconnectAttempts = 0;

                stomp.subscribe('/user/queue/chat/messages', function (frame) {
                    try {
                        const msg = JSON.parse(frame.body);
                        appendOrUpdateMessage(msg);
                    } catch (err) {
                        console.error('Lỗi đọc tin nhắn STOMP:', err);
                    }
                });
            },
            function () {
                state.connected = false;
                scheduleReconnect();
            }
        );
    }

    function scheduleReconnect() {
        if (state.reconnectTimer) {
            clearTimeout(state.reconnectTimer);
        }
        state.reconnectAttempts += 1;
        const delay = Math.min(2000 * Math.pow(1.5, Math.min(state.reconnectAttempts, 6)), 15000);
        state.reconnectTimer = setTimeout(() => {
            connectWebSocket();
        }, delay);
    }

    async function initWidget() {
        try {
            const resp = await fetch('/api/chat/context', {
                headers: { 'Accept': 'application/json' }
            });
            if (!resp.ok) return;
            const ctx = await resp.json();
            if (!ctx || ctx.authenticated !== true) {
                return;
            }

            state.userId = ctx.userId;
            state.username = ctx.username;
            state.fullName = ctx.fullName;
            state.roleCode = ctx.roleCode;
            state.roleLabel = ctx.roleLabel;
            state.canAccessInbox = Boolean(ctx.canAccessInbox);
            state.canSearchRecipients = Boolean(ctx.canSearchRecipients);
            state.canHandlePatients = Boolean(ctx.canHandlePatients);
            state.branchId = ctx.branchId;
            state.branchName = ctx.branchName || 'Cơ sở nha khoa';
            state.greetingMessage = ctx.greetingMessage || DEFAULT_GREETING;
            state.firstMessageSystemNotice = ctx.firstMessageSystemNotice || DEFAULT_FIRST_MSG_NOTICE;

            buildWidgetDom();
            await loadMyConversationHistory();
            await connectWebSocket();
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
