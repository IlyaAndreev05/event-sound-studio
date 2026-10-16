import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';

const OBJECT_PATH = '/org/ilya/EventSoundStudio';
const INTERFACE = 'org.ilya.EventSoundStudio';

export default class EventSoundStudioEventsExtension extends Extension {
    enable() {
        this._focusId = global.display.connect('notify::focus-window', () => this._emit('window_focus_changed'));
        this._workspaceId = global.workspace_manager.connect('active-workspace-changed', () => this._emit('workspace_changed'));
        this._windowSignals = new Map();
        this._createdId = global.display.connect('window-created', (_display, window) => {
            this._emit('window_opened');
            const id = window.connect('unmanaged', () => {
                this._emit('window_closed');
                this._windowSignals.delete(window);
            });
            this._windowSignals.set(window, id);
        });
    }

    _emit(name) {
        try {
            Gio.DBus.session.emit_signal(
                null, OBJECT_PATH, INTERFACE, 'Event',
                new GLib.Variant('(ss)', [name, 'gnome-shell']));
        } catch (error) {
            console.debug(`Event Sound Studio could not emit ${name}: ${error}`);
        }
    }

    disable() {
        if (this._focusId)
            global.display.disconnect(this._focusId);
        if (this._workspaceId)
            global.workspace_manager.disconnect(this._workspaceId);
        if (this._createdId)
            global.display.disconnect(this._createdId);
        for (const [window, id] of this._windowSignals ?? new Map()) {
            try { window.disconnect(id); } catch (_error) { /* window already closed */ }
        }
        this._windowSignals?.clear();
    }
}
