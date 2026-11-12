<# Runs the isolated appointment/queue demo seed using datasource settings from application.properties. #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$properties = Get-Content (Join-Path $projectRoot 'src\main\resources\application.properties')
function Get-Value([string]$key) { (($properties | Where-Object { $_ -match "^$([regex]::Escape($key))=" } | Select-Object -First 1) -split '=', 2)[1].Trim() }
$url = Get-Value 'spring.datasource.url'; $user = Get-Value 'spring.datasource.username'; $password = Get-Value 'spring.datasource.password'
if ($url -notmatch 'jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)') { throw "Không phân tích được datasource URL: $url" }
$dbHost = $Matches[1]
$dbPort = if ($Matches[2]) { $Matches[2] } else { '3306' }
$dbName = $Matches[3]
$mysql = @((Get-Command mysql -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source),'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe','C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe') | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (!$mysql) { throw 'Không tìm thấy mysql.exe.' }
$env:MYSQL_PWD = $password
try { & $mysql --host=$dbHost --port=$dbPort --user=$user --default-character-set=utf8mb4 $dbName --execute="source $PSScriptRoot/seed_clean_appointments_2026_11_08_to_12_08.sql" } finally { Remove-Item Env:\MYSQL_PWD -ErrorAction SilentlyContinue }
