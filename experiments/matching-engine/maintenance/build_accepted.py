"""Rebuild ALL accepted classes from versioned sources; never overwrite an existing canonical JAR.
Run from repository root: python -X utf8 -B experiments/matching-engine/maintenance/build_accepted.py
Requires Java25 and Maven wrapper. No SQL or server is started.
"""
from pathlib import Path
import os,sys,json,shutil,subprocess,zipfile,hashlib
HERE=Path(__file__).resolve().parent;MODULE=HERE.parent;ROOT=MODULE.parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def verify(jar):
    spec=json.loads((HERE/'ACCEPTED_JAR_CONTENT_SHA256.json').read_text())
    with zipfile.ZipFile(jar) as z:
        assert len(z.namelist())==len(set(z.namelist()))
        assert {e.filename:hashlib.sha256(z.read(e)).hexdigest() for e in z.infolist()}==spec['entries']
def main():
    dest=MODULE/'target/matching-engine-0.1-SNAPSHOT.jar'
    freeze=json.loads((MODULE/'stage6/final-acceptance/ENGINE_FREEZE.json').read_text())
    for name,h in freeze['mainSourceSHA256'].items():assert sha(MODULE/Path(name.replace('\\','/')))==h
    if dest.exists():verify(dest);print('Existing accepted-content JAR verified; no rebuild required');return
    work=HERE/'build-isolated'
    if work.exists():raise RuntimeError('Isolated build already exists; inspect it rather than overwriting it')
    work.mkdir();shutil.copy2(MODULE/'pom.xml',work/'pom.xml');shutil.copytree(MODULE/'src',work/'src')
    for relative in ['stage4batch/baseline-4b1/src/main/java','stage3/corpora']:
        shutil.copytree(MODULE/relative,work/relative)
    cmd=[str(ROOT/'mvnw.cmd' if os.name=='nt' else ROOT/'mvnw'),'-q','-f',str(work/'pom.xml'),'test','package']
    if os.name=='nt':
        ps=Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe'
        cmd=[str(ps),'-NoProfile','-ExecutionPolicy','Bypass','-File',str(HERE/'invoke-maven.ps1'),'-Wrapper',str(ROOT/'mvnw.cmd'),'-Pom',str(work/'pom.xml')]
    subprocess.run(cmd,check=True,timeout=600)
    sources=json.loads((HERE/'ACCEPTED_HARNESS_SOURCES.json').read_text())['sources'];classes=work/'target/classes'
    for name,h in sources.items():assert sha(MODULE/name)==h
    javac=Path(os.environ['JAVA_HOME'])/'bin'/('javac.exe' if os.name=='nt' else 'javac')
    subprocess.run([str(javac),'-encoding','UTF-8','-cp',str(classes),'-d',str(classes),*[str(MODULE/n) for n in sources]],check=True,timeout=120)
    built=work/'target/matching-engine-0.1-SNAPSHOT.jar'
    with zipfile.ZipFile(built,'a',compression=zipfile.ZIP_DEFLATED) as z:
        names=set(z.namelist())
        for p in classes.rglob('*.class'):
            name=p.relative_to(classes).as_posix()
            if name not in names:z.write(p,name)
    verify(built);dest.parent.mkdir(exist_ok=True);shutil.copy2(built,dest);verify(dest)
    print('Accepted108 classes and every JAR entry reproduced; ZIP timestamps may differ')
if __name__=='__main__':main()
