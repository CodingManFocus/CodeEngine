#!/usr/bin/env python3
"""Run only against a fresh, disposable Paper server. No existing worlds are modified."""
import argparse, json, os, re, shutil, subprocess, time
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('--paper', type=Path, required=True)
parser.add_argument('--engine-jar', type=Path)
parser.add_argument('--unload-timeout-seconds', type=int, default=1)
parser.add_argument('--java', type=Path, required=True)
parser.add_argument('--prepared-server', type=Path)
parser.add_argument('--work', type=Path, required=True)
parser.add_argument('--accept-eula', action='store_true')
parser.add_argument('--ui', action='store_true')
parser.add_argument('--ui-only', action='store_true')
parser.add_argument('--forks', type=int, default=3)
parser.add_argument('--start-index', type=int, default=1)
parser.add_argument('--results-dir', type=Path)
args=parser.parse_args()
if not args.accept_eula: parser.error('The disposable Minecraft server requires --accept-eula.')
root=Path(__file__).resolve().parent.parent
results=args.results_dir or root/'verification/results'
results.mkdir(parents=True,exist_ok=True)
args.work.mkdir(parents=True, exist_ok=True)
for fork in range(args.start_index,args.start_index+args.forks):
    server=args.work/f'fork-{fork}'
    if server.exists(): raise SystemExit(f'Refusing to reuse existing server: {server}')
    server.mkdir()
    shutil.copy2(args.paper, server/'paper.jar')
    if args.prepared_server:
        for directory in ['libraries','versions','cache']:
            origin=args.prepared_server/directory
            if origin.exists(): shutil.copytree(origin,server/directory,copy_function=os.link)
    plugins=server/'plugins'; plugins.mkdir(); data=plugins/'CodeEngine'; data.mkdir()
    shutil.copy2(args.engine_jar or root/'codeengine-plugin/build/libs/codeengine-plugin-0.1.0.jar',plugins/'CodeEngine.jar')
    shutil.copy2(root/'verification/build/libs/verification-0.1.0.jar',plugins/'Verification.jar')
    (data/'config.yml').write_text(f'autoLoad: false\nseedExample: false\nwebPort: 17887\nunloadTimeoutSeconds: {args.unload_timeout_seconds}\n')
    (server/'eula.txt').write_text('eula=true\n')
    (server/'server.properties').write_text('server-ip=127.0.0.1\nserver-port=25586\nonline-mode=false\nlevel-seed=123456789\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nspawn-protection=0\n')
    (server/'config').mkdir()
    (server/'config/paper-global.yml').write_text('spark:\n  enabled: false\n')
    logfile=server/'console.log'; output=logfile.open('w')
    command=[str(args.java),'-Xms512M','-Xmx2G','-Dterminal.jline=false','-Dterminal.ansi=false','-Dpaper.disablePluginRemapping=true','-jar','paper.jar','nogui']
    process=subprocess.Popen(command,cwd=server,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
    def console(command): process.stdin.write(command+'\n'); process.stdin.flush()
    def wait_for(predicate,timeout):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            text=logfile.read_text(errors='replace')
            if process.poll() is not None: raise RuntimeError(f'Server exited {process.returncode}: {text[-5000:]}')
            if 'VERIFICATION FAILED' in text: raise RuntimeError(text[-5000:])
            if predicate(text): return text
            time.sleep(.25)
        raise TimeoutError(logfile.read_text(errors='replace')[-5000:])
    print(f'Fork {fork}: starting Paper',flush=True)
    try:
        wait_for(lambda text: 'Done (' in text,120)
        if args.ui_only:
            console('ce web')
            text=wait_for(lambda text:'WebIDE (private session):' in text,10)
            url=re.findall(r'WebIDE \(private session\): (http://\S+)',text)[-1]
            environment=os.environ.copy(); environment['CE_URL']=url; environment['CE_SCREENSHOT']=str(root/'verification/results/webide-desktop.png')
            subprocess.run(['node',str(root/'verification/web-smoke.cjs')],env=environment,check=True,timeout=90)
            print('Final WebIDE workflow passed',flush=True)
            continue
        console('ceverify')
        wait_for(lambda text:'LIFECYCLE PASS' in text,90)
        print(f'Fork {fork}: lifecycle passed; measuring',flush=True)
        wait_for(lambda text:'VERIFICATION PASS' in text,90)
        destination=results/f'fork-{fork}';destination.mkdir(exist_ok=True)
        for name in ['samples.csv','checks.txt']:
            shutil.copy2(plugins/'CodeEngineVerification'/name,destination/name)
        if (destination/'generated').exists(): shutil.rmtree(destination/'generated')
        shutil.copytree(data/'builds', destination/'generated')
        if args.ui and fork==args.start_index:
            console('ce web')
            text=wait_for(lambda text:'WebIDE (private session):' in text,10)
            url=re.findall(r'WebIDE \(private session\): (http://\S+)',text)[-1]
            environment=os.environ.copy(); environment['CE_URL']=url; environment['CE_SCREENSHOT']=str(root/'verification/results/webide-desktop.png')
            subprocess.run(['node',str(root/'verification/web-smoke.cjs')],env=environment,check=True,timeout=90)
            print('WebIDE browser workflow passed',flush=True)
        destination=results/f'fork-{fork}';destination.mkdir(exist_ok=True)
        for name in ['samples.csv','checks.txt']:
            shutil.copy2(plugins/'CodeEngineVerification'/name,destination/name)
        print(f'Fork {fork}: verification passed',flush=True)
    finally:
        if process.poll() is None:
            console('stop')
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=10)
        output.close()
        destination=results/('browser' if args.ui_only else f'fork-{fork}');destination.mkdir(exist_ok=True)
        log=logfile.read_text(errors='replace')
        log=re.sub(r'(WebIDE \(private session\): http://[^#\s]+)#\S+',r'\1#[REDACTED]',log)
        (destination/'server.log').write_text(log)
        if process.returncode != 0: raise RuntimeError(f'Server exit: {process.returncode}')
