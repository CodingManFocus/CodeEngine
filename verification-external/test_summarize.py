import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import summarize


class SummaryEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'environment.json').write_text(json.dumps({'scenario': 'benchmark', 'forks': 1, 'measurementTicks': 2}))
        self.fork = self.root / 'fork-1'
        self.fork.mkdir()
        (self.fork / 'server.log').write_text('EXTERNAL VERIFICATION PASS checks=1')
        (self.fork / 'checks.txt').write_text('PASS verified\n')
        (self.fork / 'cold-loads.csv').write_text('index,nanos\n' + ''.join(f'{i},1000\n' for i in range(6)))
        self.rows = [(i, path, workload, 2048, 0, operations)
                     for workload, operations in summarize.workloads.items()
                     for path in ('native', 'module') for i in range(2)]
        self.writeSamples()

    def writeSamples(self):
        with (self.fork / 'samples.csv').open('w') as output:
            writer = csv.writer(output)
            writer.writerow(['sample', 'path', 'workload', 'nanos', 'bytes', 'operations'])
            writer.writerows(self.rows)

    def testCompletePairs(self):
        result = summarize.summarize(self.root)
        self.assertEqual(2, result['fork-1']['apiSingle']['pairs'])
        self.assertEqual(0, result['fork-1']['apiSingle']['pairedMedianDeltaNs'])

    def testMissingPair(self):
        self.rows.pop()
        self.writeSamples()
        with self.assertRaises(ValueError): summarize.summarize(self.root)

    def testDuplicatePair(self):
        self.rows.append(self.rows[0])
        self.writeSamples()
        with self.assertRaises(ValueError): summarize.summarize(self.root)

    def testMissingWorkload(self):
        self.rows = [row for row in self.rows if row[2] != 'apiSingle']
        self.writeSamples()
        with self.assertRaises(ValueError): summarize.summarize(self.root)

    def testMissingSuccess(self):
        (self.fork / 'server.log').write_text('server stopped')
        with self.assertRaises(ValueError): summarize.summarize(self.root)

    def testFailureEvidence(self):
        (self.fork / 'failure.txt').write_text('failed')
        with self.assertRaises(ValueError): summarize.summarize(self.root)

    def testMissingInputCannotOverwriteSummary(self):
        (self.fork / 'samples.csv').unlink()
        summary = self.root / 'summary.json'
        summary.write_text('previous evidence\n')
        process = subprocess.run([sys.executable, str(Path(summarize.__file__)), str(self.root)], capture_output=True)
        self.assertNotEqual(0, process.returncode)
        self.assertEqual('previous evidence\n', summary.read_text())


if __name__ == '__main__':
    unittest.main()
