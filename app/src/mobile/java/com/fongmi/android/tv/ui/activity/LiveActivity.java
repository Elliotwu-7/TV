package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityLiveBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.PassListener;
import com.fongmi.android.tv.model.LiveViewModel;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.adapter.ChannelAdapter;
import com.fongmi.android.tv.ui.adapter.EpgDataAdapter;
import com.fongmi.android.tv.ui.adapter.GroupAdapter;
import com.fongmi.android.tv.ui.custom.CustomKeyDown;
import com.fongmi.android.tv.ui.dialog.CastDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.InfoDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.PassDialog;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.playback.live.LivePlayRequest;
import com.fongmi.android.tv.playback.live.LivePlaybackController;
import com.fongmi.android.tv.playback.live.LivePlaybackHost;
import com.fongmi.android.tv.playback.live.LivePlaybackMedia;
import com.fongmi.android.tv.playback.PlaybackAction;
import com.fongmi.android.tv.playback.PlaybackOrientation;
import com.fongmi.android.tv.playback.PlaybackReset;
import com.fongmi.android.tv.utils.Biometric;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PiP;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LiveActivity extends PlaybackActivity implements CustomKeyDown.Listener, TrackDialog.Listener, Biometric.Callback, PassListener, ConfigListener, LiveListener, GroupAdapter.OnClickListener, ChannelAdapter.OnClickListener, EpgDataAdapter.OnClickListener, CastDialog.Listener, InfoDialog.Listener, LivePlaybackHost {

    private ActivityLiveBinding mBinding;
    private ChannelAdapter mChannelAdapter;
    private EpgDataAdapter mEpgDataAdapter;
    private LivePlaybackController mLive;
    private Observer<Result> mObserveUrl;
    private GroupAdapter mGroupAdapter;
    private Observer<Epg> mObserveEpg;
    private LiveViewModel mViewModel;
    private CustomKeyDown mKeyDown;
    private String mPlaybackKey;
    private List<Group> mHides;
    private Channel mChannel;
    private Group mGroup;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private boolean rotate;
    private int count;
    private PiP mPiP;
    private final Set<String> mFailedLiveSources = new LinkedHashSet<>();
    private final Runnable mClearFailedSources = mFailedLiveSources::clear;

    public static void start(Context context) {
        context.startActivity(new Intent(context, LiveActivity.class).putExtra("empty", LiveConfig.isEmpty()));
    }

    private boolean isEmpty() {
        return getIntent().getBooleanExtra("empty", true);
    }

    private Group getKeep() {
        return mGroupAdapter.get(0);
    }

    private Live getHome() {
        return LiveConfig.get().getHome();
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityLiveBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected String getPlaybackKey() {
        return mPlaybackKey;
    }

    @Override
    protected PlayerView getPlayerView() {
        return mBinding.player;
    }

    @Override
    protected PlayerSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
        PlaybackAction.setSpeedText(player(), mBinding.control.action.speed);
        checkLive();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Util.hideSystemUI(this);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mKeyDown = CustomKeyDown.create(this, mBinding.player);
        setSafeAreaPadding(mBinding.control.getRoot(), false);
        setSafeAreaPadding(mBinding.recycler, true);
        mObserveUrl = this::onUrlObserved;
        mObserveEpg = this::setEpg;
        mHides = new ArrayList<>();
        mR1 = this::hideControl;
        mR2 = this::setTraffic;
        mR3 = this::hideInfo;
        mPiP = new PiP();
        setRecyclerView();
        setVideoView();
        setViewModel();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        setActionClick(mBinding.control.back, view -> onBack());
        setActionClick(mBinding.control.cast, view -> onCast());
        setActionClick(mBinding.control.info, view -> onInfo());
        setActionClick(mBinding.control.play, view -> checkPlay());
        setActionClick(mBinding.control.next, view -> nextChannel());
        setActionClick(mBinding.control.prev, view -> prevChannel());
        setActionClick(mBinding.control.right.lock, view -> onLock());
        setActionClick(mBinding.control.right.rotate, view -> onRotate());
        setActionClick(mBinding.control.action.text, this::onTrack, view -> onTextLong());
        setActionClick(mBinding.control.action.audio, this::onTrack);
        setActionClick(mBinding.control.action.video, this::onTrack);
        setActionClick(mBinding.control.action.channelList, view -> {
            hideControl();
            showUI();
        });
        setActionClick(mBinding.control.action.more, view -> onMore());
        setActionClick(mBinding.control.action.home, view -> onHome());
        setActionClick(mBinding.control.action.line, view -> onLine());
        setActionClick(mBinding.control.action.scale, view -> onScale());
        setActionClick(mBinding.control.action.speed, view -> onSpeed());
        setActionClick(mBinding.control.action.config, view -> onConfig());
        setActionClick(mBinding.control.action.invert, view -> onInvert());
        setActionClick(mBinding.control.action.across, view -> onAcross());
        setActionClick(mBinding.control.action.change, view -> onChange());
        setActionClick(mBinding.control.action.player, view -> onChoose());
        setActionClick(mBinding.control.action.decode, view -> onDecode());
        mBinding.control.action.getRoot().setOnTouchListener(this::onActionTouch);
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setActionClick(View view, View.OnClickListener listener) {
        setActionClick(view, listener, null);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setActionClick(View view, View.OnClickListener listener, View.OnLongClickListener longListener) {
        view.setOnClickListener(listener);
        if (longListener != null) view.setOnLongClickListener(longListener);
        view.setOnTouchListener((target, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                target.setPressed(true);
                target.requestFocus();
                target.setTag(R.id.action_touch_long, Boolean.FALSE);
                if (longListener != null) target.postDelayed(() -> {
                    if (!target.isPressed()) return;
                    target.setTag(R.id.action_touch_long, target.performLongClick());
                }, ViewConfiguration.getLongPressTimeout());
            } else if (action == MotionEvent.ACTION_UP) {
                target.setPressed(false);
                boolean longPressed = Boolean.TRUE.equals(target.getTag(R.id.action_touch_long));
                target.setTag(R.id.action_touch_long, null);
                if (!longPressed) target.performClick();
                if (target != mBinding.control.action.more || !isVisible(mBinding.control.action.secondary)) setR1Callback();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                target.setPressed(false);
                target.setTag(R.id.action_touch_long, null);
            }
            return true;
        });
    }

    private void setRecyclerView() {
        mBinding.group.setItemAnimator(null);
        mBinding.channel.setItemAnimator(null);
        mBinding.epgData.setItemAnimator(null);
        mBinding.group.setAdapter(mGroupAdapter = new GroupAdapter(this));
        mBinding.channel.setAdapter(mChannelAdapter = new ChannelAdapter(this));
        mBinding.epgData.setAdapter(mEpgDataAdapter = new EpgDataAdapter(this));
    }

    private void setVideoView() {
        setScale(LiveSetting.getScale());
        PlayerEngineDialog.setText(mBinding.control.action.player);
        mBinding.control.action.invert.setSelected(LiveSetting.isInvert());
        mBinding.control.action.across.setSelected(LiveSetting.isAcross());
        mBinding.control.action.change.setSelected(LiveSetting.isChange());
        mBinding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> mPiP.update(this, view));
    }

    private void setPlaybackMode() {
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
    }

    private void setScale(int scale) {
        LiveSetting.putScale(scale);
        mBinding.player.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(LiveViewModel.class);
        mLive = mViewModel.createPlaybackController(this);
        observeForever(mViewModel.url(), mObserveUrl);
        mViewModel.xml().observe(this, this::setEpg);
        observeForever(mViewModel.epg(), mObserveEpg);
        mViewModel.live().observe(this, live -> {
            mViewModel.parseXml(live);
            setGroup(live);
            setWidth(live);
        });
    }

    private void onUrlObserved(Result result) {
        if (service() == null) return;
        mLive.onUrlResult(result);
    }

    private void checkLive() {
        if (isEmpty()) {
            LiveConfig.get().init().load(getCallback());
        } else {
            getLive();
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                getLive();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    private void getLive() {
        mBinding.control.action.home.setText(LiveConfig.isOnly() ? getString(R.string.live_refresh) : getString(R.string.live_source_format, getHome().getName()));
        mViewModel.parse(getHome());
        showProgress();
    }

    private void setGroup(Live live) {
        List<Group> items = new ArrayList<>();
        for (Group group : live.getGroups()) (group.isHidden() ? mHides : items).add(group);
        mGroupAdapter.addAll(items);
        setPosition(LiveConfig.get().findKeepPosition(items));
    }

    private void setWidth(Live live) {
        int padding = ResUtil.dp2px(48);
        if (live.getWidth() == 0) for (Group item : live.getGroups()) live.setWidth(Math.max(live.getWidth(), ResUtil.getTextWidth(item.getName(), 16)));
        int width = Math.min(Math.max(live.getWidth() + padding, ResUtil.dp2px(144)), ResUtil.getScreenWidth() / 4);
        setWidth(mBinding.group, width);
    }

    @Override
    public void setWidth(Group group) {
        int logo = ResUtil.dp2px(56);
        int padding = ResUtil.dp2px(60);
        if (group.isKeep()) group.setWidth(0);
        if (group.getWidth() == 0) for (Channel item : group.getChannel()) group.setWidth(Math.max(group.getWidth(), (item.getLogo().isEmpty() ? 0 : logo) + ResUtil.getTextWidth(item.getNumber() + item.getName(), 16)));
        int width = Math.min(Math.max(group.getWidth() + padding, ResUtil.dp2px(300)), ResUtil.getScreenWidth() / 2);
        setWidth(mBinding.channel, width);
    }

    private void setWidth(Epg epg) {
        int padding = ResUtil.dp2px(48);
        if (epg.getList().isEmpty()) return;
        int minWidth = ResUtil.getTextWidth(epg.getList().get(0).getTime(), 14);
        if (epg.getWidth() == 0) for (EpgData item : epg.getList()) epg.setWidth(Math.max(epg.getWidth(), ResUtil.getTextWidth(item.getTitle(), 16)));
        int maxWidth = ResUtil.getScreenWidth() / 2;
        int minContentWidth = Math.min(Math.max(minWidth + padding, ResUtil.dp2px(300)), maxWidth);
        int width = epg.getWidth() == 0 ? 0 : Math.clamp(epg.getWidth() + padding, minContentWidth, maxWidth);
        setWidth(mBinding.epgData, width);
    }

    private void setWidth(View view, int width) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.width == width) return;
        params.width = width;
        view.setLayoutParams(params);
    }

    private void setPosition(int[] position) {
        if (position[0] == -1) return;
        int size = mGroupAdapter.getItemCount();
        if (size == 1 || position[0] >= size) return;
        mGroup = mGroupAdapter.get(position[0]);
        mGroup.setPosition(position[1]);
        mLive.selectGroup(mGroup);
        mLive.selectChannel(mGroup.current());
    }

    private void setPosition() {
        if (mChannel == null) return;
        mGroup = mChannel.getGroup();
        int position = mGroupAdapter.indexOf(mGroup);
        boolean change = mGroupAdapter.getPosition() != position;
        if (change) mGroupAdapter.setSelected(position);
        if (change) mChannelAdapter.addAll(mGroup.getChannel());
        if (change) mChannelAdapter.setSelected(mGroup.getPosition());
        scrollToPosition(mBinding.channel, mGroup.getPosition());
        scrollToPosition(mBinding.group, position);
    }

    private void onBack() {
        onBackInvoked();
    }

    private void onCast() {
        CastDialog.create().video(new CastVideo(mBinding.control.title.getText().toString(), player().getUrl(), androidx.media3.common.C.TIME_UNSET, player().getHeaders())).fm(false).show(this);
    }

    private void onInfo() {
        InfoDialog.create().title(mBinding.control.title.getText()).headers(player().getHeaders()).url(player().getUrl()).show(this);
    }

    private void onLock() {
        setLock(!isLock());
        setRequestedOrientation(getLockOrient());
        mKeyDown.setLock(isLock());
        checkLockImg();
        showControl();
    }

    private void onRotate() {
        setR1Callback();
        setRotate(!isRotate());
        setRequestedOrientation(PlaybackOrientation.getRotateOrientation(this));
    }

    private void checkPlay() {
        if (player().isPlaying()) onPaused();
        else onPlay();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl();
    }

    private void onHome() {
        if (LiveConfig.isOnly()) setLive(getHome());
        else LiveDialog.show(this);
        hideControl();
    }

    private void onMore() {
        boolean show = !isVisible(mBinding.control.action.secondary);
        mBinding.control.action.secondary.setVisibility(show ? View.VISIBLE : View.GONE);
        mBinding.control.action.more.setSelected(show);
        if (show) {
            App.removeCallbacks(mR1);
            App.post(() -> mBinding.control.action.config.requestFocus(), 50);
        } else {
            setR1Callback();
            App.post(() -> mBinding.control.action.more.requestFocus(), 50);
        }
    }

    private void onLine() {
        nextLine(false);
    }

    private void onScale() {
        int index = LiveSetting.getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        if (mKeyDown.getScale() != 1.0f) mKeyDown.resetScale();
        else setScale(index == array.length - 1 ? 0 : ++index);
        setR1Callback();
    }

    private void onSpeed() {
        PlaybackAction.addSpeed(player(), mBinding.control.action.speed);
        setR1Callback();
    }

    private void onConfig() {
        HistoryDialog.create().live().readOnly().show(this);
        hideControl();
    }

    private void onInvert() {
        setR1Callback();
        LiveSetting.putInvert(!LiveSetting.isInvert());
        mBinding.control.action.invert.setSelected(LiveSetting.isInvert());
    }

    private void onAcross() {
        setR1Callback();
        LiveSetting.putAcross(!LiveSetting.isAcross());
        mBinding.control.action.across.setSelected(LiveSetting.isAcross());
    }

    private void onChange() {
        setR1Callback();
        LiveSetting.putChange(!LiveSetting.isChange());
        mBinding.control.action.change.setSelected(LiveSetting.isChange());
    }

    private void onDecode() {
        PlaybackAction.toggleDecode(player());
        setR1Callback();
    }

    private void onChoose() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player(), mBinding.control.title.getText());
        hideControl();
    }

    private boolean onTextLong() {
        if (!player().haveTrack(C.TRACK_TYPE_TEXT)) return false;
        onSubtitleClick();
        return true;
    }

    private boolean onActionTouch(View v, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) setR1Callback();
        return false;
    }

    private int getLockOrient() {
        return PlaybackOrientation.getLockOrientation(this, isLock(), isRotate());
    }

    private void hideUI() {
        if (isGone(mBinding.recycler)) return;
        mBinding.recycler.setVisibility(View.GONE);
        setPosition();
    }

    private void showUI() {
        if (isVisible(mBinding.recycler) || mGroupAdapter.getItemCount() == 0) return;
        mBinding.recycler.setVisibility(View.VISIBLE);
        mBinding.channel.requestFocus();
        setPosition();
        hideEpg();
    }

    private void showEpg(Channel item) {
        if (mChannel == null || mChannel.getData(mViewModel.getZoneId()).getList().isEmpty() || mEpgDataAdapter.getItemCount() == 0 || !mChannel.equals(item) || !mChannel.getGroup().equals(mGroup)) return;
        scrollToPosition(mBinding.epgData, item.getData(mViewModel.getZoneId()).getSelected());
        mBinding.epgData.setVisibility(View.VISIBLE);
        mBinding.channel.setVisibility(View.GONE);
        mBinding.group.setVisibility(View.GONE);
    }

    private void hideEpg() {
        mBinding.channel.setVisibility(View.VISIBLE);
        mBinding.group.setVisibility(View.VISIBLE);
        mBinding.epgData.setVisibility(View.GONE);
    }

    @Override
    public void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR2, 0);
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR2);
        Traffic.reset();
    }

    private void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.error.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.error.setText("");
    }

    private void showControl() {
        if (service() == null || isInPictureInPictureMode()) return;
        mBinding.control.action.secondary.setVisibility(View.GONE);
        mBinding.control.action.more.setSelected(false);
        mBinding.control.info.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.control.cast.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.control.right.rotate.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.center.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.bottom.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.back.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.top.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        App.post(() -> (isLock() ? mBinding.control.right.lock : mBinding.control.play).requestFocus(), 50);
        setR1Callback();
        hideInfo();
    }

    private void hideControl() {
        mBinding.control.action.secondary.setVisibility(View.GONE);
        mBinding.control.action.more.setSelected(false);
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR1);
    }

    private void showInfo() {
        mBinding.widget.infoPip.setVisibility(isInPictureInPictureMode() ? View.VISIBLE : View.GONE);
        mBinding.widget.info.setVisibility(isInPictureInPictureMode() ? View.GONE : View.VISIBLE);
        setR3Callback();
        hideControl();
        setInfo();
    }

    private void hideInfo() {
        mBinding.widget.infoPip.setVisibility(View.GONE);
        mBinding.widget.info.setVisibility(View.GONE);
        App.removeCallbacks(mR3);
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR2, 1000);
    }

    private void setR1Callback() {
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    private void setR3Callback() {
        App.post(mR3, Constant.INTERVAL_HIDE);
    }

    private void onToggle() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else if (isVisible(mBinding.recycler)) hideUI();
        else showControl();
        hideInfo();
    }

    private void resetPass() {
        this.count = 0;
    }

    private void setArtwork() {
        ImgUtil.load(this, mChannel.getLogo(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.player.setDefaultArtwork(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.player.setDefaultArtwork(errorDrawable);
            }
        });
    }

    @Override
    public void onItemClick(Group item) {
        mLive.selectGroup(item);
        scrollToPosition(mBinding.channel, Math.max(item.getPosition(), 0));
        if (!item.isKeep() || ++count < 5 || mHides.isEmpty()) return;
        if (Biometric.enable()) Biometric.show(this);
        else PassDialog.create().show(this);
        resetPass();
    }

    @Override
    public void onItemClick(Channel item) {
        clearFailedSources();
        if (!item.getData(mViewModel.getZoneId()).getList().isEmpty() && item.isSelected() && mChannel != null && mChannel.equals(item) && mChannel.getGroup().equals(mGroup)) {
            showEpg(item);
        } else if (mGroup != null) {
            mLive.selectChannel(item.group(mGroup));
        }
    }

    @Override
    public boolean onLongClick(Channel item) {
        if (mGroup.isHidden()) return false;
        boolean exist = Keep.exist(item.getName());
        Notify.show(exist ? R.string.keep_del : R.string.keep_add);
        if (exist) delKeep(item);
        else addKeep(item);
        return true;
    }

    @Override
    public void onItemClick(EpgData item) {
        mLive.selectEpg(item, player().getPosition());
    }

    private void addKeep(Channel item) {
        getKeep().add(item);
        Keep keep = new Keep();
        keep.setKey(item.getName());
        keep.setType(1);
        keep.save();
    }

    private void delKeep(Channel item) {
        if (mGroup.isKeep()) mChannelAdapter.remove(item);
        getKeep().getChannel().remove(item);
        Keep.delete(item.getName());
    }

    private void setInfo() {
        mViewModel.getEpg(mChannel);
        mBinding.widget.play.setText("");
        mBinding.widget.name.setMaxEms(48);
        mChannel.loadLogo(mBinding.widget.logo);
        mBinding.control.title.setSelected(true);
        mBinding.widget.line.setText(mChannel.getLine());
        mBinding.widget.name.setText(mChannel.getShow());
        mBinding.control.title.setText(mChannel.getShow());
        mBinding.widget.namePip.setText(mChannel.getShow());
        mBinding.widget.number.setText(mChannel.getNumber());
        mBinding.widget.numberPip.setText(mChannel.getNumber());
        mBinding.widget.line.setVisibility(mChannel.getLineVisible());
        mBinding.control.action.line.setText(mBinding.widget.line.getText());
        mBinding.control.action.line.setVisibility(mBinding.widget.line.getVisibility());
    }

    private void setEpg(Epg epg) {
        if (mChannel == null || !mChannel.getTvgId().equals(epg.getKey())) return;
        EpgData data = epg.getEpgData();
        boolean hasTitle = !data.getTitle().isEmpty();
        mEpgDataAdapter.addAll(epg.getList());
        if (hasTitle) mBinding.control.title.setText(getString(R.string.detail_title, mChannel.getShow(), data.getTitle()));
        mBinding.widget.name.setMaxEms(hasTitle ? 12 : 48);
        mBinding.widget.play.setText(data.format());
        setWidth(epg);
        setMetadata();
    }

    private void setEpg(boolean success) {
        if (mChannel != null && success)
            mViewModel.getEpg(mChannel);
    }

    private void start(Result result, long startPositionMs) {
        mPlaybackKey = result.getRealUrl();
        startPlayer(mPlaybackKey, result, false, getHome().getTimeout(), startPositionMs, buildMetadata());
    }

    private void stopPlayer() {
        player().clear();
        player().stop();
        player().clearMediaItems();
    }

    @Override
    public int getGroupCount() {
        return mGroupAdapter.getItemCount();
    }

    @Override
    public int getGroupPosition() {
        return mGroupAdapter.getPosition();
    }

    @Override
    public Group getGroup(int position) {
        return mGroupAdapter.get(position);
    }

    @Override
    public boolean isPlayerLive() {
        return player().isLive();
    }

    @Override
    public ZoneId getZoneId() {
        return mViewModel.getZoneId();
    }

    @Override
    public void requestUrl(LivePlayRequest request) {
        mViewModel.getUrl(request.getChannel(), request.getPosition());
    }

    @Override
    public void requestCatchupUrl(LivePlayRequest request) {
        mViewModel.getUrl(request.getChannel(), request.getCatchupData(), request.getPosition());
        hideUI();
    }

    @Override
    public void stopPlaybackForRefresh() {
        stopPlayer();
    }

    @Override
    public void startPlayback(Result result, long position, Channel channel) {
        start(result, position);
    }

    @Override
    public void resetPlaybackForError(String msg) {
        PlaybackReset.afterError(player());
        showError(msg);
    }

    @Override
    public boolean fallbackToNextLiveSource(Channel channel) {
        LiveConfig config = LiveConfig.get();
        List<Live> lives = config.getLives();
        if (channel == null || lives.size() <= 1) return false;
        Live current = config.getHome();
        mFailedLiveSources.add(current.getName());
        config.setKeep(channel);
        for (Live item : lives) {
            if (item.getName().equals(current.getName()) || mFailedLiveSources.contains(item.getName())) continue;
            item.keep(channel).save();
            setLive(item, true);
            return true;
        }
        return false;
    }

    @Override
    public void renderGroupSelection(Group group) {
        mGroupAdapter.setSelected(mGroup = group);
    }

    @Override
    public void renderGroupChannels(Group group) {
        mChannelAdapter.addAll(group.getChannel());
        mChannelAdapter.setSelected(group.getPosition());
    }

    @Override
    public void renderChannelSelection(Channel channel) {
        if (mGroup != null) mGroup.setPosition(mChannelAdapter.setSelected(channel.group(mGroup)));
        mChannel = channel;
        setArtwork();
        showInfo();
        hideUI();
    }

    @Override
    public void renderLineSelection(Channel channel, boolean show) {
        if (show) showInfo();
        else setInfo();
    }

    @Override
    public void renderEpgSelection(Channel channel, EpgData data) {
        mEpgDataAdapter.setSelected(data);
    }

    @Override
    public void showCatchupReady(Channel channel, EpgData data) {
        mBinding.control.title.setText(getString(R.string.detail_title, channel.getShow(), data.getTitle()));
        Notify.show(getString(R.string.play_ready, data.getTitle()));
    }

    private void checkControl() {
        if (isVisible(mBinding.control.getRoot())) showControl();
    }

    private void checkLockImg() {
        mBinding.control.right.lock.setImageResource(isLock() ? R.drawable.ic_control_lock_on : R.drawable.ic_control_lock_off);
    }

    private void resetAdapter() {
        mBinding.control.action.line.setVisibility(View.GONE);
        mBinding.control.title.setText("");
        mEpgDataAdapter.clear();
        mChannelAdapter.clear();
        mGroupAdapter.clear();
        mHides.clear();
        mChannel = null;
        mGroup = null;
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            nextChannel();
        }

        @Override
        public void onPrev() {
            prevChannel();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onAudio() {
            moveTaskToBack(true);
            setAudioOnly(true);
        }
    };

    @Override
    protected void onPrepare() {
        setPlaybackMode();
    }

    @Override
    protected void onDecodeChanged() {
        setPlaybackMode();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
    }

    @Override
    protected void onError(String msg) {
        App.removeCallbacks(mClearFailedSources);
        mLive.playbackError(msg);
    }

    @Override
    protected void onReclaim() {
        mLive.reclaim(player().getPosition());
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                App.removeCallbacks(mClearFailedSources);
                showProgress();
                break;
            case Player.STATE_READY:
                App.post(mClearFailedSources, 3000);
                hideProgress();
                checkControl();
                player().reset();
                break;
            case Player.STATE_ENDED:
                App.removeCallbacks(mClearFailedSources);
                mLive.playbackEnded();
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            mPiP.update(this, true);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_pause);
        } else if (isPaused()) {
            mPiP.update(this, false);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_play);
        }
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.player.getSubtitleView()).player(player()).show(this);
        hideControl();
    }

    @Override
    public void setConfig(Config config) {
        Config current = LiveConfig.get().getConfig();
        LiveConfig.load(config, getCallback(current));
    }

    private Callback getCallback(Config config) {
        return new Callback() {
            @Override
            public void start() {
                showProgress();
            }

            @Override
            public void success() {
                setLive(getHome());
            }

            @Override
            public void error(String msg) {
                LiveConfig.load(config, new Callback());
                Notify.show(msg);
                hideProgress();
            }
        };
    }

    @Override
    public void setLive(Live item) {
        setLive(item, false);
    }

    private void setLive(Live item, boolean automatic) {
        App.removeCallbacks(mClearFailedSources);
        if (!automatic) clearFailedSources();
        if (item.isSelected()) item.getGroups().clear();
        LiveConfig.get().setHome(item);
        player().reset();
        player().clear();
        player().stop();
        resetAdapter();
        hideControl();
        mViewModel.resetPlayback();
        mLive.reset();
        getLive();
    }

    @Override
    public void setPass(String pass) {
        unlock(pass);
    }

    @Override
    public void onBiometricSuccess() {
        unlock(null);
    }

    private void unlock(String pass) {
        boolean first = true;
        Iterator<Group> iterator = mHides.iterator();
        while (iterator.hasNext()) {
            Group item = iterator.next();
            if (pass != null && !pass.equals(item.getPass())) continue;
            mGroupAdapter.add(item);
            if (first) onItemClick(item);
            iterator.remove();
            first = false;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case LIVE -> setLive(getHome());
            case PLAYER -> mLive.refresh(player().getPosition());
        }
    }

    private void setTrackVisible() {
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video, mBinding.control.action.speed);
    }

    private MediaMetadata buildMetadata() {
        return LivePlaybackMedia.metadata(mChannel, mBinding.widget.play.getText());
    }

    private void setMetadata() {
        player().setMetadata(buildMetadata());
    }

    private void prevChannel() {
        clearFailedSources();
        mLive.prevChannel();
    }

    private void nextChannel() {
        clearFailedSources();
        mLive.nextChannel();
    }

    private void nextLine(boolean show) {
        if (!show) clearFailedSources();
        mLive.nextLine(show);
    }

    private void clearFailedSources() {
        App.removeCallbacks(mClearFailedSources);
        mFailedLiveSources.clear();
    }

    private void onPaused() {
        controller().pause();
    }

    private void onPlay() {
        controller().play();
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
        if (rotate) {
            noPadding(mBinding.recycler);
            noPadding(mBinding.control.getRoot());
        } else {
            setSafeAreaPadding(mBinding.recycler, true);
            setSafeAreaPadding(mBinding.control.getRoot(), false);
        }
    }

    private void setSafeAreaPadding(View view, boolean leftOnly) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (target, insets) -> {
            Insets safe = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            target.setPadding(safe.left, safe.top, leftOnly ? 0 : safe.right, safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    private void scrollToPosition(RecyclerView view, int position) {
        view.post(() -> view.scrollToPosition(position));
    }

    @Override
    public void onCasted() {
        player().stop();
    }

    @Override
    public void onSpeedUp() {
        if (player().isLive()) return;
        if (!player().isPlaying()) return;
        mBinding.widget.speed.setVisibility(View.VISIBLE);
        mBinding.widget.speed.startAnimation(ResUtil.getAnim(R.anim.forward));
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, PlayerSetting.getSpeed());
    }

    @Override
    public void onSpeedEnd() {
        mBinding.widget.speed.clearAnimation();
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, 1.0f);
    }

    @Override
    public void onBright(int progress) {
        mBinding.widget.bright.setVisibility(View.VISIBLE);
        mBinding.widget.brightProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_low);
        else if (progress < 70) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_medium);
        else mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_high);
    }

    @Override
    public void onVolume(int progress) {
        mBinding.widget.volume.setVisibility(View.VISIBLE);
        mBinding.widget.volumeProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_low);
        else if (progress < 70) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_medium);
        else mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_high);
    }

    @Override
    public void onFlingUp() {
        if (LiveSetting.isInvert()) nextChannel();
        else prevChannel();
    }

    @Override
    public void onFlingDown() {
        if (LiveSetting.isInvert()) prevChannel();
        else nextChannel();
    }

    @Override
    public void onSeeking(long time) {
        if (player().isLive()) return;
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        mBinding.widget.time.setText(player().getPositionTime(time));
        mBinding.widget.seek.setVisibility(View.VISIBLE);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (player().isLive()) return;
        seekTo(time);
    }

    @Override
    public void onSingleTap() {
        onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isVisible(mBinding.recycler)) hideUI();
        if (isVisible(mBinding.control.getRoot())) hideControl();
        checkPlay();
    }

    @Override
    public void onTouchEnd() {
        mBinding.widget.seek.setVisibility(View.GONE);
        mBinding.widget.speed.setVisibility(View.GONE);
        mBinding.widget.bright.setVisibility(View.GONE);
        mBinding.widget.volume.setVisibility(View.GONE);
    }

    @Override
    public void onShare(CharSequence title) {
        PlayerHelper.share(this, player().getUrl(), player().getHeaders(), title);
        setRedirect(true);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (isRedirect()) return;
        if (isLock()) App.post(this::onLock, 500);
        if (service() != null && player().haveTrack(C.TRACK_TYPE_VIDEO)) mPiP.enter(this, player().getVideoWidth(), player().getVideoHeight(), LiveSetting.getScale());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (isInPictureInPictureMode) {
            hideControl();
            hideInfo();
            hideUI();
        } else {
            hideInfo();
            if (isStop()) finish();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Util.hideSystemUI(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        setAudioOnly(false);
        setStop(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isAudioOnly()) setStop(true);
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.action.secondary)) {
            onMore();
        } else if (isVisible(mBinding.widget.info) || isVisible(mBinding.widget.infoPip)) {
            hideInfo();
            showControl();
        } else if (isVisible(mBinding.recycler)) {
            hideUI();
        } else if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (!isLock()) {
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        Source.get().exit();
        App.removeCallbacks(mClearFailedSources, mR1, mR2, mR3);
        super.onDestroy();
    }
}
