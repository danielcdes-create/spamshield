# Regressões de sincronização e inicialização

O HTTP 409 foi reproduzido pela diferença de representação entre eventos schema 1 originais e o transporte tipado da 1.3.3. A correção do backend normaliza apenas os defaults conhecidos para comparação, sem reescrever o diário. O cliente 1.3.4 envia o JSON armazenado sem materializar defaults.

A regressão de abertura foi localizada no uso de preferências iniciais falsas antes da leitura do DataStore. O estado não carregado agora é explícito. Os testes verificam consentimento existente, primeira instalação e retomada; não simulam janelas físicas do Samsung.

Resultados: 90 testes Android e 18 testes Rust aprovados. Duas integrações MongoDB seguem ignoradas; não foram executadas contra produção. A implantação confirma saúde e protocolo, sem gravar chamadas sintéticas na base operacional.

Nenhuma telemetria pessoal, credencial ou chave privada é publicada nestes arquivos.
