"""Scoring semantics only; synthetic temporary fixtures, never hardware evidence."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('ring_evaluate', Path(__file__).with_name('evaluate.py'))
evaluate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluate)


class ScoringTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR'))
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)/'fixture.csv'

    def test_unlabelled_data_is_not_fabricated_as_baseline(self):
        self.path.write_text('f,1000,00,0,0,1000\nf,5000,00,0,0,1000\n')
        result = evaluate.score([self.path], 'fixture.csv\t2000\t900\t150\t2\t200\t300\tGESTURE\tDOUBLE_TAP\t0\t1700\n')
        self.assertEqual(result['activities'],{})
        self.assertEqual(result['files'][0]['unscored_detections'],1)

    def test_voice_segments_do_not_invent_attempt_denominator(self):
        self.path.write_text('f,1000,00,0,0,1000\nf,6000,00,0,0,1000\n')
        self.path.with_suffix('.labels.json').write_text(json.dumps(dict(clock_offset_ms=0,
            phone_stop_elapsed_ms=6000,note='walking',labels=[dict(ts=1,label='double',note=''),dict(ts=4,label='none',note='')])))
        output = ('fixture.csv\t2000\t900\t150\t2\t200\t300\tGESTURE\tDOUBLE_TAP\t0\t1700\n'
                  'fixture.csv\t5000\t900\t150\t2\t200\t300\tGESTURE\tDOUBLE_TAP\t0\t4700\n')
        result = evaluate.score([self.path], output)['activities']['walking']
        self.assertIsNone(result['double_recall'])
        self.assertEqual(result['segment_double_detections'],1)
        self.assertEqual(result['false_fires'],1)
        self.assertEqual(result['baseline_ms'],2000)

    def test_guided_window_confusion_is_not_a_correct_detection(self):
        self.path.write_text('f,1000,00,0,0,1000\nk,1200,start,0,triple-normal,TAPS,3,normal,1,3000,3000\nc,1200,0,1\nf,5000,00,0,0,1000\n')
        output = 'fixture.csv\t2000\t900\t150\t2\t200\t300\tGESTURE\tDOUBLE_TAP\t0\t1700\n'
        result = evaluate.score([self.path],output)['activities']['still']
        self.assertEqual(result['triple_recall'],0)
        self.assertEqual(result['confusion_rate'],1)
        self.assertEqual(result['double_precision'],0)


if __name__ == '__main__': unittest.main()
