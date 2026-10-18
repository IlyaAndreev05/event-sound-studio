/** Content-free fail-closed policy shared with regression tests. */
export function notificationPermitted(state) {
    return state.supported === true && state.banners === true &&
        state.locked === false && state.mapped === true && state.opacity > 0 &&
        state.sourceAvailable === true && state.sourceEnabled === true &&
        state.sourceBanners === true;
}
