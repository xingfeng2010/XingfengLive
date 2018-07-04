package com.live.xingfene.xingfenglive.media;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioRecordThread extends Thread {
    private static final String TAG = "AudioRecordThread";
    private AudioRecordThread mAudioRecordThread;
    private boolean isRecord;
    protected final Object mSync = new Object();

    private long prevOutputPTSUs = 0L;
    private long offsetPTSUs = 0L;
    private boolean mIsEOS;
    private int mTrackIndex;
    private boolean mMuxerStarted;
    private MediaCodec mMediaCodec;

    public AudioRecordThread() {
        try {
            pareCodec();
        } catch (IOException e) {
            Log.e(TAG, "pareCodec e:" + e);
        }
    }

    protected void pareCodec() throws IOException {
        mTrackIndex = -1;
        mMuxerStarted = mIsEOS = false;
        MediaCodecInfo audioCodecInfo = selectAudioCodec("audio/mp4a-latm");
        if (audioCodecInfo == null) {
            Log.e("MediaAudioEncoder", "Unable to find an appropriate codec for audio/mp4a-latm");
        } else {
            MediaFormat audioFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, 16);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
            audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2);
            mMediaCodec = MediaCodec.createEncoderByType("audio/mp4a-latm");
            mMediaCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mMediaCodec.start();
        }
    }

    private static final MediaCodecInfo selectAudioCodec(String mimeType) {
        MediaCodecInfo result = null;
        int numCodecs = MediaCodecList.getCodecCount();

        for(int i = 0; i < numCodecs; ++i) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (codecInfo.isEncoder()) {
                String[] types = codecInfo.getSupportedTypes();

                for(int j = 0; j < types.length; ++j) {
                    if (types[j].equalsIgnoreCase(mimeType) && result == null) {
                        result = codecInfo;
                        return result;
                    }
                }
            }
        }

        return result;
    }

    public void startRecord() {
        isRecord = true;
        start();
    }

    @Override
    public void run() {
        int bufferSize = 2 * AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord mic = new AudioRecord(MediaRecorder.AudioSource.MIC, 44100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        mic.startRecording();

        ByteBuffer buf = ByteBuffer.allocateDirect(1024);
        while (isRecord) {
            int readBytes = mic.read(buf, 1024);
            if (readBytes > 0) {
                buf.position(readBytes);
                buf.flip();
                encode(buf, readBytes, getPTSUs());
                // frameAvailableSoon();
            }
        }
    }

    protected long getPTSUs() {
        long result;
        synchronized (mSync) {
            result = System.nanoTime() / 1000L - offsetPTSUs;
        }

        if (result < prevOutputPTSUs) {
            result += prevOutputPTSUs - result;
        }

        return result;
    }

    protected void encode(ByteBuffer buffer, int length, long presentationTimeUs) {
        if (isRecord) {
            ByteBuffer[] inputBuffers = mMediaCodec.getInputBuffers();

            while (isRecord) {
                int inputBufferIndex = mMediaCodec.dequeueInputBuffer(10000);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                    inputBuffer.clear();
                    if (buffer != null) {
                        inputBuffer.put(buffer);
                    }

                    if (length <= 0) {
                        mIsEOS = true;
                        mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, presentationTimeUs, 4);
                    } else {
                        mMediaCodec.queueInputBuffer(inputBufferIndex, 0, length, presentationTimeUs, 0);
                    }
                } else {
                    break;
                }
            }
        }
    }
}
