package interview.guide.infrastructure.file;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.List;
import java.util.function.Consumer;

/**
 * 有界内存流式分块器。
 *
 * <p>把流式解析产出的文本窗口累积到 {@code bufferSize} 后交给 {@link TextSplitter} 切块，
 * 并通过 carry 尾块把「最后一个可能不完整的分块」保留到下一轮，避免在缓冲区边界处截断句子。
 * 内存峰值约等于 bufferSize + 一个 chunk 的大小，不随文件总长度增长。</p>
 */
public class StreamingChunker {

    private final TextSplitter textSplitter;
    private final int bufferSize;
    private final StringBuilder buffer = new StringBuilder();
    private String carry = "";

    public StreamingChunker(TextSplitter textSplitter, int bufferSize) {
        this.textSplitter = textSplitter;
        this.bufferSize = bufferSize;
    }

    /**
     * 喂入一段已清洗文本。
     *
     * @param segment        文本段
     * @param chunksConsumer 切出的分块回调（每次回调一批 chunk 文本）
     */
    public void feed(String segment, Consumer<List<String>> chunksConsumer) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        buffer.append(segment);
        if (buffer.length() >= bufferSize) {
            flush(false, chunksConsumer);
        }
    }

    /**
     * 流结束，flush 剩余内容（此时不保留 carry）。
     */
    public void finish(Consumer<List<String>> chunksConsumer) {
        flush(true, chunksConsumer);
    }

    private void flush(boolean isFinal, Consumer<List<String>> chunksConsumer) {
        String text = carry + buffer;
        buffer.setLength(0);
        if (text.isBlank()) {
            carry = "";
            return;
        }
        List<Document> documents = textSplitter.apply(List.of(new Document(text)));
        List<String> chunkTexts = documents.stream()
            .map(Document::getText)
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .toList();
        if (chunkTexts.isEmpty()) {
            carry = "";
            return;
        }
        if (isFinal || chunkTexts.size() == 1) {
            chunksConsumer.accept(chunkTexts);
            carry = "";
        } else {
            chunksConsumer.accept(chunkTexts.subList(0, chunkTexts.size() - 1));
            carry = chunkTexts.get(chunkTexts.size() - 1);
        }
    }
}
