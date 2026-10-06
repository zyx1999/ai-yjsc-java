package com.yuerong.diligence.model.diligence.rules.investigation;

import java.math.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** V3 deterministic rules. Complete record sets are required; missing is never zero. */
public class DeterministicRules {
  public static final String VERSION = "REFERENCE-RULES-20260927";

  public static class Domain {
    public String status = "MISSING_SOURCE";
    public List<Map<String, Object>> records = new ArrayList<>();

    public Domain() {}

    public Domain(String status, List<Map<String, Object>> records) {
      this.status = status;
      this.records = records;
    }
  }

  public static class Result {
    public String rule_version = VERSION;
    public Map<String, Object> values = new LinkedHashMap<>();
    public Map<String, String> statuses = new LinkedHashMap<>();
    public List<Map<String, Object>> reference_signals = new ArrayList<>();
    public List<Map<String, Object>> template_jobs = new ArrayList<>();
    public List<String> limitations = new ArrayList<>();
  }

  public Result evaluate(Map<String, Domain> domains, LocalDate asOf) {
    Result out = new Result();
    run(out, 215, 216, () -> shareholders(out, require(domains, "shareholders")));
    run(
        out,
        217,
        221,
        () -> equity(out, require(domains, "shareholders"), require(domains, "pledges")));
    run(out, 94, 94, () -> taxRatings(out, require(domains, "tax_ratings"), asOf));
    run(out, 212, 212, () -> patentPledges(out, require(domains, "patent_pledges")));
    run(out, 222, 229, () -> changes(out, require(domains, "changes"), asOf));
    run(out, 235, 242, () -> loans(out, require(domains, "loans")));
    run(out, 245, 245, () -> guarantees(out, require(domains, "guarantees")));
    template(out, 230, new int[] {215, 216}, "借款企业共{215}名股东，{216}。");
    template(
        out,
        231,
        new int[] {222, 223, 224, 225, 226, 227, 228, 229},
        Objects.equals(out.values.get(code(222)), 0)
            ? "近一年，借款企业无工商变更记录。"
            : "近一年，借款企业共有{222}次工商信息变更记录，其中法定代表人变更{223}次、高管变更{224}次、注册地址变更{225}次、股权变更{226}次、实缴资本变更{227}次。最近一次变更日期为{228}，变更事项为{229}。");
    template(
        out,
        232,
        new int[] {215, 216, 217, 218, 219, 220, 221},
        "借款企业共{215}名股东，{216}。股权出质：{217}。整体对外出质比例为{218}%，涉及质权人共{219}名，累计质押数额最大质权人为{220}，持有比例达{221}%。");
    template(
        out,
        243,
        new int[] {235, 236, 237, 238, 239, 240, 241, 242},
        "企业名下贷款余额{235}万元，其中流动资金贷款{236}万元、占比{237}%，信用贷款{238}万元、占比{239}%。最大单笔用信所属金融机构为{240}。参考风险分类命中：{241}；对应余额{242}万元。该分类不等同于已确认逾期或不良贷款；其他角色及对外担保需分别核实。");
    if (out.values.get(code(235)) instanceof BigDecimal
        && ((BigDecimal) out.values.get(code(235))).signum() == 0) {
      out.template_jobs.remove(out.template_jobs.size() - 1);
      template(
          out, 243, new int[] {235}, "在本次企业贷款完整查询范围内，贷款余额为{235}万元。零余额时贷款占比不适用；其他主体及担保信息仍须分别核实。");
    }
    threshold(out, 222, 4, "CHANGE_COUNT", "近一年工商变更达到4次，请核实对经营的影响。");
    threshold(out, 223, 1, "LEGAL_CHANGE", "法定代表人发生变更，请核实对经营的影响。");
    threshold(out, 224, 6, "EXEC_CHANGE", "高管变更达到6次，请核实对经营的影响。");
    threshold(out, 225, 1, "ADDRESS_CHANGE", "注册地址发生变更，请核实对经营的影响。");
    threshold(out, 226, 4, "EQUITY_CHANGE", "股权变更达到4次，请核实对经营的影响。");
    threshold(out, 218, 25, "PLEDGE_25", "股权出质比例达到25%，请核实实际控制权影响。");
    threshold(out, 218, 75, "PLEDGE_75", "股权出质比例达到75%，请核实控制权影响；不据此作出授信决定。");
    threshold(out, 221, 50, "PLEDGEE_50", "单一质权人持有比例达到50%，请核实股权出质集中情况。");
    threshold(out, 239, 70, "CREDIT_70", "信用贷款占比达到70%，请关注担保方式。");
    if (Boolean.TRUE.equals(out.values.get(code(241))))
      signal(out, "LOAN_CLASSIFICATION", 241, "贷款存在关注或更差分类，需另行核实是否逾期或不良。");
    out.limitations.add("风险阈值仍为业务参考，不生成正式风险等级；五类主体必须分别按subject_id及role提供明细，不能用企业数据代替企业主/配偶。");
    return out;
  }

  private void shareholders(Result o, List<Map<String, Object>> rows) {
    Set<String> names = new HashSet<>();
    List<String> descriptions = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      String flag = required(r, "historical");
      if (!Arrays.asList("0", "1").contains(flag)) throw new IllegalArgumentException("股东历史标志待确认");
      if ("1".equals(flag)) continue;
      String name = required(r, "name");
      if (!names.add(name)) throw new IllegalArgumentException("同名股东关联不唯一，需主体ID");
      BigDecimal percent = number(r, "percent");
      if (percent.compareTo(BigDecimal.valueOf(100)) > 0)
        throw new IllegalArgumentException("股东持股比例超出100%");
      descriptions.add(name + "持股" + percent.toPlainString() + "%");
    }
    put(o, 215, names.size());
    put(o, 216, descriptions.isEmpty() ? "已确认无股东记录" : String.join("、", descriptions));
  }

  private void taxRatings(Result o, List<Map<String, Object>> rows, LocalDate asOf) {
    if (asOf == null) throw new IllegalArgumentException("纳税评级缺少固定调查时点");
    Map<Integer, String> ratings = new TreeMap<>(Comparator.reverseOrder());
    for (Map<String, Object> r : rows) {
      int year;
      try {
        year = Integer.parseInt(required(r, "year"));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("纳税评级年度无效");
      }
      if (year >= asOf.getYear() - 3
          && year < asOf.getYear()
          && ratings.put(year, required(r, "grade")) != null)
        throw new IllegalArgumentException("同年纳税评级不唯一，需确认取值口径");
    }
    List<String> values = new ArrayList<>();
    for (int year = asOf.getYear() - 1; year >= asOf.getYear() - 3; year--)
      values.add(year + "年：" + ratings.getOrDefault(year, "未取得评级"));
    put(o, 94, String.join("；", values));
  }

  private void patentPledges(Result o, List<Map<String, Object>> rows) {
    Set<String> ids = new HashSet<>();
    boolean active = false;
    for (Map<String, Object> r : rows) {
      if (!ids.add(required(r, "record_id"))) throw new IllegalArgumentException("专利质押登记号重复");
      String status = required(r, "status");
      if (!Arrays.asList("ACTIVE", "CANCELLED").contains(status))
        throw new IllegalArgumentException("专利质押有效状态待确认");
      active |= "ACTIVE".equals(status);
    }
    put(o, 212, active ? "企业存在有效专利质押登记；不代表每项专利均被质押" : "在本次完整查询范围内未发现有效专利质押登记");
  }

  private void equity(
      Result o, List<Map<String, Object>> shareholders, List<Map<String, Object>> pledges) {
    List<Map<String, Object>> ss =
        shareholders.stream()
            .filter(r -> !"1".equals(text(r, "historical")))
            .collect(Collectors.toList());
    // Missing/unknown filter flags must be rejected, not silently treated as active.
    for (Map<String, Object> r : shareholders)
      if (!Arrays.asList("0", "1").contains(text(r, "historical")))
        throw new IllegalArgumentException("股东历史标志待确认");
    Map<String, BigDecimal> capital = new LinkedHashMap<>();
    List<String> desc = new ArrayList<>();
    for (Map<String, Object> s : ss) {
      String name = required(s, "name");
      if (capital.containsKey(name)) throw new IllegalArgumentException("同名股东关联不唯一，需主体ID");
      if (!"CNY_10K".equals(required(s, "unit"))) throw new IllegalArgumentException("股东资本单位待规范化");
      capital.put(name, number(s, "capital"));
      desc.add(name + "持股" + number(s, "percent").toPlainString() + "%");
    }
    List<Map<String, Object>> ps = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Map<String, Object> p : pledges) {
      String status = required(p, "status");
      if (!Arrays.asList("1", "0", "正常", "有效", "已注销", "注销", "无效").contains(status))
        throw new IllegalArgumentException("出质状态字典待确认");
      if (Arrays.asList("1", "正常", "有效").contains(status)) {
        if (!ids.add(required(p, "record_id"))) throw new IllegalArgumentException("出质登记号重复");
        ps.add(p);
      }
    }
    BigDecimal total = sum(capital.values()), pledged = BigDecimal.ZERO;
    Map<String, BigDecimal> byPledgee = new LinkedHashMap<>(), byPledgor = new LinkedHashMap<>();
    for (Map<String, Object> p : ps) {
      if (!"CNY_10K".equals(required(p, "unit"))) throw new IllegalArgumentException("出质金额单位待规范化");
      BigDecimal amount = number(p, "amount");
      String pledgor = required(p, "pledgor"), pledgee = required(p, "pledgee");
      if (!capital.containsKey(pledgor)) throw new IllegalArgumentException("出质人无法唯一关联当前股东");
      byPledgor.merge(pledgor, amount, BigDecimal::add);
      byPledgee.merge(pledgee, amount, BigDecimal::add);
      pledged = pledged.add(amount);
    }
    for (String name : byPledgor.keySet())
      if (byPledgor.get(name).compareTo(capital.get(name)) > 0)
        throw new IllegalArgumentException("累计出质超过股东资本，需核实重复登记或计量口径");
    List<String> pledgeDesc = new ArrayList<>();
    for (String name : byPledgor.keySet())
      pledgeDesc.add(name + "的" + ratio(byPledgor.get(name), capital.get(name)) + "%股权已对外出质");
    put(o, 217, ps.isEmpty() ? "已确认当前无有效出质记录" : String.join("、", pledgeDesc));
    put(o, 218, ratio(pledged, total));
    put(o, 219, byPledgee.size());
    put(o, 220, maxNames(byPledgee));
    put(o, 221, ratio(max(byPledgee.values()), total));
  }

  private void changes(Result o, List<Map<String, Object>> records, LocalDate date) {
    if (date == null) throw new IllegalArgumentException("工商变更缺少固定调查时点");
    List<Map<String, Object>> recent = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Map<String, Object> r : records) {
      LocalDate d = date(required(r, "date"));
      if (d.isAfter(date)) throw new IllegalArgumentException("变更日期晚于调查时点");
      if (!ids.add(required(r, "record_id"))) throw new IllegalArgumentException("变更记录ID重复");
      if (!d.isBefore(date.minusYears(1))) recent.add(r);
    }
    put(o, 222, recent.size());
    String[] categories = {"legalRep", "executive", "address", "equity", "paidCapital"};
    for (int i = 0; i < categories.length; i++) {
      final String c = categories[i];
      put(o, 223 + i, recent.stream().filter(r -> c.equals(text(r, "category"))).count());
    }
    for (Map<String, Object> r : recent)
      if (!Arrays.asList("legalRep", "executive", "address", "equity", "paidCapital", "other")
          .contains(text(r, "category")))
        throw new IllegalArgumentException("工商变更类型需受控字典映射，不能猜测资本类型");
    LocalDate latest =
        recent.stream().map(r -> date(text(r, "date"))).max(LocalDate::compareTo).orElse(null);
    put(o, 228, latest == null ? "无" : latest.toString());
    List<String> details = new ArrayList<>();
    for (Map<String, Object> r : recent)
      if (date(text(r, "date")).equals(latest))
        details.add(
            required(r, "matter") + "由" + required(r, "before") + "变更为" + required(r, "after"));
    put(o, 229, details.isEmpty() ? "无" : String.join("；", details));
  }

  private void loans(Result o, List<Map<String, Object>> rows) {
    BigDecimal total = BigDecimal.ZERO,
        flow = BigDecimal.ZERO,
        credit = BigDecimal.ZERO,
        abnormal = BigDecimal.ZERO;
    Map<String, BigDecimal> entries = new LinkedHashMap<>();
    Set<String> ids = new HashSet<>();
    boolean hasAbnormal = false;
    boolean comparable = false;
    boolean productsKnown = true, guaranteesKnown = true, risksKnown = true, bankGroupsKnown = true;
    for (Map<String, Object> r : rows) {
      if (!"ENTERPRISE".equals(required(r, "role")))
        throw new IllegalArgumentException("企业指标不可混入其他主体贷款");
      if (!ids.add(required(r, "record_id"))) throw new IllegalArgumentException("融资明细ID重复");
      if (!"CNY_10K".equals(required(r, "unit")))
        throw new IllegalArgumentException("金额须先规范化为人民币万元");
      BigDecimal n = number(r, "balance");
      total = total.add(n);
      String institution = required(r, "institution");
      String product = text(r, "product");
      if ("流动资金贷款".equals(product)) flow = flow.add(n);
      else if (!"设备贷款".equals(product)) productsKnown = false;
      String security = text(r, "guarantee");
      if ("信用".equals(security)) credit = credit.add(n);
      else if (!Arrays.asList("保证", "抵押", "质押").contains(security)) guaranteesKnown = false;
      String risk = text(r, "risk_class").replace("类", "");
      if (!Arrays.asList("正常", "关注", "次级", "可疑", "损失").contains(risk)) risksKnown = false;
      else if (!"正常".equals(risk)) {
        abnormal = abnormal.add(n);
        hasAbnormal = true;
      }
      entries.merge(institution, n, BigDecimal::max);
      // Explicit normalized bank group, not substring matching names like 农商行.
      String bank = text(r, "bank_group");
      if (!Arrays.asList("ICBC", "ABC", "BOC", "CCB", "BOCOM", "PSBC", "OTHER").contains(bank))
        bankGroupsKnown = false;
      else if (!"OTHER".equals(bank)) comparable = true;
    }
    put(o, 235, total);
    put(o, 240, maxNames(entries));
    if (productsKnown) {
      put(o, 236, flow);
      if (total.signum() > 0) put(o, 237, ratio(flow, total));
      else o.statuses.put(code(237), "NOT_APPLICABLE");
    } else {
      o.statuses.put(code(236), "BLOCKED_INPUT");
      o.statuses.put(code(237), "BLOCKED_INPUT");
    }
    if (guaranteesKnown) {
      put(o, 238, credit);
      if (total.signum() > 0) put(o, 239, ratio(credit, total));
      else o.statuses.put(code(239), "NOT_APPLICABLE");
    } else {
      o.statuses.put(code(238), "BLOCKED_INPUT");
      o.statuses.put(code(239), "BLOCKED_INPUT");
    }
    if (risksKnown) {
      put(o, 241, hasAbnormal);
      put(o, 242, abnormal);
    } else {
      o.statuses.put(code(241), "BLOCKED_INPUT");
      o.statuses.put(code(242), "BLOCKED_INPUT");
    }
    if (bankGroupsKnown && !rows.isEmpty() && !comparable)
      signal(o, "NO_COMPARABLE_BANK", 240, "合作机构未包含配置的六家可比同业，请核实；不等同于已被拒贷。");
  }

  private void guarantees(Result o, List<Map<String, Object>> rows) {
    BigDecimal total = BigDecimal.ZERO;
    Set<String> ids = new HashSet<>();
    for (Map<String, Object> r : rows) {
      if (!ids.add(required(r, "record_id"))) throw new IllegalArgumentException("担保明细ID重复");
      if (!"CNY_10K".equals(required(r, "unit"))) throw new IllegalArgumentException("担保金额单位不明");
      if (!"ENTERPRISE".equals(required(r, "role")))
        throw new IllegalArgumentException("企业担保不可混入其他主体");
      String risk = required(r, "risk_class").replace("类", "");
      if (!Arrays.asList("正常", "关注", "次级", "可疑", "损失").contains(risk))
        throw new IllegalArgumentException("担保风险分类待确认");
      BigDecimal n = number(r, "balance");
      total = total.add(n);
      if (!"正常".equals(risk))
        signal(o, "GUARANTEE_CLASSIFICATION", 245, "对外担保存在关注或更差分类，请核实担保责任及风险。");
    }
    o.values.put("guarantee_total", total);
  }

  private void run(Result o, int first, int last, Runnable fn) {
    int signalsBefore = o.reference_signals.size();
    try {
      fn.run();
    } catch (IllegalArgumentException e) {
      for (int i = first; i <= last; i++) {
        o.values.remove(code(i));
        o.statuses.put(code(i), "BLOCKED_INPUT");
      }
      while (o.reference_signals.size() > signalsBefore)
        o.reference_signals.remove(o.reference_signals.size() - 1);
      o.limitations.add(e.getMessage());
    }
  }

  private List<Map<String, Object>> require(Map<String, Domain> domains, String name) {
    Domain d = domains.get(name);
    if (d == null || !Arrays.asList("AVAILABLE", "VERIFIED_NONE").contains(d.status))
      throw new IllegalArgumentException(name + "：" + (d == null ? "MISSING_SOURCE" : d.status));
    if (d.records == null || d.records.contains(null))
      throw new IllegalArgumentException(name + "：缺少完整明细数组");
    if ("VERIFIED_NONE".equals(d.status) != d.records.isEmpty())
      throw new IllegalArgumentException(name + "：数据状态与明细矛盾");
    return d.records;
  }

  private void template(Result o, int row, int[] deps, String text) {
    Map<String, Object> job = new LinkedHashMap<>();
    job.put("field_code", code(row));
    job.put("generation_mode", "CODE_TEMPLATE");
    job.put("template", text);
    Map<String, Object> params = new LinkedHashMap<>();
    boolean ok = true;
    for (int n : deps) {
      params.put(String.valueOf(n), o.values.get(code(n)));
      if (!"CURRENT".equals(o.statuses.get(code(n)))) ok = false;
    }
    job.put("params", params);
    job.put("status", ok ? "READY" : "BLOCKED_INPUT");
    o.statuses.put(code(row), ok ? "READY" : "BLOCKED_INPUT");
    o.template_jobs.add(job);
  }

  private void threshold(Result o, int row, int value, String id, String text) {
    Object n = o.values.get(code(row));
    if (n instanceof Number
        && new BigDecimal(n.toString()).compareTo(BigDecimal.valueOf(value)) >= 0)
      signal(o, id, row, text);
  }

  private void signal(Result o, String id, int row, String text) {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("rule_code", "REFERENCE_" + id);
    s.put("status", "REFERENCE_ONLY");
    s.put("depends_on", Collections.singletonList(code(row)));
    s.put("message", text);
    o.reference_signals.add(s);
  }

  private static void put(Result o, int row, Object value) {
    o.values.put(code(row), value);
    o.statuses.put(code(row), "CURRENT");
  }

  public static String code(int row) {
    return String.format("rule.r%03d", row);
  }

  private static String text(Map<String, Object> r, String key) {
    Object v = r.get(key);
    return v == null ? "" : v.toString().trim();
  }

  private static String required(Map<String, Object> r, String key) {
    String v = text(r, key);
    if (v.isEmpty()) throw new IllegalArgumentException("缺少明细字段：" + key);
    return v;
  }

  private static BigDecimal number(Map<String, Object> r, String key) {
    try {
      BigDecimal n = new BigDecimal(required(r, key));
      if (n.signum() < 0) throw new IllegalArgumentException("负数金额/比例：" + key);
      return n;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("非法数值：" + key);
    }
  }

  private static BigDecimal ratio(BigDecimal a, BigDecimal b) {
    if (b.signum() <= 0) throw new IllegalArgumentException("比例分母缺失或非正，不得生成0%");
    return a.multiply(BigDecimal.valueOf(100)).divide(b, 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal sum(Collection<BigDecimal> v) {
    return v.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal max(Collection<BigDecimal> v) {
    return v.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
  }

  private static String maxNames(Map<String, BigDecimal> v) {
    BigDecimal max = max(v.values());
    return v.isEmpty()
        ? "无"
        : v.entrySet().stream()
            .filter(e -> e.getValue().compareTo(max) == 0)
            .map(Map.Entry::getKey)
            .sorted()
            .collect(Collectors.joining("、"));
  }

  private static LocalDate date(String v) {
    try {
      return LocalDate.parse(
          v.length() == 8 ? v : v.substring(0, 10),
          v.length() == 8 ? DateTimeFormatter.BASIC_ISO_DATE : DateTimeFormatter.ISO_LOCAL_DATE);
    } catch (Exception e) {
      throw new IllegalArgumentException("日期格式无效：" + v);
    }
  }
}
