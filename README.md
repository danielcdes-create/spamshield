# SpamShield BR (`v1.2.5` — Build `9`)

Ecossistema privado de defesa em borda (`< 2 ms`), telemetria vetorial e inteligência contra chamadas de spam, robocall, chipeiras GSM e spoofing no Brasil (padrões Anatel RNPM / Ato nº 10.413 / STIR-SHAKEN).

---

## 1. Arquitetura do Sistema

1. **Android Edge App (`SpamShieldApp`)** — 100% Kotlin + Jetpack Compose + Material 3 + Room SQLite (WAL) + Hilt + WorkManager:
   - **Interceptação Nativa (`< 2 ms`):** `CallScreeningService` (`ROLE_CALL_SCREENING`) com bloqueio silencioso antes do toque (`setDisallowCall`, `setRejectCall`, `setSkipNotification`).
   - **Aprendizado Ativo via CallLog do Sistema (`evaluatePostCallLogVerdict`):**
     - O Android CallScreening executa antes do discador tocar. Quando uma chamada desconhecida toca e é marcada como spam ou bloqueada/rejeitada pelo usuário ou pelo discador nativo (ex: Google Phone com `BLOCKED_TYPE` ou `REJECTED_TYPE`), o `CallStateBroadcastReceiver` intercepta o evento pós-chamada no `CallLog`.
     - O `SpamScoringEngine` reavalia o veredito via `evaluatePostCallLogVerdict()`, elevando o score para `85` (`BLOQUEADA`), registrando `SYSTEM_CALLLOG_BLOCKED` (`+45`) ou `DIALER_REJECTED` (`+45`) e inserindo imediatamente o prefixo `/7` na tabela `prefix_quarantine` com bônus de quarentena ativa (`+70`).
     - Novas chamadas do mesmo prefixo ou de vizinhos no mesmo bloco são bloqueadas na borda antes de qualquer toque.
   - **Normalização Telefônica Brasileira (`PhoneNormalizer`):**
     - Tratamento de formato `E.164` (`+55`), prefixos de tronco com zero (`031...`, `011...`), Códigos de Seleção de Prestadora (`015`, `021`, `041`, `031`) e chamadas internacionais/duplo zero (`004131...`).
     - **Separação Estrita entre `VoIP` e `Fixo`:**
       - **`VoIP`:** Numeração não-geográfica (`0300`, `0303`, `0800`, `4004`, `3003`, `4003`), códigos curtos de telemarketing (`10315`, headers SIP curtos) e faixas de tronco PABX/SIP/DDR (`210x`–`212x`, `251x`–`255x`, `300x`–`319x`, `400x`–`419x`, `485x`, `502x`–`509x`). Prioridade absoluta sobre o CSP para impedir que chamadas VoIP discadas via CSP (`041 11 4004-3535`) sejam mascaradas como móveis.
       - **`Fixo`:** Linhas fixas STFC geográficas tradicionais (`320x`–`399x`, ex.: `31 3287-xxxx`, `31 3500-xxxx`, `11 3658-xxxx`).
     - **Inferência de Operadora Móvel (RNPM Anatel):** Mapeamento canônico para `Claro` (incluindo faixas MVNO/migradas `937`–`939` e `980`), `TIM`, `Vivo` e `Outros`.
   - **Motor Heurístico Vetorial (`SpamScoringEngine`):**
     - **Short-Circuits Seguros:**
       - `CONTACT_WHITELIST` (`-1000`): Imunidade para contatos da agenda.
       - `OUTBOUND_GRACE_PERIOD` (`-500`): Imunidade de 2h para retorno do mesmo número ou sub-bloco `/6` após chamada efetuada pelo usuário.
       - `EMERGENCY_REPEATED_CALL` (`-500`): Blindada — concedida **exclusivamente** a linhas `MOBILE` sem falha `STIR_SHAKEN`, sem cabeçalho CSP/tronco anômalo (`hasAbnormalTrunkOrCspHeader`) e cujo prefixo `/7` ou `/6` não esteja em quarentena.
     - **Regras Cumulativas de Pontuação (Threshold padrão `>= 70`):**
       - `NOT_IN_CONTACTS` (`+25`)
       - `STIR_SHAKEN_FAILED` (`+85`) / `STIR_SHAKEN_UNVERIFIED` (`+20`)
       - `PREFIX_QUARANTINE_HIT` (`+60` a `+80`): Quarentena ativa no prefixo exato `/7`.
       - `PREFIX6_CLUSTER_HIT` (`+45`): Sub-bloco `/6` (`DDD + 4 dígitos`) com `>= 2` números distintos ou `>= 2` quarentenas ativas em 24h.
       - `PREFIX6_NEIGHBOR_WARNING` (`+25`): Sub-bloco `/6` com `1` prefixo vizinho em quarentena ativa recente.
       - `PREFIX5_CHIPEIRA_FARM` (`+35`): Fazenda de chipeira GSM dedicada no bloco `/5` (`937`–`939`, `980`, `910`) ou alta densidade (`>= 6` prefixos `/7` em quarentena no mesmo `/5`).
       - `PREFIX_NEIGHBORHOOD_BURST` (`+60`): Múltiplos números distintos (`>= 2`) do mesmo prefixo `/7` em 24h.
       - `ANATEL_PABX_VOIP_RANGE` (`+35`): Linha `FIXED_OR_VOIP` / `TOLL_FREE_OR_SPECIAL` / PABX corporativo.
       - `EXTERNAL_DDD_UNKNOWN` (`+30`): Chamada originada fora do DDD local do usuário sem contato na agenda.
       - `LEADING_ZERO_TRUNK_DIALING` (`+25`): Dispara apenas em cabeçalhos de tronco/CSP anômalos (`04111...`, `015...`, `004131...`, `0300`/`0303`/`0800`, códigos curtos `03110315`/`005`, DDD externo ou linha fixa/VoIP `0314101...`). Não penaliza o formato padrão de entrega CLI móvel local da operadora (`0319xxxxxxxx` de 12 dígitos no próprio DDD).
       - `CONVERSATION_ANSWERED_BONUS` (`-40`): Bônus de legitimidade no histórico para chamadas móveis atendidas com duração `>= 10s`.
       - `PING_CALL_PENALTY` (`+55`): Detector pós-chamada (`CallStateMonitor`) para chamadas mudas desligadas em `<= 4s`.
       - `SYSTEM_CALLLOG_BLOCKED` / `DIALER_REJECTED` (`+45`): Aprendizado ativo quando o discador do sistema ou usuário rejeita/bloqueia a ligação.
       - Regras Regex (`ANATEL_ATO_10413_0303`, `BLACKLIST_MG_VOIP_PABX`, `BLACKLIST_SP_VOIP_ROBOCALL`, `REGEX_TELEMARKETING_NATIONAL`).
   - **Analisador e Reconciliador Retroativo (`CallHistoryAnalyzer`):**
     - Reconciliação contínua e retroativa com `CallLog.Calls` (`reconcileRecentCallsWithCallLog`), atualizando vereditos e quarentenas em tempo real.
     - Importa o histórico do sistema, deduplica registros em tempo real (`android-edge-br`) vs histórico (`s23-ultra-history`) na janela de `±15s`, expurga quarentenas de chamada única sem evidência de cluster e executa `reprocessAllStoredTelemetry()` para aplicar retroativamente todas as regras, classificações (`VoIP` vs `Fixo`) e correções de operadora em todo o banco SQLite local.
   - **Exportação e Compartilhamento Nativo (`SettingsScreen` / `MainViewModel`):**
     - Exportação completa da telemetria em JSON (`encodeDefaults = true`) e CSV com reconciliação prévia e abertura imediata do Android Share Sheet (`FileProvider` + `Intent.ACTION_SEND_MULTIPLE`).
   - **Atualizador OTA via Tailscale:** Verifica (`GET /api/v1/apk/info`), baixa (`GET /apk`) e instala novas versões do APK diretamente pelo app.

2. **Backend Rust (`spamshield-server`)** — `axum` + `tokio` + `mongodb` + `rayon`:
   - **Ingestão Idempotente e Atualização Retroativa (`POST /api/v1/telemetry/batch`):** Insere novos registros e atualiza integralmente (`call_details`, `decision_vector` e `audit`) registros existentes reprocessados ou auditados pelo usuário.
   - **Migração Retroativa Automática (`AppDatabase::migrate_historical_records`):** Na inicialização do servidor, normaliza e reclassifica automaticamente todo o histórico no MongoDB Atlas (`call_telemetry`, `prefix_quarantine` e `anatel_numbering_plan`), separando `VoIP` de `Fixo`, preservando quarentenas derivadas de vereditos do sistema (`SYSTEM_CALLLOG_BLOCKED`, `DIALER_REJECTED`) e corrigindo operadoras e chamadas legadas.
   - **Feedback e Auto-Recalibração (`PATCH /api/v1/telemetry/:id/feedback`):**
     - `CONFIRMED_SPAM`: Promove imediatamente `outcome = BLOCKED`, `category = ROBOCALL_PREFIX_CLUSTER`, `final_score >= 100` e insere o prefixo `/7` em quarentena (`+75`).
     - `FALSE_POSITIVE`: Promove `outcome = ALLOWED_CONTACT`, `category = LEGITIMATE`, `final_score = 0`, remove o prefixo da quarentena e reduz automaticamente o peso das regras heurísticas em `15%` (piso `10`).
   - **Sincronização Anatel e Blacklists BR (`POST /api/v1/providers/sync`):** Parser CSV paralelo em Rayon para planos de numeração Anatel, regras `0303` e clusters conhecidos de robocall.

3. **MongoDB Atlas (`spam_shield_db`)**:
   - Coleções com JSON Schema Validation e índices compostos/TTL: `call_telemetry`, `prefix_quarantine`, `active_rules` e `anatel_numbering_plan`.
   - **Feedback e Auto-Recalibração (`PATCH /api/v1/telemetry/:id/feedback`):**
     - `CONFIRMED_SPAM`: Promove imediatamente `outcome = BLOCKED`, `category = ROBOCALL_PREFIX_CLUSTER`, `final_score >= 100` e insere o prefixo `/7` em quarentena (`+75`).
     - `FALSE_POSITIVE`: Promove `outcome = ALLOWED_CONTACT`, `category = LEGITIMATE`, `final_score = 0`, remove o prefixo da quarentena e reduz automaticamente o peso das regras heurísticas em `15%` (piso `10`).
   - **Sincronização Anatel e Blacklists BR (`POST /api/v1/providers/sync`):** Parser CSV paralelo em Rayon para planos de numeração Anatel, regras `0303` e clusters conhecidos de robocall.

3. **MongoDB Atlas (`spam_shield_db`)**:
   - Coleções com JSON Schema Validation e índices compostos/TTL: `call_telemetry`, `prefix_quarantine`, `active_rules` e `anatel_numbering_plan`.

---

## 2. Endpoints da API (`spamshield-server` — Porta `8080`)

| Método | Rota | Descrição |
| :--- | :--- | :--- |
| `GET` | `/api/v1/health` | Status do servidor e latência do MongoDB Atlas |
| `POST` | `/api/v1/telemetry/batch` | Recebe lote de telemetria e atualiza registros reprocessados |
| `PATCH` | `/api/v1/telemetry/:id/feedback` | Registra `CONFIRMED_SPAM` ou `FALSE_POSITIVE` com recalibração |
| `GET` | `/api/v1/sync/rules` | Retorna regras ativas, quarentenas `/7` e prefixos Anatel |
| `POST` | `/api/v1/rules` | Cria ou atualiza regra customizada (Regex/Heurística) |
| `DELETE` | `/api/v1/rules/:rule_id` | Remove regra customizada |
| `POST` | `/api/v1/quarantine` | Adiciona prefixo `/7` manualmente na quarentena |
| `DELETE` | `/api/v1/quarantine/:prefix7` | Remove prefixo `/7` da quarentena |
| `POST` | `/api/v1/providers/sync` | Sincroniza base Anatel e Blacklists comunitárias BR |
| `POST` | `/api/v1/anatel/sync` | Importa planilha CSV de numeração Anatel via Rayon |
| `GET` | `/api/v1/apk/info` | Metadados da versão atual do APK disponível para OTA |
| `GET` | `/apk` | Download direto do pacote `SpamShieldBR-debug.apk` |

---

## 3. Como Compilar e Executar

### 3.1. Servidor Rust (`spamshield-server`)
```powershell
cd C:\Users\danie\spamshield\spamshield-server
Copy-Item .env.example .env
# Configure MONGODB_URI e MONGODB_DIRECT_URI no arquivo .env
cargo test
cargo run
```

### 3.2. Aplicativo Android (`SpamShieldApp`)
```powershell
powershell -ExecutionPolicy Bypass -File C:\Users\danie\spamshield\SpamShieldApp\build-android.ps1
```
O script executa os testes unitários (`testDebugUnitTest`), compila o APK (`assembleDebug`) e publica os artefatos em `SpamShieldApp\dist\SpamShieldBR-debug.apk` e `SpamShieldApp\dist\apk-info.json` para distribuição imediata via OTA no Tailscale (`http://100.83.253.15:8080/apk`).
