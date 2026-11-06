(() => {
    function formatMoney(value) {
        return new Intl.NumberFormat('vi-VN').format(value) + ' đ';
    }

    window.openDebtQuickPay = async function (button) {
        const template = document.getElementById('debtCollectionTemplate');
        if (!template || document.querySelector('[data-active-debt-collection]')) return;
        const root = template.content.firstElementChild.cloneNode(true);
        root.dataset.activeDebtCollection = '';
        document.body.append(root);
        const modal = bootstrap.Modal.getOrCreateInstance(root);
        const abort = new AbortController();
        const picker = root.querySelector('[data-invoice-picker]');
        const notice = root.querySelector('[data-feedback]');
        const form = root.querySelector('form');
        const amount = form.querySelector('[name=amount]');
        const select = form.querySelector('[name=invoiceId]');
        const box = form.querySelector('#debtPayAllocationPreviewBox');
        let previewTimer;
        let previewVersion = 0;
        root.addEventListener('hidden.bs.modal', () => {
            abort.abort();
            clearTimeout(previewTimer);
            modal.dispose();
            root.remove();
            if (button.isConnected) button.focus();
        }, {once: true});

        function preview() {
            clearTimeout(previewTimer);
            const version = ++previewVersion;
            box.style.display = 'none';
            if (!amount.checkValidity()) return;
            previewTimer = setTimeout(async () => {
                try {
                    const url = new URL(form.dataset.previewUrl, location.origin);
                    url.search = new URLSearchParams({invoiceId: select.value, amount: amount.value});
                    const res = await fetch(url, {signal: abort.signal});
                    if (!res.ok) return;
                    const data = await res.json();
                    if (version !== previewVersion || !data.lines?.length) return;
                    const body = box.querySelector('tbody');
                    body.replaceChildren();
                    for (const line of data.lines) {
                        const row = body.insertRow();
                        const type = {SERVICE: 'Dịch vụ', MEDICINE: 'Thuốc'}[line.itemType] || 'Phụ thu';
                        for (const value of [line.order, line.itemName, type, ...[line.payableAmount, line.previouslyPaid, line.allocatedAmount, line.remainingDebtAfter].map(formatMoney)]) {
                            row.insertCell().textContent = value;
                        }
                    }
                    box.querySelector('#debtPayRemainingBadge').textContent = 'Còn nợ sau thu: ' + formatMoney(data.remainingInvoiceDebtAfter);
                    box.style.display = 'block';
                } catch { /* Bản xem trước là tùy chọn. */ }
            }, 200);
        }

        function choose(inv) {
            const debt = Number(inv.remainingDebt);
            select.replaceChildren(new Option(inv.invoiceCode + ' - Còn nợ: ' + formatMoney(debt), inv.invoiceId));
            form.querySelector('#debtModalDebtDisplay').value = formatMoney(debt);
            amount.value = amount.max = String(debt);
            const balance = Number(button.dataset.depositBalance) || 0;
            const wallet = form.querySelector('[value=DEPOSIT_WALLET]');
            wallet.textContent = 'Ví tiền tạm ứng (Số dư: ' + formatMoney(balance) + ')';
            wallet.disabled = balance <= 0;
            form.querySelector('[name=idempotencyKey]').value = 'pay-' + (window.crypto?.randomUUID?.() || Date.now() + '-' + Math.random().toString(36).slice(2));
            picker.hidden = true;
            form.hidden = false;
            amount.focus();
            preview();
        }

        amount.addEventListener('input', preview);
        form.addEventListener('submit', () => { form.querySelector('[type=submit]').disabled = true; });
        root.querySelector('[data-patient]').textContent = button.dataset.patientName + ' (' + button.dataset.patientCode + ')';
        modal.show();
        try {
            const res = await fetch(button.dataset.invoicesUrl, {signal: abort.signal, headers: {Accept: 'application/json'}});
            if (!res.ok) throw new Error();
            const invoices = await res.json();
            const body = picker.querySelector('tbody');
            for (const inv of invoices.filter(i => Number(i.remainingDebt) > 0)) {
                const row = body.insertRow();
                const code = document.createElement('button');
                code.type = 'button';
                code.className = 'btn btn-link p-0';
                code.textContent = inv.invoiceCode;
                code.onclick = () => choose(inv);
                row.insertCell().append(code);
                const date = inv.issuedAt ? new Date(inv.issuedAt) : null;
                for (const value of [date && Number.isFinite(date.getTime()) ? date.toLocaleString('vi-VN') : '—', inv.branchName || '—', ...[inv.totalAmount, inv.paidAmount, inv.remainingDebt].map(formatMoney)]) {
                    row.insertCell().textContent = value;
                }
                const pay = document.createElement('button');
                pay.type = 'button';
                pay.className = 'btn btn-sm btn-success';
                pay.textContent = 'Thu tiền hóa đơn này';
                pay.onclick = () => choose(inv);
                row.insertCell().append(pay);
            }
            notice.textContent = body.rows.length ? 'Chọn hóa đơn cần thu tiền.' : 'Không có hóa đơn còn nợ phù hợp.';
        } catch {
            if (!abort.signal.aborted) notice.textContent = 'Không tải được hóa đơn. Hãy đóng và thử lại.';
        }
    };
})();
