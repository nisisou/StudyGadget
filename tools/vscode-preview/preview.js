'use strict';
const bridge=typeof acquireVsCodeApi==='function'?acquireVsCodeApi():{postMessage:message=>window.previewBridge(message)};
const canvases=[document.getElementById('top'),document.getElementById('bottom')],contexts=canvases.map(c=>c.getContext('2d'));
const statusElement=document.getElementById('status');
const modelView=new CharacterView(document.getElementById('character'),window.previewAssets);
let ready=false,waiting=false,now=100000,last=performance.now(),queue=[],characterState=-1;
const send=m=>bridge.postMessage(m);
function color(c){return 'rgba('+(c&255)+','+((c>>>8)&255)+','+((c>>>16)&255)+','+((c>>>24)/255)+')';}
function draw(frame){
 for(const c of contexts)c.clearRect(0,0,c.canvas.width,c.canvas.height);
 characterState=-1;
 const ops=frame.ops.slice().sort((a,b)=>a[2]-b[2]);
 for(const o of ops){
  const c=contexts[o[1]],x=o[3],y=o[4];c.beginPath();
  switch(o[0]){
   case 'rect':{const g=c.createLinearGradient(x,y,x,y+o[6]);g.addColorStop(0,color(o[7]));g.addColorStop(1,color(o[8]));c.fillStyle=g;c.fillRect(x,y,o[5],o[6]);break;}
   case 'circle':c.fillStyle=color(o[6]);c.arc(x,y,o[5],0,2*Math.PI);c.fill();break;
   case 'triangle':c.fillStyle=color(o[9]);c.moveTo(x,y);c.lineTo(o[5],o[6]);c.lineTo(o[7],o[8]);c.closePath();c.fill();break;
   case 'text':c.fillStyle=color(o[6]);c.font=(30*o[5])+'px sans-serif';c.textBaseline='top';c.textAlign=o[7]&2?'center':o[7]&4?'right':'left';c.fillText(o[8],x,y);break;
   case 'character':characterState=o[3];modelView.render(o[3],o[4]);break;
  }
 }
 if(characterState<0)modelView.clear();
 statusElement.classList.remove('error');statusElement.textContent=(frame.running?'計測中':'待機中')+' / '+frame.mins+'分設定 / モード '+['TIMER','POMODORO','STOPWATCH'][frame.mode]+' / 科目 '+(frame.subject+1)+' / 今日 '+Math.floor(frame.today/60)+'分（模擬）\n'+modelView.status;
}
window.addEventListener('message',e=>{
 const m=e.data;
 if(m.type==='ready'){ready=true;waiting=false;document.getElementById('source').textContent='C source '+m.source;last=performance.now();}
 if(m.type==='frame'){waiting=false;draw(m);}
 if(m.type==='status'){ready=false;waiting=false;statusElement.textContent=m.text;}
 if(m.type==='error'){ready=false;waiting=false;statusElement.classList.add('error');statusElement.textContent=m.error;}
});
setInterval(()=>{
 const current=performance.now();now+=(current-last)*Number(document.getElementById('speed').value);last=current;
 if(ready&&!waiting){waiting=true;send({type:'frame',now:Math.round(now),connection:document.getElementById('connected').checked?1:0,...(queue.shift()||{})});}
},80);
for(const b of document.querySelectorAll('[data-action]'))b.onclick=()=>send({type:b.dataset.action});
for(const b of document.querySelectorAll('[data-key]'))b.onclick=()=>queue.push({key:Number(b.dataset.key)});
document.getElementById('reset').onclick=()=>{queue.push({reset:true});};
canvases[1].onpointerdown=e=>{
 const r=canvases[1].getBoundingClientRect();queue.push({x:Math.floor((e.clientX-r.left)*320/r.width),y:Math.floor((e.clientY-r.top)*240/r.height)});
};
canvases[1].onpointermove=e=>{const r=canvases[1].getBoundingClientRect();document.getElementById('coordinate').textContent='x '+Math.floor((e.clientX-r.left)*320/r.width)+' / y '+Math.floor((e.clientY-r.top)*240/r.height);};
document.addEventListener('keydown',e=>{if(['INPUT','SELECT'].includes(e.target.tagName))return;const k={a:1,b:2,x:3,y:4,r:5,l:6}[e.key.toLowerCase()];if(k&&!e.repeat){e.preventDefault();queue.push({key:k});}});
send({type:'reload'});

