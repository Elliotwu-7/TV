package com.fongmi.android.tv.player.engine;

import androidx.media3.common.Player;

import com.fongmi.android.tv.player.exo.ExoPlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;

/** Z10 uses Android x86_64 MediaCodec decoders through ExoPlayer. */
public final class PlayerEngineFactory {

    public static PlayerEngine create(int decode, Player.Listener listener) {
        return new ExoPlayerEngine(decode, listener);
    }

    public static PlayerEngine create(int decode, PlaySpec spec, Player.Listener listener) {
        return create(decode, listener);
    }

    public static boolean matches(PlayerEngine engine, PlaySpec spec) {
        return engine != null && engine.getType() == PlayerEngine.Type.EXO;
    }
}
