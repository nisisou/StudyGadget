"""Run production C transport against scripted responses with real background threads.
Socket I/O is simulated; 3DS hardware, routing, and actual Wi-Fi are not verified.
The production 8-second deadline is retained. Build the support runner with MSVC first.
"""
import json,os,subprocess,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
RUNNER=ROOT/'diagnostics/network-host/network-client.exe'
RESULTS=[]

def wire(body=b'{"ok":true}',code=200):
    return f'HTTP/1.1 {code} Test\r\nContent-Length: {len(body)}\r\n\r\n'.encode()+body

def invoke(response=b'',scenario='normal',action='run',host='127.0.0.1',init_fail=False):
    path=ROOT/'diagnostics/network-host/response.bin';path.write_bytes(response)
    environment=dict(os.environ,TEST_RESPONSE_FILE=str(path),TEST_SCENARIO=scenario)
    if init_fail:environment['TEST_SOC_FAIL']='1'
    completed=subprocess.run([str(RUNNER),'8765',action,host],capture_output=True,text=True,timeout=13,env=environment)
    if completed.returncode:raise AssertionError(f'runner exit {completed.returncode}: {completed.stdout} {completed.stderr}')
    return json.loads(completed.stdout)

class TransportTests(unittest.TestCase):
    def check_result(self,name,result):
        RESULTS.append({'case':name,**result})
        if 'launch_ms' in result:
            self.assertLess(result['launch_ms'],100);self.assertLess(result['max_poll_ms'],100)
    def test_fixed_length_does_not_wait_for_close(self):
        r=invoke(wire(),'keepalive');self.check_result('fixed-length keepalive',r)
        self.assertEqual(r['code'],200);self.assertLess(r['elapsed_ms'],1000)
        self.assertEqual(bytes.fromhex(r['body_hex']),b'{"ok":true}');self.assertEqual(r['request_valid'],1)
    def test_partial_send(self):
        r=invoke(wire(),'partial-send');self.check_result('partial request writes',r)
        self.assertEqual(r['code'],200);self.assertEqual(r['request_valid'],1)
    def test_retryable_socket_errors(self):
        r=invoke(wire(),'retryable');self.check_result('EAGAIN on send and receive',r)
        self.assertEqual(r['code'],200);self.assertEqual(r['request_valid'],1)
    def test_fragmented_response_keeps_main_thread_free(self):
        r=invoke(wire(b'hello'),'fragmented');self.check_result('fragmented response',r)
        self.assertEqual(r['code'],200);self.assertEqual(bytes.fromhex(r['body_hex']),b'hello');self.assertGreater(r['ticks'],10)
    def test_close_delimited_response(self):
        r=invoke(b'HTTP/1.0 200 OK\r\n\r\nhello');self.check_result('close-delimited',r)
        self.assertEqual(r['code'],200);self.assertEqual(bytes.fromhex(r['body_hex']),b'hello')
    def test_authentication_failure(self):
        r=invoke(wire(b'denied',401));self.check_result('HTTP 401',r);self.assertEqual(r['code'],401)
    def test_truncated_body_is_rejected(self):
        r=invoke(b'HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\nshort');self.check_result('truncated body',r);self.assertEqual(r['code'],-1)
    def test_oversized_response_is_rejected(self):
        r=invoke(b'HTTP/1.1 200 OK\r\nContent-Length: 9000\r\n\r\n');self.check_result('oversized body',r);self.assertEqual(r['code'],-1)
    def test_chunked_response_is_rejected(self):
        r=invoke(b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n');self.check_result('unsupported encoding',r);self.assertEqual(r['code'],-1)
    def test_malformed_header_is_rejected(self):
        r=invoke(b'not HTTP\r\n\r\n');self.check_result('malformed header',r);self.assertEqual(r['code'],-1)
    def test_duplicate_content_length_is_rejected(self):
        r=invoke(b'HTTP/1.1 200 OK\r\nContent-Length: 0\r\nContent-Length: 5\r\n\r\n');self.check_result('duplicate length',r);self.assertEqual(r['code'],-1)
    def test_no_response_times_out_without_blocking_poll(self):
        r=invoke(scenario='silent');self.check_result('no response; production 8s deadline',r)
        self.assertEqual(r['code'],-1);self.assertGreater(r['elapsed_ms'],7800);self.assertLess(r['elapsed_ms'],9500);self.assertGreater(r['ticks'],100)
    def test_drip_response_does_not_reset_total_deadline(self):
        r=invoke(wire(b'x'*20),'drip');self.check_result('slow drip; total deadline',r)
        self.assertEqual(r['code'],-1);self.assertLess(r['elapsed_ms'],9500);self.assertGreater(r['ticks'],100)
    def test_cancel_has_bounded_wait(self):
        r=invoke(scenario='silent',action='cancel');self.check_result('cancel waiting response',r)
        self.assertLess(r['cancel_ms'],500);self.assertGreater(r['ticks'],2)
    def test_refused_connection(self):
        r=invoke(scenario='refused');self.check_result('refused connection',r);self.assertEqual(r['code'],-1);self.assertLess(r['elapsed_ms'],1000)
    def test_unavailable_gateway(self):
        r=invoke(host='auto');self.check_result('gateway unavailable',r);self.assertEqual(r['code'],-1)
    def test_service_initialization_failure(self):
        r=invoke(init_fail=True);self.check_result('socInit failure',r)
        self.assertEqual(r['service_result'],-42);self.assertEqual(r['init_code'],-1)

if __name__=='__main__':
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(TransportTests))
    report={'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'transport':'scripted I/O; production C worker and parser','hardwareVerified':False,'cases':RESULTS}
    (ROOT/'diagnostics/network-tests.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    raise SystemExit(not result.wasSuccessful())
