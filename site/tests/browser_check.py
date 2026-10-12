#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Real Chromium acceptance for the static site, never a relay or physical device."""
import argparse
from contextlib import ExitStack
import hashlib
import json
import re
from pathlib import Path
import sys
import threading
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from artifact_archive import archive_directory
from install_browser import SITE, baseline
from preview import preview_directory, server
from verify_artifact import verify
from playwright.sync_api import Error, expect, sync_playwright


def local_context(browser, origin, blocked, **options):
    """Abort non-preview traffic before DNS/connect, including accidental API access."""
    context = browser.new_context(service_workers="block", **options)
    allowed = urlsplit(origin)

    def guard(route):
        target = urlsplit(route.request.url)
        if (target.scheme, target.netloc) != (allowed.scheme, allowed.netloc) or target.path.startswith("/api/"):
            blocked.append(route.request.url)
            route.abort("blockedbyclient")
        else:
            route.continue_()

    context.route("**/*", guard)
    return context


def require(condition, message):
    if not condition:
        raise AssertionError(message)


GEOMETRY = """() => ({width: innerWidth, content: document.documentElement.scrollWidth,
  targets: [...document.querySelectorAll('a,button')].filter(e => !e.classList.contains('skip')).map(e => {
    const r=e.getBoundingClientRect(); return {text:e.textContent.trim(),width:r.width,height:r.height};}),
  arcs: [...document.querySelectorAll('.arc')].map(e => ({name:getComputedStyle(e).animationName,
    state:getComputedStyle(e).animationPlayState}))})"""

CONTRAST = """() => {
 const rgb = value => {const v=value.match(/[\\d.]+/g).map(Number); return v;};
 const luminance = c => c.slice(0,3).map(v => {v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4;})
   .reduce((sum,v,i)=>sum+v*[.2126,.7152,.0722][i],0);
 const checks=[];
 for(const e of document.querySelectorAll('body *')) {
   if(![...e.childNodes].some(n=>n.nodeType===3&&n.textContent.trim()) || !e.getClientRects().length) continue;
   const s=getComputedStyle(e); if(s.display==='none'||s.visibility==='hidden') continue;
   let p=e, bg=null;
   while(p){const c=rgb(getComputedStyle(p).backgroundColor);if(c.length===3||c[3]===1){bg=c;break;}p=p.parentElement;}
   if(!bg) bg=rgb(getComputedStyle(document.documentElement).backgroundColor);
   const fg=rgb(s.color); const l1=luminance(fg),l2=luminance(bg);
   const ratio=(Math.max(l1,l2)+.05)/(Math.min(l1,l2)+.05);
   const large=parseFloat(s.fontSize)>=24||(parseFloat(s.fontSize)>=18.66&&parseInt(s.fontWeight)>=700);
   checks.push({tag:e.tagName,text:e.textContent.trim().slice(0,45),ratio,required:large?3:4.5});
 }
 return checks;
}"""


def run(evidence=None, browser_executable=None, artifact=None, expected_manifest_sha256=None):
    if artifact is None and expected_manifest_sha256 is not None:
        raise ValueError('expected manifest identity requires --artifact or --archive')
    result = {"testedLayer":"real Chromium static-site behavior; not physical/desktop/provider acceptance", "cases":[]}
    bases = (verify(artifact, expected_manifest_sha256)['basePath'],) if artifact else ('/', '/twitch-screen/')
    result['inputMode'] = 'verified-artifact' if artifact else 'source-build'
    blocked = []
    if evidence:
        evidence.mkdir(parents=True, exist_ok=True)
    with ExitStack() as snapshots, sync_playwright() as playwright:
        reviewed = baseline()
        executable = browser_executable or SITE / '.browser' / reviewed['executable']
        if not executable.is_file():
            raise ValueError('reviewed browser missing; run site/tools/install_browser.py first')
        browser = playwright.chromium.launch(executable_path=str(executable.resolve()))
        result["browser"] = browser.version
        try:
            require(browser.version == reviewed['version'], 'executed browser differs from reviewed baseline')
            result['browserChannel'] = reviewed['channel']
            result['browserReviewDate'] = reviewed['reviewedAt']
            for base in bases:
                directory, selected_base = snapshots.enter_context(
                    preview_directory(artifact, base, expected_manifest_sha256))
                require(selected_base == base, 'preview base changed during admission')
                manifest_bytes = (directory / 'build-manifest.json').read_bytes()
                manifest = json.loads(manifest_bytes)
                if artifact:
                    result['artifactManifestSha256'] = hashlib.sha256(manifest_bytes).hexdigest()
                with server(directory, base) as http:
                    worker = threading.Thread(target=http.serve_forever, daemon=True)
                    worker.start()
                    origin = f"http://127.0.0.1:{http.server_port}"
                    try:
                        context = local_context(browser, origin, blocked)
                        page = context.new_page()
                        errors = []
                        console_errors = []
                        requests = []
                        page.on("pageerror", lambda error: errors.append(str(error)))
                        page.on("console", lambda message: console_errors.append(message.text) if message.type == "error" else None)
                        page.on("request", lambda request: requests.append(request.url))
                        for width in (320, 768, 1024, 1440):
                            page.set_viewport_size({"width":width,"height":900})
                            page.goto(origin + base, wait_until="networkidle")
                            geometry = page.evaluate(GEOMETRY)
                            require(geometry["content"] <= width, f"overflow at {base} width {width}")
                            for target in geometry["targets"]:
                                require(target["width"] >= 44 and target["height"] >= 44,
                                        f"small target at {width}: {target}")
                            require(page.locator('.feature-list li[data-state="planned"]').count() == 5,
                                    "planned features lost their scope labels")
                            contrasts = page.evaluate(CONTRAST)
                            require(contrasts and all(item["ratio"] >= item["required"] for item in contrasts),
                                    f"text contrast failure: {contrasts}")
                            if evidence and base != "/" and width in (320,1440):
                                page.screenshot(path=str(evidence / f"illustration-{width}.png"), full_page=True)
                            result["cases"].append({"base":base,"width":width,"overflow":False,
                                                    "targets":len(geometry["targets"]),
                                                    "minimumTextContrast":round(min(item["ratio"] for item in contrasts),3)})
                        page.set_viewport_size({"width":320,"height":900})
                        page.goto(origin + base, wait_until="networkidle")
                        page.keyboard.press("Tab")
                        require(page.locator('.skip').evaluate("e=>e===document.activeElement"), "skip link not first keyboard target")
                        focus = page.locator('.skip').evaluate("e=>({top:e.getBoundingClientRect().top,outline:getComputedStyle(e).outlineWidth})")
                        require(focus["top"] >= 0 and focus["outline"] == "3px", "skip focus not visible")
                        page.keyboard.press("Enter")
                        require(page.locator('#main').evaluate("e=>e===document.activeElement"), "skip does not focus main")
                        pause = page.locator('#pause-motion')
                        pause.focus()
                        page.keyboard.press("Enter")
                        require(pause.get_attribute('aria-pressed') == "true", "keyboard pause failed")
                        require(all(arc["state"] == "paused" for arc in page.evaluate(GEOMETRY)["arcs"]), "pause not applied")
                        page.locator('#change-appearance').click()
                        require(page.locator('#appearance-label').inner_text().startswith("Disconnected"), "appearance label missing")
                        require(page.locator('.loss').is_visible(), "connection-loss symbol absent")
                        page.set_viewport_size({"width":768,"height":900})
                        require(pause.get_attribute('aria-pressed') == "true", "resize lost pause preference")
                        page.emulate_media(reduced_motion="reduce")
                        expect(pause).to_be_disabled()
                        require(pause.is_disabled(), "reduced-motion pause control not disabled")
                        require(all(arc["name"] == "none" for arc in page.evaluate(GEOMETRY)["arcs"]), "reduced-motion animation active")
                        page.emulate_media(reduced_motion="no-preference")
                        require(pause.get_attribute('aria-pressed') == "true", "media change lost user pause")
                        pause.click()
                        require(pause.get_attribute('aria-pressed') == "false", "resume failed")
                        for scale, width in ((2,320),(4,320)):
                            page.set_viewport_size({"width":width,"height":900})
                            page.evaluate("scale=>document.documentElement.style.fontSize=`${scale*100}%`", scale)
                            require(page.evaluate("document.documentElement.scrollWidth <= innerWidth"), f"text enlargement overflow {scale}")
                        page.evaluate("document.documentElement.style.fontSize='' ")
                        require(not errors and not console_errors, f"browser errors: {errors} {console_errors}")
                        require(all(urlsplit(url).netloc == urlsplit(origin).netloc for url in requests), "remote/private request")
                        require(all('/api/' not in url for url in requests), "public site accessed API")
                        result["cases"].append({"base":base,"keyboardPause":True,"reducedMotion":True,
                                                "pausePersistence":True,"textEnlargement200And400":True,
                                                "requests":sorted(set(urlsplit(url).path for url in requests)),"consoleErrors":errors})
                        # Explicit manual tour steps illustrate overlays, not device timers.
                        page.locator('#show-card').focus()
                        page.keyboard.press('Enter')
                        card = page.locator('#demo-card')
                        expect(card).to_be_visible()
                        # Inspect Chromium's accessible tree, not only hidden/style attributes.
                        accessible_card = page.get_by_role('group', name='Synthetic generic card illustration')
                        expect(accessible_card).to_be_visible()
                        accessible = page.locator('.demo').aria_snapshot()
                        require('Build finished' in accessible and 'Synthetic example.' in accessible,
                                'active text illustration missing from accessible tree')
                        require('Local orbital screensaver illustration' not in accessible,
                                'covered base still exposed in accessible tree')
                        expect(page.locator('#card-title')).to_have_text('Build finished')
                        expect(page.locator('#card-body')).to_have_text('Synthetic example. Your build is ready to inspect.')
                        expect(page.locator('#finish-card')).to_be_enabled()
                        expect(page.locator('#show-card')).to_be_disabled()
                        expect(page.locator('#motion-note')).to_have_text(
                            'Decorative motion pauses while a card illustration covers the base.')
                        page.locator('#pause-motion').click()
                        expect(page.locator('#motion-note')).to_have_text(
                            'Motion paused. The appearance control still works.')
                        page.locator('#pause-motion').click()
                        expect(page.locator('#motion-note')).to_have_text(
                            'Decorative motion pauses while a card illustration covers the base.')
                        expect(card).to_be_visible()
                        require(all(arc['state'] == 'paused' for arc in page.evaluate(GEOMETRY)['arcs']),
                                'covered illustration kept decorative animation running')
                        page.emulate_media(reduced_motion='reduce')
                        expect(card).to_be_visible()
                        expect(page.locator('#motion-note')).to_have_text(
                            'Static illustration: reduced motion is enabled.')
                        expect(page.locator('#pause-motion')).to_be_disabled()
                        page.emulate_media(reduced_motion='no-preference')
                        # WCAG text-spacing overrides plus enlarged text must not lose
                        # the beginning of a scrollable synthetic card above scroll zero.
                        spacing_rules = page.evaluate('''() => {
                          const sheet=document.styleSheets[0], start=sheet.cssRules.length;
                          sheet.insertRule('body * { line-height:1.5 !important; letter-spacing:.12em !important; word-spacing:.16em !important; }', start);
                          sheet.insertRule('p { margin-bottom:2em !important; }', start+1);
                          return start;
                        }''')
                        for scale in (1, 2, 4):
                            page.set_viewport_size({'width':320,'height':900})
                            page.evaluate('scale=>document.documentElement.style.fontSize=`${scale*100}%`', scale)
                            geometry = card.evaluate('''e => {
                              e.scrollTop = 0;
                              const box=e.getBoundingClientRect();
                              return {width:innerWidth, content:document.documentElement.scrollWidth,
                                scrollHeight:e.scrollHeight, clientHeight:e.clientHeight,
                                children:[...e.children].filter(c=>!c.hidden).map(c=>{
                                  const r=c.getBoundingClientRect();
                                  return {text:c.textContent, top:r.top-box.top-e.clientTop,
                                    bottom:r.bottom-box.top-e.clientTop};
                                })};
                            }''')
                            require(geometry['content'] <= geometry['width'],
                                    f'text-spaced active card page overflow at {scale}x')
                            require(geometry['children'] and all(c['top'] >= -1 and
                                    c['bottom'] <= geometry['scrollHeight'] + 1
                                    for c in geometry['children']),
                                    f'text-spaced active card text unreachable at {scale}x: {geometry}')
                        expect(card).to_have_attribute('tabindex', '0')
                        card.focus()
                        require(card.evaluate('e=>e===document.activeElement'),
                                'overflowing card cannot receive keyboard focus')
                        maximum_scroll = card.evaluate('e=>e.scrollHeight-e.clientHeight')
                        require(maximum_scroll > 0, 'enlarged card did not exercise overflow')
                        for key, position in (('End', maximum_scroll), ('Home', 0), ('End', maximum_scroll)):
                            # Native scrolling is animated: observing an intermediate
                            # scrollTop must not race the next keyboard action.
                            card.evaluate('''e => {
                              e.removeAttribute('data-test-scroll-settled');
                              e.addEventListener('scrollend', () =>
                                e.setAttribute('data-test-scroll-settled', 'true'), {once:true});
                            }''')
                            page.keyboard.press(key)
                            expect(card).to_have_attribute('data-test-scroll-settled', 'true')
                            expect(card).to_have_js_property('scrollTop', position)
                        card.evaluate("e=>e.removeAttribute('data-test-scroll-settled')")
                        page.locator('#finish-card').click()
                        page.locator('#show-card').click()
                        expect(card).to_have_js_property('scrollTop', 0)
                        page.evaluate('start=>{const sheet=document.styleSheets[0]; sheet.deleteRule(start+1); sheet.deleteRule(start);}', spacing_rules)
                        page.evaluate("document.documentElement.style.fontSize=''")
                        result['cases'].append({'base':base,'activeCardTextSpacing':True,
                                                'activeCardTextEnlargement100200400':True,
                                                'cardBeginningReachableAtScrollZero':True,
                                                'cardKeyboardHomeEnd':True,'newCardResetsScroll':True,
                                                'browserZoomVerified':False})
                        for width in (320, 768, 1024, 1440):
                            page.set_viewport_size({'width':width,'height':900})
                            require(page.evaluate('document.documentElement.scrollWidth <= innerWidth'),
                                    f'active illustration overflow at {width}')
                        active_contrasts = page.evaluate(CONTRAST)
                        require(all(item['ratio'] >= item['required'] for item in active_contrasts),
                                'active illustration text contrast failed')
                        page.locator('#change-appearance').click()
                        expect(card).to_be_visible()
                        expect(page.locator('#appearance-label')).to_contain_text('Connected')
                        page.locator('#change-appearance').click()
                        expect(page.locator('#appearance-label')).to_contain_text('Disconnected')
                        expect(card).to_be_visible()
                        page.locator('#finish-card').click()
                        expect(card).to_be_hidden()
                        expect(accessible_card).to_have_count(0)
                        accessible = page.locator('.demo').aria_snapshot()
                        require('Local orbital screensaver illustration' in accessible,
                                'completion did not restore the accessible base')
                        require('Build finished' not in accessible and 'Synthetic example.' not in accessible,
                                'completed card remains exposed in accessible tree')
                        require(page.locator('#show-card').evaluate('e=>e===document.activeElement'),
                                'text card completion lost initiating keyboard focus')
                        expect(page.locator('.loss')).to_be_visible()
                        page.locator('#show-headline').click()
                        expect(card).to_be_visible()
                        expect(accessible_card).to_be_visible()
                        accessible = page.locator('.demo').aria_snapshot()
                        require('Build finished' in accessible and 'Synthetic example.' not in accessible,
                                'bodyless accessible illustration contains hidden body or loses title')
                        require('Local orbital screensaver illustration' not in accessible,
                                'bodyless overlay still exposes covered base')
                        expect(page.locator('#card-title')).to_have_text('Build finished')
                        expect(page.locator('#card-body')).to_be_hidden()
                        expect(page.locator('#motion-note')).to_have_text(
                            'Decorative motion pauses while a card illustration covers the base.')
                        page.locator('#finish-card').focus()
                        page.keyboard.press('Enter')
                        expect(card).to_be_hidden()
                        require(page.locator('#show-headline').evaluate('e=>e===document.activeElement'),
                                'tour completion lost initiating keyboard focus')
                        require(page.locator('#tour-note').inner_text().startswith('Illustration finished'),
                                'manual tour outcome not announced')
                        result['cases'].append({'base':base,'manualSyntheticOverlay':True,
                                                'bodylessCanonicalExample':True,'appearanceDoesNotDismissCard':True,
                                                'keyboardCompletionFocus':True,'coveredMotionReason':True,
                                                'explicitPauseReasonDuringCard':True,'reducedMotionReasonDuringCard':True,
                                                'accessibleCardGroup':True,'coveredBaseHiddenFromAccessibility':True,
                                                'accessibleBodylessAndCompletion':True,'screenReaderAudit':False})
                        # Actual scrolling must suspend decorative work without changing user intent.
                        figure = page.locator('.demo')
                        figure.scroll_into_view_if_needed()
                        expect(figure).not_to_have_class(re.compile(r'\bsuspended\b'))
                        expect(page.locator('#motion-note')).to_have_text(
                            'Slow decorative motion; pause it whenever you prefer.')
                        expect(pause).to_have_attribute('aria-pressed', 'false')
                        page.locator('footer').scroll_into_view_if_needed()
                        expect(figure).to_have_class(re.compile(r'\bsuspended\b'))
                        require(all(arc['state'] == 'paused' for arc in page.evaluate(GEOMETRY)['arcs']),
                                'offscreen illustration kept decorative animation running')
                        expect(pause).to_have_attribute('aria-pressed', 'false')
                        frozen = page.evaluate("""async () => {
                          const animations = [...document.querySelectorAll('.arc')]
                            .map(e => e.getAnimations()[0]);
                          // CSS pause becomes effective at the next animation timeline update.
                          await Promise.all(animations.map(animation => animation.ready));
                          const phases = () => animations.map(animation => animation.currentTime);
                          const before = phases();
                          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                          return {before, after: phases()};
                        }""")
                        require(frozen['before'] == frozen['after'], 'offscreen composition phase advanced')
                        figure.scroll_into_view_if_needed()
                        expect(figure).not_to_have_class(re.compile(r'\bsuspended\b'))
                        require(all(arc['state'] == 'running' for arc in page.evaluate(GEOMETRY)['arcs']),
                                'visible illustration did not resume')
                        pause.click()
                        page.locator('footer').scroll_into_view_if_needed()
                        expect(figure).to_have_class(re.compile(r'\bsuspended\b'))
                        figure.scroll_into_view_if_needed()
                        expect(figure).not_to_have_class(re.compile(r'\bsuspended\b'))
                        expect(pause).to_have_attribute('aria-pressed', 'true')
                        require(all(arc['state'] == 'paused' for arc in page.evaluate(GEOMETRY)['arcs']),
                                'scroll re-entry erased explicit user pause')
                        pause.click()
                        page.locator('#show-card').click()
                        page.locator('footer').scroll_into_view_if_needed()
                        expect(figure).to_have_class(re.compile(r'\bsuspended\b'))
                        figure.scroll_into_view_if_needed()
                        expect(figure).not_to_have_class(re.compile(r'\bsuspended\b'))
                        expect(card).to_be_visible()
                        require(all(arc['state'] == 'paused' for arc in page.evaluate(GEOMETRY)['arcs']),
                                'scroll re-entry resumed covered decorative arcs')
                        page.locator('#finish-card').click()
                        # Synthetic persisted lifecycle events exercise cleanup/restoration in Chromium.
                        # They are not evidence that the browser admitted this page to its back/forward cache.
                        page.locator('#show-headline').click()
                        page.evaluate("window.dispatchEvent(new PageTransitionEvent('pagehide', {persisted:true}))")
                        expect(figure).to_have_class(re.compile(r'\bsuspended\b'))
                        expect(card).to_be_visible()
                        require(all(arc['state'] == 'paused' for arc in page.evaluate(GEOMETRY)['arcs']),
                                'pagehide did not suspend decoration')
                        page.evaluate("window.dispatchEvent(new PageTransitionEvent('pageshow', {persisted:true}))")
                        expect(figure).not_to_have_class(re.compile(r'\bsuspended\b'))
                        expect(card).to_be_visible()
                        expect(page.locator('#card-body')).to_be_hidden()
                        expect(page.locator('#appearance-label')).to_contain_text('Disconnected')
                        page.locator('#finish-card').click()
                        page.locator('footer').scroll_into_view_if_needed()
                        expect(figure).to_have_class(re.compile(r'\bsuspended\b'))
                        figure.scroll_into_view_if_needed()
                        expect(figure).not_to_have_class(re.compile(r'\bsuspended\b'))
                        require(not errors and not console_errors, f'visibility lifecycle errors: {errors} {console_errors}')
                        result['cases'].append({'base':base,'syntheticPersistedLifecycleEvents':True,
                                                'observerReattachedAfterPageShow':True,
                                                'activeBodylessCardPreserved':True,'backForwardCacheAdmissionVerified':False})
                        result['cases'].append({'base':base,'offscreenMotionSuspension':True,
                                                'phasePreservedWhileSuspended':True,
                                                'userPauseSurvivesReentry':True,'activeCardSurvivesReentry':True})
                        # Decode authored media in Chromium, not only XML/build validation.
                        for asset in ('orbit.svg', 'social.svg'):
                            decoded = page.evaluate("""async url => {
                              const image = new Image(); image.src = url; await image.decode();
                              return {width: image.naturalWidth, height: image.naturalHeight};
                            }""", origin + base + 'assets/' + asset)
                            require(decoded['width'] > 0 and decoded['height'] > 0,
                                    f"illustration did not decode: {asset}")
                        context.close()
                        # Emulated touch/orientation behavior is not an actual mobile-device sign-off.
                        context = local_context(browser, origin, blocked, has_touch=True, is_mobile=True)
                        page = context.new_page()
                        for width, height in ((320,568),(568,320),(768,1024),(1024,768)):
                            page.set_viewport_size({'width':width,'height':height})
                            page.goto(origin + base, wait_until='networkidle')
                            require(page.evaluate('document.documentElement.scrollWidth <= innerWidth'),
                                    f"touch/orientation overflow at {width}x{height}")
                            page.locator('#pause-motion').tap()
                            expect(page.locator('#pause-motion')).to_have_attribute('aria-pressed', 'true')
                            page.locator('#change-appearance').tap()
                            expect(page.locator('#change-appearance')).to_have_attribute('aria-pressed', 'true')
                            require(page.locator('.loss').is_visible(), 'touch appearance mark absent')
                            page.locator('#show-card').tap()
                            expect(page.locator('#demo-card')).to_be_visible()
                            page.locator('#change-appearance').tap()
                            expect(page.locator('#demo-card')).to_be_visible()
                            page.locator('#finish-card').tap()
                            expect(page.locator('#demo-card')).to_be_hidden()
                            expect(page.locator('#appearance-label')).to_contain_text('Connected')
                            expect(page.locator('#show-card')).to_be_enabled()
                        context.close()
                        result['cases'].append({'base':base, 'authoredSvgDecode':True,
                                                'emulatedTouchPortraitLandscape':True,
                                                'touchViewports':[[320,568],[568,320],[768,1024],[1024,768]]})
                        # An unavailable visibility API must fail static, not add a polling fallback.
                        context = local_context(browser, origin, blocked, viewport={'width':320,'height':900})
                        context.add_init_script("delete window.IntersectionObserver")
                        page = context.new_page()
                        fallback_errors = []
                        page.on('pageerror', lambda error: fallback_errors.append(str(error)))
                        page.goto(origin + base, wait_until='networkidle')
                        expect(page.locator('#motion-note')).to_have_text(
                            'Static illustration: visibility observation is unavailable.')
                        expect(page.locator('#pause-motion')).to_be_disabled()
                        expect(page.locator('#pause-motion')).to_have_text('Motion unavailable')
                        expect(page.locator('#pause-motion')).to_have_attribute('aria-pressed', 'true')
                        require(all(arc['state'] == 'paused' for arc in page.evaluate(GEOMETRY)['arcs']),
                                'missing observer started decorative motion')
                        page.locator('#show-card').click()
                        expect(page.locator('#demo-card')).to_be_visible()
                        page.locator('#change-appearance').click()
                        expect(page.locator('#demo-card')).to_be_visible()
                        page.locator('#finish-card').click()
                        expect(page.locator('#demo-card')).to_be_hidden()
                        expect(page.locator('#appearance-label')).to_contain_text('Disconnected')
                        require(all(arc['state'] == 'paused' for arc in page.evaluate(GEOMETRY)['arcs']),
                                'fallback card completion started motion')
                        require(not fallback_errors, f'observer fallback errors: {fallback_errors}')
                        context.close()
                        result['cases'].append({'base':base,'missingVisibilityObserverStatic':True,
                                                'fallbackCardControlsWork':True,'pollingFallback':False})
                        # Chromium emulates the user's forced-color preference; this
                        # is not an operating-system high-contrast or screen-reader audit.
                        context = local_context(browser, origin, blocked, forced_colors='active',
                                                reduced_motion='reduce')
                        page = context.new_page()
                        forced_errors = []
                        page.on('pageerror', lambda error: forced_errors.append(str(error)))
                        forced_widths = []
                        for width in (320, 768, 1024, 1440):
                            page.set_viewport_size({'width':width,'height':900})
                            page.goto(origin + base, wait_until='networkidle')
                            require(page.evaluate("matchMedia('(forced-colors: active)').matches"),
                                    'forced-colors preference was not active')
                            page.locator('#show-card').focus()
                            page.keyboard.press('Enter')
                            forced_card = page.get_by_role('group', name='Synthetic generic card illustration')
                            expect(forced_card).to_be_visible()
                            expect(page.locator('#pause-motion')).to_be_disabled()
                            forced_card.focus()
                            focus_style = forced_card.evaluate('''e => {
                              const s=getComputedStyle(e);
                              return {focused:e===document.activeElement, visible:e.matches(':focus-visible'),
                                width:parseFloat(s.outlineWidth), style:s.outlineStyle,
                                color:s.outlineColor, background:s.backgroundColor};
                            }''')
                            require(focus_style['focused'] and focus_style['visible'] and
                                    focus_style['width'] >= 3 and focus_style['style'] != 'none' and
                                    focus_style['color'] != focus_style['background'],
                                    f'forced-colors card focus lost: {focus_style}')
                            forced_geometry = page.evaluate(GEOMETRY)
                            require(forced_geometry['content'] <= width,
                                    f'forced-colors active card overflow at {width}')
                            forced_contrasts = page.evaluate(CONTRAST)
                            require(forced_contrasts and all(item['ratio'] >= item['required']
                                    for item in forced_contrasts), 'forced-colors text contrast failed')
                            for control in ('change-appearance', 'finish-card'):
                                page.keyboard.press('Tab')
                                require(page.locator('#' + control).evaluate('e=>e===document.activeElement'),
                                        'active card traversal trapped focus or visited disabled controls')
                            page.keyboard.press('Enter')
                            expect(forced_card).to_have_count(0)
                            require(page.locator('#show-card').evaluate('e=>e===document.activeElement'),
                                    'forced-colors completion lost initiating focus')
                            page.locator('#demo-card').evaluate('e=>e.focus()')
                            require(not page.locator('#demo-card').evaluate('e=>e===document.activeElement'),
                                    'hidden completed card remained focusable')
                            forced_widths.append(width)
                        require(not forced_errors, f'forced-colors errors: {forced_errors}')
                        context.close()
                        result['cases'].append({'base':base,'forcedColorsPreference':True,
                                                'forcedColorsWidths':forced_widths,
                                                'forcedColorsTextContrast':True,'forcedColorsCardFocus':True,
                                                'keyboardTourTraversal':True,'hiddenCardNotFocusable':True,
                                                'operatingSystemHighContrastVerified':False,
                                                'screenReaderAudit':False})
                        # Loading with reduced motion starts static; not only a post-load media change.
                        context = local_context(browser, origin, blocked, reduced_motion="reduce", viewport={"width":320,"height":900})
                        page = context.new_page()
                        page.goto(origin + base, wait_until="networkidle")
                        require(page.locator('#pause-motion').is_disabled(), "initial reduced motion failed")
                        require(all(arc["name"] == "none" for arc in page.evaluate(GEOMETRY)["arcs"]), "initial reduced animation")
                        context.close()
                        context = local_context(browser, origin, blocked, java_script_enabled=False, viewport={"width":320,"height":900})
                        page = context.new_page()
                        page.goto(origin + base)
                        require(page.locator('h1').is_visible() and page.locator('#build').is_visible(), "no-JS content unavailable")
                        require(all(arc["state"] == "paused" for arc in page.evaluate(GEOMETRY)["arcs"]), "no-JS ambient motion active")
                        require(page.locator('noscript').is_visible(), "no-JS guidance missing")
                        page.keyboard.press('Tab')
                        require(page.locator('.skip').evaluate('e=>e===document.activeElement'),
                                'no-JS skip link not first keyboard target')
                        page.keyboard.press('Enter')
                        require(page.locator('#main').evaluate('e=>e===document.activeElement'),
                                'no-JS skip link does not focus main')
                        page.locator('.hero .actions a[href="#build"]').click()
                        require(page.url == origin + base + '#build',
                                'no-JS primary build action lost project base or fragment')
                        build_heading = page.locator('#build-title').bounding_box()
                        require(build_heading and 0 <= build_heading['y'] < 900,
                                'no-JS primary build action did not reveal setup heading')
                        setup_links = page.locator('#build .steps a')
                        require(setup_links.count() == 6 and all(
                            link.get_attribute('href').startswith(
                                'https://github.com/worxbend/twitch-screen/')
                            for link in setup_links.all()),
                            'no-JS setup journey lost public documentation links')
                        upgrade = page.locator('#upgrade-guidance')
                        expect(upgrade).to_contain_text('Old management credentials do not protect this checkout.')
                        expect(upgrade).to_contain_text('Do not delete grants or token volumes.')
                        expect(upgrade).to_contain_text('Complete production DESK/1 and rollback acceptance remain pending.')
                        upgrade_links = upgrade.locator('a')
                        require(upgrade_links.count() == 2, 'upgrade journey lost compatibility or backup guidance')
                        for link, fragment in zip(upgrade_links.all(), ('compatibility-today', 'backup-and-rollback')):
                            require(link.get_attribute('href') ==
                                    'https://github.com/worxbend/twitch-screen/blob/main/docs/guides/migration.md#' + fragment,
                                    'upgrade link differs from reviewed public guide')
                            link.focus()
                            require(link.evaluate('e=>e===document.activeElement'),
                                    'no-JS upgrade guidance is not keyboard reachable')
                            expect(link).to_be_visible()
                        result['cases'].append({'base':base,'upgradeSafetyReaderJourney':True,
                                                'publicUpgradeLinks':upgrade_links.count(),
                                                'networkBoundary':True,'grantsPreserved':True,
                                                'productionRollbackPending':True})
                        verification = page.locator('#verification-guidance')
                        expect(verification).to_contain_text('Native LVGL observations are software evidence, not LCD pixels.')
                        expect(verification).to_contain_text('Default ESP32 DESK/1 advertisement and complete product acceptance remain pending.')
                        expect(verification).to_contain_text('Finite software checks cover baseline/current peer compatibility, retained-eight reconnect and measured CPU/memory application.')
                        expect(verification).to_contain_text('The baseline firmware row exercises transport and queue code, not an LCD.')
                        expect(verification).to_contain_text('These checks do not accept the default ESP32, complete provider/suspend behavior or the private admin panel.')
                        expect(verification).to_contain_text('Correlate application views, numeric values and reports by relay epoch, connection and revision.')
                        expect(verification).to_contain_text('Matching connection numbers alone cannot join observations across relay restarts.')
                        expect(verification).to_contain_text('An unknown queue is not an empty queue.')
                        expect(verification).to_contain_text('Snapshot and pushed-feed evidence do not replace panel, deployment or LCD acceptance.')
                        targets = (
                            'twitch-screen-client/README.md#companion-to-native-application-compose-check',
                            'twitch-screen-firmware/docs/runtime-pairing.md#canonical-production-api-and-retained-eight-application-gate',
                            'docs/guides/end-to-end-testing.md#physical-and-live-prerequisites',
                            'twitch-screen-firmware/docs/runtime-pairing.md#actual-baselinecurrent-four-peer-compatibility')
                        verification_links = verification.locator('a')
                        require(verification_links.count() == len(targets), 'verification reader lost a public runbook')
                        for link, target in zip(verification_links.all(), targets):
                            require(link.get_attribute('href') ==
                                    'https://github.com/worxbend/twitch-screen/blob/main/' + target,
                                    'verification link differs from reviewed public runbook')
                            link.focus()
                            require(link.evaluate('e=>e===document.activeElement'),
                                    'no-JS verification guidance is not keyboard reachable')
                            expect(link).to_be_visible()
                            box = link.bounding_box()
                            require(box and box['height'] >= 44 and box['width'] >= 44,
                                    'verification reader touch target too small')
                        result['cases'].append({'base':base,'installedSoftwareVerificationReaderJourney':True,
                                                'publicVerificationLinks':verification_links.count(),
                                                'nativeNotPhysical':True,'defaultEsp32AcceptancePending':True,
                                                'epochConnectionRevisionCorrelation':True,'unknownQueueNotEmpty':True,
                                                'panelDeploymentAcceptancePending':True})
                        page.locator('nav a[href="#uses"]').click()
                        require(page.url == origin + base + '#uses',
                                'no-JS uses navigation lost project base or fragment')
                        page.locator('#integrations').scroll_into_view_if_needed()
                        expect(page.locator('#integrations')).to_contain_text(
                            'Legacy devices cannot enforce waiting expiry after transmission.')
                        expect(page.locator('#integrations')).to_contain_text(
                            'Complete DESK/1 device expiry and recovery acceptance remain pending.')
                        api_link = page.locator('#integrations a[href="https://github.com/worxbend/twitch-screen/blob/main/docs/reference/http-api.md"]')
                        expect(api_link).to_be_visible()
                        api_link.focus()
                        require(api_link.evaluate('e=>e===document.activeElement'),
                                'no-JS mounted API guidance is not keyboard reachable')
                        companion = page.locator('#companion-guidance')
                        expect(companion).to_contain_text('Notification forwarding starts disabled.')
                        expect(companion).to_contain_text('summary-only is the default; bodies need a separate opt-in.')
                        expect(companion).to_contain_text('Fresh CPU data does not prove notification capture is healthy.')
                        expect(companion).to_contain_text('Supported desktop, installed service and physical display acceptance remain pending.')
                        companion_links = companion.locator('a')
                        require(companion_links.count() == 3, 'companion reader journey lost documentation links')
                        for link, fragment in zip(companion_links.all(), (
                                'configure-and-run', 'privacy-and-capture-scope', 'measurement-and-delivery-semantics')):
                            require(link.get_attribute('href') ==
                                    'https://github.com/worxbend/twitch-screen/blob/main/twitch-screen-client/README.md#' + fragment,
                                    'companion reader link differs from reviewed public document')
                            link.focus()
                            require(link.evaluate('e=>e===document.activeElement'),
                                    'no-JS companion guidance is not keyboard reachable')
                            expect(link).to_be_visible()
                        result['cases'].append({'base':base,'companionPrivacyReaderJourney':True,
                                                'publicCompanionLinks':companion_links.count(),
                                                'forwardingDisabled':True,'captureCpuIndependent':True,
                                                'desktopServicePhysicalPending':True})
                        result['cases'].append({'base':base,'noJavaScriptReaderJourney':True,
                                                'keyboardSkip':True,'primaryBuildNavigation':True,
                                                'publicSetupLinks':setup_links.count(),
                                                'mountedApiGuidance':True,'deviceExpiryAcceptancePending':True})
                        rights = page.locator('#illustration-rights')
                        expect(rights).to_contain_text('Credit Twitch Screen contributors, link the illustration source and CC BY 4.0 license, and identify your changes.')
                        expect(rights).to_contain_text('Retain supplied notices and the disclaimer of warranties. No trademark rights or endorsement is granted.')
                        expect(rights).to_contain_text('This scope does not clear firmware artwork, photographs, imported CAD references or third-party binary obligations.')
                        rights_targets = (
                            'https://github.com/worxbend/twitch-screen/tree/main/site/assets',
                            'https://github.com/worxbend/twitch-screen/blob/main/LICENSES/CC-BY-4.0.txt',
                            base + 'assets/provenance.json')
                        rights_links = rights.locator('a')
                        require(rights_links.count() == len(rights_targets), 'illustration reader lost scoped attribution links')
                        for link, target in zip(rights_links.all(), rights_targets):
                            require(link.get_attribute('href') == target, 'illustration attribution target changed')
                            link.focus()
                            require(link.evaluate('e=>e===document.activeElement'), 'illustration attribution not keyboard reachable')
                            expect(link).to_be_visible()
                            box = link.bounding_box()
                            require(box and box['height'] >= 44 and box['width'] >= 44, 'attribution reader touch target too small')
                        # Only the local attribution record is opened; source/license destinations remain unfollowed.
                        rights_links.nth(2).click()
                        require(page.url == origin + base + 'assets/provenance.json', 'attribution record left the preview base')
                        provenance = json.loads(page.locator('body').inner_text())
                        require(provenance['codeLicense'] == 'MIT' and len(provenance['assets']) == 2,
                                'local attribution record scope changed')
                        require(all(item['creator'] == 'Twitch Screen contributors' and
                                    item['license'] == 'CC-BY-4.0' and item['physicalEvidence'] is False
                                    for item in provenance['assets']), 'local illustration credit/license/evidence mismatch')
                        page.go_back()
                        require(page.url == origin + base + '#uses', 'attribution reader did not return to the landing page')
                        result['cases'].append({'base':base,'illustrationAttributionReaderJourney':True,
                                                'creditSourceLicenseAndChanges':True,'scopedRightsPending':True,
                                                'localProvenanceReadback':True,'externalLinksFollowed':False})
                        expect(page.locator('#demo-card')).to_be_hidden()
                        for control in ('pause-motion', 'change-appearance', 'show-card', 'show-headline', 'finish-card'):
                            expect(page.locator('#' + control)).to_be_disabled()
                        context.close()
                        context = local_context(browser, origin, blocked, viewport={"width":320,"height":900})
                        page = context.new_page()
                        page.route('**/assets/demo.js', lambda route: route.abort())
                        page.goto(origin + base)
                        require(page.locator('h1').is_visible(), "asset failure hid content")
                        require(all(arc["state"] == "paused" for arc in page.evaluate(GEOMETRY)["arcs"]), "script failure started unpausable motion")
                        context.close()
                        result["cases"].append({"base":base,"initialReducedMotion":True,"noJavaScript":True,
                                                "scriptFailureStaticFallback":True,"gzipBytes":manifest["gzipBytes"]})
                        require(not blocked, f"unexpected network attempt blocked: {blocked}")
                        rejected = []
                        context = local_context(browser, origin, rejected)
                        try:
                            page = context.new_page()
                            for forbidden in ("https://example.invalid/never-connect", origin + "/api/v1/health"):
                                try:
                                    page.goto(forbidden)
                                except Error:
                                    pass
                                else:
                                    raise AssertionError("forbidden browser navigation succeeded")
                            require(rejected == ["https://example.invalid/never-connect", origin + "/api/v1/health"],
                                    "network guard did not intercept both forbidden navigations")
                        finally:
                            context.close()
                        result["cases"].append({"base":base,"externalAndApiRequestsAbortedBeforeConnect":True})
                    finally:
                        http.shutdown()
                        worker.join(timeout=3)
                        require(not worker.is_alive(), "preview worker leaked")
        finally:
            browser.close()
    result["status"] = "passed"
    if evidence:
        (evidence / "browser-results.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--browser-executable", type=Path)
    inputs = parser.add_mutually_exclusive_group()
    inputs.add_argument("--artifact", type=Path, help="check exact verified artifact bytes at their manifest base")
    inputs.add_argument("--archive", type=Path, help="check a bounded downloaded static artifact ZIP")
    parser.add_argument("--expected-manifest-sha256", help="require independently reviewed artifact identity")
    args = parser.parse_args()
    try:
        if not (args.artifact or args.archive) and args.expected_manifest_sha256 is not None:
            raise ValueError('expected manifest identity requires --artifact or --archive')
        if args.archive:
            with archive_directory(args.archive) as directory:
                run(args.evidence, args.browser_executable, directory, args.expected_manifest_sha256)
        else:
            run(args.evidence, args.browser_executable, args.artifact, args.expected_manifest_sha256)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(1, f"browser check failed: {error}\n")


if __name__ == "__main__":
    main()
