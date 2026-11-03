$ErrorActionPreference = "Stop"
$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
# SMS System Setup Script
Write-Host "Starting SMS System Setup" -ForegroundColor Cyan

# Step 1: Ensure MySQL is running
Write-Host "Ensuring MySQL is running..." -ForegroundColor Yellow
Write-Host "If MySQL is not running, start it now: net start mysql" -ForegroundColor Yellow

# Step 2: Ensure local configuration exists
Write-Host "Creating .env file..." -ForegroundColor Yellow
$EnvPath = Join-Path $RootDir ".env"
if (-not (Test-Path -LiteralPath $EnvPath)) {
    Copy-Item -LiteralPath (Join-Path $RootDir ".env.example") -Destination $EnvPath
    throw ".env was created from .env.example. Edit it with your MySQL credentials, then rerun setup."
} else {
    Write-Host ".env already exists; keeping existing settings" -ForegroundColor Green
}

# Step 3: Verify JDBC driver exists
Write-Host "Checking JDBC driver..." -ForegroundColor Yellow
$JdbcJars = @(Get-ChildItem -LiteralPath (Join-Path $RootDir "lib") -Filter "mysql-connector-j*.jar" -File -Recurse -ErrorAction SilentlyContinue)
if ($JdbcJars.Count -gt 0) {
    Write-Host "JDBC driver found: $($JdbcJars[0].FullName)" -ForegroundColor Green
} else {
    if (-not (Select-String -LiteralPath $EnvPath -Pattern '^\s*JDBC_JAR\s*=\s*\S+' -Quiet)) {
        throw "No MySQL JDBC jar found under $(Join-Path $RootDir 'lib'). Place mysql-connector-j*.jar there or set JDBC_JAR in .env."
    }
}

# Step 4: Run deploy script
Write-Host "Running deploy script..." -ForegroundColor Yellow
& (Join-Path $RootDir "script\deploy.ps1")
Write-Host "Setup complete!" -ForegroundColor Cyan
