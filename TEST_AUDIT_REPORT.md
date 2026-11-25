# Relatório de Auditoria de Qualidade e Arquitetura de Testes — SpamShield BR

**Data da Auditoria:** 25/11/2026  
**Versão Auditada:** `v1.4.1` (Build `21`, baseline `7446640`)  
**Status Final:** Concluído — Remediação, Cobertura `P0`/`P1`, Correções de Produção e Gates Validados (`100% exit 0`)

---

## 1. Fase 0 — Descoberta da Stack, Camadas, Gates e Baseline

### 1.1. Arquitetura e Camadas do Monorepo
| Camada / Módulo | Stack Tecnológica | Fronteiras de Integração Auditadas |
| :--- | :--- | :--- |
| **Android Edge App (`SpamShieldApp`)** | Kotlin `2.1.20`, AGP `8.12.0`, Compile SDK `36` (min `29` / target `35`), Jetpack Compose Material 3, Room `2.7.0` (SQLite WAL, schema `v6`), DataStore Preferences `1.1.2`, Hilt `2.56.1`, WorkManager `2.10.0`, Retrofit `2.11` + OkHttp `4.12` + `kotlinx.serialization`, `libphonenumber` | Android Telecom (`CallScreeningService` com orçamento monotônico de `3500 ms`), `ContactsContract`, `CallLog.Calls`, `TelephonyManager` (`BroadcastReceiver`), Room SQLite, DataStore, `FileProvider` (exportação JSON/CSV e instalador OTA) e HTTP/REST via Tailscale (`100.64.0.0/10` / `*.ts.net`) |
| **Backend (`spamshield-server`)** | Rust `2021` (`rustc 1.93.1`), `axum 0.7`, `tokio 1.40`, `mongodb 3.1`, `bson 2.11`, `rayon 1.10`, `reqwest 0.12` (`rustls-tls`), `tower-http 0.5`, `sha2` | API HTTP/REST na porta `8080` (rotas públicas OTA/health e rotas protegidas por Bearer `SPAMSHIELD_API_KEY`, limite de `16 MiB` nos lotes de diário), MongoDB Atlas (`spam_shield_db`) e artefatos OTA (`APK_STORAGE_PATH` / `APK_METADATA_PATH`) |
| **Scripts, Banco e Build** | PowerShell (`SpamShieldApp/build-android.ps1`, `iniciar-servidor.ps1`), MongoDB JS (`init_mongodb.mongodb.js`), Cross-compile Linux estático (`cargo-zigbuild` + Zig `0.13.0` $\rightarrow$ `x86_64-unknown-linux-musl`) | Empacotamento e checksum SHA-256 de candidatos OTA em `SpamShieldApp/dist/`, inicialização de schema/índices MongoDB e binário estático para VPS Linux |

### 1.2. Contexto Recente Prioritário (`git log`)
- **`v1.4.1` (`9549b43` / `7446640`):** Remoção do banner de campanha da capa do Dashboard, destaque visual vermelho de proteção pausada (janela esperada ou modo sombra) e consolidação cronológica unificada de chamadas + campanhas expansíveis em `CallAuditScreen`.
- **`v1.4.0` (`30188bc`):** Introdução das 3 ferramentas de triagem/auditoria (`ExpectedWindow`, `Campaigns` de números rotativos em 5 min, `RuleLaboratory` replay/sombra), migração Room `5 -> 6` (`feature_events`), `FeatureExport` e endpoint `POST /api/v1/telemetry/features/batch`.
- **`v1.3.5` (`fce50a3`) & Hotfixes `1.3.4` (`9fde254`, `ad9a837`, `2ec4f50`):** Prazo monotônico de `3,5s` na triagem, quarentenas automáticas com validade atrelada à evidência, confirmação atômica de eventos, isolamento de lotes HTTP `409`/`413` (limite `16 MiB`) e prontidão explícita do DataStore na abertura.

---

## 2. Fase 1 — Defeitos Auditados e Remediados na Suíte Existente

1. **Mock Drift (`FakeSpamShieldDao` e Fixtures HTTP):**
   - `FakeSpamShieldDao.deduplicateHistoryRecords()` (`SpamScoringEngineTest.kt`) era um **no-op vazio (`{}`)**, divergindo da query SQL real em `SpamShieldDao.kt` (que remove registros `s23-ultra-history` `PENDING`, `UNRATED` e sem `decisionEvents` quando há chamada real em $\le 15\text{s}$). **Remediado:** implementada a lógica fiel à SQL real.
   - `FakeSpamShieldDao.updateCallDurationAndCategory()` omitia `syncStatus = "PENDING"`, divergindo do SQL real e mascarando regressões de reenvio pós-chamada. **Remediado.**
   - `FakeSpamShieldDao.findRealTimeTelemetryNearTimestamp()` omitia o desempate determinístico `.thenBy { it.timestamp }`. **Remediado.**
   - `FakeSpamShieldDao.observeAllTelemetry()`, `observeDecisionEvents()`, `observeAllRules()` e `observeActiveQuarantines()` não reemitiam em mutações subsequentes. **Remediado** com versionamento reativo (`stateVersion`).
   - `TelemetryBatchUploadTest.kt` instanciava `DecisionVectorRemoteDto` com `category = "UNKNOWN"`, valor inexistente no enum `CallCategory` do servidor Rust (`models.rs`, onde o canônico é `"UNKNOWN_UNVERIFIED"`). **Remediado.**
2. **Teste Obsoleto / Código Morto Validando o Próprio Mock:**
   - `SpamScoringEngineTest.serializes CallTelemetryEntity to JSON for export and upgrades outcome on CONFIRMED_SPAM` exercitava `SpamShieldDao.updateUserFeedback`, método **morto em produção** desde a `v1.3.3` cuja SQL real sequer tratava `CONFIRMED_SPAM` (o teste passava apenas porque o mock continha lógica própria). **Remediado:** método morto removido de `SpamShieldDao` e teste reescrito contra o fluxo real de produção (`SyncRepository.submitFeedback` + `TelemetryExport.encode`).
3. **Falso Positivo / Teste Tautológico:**
   - `SpamScoringEngineTest.blocks MG VoIP trunk 03141011230 with quarantine, regex and PABX range penalties` nunca instanciava `SpamScoringEngine` nem chamava `evaluateIncomingCall`, limitando-se a ler o seed estático de `fakeDao.quarantines`. **Remediado:** reescrito para executar `evaluateIncomingCall("03141011230", "FAILED", now)`, validando `BLOCKED`, exclusão mútua de `VOIP_TRUNK_GROUP`, `PREFIX7_BROAD_QUARANTINE_HIT`, `LEADING_ZERO_TRUNK_DIALING` e equação algébrica de score.
4. **Acoplamento Frágil à Implementação e Caminho Local:**
   - `NtSpamshield2026001Test.stage4ScreeningUsesMemoryQuarantineAndProcessLifetimePatterns` lia código-fonte `.kt` bruto do disco (incluindo path absoluto `C:/Users/danie/spamshield/...`) e recortava substrings entre assinaturas de funções. **Remediado:** substituído por verificação comportamental de identidade de instâncias em `ScreeningPatternCache` (`assertSame`), tolerância a regex malformadas (`patternOrNull`) e zero leituras do banco em `QuarantinePrefixIndex` aquecido.
5. **Redundância:**
   - `PhoneNormalizerTest` duplicava literalmente os casos `004131939995971` e `03110315` já testados em `NtSpamshield2026001Test`. **Remediado:** duplicatas removidas e substituídas por cobertura das faixas `0800`, `0500`, `3003`, `4003`, `105x`, `106xx`, `190`, `0055`, entrada vazia, `defaultDdd != "31"`, `isKnownChipeiraFarmPrefix5` e `hasAbnormalTrunkOrCspHeader`.

---

## 3. Fases 2, 3 e 4 — Bugs Reais de Produção Encontrados e Corrigidos

Todos os **10 bugs reais de produção** abaixo foram descobertos durante a auditoria estática (Fases 1–2) e a execução das novas suítes `P0`/`P1` (Fase 4), corrigidos na implementação e blindados com testes de regressão dedicados:

| ID | Severidade | Arquivo(s) de Produção | Descrição do Defeito Real e Correção Aplicada |
| :--- | :---: | :--- | :--- |
| **BUG-01** | **P0** | `ui/screens/CallAuditScreen.kt` | `OutcomeBadge` comparava `"Bloqueio registrado pelo Android"`, mas `DecisionTrace.evidence` emite `"Bloqueio do SpamShield registrado pelo Android"`, fazendo todo bloqueio confirmado cair no badge cinza `AUDITADA`. Extraído `resolveOutcomeBadgeSpec` cobrindo o rótulo exato (`BLOQUEIO REGISTRADO` vermelho) e `SILENCIADA`. |
| **BUG-02** | **P0** | `domain/ScreeningPatternCache.kt`, `SpamScoringEngine.kt`, `RuleLaboratory.kt`, `service/CallHistoryAnalyzer.kt`, `ui/MainViewModel.kt` | Uma única regra `REGEX_MATCH` inválida (`[`) lançava `PatternSyntaxException` em `prepareIncomingCall` e `RuleLaboratory.compare`, forçando `FALLBACK_AFTER_ERROR` (`ALLOW`) e **desarmando todo o bloqueio de spam**. Adicionado `ScreeningPatternCache.patternOrNull` + validação preventiva na UI. |
| **BUG-03** | **P0** | `sync/TailscaleCleartext.kt`, `res/xml/network_security_config.xml` | `TailscaleCleartext.requireAllowed` fazia `runCatching { URI(url) }.getOrNull() ?: return` (**fail-open**): URLs HTTP públicas com espaço não codificado falhavam em `java.net.URI`, retornavam sem erro e eram aceitas pelo `OkHttp` fora da Tailnet; `.ts.net` vazio era aceito e `network_security_config.xml` omitia o IP Tailscale da VPS (`100.101.23.111`). Migrado para `toHttpUrlOrNull()` **fail-closed**, validação estrita de labels DNS, suporte a `[::1]` e inclusão de `100.101.23.111`. |
| **BUG-04** | **P1** | `domain/SpamScoringEngine.kt`, `sync/SyncRepository.kt` | `extractQuarantinePrefix` fatiava 8 dígitos brutos de números `TOLL_FREE_OR_SPECIAL` (`08007221234` $\rightarrow$ `08007221`, `40041234` $\rightarrow$ `40041234`), violando a invariante `prefix7 = ""` e gerando quarentenas corrompidas no pós-chamada/feedback; `submitFeedback` enfileirava sync duplicado. Corrigido via `PhoneNormalizer.normalize` e remoção da chamada duplicada. |
| **BUG-05** | **P1** | `domain/SpamScoringEngine.kt`, `service/CallHistoryAnalyzer.kt`, `CallStateBroadcastReceiver.kt`, `AndroidContactAndCallLogLookup.kt`, `SmartCallScreeningService.kt` | Reconciliação de `CallLog`, receptor pós-chamada e `OutboundGrace` normalizavam números locais de 8/9 dígitos com o DDD fixo `"31"` em vez de `settings.userDdd`, quebrando casamento de chamadas e imunidade de retorno para usuários fora de MG (ex.: DDD `11`). Propagado `userDdd` em todos os pontos de entrada. |
| **BUG-06** | **P0** | `spamshield-server/src/models.rs` | `flexible_datetime` multiplicava inteiros negativos por `1000` sem `checked_mul` (causando **panic por overflow** em `i64::MIN` ou corrompendo timestamps negativos em ms) e fazia cast `v as i64` em `visit_u64` (`u64::MAX` virava `-1`). Corrigido com faixa simétrica `(-100_000_000_000..100_000_000_000)`, `checked_mul(1000)` e `i64::try_from(v)`. |
| **BUG-07** | **P1** | `spamshield-server/src/routes/rules.rs`, `routes/telemetry.rs`, `db.rs`, `init_mongodb.mongodb.js`, `ui/MainViewModel.kt`, `sync/TailscaleNetworkHelper.kt` | `POST /api/v1/rules` aceitava `rule_id` vazio e `REGEX_MATCH` sem `pattern` (agora retorna `400 BAD_REQUEST`); `PATCH .../feedback` com `UNRATED` retinha overrides manuais anteriores em `triggered_rules` (agora restaura score/outcome/category); `init_mongodb.mongodb.js` alinhado com `OUTGOING_CALL` e `call_direction`; `Locale.US` explícito em `MainViewModel.kt:308` e cast seguro `as? ConnectivityManager` em `TailscaleNetworkHelper`. |
| **BUG-08** | **P1** | `ui/screens/DashboardScreen.kt` | Descoberto na Fase 4: `classifyCarrierLabel` chamava `PhoneNormalizer.isVoipPrefixOrSubscriber(item.prefix7)` passando `prefix7` posicionalmente no parâmetro `subscriber`. Para qualquer linha fixa do DDD 31 (`313xxxxx`), `sub.take(3)` resultava em `"313"` (tratado como faixa VoIP), classificando **todas as linhas fixas de MG como `VoIP`** no gráfico de rosca quando `carrierGuess != "Fixo"`. Corrigido para `isVoipPrefixOrSubscriber("", prefix7 = item.prefix7, rawCarrier = item.carrierGuess)`. |
| **BUG-09** | **P1** | `ui/MainViewModel.kt` | Descoberto na Fase 4: `exportTelemetryFiles` serializava colunas JSON internas do CSV usando `json` configurado com `prettyPrint = true`, injetando quebras de linha `\n` cruas no meio de cada registro de `spamshield_telemetry.csv`. Adicionado `csvJson` compacto de linha única para manter 1 linha física estrita por chamada. |
| **BUG-10** | **P1** | `sync/SyncRepository.kt` | Descoberto na Fase 4: `performFullSyncLocked` executava `dao.deduplicateHistoryRecords()` apenas **depois** de enviar todos os lotes `PENDING` e marcá-los como `SYNCED`. Como a query SQL de deduplicação exige `syncStatus = 'PENDING'`, duplicatas `s23-ultra-history` eram enviadas ao MongoDB Atlas e jamais removidas localmente. Adicionada execução prévia de `deduplicateHistoryRecords()` antes do loop de upload. |

---

## 4. Novos Testes Implementados por Camada (`P0` e `P1`)

### 4.1. Camada Android (`SpamShieldApp/app/src/test`) — +20 novos métodos de teste em 4 novas classes + 6 classes expandidas
- `sync/TailscaleCleartextTest.kt` (**Novo — `P0`**, 4 testes): fronteiras exatas de `100.64.0.0/10` (`100.63.255.255` vs `100.64.0.0`, `100.101.23.111`, `100.127.255.255` vs `100.128.0.0`), MagicDNS válido vs malformado (`.ts.net`, `bad..ts.net`, `evil-ts.net`), loopback IPv4/IPv6 (`[::1]`), HTTPS irrestrito e bloqueio *fail-closed* de HTTP público com espaços (`BUG-03`) e esquemas não-HTTP.
- `sync/SyncRepositoryHttpTest.kt` (**Novo — `P0`**, 4 testes de integração HTTP real via `ServerSocket` + `OkHttp` + `Retrofit` + `DataStore`): ciclo ponta a ponta de `performFullSync` (cabeçalho Bearer, lotes de telemetria e `feature_events`, retenção de `FEEDBACK` em chamada adiada vs `PATCH` em chamada confirmada, rollback local de `QUARANTINE_AUTO_UPSERT` rejeitado pelo servidor com `applied=false`, deduplicação pré-upload `BUG-10`, atualização de `QuarantinePrefixIndex` e `lastSyncTimestamp`), aborto limpo em revogação de consentimento mid-sync, verificação SHA-256 e exclusão de binário adulterado em `downloadApkUpdate`, `triggerProvidersSync` e limpeza conjunta `/8` + `/7` em `FALSE_POSITIVE`.
- `domain/RuleLabAndHistoryGapTest.kt` (**Novo — `P0`/`P1`**, 3 testes): todos os ramos de `RuleLaboratory.compare` (`AMBIGUOUS_ARRIVAL_DECISION`, `PROTECTED_ALLOW`, regras aditivas legadas sem `arrivalContext` em `BLOCK_TO_ALLOW` e `ALLOW_TO_BLOCK`, `RULE_CONFIGURATION_UNAVAILABLE`, `PREFIX_MATCH_UNAVAILABLE`, `BASE_RECONSTRUCTION_MISMATCH` e regex inválida), ciclo de vida de `FeatureRepository` (`CAMPAIGN_SILENCE_EXPIRED`, `LAB_ENDED` por `TIME_LIMIT` e `REPLACED`, `LAB_COMPARISON` retroativo, `LAB_DISCARDED`, deduplicação e guarda de `WAIT_FEEDBACK`) e triagem/reconciliação/reprocessamento multi-DDD fora de MG (`userDdd = "11"`, `BUG-05`).
- `ui/MainViewModelAndSettingsTest.kt` (**Novo — `P1`**, 2 testes): `SettingsDataStore` (clamping `20..150`, higienização de DDD/token, revogação automática de `remoteSyncEnabled` ao trocar URL do servidor preservando equivalência de barra final) e `MainViewModel` (rejeição de regex inválida, sanitização de `ruleId`, normalização de quarentena manual Fixo/Móvel, `simulateTestCall` em bloqueio/modo sombra/liberação, `checkApkUpdate` e exportação tripla JSON + summary + CSV RFC-4180 de 37 colunas em linha única `BUG-09`).
- Expansões em `SpamScoringEngineTest.kt` (`BUG-02`, `BUG-04`), `NtSpamshield2026001Test.kt`, `PhoneNormalizerTest.kt`, `TelemetryBatchUploadTest.kt`, `FeatureUploadTest.kt` e `AuditAndDashboardUiTest.kt` (`BUG-01`, `BUG-08`).

### 4.2. Camada Backend Rust (`spamshield-server/src`) — +6 novos testes unitários e de roteador HTTP Axum
- `models.rs`: `flexible_datetime_handles_rfc3339_seconds_millis_bson_and_rejects_overflow_without_panic` cobrindo RFC3339, strings numéricas, segundos/milissegundos positivos e negativos, Extended JSON BSON (`$date` / `$numberLong`) e ausência de panic em `i64::MIN`, `i64::MAX` e `u64::MAX` (`BUG-06`).
- `routes/features.rs`: validação das 7 famílias de `FeatureEvent` (`WAIT_*`, `CAMPAIGN_*`, `LAB_*`), rejeição de envelopes inválidos, lote vazio `200 OK`, duplicata no mesmo lote `400 BAD_REQUEST`, ausência de Bearer `401 UNAUTHORIZED` e excesso de `16 MiB` `413 PAYLOAD_TOO_LARGE` (`TELEMETRY_BATCH_TOO_LARGE`).
- `routes/mod.rs`: download OTA `/apk` e `/spamshield.apk` (`200 OK` com `Content-Type` e `Content-Disposition`, `404` ausente, `503` binário de 0 bytes, manifesto Gradle com BOM UTF-8), rejeição `400 BAD_REQUEST` em `/api/v1/rules` (`rule_id` vazio ou `REGEX_MATCH` sem `pattern`, `BUG-07`) e `/api/v1/telemetry/batch` com IDs repetidos.
- `routes/telemetry.rs`: transições completas de `apply_feedback_to_decision_vector` (`CONFIRMED_SPAM` $\rightarrow$ `FALSE_POSITIVE` $\rightarrow$ `UNRATED`, `BUG-07`).
- `main.rs`: parsers tolerantes de BSON em `db.rs` (`doc_get_i32`, `doc_get_datetime`, `parse_active_rule`, `parse_prefix_quarantine`, `parse_anatel_doc`), piso `15` e divisão segura de `next_global_rule_weight` e parser Rayon completo das 35 faixas de `BUILTIN_ANATEL_PLAN_CSV` + casos `0800`/`UNKNOWN`/DDD inválido.

---

## 5. Comparativo Final de Gates e Cobertura (Antes vs. Depois)

### 5.1. Status de Todos os Gates de Qualidade e Build
| Gate / Verificação | Antes (Baseline) | Depois (Pós-Remediação) |
| :--- | :--- | :--- |
| **Rust Format (`cargo fmt --check`)** | `exit 0` | **`exit 0`** |
| **Rust Lint (`cargo clippy --all-targets --locked -- -D warnings`)** | `exit 0` (0 warnings) | **`exit 0` (0 warnings)** |
| **Rust Testes (`cargo test --locked`)** | `exit 0` (25 passed, 3 ignored) | **`exit 0` (31 passed, 0 failed, 3 ignored)** |
| **Rust Release Cross-Build (`cargo zigbuild --release --target x86_64-unknown-linux-musl`)** | `exit 0` | **`exit 0`** |
| **Android Testes Unitários (`testDebugUnitTest`)** | `exit 0` (126 passed em 15 suítes) | **`exit 0` (146 passed, 0 failed em 19 suítes)** |
| **Android Cobertura (`createDebugUnitTestCoverageReport`)** | Script avulso (não configurado no módulo) | **`exit 0` (integrado nativamente ao `build.gradle.kts`)** |
| **Android Lint (`lintDebug`)** | `exit 0` (0 errors, 32 warnings — 1 `DefaultLocale`) | **`exit 0` (0 errors, 31 warnings — 0 `DefaultLocale`)** |
| **Android Build (`assembleDebug` / `build-android.ps1 -BuildType debug`)** | `exit 0` | **`exit 0`** |

### 5.2. Evolução da Cobertura Android (JaCoCo)

| Escopo Medido | Métrica | Antes (Baseline) | Depois (Final) | Evolução |
| :--- | :--- | :---: | :---: | :---: |
| **Código de Produção Autoral (`.kt` manuais)** | **Linhas** | `2.841 / 6.585` (43,1%) | **`3.594 / 6.653` (54,0%)** | **+10,9 p.p.** (+753 linhas) |
| **Código de Produção Autoral (`.kt` manuais)** | **Branches** | `1.656 / 3.970` (41,7%) | **`2.107 / 4.056` (51,9%)** | **+10,2 p.p.** (+451 branches) |
| **Código de Produção Autoral (`.kt` manuais)** | **Instruções** | `26.026 / 65.577` (39,7%) | **`32.850 / 66.298` (49,5%)** | **+9,8 p.p.** |
| **Código de Produção Autoral (`.kt` manuais)** | **Métodos** | `692 / 1.556` (44,5%) | **`873 / 1.586` (55,0%)** | **+10,5 p.p.** |
| **Bundle JaCoCo Bruto (com KSP/Hilt/Room gerados)** | **Linhas / Branches** | `31,7%` / `39,7%` | **`39,9%` / `49,4%`** | **+8,2 p.p. / +9,7 p.p.** |

#### Evolução nos Módulos e Arquivos Críticos Auditados (`P0` e `P1`)
| Pacote / Arquivo Android | Linhas (Antes $\rightarrow$ Depois) | Branches (Antes $\rightarrow$ Depois) |
| :--- | :---: | :---: |
| **Pacote `br.com.spamshield.sync`** | `19,5%` $\rightarrow$ **`84,5%`** (`393/465`) | `8,6%` $\rightarrow$ **`69,3%`** (`196/283`) |
| ↳ `TailscaleCleartext.kt` (`GAP-01`) | `0,0%` $\rightarrow$ **`100,0%`** (`17/17`) | `0,0%` $\rightarrow$ **`94,4%`** (`51/54`) |
| ↳ `SyncRepository.kt` (`GAP-02`) | `16,2%` $\rightarrow$ **`92,0%`** (`332/361`) | `5,0%` $\rightarrow$ **`61,3%`** (`111/181`) |
| ↳ `TelemetryBatchUpload.kt` | `91,2%` $\rightarrow$ **`100,0%`** (`34/34`) | `70,0%` $\rightarrow$ **`100,0%`** (`20/20`) |
| **Pacote `br.com.spamshield.domain`** | `90,5%` $\rightarrow$ **`94,6%`** (`2160/2284`) | `65,3%` $\rightarrow$ **`71,2%`** (`1486/2088`) |
| ↳ `RuleLaboratory.kt` (`GAP-07`) | `84,9%` $\rightarrow$ **`99,2%`** (`128/129`) | `38,2%` $\rightarrow$ **`66,0%`** (`103/156`) |
| ↳ `FeatureRepository.kt` (`GAP-07`) | `80,5%` $\rightarrow$ **`95,7%`** (`157/164`) | `47,4%` $\rightarrow$ **`68,2%`** (`105/154`) |
| ↳ `HistoryCallScorer.kt` (`GAP-07`) | `61,9%` $\rightarrow$ **`95,2%`** (`40/42`) | `50,0%` $\rightarrow$ **`71,1%`** (`27/38`) |
| ↳ `PhoneNormalizer.kt` | `81,2%` $\rightarrow$ **`91,0%`** (`223/245`) | `56,5%` $\rightarrow$ **`64,3%`** (`198/308`) |
| ↳ `SpamScoringEngine.kt` (`GAP-03`) | `92,2%` $\rightarrow$ **`92,8%`** (`578/623`) | `68,7%` $\rightarrow$ **`70,6%`** (`329/466`) |
| ↳ `ScreeningPatternCache.kt` (`GAP-03`) | `100,0%` $\rightarrow$ **`100,0%`** (`9/9`) | `—` $\rightarrow$ **`100,0%`** (`4/4`) |
| **Pacote `br.com.spamshield.ui`** | `0,3%` $\rightarrow$ **`55,4%`** (`209/377`) | `0,0%` $\rightarrow$ **`38,7%`** (`55/142`) |
| ↳ `MainViewModel.kt` (`GAP-06`) | `0,0%` $\rightarrow$ **`55,3%`** (`208/376`) | `0,0%` $\rightarrow$ **`38,7%`** (`55/142`) |
| **Pacote `br.com.spamshield.data.local`** | `66,8%` $\rightarrow$ **`70,7%`** (`253/358`) | `46,2%` $\rightarrow$ **`64,1%`** (`50/78`) |
| ↳ `SettingsDataStore.kt` (`GAP-06`) | `74,3%` $\rightarrow$ **`87,6%`** (`92/105`) | `58,1%` $\rightarrow$ **`80,6%`** (`50/62`) |
| **Pacote `br.com.spamshield.data.local.dao`** | `75,8%` $\rightarrow$ **`84,6%`** (`77/91`) | `50,0%` $\rightarrow$ **`56,1%`** (`37/66`) |
| **Pacote `br.com.spamshield.data.remote`** | `45,2%` $\rightarrow$ **`78,1%`** (`121/155`) | `—` |
| **Pacote `br.com.spamshield.ui.screens`** (`GAP-05`) | `1,7%` $\rightarrow$ **`4,7%`** (`74/1583`) | `2,7%` $\rightarrow$ **`11,8%`** (`69/587`) |
