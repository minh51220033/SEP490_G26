# Progress - Worker 2 (R2: Detail, DentalSoft Header & Tabs)

Last visited: 2026-11-07T07:23:00Z
Status: In Progress - Verification & Finalizing

## Completed Tasks:
- [x] Researched reference designs: `ORIGINAL_REQUEST.md`, `PROJECT.md`, `daily-visits.html`, and `Lỗi tìm được.docx`
- [x] Updated `DoctorRecordService.java` & `DoctorRecordPageController.java`:
  - Added enhanced `PhotoView`, `EmrView`, `TreatmentTimelineItem`, `TreatmentServiceItem`, `AuditLogRow` records
  - Implemented `treatmentTimeline` (diagnoses, performed services, linked EMR)
  - Implemented `auditLogs` with colored badges ([THÊM], [SỬA], [XÓA])
  - Added financial metrics calculation: `totalIncurred`, `totalPaid`, `totalDebt`
  - Added active visit detection for prominent "Vào khám ngay" action button
  - Added quick update health notes (`allergyDetails`, `underlyingDiseaseDetails`)
  - Added change patient code & merge request endpoints
  - Added document scan type change and delete endpoints
  - Added clinical photo upload, update, and delete endpoints
  - Restricted billing creation to Cashier/Accountant only (`canManageBilling = false` for doctor)
- [x] Updated `PatientService.java` & `PatientPageController.java`:
  - Injected `ClinicalPhotoRepository`, `EmrRecordRepository`, `PatientDocumentRepository`
  - Preserved backward-compatible 13-argument constructor for existing unit tests
  - Implemented `photos`, `emrRecords`, `treatmentTimeline`, `auditLogs`
  - Implemented `updateHealthNotes`, `updatePatientCode`, `updateDocumentType`, `deleteDocument`
  - Implemented role-based `canManageBilling` check (checks `KE_TOAN`, `THU_NGAN`, `ADMIN`)
- [x] Rebuilt `src/main/resources/templates/patient-records/detail.html`:
  - Re-structured Header according to DentalSoft standard:
    * Left: Avatar circle with initials, code badge, full name, phone, gender pill, age/DOB, branch
    * Prominent Allergy / Underlying Disease warning banner with quick-edit modal
    * "Vào khám ngay" prominent action button for active visit
    * Right: 3 Financial Indicator Cards: Phát sinh, Đã thanh toán (#10b981), Công nợ (#ef4444)
  - Consolidated loose buttons into a single modern Pill Tab bar:
    * `Thông tin bệnh nhân` | `Quá trình điều trị` | `Hồ sơ bệnh án (BAĐT)` | `Ảnh tổng hợp` | `Hóa đơn & Viện phí`
  - Tab 1: 2x2 Grid of 4 rounded 16px cards (Định danh with [Đổi mã], [Gộp HS], [Sửa]; Liên hệ; Nguồn & Tiếp nhận; Y tế & Quản lý scan CCCD/BHYT with view, change type, delete)
  - Tab 2: Quá trình điều trị with 2 view modes (Timeline by visit with in-place create EMR button vs. Audit Log table with badges, search, pagination)
  - Tab 3: Hồ sơ bệnh án (BAĐT) with 4 KPI cards, toolbar, in-place create EMR per visit, EMR table with CRUD actions
  - Tab 4: Ảnh tổng hợp with `.dv-toolbar-card`, date & quick segment filters, clinical photos grid, upload modal, lightbox viewer, edit & delete
  - Tab 5: Hóa đơn & Viện phí with role protection (button only for cashier/accountant, hidden for doctor/patient), removed duplicate dashboard button, search and payment status filter
  - Included 11 rich modals for all CRUD operations
- [x] Updated `patients.css` & `patient-emr.css`:
  - Added `.ds-header-card`, `.ds-avatar-circle`, `.ds-patient-name`, `.ds-patient-meta`, `.ds-alert-banner`
  - Added `.ds-finance-grid`, `.ds-finance-card` (incurred, paid, debt), `.ds-pill-tabs`, `.ds-card-grid-2x2`
  - Added `.ds-photo-grid`, `.ds-photo-card`, `.ds-photo-thumb`, `.ds-photo-badge`
  - Added EMR status styling (`status-DRAFT`, `status-AMENDMENT_DRAFT`)

## Current Task:
- [ ] Verifying build compilation (`mvn compile` and `mvn test-compile`)
- [ ] Writing handoff report and responding to parent
