#!/usr/bin/env python3
"""Summarize per-JVM batch medians, retaining all raw samples."""
import csv, json, statistics
from pathlib import Path
root=Path(__file__).resolve().parent/'results'
forks=[]
for index in range(1,4):
    rows=list(csv.DictReader((root/f'fork-{index}/samples.csv').open()))
    metrics={}
    for task in sorted({row['task'] for row in rows}):
        selected=[row for row in rows if row['task']==task]
        metrics[task]={'medianNs':statistics.median(float(row['nsPerOperation']) for row in selected),'medianBytes':statistics.median(float(row['bytesPerOperation']) for row in selected),'samples':len(selected)}
    forks.append({'fork':index,'metrics':metrics})
representative={task:{metric:statistics.median(fork['metrics'][task][metric] for fork in forks) for metric in ['medianNs','medianBytes']} for task in metrics}
summary={'paper':'1.21.11 build 132 c5eb079','paperSha256':'5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba','java':'Amazon Corretto 21.0.12.1+9-LTS','jvm':'-Xms512M -Xmx2G, default GC','forks':forks,'representative':representative,'ratios':{task:representative['cebench'+task]['medianNs']/representative['native'+task]['medianNs'] for task in ['calc','blocks']}}
(root/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({'representative':representative,'ratios':summary['ratios']},indent=2))
