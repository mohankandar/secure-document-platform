import { useEffect, useState } from 'react';
import { Notice } from './atoms';

/**
 * Task 0101 (owner test U1): the address of a page image, made and revoked in the same effect. Before, it was made in
 * useMemo and revoked in an effect's clean-up: React.StrictMode (the dev server) mounts twice, so a page already in the
 * cache had its address revoked while it was still shown, and the reviewer saw only the alt text.
 */
export function useObjectUrl(blob: Blob | undefined): string | null {
  const [made, setMade] = useState<{ blob: Blob; url: string } | null>(null);
  useEffect(() => {
    if (!blob) {
      setMade(null);
      return undefined;
    }
    const url = URL.createObjectURL(blob);
    setMade({ blob, url });
    return () => URL.revokeObjectURL(url);
  }, [blob]);
  // Never the address of an earlier image, which may be revoked already.
  return made && made.blob === blob ? made.url : null;
}

/**
 * Task 0101: for SWR's compare. Its default compares contents, and a Blob shows none, so every image counted as the same
 * and Try again kept the one that had failed. A new answer is a new image.
 */
export function sameImage(a: unknown, b: unknown): boolean {
  return a === b;
}

/** Task 0101: a page that truly failed to load says so and can be tried again; never the image's alt text. */
export function PageFailed({ onRetry }: { onRetry: () => void }) {
  return (
    <Notice tone="danger">
      The page could not be loaded.{' '}
      <button type="button" className="cf-btn" data-variant="ghost" data-size="sm" onClick={onRetry}>Try again</button>
    </Notice>
  );
}
