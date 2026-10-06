package com.yuerong.diligence.ai.platform.oneagent;

import com.yuerong.diligence.common.Fault;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OneAgentFilesTest {
  @Test
  void derivesFilesBaseFromMessageUrl() {
    assertEquals(
        "http://aiml-pub.aisp.test.abc/oneagnet-test/api/v1/files",
        OneAgentFiles.filesBase("http://aiml-pub.aisp.test.abc/oneagnet-test/api/v1/message"));
    assertEquals("", OneAgentFiles.filesBase(""));
    assertEquals("", OneAgentFiles.filesBase("http://host/custom/chat"));
  }

  @Test
  void attachmentReferenceKeepsNameAndRelativePath() {
    String reference = BankOneAgentAdapter.attachmentReference("年度财报.pdf", "sub/年度财报.pdf", "");
    assertTrue(reference.contains("年度财报.pdf"));
    assertTrue(reference.contains("sub/年度财报.pdf"));
  }

  @Test
  void uploadRequiresConfiguredBase() {
    OneAgentFiles files = new OneAgentFiles("", "{}", 1000);
    assertEquals(
        "PLATFORM_NOT_CONFIGURED",
        assertThrows(Fault.class, () -> files.upload("s", "a.pdf", new byte[] {1})).code);
  }
}
