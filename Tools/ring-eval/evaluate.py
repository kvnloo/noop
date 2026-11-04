#!/usr/bin/env python3
"""Evaluate private Oura CSVs by replaying the production Kotlin detector.

Segment-only voice labels establish class/activity but NOT repetition counts. Recall for these
is null unless an explicit `attempts` list (elapsed_ms,label,activity) is added by review.
No raw data is written in the repository. See README.md for clock and legacy-mark assumptions.
"""
import argparse
import bisect
from collections import Counter, defaultdict
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CLASSES = {"DOUBLE_TAP": "double", "TRIPLE_TAP": "triple"}


def annotations(path):
    rows = [line.split(',') for line in path.read_text().splitlines()]
    frames = [int(r[1]) for r in rows if r[0] == 'f']
    if not frames:
        return [], [], []
    windows, negatives, segments = [], [], []
    blocks = {r[3]: r for r in rows if r[0] == 'k' and r[2] == 'start'}
    for r in rows:
        if r[0] == 'c' and r[2] in blocks:
            b = blocks[r[2]]
            label = {"2": "double", "3": "triple", "4": "quadruple", "5": "quintuple"}.get(b[6], "none")
            at = int(r[1])
            windows.append((at, min(at + 2500, frames[-1]), label, 'still'))
        if r[0] == 'k' and r[2] in ('end', 'abort') and r[3] in blocks:
            b = blocks[r[3]]
            if b[5] == 'NEGATIVE':
                activity = 'walking' if 'walking' in b[4] else 'handling'
                negatives.append((int(b[1]), min(int(r[1]), frames[-1]), activity))
    # v1 normal marks identify doubles; their timing relative to contact is unknown.
    # These disclosed windows are weak labels, not tap-contact timestamps.
    if not blocks:
        for r in rows:
            if r[0] == 'm':
                at = int(r[1])
                windows.append((at - 1000, at + 350, 'double', 'unknown'))
    sidecar = path.with_suffix('.labels.json')
    if sidecar.exists():
        meta = json.loads(sidecar.read_text())
        offset = meta['clock_offset_ms']
        end = meta.get('phone_stop_elapsed_ms', frames[-1])
        labels = meta.get('labels', [])
        activity = meta.get('activity', 'unknown')
        if 'walking' in meta.get('note', '').lower(): activity = 'walking'
        if 'still' in meta.get('note', '').lower(): activity = 'still'
        for i, label in enumerate(labels):
            note = label.get('note', '').lower()
            if 'walking' in note: activity = 'walking'
            if 'still' in note: activity = 'still'
            a = max(frames[0], label['ts'] * 1000 - offset)
            b = min(end, labels[i+1]['ts'] * 1000 - offset if i+1 < len(labels) else end)
            if b > a:
                segments.append((a, b, label['label'], activity))
                if label['label'] == 'none': negatives.append((a, b, activity))
        for item in meta.get('attempts', []):
            at = item['elapsed_ms']
            windows.append((at, at + 1500, item['label'], item.get('activity', 'unknown')))
    return windows, negatives, segments


def score(paths, output):
    bursts = defaultdict(list)
    for line in output.splitlines():
        v = line.split('\t')
        if len(v) == 11:
            bursts[v[0]].append(dict(at=int(v[1]), peak=int(v[2]), quiet=int(v[3]), impacts=int(v[4]),
                verdict=v[7], label=CLASSES.get(v[8], v[8].lower()), stillness=int(v[9]), last=int(v[10])))
    aggregate = defaultdict(Counter)
    latencies = defaultdict(list)
    files = []
    for path in paths:
        windows, negatives, segments = annotations(path)
        stats = defaultdict(Counter)
        for a,b,label,activity in windows:
            if label in ('double','triple'): stats[activity][label+'_attempts'] += 1
        for a,b,activity in negatives:
            stats[activity]['baseline_ms'] += max(0,b-a)
        mapping = [r.split(',') for r in path.read_text().splitlines() if r.startswith('s,')]
        sample_clocks = [int(r[1]) for r in mapping]
        elapsed_clocks = [int(r[2]) for r in mapping]
        used = set()
        unknown = 0
        for burst in bursts[path.name]:
            label, at = burst['label'], burst['at']
            if label not in ('double','triple'): continue
            # Attribute by last detected impact rather than quiet-tail decision time at a boundary.
            event = burst['last'] if burst['last'] >= 0 else at
            if sample_clocks:
                pos = max(0, bisect.bisect_right(sample_clocks, event) - 1)
                event = elapsed_clocks[pos]
            matches = [(i,w) for i,w in enumerate(windows) if w[0] <= event <= w[1]]
            if matches:
                i,w = min(matches, key=lambda iw: abs(event - iw[1][0]))
                activity = w[3]
                stats[activity][label+'_predictions'] += 1
                if i not in used and w[2] == label:
                    used.add(i); stats[activity][label+'_tp'] += 1
                else:
                    stats[activity][label+'_fp'] += 1
                    if w[2] in ('double','triple') and w[2] != label: stats[activity]['confusions'] += 1
            else:
                baseline = next((w for w in negatives if w[0] <= event <= w[1]), None)
                segment = next((w for w in segments if w[0] <= event <= w[1]), None)
                if baseline:
                    activity = baseline[2]
                    stats[activity]['false_fires'] += 1
                    stats[activity][label+'_predictions'] += 1
                    stats[activity][label+'_fp'] += 1
                elif segment:
                    # Do not turn model detections into attempted-repetition ground truth.
                    activity = segment[3]
                    stats[activity]['segment_'+label+'_detections'] += 1
                    if segment[2] != label: stats[activity]['segment_confusions'] += 1
                else:
                    unknown += 1
                    continue
            if burst['last'] >= 0: latencies[activity].append(at - burst['last'])
        for activity, values in stats.items(): aggregate[activity].update(values)
        files.append(dict(file=path.name, annotated_windows=len(windows), unscored_detections=unknown,
                          stats=dict(stats), bursts=len(bursts[path.name])))
    result = {}
    for activity,s in aggregate.items():
        result[activity] = dict(s)
        for label in ('double','triple'):
            result[activity][label+'_precision'] = s[label+'_tp']/s[label+'_predictions'] if s[label+'_predictions'] else None
            result[activity][label+'_recall'] = s[label+'_tp']/s[label+'_attempts'] if s[label+'_attempts'] else None
        result[activity]['false_fires_per_10min'] = s['false_fires']*600000/s['baseline_ms'] if s['baseline_ms'] else None
        n = s['double_attempts']+s['triple_attempts']
        result[activity]['confusion_rate'] = s['confusions']/n if n else None
        ls = latencies[activity]
        result[activity]['last_peak_to_decision_ms_p50'] = statistics.median(ls) if ls else None
    return dict(activities=result, files=files)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recordings', type=Path)
    parser.add_argument('--compiler-lib', type=Path, help='Kotlin compiler distribution lib directory (e.g. Gradle lib; does NOT invoke Gradle)')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    paths = sorted(args.recordings.glob('*.csv'))
    if not paths: parser.error('no CSV recordings')
    if len({p.name for p in paths}) != len(paths): parser.error('duplicate filenames')
    with tempfile.TemporaryDirectory(dir=os.environ.get('TMPDIR')) as tmp:
        jar = str(Path(tmp)/'replay.jar')
        sources = [str(ROOT/'android/app/src/main/java/com/noop/ble/gesture/OuraTapDetector.kt'), str(Path(__file__).with_name('RingReplay.kt'))]
        if args.compiler_lib:
            lib = args.compiler_lib
            stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
            subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),*sources,'-d',jar],check=True)
            command = ['java','-cp',jar+os.pathsep+str(lib/'*'),'RingReplayKt']
        else:
            if not shutil.which('kotlinc'): parser.error('install kotlinc or use --compiler-lib')
            subprocess.run(['kotlinc',*sources,'-include-runtime','-d',jar],check=True)
            command = ['java','-cp',jar,'RingReplayKt']
        results = {}
        for mode in ('old','new','candidate','off'):
            replay = subprocess.run([*command,mode,*map(str,paths)],check=True,text=True,capture_output=True)
            results[mode] = score(paths,replay.stdout)
        args.output.write_text(json.dumps(results,indent=2)+'\n')
        print(json.dumps({mode:r['activities'] for mode,r in results.items()},indent=2))

if __name__ == '__main__': main()
