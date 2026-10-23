"""Private AI recipe relay (Python standard library only).
Serve behind HTTPS when connecting from a phone. Never put OPENAI_API_KEY in the APK.
Required env: MOTION_RELAY_TOKEN, MOTION_MODEL, OPENAI_API_KEY (OpenAI mode).
Optional: MOTION_LLM_URL for an OpenAI-compatible local inference server.
"""
import json, os, secrets, math, urllib.request, urllib.error, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LIMITS={'duration':(2,30),'speed':(.2,2),'intensity':(0,1),'sway':(0,.2),'head':(0,.5),'arm':(0,1.5),'phase':(0,6.284)}
FIELDS={'state',*LIMITS}
def validate(recipes):
    if not isinstance(recipes,list) or not 6<=len(recipes)<=96:raise ValueError('Expected 6..96 motion recipes')
    states=set()
    for recipe in recipes:
        if not isinstance(recipe,dict) or set(recipe)!=FIELDS:raise ValueError('Unexpected recipe fields')
        s=recipe['state']
        if type(s)!=int or s not in range(6):raise ValueError('Invalid state')
        states.add(s)
        for key,(lo,hi) in LIMITS.items():
            v=recipe[key]
            if type(v) not in (int,float) or not math.isfinite(v) or not lo<=v<=hi:raise ValueError('Invalid '+key)
    if states!=set(range(6)):raise ValueError('Missing character states')
    return recipes

def generate(context):
    props={'state':{'type':'integer','enum':list(range(6))}}
    props.update({k:{'type':'number','minimum':lo,'maximum':hi} for k,(lo,hi) in LIMITS.items()})
    schema={'type':'object','properties':{'recipes':{'type':'array','minItems':6,'maxItems':96,
        'items':{'type':'object','properties':props,'required':list(props),'additionalProperties':False}}},'required':['recipes'],'additionalProperties':False}
    model=os.environ['MOTION_MODEL']
    endpoint=os.environ.get('MOTION_LLM_URL','https://api.openai.com/v1/chat/completions')
    prompt=('Design 48 subtle skeletal motion recipes (8 per state): 0 idle, 1 focused studying, 2 break, 3 success, 4 encouragement, 5 sleeping. '
            'The renderer synthesizes smooth periodic movement, breath, gaze, arm waves and hair sway. '
            'duration is seconds (2..30); speed (.2..2), intensity (0..1); sway/head/arm are radian amplitudes. phase is radians. '
            'Keep focus and sleep calm and unobtrusive. Vary pace and phase. No text, code, bone transforms or extra fields. '
            'Only return the requested JSON object. Context is aggregate study time, never raw learning records.')
    body={'model':model,'messages':[{'role':'system','content':prompt},{'role':'user','content':json.dumps(context)}],
        'response_format':{'type':'json_schema','json_schema':{'name':'motion_bank','strict':True,'schema':schema}}}
    key=os.environ.get('OPENAI_API_KEY','')
    if not key and 'MOTION_LLM_URL' not in os.environ:raise ValueError('Set OPENAI_API_KEY')
    req=urllib.request.Request(endpoint,json.dumps(body).encode(),{'Content-Type':'application/json','Authorization':'Bearer '+key})
    with urllib.request.urlopen(req,timeout=50) as response:
        raw=response.read(131073)
        if len(raw)>131072:raise ValueError('LLM response too large')
    message=json.loads(raw)['choices'][0]['message']
    if message.get('refusal'):raise ValueError('Model declined generation')
    return validate(json.loads(message['content'])['recipes'])

class Handler(BaseHTTPRequestHandler):
    slots=threading.BoundedSemaphore(1)
    def log_message(self,*args):pass # don't log authorization or study context
    def reply(self,status,data):
        payload=json.dumps(data,allow_nan=False).encode()
        self.send_response(status);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(payload)));self.end_headers();self.wfile.write(payload)
    def do_POST(self):
        if self.path!='/generate':self.reply(404,{'error':'not found'});return
        expected='Bearer '+os.environ['MOTION_RELAY_TOKEN']
        if not secrets.compare_digest(self.headers.get('Authorization',''),expected):self.reply(401,{'error':'unauthorized'});return
        if not self.slots.acquire(blocking=False):self.reply(429,{'error':'generation in progress'});return
        try:
            length=int(self.headers.get('Content-Length','0'))
            if not 0<length<=4096:raise ValueError('Invalid request size')
            data=json.loads(self.rfile.read(length))
            if set(data)!={'schema','skeleton','studySeconds','hour','variantsPerState'}:raise ValueError('Unexpected context fields')
            if data['schema']!=1 or type(data['studySeconds'])!=int or not 0<=data['studySeconds']<=7*86400:raise ValueError('Invalid context')
            self.reply(200,generate(data))
        except (ValueError,KeyError,TypeError,json.JSONDecodeError):self.reply(400,{'error':'invalid context or model output'})
        except (OSError,urllib.error.URLError):self.reply(502,{'error':'model service unavailable'})
        finally:self.slots.release()

if __name__=='__main__':
    if len(os.environ.get('MOTION_RELAY_TOKEN',''))<24:raise SystemExit('Set MOTION_RELAY_TOKEN to at least 24 random characters')
    if not os.environ.get('MOTION_MODEL'):raise SystemExit('Set MOTION_MODEL to a structured-output-capable model ID')
    server=ThreadingHTTPServer(('127.0.0.1',int(os.environ.get('MOTION_PORT','8788'))),Handler)
    print('Motion relay listening on localhost; /generate (serve behind HTTPS for phone use)')
    server.serve_forever()
