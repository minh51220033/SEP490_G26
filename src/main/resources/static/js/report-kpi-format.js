/**
 * Tự động rút gọn số lớn sang chữ (nghìn / triệu / tỷ) trên thẻ KPI tổng quan
 * khi giá trị quá lớn hoặc khung thẻ hẹp, luôn giữ ký hiệu ("đ", "lượt", "BN")
 * trên cùng 1 dòng và giữ nguyên số chính xác trong tooltip khi rê chuột.
 */
(function () {
    'use strict';

    function trimDecimals(val, decimals) {
        return Number(val.toFixed(decimals)).toString();
    }

    function formatCompactWithUnit(num, unit, threshold) {
        const abs = Math.abs(num);
        const sign = num < 0 ? '-' : '';
        if (unit === 'đ') {
            if (abs >= 1_000_000_000) {
                return sign + trimDecimals(abs / 1_000_000_000, 2) + ' tỷ đ';
            }
            if (abs >= threshold) {
                if (abs >= 1_000_000) {
                    return sign + trimDecimals(abs / 1_000_000, abs >= 100_000_000 ? 1 : 2) + ' triệu đ';
                }
                if (abs >= 1_000) {
                    return sign + trimDecimals(abs / 1_000, 1) + ' nghìn đ';
                }
            }
            return null;
        }
        if (abs >= 1_000_000) {
            return sign + trimDecimals(abs / 1_000_000, 2) + ' triệu ' + unit;
        }
        if (abs >= threshold && abs >= 1_000) {
            return sign + trimDecimals(abs / 1_000, 1) + ' nghìn ' + unit;
        }
        return null;
    }

    function formatOverviewMetric(el, defaultMoneyThreshold, defaultCountThreshold) {
        if (!el.dataset.fullMetricText) {
            el.dataset.fullMetricText = el.textContent.replace(/\s+/g, ' ').trim();
        }
        const rawText = el.dataset.fullMetricText;
        const match = rawText.match(/^(-?[\d,]+(?:\.\d+)?)\s*(đ|lượt|BN)$/);
        if (!match) return;

        const numericValue = parseFloat(match[1].replace(/,/g, ''));
        if (!Number.isFinite(numericValue)) return;

        const unit = match[2];
        const primaryThreshold = unit === 'đ' ? defaultMoneyThreshold : defaultCountThreshold;
        let displayText = formatCompactWithUnit(numericValue, unit, primaryThreshold) || rawText;
        el.textContent = displayText;

        // Nếu màn hình hẹp khiến chuỗi vẫn tràn chiều rộng thẻ thì tiếp tục rút gọn thêm bậc
        if (el.clientWidth > 0 && el.scrollWidth > el.clientWidth + 1) {
            const tighterThreshold = unit === 'đ' ? 1_000_000 : 1_000;
            const tighterText = formatCompactWithUnit(numericValue, unit, tighterThreshold);
            if (tighterText) {
                displayText = tighterText;
                el.textContent = displayText;
            }
            if (el.scrollWidth > el.clientWidth + 1 && unit === 'đ' && Math.abs(numericValue) >= 1_000) {
                displayText = formatCompactWithUnit(numericValue, unit, 1_000) || displayText;
                el.textContent = displayText;
            }
        }

        if (displayText !== rawText) {
            el.setAttribute('title', 'Giá trị chính xác: ' + rawText);
            el.style.cursor = 'help';
        } else {
            el.removeAttribute('title');
            el.style.cursor = '';
        }
    }

    function applyReportKpiFormatting() {
        document.querySelectorAll('#overview-pane .dv-kpi-num').forEach(function (el) {
            formatOverviewMetric(el, 10_000_000, 10_000);
        });
        document.querySelectorAll('#overview-pane .fin-card .fs-4').forEach(function (el) {
            formatOverviewMetric(el, 100_000_000, 100_000);
        });
    }

    document.addEventListener('DOMContentLoaded', applyReportKpiFormatting);
    document.addEventListener('shown.bs.tab', applyReportKpiFormatting);
    window.addEventListener('resize', applyReportKpiFormatting);
})();

