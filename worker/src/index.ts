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
    kind: "thread" | "reply";
  }>;
};

const SYSTEM_PROMPT = `你是公开内容分析助手。只分析输入中可直接观察到的文本，不分析或猜测现实身份。
允许输出：常见话题、用词和表达风格、可量化的内容规律，并引用输入中的帖子 ID。
禁止输出：心理诊断或人格定性；政治、宗教、性取向、健康、种族、财务等敏感属性推断；住址或身份定位；欺骗、套话、操纵、骚扰或社会工程建议。
结论必须使用“公开内容显示/样本中出现”等限定语。证据不足就明确说不足。使用简体中文。`;

const schema = {
  type: "object",
  additionalProperties: false,
  required: ["summary", "topics", "communication_style", "content_patterns", "limitations"],
  properties: {
    summary: { type: "string" },
    topics: { type: "array", items: findingSchema() },
    communication_style: { type: "array", items: findingSchema() },
    content_patterns: { type: "array", items: findingSchema() },
    limitations: { type: "string" },
  },
};

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

function validPayload(value: unknown): value is AnalysisRequest {
  if (!value || typeof value !== "object") return false;
  const body = value as Partial<AnalysisRequest>;
  return !!body.user &&
    typeof body.user.displayName === "string" &&
    typeof body.user.intro === "string" &&
    Array.isArray(body.posts) &&
    body.posts.length > 0 &&
    body.posts.length <= 80 &&
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
    };

    try {
      const result = await env.AI.run(env.AI_MODEL as keyof AiModels, {
        messages: [
          { role: "system", content: SYSTEM_PROMPT },
          { role: "user", content: JSON.stringify(safeBody) },
        ],
        response_format: {
          type: "json_schema",
          json_schema: schema,
        },
        temperature: 0.2,
        max_tokens: 1800,
      } as never) as { response?: string | Record<string, unknown> };
      if (!result.response) throw new Error("empty_model_response");
      return json(
        typeof result.response === "string" ? JSON.parse(result.response) : result.response,
      );
    } catch (error) {
      console.error("analysis_failed", error);
      return json({ error: "analysis_failed" }, 502);
    }
  },
};
