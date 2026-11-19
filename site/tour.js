const steps = [
  {
    id: "brano",
    label: "Brano",
    eyebrow: "Apri un file",
    title: "Scegli un brano",
    body: "Tocca «Scegli un brano» e apri un file MP3, WAV o FLAC sul telefono. Loopo ti mostra la forma d’onda per trovare il passaggio che vuoi studiare.",
    image: "studio-loop.png",
    imageAlt: "Forma d’onda del brano aperto in Loopo",
    caption: "La forma d’onda del brano",
    note: "I tuoi brani restano sul dispositivo. Puoi iniziare senza creare un account.",
  },
  {
    id: "loop",
    label: "Loop A/B",
    eyebrow: "Ripeti un passaggio",
    title: "Dal punto A al punto B",
    body: "Posiziona A all’inizio del passaggio e B alla fine. Tocca «Loop» e avvia la riproduzione. Loopo torna al punto A con una transizione graduale a ogni giro.",
    image: "studio-loop.png",
    imageAlt: "Marcatori A e B ai lati della forma d’onda e pulsante Loop attivo",
    caption: "A indica l’inizio, B indica la fine",
  },
  {
    id: "velocita",
    label: "Velocità",
    eyebrow: "Tieni la tonalità",
    title: "Rallenta il brano",
    body: "Sposta il cursore «Velocità» per esercitarti a un ritmo comodo. Puoi passare dal 25% al 200% mantenendo la tonalità originale. Tocca «1x» per tornare al tempo del brano.",
    image: "studio-velocita-73.png",
    imageAlt: "Cursore della velocità di Loopo impostato al 73%",
    caption: "In questa sessione il brano suona al 73% della velocità",
  },
  {
    id: "voce",
    label: "Voce",
    eyebrow: "Tieni le mani sullo strumento",
    title: "Di’ «Ehi Lupo»",
    body: "Usa le cuffie e tieni il telefono vicino, così il brano non copre la tua voce.",
    instructions: [
      "Apri un brano, tocca il lupo con il microfono e consenti l’accesso al microfono.",
      "Alla prima attivazione, attendi il download del modello italiano (circa 50 MB). Poi i comandi funzionano offline.",
      "Di’ «Ehi Lupo». Loopo mette in pausa il brano. Quando compare «Ti ascolto…», pronuncia un comando.",
      "Per interrompere l’ascolto, tocca di nuovo il pulsante del lupo.",
    ],
    image: "voice-control.png",
    imageAlt: "Icona del lupo con microfono che attiva i comandi vocali",
    caption: "Cerca questo pulsante nella barra in alto",
    examples: [
      "Aumenta la velocità a ottanta",
      "Pausa",
      "Riprendi",
      "Ricomincia da capo",
      "Velocità al settanta e poi riprendi",
    ],
    note: "Il permesso «Mostra sopra altre app» serve al bordo luminoso ed è facoltativo. Voce e trascrizioni vengono elaborate sul telefono e non vengono salvate.",
  },
  {
    id: "installa",
    label: "Installa",
    eyebrow: "Versione 0.0.1 · APK ufficiale",
    title: "Installa Loopo su Android",
    body: "Apri questa pagina sul tuo telefono e scarica l’APK ufficiale di Loopo.",
    instructions: [
      "Tocca «Scarica Loopo per Android» e apri il file quando il download è terminato.",
      "Se Android lo chiede, consenti al browser di installare app da questa fonte.",
      "Tocca «Installa» nella schermata di Android e apri Loopo.",
    ],
    image: "loopo-mark.png",
    imageAlt: "Icona di Loopo da cercare sul telefono dopo l’installazione",
    caption: "Dopo l’installazione cerca il lupo tra le app",
  },
];

const tour = document.querySelector("#tour");
const stepList = document.querySelector("#steps");
const card = document.querySelector("#step-card");
const progress = document.querySelector("#step-progress");
const title = document.querySelector("#step-title");
const body = document.querySelector("#step-body");
const instructions = document.querySelector("#step-instructions");
const note = document.querySelector("#step-note");
const image = document.querySelector("#step-image");
const caption = document.querySelector("#step-caption");
const examples = document.querySelector("#step-examples");
const download = document.querySelector("#step-download");
const previous = document.querySelector("#previous");
const next = document.querySelector("#next");
document.querySelector("#step-apk-link").href = document.querySelector("#apk-link").href;

let activeStepIndex = 0;

const stepButtons = steps.map((step, index) => {
  const item = document.createElement("li");
  const button = document.createElement("button");
  const number = document.createElement("span");
  const label = document.createElement("span");

  button.type = "button";
  button.setAttribute("aria-controls", card.id);
  number.className = "step-number";
  number.setAttribute("aria-hidden", "true");
  number.textContent = String(index + 1).padStart(2, "0");
  label.className = "step-label";
  label.textContent = step.label;
  button.append(number, label);
  button.addEventListener("click", () => selectStep(index));
  item.append(button);
  stepList.append(item);
  return button;
});

function renderList(list, entries = []) {
  list.replaceChildren(...entries.map((entry) => {
    const item = document.createElement("li");
    item.textContent = entry;
    return item;
  }));
  list.hidden = entries.length === 0;
}

function renderStep() {
  const step = steps[activeStepIndex];
  card.dataset.step = step.id;
  progress.textContent = `Passo ${activeStepIndex + 1} di ${steps.length} · ${step.eyebrow}`;
  title.textContent = step.title;
  body.textContent = step.body;
  renderList(instructions, step.instructions);
  note.textContent = step.note ?? "";
  note.hidden = !step.note;

  image.src = `assets/${step.image}`;
  image.alt = step.imageAlt;
  caption.textContent = step.caption;
  renderList(examples, step.examples?.map((example) => `«${example}»`));
  download.hidden = step.id !== "installa";

  stepButtons.forEach((button, index) => {
    if (index === activeStepIndex) {
      button.setAttribute("aria-current", "step");
    } else {
      button.removeAttribute("aria-current");
    }
  });

  previous.disabled = activeStepIndex === 0;
  next.hidden = activeStepIndex === steps.length - 1;
}

function selectStep(index) {
  activeStepIndex = index;
  history.replaceState(null, "", `#${steps[index].id}`);
  renderStep();
  title.focus();
}

function readStepLink() {
  return steps.findIndex((step) => location.hash === `#${step.id}`);
}

previous.addEventListener("click", () => selectStep(activeStepIndex - 1));
next.addEventListener("click", () => selectStep(activeStepIndex + 1));

const voiceLink = document.querySelector('a[href="#voce"]');
voiceLink.addEventListener("click", (event) => {
  // Preserve the shareable link when opening it in a new tab.
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  event.preventDefault();
  selectStep(steps.findIndex((step) => step.id === "voce"));
});

const linkedStepIndex = readStepLink();
activeStepIndex = Math.max(0, linkedStepIndex);
renderStep();
tour.hidden = false;
voiceLink.hidden = false;

if (linkedStepIndex >= 0) {
  title.focus();
}

window.addEventListener("hashchange", () => {
  const index = readStepLink();
  if (index >= 0) selectStep(index);
});
