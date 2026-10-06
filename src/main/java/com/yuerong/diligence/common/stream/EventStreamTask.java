package com.yuerong.diligence.common.stream;

/** A scenario owns its execution and persistence; transport owns delivery and scheduling. */
public interface EventStreamTask {
  void execute(EventSink sink) throws Exception;
  void rejected();
  default Object[] diagnosticContext() { return new Object[0]; }
}
