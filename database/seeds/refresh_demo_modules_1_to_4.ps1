<#
.SYNOPSIS
    Script nạp dữ liệu Demo sạch (Clean Demo Seed) cho Module 1 - 4:
    - Module 1: Quản lý lịch hẹn (/reception/appointments)
    - Module 2: Tiếp đón & Check-in (/reception/check-in)
    - Module 4: Điều phối ghế nha & Lượt khám (/reception/dispatch)
    - Module 3: Hàng chờ khám bệnh & Màn hình Tivi sảnh (/reception/queue & /reception/queue/tv/1)

.DESCRIPTION
    Tự động đọc thông tin MySQL từ application.properties, thực thi file
    `seed_demo_modules_1_to_4.sql` với mã hóa UTF-8 hoàn chỉnh.
    Có thể chạy lặp lại bất kỳ lúc nào để đưa hệ thống về trạng thái demo chuẩn.

.EXAMPLE
    .\database\seeds\refresh_demo_modules_1_to_4.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$propertiesPath = Join-Path $projectRoot 'src\main\resources\application.properties'
$seedSqlPath = Join-Path $PSScriptRoot 'seed_demo_modules_1_to_4.sql'

# Tìm mysql.exe
$mysqlCandidates = @(@(
    (Get-Command mysql -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source),
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe'
) | Where-Object { $_ -and (Test-Path $_) })

if (-not (Test-Path $propertiesPath)) { throw "Không tìm thấy cấu hình: $propertiesPath" }
if (-not (Test-Path $seedSqlPath)) { throw "Không tìm thấy file seed: $seedSqlPath" }
if ($mysqlCandidates.Count -eq 0) { throw 'Không tìm thấy mysql.exe trong PATH hoặc thư mục tiêu chuẩn.' }

$mysql = $mysqlCandidates[0]

# Đọc cấu hình từ application.properties
$properties = Get-Content $propertiesPath
function Get-Property([string]$key) {
    $line = $properties | Where-Object { $_ -match "^$([regex]::Escape($key))=" } | Select-Object -First 1
    if (-not $line) { throw "Thiếu cấu hình $key trong application.properties" }
    return ($line -split '=', 2)[1].Trim()
}

$dbUser = Get-Property 'spring.datasource.username'
$dbPassword = Get-Property 'spring.datasource.password'
$dbUrl = Get-Property 'spring.datasource.url'

if ($dbUrl -match 'jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)') {
    $dbHost = $Matches[1]
    $dbPort = if ($Matches[2]) { $Matches[2] } else { '3306' }
    $dbName = $Matches[3]
} else {
    throw "Không phân tích được URL kết nối: $dbUrl"
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " NẠP DỮ LIỆU DEMO CV-DCMS (MODULE 1 - 4)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Host:     ${dbHost}:${dbPort}" -ForegroundColor Gray
Write-Host " Database: $dbName" -ForegroundColor Gray
Write-Host " File:     seed_demo_modules_1_to_4.sql" -ForegroundColor Gray
Write-Host ""

$env:MYSQL_PWD = $dbPassword
try {
    & $mysql --host=$dbHost --port=$dbPort --user=$dbUser --default-character-set=utf8mb4 $dbName --execute="source $seedSqlPath"
    Write-Host "[THÀNH CÔNG] Dữ liệu demo cho 4 Module đã được nạp hoàn tất!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Dữ liệu sẵn sàng kiểm thử:" -ForegroundColor Yellow
    Write-Host "  1. Quan ly lich hen:  LH_DEMO_02 (Tran Thi Mai Anh - Cho xac nhan, Di ung Lidocaine)" -ForegroundColor White
    Write-Host "                         LH_DEMO_08 (Le Quoc Hung - Da xac nhan, san sang Check-in)" -ForegroundColor White
    Write-Host "  2. Ban Tiep don:      Ma LH_DEMO_02 hoac SDT 0901234567 (Canh bao Y te do)" -ForegroundColor White
    Write-Host "  3. Ban Dieu phoi ghe: VIS_DEMO_03 (Le Hoang Long - Cho gan Bac si & Ghe nha)" -ForegroundColor White
    Write-Host "  4. Bang Hang cho & TV: So A-001 (Pham Thuy Linh - Dang cho goi so)" -ForegroundColor White
    Write-Host "                         So A-002 (Vu Minh Khang - San sang bam GOI LAI kiem chung CV-05)" -ForegroundColor White
    Write-Host "                         So A-003 (Dang Ngoc Mai - Dang kham tai ghe tren TV)" -ForegroundColor White
    Write-Host "==========================================================" -ForegroundColor Cyan
} finally {
    Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue
}
