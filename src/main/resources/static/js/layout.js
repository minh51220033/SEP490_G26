(function () {
"use strict";

const DESKTOP_QUERY = window.matchMedia("(min-width: 992px)");

function closeAllDropdowns(except) {
document.querySelectorAll("[data-dropdown].is-open").forEach(function (item) {
if (item === except) return;
item.classList.remove("is-open");
const toggle = item.querySelector("[data-dropdown-toggle]");
if (toggle) toggle.setAttribute("aria-expanded", "false");
});
}

document.querySelectorAll("[data-dropdown-toggle]").forEach(function (toggle) {
toggle.addEventListener("click", function (event) {
event.stopPropagation();
const item = toggle.closest("[data-dropdown]");
const willOpen = !item.classList.contains("is-open");
closeAllDropdowns(item);
item.classList.toggle("is-open", willOpen);
toggle.setAttribute("aria-expanded", String(willOpen));
});
});

document.addEventListener("click", function (event) {
if (!event.target.closest("[data-dropdown]")) closeAllDropdowns();
});

document.addEventListener("keydown", function (event) {
if (event.key !== "Escape") return;
closeAllDropdowns();
document.body.classList.remove("sidebar-open");
});

const navToggle = document.querySelector("[data-nav-toggle]");
if (navToggle) {
const nav = document.getElementById(navToggle.getAttribute("aria-controls"));
navToggle.addEventListener("click", function () {
const isOpen = nav.classList.toggle("is-open");
navToggle.setAttribute("aria-expanded", String(isOpen));
navToggle.querySelector(".bi").className = isOpen ? "bi bi-x-lg" : "bi bi-list";
});
}

document.querySelectorAll("[data-sidebar-toggle]").forEach(function (button) {
button.addEventListener("click", function () {
if (DESKTOP_QUERY.matches) {
document.body.classList.toggle("sidebar-collapsed");
} else {
document.body.classList.toggle("sidebar-open");
}
});
});

document.querySelectorAll(".side-link.is-disabled").forEach(function (link) {
link.addEventListener("click", function (event) {
event.preventDefault();
});
});

document.querySelectorAll("[data-back-to-top]").forEach(function (button) {
button.addEventListener("click", function () {
window.scrollTo({ top: 0, behavior: "smooth" });
});
});
})();