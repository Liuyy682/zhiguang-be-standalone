package com.tongji.agent;

import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PostContextTool {

    private static final int MAX_CHUNKS = 3;
    private static final int MAX_CHUNK_LENGTH = 1000;

    private final KnowPostMapper knowPostMapper;
    private final VectorStore vectorStore;

    public PostContextTool(KnowPostMapper knowPostMapper, VectorStore vectorStore) {
        this.knowPostMapper = knowPostMapper;
        this.vectorStore = vectorStore;
    }

    public Observation search(long postId, String query) {
        KnowPost post = knowPostMapper.findById(postId);
        if (post == null || !"published".equals(post.getStatus()) || !"public".equals(post.getVisible())) {
            return new Observation("unavailable", List.of());
        }

        String id = Long.toString(postId);
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(MAX_CHUNKS)
                .filterExpression(new FilterExpressionBuilder().eq("postId", id).build())
                .build();
        List<Chunk> chunks = new ArrayList<>(MAX_CHUNKS);
        for (Document document : vectorStore.similaritySearch(request)) {
            if (!id.equals(document.getMetadata().get("postId"))) {
                continue;
            }
            Object chunkMetadata = document.getMetadata().get("chunkId");
            String text = document.getText();
            if (!(chunkMetadata instanceof String chunkId) || chunkId.isBlank() || text == null || text.isBlank()) {
                continue;
            }

            int length = Math.min(MAX_CHUNK_LENGTH, text.codePointCount(0, text.length()));
            chunks.add(new Chunk(id, chunkId, text.substring(0, text.offsetByCodePoints(0, length))));
            if (chunks.size() == MAX_CHUNKS) {
                break;
            }
        }

        return new Observation(chunks.isEmpty() ? "no_context" : "ok", List.copyOf(chunks));
    }

    public record Observation(String status, List<Chunk> chunks) {
    }

    public record Chunk(String postId, String chunkId, String text) {
    }
}
