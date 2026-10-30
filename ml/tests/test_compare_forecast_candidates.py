from pathlib import Path
import gzip,json,runpy,sys

def test_saved_opportunity_selection_ignores_json_whitespace(tmp_path,monkeypatch):
    root=Path(__file__).resolve().parents[2]
    monkeypatch.setattr(sys,'argv',['compare','--research-root',str(tmp_path),'--output',str(tmp_path/'unused')])
    script=runpy.run_path(str(root/'scripts/compare-forecast-candidates.py'))
    p=tmp_path/'rows.gz'
    with gzip.open(p,'wt') as stream:
        stream.write('{"evaluation":true,"id":1}\n')
        stream.write('{"evaluation": true, "id": 2}\n')
        stream.write('{"evaluation":false,"id":3}\n')
    assert [r['id'] for r in script['evaluation_opportunities'](p)]==[1,2]
