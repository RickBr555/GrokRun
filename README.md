# GrokRun

Treino de tiros e corrida com pace, GPS, percurso e um jogo de pista. Interface em português do Brasil. Unidades: km, min/km, km/h, m.

O que você usa na prévia é o **site** (esta pasta). O app Android nativo está em `android/`.

## Site

Precisa do Node.js 22.

```bash
npm install
npm run dev
```

Abre em http://localhost:8080.

Os desenhos dos bots ficam em `public/mascots/`. Para trocar um personagem, substitua o PNG com o mesmo nome (`bola-sheet.png`, `neve-sheet.png`, e assim por diante).

## Android

Abra a pasta `android` no Android Studio (SDK 35, aparelho ou emulador API 26+). O passo a passo está em `android/README.md`.

As artes novas da prévia (chapéu, neve, fantasma, fone, toalha) estão no site. No Android os mascotes ainda são os vetores em `android/app/src/main/res/drawable/`.

## Repositório

O código está em https://github.com/RickBr555/GrokRun.
