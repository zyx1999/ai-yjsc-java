package com.yuerong.diligence.web.sse;

import com.yuerong.diligence.common.*;
import com.yuerong.diligence.common.stream.EventStreamTask;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Reusable bounded SSE transport; no business state, event names, or platform protocol. */
@Component
public class SseStreams {
  /** 空闲心跳间隔：长耗时模型等待期间保持连接，避免网关/浏览器因长时间无数据而断开。 */
  private static final long HEARTBEAT_MS = 15000;

  private final ExecutorService executor;
  private final ScheduledExecutorService heartbeat;
  private final long timeout;
  private final long heartbeatMs;

  @Autowired
  public SseStreams(@Value("${diligence.platform.timeout-ms:600000}") long timeout) {
    this(new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16)), timeout);
  }

  SseStreams(ExecutorService executor, long timeout) {
    this(executor, timeout, HEARTBEAT_MS);
  }

  SseStreams(ExecutorService executor, long timeout, long heartbeatMs) {
    this.executor = executor;
    this.timeout = timeout;
    this.heartbeatMs = heartbeatMs;
    this.heartbeat =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "sse-heartbeat");
              thread.setDaemon(true);
              return thread;
            });
  }

  public SseEmitter open(EventStreamTask task) {
    SseEmitter emitter = new SseEmitter(timeout);
    AtomicBoolean closed = new AtomicBoolean();
    Object[] context = task.diagnosticContext();
    ScheduledFuture<?>[] beat = new ScheduledFuture<?>[1];
    Runnable stopBeat =
        () -> {
          if (beat[0] != null) beat[0].cancel(false);
        };
    emitter.onCompletion(() -> {
      closed.set(true);
      stopBeat.run();
    });
    emitter.onTimeout(() -> {
      closed.set(true);
      stopBeat.run();
      Diagnostics.failure("stream.browser_timeout", null, context);
    });
    emitter.onError(error -> {
      closed.set(true);
      stopBeat.run();
      Diagnostics.failure("stream.browser_failed", error, context);
    });
    // 心跳为 SSE 注释帧（不产生 data 行），仅用于维持连接，不参与业务事件与顺序。
    beat[0] =
        heartbeat.scheduleAtFixedRate(
            () -> {
              if (closed.get()) return;
              try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
              } catch (Exception ignored) {
                // 连接已断开：任务线程在下次发送时感知，无需在此中断任务。
              }
            },
            heartbeatMs,
            heartbeatMs,
            TimeUnit.MILLISECONDS);
    try {
      executor.execute(() -> {
        try (Diagnostics.Scope ignored = Diagnostics.scope(context)) {
          task.execute((id, type, payload) -> {
            if (closed.get()) throw new IOException("SSE connection closed");
            emitter.send(SseEmitter.event().id(id).name(type).data(payload.toString()));
          });
          emitter.complete();
        } catch (Exception error) {
          Diagnostics.failure("stream.failed", error, context);
          emitter.completeWithError(error);
        } finally {
          stopBeat.run();
        }
      });
    } catch (RejectedExecutionException error) {
      stopBeat.run();
      Diagnostics.failure("stream.queue_full", error, context);
      task.rejected();
      throw new Fault("RUN_BUSY", "服务繁忙，请稍后重试", 503);
    }
    return emitter;
  }

  @javax.annotation.PreDestroy
  public void shutdown() {
    executor.shutdownNow();
    heartbeat.shutdownNow();
  }
}
