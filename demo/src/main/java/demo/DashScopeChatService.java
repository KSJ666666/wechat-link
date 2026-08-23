package demo;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.protocol.Protocol;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DashScopeChatService {
	private final String apiKey;
	private final String model;
	private final String systemPrompt;
	private final Generation generation;

	public DashScopeChatService(
			@Value("${dashscope.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
			@Value("${dashscope.model:qwen-plus}") String model,
			@Value("${dashscope.system-prompt:你是接入微信机器人的助手。请用简洁、自然的中文回复用户。}") String systemPrompt,
			@Value("${dashscope.base-url:${DASHSCOPE_BASE_URL:}}") String baseUrl) {
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.model = model;
		this.systemPrompt = systemPrompt;
		if (baseUrl == null || baseUrl.trim().isEmpty()) {
			this.generation = new Generation();
		}
		else {
			this.generation = new Generation(Protocol.HTTP.getValue(), baseUrl.trim());
		}
	}

	public String reply(String userText) throws ApiException, NoApiKeyException, InputRequiredException {
		if (this.apiKey.isEmpty()) {
			throw new IllegalStateException("请先配置 dashscope.api-key 或环境变量 DASHSCOPE_API_KEY");
		}

		Message systemMessage = Message.builder()
				.role(Role.SYSTEM.getValue())
				.content(this.systemPrompt)
				.build();
		Message userMessage = Message.builder()
				.role(Role.USER.getValue())
				.content(userText)
				.build();

		GenerationParam param = GenerationParam.builder()
				.apiKey(this.apiKey)
				.model(this.model)
				.messages(Arrays.asList(systemMessage, userMessage))
				.resultFormat(GenerationParam.ResultFormat.MESSAGE)
				.build();

		GenerationResult result = this.generation.call(param);
		if (result == null
				|| result.getOutput() == null
				|| result.getOutput().getChoices() == null
				|| result.getOutput().getChoices().isEmpty()
				|| result.getOutput().getChoices().get(0).getMessage() == null
				|| result.getOutput().getChoices().get(0).getMessage().getContent() == null) {
			return "我暂时没有生成有效回复，请稍后再试。";
		}
		return result.getOutput().getChoices().get(0).getMessage().getContent();
	}
}
