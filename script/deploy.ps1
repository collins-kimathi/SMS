# Enable strict mode (forces good coding practices)
Set-StrictMode -Version Latest

# Stop execution immediately if any error occurs
$ErrorActionPreference = "Stop"

# Get the root directory of the project (2 levels up from this script)
$RootDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

# Directory where compiled Java .class files will be stored
$OutDir = Join-Path $RootDir "target\classes"

# Main Java class to run (entry point of your application)
$MainClass = "com.uniongroup.sms.SmsApplication"

# Resolve Java tools from JAVA_HOME first, then PATH.
$JavaBin = if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { Join-Path $env:JAVA_HOME "bin" } else { $null }
$Javac = if ($JavaBin -and (Test-Path (Join-Path $JavaBin "javac.exe"))) { Join-Path $JavaBin "javac.exe" } else { (Get-Command javac -ErrorAction SilentlyContinue).Source }
$Java = if ($JavaBin -and (Test-Path (Join-Path $JavaBin "java.exe"))) { Join-Path $JavaBin "java.exe" } else { (Get-Command java -ErrorAction SilentlyContinue).Source }
if (-not $Javac -or -not $Java) {
    throw "A JDK is required but javac/java were not found. Install a JDK and set JAVA_HOME to its folder (for example, C:\Program Files\Microsoft\jdk-21...)."
}

# Prefer a JDBC driver bundled with this project. This keeps the setup portable
# across machines and avoids relying on a user's Downloads directory.
$BundledJars = @(Get-ChildItem -LiteralPath (Join-Path $RootDir "lib") -Filter "mysql-connector-j*.jar" -File -Recurse -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending)


# Function to load environment variables from a .env file
function Import-DotEnv {
    param([string]$Path)

    # If .env file does not exist, do nothing
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    # Read each line in the .env file
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()

        # Skip empty lines or comments (#)
        if (-not $line -or $line.StartsWith("#")) {
            return
        }

        # Split line into key=value
        $parts = $line.Split("=", 2)

        # Ignore invalid lines
        if ($parts.Count -ne 2) {
            return
        }

        # Only set variable if it doesn't already exist
        if ([string]::IsNullOrWhiteSpace([System.Environment]::GetEnvironmentVariable($parts[0]))) {
            [System.Environment]::SetEnvironmentVariable($parts[0], $parts[1])
        }
    }
}


# Function to ensure a required environment variable exists
function Require-Env {
    param([string]$Name)

    # Get the variable value
    $value = [System.Environment]::GetEnvironmentVariable($Name)

    # If missing or empty → stop script
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Missing required environment variable: $Name"
    }

    return $value
}


# Load environment variables from .env file in project root
Import-DotEnv (Join-Path $RootDir ".env")


# Get JDBC JAR path from environment variable (if set)
$JdbcJar = [System.Environment]::GetEnvironmentVariable("JDBC_JAR")

# If not set, use the first bundled Connector/J jar.
if ([string]::IsNullOrWhiteSpace($JdbcJar)) {
    if ($BundledJars.Count -gt 0) {
        $JdbcJar = $BundledJars[0].FullName
    }
}


# Get PORT from environment variable
$PortValue = [System.Environment]::GetEnvironmentVariable("PORT")

# Default to 9090 if not set
if ([string]::IsNullOrWhiteSpace($PortValue)) {
    $PortValue = "9090"
}


# Ensure required database environment variables exist
[void](Require-Env "SMS_DB_URL")
[void](Require-Env "SMS_DB_USER")
[void](Require-Env "SMS_DB_PASSWORD")


# Check if MySQL JDBC driver file exists
if (-not (Test-Path -LiteralPath $JdbcJar)) {
    $bundledHint = if ($BundledJars.Count -gt 0) { $BundledJars[0].FullName } else { "No mysql-connector-j*.jar found under $(Join-Path $RootDir 'lib')." }
    throw "MySQL JDBC jar not found at: $JdbcJar`nSet JDBC_JAR in .env to a valid jar path, or place the driver under the project's lib folder. $bundledHint"
}


# Check if the selected port is already in use
$existing = Get-NetTCPConnection -LocalPort ([int]$PortValue) -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1

# If port is in use, display which process is using it and stop
if ($existing) {
    $process = Get-Process -Id $existing.OwningProcess -ErrorAction SilentlyContinue
    if ($process) {
        Write-Host "Port $PortValue is already in use by $($process.ProcessName) (PID $($process.Id)). Stop it first, then rerun this script."
        exit 1
    }
}


# If output directory exists, delete it (clean build)
if (Test-Path -LiteralPath $OutDir) {
    Remove-Item -LiteralPath $OutDir -Recurse -Force
}

# Create fresh output directory
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null


# Path to all Java source files
# Get all .java files in the folder
$JavaSources = Get-ChildItem -Path (Join-Path $RootDir "src\main\java\com\uniongroup\sms") -Filter *.java -Recurse | ForEach-Object { $_.FullName }


# Compile Java files using javac
# -cp → classpath (includes MySQL driver)
# -d → output directory
& $Javac -cp $JdbcJar -d $OutDir $JavaSources

# If compilation fails, exit with error code
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}


# Create classpath including compiled classes + MySQL driver
$ClassPath = "$OutDir;$JdbcJar"

# Run the Java application
& $Java -cp $ClassPath $MainClass $PortValue

# Exit with the same status as Java program
exit $LASTEXITCODE
