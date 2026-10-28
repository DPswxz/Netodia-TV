#!/usr/bin/env python3
"""Inspect TV focus after optional remote key presses; never uses mouse coordinates."""
import argparse
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

KEYS={'up':19,'down':20,'left':21,'right':22,'ok':23,'back':4,'menu':82,'play':85,'home':3,'next':87,'previous':88}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb',default='adb');p.add_argument('--port',default='5037')
    p.add_argument('--device',required=True);p.add_argument('--all',action='store_true')
    p.add_argument('--capture',type=Path);p.add_argument('--report',type=Path)
    p.add_argument('keys',nargs='*')
    args=p.parse_args();
    if any(key not in KEYS for key in args.keys):p.error('Unknown remote key')
    adb=[args.adb,'-P',args.port]
    subprocess.run(adb+['connect',args.device],check=True,stdout=subprocess.DEVNULL)
    device=adb+['-s',args.device]
    def shell(*command):return subprocess.check_output(device+['shell',*map(str,command)],text=True,timeout=40)
    for key in args.keys:shell('input','keyevent',KEYS[key])
    result=shell('uiautomator','dump','--compressed','/data/local/tmp/netodia-inspect.xml')
    if 'dumped' not in result:raise RuntimeError('Fresh hierarchy unavailable: '+result)
    tree=ET.fromstring(shell('cat','/data/local/tmp/netodia-inspect.xml'))
    nodes=list(tree.iter('node'))
    report={'keys':args.keys,'focused':[{'text':n.get('text'),'description':n.get('content-desc'),'class':n.get('class'),'bounds':n.get('bounds')} for n in nodes if n.get('focused')=='true']}
    if args.all:report['texts']=[n.get('text') for n in nodes if n.get('text')]
    if args.capture:
        args.capture.parent.mkdir(parents=True,exist_ok=True)
        shell('screencap','-p','/data/local/tmp/netodia-capture.png')
        subprocess.run(device+['pull','/data/local/tmp/netodia-capture.png',str(args.capture)],stdout=subprocess.DEVNULL,check=True)
    print(json.dumps(report,ensure_ascii=False,indent=2))
    if args.report:
        args.report.parent.mkdir(parents=True,exist_ok=True)
        with args.report.open('a') as out:out.write(json.dumps(report,ensure_ascii=False)+'\n')

if __name__=='__main__':main()
