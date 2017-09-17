package com.me.audioprocesser.play;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.text.TextUtils;
import android.util.Log;

import com.me.audioprocesser.base.DispatchQueue;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * AudioPlayer process audio play
 *
 * Created by zhuqian on 17/9/16.
 */

public class AudioPlayer {
    public static final String TAG = "AudioPlayer";
    private native int openOpusFile(String path);
    private native int seekOpusFile(float position);
    private native int isOpusFile(String path);
    private native void closeOpusFile();
    private native void readOpusFile(ByteBuffer buffer, int capacity, int[] args);

    static {
        System.loadLibrary("opus");
    }
    private AudioTrack audioTrackPlayer;
    private Activity activity;

    /************************/
    private int playerBufferSize;
    private final Object playerSync = new Object();
    private final Object playerObjectSync = new Object();
    private DispatchQueue fileDecodingQueue;
    private DispatchQueue playerQueue;
    private ArrayList<AudioBuffer> usedPlayerBuffers = new ArrayList<>();
    private ArrayList<AudioBuffer> freePlayerBuffers = new ArrayList<>();
    public static int[] readArgs = new int[3];
    private boolean decodingFinished = false;
    private int buffersWrited;
    /***********************/
    private OnAudioPlayListener onAudioPlayListener;

    public void setOnAudioPlayListener(OnAudioPlayListener onAudioPlayListener) {
        this.onAudioPlayListener = onAudioPlayListener;
    }

    public AudioPlayer(Activity activity) {
        this.activity = activity;
        playerBufferSize = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (playerBufferSize <= 0) {
            playerBufferSize = 3840;
        }
        for (int i = 0; i < 3; i++) {
            freePlayerBuffers.add(new AudioBuffer(playerBufferSize));
        }
        fileDecodingQueue = new DispatchQueue("FileDecoding-Thread");
        fileDecodingQueue.setPriority(Thread.MAX_PRIORITY);
        playerQueue = new DispatchQueue("Player-Thread");
        playerQueue.setPriority(Thread.MAX_PRIORITY);
    }

    public void play(String filepath) {
        if (TextUtils.isEmpty(filepath) || isOpusFile(filepath) != 1 || openOpusFile(filepath) != 1){
            Log.d(TAG, "play audio ["+filepath+"] error.");
            return;
        }
        synchronized (playerObjectSync) {
            try {
                audioTrackPlayer = new AudioTrack(AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, playerBufferSize, AudioTrack.MODE_STREAM);
                audioTrackPlayer.setStereoVolume(1.0f, 1.0f);
                audioTrackPlayer.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                    @Override
                    public void onMarkerReached(AudioTrack audioTrack) {
                        cleanupPlayer();
                    }

                    @Override
                    public void onPeriodicNotification(AudioTrack audioTrack) {

                    }
                });
                audioTrackPlayer.play();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        fileDecodingQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                synchronized (playerSync) {
                    freePlayerBuffers.addAll(usedPlayerBuffers);
                    usedPlayerBuffers.clear();
                }
                decodingFinished = false;
                checkPlayerQueue();
            }
        });
    }

    private void cleanupPlayer() {
        Log.d(TAG, "cleanupPlayer.");
        synchronized (playerObjectSync) {
            if (audioTrackPlayer != null) {
                try {
                    audioTrackPlayer.pause();
                    audioTrackPlayer.flush();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    audioTrackPlayer.release();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                audioTrackPlayer = null;
            }
        }
        if (onAudioPlayListener != null) {
            onAudioPlayListener.onFinish();
        }
        buffersWrited = 0;
    }

    private void checkDecoderQueue() {
        fileDecodingQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                if (decodingFinished) {
                    checkPlayerQueue();
                    return;
                }
                boolean was = false;
                while (true) {
                    AudioBuffer buffer = null;
                    synchronized (playerSync) {
                        if (!freePlayerBuffers.isEmpty()) {
                            buffer = freePlayerBuffers.get(0);
                            freePlayerBuffers.remove(0);
                        }
                        if (!usedPlayerBuffers.isEmpty()) {
                            was = true;
                        }
                    }
                    if (buffer != null) {
                        readOpusFile(buffer.buffer, playerBufferSize, readArgs);
                        buffer.size = readArgs[0];
                        buffer.pcmOffset = readArgs[1];
                        buffer.finished = readArgs[2];
                        if (buffer.finished == 1) {
                            decodingFinished = true;
                        }
                        if (buffer.size != 0) {
                            buffer.buffer.rewind();
                            buffer.buffer.get(buffer.bufferBytes);
                            synchronized (playerSync) {
                                usedPlayerBuffers.add(buffer);
                            }
                        } else {
                            synchronized (playerSync) {
                                freePlayerBuffers.add(buffer);
                                break;
                            }
                        }
                        was = true;
                    } else {
                        break;
                    }
                }
                if (was) {
                    checkPlayerQueue();
                }
            }
        });
    }

    private void checkPlayerQueue() {
        playerQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                synchronized (playerObjectSync) {
                    if (audioTrackPlayer == null || audioTrackPlayer.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                        return;
                    }
                }
                AudioBuffer buffer = null;
                synchronized (playerSync) {
                    if (!usedPlayerBuffers.isEmpty()) {
                        buffer = usedPlayerBuffers.get(0);
                        usedPlayerBuffers.remove(0);
                    }
                }

                if (buffer != null) {
                    int count = 0;
                    try {
                        Log.d(TAG, "play audio size["+buffer.size+"].");
                        count = audioTrackPlayer.write(buffer.bufferBytes, 0, buffer.size);
                        Log.d(TAG, "play end one frame.");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    buffersWrited++;

                    if (count > 0) {
                        final long pcm = buffer.pcmOffset;
                        final int marker = buffer.finished == 1 ? count : -1;
                        final int finalBuffersWrited = buffersWrited;
                        activity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (marker != -1) {
                                    Log.d(TAG, "play end.");
                                    if (audioTrackPlayer != null) {
                                        audioTrackPlayer.setNotificationMarkerPosition(1);
                                    }
                                    if (finalBuffersWrited == 1) {
                                        cleanupPlayer();
                                    }
                                }
                            }
                        });
                    }


                    if (buffer.finished != 1) {
                        checkPlayerQueue();
                    }
                }
                if (buffer == null || buffer.finished != 1) {
                    checkDecoderQueue();
                }

                if (buffer != null) {
                    synchronized (playerSync) {
                        freePlayerBuffers.add(buffer);
                    }
                }
            }
        });
    }

    public interface OnAudioPlayListener{
        void onFinish();
    }

    private class AudioBuffer {
        public AudioBuffer(int capacity) {
            buffer = ByteBuffer.allocateDirect(capacity);
            bufferBytes = new byte[capacity];
        }

        ByteBuffer buffer;
        byte[] bufferBytes;
        int size;
        int finished;
        long pcmOffset;
    }
}
