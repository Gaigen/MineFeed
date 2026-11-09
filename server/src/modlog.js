// Shared by feed.js and moderation.js: closing reports and the moderation log.

/** Closes open reports on a comment, on a post itself, or (wholePost) on a post and all its comments. */
export async function closeReports(env, { postId, commentId = null, wholePost = false }, status, action, by, now) {
  const set = "UPDATE reports SET status = ?, action = ?, resolved_by = ?, resolved_at = ?";
  if (wholePost) {
    await env.DB.prepare(`${set} WHERE post_id = ? AND status = 'open'`).bind(status, action, by, now, String(postId)).run();
  } else if (commentId) {
    await env.DB.prepare(`${set} WHERE comment_id = ? AND status = 'open'`).bind(status, action, by, now, String(commentId)).run();
  } else {
    await env.DB.prepare(`${set} WHERE post_id = ? AND comment_id IS NULL AND status = 'open'`).bind(status, action, by, now, String(postId)).run();
  }
}

export async function modLog(env, actorId, action, f, now) {
  await env.DB.prepare(
    "INSERT INTO mod_log (actor_id, action, target_id, post_id, comment_id, space, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
  ).bind(actorId, action, f.target ?? null, f.postId ?? null, f.commentId ?? null, f.space ?? null, f.reason ?? null, now).run();
}
