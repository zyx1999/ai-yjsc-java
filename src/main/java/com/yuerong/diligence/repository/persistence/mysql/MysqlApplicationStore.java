package com.yuerong.diligence.repository.persistence.mysql;

import com.yuerong.diligence.common.Fault;
import com.yuerong.diligence.common.Json;
import com.yuerong.diligence.repository.ApplicationStorePort;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class MysqlApplicationStore implements ApplicationStorePort {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public MysqlApplicationStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(manager);
  }

  public ObjectNode get(String kind, String id) {
    List<String> rows =
        jdbc.query(
            "select payload from diligence_document where kind=? and document_id=?",
            (r, n) -> r.getString(1),
            kind,
            id);
    return rows.isEmpty() ? null : Json.object(Json.parse(rows.get(0)));
  }

  public List<ObjectNode> list(String kind, String owner) {
    return jdbc.query(
        "select payload from diligence_document where kind=? and owner_id=? order by document_id",
        (r, n) -> Json.object(Json.parse(r.getString(1))),
        kind,
        owner);
  }

  public void create(String kind, String id, String owner, ObjectNode value) {
    if ("session".equals(kind)) {
      String now = Json.now();
      value.put("created_at", now);
      value.put("last_used_at", now);
    }
    try {
      jdbc.update(
          "insert into diligence_document(kind,document_id,owner_id,payload) values(?,?,?,?)",
          kind,
          id,
          owner,
          value.toString());
    } catch (org.springframework.dao.DuplicateKeyException e) {
      throw new Fault("VERSION_CONFLICT", "对象已存在，请刷新", 409);
    }
  }

  public ObjectNode update(String kind, String id, Function<ObjectNode, ObjectNode> edit) {
    return tx.execute(
        status -> {
          List<String> rows =
              jdbc.query(
                  "select payload from diligence_document where kind=? and document_id=? for"
                      + " update",
                  (r, n) -> r.getString(1),
                  kind,
                  id);
          if (rows.isEmpty()) throw new Fault("NOT_FOUND", "对象不存在", 404);
          ObjectNode value = edit.apply(Json.object(Json.parse(rows.get(0))));
          if ("session".equals(kind) && !value.path("deleted").asBoolean())
            value.put("last_used_at", Json.now());
          jdbc.update(
              "update diligence_document set payload=? where kind=? and document_id=?",
              value.toString(),
              kind,
              id);
          return value;
        });
  }
}
