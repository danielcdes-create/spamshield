# SpamShield BR (`v1.3.2` — Build `16`)

Ecossistema privado de defesa em borda (`< 2 ms`), telemetria vetorial auditável e inteligência contra chamadas de spam, robocall, chipeiras GSM e spoofing no Brasil (padrões Anatel RNPM / Ato nº 10.413 / STIR-SHAKEN).

### Novidades da versão `v1.3.2` (Build `16`):
- **Fidelidade Causal no Reprocessamento (`reprocessStoredTelemetry`):** Chamadas interceptadas em tempo real (`SCREENING_DECISION`) preservam seu estado original de quarentena (`quarantineMatch`), rajada de 24h (`distinctNumbers24h`) e alertas de vizinhança (`PREFIX6_NEIGHBOR_WARNING`, `PREFIX5_CHIPEIRA_FARM`), impedindo que quarentenas ou rajadas futuras contaminem retroativamente chamadas passadas.
- **Idempotência de Eventos e Coalescência Blindada (15s):**
  - Deduplicação semântica de `QUARANTINE_UPDATED` por `(callId, prefix, baseReason)` (ignorando jitter de `expiresAt`) e de eventos de avaliação (`POST_CALL_EVALUATION`, `REPROCESS_EVALUATION`, `HISTORY_ASSESSMENT`) quando score, outcome, categoria e regras permanecem idênticos, com poda automática de eventos redundantes no SQLite (`DecisionTrace.pruneRedundantEvents`).
  - Proteção contra *redials* na janela de 15s: uma vez vinculada a observação `CALL_LOG_OBSERVATION` de timestamp mais próximo, rediscagens subsequentes com timestamp mais distante não sobrescrevem o evento nem causam oscilação (`REJECTED` $\leftrightarrow$ `BLOCKED`).
  - Deduplicação retroativa de códigos curtos de utilidade (`10315`, `005` com `prefix7 = ""`) e registros legados `calllog-...`, preservando e reatribuindo integralmente seus `decisionEvents`.
- **Normalização de Cabeçalhos de País (`055` / `0055`):** `PhoneNormalizer.parseGeographic` descasca prefixos de país discados como tronco nacional (`05531...`, `005531...`) antes de DDDs válidos, normalizando para E.164 (`+5531...`), extraindo `cspExtracted = "055"`, inferindo corretamente a operadora móvel e migrando quarentenas corrompidas `055...` para o prefixo canônico de 8 dígitos (`DDD + 6`).

### Novidades da versão `v1.3.1` (Build `15`):
- **Diário Auditável de Decisões (`call_decision_events` / `decisionEvents`):** Preserva separadamente a decisão original de chegada (`SCREENING_DECISION`), a resposta enviada ao Android Telecom (`TELECOM_RESPONSE`), a observação real do `CallLog` (`CALL_LOG_OBSERVATION`), avaliações pós-chamada (`POST_CALL_EVALUATION`), reprocessamentos (`REPROCESS_EVALUATION`), feedback do usuário (`USER_FEEDBACK`) e atualizações de quarentena (`QUARANTINE_UPDATED`). Consulte o [contrato de telemetria](docs/TELEMETRIA-DECISOES.md).
- **Sincronia Estrita de Cache (`QuarantinePrefixIndex`):** Cache em memória de prefixos `/8` (`EXACT8`, peso `+70`) e `/7` legado (`BROAD7`, peso `+60`) mantido sincronizado em todas as operações de triagem, reconciliação do `CallLog`, feedback do usuário, sync com o servidor e reprocessamento.

### Novidades da versão `v1.3.0` (Build `14`):
- Parser E.164 determinístico, prefixo canônico de 8 dígitos (`/8`), grupo de exclusão mútua (`VOIP_TRUNK_GROUP`) e equação algébrica estrita de score conforme `NT-SPAMSHIELD-2026-001`.
- Coalescência de 15s entre `CallScreeningService` e `CallLog`, faixas contínuas de duração (`1–5s`, `6–11s`, `>= 12s`), regra auditável `USER_CONFIRMED_SPAM_OVERRIDE (+100)` e migração local de saneamento (`MIGRATION_2_3` / `MIGRATION_3_4`).

### Novidades da versão `v1.2.9` (Build `13`):
- **Atualização OTA Contínua e Sem Fricção:** Endpoints `/api/v1/health`, `/api/v1/apk/info`, `/apk` e `/spamshield.apk` públicos para permitir que clientes instalados descubram e baixem automaticamente novas versões via Tailscale.
- **Segurança da API Reforçada:** Proteção com token Bearer (`SPAMSHIELD_API_KEY`) nas rotas de sincronização de telemetria, regras, quarentena e analíticos.
- **Endurecimento de Rede:** Regras de tráfego cleartext no Android limitadas à rede CGNAT do Tailscale (`100.64.0.0/10`), MagicDNS (`*.ts.net`) e loopback local.
- **Proteção de Linhas Fixas:** Ajuste no motor de scoring para não aplicar regras de tronco VoIP a linhas fixas geográficas convencionais (`32xx`–`39xx`).
- **Governança de Pesos Globais:** Travamento de recalibração global de pesos no servidor (`recalibrator.rs`), exigindo taxa mínima de falsos positivos (`> 5%`) e aprovação administrativa (`rule_weight_approvals`).
- **Verificação de Integridade:** Validação de integridade do pacote APK via SHA-256 tanto no servidor quanto no app antes da instalação.

---

## 1. Arquitetura do Sistema

### 1.1. Android Edge App (`SpamShieldApp`)
100% Kotlin + Jetpack Compose + Material 3 + Room SQLite (WAL, schema `v4`) + Hilt + WorkManager:

- **Interceptação Nativa (`< 2 ms`):** `SmartCallScreeningService` (`ROLE_CALL_SCREENING`) com bloqueio silencioso antes do toque (`setDisallowCall`, `setRejectCall`, `setSkipNotification`).
- **Quarentena Granular de 8 Dígitos (`/8` — bloco de 1.000 números):**
  - Adota **8 dígitos** (`/8`, ex.: `31982042`, correspondendo a `DDD + 6` primeiros dígitos do assinante) para linhas `MOBILE` e `FIXED_OR_VOIP`, restringindo a quarentena ao milhar específico utilizado pela chipeira/robocall (`3198204-2000` a `3198204-2999`).
  - Mantém retrocompatibilidade com prefixos legados de 7 dígitos (`/7`) através do `QuarantinePrefixIndex`, separando acertos exatos (`PREFIX_QUARANTINE_HIT`, `+70`) de acertos amplos (`PREFIX7_BROAD_QUARANTINE_HIT`, `+60`).
- **Transparência Visual no Histórico e Diário de Decisões:**
  - **Bloqueio em Tempo Real na Borda (`BLOQUEADA`, Badge Vermelho):** Chamadas com score de chegada `>= 70`, interceptadas e rejeitadas pelo `CallScreeningService` antes de tocar no aparelho (`SCREENING_DECISION` + `TELECOM_RESPONSE`).
  - **Aprendizado Ativo Pós-Chamada (`APRENDIDA` / `FLAGGED_POST_CALL_QUARANTINED`, Badge Laranja):** Chamadas que tocaram na chegada (`ALLOWED_LOW_SCORE`, score inicial `< 70`) e foram rejeitadas no discador (`DIALER_USER_REJECTED_RINGING`, `+65`), bloqueadas pelo CallLog nativo (`SYSTEM_CALLLOG_BLOCKED`, `+65`) ou desligadas rapidamente após atendimento (`PING_CALL_PENALTY`, `+55`), colocando o prefixo `/8` em quarentena para chamadas futuras.
  - **Permitida em Tempo Real (`PERMITIDA`, Badge Verde):** Chamadas legítimas ou desconhecidas com score `< 70`.
- **Blindagem do Feedback Loop do Telecom (`evaluatePostCallLogVerdict`):**
  - Quando o SpamShield rejeita uma ligação na borda, o Android Telecom grava o evento no `CallLog` como `BLOCKED_TYPE` (6). O motor verifica a decisão de chegada (`outcome == "BLOCKED" || outcome == "SHADOW_BLOCKED"`) antes de avaliar vereditos pós-chamada, impedindo que o bloqueio preventivo do próprio SpamShield seja reinterpretado como aprendizado pós-chamada.
  - Diferencia explicitamente bloqueio nativo do sistema (`Calls.BLOCKED_TYPE` $\rightarrow$ `SYSTEM_CALLLOG_BLOCKED`) de recusa manual pelo usuário enquanto tocava (`Calls.REJECTED_TYPE` $\rightarrow$ `DIALER_USER_REJECTED_RINGING`).
- **Normalização Telefônica Brasileira (`PhoneNormalizer`):**
  - Tratamento determinístico de formato `E.164` (`+55`), prefixos de tronco com zero (`031...`, `011...`), Códigos de Seleção de Prestadora (`012`, `014`, `015`, `021`, `031`, `041`, `043`, `099`, `00xx`) e cabeçalhos de país malformados (`055...`, `0055...`).
  - **Detecção de Códigos Curtos e Utilidade Pública (`TOLL_FREE_OR_SPECIAL`):** Números como `10315`, `105x`, `106xx`, `1xx`, `0800`, `0300`, `0303`, `400x` ou curtos (`< 8` dígitos) preservam sua numeração sem injeção indevida de `+55` ou DDD local e recebem `prefix7 = ""`.
  - **Validação de Cardinalidade Móvel (`MALFORMED_OR_SPOOFED`):** Assinantes iniciados por `9` com comprimento diferente de 9 dígitos (ex.: 8 dígitos) ou iniciados por `[6..8]` com 8 dígitos são classificados como `MALFORMED_OR_SPOOFED`.
  - **Separação Estrita entre `VoIP` e `Fixo`:**
    - **`VoIP`:** Numeração não-geográfica (`0300`, `0303`, `0800`, `4004`, `3003`, `4003`) e faixas dedicadas a STFC Digital/PABX/SIP/DDR (`2108`, `2517`, `30xx`, `31xx`, `40xx`, `41xx`).
    - **`Fixo`:** Linhas fixas STFC geográficas tradicionais (`32xx`–`39xx`, ex.: `31 3768-xxxx`, `31 3287-xxxx`).
  - **Inferência de Operadora Móvel (RNPM Anatel):** Lookup realizado exclusivamente sobre `ddd + subscriberNumber` (sem contaminação pelo CSP), mapeando `Claro` (incluindo faixas `937`–`939` e `980`), `TIM`, `Vivo` e `Outros`.
- **Motor Heurístico Vetorial (`SpamScoringEngine` + `HeuristicScorer` + `HistoryCallScorer`):**
  - **Equação de Consistência Algébrica:** $\text{finalScore} = \max\left(0, \sum w_i\right)$ sobre todas as regras presentes em `triggeredRulesJson`.
  - **Short-Circuits Seguros (Permissão Imediata):**
    - `CONTACT_WHITELIST` (`-1000`): Imunidade para contatos salvos na agenda (`ALLOWED_CONTACT`).
    - `OUTBOUND_GRACE_PERIOD` (`-500`): Imunidade de 2h para retorno do mesmo número ou sub-bloco `/6` após chamada efetuada pelo usuário (`ALLOWED_OUTBOUND_GRACE`).
    - `EMERGENCY_REPEATED_CALL` (`-500`): Concedida exclusivamente a linhas `MOBILE` sem falha `STIR_SHAKEN`, sem cabeçalho CSP/tronco anômalo e cujo prefixo não esteja em quarentena (`ALLOWED_EMERGENCY_REPEAT`).
  - **Regras Cumulativas de Pontuação (Threshold padrão `>= 70`):**
    - `NOT_IN_CONTACTS` (`+25`): Número ausente da agenda de contatos.
    - `STIR_SHAKEN_FAILED` (`+85`): Falha explícita de autenticação STIR/SHAKEN (`SPOOFING_STIR_FAILED`).
    - `STIR_SHAKEN_UNVERIFIED` (`+20` padrão / `+15` no perfil sincronizado e histórico): Chamada sem assinatura STIR/SHAKEN (`NOT_VERIFIED`).
    - `MALFORMED_NUMBER_SPOOFING` (`+85`): Número com cardinalidade móvel inválida ou spoofing estrutural (`lineType == "MALFORMED_OR_SPOOFED"`).
    - `PREFIX_QUARANTINE_HIT` (`+70`): Quarentena ativa no prefixo exato de 8 dígitos (`/8`, `EXACT8`).
    - `PREFIX7_BROAD_QUARANTINE_HIT` (`+60`): Fallback amplo quando há quarentena ativa em prefixo legado de 7 dígitos (`/7`, `BROAD7`).
    - `PREFIX6_NEIGHBOR_WARNING` (`+15`): Alerta de vizinhança no tronco `/6` quando já existem $\ge 2$ sub-blocos `/8` em quarentena ativa sob o mesmo `/6` (substituiu a antiga regra `PREFIX6_CLUSTER_HIT` para evitar falsos positivos em cascata).
    - `PREFIX5_CHIPEIRA_FARM` (`+35`): Detecção de fazenda de chipeira GSM/VoIP quando o tronco `/5` acumula $\ge 3$ prefixos `/8` em quarentena ativa.
    - `PREFIX_NEIGHBORHOOD_BURST` (`+60`): $\ge 2$ números distintos do mesmo prefixo `/8` nas últimas 24h (calculado estritamente na janela temporal anterior à chamada).
    - **Grupo Mutex de Troncos VoIP (`VOIP_TRUNK_GROUP` — no máximo 1 regra por chamada):**
      - `BLACKLIST_MG_VOIP_PABX` (`+80`): Faixas críticas de PABX/VoIP em MG (`31 2108`, `2517`, `3191`, `4020`, `4062`, `4101`).
      - `REGEX_MG_VOIP_TRUNKS` (`+80`): Troncos VoIP massivos (`31 4101-XXXX`).
      - `ANATEL_PABX_VOIP_RANGE` (`+35`): Aplicada apenas quando `carrierGuess == "VoIP"` ou o assinante pertence a blocos dedicados VoIP (`2108`, `2517`, `30xx`, `31xx`, `40xx`, `41xx`), isentando linhas fixas convencionais (`32xx`–`39xx`) e códigos de utilidade (`TOLL_FREE_OR_SPECIAL`).
    - `REGEX_TELEMARKETING_NATIONAL` (`+90`): Prefixos nacionais de telemarketing (`0300`, `0303`, `4004`).
    - `EXTERNAL_DDD_UNKNOWN` (`+30`): Chamada originada fora do DDD local do usuário (sem agenda e fora de faixas 0800/0300/4004).
    - `LEADING_ZERO_TRUNK_DIALING` (`+25`): Aplicada apenas quando há CSP explícito (`012`..`099`, `00xx`), cabeçalho `055`/`0055`, `stirShakenStatus == "FAILED"`, `quarantineMatch == EXACT8` ou `carrierGuess == "VoIP"`. Não penaliza chamadas locais legítimas (`Fixo` ou `MOBILE`) entregues com `0 + DDD`.
    - **Faixas Contínuas de Duração (`callDurationSec > 0`):**
      - `PING_CALL_PENALTY` (`+55`): Chamada atendida muda/curta de `1s a 5s` (gera `outcome = FLAGGED_POST_CALL_QUARANTINED` se score $\ge 70$).
      - `SHORT_ROBOCALL_PENALTY` (`+30`): Desligamento rápido de robocall entre `6s e 11s`.
      - `CONVERSATION_ANSWERED_BONUS` (`-40`): Conversa legítima atendida com duração $\ge 12\text{s}$.
    - **Reconciliação Pós-Chamada e Feedback Auditável:**
      - `SYSTEM_CALLLOG_BLOCKED` (`+65`): Chamada bloqueada pelo CallLog do sistema (`Calls.BLOCKED_TYPE`) quando não havia sido bloqueada na chegada.
      - `DIALER_USER_REJECTED_RINGING` (`+65`): Chamada recusada manualmente pelo usuário enquanto tocava (`Calls.REJECTED_TYPE`).
      - `USER_CONFIRMED_SPAM_OVERRIDE` (`+100`): Regra auditável adicionada quando o usuário marca `CONFIRMED_SPAM` (`category = USER_REPORTED_SPAM`, atualizando chamadas antes permitidas para `outcome = USER_BLOCKED_RETROACTIVE` e enfileirando sync imediato via `TelemetrySyncScheduler`).

### 1.2. Backend Rust (`spamshield-server`)
`axum` + `tokio` + `mongodb` + `rayon`:

- **Ingestão Idempotente e Diário de Decisões (`POST /api/v1/telemetry/batch`):** Insere novos registros, atualiza registros existentes (`call_details`, `decision_vector`, `audit`) e mescla `decision_events` com `$addToSet`, retornando `accepted_ids` e `accepted_event_ids`.
- **Migração Retroativa Idempotente (`AppDatabase::migrate_historical_records`):** Controlada pelo marcador `_migrations.historical_regex_v1`, normaliza registros legados no MongoDB Atlas sem reexecutar em cada boot.
- **Feedback e Governança de Recalibração (`PATCH /api/v1/telemetry/:id/feedback`):**
  - `CONFIRMED_SPAM`: Registra evento `USER_FEEDBACK`, atualiza o documento e reforça o prefixo em `prefix_quarantine` com validade de 30 dias e `penalty_weight = 80`.
  - `FALSE_POSITIVE`: Promove `outcome = ALLOWED_CONTACT`, `category = LEGITIMATE`, `final_score = 0`, remove imediatamente o prefixo de `prefix_quarantine` e só reduz globalmente o peso de uma regra em `-5` (piso `15`) quando a taxa amostral de falsos positivos supera `5%` **e** há aprovação explícita na coleção `rule_weight_approvals`.
- **Sincronização Anatel e Blacklists BR (`POST /api/v1/providers/sync`):** Parser CSV paralelo em Rayon para planos de numeração Anatel, regras `0303` e clusters conhecidos de robocall.

### 1.3. MongoDB Atlas (`spam_shield_db`)
- Coleções principais com validação JSON Schema e índices compostos/TTL:
  - `call_telemetry` (inclui o vetor `decision_events` por chamada)
  - `prefix_quarantine` (índice único em `prefix_7` e índice TTL em `expires_at`)
  - `active_rules`
  - `anatel_numbering_plan`
  - `_migrations` e `rule_weight_approvals` (controle de migração e governança de recalibração)

---

## 2. Endpoints da API (`spamshield-server` — Porta `8080`)

| Método | Rota | Autenticação | Descrição |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/health` | Pública | Status do servidor, conectividade MongoDB Atlas e IP Tailscale |
| `GET` | `/api/v1/apk/info` | Pública | Metadados (`version_code`, `version_name`, `size_bytes`) e SHA-256 do APK atual |
| `GET` | `/apk` (ou `/spamshield.apk`) | Pública | Download direto do pacote APK para atualização OTA |
| `POST` | `/api/v1/telemetry/batch` | Bearer Token | Recebe lote de telemetria, `decision_events` e atualiza quarentenas |
| `PATCH` | `/api/v1/telemetry/:id/feedback` | Bearer Token | Registra `CONFIRMED_SPAM` ou `FALSE_POSITIVE` com recalibração |
| `GET` | `/api/v1/sync/payload` | Bearer Token | Retorna regras ativas, quarentenas `/8`/`/7` e prefixos Anatel |
| `POST` | `/api/v1/rules` | Bearer Token | Cria ou atualiza regra customizada (Regex/Heurística) |
| `POST` | `/api/v1/quarantine/batch` | Bearer Token | Ingestão em lote de quarentenas derivadas na borda |
| `POST` | `/api/v1/quarantine` | Bearer Token | Adiciona ou atualiza prefixo manualmente na quarentena |
| `DELETE` | `/api/v1/quarantine/:prefix7` | Bearer Token | Remove prefixo da quarentena |
| `POST` | `/api/v1/providers/sync` | Bearer Token | Sincroniza base Anatel e Blacklists comunitárias BR |
| `GET` | `/api/v1/analytics/summary` | Bearer Token | Métricas e sumário de bloqueios do cluster |

---

## 3. Como Compilar e Executar

### 3.1. Servidor Rust (`spamshield-server`)
Para iniciar o ambiente local no Windows (Proxy Tailscale + Servidor Rust):
```powershell
.\iniciar-servidor.ps1
```

Ou manualmente:
```powershell
# 1. Garante o proxy do Tailscale ativo no Windows (se rodando localmente)
& "C:\Program Files\Tailscale\tailscale.exe" serve --bg --tcp 8080 tcp://127.0.0.1:8080

# 2. Executa os testes unitários e inicia o servidor Rust
cd C:\Users\danie\spamshield\spamshield-server
cargo test
cargo run
```

> **Nota sobre Produção VPS:** Em produção na VPS Linux (`100.101.23.111:8080`), o serviço roda sob `systemd` (`spamshield.service`). Consulte o guia completo em [nota_tecnica_deploy_vps.md](nota_tecnica_deploy_vps.md).

### 3.2. Aplicativo Android (`SpamShieldApp`)
```powershell
powershell -ExecutionPolicy Bypass -File C:\Users\danie\spamshield\SpamShieldApp\build-android.ps1
```
O script executa os testes unitários (`testDebugUnitTest`), compila o APK (`assembleDebug`) e publica os artefatos em `SpamShieldApp\dist\SpamShieldBR-debug.apk` e `SpamShieldApp\dist\apk-info.json`.

---

## 4. Guia Operacional Tailscale

### 4.1. Ambientes Tailscale (VPS Linux vs. Desenvolvimento Local Windows)
- **VPS de Produção (Recomendado 24/7):** IP Tailscale `100.101.23.111:8080` (`vps55091145`, Ubuntu 24.04 LTS), gerenciado via `systemd` (`spamshield.service`), conforme documentado em [nota_tecnica_deploy_vps.md](nota_tecnica_deploy_vps.md).
- **Host Local Windows (`100.83.253.15:8080`):** Caso execute o servidor localmente no Windows junto a outras VPNs (ex.: NordVPN / `NordLynx`), mantenha ativo o proxy userspace do Tailscale:
  ```powershell
  & "C:\Program Files\Tailscale\tailscale.exe" serve --bg --tcp 8080 tcp://127.0.0.1:8080
  ```

### 4.2. Autenticação Bearer Obrigatória
Os endpoints privados da API (`/api/v1/telemetry/batch`, `/api/v1/sync/payload`, `/api/v1/quarantine`, etc.) exigem a chave configurada em `SPAMSHIELD_API_KEY`:
1. No aplicativo móvel, abra a aba **Tailscale**.
2. No card **Endpoint Tailscale (Servidor Rust Axum)**, verifique o endereço do servidor e preencha o campo **Chave Bearer da API**.
3. Toque em **Salvar chave**.

### 4.3. Indicadores Visuais de Conexão no Dashboard
- **Bolinha Verde (`🟢 Tailscale 100.x.y.z`):** A VPN do Tailscale está conectada no celular (`Transport.VPN`) e com rota ativa para o servidor configurado.
- **Bolinha Vermelha (`🔴 Tailscale 100.67.x.y`):** A VPN do Tailscale no celular está desconectada (o IP `100.67...` pertence ao CGNAT da operadora móvel e não alcança a Tailnet privada). Basta abrir o app Tailscale e reconectar.
