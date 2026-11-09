"""Parser regressions using retained physical-camera logs, not a camera simulation."""
from pathlib import Path
import re
import unittest

from tools.neo_qa_hypergrid.audit_lq001_cinema_master import select_actual_recording_evidence


class CinemaMasterLogProofTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.logs = (Path(__file__).parent / 'fixtures' / 'lq001_actual_master_log_20260910.txt').read_text(encoding='utf-8')
        cls.master = re.search(r'master=(\S+)', cls.logs)[1]
        cls.lines = [line for line in cls.logs.splitlines() if 'ACTUAL_' in line]

    def test_retained_real_log_passes_exact_three_sample_and_timeline_contract(self):
        samples, timeline = select_actual_recording_evidence(self.logs, self.master, 7547)
        self.assertEqual([0, 3773, 7546], [int(item['index']) for item in samples])
        self.assertEqual('25.016462395370656', timeline['fps'])

    def test_empty_or_evicted_log_cannot_pass(self):
        with self.assertRaises(ValueError):
            select_actual_recording_evidence('', self.master, 7547)

    def test_master_name_requires_exact_match_not_prefix(self):
        with self.assertRaises(ValueError):
            select_actual_recording_evidence(self.logs, self.master[:-1], 7547)

    def test_missing_sample_cannot_pass(self):
        with self.assertRaises(ValueError):
            select_actual_recording_evidence('\n'.join(line for line in self.lines if 'name=first ' not in line), self.master, 7547)

    def test_duplicate_sample_cannot_replace_missing_sample(self):
        with self.assertRaises(ValueError):
            select_actual_recording_evidence(self.logs + '\n' + self.lines[0], self.master, 7547)

    def test_wrong_sample_index_cannot_pass(self):
        with self.assertRaises(ValueError):
            select_actual_recording_evidence(self.logs.replace('index=3773 ', 'index=3774 '), self.master, 7547)

    def test_invalid_sample_hash_cannot_pass(self):
        with self.assertRaises(ValueError):
            select_actual_recording_evidence(re.sub(r'sha256=[a-f0-9]{64}', 'sha256=invalid', self.logs, count=1), self.master, 7547)

    def test_new_incomplete_export_cannot_reuse_old_complete_export(self):
        newest = self.lines[0].replace('-130654164478384/first.p010', '-130654164478385/first.p010')
        with self.assertRaises(ValueError):
            select_actual_recording_evidence(self.logs + '\n' + newest, self.master, 7547)

    def test_missing_timeline_cannot_pass(self):
        with self.assertRaises(ValueError):
            select_actual_recording_evidence('\n'.join(line for line in self.lines if 'ACTUAL_COMPLETE_FRAME_TIMELINE' not in line), self.master, 7547)

    def test_conflicting_latest_timeline_cannot_fall_back(self):
        latest = self.lines[-1].replace('frames=7547 ', 'frames=7546 ')
        with self.assertRaises(ValueError):
            select_actual_recording_evidence(self.logs + '\n' + latest, self.master, 7547)

    def test_other_master_logs_cannot_overwrite_selected_recording(self):
        unrelated = self.logs.replace(self.master, 'unrelated-master.npv10')
        samples, timeline = select_actual_recording_evidence(self.logs + unrelated, self.master, 7547)
        self.assertEqual(3, len(samples))
        self.assertEqual(self.master, timeline['master'])


if __name__ == '__main__':
    unittest.main()
