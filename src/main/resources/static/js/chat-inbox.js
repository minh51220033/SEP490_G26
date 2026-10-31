/**
 * CV-DCMS — Quản lý Hộp thư Tin nhắn Realtime theo Cơ sở (/chat)
 * Quy tắc phân quyền:
 * 1. Chỉ Admin mới được xem tin nhắn của tất cả các cơ sở; các role khác được phân ở cơ sở nào chỉ xem được tin nhắn ở cơ sở đó.
 * 2. Người nhận được tin nhắn của Bệnh nhân để phản hồi chỉ gồm: Lễ tân (LE_TAN), Bác sĩ (BAC_SI) cùng cơ sở và Admin (ADMIN).
 * 3. Các vai trò ngoài Bệnh nhân có thể tìm kiếm các tài khoản trong cùng cơ sở để chủ động gửi tin nhắn.
 */
(function () {
    'use strict';

    const rootEl = document.getElementById('chatInboxApp');
    if (!rootEl) return;

    const state = {
        currentUserId: Number(rootEl.dataset.userId || 0),
        currentUsername: rootEl.dataset.username || '',
        currentRole: rootEl.dataset.role || '',
        isAdmin: rootEl.dataset.isAdmin === 'true',
        canHandlePatients: rootEl.dataset.canHandlePatients === 'true',
        defaultBranchId: rootEl.dataset.defaultBranchId ? Number(rootEl.dataset.defaultBranchId) : null,

        // Trạng thái bộ lọc hộp thư
        keyword: '',
        initiatorRole: 'ALL',
        branchId: rootEl.dataset.isAdmin === 'true' ? '' : (rootEl.dataset.defaultBranchId || ''),
        unreadOnly: false,
        page: 0,
        size: 30,
        totalPages: 1,
        totalElements: 0,

        // Danh sách hội thoại & hội thoại đang mở
        conversations: [],
        activeConversationId: null,
        activeConversationMeta: null,
        activeMessages: [],
        knownMessageIds: new Set(),

        // Modal tìm kiếm tài khoản người nhận trong cùng cơ sở
        recipientKeyword: '',
        recipientRole: 'ALL',
        recipientBranchId: rootEl.dataset.isAdmin === 'true' ? '' : (rootEl.dataset.defaultBranchId || ''),
        recipientPage: 0,
        recipientSize: 30,
        recipientHasMore: false,
        recipientTotalCount: 0,
        recipientUsers: [],
        recipientDebounceTimer: null,
        recipientAbortController: null,
        recipientRequestSeq: 0,

        // WebSocket STOMP
        stompClient: null,
        connected: false,
        reconnectTimer: null,
        reconnectAttempts: 0,
        searchDebounceTimer: null,
        convAbortController: null,
        convRequestSeq: 0,
        isSending: false
    };

    // DOM Elements
    const elKeyword = document.getElementById('chatKeywordInput');
    const elRoleFilter = document.getElementById('chatInitiatorRoleFilter');
    const elBranchFilter = document.getElementById('chatBranchFilter');
    const elUnreadToggle = document.getElementById('chatUnreadOnlyToggle');
    const elClearFiltersBtn = document.getElementById('chatClearFiltersBtn');
    const elConvList = document.getElementById('chatConversationList');
    const elTotalBadge = document.getElementById('chatTotalConvBadge');
    const elWsBadge = document.getElementById('chatWsStatusBadge');
    const elPaginationBar = document.getElementById('chatPaginationBar');
    const elPageInfoText = document.getElementById('chatPageInfoText');
    const elPrevPageBtn = document.getElementById('chatPrevPageBtn');
    const elNextPageBtn = document.getElementById('chatNextPageBtn');

    const elActiveHeader = document.getElementById('chatActiveHeader');
    const elActiveAvatar = document.getElementById('chatActiveAvatar');
    const elActiveUserName = document.getElementById('chatActiveUserName');
    const elActiveUserRoleBadge = document.getElementById('chatActiveUserRoleBadge');
    const elActiveBranchBadge = document.getElementById('chatActiveBranchBadge');
    const elActiveModeBadge = document.getElementById('chatActiveModeBadge');
    const elActiveRightBadge = document.getElementById('chatActiveRightBadge');
    const elActiveSubInfo = document.getElementById('chatActiveSubInfo');
    const elMainMessages = document.getElementById('chatMainMessages');
    const elMainComposer = document.getElementById('chatMainComposer');
    const elReplyInput = document.getElementById('chatReplyInput');
    const elReplySendBtn = document.getElementById('chatReplySendBtn');

    // Modal Tìm người nhận
    const elOpenRecipientModalBtn = document.getElementById('chatOpenRecipientModalBtn');
    const elEmptyStateNewMsgBtn = document.getElementById('chatEmptyStateNewMsgBtn');
    const elRecipientModalBackdrop = document.getElementById('chatRecipientModalBackdrop');
    const elCloseRecipientModalBtn = document.getElementById('chatCloseRecipientModalBtn');
    const elRecipientSearchInput = document.getElementById('chatRecipientSearchInput');
    const elRecipientRoleSelect = document.getElementById('chatRecipientRoleSelect');
    const elRecipientBranchSelect = document.getElementById('chatRecipientBranchSelect');
    const elRecipientListContainer = document.getElementById('chatRecipientListContainer');

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

    function setWsStatus(connected) {
        state.connected = connected;
        if (!elWsBadge) return;
        if (connected) {
            elWsBadge.className = 'badge rounded-pill bg-success-subtle text-success border border-success-subtle';
            elWsBadge.innerHTML = '<i class="bi bi-broadcast me-1"></i>Realtime Đã kết nối';
        } else {
            elWsBadge.className = 'badge rounded-pill bg-warning-subtle text-warning-emphasis border border-warning-subtle';
            elWsBadge.innerHTML = '<i class="bi bi-arrow-repeat me-1"></i>Đang kết nối lại...';
        }
    }

    // =========================================================================
    // 1. TÌM KIẾM TÀI KHOẢN TRONG CÙNG CƠ SỞ ĐỂ NHẮN TIN (MODAL)
    // =========================================================================

    function openRecipientModal() {
        if (!elRecipientModalBackdrop) return;
        elRecipientModalBackdrop.hidden = false;
        if (elRecipientSearchInput) {
            elRecipientSearchInput.focus();
        }
        state.recipientPage = 0;
        fetchRecipientAccounts(false);
    }

    function closeRecipientModal() {
        if (!elRecipientModalBackdrop) return;
        elRecipientModalBackdrop.hidden = true;
    }

    async function fetchRecipientAccounts(append = false) {
        if (!elRecipientListContainer) return;

        if (!append) {
            state.recipientPage = 0;
            state.recipientUsers = [];
        }

        if (state.recipientAbortController) {
            try { state.recipientAbortController.abort(); } catch (e) { /* ignore */ }
        }
        const abortController = new AbortController();
        state.recipientAbortController = abortController;
        const requestSeq = ++state.recipientRequestSeq;

        const params = new URLSearchParams();
        if (state.recipientKeyword && state.recipientKeyword.trim() !== '') {
            params.set('keyword', state.recipientKeyword.trim());
        }
        if (state.recipientRole && state.recipientRole !== 'ALL') {
            params.set('role', state.recipientRole);
        }
        if (state.recipientBranchId && String(state.recipientBranchId).trim() !== '') {
            params.set('branchId', String(state.recipientBranchId).trim());
        }
        params.set('page', String(state.recipientPage));
        params.set('size', String(state.recipientSize));

        if (!append) {
            elRecipientListContainer.innerHTML = `
                <div class="p-4 text-center text-muted small">
                    <div class="spinner-border spinner-border-sm text-primary me-2" role="status"></div>
                    Đang tìm kiếm tài khoản trong cơ sở...
                </div>
            `;
        }

        try {
            const resp = await fetch(`/api/chat/recipients?${params.toString()}`, {
                headers: { 'Accept': 'application/json' },
                signal: abortController.signal
            });
            if (requestSeq !== state.recipientRequestSeq) return;
            if (!resp.ok) {
                throw new Error('Không thể tải danh sách tài khoản trong cơ sở.');
            }
            const hasMoreHeader = resp.headers.get('X-Has-More');
            const totalCountHeader = resp.headers.get('X-Total-Count');
            const users = await resp.json();
            if (requestSeq !== state.recipientRequestSeq) return;

            const pageItems = Array.isArray(users) ? users : [];
            state.recipientUsers = append ? state.recipientUsers.concat(pageItems) : pageItems;
            state.recipientHasMore = hasMoreHeader === 'true';
            state.recipientTotalCount = totalCountHeader ? Number(totalCountHeader) : state.recipientUsers.length;
            renderRecipientList(state.recipientUsers);
        } catch (err) {
            if (err && err.name === 'AbortError') return;
            if (requestSeq !== state.recipientRequestSeq) return;
            elRecipientListContainer.innerHTML = `
                <div class="p-4 text-center text-danger small">
                    <i class="bi bi-exclamation-triangle me-1"></i>${escapeHtml(err.message || 'Lỗi tải danh sách tài khoản.')}
                </div>
            `;
        }
    }

    function renderRecipientList(users) {
        if (!elRecipientListContainer) return;

        if (!users || users.length === 0) {
            elRecipientListContainer.innerHTML = `
                <div class="p-4 text-center text-muted small">
                    <i class="bi bi-people fs-3 d-block mb-1 text-secondary"></i>
                    Không tìm thấy tài khoản nào khớp với điều kiện lọc trong cơ sở.
                </div>
            `;
            return;
        }

        let html = '';
        for (const u of users) {
            const fullName = u.fullName || u.username || ('Tài khoản #' + u.userId);
            const initial = fullName.trim().charAt(0).toUpperCase() || 'U';
            const roleCode = u.roleCode || 'BENH_NHAN';
            const roleLabel = u.roleLabel || 'Bệnh nhân';
            const branchName = u.branchName || 'Cơ sở';
            const subDetails = [
                u.username ? `@${u.username}` : '',
                u.patientCode ? `Mã BN: ${u.patientCode}` : '',
                u.phoneNumber ? `SĐT: ${u.phoneNumber}` : '',
                u.email || ''
            ].filter(Boolean).join(' • ');

            const profilesBadge = (u.patientProfilesSummary && Number(u.patientProfileCount || 0) > 0)
                ? `<div class="text-primary text-truncate" style="font-size:11px;margin-top:2px;" title="${escapeHtml(u.patientProfilesSummary)}">
                       <i class="bi bi-person-vcard me-1"></i>Hồ sơ BN (${Number(u.patientProfileCount)}): ${escapeHtml(u.patientProfilesSummary)}
                   </div>`
                : '';

            const actionLabel = u.existingConversationId ? 'Mở hội thoại' : 'Nhắn tin';

            html += `
                <div class="chat-recipient-item" data-target-user-id="${u.userId}" data-target-branch-id="${u.branchId || ''}">
                    <div class="d-flex align-items-center gap-3 overflow-hidden">
                        <span class="chat-recipient-avatar">${escapeHtml(initial)}</span>
                        <div class="overflow-hidden">
                            <div class="d-flex align-items-center flex-wrap gap-2">
                                <span class="fw-bold text-dark" style="font-size:13.5px;">${escapeHtml(fullName)}</span>
                                <span class="chat-role-badge chat-role-badge--${escapeHtml(roleCode)}">${escapeHtml(roleLabel)}</span>
                                <span class="chat-branch-badge">
                                    <i class="bi bi-building"></i>
                                    <span>${escapeHtml(branchName)}</span>
                                </span>
                            </div>
                            <div class="text-muted text-truncate" style="font-size:11.5px;margin-top:2px;">
                                ${escapeHtml(subDetails)}
                            </div>
                            ${profilesBadge}
                        </div>
                    </div>
                    <button type="button" class="btn btn-sm btn-primary fw-semibold px-3 flex-shrink-0" style="border-radius:8px;font-size:12px;">
                        <i class="bi bi-chat-dots-fill me-1"></i>${actionLabel}
                    </button>
                </div>
            `;
        }

        if (state.recipientHasMore) {
            html += `
                <div class="p-2 text-center border-top bg-light">
                    <button type="button" id="chatLoadMoreRecipientsBtn" class="btn btn-sm btn-outline-primary fw-semibold">
                        <i class="bi bi-arrow-down-circle me-1"></i>Tải thêm tài khoản (${users.length}/${state.recipientTotalCount})
                    </button>
                </div>
            `;
        }

        elRecipientListContainer.innerHTML = html;

        elRecipientListContainer.querySelectorAll('.chat-recipient-item').forEach((item) => {
            item.addEventListener('click', () => {
                const targetUserId = Number(item.getAttribute('data-target-user-id'));
                const targetBranchIdAttr = item.getAttribute('data-target-branch-id');
                const targetBranchId = targetBranchIdAttr ? Number(targetBranchIdAttr) : null;
                if (targetUserId) {
                    startConversationWithUser(targetUserId, targetBranchId);
                }
            });
        });

        const loadMoreBtn = document.getElementById('chatLoadMoreRecipientsBtn');
        if (loadMoreBtn) {
            loadMoreBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                state.recipientPage += 1;
                fetchRecipientAccounts(true);
            });
        }
    }

    async function startConversationWithUser(targetUserId, targetBranchId) {
        try {
            const resp = await fetch('/api/chat/conversations/start', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Accept': 'application/json'
                },
                body: JSON.stringify({
                    targetUserId: targetUserId,
                    branchId: targetBranchId
                })
            });

            if (!resp.ok) {
                const errData = await resp.json().catch(() => ({}));
                throw new Error(errData.message || 'Không thể khởi tạo cuộc hội thoại với người nhận này.');
            }

            const convData = await resp.json();
            closeRecipientModal();

            if (convData && convData.conversationId) {
                state.activeConversationId = Number(convData.conversationId);
                await fetchConversations(false);
                await selectConversation(convData.conversationId);
            }
        } catch (err) {
            alert(err.message || 'Không thể bắt đầu cuộc hội thoại.');
        }
    }

    // =========================================================================
    // 2. TẢI DANH SÁCH HỘI THOẠI KÈM TÌM KIẾM & LỌC (AND LOGIC)
    // =========================================================================

    async function fetchConversations(preserveActiveSelection = true) {
        if (state.convAbortController) {
            try { state.convAbortController.abort(); } catch (e) { /* ignore */ }
        }
        const abortController = new AbortController();
        state.convAbortController = abortController;
        const requestSeq = ++state.convRequestSeq;

        const params = new URLSearchParams();
        if (state.keyword && state.keyword.trim() !== '') {
            params.set('keyword', state.keyword.trim());
        }
        if (state.initiatorRole && state.initiatorRole !== 'ALL') {
            params.set('initiatorRole', state.initiatorRole);
        }
        if (state.branchId && String(state.branchId).trim() !== '') {
            params.set('branchId', String(state.branchId).trim());
        }
        if (state.unreadOnly) {
            params.set('unreadOnly', 'true');
        }
        params.set('page', String(state.page));
        params.set('size', String(state.size));

        try {
            const resp = await fetch(`/api/chat/conversations?${params.toString()}`, {
                headers: { 'Accept': 'application/json' },
                signal: abortController.signal
            });
            if (requestSeq !== state.convRequestSeq) return;
            if (!resp.ok) {
                throw new Error('Không thể tải danh sách hội thoại');
            }
            const pageData = await resp.json();
            if (requestSeq !== state.convRequestSeq) return;
            state.conversations = Array.isArray(pageData.content) ? pageData.content : [];
            state.totalPages = Math.max(Number(pageData.totalPages || 1), 1);
            state.totalElements = Number(pageData.totalElements || state.conversations.length);

            if (elTotalBadge) {
                elTotalBadge.textContent = String(state.totalElements);
            }

            renderConversationList();
            renderPagination();

            if (preserveActiveSelection && !state.activeConversationId && state.conversations.length > 0) {
                selectConversation(state.conversations[0].conversationId);
            }
        } catch (err) {
            if (err && err.name === 'AbortError') return;
            if (requestSeq !== state.convRequestSeq) return;
            console.error(err);
            if (elConvList) {
                elConvList.innerHTML = `
                    <div class="p-4 text-center text-danger small">
                        <i class="bi bi-exclamation-circle me-1"></i>Không thể tải danh sách hội thoại. Vui lòng thử lại.
                    </div>
                `;
            }
        }
    }

    function renderConversationList() {
        if (!elConvList) return;

        if (!state.conversations || state.conversations.length === 0) {
            elConvList.innerHTML = `
                <div class="p-4 text-center text-muted">
                    <i class="bi bi-inbox fs-2 d-block mb-2 text-secondary"></i>
                    <div class="fw-semibold small">Không tìm thấy cuộc hội thoại nào</div>
                    <div style="font-size:12px;">Bấm <strong>Nhắn tin mới</strong> để tìm tài khoản trong cơ sở và bắt đầu trò chuyện.</div>
                </div>
            `;
            return;
        }

        let html = '';
        for (const c of state.conversations) {
            const isActive = Number(c.conversationId) === Number(state.activeConversationId);
            const isUnread = Boolean(c.unread) && Number(c.unreadCount) > 0;
            const itemClass = [
                'chat-conv-item',
                isActive ? 'is-active' : '',
                isUnread ? 'is-unread' : ''
            ].filter(Boolean).join(' ');

            const isDirect = Boolean(c.directConversation);
            const displayName = isDirect
                ? (c.counterpartFullName || c.counterpartUsername || c.initiatorFullName)
                : (c.initiatorFullName || c.initiatorUsername || ('Người dùng #' + c.initiatorUserId));
            const roleCode = isDirect
                ? (c.counterpartRoleCode || c.initiatorRoleCode || 'LE_TAN')
                : (c.initiatorRoleCode || 'BENH_NHAN');
            const roleLabel = isDirect
                ? (c.counterpartRoleLabel || c.initiatorRoleLabel || 'Nhân viên')
                : (c.initiatorRoleLabel || 'Bệnh nhân');

            const branchName = c.branchName || 'Chưa gắn cơ sở';
            const previewPrefix = c.lastMessageSenderName ? `${c.lastMessageSenderName}: ` : '';
            const previewText = c.lastMessageText
                ? `${previewPrefix}${c.lastMessageText}`
                : 'Bắt đầu cuộc trò chuyện...';

            const directBadge = isDirect
                ? `<span class="chat-direct-badge" title="Hội thoại trực tiếp giữa các tài khoản trong cơ sở"><i class="bi bi-person-lines-fill"></i>1-1</span>`
                : '';

            const unreadBadge = isUnread
                ? `<span class="chat-unread-pill">${c.unreadCount > 99 ? '99+' : c.unreadCount}</span>`
                : '';

            html += `
                <div class="${itemClass}" data-conversation-id="${c.conversationId}">
                    <div class="chat-conv-top">
                        <h6 class="chat-conv-name" title="${escapeHtml(displayName)}">
                            ${escapeHtml(displayName)}
                        </h6>
                        <span class="chat-conv-time">${escapeHtml(formatTime(c.lastMessageAt))}</span>
                    </div>
                    <div class="chat-conv-meta">
                        <span class="chat-role-badge chat-role-badge--${escapeHtml(roleCode)}">
                            ${escapeHtml(roleLabel)}
                        </span>
                        ${directBadge}
                        <span class="chat-branch-badge">
                            <i class="bi bi-building"></i>
                            <span>${escapeHtml(branchName)}</span>
                        </span>
                        <span class="text-muted" style="font-size:11px;">#${c.conversationId}</span>
                    </div>
                    <div class="chat-conv-bottom">
                        <p class="chat-conv-preview">${escapeHtml(previewText)}</p>
                        ${unreadBadge}
                    </div>
                </div>
            `;
        }

        elConvList.innerHTML = html;

        elConvList.querySelectorAll('.chat-conv-item').forEach((item) => {
            item.addEventListener('click', () => {
                const cid = Number(item.getAttribute('data-conversation-id'));
                if (cid) {
                    selectConversation(cid);
                }
            });
        });
    }

    function renderPagination() {
        if (!elPaginationBar) return;
        if (state.totalPages <= 1) {
            elPaginationBar.hidden = true;
            return;
        }
        elPaginationBar.hidden = false;
        elPageInfoText.textContent = `Trang ${state.page + 1} / ${state.totalPages}`;
        elPrevPageBtn.disabled = state.page <= 0;
        elNextPageBtn.disabled = state.page + 1 >= state.totalPages;
    }

    // =========================================================================
    // 3. XEM CHI TIẾT CUỘC HỘI THOẠI & ĐÁNH DẤU ĐÃ ĐỌC
    // =========================================================================

    async function selectConversation(conversationId) {
        state.activeConversationId = Number(conversationId);
        renderConversationList();

        try {
            const resp = await fetch(`/api/chat/conversations/${encodeURIComponent(conversationId)}/messages`, {
                headers: { 'Accept': 'application/json' }
            });
            if (!resp.ok) {
                throw new Error('Không thể tải chi tiết tin nhắn của hội thoại #' + conversationId);
            }
            const data = await resp.json();
            state.activeConversationMeta = data;
            state.activeMessages = Array.isArray(data.messages) ? data.messages : [];
            state.knownMessageIds.clear();
            for (const m of state.activeMessages) {
                if (m.messageId) state.knownMessageIds.add(Number(m.messageId));
            }

            renderActiveConversationHeader(data);
            renderActiveMessages();

            if (elMainComposer) elMainComposer.hidden = false;
            if (elReplyInput) elReplyInput.focus();

            await markConversationRead(conversationId);
        } catch (err) {
            console.error(err);
        }
    }

    function renderActiveConversationHeader(meta) {
        if (!elActiveHeader || !meta) return;
        elActiveHeader.hidden = false;

        const isDirect = Boolean(meta.directConversation);
        const name = isDirect
            ? (meta.counterpartFullName || meta.counterpartUsername || meta.initiatorFullName || 'Người dùng')
            : (meta.initiatorFullName || meta.initiatorUsername || 'Người dùng');
        const roleCode = isDirect
            ? (meta.counterpartRoleCode || meta.initiatorRoleCode || 'LE_TAN')
            : (meta.initiatorRoleCode || 'BENH_NHAN');
        const roleLabel = isDirect
            ? (meta.counterpartRoleLabel || meta.initiatorRoleLabel || 'Nhân viên')
            : (meta.initiatorRoleLabel || 'Bệnh nhân');
        const phone = isDirect ? meta.counterpartPhone : meta.initiatorPhone;
        const username = isDirect ? meta.counterpartUsername : meta.initiatorUsername;

        const branchName = meta.branchName || 'Chưa gắn cơ sở';
        const phonePart = phone ? ` • SĐT: ${phone}` : '';
        const usernamePart = username ? ` (@${username})` : '';

        if (elActiveAvatar) {
            elActiveAvatar.textContent = name.trim().charAt(0).toUpperCase() || 'U';
        }
        if (elActiveUserName) {
            elActiveUserName.textContent = name + usernamePart;
        }
        if (elActiveUserRoleBadge) {
            elActiveUserRoleBadge.className = `chat-role-badge chat-role-badge--${roleCode}`;
            elActiveUserRoleBadge.textContent = roleLabel;
        }
        if (elActiveBranchBadge) {
            elActiveBranchBadge.innerHTML = `<i class="bi bi-geo-alt-fill text-primary"></i><span>${escapeHtml(branchName)}</span>`;
        }
        if (elActiveModeBadge) {
            elActiveModeBadge.hidden = !isDirect;
        }
        if (elActiveRightBadge) {
            if (isDirect) {
                elActiveRightBadge.className = 'badge bg-primary-subtle text-primary border border-primary-subtle';
                elActiveRightBadge.innerHTML = '<i class="bi bi-person-check-fill me-1"></i>Trò chuyện trực tiếp cùng cơ sở';
            } else {
                elActiveRightBadge.className = 'badge bg-success-subtle text-success border border-success-subtle';
                elActiveRightBadge.innerHTML = '<i class="bi bi-people-fill me-1"></i>Lễ tân, Bác sĩ cơ sở &amp; Admin tiếp nhận';
            }
        }
        if (elActiveSubInfo) {
            elActiveSubInfo.textContent = `Mã hội thoại: #${meta.conversationId}${phonePart}`;
        }
    }

    function renderActiveMessages() {
        if (!elMainMessages) return;

        if (!state.activeConversationId) {
            elMainMessages.innerHTML = `
                <div class="chat-empty-state">
                    <div class="chat-empty-state__icon"><i class="bi bi-chat-square-dots"></i></div>
                    <h6 class="fw-bold text-dark mb-1">Chưa chọn cuộc hội thoại</h6>
                    <p class="small mb-0">Hãy chọn một cuộc hội thoại ở cột bên trái hoặc bấm Nhắn tin mới để chọn người nhận.</p>
                </div>
            `;
            return;
        }

        if (!state.activeMessages || state.activeMessages.length === 0) {
            elMainMessages.innerHTML = `
                <div class="chat-empty-state">
                    <div class="chat-empty-state__icon"><i class="bi bi-chat-dots"></i></div>
                    <h6 class="fw-bold text-dark mb-1">Chưa có tin nhắn nào</h6>
                    <p class="small mb-0">Hãy gửi tin nhắn đầu tiên ở khung bên dưới để bắt đầu trò chuyện.</p>
                </div>
            `;
            return;
        }

        const isDirect = state.activeConversationMeta && Boolean(state.activeConversationMeta.directConversation);
        const initiatorId = state.activeConversationMeta ? Number(state.activeConversationMeta.initiatorUserId) : null;
        let html = '';

        for (const msg of state.activeMessages) {
            let isOutgoing;
            if (isDirect) {
                isOutgoing = Number(msg.senderId) === state.currentUserId;
            } else {
                isOutgoing = initiatorId !== null
                    ? Number(msg.senderId) !== initiatorId
                    : Number(msg.senderId) === state.currentUserId;
            }

            const rowClass = isOutgoing
                ? 'chat-msg-row chat-msg-row--outgoing'
                : 'chat-msg-row chat-msg-row--incoming';

            const senderName = msg.senderFullName || msg.senderUsername || ('Người dùng #' + msg.senderId);
            const senderRoleCode = msg.senderRoleCode || 'BENH_NHAN';
            const senderRoleLabel = msg.senderRoleLabel || 'Bệnh nhân';

            html += `
                <div class="${rowClass}" data-message-id="${escapeHtml(msg.messageId || '')}">
                    <div class="chat-msg-sender">
                        <span>${escapeHtml(senderName)}</span>
                        <span class="chat-role-badge chat-role-badge--${escapeHtml(senderRoleCode)}">${escapeHtml(senderRoleLabel)}</span>
                    </div>
                    <div class="chat-msg-bubble">${escapeHtml(msg.messageText)}</div>
                    <div class="chat-msg-time">${escapeHtml(formatTime(msg.sentAt))}</div>
                </div>
            `;
        }

        elMainMessages.innerHTML = html;
        elMainMessages.scrollTop = elMainMessages.scrollHeight;
    }

    async function markConversationRead(conversationId) {
        const target = state.conversations.find((c) => Number(c.conversationId) === Number(conversationId));
        if (target) {
            target.unreadCount = 0;
            target.unread = false;
            renderConversationList();
        }
        try {
            await fetch(`/api/chat/conversations/${encodeURIComponent(conversationId)}/read`, {
                method: 'POST',
                headers: { 'Accept': 'application/json' }
            });
        } catch (e) {
            // Bỏ qua lỗi mạng tạm thời
        }
    }

    // =========================================================================
    // 4. GỬI TIN NHẮN PHẢN HỒI TỪ HỘP THƯ
    // =========================================================================

    async function handleSendReply() {
        if (!state.activeConversationId || !elReplyInput || state.isSending) return;
        const text = elReplyInput.value.trim();
        if (!text) return;

        state.isSending = true;
        if (elReplySendBtn) elReplySendBtn.disabled = true;

        const clientMessageId = 'imsg_' + Date.now() + '_' + Math.random().toString(36).substring(2, 8);
        const payload = {
            conversationId: state.activeConversationId,
            messageText: text,
            clientMessageId: clientMessageId
        };

        elReplyInput.value = '';

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
                throw new Error(errData.message || 'Không thể gửi tin nhắn.');
            }

            const savedMsg = await resp.json();
            handleIncomingRealtimeMessage(savedMsg);
        } catch (err) {
            elReplyInput.value = text;
            alert(err.message || 'Gửi tin nhắn thất bại. Vui lòng thử lại.');
        } finally {
            state.isSending = false;
            if (elReplySendBtn) elReplySendBtn.disabled = false;
            elReplyInput.focus();
        }
    }

    // =========================================================================
    // 5. XỬ LÝ SỰ KIỆN REALTIME QUA STOMP WEBSOCKET
    // =========================================================================

    function handleIncomingRealtimeMessage(msg) {
        if (!msg || !msg.conversationId) return;

        if (Number(msg.conversationId) === Number(state.activeConversationId)) {
            if (!msg.messageId || !state.knownMessageIds.has(Number(msg.messageId))) {
                if (msg.messageId) {
                    state.knownMessageIds.add(Number(msg.messageId));
                }
                state.activeMessages.push(msg);
                renderActiveMessages();
            }
            if (Number(msg.senderId) !== state.currentUserId) {
                markConversationRead(msg.conversationId);
            }
        }

        fetchConversations(false);
    }

    function connectWebSocket() {
        if (typeof window.SockJS === 'undefined' || typeof window.Stomp === 'undefined') {
            return;
        }
        const socket = new window.SockJS('/ws-chat');
        const stomp = window.Stomp.over(socket);
        stomp.debug = null;

        stomp.connect(
            {},
            function () {
                state.stompClient = stomp;
                setWsStatus(true);

                if (state.reconnectAttempts > 0) {
                    fetchConversations(false);
                    if (state.activeConversationId) {
                        selectConversation(state.activeConversationId);
                    }
                }
                state.reconnectAttempts = 0;

                // Đăng ký hàng đợi cá nhân để luôn nhận được tin nhắn 1-1 trực tiếp
                stomp.subscribe('/user/queue/chat/messages', function (frame) {
                    try {
                        const msg = JSON.parse(frame.body);
                        handleIncomingRealtimeMessage(msg);
                    } catch (e) {
                        console.error(e);
                    }
                });

                if (state.isAdmin) {
                    stomp.subscribe('/topic/chat/admin', function (frame) {
                        try {
                            const msg = JSON.parse(frame.body);
                            handleIncomingRealtimeMessage(msg);
                        } catch (e) {
                            console.error(e);
                        }
                    });
                } else if (state.canHandlePatients) {
                    // Chỉ Lễ tân (LE_TAN) và Bác sĩ (BAC_SI) mới đăng ký kênh hộp thư Bệnh nhân của cơ sở được phân công
                    const branchOptions = elBranchFilter ? Array.from(elBranchFilter.options) : [];
                    const subscribedBranchIds = new Set();
                    for (const opt of branchOptions) {
                        const bid = Number(opt.value);
                        if (bid > 0 && !subscribedBranchIds.has(bid)) {
                            subscribedBranchIds.add(bid);
                            stomp.subscribe(`/topic/chat/branch/${bid}`, function (frame) {
                                try {
                                    const msg = JSON.parse(frame.body);
                                    handleIncomingRealtimeMessage(msg);
                                } catch (e) {
                                    console.error(e);
                                }
                            });
                        }
                    }
                }
            },
            function () {
                setWsStatus(false);
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

    // =========================================================================
    // 6. GẮN SỰ KIỆN TÌM KIẾM, BỘ LỌC, MODAL NGƯỜI NHẬN & KHỞI TẠO
    // =========================================================================

    if (elOpenRecipientModalBtn) {
        elOpenRecipientModalBtn.addEventListener('click', openRecipientModal);
    }
    if (elEmptyStateNewMsgBtn) {
        elEmptyStateNewMsgBtn.addEventListener('click', openRecipientModal);
    }
    if (elCloseRecipientModalBtn) {
        elCloseRecipientModalBtn.addEventListener('click', closeRecipientModal);
    }
    if (elRecipientModalBackdrop) {
        elRecipientModalBackdrop.addEventListener('click', (e) => {
            if (e.target === elRecipientModalBackdrop) {
                closeRecipientModal();
            }
        });
    }

    if (elRecipientSearchInput) {
        elRecipientSearchInput.addEventListener('input', () => {
            if (state.recipientDebounceTimer) {
                clearTimeout(state.recipientDebounceTimer);
            }
            state.recipientDebounceTimer = setTimeout(() => {
                state.recipientKeyword = elRecipientSearchInput.value || '';
                fetchRecipientAccounts(false);
            }, 350);
        });
    }

    if (elRecipientRoleSelect) {
        elRecipientRoleSelect.addEventListener('change', () => {
            state.recipientRole = elRecipientRoleSelect.value || 'ALL';
            fetchRecipientAccounts(false);
        });
    }

    if (elRecipientBranchSelect) {
        elRecipientBranchSelect.addEventListener('change', () => {
            state.recipientBranchId = elRecipientBranchSelect.value || '';
            fetchRecipientAccounts(false);
        });
    }

    if (elKeyword) {
        elKeyword.addEventListener('input', () => {
            if (state.searchDebounceTimer) {
                clearTimeout(state.searchDebounceTimer);
            }
            state.searchDebounceTimer = setTimeout(() => {
                state.keyword = elKeyword.value || '';
                state.page = 0;
                fetchConversations(false);
            }, 350);
        });
    }

    if (elRoleFilter) {
        elRoleFilter.addEventListener('change', () => {
            state.initiatorRole = elRoleFilter.value || 'ALL';
            state.page = 0;
            fetchConversations(false);
        });
    }

    if (elBranchFilter) {
        elBranchFilter.addEventListener('change', () => {
            state.branchId = elBranchFilter.value || '';
            state.page = 0;
            fetchConversations(false);
        });
    }

    if (elUnreadToggle) {
        elUnreadToggle.addEventListener('change', () => {
            state.unreadOnly = Boolean(elUnreadToggle.checked);
            state.page = 0;
            fetchConversations(false);
        });
    }

    if (elClearFiltersBtn) {
        elClearFiltersBtn.addEventListener('click', () => {
            state.keyword = '';
            state.initiatorRole = 'ALL';
            state.unreadOnly = false;
            state.page = 0;
            if (state.isAdmin) {
                state.branchId = '';
                if (elBranchFilter) elBranchFilter.value = '';
            } else if (state.defaultBranchId && elBranchFilter) {
                state.branchId = String(state.defaultBranchId);
                elBranchFilter.value = String(state.defaultBranchId);
            }
            if (elKeyword) elKeyword.value = '';
            if (elRoleFilter) elRoleFilter.value = 'ALL';
            if (elUnreadToggle) elUnreadToggle.checked = false;
            fetchConversations(false);
        });
    }

    if (elPrevPageBtn) {
        elPrevPageBtn.addEventListener('click', () => {
            if (state.page > 0) {
                state.page -= 1;
                fetchConversations(false);
            }
        });
    }

    if (elNextPageBtn) {
        elNextPageBtn.addEventListener('click', () => {
            if (state.page + 1 < state.totalPages) {
                state.page += 1;
                fetchConversations(false);
            }
        });
    }

    if (elReplySendBtn) {
        elReplySendBtn.addEventListener('click', handleSendReply);
    }

    if (elReplyInput) {
        elReplyInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                handleSendReply();
            }
        });
    }

    fetchConversations(true);
    connectWebSocket();
})();
