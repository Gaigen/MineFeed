// Preserve the original text, timestamp, and unrelated components when updating a post.
export function releaseEditPayload(message, desired) {
  const original = message.embeds[0].toJSON(), update = desired.embeds[0];
  original.fields = (original.fields || []).filter(f => f.name !== 'Minecraft' && f.name !== 'Loaders').concat(update.fields);
  if (update.url) original.url = update.url;
  const components = message.components.map(row => row.toJSON());
  for (const button of desired.components[0]?.components || []) {
    const existing = components.flatMap(row => row.components || []).find(b => b.label === button.label && b.style === 5);
    if (existing) existing.url = button.url;
    else {
      let row = components.find(r => r.type === 1 && r.components.length < 5);
      if (!row && components.length < 5) { row = { type: 1, components: [] }; components.push(row); }
      if (row) row.components.push(button);
    }
  }
  return { embeds: [original, ...message.embeds.slice(1).map(e => e.toJSON())], components, allowedMentions: { parse: [] } };
}
