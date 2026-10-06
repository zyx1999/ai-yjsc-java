#!/usr/bin/env node
/**
 * 行内自主编排平台本地 Mock 服务（零依赖，Node 8+，兼容 Node 10.14.2）
 *
 * 与 ai-yjsc-java/tools/mock-yunxia-server.js 同构，按 tyy/backend 的行内平台协议做了对接适配，
 * 用于不依赖行内网络的本地联调：
 *   - POST {任意前缀}/api/v1/message        （SSE：start / progress / message / trace / done，报文与真实平台对齐）
 *   - POST {任意前缀}/api/v1/files/upload   （multipart，内存 Workspace）
 *   - GET  {任意前缀}/api/v1/files/list
 *   - GET  {任意前缀}/api/v1/files/content
 *   - DELETE {任意前缀}/api/v1/files
 *
 * 用法：
 *   node tools/mock-yunxia-server.js [--port 18784] [--delay 300] [--slow-seconds 3] [--hang-seconds 20] [--pending-seconds 120] [--heartbeat-seconds 5]
 *
 * 后端对接（tyy/backend）：
 *   # 默认 application.yml 已指向 18784，无需改动即可直接联调：
 *   #   diligence.platform.adapter = agent-service
 *   #   diligence.platform.url     = http://127.0.0.1:18784/api/v1/message
 *   mvn spring-boot:run
 *   （files 地址会由 /message 自动推导为 /files，无需单独配置；如已配置 files-url 则指向同端口即可）
 *
 * 场景关键词（发送含以下词语的消息验证不同链路）：
 *   尽调 / 调查 / 企业   → progress: skill_loaded(investigation) + workflow_called + 尽调话术
 *   查询 / 帮我          → progress: skill_loaded(mock-skill)
 *   敏感                 → message(ok=false, POLICY_SENSITIVE_WORD_BLOCKED)：策略拦截
 *   限流                 → message(ok=false, MODEL_RATE_LIMITED)：模型限流
 *   断流                 → start 后直接断开（验证客户端“结果未知”）
 *   超时                 → start 后挂起不发送（验证客户端读取超时；可配合调小 diligence.platform.timeout-ms）
 *   慢                   → message 前等待 --slow-seconds 秒（验证长耗时链路）
 *   挂起 / pending       → 保持连接 pending（每 --heartbeat-seconds 秒发心跳帧），
 *                          持续 --pending-seconds 秒后正常返回结果
 *                          （验证前端长时间“events pending”时页面不卡死、结束后正常渲染）
 */
'use strict'

const http = require('http')
const url = require('url')

const args = process.argv.slice(2)
function argValue (name, fallback) {
  const index = args.indexOf(name)
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback
}

const PORT = parseInt(argValue('--port', '18784'), 10)
const BASE_DELAY_MS = parseInt(argValue('--delay', '300'), 10)
const SLOW_SECONDS = parseInt(argValue('--slow-seconds', '3'), 10)
const HANG_SECONDS = parseInt(argValue('--hang-seconds', '20'), 10)
/** 挂起场景总时长与心跳间隔（默认 120s / 5s）：保持连接 pending 后正常返回。 */
const PENDING_SECONDS = parseInt(argValue('--pending-seconds', '120'), 10)
const HEARTBEAT_SECONDS = parseInt(argValue('--heartbeat-seconds', '5'), 10)

/** sessionId -> Map(相对路径 -> {name, size, content})，模拟平台会话 Workspace */
const workspaces = new Map()
const turns = new Map()

function log () {
  console.log('[mock] ' + Array.prototype.join.call(arguments, ' '))
}

function delay (ms) {
  return new Promise(function (resolve) { setTimeout(resolve, ms) })
}

function randomHex (length) {
  let out = ''
  const chars = '0123456789abcdef'
  for (let i = 0; i < length; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length))
  }
  return out
}

function nextTurnId (sessionId) {
  const next = (turns.get(sessionId) || 0) + 1
  turns.set(sessionId, next)
  return 'turn_' + ('000' + next).slice(-4)
}

function sse (res, event, data) {
  res.write('event: ' + event + '\n')
  res.write('data: ' + data + '\n\n')
}

function sendJson (res, code, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8')
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length
  })
  res.end(body)
}

function collect (req) {
  return new Promise(function (resolve) {
    const chunks = []
    req.on('data', function (chunk) { chunks.push(chunk) })
    req.on('end', function () { resolve(Buffer.concat(chunks)) })
  })
}

function getWorkspace (sessionId) {
  let workspace = workspaces.get(sessionId)
  if (!workspace) {
    workspace = new Map()
    workspaces.set(sessionId, workspace)
  }
  return workspace
}

/** 是否命中“尽调/调查”业务链（用于 skill_loaded + workflow_called 进度事件与业务话术）。 */
function isInvestigationIntent (txt) {
  return /尽调|调查|企业|征信/.test(txt)
}

function buildAnswer (txt) {
  if (isInvestigationIntent(txt)) {
    return '（Mock）已识别企业尽调意图：「' + txt + '」\n\n请补充待调查企业名称与统一社会信用代码，我将启动企业尽调分析。\n\n当前为本地 Mock 平台服务（tyy/backend/tools/mock-yunxia-server.js）。'
  }
  return '您好！这是本地 Mock 大模型应答。已收到：「' + txt + '」\n\n当前连接的是本地 Mock 平台服务（tyy/backend/tools/mock-yunxia-server.js）：支持 start / progress / message / trace / done 帧；可用关键词验证场景：尽调、敏感、限流、断流、超时、慢。'
}

/** trace 帧（报文与真实平台同构，字段做了精简） */
function sendTraceFrame (res, sessionId, requestId, txt, turnId) {
  sse(res, 'trace', JSON.stringify({
    session_id: sessionId,
    request_id: requestId,
    turn_id: turnId,
    agent_version: 'mock',
    execution_trace: {
      event_count: 2,
      events: [
        {
          seq: 1, event_type: 'mock.request', node_name: 'mock', node_type: 'model',
          phase: 'input', data: { user_input: { user_input: txt } }, agent_name: ''
        },
        {
          seq: 2, event_type: 'mock.response', node_name: 'mock', node_type: 'model',
          phase: 'output', data: {}, agent_name: ''
        }
      ]
    }
  }))
}

async function handleMessage (req, res) {
  const raw = (await collect(req)).toString('utf8')
  let payload = {}
  try {
    payload = JSON.parse(raw || '{}')
  } catch (error) {
    sendJson(res, 400, { success: false, message: 'invalid json body' })
    return
  }
  const sessionId = String(payload.sessionId || 'mock-session')
  const txt = String(payload.txt || '')
  const debugTrace = payload.debugTrace !== false
  const requestId = randomHex(32)
  const turnId = nextTurnId(sessionId)
  log('POST message session=' + sessionId + ' turn=' + turnId + ' txt=' + JSON.stringify(txt)
    + ' vars=' + JSON.stringify(variableNames(payload.config_variables))
    + ' executionMode=' + JSON.stringify(payload.executionMode || ''))

  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no'
  })
  sse(res, 'start', JSON.stringify({ session_id: sessionId, request_id: requestId }))
  await delay(BASE_DELAY_MS)

  // 场景：断流（start 后直接断开，无 done）
  if (txt.indexOf('断流') >= 0) {
    log('  -> 场景：断流（直接断开连接）')
    res.destroy()
    return
  }

  // 场景：超时（保持连接不发送数据，交由客户端超时处理）
  if (txt.indexOf('超时') >= 0) {
    log('  -> 场景：挂起 ' + HANG_SECONDS + 's（验证客户端读取超时）')
    setTimeout(function () { try { res.destroy() } catch (ignored) {} }, HANG_SECONDS * 1000)
    return
  }

  // 场景：挂起（模拟平台长时间处理：保持连接 pending + 周期心跳；到时后正常返回成功结果）
  if (txt.indexOf('挂起') >= 0 || /pending/i.test(txt)) {
    log('  -> 场景：挂起 pending ' + PENDING_SECONDS + 's（心跳 ' + HEARTBEAT_SECONDS + 's；连接保持，客户端持续“events pending”）')
    let heartbeatSeq = 10
    const heartbeat = setInterval(function () {
      heartbeatSeq += 1
      sse(res, 'progress', JSON.stringify({
        type: 'heartbeat', seq: heartbeatSeq, title: '模型处理中',
        message: '大模型仍在分析（Mock 心跳）', session_id: sessionId, request_id: requestId
      }))
      log('  -> 心跳#' + heartbeatSeq + '（连接保持 pending）')
    }, HEARTBEAT_SECONDS * 1000)
    const finish = setTimeout(function () {
      clearInterval(heartbeat)
      const answer = buildAnswer(txt)
      log('  -> 挂起结束，正常返回结果')
      sse(res, 'message', JSON.stringify({
        ok: true, status: 'completed', intent_code: null, message: answer,
        errorCode: null, raw: answer
      }))
      if (debugTrace) {
        try { sendTraceFrame(res, sessionId, requestId, txt, turnId) } catch (ignored) {}
      }
      sse(res, 'done', '"[DONE]"')
      res.end()
    }, PENDING_SECONDS * 1000)
    res.on('close', function () {
      clearInterval(heartbeat)
      clearTimeout(finish)
      log('  -> 客户端断开，挂起场景定时器已清理')
    })
    return
  }

  // 场景：策略拦截
  if (txt.indexOf('敏感') >= 0) {
    log('  -> 场景：策略拦截（POLICY_SENSITIVE_WORD_BLOCKED）')
    sse(res, 'message', JSON.stringify({
      ok: false, status: 'error', intent_code: null, message: '命中平台敏感词策略（Mock）',
      errorCode: 'POLICY_SENSITIVE_WORD_BLOCKED', raw: ''
    }))
    if (debugTrace) { await delay(120); sendTraceFrame(res, sessionId, requestId, txt, turnId) }
    sse(res, 'done', '"[DONE]"')
    res.end()
    return
  }

  // 场景：模型限流
  if (txt.indexOf('限流') >= 0) {
    log('  -> 场景：模型限流（MODEL_RATE_LIMITED）')
    sse(res, 'message', JSON.stringify({
      ok: false, status: 'error', intent_code: null, message: '模型服务限流（Mock，HTTP 429 语义）',
      errorCode: 'MODEL_RATE_LIMITED', raw: ''
    }))
    if (debugTrace) { await delay(120); sendTraceFrame(res, sessionId, requestId, txt, turnId) }
    sse(res, 'done', '"[DONE]"')
    res.end()
    return
  }

  // 正常链路：必要时发送进度事件（skill_loaded / workflow_called）
  if (isInvestigationIntent(txt) || /查询|skill|帮我/.test(txt)) {
    const skillName = isInvestigationIntent(txt) ? 'investigation' : 'mock-skill'
    sse(res, 'progress', JSON.stringify({
      type: 'skill_loaded', seq: 1,
      skill: { name: skillName, title: '（Mock）识别用户意图并加载业务能力' },
      title: '已加载业务能力', message: '已加载 skill：' + skillName,
      session_id: sessionId, request_id: requestId
    }))
    await delay(BASE_DELAY_MS)
    if (isInvestigationIntent(txt)) {
      sse(res, 'progress', JSON.stringify({
        type: 'workflow_called', seq: 2, title: '正在调用业务流程', message: '正在调用 workflow',
        session_id: sessionId, request_id: requestId
      }))
      await delay(BASE_DELAY_MS)
    }
  }

  // 场景：慢响应
  if (txt.indexOf('慢') >= 0) {
    log('  -> 场景：慢响应（等待 ' + SLOW_SECONDS + 's）')
    await delay(SLOW_SECONDS * 1000)
  }

  const intentCode = isInvestigationIntent(txt) ? 'INVESTIGATION' : null
  const answer = buildAnswer(txt)
  sse(res, 'message', JSON.stringify({
    ok: true, status: 'completed', intent_code: intentCode, message: answer,
    errorCode: null, raw: answer
  }))
  if (debugTrace) {
    await delay(120)
    sendTraceFrame(res, sessionId, requestId, txt, turnId)
  }
  sse(res, 'done', '"[DONE]"')
  res.end()
}

/** 提取 config_variables 的名称列表用于日志（不透出取值）。 */
function variableNames (variables) {
  if (!variables) {
    return null
  }
  if (!Array.isArray(variables)) {
    return variables
  }
  return variables.map(function (item) { return item && item.name }).filter(Boolean)
}

/** 解析我方客户端发送的 multipart 请求体（仅需支持 sessionId 文本字段与 file 文件字段） */
function splitParts (body, boundary) {
  const parts = []
  const firstDelimiter = Buffer.from(boundary + '\r\n')
  let start = body.indexOf(firstDelimiter)
  if (start < 0) {
    return parts
  }
  start += firstDelimiter.length
  const delimiter = Buffer.from('\r\n' + boundary)
  while (true) {
    const end = body.indexOf(delimiter, start)
    if (end < 0) {
      break
    }
    parts.push(body.slice(start, end))
    const after = body.slice(end + delimiter.length, end + delimiter.length + 2).toString()
    if (after === '--') {
      break
    }
    start = end + delimiter.length + 2
  }
  return parts
}

async function handleUpload (req, res, query) {
  const body = await collect(req)
  const contentType = String(req.headers['content-type'] || '')
  const boundaryMatch = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType)
  if (!boundaryMatch) {
    sendJson(res, 400, { success: false, message: 'missing multipart boundary' })
    return
  }
  const boundary = '--' + (boundaryMatch[1] || boundaryMatch[2]).trim()
  let sessionId = String(query.sessionId || '')
  let fileName = 'attachment.bin'
  let fileContent = Buffer.alloc(0)
  splitParts(body, boundary).forEach(function (part) {
    const headerEnd = part.indexOf('\r\n\r\n')
    if (headerEnd < 0) {
      return
    }
    const headers = part.slice(0, headerEnd).toString('utf8')
    const content = part.slice(headerEnd + 4)
    const nameMatch = /name="([^"]+)"/.exec(headers)
    const fileMatch = /filename="([^"]*)"/.exec(headers)
    if (!nameMatch) {
      return
    }
    if (fileMatch && nameMatch[1] === 'file') {
      fileName = fileMatch[1] || fileName
      fileContent = content
    } else if (nameMatch[1] === 'sessionId') {
      sessionId = content.toString('utf8').trim()
    }
  })
  const workspace = getWorkspace(sessionId)
  workspace.set(fileName, { name: fileName, size: fileContent.length, content: fileContent })
  log('POST files/upload session=' + sessionId + ' file=' + fileName + ' size=' + fileContent.length)
  sendJson(res, 200, {
    sessionId: sessionId,
    file: { path: fileName, name: fileName, type: 'file', size: fileContent.length }
  })
}

function handleList (res, query) {
  const sessionId = String(query.sessionId || '')
  const workspace = getWorkspace(sessionId)
  const files = []
  workspace.forEach(function (value, key) {
    files.push({ path: key, name: value.name, type: 'file', size: value.size })
  })
  log('GET files/list session=' + sessionId + ' files=' + files.length)
  sendJson(res, 200, { sessionId: sessionId, path: '', files: files })
}

function handleContent (res, query) {
  const sessionId = String(query.sessionId || '')
  const path = String(query.path || '')
  const workspace = getWorkspace(sessionId)
  const file = workspace.get(path)
  log('GET files/content session=' + sessionId + ' path=' + path + ' found=' + !!file)
  if (!file) {
    sendJson(res, 404, { success: false, message: 'file not found' })
    return
  }
  res.writeHead(200, {
    'Content-Type': 'application/octet-stream',
    'Content-Length': file.content.length,
    'Content-Disposition': contentDisposition(file.name),
    'X-Content-Type-Options': 'nosniff'
  })
  res.end(file.content)
}

/** 生成 ASCII 安全的 Content-Disposition（中文等非 ASCII 文件名用 RFC 5987 filename* 承载）。 */
function contentDisposition (name) {
  const ascii = String(name).replace(/[^\x20-\x7e]/g, '_').replace(/["\\]/g, '_')
  return 'attachment; filename="' + ascii + '"; filename*=UTF-8\'\'' + encodeURIComponent(name)
}

function handleDelete (res, query) {
  const sessionId = String(query.sessionId || '')
  const path = String(query.path || '')
  const workspace = getWorkspace(sessionId)
  const deleted = workspace.delete(path)
  log('DELETE files session=' + sessionId + ' path=' + path + ' deleted=' + deleted)
  sendJson(res, 200, { sessionId: sessionId, path: path, deleted: !!deleted })
}

const server = http.createServer(function (req, res) {
  req.on('error', function () {})
  res.on('error', function () {})
  const parsed = url.parse(req.url, true)
  const path = parsed.pathname || ''
  const guard = function (handler) {
    Promise.resolve()
      .then(handler)
      .catch(function (error) {
        log('ERROR ' + req.method + ' ' + path + ' -> ' + error.message)
        if (!res.headersSent) {
          sendJson(res, 500, { success: false, message: error.message })
        } else {
          try { res.destroy() } catch (ignored) {}
        }
      })
  }
  if (req.method === 'POST' && /\/api\/v1\/message$/.test(path)) {
    guard(function () { return handleMessage(req, res) })
    return
  }
  if (req.method === 'POST' && /\/api\/v1\/files\/upload$/.test(path)) {
    guard(function () { return handleUpload(req, res, parsed.query) })
    return
  }
  if (req.method === 'GET' && /\/api\/v1\/files\/list$/.test(path)) {
    guard(function () { return handleList(res, parsed.query) })
    return
  }
  if (req.method === 'GET' && /\/api\/v1\/files\/content$/.test(path)) {
    guard(function () { return handleContent(res, parsed.query) })
    return
  }
  if (req.method === 'DELETE' && /\/api\/v1\/files$/.test(path)) {
    guard(function () { return handleDelete(res, parsed.query) })
    return
  }
  if (req.method === 'GET' && (path === '/' || path === '/health')) {
    guard(function () { return sendJson(res, 200, { provider: 'mock', port: PORT, time: new Date().toISOString() }) })
    return
  }
  sendJson(res, 404, { success: false, message: 'not found: ' + req.method + ' ' + path })
})

process.on('uncaughtException', function (error) {
  log('uncaughtException: ' + error.message)
})

server.listen(PORT, '127.0.0.1', function () {
  log('行内平台 Mock 服务已启动（零依赖，Node ' + process.version + '）')
  log('  message  : POST   http://127.0.0.1:' + PORT + '/api/v1/message')
  log('  files    : POST/GET/DELETE http://127.0.0.1:' + PORT + '/api/v1/files[/upload|/list|/content]')
  log('  场景关键词: 尽调 / 敏感 / 限流 / 断流 / 超时 / 慢 / 挂起(pending)')
  log('  后端对接: diligence.platform.url=http://127.0.0.1:' + PORT + '/api/v1/message（application.yml 默认已指向本端口）')
})
