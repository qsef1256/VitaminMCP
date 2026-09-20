param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('install', 'uninstall', 'status')]
    [string]$Action,

    [string]$PackageRoot = '',
    [string]$NodePath = '',
    [string]$NpmCliPath = '',
    [string]$JavaPath = '',
    [string]$DataHome = '',
    [string]$Version = '',
    [string]$ServerJar = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ServiceId = 'VitaminMCP'
$InstallRoot = [System.IO.Path]::GetFullPath((Join-Path $env:ProgramData $ServiceId))
$ExpectedRoot = [System.IO.Path]::GetFullPath((Join-Path $env:ProgramData 'VitaminMCP'))
$Wrapper = Join-Path $InstallRoot 'VitaminMCP.exe'
$Config = Join-Path $InstallRoot 'VitaminMCP.xml'
$AppRoot = Join-Path $InstallRoot 'app'
$Logs = Join-Path $InstallRoot 'logs'
$WinSwUrl = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe'
$WinSwSha256 = 'b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f'

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Quote-PowerShell([string]$Value) {
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-Elevated {
    $values = @{
        Action = $Action
        PackageRoot = $PackageRoot
        NodePath = $NodePath
        NpmCliPath = $NpmCliPath
        JavaPath = $JavaPath
        DataHome = $DataHome
        Version = $Version
        ServerJar = $ServerJar
    }
    $command = '& ' + (Quote-PowerShell $PSCommandPath)
    foreach ($entry in $values.GetEnumerator()) {
        $command += ' -' + $entry.Key + ' ' + (Quote-PowerShell ([string]$entry.Value))
    }
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($command))
    $process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', $encoded)
    exit $process.ExitCode
}

function Invoke-Native([string]$File, [string[]]$Arguments) {
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$File exited with code $LASTEXITCODE"
    }
}

function Escape-Xml([string]$Value) {
    return [Security.SecurityElement]::Escape($Value)
}

function Get-InstalledService {
    return Get-CimInstance Win32_Service -Filter "Name='$ServiceId'" -ErrorAction SilentlyContinue
}

function Assert-OwnedService {
    $service = Get-InstalledService
    if ($null -eq $service) {
        return
    }
    $registered = [System.IO.Path]::GetFullPath($service.PathName.Trim('"'))
    if (-not $registered.Equals($Wrapper, [StringComparison]::OrdinalIgnoreCase)) {
        throw "A different service named $ServiceId already exists at $registered"
    }
}

function Test-Mcp {
    $session = $null
    try {
        $body = @{
            jsonrpc = '2.0'
            id = 1
            method = 'initialize'
            params = @{
                protocolVersion = '2025-06-18'
                capabilities = @{}
                clientInfo = @{ name = 'vitaminmcp-service'; version = '1' }
            }
        } | ConvertTo-Json -Depth 5 -Compress
        $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 `
            -Uri 'http://127.0.0.1:25584/mcp' -Method Post `
            -ContentType 'application/json' -Headers @{ Accept = 'application/json, text/event-stream' } `
            -Body $body
        $session = $response.Headers['Mcp-Session-Id']
        $json = $response.Content | ConvertFrom-Json
        return $response.StatusCode -eq 200 -and $session -and `
            $json.result.serverInfo.name -eq 'VitaminMCP'
    } catch {
        return $false
    } finally {
        if ($session) {
            try {
                Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 `
                    -Uri 'http://127.0.0.1:25584/mcp' -Method Delete `
                    -Headers @{ 'Mcp-Protocol-Version' = '2025-06-18'; 'Mcp-Session-Id' = $session } `
                    | Out-Null
            } catch {}
        }
    }
}

function Show-Status {
    $service = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue
    if ($null -eq $service) {
        Write-Output 'VitaminMCP service is not installed.'
        exit 1
    }
    $ready = Test-Mcp
    Write-Output "VitaminMCP service: $($service.Status); MCP ready: $ready"
    if (-not $ready) {
        exit 1
    }
}

function Install-Service {
    if (-not $PackageRoot -or -not $NodePath -or -not $NpmCliPath -or `
            -not $JavaPath -or -not $DataHome -or -not $Version) {
        throw 'The service installer did not receive complete runtime paths.'
    }
    foreach ($required in @($PackageRoot, $NodePath, $NpmCliPath, $JavaPath)) {
        if (-not (Test-Path -LiteralPath $required)) {
            throw "Required path does not exist: $required"
        }
    }
    if ($ServerJar -and -not (Test-Path -LiteralPath $ServerJar -PathType Leaf)) {
        throw "Local MCP server jar does not exist: $ServerJar"
    }

    New-Item -ItemType Directory -Force -Path $InstallRoot, $DataHome, $Logs | Out-Null

    $download = "$Wrapper.part"
    $validWrapper = (Test-Path -LiteralPath $Wrapper -PathType Leaf) -and `
        ((Get-FileHash -Algorithm SHA256 -LiteralPath $Wrapper).Hash.ToLowerInvariant() -eq $WinSwSha256)
    if (-not $validWrapper) {
        Remove-Item -LiteralPath $download -Force -ErrorAction SilentlyContinue
        Invoke-WebRequest -UseBasicParsing -Uri $WinSwUrl -OutFile $download
        $received = (Get-FileHash -Algorithm SHA256 -LiteralPath $download).Hash.ToLowerInvariant()
        if ($received -ne $WinSwSha256) {
            Remove-Item -LiteralPath $download -Force
            throw "WinSW checksum mismatch: expected $WinSwSha256, received $received"
        }
        Move-Item -LiteralPath $download -Destination $Wrapper -Force
    }

    $staging = Join-Path $InstallRoot 'app.new'
    if (Test-Path -LiteralPath $staging) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
    New-Item -ItemType Directory -Path $staging | Out-Null
    $npmArguments = @(
        'install', '--omit=dev', '--ignore-scripts', '--install-links', '--no-save',
        '--prefix', $staging, $PackageRoot
    )
    if ($NpmCliPath.EndsWith('.cmd', [StringComparison]::OrdinalIgnoreCase)) {
        Invoke-Native $NpmCliPath $npmArguments
    } else {
        Invoke-Native $NodePath (@($NpmCliPath) + $npmArguments)
    }

    $launcher = Join-Path $staging 'node_modules\vitaminmcp\bin\vitaminmcp.mjs'
    if (-not (Test-Path -LiteralPath $launcher -PathType Leaf)) {
        throw "npm did not install the VitaminMCP launcher at $launcher"
    }

    Assert-OwnedService
    $service = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue
    if ($service -and $service.Status -ne 'Stopped') {
        Stop-Service -Name $ServiceId -Force
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    }

    if (Test-Path -LiteralPath $AppRoot) {
        Remove-Item -LiteralPath $AppRoot -Recurse -Force
    }
    Move-Item -LiteralPath $staging -Destination $AppRoot
    $launcher = Join-Path $AppRoot 'node_modules\vitaminmcp\bin\vitaminmcp.mjs'

    $localJar = ''
    if ($ServerJar) {
        $localJar = Join-Path $InstallRoot 'mcp-server.jar'
        Copy-Item -LiteralPath $ServerJar -Destination $localJar -Force
    }

    $javaDirectory = Split-Path -Parent $JavaPath
    $accounts = Join-Path $DataHome 'accounts'
    $serverEnvironment = if ($localJar) {
        "  <env name=`"VITAMINMCP_SERVER_JAR`" value=`"$(Escape-Xml $localJar)`" />`r`n"
    } else { '' }
    $xml = @"
<service>
  <id>$ServiceId</id>
  <name>VitaminMCP</name>
  <description>Shared local VitaminMCP server for MCP clients.</description>
  <executable>$(Escape-Xml $NodePath)</executable>
  <arguments>&quot;$(Escape-Xml $launcher)&quot; --http 25584</arguments>
  <workingdirectory>$(Escape-Xml $AppRoot)</workingdirectory>
  <env name="VITAMINMCP_HOME" value="$(Escape-Xml $DataHome)" />
  <env name="VITAMINMCP_ACCOUNTS_DIR" value="$(Escape-Xml $accounts)" />
  <env name="VITAMINMCP_NODE" value="$(Escape-Xml $NodePath)" />
  <env name="PATH" value="$(Escape-Xml $javaDirectory);%PATH%" />
$serverEnvironment  <startmode>Automatic</startmode>
  <delayedAutoStart>true</delayedAutoStart>
  <onfailure action="restart" delay="10 sec" />
  <resetfailure>1 hour</resetfailure>
  <stoptimeout>15 sec</stoptimeout>
  <logpath>$(Escape-Xml $Logs)</logpath>
  <log mode="roll" />
</service>
"@
    [System.IO.File]::WriteAllText($Config, $xml, [Text.UTF8Encoding]::new($false))

    if (-not (Get-Service -Name $ServiceId -ErrorAction SilentlyContinue)) {
        Invoke-Native $Wrapper @('install')
    }
    Invoke-Native 'sc.exe' @('config', $ServiceId, 'obj=', "NT SERVICE\$ServiceId", 'start=', 'delayed-auto')
    Invoke-Native 'sc.exe' @('sidtype', $ServiceId, 'unrestricted')

    $principal = "NT SERVICE\$ServiceId"
    Invoke-Native 'icacls.exe' @($InstallRoot, '/grant', "${principal}:(OI)(CI)RX", '/T', '/C')
    Invoke-Native 'icacls.exe' @($Logs, '/grant', "${principal}:(OI)(CI)M", '/T', '/C')
    Invoke-Native 'icacls.exe' @($DataHome, '/grant', "${principal}:(OI)(CI)M", '/T', '/C')
    Invoke-Native 'icacls.exe' @($NodePath, '/grant', "${principal}:RX", '/C')
    Invoke-Native 'icacls.exe' @($JavaPath, '/grant', "${principal}:RX", '/C')

    Start-Service -Name $ServiceId
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        if (Test-Mcp) {
            Write-Output "VitaminMCP $Version is ready at http://127.0.0.1:25584/mcp"
            Write-Output ''
            Write-Output 'Codex config:'
            Write-Output '[mcp_servers.vitaminmcp]'
            Write-Output 'url = "http://127.0.0.1:25584/mcp"'
            Write-Output 'startup_timeout_sec = 60'
            return
        }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "The service started but MCP did not become ready. Check $Logs"
}

function Uninstall-Service {
    Assert-OwnedService
    $service = Get-Service -Name $ServiceId -ErrorAction SilentlyContinue
    if ($service) {
        if ($service.Status -ne 'Stopped') {
            Stop-Service -Name $ServiceId -Force
            $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
        }
        Invoke-Native 'sc.exe' @('delete', $ServiceId)
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        while ((Get-Service -Name $ServiceId -ErrorAction SilentlyContinue) -and `
                [DateTime]::UtcNow -lt $deadline) {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $InstallRoot.Equals($ExpectedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove unexpected install path: $InstallRoot"
    }
    if (Test-Path -LiteralPath $InstallRoot) {
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force
    }
    Write-Output "VitaminMCP service removed. Account data was kept at $DataHome"
}

if ($Action -eq 'status') {
    Show-Status
    exit 0
}
if (-not (Test-Administrator)) {
    Invoke-Elevated
}
if ($Action -eq 'install') {
    Install-Service
} else {
    Uninstall-Service
}
