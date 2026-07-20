/** Render a safe, accessible load failure with an in-place retry action. */
export function renderLoadFailure(
  container: HTMLElement,
  error: unknown,
  retry: () => void | Promise<void>,
): void {
  container.setAttribute('aria-busy', 'false');
  container.replaceChildren();

  const state = document.createElement('div');
  state.className = 'browse-empty load-failure';
  state.setAttribute('role', 'alert');

  const message = document.createElement('p');
  message.textContent = error instanceof Error ? error.message : String(error);

  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'ghost small';
  button.textContent = 'Try again';
  button.addEventListener('click', () => void retry());

  state.append(message, button);
  container.appendChild(state);
}
