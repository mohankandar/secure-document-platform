/**
 * Task 0068: the ports of the browser suites. Each suite has its own port; LV_E2E_PORT_BASE moves all of them by the
 * same amount, so sessions in different worktrees can run them at the same time. Not set: the ports they always had
 * (CI too). A suite never uses a server it didn't start (reuseExistingServer is false in every config).
 */
export const DEFAULT_PORT_BASE = 15175;

/** How far the ports are moved. Anything but a whole number that keeps the ports usable is refused, never guessed. */
export function portShift(value: string | undefined = process.env.LV_E2E_PORT_BASE): number {
  if (value === undefined || value.trim() === '') return 0;
  const base = Number(value);
  if (!Number.isInteger(base) || base < 10_000 || base > 40_000) {
    throw new Error('LV_E2E_PORT_BASE must be a whole number from 10000 to 40000');
  }
  return base - DEFAULT_PORT_BASE;
}

/** A suite's port: the default one, moved by LV_E2E_PORT_BASE. */
export const e2ePort = (defaultPort: number): number => defaultPort + portShift();

export const E2E_PORTS = {
  oidcSignin: e2ePort(15175),
  tablet: e2ePort(15176),
  a11y: e2ePort(15177),
  exportUi: e2ePort(15235),
  /** The export suite's backend and its stand-in AI service, unless LEXAVAULT_E2E_* names them. */
  exportApp: Number(process.env.LEXAVAULT_E2E_APP_PORT || e2ePort(18035)),
  exportOllama: Number(process.env.LEXAVAULT_E2E_OLLAMA_PORT || e2ePort(11535)),
} as const;
