#!/usr/bin/env python3
"""Copy fresh test renders from engine/build/ into docs/ preview names.

Run from the android/ directory after `gradle :engine:test`. Preview naming is
docs/<face>-{front,back,night}.png; test outputs use <face>.png for fronts on
some faces, which this maps. Previews are rendered with the free font stack
(Liberation Sans / Nimbus Roman / DejaVu Sans) unless real fonts are present
in docs/ (*.ttf/*.otf, git-ignored).
"""
import glob
import os
import shutil

def main():
    n = 0
    for d in sorted(glob.glob('docs/*-front.png') + glob.glob('docs/*-back.png') + glob.glob('docs/*-night.png')):
        name = os.path.basename(d)
        candidates = [f'engine/build/{name}', f"engine/build/{name.replace('-front', '')}"]
        for b in candidates:
            if os.path.exists(b):
                shutil.copyfile(b, d)
                n += 1
                break
        else:
            print(f'!! no build output for {name}')
    print(f'refreshed {n} previews')

if __name__ == '__main__':
    main()
