import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeD1 } from './d1shim.mjs';
import { route } from '../src/index.js';
import { HttpError, errorResponse } from '../src/util.js';
const T0 = 1_800_000_000_000;
async function setup() {
 const DB = makeD1(new URL('../migrations', import.meta.url).pathname);
 const env = { DB, BOT_SECRET:'bot-secret-0123456789' };
 for (const [id, discord] of [['alice','d-alice'],['bob','d-bob']]) {
   await DB.prepare('INSERT INTO accounts (id,tag,nick,created_at,recovery_hash,discord_id) VALUES (?,?,?,?,?,?)').bind(id,id,id,T0,'hash-'+id,discord).run();
 }
 await DB.prepare('INSERT INTO communities (id,name,description,owner_id,forum_id,category_id,mod_role_id,member_role_id,created_at,about,links,nsfw) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)').bind('c1','Catalog Home','Hello','alice','forum','cat','mod','member',T0,'About','https://example.org',1).run();
 await DB.prepare('INSERT INTO invite_codes (code,community_id,note,approval,created_by,created_at) VALUES (?,?,?,?,?,?)').bind('PUBLIC-CODE','c1','',1,'alice',T0).run();
 async function call(body, auth=true) {
  const req = new Request('https://test/v2/bot/catalog',{method:'POST',headers:auth ? {authorization:'Bearer '+env.BOT_SECRET} : {},body:JSON.stringify(body)});
  let res; try { res=await route(req,env,T0); } catch(e) { if (!(e instanceof HttpError)) throw e; res=errorResponse(e); } return {status:res.status,data:await res.json()};
 }
 return { DB, call };
}
test('catalog returns only owned community and explicitly selected active code',async()=>{
 const {DB,call}=await setup();
 assert.equal((await call({discordId:'d-alice',code:'PUBLIC-CODE'},false)).status,401);
 assert.equal((await call({discordId:'d-bob',code:'PUBLIC-CODE'})).data.code,'no_community');
 assert.equal((await call({discordId:'missing'})).data.code,'not_linked');
 assert.deepEqual((await call({discordId:'d-alice'})).data.community,{id:'c1',name:'Catalog Home'});
 let r=await call({discordId:'d-alice',code:'public-code'}); assert.equal(r.data.community.about,'About'); assert.equal(r.data.community.nsfw,true); assert.equal(r.data.code,'PUBLIC-CODE'); assert.equal(r.data.approval,true);
 assert.equal((await call({discordId:'d-alice',code:'BAD'})).data.code,'invalid_invite');
 await DB.prepare('UPDATE invite_codes SET expires_at = ? WHERE code = ?').bind(T0,'PUBLIC-CODE').run();
 assert.equal((await call({discordId:'d-alice',code:'PUBLIC-CODE'})).data.code,'invalid_invite');
 await DB.prepare('UPDATE invite_codes SET expires_at = NULL, max_uses = 1, uses = 1 WHERE code = ?').bind('PUBLIC-CODE').run();
 assert.equal((await call({discordId:'d-alice',code:'PUBLIC-CODE'})).data.code,'invalid_invite');
 await DB.prepare('UPDATE invite_codes SET revoked = 1 WHERE code = ?').bind('PUBLIC-CODE').run();
 assert.equal((await call({discordId:'d-alice',code:'PUBLIC-CODE'})).data.code,'invalid_invite');
 await DB.prepare('UPDATE accounts SET banned = 1 WHERE id = ?').bind('alice').run();
 assert.equal((await call({discordId:'d-alice'})).data.code,'not_linked');
});
