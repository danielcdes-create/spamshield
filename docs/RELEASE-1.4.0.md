# SpamShield BR 1.4.0 · build 20

- **Ligação esperada.** Toque no escudo para liberar temporariamente chamadas com sinais elegíveis. A janela oferece quatro durações e mantém o bloqueio por quarentena exata por padrão. A Auditoria registra a liberação e permite indicar se era a ligação esperada ou spam.
- **Campanhas.** A Auditoria reúne três ou mais números distintos da mesma faixa de oito dígitos em até cinco minutos. Permite silenciar próximas chamadas da faixa por um prazo escolhido e desfazer a ação. Pedidos de bloqueio e bloqueios atribuídos pelo Android têm contagens próprias.
- **Laboratório.** Em **Testar alteração**, compare uma regra com a política original de chegada no histórico ou em sombra. O resultado inclui diferenças de score, ação e evidência insuficiente. **Aplicar alteração** atualiza a regra ativa.
- **Telemetria.** O export conserva a lista de chamadas e acrescenta o diário de recursos ao resumo. A migração Room 5 → 6 mantém os registros existentes. A sincronização confirma cada evento por ID. [Contrato, indicadores e roteiro em aparelho](TELEMETRIA-DECISOES.md)

Os eventos de janela, campanha e laboratório aparecem localmente e no export. O envio desses eventos depende do backend com `POST /api/v1/telemetry/features/batch`. Enquanto o servidor estiver na imagem anterior, eles ficam pendentes no aparelho e a sincronização de chamadas continua.

As verificações de chamadas e latência são automatizadas. Toque, silenciamento e comportamento do CallLog ainda precisam de teste em aparelho.

## Publicação em 19/11/2026

O commit `30188bc8e700ae6b6fbb6f3a55f534e0b7fe4d2a` está na `main` e na tag `v1.4.0`. O APK assinado está disponível na [release do GitHub](https://github.com/danielcdes-create/spamshield/releases/tag/v1.4.0) e no canal OTA da VPS.

Os downloads do GitHub e do OTA têm 4.031.820 bytes e SHA-256 `e370ca422d37b2890e1cb0f5f04af7a471d4c4a8a12f19b1156b9dee7663df6c`. O certificado de assinatura coincide com o da 1.3.5. A VPS continuou usando a imagem do backend 1.3.5 e confirmou a conexão com o MongoDB após a troca do APK. A configuração OTA anterior foi preservada em backup privado.

O build 20 passou nos 123 testes JVM, em `lintDebug` e em `assembleRelease`. O código Rust da tag passou em 28 testes, incluindo as integrações com MongoDB local isolado.
