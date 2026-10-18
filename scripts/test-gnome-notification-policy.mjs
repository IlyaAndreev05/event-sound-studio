import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

// Load the extension's exact pure module without requiring Node package module settings.
const source = await readFile(new URL('../gnome/event-sound-studio-events/notificationPolicy.js', import.meta.url), 'utf8');
const {notificationPermitted} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const visible = {supported: true, banners: true, locked: false, mapped: true, opacity: 255,
    sourceAvailable: true, sourceEnabled: true, sourceBanners: true};
assert.equal(notificationPermitted(visible), true);
for (const field of ['supported', 'banners', 'mapped', 'sourceAvailable', 'sourceEnabled', 'sourceBanners']) {
    assert.equal(notificationPermitted({...visible, [field]: false}), false, field);
    const missing = {...visible};
    delete missing[field];
    assert.equal(notificationPermitted(missing), false, `missing ${field}`);
}
assert.equal(notificationPermitted({...visible, locked: true}), false, 'lock screen');
assert.equal(notificationPermitted({...visible, opacity: 0}), false, 'unpainted banner');
assert.equal(notificationPermitted({...visible, banners: false, urgency: 'critical'}), false, 'DND never overridden');
console.log('Visible notification suppression policy regressions passed');
