package interview.guide.modules.voiceinterview.audio;

/**
 * PCM 音频转 WAV 格式工具。
 *
 * <p>Qwen TTS Realtime API 输出 24kHz / 16bit / 单声道 PCM，前端浏览器需要带
 * 44 字节 RIFF 头的 WAV 才能直接播放。</p>
 */
public final class AudioConverter {

    /** Qwen TTS Realtime API 输出采样率 */
    private static final int SAMPLE_RATE = 24000;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int NUM_CHANNELS = 1;

    private AudioConverter() {
    }

    /**
     * 给 PCM 数据追加 WAV 头，转成可直接播放的 WAV 字节流。
     *
     * @param pcmData 原始 PCM 数据（24kHz、16bit、单声道）
     * @return WAV 格式音频数据
     */
    public static byte[] convertPcmToWav(byte[] pcmData) {
        int byteRate = SAMPLE_RATE * NUM_CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = NUM_CHANNELS * BITS_PER_SAMPLE / 8;
        int dataSize = pcmData.length;
        int fileSize = dataSize + 36;

        byte[] wavData = new byte[dataSize + 44];
        int pos = 0;

        // RIFF header
        wavData[pos++] = 'R'; wavData[pos++] = 'I'; wavData[pos++] = 'F'; wavData[pos++] = 'F';
        writeIntLE(wavData, pos, fileSize); pos += 4;
        wavData[pos++] = 'W'; wavData[pos++] = 'A'; wavData[pos++] = 'V'; wavData[pos++] = 'E';

        // fmt chunk
        wavData[pos++] = 'f'; wavData[pos++] = 'm'; wavData[pos++] = 't'; wavData[pos++] = ' ';
        writeIntLE(wavData, pos, 16); pos += 4;
        writeShortLE(wavData, pos, (short) 1); pos += 2;
        writeShortLE(wavData, pos, (short) NUM_CHANNELS); pos += 2;
        writeIntLE(wavData, pos, SAMPLE_RATE); pos += 4;
        writeIntLE(wavData, pos, byteRate); pos += 4;
        writeShortLE(wavData, pos, (short) blockAlign); pos += 2;
        writeShortLE(wavData, pos, (short) BITS_PER_SAMPLE); pos += 2;

        // data chunk
        wavData[pos++] = 'd'; wavData[pos++] = 'a'; wavData[pos++] = 't'; wavData[pos++] = 'a';
        writeIntLE(wavData, pos, dataSize); pos += 4;

        System.arraycopy(pcmData, 0, wavData, 44, pcmData.length);

        return wavData;
    }

    private static void writeIntLE(byte[] buf, int pos, int value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
        buf[pos + 2] = (byte) ((value >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShortLE(byte[] buf, int pos, short value) {
        buf[pos] = (byte) (value & 0xFF);
        buf[pos + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
