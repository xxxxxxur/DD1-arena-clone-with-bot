$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir = Join-Path $Root "java\out"
$TestOutDir = Join-Path $Root "java\out-test"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
New-Item -ItemType Directory -Force -Path $TestOutDir | Out-Null

$SrcFiles = @(Get-ChildItem -Path (Join-Path $Root "java\src") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
$BotDir = Join-Path $Root "bot"
if (Test-Path $BotDir) {
    $CustomBots = @(Get-ChildItem -Path $BotDir -Recurse -Filter "*.java" | ForEach-Object { $_.FullName })
    if ($CustomBots.Count -gt 0) {
        $SrcFiles += $CustomBots
    }
}

Write-Host "Compiling $($SrcFiles.Count) Java source files (java/src + bot/)..."
javac --release 26 -encoding UTF-8 -d $OutDir $SrcFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$TestFiles = @(Get-ChildItem -Path (Join-Path $Root "java\test") -Recurse -Filter "*.java" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
if ($TestFiles.Count -gt 0) {
    Write-Host "Compiling $($TestFiles.Count) Java test files..."
    javac --release 26 -encoding UTF-8 -cp $OutDir -d $TestOutDir $TestFiles
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host "Build succeeded -> $OutDir"
