package com.yuerong.diligence.service.diligence;

import com.yuerong.diligence.common.Diagnostics;
import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class FilesService {
  private final Path root;
  private final DiligenceService biz;

  public FilesService(
      DiligenceService biz, @Value("${diligence.files.root:./storage/diligence}") String root)
      throws IOException {
    this.biz = biz;
    this.root = Paths.get(root).toAbsolutePath().normalize();
    java.nio.file.Files.createDirectories(this.root);
  }

  public ObjectNode upload(String task, String code, String role, String name, byte[] content)
      throws IOException {
    biz.subject(code);
    if (!java.util.Arrays.asList("FINANCIAL", "CREDIT", "BANK_FLOW").contains(role))
      throw new Fault("INVALID_ARGUMENT", "请指定材料用途");
    if (content.length == 0 || content.length > 10485760)
      throw new Fault("INVALID_ARGUMENT", "文件须在10MiB以内");
    return register(task, code, role, name, content);
  }

  public ObjectNode register(String task, String code, String role, String name, byte[] bytes)
      throws IOException {
    String id = Json.id();
    return traced(task, id, "register", () -> registerFile(task, code, role, name, bytes, id));
  }

  private ObjectNode registerFile(String task, String code, String role, String name, byte[] bytes, String id)
      throws IOException {
    Diagnostics.info("file.validating", "bytes", bytes.length, "role", role);
    biz.subject(code);
    biz.requireInvestigation(task, code);
    if (!java.util.Arrays.asList("FINANCIAL", "CREDIT", "BANK_FLOW").contains(role)
        || bytes.length == 0
        || bytes.length > 10485760) throw new Fault("INVALID_ARGUMENT", "材料用途或大小无效");
    int pages;
    try (PDDocument doc = PDDocument.load(bytes)) {
      if (doc.isEncrypted()) throw new Fault("INVALID_ARGUMENT", "暂不接受加密PDF");
      pages = doc.getNumberOfPages();
      if (pages < 1 || pages > 100) throw new Fault("INVALID_ARGUMENT", "PDF须为1至100页");
    } catch (IOException e) {
      throw new Fault("INVALID_ARGUMENT", "请上传有效PDF", 400, e);
    }
    Diagnostics.info("file.writing", "pages", pages, "bytes", bytes.length);
    java.nio.file.Files.write(root.resolve(id), bytes, StandardOpenOption.CREATE_NEW);
    ObjectNode data =
        Json.obj(
            "task_id",
            task,
            "file_id",
            id,
            "credit_code",
            code,
            "role",
            role,
            "name",
            name == null ? "材料.pdf" : Paths.get(name).getFileName().toString(),
            "mime",
            "application/pdf",
            "pages",
            pages,
            "sha256",
            Json.hash(bytes));
    biz.store.create("file", id, task, data);
    Diagnostics.info("file.registered", "pages", pages, "bytes", bytes.length);
    return data;
  }

  public byte[] read(String task, String id) throws IOException {
    return traced(task, id, "read", () -> {
      Diagnostics.info("file.lookup");
      biz.owned("file", id, task);
      Diagnostics.info("file.reading");
      byte[] bytes = java.nio.file.Files.readAllBytes(root.resolve(id));
      Diagnostics.info("file.loaded", "bytes", bytes.length);
      return bytes;
    });
  }

  public ObjectNode fragments(String task, String id) throws IOException {
    return traced(task, id, "parse_pdf", () -> fragmentsFile(task, id));
  }

  private ObjectNode fragmentsFile(String task, String id) throws IOException {
    ObjectNode meta = biz.owned("file", id, task);
    ArrayNode fragments = Json.arr();
    try (PDDocument doc = PDDocument.load(read(task, id))) {
      PDFTextStripper strip = new PDFTextStripper();
      for (int i = 1; i <= doc.getNumberOfPages(); i++) {
        strip.setStartPage(i);
        strip.setEndPage(i);
        String text = strip.getText(doc);
        if (text.length() > 20000) throw new Fault("FILE_TOO_COMPLEX", "单页文本超出处理范围，请拆分材料后重试");
        fragments.add(
            Json.obj("evidence_id", id + "-p" + i, "source_id", id, "page", i, "text", text));
      }
    }
    Diagnostics.info("file.parsed", "pages", fragments.size());
    return Json.obj("metadata", meta, "fragments", fragments);
  }

  public ObjectNode page(String task, String id, int page) throws IOException {
    try (Diagnostics.Scope ignored = Diagnostics.scope("page", page)) {
      return traced(task, id, "render_page", () -> renderPage(task, id, page));
    }
  }

  private ObjectNode renderPage(String task, String id, int page) throws IOException {
    ObjectNode meta = biz.owned("file", id, task);
    if (page < 1 || page > meta.path("pages").asInt())
      throw new Fault("INVALID_ARGUMENT", "页码超出范围");
    try (PDDocument doc = PDDocument.load(read(task, id))) {
      org.apache.pdfbox.pdmodel.common.PDRectangle box = doc.getPage(page - 1).getMediaBox();
      if (box.getWidth() > 2000 || box.getHeight() > 2000)
        throw new Fault("INVALID_ARGUMENT", "页面尺寸过大，请拆分材料");
      java.awt.image.BufferedImage image =
          new org.apache.pdfbox.rendering.PDFRenderer(doc).renderImageWithDPI(page - 1, 120);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      javax.imageio.ImageIO.write(image, "png", out);
      if (out.size() > 4194304) throw new Fault("INVALID_ARGUMENT", "渲染页过大，请压缩材料");
      Diagnostics.info("file.rendered", "bytes", out.size());
      return Json.obj(
          "name",
          id + "-p" + page + ".png",
          "mime",
          "image/png",
          "base64",
          java.util.Base64.getEncoder().encodeToString(out.toByteArray()));
    }
  }

  private interface FileAction<T> { T run() throws IOException; }

  private <T> T traced(String task, String id, String stage, FileAction<T> action) throws IOException {
    long started = System.nanoTime();
    try (Diagnostics.Scope ignored = Diagnostics.scope("task_id", task, "file_id", id, "stage", stage)) {
      Diagnostics.info("file.started");
      try {
        T result = action.run();
        Diagnostics.info("file.completed", "elapsed_ms", Diagnostics.elapsed(started));
        return result;
      } catch (IOException | RuntimeException error) {
        Diagnostics.failure("file.failed", error, "elapsed_ms", Diagnostics.elapsed(started));
        throw error;
      }
    }
  }

  public ObjectNode export(String task, JsonNode args) throws IOException {
    biz.contracts.check("ExportInput", args);
    ObjectNode result = biz.read(task, argsToRead(args));
    String key = Json.hash(Json.arr(task, args.get("idempotency_key")));
    ObjectNode existing = biz.store.get("export", key);
    if (existing != null) {
      if (!existing.path("digest").asText().equals(Json.hash(args)))
        throw new Fault("IDEMPOTENCY_CONFLICT", "导出请求内容改变", 409);
      return Json.object(existing.get("data"));
    }
    ObjectNode op = biz.newOperation(task, "EXPORT_DOCX");
    String id = Json.id();
    try (XWPFDocument doc = new XWPFDocument()) {
      paragraph(doc, "企业调查表", true);
      paragraph(
          doc,
          result.at("/subject/enterprise_name").asText()
              + " / "
              + result.at("/subject/credit_code").asText(),
          false);
      paragraph(doc, "版本：" + result.path("version").asText(), false);
      for (JsonNode dim : biz.contracts.schema.path("x-dimensions")) {
        paragraph(doc, dim.path("label").asText(), true);
        JsonNode card = null;
        for (JsonNode c : result.path("dimensions"))
          if (c.path("dimension").equals(dim.get("code"))) card = c;
        if (card == null) {
          paragraph(doc, "尚未查询", false);
          continue;
        }
        paragraph(
            doc,
            "数据："
                + card.path("data_status").asText()
                + "；分析："
                + card.path("analysis_status").asText(),
            false);
        for (JsonNode l : card.path("limitations")) paragraph(doc, l.asText(), false);
        for (JsonNode g : card.path("groups")) {
          paragraph(doc, g.path("title").asText() + " / " + g.path("data_status").asText(), true);
          if (!g.path("complete").asBoolean()) paragraph(doc, "仅包含已保存的部分数据，未返回内容未纳入导出", false);
          for (JsonNode l : g.path("limitations")) paragraph(doc, l.asText(), false);
          for (JsonNode row : g.path("rows")) {
            paragraph(doc, row.path("subject_name").asText(), false);
            if (row.path("fields").size() > 0) {
              JsonNode context = row.path("fields").get(0).path("context");
              if (context.hasNonNull("period"))
                paragraph(
                    doc,
                    "期间："
                        + context.at("/period/start").asText()
                        + " 至 "
                        + context.at("/period/end").asText()
                        + "；口径："
                        + context.path("scope").asText()
                        + "；币种/单位："
                        + context.path("currency").asText()
                        + " / "
                        + context.path("unit").asText(),
                    false);
            }
            XWPFTable table = doc.createTable();
            table.setWidth(9000);
            for (JsonNode f : row.path("fields")) {
              XWPFTableRow r = table.createRow();
              while (r.getTableCells().size() < 2) r.createCell();
              r.getCell(0).setColor("EEF4F2");
              r.getCell(0).setText(f.path("label").asText());
              r.getCell(1)
                  .setText(
                      f.path("value").isNull()
                          ? f.path("missing_reason").asText()
                          : f.path("value").asText());
            }
            table.removeRow(0);
          }
        }
        for (JsonNode f : card.path("metrics"))
          paragraph(
              doc,
              f.path("label").asText()
                  + "："
                  + (f.path("value").isNull()
                      ? f.path("missing_reason").asText()
                      : f.path("value").asText()),
              false);
        for (JsonNode f : card.path("summaries"))
          paragraph(
              doc,
              f.path("label").asText()
                  + "："
                  + (f.path("value").isNull()
                      ? f.path("missing_reason").asText()
                      : f.path("value").asText()),
              false);
        for (JsonNode ev : card.path("evidence"))
          paragraph(
              doc,
              "依据：" + ev.path("source_id").asText() + " " + ev.path("locator").asText(),
              false);
      }
      try (OutputStream out =
          java.nio.file.Files.newOutputStream(root.resolve(id), StandardOpenOption.CREATE_NEW)) {
        doc.write(out);
      }
    }
    biz.store.create(
        "file",
        id,
        task,
        Json.obj(
            "task_id",
            task,
            "file_id",
            id,
            "credit_code",
            result.at("/subject/credit_code"),
            "role",
            "EXPORT",
            "name",
            "企业调查表.docx",
            "mime",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    op.put("status", "SUCCEEDED");
    op.put("file_id", id);
    op.set("result_id", result.get("result_id"));
    op.set("result_version", result.get("version"));
    biz.finish(task, op);
    ObjectNode data = Json.obj("operation", op, "file_id", id);
    biz.store.create("export", key, task, Json.obj("digest", Json.hash(args), "data", data));
    return data;
  }

  private ObjectNode argsToRead(JsonNode args) {
    return Json.obj("result_id", args.get("result_id"), "version", args.get("version"));
  }

  private void paragraph(XWPFDocument doc, String text, boolean bold) {
    XWPFRun run = doc.createParagraph().createRun();
    run.setBold(bold);
    run.setFontFamily("宋体");
    run.setFontSize(bold ? 14 : 10);
    run.setText(text);
  }
}
