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

## Baixar e instalar

[**auravox-1.0-debug.apk**](https://github.com/tenoriodouglas/auravox/raw/main/dist/auravox-1.0-debug.apk) — 20 MB, arm64 e x86_64.

Baixe pelo celular, abra e autorize a instalação de fontes desconhecidas
quando o Android pedir. É um build de debug assinado com a chave padrão do
Android SDK: instala em qualquer aparelho e continua compatível com builds
feitos na sua máquina. Para publicar na Play Store é preciso gerar uma chave
própria e rodar `./gradlew assembleRelease` com ela.

**Use fone com fio.** No alto-falante o microfone capta a própria base e o
monitoramento vira microfonia. Bluetooth adiciona 150–300 ms e inviabiliza
cantar junto.

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
  Oboe input ──▶ callback do output ──▶ FxChain          TrackPlayer
  (VoicePerformance)         │        (voz estéreo)    (WSOLA + resampler)
                             │              │                 │
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
| `cpp/dsp/Spatial.h` | Reverb estéreo, delay ping-pong, doubler |
| `cpp/dsp/Dynamics.h` | Gate, compressor, de-esser, limiter, EQ, ducker |
| `cpp/track/TrackPlayer.h` | Playhead, ring do decoder, ganho, remoção de vocal |
| `cpp/karaoke/ScoreTracker.h` | Pontuação contra a melodia, combo, notas |
| `cpp/karaoke/Analysis.h` | Melodia, tom e BPM na importação |
| `kotlin/audio/PcmSource.kt` | MediaCodec para float PCM |
| `kotlin/audio/TrackDecoder.kt` | Streaming para o ring, com backpressure |
| `kotlin/audio/SongAnalyzer.kt` | Decodifica, reduz para 11 kHz mono, chama a análise |
| `kotlin/karaoke/Lrc.kt` | LRC simples e estendido |
| `kotlin/ui/widget/PitchLane.kt` | O piano roll rolando |

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
além da biblioteca padrão. 62 checagens, entre elas:

```
PSOLA pitch shift          -12 a +12 semitons, erro 0.0 cents
TimeScale unity            transparente, SNR 142.7 dB
TimeScale tom              ±7 semitons, erro abaixo de 1 cent
Remoção de vocal           voz central -32 dB, grave -0.2 dB
Alinhamento da gravação    base e voz no mesmo frame no take
Pontuação                  60 ms de latência não custa ponto
Análise                    frase em dó maior: 7/7 notas, tom e 120 BPM
Pior caso                  3.2% de tempo real em x86
```

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
3. **Viterbi na trajetória de pitch** da análise, para matar o resto dos erros
   de oitava na extração de melodia
4. **Dueto** com duas trilhas de melodia e pontuação separada por cantor
5. **Vídeo** junto com o take
