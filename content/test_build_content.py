import copy
import unittest
from unittest.mock import patch
import build_content as build


class SourceAndHistoryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.chapters, cls.aliases = build.build_chapters()
        cls.bank = build.build_bank(cls.aliases)
        cls.concepts = build.build_concepts(cls.aliases)
        cls.figures = build.build_figures(cls.aliases)
        cls.exams = build.build_gaokao(cls.chapters)

    def history(self, exams=None, aliases=None):
        return build.history_problems(self.chapters, self.bank, self.concepts, self.figures, self.exams if exams is None else exams, self.aliases if aliases is None else aliases)

    def test_pinned_sources_preserve_every_published_exam_identity_and_score(self):
        self.assertEqual([], self.history())
        self.assertEqual(7, len(self.exams))
        self.assertEqual(23, sum(len(g['questions']) for g in self.exams))
        self.assertEqual(29, len(self.aliases))
        self.assertEqual(512, len(self.chapters))

    def test_concepts_and_figures_have_real_source_fields_and_canonical_refs(self):
        self.assertEqual([], build.validate(self.chapters, self.bank, self.concepts, self.figures, self.exams))
        self.assertEqual('仁', self.concepts[0]['name'])
        self.assertIn('思考：', self.concepts[0]['detail'])
        self.assertIn('易混点：', self.concepts[0]['detail'])
        self.assertIn('仲尼', self.figures[0]['style'])
        ids = {c['id'] for c in self.chapters}
        self.assertTrue(all(set(x['refs']) <= ids and x['refs'] for x in self.concepts + self.figures))

    def test_missing_fields_and_invalid_refs_cannot_pass_by_count_alone(self):
        for field in ['name', 'gloss', 'detail', 'refs']:
            concepts = copy.deepcopy(self.concepts)
            concepts[0][field] = [] if field == 'refs' else ''
            self.assertTrue(build.validate(self.chapters, self.bank, concepts, self.figures, self.exams))
        figures = copy.deepcopy(self.figures)
        figures[0]['refs'] = [999999]
        self.assertTrue(build.validate(self.chapters, self.bank, self.concepts, figures, self.exams))

    def test_legacy_question_numbers_and_explicit_scores_survive(self):
        rows = build.gk_questions({'questions': [], 'question1': '第一题（2分）', 'question2': None, 'question3': '未标分'})
        self.assertEqual(['q1', 'q3'], [q['id'] for q in rows])
        self.assertEqual([2, None], [q['score'] for q in rows])
        structured = [{'id':'q9','text':'题面','score':0}]
        self.assertEqual(structured, build.gk_questions({'questions': structured, 'question1':'旧投影'}))

    def test_malformed_input_fails_instead_of_dropping_questions(self):
        for item in [{'questions':{}}, {'questions':[{'stem':'未识别字段'}]}, {'question1':7}, {'question1':'  '}]:
            with self.assertRaises(ValueError):
                build.gk_questions(item)

    def test_history_guard_rejects_loss_renumbering_and_regrading(self):
        for mutation in [lambda g:g.pop(), lambda g:g[0]['questions'].pop(), lambda g:g[0]['questions'][0].update(id='replacement'), lambda g:g[0]['questions'][0].update(score=999), lambda g:g[0]['questions'][0].update(prompt='changed')]:
            exams = copy.deepcopy(self.exams)
            mutation(exams)
            self.assertTrue(self.history(exams=exams))
        aliases = dict(self.aliases)
        aliases.pop(next(iter(aliases)))
        self.assertTrue(self.history(aliases=aliases))

    def test_unpinned_file_or_wrong_git_blob_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'unpinned'):
            build.INPUTS.read(build.CF / 'lunyu/data/unreviewed.json')
        reader = copy.deepcopy(build.INPUTS)
        reader.cache = {}
        with patch('source_inputs.subprocess.check_output', return_value=b'wrong bytes'):
            with self.assertRaisesRegex(ValueError, 'hash mismatch'):
                reader.read(build.SRC_DIALOGUES)


if __name__ == '__main__':
    unittest.main()
