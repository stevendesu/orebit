# Orebit headless autotest orchestrator (mc-1.21 era, Fabric).
# Intended location: <repo>/scripts/run-autotest.ps1, with templates in <repo>/scripts/autotest/
# (server.properties, eula.txt, orebit.properties -- copied from the s53 harness design).
#
# What it does:
#   1. resets run/autotest to a deterministic state (deletes the world -> same seed regenerates it,
#      drops the seed/eula/bot-config templates, clears any stale result file),
#   2. runs :fabric:<ver>:runAutotest (dedicated server, headless -- Loom's server() preset adds nogui),
#   3. asserts on run/autotest/orebit-autotest-result.properties.
# Exit codes: 0 = PASS, 1 = FAIL (result file says so), 2 = no result file (crash / hook never armed).
#
# Requires JAVA_HOME -> JDK 21 (the 1.21.11 node; mc-1.21 era rule: >=1.20.5 -> 21).
# Windows PowerShell 5.1 compatible (no &&, no ternary).

param(
    [string]$McVersion = "1.21.11",
    # Optional scenario overrides, forwarded as gradle -P properties -> -D JVM args by the run config.
    [string]$Start = "",
    [string]$Goal = "",
    [int]$BudgetTicks = 0,
    [switch]$BotDebug,
    # -Trace: dump EVERY A* search's full expansion trace to run/autotest/orebit-autotest-trace-<n>.txt
    # (one numbered file per search; analyze with internal_docs/trace_analysis.py). Trace runs are SLOW
    # and the files are HUGE (per-node file I/O on the tick thread) -- diagnostic runs only.
    [switch]$Trace
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot            # scripts/ -> repo root
$runDir = Join-Path $repo "run\autotest"
$templates = Join-Path $PSScriptRoot "autotest"
$resultFile = Join-Path $runDir "orebit-autotest-result.properties"

# ---- 1. Deterministic run-dir state ----------------------------------------------------------
New-Item -ItemType Directory -Force -Path (Join-Path $runDir "config") | Out-Null
$world = Join-Path $runDir "world"
if (Test-Path $world) {
    Write-Host "[autotest] deleting previous world (fresh gen from the pinned seed)"
    Remove-Item -Recurse -Force $world
}
if (Test-Path $resultFile) { Remove-Item -Force $resultFile }
# Stale per-search trace files from a previous -Trace run would mix into this run's numbering.
Get-ChildItem -Path $runDir -Filter "orebit-autotest-trace-*.txt" -ErrorAction SilentlyContinue | Remove-Item -Force

Copy-Item (Join-Path $templates "server.properties") (Join-Path $runDir "server.properties") -Force
Copy-Item (Join-Path $templates "eula.txt")          (Join-Path $runDir "eula.txt") -Force
Copy-Item (Join-Path $templates "orebit.properties") (Join-Path $runDir "config\orebit.properties") -Force

# ---- 2. Run -----------------------------------------------------------------------------------
$gradleArgs = @(":fabric:${McVersion}:runAutotest")
if ($Start -ne "")    { $gradleArgs += "-Porebit.autotest.start=$Start" }
if ($Goal -ne "")     { $gradleArgs += "-Porebit.autotest.goal=$Goal" }
if ($BudgetTicks -gt 0) { $gradleArgs += "-Porebit.autotest.budgetTicks=$BudgetTicks" }
if ($BotDebug)        { $gradleArgs += "-Porebit.autotest.debug=true" }
if ($Trace)           { $gradleArgs += "-Porebit.autotest.trace=true" }

# gradlew.bat resolves the PROJECT from the current working directory, not from its own location --
# invoked from elsewhere it would run this wrapper against a different repo's build. Pin the cwd.
Push-Location $repo
try {
    Write-Host "[autotest] re-asserting active Stonecutter project ($McVersion)"
    & (Join-Path $repo "gradlew.bat") "Set active project to $McVersion"
    if ($LASTEXITCODE -ne 0) { Write-Error "Set active project failed"; exit 2 }

    Write-Host "[autotest] launching headless server: gradlew $($gradleArgs -join ' ')"
    & (Join-Path $repo "gradlew.bat") @gradleArgs
    $gradleExit = $LASTEXITCODE
    Write-Host "[autotest] gradle exited with code $gradleExit"
} finally {
    Pop-Location
}

# ---- 3. Assert --------------------------------------------------------------------------------
if (-not (Test-Path $resultFile)) {
    Write-Error ("no result file at $resultFile -- the server crashed or the hook never armed. " +
                 "Check run/autotest/logs/latest.log for [Orebit/autotest] lines.")
    exit 2
}

$result = ConvertFrom-StringData (Get-Content $resultFile -Raw)
Write-Host ""
Write-Host "===== Orebit autotest result ====="
$result.GetEnumerator() | Sort-Object Name | ForEach-Object { Write-Host ("  {0} = {1}" -f $_.Name, $_.Value) }
Write-Host "=================================="

if ($result.result -eq "PASS") {
    Write-Host "[autotest] PASS"
    exit 0
} else {
    Write-Host "[autotest] FAIL: $($result.reason)  (triage table: DESIGN.md section 7)"
    exit 1
}
