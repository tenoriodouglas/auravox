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
| **Separação de vocal por modelo** | Spleeter rodando no próprio aparelho. Tira a voz de verdade, em vez de cancelar o centro da imagem estéreo e levar o bumbo e a caixa junto. ~30 s por música, uma vez. |
| **Melodia lida do vocal isolado** | Depois de separar, a melodia é relida da voz sozinha em vez da mixagem completa. É daí que vem a maior parte da melhora na pontuação. |
| **Vocal original como guia** | O cantor original baixinho sob a base, para aprender uma música que você ainda não sabe. |
| **Letra sincronizada automática** | Busca sozinha no LRCLIB por título, artista e duração. Com sincronia por palavra o destaque varre a linha em vez de pular. Importar `.lrc` continua ali para o que nenhum banco tem. |
| **Presets próprios** | Salve o som que você acertou e mande para alguém: é um arquivo JSON legível, indexado por nome de parâmetro para sobreviver a versões novas. |
| **Contagem sample-accurate** | O metrônomo é gerado na thread de áudio, então a base entra exatamente no tempo forte. |
| **Camadas por bounce** | Cada passada grava a mixagem completa, e a camada seguinte canta por cima desse arquivo. Empilhar sai de graça: não importa se são duas vozes ou dez, o custo é sempre uma faixa. |
| **Loop A–B com andamento** | Marque o trecho, baixe o andamento e repita até acertar. Nenhum processador vocal tem motivo para ter isso; um app de karaokê tem. |
| **Vocoder e talkbox** | A voz vira filtro. A portadora é um sintetizador afinado na nota cantada ou a própria base — aí a música fala a letra. |
| **Ouvir, exportar e compartilhar** | Toca no próprio app, salva em Música/AuraVox e compartilha por FileProvider. |
| **Escolha do microfone** | Inclusive o de fone Bluetooth, que o Android não entrega enquanto a captura não entrar em modo de comunicação. |
| **Escolher pelo título** | Lê o mesmo índice de músicas que o app de música do aparelho lê, com busca. O seletor de arquivos continua para o que não está indexado. |
| **Palco aceso pela capa** | A luz de fundo tira as cores da capa embutida no arquivo e respira com a voz. Shader onde há shader, degradês onde não há. |
| **Atualização automática** | Lê o manifesto publicado pela CI e instala a versão nova sem passar por loja. |

## Baixar e instalar

[**auravox-latest.apk**](https://github.com/tenoriodouglas/auravox/releases/download/apk-latest/auravox-latest.apk) — arm64.

Os pesos da separação não vêm no APK: são ~38 MB baixados na primeira vez que
você pedir para tirar o vocal, e o app confere o SHA-256 antes de usar.

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
| `cpp/dsp/VocalRemover.h` | Cancelamento de centro limitado à banda da voz, para o que ainda não foi separado |
| `cpp/dsp/Stft.h` | FFT radix-2 e sobreposição-soma ponderada |
| `cpp/dsp/Resampler.h` | Sinc janelada, para levar o arquivo aos 44,1 kHz do modelo |
| `cpp/separation/Separator.h` | Máscaras por razão e a extensão acima do teto da rede |
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
| `kotlin/karaoke/LyricsService.kt` | Busca no LRCLIB, com escada de resolução |
| `kotlin/karaoke/LyricsFile.kt` | Lyricsfile do LRCLIB, que é onde vem a sincronia por palavra |
| `kotlin/karaoke/LyricsMatch.kt` | Limpeza de título e desempate por duração |
| `kotlin/karaoke/DeviceLibrary.kt` | Índice de músicas do aparelho |
| `kotlin/karaoke/SongArt.kt` | Capa embutida e as duas cores que acendem o palco |
| `kotlin/separation/VocalSeparator.kt` | Percorre a música em trechos, orquestra o modelo |
| `kotlin/separation/SpleeterSession.kt` | As duas redes, no ONNX Runtime |
| `kotlin/separation/ModelStore.kt` | Baixa, confere o hash e guarda os pesos |
| `kotlin/audio/UserPresets.kt` | Formato de preset, salvo e compartilhável |
| `kotlin/audio/AudioDevices.kt` | Escolha de microfone e roteamento Bluetooth |
| `kotlin/karaoke/Exporter.kt` | FileProvider e MediaStore |
| `kotlin/update/GithubUpdater.kt` | Atualização a partir da release rolante |
| `kotlin/ui/widget/PitchLane.kt` | O piano roll rolando |
| `kotlin/ui/widget/LyricsView.kt` | Varredura por palavra na fase de desenho |
| `kotlin/ui/widget/Aurora.kt` | A luz de fundo, em três níveis de API |
| `tools/check_params.py` | Mantém os ids de Params.h e Param.kt em sincronia |

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

## Como a separação de vocal funciona aqui

A rede é uma U-Net convolucional sobre espectrogramas de magnitude. Ela não
tem transformada própria — o grafo não tem uma única operação de FFT — então
tudo dos dois lados dela fica no C++, que é onde já existe teste.

```
arquivo ──MediaCodec──▶ rascunho (float, taxa do arquivo)
                            │
                      sinc janelada ──▶ rascunho a 44,1 kHz
                            │
         ┌──────────── trecho de 11,9 s ────────────┐
         ▼                                          │
   STFT 4096/1024 ──▶ magnitude dos bins 0..1023 ──▶ rede (×2)
         │                                          │
   espectro completo guardado                 duas magnitudes
         │                                          │
         └────────▶ máscara por razão ◀─────────────┘
                            │
                    iSTFT com a fase da mistura
                            │
                    WAV 16 bits, com crossfade na costura
```

Três coisas decidem se o resultado serve, e todas foram medidas antes de virar
código.

**A máscara tem que ser razão, não a saída da rede.** Os dois modelos são
treinados separados e suas magnitudes não somam a mistura: medido em 1,24× dela.
Usar a saída direto vaza a voz para o playback. Dividir cada uma pela soma faz os
dois stems devolverem exatamente a entrada — medido em 4,4e-16.

**A rede para em 11 kHz.** Com janela de 4096 a 44,1 kHz, o bin 1024 é 11024 Hz,
e o padrão do próprio Spleeter descarta tudo acima. Medido num sinal com conteúdo
agudo de verdade, isso derruba a banda em **39 dB**: todo prato e todo brilho vão
junto, e o instrumental sai abafado. Continuar a máscara para cima, pela média
das bordas superiores que a rede modelou, guarda 99,99% — e custa um laço.

**Onde os dois modelos veem silêncio a razão não existe.** Dividir por um
denominador com piso manda as duas máscaras a zero e perde o bin. Esses vão
inteiros para o acompanhamento: silêncio não é do cantor.

A memória é o que dá forma ao resto. Quatro minutos de estéreo são oitenta
megabytes como float, e guardar entrada, cópia reamostrada e duas saídas ao mesmo
tempo seria quase toda a heap de um telefone — então a decodificação vai para
arquivo e a música é percorrida em trechos, o que limita o que fica residente a
alguns megabytes qualquer que seja a duração.

### Por que Spleeter e não o que soa melhor

A escolha é de licença, não de qualidade.

| Modelo | SDR vocal | Por que não |
|---|---|---|
| **Spleeter 2stems** | 6,55 dB | **Usado.** MIT, treinado em dados internos da Deezer |
| HTDemucs | 9,00 dB | O autor diz que os pesos são "apenas para fins científicos" |
| UVR / MDX-Net | melhor | Dados de treino não documentados, linhagem MUSDB18-HQ |
| BS-RoFormer | estado da arte | 72-95M parâmetros, nenhum runtime móvel |
| Open-Unmix | 6,25 dB | Igual ou pior, e sem export ONNX oficial |

O MUSDB18 está no caminho de quase todos: partes dele são CC BY-NC-SA e os
termos dizem "apenas para fins educacionais". O Spleeter é o único separador
competitivo que não herda isso.

Os pesos ficam numa release própria e não no repositório nem no APK. O upstream
publica só `.tar.bz2` e o Android não lê bzip2, então a CI extrai uma vez e
republica os dois `.onnx` soltos com os hashes que o app confere — um download
truncado não falharia alto, só separaria mal.

## Testes

```bash
tools/run_tests.sh
```

Roda no host, sem Android: `dsp/`, `track/`, `karaoke/` e `separation/` não
dependem de nada além da biblioteca padrão. 106 checagens, entre elas:

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
Nota fixada                troca com 30 cents de folga, não no meio
Ataque da afinação         erro mediano 0.09 semitom contra 0.69 sem ele
Centro da nota             entra em 60.48, sai em 59.88, vibrato preservado
STFT                       ida e volta exata a 1.8e-07
Máscaras de separação      somam 1 em todo bin; stems devolvem a mistura
Teto da rede               a banda de 14 kHz sobrevive integralmente
Reamostragem               1 kHz exato, banda que aliasaria 26 dB abaixo
Pior caso                  1.7% de tempo real em x86
```

Mais 33 na JVM, para o que não é DSP e ainda assim quebra calado:

```bash
./gradlew :app:testDebugUnitTest
tools/check_params.py
```

```
Contraste da paleta        todo par texto/superfície ≥ 4.5:1, borda ≥ 3:1
Lyricsfile                 documento real de 712 linhas, 35 versos
Casamento de letra         acento dobrado, ao vivo perde para estúdio
Cor da capa                capa cinza, capa escura e capa de um só matiz
Formato de preset          arquivo hostil não troca o ganho de entrada
Contrato de parâmetros     57 ids em sincronia entre os dois lados
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
- **A separação deixa resíduo de voz em pop denso.** O Spleeter mede 6,55 dB
  de SDR no vocal contra 9,00 dB do HTDemucs, que não pode ser usado: o autor
  dele diz que os pesos são "apenas para fins científicos". Serve para
  karaokê; não passa por instrumental de estúdio.
- **Separar é trabalho offline, não ao vivo.** Roda uma vez por música, com
  barra de progresso, e nunca dentro do callback de áudio.
- **Cada música separada ocupa espaço.** Base e vocal isolado são WAV de 16
  bits a 44,1 kHz: cerca de 40 MB cada um numa música de quatro minutos. Dá
  para apagar pelo menu da música, e aí ela volta a tocar do original.
- **A letra vem de um banco comunitário.** O LRCLIB não garante cobertura nem
  precisão de sincronia, e a letra em si é de terceiros: o app só consulta em
  nome de quem está usando, uma música por vez, e nunca distribui uma cópia do
  banco.
- **Não tem como pegar música do Spotify.** Isto não é uma escolha de projeto:
  nenhum caminho oficial entrega amostras de áudio a um app de terceiros, e os
  termos de desenvolvedor proíbem o resultado de qualquer jeito — a cláusula
  III.7 proíbe "mesclar, remixar ou sobrepor" conteúdo do Spotify com outro
  áudio, que é a descrição exata de karaokê. O caso que fecha a questão é o
  djay da Algoriddim: parceiro licenciado do Spotify, com separação de stems
  no próprio aparelho, e proibido de usá-la em faixas do Spotify, pela própria
  página de suporte deles. Nem um parceiro pagante consegue. O que o app faz no
  lugar é tirar o vocal do arquivo que você já tem e buscar a letra sozinho.

## Próximos passos

1. **Tap-to-sync de letra** dentro do app, para o que o LRCLIB não tem
2. **Auto Mix**, equilibrando voz e base por loudness BS.1770 em vez de deixar
   a pessoa adivinhando no fader
3. **Mixagem offline** das camadas, para dar volume e mudo por voz sem abrir
   mão do custo constante do bounce
4. **Punch-in** para regravar só um trecho dentro de uma camada
5. **Viterbi na trajetória de pitch** da análise, para matar o resto dos erros
   de oitava na extração de melodia
6. **Dueto** com duas trilhas de melodia e pontuação separada por cantor
7. **Vídeo** junto com o take
8. **Runtime reduzido do ONNX**, que tiraria 30 MB do APK. Não entrou porque o
   conjunto de operadores dele é fixo e não dá para verificar sem um aparelho:
   se faltar um operador, o modelo não carrega na mão de quem usa.
