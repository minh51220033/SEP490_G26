/**
 * VietQR Payment Component - FT22 Dental Clinic Management System
 * Hỗ trợ sinh VietQR động theo chuẩn VietQR Quick Link
 * Tự động chọn tài khoản mặc định theo chi nhánh, cho phép đổi tài khoản cùng chi nhánh.
 * Chống race-condition: Dùng request sequence + AbortController + kiểm tra trạng thái trước khi render.
 */
class VietQrPaymentBox {
    constructor(options) {
        this.container = typeof options.container === 'string' ? document.getElementById(options.container) : options.container;
        this.methodSelect = typeof options.methodSelect === 'string' ? document.getElementById(options.methodSelect) : options.methodSelect;
        this.amountInput = typeof options.amountInput === 'string' ? document.getElementById(options.amountInput) : options.amountInput;
        this.getBranchId = typeof options.branchId === 'function' ? options.branchId : () => options.branchId;
        this.getInvoiceId = typeof options.invoiceId === 'function' ? options.invoiceId : () => options.invoiceId;
        this.getProfileId = typeof options.profileId === 'function' ? options.profileId : () => options.profileId;
        this.bankAccountIdInput = typeof options.bankAccountIdInput === 'string' ? document.getElementById(options.bankAccountIdInput) : options.bankAccountIdInput;
        this.expectedContentInput = typeof options.expectedContentInput === 'string' ? document.getElementById(options.expectedContentInput) : options.expectedContentInput;
        
        this.sessionRef = 'TT' + (Math.floor(100000 + Math.random() * 900000));
        this.selectedAccountId = null;
        this.debounceTimer = null;
        this.currentQrData = null;
        
        // Quản lý thứ tự request và hủy request cũ
        this.requestSeq = 0;
        this.abortController = null;
        this.lastBranchId = null;
        this.lastInvoiceId = null;

        if (this.container) {
            this.renderTemplate();
            this.bindEvents();
            // Khởi tạo trạng thái ban đầu
            this.handleMethodChange();
        }
    }

    renderTemplate() {
        this.container.innerHTML = `
            <div class="vietqr-box border rounded-3 p-3 bg-white mt-3 shadow-sm">
                <!-- Loading State -->
                <div class="vietqr-loading text-center py-4" style="display: none;">
                    <div class="spinner-border text-primary spinner-border-sm" role="status"></div>
                    <div class="small text-muted mt-2">Đang tải mã VietQR chuyển khoản...</div>
                </div>

                <!-- Error State (Thiếu cấu hình, lỗi tải, chi nhánh chưa có tài khoản) -->
                <div class="vietqr-error alert alert-warning py-3 small mb-0" style="display: none;">
                    <div class="d-flex align-items-center">
                        <i class="bi bi-exclamation-triangle-fill fs-4 text-warning me-2 flex-shrink-0"></i>
                        <div>
                            <strong class="d-block text-dark">Chưa thể sinh mã VietQR:</strong>
                            <span class="vietqr-error-msg">Chi nhánh chưa có tài khoản ngân hàng hoạt động.</span>
                        </div>
                    </div>
                </div>

                <!-- Content State -->
                <div class="vietqr-content" style="display: none;">
                    <div class="vietqr-body d-flex flex-column flex-sm-row gap-3 align-items-center align-items-sm-start">
                        <!-- Cột trái: Ảnh QR Code VietQR -->
                        <div class="vietqr-col-qr text-center flex-shrink-0" style="width: 200px; max-width: 100%;">
                            <div class="p-2 bg-light border rounded-3 position-relative d-flex align-items-center justify-content-center shadow-xs mx-auto" style="width: 190px; height: 190px;">
                                <img class="vietqr-img img-fluid rounded" alt="VietQR" style="max-width: 174px; max-height: 174px; object-fit: contain; display: none; margin: 0 auto;">
                                <div class="vietqr-img-spinner position-absolute top-50 start-50 translate-middle" style="display: none;">
                                    <div class="spinner-border text-primary"></div>
                                </div>
                            </div>
                            <div class="mt-2 text-center">
                                <span class="badge bg-primary-subtle text-primary border border-primary-subtle px-2 py-1" style="font-size: 0.72rem;">
                                    <i class="bi bi-qr-code-scan me-1"></i> Quét bằng App Ngân hàng
                                </span>
                            </div>
                        </div>

                        <!-- Cột phải: Thông tin tài khoản & nút sao chép -->
                        <div class="vietqr-col-info flex-grow-1" style="min-width: 0; width: 100%;">
                            <!-- Dropdown chọn tài khoản ngân hàng của chi nhánh -->
                            <div class="mb-2">
                                <label class="form-label small fw-bold text-muted mb-1 text-uppercase" style="font-size: 0.75rem;">
                                    <i class="bi bi-bank me-1"></i> Tài khoản nhận tiền chi nhánh:
                                </label>
                                <select class="form-select form-select-sm vietqr-account-select fw-semibold text-truncate"></select>
                            </div>

                            <!-- Hiển thị số tiền nổi bật -->
                            <div class="p-2 mb-2 rounded-2 bg-light border">
                                <span class="small text-muted d-block" style="font-size: 0.72rem;">SỐ TIỀN CHUYỂN KHOẢN ĐỢT NÀY:</span>
                                <strong class="fs-4 text-success vietqr-amount-display">0 đ</strong>
                            </div>

                            <!-- Thông tin ngân hàng, STK, Chủ TK, Nội dung -->
                            <div class="small">
                                <div class="d-flex justify-content-between align-items-center py-1 border-bottom">
                                    <span class="text-muted text-nowrap me-2">Ngân hàng:</span>
                                    <strong class="vietqr-bank-name text-dark text-end text-truncate"></strong>
                                </div>
                                <div class="d-flex justify-content-between align-items-center py-1 border-bottom">
                                    <span class="text-muted text-nowrap me-2">Số tài khoản:</span>
                                    <div class="d-flex align-items-center gap-1">
                                        <strong class="font-monospace fs-6 vietqr-account-num text-primary"></strong>
                                        <button type="button" class="btn btn-sm btn-outline-secondary py-0 px-2 vietqr-btn-copy-acc" title="Sao chép số tài khoản">
                                            <i class="bi bi-clipboard"></i>
                                        </button>
                                    </div>
                                </div>
                                <div class="d-flex justify-content-between align-items-center py-1 border-bottom">
                                    <span class="text-muted text-nowrap me-2">Chủ tài khoản:</span>
                                    <strong class="vietqr-account-holder text-uppercase text-dark text-end text-truncate"></strong>
                                </div>
                                <div class="d-flex justify-content-between align-items-center py-1">
                                    <span class="text-muted text-nowrap me-2">Nội dung CK:</span>
                                    <div class="d-flex align-items-center gap-1 text-end" style="min-width: 0;">
                                        <strong class="font-monospace text-danger vietqr-transfer-content text-break" style="font-size: 0.85rem;"></strong>
                                        <button type="button" class="btn btn-sm btn-outline-secondary py-0 px-2 flex-shrink-0 vietqr-btn-copy-content" title="Sao chép nội dung">
                                            <i class="bi bi-clipboard"></i>
                                        </button>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        `;

        this.boxEl = this.container.querySelector('.vietqr-box');
        this.loadingEl = this.container.querySelector('.vietqr-loading');
        this.errorEl = this.container.querySelector('.vietqr-error');
        this.errorMsgEl = this.container.querySelector('.vietqr-error-msg');
        this.contentEl = this.container.querySelector('.vietqr-content');
        this.imgEl = this.container.querySelector('.vietqr-img');
        this.imgSpinner = this.container.querySelector('.vietqr-img-spinner');
        this.accountSelectEl = this.container.querySelector('.vietqr-account-select');
        this.amountDisplayEl = this.container.querySelector('.vietqr-amount-display');
        this.bankNameEl = this.container.querySelector('.vietqr-bank-name');
        this.accountNumEl = this.container.querySelector('.vietqr-account-num');
        this.accountHolderEl = this.container.querySelector('.vietqr-account-holder');
        this.transferContentEl = this.container.querySelector('.vietqr-transfer-content');
        this.btnCopyAcc = this.container.querySelector('.vietqr-btn-copy-acc');
        this.btnCopyContent = this.container.querySelector('.vietqr-btn-copy-content');
    }

    bindEvents() {
        if (this.methodSelect) {
            this.methodSelect.addEventListener('change', () => this.handleMethodChange());
        }

        if (this.amountInput) {
            this.amountInput.addEventListener('input', () => {
                if (this.isBankTransferSelected()) {
                    // Vô hiệu hóa request cũ ngay khi đầu vào thay đổi
                    this.cancelOngoingRequests();
                    this.clearQrData();
                    this.debounceTimer = setTimeout(() => this.loadQr(), 300);
                }
            });
        }

        if (this.accountSelectEl) {
            this.accountSelectEl.addEventListener('change', (e) => {
                this.selectedAccountId = e.target.value;
                this.cancelOngoingRequests();
                this.clearQrData();
                this.loadQr();
            });
        }

        if (this.btnCopyAcc) {
            this.btnCopyAcc.addEventListener('click', () => {
                if (this.currentQrData && this.currentQrData.accountNumber) {
                    this.copyToClipboard(this.currentQrData.accountNumber, this.btnCopyAcc);
                }
            });
        }

        if (this.btnCopyContent) {
            this.btnCopyContent.addEventListener('click', () => {
                if (this.currentQrData && this.currentQrData.transferContent) {
                    this.copyToClipboard(this.currentQrData.transferContent, this.btnCopyContent);
                }
            });
        }
    }

    isBankTransferSelected() {
        return this.methodSelect && this.methodSelect.value === 'BANK_TRANSFER';
    }

    handleMethodChange() {
        if (this.isBankTransferSelected()) {
            this.container.style.display = 'block';
            this.loadQr();
        } else {
            // Khi chuyển sang Tiền mặt hoặc phương thức khác: lập tức hủy request đang chạy và dọn dẹp state
            this.cancelOngoingRequests();
            this.clearQrData();
            this.container.style.display = 'none';
        }
    }

    cancelOngoingRequests() {
        if (this.debounceTimer) {
            clearTimeout(this.debounceTimer);
            this.debounceTimer = null;
        }
        if (this.abortController) {
            try {
                this.abortController.abort();
            } catch (e) {}
            this.abortController = null;
        }
        // Tăng sequence để đánh dấu các phản hồi mạng đang bay là lỗi thời
        this.requestSeq++;
    }

    clearQrData() {
        this.currentQrData = null;
        if (this.bankAccountIdInput) {
            this.bankAccountIdInput.value = '';
        }
        if (this.expectedContentInput) {
            this.expectedContentInput.value = '';
        }
        if (this.imgEl) {
            this.imgEl.src = '';
            this.imgEl.style.display = 'none';
        }
        if (this.imgSpinner) {
            this.imgSpinner.style.display = 'none';
        }
    }

    async loadQr() {
        this.cancelOngoingRequests();
        const currentSeq = this.requestSeq;

        const branchId = this.getBranchId ? this.getBranchId() : null;
        const invoiceId = this.getInvoiceId ? this.getInvoiceId() : null;
        const profileId = this.getProfileId ? this.getProfileId() : null;
        const amount = this.amountInput ? parseFloat(this.amountInput.value) || 0 : 0;

        // Nếu chuyển sang hóa đơn hoặc chi nhánh khác, xóa selectedAccountId cũ để nhận tài khoản mặc định của chi nhánh mới
        if (this.lastBranchId !== branchId || this.lastInvoiceId !== invoiceId) {
            this.selectedAccountId = null;
            this.lastBranchId = branchId;
            this.lastInvoiceId = invoiceId;
        }

        // Xóa sạch dữ liệu cũ và hidden inputs trước khi tải
        this.clearQrData();

        if (!branchId && !invoiceId) {
            this.showError('Vui lòng chọn hóa đơn hoặc chi nhánh thu tiền để sinh mã VietQR.');
            return;
        }

        if (amount <= 0) {
            this.showError('Số tiền thanh toán phải lớn hơn 0 để tạo mã VietQR.');
            return;
        }

        this.showLoading();
        this.abortController = new AbortController();

        try {
            const params = new URLSearchParams();
            if (branchId) params.append('branchId', branchId);
            if (invoiceId) params.append('invoiceId', invoiceId);
            if (profileId) params.append('profileId', profileId);
            if (this.selectedAccountId) params.append('accountId', this.selectedAccountId);
            params.append('amount', amount);
            params.append('sessionRef', this.sessionRef);

            const resp = await fetch('/api/finance/qr-info?' + params.toString(), {
                signal: this.abortController.signal
            });

            // Nếu sequence đã thay đổi hoặc người dùng đã chuyển sang phương thức khác -> hủy bỏ ngay
            if (currentSeq !== this.requestSeq || !this.isBankTransferSelected()) {
                return;
            }

            const data = await resp.json();

            if (currentSeq !== this.requestSeq || !this.isBankTransferSelected()) {
                return;
            }

            if (!data.success) {
                this.clearQrData();
                this.showError(data.errorMessage || 'Không thể tạo mã VietQR.');
                return;
            }

            // Ghi nhận dữ liệu hợp lệ
            this.currentQrData = data;
            this.selectedAccountId = data.accountId;

            if (this.bankAccountIdInput) {
                this.bankAccountIdInput.value = data.accountId;
            }
            if (this.expectedContentInput) {
                this.expectedContentInput.value = data.transferContent;
            }

            this.showContent(data, currentSeq);
        } catch (e) {
            if (e.name === 'AbortError') {
                return; // Request bị cố tình hủy, bỏ qua
            }
            if (currentSeq === this.requestSeq && this.isBankTransferSelected()) {
                this.clearQrData();
                this.showError('Lỗi kết nối khi tải mã QR: ' + e.message);
            }
        }
    }

    showLoading() {
        this.loadingEl.style.display = 'block';
        this.errorEl.style.display = 'none';
        this.contentEl.style.display = 'none';
    }

    showError(msg) {
        this.loadingEl.style.display = 'none';
        this.errorEl.style.display = 'block';
        this.errorMsgEl.textContent = msg;
        this.contentEl.style.display = 'none';
    }

    showContent(data, currentSeq) {
        if (currentSeq !== this.requestSeq || !this.isBankTransferSelected()) {
            return;
        }

        this.loadingEl.style.display = 'none';
        this.errorEl.style.display = 'none';
        this.contentEl.style.display = 'block';

        // Điền dropdown danh sách tài khoản
        if (data.availableAccounts && data.availableAccounts.length > 0) {
            this.accountSelectEl.innerHTML = '';
            data.availableAccounts.forEach(acc => {
                const opt = document.createElement('option');
                opt.value = acc.accountId;
                const defText = acc.isDefault ? ' [Mặc định]' : '';
                opt.textContent = `${acc.bankName} - ${acc.accountNumber} (${acc.accountHolderName})${defText}`;
                if (acc.accountId === data.accountId) {
                    opt.selected = true;
                }
                this.accountSelectEl.appendChild(opt);
            });
            this.accountSelectEl.disabled = data.availableAccounts.length <= 1;
        }

        // Điền thông tin chuyển khoản bằng chữ
        this.bankNameEl.textContent = data.bankName + (data.bankShortName ? ` (${data.bankShortName})` : '');
        this.accountNumEl.textContent = data.accountNumber;
        this.accountHolderEl.textContent = data.accountHolderName;
        this.transferContentEl.textContent = data.transferContent;
        this.amountDisplayEl.textContent = new Intl.NumberFormat('vi-VN').format(data.amount || 0) + ' đ';

        // Ẩn ảnh QR cũ ngay lập tức, hiển thị spinner để không hiển thị ảnh cũ dưới số tiền mới!
        this.imgEl.style.display = 'none';
        if (this.imgSpinner) this.imgSpinner.style.display = 'block';

        // Tải ảnh QR mới độc lập với xử lý onload/onerror chống race condition
        const newImg = new Image();
        newImg.onload = () => {
            if (currentSeq !== this.requestSeq || !this.isBankTransferSelected()) {
                return;
            }
            this.imgEl.src = data.qrUrl;
            this.imgEl.style.display = 'block';
            if (this.imgSpinner) this.imgSpinner.style.display = 'none';
        };
        newImg.onerror = () => {
            if (currentSeq !== this.requestSeq || !this.isBankTransferSelected()) {
                return;
            }
            if (this.imgSpinner) this.imgSpinner.style.display = 'none';
            // Không âm thầm dùng dữ liệu cũ
            this.clearQrData();
            this.showError('Không thể tải hình ảnh mã VietQR từ cổng dịch vụ. Vui lòng quét theo STK hoặc kiểm tra lại đường truyền.');
        };
        newImg.src = data.qrUrl;
    }

    copyToClipboard(text, btnEl) {
        if (!text) return;
        navigator.clipboard.writeText(text).then(() => {
            const originalHtml = btnEl.innerHTML;
            btnEl.innerHTML = '<i class="bi bi-check2 text-success"></i>';
            btnEl.classList.add('btn-success', 'text-white');
            setTimeout(() => {
                btnEl.innerHTML = originalHtml;
                btnEl.classList.remove('btn-success', 'text-white');
            }, 1500);
        }).catch(err => {
            console.error('Không thể sao chép:', err);
        });
    }

    refresh(resetAccount = false) {
        if (resetAccount) {
            this.selectedAccountId = null;
        }
        if (this.isBankTransferSelected()) {
            this.loadQr();
        }
    }

    reset() {
        this.cancelOngoingRequests();
        this.selectedAccountId = null;
        this.lastBranchId = null;
        this.lastInvoiceId = null;
        this.clearQrData();
        if (this.loadingEl) this.loadingEl.style.display = 'none';
        if (this.errorEl) this.errorEl.style.display = 'none';
        if (this.contentEl) this.contentEl.style.display = 'none';
    }
}

window.VietQrPaymentBox = VietQrPaymentBox;
