Copilot Proxy — Quick start (PowerShell & WSL)

Security first
- Never paste a PAT into chat. If you accidentally revealed a PAT, revoke it immediately in GitHub: Settings → Developer settings → Personal access tokens.

Recommended (use Copilot OAuth via hosts.json)

PowerShell (session-only):
$env:COPILOT_PROXY_HOSTS_JSON='C:\Users\YOU\.config\github-copilot\hosts.json'; $env:COPILOT_PROXY_TOKEN_MODE='auto'; npm run copilot-proxy:fixed

WSL (Bash):
COPILOT_PROXY_HOSTS_JSON=~/.config/github-copilot/hosts.json COPILOT_PROXY_TOKEN_MODE=auto node tools/copilot-openai-proxy-fixed.mjs &

If you must use a PAT (not recommended)
- Revoke any leaked PAT first; create a new one only if necessary.

PowerShell (session-only):
$env:COPILOT_PROXY_API_TOKEN='NEW_PAT' ; $env:COPILOT_PROXY_TOKEN_MODE='pat' ; npm run copilot-proxy:fixed

WSL (Bash):
export COPILOT_PROXY_API_TOKEN='NEW_PAT' && export COPILOT_PROXY_TOKEN_MODE=pat && node tools/copilot-openai-proxy-fixed.mjs &

Quick tests (Windows PowerShell):
Invoke-RestMethod http://localhost:4141/health
Invoke-RestMethod http://localhost:4141/v1/models

Codex CLI (use a custom provider pointing at the proxy):
codex exec -c 'model_provider="copilot-local"' -c 'model_providers.copilot-local.base_url="http://localhost:4141"' -c 'model_providers.copilot-local.wire_api="responses"' --model gpt-5.5 "Your prompt"

WSL notes
- From WSL, reach the Windows proxy via the host gateway IP if needed:
  HOST_IP=$(ip route | awk '/default/ {print $3}')
  curl http://$HOST_IP:4141/health

Help
- Tell me which shell you use (PowerShell or WSL) and whether you prefer OAuth (hosts.json) or a local PAT; I can start the proxy for you and run a smoke test.
