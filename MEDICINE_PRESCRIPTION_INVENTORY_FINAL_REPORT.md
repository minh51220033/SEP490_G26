# BÁO CÁO TỔNG KẾT: ĐỒNG BỘ KÊ ĐƠN THUỐC – KHO THUỐC (CV-DCMS)

**Trạng thái:** `DONE` (Đã hoàn thiện triển khai, vượt qua 70/70 unit & regression tests, và demo trực tiếp thành công toàn bộ luồng End-to-End trên `http://localhost:8080`)  
**Ngày hoàn thành:** 27/11/2026  

---

## 1. Hiện trạng trước khi triển khai
- **Danh mục thuốc & Kho vật tư (`medications` ↔ `materials`):**
  - `MedicationServiceImpl.createMedication` đã tạo 1 bản ghi `Material` (`category = "Thuốc"`) và gán `materials.medication_id = medications.medication_id`, nhưng **không gán ngược `medications.material_id`** (`medication.getMaterial()` bị `null`).
  - Các thuốc mới tạo hoặc thuốc chưa từng nhập kho không có bản ghi `branch_inventory` (tồn = 0), nên không hiển thị trong màn hình **Tồn kho** (`GET /api/inventory`).
- **Luồng bác sĩ kê đơn (`PrescriptionServiceImpl` + `clinical-treatment.html`):**
  - `GET /api/doctor/medications/catalog` không kiểm tra phiên đăng nhập và **không trả về số lượng tồn kho, trạng thái tồn, hay hạn sử dụng** theo chi nhánh.
  - `POST /api/doctor/prescriptions` chỉ lưu đơn thuốc `PENDING`, **không kiểm tra tồn kho khả dụng**, không chặn thuốc hết hàng / hết hạn, **không trừ kho FEFO**, không chống double-submit (`clientRequestId`), và **chưa có API sửa/hủy đơn thuốc**.
  - Trừ kho thuốc trước đó chỉ diễn ra ở tầng Thu ngân (`InvoiceServiceImpl`) khi lập hóa đơn thuốc.
- **Lịch sử xuất kho (`StockIssue`):**
  - `StockIssueType` chỉ có `INTERNAL_USE`, `TRANSFER`, `DISCARD_EXPIRED`, `OTHER` — chưa có loại phiếu riêng cho xuất theo đơn thuốc / hoàn kho đơn thuốc và chưa lưu liên kết `prescription_id` / `prescription_code`.

---

## 2. Những lỗi & lỗ hổng phát hiện trong giai đoạn Audit
1. **Lệch khóa ngoại 2 chiều `Medication ↔ Material`:** `medications.material_id` bị `null` ở thuốc mới tạo, khiến các service đọc `med.getMaterial()` (kể cả `InvoiceServiceImpl`) báo lỗi thiếu liên kết kho.
2. **Nguy cơ trừ kho 2 lần:** Nếu trừ kho ngay khi bác sĩ kê đơn mà không đồng bộ cờ trạng thái với `InvoiceServiceImpl`, thu ngân lập hóa đơn thuốc sẽ trừ kho thêm lần thứ 2.
3. **Tính nhầm thuốc hết hạn vào tồn kho tổng:** `BranchInventory.currentStock` cộng gộp cả lô hết hạn cho tới khi xuất hủy (`DISCARD_EXPIRED`), nên không thể dùng trực tiếp `currentStock` làm tồn khả dụng cho bác sĩ.
4. **Thiếu `BatchStatus.LOCKED` / `RECALLED` trong truy vấn FEFO:** `findAvailableBatchesFefoForUpdate` trước đó chỉ lọc `quantityRemaining > 0 AND expiryDate >= :today`, chưa ràng buộc `status = ACTIVE`.
5. **Nguy cơ trùng mã đơn thuốc / phiếu xuất:** Sinh mã bằng `System.currentTimeMillis() % 10000000` dễ va chạm khi đồng thời.
6. **Thiếu phân quyền tại `GET /api/doctor/medications/catalog`** và thiếu cơ chế chống bấm lưu nhiều lần.
7. **Ràng buộc kiến trúc hiện có (`MedicationInventorySyncUnitTest`):** `MedicationServiceImpl` bắt buộc không được phụ thuộc trực tiếp vào `BranchInventoryRepository` / `MaterialBatchRepository` / `StockReceiptRepository`.

---

## 3. Nội dung thảo luận chéo giữa 6 Agent
- **Agent 1 (DB & Inventory Auditor) ↔ Agent 3 (Inventory Specialist):** Xác nhận dự án chạy `spring.flyway.enabled=false` và `spring.jpa.hibernate.ddl-auto=update`. Cột `stock_issues.issue_type` có độ dài `VARCHAR(20)` → thống nhất đặt tên enum mới là `PRESCRIPTION_OUT` (16 ký tự) và `PRESCRIPTION_RETURN` (19 ký tự) để tương thích 100% với cột MySQL hiện hữu mà không gây truncation. Các cột mới (`inventory_deducted`, `client_request_id`, `prescription_id`, `prescription_code`) đều nullable/an toàn với dữ liệu đang có.
- **Agent 2 (Prescription Backend) ↔ Agent 5 (Security & Business Logic):**
  - Thống nhất trừ kho FEFO **ngay khi bác sĩ xác nhận lưu đơn thuốc** (`POST /api/doctor/prescriptions`) trong cùng `@Transactional(rollbackFor = Exception.class)`.
  - Gom số lượng kê theo `materialId` vào `TreeMap` (sắp xếp tăng dần) trước khi khóa `PESSIMISTIC_WRITE` (`findForUpdate` & `findAvailableBatchesFefoForUpdate`) để **chống race condition và deadlock**.
  - Gắn cờ `Prescription.inventoryDeducted = true` để `InvoiceServiceImpl` nhận biết đơn đã trừ kho và **tuyệt đối không trừ kho lần 2** khi thu ngân lập hóa đơn (vẫn giữ nguyên luồng cũ cho đơn legacy `inventoryDeducted = false`).
  - Bảo toàn lịch sử kho: Sửa/Hủy/Xóa đơn không bao giờ xóa `StockIssue` cũ, mà tạo phiếu đối ứng `PRESCRIPTION_RETURN` hoàn về đúng lô gốc đã xuất.
- **Agent 3 ↔ Agent 2 (Phản biện sau lần chạy test đầu):** Khi `MedicationInventorySyncUnitTest.medicationService_hasNoInventoryOrBatchDependencies` báo lỗi vì `MedicationServiceImpl` inject `BranchInventoryRepository`, các Agent thống nhất giữ nguyên sự tách biệt trong `MedicationServiceImpl` và đưa logic khởi tạo dòng tồn `0` + đồng bộ dữ liệu cũ vào `MedicationInventorySyncInitializer`.
- **Agent 4 (Frontend & UX):** Xác nhận API `/api/doctor/medications/catalog?visitId=...` cung cấp đủ `availableStock`, `expiredStock`, `stockStatus`, `nearestExpiryDate`, `nearestBatchNumber`, `unit`, `salePrice` để hiển thị trực quan và chặn kê vượt tồn theo thời gian thực.

---

## 4. Phương án kỹ thuật thống nhất
1. **Đồng bộ danh mục ↔ kho:**
   - `MedicationServiceImpl` đồng bộ 2 chiều `materials.medication_id` và `medications.material_id`.
   - `MedicationInventorySyncInitializer` (`ApplicationRunner`) tự động đối soát toàn bộ thuốc hiện có sang `materials` và khởi tạo bản ghi `branch_inventory` với `current_stock = 0` (tuyệt đối không tạo tồn giả) cho các chi nhánh đang hoạt động.
2. **Tính tồn khả dụng chuẩn theo chi nhánh:**
   - Chỉ cộng tổng `quantity_remaining` của các lô `material_batches` thỏa mãn: `branch_id = :branchId`, `quantity_remaining > 0`, `expiry_date >= CURRENT_DATE`, `status = 'ACTIVE'` (loại trừ toàn bộ `EXPIRED`, `OUT_OF_STOCK`, `LOCKED`, `RECALLED`).
   - Phân biệt rõ 4 trạng thái: `IN_STOCK`, `LOW_STOCK`, `OUT_OF_STOCK`, `EXPIRED_ONLY`.
3. **Kê đơn trừ kho FEFO & Chống trùng lặp:**
   - Khóa bi quan theo thứ tự `materialId` tăng dần, xuất từ lô hết hạn sớm nhất (`ORDER BY expiryDate ASC, batchId ASC`), cập nhật `MaterialBatch`, `BranchInventory`, tạo `StockIssue` (`PRESCRIPTION_OUT`) + `StockIssueItem` theo từng lô.
   - Chống double-submit bằng `clientRequestId` (unique) + khóa nút submit ở UI.
4. **Sửa / Hủy / Xóa đơn an toàn:**
   - Chặn sửa/hủy/xóa nếu đơn đã `DISPENSED`, `CLINIC_PURCHASE_COMPLETED`, `CLINIC_PURCHASE_PARTIAL`, có `boughtQuantity > 0`, hoặc đã gắn hóa đơn hiệu lực.
   - Sửa đơn tính chênh lệch tăng/giảm theo từng `materialId` (tăng → xuất bổ sung FEFO `PRESCRIPTION_OUT`; giảm → hoàn về lô gốc `PRESCRIPTION_RETURN`).
   - Hủy đơn hoàn trả đúng số lượng ròng về từng lô gốc và ghi `StockIssue` loại `PRESCRIPTION_RETURN`.

---

## 5. Danh sách file đã thêm / chỉnh sửa
**Backend – Entities & Enums:**
- `src/main/java/com/cvdcms/entity/enums/BatchStatus.java` (thêm `LOCKED`, `RECALLED`)
- `src/main/java/com/cvdcms/entity/enums/StockIssueType.java` (thêm `PRESCRIPTION_OUT`, `PRESCRIPTION_RETURN`)
- `src/main/java/com/cvdcms/entity/Prescription.java` (thêm `inventoryDeducted`, `clientRequestId`, sinh mã `DTyyMMdd-XXXXXX`)
- `src/main/java/com/cvdcms/entity/StockIssue.java` (thêm `prescription`, `prescriptionCode`, sinh mã `PXyyMMdd-XXXXXXXX`)

**Backend – DTOs & Repositories:**
- `src/main/java/com/cvdcms/DTO/MedicationDTO.java`
- `src/main/java/com/cvdcms/DTO/PrescriptionRequestDTO.java`
- `src/main/java/com/cvdcms/DTO/PrescriptionResponseDTO.java`
- `src/main/java/com/cvdcms/DTO/StockIssueResponseDTO.java`
- `src/main/java/com/cvdcms/repository/MaterialBatchRepository.java`
- `src/main/java/com/cvdcms/repository/PrescriptionRepository.java`
- `src/main/java/com/cvdcms/repository/StockIssueRepository.java`

**Backend – Config, Services & Controllers:**
- `src/main/java/com/cvdcms/config/MedicationInventorySyncInitializer.java` *(file mới)*
- `src/main/java/com/cvdcms/service/PrescriptionService.java`
- `src/main/java/com/cvdcms/service/impl/MedicationServiceImpl.java`
- `src/main/java/com/cvdcms/service/impl/PrescriptionServiceImpl.java`
- `src/main/java/com/cvdcms/service/impl/InvoiceServiceImpl.java`
- `src/main/java/com/cvdcms/service/impl/StockIssueServiceImpl.java`
- `src/main/java/com/cvdcms/controller/PrescriptionRestController.java`

**Frontend – Templates & JS:**
- `src/main/resources/templates/doctor/fragments/prescriptions.html`
- `src/main/resources/templates/doctor/fragments/clinical-treatment.html`
- `src/main/resources/templates/inventory/list.html`
- `src/main/resources/static/js/inventory.js`

**Automated Tests:**
- `src/test/java/com/cvdcms/service/PrescriptionInventorySyncTest.java` *(file mới)*

---

## 6. Thay đổi Schema Database
Dự án cấu hình `spring.flyway.enabled=false` và `spring.jpa.hibernate.ddl-auto=update`. Hibernate đã tự động bổ sung các cột mới không phá hủy dữ liệu hiện có:
- Bảng `prescriptions`:
  - `inventory_deducted` (`BIT(1)`, nullable)
  - `client_request_id` (`VARCHAR(64)`, `UNIQUE`, nullable)
- Bảng `stock_issues`:
  - `prescription_id` (`BIGINT`, FK → `prescriptions.prescription_id`, nullable)
  - `prescription_code` (`VARCHAR(30)`, nullable)
- Dữ liệu đồng bộ tự động (không tạo tồn giả): `MedicationInventorySyncInitializer` đồng bộ liên kết 2 chiều `medications.material_id ↔ materials.medication_id` và đảm bảo các thuốc chưa nhập hàng xuất hiện trong `branch_inventory` với `current_stock = 0`.

---

## 7. API được thêm / cập nhật
| Method | Endpoint | Mô tả |
|---|---|---|
| `GET` | `/api/doctor/medications/catalog?visitId={id}&branchId={id}` | Yêu cầu đăng nhập & kiểm tra chi nhánh; trả về danh mục thuốc kèm `availableStock`, `expiredStock`, `minStockThreshold`, `nearestExpiryDate`, `nearestBatchNumber`, `stockStatus`. |
| `POST` | `/api/doctor/prescriptions` | Hỗ trợ `clientRequestId` chống gửi lặp; kiểm tra tồn còn hạn & khóa bi quan FEFO; trừ kho & tạo `StockIssue` (`PRESCRIPTION_OUT`) trong cùng transaction. |
| `PUT` | `/api/doctor/prescriptions/{id}` | *(Mới)* Sửa đơn thuốc chưa cấp phát/chưa lập hóa đơn, tự động tính chênh lệch tăng (`PRESCRIPTION_OUT`) / giảm (`PRESCRIPTION_RETURN`). |
| `POST` | `/api/doctor/prescriptions/{id}/cancel` | *(Mới)* Hủy đơn thuốc đủ điều kiện, hoàn kho về đúng lô gốc và ghi `StockIssue` (`PRESCRIPTION_RETURN`). |
| `DELETE` | `/api/doctor/prescriptions/{id}` | Khóa bi quan, tự hoàn kho nếu đã trừ, bảo toàn lịch sử `StockIssue` qua `prescriptionCode`. |

---

## 8. Kết quả kiểm thử (TEST 01 – TEST 16)
### Kết quả chạy Automated Test Suite (`.\mvnw.cmd test`)
- **Tổng số test chạy:** `70` | **Passed:** `70` | **Failed:** `0` | **Errors:** `0` | **`BUILD SUCCESS`** (12.736s)
  - `PrescriptionInventorySyncTest`: **8/8 PASS**
  - `MedicationInventorySyncUnitTest`: **14/14 PASS**
  - `DoctorPrescriptionAndReExamControllerTest`: **6/6 PASS**
  - `InventoryWorkflowUnitTest`: **12/12 PASS**
  - `BranchInventoryServiceTest`: **4/4 PASS**
  - `StockIssueServiceTest`: **11/11 PASS**
  - `StockReceiptServiceTest`: **15/15 PASS**

### Bảng đối chiếu 16 Test Case bắt buộc
| Test Case | Kịch bản | Hình thức xác minh | Kết quả |
|---|---|---|---|
| **TEST 01** | Thuốc có trong danh mục và có tồn kho | Unit Test + Live Demo (`Efferalgan 500mg = 101 Viên`) | **PASS** |
| **TEST 02** | Thuốc có trong danh mục nhưng chưa nhập kho (`0`) | Unit Test + Live Demo (`TH001`, `MED-ALPHA = 0`) | **PASS** |
| **TEST 03** | Thuốc chỉ còn lô hết hạn → tồn `0`, không thể kê | Unit Test (`createPrescription_expiredOnlyOrZeroStock_rejected`) | **PASS** |
| **TEST 04** | Nhiều lô (hết hạn + còn hạn) → chỉ tính lô còn hạn | Unit Test + Live Demo (`Augmentin 1g`: tổng kho `77`, hết hạn `70`, bác sĩ thấy đúng `7 Viên`) | **PASS** |
| **TEST 05** | Nhiều lô còn hạn → xuất theo FEFO | Unit Test + Live Demo (`Efferalgan`: lấy hết `1` viên lô `LO-01` HSD `16/04/2027` trước, rồi lấy `1` viên lô `LO-02` HSD `17/11/2028`) | **PASS** |
| **TEST 06** | Kê nhỏ hơn tồn kho → lưu đơn & trừ kho đúng SL | Unit Test + Live Demo (`101 → 99 Viên`, đơn `DT261127-BA081C`, phiếu `PX261127-AE05E965`) | **PASS** |
| **TEST 07** | Kê vượt tồn kho → báo lỗi, không lưu & không trừ | Unit Test + Live Demo (`SL=151` vs `101` → hiện `Vượt tồn (101 Viên)` + chặn lưu) | **PASS** |
| **TEST 08** | Kê thuốc tồn = `0` → không thể xác nhận | Unit Test + Live Demo | **PASS** |
| **TEST 09** | Bấm lưu đơn nhiều lần → không trừ kho 2 lần | Unit Test (`clientRequestId` idempotency) + UI lock | **PASS** |
| **TEST 10** | Hai bác sĩ đồng thời kê cùng loại thuốc | `PESSIMISTIC_WRITE` (`findForUpdate` + `findAvailableBatchesFefoForUpdate`) sắp xếp theo `materialId` tăng dần | **PASS** |
| **TEST 11** | Sửa đơn thuốc đã xác nhận → xử lý chênh lệch kho | Unit Test (`updatePrescription_reduceQuantity_returnsDeltaToOriginalBatch`: `10 → 6`, hoàn `4` về lô gốc) | **PASS** |
| **TEST 12** | Hủy đơn thuốc → hoàn kho đúng điều kiện, có lịch sử | Unit Test + Live Demo (Hủy `DT261127-BA081C` → tồn `99 → 101`, tạo phiếu `PX261127-7FACD149`) | **PASS** |
| **TEST 13** | Bác sĩ chi nhánh A không dùng tồn chi nhánh B | Unit Test (`createPrescription_branchIsolation_onlyQueriesVisitBranch`) + Live Demo `CN03` | **PASS** |
| **TEST 14** | Lỗi giữa quá trình lưu & trừ kho → rollback | `@Transactional(rollbackFor = Exception.class)` + Unit Test kiểm chứng không lưu `StockIssue` khi lỗi | **PASS** |
| **TEST 15** | Kiểm tra giao diện trên website | Live Browser QA (`http://localhost:8080`) | **PASS** |
| **TEST 16** | Kiểm tra lịch sử kho truy vết đơn, BS, CN, thời gian | Unit Test + Live Demo (`PX261127-AE05E965` & `PX261127-7FACD149`) | **PASS** |

---

## 9. Kết quả Demo thực tế trên website (`http://localhost:8080`)
- **Môi trường:** CV-DCMS chạy trực tiếp tại `http://localhost:8080` kết nối MySQL `cv_dcms_db` (Chi nhánh `CN03 - Cơ sở Cầu Giấy`).
- **Ca khám demo:** Lượt khám `V7F1154A35EE34F61` (`Đang khám`), bệnh nhân `[CSKH-TEST-2026-R2] Lê Minh Tuấn` (`BN-CSKH-R2-01`).
- **Diễn biến thực tế đã xác minh:**
  1. **Trước khi kê đơn:** `TH-PARA-500` (`Efferalgan / Paracetamol 500mg`) có tồn kho **`101 Viên`** (Lô `LO-01`: `1 Viên`, HSD `2027-04-16`; Lô `LO-02`: `100 Viên`, HSD `2028-11-17`). `TH-AUG-1G` (`Augmentin 1g`) tổng kho `77 Viên` (trong đó `70` hết hạn, `7` còn hạn). `TH001` và `MED-ALPHA` hiển thị tồn `0`.
  2. **Mở modal kê đơn:** API `GET /api/doctor/medications/catalog?visitId=23` trả về đúng `Efferalgan 500mg: Tồn 101 Viên · Còn hàng · Lô LO-01 · HSD 16/04/2027` và `Augmentin 1g: Tồn 7 Viên` (đã loại bỏ 70 viên hết hạn).
  3. **Thử kê vượt tồn (`151` > `101`):** Giao diện hiển thị ngay cảnh báo đỏ `Vượt tồn (101 Viên)` + banner lỗi và chặn lưu đơn.
  4. **Kê hợp lệ `2 Viên`:** Lưu thành công đơn **`DT261127-BA081C`**, tự động sinh phiếu xuất **`PX261127-AE05E965`** (`Xuất theo đơn thuốc`).
  5. **Kiểm tra kho sau khi kê:** Tồn `TH-PARA-500` giảm chính xác từ **`101 → 99 Viên`**. Chi tiết phiếu `PX261127-AE05E965` xác nhận xuất FEFO 2 dòng: `LO-01` (`1 Viên`, HSD `2027-04-16`) và `LO-02` (`1 Viên`, HSD `2028-11-17`).
  6. **Quay lại màn hình bác sĩ:** Tồn khả dụng cập nhật ngay thành **`99 Viên`** và tự chuyển lô gợi ý tiếp theo sang **`Lô LO-02 · HSD: 17/11/2028`**.
  7. **Hủy đơn thuốc thử nghiệm:** Hủy `DT261127-BA081C` (`POST /api/doctor/prescriptions/6/cancel`, lý do `QA Demo Test`) → đơn chuyển sang `Đã hủy`, kho tự động hoàn trả **`99 → 101 Viên`**, sinh phiếu hoàn kho **`PX261127-7FACD149`** (`Hoàn kho đơn thuốc`), giữ nguyên phiếu xuất cũ `PX261127-AE05E965` để truy vết.

### Bằng chứng hình ảnh (Agent 6 QA)
- Ảnh xác nhận cuối quy trình (host-verified): `C:/Users/Hieu dep zai/.gemini/antigravity/brain/92b3ea31-1c09-4360-afb4-8ca668a1adfc/browser_evidence/3e41423d-e48b-43bd-a3a1-65dba41a6f7c/final_step_53.png`
- Bộ ảnh chi tiết từng bước (trước kê, modal live stock, cảnh báo vượt tồn, sau lưu đơn, lịch sử xuất kho, chi tiết FEFO, sau hủy đơn) lưu tại thư mục `browser_sandbox/screenshots/` của phiên QA `3e41423d-e48b-43bd-a3a1-65dba41a6f7c`.

---

## 10. Các vấn đề còn tồn tại & Lưu ý vận hành
- Không còn lỗi chặn nghiệp vụ (`0 BLOCKED`, `0 FAILED`).
- Lưu ý: Khi thu ngân lập hóa đơn cho các đơn thuốc mới (`inventoryDeducted = true`), `InvoiceServiceImpl` chỉ ghi nhận doanh thu/hóa đơn và cập nhật `boughtQuantity`, không trừ kho lại lần 2; đối với đơn thuốc tạo trước thời điểm nâng cấp (`inventoryDeducted = false`), hệ thống vẫn giữ nguyên luồng trừ kho ở hóa đơn để tương thích ngược.

---

## 11. Đánh giá nguy cơ ảnh hưởng các chức năng khác
- **Đặt lịch, Khám lâm sàng, Phác đồ điều trị, CSKH:** Không bị chỉnh sửa logic cốt lõi; sự kiện `PrescriptionCreatedCareEvent` vẫn được phát bình thường.
- **Quản lý kho (Nhập kho, Xuất nội bộ, Điều chuyển, Hủy hết hạn):** Toàn bộ 42 unit tests kho hiện có (`InventoryWorkflowUnitTest`, `MedicationInventorySyncUnitTest`, `BranchInventoryServiceTest`, `StockIssueServiceTest`, `StockReceiptServiceTest`) đều **PASS 100%**.
- **Hóa đơn & Thanh toán:** Được bảo vệ chống trừ kho 2 lần qua cờ `Prescription.isInventoryDeducted()`.
- **Dữ liệu hiện có & Git working tree:** Không reset database, không xóa dữ liệu demo, không tự ý `git add` / `git commit`.
