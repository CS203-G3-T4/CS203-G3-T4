#!/usr/bin/env python3
"""Explicit offline commands; no timers, network calls, retries, or live promotion."""
import argparse
import json
from pathlib import Path
from wattly_ml.cli import report
from wattly_ml.demand_candidate import readiness, fit, evaluate
from wattly_ml.timebase import instant


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['readiness', 'fit', 'evaluate'])
    for name in ('issues', 'prices', 'protocol', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--as-of', type=instant, required=True)
    parser.add_argument('--experiment', type=Path)
    parser.add_argument('--frozen-model', type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Output already exists; preserve prior experiment evidence')
    issues = [json.loads(line) for line in args.issues.read_text().splitlines()]
    prices = [json.loads(line) for line in args.prices.read_text().splitlines()]
    protocol = json.loads(args.protocol.read_text())
    if args.action == 'readiness':
        result = readiness(issues, prices, protocol, args.as_of)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('x') as stream:
            json.dump(result, stream, indent=2, allow_nan=False)
            stream.write('\n')
    elif args.action == 'fit':
        result = fit(issues, prices, protocol, args.as_of, args.output)
    else:
        if args.experiment is None or args.frozen_model is None:
            parser.error('Evaluation requires --experiment and --frozen-model')
        records, result = evaluate(issues, prices, protocol, args.as_of, args.experiment, args.frozen_model)
        report(args.output, records, result)
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
