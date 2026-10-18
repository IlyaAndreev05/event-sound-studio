import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as Config from 'resource:///org/gnome/shell/misc/config.js';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import {notificationPermitted} from './notificationPolicy.js';

const OBJECT_PATH = '/org/ilya/EventSoundStudio';
const INTERFACE = 'org.ilya.EventSoundStudio';

export default class EventSoundStudioEventsExtension extends Extension {
    enable() {
        this._signals = [];
        this._workspace = global.workspace_manager.get_active_workspace();
        this._connect(global.display, 'notify::focus-window', () => this._emit('window_focus_changed'));
        this._connect(global.workspace_manager, 'active-workspace-changed', () => {
            const workspace = global.workspace_manager.get_active_workspace();
            if (workspace !== this._workspace) {
                this._workspace = workspace;
                this._emit('workspace_changed');
            }
        });
        this._windowSignals = new Map();
        this._connect(global.display, 'window-created', (_display, window) => {
            this._emit('window_opened');
            const id = window.connect('unmanaged', () => {
                this._emit('window_closed');
                this._windowSignals.delete(window);
            });
            this._windowSignals.set(window, id);
        });
        this._settings = new Gio.Settings({schema_id: 'org.gnome.desktop.notifications'});
        this._seen = new WeakSet();
        // GNOME 50 has no public banner-shown signal. This version-gated adapter
        // observes the actual rendered banner actor, never notification-added.
        const tray = Main.messageTray;
        this._notificationSupported = Config.PACKAGE_VERSION.split('.')[0] === '50' &&
            Boolean(tray?._bannerBin?.connect);
        if (this._notificationSupported) {
            for (const signal of ['notify::opacity', 'notify::mapped'])
                this._connect(tray._bannerBin, signal, () => this._visibleNotification());
        }
        this._heartbeat = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, 5, () => {
            this._publishStatus();
            return GLib.SOURCE_CONTINUE;
        });
        this._publishStatus();
    }

    _connect(object, signal, callback) {
        this._signals.push([object, object.connect(signal, callback)]);
    }

    _visibleNotification() {
        const tray = Main.messageTray;
        const notification = tray._notification;
        const bin = tray._bannerBin;
        const policy = notification?.source?.policy;
        if (!notificationPermitted({
            supported: this._notificationSupported,
            banners: this._settings.get_boolean('show-banners'),
            locked: Main.sessionMode.isLocked,
            mapped: bin.mapped && tray.mapped,
            opacity: bin.opacity,
            sourceAvailable: Boolean(policy),
            sourceEnabled: policy?.enable === true,
            sourceBanners: policy?.showBanners === true,
        }) || this._seen.has(notification))
            return;
        this._seen.add(notification);
        this._emit('notification_visible');
    }

    _publishStatus() {
        const notification = !this._notificationSupported ? 'unsupported' :
            !this._settings.get_boolean('show-banners') ? 'dnd' :
                Main.sessionMode.isLocked ? 'locked' : 'available';
        this._signal('Status', `workspace=available;notification=${notification}`);
    }

    _emit(name) { this._signal('Event', name); }

    _signal(member, name) {
        try {
            Gio.DBus.session.emit_signal(null, OBJECT_PATH, INTERFACE, member,
                new GLib.Variant('(ss)', [name, 'gnome-shell']));
        } catch (_error) { /* No content or activity logging. */ }
    }

    disable() {
        if (this._heartbeat)
            GLib.source_remove(this._heartbeat);
        for (const [object, id] of this._signals ?? []) {
            try { object.disconnect(id); } catch (_error) { /* actor destroyed */ }
        }
        for (const [window, id] of this._windowSignals ?? []) {
            try { window.disconnect(id); } catch (_error) { /* window closed */ }
        }
        this._signals = [];
        this._windowSignals?.clear();
        this._settings = null;
        this._seen = null;
    }
}
