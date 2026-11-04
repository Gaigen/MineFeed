import json, os, time, urllib.request, urllib.error
TOKEN = os.environ["DISCORD_BOT_TOKEN"]
G = "1525462612920504420"
API = "https://discord.com/api/v10"
OLD_GENERAL = "1525462614044315770"   # feed-proxy posts reports here -> becomes private #reports (same id)
FEED_POSTS = "1525462716293185657"    # feed-proxy forum -> read-only #live-feed (same id)
TEXT_CAT = "1525462614044315768"
VOICE_CAT = "1525462614044315769"
VOICE_GENERAL = "1525462614044315771"
DEV_CATS = ["1567658245609226250", "1567658247928807444"]
EVERYONE = G
log = {}

def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(API + path, data=data, method=method, headers={
        "Authorization": "Bot " + TOKEN, "Content-Type": "application/json",
        "User-Agent": "DiscordBot (https://github.com/Gaigen/MineFeed, 2)", "X-Audit-Log-Reason": "Server setup for MineFeed and RetroConsole"})
    for _ in range(5):
        try:
            with urllib.request.urlopen(r) as resp:
                t = resp.read()
                return json.loads(t) if t else None
        except urllib.error.HTTPError as e:
            t = e.read().decode()
            if e.code == 429:
                time.sleep(json.loads(t).get("retry_after", 1) + 0.2); continue
            print("ERR", method, path, e.code, t[:300]); return None

P = {k: 1 << v for k, v in dict(VIEW=10, SEND=11, REACT=6, THREAD_PUB=35, THREAD_PRIV=36, SEND_THREAD=38,
     KICK=1, BAN=2, AUDIT=7, MANAGE_MSG=13, MANAGE_THREADS=34, TIMEOUT=40, MUTE=22, MOVE=24, NICKS=27, MENTION=17).items()}
def bits(*names): return str(sum(P[n] for n in names))
READ_ONLY = {"id": EVERYONE, "type": 0, "deny": bits("SEND", "THREAD_PUB", "THREAD_PRIV", "SEND_THREAD")}

roles = req("GET", f"/guilds/{G}/roles")
mod = next((r for r in roles if r["name"] == "Moderator"), None) or req("POST", f"/guilds/{G}/roles", {
    "name": "Moderator", "color": 0xFFFF55, "hoist": True, "mentionable": True,
    "permissions": bits("KICK", "BAN", "AUDIT", "MANAGE_MSG", "MANAGE_THREADS", "TIMEOUT", "MUTE", "MOVE", "NICKS", "MENTION")})
MOD = mod["id"]; log["role_moderator"] = MOD
STAFF_ONLY = [{"id": EVERYONE, "type": 0, "deny": bits("VIEW")}, {"id": MOD, "type": 0, "allow": bits("VIEW", "SEND")}]

existing = {}
for c in req("GET", f"/guilds/{G}/channels"):
    existing[(c["name"], c.get("parent_id"), c["type"])] = c
def channel(name, type=0, parent_id=None, **kw):
    key = (name, parent_id, type)
    if key in existing: return existing[key]
    c = req("POST", f"/guilds/{G}/channels", {"name": name, "type": type, **({"parent_id": parent_id} if parent_id else {}), **kw})
    existing[key] = c; return c
def forum(name, topic, tags, parent):
    return channel(name, 15, parent, topic=topic, default_sort_order=0,
                   available_tags=[{"name": t} for t in tags], default_reaction_emoji={"emoji_name": "👍"})

# categories
info = channel("INFO", 4)
req("PATCH", f"/channels/{TEXT_CAT}", {"name": "COMMUNITY"})
mf = channel("MINEFEED", 4)
rc = channel("RETROCONSOLE", 4)
req("PATCH", f"/channels/{VOICE_CAT}", {"name": "VOICE"})
staff = channel("STAFF", 4, permission_overwrites=STAFF_ONLY)

# INFO
welcome = channel("welcome", 0, info["id"], topic="Start here: what this server is and where to go.", permission_overwrites=[READ_ONLY])
rules = channel("rules", 0, info["id"], topic="Server rules. By chatting here you agree to them.", permission_overwrites=[READ_ONLY])
ann = channel("announcements", 0, info["id"], topic="Releases, updates and news for MineFeed and RetroConsole.", permission_overwrites=[READ_ONLY])

# COMMUNITY
general = channel("general", 0, TEXT_CAT, topic="Main chat. English please — Russian speakers welcome in #ru-chat.")
ru = channel("ru-chat", 0, TEXT_CAT, topic="Общение на русском.")
off = channel("off-topic", 0, TEXT_CAT, topic="Anything else.", rate_limit_per_user=3)
shots = channel("screenshots", 0, TEXT_CAT, topic="Show off your builds, setups and screenshots.")

# MINEFEED
req("PATCH", f"/channels/{FEED_POSTS}", {"name": "live-feed", "parent_id": mf["id"],
    "topic": "Live MineFeed posts from players in game. Read-only here — post from Minecraft (press J).",
    "permission_overwrites": [{"id": EVERYONE, "type": 0, "deny": bits("SEND", "THREAD_PUB", "THREAD_PRIV", "SEND_THREAD", "REACT")}]})
helpf = forum("minefeed-help", "Questions about installing or using MineFeed. One post per question; mark it solved when done.", ["question", "install", "account", "solved"], mf["id"])
bugs = forum("minefeed-bugs", "One post per bug: MineFeed version, NeoForge version, steps to reproduce, logs or screenshots.", ["bug", "crash", "ui", "fixed", "can't reproduce"], mf["id"])
ideas = forum("minefeed-ideas", "Ideas for MineFeed. Search first, one idea per post. React 👍 to vote.", ["idea", "planned", "done", "declined"], mf["id"])

# RETROCONSOLE
rc_chat = channel("retroconsole-chat", 0, rc["id"], topic="RetroConsole — play libretro emulators on in-world TV screens. Builds, setups, cores, chat.")
rc_help = forum("retroconsole-help", "Questions about RetroConsole: install, cores, setup, controls. One post per question; mark it solved.", ["question", "install", "cores", "controls", "solved"], rc["id"])
rc_bugs = forum("retroconsole-bugs", "One post per bug: RetroConsole version, NeoForge version, core, steps to reproduce, logs.", ["bug", "crash", "audio/video", "fixed", "can't reproduce"], rc["id"])
rc_ideas = forum("retroconsole-ideas", "Ideas for RetroConsole. Search first, one idea per post. React 👍 to vote.", ["idea", "planned", "done", "declined"], rc["id"])

# STAFF
req("PATCH", f"/channels/{OLD_GENERAL}", {"name": "reports", "parent_id": staff["id"],
    "topic": "MineFeed post reports (sent automatically by the bot).", "permission_overwrites": STAFF_ONLY})
modchat = channel("mod-chat", 0, staff["id"], permission_overwrites=STAFF_ONLY)
botlog = channel("bot-log", 0, staff["id"], topic="Bot and server events.", permission_overwrites=STAFF_ONLY)
req("PATCH", f"/channels/{VOICE_GENERAL}", {"name": "Lounge"})

order = [(info["id"], 0), (TEXT_CAT, 1), (mf["id"], 2), (rc["id"], 3), (VOICE_CAT, 4), (staff["id"], 5), (DEV_CATS[0], 6), (DEV_CATS[1], 7),
         (welcome["id"], 0), (rules["id"], 1), (ann["id"], 2),
         (general["id"], 0), (ru["id"], 1), (off["id"], 2), (shots["id"], 3),
         (FEED_POSTS, 0), (helpf["id"], 1), (bugs["id"], 2), (ideas["id"], 3),
         (rc_chat["id"], 0), (rc_help["id"], 1), (rc_bugs["id"], 2), (rc_ideas["id"], 3),
         (OLD_GENERAL, 0), (modchat["id"], 1), (botlog["id"], 2)]
req("PATCH", f"/guilds/{G}/channels", [{"id": i, "position": p} for i, p in order])

DESC = "Home of MineFeed and RetroConsole — Minecraft mods by Gaigen."
req("PATCH", f"/guilds/{G}", {"verification_level": 1, "explicit_content_filter": 2, "default_message_notifications": 1,
    "system_channel_id": general["id"], "system_channel_flags": 2 | 4 | 8,
    "rules_channel_id": rules["id"], "public_updates_channel_id": modchat["id"], "preferred_locale": "en-US",
    "description": DESC, "features": ["COMMUNITY"]})
g = req("GET", f"/guilds/{G}") or {}
log["community"] = "COMMUNITY" in g.get("features", [])
if log["community"]:
    req("PATCH", f"/channels/{ann['id']}", {"type": 5})
    req("PATCH", f"/guilds/{G}/welcome-screen", {"enabled": True, "description": DESC, "welcome_channels": [
        {"channel_id": rules["id"], "description": "Read the rules", "emoji_name": "📜"},
        {"channel_id": ann["id"], "description": "Releases and news", "emoji_name": "📣"},
        {"channel_id": helpf["id"], "description": "MineFeed help", "emoji_name": "📰"},
        {"channel_id": rc_help["id"], "description": "RetroConsole help", "emoji_name": "🕹️"},
        {"channel_id": general["id"], "description": "Say hi", "emoji_name": "👋"}]})

have = {r["name"] for r in (req("GET", f"/guilds/{G}/auto-moderation/rules") or [])}
alert = [{"type": 1}, {"type": 2, "metadata": {"channel_id": botlog["id"]}}]
if "Mention spam" not in have:
    req("POST", f"/guilds/{G}/auto-moderation/rules", {"name": "Mention spam", "event_type": 1, "trigger_type": 5,
        "trigger_metadata": {"mention_total_limit": 6, "mention_raid_protection_enabled": True}, "actions": alert, "enabled": True, "exempt_roles": [MOD]})
if "Suspected spam" not in have:
    req("POST", f"/guilds/{G}/auto-moderation/rules", {"name": "Suspected spam", "event_type": 1, "trigger_type": 3,
        "actions": alert, "enabled": True, "exempt_roles": [MOD]})
if "Invite links" not in have:
    req("POST", f"/guilds/{G}/auto-moderation/rules", {"name": "Invite links", "event_type": 1, "trigger_type": 1,
        "trigger_metadata": {"keyword_filter": ["discord.gg/*", "*discord.com/invite/*", "*discordapp.com/invite/*"]},
        "actions": alert, "enabled": True, "exempt_roles": [MOD]})

def post_once(ch, content):
    msgs = req("GET", f"/channels/{ch}/messages?limit=10") or []
    if any(m["author"].get("bot") and m["content"][:40] == content[:40] for m in msgs): return
    req("POST", f"/channels/{ch}/messages", {"content": content, "allowed_mentions": {"parse": []}, "flags": 4})
invite = req("POST", f"/channels/{welcome['id']}/invites", {"max_age": 0, "max_uses": 0, "unique": False})
log["invite"] = "https://discord.gg/" + invite["code"] if invite else None
post_once(welcome["id"], f"""## Welcome! 👋
Home of two NeoForge 1.21.1 mods by Gaigen:

📰 **MineFeed** — a social feed right inside Minecraft: posts, reactions, comments, notifications and private communities. Press **J** in game.
• Live feed: <#{FEED_POSTS}> · Help: <#{helpf['id']}> · Bugs: <#{bugs['id']}> · Ideas: <#{ideas['id']}>

🕹️ **RetroConsole** — play libretro emulators on in-world TV screens.
• Chat: <#{rc_chat['id']}> · Help: <#{rc_help['id']}> · Bugs: <#{rc_bugs['id']}> · Ideas: <#{rc_ideas['id']}>
• Download: <https://www.curseforge.com/minecraft/mc-mods/retroconsole> · <https://modrinth.com/mod/retroconsole> · Source: <https://github.com/Gaigen/retroconsole>

**Start here:** read <#{rules['id']}>, follow <#{ann['id']}> for releases, say hi in <#{general['id']}>. Русскоязычным — <#{ru['id']}>.
**Invite friends:** {log['invite']}""")
post_once(rules["id"], """## Rules
**1. Be respectful.** No harassment, hate speech, slurs or discrimination.
**2. No spam or ads.** No self-promotion, invite links or DM advertising without a moderator's OK.
**3. Keep it SFW.** No NSFW, gore or shocking content — in Discord and in MineFeed posts.
**4. Right channel, right language.** English in public channels; Russian is welcome in #ru-chat.
**5. No harmful stuff.** No cheats, malware, piracy links, leaks, account trading or anyone's personal info.
**6. Follow Discord's Terms and Community Guidelines.** <https://discord.com/guidelines>
**7. Moderators have the final say.** Problem? Ping a Moderator or use the in-game Report button in MineFeed.

The same rules apply to MineFeed posts and comments. Breaking them can get you muted, banned, or your MineFeed account blocked.""")
log.update(welcome=welcome["id"], rules=rules["id"], announcements=ann["id"], general=general["id"], ru_chat=ru["id"],
           mf_help=helpf["id"], mf_bugs=bugs["id"], mf_ideas=ideas["id"], rc_chat=rc_chat["id"], rc_help=rc_help["id"],
           rc_bugs=rc_bugs["id"], rc_ideas=rc_ideas["id"], reports=OLD_GENERAL, mod_chat=modchat["id"], bot_log=botlog["id"], live_feed=FEED_POSTS)
print(json.dumps(log, indent=1))
json.dump(log, open("/data/discord/guild_ids.json", "w"), indent=1)
