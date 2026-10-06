package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Bounded, resumable PDF transport for platform-only uploads through the same fixed URL. */
@Service
public class GatewayFiles {
  public static final int CHUNK_SIZE = 131072;
  private final DiligenceService biz;
  private final FilesService files;
  public GatewayFiles(DiligenceService biz, FilesService files) { this.biz = biz; this.files = files; }

  public ObjectNode begin(String task, String request, JsonNode args) {
    biz.contracts.check("FileBeginInput", args);
    biz.requireInvestigation(task, args.path("credit_code").asText());
    String name = args.path("name").asText();
    if (name.contains("/") || name.contains("\\") || name.contains("\n") || name.contains("\r") || name.indexOf(0) >= 0)
      throw new Fault("INVALID_ARGUMENT", "文件名无效");
    String id = "upload-" + Json.hash(Json.arr(task, request));
    ObjectNode old = biz.store.get("file-transfer", id);
    if (old == null) {
      try { biz.store.create("file-transfer", id, task, Json.obj("task_id", task, "input", args,
          "expires", System.currentTimeMillis() + 3600000, "completed", false)); }
      catch (Fault e) { if (!"VERSION_CONFLICT".equals(e.code)) throw e; }
      old = biz.owned("file-transfer", id, task);
    }
    if (!old.path("input").equals(args)) throw new Fault("IDEMPOTENCY_CONFLICT", "同次上传的文件内容改变", 409);
    if (!old.path("completed").asBoolean() && old.path("expires").asLong() <= System.currentTimeMillis())
      throw new Fault("UPLOAD_EXPIRED", "上传会话过期，请重新上传", 409);
    ObjectNode result = Json.obj("upload_id", id, "chunk_size", CHUNK_SIZE, "completed", old.path("completed").asBoolean());
    if (old.has("file")) result.set("file", old.get("file"));
    return result;
  }

  public ObjectNode part(String task, JsonNode args) {
    biz.contracts.check("FilePartInput", args);
    String id = args.path("upload_id").asText();
    biz.owned("file-transfer", id, task);
    byte[] bytes;
    try { bytes = Base64.getDecoder().decode(args.path("base64").asText()); }
    catch (IllegalArgumentException e) { throw new Fault("INVALID_ARGUMENT", "文件分片编码无效"); }
    int index = args.path("index").asInt();
    biz.store.update("file-transfer", id, transfer -> {
      check(transfer);
      int size = transfer.at("/input/size").asInt();
      if (index * CHUNK_SIZE >= size || bytes.length != Math.min(CHUNK_SIZE, size - index * CHUNK_SIZE))
        throw new Fault("INVALID_ARGUMENT", "文件分片大小或序号无效");
      String key = Json.hash(Json.arr(id, index));
      ObjectNode old = biz.store.get("file-part", key);
      String hash = Json.hash(bytes);
      if (old != null && !hash.equals(old.path("sha256").asText())) throw new Fault("IDEMPOTENCY_CONFLICT", "上传分片内容改变", 409);
      if (old == null) biz.store.create("file-part", key, id, Json.obj("base64", args.get("base64"), "sha256", hash));
      return transfer;
    });
    return Json.obj("upload_id", id, "index", index, "sha256", Json.hash(bytes));
  }

  public ObjectNode complete(String task, JsonNode args) {
    biz.contracts.check("FileCompleteInput", args);
    String id = args.path("upload_id").asText(); biz.owned("file-transfer", id, task);
    ObjectNode transfer = biz.store.update("file-transfer", id, current -> {
      if (current.path("completed").asBoolean()) return current;
      check(current); JsonNode input = current.get("input");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try {
        int size = input.path("size").asInt();
        for (int i = 0; i < (size + CHUNK_SIZE - 1) / CHUNK_SIZE; i++) {
          ObjectNode part = biz.store.get("file-part", Json.hash(Json.arr(id, i)));
          if (part == null) throw new Fault("UPLOAD_INCOMPLETE", "文件分片尚未全部到达", 409);
          byte[] bytes = Base64.getDecoder().decode(part.path("base64").asText());
          if (!Json.hash(bytes).equals(part.path("sha256").asText())) throw new Fault("FILE_CORRUPTED", "文件分片校验失败");
          out.write(bytes);
        }
        byte[] content = out.toByteArray();
        if (content.length != size || !Json.hash(content).equals(input.path("sha256").asText())) throw new Fault("FILE_CORRUPTED", "文件完整性校验失败");
        ObjectNode file = files.register(task, input.path("credit_code").asText(), input.path("role").asText(), input.path("name").asText(), content);
        current.set("file", file); current.put("completed", true);
      } catch (IOException e) { throw new Fault("FILE_UNAVAILABLE", "原件归档未完成"); }
      return current;
    });
    return Json.object(transfer.get("file").deepCopy());
  }

  private void check(ObjectNode transfer) {
    if (transfer.path("completed").asBoolean()) throw new Fault("UPLOAD_COMPLETED", "文件已经归档", 409);
    if (transfer.path("expires").asLong() <= System.currentTimeMillis()) throw new Fault("UPLOAD_EXPIRED", "上传会话过期", 409);
  }
}
