"""Optional browser preview; same C host simulator as the VS Code panel."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import argparse,json,sys,threading
sys.path.insert(0,str(Path(__file__).resolve().parent))
from ui_preview import ROOT,SUPPORT,compile_preview
class PreviewServer(ThreadingHTTPServer):
    def __init__(self,address):
        self.lock=threading.Lock();self.lib,self.digest=compile_preview()
        super().__init__(address,Handler)
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,data,kind='application/json',code=200):
        if isinstance(data,str):data=data.encode('utf-8')
        self.send_response(code);self.send_header('Content-Type',kind+'; charset=utf-8');self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data)
    def do_GET(self):
        if self.path=='/':
            html=(SUPPORT/'preview.html').read_text(encoding='utf-8-sig')
            for a,b in {'%NONCE%':'preview-local','%CSP%':"'self'",'%PREVIEW_JS%':'/preview.js','%MODEL_JS%':'/model.js','%MODEL_URL%':'/model.sgch','%MOTIONS_URL%':'/motions.sgmo'}.items():html=html.replace(a,b)
            bridge="""<script nonce="preview-local">window.previewBridge=async function(m){
                try{if(m.type==='reload'){const r=await fetch('/ready');window.postMessage({type:'ready',...await r.json()},location.origin);}
                else if(m.type==='frame'){const r=await fetch('/frame',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(m)});window.postMessage({type:'frame',...await r.json()},location.origin);}
                else window.postMessage({type:'status',text:'この操作はVS Code内のパネルで実行してください。'},location.origin);
                }catch(e){window.postMessage({type:'error',error:e.message},location.origin);}};</script>"""
            html=html.replace('<script nonce="preview-local" src="/model.js">',bridge+'<script nonce="preview-local" src="/model.js">')
            self.reply(html,'text/html')
        elif self.path=='/ready':self.reply(json.dumps({'source':self.server.digest}))
        elif self.path in ('/preview.js','/model.js'):self.reply((SUPPORT/self.path[1:]).read_bytes(),'application/javascript')
        elif self.path=='/model.sgch':self.reply((ROOT/'character/model.sgch').read_bytes(),'application/octet-stream')
        elif self.path=='/motions.sgmo':self.reply((ROOT/'character/motions.sgmo').read_bytes(),'text/plain')
        else:self.reply('{}',code=404)
    def do_POST(self):
        if self.path!='/frame':self.reply('{}',code=404);return
        origin=self.headers.get('Origin')
        if origin and origin!='http://'+self.headers.get('Host',''):self.reply('{}',code=403);return
        length=int(self.headers.get('Content-Length','0'))
        if not 0<length<=1024:self.reply('{}',code=400);return
        try:
            event=json.loads(self.rfile.read(length))
            with self.server.lock:
                if event.get('reset'):self.server.lib.sg_reset()
                result=self.server.lib.sg_frame(int(event.get('now',100000)),int(event.get('x',-1)),int(event.get('y',-1)),int(event.get('key',0)),int(event.get('connection',0)))
            self.reply(result)
        except Exception as e:self.reply(json.dumps({'error':str(e)}),code=400)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=8769);a=p.parse_args()
    server=PreviewServer(('127.0.0.1',a.port));print('Preview: http://127.0.0.1:'+str(a.port),flush=True)
    server.serve_forever()

