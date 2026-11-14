---
name: content-ux-design
description: >-
  Audit and refactor Thymeleaf content areas, detail views, wizards, and complex forms using external UI/UX design system standards (NN/g, Shopify Polaris, IBM Carbon, Material 3, Atlassian, Laws of UX, Refactoring UI). Activate before designing or simplifying content screens to eliminate duplicate buttons, reduce border clutter, enforce 1-primary-CTA hierarchy, preserve clinical workflow order, and improve inline validation UX.
  Covers adaptive clinical summary layouts, Micro-States for zero-data modules, and data-driven odontogram chrome.
---

# Content Area UI/UX Design & Audit Checklist

Apply this checklist whenever refactoring or creating non-list content pages (detail dashboards, wizards, treatment plans, multi-section forms). Keep Sidebar, Header, and List/Table patterns aligned with `GEMINI.md` Rule 7.

## 1. Anti-Redundancy (Single Source of Truth)
- Remove duplicate action buttons appearing in both container headers and individual sub-cards.
- Keep one Card focused on one logical subject (Material 3); avoid nested scrollbars or carousels inside cards.
- Combine symmetric toggles (*Expand all* / *Collapse all*) into a single stateful toggle button.

## 2. Action Consolidation & Hierarchy
- Strictly **1 Primary solid CTA** per viewport (IBM Carbon / Material 3 / Shopify Polaris); never place two high-emphasis filled buttons side by side.
- Demote intra-section actions (*Add item*, *Auto-split*, *Add stage*) to tonal or outline secondary styles.
- Pair order: **Secondary on the left, Primary on the right** (`[Cancel] [Save]`).
- Consolidate >5 secondary actions or tight-space actions into an Overflow Menu `⋯` or Split Button; isolate destructive actions at the bottom below a divider or keep table-row delete icons neutral gray until hover.
- Use `{Verb} + {Noun}` labels; keep titles Sentence case (3–5 words) with `text-transform: none` if shared stylesheets apply uppercase.

## 3. Minimalism & Visual Craft (Grayscale First)
- Validate contrast, hierarchy, and spacing without color first (Refactoring UI): 3-tier text hierarchy (Primary `#0f172a` SemiBold, Secondary `#475569`, Tertiary `#64748b`).
- Eliminate `table-bordered` vertical gridlines and cards nested inside bordered cards; separate groups using subtle fills (`#f8fafc` vs `#ffffff`), hairline row dividers (`#f1f5f9`), and 8px-grid spacing rhythm.
- **No Hollow Equal-Height Boxes:** Never force `height: 100vh` / `overflow: hidden` or equal `1fr` row tracks when sections may have zero records; allow natural flow sizing and sticky the visual anchor (e.g., Odontogram) on desktop.
- Strict Bootstrap 5 utilities only (`gap-2`, `px-3`, `py-2`, `me-1`) — zero decimal Tailwind utilities.

## 4. Detailed UX, Workflow Order & Forms
- **Clinical Mental Model + Progressive Disclosure (NN/g):** Follow domain task order (e.g., *Diagnosis → Services → Plan Details → Roadmap Stages → Installments*, or on visit summaries *Odontogram anchor | Diagnosis & Procedures → Treatment Plan → Prescription*), auto-expanding optional sections when pre-populated and collapsing them to a slim summary bar when empty.
- **Micro-States & Data-Driven Chrome (IBM Carbon / Dental EHR):** Collapse zero-data clinical cards into compact 1-line horizontal micro-bars (`~48px`, title left + quiet muted status right + subtle tonal CTA only when writable). Hide empty count pills (`0 răng`), multi-dot odontogram legends, and segmented switchers `(0)|(0)` until real data exists. Keep high-frequency reception/clinical items only; omit bedside vitals/exam prose from read-oriented visit overviews.
- **Cognitive Laws (Laws of UX):** Respect Hick's Law (hide crowded quick-chip bars until requested), Miller's Law (chunk fields into groups of 5–7), Law of Proximity (tight label-input spacing, larger inter-group gaps), and Tesler's Law (auto-calculate dates, totals, titles).
- **Forms & Validation (Atlassian):** Place labels above fields left-aligned; autofocus the primary starting field on create; never disable form submit buttons—keep clickable and trigger non-blocking inline errors that dynamically clear on input.
- **Empty States (Shopify Polaris):** State why it is empty + provide 1 clear primary action (differentiate zero-data from active-filter-empty).

## External Reference Sources
- NN/g Progressive Disclosure: https://www.nngroup.com/articles/progressive-disclosure/
- NN/g Minimalism: https://www.nngroup.com/articles/characteristics-minimalism/
- Shopify Polaris Buttons / Cards / Empty State: https://polaris.shopify.com/components/actions/button | https://polaris.shopify.com/components/layout-and-structure/card | https://polaris.shopify.com/components/feedback-and-status-indicators/empty-state
- IBM Carbon Buttons / Overflow / Forms: https://www.carbondesignsystem.com/components/button/usage | https://www.carbondesignsystem.com/components/overflow-menu/usage | https://www.carbondesignsystem.com/patterns/form-pattern/overview
- IBM Carbon Status Indicators & Empty States: https://www.carbondesignsystem.com/patterns/empty-states-pattern/
- Material Design 3 Buttons / Split / Cards / Spacing: https://m3.material.io/components/all-buttons/guidelines | https://m3.material.io/components/split-button/overview | https://m3.material.io/components/cards/guidelines | https://m3.material.io/styles/spacing/overview
- Atlassian Buttons & Forms: https://atlassian.design/components/button/usage | https://atlassian.design/components/form/usage
- Laws of UX & Refactoring UI: https://lawsofux.com/ | https://refactoringui.com/
