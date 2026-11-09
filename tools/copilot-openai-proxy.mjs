import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { Readable } from "node:stream";

const PORT = 4141;
const LISTEN_HOST = process.env.COPILOT_PROXY_HOST || "127.0.0.1";
const API_BASE = "https://api.githubcopilot.com";
const TOKEN_EXCHANGE_URL = "https://api.github.com/copilot_internal/v2/token";
const USER_AGENT = process.env.COPILOT_PROXY_USER_AGENT || "GitHubCopilotChat/0.35.0";
const EDITOR_VERSION = process.env.COPILOT_PROXY_EDITOR_VERSION || "vscode/1.107.0";
const EDITOR_PLUGIN_VERSION =
  process.env.COPILOT_PROXY_EDITOR_PLUGIN_VERSION || "copilot-chat/0.35.0";
const INTEGRATION_ID = process.env.COPILOT_PROXY_INTEGRATION_ID || "vscode-chat";
const TOKEN_MODE = (process.env.COPILOT_PROXY_TOKEN_MODE || "oauth").trim().toLowerCase();
const ENV_TOKEN =
  process.env.COPILOT_PROXY_API_TOKEN ||
  process.env.COPILOT_PROXY_GITHUB_TOKEN ||
  process.env.GITHUB_TOKEN ||
  process.env.GH_TOKEN ||
  "";

let cachedApiToken = "";
let cachedApiTokenExpiresAtMs = 0;

function log(message) {
  process.stdout.write(`${new Date().toISOString()} ${message}\n`);
}

function sendJson(res, statusCode, payload) {
  if (res.headersSent) {
    if (!res.writableEnded) {
      res.end();
    }
    return;
  }
  res.writeHead(statusCode, { "Content-Type": "application/json" });
  res.end(JSON.stringify(payload));
}

function isLikelyPAT(token) {
  return token.startsWith("ghp_") || token.startsWith("github_pat_");
}

function candidateHostsPaths() {
  const override = process.env.COPILOT_PROXY_HOSTS_JSON?.trim();
  const values = [];
  if (override) {
    values.push(path.resolve(override));
  }

  values.push(path.join(os.homedir(), ".config", "github-copilot", "hosts.json"));

  if (process.platform === "win32") {
    if (process.env.USERPROFILE) {
      values.push(path.join(process.env.USERPROFILE, ".config", "github-copilot", "hosts.json"));
    }
    if (process.env.LOCALAPPDATA) {
      values.push(path.join(process.env.LOCALAPPDATA, "github-copilot", "hosts.json"));
    }
  }

  return [...new Set(values)];
}

function extractOauthToken(value) {
  if (!value || typeof value !== "object") {
    return "";
  }

  if (typeof value.oauth_token === "string" && value.oauth_token.trim().length > 0) {
    return value.oauth_token.trim();
  }
  if (typeof value.oauthToken === "string" && value.oauthToken.trim().length > 0) {
    return value.oauthToken.trim();
  }

  for (const nested of Object.values(value)) {
    const token = extractOauthToken(nested);
    if (token) {
      return token;
    }
  }
  return "";
}

function readTokenFromHostsObject(hostsJson) {
  if (!hostsJson || typeof hostsJson !== "object") {
    return "";
  }

  const hostCandidates = Object.entries(hostsJson)
    .filter(([host]) => host.toLowerCase().includes("github.com"))
    .map(([, payload]) => payload);

  for (const payload of hostCandidates) {
    const token = extractOauthToken(payload);
    if (token) {
      return token;
    }
  }

  return extractOauthToken(hostsJson);
}

function readTokenFromHostsFile(hostsPath) {
  const raw = fs.readFileSync(hostsPath, "utf8");
  const parsed = JSON.parse(raw);
  return readTokenFromHostsObject(parsed);
}

function readTokenFromWslHostsFile() {
  if (process.platform !== "win32") {
    return "";
  }
  if (process.env.COPILOT_PROXY_DISABLE_WSL_LOOKUP === "1") {
    return "";
  }

  const result = spawnSync("wsl.exe", ["bash", "-lc", "cat ~/.config/github-copilot/hosts.json"], {
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.status !== 0) {
    return "";
  }
  const parsed = JSON.parse(result.stdout);
  return readTokenFromHostsObject(parsed);
}

function loadCopilotOauthToken() {
  for (const hostsPath of candidateHostsPaths()) {
    if (!fs.existsSync(hostsPath)) {
      continue;
    }
    const token = readTokenFromHostsFile(hostsPath);
    if (token) {
      if (isLikelyPAT(token)) {
        throw new Error(
          `Refusing PAT from ${hostsPath}. Expected Copilot OAuth session token from hosts.json.`,
        );
      }
      return token;
    }
  }

  const wslToken = readTokenFromWslHostsFile();
  if (wslToken) {
    if (isLikelyPAT(wslToken)) {
      throw new Error("Refusing PAT from WSL hosts.json. Expected Copilot OAuth session token.");
    }
    return wslToken;
  }

  throw new Error(
    "No Copilot OAuth token found. Run the official Copilot CLI login, then ensure ~/.config/github-copilot/hosts.json exists.",
  );
}

async function exchangeCopilotApiToken(oauthToken) {
  const now = Date.now();
  if (cachedApiToken && now < cachedApiTokenExpiresAtMs - 60_000) {
    return cachedApiToken;
  }

  const response = await fetch(TOKEN_EXCHANGE_URL, {
    method: "GET",
    headers: {
      Authorization: `token ${oauthToken}`,
      Accept: "application/json",
      "User-Agent": USER_AGENT,
      "Editor-Version": EDITOR_VERSION,
      "Editor-Plugin-Version": EDITOR_PLUGIN_VERSION,
      "Copilot-Integration-Id": INTEGRATION_ID,
    },
  });

  const payloadText = await response.text();
  if (!response.ok) {
    throw new Error(`Copilot token exchange failed (${response.status}): ${payloadText.slice(0, 300)}`);
  }

  let payload;
  try {
    payload = JSON.parse(payloadText);
  } catch {
    throw new Error("Copilot token exchange returned non-JSON payload.");
  }

  const apiToken = typeof payload.token === "string" ? payload.token.trim() : "";
  if (!apiToken) {
    throw new Error("Copilot token exchange did not return token.");
  }

  const expiresAtSeconds = Number(payload.expires_at);
  cachedApiToken = apiToken;
  cachedApiTokenExpiresAtMs = Number.isFinite(expiresAtSeconds) ? expiresAtSeconds * 1000 : now + 4 * 60 * 1000;
  return cachedApiToken;
}

async function resolveCopilotBearerToken() {
  // Prefer an explicit environment token if requested ("pat" or "env")
  if (ENV_TOKEN && (TOKEN_MODE === "pat" || TOKEN_MODE === "env")) {
    return ENV_TOKEN;
  }

  // Try to load an oauth token from hosts.json but don't fail immediately
  let oauthToken = null;
  try {
    oauthToken = loadCopilotOauthToken();
  } catch (e) {
    oauthToken = null;
  }

  if (TOKEN_MODE === "pat" || TOKEN_MODE === "env") {
    if (ENV_TOKEN) return ENV_TOKEN;
    throw new Error('COPILOT_PROXY_TOKEN_MODE is set to "pat/env" but no env token was found. Set COPILOT_PROXY_API_TOKEN or COPILOT_PROXY_GITHUB_TOKEN.');
  }

  if (TOKEN_MODE === "oauth") {
    if (!oauthToken) throw new Error("No Copilot OAuth token found in hosts.json (set COPILOT_PROXY_HOSTS_JSON or login with Copilot CLI).");
    return oauthToken;
  }

  if (TOKEN_MODE === "exchange") {
    if (!oauthToken) throw new Error("No Copilot OAuth token available for exchange.");
    return await exchangeCopilotApiToken(oauthToken);
  }

  if (TOKEN_MODE === "auto") {
    // Prefer exchanging an oauth token when available; otherwise accept ENV_TOKEN or oauth token.
    if (oauthToken) {
      try {
        return await exchangeCopilotApiToken(oauthToken);
      } catch (err) {
        if (ENV_TOKEN) return ENV_TOKEN;
        return oauthToken;
      }
    }
    if (ENV_TOKEN) return ENV_TOKEN;
    throw new Error('No token sources found: ensure hosts.json or COPILOT_PROXY_API_TOKEN is set.');
  }

  throw new Error('Invalid COPILOT_PROXY_TOKEN_MODE. Use "oauth", "exchange", "auto", or "pat".');
}

function getRequestBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function buildUpstreamHeaders(req, token, hasBody) {
  const headers = {
    Authorization: `Bearer ${token}`,
    "User-Agent": USER_AGENT,
    "Editor-Version": EDITOR_VERSION,
    "Editor-Plugin-Version": EDITOR_PLUGIN_VERSION,
    "Copilot-Integration-Id": INTEGRATION_ID,
    Accept: req.headers.accept || "application/json",
  };

  if (hasBody) {
    headers["Content-Type"] = req.headers["content-type"] || "application/json";
  }
  if (typeof req.headers["openai-intent"] === "string") {
    headers["Openai-Intent"] = req.headers["openai-intent"];
  }

  return headers;
}

function copyUpstreamResponseHeaders(upstreamHeaders, res) {
  const contentType = upstreamHeaders.get("content-type");
  if (contentType) {
    res.setHeader("Content-Type", contentType);
  } else {
    res.setHeader("Content-Type", "application/json");
  }

  const cacheControl = upstreamHeaders.get("cache-control");
  if (cacheControl) {
    res.setHeader("Cache-Control", cacheControl);
  }
}

async function proxyRequest(req, res, upstreamPath) {
  const body = req.method === "POST" ? await getRequestBody(req) : undefined;
  const token = await resolveCopilotBearerToken();
  const upstreamResponse = await fetch(`${API_BASE}${upstreamPath}`, {
    method: req.method,
    headers: buildUpstreamHeaders(req, token, Boolean(body)),
    body,
  });

  copyUpstreamResponseHeaders(upstreamResponse.headers, res);
  res.writeHead(upstreamResponse.status);
  log(`${req.method} ${upstreamPath} -> ${upstreamResponse.status}`);

  if (!upstreamResponse.body) {
    res.end();
    return;
  }

  Readable.fromWeb(upstreamResponse.body).pipe(res);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || `localhost:${PORT}`}`);
  const pathname = url.pathname;

  if (req.method === "GET" && (pathname === "/health" || pathname === "/v1/health")) {
    sendJson(res, 200, {
      status: "ok",
      listen: `${LISTEN_HOST}:${PORT}`,
      upstream: API_BASE,
      endpoints: ["/v1/responses", "/responses", "/v1/models", "/models"],
    });
    return;
  }

  const normalizedPath = pathname.startsWith("/v1/") ? pathname.slice(3) : pathname;
  const isResponsesRoute = normalizedPath === "/responses";
  const isModelsRoute = normalizedPath === "/models";

  if ((req.method === "POST" && isResponsesRoute) || (req.method === "GET" && isModelsRoute)) {
    try {
      await proxyRequest(req, res, normalizedPath);
    } catch (error) {
      sendJson(res, 500, {
        error: {
          type: "proxy_error",
          message: String(error?.message || error),
        },
      });
    }
    return;
  }

  sendJson(res, 404, {
    error: {
      type: "not_found",
      message: "Supported routes: POST /v1/responses, POST /responses, GET /v1/models, GET /models",
    },
  });
});

server.listen(PORT, LISTEN_HOST, () => {
  log(
    `Copilot proxy listening on http://${LISTEN_HOST}:${PORT} (OpenAI base URL: http://localhost:${PORT}, token mode: ${TOKEN_MODE})`,
  );
});
