const assert = require('assert');
const zcm = require('zerocm');
const types = require('zcmtypes');
const z = zcm.create(types, 'block-inproc');
const msg = new types.example_t();
msg.utime = 42;

const watchdog = setTimeout(() => {
  console.error('Channel subscription or unsubscribe did not complete');
  process.exit(1);
}, 5000);

async function roundTrip(pattern, channel) {
  let sub;
  await new Promise((resolve, reject) => {
    z.subscribe(pattern, types.example_t, (actual, received) => {
      try {
        assert.strictEqual(actual, channel);
        assert.strictEqual(received.utime.toString(), '42');
        resolve();
      } catch (err) {
        reject(err);
      }
    }, subscription => {
      sub = subscription;
      assert.strictEqual(z.publish(channel, msg), zcm.ZCM_EOK);
    });
  });
  await new Promise(resolve => z.unsubscribe(sub, resolve));
}

(async () => {
  for (const channel of ['a'.repeat(72), 'é'.repeat(36)]) {
    await roundTrip(channel, channel);
  }
  const pattern = '(' + 'a'.repeat(72) + '|event)';
  await roundTrip(pattern, 'event');
  assert.throws(() => z.publish(pattern, msg), /too long/);
  for (const channel of ['a'.repeat(73), 'é'.repeat(37)]) {
    assert.throws(() => z.publish(channel, msg), /too long/);
    assert.throws(() => z.subscribe(channel, null, () => {}, () => {}), /too long/);
  }
  await new Promise(resolve => z.stop(resolve));
  z.destroy();
  clearTimeout(watchdog);
  console.log('Node channel length and regex tests passed');
})().catch(err => {
  console.error(err);
  process.exit(1);
});
