// SPDX-License-Identifier: MIT
// Local illustration only. No relay, provider, storage or network access.
const figure = document.querySelector('.demo');
const orbit = document.querySelector('.orbit');
const pause = document.querySelector('#pause-motion');
const appearance = document.querySelector('#change-appearance');
const label = document.querySelector('#appearance-label');
const note = document.querySelector('#motion-note');
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
const card = document.querySelector('#demo-card');
const body = document.querySelector('#card-body');
const showCard = document.querySelector('#show-card');
const showHeadline = document.querySelector('#show-headline');
const finishCard = document.querySelector('#finish-card');
const tourNote = document.querySelector('#tour-note');
let userPaused = false;
let onScreen = false;
let pageActive = true;
let initiatingControl = null;
// Stay static until the browser observes visibility; never replace this with polling.
const visibilityObserver = 'IntersectionObserver' in window ? new IntersectionObserver(entries => {
  onScreen = entries[entries.length - 1].isIntersecting;
  updateMotion();
}) : null;

// Manual local steps only: no copy of device arbitration, expiry or delivery.
function showIllustration(control, bodyless) {
  initiatingControl = control;
  card.hidden = false;
  body.hidden = bodyless;
  card.scrollTop = 0;
  card.classList.toggle('headline', bodyless);
  figure.classList.add('card-covered');
  updateMotion();
  showCard.disabled = true;
  showHeadline.disabled = true;
  finishCard.disabled = false;
  tourNote.textContent = 'Synthetic card illustration. Base appearance changes do not dismiss it. Choose Finish illustration to reveal the current appearance.';
  finishCard.focus();
}

showCard.addEventListener('click', () => showIllustration(showCard, false));
showHeadline.addEventListener('click', () => showIllustration(showHeadline, true));
finishCard.addEventListener('click', () => {
  card.hidden = true;
  figure.classList.remove('card-covered');
  updateMotion();
  showCard.disabled = false;
  showHeadline.disabled = false;
  finishCard.disabled = true;
  tourNote.textContent = 'Illustration finished. The current base appearance is revealed. No notification was sent.';
  initiatingControl.focus();
});
showCard.disabled = false;
showHeadline.disabled = false;
appearance.disabled = false;

function updateMotion() {
  const suspended = !pageActive || document.hidden || !onScreen;
  figure.classList.toggle('suspended', suspended);
  // Covered decoration is not the current illustration in the accessibility tree.
  orbit.setAttribute('aria-hidden', String(!card.hidden));
  figure.classList.toggle('paused', userPaused || reduced.matches);
  const motionUnavailable = !visibilityObserver;
  pause.disabled = reduced.matches || motionUnavailable;
  pause.setAttribute('aria-pressed', String(userPaused || reduced.matches || motionUnavailable));
  pause.textContent = reduced.matches ? 'Motion disabled' : motionUnavailable ? 'Motion unavailable' : userPaused ? 'Resume motion' : 'Pause motion';
  if (reduced.matches) {
    note.textContent = 'Static illustration: reduced motion is enabled.';
  } else if (motionUnavailable) {
    note.textContent = 'Static illustration: visibility observation is unavailable.';
  } else if (userPaused) {
    note.textContent = 'Motion paused. The appearance control still works.';
  } else if (!card.hidden) {
    note.textContent = 'Decorative motion pauses while a card illustration covers the base.';
  } else if (suspended) {
    note.textContent = 'Motion pauses while the illustration is offscreen or the page is hidden.';
  } else {
    note.textContent = 'Slow decorative motion; pause it whenever you prefer.';
  }
}

pause.addEventListener('click', () => {
  userPaused = !userPaused;
  updateMotion();
});
appearance.addEventListener('click', () => {
  const disconnected = figure.classList.toggle('disconnected');
  appearance.setAttribute('aria-pressed', String(disconnected));
  appearance.textContent = disconnected ? 'Show connected appearance' : 'Show disconnected appearance';
  label.textContent = disconnected ? 'Disconnected appearance — illustration' : 'Connected appearance — illustration';
});
reduced.addEventListener('change', updateMotion);
document.addEventListener('visibilitychange', updateMotion);
window.addEventListener('pagehide', () => {
  pageActive = false;
  onScreen = false;
  visibilityObserver?.disconnect();
  updateMotion();
});
window.addEventListener('pageshow', () => {
  pageActive = true;
  visibilityObserver?.observe(figure);
  updateMotion();
});
visibilityObserver?.observe(figure);
updateMotion();
