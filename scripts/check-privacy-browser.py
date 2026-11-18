"""CI-only Chromium checks; no browser dependency in the app or policy page."""
from pathlib import Path
from playwright.sync_api import sync_playwright
root=Path(__file__).resolve().parents[1]
url=(root/'docs/privacy.html').as_uri()
output=root/'build/privacy-preview'
output.mkdir(parents=True,exist_ok=True)
with sync_playwright() as p:
 b=p.chromium.launch(headless=True)
 for width in (320,360,412):
  page=b.new_page(viewport={'width':width,'height':800},device_scale_factor=1)
  errors=[];requests=[]
  page.on('pageerror',lambda e:errors.append(str(e)))
  page.on('request',lambda r:requests.append(r.url))
  page.goto(url+'#ja')
  for lang in ('ja','en','fr','de','hi','zh-Hans'):
   page.locator('nav a[href="#'+lang+'"]').click()
   assert page.locator('article:visible').count()==1
   assert page.locator('article:visible').get_attribute('lang')==lang
   assert page.locator('article:visible section').count()==10
   assert page.evaluate('document.documentElement.scrollWidth <= innerWidth'), (width,lang,'overflow')
   assert page.locator('nav a[aria-current]').get_attribute('href')=='#'+lang
   if width==360 and lang in ('ja','de','hi'):
    page.screenshot(path=str(output/('privacy-'+lang+'.png')))
  assert not errors,errors
  assert all(r.startswith('file:') for r in requests), requests
  page.close()
 context=b.new_context(java_script_enabled=False,viewport={'width':360,'height':800})
 page=context.new_page();page.goto(url)
 assert page.locator('article:visible').count()==6
 assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
 context.close()
 context=b.new_context(locale='es-ES');page=context.new_page();page.goto(url)
 assert page.locator('article:visible').get_attribute('lang')=='en'
 context.close()
 context=b.new_context(locale='fr-FR');page=context.new_page();page.goto(url)
 assert page.locator('article:visible').get_attribute('lang')=='fr'
 context.close();b.close()
print('Chromium: 6 languages x 3 mobile widths, no horizontal overflow/network dependencies; no-JS and locale fallback OK')
