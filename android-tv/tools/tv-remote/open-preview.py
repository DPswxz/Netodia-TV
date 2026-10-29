#!/usr/bin/env python3
from pathlib import Path
import subprocess
import webbrowser
url = globals().get('PREVIEW_URL', 'http://127.0.0.1:8766')
browser = Path.home() / 'Library/Caches/ms-playwright/chromium-1234/chrome-mac-arm64/Google Chrome for Testing.app'
if browser.exists():
    subprocess.Popen(['open','-na',str(browser),'--args','--app='+url,'--user-data-dir='+str(Path.home()/'.cache/netodia-tv-remote-browser')])
else:
    webbrowser.open(url)
