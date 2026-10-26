# GrokRun

Timer de treino intervalado (tiros) e corrida com faixa de pace, GPS, percurso e evolução. Kotlin + Jetpack Compose. Interface em português do Brasil. Unidades: km, min/km, km/h, m.

## Como abrir

1. Instale o Android Studio (Ladybug ou mais novo) com SDK 35.
2. **File → Open** e escolha esta pasta (`android`).
3. Se o Android Studio pedir para criar o Gradle Wrapper, aceite. O `gradle-wrapper.properties` já aponta para o Gradle 8.11.1.
4. Deixe o Gradle sincronizar (precisa de internet na primeira vez: AndroidX, Room, Play Services Location, osmdroid).
5. Rode o módulo `app` num aparelho ou emulador com API 26 ou mais (API 34+ para validar o serviço em primeiro plano do Android 14).

minSdk 26 · targetSdk 35 · compileSdk 35.

## O que o app faz

- **Tiros:** aquecimento, tiros, descanso entre tiros (sem descanso depois do último do ciclo), descanso entre ciclos e desaquecimento. O tempo total é calculado. Durante o treino: fase, tempo restante, tiro X/Y, ciclo X/Y, pace, velocidade e distância.
- **Corrida:** faixa de pace. A média móvel usa uma janela de 15–30 s. O alerta só sai depois de 5 s contínuos fora da faixa e no máximo a cada 15 s. Rápido demais: bipe agudo duplo. Lento demais: bipe grave longo. Dentro da faixa: silêncio. O áudio pede `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` só na hora de cada bipe e solta ~600 ms depois, então a música só abaixa durante o apito.
- **Relógio:** todas as trocas são horários absolutos em `SystemClock.elapsedRealtime()`. Pausar e retomar deslocam esses horários. Não há contador que incrementa a cada tick.
- **Serviço:** `WorkoutService` é um foreground service com `foregroundServiceType="location"`, notificação com pausar/retomar/parar, e `PARTIAL_WAKE_LOCK`. Se a activity fechar, o treino continua.
- **GPS:** `FusedLocationProviderClient`, `PRIORITY_HIGH_ACCURACY`, intervalo de 1 s. Pontos com precisão > 20 m ou salto acima de 25 km/h são descartados. A coordenada gravada passa por uma suavização leve.
- **Percurso:** cada ponto vai para o Room (lat, lon, tempo, precisão, altitude, fase). O mapa da atividade usa osmdroid / OpenStreetMap, sem chave de API.
- **Elevação:** se houver barômetro, altitude relativa com `SensorManager.getAltitude`, ancorada na altitude GPS inicial. Senão, ao final, a Open-Meteo Elevation API. Offline, fica a altitude GPS marcada como estimada. Ganho e perda usam limiar de 2,5 m.
- **Sinais:** `ToneGenerator` + `VibrationEffect`. Contagem de 3 bipes antes de cada troca.
- **GPX:** a tela da atividade compartilha um `.gpx` (para o Strava, por exemplo).
- **Presets:** alguns já vêm gravados no Room (“3 tiros de 1 min, 2 ciclos”, “Teste rápido”, …).
- **Permissões:** a tela Ajustes pede localização precisa, localização em segundo plano, notificações e isenção de otimização de bateria (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), com o motivo de cada uma.

## Mascotes

Os seis mascotes ficam em `app/src/main/res/drawable-nodpi/` como PNG com fundo transparente, três poses cada:
`mascot_<id>_idle.png` (parado), `mascot_<id>_run.png` (correndo) e `mascot_<id>_rest.png` (sentado na cadeira).
Ids: `bola`, `triangulo`, `hexagono`, `fantasma`, `oblongo` (aparece como "Boné") e `quadrado`.
A lista está em `ui/Mascots.kt`; o mascote escolhido fica salvo em SharedPreferences. Para trocar uma arte, basta substituir o PNG com o mesmo nome.

## Testes

`app/src/test/java/com/grokrun/domain/EngineTest.kt` cobre a sequência dos tiros (sem descanso depois do último do ciclo), o tempo total e o relógio com pausa. (O arquivo de testes não veio no repositório.)
