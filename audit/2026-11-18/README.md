# Regressões de sincronização e inicialização

O HTTP 409 foi reproduzido pela diferença de representação entre eventos schema 1 originais e o transporte tipado da 1.3.3. A correção do backend normaliza apenas os defaults conhecidos para comparação, sem reescrever o diário. O cliente 1.3.4 envia o JSON armazenado sem materializar defaults.

A regressão de abertura foi localizada no uso de preferências iniciais falsas antes da leitura do DataStore. O estado não carregado agora é explícito. Os testes verificam consentimento existente, primeira instalação e retomada; não simulam janelas físicas do Samsung.

Resultados: 90 testes Android e 18 testes Rust aprovados. Duas integrações MongoDB seguem ignoradas; não foram executadas contra produção. A implantação confirma saúde e protocolo, sem gravar chamadas sintéticas na base operacional.

Nenhuma telemetria pessoal, credencial ou chave privada é publicada nestes arquivos.

## HTTP 413 após a atualização

`payload-limit-before.json` registra a fronteira medida na VPS antes do hotfix: 2 MiB aceitos e 2 MiB + 1 byte recusados. Foram enviados somente arrays vazios com espaços, sem chamadas nem eventos. O lote que falhou no telefone não foi coletado; o teste comprova o limite do servidor.

O hotfix limita somente a rota de lote de telemetria a 16 MiB, mantendo autenticação anterior à extração do corpo e os limites das outras rotas. `rust-tests-payload-limit.log` registra 23 testes aprovados e duas integrações ignoradas, incluindo cinco regressões de tamanho, autenticação e isolamento da configuração. O APK permanece na versão 1.3.4/build 18.

`payload-limit-build.json` identifica o binário Linux e seu commit de origem. `payload-limit-deployment.json` confirma a implantação, saúde, hash do APK preservado e os mesmos testes de fronteira na VPS. As sondagens de sucesso enviaram somente `[]` preenchido com espaços, sem persistência de chamadas. A sonda sem autenticação declarou um corpo grande, mas recebeu 401 antes de enviá-lo. O lote real do telefone não foi reenviado por esta validação.
