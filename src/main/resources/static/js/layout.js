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
   10. Reveal      : (v2) hiện dần card khi cuộn tới — opt-out bằng data-reveal-skip
   11. Loading     : (v2) trạng thái đang xử lý cho form có data-loading-on-submit
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
       SIDEBAR SCROLL RESTORATION & SYNC
       Bảo toàn vị trí cuộn của sidebar khi chuyển trang, chống giật / nhảy lên đầu trang
       ============================================================= */
    (function initSidebarScroll() {
        var nav = document.querySelector(".app-sidebar__nav");
        if (!nav) return;

        var SCROLL_KEY = "cvdcms.sidebar.navScrollTop";

        function saveScroll() {
            try {
                sessionStorage.setItem(SCROLL_KEY, String(nav.scrollTop));
            } catch (e) {}
        }

        function restoreScroll() {
            try {
                var saved = sessionStorage.getItem(SCROLL_KEY);
                if (saved !== null) {
                    nav.scrollTop = parseInt(saved, 10) || 0;
                }
                var active = nav.querySelector(".side-link.is-active");
                if (active) {
                    var navRect = nav.getBoundingClientRect();
                    var actRect = active.getBoundingClientRect();
                    if (actRect.top < navRect.top || actRect.bottom > navRect.bottom) {
                        var target = active.offsetTop - (nav.clientHeight / 2) + (active.clientHeight / 2);
                        nav.scrollTop = Math.max(0, target);
                        saveScroll();
                    }
                }
            } catch (e) {}
        }

        var scrollTimer = null;
        nav.addEventListener("scroll", function () {
            if (scrollTimer) clearTimeout(scrollTimer);
            scrollTimer = setTimeout(saveScroll, 50);
        }, { passive: true });

        document.addEventListener("click", function (e) {
            var link = e.target.closest(".app-sidebar a");
            if (link) saveScroll();
        }, true);

        window.addEventListener("beforeunload", saveScroll);
        window.addEventListener("pageshow", restoreScroll);
        restoreScroll();
    })();

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
        // Nhóm luôn được mở sẵn toàn bộ link con
        group.classList.add("is-open");
        if (group.querySelector(".side-link.is-active")) {
            group.classList.add("has-active");
        }
        setGroupOpen(group, true);

        var toggle = group.querySelector("[data-accordion-toggle]");
        if (!toggle) return;
        toggle.addEventListener("click", function (e) {
            if (e) e.preventDefault();
            // Đang ở rail icon-only: mở rộng sidebar
            if (isDesktop() && root.classList.contains("is-sidebar-collapsed")) {
                setCollapsed(false);
            }
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
            event.preventDefault();
            event.stopPropagation();
            var item = toggle.closest("[data-dropdown]");
            if (!item) return;
            var willOpen = !item.classList.contains("is-open");
            closeAllDropdowns(item);
            item.classList.toggle("is-open", willOpen);
            toggle.setAttribute("aria-expanded", String(willOpen));
        });
        toggle.addEventListener("keydown", function (event) {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                toggle.click();
            }
        });
    });

    // Ngăn chặn sự kiện click bên trong dropdown panel làm đóng chính nó
    $all(".dropdown-panel, .cp-user-dropdown, .user-dropup-panel").forEach(function (panel) {
        panel.addEventListener("pointerdown", function (event) {
            event.stopPropagation();
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

    // Link có data-scroll-target: nếu đang ở đúng trang thì chuyển tab / cuộn mượt tới section
    $all("[data-scroll-target]").forEach(function (link) {
        link.addEventListener("click", function (event) {
            var path = link.getAttribute("data-scroll-path");
            if (path && window.location.pathname.indexOf(path) === -1) return;
            var targetId = link.getAttribute("data-scroll-target");

            // 1. Nếu trang hiện tại có hệ thống tab khám bệnh (như trang Ca khám của Bác sĩ)
            if (typeof window.switchMainTab === "function") {
                event.preventDefault();
                var tabId = targetId;
                if (targetId === "treatmentPlanSection" || targetId === "tab-treatment-plan") {
                    tabId = "tab-treatment-plan";
                } else if (targetId === "prescriptionsSection" || targetId === "tab-prescriptions") {
                    tabId = "tab-prescriptions";
                }
                window.switchMainTab(tabId);
                return;
            }

            // 2. Trường hợp thông thường: cuộn mượt tới section
            var section = document.getElementById(targetId);
            if (!section) return;
            event.preventDefault();
            section.scrollIntoView({ behavior: REDUCED_MOTION.matches ? "auto" : "smooth", block: "start" });
        });
    });

    /* =============================================================
       10. SCROLL REVEAL (v2)
       - Chỉ chạy khi có IntersectionObserver và KHÔNG bật giảm chuyển động.
       - Chỉ thêm/bỏ class hiển thị, gỡ sạch sau khi chạy xong để trả lại
         transition gốc (hover) của từng thành phần.
       - Phần tử nằm trong [data-reveal-skip] sẽ được bỏ qua.
       ============================================================= */
    (function initReveal() {
        if (REDUCED_MOTION.matches || !("IntersectionObserver" in window)) return;

        var SELECTOR = "[data-reveal], .stat-card, .feature-card, .cp-card, .cp-branch-card, .cp-section-header";
        var items = $all(SELECTOR).filter(function (el) {
            return !el.closest("[data-reveal-skip]") && !el.classList.contains("reveal-item");
        });
        if (!items.length) return;

        root.classList.add("js-reveal");

        items.forEach(function (el) {
            // Trễ nhẹ theo thứ tự trong cùng hàng (tối đa 5 bước) để tạo nhịp
            var index = Array.prototype.indexOf.call(el.parentNode.children, el);
            el.style.setProperty("--reveal-delay", (Math.min(Math.max(index, 0), 5) * 60) + "ms");
            el.classList.add("reveal-item");
        });

        function cleanup(el) {
            el.classList.remove("reveal-item", "is-revealed");
            el.style.removeProperty("--reveal-delay");
        }

        var observer = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) return;
                var el = entry.target;
                observer.unobserve(el);
                el.classList.add("is-revealed");
                var done = false;
                var finish = function () { if (!done) { done = true; cleanup(el); } };
                el.addEventListener("transitionend", function (e) {
                    if (e.target === el && e.propertyName === "opacity") finish();
                });
                window.setTimeout(finish, 1200); // phòng khi transitionend không bắn
            });
        }, { rootMargin: "0px 0px -6% 0px", threshold: 0.06 });

        items.forEach(function (el) { observer.observe(el); });
    })();

    /* =============================================================
       11. LOADING STATE CHO FORM (v2, opt-in)
       Thêm data-loading-on-submit vào <form> để nút submit hiện spinner.
       - Không disable nút (giữ nguyên name/value gửi lên server).
       - Nếu JS nghiệp vụ khác preventDefault() thì không bật loading.
       - Gỡ trạng thái khi quay lại trang bằng nút Back (bfcache).
       ============================================================= */
    document.addEventListener("submit", function (event) {
        var form = event.target;
        if (!form || !form.matches || !form.matches("form[data-loading-on-submit]")) return;
        var button = event.submitter || form.querySelector("[type='submit']");
        window.setTimeout(function () {
            if (event.defaultPrevented || !button) return;
            button.classList.add("is-loading");
            button.setAttribute("aria-busy", "true");
        }, 0);
    });
    window.addEventListener("pageshow", function () {
        $all(".is-loading[aria-busy='true']").forEach(function (el) {
            el.classList.remove("is-loading");
            el.removeAttribute("aria-busy");
        });
    });

    /* -------------------------------------------------------------
       Khởi tạo: đồng bộ ARIA rồi bật lại transition sau frame đầu
       ------------------------------------------------------------- */
    /* =============================================================
       12. GUEST LOGIN CONFIRMATION PROMPT (Customer Portal)
       ============================================================= */
    (function initGuestLoginPrompt() {
        var modalEl = null;
        function ensureModal() {
            if (modalEl) return modalEl;
            var wrapper = document.createElement("div");
            wrapper.id = "cvGuestLoginPromptModal";
            wrapper.setAttribute("role", "dialog");
            wrapper.setAttribute("aria-modal", "true");
            wrapper.style.cssText = "display:none;position:fixed;inset:0;background:rgba(15,23,42,0.55);z-index:10050;align-items:center;justify-content:center;padding:16px;backdrop-filter:blur(2px);";
            wrapper.innerHTML =
                '<div style="background:#ffffff;border-radius:16px;max-width:440px;width:100%;padding:24px;box-shadow:0 20px 35px -8px rgba(15,23,42,0.25);border:1px solid #e2e8f0;">' +
                '  <div style="display:flex;align-items:center;gap:12px;margin-bottom:12px;">' +
                '    <span style="width:42px;height:42px;border-radius:12px;background:#eff6ff;color:#0052cc;display:inline-flex;align-items:center;justify-content:center;font-size:20px;flex-shrink:0;">' +
                '      <i class="bi bi-person-lock"></i>' +
                '    </span>' +
                '    <div>' +
                '      <h3 style="margin:0;font-size:17px;font-weight:700;color:#0f172a;">Yêu cầu đăng nhập</h3>' +
                '      <div style="font-size:12px;color:#64748b;">Hệ thống Nha khoa Cường Việt · CV-DCMS</div>' +
                '    </div>' +
                '  </div>' +
                '  <p id="cvGuestLoginPromptMessage" style="margin:0 0 20px;font-size:14px;line-height:1.6;color:#334155;"></p>' +
                '  <div style="display:flex;justify-content:flex-end;gap:10px;flex-wrap:wrap;">' +
                '    <button type="button" id="cvGuestLoginCancelBtn" style="padding:9px 16px;border-radius:10px;border:1px solid #cbd5e1;background:#f8fafc;color:#334155;font-weight:600;font-size:14px;cursor:pointer;">Để sau</button>' +
                '    <a id="cvGuestLoginConfirmBtn" href="/login" style="padding:9px 18px;border-radius:10px;border:none;background:#0052cc;color:#ffffff;font-weight:600;font-size:14px;text-decoration:none;display:inline-flex;align-items:center;gap:6px;">' +
                '      <i class="bi bi-box-arrow-in-right"></i> Đồng ý đăng nhập' +
                '    </a>' +
                '  </div>' +
                '</div>';
            document.body.appendChild(wrapper);
            var cancelBtn = wrapper.querySelector("#cvGuestLoginCancelBtn");
            if (cancelBtn) cancelBtn.addEventListener("click", function () { wrapper.style.display = "none"; });
            wrapper.addEventListener("click", function (e) { if (e.target === wrapper) wrapper.style.display = "none"; });
            modalEl = wrapper;
            return modalEl;
        }
        function showLoginPrompt(actionLabel, targetUrl) {
            var modal = ensureModal();
            var msgEl = modal.querySelector("#cvGuestLoginPromptMessage");
            var confirmBtn = modal.querySelector("#cvGuestLoginConfirmBtn");
            if (msgEl) msgEl.textContent = "Bạn cần đăng nhập tài khoản để " + actionLabel + ". Bạn có muốn chuyển đến trang đăng nhập để thực hiện tiếp hay không?";
            if (confirmBtn) confirmBtn.setAttribute("href", targetUrl || "/login");
            modal.style.display = "flex";
        }
        document.addEventListener("click", function (event) {
            var trigger = event.target.closest("[data-require-login]");
            if (trigger) {
                event.preventDefault();
                showLoginPrompt(trigger.getAttribute("data-require-login") || "thực hiện chức năng này", trigger.getAttribute("href") || "/login");
                return;
            }
            var loginLink = event.target.closest("a[href='/login'], a[href$='/login']");
            if (!loginLink) return;
            var text = (loginLink.textContent || "").trim().toLowerCase();
            if (text.indexOf("đặt lịch") !== -1) {
                event.preventDefault();
                showLoginPrompt("đặt lịch khám trực tuyến", loginLink.getAttribute("href") || "/login");
            }
        });
    })();

    syncToggleState();
    window.requestAnimationFrame(function () {
        window.requestAnimationFrame(function () { root.classList.remove("is-preload"); });
    });

    /* =============================================================
       10. BOOTSTRAP MODAL GLOBAL FIX
       - Đảm bảo toàn bộ modal luôn là con trực tiếp của <body>
         để không bị kẹt trong stacking context của .app-content hoặc tab containers.
       - Tự động dọn dẹp modal-backdrop và khôi phục scroll body khi đóng.
       ============================================================= */
    document.addEventListener("show.bs.modal", function (event) {
        var modal = event.target;
        if (modal && modal.parentElement !== document.body) {
            document.body.appendChild(modal);
        }
    });

    document.addEventListener("hidden.bs.modal", function () {
        var openModals = document.querySelectorAll(".modal.show");
        if (openModals.length === 0) {
            var backdrops = document.querySelectorAll(".modal-backdrop");
            backdrops.forEach(function (bd) {
                bd.remove();
            });
            document.body.classList.remove("modal-open");
            document.body.style.removeProperty("overflow");
            document.body.style.removeProperty("padding-right");
        }
    });

    /* =============================================================
       11. GLOBAL INLINE BRANCH SWITCHER (CHUẨN DENTALSOFT)
       ============================================================= */
    window.switchAppBranchContext = function (branchId) {
        var url = '/api/branches/switch-context?branchId=' + encodeURIComponent(branchId || '');
        var headers = {
            'Content-Type': 'application/json',
            'X-Requested-With': 'XMLHttpRequest'
        };
        var csrfMeta = document.querySelector('meta[name="_csrf"]');
        var csrfHeaderMeta = document.querySelector('meta[name="_csrf_header"]');
        if (csrfMeta && csrfHeaderMeta && csrfMeta.content && csrfHeaderMeta.content) {
            headers[csrfHeaderMeta.content] = csrfMeta.content;
        }
        fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: headers
        }).then(function (res) {
            return res.json();
        }).then(function (data) {
            if (data && data.success) {
                var filterForm = document.getElementById('leaveFilterForm') ||
                                 document.getElementById('filterForm') ||
                                 document.getElementById('auditFilterForm') ||
                                 document.querySelector('form.filter-form') ||
                                 document.querySelector('form.df-clinic-toolbar-form');
                var branchInput = filterForm ? (filterForm.querySelector('input[name="branchId"]') || filterForm.querySelector('select[name="branchId"]')) : null;
                if (filterForm && branchInput) {
                    branchInput.value = branchId || '';
                    var pageInput = filterForm.querySelector('input[name="page"]');
                    if (pageInput) pageInput.value = '0';
                    filterForm.submit();
                } else {
                    var currentUrl = new URL(window.location.href);
                    if (branchId) {
                        currentUrl.searchParams.set('branchId', branchId);
                    } else {
                        currentUrl.searchParams.delete('branchId');
                    }
                    currentUrl.searchParams.set('page', '0');
                    window.location.href = currentUrl.toString();
                }
            } else {
                alert(data && data.message ? data.message : 'Không thể chuyển cơ sở');
            }
        }).catch(function () {
            var currentUrl = new URL(window.location.href);
            if (branchId) {
                currentUrl.searchParams.set('branchId', branchId);
            } else {
                currentUrl.searchParams.delete('branchId');
            }
            currentUrl.searchParams.set('page', '0');
            window.location.href = currentUrl.toString();
        });
    };
})();
