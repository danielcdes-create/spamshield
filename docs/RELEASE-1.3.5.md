# SpamShield BR 1.3.5 · build 19

A versão corrige a política de quarentena, a resiliência da sincronização e a atribuição de bloqueios na telemetria.

## Triagem e quarentena

- A triagem envia uma resposta por chamada. Após um orçamento de 3,5 segundos, a contingência permite a chamada e a avaliação pendente é cancelada. O diário é gravado após o envio ao Android.
- Modo sombra e simulação preservam a proteção ativa. A simulação indica a ação, a pontuação e o limiar na própria tela.
- Uma quarentena automática tem validade limitada pela data da evidência. A importação ignora proteção vencida. O reprocessamento atualiza classificações e preserva as decisões de chegada.
- O servidor recebe quarentenas pelo endpoint dedicado. A carga do catálogo preserva regras existentes, ajustes manuais e remoções. Quarentenas legadas com origem ambígua conservam a validade já registrada.

## Auditoria e sincronização

- O painel e a API atribuem bloqueios ao SpamShield pela autoria explícita no CallLog. Pedidos de bloqueio, triagens registradas e recusas no discador têm contagens próprias.
- JSON, CSV e resumo usam o mesmo snapshot do banco, com estado local de sincronização por evento.
- Um conflito de diário deixa a chamada correspondente intacta no servidor. Os demais registros do lote podem ser confirmados. O cliente isola chamadas recusadas por HTTP 409 e 413 e conserva seu diário pendente.
- O servidor valida presença do APK e metadados OTA. Com um manifesto de release vinculado ao APK, confere tamanho e SHA-256.

Atualize servidor e APK para aplicar a nova política nos dois lados. Consulte o [contrato de telemetria](TELEMETRIA-DECISOES.md).

## Validação

- 112 testes JVM Android e lint Android aprovados.
- 24 testes Rust aprovados. As duas integrações MongoDB ficaram ignoradas.
- Os testes de triagem e de reconciliação foram executados localmente. A validação em aparelho físico permanece pendente.

## Publicação em 19/11/2026

O commit de código `fce50a33b600af68e92af27cc95c362556f203b9` foi publicado na `main` e marcado com a tag `v1.3.5`. O mesmo APK assinado está na [release do GitHub](https://github.com/danielcdes-create/spamshield/releases/tag/v1.3.5) e no canal OTA. O certificado de assinatura coincide com o da versão 1.3.4.

O APK servido pelos dois canais tem 3.757.088 bytes e SHA-256 `e28202351f11ecc4de38c93a67c901374e906cf5ff760dc9c2ffb74114badaee`. O servidor validou o manifesto pareado com o APK. Depois da implantação do backend, a verificação de saúde confirmou conexão ao MongoDB, e a rota de telemetria respondeu a um lote vazio autenticado com os campos de confirmação e conflito. A imagem, a configuração e o APK anteriores foram preservados em backup privado na VPS.
