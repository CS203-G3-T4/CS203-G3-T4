"""Only synthetic inputs; no real resident, market or calibration data."""
from copy import deepcopy
from datetime import timedelta
from pathlib import Path
import hashlib
import json
import socket
import pytest
from wattly_ml import spike_policy_validation as grade

FIXTURES = Path(__file__).parent / "fixtures"

def inputs():
    return (json.loads((FIXTURES / "spike-policy-snapshot.synthetic.json").read_text()),
            json.loads((FIXTURES / "spike-policy-protocol.synthetic.json").read_text()))

def evaluate(snapshot=None, protocol=None):
    base, fixed = inputs()
    return grade.evaluate(snapshot or base, protocol or fixed, "preliminary", "2026-01-02T03:10:00Z")

def test_synthetic_example_has_hand_calculated_counts_without_production_claim():
    result = evaluate()
    assert result["synthetic"] and not result["inputAuthenticityVerified"]
    assert not result["productionEligible"] and not result["runtimeGuardsExecuted"]
    assert result["priceForecasts"]["AI"]["dependentRunTargetPairs"] == {
        "tp": 1, "fp": 0, "fn": 0, "tn": 2, "pairs": 3, "distinctTargets": 3,
        "precision": 1.0, "recall": 1.0,
        "interpretation": "Arithmetic on statistical proxy labels; no independent human labels or population-confidence claim"}
    assert result["priceForecasts"]["B3"]["dependentRunTargetPairs"]["fn"] == 1
    assert result["observedRule"]["classifications"] == {"SPIKE": 1}
    assert result["candidateEpisodes"]["jointUsefulEpisodes"] == 1
    assert result["twoFalseInTenTarget"] == "UNDETERMINED"

def test_later_history_receipts_do_not_rewrite_the_original_reference():
    snapshot, _ = inputs()
    for row in snapshot["history"][:8]:
        later = dict(row, id=row["id"]+200, published_at="2026-01-02T00:45:00Z",
                     available_at="2026-01-02T02:00:00Z", usep=5000)
        snapshot["history"].append(later)
    assert evaluate(snapshot)["observedRule"]["classifications"] == {"SPIKE": 1}

def test_truth_and_losses_cannot_select_a_different_candidate():
    snapshot, _ = inputs()
    before = evaluate(snapshot)
    snapshot["pairs"][0]["actual"] = 125
    snapshot["opportunities"][0]["outcome"]["AI"].update(chosenCostSgd=.125, savingSgd=-.015)
    after = evaluate(snapshot)
    assert before["candidateWarningPlanCount"] == after["candidateWarningPlanCount"] == 1
    assert after["candidateEpisodes"]["episodesWithHarmfulMoves"] == 1
    assert after["candidateEpisodes"]["jointUsefulEpisodes"] == 0

@pytest.mark.parametrize("available,days,lower", [(False,20,.1),(True,19,.1),(True,20,0),(True,20,-.1)])
def test_missing_immature_or_nonpositive_ranges_withhold_warnings(available,days,lower):
    snapshot, _ = inputs()
    snapshot["opportunities"][0]["prediction"]["aiRange"].update(available=available,completedDays=days,lowerSgd=lower)
    result = evaluate(snapshot)
    assert result["candidateWarningPlanCount"] == 0
    assert result["candidateEpisodes"]["spikeWarningPrecision"] is None
    assert result["twoFalseInTenTarget"] == "UNDETERMINED"

def test_pending_actual_is_not_a_normal_label_or_false_warning():
    snapshot, _ = inputs()
    snapshot["pairs"][1].update(actual=None,evaluated_at=None)
    result = evaluate(snapshot)
    assert result["excludedPairs"]["PENDING_TRUTH"] == 1
    assert result["candidateEpisodes"]["pendingEpisodes"] == 1
    assert result["candidateEpisodes"]["falseStatisticalSpikeEpisodes"] == 0

def test_first_issued_target_is_not_replaced_by_a_later_bad_prediction():
    snapshot, _ = inputs()
    for row in list(snapshot["pairs"]):
        later = dict(row, run_id="SYNTHETIC_LATER", as_of="2026-01-02T00:05:00Z", captured_at="2026-01-02T00:05:01Z")
        if later["horizon"] == 1:
            later["ai"] = 999
        snapshot["pairs"].append(later)
    result = evaluate(snapshot)["priceForecasts"]["AI"]
    assert result["dependentRunTargetPairs"]["fp"] == 1
    assert result["fixedLeadFirstIssueTargets"]["1"]["fp"] == 0
    assert result["fixedLeadFirstIssueTargets"]["1"]["pairs"] == 1

def test_overlapping_appliances_are_one_household_episode():
    snapshot, _ = inputs()
    other = deepcopy(snapshot["opportunities"][0]);other.update(id=2,appliance_id=2)
    snapshot["opportunities"].append(other)
    result = evaluate(snapshot)
    assert result["candidateWarningPlanCount"] == 2
    assert result["candidateEpisodes"]["warningEpisodes"] == 1

@pytest.mark.parametrize("kind", ["naive","duplicate_pair","duplicate_plan","different_model","different_trial",
                                  "alignment","nonfinite","future_truth","future_history","costs","changed_policy"])
def test_incompatible_or_leaking_inputs_are_rejected(kind):
    snapshot, protocol = inputs()
    if kind == "naive":snapshot["pairs"][0]["captured_at"] = "2026-01-02T00:00:01"
    if kind == "duplicate_pair":snapshot["pairs"].append(deepcopy(snapshot["pairs"][0]))
    if kind == "duplicate_plan":snapshot["opportunities"].append(deepcopy(snapshot["opportunities"][0]))
    if kind == "different_model":snapshot["pairs"][0]["model_version"] = "OTHER_MODEL"
    if kind == "different_trial":snapshot["opportunities"][0]["trial_id"] = "OTHER_TRIAL"
    if kind == "alignment":snapshot["pairs"][0]["target_period"] = "2026-01-02T00:31:00Z"
    if kind == "nonfinite":snapshot["pairs"][0]["ai"] = float("nan")
    if kind == "future_truth":snapshot["pairs"][0]["evaluated_at"] = "2026-01-03T00:00:00Z"
    if kind == "future_history":snapshot["history"][0]["available_at"] = "2026-01-03T00:00:00Z"
    if kind == "costs":snapshot["opportunities"][0]["outcome"]["AI"]["savingSgd"] = 500
    if kind == "changed_policy":protocol["candidate"]["minimumEstimatedSavingSgd"] = .2
    with pytest.raises(ValueError):evaluate(snapshot,protocol)

def test_scoring_time_and_prospective_maturity_are_explicit():
    snapshot, protocol = inputs()
    with pytest.raises(ValueError,match="scoring time"):
        grade.evaluate(snapshot,protocol,"preliminary","2026-01-02T02:00:00Z")
    with pytest.raises(ValueError,match="not matured"):
        grade.evaluate(snapshot,protocol,"prospective","2026-01-02T03:10:00Z")

def test_cli_is_offline_and_preserves_inputs_and_existing_output(tmp_path,monkeypatch,capsys):
    snapshot=FIXTURES/"spike-policy-snapshot.synthetic.json";protocol=FIXTURES/"spike-policy-protocol.synthetic.json"
    before={p:p.read_bytes() for p in [snapshot,protocol]}
    def forbidden(*args,**kwargs):raise AssertionError("Network access forbidden")
    monkeypatch.setattr(socket,"create_connection",forbidden)
    monkeypatch.setenv("DB_URL","do-not-read-this-environment-value")
    output=tmp_path/"report.json"
    argv=["--snapshot",str(snapshot),"--protocol",str(protocol),"--output",str(output),"--mode","preliminary","--as-of","2026-01-02T03:10:00Z"]
    grade.main(argv)
    result=json.loads(output.read_text());assert result["synthetic"]
    assert result["snapshotSha256"]==hashlib.sha256(snapshot.read_bytes()).hexdigest()
    saved=output.read_bytes()
    with pytest.raises(SystemExit) as error:grade.main(argv)
    assert error.value.code==2 and output.read_bytes()==saved
    assert all(p.read_bytes()==value for p,value in before.items())

def test_duplicate_json_keys_and_size_limit_refuse_without_a_report(tmp_path,monkeypatch):
    source=tmp_path/"duplicate.json";source.write_text('{"pairs": [], "pairs": []}')
    output=tmp_path/"report.json";protocol=FIXTURES/"spike-policy-protocol.synthetic.json"
    with pytest.raises(ValueError,match="Duplicate JSON"):
        grade.evaluate_files(source,protocol,output,"preliminary","2026-01-02T03:10:00Z")
    assert not output.exists()
    monkeypatch.setattr(grade,"MAX_INPUT_BYTES",1)
    with pytest.raises(ValueError,match="limit"):
        grade.evaluate_files(source,protocol,output,"preliminary","2026-01-02T03:10:00Z")
    assert not output.exists()
