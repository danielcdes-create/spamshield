# SpamShield BR 1.3.4 — build 18

Corrige duas regressões relatadas após a instalação da 1.3.3: conflito HTTP 409 no reenvio do diário e aviso de privacidade que aparecia e sumia na abertura.

## Sincronização do diário

A 1.3.3 desserializava detalhes antigos com o modelo novo antes de enviá-los. Isso acrescentava versões nulas e `evaluatedRules.enabled=true` a eventos schema 1, sem mudar seus IDs. A comparação literal introduzida no backend interpretava essas adições como alteração de evidência e devolvia 409.

O backend agora aceita somente esses três defaults conhecidos para schema 1, comparando cópias. Horários, fatos, fingerprint, versões explícitas, regras desativadas e campos desconhecidos continuam protegidos contra alteração. O array já armazenado permanece intacto; a mescla acrescenta somente IDs inéditos e confirma apenas variantes equivalentes persistidas. A correção do servidor também atende à 1.3.3 já instalada.

O APK 1.3.4 transmite `detailsJson` como JSON bruto. Não injeta defaults nem descarta campos desconhecidos dos eventos antigos. JSON inválido permanece pendente e gera falha, em vez de ser convertido em um evento vazio.

## Inicialização e permissões

O estado inicial das preferências é explicitamente desconhecido até a primeira leitura do DataStore. A interface aguarda essa leitura antes de criar avisos, banners e launchers. Um consentimento já salvo deixa de passar temporariamente por um estado falso na abertura.

Recusar o papel de bloqueador não abre automaticamente outra janela de configurações. O botão de ativação continua disponível, assim como os pedidos de acesso iniciados pelo usuário.

## Validação

- 90 testes Android passaram, incluindo transporte fiel de eventos, campos desconhecidos, JSON inválido e leitura atrasada das preferências.
- 18 testes Rust passaram; duas integrações MongoDB permanecem ignoradas.
- APK release, sem depuração e com o mesmo certificado da 1.3.3, permitindo atualização compatível sem desinstalação.
- A chave Bearer e o endereço MagicDNS atuais permanecem os mesmos. Não é necessário cadastrar outra chave por causa desta atualização.

O comportamento das janelas no Samsung físico e a classificação do Play Protect não foram verificados no aparelho. Evidências de build e implantação ficam em `audit/2026-11-18/`.

## Publicação em 18/11/2026

O hotfix de compatibilidade do backend foi implantado a partir do commit `2ec4f505db8f1f0ef3ecdd543d9905e2a8391245`; o APK 1.3.4 foi preparado a partir de `ad9a8370d86a0586e1934d68c6a1b9ab042320e2`. O canal OTA passou a informar versão 1.3.4/build 18, com saúde `ok`, MongoDB conectado e hash do download real conferido: `29bf8643ac4cab9034ace52248f06bb48ba15b769e00db3ec00b6e9bd0bdda4a`.

O backend e os APKs anteriores permanecem disponíveis nos backups privados da VPS. A troca manteve a credencial atual e a porta limitada ao loopback, com acesso privado via Tailscale. Não foram apagados registros do telefone nem gravadas chamadas sintéticas na base operacional.

## Hotfix de tamanho dos lotes — HTTP 413

Após a instalação da 1.3.4, foi relatado HTTP 413 na sincronização. A rota de telemetria ainda usava o limite padrão de 2 MiB do Axum. Na VPS, um lote vazio com espaços até exatamente 2.097.152 bytes foi aceito; um byte a mais foi recusado. O diário completo pode fazer um lote de até 100 chamadas ultrapassar esse limite. O tamanho do lote que falhou no telefone não foi coletado.

Apenas `POST /api/v1/telemetry/batch` passa a aceitar até 16 MiB (16.777.216 bytes). O limite continua obrigatório, a autenticação antecede a leitura do corpo e as demais rotas conservam seus limites. Uma recusa por tamanho retorna `TELEMETRY_BATCH_TOO_LARGE` e `max_request_bytes`. Os logs informam o `Content-Length` declarado, quando presente, o status HTTP e o limite na recusa, sem registrar o conteúdo do lote.

Os cinco novos testes exercitam o router real: lote acima do limite anterior, fronteira de 16 MiB, recusa um byte acima, autenticação e limite de outra rota. Resultado: 23 testes Rust aprovados e duas integrações MongoDB ignoradas. O hotfix é exclusivo do servidor e atende ao APK 1.3.4 já instalado.
