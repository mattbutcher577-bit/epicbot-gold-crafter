$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$WorkRoot = Join-Path $RepoRoot ".epicbot-build"
$TemplateDir = Join-Path $WorkRoot "template"
$ReleaseDir = Join-Path $RepoRoot "release"

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "============================================================"
    Write-Host " $Text"
    Write-Host "============================================================"
}

function Ensure-Git {
    if (Get-Command git -ErrorAction SilentlyContinue) { return }

    Write-Step "Installing Git"
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw "Git is missing and winget is unavailable. Install Git for Windows and run again."
    }

    winget install Git.Git -e --accept-package-agreements --accept-source-agreements
    $env:Path += ";C:\Program Files\Git\cmd"

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw "Git installation completed but git.exe is still unavailable. Open a new terminal and run again."
    }
}

function Use-Java21 {
    $candidates = @()
    $roots = @(
        "C:\Program Files\Eclipse Adoptium",
        "C:\Program Files\Java",
        "C:\Program Files\Microsoft",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
    )

    foreach ($root in $roots) {
        if (Test-Path $root) {
            $candidates += Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | Where-Object {
                $_.Name -match "jdk-?21|temurin-?21|openjdk-?21"
            }
        }
    }

    $jdk = $candidates | Sort-Object Name -Descending | Select-Object -First 1

    if (-not $jdk) {
        Write-Step "Installing Java 21"
        if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
            throw "Java 21 is missing and winget is unavailable. Install Temurin JDK 21 and run again."
        }

        winget install EclipseAdoptium.Temurin.21.JDK -e --accept-package-agreements --accept-source-agreements

        if (Test-Path "C:\Program Files\Eclipse Adoptium") {
            $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -match "^jdk-21" } |
                Sort-Object Name -Descending |
                Select-Object -First 1
        }
    }

    if (-not $jdk) {
        throw "Could not locate Java 21 after installation."
    }

    $env:JAVA_HOME = $jdk.FullName
    $env:Path = "$($jdk.FullName)\bin;$env:Path"

    Write-Host "JAVA_HOME=$env:JAVA_HOME"
    & java -version
    if ($LASTEXITCODE -ne 0) {
        throw "Java 21 could not be started."
    }
}

function Find-TemplateJavaDir([string]$Root) {
    $dirs = Get-ChildItem $Root -Directory -Recurse -ErrorAction SilentlyContinue | Where-Object {
        $_.FullName -match "[\\/]src[\\/]main[\\/]java$" -and $_.FullName -notmatch "[\\/]build[\\/]"
    }

    if (-not $dirs) {
        throw "Could not find a src/main/java folder in the EpicBot template."
    }

    foreach ($dir in $dirs) {
        $javaFiles = Get-ChildItem $dir.FullName -Filter *.java -Recurse -ErrorAction SilentlyContinue
        foreach ($file in $javaFiles) {
            $text = Get-Content $file.FullName -Raw -ErrorAction SilentlyContinue
            if ($text -match "ScriptManifest|LoopScript|TreeScript") {
                return $dir.FullName
            }
        }
    }

    return ($dirs | Select-Object -First 1).FullName
}

function Find-ModuleRoot([string]$JavaDir, [string]$TemplateRoot) {
    $dir = Get-Item $JavaDir

    while ($dir -and $dir.FullName.StartsWith($TemplateRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        if ((Test-Path (Join-Path $dir.FullName "build.gradle")) -or (Test-Path (Join-Path $dir.FullName "build.gradle.kts"))) {
            return $dir.FullName
        }
        $dir = $dir.Parent
    }

    return $TemplateRoot
}

Write-Step "EPICBOT GOLD CRAFTER - BUILD"
Ensure-Git
Use-Java21

if (Test-Path $WorkRoot) {
    Remove-Item $WorkRoot -Recurse -Force
}
New-Item $WorkRoot -ItemType Directory -Force | Out-Null

Write-Step "Downloading official EpicBot script template"
git clone --depth 1 https://gitlab.epicbot.com/epicbot-public/epicbot-script-template.git $TemplateDir
if ($LASTEXITCODE -ne 0) {
    throw "Could not clone EpicBot's official script template."
}

$javaDir = Find-TemplateJavaDir $TemplateDir
$moduleRoot = Find-ModuleRoot $javaDir $TemplateDir

Write-Host "Template Java source: $javaDir"
Write-Host "Template module root: $moduleRoot"

Write-Step "Injecting Gold Crafter source"
Get-ChildItem $javaDir -Filter *.java -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

$targetDir = Join-Path $javaDir "com\goldcrafter"
New-Item $targetDir -ItemType Directory -Force | Out-Null
Copy-Item (Join-Path $RepoRoot "src\main\java\com\goldcrafter\GoldCrafter.java") (Join-Path $targetDir "GoldCrafter.java") -Force

Write-Step "Building with Java 21"
Push-Location $TemplateDir
try {
    & ".\gradlew.bat" clean build --stacktrace
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed. Scroll up to the first compiler error."
    }
}
finally {
    Pop-Location
}

Write-Step "Finding compiled JAR"
$jarCandidates = @()

$moduleLibs = Join-Path $moduleRoot "build\libs"
if (Test-Path $moduleLibs) {
    $jarCandidates += Get-ChildItem $moduleLibs -Filter *.jar -File -ErrorAction SilentlyContinue
}

$jarCandidates += Get-ChildItem $TemplateDir -Filter *.jar -File -Recurse -ErrorAction SilentlyContinue | Where-Object {
    $_.FullName -match "[\\/]build[\\/]libs[\\/]" -and $_.Name -notmatch "sources|javadoc|plain"
}

$jar = $jarCandidates |
    Where-Object { $_.Name -notmatch "sources|javadoc|plain" } |
    Sort-Object LastWriteTime, Length -Descending |
    Select-Object -First 1

if (-not $jar) {
    throw "Gradle reported success but no compiled script JAR was found."
}

New-Item $ReleaseDir -ItemType Directory -Force | Out-Null
$finalJar = Join-Path $ReleaseDir "GoldCrafter.jar"
Copy-Item $jar.FullName $finalJar -Force

Write-Host ""
Write-Host "BUILD SUCCESSFUL"
Write-Host "JAR: $finalJar"
Write-Host ""

Start-Process explorer.exe $ReleaseDir
