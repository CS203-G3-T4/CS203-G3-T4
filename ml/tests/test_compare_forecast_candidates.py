from pathlib import Path
import gzip,json,runpy,sys
import shutil
import pytest

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


def replay_fixture(tmp_path,monkeypatch):
    root=Path(__file__).resolve().parents[2]
    research=tmp_path/'research';delivery=tmp_path/'delivery'
    java='src/main/java/sg/edu/smu/cs203/'
    for name in ('household/appliance/TimeWindow.java','recommendation/CheapestStartCalculator.java'):
        for directory in (research,delivery):
            path=directory/(java+name);path.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(root/(java+name),path)
    original=research/(java+'recommendation/PriceForecast.java')
    original.parent.mkdir(parents=True,exist_ok=True)
    frozen=delivery/'scripts/forecast-replay/PriceForecast.java';frozen.parent.mkdir(parents=True)
    for path in (original,frozen):shutil.copyfile(root/'scripts/forecast-replay/PriceForecast.java',path)
    # The live record has extra provenance fields; it must not be a replay dependency.
    shutil.copyfile(root/(java+'recommendation/PriceForecast.java'),delivery/(java+'recommendation/PriceForecast.java'))
    monkeypatch.setattr(sys,'argv',['compare','--research-root',str(research),'--output',str(tmp_path/'unused')])
    script=runpy.run_path(str(root/'scripts/compare-forecast-candidates.py'))
    return script,research,delivery


def test_replay_keeps_frozen_dto_when_live_provenance_changes(tmp_path,monkeypatch):
    script,research,delivery=replay_fixture(tmp_path,monkeypatch)
    sources=script['replay_sources'](research,delivery)
    assert sources[-1]==delivery/'scripts/forecast-replay/PriceForecast.java'
    live=delivery/'src/main/java/sg/edu/smu/cs203/recommendation/PriceForecast.java'
    assert script['sha'](live)!=script['FROZEN_PRICE_FORECAST_SHA256']
    assert live not in sources


@pytest.mark.parametrize('changed',['calculator','original_dto','frozen_dto'])
def test_replay_still_rejects_changed_calculation_or_frozen_contract(tmp_path,monkeypatch,changed):
    script,research,delivery=replay_fixture(tmp_path,monkeypatch)
    path={'calculator':delivery/'src/main/java/sg/edu/smu/cs203/recommendation/CheapestStartCalculator.java',
          'original_dto':research/'src/main/java/sg/edu/smu/cs203/recommendation/PriceForecast.java',
          'frozen_dto':delivery/'scripts/forecast-replay/PriceForecast.java'}[changed]
    path.write_text(path.read_text()+'\n// changed\n')
    with pytest.raises(ValueError,match='differs|changed'):script['replay_sources'](research,delivery)
