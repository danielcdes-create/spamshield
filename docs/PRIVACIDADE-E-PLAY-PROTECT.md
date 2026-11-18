# Privacidade e alerta do Play Protect

## O que o alerta informa

A captura enviada em 17/11/2026 mostra o **Google Play Protect** classificando SpamShield BR como app nocivo, com a mensagem correspondente à categoria **spyware**, e informando remoção de permissões. Não é apenas a mensagem de aplicativo desconhecido. A indicação de SMS, fotos e áudio na mensagem é genérica da categoria; ela não prova acesso do SpamShield a cada um desses recursos.

Fontes oficiais consultadas: [mensagens por categoria](https://developers.google.com/android/play-protect/warning-strings), [orientação a desenvolvedores](https://developers.google.com/android/play-protect/warning-dev-guidance).

A imagem não fornece o indicador técnico que provocou a classificação. Revisão de código, assinatura e permissões ajuda a corrigir problemas reais, mas não equivale a uma análise independente de malware nem garante remoção da classificação pelo Google. Não há aparelho físico conectado para verificar o APK instalado ou reproduzir o alerta em One UI 9/Android 17.

## Ajustes da versão 1.3.3

- Aviso de uso dos dados antes de solicitar permissões. A solicitação é iniciada por ação explícita do usuário.
- Conceder acesso ao histórico deixa de importar automaticamente chamadas antigas; a importação passa a ser uma operação separada em Ajustes.
- Envio remoto desativado por padrão, inclusive para instalações atualizadas que ainda não registraram esse consentimento. O bloqueio e o diário local continuam disponíveis.
- Ativação do envio com confirmação destacando servidor, campos enviados e execução em segundo plano. Desativação impede novos envios; não apaga dados previamente recebidos pelo servidor.
- Alterar o endereço do servidor revoga a autorização de envio, exigindo nova confirmação para o novo destino.
- Fila de alterações offline mantida localmente enquanto o envio está desativado. Não se trata de apagar essa fila.
- Acesso ausente a contatos/histórico/telefone aparece na interface, inclusive após revogação por proteção do Android. Papel de bloqueador padrão verificado separadamente.
- Logcat usa ID técnico da chamada e versão; números completos deixam de ser registrados nas mensagens operacionais.
- Build de distribuição em modo release, sem `debuggable`, com assinatura explicitamente configurada. A assinatura existente deve ser preservada para atualização compatível; mudar a chave sem uma migração válida pode impedir a atualização.

## Escopo dos dados

| Acesso/dado | Finalidade | Envio |
| --- | --- | --- |
| Contatos | Consultar se um número já é conhecido e permitir a chamada | A agenda e nomes não são enviados pelo fluxo de telemetria |
| Histórico de ligações | Observar tipo, duração e resultado da chamada; importação antiga por ação do usuário | As chamadas persistidas podem ser enviadas se a sincronização for ativada |
| Número bruto e normalizado, horário, duração, DDD/prefixo | Classificação e rastreio | Incluídos na telemetria remota autorizada e na exportação local |
| Regras, score, decisão e diário de eventos | Explicar a decisão e separar chegada de reavaliação | Incluídos no envio autorizado |
| Estado de telefone | Acionar a inspeção pós-chamada | Não contém áudio da ligação |
| Notificações | Mostrar auditoria de modo sombra | Notificação no aparelho |
| Instalação de pacotes | Abrir o instalador Android para atualização solicitada pelo usuário | APK baixado do servidor configurado, com checksum; o Android exige sua confirmação |

O manifesto e os fontes não solicitam SMS, microfone, câmera, fotos, localização, acessibilidade ou leitura de notificações de outros aplicativos. A revisão da dependência/manifesto final deve acompanhar cada novo build. O app usa bibliotecas Android/Jetpack, Hilt, Room, Kotlin coroutines/serialization, libphonenumber, OkHttp e Retrofit. Isso não constitui garantia de ausência de vulnerabilidades.

O destino é o servidor configurado pelo usuário. HTTPS tem a proteção de transporte correspondente; HTTP só é aceito para endereços privados permitidos pelo app e deve operar dentro do túnel Tailscale. O token da API não é incluído nas exportações. JSON/CSV e seu resumo podem conter dados pessoais das chamadas: compartilhar esses arquivos é uma ação separada e explícita.

## Como tratar o alerta no Samsung

1. Preserve os registros locais antes de qualquer desinstalação. Os backups do Android estão desativados neste app; desinstalar pode apagar o histórico e o diário. A exportação do banco já armazenado não exige reimportar o CallLog.
2. Verifique que o APK corresponde ao build revisado e possui assinatura compatível com o pacote instalado. Não troque o identificador do pacote nem a assinatura para tentar contornar a classificação.
3. Submeta a classificação à revisão do Google após corrigir os comportamentos identificados. O [formulário oficial do Play Protect](https://support.google.com/googleplay/android-developer/contact/protectappeals) solicita pacote, informações de suporte e SHA-256 do APK enviado ao VirusTotal. Upload de um APK privado ao VirusTotal pode compartilhá-lo com terceiros; esta tarefa não fez esse upload nem enviou o recurso.
4. Mantenha o Play Protect ativado. Uma nova versão ou assinatura válida, por si só, não garante que o aviso desapareça. Se a instalação continuar bloqueada, conclua a revisão da classificação antes de tentar forçá-la.
5. Quando o alerta estiver resolvido e o APK atualizado, revise no Android as permissões do SpamShield e a seleção como app de identificação/bloqueio de chamadas. A interface da 1.3.3 mostra os acessos ausentes. Enquanto o sistema tiver removido as permissões ou o papel necessário, a cobertura de bloqueio/aprendizado pode ficar limitada.

O fluxo oficial de recurso e a possibilidade de corrigir uma classificação indevida estão descritos na [orientação do Google](https://developers.google.com/android/play-protect/warning-dev-guidance#appeals). Não foi feita uma alteração de segurança nas configurações do telefone.

## Dados técnicos disponíveis para revisão

- Pacote: `br.com.spamshield`.
- APK anterior local: 1.3.2/build 16, SHA-256 `c9bb354a46ee7a3fbb1077315304a967bc7c9678b0c46fc7466514a1b9ad1b9c`.
- Certificado anterior local SHA-256: `b455a4a76390115a922871714cf0be721b9c08434b10f86cb2c4e9efa60c0aeb`, identificado como `Android Debug`. O aparelho não foi conectado para confirmar que sua instalação usa este mesmo certificado.
- A chave privada não acompanha os artefatos. O build aceita as variáveis `SPAMSHIELD_KEYSTORE_PATH`, `SPAMSHIELD_KEYSTORE_PASSWORD`, `SPAMSHIELD_KEY_ALIAS`, `SPAMSHIELD_KEY_PASSWORD`; valores não são versionados.
- Hash, certificado, permissões e resultado de validação local da 1.3.3 estão em `audit/2026-11-17/release-validation.json`. A publicação na VPS e a revogação da credencial antiga exposta estão em `audit/2026-11-17/deployment-1.3.3.json`, sem valores de credenciais.

A versão 1.3.3 foi publicada em 18/11/2026. A troca do token no servidor exige atualizar o campo Bearer no app; o endereço e a nova chave estão em arquivo privado entregue ao operador, fora do repositório. Use o endereço MagicDNS informado nesse arquivo, mantenha o Tailscale conectado e autorize a sincronização somente depois de salvar os dados corretos.

Referência de assinatura e compatibilidade: [assinar seu app](https://developer.android.com/studio/publish/app-signing).
