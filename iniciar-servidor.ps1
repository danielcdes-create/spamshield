# ==============================================================================
# Script de Inicializacao: SpamShield Server + Tailscale Serve Proxy
# ==============================================================================

$ErrorActionPreference = "Stop"

Write-Host ">>> [1/3] Garantindo proxy do Tailscale na porta 8080..." -ForegroundColor Cyan
$tailscaleBin = "C:\Program Files\Tailscale\tailscale.exe"
if (Test-Path $tailscaleBin) {
    & $tailscaleBin serve --bg --tcp 8080 tcp://127.0.0.1:8080
    Write-Host "Proxy Tailscale ativo: 100.83.253.15:8080 -> 127.0.0.1:8080" -ForegroundColor Green
} else {
    Write-Warning "Tailscale executavel nao encontrado em $tailscaleBin."
}

Write-Host ">>> [2/3] Entrando na pasta do servidor..." -ForegroundColor Cyan
$serverDir = Join-Path $PSScriptRoot "spamshield-server"
Set-Location $serverDir

Write-Host ">>> [3/3] Iniciando o servidor Rust..." -ForegroundColor Cyan
cargo run
