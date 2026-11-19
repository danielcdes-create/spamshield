# Telemetria de decisões

## Correções da auditoria de 19/11/2026

- A exportação de JSON, CSV e resumo usa uma única leitura transacional. Ela inclui o diário que já estava no banco quando a leitura começou. `decisionEventSync` no JSON, `decisionEventSyncJson` no CSV e `pendingEventCount` no resumo descrevem o estado local da sincronização dos eventos.
- A inclusão automática de quarentena usa a data da chamada como início da validade. Na importação, apenas evidência dentro da janela de sete dias pode criar proteção. O reprocessamento atualiza a classificação e registra as transições do diário, preservando a evidência de chegada. Modo sombra e simulações deixam a proteção intacta. A sincronização de proteções automáticas usa `automatic=true` no endpoint específico de quarentena, que preserva proteções ativas e respeita a ordem de remoções.
- A triagem tenta responder até 3,5 segundos após o callback. Se a avaliação estourar esse orçamento, a contingência permite a chamada. O serviço envia apenas uma resposta por callback e grava o diário depois do envio.
- O backend deixa de criar quarentenas a partir da classificação enviada em lotes de telemetria. Atualize servidor e APK para aplicar a mesma política nos dois lados. Quarentenas legadas sem proveniência suficiente ficam sujeitas à validade já registrada ou à remoção explícita.

## Transporte fiel e compatibilidade — 1.3.4

O transporte usa o `detailsJson` armazenado como JSON bruto, preservando campos ausentes, desconhecidos e valores explícitos. A decodificação tipada continua servindo à interface e à proveniência; ela não reconstrói o payload enviado ao servidor.

O hotfix do backend compara cópias dos eventos schema 1 ignorando somente os defaults aditivos introduzidos pela 1.3.3: versões de app nulas e `evaluatedRules.enabled=true`. Isso não altera o documento armazenado. Alterações reais de versão, score, regra, horário, schema, fingerprint ou campos desconhecidos continuam gerando conflito, e o ACK depende da equivalência com uma variante já persistida.

A mescla começa com o array existente e acrescenta IDs inéditos. Variantes legadas já armazenadas não são apagadas durante o reenvio. O hotfix também atende clientes 1.3.3; o APK 1.3.4 evita a alteração de representação na origem.

## Versões explícitas na exportação — 1.3.3

O JSON mantém o array de chamadas e os campos anteriores. Cada linha acrescenta `provenance` e `exportMetadata`; `spamshield_telemetry_summary.json` acompanha JSON/CSV no compartilhamento e também representa exportações vazias.

- `provenance.originalDecision.appVersion`: versão registrada no evento `SCREENING_DECISION` do serviço real. Sem esse evento, `originalVersionStatus=ORIGINAL_VERSION_UNKNOWN`; não se usa a versão do reprocessamento ou do exportador como substituta.
- `provenance.currentClassification.appVersion`: versão do último snapshot de avaliação que corresponde à classificação atual. Se a correspondência não for demonstrável, o status é `CURRENT_CLASSIFICATION_VERSION_UNKNOWN`.
- `provenance.eventAppVersions`: versões representadas no diário daquela chamada.
- `exportMetadata.exporterVersionName/exporterVersionCode`: app que gerou o arquivo, sem atribuição dessa versão a chamadas antigas. `exportedAt`, `exportId` e as semânticas dos horários acompanham o arquivo.
- O resumo apresenta, por versão, contagens e período das chamadas com decisão original, além de contagens e período dos eventos gravados. Esses dois períodos permanecem distintos.
- CSV expõe os mesmos estados e versões em colunas; a tela de auditoria separa horário da chamada e horário de registro de cada evento.

Uma chamada de novembro decidida pela 1.3.2, reavaliada pela 1.3.3 e exportada pela 1.3.3 mantém esses três papéis separados. O horário exato de instalação não é inferido da primeira atividade de uma versão.

Eventos novos usam `details.schemaVersion=2`, `engineRevision=decision-journal-v2` e `appVersionName/appVersionCode`. Eventos antigos mantêm seu schema e seus campos originais; a decodificação não injeta a versão atual nos eventos antigos. Avaliações idênticas em versões ou configurações diferentes são preservadas como etapas distintas.

Room `4→5` acrescenta a outbox e a origem das quarentenas remotas. A sincronização exige consentimento explícito; enquanto desativada, os dados e as mutações ficam no aparelho. O backend atualizado mescla eventos por ID atomicamente e só confirma o conjunto após persistência. Consulte [release 1.3.3](RELEASE-1.3.3.md).

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

## Reprocessamento e integridade do diário

- Avaliações repetidas da mesma origem podem ser omitidas enquanto a classificação continuar igual. Se a classificação mudou entre duas avaliações iguais, o retorno ao resultado anterior também fica registrado. Eventos gravados conservam o ID e a chamada associados.
- A reconciliação associa observações do CallLog à ocorrência mais próxima dentro de 15 segundos. Quando já há uma observação mais próxima da chamada, a observação de uma rediscagem fica fora desse vínculo.
- O reprocessamento usa `quarantineMatch`, `distinctNumbers24h` e as regras contextuais de vizinhança do evento de chegada, quando disponíveis. A avaliação de histórico usa a janela de 24 horas anterior à respectiva chamada. Somente o tipo `REJECTED_TYPE` do CallLog sustenta aprendizado por recusa manual.
- A normalização de cabeçalhos `055` e `0055` atualiza a classificação do registro de chamada. A deduplicação automática preserva linhas sincronizadas, avaliadas pelo usuário ou com diário próprio.

## O que o painel pode afirmar

- **Bloqueio solicitado:** houve resposta de bloqueio enviada pelo `CallScreeningService` real. Simulações não contam.
- **Bloqueio registrado pelo Android:** a observação mais recente registra `BLOCKED_TYPE`, `BLOCK_REASON_CALL_SCREENING_SERVICE` e atribuição explícita ao SpamShield, mesmo quando o diário local carece do pedido.
- **Triagens registradas:** linhas com decisão ou resposta do serviço real. `total_incoming_records` na API inclui o histórico de entrada e `total_intercepted` conta as triagens registradas.
- **Rejeitada no discador:** o Android registrou `REJECTED_TYPE`, separado dos bloqueios.
- **Legado:** falta a decisão original. Não se reconstrói essa prova a partir do score atual.

O painel e a API de analíticos deixam de usar `outcome == BLOCKED` como prova de bloqueio preventivo. A API mantém `total_blocked` para bloqueios com evidência e acrescenta `total_block_requests` e `total_dialer_rejected`. A tela de auditoria mostra a sequência de eventos e a classificação atual separadamente. Estimativas de tempo economizado foram substituídas por contagens observáveis.

O retorno de `respondToCall` indica envio ao Android. O sistema pode ignorar uma resposta tardia. `elapsedMs` mede o caminho até o envio, enquanto `processingLatencyMs` continua sendo uma medida do motor.

Referências: [CallScreeningService](https://developer.android.com/reference/android/telecom/CallScreeningService) e [CallLog.Calls](https://developer.android.com/reference/android/provider/CallLog.Calls).

## Exportação e sincronização

O JSON conserva a lista e os campos existentes; cada chamada acrescenta `decisionEvents`, com detalhes como objetos JSON. O CSV inclui `arrivalScore`, `observedResult` e o diário completo em `decisionEventsJson`, com aspas escapadas. Eventos de rejeição, feedback e reprocessamento não são omitidos.

O servidor valida eventos antes de atualizar cada chamada. Quando um lote tem chamadas aceitas e chamadas conflitantes, a resposta inclui `accepted_ids`, `accepted_event_ids` e `conflicting_call_ids`. Quando todas conflitam, a resposta é HTTP 409. O app isola itens recusados por HTTP 409 ou 413, prossegue com as demais chamadas e conserva os dados pendentes.

A confirmação local cobre somente a versão da linha enviada e os IDs de eventos aceitos para a respectiva chamada. Eventos criados durante o envio continuam pendentes. Sem confirmação de um servidor antigo, a chamada fica pendente e as outras chamadas, regras e quarentenas seguem na sincronização.

Desde o hotfix de 18/11/2026, `POST /api/v1/telemetry/batch` aceita corpos JSON de até 16 MiB (16.777.216 bytes). O limite inclui o diário dos eventos e não é determinado somente pela quantidade de chamadas. Acima dele, o servidor retorna HTTP 413 com `error=TELEMETRY_BATCH_TOO_LARGE` e `max_request_bytes`. Esse aumento atende também à 1.3.4 instalada; não muda os limites das demais rotas. Os logs de transporte registram somente o tamanho declarado no `Content-Length`, quando fornecido, e o status HTTP; na recusa, também registram o limite.

## Limites e validação

Os registros anteriores à atualização não ganham uma decisão de chegada inventada. Reconciliar o CallLog pode acrescentar observações antigas ainda disponíveis, mas não recupera respostas originalmente não registradas. O comportamento depende das informações e permissões fornecidas pelo Android/OEM. Falhas no próprio armazenamento são relatadas no log do serviço; não é possível garantir persistência quando o banco/disco está indisponível.

Testes JVM cobrem prazo de triagem, recusa manual, autoria de bloqueio, simulação, preservação após reprocessamento, chamadas próximas, exportação, normalização `055`/`0055`, sincronização parcial e confirmação de eventos. Os testes Rust verificam o contrato JSON/BSON, preservação de eventos e compatibilidade com payload antigo.

O teste Rust que usa MongoDB real fica ignorado por padrão e exige banco com nome terminado em `_test` antes de conectar. Não executar testes de integração contra a base operacional. A validação padrão usa `cargo test --locked` sem habilitar o teste ignorado.

O APK local fica em `SpamShieldApp/app/build/outputs/apk/debug/app-debug.apk`. Rodar Gradle diretamente não publica esse APK em `dist`, não altera o servidor em execução e não instala no telefone. O teste físico de chamadas e a integração com MongoDB isolado são verificações separadas.

## Publicação em 10/11/2026

Código publicado no commit `94c0abf`, branch `main`. Release GitHub: https://github.com/danielcdes-create/spamshield/releases/tag/v1.3.1. APK v1.3.1 / Build 15 publicado na VPS e no canal OTA, com SHA-256 `15e5a783c6489c783f9ec35fd8841be0b50d9598bd43ebde37ea9ba09a554edf`. Backend Linux compilado fora da VPS e container atualizado. Validação após reinício: saúde `ok`, MongoDB conectado, download `/apk` HTTP 200 e lote vazio autenticado retornando `accepted_event_ids`. Essa verificação de protocolo não grava chamadas sintéticas. O binário anterior foi preservado em `/opt/spamshield/backups-v1.3.1/`. Instalação no telefone continua dependente da atualização pelo usuário.
