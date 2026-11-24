param(
    [string]$Python = "python",
    [int]$Workers = 20,
    [string]$RunDirectory = ".cache/deepsets"
)
$ErrorActionPreference = "Stop"
Push-Location (Join-Path $PSScriptRoot "..")
try {
    & ./build.ps1
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
    $run = (New-Item -Force -ItemType Directory $RunDirectory).FullName
    $class = "com.circus.bot.DeepSetsGames"
    & java -Xmx8g -cp java/out $class "$run/valid" 124 $Workers 0 valid
    if ($LASTEXITCODE -ne 0) { throw "Validation game collection failed" }

    $data = @()
    $best = $null
    foreach ($generation in 0..3) {
        $shard = "$run/gen$generation"
        $model = "$run/g$generation.bin"
        $data += $shard
        $modelOption = if ($best) { "-Dcircus.deepsets.model=$best" }
        $javaArgs = @("-Xmx8g", "-cp", "java/out")
        if ($best) { $javaArgs = @($modelOption) + $javaArgs }
        $count = if ($generation -eq 0) { 3000 } elseif ($generation -eq 3) { 2000 } else { 1200 }
        $budget = if ($generation -eq 3) { ".06" } else { ".04" }
        & java @javaArgs $class $shard $count $Workers $generation train $budget | Tee-Object "$run/g$generation-games.log"
        if ($LASTEXITCODE -ne 0) { throw "Training game collection failed" }
        & $Python training/fit_deepsets.py --train @data --valid "$run/valid" --output $model | Tee-Object "$run/g$generation.log"
        if ($LASTEXITCODE -ne 0) { throw "Model fitting failed" }

        $opponent = if ($best) { $best } else { "search" }
        $gateGames = if ($generation -eq 3) { 124 } else { 62 }
        $gateSeed = if ($generation -eq 3) { 271823 } else { 0 }
        & java -Xmx6g "-Dcircus.trial.seedOffset=$gateSeed" -cp java/out com.circus.bot.DeepSetsTrial $model $opponent $gateGames $Workers .10 | Tee-Object "$run/g$generation-trial.log"
        $status = $LASTEXITCODE
        # The initial model seeds self-play, even when it loses to the search teacher.
        if ($status -notin @(0, 2)) { throw "Promotion trial failed" }
        if (!$best -or $status -eq 0) { $best = $model }
    }
    Copy-Item $best bot/neural-deepsets-v1.bin
    Write-Host "Selected $best"
} finally {
    Pop-Location
}
