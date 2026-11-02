// 시연용 가상 등하원 기록을 만든다. 실제 기록에서 가져온 값은 하나도 없다.
// 실행: node tools/make-sample-data.js  → data/sample_saturdays.csv, center-web/data.js
const fs = require('fs');
const path = require('path');

let seed = 20260103;
const rand = () => { seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5; return (seed >>> 0) / 4294967296; };
const hhmm = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');

const DATES = ['2026-01-03', '2026-01-10', '2026-01-17', '2026-01-24'];
const PICKUP = { '03': ['S2', 'S3'], '00': ['S6', ''], '02': ['S5', ''] };
const DROPOFF = { '03': ['S2', 'S3'], '01': ['S1', 'S7'], '02': ['S4', ''] };

// 어르신별로 자주 타는 차와 고정 탑승 시각을 정해 둔다.
const elders = Array.from({ length: 24 }, (_, i) => {
  const pickup = i < 14 ? '03' : i < 18 ? '00' : '02';
  // 스타리아는 1차(08:30 도착)와 2차(09:00 도착)로 나눠 탄다.
  const at = pickup === '03' ? (i < 8 ? 445 + i * 6 : 515 + (i - 8) * 4)
    : pickup === '00' ? 470 + (i - 14) * 8
    : 515 + (i - 18) * 4;
  return {
    id: 'E' + String(i + 1).padStart(2, '0'),
    km: (0.5 + rand() * 9).toFixed(1),
    pickup,
    at,
    dropoff: ['03', '01', '02'][i % 3],
    other: ['01', '02', '03'][i % 3],
    leave: 980 + (i % 8) * 8, // 16:20부터
  };
});

const rows = [['date', 'date_estimated', 'elder', 'official_km', 'pickup_vehicle', 'pickup_time', 'pickup_driver', 'pickup_escort', 'dropoff_vehicle', 'dropoff_time', 'dropoff_driver', 'dropoff_escort', 'guardian_direct']];
DATES.forEach((date) => {
  elders.forEach((e) => {
    if (rand() < 0.12) return; // 결석
    const up = rand() < 0.1 ? null : e.pickup; // 보호자가 직접 모심
    const down = rand() < 0.1 ? null : rand() < 0.9 ? e.dropoff : e.other;
    const direct = [up ? '' : '등원', down ? '' : '하원'].filter(Boolean).join('·');
    rows.push([
      date, '', e.id, e.km,
      up || '45', up ? hhmm(e.at) : '', ...(up ? PICKUP[up] : ['', '']),
      down || '45', down ? hhmm(e.leave) : '', ...(down ? DROPOFF[down] : ['', '']),
      direct,
    ]);
  });
});

const csv = rows.map((r) => r.join(',')).join('\n');
fs.writeFileSync(path.join(__dirname, '../data/sample_saturdays.csv'), csv + '\n');
fs.writeFileSync(path.join(__dirname, '../center-web/data.js'), '// data/sample_saturdays.csv 사본(가상 데이터). file:// 로 열어도 읽히도록 스크립트에 넣었다.\nconst MOSIDA_SAMPLE_CSV = ' + JSON.stringify(csv) + ';\n');
console.log(`${rows.length - 1}줄 생성`);
