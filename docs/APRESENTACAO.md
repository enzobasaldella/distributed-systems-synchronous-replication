# Roteiro da apresentação — aproximadamente 20 minutos

## 1. Objetivo e arquitetura — 2 minutos

Mostrar o diagrama do `README.md` e destacar que cada elemento é um processo Java
independente. Toda comunicação entre caixas é uma linha UTF-8 enviada por socket TCP.

## 2. Caminho de uma requisição — 4 minutos

1. O cliente sorteia `1..1000`, grava o número em seu log e envia `CHECK`.
2. O balanceador escolhe uma aplicação por Round Robin.
3. A aplicação cria uma worker, calcula primeiro a primalidade e sorteia um servidor de
   dados.
4. O líder dos dados verifica a existência e devolve `INSERTED` ou `EXISTS` com a linha.
5. A worker imprime a frase exigida e termina.

Arquivos sugeridos para mostrar no código:

- `Client.java`: geração, log e confirmação;
- `LoadBalancer.java`: contador atômico e escolha circular;
- `ApplicationServer.java`: criação da worker, primalidade e escolha aleatória;
- `LeaderRepository.java`: verificação e região crítica.

## 3. Política de balanceamento — 3 minutos

**Escolha:** Round Robin com contador atômico.

**Justificativa:** as requisições têm custo semelhante e os três servidores de aplicação
têm a mesma capacidade. Portanto, uma distribuição uniforme, determinística e de custo
constante é mais adequada do que manter métricas adicionais. O contador atômico evita
corridas entre threads do balanceador. O failover circular impede perda se uma aplicação
estiver momentaneamente indisponível.

Ponto observável na demonstração: o log do balanceador alterna entre as portas 6001,
6002 e 6003.

## 4. Exclusão mútua e consistência — 5 minutos

**Escolha:** primário/backup com replicação síncrona e coordenador centralizado.

O servidor 1 é a autoridade da ordem das linhas. O servidor 2 atende a escolha aleatória
das aplicações, mas encaminha a operação para o líder. Isso cria um único ponto de
serialização para escritas e impede que dois números recebam a mesma linha.

O líder usa um `ReentrantReadWriteLock(true)`:

- leituras de números existentes podem ocorrer em paralelo;
- um número aparentemente novo troca para o lock de escrita e é verificado outra vez;
- gravação local, `fsync`, replicação e ACK remoto acontecem dentro do lock exclusivo;
- a opção justa impede que novas leituras ultrapassem uma escrita já aguardando.

A confirmação síncrona é simples de demonstrar e garante que uma resposta de inserção
nunca seja emitida com os arquivos divergentes. Se a réplica estiver fora de ordem, o
líder manda um snapshot completo. O mesmo snapshot é obrigatório na inicialização.

**Trade-off:** o líder é ponto único de disponibilidade e a escrita depende do seguidor.
Isso foi aceito porque o enunciado prioriza consistência forte e não exige eleição de
líder ou tolerância à queda permanente. Enquanto o seguidor estiver fora, requisições
ficam bloqueadas em vez de serem perdidas.

## 5. Demonstração — 4 minutos

Começar com arquivos vazios:

```bash
scripts/reset-data.sh
scripts/run-all.sh
```

Em terminais separados:

```bash
tail -f runtime/logs/balanceador.log
tail -f runtime/logs/aplicacao-1.log
tail -f runtime/logs/dados-1.log
```

Deixar rodar por 15–30 segundos e então executar:

```bash
scripts/stop-all.sh
scripts/verify-consistency.sh
```

Também mostrar:

```bash
cmp data/servidor-dados-1.txt data/servidor-dados-2.txt
sort -n -u data/cliente-1.log data/cliente-2.log | wc -l
wc -l data/servidor-dados-1.txt
```

## 6. Testes e fechamento — 2 minutos

`scripts/test.sh` testa o algoritmo de primalidade para todo o domínio `1..1000`, a
codificação da requisição e a leitura de endereços. O verificador final é o teste de
integração dos invariantes importantes.

### Perguntas prováveis

**Por que não gravar diretamente nos dois servidores?**

Dois escritores independentes precisariam concordar sobre ordem e número de linha. O
líder fornece essa ordem de maneira direta e verificável.

**A escolha do servidor de dados continua aleatória?**

Sim. Cada worker sorteia um dos dois endpoints. O seguidor encaminha ao líder para
preservar a ordem global.

**O que ocorre se duas workers recebem o mesmo número?**

Ambas podem não encontrá-lo inicialmente. No lock exclusivo há uma segunda verificação:
a primeira insere e a segunda recebe a linha existente.

**O que ocorre durante a consistência?**

O lock exclusivo só é liberado após o ACK. Novas operações ficam na fila justa do lock.

**Por que o cliente grava antes de enviar?**

Assim o log representa todos os valores gerados. O envio usa confirmação e repetição,
ligando cada linha do log a uma operação que será mantida até ser aceita.
