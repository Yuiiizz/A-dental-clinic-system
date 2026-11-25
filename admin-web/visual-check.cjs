const { chromium } = require('playwright');
const path = require('node:path');

const p = Object.fromEntries(new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
}).formatToParts(new Date()).map((part) => [part.type, part.value]));
const base = Date.UTC(Number(p.year), Number(p.month) - 1, Number(p.day));
function time(day, hour) { return new Date(base + day * 86400000 + (hour - 8) * 3600000).toISOString(); }
const schedules = [];
const assistant_shifts = [];
for (let day = 1; day <= 14; day++) {
  for (const [branch, name, doctors] of [
    ['901', '云栖门店', [['911','林知夏',9,12],['915','许清禾',9,12],['912','周屿',13,17]]],
    ['902', '滨江门店', [['913','陈望',9,12],['918','叶澄',13,17]]],
  ]) {
    for (const [id, doctor, start, end] of doctors) schedules.push({
      schedule_id: `${branch}-${day}-${id}`, branch_id: branch, branch_name: name,
      dentist_id: id, dentist_name: doctor, starts_at: time(day, start), ends_at: time(day, end),
    });
    for (const [id, assistant, start, end] of [['951','方小满',9,12],['952','许闻溪',13,17]])
      assistant_shifts.push({ shift_id: `${branch}-${day}-${id}`, branch_id: branch,
        staff_id: id, name: assistant, job_title: '诊疗助手', starts_at: time(day, start), ends_at: time(day, end) });
  }
}
const data = {
  branches: [{ branch_id: '901', name: '云栖门店' }, { branch_id: '902', name: '滨江门店' }],
  patients: [], policies: [], appointments: [], schedules, assistant_shifts,
  services: [{ item_id: '901', name: '初诊检查', minutes: 30, price: '80' }],
  availability: schedules.filter((_, index) => index % 3 === 0).map((s) => ({ schedule_id: s.schedule_id, item_id: '901', slots: [s.starts_at] })),
};
async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' });
  try {
    for (const width of [1440, 760]) {
      const context = await browser.newContext({ viewport: { width, height: 900 }, deviceScaleFactor: 1 });
      const page = await context.newPage();
      page.on('console', (message) => process.stdout.write(`CONSOLE ${message.type()} ${message.text()}\n`));
      page.on('pageerror', (error) => process.stdout.write(`PAGEERROR ${error.message}\n`));
      await page.addInitScript(() => sessionStorage.setItem('a-clinic.session.csrf', 'mock-csrf'));
      await page.route('**/api/v1/auth/me', (route) => route.fulfill({ json: { data: {
        account_id: '902', name: '前台样例', identity_type: 'staff', must_change_password: false,
        roles: [{ role_code: 'frontdesk', branch_ids: ['901','902'] }],
      }, request_id: 'mock' } }));
      await page.route('**/api/v1/staff/booking', (route) => route.fulfill({ json: { data, request_id: 'mock' } }));
      await page.goto('http://127.0.0.1:5173/frontdesk-schedule');
      await page.waitForTimeout(1500);
      process.stdout.write(`DEBUG ${page.url()} ${(await page.locator('body').innerText()).slice(0,300)}\n`);
      await page.getByRole('table', { name: '医生排班课程表' }).waitFor();
      if (!(await page.getByText('当班助手').count())) throw new Error('Assistant label missing');
      const out = path.resolve(__dirname, '../docs/screenshots/admin-ui', `frontdesk-timetable-${width}.png`);
      await page.screenshot({ path: out, fullPage: true });
      await page.getByRole('button', { name: '后七天 →' }).click();
      if (!(await page.getByRole('columnheader', { name: /第/ }).count())) {
        const columns = await page.locator('.calendar-date').count();
        if (columns !== 7) throw new Error('Expected seven date columns');
      }
      await page.locator('.board-tools select').selectOption('902');
      if (!(await page.getByText('陈望').count())) throw new Error('Branch switch failed');
      await context.close();
      process.stdout.write(`PASS ${width}: two-week grid, branch switch and assistants\n`);
    }
  } finally { await browser.close(); }
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
