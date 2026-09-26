"""Persistent editable FreeCAD features. Open via TwitchScreen.FCMacro for recompute."""
import json
from pathlib import Path
import FreeCAD as App
from pod_geometry import build

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS = json.loads((ROOT/'parameters.json').read_text())
_cache = {}


def parameter_values(obj):
    return {k: float(getattr(obj,k)) for k in DEFAULTS}


def geometry(params):
    key = tuple(sorted(params.items()))
    if key not in _cache:
        _cache.clear()
        _cache[key] = build(params)
    return _cache[key]


class PodFeature:
    def __init__(self, obj, params, name, hardware=False):
        obj.addProperty('App::PropertyLink','Parameters','Design').Parameters = params
        obj.addProperty('App::PropertyString','PartKey','Design').PartKey = name
        obj.addProperty('App::PropertyBool','HardwareReference','Design').HardwareReference = hardware
        obj.Proxy = self

    def execute(self, obj):
        parts, hardware, _ = geometry(parameter_values(obj.Parameters))
        obj.Shape = (hardware if obj.HardwareReference else parts)[obj.PartKey]


def create_document(params=DEFAULTS, name='TwitchScreen', only=None):
    doc = App.newDocument(name)
    config = doc.addObject('App::FeaturePython','Parameters')
    config.Label = 'Dimensions (mm, angle in degrees) — measure clone hardware'
    for key,value in params.items():
        group = ('Hardware - MEASURE' if key.startswith(('Esp','Header','Usb','Lcd','Button','Cable'))
                 else 'Enclosure design')
        if key in ('LcdDiameter','LcdOverallLength','LcdTabWidth'):
            group = 'Hardware - Waveshare drawing'
        config.addProperty('App::PropertyFloat',key,group)
        setattr(config,key,value)
    config.addProperty('App::PropertyString','Instructions','Documentation')
    config.Instructions = 'Open with TwitchScreen.FCMacro; edit dimensions then recompute. See docs/measurement_checklist.md.'
    printed = doc.addObject('App::DocumentObjectGroup','PrintedParts')
    electronics = doc.addObject('App::DocumentObjectGroup','HardwareReferences')
    parts, hw, _ = geometry(params)
    for is_hw, shapes, group in [(False,parts,printed),(True,hw,electronics)]:
        for key in shapes:
            if only and key != only:
                continue
            obj = doc.addObject('Part::FeaturePython',key)
            obj.Label = key.replace('_',' ').title() + (' (reference)' if is_hw else '')
            PodFeature(obj,config,key,is_hw)
            group.addObject(obj)
    doc.recompute()
    return doc
