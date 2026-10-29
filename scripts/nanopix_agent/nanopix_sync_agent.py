"""
NANOPIX X-RAY WINDOWS BACKGROUND SYNC WORKER (CV-DCMS)
------------------------------------------------------
Chương trình chạy nền trên máy trạm Windows tại phòng chụp X-quang:
1. Giám sát thư mục xuất ảnh của phần mềm Eighteeth NanoPix1.
2. Kiểm tra File Lock (chờ phần mềm Eighteeth ghi xong toàn bộ file ảnh).
3. Chống gửi trùng lặp tuyệt đối bằng mã băm SHA-256 và SQLite local cache.
4. Gửi ảnh + metadata qua POST /api/xray/ingest.
5. Hàng đợi thử lại cục bộ (Offline Retry Queue) khi mất mạng hoặc server bận.
"""

import os
import sys
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
                    attempts INTEGER DEFAULT 0,
                    last_attempt TEXT,
                    error_message TEXT
                )
            """)
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

    def add_to_retry(self, file_path: str, file_hash: str, error_msg: str):
        with self._get_conn() as conn:
            conn.execute("""
                INSERT INTO retry_queue (file_path, file_hash, attempts, last_attempt, error_message)
                VALUES (?, ?, 1, ?, ?)
                ON CONFLICT(file_path) DO UPDATE SET
                    attempts = attempts + 1,
                    last_attempt = excluded.last_attempt,
                    error_message = excluded.error_message
            """, (file_path, file_hash, datetime.now().isoformat(), error_msg))
            conn.commit()

    def get_pending_retries(self):
        with self._get_conn() as conn:
            cur = conn.cursor()
            cur.execute("SELECT file_path, file_hash, attempts FROM retry_queue ORDER BY attempts ASC")
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
        self.api_key = self.config.get("SERVER", "api_key", fallback="")
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
        logger.info(f"Khởi động NanoPix Sync Agent.")
        logger.info(f" - Thư mục theo dõi: {self.watch_folder}")
        logger.info(f" - Server API: {self.api_url}")
        logger.info(f" - Device S/N: {self.device_serial} | Cơ sở ID: {self.branch_id}")

    @staticmethod
    def calculate_sha256(file_path: str) -> str:
        sha = hashlib.sha256()
        with open(file_path, "rb") as f:
            while chunk := f.read(65536):
                sha.update(chunk)
        return sha.hexdigest()

    def wait_until_file_ready(self, file_path: str) -> bool:
        """Kiểm tra File Lock: Đảm bảo phần mềm Eighteeth đã ghi xong hoàn toàn file ảnh."""
        prev_size = -1
        stable_count = 0

        for _ in range(self.stability_checks * 4):
            try:
                # Thử mở file ở chế độ độc quyền đọc để kiểm tra lock
                with open(file_path, "rb") as f:
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

            time.sleep(self.settle_seconds / self.stability_checks)

        logger.warning(f"File '{file_path}' không ổn định sau nhiều lần kiểm tra. Bỏ qua lượt này.")
        return False

    def parse_file_metadata(self, filename: str) -> dict:
        """
        Trích xuất thông tin an toàn từ tên file mà phần mềm Eighteeth sinh ra.
        Cú pháp thường gặp của Eighteeth: '20261025_193542 [Tên bệnh nhân]' hoặc 'Study_202610251935'.
        TUYỆT ĐỐI KHÔNG TỰ ĐOÁN MÃ BỆNH NHÂN (patientCode) nếu không có tiền tố BN rõ ràng.
        """
        stem = Path(filename).stem
        study_uid = f"NANOPIX_{stem}_{int(time.time())}"
        patient_name = ""
        external_patient_id = ""

        # Nếu tên file có phần trong ngoặc hoặc dấu gạch nối
        if "_" in stem:
            parts = stem.split("_", 1)
            study_uid = f"NANOPIX_{parts[0]}"
            if len(parts) > 1 and parts[1].strip():
                raw = parts[1].strip().replace("_", " ")
                # Nếu người chụp gõ 'BN000123 Nguyen Van A' -> trích xuất mã BN
                tokens = raw.split()
                if tokens and tokens[0].upper().startswith("BN"):
                    external_patient_id = tokens[0].upper()
                    patient_name = " ".join(tokens[1:])
                else:
                    patient_name = raw
        else:
            patient_name = stem

        return {
            "study_uid": study_uid,
            "patient_name": patient_name,
            "external_patient_id": external_patient_id
        }

    def send_to_server(self, file_path: str, file_hash: str) -> bool:
        """Đóng gói multipart/form-data và gửi tới endpoint /api/xray/ingest."""
        meta = self.parse_file_metadata(os.path.basename(file_path))
        boundary = f"----WebKitFormBoundary{hashlib.md5(str(time.time()).encode()).hexdigest()}"

        fields = {
            "externalStudyUid": meta["study_uid"],
            "rawPatientName": meta["patient_name"],
            "externalPatientId": meta["external_patient_id"],
            "deviceSerialNumber": self.device_serial,
            "deviceModel": self.device_model,
            "branchId": str(self.branch_id),
            "notes": f"Tự động đồng bộ bởi Windows NanoPix Agent từ '{os.path.basename(file_path)}'"
        }

        body = bytearray()
        for k, v in fields.items():
            if v:
                body.extend(f"--{boundary}\r\n".encode())
                body.extend(f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode())
                body.extend(f"{v}\r\n".encode())

        # Thêm file ảnh
        filename = os.path.basename(file_path)
        mime_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        body.extend(f"--{boundary}\r\n".encode())
        body.extend(f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode())
        body.extend(f"Content-Type: {mime_type}\r\n\r\n".encode())
        with open(file_path, "rb") as f:
            body.extend(f.read())
        body.extend(b"\r\n")
        body.extend(f"--{boundary}--\r\n".encode())

        req = urllib.request.Request(self.api_url, data=bytes(body), method="POST")
        req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
        if self.api_key:
            req.add_header("X-API-KEY", self.api_key)

        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                if response.status in (200, 201):
                    logger.info(f" [THÀNH CÔNG] Đã đồng bộ '{filename}' -> Server UID: {meta['study_uid']}")
                    self.db.mark_success(file_hash, file_path, meta["study_uid"])
                    return True
                else:
                    logger.error(f" [LỖI SERVER] HTTP {response.status}: {response.read().decode('utf-8', errors='ignore')}")
                    return False
        except urllib.error.HTTPError as e:
            err_body = e.read().decode('utf-8', errors='ignore')
            logger.error(f" [LỖI HTTP {e.code}] {err_body}")
            # Nếu server báo đã tồn tại Study UID (400 hoặc duplicate) -> ghi nhận tránh spam
            if "tồn tại" in err_body or "duplicate" in err_body.lower():
                self.db.mark_success(file_hash, file_path, meta["study_uid"])
                return True
            self.db.add_to_retry(file_path, file_hash, f"HTTP {e.code}: {err_body}")
            return False
        except Exception as ex:
            logger.error(f" [MẤT MẠNG / LỖI KẾT NỐI] Không thể gửi tới {self.api_url}: {ex}")
            self.db.add_to_retry(file_path, file_hash, str(ex))
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
        for file_path, file_hash, attempts in pending:
            if not os.path.exists(file_path):
                logger.warning(f"File trong hàng đợi không còn tồn tại: {file_path}")
                continue

            logger.info(f"Thử gửi lại lần {attempts + 1}: {os.path.basename(file_path)}")
            if self.send_to_server(file_path, file_hash):
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
