param(
    [string]$DbHost = $env:DB_HOST,
    [int]$DbPort = $(if ($env:DB_PORT) { [int]$env:DB_PORT } else { 3306 }),
    [string]$DbName = $(if ($env:DB_NAME) { $env:DB_NAME } else { 'mingli' }),
    [string]$DbUser = $env:DB_USERNAME,
    [string]$DbPassword = $env:DB_PASSWORD
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($DbHost) -or [string]::IsNullOrWhiteSpace($DbUser) -or [string]::IsNullOrWhiteSpace($DbPassword)) {
    throw '请通过 DB_HOST、DB_USERNAME、DB_PASSWORD 环境变量提供数据库连接信息。'
}

$schemaPath = Join-Path $PSScriptRoot 'src\main\resources\db\schema.sql'
if (-not (Test-Path -LiteralPath $schemaPath)) {
    throw "找不到数据库脚本：$schemaPath"
}

$mysqlCandidates = @(
    (Get-Command mysql.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
    'C:\xampp\mysql\bin\mysql.exe'
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique

if (-not $mysqlCandidates) {
    throw '未找到 mysql.exe。请先安装 MySQL Client，并将其 bin 目录加入 PATH。'
}

$mysql = $mysqlCandidates[0]
$securePassword = ConvertTo-SecureString $DbPassword -AsPlainText -Force
$credential = New-Object System.Management.Automation.PSCredential($DbUser, $securePassword)

$env:MYSQL_PWD = $DbPassword
try {
    & $mysql "--host=$DbHost" "--port=$DbPort" "--user=$DbUser" '--protocol=tcp' '--default-character-set=utf8mb4' '--connect-timeout=10' $DbName '-e' "source $schemaPath"
    if ($LASTEXITCODE -ne 0) {
        throw "数据库初始化失败，mysql.exe 退出码：$LASTEXITCODE"
    }
    Write-Output "数据库 $DbName 初始化完成（密码不显示）。"
}
finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
