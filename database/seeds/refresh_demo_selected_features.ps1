<#
.SYNOPSIS
    Script làm mới và nạp dữ liệu Demo sạch (Clean Demo Seed Data)
    dành riêng cho các phân hệ đã chọn:
    - FT03–04: Hồ sơ Bệnh nhân (SCR-03.1 - SCR-03.4 & SCR-04.1 - SCR-04.2)
    - FT35–36: Người phụ thuộc & Tách tài khoản (SCR-35.1 - SCR-35.3 & SCR-36.1 - SCR-36.3)
    - FT05–06: Lịch hẹn & Đặt lịch Online (SCR-05.1 - SCR-05.4 & SCR-06.1 - SCR-06.4)
    - FT07–10: Tiếp nhận, Điều phối Ghế & Hàng đợi (SCR-07.1 - SCR-10.4)

.DESCRIPTION
    Script thực thi nạp file `seed_clean_demo_selected_features.sql` một cách an toàn,
    idempotent (có thể chạy lại nhiều lần mà không tạo trùng bản ghi), bảo đảm
    chuẩn mã hóa UTF-8 tiếng Việt hoàn chỉnh, không làm thay đổi hay xóa dữ liệu
    của các module ngoài phạm vi.

.PARAMETER Backup
    Tự động sao lưu database ra thư mục database/backups trước khi nạp dữ liệu.

.EXAMPLE
    .\database\seeds\refresh_demo_selected_features.ps1
    .\database\seeds\refresh_demo_selected_features.ps1 -Backup
#>
[CmdletBinding()]
param(
    [switch]$Backup
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$propertiesPath = Join-Path $projectRoot 'src\main\resources\application.properties'
$seedSqlPath = Join-Path $projectRoot 'database\seeds\seed_clean_demo_selected_features.sql'

# Tìm mysql.exe và mysqldump.exe
$mysqlCandidates = @(@(
    (Get-Command mysql -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source),
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
) | Where-Object { $_ -and (Test-Path $_) })

if (-not (Test-Path $propertiesPath)) { throw "Không tìm thấy cấu hình: $propertiesPath" }
if (-not (Test-Path $seedSqlPath)) { throw "Không tìm thấy file seed: $seedSqlPath" }
if ($mysqlCandidates.Count -eq 0) { throw 'Không tìm thấy mysql.exe trong PATH hoặc thư mục tiêu chuẩn.' }

$mysql = $mysqlCandidates[0]
$mysqlBinDir = Split-Path $mysql -Parent
$mysqldump = Join-Path $mysqlBinDir 'mysqldump.exe'

# Đọc cấu hình kết nối từ application.properties
$properties = Get-Content $propertiesPath
function Get-Property([string]$key) {
    $line = $properties | Where-Object { $_ -match "^$([regex]::Escape($key))=" } | Select-Object -First 1
    if (-not $line) { throw "Thiếu cấu hình $key trong application.properties" }
    return ($line -split '=', 2)[1].Trim()
}

$dbUser = Get-Property 'spring.datasource.username'
$dbPassword = Get-Property 'spring.datasource.password'
$url = Get-Property 'spring.datasource.url'
$match = [regex]::Match($url, '/([^/?]+)\?')
if (-not $match.Success) { throw 'Không xác định được database name từ spring.datasource.url.' }
$dbName = $match.Groups[1].Value

$env:MYSQL_PWD = $dbPassword

try {
    Write-Host "=================================================================" -ForegroundColor Cyan
    Write-Host "  HỆ THỐNG REFRESH CLEAN DEMO SEED DATA (SEP490_G26)             " -ForegroundColor Green
    Write-Host "  Database: $dbName | User: $dbUser                             " -ForegroundColor Gray
    Write-Host "=================================================================" -ForegroundColor Cyan

    # 1. Tùy chọn Backup
    if ($Backup) {
        $backupDir = Join-Path $projectRoot 'database\backups'
        if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Path $backupDir -Force | Out-Null }
        $timestamp = Get-Date -Format 'yyyyMMdd_HHmmss'
        $backupFile = Join-Path $backupDir "cv_dcms_db_backup_$timestamp.sql"
        Write-Host "Đang sao lưu database trước khi nạp: $backupFile ..." -ForegroundColor Yellow
        & $mysqldump --protocol=tcp --default-character-set=utf8mb4 -u $dbUser $dbName --result-file=$backupFile
        if ($LASTEXITCODE -ne 0) { throw "Sao lưu database thất bại!" }
        Write-Host "Sao lưu thành công! Kích thước: $((Get-Item $backupFile).Length) bytes" -ForegroundColor Green
    }

    # 2. Thực thi Seed SQL
    $sourcePath = $seedSqlPath.Replace('\', '/')
    Write-Host "Đang nạp file seed demo sạch: $sourcePath ..." -ForegroundColor Cyan
    & $mysql --protocol=tcp --default-character-set=utf8mb4 -u $dbUser $dbName `
        -e "SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci; SOURCE $sourcePath;"

    if ($LASTEXITCODE -ne 0) {
        throw "Thực thi seed_clean_demo_selected_features.sql thất bại với mã lỗi $LASTEXITCODE."
    }

    Write-Host "Nạp dữ liệu SQL thành công!" -ForegroundColor Green

    # 3. Kiểm tra và thống kê số liệu sau khi nạp
    Write-Host "`n---------------- THỐNG KÊ DỮ LIỆU DEMO SAU KHI REFRESH ----------------" -ForegroundColor White

    # Đếm số bệnh nhân
    $patientCount = (& $mysql --protocol=tcp --default-character-set=utf8mb4 -N -B -u $dbUser $dbName `
        -e "SELECT COUNT(*) FROM patient_profiles WHERE status = 'ACTIVE';").Trim()
    Write-Host "[FT03-04] Tổng số hồ sơ bệnh nhân đang ACTIVE trong hệ thống: $patientCount" -ForegroundColor Green

    # Kiểm tra FT35-36 (Gia đình user patient_an)
    Write-Host "[FT35-36] Danh sách hồ sơ thuộc gia đình user 'patient_an' (ID=4):" -ForegroundColor Magenta
    & $mysql --protocol=tcp --default-character-set=utf8mb4 -u $dbUser $dbName `
        -e "SET NAMES utf8mb4; SELECT p.profile_id, p.patient_code, p.full_name, p.dob, p.gender, p.guardian_name, a.access_type, p.is_default 
            FROM account_patient_profiles a 
            JOIN patient_profiles p ON a.profile_id = p.profile_id 
            WHERE a.account_id = 4 ORDER BY p.is_default DESC, p.profile_id ASC;"

    # Kiểm tra hồ sơ đã tách FT36
    Write-Host "[FT36] Hồ sơ đã tách tài khoản thành công:" -ForegroundColor Magenta
    & $mysql --protocol=tcp --default-character-set=utf8mb4 -u $dbUser $dbName `
        -e "SET NAMES utf8mb4; SELECT p.profile_id, p.patient_code, p.full_name, p.phone_number, p.user_id, p.previous_user_id, p.separated_at 
            FROM patient_profiles p WHERE p.previous_user_id IS NOT NULL;"

    # Kiểm tra FT05-06 (Lịch hẹn hôm nay và sắp tới)
    Write-Host "`n[FT05-06] Lịch hẹn demo hôm nay (CURDATE()) theo trạng thái:" -ForegroundColor Cyan
    & $mysql --protocol=tcp --default-character-set=utf8mb4 -u $dbUser $dbName `
        -e "SET NAMES utf8mb4; SELECT appointment_code, appointment_date, expected_time, status, source, initial_symptoms 
            FROM appointments WHERE appointment_date = CURDATE() ORDER BY expected_time ASC LIMIT 6;"

    # Kiểm tra trạng thái ghế FT08
    Write-Host "`n[FT08] Trạng thái ghế nha khoa tại Chi nhánh Quận 1 (CN01):" -ForegroundColor Cyan
    & $mysql --protocol=tcp --default-character-set=utf8mb4 -u $dbUser $dbName `
        -e "SET NAMES utf8mb4; SELECT chair_code, chair_name, status FROM dental_chairs WHERE branch_id = 1 ORDER BY chair_id ASC;"

    # Kiểm tra Bảng hàng chờ & TV Queue FT10
    Write-Host "`n[FT10] Danh sách vé hàng chờ hôm nay (CURDATE()) - Phù hợp Kanban Board & TV Queue:" -ForegroundColor Green
    & $mysql --protocol=tcp --default-character-set=utf8mb4 -u $dbUser $dbName `
        -e "SET NAMES utf8mb4; SELECT q.display_number, q.is_priority, q.is_emergency, q.status, v.visit_code, p.full_name, s.full_name AS doctor_name, c.chair_name, q.priority_reason, q.pause_reason 
            FROM queue_tickets q 
            JOIN visits v ON q.visit_id = v.visit_id 
            JOIN patient_profiles p ON v.profile_id = p.profile_id 
            LEFT JOIN staff_profiles s ON v.primary_doctor_id = s.staff_id 
            LEFT JOIN dental_chairs c ON v.chair_id = c.chair_id 
            WHERE q.branch_id = 1 AND q.queue_date = CURDATE() 
            ORDER BY q.ticket_number ASC;"

    Write-Host "`n=================================================================" -ForegroundColor Cyan
    Write-Host " REFRESH CLEAN DEMO DATA HOÀN TẤT THÀNH CÔNG!                     " -ForegroundColor Green
    Write-Host " Các màn hình FT03-04, FT35-36, FT05-06, FT07-10 đã sẵn sàng demo." -ForegroundColor Green
    Write-Host "=================================================================" -ForegroundColor Cyan
}
catch {
    Write-Host "LỖI KHI REFRESH DEMO DATA: $_" -ForegroundColor Red
    throw $_
}
finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

