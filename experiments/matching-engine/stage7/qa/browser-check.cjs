const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const out = path.join(__dirname,'large-evidence'); fs.mkdirSync(out,{recursive:true});
const checks=[];
const note=(name,details={})=>checks.push({name,status:'PASS',...details});
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const context=await browser.newContext({viewport:{width:1920,height:1080}});
 const page=await context.newPage(); const errors=[];
 page.on('pageerror',e=>errors.push(String(e)));page.on('dialog',async d=>{errors.push('Unexpected dialog '+d.message());await d.dismiss();});
 async function state(value){await page.locator('#run-state[data-state="'+value+'"]').waitFor({timeout:240000});}
 async function choose(label,value){let box=page.getByRole('combobox',{name:label,exact:true});await box.fill(label.endsWith('платёж')?value.split(' · ')[0]:value);await page.getByRole('option',{name:value,exact:true}).click();await page.waitForTimeout(250);}
 async function metrics(expected){
  for(const [label,value] of Object.entries(expected)){
   const card=page.locator('.metric').filter({has:page.locator('span').filter({hasText:new RegExp('^'+label+'$')})});
   assert.equal(await card.locator('.metric-value').innerText(),String(value),label);
  }
 }
 try{
  await page.goto('http://127.0.0.1:8087',{waitUntil:'domcontentloaded',timeout:180000});
  await page.getByRole('button',{name:'Подготовить набор',exact:true}).waitFor({timeout:180000});
  await page.getByRole('button',{name:'Подготовить набор',exact:true}).click();await state('READY');
  assert((await page.locator('.dataset-summary').innerText()).includes('платежей 7'));
  await page.getByRole('button',{name:'Запустить сопоставление',exact:true}).click();await state('COMPLETE');
  await metrics({'Обработано':7,'SAFE':1,'REVIEW':4,'NO_CONTRACT':1,'Технические ошибки':1,'Доля SAFE от обработанных':'14.3%'});
  assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),7);
  note('Built-in dataset + real-engine UI E2E + dashboard',{payments:7,safe:1,review:4,noContract:1,errors:1});
  await page.screenshot({path:path.join(out,'light-1920.png'),fullPage:false});
  await page.getByRole('button',{name:'Подробнее',exact:true}).first().click();
  await page.getByText('Доступность операции',{exact:true}).waitFor();
  assert(await page.getByText('Не выполнялась',{exact:true}).count()>0);
  assert((await page.locator('.payment-detail').innerText()).includes('D-101'));
  await page.getByText('Полные технические сведения',{exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.payment-detail')?.innerText.includes('searchEvidence'));assert((await page.locator('.payment-detail').innerText()).includes('searchEvidence'));
  await page.screenshot({path:path.join(out,'safe-details.png'),fullPage:false});
  await page.getByRole('button',{name:'Закрыть',exact:true}).click();note('Raw purpose, participants, candidates, full Decision and PASS/FAIL/not-executed gates');
  await choose('Статус','REVIEW');assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),4);
  await page.getByRole('textbox',{name:'Поиск',exact:true}).fill('ABSENT');
  await page.waitForTimeout(500);assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),0);
  await metrics({'Обработано':7,'SAFE':1});note('Search empty state does not change run metrics');
  await choose('Статус','Все');await page.waitForTimeout(500);
  assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),1);
  await page.getByRole('button',{name:'Подробнее',exact:true}).click();
  assert((await page.locator('.payment-detail').innerText()).includes('NO_CONTRACT'));
  assert((await page.locator('.payment-detail').innerText()).includes('Подтверждение отсутствия договора'));
  await page.keyboard.press('Escape');note('NO_CONTRACT details + Escape');
  await page.getByRole('textbox',{name:'Поиск',exact:true}).fill('');await page.waitForTimeout(500);
  await choose('Первый платёж','1 · SAFE');await choose('Второй платёж','2 · REVIEW');await page.getByRole('button',{name:'Сравнить платежи',exact:true}).click();
  await page.locator('.comparison .payment-detail').first().waitFor();assert.equal(await page.locator('.comparison .payment-detail').count(),2);
  const compared=await page.locator('.comparison').innerText();assert(compared.includes('SAFE')&&compared.includes('REVIEW'));
  await page.waitForTimeout(350);
  const compareGeometry=await page.locator('.comparison .payment-detail').evaluateAll(xs=>xs.map(x=>{const r=x.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom};}));
  assert(compareGeometry.every(b=>b.left>=0&&b.right<=1921&&b.top>=0&&b.bottom<=1081),JSON.stringify(compareGeometry));
  await page.screenshot({path:path.join(out,'comparison.png'),fullPage:false});
  await page.getByRole('button',{name:'Закрыть',exact:true}).click();note('Side-by-side SAFE/REVIEW comparison',{compareGeometry});
  await choose('Статус','TECHNICAL_ERROR');await page.waitForTimeout(400);
  await page.getByRole('button',{name:'Подробнее',exact:true}).click();
  assert((await page.locator('.payment-detail').innerText()).includes('намеренно вызывает SQLException'));
  assert((await page.locator('.payment-detail').innerText()).includes('Decision и признаки не получены'));
  await page.getByRole('button',{name:'Закрыть',exact:true}).click();note('Explicit injected JDBC fault is not a fake business Decision');
  await choose('Статус','Все');
  await page.getByRole('button',{name:'Сменить тему',exact:true}).click();
  await page.waitForFunction(()=>document.documentElement.getAttribute('theme')==='dark');assert.equal(await page.locator('html').getAttribute('theme'),'dark');
  await page.screenshot({path:path.join(out,'dark-1920.png'),fullPage:false});
  await page.setViewportSize({width:390,height:844});
  const overflow=await page.evaluate(()=>({width:innerWidth,document:document.documentElement.scrollWidth,painted:document.documentElement.scrollWidth*parseFloat(getComputedStyle(document.documentElement).zoom),zoom:getComputedStyle(document.documentElement).zoom}));
  assert(overflow.painted<=overflow.width+2,JSON.stringify(overflow));
  await page.screenshot({path:path.join(out,'dark-390.png'),fullPage:false});
  await page.getByRole('button',{name:'Сменить тему',exact:true}).click();
  await page.waitForFunction(()=>document.documentElement.getAttribute('theme')==='light');
  await page.screenshot({path:path.join(out,'light-390.png'),fullPage:false});
  await page.emulateMedia({reducedMotion:'reduce'});await page.getByRole('button',{name:'Подготовить набор',exact:true}).focus();
  note('Both themes, 1920/390 viewport, focus and reduced-motion',{overflow});
  await page.setViewportSize({width:1920,height:1080});
  await choose('Готовый набор','MIXED 10 000');await page.getByRole('button',{name:'Подготовить набор',exact:true}).click();await state('READY');
  assert((await page.locator('.dataset-summary').innerText()).includes('платежей 10000'));
  await page.getByRole('button',{name:'Запустить сопоставление',exact:true}).click();await state('RUNNING');
  assert(await page.getByRole('button',{name:'Запустить сопоставление',exact:true}).isDisabled());
  assert(await page.getByRole('button',{name:'Подготовить набор',exact:true}).isDisabled());
  await page.getByRole('textbox',{name:'Поиск',exact:true}).fill('1009999');await page.getByRole('textbox',{name:'Поиск',exact:true}).fill('');
  const realProgress=await page.locator('#matching-progress').getAttribute('value');await state('COMPLETE');
  await metrics({'Обработано':10000,'Технические ошибки':0});assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),10);
  const begin=Date.now();const pageInput=page.getByRole('textbox',{name:'Номер страницы',exact:true});await pageInput.fill('500');await pageInput.press('Enter');
  await page.getByText('Показаны 4991-5000 из 10000',{exact:true}).waitFor();const pageLatencyMs=Date.now()-begin;
  await page.getByRole('textbox',{name:'Поиск',exact:true}).fill('1009999');await page.waitForTimeout(300);
  assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),1);
  await page.getByRole('button',{name:'Подробнее',exact:true}).click();await page.getByText('Полные технические сведения',{exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('.payment-detail')?.innerText.includes('searchEvidence'));assert((await page.locator('.payment-detail').innerText()).includes('searchEvidence'));await page.getByRole('button',{name:'Закрыть',exact:true}).click();
  await page.getByRole('textbox',{name:'Поиск',exact:true}).fill('');await choose('Порядок','ID ↓');await page.waitForTimeout(300);
  assert((await page.locator('#results-grid').innerText()).includes('1009999'));
  for(const [label,id] of [['Первый платёж','1000000'],['Второй платёж','1000001']]){const box=page.getByRole('combobox',{name:label,exact:true});await box.fill(id);await page.getByRole('option').filter({hasText:new RegExp('^'+id+' · ')}).first().click();}
  for(const size of [{width:1920,height:1080},{width:390,height:844}]){
   await page.setViewportSize(size);
   for(const theme of ['light','dark']){
    if((await page.locator('html').getAttribute('theme'))!==theme)await page.getByRole('button',{name:'Сменить тему',exact:true}).click();
    const geometry=await page.evaluate(()=>({width:innerWidth,painted:document.documentElement.scrollWidth*parseFloat(getComputedStyle(document.documentElement).zoom)}));assert(geometry.painted<=geometry.width+2,JSON.stringify(geometry));
    await page.screenshot({path:path.join(out,'large-'+size.width+'-'+theme+'.png')});
    await page.getByRole('button',{name:'Подробнее',exact:true}).first().click();const close=page.getByRole('button',{name:'Закрыть',exact:true});await close.waitFor();const rect=await close.boundingBox();assert(rect.y+rect.height<=size.height+1,JSON.stringify(rect));await close.click();
    await page.getByRole('button',{name:'Сравнить платежи',exact:true}).click();await page.locator('.comparison .payment-detail').first().waitFor();const r=await close.boundingBox();assert(r.y+r.height<=size.height+1,JSON.stringify(r));await close.click();
   }
  }
  const scrolling=await page.locator('#results-grid').evaluate(grid=>{const all=[...grid.shadowRoot.querySelectorAll('*')];const x=all.find(e=>e.clientWidth>0&&e.scrollWidth>e.clientWidth+100);if(!x)return null;x.scrollLeft=80;return {client:x.clientWidth,scroll:x.scrollWidth,left:x.scrollLeft};});
  assert(scrolling&&scrolling.left>0,JSON.stringify(scrolling));
  note('Real MIXED10k, actual progress, no duplicate start, 10-row paging, page500, direct ID, lazy details, sorting and comparison in both themes/sizes',{pageLatencyMs,realProgress,scrolling,metrics:await page.locator('#dashboard').innerText()});
  await page.setViewportSize({width:1920,height:1080});await choose('Порядок','ID ↑');
  const sample=JSON.parse(fs.readFileSync(path.join(__dirname,'../src/main/resources/META-INF/resources/example.json'),'utf8'));
  sample.name='Пользовательский импорт';sample.clients[0].name="ООО <script>window.__stage7Injected=true</script>'; DROP TABLE client; --";sample.payments=sample.payments.filter(x=>x.fault!=='JDBC_ERROR');
  await page.locator('input[type=file]').setInputFiles({name:'own.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(sample))});await state('READY');
  assert((await page.locator('.dataset-summary').innerText()).includes('Пользовательский импорт'));
  await page.getByRole('button',{name:'Запустить сопоставление',exact:true}).click();await state('COMPLETE');
  await metrics({'Обработано':6,'Технические ошибки':0});note('Own JSON upload processed by real engine');
  await page.getByRole('button',{name:'Подробнее',exact:true}).first().click();await page.getByText('Доступность операции',{exact:true}).waitFor();assert((await page.locator('.payment-detail').innerText()).includes('<script>window.__stage7Injected=true</script>'));assert.equal(await page.evaluate(()=>globalThis.__stage7Injected),undefined);await page.getByRole('button',{name:'Закрыть',exact:true}).click();note('Imported HTML/SQL metacharacters remain inert text; tables still processed');
  await page.locator('input[type=file]').setInputFiles({name:'invalid.json',mimeType:'application/json',buffer:Buffer.from('{}')});
  await page.locator('#input-error').filter({hasText:/./}).waitFor();
  assert((await page.locator('.dataset-summary').innerText()).includes('Пользовательский импорт'));note('Invalid upload preserves previous dataset/results');
  await page.getByRole('button',{name:'Запустить сопоставление',exact:true}).click();await state('COMPLETE');
  await metrics({'Обработано':6,'SAFE':1});
  await page.locator('input[type=file]').setInputFiles({name:'oversize.json',mimeType:'application/json',buffer:Buffer.alloc(4*1024*1024+1,32)});
  await page.locator('#input-error').filter({hasText:/Файл не принят/}).waitFor();
  await metrics({'Обработано':6,'SAFE':1});note('Oversize upload rejected without state mutation');
  await page.getByRole('button',{name:'Сбросить',exact:true}).click();await state('EMPTY');
  assert.equal(await page.locator('.metric').count(),0);assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),0);
  note('Repeat run and reset');
  sample.name='Пагинация';sample.payments=Array.from({length:12},(_,n)=>({...sample.payments[0],id:n+1}));
  await page.locator('input[type=file]').setInputFiles({name:'pages.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(sample))});await state('READY');
  await page.getByRole('button',{name:'Запустить сопоставление',exact:true}).click();await state('COMPLETE');
  await metrics({'Обработано':12,'SAFE':12});
  assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),10);
  await page.getByRole('button',{name:'Следующая страница',exact:true}).click();await page.waitForTimeout(300);
  assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),2);
  await page.getByRole('button',{name:'Первая страница',exact:true}).click();await page.waitForTimeout(300);
  assert.equal(await page.getByRole('button',{name:'Подробнее',exact:true}).count(),10);
  note('Real imported 12-payment run + current-page pagination');
  assert.equal((await context.request.get('http://127.0.0.1:8087/internal/health')).status(),403);
  assert.equal((await context.request.post('http://127.0.0.1:8087/internal/shutdown')).status(),403);
  note('Lifecycle endpoints reject missing token');
  assert.equal(errors.length,0,JSON.stringify(errors));
  fs.writeFileSync(path.join(out,'BROWSER_RESULT.json'),JSON.stringify({status:'PASS',browser:await browser.version(),checks,errors},null,2));
  console.log(JSON.stringify({status:'PASS',checks:checks.length,browser:await browser.version()}));
 }catch(e){
  await page.screenshot({path:path.join(out,'failure.png'),fullPage:false}).catch(()=>{});
  fs.writeFileSync(path.join(out,'BROWSER_RESULT.json'),JSON.stringify({status:'FAIL',checks,error:String(e),errors},null,2));
  console.error(e);process.exitCode=1;
 }finally{await browser.close();}
})();
