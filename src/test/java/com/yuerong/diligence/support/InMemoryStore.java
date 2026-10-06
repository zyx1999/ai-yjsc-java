package com.yuerong.diligence.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuerong.diligence.common.*;
import com.yuerong.diligence.repository.ApplicationStorePort;
import java.util.*;
import java.util.function.Function;

/** Isolated application-service fixture; it does not claim JDBC/transaction coverage. */
public final class InMemoryStore implements ApplicationStorePort {
  private final Map<String,ObjectNode> values = new LinkedHashMap<>();
  private final Map<String,String> owners = new HashMap<>();
  private String key(String kind, String id) { return kind + ":" + id; }
  public synchronized ObjectNode get(String kind, String id) {
    ObjectNode v = values.get(key(kind,id)); return v == null ? null : v.deepCopy();
  }
  public synchronized List<ObjectNode> list(String kind, String owner) {
    List<ObjectNode> out = new ArrayList<>();
    values.forEach((key,value) -> { if(key.startsWith(kind+":") && owner.equals(owners.get(key))) out.add(value.deepCopy()); });
    return out;
  }
  public synchronized void create(String kind, String id, String owner, ObjectNode value) {
    String key=key(kind,id);
    if(values.containsKey(key)) throw new Fault("VERSION_CONFLICT","对象已存在",409);
    values.put(key,value.deepCopy()); owners.put(key,owner);
  }
  public synchronized ObjectNode update(String kind, String id, Function<ObjectNode,ObjectNode> edit) {
    ObjectNode previous=get(kind,id);
    if(previous==null) throw new Fault("NOT_FOUND","对象不存在",404);
    ObjectNode next=edit.apply(previous); values.put(key(kind,id),next.deepCopy());return next.deepCopy();
  }
}
