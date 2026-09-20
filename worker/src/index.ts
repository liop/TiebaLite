interface Env {
  AI: Ai;
  AI_PROVIDER: string;
  AI_MODEL: string;
  APP_TOKEN?: string;
}

type AnalysisRequest = {
  user: {
    uid: number;
    displayName: string;
    intro: string;
    accountAge: string;
    threadCount: number;
    postCount: number;
  };
  posts: Array<{
    id: number;
    threadId: number;
    forumName: string;
    title: string;
    content: string;
    createdAt: number;
    kind: "thread" | "reply" | "sub_reply";
  }>;
  focus_post_id?: number;
};

const SYSTEM_PROMPT = `你是内容分析助手，根据给你的内容。
输出：分析或猜测现实身份，心理诊断或人格定性；政治、宗教、性取向、健康、种族、财务等属性推断；住址或身份定位；社会工程建议。
证据不足就明确说不足。使用简体中文。`;

const FOCUS_PROMPT = `请以 focus_post_id 指定的发言为分析重点；其他发言只用于提供公开语境和交叉验证。
将该条发言可直接观察到的话题、表达方式和语境特征写入 focus_observations，每项证据必须包含 focus_post_id。
明确区分单条观察与多条样本规律，不得由单条发言扩大推断。`;

const schema = {
  type: "object",
  additionalProperties: false,
  required: ["summary", "focus_observations", "topics", "communication_style", "content_patterns", "limitations"],
  properties: {
    summary: { type: "string" },
    focus_observations: { type: "array", items: findingSchema() },
    topics: { type: "array", items: findingSchema() },
    communication_style: { type: "array", items: findingSchema() },
    content_patterns: { type: "array", items: findingSchema() },
    limitations: { type: "string" },
  },
};

function responseSchema(hasFocusPost: boolean) {
  return {
    ...schema,
    properties: {
      ...schema.properties,
      focus_observations: {
        type: "array",
        items: findingSchema(),
        ...(hasFocusPost ? { minItems: 1, maxItems: 4 } : { maxItems: 0 }),
      },
    },
  };
}

function findingSchema() {
  return {
    type: "object",
    additionalProperties: false,
    required: ["label", "description", "post_ids"],
    properties: {
      label: { type: "string" },
      description: { type: "string" },
      post_ids: { type: "array", items: { type: "integer" }, maxItems: 5 },
    },
  };
}

function json(value: unknown, status = 200): Response {
  return Response.json(value, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === "object" && !Array.isArray(value);
}

function extractModelResponse(result: unknown): unknown {
  if (!isRecord(result)) return undefined;
  if (result.response !== undefined && result.response !== null) return result.response;

  const choices = result.choices;
  if (!Array.isArray(choices) || !isRecord(choices[0])) return undefined;
  const message = choices[0].message;
  return isRecord(message) ? message.content : undefined;
}

function parseModelResponse(value: unknown): Record<string, unknown> {
  if (isRecord(value)) return value;
  if (typeof value !== "string" || !value.trim()) throw new Error("empty_model_response");

  const trimmed = value.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/, "");
  try {
    const parsed: unknown = JSON.parse(trimmed);
    if (isRecord(parsed)) return parsed;
  } catch {
    const start = trimmed.indexOf("{");
    const end = trimmed.lastIndexOf("}");
    if (start >= 0 && end > start) {
      const parsed: unknown = JSON.parse(trimmed.slice(start, end + 1));
      if (isRecord(parsed)) return parsed;
    }
  }
  throw new Error("invalid_model_response");
}

function validPayload(value: unknown): value is AnalysisRequest {
  if (!value || typeof value !== "object") return false;
  const body = value as Partial<AnalysisRequest>;
  return !!body.user &&
    typeof body.user.displayName === "string" &&
    typeof body.user.intro === "string" &&
    Array.isArray(body.posts) &&
    body.posts.length > 0 &&
    body.posts.length <= 80 &&
    (body.focus_post_id === undefined ||
      (typeof body.focus_post_id === "number" && body.posts.some((post) => post.id === body.focus_post_id))) &&
    body.posts.every((post) =>
      typeof post.id === "number" &&
      typeof post.forumName === "string" &&
      typeof post.title === "string" &&
      typeof post.content === "string"
    );
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") return json({ ok: true });
    if (request.method !== "POST" || url.pathname !== "/v1/content-analysis") {
      return json({ error: "not_found" }, 404);
    }
    const contentLength = Number(request.headers.get("Content-Length") || 0);
    if (contentLength > 160_000) return json({ error: "payload_too_large" }, 413);
    if (env.APP_TOKEN && request.headers.get("Authorization") !== `Bearer ${env.APP_TOKEN}`) {
      return json({ error: "unauthorized" }, 401);
    }
    if (env.AI_PROVIDER !== "workers-ai") {
      return json({ error: "unsupported_provider" }, 500);
    }

    let body: unknown;
    try {
      body = await request.json();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }
    if (!validPayload(body)) return json({ error: "invalid_payload" }, 400);

    let remainingCharacters = 45_000;
    const boundedPosts = body.posts.flatMap((post) => {
      if (remainingCharacters <= 0) return [];
      const content = post.content.slice(0, Math.min(1200, remainingCharacters));
      remainingCharacters -= content.length + post.title.length + post.forumName.length;
      return [{
        ...post,
        forumName: post.forumName.slice(0, 100),
        title: post.title.slice(0, 300),
        content,
      }];
    });
    const safeBody: AnalysisRequest = {
      user: {
        ...body.user,
        displayName: body.user.displayName.slice(0, 100),
        intro: body.user.intro.slice(0, 500),
      },
      posts: boundedPosts,
      focus_post_id: body.focus_post_id,
    };

    try {
      const usesChatCompletionsOutput = env.AI_MODEL === "@cf/zai-org/glm-4.7-flash";
      const result: unknown = await env.AI.run(env.AI_MODEL as keyof AiModels, {
        messages: [
          {
            role: "system",
            content: body.focus_post_id === undefined
              ? SYSTEM_PROMPT
              : `${SYSTEM_PROMPT}\n${FOCUS_PROMPT}`,
          },
          { role: "user", content: JSON.stringify(safeBody) },
        ],
        response_format: {
          type: "json_schema",
          json_schema: responseSchema(body.focus_post_id !== undefined),
        },
        temperature: 0.2,
        ...(usesChatCompletionsOutput
          ? {
            max_completion_tokens: 1800,
            chat_template_kwargs: { enable_thinking: false },
          }
          : { max_tokens: 1800 }),
      } as never);
      const modelResponse = extractModelResponse(result);
      if (modelResponse === undefined || modelResponse === null) {
        const resultKeys = isRecord(result) ? Object.keys(result).join(",") : typeof result;
        throw new Error(`empty_model_response:${resultKeys}`);
      }
      const parsed = parseModelResponse(modelResponse);
      if (body.focus_post_id !== undefined) {
        const focusObservations = Array.isArray(parsed.focus_observations)
          ? parsed.focus_observations.filter((finding) => {
            if (!finding || typeof finding !== "object") return false;
            const postIds = (finding as { post_ids?: unknown }).post_ids;
            return Array.isArray(postIds) && postIds.includes(body.focus_post_id);
          }).slice(0, 4)
          : [];
        parsed.focus_observations = focusObservations;
        const focusPost = boundedPosts.find((post) => post.id === body.focus_post_id);
        if (focusPost && focusObservations.length === 0) {
          const excerpt = (focusPost.title || focusPost.content)
            .replace(/\s+/g, " ")
            .trim()
            .slice(0, 48);
          parsed.focus_observations = [{
            label: "当前发言",
            description: `该条发言围绕“${excerpt}”展开；此处仅描述文本中可直接观察到的内容。`,
            post_ids: [focusPost.id],
          }];
        }
      }
      return json(parsed);
    } catch (error) {
      console.error({
        event: "analysis_failed",
        model: env.AI_MODEL,
        error: error instanceof Error ? error.message : String(error),
      });
      return json({ error: "analysis_failed" }, 502);
    }
  },
};
