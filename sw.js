const CACHE='hajujangbu-v3';
const FILES=['./privacy.html','./terms.html','./','./index.html','./manifest.json','./icon-192.png','./icon-512.png','./apple-touch-icon.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(FILES)));self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))));self.clients.claim()});
self.addEventListener('fetch',e=>{
 const u=new URL(e.request.url);
 if(u.origin!==location.origin)return; // 구글 API 등 외부 요청은 캐시하지 않음
 // 다른 앱(문자 앱 등)에서 "공유 → 하루장부"로 넘어온 글: 서버로 보내지 않고 여기서 받아 주소의 # 뒤로 넘김
 if(e.request.method==='POST'&&u.pathname.endsWith('/share-target')){
  e.respondWith((async()=>{
   let t='';
   try{const f=await e.request.formData();t=[f.get('title'),f.get('text'),f.get('url')].filter(Boolean).join(' ')}catch(err){}
   return Response.redirect(new URL('./index.html',self.registration.scope).href+'#pay='+encodeURIComponent(t),303)})());
  return}
 if(e.request.method!=='GET')return;
 // 주소에 붙은 ?pay=… 같은 내용(결제 문자)이 캐시에 남지 않도록 검색어를 뺀 주소로 저장
 const k=e.request.mode==='navigate'?new Request(u.origin+u.pathname):e.request;
 e.respondWith(fetch(e.request).then(r=>{const c=r.clone();caches.open(CACHE).then(x=>x.put(k,c));return r}).catch(()=>caches.match(k,{ignoreSearch:true}).then(m=>m||caches.match('./index.html'))));
});
