/* CV-DCMS - Tương tác trang xác thực */
(function () {
    "use strict";

    document.querySelectorAll("[data-toggle-password]").forEach(function (btn) {
        var input = document.getElementById(btn.getAttribute("data-toggle-password"));
        if (!input) return;
        btn.addEventListener("click", function () {
            var show = input.type === "password";
            input.type = show ? "text" : "password";
            btn.setAttribute("aria-label", show ? "Ẩn mật khẩu" : "Hiện mật khẩu");
            btn.setAttribute("aria-pressed", String(show));
            var icon = btn.querySelector("i");
            if (icon) icon.className = show ? "bi bi-eye-slash" : "bi bi-eye";
        });
    });

    document.querySelectorAll("[data-strength-for]").forEach(function (meter) {
        var input = document.getElementById(meter.getAttribute("data-strength-for"));
        var label = document.getElementById(meter.getAttribute("data-strength-label"));
        if (!input) return;
        var texts = ["Tối thiểu 8 ký tự", "Yếu", "Trung bình", "Khá", "Mạnh"];
        input.addEventListener("input", function () {
            var v = input.value, level = 0;
            if (v.length >= 8) level++;
            if (/[A-Z]/.test(v) && /[a-z]/.test(v)) level++;
            if (/\d/.test(v)) level++;
            if (/[^A-Za-z0-9]/.test(v)) level++;
            if (!v) level = 0;
            meter.setAttribute("data-level", String(level));
            if (label) label.textContent = v ? "Độ mạnh: " + texts[level] : texts[0];
        });
    });

    document.querySelectorAll("[data-match]").forEach(function (confirm) {
        var source = document.getElementById(confirm.getAttribute("data-match"));
        var error = document.getElementById(confirm.id + "-error");
        if (!source) return;
        function check() {
            var mismatch = confirm.value && confirm.value !== source.value;
            confirm.setCustomValidity(mismatch ? "Mật khẩu xác minh không khớp" : "");
            confirm.classList.toggle("is-invalid", !!mismatch);
            confirm.setAttribute("aria-invalid", String(!!mismatch));
            if (error) error.hidden = !mismatch;
        }
        confirm.addEventListener("input", check);
        source.addEventListener("input", check);
    });

    document.querySelectorAll("[data-otp]").forEach(function (input) {
        input.addEventListener("input", function () {
            input.value = input.value.replace(/\D/g, "").slice(0, 6);
        });
    });
})();
