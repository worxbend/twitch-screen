"""Sample exact cavity/outer surface separation; this is not a print strength test."""
import json
import Part
from pod_document import ROOT, DEFAULTS, geometry


def main():
    _,_,refs = geometry(DEFAULTS)
    inner_faces = Part.makeCompound(refs['inner'].Faces)
    samples = []
    for face in refs['outer'].Faces:
        u0,u1,v0,v1 = face.ParameterRange
        for i in range(1,18):
            for j in range(1,10):
                point = face.valueAt(u0+(u1-u0)*i/18,v0+(v1-v0)*j/10)
                if point.z < 6 or face.distToShape(Part.Vertex(point))[0] > 1e-5:
                    continue
                gap = Part.Vertex(point).distToShape(inner_faces)[0]
                samples.append((gap,list(point)))
    if not samples:
        raise RuntimeError('Wall validation produced no samples above Z=6; check the body surfaces and sampling domain.')
    minimum = min(samples)
    report = {'samples':len(samples),'minimum_sampled_separation_mm':minimum[0],
              'minimum_location_mm':minimum[1],
              'scope':'Exact distance between underlying unperforated body and cavity surfaces at UV samples above Z=6; includes 1.5 mm structural bezel seat. Excludes deliberate vents, screw holes, added rim and 0.8 mm adhesive fascia. Not an exhaustive structural or print test.'}
    assert minimum[0] >= 1.45, report
    (ROOT/'output/reports/wall_validation.json').write_text(json.dumps(report,indent=2))
    print('WALLS_VALIDATED',json.dumps(report),flush=True)


if __name__ == '__main__':
    main()
