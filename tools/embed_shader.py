import sys
from pathlib import Path
src,c,h=map(Path,sys.argv[1:])
data=src.read_bytes()
h.write_text('#pragma once\nextern const unsigned char character_shader[];\nextern const unsigned int character_shader_size;\n')
c.write_text('#include "character_shader.h"\nconst unsigned char character_shader[] __attribute__((aligned(4))) = {'+','.join(map(str,data))+'};\nconst unsigned int character_shader_size = '+str(len(data))+';\n')
