package com.fongmi.android.tv.playback.live;

import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.setting.LiveSetting;

class LiveFallbackPolicy {

    private final LivePlaybackController controller;
    private final LivePlaybackState state;
    private final LivePlaybackHost host;

    LiveFallbackPolicy(LivePlaybackController controller, LivePlaybackState state, LivePlaybackHost host) {
        this.controller = controller;
        this.state = state;
        this.host = host;
    }

    boolean canAutoFallback() {
        return LiveSetting.isChange() && state.getChannel() != null;
    }

    boolean playbackError() {
        Channel channel = state.getChannel();
        if (!canAutoFallback()) return false;
        if (!channel.isOnly() && !channel.isLast()) {
            controller.nextLine(true);
            return true;
        }
        return controller.fallbackToNextLiveSource();
    }

    void playbackEnded() {
        if (host.isPlayerLive()) checkNext();
        else controller.nextChannel();
    }

    private void checkNext() {
        Channel channel = state.getChannel();
        if (channel == null) return;
        EpgData data = host.getNextEpgData(channel);
        if (data != null && controller.selectEpg(data)) return;
        data = host.getCurrentEpgData(channel);
        if (data != null) host.renderEpgSelection(channel, data);
        controller.refresh();
    }
}
