// Plaid Link island for /setup.
//
// Wires the "Link Bank Account" button to the embedded Plaid Link flow:
//   1. fetch a link_token from the server,
//   2. open Plaid Link (multiple-account selection is enabled in the Plaid
//      Dashboard, so the user picks which accounts to grant),
//   3. on success, POST the public_token + selected accounts to the server, which
//      exchanges it for an access_token, stores the Item credential, creates the
//      connection and kicks off the initial sync,
//   4. reload /setup so the new connection card appears.
//
// Plaid's Link.js is loaded lazily from Plaid's CDN on first click, so the page
// itself ships no third-party script until the user opts in.
export {};

interface PlaidAccount {
  id: string;
  name: string;
  mask: string | null;
  type: string;
  subtype: string | null;
}
interface PlaidInstitution {
  name: string;
  institution_id: string;
}
interface PlaidMetadata {
  institution: PlaidInstitution | null;
  accounts: PlaidAccount[];
}
interface PlaidHandler {
  open: () => void;
  destroy: () => void;
}
interface PlaidCreateOptions {
  token: string;
  onSuccess: (publicToken: string, metadata: PlaidMetadata) => void;
  onExit?: () => void;
}
interface PlaidGlobal {
  create: (opts: PlaidCreateOptions) => PlaidHandler;
}

const PLAID_CDN = 'https://cdn.plaid.com/link/v2/stable/link-initialize.js';

function loadPlaid(): Promise<PlaidGlobal> {
  const w = window as unknown as { Plaid?: PlaidGlobal };
  if (w.Plaid) return Promise.resolve(w.Plaid);
  return new Promise<PlaidGlobal>((resolve, reject) => {
    const s = document.createElement('script');
    s.src = PLAID_CDN;
    s.onload = (): void => (w.Plaid ? resolve(w.Plaid) : reject(new Error('Plaid unavailable after load')));
    s.onerror = (): void => reject(new Error('Plaid script failed to load'));
    document.head.appendChild(s);
  });
}

const btn = document.getElementById('plaid-link-btn') as HTMLButtonElement | null;

if (btn) {
  btn.addEventListener('click', async () => {
    btn.disabled = true;
    try {
      const Plaid = await loadPlaid();
      const tokenRes = await fetch('/setup/plaid/link-token');
      if (!tokenRes.ok) throw new Error(`link-token request failed: ${tokenRes.status}`);
      const { link_token: token } = (await tokenRes.json()) as { link_token: string };

      const handler = Plaid.create({
        token,
        onSuccess: async (publicToken, metadata) => {
          await fetch('/setup/plaid/exchange', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              public_token: publicToken,
              institution: metadata.institution,
              accounts: metadata.accounts,
            }),
          });
          window.location.assign('/setup');
        },
        onExit: () => {
          btn.disabled = false;
        },
      });
      handler.open();
    } catch (err) {
      console.error('Plaid Link failed', err);
      btn.disabled = false;
    }
  });
}
