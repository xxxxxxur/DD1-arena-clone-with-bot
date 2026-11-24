param(
    [string]$Output = "benchmarks/deepsets-v1",
    [int]$Workers = 16,
    [string[]]$Variants = @("deepsets_neural", "unit_neural_search", "search"),
    [string]$Model = "bot/neural-deepsets-v1.bin",
    [switch]$CoreOnly
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Push-Location $root
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $stage = (New-Item -Force -ItemType Directory $Output).FullName
    $summary = Join-Path $stage "comparisons.csv"
    $teams = @(Get-ChildItem team/*.json | ForEach-Object { (Get-Content -Raw -Encoding UTF8 $_ | ConvertFrom-Json).id })
    $requests = @(
        @("iron_wall", "iron_wall"), @("iron_hound", "iron_hound"), @("direct_kill", "direct_kill"),
        @("iron_hound", "iron_wall")
    )
    if (!$CoreOnly) { $requests += ,@("aggressive_mark", "iron_wall") }
    foreach ($pattern in @("stun", "stress", "bleed_blight", "shuffle", "aggressive_mark", "raid_boss")) {
        if ($CoreOnly) { break }
        $team = $teams | Where-Object { $_ -match $pattern } | Select-Object -First 1
        if ($team) { $requests += ,@($team, $team) }
    }
    foreach ($budget in @("0.25", "0.10")) {
        $dir = (New-Item -Force -ItemType Directory (Join-Path $stage $budget)).FullName
        foreach ($folder in @("java/resources", "bot", "team")) {
            New-Item -Force -ItemType Directory (Join-Path $dir $folder) | Out-Null
        }
        Copy-Item java/resources/game_data.json (Join-Path $dir "java/resources")
        Copy-Item bot/*.bin (Join-Path $dir "bot")
        if ($Variants -contains "deepsets_neural") {
            Copy-Item -LiteralPath $Model -Destination (Join-Path $dir "bot/neural-deepsets-v1.bin")
        }
        Copy-Item team/*.json (Join-Path $dir "team")
        $env:JAVA_TOOL_OPTIONS = "-Xmx8g -Dcircus.root=`"$dir`""
        foreach ($pair in $requests) {
            $left, $right = $pair
            foreach ($variant in $Variants) {
                if ($variant -eq "search" -and $left -eq $right) { continue }
                $label = "$right@$variant"
                $saved = if (Test-Path $summary) { Import-Csv $summary }
                if ($saved | Where-Object { $_.left -eq $left -and $_.right -eq $label -and $_.budget -eq $budget }) { continue }
                $replays = if ($variant -eq "deepsets_neural" -and $right -in @("iron_wall", "iron_hound")) { 2 } else { 0 }
                if ($variant -eq "deepsets_neural" -and $left -eq "aggressive_mark" -and $right -eq "iron_wall") { $replays = 8 }
                $log = Join-Path $dir "$left-$label.log"
                & ./run_tournament.ps1 --bots "$left,$label" --matches 30 --workers $Workers --budget $budget --seed 42 --replays $replays *> $log
                if ($LASTEXITCODE -ne 0) { throw "Tournament failed. See $log" }
                $report = Get-ChildItem "$dir/tournaments/*.json" | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
                $r = Get-Content -Raw -Encoding UTF8 $report | ConvertFrom-Json
                $scores = $r.winrates.$label
                if ($r.total_matches -ne 30 -or !$scores) { throw "Unexpected report: $report" }
                [pscustomobject]@{ budget=$budget; left=$left; right=$label; wins=$scores.wins; losses=$scores.losses;
                    games=$r.total_matches; pct=$scores.winrate_pct; report=$report.Name } | Export-Csv $summary -NoTypeInformation -Append
                Write-Host "$budget $left vs $label : $($scores.wins)/30"
            }
        }
    }
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaOptions
    Pop-Location
}
