/**
 * ============================================================================
 * CV-DCMS FINANCE DIGITAL SIGNATURE
 * Tai su dung chuan ky dien tu PNG (data:image/png;base64,...) luu qua
 * InventoryFileStorageUtil.saveSignatureDataUrl -> /uploads/signatures/**
 * Dong bo live giua khung "Xac nhan" ben trai va khung "Nguoi lap phieu" ben phai.
 * ============================================================================
 */
(function () {
    'use strict';

    let activeKey = null;
    let hasStrokes = false;
    let drawing = false;
    let lastX = 0;
    let lastY = 0;

    function ensureModal() {
        let modal = document.getElementById('financeSignatureModal');
        if (modal) return modal;

        modal = document.createElement('div');
        modal.id = 'financeSignatureModal';
        modal.className = 'fin-sig-modal-backdrop';
        modal.hidden = true;
        modal.innerHTML = `
            <div class="fin-sig-modal-card" role="dialog" aria-modal="true" aria-labelledby="finSigModalTitle">
                <div class="fin-sig-modal-head">
                    <h5 id="finSigModalTitle" class="m-0 fw-bold text-dark">
                        <i class="bi bi-pen-fill text-primary me-2"></i>Ký điện tử xác nhận chứng từ
                    </h5>
                    <button type="button" class="btn-close" id="finSigCloseBtn" aria-label="Đóng"></button>
                </div>
                <div class="fin-sig-modal-body">
                    <p class="text-muted small mb-2">
                        Vui lòng vẽ chữ ký bằng chuột, màn hình cảm ứng hoặc bút cảm ứng vào khung bên dưới. Họ tên người ký tự động ghi nhận theo tài khoản đang đăng nhập.
                    </p>
                    <div class="fin-sig-canvas-wrap">
                        <canvas id="finSignatureCanvas" width="520" height="190"></canvas>
                    </div>
                    <div id="finSigWarn" class="text-danger small mt-2" style="display: none;">
                        <i class="bi bi-exclamation-circle me-1"></i>Vui lòng ký tên lên khung trước khi bấm Xác nhận.
                    </div>
                </div>
                <div class="fin-sig-modal-foot">
                    <button type="button" class="fin-btn fin-btn-outline" id="finSigClearCanvasBtn">Xóa ký lại</button>
                    <div class="d-flex gap-2">
                        <button type="button" class="fin-btn fin-btn-outline" id="finSigCancelBtn">Hủy</button>
                        <button type="button" class="fin-btn fin-btn-primary" id="finSigConfirmBtn">
                            <i class="bi bi-check2-circle"></i> Xác nhận chữ ký
                        </button>
                    </div>
                </div>
            </div>
        `;
        document.body.appendChild(modal);
        bindCanvasAndModalEvents(modal);
        return modal;
    }

    function bindCanvasAndModalEvents(modal) {
        const canvas = document.getElementById('finSignatureCanvas');
        const ctx = canvas ? canvas.getContext('2d') : null;

        function resetCtxStyle() {
            if (!ctx) return;
            ctx.strokeStyle = '#0f172a';
            ctx.lineWidth = 2.4;
            ctx.lineCap = 'round';
            ctx.lineJoin = 'round';
        }
        resetCtxStyle();

        function getPoint(e) {
            const rect = canvas.getBoundingClientRect();
            const scaleX = canvas.width / (rect.width || canvas.width);
            const scaleY = canvas.height / (rect.height || canvas.height);
            let clientX = e.clientX;
            let clientY = e.clientY;
            if (e.touches && e.touches.length > 0) {
                clientX = e.touches[0].clientX;
                clientY = e.touches[0].clientY;
            }
            return {
                x: (clientX - rect.left) * scaleX,
                y: (clientY - rect.top) * scaleY
            };
        }

        function startDraw(e) {
            if (!ctx) return;
            if (e.cancelable) e.preventDefault();
            drawing = true;
            hasStrokes = true;
            hideWarn();
            const pt = getPoint(e);
            lastX = pt.x;
            lastY = pt.y;
            ctx.beginPath();
            ctx.moveTo(lastX, lastY);
            ctx.lineTo(lastX + 0.1, lastY + 0.1);
            ctx.stroke();
        }

        function moveDraw(e) {
            if (!drawing || !ctx) return;
            if (e.cancelable) e.preventDefault();
            const pt = getPoint(e);
            ctx.beginPath();
            ctx.moveTo(lastX, lastY);
            ctx.lineTo(pt.x, pt.y);
            ctx.stroke();
            lastX = pt.x;
            lastY = pt.y;
        }

        function endDraw() {
            drawing = false;
        }

        if (canvas) {
            canvas.addEventListener('mousedown', startDraw);
            canvas.addEventListener('mousemove', moveDraw);
            window.addEventListener('mouseup', endDraw);

            canvas.addEventListener('touchstart', startDraw, { passive: false });
            canvas.addEventListener('touchmove', moveDraw, { passive: false });
            window.addEventListener('touchend', endDraw);
        }

        document.getElementById('finSigClearCanvasBtn')?.addEventListener('click', clearCanvas);
        document.getElementById('finSigCloseBtn')?.addEventListener('click', closeModal);
        document.getElementById('finSigCancelBtn')?.addEventListener('click', closeModal);
        document.getElementById('finSigConfirmBtn')?.addEventListener('click', confirmSignature);

        modal.addEventListener('click', function (e) {
            if (e.target === modal) closeModal();
        });
    }

    function hideWarn() {
        const w = document.getElementById('finSigWarn');
        if (w) w.style.display = 'none';
    }

    function clearCanvas() {
        const canvas = document.getElementById('finSignatureCanvas');
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.strokeStyle = '#0f172a';
        ctx.lineWidth = 2.4;
        ctx.lineCap = 'round';
        ctx.lineJoin = 'round';
        hasStrokes = false;
        hideWarn();
    }

    function openModalForKey(key) {
        activeKey = key;
        const modal = ensureModal();
        clearCanvas();
        modal.hidden = false;
    }

    function closeModal() {
        const modal = document.getElementById('financeSignatureModal');
        if (modal) modal.hidden = true;
    }

    function applySignatureToKey(key, dataUrl, existingUrl) {
        const srcToDisplay = dataUrl || existingUrl || '';
        const hasSig = Boolean(srcToDisplay && srcToDisplay.trim() !== '');

        document.querySelectorAll(`[data-fin-sig-field="${key}"]`).forEach(field => {
            const hiddenInput = field.querySelector('input[type="hidden"][name="signatureData"], input[type="hidden"][data-fin-sig-input]');
            if (hiddenInput) {
                hiddenInput.value = dataUrl || '';
            }
            const img = field.querySelector(`[data-fin-sig-preview="${key}"]`);
            const placeholder = field.querySelector(`[data-fin-sig-placeholder="${key}"]`);
            const clearBtn = field.querySelector(`[data-fin-sig-clear="${key}"]`);

            if (img) {
                img.src = hasSig ? srcToDisplay : '';
                img.hidden = !hasSig;
            }
            if (placeholder) {
                placeholder.hidden = hasSig;
            }
            if (clearBtn) {
                clearBtn.hidden = !Boolean(dataUrl);
            }
        });

        document.querySelectorAll(`[data-fin-sig-mirror="${key}"]`).forEach(mirror => {
            const img = mirror.querySelector('img');
            const placeholder = mirror.querySelector('.fin-sig-mirror-placeholder');
            if (img) {
                img.src = hasSig ? srcToDisplay : '';
                img.hidden = !hasSig;
            }
            if (placeholder) {
                placeholder.hidden = hasSig;
            }
        });
    }

    function confirmSignature() {
        const canvas = document.getElementById('finSignatureCanvas');
        if (!canvas || !hasStrokes) {
            const w = document.getElementById('finSigWarn');
            if (w) w.style.display = 'block';
            return;
        }
        const dataUrl = canvas.toDataURL('image/png');
        if (activeKey) {
            applySignatureToKey(activeKey, dataUrl, '');
        }
        closeModal();
    }

    function initFinanceSignatures(root) {
        const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
        scope.querySelectorAll('[data-fin-sig-field]').forEach(field => {
            const key = field.getAttribute('data-fin-sig-field');
            if (!key) return;

            if (field.dataset.finSigBound !== '1') {
                field.dataset.finSigBound = '1';
                const openBtn = field.querySelector(`[data-fin-sig-open="${key}"]`);
                const clearBtn = field.querySelector(`[data-fin-sig-clear="${key}"]`);
                if (openBtn) {
                    openBtn.addEventListener('click', () => openModalForKey(key));
                }
                if (clearBtn) {
                    clearBtn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        const existing = field.querySelector('input[data-fin-sig-existing="1"]')?.value || '';
                        applySignatureToKey(key, '', existing);
                    });
                }
            }

            const hiddenInput = field.querySelector('input[type="hidden"][name="signatureData"], input[type="hidden"][data-fin-sig-input]');
            const existing = field.querySelector('input[data-fin-sig-existing="1"]')?.value || '';
            const currentData = hiddenInput ? hiddenInput.value : '';
            applySignatureToKey(key, currentData, existing);
        });
    }

    window.initFinanceSignatures = initFinanceSignatures;
    window.resetFinanceSignature = function (key, existingUrl) {
        applySignatureToKey(key, '', existingUrl || '');
    };
    window.openFinanceSignatureModal = openModalForKey;

    document.addEventListener('DOMContentLoaded', () => initFinanceSignatures(document));
})();
