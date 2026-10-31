package com.orangecode.tianmu.websocket;

import com.fasterxml.jackson.databind.*;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomBatchBroadcasterTest {
    private record Timer(Runnable action, ScheduledFuture<?> future) {}
    private final List<Timer> timers = new CopyOnWriteArrayList<>();
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private ScheduledExecutorService scheduler;
    private RoomBatchBroadcaster broadcasts;
    private int maxFrameBytes;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach void setup() {
        scheduler=mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class),anyLong(),eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            ScheduledFuture<?> future=mock(ScheduledFuture.class);
            timers.add(new Timer(call.getArgument(0),future)); return future;
        });
    }
    @AfterEach void cleanup() {
        if(broadcasts!=null)broadcasts.close();
        for(var channel:channels)channel.finishAndReleaseAll();
    }

    @Test void lowTrafficUsesOneTimerFromTheFirstItemAndFlushesInAppendOrder() throws Exception {
        start(50,16384);var viewer=join(99);
        broadcasts.append(99,item(1,"a"));broadcasts.append(99,item(2,"b"));
        assertTrue(frames(viewer).isEmpty());assertEquals(1,timers.size());
        verify(scheduler).schedule(any(Runnable.class),eq(100L),eq(TimeUnit.MILLISECONDS));
        timers.get(0).action.run();
        var frames=frames(viewer);assertEquals(1,frames.size());
        assertEquals(List.of("1","2"),ids(frames));
        assertEquals(5,frames.get(0).path("type").asInt());
    }

    @Test void countLimitFlushesImmediatelyAndStaleTimerCannotFlushTheNextBatch() throws Exception {
        start(2,16384);var viewer=join(99);
        broadcasts.append(99,item(1,"a"));broadcasts.append(99,item(2,"b"));
        assertEquals(List.of("1","2"),ids(frames(viewer)));
        verify(timers.get(0).future).cancel(false);
        broadcasts.append(99,item(3,"c"));
        timers.get(0).action.run();assertTrue(frames(viewer).isEmpty());
        timers.get(1).action.run();assertEquals(List.of("3"),ids(frames(viewer)));
    }

    @Test void byteLimitIncludesUtf8EnvelopeAndSeparatorsWithoutLosingItems() throws Exception {
        start(50,4096);var viewer=join(99);
        for(int i=1;i<=6;i++)broadcasts.append(99,item(i,"中".repeat(255)));
        var output=frames(viewer);assertFalse(output.isEmpty());
        for(var timer:timers)timer.action.run();output.addAll(frames(viewer));
        assertEquals(List.of("1","2","3","4","5","6"),ids(output));
        for(var frame:output)assertTrue(JSON.writeValueAsBytes(frame).length<=4096);
    }

    @Test void roomsHaveIndependentBatchesAndControlMessagesAreImmediate() throws Exception {
        start(50,16384);var first=join(99);var second=join(100);
        broadcasts.append(99,item(1,"a"));broadcasts.append(100,item(2,"b"));
        timers.get(0).action.run();
        assertEquals(List.of("1"),ids(frames(first)));assertTrue(frames(second).isEmpty());
        timers.get(1).action.run();assertEquals(List.of("2"),ids(frames(second)));
    }

    @Test void lastViewerLeavingCancelsPendingBatchAndCannotLeakIntoRecreatedRoom() throws Exception {
        start(50,16384);var old=join(99);broadcasts.append(99,item(1,"old"));
        broadcasts.leave(99,old);assertEquals(0,broadcasts.roomCount());
        verify(timers.get(0).future).cancel(false);
        var current=join(99);broadcasts.append(99,item(2,"new"));
        timers.get(0).action.run();assertTrue(frames(current).isEmpty());
        timers.get(1).action.run();assertEquals(List.of("2"),ids(frames(current)));
        assertTrue(frames(old).isEmpty());
    }

    @Test void shutdownFlushesPendingOnceAndStopsTimers() throws Exception {
        start(50,16384);var viewer=join(99);broadcasts.append(99,item(1,"a"));
        broadcasts.close();assertEquals(List.of("1"),ids(frames(viewer)));
        broadcasts.close();timers.get(0).action.run();assertTrue(frames(viewer).isEmpty());
        assertEquals(0,broadcasts.roomCount());verify(scheduler).shutdownNow();
        assertThrows(IllegalStateException.class,()->broadcasts.join(99,viewer));
    }

    @Test void burstOfOneHundredMessagesProducesTenFramesInsteadOfOneHundred() throws Exception {
        start(10,16384);var viewer=join(99);
        for(int i=0;i<100;i++)broadcasts.append(99,item(i,"message"));
        var output=frames(viewer);assertEquals(10,output.size());assertEquals(100,ids(output).size());
        for(int i=0;i<100;i++)assertEquals(Integer.toString(i),ids(output).get(i));
    }

    @Test void concurrentRoomWritersDoNotDuplicateOrLoseItems() throws Exception {
        start(10,16384);var viewer=join(99);var workers=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> calls=new ArrayList<>();
            for(int i=0;i<100;i++){int id=i;calls.add(workers.submit(()->broadcasts.append(99,item(id,"message"))));}
            for(var call:calls)call.get(10,TimeUnit.SECONDS);
            var output=frames(viewer);assertEquals(10,output.size());assertEquals(100,ids(output).size());
            assertEquals(100,new HashSet<>(ids(output)).size());
        } finally {workers.shutdownNow();}
    }

    @Test void emptyRoomsAndOversizedItemsCannotBuildUnboundedPendingWork() throws Exception {
        start(50,4096);broadcasts.append(99,item(1,"no viewers"));assertTrue(timers.isEmpty());
        var viewer=join(99);
        assertThrows(IllegalArgumentException.class,()->broadcasts.append(99,item(2,"x".repeat(5000))));
        assertTrue(timers.isEmpty());assertTrue(frames(viewer).isEmpty());
        assertThrows(IllegalArgumentException.class,()->new RoomBatchBroadcaster.Limits(1000,50,4096));
        assertThrows(IllegalArgumentException.class,()->new RoomBatchBroadcaster.Limits(100,0,4096));
    }

    private void start(int count,int bytes) {
        maxFrameBytes=bytes;broadcasts=new RoomBatchBroadcaster(new RoomBatchBroadcaster.Limits(100,count,bytes),scheduler);
    }
    private EmbeddedChannel join(long room) throws Exception {
        var channel=new EmbeddedChannel(DefaultChannelId.newInstance());channels.add(channel);broadcasts.join(room,channel);
        var control=frames(channel);assertEquals(1,control.size());assertEquals(1,control.get(0).path("type").asInt());return channel;
    }
    private OnlineBulletResponse item(int id,String text) {
        var item=new OnlineBulletResponse();item.setBulletId(Integer.toString(id));item.setUserId("1");
        item.setText(text);item.setPlaybackTime((double)id);return item;
    }
    private List<JsonNode> frames(EmbeddedChannel channel) throws Exception {
        channel.runPendingTasks();List<JsonNode> result=new ArrayList<>();Object next;
        while((next=channel.readOutbound())!=null) {
            try {if(next instanceof TextWebSocketFrame text){
                assertTrue(text.content().readableBytes()<=maxFrameBytes);result.add(JSON.readTree(text.text()));
            }} finally {ReferenceCountUtil.release(next);}
        }
        return result;
    }
    private List<String> ids(List<JsonNode> frames) {
        List<String> result=new ArrayList<>();for(var frame:frames)for(var item:frame.path("data"))result.add(item.path("bulletId").asText());return result;
    }
}
