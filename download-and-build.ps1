$ErrorActionPreference = "Stop"

$RepoUrl = "https://github.com/mattbutcher577-bit/epicbot-gold-crafter.git"
$InstallDir = Join-Path $env:USERPROFILE "Desktop\epicbot-gold-crafter"

function Ensure-Git {
    if (Get-Command git -ErrorAction SilentlyContinue) { return }

    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw "Git is missing and winget is unavailable. Install Git for Windows first."
    }

    winget install Git.Git -e --accept-package-agreements --accept-source-agreements
    $env:Path += ";C:\Program Files\Git\cmd"

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw "Git installed but is not available yet. Open a new terminal and run again."
    }
}

Ensure-Git

if (Test-Path (Join-Path $InstallDir ".git")) {
    Write-Host "Updating existing Gold Crafter checkout..."
    Push-Location $InstallDir
    try {
        git reset --hard
        git pull --ff-only
        if ($LASTEXITCODE -ne 0) { throw "git pull failed" }
    }
    finally {
        Pop-Location
    }
}
else {
    if (Test-Path $InstallDir) {
        Remove-Item $InstallDir -Recurse -Force
    }

    Write-Host "Downloading Gold Crafter..."
    git clone $RepoUrl $InstallDir
    if ($LASTEXITCODE -ne 0) { throw "git clone failed" }
}

Write-Host ""
Write-Host "Starting build..."
& powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File (Join-Path $InstallDir "build.ps1")
exit $LASTEXITCODE
