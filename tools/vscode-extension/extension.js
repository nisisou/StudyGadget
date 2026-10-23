'use strict';
const vscode=require('vscode'),fs=require('node:fs'),path=require('node:path');
const cp=require('node:child_process'),readline=require('node:readline'),crypto=require('node:crypto'),http=require('node:http'),https=require('node:https');
let context,output,panel,preview,root,busy=false,sourceGeneration=0;
const config=()=>vscode.workspace.getConfiguration('studygadget');
function pythonCommand(){
 const chosen=config().get('python')||'python';if(chosen!=='python')return chosen;
 const base=path.join(process.env.LOCALAPPDATA||'', 'Programs','Python');
 if(fs.existsSync(base)){
  const candidate=fs.readdirSync(base).filter(n=>/^Python3/.test(n)).sort().reverse().map(n=>path.join(base,n,'python.exe')).find(p=>fs.existsSync(p));
  if(candidate)return candidate;
 }
 return chosen;
}
function workspace(){
 const folders=vscode.workspace.workspaceFolders||[];
 const folder=folders.find(f=>fs.existsSync(path.join(f.uri.fsPath,'assets','main.c')));
 if(!folder)throw Error('Study GadgetのフォルダーをVS Codeで開いてください。');
 if(!vscode.workspace.isTrusted)throw Error('信頼済みの作業フォルダーで実行してください。');
 return folder.uri.fsPath;
}
function log(s){output.appendLine(s);}
function execute(executable,args,cwd,env){
 return new Promise((resolve,reject)=>{
  const child=cp.spawn(executable,args,{cwd,env:{...process.env,...env},windowsHide:true,shell:false});
  child.stdout.on('data',d=>output.append(d.toString()));child.stderr.on('data',d=>output.append(d.toString()));
  child.on('error',reject);child.on('exit',code=>code===0?resolve():reject(Error('処理に失敗しました（終了コード '+code+'）。出力を確認してください。')));
 });
}
async function build(){
 if(!await vscode.workspace.saveAll(false))throw Error('保存できなかったファイルがあります。ビルドを中止しました。');
 output.show(true);log('\n[3DS build] '+new Date().toLocaleTimeString());
 await execute(pythonCommand(),[path.join(root,'tools','build_3ds.py'),'--devkit',config().get('devkitPro')||'C:\\devkitPro'],root);
}
function phoneKey(){return 'phone:'+vscode.Uri.file(root).toString();}
function normalizeUrl(value){
 const u=new URL(value);
 if(!['http:','https:'].includes(u.protocol)||u.username||u.password||u.search||u.hash)throw Error('http://192.168.x.x:8765 の形で入力してください。');
 const h=u.hostname;
 const parts=h.split('.').map(Number);
 const local=h==='localhost'||h==='[::1]'||(parts.length===4&&parts.every(n=>Number.isInteger(n)&&n>=0&&n<=255)&&(parts[0]===10||parts[0]===127||(parts[0]===192&&parts[1]===168)||(parts[0]===172&&parts[1]>=16&&parts[1]<=31)));
 if(!local)throw Error('親機のローカルIPv4アドレスを指定してください。');
 return u.origin;
}
async function pair(){
 const old=context.workspaceState.get(phoneKey());
 const url=await vscode.window.showInputBox({title:'親機スマホの接続先',prompt:'スマホの親機サーバーを起動し、表示されたIPとポートを入力',value:old||'http://192.168.43.1:8765',ignoreFocusOut:true});
 if(url===undefined)return false;
 const normalized=normalizeUrl(url);
 const token=await vscode.window.showInputBox({title:'親機のペアリングトークン',prompt:'スマホの設定にある認証トークン',password:true,ignoreFocusOut:true});
 if(token===undefined)return false;if(!token.trim())throw Error('トークンが空です。');
 await context.secrets.store(phoneKey(),token.trim());await context.workspaceState.update(phoneKey(),normalized);
 vscode.window.showInformationMessage('親機の接続設定を保存しました。');return true;
}
function request(base,route,token,{file,json,method='GET',timeout=120000}={}){
 return new Promise((resolve,reject)=>{
  const u=new URL(route,base),headers={Authorization:'Bearer '+token};
  let bytes;
  if(json!==undefined){bytes=Buffer.from(JSON.stringify(json));headers['Content-Type']='application/json';headers['Content-Length']=bytes.length;}
  if(file){headers['Content-Type']='application/octet-stream';headers['Content-Length']=fs.statSync(file).size;}
  const req=(u.protocol==='https:'?https:http).request(u,{method,headers},res=>{
   const chunks=[];let size=0;res.on('data',b=>{size+=b.length;if(size>1024*1024){req.destroy(Error('親機の応答が大きすぎます。'));return;}chunks.push(b);});
   res.on('end',()=>{const text=Buffer.concat(chunks).toString();if(res.statusCode<200||res.statusCode>=300)return reject(Error('親機 HTTP '+res.statusCode+'。'+(res.statusCode===401?'トークンを設定し直してください。':res.statusCode===404?'対応する親機APKへ更新してください。':text.slice(0,250))));try{resolve(JSON.parse(text));}catch{resolve(text);}});
   res.on('error',reject);
  });
  req.setTimeout(timeout,()=>req.destroy(Error('親機との通信がタイムアウトしました。')));req.on('error',reject);
  if(file){const stream=fs.createReadStream(file);stream.on('error',e=>req.destroy(e));stream.pipe(req);}else req.end(bytes);
 });
}
async function deploy(){
 let base=context.workspaceState.get(phoneKey()),token=await context.secrets.get(phoneKey());
 if(!base||!token){if(!await pair())return;base=context.workspaceState.get(phoneKey());token=await context.secrets.get(phoneKey());}
 await build();const file=path.join(root,'studygadget.3dsx');
 const sha=crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
 log('[upload] 親機へ更新ファイルを送信しています…');
 await request(base,'/api/update/upload',token,{file,method:'POST'});
 log('[upload] 親機で受け付けました。3DSの自動更新を予約します。');
 await request(base,'/api/dev/auto-update',token,{method:'POST',json:{enabled:true,sha256:sha}});
 log('[3DS] 3DSの受信・検証を待っています。計測中は終了後に反映します。');
 const deadline=Date.now()+120000;
 while(Date.now()<deadline){
  await new Promise(r=>setTimeout(r,1500));
  const state=await request(base,'/api/dev/status',token,{timeout:10000});
  if(state.targetSha!==sha)throw Error('親機で別の更新が予約されました。もう一度実行してください。');
  if(state.installedSha===sha){log('[3DS] 更新ファイルの反映を確認しました。'+(state.restarted?'再起動しました。':'HBL環境により再起動待ちの場合があります。'));vscode.window.showInformationMessage('3DSの更新ファイル反映を確認しました。');return;}
  if(state.error){throw Error('3DS更新: '+state.error);}
 }
 vscode.window.showWarningMessage('親機への送信と自動更新の予約は完了しました。3DSの完了通知は未確認です。3DSのWi-Fi・アプリ起動・計測状態を確認してください。');
 log('[3DS] 完了未確認。更新予約は親機に保持されています。');
}
function stopPreview(){sourceGeneration++;if(preview){preview.kill();preview=undefined;}}
function startPreview(){
 stopPreview();const generation=sourceGeneration;
 panel.webview.postMessage({type:'status',text:'Cコードを読み込み中…'});
 const child=cp.spawn(pythonCommand(),[path.join(root,'tools','ui_preview.py'),'--stdio'],{cwd:root,windowsHide:true,env:{...process.env,PYTHONIOENCODING:'utf-8'},shell:false});preview=child;
 readline.createInterface({input:child.stdout}).on('line',line=>{
  if(generation!==sourceGeneration||!panel)return;
  try{const data=JSON.parse(line);panel.webview.postMessage({type:data.ready?'ready':data.error?'error':'frame',...data});}catch{log(line);}
 });
 child.stderr.on('data',d=>log(d.toString()));
 child.on('error',e=>{if(panel)panel.webview.postMessage({type:'error',error:e.message});});
 child.on('exit',code=>{if(code&&generation===sourceGeneration&&panel)panel.webview.postMessage({type:'error',error:'プレビューを開始できませんでした。Study Gadgetの出力を確認してください。'});});
}
function openPreview(){
 if(panel){panel.reveal(vscode.ViewColumn.Beside);return;}
 const support=vscode.Uri.file(path.join(root,'tools','vscode-preview'));
 panel=vscode.window.createWebviewPanel('studygadget.preview','Study Gadget • UI Preview',vscode.ViewColumn.Beside,{enableScripts:true,retainContextWhenHidden:true,localResourceRoots:[support,vscode.Uri.file(path.join(root,'character'))]});
 const nonce=crypto.randomBytes(24).toString('hex');
 let html=fs.readFileSync(path.join(root,'tools','vscode-preview','preview.html'),'utf8');
 const uri=name=>panel.webview.asWebviewUri(vscode.Uri.joinPath(support,name)).toString();
 html=html.replaceAll('%NONCE%',nonce).replaceAll('%CSP%',panel.webview.cspSource).replaceAll('%PREVIEW_JS%',uri('preview.js')).replaceAll('%MODEL_JS%',uri('model.js')).replaceAll('%MODEL_URL%',panel.webview.asWebviewUri(vscode.Uri.file(path.join(root,'character','model.sgch'))).toString()).replaceAll('%MOTIONS_URL%',panel.webview.asWebviewUri(vscode.Uri.file(path.join(root,'character','motions.sgmo'))).toString());
 panel.webview.html=html;
 panel.webview.onDidReceiveMessage(m=>{
  if(m.type==='frame'&&preview?.stdin.writable){const event={now:Math.max(100000,Math.min(Number(m.now)||100000,Number.MAX_SAFE_INTEGER)),x:Number.isFinite(Number(m.x))?Math.round(Number(m.x)):-1,y:Number.isFinite(Number(m.y))?Math.round(Number(m.y)):-1,key:Math.max(0,Math.min(6,Number(m.key)||0)),connection:m.connection===1?1:0,reset:!!m.reset};preview.stdin.write(JSON.stringify(event)+'\n');}
  if(m.type==='reload')startPreview();
  if(['build','deploy','pair','emulator'].includes(m.type))vscode.commands.executeCommand('studygadget.'+m.type);
 });
 panel.onDidDispose(()=>{stopPreview();panel=undefined;});
 // Webview sends reload only after its scripts and frame handler are ready.
}
async function emulator(){
 const executable=config().get('emulator');if(!executable){vscode.window.showInformationMessage('設定の Study Gadget: Emulator に3DSエミュレーターの実行ファイルを指定してください。UIプレビューは追加ソフトなしで使えます。');return;}
 await build();const child=cp.spawn(executable,[path.join(root,'studygadget.3dsx')],{cwd:root,windowsHide:true,shell:false,stdio:'ignore'});child.on('error',e=>vscode.window.showErrorMessage(e.message));
}
function activate(ctx){
 context=ctx;output=vscode.window.createOutputChannel('Study Gadget');ctx.subscriptions.push(output);
 ctx.subscriptions.push(vscode.commands.registerCommand('studygadget.resolvePython',pythonCommand));
 for(const [name,fn] of Object.entries({preview:openPreview,build,deploy,pair,emulator})){
  ctx.subscriptions.push(vscode.commands.registerCommand('studygadget.'+name,async()=>{
   try{root=workspace();if(name==='preview'){openPreview();return;}if(busy){vscode.window.showInformationMessage('前の操作が完了するまでお待ちください。');return;}busy=true;await fn();}
   catch(e){log(e.stack||e.message);output.show(true);vscode.window.showErrorMessage('Study Gadget: '+e.message);}
   finally{if(name!=='preview')busy=false;}
  }));
 }
 for(const [name,label,tip,priority] of [['preview','$(device-mobile) 3DS Preview','画面プレビュー',103],['build','$(tools) Build','3DSをビルド',102],['deploy','$(cloud-upload) 3DS更新','ビルドして親機経由で3DSを更新',101]]){
  const item=vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left,priority);item.text=label;item.tooltip=tip;item.command='studygadget.'+name;item.show();ctx.subscriptions.push(item);
 }
 let refresh;
 ctx.subscriptions.push(vscode.workspace.onDidSaveTextDocument(doc=>{
  if(panel&&root&&(doc.uri.fsPath===path.join(root,'assets','main.c')||doc.uri.fsPath.startsWith(path.join(root,'tools','vscode-preview')))){
   clearTimeout(refresh);refresh=setTimeout(startPreview,250);
  }
 }),{dispose:()=>{clearTimeout(refresh);stopPreview();panel?.dispose();}});
}
module.exports={activate,deactivate:stopPreview,normalizeUrl,request};

