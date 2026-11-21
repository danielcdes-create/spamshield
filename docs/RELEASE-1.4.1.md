# SpamShield BR 1.4.1 · build 21

- **Capa enxuta no Dashboard.** Removido o banner de campanha da tela inicial, preservando o foco no estado do escudo, sincronização e métricas do dia.
- **Indicador visual de proteção pausada.** Ao ativar a janela de ligação esperada (ou o modo sombra), o escudo, o contorno do card principal e o subtítulo de estado mudam para vermelho, retornando ao azul automaticamente ao restaurar ou expirar a janela.
- **Lista cronológica unificada na Auditoria.** Removida a seção separada de campanhas do topo da Auditoria. Campanhas são exibidas diretamente na lista ordenada pela data da chamada mais recente (`lastCallAt`), consolidando suas tentativas e expandindo os detalhes, controles de silenciamento e chamadas individuais apenas ao tocar no card.

## Publicação em 21/11/2026

O commit `9549b431844590a5d1eb66b59439756955fc1291` está na `main` e na tag `v1.4.1`. O APK assinado está disponível na [release do GitHub](https://github.com/danielcdes-create/spamshield/releases/tag/v1.4.1) e no canal OTA da VPS.

Os downloads do GitHub e do OTA têm 4.031.820 bytes e SHA-256 `6e76f890dbe7bcc378241c819fd2e1fd6b4a4d054ccc103a4eb17280b19679e7`. O certificado de assinatura coincide com o da 1.4.0 (`b455a4a76390115a922871714cf0be721b9c08434b10f86cb2c4e9efa60c0aeb`). A VPS confirmou saúde `ok`, conexão ativa com o MongoDB e entrega íntegra em `/apk`, com backup privado da configuração OTA anterior em `/opt/spamshield/backups-apk-v1.4.1-20261121T103615Z`.

O build 21 passou nos 126 testes JVM, em `lintDebug`, `lintVitalRelease` e `assembleRelease`.
