# NOTA TÉCNICA DE ENGENHARIA: CORREÇÃO ESTRUTURAL DO MOTOR SPAMSHIELD

* **Identificador:** `NT-SPAMSHIELD-2026-001`
* **Alvo de Execução:** `LLM Harness` (Agente Autónomo de Codificação / CI-CD)
* **Escopo:** Motor de Triagem (Core), Parser E.164, Máquina de Estados de Chamada e Sincronização de Telemetria
* **Modo de Operação:** Execução Sequencial Estrita (*Strict Gatekeeping* — a Etapa $N+1$ só pode ser iniciada após $100\%$ dos testes da Etapa $N$ passarem).

---

## 0. DIRETRIZES DE EXECUÇÃO PARA O LLM HARNESS

1. **Ordem de Dependência:** As etapas estão ordenadas da camada mais baixa (parsing e normalização de strings) até à camada mais alta (persistência e sincronização de rede). Alterar esta ordem causará falsos negativos nos testes unitários das camadas superiores.
2. **Validação por Contrato:** Ao final de cada etapa, o Harness deve executar a suíte de testes utilizando os blocos JSON `input` e `expected_output` fornecidos nesta nota.
3. **Imutabilidade de Auditoria:** O somatório dos pesos em `triggeredRulesJson` deve satisfazer estritamente a equação de consistência:
   $$\text{finalScore} = \max\left(0, \sum_{i=1}^{k} w_i\right)$$
   Onde $w_i$ representa o atributo `weight` de cada regra ativada no vetor JSON.

---

## ETAPA 1: Saneamento do Parser Numérico, Normalização E.164 e Inferência de Operadora

### 1.1. Diagnóstico Técnico
* **Injeção Indevida de DDD Local em Códigos Curtos (Short Codes):** Números de serviços de emergência/utilidade ou códigos de operadora truncados como `"005"` (ID `5b6b6016-2b8c-399c-9256-08967726e174`), `"00411"` (ID `cd911a05-799c-45c2-bfcc-ff1513db7f05`) e `"03110315"` (ID `f2659819-aa1e-3e10-9753-3de65f2c3ae2`, central $10315$) estão a receber o DDD padrão `31`, gerando números E.164 inválidos (`"+5531005"`, `"+553100411"`, `"+553110315"`) e acionando falsamente `ANATEL_PABX_VOIP_RANGE`.
* **Contaminação de Operadora pelo CSP (Código de Seleção de Prestadora):** No registo `4bc363db-4816-38b9-aca1-6b47b39a0db8` (`rawNumber: "004131939995971"`), o prefixo de longa distância `0041` (TIM) contamina o `carrierGuess`, classificando o número como `"TIM"`, embora a faixa `31939xx` pertença à `"Claro"` (conforme comprovado pelo registo `831860a9-4221-3e69-8e33-cc58a41034c9`).
* **Falta de Validação de Cardinalidade Móvel (Subcomprimento / Spoofing):** O registo `066db72d-07d8-47cf-8544-bfb0bb87d69c` (`rawNumber: "+553198621040"`) possui apenas $8$ dígitos após o DDD (`98621040`), violando o padrão brasileiro de $9$ dígitos para linhas móveis iniciadas por $9$. O mesmo ocorre em `664a63a0-9ad2-4aee-9720-7e0a91cd32a6` (`0415476080729` $\rightarrow$ `+555476080729`, onde linhas iniciadas por $7$ são fixas/trunk ou números malformados, mas foi classificado como `MOBILE`).

### 1.2. Especificação de Implementação
1. **Ordem de Descasque (Stripping Pipeline):**
   * Passo 1: Remover caracteres não numéricos (preservando `+` inicial se existir).
   * Passo 2: Detetar códigos curtos e serviços de utilidade pública (`103xx`, `105x`, `106xx`, `1xx`, `0800`, `0300`, `400x`, ou strings com comprimento total $L < 8$ dígitos). Se detetado:
     * Definir `lineType = "TOLL_FREE_OR_SPECIAL"`.
     * Não injetar `+55` nem o DDD local se for código curto ou `103xx`.
     * Isentar da regra `ANATEL_PABX_VOIP_RANGE`.
   * Passo 3: Extrair o prefixo de tronco/CSP usando a expressão regular:
     $$\text{Regex CSP: } \quad \text{\^{}}(?:00|\text{0})(?:(12|14|15|21|31|41|43|99))?([1-9][0-9])(\textbackslash d+)\$ $$
     Onde o Grupo 1 é o `cspCode` (opcional), o Grupo 2 é o `ddd` ($2$ dígitos) e o Grupo 3 é o `subscriberNumber` (número de assinante).
2. **Validação de Cardinalidade do Assinante (`subscriberNumber`):**
   * Se o primeiro dígito de `subscriberNumber` for $9$: o comprimento $L_{\text{sub}}$ **deve** ser exatamente $9$ dígitos. Se $L_{\text{sub}} \neq 9$, classificar `lineType = "MALFORMED_OR_SPOOFED"` e acionar a nova regra `MALFORMED_NUMBER_SPOOFING` (`weight: 85`).
   * Se o primeiro dígito estiver entre $[2, 5]$: o comprimento $L_{\text{sub}}$ **deve** ser exatamente $8$ dígitos (`lineType = "FIXED_OR_VOIP"`).
   * Se o primeiro dígito estiver entre $[6, 8]$ com $L_{\text{sub}} = 8$: classificar como legado móvel inválido/IoT ou `MALFORMED_OR_SPOOFED`, nunca como `MOBILE` padrão sem penalização.
3. **Inferência de Operadora (`carrierGuess`):**
   * Executar o lookup de operadora **exclusivamente** sobre a concatenação `ddd + subscriberNumber`, ignorando completamente o `cspCode` (`041`, `015`, `021`, `0041`).

### 1.3. Contrato JSON de Teste — Etapa 1

```json
{
  "stage": "STAGE_1_PARSER_AND_NORMALIZATION",
  "test_cases": [
    {
      "test_id": "TC_101_UTILITY_CODE_10315",
      "source_log_id": "f2659819-aa1e-3e10-9753-3de65f2c3ae2",
      "input": {
        "rawNumber": "03110315",
        "userLocalDdd": "31"
      },
      "expected_output": {
        "normalizedNumber": "10315",
        "ddd": "31",
        "lineType": "TOLL_FREE_OR_SPECIAL",
        "carrierGuess": "Vivo",
        "isShortOrUtilityCode": true
      }
    },
    {
      "test_id": "TC_102_CSP_CONTAMINATION_CLARO_OVER_0041",
      "source_log_id": "4bc363db-4816-38b9-aca1-6b47b39a0db8",
      "input": {
        "rawNumber": "004131939995971",
        "userLocalDdd": "31"
      },
      "expected_output": {
        "normalizedNumber": "+5531939995971",
        "ddd": "31",
        "cspExtracted": "0041",
        "subscriberNumber": "939995971",
        "lineType": "MOBILE",
        "carrierGuess": "Claro"
      }
    },
    {
      "test_id": "TC_103_MALFORMED_8_DIGIT_MOBILE_SPOOFING",
      "source_log_id": "066db72d-07d8-47cf-8544-bfb0bb87d69c",
      "input": {
        "rawNumber": "+553198621040",
        "userLocalDdd": "31"
      },
      "expected_output": {
        "normalizedNumber": "+553198621040",
        "ddd": "31",
        "subscriberNumber": "98621040",
        "lineType": "MALFORMED_OR_SPOOFED",
        "triggeredRuleRequired": {
          "rule_id": "MALFORMED_NUMBER_SPOOFING",
          "weight": 85,
          "meta": "Mobile length 8 != 9"
        }
      }
    }
  ]
}
```

---

## ETAPA 2: Padronização Determinística de Prefixos e Peso de Quarentena

### 2.1. Diagnóstico Técnico
* **Mutação de Comprimento no Campo `prefix7`:** Nos registos mais antigos (ex: `13ef82b4-6826-3c2f-92a4-4438b1ae3af2`), o campo `prefix7` guarda $7$ dígitos (`"3198836"`). Nos registos mais recentes (ex: `4252c6ec-a56d-418d-a25c-8b16267bbee5`), o mesmo campo `prefix7` guarda $8$ dígitos (`"31414605"`).
* **Divergência por Truncamento no `rawNumber`:** No par de registos `940c2a06-b6f5-3ca7-8380-93e0e5544aaf` e `4bc363db-4816-38b9-aca1-6b47b39a0db8`, gerados no mesmo milissegundo para `"004131939995971"`, um extrai `"3193999"` ($7$ dígitos) e o outro `"31939995"` ($8$ dígitos), porque uma thread cortou a string antes da remoção completa do CSP `0041`.
* **Oscilação de Peso na Quarentena:** A regra `PREFIX_QUARANTINE_HIT` atribui peso $60$ no registo `037fe068-c0de-38ea-bf21-1b0c3c0ab48e`, peso $65$ em `ce0e429d-b65c-3276-b896-24937ca5446b` e peso $70$ nos restantes.

### 2.2. Especificação de Implementação
1. **Extração Pós-Normalização:** O prefixo de clusterização nunca deve ser extraído de `rawNumber`. Deve ser derivado exclusivamente de `nationalNumber = ddd + subscriberNumber`.
2. **Definição de Cardinalidade Fixa ($8$ dígitos):**
   * Para linhas `MOBILE` ($11$ dígitos nacionais) e `FIXED_OR_VOIP` ($10$ dígitos nacionais), o prefixo de quarentena deve conter exatamente os primeiros $8$ dígitos do `nationalNumber` ($\text{DDD} + 6$ primeiros dígitos do assinante, deixando $3$ dígitos finais para rotação em móvel e $2$ dígitos finais em fixo).
   * Para manter compatibilidade retroativa com o schema JSON atual, preencher a propriedade `prefix7` (ou adicionar `prefixCluster8` mantendo `prefix7` como alias) sempre com $\text{length} = 8$ para números E.164 válidos, ou `null` para `TOLL_FREE_OR_SPECIAL`.
3. **Peso Determinístico:** Padronizar o peso de `PREFIX_QUARANTINE_HIT` em exatamente $70$ pontos para match de $8$ dígitos. Caso exista fallback de match parcial de $7$ dígitos, usar um `rule_id` distinto (`PREFIX7_BROAD_QUARANTINE_HIT`) com peso fixo e documentado, evitando mutação silenciosa do mesmo `rule_id`.

### 2.3. Contrato JSON de Teste — Etapa 2

```json
{
  "stage": "STAGE_2_PREFIX_STANDARDIZATION",
  "test_cases": [
    {
      "test_id": "TC_201_DETERMINISTIC_8_CHAR_PREFIX",
      "source_log_id": "940c2a06-b6f5-3ca7-8380-93e0e5544aaf",
      "input": {
        "rawNumber": "004131939995971",
        "normalizedNumber": "+5531939995971",
        "ddd": "31",
        "subscriberNumber": "939995971",
        "lineType": "MOBILE"
      },
      "expected_output": {
        "prefix7": "31939995",
        "prefixLength": 8,
        "quarantineRule": {
          "rule_id": "PREFIX_QUARANTINE_HIT",
          "weight": 70,
          "meta": "31939995"
        }
      }
    },
    {
      "test_id": "TC_202_HISTORICAL_7_CHAR_MIGRATION",
      "source_log_id": "ce0e429d-b65c-3276-b896-24937ca5446b",
      "input": {
        "rawNumber": "031987806827",
        "normalizedNumber": "+5531987806827",
        "ddd": "31",
        "subscriberNumber": "987806827",
        "lineType": "MOBILE"
      },
      "expected_output": {
        "prefix7": "31987806",
        "prefixLength": 8,
        "quarantineRule": {
          "rule_id": "PREFIX_QUARANTINE_HIT",
          "weight": 70,
          "meta": "31987806"
        }
      }
    }
  ]
}
```

---

## ETAPA 3: Calibração de Pesos, STIR/SHAKEN Trust e Desduplicação de Regras

### 3.1. Diagnóstico Técnico
* **Falso Positivo Sistemático em Linhas Fixas Verificadas (`stirShakenStatus == "PASSED"`):**
  * Nos registos `de65c16e-3bfd-4ab6-8837-bd70a851f16c` (`03137682020`) e `86155641-677c-49fb-8f80-5bb5213f6edc` (`03137686745`), ambas as chamadas são de telefonia fixa convencional (`3768-xxxx`), têm assinatura `stirShakenStatus: "PASSED"` e não estão em quarentena.
  * Contudo, somam `NOT_IN_CONTACTS` ($25$) + `ANATEL_PABX_VOIP_RANGE` ($35$) + `LEADING_ZERO_TRUNK_DIALING` ($25$) = $85 \ge 70 \rightarrow \text{BLOCKED}$.
  * Isto significa que **$100\%$ das chamadas de telefones fixos locais fora da agenda (hospitais, clínicas, escolas) estão a ser bloqueadas**, mesmo quando autenticadas pela operadora.
* **Sobreposição Tripla de Regras VoIP:**
  * No registo `84ec8da5-0ee3-4ba3-a8f0-35c11624c19a` (`03141011230`), o prefixo `314101` aciona simultaneamente `REGEX_MG_VOIP_TRUNKS` ($+80$), `BLACKLIST_MG_VOIP_PABX` ($+80$) e `ANATEL_PABX_VOIP_RANGE` ($+35$), gerando `finalScore: 400`.

### 3.2. Especificação de Implementação
1. **Separação Estrita entre Fixo Convencional e PABX/VoIP:**
   * A regra `ANATEL_PABX_VOIP_RANGE` (`weight: 35`) **só** pode ser disparada quando `carrierGuess == "VoIP"` ou quando o prefixo de assinante pertencer a blocos dedicados a STFC Digital/VoIP (`30xx`, `31xx`, `40xx`, `41xx`, `2108`, `2517`).
   * Prefixos de telefonia fixa residencial/comercial clássica (`32xx` a `37xx`) com `carrierGuess == "Fixo"` **não** devem acionar `ANATEL_PABX_VOIP_RANGE`.
2. **Revisão de `LEADING_ZERO_TRUNK_DIALING`:**
   * Na sinalização SIP das operadoras brasileiras, entregar o número nacional com `0 + DDD` (ex: `031...`) é comportamento padrão da rede local e não evidência isolada de tronco pirata.
   * Aplicar `LEADING_ZERO_TRUNK_DIALING` (`weight: 25`) **apenas** quando:
     1. O número contiver CSP explícito (`041`, `015`, `021`, `0041`) ou prefixo de país malformado (`055`), **OU**
     2. O `stirShakenStatus` for `"FAILED"`, **OU**
     3. Houver combinação com `PREFIX_QUARANTINE_HIT` ou `carrierGuess == "VoIP"`.
   * Se `ddd == userLocalDdd`, sem CSP, `carrierGuess == "Fixo"` e `stirShakenStatus == "PASSED"`, o peso de `LEADING_ZERO_TRUNK_DIALING` deve ser $0$ (não disparar).
3. ** Exclusão Mútua (Mutex Group) para Regras de Tronco VoIP:**
   * Consolidar `REGEX_MG_VOIP_TRUNKS`, `BLACKLIST_MG_VOIP_PABX` e `ANATEL_PABX_VOIP_RANGE` num grupo de avaliação hierárquico (`VOIP_TRUNK_GROUP`), onde apenas a regra mais específica (de maior peso) é adicionada a `triggeredRulesJson`.

### 3.3. Contrato JSON de Teste — Etapa 3

```json
{
  "stage": "STAGE_3_RULE_CALIBRATION_AND_MUTEX",
  "test_cases": [
    {
      "test_id": "TC_301_ALLOW_VERIFIED_LOCAL_FIXED_LINE",
      "source_log_id": "86155641-677c-49fb-8f80-5bb5213f6edc",
      "input": {
        "rawNumber": "03137686745",
        "normalizedNumber": "+553137686745",
        "ddd": "31",
        "prefix7": "31376867",
        "carrierGuess": "Fixo",
        "lineType": "FIXED_OR_VOIP",
        "stirShakenStatus": "PASSED",
        "inContacts": false,
        "inQuarantine": false,
        "thresholdApplied": 70
      },
      "expected_output": {
        "finalScore": 25,
        "outcome": "ALLOWED_LOW_SCORE",
        "category": "VERIFIED_UNKNOWN",
        "triggeredRules": [
          {
            "rule_id": "NOT_IN_CONTACTS",
            "weight": 25
          }
        ]
      }
    },
    {
      "test_id": "TC_302_DEDUPLICATE_VOIP_TRUNK_RULES",
      "source_log_id": "84ec8da5-0ee3-4ba3-a8f0-35c11624c19a",
      "input": {
        "rawNumber": "03141011230",
        "normalizedNumber": "+553141011230",
        "ddd": "31",
        "prefix7": "31410112",
        "carrierGuess": "VoIP",
        "lineType": "FIXED_OR_VOIP",
        "stirShakenStatus": "FAILED",
        "inContacts": false,
        "inQuarantine": true,
        "thresholdApplied": 70
      },
      "expected_output": {
        "finalScore": 285,
        "outcome": "BLOCKED",
        "category": "SPOOFING_STIR_FAILED",
        "triggeredRules": [
          {
            "rule_id": "NOT_IN_CONTACTS",
            "weight": 25
          },
          {
            "rule_id": "STIR_SHAKEN_FAILED",
            "weight": 85,
            "meta": "VERIFICATION_STATUS_FAILED"
          },
          {
            "rule_id": "PREFIX_QUARANTINE_HIT",
            "weight": 70,
            "meta": "31410112"
          },
          {
            "rule_id": "BLACKLIST_MG_VOIP_PABX",
            "weight": 80,
            "meta": "^(?:\\+55|0(?:\\d{2})?)?31(?:2108|2517|3191|4020|4062|4101)\\d{4}$"
          },
          {
            "rule_id": "LEADING_ZERO_TRUNK_DIALING",
            "weight": 25,
            "meta": "0314"
          }
        ]
      }
    }
  ]
}
```

---

## ETAPA 4: Pré-Compilação Estática e Otimização de Latência ($O(1)$ Lookup)

### 4.1. Diagnóstico Técnico
* Enquanto a importação histórica (`s23-ultra-history`) opera com latência média de $\approx 1.7\text{ ms}$, o dispositivo em tempo real (`android-edge-br`) apresenta picos severos:
  * `d03041ba-2c3f-46b1-aa9e-0f6720340c62`: $\text{processingLatencyMs} = 98.592812\text{ ms}$ (primeira chamada da rajada `03141011230`).
  * `8b7b23ad-687b-452e-a704-161e236c31cb`: $\text{processingLatencyMs} = 65.667813\text{ ms}$.
  * `8ffe783c-da8a-4988-adac-0235dfe47fa3`: $\text{processingLatencyMs} = 58.950573\text{ ms}$.
* O pico de $98.59\text{ ms}$ em `d03041ba` seguido por $\approx 18\text{ ms}$ nas chamadas subsequentes da mesma rajada (`706f9581`, `01a069a1`, `84ec8da5`) comprova que expressões regulares e conexões/queries de quarentena estão a sofrer *cold-start* ou compilação JIT dentro do método `onScreenCall()`.

### 4.2. Especificação de Implementação
1. **Singleton de Expressões Regulares (`LazyStatic` / Kotlin `companion object`):**
   * Proibir qualquer chamada a `Regex(...)` ou `Pattern.compile(...)` dentro do ciclo de avaliação de chamada. Todas as expressões regulares devem ser instanciadas uma única vez na inicialização do processo.
2. **Cache em Memória para Quarentena de Prefixos (`HashSet<String>` / `UInt64` Bitmask):**
   * Como `prefix7` é agora uma string numérica de $8$ dígitos (ex: `"31410112"`), pode ser convertida diretamente num inteiro de $32$ bits (`UInt32`) e armazenada num `HashSet<UInt32>` em RAM sincronizado por `RwLock`.
   * Proibir queries SQLite síncronas (`SELECT ... FROM quarantine WHERE prefix = ?`) no caminho crítico do `CallScreeningService`.
3. **Orçamento de Latência (SLA Invariant):**
   * $\text{P99}(\text{processingLatencyMs}) \le 15.0\text{ ms}$ em `android-edge-br`.

---

## ETAPA 5: Controlo de Concorrência, Idempotência e Máquina de Estados Pós-Chamada

### 5.1. Diagnóstico Técnico
1. **Duplicação e Perda de Estado STIR/SHAKEN em Tempo Real (`android-edge-br`):**
   * Par 1: `de65c16e` (`ts: 1794237222816`, `03137682020`, `stirShakenStatus: "PASSED"`, `score: 85`, `callDurationSec: 0`) $\rightarrow$ $1871\text{ ms}$ depois surge `203688fe` (`ts: 1794237224687`, `03137682020`, `stirShakenStatus: "NOT_VERIFIED"`, `score: 170`, `callDurationSec: null`).
   * Par 2: `177d4560` (`ts: 1794237214541`, `03130711154`, `PASSED`, `score: 155`, `callDurationSec: 0`) $\rightarrow$ $2110\text{ ms}$ depois surge `1371beb3` (`ts: 1794237216651`, `NOT_VERIFIED`, `score: 170`, `callDurationSec: null`).
   * **Causa Raiz:** O `CallScreeningService` interceta a chamada no instante $t_0$ (onde os metadados SIP/IMS fornecem `stirShakenStatus: "PASSED"`), bloqueia a chamada e insere o prefixo em quarentena. Segundos depois ($t_0 + 2\text{s}$), um `ContentObserver` no `CallLog` (ou segundo callback de desconexão) lê o registo do sistema Android — onde o status STIR/SHAKEN não está disponível (`NOT_VERIFIED`) e a duração ainda é `null` — e cria um **segundo registo duplicado** em vez de atualizar o primeiro!
2. **Condição de Corrida no Importador Histórico (`s23-ultra-history`):**
   * Registos com **exatamente o mesmo timestamp** para o mesmo número:
     * `c8b28626` (`score: 100`) e `cf1631a8` (`score: 170`) em `ts: 1790944698888`.
     * `f2659819` e `4b98f6ee` em `ts: 1791555733869`.
     * `48134ef3` e `effade14` em `ts: 1791482495624`.
     * `d340cee7` e `8f9cc607` em `ts: 1791296311481`.
     * `cdc44bd3` e `1b5749de` em `ts: 1791236606936`.
3. **Corrupção Semântica de `outcome: "BLOCKED"` em Chamadas Atendidas e Hiato de Duração:**
   * Registos com `callDurationSec` entre $3\text{s}$ e $8\text{s}$ (`12234343` com $3\text{s}$, `81935f99` com $4\text{s}$, `5d3b74ba` com $6\text{s}$, `b7fe78c8` com $7\text{s}$, `ef753627` com $8\text{s}$) apresentam `outcome: "BLOCKED"`. Uma chamada com duração $> 0\text{s}$ **não foi bloqueada na triagem**; ela tocou, foi atendida pelo utilizador e desligada rapidamente (Ping Call / Robocall mudo).
   * Além disso, existe um **vazio lógico** entre $5\text{s}$ e $11\text{s}$:
     * $1\text{s} \le d \le 4\text{s}$: aciona `PING_CALL_PENALTY` ($+55$).
     * $5\text{s} \le d \le 11\text{s}$: **não aciona nenhuma regra** (ex: `de8f026a` com $5\text{s}$, `5d3b74ba` com $6\text{s}$, `ef753627` com $8\text{s}$).
     * $d \ge 12\text{s}$: aciona `CONVERSATION_ANSWERED_BONUS` ($-40$, ex: `a728646a` com $12\text{s}$).

### 5.2. Especificação de Implementação
1. **Chave de Idempotência e Janela de Coalescência ($\Delta t \le 15000\text{ ms}$):**
   * Antes de inserir um novo evento de telemetria, verificar se existe um registo para o mesmo `normalizedNumber` cujo timestamp satisfaça $|t_{\text{novo}} - t_{\text{existente}}| \le 15000\text{ ms}$.
   * Se existir: realizar `UPSERT` (merge) no registo existente em vez de `INSERT`.
   * **Regra de Preservação de Metadados:** Durante o merge, um `stirShakenStatus` válido (`"PASSED"` ou `"FAILED"`) **nunca** pode ser sobrescrito por `"NOT_VERIFIED"` oriundo do `CallLog`. Da mesma forma, `callDurationSec: 0` não deve ser sobrescrito por `null`.
2. **Execução Sequencial Determinística no Importador de Histórico:**
   * No processamento em lote (`s23-ultra-history`), deduplicar a lista de entradas do `CallLog` pela tupla `(normalizedNumber, timestamp)` antes de iniciar a avaliação e processar de forma estritamente sequencial (ou usar lock por `prefix7`) para evitar leituras sujas da tabela de quarentena.
3. **Máquina de Estados Pós-Chamada e Fechamento do Hiato de Duração:**
   * Se `callDurationSec > 0`: a chamada **nunca** pode ter `outcome = "BLOCKED"`. Caso o score pós-chamada atinja $\ge 70$ (por `PING_CALL_PENALTY` ou quarentena), o `outcome` deve ser `"FLAGGED_POST_CALL_QUARANTINED"` (indicando que a chamada passou, mas o número/prefixo foi colocado em quarentena para chamadas futuras).
   * Redefinir os intervalos contínuos de duração $d$ (em segundos) para chamadas atendidas ($d > 0$):
     * $1 \le d \le 5$: `PING_CALL_PENALTY` (`weight: 55`, `"Chamada muda/curta <= 5s"`).
     * $6 \le d \le 11$: `SHORT_ROBOCALL_PENALTY` (`weight: 30`, `"Desligamento rápido de robocall 6s-11s"`).
     * $d \ge 12$: `CONVERSATION_ANSWERED_BONUS` (`weight: -40`, `"Conversa atendida >= 12s"`).

### 5.3. Contrato JSON de Teste — Etapa 5

```json
{
  "stage": "STAGE_5_CONCURRENCY_AND_POST_CALL_STATE_MACHINE",
  "test_cases": [
    {
      "test_id": "TC_501_COALESCE_SCREENING_AND_CALLLOG_EVENTS",
      "source_log_ids": [
        "177d4560-45b4-4865-8444-0aa7ea5ecbcd",
        "1371beb3-3dec-4cc9-99bc-993f46fcb2e0"
      ],
      "input_event_stream": [
        {
          "source": "CALL_SCREENING_SERVICE",
          "timestamp": 1794237214541,
          "rawNumber": "03130711154",
          "stirShakenStatus": "PASSED",
          "callDurationSec": 0
        },
        {
          "source": "CALL_LOG_OBSERVER",
          "timestamp": 1794237216651,
          "rawNumber": "03130711154",
          "stirShakenStatus": "NOT_VERIFIED",
          "callDurationSec": null
        }
      ],
      "expected_output": {
        "totalRecordsPersisted": 1,
        "persistedRecord": {
          "id": "177d4560-45b4-4865-8444-0aa7ea5ecbcd",
          "timestamp": 1794237214541,
          "normalizedNumber": "+553130711154",
          "stirShakenStatus": "PASSED",
          "callDurationSec": 0,
          "finalScore": 155,
          "outcome": "BLOCKED"
        }
      }
    },
    {
      "test_id": "TC_502_POST_CALL_PING_OUTCOME_AND_DURATION_GAP",
      "source_log_id": "de8f026a-771a-3dc4-9372-bdf237c16f38",
      "input": {
        "rawNumber": "031982996360",
        "normalizedNumber": "+5531982996360",
        "ddd": "31",
        "prefix7": "31982996",
        "carrierGuess": "TIM",
        "lineType": "MOBILE",
        "stirShakenStatus": "NOT_VERIFIED",
        "callDurationSec": 5,
        "thresholdApplied": 70
      },
      "expected_output": {
        "finalScore": 95,
        "outcome": "FLAGGED_POST_CALL_QUARANTINED",
        "category": "PING_CALL_ROBOCALL",
        "triggeredRules": [
          {
            "rule_id": "NOT_IN_CONTACTS",
            "weight": 25
          },
          {
            "rule_id": "STIR_SHAKEN_UNVERIFIED",
            "weight": 15,
            "meta": "Histórico"
          },
          {
            "rule_id": "PING_CALL_PENALTY",
            "weight": 55,
            "meta": "Duração 5s"
          }
        ]
      }
    }
  ]
}
```

---

## ETAPA 6: Integridade Auditável de Feedback do Utilizador e Disparo de Sincronização

### 6.1. Diagnóstico Técnico
* **Corrupção da Equação de Score por Sobrescrita Manual (`finalScore: 100`):**
  * Nos registos `151fe7f3-5eb8-453d-a27c-d6d1a33207fc` (`031996856221`), `95a3df2f-2516-4f3c-abec-e06538594916` (`031991967578`) e `40c66556-ca4c-4b0e-949f-79ea4113ef8e` (`031996259788`), as regras em `triggeredRulesJson` somam apenas $25 + 15 = 40$ pontos (chamadas originalmente permitidas com `ALLOWED_LOW_SCORE`).
  * Quando o utilizador marcou `userFeedback: "CONFIRMED_SPAM"`, o código sobrescreveu diretamente `finalScore = 100` e `outcome = "BLOCKED"`, sem adicionar qualquer regra justificativa ao `triggeredRulesJson`.
  * O mesmo ocorreu em `96854886`, `0e43a524`, `e68988dc` (soma das regras = $85$, mas `finalScore = 100`) e `81935f99` (soma das regras = $95$, mas `finalScore = 100`).
* **Fila de Sincronização Órfã (`syncStatus: "PENDING"`):**
  * Os registos `96854886-39ed-4f46-adb5-d09b06895713`, `0e43a524-3cc3-4b4d-9736-ac557f5ec63b` e `e68988dc-14b7-4f13-ad9c-ede78e65ea32` foram marcados como `CONFIRMED_SPAM` e transitaram para `syncStatus: "PENDING"`, mas nunca foram enviados ao servidor, mesmo havendo dezenas de chamadas posteriores com `syncStatus: "SYNCED"`.

### 6.2. Especificação de Implementação
1. **Regra Auditável de Feedback (`USER_CONFIRMED_SPAM_OVERRIDE`):**
   * Proibir a atribuição direta `record.finalScore = 100`.
   * Quando o utilizador acionar `CONFIRMED_SPAM`:
     * Inserir no array `triggeredRulesJson` a regra `{"rule_id": "USER_CONFIRMED_SPAM_OVERRIDE", "weight": 100, "meta": "Feedback manual do utilizador"}` (caso ainda não exista).
     * Recalcular `finalScore` pela soma real de todas as regras do array.
     * Se a chamada tinha sido originalmente permitida (`ALLOWED_LOW_SCORE`), atualizar `outcome` para `"USER_BLOCKED_RETROACTIVE"` (preservando a distinção entre o que o motor bloqueou sozinho e o que exigiu intervenção humana).
2. **Disparo Reativo do Worker de Sincronização:**
   * No repositório de dados (DAO/Repository), qualquer execução de `updateUserFeedback()` que defina `syncStatus = "PENDING"` deve invocar imediatamente `TelemetrySyncScheduler.enqueueImmediateSync()`.
   * O worker de sincronização periódica deve fazer query por `WHERE syncStatus = 'PENDING'` sem filtrar apenas pelos registos criados na sessão atual.

### 6.3. Contrato JSON de Teste — Etapa 6

```json
{
  "stage": "STAGE_6_USER_FEEDBACK_AND_SYNC_TRIGGER",
  "test_cases": [
    {
      "test_id": "TC_601_AUDIT_TRAIL_ON_CONFIRMED_SPAM",
      "source_log_id": "151fe7f3-5eb8-453d-a27c-d6d1a33207fc",
      "input_initial_record": {
        "id": "151fe7f3-5eb8-453d-a27c-d6d1a33207fc",
        "normalizedNumber": "+5531996856221",
        "finalScore": 40,
        "thresholdApplied": 70,
        "outcome": "ALLOWED_LOW_SCORE",
        "category": "UNKNOWN_UNVERIFIED",
        "triggeredRules": [
          {
            "rule_id": "NOT_IN_CONTACTS",
            "weight": 25
          },
          {
            "rule_id": "STIR_SHAKEN_UNVERIFIED",
            "weight": 15,
            "meta": "VERIFICATION_STATUS_NOT_VERIFIED"
          }
        ],
        "userFeedback": "UNRATED",
        "syncStatus": "SYNCED"
      },
      "user_action": {
        "action": "MARK_AS_CONFIRMED_SPAM"
      },
      "expected_output": {
        "id": "151fe7f3-5eb8-453d-a27c-d6d1a33207fc",
        "finalScore": 140,
        "outcome": "USER_BLOCKED_RETROACTIVE",
        "category": "USER_REPORTED_SPAM",
        "triggeredRules": [
          {
            "rule_id": "NOT_IN_CONTACTS",
            "weight": 25
          },
          {
            "rule_id": "STIR_SHAKEN_UNVERIFIED",
            "weight": 15,
            "meta": "VERIFICATION_STATUS_NOT_VERIFIED"
          },
          {
            "rule_id": "USER_CONFIRMED_SPAM_OVERRIDE",
            "weight": 100,
            "meta": "Feedback manual do utilizador"
          }
        ],
        "userFeedback": "CONFIRMED_SPAM",
        "syncStatusAfterWorker": "SYNCED",
        "syncWorkerEnqueued": true
      }
    }
  ]
}
```

---

## ETAPA 7: Saneamento da Base de Dados Local e Testes de Regressão

### 7.1. Rotina de Migração de Dados (Data Sanitization Script)
O LLM Harness deve criar uma migração de base de dados (Room/SQLite Migration) para corrigir os registos históricos já gravados no dispositivo:

1. **Remoção de Duplicados Exatos e Concorrentes:**
   * Eliminar o segundo registo dos pares duplicados no mesmo milissegundo (`4b98f6ee`, `effade14`, `8f9cc607`, `1b5749de`, `c8b28626`, `940c2a06`) e dos pares de condição de corrida (`203688fe`, `1371beb3`, `409d2321`, e as repetições de `callDurationSec IS NULL` na rajada `1792718165716`–`1792718225739`).
2. **Reconciliação de `triggeredRulesJson` vs `finalScore`:**
   * Para todos os registos onde `userFeedback = 'CONFIRMED_SPAM'` e `finalScore = 100`, anexar a regra `USER_CONFIRMED_SPAM_OVERRIDE` e recalcular `finalScore` como a soma exata dos pesos.
3. **Normalização Retroativa de `prefix7`:**
   * Atualizar todos os registos com `LENGTH(prefix7) = 7` (em números móveis/fixos de $10$ ou $11$ dígitos) para `SUBSTR(REPLACE(normalizedNumber, '+55', ''), 1, 8)`.
4. **Flush de Registos `PENDING`:**
   * Enfileirar imediatamente os registos `96854886`, `0e43a524` e `e68988dc` para sincronização com o backend.

### 7.2. Checklist de Verificação Final (Gate de Saída para o LLM Harness)
O LLM Harness só deve encerrar a tarefa quando todas as seguintes asserções retornarem `true` sobre o conjunto completo de $122$ eventos de `spamshield_telemetry.json`:

* [x] **Invariante 1 (Soma Algébrica):** Para $100\%$ dos registos, $\text{finalScore} == \max\left(0, \sum w_i\right)$.
* [x] **Invariante 2 (Proteção de Fixos Legítimos):** Nenhuma chamada com `stirShakenStatus == "PASSED"`, `carrierGuess == "Fixo"` e sem `PREFIX_QUARANTINE_HIT` possui $\text{finalScore} \ge 70$.
* [x] **Invariante 3 (Idempotência):** Não existem dois registos com o mesmo `normalizedNumber` cuja diferença de `timestamp` seja $\Delta t \le 15000\text{ ms}$.
* [x] **Invariante 4 (Consistência de Prefixo):** Para todo o registo com `lineType IN ("MOBILE", "FIXED_OR_VOIP")`, $\text{length}(\text{prefix7}) == 8$.
* [x] **Invariante 5 (Sincronização):** Nenhum registo permanece com `syncStatus == "PENDING"` após a execução do `TelemetrySyncWorker`.

---

## 8. STATUS DE IMPLEMENTAÇÃO (`v1.3.0` / `v1.3.1`)

Todas as etapas desta Nota Técnica foram implementadas e verificadas por testes unitários automatizados (`testDebugUnitTest`):

1. **Etapa 1 (`PhoneNormalizer.kt`):** Pipeline de descasque para códigos curtos/utilidade (`TOLL_FREE_OR_SPECIAL`, `prefix7 = ""`), extração de CSP (`012`..`099`, `00xx`) e cabeçalhos de país discados como tronco (`055`/`0055`), validação de cardinalidade móvel (`MALFORMED_OR_SPOOFED` $\rightarrow$ `MALFORMED_NUMBER_SPOOFING` `+85`) e inferência de operadora isolada do CSP.
2. **Etapa 2 (`QuarantinePrefixIndex.kt` & `PhoneNormalizer.kt`):** Prefixo canônico de $8$ dígitos (`DDD + 6` primeiros dígitos do assinante) com peso determinístico `+70` (`PREFIX_QUARANTINE_HIT`, `EXACT8`) e fallback explícito de $7$ dígitos com peso `+60` (`PREFIX7_BROAD_QUARANTINE_HIT`, `BROAD7`).
3. **Etapa 3 (`HeuristicScorer.kt`):** Separação estrita entre `Fixo` (`32xx`–`39xx`) e `VoIP`, grupo de exclusão mútua `VOIP_TRUNK_GROUP` (`BLACKLIST_MG_VOIP_PABX`, `REGEX_MG_VOIP_TRUNKS`, `ANATEL_PABX_VOIP_RANGE`) e restrição de `LEADING_ZERO_TRUNK_DIALING` (`+25`).
4. **Etapa 4 (`ScreeningPatternCache.kt` & `QuarantinePrefixIndex.kt`):** Cache estático de expressões regulares compiladas e índice em memória $O(1)$ sincronizado em todas as mutações do DAO/reprocessamento.
5. **Etapa 5 (`SpamScoringEngine.kt`, `CallHistoryAnalyzer.kt` & `HeuristicScorer.kt`):** Coalescência de $15\text{ s}$ com proteção do vínculo `CALL_LOG_OBSERVATION` mais próximo, preservação de `stirShakenStatus` válido e intervalos contínuos de duração (`1..5s` $\rightarrow$ `PING_CALL_PENALTY` `+55`, `6..11s` $\rightarrow$ `SHORT_ROBOCALL_PENALTY` `+30`, `>= 12s` $\rightarrow$ `CONVERSATION_ANSWERED_BONUS` `-40`) com `outcome = "FLAGGED_POST_CALL_QUARANTINED"` para chamadas atendidas com score $\ge 70$.
6. **Etapa 6 (`UserFeedbackAudit.kt` & `SyncRepository.kt`):** Adição auditável de `USER_CONFIRMED_SPAM_OVERRIDE` (`+100`), transição de `ALLOWED_LOW_SCORE` para `USER_BLOCKED_RETROACTIVE` (`category = "USER_REPORTED_SPAM"`) e disparo imediato de sincronização (`TelemetrySyncScheduler.enqueueImmediateSync`).
7. **Etapa 7 (`AppDatabase.kt` `MIGRATION_2_3` / `MIGRATION_3_4`, `TelemetrySanitizer.kt`, `DecisionTrace.kt` & `CallHistoryAnalyzer.kt`):** Saneamento retroativo da base SQLite, deduplicação de registros concorrentes/legados (`calllog-...` e códigos curtos), preservação causal de regras de chegada (`SCREENING_DECISION`) durante reprocessamento e diário de decisões em `call_decision_events` (documentado em [docs/TELEMETRIA-DECISOES.md](docs/TELEMETRIA-DECISOES.md)).
