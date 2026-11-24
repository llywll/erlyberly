import { chromium } from 'playwright'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })

let shellOut = 0
page.on('websocket', ws => {
  console.log('[ws open]', ws.url())
  ws.on('framesent', d => { const s = d.toString(); if (s.includes('shell_in')) console.log('[ws→]', s.slice(0, 70)) })
  ws.on('framereceived', d => { const s = d.toString(); if (s.includes('shell_out')) { shellOut++; if (shellOut <= 4) console.log('[ws←]', s.slice(0, 130)) } })
})
page.on('pageerror', e => console.log('[page-error]', String(e).slice(0, 200)))

await page.goto('http://localhost:8080/', { waitUntil: 'networkidle' })
await page.fill('input[placeholder*="本地节点"]', 'ebweb')
await page.fill('input[placeholder*="目标节点"]', 'ebmini@127.0.0.1')
await page.fill('input[placeholder*="Cookie"]', 'cookiexxx')
await page.click('button:has-text("连接")')
await page.waitForURL('**/main', { timeout: 30000 })
console.log('== 已连接')
await page.click('text=控制台')
console.log('== 控制台页签已打开')
await page.waitForTimeout(4000)

// 通过 xterm 输入命令
await page.keyboard.type('X = 41.')
await page.keyboard.press('Enter')
await page.waitForTimeout(2000)
await page.keyboard.type('X * 2.')
await page.keyboard.press('Enter')
await page.waitForTimeout(3000)

const term = await page.evaluate(() => (document.querySelector('.xterm-rows')?.innerText ?? 'NO').slice(0, 500))
console.log('[xterm 内容]', JSON.stringify(term))
console.log('[shell_out 帧数]', shellOut)
await page.screenshot({ path: '/tmp/edge-console2.png' })
await browser.close()
process.exit(0)
