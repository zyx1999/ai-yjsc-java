package com.yuerong.diligence.repository;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Function;

public interface ApplicationStorePort {
  ObjectNode get(String kind, String id);

  List<ObjectNode> list(String kind, String owner);

  void create(String kind, String id, String owner, ObjectNode value);

  ObjectNode update(String kind, String id, Function<ObjectNode, ObjectNode> edit);
}
