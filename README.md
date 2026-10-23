# SpamShield BR (`v1.2.1`)

Ecossistema privado de defesa, telemetria e inteligência contra chamadas de spam/robocall no Brasil, composto por:

1. **Android Edge App (`SpamShieldApp`)** — 100% Kotlin + Jetpack Compose + Material 3 + Room SQLite (WAL):
   - Interceptação nativa via `CallScreeningService` (`ROLE_CALL_SCREENING`) em **`< 2 ms`**.
   - Normalização estrita de números brasileiros (`libphonenumber` + inferência de operadora por CSP e faixa RNPM Anatel: `TIM`, `Claro`, `Vivo`, `VoIP / Fixo`).
   - Árvore de decisão híbrida determinística + heurística com suporte a **Modo Sombra**, **Whitelist de Contatos**, **Grace Period de Chamada Efetuada**, **Quarentena Dinâmica `/7`**, **Burst de Vizinhança (`PREFIX_NEIGHBORHOOD_BURST`)** e **Detector Pós-Chamada de Ping Call (`<= 4s`)**.
   - **Analisador de Histórico (`CallHistoryAnalyzer`)**: importa e classifica retroativamente o histórico de chamadas do aparelho (`CallLog.Calls`).
   - **Atualizador OTA integrado**: verifica, baixa e instala novas versões do APK diretamente do servidor via Tailscale.
2. **Backend Rust (`spamshield-server`)** — `axum` + `tokio` + `mongodb`:
   - Sincronização em lote idempotente (`POST /api/v1/telemetry/batch`) com agrupamento paralelo (`futures::future::join_all`).
   - Recalibração automática de pesos em caso de `FALSE_POSITIVE` (`PATCH /api/v1/telemetry/:id/feedback`).
   - Sincronização sob demanda e periódica de faixas **Anatel (Plano de Numeração + RGC `0303`)** e **Blacklists BR** (`POST /api/v1/providers/sync`).
   - Servidor OTA de APK (`GET /api/v1/apk/info` e `GET /apk`).
3. **MongoDB Atlas (`spam_shield_db`)**:
   - Coleções com JSON Schema Validation e índices compostos/TTL: `call_telemetry`, `prefix_quarantine`, `active_rules` e `anatel_numbering_plan`.

## Como Executar

### 1. Servidor Rust (`spamshield-server`)
```powershell
cd spamshield-server
Copy-Item .env.example .env
# Preencha MONGODB_URI no arquivo .env
cargo run
```

### 2. Aplicativo Android (`SpamShieldApp`)
```powershell
powershell -ExecutionPolicy Bypass -File .\SpamShieldApp\build-android.ps1
```
