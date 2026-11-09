#!/usr/bin/env node
/**
 * copilot.js
 * ----------
 * A lightweight "GitHub Copilot-like" CLI that can:
 * • Suggest code at a cursor position
 * • Edit a whole file (or give a diff)
 * • Run arbitrary shell commands (e.g. test suite)
 * • Upgrade itself (git pull / npm update)
 * • Show/change persistent configuration
 *
 * Uses the NVIDIA Nemotron model via the NIM endpoint:
 * https://integrate.api.nvidia.com/v1
 *
 * Install: npm i
 * .env must contain NVIDIA_API_KEY
 *
 * Usage examples:
 * copilot suggest src/utils.ts "Return the current date as ISO string"
 * copilot edit src/App.jsx --diff "Convert class component to hooks"
 * copilot run npm test
 * copilot upgrade
 * copilot config model nvidia/nemotron-3-super-49b-instruct
 */

require('dotenv').config();
const { OpenAI } = require('openai');
const yargs = require('yargs/yargs');
const { hideBin } = require('yargs/helpers');
const fs = require('fs');
const fsPromises = require('fs').promises;
const path = require('path');
const { execSync, spawn } = require('child_process');
const chalk = require('chalk');
const figlet = require('figlet');
const readlineSync = require('readline-sync');
const os = require('os');

// ─────────────────────────────────────────────────────────────────────────────
// Banner
// ─────────────────────────────────────────────────────────────────────────────
function printBanner() {
  try {
    console.log(
      chalk.cyan(figlet.textSync('CopiLot', { horizontalLayout: 'full' }))
    );
  } catch (_) {
    console.log(chalk.cyan('=== CopiLot ==='));
  }
  console.log(chalk.dim('CLI Copilot – powered by NVIDIA Nemotron\n'));
}

// ─────────────────────────────────────────────────────────────────────────────
// Spinner
// ─────────────────────────────────────────────────────────────────────────────
const SPINNER_FRAMES = ['|', '/', '-', '\\'];
let spinnerIdx = 0;
let spinnerTimer = null;

function startSpinner(label = 'Thinking') {
  spinnerIdx = 0;
  spinnerTimer = setInterval(() => {
    process.stdout.write(
      `\r${chalk.yellow(SPINNER_FRAMES[spinnerIdx++ % SPINNER_FRAMES.length])} ${label}…`
    );
  }, 80);
}

function stopSpinner() {
  if (spinnerTimer) {
    clearInterval(spinnerTimer);
    spinnerTimer = null;
    process.stdout.write('\r' + ' '.repeat(40) + '\r');
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Persistent config (~/.copilotrc)
// ─────────────────────────────────────────────────────────────────────────────
const CONFIG_PATH = path.join(os.homedir(), '.copilotrc');

const DEFAULT_CONFIG = {
  model: 'nvidia/nemotron-3-super-49b-instruct',
  temperature: 0.2,
  topP: 0.95,
  maxTokens: 4096,
};

function loadConfig() {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, 'utf8');
    return { ...DEFAULT_CONFIG, ...JSON.parse(raw) };
  } catch (_) {
    return { ...DEFAULT_CONFIG };
  }
}

function saveConfig(cfg) {
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(cfg, null, 2), 'utf8');
}

// ─────────────────────────────────────────────────────────────────────────────
// OpenAI client → NVIDIA NIM
// ─────────────────────────────────────────────────────────────────────────────
const apiKey = process.env.NVIDIA_API_KEY;
if (!apiKey) {
  console.error(
    chalk.red('❌ NVIDIA_API_KEY not set – add it to .env or export it.')
  );
  process.exit(1);
}

const openai = new OpenAI({
  apiKey,
  baseURL: 'https://integrate.api.nvidia.com/v1',
});

// ─────────────────────────────────────────────────────────────────────────────
// Shared streaming helper
// Returns the full concatenated assistant text.
// ─────────────────────────────────────────────────────────────────────────────
async function streamChat({ model, temperature, topP, maxTokens, messages, label = 'Generating' }) {
  startSpinner(label);

  let fullText = '';
  let thinkingText = '';
  let inThinking = false;

  try {
    const stream = await openai.chat.completions.create({
      model,
      temperature,
      top_p: topP,
      max_tokens: maxTokens,
      stream: true,
      messages,
    });

    stopSpinner();

    for await (const chunk of stream) {
      const delta = chunk.choices?.[0]?.delta?.content ?? '';
      if (!delta) continue;

      // Capture <think>…</think> blocks separately
      if (delta.includes('<think>')) {
        inThinking = true;
      }
      if (inThinking) {
        thinkingText += delta;
        if (delta.includes('</think>')) {
          inThinking = false;
        }
        continue;
      }

      process.stdout.write(delta);
      fullText += delta;
    }

    process.stdout.write('\n');

    if (thinkingText) {
      const cleaned = thinkingText
        .replace(/<\/?think>/g, '')
        .trim();
      console.log(chalk.dim('\n─── Reasoning (hidden by default) ───'));
      console.log(chalk.dim(cleaned));
      console.log(chalk.dim('──────────────────────────────────────\n'));
    }
  } catch (err) {
    stopSpinner();
    throw err;
  }

  return fullText.trim();
}

// ─────────────────────────────────────────────────────────────────────────────
// Merge argv overrides on top of saved config
// ─────────────────────────────────────────────────────────────────────────────
function resolveParams(argv) {
  const cfg = loadConfig();
  return {
    model: argv.model ?? cfg.model,
    temperature: argv.temperature ?? cfg.temperature,
    topP: argv['top-p'] ?? cfg.topP,
    maxTokens: argv['max-tokens'] ?? cfg.maxTokens,
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. SUGGEST
// ─────────────────────────────────────────────────────────────────────────────
async function handleSuggest(argv) {
  printBanner();

  const { file, prompt, line, col } = argv;
  const { model, temperature, topP, maxTokens } = resolveParams(argv);

  const filePath = path.isAbsolute(file) ? file : path.resolve(process.cwd(), file);

  let original = '';
  try {
    original = await fsPromises.readFile(filePath, 'utf8');
  } catch (e) {
    console.error(chalk.red(`❌ Cannot read ${filePath}: ${e.message}`));
    process.exit(1);
  }

  const lines = original.split('\n');
  const lineIdx = Math.max(0, Math.min((line ?? 1) - 1, lines.length - 1));
  const lineText = lines[lineIdx] ?? '';
  const colIdx = Math.max(0, Math.min(col ?? 0, lineText.length));

  const prefix = lines.slice(0, lineIdx).join('\n') + (lineIdx > 0 ? '\n' : '');
  const lineBeforeCursor = lineText.slice(0, colIdx);
  const lineAfterCursor = lineText.slice(colIdx);
  const suffix = lineAfterCursor + (lines.length > lineIdx + 1 ? '\n' + lines.slice(lineIdx + 1).join('\n') : '');

  const systemMsg =
    'You are an expert programmer assistant. ' +
    'Given the code before and after the cursor, output ONLY the text that should be ' +
    'inserted at the cursor. Do NOT include any explanation, markdown fences, or extra whitespace.';

  const userMsg =
    `Task: ${prompt}\n\n` +
    `Code BEFORE cursor:\n\`\`\`\n${prefix}${lineBeforeCursor}\n\`\`\`\n\n` +
    `Code AFTER cursor (context only – do not repeat it):\n\`\`\`\n${suffix}\n\`\`\``;

  console.log(chalk.bold(`\n📄 ${filePath}`) + chalk.dim(` — line ${line ?? 1}, col ${col ?? 0}`));
  console.log(chalk.bold('\n💡 Suggestion:\n'));

  try {
    await streamChat({
      model, temperature, topP, maxTokens,
      label: 'Generating suggestion',
      messages: [
        { role: 'system', content: systemMsg },
        { role: 'user', content: userMsg },
      ],
    });
  } catch (err) {
    console.error(chalk.red(`\n❌ API error: ${err.message}`));
    process.exit(1);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. EDIT
// ─────────────────────────────────────────────────────────────────────────────
async function handleEdit(argv) {
  printBanner();

  const { file, prompt, diff: wantDiff, yes: skipConfirm } = argv;
  const { model, temperature, topP, maxTokens } = resolveParams(argv);

  const filePath = path.isAbsolute(file) ? file : path.resolve(process.cwd(), file);

  let original = '';
  try {
    original = await fsPromises.readFile(filePath, 'utf8');
  } catch (e) {
    console.error(chalk.red(`❌ Cannot read ${filePath}: ${e.message}`));
    process.exit(1);
  }

  const ext = path.extname(filePath).slice(1) || 'text';

  let systemMsg, userMsg;

  if (wantDiff) {
    systemMsg =
      'You are an expert programmer. Output ONLY a standard unified diff (--- / +++ / @@ lines). ' +
      'Do NOT include any explanation or markdown fences.';
    userMsg =
      `Instruction: ${prompt}\n\n` +
      `Current file (${ext}):\n\`\`\`${ext}\n${original}\n\`\`\``;
  } else {
    systemMsg =
      `You are an expert programmer. Rewrite the entire file according to the instruction. ` +
      `Output ONLY the complete new file contents with no explanation, no markdown fences, and no extra text.`;
    userMsg =
      `Instruction: ${prompt}\n\n` +
      `Current file (${ext}):\n\`\`\`${ext}\n${original}\n\`\`\``;
  }

  console.log(chalk.bold(`\n📄 Editing: ${filePath}`));
  console.log(chalk.bold(wantDiff ? '\n📋 Diff output:\n' : '\n✏️ New file contents:\n'));

  let result = '';
  try {
    result = await streamChat({
      model, temperature, topP, maxTokens,
      label: 'Generating edit',
      messages: [
        { role: 'system', content: systemMsg },
        { role: 'user', content: userMsg },
      ],
    });
  } catch (err) {
    console.error(chalk.red(`\n❌ API error: ${err.message}`));
    process.exit(1);
  }

  // ── Ask to apply ──────────────────────────────────────────────────────────
  const apply = skipConfirm
    ? true
    : readlineSync.keyInYNStrict(
        chalk.yellow('\nApply changes to the file?')
      );

  if (!apply) {
    console.log(chalk.dim('Aborted – file not modified.'));
    return;
  }

  if (wantDiff) {
    // Write diff to a temp file and apply with `patch`
    const tmpPatch = path.join(os.tmpdir(), `copilot_${Date.now()}.patch`);
    await fsPromises.writeFile(tmpPatch, result, 'utf8');
    try {
      execSync(`patch "${filePath}" "${tmpPatch}"`, { stdio: 'inherit' });
      console.log(chalk.green('✅ Patch applied.'));
    } catch (e) {
      console.error(chalk.red('❌ patch failed – diff saved to ' + tmpPatch));
    }
  } else {
    // Back up original
    const backup = filePath + '.copilot.bak';
    await fsPromises.copyFile(filePath, backup);
    console.log(chalk.dim(` Backup saved → ${backup}`));

    await fsPromises.writeFile(filePath, result, 'utf8');
    console.log(chalk.green(`✅ File updated: ${filePath}`));
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. RUN
// ─────────────────────────────────────────────────────────────────────────────
function handleRun(argv) {
  // Reconstruct the full command string from positional + remaining args
  const parts = [argv.command, ...(argv.args ?? [])];
  const cmd = parts.join(' ');

  console.log(chalk.bold(`\n▶ Running: ${chalk.cyan(cmd)}\n`));

  try {
    // Use spawn so output is streamed live
    const proc = spawn(cmd, { shell: true, stdio: 'inherit' });
    proc.on('close', (code) => {
      if (code === 0) {
        console.log(chalk.green('\n✅ Command finished successfully.'));
      } else {
        console.log(chalk.red(`\n❌ Command exited with code ${code}.`));
        process.exit(code);
      }
    });
  } catch (e) {
    console.error(chalk.red(`❌ Failed to run command: ${e.message}`));
    process.exit(1);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. UPGRADE
// ─────────────────────────────────────────────────────────────────────────────
function handleUpgrade(argv) {
  console.log(chalk.bold('\n🔄 Upgrading copilot-cli…\n'));

  if (!argv.npm) {
    // Try git pull first
    try {
      execSync('git rev-parse --is-inside-work-tree', { stdio: 'ignore' });
      console.log(chalk.dim(' Running: git pull'));
      execSync('git pull', { stdio: 'inherit' });
      console.log(chalk.green('\n✅ Updated via git pull.'));
      return;
    } catch (_) {
      console.log(chalk.dim(' Not a git repo – falling back to npm…'));
    }
  }

  // npm fallback
  try {
    console.log(chalk.dim(' Running: npm update -g copilot-cli'));
    execSync('npm update -g copilot-cli', { stdio: 'inherit' });
    console.log(chalk.green('\n✅ Updated via npm.'));
  } catch (e) {
    console.error(chalk.red(`❌ npm update failed: ${e.message}`));
    process.exit(1);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. CONFIG
// ─────────────────────────────────────────────────────────────────────────────
function handleConfig(argv) {
  const cfg = loadConfig();
  const { key, value } = argv;

  const VALID_KEYS = ['model', 'temperature', 'topP', 'maxTokens'];

  if (!key) {
    // Show all
    console.log(chalk.bold('\n⚙️ Current configuration:\n'));
    for (const [k, v] of Object.entries(cfg)) {
      console.log(` ${chalk.cyan(k.padEnd(14))} = ${chalk.yellow(v)}`);
    }
    console.log(chalk.dim(`\n Stored at: ${CONFIG_PATH}\n`));
    return;
  }

  if (!VALID_KEYS.includes(key)) {
    console.error(
      chalk.red(`❌ Unknown key "${key}". Valid keys: ${VALID_KEYS.join(', ')}`)
    );
    process.exit(1);
  }

  if (value === undefined) {
    // Get single key
    console.log(`${chalk.cyan(key)} = ${chalk.yellow(cfg[key])}`);
    return;
  }

  // Set
  const coerced =
    key === 'temperature' || key === 'topP'
      ? parseFloat(value)
      : key === 'maxTokens'
      ? parseInt(value, 10)
      : value;

  cfg[key] = coerced;
  saveConfig(cfg);
  console.log(chalk.green(`✅ ${key} set to ${coerced}`));
}

// ─────────────────────────────────────────────────────────────────────────────
// yargs wiring
// ─────────────────────────────────────────────────────────────────────────────
yargs(hideBin(process.argv))
  .scriptName('copilot')
  .usage('Usage: $0 <command> [options]')

  // ── suggest ────────────────────────────────────────────────────────────────
  .command(
    'suggest <file> <prompt>',
    'Get an inline code suggestion at a given cursor position',
    (y) => {
      y.positional('file', { describe: 'Target file', type: 'string' });
      y.positional('prompt', { describe: 'What you want the code to do', type: 'string' });
      y.option('line', { alias: 'l', describe: 'Cursor line (1-based)', type: 'number', default: 1 });
      y.option('col', { alias: 'c', describe: 'Cursor col (0-based)', type: 'number', default: 0 });
      y.option('model', { alias: 'm', describe: 'Model override', type: 'string' });
      y.option('temperature', { alias: 't', describe: 'Temperature', type: 'number' });
      y.option('top-p', { alias: 'p', describe: 'Top-p', type: 'number' });
      y.option('max-tokens', { alias: 'x', describe: 'Max tokens', type: 'number' });
    },
    handleSuggest
  )

  // ── edit ───────────────────────────────────────────────────────────────────
  .command(
    'edit <file> <prompt>',
    'Rewrite a file (or output a unified diff) based on a natural-language prompt',
    (y) => {
      y.positional('file', { describe: 'Target file', type: 'string' });
      y.positional('prompt', { describe: 'Instruction for the model',type: 'string' });
      y.option('model', { alias: 'm', describe: 'Model override', type: 'string' });
      y.option('temperature', { alias: 't', describe: 'Temperature', type: 'number' });
      y.option('top-p', { alias: 'p', describe: 'Top-p', type: 'number' });
      y.option('max-tokens', { alias: 'x', describe: 'Max tokens', type: 'number' });
      y.option('diff', { describe: 'Output a unified diff instead of full file', type: 'boolean' });
      y.option('yes', { alias: 'y', describe: 'Skip confirmation prompt', type: 'boolean' });
    },
    handleEdit
  )

  // ── run ────────────────────────────────────────────────────────────────────
  .command(
    'run <command> [args..]',
    'Run any shell command and stream its output',
    (y) => {
      y.positional('command', { describe: 'Shell command', type: 'string' });
    },
    handleRun
  )

  // ── upgrade ────────────────────────────────────────────────────────────────
  .command(
    'upgrade',
    'Upgrade this CLI (git pull, then npm update as fallback)',
    (y) => {
      y.option('npm', { describe: 'Force npm update instead of git pull', type: 'boolean' });
    },
    handleUpgrade
  )

  // ── config ─────────────────────────────────────────────────────────────────
  .command(
    'config [key] [value]',
    'Show or set persistent config (model, temperature, topP, maxTokens)',
    (y) => {
      y.positional('key', { describe: 'Config key to read/write', type: 'string' });
      y.positional('value', { describe: 'Value to set', type: 'string' });
    },
    handleConfig
  )

  .demandCommand(1, chalk.red('❌ Please specify a command. Run with --help for usage.'))
  .strict()
  .help()
  .alias('h', 'help')
  .version('1.0.0')
  .alias('v', 'version')
  .parse();