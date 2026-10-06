"""Project exact reviewed subparts onto retained IDs without rewriting history."""
import hashlib
import json
import re
from urllib.parse import urlparse


def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def apply_exam_reviews(groups, records, mapping, simplify):
    if mapping.get('schema') != 'weibian-exam-review-map-v1':
        raise ValueError('unknown exam review map')
    source = {r['id']: r for r in records}
    targets = {g['id']: g for g in groups}
    reviewed = {r['id'] for r in records if r.get('source_review')}
    if set(mapping['records']) != reviewed:
        raise ValueError('unmapped source review')
    for record_id, route in mapping['records'].items():
        record = source[record_id]
        review = record['source_review']
        group = targets[route['groupId']]
        context = {k: record[k] for k in sorted(record) if re.fullmatch(r'topic|materials|material\d+|annotation|annotations', k)}
        if review.get('schema') != 'gk-answer-review-v1' or review.get('officialSource') is not False:
            raise ValueError('invalid exam review metadata')
        if digest(json.dumps(context, ensure_ascii=False, separators=(',', ':'))) != review['sourceContextSha256']:
            raise ValueError('exam review material drift')
        if not review.get('sources') or not review.get('sourceNote'):
            raise ValueError('missing exam source evidence')
        for evidence in review['sources']:
            url = urlparse(evidence['url'])
            if url.scheme != 'https' or url.netloc not in {'img.eol.cn', 'gaokao.eol.cn', 'cdn.gaokzx.com'} or not re.fullmatch('[a-f0-9]{64}', evidence['sha256']):
                raise ValueError('invalid exam source evidence')
        fields = set()
        corrected = dict(record)
        for correction in review.get('corrections', []):
            field, before, after = (correction[k] for k in ('field', 'from', 'to'))
            if not re.fullmatch(r'topic|material[1-9]\d*', field) or field in fields or not before or len(before) != len(after) or corrected[field].count(before) != 1:
                raise ValueError('invalid exam source correction')
            fields.add(field)
            corrected[field] = corrected[field].replace(before, after)
        material = '\n\n'.join(corrected.get(f'material{i}') or '' for i in range(1, 4)).strip()
        group['sourceReview'] = {
            'schema': 'weibian-exam-review-v1', 'sourceRecordId': record_id,
            'reviewedAt': review['reviewedAt'], 'officialSource': False,
            'topic': simplify(corrected.get('topic') or ''), 'material': simplify(material),
            'note': ' '.join(filter(None, [review['sourceNote'], review.get('scoreNote')])),
            'sources': review['sources'],
        }
        if set(route['answers']) != set(review['answers']):
            raise ValueError('unmapped reviewed subpart')
        questions = {q['id']: q for q in group['questions']}
        seen = set()
        for key, destinations in route['answers'].items():
            answer = review['answers'][key]
            source_question = next(q for q in record['questions'] if str(q['qIndex']) == key)
            if digest(source_question['text']) != answer['questionSha256'] or not answer['text'].strip():
                raise ValueError('exam review question drift')
            score = answer.get('printedScore')
            if score is not None and (type(score) is not int or score <= 0 or score > review['printedGroupScore']):
                raise ValueError('invalid reviewed score')
            for target in destinations:
                question = questions[target['id']]
                prompt_hash = digest(json.dumps(question['prompt'], ensure_ascii=False, separators=(',', ':')))
                if target['id'] in seen or prompt_hash != target['promptSha256']:
                    raise ValueError('legacy exam target drift')
                seen.add(target['id'])
                question['sourceReview'] = {
                    'sourceQIndex': int(key), 'questionSha256': answer['questionSha256'],
                    'answer': simplify(answer['text']), 'printedScore': score,
                }
        if seen != set(questions):
            raise ValueError('unreviewed legacy exam target')
    return groups
