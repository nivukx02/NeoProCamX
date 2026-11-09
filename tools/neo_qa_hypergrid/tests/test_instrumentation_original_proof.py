import unittest

from tools.neo_qa_hypergrid.audit_lq001_cinema_master import original_proof_from_instrumentation


class InstrumentationOriginalProofTest(unittest.TestCase):
    def test_exact_status_key_is_selected_not_unrelated_old_log_text(self):
        line = 'ACTUAL_RECORDED_SAMPLE name=first index=0 master=owned.npv10'
        self.assertEqual(line, original_proof_from_instrumentation(
            'old log ACTUAL_RECORDED_SAMPLE name=wrong\n'
            'INSTRUMENTATION_STATUS: neoNpv10OriginalProof=' + line + '\n'))

    def test_no_missing_or_near_matching_key_can_create_proof(self):
        for output in ('', 'ACTUAL_RECORDED_SAMPLE name=first',
                       'INSTRUMENTATION_STATUS: neoNpv10OriginalProo=ACTUAL_RECORDED_SAMPLE x'):
            with self.subTest(output=output), self.assertRaises(ValueError):
                original_proof_from_instrumentation(output)

    def test_unknown_proof_type_is_rejected(self):
        with self.assertRaises(ValueError):
            original_proof_from_instrumentation('INSTRUMENTATION_STATUS: neoNpv10OriginalProof=PASS')

    def test_failed_timeline_keeps_evidence_but_does_not_add_success(self):
        proof = 'ACTUAL_COMPLETE_FRAME_TIMELINE frames=10 intervalsAbove1p5FramePeriods=1'
        output = 'INSTRUMENTATION_STATUS: neoNpv10OriginalProof=' + proof + '\nFAILURES!!!'
        self.assertEqual(proof, original_proof_from_instrumentation(output))
        self.assertNotIn('OK (2 tests)', output)
