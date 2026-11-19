/*
 * IBM Plex, bundled with the app (owner, U014 answer 1). The font files are copied into the build by Vite and served
 * from the app itself; nothing is loaded from the internet, so on-premises installs with no outside access get the
 * same type. Only the weights the Case File canvas uses: Sans 400, 500, 600; Mono 400, 500.
 * The legacy pages name their own system font stack, so these faces do not change them.
 */
import '@fontsource/ibm-plex-sans/400.css';
import '@fontsource/ibm-plex-sans/500.css';
import '@fontsource/ibm-plex-sans/600.css';
import '@fontsource/ibm-plex-mono/400.css';
import '@fontsource/ibm-plex-mono/500.css';
