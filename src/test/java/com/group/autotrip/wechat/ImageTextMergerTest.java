package com.group.autotrip.wechat;

import com.github.wechat.ilink.sdk.core.model.ImageItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageTextMergerTest {

    private static MessageItem imageItem() {
        MessageItem item = new MessageItem();
        item.setImage_item(new ImageItem());
        return item;
    }

    /** 记录回调事件，便于断言合并结果 */
    private static final class RecordingHandler implements ImageTextMerger.Handler {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void onImage(String fromUserId, MessageItem imageItem, String text) {
            events.add("image:" + (text == null ? "" : text));
        }

        @Override
        public void onText(String fromUserId, String text) {
            events.add("text:" + text);
        }
    }

    @Test
    void imageAndTextInSameMessageAreMergedImmediately() {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(1000, 1000, handler);
        merger.handleImage("u1", imageItem(), "帮我看下这个");
        merger.shutdown();
        assertEquals(List.of("image:帮我看下这个"), handler.events);
    }

    @Test
    void textThenImageIsMerged() {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(1000, 1000, handler);
        merger.handleText("u1", "这是哪里");
        merger.handleImage("u1", imageItem(), null);
        merger.shutdown();
        assertEquals(List.of("image:这是哪里"), handler.events);
    }

    @Test
    void imageThenTextIsMerged() {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(1000, 1000, handler);
        merger.handleImage("u1", imageItem(), null);
        merger.handleText("u1", "帮我翻译一下");
        merger.shutdown();
        assertEquals(List.of("image:帮我翻译一下"), handler.events);
    }

    @Test
    void imageWithoutTextFallsBackToGenericDescribeAfterWindow() throws InterruptedException {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(100, 100, handler);
        merger.handleImage("u1", imageItem(), null);
        awaitEvent(handler, 1);
        merger.shutdown();
        assertEquals(List.of("image:"), handler.events);
    }

    @Test
    void textWithoutImageFallsBackToPlainChatAfterWindow() throws InterruptedException {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(100, 100, handler);
        merger.handleText("u1", "你好");
        awaitEvent(handler, 1);
        merger.shutdown();
        assertEquals(List.of("text:你好"), handler.events);
    }

    @Test
    void consecutiveTextsAreBothHandled() throws InterruptedException {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(100, 100, handler);
        merger.handleText("u1", "第一句");
        merger.handleText("u1", "第二句");
        awaitEvent(handler, 2);
        merger.shutdown();
        assertEquals(List.of("text:第一句", "text:第二句"), handler.events);
    }

    @Test
    void differentUsersDoNotMerge() throws InterruptedException {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(100, 100, handler);
        merger.handleImage("u1", imageItem(), null);
        merger.handleText("u2", "别人的文字");
        awaitEvent(handler, 2);
        merger.shutdown();
        assertEquals(2, handler.events.size());
        assertTrue(handler.events.contains("image:"));
        assertTrue(handler.events.contains("text:别人的文字"));
    }

    @Test
    void textArrivingWithinImageWindowMergesEvenAfterTextWindow() throws InterruptedException {
        RecordingHandler handler = new RecordingHandler();
        // 图片等文字 400ms、文字等图片 50ms：文字晚到但仍在图片窗口内，应合并
        ImageTextMerger merger = new ImageTextMerger(400, 50, handler);
        merger.handleImage("u1", imageItem(), null);
        Thread.sleep(150);
        merger.handleText("u1", "晚到的文字");
        merger.shutdown();
        assertEquals(List.of("image:晚到的文字"), handler.events);
    }

    @Test
    void textFlushedWhenImageArrivesAfterTextWindow() throws InterruptedException {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(400, 50, handler);
        merger.handleText("u1", "先发的文字");
        awaitEvent(handler, 1);          // 文字窗口超时，已按普通文本处理
        merger.handleImage("u1", imageItem(), null);
        awaitEvent(handler, 2);          // 图片窗口超时，按无文字描述处理
        merger.shutdown();
        assertEquals(List.of("text:先发的文字", "image:"), handler.events);
    }

    @Test
    void duplicateTextAfterImageMergeIsIgnored() {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(1000, 1000, handler);
        // 图片消息带文字立即合并；同一句描述又作为独立文字消息发了一遍，应被忽略
        merger.handleImage("u1", imageItem(), "帮我看下");
        merger.handleText("u1", "帮我看下");
        merger.shutdown();
        assertEquals(List.of("image:帮我看下"), handler.events);
    }

    @Test
    void sameMessageTextCleansPendingText() throws InterruptedException {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(200, 200, handler);
        // 文字先到待处理；图片消息带同一句文字到达后立即合并，并清掉待处理文字
        merger.handleText("u1", "这是哪里");
        merger.handleImage("u1", imageItem(), "这是哪里");
        awaitEvent(handler, 1);
        Thread.sleep(300);   // 超过窗口：若待处理文字没被清理，会多出一条 text 事件
        merger.shutdown();
        assertEquals(List.of("image:这是哪里"), handler.events);
    }

    @Test
    void differentPendingTextIsNotConsumedBySameMessageMerge() throws InterruptedException {
        RecordingHandler handler = new RecordingHandler();
        ImageTextMerger merger = new ImageTextMerger(200, 200, handler);
        // 先发了一句无关文字，再发"图片+另一句文字"：无关文字应仍按普通文本处理
        merger.handleText("u1", "你好");
        merger.handleImage("u1", imageItem(), "看看这个");
        awaitEvent(handler, 2);
        merger.shutdown();
        assertEquals(List.of("image:看看这个", "text:你好"), handler.events);
    }

    private static void awaitEvent(RecordingHandler handler, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (handler.events.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}