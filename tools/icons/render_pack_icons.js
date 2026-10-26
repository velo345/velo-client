// Renders the pack editor icons (Lucide, ISC) to textures/icon/packs: npm install lucide-static@0.469.0 @resvg/resvg-js@2.6.2
// next to this file, then: node render_pack_icons.js <out dir>
const fs = require('fs');
const path = require('path');
const { Resvg } = require('@resvg/resvg-js');
const out = process.argv[2];
const map = {
  pencil: 'pencil', brush: 'paintbrush', eraser: 'eraser', fill: 'paint-bucket', gradient: 'blend', line: 'slash',
  rect: 'square', ellipse: 'circle', picker: 'pipette', shade: 'sun-moon', dither: 'grip', select: 'square-dashed',
  select_ellipse: 'circle-dashed', lasso: 'lasso', wand: 'wand-sparkles', move: 'move', hand: 'hand', undo: 'undo-2',
  redo: 'redo-2', grid: 'grid-3x3', tile: 'layout-grid', mirror: 'flip-horizontal-2', play: 'play', pause: 'pause',
  stop: 'circle-stop', plus: 'plus', copy: 'copy', trash: 'trash-2', left: 'chevron-left', right: 'chevron-right',
  up: 'chevron-up', down: 'chevron-down', folder: 'folder-open', edit: 'pencil', reload: 'refresh-cw', rotate: 'rotate-cw',
  eye: 'eye', onion: 'layers', small: 'grid-3x3', medium: 'grid-2x2', large: 'square', music: 'music', sound: 'volume-2',
  file: 'file-text', code: 'file-code', json: 'braces', search: 'search', save: 'save', upload: 'upload', download: 'download',
  scissors: 'scissors', repeat: 'repeat', replace: 'replace', check: 'check', x: 'x', wrap: 'wrap-text', format: 'align-left',
  waves: 'audio-lines', image: 'image', import: 'file-up', export: 'file-down', info: 'info', speed: 'gauge'
};
for (const [name, icon] of Object.entries(map)) {
  let svg = fs.readFileSync(path.join(__dirname, 'node_modules/lucide-static/icons', icon + '.svg'), 'utf8');
  svg = svg.replace(/currentColor/g, '#ffffff').replace('<!--', '<!--').replace(/class="[^"]*"/, '');
  const png = new Resvg(svg, { fitTo: { mode: 'width', value: 64 }, background: 'rgba(0,0,0,0)' }).render().asPng();
  fs.writeFileSync(path.join(out, name + '.png'), png);
  fs.writeFileSync(path.join(out, name + '.png.mcmeta'), '{\n  "texture": {\n    "blur": true\n  }\n}\n');
}
console.log(Object.keys(map).length + ' icons');
