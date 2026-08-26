package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * 流式文档解析服务。
 *
 * <p>与 {@link DocumentParseService} 不同，本服务不把整份文本累积进内存，而是通过自定义
 * {@link org.xml.sax.ContentHandler} 在正文达到 {@code flushSize} 时按窗口回调，
 * 供下游分块器增量消费。复用与 {@link DocumentParseService} 相同的 PDF 解析配置与嵌入文档禁用策略。</p>
 */
@Slf4j
@Service
public class StreamingDocumentParseService {

    /**
     * 流式解析文件，把正文按窗口回调。
     *
     * @param inputStream  文件输入流（调用方负责关闭）
     * @param flushSize    正文 flush 阈值（字符）
     * @param textConsumer 正文窗口回调，可能被多次调用
     */
    public void parseStream(InputStream inputStream, int flushSize, Consumer<String> textConsumer) {
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());
        PDFParserConfig pdfConfig = new PDFParserConfig();
        pdfConfig.setExtractInlineImages(false);
        pdfConfig.setSortByPosition(true);
        context.set(PDFParserConfig.class, pdfConfig);

        StreamingContentHandler handler = new StreamingContentHandler(flushSize, textConsumer);
        try {
            parser.parse(inputStream, handler, metadata, context);
            handler.flush();
        } catch (SAXException | TikaException | IOException e) {
            log.error("流式解析文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 有界缓冲的正文处理器，只在窗口阈值处 flush，避免内存随文本长度增长。
     */
    private static final class StreamingContentHandler extends DefaultHandler {

        private final int flushSize;
        private final Consumer<String> textConsumer;
        private final StringBuilder buffer = new StringBuilder();

        StreamingContentHandler(int flushSize, Consumer<String> textConsumer) {
            this.flushSize = flushSize;
            this.textConsumer = textConsumer;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            if (buffer.length() >= flushSize) {
                flush();
            }
        }

        void flush() {
            if (!buffer.isEmpty()) {
                textConsumer.accept(buffer.toString());
                buffer.setLength(0);
            }
        }
    }
}
