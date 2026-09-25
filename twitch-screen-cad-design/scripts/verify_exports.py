"""Independent mesh/package audit. Run with uv run --with trimesh --with numpy."""
import json
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
import trimesh

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'output'
NS = {'m':'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'}


def read_3mf(path):
    with zipfile.ZipFile(path) as z:
        assert z.testzip() is None
        for item in ('[Content_Types].xml','_rels/.rels','3D/3dmodel.model'):
            assert item in z.namelist(), (path,item)
        model = ET.fromstring(z.read('3D/3dmodel.model'))
        assert model.attrib['unit']=='millimeter'
        meshes = {}
        for obj in model.findall('m:resources/m:object',NS):
            vertices = [[float(v.attrib[a]) for a in 'xyz']
                        for v in obj.findall('m:mesh/m:vertices/m:vertex',NS)]
            faces = [[int(t.attrib[f'v{i}']) for i in (1,2,3)]
                     for t in obj.findall('m:mesh/m:triangles/m:triangle',NS)]
            meshes[obj.attrib['name']] = trimesh.Trimesh(vertices,faces,process=True)
        assert len(model.findall('m:build/m:item',NS)) == len(meshes)
        return meshes


def main():
    cad = json.loads((OUT/'reports/geometry_validation.json').read_text())
    report = {'parts':{},'print_plate':{}}
    for name in ('shell','base','lcd_retainer','face_bezel'):
        stl = trimesh.load_mesh(OUT/'stl'/f'{name}.stl')
        mf = read_3mf(OUT/'3mf'/f'{name}.3mf')[name]
        for fmt,m in [('stl',stl),('3mf',mf)]:
            assert m.is_watertight and m.is_winding_consistent and m.is_volume, (name,fmt)
            assert len(m.split())==1, (name,fmt,'disconnected')
            assert abs(m.bounds[0,2]) < .002, (name,fmt,'not on bed')
            assert abs(m.volume/cad['solid_checks'][name]['volume_mm3']-1) < .005
        assert np.allclose(stl.bounds,mf.bounds,atol=1e-4)
        report['parts'][name] = {'watertight':True,'consistent_winding':True,
            'connected_components':1,'on_print_bed':True,'stl_matches_3mf':True,
            'volume_mm3':float(stl.volume),'print_extents_mm':stl.extents.tolist()}
    plate = read_3mf(OUT/'3mf/print_plate.3mf')
    assert set(plate)==set(report['parts'])
    for i,left in enumerate(plate.values()):
        for right in list(plate.values())[i+1:]:
            gap = np.maximum(left.bounds[0,:2]-right.bounds[1,:2],right.bounds[0,:2]-left.bounds[1,:2])
            assert np.max(gap) >= 9.99
    report['print_plate'] = {'objects':list(plate),'separation_at_least_mm':9.99,
        'extents_mm':(np.max([m.bounds[1] for m in plate.values()],axis=0)-
                      np.min([m.bounds[0] for m in plate.values()],axis=0)).tolist()}
    assembly = read_3mf(OUT/'3mf/TwitchScreen_assembly_view_only.3mf')
    assert {'shell','base','lcd_retainer','usb_socket','esp32_pcb','lcd_pcb'} <= set(assembly)
    report['assembly_3mf_objects'] = list(assembly)
    assembly_stl = trimesh.load_mesh(OUT/'stl/TwitchScreen_assembly_view_only.stl')
    combined = trimesh.util.concatenate(list(assembly.values()))
    assert len(assembly_stl.faces) == len(combined.faces)
    assert np.allclose(assembly_stl.bounds,combined.bounds,atol=1e-4)
    assert abs(assembly_stl.volume/combined.volume-1) < 1e-5
    report['assembly_stl_matches_3mf'] = True
    for path in (OUT/'freecad').glob('*.FCStd'):
        with zipfile.ZipFile(path) as z:
            assert z.testzip() is None and 'Document.xml' in z.namelist()
    (OUT/'reports/mesh_validation.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))


if __name__ == '__main__':
    main()
