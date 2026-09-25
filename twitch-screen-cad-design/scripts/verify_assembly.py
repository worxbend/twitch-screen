"""FreeCAD assembly, access and editable-document checks on the delivered model."""
import json
import FreeCAD as App
import Part
from pod_document import ROOT, DEFAULTS, geometry
from pod_geometry import (V, box, cylinder, at_face, pcb_holes, screw_centres,
                          shell_vents, BASE_VENT_Y, BASE_HARDWARE, LCD_HARDWARE)


def main():
    p = DEFAULTS
    parts,hw,refs = geometry(p)
    report = {}
    # Read the full assembly STEP as well as each per-part export.
    assembly_step = Part.Shape()
    assembly_step.read(str(ROOT/'output/step/TwitchScreen_assembly.step'))
    expected = Part.makeCompound(list(parts.values())+list(hw.values()))
    assert assembly_step.isValid()
    assert len(assembly_step.Solids) == len(expected.Solids)
    assert abs(assembly_step.Volume/expected.Volume-1) < 1e-5
    report['assembly_step_roundtrip_solids'] = len(assembly_step.Solids)
    # The cavity must remain wholly enclosed above the open underside.
    outside = refs['inner'].cut(refs['outer']).common(box(250,250,150,-125,-125,3.001))
    report['cavity_outside_outer_mm3'] = outside.Volume
    assert outside.Volume < .001
    vents = shell_vents(p)
    assert len(vents) == 21
    for vent in vents:
        assert vent.common(parts['shell']).Volume < .001
        assert vent.common(refs['outer']).Volume > 1 and vent.common(refs['inner']).Volume > 1
    assert len(BASE_VENT_Y) == 6
    for y in BASE_VENT_Y:
        # Independent smaller rectangular probe, not the construction solid.
        assert box(17,2,p['BaseThickness']+2,-8.5,y-1,-1).common(parts['base']).Volume < .001
    report['open_vents'] = {'side':18,'rear':3,'base':6}
    # Check actual screw head envelopes; threaded shanks intentionally engage bosses.
    back = -p['LcdRecess']-p['LcdGlassThickness']-p['LcdPcbThickness']
    heads = {
        'esp32':Part.makeCompound([cylinder(1.9,1.6,x,y,p['EspBoardZ']+p['EspThickness'])
                                   for x,y in pcb_holes(p)]),
        'base':Part.makeCompound([cylinder(2.5,1.2,x,y,.1) for x,y in screw_centres(p)]),
        'retainer':at_face(Part.makeCompound([cylinder(1.9,1.3,x,0,back-3.7)
                                             for x in (-22.5,22.5)]),p),
    }
    report['screw_head_collisions_mm3'] = {}
    for group,heads_shape in heads.items():
        for name,shape in {**parts,**hw}.items():
            overlap = heads_shape.common(shape).Volume
            report['screw_head_collisions_mm3'][group+'/'+name] = overlap
            assert overlap < .001, (group,name,overlap)
    report['hardware_pair_collisions_mm3'] = {}
    for i,(a,sa) in enumerate(hw.items()):
        for b,sb in list(hw.items())[i+1:]:
            overlap = sa.common(sb).Volume
            report['hardware_pair_collisions_mm3'][a+'/'+b] = overlap
            assert overlap < .001, (a,b,overlap)
    # Straight downward extraction of the assembled base, PCB and headers.
    base_group = Part.makeCompound([parts['base']]+[hw[name] for name in BASE_HARDWARE])
    sweep = {}
    # Discrete 1 mm samples improve coverage; they cannot prove continuous clearance.
    report['extraction_sampling'] = {'step_mm':1, 'continuous_clearance_proven':False}
    for travel in range(1,36):
        shifted = base_group.copy()
        shifted.translate(V(0,0,-travel))
        overlap = shifted.common(parts['shell']).Volume
        sweep[str(travel)] = overlap
        assert overlap < .001, ('base extraction',travel,overlap)
    report['base_extraction_collisions_mm3'] = sweep
    # LCD insertion along the face normal (retract into the open cavity).
    lcd_group = Part.makeCompound([hw[n] for n in LCD_HARDWARE])
    normal = App.Rotation(V(1,0,0),p['FaceAngle']).multVec(V(0,0,1))
    report['lcd_retraction_collisions_mm3'] = {}
    for travel in range(1,16):
        shifted = lcd_group.copy()
        shifted.translate(normal*(-travel))
        overlap = shifted.common(parts['shell']).Volume
        report['lcd_retraction_collisions_mm3'][str(travel)] = overlap
        assert overlap < .001, ('lcd insertion',travel,overlap)
    # Reopen each real FCStd, not just the zip structure.
    report['freecad_files'] = {}
    for path in (ROOT/'output/freecad').glob('*.FCStd'):
        doc = App.openDocument(str(path))
        feats = [o for o in doc.Objects if hasattr(o,'PartKey')]
        assert feats and all(o.Shape.isValid() for o in feats)
        report['freecad_files'][path.name] = len(feats)
        if path.stem == 'TwitchScreen':
            before = doc.usb_socket.Shape.BoundBox.Center.x
            original_usb = doc.Parameters.UsbCenterX
            original_angle = doc.Parameters.FaceAngle
            try:
                doc.Parameters.UsbCenterX = original_usb + 2.0
                doc.recompute()
                after = doc.usb_socket.Shape.BoundBox.Center.x
                assert abs(after-before-2) < 1e-6
                assert doc.shell.Shape.isValid()
                report['editable_usb_offset_delta_mm'] = after-before
                doc.Parameters.FaceAngle = 52
                doc.recompute()
                assert all(o.Shape.isValid() for o in feats)
                report['editable_face_angle_recompute'] = True
            finally:
                doc.Parameters.UsbCenterX = original_usb
                doc.Parameters.FaceAngle = original_angle
                doc.recompute()
        App.closeDocument(doc.Name)  # Do not save the test mutations.
    (ROOT/'output/reports/assembly_validation.json').write_text(json.dumps(report,indent=2))
    print('ASSEMBLY_VALIDATED',json.dumps(report),flush=True)


if __name__ == '__main__':
    main()
