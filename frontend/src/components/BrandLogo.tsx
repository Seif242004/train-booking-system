import { Link } from 'react-router-dom';

// The ONE place that names the logo files (in public/brand/): the white wordmark for the red header and the dark footer (a PNG until the
// official white SVG arrives), and the red logo for white surfaces such as the ticket (SVG, 137 x 50). Swapping a file is a change here.
const LOGOS = {
  white: { src: '/brand/logo-etisalat-white.png', ratio: 272 / 64 },
  red: { src: '/brand/logo-etisalat-red.svg', ratio: 137 / 50 },
};

interface Props {
  /** Height in pixels. The header uses 30, the footer 26. */
  height?: number;
  /** The header logo links home; the footer one is plain. */
  home?: boolean;
  /** `white` (default) for dark and red backgrounds, `red` for white ones. */
  variant?: keyof typeof LOGOS;
}

/** The etisalat logo: white for the red header and the dark footer, red for white surfaces. */
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
