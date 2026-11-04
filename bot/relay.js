// Batches events and POSTs them to the Worker. Retries with backoff; never drops on a Worker outage
// (up to MAX_QUEUE events), and the Worker side is idempotent, so resending is safe.
export const MAX_BATCH = 100;
export const MAX_QUEUE = 5000;

export class Relay {
  constructor({ url, secret, fetchImpl = fetch, log = console, flushMs = 1500 }) {
    this.url = url.replace(/\/+$/, "") + "/v2/bot/events";
    this.secret = secret;
    this.fetch = fetchImpl;
    this.log = log;
    this.flushMs = flushMs;
    this.queue = [];
    this.backoff = 0;
    this.timer = null;
    this.sending = false;
    this.dropped = 0;
  }

  push(t, d) {
    if (this.queue.length >= MAX_QUEUE) {
      this.queue.shift();
      this.dropped++;
    }
    this.queue.push({ t, d });
    this.schedule(this.flushMs);
  }

  schedule(ms) {
    if (this.timer || this.sending) return;
    this.timer = setTimeout(() => { this.timer = null; this.flush(); }, ms);
  }

  async flush() {
    if (this.sending || !this.queue.length) return;
    this.sending = true;
    const batch = this.queue.slice(0, MAX_BATCH);
    let ok = false;
    try {
      const res = await this.fetch(this.url, {
        method: "POST",
        headers: { "content-type": "application/json", authorization: `Bearer ${this.secret}` },
        body: JSON.stringify({ events: batch }),
      });
      ok = res.ok;
      if (!ok) this.log.warn(`relay: worker answered ${res.status}`);
      if (res.status === 401) this.log.error("relay: BOT_SECRET does not match the Worker secret");
    } catch (e) {
      this.log.warn(`relay: ${e.message}`);
    }
    this.sending = false;
    if (ok) {
      this.queue.splice(0, batch.length);
      this.backoff = 0;
      if (this.queue.length) this.schedule(50);
    } else {
      this.backoff = Math.min(60_000, this.backoff ? this.backoff * 2 : 2000);
      this.schedule(this.backoff);
    }
    return ok;
  }
}
