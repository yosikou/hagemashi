"""Check production translations without network access or an Android SDK."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'app/src/main/res'

def strings(folder):
    return {s.attrib['name']: s for s in ET.parse(root / folder / 'strings.xml').getroot()}

def text(s):
    return ''.join(s.itertext()).replace("\\'", "'").replace('\\"', '"')

base = strings('values')
required = {k for k, s in base.items() if s.attrib.get('translatable') != 'false'}
pattern = re.compile(r'%(?:\d+\$)?[sdf]')
for folder in ('values-en', 'values-fr', 'values-de', 'values-hi', 'values-b+zh+Hans'):
    translated = strings(folder)
    assert set(translated) == required, (folder, 'missing', required - translated.keys(), 'extra', translated.keys() - required)
    for key in required:
        assert text(translated[key]).strip(), (folder, key, 'empty')
        assert set(pattern.findall(text(base[key]))) == set(pattern.findall(text(translated[key]))), (folder, key, 'format arguments')
    fallback = text(translated['notification_fallback'])
    assert 40 <= len(fallback) <= 60, (folder, 'fallback length', len(fallback))
    assert not any(c.isnumeric() for c in fallback), (folder, 'numeric fallback')
    for key in ('persona_prompt', 'persona_numbers_prompt', 'persona_companion_prompt', 'manual_generation_rules', 'morning_rules', 'manual_reflection_rules', 'output_language_rule'):
        assert text(translated[key]) != text(base[key]), (folder, key, 'untranslated rule')
    print(folder, len(required), 'production strings OK')
