# Calculadora Penal - script de build e execução (PowerShell)
#
#   .\executar.ps1            compila (se preciso) e sobe o servidor
#   .\executar.ps1 testar     compila e roda os testes automatizados
#   .\executar.ps1 compilar   só compila
#
# Só é preciso ter o Java instalado. Se o Kotlin não estiver no PATH, o script
# baixa o compilador (kotlinc 2.2.20) uma única vez para a pasta ferramentas/.
#
# Se o PowerShell bloquear o script, rode antes (vale só para esta janela):
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

param([ValidateSet("rodar", "testar", "compilar")][string]$Tarefa = "rodar")

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"   # a barra de progresso deixa o download muito lento no PowerShell 5
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$KotlinVersao = "2.2.20"
$SqliteVersao = "3.51.1.0"
$Jar = "build/calculadora-penal.jar"
$JarTestes = "build/testes.jar"
$Driver = "lib/sqlite-jdbc-$SqliteVersao.jar"
$Sep = [IO.Path]::PathSeparator             # ";" no Windows, ":" no Linux/macOS
$EhWindows = $env:OS -eq "Windows_NT"

Set-Location $PSScriptRoot
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

function Falhar($mensagem) {
    Write-Host $mensagem -ForegroundColor Red
    exit 1
}

function Baixar($url, $destino) {
    Invoke-WebRequest -Uri $url -OutFile $destino -UseBasicParsing
}

# ---------------------------------------------------------------------------
# Java
# ---------------------------------------------------------------------------
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Falhar ("Java não encontrado. Instale o Temurin 21 em https://adoptium.net/ " +
            "(marque 'Add to PATH') e abra um PowerShell novo.")
}

# ---------------------------------------------------------------------------
# Kotlin: usa o da pasta ferramentas/; senão, o do PATH; senão, baixa.
# A instalação só é aceita se tiver lib/kotlin-stdlib.jar (a biblioteca padrão).
# ---------------------------------------------------------------------------
$NomeKotlinc = if ($EhWindows) { "kotlinc.bat" } else { "kotlinc" }

function HomeDoKotlin($kotlinc) {
    # .../kotlinc/bin/kotlinc(.bat) -> .../kotlinc
    return Split-Path (Split-Path $kotlinc -Parent) -Parent
}

function InstalacaoValida($kotlinc) {
    if (-not $kotlinc -or -not (Test-Path $kotlinc)) { return $false }
    return Test-Path (Join-Path (HomeDoKotlin $kotlinc) "lib/kotlin-stdlib.jar")
}

function EncontrarKotlinc {
    $local = Join-Path $PSScriptRoot "ferramentas/kotlinc/bin/$NomeKotlinc"
    if (InstalacaoValida $local) { return $local }

    $doPath = Get-Command kotlinc -ErrorAction SilentlyContinue
    if ($doPath) {
        if (InstalacaoValida $doPath.Source) { return $doPath.Source }
        Write-Host "O kotlinc do PATH ($($doPath.Source)) está incompleto: falta lib\kotlin-stdlib.jar. Vou usar um compilador próprio." -ForegroundColor Yellow
    }

    Write-Host "Baixando o compilador Kotlin $KotlinVersao para a pasta ferramentas/ (só na primeira vez, ~80 MB)..." -ForegroundColor Cyan
    Remove-Item ferramentas/kotlinc -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force ferramentas | Out-Null
    $zip = "ferramentas/kotlin-compiler-$KotlinVersao.zip"
    $url = "https://github.com/JetBrains/kotlin/releases/download/v$KotlinVersao/kotlin-compiler-$KotlinVersao.zip"
    try {
        Baixar $url $zip
    } catch {
        Remove-Item $zip -ErrorAction SilentlyContinue
        Falhar ("Não foi possível baixar o Kotlin: $($_.Exception.Message)`n" +
                "Baixe manualmente em $url, extraia o zip dentro da pasta ferramentas/ " +
                "(deve ficar ferramentas/kotlinc/bin) e rode o script de novo.")
    }
    Write-Host "Extraindo (pode levar 1 ou 2 minutos)..." -ForegroundColor Cyan
    Expand-Archive -Path $zip -DestinationPath ferramentas -Force
    Remove-Item $zip
    if (-not $EhWindows) { & chmod +x (Join-Path $PSScriptRoot "ferramentas/kotlinc/bin/kotlinc") }
    if (-not (InstalacaoValida $local)) { Falhar "Extração do Kotlin falhou: $local ou lib\kotlin-stdlib.jar não existe." }
    Write-Host "Kotlin pronto." -ForegroundColor Green
    return $local
}

$Kotlinc = EncontrarKotlinc
$KotlinHome = HomeDoKotlin $Kotlinc
Write-Host "Usando o compilador em $KotlinHome" -ForegroundColor DarkGray

# ---------------------------------------------------------------------------
# Compilação
# ---------------------------------------------------------------------------
function Desatualizado($saida, $pastas) {
    if (-not (Test-Path $saida)) { return $true }
    $data = (Get-Item $saida).LastWriteTime
    $novos = Get-ChildItem $pastas -Recurse -Filter *.kt | Where-Object { $_.LastWriteTime -gt $data }
    return [bool]$novos
}

function Compilar($saida, $pastas) {
    if (Desatualizado $saida $pastas) {
        Write-Host "Compilando $($pastas -join ' + ') (a primeira vez leva até 1 minuto)..." -ForegroundColor Cyan
        New-Item -ItemType Directory -Force build | Out-Null
        # -kotlin-home diz ao compilador, sem adivinhar, onde está a biblioteca padrão (mapOf, listOf, Regex...)
        & $Kotlinc -kotlin-home $KotlinHome @pastas -include-runtime -d $saida
        if ($LASTEXITCODE -ne 0) { Falhar "Erro de compilação." }
    }
}

function BaixarDriver {
    if (Test-Path $Driver) { return }
    Write-Host "Baixando o driver do SQLite ($SqliteVersao) para a pasta lib/ ..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force lib | Out-Null
    $url = "https://github.com/xerial/sqlite-jdbc/releases/download/$SqliteVersao/sqlite-jdbc-$SqliteVersao.jar"
    try {
        Baixar $url $Driver
    } catch {
        Write-Host "Não foi possível baixar o driver. Os contatos ficarão só em memória." -ForegroundColor Yellow
        Remove-Item $Driver -ErrorAction SilentlyContinue
    }
}

switch ($Tarefa) {
    "compilar" { Compilar $Jar @("src") }
    "testar" {
        Compilar $JarTestes @("src", "test")
        & java "-Dstdout.encoding=UTF-8" -cp "$JarTestes${Sep}resources" penal.ExecutarTestes
        exit $LASTEXITCODE
    }
    "rodar" {
        Compilar $Jar @("src")
        BaixarDriver
        & java "-Dstdout.encoding=UTF-8" -cp "$Jar${Sep}resources${Sep}lib/*" penal.MainKt
    }
}
