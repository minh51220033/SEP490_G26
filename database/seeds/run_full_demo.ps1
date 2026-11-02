<#
Nạp dữ liệu demo UTF-8 cho hành trình: đặt lịch -> check-in -> phân công -> cấp số
-> khám/chẩn đoán -> bệnh án điện tử.

Ví dụ:
  .\database\seeds\run_full_demo.ps1
  .\database\seeds\run_full_demo.ps1 -ResetEmrDemo
#>
[CmdletBinding()]
param(
    [switch]$ResetEmrDemo
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$propertiesPath = Join-Path $projectRoot 'src\main\resources\application.properties'
$mysqlCandidates = @(@(
    (Get-Command mysql -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source),
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
) | Where-Object { $_ -and (Test-Path $_) })

if (-not (Test-Path $propertiesPath)) { throw "Không tìm thấy $propertiesPath" }
if ($mysqlCandidates.Count -eq 0) { throw 'Không tìm thấy mysql.exe. Cài MySQL Client hoặc thêm mysql vào PATH.' }

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
if (-not $match.Success) { throw 'Không xác định được tên database từ spring.datasource.url.' }
$dbName = $match.Groups[1].Value
$mysql = $mysqlCandidates[0]

# Chỉ tồn tại trong tiến trình PowerShell hiện tại; không in mật khẩu ra màn hình.
$env:MYSQL_PWD = $dbPassword

function Invoke-DbFile([string]$relativePath) {
    $path = Join-Path $projectRoot $relativePath
    if (-not (Test-Path $path)) { throw "Không tìm thấy file SQL: $relativePath" }
    $sourcePath = $path.Replace('\', '/')
    Write-Host "Nạp $relativePath" -ForegroundColor Cyan
    & $mysql --protocol=tcp --default-character-set=utf8mb4 -u $dbUser $dbName `
        -e "SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci; SOURCE $sourcePath;"
    if ($LASTEXITCODE -ne 0) { throw "Nạp thất bại: $relativePath" }
}

function Invoke-DbValue([string]$sql) {
    $value = & $mysql --protocol=tcp --default-character-set=utf8mb4 -N -B -u $dbUser $dbName -e $sql
    if ($LASTEXITCODE -ne 0) { throw 'Không thể kiểm tra cấu trúc database.' }
    return ($value | Select-Object -First 1).Trim()
}

try {
    $hasBranches = Invoke-DbValue "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$dbName' AND table_name='branches';"
    if ($hasBranches -ne '1') {
        throw 'Schema nền chưa tồn tại. Hãy chạy ứng dụng Spring Boot một lần để Hibernate tạo schema nền, rồi chạy lại script.'
    }

    # V7/V8 không idempotent, nên chỉ chạy khi chưa có constraint tương ứng.
    $hasCheckInConstraint = Invoke-DbValue "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='$dbName' AND table_name='visits' AND constraint_name='uk_ft07_visit_appointment';"
    if ($hasCheckInConstraint -eq '0') { Invoke-DbFile 'database/migrations/V7__ft07_checkin_integrity.sql' }

    $hasDispatchConstraint = Invoke-DbValue "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='$dbName' AND table_name='queue_tickets' AND constraint_name='uk_ft09_visit_queue';"
    if ($hasDispatchConstraint -eq '0') { Invoke-DbFile 'database/migrations/V17__ft08_ft09_dispatch_ticket.sql' }

    Invoke-DbFile 'database/migrations/V11__emr_module.sql'
    Invoke-DbFile 'database/migrations/V12__emr_printable_components.sql'
    Invoke-DbFile 'database/migrations/V14__emr_attachments.sql'
    Invoke-DbFile 'database/migrations/V16__emr_doctor_only_signing.sql'
    $demoImageDir = Join-Path $projectRoot 'uploads\clinical_photos'
    New-Item -ItemType Directory -Path $demoImageDir -Force | Out-Null
    $demoImageTarget = Join-Path $demoImageDir 'emr-demo-odontogram.png'
    if (-not (Test-Path $demoImageTarget)) {
        Copy-Item -LiteralPath (Join-Path $projectRoot 'src\main\resources\static\images\fdi-chart-pure.png') -Destination $demoImageTarget
    }
    if ($ResetEmrDemo) { Invoke-DbFile 'database/seeds/reset_emr_demo.sql' }

    # FT07/08/09/10 cung cấp tình huống tiếp đón, điều phối và hàng chờ.
    Invoke-DbFile 'database/seeds/seed_ft07_reception.sql'
    Invoke-DbFile 'database/seeds/seed_ft08_ft09_dispatch.sql'
    Invoke-DbFile 'database/seeds/seed_ft10_queue.sql'
    Invoke-DbFile 'database/seeds/repair_demo_utf8.sql'
    Invoke-DbFile 'database/seeds/seed_emr_full_demo.sql'

    Write-Host ''
    Write-Host 'Hoàn tất. Tài khoản: emr_demo_doctor / Hieu1811; emr_demo_patient / Hieu1811; reception_ft07 / Hieu1811.' -ForegroundColor Green
    Write-Host 'Xem database/EMR_DEMO_SCRIPT.md để chạy hành trình demo đầy đủ.' -ForegroundColor Green
}
finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
