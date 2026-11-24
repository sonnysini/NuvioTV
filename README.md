# NuvioTV — fork personale

Branch di servizio: contiene solo la CI del fork.

- Codice ufficiale: [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) (GPL-3.0)
- Patch del fork: branch `sonny/autoplay-priority` (commit sopra un tag ufficiale)
- `.github/workflows/fork-sync-build.yml`: ogni giorno prende l'ultimo tag ufficiale,
  riapplica la patch, compila e pubblica la release `<tag>+sonny` con APK e `fork.patch`.
  Se la patch non si applica, il job fallisce e non pubblica nulla.

Funzione aggiunta: **priorità fonti a cascata per l'autoplay** (Impostazioni → Riproduzione →
Selezione stream → Priorità fonti). Ogni addon/plugin riceve un livello; l'autoplay prova il
livello 1, poi il 2, ecc. Fonti con lo stesso livello hanno la stessa priorità.
