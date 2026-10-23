'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),{EventEmitter}=require('node:events'),{Writable}=require('node:stream');
const root=path.resolve(__dirname,'..'),report=[];
async function extensionTests(){
 let responses=[],requests=[],commands={},errors=[],built=0,failBuild=false;
 const log={append(){},appendLine(){},show(){},dispose(){}};
 const vscode={
  Uri:{file:p=>({fsPath:p,toString:()=>p})},
  workspace:{workspaceFolders:[{uri:{fsPath:root}}],isTrusted:true,getConfiguration:()=>({get:k=>({powershell:'pwsh',python:'python'}[k])}),saveAll:async()=>true,onDidSaveTextDocument:()=>({dispose(){}})},
  window:{createOutputChannel:()=>log,createStatusBarItem:()=>({show(){},dispose(){}}),showInformationMessage(){},showWarningMessage(){},showErrorMessage:m=>errors.push(m)},
  commands:{registerCommand:(name,fn)=>{commands[name]=fn;return {dispose(){}};}},
  StatusBarAlignment:{Left:1},ViewColumn:{Beside:2}
 };
 const transport={request:(url,options,callback)=>{
  const chunks=[];const req=new Writable({write(chunk,encoding,next){chunks.push(Buffer.from(chunk));next();}});
  req.setTimeout=()=>{};req.on('finish',()=>{
   requests.push({url:String(url),options,body:Buffer.concat(chunks)});
   const entry=responses.shift()||{status:200,body:'{}'};
   const res=new EventEmitter();res.statusCode=entry.status;
   callback(res);res.emit('data',Buffer.from(entry.body));res.emit('end');
  });return req;
 }};
 const fakeCp={spawn:()=>{
  built++;const proc=new EventEmitter();proc.stdout=new EventEmitter();proc.stderr=new EventEmitter();
  queueMicrotask(()=>proc.emit('exit',failBuild?1:0));return proc;
 }};
 const exports={};
 const context={module:{exports},exports,require:id=>id==='vscode'?vscode:id==='node:http'||id==='node:https'?transport:id==='node:child_process'?fakeCp:require(id),process,Buffer,URL,setTimeout:fn=>{queueMicrotask(fn);return 1;},clearTimeout,console};
 vm.runInNewContext(fs.readFileSync(path.join(root,'tools/vscode-extension/extension.js'),'utf8'),context);
 const api=context.module.exports;
 for(const s of ['http://192.168.1.2:8765','http://10.0.0.3:8765','https://172.20.10.1:8765'])assert.equal(api.normalizeUrl(s),s);
 for(const s of ['http://example.com:8765','file:///tmp/a','http://user:secret@192.168.1.2','http://192.168.1.2/?t=secret'])assert.throws(()=>api.normalizeUrl(s));
 report.push('private phone URLs and credential-safe URL validation');
 responses=[{status:302,body:'redirect'}];
 await assert.rejects(()=>api.request('http://192.168.1.2:8765','/api/update/upload','test-only-token'),/HTTP 302/);
 assert.equal(requests.length,1);assert.equal(requests[0].options.headers.Authorization,'Bearer test-only-token');
 report.push('redirects rejected; bearer only in header');
 requests=[];responses=[{status:401,body:'no'}];
 await assert.rejects(()=>api.request('http://192.168.1.2:8765','/api/status','test-only-token'),/401/);
 report.push('authentication errors reported');
 const store=new Map();store.set('phone:'+root,'http://192.168.1.2:8765');
 api.activate({subscriptions:[],secrets:{get:async()=> 'test-only-token'},workspaceState:{get:k=>store.get(k),update:async(k,v)=>store.set(k,v)}});
 const binary=fs.readFileSync(path.join(root,'studygadget.3dsx'));
 const sha=require('node:crypto').createHash('sha256').update(binary).digest('hex');
 requests=[];responses=[{status:200,body:'ok'},{status:200,body:'{"ok":true}'},{status:200,body:JSON.stringify({targetSha:sha,installedSha:sha,restarted:true})}];
 await commands['studygadget.deploy']();
 assert.equal(errors.length,0,errors.join('\n'));assert.equal(built,1);
 assert.equal(requests.length,3);assert.ok(requests[0].url.endsWith('/api/update/upload'));assert.deepEqual(requests[0].body,binary);
 assert.equal(requests[0].options.headers['Content-Length'],binary.length);
 assert.equal(JSON.parse(requests[1].body).sha256,sha);assert.ok(requests[2].url.endsWith('/api/dev/status'));
 report.push('build -> exact binary upload -> pinned auto-update -> installation acknowledgement');
 requests=[];failBuild=true;await commands['studygadget.deploy']();
 assert.equal(requests.length,0);assert.equal(errors.length,1);
 report.push('failed build never uploads an older executable');
}
async function modelTests(){
 let uploaded;
 const gl=new Proxy({}, {get:(obj,key)=>{
  if(key==='getShaderParameter'||key==='getProgramParameter')return ()=>true;
  if(key==='bufferSubData')return (target,offset,vertices)=>{uploaded=Array.from(vertices);};
  if(key.startsWith('create'))return ()=>({});
  if(key==='getAttribLocation')return ()=>0;
  if(typeof key==='string'&&key.toUpperCase()===key)return 1;
  return ()=>{};
 }});
 const context={window:{},DataView,TextDecoder,Uint8Array,Float32Array,Math,Array,Error,fetch:async url=>{
  const b=fs.readFileSync(path.join(root,'character',url));
  return {ok:true,arrayBuffer:async()=>b.buffer.slice(b.byteOffset,b.byteOffset+b.byteLength),text:async()=>b.toString()};
 }};
 vm.runInNewContext(fs.readFileSync(path.join(root,'tools/vscode-preview/model.js'),'utf8'),context);
 const model=new context.window.CharacterView({getContext:()=>gl},{model:'model.sgch',motions:'motions.sgmo'});
 for(let i=0;!model.loaded&&i<100;i++)await new Promise(setImmediate);
 assert.ok(model.loaded,model.status);assert.equal(model.motions.length,48);assert.equal(model.bones.length,23);
 for(let state=0;state<6;state++){model.render(state,state*10);model.render(state,state*10+1.5);assert.ok(uploaded.every(Number.isFinite));assert.equal(uploaded.length,8316*9);}
 report.push('actual SGCH + 48 SGMO motions; finite 4-weight vertices in all six states');
}
(async()=>{
 await extensionTests();await modelTests();
 fs.writeFileSync(path.join(root,'diagnostics/vscode-workflow-tests.json'),JSON.stringify({checks:report.length,failures:0,checksPassed:report,network:'mock transport; no device connection',hardwareVerified:false},null,2));
 for(const check of report)console.log('PASS '+check);
})().catch(e=>{console.error(e);process.exitCode=1;});

