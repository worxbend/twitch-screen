"""Millimetre BRep geometry; only FreeCAD's built-in Python modules are required."""
import FreeCAD as App
import Part

V = App.Vector
BASE_VENT_Y = (-8, -3, 2, 7, 12, 17)
BASE_HARDWARE = ('esp32_pcb', 'esp32_shield', 'esp32_headers', 'buttons', 'usb_socket', 'rubber_feet')
LCD_HARDWARE = ('lcd_pcb', 'lcd_glass', 'lcd_connector', 'lcd_brass_mounts')


def box(x, y, z, px, py, pz):
    return Part.makeBox(x, y, z, V(px, py, pz))


def cylinder(r, h, x=0, y=0, z=0):
    return Part.makeCylinder(r, h, V(x, y, z))


def rear_slot(width, height, y, z, depth):
    mid = box(width-height,depth,height,-(width-height)/2,y,z-height/2)
    ends = [Part.makeCylinder(height/2,depth,V(x,y,z),V(0,1,0))
            for x in (-(width-height)/2,(width-height)/2)]
    return mid.multiFuse(ends)


def rounded_port(width,height,radius,depth,position):
    if radius <= 0:
        return box(width,depth,height,position.x-width/2,position.y,position.z-height/2)
    assert radius < min(width,height)/2
    pieces = [box(width-2*radius,depth,height,position.x-width/2+radius,position.y,position.z-height/2),
              box(width,depth,height-2*radius,position.x-width/2,position.y,position.z-height/2+radius)]
    for dx in (-width/2+radius,width/2-radius):
        for dz in (-height/2+radius,height/2-radius):
            pieces.append(Part.makeCylinder(radius,depth,position+V(dx,0,dz),V(0,1,0)))
    return pieces[0].multiFuse(pieces[1:])


def ellipse(rx, ry, cy, z):
    # Keep seam and winding consistent with the top circle.
    e = Part.Ellipse(V(0, cy + ry, z), V(-rx, cy, z), V(0, cy, z))
    return Part.Wire([e.toShape()])


def face_transform(p):
    return App.Placement(V(0, p['FaceCenterY'], p['FaceCenterZ']),
                         App.Rotation(V(1, 0, 0), p['FaceAngle']))


def at_face(shape, p):
    shape = shape.copy()
    shape.Placement = face_transform(p).multiply(shape.Placement)
    return shape


def circle_wire(r, z, p):
    # Ellipse and circle both begin at +local v.
    w = Part.Wire([Part.makeCircle(r, V(0, 0, z), V(0, 0, 1), 90, 450)])
    return at_face(w, p)


def cavity_profile(p, inset, clearance=0):
    """Bottom ellipse radii and centre; taper applies to wall inset only.

    Clearance shrinks both radii equally without moving the centre, as needed
    by the base locating lip.
    """
    return (p['BodyHalfWidth']-inset-clearance,
            p['BodyHalfDepth']-1.75*inset-clearance,
            p['BodyCenterY']+.75*inset)


def shell_vents(p):
    """Vent cutting solids in construction order (rear, then sides)."""
    vents = [rear_slot(20,2.2,p['BodyCenterY']+p['BodyHalfDepth']-25,z,30)
             for z in (34,39,44)]
    vents += [Part.makeCylinder(1.5,15,V(sign*(p['BodyHalfWidth']+1),y,z),V(-sign,0,0))
              for sign in (-1,1) for y in (-2,3,8) for z in (8,13,18)]
    return vents


def base_vent(p, y):
    slot = box(18,2.2,p['BaseThickness']+2,-9,y-1.1,-1)
    for x in (-9,9):
        slot = slot.fuse(cylinder(1.1,p['BaseThickness']+2,x,y,-1))
    return slot


def pod_loft(p, inset=0, top=-0.0):
    # A radial shrink alone crosses the steep front wall. Move the cavity
    # profile inward in Y and behind the face as well as shrinking its radius.
    rx, ry, cy = cavity_profile(p, inset)
    neck = -10-1.6*inset
    wires = [ellipse(rx, ry, cy, p['BaseThickness']),
             ellipse(rx, ry, cy, 13),
             circle_wire(p['FaceRadius']-1.6*inset, neck, p)]
    shape = Part.makeLoft(wires, True, True, False)
    shoulder = [e for e in shape.Edges if abs(e.BoundBox.ZMin-13)<0.001
                and abs(e.BoundBox.ZMax-13)<0.001]
    shape = shape.makeFillet(3.0, shoulder)
    if inset:
        transition = Part.makeCone(p['FaceRadius']-1.6*inset,p['FaceRadius']-inset,
                                   -8-neck,V(0,0,neck))
        collar = transition.fuse(cylinder(p['FaceRadius']-inset,top+8,z=-8))
        collar = at_face(collar,p)
    else:
        collar = at_face(cylinder(p['FaceRadius'],top-neck,z=neck),p)
    return shape.fuse(collar)


def usb_placement(p):
    # Board coordinates: (0,0,0) = PCB underside centre; connector points +Y.
    board = App.Placement(V(0, p['EspCenterY'], p['EspBoardZ']), App.Rotation())
    local = V(p['UsbCenterX'], p['EspLength']/2+p['UsbProjection'],
              p['EspThickness']+p['UsbBottomOffset']+p['UsbHeight']/2)
    return board.multVec(local)


def core(p):
    outer = pod_loft(p)
    inner = pod_loft(p, p['Wall'], -p['LcdRecess'])
    # Extend the open bottom through the shell origin, independent of loft caps.
    inner = inner.fuse(Part.Face(ellipse(*cavity_profile(p, p['Wall']), 0))
                       .extrude(V(0,0,p['BaseThickness']+.01)))
    shell = outer.cut(inner)
    opening = at_face(cylinder(p['DisplayOpening']/2, 12, z=-8), p)
    shell = shell.cut(opening)
    usb = usb_placement(p)
    start = usb.y-p['UsbLength']-.5
    port = rounded_port(p['UsbPlugWidth'],p['UsbPlugHeight'],p['UsbPlugCornerRadius'],
                        p['BodyCenterY']+p['BodyHalfDepth']+10-start,V(usb.x,start,usb.z))
    shell = shell.cut(port)
    # Vent pattern comes from the supplied appearance references, not their
    # unverified dimensions. All cuts stop at the local side/rear cavity.
    for vent in shell_vents(p):
        shell = shell.cut(vent)
    # Raised fine rim surrounds a separate matte black face insert. A 0.15 mm
    # adhesive film seats the insert without thinning the structural bezel.
    rim = cylinder(p['FaceRadius'],1.05,z=-.1).cut(cylinder(p['FaceRadius']-1.15,1.3,z=-.2))
    shell = shell.fuse(at_face(rim,p))
    base = Part.Face(ellipse(p['BodyHalfWidth'], p['BodyHalfDepth'],
                            p['BodyCenterY'], 0)).extrude(V(0,0,p['BaseThickness']-0.2))
    for y in BASE_VENT_Y:
        base = base.cut(base_vent(p, y))
    return {'shell': shell, 'base': base}, {'outer': outer, 'inner': inner, 'usb_keepout': port}


def screw_centres(p):
    return [(x, p['BodyCenterY']+y) for x in (-p['BaseScrewX'], p['BaseScrewX'])
            for y in (-p['BaseScrewY'], p['BaseScrewY'])]


def pcb_holes(p):
    return [(x, p['EspCenterY']+y) for x in (-p['EspHolePitchX']/2, p['EspHolePitchX']/2)
            for y in (-p['EspHolePitchY']/2, p['EspHolePitchY']/2)]


def hardware(p):
    z, cy = p['EspBoardZ'], p['EspCenterY']
    pcb = box(p['EspWidth'], p['EspLength'], p['EspThickness'],
              -p['EspWidth']/2, cy-p['EspLength']/2, z)
    for x, y in pcb_holes(p):
        pcb = pcb.cut(cylinder(p['EspHoleDiameter']/2, p['EspThickness']+2, x,y,z-1))
    shield = box(p['EspShieldWidth'],p['EspShieldLength'],p['EspShieldHeight'],
                 -p['EspShieldWidth']/2,cy-p['EspLength']/2+p['EspShieldFrontInset'],z+p['EspThickness'])
    headers = []
    sockets = []
    for x in (-p['HeaderPitchX']/2, p['HeaderPitchX']/2):
        headers.append(box(2.5,p['HeaderLength'],2.5,x-1.25,
                           cy-p['HeaderLength']/2,z-2.5))
        for i in range(15):
            headers.append(box(.64,.64,6,x-.32,cy-17.78+i*2.54-.32,z-8.5))
        # Clearance for female Dupont housings with a bent wire exit below.
        sockets.append(box(3.2,p['HeaderLength'],p['HeaderBelowBoard'],x-1.6,
                           cy-p['HeaderLength']/2,z-p['HeaderBelowBoard']))
    u = usb_placement(p)
    socket = box(p['UsbWidth'],p['UsbLength'],p['UsbHeight'],u.x-p['UsbWidth']/2,
                 u.y-p['UsbLength'],u.z-p['UsbHeight']/2)
    socket = socket.cut(box(p['UsbWidth']-1,p['UsbLength']-1,p['UsbHeight']-1,
                 u.x-p['UsbWidth']/2+.5,u.y-p['UsbLength']+1,u.z-p['UsbHeight']/2+.5))
    buttons = Part.makeCompound([box(p['ButtonWidth'],p['ButtonDepth'],p['ButtonHeight'],
        x-p['ButtonWidth']/2,cy+p['EspLength']/2-p['ButtonRearInset'],z+p['EspThickness'])
        for x in (-p['ButtonCenterX'],p['ButtonCenterX'])])
    t = -p['LcdRecess']-p['LcdGlassThickness']-p['LcdPcbThickness']
    lcd = cylinder(p['LcdDiameter']/2,p['LcdPcbThickness'],z=t)
    lcd = lcd.fuse(box(p['LcdTabWidth'],p['LcdOverallLength']-p['LcdDiameter']+4,
                      p['LcdPcbThickness'],-p['LcdTabWidth']/2,
                      p['LcdDiameter']/2-p['LcdOverallLength'],t)).removeSplitter()
    glass = cylinder(p['LcdGlassDiameter']/2,p['LcdGlassThickness'],
                     z=-p['LcdRecess']-p['LcdGlassThickness'])
    conn = box(p['LcdConnectorWidth'],p['LcdConnectorDepth'],p['LcdConnectorHeight'],
               -p['LcdConnectorWidth']/2,p['LcdConnectorV']-p['LcdConnectorDepth']/2,
               t-p['LcdConnectorHeight'])
    brass = []
    for x in (-p['LcdMountPitchX']/2,p['LcdMountPitchX']/2):
        for v in (-p['LcdMountPitchV']/2,p['LcdMountPitchV']/2):
            mount = cylinder(p['LcdMountDiameter']/2,p['LcdMountProjection'],x,v,t-p['LcdMountProjection'])
            brass.append(mount.cut(cylinder(.9,p['LcdMountProjection']+1,x,v,t-p['LcdMountProjection']-.5)))
    # A 4.4 mm routing corridor for the eight-wire loom; exact pin assignment is
    # firmware dependent. Rounded polyline reserves bend space inside the pod.
    pose = face_transform(p)
    radius = p['CableBundleDiameter']/2
    side = p['EspWidth']/2+6
    exit_z = z-p['HeaderBelowBoard']-.8
    route = [pose.multVec(V(0,p['LcdConnectorV']+p['LcdConnectorDepth']/2+radius,t-3)),
             pose.multVec(V(0,21,-12)),V(side,cy+3,p['FaceCenterZ']-12),
             V(side,cy+5,exit_z+2.8),V(p['HeaderPitchX']/2,cy+5,exit_z)]
    tubes = [Part.makeSphere(radius,pt) for pt in route]
    for a,b in zip(route,route[1:]):
        delta = b-a
        tubes.append(Part.makeCylinder(radius,delta.Length,a,delta))
    cable = tubes[0].multiFuse(tubes[1:])
    feet = Part.makeCompound([cylinder(4,1.5,x,y,-1) for x in (-15,15) for y in (-24,33)])
    return {'esp32_pcb':pcb,'esp32_shield':shield,'esp32_headers':Part.makeCompound(headers),
            'usb_socket':socket,'buttons':buttons,'lcd_pcb':at_face(lcd,p),
            'lcd_glass':at_face(glass,p),'lcd_connector':at_face(conn,p),
            'lcd_brass_mounts':at_face(Part.makeCompound(brass),p),
            'cable_route':cable,'rubber_feet':feet}, {
                'header_housings':Part.makeCompound(sockets)}


def build(p):
    parts, refs = core(p)
    shell, base = parts['shell'], parts['base']
    t = p['BaseThickness']
    # Four bottom-up M2.5 screws; blind pilot holes stay inside the shell.
    for x, y in screw_centres(p):
        boss = cylinder(3.6,10,x,y,t)
        # Broad web joins each boss to the wall without reaching the PCB.
        web = box(8,5,8,x if x>0 else x-8,y-2.5,t)
        shell = shell.fuse(boss.fuse(web).common(refs['outer']))
        shell = shell.cut(cylinder(1.05,8,x,y,t-.1))
        base = base.cut(cylinder(1.4,t+2,x,y,-1))
        base = base.cut(cylinder(2.6,1.4,x,y,-.1))
    # Continuous locating lip with 0.3 mm radial clearance; relief at screw webs.
    rx, ry, cy = cavity_profile(p, p['Wall'], p['FitClearance'])
    lip = Part.Face(ellipse(rx,ry,cy,t-.3)).extrude(V(0,0,2.8))
    lip = lip.cut(Part.Face(ellipse(rx-1.4,ry-1.4,cy,t-.4)).extrude(V(0,0,3)))
    for x,y in screw_centres(p):
        lip = lip.cut(box(15,10,5,x-7.5,y-5,t-1))
    base = base.fuse(lip)
    for x,y in pcb_holes(p):
        base = base.fuse(cylinder(2.8,p['EspBoardZ']-(t-.3),x,y,t-.3))
        base = base.cut(cylinder(.85,8,x,y,p['EspBoardZ']-7))
    # Adhesive 8 mm feet sit in shallow underside recesses, clear of screws.
    for x in (-15,15):
        for y in (-24,33):
            base = base.cut(cylinder(4.1,.6,x,y,-.1))
    # Internal LCD retainer bears on PCB perimeter; no invented LCD hole spacing.
    back = -p['LcdRecess']-p['LcdGlassThickness']-p['LcdPcbThickness']
    ring = cylinder(24.5,2.4,z=back-2.4).cut(cylinder(17.0,3,z=back-2.7))
    ring = ring.cut(box(p['LcdConnectorWidth']+2,22,4,
                         -p['LcdConnectorWidth']/2-1,6,back-3))
    ring = ring.cut(box(p['LcdTabWidth']+1,6,4,
                         -p['LcdTabWidth']/2-.5,-22.2,back-3))
    # Clear the four brass mounting points visible in the hardware photos.
    # These are generous reliefs, not screw interfaces to unknown LCD threads.
    for x in (-p['LcdMountPitchX']/2,p['LcdMountPitchX']/2):
        for v in (-p['LcdMountPitchV']/2,p['LcdMountPitchV']/2):
            ring = ring.cut(cylinder(p['LcdMountDiameter']/2+1,4,x,v,back-3))
    for x in (-22.5,22.5):
        boss = at_face(cylinder(3.3,-p['LcdRecess']-back+.1,x,0,back),p)
        shell = shell.fuse(boss.common(refs['outer']))
        shell = shell.cut(at_face(cylinder(.85,3.2,x,0,back-.01),p))
        ring = ring.cut(cylinder(1.1,4,x,0,back-3))
    # OCCT same-domain refinement corrupts the trimmed loft after boss union.
    # Preserve the valid boolean faces; never repair by dropping faces/solids.
    fascia = cylinder(p['FaceRadius']-1.4,.8,z=.15).cut(cylinder(p['DisplayOpening']/2+.1,1.2,z=0))
    parts = {'shell':shell,'base':base.removeSplitter(),
             'lcd_retainer':at_face(ring.removeSplitter(),p),
             'face_bezel':at_face(fascia,p)}
    hw, keepouts = hardware(p)
    refs.update(keepouts)
    return parts, hw, refs
