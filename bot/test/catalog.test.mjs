import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeCatalog, catalogCard, CATALOG_COMMANDS, catalogState } from '../catalog.js';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

function fixture() {
  let data = { community: { id: 'c1', name: 'Example', description: 'Hello @everyone', about: 'About', links: '', nsfw: true }, code: 'MF-TEST', approval: true };
  let store = { version: 1, posts: {} }; const actions = []; const pendingCalls = [];
  const call = async (_path, body) => { pendingCalls.push(body); return { status: 200, data: body.code == null ? { community: { id: data.community.id, name: data.community.name } } : structuredClone(data) }; };
  const state = { load: async () => structuredClone(store), save: async (v) => { store = structuredClone(v); } };
  const forum = { create: async (c) => { actions.push(['create', c]); return { id: 't1', url: 'https://discord.com/channels/g/t1' }; }, update: async (id,c) => { actions.push(['update',id,c]); return 'https://discord.com/channels/g/t1'; }, remove: async (id) => { actions.push(['remove',id]); } };
  const handle = makeCatalog({ call, state, forum, log: { error() {} }, now: () => 10 });
  function interaction(commandName, user = 'u1') {
    const out=[]; const i = { user: {id:user}, commandName, customId: '', deferred:false, replied:false, isChatInputCommand:()=>!!commandName, isButton:()=>!commandName,
      options: {getString:()=> 'MF-TEST'}, deferReply:async()=>{i.deferred=true;}, deferUpdate:async()=>{i.deferred=true;},
      reply:async(x)=>{i.replied=true;out.push(x);}, editReply:async(x)=>out.push(x) };
    return { i, out };
  }
  return { handle, interaction, actions, pendingCalls, change: (f) => f(data), getStore:()=>store };
}

test('explicit preview, owner-bound confirmation, create then update, unpublish without revoking code', async () => {
 const f=fixture(); const p=f.interaction('community-publish'); assert.equal(await f.handle(p.i), true); assert.equal(f.actions.length,0);
 const preview=p.out[0]; assert.match(preview.content,/MF-TEST/); assert.match(preview.content,/public/i); assert.match(preview.content,/@\u200beveryone/);
 const button=preview.components[0].components[0].custom_id; const wrong=f.interaction(null,'u2'); wrong.i.customId=button; await f.handle(wrong.i); assert.equal(f.actions.length,0);
 const yes=f.interaction(null); yes.i.customId=button; await f.handle(yes.i); assert.equal(f.actions[0][0],'create'); assert.equal(f.actions[0][1].title,'[18+] Example'); assert.equal(f.getStore().posts.c1,'t1');
 const again=f.interaction('community-publish'); await f.handle(again.i); const y2=f.interaction(null); y2.i.customId=again.out[0].components[0].components[0].custom_id; await f.handle(y2.i); assert.equal(f.actions[1][0],'update');
 const del=f.interaction('community-unpublish'); await f.handle(del.i); assert.deepEqual(f.actions[2],['remove','t1']); assert.match(del.out[0],/remains active/);
 assert.equal(CATALOG_COMMANDS.length,2);
});
test('cancellation and changed data never publish', async () => {
 const f=fixture(); let p=f.interaction('community-publish'); await f.handle(p.i); let cancel=f.interaction(null); cancel.i.customId=p.out[0].components[0].components[1].custom_id; await f.handle(cancel.i); assert.equal(f.actions.length,0);
 p=f.interaction('community-publish'); await f.handle(p.i); f.change(d => d.community.name='Changed'); let yes=f.interaction(null); yes.i.customId=p.out[0].components[0].components[0].custom_id; await f.handle(yes.i); assert.equal(f.actions.length,0); assert.match(yes.out[0].content,/changed/i);
});
test('NSFW titles do not duplicate marker', () => assert.equal(catalogCard({community:{name:'X (18+)',nsfw:true},code:'A'}).title,'X (18+)'));
test('durable state rejects corruption', async () => { const dir=await mkdtemp(join(tmpdir(),'mf-cat-')); try { const path=join(dir,'catalog.json'); const s=catalogState(path); assert.deepEqual(await s.load(),{version:1,posts:{}}); await s.save({version:1,posts:{c:'t'}}); assert.deepEqual((await s.load()).posts,{c:'t'}); assert.ok((await readFile(path,'utf8')).includes('"c"')); } finally { await rm(dir,{recursive:true,force:true}); } });
