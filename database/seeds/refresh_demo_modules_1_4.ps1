<#
.SYNOPSIS
    Script nạp dữ liệu Seed sạch dành riêng cho Demo Hội đồng (Module 1 - 4: Lịch hẹn, Tiếp đón, Điều phối, Hàng chờ & Tivi)
    Đảm bảo 100% chuẩn tiếng Việt UTF-8 (utf8mb4), không lỗi font chữ, an toàn và idempotent.

.DESCRIPTION
    Tự động đọc cấu hình kết nối từ src/main/resources/application.properties,
    thực thi file database/seeds/seed_demo_modules_1_4.sql với charset utf8mb4,
    sau đó kiểm tra và in ra tóm tắt dữ liệu demo sẵn sàng phục vụ báo cáo.

.EXAMPLE
    .\database\seeds\refresh_demo_modules_1_4.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$propertiesPath = Join-Path $projectRoot 'src\main\resources\application.properties'
$seedSqlPath = Join-Path $projectRoot 'database\seeds\seed_demo_modules_1_4.sql'

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  NẠP DỮ LIỆU SEED CHUẨN DEMO HỘI ĐỒNG (MODULE 1 - 4) - CV-DCMS " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# 1. Kiểm tra file cấu hình và file sql
if (-not (Test-Path $propertiesPath)) { throw "Không tìm thấy cấu hình: $propertiesPath" }
if (-not (Test-Path $seedSqlPath)) { throw "Không tìm thấy file seed: $seedSqlPath" }

# 2. Tìm mysql.exe
$mysqlCandidates = @(@(
    (Get-Command mysql -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source),
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
) | Where-Object { $_ -and (Test-Path $_) })

if ($mysqlCandidates.Count -eq 0) { throw 'Không tìm thấy mysql.exe trong PATH hoặc thư mục tiêu chuẩn C:\Program Files\MySQL.' }
$mysql = $mysqlCandidates[0]

# 3. Đọc thông tin kết nối
$properties = Get-Content $propertiesPath
function Get-Property([string]$key) {
    $line = $properties | Where-Object { $_ -match "^$([regex]::Escape($key))=" } | Select-Object -First 1
    if (-not $line) { throw "Thiếu cấu hình $key trong application.properties" }
    return ($line -split '=', 2)[1].Trim()
}

$dbUser = Get-Property 'spring.datasource.username'
$dbPassword = Get-Property 'spring.datasource.password'
$dbUrl = Get-Property 'spring.datasource.url'

$dbHost = 'localhost'
$dbPort = 3306
$dbName = 'cv_dcms_db'

if ($dbUrl -match 'jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)') {
    $dbHost = $matches[1]
    if ($matches[2]) { $dbPort = [int]$matches[2] }
    $dbName = $matches[3]
}

Write-Host "-> Kết nối: $dbUser@$dbHost:$dbPort/$dbName" -ForegroundColor Yellow

# 4. Thực thi nạp file SQL
Write-Host "-> Đang thực thi nạp dữ liệu sạch từ seed_demo_modules_1_4.sql..." -ForegroundColor Yellow
$process = Start-Process -FilePath $mysql -ArgumentList @(
    "-h$dbHost",
    "-P$dbPort",
    "-u$dbUser",
    "-p$dbPassword",
    "--default-character-set=utf8mb4",
    $dbName,
    "-e", "source $seedSqlPath"
) -NoNewWindow -Wait -PassThru

if ($process.ExitCode -ne 0) {
    throw "Thực thi SQL thất bại với Exit Code: $($process.ExitCode)"
}

Write-Host "-> Nạp dữ liệu seed thành công!" -ForegroundColor Green
Write-Host ""

# 5. Kiểm tra và xuất bảng tóm tắt các ca demo sẵn sàng
Write-Host "===================== TÓM TẮT DỮ LIỆU DEMO SẴN SÀNG =====================" -ForegroundColor Cyan

$verifySql = @"
SELECT '1. Lịch hẹn hôm nay' AS Module, appointment_code AS Ma, p.full_name AS BenhNhan, a.status AS TrangThai, expected_time AS Gio
FROM appointments a JOIN patient_profiles p ON a.profile_id = p.profile_id
WHERE a.appointment_code LIKE 'LH_DEMO_%'
UNION ALL
SELECT '2. Chờ điều phối ghế', visit_code, p.full_name, v.status, TIME(check_in_at)
FROM visits v JOIN patient_profiles p ON v.profile_id = p.profile_id
WHERE v.visit_code = 'VIS_DEMO_04'
UNION ALL
SELECT '3. Hàng chờ & Tivi sảnh', display_number, p.full_name, t.status, TIME(issued_at)
FROM queue_tickets t JOIN visits v ON t.visit_id = v.visit_id JOIN patient_profiles p ON v.profile_id = p.profile_id
WHERE v.visit_code IN ('VIS_DEMO_06', 'VIS_DEMO_07', 'VIS_DEMO_08')
ORDER BY Module, Ma;
"@

& $mysql "-h$dbHost" "-P$dbPort" "-u$dbUser" "-p$dbPassword" "--default-character-set=utf8mb4" $dbName -e $verifySql

Write-Host "=========================================================================" -ForegroundColor Cyan
Write-Host "BẠN ĐÃ SẴN SÀNG ĐỂ DEMO RA HỘI ĐỒNG VỚI CÁC LUỒNG ĐÃ ĐƯỢC CHUẨN HÓA!" -ForegroundColor Green
