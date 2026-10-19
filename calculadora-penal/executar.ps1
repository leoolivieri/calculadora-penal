# Calculadora Penal - script de build e execução (Windows PowerShell)
#
#   .\executar.ps1            compila (se preciso) e sobe o servidor
#   .\executar.ps1 testar     compila e roda os testes automatizados
#   .\executar.ps1 compilar   só compila
#
# Se o PowerShell bloquear o script, rode antes (vale só para esta janela):
#   Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass

param([ValidateSet("rodar", "testar", "compilar")][string]$Tarefa = "rodar")

$SqliteVersao = "3.51.1.0"
$Jar = "build/calculadora-penal.jar"
$JarTestes = "build/testes.jar"
$Driver = "lib/sqlite-jdbc-$SqliteVersao.jar"

Set-Location $PSScriptRoot
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if (-not (Get-Command kotlinc -ErrorAction SilentlyContinue)) {
    Write-Host "kotlinc não encontrado no PATH. No laboratório, rode por exemplo:" -ForegroundColor Red
    Write-Host '  $env:Path += ";C:\Users\SEU_RA\Downloads\kotlin-compiler-2.2.20\kotlinc\bin"'
    exit 1
}

function Desatualizado($saida, $pastas) {
    if (-not (Test-Path $saida)) { return $true }
    $data = (Get-Item $saida).LastWriteTime
    $novos = Get-ChildItem $pastas -Recurse -Filter *.kt | Where-Object { $_.LastWriteTime -gt $data }
    return [bool]$novos
}

function Compilar($saida, $pastas) {
    if (Desatualizado $saida $pastas) {
        Write-Host "Compilando $($pastas -join ' + ') ..." -ForegroundColor Cyan
        New-Item -ItemType Directory -Force build | Out-Null
        & kotlinc @pastas -include-runtime -d $saida
        if ($LASTEXITCODE -ne 0) { Write-Host "Erro de compilação." -ForegroundColor Red; exit 1 }
    }
}

function BaixarDriver {
    if (Test-Path $Driver) { return }
    Write-Host "Baixando o driver do SQLite ($SqliteVersao) para a pasta lib/ ..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force lib | Out-Null
    $url = "https://github.com/xerial/sqlite-jdbc/releases/download/$SqliteVersao/sqlite-jdbc-$SqliteVersao.jar"
    try {
        Invoke-WebRequest -Uri $url -OutFile $Driver -UseBasicParsing
    } catch {
        Write-Host "Não foi possível baixar o driver. Os contatos ficarão só em memória." -ForegroundColor Yellow
        Remove-Item $Driver -ErrorAction SilentlyContinue
    }
}

switch ($Tarefa) {
    "compilar" { Compilar $Jar @("src") }
    "testar" {
        Compilar $JarTestes @("src", "test")
        & java "-Dstdout.encoding=UTF-8" -cp "$JarTestes;resources" penal.ExecutarTestes
        exit $LASTEXITCODE
    }
    "rodar" {
        Compilar $Jar @("src")
        BaixarDriver
        & java "-Dstdout.encoding=UTF-8" -cp "$Jar;resources;lib/*" penal.MainKt
    }
}
