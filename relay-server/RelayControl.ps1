# WiChat Relay Control: a small Windows window to start/stop the dev relay and show the
# address each device should use. Launch with "Start Relay Control.bat" (double-click).
#
#   -SelfTest   start the relay, wait for /health, stop it, exit 0/1 (no window)

param([switch]$SelfTest)

$ErrorActionPreference = 'Stop'
$RelayDir = $PSScriptRoot
$Adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

$script:proc = $null
$script:pendingLine = $null
$script:port = 8080

function Get-LanAddresses {
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
        Select-Object InterfaceAlias, IPAddress
}

function Test-RelayHealthy([int]$Port) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "http://127.0.0.1:$Port/health"
        return $r.StatusCode -eq 200
    } catch {
        return $false
    }
}

function Start-Relay([int]$Port) {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        throw 'Node.js is not installed. Get it from https://nodejs.org and try again.'
    }
    if (-not (Test-Path (Join-Path $RelayDir 'node_modules'))) {
        $npm = Start-Process -FilePath 'npm.cmd' -ArgumentList 'install', '--no-audit', '--no-fund' `
            -WorkingDirectory $RelayDir -NoNewWindow -Wait -PassThru
        if ($npm.ExitCode -ne 0) { throw 'npm install failed' }
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = (Get-Command node).Source
    $psi.Arguments = 'server.js'
    $psi.WorkingDirectory = $RelayDir
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.EnvironmentVariables['PORT'] = "$Port"
    $p = [System.Diagnostics.Process]::Start($psi)
    # Async line reads, polled by Read-RelayOutput so the window never blocks.
    $script:pendingErr = $p.StandardError.ReadLineAsync()
    $script:pendingLine = $p.StandardOutput.ReadLineAsync()
    return $p
}

function Stop-Relay {
    if ($script:proc -and -not $script:proc.HasExited) {
        try { $script:proc.Kill() } catch {}
        $script:proc.WaitForExit(3000) | Out-Null
    }
    $script:proc = $null
}

# Collects whatever the relay has printed since the last call, without blocking.
function Read-RelayOutput {
    $lines = @()
    if (-not $script:proc) { return $lines }
    while ($script:pendingLine -and $script:pendingLine.IsCompleted) {
        $line = $script:pendingLine.Result
        if ($null -eq $line) { $script:pendingLine = $null; break }
        $lines += $line
        $script:pendingLine = $script:proc.StandardOutput.ReadLineAsync()
    }
    while ($script:pendingErr -and $script:pendingErr.IsCompleted) {
        $line = $script:pendingErr.Result
        if ($null -eq $line) { $script:pendingErr = $null; break }
        $lines += "ERROR: $line"
        $script:pendingErr = $script:proc.StandardError.ReadLineAsync()
    }
    return $lines
}

if ($SelfTest) {
    $testPort = 18080
    if (Test-RelayHealthy $testPort) { Write-Output "port $testPort busy"; exit 1 }
    $script:proc = Start-Relay $testPort
    $ok = $false
    for ($i = 0; $i -lt 30 -and -not $ok; $i++) { Start-Sleep -Milliseconds 300; $ok = Test-RelayHealthy $testPort }
    Start-Sleep -Milliseconds 300
    Read-RelayOutput | ForEach-Object { Write-Output "relay: $_" }
    Stop-Relay
    $stopped = -not (Test-RelayHealthy $testPort)
    Write-Output "healthy=$ok stopped=$stopped lan=$((Get-LanAddresses).IPAddress -join ',')"
    if ($ok -and $stopped) { exit 0 } else { exit 1 }
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$font = New-Object System.Drawing.Font('Segoe UI', 10)
$form = New-Object System.Windows.Forms.Form
$form.Text = 'WiChat Relay Control'
$form.Size = New-Object System.Drawing.Size(720, 600)
$form.MinimumSize = New-Object System.Drawing.Size(620, 500)
$form.StartPosition = 'CenterScreen'
$form.Font = $font

# Status row
$statusDot = New-Object System.Windows.Forms.Label
$statusDot.Location = New-Object System.Drawing.Point(16, 18)
$statusDot.Size = New-Object System.Drawing.Size(16, 16)
$statusDot.BackColor = [System.Drawing.Color]::Gray
$form.Controls.Add($statusDot)

$statusText = New-Object System.Windows.Forms.Label
$statusText.Location = New-Object System.Drawing.Point(40, 14)
$statusText.Size = New-Object System.Drawing.Size(330, 24)
$statusText.Font = New-Object System.Drawing.Font('Segoe UI', 12, [System.Drawing.FontStyle]::Bold)
$statusText.Text = 'Relay is stopped'
$form.Controls.Add($statusText)

$portLabel = New-Object System.Windows.Forms.Label
$portLabel.Location = New-Object System.Drawing.Point(380, 17)
$portLabel.Size = New-Object System.Drawing.Size(36, 22)
$portLabel.Text = 'Port'
$form.Controls.Add($portLabel)

$portBox = New-Object System.Windows.Forms.NumericUpDown
$portBox.Location = New-Object System.Drawing.Point(418, 14)
$portBox.Size = New-Object System.Drawing.Size(80, 24)
$portBox.Minimum = 1024
$portBox.Maximum = 65535
$portBox.Value = 8080
$form.Controls.Add($portBox)

$startBtn = New-Object System.Windows.Forms.Button
$startBtn.Location = New-Object System.Drawing.Point(510, 10)
$startBtn.Size = New-Object System.Drawing.Size(88, 32)
$startBtn.Text = 'Start'
$startBtn.Anchor = 'Top, Right'
$form.Controls.Add($startBtn)

$stopBtn = New-Object System.Windows.Forms.Button
$stopBtn.Location = New-Object System.Drawing.Point(604, 10)
$stopBtn.Size = New-Object System.Drawing.Size(88, 32)
$stopBtn.Text = 'Stop'
$stopBtn.Enabled = $false
$stopBtn.Anchor = 'Top, Right'
$form.Controls.Add($stopBtn)

# Links
$linksLabel = New-Object System.Windows.Forms.Label
$linksLabel.Location = New-Object System.Drawing.Point(16, 54)
$linksLabel.Size = New-Object System.Drawing.Size(680, 20)
$linksLabel.Text = 'Relay server address to type in the app (double-click a row to copy it):'
$form.Controls.Add($linksLabel)

$links = New-Object System.Windows.Forms.ListView
$links.Location = New-Object System.Drawing.Point(16, 76)
$links.Size = New-Object System.Drawing.Size(676, 150)
$links.View = 'Details'
$links.FullRowSelect = $true
$links.HeaderStyle = 'Nonclickable'
$links.Anchor = 'Top, Left, Right'
[void]$links.Columns.Add('Device', 230)
[void]$links.Columns.Add('Relay server address', 260)
[void]$links.Columns.Add('Note', 180)
$form.Controls.Add($links)

$copyBtn = New-Object System.Windows.Forms.Button
$copyBtn.Location = New-Object System.Drawing.Point(16, 232)
$copyBtn.Size = New-Object System.Drawing.Size(130, 30)
$copyBtn.Text = 'Copy address'
$form.Controls.Add($copyBtn)

$openBtn = New-Object System.Windows.Forms.Button
$openBtn.Location = New-Object System.Drawing.Point(152, 232)
$openBtn.Size = New-Object System.Drawing.Size(130, 30)
$openBtn.Text = 'Open in browser'
$form.Controls.Add($openBtn)

$usbBtn = New-Object System.Windows.Forms.Button
$usbBtn.Location = New-Object System.Drawing.Point(288, 232)
$usbBtn.Size = New-Object System.Drawing.Size(170, 30)
$usbBtn.Text = 'Connect USB phone'
$form.Controls.Add($usbBtn)

$hint = New-Object System.Windows.Forms.Label
$hint.Location = New-Object System.Drawing.Point(16, 268)
$hint.Size = New-Object System.Drawing.Size(676, 36)
$hint.Anchor = 'Top, Left, Right'
$hint.ForeColor = [System.Drawing.Color]::DimGray
$hint.Text = 'These addresses only work while this PC is on and the relay is running. To reach people miles away, deploy the relay online (see DEPLOY.md).'
$form.Controls.Add($hint)

# Log
$logLabel = New-Object System.Windows.Forms.Label
$logLabel.Location = New-Object System.Drawing.Point(16, 306)
$logLabel.Size = New-Object System.Drawing.Size(200, 20)
$logLabel.Text = 'Relay log'
$form.Controls.Add($logLabel)

$log = New-Object System.Windows.Forms.TextBox
$log.Location = New-Object System.Drawing.Point(16, 328)
$log.Size = New-Object System.Drawing.Size(676, 220)
$log.Multiline = $true
$log.ReadOnly = $true
$log.ScrollBars = 'Vertical'
$log.Font = New-Object System.Drawing.Font('Consolas', 9)
$log.Anchor = 'Top, Bottom, Left, Right'
$form.Controls.Add($log)

function Write-Log([string]$text) {
    $log.AppendText("[$(Get-Date -Format HH:mm:ss)] $text`r`n")
}

function Update-Links {
    $links.Items.Clear()
    $p = $script:port
    $rows = @(@('This PC (browser test)', "http://localhost:$p", ''))
    $rows += , @('USB phone', "http://localhost:$p", 'press Connect USB phone first')
    $rows += , @('Android emulator on this PC', "http://10.0.2.2:$p", '')
    foreach ($a in Get-LanAddresses) {
        $rows += , @("Phone on same network ($($a.InterfaceAlias))", "http://$($a.IPAddress):$p", 'blocked on some campus Wi-Fi')
    }
    foreach ($r in $rows) {
        $item = New-Object System.Windows.Forms.ListViewItem($r[0])
        [void]$item.SubItems.Add($r[1])
        [void]$item.SubItems.Add($r[2])
        [void]$links.Items.Add($item)
    }
    $links.Items[0].Selected = $true
}

function Get-SelectedAddress {
    if ($links.SelectedItems.Count -eq 0) { return $null }
    return $links.SelectedItems[0].SubItems[1].Text
}

function Set-Status([string]$state) {
    switch ($state) {
        'running' {
            $statusDot.BackColor = [System.Drawing.Color]::FromArgb(46, 160, 67)
            $statusText.Text = "Relay is running on port $($script:port)"
            $startBtn.Enabled = $false; $stopBtn.Enabled = $true; $portBox.Enabled = $false
        }
        'starting' {
            $statusDot.BackColor = [System.Drawing.Color]::Orange
            $statusText.Text = 'Starting relay...'
            $startBtn.Enabled = $false; $stopBtn.Enabled = $true; $portBox.Enabled = $false
        }
        'external' {
            $statusDot.BackColor = [System.Drawing.Color]::FromArgb(46, 160, 67)
            $statusText.Text = "Relay already running on port $($script:port)"
            $startBtn.Enabled = $false; $stopBtn.Enabled = $false; $portBox.Enabled = $true
        }
        default {
            $statusDot.BackColor = [System.Drawing.Color]::Gray
            $statusText.Text = 'Relay is stopped'
            $startBtn.Enabled = $true; $stopBtn.Enabled = $false; $portBox.Enabled = $true
        }
    }
}

$startBtn.Add_Click({
    $script:port = [int]$portBox.Value
    if (Test-RelayHealthy $script:port) { Set-Status 'external'; Update-Links; return }
    try {
        Set-Status 'starting'
        Write-Log 'Starting relay...'
        $script:proc = Start-Relay $script:port
        Update-Links
    } catch {
        Write-Log "Could not start: $($_.Exception.Message)"
        Set-Status 'stopped'
    }
})

$stopBtn.Add_Click({
    Stop-Relay
    Write-Log 'Relay stopped.'
    Set-Status 'stopped'
})

$copyBtn.Add_Click({
    $addr = Get-SelectedAddress
    if ($addr) { [System.Windows.Forms.Clipboard]::SetText($addr); Write-Log "Copied $addr" }
})
$links.Add_DoubleClick({
    $addr = Get-SelectedAddress
    if ($addr) { [System.Windows.Forms.Clipboard]::SetText($addr); Write-Log "Copied $addr" }
})

$openBtn.Add_Click({
    Start-Process "http://localhost:$($script:port)/health"
})

$usbBtn.Add_Click({
    if (-not (Test-Path $Adb)) { Write-Log 'adb not found. Install Android Studio platform-tools.'; return }
    $out = & $Adb reverse "tcp:$($script:port)" "tcp:$($script:port)" 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Log "USB phone connected: on the phone use http://localhost:$($script:port)"
    } else {
        Write-Log "USB connect failed: $out (is the phone plugged in with USB debugging allowed?)"
    }
})

# Poll the relay's output and health without blocking the window.
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 500
$script:ticks = 0
$timer.Add_Tick({
    foreach ($line in Read-RelayOutput) { Write-Log $line }
    $script:ticks++
    if ($script:proc) {
        if ($script:proc.HasExited) {
            foreach ($line in Read-RelayOutput) { Write-Log $line }
            Write-Log "Relay exited (code $($script:proc.ExitCode)). Is port $($script:port) already in use?"
            $script:proc = $null
            Set-Status 'stopped'
        } elseif ($statusText.Text -like 'Starting*' -and ($script:ticks % 2 -eq 0) -and (Test-RelayHealthy $script:port)) {
            Set-Status 'running'
        }
    }
})

$form.Add_Shown({
    Update-Links
    if (Test-RelayHealthy $script:port) {
        Set-Status 'external'
        Write-Log "A relay is already running on port $($script:port) (started outside this window)."
    } else {
        Write-Log 'Press Start to run the relay.'
    }
    $timer.Start()
})

$form.Add_FormClosing({
    $timer.Stop()
    Stop-Relay
})

[void]$form.ShowDialog()
