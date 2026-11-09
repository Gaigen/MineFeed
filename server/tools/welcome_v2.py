import json, os, uuid, urllib.request, urllib.error
TOKEN = os.environ["DISCORD_BOT_TOKEN"]; API = "https://discord.com/api/v10"
ids = json.load(open("guild_ids.json"))
UA = "DiscordBot (https://github.com/Gaigen/MineFeed, 2)"
OLD_PREFIX = "## Welcome! 👋"  # only the previous plain-text welcome is replaced
def req(method, path, body=None, files=None):
    headers = {"Authorization": "Bot " + TOKEN, "User-Agent": UA}
    if files:
        b = uuid.uuid4().hex; parts = [f'--{b}\r\nContent-Disposition: form-data; name="payload_json"\r\nContent-Type: application/json\r\n\r\n{json.dumps(body)}\r\n'.encode()]
        for i, (name, data) in enumerate(files):
            parts.append(f'--{b}\r\nContent-Disposition: form-data; name="files[{i}]"; filename="{name}"\r\nContent-Type: image/png\r\n\r\n'.encode() + data + b"\r\n")
        parts.append(f"--{b}--\r\n".encode()); data = b"".join(parts); headers["Content-Type"] = f"multipart/form-data; boundary={b}"
    else:
        data = json.dumps(body).encode() if body is not None else None; headers["Content-Type"] = "application/json"
    try:
        with urllib.request.urlopen(urllib.request.Request(API + path, data=data, method=method, headers=headers)) as r:
            t = r.read(); return json.loads(t) if t else {}
    except urllib.error.HTTPError as e:
        print("ERR", method, path, e.code, e.read().decode()[:800]); return None

ch = lambda k: f"<#{ids[k]}>"
def link(label, url, emoji=None):
    b = {"type": 2, "style": 5, "label": label, "url": url}
    if emoji: b["emoji"] = {"name": emoji}
    return b
sep = {"type": 14, "divider": True, "spacing": 2}
txt = lambda s: {"type": 10, "content": s}
invite = ids.get("invite") or "https://discord.gg/FzZBQ43ujk"
body = {"flags": 1 << 15, "allowed_mentions": {"parse": []}, "attachments": [{"id": 0, "filename": "banner.png"}], "components": [
  {"type": 17, "accent_color": 0xFFFF55, "components": [
    {"type": 12, "items": [{"media": {"url": "attachment://banner.png"}, "description": "FEED — MineFeed + RetroConsole"}]},
    txt("# Welcome to Feed\nHome of **MineFeed** and **RetroConsole** — Minecraft mods by Gaigen. Grab a mod, get help, share ideas and hang out."),
    sep,
    txt("## 📰 MineFeed\nA social feed right inside Minecraft: posts, reactions, comments, notifications and private communities — without leaving the game.\n-# Press **J** in game to open the feed"),
    txt(f"📡 {ch('live_feed')}　🛟 {ch('mf_help')}　🐞 {ch('mf_bugs')}　💡 {ch('mf_ideas')}"),
    {"type": 1, "components": [link("CurseForge", "https://www.curseforge.com/minecraft/mc-mods/minefeed", "⬇️")]},
    sep,
    txt("## 🕹️ RetroConsole\nPlay libretro emulators on in-world TV screens."),
    txt(f"💬 {ch('rc_chat')}　🛟 {ch('rc_help')}　🐞 {ch('rc_bugs')}　💡 {ch('rc_ideas')}"),
    {"type": 1, "components": [
      link("CurseForge", "https://www.curseforge.com/minecraft/mc-mods/retroconsole", "⬇️"),
      link("Modrinth", "https://modrinth.com/mod/retroconsole", "⬇️"),
      link("GitHub", "https://github.com/Gaigen/retroconsole")]},
    sep,
    txt(f"## 🚀 Getting started\n**1.** Read the {ch('rules')}\n**2.** Follow {ch('announcements')} — new releases, loaders and Minecraft versions land there first\n**3.** Say hi in {ch('general')}\n-# Русскоязычным — {ch('ru_chat')}"),
    {"type": 1, "components": [link("Invite a friend", invite, "✉️")]},
  ]}]}
msg = req("POST", f"/channels/{ids['welcome']}/messages", body, files=[("banner.png", open("banner.png", "rb").read())])
if msg:
    print("posted", msg["id"])
    for m in req("GET", f"/channels/{ids['welcome']}/messages?limit=20") or []:
        if m["id"] != msg["id"] and m["author"].get("bot") and m.get("content", "").startswith(OLD_PREFIX):
            req("DELETE", f"/channels/{ids['welcome']}/messages/{m['id']}"); print("deleted old welcome", m["id"])
    ids["welcome_message"] = msg["id"]; json.dump(ids, open("guild_ids.json", "w"), indent=1)
