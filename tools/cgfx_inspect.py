"""Independent reader for Study Gadget's serialized rigid HOME CGFX profile."""
import argparse, hashlib, json, math, struct
from pathlib import Path

class InvalidCgfx(ValueError):
    pass

class Reader:
    def __init__(self, data):
        self.data, self.errors = data, []
        self.models, self.animations, self.textures = [], [], []
        self.dictionary_count = 0
    def need(self, ok, message):
        if not ok: raise InvalidCgfx(message)
    def check(self, ok, message):
        if not ok: self.errors.append(message)
    def span(self, pos, size):
        self.need(0 <= pos <= len(self.data) and 0 <= size <= len(self.data)-pos,
                  f'Out-of-file range at 0x{pos:x}, length {size}')
    def unpack(self, fmt, pos):
        self.span(pos, struct.calcsize('<'+fmt))
        return struct.unpack_from('<'+fmt, self.data, pos)
    def u32(self, pos): return self.unpack('I', pos)[0]
    def pointer(self, pos, size=1, nullable=False):
        delta = self.unpack('i', pos)[0]
        if delta == 0 and nullable: return None
        self.need(delta != 0, f'Null pointer at 0x{pos:x}')
        target = pos+delta; self.span(target, size)
        return target
    def string(self, pos):
        target = self.pointer(pos)
        end = self.data.find(b'\0', target, min(len(self.data), target+1024))
        self.need(end >= target, f'Unterminated string at 0x{target:x}')
        try: return self.data[target:end].decode('utf8')
        except UnicodeDecodeError as e: raise InvalidCgfx(f'Invalid UTF-8 at 0x{target:x}') from e
    def magic(self, pos, value):
        self.span(pos, len(value))
        self.need(self.data[pos:pos+len(value)] == value, f'Expected {value!r} at 0x{pos:x}')
    def dictionary(self, pos):
        count = self.u32(pos)
        self.need(count <= 1024, f'Dictionary too large: {count}')
        if not count:
            self.check(self.u32(pos+4) == 0, 'Empty dictionary has nonnull pointer')
            return []
        start = self.pointer(pos+4, 12+16*(count+1)); self.magic(start, b'DICT')
        self.need(self.u32(start+4) == 12+16*(count+1), 'DICT size mismatch')
        self.need(self.u32(start+8) == count, 'DICT count mismatch')
        result = []
        for i in range(count+1):
            node = start+12+16*i
            _, left, right = self.unpack('iHH', node)
            self.need(left <= count and right <= count, 'Invalid DICT branch index')
            if i: result.append((self.string(node+8), self.pointer(node+12)))
        self.check(len({n for n, _ in result}) == count, 'Duplicate DICT names')
        self.dictionary_count += 1
        return result
    def list(self, pos, objects=True):
        count = self.u32(pos); self.need(count <= 24000, f'List too large: {count}')
        if not count:
            self.check(self.u32(pos+4) == 0, 'Empty list has nonnull pointer')
            return []
        array = self.pointer(pos+4, count*4)
        return [self.pointer(array+i*4) if objects else self.u32(array+i*4) for i in range(count)]
    def vertex_stream(self, pos):
        ty, usage, _ = self.unpack('III', pos)
        self.check(usage not in (7,8), 'BoneIndex/BoneWeight in vertex stream')
        self.need(ty == 0x40000001, f'Unsupported vertex attribute 0x{ty:x}')
        self.span(pos, 52)
        length, fmt, components = self.u32(pos+20), self.u32(pos+36), self.u32(pos+40)
        self.need(fmt == 0x1406 and 1 <= components <= 4, 'Expected float vertex stream')
        self.need(length > 0 and length % (4*components) == 0, 'Malformed vertex stream size')
        values = self.unpack('f'*(length//4), self.pointer(pos+24, length))
        self.check(all(math.isfinite(v) for v in values), 'Nonfinite vertex value')
        self.check(math.isfinite(self.unpack('f',pos+44)[0]), 'Nonfinite stream scale')
        return usage, length//(4*components)
    def shape(self, pos, bones):
        self.magic(pos+4,b'SOBJ'); self.need(self.u32(pos)==0x10000001,'Expected shape SOBJ')
        self.span(pos,68); self.check(self.u32(pos+64)==0,'Blend shape is not allowed')
        vertex_streams=self.list(pos+56)
        attrs = [self.vertex_stream(p) for p in vertex_streams]
        index_streams=[]
        positions = [n for u,n in attrs if u==0]
        self.need(len(positions)==1,'Missing or duplicate position stream')
        count=positions[0]; self.check(all(n==count for _,n in attrs),'Vertex stream counts differ')
        bindings, triangles, modes = set(), 0, []
        for ps in self.list(pos+44):
            self.span(ps,20); related=self.list(ps,objects=False)
            mode=self.u32(ps+8); modes.append(mode)
            self.check(mode==0,f'Skinning mode {mode}, expected rigid mode 0')
            self.check(len(related)==1,'Rigid primitive must bind exactly one node')
            for b in related:
                self.need(b<len(bones),f'Invalid bone index {b}'); bindings.add(bones[b]['name'])
            for primitive in self.list(ps+12):
                self.span(primitive,24)
                for stream in self.list(primitive):
                    index_streams.append(stream)
                    self.span(stream,44); fmt=self.u32(stream)
                    self.need(fmt in (0x1401,0x1403),'Unsupported index type')
                    self.check(self.data[stream+4]==0,'Expected triangle-list indices')
                    width,code=(1,'B') if fmt==0x1401 else (2,'H')
                    length=self.u32(stream+8)
                    self.need(length>0 and length%(width*3)==0,'Invalid triangle index size')
                    indices=self.unpack(code*(length//width),self.pointer(stream+12,length))
                    self.check(max(indices)<count,'Index exceeds vertex count')
                    triangles+=len(indices)//3
        return {'vertices':count,'triangles':triangles,'bindings':sorted(bindings),
                'skinningModes':modes,'attributeUsages':[u for u,_ in attrs],
                'vertexStreamOffsets':vertex_streams,'indexStreamOffsets':index_streams}
    def model(self,pos):
        self.magic(pos+4,b'CMDL'); self.need(self.u32(pos)==0x40000092,'Expected model with node skeleton')
        self.span(pos,228); name=self.string(pos+12)
        skeleton=self.pointer(pos+224,44); self.magic(skeleton+4,b'SOBJ')
        self.need(self.u32(skeleton)==0x2000000,'Invalid skeleton type')
        entries=self.dictionary(skeleton+24); bones=[]
        for key,bone in entries:
            self.span(bone,224)
            bname,flags,parent=self.string(bone),self.u32(bone+4),self.unpack('i',bone+12)[0]
            self.check(bname==key,'Bone dictionary name mismatch')
            self.check(not flags&512,'Bone has skinning matrix flag')
            self.check(-1<=parent<len(entries),'Invalid bone parent')
            self.check(all(math.isfinite(x) for x in self.unpack('f'*45,bone+32)),'Nonfinite bone matrix')
            bones.append({'name':bname,'parent':parent,'offset':bone})
        groups={}
        for group_name,group in self.dictionary(pos+40):
            self.span(group,36); self.check(self.string(group+8)==group_name,'Animation group name mismatch')
            members=self.dictionary(group+16); groups[group_name]={k for k,_ in members}
            if group_name=='SkeletalAnimation':
                self.check(self.u32(group+12)==1,'Wrong skeletal animation group type')
                for key,member in members:
                    self.span(member,48)
                    self.check(self.u32(member)==0x40000000,'Wrong bone member object type')
                    self.check(self.string(member+4)==key and self.string(member+8)==key,'Bone animation group path mismatch')
        materials=self.dictionary(pos+188)
        for _,material in materials:
            self.magic(material+4,b'MTOB'); self.span(material+84,32)
            self.check(all(abs(v)<1e-7 for v in self.unpack('f'*8,material+84)),
                       'Specular material colors have not been cleared')
        shapes=[self.shape(s,bones) for s in self.list(pos+196)]
        meshes=[]
        for mesh in self.list(pos+180):
            self.magic(mesh+4,b'SOBJ'); self.span(mesh,116)
            self.need(self.u32(mesh)==0x1000000,'Invalid mesh SOBJ type')
            sid,mid=self.u32(mesh+24),self.u32(mesh+28)
            self.need(sid<len(shapes) and mid<len(materials),'Mesh resource index out of range')
            binding=self.string(mesh+112)
            self.check(bool(binding) and binding in shapes[sid]['bindings'],'Mesh node binding is missing or mismatched')
            self.check(self.pointer(mesh+32)==pos,'Mesh owner reference mismatch')
            meshes.append({'name':self.string(mesh+12),'node':binding,'shape':sid,'offset':mesh})
        self.check(len({m['name'] for m in meshes})==len(meshes),'Duplicate mesh names')
        self.models.append({'name':name,'bones':bones,'groups':{k:sorted(v) for k,v in groups.items()},
                            'meshes':meshes,'shapes':shapes,'materials':len(materials)})
    def curve(self,pos,duration):
        self.span(pos,20); start,end=self.unpack('ff',pos)
        self.check(math.isfinite(start) and math.isfinite(end) and abs(start)<1e-5 and abs(end-duration)<1e-3,
                   'Animation curve does not cover the complete loop')
        count=self.u32(pos+16); self.need(0<count<=64,'Invalid curve segment count')
        self.span(pos+20,4*count); values=[]
        for i in range(count):
            segment=self.pointer(pos+20+4*i,16); a,b=self.unpack('ff',segment); flags=self.u32(segment+8)
            self.check(math.isfinite(a) and math.isfinite(b) and a<=b,'Invalid segment interval')
            if flags&1:
                value=self.unpack('f',segment+12)[0]
                self.check(math.isfinite(value),'Nonfinite constant curve')
                values.extend([(a,value),(b,value)]); continue
            interpolation,quantization=(flags>>2)&3,(flags>>5)&7
            self.need(quantization==6 and interpolation in (0,1),'Only StepLinear64 curves supported by this profile')
            keys=self.u32(segment+12); self.need(2<=keys<=4096,'Invalid animation key count')
            speed=self.unpack('f',segment+16)[0]
            self.check(math.isfinite(speed) and speed>0,'Invalid key lookup speed')
            self.span(segment+20,keys*8)
            samples=[self.unpack('ff',segment+20+j*8) for j in range(keys)]
            self.check(all(math.isfinite(t) and math.isfinite(v) for t,v in samples),'Nonfinite animation key')
            self.check(all(samples[j][0]<samples[j+1][0] for j in range(keys-1)),'Nonmonotonic key times')
            self.check(abs(samples[0][0]-a)<1e-3 and abs(samples[-1][0]-b)<1e-3,'Segment/key interval mismatch')
            values.extend(samples)
        self.check(abs(values[0][0])<1e-3 and abs(values[-1][0]-duration)<1e-3,'Key range does not match loop')
        self.check(abs(values[0][1]-values[-1][1])<1e-5,'Loop endpoints differ')
        return [v for _,v in values]
    def animation(self,pos):
        self.magic(pos,b'CANM'); self.span(pos,40)
        name,target=self.string(pos+8),self.string(pos+12)
        looping,duration=self.u32(pos+16),self.unpack('f',pos+20)[0]
        self.check(looping==1,'CANM is not looping')
        self.need(math.isfinite(duration) and 0<duration<3600,'Invalid CANM duration')
        self.check(abs(duration/60-2.4)<1e-4,'Unexpected banner loop length')
        groups=set().union(*(set(m['groups'].get(target,[])) for m in self.models))
        bound={mesh['node'] for m in self.models for mesh in m['meshes']}
        members=[]
        for key,member in self.dictionary(pos+24):
            self.span(member,60); path,ty=self.string(member+4),self.u32(member+16)
            self.check(ty==5,f'CANM primitive {ty}, expected Transform (5)')
            self.need(ty==5,'Unsupported CANM member layout')
            self.check(key==path and path in groups and path in bound,'CANM target is not a bound mesh node')
            flags=self.u32(member); axes=[]
            ignore=[0x10000,0x20000,0x40000,0x80000,0x100000,0x200000,0x800000,0x1000000,0x2000000]
            const=[0x40,0x80,0x100,0x200,0x400,0x800,0x2000,0x4000,0x8000]
            for i,offset in enumerate((20,24,28,32,36,40,48,52,56)):
                if flags&ignore[i]: continue
                values=[self.unpack('f',member+offset)[0]] if flags&const[i] else self.curve(self.pointer(member+offset,20),duration)
                self.check(all(math.isfinite(v) for v in values),'Nonfinite transform value')
                if i<3: self.check(all(.1<v<4 for v in values),'Invalid banner animation scale')
                self.check(all(abs(v)<=100 for v in values),'Transform outside banner range')
                axes.append({'component':i,'min':min(values),'max':max(values),'keys':len(values)})
            self.check(any(a['max']-a['min']>1e-6 for a in axes),'Animation contains no motion')
            members.append({'node':path,'primitiveType':ty,'axes':axes,'offset':member})
        self.check(bool(members),'No transform animation targets')
        self.animations.append({'name':name,'targetGroup':target,'seconds':duration/60,'members':members})
    def run(self):
        self.magic(0,b'CGFX'); self.span(0,20)
        self.need(self.unpack('H',4)[0]==0xfeff and self.unpack('H',6)[0]==20,'Unsupported CGFX header')
        self.need(self.u32(12)==len(self.data),'CGFX header file length mismatch')
        self.need(len(self.data)<=524288,'CGFX exceeds 512 KiB')
        self.magic(20,b'DATA'); size=self.u32(24)
        self.need(128<=size<=len(self.data)-20,'Invalid DATA length')
        self.need(self.u32(16)==2,'Expected DATA and IMAG blocks')
        imag=20+size; self.magic(imag,b'IMAG')
        self.need(imag+8+self.u32(imag+4)==len(self.data),'IMAG length mismatch')
        for _,model in self.dictionary(28): self.model(model)
        self.check(len(self.models)==1,'Expected exactly one banner model')
        for name,tex in self.dictionary(36):
            self.magic(tex+4,b'TXOB'); self.span(tex,60)
            self.need(self.u32(tex)==0x20000011,'Expected pixel image texture')
            h,w,fmt=self.u32(tex+24),self.u32(tex+28),self.u32(tex+52)
            self.check(8<=w<=1024 and w&(w-1)==0 and 8<=h<=1024 and h&(h-1)==0,'Invalid power-of-two texture size')
            self.check(fmt in (1,2,3,4,5,7,8,9,10,11),'Unsupported HOME texture format, including RGBA8')
            image=self.pointer(tex+56,32)
            self.check((self.u32(image),self.u32(image+4))==(h,w),'Texture/image dimensions differ')
            length=self.u32(image+8); self.pointer(image+12,length)
            bpp={1:3,2:2,3:2,4:2,5:2,7:1,8:1,9:1,10:.5,11:.5}.get(fmt,0)
            self.check(length==int(h*w*bpp),'Texture byte length mismatch')
            self.textures.append({'name':name,'width':w,'height':h,'format':fmt,'bytes':length})
        for _,canm in self.dictionary(28+9*8): self.animation(canm)
        self.check(bool(self.animations),'No mesh-node animation CANM')
        self.check(bool(self.textures),'No banner texture')
        return self

def inspect_bytes(data):
    r=Reader(data)
    try: r.run()
    except (InvalidCgfx,struct.error) as e: r.errors.append(str(e))
    return {'schema':1,'profile':'studygadget-rigid-home-v1','passed':not r.errors,
            'bytes':len(data),'limitBytes':524288,'sha256':hashlib.sha256(data).hexdigest(),
            'models':r.models,'animations':r.animations,'textures':r.textures,
            'dictionariesChecked':r.dictionary_count,'errors':r.errors,'hardwareTested':False}

def compare_source(report, data, source):
    """Check actual vertex/index buffers against source material sidedness."""
    blob=source.read_bytes()
    if blob[:4]==b'glTF':
        size,kind=struct.unpack_from('<I4s',blob,12)
        if kind!=b'JSON': raise InvalidCgfx('GLB JSON chunk missing')
        doc=json.loads(blob[20:20+size])
    else:
        doc=json.loads(blob.decode('utf-8-sig'))
    report['sourceSha256']=hashlib.sha256(blob).hexdigest()
    if len(doc['meshes'])!=1 or len(report['models'])!=1:
        report['errors'].append('Expected one source mesh/model')
        report['passed']=False; return report
    primitives=doc['meshes'][0]['primitives']; shapes=report['models'][0]['shapes']
    r=Reader(data)
    if len(primitives)!=len(shapes):
        report['errors'].append('Source/CGFX shape count mismatch')
    for p,s in zip(primitives,shapes):
        original_count=doc['accessors'][p['attributes']['POSITION']]['count']
        index_count=doc['accessors'][p['indices']]['count']
        double=bool(doc['materials'][p['material']].get('doubleSided',False))
        factor=2 if double else 1
        if s['vertices']!=original_count*factor or s['triangles']!=index_count//3*factor:
            report['errors'].append('CGFX geometry does not preserve source material sidedness')
        if double:
            for stream in s['vertexStreamOffsets']:
                length=r.u32(stream+20); start=r.pointer(stream+24,length)
                if length%2: raise InvalidCgfx('Odd double-sided stream length')
                half=length//2; first=data[start:start+half]; second=data[start+half:start+length]
                if r.u32(stream+4)==1:
                    a=r.unpack('f'*(half//4),start); b=r.unpack('f'*(half//4),start+half)
                    if not all(abs(x+y)<1e-6 for x,y in zip(a,b)):
                        report['errors'].append('Back-face normals are not inverted')
                elif first!=second:
                    report['errors'].append('Back-face position/UV data differs from front')
            for stream in s['indexStreamOffsets']:
                code='H' if r.u32(stream)==0x1403 else 'B'
                width=struct.calcsize(code); length=r.u32(stream+8)
                indices=r.unpack(code*(length//width),r.pointer(stream+12,length))
                first,second=indices[:index_count],indices[index_count:]
                if tuple(v+original_count for v in reversed(first))!=second:
                    report['errors'].append('Back-face triangle winding is not reversed')
    report['passed']=not report['errors']
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument('cgfx',type=Path); p.add_argument('--report',type=Path); p.add_argument('--source',type=Path)
    a=p.parse_args(); data=a.cgfx.read_bytes(); result=inspect_bytes(data)
    if a.source and result['passed']: compare_source(result,data,a.source)
    if a.report:
        a.report.parent.mkdir(parents=True,exist_ok=True)
        a.report.write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf8')
    print(json.dumps({k:result[k] for k in ('passed','bytes','sha256','errors')}))
    return 0 if result['passed'] else 1

if __name__=='__main__': raise SystemExit(main())

