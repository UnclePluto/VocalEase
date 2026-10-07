package androidx.camera.video;

/** CameraX 1.6.1 专用适配：读取已写首个关键帧的单调 PTS，不读取回调时钟。
 * 此包访问在编译时校验；升级 CameraX 必须重新验证此适配和成片首帧同步。
 */
public final class RecorderTimeBridge {
    private RecorderTimeBridge() {}
    public static long firstFrameTimeNanos(Recorder recorder) {
        long us = recorder.mFirstRecordingVideoDataTimeUs;
        if (us == Long.MAX_VALUE || us < 0) throw new IllegalStateException("录像尚未写入首帧");
        return Math.multiplyExact(us, 1000L);
    }
}
