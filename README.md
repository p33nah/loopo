<h1><img src="docs/images/loopo-mark.png" width="48" alt="Icona di Loopo"> Loopo</h1>

Loopo è un'app Android per studiare musica partendo dai brani sul tuo dispositivo. Apri un file audio, isola un passaggio e ripetilo alla velocità che preferisci.

## Studia al tuo ritmo

- Apri brani MP3, WAV e FLAC dal dispositivo.
- Segui la forma d'onda e scegli un passaggio con i marcatori A e B.
- Ripeti il passaggio con una transizione graduale tra la fine e l'inizio del ciclo.
- Cambia la velocità dal 25% al 200% senza cambiare tonalità.
- Continua l'ascolto in background e usa la notifica di Android per mettere in pausa.
- Attiva i comandi vocali in italiano quando vuoi. Dopo la configurazione iniziale funzionano offline. L'audio e le trascrizioni non vengono caricati né salvati.

Loopo lascia a te la scelta del brano, del passaggio e del ritmo. Non richiede un account. La musica e le sessioni restano sul dispositivo.

## Comandi vocali mentre ascolti

Per ridurre il rischio che la musica copra la voce, usa le cuffie e tieni il telefono vicino. Loopo rileva la parola di attivazione mentre il brano suona. Quando la riconosce, mette in pausa la musica e ascolta un comando.

<p align="center">
  <img src="docs/images/voice-control.png" width="64" alt="Pulsante dei comandi vocali di Loopo">
</p>

1. Apri un brano e tocca il pulsante con il lupo e il microfono nella barra in alto.
2. Concedi l'accesso al microfono. La prima attivazione scarica il modello vocale italiano, circa 50 MB. Per il download serve una connessione.
3. Per vedere il bordo luminoso sopra altre app, concedi il relativo permesso quando Android lo chiede. È facoltativo. Senza questo permesso, i comandi funzionano mentre Loopo è aperta.
4. Di' **Ehi Lupo** e aspetta **Ti ascolto…**. Poi pronuncia un comando, per esempio:

   - «Aumenta la velocità a ottanta»
   - «Diminuisci la velocità del cinque per cento»
   - «Pausa» oppure «Riprendi»
   - «Ricomincia da capo»
   - «Velocità al settanta e poi riprendi»

5. Tocca di nuovo il pulsante con il lupo per disattivare l'ascolto.

Se il ciclo è attivo, «Ricomincia da capo» riparte dal marcatore A. Se il ciclo è spento, riparte dall'inizio. Un comando che cambia solo la velocità riprende la musica se stava suonando prima dell'attivazione. Se era già in pausa, resta in pausa.

## Come è costruita

Kotlin e Jetpack Compose gestiscono l'interfaccia e la sessione di studio. Android decodifica i file. Il motore audio nativo usa Signalsmith Stretch per cambiare la velocità senza alterare l'intonazione. I comandi vocali usano Vosk sul dispositivo. Questa separazione mantiene i controlli dello studio indipendenti dal motore di riproduzione.

Per i dettagli, leggi [l'architettura](docs/architecture.md) e [l'architettura dei comandi vocali](docs/voice-ai-architecture.md).

## Installazione e sviluppo

La versione **0.0.1** è disponibile come prerelease su [GitHub Releases](https://github.com/p33nah/loopo/releases/tag/v0.0.1). Lo sviluppo richiede JDK 17 e Android SDK. Per i requisiti aggiornati e i comandi di build e verifica, consulta [Testing](docs/testing.md).

Per contribuire, leggi [Contributing](CONTRIBUTING.md). Consulta [Release readiness](docs/release-readiness.md) per lo stato e le verifiche della prima release. Loopo usa la [licenza MIT](LICENSE). Le licenze delle dipendenze sono in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Per segnalare problemi di sicurezza, consulta [Security](SECURITY.md).
