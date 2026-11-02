// 등하원표 자동 생성 (서버 없이 브라우저·Node 양쪽에서 돈다). 어르신은 E번호, 직원은 S코드만 쓴다.
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.MosidaPlanner = api;
})(typeof self !== 'undefined' ? self : this, function () {
  const D = 'D', S = 'S', P = 'P', A = 'A';
  // 스타리아 1열은 운전자·동승 직원만 앉는다. 어르신은 2열부터 8명.
  const STARIA = [[D, A, S], [P, P, P], [P, A, P], [P, P, P]];
  const VEHICLES = [
    { code: '00', name: '레이EV', rows: [[D, A, S], [P, A, P]], doorRow: 2 },
    { code: '01', name: '스타리아 A', rows: STARIA, doorRow: 2 },
    { code: '02', name: '카니발', rows: [[D, A, S], [P, A, P], [P, A, P], [P, P, P]], doorRow: 2 },
    { code: '03', name: '스타리아 B', rows: STARIA, doorRow: 2 },
  ];
  // 시연용 직원별 운전 가능 차종. 실제 운영에서는 센터가 입력한다.
  const STAFF = {
    S1: ['00', '01', '02'], S2: ['00', '03'], S3: [], S4: ['00', '02'],
    S5: ['01', '02'], S6: ['00'], S7: [],
  };
  const ESCORT_FIRST = ['S3', 'S7'];
  // 하원은 출발 예정, 등원은 센터 도착 목표(08:30·09:00)
  const DEPART = { dropoff: ['16:20', '17:25'], pickup: ['08:30', '09:00'] };
  const COL = ['왼쪽', '가운데', '오른쪽'];

  const vehicle = (code) => VEHICLES.find((v) => v.code === code);
  const toMin = (t) => { const m = /^(\d+):(\d+)$/.exec(t || ''); return m ? +m[1] * 60 + +m[2] : null; };
  const fromMin = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
  const median = (a) => { const s = a.slice().sort((x, y) => x - y); return s[Math.floor((s.length - 1) / 2)]; };
  const isSaturday = (date) => new Date(date + 'T00:00:00').getDay() === 6;

  function parseCsv(text) {
    const [head, ...lines] = text.trim().split(/\r?\n/);
    const keys = head.split(',');
    return lines.map((l) => Object.fromEntries(l.split(',').map((v, i) => [keys[i], v])));
  }

  /** 먼저 내리는 분일수록 문 가까운 자리. 차 앱 TripController.autoAssign과 같은 규칙. */
  function seatsOf(v) {
    const list = [];
    v.rows.forEach((row, r) => row.forEach((role, c) => { if (role === P) list.push({ row: r + 1, col: c }); }));
    const dist = (s) => Math.abs(s.row - v.doorRow) + (s.row > v.doorRow ? 0.5 : 0);
    return list.sort((a, b) => dist(a) - dist(b) || b.col - a.col).map((s) => `${s.row}열 ${COL[s.col]}`);
  }

  /** 그날 표의 출석·보호자 직접·근무자. 시연용 기록을 입력값으로 불러온다. */
  function dayInput(rows, date, kind) {
    const day = rows.filter((r) => r.date === date);
    const staff = new Set();
    day.forEach((r) => ['pickup_driver', 'pickup_escort', 'dropoff_driver', 'dropoff_escort'].forEach((k) => { if (r[k]) staff.add(r[k]); }));
    return {
      present: day.map((r) => r.elder),
      direct: day.filter((r) => r[kind + '_vehicle'] === '45').map((r) => r.elder),
      staff: [...staff].sort(),
    };
  }

  /** 지난 기록에서 어르신별 차량 선호와 내리는 순서를 뽑는다. 시각 값 자체는 쓰지 않고 앞뒤 순서만 쓴다. */
  function learn(rows, date, kind) {
    const past = rows.filter((r) => r.date !== date);
    const stat = {};
    past.forEach((r) => {
      const code = r[kind + '_vehicle'];
      const t = toMin(r[kind + '_time']);
      const s = (stat[r.elder] = stat[r.elder] || { count: {}, last: {}, times: [] });
      if (code && code !== '45') {
        s.count[code] = (s.count[code] || 0) + 1;
        if (!s.last[code] || r.date > s.last[code]) s.last[code] = r.date;
      }
      if (t != null) s.times.push(t);
    });
    const driven = {};
    past.forEach((r) => {
      const d = r[kind + '_driver'], c = r[kind + '_vehicle'];
      if (d && c) driven[d + c] = (driven[d + c] || 0) + 1;
    });
    return { stat, driven };
  }

  const prefs = (s) => (s ? Object.keys(s.count).sort((a, b) => s.count[b] - s.count[a] || (s.last[b] > s.last[a] ? 1 : -1)) : []);

  /** 근무자로 태운 인원이 가장 많아지게 차마다 운전자를 고른다(직원별 운전 가능 차종 확인). */
  function chooseDrivers(load, staff, driven) {
    const codes = Object.keys(load).sort((a, b) => load[b] - load[a]);
    let best = { covered: -1, score: -1, map: {} };
    (function walk(i, used, map, covered, score) {
      if (i === codes.length) {
        if (covered > best.covered || (covered === best.covered && score > best.score)) best = { covered, score, map: { ...map } };
        return;
      }
      const code = codes[i];
      staff.forEach((s) => {
        if (used.has(s) || !(STAFF[s] || []).includes(code)) return;
        used.add(s); map[code] = s;
        walk(i + 1, used, map, covered + load[code], score + (driven[s + code] || 0));
        used.delete(s); delete map[code];
      });
      walk(i + 1, used, map, covered, score);
    })(0, new Set(), {}, 0, 0);
    return best.map;
  }

  /** 차별 명단(순서 포함)에 회차·자리·출발 예정을 붙인다. 직원이 손으로 옮긴 뒤에도 이 함수만 다시 부른다. */
  function arrange(plan) {
    const warnings = [];
    plan.vehicles.forEach((v) => {
      const seats = seatsOf(vehicle(v.code));
      const cap = seats.length;
      v.capacity = cap;
      v.rounds = [];
      // 등원은 고정 탑승 시각으로 도착 목표(1차·2차)를 가른다. 하원은 정원대로 나눈다.
      const groups = plan.kind === 'pickup'
        ? [v.riders.filter((r) => r.at && toMin(r.at) <= toMin(DEPART.pickup[0])), v.riders.filter((r) => !r.at || toMin(r.at) > toMin(DEPART.pickup[0]))]
        : [v.riders];
      groups.forEach((group, g) => {
        for (let i = 0; i < group.length; i += cap) {
          const part = group.slice(i, i + cap);
          const n = v.rounds.length;
          const seatFor = (idx) => (plan.kind === 'dropoff' ? seats[idx] : seats[part.length - 1 - idx]);
          v.rounds.push({
            round: n + 1,
            departAt: DEPART[plan.kind][plan.kind === 'pickup' ? g : n] || '시간 확인',
            stops: part.map((r, idx) => ({ ...r, order: idx + 1, seat: seatFor(idx) })),
          });
        }
      });
      if (plan.kind === 'pickup') {
        const late = v.riders.filter((r) => r.at && toMin(r.at) > toMin(DEPART.pickup[1])).length;
        if (late) warnings.push(`${v.name}: ${late}명은 탑승 예정이 ${DEPART.pickup[1]} 도착 목표보다 늦어요. 시간을 확인해 주세요.`);
      }
      if (v.rounds.length > 2) warnings.push(`${v.name}이 ${v.rounds.length}번 돌아야 해요. 다른 차로 옮겨 주세요.`);
      if (v.riders.length && !v.driver) warnings.push(`${v.name}에 운전할 사람이 없어요.`);
    });
    return { ...plan, warnings: [...plan.baseWarnings, ...warnings] };
  }

  function makePlan({ rows, date, kind, present, direct, staff }) {
    const { stat, driven } = learn(rows, date, kind);
    const directSet = new Set(direct);
    const riders = present.filter((id) => !directSet.has(id));
    const banned = kind === 'pickup' && isSaturday(date) ? ['01'] : [];
    const usable = (code) => !banned.includes(code) && vehicle(code);

    const load = {};
    riders.forEach((id) => {
      const top = prefs(stat[id]).find(usable);
      if (top) load[top] = (load[top] || 0) + 1;
    });
    const drivers = chooseDrivers(load, staff, driven);
    const running = Object.keys(drivers);
    const baseWarnings = [];
    if (riders.length && !running.length) baseWarnings.push('운전할 수 있는 근무자가 없어요. 근무자를 확인해 주세요.');

    const lists = Object.fromEntries(running.map((c) => [c, []]));
    // 등원 고정 탑승 시각: 지난 기록의 가운데 값. 표의 예정일 뿐 실제 기록을 대신하지 않는다.
    const plannedAt = (id) => {
      const times = stat[id] && stat[id].times;
      return kind === 'pickup' && times && times.length ? fromMin(median(times)) : null;
    };
    const orderKey = (id) => {
      const t = stat[id] && stat[id].times;
      return t && t.length ? t.reduce((a, b) => a + b, 0) / t.length : Infinity;
    };
    const unplaced = [];
    riders.forEach((id) => {
      const s = stat[id];
      const p = prefs(s).filter(usable);
      const code = p.find((c) => lists[c]);
      if (!code) { unplaced.push(id); return; }
      const total = Object.values(s.count).reduce((a, b) => a + b, 0);
      lists[code].push({
        elder: id,
        at: plannedAt(id),
        reason: code === p[0] ? `지난 ${total}번 중 ${s.count[code]}번 이 차` : `자주 타던 ${vehicle(p[0]).name}에 운전자가 없어 옮김`,
      });
    });
    unplaced.forEach((id) => {
      const room = (c) => seatsOf(vehicle(c)).length - lists[c].length;
      const code = running.slice().sort((a, b) => room(b) - room(a))[0];
      if (!code) return; // 운전자가 한 명도 없는 경우: 위에서 한 줄로 이미 알렸다
      lists[code].push({ elder: id, at: plannedAt(id), reason: prefs(stat[id]).length ? '자주 타던 차에 운전자가 없어 자리 많은 차로' : '지난 기록이 없어 자리 많은 차로' });
    });
    // 순서가 같으면 번호순. 입력 순서에 따라 표가 달라지지 않게 한다.
    running.forEach((c) => lists[c].sort((a, b) => orderKey(a.elder) - orderKey(b.elder) || a.elder.localeCompare(b.elder)));

    // 한 분 때문에 차가 한 번 더 돌지 않게: 혼자 남는 마지막 분을 회차가 늘지 않는 다른 차로 옮긴다.
    running.forEach((c) => {
      const cap = seatsOf(vehicle(c)).length;
      if (kind !== 'dropoff' || lists[c].length <= cap || lists[c].length % cap !== 1) return;
      const room = (x) => seatsOf(vehicle(x)).length - lists[x].length;
      const to = running.filter((x) => x !== c && room(x) > 0).sort((a, b) => room(b) - room(a))[0];
      if (!to) return;
      const rider = lists[c].pop();
      lists[to].push({ elder: rider.elder, reason: `${vehicle(c).name} 2차에 혼자 남아 자리 있는 차로 옮김` });
      lists[to].sort((a, b) => orderKey(a.elder) - orderKey(b.elder) || a.elder.localeCompare(b.elder));
    });

    const spare = staff.filter((s) => !Object.values(drivers).includes(s))
      .sort((a, b) => (ESCORT_FIRST.includes(b) ? 1 : 0) - (ESCORT_FIRST.includes(a) ? 1 : 0));
    const vehicles = running.slice().sort((a, b) => lists[b].length - lists[a].length).map((code) => ({
      code, name: vehicle(code).name, driver: drivers[code], escort: spare.shift() || null, riders: lists[code],
    }));
    return arrange({ date, kind, vehicles, direct: present.filter((id) => directSet.has(id)), baseWarnings });
  }

  /** 앱이 짠 표를 입력한 기록과 비교한다. 시각은 비교하지 않는다. */
  function compare(plan, rows) {
    const key = plan.kind + '_vehicle';
    const actual = Object.fromEntries(rows.filter((r) => r.date === plan.date).map((r) => [r.elder, r]));
    let same = 0, total = 0, pairs = 0, agree = 0;
    plan.vehicles.forEach((v) => {
      const ids = v.riders.map((r) => r.elder);
      ids.forEach((id) => {
        const real = actual[id] && actual[id][key];
        if (real && real !== '45') { total++; if (real === v.code) same++; }
      });
      for (let i = 0; i < ids.length; i++) for (let j = i + 1; j < ids.length; j++) {
        const a = actual[ids[i]], b = actual[ids[j]];
        if (!a || !b || a[key] !== v.code || b[key] !== v.code) continue;
        const ta = toMin(a[plan.kind + '_time']), tb = toMin(b[plan.kind + '_time']);
        if (ta == null || tb == null || ta === tb) continue;
        pairs++; if (ta < tb) agree++;
      }
    });
    return { same, total, pairs, agree };
  }

  /** 차 앱이 읽는 파일(mosida-trip.json) 내용 */
  function toTripFile(plan) {
    const trips = plan.vehicles.flatMap((v) => v.rounds.map((r) => ({
      vehicle: v.code, name: v.name, driver: v.driver, escort: v.escort, round: r.round, departAt: r.departAt,
      stops: r.stops.map((s) => ({ order: s.order, elder: s.elder, seat: s.seat, at: s.at || null })),
    })));
    return JSON.stringify({ date: plan.date, kind: plan.kind, trips, direct: plan.direct }, null, 2);
  }

  return { VEHICLES, STAFF, parseCsv, seatsOf, dayInput, makePlan, arrange, compare, toTripFile };
});
