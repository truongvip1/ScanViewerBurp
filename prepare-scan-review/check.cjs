const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('../capture-ui-review/node_modules/playwright');
const fragment = 'C:/Users/84845/.codex/visualizations/2026/09/17/01a0afaf-c45d-7042-b665-c869b86fd83a/prepare-scan-dialog.html';
(async () => {
  const browser = await chromium.launch({headless:true, executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
  try {
    const page = await browser.newPage({viewport:{width:736,height:1200}});
    const errors=[]; page.on('pageerror',e=>errors.push(e.message));
    const source=fs.readFileSync(fragment,'utf8');
    await page.setContent(source);
    const requireText=async(selector,value)=>{if(!(await page.locator(selector).textContent()).includes(value))throw new Error(selector+' missing '+value);};
    await requireText('#ps-count','7 / 7');
    await page.locator('#ps-clear').click();
    if(!await page.locator('#ps-confirm').isDisabled())throw new Error('Empty scope can submit');
    await page.locator('#ps-request').selectOption('1');
    await requireText('#ps-count','3 / 3');
    await page.locator('#ps-response-tab').click();
    await requireText('#ps-response','No saved response');
    await page.locator('#ps-request').selectOption('0');
    await requireText('#ps-count','0 / 7');
    await page.locator('#ps-location').selectOption('Body');
    await page.locator('#ps-all').check();
    await requireText('#ps-count','2 / 7');
    await page.locator('#ps-location').selectOption('All locations');
    await page.locator('#ps-all').check();
    await page.locator('#ps-request-tab').click();
    await page.getByRole('button',{name:'Authorization',exact:true}).click();
    await requireText('#ps-point-info','preserve Scanner payload');
    await page.evaluate(()=>{
      const mark=[...document.querySelectorAll('#ps-raw mark')].find(m=>m.textContent==='notebook');
      const range=document.createRange();range.setStart(mark.firstChild,0);range.setEnd(mark.firstChild,4);
      const selection=window.getSelection();selection.removeAllRanges();selection.addRange(range);
      document.querySelector('#ps-raw').dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));
    });
    await page.locator('#ps-add').click();
    if(!await page.locator('#ps-replace').isVisible())throw new Error('Missing overlap decision');
    await requireText('#ps-count','7 / 7');
    await page.locator('#ps-replace').click();
    await requireText('#ps-point-info','Custom selection');
    await requireText('#ps-point-info','note');
    await page.locator('#ps-confirm').click();
    await requireText('#ps-validation','Queue remains paused');
    await page.locator('#ps-start').check();await page.locator('#ps-confirm').click();
    await requireText('#ps-validation','Queue running');
    await page.evaluate(()=>window.dispatchEvent(new CustomEvent('openai:set_globals',{detail:{globals:{widgetState:{privateContent:{index:1,layout:'tabs',running:true}}}}})));
    if(await page.locator('#ps-start-label').isVisible())throw new Error('Start option visible for running queue');
    await requireText('#ps-queue-context','may start');
    for(const width of [900,736,320]) {
      await page.setViewportSize({width,height:1400});
      for(const layout of ['tabs','side']) {
        await page.locator('#ps-layout').selectOption(layout);
        const dims=await page.evaluate(()=>({width:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth}));
        if(dims.scroll>dims.width+1)throw new Error('Overflow '+width+' '+layout);
      }
    }
    await page.setContent(source);await page.setViewportSize({width:736,height:1200});
    await page.locator('#prepare-scan-proposal').screenshot({path:path.join(__dirname,'proposal-736.png')});
    await page.emulateMedia({colorScheme:'dark'});
    await page.setViewportSize({width:320,height:1500});
    await page.locator('#prepare-scan-proposal').screenshot({path:path.join(__dirname,'proposal-320-dark.png')});
    if(errors.length)throw new Error(errors.join('\n'));
    console.log('Concept checks passed: independent drafts, filtered select-all, empty-scope guard, saved response, field highlight, overlap confirmation, simulated enqueue, queue state and 900/736/320 px layouts. No HTTP requests sent.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
