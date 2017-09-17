package com.me.audioprocesser.record;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.text.TextUtils;
import android.util.Log;

import com.me.audioprocesser.base.DispatchQueue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * AudioRecorder process audio record
 *
 * Created by zhuqian on 17/9/16.
 */

public class AudioRecorder {
    public static final String TAG = "AudioRecord";

    static {
        System.loadLibrary("opus");
    }
    private native int startRecord(String path);
    private native int stopRecord();
    private native int writeFrame(ByteBuffer frame, int len);

    public static final int RECORD_STATUS_IDEL = 0;
    public static final int RECORD_STATUS_ING = 1;
    public static final int RECORD_STATUS_FINISH = 2;
    private int mRecordStatus;

    /*******************/
    private AudioRecord audioRecorder;
    private int recordBufferSize;
    private ByteBuffer fileBuffer;
    private DispatchQueue recordQueue;
    private DispatchQueue encodeQueue;
    public static final int SAMPLE_RATE = 16000;
    public static final int FILE_BUFFER_SIZE = 1920;
    /*******************/
    private List<ByteBuffer> recordBuffers = new ArrayList<>();

    public AudioRecorder() {
        recordBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (recordBufferSize <= 0) {
            recordBufferSize = 1280;
        }
        Log.d(TAG, "recordBufferSize ["+recordBufferSize+"]");
        fileBuffer = ByteBuffer.allocateDirect(FILE_BUFFER_SIZE);
        recordQueue = new DispatchQueue("AudioRecord-Thread");
        recordQueue.setPriority(Thread.MAX_PRIORITY);
        encodeQueue = new DispatchQueue("AudioEncode-Thread");
        encodeQueue.setPriority(Thread.MAX_PRIORITY);
    }

    public boolean startRecording(String recordPath) {
        if (RECORD_STATUS_IDEL != mRecordStatus || TextUtils.isEmpty(recordPath)
                || startRecord(recordPath) == 0) {
            return false;
        }
        try {
            audioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recordBufferSize * 10);
            audioRecorder.startRecording();
            recordQueue.postRunnable(recordRunnable);
            mRecordStatus = RECORD_STATUS_ING;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return true;
    }

    public void stopRecording() {
        if (RECORD_STATUS_FINISH == mRecordStatus) {
            return;
        }
        mRecordStatus = RECORD_STATUS_FINISH;
        release();
        stopRecord();
    }

    private void release() {
        if (audioRecorder != null) {
            audioRecorder.release();
            audioRecorder = null;
        }
        mRecordStatus = RECORD_STATUS_IDEL;
    }

    private void encodFrame(final ByteBuffer readBuffer, boolean flush) {
        while (readBuffer.hasRemaining()) {
            int oldLimit = -1;
            if (readBuffer.remaining() > fileBuffer.remaining()) {
                oldLimit = readBuffer.limit();
                readBuffer.limit(fileBuffer.remaining() + readBuffer.position());
            }
            fileBuffer.put(readBuffer);
            if (fileBuffer.position() == fileBuffer.limit() || flush) {
                if (writeFrame(fileBuffer, !flush ? fileBuffer.limit() : readBuffer.position()) != 0) {
                    fileBuffer.rewind();
                }
                fileBuffer.rewind();
            }
            if (oldLimit != -1) {
                readBuffer.limit(oldLimit);
            }
        }
        recordQueue.postRunnable(new Runnable() {
            @Override
            public void run() {
                recordBuffers.add(readBuffer);
            }
        });
    }

    private Runnable recordRunnable = new Runnable() {
        @Override
        public void run() {
            if (audioRecorder != null) {
                ByteBuffer readBuffer;
                if (!recordBuffers.isEmpty()) {
                    readBuffer = recordBuffers.get(0);
                    recordBuffers.remove(0);
                } else {
                    readBuffer = ByteBuffer.allocateDirect(recordBufferSize);
                    readBuffer.order(ByteOrder.nativeOrder());
                }
                readBuffer.rewind();
                int len = audioRecorder.read(readBuffer, readBuffer.capacity());
                final boolean flush = len != readBuffer.capacity();
                Log.d(TAG, "read audio ["+len+"]，flush ["+flush+"]");
                if (len > 0) {
                    readBuffer.limit(len);
                    final ByteBuffer finalBuffer = readBuffer;
                    encodeQueue.postRunnable(new Runnable() {
                        @Override
                        public void run() {
                            encodFrame(finalBuffer, flush);
                        }
                    });
                    recordQueue.postRunnable(recordRunnable);
                } else {
                    recordBuffers.add(readBuffer);
                }
            }
        }
    };
}
