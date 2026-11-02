// The ONE place that names the hero photo. A licensed photo replaces public/brand/hero.jpg (or this path) and nothing else changes.
const HERO_SRC = '/brand/hero.jpg';
const HERO_ALT = 'A passenger train running beside palm trees and farmland along the Nile at sunset';

/** Full-bleed hero picture with the slow camera drift, and the dark scrim that keeps the white text readable. Put it in a positioned box. */
export function HeroPhoto() {
  return (
    <>
      <div className="hero-photo" role="img" aria-label={HERO_ALT} style={{ backgroundImage: `url(${HERO_SRC})` }} />
      <div className="hero-scrim" aria-hidden="true" />
    </>
  );
}
