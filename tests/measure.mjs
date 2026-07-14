import {chromium} from '@playwright/test';
const BASE='http://localhost:8081';
const u={email:'measure-nav@example.com',password:'test_password123',displayName:'Measure'};
const b=await chromium.launch();
const api=await b.newContext();
// The API refuses to create the initial account, so bootstrap the first (admin) user via the web
// /register form, then register this measurement user via the API.
await api.request.post(BASE+'/register',{form:{email:'measure-admin@example.com',displayName:'Measure Admin',password:'bootstrap_password123',confirmPassword:'bootstrap_password123'}});
await api.request.post(BASE+'/api/auth/register',{data:u});
async function measure(w,h,label){
  const ctx=await b.newContext({viewport:{width:w,height:h}});
  const p=await ctx.newPage();
  await p.goto(BASE+'/login');
  await p.fill('input[name="email"]',u.email);
  await p.fill('input[name="password"]',u.password);
  await p.click('button[type="submit"]');
  await p.waitForURL(BASE+'/');
  const hh=await p.locator('header').first().evaluate(e=>e.getBoundingClientRect().height);
  let bh=null;
  const btn=p.locator('header button[aria-label*="menu"]');
  if(await btn.count()) bh=await btn.first().evaluate(e=>e.getBoundingClientRect().height);
  console.log(label,'header=',hh,'hamburger=',bh);
  await ctx.close();
}
await measure(1280,800,'DESKTOP');
await measure(390,844,'MOBILE ');
await b.close();
