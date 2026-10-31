-- =====================================================================================
--  CV-DCMS — HỆ THỐNG QUẢN LÝ CHUỖI PHÒNG KHÁM NHA KHOA (PHIÊN BẢN TINH GỌN CHUẨN HÓA)
--  Database: MySQL 8.0+  (InnoDB, utf8mb4)
--
--  CĂN CỨ THIẾT KẾ:
--    * Source Code Entities (JPA / Hibernate) tại com.cvdcms.entity:
--        Branch.java, User.java, Role.java, Token.java, PatientProfile.java,
--        NewsArticle.java, Comment.java, CommentLike.java, CommentBan.java
--        -> 100% giữ nguyên tên bảng, cột, khóa chính, kiểu dữ liệu, ràng buộc UNIQUE/FK.
--    * 27 Use Cases (UC-01 .. UC-27) & 45 Màn hình / Tính năng (FT01 .. FT45)
--    * Mẫu thiết kế quy trình lâm sàng UC-12 (giữ nguyên vẹn 9 bảng quy trình & thực thi)
--    * Tổng số: 83 bảng chuẩn hóa, 2 Views báo cáo/công nợ (R5), 5 Triggers kiểm soát (R4..R9).
-- =====================================================================================

CREATE DATABASE IF NOT EXISTS cv_dcms_db
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE cv_dcms_db;

SET NAMES utf8mb4;
SET time_zone = '+07:00';
SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================================================
-- 1. HỆ THỐNG & PHÂN QUYỀN (7 BẢNG) — KHỚP 100% CÁC ENTITY JAVA
-- =====================================================================================

-- [Entity: Branch.java] Chi nhánh phòng khám (FT40, UC-02)
CREATE TABLE branches (
  branch_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  branch_code         VARCHAR(30)  NOT NULL,
  branch_name         VARCHAR(150) NOT NULL,
  address             VARCHAR(255) NOT NULL,
  phone               VARCHAR(20)  NULL,
  email               VARCHAR(100) NULL,
  tax_code            VARCHAR(20)  NULL,
  manager_user_id     BIGINT       NULL,
  opening_time        TIME         NOT NULL DEFAULT '08:00:00',
  closing_time        TIME         NOT NULL DEFAULT '20:00:00',
  working_days        VARCHAR(20)  NOT NULL DEFAULT '2,3,4,5,6,7,CN',
  allowed_ip_range    VARCHAR(255) NULL COMMENT 'Dải IP / SSID wifi được phép chấm công',
  tv_config_json      JSON         NULL COMMENT 'Cấu hình hiển thị màn hình TV gọi số',
  is_active           TINYINT(1)   NOT NULL DEFAULT 1,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_branches_branch_code UNIQUE (branch_code)
) ENGINE=InnoDB COMMENT='Chi nhánh phòng khám';

-- [Entity: User.java] Tài khoản đăng nhập (FT01, UC-01)
CREATE TABLE users (
  user_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  username            VARCHAR(50)  NOT NULL,
  password            VARCHAR(255) NOT NULL COMMENT 'BCrypt hash password',
  email               VARCHAR(100) NOT NULL,
  phone_number        VARCHAR(20)  NOT NULL,
  branch_id           BIGINT       NULL COMMENT 'Chi nhánh trực thuộc chính (User.java)',
  user_type           ENUM('STAFF','PATIENT') NOT NULL DEFAULT 'PATIENT',
  status              VARCHAR(50)  NULL DEFAULT 'ACTIVE',
  is_active           TINYINT(1)   NOT NULL DEFAULT 1,
  created_date        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  last_login_at       DATETIME     NULL,
  CONSTRAINT uk_users_username     UNIQUE (username),
  CONSTRAINT uk_users_email        UNIQUE (email),
  CONSTRAINT uk_users_phone_number UNIQUE (phone_number),
  CONSTRAINT fk_users_branch       FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Tài khoản người dùng';

-- [Entity: Role.java] Vai trò người dùng
CREATE TABLE roles (
  role_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  role_name           VARCHAR(50)  NOT NULL,
  display_name        VARCHAR(100) NULL,
  description         VARCHAR(255) NULL,
  is_system           TINYINT(1)   NOT NULL DEFAULT 0,
  is_active           TINYINT(1)   NOT NULL DEFAULT 1,
  CONSTRAINT uk_roles_role_name UNIQUE (role_name)
) ENGINE=InnoDB COMMENT='Vai trò hệ thống';

-- [Entity: User.java @JoinTable user_roles] Phân vai trò cho tài khoản
CREATE TABLE user_roles (
  user_id             BIGINT NOT NULL,
  role_id             BIGINT NOT NULL,
  PRIMARY KEY (user_id, role_id),
  CONSTRAINT uk_user_roles UNIQUE (user_id, role_id),
  CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Danh mục quyền hạn chi tiết (FT01)
CREATE TABLE permissions (
  permission_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  permission_code     VARCHAR(100) NOT NULL,
  module              VARCHAR(50)  NOT NULL,
  description         VARCHAR(255) NULL,
  CONSTRAINT uk_permissions_code UNIQUE (permission_code)
) ENGINE=InnoDB;

-- Gán quyền cho vai trò
CREATE TABLE role_permissions (
  role_id             BIGINT NOT NULL,
  permission_id       BIGINT NOT NULL,
  PRIMARY KEY (role_id, permission_id),
  CONSTRAINT fk_rp_role       FOREIGN KEY (role_id)       REFERENCES roles(role_id) ON DELETE CASCADE,
  CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permissions(permission_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- [Entity: Token.java] Mã OTP xác thực & Token phiên
CREATE TABLE token (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  token               VARCHAR(255) NOT NULL COMMENT 'Mã OTP hoặc chuỗi xác thực',
  expiry_date         DATETIME     NOT NULL,
  user_id             BIGINT       NOT NULL,
  token_type          ENUM('OTP','REFRESH','RESET_PASSWORD') NOT NULL DEFAULT 'OTP',
  is_used             TINYINT(1)   NOT NULL DEFAULT 0,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_token_user (user_id),
  CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='Mã OTP và token xác thực tài khoản';

-- =====================================================================================
-- 2. NHÂN SỰ, LỊCH LÀM VIỆC & CHẤM CÔNG (6 BẢNG)
-- =====================================================================================

-- Hồ sơ chi tiết nhân sự (Bác sĩ, Phụ tá, Lễ tân, Thu ngân, Quản lý) (FT41, UC-03)
CREATE TABLE staff_profiles (
  staff_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id             BIGINT       NULL COMMENT 'Tài khoản đăng nhập tương ứng',
  staff_code          VARCHAR(20)  NOT NULL,
  full_name           VARCHAR(150) NOT NULL,
  gender              ENUM('MALE','FEMALE','OTHER') NULL,
  dob                 DATE         NULL,
  phone_number        VARCHAR(20)  NULL,
  email               VARCHAR(100) NULL,
  id_card_number      VARCHAR(20)  NULL COMMENT 'CCCD',
  department          VARCHAR(100) NULL COMMENT 'Bác sĩ, Điều dưỡng, Lễ tân, Kế toán...',
  position_title      VARCHAR(100) NULL,
  specialty           VARCHAR(150) NULL COMMENT 'Chuyên môn: Niềng răng, Implant, Răng thẩm mỹ...',
  practice_license_no VARCHAR(50)  NULL COMMENT 'Số chứng chỉ hành nghề Y khoa',
  moh_doctor_code     VARCHAR(50)  NULL COMMENT 'Mã bác sĩ liên thông Cổng BYT',
  base_salary         DECIMAL(15,2) NOT NULL DEFAULT 0,
  allowance_amount    DECIMAL(15,2) NOT NULL DEFAULT 0,
  insurance_salary    DECIMAL(15,2) NOT NULL DEFAULT 0,
  bank_account_number VARCHAR(30)  NULL,
  bank_name           VARCHAR(100) NULL,
  hire_date           DATE         NULL,
  status              ENUM('ACTIVE','ON_LEAVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_staff_code UNIQUE (staff_code),
  CONSTRAINT uk_staff_user UNIQUE (user_id),
  CONSTRAINT fk_staff_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Hồ sơ nhân viên & bác sĩ';

-- Phân bổ nhân sự làm việc tại nhiều chi nhánh (FT41, UC-03)
CREATE TABLE staff_branch_assignments (
  assignment_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  staff_id            BIGINT      NOT NULL,
  branch_id           BIGINT      NOT NULL,
  role_id             BIGINT      NOT NULL,
  is_primary          TINYINT(1)  NOT NULL DEFAULT 0,
  start_date          DATE        NOT NULL,
  end_date            DATE        NULL,
  status              ENUM('ACTIVE','ENDED') NOT NULL DEFAULT 'ACTIVE',
  created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_sba UNIQUE (staff_id, branch_id, role_id, start_date),
  CONSTRAINT fk_sba_staff  FOREIGN KEY (staff_id)  REFERENCES staff_profiles(staff_id) ON DELETE CASCADE,
  CONSTRAINT fk_sba_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_sba_role   FOREIGN KEY (role_id)   REFERENCES roles(role_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Phân công chi nhánh và vai trò nhân sự';

-- Ca làm việc (Sáng, Chiều, Tối)
CREATE TABLE work_shifts (
  shift_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  branch_id           BIGINT      NULL COMMENT 'NULL = ca áp dụng chung toàn chuỗi',
  shift_code          VARCHAR(20) NOT NULL,
  shift_name          VARCHAR(50) NOT NULL,
  start_time          TIME        NOT NULL,
  end_time            TIME        NOT NULL,
  is_active           TINYINT(1)  NOT NULL DEFAULT 1,
  CONSTRAINT uk_shift UNIQUE (branch_id, shift_code),
  CONSTRAINT fk_shift_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Lịch phân ca làm việc tuần/tháng (FT33, UC-06)
CREATE TABLE staff_schedules (
  schedule_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  staff_id            BIGINT      NOT NULL,
  branch_id           BIGINT      NOT NULL,
  shift_id            BIGINT      NOT NULL,
  work_date           DATE        NOT NULL,
  status              ENUM('SCHEDULED','CANCELLED','ON_LEAVE') NOT NULL DEFAULT 'SCHEDULED',
  note                VARCHAR(255) NULL,
  created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_staff_schedule UNIQUE (staff_id, work_date, shift_id),
  KEY idx_schedule_branch_date (branch_id, work_date),
  CONSTRAINT fk_sch_staff  FOREIGN KEY (staff_id)  REFERENCES staff_profiles(staff_id) ON DELETE CASCADE,
  CONSTRAINT fk_sch_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_sch_shift  FOREIGN KEY (shift_id)  REFERENCES work_shifts(shift_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Đơn nghỉ phép / khóa lịch của bác sĩ & nhân sự (FT42, UC-06, R8: gửi trước >= 3 ngày, hỗ trợ nghỉ theo ca và ngoại lệ đột xuất)
CREATE TABLE leave_requests (
  leave_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  staff_id            BIGINT       NOT NULL,
  branch_id           BIGINT       NOT NULL,
  shift_id            BIGINT       NULL COMMENT 'NULL = Nghỉ cả ngày, có giá trị = Khóa lịch theo ca cụ thể (FT42)',
  leave_type          ENUM('ANNUAL','SICK','UNPAID','PERSONAL') NOT NULL DEFAULT 'ANNUAL',
  is_emergency        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1 = Nghỉ đột xuất có xác nhận riêng (FT42)',
  start_date          DATE         NOT NULL,
  end_date            DATE         NOT NULL,
  reason              VARCHAR(500) NOT NULL,
  status              ENUM('PENDING','APPROVED','REJECTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  submitted_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_by         BIGINT       NULL,
  reviewed_at         DATETIME     NULL,
  review_note         VARCHAR(500) NULL,
  CONSTRAINT fk_lr_staff    FOREIGN KEY (staff_id)    REFERENCES staff_profiles(staff_id) ON DELETE CASCADE,
  CONSTRAINT fk_lr_branch   FOREIGN KEY (branch_id)   REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_lr_shift    FOREIGN KEY (shift_id)    REFERENCES work_shifts(shift_id) ON DELETE SET NULL,
  CONSTRAINT fk_lr_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Yêu cầu nghỉ phép / Khóa lịch bác sĩ';

-- Dữ liệu chấm công nhân sự (FT33, UC-21: kiểm tra IP/Wifi chi nhánh)
CREATE TABLE attendance_records (
  attendance_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  staff_id            BIGINT       NOT NULL,
  branch_id           BIGINT       NOT NULL,
  work_date           DATE         NOT NULL,
  check_in_at         DATETIME     NULL,
  check_in_ip         VARCHAR(45)  NULL,
  check_out_at        DATETIME     NULL,
  check_out_ip        VARCHAR(45)  NULL,
  is_valid_network    TINYINT(1)   NOT NULL DEFAULT 0,
  worked_minutes      INT          NULL,
  status              ENUM('ON_TIME','LATE','EARLY_LEAVE','ABSENT','ON_LEAVE') NOT NULL DEFAULT 'ON_TIME',
  notes               VARCHAR(255) NULL,
  CONSTRAINT uk_attendance UNIQUE (staff_id, work_date),
  CONSTRAINT fk_att_staff  FOREIGN KEY (staff_id)  REFERENCES staff_profiles(staff_id) ON DELETE CASCADE,
  CONSTRAINT fk_att_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- =====================================================================================
-- 3. HỒ SƠ BỆNH NHÂN & TÀI LIỆU (3 BẢNG) — KHỚP 100% PatientProfile.java
-- =====================================================================================

-- Cấu hình hạng thành viên (FT19, UC-14)
CREATE TABLE membership_tiers (
  tier_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  tier_code           VARCHAR(20)   NOT NULL,
  tier_name           VARCHAR(50)   NOT NULL,
  min_total_spent     DECIMAL(15,2) NOT NULL DEFAULT 0,
  discount_percent    DECIMAL(5,2)  NOT NULL DEFAULT 0,
  spend_per_point     DECIMAL(15,2) NOT NULL DEFAULT 10000.00,
  point_value         DECIMAL(15,2) NOT NULL DEFAULT 1000.00,
  display_order       INT           NOT NULL DEFAULT 0,
  is_active           TINYINT(1)    NOT NULL DEFAULT 1,
  CONSTRAINT uk_tier_code UNIQUE (tier_code)
) ENGINE=InnoDB;

-- [Entity: PatientProfile.java] Hồ sơ bệnh nhân (FT03, FT04, FT35, FT36, UC-04, UC-09)
CREATE TABLE patient_profiles (
  profile_id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id                    BIGINT        NOT NULL COMMENT 'Tài khoản chủ sở hữu hiện tại',
  previous_user_id           BIGINT        NULL COMMENT 'Tài khoản quản lý trước khi tách tài khoản riêng (FT36)',
  separated_at               DATETIME      NULL COMMENT 'Thời điểm tách sang tài khoản riêng (SCR-36.3)',
  patient_code               VARCHAR(20)   NOT NULL COMMENT 'Mã BN: BN000123',
  full_name                  VARCHAR(100)  NOT NULL,
  dob                        DATE          NULL,
  gender                     VARCHAR(20)   NOT NULL,
  phone_number               VARCHAR(20)   NOT NULL COMMENT 'R1: Không UNIQUE (cha mẹ dùng cho con)',
  address                    VARCHAR(255)  NOT NULL,
  avatar_url                 VARCHAR(500)  NULL,
  id_card_number             VARCHAR(20)   NULL COMMENT 'CCCD',
  health_insurance_number    VARCHAR(20)   NULL COMMENT 'Số thẻ BHYT',
  guardian_name              VARCHAR(100)  NULL,
  guardian_phone             VARCHAR(20)   NULL,
  guardian_relationship      VARCHAR(50)   NULL,
  -- Tiền sử y tế (Khớp PatientProfile.java)
  has_allergies              TINYINT(1)    NULL,
  allergy_details            VARCHAR(1000) NULL,
  has_cardiovascular_disease TINYINT(1)    NULL,
  cardiovascular_details     VARCHAR(1000) NULL,
  has_underlying_disease     TINYINT(1)    NULL,
  underlying_disease_details VARCHAR(1000) NULL,
  medical_history_completed  TINYINT(1)    NOT NULL DEFAULT 0,
  preferred_branch_id        BIGINT        NOT NULL,
  is_default                 TINYINT(1)    NOT NULL DEFAULT 0,
  membership_tier_id         BIGINT        NULL,
  deposit_balance            DECIMAL(15,2) NOT NULL DEFAULT 0 COMMENT 'Số dư tiền gửi tạm ứng',
  customer_source            VARCHAR(100)  NULL COMMENT 'Facebook, Google, Người quen...',
  status                     VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
  created_at                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at                 DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_patient_code  UNIQUE (patient_code),
  KEY idx_patient_phone (phone_number),
  KEY idx_patient_user (user_id),
  CONSTRAINT fk_pp_user      FOREIGN KEY (user_id)             REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_pp_prev_user FOREIGN KEY (previous_user_id)    REFERENCES users(user_id) ON DELETE SET NULL,
  CONSTRAINT fk_pp_branch    FOREIGN KEY (preferred_branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pp_tier      FOREIGN KEY (membership_tier_id)  REFERENCES membership_tiers(tier_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Hồ sơ bệnh nhân';

-- Tài liệu scan bệnh nhân & Bản cam kết điều trị (FT03, FT17)
CREATE TABLE patient_documents (
  document_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id          BIGINT       NOT NULL,
  visit_id            BIGINT       NULL,
  document_type       ENUM('ID_CARD','INSURANCE_CARD','CONSENT_FORM','TREATMENT_CONTRACT','OTHER') NOT NULL,
  document_name       VARCHAR(200) NOT NULL,
  file_url            VARCHAR(500) NOT NULL,
  file_type           VARCHAR(20)  NOT NULL DEFAULT 'PDF',
  notes               VARCHAR(500) NULL,
  uploaded_by         BIGINT       NULL,
  uploaded_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pdoc_profile (profile_id),
  CONSTRAINT fk_pdoc_profile  FOREIGN KEY (profile_id)  REFERENCES patient_profiles(profile_id) ON DELETE CASCADE,
  CONSTRAINT fk_pdoc_uploader FOREIGN KEY (uploaded_by) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Hồ sơ tài liệu đính kèm & Giấy cam kết scan';

-- =====================================================================================
-- 4. DANH MỤC DỊCH VỤ & BỆNH ICD-10 (3 BẢNG)
-- =====================================================================================

CREATE TABLE service_categories (
  category_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  category_code       VARCHAR(30)  NOT NULL,
  category_name       VARCHAR(150) NOT NULL,
  category_type       ENUM('PROCEDURE','LAB','XRAY','CONSULTATION') NOT NULL DEFAULT 'PROCEDURE',
  display_order       INT          NOT NULL DEFAULT 0,
  is_active           TINYINT(1)   NOT NULL DEFAULT 1,
  CONSTRAINT uk_svc_cat_code UNIQUE (category_code)
) ENGINE=InnoDB;

-- Danh mục dịch vụ, thủ thuật & cận lâm sàng (FT02 / SCR-02.3, UC-11)
CREATE TABLE services (
  service_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  category_id            BIGINT        NOT NULL,
  service_code           VARCHAR(30)   NOT NULL,
  service_name           VARCHAR(200)  NOT NULL,
  service_type           ENUM('PROCEDURE','LAB','XRAY','CONSULTATION') NOT NULL DEFAULT 'PROCEDURE',
  unit                   VARCHAR(30)   NOT NULL DEFAULT 'Lần',
  base_price             DECIMAL(15,2) NOT NULL DEFAULT 0,
  surcharge_amount       DECIMAL(15,2) NOT NULL DEFAULT 0,
  requires_tooth         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'Bắt buộc chọn vị trí răng (FDI)',
  is_paraclinical        TINYINT(1)    NOT NULL DEFAULT 0 COMMENT 'Dịch vụ chụp chiếu X-quang/XN',
  result_template_json   JSON          NULL COMMENT 'Cấu hình thông số kết quả chuẩn cho XN/X-quang (SCR-02.3)',
  technical_requirements TEXT          NULL COMMENT 'Yêu cầu thực hiện kỹ thuật làm cơ sở thiết lập quy trình khám (UC-11)',
  is_bookable_online     TINYINT(1)    NOT NULL DEFAULT 0,
  warranty_months        INT           NULL,
  description            TEXT          NULL,
  status                 ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_service_code UNIQUE (service_code),
  CONSTRAINT fk_svc_category FOREIGN KEY (category_id) REFERENCES service_categories(category_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Danh mục thủ thuật, xét nghiệm, X-quang';

CREATE TABLE icd10_diseases (
  icd10_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  icd10_code          VARCHAR(20)  NOT NULL,
  disease_name_vi     VARCHAR(255) NOT NULL,
  disease_name_en     VARCHAR(255) NULL,
  is_dental_common    TINYINT(1)   NOT NULL DEFAULT 0,
  is_active           TINYINT(1)   NOT NULL DEFAULT 1,
  CONSTRAINT uk_icd10_code UNIQUE (icd10_code)
) ENGINE=InnoDB;

-- =====================================================================================
-- 5. MẪU QUY TRÌNH LÂM SÀNG (UC-12 / FT34) & THỰC THI (UC-13 / FT43) (9 BẢNG)
-- =====================================================================================

CREATE TABLE clinical_workflow_templates (
  template_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_code       VARCHAR(30)  NOT NULL,
  template_name       VARCHAR(200) NOT NULL,
  description         TEXT         NULL,
  service_id          BIGINT       NULL,
  clinic_id           BIGINT       NULL COMMENT 'NULL = áp dụng toàn chuỗi',
  status              ENUM('DRAFT','ACTIVE','INACTIVE') NOT NULL DEFAULT 'DRAFT',
  created_by          BIGINT       NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_cwt_code    UNIQUE (template_code),
  CONSTRAINT fk_cwt_service FOREIGN KEY (service_id) REFERENCES services(service_id) ON DELETE SET NULL,
  CONSTRAINT fk_cwt_branch  FOREIGN KEY (clinic_id)  REFERENCES branches(branch_id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE clinical_workflow_versions (
  version_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_id         BIGINT       NOT NULL,
  version_number      INT          NOT NULL,
  status              ENUM('DRAFT','PUBLISHED','RETIRED') NOT NULL DEFAULT 'DRAFT',
  flow_mode           ENUM('CONTINUOUS','INTERRUPTIBLE') NOT NULL DEFAULT 'CONTINUOUS',
  effective_from      DATETIME     NULL,
  effective_to        DATETIME     NULL,
  change_summary      VARCHAR(500) NULL,
  created_by          BIGINT       NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  approved_by         BIGINT       NULL,
  published_at        DATETIME     NULL,
  CONSTRAINT uk_cwv UNIQUE (template_id, version_number),
  CONSTRAINT fk_cwv_template FOREIGN KEY (template_id) REFERENCES clinical_workflow_templates(template_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE clinical_workflow_sections (
  section_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  version_id          BIGINT       NOT NULL,
  section_code        ENUM('I','II','III','IV','V','VI') NOT NULL,
  section_title       VARCHAR(200) NOT NULL,
  content             LONGTEXT     NULL,
  display_order       INT          NOT NULL DEFAULT 0,
  CONSTRAINT uk_cws UNIQUE (version_id, section_code),
  CONSTRAINT fk_cws_version FOREIGN KEY (version_id) REFERENCES clinical_workflow_versions(version_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE clinical_workflow_steps (
  step_id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  version_id                 BIGINT       NOT NULL,
  parent_step_id             BIGINT       NULL,
  step_code                  VARCHAR(30)  NOT NULL,
  step_name                  VARCHAR(200) NOT NULL,
  step_description           TEXT         NULL,
  step_type                  ENUM('CLINICAL','PARACLINICAL','TREATMENT','FOLLOW_UP','DECISION') NOT NULL DEFAULT 'CLINICAL',
  sequence_order             INT          NOT NULL,
  is_optional                TINYINT(1)   NOT NULL DEFAULT 0,
  completion_criteria        TEXT         NULL,
  requires_clinical_approval TINYINT(1)   NOT NULL DEFAULT 0,
  estimated_duration_minutes INT          NULL,
  target_queue_type          ENUM('RECEPTION','EXAM','PARACLINICAL','RESULT_READING','TREATMENT','PAYMENT') NULL,
  queue_priority             INT          NOT NULL DEFAULT 0,
  status                     ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uk_cwst_code   UNIQUE (version_id, step_code),
  CONSTRAINT fk_cwst_ver    FOREIGN KEY (version_id)     REFERENCES clinical_workflow_versions(version_id) ON DELETE CASCADE,
  CONSTRAINT fk_cwst_parent FOREIGN KEY (parent_step_id) REFERENCES clinical_workflow_steps(step_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE clinical_workflow_step_roles (
  step_role_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  step_id             BIGINT     NOT NULL,
  role_id             BIGINT     NOT NULL,
  is_primary          TINYINT(1) NOT NULL DEFAULT 0,
  CONSTRAINT uk_cwsr UNIQUE (step_id, role_id),
  CONSTRAINT fk_cwsr_step FOREIGN KEY (step_id) REFERENCES clinical_workflow_steps(step_id) ON DELETE CASCADE,
  CONSTRAINT fk_cwsr_role FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE clinical_workflow_step_paraclinical (
  step_paraclinical_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  step_id              BIGINT     NOT NULL,
  service_id           BIGINT     NOT NULL,
  is_required          TINYINT(1) NOT NULL DEFAULT 1,
  result_required      TINYINT(1) NOT NULL DEFAULT 1,
  CONSTRAINT uk_cwsp UNIQUE (step_id, service_id),
  CONSTRAINT fk_cwsp_step FOREIGN KEY (step_id)    REFERENCES clinical_workflow_steps(step_id) ON DELETE CASCADE,
  CONSTRAINT fk_cwsp_svc  FOREIGN KEY (service_id) REFERENCES services(service_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE clinical_workflow_transition_rules (
  rule_id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  version_id           BIGINT     NOT NULL,
  from_step_id         BIGINT     NOT NULL,
  to_step_id           BIGINT     NULL,
  condition_expression JSON       NULL,
  priority_order       INT        NOT NULL DEFAULT 0,
  is_default           TINYINT(1) NOT NULL DEFAULT 0,
  CONSTRAINT fk_cwtr_ver  FOREIGN KEY (version_id)   REFERENCES clinical_workflow_versions(version_id) ON DELETE CASCADE,
  CONSTRAINT fk_cwtr_from FOREIGN KEY (from_step_id) REFERENCES clinical_workflow_steps(step_id) ON DELETE CASCADE,
  CONSTRAINT fk_cwtr_to   FOREIGN KEY (to_step_id)   REFERENCES clinical_workflow_steps(step_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Thực thi quy trình lâm sàng theo ca khám (UC-13, FT43)
CREATE TABLE workflow_executions (
  execution_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  version_id          BIGINT      NOT NULL,
  visit_id            BIGINT      NOT NULL,
  profile_id          BIGINT      NOT NULL,
  branch_id           BIGINT      NOT NULL,
  current_step_id     BIGINT      NULL,
  status              ENUM('IN_PROGRESS','PAUSED','COMPLETED','CANCELLED') NOT NULL DEFAULT 'IN_PROGRESS',
  started_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at        DATETIME    NULL,
  CONSTRAINT fk_we_ver     FOREIGN KEY (version_id)      REFERENCES clinical_workflow_versions(version_id) ON DELETE RESTRICT,
  CONSTRAINT fk_we_visit   FOREIGN KEY (visit_id)        REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_we_profile FOREIGN KEY (profile_id)      REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_we_branch  FOREIGN KEY (branch_id)       REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_we_step    FOREIGN KEY (current_step_id) REFERENCES clinical_workflow_steps(step_id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE workflow_step_executions (
  step_execution_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
  execution_id        BIGINT      NOT NULL,
  step_id             BIGINT      NOT NULL,
  status              ENUM('PENDING','IN_PROGRESS','PAUSED','SKIPPED','COMPLETED') NOT NULL DEFAULT 'PENDING',
  assigned_staff_id   BIGINT      NULL,
  started_at          DATETIME    NULL,
  completed_at        DATETIME    NULL,
  result_note         TEXT        NULL,
  result_data         JSON        NULL,
  approved_by         BIGINT      NULL,
  approved_at         DATETIME    NULL,
  CONSTRAINT uk_wse UNIQUE (execution_id, step_id),
  CONSTRAINT fk_wse_exec  FOREIGN KEY (execution_id)      REFERENCES workflow_executions(execution_id) ON DELETE CASCADE,
  CONSTRAINT fk_wse_step  FOREIGN KEY (step_id)           REFERENCES clinical_workflow_steps(step_id) ON DELETE RESTRICT,
  CONSTRAINT fk_wse_staff FOREIGN KEY (assigned_staff_id) REFERENCES staff_profiles(staff_id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- =====================================================================================
-- 6. LỊCH HẸN, TIẾP ĐÓN, PHÒNG KHÁM & HÀNG ĐỢI (5 BẢNG)
-- =====================================================================================

-- Lịch hẹn khám (FT05, FT06, UC-05)
CREATE TABLE appointments (
  appointment_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  appointment_code    VARCHAR(20)  NOT NULL,
  profile_id          BIGINT       NOT NULL,
  branch_id           BIGINT       NOT NULL,
  doctor_id           BIGINT       NULL,
  service_id          BIGINT       NULL,
  appointment_date    DATE         NOT NULL COMMENT 'Đặt lịch theo ngày',
  preferred_session   ENUM('ANY','MORNING','AFTERNOON','EVENING') NOT NULL DEFAULT 'ANY',
  expected_time       TIME         NULL,
  initial_symptoms    VARCHAR(500) NULL,
  source              ENUM('ONLINE','RECEPTIONIST','PHONE','CHATBOT') NOT NULL DEFAULT 'ONLINE',
  status              ENUM('PENDING','CONFIRMED','CHECKED_IN','COMPLETED','CANCELLED','NO_SHOW') NOT NULL DEFAULT 'PENDING',
  reschedule_count    INT          NOT NULL DEFAULT 0,
  cancel_reason       VARCHAR(255) NULL,
  cancelled_by        BIGINT       NULL,
  cancelled_at        DATETIME     NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_appt_code   UNIQUE (appointment_code),
  KEY idx_appt_branch_date (branch_id, appointment_date, status),
  CONSTRAINT fk_appt_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_appt_branch  FOREIGN KEY (branch_id)  REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_appt_doctor  FOREIGN KEY (doctor_id)  REFERENCES staff_profiles(staff_id) ON DELETE SET NULL,
  CONSTRAINT fk_appt_service FOREIGN KEY (service_id) REFERENCES services(service_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Lịch hẹn khám';

CREATE TABLE rooms (
  room_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  branch_id           BIGINT       NOT NULL,
  room_code           VARCHAR(20)  NOT NULL,
  room_name           VARCHAR(100) NOT NULL,
  room_type           ENUM('EXAM','TREATMENT','XRAY','LAB','OTHER') NOT NULL DEFAULT 'TREATMENT',
  status              ENUM('ACTIVE','MAINTENANCE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uk_room UNIQUE (branch_id, room_code),
  CONSTRAINT fk_room_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE dental_chairs (
  chair_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  branch_id           BIGINT      NOT NULL,
  room_id             BIGINT      NULL,
  chair_code          VARCHAR(20) NOT NULL,
  chair_name          VARCHAR(50) NOT NULL,
  status              ENUM('AVAILABLE','IN_USE','CLEANING','MAINTENANCE') NOT NULL DEFAULT 'AVAILABLE',
  CONSTRAINT uk_chair UNIQUE (branch_id, chair_code),
  CONSTRAINT fk_chair_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_chair_room   FOREIGN KEY (room_id)   REFERENCES rooms(room_id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- Lượt khám bệnh nhân (FT07, FT08, UC-07)
CREATE TABLE visits (
  visit_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  visit_code          VARCHAR(20)  NOT NULL,
  profile_id          BIGINT       NOT NULL,
  branch_id           BIGINT       NOT NULL,
  appointment_id      BIGINT       NULL,
  visit_type          ENUM('WALK_IN','APPOINTMENT','EMERGENCY') NOT NULL DEFAULT 'WALK_IN',
  check_in_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  initial_symptoms    VARCHAR(500) NULL,
  primary_doctor_id   BIGINT       NULL,
  room_id             BIGINT       NULL,
  chair_id            BIGINT       NULL,
  status              ENUM('WAITING','IN_EXAM','WAITING_PARACLINICAL','WAITING_RESULT','IN_TREATMENT','WAITING_PAYMENT','COMPLETED','CANCELLED') NOT NULL DEFAULT 'WAITING',
  completed_at        DATETIME     NULL,
  notes               TEXT         NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_visit_code   UNIQUE (visit_code),
  KEY idx_visit_branch_date (branch_id, check_in_at),
  CONSTRAINT fk_visit_profile FOREIGN KEY (profile_id)        REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_visit_branch  FOREIGN KEY (branch_id)         REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_visit_appt    FOREIGN KEY (appointment_id)    REFERENCES appointments(appointment_id) ON DELETE SET NULL,
  CONSTRAINT fk_visit_doctor  FOREIGN KEY (primary_doctor_id) REFERENCES staff_profiles(staff_id) ON DELETE SET NULL,
  CONSTRAINT fk_visit_room    FOREIGN KEY (room_id)           REFERENCES rooms(room_id) ON DELETE SET NULL,
  CONSTRAINT fk_visit_chair   FOREIGN KEY (chair_id)          REFERENCES dental_chairs(chair_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Lượt khám bệnh nhân';

-- Phiếu xếp hàng phòng khám (FT09, FT10, UC-07)
CREATE TABLE queue_tickets (
  ticket_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  visit_id            BIGINT      NOT NULL,
  branch_id           BIGINT      NOT NULL,
  queue_type          ENUM('RECEPTION','EXAM','PARACLINICAL','RESULT_READING','TREATMENT','PAYMENT') NOT NULL DEFAULT 'EXAM',
  queue_date          DATE        NOT NULL,
  ticket_number       INT         NOT NULL,
  is_priority         TINYINT(1)  NOT NULL DEFAULT 0,
  display_number      VARCHAR(10) NOT NULL COMMENT '05 | P-01',
  is_emergency        TINYINT(1)  NOT NULL DEFAULT 0,
  priority_score      INT         NOT NULL DEFAULT 0,
  status              ENUM('WAITING','CALLED','IN_SERVICE','PAUSED','DONE','CANCELLED') NOT NULL DEFAULT 'WAITING',
  issued_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  called_at           DATETIME    NULL,
  completed_at        DATETIME    NULL,
  CONSTRAINT uk_queue_no UNIQUE (branch_id, queue_date, queue_type, is_priority, ticket_number),
  KEY idx_queue_board (branch_id, queue_date, queue_type, status, priority_score),
  CONSTRAINT fk_qt_visit  FOREIGN KEY (visit_id)  REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_qt_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Phiếu xếp hàng phòng khám';

-- =====================================================================================
-- 7. KHÁM LÂM SÀNG, CHẨN ĐOÁN, CẬN LÂM SÀNG, NANOPIX & LAB (7 BẢNG)
-- =====================================================================================

-- Phiếu khám lâm sàng & Sơ đồ răng (FT11, UC-08)
CREATE TABLE clinical_examinations (
  exam_id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  visit_id              BIGINT       NOT NULL,
  profile_id            BIGINT       NOT NULL,
  doctor_id             BIGINT       NOT NULL,
  chief_complaint       VARCHAR(500) NOT NULL,
  history_illness       TEXT         NULL,
  vital_signs_json      JSON         NULL COMMENT 'Huyết áp, nhịp tim...',
  extraoral_exam        TEXT         NULL COMMENT 'Khám ngoài mặt',
  intraoral_exam        TEXT         NULL COMMENT 'Khám trong miệng, nướu',
  periodontal_status    TEXT         NULL COMMENT 'Tình trạng nha chu',
  odontogram_chart_json JSON         NULL COMMENT 'Sơ đồ hình ảnh 32 răng (Odontogram)',
  summary_assessment    TEXT         NULL,
  exam_time             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_exam_visit UNIQUE (visit_id),
  CONSTRAINT fk_ce_visit   FOREIGN KEY (visit_id)   REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_ce_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_ce_doctor  FOREIGN KEY (doctor_id)  REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Phiếu khám lâm sàng & Sơ đồ răng';

-- Chẩn đoán mã bệnh theo lượt khám (FT14, UC-08)
CREATE TABLE examination_diagnoses (
  diagnosis_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  exam_id             BIGINT       NOT NULL,
  visit_id            BIGINT       NOT NULL,
  icd10_code          VARCHAR(20)  NULL,
  diagnosis_text      VARCHAR(255) NOT NULL,
  diagnosis_type      ENUM('MAIN','SECONDARY','DIFFERENTIAL') NOT NULL DEFAULT 'MAIN',
  tooth_numbers       VARCHAR(100) NULL COMMENT 'Vị trí răng theo FDI: 11, 21, 36...',
  notes               VARCHAR(255) NULL,
  CONSTRAINT fk_ed_exam  FOREIGN KEY (exam_id)    REFERENCES clinical_examinations(exam_id) ON DELETE CASCADE,
  CONSTRAINT fk_ed_visit FOREIGN KEY (visit_id)   REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_ed_icd10 FOREIGN KEY (icd10_code) REFERENCES icd10_diseases(icd10_code) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Chẩn đoán mã bệnh theo lượt khám';

-- Phiếu chỉ định cận lâm sàng (FT11, UC-10)
CREATE TABLE paraclinical_orders (
  order_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_code          VARCHAR(20)  NOT NULL,
  visit_id            BIGINT       NOT NULL,
  profile_id          BIGINT       NOT NULL,
  doctor_id           BIGINT       NOT NULL,
  status              ENUM('ORDERED','IN_PROGRESS','COMPLETED','CANCELLED') NOT NULL DEFAULT 'ORDERED',
  order_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at        DATETIME     NULL,
  CONSTRAINT uk_pco_code UNIQUE (order_code),
  CONSTRAINT fk_pco_visit   FOREIGN KEY (visit_id)   REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_pco_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pco_doctor  FOREIGN KEY (doctor_id)  REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Phiếu chỉ định cận lâm sàng';

CREATE TABLE paraclinical_order_items (
  order_item_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id            BIGINT       NOT NULL,
  service_id          BIGINT       NOT NULL,
  tooth_numbers       VARCHAR(50)  NULL,
  status              ENUM('WAITING','IN_PROGRESS','COMPLETED','CANCELLED') NOT NULL DEFAULT 'WAITING',
  technician_id       BIGINT       NULL,
  performed_at        DATETIME     NULL,
  result_summary      TEXT         NULL,
  results_json        JSON         NULL COMMENT 'Giá trị thông số chi tiết của xét nghiệm/X-quang',
  external_ref_id     VARCHAR(100) NULL COMMENT 'Mã NanoPix UID / Mã barcode Lab',
  CONSTRAINT fk_poi_order FOREIGN KEY (order_id)       REFERENCES paraclinical_orders(order_id) ON DELETE CASCADE,
  CONSTRAINT fk_poi_svc   FOREIGN KEY (service_id)     REFERENCES services(service_id) ON DELETE RESTRICT,
  CONSTRAINT fk_poi_tech  FOREIGN KEY (technician_id)  REFERENCES staff_profiles(staff_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Chi tiết từng dịch vụ cận lâm sàng';

CREATE TABLE paraclinical_result_attachments (
  attachment_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_item_id       BIGINT       NOT NULL,
  file_name           VARCHAR(255) NOT NULL,
  file_url            VARCHAR(500) NOT NULL,
  file_type           ENUM('IMAGE','DICOM','PDF','OTHER') NOT NULL DEFAULT 'IMAGE',
  caption             VARCHAR(255) NULL,
  uploaded_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_pra_item FOREIGN KEY (order_item_id) REFERENCES paraclinical_order_items(order_item_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Nhật ký đồng bộ ảnh X-quang máy chụp NanoPix (FT12, UC-10)
CREATE TABLE nanopix_xray_sync_logs (
  sync_log_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_study_uid  VARCHAR(100) NOT NULL COMMENT 'UID định danh duy nhất của ca chụp',
  external_patient_id VARCHAR(50)  NULL,
  image_url           VARCHAR(500) NOT NULL,
  sync_status         ENUM('MATCHED','UNMATCHED','ERROR') NOT NULL DEFAULT 'UNMATCHED',
  matched_item_id     BIGINT       NULL,
  matched_profile_id  BIGINT       NULL,
  is_manual_linked    TINYINT(1)   NOT NULL DEFAULT 0,
  linked_by           BIGINT       NULL,
  captured_at         DATETIME     NOT NULL,
  sync_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_nanopix_uid UNIQUE (external_study_uid),
  CONSTRAINT fk_npx_item    FOREIGN KEY (matched_item_id)    REFERENCES paraclinical_order_items(order_item_id) ON DELETE SET NULL,
  CONSTRAINT fk_npx_profile FOREIGN KEY (matched_profile_id) REFERENCES patient_profiles(profile_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Đồng bộ ảnh X-quang NanoPix';

-- Theo dõi mẫu gửi phòng Labo xét nghiệm bên ngoài (FT13, UC-10)
CREATE TABLE lab_external_orders (
  lab_order_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_item_id        BIGINT       NOT NULL,
  lab_partner_name     VARCHAR(150) NOT NULL,
  sample_code          VARCHAR(50)  NOT NULL,
  sample_type          VARCHAR(100) NOT NULL,
  sent_at              DATETIME     NOT NULL,
  expected_result_date DATETIME     NULL,
  received_result_at   DATETIME     NULL,
  lab_status           ENUM('SENT','PROCESSING','RESULT_RECEIVED','REJECTED') NOT NULL DEFAULT 'SENT',
  result_file_url      VARCHAR(500) NULL,
  notes                VARCHAR(255) NULL,
  CONSTRAINT uk_lab_ext_item UNIQUE (order_item_id),
  CONSTRAINT fk_lab_ext_item FOREIGN KEY (order_item_id) REFERENCES paraclinical_order_items(order_item_id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='Mẫu xét nghiệm gửi Labo ngoài';

-- =====================================================================================
-- 8. KẾ HOẠCH ĐIỀU TRỊ, DỊCH VỤ THỰC HIỆN & LABO RĂNG GIẢ (6 BẢNG)
-- =====================================================================================

-- Kế hoạch điều trị nhiều giai đoạn (FT15, UC-08)
CREATE TABLE treatment_plans (
  plan_id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  plan_code            VARCHAR(20)   NOT NULL,
  profile_id           BIGINT        NOT NULL,
  branch_id            BIGINT        NOT NULL,
  doctor_id            BIGINT        NOT NULL,
  plan_name            VARCHAR(200)  NOT NULL,
  total_estimated_cost DECIMAL(15,2) NOT NULL DEFAULT 0,
  final_estimated_cost DECIMAL(15,2) NOT NULL DEFAULT 0,
  status               ENUM('DRAFT','PROPOSED','ACCEPTED','IN_PROGRESS','COMPLETED','CANCELLED') NOT NULL DEFAULT 'DRAFT',
  start_date           DATE          NULL,
  expected_end_date    DATE          NULL,
  notes                TEXT          NULL,
  created_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_plan_code UNIQUE (plan_code),
  CONSTRAINT fk_tp_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_tp_branch  FOREIGN KEY (branch_id)  REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_tp_doctor  FOREIGN KEY (doctor_id)  REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE treatment_plan_stages (
  stage_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  plan_id             BIGINT       NOT NULL,
  stage_order         INT          NOT NULL,
  stage_name          VARCHAR(150) NOT NULL,
  status              ENUM('PENDING','IN_PROGRESS','COMPLETED','SKIPPED') NOT NULL DEFAULT 'PENDING',
  completed_at        DATETIME     NULL,
  CONSTRAINT uk_tps UNIQUE (plan_id, stage_order),
  CONSTRAINT fk_tps_plan FOREIGN KEY (plan_id) REFERENCES treatment_plans(plan_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE treatment_plan_items (
  plan_item_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  stage_id            BIGINT        NOT NULL,
  plan_id             BIGINT        NOT NULL,
  service_id          BIGINT        NOT NULL,
  tooth_numbers       VARCHAR(50)   NULL,
  quantity            DECIMAL(5,2)  NOT NULL DEFAULT 1,
  estimated_price     DECIMAL(15,2) NOT NULL DEFAULT 0,
  status              ENUM('PLANNED','IN_PROGRESS','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PLANNED',
  CONSTRAINT fk_tpi_stage FOREIGN KEY (stage_id)   REFERENCES treatment_plan_stages(stage_id) ON DELETE CASCADE,
  CONSTRAINT fk_tpi_plan  FOREIGN KEY (plan_id)    REFERENCES treatment_plans(plan_id) ON DELETE CASCADE,
  CONSTRAINT fk_tpi_svc   FOREIGN KEY (service_id) REFERENCES services(service_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Dịch vụ THỰC TẾ ĐÃ THỰC HIỆN (FT16, UC-08: căn cứ xuất hóa đơn & trích hoa hồng, liên kết hạng mục kế hoạch điều trị)
CREATE TABLE performed_services (
  performed_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  visit_id            BIGINT        NOT NULL,
  plan_item_id        BIGINT        NULL COMMENT 'Liên kết hạng mục trong kế hoạch điều trị (FT15-FT16)',
  profile_id          BIGINT        NOT NULL,
  branch_id           BIGINT        NOT NULL,
  service_id          BIGINT        NOT NULL,
  tooth_numbers       VARCHAR(50)   NULL COMMENT 'Vị trí răng thực tế (FDI: 11, 21, 36...)',
  quantity            DECIMAL(5,2)  NOT NULL DEFAULT 1,
  unit_price          DECIMAL(15,2) NOT NULL DEFAULT 0,
  discount_amount     DECIMAL(15,2) NOT NULL DEFAULT 0,
  final_price         DECIMAL(15,2) NOT NULL DEFAULT 0,
  doctor_id           BIGINT        NOT NULL,
  assistant_id        BIGINT        NULL,
  completed_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status              ENUM('COMPLETED','CANCELLED') NOT NULL DEFAULT 'COMPLETED',
  clinical_notes      TEXT          NULL,
  KEY idx_ps_visit (visit_id),
  KEY idx_ps_doctor (doctor_id, completed_at),
  CONSTRAINT fk_ps_visit     FOREIGN KEY (visit_id)      REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_ps_plan_item FOREIGN KEY (plan_item_id)  REFERENCES treatment_plan_items(plan_item_id) ON DELETE SET NULL,
  CONSTRAINT fk_ps_profile   FOREIGN KEY (profile_id)    REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_ps_branch    FOREIGN KEY (branch_id)     REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_ps_svc       FOREIGN KEY (service_id)    REFERENCES services(service_id) ON DELETE RESTRICT,
  CONSTRAINT fk_ps_doctor    FOREIGN KEY (doctor_id)     REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT,
  CONSTRAINT fk_ps_assistant FOREIGN KEY (assistant_id)  REFERENCES staff_profiles(staff_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Dịch vụ nha khoa thực tế hoàn thành';

-- Ảnh chụp lâm sàng trước/trong/sau điều trị (FT17, UC-08)
CREATE TABLE clinical_photos (
  photo_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id          BIGINT       NOT NULL,
  visit_id            BIGINT       NULL,
  performed_id        BIGINT       NULL,
  photo_stage         ENUM('BEFORE','DURING','AFTER') NOT NULL DEFAULT 'BEFORE',
  photo_category      ENUM('EXTRAORAL','INTRAORAL','SMILE','OCCLUSAL','OTHER') NOT NULL DEFAULT 'INTRAORAL',
  image_url           VARCHAR(500) NOT NULL,
  caption             VARCHAR(255) NULL,
  captured_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_cp_profile FOREIGN KEY (profile_id)   REFERENCES patient_profiles(profile_id) ON DELETE CASCADE,
  CONSTRAINT fk_cp_visit   FOREIGN KEY (visit_id)     REFERENCES visits(visit_id) ON DELETE SET NULL,
  CONSTRAINT fk_cp_ps      FOREIGN KEY (performed_id) REFERENCES performed_services(performed_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Ảnh chụp lâm sàng răng miệng';

-- Đơn đặt xưởng chế tác Labo phục hình răng sứ / hàm giả
CREATE TABLE labo_orders (
  labo_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  labo_code           VARCHAR(20)   NOT NULL,
  visit_id            BIGINT        NOT NULL,
  profile_id          BIGINT        NOT NULL,
  branch_id           BIGINT        NOT NULL,
  doctor_id           BIGINT        NOT NULL,
  supplier_id         BIGINT        NOT NULL COMMENT 'Xưởng gia công Labo',
  order_type          ENUM('NEW','REDO','WARRANTY') NOT NULL DEFAULT 'NEW' COMMENT 'Làm mới, Làm lại, Bảo hành',
  tooth_positions     VARCHAR(100)  NOT NULL,
  material_type       VARCHAR(100)  NOT NULL COMMENT 'Cercon HT, Zirconia, Emax...',
  shade_color         VARCHAR(50)   NOT NULL COMMENT 'Bảng so màu Vita: A1, A2, B1...',
  cost_to_clinic      DECIMAL(15,2) NOT NULL DEFAULT 0,
  sent_date           DATE          NOT NULL,
  try_in_date         DATE          NULL COMMENT 'Ngày hẹn thử sườn / thử răng sứ',
  expected_date       DATE          NOT NULL,
  received_date       DATE          NULL,
  status              ENUM('SENT','PROCESSING','DELIVERED','FITTED_SUCCESS','RETURNED') NOT NULL DEFAULT 'SENT',
  notes               TEXT          NULL,
  CONSTRAINT uk_labo_code UNIQUE (labo_code),
  CONSTRAINT fk_labo_visit    FOREIGN KEY (visit_id)     REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_labo_profile  FOREIGN KEY (profile_id)   REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_labo_branch   FOREIGN KEY (branch_id)    REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_labo_doctor   FOREIGN KEY (doctor_id)    REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT,
  CONSTRAINT fk_labo_supplier FOREIGN KEY (supplier_id)  REFERENCES suppliers(supplier_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Đơn đặt xưởng răng giả Labo';

-- =====================================================================================
-- 9. ĐƠN THUỐC & DƯỢC PHẨM (3 BẢNG)
-- =====================================================================================

CREATE TABLE medications (
  medication_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  drug_code           VARCHAR(30)  NOT NULL,
  drug_name           VARCHAR(200) NOT NULL,
  active_ingredient   VARCHAR(200) NULL,
  unit                VARCHAR(30)  NOT NULL DEFAULT 'Viên',
  usage_instruction   VARCHAR(255) NULL,
  is_active           TINYINT(1)   NOT NULL DEFAULT 1,
  CONSTRAINT uk_drug_code UNIQUE (drug_code)
) ENGINE=InnoDB;

-- Đơn thuốc điều trị, lời dặn & hẹn tái khám (FT18, UC-08)
CREATE TABLE prescriptions (
  prescription_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  prescription_code   VARCHAR(20)  NOT NULL,
  visit_id            BIGINT       NOT NULL,
  profile_id          BIGINT       NOT NULL,
  branch_id           BIGINT       NOT NULL,
  doctor_id           BIGINT       NOT NULL,
  diagnosis_text      VARCHAR(255) NOT NULL,
  advice_notes        TEXT         NULL COMMENT 'Lời dặn bác sĩ & hướng dẫn chăm sóc',
  followup_date       DATE         NULL COMMENT 'Hẹn ngày tái khám',
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_presc_code UNIQUE (prescription_code),
  CONSTRAINT fk_pr_visit   FOREIGN KEY (visit_id)   REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_pr_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pr_branch  FOREIGN KEY (branch_id)  REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pr_doctor  FOREIGN KEY (doctor_id)  REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Đơn thuốc điều trị';

CREATE TABLE prescription_items (
  item_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  prescription_id     BIGINT       NOT NULL,
  medication_id       BIGINT       NULL,
  drug_name           VARCHAR(200) NOT NULL,
  dosage              VARCHAR(100) NOT NULL,
  duration_days       INT          NOT NULL DEFAULT 5,
  quantity            DECIMAL(6,2) NOT NULL,
  unit                VARCHAR(30)  NOT NULL DEFAULT 'Viên',
  usage_instruction   VARCHAR(255) NOT NULL,
  CONSTRAINT fk_pi_presc FOREIGN KEY (prescription_id) REFERENCES prescriptions(prescription_id) ON DELETE CASCADE,
  CONSTRAINT fk_pi_med   FOREIGN KEY (medication_id)   REFERENCES medications(medication_id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- =====================================================================================
-- 10. TÍCH ĐIỂM, KHUYẾN MÃI & VOUCHER (4 BẢNG)
-- =====================================================================================

CREATE TABLE patient_loyalty_wallets (
  wallet_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id          BIGINT        NOT NULL,
  current_points      INT           NOT NULL DEFAULT 0,
  total_spent_amount  DECIMAL(15,2) NOT NULL DEFAULT 0,
  last_updated        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_plw_profile UNIQUE (profile_id),
  CONSTRAINT fk_plw_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE point_transactions (
  transaction_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id          BIGINT       NOT NULL,
  invoice_id          BIGINT       NULL,
  transaction_type    ENUM('EARN','REDEEM','ADJUST') NOT NULL,
  points              INT          NOT NULL COMMENT 'Dương = cộng điểm, Âm = tiêu điểm',
  balance_after       INT          NOT NULL,
  reason              VARCHAR(255) NOT NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_pt_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pt_inv     FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE SET NULL
) ENGINE=InnoDB;

-- Chương trình khuyến mãi & Điều kiện áp dụng (FT20 / SCR-20.3, UC-15)
CREATE TABLE promotions (
  promotion_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  promo_code            VARCHAR(30)   NOT NULL,
  promo_name            VARCHAR(200)  NOT NULL,
  branch_id             BIGINT        NULL COMMENT 'NULL = Toàn chuỗi, có giá trị = Chỉ áp dụng tại chi nhánh',
  promo_type            ENUM('PERCENTAGE','FIXED_AMOUNT') NOT NULL DEFAULT 'PERCENTAGE',
  discount_value        DECIMAL(15,2) NOT NULL,
  max_discount_amount   DECIMAL(15,2) NULL,
  min_bill_amount       DECIMAL(15,2) NOT NULL DEFAULT 0,
  applicable_tier_id    BIGINT        NULL COMMENT 'Điều kiện áp dụng theo hạng thành viên (FT20)',
  applicable_service_id BIGINT        NULL COMMENT 'Điều kiện áp dụng theo dịch vụ cụ thể (FT20)',
  start_date            DATETIME      NOT NULL,
  end_date              DATETIME      NOT NULL,
  usage_limit           INT           NULL COMMENT 'Tổng số lượt sử dụng tối đa (NULL = không giới hạn)',
  used_count            INT           NOT NULL DEFAULT 0,
  is_active             TINYINT(1)    NOT NULL DEFAULT 1,
  CONSTRAINT uk_promo_code    UNIQUE (promo_code),
  CONSTRAINT fk_promo_branch  FOREIGN KEY (branch_id)             REFERENCES branches(branch_id) ON DELETE SET NULL,
  CONSTRAINT fk_promo_tier    FOREIGN KEY (applicable_tier_id)    REFERENCES membership_tiers(tier_id) ON DELETE SET NULL,
  CONSTRAINT fk_promo_service FOREIGN KEY (applicable_service_id) REFERENCES services(service_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Chương trình khuyến mãi & Mã giảm giá';

CREATE TABLE patient_vouchers (
  voucher_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  promotion_id        BIGINT      NOT NULL,
  profile_id          BIGINT      NOT NULL,
  voucher_code        VARCHAR(50) NOT NULL,
  status              ENUM('AVAILABLE','USED','EXPIRED') NOT NULL DEFAULT 'AVAILABLE',
  expires_at          DATETIME    NOT NULL,
  used_at             DATETIME    NULL,
  CONSTRAINT uk_voucher_code UNIQUE (voucher_code),
  CONSTRAINT fk_pv_promo   FOREIGN KEY (promotion_id) REFERENCES promotions(promotion_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pv_profile FOREIGN KEY (profile_id)   REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- =====================================================================================
-- 11. HÓA ĐƠN, THANH TOÁN, TẠM ỨNG, HOÀN TIỀN & CHI PHÍ (5 BẢNG)
-- =====================================================================================

-- Hóa đơn điều trị & Lịch sử điều chỉnh (FT21 / SCR-21.4, FT24, UC-16)
CREATE TABLE invoices (
  invoice_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_code         VARCHAR(20)   NOT NULL,
  visit_id             BIGINT        NOT NULL,
  profile_id           BIGINT        NOT NULL,
  branch_id            BIGINT        NOT NULL,
  cashier_id           BIGINT        NOT NULL,
  promotion_id         BIGINT        NULL,
  voucher_id           BIGINT        NULL,
  total_service_amount DECIMAL(15,2) NOT NULL DEFAULT 0,
  discount_amount      DECIMAL(15,2) NOT NULL DEFAULT 0,
  promotion_discount   DECIMAL(15,2) NOT NULL DEFAULT 0,
  point_discount       DECIMAL(15,2) NOT NULL DEFAULT 0,
  total_amount         DECIMAL(15,2) NOT NULL DEFAULT 0,
  paid_amount          DECIMAL(15,2) NOT NULL DEFAULT 0,
  remaining_debt       DECIMAL(15,2) NOT NULL DEFAULT 0,
  payment_status       ENUM('UNPAID','PARTIAL','PAID','CANCELLED') NOT NULL DEFAULT 'UNPAID',
  issued_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  notes                TEXT          NULL,
  adjusted_by          BIGINT        NULL COMMENT 'Nhân sự điều chỉnh hóa đơn (SCR-21.4)',
  adjustment_reason    VARCHAR(500)  NULL COMMENT 'Lý do điều chỉnh hóa đơn (SCR-21.4)',
  signature_hash       TEXT          NULL COMMENT 'Chữ ký điện tử trên hóa đơn nếu áp dụng (FT21)',
  CONSTRAINT uk_invoice_code UNIQUE (invoice_code),
  KEY idx_inv_profile (profile_id),
  KEY idx_inv_branch_date (branch_id, issued_at),
  CONSTRAINT fk_inv_visit   FOREIGN KEY (visit_id)     REFERENCES visits(visit_id) ON DELETE RESTRICT,
  CONSTRAINT fk_inv_profile FOREIGN KEY (profile_id)   REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_inv_branch  FOREIGN KEY (branch_id)    REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_inv_cashier FOREIGN KEY (cashier_id)   REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT,
  CONSTRAINT fk_inv_promo   FOREIGN KEY (promotion_id) REFERENCES promotions(promotion_id) ON DELETE SET NULL,
  CONSTRAINT fk_inv_voucher FOREIGN KEY (voucher_id)   REFERENCES patient_vouchers(voucher_id) ON DELETE SET NULL,
  CONSTRAINT fk_inv_adjust  FOREIGN KEY (adjusted_by)  REFERENCES staff_profiles(staff_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Hóa đơn điều trị';

CREATE TABLE invoice_items (
  item_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id          BIGINT        NOT NULL,
  performed_id        BIGINT        NULL,
  service_id          BIGINT        NOT NULL,
  item_name           VARCHAR(200)  NOT NULL,
  quantity            DECIMAL(5,2)  NOT NULL DEFAULT 1,
  unit_price          DECIMAL(15,2) NOT NULL DEFAULT 0,
  final_amount        DECIMAL(15,2) NOT NULL DEFAULT 0,
  CONSTRAINT fk_ii_invoice FOREIGN KEY (invoice_id)   REFERENCES invoices(invoice_id) ON DELETE CASCADE,
  CONSTRAINT fk_ii_ps      FOREIGN KEY (performed_id) REFERENCES performed_services(performed_id) ON DELETE SET NULL,
  CONSTRAINT fk_ii_svc     FOREIGN KEY (service_id)   REFERENCES services(service_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Giao dịch thanh toán nhiều đợt & Tạm ứng (FT22, UC-16)
CREATE TABLE payments (
  payment_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  payment_code        VARCHAR(20)   NOT NULL,
  invoice_id          BIGINT        NULL COMMENT 'NULL nếu là nạp tiền tạm ứng',
  profile_id          BIGINT        NOT NULL,
  branch_id           BIGINT        NOT NULL,
  payment_type        ENUM('PAYMENT','ADVANCE_DEPOSIT') NOT NULL DEFAULT 'PAYMENT',
  payment_method      ENUM('CASH','BANK_TRANSFER','POS_CARD','DEPOSIT_WALLET') NOT NULL DEFAULT 'CASH' COMMENT 'DEPOSIT_WALLET = Cấn trừ từ số dư tạm ứng deposit_balance',
  amount              DECIMAL(15,2) NOT NULL,
  transaction_ref     VARCHAR(100)  NULL,
  notes               VARCHAR(255)  NULL,
  cashier_id          BIGINT        NOT NULL,
  paid_at             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_pmt_code UNIQUE (payment_code),
  CONSTRAINT fk_pmt_inv     FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pmt_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pmt_branch  FOREIGN KEY (branch_id)  REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_pmt_cashier FOREIGN KEY (cashier_id) REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Giao dịch thanh toán & Tạm ứng';

-- Hoàn tiền & Điều chỉnh tài chính (FT24, UC-16)
CREATE TABLE refunds (
  refund_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  refund_code         VARCHAR(20)   NOT NULL,
  invoice_id          BIGINT        NULL,
  profile_id          BIGINT        NOT NULL,
  branch_id           BIGINT        NOT NULL,
  amount              DECIMAL(15,2) NOT NULL,
  refund_method       ENUM('CASH','BANK_TRANSFER') NOT NULL DEFAULT 'CASH',
  reason              VARCHAR(500)  NOT NULL,
  refunded_by         BIGINT        NOT NULL,
  refunded_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_ref_code UNIQUE (refund_code),
  CONSTRAINT fk_ref_inv     FOREIGN KEY (invoice_id)  REFERENCES invoices(invoice_id) ON DELETE SET NULL,
  CONSTRAINT fk_ref_profile FOREIGN KEY (profile_id)  REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_ref_branch  FOREIGN KEY (branch_id)   REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_ref_staff   FOREIGN KEY (refunded_by) REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Chi phí vận hành theo chi nhánh (FT25, UC-17)
CREATE TABLE operating_expenses (
  expense_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  expense_code        VARCHAR(20)   NOT NULL,
  branch_id           BIGINT        NOT NULL,
  category            VARCHAR(100)  NOT NULL COMMENT 'Điện nước, Thuê mặt bằng, Lương, Mua vật tư...',
  amount              DECIMAL(15,2) NOT NULL,
  expense_date        DATE          NOT NULL,
  recipient_name      VARCHAR(150)  NULL,
  payment_method      ENUM('CASH','BANK_TRANSFER') NOT NULL DEFAULT 'BANK_TRANSFER',
  payroll_period_id   BIGINT        NULL COMMENT 'Khóa ngoại liên kết kỳ lương (tránh hạch toán 2 lần)',
  description         TEXT          NOT NULL,
  created_by          BIGINT        NOT NULL,
  created_at          DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_exp_code UNIQUE (expense_code),
  KEY idx_exp_branch_date (branch_id, expense_date),
  CONSTRAINT fk_exp_branch  FOREIGN KEY (branch_id)          REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_exp_payroll FOREIGN KEY (payroll_period_id)  REFERENCES payroll_periods(period_id) ON DELETE SET NULL,
  CONSTRAINT fk_exp_creator FOREIGN KEY (created_by)         REFERENCES users(user_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Chi phí hoạt động theo chi nhánh';

-- =====================================================================================
-- 12. NHÀ CUNG CẤP & KHO VẬT TƯ (9 BẢNG)
-- =====================================================================================

-- Nhà cung cấp vật tư & Labo (FT26, UC-19)
CREATE TABLE suppliers (
  supplier_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  supplier_code       VARCHAR(30)  NOT NULL,
  supplier_name       VARCHAR(200) NOT NULL,
  tax_code            VARCHAR(20)  NULL,
  phone_number        VARCHAR(20)  NULL,
  address             VARCHAR(255) NULL,
  is_active           TINYINT(1)   NOT NULL DEFAULT 1,
  CONSTRAINT uk_supp_code UNIQUE (supplier_code)
) ENGINE=InnoDB COMMENT='Nhà cung cấp vật tư y tế, xưởng Labo';

CREATE TABLE materials (
  material_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  material_code       VARCHAR(30)   NOT NULL,
  material_name       VARCHAR(200)  NOT NULL,
  category            VARCHAR(100)  NOT NULL COMMENT 'Trám răng, Chỉnh nha, Thuốc tê, Tiêu hao...',
  unit                VARCHAR(30)   NOT NULL COMMENT 'Cây, Hộp, Lọ, Ống...',
  min_stock_threshold DECIMAL(12,3) NOT NULL DEFAULT 10.000,
  is_active           TINYINT(1)    NOT NULL DEFAULT 1,
  CONSTRAINT uk_mat_code UNIQUE (material_code)
) ENGINE=InnoDB;

CREATE TABLE branch_inventory (
  inventory_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
  branch_id           BIGINT        NOT NULL,
  material_id         BIGINT        NOT NULL,
  current_stock       DECIMAL(12,3) NOT NULL DEFAULT 0.000,
  last_updated        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uk_bi UNIQUE (branch_id, material_id),
  CONSTRAINT fk_bi_branch FOREIGN KEY (branch_id)   REFERENCES branches(branch_id) ON DELETE CASCADE,
  CONSTRAINT fk_bi_mat    FOREIGN KEY (material_id) REFERENCES materials(material_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Tồn kho tức thời tại chi nhánh';

CREATE TABLE material_batches (
  batch_id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  branch_id           BIGINT        NOT NULL,
  material_id         BIGINT        NOT NULL,
  batch_number        VARCHAR(50)   NOT NULL,
  expiry_date         DATE          NOT NULL,
  quantity_remaining  DECIMAL(12,3) NOT NULL,
  import_price        DECIMAL(15,2) NOT NULL DEFAULT 0,
  supplier_id         BIGINT        NULL,
  status              ENUM('ACTIVE','EXPIRED','OUT_OF_STOCK') NOT NULL DEFAULT 'ACTIVE',
  CONSTRAINT uk_mb UNIQUE (branch_id, material_id, batch_number),
  KEY idx_mb_expiry (branch_id, expiry_date),
  CONSTRAINT fk_mb_branch   FOREIGN KEY (branch_id)   REFERENCES branches(branch_id) ON DELETE CASCADE,
  CONSTRAINT fk_mb_mat      FOREIGN KEY (material_id) REFERENCES materials(material_id) ON DELETE RESTRICT,
  CONSTRAINT fk_mb_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Lô vật tư có hạn dùng';

-- Phiếu nhập kho & Chứng từ mua hàng (FT27 / SCR-27.3, UC-20)
CREATE TABLE stock_receipts (
  receipt_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  receipt_code        VARCHAR(20)   NOT NULL,
  branch_id           BIGINT        NOT NULL,
  supplier_id         BIGINT        NOT NULL,
  supplier_invoice_no VARCHAR(50)   NULL COMMENT 'Số hóa đơn mua hàng NCC (SCR-27.3)',
  receipt_date        DATE          NOT NULL,
  total_amount        DECIMAL(15,2) NOT NULL DEFAULT 0,
  receiver_id         BIGINT        NOT NULL,
  notes               VARCHAR(255)  NULL,
  attachment_url      VARCHAR(500)  NULL COMMENT 'Ảnh chụp/scan hóa đơn và chứng từ mua hàng để đối soát (FT27)',
  CONSTRAINT uk_sr_code UNIQUE (receipt_code),
  CONSTRAINT fk_sr_branch   FOREIGN KEY (branch_id)   REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_sr_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(supplier_id) ON DELETE RESTRICT,
  CONSTRAINT fk_sr_receiver FOREIGN KEY (receiver_id) REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Phiếu nhập kho vật tư';

CREATE TABLE stock_receipt_items (
  receipt_item_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  receipt_id          BIGINT        NOT NULL,
  material_id         BIGINT        NOT NULL,
  batch_number        VARCHAR(50)   NOT NULL,
  expiry_date         DATE          NOT NULL,
  quantity            DECIMAL(12,3) NOT NULL,
  unit_price          DECIMAL(15,2) NOT NULL,
  CONSTRAINT fk_sri_receipt FOREIGN KEY (receipt_id)  REFERENCES stock_receipts(receipt_id) ON DELETE CASCADE,
  CONSTRAINT fk_sri_mat     FOREIGN KEY (material_id) REFERENCES materials(material_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- Phiếu xuất kho / điều chuyển / hủy hết hạn (FT27 / SCR-27.4, UC-20)
CREATE TABLE stock_issues (
  issue_id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  issue_code            VARCHAR(20)  NOT NULL,
  branch_id             BIGINT       NOT NULL,
  issue_type            ENUM('INTERNAL_USE','TRANSFER','DISCARD_EXPIRED') NOT NULL DEFAULT 'INTERNAL_USE',
  issue_date            DATE         NOT NULL,
  destination_branch_id BIGINT       NULL,
  issuer_id             BIGINT       NOT NULL,
  notes                 VARCHAR(255) NULL,
  CONSTRAINT uk_si_code UNIQUE (issue_code),
  CONSTRAINT fk_si_branch   FOREIGN KEY (branch_id)             REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_si_dest     FOREIGN KEY (destination_branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_si_issuer   FOREIGN KEY (issuer_id)             REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Phiếu xuất kho vật tư';

-- Chi tiết vật tư xuất kho (FT27, UC-20)
CREATE TABLE stock_issue_items (
  issue_item_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  issue_id            BIGINT        NOT NULL,
  material_id         BIGINT        NOT NULL,
  batch_id            BIGINT        NULL COMMENT 'Lô vật tư xuất (theo FEFO)',
  quantity            DECIMAL(12,3) NOT NULL,
  notes               VARCHAR(255)  NULL,
  CONSTRAINT fk_sii_issue FOREIGN KEY (issue_id)    REFERENCES stock_issues(issue_id) ON DELETE CASCADE,
  CONSTRAINT fk_sii_mat   FOREIGN KEY (material_id) REFERENCES materials(material_id) ON DELETE RESTRICT,
  CONSTRAINT fk_sii_batch FOREIGN KEY (batch_id)    REFERENCES material_batches(batch_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Chi tiết vật tư trong phiếu xuất/điều chuyển/hủy kho';

-- Tiêu hao vật tư theo ca điều trị thực tế (FT28, UC-20, R7: UNIQUE khóa tránh trừ 2 lần)
CREATE TABLE material_consumptions (
  consumption_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  visit_id            BIGINT        NOT NULL,
  performed_id        BIGINT        NOT NULL,
  branch_id           BIGINT        NOT NULL,
  material_id         BIGINT        NOT NULL,
  batch_id            BIGINT        NOT NULL,
  quantity            DECIMAL(12,3) NOT NULL,
  consumed_by         BIGINT        NOT NULL,
  consumed_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_mc_dedup UNIQUE (performed_id, material_id, batch_id),
  CONSTRAINT fk_mc_visit FOREIGN KEY (visit_id)     REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_mc_ps    FOREIGN KEY (performed_id) REFERENCES performed_services(performed_id) ON DELETE CASCADE,
  CONSTRAINT fk_mc_br    FOREIGN KEY (branch_id)    REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_mc_mat   FOREIGN KEY (material_id)  REFERENCES materials(material_id) ON DELETE RESTRICT,
  CONSTRAINT fk_mc_batch FOREIGN KEY (batch_id)     REFERENCES material_batches(batch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_mc_staff FOREIGN KEY (consumed_by)  REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Tiêu hao vật tư từng ca làm (chống trừ 2 lần)';

-- =====================================================================================
-- 13. LƯƠNG & HOA HỒNG NHÂN SỰ (4 BẢNG)
-- =====================================================================================

CREATE TABLE commission_policies (
  policy_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  policy_name         VARCHAR(150)  NOT NULL,
  service_id          BIGINT        NULL COMMENT 'NULL = toàn bộ dịch vụ',
  role_id             BIGINT        NULL,
  staff_id            BIGINT        NULL,
  commission_type     ENUM('PERCENTAGE','FIXED_AMOUNT') NOT NULL DEFAULT 'PERCENTAGE',
  commission_value    DECIMAL(15,2) NOT NULL,
  is_active           TINYINT(1)    NOT NULL DEFAULT 1,
  CONSTRAINT fk_cp_svc   FOREIGN KEY (service_id) REFERENCES services(service_id) ON DELETE CASCADE,
  CONSTRAINT fk_cp_role  FOREIGN KEY (role_id)    REFERENCES roles(role_id) ON DELETE CASCADE,
  CONSTRAINT fk_cp_staff FOREIGN KEY (staff_id)   REFERENCES staff_profiles(staff_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE service_commissions (
  commission_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  performed_id        BIGINT        NOT NULL,
  staff_id            BIGINT        NOT NULL,
  role_type           ENUM('MAIN_DOCTOR','ASSISTANT') NOT NULL DEFAULT 'MAIN_DOCTOR',
  commission_amount   DECIMAL(15,2) NOT NULL,
  status              ENUM('PENDING','APPROVED','PAID') NOT NULL DEFAULT 'PENDING',
  calculated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_sc UNIQUE (performed_id, staff_id, role_type),
  CONSTRAINT fk_sc_ps    FOREIGN KEY (performed_id) REFERENCES performed_services(performed_id) ON DELETE CASCADE,
  CONSTRAINT fk_sc_staff FOREIGN KEY (staff_id)     REFERENCES staff_profiles(staff_id) ON DELETE CASCADE
) ENGINE=InnoDB COMMENT='Hoa hồng kỹ thuật theo dịch vụ thực tế';

CREATE TABLE payroll_periods (
  period_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  period_name         VARCHAR(100)  NOT NULL COMMENT 'Bảng lương Tháng 10/2026',
  branch_id           BIGINT        NOT NULL,
  month               INT           NOT NULL,
  year                INT           NOT NULL,
  total_salary        DECIMAL(15,2) NOT NULL DEFAULT 0,
  status              ENUM('DRAFT','APPROVED','PAID') NOT NULL DEFAULT 'DRAFT',
  CONSTRAINT uk_payroll_period UNIQUE (branch_id, month, year),
  CONSTRAINT fk_pp_branch_payroll FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE payroll_items (
  item_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  period_id           BIGINT        NOT NULL,
  staff_id            BIGINT        NOT NULL,
  base_salary         DECIMAL(15,2) NOT NULL DEFAULT 0,
  actual_work_days    DECIMAL(5,2)  NOT NULL DEFAULT 0,
  actual_base_salary  DECIMAL(15,2) NOT NULL DEFAULT 0,
  total_commission    DECIMAL(15,2) NOT NULL DEFAULT 0,
  total_allowance     DECIMAL(15,2) NOT NULL DEFAULT 0,
  net_salary          DECIMAL(15,2) NOT NULL DEFAULT 0,
  payment_status      ENUM('UNPAID','PAID') NOT NULL DEFAULT 'UNPAID',
  CONSTRAINT uk_pi_staff UNIQUE (period_id, staff_id),
  CONSTRAINT fk_pi_period FOREIGN KEY (period_id) REFERENCES payroll_periods(period_id) ON DELETE CASCADE,
  CONSTRAINT fk_pi_staff  FOREIGN KEY (staff_id)  REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Chi tiết lương từng nhân viên';

-- =====================================================================================
-- 14. CHĂM SÓC KHÁCH HÀNG & ĐÁNH GIÁ (3 BẢNG)
-- =====================================================================================

-- Công việc chăm sóc khách hàng & Chuyển bác sĩ hỗ trợ chuyên môn (FT18, FT44 / SCR-44.5, UC-26)
CREATE TABLE customer_care_tasks (
  task_id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_code              VARCHAR(20)  NOT NULL,
  profile_id             BIGINT       NOT NULL,
  branch_id              BIGINT       NOT NULL,
  task_type              ENUM('APPOINTMENT_REMINDER','MEDICATION_REMINDER','POST_TREATMENT_FOLLOWUP','NO_SHOW_RECALL') NOT NULL,
  visit_id               BIGINT       NULL,
  appointment_id         BIGINT       NULL,
  prescription_id        BIGINT       NULL COMMENT 'Đơn thuốc cần nhắc dùng thuốc (FT44)',
  assigned_staff_id      BIGINT       NULL,
  escalated_to_doctor_id BIGINT       NULL COMMENT 'Bác sĩ tiếp nhận khi cần hỗ trợ chuyên môn (SCR-44.5)',
  escalated_at           DATETIME     NULL,
  due_date               DATE         NOT NULL,
  status                 ENUM('PENDING','IN_PROGRESS','ESCALATED_TO_DOCTOR','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  notes                  TEXT         NULL,
  doctor_response        TEXT         NULL COMMENT 'Phản hồi chuyên môn từ bác sĩ (FT44)',
  CONSTRAINT uk_cct_code UNIQUE (task_code),
  KEY idx_cct_assigned (assigned_staff_id, due_date, status),
  CONSTRAINT fk_cct_profile FOREIGN KEY (profile_id)             REFERENCES patient_profiles(profile_id) ON DELETE CASCADE,
  CONSTRAINT fk_cct_branch  FOREIGN KEY (branch_id)              REFERENCES branches(branch_id) ON DELETE RESTRICT,
  CONSTRAINT fk_cct_visit   FOREIGN KEY (visit_id)               REFERENCES visits(visit_id) ON DELETE SET NULL,
  CONSTRAINT fk_cct_appt    FOREIGN KEY (appointment_id)         REFERENCES appointments(appointment_id) ON DELETE SET NULL,
  CONSTRAINT fk_cct_presc   FOREIGN KEY (prescription_id)        REFERENCES prescriptions(prescription_id) ON DELETE SET NULL,
  CONSTRAINT fk_cct_staff   FOREIGN KEY (assigned_staff_id)      REFERENCES staff_profiles(staff_id) ON DELETE SET NULL,
  CONSTRAINT fk_cct_doctor  FOREIGN KEY (escalated_to_doctor_id) REFERENCES staff_profiles(staff_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Công việc chăm sóc khách hàng';

-- Kết quả liên hệ & Lịch hẹn gọi lại khi chưa bắt máy (FT18, FT44, UC-26)
CREATE TABLE customer_care_interactions (
  interaction_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id             BIGINT       NOT NULL,
  profile_id          BIGINT       NOT NULL,
  staff_id            BIGINT       NOT NULL,
  channel             ENUM('PHONE_CALL','ZALO','SMS','IN_PERSON') NOT NULL DEFAULT 'PHONE_CALL',
  contact_result      ENUM('CONNECTED','NO_ANSWER','BUSY','SCHEDULED_APPOINTMENT') NOT NULL,
  notes               TEXT         NOT NULL,
  next_retry_at       DATETIME     NULL COMMENT 'Lịch hẹn liên hệ lại khi chưa bắt máy (FT18/FT44)',
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_cci_task    FOREIGN KEY (task_id)    REFERENCES customer_care_tasks(task_id) ON DELETE CASCADE,
  CONSTRAINT fk_cci_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE CASCADE,
  CONSTRAINT fk_cci_staff   FOREIGN KEY (staff_id)   REFERENCES staff_profiles(staff_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Lịch sử tương tác chăm sóc khách hàng';

-- Đánh giá dịch vụ sau điều trị (FT23, UC-24, R9)
CREATE TABLE service_reviews (
  review_id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  visit_id            BIGINT       NOT NULL,
  profile_id          BIGINT       NOT NULL,
  branch_id           BIGINT       NOT NULL,
  rating              TINYINT      NOT NULL COMMENT '1 đến 5 sao',
  comment             TEXT         NULL,
  tags                VARCHAR(255) NULL,
  clinic_response     TEXT         NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_review_visit UNIQUE (visit_id),
  CONSTRAINT fk_sr_visit   FOREIGN KEY (visit_id)   REFERENCES visits(visit_id) ON DELETE CASCADE,
  CONSTRAINT fk_sr_profile FOREIGN KEY (profile_id) REFERENCES patient_profiles(profile_id) ON DELETE RESTRICT,
  CONSTRAINT fk_service_reviews_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE RESTRICT
) ENGINE=InnoDB;

-- =====================================================================================
-- 15. TIN TỨC & BÌNH LUẬN (4 BẢNG) — KHỚP 100% CÁC ENTITY JAVA
-- =====================================================================================

-- [Entity: NewsArticle.java] Bài viết tin tức (FT37, UC-23)
CREATE TABLE news_articles (
  news_id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  title               VARCHAR(200) NOT NULL,
  slug                VARCHAR(250) NOT NULL,
  summary             VARCHAR(500) NULL,
  content             LONGTEXT     NOT NULL,
  thumbnail           VARCHAR(500) NULL,
  status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
  created_by          BIGINT       NOT NULL,
  branch_id           BIGINT       NOT NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  published_at        DATETIME     NULL,
  CONSTRAINT uk_news_slug    UNIQUE (slug),
  KEY idx_news_status (status),
  KEY idx_news_published_at (published_at),
  CONSTRAINT fk_news_creator FOREIGN KEY (created_by) REFERENCES users(user_id) ON DELETE RESTRICT,
  CONSTRAINT fk_news_branch  FOREIGN KEY (branch_id)  REFERENCES branches(branch_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Bài viết tin tức nha khoa';

-- [Entity: Comment.java] Bình luận bài viết (FT38, FT39, UC-23)
CREATE TABLE comments (
  comment_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  news_id             BIGINT       NOT NULL,
  user_id             BIGINT       NOT NULL,
  parent_comment_id   BIGINT       NULL,
  content             TEXT         NOT NULL,
  status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_at          DATETIME     NULL,
  deleted_by          BIGINT       NULL,
  KEY idx_comment_news (news_id),
  KEY idx_comment_user (user_id),
  KEY idx_comment_parent (parent_comment_id),
  CONSTRAINT fk_com_news    FOREIGN KEY (news_id)           REFERENCES news_articles(news_id) ON DELETE CASCADE,
  CONSTRAINT fk_com_user    FOREIGN KEY (user_id)           REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_com_parent  FOREIGN KEY (parent_comment_id) REFERENCES comments(comment_id) ON DELETE CASCADE,
  CONSTRAINT fk_com_deleted FOREIGN KEY (deleted_by)        REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Bình luận bài viết';

-- [Entity: CommentLike.java] Thích bình luận (FT38, UC-23)
CREATE TABLE comment_likes (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  comment_id          BIGINT NOT NULL,
  user_id             BIGINT NOT NULL,
  CONSTRAINT uk_comment_user_like UNIQUE (comment_id, user_id),
  CONSTRAINT fk_cl_comment FOREIGN KEY (comment_id) REFERENCES comments(comment_id) ON DELETE CASCADE,
  CONSTRAINT fk_cl_user    FOREIGN KEY (user_id)    REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- [Entity: CommentBan.java] Chặn bình luận vi phạm (FT39, UC-23)
CREATE TABLE comment_bans (
  ban_id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id             BIGINT       NOT NULL,
  banned_by           BIGINT       NOT NULL,
  reason              VARCHAR(500) NOT NULL,
  banned_at           DATETIME     NOT NULL,
  expires_at          DATETIME     NULL,
  is_active           TINYINT(1)   NOT NULL DEFAULT 1,
  KEY idx_ban_user_active (user_id, is_active),
  KEY idx_ban_banned_at (banned_at),
  CONSTRAINT fk_cb_user      FOREIGN KEY (user_id)   REFERENCES users(user_id) ON DELETE CASCADE,
  CONSTRAINT fk_cb_banned_by FOREIGN KEY (banned_by) REFERENCES users(user_id) ON DELETE RESTRICT
) ENGINE=InnoDB COMMENT='Danh sách cấm bình luận';

-- =====================================================================================
-- 16. TƯ VẤN TRỰC TUYẾN & CHATBOT AI (3 BẢNG)
-- =====================================================================================

-- Phiên hội thoại AI Chatbot & Nhân sự tư vấn (FT45, UC-27)
CREATE TABLE chat_conversations (
  conversation_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  profile_id          BIGINT      NULL,
  user_id             BIGINT      NULL,
  channel             ENUM('WEB_WIDGET','MOBILE_APP','ZALO') NOT NULL DEFAULT 'WEB_WIDGET',
  status              ENUM('BOT_HANDLING','STAFF_HANDLING','CLOSED') NOT NULL DEFAULT 'BOT_HANDLING',
  assigned_staff_id   BIGINT      NULL,
  created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_message_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_conv_profile FOREIGN KEY (profile_id)        REFERENCES patient_profiles(profile_id) ON DELETE SET NULL,
  CONSTRAINT fk_conv_user    FOREIGN KEY (user_id)           REFERENCES users(user_id) ON DELETE SET NULL,
  CONSTRAINT fk_conv_staff   FOREIGN KEY (assigned_staff_id) REFERENCES staff_profiles(staff_id) ON DELETE SET NULL
) ENGINE=InnoDB;

CREATE TABLE chat_messages (
  message_id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id     BIGINT     NOT NULL,
  sender_type         ENUM('BOT','PATIENT','STAFF') NOT NULL,
  sender_id           BIGINT     NULL,
  message_text        TEXT       NOT NULL,
  is_read             TINYINT(1) NOT NULL DEFAULT 0,
  sent_at             DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversations(conversation_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- Nhật ký chuyển giao hội thoại giữa AI -> Lễ tân -> Bác sĩ (FT45 / SCR-45.4, SCR-45.5, UC-27)
CREATE TABLE chat_handover_logs (
  handover_id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id     BIGINT       NOT NULL,
  from_type           ENUM('BOT','RECEPTIONIST','DOCTOR') NOT NULL DEFAULT 'BOT',
  from_staff_id       BIGINT       NULL,
  to_role             ENUM('RECEPTIONIST','DOCTOR') NOT NULL,
  to_staff_id         BIGINT       NULL,
  handover_reason     VARCHAR(500) NULL,
  handed_over_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_chl_conv       FOREIGN KEY (conversation_id) REFERENCES chat_conversations(conversation_id) ON DELETE CASCADE,
  CONSTRAINT fk_chl_from_staff FOREIGN KEY (from_staff_id)   REFERENCES staff_profiles(staff_id) ON DELETE SET NULL,
  CONSTRAINT fk_chl_to_staff   FOREIGN KEY (to_staff_id)     REFERENCES staff_profiles(staff_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Lịch sử chuyển giao hội thoại Chatbot/Tư vấn (FT45/UC-27)';

-- =====================================================================================
-- 17. NHẬT KÝ KIỂM TOÁN & THÔNG BÁO (2 BẢNG)
-- =====================================================================================

-- R6: Bảng system_audit_logs là CHỈ ĐỌC (Append-only) (FT31, UC-25)
CREATE TABLE system_audit_logs (
  log_id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id             BIGINT       NULL,
  branch_id           BIGINT       NULL,
  action              VARCHAR(50)  NOT NULL,
  entity_table        VARCHAR(60)  NOT NULL,
  entity_id           BIGINT       NULL,
  old_values_json     JSON         NULL,
  new_values_json     JSON         NULL,
  ip_address          VARCHAR(45)  NULL,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_audit_table_id (entity_table, entity_id),
  CONSTRAINT fk_audit_user   FOREIGN KEY (user_id)   REFERENCES users(user_id) ON DELETE SET NULL,
  CONSTRAINT fk_audit_branch FOREIGN KEY (branch_id) REFERENCES branches(branch_id) ON DELETE SET NULL
) ENGINE=InnoDB COMMENT='Nhật ký kiểm toán bảo mật';

CREATE TABLE system_notifications (
  notification_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id             BIGINT       NOT NULL,
  title               VARCHAR(200) NOT NULL,
  message             TEXT         NOT NULL,
  notification_type   ENUM('APPOINTMENT','LAB_RESULT','LEAVE_APPROVAL','PAYROLL','SYSTEM') NOT NULL,
  is_read             TINYINT(1)   NOT NULL DEFAULT 0,
  created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_notif_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- =====================================================================================
-- 18. VIEWS BÁO CÁO & CÔNG NỢ DẪN XUẤT (R5)
-- =====================================================================================

-- R5: View tính công nợ tức thời từ hóa đơn (FT24, FT29)
CREATE OR REPLACE VIEW v_patient_debt_summary AS
SELECT
  p.profile_id,
  p.patient_code,
  p.full_name,
  p.phone_number,
  p.preferred_branch_id,
  COUNT(i.invoice_id) AS total_unpaid_invoices,
  COALESCE(SUM(i.total_amount), 0) AS total_billed_amount,
  COALESCE(SUM(i.paid_amount), 0)  AS total_paid_amount,
  COALESCE(SUM(i.remaining_debt), 0) AS total_outstanding_debt,
  MAX(i.issued_at) AS latest_invoice_date
FROM patient_profiles p
JOIN invoices i ON p.profile_id = i.profile_id
WHERE i.payment_status IN ('UNPAID', 'PARTIAL')
GROUP BY p.profile_id, p.patient_code, p.full_name, p.phone_number, p.preferred_branch_id;

-- View cảnh báo tồn kho dưới mức an toàn (FT27)
CREATE OR REPLACE VIEW v_branch_low_stock_alerts AS
SELECT
  b.branch_id,
  b.branch_name,
  m.material_id,
  m.material_code,
  m.material_name,
  m.unit,
  bi.current_stock,
  m.min_stock_threshold,
  (m.min_stock_threshold - bi.current_stock) AS shortage_quantity
FROM branch_inventory bi
JOIN branches b ON bi.branch_id = b.branch_id
JOIN materials m ON bi.material_id = m.material_id
WHERE bi.current_stock <= m.min_stock_threshold
  AND m.is_active = 1
  AND b.is_active = 1;

-- =====================================================================================
-- 19. TRIGGERS KIỂM SOÁT NGHIỆP VỤ BẮT BUỘC
-- =====================================================================================

DELIMITER $$


DELIMITER ;

-- =====================================================================================
-- 20. SEED DATA KHỞI TẠO HỆ THỐNG
-- =====================================================================================

INSERT INTO roles (role_id, role_name, display_name, description, is_system, is_active) VALUES
(1, 'ADMIN',              'Quản trị hệ thống',      'Toàn quyền quản trị chuỗi phòng khám', 1, 1),
(2, 'BAC_SI',             'Bác sĩ nha khoa',        'Khám, chẩn đoán, điều trị, kê đơn', 1, 1),
(3, 'LE_TAN',       'Lễ tân',                 'Tiếp đón, xếp hàng, đặt lịch hẹn', 1, 1),
(4, 'KE_TOAN',            'Thu ngân / Kế toán',     'Xuất hóa đơn, thu chi, công nợ', 1, 1),
(5, 'BENH_NHAN',            'Bệnh nhân',              'Người dùng cổng thông tin bệnh nhân', 1, 1),
(6, 'QUAN_LY_kHO',            'Quản lý Kho',              'Quản lý kho vật tư', 1, 1)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

INSERT INTO membership_tiers (tier_id, tier_code, tier_name, min_total_spent, discount_percent, display_order, is_active) VALUES
(1, 'STANDARD', 'Thành viên mới', 0,           0.00, 1, 1),
(2, 'SILVER',   'Thành viên Bạc', 5000000.00,  5.00, 2, 1),
(3, 'GOLD',     'Thành viên Vàng', 20000000.00, 10.00, 3, 1),
(4, 'DIAMOND',  'Kim Cương VIP',  50000000.00, 15.00, 4, 1)
ON DUPLICATE KEY UPDATE tier_name = VALUES(tier_name);

INSERT INTO icd10_diseases (icd10_code, disease_name_vi, disease_name_en, is_dental_common, is_active) VALUES
('K02.1', 'Sâu ngà răng',                             'Caries of dentine', 1, 1),
('K02.0', 'Sâu men răng',                             'Caries limited to enamel', 1, 1),
('K04.0', 'Viêm tủy răng',                            'Pulpitis', 1, 1),
('K04.1', 'Hoại tử tủy răng',                         'Necrosis of pulp', 1, 1),
('K04.7', 'Áp xe quanh chóp răng không có lỗ rò',     'Periapical abscess without sinus', 1, 1),
('K05.0', 'Viêm nướu cấp tính',                       'Acute gingivitis', 1, 1),
('K05.1', 'Viêm nướu mạn tính',                       'Chronic gingivitis', 1, 1),
('K05.3', 'Viêm nha chu mạn tính',                    'Chronic periodontitis', 1, 1),
('K07.2', 'Bất thường tương quan cung răng (Sai khớp cắn)','Anomalies of dental arch relationship', 1, 1),
('K01.1', 'Răng khôn mọc kẹt / mọc ngầm',             'Impacted teeth', 1, 1)
ON DUPLICATE KEY UPDATE disease_name_vi = VALUES(disease_name_vi);

SET FOREIGN_KEY_CHECKS = 1;