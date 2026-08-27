package demo;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "wechat.bot", name = "enabled", havingValue = "true")
public class WechatBotRunner implements ApplicationRunner {
	private static final Logger log = LoggerFactory.getLogger(WechatBotRunner.class);

	private final DashScopeChatService chatService;
	private final Set<Long> handledMessageIds = ConcurrentHashMap.newKeySet();
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private volatile ILinkClient client;

	public WechatBotRunner(DashScopeChatService chatService) {
		this.chatService = chatService;
	}

	@Override
	public void run(ApplicationArguments args) {
		this.executor.submit(this::runBot);
	}

	private void runBot() {
		ILinkConfig config = ILinkConfig.builder()
				.connectTimeoutMs(35000)
				.readTimeoutMs(35000)
				.writeTimeoutMs(35000)
				.httpMaxRetries(3)
				.retryBaseDelayMs(1000)
				.retryMaxDelayMs(10000)
				.heartbeatEnabled(false)
				.heartbeatIntervalMs(30000)
				.channelVersion("1.0.0")
				.build();

		try {
			this.client = ILinkClient.builder().config(config).build();
			String qrCodeContent = this.client.executeLogin();
			log.info("请将下面内容渲染为二维码并扫码登录：{}", qrCodeContent);

			LoginContext context = this.client.getLoginFuture().get();
			log.info("微信登录成功，botId = {}", context.getBotId());

			while (!Thread.currentThread().isInterrupted()) {
				List<WeixinMessage> messages = this.client.getUpdates();
				for (WeixinMessage message : messages) {
					handleMessage(message);
				}
			}
		}
		catch (Exception ex) {
			log.error("微信机器人运行失败", ex);
		}
	}

	private void handleMessage(WeixinMessage message) {
		if (message == null || message.getMessage_id() == null) {
			return;
		}
		if (!this.handledMessageIds.add(message.getMessage_id())) {
			return;
		}

		String fromUserId = message.getFrom_user_id();
		String text = extractText(message);
		if (fromUserId == null || fromUserId.trim().isEmpty() || text.trim().isEmpty()) {
			return;
		}

		try {
			log.info("收到微信文本消息，fromUserId = {}", fromUserId);
			this.client.startTyping(fromUserId);
			String reply = this.chatService.reply(text);
			this.client.sendText(fromUserId, reply);
			log.info("已回复微信消息，fromUserId = {}", fromUserId);
		}
		catch (Exception ex) {
			log.error("处理微信消息失败，fromUserId = {}", fromUserId, ex);
			try {
				this.client.sendText(fromUserId, "抱歉，我刚刚处理消息时遇到问题，请稍后再试。");
			}
			catch (Exception ignored) {
			}
		}
		finally {
			try {
				this.client.stopTyping(fromUserId);
			}
			catch (Exception ignored) {
			}
		}
	}

	private String extractText(WeixinMessage message) {
		if (message.getItem_list() == null) {
			return "";
		}

		StringBuilder builder = new StringBuilder();
		for (MessageItem item : message.getItem_list()) {
			if (item != null && item.getText_item() != null && item.getText_item().getText() != null) {
				if (builder.length() > 0) {
					builder.append('\n');
				}
				builder.append(item.getText_item().getText());
			}
		}
		return builder.toString();
	}

	@PreDestroy
	public void close() {
		this.executor.shutdownNow();
		if (this.client != null) {
			this.client.close();
		}
	}
}
