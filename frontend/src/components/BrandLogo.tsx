import { Link } from 'react-router-dom';

// The ONE place that names the logo file. Until the official SVG arrives this is a white PNG wordmark (272 x 64); swapping it is a change
// to these two lines (and the file in public/brand/).
const LOGO_SRC = '/brand/logo-etisalat-white.png';
const LOGO_RATIO = 272 / 64;

interface Props {
  /** Height in pixels. The header uses 30, the footer 26. */
  height?: number;
  /** The header logo links home; the footer one is plain. */
  home?: boolean;
}

/** The white etisalat wordmark, for the red header and the dark footer. */
export function BrandLogo({ height = 30, home = true }: Props) {
  const image = <img src={LOGO_SRC} alt="etisalat" height={height} width={Math.round(height * LOGO_RATIO)} />;
  if (!home) return <span className="brand-logo">{image}</span>;
  return (
    <Link to="/" className="brand-logo" aria-label="Train Booking, home">
      {image}
    </Link>
  );
}
