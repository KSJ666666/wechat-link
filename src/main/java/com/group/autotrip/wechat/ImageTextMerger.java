package com.group.autotrip.wechat;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 图文/文图消息配对器。
 *
 * <p>用户给 bot 发"图片 + 一句文字描述"时，iLink 协议通常把两者拆成两条独立消息
 * （先图后文 或 先文后图），且心跳轮询可能把两者分到两次 getUpdates 里。本类按
 * fromUserId 维护合并窗口：图片等待文字的时间较长（覆盖跨轮询到达），文字等待图片的
 * 时间较短（避免拖慢普通聊天）。窗口内凑齐"图片 + 文字"就触发一次
 * {@link Handler#onImage}（文字作为意图提示词）；超时只凑齐一边，则图片单独走
 * onImage(text=null)、文字单独走 onText，保证不丢消息。
 *
 * <p>另外微信发"图片+文字"时，同一句描述可能既出现在图片消息的 item_list 里
 * （图+文同一条消息），又作为一条独立文字消息再发一遍。本类通过"最近已合并文字"
 * 去重，避免同一句话被回复两次。
 */
class ImageTextMerger {

    private static final Logger log = LoggerFactory.getLogger(ImageTextMerger.class);

    /** 已合并文字的去重有效期：窗口内收到一模一样的文字则视为重复，直接忽略 */
    private static final long CONSUMED_TEXT_TTL_MS = 10000;

    private final long imageWindowMs;
    private final long textWindowMs;
    private final Handler handler;
    private final Map<String, PendingPair> pendingPairs = new ConcurrentHashMap<>();
    private final Map<String, ConsumedText> consumedTexts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /** 合并结果回调 */
    interface Handler {
        /** 处理一张图片；text 为配对到的文字描述，null 表示没有文字 */
        void onImage(String fromUserId, MessageItem imageItem, String text);

        /** 处理一条未与图片配对的纯文本 */
        void onText(String fromUserId, String text);
    }

    private static final class PendingPair {
        final String fromUserId;
        volatile MessageItem imageItem;
        volatile String text;
        volatile ScheduledFuture<?> timer;

        PendingPair(String fromUserId) {
            this.fromUserId = fromUserId;
        }
    }

    /** 最近一次被图文合并消费掉的文字 */
    private static final class ConsumedText {
        final String text;
        final long timeMs;

        ConsumedText(String text, long timeMs) {
            this.text = text;
            this.timeMs = timeMs;
        }
    }

    ImageTextMerger(long imageWindowMs, long textWindowMs, Handler handler) {
        this.imageWindowMs = imageWindowMs;
        this.textWindowMs = textWindowMs;
        this.handler = handler;
    }

    /**
     * 收到一条图片消息。
     *
     * @param sameMessageText 同一条消息内携带的文字（iLink 单条消息 item_list 可同时含图+文），
     *                        非空时视为已合并，立即处理
     */
    void handleImage(String fromUserId, MessageItem imageItem, String sameMessageText) {
        if (sameMessageText != null && !sameMessageText.isBlank()) {
            // 同一条消息已带文字：立即合并。同时清理同内容的待处理文字——
            // 微信可能把同一句描述再作为独立文字消息发一遍，避免它稍后按普通聊天再次回复
            PendingPair pair = pendingPairs.get(fromUserId);
            if (pair != null) {
                synchronized (pair) {
                    if (sameMessageText.equals(pair.text)) {
                        pair.text = null;
                        cancelTimer(pair);
                        removeIfEmpty(pair);
                    }
                }
            }
            markConsumed(fromUserId, sameMessageText);
            log.info("图文合并（同一条消息带文字）：from={}, 文字意图={}", fromUserId, sameMessageText);
            handler.onImage(fromUserId, imageItem, sameMessageText);
            return;
        }
        PendingPair pair = pendingPairs.computeIfAbsent(fromUserId, PendingPair::new);
        synchronized (pair) {
            if (pair.imageItem != null) {
                // 前一张图没等到文字就被新图顶替，先按无文字描述处理，避免丢图
                MessageItem previous = pair.imageItem;
                pair.imageItem = null;
                cancelTimer(pair);
                handler.onImage(fromUserId, previous, null);
            }
            if (pair.text != null) {
                // 文图：文字先到，立即合并
                String prompt = pair.text;
                pair.text = null;
                cancelTimer(pair);
                markConsumed(fromUserId, prompt);
                log.info("文图合并：from={}, 文字意图={}", fromUserId, prompt);
                handler.onImage(fromUserId, imageItem, prompt);
                removeIfEmpty(pair);
                return;
            }
            pair.imageItem = imageItem;
            ensureTimer(pair, imageWindowMs);
        }
    }

    /** 收到一条文本消息 */
    void handleText(String fromUserId, String text) {
        if (isConsumedRecently(fromUserId, text)) {
            log.info("忽略重复文字（已被图文合并消费）：from={}, text={}", fromUserId, text);
            return;
        }
        PendingPair pair = pendingPairs.computeIfAbsent(fromUserId, PendingPair::new);
        synchronized (pair) {
            if (pair.imageItem != null) {
                // 图文：图片在等文字，立即合并
                MessageItem image = pair.imageItem;
                pair.imageItem = null;
                cancelTimer(pair);
                markConsumed(fromUserId, text);
                log.info("图文合并：from={}, 文字意图={}", fromUserId, text);
                handler.onImage(fromUserId, image, text);
                removeIfEmpty(pair);
                return;
            }
            if (pair.text != null) {
                // 连续多条文本：把上一条立即按普通文本处理，避免被覆盖
                String pending = pair.text;
                pair.text = null;
                cancelTimer(pair);
                handler.onText(fromUserId, pending);
            }
            pair.text = text;
            ensureTimer(pair, textWindowMs);
        }
    }

    /** 停止调度；未到期的等待消息会被丢弃（应用关闭时调用） */
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** 合并窗口超时：没等到另一半，按独立消息兜底处理 */
    private void flush(PendingPair pair) {
        synchronized (pair) {
            pair.timer = null;
            MessageItem image = pair.imageItem;
            String text = pair.text;
            pair.imageItem = null;
            pair.text = null;
            if (image != null) {
                log.info("图片未等到文字，按无文字描述处理：from={}", pair.fromUserId);
                handler.onImage(pair.fromUserId, image, text);
            } else if (text != null) {
                log.info("文字未等到图片，按普通对话处理：from={}", pair.fromUserId);
                handler.onText(pair.fromUserId, text);
            }
        }
    }

    private void ensureTimer(PendingPair pair, long windowMs) {
        if (pair.timer == null) {
            pair.timer = scheduler.schedule(() -> flush(pair), windowMs, TimeUnit.MILLISECONDS);
        }
    }

    private static void cancelTimer(PendingPair pair) {
        ScheduledFuture<?> timer = pair.timer;
        pair.timer = null;
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private void removeIfEmpty(PendingPair pair) {
        if (pair.imageItem == null && pair.text == null) {
            pendingPairs.remove(pair.fromUserId, pair);
        }
    }

    private void markConsumed(String fromUserId, String text) {
        consumedTexts.put(fromUserId, new ConsumedText(text, System.currentTimeMillis()));
    }

    private boolean isConsumedRecently(String fromUserId, String text) {
        ConsumedText consumed = consumedTexts.get(fromUserId);
        return consumed != null && consumed.text.equals(text)
                && System.currentTimeMillis() - consumed.timeMs < CONSUMED_TEXT_TTL_MS;
    }
}