// 앱이 짠 표와 기록을 비교한다. 실행: node center-web/verify.js [csv 경로]
// 각 날짜는 그날을 뺀 나머지 3회 기록만 보고 짠다(그날 정답을 미리 보지 않게).
const fs = require('fs');
const path = require('path');
const P = require('./planner.js');

const rows = P.parseCsv(fs.readFileSync(process.argv[2] || path.join(__dirname, '../data/sample_saturdays.csv'), 'utf8'));
const dates = [...new Set(rows.map((r) => r.date))];

for (const kind of ['dropoff', 'pickup']) {
  let same = 0, total = 0, pairs = 0, agree = 0;
  console.log(kind === 'dropoff' ? '\n[하원]' : '\n[등원]');
  for (const date of dates) {
    const input = P.dayInput(rows, date, kind);
    const plan = P.makePlan({ rows, date, kind, ...input });
    const c = P.compare(plan, rows);
    same += c.same; total += c.total; pairs += c.pairs; agree += c.agree;
    const used = plan.vehicles.map((v) => `${v.code}:${v.driver}${v.escort ? '+' + v.escort : ''}(${v.riders.length})`).join(' ');
    console.log(`${date} 근무 ${input.staff.join(',')} | 차량 일치 ${c.same}/${c.total} | 순서 ${c.agree}/${c.pairs} | ${used}${plan.warnings.length ? ' | 경고 ' + plan.warnings.join(' / ') : ''}`);
  }
  console.log(`합계: 차량 일치 ${same}/${total} = ${Math.round((same / total) * 100)}%, 순서 일치 ${agree}/${pairs} = ${Math.round((agree / pairs) * 100)}%`);
}
