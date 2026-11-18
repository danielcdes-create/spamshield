# SpamShield BR 1.3.3 — build 17

Esta versão corrige a aplicação de quarentenas, a sincronização das alterações e a atribuição das versões no diário. Os defeitos foram encontrados na revisão do código da 1.3.2; sua incidência nas chamadas novas do arquivo analisado não foi comprovada.

## Bloqueio e aprendizado

- Índice de quarentena conserva a validade, ignora entradas vencidas, carrega o estado ativo quando necessário e é atualizado por alterações locais/remotas. O refresh evita sobrescrever alterações concorrentes com uma leitura anterior.
- Chamadas abaixo do limiar não criam quarentena pelo detector de duração curta.
- Grace por bloco exige prefixo não vazio: ligar para um código de serviço não libera outros números especiais.
- Regras desativadas, pesos e expressões configuradas são respeitados também pelos caminhos com regras embutidas e pelo reprocessamento.
- A penalidade de cabeçalho com zero exige esse cabeçalho; VoIP em E.164 não recebe a penalidade apenas por ser VoIP.
- Permissões por contato, retorno e repetição usam score zero, respeitando a equação do vetor.

## Histórico e diário

- Chamadas efetuadas ficam fora da avaliação de spam recebido e das rajadas. Quando já poluíram o histórico, a classificação derivada é corrigida preservando as observações antigas.
- Rediscagens próximas sem decisão própria recebem registro histórico independente.
- Eventos novos registram versão nome/código e revisão `decision-journal-v2`. Mudanças de versão/configuração não somem na deduplicação apenas por produzirem o mesmo score.
- JSON conserva o array anterior e acrescenta `provenance` e `exportMetadata`. O CSV expõe as versões em colunas; a auditoria mostra data da chamada e data da gravação separadamente.
- `spamshield_telemetry_summary.json` resume versões representadas, períodos de chamadas originais e períodos de processamento. Falta de decisão original significa versão original desconhecida, inclusive quando a chamada é reprocessada em uma versão nova.

## Sincronização e preservação

- Room 4→5 adiciona fila persistente de mutações e o controle das cópias remotas, preservando tabelas de chamadas e eventos.
- Alterações de quarentena e feedback offline ficam pendentes até confirmação, sem republicar indiscriminadamente cópias recebidas do servidor.
- Exclusões têm tombstones no servidor; regras desativadas chegam no payload. Snapshots antigos/incompletos têm tratamento de compatibilidade.
- Mutex impede execuções concorrentes do sync no mesmo processo. Cada novo envio confere consentimento, destino e credencial capturados.
- Backend faz upsert atômico e mescla por ID de evento; conflitos de conteúdo para o mesmo ID não são confirmados como aceitos. Reenvio de histórico não renova a proteção usando a data do upload.

As garantias de exclusão e confirmação atômica exigem atualizar também o backend. O APK aceita o contrato antigo para permitir a transição, mas não corrige o servidor antigo apenas por ser instalado.

## Privacidade e Play Protect

- Aviso antes de solicitar permissões; conceder histórico não inicia importação de chamadas antigas automaticamente.
- Sincronização remota passa a exigir consentimento explícito em Ajustes. Sem essa autorização, o tratamento é local; alterar o destino exige nova autorização.
- Interface informa acessos ausentes. Logs operacionais não incluem números completos.
- Build release com assinatura configurada externamente e preservação do certificado anterior para compatibilidade com atualização local. A chave anterior é identificada como `Android Debug`: isso continua uma limitação da distribuição existente e não é apresentado como uma assinatura comercial verificada pelo Google.
- O alerta enviado pelo usuário é da categoria spyware do Play Protect. Os ajustes não garantem retirada automática da classificação; ver [orientação de privacidade e revisão](PRIVACIDADE-E-PLAY-PROTECT.md).

## Validação e entrega

Resultados executados, hash e permissões do APK são registrados em `audit/2026-11-17/release-validation.json` e no log de build. O teste de MongoDB requer instância local isolada; não é executado contra produção. A instalação no telefone e a reavaliação pelo Play Protect são etapas distintas do build local.

O APK e o backend desta tarefa são candidatos locais. Nenhum deploy ou publicação é realizado apenas pela compilação. A instalação compatível depende de o certificado no aparelho corresponder ao do APK anterior local verificado; o telefone não estava conectado para essa confirmação.
