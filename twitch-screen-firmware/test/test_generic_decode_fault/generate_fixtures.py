"""Deterministic JPEG controls, encoded by Pillow/libjpeg, not provider data.
Run explicitly to regenerate committed test fixtures; no test-time dependency.
"""
from pathlib import Path
from PIL import Image, features

root = Path(__file__).resolve().parent
print('Pillow JPEG library', features.version('jpg'))
for gray in (False, True):
    image = Image.new('L' if gray else 'RGB', (96, 96))
    for y in range(96):
        for x in range(96):
            image.putpixel((x, y), (x * 2 + y) % 256 if gray else
                           ((x * 2 + y) % 256, (y * 2 + x) % 256, (x + y) % 256))
    for progressive in (False, True):
        for sampling in ((0,) if gray else (0, 1, 2)):
            # Color 4:4:4 progressive exceeds the production cap by design.
            for restart in (0, 3):
                name = f'{"gray" if gray else "color"}-{ "progressive" if progressive else "baseline"}-s{sampling}-r{restart}.jpg'
                image.save(root / name, quality=65, progressive=progressive,
                           subsampling=sampling, restart_marker_blocks=restart)
                data = (root / name).read_bytes()
                count = sum(data.count(bytes((255, m))) for m in range(208, 216))
                print(name, len(data), 'RST markers', count)
                if restart and not count:
                    raise RuntimeError('Pillow encoder lacks restart_marker_blocks')
