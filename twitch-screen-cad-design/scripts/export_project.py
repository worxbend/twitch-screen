"""Run inside FreeCADCmd. Exact solids and real meshes, never renamed formats."""
import json
import zipfile
import xml.etree.ElementTree as ET
import FreeCAD as App
import Part
import Mesh
import MeshPart
import Import
from pod_geometry import face_transform, usb_placement, box, V
from pod_document import ROOT, DEFAULTS, create_document, geometry

OUT = ROOT/'output'
NS = 'http://schemas.microsoft.com/3dmanufacturing/core/2015/02'


def mesh(shape):
    return MeshPart.meshFromShape(Shape=shape,LinearDeflection=.035,
                                 AngularDeflection=.12,Relative=False)


def write_3mf(path, named_meshes):
    ET.register_namespace('',NS)
    model = ET.Element('{'+NS+'}model',{'unit':'millimeter','{http://www.w3.org/XML/1998/namespace}lang':'en-US'})
    resources = ET.SubElement(model,'resources')
    build = ET.SubElement(model,'build')
    for i,(name,m) in enumerate(named_meshes.items(),1):
        obj = ET.SubElement(resources,'object',{'id':str(i),'type':'model','name':name})
        msh = ET.SubElement(obj,'mesh')
        vertices = ET.SubElement(msh,'vertices')
        triangles = ET.SubElement(msh,'triangles')
        pts,tris = m.Topology
        for pt in pts:
            ET.SubElement(vertices,'vertex',{a:format(v,'.9g') for a,v in zip('xyz',pt)})
        for tri in tris:
            ET.SubElement(triangles,'triangle',{f'v{j+1}':str(v) for j,v in enumerate(tri)})
        ET.SubElement(build,'item',{'objectid':str(i)})
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml','<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>')
        z.writestr('_rels/.rels','<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>')
        z.writestr('3D/3dmodel.model',ET.tostring(model,encoding='utf-8',xml_declaration=True))


def print_shape(name,shape,p):
    s = shape.copy()
    if name in ('shell','lcd_retainer','face_bezel'):
        s.Placement = face_transform(p).inverse().multiply(s.Placement)
        if name == 'shell':
            s.rotate(V(0,0,0),V(1,0,0),180)
    bounds = mesh(s).BoundBox
    s.translate(V(-bounds.XMin,-bounds.YMin,-bounds.ZMin))
    return s


def validate(parts,hw,refs,p):
    result = {'solid_checks':{},'collisions_mm3':{},'clearances_mm':{}}
    for name,s in parts.items():
        assert s.isValid() and len(s.Solids)==1 and s.Volume>100, name
        m = mesh(s)
        assert m.isSolid(), 'Open mesh: '+name
        result['solid_checks'][name] = {'valid':True,'solids':len(s.Solids),
            'volume_mm3':s.Volume,'mesh_volume_mm3':m.Volume,'triangles':m.CountFacets,
            'size_mm':[m.BoundBox.XLength,m.BoundBox.YLength,m.BoundBox.ZLength]}
        assert abs(m.Volume-s.Volume)/s.Volume < .005, name
    for i,(a,sa) in enumerate(parts.items()):
        for b,sb in list(parts.items())[i+1:]+list(hw.items())+[('header_housings',refs['header_housings'])]:
            vol = sa.common(sb).Volume
            result['collisions_mm3'][a+' / '+b] = vol
            assert vol < .001, (a,b,vol)
    u = usb_placement(p)
    assert u.y > p['EspCenterY'] and u.y > 0
    overlap = parts['shell'].common(refs['usb_keepout']).Volume
    assert overlap < .001
    result['usb'] = {'connector_mating_centre_mm':list(u),'insertion_axis':[0,1,0],
                     'shell_overlap_mm3':overlap,'front_port':False}
    # Probe the front wall at connector height to independently reject a through-slot.
    front = box(6,50,3,-3,-50,u.z-1.5)
    assert parts['shell'].common(front).Volume > 1
    result['usb']['front_wall_probe_volume_mm3'] = parts['shell'].common(front).Volume
    result['clearances_mm']['header_housing_to_base'] = refs['header_housings'].distToShape(parts['base'])[0]
    return result


def main():
    p = DEFAULTS
    for sub in ('step','stl','3mf','freecad','preview','render_meshes','reports'):
        (OUT/sub).mkdir(parents=True,exist_ok=True)
    parts,hw,refs = geometry(p)
    report = validate(parts,hw,refs,p)
    doc = create_document()
    objects = {o.PartKey:o for o in doc.Objects if hasattr(o,'PartKey')}
    print_meshes = {}
    render_config = {}
    for name,s in {**parts,**hw}.items():
        m = mesh(s)
        m.write(str(OUT/'render_meshes'/f'{name}.stl'))
        render_config[name] = {'file':f'render_meshes/{name}.stl','hardware':name in hw}
    for name,s in parts.items():
        ps = print_shape(name,s,p)
        pm = mesh(ps)
        print_meshes[name] = pm
        pm.write(str(OUT/'stl'/f'{name}.stl'))
        write_3mf(OUT/'3mf'/f'{name}.3mf',{name:pm})
        Import.export([objects[name]],str(OUT/'step'/f'{name}.step'))
        individual = create_document(p,'part_'+name,only=name)
        individual.saveAs(str(OUT/'freecad'/f'{name}.FCStd'))
        App.closeDocument(individual.Name)
    # Print plate: already oriented and separated, with only printable solids.
    plate = {}
    offset = 0
    row_y = 0
    row_depth = 0
    for name,pm in print_meshes.items():
        pm = Mesh.Mesh(pm)
        if offset+pm.BoundBox.XLength > 200:
            row_y += row_depth+10
            offset = 0
            row_depth = 0
        pm.translate(offset,row_y,0)
        plate[name] = pm
        offset += pm.BoundBox.XLength+10
        row_depth = max(row_depth,pm.BoundBox.YLength)
    write_3mf(OUT/'3mf'/'print_plate.3mf',plate)
    assembly = {**parts,**hw}
    Import.export(list(objects.values()),str(OUT/'step'/'TwitchScreen_assembly.step'))
    assembly_mesh = Mesh.Mesh()
    for shape in assembly.values():
        assembly_mesh.addMesh(mesh(shape))
    assembly_mesh.write(str(OUT/'stl'/'TwitchScreen_assembly_view_only.stl'))
    write_3mf(OUT/'3mf'/'TwitchScreen_assembly_view_only.3mf',
              {n:mesh(s) for n,s in assembly.items()})
    doc.saveAs(str(OUT/'freecad'/'TwitchScreen.FCStd'))
    App.closeDocument(doc.Name)
    reopened = App.openDocument(str(OUT/'freecad'/'TwitchScreen.FCStd'))
    for obj in reopened.Objects:
        if hasattr(obj,'PartKey'):
            assert obj.Shape.isValid() and not obj.Shape.isNull(), obj.Name
    report['freecad_reopen'] = True
    App.closeDocument(reopened.Name)
    report['step_roundtrip'] = {}
    for name,s in parts.items():
        loaded = Part.Shape()
        loaded.read(str(OUT/'step'/f'{name}.step'))
        assert loaded.isValid() and len(loaded.Solids)==1
        assert abs(loaded.Volume-s.Volume)/s.Volume < 1e-5
        report['step_roundtrip'][name] = True
    report['parameters'] = p
    report['physical_fit_status'] = 'Nominal model verified; measure exact clone hardware before fabrication.'
    (OUT/'reports'/'geometry_validation.json').write_text(json.dumps(report,indent=2))
    (OUT/'render_scene.json').write_text(json.dumps(render_config,indent=2))
    print('EXPORT_COMPLETE',json.dumps(report['solid_checks']),flush=True)


if __name__ == '__main__':
    main()
