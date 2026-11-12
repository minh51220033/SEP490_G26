-- =============================================================================
-- CLEAN SEED: QUẢN LÝ LỊCH HẸN 08/11/2026 - 08/12/2026
-- Phạm vi: lịch hẹn, tiếp đón, điều phối ghế, hàng chờ và số gọi tại sảnh.
-- Idempotent: chỉ xóa bản ghi có prefix APT26 / VIS_APT26 / BN_APT26.
-- =============================================================================
SET NAMES utf8mb4;
SET time_zone = '+07:00';

-- 1) Dọn đúng tập dữ liệu demo cũ theo thứ tự khóa ngoại.
DELETE FROM queue_tickets WHERE visit_id IN (SELECT visit_id FROM visits WHERE visit_code LIKE 'VIS_APT26_%');
DELETE FROM reception_checkin_keys WHERE visit_id IN (SELECT visit_id FROM visits WHERE visit_code LIKE 'VIS_APT26_%');
DELETE FROM visits WHERE visit_code LIKE 'VIS_APT26_%';
DELETE FROM appointment_histories WHERE appointment_id IN (SELECT appointment_id FROM appointments WHERE appointment_code LIKE 'APT26_%');
DELETE FROM appointments WHERE appointment_code LIKE 'APT26_%';
DELETE FROM patient_profiles WHERE patient_code LIKE 'BN_APT26_%';

-- 2) Hạ tầng demo tách biệt: 1 phòng, 3 ghế, 1 ca trực cả ngày và 2 bác sĩ.
INSERT INTO work_shifts (branch_id, shift_code, shift_name, start_time, end_time, is_active)
SELECT 1, 'SEED_APT26_ALL', 'Ca trực demo lịch hẹn', '00:00:00', '23:59:59', 1
WHERE NOT EXISTS (SELECT 1 FROM work_shifts WHERE branch_id = 1 AND shift_code = 'SEED_APT26_ALL');

INSERT INTO rooms (branch_id, room_code, room_name, room_type, status)
SELECT 1, 'SEED_APT26_R1', 'Phòng demo lịch hẹn', 'TREATMENT', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM rooms WHERE branch_id = 1 AND room_code = 'SEED_APT26_R1');

INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, room_id, 'SEED_APT26_C1', 'Ghế demo 01', 'AVAILABLE'
FROM rooms WHERE branch_id = 1 AND room_code = 'SEED_APT26_R1'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'SEED_APT26_C1');
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, room_id, 'SEED_APT26_C2', 'Ghế demo 02', 'AVAILABLE'
FROM rooms WHERE branch_id = 1 AND room_code = 'SEED_APT26_R1'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'SEED_APT26_C2');
INSERT INTO dental_chairs (branch_id, room_id, chair_code, chair_name, status)
SELECT 1, room_id, 'SEED_APT26_C3', 'Ghế demo 03', 'IN_USE'
FROM rooms WHERE branch_id = 1 AND room_code = 'SEED_APT26_R1'
AND NOT EXISTS (SELECT 1 FROM dental_chairs WHERE branch_id = 1 AND chair_code = 'SEED_APT26_C3');

CREATE TEMPORARY TABLE seed_appointment_dates (work_date DATE PRIMARY KEY);
INSERT INTO seed_appointment_dates VALUES
('2026-11-08'),('2026-11-09'),('2026-11-10'),('2026-11-12'),('2026-11-15'),('2026-11-18'),
('2026-11-22'),('2026-11-26'),('2026-11-30'),('2026-12-02'),('2026-12-05'),('2026-12-08');

INSERT INTO staff_schedules (staff_id, branch_id, shift_id, work_date, status, created_at)
SELECT doctor.staff_id, 1, shift_data.shift_id, days.work_date, 'SCHEDULED', NOW()
FROM (SELECT 1 AS staff_id UNION ALL SELECT 2) doctor
CROSS JOIN seed_appointment_dates days
JOIN work_shifts shift_data ON shift_data.branch_id = 1 AND shift_data.shift_code = 'SEED_APT26_ALL'
WHERE NOT EXISTS (
    SELECT 1 FROM staff_schedules schedules
    WHERE schedules.staff_id = doctor.staff_id AND schedules.branch_id = 1
      AND schedules.work_date = days.work_date AND schedules.status = 'SCHEDULED'
);

-- 3) Hồ sơ bệnh nhân: mỗi hồ sơ biểu diễn một hành trình/loại trạng thái rõ ràng.
INSERT INTO patient_profiles
    (patient_code, full_name, dob, gender, phone_number, address, preferred_branch_id,
     is_default, medical_history_completed, deposit_balance, has_allergies, allergy_details,
     has_underlying_disease, underlying_disease_details, status, created_at)
VALUES
('BN_APT26_01','Nguyễn Minh An','1990-03-14','Nam','0901100001','12 Nguyễn Huệ, Quận 1',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW()),
('BN_APT26_02','Trần Thu Hà','1987-07-22','Nữ','0901100002','18 Lê Lợi, Quận 1',1,0,1,0,1,'Dị ứng Penicillin',0,NULL,'ACTIVE',NOW()),
('BN_APT26_03','Lê Quốc Bảo','2001-11-02','Nam','0901100003','35 Pasteur, Quận 1',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW()),
('BN_APT26_04','Phạm Ngọc Linh','1996-04-18','Nữ','0901100004','62 Đồng Khởi, Quận 1',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW()),
('BN_APT26_05','Đỗ Anh Khoa','1982-09-09','Nam','0901100005','73 Hai Bà Trưng, Quận 1',1,0,1,0,0,NULL,1,'Tăng huyết áp đã kiểm soát','ACTIVE',NOW()),
('BN_APT26_06','Vũ Thanh Mai','1994-01-27','Nữ','0901100006','98 Nam Kỳ Khởi Nghĩa, Quận 1',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW()),
('BN_APT26_07','Hoàng Gia Huy','1978-12-06','Nam','0901100007','115 Võ Văn Tần, Quận 3',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW()),
('BN_APT26_08','Bùi Khánh Vy','2003-05-30','Nữ','0901100008','26 Cống Quỳnh, Quận 1',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW()),
('BN_APT26_09','Ngô Hoài Nam','1989-08-11','Nam','0901100009','45 Trần Hưng Đạo, Quận 1',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW()),
('BN_APT26_10','Dương Mỹ Duyên','1998-10-25','Nữ','0901100010','80 Bùi Viện, Quận 1',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW()),
('BN_APT26_11','Phan Đức Long','1985-02-17','Nam','0901100011','14 Nguyễn Trãi, Quận 1',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW()),
('BN_APT26_12','Lý Bảo Trâm','1992-06-08','Nữ','0901100012','101 Cách Mạng Tháng 8, Quận 1',1,0,1,0,0,NULL,0,NULL,'ACTIVE',NOW());

-- 4) Lịch hẹn phủ toàn bộ khoảng thời gian, có đủ lifecycle để kiểm thử danh sách/bộ lọc.
INSERT INTO appointments
    (appointment_code, profile_id, branch_id, doctor_id, appointment_date, preferred_session,
     expected_time, duration_minutes, initial_symptoms, source, status, reschedule_count,
     cancel_reason, cancelled_at, created_at)
SELECT seed.code, profile.profile_id, 1, seed.doctor_id, seed.appointment_date, seed.session,
       seed.expected_time, seed.duration_minutes, seed.symptoms, seed.source, seed.status, 0,
       seed.cancel_reason, CASE WHEN seed.status = 'CANCELLED' THEN '2026-11-07 16:00:00' ELSE NULL END, NOW()
FROM (
    SELECT 'APT26_1108_01' code,'BN_APT26_01' patient_code,1 doctor_id,'2026-11-08' appointment_date,'MORNING' session,'08:00:00' expected_time,30 duration_minutes,'Khám tổng quát và tư vấn vệ sinh răng miệng' symptoms,'RECEPTIONIST' source,'PENDING' status,NULL cancel_reason
    UNION ALL SELECT 'APT26_1108_02','BN_APT26_02',1,'2026-11-08','MORNING','08:30:00',30,'Cạo vôi răng, lưu ý dị ứng Penicillin','ONLINE','CONFIRMED',NULL
    UNION ALL SELECT 'APT26_1108_03','BN_APT26_03',2,'2026-11-08','MORNING','09:00:00',45,'Đau buốt răng 26 khi ăn nóng lạnh','PHONE','CHECKED_IN',NULL
    UNION ALL SELECT 'APT26_1108_04','BN_APT26_04',1,'2026-11-08','MORNING','09:30:00',30,'Tư vấn niềng răng trong suốt','ONLINE','CHECKED_IN',NULL
    UNION ALL SELECT 'APT26_1108_05','BN_APT26_05',2,'2026-11-08','MORNING','10:00:00',60,'Điều trị tủy răng 46, tiền sử tăng huyết áp','RECEPTIONIST','CHECKED_IN',NULL
    UNION ALL SELECT 'APT26_1109_01','BN_APT26_06',1,'2026-11-09','AFTERNOON','14:00:00',30,'Trám răng sâu mặt nhai','ONLINE','CONFIRMED',NULL
    UNION ALL SELECT 'APT26_1110_01','BN_APT26_07',2,'2026-11-10','MORNING','09:15:00',45,'Khám răng khôn đau tái phát','PHONE','NO_SHOW',NULL
    UNION ALL SELECT 'APT26_1112_01','BN_APT26_08',1,'2026-11-12','AFTERNOON','15:00:00',30,'Tái khám sau cạo vôi','RECEPTIONIST','PENDING',NULL
    UNION ALL SELECT 'APT26_1115_01','BN_APT26_09',2,'2026-11-15','MORNING','10:30:00',45,'Tư vấn bọc răng sứ thẩm mỹ','ONLINE','CONFIRMED',NULL
    UNION ALL SELECT 'APT26_1118_01','BN_APT26_10',1,'2026-11-18','AFTERNOON','13:30:00',30,'Khám nha khoa định kỳ','PHONE','CANCELLED','Bệnh nhân đổi lịch công tác'
    UNION ALL SELECT 'APT26_1122_01','BN_APT26_11',2,'2026-11-22','MORNING','08:45:00',60,'Nhổ răng 38 theo chỉ định','RECEPTIONIST','PENDING',NULL
    UNION ALL SELECT 'APT26_1126_01','BN_APT26_12',1,'2026-11-26','AFTERNOON','16:00:00',30,'Tẩy trắng răng tại phòng khám','ONLINE','CONFIRMED',NULL
    UNION ALL SELECT 'APT26_1130_01','BN_APT26_01',1,'2026-11-30','MORNING','09:00:00',30,'Tái khám vệ sinh răng miệng','RECEPTIONIST','PENDING',NULL
    UNION ALL SELECT 'APT26_1202_01','BN_APT26_02',2,'2026-12-02','AFTERNOON','14:30:00',30,'Tái khám sau trám răng','PHONE','CONFIRMED',NULL
    UNION ALL SELECT 'APT26_1205_01','BN_APT26_03',1,'2026-12-05','MORNING','08:30:00',45,'Kiểm tra khớp cắn','ONLINE','PENDING',NULL
    UNION ALL SELECT 'APT26_1208_01','BN_APT26_04',2,'2026-12-08','AFTERNOON','15:30:00',30,'Tư vấn chỉnh nha định kỳ','RECEPTIONIST','CONFIRMED',NULL
) seed
JOIN patient_profiles profile ON profile.patient_code = seed.patient_code;

-- 5) Lượt khám 08/11: lượt được gọi luôn có bác sĩ/ghế; tách riêng một lượt chờ điều phối.
INSERT INTO visits
    (visit_code, profile_id, branch_id, appointment_id, visit_type, check_in_at, initial_symptoms,
     primary_doctor_id, room_id, chair_id, status, created_at)
SELECT 'VIS_APT26_1108_03', profile.profile_id, 1, appointment.appointment_id, 'APPOINTMENT',
       '2026-11-08 08:58:00', 'Đau buốt răng 26 khi ăn nóng lạnh', 1, room.room_id, chair.chair_id, 'WAITING', NOW()
FROM appointments appointment JOIN patient_profiles profile ON profile.profile_id = appointment.profile_id
JOIN rooms room ON room.room_code = 'SEED_APT26_R1' JOIN dental_chairs chair ON chair.chair_code = 'SEED_APT26_C2'
WHERE appointment.appointment_code = 'APT26_1108_03';
INSERT INTO visits
    (visit_code, profile_id, branch_id, appointment_id, visit_type, check_in_at, initial_symptoms,
     primary_doctor_id, room_id, chair_id, status, created_at)
SELECT 'VIS_APT26_1108_04', profile.profile_id, 1, appointment.appointment_id, 'APPOINTMENT',
       '2026-11-08 09:27:00', 'Tư vấn niềng răng trong suốt', 1, room.room_id, chair.chair_id, 'WAITING', NOW()
FROM appointments appointment JOIN patient_profiles profile ON profile.profile_id = appointment.profile_id
JOIN rooms room ON room.room_code = 'SEED_APT26_R1' JOIN dental_chairs chair ON chair.chair_code = 'SEED_APT26_C1'
WHERE appointment.appointment_code = 'APT26_1108_04';
INSERT INTO visits
    (visit_code, profile_id, branch_id, appointment_id, visit_type, check_in_at, initial_symptoms,
     primary_doctor_id, room_id, chair_id, status, created_at)
SELECT 'VIS_APT26_1108_05', profile.profile_id, 1, appointment.appointment_id, 'APPOINTMENT',
       '2026-11-08 09:52:00', 'Điều trị tủy răng 46', 2, room.room_id, chair.chair_id, 'IN_EXAM', NOW()
FROM appointments appointment JOIN patient_profiles profile ON profile.profile_id = appointment.profile_id
JOIN rooms room ON room.room_code = 'SEED_APT26_R1' JOIN dental_chairs chair ON chair.chair_code = 'SEED_APT26_C3'
WHERE appointment.appointment_code = 'APT26_1108_05';

-- 6) Số khám thực tế: số thường 01/02, ưu tiên P-01; đủ WAITING/CALLED/IN_SERVICE/PAUSED/DONE.
INSERT INTO queue_tickets
    (visit_id, branch_id, queue_type, queue_date, ticket_number, is_priority, display_number,
     is_emergency, priority_score, priority_reason, pause_reason, status, issued_at, called_at, paused_at, completed_at)
SELECT visit_id,1,'EXAM','2026-11-08',1,0,'01',0,0,NULL,NULL,'WAITING','2026-11-08 09:28:00',NULL,NULL,NULL FROM visits WHERE visit_code='VIS_APT26_1108_04'
UNION ALL
SELECT visit_id,1,'EXAM','2026-11-08',1,1,'P-01',1,100,'Đau cấp tính cần ưu tiên',NULL,'CALLED','2026-11-08 09:05:00','2026-11-08 09:35:00',NULL,NULL FROM visits WHERE visit_code='VIS_APT26_1108_03'
UNION ALL
SELECT visit_id,1,'EXAM','2026-11-08',2,0,'02',0,0,NULL,NULL,'IN_SERVICE','2026-11-08 09:55:00','2026-11-08 10:05:00',NULL,NULL FROM visits WHERE visit_code='VIS_APT26_1108_05';

-- Two standalone walk-ins complete the paused/done states without changing appointment lifecycle.
INSERT INTO visits (visit_code,profile_id,branch_id,visit_type,check_in_at,initial_symptoms,primary_doctor_id,status,created_at)
SELECT 'VIS_APT26_1108_06',profile_id,1,'WALK_IN','2026-11-08 08:40:00','Bệnh nhân tạm rời khu chờ',1,'WAITING',NOW() FROM patient_profiles WHERE patient_code='BN_APT26_06'
UNION ALL
SELECT 'VIS_APT26_1108_07',profile_id,1,'WALK_IN','2026-11-08 08:10:00','Khám tổng quát đã hoàn tất',2,'COMPLETED',NOW() FROM patient_profiles WHERE patient_code='BN_APT26_07'
UNION ALL
SELECT 'VIS_APT26_1108_08',profile_id,1,'WALK_IN','2026-11-08 09:10:00','Chờ điều phối bác sĩ và ghế',NULL,'WAITING',NOW() FROM patient_profiles WHERE patient_code='BN_APT26_08';
INSERT INTO queue_tickets
    (visit_id,branch_id,queue_type,queue_date,ticket_number,is_priority,display_number,is_emergency,priority_score,pause_reason,status,issued_at,paused_at,completed_at)
SELECT visit_id,1,'EXAM','2026-11-08',3,0,'03',0,0,'Bệnh nhân tạm ra ngoài làm thủ tục','PAUSED','2026-11-08 08:42:00','2026-11-08 09:20:00',NULL FROM visits WHERE visit_code='VIS_APT26_1108_06'
UNION ALL
SELECT visit_id,1,'EXAM','2026-11-08',4,0,'04',0,0,NULL,'DONE','2026-11-08 08:15:00',NULL,'2026-11-08 08:55:00' FROM visits WHERE visit_code='VIS_APT26_1108_07';

DROP TEMPORARY TABLE seed_appointment_dates;
-- Kết quả mong đợi: 16 lịch hẹn trong khoảng, 6 lượt tiếp đón, 5 số khám 08/11 và 1 lượt chờ điều phối chưa gán.
