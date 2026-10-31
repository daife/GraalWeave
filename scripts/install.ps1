param(
    [Parameter(Mandatory=$true)][string]$Instance,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Codex
)
$ErrorActionPreference = 'Stop'
$target = [IO.Path]::GetFullPath($Instance)
if (!(Test-Path -LiteralPath $target -PathType Container)) { throw "Instance does not exist: $target" }
if (!$JavaHome) {
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $JavaHome = Split-Path -Parent (Split-Path -Parent $java)
}
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
if (!(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe') -PathType Leaf)) {
    throw 'JavaHome must point to a Java 25 JDK directory'
}
$packageRoot = Split-Path -Parent $PSCommandPath
$env:JAVA_HOME = $JavaHome
$destination = Join-Path $target 'graalweave\client'
New-Item -ItemType Directory -Force -Path $destination | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $target 'graalweave\startup') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $target 'graalweave\workspace') | Out-Null
Copy-Item -LiteralPath (Join-Path $packageRoot 'graalweave\bin') -Destination $destination -Recurse -Force
Copy-Item -LiteralPath (Join-Path $packageRoot 'graalweave\lib') -Destination $destination -Recurse -Force
[IO.File]::WriteAllText((Join-Path $destination 'java-home.txt'), $JavaHome + "`r`n", [Text.UTF8Encoding]::new($false))
$mods = Join-Path $target 'mods'
New-Item -ItemType Directory -Force -Path $mods | Out-Null
Get-ChildItem -LiteralPath (Join-Path $packageRoot 'mods') -Filter '*.jar' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $mods -Force
}
& (Join-Path $destination 'bin\graalweave.bat') --instance $target env init
if ($LASTEXITCODE -ne 0) { throw 'Python environment initialization failed' }
$mcpCommand = Join-Path $JavaHome 'bin\java.exe'
$mcpArgs = @('--enable-native-access=ALL-UNNAMED', '-Dfile.encoding=UTF-8', '-cp', (Join-Path $destination 'lib\*'),
    'dev.graalweave.cli.Main', '--instance', $target, 'mcp')
$mcpConfig = @{ mcpServers = @{ graalweave = @{ command = $mcpCommand; args = $mcpArgs } } }
$mcpFile = Join-Path $target 'graalweave\mcp.json'
[IO.File]::WriteAllText($mcpFile, ($mcpConfig | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
if ($Codex) {
    & codex mcp add graalweave -- $mcpCommand @mcpArgs
    if ($LASTEXITCODE -ne 0) { throw 'Codex MCP registration failed' }
}
Write-Host "MCP config: $mcpFile (stdio; direct Java launch, no shell)"
Write-Host "Installed. Start Minecraft, then use: $destination\bin\graalweave.bat --instance `"$target`" status"
