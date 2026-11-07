# Project: SEP490_G26_CV_DMS - Dental Clinic EMR & Patient Management

## Architecture
- Spring Boot 3 MVC / REST, Thymeleaf, Spring Data JPA / Hibernate, PostgreSQL / MySQL / H2.
- UI: Bootstrap 5, Bootstrap Icons, Custom DentalSoft & AMIS styles (`daily-visits.html` as primary design reference), `#0284c7` primary color.
- Modules:
  1. Patient Module (`com.cvdcms.patient`): Patient records, search, demographic details, visits history, scan files.
  2. Doctor / Examination Module (`com.cvdcms.doctor`): Diagnoses, examination tabs, dental chart, clinical photos, prescriptions, service orders.
  3. EMR Module (`com.cvdcms.emr`): Electronic Medical Records (MS: BA-18), snapshot synchronization, digital signing, sheets & attachments.
  4. Billing / Invoice Module: Financial indicators, invoices (restricted to Cashier/Accountant).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | diagnoses.html tab hash fix | Fix hash clearing, restore tab activation from URL hash, add quick EMR link after completion | M1 | ORIGINAL_REQUEST §R1 (DONE) |
| 2 | patient-records/list.html UI alignment | Align with daily-visits.html (#0284c7, dv-toolbar-card, dv-quick-segment, dv-amis-table with sticky columns) | M1 | ORIGINAL_REQUEST §R1 (DONE) |
| 3 | list.html badge syntax fix | Fix Thymeleaf syntax so 1 Payment badge & 1 Source badge show correctly | M1 | ORIGINAL_REQUEST §R1 (DONE) |
| 4 | list.html CRUD actions & pagination | View, Edit modal, Soft-delete modal, Add patient modal with Source, sticky footer pagination | M1 | ORIGINAL_REQUEST §R1 (DONE) |
| 5 | sidebar.html cleanup | Remove standalone /emr/records menu item from left sidebar | M1 | ORIGINAL_REQUEST §R1 (DONE) |
| 6 | detail.html Header DentalSoft restructuring | Left: Avatar, code, name, phone, Allergy/Condition banner with quick-edit, "Vào khám ngay" button. Right: 3 financial cards (Incurred, Paid, Debt) | M2 | ORIGINAL_REQUEST §R2 (DONE) |
| 7 | detail.html Tab Pill consolidation | Consolidate loose buttons into unified tab pills: Patient Info \| Treatment Progress \| EMR \| Photos \| Billing | M2 | ORIGINAL_REQUEST §R2 (DONE) |
| 8 | detail.html Patient Info Tab (4 cards) | 2x2 grid: Identity (Change code, Merge, Edit), Contact, Source & Intake, Medical & Notes with ID/Insurance scan manager (upload, lightbox, change type, delete) | M2 | ORIGINAL_REQUEST §R2 (DONE) |
| 9 | detail.html Billing Tab role check & filters | Restrict Create Invoice to Cashier/Accountant, remove duplicate dashboard button, add filter & pagination | M2 | ORIGINAL_REQUEST §R2 (DONE) |
| 10 | detail.html Photos Tab CRUD | dv-toolbar-card, date & category filter, upload clinical photo modal, lightbox, edit note/type, delete | M2 | ORIGINAL_REQUEST §R2 (DONE) |
| 11 | detail.html EMR Tab in-place management | 4 KPI cards, toolbar, in-place create EMR per visit, EMR table with CRUD actions, pagination | M2 | ORIGINAL_REQUEST §R2 (DONE) |
| 12 | detail.html Treatment Progress Tab | 2 view modes: Group by visit date (with create EMR button) and detailed audit log table with badges, search, pagination | M2 | ORIGINAL_REQUEST §R2 (DONE) |
| 13 | visit.html UI overhaul | Rebuild visit detail screen matching daily-visits.html (#0284c7, 16px radius, crisp badges) | M3 | ORIGINAL_REQUEST §R3 (DONE) |
| 14 | visit.html full clinical examination data | Admin info, reason, vitals, visual dental chart, ICD-10 diagnoses, treatment plan, performed services, lab tests/x-rays, prescriptions, re-examination date, action buttons | M3 | ORIGINAL_REQUEST §R3 (DONE) |
| 15 | EMR backend 1-1 visit synchronization | 1 EMR per visit rule, map clinical examination to EmrSourceSnapshot and sheets, tooth & procedure breakdown for BA-18 chart | M3 | ORIGINAL_REQUEST §R3 (DONE) |
| 16 | EMR sync & quick edit endpoints | Endpoint to re-sync examination to open EMR, endpoint to quick-update patient demographics from EMR modal, soft-delete for draft EMR | M3 | ORIGINAL_REQUEST §R3 (DONE) |
| 17 | emr/detail.html 2-column layout & cleanup | 2-column layout (280px sidebar + A4 view), drawer/modal for logs, remove red/orange lock/supplement warning blocks, remove bottom blue edit form | M4 | ORIGINAL_REQUEST §R4 (DONE) |
| 18 | EMR Cover & Sheet 2 (MS: BA-18) standardization | Part A: items 1-14 with character boxes. Part B: 2-tier cross dental chart (permanent & primary) with intervension highlights (#fde68a, •, tooltips, procedure breakdown table), Part 5 (lab) & Part 6 (summary) | M4 | ORIGINAL_REQUEST §R4 (DONE) |
| 19 | EMR digital signing & status | Direct "Ký số ngay" and "Đóng HSBA" buttons on sub-header & sign area, green electronic seal when signed | M4 | ORIGINAL_REQUEST §R4 (DONE) |
| 20 | emr/list.html & emr.css modernization | System sans-serif font (Inter/Segoe UI/Arial), 4 pastel KPI cards, 9-column standardized table, pagination | M4 | ORIGINAL_REQUEST §R4 (DONE) |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | R1_patient_list_navigation | Patient list UI alignment, Thymeleaf fix, action buttons, diagnoses hash fix, sidebar cleanup | none | DONE |
| 2 | R2_patient_detail_tabs | Patient detail Header DentalSoft, 5 tab pills, scan manager, photo CRUD, billing auth check, EMR tab & treatment progress | none | DONE |
| 3 | R3_visit_detail_emr_backend | Visit detail UI overhaul & comprehensive data view, backend EMR sync, EmrSourceSnapshot mapping, tooth procedure mapping, soft-delete draft EMR | none | DONE |
| 4 | R4_emr_ba18_frontend | EMR detail 2-column layout, MS: BA-18 form standardization (Part A boxes, Part B 2-tier cross chart), digital signing, emr/list.html & emr.css styling | none | DONE |

## Code Layout & File Boundaries (STRICTLY NON-OVERLAPPING - 24 FILES AUDITED CLEAN)
### Worker 1 (R1: Patient List, Diagnoses Hash, Navigation)
- `src/main/resources/templates/patient-records/list.html`
- `src/main/resources/templates/doctor/diagnoses.html`
- `src/main/resources/templates/fragments/sidebar.html`
- `src/main/java/com/cvdcms/patient/PatientSearchRepository.java`
- `src/main/java/com/cvdcms/patient/PatientRestController.java`

### Worker 2 (R2: Patient Detail, Header DentalSoft, Deep Tabs)
- `src/main/resources/templates/patient-records/detail.html`
- `src/main/resources/static/css/patients.css`
- `src/main/resources/static/css/patient-emr.css`
- `src/main/java/com/cvdcms/patient/DoctorRecordPageController.java`
- `src/main/java/com/cvdcms/patient/DoctorRecordService.java`
- `src/main/java/com/cvdcms/patient/PatientPageController.java`
- `src/main/java/com/cvdcms/patient/PatientService.java`

### Worker 3 (R3: Visit Detail Screen & EMR Backend Synchronization)
- `src/main/resources/templates/patient-records/visit.html`
- `src/main/java/com/cvdcms/patient/DoctorRecordRepository.java`
- `src/main/java/com/cvdcms/patient/PatientVisitReadRepository.java`
- `src/main/java/com/cvdcms/patient/VisitView.java`
- `src/main/java/com/cvdcms/emr/EmrModuleService.java`
- `src/main/java/com/cvdcms/emr/EmrSourceSnapshot.java`
- `src/main/java/com/cvdcms/emr/EmrPageController.java`
- `src/main/java/com/cvdcms/emr/EmrRestController.java`

### Worker 4 (R4: EMR Detail MS: BA-18, List & CSS)
- `src/main/resources/templates/emr/detail.html`
- `src/main/resources/templates/emr/list.html`
- `src/main/resources/static/css/emr.css`
- `src/main/resources/static/js/emr-detail.js`
