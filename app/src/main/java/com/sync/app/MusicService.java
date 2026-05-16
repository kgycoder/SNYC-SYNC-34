package com.sync.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicService extends Service {

    public static final String ACTION_PLAY   = "com.sync.app.PLAY";
    public static final String ACTION_PAUSE  = "com.sync.app.PAUSE";
    public static final String ACTION_NEXT   = "com.sync.app.NEXT";
    public static final String ACTION_PREV   = "com.sync.app.PREV";
    public static final String ACTION_STOP   = "com.sync.app.STOP";

    private static final String CHANNEL_ID = "sync_music_channel";
    private static final int    NOTIF_ID   = 1001;

    private MediaSessionCompat mediaSession;
    private NotificationManager notifManager;
    private final IBinder binder = new LocalBinder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 현재 트랙 상태
    private String currentTitle   = "";
    private String currentArtist  = "";
    private String currentThumbUrl = "";
    private boolean isPlaying     = false;
    private long    duration      = 0;
    private long    position      = 0;

    // MainActivity 콜백
    private ServiceCallback callback;

    public interface ServiceCallback {
        void onPlay();
        void onPause();
        void onNext();
        void onPrev();
    }

    public class LocalBinder extends Binder {
        MusicService getService() { return MusicService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        initMediaSession();
    }

    private void initMediaSession() {
        mediaSession = new MediaSessionCompat(this, "SYNCMediaSession");
        mediaSession.setFlags(
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        );

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay()     { if (callback != null) callback.onPlay();  setPlaying(true); }
            @Override public void onPause()    { if (callback != null) callback.onPause(); setPlaying(false); }
            @Override public void onSkipToNext()     { if (callback != null) callback.onNext(); }
            @Override public void onSkipToPrevious() { if (callback != null) callback.onPrev(); }
            @Override public void onStop() { stopSelf(); }
        });

        updatePlaybackState(PlaybackStateCompat.STATE_NONE, 0);
        mediaSession.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY:  if (callback != null) callback.onPlay();  setPlaying(true);  break;
                case ACTION_PAUSE: if (callback != null) callback.onPause(); setPlaying(false); break;
                case ACTION_NEXT:  if (callback != null) callback.onNext();  break;
                case ACTION_PREV:  if (callback != null) callback.onPrev();  break;
                case ACTION_STOP:  stopSelf(); return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    public void setCallback(ServiceCallback cb) { this.callback = cb; }

    public void updateTrack(String title, String artist, String thumbUrl,
                            boolean playing, long durationMs) {
        this.currentTitle    = title;
        this.currentArtist   = artist;
        this.currentThumbUrl = thumbUrl;
        this.isPlaying       = playing;
        this.duration        = durationMs;
        updateMediaMetadata();
        updatePlaybackState(
            playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
            position
        );
        // 썸네일 비동기 로드 후 알림 업데이트
        executor.submit(() -> {
            Bitmap bmp = loadBitmap(thumbUrl);
            updateNotification(bmp);
        });
    }

    public void setPlaying(boolean playing) {
        this.isPlaying = playing;
        updatePlaybackState(
            playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
            position
        );
        executor.submit(() -> {
            Bitmap bmp = loadBitmap(currentThumbUrl);
            updateNotification(bmp);
        });
    }

    public void updatePosition(long positionMs) {
        this.position = positionMs;
        updatePlaybackState(
            isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
            positionMs
        );
    }

    private void updateMediaMetadata() {
        MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,  currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
        mediaSession.setMetadata(meta.build());
    }

    private void updatePlaybackState(int state, long pos) {
        PlaybackStateCompat.Builder sb = new PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY |
                PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_PLAY_PAUSE |
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, pos, isPlaying ? 1f : 0f);
        mediaSession.setPlaybackState(sb.build());
    }

    private void updateNotification(Bitmap thumbnail) {
        Notification notif = buildNotification(thumbnail);
        notifManager.notify(NOTIF_ID, notif);
        // 포그라운드 서비스 시작 (아직 시작 안 됐으면)
        startForeground(NOTIF_ID, notif);
    }

    private Notification buildNotification(Bitmap thumbnail) {
        // 앱 열기 인텐트
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 미디어 컨트롤 인텐트들
        PendingIntent prevPi  = buildActionPi(ACTION_PREV,  100);
        PendingIntent playPi  = buildActionPi(isPlaying ? ACTION_PAUSE : ACTION_PLAY, 101);
        PendingIntent nextPi  = buildActionPi(ACTION_NEXT,  102);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle.isEmpty() ? "SYNC" : currentTitle)
            .setContentText(currentArtist.isEmpty() ? "음악 재생 중" : currentArtist)
            .setContentIntent(openPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "이전", prevPi)
            .addAction(
                isPlaying ? android.R.drawable.ic_media_pause
                          : android.R.drawable.ic_media_play,
                isPlaying ? "일시정지" : "재생",
                playPi
            )
            .addAction(android.R.drawable.ic_media_next, "다음", nextPi)
            .setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0, 1, 2)
            );

        if (thumbnail != null) builder.setLargeIcon(thumbnail);

        return builder.build();
    }

    private PendingIntent buildActionPi(String action, int reqCode) {
        Intent i = new Intent(this, MusicService.class);
        i.setAction(action);
        return PendingIntent.getService(
            this, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private Bitmap loadBitmap(String urlStr) {
        if (urlStr == null || urlStr.isEmpty()) return null;
        try {
            HttpURLConnection conn = (HttpURLConnection)
                new URL(urlStr).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setDoInput(true);
            conn.connect();
            InputStream in = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(in);
            in.close();
            return bmp;
        } catch (Exception e) { return null; }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "SYNC 음악 재생",
                NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("백그라운드 음악 재생");
            ch.setShowBadge(false);
            notifManager.createNotificationChannel(ch);
        }
    }

    public MediaSessionCompat.Token getSessionToken() {
        return mediaSession.getSessionToken();
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mediaSession.setActive(false);
        mediaSession.release();
        executor.shutdown();
        stopForeground(true);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 앱이 태스크에서 제거돼도 재생 중이면 서비스 유지
        if (!isPlaying) stopSelf();
    }
}
