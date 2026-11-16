// Local CI/CD: a registry, a kind Kubernetes cluster and Jenkins, all in Docker (nothing but Docker and Node is needed on the host).
//
//   node scripts/ci.mjs up                  start (or repair) everything; safe to run again
//   node scripts/ci.mjs down                remove Jenkins, the registry and the cluster (volumes and the Compose infra stay)
//   node scripts/ci.mjs status              what runs, the pods and the last build of the job
//   node scripts/ci.mjs set-repo <url> [branch]   point the Jenkins job at a Git address (a GitHub https URL, or /repo = this folder)
//
// Layout: infra (Oracle, Keycloak, OpenObserve, Mailpit) stays in the root docker-compose.yml; the app services run in the cluster
// "tbs" (manifests in deploy/k8s); Jenkins (ci/jenkins) builds from Git, pushes to localhost:5001 and deploys with kubectl.
// The Compose app containers use the same host ports (8080, 5173), so `up` stops them. Secrets are read from / generated into the
// git-ignored root .env and are never printed.
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync, appendFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ENV_FILE = `${ROOT}/.env`;
const IMAGE = 'tbs-ci/jenkins:2.580.2';
const CLUSTER = 'tbs';
const NODE = `${CLUSTER}-control-plane`;
const APP_NET = 'train-booking_default';
const APP_CONTAINERS = ['tbs-train-schedule', 'tbs-booking-seat', 'tbs-payment', 'tbs-ticket', 'tbs-notification', 'tbs-gateway', 'tbs-frontend'];
const JENKINS = 'http://127.0.0.1:9090';

// A pending fetch alone does not keep Node alive; this timer does until the process exits.
setInterval(() => {}, 1000);
const log = (m) => console.log(`[ci] ${m}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Runs a command and returns its stdout ('' on failure when quiet). `input` goes to stdin.
function run(cmd, args, { input, quiet = false, inherit = false } = {}) {
  const r = spawnSync(cmd, args, { cwd: ROOT, input, encoding: 'utf8', stdio: inherit ? 'inherit' : ['pipe', 'pipe', 'pipe'] });
  if (r.status !== 0 && !quiet) throw new Error(`${cmd} ${args.slice(0, 3).join(' ')} failed: ${(r.stderr || '').trim().split('\n').slice(-3).join(' | ')}`);
  return r.status === 0 ? (r.stdout || '').trim() : '';
}
const docker = (...a) => run('docker', a);
const dockerQ = (...a) => run('docker', a, { quiet: true });
const compose = (...a) => run('docker', ['compose', ...a], { inherit: true });
// Compose files: the application stack (root) and the CI stack (registry + Jenkins).
const ciCompose = (...a) => compose('-f', 'ci/docker-compose.yml', '--env-file', '.env', ...a);

// ---- .env helpers: values are kept in memory or written, never printed ----------------------------------------------------------
function readEnv() {
  if (!existsSync(ENV_FILE)) throw new Error('.env not found: copy .env.example to .env and fill it in first');
  return Object.fromEntries(readFileSync(ENV_FILE, 'utf8').split(/\r?\n/).filter((l) => /^[A-Z_0-9]+=/.test(l)).map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1)]));
}
function setEnv(key, value) {
  const text = readFileSync(ENV_FILE, 'utf8');
  const lines = text.split(/\r?\n/);
  const i = lines.findIndex((l) => l.startsWith(`${key}=`));
  if (i >= 0) { lines[i] = `${key}=${value}`; writeFileSync(ENV_FILE, lines.join('\n')); } else appendFileSync(ENV_FILE, `${text.endsWith('\n') ? '' : '\n'}${key}=${value}\n`);
}
function ensureEnv() {
  let env = readEnv();
  if (!env.JENKINS_ADMIN_USER) setEnv('JENKINS_ADMIN_USER', 'admin');
  if (!env.JENKINS_ADMIN_PASSWORD) { setEnv('JENKINS_ADMIN_PASSWORD', randomBytes(24).toString('base64url')); log('generated JENKINS_ADMIN_PASSWORD in .env'); }
  if (!env.JENKINS_GIT_URL) {
    // Default: this repository's origin, as an https address (the job polls it; a private repository needs GITHUB_TOKEN).
    const origin = run('git', ['remote', 'get-url', 'origin'], { quiet: true }).replace(/^git@github\.com:/, 'https://github.com/');
    if (!origin) throw new Error('no origin remote: run `node scripts/ci.mjs set-repo <url>`');
    setEnv('JENKINS_GIT_URL', origin);
  }
  env = readEnv();
  return env;
}

// ---- steps ----------------------------------------------------------------------------------------------------------------------
function checkMemory() {
  const gib = Number(docker('info', '--format', '{{.MemTotal}}')) / 2 ** 30;
  if (gib < 8) log(`WARNING: Docker has ${gib.toFixed(1)} GiB of memory; 8 GiB or more is advised (Docker Desktop > Settings > Resources). Expect slow builds or OOM kills.`);
  else log(`Docker memory: ${gib.toFixed(1)} GiB`);
}
function stopComposeApp() {
  const running = APP_CONTAINERS.filter((n) => dockerQ('inspect', '-f', '{{.State.Running}}', n) === 'true');
  if (!running.length) return;
  log(`the Compose app containers use ports 8080 and 5173, which the cluster needs: stopping ${running.join(', ')}`);
  log('(back to Compose later: node scripts/ci.mjs down, then docker compose --profile dev up -d)');
  docker('stop', ...running);
}
async function waitHealthy(name, ms = 300000) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (dockerQ('inspect', '-f', '{{.State.Health.Status}}', name) === 'healthy') return; await sleep(3000); }
  throw new Error(`${name} did not become healthy`);
}
// The kind CLI lives in the Jenkins image, so the host needs no install; it talks to Docker through the socket.
const kind = (args, input) => run('docker', ['run', '--rm', '-i', '--user', 'root', '-v', '/var/run/docker.sock:/var/run/docker.sock', '--entrypoint', 'kind', IMAGE, ...args], { input });
function ensureCluster() {
  if (!kind(['get', 'clusters']).split('\n').includes(CLUSTER)) {
    log('creating the kind cluster (first time: pulls the node image)');
    kind(['create', 'cluster', '--name', CLUSTER, '--config', '-', '--wait', '180s', '--kubeconfig', '/tmp/kubeconfig'], readFileSync(`${ROOT}/ci/kind-config.yaml`, 'utf8'));
  }
  // Pods reach Oracle, Keycloak, OpenObserve and Mailpit by their Compose names through the node's Docker DNS.
  dockerQ('network', 'connect', APP_NET, NODE);
  // Registry mirror (the standard containerd pattern): images named localhost:5001/... are pulled from the registry container.
  docker('exec', NODE, 'mkdir', '-p', '/etc/containerd/certs.d/localhost:5001');
  run('docker', ['exec', '-i', NODE, 'sh', '-c', 'cat > /etc/containerd/certs.d/localhost:5001/hosts.toml'], { input: '[host."http://kind-registry:5000"]\n' });
}
async function waitJenkins(ms = 300000) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    try { if ((await fetch(`${JENKINS}/login`)).status === 200) return; } catch { /* not up yet */ }
    await sleep(3000);
  }
  throw new Error('Jenkins did not start; see: docker logs tbs-jenkins');
}
// The internal kubeconfig points at the node's name on the kind network, which the Jenkins container has joined.
function writeKubeconfig() {
  docker('exec', 'tbs-jenkins', 'sh', '-c', `mkdir -p /var/jenkins_home/.kube && kind get kubeconfig --internal --name ${CLUSTER} > /var/jenkins_home/.kube/config && chmod 600 /var/jenkins_home/.kube/config`);
}
// The Stripe CLI of the dev profile forwarded events to the Compose gateway; while the cluster runs, send them to the cluster's Gateway.
function stripeForwarder(env) {
  if (!env.STRIPE_SECRET_KEY) return log('no STRIPE_SECRET_KEY: Stripe CLI forwarder not started');
  dockerQ('rm', '-f', 'tbs-stripe-cli');
  docker('compose', '--profile', 'dev', 'run', '-d', '--no-deps', '--name', 'tbs-stripe-cli', 'stripe-cli', 'listen',
    '--forward-to', `http://${NODE}:30080/webhooks/payment`,
    '--events', 'payment_intent.succeeded,payment_intent.processing,payment_intent.payment_failed,payment_intent.canceled,refund.updated,refund.failed');
  log('Stripe CLI forwards to the cluster Gateway');
}

async function up() {
  checkMemory();
  const env = ensureEnv();
  stopComposeApp();
  log('starting the infra (Oracle, Keycloak, OpenObserve, Mailpit)');
  compose('--profile', 'dev', 'up', '-d', 'oracle', 'keycloak', 'openobserve', 'mailpit');
  await waitHealthy('tbs-oracle');
  await waitHealthy('tbs-keycloak');
  log('building the Jenkins image (the first time takes a few minutes)');
  docker('build', '-t', IMAGE, 'ci/jenkins');
  ensureCluster();
  log('starting the registry and Jenkins');
  ciCompose('up', '-d', '--no-build');
  await waitJenkins();
  writeKubeconfig();
  stripeForwarder(env);
  log(`ready: Jenkins ${JENKINS} (user ${env.JENKINS_ADMIN_USER}, password in .env as JENKINS_ADMIN_PASSWORD); the app is at http://localhost:5173 once a build has deployed`);
}
function down() {
  ciCompose('down');
  dockerQ('rm', '-f', 'tbs-stripe-cli');
  if (kind(['get', 'clusters']).split('\n').includes(CLUSTER)) kind(['delete', 'cluster', '--name', CLUSTER]);
  log('stopped Jenkins, the registry and the cluster (volumes and the Compose infra remain). Compose app: docker compose --profile dev up -d');
}
async function status() {
  const env = readEnv();
  log(docker('ps', '-a', '--filter', 'name=tbs-jenkins', '--filter', 'name=kind-registry', '--filter', `name=${NODE}`, '--format', '{{.Names}}: {{.Status}}').replace(/\n/g, '\n[ci] '));
  console.log(dockerQ('exec', 'tbs-jenkins', 'kubectl', 'get', 'pods', '-n', 'tbs') || '[ci] no pods (cluster or Jenkins down)');
  try {
    const auth = `Basic ${Buffer.from(`${env.JENKINS_ADMIN_USER}:${env.JENKINS_ADMIN_PASSWORD}`).toString('base64')}`;
    const j = await (await fetch(`${JENKINS}/job/train-booking/api/json?tree=lastBuild[number,result,building]`, { headers: { Authorization: auth } })).json();
    log(`job train-booking: last build ${JSON.stringify(j.lastBuild)}; repository ${env.JENKINS_GIT_URL}`);
  } catch { log('Jenkins job not reachable'); }
}
async function setRepo(url, branch) {
  if (!url) throw new Error('usage: set-repo <url> [branch]');
  ensureEnv();
  setEnv('JENKINS_GIT_URL', url);
  if (branch) setEnv('JENKINS_GIT_BRANCH', branch);
  // JCasC reads the address from the container environment, so the container is recreated (the Jenkins home volume keeps its data).
  ciCompose('up', '-d', '--no-build', 'jenkins');
  await sleep(5000);
  await waitJenkins();
  writeKubeconfig();
  log(`job now polls ${url}${branch ? ` (${branch})` : ''}`);
}

const [cmd, ...rest] = process.argv.slice(2);
try {
  if (cmd === 'up') await up();
  else if (cmd === 'down') down();
  else if (cmd === 'status') await status();
  else if (cmd === 'set-repo') await setRepo(rest[0], rest[1]);
  else { console.log('usage: node scripts/ci.mjs up | down | status | set-repo <url> [branch]'); process.exit(2); }
} catch (e) { console.error(`[ci] ERROR: ${e.message}`); process.exit(1); }
process.exit(0);
