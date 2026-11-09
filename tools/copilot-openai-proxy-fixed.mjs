import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { Readable } from "node:stream";

const PORT = Number(process.env.COPILOT_PROXY_PORT || 4141);
const LISTEN_HOST = process.env.COPILOT_PROXY_HOST || "127.0.0.1";
const API_BASE = process.env.COPILOT_PROXY_UPSTREAM || "https://api.githubcopilot.com";
const TOKEN_EXCHANGE_URL = process.env.COPILOT_PROXY_TOKEN_EXCHANGE_URL || "https://api.github.com/copilot_internal/v2/token";
const USER_AGENT = process.env.COPILOT_PROXY_USER_AGENT || "GitHubCopilotChat/0.35.0";
const EDITOR_VERSION = process.env.COPILOT_PROXY_EDITOR_VERSION || "vscode/1.107.0";
const EDITOR_PLUGIN_VERSION = process.env.COPILOT_PROXY_EDITOR_PLUGIN_VERSION || "copilot-chat/0.35.0";
const INTEGRATION_ID = process.env.COPILOT_PROXY_INTEGRATION_ID || "vscode-chat";
const TOKEN_MODE = (process.env.COPILOT_PROXY_TOKEN_MODE || "auto").trim().toLowerCase();

// Explicit environment token support (set locally, do NOT paste tokens into chat)
const ENV_TOKEN =
  process.env.COPILOT_PROXY_API_TOKEN ||
  process.env.COPILOT_PROXY_GITHUB_TOKEN ||
  process.env.GITHUB_TOKEN ||
  process.env.GH_TOKEN ||
  "";

let cachedApiToken = "";
let cachedApiTokenExpiresAtMs = 0;

function log(...args) {
  try {
    process.stdout.write(new Date().toISOString() + " " + args.join(" ") + "\n");
  } catch (e) {
    // best-effort logging
  }
}

function sendJson(res, statusCode, payload) {
  if (res.headersSent) {
    if (!res.writableEnded) res.end();
    return;
  }
  res.writeHead(statusCode, { "Content-Type": "application/json" });
  res.end(JSON.stringify(payload));
}

function isLikelyPAT(token) {
  return typeof token === "string" && (token.startsWith("ghp_") || token.startsWith("github_pat_"));
}

function candidateHostsPaths() {
  const override = process.env.COPILOT_PROXY_HOSTS_JSON?.trim();
  const values = [];
  if (override) values.push(path.resolve(override));

  values.push(path.join(os.homedir(), ".config", "github-copilot", "hosts.json"));

  if (process.platform === "win32") {
    if (process.env.USERPROFILE) values.push(path.join(process.env.USERPROFILE, ".config", "github-copilot", "hosts.json"));
    if (process.env.LOCALAPPDATA) values.push(path.join(process.env.LOCALAPPDATA, "github-copilot", "hosts.json"));
  }
  return [...new Set(values)];
}

function extractOauthToken(obj) {
  if (!obj || typeof obj !== "object") return "";
  const keys = ["oauth_token", "oauthToken", "token", "access_token", "accessToken"];
  for (const k of keys) {
    if (typeof obj[k] === "string" && obj[k].trim()) return obj[k].trim();
  }
  for (const v of Object.values(obj)) {
    if (typeof v === "object") {
      const nested = extractOauthToken(v);
      if (nested) return nested;
    }
  }
  return "";
}

function readTokenFromHostsFile(hostsPath) {
  const raw = fs.readFileSync(hostsPath, "utf8");
  const parsed = JSON.parse(raw);
  // try common "github.com" entry first
  if (parsed && typeof parsed === "object") {
    const byHost = Object.entries(parsed).filter(([k]) => String(k).toLowerCase().includes("github.com"));
    for (const [, payload] of byHost) {
      const token = extractOauthToken(payload);
      if (token) return token;
    }
  }
  return extractOauthToken(parsed);
}

function readTokenFromWslHostsFile() {
  if (process.platform !== "win32") return "";
  if (process.env.COPILOT_PROXY_DISABLE_WSL_LOOKUP === "1") return "";
  try {
    const result = spawnSync("wsl.exe", ["bash", "-lc", "cat ~/.config/github-copilot/hosts.json"], { encoding: "utf8", windowsHide: true });
    if (result.status !== 0 || !result.stdout) return "";
    const parsed = JSON.parse(result.stdout);
    return extractOauthToken(parsed);
  } catch (e) {
    return "";
  }
}

function loadCopilotOauthToken() {
  // If user explicitly provided ENV_TOKEN but also wants to treat it as oauth, allow it
  for (const hostsPath of candidateHostsPaths()) {
    try {
      if (!fs.existsSync(hostsPath)) continue;
      const token = readTokenFromHostsFile(hostsPath);
      if (token) return token;
    } catch (e) {
      // ignore
    }
  }

  const wslToken = readTokenFromWslHostsFile();
  if (wslToken) return wslToken;

  throw new Error("No Copilot OAuth token found in hosts.json. Run Copilot login or set COPILOT_PROXY_HOSTS_JSON to point at hosts.json.");
}

async function exchangeCopilotApiToken(oauthToken) {
  const now = Date.now();
  if (cachedApiToken && now < cachedApiTokenExpiresAtMs - 60_000) return cachedApiToken;
  const resp = await fetch(TOKEN_EXCHANGE_URL, {
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
  const text = await resp.text();
  if (!resp.ok) throw new Error(`Copilot token exchange failed (${resp.status}): ${text.slice(0, 300)}`);
  const payload = JSON.parse(text);
  const apiToken = typeof payload.token === "string" ? payload.token.trim() : "";
  if (!apiToken) throw new Error("Copilot token exchange returned no token");
  const expiresAtSeconds = Number(payload.expires_at || payload.expiresAt || 0);
  cachedApiToken = apiToken;
  cachedApiTokenExpiresAtMs = Number.isFinite(expiresAtSeconds) && expiresAtSeconds > 0 ? expiresAtSeconds * 1000 : now + 4 * 60 * 1000;
  return cachedApiToken;
}

async function resolveCopilotBearerToken() {
  // If user explicitly provided an env token and asked for pat/env mode, use it immediately
  if (ENV_TOKEN && (TOKEN_MODE === "pat" || TOKEN_MODE === "env")) return ENV_TOKEN;

  // Try hosts.json if available (don't throw yet)
  let oauthToken = null;
  try {
    oauthToken = loadCopilotOauthToken();
  } catch (e) {
    oauthToken = null;
  }

  if (TOKEN_MODE === "pat" || TOKEN_MODE === "env") {
    if (ENV_TOKEN) return ENV_TOKEN;
    throw new Error('COPILOT_PROXY_TOKEN_MODE is "pat/env" but no env token found. Set COPILOT_PROXY_API_TOKEN or COPILOT_PROXY_GITHUB_TOKEN.');
  }

  if (TOKEN_MODE === "oauth") {
    if (!oauthToken) throw new Error("No Copilot OAuth token found in hosts.json");
    return oauthToken;
  }

  if (TOKEN_MODE === "exchange") {
    if (!oauthToken) throw new Error("No oauth token available for token exchange");
    return await exchangeCopilotApiToken(oauthToken);
  }

  // auto: prefer exchange, fallback to env token or oauth token
  if (TOKEN_MODE === "auto") {
    if (oauthToken) {
      try {
        return await exchangeCopilotApiToken(oauthToken);
      } catch (e) {
        if (ENV_TOKEN) return ENV_TOKEN;
        return oauthToken;
      }
    }
    if (ENV_TOKEN) return ENV_TOKEN;
    throw new Error("No token sources found. Provide hosts.json or COPILOT_PROXY_API_TOKEN");
  }

  throw new Error('Invalid COPILOT_PROXY_TOKEN_MODE. Use "oauth", "exchange", "auto", or "pat"');
}

function getRequestBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(Buffer.from(c)));
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
  if (hasBody) headers["Content-Type"] = req.headers["content-type"] || "application/json";
  if (typeof req.headers["openai-intent"] === "string") headers["Openai-Intent"] = req.headers["openai-intent"];
  return headers;
}

function copyUpstreamResponseHeaders(upstreamHeaders, res) {
  const contentType = upstreamHeaders.get("content-type");
  if (contentType) res.setHeader("Content-Type", contentType);
  else res.setHeader("Content-Type", "application/json");
  const cacheControl = upstreamHeaders.get("cache-control");
  if (cacheControl) res.setHeader("Cache-Control", cacheControl);
}

async function proxyRequest(req, res, upstreamPath) {
  const body = req.method === "POST" ? await getRequestBody(req) : undefined;
  const token = await resolveCopilotBearerToken();
  const upstreamUrl = `${API_BASE}${upstreamPath}`;
  log(`${req.method} ${upstreamPath} [proxy->upstream]`);
  const upstreamResponse = await fetch(upstreamUrl, { method: req.method, headers: buildUpstreamHeaders(req, token, Boolean(body)), body });
  copyUpstreamResponseHeaders(upstreamResponse.headers, res);
  res.writeHead(upstreamResponse.status);
  if (!upstreamResponse.body) return res.end();
  // Stream body
  try {
    Readable.fromWeb(upstreamResponse.body).pipe(res);
  } catch (e) {
    // Fallback for Node versions without fromWeb
    const buf = await upstreamResponse.arrayBuffer();
    res.end(Buffer.from(buf));
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || "/", `http://${req.headers.host || `localhost:${PORT}`}`);
  const pathname = url.pathname;

  if (req.method === "GET" && (pathname === "/health" || pathname === "/v1/health")) {
    return sendJson(res, 200, { status: "ok", listen: `${LISTEN_HOST}:${PORT}`, upstream: API_BASE, endpoints: ["/v1/responses", "/responses", "/v1/models", "/models"] });
  }

  const normalized = pathname.startsWith("/v1/") ? pathname.slice(3) : pathname;
  const isResponses = normalized === "/responses";
  const isModels = normalized === "/models";

  if ((req.method === "POST" && isResponses) || (req.method === "GET" && isModels)) {
    try {
      await proxyRequest(req, res, normalized);
    } catch (err) {
      log("proxy error:", err?.message || err);
      if (!res.headersSent) sendJson(res, 500, { error: { message: String(err?.message || err) } });
    }
    return;
  }

  sendJson(res, 404, { error: { message: "Supported: POST /v1/responses, POST /responses, GET /v1/models, GET /models" } });
});

server.listen(PORT, LISTEN_HOST, () => log(`Copilot proxy listening on http://${LISTEN_HOST}:${PORT} (token mode=${TOKEN_MODE})`));
