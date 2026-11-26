// index.html 을 가상 브라우저(jsdom)에서 실제로 실행해 보고, 결제·입금 알림이 맞게 기록되는지 확인해요.
const { JSDOM, VirtualConsole } = require('jsdom');
const fs = require('fs');
const path = require('path');

const html = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
const errors = [];
const vc = new VirtualConsole();
vc.on('jsdomError', (e) => errors.push(String(e && e.stack || e)));
vc.on('error', (e) => errors.push(String(e)));

const dom = new JSDOM(html, {
  url: 'https://wltn1679.github.io/hajujangbu/',
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  virtualConsole: vc,
  beforeParse(w) {
    w.localStorage.setItem('hj_sched', '{}');
    w.localStorage.setItem('hj_exp', '{}');
    w.matchMedia = w.matchMedia || (() => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }));
    w.scrollTo = () => {};
    w.crypto = w.crypto || require('crypto').webcrypto;
  },
});
const w = dom.window;

let fail = 0;
const ok = (c, msg) => { if (!c) { fail++; console.log('  ✗ ' + msg); } else console.log('  ✓ ' + msg); };

(async () => {
  await new Promise((r) => setTimeout(r, 300));
  console.log('로드');
  ok(errors.length === 0, '스크립트 오류 없음' + (errors.length ? '\n' + errors.join('\n') : ''));
  ok(typeof w.hjIngest === 'function', 'hjIngest 가 있음');
  if (typeof w.hjIngest !== 'function') process.exit(1);

  const now = new Date();
  const md = `${now.getMonth() + 1}/${now.getDate()}`;
  const ev = (s) => w.eval(s);
  const items = () => ev('Object.values(exp).flat()');

  const ing = (list) => w.hjIngest(JSON.stringify(list));
  const T = Date.now();

  console.log('카드 승인');
  let n = ing([{ pkg: 'com.samsung.android.messaging', title: '신한카드', text: `[Web발신]\n신한카드(1234)승인 홍*동 4,500원 일시불 ${md} 12:30 스타벅스`, t: T }]);
  ok(n === 1, '승인 문자 1건 기록');
  let a = items();
  ok(a.length === 1 && a[0].amt === 4500 && a[0].kind === 'out' && a[0].cat === '카페', `지출 4,500 카페 (${JSON.stringify(a[0] || {})})`);

  console.log('같은 결제 문자+앱 알림 중복');
  n = ing([{ pkg: 'com.shinhan.sbanking', title: '신한 SOL페이', text: '스타벅스 4,500원 결제 완료', t: T + 20000 }]);
  ok(n === 0 && items().length === 1, '3분 안 같은 금액은 한 번만');

  console.log('입금');
  n = ing([{ pkg: 'com.samsung.android.messaging', title: 'KB국민은행', text: `[Web발신]\n[KB]${md} 14:03 123*456 홍길동 입금 50,000 잔액 1,200,000`, t: T + 400000 }]);
  a = items();
  const inc = a.find((x) => x.kind === 'in');
  ok(n === 1 && inc && inc.amt === 50000, `입금 +50,000 (${JSON.stringify(inc || {})})`);

  n = ing([{ pkg: 'com.kakaobank.channel', title: '카카오뱅크', text: '입금 2,800,000원 (주)하루 급여', t: T + 800000 }]);
  a = items();
  const sal = a.find((x) => x.kind === 'in' && x.amt === 2800000);
  ok(n === 1 && sal && sal.cat === '월급', `급여 입금 → 월급 (${JSON.stringify(sal || {})})`);

  console.log('출금');
  n = ing([{ pkg: 'com.kakaobank.channel', title: '카카오뱅크', text: '출금 12,000원 쿠팡', t: T + 1200000 }]);
  a = items();
  ok(n === 1 && a.some((x) => x.amt === 12000 && x.kind === 'out'), '출금 12,000 지출');

  console.log('걸러져야 하는 알림');
  n = ing([
    { pkg: 'com.kakao.talk', title: '엄마', text: '이번 달 용돈 30,000원 보냈어 입금 확인해', t: T + 2000000 },
    { pkg: 'com.samsung.android.messaging', title: '010-1234-5678', text: '5,000원 입금해줘', t: T + 2100000 },
    { pkg: 'com.shinhancard.app', title: '신한카드', text: '(광고) 결제 시 5,000원 쿠폰 지급', t: T + 2200000 },
    { pkg: 'com.shinhancard.app', title: '신한카드', text: '이번 달 결제 예정 금액 1,234,000원', t: T + 2300000 },
  ]);
  ok(n === 0, '대화·광고·결제예정 알림은 기록 안 함');

  console.log('취소');
  n = ing([{ pkg: 'com.samsung.android.messaging', title: '신한카드', text: `[Web발신]\n신한카드(1234)취소 홍*동 12,000원 ${md} 15:00 쿠팡`, t: T + 3000000 }]);
  a = items();
  ok(!a.some((x) => x.amt === 12000), '같은 금액 지출이 취소돼 사라짐');

  console.log('월별 정산');
  const y = now.getFullYear(), m = now.getMonth();
  const income = ev(`sumIn(${y},${m},1,31)`), spent = ev(`sumRange(${y},${m},1,31)`);
  ok(income === 2850000, `들어온 돈 2,850,000 (${income})`);
  ok(spent === 4500, `쓴 돈 4,500 (${spent})`);
  ev(`eM=new Date(${y},${m},1);renderSettle(${y},${m})`);
  const net = w.document.getElementById('setN').textContent;
  ok(/^\+2,845,500원$/.test(net), `남은 돈 +2,845,500원 (${net})`);

  console.log('화면 그리기');
  ev("tab('e')");
  w.localStorage.setItem('hj_open', 'true');
  ok(errors.length === 0, '화면 전환 중 오류 없음' + (errors.length ? '\n' + errors.join('\n') : ''));

  console.log(fail ? `\n실패 ${fail}건` : '\n모두 통과');
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
