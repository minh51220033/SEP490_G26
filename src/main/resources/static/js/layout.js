/* =====================================================================
   CV-DCMS - LAYOUT.JS
   Không phụ thuộc thư viện. Nạp bằng <script defer> trong head.html.

   MODULE
   1. Sidebar      : thu gọn (desktop) / drawer (mobile), lưu trạng thái, focus
   2. Accordion    : mở/đóng nhóm menu con trong sidebar
   3. Swipe        : vuốt từ mép trái để mở, vuốt sang trái để đóng drawer
   4. Dropdown     : menu tài khoản, thông báo, tạo mới, mega menu (click-outside, Esc)
   5. Site nav     : hamburger của header trang công khai
   6. Search       : phím tắt Ctrl/Cmd + K hoặc "/", ô tìm kiếm mobile
   7. Ripple       : hiệu ứng gợn sóng khi bấm (chỉ transform + opacity)
   8. Scroll       : bóng topbar, nút lên đầu trang, gợi ý cuộn bảng
   9. Misc         : link bị vô hiệu hoá, link cuộn tới section
   ===================================================================== */
(function () {
    "use strict";

    var root = document.documentElement;
    var DESKTOP_QUERY = window.matchMedia("(min-width: 992px)");
    var REDUCED_MOTION = window.matchMedia("(prefers-reduced-motion: reduce)");
    var STORAGE_KEY = "cvdcms.sidebar.collapsed";

    var sidebar = document.querySelector("[data-sidebar]");
    var backdrop = document.querySelector(".app-sidebar__backdrop");
    var lastFocused = null;

    /* -------------------------------------------------------------
       Tiện ích
       ------------------------------------------------------------- */
    function $all(selector, scope) {
        return Array.prototype.slice.call((scope || document).querySelectorAll(selector));
    }

    function storageSet(value) {
        try { localStorage.setItem(STORAGE_KEY, value); } catch (e) { /* bỏ qua */ }
    }

    function isDesktop() { return DESKTOP_QUERY.matches; }

    /* =============================================================
       1. SIDEBAR
       ============================================================= */
    function syncToggleState() {
        var expanded = isDesktop()
            ? !root.classList.contains("is-sidebar-collapsed")
            : root.classList.contains("is-sidebar-open");

        $all("[data-sidebar-toggle]").forEach(function (btn) {
            btn.setAttribute("aria-expanded", String(expanded));
        });

        $all(".app-sidebar__collapse").forEach(function (btn) {
            btn.setAttribute("aria-label", expanded ? "Thu gọn menu" : "Mở rộng menu");
            var label = btn.querySelector(".side-link__label");
            if (label) label.textContent = expanded ? "Thu gọn" : "Mở rộng";
        });

        // Drawer đóng trên mobile: loại khỏi thứ tự Tab & cây accessibility
        if (sidebar) sidebar.inert = !isDesktop() && !expanded;

        updateRailTooltips();
    }

    /* Ở chế độ icon-only, hiện nhãn qua thuộc tính title để người dùng biết icon là gì */
    function updateRailTooltips() {
        if (!sidebar) return;
        var rail = isDesktop() && root.classList.contains("is-sidebar-collapsed");

        $all(".side-link, .side-group__toggle", sidebar).forEach(function (item) {
            var label = item.querySelector(".side-link__label, .side-group__label");
            if (!label) return;
            if (!item.hasAttribute("data-original-title")) {
                item.setAttribute("data-original-title", item.getAttribute("title") || "");
            }
            var original = item.getAttribute("data-original-title");
            if (rail) item.setAttribute("title", label.textContent.trim());
            else if (original) item.setAttribute("title", original);
            else item.removeAttribute("title");
        });
    }

    function openDrawer() {
        if (!sidebar) return;
        lastFocused = document.activeElement;
        root.classList.add("is-sidebar-open");
        syncToggleState();
        // Đưa focus vào drawer sau khi animation bắt đầu (hỗ trợ bàn phím & screen reader)
        window.setTimeout(function () {
            var target = sidebar.querySelector(".side-link.is-active") || sidebar.querySelector(".side-link, .side-group__toggle");
            if (target) target.focus({ preventScroll: true });
        }, 60);
    }

    function closeDrawer() {
        if (!root.classList.contains("is-sidebar-open")) return;
        root.classList.remove("is-sidebar-open");
        syncToggleState();
        if (lastFocused && typeof lastFocused.focus === "function") lastFocused.focus({ preventScroll: true });
    }

    function setCollapsed(collapsed) {
        root.classList.toggle("is-sidebar-collapsed", collapsed);
        storageSet(collapsed ? "1" : "0");
        syncToggleState();
    }

    function toggleSidebar() {
        if (isDesktop()) {
            setCollapsed(!root.classList.contains("is-sidebar-collapsed"));
        } else if (root.classList.contains("is-sidebar-open")) {
            closeDrawer();
        } else {
            openDrawer();
        }
    }

    $all("[data-sidebar-toggle]").forEach(function (btn) {
        btn.addEventListener("click", toggleSidebar);
    });
    $all("[data-sidebar-close]").forEach(function (el) {
        el.addEventListener("click", closeDrawer);
    });

    // Bấm vào link trong drawer (mobile) thì đóng drawer cho gọn
    if (sidebar) {
        sidebar.addEventListener("click", function (event) {
            var link = event.target.closest("a.side-link:not(.is-disabled)");
            if (link && !isDesktop()) closeDrawer();
        });
    }

    // Đổi kích thước qua mốc breakpoint: dọn trạng thái không còn phù hợp
    function handleBreakpointChange() {
        if (isDesktop()) {
            root.classList.remove("is-sidebar-open");
            try {
                root.classList.toggle("is-sidebar-collapsed", localStorage.getItem(STORAGE_KEY) === "1");
            } catch (e) { /* bỏ qua */ }
        } else {
            root.classList.remove("is-sidebar-collapsed");
        }
        syncToggleState();
    }
    if (DESKTOP_QUERY.addEventListener) DESKTOP_QUERY.addEventListener("change", handleBreakpointChange);
    else if (DESKTOP_QUERY.addListener) DESKTOP_QUERY.addListener(handleBreakpointChange);

    // Đánh dấu aria-current cho link đang active
    $all(".side-link.is-active").forEach(function (link) { link.setAttribute("aria-current", "page"); });

    /* =============================================================
       2. ACCORDION (nhóm menu con)
       ============================================================= */
    function setGroupOpen(group, open) {
        group.classList.toggle("is-open", open);
        var toggle = group.querySelector("[data-accordion-toggle]");
        if (toggle) toggle.setAttribute("aria-expanded", String(open));
        // Link con bị ẩn không được nhận focus bằng Tab
        $all(".side-group__inner > a", group).forEach(function (a) {
            if (open) a.removeAttribute("tabindex");
            else a.setAttribute("tabindex", "-1");
        });
    }

    $all("[data-accordion]").forEach(function (group) {
        // Nhóm chứa link active luôn được mở và tô màu icon nhóm
        if (group.querySelector(".side-link.is-active")) {
            group.classList.add("has-active", "is-open");
        }
        setGroupOpen(group, group.classList.contains("is-open"));

        var toggle = group.querySelector("[data-accordion-toggle]");
        if (!toggle) return;
        toggle.addEventListener("click", function () {
            // Đang ở rail icon-only: mở rộng sidebar trước rồi mới bung nhóm
            if (isDesktop() && root.classList.contains("is-sidebar-collapsed")) {
                setCollapsed(false);
                setGroupOpen(group, true);
                return;
            }
            setGroupOpen(group, !group.classList.contains("is-open"));
        });
    });

    /* =============================================================
       3. SWIPE (mobile drawer)
       ============================================================= */
    (function initSwipe() {
        if (!sidebar || !("ontouchstart" in window)) return;

        var EDGE = 24;          // vùng mép trái kích hoạt vuốt mở (px)
        var THRESHOLD = 0.35;   // kéo quá 35% chiều rộng thì chốt mở/đóng
        var startX = 0, startY = 0, currentX = 0;
        var tracking = false, decided = false, horizontal = false, opening = false;
        var width = 0;

        function setProgress(progress) {
            var p = Math.max(0, Math.min(1, progress));
            sidebar.style.transform = "translate3d(" + ((p - 1) * 100) + "%, 0, 0)";
            if (backdrop) {
                backdrop.style.opacity = String(p);
                backdrop.style.visibility = "visible";
            }
        }

        function reset() {
            sidebar.classList.remove("is-dragging");
            sidebar.style.transform = "";
            if (backdrop) {
                backdrop.classList.remove("is-dragging");
                backdrop.style.opacity = "";
                backdrop.style.visibility = "";
            }
        }

        document.addEventListener("touchstart", function (e) {
            if (isDesktop() || e.touches.length !== 1) return;
            var t = e.touches[0];
            var isOpen = root.classList.contains("is-sidebar-open");
            opening = !isOpen && t.clientX <= EDGE;
            var closing = isOpen && (sidebar.contains(e.target) || e.target === backdrop);
            if (!opening && !closing) return;

            tracking = true;
            decided = false;
            horizontal = false;
            startX = currentX = t.clientX;
            startY = t.clientY;
            width = sidebar.offsetWidth || 280;
        }, { passive: true });

        document.addEventListener("touchmove", function (e) {
            if (!tracking) return;
            var t = e.touches[0];
            var dx = t.clientX - startX;
            var dy = t.clientY - startY;

            // Xác định hướng vuốt 1 lần: dọc thì nhường cho cuộn trang
            if (!decided && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                decided = true;
                horizontal = Math.abs(dx) > Math.abs(dy);
                if (!horizontal) { tracking = false; return; }
                sidebar.classList.add("is-dragging");
                if (backdrop) backdrop.classList.add("is-dragging");
            }
            if (!horizontal) return;

            currentX = t.clientX;
            var progress = opening ? dx / width : 1 + dx / width;
            window.requestAnimationFrame(function () { setProgress(progress); });
        }, { passive: true });

        document.addEventListener("touchend", function () {
            if (!tracking) return;
            tracking = false;
            if (!horizontal) { reset(); return; }

            var dx = currentX - startX;
            var shouldOpen = opening ? dx / width > THRESHOLD : -dx / width < THRESHOLD;
            reset();
            if (!shouldOpen) closeDrawer();
            else if (!root.classList.contains("is-sidebar-open")) openDrawer();
        }, { passive: true });

        document.addEventListener("touchcancel", function () { tracking = false; reset(); }, { passive: true });
    })();

    /* =============================================================
       4. DROPDOWN
       ============================================================= */
    function closeAllDropdowns(except) {
        $all("[data-dropdown].is-open").forEach(function (item) {
            if (item === except) return;
            item.classList.remove("is-open");
            var toggle = item.querySelector("[data-dropdown-toggle]");
            if (toggle) toggle.setAttribute("aria-expanded", "false");
        });
    }

    $all("[data-dropdown-toggle]").forEach(function (toggle) {
        toggle.addEventListener("click", function (event) {
            event.stopPropagation();
            var item = toggle.closest("[data-dropdown]");
            var willOpen = !item.classList.contains("is-open");
            closeAllDropdowns(item);
            item.classList.toggle("is-open", willOpen);
            toggle.setAttribute("aria-expanded", String(willOpen));
        });
    });

    // Click-outside: đóng mọi dropdown (dùng pointerdown để phản hồi nhanh trên cảm ứng)
    document.addEventListener("pointerdown", function (event) {
        if (!event.target.closest("[data-dropdown]")) closeAllDropdowns();
    });

    /* =============================================================
       5. SITE NAV (header trang công khai)
       ============================================================= */
    var navToggle = document.querySelector("[data-nav-toggle]");
    if (navToggle) {
        var nav = document.getElementById(navToggle.getAttribute("aria-controls"));
        navToggle.addEventListener("click", function () {
            var isOpen = nav.classList.toggle("is-open");
            navToggle.setAttribute("aria-expanded", String(isOpen));
            navToggle.setAttribute("aria-label", isOpen ? "Đóng menu" : "Mở menu");
            var icon = navToggle.querySelector(".bi");
            if (icon) icon.className = isOpen ? "bi bi-x-lg" : "bi bi-list";
        });
        document.addEventListener("pointerdown", function (event) {
            if (!nav.classList.contains("is-open")) return;
            if (nav.contains(event.target) || navToggle.contains(event.target)) return;
            navToggle.click();
        });
    }

    /* =============================================================
       6. SEARCH
       ============================================================= */
    var topbar = document.querySelector("[data-topbar]");
    var searchInput = document.querySelector("[data-search-input]");

    function openSearch() {
        if (!searchInput) return;
        if (topbar) topbar.classList.add("is-searching");
        // Đợi frame kế tiếp để input đã visible thì mới focus được (iOS)
        window.requestAnimationFrame(function () { searchInput.focus(); });
    }
    function closeSearch() {
        if (topbar) topbar.classList.remove("is-searching");
        if (searchInput) searchInput.blur();
    }

    $all("[data-search-toggle]").forEach(function (btn) {
        btn.addEventListener("click", function () {
            if (topbar && topbar.classList.contains("is-searching")) closeSearch();
            else openSearch();
        });
    });

    /* =============================================================
       PHÍM TẮT TOÀN CỤC
       ============================================================= */
    document.addEventListener("keydown", function (event) {
        var target = event.target;
        var typing = target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));

        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
            event.preventDefault();
            openSearch();
            return;
        }
        if (event.key === "/" && !typing) {
            event.preventDefault();
            openSearch();
            return;
        }
        if (event.key === "Escape") {
            closeAllDropdowns();
            closeDrawer();
            if (topbar && topbar.classList.contains("is-searching")) closeSearch();
            if (navToggle && navToggle.getAttribute("aria-expanded") === "true") navToggle.click();
        }
    });

    /* =============================================================
       7. RIPPLE
       ============================================================= */
    var RIPPLE_SELECTOR = ".btn-brand, .btn-outline-brand, .btn-topbar, .btn-light-soft, .side-link, " +
        ".side-group__toggle, .app-sidebar__collapse, .icon-button, .topbar-btn, .dropdown-item-link, [data-ripple]";

    document.addEventListener("pointerdown", function (event) {
        if (REDUCED_MOTION.matches || event.button !== 0) return;
        var host = event.target.closest(RIPPLE_SELECTOR);
        if (!host || host.classList.contains("is-disabled") || host.disabled) return;

        var rect = host.getBoundingClientRect();
        var size = Math.max(rect.width, rect.height) * 2;
        var ripple = document.createElement("span");
        ripple.className = "ripple";
        ripple.style.width = ripple.style.height = size + "px";
        ripple.style.left = (event.clientX - rect.left - size / 2) + "px";
        ripple.style.top = (event.clientY - rect.top - size / 2) + "px";
        host.appendChild(ripple);
        ripple.addEventListener("animationend", function () { ripple.remove(); }, { once: true });
    }, { passive: true });

    /* =============================================================
       8. SCROLL: bóng topbar + nút lên đầu trang (gộp 1 rAF, passive)
       ============================================================= */
    var backToTopButtons = $all("[data-back-to-top]");
    var ticking = false;

    function onScroll() {
        var y = window.scrollY || window.pageYOffset;
        if (topbar) topbar.classList.toggle("is-scrolled", y > 4);
        backToTopButtons.forEach(function (btn) { btn.classList.toggle("is-visible", y > 400); });
        ticking = false;
    }
    window.addEventListener("scroll", function () {
        if (!ticking) { ticking = true; window.requestAnimationFrame(onScroll); }
    }, { passive: true });
    onScroll();

    backToTopButtons.forEach(function (btn) {
        btn.addEventListener("click", function () {
            window.scrollTo({ top: 0, behavior: REDUCED_MOTION.matches ? "auto" : "smooth" });
        });
    });

    // Gợi ý "còn nội dung bên phải" cho bảng cuộn ngang
    var tableWraps = $all(".table-wrap");
    function updateTableHint(wrap) {
        var remaining = wrap.scrollWidth - wrap.clientWidth - wrap.scrollLeft;
        wrap.classList.toggle("has-overflow-right", remaining > 2);
    }
    tableWraps.forEach(function (wrap) {
        updateTableHint(wrap);
        wrap.addEventListener("scroll", function () { updateTableHint(wrap); }, { passive: true });
    });
    if ("ResizeObserver" in window && tableWraps.length) {
        var ro = new ResizeObserver(function (entries) {
            entries.forEach(function (entry) { updateTableHint(entry.target); });
        });
        tableWraps.forEach(function (wrap) { ro.observe(wrap); });
    }

    /* =============================================================
       9. MISC
       ============================================================= */
    // Link "Đang phát triển": chặn điều hướng
    document.addEventListener("click", function (event) {
        var disabled = event.target.closest(".side-link.is-disabled, a[aria-disabled='true']");
        if (disabled) event.preventDefault();
    });

    // Link có data-scroll-target: nếu đang ở đúng trang thì cuộn mượt tới section
    $all("[data-scroll-target]").forEach(function (link) {
        link.addEventListener("click", function (event) {
            var path = link.getAttribute("data-scroll-path");
            if (path && window.location.pathname.indexOf(path) === -1) return;
            var section = document.getElementById(link.getAttribute("data-scroll-target"));
            if (!section) return;
            event.preventDefault();
            section.scrollIntoView({ behavior: REDUCED_MOTION.matches ? "auto" : "smooth", block: "start" });
        });
    });

    /* -------------------------------------------------------------
       Khởi tạo: đồng bộ ARIA rồi bật lại transition sau frame đầu
       ------------------------------------------------------------- */
    syncToggleState();
    window.requestAnimationFrame(function () {
        window.requestAnimationFrame(function () { root.classList.remove("is-preload"); });
    });
})();
