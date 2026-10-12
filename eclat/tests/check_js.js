// The pages must run on Android 8's built-in WebView (Chrome 58): ES2017
// syntax only (no ?? ?. {...spread}, no catch without (e)).
// node check_js.js <html or js files...>
const acorn = require(process.env.ACORN || 'acorn');
const fs = require('fs');
let bad = 0;
for (const f of process.argv.slice(2)) {
  let src = fs.readFileSync(f, 'utf8');
  const parts = f.endsWith('.html') ? [...src.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1]) : [src];
  for (const p of parts) {
    try { acorn.parse(p, { ecmaVersion: 2017, sourceType: 'script' }); }
    catch (e) { console.log(`FAIL ${f}: ${e.message}`); bad++; }
  }
  if (!bad) console.log(`OK   ${f}`);
}
process.exit(bad ? 1 : 0);
