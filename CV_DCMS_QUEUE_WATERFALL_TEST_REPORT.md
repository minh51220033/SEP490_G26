# CV-DCMS Queue Waterfall — Test Report

## 1. Automated Integration & Unit Test Suites (H2 In-Memory Isolated)
Lệnh thực thi:
```powershell
.\mvnw.cmd test "-Dtest=WaterfallQueueStrictOrderingIntegrationTest,MultiServiceAnd4ChairMatrixIntegrationTest,QueueResourceMatrixAndTvIntegrationTest,DispatchDoctorPermissionIntegrationTest,FinalAppointmentCapacityAndResourceIntegrationTest,AppointmentSlotServiceTest,PublicBookingGuestSecurityIntegrationTest" "-DargLine=-javaagent:target/byte-buddy-agent.jar"
```

Kết quả tổng hợp: **48 / 48 PASS** (`Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`).

| Test Class | Tests run | Failures | Errors | Skipped | Status |
|---|---:|---:|---:|---:|---|
| `WaterfallQueueStrictOrderingIntegrationTest` | 8 | 0 | 0 | 0 | PASS |
| `MultiServiceAnd4ChairMatrixIntegrationTest` | 4 | 0 | 0 | 0 | PASS |
| `QueueResourceMatrixAndTvIntegrationTest` | 5 | 0 | 0 | 0 | PASS |
| `DispatchDoctorPermissionIntegrationTest` | 5 | 0 | 0 | 0 | PASS |
| `FinalAppointmentCapacityAndResourceIntegrationTest` | 7 | 0 | 0 | 0 | PASS |
| `AppointmentSlotServiceTest` | 5 | 0 | 0 | 0 | PASS |
| `PublicBookingGuestSecurityIntegrationTest` | 14 | 0 | 0 | 0 | PASS |

### Nội dung 8 test case bắt buộc trong `WaterfallQueueStrictOrderingIntegrationTest`
1. `test1`: Check-in vãng lai và lịch hẹn thường không gán sớm `primaryDoctor`.
2. `test2`: Waterfall ordering — bác sĩ nào rảnh trước cũng kéo phiếu đứng đầu hàng đợi trước (`05` trước `07`), không phụ thuộc bác sĩ dự kiến.
3. `test3`: Tôn trọng `doctorServicePermissions` — nếu bác sĩ đầu tiên rảnh không được phép làm dịch vụ của phiếu đầu hàng, phiếu đầu hàng vẫn giữ vị trí; bác sĩ đủ điều kiện rảnh tiếp theo sẽ kéo phiếu đầu hàng.
4. `test4`: Ngoại lệ đặt riêng bác sĩ (`isDoctorRequested = true`) — không tự ý chuyển sang bác sĩ khác khi bác sĩ được đặt riêng đang bận.
5. `test5`: Không để `Appointment.doctor` tự động gán theo slot bị nhầm thành đặt riêng bác sĩ (`isDoctorRequested = false`).
6. `test6`: Chuỗi `completeVisitAndAutoCallNext` / turnover gọi đúng bệnh nhân đứng đầu hàng đợi chung.
7. `test7`: `QueueWorkflowService.buildPublicQueueList` hiển thị `Chưa phân công` cho `WAITING` thường và `Đã đặt bác sĩ` (`BOOKED_DOCTOR`) cho `WAITING` đặt riêng bác sĩ.
8. `test8`: Điều phối theo thời gian thực khi nhiều bác sĩ rảnh ở các thời điểm khác nhau vẫn giữ nguyên thứ tự `waitingQueueComparator()`.

## 2. Live Browser Verification (`http://localhost:8089`, DB `cv_dcms_v118_verify`)
1. `http://localhost:8089/reception/queue/tv/1`:
   - HTTP 200 OK, 0 console/JS errors, 0 failed network requests.
   - Bảng TV hiển thị đúng 6 phiếu:
     - `P-01` (`Tran Thi Demo 2`): `BS Pham D` (`Đã đặt bác sĩ`) | `Đang gọi` | `Phong X-Quang 104` | `May / Ghe X-Quang 104`
     - `04` (`Benh nhan 6`): `BS Nguyen A` (`Đã được phân bổ`) | `Đang gọi` | `Phòng khám 101` | `Ghe 101-02`
     - `06` (`Benh nhan 8`): `BS Le C` (`Đã được phân bổ`) | `Đang gọi` | `Phòng khám 101` | `Ghe 101-01`
     - `03` (`Benh nhan 5`): `BS Tran B` (`Đã được phân bổ`) | `Đang khám` | `Phòng khám 102` | `Ghe 102-01`
     - `05` (`Benh nhan 7`): `Chưa phân công` (`Chưa phân công`) | `Đang chờ` | `Chưa phân công` | `Chưa phân công`
     - `07` (`Dinh Xuan Hieu`): `Chưa phân công` (`Chưa phân công`) | `Đang chờ` | `Chưa phân công` | `Chưa phân công`
2. `http://localhost:8089/reception/check-in?branchId=1`:
   - Đăng nhập `letan_demo` / `Demo@123`, tải trang HTTP 200 OK, 0 console/JS errors.
3. `http://localhost:8089/reception/dispatch?branchId=1`:
   - Tải trang HTTP 200 OK, 0 console/JS errors.
