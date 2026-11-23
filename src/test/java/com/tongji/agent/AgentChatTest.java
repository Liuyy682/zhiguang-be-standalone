package com.tongji.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tongji.auth.config.SecurityConfig;
import com.tongji.knowpost.mapper.KnowPostMapper;
import com.tongji.knowpost.model.KnowPost;
import com.tongji.llm.LlmConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import({AgentLoop.class, PostContextTool.class, LlmConfig.class, SecurityConfig.class})
class AgentChatTest {
    private static final String CHAT_PATH = "/api/v1/agent/chat";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean(name = "deepSeekChatModel")
    private ChatModel model;
    @MockBean
    private JwtDecoder jwtDecoder;
    @MockBean
    private KnowPostMapper posts;
    @MockBean
    private VectorStore vectors;

    @Test
    void requiresLoginBeforeCallingModel() throws Exception {
        mvc.perform(post(CHAT_PATH).contentType(APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(model, posts, vectors);
    }

    @Test
    void rejectsBlankOrOversizeMessagesBeforeCallingModel() throws Exception {
        chat("   ").andExpect(status().isBadRequest());
        chat("字".repeat(2001)).andExpect(status().isBadRequest());
        verifyNoInteractions(model, posts, vectors);
    }

    @Test
    void returnsDirectAnswerWithoutRetrieval() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(reply("{\"type\":\"final\",\"answer\":\"你好\"}"));

        chat("打个招呼")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.answer").value("你好"));
        verifyNoInteractions(posts, vectors);
    }

    @Test
    void existingDeepSeekClientSendsDecisionPromptAndReplaysTheToolResult() throws Exception {
        var transport = RestClient.builder();
        var server = MockRestServiceServer.bindTo(transport).build();
        var deepSeekApi = DeepSeekApi.builder().apiKey("test-key").baseUrl("http://deepseek.test")
                .completionsPath("/chat/completions").restClientBuilder(transport).build();
        var deepSeek = DeepSeekChatModel.builder().deepSeekApi(deepSeekApi).build();
        var loop = new AgentLoop(new LlmConfig().chatClient(deepSeek), objectMapper, new PostContextTool(posts, vectors));
        when(posts.findById(123L)).thenReturn(KnowPost.builder().status("published").visible("public").build());
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk("123", "123#0", "沿河向北")));

        server.expect(requestTo("http://deepseek.test/chat/completions"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.model").value("deepseek-flash"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.messages[0].content").value(containsString("JSON")))
                .andExpect(MockRestRequestMatchers.jsonPath("$.tools").doesNotExist())
                .andRespond(completion(searchDecision("123")));
        server.expect(requestTo("http://deepseek.test/chat/completions"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.messages[2].role").value("assistant"))
                .andExpect(MockRestRequestMatchers.jsonPath("$.messages[3].content")
                        .value(containsString("沿河向北")))
                .andRespond(completion("{\"type\":\"final\",\"answer\":\"沿河向北\"}"));

        assertThat(loop.run("帖子 123 的路线怎么走？")).isEqualTo(new AgentLoop.Result("completed", "沿河向北"));
        server.verify();
    }

    @Test
    void comparesTwoPostsUsingOnlyTheirBoundedRetrievedEvidence() throws Exception {
        when(posts.findById(123L)).thenReturn(KnowPost.builder().status("published").visible("public").build());
        when(posts.findById(456L)).thenReturn(KnowPost.builder().status("published").visible("public").build());
        when(vectors.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(chunk("123", "123#0", "沿河向北" + "甲".repeat(1100) + "超长尾部"),
                        chunk("456", "456#9", "另一帖的片段"),
                        chunk("123", "123#1", "经过小桥"),
                        chunk("123", "123#2", "终点是码头"),
                        chunk("123", "123#3", "第四段片段")))
                .thenReturn(List.of(chunk("456", "456#0", "穿过山口")));
        when(model.call(any(Prompt.class))).thenReturn(
                search("123"), search("456"), reply("{\"type\":\"final\",\"answer\":\"一篇沿河，一篇穿过山口。\"}"));

        chat("比较帖子 123 和 456 推荐的路线")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("一篇沿河，一篇穿过山口。"));

        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(3)).call(prompts.capture());
        assertThat(prompts.getAllValues()).allSatisfy(prompt ->
                assertThat(prompt.getOptions().getModel()).isEqualTo("deepseek-flash"));
        assertThat(messages(prompts.getAllValues().get(1)))
                .contains("比较帖子 123 和 456 推荐的路线", "123#0", "沿河向北", "终点是码头")
                .doesNotContain("超长尾部", "另一帖的片段", "第四段片段", "穿过山口");
        assertThat(messages(prompts.getValue())).contains("123#0", "456#0", "沿河向北", "穿过山口");

        var searches = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectors, times(2)).similaritySearch(searches.capture());
        var filters = new FilterExpressionBuilder();
        assertThat(searches.getAllValues()).extracting(SearchRequest::getFilterExpression)
                .containsExactly(filters.eq("postId", "123").build(), filters.eq("postId", "456").build());
    }

    @ParameterizedTest
    @CsvSource({"published, private", "draft, public", "deleted, public", " , "})
    void returnsUnavailableForPostsThatCannotBeSearched(String postStatus, String visibility) throws Exception {
        when(posts.findById(123L)).thenReturn(postStatus == null ? null :
                KnowPost.builder().status(postStatus).visible(visibility).build());
        when(model.call(any(Prompt.class))).thenReturn(search("123"),
                reply("{\"type\":\"final\",\"answer\":\"无法查询该帖子。\"}"));

        chat("帖子 123 推荐了什么路线？")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("无法查询该帖子。"));

        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(messages(prompts.getValue())).contains("unavailable");
        verifyNoInteractions(vectors);
    }

    @Test
    void reportsMissingIndexedContextForPublicPost() throws Exception {
        when(posts.findById(123L)).thenReturn(KnowPost.builder().status("published").visible("public").build());
        when(vectors.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenReturn(search("123"),
                reply("{\"type\":\"final\",\"answer\":\"没有可用正文。\"}"));

        chat("帖子 123 推荐了什么路线？").andExpect(status().isOk());

        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(messages(prompts.getValue())).contains("no_context");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "   ",
            "invalid json",
            "{\"type\":\"final\",\"answer\":\"好\"} {\"type\":\"final\",\"answer\":\"嗯\"}",
            "{\"type\":\"action\",\"tool\":\"delete_post\",\"postId\":\"123\",\"query\":\"路线\"}",
            "{\"type\":\"action\",\"tool\":\"search_post_context\",\"postId\":\"9223372036854775808\",\"query\":\"路线\"}"
    })
    void invalidModelDecisionFailsBeforeRetrieval(String output) throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(reply(output));

        chat("查帖子正文").andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        verifyNoInteractions(posts, vectors);
    }

    @Test
    void modelFailureUsesGenericErrorResponse() throws Exception {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("upstream detail"));

        chat("查帖子正文").andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("服务异常，请稍后重试"));
        verifyNoInteractions(posts, vectors);
    }

    @Test
    void fourthToolRequestStopsWithoutRunningTheTool() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(search("123"), search("123"), search("123"), search("456"));

        chat("查帖子正文")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("step_limit"))
                .andExpect(jsonPath("$.answer").value(nullValue()));
        verify(model, times(4)).call(any(Prompt.class));
        verify(posts, times(3)).findById(123L);
        verify(posts, never()).findById(456L);
    }

    private ResultActions chat(String message) throws Exception {
        return mvc.perform(post(CHAT_PATH).with(jwt()).contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("message", message))));
    }

    private static ChatResponse search(String postId) {
        return reply(searchDecision(postId));
    }

    private static String searchDecision(String postId) {
        return """
                {"type":"action","tool":"search_post_context","postId":"%s","query":"路线"}
                """.formatted(postId);
    }

    private ResponseCreator completion(String content) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("id", "mock-completion", "model", "deepseek-flash",
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                        "message", Map.of("role", "assistant", "content", content)))));
        return withSuccess(body, APPLICATION_JSON);
    }

    private static ChatResponse reply(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static Document chunk(String postId, String chunkId, String text) {
        return new Document(text, Map.of("postId", postId, "chunkId", chunkId));
    }

    private static String messages(Prompt prompt) {
        return String.join("\n", prompt.getInstructions().stream().map(Message::getText).toList());
    }
}
