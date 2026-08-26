package interview.guide.infrastructure.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StreamingChunker 单元测试。
 *
 * <p>使用固定长度 {@link TextSplitter} 子类，确定性验证：跨 buffer 边界的 carry 不丢文本、
 * 分块顺序稳定、空白段被忽略、单个分块立即产出。</p>
 */
@DisplayName("流式分块器测试")
class StreamingChunkerTest {

    /**
     * 固定长度分块器：按固定字符数切分，行为完全确定，便于验证 carry / 无丢失。
     */
    private static final class FixedSizeSplitter extends TextSplitter {

        private final int size;

        FixedSizeSplitter(int size) {
            this.size = size;
        }

        @Override
        protected List<String> splitText(String text) {
            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < text.length(); i += size) {
                chunks.add(text.substring(i, Math.min(i + size, text.length())));
            }
            return chunks;
        }
    }

    private static List<String> collect(StreamingChunker chunker, String... segments) {
        List<String> result = new ArrayList<>();
        Consumer<List<String>> consumer = result::addAll;
        for (String segment : segments) {
            chunker.feed(segment, consumer);
        }
        chunker.finish(consumer);
        return result;
    }

    private static String join(List<String> chunks) {
        return String.join("", chunks);
    }

    @Nested
    @DisplayName("基础切分")
    class BasicSplittingTests {

        @Test
        @DisplayName("小于 buffer 阈值的文本在 finish 时切分")
        void testFinishFlushesBelowBufferSize() {
            StreamingChunker chunker = new StreamingChunker(new FixedSizeSplitter(4), 100);

            List<String> chunks = collect(chunker, "abcdefgh");

            assertEquals(List.of("abcd", "efgh"), chunks);
        }

        @Test
        @DisplayName("单个分块立即产出，不作为 carry 保留")
        void testSingleChunkFlushedImmediately() {
            StreamingChunker chunker = new StreamingChunker(new FixedSizeSplitter(10), 10);

            List<String> chunks = collect(chunker, "abcdefghij");

            assertEquals(List.of("abcdefghij"), chunks);
        }
    }

    @Nested
    @DisplayName("跨 buffer 边界的 carry")
    class CarryTests {

        @Test
        @DisplayName("buffer 边界处的尾块被保留到下一轮，不丢失")
        void testCarryPreservesTrailingChunk() {
            // bufferSize=10，chunkSize=4：10 个字符切出 ["abcd","efgh","ij"]，
            // 尾块 "ij" 应被 carry 到 finish 再产出。
            StreamingChunker chunker = new StreamingChunker(new FixedSizeSplitter(4), 10);

            List<String> chunks = collect(chunker, "abcdefghij");

            assertEquals("abcdefghij", join(chunks));
            assertEquals(List.of("abcd", "efgh", "ij"), chunks);
        }

        @Test
        @DisplayName("多段 feed 跨多次 flush，文本无丢失且顺序稳定")
        void testMultipleFeedsNoLossAndStableOrder() {
            StreamingChunker chunker = new StreamingChunker(new FixedSizeSplitter(4), 10);

            List<String> chunks = collect(chunker, "abcde", "fghij", "klmno");

            assertEquals("abcdefghijklmno", join(chunks));
            assertEquals(List.of("abcd", "efgh", "ijkl", "mno"), chunks);
        }

        @Test
        @DisplayName("长文本跨多轮 flush 无丢失")
        void testLongTextNoLoss() {
            StreamingChunker chunker = new StreamingChunker(new FixedSizeSplitter(4), 10);
            String text = "abcdefghijklmnopqrstuvwxyz0123456789";

            List<String> chunks = collect(chunker, text);

            assertEquals(text, join(chunks));
        }
    }

    @Nested
    @DisplayName("边界与空输入")
    class EdgeCaseTests {

        @Test
        @DisplayName("空字符串与空白段不产生分块")
        void testBlankSegmentsIgnored() {
            StreamingChunker chunker = new StreamingChunker(new FixedSizeSplitter(4), 10);

            List<String> chunks = collect(chunker, "", "   ", "\n\t");

            assertTrue(chunks.isEmpty());
        }

        @Test
        @DisplayName("空输入 finish 不产生分块")
        void testEmptyInput() {
            StreamingChunker chunker = new StreamingChunker(new FixedSizeSplitter(4), 10);

            List<String> chunks = collect(chunker);

            assertTrue(chunks.isEmpty());
        }
    }
}
