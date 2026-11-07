"""
NANOPIX X-RAY WINDOWS BACKGROUND SYNC WORKER (CV-DCMS)
------------------------------------------------------
Chương trình chạy nền trên máy trạm Windows tại phòng chụp X-quang:
1. Giám sát thư mục xuất ảnh của phần mềm Eighteeth NanoPix1.
2. Kiểm tra File Lock & tính toàn vẹn file (chờ phần mềm Eighteeth ghi xong hoàn toàn, từ chối file rỗng/hỏng).
3. Chống gửi trùng lặp tuyệt đối bằng mã băm SHA-256 và SQLite local cache.
4. Trích xuất thông tin định danh chính xác (orderCode XQ..., visitCode, externalPatientId BN..., toothNumbers R..., hoặc sidecar JSON).
5. Gửi ảnh + metadata qua POST /api/xray/ingest kèm X-API-KEY (không bao giờ ghi API key ra log).
6. Hàng đợi thử lại cục bộ (Offline Retry Queue) khi mất mạng hoặc server bận.
"""

import os
import re
import sys
import json
import time
import hashlib
import sqlite3
import logging
import configparser
from datetime import datetime
from pathlib import Path
import urllib.request
import urllib.error
import mimetypes

# Thiết lập ghi log
logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] [%(levelname)s] %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler("nanopix_sync_agent.log", encoding="utf-8")
    ]
)
logger = logging.getLogger("NanoPixAgent")

MAX_FILE_SIZE_BYTES = 25 * 1024 * 1024  # 25 MB

ORDER_CODE_PATTERN = re.compile(r"^(XQ\d+|CĐ\d+|CD\d+|CLS[-_A-Z0-9]+)$", re.IGNORECASE)
VISIT_CODE_PATTERN = re.compile(r"^(VIS\d+|KHAM[-_A-Z0-9]+)$", re.IGNORECASE)
PATIENT_CODE_PATTERN = re.compile(r"^BN[A-Z0-9_-]+$", re.IGNORECASE)
TOOTH_PATTERN = re.compile(r"^R\d{2}$", re.IGNORECASE)
DATE_TIME_TOKEN_PATTERN = re.compile(r"^(20\d{6}|\d{6}|study|img|xray|nanopix)$", re.IGNORECASE)


class NanoPixDatabase:
    """Quản lý cơ sở dữ liệu SQLite cục bộ chống gửi trùng và lưu hàng đợi retry."""
    def __init__(self, db_path: str):
        self.db_path = db_path
        self._init_db()

    def _get_conn(self):
        return sqlite3.connect(self.db_path)

    def _init_db(self):
        with self._get_conn() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS sent_files (
                    file_hash TEXT PRIMARY KEY,
                    file_path TEXT NOT NULL,
                    study_uid TEXT NOT NULL,
                    sent_at TEXT NOT NULL,
                    status TEXT NOT NULL
                )
            """)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS retry_queue (
                    file_path TEXT PRIMARY KEY,
                    file_hash TEXT NOT NULL,
                    study_uid TEXT,
                    attempts INTEGER DEFAULT 0,
                    last_attempt TEXT,
                    error_message TEXT
                )
            """)
            # Đảm bảo tương thích ngược nếu bảng retry_queue cũ chưa có cột study_uid
            cur = conn.cursor()
            cur.execute("PRAGMA table_info(retry_queue)")
            cols = [row[1] for row in cur.fetchall()]
            if "study_uid" not in cols:
                conn.execute("ALTER TABLE retry_queue ADD COLUMN study_uid TEXT")
            conn.commit()

    def is_already_sent(self, file_hash: str) -> bool:
        with self._get_conn() as conn:
            cur = conn.cursor()
            cur.execute("SELECT 1 FROM sent_files WHERE file_hash = ? AND status = 'SUCCESS'", (file_hash,))
            return cur.fetchone() is not None

    def mark_success(self, file_hash: str, file_path: str, study_uid: str):
        with self._get_conn() as conn:
            conn.execute("""
                INSERT OR REPLACE INTO sent_files (file_hash, file_path, study_uid, sent_at, status)
                VALUES (?, ?, ?, ?, 'SUCCESS')
            """, (file_hash, file_path, study_uid, datetime.now().isoformat()))
            conn.execute("DELETE FROM retry_queue WHERE file_path = ?", (file_path,))
            conn.commit()

    def add_to_retry(self, file_path: str, file_hash: str, study_uid: str, error_msg: str):
        with self._get_conn() as conn:
            conn.execute("""
                INSERT INTO retry_queue (file_path, file_hash, study_uid, attempts, last_attempt, error_message)
                VALUES (?, ?, ?, 1, ?, ?)
                ON CONFLICT(file_path) DO UPDATE SET
                    study_uid = COALESCE(retry_queue.study_uid, excluded.study_uid),
                    attempts = attempts + 1,
                    last_attempt = excluded.last_attempt,
                    error_message = excluded.error_message
            """, (file_path, file_hash, study_uid, datetime.now().isoformat(), error_msg))
            conn.commit()

    def get_pending_retries(self):
        with self._get_conn() as conn:
            cur = conn.cursor()
            cur.execute("SELECT file_path, file_hash, study_uid, attempts FROM retry_queue ORDER BY attempts ASC")
            return cur.fetchall()


class NanoPixSyncWorker:
    def __init__(self, config_file: str = "config.ini"):
        self.config = configparser.ConfigParser()
        if not os.path.exists(config_file):
            example_cfg = Path(__file__).parent / "config.example.ini"
            if example_cfg.exists():
                logger.warning(f"Chưa tìm thấy '{config_file}', sao chép từ '{example_cfg}'...")
                self.config.read(example_cfg, encoding="utf-8")
                with open(config_file, "w", encoding="utf-8") as f:
                    self.config.write(f)
            else:
                raise FileNotFoundError(f"Không tìm thấy file cấu hình: {config_file}")
        else:
            self.config.read(config_file, encoding="utf-8")

        self.api_url = self.config.get("SERVER", "api_url", fallback="http://localhost:8080/api/xray/ingest")
        # Ưu tiên biến môi trường NANOPIX_API_KEY nếu được cấu hình trên máy trạm
        self.api_key = os.environ.get("NANOPIX_API_KEY") or self.config.get("SERVER", "api_key", fallback="")
        self.watch_folder = self.config.get("NANOPIX", "watch_folder", fallback="C:/Eighteeth/NanoPix/Export")
        exts = self.config.get("NANOPIX", "file_extensions", fallback=".jpg,.jpeg,.png,.bmp,.dcm")
        self.valid_exts = tuple(e.strip().lower() for e in exts.split(","))

        self.device_serial = self.config.get("NANOPIX", "device_serial", fallback="EN1E0408A032")
        self.device_model = self.config.get("NANOPIX", "device_model", fallback="NanoPix1 (Eighteeth)")
        self.branch_id = self.config.get("NANOPIX", "branch_id", fallback="1")

        self.settle_seconds = self.config.getfloat("SYNC_OPTIONS", "file_write_settle_seconds", fallback=2.0)
        self.stability_checks = self.config.getint("SYNC_OPTIONS", "file_stability_checks", fallback=3)
        self.retry_interval = self.config.getint("SYNC_OPTIONS", "retry_interval_seconds", fallback=30)
        db_path = self.config.get("SYNC_OPTIONS", "local_db_path", fallback="./nanopix_agent_state.db")

        self.db = NanoPixDatabase(db_path)
        logger.info("Khởi động NanoPix Sync Agent.")
        logger.info(f" - Thư mục theo dõi: {self.watch_folder}")
        logger.info(f" - Server API: {self.api_url}")
        logger.info(f" - Device S/N: {self.device_serial} | Cơ sở ID: {self.branch_id}")
        logger.info(f" - Xác thực X-API-KEY: {'Đã cấu hình' if self.api_key else 'CHƯA CẤU HÌNH'}")

    @staticmethod
    def calculate_sha256(file_path: str) -> str:
        sha = hashlib.sha256()
        with open(file_path, "rb") as f:
            while chunk := f.read(65536):
                sha.update(chunk)
        return sha.hexdigest()

    @staticmethod
    def validate_file(file_path: str) -> tuple[bool, str]:
        """Kiểm tra tính hợp lệ của file trước khi gửi lên server."""
        if not os.path.exists(file_path):
            return False, "File không tồn tại"
        size = os.path.getsize(file_path)
        if size <= 0:
            return False, "File rỗng (0 bytes)"
        if size > MAX_FILE_SIZE_BYTES:
            return False, f"File vượt quá dung lượng tối đa 25MB ({size} bytes)"

        try:
            with open(file_path, "rb") as f:
                header = f.read(64)
            if header.startswith(b"MZ") or header.startswith(b"\x7fELF"):
                return False, "Phát hiện file thực thi giả mạo ảnh"
            lower_head = header.decode("ascii", errors="ignore").strip().lower()
            if lower_head.startswith("<script") or lower_head.startswith("<!doctype") or lower_head.startswith("<html") or lower_head.startswith("corrupt"):
                return False, "Nội dung file bị hỏng hoặc không phải định dạng ảnh/DICOM hợp lệ"
        except Exception as e:
            return False, f"Không thể đọc header file: {e}"

        return True, "OK"

    def wait_until_file_ready(self, file_path: str) -> bool:
        """Kiểm tra File Lock: Đảm bảo phần mềm Eighteeth đã ghi xong hoàn toàn file ảnh."""
        prev_size = -1
        stable_count = 0

        for _ in range(self.stability_checks * 4):
            try:
                with open(file_path, "rb"):
                    size = os.path.getsize(file_path)
                    if size > 0 and size == prev_size:
                        stable_count += 1
                        if stable_count >= self.stability_checks:
                            return True
                    else:
                        stable_count = 0
                        prev_size = size
            except (IOError, PermissionError):
                logger.debug(f"File '{file_path}' đang bị phần mềm Eighteeth ghi dữ liệu. Đang chờ...")
                stable_count = 0

            time.sleep(self.settle_seconds / max(1, self.stability_checks))

        logger.warning(f"File '{file_path}' không ổn định sau nhiều lần kiểm tra. Bỏ qua lượt này.")
        return False

    def parse_file_metadata(self, file_path: str, file_hash: str, existing_study_uid: str = None) -> dict:
        """
        Trích xuất thông tin định danh an toàn từ:
        1. File manifest sidecar '<stem>.json' (nếu có).
        2. Tên thư mục cha (nếu Eighteeth tạo thư mục theo bệnh nhân/ca chụp) và tên file ảnh.
        3. Tạo study_uid tất định dựa trên tên file + 12 ký tự đầu của SHA-256 (tuyệt đối không trùng lặp
           giữa các ảnh chụp cùng ngày và giữ nguyên khi retry).
        TUYỆT ĐỐI KHÔNG ĐOÁN BỪA MÃ BỆNH NHÂN HOẶC MÃ CHỈ ĐỊNH NẾU KHÔNG CÓ TIỀN TỐ RÕ RÀNG.
        """
        p = Path(file_path)
        stem = p.stem
        clean_stem = re.sub(r"[^A-Za-z0-9_-]", "_", stem)[:32]
        study_uid = existing_study_uid or f"NP_{clean_stem}_{file_hash[:12]}"

        meta = {
            "study_uid": study_uid,
            "order_item_id": "",
            "order_code": "",
            "visit_code": "",
            "external_patient_id": "",
            "tooth_numbers": "",
            "patient_name": ""
        }

        # 1. Đọc sidecar JSON nếu có (ví dụ: XQ812345.json đi kèm XQ812345.jpg)
        sidecar_path = p.with_suffix(".json")
        if sidecar_path.exists():
            try:
                with open(sidecar_path, "r", encoding="utf-8") as jf:
                    data = json.load(jf)
                if isinstance(data, dict):
                    meta["study_uid"] = str(data.get("externalStudyUid") or data.get("studyUid") or meta["study_uid"])
                    meta["order_item_id"] = str(data.get("orderItemId") or "")
                    meta["order_code"] = str(data.get("orderCode") or data.get("externalRefId") or "").upper()
                    meta["visit_code"] = str(data.get("visitCode") or "").upper()
                    meta["external_patient_id"] = str(data.get("externalPatientId") or data.get("patientCode") or "").upper()
                    meta["tooth_numbers"] = str(data.get("toothNumbers") or "").upper()
                    meta["patient_name"] = str(data.get("rawPatientName") or data.get("patientName") or "")
            except Exception as e:
                logger.warning(f"Không thể đọc file sidecar '{sidecar_path}': {e}")

        # 2. Phân tích token từ thư mục cha (nếu nằm dưới watch_folder) và tên file
        tokens_source = stem
        try:
            watch_root = Path(self.watch_folder).resolve()
            parent_dir = p.parent.resolve()
            if parent_dir != watch_root and watch_root in parent_dir.parents:
                tokens_source = f"{parent_dir.name}_{stem}"
        except Exception:
            pass

        # Tách token theo dấu cách, gạch dưới, gạch ngang, ngoặc vuông/tròn
        raw_tokens = [t.strip() for t in re.split(r"[\s_\-\[\]\(\)]+", tokens_source) if t.strip()]
        name_tokens = []
        teeth_found = []

        for tok in raw_tokens:
            upper_tok = tok.upper()
            if ORDER_CODE_PATTERN.match(upper_tok):
                if not meta["order_code"]:
                    meta["order_code"] = upper_tok
            elif VISIT_CODE_PATTERN.match(upper_tok):
                if not meta["visit_code"]:
                    meta["visit_code"] = upper_tok
            elif PATIENT_CODE_PATTERN.match(upper_tok):
                if not meta["external_patient_id"]:
                    meta["external_patient_id"] = upper_tok
            elif TOOTH_PATTERN.match(upper_tok):
                if upper_tok not in teeth_found:
                    teeth_found.append(upper_tok)
            elif DATE_TIME_TOKEN_PATTERN.match(upper_tok):
                continue
            else:
                name_tokens.append(tok)

        if teeth_found and not meta["tooth_numbers"]:
            meta["tooth_numbers"] = ",".join(teeth_found)

        if name_tokens and not meta["patient_name"]:
            meta["patient_name"] = " ".join(name_tokens)

        return meta

    def send_to_server(self, file_path: str, file_hash: str, existing_study_uid: str = None) -> bool:
        """Kiểm tra tính hợp lệ, đóng gói multipart/form-data và gửi tới endpoint /api/xray/ingest."""
        is_valid, reason = self.validate_file(file_path)
        if not is_valid:
            logger.error(f" [TỪ CHỐI FILE] '{os.path.basename(file_path)}': {reason}")
            return False

        meta = self.parse_file_metadata(file_path, file_hash, existing_study_uid)
        study_uid = meta["study_uid"]
        boundary = f"----WebKitFormBoundary{hashlib.md5(f'{study_uid}_{time.time()}'.encode()).hexdigest()}"

        fields = {
            "externalStudyUid": study_uid,
            "orderItemId": meta["order_item_id"],
            "orderCode": meta["order_code"],
            "visitCode": meta["visit_code"],
            "externalPatientId": meta["external_patient_id"],
            "toothNumbers": meta["tooth_numbers"],
            "rawPatientName": meta["patient_name"],
            "deviceSerialNumber": self.device_serial,
            "deviceModel": self.device_model,
            "branchId": str(self.branch_id),
            "notes": f"Tự động đồng bộ bởi Windows NanoPix Agent từ '{os.path.basename(file_path)}'"
        }

        body = bytearray()
        for k, v in fields.items():
            if v:
                body.extend(f"--{boundary}\r\n".encode("utf-8"))
                body.extend(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode("utf-8"))
                body.extend(f"{v}\r\n".encode("utf-8"))

        filename = os.path.basename(file_path)
        mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode("utf-8"))
        body.extend(f"Content-Type: {mime_type}\r\n\r\n".encode("utf-8"))
        with open(file_path, "rb") as f:
            body.extend(f.read())
        body.extend(b"\r\n")
        body.extend(f"--{boundary}--\r\n".encode("utf-8"))

        req = urllib.request.Request(self.api_url, data=bytes(body), method="POST")
        req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
        if self.api_key:
            req.add_header("X-API-KEY", self.api_key)

        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                if response.status in (200, 201):
                    logger.info(
                        f" [THÀNH CÔNG] Đã đồng bộ '{filename}' -> UID: {study_uid} "
                        f"(OrderCode: {meta['order_code'] or 'N/A'}, BN: {meta['external_patient_id'] or 'N/A'})"
                    )
                    self.db.mark_success(file_hash, file_path, study_uid)
                    return True
                else:
                    logger.error(f" [LỖI SERVER] HTTP {response.status}: {response.read().decode('utf-8', errors='ignore')}")
                    return False
        except urllib.error.HTTPError as e:
            err_body = e.read().decode("utf-8", errors="ignore")
            logger.error(f" [LỖI HTTP {e.code}] {err_body}")
            # Nếu server báo đã tồn tại Study UID (409 Conflict hoặc duplicate) -> ghi nhận tránh gửi lặp
            if e.code == 409 or "tồn tại" in err_body or "duplicate" in err_body.lower():
                self.db.mark_success(file_hash, file_path, study_uid)
                return True
            # Không đưa vào hàng đợi retry nếu lỗi 400 (file hỏng / dữ liệu không hợp lệ)
            if e.code == 400:
                return False
            self.db.add_to_retry(file_path, file_hash, study_uid, f"HTTP {e.code}: {err_body}")
            return False
        except Exception as ex:
            logger.error(f" [MẤT MẠNG / LỖI KẾT NỐI] Không thể gửi tới {self.api_url}: {ex}")
            self.db.add_to_retry(file_path, file_hash, study_uid, str(ex))
            return False

    def scan_and_process(self):
        """Quét thư mục xuất ảnh của Eighteeth để tìm file mới."""
        if not os.path.exists(self.watch_folder):
            logger.warning(f"Thư mục '{self.watch_folder}' chưa tồn tại hoặc chưa được mount.")
            return

        for root, _, files in os.walk(self.watch_folder):
            for file in files:
                if file.lower().endswith(self.valid_exts):
                    full_path = os.path.join(root, file)
                    if not self.wait_until_file_ready(full_path):
                        continue

                    try:
                        file_hash = self.calculate_sha256(full_path)
                    except Exception as e:
                        logger.error(f"Không thể đọc file '{full_path}': {e}")
                        continue

                    if self.db.is_already_sent(file_hash):
                        continue

                    logger.info(f"Phát hiện ảnh chụp X-quang mới: {file} (SHA: {file_hash[:10]}...)")
                    self.send_to_server(full_path, file_hash)

    def process_retry_queue(self):
        """Thử lại các file chưa gửi được khi mạng hồi phục."""
        pending = self.db.get_pending_retries()
        if not pending:
            return

        logger.info(f"Đang xử lý hàng đợi thử lại ({len(pending)} file chờ gửi)...")
        for file_path, file_hash, study_uid, attempts in pending:
            if not os.path.exists(file_path):
                logger.warning(f"File trong hàng đợi không còn tồn tại: {file_path}")
                continue

            logger.info(f"Thử gửi lại lần {attempts + 1}: {os.path.basename(file_path)}")
            if self.send_to_server(file_path, file_hash, existing_study_uid=study_uid):
                time.sleep(1)

    def run(self):
        logger.info("NanoPix Sync Agent đang hoạt động. Nhấn Ctrl+C để dừng.")
        last_retry_check = 0

        while True:
            try:
                self.scan_and_process()

                now = time.time()
                if now - last_retry_check > self.retry_interval:
                    self.process_retry_queue()
                    last_retry_check = now

                time.sleep(3)
            except KeyboardInterrupt:
                logger.info("Đã dừng NanoPix Sync Agent.")
                break
            except Exception as e:
                logger.error(f"Lỗi vòng lặp giám sát: {e}", exc_info=True)
                time.sleep(5)


if __name__ == "__main__":
    worker = NanoPixSyncWorker("config.ini")
    worker.run()
