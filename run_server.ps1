param(
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 8765,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$OutDir = Join-Path $Root "java\out"

if (-not (Test-Path (Join-Path $OutDir "com\circus\Main.class"))) {
    & (Join-Path $Root "build.ps1")
}

java --enable-native-access=ALL-UNNAMED -cp $OutDir com.circus.Main --host $BindHost --port $Port @ExtraArgs
