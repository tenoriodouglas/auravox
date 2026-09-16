# AuraVox

Karaokê para Android. Importe qualquer música do aparelho: o app extrai a
melodia, descobre o tom e o andamento, tira o vocal original e pontua sua
afinação nota por nota — com a voz processada em tempo real por uma cadeia
nativa em C++.

## O que ele faz que um processador vocal não faz

| | |
|---|---|
| **Guia de melodia automático** | Nenhum arquivo extra. O app roda YIN sobre a música importada, segmenta em notas e monta o piano roll que você acompanha cantando. |
| **Pontuação por afinação** | Crédito total dentro de 50 cents, caindo a zero num semitom. Nota longa pesa mais que nota curta. Erro de oitava vale crédito parcial: você achou a nota, errou o registro. |
| **Latência descontada na pontuação** | O que você canta agora responde ao que ouviu um round trip atrás. Sem essa correção, quem canta certo é marcado como atrasado. |
| **Gravação alinhada** | A base entra na gravação atrasada pela latência medida, então a voz cai onde você quis colocá-la. Gravar a mixagem do monitor é o motivo de take caseiro voltar com o vocal arrastado. |
| **Tom e andamento independentes** | WSOLA mais resampler. ±12 semitons sem mudar a velocidade, 50% a 150% de andamento sem mudar o tom. |
| **Remoção de vocal por banda** | Cancela o centro só na faixa da voz. Grave e pratos ficam inteiros — L−R puro é o que deixa karaokê caseiro com som oco. |
| **Letra sincronizada** | LRC simples e estendido. Com marcação por palavra o destaque anda pela linha em vez de pular. |
| **Contagem sample-accurate** | O metrônomo é gerado na thread de áudio, então a base entra exatamente no tempo forte. |
| **Camadas por bounce** | Cada passada grava a mixagem completa, e a camada seguinte canta por cima desse arquivo. Empilhar sai de graça: não importa se são duas vozes ou dez, o custo é sempre uma faixa. |
| **Loop A–B com andamento** | Marque o trecho, baixe o andamento e repita até acertar. Nenhum processador vocal tem motivo para ter isso; um app de karaokê tem. |
| **Vocoder e talkbox** | A voz vira filtro. A portadora é um sintetizador afinado na nota cantada ou a própria base — aí a música fala a letra. |
| **Ouvir, exportar e compartilhar** | Toca no próprio app, salva em Música/AuraVox e compartilha por FileProvider. |
| **Escolha do microfone** | Inclusive o de fone Bluetooth, que o Android não entrega enquanto a captura não entrar em modo de comunicação. |
| **Atualização automática** | Lê o manifesto publicado pela CI e instala a versão nova sem passar por loja. |

## Baixar e instalar

[**auravox-latest.apk**](https://github.com/tenoriodouglas/auravox/releases/download/apk-latest/auravox-latest.apk) — arm64 e x86_64.

O link é fixo e sempre serve a última build da `main`. A CI republica a mesma
tag a cada commit, então nem o repositório nem a lista de releases cresce — e
**o app se atualiza sozinho**: ele lê o `latest.json` publicado ao lado do APK,
e quando o `versionCode` de lá é maior que o instalado, oferece a atualização
na biblioteca. Basta tocar em Atualizar; o Android pede a autorização de
instalação na primeira vez.

Build de debug assinado com a chave padrão do SDK: instala em qualquer
aparelho e continua compatível com builds feitos na sua máquina. Para publicar
na Play Store é preciso gerar uma chave própria e rodar `./gradlew
assembleRelease` com ela.

**Use fone com fio.** No alto-falante o microfone capta a própria base e o
monitoramento vira microfonia.

## Como rodar

1. Android Studio → Open → esta pasta
2. Instale o NDK 27 e o CMake 3.22 pelo SDK Manager
3. Sync e Run em **aparelho físico** com **fone com fio**

O emulador não tem caminho de áudio de baixa latência: o app abre, mas o
resultado não diz nada sobre o desempenho real.

## Arquitetura

```
Compose ──setParam(id, valor)──▶ JNI ──▶ ParamStore (std::atomic[])
        ◀──nível, pitch, nota, score, posição──          │
                                                ┌────────┴────────┐
                                                ▼                 ▼
  Oboe input ──▶ callback do output ──▶ TrackPlayer ──▶ FxChain
  (VoicePerformance)         │        (WSOLA + resampler)  (voz estéreo,
                             │              │               vocoder usa a
                             │              │               base como portadora)
                             │              └────▶ Mixer ◀────┘
                             │                    │     │
                             │              monitor      take (base atrasada)
                             │                                │
                             └──▶ ScoreTracker          ring ──▶ thread de WAV

  MediaCodec (thread do decoder) ──▶ ring SPSC ──▶ TrackPlayer
```

Só o stream de saída tem callback. Ele puxa o que a entrada tiver pronto com
timeout zero. Dois callbacks e uma FIFO no meio custariam mais um buffer de
latência.

| Arquivo | Papel |
|---|---|
| `cpp/AudioEngine.cpp` | Duplex, priming, reconexão, medição de latência |
| `cpp/Params.h` | Ids de parâmetro e o store lock-free |
| `cpp/dsp/FxChain.h` | Ordem da cadeia vocal, mono in, estéreo out |
| `cpp/dsp/PsolaShifter.h` | PSOLA com relógios de análise e síntese fracionários |
| `cpp/dsp/VoiceProcessor.h` | Snap de escala, autotune, três harmonias diatônicas |
| `cpp/dsp/PitchDetector.h` | YIN com decimação 2x e correção de oitava |
| `cpp/dsp/TimeScale.h` | WSOLA mais resampler cúbico: tom e andamento da base |
| `cpp/dsp/VocalRemover.h` | Cancelamento de centro limitado à banda da voz |
| `cpp/dsp/Mixer.h` | Monitor, take alinhado, ducking, limiter |
| `cpp/dsp/Metronome.h` | Contagem que entrega o downbeat exato |
| `cpp/dsp/Vocoder.h` | Banco de 16 bandas, portadora de síntese ou da base |
| `cpp/dsp/InputFifo.h` | FIFO de captura e a política de escorva |
| `cpp/dsp/Spatial.h` | Reverb estéreo, delay ping-pong, doubler |
| `cpp/dsp/Dynamics.h` | Gate, compressor, de-esser, limiter, EQ, ducker |
| `cpp/track/TrackPlayer.h` | Playhead, ring do decoder, ganho, remoção de vocal |
| `cpp/karaoke/ScoreTracker.h` | Pontuação contra a melodia, combo, notas |
| `cpp/karaoke/Analysis.h` | Melodia, tom e BPM na importação |
| `kotlin/audio/PcmSource.kt` | MediaCodec para float PCM |
| `kotlin/audio/TrackDecoder.kt` | Streaming para o ring, com backpressure |
| `kotlin/audio/SongAnalyzer.kt` | Decodifica, reduz para 11 kHz mono, chama a análise |
| `kotlin/karaoke/Lrc.kt` | LRC simples e estendido |
| `kotlin/audio/AudioDevices.kt` | Escolha de microfone e roteamento Bluetooth |
| `kotlin/karaoke/Exporter.kt` | FileProvider e MediaStore |
| `kotlin/update/GithubUpdater.kt` | Atualização a partir da release rolante |
| `kotlin/ui/widget/PitchLane.kt` | O piano roll rolando |

## Por que a captura não pode ser lida direto no callback

Entrada e saída rodam no mesmo clock de amostragem, mas em threads diferentes.
O que oscila entre um callback e outro é a fase, não a taxa. Ler o stream de
entrada dentro do callback de saída significa que, toda vez que o callback
chega um fio antes da rajada de captura terminar, a leitura vem curta — e
completar o resto com zero emenda silêncio no meio da voz, várias vezes por
segundo. Isso é o chiado.

A captura é drenada para um FIFO e o bloco é servido de lá, com uma almofada de
folga à frente do consumidor. Um bloco é inteiro ou é recusado; nunca meio. A
almofada começa em uma rajada e **cresce sozinha** quando o aparelho prova que
precisa de mais, até quatro rajadas — quanto os dois relógios se afastam entre
callbacks é característica do aparelho, não algo que se adivinhe em tempo de
compilação. O buffer de saída segue a mesma ideia: cresce uma rajada a cada
falha em vez de estalar a música inteira.

O teste `Continuidade da captura` mede isso com um contador como sinal: cada
amostra tem que ser exatamente a anterior mais um. O caminho antigo dá 163
cortes em 2000 blocos; com o FIFO, zero.

## Camadas sem custo

Gravar a segunda voz não guarda a primeira em memória nem monta um mixer
offline. Cada passada escreve dois arquivos:

- **mix** — tudo que estava tocando mais a voz nova
- **voz** — só a voz processada, para ouvir separada ou aproveitar depois

A camada seguinte simplesmente toca o **mix** da anterior como base. O custo de
empilhar é sempre o de uma faixa, e desfazer é voltar um arquivo — nada é
destruído até o take inteiro ser apagado.

O detalhe que faz funcionar: o take carrega a base já atrasada pela latência
medida, então o primeiro quadro do arquivo pertence a um instante da música
**anterior** ao playhead que o gravou. Esse deslocamento é guardado por camada
e reaplicado na reprodução — sem ele a letra e o piano roll da segunda camada
correm adiantados por um round trip inteiro.

Uma consequência: **o andamento trava** quando existem camadas. Esticar a base
esticaria junto a voz já gravada, e a versão disso que alguém iria querer não
existe. Mudar o tom continua liberado — ele move a mixagem inteira de uma vez.

## Microfone Bluetooth

O Android não entrega o microfone de um fone Bluetooth a um app só porque o
fone está conectado. A captura precisa entrar em modo de comunicação (SCO), e
esse caminho **não tem versão de baixa latência** — pedir uma faz o sistema
devolver o microfone do próprio celular, que é exatamente o que parece de fora.

Em Mixer → Ajustes dá para escolher a entrada. Ao escolher um Bluetooth o app
liga o SCO, espera o enlace subir e reabre o áudio sem exigir o caminho rápido.
Duas consequências, que ele avisa na hora:

- o monitor é desligado sozinho — 150 a 300 ms de ida e volta torna impossível
  cantar se ouvindo
- o SCO é bidirecional, então a música também passa a sair em banda estreita
  pelo enlace enquanto o microfone estiver em uso

Para cantar de verdade, fone com fio. O Bluetooth está ali para quem não tem
outra opção.

## Correção guiada pela melodia

Travar na escala só leva a voz para a nota mais próxima dela — no cromático,
no máximo cinquenta cents, e em qualquer escala ainda pode ser a nota errada do
acorde. Como o app já extraiu a melodia da música, ele sabe **qual nota a
pessoa estava tentando alcançar** naquele instante, e corrige para essa.

A nota do guia é dobrada para a oitava em que a pessoa está cantando, então um
barítono cantando a melodia uma oitava abaixo é corrigido dentro da própria
oitava em vez de ser arrastado para cima. Passando de três semitons de
distância, quem está cantando não está naquela nota: puxar seria pior do que
deixar quieto, e a escala reassume.

Liga em Mixer → Voz → Corrigir pela melodia, e vem ligado nos presets
**Karaokê** e **Perfeito**.

## Como o PSOLA funciona aqui

Dois relógios independentes:

- marcas de **análise** andam um período de pitch por vez pela entrada
- marcas de **síntese** andam `período / razão` pela saída

Cada marca de síntese copia o grão da marca de análise mais recente e soma com
sobreposição na posição de síntese. Esse deslocamento no tempo é o que muda o
pitch. Como o grão nunca é reamostrado, o envelope espectral fica intacto — por
isso o formante se preserva sozinho, e por isso ele precisa de um controle
próprio para ser alterado de propósito.

Três detalhes que quebram tudo se forem ignorados:

1. **Tudo tem que ser fracionário: período, espaçamento, posição do grão e
   fase da janela.** Arredondar qualquer um deles deixa o fundamental original
   sem cancelar. Com o período inteiro, uma oitava acima volta com o som
   original só 5 dB abaixo da nota nova.
2. **O tamanho do grão segue o espaçamento de síntese, não o período de
   análise.** Dimensionar pelo período empilha quatro cópias do mesmo grão ao
   subir o tom, e os harmônicos ímpares se cancelam por pente.
3. **Ler e escrever o grão na mesma posição não é PSOLA.** Sem deslocamento
   entre análise e síntese, o resultado é modulação de amplitude.

## Testes

```bash
tools/run_tests.sh
```

Roda no host, sem Android: `dsp/`, `track/` e `karaoke/` não dependem de nada
além da biblioteca padrão. 79 checagens, entre elas:

```
PSOLA pitch shift          -12 a +12 semitons, erro 0.0 cents
TimeScale unity            transparente, SNR 142.7 dB
TimeScale tom              ±7 semitons, erro abaixo de 1 cent
Remoção de vocal           voz central -32 dB, grave -0.2 dB
Alinhamento da gravação    base e voz no mesmo frame no take
Pontuação                  60 ms de latência não custa ponto
Análise                    frase em dó maior: 7/7 notas, tom e 120 BPM
Vocoder                    saída canta a portadora, 46 dB acima da voz
Continuidade da captura    0 cortes com FIFO contra 163 sem
Monitor desligado          fone em silêncio, take com a voz inteira
Pior caso                  3.2% de tempo real em x86
```

No GitHub esses testes rodam antes do APK: se o DSP quebrou, não existe build
para baixar.

Em ARM conte com 5 a 8x isso — ainda folgado.

## Latência

| Fonte | Custo |
|---|---|
| Buffers Oboe (entrada + saída) | 10–25 ms, depende do aparelho |
| Lookahead do PSOLA | 12 ms, só quando autotune/formante/harmonia estão ativos |
| Hardware do fone com fio | 1–3 ms |

Com autotune desligado o caminho do PSOLA é puro dry e os 12 ms somem.
Bluetooth adiciona 150–300 ms e torna o monitoramento inútil — não tem ajuste
de software que resolva.

Três posições de tempo circulam pelo app, e confundi-las é o que desalinha
karaokê:

- **playhead** — o que o mixer está escrevendo agora
- **ouvido** = playhead − latência de saída — o que sai do fone neste instante;
  é contra ele que a letra e o piano roll são desenhados
- **cantado** = playhead − round trip − lookahead − ajuste fino — o trecho a
  que a voz que acabou de chegar estava respondendo; é contra ele que a
  pontuação conta e é por ele que a base entra atrasada na gravação

O ajuste fino fica em Mixer → Ajustes, e mexe nos dois de uma vez.

## Regras da thread de áudio

Quebrar qualquer uma gera estalo:

- Sem alocação, sem `new`, sem `std::string`
- Sem mutex e sem I/O
- Sem log
- Parâmetros entram por `std::atomic` e são aplicados uma vez por bloco
- Denormais em flush (`enableFlushDenormals`), senão a cauda do reverb custa 100x

## Limitações conhecidas

- **Entrada mono.** A saída é estéreo; o microfone é um só e a cadeia vocal
  roda em mono até a colocação estéreo.
- **Camada não tem volume próprio depois de gravada.** O bounce é o preço da
  simplicidade: para mudar o equilíbrio entre as vozes, desfaça a camada e
  grave de novo. Em compensação empilhar não tem limite nem custo de memória.
- **Andamento travado com camadas.** Explicado acima.
- **Exportar para Música precisa de Android 10.** Abaixo disso a pasta pública
  exige uma permissão em tempo de execução que o app não pede; use
  Compartilhar.
- **PSOLA acima de uma oitava fica sujo.** O resíduo depende de
  `espaçamento mod período`: ele é pior onde isso cai em meio período, que é
  exatamente uma oitava acima e uma quinta abaixo. Nos dois casos o resíduo cai
  num intervalo consoante com o alvo, por isso ainda se sustenta numa mixagem.
  Terças e quintas ficam abaixo de -10 dB.
- **Melodia extraída depende do vocal estar audível.** Em música muito
  compressa ou com vocal enterrado, a segmentação erra notas. Refazer a análise
  não muda nada: o limite é o sinal.
- **Sussurro e voz rouca** degradam o PSOLA. Sem periodicidade clara não há
  marca de pitch confiável. O caminho unvoiced passa dry, então não quebra, mas
  o autotune simplesmente não atua.
- **Sem serviço em primeiro plano.** Sair do app solta o microfone e para a
  música.

## Próximos passos

1. **Tap-to-sync de letra** dentro do app, para quem não acha o `.lrc`
2. **Separação de fontes** no lugar do cancelamento de centro, para tirar
   vocal de mixagem moderna sem furo no meio
3. **Mixagem offline** das camadas, para dar volume e mudo por voz sem abrir
   mão do custo constante do bounce
4. **Punch-in** para regravar só um trecho dentro de uma camada
5. **Viterbi na trajetória de pitch** da análise, para matar o resto dos erros
   de oitava na extração de melodia
6. **Dueto** com duas trilhas de melodia e pontuação separada por cantor
7. **Vídeo** junto com o take
