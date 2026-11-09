// Decides which gateway events matter and adds the thread context the Worker needs.
// `channels` is a lookup (id -> {type, parentId, name, appliedTags}) backed by the discord.js cache.
const FORUM = 15;
const THREAD_TYPES = new Set([10, 11, 12]);

export function makeFilter({ guildId, categories, forums = [], channels }) {
  const cats = new Set(categories);
  const explicitForums = new Set(forums);
  // configured categories, plus the extra "MineFeed communities N" ones the Worker creates when one fills up
  const isFeedCategory = (id) => {
    if (cats.has(id)) return true;
    const c = id && channels(id);
    return !!(c && /^MineFeed communities/i.test(c.name || ""));
  };
  const isFeedForum = (id) => {
    const f = id && channels(id);
    return !!(f && f.type === FORUM && (explicitForums.has(id) || isFeedCategory(f.parentId)));
  };
  // Unknown channels (old archived threads not in the cache) are forwarded too: the Worker
  // ignores anything that is not a known post, and that costs nothing.
  const feedThread = (id) => {
    if (!id) return null;
    const t = channels(id);
    if (!t) return {};
    return THREAD_TYPES.has(t.type) && isFeedForum(t.parentId) ? t : null;
  };

  return function filter(t, d) {
    if (!d || (d.guild_id && d.guild_id !== guildId)) return null;
    switch (t) {
      case "MESSAGE_CREATE": {
        const th = feedThread(d.channel_id);
        if (!th) return null;
        const out = {
          id: d.id, channel_id: d.channel_id, content: d.content, webhook_id: d.webhook_id,
          author: d.author && { id: d.author.id, username: d.author.username, global_name: d.author.global_name, avatar: d.author.avatar || null, bot: !!d.author.bot },
          member: d.member && { nick: d.member.nick },
          mentions: (d.mentions || []).map((u) => ({ id: u.id, username: u.username, global_name: u.global_name })),
          attachments: (d.attachments || []).map((a) => ({ url: a.url, content_type: a.content_type, width: a.width, height: a.height })),
        };
        if (d.id === d.channel_id && th.parentId) Object.assign(out, { parent_id: th.parentId, thread_name: th.name, applied_tags: th.appliedTags || [] });
        return out;
      }
      case "MESSAGE_UPDATE":
        if (!feedThread(d.channel_id) || d.content === undefined) return null;
        return { id: d.id, channel_id: d.channel_id, content: d.content, webhook_id: d.webhook_id, mentions: (d.mentions || []).map((u) => ({ id: u.id, username: u.username, global_name: u.global_name })) };
      case "MESSAGE_DELETE":
        // the message itself is gone from the cache; the channel usually is not
        return feedThread(d.channel_id) ? { id: d.id, channel_id: d.channel_id } : null;
      case "MESSAGE_DELETE_BULK":
        return feedThread(d.channel_id) ? { ids: d.ids, channel_id: d.channel_id } : null;
      case "THREAD_DELETE":
        return isFeedForum(d.parent_id) ? { id: d.id, parent_id: d.parent_id } : null;
      case "THREAD_UPDATE":
        return isFeedForum(d.parent_id) ? { id: d.id, parent_id: d.parent_id, applied_tags: d.applied_tags || [] } : null;
      case "GUILD_MEMBER_UPDATE":
        return null; // handled by the caller (needs the moderator role id)
      default:
        return null;
    }
  };
}
