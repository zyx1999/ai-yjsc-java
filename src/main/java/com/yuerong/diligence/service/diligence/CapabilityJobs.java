package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Durable jobs in the application store. A renewable lease fences every checkpoint and publish. */
@Service
public class CapabilityJobs {
  static final String PLAN = "capabilities-v1";
  private static final long LEASE_MS = 60000;
  private final DiligenceService biz;
  private final BusinessCapabilities capabilities;
  private final boolean enabled;
  private final ExecutorService workers = Executors.newFixedThreadPool(2);
  private final ScheduledExecutorService clock = Executors.newScheduledThreadPool(2);
  private final Set<String> active = ConcurrentHashMap.newKeySet();
  private volatile boolean closing;

  public CapabilityJobs(DiligenceService biz, BusinessCapabilities capabilities,
      @Value("${diligence.jobs.enabled:true}") boolean enabled) {
    this.biz = biz; this.capabilities = capabilities; this.enabled = enabled;
  }

  @PostConstruct public void start() {
    Diagnostics.info("jobs.started", "enabled", enabled);
    if (enabled) clock.scheduleWithFixedDelay(this::safeSweep, 1, 2, TimeUnit.SECONDS);
  }

  @PreDestroy public void stop() {
    closing = true; clock.shutdownNow(); workers.shutdownNow();
    try { workers.awaitTermination(5, TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }

  public ObjectNode submit(String task, String action, JsonNode args, String requestId) {
    String key = Json.hash(Json.arr(task, action, requestId));
    String digest = Json.hash(Json.arr(action, args));
    final ObjectNode[] result = {null};
    biz.store.update("session", task, session -> {
      if (session.path("deleted").asBoolean()) throw new Fault("NOT_FOUND", "会话不存在", 404);
      ObjectNode prior = biz.store.get("cap-request", key);
      if (prior != null) {
        if (!digest.equals(prior.path("digest").asText()))
          throw new Fault("IDEMPOTENCY_CONFLICT", "同一次提交不能改变业务参数", 409);
        result[0] = read(task, prior.path("operation_id").asText());
        return session;
      }
      capabilities.validate(task, action, args);
      session = biz.store.get("session", task);
      String running = session.path("capability_operation").asText();
      ObjectNode existing = running.isEmpty() ? null : biz.store.get("cap-job", running);
      if (existing != null && "RUNNING".equals(existing.path("state").asText())) {
        if (!digest.equals(existing.path("digest").asText()))
          throw new Fault("RUN_BUSY", "当前会话有业务正在执行，请先查询进度", 409);
        result[0] = read(task, running);
        biz.store.create("cap-request", key, task, Json.obj("digest", digest, "operation_id", running));
        return session;
      }
      if ("investigation.get".equals(action) && !args.path("refresh").asBoolean()
          && session.path("investigation_ready").asBoolean()) {
        JsonNode saved = biz.results(task).get(0);
        ObjectNode op = biz.newOperation(task, "INVESTIGATION_SAVED");
        ArrayNode limits = Json.arr();
        for (JsonNode card : saved.path("dimensions")) {
          if (!Arrays.asList("AVAILABLE", "EMPTY").contains(card.path("data_status").asText()))
            limits.add(card.path("dimension").asText() + "：数据存在缺口");
          if ("NOT_GENERATED".equals(card.path("analysis_status").asText())
              && Arrays.asList("PROFILE", "CREDIT").contains(card.path("dimension").asText())) limits.add("AI总结未完成");
        }
        op.put("status", limits.isEmpty() ? "SUCCEEDED" : "PARTIAL"); op.set("result_id", saved.get("result_id"));
        op.set("result_version", saved.get("version"));
        biz.finish(task, op);
        result[0] = skillResult(op, dimensions(action), limits);
        biz.store.create("cap-job", op.path("operation_id").asText(), "worker", Json.obj(
            "task_id", task, "request_id", requestId, "action", action, "state", op.get("status"), "result", result[0], "input", args));
        biz.store.create("cap-request", key, task, Json.obj("digest", digest, "operation_id", op.get("operation_id")));
        return session;
      }
      ObjectNode operation = biz.newOperation(task, "QUEUED");
      String id = operation.path("operation_id").asText();
      String code = args.has("credit_code") ? args.path("credit_code").asText() : args.at("/subject/credit_code").asText();
      ObjectNode job = Json.obj("task_id", task, "owner", session.get("owner"), "action", action,
          "request_id", requestId, "http_request_id", Diagnostics.current("http_request_id"),
          "input", args, "digest", digest, "state", "RUNNING", "operation_id", id,
          "plan_version", PLAN, "base_version", biz.currentVersion(task, code),
          "created_at", Json.now(), "lease_until", 0, "lease_id", "", "attempts", 0);
      biz.store.create("cap-job", id, "worker", job);
      biz.store.create("cap-request", key, task, Json.obj("digest", digest, "operation_id", id));
      session.put("capability_operation", id);
      result[0] = skillResult(operation, dimensions(action), Json.arr());
      return session;
    });
    Diagnostics.info("job.accepted", "request_id", requestId, "task_id", task, "action", action,
        "operation_id", result[0].at("/operation/operation_id").asText(),
        "status", result[0].at("/operation/status").asText(), "stage", result[0].at("/operation/stage").asText());
    return result[0];
  }

  public ObjectNode read(String task, String id) {
    ObjectNode job = biz.owned("cap-job", id, task);
    if (job.has("result")) return Json.object(job.get("result").deepCopy());
    return skillResult(biz.operation(task, id), dimensions(job.path("action").asText()), Json.arr());
  }

  public static ArrayNode dimensions(String action) {
    if ("investigation.get".equals(action)) return Json.arr("PROFILE", "OPERATIONS", "FINANCIALS", "CREDIT", "BANKING", "LEGAL", "IP");
    return Json.arr(action.startsWith("financial.") ? "FINANCIALS" : "CREDIT");
  }

  public static ObjectNode skillResult(JsonNode operation, JsonNode dimensions, JsonNode limits) {
    return Json.obj("operation", operation, "dimensions", dimensions, "limitations", limits);
  }

  private void safeSweep() {
    try { sweep(); } catch (RuntimeException e) { Diagnostics.failure("jobs.sweep_failed", e); }
  }

  void sweep() {
    if (closing) return;
    for (ObjectNode job : biz.store.list("cap-job", "worker")) {
      if (active.size() >= 2) return;
      if (!"RUNNING".equals(job.path("state").asText()) || job.path("lease_until").asLong() > System.currentTimeMillis()) continue;
      String id = job.path("operation_id").asText();
      if (!active.add(id)) continue;
      workers.execute(() -> {
        try { runOnce(id); }
        catch (RuntimeException error) { Diagnostics.failure("job.worker_failed", error, "operation_id", id); }
        finally { active.remove(id); }
      });
    }
  }

  /** Also used by integration tests to simulate recovery and a second worker. */
  void runOnce(String id) {
    String lease = Json.id();
    final boolean[] claimed = {false};
    ObjectNode job = biz.store.update("cap-job", id, current -> {
      if (!"RUNNING".equals(current.path("state").asText()) || current.path("lease_until").asLong() > System.currentTimeMillis()) return current;
      current.put("lease_id", lease); current.put("lease_until", System.currentTimeMillis() + LEASE_MS);
      current.put("attempts", current.path("attempts").asInt() + 1); claimed[0] = true;
      return current;
    });
    if (!claimed[0]) return;
    Run run = new Run(id, lease, job);
    try (Diagnostics.Scope ignored = Diagnostics.scope("request_id", run.requestId,
        "http_request_id", job.path("http_request_id").asText(), "task_id", run.task,
        "operation_id", id, "action", run.action, "attempt", job.path("attempts").asInt())) {
      long started = System.nanoTime();
      Diagnostics.info("job.started");
      ScheduledFuture<?> heartbeat = clock.scheduleWithFixedDelay(() -> {
        try (Diagnostics.Scope heartbeatScope = Diagnostics.scope("request_id", run.requestId,
            "task_id", run.task, "operation_id", id, "action", run.action, "stage", run.stage)) {
          try { biz.store.update("cap-job", id, j -> { run.fence(j); j.put("lease_until", System.currentTimeMillis() + LEASE_MS); return j; }); }
          catch (RuntimeException e) {
            if (!run.lost) Diagnostics.failure("job.lease_lost", e);
            run.lost = true;
          }
          if (!run.lost && System.nanoTime() - run.lastProgress >= TimeUnit.SECONDS.toNanos(60)) {
            Diagnostics.info("job.waiting", "elapsed_ms", Diagnostics.elapsed(started));
            run.lastProgress = System.nanoTime();
          }
        }
      }, 10, 10, TimeUnit.SECONDS);
      try {
        if (!PLAN.equals(job.path("plan_version").asText()))
          throw new Fault("PLAN_CHANGED", "任务执行版本已变化，请重新提交");
        biz.session(run.task, job.path("owner").asText());
        Supplier<ObjectNode> publish = capabilities.execute(run);
        run.finish(publish);
        JsonNode result = run.completedResult;
        Diagnostics.info("job.completed", "status", result.at("/operation/status").asText(),
            "stage", result.at("/operation/stage").asText(), "limit_count", result.path("limitations").size(),
            "elapsed_ms", Diagnostics.elapsed(started));
      } catch (Exception error) {
        Diagnostics.failure("job.failed", error, "stage", run.stage, "elapsed_ms", Diagnostics.elapsed(started));
        if (!closing && !run.lost) {
          Fault fault = error instanceof Fault ? (Fault) error : new Fault("BUSINESS_EXECUTION_FAILED", "业务执行失败，请查询任务与材料");
          try { run.finish(() -> {
            ObjectNode operation = biz.operation(run.task, id);
            operation.put("status", "FAILED"); operation.put("stage", "FAILED");
            operation.set("error", Json.obj("code", fault.code, "message", fault.getMessage(), "retryable", false));
            return skillResult(operation, dimensions(run.action), Json.arr());
          });
            Diagnostics.info("job.failure_saved", "status", "FAILED", "error_code", fault.code);
          } catch (RuntimeException persistenceError) { Diagnostics.failure("job.failure_save_failed", persistenceError); }
        }
      } finally { heartbeat.cancel(false); }
    }
  }

  public final class Run {
    public final String id, task, action, baseVersion;
    public final JsonNode input;
    private final String lease;
    private volatile boolean lost;
    private final String requestId;
    private volatile String stage = "STARTING";
    private volatile long lastProgress = System.nanoTime();
    private ObjectNode completedResult;

    Run(String id, String lease, ObjectNode job) {
      this.id = id; this.lease = lease; this.task = job.path("task_id").asText();
      this.action = job.path("action").asText(); this.input = job.get("input");
      this.baseVersion = job.path("base_version").asText();
      this.requestId = job.path("request_id").asText();
    }

    void fence(ObjectNode job) {
      if (lost || closing || !"RUNNING".equals(job.path("state").asText())
          || !lease.equals(job.path("lease_id").asText()) || job.path("lease_until").asLong() <= System.currentTimeMillis())
        throw new Fault("LEASE_LOST", "任务执行权已变化，停止旧执行器", 409);
    }

    public JsonNode step(String key, String stage, Supplier<JsonNode> compute) {
      this.stage = stage;
      long started = System.nanoTime();
      try (Diagnostics.Scope ignored = Diagnostics.scope("stage", stage)) {
        ObjectNode live = biz.store.get("cap-job", id); fence(live);
        String stepId = Json.hash(Json.arr(id, key));
        ObjectNode saved = biz.store.get("cap-step", stepId);
        if (saved != null) {
          Diagnostics.info("job.step_reused", "step_id", stepId);
          return saved.get("value").deepCopy();
        }
        Diagnostics.info("job.step_started", "step_id", stepId);
        biz.store.update("cap-job", id, j -> {
          fence(j);
          ObjectNode op = biz.operation(task, id); op.put("stage", stage); biz.finish(task, op);
          return j;
        });
        JsonNode value = compute.get();
        biz.store.update("cap-job", id, j -> {
          fence(j);
          biz.store.create("cap-step", stepId, id, Json.obj("task_id", task, "value", value));
          return j;
        });
        Diagnostics.info("job.step_completed", "step_id", stepId, "elapsed_ms", Diagnostics.elapsed(started));
        return value.deepCopy();
      } catch (RuntimeException error) {
        Diagnostics.failure("job.step_failed", error, "stage", stage, "elapsed_ms", Diagnostics.elapsed(started));
        throw error;
      }
    }

    public ObjectNode operation(String status, String stage) {
      ObjectNode op = biz.operation(task, id);
      op.put("status", status); op.put("stage", stage); op.putNull("error");
      return op;
    }

    private void finish(Supplier<ObjectNode> publish) {
      stage = "PUBLISHING";
      Diagnostics.info("job.publishing", "stage", stage);
      biz.store.update("session", task, session -> {
        if (session.path("deleted").asBoolean()) throw new Fault("NOT_FOUND", "会话不存在", 404);
        biz.store.update("cap-job", id, j -> {
          fence(j);
          ObjectNode result = publish.get();
          biz.contracts.check("SkillResult", result);
          biz.finish(task, Json.object(result.get("operation")));
          j.set("result", result); j.put("state", result.at("/operation/status").asText());
          j.put("lease_until", 0); j.put("finished_at", Json.now());
          completedResult = result;
          return j;
        });
        if (id.equals(session.path("capability_operation").asText())) session.remove("capability_operation");
        // publish may have bound the subject/registered results in this same transaction.
        ObjectNode fresh = biz.store.get("session", task);
        fresh.remove("capability_operation");
        return fresh;
      });
    }
  }
}
