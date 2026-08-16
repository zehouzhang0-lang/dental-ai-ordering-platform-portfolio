import fs from 'node:fs'
import path from 'node:path'
import childProcess from 'node:child_process'

const root = process.cwd()
const ignoredWalkSegments = new Set(['.git', 'node_modules', 'target', 'dist'])
const forbiddenSegments = new Set([
  '.venv', '.codex-artifacts', 'artifacts', '.idea', '.netlify',
  '.gemini-designer', 'uploads', 'deployment-backups'
])
const forbiddenExtensions = new Set([
  '.7z', '.aiff', '.db', '.dcm', '.dicom', '.docx', '.key', '.log',
  '.mp3', '.mp4', '.otf', '.p12', '.pdf', '.pem', '.pfx', '.rar',
  '.sqlite', '.stl', '.ttc', '.wav', '.webm', '.xlsx', '.zip'
])
const textExtensions = new Set([
  '', '.conf', '.css', '.csv', '.html', '.http', '.java', '.js', '.json',
  '.lock', '.md', '.mjs', '.properties', '.rb', '.sh', '.sql', '.svg',
  '.ts', '.txt', '.vm', '.vue', '.xml', '.yaml', '.yml'
])

const secretRules = [
  ['private key block', /-----BEGIN [A-Z ]*PRIVATE KEY-----/],
  ['GitHub token', /(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{30,})/],
  ['OpenAI-style API key', /\bsk-[A-Za-z0-9_-]{20,}\b/],
  ['AWS access key', /\bAKIA[0-9A-Z]{16}\b/],
  ['Slack token', /\bxox[baprs]-[A-Za-z0-9-]{16,}\b/],
  ['Google API key', /\bAIza[A-Za-z0-9_-]{30,}\b/],
  ['JWT-like token', /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/],
  ['credential-bearing database URI', /\b(?:postgres(?:ql)?|mysql|redis|mongodb(?:\+srv)?):\/\/[^\s/@:]+:[^\s/@]+@/i],
  ['signed object URL', /[?&](?:X-Amz-(?:Algorithm|Credential|Signature|Security-Token)|GoogleAccessId|Signature)=/i]
]

const emailPattern = /\b[A-Z0-9._%+-]+@([A-Z0-9.-]+\.[A-Z]{2,})\b/gi
const phonePattern = /(?<!\d)1[3-9]\d{9}(?!\d)/
const cnIdPattern = /(?<!\d)\d{6}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx](?!\d)/
const ipv4Pattern = /(?<!\d)(?:\d{1,3}\.){3}\d{1,3}(?!\d)/g

const allowedEmailDomains = new Set([
  'example.com', 'example.org', 'example.net', 'example.test', 'test.invalid'
])

const findings = []

function relative(file) {
  return path.relative(root, file).split(path.sep).join('/')
}

function isAllowedIp(value) {
  const parts = value.split('.').map(Number)
  if (parts.some((part) => part < 0 || part > 255)) return true
  if (parts[0] === 10 || parts[0] === 127 || parts[0] === 0) return true
  if (parts[0] === 192 && parts[1] === 168) return true
  if (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31) return true
  if (parts[0] === 192 && parts[1] === 0 && parts[2] === 2) return true
  if (parts[0] === 198 && parts[1] === 51 && parts[2] === 100) return true
  if (parts[0] === 203 && parts[1] === 0 && parts[2] === 113) return true
  return false
}

function inspectFile(file) {
  const rel = relative(file)
  const ext = path.extname(file).toLowerCase()
  if (forbiddenExtensions.has(ext)) {
    findings.push(`${rel}: forbidden review artifact type ${ext}`)
    return
  }
  if (!textExtensions.has(ext)) return

  const text = fs.readFileSync(file, 'utf8')
  for (const [name, pattern] of secretRules) {
    if (pattern.test(text)) findings.push(`${rel}: ${name}`)
  }

  if (!rel.startsWith('vendor/')) {
    for (const match of text.matchAll(emailPattern)) {
      const domain = match[1].toLowerCase()
      if (!allowedEmailDomains.has(domain)) {
        findings.push(`${rel}: non-example email address`)
        break
      }
    }
    if (phonePattern.test(text)) findings.push(`${rel}: 11-digit phone-like value`)
    if (cnIdPattern.test(text)) findings.push(`${rel}: Chinese ID-like value`)
    for (const match of text.matchAll(ipv4Pattern)) {
      if (!isAllowedIp(match[0])) {
        findings.push(`${rel}: public IPv4-like value`)
        break
      }
    }
  }
}

function inspectTrackedPath(rel) {
  const segments = rel.split('/')
  const forbidden = segments.find((segment) => forbiddenSegments.has(segment))
  if (forbidden) {
    findings.push(`${rel}: forbidden tracked directory ${forbidden}`)
  }

  const base = segments.at(-1)
  if ((base === '.env' || base.startsWith('.env.')) && base !== '.env.example') {
    findings.push(`${rel}: local environment file`)
  }
}

function walk(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (entry.name === '.env') {
      findings.push(`${relative(path.join(directory, entry.name))}: local environment file`)
      continue
    }
    const full = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      if (forbiddenSegments.has(entry.name)) {
        findings.push(`${relative(full)}/: forbidden directory`)
        continue
      }
      if (!ignoredWalkSegments.has(entry.name)) walk(full)
      continue
    }
    inspectFile(full)
  }
}

if (fs.existsSync(path.join(root, '.git'))) {
  const tracked = childProcess.execFileSync(
    'git', ['ls-files', '-z'], { cwd: root, encoding: 'utf8' }
  ).split('\0').filter(Boolean)
  const trackedBytes = tracked.reduce(
    (sum, rel) => sum + fs.statSync(path.join(root, rel)).size, 0
  )
  if (trackedBytes > 25 * 1024 * 1024) {
    findings.push(`tracked tree: unexpectedly large (${trackedBytes} bytes)`)
  }
  for (const rel of tracked) {
    inspectTrackedPath(rel)
    inspectFile(path.join(root, rel))
  }
} else {
  walk(root)
}

if (findings.length) {
  console.error(`Security scan failed with ${findings.length} finding(s):`)
  for (const finding of findings) console.error(`- ${finding}`)
  process.exit(1)
}

console.log('Security scan passed: no forbidden artifacts or configured secret/PII patterns found.')
