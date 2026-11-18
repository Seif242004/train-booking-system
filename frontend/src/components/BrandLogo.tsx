import { Link } from 'react-router-dom';

// The ONE place that names the logo files (in public/brand/): the official vector logo in two colours, both 137 x 50 with a transparent
// background. White for the red header, the dark footer and the admin sidebar, red for white surfaces such as the ticket.
// Swapping a file is a change here.
const LOGOS = {
  white: { src: '/brand/logo-etisalat-white.svg', ratio: 137 / 50 },
  red: { src: '/brand/logo-etisalat-red.svg', ratio: 137 / 50 },
};

interface Props {
  /** Height in pixels of the image box, which has empty space above and below the letters: the header uses 44, the footer and the admin sidebar 40. */
  height?: number;
  /** The header logo links home; the footer one is plain. */
  home?: boolean;
  /** `white` (default) for dark and red backgrounds, `red` for white ones. */
  variant?: keyof typeof LOGOS;
}

/** The etisalat logo: white for the red header and the dark footer, red for white surfaces. */
// `ratio` is width divided by height, so the width can be set from the height and the image does not jump while loading.
export function BrandLogo({ height = 30, home = true, variant = 'white' }: Props) {
  const { src, ratio } = LOGOS[variant];
  const image = <img src={src} alt="etisalat" height={height} width={Math.round(height * ratio)} />;
  if (!home) return <span className="brand-logo">{image}</span>;
  return (
    <Link to="/" className="brand-logo" aria-label="Train Booking, home">
      {image}
    </Link>
  );
}
