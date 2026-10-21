// 작품 페이지 공통: 쉽게 읽기(콜라주 요약) 열고 닫기 + 자세히 읽기 모두 펼치기 + 작품 모음 돌아가기 주소
(() => {
  'use strict';
  const dlg = document.getElementById('easy');
  const openers = [...document.querySelectorAll('[data-easy-open]')];
  if (!dlg || !openers.length || typeof dlg.showModal !== 'function') { openers.forEach(b => { b.hidden = true; }); return; }
  let opener = openers[0];
  function open(e) {
    if (dlg.open) return;
    if (e && e.currentTarget) opener = e.currentTarget;
    dlg.showModal(); dlg.scrollTop = 0;
    if (location.hash !== '#easy') history.replaceState(history.state, '', location.pathname + location.search + '#easy');
  }
  function close() { if (dlg.open) dlg.close(); }
  openers.forEach(b => b.addEventListener('click', open));
  dlg.querySelectorAll('[data-easy-close]').forEach(b => b.addEventListener('click', close));
  dlg.addEventListener('close', () => {
    if (location.hash === '#easy') history.replaceState(history.state, '', location.pathname + location.search);
    opener.focus();
  });
  if (location.hash === '#easy') open();
})();

(() => {
  const btn = document.querySelector('[data-expand-all]');
  if (!btn) return;
  const all = () => [...document.querySelectorAll('.cp-detail')];
  const sync = () => { btn.textContent = all().every(d => d.open) ? '모두 접기' : '모두 펼치기'; };
  btn.addEventListener('click', () => { const openAll = !all().every(d => d.open); all().forEach(d => { d.open = openAll; }); sync(); });
  all().forEach(d => d.addEventListener('toggle', sync));
})();

// 작품 모음 돌아가기: 작품 모음에서 왔으면 보던 위치로, 아니면 이 작품 카드로 (작품 모음 portfolio-return.js 와 같은 약속)
(() => {
  const links = [...document.querySelectorAll('[data-portfolio-back]')];
  if (!links.length) return;
  const query = new URLSearchParams(location.search);
  let saved = null; try { saved = JSON.parse(sessionStorage.getItem('portfolio.reading.return.v1')); } catch (e) { /* 없음 */ }
  links.forEach(link => {
    const home = new URL(link.getAttribute('href'), location.href);
    const work = link.dataset.portfolioBack;
    home.search = ''; home.hash = '';
    if (work) home.searchParams.set('work', work);
    if (query.get('from') === 'portfolio' && saved && saved.version === 1) home.searchParams.set('resume', '1');
    else home.hash = 'carousel';
    link.href = home.href;
  });
})();
