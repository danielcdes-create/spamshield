**Avaliação do SpamShield — 10/11/2026**

Há uma regressão real na versão 1.3.0 capaz de deixar passar chamadas de prefixos já quarentenados. A causa mais forte encontrada é a divergência entre o banco local e o novo cache usado pelo bloqueio. Reproduzi o defeito em teste. Isso torna a piora relatada tecnicamente plausível, embora o arquivo exportado não permita determinar quantas ligações de spam efetivamente tocaram após a atualização.

O usuário informou instalação em 09/11/2026, versão “1.30”, interpretada como 1.3.0. O checkout está no commit `9a3e420cd66b96793536db9f77a3f6a3bbac9900`, que declara 1.3.0 / build 14. Não houve leitura do APK instalado no telefone para confirmar o build exato. A autoria “Grok Build” é informação do usuário; o Git, sozinho, não identifica o agente que produziu cada trecho.

**1. Prioridade alta: quarentenas gravadas podem não chegar ao bloqueador**

A versão anterior consultava as quarentenas no DAO durante a avaliação. O commit `9a3e420` substituiu essas consultas pelo singleton `QuarantinePrefixIndex`.

O índice é carregado uma vez, de forma assíncrona, no `onOpen` do Room. O motor também o atualiza quando ele próprio cria uma quarentena. Entretanto, sincronização, criação/remoção manual, confirmação de spam e reprocessamento alteram o DAO sem atualizar o índice. Não há observador que mantenha os dois estados coerentes.

Consequência: uma quarentena pode aparecer na tela e no banco, enquanto a chamada continua sem essa proteção. A situação inversa também existe: remover uma quarentena pode não interromper seu efeito no processo atual.

Reproduções com dados sintéticos, usando o motor real e o DAO de teste existente:

| Cenário | Resultado observado |
| --- | --- |
| Prefixo confirmado no banco, cache vazio | Permitida, score 45 |
| Mesma configuração após carregar o cache | Bloqueada, score 140 |
| Quarentena removida do banco, mantida no cache | Continua bloqueando |
| Quarentena vencida carregada como no `onOpen` | Continua bloqueando |
| Número repetido após quarentena gravada só no banco | `ALLOWED_EMERGENCY_REPEAT` |

O score 45 do teste usa o peso padrão 20 para ausência de verificação. No arquivo fornecido esse peso é 15, resultando em base 40. Os 140 pontos do teste somam 25 + 20 + 70 de quarentena + 25 da regra de tronco, que também dispara em um acerto exato de quarentena.

O índice armazena apenas strings, sem validade. Seu carregamento inclui quarentenas vencidas e seu estado também não acompanha a limpeza por expiração. Além disso, o carregamento assíncrono deixa uma janela inicial sem garantia de prontidão. A duração dessa janela não foi medida no aparelho.

Fontes: [consulta no motor](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/domain/SpamScoringEngine.kt:227), [carregamento inicial](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/di/DiModule.kt:63), [índice](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/domain/QuarantinePrefixIndex.kt:18), [alteração manual](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/sync/SyncRepository.kt:353).

**Correção recomendada:** manter um único fluxo de atualização de quarentenas, com instantâneo completo, troca atômica, validade e prontidão explícitas. Toda inclusão, exclusão, sincronização e reclassificação precisa atualizar o estado consultado pelo motor. Trocar apenas o cache por consultas adicionais sem avaliar o prazo do Android não resolve o desenho inteiro.

**2. Prioridade alta: o reprocessamento transforma avaliação posterior em suposto bloqueio anterior**

Antes de exportar, o app reconcilia o CallLog e reprocessa a base. O registro original é sobrescrito: o arquivo não preserva separadamente a decisão tomada na chegada, o aprendizado posterior e a reavaliação com as regras atuais.

No reprocessamento, os números distintos são agrupados sobre todo o histórico, sem janela de 24 horas ou limite superior pelo instante da chamada. Depois esse total alimenta `PREFIX_NEIGHBORHOOD_BURST`, cujo significado declarado é uma rajada em 24 horas. Quarentenas atuais também podem ser aplicadas a eventos antigos sem verificar se existiam na chegada.

Reprodução: duas chamadas permitidas, com três dias de distância e números diferentes no mesmo prefixo. Ao reprocessar, a primeira recebeu +60 por rajada e virou `BLOCKED`, score 105, usando informação de uma chamada que ainda não havia ocorrido. O reprocessamento criou quarentena no banco, mas ela continuou ausente do cache.

Outra reprodução: uma chamada permitida na chegada, score 45, foi rejeitada posteriormente no discador. O registro passou para `BLOCKED`, score 90. A tela de auditoria tenta distinguir aprendizado pela lista de regras, mas o painel soma `BLOCKED` como bloqueio. Essa distinção fica ainda menos confiável depois de novas reclassificações.

Fontes: [exportação](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/ui/MainViewModel.kt:372), [agrupamento sem janela](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/service/CallHistoryAnalyzer.kt:510), [quarentena atual em evento antigo](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/service/CallHistoryAnalyzer.kt:658), [contagem do painel](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/ui/screens/DashboardScreen.kt:64).

**Correção recomendada:** preservar uma decisão de chegada imutável, a resposta enviada ao Android e a origem de cada evento. Guardar aprendizado e reavaliação em campos separados. Calcular vizinhança somente com eventos anteriores dentro da janela apropriada. Esse problema existe no código atual; não atribuo sua origem inteira à última alteração.

**3. Prioridade alta, condicional: regex inválida pode liberar até chamadas quarentenadas**

A compilação de regras configuradas passou de uma operação protegida por `runCatching` para `ScreeningPatternCache.pattern`, que deixa escapar `PatternSyntaxException`. O serviço captura a exceção geral e responde permitindo a chamada.

O teste confirmou que uma única regex `[` habilitada aborta a avaliação mesmo com acerto exato no cache. Nenhum registro de telemetria da avaliação é criado. Não há inventário das regras habilitadas no JSON fornecido; portanto, o mecanismo está comprovado, mas não há evidência de que uma regex inválida tenha causado as chamadas relatadas.

Fontes: [compilação](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/domain/SpamScoringEngine.kt:218), [cache de expressões](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/domain/ScreeningPatternCache.kt:24), [liberação em erro](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/service/SmartCallScreeningService.kt:80).

**Correção recomendada:** validar antes de aceitar regras e isolar falhas de uma regra durante a preparação do instantâneo, preservando as proteções válidas. Registrar a falha como evento auditável.

**4. Prioridade média: regras por número nacional perderam compatibilidade**

O motor anterior testava regex também contra `nationalDigits`. O novo conjunto de textos contém número bruto, E.164, assinante sem DDD e prefixo, mas omite o número nacional completo. Uma regra ancorada em DDD + assinante pode deixar de atuar.

O teste reproduziu uma regra sintética `^319952\d{5}$` que corresponde ao número nacional e acrescentaria 90 pontos: ela não disparou e a chamada foi permitida com 45. Não encontrei no export evidência de uma regra personalizada desse formato em uso.

Fonte: [textos usados pelo scorer](C:/Users/danie/spamshield/SpamShieldApp/app/src/main/java/br/com/spamshield/domain/HeuristicScorer.kt:107).

**5. O que a telemetria permite afirmar**

Fonte: `G:/Meu Drive/spamshield_telemetry (4).json`, 393.853 bytes, SHA-256 `dab3af6dda09fec4bf7fd249fe76f20b5187186fdaf5ed92400b3bed7edc93e0`. Período dos eventos: 26/09/2026 12:05:22 a 10/11/2026 17:55:18, horário de São Paulo.

| Verificação | Resultado |
| --- | --- |
| Registros exportados | 391 |
| Origem `android-edge-br` | 93 |
| Histórico importado `s23-ultra-history` | 298 |
| Classificados como `BLOCKED` | 285 |
| `BLOCKED` na origem de borda | 72 |
| Desses 72 com regra de aprendizado `SYSTEM_CALLLOG_BLOCKED` | 25 |
| Registros de 10/11, todos de origem de borda | 25: 20 `BLOCKED`, 5 `ALLOWED_LOW_SCORE` |
| Feedback | 12 `CONFIRMED_SPAM`, 379 sem avaliação |
| Limite aplicado | 70 em todos os registros |
| Modo sombra | Desativado em todos os registros |
| Sincronização | 389 `SYNCED`, 2 `PENDING` |
| Soma dos pesos diferente do score | Nenhum caso |
| `BLOCKED` com duração positiva | Nenhum caso |

Os cinco registros de hoje classificados como permitidos têm score 40, formado por 25 de desconhecido + 15 de ausência de verificação. Horários: 08:44:34, 10:57:44, 12:08:25, 16:36:34 e 16:36:53. Todos estão sem feedback. Não é possível classificá-los como cinco falsos negativos sem saber quais eram spam; seus scores e estados também podem ter sido reprocessados antes da exportação.

Há sete pares de registros com mesmo número e diferença de até 15 segundos, envolvendo 14 linhas. Cinco pares têm também timestamp idêntico. São candidatos a duplicidade; proximidade temporal sozinha não comprova identidade da chamada. Dois pares adicionais envolvem registros de observação `calllog-*`.

Em 72 registros com `PREFIX_NEIGHBORHOOD_BURST`, o export não contém dois números distintos no mesmo prefixo nas 24 horas anteriores, incluindo o evento corrente. Isso é uma lacuna de corroboração no arquivo, não uma afirmação de que o telefone jamais teve outros eventos. O defeito do agrupamento sem janela foi confirmado separadamente pelo teste.

Quatro registros com cabeçalho `055...` permanecem `UNKNOWN`, com normalização iniciada por `+55055...`; precisam de tratamento explícito de formato inválido/ambíguo. Não há prova de liberação dessas quatro chamadas: todas estão atualmente classificadas como `BLOCKED`.

Não há base para calcular precisão, recall ou a queda percentual de eficácia: faltam rótulos das chamadas permitidas, decisão original imutável, versão por evento e comparabilidade entre períodos. `SYNCED` confirma o estado gravado de envio, sem provar que o cache aplicou uma regra. `NOT_VERIFIED` aparece em 365 registros e tampouco equivale a spam confirmado.

**6. Validação e limites**

Executei a suíte Android junto com oito testes de caracterização externos ao código do aplicativo: **44 testes, zero falhas** — 36 existentes e oito desta avaliação. Os testes adicionais passam quando reproduzem o comportamento defeituoso atual; não representam aprovação desse comportamento. A suíte preexistente cobre partes do contrato, mas não garante a coerência banco/cache nem a causalidade temporal do reprocessamento.

Executei também `cargo test --locked`: **nove testes passaram**. Identifiquei depois da execução que o teste de integração existente usa o MongoDB configurado pelo ambiente, chama inicialização/seeds/migração e realiza escrita real. Ele insere e depois remove sua telemetria de teste, mas atualiza a quarentena `3141011` e não a restaura: o caminho executado incrementa acertos na ingestão e na confirmação de spam, define peso 80 e validade de 30 dias. Não capturei o estado anterior, portanto não tentei uma restauração por suposição. Isso é um defeito de isolamento da própria suíte e um efeito desta execução que precisa ficar registrado. Nenhuma outra execução desse teste foi feita.

Não publiquei APK nem alterei o código funcional do aplicativo ou servidor. Foram adicionados somente os arquivos desta avaliação. Não houve teste físico de chamadas, leitura do logcat, verificação do papel `ROLE_CALL_SCREENING` no aparelho ou medição do tempo completo até `respondToCall`.

O serviço permite em caso de exceção e não limita explicitamente o tempo de sua avaliação. A latência exportada reinicia o cronômetro depois de consultas de Contacts/CallLog e termina antes de toda a persistência/resposta; não mede o prazo completo do sistema. A API Android exige resposta em até cinco segundos: [documentação oficial de CallScreeningService](https://developer.android.com/reference/android/telecom/CallScreeningService). Não há evidência no export para afirmar que esse prazo foi excedido nas chamadas relatadas.

**7. Ordem recomendada de correção**

1. Corrigir prontidão, atualização e expiração do cache, cobrindo sync, feedback e reprocessamento. É o defeito com ligação mais direta à regressão de proteção em 1.3.0.
2. Preservar decisões originais e corrigir a janela temporal antes de usar o painel para avaliar eficácia.
3. Isolar regex inválidas e restaurar compatibilidade das regras nacionais.
4. Isolar os testes Rust em banco exclusivo e impedir conexão acidental à base operacional.
5. Validar no aparelho uma sequência controlada de chamadas, incluindo spam confirmado, retorno legítimo e expiração, com versão, motivo da decisão e resposta ao Android registrados.

Reduzir o limite global de 70 para 40 tornaria qualquer desconhecido não verificado elegível a bloqueio. Isso não corrige o cache e aumenta o risco de bloquear chamadas legítimas. Também não recomendo reverter indiscriminadamente as melhorias: a consistência aritmética está preservada, a duração positiva já é separada de `BLOCKED` e existem ajustes úteis para reduzir falsos positivos. A granularidade /8 já existia antes da 1.3.0.

A nota técnica do repositório foi tratada como especificação para comparação, não como autorização para publicar, executar saneamento ou substituir a solicitação de avaliação.

**Arquivos para reprodução**

- [Análise reproduzível do JSON](C:/Users/danie/spamshield/audit/2026-11-10/analyze_telemetry.py)
- [Resultados e IDs de evidência, com números mascarados](C:/Users/danie/spamshield/audit/2026-11-10/telemetry_findings.json)
- [Oito testes de caracterização](C:/Users/danie/spamshield/audit/2026-11-10/SpamShieldAuditTest.kt)
- [Resultados das suítes](C:/Users/danie/spamshield/audit/2026-11-10/test_results.json)

Na raiz do repositório, o script Python aceita o arquivo de entrada e `--output`. Para executar os testes Android adicionais, a partir de `SpamShieldApp`, use `./gradlew.bat -I ../audit/2026-11-10/audit.init.gradle :app:testDebugUnitTest --console=plain`, com Java/Android SDK configurados. A invocação inclui o diretório de diagnóstico apenas no source set de testes.

**Complemento informado pelo usuário:** grande parte dos 20 registros classificados como bloqueados em 10/11 corresponde a recusas manuais enquanto o telefone tocava. Portanto, esse total não deve ser apresentado como 20 bloqueios preventivos. A implementação posterior do diário de decisões é documentada em `docs/TELEMETRIA-DECISOES.md`; os testes e resultados desta avaliação original permanecem como registro histórico.

**Atualização de Resolução (`v1.3.1` — 10/11 e 11/11/2026):**
- Os pontos levantados nesta avaliação foram endereçados no código principal (`v1.3.1` / Build `15`): sincronização atômica e contínua do `QuarantinePrefixIndex`, diário de decisões imutável (`call_decision_events` / `DecisionTrace`), separação entre `SYSTEM_CALLLOG_BLOCKED` e `DIALER_USER_REJECTED_RINGING`, preservação causal de regras de chegada (`SCREENING_DECISION`) durante o reprocessamento, isolamento do teste de integração Rust (`#[ignore]` + exigência de sufixo `_test`), normalização de cabeçalhos `055`/`0055`, poda de eventos `QUARANTINE_UPDATED`/`POST_CALL_EVALUATION` redundantes e deduplicação de códigos curtos/registros legados `calllog-*`. Consulte [docs/TELEMETRIA-DECISOES.md](../../docs/TELEMETRIA-DECISOES.md).
