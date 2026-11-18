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
