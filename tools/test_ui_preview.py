"""Behavioral checks for the real C UI/session functions used by the preview."""
import json,unittest
from pathlib import Path
from ui_preview import compile_preview
class PreviewTests(unittest.TestCase):
 @classmethod
 def setUpClass(cls):cls.lib,cls.digest=compile_preview()
 def setUp(self):self.lib.sg_reset();self.now=100000
 def frame(self,x=-1,y=-1,key=0,advance=0):
  self.now+=advance
  return json.loads(self.lib.sg_frame(self.now,x,y,key,0))
 def texts(self,f):return [x[8] for x in f['ops'] if x[0]=='text']
 def test_actual_top_and_bottom_drawn(self):
  f=self.frame();self.assertTrue({0,1}.issubset({x[1] for x in f['ops']}))
  self.assertIn('25:00',self.texts(f));self.assertIn('START',self.texts(f))
 def test_touch_changes_timer(self):
  f=self.frame(280,163);self.assertEqual(f['mins'],30)
  f=self.frame(30,163);self.assertEqual(f['mins'],25)
 def test_timer_limits(self):
  for _ in range(50):f=self.frame(key=5)
  self.assertEqual(f['mins'],180)
  for _ in range(50):f=self.frame(key=6)
  self.assertEqual(f['mins'],5)
 def test_start_stop_and_log_simulation(self):
  self.assertTrue(self.frame(30,200)['running'])
  f=self.frame(30,200,advance=12000)
  self.assertFalse(f['running']);self.assertEqual(f['today'],12);self.assertEqual(f['pending'],1)
 def test_subject_locked_while_running(self):
  self.frame(130,58);self.frame(key=1)
  self.assertEqual(self.frame(230,58)['subject'],1)
 def test_timer_controls_locked_while_running(self):
  self.frame(key=1);self.assertEqual(self.frame(280,163)['mins'],25)
 def test_stopwatch_counts_and_records(self):
  self.frame(260,130);self.frame(key=1)
  self.assertIn('02:00',self.texts(self.frame(advance=120000)))
  f=self.frame(key=2);self.assertEqual(f['today'],120)
 def test_pomodoro_auto_enters_break(self):
  self.frame(130,130);self.frame(key=1);self.frame(advance=1500000)
  f=self.frame();self.assertTrue(f['running']);self.assertIn('05:00',self.texts(f));self.assertIn('BREAK',self.texts(f))
 def test_character_visibility(self):
  self.frame(key=3);self.assertFalse(any(x[0]=='character' for x in self.frame()['ops']))
if __name__=='__main__':
 result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(PreviewTests))
 Path('diagnostics/ui-preview-tests.json').write_text(json.dumps({'tests':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'source':'actual C draw and session functions','hardwareVerified':False},indent=2))
 raise SystemExit(not result.wasSuccessful())

