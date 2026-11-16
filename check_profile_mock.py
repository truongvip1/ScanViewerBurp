from pathlib import Path
import re
import subprocess
from playwright.sync_api import sync_playwright

fragment = Path(r'C:/Users/84845/.codex/visualizations/2026/09/17/01a0afaf-c45d-7042-b665-c869b86fd83a/matrix-profiles-v2.html').read_text(encoding='utf-8')
script = re.search(r'<script>([\s\S]*?)</script>', fragment).group(1)
subprocess.run(['node', '--check'], input=script, text=True, check=True, capture_output=True)
ids = re.findall(r'\bid="([^"]+)"', fragment)
assert len(ids) == len(set(ids)), 'Duplicate element IDs'
for ref in re.findall(r"el\('([^']+)'\)", script):
    assert ref in ids, f'Missing element: {ref}'

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel='msedge')
    page = browser.new_page(viewport={'width': 900, 'height': 850})
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.set_content('<!doctype html><html><head><style>html{color-scheme:light}body{margin:0}</style></head><body>' + fragment + '</body></html>')
    page.locator('[data-person="bob"] button').click()
    assert page.locator('#mp-user').input_value() == 'bob@example.test'
    page.locator('[data-view="preview"]').click()
    assert 'bob@example.test' in page.locator('#mp-login-preview').inner_text()
    page.locator('[data-person="admin"] input').check()
    assert '3 profiles' in page.locator('#mp-count').inner_text()
    page.locator('[data-view="mapping"]').click()
    page.locator('#mp-pick').click()
    assert page.locator('#mp-picker').is_visible()
    page.locator('[data-view="accounts"]').click()
    page.locator('#mp-test').click()
    assert 'Bob' in page.locator('#mp-test-name').inner_text()
    for width in (900, 736, 320):
        page.set_viewport_size({'width': width, 'height': 900})
        for view in ('accounts', 'mapping', 'preview', 'more'):
            page.locator(f'[data-view="{view}"]').click()
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), f'Overflow {width}/{view}'
    page.set_viewport_size({'width': 900, 'height': 850})
    page.locator('[data-view="accounts"]').click()
    page.locator('[data-person="alice"] button').click()
    page.locator('[data-person="admin"] input').uncheck()
    page.screenshot(path=r'D:/ExtBurp/ScanViewer/target/matrix-profiles-v2-light.png', full_page=True)
    page.evaluate("document.documentElement.style.colorScheme='dark'")
    page.screenshot(path=r'D:/ExtBurp/ScanViewer/target/matrix-profiles-v2-dark.png', full_page=True)
    assert not errors, errors
    browser.close()
print('Mockup syntax, references, primary interactions and 900/736/320 px layout checks passed.')
