"""Check the self-contained policy and section parity without network or third-party packages."""
from pathlib import Path
from html.parser import HTMLParser
import base64
import hashlib
import re

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = ('ja', 'en', 'fr', 'de', 'hi', 'zh-Hans')
SECTIONS = ('scope', 'health', 'openai', 'timing', 'local', 'backup', 'ads', 'deletion', 'site', 'resources')

class Policy(HTMLParser):
    def __init__(self):
        super().__init__()
        self.articles = {}
        self.active = None
        self.ids = set()
        self.script = ''
        self.in_script = False
        self.csp = ''
        self.nav_links = []
        self.in_nav = False

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if 'id' in a:
            assert a['id'] not in self.ids, ('duplicate ID', a['id'])
            self.ids.add(a['id'])
        assert not any(k.startswith('on') for k in a), 'inline event attribute'
        assert tag not in ('iframe', 'img', 'form', 'link', 'object', 'embed'), ('external or interactive dependency', tag)
        if tag == 'script':
            assert not a, 'script must be inline'
            self.in_script = True
        if tag == 'meta' and a.get('http-equiv') == 'Content-Security-Policy':
            self.csp = a['content']
        if tag == 'nav':
            self.in_nav = True
        if tag == 'article':
            assert 'hidden' not in a, 'all translations must remain readable without JavaScript'
            self.active = a['lang']
            assert self.active not in self.articles
            assert a['id'] == self.active
            self.articles[self.active] = {'sections': [], 'text': '', 'paragraphs': 0, 'links': []}
        if self.active:
            if tag == 'section':
                self.articles[self.active]['sections'].append(a['data-section'])
            if tag == 'p':
                self.articles[self.active]['paragraphs'] += 1
            if tag == 'a':
                self.articles[self.active]['links'].append(a['href'])
            if tag == 'time':
                assert a['datetime'] == '2026-11-18'
        if tag == 'a' and self.in_nav:
            self.nav_links.append(a['href'])

    def handle_endtag(self, tag):
        if tag == 'script': self.in_script = False
        if tag == 'article': self.active = None
        if tag == 'nav': self.in_nav = False

    def handle_data(self, data):
        if self.in_script: self.script += data
        if self.active: self.articles[self.active]['text'] += data

page = Policy()
page.feed((ROOT / 'docs/privacy.html').read_text())
assert tuple(page.articles) == LANGUAGES
assert page.nav_links == ['#' + tag for tag in LANGUAGES]
for tag, article in page.articles.items():
    assert tuple(article['sections']) == SECTIONS, (tag, 'sections')
    assert article['paragraphs'] == 26, (tag, 'paragraph count', article['paragraphs'])
    for token in ('com.yosikou.hagemashi', 'READ_WEIGHT', 'READ_BODY_FAT', 'READ_STEPS',
                  'READ_HEALTH_DATA_IN_BACKGROUND', 'auth.openai.com', 'api.openai.com',
                  'store=false', 'AES-GCM', 'Android Keystore', 'PBKDF2-HMAC-SHA256',
                  '25.5.0', '4.0.0', 'yosikou.createapp@gmail.com'):
        # The shared header also presents the exact public app name.
        assert token in article['text'], (tag, token)
    assert article['links'] == page.articles['en']['links'], (tag, 'service/contact links')
assert "default-src 'none'" in page.csp
script_hash = base64.b64encode(hashlib.sha256(page.script.encode()).digest()).decode()
assert "script-src 'sha256-" + script_hash + "'" in page.csp, 'script CSP hash mismatch'
assert not re.search(r'\b(fetch|XMLHttpRequest|localStorage|sessionStorage|cookie)\b', page.script)
assert (ROOT / 'docs/.nojekyll').exists()
print('Privacy policy: 6 languages, 10 matching sections, local-only language switch and CSP OK')
