# Validação da versão 1.3.3

Evidências públicas desta revisão: 82 testes JVM e 14 testes Rust passaram, sem falhas; duas integrações MongoDB não foram executadas. A verificação SQLite executou o SQL da migração 4→5 com fixtures sintéticos e preservou as seis tabelas anteriores. O runner Android Room e o telefone físico não foram exercitados.

O APK release build 17 não é depurável e usa o certificado do APK anterior local, para compatibilidade de atualização. Seu hash é `a87c4ced66bfe0e4036b8694741431e4e8535779dc881d03f434ff9491374c32`. A assinatura no aparelho não foi conferida porque não havia dispositivo conectado.

O binário Linux foi compilado no Windows com `cargo zigbuild --release --locked --target x86_64-unknown-linux-musl`. O manifesto do build registra hash, tamanho e verificação de ELF estático.

Os scripts permitem reproduzir a análise de uma exportação fornecida pelo operador e a validação SQLite. Dados individuais de chamadas, IDs e horários detalhados da revisão permanecem apenas localmente e estão excluídos do Git. A exportação original não foi copiada para o repositório.

`release-validation.json` registra a validação local anterior à publicação; `deployment-1.3.3.json`, quando presente, registra as verificações posteriores na VPS. Nenhuma credencial faz parte destas evidências. A chave de API antiga encontrada na documentação pública foi redigida; sua revogação e a configuração privada atual são registradas apenas como estados, sem valores.
