import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowRight, FileStack, Inbox, ScanLine, Search, Upload } from 'lucide-react';
import { CAP, caseTitle, stageOf, usePackages } from './data';
import { StageChip } from './atoms';

interface Command {
  id: string;
  group: 'Actions' | 'Cases';
  label: string;
  hint?: string;
  icon?: JSX.Element;
  stage?: ReturnType<typeof stageOf>;
  status?: string;
  run: () => void;
}

export default function CommandPalette({ open, onClose, caps }: { open: boolean; onClose: () => void; caps: string[] }) {
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const { data: packages } = usePackages(open && caps.includes(CAP.desk));

  useEffect(() => {
    if (open) {
      setQuery('');
      setActive(0);
      window.setTimeout(() => inputRef.current?.focus(), 0);
    }
  }, [open]);

  const commands = useMemo<Command[]>(() => {
    const go = (path: string) => () => { navigate(path); onClose(); };
    const actions: Command[] = [];
    if (caps.includes(CAP.intake)) {
      actions.push({ id: 'upload', group: 'Actions', label: 'New case from PDFs', hint: 'Upload', icon: <Upload size={16} />, run: go('/intake?via=upload') });
      actions.push({ id: 'scan', group: 'Actions', label: 'New case from paper', hint: 'Scan', icon: <ScanLine size={16} />, run: go('/intake?via=scan') });
    }
    if (caps.includes(CAP.review)) actions.push({ id: 'review', group: 'Actions', label: 'Open the review queue', icon: <Inbox size={16} />, run: go('/review') });
    if (caps.includes(CAP.desk)) actions.push({ id: 'desk', group: 'Actions', label: 'Go to the desk', icon: <FileStack size={16} />, run: go('/') });

    const q = query.trim().toLowerCase();
    const matchedActions = q ? actions.filter((a) => `${a.label} ${a.hint ?? ''}`.toLowerCase().includes(q)) : actions;

    const cases: Command[] = (packages ?? [])
      .filter((p) => !q || p.businessIdentifiers.some((i) => i.value.toLowerCase().includes(q)))
      .slice(0, q ? 8 : 5)
      .map((p) => {
        const title = caseTitle(p.businessIdentifiers);
        return {
          id: p.packageId,
          group: 'Cases',
          label: title.primary,
          hint: title.secondary,
          stage: stageOf(p.status),
          status: p.status,
          run: go(`/case/${p.packageId}`),
        };
      });

    return [...matchedActions, ...cases];
  }, [caps, navigate, onClose, packages, query]);

  useEffect(() => { setActive(0); }, [query]);

  if (!open) return null;

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') { event.preventDefault(); onClose(); }
    else if (event.key === 'ArrowDown') { event.preventDefault(); setActive((i) => Math.min(i + 1, commands.length - 1)); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); setActive((i) => Math.max(i - 1, 0)); }
    else if (event.key === 'Enter') {
      if (event.nativeEvent.isComposing || event.keyCode === 229) return;
      event.preventDefault();
      commands[active]?.run();
    }
  };

  let lastGroup = '';

  return (
    <div className="cf-palette-scrim" onMouseDown={onClose}>
      <div className="cf-palette" role="dialog" aria-modal="true" aria-label="Find a case or action" onMouseDown={(e) => e.stopPropagation()} onKeyDown={onKeyDown}>
        <div className="cf-palette-input">
          <Search size={16} aria-hidden="true" />
          <input
            ref={inputRef}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Contract number, borrower, or an action…"
            aria-label="Search cases and actions"
            role="combobox"
            aria-expanded="true"
            aria-controls="cf-palette-list"
            aria-activedescendant={commands[active] ? `cf-cmd-${commands[active].id}` : undefined}
          />
        </div>
        <ul className="cf-palette-list" id="cf-palette-list" role="listbox">
          {commands.length === 0 && <li className="cf-palette-empty">No case matches “{query}”.</li>}
          {commands.map((command, index) => {
            const header = command.group !== lastGroup ? command.group : null;
            lastGroup = command.group;
            return (
              <li key={command.id} role="presentation">
                {header && <div className="cf-palette-group">{header}</div>}
                <div
                  id={`cf-cmd-${command.id}`}
                  role="option"
                  aria-selected={index === active}
                  className="cf-palette-item"
                  onMouseEnter={() => setActive(index)}
                  onClick={command.run}
                >
                  {command.icon ? <span className="cf-palette-icon" aria-hidden="true">{command.icon}</span> : null}
                  <span className={command.group === 'Cases' ? 'cf-palette-label cf-mono' : 'cf-palette-label'}>{command.label}</span>
                  {command.hint && <span className="cf-palette-hint">{command.hint}</span>}
                  {command.stage && <StageChip stage={command.stage} status={command.status} />}
                  <ArrowRight size={14} className="cf-palette-go" aria-hidden="true" />
                </div>
              </li>
            );
          })}
        </ul>
      </div>
    </div>
  );
}
