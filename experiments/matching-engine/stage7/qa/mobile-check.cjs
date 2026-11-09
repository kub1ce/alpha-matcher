const {chromium}=require('playwright');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');
(async()=>{const browser=await chromium.launch({channel:'chrome',headless:true});const page=await browser.newPage({viewport:{width:390,height:844}});const findings=[];
async function choose(label,value){const box=page.getByRole('combobox',{name:label,exact:true});await box.fill(label.endsWith('платёж')?value.split(' · ')[0]:value);await page.getByRole('option',{name:value,exact:true}).click();}
fs.mkdirSync(path.join(__dirname,'large-evidence'),{recursive:true});
try{await page.goto('http://127.0.0.1:8087');await page.getByRole('button',{name:'Подготовить набор',exact:true}).click();await page.locator('#run-state[data-state="READY"]').waitFor();await page.getByRole('button',{name:'Запустить сопоставление',exact:true}).click();await page.locator('#run-state[data-state="COMPLETE"]').waitFor();
for(const theme of ['light','dark']){
 if(theme==='dark'){await page.getByRole('button',{name:'Сменить тему',exact:true}).click();await page.waitForFunction(()=>document.documentElement.getAttribute('theme')==='dark');}
 await page.getByRole('button',{name:'Подробнее',exact:true}).first().click();await page.getByText('Доступность операции',{exact:true}).waitFor();await page.waitForTimeout(350);
 let button=page.getByRole('button',{name:'Закрыть',exact:true});let rect=await button.boundingBox();assert(rect.x>=0&&rect.x+rect.width<=391&&rect.y>=0&&rect.y+rect.height<=845,JSON.stringify(rect));
 await page.screenshot({path:path.join(__dirname,'large-evidence','detail-390-'+theme+'.png')});await button.click();
 await choose('Первый платёж','1 · SAFE');await choose('Второй платёж','2 · REVIEW');await page.getByRole('button',{name:'Сравнить платежи',exact:true}).click();await page.locator('.comparison .payment-detail').first().waitFor();await page.waitForTimeout(350);button=page.getByRole('button',{name:'Закрыть',exact:true});rect=await button.boundingBox();assert(rect.x>=0&&rect.x+rect.width<=391&&rect.y>=0&&rect.y+rect.height<=845,JSON.stringify(rect));await button.click();findings.push({theme,detailAndComparisonFooterVisible:true,closeRect:rect});
}
fs.writeFileSync(path.join(__dirname,'large-evidence','MOBILE_RESULT.json'),JSON.stringify({status:'PASS',findings},null,2));console.log('MOBILE PASS');}
catch(e){await page.screenshot({path:path.join(__dirname,'large-evidence','mobile-failure.png')});fs.writeFileSync(path.join(__dirname,'large-evidence','MOBILE_RESULT.json'),JSON.stringify({status:'FAIL',error:String(e),findings},null,2));throw e;}
finally{await browser.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
