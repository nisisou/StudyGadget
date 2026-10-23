'use strict';
class CharacterView{
 constructor(canvas,assets){this.canvas=canvas;this.status='モデル読み込み中';this.state=-1;this.selected=-1;this.until=0;this.changed=0;this.turn=0;
  this.gl=canvas.getContext('webgl',{alpha:true,premultipliedAlpha:false,antialias:true});
  this.load(assets).catch(e=>{this.status='モデルプレビュー: '+e.message;});
 }
 async load(assets){
  const response=await fetch(assets.model);if(!response.ok)throw Error('model.sgch がありません');
  const binary=await response.arrayBuffer(),d=new DataView(binary);
  if(new TextDecoder().decode(new Uint8Array(binary,0,4))!=='SGCH')throw Error('SGCH形式ではありません');
  const nb=d.getUint32(12,true),nv=d.getUint32(16,true),nt=d.getUint32(20,true),nbatch=d.getUint32(24,true);
  if(nb>96||nv>24000||nt>1024||!nb||!nt)throw Error('モデルのサイズが範囲外です');
  const floats=(offset,n)=>Array.from({length:n},(_,i)=>d.getFloat32(offset+i*4,true));
  let p=36;this.bones=[];this.rest=[];this.basis=[];this.invbasis=[];
  for(let i=0;i<nb;i++,p+=164){
   const name=new TextDecoder().decode(new Uint8Array(binary,p,32)).split('\0')[0],parent=d.getInt32(p+32,true);
   const local=floats(p+36,16),inverse=floats(p+100,16);
   this.bones.push({name,parent,local,inverse});
   const rest=parent<0?local:CharacterView.mul(this.rest[parent],local);this.rest.push(rest);
   const basis=CharacterView.identity(),inv=CharacterView.identity();
   for(let col=0;col<3;col++){const length=Math.sqrt(Math.max(1e-6,rest[col]**2+rest[4+col]**2+rest[8+col]**2));
    for(let row=0;row<3;row++){basis[row*4+col]=rest[row*4+col]/length;inv[col*4+row]=basis[row*4+col];}}
   this.basis.push(basis);this.invbasis.push(inv);
  }
  this.batches=[];for(let i=0;i<nbatch;i++,p+=12)this.batches.push([d.getUint32(p,true),d.getUint32(p+4,true),d.getUint32(p+8,true)]);
  this.vertices=[];this.gpu=new Float32Array(nv*9);
  for(let i=0;i<nv;i++,p+=56){
   this.vertices.push({p:floats(p,3),uv:floats(p+12,2),color:floats(p+20,4),bone:Array.from(new Uint8Array(binary,p+36,4)),weight:floats(p+40,4)});
   this.gpu.set(this.vertices[i].uv,i*9+3);this.gpu.set(this.vertices[i].color,i*9+5);
  }
  if(p+nt*nt*4!==binary.byteLength)throw Error('テクスチャのサイズが一致しません');
  const tiled=new Uint8Array(binary,p),rgba=new Uint8Array(nt*nt*4);
  for(let y=0;y<nt;y++)for(let x=0;x<nt;x++){
   let morton=0;for(let b=0;b<3;b++)morton|=((x>>b)&1)<<(2*b)|((y>>b)&1)<<(2*b+1);
   const src=((y>>3)*(nt>>3)*64+(x>>3)*64+morton)*4,dst=(y*nt+x)*4;
   rgba[dst]=tiled[src+3];rgba[dst+1]=tiled[src+2];rgba[dst+2]=tiled[src+1];rgba[dst+3]=tiled[src];
  }
  this.motions=[];
  try{const r=await fetch(assets.motions);if(r.ok){const rows=(await r.text()).trim().split(/\r?\n/);for(const row of rows.slice(1)){const v=row.trim().split(/\s+/).map(Number);if(v.length===8&&v.every(Number.isFinite))this.motions.push(v);}}}catch{}
  const gl=this.gl;if(!gl)throw Error('WebGLを利用できません');
  const shader=(type,source)=>{const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS))throw Error(gl.getShaderInfoLog(s));return s;};
  this.program=gl.createProgram();
  gl.attachShader(this.program,shader(gl.VERTEX_SHADER,'attribute vec3 p;attribute vec2 uv;attribute vec4 color;varying vec2 vuv;varying vec4 vc;void main(){gl_Position=vec4(p.x/.64,p.y/.45,-p.z*.1,1.0);vuv=uv;vc=color;}'));
  gl.attachShader(this.program,shader(gl.FRAGMENT_SHADER,'precision mediump float;varying vec2 vuv;varying vec4 vc;uniform sampler2D tex;void main(){vec4 c=texture2D(tex,vuv)*vc;if(c.a<=.03137)discard;gl_FragColor=c;}'));
  gl.linkProgram(this.program);if(!gl.getProgramParameter(this.program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(this.program));
  gl.useProgram(this.program);this.buffer=gl.createBuffer();gl.bindBuffer(gl.ARRAY_BUFFER,this.buffer);gl.bufferData(gl.ARRAY_BUFFER,this.gpu,gl.DYNAMIC_DRAW);
  for(const [name,size,offset] of [['p',3,0],['uv',2,12],['color',4,20]]){const a=gl.getAttribLocation(this.program,name);gl.enableVertexAttribArray(a);gl.vertexAttribPointer(a,size,gl.FLOAT,false,36,offset);}
  gl.bindTexture(gl.TEXTURE_2D,gl.createTexture());gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,nt,nt,0,gl.RGBA,gl.UNSIGNED_BYTE,rgba);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
  this.pose=this.bones.map(()=>[0,0,0]);this.from=this.pose.map(a=>a.slice());
  this.status='SGCHモデル / SGMO '+this.motions.length+'動作（WebGL・動作選択順は実機と異なります）';this.loaded=true;
 }
 static identity(){return [1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1];}
 static mul(a,b){const out=new Array(16).fill(0);for(let i=0;i<4;i++)for(let j=0;j<4;j++)for(let k=0;k<4;k++)out[i*4+j]+=a[i*4+k]*b[k*4+j];return out;}
 static rotation(x,y,z){
  const cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
  return [cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx,0,sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx,0,-sy,cy*sx,cy*cx,0,0,0,0,1];
 }
 clear(){if(this.gl){this.gl.clearColor(0,0,0,0);this.gl.clear(this.gl.COLOR_BUFFER_BIT|this.gl.DEPTH_BUFFER_BIT);}}
 render(state,t){
  if(!this.loaded)return;
  if(this.state!==state||t>=this.until||this.selected<0){
   this.from=this.pose.map(a=>a.slice());this.changed=t;this.state=state;
   const choices=this.motions.map((m,i)=>m[0]===state&&i!==this.selected?i:-1).filter(i=>i>=0);
   this.selected=choices.length?choices[this.turn++%choices.length]:-1;
   this.until=t+(this.selected>=0?this.motions[this.selected][1]:8);
  }
  const m=this.selected>=0?this.motions[this.selected]:[state,8,1,.4,.04,.18,.8,0];
  const s=Math.sin(t*m[2]*1.5+m[7]),q=Math.sin(t*m[2]*.63+m[7]);
  let blend=Math.min(1,Math.max(0,(t-this.changed)/.8));blend=blend*blend*(3-2*blend);
  const world=[],skin=[];
  this.bones.forEach((b,i)=>{
   let x=0,y=0,z=0,amp=m[3];
   if(b.name==='upper_arm.L')z=-1.18;if(b.name==='upper_arm.R')z=1.18;
   if(b.name==='chest'){z=q*m[4];amp*=.4;x=s*.025*amp;}
   if(b.name==='head'){y=q*m[5]*.4;x=s*m[5]*.3;if(state===1)x+=.12;if(state===5)z=.18;}
   if(b.name.includes('hair.')){x=s*.04*amp;z=q*m[4]*.4;}
   if(state===3||state===4){
    if(b.name==='upper_arm.L')z+=m[6]*(.6+.25*s)*amp;
    if(b.name==='forearm.L')z=.5*amp;if(b.name==='hand.L')y=s*.4*amp;
    if(state===3&&b.name==='upper_arm.R')z-=m[6]*.6*amp;
   }
   if(state===2&&b.name==='chest')z+=s*.08*amp;if(state===5&&b.name==='chest')x=.06+s*.01;
   this.pose[i]=[x,y,z].map((v,k)=>this.from[i][k]*(1-blend)+v*blend);
   const rotated=CharacterView.mul(CharacterView.mul(this.invbasis[i],CharacterView.rotation(...this.pose[i])),this.basis[i]);
   const local=CharacterView.mul(b.local,rotated);world[i]=b.parent<0?local:CharacterView.mul(world[b.parent],local);skin[i]=CharacterView.mul(world[i],b.inverse);
  });
  this.vertices.forEach((v,i)=>{
   for(let row=0;row<3;row++){let value=0;
    for(let k=0;k<4;k++){const a=skin[v.bone[k]];value+=v.weight[k]*(a[row*4]*v.p[0]+a[row*4+1]*v.p[1]+a[row*4+2]*v.p[2]+a[row*4+3]);}
    this.gpu[i*9+row]=value*.88+[.32,-.025,-2][row];
   }
  });
  const gl=this.gl;this.clear();gl.useProgram(this.program);gl.bindBuffer(gl.ARRAY_BUFFER,this.buffer);gl.bufferSubData(gl.ARRAY_BUFFER,0,this.gpu);
  gl.enable(gl.DEPTH_TEST);gl.depthFunc(gl.LESS);gl.frontFace(gl.CCW);gl.disable(gl.BLEND);
  for(const [start,count,cull] of this.batches){if(cull){gl.enable(gl.CULL_FACE);gl.cullFace(cull===1?gl.BACK:gl.FRONT);}else gl.disable(gl.CULL_FACE);gl.drawArrays(gl.TRIANGLES,start,count);}
 }
}
window.CharacterView=CharacterView;

