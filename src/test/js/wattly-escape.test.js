// Run with: node --test src/test/js/wattly-escape.test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/wattly.js'), 'utf8');
const document = {
  createElement() {
    let text = '';
    return {
      set textContent(value) { text = value; },
      get innerHTML() {
        return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      }
    };
  }
};
const escape = vm.runInNewContext(source + '\nWattly.escape;', { document });

test('names cannot introduce attributes when inserted into quoted labels', () => {
  const name = 'Dryer" onmouseover="alert(1)';
  const label = '<button aria-label="Edit ' + escape(name) + '">Edit</button>';

  assert.equal(escape(name), 'Dryer&quot; onmouseover=&quot;alert(1)');
  assert.doesNotMatch(label, / onmouseover="/);
});

test('ordinary text and both quote styles remain readable', () => {
  assert.equal(escape('A & B < C > D'), 'A &amp; B &lt; C &gt; D');
  assert.equal(escape("O'Neil \"Home\""), 'O&#39;Neil &quot;Home&quot;');
  assert.equal(escape(null), '');
});
