/// <reference path="../pb_data/types.d.ts" />

var QDRANT_SYNC_LOG_COLLECTION = String(
  $os.getenv("QDRANT_SYNC_LOG_COLLECTION") || "qdrant_sync_logs"
).trim();

function qdrantLogSafeStr(v) {
  return v === null || v === undefined ? "" : String(v);
}

function logQdrantSyncEvent(app, payload) {
  var action = qdrantLogSafeStr(payload && payload.action);
  var status = qdrantLogSafeStr(payload && payload.status);
  try {
    if (!app) {
      console.log(`>> [QdrantLog Skip] app missing (${action}/${status})`);
      return;
    }
    var collectionName = String(QDRANT_SYNC_LOG_COLLECTION || "qdrant_sync_logs").trim();
    if (!collectionName) {
      console.log(`>> [QdrantLog Skip] empty collection name (${action}/${status})`);
      return;
    }

    var collection = null;
    try {
      collection = app.findCollectionByNameOrId(collectionName);
    } catch (_) {
      collection = null;
    }
    if (!collection) {
      console.log(`>> [QdrantLog Skip] collection not found: ${collectionName}`);
      return;
    }

    var record = new Record(collection);
    record.set("action", qdrantLogSafeStr(payload.action));
    record.set("status", qdrantLogSafeStr(payload.status));
    record.set("recordId", qdrantLogSafeStr(payload.recordId));
    record.set("bookId", qdrantLogSafeStr(payload.bookId));
    record.set("qdrantCollection", qdrantLogSafeStr(payload.qdrantCollection));
    record.set("pointId", qdrantLogSafeStr(payload.pointId));
    record.set("reason", qdrantLogSafeStr(payload.reason));
    record.set("detail", qdrantLogSafeStr(payload.detail));
    record.set("error", qdrantLogSafeStr(payload.error));
    record.set("timestamp", Number(payload.timestamp) || Date.now());

    var userId = qdrantLogSafeStr(payload.userId).trim();
    if (userId) {
      record.set("user", userId);
    }

    app.save(record);
  } catch (err) {
    console.log(`>> [QdrantLog Error] ${err}`);
    // Best-effort logging only; never break primary flow.
  }
}

// ============================================================
// 1) Create -> Qdrant upsert (self-contained)
// ============================================================
onRecordCreate((e) => {
  e.next();

  const appRef = (e && e.app) || (typeof $app !== "undefined" ? $app : null);
  let recordIdForLog = "";
  let userIdForLog = "";
  let bookIdForLog = "";
  let qdrantCollectionForLog = "";
  let pointIdForLog = "";

  try {
    const safeStr = (v) => (v === null || v === undefined ? "" : String(v));
    const toRelId = (v) => {
      if (Array.isArray(v)) return String(v[0] || "");
      if (v && typeof v === "object") return String(v.id || "");
      return String(v || "");
    };
    const nowTs = Date.now();

    recordIdForLog = safeStr(e.record && e.record.id);
    userIdForLog = toRelId(e.record.get("user"));
    bookIdForLog = safeStr(e.record.get("bookId"));

    const cfg = {
      embeddingApiKey: String($os.getenv("DASHSCOPE_API_KEY") || "").trim(),
      embeddingUrl: String(
        $os.getenv("DASHSCOPE_EMBED_URL") ||
          "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/embeddings"
      ).trim(),
      embeddingModel: String($os.getenv("DASHSCOPE_EMBED_MODEL") || "text-embedding-v4").trim(),
      embeddingDim: parseInt(String($os.getenv("DASHSCOPE_EMBED_DIM") || "1024"), 10) || 1024,
      qdrantUrl: String($os.getenv("QDRANT_URL") || "http://127.0.0.1:6333")
        .trim()
        .replace(/\/+$/, ""),
      qdrantCollection: String($os.getenv("QDRANT_COLLECTION") || "ai_notes").trim(),
      maxEmbedText: parseInt(String($os.getenv("MAX_EMBED_TEXT") || "6000"), 10) || 6000,
    };
    qdrantCollectionForLog = cfg.qdrantCollection;

    const status = safeStr(e.record.get("status"));
    if (status !== "done") {
      if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
        action: "upsert_create",
        status: "skipped",
        reason: "status_not_done",
        detail: `status=${status}`,
        recordId: recordIdForLog,
        userId: userIdForLog,
        bookId: bookIdForLog,
        qdrantCollection: qdrantCollectionForLog,
        timestamp: nowTs,
      });
      return;
    }
    const aiResponse = safeStr(e.record.get("aiResponse"));
    if (aiResponse.length < 2) {
      if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
        action: "upsert_create",
        status: "skipped",
        reason: "ai_response_too_short",
        detail: `length=${aiResponse.length}`,
        recordId: recordIdForLog,
        userId: userIdForLog,
        bookId: bookIdForLog,
        qdrantCollection: qdrantCollectionForLog,
        timestamp: nowTs,
      });
      return;
    }

    const originalText = safeStr(e.record.get("originalText"));
    let textToEmbed = `Title: ${originalText}\n\nContent: ${aiResponse}`;
    if (textToEmbed.length > cfg.maxEmbedText) {
      textToEmbed = textToEmbed.substring(0, cfg.maxEmbedText);
    }

    if (!cfg.embeddingApiKey) {
      throw new Error("DASHSCOPE_API_KEY is missing");
    }

    const embedRes = $http.send({
      url: cfg.embeddingUrl,
      method: "POST",
      headers: {
        Authorization: `Bearer ${cfg.embeddingApiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: cfg.embeddingModel,
        input: textToEmbed,
        dimensions: cfg.embeddingDim,
        encoding_format: "float",
      }),
      timeout: 120,
    });

    if (embedRes.statusCode !== 200) {
      throw new Error(`Embedding API Error (${embedRes.statusCode}): ${embedRes.raw}`);
    }

    const raw = embedRes.json || {};
    const vector = raw.data && raw.data[0] ? raw.data[0].embedding : null;

    if (!Array.isArray(vector)) throw new Error("embedding missing");
    if (vector.length !== cfg.embeddingDim) {
      throw new Error(`embedding dim mismatch: ${vector.length}`);
    }
    for (let i = 0; i < vector.length; i++) {
      const n = vector[i];
      if (typeof n !== "number" || !isFinite(n)) {
        throw new Error("embedding contains non-finite values");
      }
    }

    const hash = $security.md5(String(e.record.id || ""));
    const uuid =
      hash.substring(0, 8) +
      "-" +
      hash.substring(8, 12) +
      "-" +
      hash.substring(12, 16) +
      "-" +
      hash.substring(16, 20) +
      "-" +
      hash.substring(20, 32);
    pointIdForLog = uuid;

    const qRes = $http.send({
      url: `${cfg.qdrantUrl}/collections/${cfg.qdrantCollection}/points?wait=true`,
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        points: [
          {
            id: uuid,
            vector: vector,
            payload: {
              pb_id: e.record.id,
              user_id: toRelId(e.record.get("user")),
              book_id: safeStr(e.record.get("bookId")),
            },
          },
        ],
      }),
      timeout: 30,
    });

    if (qRes.statusCode < 200 || qRes.statusCode >= 300) {
      throw new Error(`Qdrant upsert error (${qRes.statusCode}): ${qRes.raw}`);
    }

    console.log(`>> [Create Success] Synced ${e.record.id}`);
    if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
      action: "upsert_create",
      status: "success",
      recordId: recordIdForLog,
      userId: userIdForLog,
      bookId: bookIdForLog,
      qdrantCollection: qdrantCollectionForLog,
      pointId: pointIdForLog,
      detail: `embed_len=${textToEmbed.length}`,
      timestamp: nowTs,
    });
  } catch (err) {
    console.log(`>> [Create Error] ${err}`);
    if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
      action: "upsert_create",
      status: "failed",
      reason: "hook_exception",
      error: qdrantLogSafeStr(err),
      recordId: recordIdForLog,
      userId: userIdForLog,
      bookId: bookIdForLog,
      qdrantCollection: qdrantCollectionForLog,
      pointId: pointIdForLog,
      timestamp: Date.now(),
    });
  }
}, "ai_notes");

// ============================================================
// 2) Update -> Qdrant upsert (self-contained)
// ============================================================
onRecordUpdate((e) => {
  e.next();

  const appRef = (e && e.app) || (typeof $app !== "undefined" ? $app : null);
  let recordIdForLog = "";
  let userIdForLog = "";
  let bookIdForLog = "";
  let qdrantCollectionForLog = "";
  let pointIdForLog = "";

  try {
    const safeStr = (v) => (v === null || v === undefined ? "" : String(v));
    const toRelId = (v) => {
      if (Array.isArray(v)) return String(v[0] || "");
      if (v && typeof v === "object") return String(v.id || "");
      return String(v || "");
    };
    const nowTs = Date.now();

    recordIdForLog = safeStr(e.record && e.record.id);
    userIdForLog = toRelId(e.record.get("user"));
    bookIdForLog = safeStr(e.record.get("bookId"));

    const cfg = {
      embeddingApiKey: String($os.getenv("DASHSCOPE_API_KEY") || "").trim(),
      embeddingUrl: String(
        $os.getenv("DASHSCOPE_EMBED_URL") ||
          "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/embeddings"
      ).trim(),
      embeddingModel: String($os.getenv("DASHSCOPE_EMBED_MODEL") || "text-embedding-v4").trim(),
      embeddingDim: parseInt(String($os.getenv("DASHSCOPE_EMBED_DIM") || "1024"), 10) || 1024,
      qdrantUrl: String($os.getenv("QDRANT_URL") || "http://127.0.0.1:6333")
        .trim()
        .replace(/\/+$/, ""),
      qdrantCollection: String($os.getenv("QDRANT_COLLECTION") || "ai_notes").trim(),
      maxEmbedText: parseInt(String($os.getenv("MAX_EMBED_TEXT") || "6000"), 10) || 6000,
    };
    qdrantCollectionForLog = cfg.qdrantCollection;

    const status = safeStr(e.record.get("status"));
    if (status !== "done") {
      if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
        action: "upsert_update",
        status: "skipped",
        reason: "status_not_done",
        detail: `status=${status}`,
        recordId: recordIdForLog,
        userId: userIdForLog,
        bookId: bookIdForLog,
        qdrantCollection: qdrantCollectionForLog,
        timestamp: nowTs,
      });
      return;
    }
    const aiResponse = safeStr(e.record.get("aiResponse"));
    if (aiResponse.length < 2) {
      if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
        action: "upsert_update",
        status: "skipped",
        reason: "ai_response_too_short",
        detail: `length=${aiResponse.length}`,
        recordId: recordIdForLog,
        userId: userIdForLog,
        bookId: bookIdForLog,
        qdrantCollection: qdrantCollectionForLog,
        timestamp: nowTs,
      });
      return;
    }

    const originalText = safeStr(e.record.get("originalText"));
    let textToEmbed = `Title: ${originalText}\n\nContent: ${aiResponse}`;
    if (textToEmbed.length > cfg.maxEmbedText) {
      textToEmbed = textToEmbed.substring(0, cfg.maxEmbedText);
    }

    if (!cfg.embeddingApiKey) {
      throw new Error("DASHSCOPE_API_KEY is missing");
    }

    const embedRes = $http.send({
      url: cfg.embeddingUrl,
      method: "POST",
      headers: {
        Authorization: `Bearer ${cfg.embeddingApiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: cfg.embeddingModel,
        input: textToEmbed,
        dimensions: cfg.embeddingDim,
        encoding_format: "float",
      }),
      timeout: 120,
    });

    if (embedRes.statusCode !== 200) {
      throw new Error(`Embedding API Error (${embedRes.statusCode}): ${embedRes.raw}`);
    }

    const raw = embedRes.json || {};
    const vector = raw.data && raw.data[0] ? raw.data[0].embedding : null;

    if (!Array.isArray(vector)) throw new Error("embedding missing");
    if (vector.length !== cfg.embeddingDim) {
      throw new Error(`embedding dim mismatch: ${vector.length}`);
    }
    for (let i = 0; i < vector.length; i++) {
      const n = vector[i];
      if (typeof n !== "number" || !isFinite(n)) {
        throw new Error("embedding contains non-finite values");
      }
    }

    const hash = $security.md5(String(e.record.id || ""));
    const uuid =
      hash.substring(0, 8) +
      "-" +
      hash.substring(8, 12) +
      "-" +
      hash.substring(12, 16) +
      "-" +
      hash.substring(16, 20) +
      "-" +
      hash.substring(20, 32);
    pointIdForLog = uuid;

    const qRes = $http.send({
      url: `${cfg.qdrantUrl}/collections/${cfg.qdrantCollection}/points?wait=true`,
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        points: [
          {
            id: uuid,
            vector: vector,
            payload: {
              pb_id: e.record.id,
              user_id: toRelId(e.record.get("user")),
              book_id: safeStr(e.record.get("bookId")),
            },
          },
        ],
      }),
      timeout: 30,
    });

    if (qRes.statusCode < 200 || qRes.statusCode >= 300) {
      throw new Error(`Qdrant upsert error (${qRes.statusCode}): ${qRes.raw}`);
    }

    console.log(`>> [Update Success] Synced ${e.record.id}`);
    if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
      action: "upsert_update",
      status: "success",
      recordId: recordIdForLog,
      userId: userIdForLog,
      bookId: bookIdForLog,
      qdrantCollection: qdrantCollectionForLog,
      pointId: pointIdForLog,
      detail: `embed_len=${textToEmbed.length}`,
      timestamp: nowTs,
    });
  } catch (err) {
    console.log(`>> [Update Error] ${err}`);
    if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
      action: "upsert_update",
      status: "failed",
      reason: "hook_exception",
      error: qdrantLogSafeStr(err),
      recordId: recordIdForLog,
      userId: userIdForLog,
      bookId: bookIdForLog,
      qdrantCollection: qdrantCollectionForLog,
      pointId: pointIdForLog,
      timestamp: Date.now(),
    });
  }
}, "ai_notes");

// ============================================================
// 3) Delete -> Qdrant delete (self-contained)
// ============================================================
onRecordDelete((e) => {
  e.next();

  const appRef = (e && e.app) || (typeof $app !== "undefined" ? $app : null);
  let recordIdForLog = "";
  let userIdForLog = "";
  let bookIdForLog = "";
  let qdrantCollectionForLog = "";
  let pointIdForLog = "";

  try {
    const safeStr = (v) => (v === null || v === undefined ? "" : String(v));
    const toRelId = (v) => {
      if (Array.isArray(v)) return String(v[0] || "");
      if (v && typeof v === "object") return String(v.id || "");
      return String(v || "");
    };
    const nowTs = Date.now();

    recordIdForLog = safeStr(e.record && e.record.id);
    userIdForLog = toRelId(e.record.get("user"));
    bookIdForLog = safeStr(e.record.get("bookId"));

    const cfg = {
      qdrantUrl: String($os.getenv("QDRANT_URL") || "http://127.0.0.1:6333")
        .trim()
        .replace(/\/+$/, ""),
      qdrantCollection: String($os.getenv("QDRANT_COLLECTION") || "ai_notes").trim(),
    };
    qdrantCollectionForLog = cfg.qdrantCollection;

    const hash = $security.md5(String(e.record.id || ""));
    const uuid =
      hash.substring(0, 8) +
      "-" +
      hash.substring(8, 12) +
      "-" +
      hash.substring(12, 16) +
      "-" +
      hash.substring(16, 20) +
      "-" +
      hash.substring(20, 32);
    pointIdForLog = uuid;

    const delRes = $http.send({
      url: `${cfg.qdrantUrl}/collections/${cfg.qdrantCollection}/points/delete?wait=true`,
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ points: [uuid] }),
      timeout: 30,
    });

    if (delRes.statusCode < 200 || delRes.statusCode >= 300) {
      throw new Error(`Qdrant delete error (${delRes.statusCode}): ${delRes.raw}`);
    }

    console.log(`>> [Delete Success] Removed ${e.record.id}`);
    if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
      action: "delete",
      status: "success",
      recordId: recordIdForLog,
      userId: userIdForLog,
      bookId: bookIdForLog,
      qdrantCollection: qdrantCollectionForLog,
      pointId: pointIdForLog,
      timestamp: nowTs,
    });
  } catch (err) {
    console.log(`>> [Delete Error] ${err}`);
    if (typeof logQdrantSyncEvent === "function") logQdrantSyncEvent(appRef, {
      action: "delete",
      status: "failed",
      reason: "hook_exception",
      error: qdrantLogSafeStr(err),
      recordId: recordIdForLog,
      userId: userIdForLog,
      bookId: bookIdForLog,
      qdrantCollection: qdrantCollectionForLog,
      pointId: pointIdForLog,
      timestamp: Date.now(),
    });
  }
}, "ai_notes");

// ============================================================
// 4) Semantic Search API (self-contained)
// POST /boox-ai-notes-semantic-search
// body: { query, limit, bookId, excludeRemoteId, excludeLocalId }
// ============================================================
routerAdd("POST", "/boox-ai-notes-semantic-search", (e) => {
  try {
    const safeStr = (v) => (v === null || v === undefined ? "" : String(v));
    const toRelId = (v) => {
      if (Array.isArray(v)) return String(v[0] || "");
      if (v && typeof v === "object") return String(v.id || "");
      return String(v || "");
    };

    const cfg = {
      embeddingApiKey: String($os.getenv("DASHSCOPE_API_KEY") || "").trim(),
      embeddingUrl: String(
        $os.getenv("DASHSCOPE_EMBED_URL") ||
          "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/embeddings"
      ).trim(),
      embeddingModel: String($os.getenv("DASHSCOPE_EMBED_MODEL") || "text-embedding-v4").trim(),
      embeddingDim: parseInt(String($os.getenv("DASHSCOPE_EMBED_DIM") || "1024"), 10) || 1024,
      qdrantUrl: String($os.getenv("QDRANT_URL") || "http://127.0.0.1:6333")
        .trim()
        .replace(/\/+$/, ""),
      qdrantCollection: String($os.getenv("QDRANT_COLLECTION") || "ai_notes").trim(),
      pbPublicUrl: String($os.getenv("POCKETBASE_PUBLIC_URL") || "")
        .trim()
        .replace(/\/+$/, ""),
    };

    const parseReqBody = () => {
      try {
        const reqInfo = e.requestInfo();
        if (!reqInfo || reqInfo.body === null || reqInfo.body === undefined) return {};
        if (typeof reqInfo.body === "string") {
          try {
            return JSON.parse(reqInfo.body);
          } catch (_) {
            return {};
          }
        }
        return reqInfo.body;
      } catch (_) {
        return {};
      }
    };

    const getAuthHeader = () => {
      try {
        if (e.request && e.request.header && e.request.header.get) {
          const h = e.request.header.get("Authorization");
          if (h) return String(h);
        }
      } catch (_) {}
      try {
        if (e.request && e.request.headers && e.request.headers.get) {
          const h = e.request.headers.get("Authorization");
          if (h) return String(h);
        }
      } catch (_) {}
      return "";
    };

    const resolveUserId = () => {
      let reqInfo = null;
      try {
        reqInfo = e.requestInfo();
      } catch (_) {
        reqInfo = null;
      }

      const routeAuthRecord =
        (e && e.auth) ||
        (reqInfo && (reqInfo.authRecord || reqInfo.auth)) ||
        null;
      if (routeAuthRecord) {
        const uid = toRelId(routeAuthRecord.id || routeAuthRecord);
        if (uid) return uid;
      }

      const authHeader = getAuthHeader();
      const token = String(authHeader).replace(/^Bearer\s+/i, "").trim();
      if (!token) {
        throw new Error("Unauthorized: missing bearer token");
      }

      try {
        const authRecord = $app.findAuthRecordByToken(token, "auth");
        const localUserId = toRelId(authRecord && authRecord.id ? authRecord.id : authRecord);
        if (localUserId) return localUserId;
      } catch (_) {}

      if (!cfg.pbPublicUrl) {
        throw new Error("Unauthorized: invalid auth token");
      }

      const verifyRes = $http.send({
        url: `${cfg.pbPublicUrl}/api/collections/users/auth-refresh`,
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        timeout: 20,
      });

      if (verifyRes.statusCode !== 200) {
        throw new Error(`Unauthorized: auth-refresh failed (${verifyRes.statusCode})`);
      }

      const rec = verifyRes.json && verifyRes.json.record ? verifyRes.json.record : null;
      const userId = toRelId(rec ? rec.id : "");
      if (!userId) throw new Error("Unauthorized: no user id in auth-refresh response");
      return userId;
    };

    const qdrantSearch = (vector, limit, filterObj) => {
      const payload = { vector: vector, limit: limit, with_payload: true };
      if (filterObj) payload.filter = filterObj;

      const res = $http.send({
        url: `${cfg.qdrantUrl}/collections/${cfg.qdrantCollection}/points/search`,
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
        timeout: 30,
      });

      if (res.statusCode < 200 || res.statusCode >= 300) {
        throw new Error(`Qdrant search error (${res.statusCode}): ${res.raw}`);
      }

      return res.json && res.json.result ? res.json.result : [];
    };

    const userId = resolveUserId();
    const body = parseReqBody();

    const query = safeStr(body.query).trim();
    const parsedLimit = parseInt(safeStr(body.limit || "5"), 10);
    const limit = Math.max(1, Math.min(10, isNaN(parsedLimit) ? 5 : parsedLimit));
    const bookId = safeStr(body.bookId).trim();
    const excludeRemoteId = safeStr(body.excludeRemoteId).trim();
    const excludeLocalId = safeStr(body.excludeLocalId).trim();

    if (!query) return e.json(400, { error: "query required" });
    if (!cfg.embeddingApiKey) return e.json(500, { error: "DASHSCOPE_API_KEY is missing" });

    const embedRes = $http.send({
      url: cfg.embeddingUrl,
      method: "POST",
      headers: {
        Authorization: `Bearer ${cfg.embeddingApiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: cfg.embeddingModel,
        input: query,
        dimensions: cfg.embeddingDim,
        encoding_format: "float",
      }),
      timeout: 120,
    });

    if (embedRes.statusCode !== 200) {
      throw new Error(`Embedding API Error (${embedRes.statusCode}): ${embedRes.raw}`);
    }

    const embRaw = embedRes.json || {};
    const vector = embRaw.data && embRaw.data[0] ? embRaw.data[0].embedding : null;

    if (!Array.isArray(vector)) throw new Error("embedding missing");
    if (vector.length !== cfg.embeddingDim) {
      throw new Error(`embedding dim mismatch: ${vector.length}`);
    }
    for (let i = 0; i < vector.length; i++) {
      const n = vector[i];
      if (typeof n !== "number" || !isFinite(n)) throw new Error("embedding contains non-finite values");
    }

    const must = [{ key: "user_id", match: { value: userId } }];
    const must_not = [];
    if (bookId) must.push({ key: "book_id", match: { value: bookId } });
    if (excludeRemoteId) must_not.push({ key: "pb_id", match: { value: excludeRemoteId } });
    if (excludeLocalId) must_not.push({ key: "local_id", match: { value: excludeLocalId } });

    let points = qdrantSearch(vector, limit, { must, must_not });

    if (!points || points.length === 0) {
      const fallbackMustNot = [];
      if (excludeRemoteId) fallbackMustNot.push({ key: "pb_id", match: { value: excludeRemoteId } });
      if (excludeLocalId) fallbackMustNot.push({ key: "local_id", match: { value: excludeLocalId } });
      const fallbackFilter = fallbackMustNot.length > 0 ? { must_not: fallbackMustNot } : null;
      points = qdrantSearch(vector, Math.max(limit * 5, 50), fallbackFilter);
    }

    if (!points || points.length === 0) return e.json(200, { results: [] });

    const pbIdSet = {};
    const pbIds = [];
    for (let i = 0; i < points.length; i++) {
      const payload = points[i].payload || {};
      const pbId = safeStr(payload.pb_id).trim();
      if (pbId && !pbIdSet[pbId]) {
        pbIdSet[pbId] = true;
        pbIds.push(pbId);
      }
    }

    if (pbIds.length === 0) return e.json(200, { results: [] });

    const records = $app.findRecordsByIds("ai_notes", pbIds);
    const recordMap = {};
    for (let i = 0; i < records.length; i++) {
      recordMap[records[i].id] = records[i];
    }

    const results = [];
    for (let i = 0; i < points.length; i++) {
      const p = points[i];
      const payload = p.payload || {};
      const pbId = safeStr(payload.pb_id).trim();
      const r = recordMap[pbId];
      if (!r) continue;

      const recordUserId = toRelId(r.get("user"));
      if (recordUserId !== userId) continue;

      if (bookId) {
        const recordBookId = safeStr(r.get("bookId"));
        if (recordBookId !== bookId) continue;
      }

      const reason =
        safeStr(payload.reason) ||
        safeStr(payload.match_reason) ||
        safeStr(payload.reasoning) ||
        "Semantic match";

      results.push({
        noteId: r.id,
        score: p.score || 0,
        reason: reason,
        bookTitle: safeStr(r.get("bookTitle")),
        originalText: safeStr(r.get("originalText")),
        aiResponse: safeStr(r.get("aiResponse")),
        remoteId: r.id,
        localId: payload.local_id || null,
      });

      if (results.length >= limit) break;
    }

    return e.json(200, { results });
  } catch (err) {
    return e.json(500, { error: String(err || "unknown error") });
  }
});

// ============================================================
// 5) PocketBase-native RAG APIs (documents/chunks/embeddings)
// ============================================================
function ragSafeStr(v) {
  return v === null || v === undefined ? "" : String(v);
}

function ragToRelId(v) {
  if (Array.isArray(v)) return String(v[0] || "");
  if (v && typeof v === "object") return String(v.id || "");
  return String(v || "");
}

function ragEscapeFilterString(v) {
  return String(v || "")
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"');
}

function ragParseReqBody(e) {
  try {
    const reqInfo = e.requestInfo();
    if (!reqInfo || reqInfo.body === null || reqInfo.body === undefined) return {};
    if (typeof reqInfo.body === "string") {
      try {
        return JSON.parse(reqInfo.body);
      } catch (_) {
        return {};
      }
    }
    return reqInfo.body;
  } catch (_) {
    return {};
  }
}

function ragGetAuthHeader(e) {
  try {
    if (e.request && e.request.header && e.request.header.get) {
      const h = e.request.header.get("Authorization");
      if (h) return String(h);
    }
  } catch (_) {}
  try {
    if (e.request && e.request.headers && e.request.headers.get) {
      const h = e.request.headers.get("Authorization");
      if (h) return String(h);
    }
  } catch (_) {}
  return "";
}

function ragResolveUserId(e, cfg) {
  let reqInfo = null;
  try {
    reqInfo = e.requestInfo();
  } catch (_) {
    reqInfo = null;
  }

  const routeAuthRecord = (e && e.auth) || (reqInfo && (reqInfo.authRecord || reqInfo.auth)) || null;
  if (routeAuthRecord) {
    const uid = ragToRelId(routeAuthRecord.id || routeAuthRecord);
    if (uid) return uid;
  }

  const authHeader = ragGetAuthHeader(e);
  const token = String(authHeader).replace(/^Bearer\s+/i, "").trim();
  if (!token) {
    throw new Error("Unauthorized: missing bearer token");
  }

  try {
    const authRecord = $app.findAuthRecordByToken(token, "auth");
    const localUserId = ragToRelId(authRecord && authRecord.id ? authRecord.id : authRecord);
    if (localUserId) return localUserId;
  } catch (_) {}

  if (!cfg.pbPublicUrl) {
    throw new Error("Unauthorized: invalid auth token");
  }

  const verifyRes = $http.send({
    url: `${cfg.pbPublicUrl}/api/collections/users/auth-refresh`,
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    timeout: 20,
  });

  if (verifyRes.statusCode !== 200) {
    throw new Error(`Unauthorized: auth-refresh failed (${verifyRes.statusCode})`);
  }

  const rec = verifyRes.json && verifyRes.json.record ? verifyRes.json.record : null;
  const userId = ragToRelId(rec ? rec.id : "");
  if (!userId) throw new Error("Unauthorized: no user id in auth-refresh response");
  return userId;
}

function ragParseJsonObject(value) {
  if (value === null || value === undefined) return null;
  if (typeof value === "object") return value;
  const raw = String(value).trim();
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch (_) {
    return null;
  }
}

function ragSerializeJsonObject(value) {
  if (value === null || value === undefined) return "";
  if (typeof value === "string") {
    const trimmed = value.trim();
    return trimmed;
  }
  try {
    return JSON.stringify(value);
  } catch (_) {
    return "";
  }
}

function ragParseVector(value, expectedDim) {
  let raw = value;
  if (typeof raw === "string") {
    const trimmed = raw.trim();
    if (!trimmed) return [];
    try {
      raw = JSON.parse(trimmed);
    } catch (_) {
      return [];
    }
  }

  if (!Array.isArray(raw)) return [];
  if (raw.length === 0) return [];
  if (expectedDim && raw.length !== expectedDim) return [];

  const vector = [];
  for (let i = 0; i < raw.length; i++) {
    const n = Number(raw[i]);
    if (!isFinite(n)) return [];
    vector.push(n);
  }
  return vector;
}

function ragVectorNorm(vector) {
  if (!Array.isArray(vector) || vector.length === 0) return 0;
  let sum = 0;
  for (let i = 0; i < vector.length; i++) {
    const n = Number(vector[i]);
    if (!isFinite(n)) return 0;
    sum += n * n;
  }
  return Math.sqrt(sum);
}

function ragCosineSimilarity(a, b, aNorm, bNorm) {
  if (!Array.isArray(a) || !Array.isArray(b) || a.length === 0 || b.length === 0) return 0;
  if (a.length !== b.length) return 0;
  const normA = isFinite(aNorm) && aNorm > 0 ? aNorm : ragVectorNorm(a);
  const normB = isFinite(bNorm) && bNorm > 0 ? bNorm : ragVectorNorm(b);
  if (normA <= 0 || normB <= 0) return 0;

  let dot = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
  }
  return dot / (normA * normB);
}

function ragGetFinder(appRef) {
  if (appRef && typeof appRef.findRecordsByFilter === "function") return appRef;
  if (typeof $app !== "undefined" && typeof $app.findRecordsByFilter === "function") return $app;
  return null;
}

function ragFindRecordsByFilter(appRef, collectionName, filterExpr, sortExpr, limit, offset) {
  const finder = ragGetFinder(appRef);
  if (!finder) throw new Error("findRecordsByFilter is unavailable");
  return (
    finder.findRecordsByFilter(
      String(collectionName || "").trim(),
      String(filterExpr || ""),
      String(sortExpr || ""),
      Number(limit) || 50,
      Number(offset) || 0
    ) || []
  );
}

function ragFindOneByFilter(appRef, collectionName, filterExpr, sortExpr) {
  const rows = ragFindRecordsByFilter(appRef, collectionName, filterExpr, sortExpr || "-updated", 1, 0);
  return Array.isArray(rows) && rows.length > 0 ? rows[0] : null;
}

function ragEmbedText(cfg, inputText) {
  const text = ragSafeStr(inputText).trim();
  if (!text) throw new Error("embedText/text is required when embedding is not provided");
  if (!cfg.embeddingApiKey) throw new Error("DASHSCOPE_API_KEY is missing");

  const embedRes = $http.send({
    url: cfg.embeddingUrl,
    method: "POST",
    headers: {
      Authorization: `Bearer ${cfg.embeddingApiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: cfg.embeddingModel,
      input: text,
      dimensions: cfg.embeddingDim,
      encoding_format: "float",
    }),
    timeout: 120,
  });

  if (embedRes.statusCode !== 200) {
    throw new Error(`Embedding API Error (${embedRes.statusCode}): ${embedRes.raw}`);
  }

  const raw = embedRes.json || {};
  const vector = raw.data && raw.data[0] ? raw.data[0].embedding : null;
  const parsed = ragParseVector(vector, cfg.embeddingDim);
  if (parsed.length !== cfg.embeddingDim) {
    throw new Error(`embedding dim mismatch: ${Array.isArray(vector) ? vector.length : 0}`);
  }
  return parsed;
}

function ragFindRecordsByIdsSafe(appRef, collectionName, ids) {
  if (!Array.isArray(ids) || ids.length === 0) return [];
  try {
    if (appRef && typeof appRef.findRecordsByIds === "function") {
      return appRef.findRecordsByIds(collectionName, ids) || [];
    }
  } catch (_) {}
  try {
    if (typeof $app !== "undefined" && typeof $app.findRecordsByIds === "function") {
      return $app.findRecordsByIds(collectionName, ids) || [];
    }
  } catch (_) {}
  return [];
}

routerAdd("POST", "/boox-rag-upsert", (e) => {
  try {
    const appRef = (e && e.app) || (typeof $app !== "undefined" ? $app : null);
    if (!appRef) return e.json(500, { error: "app is unavailable" });

    const cfg = {
      documentsCollection: String($os.getenv("RAG_DOCUMENTS_COLLECTION") || "documents").trim(),
      chunksCollection: String($os.getenv("RAG_CHUNKS_COLLECTION") || "chunks").trim(),
      embeddingsCollection: String($os.getenv("RAG_EMBEDDINGS_COLLECTION") || "embeddings").trim(),
      embeddingApiKey: String($os.getenv("DASHSCOPE_API_KEY") || "").trim(),
      embeddingUrl: String(
        $os.getenv("DASHSCOPE_EMBED_URL") ||
          "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/embeddings"
      ).trim(),
      embeddingModel: String($os.getenv("DASHSCOPE_EMBED_MODEL") || "text-embedding-v4").trim(),
      embeddingDim: parseInt(String($os.getenv("DASHSCOPE_EMBED_DIM") || "1024"), 10) || 1024,
      pbPublicUrl: String($os.getenv("POCKETBASE_PUBLIC_URL") || "")
        .trim()
        .replace(/\/+$/, ""),
    };

    const userId = ragResolveUserId(e, cfg);
    const body = ragParseReqBody(e);

    const documentId = ragSafeStr(body.documentId).trim();
    const title = ragSafeStr(body.title).trim();
    const source = ragSafeStr(body.source).trim();
    const chunkIndexRaw = parseInt(ragSafeStr(body.chunkIndex || "0"), 10);
    const chunkIndex = isNaN(chunkIndexRaw) ? 0 : chunkIndexRaw;
    const chunkText = ragSafeStr(body.chunkText || body.text).trim();
    const embedText = ragSafeStr(body.embedText || chunkText).trim();

    if (!documentId) return e.json(400, { error: "documentId is required" });
    if (!chunkText) return e.json(400, { error: "chunkText (or text) is required" });

    const chunkIdFallback = `${documentId}#${String(chunkIndex)}`;
    const chunkId = ragSafeStr(body.chunkId).trim() || chunkIdFallback;

    let vector = ragParseVector(body.embedding);
    if (vector.length === 0) {
      vector = ragEmbedText(cfg, embedText);
    } else {
      for (let i = 0; i < vector.length; i++) {
        if (!isFinite(vector[i])) return e.json(400, { error: "embedding contains non-finite values" });
      }
    }

    const dimensions = vector.length;
    if (dimensions <= 0) return e.json(400, { error: "embedding is empty" });
    const norm = ragVectorNorm(vector);
    const metadataJson = ragSerializeJsonObject(body.metadata);
    const model = ragSafeStr(body.model).trim() || cfg.embeddingModel;
    const nowTs = Date.now();

    const documentsCollection = appRef.findCollectionByNameOrId(cfg.documentsCollection);
    const chunksCollection = appRef.findCollectionByNameOrId(cfg.chunksCollection);
    const embeddingsCollection = appRef.findCollectionByNameOrId(cfg.embeddingsCollection);

    const docFilter =
      'user = "' + ragEscapeFilterString(userId) + '" && documentId = "' + ragEscapeFilterString(documentId) + '"';
    let docRecord = ragFindOneByFilter(appRef, cfg.documentsCollection, docFilter, "-updated");
    if (!docRecord) docRecord = new Record(documentsCollection);
    docRecord.set("user", userId);
    docRecord.set("documentId", documentId);
    docRecord.set("title", title);
    docRecord.set("source", source);
    docRecord.set("metadataJson", metadataJson);
    docRecord.set("updatedAt", nowTs);
    appRef.save(docRecord);

    const chunkFilter =
      'user = "' + ragEscapeFilterString(userId) + '" && chunkId = "' + ragEscapeFilterString(chunkId) + '"';
    let chunkRecord = ragFindOneByFilter(appRef, cfg.chunksCollection, chunkFilter, "-updated");
    if (!chunkRecord) chunkRecord = new Record(chunksCollection);
    chunkRecord.set("user", userId);
    chunkRecord.set("document", docRecord.id);
    chunkRecord.set("chunkId", chunkId);
    chunkRecord.set("chunkIndex", chunkIndex);
    chunkRecord.set("content", chunkText);
    chunkRecord.set("metadataJson", metadataJson);
    chunkRecord.set("updatedAt", nowTs);
    appRef.save(chunkRecord);

    const embeddingFilter =
      'user = "' + ragEscapeFilterString(userId) + '" && chunkId = "' + ragEscapeFilterString(chunkId) + '"';
    let embeddingRecord = ragFindOneByFilter(appRef, cfg.embeddingsCollection, embeddingFilter, "-updated");
    if (!embeddingRecord) embeddingRecord = new Record(embeddingsCollection);
    embeddingRecord.set("user", userId);
    embeddingRecord.set("document", docRecord.id);
    embeddingRecord.set("chunk", chunkRecord.id);
    embeddingRecord.set("chunkId", chunkId);
    embeddingRecord.set("model", model);
    embeddingRecord.set("dimensions", dimensions);
    embeddingRecord.set("vectorJson", JSON.stringify(vector));
    embeddingRecord.set("norm", norm);
    embeddingRecord.set("metadataJson", metadataJson);
    embeddingRecord.set("updatedAt", nowTs);
    appRef.save(embeddingRecord);

    return e.json(200, {
      ok: true,
      document: { id: docRecord.id, documentId: documentId },
      chunk: { id: chunkRecord.id, chunkId: chunkId, chunkIndex: chunkIndex },
      embedding: { id: embeddingRecord.id, dimensions: dimensions, norm: norm },
    });
  } catch (err) {
    return e.json(500, { error: String(err || "unknown error") });
  }
});

routerAdd("POST", "/boox-rag-search", (e) => {
  try {
    const appRef = (e && e.app) || (typeof $app !== "undefined" ? $app : null);
    if (!appRef) return e.json(500, { error: "app is unavailable" });

    const cfg = {
      documentsCollection: String($os.getenv("RAG_DOCUMENTS_COLLECTION") || "documents").trim(),
      chunksCollection: String($os.getenv("RAG_CHUNKS_COLLECTION") || "chunks").trim(),
      embeddingsCollection: String($os.getenv("RAG_EMBEDDINGS_COLLECTION") || "embeddings").trim(),
      embeddingApiKey: String($os.getenv("DASHSCOPE_API_KEY") || "").trim(),
      embeddingUrl: String(
        $os.getenv("DASHSCOPE_EMBED_URL") ||
          "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/embeddings"
      ).trim(),
      embeddingModel: String($os.getenv("DASHSCOPE_EMBED_MODEL") || "text-embedding-v4").trim(),
      embeddingDim: parseInt(String($os.getenv("DASHSCOPE_EMBED_DIM") || "1024"), 10) || 1024,
      pbPublicUrl: String($os.getenv("POCKETBASE_PUBLIC_URL") || "")
        .trim()
        .replace(/\/+$/, ""),
    };

    const userId = ragResolveUserId(e, cfg);
    const body = ragParseReqBody(e);

    const queryText = ragSafeStr(body.query).trim();
    const topKRaw = parseInt(ragSafeStr(body.topK || body.limit || "5"), 10);
    const topK = Math.max(1, Math.min(50, isNaN(topKRaw) ? 5 : topKRaw));
    const maxCandidatesRaw = parseInt(ragSafeStr(body.maxCandidates || "500"), 10);
    const maxCandidates = Math.max(topK, Math.min(2000, isNaN(maxCandidatesRaw) ? 500 : maxCandidatesRaw));
    const minScoreRaw = Number(body.minScore);
    const minScore = isFinite(minScoreRaw) ? minScoreRaw : -1;
    const includeContent = body.includeContent === false ? false : true;
    const documentId = ragSafeStr(body.documentId).trim();

    let queryVector = ragParseVector(body.embedding);
    if (queryVector.length === 0) {
      if (!queryText) return e.json(400, { error: "query or embedding is required" });
      queryVector = ragEmbedText(cfg, queryText);
    }
    const queryNorm = ragVectorNorm(queryVector);
    if (queryNorm <= 0) return e.json(400, { error: "query embedding norm is zero" });

    let embeddingFilter = 'user = "' + ragEscapeFilterString(userId) + '"';
    if (documentId) {
      const docFilter =
        'user = "' + ragEscapeFilterString(userId) + '" && documentId = "' + ragEscapeFilterString(documentId) + '"';
      const docRecord = ragFindOneByFilter(appRef, cfg.documentsCollection, docFilter, "-updated");
      if (!docRecord) return e.json(200, { results: [] });
      embeddingFilter += ' && document = "' + ragEscapeFilterString(docRecord.id) + '"';
    }

    const candidates = ragFindRecordsByFilter(
      appRef,
      cfg.embeddingsCollection,
      embeddingFilter,
      "-updated",
      maxCandidates,
      0
    );
    if (!Array.isArray(candidates) || candidates.length === 0) {
      return e.json(200, { results: [] });
    }

    const chunkIdSet = {};
    const docIdSet = {};
    const chunkIds = [];
    const docIds = [];

    for (let i = 0; i < candidates.length; i++) {
      const rec = candidates[i];
      const chunkRel = ragToRelId(rec.get("chunk")).trim();
      const docRel = ragToRelId(rec.get("document")).trim();
      if (chunkRel && !chunkIdSet[chunkRel]) {
        chunkIdSet[chunkRel] = true;
        chunkIds.push(chunkRel);
      }
      if (docRel && !docIdSet[docRel]) {
        docIdSet[docRel] = true;
        docIds.push(docRel);
      }
    }

    const chunkRecords = ragFindRecordsByIdsSafe(appRef, cfg.chunksCollection, chunkIds);
    const docRecords = ragFindRecordsByIdsSafe(appRef, cfg.documentsCollection, docIds);

    const chunkMap = {};
    const docMap = {};
    for (let i = 0; i < chunkRecords.length; i++) chunkMap[chunkRecords[i].id] = chunkRecords[i];
    for (let i = 0; i < docRecords.length; i++) docMap[docRecords[i].id] = docRecords[i];

    const scored = [];
    for (let i = 0; i < candidates.length; i++) {
      const rec = candidates[i];
      const vec = ragParseVector(rec.get("vectorJson"), queryVector.length);
      if (vec.length !== queryVector.length) continue;

      const recNormRaw = Number(rec.get("norm"));
      const recNorm = isFinite(recNormRaw) && recNormRaw > 0 ? recNormRaw : ragVectorNorm(vec);
      const score = ragCosineSimilarity(queryVector, vec, queryNorm, recNorm);
      if (!isFinite(score) || score < minScore) continue;

      const chunkRel = ragToRelId(rec.get("chunk")).trim();
      const docRel = ragToRelId(rec.get("document")).trim();
      const chunk = chunkMap[chunkRel] || null;
      const doc = docMap[docRel] || null;
      const chunkMetadata = chunk ? ragParseJsonObject(chunk.get("metadataJson")) : null;
      const embeddingMetadata = ragParseJsonObject(rec.get("metadataJson"));

      scored.push({
        score: score,
        reason: "Cosine similarity",
        documentId: doc ? ragSafeStr(doc.get("documentId")) : "",
        documentTitle: doc ? ragSafeStr(doc.get("title")) : "",
        source: doc ? ragSafeStr(doc.get("source")) : "",
        chunkId: ragSafeStr(rec.get("chunkId")),
        chunkIndex: chunk ? Number(chunk.get("chunkIndex")) || 0 : 0,
        chunkText: includeContent && chunk ? ragSafeStr(chunk.get("content")) : "",
        metadata: chunkMetadata || embeddingMetadata || null,
        embeddingId: rec.id,
      });
    }

    scored.sort((a, b) => b.score - a.score);
    return e.json(200, { results: scored.slice(0, topK), candidateCount: scored.length });
  } catch (err) {
    return e.json(500, { error: String(err || "unknown error") });
  }
});

// ============================================================
// 6) Mail queue sender + custom route
//    Keep in main.pb.js for PocketBase versions that only load main.
// ============================================================
var MAIL_QUEUE_NAME = "mail_queue";
var MAIL_DAILY_LIMIT = 20;

function toRelId(v) {
  if (Array.isArray(v)) return String(v[0] || "");
  if (v && typeof v === "object") return String(v.id || "");
  return String(v || "");
}

function escapeFilterString(v) {
  return String(v || "")
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"');
}

function getTodayRangeMs() {
  var now = new Date();
  var start = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  return {
    start: start,
    end: start + 24 * 60 * 60 * 1000,
    startIso: new Date(start).toISOString(),
    endIso: new Date(start + 24 * 60 * 60 * 1000).toISOString(),
  };
}

function getRecordCreatedAtMs(record) {
  var n = Number(record.get("createdAt"));
  if (isFinite(n) && n > 0) return n;
  var created = String(record.get("created") || "").trim();
  var parsed = Date.parse(created);
  return isFinite(parsed) ? parsed : 0;
}

function normalizeMailStatus(v) {
  return String(v || "")
    .trim()
    .toLowerCase();
}

function deleteQueueRecordQuietly(app, record, reason) {
  try {
    app.delete(record);
    return true;
  } catch (err) {
    try {
      app.logger().error(
        "mail_queue delete failed",
        "recordId",
        String((record && record.id) || ""),
        "reason",
        String(reason || ""),
        "error",
        String(err || "")
      );
    } catch (_) {}
    return false;
  }
}

function listTodayMailQueueRecords(app, userId) {
  var dayRange = getTodayRangeMs();
  var createdAtFilter =
    'user = "' +
    escapeFilterString(userId) +
    '" && createdAt >= ' +
    dayRange.start +
    " && createdAt < " +
    dayRange.end;
  var createdFilter =
    'user = "' +
    escapeFilterString(userId) +
    '" && created >= "' +
    dayRange.startIso +
    '" && created < "' +
    dayRange.endIso +
    '"';

  var records = [];
  var finder = null;
  if (typeof app.findRecordsByFilter === "function") {
    finder = app;
  } else if (typeof $app !== "undefined" && typeof $app.findRecordsByFilter === "function") {
    finder = $app;
  }
  if (!finder) {
    throw new Error("findRecordsByFilter is unavailable");
  }

  try {
    records = finder.findRecordsByFilter(
      typeof MAIL_QUEUE_NAME !== "undefined" && String(MAIL_QUEUE_NAME || "").trim()
        ? String(MAIL_QUEUE_NAME || "").trim()
        : "mail_queue",
      createdAtFilter,
      "createdAt",
      200,
      0
    );
  } catch (_) {
    // Backward-compatible fallback when custom numeric `createdAt` field is missing.
    records = finder.findRecordsByFilter(
      typeof MAIL_QUEUE_NAME !== "undefined" && String(MAIL_QUEUE_NAME || "").trim()
        ? String(MAIL_QUEUE_NAME || "").trim()
        : "mail_queue",
      createdFilter,
      "created",
      200,
      0
    );
  }

  return Array.isArray(records) ? records : [];
}

function collectionHasField(collection, fieldName) {
  if (!collection || !fieldName) return false;
  var fields = collection.fields || collection.schema || [];
  if (!Array.isArray(fields)) return false;
  for (var i = 0; i < fields.length; i++) {
    var f = fields[i];
    var name = String((f && f.name) || "").trim();
    if (name === fieldName) return true;
  }
  return false;
}

function enforceMailDailyLimit(app, userId, currentRecordId) {
  var dailyLimit =
    typeof MAIL_DAILY_LIMIT === "number" && isFinite(MAIL_DAILY_LIMIT)
      ? MAIL_DAILY_LIMIT
      : 2;
  var result = {
    limit: dailyLimit,
    sentCount: 0,
    pendingCount: 0,
    availableSlots: dailyLimit,
    blocked: false,
    removedIds: [],
  };

  if (!String(userId || "").trim()) {
    result.blocked = true;
    return result;
  }

  var allRecords = listTodayMailQueueRecords(app, userId);
  var pending = [];

  for (var i = 0; i < allRecords.length; i++) {
    var r = allRecords[i];
    var status = normalizeMailStatus(r.get("status"));

    if (status === "sent") {
      result.sentCount += 1;
      continue;
    }

    if (
      status === "failed" ||
      status === "cancelled" ||
      status === "canceled" ||
      status === "dropped"
    ) {
      continue;
    }

    pending.push(r);
  }

  var availableSlots = dailyLimit - result.sentCount;
  result.availableSlots = availableSlots;

  pending.sort(function (a, b) {
    var diff = getRecordCreatedAtMs(a) - getRecordCreatedAtMs(b);
    if (diff !== 0) return diff;
    return String(a.id || "").localeCompare(String(b.id || ""));
  });

  var keepPendingCount = availableSlots > 0 ? availableSlots : 0;
  var removedCount = 0;
  for (var j = keepPendingCount; j < pending.length; j++) {
    if (deleteQueueRecordQuietly(app, pending[j], "daily_limit_overflow")) {
      removedCount += 1;
      result.removedIds.push(String(pending[j].id || ""));
    }
  }

  result.pendingCount = Math.max(0, pending.length - removedCount);

  if (availableSlots <= 0) {
    result.blocked = true;
  }

  var currentId = String(currentRecordId || "").trim();
  if (currentId) {
    for (var k = 0; k < result.removedIds.length; k++) {
      if (result.removedIds[k] === currentId) {
        result.blocked = true;
        break;
      }
    }
  }

  return result;
}

function parseRequestBody(reqInfo) {
  if (!reqInfo || reqInfo.body === null || reqInfo.body === undefined) {
    return {};
  }
  if (typeof reqInfo.body === "string") {
    try {
      return JSON.parse(reqInfo.body);
    } catch (_) {
      return {};
    }
  }
  return reqInfo.body;
}

function resolveRouteAuthRecord(e, reqInfo) {
  if (e && e.auth) return e.auth;
  if (reqInfo && reqInfo.auth) return reqInfo.auth;
  if (reqInfo && reqInfo.authRecord) return reqInfo.authRecord;
  return null;
}

function hasAuthorizationHeader(reqInfo) {
  if (!reqInfo || !reqInfo.headers) return false;
  var headers = reqInfo.headers;
  var authHeader =
    headers.Authorization ||
    headers.authorization ||
    headers.AUTHORIZATION;
  if (Array.isArray(authHeader)) return authHeader.length > 0 && String(authHeader[0]).trim() !== "";
  return String(authHeader || "").trim() !== "";
}

function handleBooxMailSendRoute(e) {
  try {
    var reqInfo = null;
    try {
      reqInfo = e.requestInfo();
    } catch (_) {
      reqInfo = null;
    }

    var authRecord = null;
    if (e && e.auth) authRecord = e.auth;
    else if (reqInfo && reqInfo.auth) authRecord = reqInfo.auth;
    else if (reqInfo && reqInfo.authRecord) authRecord = reqInfo.authRecord;
    var userId = String(authRecord && authRecord.id ? authRecord.id : "").trim();
    if (!userId) {
      var hasAuthHeader = false;
      if (reqInfo && reqInfo.headers) {
        var headers = reqInfo.headers;
        var authHeader =
          headers.Authorization ||
          headers.authorization ||
          headers.AUTHORIZATION;
        if (Array.isArray(authHeader)) {
          hasAuthHeader = authHeader.length > 0 && String(authHeader[0]).trim() !== "";
        } else {
          hasAuthHeader = String(authHeader || "").trim() !== "";
        }
      }
      try {
        e.app.logger().warn(
          "boox-mail-send unauthorized",
          "hasAuthorizationHeader",
          hasAuthHeader
        );
      } catch (_) {}
      return e.json(401, {
        error: "Unauthorized: missing or invalid auth token",
      });
    }

    var bodyObj = {};
    if (reqInfo && reqInfo.body !== null && reqInfo.body !== undefined) {
      if (typeof reqInfo.body === "string") {
        try {
          bodyObj = JSON.parse(reqInfo.body);
        } catch (_) {
          bodyObj = {};
        }
      } else {
        bodyObj = reqInfo.body;
      }
    }
    var toEmail = String(bodyObj.toEmail || "").trim();
    var subject = String(bodyObj.subject || "").trim();
    var body = String(bodyObj.body || "");
    if (!subject) subject = "AI Note Daily Summary";

    if (!toEmail) return e.json(400, { error: "toEmail is required" });
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(toEmail)) {
      return e.json(400, { error: "toEmail is invalid" });
    }
    if (!String(body).trim()) return e.json(400, { error: "body is required" });

    var dailyLimit =
      typeof MAIL_DAILY_LIMIT === "number" && isFinite(MAIL_DAILY_LIMIT)
        ? MAIL_DAILY_LIMIT
        : 2;
    var quota = {
      availableSlots: dailyLimit,
      pendingCount: 0,
      sentCount: 0,
    };
    if (typeof enforceMailDailyLimit === "function") {
      quota = enforceMailDailyLimit(e.app, userId, "");
    }
    var queueSlotsLeft = quota.availableSlots - quota.pendingCount;
    if (queueSlotsLeft <= 0) {
      return e.json(429, {
        error: "Daily email limit reached (max 2/day).",
        limit: dailyLimit,
        sentToday: quota.sentCount,
        pendingToday: quota.pendingCount,
      });
    }

    var mailQueueName =
      typeof MAIL_QUEUE_NAME !== "undefined" && String(MAIL_QUEUE_NAME || "").trim()
        ? String(MAIL_QUEUE_NAME || "").trim()
        : "mail_queue";
    var collection = e.app.findCollectionByNameOrId(mailQueueName);
    var record = new Record(collection);
    record.set("user", userId);
    record.set("toEmail", toEmail);
    record.set("subject", subject);
    record.set("body", body);
    record.set("category", "ai_note_daily_summary");
    record.set("status", "pending");
    record.set("error", "");
    var routeFields = (collection && (collection.fields || collection.schema)) || [];
    var routeHasCreatedAt = false;
    if (Array.isArray(routeFields)) {
      for (var fidx = 0; fidx < routeFields.length; fidx++) {
        var rf = routeFields[fidx];
        var rn = String((rf && rf.name) || "").trim();
        if (rn === "createdAt") {
          routeHasCreatedAt = true;
          break;
        }
      }
    }
    if (routeHasCreatedAt) {
      record.set("createdAt", Date.now());
    }

    e.app.save(record);

    return e.json(200, {
      ok: true,
      mode: "queued_via_custom_route",
      queueId: record.id,
    });
  } catch (err) {
    return e.json(500, { error: String(err || "unknown error") });
  }
}

onRecordCreate(function (e) {
  e.next();
  try {
    var dailyLimit =
      typeof MAIL_DAILY_LIMIT === "number" && isFinite(MAIL_DAILY_LIMIT)
        ? MAIL_DAILY_LIMIT
        : 2;
    var rawUser = e.record.get("user");
    var userId = "";
    if (Array.isArray(rawUser)) userId = String(rawUser[0] || "");
    else if (rawUser && typeof rawUser === "object") userId = String(rawUser.id || "");
    else userId = String(rawUser || "");
    userId = userId.trim();
    if (!userId) {
      e.record.set("status", "failed");
      e.record.set("error", "user is empty");
      e.app.save(e.record);
      return;
    }

    var category = String(e.record.get("category") || "")
      .trim()
      .toLowerCase();
    var bypassDailyLimit = category === "ai_note_daily_report_auto";
    if (!bypassDailyLimit) {
      var quota = {
        blocked: false,
        availableSlots: dailyLimit,
        pendingCount: 0,
        sentCount: 0,
      };
      if (typeof enforceMailDailyLimit === "function") {
        quota = enforceMailDailyLimit(e.app, userId, String((e.record && e.record.id) || ""));
      }
      if (quota.blocked) {
        if (typeof deleteQueueRecordQuietly === "function") {
          deleteQueueRecordQuietly(e.app, e.record, "daily_limit_blocked");
        } else {
          try {
            e.app.delete(e.record);
          } catch (_) {}
        }
        return;
      }
    }

    var toEmail = String(e.record.get("toEmail") || "").trim();
    var subject = String(e.record.get("subject") || "").trim();
    var body = String(e.record.get("body") || "");
    if (!subject) subject = "AI Note Daily Summary";

    if (!toEmail) {
      e.record.set("status", "failed");
      e.record.set("error", "toEmail is empty");
      e.app.save(e.record);
      return;
    }

    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(toEmail)) {
      e.record.set("status", "failed");
      e.record.set("error", "invalid toEmail: " + toEmail);
      e.app.save(e.record);
      return;
    }

    var settings = e.app.settings();
    var senderAddress = String(
      settings && settings.meta && settings.meta.senderAddress
        ? settings.meta.senderAddress
        : ""
    ).trim();
    var senderName = String(
      settings && settings.meta && settings.meta.senderName
        ? settings.meta.senderName
        : ""
    ).trim();

    if (!senderAddress) {
      e.record.set("status", "failed");
      e.record.set(
        "error",
        "SMTP senderAddress is empty (PocketBase Settings -> Mail settings)"
      );
      e.app.save(e.record);
      return;
    }

    var escapedBody = body
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/\"/g, "&quot;")
      .replace(/'/g, "&#39;");

    var message = new MailerMessage({
      from: {
        address: senderAddress,
        name: senderName || "BooxReader",
      },
      to: [{ address: toEmail }],
      subject: subject,
      text: body,
      html:
        '<pre style="white-space:pre-wrap;font-family:monospace;">' +
        escapedBody +
        "</pre>",
    });

    e.app.newMailClient().send(message);
    e.record.set("status", "sent");
    e.record.set("error", "");
    e.app.save(e.record);
  } catch (err) {
    var errText = String(err || "").trim();
    e.app.logger().error(
      "mail_queue send failed",
      "recordId",
      String((e.record && e.record.id) || ""),
      "error",
      errText
    );
    try {
      e.record.set("status", "failed");
      e.record.set("error", errText);
      e.app.save(e.record);
    } catch (_) {}
  }
},
typeof MAIL_QUEUE_NAME !== "undefined" && String(MAIL_QUEUE_NAME || "").trim()
  ? String(MAIL_QUEUE_NAME || "").trim()
  : "mail_queue");

routerAdd("POST", "/boox-mail-send", handleBooxMailSendRoute);

// Alias route for deployments/proxies that only expose /api/* paths.
routerAdd("POST", "/api/boox-mail-send", handleBooxMailSendRoute);

// ============================================================
// 6) Daily AI note report scheduler (self-contained)
//    Defaults:
//      DAILY_AI_NOTE_REPORT_CRON = "5 0 * * *" (00:05 UTC every day)
//      DAILY_AI_NOTE_REPORT_MAX_NOTES = 30
// ============================================================
cronAdd(
  "daily_ai_note_report",
  String($os.getenv("DAILY_AI_NOTE_REPORT_CRON") || "5 0 * * *").trim(),
  function () {
    try {
      var queueName =
        typeof MAIL_QUEUE_NAME !== "undefined" && String(MAIL_QUEUE_NAME || "").trim()
          ? String(MAIL_QUEUE_NAME || "").trim()
          : "mail_queue";
      var maxNotes = parseInt(String($os.getenv("DAILY_AI_NOTE_REPORT_MAX_NOTES") || "30"), 10);
      if (!isFinite(maxNotes) || maxNotes <= 0) maxNotes = 30;
      if (maxNotes > 100) maxNotes = 100;

      var now = new Date();
      var dayEnd = now;
      var dayStart = new Date(now.getTime() - 24 * 60 * 60 * 1000);
      var dayLabel = dayStart.toISOString().slice(0, 10) + " to " + dayEnd.toISOString().slice(0, 10);
      var startIso = dayStart.toISOString();
      var endIso = dayEnd.toISOString();

      var esc = function (v) {
        return String(v || "")
          .replace(/\\/g, "\\\\")
          .replace(/"/g, '\\"');
      };

      // De-dup per user/day by subject+category already in queue.
      var existingMap = {};
      var existingFilter =
        'category = "ai_note_daily_report_auto" && subject ~ "AI Note Daily Report (' +
        esc(dayLabel) +
        ')"';
      var existing = [];
      try {
        existing = $app.findRecordsByFilter(queueName, existingFilter, "-created", 500, 0);
      } catch (_) {
        existing = [];
      }
      for (var ei = 0; ei < existing.length; ei++) {
        var er = existing[ei];
        var eu = er.get("user");
        var eid = "";
        if (Array.isArray(eu)) eid = String(eu[0] || "");
        else if (eu && typeof eu === "object") eid = String(eu.id || "");
        else eid = String(eu || "");
        eid = eid.trim();
        if (eid) existingMap[eid] = true;
      }

      var users = [];
      try {
        users = $app.findRecordsByFilter("users", "", "id", 10000, 0);
      } catch (_) {
        users = [];
      }
      var stats = {
        users: Array.isArray(users) ? users.length : 0,
        queued: 0,
        skippedExisting: 0,
        skippedInvalidEmail: 0,
        skippedNoNotes: 0,
        skippedNoCollection: 0,
        userErrors: 0,
      };

      for (var i = 0; i < users.length; i++) {
        try {
          var u = users[i];
          var userId = String((u && u.id) || "").trim();
          if (!userId || existingMap[userId]) {
            stats.skippedExisting += 1;
            continue;
          }

          var toEmail = String((u && u.get && u.get("email")) || "").trim();
          if (!toEmail || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(toEmail)) {
            stats.skippedInvalidEmail += 1;
            continue;
          }

          var noteFilter =
            'user = "' +
            esc(userId) +
            '" && status = "done" && created >= "' +
            esc(startIso) +
            '" && created < "' +
            esc(endIso) +
            '"';
          var notes = [];
          try {
            notes = $app.findRecordsByFilter("ai_notes", noteFilter, "-created", maxNotes, 0);
          } catch (_) {
            notes = [];
          }
          if (!Array.isArray(notes) || notes.length === 0) {
            stats.skippedNoNotes += 1;
            continue;
          }

          var lines = [];
          lines.push("AI Note Daily Report (" + dayLabel + ")");
          lines.push("");
          lines.push("Total notes: " + String(notes.length));
          lines.push("");
          for (var n = 0; n < notes.length; n++) {
            var r = notes[n];
            var title = String((r && r.get && r.get("bookTitle")) || "").trim();
            var originalText = String((r && r.get && r.get("originalText")) || "").trim();
            var aiResponse = String((r && r.get && r.get("aiResponse")) || "").trim();
            if (!title) title = "Untitled";
            if (originalText.length > 180) originalText = originalText.slice(0, 180) + "...";
            if (aiResponse.length > 260) aiResponse = aiResponse.slice(0, 260) + "...";
            lines.push((n + 1) + ". " + title);
            if (originalText) lines.push("   Note: " + originalText);
            if (aiResponse) lines.push("   AI: " + aiResponse);
            lines.push("");
          }
          var body = lines.join("\n").trim();

          var collection = null;
          try {
            collection = $app.findCollectionByNameOrId(queueName);
          } catch (_) {
            collection = null;
          }
          if (!collection) {
            stats.skippedNoCollection += 1;
            continue;
          }

          var q = new Record(collection);
          q.set("user", userId);
          q.set("toEmail", toEmail);
          q.set("subject", "AI Note Daily Report (" + dayLabel + ")");
          q.set("body", body);
          q.set("category", "ai_note_daily_report_auto");
          q.set("status", "pending");
          q.set("error", "");

          var fields = (collection && (collection.fields || collection.schema)) || [];
          var hasCreatedAt = false;
          if (Array.isArray(fields)) {
            for (var f = 0; f < fields.length; f++) {
              var fn = String((fields[f] && fields[f].name) || "").trim();
              if (fn === "createdAt") {
                hasCreatedAt = true;
                break;
              }
            }
          }
          if (hasCreatedAt) q.set("createdAt", Date.now());

          $app.save(q);
          stats.queued += 1;
        } catch (_) {
          stats.userErrors += 1;
        }
      }
      try {
        $app.logger().info(
          "daily_ai_note_report finished",
          "users",
          String(stats.users),
          "queued",
          String(stats.queued),
          "skippedExisting",
          String(stats.skippedExisting),
          "skippedInvalidEmail",
          String(stats.skippedInvalidEmail),
          "skippedNoNotes",
          String(stats.skippedNoNotes),
          "skippedNoCollection",
          String(stats.skippedNoCollection),
          "userErrors",
          String(stats.userErrors),
          "rangeStart",
          startIso,
          "rangeEnd",
          endIso
        );
      } catch (_) {}
    } catch (err) {
      try {
        $app.logger().error("daily_ai_note_report failed", "error", String(err || ""));
      } catch (_) {}
    }
  }
);
