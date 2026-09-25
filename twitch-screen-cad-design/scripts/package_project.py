"""Build a local gallery, checksummed manifest and complete portable delivery ZIP."""
import hashlib
import json
import struct
import zipfile
from pathlib import Path
from render_settings import PREVIEW_SIZE

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'output'
PREVIEWS = ['01_front_hero','02_rear_usb','03_side','04_front','05_section',
            '06_exploded','part_shell_interior','part_base','part_lcd_retainer','part_face_bezel']


def main():
    for stem in PREVIEWS:
        path = OUT/'preview'/f'{stem}.png'
        data = path.read_bytes()
        assert data[:8] == b'\x89PNG\r\n\x1a\n' and len(data)>10000
        assert struct.unpack('>II',data[16:24]) == PREVIEW_SIZE
    for stem in ('geometry','mesh','assembly','wall'):
        json.loads((OUT/'reports'/f'{stem}_validation.json').read_text())
    rows = []
    for name in ('shell','base','lcd_retainer','face_bezel'):
        links = ' · '.join(f'<a href="{folder}/{name}.{ext}">{label}</a>'
            for folder,ext,label in [('freecad','FCStd','FreeCAD'),('step','step','STEP'),
                                     ('stl','stl','STL'),('3mf','3mf','3MF')])
        rows.append(f'<tr><td>{name.replace("_"," ").title()}</td><td>{links}</td></tr>')
    cards = ''.join(f'<figure><a href="preview/{s}.png"><img src="preview/{s}.png" alt="{s.replace("_"," ")}"></a><figcaption>{s.replace("_"," ")}</figcaption></figure>' for s in PREVIEWS)
    page = '''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TwitchScreen — CAD delivery</title><style>
body{font:17px/1.6 system-ui,sans-serif;background:#f4f2ec;color:#24272c;max-width:1150px;margin:40px auto;padding:0 24px}h1{font-size:44px;letter-spacing:-1.5px;margin-bottom:0}a{color:#6046a0}table{border-collapse:collapse;width:100%;background:#fff}td,th{padding:14px 18px;text-align:left;border-bottom:1px solid #ddd}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:20px}figure{margin:0;background:white;border-radius:8px;overflow:hidden}img{display:block;width:100%;height:auto}figcaption{padding:10px 16px;font-size:14px}.note{padding:16px 20px;background:#e8e2d4;border-radius:8px}</style>
<h1>TwitchScreen</h1><p>Round display pod · ESP32 Type-C · 64 × 86 × 78.1 mm · 55° face</p>
<p><a href="freecad/TwitchScreen.FCStd">Editable FreeCAD assembly</a> · <a href="step/TwitchScreen_assembly.step">STEP assembly</a> · <a href="3mf/print_plate.3mf">3MF print plate</a> · <a href="../README.md">Build &amp; assembly guide</a></p>
<p class="note">Nominal CAD is verified. Measure the exact clone board, LCD stack and connectors before printing: <a href="../docs/measurement_checklist.md">measurement checklist</a>. Four hardware photos and five concept screenshots informed the design; PLAN.md overrides the conflicting front USB placement.</p>
<h2>Printed parts</h2><table><tr><th>Part</th><th>Formats</th></tr>'''+''.join(rows)+'''</table>
<p>STL/3MF parts are oriented for printing; STEP/FreeCAD retain assembly coordinates. Assembly files marked “view_only” include hardware envelopes.</p>
<h2>CAD preview renders</h2><div class="grid">'''+cards+'''</div>
<h2>Verification</h2><p><a href="reports/geometry_validation.json">Solid geometry</a> · <a href="reports/mesh_validation.json">Written meshes</a> · <a href="reports/assembly_validation.json">Assembly &amp; parameters</a> · <a href="reports/wall_validation.json">Wall samples</a> · <a href="../docs/completion_audit.md">PLAN.md audit</a></p></html>'''
    (OUT/'index.html').write_text(page)
    roots = ['README.md','PLAN.md','parameters.json','TwitchScreen.FCMacro']
    paths = [ROOT/p for p in roots]
    paths += [p for p in ROOT.iterdir() if p.suffix.lower() in ('.jpg','.jpeg','.png','.webp')]
    for folder in ('scripts','docs','refs','output'):
        paths += [p for p in (ROOT/folder).rglob('*') if p.is_file()
                  and '__pycache__' not in p.parts and p.suffix not in ('.pyc','.blend1','.FCBak','.FCStd1')
                  and p.name != 'manifest.json']
    manifest = {'units':'millimetres','status':'nominal CAD verified; hardware measurements pending',
        'files':{str(p.relative_to(ROOT)):{'bytes':p.stat().st_size,
                  'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(paths)}}
    (OUT/'manifest.json').write_text(json.dumps(manifest,indent=2))
    paths.append(OUT/'manifest.json')
    archive = ROOT/'TwitchScreen-deliverables.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for path in paths:
            z.write(path,'TwitchScreen/'+str(path.relative_to(ROOT)))
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        assert len(z.namelist()) == len(paths)
    print(f'PACKAGED {len(paths)} files, {archive.stat().st_size:,} bytes: {archive}')


if __name__ == '__main__':
    main()
