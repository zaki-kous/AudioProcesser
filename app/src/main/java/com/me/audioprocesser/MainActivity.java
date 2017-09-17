package com.me.audioprocesser;

import android.Manifest;
import android.os.Build;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.me.audioprocesser.play.AudioPlayer;
import com.me.audioprocesser.record.AudioRecorder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    private AudioRecorder audioRecorder;
    private AudioPlayer audioPlayer;
    private Button mPlayBtn;
    private Button mRecordBtn;
    private String audioRecordPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mRecordBtn = (Button) findViewById(R.id.audio_recod_btn);
        mPlayBtn = (Button) findViewById(R.id.audio_play_btn);
        mPlayBtn.setVisibility(View.GONE);

        mRecordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPlayBtn.setVisibility(View.GONE);
                Button clickedButton = (Button) v;
                if (audioRecorder == null) {
                    audioRecorder = new AudioRecorder();
                }
                int recordStatus = v.getTag(R.id.id_audio_record_status) != null ? (int) v.getTag(R.id.id_audio_record_status) : AudioRecorder.RECORD_STATUS_IDEL;
                if (recordStatus == AudioRecorder.RECORD_STATUS_IDEL) {
                    audioRecordPath = getRecordAudioPath();
                    if (audioRecorder.startRecording(audioRecordPath)) {
                        v.setTag(R.id.id_audio_record_status, AudioRecorder.RECORD_STATUS_ING);
                        clickedButton.setText("暂停录制");
                    }
                } else if (recordStatus == AudioRecorder.RECORD_STATUS_ING) {
                    audioRecorder.stopRecording();
                    v.setTag(R.id.id_audio_record_status, AudioRecorder.RECORD_STATUS_IDEL);
                    clickedButton.setText("开始录制");
                    mPlayBtn.setVisibility(View.VISIBLE);
                }
            }
        });

        mPlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mPlayBtn.setClickable(false);
                mPlayBtn.setFocusable(false);
                mRecordBtn.setVisibility(View.GONE);
                if (audioPlayer == null) {
                    audioPlayer = new AudioPlayer(MainActivity.this);
                    audioPlayer.setOnAudioPlayListener(new AudioPlayer.OnAudioPlayListener() {
                        @Override
                        public void onFinish() {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    mPlayBtn.setClickable(true);
                                    mPlayBtn.setFocusable(true);
                                    mRecordBtn.setVisibility(View.VISIBLE);
                                }
                            });
                        }
                    });
                }
                audioPlayer.play(audioRecordPath);
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 200);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    }

    private String getRecordAudioPath() {
        Date currentTime = new Date();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
        String dateString = formatter.format(currentTime);
        String audioDir = Environment.getExternalStorageDirectory().getPath() + File.separator + "audioDemo";
        File audioFileDir = new File(audioDir);
        if (!audioFileDir.exists()) {
            audioFileDir.mkdirs();
        }
        return audioDir + File.separator + dateString + ".ogg";
    }

}
