/**
 * ============================================================================
 * CV-DCMS CASHIER MONEY INPUT
 * Định dạng số tiền nhập trên các trang Thu ngân với dấu phẩy (,) tách hàng trăm/nghìn:
 *   VD: 1000000 -> 1,000,000
 * Đồng bộ giá trị số thuần sang <input type="hidden"> để submit form lên Spring MVC
 * và cung cấp helper parseCashierMoney / formatCashierMoney cho các script tính toán.
 * ============================================================================
 */
(function () {
    'use strict';

    const nativeValueDesc = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');

    function parseCashierMoney(val) {
        if (val === null || val === undefined) return NaN;
        if (typeof val === 'number') return Number.isFinite(val) ? val : NaN;
        const str = String(val).trim();
        if (!str) return NaN;
        const cleaned = str.replace(/,/g, '').replace(/\s+/g, '');
        if (!/^-?\d*(\.\d*)?$/.test(cleaned) || cleaned === '' || cleaned === '-' || cleaned === '.') {
            return NaN;
        }
        const num = Number(cleaned);
        return Number.isFinite(num) ? num : NaN;
    }

    function addCommasToInt(intDigits) {
        if (!intDigits) return '';
        return intDigits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    }

    function formatTypedValue(raw, allowDecimal, maxDecimals) {
        if (raw === null || raw === undefined) {
            return { display: '', raw: '' };
        }
        const str = String(raw).trim();
        if (!str) {
            return { display: '', raw: '' };
        }

        if (!allowDecimal) {
            const digitsOnly = str.replace(/[^0-9]/g, '');
            if (!digitsOnly) return { display: '', raw: '' };
            const normalized = digitsOnly.replace(/^0+(?=\d)/, '');
            return {
                display: addCommasToInt(normalized),
                raw: normalized
            };
        }

        // allowDecimal = true
        let cleaned = str.replace(/[^0-9.]/g, '');
        const firstDot = cleaned.indexOf('.');
        let hasDot = firstDot !== -1;
        let intPart = hasDot ? cleaned.slice(0, firstDot) : cleaned;
        let decPart = hasDot ? cleaned.slice(firstDot + 1).replace(/\./g, '') : '';

        if (maxDecimals > 0 && decPart.length > maxDecimals) {
            decPart = decPart.slice(0, maxDecimals);
        }

        if (!intPart && !hasDot) {
            return { display: '', raw: '' };
        }

        intPart = intPart ? intPart.replace(/^0+(?=\d)/, '') : '0';
        const formattedInt = addCommasToInt(intPart);
        const display = hasDot ? `${formattedInt}.${decPart}` : formattedInt;
        const rawOut = hasDot && decPart.length > 0 ? `${intPart}.${decPart}` : intPart;

        return { display, raw: rawOut };
    }

    function formatProgrammaticValue(val, allowDecimal, maxDecimals) {
        if (val === null || val === undefined) {
            return { display: '', raw: '' };
        }
        const str = String(val).trim();
        if (!str) {
            return { display: '', raw: '' };
        }

        const num = parseCashierMoney(str);
        if (!Number.isFinite(num)) {
            return { display: '', raw: '' };
        }

        const safeNum = Math.max(0, num);
        let plainStr;
        if (allowDecimal) {
            const factor = Math.pow(10, maxDecimals);
            const rounded = Math.round(safeNum * factor) / factor;
            plainStr = String(rounded);
        } else {
            // Giữ phần thập phân khác 0 nếu DB có sẵn, bỏ .00 vô nghĩa
            const rounded2 = Math.round(safeNum * 100) / 100;
            plainStr = Number.isInteger(rounded2) ? String(rounded2) : String(Math.round(safeNum));
        }

        const parts = plainStr.split('.');
        const intPart = parts[0].replace(/^0+(?=\d)/, '');
        const decPart = parts[1] || '';
        const formattedInt = addCommasToInt(intPart);

        return {
            display: decPart ? `${formattedInt}.${decPart}` : formattedInt,
            raw: decPart ? `${intPart}.${decPart}` : intPart
        };
    }

    function formatCashierMoney(val, allowDecimal) {
        return formatProgrammaticValue(val, Boolean(allowDecimal), 2).display;
    }

    function getNumberAttr(el, dataKey, attrName) {
        const raw = el.dataset[dataKey] !== undefined ? el.dataset[dataKey] : el.getAttribute(attrName);
        if (raw === null || raw === undefined || raw === '') return null;
        const parsed = Number(raw);
        return Number.isFinite(parsed) ? parsed : null;
    }

    function validateMoneyInput(el) {
        const rawDisplay = nativeValueDesc ? nativeValueDesc.get.call(el) : el.value;
        const trimmed = String(rawDisplay || '').trim();
        if (!trimmed) {
            el.setCustomValidity('');
            return;
        }

        const num = parseCashierMoney(trimmed);
        if (!Number.isFinite(num)) {
            el.setCustomValidity('Vui lòng nhập số tiền hợp lệ.');
            return;
        }

        const allowDecimal = el.dataset.moneyDecimal === 'true';
        const min = getNumberAttr(el, 'moneyMin', 'min');
        const max = getNumberAttr(el, 'moneyMax', 'max');

        if (min !== null && num < min) {
            el.setCustomValidity('Số tiền tối thiểu là ' + formatCashierMoney(min, allowDecimal) + ' đ.');
            return;
        }
        if (max !== null && num > max) {
            el.setCustomValidity('Số tiền tối đa là ' + formatCashierMoney(max, allowDecimal) + ' đ.');
            return;
        }
        el.setCustomValidity('');
    }

    function countMeaningfulChars(str, endIdx, allowDecimal) {
        const regex = allowDecimal ? /[0-9.]/ : /[0-9]/;
        let count = 0;
        const limit = Math.min(endIdx, str.length);
        for (let i = 0; i < limit; i++) {
            if (regex.test(str[i])) {
                count++;
            }
        }
        return count;
    }

    function findCaretPosByCount(str, targetCount, allowDecimal) {
        if (targetCount <= 0) return 0;
        const regex = allowDecimal ? /[0-9.]/ : /[0-9]/;
        let count = 0;
        for (let i = 0; i < str.length; i++) {
            if (regex.test(str[i])) {
                count++;
                if (count === targetCount) {
                    return i + 1;
                }
            }
        }
        return str.length;
    }

    function setDisplayAndSync(el, formattedObj) {
        if (nativeValueDesc) {
            nativeValueDesc.set.call(el, formattedObj.display);
        } else {
            el.value = formattedObj.display;
        }
        if (el._cashierHiddenInput) {
            el._cashierHiddenInput.value = formattedObj.raw;
        }
        validateMoneyInput(el);
    }

    function enhanceMoneyInput(el) {
        if (!el || el.dataset.moneyInit === '1') return;
        el.dataset.moneyInit = '1';

        const allowDecimal = el.dataset.moneyDecimal === 'true';
        const maxDecimals = Number(el.dataset.moneyMaxDecimals || 2);

        // Chuyển min/max sang data-* để tránh HTML5 validation lỗi khi input là type="text"
        if (el.hasAttribute('min') && el.dataset.moneyMin === undefined) {
            el.dataset.moneyMin = el.getAttribute('min');
        }
        if (el.hasAttribute('max') && el.dataset.moneyMax === undefined) {
            el.dataset.moneyMax = el.getAttribute('max');
        }

        if (el.type !== 'text') {
            el.type = 'text';
        }
        el.inputMode = allowDecimal ? 'decimal' : 'numeric';
        if (!el.getAttribute('autocomplete')) {
            el.setAttribute('autocomplete', 'off');
        }

        // Tạo hidden input giữ giá trị số thuần để submit lên server
        const originalName = el.getAttribute('name');
        if (originalName) {
            const hidden = document.createElement('input');
            hidden.type = 'hidden';
            hidden.name = originalName;
            hidden.disabled = el.disabled;
            hidden.dataset.moneyHidden = '1';
            el.removeAttribute('name');
            el.insertAdjacentElement('afterend', hidden);
            el._cashierHiddenInput = hidden;
        }

        // Format giá trị khởi tạo (xử lý cả BigDecimal như 1000000.00)
        const initialRaw = nativeValueDesc ? nativeValueDesc.get.call(el) : el.value;
        setDisplayAndSync(el, formatProgrammaticValue(initialRaw, allowDecimal, maxDecimals));

        // Bọc getter/setter .value để JS set `el.value = debt` tự format có dấu phẩy
        if (nativeValueDesc && nativeValueDesc.configurable !== false) {
            Object.defineProperty(el, 'value', {
                configurable: true,
                get: function () {
                    return nativeValueDesc.get.call(this);
                },
                set: function (newVal) {
                    const formatted = formatProgrammaticValue(newVal, allowDecimal, maxDecimals);
                    setDisplayAndSync(this, formatted);
                }
            });
        }

        el.getNumericValue = function () {
            return parseCashierMoney(this.value);
        };

        el.addEventListener('input', function () {
            const currentVal = nativeValueDesc ? nativeValueDesc.get.call(this) : this.value;
            const isFocused = document.activeElement === this;
            const selStart = isFocused && typeof this.selectionStart === 'number' ? this.selectionStart : currentVal.length;
            const meaningfulBefore = countMeaningfulChars(currentVal, selStart, allowDecimal);

            const formatted = formatTypedValue(currentVal, allowDecimal, maxDecimals);
            setDisplayAndSync(this, formatted);

            if (isFocused && typeof this.setSelectionRange === 'function') {
                const nextPos = findCaretPosByCount(formatted.display, meaningfulBefore, allowDecimal);
                try {
                    this.setSelectionRange(nextPos, nextPos);
                } catch (e) { /* Bỏ qua nếu trình duyệt không hỗ trợ caret */ }
            }
        });

        el.addEventListener('blur', function () {
            const currentVal = nativeValueDesc ? nativeValueDesc.get.call(this) : this.value;
            setDisplayAndSync(this, formatProgrammaticValue(currentVal, allowDecimal, maxDecimals));
        });

        // Đồng bộ trạng thái disabled và min/max khi JS thay đổi thuộc tính động
        const observer = new MutationObserver(function (mutations) {
            for (const m of mutations) {
                if (m.attributeName === 'disabled' && el._cashierHiddenInput) {
                    el._cashierHiddenInput.disabled = el.disabled;
                } else if (m.attributeName === 'min') {
                    const val = el.getAttribute('min');
                    if (val !== null) el.dataset.moneyMin = val;
                    else delete el.dataset.moneyMin;
                    validateMoneyInput(el);
                } else if (m.attributeName === 'max') {
                    const val = el.getAttribute('max');
                    if (val !== null) el.dataset.moneyMax = val;
                    else delete el.dataset.moneyMax;
                    validateMoneyInput(el);
                } else if (m.attributeName === 'data-money-min' || m.attributeName === 'data-money-max') {
                    validateMoneyInput(el);
                }
            }
        });
        observer.observe(el, {
            attributes: true,
            attributeFilter: ['disabled', 'min', 'max', 'data-money-min', 'data-money-max']
        });

        // Đảm bảo đồng bộ lần cuối ngay trước khi form submit
        const form = el.closest('form');
        if (form && form.dataset.cashierMoneySubmitBound !== '1') {
            form.dataset.cashierMoneySubmitBound = '1';
            form.addEventListener('submit', function () {
                form.querySelectorAll('[data-cashier-money="true"]').forEach(input => {
                    const currentVal = nativeValueDesc ? nativeValueDesc.get.call(input) : input.value;
                    const formatted = formatProgrammaticValue(currentVal, input.dataset.moneyDecimal === 'true', Number(input.dataset.moneyMaxDecimals || 2));
                    setDisplayAndSync(input, formatted);
                    if (input._cashierHiddenInput) {
                        input._cashierHiddenInput.disabled = input.disabled;
                    }
                });
            }, true);
        }
    }

    function initCashierMoneyInputs(root) {
        const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
        scope.querySelectorAll('[data-cashier-money="true"]').forEach(enhanceMoneyInput);
    }

    window.parseCashierMoney = parseCashierMoney;
    window.formatCashierMoney = formatCashierMoney;
    window.initCashierMoneyInputs = initCashierMoneyInputs;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            initCashierMoneyInputs(document);
        });
    } else {
        initCashierMoneyInputs(document);
    }
})();
