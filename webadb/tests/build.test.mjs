import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = fileURLToPath(new URL('../', import.meta.url));
test('build emits relative, hashed browser assets without exposing private environment values', async () => {
  const directory = await mkdtemp(resolve(tmpdir(), 'yidream-web-build-'));
  try {
    const result = spawnSync(process.execPath, ['scripts/build.mjs', '--outdir', directory], {
      cwd: root, encoding: 'utf8', env: { ...process.env, PRIVATE_TEST_SENTINEL: 'never-bundle-this-private-value' }
    });
    assert.equal(result.status, 0, result.stderr);
    const html = await readFile(resolve(directory, 'index.html'), 'utf8');
    assert.doesNotMatch(html, /src="\.\/src\//);
    const script = html.match(/src="(\.\/assets\/suite-[A-Z0-9]+\.js)"/);
    assert.ok(script, 'hashed module path stays relative to the Pages directory');
    await access(resolve(directory, script[1]));
    for (const entry of await readdir(resolve(directory, 'assets'))) {
      if (!entry.endsWith('.js')) continue;
      assert.doesNotMatch(await readFile(resolve(directory, 'assets', entry), 'utf8'), /never-bundle-this-private-value/);
    }
    await access(resolve(directory, 'assets/yidream-logo.png'));
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test('build refuses to erase its source directory', () => {
  const result = spawnSync(process.execPath, ['scripts/build.mjs', '--outdir', root], { cwd: root, encoding: 'utf8' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /separate temporary output directory/);
});
