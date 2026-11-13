<#
.SYNOPSIS
    Prueft die Abnahmeszenarien S3 bis S6 der Live-Zustellung am laufenden Compose-Stack
    (docs/spec-live-zustellung.md, Abschnitt 8). S7 kommt in einem spaeteren Schritt dazu.

.DESCRIPTION
    Das Skript laeuft im Repository-Root oder von ueberall (es wechselt selbst dorthin).
    Voraussetzung: Der Stack ist gebaut und gestartet (docker compose up -d --build).
    Es veroeffentlicht keine Ports. Alle Zugriffe laufen ueber "docker compose exec"
    mit dem BusyBox-wget im chat-service-Image.

    Pro Szenario wird der gemessene Wert neben den erwarteten ausgegeben. Am Ende gibt es
    eine Zusammenfassung; der Exit-Code ist 1, wenn ein Kriterium nicht erfuellt ist.

.PARAMETER Scenarios
    Welche Szenarien laufen sollen. Standard: S3, S4, S5, S6.

.PARAMETER Build
    Baut die Images vor dem Start neu (docker compose up --build).

.EXAMPLE
    .\scripts\pruefe-live-zustellung.ps1
    .\scripts\pruefe-live-zustellung.ps1 -Scenarios S3,S4
#>
param(
    [string[]]$Scenarios = @('S3', 'S4', 'S5', 'S6'),
    [switch]$Build
)

# Fehlermeldungen von docker (stderr) sollen das Skript nicht abbrechen; Ergebnisse werden selbst geprueft.
$ErrorActionPreference = 'Continue'

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$workDir = Join-Path ([System.IO.Path]::GetTempPath()) ('live-zustellung-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $workDir | Out-Null

$results = New-Object System.Collections.ArrayList
$runningProcesses = New-Object System.Collections.ArrayList
$pausedContainer = $null
# Ob der Befehl "timeout" im Container vorhanden ist (wird beim Start geprueft).
$script:hasTimeout = $false

# ---------------------------------------------------------------------------
# Konfiguration aus .env lesen (nur die Namen, keine Passwoerter im Skript)
# ---------------------------------------------------------------------------

# Liest einen Wert aus der Datei .env. Fehlt die Datei oder der Eintrag, gilt der Standardwert
# (derselbe wie in docker-compose.yml).
function Get-EnvValue([string]$name, [string]$default) {
    $envFile = Join-Path $repoRoot '.env'
    if (Test-Path $envFile) {
        foreach ($line in (Get-Content $envFile)) {
            if ($line -match ('^\s*' + [regex]::Escape($name) + '\s*=\s*(.*?)\s*$')) {
                return $Matches[1]
            }
        }
    }
    return $default
}

$pgUser = Get-EnvValue 'POSTGRES_USER' 'chatapp'
$pgDb = Get-EnvValue 'POSTGRES_DB' 'chat'

# ---------------------------------------------------------------------------
# Ausgabe und Ergebnisse
# ---------------------------------------------------------------------------

# Gibt eine Ueberschrift fuer ein Szenario aus.
function Write-Header([string]$text) {
    Write-Host ''
    Write-Host ('=== ' + $text + ' ===') -ForegroundColor Cyan
}

# Haelt ein Kriterium fest und gibt es sofort aus: erwarteter Wert, gemessener Wert, bestanden oder nicht.
function Add-Result([string]$scenario, [string]$criterion, [string]$expected, [string]$measured, [bool]$passed) {
    $status = 'FEHLER'
    $color = 'Red'
    if ($passed) {
        $status = 'OK'
        $color = 'Green'
    }
    [void]$results.Add([pscustomobject]@{
        Szenario = $scenario; Kriterium = $criterion; Erwartet = $expected; Gemessen = $measured; Ergebnis = $status
    })
    Write-Host ("[{0}] {1}: erwartet {2}, gemessen {3}" -f $status, $criterion, $expected, $measured) -ForegroundColor $color
}

# ---------------------------------------------------------------------------
# Hilfen fuer docker compose
# ---------------------------------------------------------------------------

# Fuehrt docker compose aus und gibt die Ausgabezeilen (stdout) zurueck. stderr wird verworfen.
function Invoke-Compose([string[]]$arguments) {
    $output = & docker compose @arguments 2>$null
    return $output
}

# Liest die Zeilen einer Datei, auch wenn ein anderer Prozess sie gerade beschreibt.
function Read-SharedLines([string]$path) {
    if (-not (Test-Path $path)) {
        return @()
    }
    $stream = [System.IO.File]::Open($path, 'Open', 'Read', 'ReadWrite')
    try {
        $reader = New-Object System.IO.StreamReader($stream)
        $text = $reader.ReadToEnd()
        $reader.Close()
    } finally {
        $stream.Close()
    }
    return $text -split "`n"
}

# Gibt die Zeilen von "rabbitmqctl list_queues" zurueck, aufgeteilt in Name, Nachrichten, Consumer.
function Get-Queues {
    $lines = Invoke-Compose @('exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_queues', 'name', 'messages', 'consumers')
    $queues = New-Object System.Collections.ArrayList
    foreach ($line in $lines) {
        $columns = ([string]$line).Trim() -split "`t"
        if ($columns.Count -eq 3 -and $columns[1] -match '^\d+$') {
            [void]$queues.Add([pscustomobject]@{ Name = $columns[0]; Messages = [int]$columns[1]; Consumers = [int]$columns[2] })
        }
    }
    # Das Komma verhindert, dass PowerShell eine Liste mit einem Eintrag zu einem einzelnen Text macht.
    return , $queues
}

# Gibt die Namen der Queues zurueck, die am Exchange chat.delivery haengen.
function Get-DeliveryBindings {
    $lines = Invoke-Compose @('exec', '-T', 'rabbitmq', 'rabbitmqctl', '-q', 'list_bindings', 'source_name', 'destination_name')
    $names = New-Object System.Collections.ArrayList
    foreach ($line in $lines) {
        $columns = ([string]$line).Trim() -split "`t"
        if ($columns.Count -eq 2 -and $columns[0] -eq 'chat.delivery') {
            [void]$names.Add($columns[1])
        }
    }
    # Das Komma verhindert, dass PowerShell eine Liste mit einem Eintrag zu einem einzelnen Text macht.
    return , $names
}

# Wartet, bis genau $count Queues am Exchange chat.delivery haengen. Gibt die Wartezeit in Sekunden
# zurueck oder -1 beim Zeitlimit.
function Wait-ForBindings([int]$count, [int]$timeoutSeconds) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $timeoutSeconds) {
        $bindings = Get-DeliveryBindings
        if ($bindings.Count -eq $count) {
            return [math]::Round($watch.Elapsed.TotalSeconds, 1)
        }
        Start-Sleep -Seconds 1
    }
    return -1
}

# Wartet, bis die Instanz mit dem Index antwortet. /stream ohne roomId liefert 400 (HTTP-Statuszeile),
# solange der Dienst noch nicht laeuft, kommt "can't connect".
function Wait-ForInstance([int]$index, [int]$timeoutSeconds) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $timeoutSeconds) {
        $answer = Invoke-Compose @('exec', '-T', '--index', "$index", 'chat-service', 'sh', '-c',
            "wget -S -q -O /dev/null 'http://localhost:8082/stream?roomId=x' 2>&1 | head -1")
        if (([string]$answer) -match 'HTTP/') {
            return $true
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Stellt die gewuenschte Anzahl chat-service-Instanzen her und wartet, bis jede eine Queue am Exchange
# hat und antwortet.
function Ensure-Instances([int]$count) {
    $arguments = @('up', '-d', '--scale', "chat-service=$count")
    if ($Build) {
        $arguments += '--build'
    }
    Write-Host ("Stelle {0} chat-service-Instanz(en) her ..." -f $count)
    [void](Invoke-Compose $arguments)
    $waited = Wait-ForBindings $count 120
    if ($waited -lt 0) {
        throw "Es haengen nicht $count Queues am Exchange chat.delivery."
    }
    for ($index = 1; $index -le $count; $index++) {
        if (-not (Wait-ForInstance $index 120)) {
            throw "Instanz $index antwortet nicht."
        }
    }
}

# Gibt den Containernamen der Instanz mit dem Index zurueck (der Name endet auf -<Index>).
function Get-ContainerName([int]$index) {
    $names = Invoke-Compose @('ps', '--format', '{{.Name}}', 'chat-service')
    foreach ($name in $names) {
        if (([string]$name).Trim().EndsWith("-$index")) {
            return ([string]$name).Trim()
        }
    }
    return $null
}

# ---------------------------------------------------------------------------
# Senden und Empfangen
# ---------------------------------------------------------------------------

# Das Sendeskript laeuft im Container: Es schickt <Anzahl> Nachrichten nacheinander per wget an
# localhost:8082. Inhalt "<senderId>-<Nummer>", damit die Reihenfolge pruefbar ist.
$sendScript = @'
#!/bin/sh
# Argumente: Raum-ID, Absender-ID, Anzahl
ROOM="$1"
SENDER="$2"
COUNT="$3"
i=0
while [ "$i" -lt "$COUNT" ]; do
  printf '{"roomId":"%s","senderId":"%s","senderName":"Skript","content":"%s-%s"}' "$ROOM" "$SENDER" "$SENDER" "$i" > "/tmp/m-$SENDER.json"
  wget -q -O /dev/null --header='Content-Type: application/json' --post-file="/tmp/m-$SENDER.json" http://localhost:8082/messages || echo "FEHLER bei Nachricht $i" >&2
  i=$((i+1))
done
echo "FERTIG $COUNT"
# Ende
'@

# Kopiert das Sendeskript (mit Unix-Zeilenenden) in die Instanz mit dem Index.
function Install-SendScript([int]$index) {
    $localFile = Join-Path $workDir 'send.sh'
    $text = $sendScript -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText($localFile, $text, (New-Object System.Text.UTF8Encoding($false)))
    [void](Invoke-Compose @('cp', '--index', "$index", $localFile, 'chat-service:/tmp/send.sh'))
}

# Startet einen Sender im Hintergrund: <count> Nachrichten ueber die Instanz mit dem Index.
# Gibt ein Objekt mit dem Prozess und der Ausgabedatei zurueck.
function Start-Sender([int]$index, [string]$roomId, [string]$senderId, [int]$count) {
    Install-SendScript $index
    $outFile = Join-Path $workDir ("send-$senderId.out")
    $errFile = Join-Path $workDir ("send-$senderId.err")
    $arguments = @('compose', 'exec', '-T', '--index', "$index", 'chat-service', 'sh', '/tmp/send.sh', $roomId, $senderId, "$count")
    $process = Start-Process -FilePath 'docker' -ArgumentList $arguments -RedirectStandardOutput $outFile `
        -RedirectStandardError $errFile -NoNewWindow -PassThru
    [void]$runningProcesses.Add($process)
    return [pscustomobject]@{ Process = $process; OutFile = $outFile; ErrFile = $errFile }
}

# Wartet, bis der Sender fertig ist (Zeile "FERTIG" in der Ausgabe). Gibt $true zurueck, wenn es klappte.
function Wait-ForSender($sender, [int]$timeoutSeconds) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $timeoutSeconds) {
        foreach ($line in (Read-SharedLines $sender.OutFile)) {
            if ($line.StartsWith('FERTIG')) {
                return $true
            }
        }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

# Oeffnet einen SSE-Client in der Instanz mit dem Index und schreibt die Antwort in eine Datei.
# "timeout 900" beendet den wget im Container zur Sicherheit selbst. Wartet auf die Zeile ":connected".
function Start-SseClient([int]$index, [string]$roomId, [string]$name) {
    $outFile = Join-Path $workDir ("sse-$name.txt")
    $errFile = Join-Path $workDir ("sse-$name.err")
    $url = "http://localhost:8082/stream?roomId=$roomId"
    $arguments = @('compose', 'exec', '-T', '--index', "$index", 'chat-service')
    if ($script:hasTimeout) {
        $arguments += @('timeout', '900')
    }
    $arguments += @('wget', '-q', '-O-', $url)
    $process = Start-Process -FilePath 'docker' -ArgumentList $arguments -RedirectStandardOutput $outFile `
        -RedirectStandardError $errFile -NoNewWindow -PassThru
    [void]$runningProcesses.Add($process)
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 60) {
        foreach ($line in (Read-SharedLines $outFile)) {
            if ($line.StartsWith(':connected')) {
                return [pscustomobject]@{ Process = $process; File = $outFile }
            }
        }
        Start-Sleep -Milliseconds 300
    }
    throw "SSE-Client $name hat sich nicht verbunden."
}

# Muster fuer eine Nachrichtenzeile: id, senderId und die Nummer am Ende des Inhalts.
$eventPattern = [regex]'"id":"([0-9a-fA-F\-]{36})".*?"senderId":"([^"]+)".*?"content":"[^"]*?-(\d+)"'

# Liest alle vollstaendigen Nachrichten (data:-Zeilen) aus der Datei eines SSE-Clients.
function Get-Events([string]$file) {
    $events = New-Object System.Collections.ArrayList
    foreach ($rawLine in (Read-SharedLines $file)) {
        $line = $rawLine.TrimEnd("`r")
        if ($line.StartsWith('data:') -and $line.EndsWith('}')) {
            $match = $eventPattern.Match($line)
            if ($match.Success) {
                [void]$events.Add([pscustomobject]@{
                    Id = $match.Groups[1].Value; Sender = $match.Groups[2].Value; Number = [int]$match.Groups[3].Value
                })
            }
        }
    }
    return , $events
}

# Wartet, bis der Client mindestens $expected Nachrichten hat. Gibt die Wartezeit in Sekunden zurueck
# oder -1 beim Zeitlimit.
function Wait-ForEvents([string]$file, [int]$expected, [int]$timeoutSeconds) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt $timeoutSeconds) {
        $events = Get-Events $file
        if ($events.Count -ge $expected) {
            return [math]::Round($watch.Elapsed.TotalSeconds, 1)
        }
        Start-Sleep -Milliseconds 500
    }
    return -1
}

# Prueft die Nachrichten eines Clients: Anzahl, jede id nur einmal, je Absender lueckenlos 0, 1, 2, ...
# in Reihenfolge. $senders ist eine Hashtable Absender -> erwartete Anzahl.
function Test-Events($events, $senders) {
    $ids = New-Object 'System.Collections.Generic.HashSet[string]'
    $next = @{}
    $orderOk = $true
    $violations = 0
    $firstViolation = 'keine'
    $position = 0
    $expectedTotal = 0
    foreach ($key in $senders.Keys) {
        $expectedTotal += $senders[$key]
        $next[$key] = 0
    }
    foreach ($item in $events) {
        $position++
        [void]$ids.Add($item.Id)
        if (-not $next.ContainsKey($item.Sender) -or $next[$item.Sender] -ne $item.Number) {
            $orderOk = $false
            $violations++
            if ($violations -eq 1) {
                $expectedNumber = 'unbekannter Absender'
                if ($next.ContainsKey($item.Sender)) {
                    $expectedNumber = [string]$next[$item.Sender]
                }
                $firstViolation = "Position $position, Absender $($item.Sender), erwartet Nummer $expectedNumber, erhalten $($item.Number)"
            }
        } else {
            $next[$item.Sender] = $next[$item.Sender] + 1
        }
    }
    return [pscustomobject]@{
        Total = $events.Count; ExpectedTotal = $expectedTotal; Unique = $ids.Count; OrderOk = $orderOk
        Violations = $violations; FirstViolation = $firstViolation
    }
}

# Beendet alle Hintergrundprozesse dieses Skripts.
function Stop-AllProcesses {
    foreach ($process in $runningProcesses) {
        try {
            if (-not $process.HasExited) {
                Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            }
        } catch {
            # Prozess ist schon weg.
        }
    }
    $runningProcesses.Clear()
}

# ---------------------------------------------------------------------------
# Szenarien
# ---------------------------------------------------------------------------

# S3: Zustellung an zwei Instanzen.
function Invoke-S3 {
    Write-Header 'S3: Zustellung an zwei Instanzen'
    Ensure-Instances 2
    $roomA = [guid]::NewGuid().ToString()
    $roomB = [guid]::NewGuid().ToString()
    $clientA1 = Start-SseClient 1 $roomA 's3-a1'
    $clientA2 = Start-SseClient 2 $roomA 's3-a2'
    $clientB = Start-SseClient 1 $roomB 's3-b'

    $sender1 = Start-Sender 1 $roomA 'sender-1' 200
    $sender2 = Start-Sender 2 $roomA 'sender-2' 200
    $sent1 = Wait-ForSender $sender1 180
    $sent2 = Wait-ForSender $sender2 180
    if (-not ($sent1 -and $sent2)) {
        Write-Host 'Warnung: Ein Sender meldete kein FERTIG.' -ForegroundColor Yellow
    }

    [void](Wait-ForEvents $clientA1.File 400 60)
    [void](Wait-ForEvents $clientA2.File 400 60)

    # Abschlussmarke an Raum B: Sie laeuft hinter allen Nachrichten durch dieselben Queues.
    $marker = Start-Sender 1 $roomB 'marker' 1
    [void](Wait-ForSender $marker 30)
    [void](Wait-ForEvents $clientB.File 1 30)
    Start-Sleep -Seconds 2

    $expected = @{ 'sender-1' = 200; 'sender-2' = 200 }
    $names = @(@{ Name = 'Client Raum A an Instanz 1'; File = $clientA1.File }, @{ Name = 'Client Raum A an Instanz 2'; File = $clientA2.File })
    foreach ($entry in $names) {
        $check = Test-Events (Get-Events $entry.File) $expected
        Add-Result 'S3' ($entry.Name + ': Anzahl Nachrichten') '400' ([string]$check.Total) ($check.Total -eq 400)
        Add-Result 'S3' ($entry.Name + ': jede id genau einmal') '400 verschiedene' ([string]$check.Unique) ($check.Unique -eq 400)
        Add-Result 'S3' ($entry.Name + ': Reihenfolge je Absender') 'lueckenlos in Reihenfolge' ("$($check.OrderOk); erste Abweichung: $($check.FirstViolation); Abweichungen: $($check.Violations)") $check.OrderOk
    }
    $eventsB = Get-Events $clientB.File
    $fromA = 0
    foreach ($item in $eventsB) {
        if ($item.Sender -ne 'marker') {
            $fromA++
        }
    }
    Add-Result 'S3' 'Client Raum B: Nachrichten aus Raum A' '0' ([string]$fromA) ($fromA -eq 0)
    Add-Result 'S3' 'Client Raum B: eigene Marke' '1' ([string]$eventsB.Count) ($eventsB.Count -eq 1)
    Stop-AllProcesses
}

# S4: Queue-Lebenszyklus beim Verkleinern von zwei auf eine Instanz.
function Invoke-S4 {
    Write-Header 'S4: Queue-Lebenszyklus'
    Ensure-Instances 2
    $bindings = Get-DeliveryBindings
    $queues = Get-Queues
    Write-Host 'rabbitmqctl list_queues name messages consumers:'
    foreach ($queue in $queues) {
        Write-Host ('  {0}  messages={1}  consumers={2}' -f $queue.Name, $queue.Messages, $queue.Consumers)
    }
    Write-Host 'Bindungen am Exchange chat.delivery:'
    foreach ($name in $bindings) {
        Write-Host ('  chat.delivery -> ' + $name)
    }
    Add-Result 'S4' 'Queues am Exchange bei zwei Instanzen' '2' ([string]$bindings.Count) ($bindings.Count -eq 2)
    $consumersOk = $true
    foreach ($name in $bindings) {
        foreach ($queue in $queues) {
            if ($queue.Name -eq $name -and $queue.Consumers -ne 1) {
                $consumersOk = $false
            }
        }
    }
    Add-Result 'S4' 'Consumer je Instanz-Queue' '1' ([string]$consumersOk) $consumersOk

    Write-Host 'Skaliere auf eine Instanz ...'
    [void](Invoke-Compose @('up', '-d', '--scale', 'chat-service=1'))
    $waited = Wait-ForBindings 1 60
    $measured = 'Zeitlimit'
    if ($waited -ge 0) {
        $measured = "$waited s"
    }
    Add-Result 'S4' 'Eine Queue am Exchange, spaetestens nach' '<= 30 s' $measured (($waited -ge 0) -and ($waited -le 30))

    $queuesAfter = Get-Queues
    $remaining = Get-DeliveryBindings
    $remainingConsumers = -1
    foreach ($queue in $queuesAfter) {
        if ($remaining.Count -eq 1 -and $queue.Name -eq $remaining[0]) {
            $remainingConsumers = $queue.Consumers
        }
    }
    Add-Result 'S4' 'Consumer der verbleibenden Queue' '1' ([string]$remainingConsumers) ($remainingConsumers -eq 1)
    $hasPersist = $false
    $hasDlq = $false
    foreach ($queue in $queuesAfter) {
        if ($queue.Name -eq 'chat.persist') { $hasPersist = $true }
        if ($queue.Name -eq 'chat.dlq') { $hasDlq = $true }
    }
    Add-Result 'S4' 'Queues chat.persist und chat.dlq vorhanden' 'ja' ([string]($hasPersist -and $hasDlq)) ($hasPersist -and $hasDlq)
}

# S5: Live-Zustellung ohne PostgreSQL und batch-writer.
function Invoke-S5 {
    Write-Header 'S5: Live-Zustellung ohne PostgreSQL und batch-writer'
    Ensure-Instances 1
    $room = [guid]::NewGuid().ToString()
    $client = Start-SseClient 1 $room 's5'

    Write-Host 'Stoppe batch-writer und postgres ...'
    [void](Invoke-Compose @('stop', 'batch-writer', 'postgres'))
    $sendWatch = [System.Diagnostics.Stopwatch]::StartNew()
    $sender = Start-Sender 1 $room 'sender-1' 100
    $sent = Wait-ForSender $sender 120
    $sendSeconds = [math]::Round($sendWatch.Elapsed.TotalSeconds, 1)
    $waited = Wait-ForEvents $client.File 100 5
    $events = Get-Events $client.File
    $check = Test-Events $events @{ 'sender-1' = 100 }
    $measured = "$($check.Total) Nachrichten"
    if ($waited -ge 0) {
        $measured = "$($check.Total) Nachrichten, $waited s nach Sendeende (Senden dauerte $sendSeconds s)"
    }
    Add-Result 'S5' 'Client erhaelt alle 100 Nachrichten, spaetestens 5 s nach dem Senden' '100' $measured (($waited -ge 0) -and ($check.Total -eq 100))
    Add-Result 'S5' 'Reihenfolge und keine Doppelten' 'lueckenlos, 100 verschiedene' ("$($check.Unique) verschieden, Reihenfolge $($check.OrderOk); erste Abweichung: $($check.FirstViolation); Abweichungen: $($check.Violations)") ($check.OrderOk -and $check.Unique -eq 100)
    Stop-AllProcesses

    Write-Host 'Starte postgres und batch-writer wieder ...'
    [void](Invoke-Compose @('start', 'postgres', 'batch-writer'))
    $count = -1
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 120) {
        $answer = Invoke-Compose @('exec', '-T', 'postgres', 'psql', '-U', $pgUser, '-d', $pgDb, '-t', '-A', '-c',
            "SELECT count(*) FROM message WHERE room_id = '$room'")
        $text = ([string]$answer).Trim()
        if ($text -match '^\d+$') {
            $count = [int]$text
            if ($count -ge 100) {
                break
            }
        }
        Start-Sleep -Seconds 3
    }
    Add-Result 'S5' 'Gespeicherte Nachrichten des Testraums nach dem Neustart' '100' ([string]$count) ($count -eq 100)
}

# S6: Queue-Limit bei pausierter Instanz.
function Invoke-S6 {
    Write-Header 'S6: Queue-Limit bei pausierter Instanz'
    Ensure-Instances 2
    $room = [guid]::NewGuid().ToString()
    $active = Get-ContainerName 1
    $paused = Get-ContainerName 2
    if (($null -eq $active) -or ($null -eq $paused)) {
        throw 'Containernamen der beiden Instanzen nicht gefunden.'
    }
    Write-Host ("Aktive Instanz: {0}, pausierte Instanz: {1}" -f $active, $paused)
    $client = Start-SseClient 1 $room 's6'

    & docker pause $paused | Out-Null
    $script:pausedContainer = $paused
    $pauseWatch = [System.Diagnostics.Stopwatch]::StartNew()
    Write-Host 'Instanz pausiert. Sende 5000 Nachrichten an die aktive Instanz ...'
    # Ein einzelner, sequentieller Absender: RabbitMQ garantiert die Reihenfolge nur je Channel.
    $senderOne = Start-Sender 1 $room 'sender-1' 5000

    # Die Queues wiederholt messen: waehrend des Sendens und danach, insgesamt mindestens 65 Sekunden
    # nach dem Pausieren, damit der Heartbeat-Ausfall von RabbitMQ sichtbar werden kann.
    $maxMessages = 0
    $minQueues = 99
    $queueDroppedAt = -1
    $samples = 0
    while ($true) {
        $queues = Get-Queues
        $deliveryQueues = 0
        $line = ''
        foreach ($queue in $queues) {
            if ($queue.Name.StartsWith('spring.gen-')) {
                $deliveryQueues++
                $line += (' {0}...={1}' -f $queue.Name.Substring(0, 16), $queue.Messages)
                if ($queue.Messages -gt $maxMessages) {
                    $maxMessages = $queue.Messages
                }
            }
        }
        if ($deliveryQueues -lt $minQueues) {
            $minQueues = $deliveryQueues
        }
        if (($deliveryQueues -lt 2) -and ($queueDroppedAt -lt 0)) {
            $queueDroppedAt = [math]::Round($pauseWatch.Elapsed.TotalSeconds, 0)
        }
        $samples++
        if (($samples % 3) -eq 1) {
            Write-Host ('  t={0,3} s  messages je Queue:{1}' -f [math]::Round($pauseWatch.Elapsed.TotalSeconds, 0), $line)
        }
        $sendersDone = $senderOne.Process.HasExited
        if ($sendersDone -and ($pauseWatch.Elapsed.TotalSeconds -ge 65)) {
            break
        }
        if ($pauseWatch.Elapsed.TotalSeconds -ge 240) {
            break
        }
        Start-Sleep -Seconds 2
    }
    Add-Result 'S6' 'Hoechste gemessene Zahl Nachrichten (Spalte messages) je Queue' '<= 1000' ([string]$maxMessages) ($maxMessages -le 1000)
    $queueInfo = 'Queue der pausierten Instanz blieb bestehen'
    if ($queueDroppedAt -ge 0) {
        $queueInfo = "Queue der pausierten Instanz verschwand nach etwa $queueDroppedAt s (Heartbeat)"
    }
    Write-Host ('Hinweis: ' + $queueInfo) -ForegroundColor Yellow

    $waited = Wait-ForEvents $client.File 5000 90
    $check = Test-Events (Get-Events $client.File) @{ 'sender-1' = 5000 }
    Add-Result 'S6' 'Client an der aktiven Instanz erhaelt alle Nachrichten' '5000' ([string]$check.Total) ($check.Total -eq 5000)
    Add-Result 'S6' 'Reihenfolge je Absender, keine Doppelten' 'lueckenlos, 5000 verschiedene' ("$($check.Unique) verschieden, Reihenfolge $($check.OrderOk); erste Abweichung: $($check.FirstViolation); Abweichungen: $($check.Violations)") ($check.OrderOk -and $check.Unique -eq 5000)
    Stop-AllProcesses

    Write-Host 'Setze die pausierte Instanz fort ...'
    & docker unpause $paused | Out-Null
    $script:pausedContainer = $null
    $rebound = Wait-ForBindings 2 90
    $reboundText = 'Zeitlimit'
    if ($rebound -ge 0) {
        $reboundText = "$rebound s"
    }
    Add-Result 'S6' 'Fortgesetzte Instanz hat wieder eine Queue am Exchange' 'ja, innerhalb 90 s' $reboundText ($rebound -ge 0)

    # Eine neue Nachricht ueber die aktive Instanz muss die fortgesetzte Instanz wieder zustellen:
    # Ein Client an der fortgesetzten Instanz (Index 2) in einem neuen Raum erhaelt sie.
    $roomAfter = [guid]::NewGuid().ToString()
    [void](Wait-ForInstance 2 60)
    $clientAfter = Start-SseClient 2 $roomAfter 's6-nach-unpause'
    $sendAfter = Start-Sender 1 $roomAfter 'sender-1' 1
    [void](Wait-ForSender $sendAfter 30)
    $waitedAfter = Wait-ForEvents $clientAfter.File 1 30
    $afterText = 'nicht angekommen'
    if ($waitedAfter -ge 0) {
        $afterText = "angekommen nach $waitedAfter s"
    }
    Add-Result 'S6' 'Fortgesetzte Instanz stellt neue Nachricht zu' 'angekommen' $afterText ($waitedAfter -ge 0)
    Stop-AllProcesses
}

# ---------------------------------------------------------------------------
# Ablauf
# ---------------------------------------------------------------------------

$exitCode = 0
try {
    Write-Host 'Pruefung der Live-Zustellung (S3 bis S6)' -ForegroundColor Cyan
    # Jedes Werkzeug einzeln pruefen: "command -v" mit mehreren Namen liefert in BusyBox nicht fuer alle eine Zeile.
    $toolCheck = [string](Invoke-Compose @('exec', '-T', 'chat-service', 'sh', '-c',
        'for t in wget timeout; do if command -v $t >/dev/null 2>&1; then echo ok-$t; else echo missing-$t; fi; done'))
    Write-Host ('Werkzeuge im Container: ' + $toolCheck)
    if ($toolCheck -notmatch 'ok-wget') {
        throw 'Im chat-service-Container fehlt wget oder der Container laeuft nicht (docker compose up -d --build).'
    }
    $script:hasTimeout = ($toolCheck -match 'ok-timeout')
    if (-not $script:hasTimeout) {
        Write-Host 'Hinweis: "timeout" fehlt im Container. SSE-Clients enden dann erst, wenn der Dienst die Verbindung schliesst.' -ForegroundColor Yellow
    }
    foreach ($scenario in $Scenarios) {
        switch ($scenario.ToUpper()) {
            'S3' { Invoke-S3 }
            'S4' { Invoke-S4 }
            'S5' { Invoke-S5 }
            'S6' { Invoke-S6 }
            default { Write-Host ("Unbekanntes Szenario: " + $scenario) -ForegroundColor Yellow }
        }
    }
} catch {
    Write-Host ('ABBRUCH: ' + $_.Exception.Message) -ForegroundColor Red
    $exitCode = 1
} finally {
    Stop-AllProcesses
    if ($null -ne $pausedContainer) {
        & docker unpause $pausedContainer 2>$null | Out-Null
    }
    # Postgres und batch-writer sollen nach einem Abbruch in S5 wieder laufen.
    [void](Invoke-Compose @('start', 'postgres', 'batch-writer'))
    Remove-Item -Recurse -Force $workDir -ErrorAction SilentlyContinue
}

Write-Header 'Zusammenfassung'
foreach ($result in $results) {
    $color = 'Green'
    if ($result.Ergebnis -ne 'OK') {
        $color = 'Red'
    }
    Write-Host ('{0,-6} {1}: erwartet {2}, gemessen {3} -> {4}' -f $result.Szenario, $result.Kriterium, $result.Erwartet, $result.Gemessen, $result.Ergebnis) -ForegroundColor $color
}
$failed = 0
foreach ($result in $results) {
    if ($result.Ergebnis -ne 'OK') {
        $failed++
    }
}
Write-Host ("{0} Kriterien geprueft, {1} nicht erfuellt." -f $results.Count, $failed)
if (($failed -gt 0) -or ($exitCode -ne 0)) {
    exit 1
}
exit 0
