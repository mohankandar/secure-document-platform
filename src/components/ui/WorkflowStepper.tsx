import Icon from './Icon';
export type WorkflowStep = {
  label: string;
  description?: string;
  state?: 'complete' | 'current' | 'upcoming';
};

export default function WorkflowStepper({ steps, ariaLabel = 'Workflow progress' }: { steps: WorkflowStep[]; ariaLabel?: string }) {
  return (
    <ol className="workflow-stepper" aria-label={ariaLabel}>
      {steps.map((step, index) => {
        const state = step.state || 'upcoming';
        return (
          <li key={`${index}-${step.label}`} className={`workflow-step is-${state}`} aria-current={state === 'current' ? 'step' : undefined}>
            <span className="workflow-step-number" aria-hidden="true">{state === 'complete' ? <Icon name="check" /> : index + 1}</span>
            <span className="workflow-step-copy">
              <strong>{step.label}</strong>
              {step.description && <small>{step.description}</small>}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
