# SpamShield BR (`v1.2.9` — Build `13`)

Ecossistema privado de defesa em borda (`< 2 ms`), telemetria vetorial e inteligência contra chamadas de spam, robocall, chipeiras GSM e spoofing no Brasil (padrões Anatel RNPM / Ato nº 10.413 / STIR-SHAKEN).

### Novidades da versão v1.2.9 (Build 13):
- **Atualização OTA Contínua e Sem Fricção:** Endpoints `/api/v1/health`, `/api/v1/apk/info` e `/apk` públicos para permitir que clientes instalados (v1.2.8 ou anteriores) descubram e baixem automaticamente a nova versão via Tailscale, sem exigir chave prévia.
- **Segurança da API Reforçada:** Proteção com token Bearer (`SPAMSHIELD_API_KEY`) nas rotas de sincronização de telemetria, regras, quarentena e analíticos. Campo de configuração de token Bearer disponível em Ajustes no app.
- **Endurecimento de Rede:** Regras de tráfego cleartext no Android limitadas à rede CGNAT do Tailscale (`100.64.0.0/10`), MagicDNS (`*.ts.net`) e loopback local.
- **Proteção de Linhas Fixas:** Ajuste no motor de scoring para não aplicar quarentena de robocall a linhas fixas convencionais por duas chamadas perdidas.
- **Governança de Pesos Globais:** Travamento de recalibração global de pesos no servidor, exigindo taxa mínima de falso-positivos (> 5%) e aprovação.
- **Verificação de Integridade:** Validação de integridade do pacote APK via SHA-256 tanto no servidor quanto no app antes da instalação.

---

## 1. Arquitetura do Sistema

1. **Android Edge App (`SpamShieldApp`)** — 100% Kotlin + Jetpack Compose + Material 3 + Room SQLite (WAL) + Hilt + WorkManager:
   - **Interceptação Nativa (`< 2 ms`):** `CallScreeningService` (`ROLE_CALL_SCREENING`) com bloqueio silencioso antes do toque (`setDisallowCall`, `setRejectCall`, `setSkipNotification`).
   - **Quarentena Granular com 1 Dígito a Mais (`/8` — 1.000 números):**
     - Em vez de quarentenar blocos amplos de 10.000 números (`/7`, ex.: `3198204`), o motor agora adota **8 dígitos** (`/8`, ex.: `31982042`), restringindo a quarentena ao milhar específico utilizado pela chipeira/robocall (`3198204-2000` a `3198204-2999`).
     - Reduz a restritividade em 10 vezes, blindando contra falso-positivos em vizinhos da mesma central telefônica, mantendo total retrocompatibilidade com quarentenas e troncos legados (`/7`).
    - **Transparência Visual no Histórico e Aprendizado Ativo:**
      - Distinção transparente e estrita entre:
        - **Bloqueio em Tempo Real na Borda (`BLOQUEADA`, Badge Vermelho):** Chamadas com score inicial `>= 70` (ex: `170`), interceptadas e rejeitadas pelo `CallScreeningService` em `< 2 ms` antes de tocar no aparelho. Exibe banner explicativo: *"🛡️ Bloqueada na borda em X.XX ms (Não tocou)"*.
        - **Aprendizado Ativo Pós-Rejeição (`APRENDIDA`, Badge Laranja):** Chamadas que tocaram por ser a 1ª vez do prefixo (score inicial `< 70`) e que o usuário/discador rejeitou (`REJECTED_TYPE`), elevando o score com `SYSTEM_CALLLOG_BLOCKED (+45)` e quarentenando o bloco `/8`. Exibe banner explícito: *"🔔 Tocou na chegada (Score inicial X) ➔ Quarentenada pós-rejeição"*.
        - **Permitida em Tempo Real (`PERMITIDA`, Badge Verde):** Chamadas legítimas ou desconhecidas com score `< 70`.
    - **Blindagem do Feedback Loop do Telecom (`evaluatePostCallLogVerdict`):**
      - Quando o SpamShield rejeita uma ligação na borda, o Android Telecom grava o evento no `CallLog` como `BLOCKED_TYPE` (6). O motor agora verifica `outcome == "BLOCKED" || outcome == "SHADOW_BLOCKED"` antes de avaliar vereditos do sistema, impedindo que o bloqueio nativo do SpamShield seja interpretado erroneamente como rejeição manual do usuário.
      - A regra `SYSTEM_CALLLOG_BLOCKED` só é atribuída se o score de chegada era inferior ao limite de bloqueio (`ALLOWED_LOW_SCORE`), mantendo o histórico de auditoria 100% fidedigno à realidade.
      - O reprocessamento retroativo (`reprocessAllStoredTelemetry`) expurga automaticamente regras residuais de chamadas que já possuíam score suficiente para bloqueio de borda.
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

| Método | Rota | Autenticação | Descrição |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/v1/health` | Pública | Status do servidor e latência do MongoDB Atlas |
| `GET` | `/api/v1/apk/info` | Pública | Metadados e SHA-256 da versão atual do APK para atualização OTA |
| `GET` | `/apk` | Pública | Download direto do pacote `SpamShieldBR-debug.apk` |
| `POST` | `/api/v1/telemetry/batch` | Bearer Token | Recebe lote de telemetria e atualiza registros reprocessados |
| `PATCH` | `/api/v1/telemetry/:id/feedback` | Bearer Token | Registra `CONFIRMED_SPAM` ou `FALSE_POSITIVE` com recalibração |
| `GET` | `/api/v1/sync/payload` | Bearer Token | Retorna regras ativas, quarentenas `/8`/`/7` e prefixos Anatel |
| `POST` | `/api/v1/rules` | Bearer Token | Cria ou atualiza regra customizada (Regex/Heurística) |
| `POST` | `/api/v1/quarantine/batch` | Bearer Token | Ingestão em lote de quarentenas derivadas na borda |
| `POST` | `/api/v1/quarantine` | Bearer Token | Adiciona prefixo manualmente na quarentena |
| `DELETE` | `/api/v1/quarantine/:prefix7` | Bearer Token | Remove prefixo da quarentena |
| `POST` | `/api/v1/providers/sync` | Bearer Token | Sincroniza base Anatel e Blacklists comunitárias BR |
| `GET` | `/api/v1/analytics/summary` | Bearer Token | Métricas e sumário de bloqueios do cluster |

---

## 3. Como Compilar e Executar

### 3.1. Servidor Rust (`spamshield-server`)
Você pode iniciar todo o ambiente (Proxy Tailscale + Servidor Rust) com um único comando na raiz do projeto:
```powershell
.\iniciar-servidor.ps1
```

Ou manualmente passo a passo:
```powershell
# 1. Garante o proxy do Tailscale ativo no Windows
& "C:\Program Files\Tailscale\tailscale.exe" serve --bg --tcp 8080 tcp://127.0.0.1:8080

# 2. Inicia o servidor Rust
cd C:\Users\danie\spamshield\spamshield-server
cargo test
cargo run
```

> **Atenção:** Se houver alterações em rotas públicas ou modelos, certifique-se de recompilar o binário com `cargo build` antes de executar o executável direto.

### 3.2. Aplicativo Android (`SpamShieldApp`)
```powershell
powershell -ExecutionPolicy Bypass -File C:\Users\danie\spamshield\SpamShieldApp\build-android.ps1
```
O script executa os testes unitários (`testDebugUnitTest`), compila o APK (`assembleDebug`) e publica os artefatos em `SpamShieldApp\dist\SpamShieldBR-debug.apk` e `SpamShieldApp\dist\apk-info.json` para distribuição imediata via OTA no Tailscale (`http://100.83.253.15:8080/apk`).

---

## 4. Guia Operacional Tailscale e Lições Aprendidas

### 4.1. Proxy Userspace Obrigatório (`tailscale serve`) no Windows
No Windows, se houver outras VPNs ativas (como NordVPN / adaptador `NordLynx`) ou políticas WFP restritivas, pacotes TCP direcionados diretamente para o IP da interface virtual Tailscale (`100.83.253.15:8080`) são descartados pelo sistema operacional, gerando timeout de 10 segundos no celular (`failed to connect ... after 10000ms`).

**Procedimento de ativação do proxy da porta 8080:**
```powershell
& "C:\Program Files\Tailscale\tailscale.exe" serve --bg --tcp 8080 tcp://127.0.0.1:8080
```
- Esse comando configura o `tailscaled` para escutar na interface Tailscale (`100.83.253.15:8080` e `gbp360.tail1093e7.ts.net:8080`) e repassar o tráfego de forma confiável para o servidor Rust em `127.0.0.1:8080`.
- **Nunca desative este proxy** (`tailscale serve --tcp 8080 off`) sob risco de quebrar o canal de sincronização do aplicativo móvel.

### 4.2. Autenticação Bearer Obrigatória
A partir da versão v1.2.9 (Build 13), os endpoints privados da API (`/api/v1/telemetry/batch`, `/api/v1/sync/payload`, `/api/v1/quarantine`, etc.) exigem a chave de segurança configurada em `SPAMSHIELD_API_KEY`:
1. No aplicativo móvel, vá até a aba **Tailscale**.
2. No card **Endpoint Tailscale (Servidor Rust Axum)**, preencha o campo **Chave Bearer da API** com o token correspondente.
3. Toque em **Salvar chave**. Sem isso, qualquer sincronização é recusada pelo servidor com `HTTP 401 Unauthorized`.

### 4.3. Indicadores Visuais de Conexão no Dashboard
- **Bolinha Verde (`🟢 Tailscale 100.77.79.105`):** A VPN do Tailscale está conectada no celular (`Transport.VPN`) e com rota ativa para o nó do servidor (`100.83.253.15`).
- **Bolinha Vermelha (`🔴 Tailscale 100.67.x.y`):** A VPN do Tailscale no celular está desconectada (por exemplo, após troca de rede, modo de economia de bateria ou inicialização do sistema). O IP `100.67...` exibido é o CGNAT atribuído pela operadora de telefonia celular (Claro/Vivo/TIM), e não alcança a rede privada Tailscale. Basta abrir o aplicativo Tailscale no celular e religar a chave.
