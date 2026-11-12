# Telemetria de decisões — v1.3.1 / v1.3.2 (Build 15–16)

Uma recusa no discador enquanto a chamada toca não é um bloqueio preventivo do SpamShield. O score e o `outcome` reprocessados descrevem a classificação atual; não comprovam a resposta enviada na chegada. A implementação acrescenta um diário de eventos separado para preservar essa distinção.

## Persistência e contrato

A migração Room 3 → 4 cria `call_decision_events` sem reescrever a telemetria existente. Cada evento tem ID, chamada associada, horário de gravação, etapa, origem, versão do app e detalhes estruturados. Inserções repetidas do mesmo evento são ignoradas. Somente o estado de sincronização é atualizado; reprocessamento e feedback não substituem os eventos originais.

| Evento | Evidência capturada |
| --- | --- |
| `SCREENING_DECISION` | Score, limite, regras disparadas, resultado calculado, número bruto/normalizado, prefixo, verificação, modo sombra; nas avaliações completas, regras habilitadas, hash da configuração, acerto/prontidão/tamanho do cache e contagem da janela de vizinhança |
| `SCREENING_RESPONSE_SUBMITTED` | Permitir/bloquear, rejeitar, ocultar notificação/histórico e tempo total até o envio ao Android |
| `SCREENING_ERROR` / `SCREENING_RESPONSE_FAILED` | Falha de avaliação/envio e tipo da exceção; a resposta alternativa fica registrada separadamente |
| `CALL_LOG_OBSERVATION` | Tipo do CallLog, duração, instante da chamada, motivo de bloqueio e componente de screening quando disponíveis |
| `POST_CALL_EVALUATION` | Classificação depois do resultado do sistema, incluindo caminhos sem mudança de score |
| `HISTORY_ASSESSMENT` | Avaliação de registro importado, sem alegar decisão em tempo real |
| `REPROCESS_EVALUATION` | Nova avaliação com regras atuais, sem alterar a decisão original |
| `USER_FEEDBACK` | Confirmação de spam ou falso positivo; o servidor também registra decisões recebidas por sua API |
| `QUARANTINE_UPDATED` | Prefixo, motivo e validade da proteção criada/renovada pela avaliação |

`details.schemaVersion = 1` identifica o formato. `details.engineRevision = decision-journal-v1` identifica essa implementação. Horários são epoch em milissegundos. A versão do app é independente da versão do contrato.

O timestamp original da chamada e o horário em que uma observação foi gravada são distintos. Observações repetidas do mesmo estado têm chave determinística. Uma mudança posterior de tipo, duração ou motivo gera outro evento. As decisões de chamadas com horários diferentes mantêm IDs próprios, mesmo quando ocorrem dentro de 15 segundos. A associação ao CallLog escolhe a ocorrência mais próxima do timestamp informado; se não houver correspondência, cria um registro de histórico sem inventar uma decisão original.

## Idempotência Semântica, Coalescência e Fidelidade Causal (Refinamentos v1.3.1 — 11/11/2026)

A análise da telemetria real exportada do dispositivo (`v1.3.1` / Build `15`) levou às seguintes garantias adicionais no motor e no diário de decisões:

1. **Deduplicação Semântica e Poda de Eventos Redundantes (`DecisionTrace`):**
   - `QUARANTINE_UPDATED`: deduplicado por `(callId, prefix, baseReason)`, ignorando variações de milissegundos no sufixo `; expiresAt=...` geradas por múltiplas passagens do reconciliador ou do reprocessador. Além disso, `applyPostCallLogVerdict` e `reprocessStoredTelemetry` só persistem/emitem `QUARANTINE_UPDATED` quando o prefixo é novo na quarentena ou seu motivo base (`reason`) efetivamente muda.
   - `POST_CALL_EVALUATION`, `REPROCESS_EVALUATION` e `HISTORY_ASSESSMENT`: deduplicados por equivalência de resultado (`sameEvaluationOutcome`: mesmo `score`, `outcome`, `category`, `reason` e vetor `triggeredRules`).
   - `DecisionTrace.pruneRedundantEvents`: durante o reprocessamento, expurga do SQLite eventos `QUARANTINE_UPDATED` e avaliações repetidas já armazenadas em execuções anteriores, preservando apenas a primeira ocorrência de cada transição semântica.
2. **Proteção de Vínculo `CALL_LOG_OBSERVATION` contra Rediscagens na Janela de 15s:**
   - `reconcileRecentCallsWithCallLog` processa as entradas do `CallLog` em ordem cronológica crescente (do mais antigo para o mais recente).
   - Em `SpamScoringEngine.evaluatePostCallLogVerdict`, caso uma chamada já possua uma `CALL_LOG_OBSERVATION` cujo `callLogTimestamp` esteja mais próximo (ou empatado) do `timestamp` da entidade do que uma nova entrada candidata dentro da janela de 15s, a observação de uma rediscagem subsequente é ignorada para aquela entidade, impedindo oscilação (*ping-pong*) entre `DIALER_USER_REJECTED_RINGING` (`REJECTED`) e `SYSTEM_CALLLOG_BLOCKED` (`BLOCKED`).
3. **Fidelidade Causal no Reprocessamento (`reprocessStoredTelemetry` & `HistoryCallScorer`):**
   - Chamadas que possuem evento `SCREENING_DECISION` preservam no reprocessamento o estado de quarentena (`quarantineMatch`: `EXACT8`, `BROAD7` ou `NONE`) e a contagem de vizinhança (`distinctNumbers24h`) observados no instante real de chegada, evitando que uma quarentena ou rajada futura adicione retroativamente `PREFIX_QUARANTINE_HIT` ou `PREFIX_NEIGHBORHOOD_BURST` a chamadas anteriores.
   - Regras contextuais de vizinhança disparadas na chegada (`PREFIX6_NEIGHBOR_WARNING` `+15` e `PREFIX5_CHIPEIRA_FARM` `+35`) são preservadas em `HistoryCallScorer.score` a partir do registro/evento `SCREENING_DECISION`.
4. **Normalização de Cabeçalhos `055` / `0055` e Deduplicação de Códigos Curtos / Legados:**
   - `PhoneNormalizer.parseGeographic` reconhece e descasca prefixos de país discados como tronco nacional (`055` e `0055`) antes de DDDs válidos (ex.: `05531973350693` $\rightarrow$ `+5531973350693`, `ddd = "31"`, `prefix7 = "31973350"`, `cspExtracted = "055"`, `carrierGuess = "Claro"`), migrando quarentenas legadas `055...` para o prefixo canônico de 8 dígitos.
   - `deduplicateStoredTelemetryRows` unifica registros duplicados de mesmo `(normalizedNumber, timestamp)` (incluindo códigos de utilidade como `10315` e `005` com `prefix7 = ""`) e absorve registros órfãos `calllog-...` dentro da janela de 15s, reatribuindo seus `decisionEvents` ao registro canônico antes da remoção.

## O que o painel pode afirmar

- **Bloqueio solicitado:** houve resposta de bloqueio enviada pelo `CallScreeningService` real. Simulações não contam.
- **Bloqueio registrado pelo Android:** além do pedido, a observação mais recente registra `BLOCKED_TYPE` e `BLOCK_REASON_CALL_SCREENING_SERVICE`. Se o componente identificar outro app, o bloqueio não é atribuído ao SpamShield.
- **Rejeitada no discador:** o Android registrou `REJECTED_TYPE`, separado dos bloqueios.
- **Legado:** falta a decisão original. Não se reconstrói essa prova a partir do score atual.

O painel e a API de analíticos deixam de usar `outcome == BLOCKED` como prova de bloqueio preventivo. A API mantém `total_blocked` para bloqueios com evidência e acrescenta `total_block_requests` e `total_dialer_rejected`. A tela de auditoria mostra a sequência de eventos e a classificação atual separadamente. Estimativas de tempo economizado foram substituídas por contagens observáveis.

O retorno de `respondToCall` é um envio, não uma confirmação independente de que o telefone não tocou. O Android pode ignorar uma resposta tardia. O novo tempo até o envio inclui a avaliação e a persistência anteriores à resposta; o antigo `processingLatencyMs` continua sendo uma medida parcial do motor.

Referências: [CallScreeningService](https://developer.android.com/reference/android/telecom/CallScreeningService) e [CallLog.Calls](https://developer.android.com/reference/android/provider/CallLog.Calls).

## Exportação e sincronização

O JSON conserva a lista e os campos existentes; cada chamada acrescenta `decisionEvents`, com detalhes como objetos JSON. O CSV inclui `arrivalScore`, `observedResult` e o diário completo em `decisionEventsJson`, com aspas escapadas. Eventos de rejeição, feedback e reprocessamento não são omitidos.

O lote da API acrescenta `decision_events`. O servidor preserva os eventos anteriores e acrescenta os recebidos com `$addToSet`; clientes antigos podem continuar enviando lotes sem esse campo. A resposta acrescenta `accepted_event_ids`, confirmados somente após persistência. O app confirma apenas os IDs enviados e aceitos: um evento criado durante o envio continua pendente. Uma chamada cuja linha esteja `SYNCED`, mas tenha eventos pendentes, volta à fila.

Servidor antigo que não confirmar os eventos deixa o diário pendente no aparelho e mostra a necessidade de confirmação. O cliente interrompe esse envio sem entrar num ciclo infinito nem descartar os dados. Atualizar apenas o APK não basta para persistir o diário no servidor: as duas partes precisam ser atualizadas.

## Limites e validação

Os registros anteriores à atualização não ganham uma decisão de chegada inventada. Reconciliar o CallLog pode acrescentar observações antigas ainda disponíveis, mas não recupera respostas originalmente não registradas. O comportamento depende das informações e permissões fornecidas pelo Android/OEM. Falhas no próprio armazenamento são relatadas no log do serviço; não é possível garantir persistência quando o banco/disco está indisponível.

Testes JVM cobrem recusa manual, separação entre pedido e resultado observado, autoria de bloqueio, legado/simulação, preservação após reprocessamento, idempotência das observações, chamadas próximas, associação por timestamp, exportação, normalização `055`/`0055`, poda de eventos redundantes e confirmação de eventos durante sincronização. Os testes Rust verificam o contrato JSON/BSON e compatibilidade com payload antigo.

O teste Rust que usa MongoDB real fica ignorado por padrão e exige banco com nome terminado em `_test` antes de conectar. Não executar testes de integração contra a base operacional. A validação padrão usa `cargo test --locked` sem habilitar o teste ignorado.

O APK local fica em `SpamShieldApp/app/build/outputs/apk/debug/app-debug.apk`. Rodar Gradle diretamente não publica esse APK em `dist`, não altera o servidor em execução e não instala no telefone. O teste físico de chamadas e a integração com MongoDB isolado são verificações separadas.

## Publicação em 10/11/2026

Código publicado no commit `94c0abf`, branch `main`. Release GitHub: https://github.com/danielcdes-create/spamshield/releases/tag/v1.3.1. APK v1.3.1 / Build 15 publicado na VPS e no canal OTA, com SHA-256 `15e5a783c6489c783f9ec35fd8841be0b50d9598bd43ebde37ea9ba09a554edf`. Backend Linux compilado fora da VPS e container atualizado. Validação após reinício: saúde `ok`, MongoDB conectado, download `/apk` HTTP 200 e lote vazio autenticado retornando `accepted_event_ids`. Essa verificação de protocolo não grava chamadas sintéticas. O binário anterior foi preservado em `/opt/spamshield/backups-v1.3.1/`. Instalação no telefone continua dependente da atualização pelo usuário.
