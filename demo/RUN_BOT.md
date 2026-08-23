# 微信 iLink + 阿里云百炼接入说明

## 已接入内容

- 已添加微信 iLink SDK 依赖。
- 已添加阿里云百炼 DashScope Java SDK 依赖。
- Spring Boot 启动后可扫码登录微信 iLink Bot。
- 收到文本消息后调用百炼大模型生成回复，并发送回微信。

## 本地运行前配置

先配置百炼 API Key：

```powershell
$env:DASHSCOPE_API_KEY="sk-你的百炼APIKey"
```

然后打开 `src/main/resources/application.properties`：

```properties
wechat.bot.enabled=true
```

可选配置：

```properties
dashscope.model=qwen-plus
dashscope.system-prompt=你是一个微信聊天助手，请简洁回复。
```

启动项目：

```powershell
.\mvnw spring-boot:run
```

控制台会输出二维码内容。扫码登录后，用户向 bot 发送文本消息，项目会自动调用百炼并回复文本。
